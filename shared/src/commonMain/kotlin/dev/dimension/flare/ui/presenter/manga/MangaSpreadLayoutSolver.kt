package dev.dimension.flare.ui.presenter.manga

import androidx.compose.runtime.Immutable
import dev.dimension.flare.ui.model.MangaReadingDirection

@Immutable
public sealed interface ReaderSlot {
    public val primaryPageIndex: Int

    public fun contains(pageIndex: Int): Boolean

    @Immutable
    public data class Single(
        public val pageIndex: Int,
    ) : ReaderSlot {
        override val primaryPageIndex: Int
            get() = pageIndex

        override fun contains(pageIndex: Int): Boolean = this.pageIndex == pageIndex
    }

    @Immutable
    public data class Double(
        public val leftPageIndex: Int,
        public val rightPageIndex: Int,
        override val primaryPageIndex: Int,
    ) : ReaderSlot {
        override fun contains(pageIndex: Int): Boolean = leftPageIndex == pageIndex || rightPageIndex == pageIndex
    }
}

public object MangaSpreadLayoutSolver {
    public fun buildSlots(
        pageCount: Int,
        direction: MangaReadingDirection,
        enabled: Boolean,
        coverSinglePage: Boolean = true,
        isLandscapePage: (Int) -> Boolean = { false },
    ): List<ReaderSlot> {
        if (pageCount <= 0) {
            return emptyList()
        }
        if (!enabled || direction == MangaReadingDirection.Webtoon) {
            return (0 until pageCount).map(ReaderSlot::Single)
        }
        val slots = ArrayList<ReaderSlot>(pageCount)
        var i = 0
        while (i < pageCount) {
            if ((i == 0 && coverSinglePage) || isLandscapePage(i) || i + 1 >= pageCount || isLandscapePage(i + 1)) {
                slots.add(ReaderSlot.Single(i))
                i += 1
            } else {
                val leftPageIndex = if (direction == MangaReadingDirection.Rtl) i + 1 else i
                val rightPageIndex = if (direction == MangaReadingDirection.Rtl) i else i + 1
                slots.add(
                    ReaderSlot.Double(
                        leftPageIndex = leftPageIndex,
                        rightPageIndex = rightPageIndex,
                        primaryPageIndex = i,
                    ),
                )
                i += 2
            }
        }
        return slots
    }

    public fun slotIndexForPage(
        slots: List<ReaderSlot>,
        pageIndex: Int,
    ): Int {
        if (slots.isEmpty()) {
            return 0
        }
        val exact = slots.indexOfFirst { it.contains(pageIndex) }
        if (exact >= 0) {
            return exact
        }
        return when {
            pageIndex <= slots.first().primaryPageIndex -> 0
            pageIndex >= slots.last().primaryPageIndex -> slots.lastIndex
            else -> slots.indexOfLast { it.primaryPageIndex <= pageIndex }.coerceAtLeast(0)
        }
    }
}
