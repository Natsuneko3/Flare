package dev.dimension.flare.ui.presenter.manga

import androidx.compose.runtime.snapshots.Snapshot
import androidx.paging.PagingState as AndroidxPagingState
import androidx.room3.Room
import app.cash.molecule.RecompositionMode
import app.cash.molecule.moleculeFlow
import dev.dimension.flare.common.Cacheable
import dev.dimension.flare.common.PagingState
import dev.dimension.flare.common.PlatformDispatchers
import dev.dimension.flare.createTestFileSystem
import dev.dimension.flare.createTestRootPath
import dev.dimension.flare.data.database.cache.CacheDatabase
import dev.dimension.flare.data.database.createDatabaseDriver
import dev.dimension.flare.data.datasource.microblog.DataSourceCapabilityProvider
import dev.dimension.flare.data.datasource.microblog.DataSourceCapabilitySet
import dev.dimension.flare.data.datasource.microblog.MicroblogDataSource
import dev.dimension.flare.data.datasource.microblog.datasource.MangaDataSource
import dev.dimension.flare.data.datasource.microblog.paging.PagingRequest
import dev.dimension.flare.data.datasource.microblog.paging.PagingResult
import dev.dimension.flare.data.datasource.microblog.ProfileTab
import dev.dimension.flare.data.datasource.microblog.paging.RemoteLoader
import dev.dimension.flare.data.datastore.AppDataStore
import dev.dimension.flare.data.datastore.model.AppSettings
import dev.dimension.flare.data.io.OkioFileStorage
import dev.dimension.flare.data.model.ProxyRef
import dev.dimension.flare.data.repository.AccountMicroblogDataSource
import dev.dimension.flare.data.repository.AccountService
import dev.dimension.flare.data.repository.MangaProgressRepository
import dev.dimension.flare.data.repository.STATUS_HISTORY_PAGING_KEY
import dev.dimension.flare.deleteTestRootPath
import dev.dimension.flare.di.startKoin
import dev.dimension.flare.di.testSingle
import dev.dimension.flare.memoryDatabaseBuilder
import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.MicroBlogKey
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
import dev.dimension.flare.ui.model.UiTranslatableText
import dev.dimension.flare.ui.model.takeSuccess
import dev.dimension.flare.ui.render.toUi
import dev.dimension.flare.ui.render.toUiPlainText
import dev.dimension.flare.ui.route.DeeplinkRoute
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.KSerializer
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class MangaDetailPresenterTest {
    private val accountKey = MicroBlogKey("reader", "manga.example")
    private val accountType = AccountType.Specific(accountKey)
    private val mangaKey = MicroBlogKey("series-1", "manga.example")

    @Test
    fun primaryCtaAdvancesToNextUnlockedChapterInGroupWhenLastReadCompleted() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val tempDir = Files.createTempDirectory("manga-detail-test")
            val root = createTestRootPath()
            val fileStorage = OkioFileStorage(createTestFileSystem(), root)
            val appDataStore = AppDataStore(fileStorage)
            val cacheDatabase =
                Room
                    .databaseBuilder<CacheDatabase>(name = tempDir.resolve("cache.db").toAbsolutePath().toString())
                    .setDriver(createDatabaseDriver())
                    .setQueryCoroutineContext(PlatformDispatchers.IO)
                    .build()
            var now = 1_000L
            val progressRepository = MangaProgressRepository(cacheDatabase) { now++ }
            val detail =
                sampleDetail(
                    chapterGroups =
                        persistentListOf(
                            UiMangaChapterGroup(
                                id = "group-a",
                                title = UiText.Raw("Serial"),
                                chapters =
                                    persistentListOf(
                                        UiMangaChapter(
                                            id = "ch-1",
                                            title = "Chapter 1",
                                            subtitle = null,
                                            uploadedAt = null,
                                            locked = false,
                                            url = "https://manga.example/ch-1",
                                        ),
                                        UiMangaChapter(
                                            id = "ch-2",
                                            title = "Chapter 2",
                                            subtitle = null,
                                            uploadedAt = null,
                                            locked = false,
                                            url = "https://manga.example/ch-2",
                                        ),
                                        UiMangaChapter(
                                            id = "ch-3",
                                            title = "Chapter 3",
                                            subtitle = null,
                                            uploadedAt = null,
                                            locked = true,
                                            url = "https://manga.example/ch-3",
                                        ),
                                    ),
                            ),
                            UiMangaChapterGroup(
                                id = "group-b",
                                title = UiText.Raw("Tankobon"),
                                chapters =
                                    persistentListOf(
                                        UiMangaChapter(
                                            id = "vol-1",
                                            title = "Volume 1",
                                            subtitle = null,
                                            uploadedAt = null,
                                            locked = false,
                                            url = "https://manga.example/vol-1",
                                        ),
                                    ),
                            ),
                        ),
                )
            val mangaDataSource = FakeDetailMangaDataSource(detail = detail)
            val accountService = FakeMangaAccountService(accountKey, mangaDataSource)
            val presenterJob = SupervisorJob()
            val presenterScope = CoroutineScope(dispatcher + presenterJob)
            startKoin {
                modules(
                    module {
                        testSingle<AccountService> { accountService }
                        testSingle { appDataStore }
                        testSingle { cacheDatabase }
                        testSingle { progressRepository }
                    },
                )
            }
            val presenter = MangaDetailPresenter(accountType = accountType, mangaKey = mangaKey)
            try {
                val states = MutableStateFlow<MangaDetailPresenter.State?>(null)
                presenterScope.launch {
                    moleculeFlow(RecompositionMode.Immediate) { presenter.body() }.collect { states.value = it }
                }

                val initial = awaitState(states) { it.detail is UiState.Success }
                assertEquals("group-a", initial.selectedGroupId)
                assertTrue(initial.chapterSortDescending)
                assertEquals(listOf("ch-3", "ch-2", "ch-1"), initial.chapters.map { it.chapter.id })
                assertEquals(
                    PrimaryReadTarget(
                        chapterId = "ch-1",
                        chapterTitle = "Chapter 1",
                        initialPage = 0,
                        isContinue = false,
                        isSingleChapterWork = false,
                    ),
                    initial.primaryReadTarget,
                )

                // Switching group updates chapters list, then switch back to group-a.
                initial.selectGroup("group-b")
                Snapshot.sendApplyNotifications()
                runCurrent()
                val groupBState = awaitState(states) { it.selectedGroupId == "group-b" }
                assertEquals(listOf("vol-1"), groupBState.chapters.map { it.chapter.id })
                groupBState.selectGroup("group-a")
                Snapshot.sendApplyNotifications()
                runCurrent()
                awaitState(states) { it.selectedGroupId == "group-a" }

                // Record partial progress in ch-1 -> CTA resumes ch-1 at page 5.
                progressRepository.recordPage(
                    accountType = accountType,
                    mangaKey = mangaKey,
                    chapterId = "ch-1",
                    pageIndex = 5,
                    pageCount = 20,
                    isCompleted = false,
                )
                val midChapter =
                    awaitState(states) { state ->
                        state.primaryReadTarget?.initialPage == 5 &&
                            state.chapters.first { it.chapter.id == "ch-1" }.pageCount == 20
                    }
                assertEquals(
                    PrimaryReadTarget(
                        chapterId = "ch-1",
                        chapterTitle = "Chapter 1",
                        initialPage = 5,
                        isContinue = true,
                        isSingleChapterWork = false,
                    ),
                    midChapter.primaryReadTarget,
                )
                val ch1MidItem = midChapter.chapters.first { it.chapter.id == "ch-1" }
                assertTrue(ch1MidItem.isCurrent)
                assertFalse(ch1MidItem.isRead)
                assertEquals(5, ch1MidItem.lastPageIndex)
                assertEquals(20, ch1MidItem.pageCount)

                // Mark ch-1 completed -> CTA advances to ch-2 (unlocked) at page 0 with isContinue = true.
                midChapter.setChapterRead("ch-1", isRead = true)
                Snapshot.sendApplyNotifications()
                runCurrent()
                val afterCh1Completed = awaitState(states) { it.primaryReadTarget?.chapterId == "ch-2" }
                assertEquals(
                    PrimaryReadTarget(
                        chapterId = "ch-2",
                        chapterTitle = "Chapter 2",
                        initialPage = 0,
                        isContinue = true,
                        isSingleChapterWork = false,
                    ),
                    afterCh1Completed.primaryReadTarget,
                )
                assertTrue(afterCh1Completed.chapters.first { it.chapter.id == "ch-1" }.isRead)

                // Wait for persistence to settle, then mark ch-2 completed too; ch-3 is locked so CTA must NOT target ch-3 or cross into group-b.
                withContext(Dispatchers.Default) {
                    withTimeout(5_000) {
                        progressRepository.observe(accountType, mangaKey).first { it.chapters["ch-1"]?.isCompleted == true }
                    }
                }
                progressRepository.recordPage(
                    accountType = accountType,
                    mangaKey = mangaKey,
                    chapterId = "ch-2",
                    pageIndex = 14,
                    pageCount = 15,
                    isCompleted = true,
                )
                val afterAllGroupUnlockedCompleted =
                    awaitState(states) { state ->
                        state.chapters.first { it.chapter.id == "ch-2" }.isRead &&
                            state.primaryReadTarget?.chapterId == "ch-1"
                    }
                assertEquals(
                    PrimaryReadTarget(
                        chapterId = "ch-1",
                        chapterTitle = "Chapter 1",
                        initialPage = 0,
                        isContinue = false,
                        isSingleChapterWork = false,
                    ),
                    afterAllGroupUnlockedCompleted.primaryReadTarget,
                )
            } finally {
                presenterJob.cancelAndJoin()
                presenter.awaitPendingWrites()
                stopKoin()
                cacheDatabase.close()
                deleteTestRootPath(root)
                tempDir.toFile().deleteRecursively()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun logsHistorySortsSelectsGroupGatesUnsupportedPagersAndLoadsSupportedPagers() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val tempDir = Files.createTempDirectory("manga-detail-test")
            val root = createTestRootPath()
            val fileStorage = OkioFileStorage(createTestFileSystem(), root)
            val appDataStore = AppDataStore(fileStorage)
            val cacheDatabase =
                Room
                    .databaseBuilder<CacheDatabase>(name = tempDir.resolve("cache.db").toAbsolutePath().toString())
                    .setDriver(createDatabaseDriver())
                    .setQueryCoroutineContext(PlatformDispatchers.IO)
                    .build()
            val progressRepository = MangaProgressRepository(cacheDatabase) { 5_000L }
            val singleChapterDetail =
                sampleDetail(
                    chapterGroups =
                        persistentListOf(
                            UiMangaChapterGroup(
                                id = "oneshot-group",
                                title = null,
                                chapters =
                                    persistentListOf(
                                        UiMangaChapter(
                                            id = "oneshot",
                                            title = "Oneshot",
                                            subtitle = null,
                                            uploadedAt = null,
                                            locked = false,
                                            url = "https://manga.example/oneshot",
                                        ),
                                    ),
                            ),
                        ),
                )
            var commentCalls = 0
            var recommendationCalls = 0
            val unsupportedSource =
                FakeDetailMangaDataSource(
                    detail = singleChapterDetail,
                    supportsComments = false,
                    supportsRecommendations = false,
                    onComments = { commentCalls++ },
                    onRecommendations = { recommendationCalls++ },
                )
            val serviceState = MutableStateFlow<MangaDataSource>(unsupportedSource)
            val accountService = FakeDynamicMangaAccountService(accountKey, serviceState)
            val presenterJob = SupervisorJob()
            val presenterScope = CoroutineScope(dispatcher + presenterJob)
            startKoin {
                modules(
                    module {
                        testSingle<AccountService> { accountService }
                        testSingle { appDataStore }
                        testSingle { cacheDatabase }
                        testSingle { progressRepository }
                    },
                )
            }
            val presenter = MangaDetailPresenter(accountType = accountType, mangaKey = mangaKey)
            try {
                val states = MutableStateFlow<MangaDetailPresenter.State?>(null)
                presenterScope.launch {
                    moleculeFlow(RecompositionMode.Immediate) { presenter.body() }.collect { states.value = it }
                }

                val loaded = awaitState(states) { it.detail is UiState.Success }
                assertTrue(loaded.primaryReadTarget?.isSingleChapterWork == true)
                assertFalse(loaded.supportsComments)
                assertFalse(loaded.supportsRecommendations)
                assertIs<PagingState.Empty<UiTimelineV2>>(loaded.comments)
                assertIs<PagingState.Empty<UiTimelineV2>>(loaded.recommendations)
                assertEquals(0, commentCalls)
                assertEquals(0, recommendationCalls)
                assertEquals(ProxyRef.Account(accountKey), loaded.detail.takeSuccess()?.cover?.proxy)

                // Toggle sort order and verify persistence.
                loaded.toggleChapterSort()
                Snapshot.sendApplyNotifications()
                runCurrent()
                val ascState = awaitState(states) { !it.chapterSortDescending }
                assertFalse(ascState.chapterSortDescending)
                withContext(Dispatchers.Default) {
                    withTimeout(5_000) {
                        progressRepository.observe(accountType, mangaKey).first { !it.chapterSortDescending }
                    }
                }

                // Verify history feed logged into STATUS_HISTORY_PAGING_KEY with DeeplinkRoute.Manga.Detail.
                val historyItems =
                    withContext(Dispatchers.Default) {
                        withTimeout(5_000) {
                            var snapshot = emptyList<dev.dimension.flare.data.database.cache.model.DbPagingTimelineWithStatus>()
                            while (snapshot.isEmpty()) {
                                snapshot =
                                    cacheDatabase
                                        .pagingTimelineDao()
                                        .getPagingSource(STATUS_HISTORY_PAGING_KEY)
                                        .load(
                                            androidx.paging.PagingSource.LoadParams.Refresh(
                                                key = null,
                                                loadSize = 10,
                                                placeholdersEnabled = false,
                                            ),
                                        ).let { result ->
                                            (result as? androidx.paging.PagingSource.LoadResult.Page)?.data.orEmpty()
                                        }
                            }
                            snapshot
                        }
                    }
                val savedFeed = assertIs<UiTimelineV2.Feed>(historyItems.single().status.status.data.content)
                assertEquals(
                    ClickEvent.Deeplink(DeeplinkRoute.Manga.Detail(mangaKey = mangaKey, accountType = accountType)),
                    savedFeed.clickEvent,
                )

                // Switch to a data source supporting comments and recommendations.
                val commentPost = createPost(MicroBlogKey("c-1", mangaKey.host))
                val recPost = createPost(MicroBlogKey("r-1", mangaKey.host))
                serviceState.value =
                    FakeDetailMangaDataSource(
                        detail = singleChapterDetail,
                        supportsComments = true,
                        supportsRecommendations = true,
                        commentsItems = listOf(commentPost),
                        recommendationsItems = listOf(recPost),
                        onComments = { commentCalls++ },
                        onRecommendations = { recommendationCalls++ },
                    )
                val withSocial =
                    awaitState(states) {
                        it.supportsComments &&
                            it.supportsRecommendations &&
                            it.comments is PagingState.Success &&
                            it.recommendations is PagingState.Success
                    }
                assertEquals(1, (withSocial.comments as PagingState.Success).itemCount)
                assertEquals(1, (withSocial.recommendations as PagingState.Success).itemCount)
                assertTrue(commentCalls >= 1)
                assertTrue(recommendationCalls >= 1)
            } finally {
                presenterJob.cancelAndJoin()
                presenter.awaitPendingWrites()
                stopKoin()
                cacheDatabase.close()
                deleteTestRootPath(root)
                tempDir.toFile().deleteRecursively()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun mangaTranslatePresenterTranslatesTitleAndContentAndRespectsBilingualMode() =
        runTest {
            val source =
                sampleDetail(chapterGroups = persistentListOf()).copy(
                    title = "Original Title",
                    content = "Original synopsis".toUiPlainText(),
                    sourceLanguages = persistentListOf("ja"),
                )
            val translated =
                source.copy(
                    title = "Translated Title",
                    content = "Translated synopsis".toUiPlainText(),
                )
            val config = MutableStateFlow(AppSettings.TranslateConfig())
            var requests = 0
            val presenter =
                MangaTranslatePresenter(
                    source = source,
                    targetLanguage = "en",
                    config = config,
                    translate = { _, _ ->
                        requests++
                        translated
                    },
                )
            var latest: MangaTranslatePresenter.State? = null
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                moleculeFlow(RecompositionMode.Immediate) { presenter.body() }.collect { latest = it }
            }
            runCurrent()
            val state0 = assertNotNull(latest)
            assertEquals(0, requests)
            assertEquals(source, state0.detail)

            config.value = config.value.copy(preTranslate = true)
            runCurrent()
            val state1 = assertNotNull(latest)
            assertEquals(1, requests)
            assertTrue(state1.showTranslation)
            assertEquals("Translated Title", state1.detail.title)
            assertEquals("Translated synopsis", state1.detail.content?.innerText)

            config.value = config.value.copy(showOriginalWithTranslation = true)
            runCurrent()
            val state2 = assertNotNull(latest)
            assertEquals(1, requests)
            assertEquals("Original Title\n\nTranslated Title", state2.detail.title)
            assertEquals("Original synopsis\n\nTranslated synopsis", state2.detail.content?.innerText)

            state2.toggleTranslation()
            runCurrent()
            assertEquals(source, assertNotNull(latest).detail)
        }

    private fun sampleDetail(chapterGroups: ImmutableList<UiMangaChapterGroup>): UiMangaDetail =
        UiMangaDetail(
            pluginId = "dev.example.manga",
            platformId = "ExampleManga",
            mangaKey = mangaKey,
            accountType = accountType,
            title = "Sample Manga",
            altTitles = persistentListOf(),
            cover = UiMedia.Image("https://manga.example/cover.jpg"),
            author = null,
            authors = persistentListOf("Author A"),
            artists = persistentListOf(),
            status = UiMangaStatus.Ongoing,
            content = "Synopsis".toUiPlainText(),
            url = "https://manga.example/series-1",
            updatedAt = Instant.parse("2026-01-01T00:00:00Z").toUi(),
            readingDirection = MangaReadingDirection.Rtl,
            tagGroups = persistentListOf(),
            chapterGroups = chapterGroups,
            isBookmarked = false,
            bookmarkAction = ClickEvent.Noop,
            actions = persistentListOf(),
            sourceLanguages = persistentListOf("ja"),
        )

    private fun createPost(statusKey: MicroBlogKey): UiTimelineV2.Post =
        UiTimelineV2.Post(
            platformId = "ExampleManga",
            images = persistentListOf(),
            sensitive = false,
            contentWarning = null,
            user = null,
            content = UiTranslatableText(statusKey.id.toUiPlainText()),
            actions = persistentListOf(),
            poll = null,
            statusKey = statusKey,
            card = null,
            createdAt = Instant.parse("2026-01-01T00:00:00Z").toUi(),
            emojiReactions = persistentListOf(),
            sourceChannel = null,
            visibility = null,
            replyToHandle = null,
            references = persistentListOf(),
            clickEvent = ClickEvent.Noop,
            accountType = accountType,
        )

    private suspend fun <T : Any> awaitState(
        states: MutableStateFlow<T?>,
        predicate: (T) -> Boolean,
    ): T =
        withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                states.filterNotNull().first(predicate)
            }
        }
}

