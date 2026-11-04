package dev.dimension.flare.ui.presenter

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HomeTimelinePagerRotationTest {
    @get:Rule val composeRule = createComposeRule()

    private val owner =
        object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
    private var visible by mutableStateOf(true)
    private var landscape by mutableStateOf(false)
    private var lookahead by mutableStateOf(true)
    private var registry by mutableStateOf(SaveableStateRegistry(restoredValues = null) { true })
    private lateinit var pager: PagerState
    private val selected = CopyOnWriteArrayList<String>()
    private val tabIds = listOf("following", "for-you", "mixed")

    @After
    fun clearRetainedPresenter() {
        composeRule.runOnIdle { owner.viewModelStore.clear() }
    }

    @Test
    fun bothSwipeDirectionsWorkAfterPortraitLandscapeAndPortraitRecreation() {
        render()
        assertPage(1)
        swipeLeftTo(2)
        recreate(landscape = true, lookahead = true)
        assertPage(2)
        swipeRightTo(1)
        swipeRightTo(0)
        swipeLeftTo(1)
        recreate(landscape = false, lookahead = true)
        assertPage(1)
        swipeRightTo(0)
        swipeLeftTo(1)
    }

    @Test
    fun restoredTabCanSwipeWhenTheNewLandscapeTreeHasNoPreviousLookahead() {
        render()
        assertPage(1)
        // Navigation's old lookahead measurements must not survive replacement of the UI tree.
        recreate(landscape = true, lookahead = false)
        assertPage(1)
        swipeLeftTo(2)
        swipeRightTo(1)
        swipeRightTo(0)
    }

    private fun render() {
        composeRule.setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                if (visible) {
                    CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                        val state =
                            rememberHomeTimelinePagerState(
                                tabIds = tabIds,
                                initialSelectedTabId = "for-you",
                                onSelectedTab = selected::add,
                            )
                        SideEffect { pager = state }
                        if (lookahead) {
                            LookaheadScope { Pager(state) }
                        } else {
                            Pager(state)
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Composable
    private fun Pager(state: PagerState) {
        HorizontalPager(
            state = state,
            key = { tabIds[it] },
            modifier =
                Modifier
                    .size(
                        width = if (landscape) 560.dp else 240.dp,
                        height = if (landscape) 240.dp else 560.dp,
                    ).testTag(PAGER_TAG),
        ) { page ->
            Box(Modifier.fillMaxSize()) {
                BasicText(tabIds[page])
            }
        }
    }

    private fun recreate(
        landscape: Boolean,
        lookahead: Boolean,
    ) {
        val saved = composeRule.runOnIdle { registry.performSave() }
        composeRule.runOnIdle { visible = false }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            registry = SaveableStateRegistry(saved) { true }
            this.landscape = landscape
            this.lookahead = lookahead
            visible = true
        }
        composeRule.waitForIdle()
    }

    private fun swipeLeftTo(page: Int) {
        composeRule.onNodeWithTag(PAGER_TAG).performTouchInput { swipeLeft(durationMillis = 300) }
        assertPage(page)
    }

    private fun swipeRightTo(page: Int) {
        composeRule.onNodeWithTag(PAGER_TAG).performTouchInput { swipeRight(durationMillis = 300) }
        assertPage(page)
    }

    private fun assertPage(page: Int) {
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(page, pager.settledPage, "A completed swipe must switch the selected home tab")
            assertEquals(page, pager.currentPage)
            assertTrue(!pager.isScrollInProgress, "The reattached pager must finish its gesture")
        }
        composeRule.waitUntil(timeoutMillis = 5_000) { selected.lastOrNull() == tabIds[page] }
    }

    private companion object {
        const val PAGER_TAG = "home-timeline-pager"
    }
}
