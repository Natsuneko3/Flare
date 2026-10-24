package dev.dimension.flare.ui.presenter

import kotlin.test.Test
import kotlin.test.assertEquals

class TimelineNewPostsCountTest {
    @Test
    fun scrollingADeepTimelineReadsOnlyTheVisiblePostKey() {
        val inspected = mutableListOf<Int>()
        val remaining =
            remainingNewPostsCount(20, visibleIndex = 5_001, visibleKey = "post-5000", leadingItemCount = 1, itemCount = 10_000) {
                inspected += it
                "post-$it"
            }

        assertEquals(20, remaining)
        assertEquals(listOf(5_000), inspected)
    }

    @Test
    fun scrollingWithNoUnreadPostsDoesNotReadPostKeys() {
        val inspected = mutableListOf<Int>()
        val remaining =
            remainingNewPostsCount(0, visibleIndex = 5_000, visibleKey = "post-5000", leadingItemCount = 0, itemCount = 10_000) {
                inspected += it
                "post-$it"
            }

        assertEquals(0, remaining)
        assertEquals(emptyList(), inspected)
    }

    @Test
    fun staleLayoutSearchesOnlyTheUnreadPrefix() {
        val inspected = mutableListOf<Int>()
        val remaining =
            remainingNewPostsCount(3, visibleIndex = 5_000, visibleKey = "post-5000", leadingItemCount = 0, itemCount = 10_000) {
                inspected += it
                "post-${it - 10}"
            }

        assertEquals(3, remaining)
        assertEquals(listOf(5_000, 0, 1, 2), inspected)
    }

    @Test
    fun staleLayoutStillConsumesAVisibleUnreadPost() {
        val keys = listOf("new-0", "new-1", "new-2", "old-0", "old-1")
        val remaining =
            remainingNewPostsCount(3, visibleIndex = 0, visibleKey = "new-2", leadingItemCount = 0, itemCount = keys.size, keys::get)

        assertEquals(2, remaining)
    }

    @Test
    fun headerAndMissingKeysDoNotConsumeUnreadPosts() {
        val keys = listOf("new-0", "new-1", "new-2", "old-0")
        for (visibleKey in listOf("header", "removed", 123)) {
            assertEquals(
                3,
                remainingNewPostsCount(
                    3,
                    visibleIndex = 0,
                    visibleKey = visibleKey,
                    leadingItemCount = 1,
                    itemCount = keys.size,
                    keys::get,
                ),
            )
        }
    }

    @Test
    fun missingMeasuredKeyPreservesIndexBasedConsumption() {
        assertEquals(
            2,
            remainingNewPostsCount(3, visibleIndex = 2, visibleKey = null, leadingItemCount = 1, itemCount = 10) {
                error("An unmeasured viewport should not inspect post keys")
            },
        )
    }

    @Test
    fun lookupMatchesFullScanAcrossPagingAndLayoutUpdates() {
        val keySets =
            listOf(
                emptyList(),
                listOf("a", "b", "c", "d"),
                listOf("new-0", "new-1", "a", "b", "c"),
                listOf(null, "a", null, "c"),
            )
        for (keys in keySets) {
            for (count in 0..6) {
                for (index in 0..8) {
                    for (headers in 0..2) {
                        for (key in keys + listOf(null, "header", "removed")) {
                            val oldIndex = if (key == null) index else keys.indexOf(key)
                            val expected = if (oldIndex >= 0) minOf(count, oldIndex) else count
                            assertEquals(
                                expected,
                                remainingNewPostsCount(count, index, key, headers, keys.size, keys::get),
                                "count=$count index=$index headers=$headers key=$key keys=$keys",
                            )
                        }
                    }
                }
            }
        }
    }
}
