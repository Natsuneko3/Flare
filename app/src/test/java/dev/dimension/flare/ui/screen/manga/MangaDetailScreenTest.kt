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

    @Test
    fun resolveAndroidMangaTabAfterCapabilityChangeResetsRemovedTabToChaptersAndPreservesSurvivingTab() {
        val chaptersAndRecommendations = buildAndroidMangaTabs(supportsComments = false, supportsRecommendations = true)
        val chaptersAndComments = buildAndroidMangaTabs(supportsComments = true, supportsRecommendations = false)
        val chaptersOnly = buildAndroidMangaTabs(supportsComments = false, supportsRecommendations = false)
        val allThreeTabs = buildAndroidMangaTabs(supportsComments = true, supportsRecommendations = true)

        // 3-to-2 transitions when the removed tab was active -> must clamp back to Chapters (index 0)
        assertEquals(
            0,
            resolveAndroidMangaTabAfterCapabilityChange(
                selectedTab = AndroidMangaTab.Comments,
                availableTabs = chaptersAndRecommendations,
            ),
        )
        assertEquals(
            0,
            resolveAndroidMangaTabAfterCapabilityChange(
                selectedTab = AndroidMangaTab.Recommendations,
                availableTabs = chaptersAndComments,
            ),
        )

        // 3-to-2 transitions when an unaffected tab was active -> must preserve that tab's new index
        assertEquals(
            1,
            resolveAndroidMangaTabAfterCapabilityChange(
                selectedTab = AndroidMangaTab.Recommendations,
                availableTabs = chaptersAndRecommendations,
            ),
        )
        assertEquals(
            1,
            resolveAndroidMangaTabAfterCapabilityChange(
                selectedTab = AndroidMangaTab.Comments,
                availableTabs = chaptersAndComments,
            ),
        )
        assertEquals(
            0,
            resolveAndroidMangaTabAfterCapabilityChange(
                selectedTab = AndroidMangaTab.Chapters,
                availableTabs = chaptersAndRecommendations,
            ),
        )

        // 3-to-1 and 2-to-3 transitions
        assertEquals(
            0,
            resolveAndroidMangaTabAfterCapabilityChange(
                selectedTab = AndroidMangaTab.Comments,
                availableTabs = chaptersOnly,
            ),
        )
        assertEquals(
            2,
            resolveAndroidMangaTabAfterCapabilityChange(
                selectedTab = AndroidMangaTab.Recommendations,
                availableTabs = allThreeTabs,
            ),
        )
    }
}
