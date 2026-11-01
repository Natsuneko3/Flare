package dev.dimension.flare.ui.presenter

import androidx.paging.LoadState
import androidx.room3.Room
import app.cash.molecule.RecompositionMode
import app.cash.molecule.moleculeFlow
import dev.dimension.flare.common.PagingState
import dev.dimension.flare.common.TestFormatter
import dev.dimension.flare.createTestFileSystem
import dev.dimension.flare.createTestRootPath
import dev.dimension.flare.data.database.app.AppDatabase
import dev.dimension.flare.data.database.cache.CacheDatabase
import dev.dimension.flare.data.database.createDatabaseDriver
import dev.dimension.flare.data.datasource.microblog.paging.PagingRequest
import dev.dimension.flare.data.datasource.microblog.paging.PagingResult
import dev.dimension.flare.data.datasource.microblog.paging.RecommendationBatchLoader
import dev.dimension.flare.data.datasource.microblog.paging.RecommendationRefreshSource
import dev.dimension.flare.data.datastore.AppDataStore
import dev.dimension.flare.data.datastore.model.HomeTimelinePosition
import dev.dimension.flare.data.io.OkioFileStorage
import dev.dimension.flare.data.model.tab.TimelineResolver
import dev.dimension.flare.data.repository.LocalFilterRepository
import dev.dimension.flare.data.repository.MxgaRepository
import dev.dimension.flare.data.repository.SettingsRepository
import dev.dimension.flare.data.translation.NoopPreTranslationService
import dev.dimension.flare.data.translation.PreTranslationService
import dev.dimension.flare.deleteTestRootPath
import dev.dimension.flare.di.startKoin
import dev.dimension.flare.di.testSingle
import dev.dimension.flare.memoryDatabaseBuilder
import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.model.PlatformRegistry
import dev.dimension.flare.testPlatformRegistry
import dev.dimension.flare.testPlatformRuntimeData
import dev.dimension.flare.ui.humanizer.PlatformFormatter
import dev.dimension.flare.ui.model.ClickEvent
import dev.dimension.flare.ui.model.TimelineReadingPosition
import dev.dimension.flare.ui.model.UiTimelineV2
import dev.dimension.flare.ui.model.UiTranslatableText
import dev.dimension.flare.ui.model.contentPostOrNull
import dev.dimension.flare.ui.presenter.home.RecommendationRefreshOutcome
import dev.dimension.flare.ui.presenter.home.TimelinePresenter
import dev.dimension.flare.ui.presenter.home.TimelineState
import dev.dimension.flare.ui.render.toUi
import dev.dimension.flare.ui.render.toUiPlainText
import dev.dimension.flare.unavailableAccountService
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class RecommendationPagerRefreshTest {
    @Test fun exhaustedAppendCanContinueAfterPullWithoutRefetchingTheHead() = refresh()

    @Test fun failedAppendCanContinueAfterPullWithoutRetryingTheOldCursor() = refresh(appendFails = true)

    @Test fun rememberedReadingDoesNotRestoreThePreviousBatchPosition() = refresh(remember = true)

    @Test fun cancelledUiAfterCacheCommitStillRebuildsThePager() = refresh(cancelAfterCommit = true)

    private fun refresh(
        appendFails: Boolean = false,
        remember: Boolean = false,
        cancelAfterCommit: Boolean = false,
    ) = runBlocking {
        val main = Executors.newSingleThreadExecutor { Thread(it, "recommendation-ui") }.asCoroutineDispatcher()
        Dispatchers.setMain(main)
        val root = createTestRootPath()
        val storage = OkioFileStorage(createTestFileSystem(), root)
        val cache =
            Room
                .memoryDatabaseBuilder<CacheDatabase>()
                .setDriver(
                    createDatabaseDriver(),
                ).setQueryCoroutineContext(Dispatchers.IO)
                .build()
        val app =
            Room
                .memoryDatabaseBuilder<AppDatabase>()
                .setDriver(
                    createDatabaseDriver(),
                ).setQueryCoroutineContext(Dispatchers.IO)
                .build()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val appData = AppDataStore(storage)
            appData.appSettingsStore.updateData {
                it.copy(rememberHomeTimelinePosition = remember, refreshHomeTimelineOnLaunch = true, mxgaEnabled = false)
            }
            val settings =
                SettingsRepository(storage, appData, TimelineResolver(testPlatformRuntimeData(), unavailableAccountService()), cache)
            startKoin {
                modules(
                    module {
                        testSingle { cache }
                        testSingle { appData }
                        testSingle { settings }
                        testSingle<CoroutineScope> { scope }
                        testSingle<PreTranslationService> { NoopPreTranslationService }
                        testSingle { LocalFilterRepository(app, scope) }
                        testSingle { MxgaRepository(storage, settings, scope) }
                        testSingle<PlatformRegistry> { testPlatformRegistry() }
                        testSingle<PlatformFormatter> { TestFormatter() }
                    },
                )
            }
            val source = Source(appendFails, pauseFirstFresh = cancelAfterCommit)
            val batch = RecommendationBatchLoader(source, "for-you")
            val presenter = TimelinePresenter(tabId = "for-you", loader = flowOf(batch), isHomeTimeline = true)
            val models = MutableStateFlow<TimelineState?>(null)
            scope.launch(Dispatchers.Main) {
                moleculeFlow(RecompositionMode.Immediate) { presenter.body() }.collect { models.value = it }
            }
            awaitModel(models, "Initial recommendation batch did not load") { it.ids().firstOrNull() == "1" }
            withContext(Dispatchers.Main) {
                val state = assertNotNull(models.value)
                state.reading?.restorationFinished()
                if (remember) state.reading?.positionChanged(12, -17.5)
                state.markRecommendationsViewed(listOf(post(1)))
                val items = state.listState as PagingState.Success
                items[items.itemCount - 1]
            }
            awaitModel(models, "Old append never reached the expected terminal state") {
                val append = (it.listState as? PagingState.Success)?.appendState
                if (appendFails) append is LoadState.Error else append is LoadState.NotLoading && append.endOfPaginationReached
            }
            if (remember) {
                appData.homeTimelinePreferences.updateData {
                    it.copy(positions = mapOf("for-you" to HomeTimelinePosition(batch.pagingKey, TimelineReadingPosition(12, -17.5), 1)))
                }
            }
            if (cancelAfterCommit) {
                val caller = scope.launch(Dispatchers.Main) { assertNotNull(models.value).refreshForPull() }
                source.firstFreshStarted.await()
                val mainBlocked = CompletableDeferred<Unit>()
                val resumeMain = CountDownLatch(1)
                val blocker =
                    scope.launch(Dispatchers.Main) {
                        mainBlocked.complete(Unit)
                        check(resumeMain.await(8, TimeUnit.SECONDS))
                    }
                try {
                    mainBlocked.await()
                    source.resumeFirstFresh.complete(Unit)
                    assertNotNull(
                        withTimeoutOrNull(5_000) {
                            while (cache.pagingTimelineDao().getNextKey(batch.pagingKey) != "fresh-1") delay(10)
                            Unit
                        },
                        "The replacement must commit before cancelling the UI's suspended pull",
                    )
                    caller.cancel()
                } finally {
                    resumeMain.countDown()
                }
                caller.join()
                blocker.join()
            } else {
                withContext(Dispatchers.Main) {
                    assertEquals(RecommendationRefreshOutcome.Updated, assertNotNull(models.value).refreshForPull())
                }
            }
            val first = awaitFreshAppend(models, "101", "121")
            assertEquals(1, source.headRequests.get(), "A batch replacement must not issue another ordinary head refresh")
            assertTrue(source.requests.contains(PagingRequest.Append("fresh-1")))
            if (remember) {
                assertTrue(assertNotNull(first.reading).isAtLatest)
                assertTrue(first.reading?.restorePosition?.index == null || first.reading?.restorePosition?.index == 0)
            }
            withContext(Dispatchers.Main) {
                val state = assertNotNull(models.value)
                state.markRecommendationsViewed(listOf(post(101)))
                assertEquals(RecommendationRefreshOutcome.Updated, state.refreshForPull())
            }
            awaitFreshAppend(models, "201", "221")
            assertEquals(1, source.headRequests.get())
            assertEquals(2, source.freshRequests.size)
            assertTrue(source.freshRequests[1].containsAll(listOf("1", "101")), "Pager rebuilding must retain the same viewed session")
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
            stopKoin()
            cache.close()
            app.close()
            Dispatchers.resetMain()
            main.close()
            deleteTestRootPath(root)
        }
    }

    private suspend fun awaitModel(
        models: MutableStateFlow<TimelineState?>,
        message: String,
        predicate: (TimelineState) -> Boolean,
    ): TimelineState = assertNotNull(withTimeoutOrNull(8_000) { models.filterNotNull().first { predicate(it) } }, message)

    private suspend fun awaitFreshAppend(
        models: MutableStateFlow<TimelineState?>,
        firstId: String,
        appendedId: String,
    ): TimelineState =
        assertNotNull(
            withTimeoutOrNull(5_000) {
                models.filterNotNull().first { state ->
                    val ids = state.ids()
                    if (ids.firstOrNull() == firstId) {
                        withContext(Dispatchers.Main) {
                            val items = state.listState as? PagingState.Success
                            if (items != null && items.itemCount > 0) items[items.itemCount - 1]
                        }
                    }
                    appendedId in ids
                }
            },
            "The fresh batch cursor must still append after the old batch completed or failed",
        )

    private fun TimelineState.ids(): List<String> =
        (listState as? PagingState.Success)
            ?.let { items ->
                (0 until items.itemCount).mapNotNull {
                    items
                        .peek(it)
                        ?.contentPostOrNull()
                        ?.statusKey
                        ?.id
                }
            }.orEmpty()

    private inner class Source(
        private val appendFails: Boolean,
        private val pauseFirstFresh: Boolean,
    ) : RecommendationRefreshSource {
        override val pagingKey = "featured_account"
        val headRequests = AtomicInteger()
        val requests = CopyOnWriteArrayList<PagingRequest>()
        val freshRequests = CopyOnWriteArrayList<List<String>>()
        val firstFreshStarted = CompletableDeferred<Unit>()
        val resumeFirstFresh = CompletableDeferred<Unit>()

        override suspend fun load(
            pageSize: Int,
            request: PagingRequest,
        ): PagingResult<UiTimelineV2> {
            requests += request
            return when (request) {
                PagingRequest.Refresh -> {
                    headRequests.incrementAndGet()
                    PagingResult((1..20).map(::post), nextKey = "old-tail")
                }

                is PagingRequest.Append -> {
                    when (request.nextKey) {
                        "old-tail" -> if (appendFails) error("old cursor failed") else PagingResult()
                        "fresh-1" -> PagingResult(listOf(post(121)))
                        "fresh-2" -> PagingResult(listOf(post(221)))
                        else -> error("Unexpected cursor ${request.nextKey}")
                    }
                }

                is PagingRequest.Prepend -> {
                    PagingResult()
                }
            }
        }

        override suspend fun loadRecommendations(
            pageSize: Int,
            cursor: String?,
            seenPostIds: List<String>,
        ): PagingResult<UiTimelineV2> {
            assertEquals(null, cursor)
            freshRequests += seenPostIds.toList()
            val round = freshRequests.size
            if (pauseFirstFresh && round == 1) {
                firstFreshStarted.complete(Unit)
                resumeFirstFresh.await()
            }
            val start = round * 100 + 1
            val items = (start until start + 20).map(::post)
            return PagingResult(if (round == 2) listOf(post(101)) + items else items, nextKey = "fresh-$round")
        }
    }

    private fun post(id: Int) =
        UiTimelineV2.Post(
            platformId = "xQt",
            images = persistentListOf(),
            sensitive = false,
            contentWarning = null,
            user = null,
            content = UiTranslatableText("Post $id".toUiPlainText()),
            actions = persistentListOf(),
            poll = null,
            statusKey = MicroBlogKey(id.toString(), "x.com"),
            card = null,
            createdAt = Instant.fromEpochMilliseconds(1).toUi(),
            clickEvent = ClickEvent.Noop,
            accountType = AccountType.Guest,
        )
}
