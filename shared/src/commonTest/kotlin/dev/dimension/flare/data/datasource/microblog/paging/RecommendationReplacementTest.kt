package dev.dimension.flare.data.datasource.microblog.paging

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingConfig
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import androidx.room3.Room
import dev.dimension.flare.RobolectricTest
import dev.dimension.flare.data.database.cache.CacheDatabase
import dev.dimension.flare.data.database.createDatabaseDriver
import dev.dimension.flare.memoryDatabaseBuilder
import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.model.ClickEvent
import dev.dimension.flare.ui.model.UiTimelineV2
import dev.dimension.flare.ui.model.UiTranslatableText
import dev.dimension.flare.ui.presenter.home.RecommendationRefreshOutcome
import dev.dimension.flare.ui.render.toUi
import dev.dimension.flare.ui.render.toUiPlainText
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalPagingApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RecommendationReplacementTest : RobolectricTest() {
    private lateinit var database: CacheDatabase
    private val state = PagingState<OffsetFromStartPagingKey, TimelinePageItem>(emptyList(), null, PagingConfig(20), 0)

    @BeforeTest fun setup() {
        database =
            Room
                .memoryDatabaseBuilder<CacheDatabase>()
                .setDriver(createDatabaseDriver())
                .setQueryCoroutineContext(Dispatchers.Unconfined)
                .build()
    }

    @AfterTest fun tearDown() = database.close()

    @Test fun pullReplacesRetainedHistoryAndStartsTheNewCursor() =
        runTest {
            for (retain in listOf(false, true)) {
                val source = Source("featured-$retain")
                val batch = RecommendationBatchLoader(source, "for-you")
                val mediator = mediator(batch, retain)
                mediator.load(LoadType.REFRESH, state)
                assertEquals(listOf("1", "2"), ids(batch.pagingKey))
                assertEquals(RecommendationRefreshOutcome.Updated, mediator.refreshRecommendations(pageSize = 2))
                assertEquals(listOf("3", "4"), ids(batch.pagingKey))
                assertEquals("fresh-tail", database.pagingTimelineDao().getNextKey(batch.pagingKey))
                mediator.load(LoadType.APPEND, state)
                assertEquals(PagingRequest.Append("fresh-tail"), source.requests.last())
                assertEquals(listOf("3", "4", "5"), ids(batch.pagingKey))
            }
        }

    @Test fun noNewAndFilteredResultsPreserveRowsAndCursor() =
        runTest {
            val source = Source("featured")
            val batch = RecommendationBatchLoader(source, "for-you")
            val mediator = mediator(batch, true)
            mediator.load(LoadType.REFRESH, state)
            val before = database.pagingTimelineDao().getPagingKeyMetadata(batch.pagingKey)
            val rows = database.pagingTimelineDao().getByPagingKey(batch.pagingKey)
            source.fresh = { _, _ -> PagingResult(listOf(post(1), post(2))) }
            batch.markViewed(listOf(post(1), post(2)))
            assertEquals(RecommendationRefreshOutcome.NoNew, mediator.refreshRecommendations())
            source.fresh = { _, _ -> PagingResult(listOf(post(3))) }
            assertEquals(RecommendationRefreshOutcome.NoNew, mediator.refreshRecommendations(accept = { false }))
            assertEquals(rows, database.pagingTimelineDao().getByPagingKey(batch.pagingKey))
            assertEquals(before, database.pagingTimelineDao().getPagingKeyMetadata(batch.pagingKey))
        }

    @Test fun failedRequestLeavesTheOldListAvailable() =
        runTest {
            val source = Source("featured")
            val batch = RecommendationBatchLoader(source, "for-you")
            val mediator = mediator(batch, true)
            mediator.load(LoadType.REFRESH, state)
            source.fresh = { _, _ -> throw IllegalStateException("offline") }
            assertFailsWith<IllegalStateException> { mediator.refreshRecommendations() }
            assertEquals(listOf("1", "2"), ids(batch.pagingKey))
            assertEquals("old-tail", database.pagingTimelineDao().getNextKey(batch.pagingKey))
        }

    @Test fun replacementDoesNotDeleteTheOriginalSourceOrAnotherPage() =
        runTest {
            val source = Source("featured")
            val batch = RecommendationBatchLoader(source, "for-you")
            val another = RecommendationBatchLoader(source, "another-page")
            val regular = mediator(source, true)
            val other = mediator(another, true)
            val target = mediator(batch, true)
            regular.load(LoadType.REFRESH, state)
            other.load(LoadType.REFRESH, state)
            target.load(LoadType.REFRESH, state)
            val otherRows = database.pagingTimelineDao().getByPagingKey(another.pagingKey)
            val originalRows = database.pagingTimelineDao().getByPagingKey(source.pagingKey)
            assertEquals(RecommendationRefreshOutcome.Updated, target.refreshRecommendations())
            assertEquals(otherRows, database.pagingTimelineDao().getByPagingKey(another.pagingKey))
            assertEquals(originalRows, database.pagingTimelineDao().getByPagingKey(source.pagingKey))
            assertEquals(listOf("1", "2"), ids(source.pagingKey))
        }

    @Test fun cancellationBeforeCommitKeepsOldList() =
        runTest {
            val source = Source("featured")
            val batch = RecommendationBatchLoader(source, "for-you")
            val mediator = mediator(batch, true)
            mediator.load(LoadType.REFRESH, state)
            var started = false
            val gate = CompletableDeferred<Unit>()
            source.fresh = { _, _ ->
                started = true
                gate.await()
                PagingResult(listOf(post(3)))
            }
            val job = launch { mediator.refreshRecommendations() }
            runCurrent()
            assertTrue(started)
            assertEquals(listOf("1", "2"), ids(batch.pagingKey))
            job.cancel()
            gate.complete(Unit)
            job.join()
            assertEquals(listOf("1", "2"), ids(batch.pagingKey))
            assertEquals("old-tail", database.pagingTimelineDao().getNextKey(batch.pagingKey))
        }

    @Test fun appendDuringPullUsesOnlyTheCommittedNewCursor() =
        runTest {
            val source = Source("featured")
            val batch = RecommendationBatchLoader(source, "for-you")
            val mediator = mediator(batch, true)
            mediator.load(LoadType.REFRESH, state)
            val gate = CompletableDeferred<Unit>()
            var started = false
            source.fresh = { _, _ ->
                started = true
                gate.await()
                PagingResult(listOf(post(3)), nextKey = "fresh-tail")
            }
            val refreshing = launch { mediator.refreshRecommendations(pageSize = 1) }
            runCurrent()
            assertTrue(started)
            val appending = launch { mediator.load(LoadType.APPEND, state) }
            runCurrent()
            assertEquals(listOf<PagingRequest>(PagingRequest.Refresh), source.requests)
            gate.complete(Unit)
            refreshing.join()
            appending.join()
            assertEquals(PagingRequest.Append("fresh-tail"), source.requests.last())
            assertEquals(listOf("3", "5"), ids(batch.pagingKey))
        }

    @Test fun deactivatedSourceCannotCommitAfterTheRequestFinishes() =
        runTest {
            val source = Source("featured")
            val batch = RecommendationBatchLoader(source, "for-you")
            val mediator = mediator(batch, true)
            mediator.load(LoadType.REFRESH, state)
            val gate = CompletableDeferred<Unit>()
            var started = false
            var active = true
            source.fresh = { _, _ ->
                started = true
                gate.await()
                PagingResult(listOf(post(3)))
            }
            val request = launch { mediator.refreshRecommendations(isCurrent = { active }) }
            runCurrent()
            assertTrue(started)
            active = false
            gate.complete(Unit)
            request.join()
            assertTrue(request.isCancelled)
            assertEquals(listOf("1", "2"), ids(batch.pagingKey))
            assertEquals("old-tail", database.pagingTimelineDao().getNextKey(batch.pagingKey))
        }

    private fun mediator(
        loader: CacheableRemoteLoader<UiTimelineV2>,
        retain: Boolean,
    ) = TimelineRemoteMediator(
        loader = loader,
        database = database,
        allowLongText = false,
        retainHistory = retain,
    )

    private suspend fun ids(key: String) =
        database
            .pagingTimelineDao()
            .getTimelinePage(key, 0, 100)
            .map { it.statusData.statusKey.id }

    private inner class Source(
        override val pagingKey: String,
    ) : RecommendationRefreshSource {
        val requests = mutableListOf<PagingRequest>()
        var fresh: suspend (String?, List<String>) -> PagingResult<UiTimelineV2> = { _, _ ->
            PagingResult(listOf(post(3), post(4)), nextKey = "fresh-tail")
        }

        override suspend fun load(
            pageSize: Int,
            request: PagingRequest,
        ): PagingResult<UiTimelineV2> {
            requests += request
            return if (request == PagingRequest.Refresh) {
                PagingResult(listOf(post(1), post(2)), nextKey = "old-tail")
            } else {
                PagingResult(listOf(post(5)))
            }
        }

        override suspend fun loadRecommendations(
            pageSize: Int,
            cursor: String?,
            seenPostIds: List<String>,
        ) = fresh(cursor, seenPostIds)
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
            references = persistentListOf(),
            clickEvent = ClickEvent.Noop,
            accountType = AccountType.Guest,
        )
}
