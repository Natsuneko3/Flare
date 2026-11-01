package dev.dimension.flare.ui.presenter

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import dev.dimension.flare.common.onSuccess
import dev.dimension.flare.common.toPagingState
import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.model.ClickEvent
import dev.dimension.flare.ui.model.UiTimelineV2
import dev.dimension.flare.ui.model.UiTranslatableText
import dev.dimension.flare.ui.render.toUi
import dev.dimension.flare.ui.render.toUiPlainText
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlin.time.TimeSource

class RecommendationExposureUiTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun onlyAnActivePageReportsVisiblePostsAndDeactivationStopsNewExposure() {
        var active by mutableStateOf(false)
        val seen = CopyOnWriteArrayList<String>()
        val scroll = render(supported = true, active = { active }, seen = seen)
        waitRealTime(700)
        assertEquals(emptySet(), seen.toSet(), "A retained hidden page must not mark its prefetched posts as read")

        composeRule.runOnIdle { active = true }
        composeRule.waitUntil(timeoutMillis = 5_000) { seen.containsAll(listOf("0", "1")) }
        assertEquals(setOf("0", "1"), seen.toSet(), "Only two post cards are visible below the leading header")

        composeRule.runOnIdle { active = false }
        composeRule.waitForIdle()
        composeRule.runOnIdle { scroll.requestScrollToItem(8) }
        composeRule.waitForIdle()
        waitRealTime(700)
        assertEquals(setOf("0", "1"), seen.toSet(), "Newly measured posts remain unread while this page is inactive")

        composeRule.runOnIdle { active = true }
        composeRule.waitUntil(timeoutMillis = 5_000) { seen.containsAll(listOf("7", "8", "9")) }
        assertEquals(setOf("0", "1", "7", "8", "9"), seen.toSet())
    }

    @Test
    fun ordinaryTimelinesNeverReportRecommendationsEvenWhileVisible() {
        val seen = CopyOnWriteArrayList<String>()
        render(supported = false, active = { true }, seen = seen)
        waitRealTime(700)
        assertEquals(emptySet(), seen.toSet())
    }

    private fun render(
        supported: Boolean,
        active: () -> Boolean,
        seen: MutableList<String>,
    ): LazyStaggeredGridState {
        val scroll = LazyStaggeredGridState()
        val pages =
            MutableStateFlow(
                PagingData.from<UiTimelineV2>(
                    data = (0..19).map(::post),
                    sourceLoadStates =
                        LoadStates(
                            refresh = LoadState.NotLoading(endOfPaginationReached = false),
                            prepend = LoadState.NotLoading(endOfPaginationReached = true),
                            append = LoadState.NotLoading(endOfPaginationReached = false),
                        ),
                ),
            )
        composeRule.setContent {
            val paging = pages.collectAsLazyPagingItems().toPagingState()
            val state =
                object : TimelineItemPresenter.State {
                    override val listState = paging
                    override val supportsRecommendationRefresh = supported
                    override val isRefreshing = false

                    override fun refreshSync() = Unit

                    override suspend fun refreshSuspend() = Unit

                    override fun markRecommendationsViewed(items: List<UiTimelineV2>) {
                        seen += items.map { it.statusKey.id }
                    }
                }
            rememberTimelineWithLazyListState(state, scroll, leadingItemCount = 1, isCurrentlyVisible = active())
            LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Fixed(1),
                modifier = Modifier.size(300.dp, 250.dp),
                state = scroll,
            ) {
                item(key = "header") { Box(Modifier.fillMaxWidth().height(100.dp)) }
                paging.onSuccess {
                    items(itemCount, key = itemKey { it.stableItemKey }) { Box(Modifier.fillMaxWidth().height(100.dp)) }
                }
            }
        }
        composeRule.waitUntil(timeoutMillis = 5_000) { scroll.layoutInfo.visibleItemsInfo.size > 1 }
        return scroll
    }

    private fun waitRealTime(millis: Long) {
        val started = TimeSource.Monotonic.markNow()
        composeRule.waitUntil(timeoutMillis = millis + 2_000) { started.elapsedNow().inWholeMilliseconds >= millis }
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
