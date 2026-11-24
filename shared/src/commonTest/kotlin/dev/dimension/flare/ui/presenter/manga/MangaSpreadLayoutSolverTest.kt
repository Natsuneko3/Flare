package dev.dimension.flare.ui.presenter.manga

import dev.dimension.flare.ui.model.MangaReadingDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MangaSpreadLayoutSolverTest {
    @Test
    fun pairsRtlAndLtrPagesWithCoverOffsetAndLandscapeSplits() {
        val rtlCoverSlots =
            MangaSpreadLayoutSolver.buildSlots(
                pageCount = 5,
                direction = MangaReadingDirection.Rtl,
                enabled = true,
                coverSinglePage = true,
                isLandscapePage = { false },
            )
        assertEquals(
            listOf(
                ReaderSlot.Single(0),
                ReaderSlot.Double(leftPageIndex = 2, rightPageIndex = 1, primaryPageIndex = 1),
                ReaderSlot.Double(leftPageIndex = 4, rightPageIndex = 3, primaryPageIndex = 3),
            ),
            rtlCoverSlots,
        )
        assertTrue(rtlCoverSlots[1].contains(1))
        assertTrue(rtlCoverSlots[1].contains(2))
        assertFalse(rtlCoverSlots[1].contains(0))
        assertEquals(listOf(0, 1, 1, 2, 2), (0 until 5).map { MangaSpreadLayoutSolver.slotIndexForPage(rtlCoverSlots, it) })

        val ltrCoverSlots =
            MangaSpreadLayoutSolver.buildSlots(
                pageCount = 5,
                direction = MangaReadingDirection.Ltr,
                enabled = true,
                coverSinglePage = true,
                isLandscapePage = { false },
            )
        assertEquals(
            listOf(
                ReaderSlot.Single(0),
                ReaderSlot.Double(leftPageIndex = 1, rightPageIndex = 2, primaryPageIndex = 1),
                ReaderSlot.Double(leftPageIndex = 3, rightPageIndex = 4, primaryPageIndex = 3),
            ),
            ltrCoverSlots,
        )

        val rtlNoCoverOffset =
            MangaSpreadLayoutSolver.buildSlots(
                pageCount = 5,
                direction = MangaReadingDirection.Rtl,
                enabled = true,
                coverSinglePage = false,
                isLandscapePage = { false },
            )
        assertEquals(
            listOf(
                ReaderSlot.Double(leftPageIndex = 1, rightPageIndex = 0, primaryPageIndex = 0),
                ReaderSlot.Double(leftPageIndex = 3, rightPageIndex = 2, primaryPageIndex = 2),
                ReaderSlot.Single(4),
            ),
            rtlNoCoverOffset,
        )

        val landscapeSplitSlots =
            MangaSpreadLayoutSolver.buildSlots(
                pageCount = 6,
                direction = MangaReadingDirection.Rtl,
                enabled = true,
                coverSinglePage = true,
                isLandscapePage = { it == 2 },
            )
        assertEquals(
            listOf(
                ReaderSlot.Single(0),
                ReaderSlot.Single(1),
                ReaderSlot.Single(2),
                ReaderSlot.Double(leftPageIndex = 4, rightPageIndex = 3, primaryPageIndex = 3),
                ReaderSlot.Single(5),
            ),
            landscapeSplitSlots,
        )

        assertEquals(
            (0 until 4).map(ReaderSlot::Single),
            MangaSpreadLayoutSolver.buildSlots(
                pageCount = 4,
                direction = MangaReadingDirection.Rtl,
                enabled = false,
                coverSinglePage = true,
                isLandscapePage = { false },
            ),
        )
        assertEquals(
            (0 until 4).map(ReaderSlot::Single),
            MangaSpreadLayoutSolver.buildSlots(
                pageCount = 4,
                direction = MangaReadingDirection.Webtoon,
                enabled = true,
                coverSinglePage = true,
                isLandscapePage = { false },
            ),
        )
        assertEquals(
            emptyList(),
            MangaSpreadLayoutSolver.buildSlots(
                pageCount = 0,
                direction = MangaReadingDirection.Rtl,
                enabled = true,
                coverSinglePage = true,
                isLandscapePage = { false },
            ),
        )
        assertEquals(0, MangaSpreadLayoutSolver.slotIndexForPage(emptyList(), 0))
    }

    @Test
    fun runtimeLandscapeDimensionSplitsSpreadWithoutLosingActivePage() {
        val landscapePages = mutableSetOf<Int>()
        val initialSlots =
            MangaSpreadLayoutSolver.buildSlots(
                pageCount = 5,
                direction = MangaReadingDirection.Rtl,
                enabled = true,
                coverSinglePage = true,
                isLandscapePage = landscapePages::contains,
            )
        val activePageIndex = 2
        assertEquals(1, MangaSpreadLayoutSolver.slotIndexForPage(initialSlots, activePageIndex))
        assertEquals(
            ReaderSlot.Double(leftPageIndex = 2, rightPageIndex = 1, primaryPageIndex = 1),
            initialSlots[1],
        )

        // Runtime decode discovers page 2 is a wide spread (width > height).
        landscapePages.add(2)
        val recomputedSlots =
            MangaSpreadLayoutSolver.buildSlots(
                pageCount = 5,
                direction = MangaReadingDirection.Rtl,
                enabled = true,
                coverSinglePage = true,
                isLandscapePage = landscapePages::contains,
            )
        assertEquals(
            listOf(
                ReaderSlot.Single(0),
                ReaderSlot.Single(1),
                ReaderSlot.Single(2),
                ReaderSlot.Double(leftPageIndex = 4, rightPageIndex = 3, primaryPageIndex = 3),
            ),
            recomputedSlots,
        )
        val recomputedSlotIndex = MangaSpreadLayoutSolver.slotIndexForPage(recomputedSlots, activePageIndex)
        assertEquals(2, recomputedSlotIndex)
        assertEquals(ReaderSlot.Single(2), recomputedSlots[recomputedSlotIndex])
        assertTrue(recomputedSlots[recomputedSlotIndex].contains(activePageIndex))
    }
}
