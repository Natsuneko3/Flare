package dev.dimension.flare.ui.screen.manga

import org.junit.Assert.assertEquals
import org.junit.Test

class MangaDetailScreenTest {
    @Test
    fun computeAndroidMangaTopBarAlphaGuardsZeroMaxOffsetAndClampsOverscroll() {
        assertEquals(0f, computeAndroidMangaTopBarAlpha(offset = 0f, maxOffset = 0f), 0.0001f)
        assertEquals(0f, computeAndroidMangaTopBarAlpha(offset = Float.NaN, maxOffset = 200f), 0.0001f)
        assertEquals(0f, computeAndroidMangaTopBarAlpha(offset = 50f, maxOffset = -10f), 0.0001f)
        assertEquals(0f, computeAndroidMangaTopBarAlpha(offset = -80f, maxOffset = 200f), 0.0001f)
        assertEquals(0.5f, computeAndroidMangaTopBarAlpha(offset = 100f, maxOffset = 200f), 0.0001f)
        assertEquals(1f, computeAndroidMangaTopBarAlpha(offset = 260f, maxOffset = 200f), 0.0001f)
        // NestedScrollViewState uses negative bounds (offset in maxOffset..0f, maxOffset <= 0f)
        assertEquals(0f, computeAndroidMangaTopBarAlpha(offset = 0f, maxOffset = -200f), 0.0001f)
        assertEquals(0.5f, computeAndroidMangaTopBarAlpha(offset = -100f, maxOffset = -200f), 0.0001f)
        assertEquals(1f, computeAndroidMangaTopBarAlpha(offset = -260f, maxOffset = -200f), 0.0001f)
    }

    @Test
    fun buildAndroidMangaTabsAndClampSelectionHandlesCapabilityChanges() {
        assertEquals(listOf(AndroidMangaTab.Chapters), buildAndroidMangaTabs(false, false))
        assertEquals(
            listOf(AndroidMangaTab.Chapters, AndroidMangaTab.Comments),
            buildAndroidMangaTabs(true, false),
        )
        assertEquals(
            listOf(AndroidMangaTab.Chapters, AndroidMangaTab.Recommendations),
            buildAndroidMangaTabs(false, true),
        )
        assertEquals(
            listOf(AndroidMangaTab.Chapters, AndroidMangaTab.Comments, AndroidMangaTab.Recommendations),
            buildAndroidMangaTabs(true, true),
        )
        assertEquals(0, resolveActiveAndroidMangaTabIndex(currentPage = 0, tabCount = 0))
        assertEquals(0, resolveActiveAndroidMangaTabIndex(currentPage = -1, tabCount = 3))
        assertEquals(0, resolveActiveAndroidMangaTabIndex(currentPage = 2, tabCount = 1))
        assertEquals(1, resolveActiveAndroidMangaTabIndex(currentPage = 1, tabCount = 2))
        assertEquals(1, resolveActiveAndroidMangaTabIndex(currentPage = 2, tabCount = 2))
    }
}
