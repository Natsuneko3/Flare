package dev.dimension.flare.ui.presenter.manga

import androidx.compose.runtime.snapshots.Snapshot
import androidx.room3.Room
import app.cash.molecule.RecompositionMode
import app.cash.molecule.moleculeFlow
import dev.dimension.flare.common.Cacheable
import dev.dimension.flare.common.PlatformDispatchers
import dev.dimension.flare.createTestFileSystem
import dev.dimension.flare.createTestRootPath
import dev.dimension.flare.data.database.cache.CacheDatabase
import dev.dimension.flare.data.database.createDatabaseDriver
import dev.dimension.flare.data.datasource.microblog.DataSourceCapabilityProvider
import dev.dimension.flare.data.datasource.microblog.DataSourceCapabilitySet
import dev.dimension.flare.data.datasource.microblog.MicroblogDataSource
import dev.dimension.flare.data.datasource.microblog.ProfileTab
import dev.dimension.flare.data.datasource.microblog.datasource.MangaDataSource
import dev.dimension.flare.data.datasource.microblog.paging.RemoteLoader
import dev.dimension.flare.data.datastore.AppDataStore
import dev.dimension.flare.data.datastore.model.AppSettings
import dev.dimension.flare.data.io.OkioFileStorage
import dev.dimension.flare.data.model.ProxyRef
import dev.dimension.flare.data.repository.AccountMicroblogDataSource
import dev.dimension.flare.data.repository.AccountService
import dev.dimension.flare.data.repository.MangaProgressRepository
import dev.dimension.flare.data.translation.image.ImageTranslationRepository
import dev.dimension.flare.deleteTestRootPath
import dev.dimension.flare.di.startKoin
import dev.dimension.flare.di.testSingle
import dev.dimension.flare.memoryDatabaseBuilder
import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.shared.image.JvmImageCompressor
import dev.dimension.flare.ui.model.ClickEvent
import dev.dimension.flare.ui.model.MangaReadingDirection
import dev.dimension.flare.ui.model.UiAccount
import dev.dimension.flare.ui.model.UiHashtag
import dev.dimension.flare.ui.model.UiMangaChapter
import dev.dimension.flare.ui.model.UiMangaChapterGroup
import dev.dimension.flare.ui.model.UiMangaDetail
import dev.dimension.flare.ui.model.UiMangaPage
import dev.dimension.flare.ui.model.UiMangaStatus
import dev.dimension.flare.ui.model.UiMedia
import dev.dimension.flare.ui.model.UiProfile
import dev.dimension.flare.ui.model.UiState
import dev.dimension.flare.ui.model.UiText
import dev.dimension.flare.ui.model.UiTimelineV2
import dev.dimension.flare.ui.render.toUi
import dev.dimension.flare.ui.render.toUiPlainText
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.KSerializer
import okio.FileSystem
import okio.Path.Companion.toPath
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class MangaReaderPresenterTest {
    private val accountKey = MicroBlogKey("reader", "manga.example")
    private val accountType = AccountType.Specific(accountKey)
    private val mangaKey = MicroBlogKey("series-1", "manga.example")

    @Test
    fun coldChapterEntryLoadsDetailTokensAndGatesLockedChapter() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val tempDir = Files.createTempDirectory("manga-reader-test-1")
            val root = createTestRootPath()
            val fileStorage = OkioFileStorage(createTestFileSystem(), root)
            val realStorage = OkioFileStorage(FileSystem.SYSTEM, tempDir.toString().toPath(), cacheRoot = tempDir.toString().toPath())
            val appDataStore = AppDataStore(fileStorage)
            val cacheDatabase =
                Room
                    .databaseBuilder<CacheDatabase>(name = tempDir.resolve("cache.db").toAbsolutePath().toString())
                    .setDriver(createDatabaseDriver())
                    .setQueryCoroutineContext(PlatformDispatchers.IO)
                    .build()
            val progressRepository = MangaProgressRepository(cacheDatabase) { 1_000L }
            val mockClient = HttpClient(MockEngine { respond(ByteArray(0)) })
            val imageTranslationRepository =
                ImageTranslationRepository(
                    storage = realStorage,
                    client = mockClient,
                    adapters = emptyList(),
                    sourceClient = { mockClient },
                    imageCompressor = JvmImageCompressor(),
                )

            var discoveredEntityToken: String? = null
            val discoveredChapterTokens = ConcurrentHashMap<String, String>()
            val chapterPageCalls = CopyOnWriteArrayList<String>()
            val resolvedPageIds = CopyOnWriteArrayList<String>()
            val inFlightCount = AtomicInteger(0)
            val maxObservedConcurrency = AtomicInteger(0)
            var releaseResolveGate = CompletableDeferred<Unit>()
            var threePermitsEntered = CompletableDeferred<Unit>()
            var ch3Locked = true
            val sharedDetailCache = MutableStateFlow<UiMangaDetail?>(null)

            fun currentDetail(): UiMangaDetail =
                readerDetail(
                    chapterGroups =
                        persistentListOf(
                            UiMangaChapterGroup(
                                id = "group-a",
                                title = UiText.Raw("Main"),
                                chapters =
                                    persistentListOf(
                                        UiMangaChapter("ch-1", "Chapter 1", null, null, locked = false, url = "https://manga.example/ch-1"),
                                        UiMangaChapter("ch-2", "Chapter 2", null, null, locked = false, url = "https://manga.example/ch-2"),
                                        UiMangaChapter(
                                            "ch-3",
                                            "Chapter 3",
                                            null,
                                            null,
                                            locked = ch3Locked,
                                            url = "https://manga.example/paywall/ch-3",
                                        ),
                                    ),
                            ),
                        ),
                )

            val mangaDataSource =
                object : MangaDataSource {
                    override fun mangaDetail(mangaKey: MicroBlogKey): Cacheable<UiMangaDetail> =
                        Cacheable(
                            fetchSource = {
                                discoveredEntityToken = "entity-token-series-1"
                                discoveredChapterTokens["ch-1"] = "chapter-token-1"
                                discoveredChapterTokens["ch-2"] = "chapter-token-2"
                                discoveredChapterTokens["ch-3"] =
                                    if (ch3Locked) "chapter-token-3-locked" else "chapter-token-3-unlocked"
                                sharedDetailCache.value = currentDetail()
                            },
                            cacheSource = { sharedDetailCache.filterNotNull() },
                        )

                    override suspend fun chapterPages(
                        mangaKey: MicroBlogKey,
                        chapterId: String,
                    ): ImmutableList<UiMangaPage> {
                        chapterPageCalls += chapterId
                        assertEquals(
                            "entity-token-series-1",
                            discoveredEntityToken,
                            "mangaDetail must populate entityToken before chapterPages",
                        )
                        val expectedChapterToken =
                            when (chapterId) {
                                "ch-2" -> "chapter-token-2"
                                "ch-3" -> "chapter-token-3-unlocked"
                                else -> error("Unexpected chapterId $chapterId")
                            }
                        assertEquals(
                            expectedChapterToken,
                            discoveredChapterTokens[chapterId],
                            "mangaDetail must populate chapterToken before chapterPages",
                        )
                        if (chapterId == "ch-3") {
                            return readyPages(count = 2, prefix = "ch3")
                        }
                        return (0 until 8)
                            .map { idx ->
                                UiMangaPage.Deferred(
                                    id = "p-$idx",
                                    pageToken = "tok-$idx",
                                    previewUrl = "https://manga.example/preview/$idx.jpg",
                                    width = 800f,
                                    height = 1200f,
                                    customHeaders = persistentMapOf("Referer" to "https://manga.example"),
                                    proxy = ProxyRef.Unbound,
                                )
                            }.toImmutableList()
                    }

                    override suspend fun resolvePageMedia(
                        mangaKey: MicroBlogKey,
                        chapterId: String,
                        pageId: String,
                        pageToken: String,
                    ): UiMedia {
                        assertEquals("entity-token-series-1", discoveredEntityToken)
                        assertEquals("chapter-token-2", discoveredChapterTokens[chapterId])
                        val active = inFlightCount.incrementAndGet()
                        maxObservedConcurrency.accumulateAndGet(active, ::maxOf)
                        if (active >= 3) {
                            threePermitsEntered.complete(Unit)
                        }
                        try {
                            releaseResolveGate.await()
                            resolvedPageIds += pageId
                            return UiMedia.Image(
                                url = "https://manga.example/full/$pageId.jpg",
                                previewUrl = "https://manga.example/preview/$pageId.jpg",
                                description = null,
                                height = 1200f,
                                width = 800f,
                                sensitive = false,
                            )
                        } finally {
                            inFlightCount.decrementAndGet()
                        }
                    }
                }

            val accountService = FakeReaderAccountService(accountKey, mangaDataSource)
            startKoin {
                modules(
                    module {
                        testSingle<AccountService> { accountService }
                        testSingle { appDataStore }
                        testSingle { cacheDatabase }
                        testSingle { progressRepository }
                        testSingle { imageTranslationRepository }
                    },
                )
            }

            val lockedJob = SupervisorJob()
            val lockedScope = CoroutineScope(dispatcher + lockedJob)
            val unlockedJob = SupervisorJob()
            val unlockedScope = CoroutineScope(dispatcher + unlockedJob)
            val lockedPresenter = MangaReaderPresenter(accountType, mangaKey, initialChapterId = "ch-3")
            val unlockedPresenter = MangaReaderPresenter(accountType, mangaKey, initialChapterId = "ch-2", initialPage = 2)
            try {
                // 1. Enter locked "ch-3" directly -> loadState shows locked gate with openUrl, chapterPages() is NEVER called.
                val lockedStates = MutableStateFlow<MangaReaderPresenter.State?>(null)
                lockedScope.launch {
                    moleculeFlow(RecompositionMode.Immediate) { lockedPresenter.body() }.collect { lockedStates.value = it }
                }
                val lockedState = awaitReaderState(lockedStates) { it.loadState is UiState.Error }
                val lockedError = assertIs<LockedChapterException>((lockedState.loadState as UiState.Error).throwable)
                assertEquals("ch-3", lockedError.chapter.id)
                assertEquals("https://manga.example/paywall/ch-3", lockedError.openUrl)
                assertEquals("https://manga.example/paywall/ch-3", lockedState.lockedChapter?.openUrl)
                assertTrue(chapterPageCalls.isEmpty(), "chapterPages() must NEVER be called for a locked chapter")

                // Unlocking ch-3 externally and calling retryChapter() refreshes mangaDetail, discovers unlocked state + fresh token, and loads pages!
                ch3Locked = false
                lockedState.retryChapter()
                Snapshot.sendApplyNotifications()
                runCurrent()
                val unlockedAfterRetry =
                    awaitReaderState(lockedStates) {
                        it.loadState is UiState.Success && it.pages.size == 2
                    }
                assertEquals(null, unlockedAfterRetry.lockedChapter)
                assertEquals(listOf("ch-3"), chapterPageCalls.toList())

                lockedJob.cancelAndJoin()
                lockedPresenter.awaitPendingWrites()

                // Reset tokens & shared cache to prove cold unlocked entry ALSO loads mangaDetail first before chapterPages().
                sharedDetailCache.value = null
                chapterPageCalls.clear()
                discoveredEntityToken = null
                discoveredChapterTokens.clear()
                releaseResolveGate = CompletableDeferred()
                threePermitsEntered = CompletableDeferred()

                // 2. Enter unlocked "ch-2" directly at page 2 -> mangaDetail loads first, chapterPages() receives tokens,
                //    deferred pages within [currentPage-1 .. currentPage+3] = [1..5] resolve with max concurrency <= 3.
                val unlockedStates = MutableStateFlow<MangaReaderPresenter.State?>(null)
                unlockedScope.launch {
                    moleculeFlow(RecompositionMode.Immediate) { unlockedPresenter.body() }.collect { unlockedStates.value = it }
                }

                withContext(Dispatchers.Default) {
                    withTimeout(5_000) { threePermitsEntered.await() }
                }
                val loadingWindowState = awaitReaderState(unlockedStates) { it.pages.size == 8 }
                assertEquals(2, loadingWindowState.currentPageIndex)
                assertTrue(maxObservedConcurrency.get() in 1..3, "Expected concurrency in 1..3, got ${maxObservedConcurrency.get()}")
                // Verify outside-window pages (0, 6, 7) are Loading with bound ProxyRef.Account.
                val page0Loading = assertIs<PageMediaStatus.Loading>(loadingWindowState.pages[0].status)
                assertEquals(ProxyRef.Account(accountKey), page0Loading.proxy)

                releaseResolveGate.complete(Unit)

                val readyWindowState =
                    awaitReaderState(unlockedStates) { state ->
                        state.pages.size == 8 && (1..5).all { idx -> state.pages[idx].status is PageMediaStatus.Ready }
                    }
                assertTrue(maxObservedConcurrency.get() <= 3, "Max concurrency exceeded 3: ${maxObservedConcurrency.get()}")
                assertEquals(setOf("p-1", "p-2", "p-3", "p-4", "p-5"), resolvedPageIds.toSet())
                assertIs<PageMediaStatus.Loading>(readyWindowState.pages[0].status)
                assertIs<PageMediaStatus.Loading>(readyWindowState.pages[6].status)
                assertIs<PageMediaStatus.Loading>(readyWindowState.pages[7].status)
                // Verify prefetchMedia exposes [currentPageIndex + 1 .. currentPageIndex + 3] = [p-3, p-4, p-5] with bound proxy.
                assertEquals(
                    listOf(
                        "https://manga.example/full/p-3.jpg",
                        "https://manga.example/full/p-4.jpg",
                        "https://manga.example/full/p-5.jpg",
                    ),
                    readyWindowState.prefetchMedia.map { it.url },
                )
                assertTrue(readyWindowState.prefetchMedia.all { it.proxy == ProxyRef.Account(accountKey) })
            } finally {
                lockedJob.cancelAndJoin()
                unlockedJob.cancelAndJoin()
                lockedPresenter.awaitPendingWrites()
                unlockedPresenter.awaitPendingWrites()
                mockClient.close()
                stopKoin()
                cacheDatabase.close()
                deleteTestRootPath(root)
                tempDir.toFile().deleteRecursively()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun previousChapterTransitionLandsOnFinalPage() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val tempDir = Files.createTempDirectory("manga-reader-test-2")
            val root = createTestRootPath()
            val fileStorage = OkioFileStorage(createTestFileSystem(), root)
            val realStorage = OkioFileStorage(FileSystem.SYSTEM, tempDir.toString().toPath(), cacheRoot = tempDir.toString().toPath())
            val appDataStore = AppDataStore(fileStorage)
            val cacheDatabase =
                Room
                    .databaseBuilder<CacheDatabase>(name = tempDir.resolve("cache.db").toAbsolutePath().toString())
                    .setDriver(createDatabaseDriver())
                    .setQueryCoroutineContext(PlatformDispatchers.IO)
                    .build()
            val progressRepository = MangaProgressRepository(cacheDatabase) { 2_000L }
            val mockClient = HttpClient(MockEngine { respond(ByteArray(0)) })
            val imageTranslationRepository =
                ImageTranslationRepository(
                    storage = realStorage,
                    client = mockClient,
                    adapters = emptyList(),
                    sourceClient = { mockClient },
                    imageCompressor = JvmImageCompressor(),
                )

            val detail =
                readerDetail(
                    chapterGroups =
                        persistentListOf(
                            UiMangaChapterGroup(
                                id = "other-group",
                                title = UiText.Raw("Other"),
                                chapters =
                                    persistentListOf(
                                        UiMangaChapter("other-last", "Other Last", null, null, locked = false, url = null),
                                    ),
                            ),
                            UiMangaChapterGroup(
                                id = "group-a",
                                title = UiText.Raw("Main"),
                                chapters =
                                    persistentListOf(
                                        UiMangaChapter("ch-1", "Chapter 1", null, null, locked = false, url = "https://manga.example/ch-1"),
                                        UiMangaChapter("ch-2", "Chapter 2", null, null, locked = false, url = "https://manga.example/ch-2"),
                                        UiMangaChapter("ch-3", "Chapter 3", null, null, locked = true, url = null),
                                    ),
                            ),
                        ),
                )
            val pagesByChapter =
                mapOf(
                    "ch-1" to readyPages(count = 5, prefix = "ch1"),
                    "ch-2" to readyPages(count = 3, prefix = "ch2"),
                )
            val mangaDataSource = FakeStaticPagesMangaDataSource(detail, pagesByChapter)
            val accountService = FakeReaderAccountService(accountKey, mangaDataSource)
            startKoin {
                modules(
                    module {
                        testSingle<AccountService> { accountService }
                        testSingle { appDataStore }
                        testSingle { cacheDatabase }
                        testSingle { progressRepository }
                        testSingle { imageTranslationRepository }
                    },
                )
            }

            val singleJob = SupervisorJob()
            val singleScope = CoroutineScope(dispatcher + singleJob)
            val spreadJob = SupervisorJob()
            val spreadScope = CoroutineScope(dispatcher + spreadJob)
            val presenter = MangaReaderPresenter(accountType, mangaKey, initialChapterId = "ch-2", initialPage = 0)
            val spreadPresenter = MangaReaderPresenter(accountType, mangaKey, initialChapterId = "ch-2", initialPage = 0)
            try {
                // 1. Single-page mode: on page 0 of ch-2, stepBackward() loads ch-1 at ch1Pages.lastIndex (4).
                val states = MutableStateFlow<MangaReaderPresenter.State?>(null)
                singleScope.launch {
                    moleculeFlow(RecompositionMode.Immediate) { presenter.body() }.collect { states.value = it }
                }
                val ch2Initial = awaitReaderState(states) { it.activeChapterId == "ch-2" && it.pages.size == 3 }
                assertEquals(0, ch2Initial.currentPageIndex)
                val prevUnlocked = assertIs<ChapterBoundary.Unlocked>(ch2Initial.previousBoundary)
                assertEquals("ch-1", prevUnlocked.chapter.id)
                assertTrue(prevUnlocked.startFromEnd)
                val nextLocked = assertIs<ChapterBoundary.Locked>(ch2Initial.nextBoundary)
                assertEquals("ch-3", nextLocked.chapter.id)
                // Falls back to detail.url since ch-3.url == null
                assertEquals("https://manga.example/series-1", nextLocked.openUrl)

                ch2Initial.stepBackward()
                Snapshot.sendApplyNotifications()
                runCurrent()

                val ch1FromBackward = awaitReaderState(states) { it.activeChapterId == "ch-1" && it.pages.size == 5 }
                assertEquals(4, ch1FromBackward.currentPageIndex)
                assertEquals(4, ch1FromBackward.currentSlotIndex)
                // ch-1 is index 0 in group-a -> previousBoundary must be None (never crosses into other-group!).
                assertEquals(ChapterBoundary.None, ch1FromBackward.previousBoundary)
                singleJob.cancelAndJoin()
                presenter.awaitPendingWrites()

                // 2. Spread mode: openAdjacentChapter(previousBoundary as Unlocked) lands on ch1Pages.lastIndex (4) AND slots.lastIndex (2).
                val spreadStates = MutableStateFlow<MangaReaderPresenter.State?>(null)
                spreadScope.launch {
                    moleculeFlow(RecompositionMode.Immediate) { spreadPresenter.body() }.collect { spreadStates.value = it }
                }
                val spreadCh2 = awaitReaderState(spreadStates) { it.activeChapterId == "ch-2" && it.pages.size == 3 }
                spreadCh2.setWideViewport(true)
                spreadCh2.setDoublePageSpread(true)
                Snapshot.sendApplyNotifications()
                runCurrent()
                val spreadEnabledCh2 = awaitReaderState(spreadStates) { it.doublePageSpread }
                val boundary = assertIs<ChapterBoundary.Unlocked>(spreadEnabledCh2.previousBoundary)
                spreadEnabledCh2.openAdjacentChapter(boundary)
                Snapshot.sendApplyNotifications()
                runCurrent()

                val spreadCh1 = awaitReaderState(spreadStates) { it.activeChapterId == "ch-1" && it.pages.size == 5 }
                // 5 portrait pages in RTL with spreadCoverSinglePage = true -> [Single(0), Double(2, 1, 1), Double(4, 3, 3)]
                assertEquals(3, spreadCh1.slots.size)
                assertEquals(4, spreadCh1.currentPageIndex)
                assertEquals(2, spreadCh1.currentSlotIndex)
                assertTrue(spreadCh1.slots[spreadCh1.currentSlotIndex].contains(4))

                // Discovering landscape dimensions on page 3 splits the spread without losing currentPageIndex = 4!
                spreadCh1.onPageDimensionsLoaded("ch1-3", width = 1600f, height = 1000f)
                Snapshot.sendApplyNotifications()
                runCurrent()
                val splitSpreadCh1 = awaitReaderState(spreadStates) { it.slots.size == 4 }
                assertEquals(4, splitSpreadCh1.currentPageIndex)
                assertEquals(3, splitSpreadCh1.currentSlotIndex)
                assertEquals(ReaderSlot.Single(4), splitSpreadCh1.slots[splitSpreadCh1.currentSlotIndex])
            } finally {
                singleJob.cancelAndJoin()
                spreadJob.cancelAndJoin()
                presenter.awaitPendingWrites()
                spreadPresenter.awaitPendingWrites()
                mockClient.close()
                stopKoin()
                cacheDatabase.close()
                deleteTestRootPath(root)
                tempDir.toFile().deleteRecursively()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun debouncesProgressFlushesOnSwitchAndDisposeCancelsOldChapterJobsAndRetries() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val tempDir = Files.createTempDirectory("manga-reader-test-3")
            val root = createTestRootPath()
            val fileStorage = OkioFileStorage(createTestFileSystem(), root)
            val realStorage = OkioFileStorage(FileSystem.SYSTEM, tempDir.toString().toPath(), cacheRoot = tempDir.toString().toPath())
            val appDataStore = AppDataStore(fileStorage)
            val cacheDatabase =
                Room
                    .databaseBuilder<CacheDatabase>(name = tempDir.resolve("cache.db").toAbsolutePath().toString())
                    .setDriver(createDatabaseDriver())
                    .setQueryCoroutineContext(dispatcher)
                    .build()
            var now = 10_000L
            val progressRepository = MangaProgressRepository(cacheDatabase) { now++ }
            val mockClient = HttpClient(MockEngine { respond(ByteArray(0)) })
            val imageTranslationRepository =
                ImageTranslationRepository(
                    storage = realStorage,
                    client = mockClient,
                    adapters = emptyList(),
                    sourceClient = { mockClient },
                    imageCompressor = JvmImageCompressor(),
                )

            val ch1PageResolutionCancelled = CompletableDeferred<Unit>()
            val ch1PageResolutionStarted = CompletableDeferred<Unit>()
            var failCh2ChapterOnce = true
            var failCh2Page0Once = true

            val detail =
                readerDetail(
                    readingDirection = MangaReadingDirection.Ltr,
                    chapterGroups =
                        persistentListOf(
                            UiMangaChapterGroup(
                                id = "group-a",
                                title = null,
                                chapters =
                                    persistentListOf(
                                        UiMangaChapter("ch-1", "Chapter 1", null, null, locked = false, url = null),
                                        UiMangaChapter("ch-2", "Chapter 2", null, null, locked = false, url = null),
                                    ),
                            ),
                        ),
                )

            val mangaDataSource =
                object : MangaDataSource {
                    override fun mangaDetail(mangaKey: MicroBlogKey): Cacheable<UiMangaDetail> {
                        val cache = MutableStateFlow<UiMangaDetail?>(null)
                        return Cacheable(
                            fetchSource = { cache.value = detail },
                            cacheSource = { cache.filterNotNull() },
                        )
                    }

                    override suspend fun chapterPages(
                        mangaKey: MicroBlogKey,
                        chapterId: String,
                    ): ImmutableList<UiMangaPage> =
                        when (chapterId) {
                            "ch-1" -> {
                                persistentListOf(
                                    UiMangaPage.Ready("ch1-0", UiMedia.Image("https://manga.example/ch1-0.jpg")),
                                    UiMangaPage.Ready("ch1-1", UiMedia.Image("https://manga.example/ch1-1.jpg")),
                                    UiMangaPage.Ready("ch1-2", UiMedia.Image("https://manga.example/ch1-2.jpg")),
                                    UiMangaPage.Deferred(
                                        id = "ch1-3",
                                        pageToken = "slow-tok",
                                        previewUrl = null,
                                        width = 800f,
                                        height = 1200f,
                                        customHeaders = null,
                                        proxy = ProxyRef.Global,
                                    ),
                                )
                            }

                            "ch-2" -> {
                                if (failCh2ChapterOnce) {
                                    failCh2ChapterOnce = false
                                    error("Transient chapter list failure")
                                }
                                persistentListOf(
                                    UiMangaPage.Deferred(
                                        id = "ch2-0",
                                        pageToken = "flaky-tok",
                                        previewUrl = null,
                                        width = 800f,
                                        height = 1200f,
                                        customHeaders = null,
                                        proxy = ProxyRef.Global,
                                    ),
                                    UiMangaPage.Ready("ch2-1", UiMedia.Image("https://manga.example/ch2-1.jpg")),
                                    UiMangaPage.Ready("ch2-2", UiMedia.Image("https://manga.example/ch2-2.jpg")),
                                )
                            }

                            else -> {
                                error("Unexpected chapter $chapterId")
                            }
                        }

                    override suspend fun resolvePageMedia(
                        mangaKey: MicroBlogKey,
                        chapterId: String,
                        pageId: String,
                        pageToken: String,
                    ): UiMedia {
                        if (chapterId == "ch-1" && pageId == "ch1-3") {
                            ch1PageResolutionStarted.complete(Unit)
                            try {
                                awaitCancellation()
                            } catch (e: CancellationException) {
                                ch1PageResolutionCancelled.complete(Unit)
                                throw e
                            }
                        }
                        if (chapterId == "ch-2" && pageId == "ch2-0") {
                            if (failCh2Page0Once) {
                                failCh2Page0Once = false
                                error("Transient CDN error")
                            }
                            return UiMedia.Image("https://manga.example/ch2-0.jpg")
                        }
                        error("Unexpected resolvePageMedia($chapterId, $pageId)")
                    }
                }

            val accountService = FakeReaderAccountService(accountKey, mangaDataSource)
            startKoin {
                modules(
                    module {
                        testSingle<AccountService> { accountService }
                        testSingle { appDataStore }
                        testSingle { cacheDatabase }
                        testSingle { progressRepository }
                        testSingle { imageTranslationRepository }
                    },
                )
            }

            val readerJob = SupervisorJob()
            val readerScope = CoroutineScope(dispatcher + readerJob)
            var activePresenter: MangaReaderPresenter? = null
            try {
                appDataStore.appSettingsStore.updateData {
                    it.copy(mangaReaderConfig = AppSettings.MangaReaderConfig(defaultDirection = MangaReadingDirection.Rtl))
                }
                val presenter = MangaReaderPresenter(accountType, mangaKey, initialChapterId = "ch-1", initialPage = 0)
                activePresenter = presenter
                val states = MutableStateFlow<MangaReaderPresenter.State?>(null)
                readerScope.launch {
                    moleculeFlow(RecompositionMode.Immediate) { presenter.body() }.collect { states.value = it }
                }

                val observedSnapshots = CopyOnWriteArrayList<dev.dimension.flare.data.repository.MangaProgressSnapshot>()
                readerScope.launch {
                    progressRepository.observe(accountType, mangaKey).collect { observedSnapshots += it }
                }

                val initial = awaitReaderState(states) { it.activeChapterId == "ch-1" && it.pages.size == 4 }
                // detail.readingDirection (Ltr) overrides settings.defaultDirection (Rtl) when readerModeOverride == null
                assertEquals(MangaReadingDirection.Ltr, initial.direction)
                runCurrent()
                assertTrue(ch1PageResolutionStarted.isCompleted)

                // Rapidly scrub pages 1 -> 2 within < 300ms: neither page 1 nor page 2 may be persisted before 300ms elapses.
                val writesBeforeScrub = now
                initial.goToPage(1)
                Snapshot.sendApplyNotifications()
                runCurrent()
                advanceTimeBy(150)
                runCurrent()
                states.value!!.goToPage(2)
                Snapshot.sendApplyNotifications()
                runCurrent()
                advanceTimeBy(250)
                runCurrent()
                assertEquals(writesBeforeScrub, now, "Debounce must suppress writes while < 300ms has elapsed since last page change")
                assertTrue(observedSnapshots.none { it.lastPageIndex == 1 || it.lastPageIndex == 2 })

                // Complete the 300ms debounce window after goToPage(2): writes exactly once at pageIndex = 2 (skipping page 1).
                advanceTimeBy(60)
                runCurrent()
                assertEquals(writesBeforeScrub + 1, now)
                assertTrue(observedSnapshots.none { it.lastPageIndex == 1 })
                val debouncedProgress = observedSnapshots.last()
                assertEquals("ch-1", debouncedProgress.lastChapterId)
                assertEquals(2, debouncedProgress.lastPageIndex)
                assertFalse(debouncedProgress.chapters["ch-1"]!!.isCompleted)

                // Enable double-page spread with coverSinglePage = false -> slots are [Double(0, 1, 0), Double(2, 3, 2)].
                // Jumping to page 2 (primaryPageIndex of the final Double(2, 3) slot) includes pages.lastIndex (3),
                // so stepForward() into ch-2 BEFORE 300ms must:
                // 1) flush ch-1 immediately with isCompleted = true (even though currentPageIndex == 2 < lastIndex == 3!)
                // 2) cancel in-flight resolvePageMedia("ch-1", "ch1-3")!
                states.value!!.setWideViewport(true)
                states.value!!.setDoublePageSpread(true)
                states.value!!.setSpreadCoverSinglePage(false)
                Snapshot.sendApplyNotifications()
                runCurrent()
                val spreadCh1 = awaitReaderState(states) { it.doublePageSpread && !it.spreadCoverSinglePage && it.slots.size == 2 }
                assertEquals(1, spreadCh1.currentSlotIndex)
                assertTrue(spreadCh1.slots[spreadCh1.currentSlotIndex].contains(3))
                spreadCh1.stepForward()
                Snapshot.sendApplyNotifications()
                runCurrent()

                withContext(Dispatchers.Default) {
                    withTimeout(5_000) { ch1PageResolutionCancelled.await() }
                }
                val flushedCh1 =
                    withContext(Dispatchers.Default) {
                        withTimeout(5_000) {
                            progressRepository.observe(accountType, mangaKey).first { it.chapters["ch-1"]?.isCompleted == true }
                        }
                    }
                assertEquals(2, flushedCh1.chapters["ch-1"]?.lastPageIndex)

                // ch-2 chapterPages() fails once -> retryChapter() recovers ch-2!
                val ch2ChapterFailed =
                    awaitReaderState(states) {
                        it.activeChapterId == "ch-2" && it.loadState is UiState.Error
                    }
                ch2ChapterFailed.retryChapter()
                Snapshot.sendApplyNotifications()
                runCurrent()

                // ch-2 page 0 fails once -> retryPage("ch2-0") recovers it to Ready!
                val ch2Failed =
                    awaitReaderState(states) {
                        it.activeChapterId == "ch-2" &&
                            it.pages.firstOrNull()?.status is PageMediaStatus.Failed
                    }
                ch2Failed.retryPage("ch2-0")
                Snapshot.sendApplyNotifications()
                runCurrent()
                val ch2Recovered =
                    awaitReaderState(states) {
                        it.activeChapterId == "ch-2" &&
                            it.pages.firstOrNull()?.status is PageMediaStatus.Ready
                    }

                // Override direction to Webtoon and navigate to page 1, then dispose immediately (< 300ms) -> flushes page 1 on dispose!
                ch2Recovered.setDirection(MangaReadingDirection.Webtoon)
                ch2Recovered.goToPage(1)
                Snapshot.sendApplyNotifications()
                runCurrent()
                readerJob.cancelAndJoin()

                val finalProgress =
                    withContext(Dispatchers.Default) {
                        withTimeout(5_000) {
                            progressRepository.observe(accountType, mangaKey).first {
                                it.lastChapterId == "ch-2" &&
                                    it.lastPageIndex == 1 &&
                                    it.readerModeOverride == MangaReadingDirection.Webtoon
                            }
                        }
                    }
                assertEquals(1, finalProgress.chapters["ch-2"]?.lastPageIndex)
                assertFalse(finalProgress.chapters["ch-2"]!!.isCompleted)
            } finally {
                readerJob.cancelAndJoin()
                activePresenter?.awaitPendingWrites()
                mockClient.close()
                stopKoin()
                cacheDatabase.close()
                deleteTestRootPath(root)
                tempDir.toFile().deleteRecursively()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun coldEntryWithNullInitialPageAwaitsIoProgressWithoutClobberingSavedPage() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val tempDir = Files.createTempDirectory("manga-reader-test-4")
            val root = createTestRootPath()
            val fileStorage = OkioFileStorage(createTestFileSystem(), root)
            val realStorage = OkioFileStorage(FileSystem.SYSTEM, tempDir.toString().toPath(), cacheRoot = tempDir.toString().toPath())
            val appDataStore = AppDataStore(fileStorage)
            val cacheDatabase =
                Room
                    .databaseBuilder<CacheDatabase>(name = tempDir.resolve("cache.db").toAbsolutePath().toString())
                    .setDriver(createDatabaseDriver())
                    .setQueryCoroutineContext(PlatformDispatchers.IO)
                    .build()
            val progressRepository = MangaProgressRepository(cacheDatabase) { 50_000L }
            val mockClient = HttpClient(MockEngine { respond(ByteArray(0)) })
            val imageTranslationRepository =
                ImageTranslationRepository(
                    storage = realStorage,
                    client = mockClient,
                    adapters = emptyList(),
                    sourceClient = { mockClient },
                    imageCompressor = JvmImageCompressor(),
                )

            val detail =
                readerDetail(
                    readingDirection = MangaReadingDirection.Rtl,
                    chapterGroups =
                        persistentListOf(
                            UiMangaChapterGroup(
                                id = "group-a",
                                title = null,
                                chapters =
                                    persistentListOf(
                                        UiMangaChapter("ch-2", "Chapter 2", null, null, locked = false, url = null),
                                    ),
                            ),
                        ),
                )
            // Warm in-memory detail + instant chapterPages so detail & pages resolve BEFORE PlatformDispatchers.IO Room query!
            val warmCache = MutableStateFlow<UiMangaDetail?>(detail)
            val mangaDataSource =
                object : MangaDataSource {
                    override fun mangaDetail(mangaKey: MicroBlogKey): Cacheable<UiMangaDetail> =
                        Cacheable(
                            fetchSource = { warmCache.value = detail },
                            cacheSource = { warmCache.filterNotNull() },
                        )

                    override suspend fun chapterPages(
                        mangaKey: MicroBlogKey,
                        chapterId: String,
                    ): ImmutableList<UiMangaPage> = readyPages(count = 8, prefix = "warm")

                    override suspend fun resolvePageMedia(
                        mangaKey: MicroBlogKey,
                        chapterId: String,
                        pageId: String,
                        pageToken: String,
                    ): UiMedia = error("Unused")
                }

            val accountService = FakeReaderAccountService(accountKey, mangaDataSource)
            startKoin {
                modules(
                    module {
                        testSingle<AccountService> { accountService }
                        testSingle { appDataStore }
                        testSingle { cacheDatabase }
                        testSingle { progressRepository }
                        testSingle { imageTranslationRepository }
                    },
                )
            }

            val readerJob = SupervisorJob()
            val readerScope = CoroutineScope(dispatcher + readerJob)
            var activePresenter: MangaReaderPresenter? = null
            try {
                progressRepository.recordPage(
                    accountType = accountType,
                    mangaKey = mangaKey,
                    chapterId = "ch-2",
                    pageIndex = 5,
                    pageCount = 8,
                    isCompleted = false,
                )
                progressRepository.setReaderModeOverride(accountType, mangaKey, MangaReadingDirection.Webtoon)

                val presenter = MangaReaderPresenter(accountType, mangaKey, initialChapterId = "ch-2", initialPage = null)
                activePresenter = presenter
                val states = MutableStateFlow<MangaReaderPresenter.State?>(null)
                val successPageIndices = CopyOnWriteArrayList<Int>()
                readerScope.launch {
                    moleculeFlow(RecompositionMode.Immediate) { presenter.body() }.collect { state ->
                        if (state.loadState is UiState.Success) {
                            successPageIndices += state.currentPageIndex
                        }
                        states.value = state
                    }
                }

                val loaded = awaitReaderState(states) { it.loadState is UiState.Success && it.pages.size == 8 }
                assertEquals(5, loaded.currentPageIndex)
                assertEquals(MangaReadingDirection.Webtoon, loaded.direction)
                assertTrue(
                    successPageIndices.all { it == 5 },
                    "Success state must never emit page 0 when resuming saved page 5: $successPageIndices",
                )

                // Dispose reader immediately and verify saved progress in SQLite remains at pageIndex = 5 (never overwritten with 0).
                readerJob.cancelAndJoin()
                presenter.awaitPendingWrites()

                val persisted =
                    withContext(Dispatchers.Default) {
                        withTimeout(5_000) {
                            progressRepository.observe(accountType, mangaKey).first()
                        }
                    }
                assertEquals("ch-2", persisted.lastChapterId)
                assertEquals(5, persisted.lastPageIndex)
                assertEquals(5, persisted.chapters["ch-2"]?.lastPageIndex)
                assertEquals(MangaReadingDirection.Webtoon, persisted.readerModeOverride)
            } finally {
                readerJob.cancelAndJoin()
                activePresenter?.awaitPendingWrites()
                mockClient.close()
                stopKoin()
                cacheDatabase.close()
                deleteTestRootPath(root)
                tempDir.toFile().deleteRecursively()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun retryDeferredReadyPageMintsFreshUrlAndBackgroundDetailRefreshPreservesActiveSession() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val tempDir = Files.createTempDirectory("manga-reader-test-5")
            val root = createTestRootPath()
            val fileStorage = OkioFileStorage(createTestFileSystem(), root)
            val realStorage = OkioFileStorage(FileSystem.SYSTEM, tempDir.toString().toPath(), cacheRoot = tempDir.toString().toPath())
            val appDataStore = AppDataStore(fileStorage)
            val cacheDatabase =
                Room
                    .databaseBuilder<CacheDatabase>(name = tempDir.resolve("cache.db").toAbsolutePath().toString())
                    .setDriver(createDatabaseDriver())
                    .setQueryCoroutineContext(PlatformDispatchers.IO)
                    .build()
            val progressRepository = MangaProgressRepository(cacheDatabase) { 75_000L }
            val mockClient = HttpClient(MockEngine { respond(ByteArray(0)) })
            val imageTranslationRepository =
                ImageTranslationRepository(
                    storage = realStorage,
                    client = mockClient,
                    adapters = emptyList(),
                    sourceClient = { mockClient },
                    imageCompressor = JvmImageCompressor(),
                )

            val detail =
                readerDetail(
                    readingDirection = MangaReadingDirection.Rtl,
                    chapterGroups =
                        persistentListOf(
                            UiMangaChapterGroup(
                                id = "group-a",
                                title = null,
                                chapters =
                                    persistentListOf(
                                        UiMangaChapter("ch-1", "Chapter 1", null, null, locked = false, url = null),
                                    ),
                            ),
                        ),
                )

            var failInitialDetailOnce = true
            val detailFetchCount = AtomicInteger(0)
            val chapterPagesCallCount = AtomicInteger(0)
            val deferredResolveCount = AtomicInteger(0)
            var backgroundRefreshEntered: CompletableDeferred<Unit>? = null
            var releaseBackgroundRefresh: CompletableDeferred<Unit>? = null
            var latestDetailCacheable: Cacheable<UiMangaDetail>? = null
            val detailCache = MutableStateFlow<UiMangaDetail?>(null)

            val mangaDataSource =
                object : MangaDataSource {
                    override fun mangaDetail(mangaKey: MicroBlogKey): Cacheable<UiMangaDetail> =
                        Cacheable(
                            fetchSource = {
                                val call = detailFetchCount.incrementAndGet()
                                if (failInitialDetailOnce) {
                                    failInitialDetailOnce = false
                                    error("Initial detail network failure")
                                }
                                if (call >= 3) {
                                    backgroundRefreshEntered?.complete(Unit)
                                    releaseBackgroundRefresh?.await()
                                }
                                detailCache.value = detail
                            },
                            cacheSource = { detailCache.filterNotNull() },
                        ).also { latestDetailCacheable = it }

                    override suspend fun chapterPages(
                        mangaKey: MicroBlogKey,
                        chapterId: String,
                    ): ImmutableList<UiMangaPage> {
                        chapterPagesCallCount.incrementAndGet()
                        return persistentListOf(
                            UiMangaPage.Deferred(
                                id = "def-0",
                                pageToken = "signed-tok",
                                previewUrl = null,
                                width = 800f,
                                height = 1200f,
                                customHeaders = null,
                                proxy = ProxyRef.Global,
                            ),
                            UiMangaPage.Ready("p-1", UiMedia.Image("https://manga.example/p-1.jpg")),
                            UiMangaPage.Ready("p-2", UiMedia.Image("https://manga.example/p-2.jpg")),
                        )
                    }

                    override suspend fun resolvePageMedia(
                        mangaKey: MicroBlogKey,
                        chapterId: String,
                        pageId: String,
                        pageToken: String,
                    ): UiMedia {
                        val attempt = deferredResolveCount.incrementAndGet()
                        return UiMedia.Image("https://manga.example/cdn-v$attempt/$pageId.jpg")
                    }
                }

            val accountService = FakeReaderAccountService(accountKey, mangaDataSource)
            startKoin {
                modules(
                    module {
                        testSingle<AccountService> { accountService }
                        testSingle { appDataStore }
                        testSingle { cacheDatabase }
                        testSingle { progressRepository }
                        testSingle { imageTranslationRepository }
                    },
                )
            }

            val readerJob = SupervisorJob()
            val readerScope = CoroutineScope(dispatcher + readerJob)
            var activePresenter: MangaReaderPresenter? = null
            try {
                val presenter = MangaReaderPresenter(accountType, mangaKey, initialChapterId = "ch-1", initialPage = 0)
                activePresenter = presenter
                val states = MutableStateFlow<MangaReaderPresenter.State?>(null)
                val observedLoadStates = CopyOnWriteArrayList<UiState<Unit>>()
                readerScope.launch {
                    moleculeFlow(RecompositionMode.Immediate) { presenter.body() }.collect { state ->
                        observedLoadStates += state.loadState
                        states.value = state
                    }
                }

                // 1. Initial detail fetch fails -> retryChapter() sets detailAttempt = 1 (forceRefresh = true) and recovers!
                val initialError = awaitReaderState(states) { it.loadState is UiState.Error }
                initialError.retryChapter()
                Snapshot.sendApplyNotifications()
                runCurrent()

                val firstReady =
                    awaitReaderState(states) { state ->
                        state.loadState is UiState.Success &&
                            state.pages.firstOrNull()?.status is PageMediaStatus.Ready
                    }
                val v1Media = assertIs<PageMediaStatus.Ready>(firstReady.pages[0].status).original as UiMedia.Image
                assertEquals("https://manga.example/cdn-v1/def-0.jpg", v1Media.url)
                assertEquals(1, deferredResolveCount.get())
                assertEquals(1, chapterPagesCallCount.get())

                // 2. retryPage("def-0") on an already-Ready Deferred page evicts resolvedMedia and mints a fresh v2 URL!
                firstReady.retryPage("def-0")
                Snapshot.sendApplyNotifications()
                runCurrent()

                val secondReady =
                    awaitReaderState(states) { state ->
                        val ready = state.pages.firstOrNull()?.status as? PageMediaStatus.Ready
                        (ready?.original as? UiMedia.Image)?.url == "https://manga.example/cdn-v2/def-0.jpg"
                    }
                assertEquals(2, deferredResolveCount.get())

                // 3. Advance to page 2, then trigger a background detail refresh (refreshState = LoadState.Loading) while detailAttempt > 0:
                //    active ChapterPagesSession and currentPageIndex must stay mounted without resetting!
                secondReady.goToPage(2)
                Snapshot.sendApplyNotifications()
                runCurrent()
                val onPage2 = awaitReaderState(states) { it.currentPageIndex == 2 }
                observedLoadStates.clear()

                val refreshEntered = CompletableDeferred<Unit>()
                val releaseRefresh = CompletableDeferred<Unit>()
                backgroundRefreshEntered = refreshEntered
                releaseBackgroundRefresh = releaseRefresh
                val activeCacheable = assertNotNull(latestDetailCacheable)
                activeCacheable.refresh()

                withContext(Dispatchers.Default) {
                    withTimeout(5_000) { refreshEntered.await() }
                }
                Snapshot.sendApplyNotifications()
                runCurrent()

                val duringBackgroundRefresh = assertNotNull(states.value)
                assertIs<UiState.Success<Unit>>(duringBackgroundRefresh.loadState)
                assertEquals(2, duringBackgroundRefresh.currentPageIndex)
                assertEquals(1, chapterPagesCallCount.get(), "Background detail refresh must not tear down ChapterPagesSession")
                assertTrue(
                    observedLoadStates.all { it is UiState.Success },
                    "loadState must remain Success across background detail refresh, saw $observedLoadStates",
                )

                releaseRefresh.complete(Unit)
                Snapshot.sendApplyNotifications()
                runCurrent()
                assertEquals(2, states.value!!.currentPageIndex)
                assertEquals(1, chapterPagesCallCount.get())
            } finally {
                releaseBackgroundRefresh?.complete(Unit)
                readerJob.cancelAndJoin()
                activePresenter?.awaitPendingWrites()
                mockClient.close()
                stopKoin()
                cacheDatabase.close()
                deleteTestRootPath(root)
                tempDir.toFile().deleteRecursively()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun canonicalMangaKeyAlignsReaderProgressChapterPagesAndDeferredMediaWithEntityToken() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val tempDir = Files.createTempDirectory("manga-reader-canonical-test")
            val root = createTestRootPath()
            val fileStorage = OkioFileStorage(createTestFileSystem(), root)
            val realStorage = OkioFileStorage(FileSystem.SYSTEM, tempDir.toString().toPath(), cacheRoot = tempDir.toString().toPath())
            val appDataStore = AppDataStore(fileStorage)
            val cacheDatabase =
                Room
                    .databaseBuilder<CacheDatabase>(name = tempDir.resolve("cache.db").toAbsolutePath().toString())
                    .setDriver(createDatabaseDriver())
                    .setQueryCoroutineContext(PlatformDispatchers.IO)
                    .build()
            var now = 20_000L
            val progressRepository = MangaProgressRepository(cacheDatabase) { now++ }
            val mockClient = HttpClient(MockEngine { respond(ByteArray(0)) })
            val imageTranslationRepository =
                ImageTranslationRepository(
                    storage = realStorage,
                    client = mockClient,
                    adapters = emptyList(),
                    sourceClient = { mockClient },
                    imageCompressor = JvmImageCompressor(),
                )

            val slugKey = MicroBlogKey("slug-solo-1", "manga.example")
            val canonicalKey = MicroBlogKey("canonical-solo-1", "manga.example")
            val detail =
                readerDetail(
                    chapterGroups =
                        persistentListOf(
                            UiMangaChapterGroup(
                                id = "group-a",
                                title = UiText.Raw("Main"),
                                chapters =
                                    persistentListOf(
                                        UiMangaChapter("ch-1", "Chapter 1", null, null, locked = false, url = "https://manga.example/ch-1"),
                                    ),
                            ),
                        ),
                ).copy(mangaKey = canonicalKey)

            val entityTokensByKey = ConcurrentHashMap<MicroBlogKey, String>()
            val chapterTokensByKey = ConcurrentHashMap<Pair<MicroBlogKey, String>, String>()
            val detailKeys = CopyOnWriteArrayList<MicroBlogKey>()
            val chapterPagesKeys = CopyOnWriteArrayList<MicroBlogKey>()
            val resolvePageKeys = CopyOnWriteArrayList<MicroBlogKey>()
            val detailCache = MutableStateFlow<UiMangaDetail?>(null)

            val mangaDataSource =
                object : MangaDataSource {
                    override fun mangaDetail(mangaKey: MicroBlogKey): Cacheable<UiMangaDetail> {
                        detailKeys += mangaKey
                        return Cacheable(
                            fetchSource = {
                                listOf(mangaKey, canonicalKey).forEach { key ->
                                    entityTokensByKey[key] = "entity-tok-canonical"
                                    chapterTokensByKey[key to "ch-1"] = "chapter-tok-1"
                                }
                                detailCache.value = detail
                            },
                            cacheSource = { detailCache.filterNotNull() },
                        )
                    }

                    override suspend fun chapterPages(
                        mangaKey: MicroBlogKey,
                        chapterId: String,
                    ): ImmutableList<UiMangaPage> {
                        chapterPagesKeys += mangaKey
                        assertEquals("entity-tok-canonical", entityTokensByKey[mangaKey])
                        assertEquals("chapter-tok-1", chapterTokensByKey[mangaKey to chapterId])
                        return (0 until 5)
                            .map { idx ->
                                UiMangaPage.Deferred(
                                    id = "cp-$idx",
                                    pageToken = "pt-$idx",
                                    previewUrl = null,
                                    width = 800f,
                                    height = 1200f,
                                    customHeaders = null,
                                    proxy = ProxyRef.Unbound,
                                )
                            }.toImmutableList()
                    }

                    override suspend fun resolvePageMedia(
                        mangaKey: MicroBlogKey,
                        chapterId: String,
                        pageId: String,
                        pageToken: String,
                    ): UiMedia {
                        resolvePageKeys += mangaKey
                        assertEquals("entity-tok-canonical", entityTokensByKey[mangaKey])
                        assertEquals("chapter-tok-1", chapterTokensByKey[mangaKey to chapterId])
                        return UiMedia.Image("https://manga.example/canonical/$pageId.jpg")
                    }
                }

            val accountService = FakeReaderAccountService(accountKey, mangaDataSource)
            startKoin {
                modules(
                    module {
                        testSingle<AccountService> { accountService }
                        testSingle { appDataStore }
                        testSingle { cacheDatabase }
                        testSingle { progressRepository }
                        testSingle { imageTranslationRepository }
                    },
                )
            }

            // Seed existing progress under canonicalKey so cold entry via slugKey (initialPage = null) must resume page 2!
            progressRepository.recordPage(
                accountType = accountType,
                mangaKey = canonicalKey,
                chapterId = "ch-1",
                pageIndex = 2,
                pageCount = 5,
                isCompleted = false,
            )

            val readerJob = SupervisorJob()
            val readerScope = CoroutineScope(dispatcher + readerJob)
            val detailJob = SupervisorJob()
            val detailScope = CoroutineScope(dispatcher + detailJob)
            val readerPresenter = MangaReaderPresenter(accountType, slugKey, initialChapterId = "ch-1", initialPage = null)
            val detailPresenter = MangaDetailPresenter(accountType, slugKey)
            try {
                val detailStates = MutableStateFlow<MangaDetailPresenter.State?>(null)
                detailScope.launch {
                    moleculeFlow(RecompositionMode.Immediate) { detailPresenter.body() }.collect { detailStates.value = it }
                }

                val readerStates = MutableStateFlow<MangaReaderPresenter.State?>(null)
                readerScope.launch {
                    moleculeFlow(RecompositionMode.Immediate) { readerPresenter.body() }.collect { readerStates.value = it }
                }

                val ready =
                    awaitReaderState(readerStates) { state ->
                        state.loadState is UiState.Success &&
                            state.pages.size == 5 &&
                            state.pages[2].status is PageMediaStatus.Ready
                    }
                assertEquals(2, ready.currentPageIndex, "Cold entry via slugKey must resume saved page from canonicalKey")
                assertEquals(listOf(canonicalKey), chapterPagesKeys.toList())
                assertTrue(resolvePageKeys.isNotEmpty() && resolvePageKeys.all { it == canonicalKey })

                // Advance to page 3 and override direction -> MangaDetailPresenter(slugKey) must observe the updated canonical progress!
                ready.goToPage(3)
                ready.setDirection(MangaReadingDirection.Webtoon)
                Snapshot.sendApplyNotifications()
                advanceTimeBy(350)
                runCurrent()

                val canonicalProgress =
                    withContext(Dispatchers.Default) {
                        withTimeout(5_000) {
                            progressRepository.observe(accountType, canonicalKey).first {
                                it.lastPageIndex == 3 && it.readerModeOverride == MangaReadingDirection.Webtoon
                            }
                        }
                    }
                val updatedDetailState =
                    awaitReaderState(detailStates) { state ->
                        state.detail is UiState.Success && state.primaryReadTarget?.initialPage == 3
                    }
                assertEquals(3, updatedDetailState.primaryReadTarget?.initialPage)
                assertEquals(3, canonicalProgress.lastPageIndex)
                assertEquals(MangaReadingDirection.Webtoon, canonicalProgress.readerModeOverride)
                assertEquals(null, progressRepository.observe(accountType, slugKey).first().lastChapterId)
            } finally {
                readerJob.cancelAndJoin()
                detailJob.cancelAndJoin()
                readerPresenter.awaitPendingWrites()
                detailPresenter.awaitPendingWrites()
                mockClient.close()
                stopKoin()
                cacheDatabase.close()
                deleteTestRootPath(root)
                tempDir.toFile().deleteRecursively()
                Dispatchers.resetMain()
            }
        }

    private fun readerDetail(
        readingDirection: MangaReadingDirection? = MangaReadingDirection.Rtl,
        chapterGroups: ImmutableList<UiMangaChapterGroup>,
    ): UiMangaDetail =
        UiMangaDetail(
            pluginId = "dev.example.manga",
            platformId = "ExampleManga",
            mangaKey = mangaKey,
            accountType = accountType,
            title = "Sample Manga",
            altTitles = persistentListOf(),
            cover = null,
            author = null,
            authors = persistentListOf(),
            artists = persistentListOf(),
            status = UiMangaStatus.Ongoing,
            content = "Synopsis".toUiPlainText(),
            url = "https://manga.example/series-1",
            updatedAt = Instant.parse("2026-01-01T00:00:00Z").toUi(),
            readingDirection = readingDirection,
            tagGroups = persistentListOf(),
            chapterGroups = chapterGroups,
            isBookmarked = false,
            bookmarkAction = ClickEvent.Noop,
            actions = persistentListOf(),
            sourceLanguages = persistentListOf(),
        )

    private fun readyPages(
        count: Int,
        prefix: String,
    ): ImmutableList<UiMangaPage> =
        (0 until count)
            .map { idx ->
                UiMangaPage.Ready(
                    id = "$prefix-$idx",
                    media =
                        UiMedia.Image(
                            url = "https://manga.example/$prefix-$idx.jpg",
                            previewUrl = "https://manga.example/$prefix-$idx.jpg",
                            description = null,
                            height = 1200f,
                            width = 800f,
                            sensitive = false,
                        ),
                )
            }.toImmutableList()

    private suspend fun <T : Any> awaitReaderState(
        states: MutableStateFlow<T?>,
        predicate: (T) -> Boolean,
    ): T =
        withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                states.filterNotNull().first(predicate)
            }
        }
}

private class FakeStaticPagesMangaDataSource(
    private val detail: UiMangaDetail,
    private val pagesByChapter: Map<String, ImmutableList<UiMangaPage>>,
) : MangaDataSource {
    private val cache = MutableStateFlow<UiMangaDetail?>(null)

    override fun mangaDetail(mangaKey: MicroBlogKey): Cacheable<UiMangaDetail> =
        Cacheable(
            fetchSource = { cache.value = detail },
            cacheSource = { cache.filterNotNull() },
        )

    override suspend fun chapterPages(
        mangaKey: MicroBlogKey,
        chapterId: String,
    ): ImmutableList<UiMangaPage> = pagesByChapter.getValue(chapterId)

    override suspend fun resolvePageMedia(
        mangaKey: MicroBlogKey,
        chapterId: String,
        pageId: String,
        pageToken: String,
    ): UiMedia = error("Unused")
}

private class FakeReaderAccountService(
    private val accountKey: MicroBlogKey,
    private val mangaDataSource: MangaDataSource,
) : AccountService {
    override suspend fun deleteAccounts(
        platformId: String,
        ownsCredential: (String) -> Boolean,
        beforeDelete: suspend () -> Unit,
    ) = Unit

    override fun accountServiceFlow(accountType: AccountType): Flow<MicroblogDataSource> =
        flowOf(
            object : MicroblogDataSource, DataSourceCapabilityProvider {
                override val authenticatedAccountKey: MicroBlogKey = accountKey
                override val capabilitySet: DataSourceCapabilitySet = DataSourceCapabilitySet(manga = mangaDataSource)

                override fun homeTimeline(): RemoteLoader<UiTimelineV2> = error("Unused")

                override fun userTimeline(
                    userKey: MicroBlogKey,
                    mediaOnly: Boolean,
                ): RemoteLoader<UiTimelineV2> = error("Unused")

                override fun context(statusKey: MicroBlogKey): RemoteLoader<UiTimelineV2> = error("Unused")

                override fun searchStatus(query: String): RemoteLoader<UiTimelineV2> = error("Unused")

                override fun searchUser(query: String): RemoteLoader<UiProfile> = error("Unused")

                override fun discoverUsers(): RemoteLoader<UiProfile> = error("Unused")

                override fun discoverStatuses(): RemoteLoader<UiTimelineV2> = error("Unused")

                override fun discoverHashtags(): RemoteLoader<UiHashtag> = error("Unused")

                override fun following(userKey: MicroBlogKey): RemoteLoader<UiProfile> = error("Unused")

                override fun fans(userKey: MicroBlogKey): RemoteLoader<UiProfile> = error("Unused")

                override suspend fun profileTabs(userKey: MicroBlogKey): ImmutableList<ProfileTab> = persistentListOf()
            },
        )

    override fun allAccountServicesFlow(): Flow<List<AccountMicroblogDataSource>> = flowOf(emptyList())

    override suspend fun <T : Any> addAccount(
        account: UiAccount,
        credential: T,
        serializer: KSerializer<T>,
        beforeCommit: () -> Unit,
    ) = Unit

    override fun <T : Any> credentialFlow(
        accountKey: MicroBlogKey,
        serializer: KSerializer<T>,
    ): Flow<T> = error("Unused")

    override fun <T : Any> updateCredential(
        accountKey: MicroBlogKey,
        credential: T,
        serializer: KSerializer<T>,
    ): Job = Job()
}
