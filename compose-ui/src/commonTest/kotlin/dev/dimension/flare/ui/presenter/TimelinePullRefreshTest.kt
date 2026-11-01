package dev.dimension.flare.ui.presenter

import dev.dimension.flare.common.PagingState
import dev.dimension.flare.ui.model.UiTimelineV2
import dev.dimension.flare.ui.presenter.home.RecommendationRefreshOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@OptIn(ExperimentalCoroutinesApi::class)
class TimelinePullRefreshTest {
    @Test
    fun ordinaryTimelinesKeepTheirOriginalRefreshWithoutMovingTheList() =
        runTest {
            val state = RefreshState(supported = false)
            val effects = mutableListOf<String>()
            state.refreshTimelineFromPull({ effects += "top" }, { effects += it.name })
            assertEquals(1, state.ordinaryRefreshes)
            assertEquals(0, state.recommendationRefreshes)
            assertEquals(emptyList(), effects)
        }

    @Test
    fun forYouScrollsToTopOnlyAfterTheNewBatchHasCommitted() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val state = RefreshState(supported = true, outcome = RecommendationRefreshOutcome.Updated, gate = gate)
            val effects = mutableListOf<String>()
            val job = launch { state.refreshTimelineFromPull({ effects += "top" }, { effects += it.name }) }
            runCurrent()
            assertEquals(1, state.recommendationRefreshes)
            assertEquals(0, state.ordinaryRefreshes)
            assertEquals(emptyList(), effects)
            gate.complete(Unit)
            job.join()
            assertEquals(listOf("top"), effects)
        }

    @Test
    fun noNewRecommendationsKeepTheCurrentPositionAndReportNoNew() =
        runTest {
            val effects = mutableListOf<String>()
            RefreshState(true, RecommendationRefreshOutcome.NoNew)
                .refreshTimelineFromPull({ effects += "top" }, { effects += it.name })
            assertEquals(listOf("NoNew"), effects)
        }

    @Test
    fun failedRecommendationsKeepTheCurrentPositionAndReportFailure() =
        runTest {
            val effects = mutableListOf<String>()
            RefreshState(true, RecommendationRefreshOutcome.Failed)
                .refreshTimelineFromPull({ effects += "top" }, { effects += it.name })
            assertEquals(listOf("Failed"), effects)
        }

    @Test
    fun anotherPullDoesNotStartWhileTheRecommendationRequestIsActive() =
        runTest {
            val state = RefreshState(supported = true, refreshing = true)
            state.refreshTimelineFromPull({ error("Already refreshing") }, { error("Already refreshing") })
            assertEquals(0, state.recommendationRefreshes)
            assertEquals(0, state.ordinaryRefreshes)
        }

    @Test
    fun leavingTheScreenCancelsWithoutMovingOrShowingAnError() =
        runTest {
            val state = RefreshState(supported = true, cancelled = true)
            assertFailsWith<CancellationException> {
                state.refreshTimelineFromPull({ error("Cancelled") }, { error("Cancelled") })
            }
            assertEquals(1, state.recommendationRefreshes)
        }

    private class RefreshState(
        supported: Boolean,
        private val outcome: RecommendationRefreshOutcome? = null,
        private val gate: CompletableDeferred<Unit>? = null,
        refreshing: Boolean = false,
        private val cancelled: Boolean = false,
    ) : TimelineItemPresenter.State {
        override val supportsRecommendationRefresh = supported
        override val isRefreshing = refreshing
        override val listState: PagingState<UiTimelineV2> get() = error("Refresh routing must not read paging data")
        var ordinaryRefreshes = 0
        var recommendationRefreshes = 0

        override fun refreshSync() {
            ordinaryRefreshes++
        }

        override suspend fun refreshSuspend() = error("A pull must not invoke the automatic refresh route")

        override suspend fun refreshForPull(): RecommendationRefreshOutcome? {
            recommendationRefreshes++
            if (cancelled) throw CancellationException("Screen left")
            gate?.await()
            return outcome
        }
    }
}
