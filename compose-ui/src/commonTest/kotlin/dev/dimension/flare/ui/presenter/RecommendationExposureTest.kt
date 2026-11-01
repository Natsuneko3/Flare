package dev.dimension.flare.ui.presenter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RecommendationExposureTest {
    @Test
    fun onlyMeasuredMatchingPostsInsideTheViewportAreRead() {
        val inspected = mutableListOf<Int>()
        val posts = listOf("post-a", "gap", "post-c", "post-prefetched")
        val visible =
            recommendationVisibleItems(
                measuredItems =
                    listOf(
                        RecommendationViewportItem(0, "header", 0, 40),
                        RecommendationViewportItem(1, "post-a", 40, 140),
                        RecommendationViewportItem(2, "gap", 140, 180),
                        RecommendationViewportItem(3, "stale-key", 180, 240),
                        RecommendationViewportItem(4, "post-prefetched", 300, 400),
                        RecommendationViewportItem(99, "invalid-index", 40, 140),
                    ),
                viewportStart = 0,
                viewportEnd = 300,
                leadingItemCount = 1,
                itemCount = posts.size,
                peek = { index -> posts[index].also { inspected += index } },
                postKey = { it.takeIf { key -> key.startsWith("post-") } },
            )

        assertEquals(listOf("post-a"), visible)
        assertEquals(listOf(0, 1, 2), inspected)
    }

    @Test
    fun offscreenAndZeroHeightItemsNeverTouchPagingData() {
        assertEquals(
            emptyList(),
            recommendationVisibleItems<String>(
                measuredItems =
                    listOf(
                        RecommendationViewportItem(0, "above", -100, 0),
                        RecommendationViewportItem(1, "below", 300, 400),
                        RecommendationViewportItem(2, "zero", 100, 100),
                    ),
                viewportStart = 0,
                viewportEnd = 300,
                leadingItemCount = 0,
                itemCount = 3,
                peek = { error("Offscreen items must not access paging") },
                postKey = { it },
            ),
        )
    }

    @Test
    fun aPostMustRemainVisibleForHalfASecond() {
        val tracker = RecommendationExposureTracker<String>()
        val visible = mapOf("a" to "post-a")
        assertEquals(emptyList(), tracker.update(visible, 100))
        assertEquals(500L, tracker.nextDelay(100))
        assertEquals(emptyList(), tracker.update(visible, 599))
        assertEquals(1L, tracker.nextDelay(599))
        assertEquals(listOf("post-a"), tracker.update(visible, 600))
        assertNull(tracker.nextDelay(600))
        assertEquals(emptyList(), tracker.update(visible, 1000))
    }

    @Test
    fun continuouslyVisiblePostsKeepTheirDwellWhenAnotherPostEnters() {
        val tracker = RecommendationExposureTracker<String>()
        tracker.update(mapOf("a" to "post-a"), 0)
        tracker.update(mapOf("a" to "post-a", "b" to "post-b"), 300)
        assertEquals(listOf("post-a"), tracker.update(mapOf("a" to "post-a", "b" to "post-b"), 500))
        assertEquals(300L, tracker.nextDelay(500))
        assertEquals(listOf("post-b"), tracker.update(mapOf("b" to "post-b"), 800))
    }

    @Test
    fun scrollingPastAPostDoesNotCountItAndReturningRestartsItsDwell() {
        val tracker = RecommendationExposureTracker<String>()
        tracker.update(mapOf("a" to "post-a"), 0)
        assertEquals(emptyList(), tracker.update(emptyMap(), 400))
        assertNull(tracker.nextDelay(500))
        assertEquals(emptyList(), tracker.update(mapOf("a" to "post-a"), 700))
        assertEquals(emptyList(), tracker.update(mapOf("a" to "post-a"), 1199))
        assertEquals(listOf("post-a"), tracker.update(mapOf("a" to "post-a"), 1200))
    }
}