private class FakeDetailMangaDataSource(
    private val detail: UiMangaDetail,
    override val supportsComments: Boolean = false,
    override val supportsRecommendations: Boolean = false,
    private val commentsItems: List<UiTimelineV2> = emptyList(),
    private val recommendationsItems: List<UiTimelineV2> = emptyList(),
    private val onComments: () -> Unit = {},
    private val onRecommendations: () -> Unit = {},
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
    ): ImmutableList<UiMangaPage> = error("Unused")

    override suspend fun resolvePageMedia(
        mangaKey: MicroBlogKey,
        chapterId: String,
        pageId: String,
        pageToken: String,
    ): UiMedia = error("Unused")

    override fun mangaComments(mangaKey: MicroBlogKey): RemoteLoader<UiTimelineV2> {
        onComments()
        return singlePageLoader(commentsItems)
    }

    override fun mangaRecommendations(mangaKey: MicroBlogKey): RemoteLoader<UiTimelineV2> {
        onRecommendations()
        return singlePageLoader(recommendationsItems)
    }

    private fun singlePageLoader(items: List<UiTimelineV2>): RemoteLoader<UiTimelineV2> =
        object : RemoteLoader<UiTimelineV2> {
            override suspend fun load(
                pageSize: Int,
                request: PagingRequest,
            ): PagingResult<UiTimelineV2> =
                when (request) {
                    PagingRequest.Refresh -> PagingResult(data = items, endOfPaginationReached = true)
                    else -> PagingResult(endOfPaginationReached = true)
                }
        }
}

private class FakeMangaAccountService(
    accountKey: MicroBlogKey,
    mangaDataSource: MangaDataSource,
) : AccountService by FakeDynamicMangaAccountService(accountKey, MutableStateFlow(mangaDataSource))

private class FakeDynamicMangaAccountService(
    private val accountKey: MicroBlogKey,
    private val mangaSourceFlow: Flow<MangaDataSource>,
) : AccountService {
    override suspend fun deleteAccounts(
        platformId: String,
        ownsCredential: (String) -> Boolean,
        beforeDelete: suspend () -> Unit,
    ) = Unit

    override fun accountServiceFlow(accountType: AccountType): Flow<MicroblogDataSource> =
        mangaSourceFlow.map { mangaSource ->
            object : MicroblogDataSource, DataSourceCapabilityProvider {
                override val authenticatedAccountKey: MicroBlogKey = accountKey
                override val capabilitySet: DataSourceCapabilitySet = DataSourceCapabilitySet(manga = mangaSource)

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
            }
        }

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
