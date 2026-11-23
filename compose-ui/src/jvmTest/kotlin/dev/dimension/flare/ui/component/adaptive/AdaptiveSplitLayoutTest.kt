package dev.dimension.flare.ui.component.adaptive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.assertTopPositionInRootIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.dimension.flare.ui.component.platform.LocalWindowSizeClass
import dev.dimension.flare.ui.component.platform.WindowSizeClass
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdaptiveSplitLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun verticalFoldSplitsMediumScreenAndMirrorsInRtl() {
        var layoutDirection by mutableStateOf(LayoutDirection.Ltr)
        lateinit var splitState: SplitPaneState
        composeRule.setContent {
            splitState = rememberSplitPaneState()
            CompositionLocalProvider(
                LocalLayoutDirection provides layoutDirection,
                LocalFoldHingeOverride provides FoldHinge(Rect(340f, 0f, 360f, 800f), isVertical = true, isOccluding = true),
            ) {
                Box(Modifier.size(width = 700.dp, height = 800.dp)) {
                    AdaptiveSplitLayout(
                        primaryWidthStrategy = PrimaryWidthStrategy.PostDetailDefault,
                        state = splitState,
                        isBigScreen = false,
                        divider = { Box(Modifier.fillMaxSize().testTag("divider")) },
                        compactContent = { Box(Modifier.fillMaxSize().testTag("compact")) },
                        primaryPane = { _, _ -> Box(Modifier.fillMaxSize().testTag("primary")) },
                        secondaryPane = { _, _ -> Box(Modifier.fillMaxSize().testTag("secondary")) },
                    )
                }
            }
        }

        assertTrue(splitState.mode.isSplit)
        composeRule.onNodeWithTag("primary").assertLeftPositionInRootIsEqualTo(0.dp).assertWidthIsEqualTo(340.dp)
        composeRule.onNodeWithTag("divider").assertLeftPositionInRootIsEqualTo(340.dp).assertWidthIsEqualTo(20.dp)
        composeRule.onNodeWithTag("secondary").assertLeftPositionInRootIsEqualTo(360.dp).assertWidthIsEqualTo(340.dp)

        composeRule.runOnIdle { layoutDirection = LayoutDirection.Rtl }
        composeRule.onNodeWithTag("primary").assertLeftPositionInRootIsEqualTo(360.dp).assertWidthIsEqualTo(340.dp)
        composeRule.onNodeWithTag("divider").assertLeftPositionInRootIsEqualTo(340.dp).assertWidthIsEqualTo(20.dp)
        composeRule.onNodeWithTag("secondary").assertLeftPositionInRootIsEqualTo(0.dp).assertWidthIsEqualTo(340.dp)
    }

    @Test
    fun deckColumnStaysCompactWithATabletopHinge() {
        var splitEnabled by mutableStateOf(true)
        composeRule.setContent {
            CompositionLocalProvider(LocalAdaptiveSplitEnabled provides splitEnabled) {
                Box(Modifier.size(width = 360.dp, height = 700.dp)) {
                    AdaptiveSplitLayout(
                        primaryWidthStrategy = PrimaryWidthStrategy.PostDetailDefault,
                        isBigScreen = false,
                        hinge = FoldHinge(Rect(0f, 330f, 1_000f, 330f), isVertical = false),
                        compactContent = { Box(Modifier.fillMaxSize().testTag("compact")) },
                        primaryPane = { _, _ -> Box(Modifier.fillMaxSize().testTag("primary")) },
                        secondaryPane = { _, _ -> Box(Modifier.fillMaxSize().testTag("secondary")) },
                    )
                }
            }
        }

        composeRule.onNodeWithTag("secondary").assertExists()
        composeRule.runOnIdle { splitEnabled = false }
        composeRule.onNodeWithTag("compact").assertExists()
        composeRule.onNodeWithTag("secondary").assertDoesNotExist()
    }

    @Test
    fun horizontalTabletopFoldPlacesTopAndBottomPanesWithPartitionedPadding() {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalFoldHingeOverride provides FoldHinge(Rect(0f, 320f, 800f, 336f), isVertical = false),
            ) {
                Box(Modifier.size(width = 800.dp, height = 700.dp)) {
                    AdaptiveSplitLayout(
                        primaryWidthStrategy = PrimaryWidthStrategy.PostDetailDefault,
                        isBigScreen = false,
                        contentPadding = PaddingValues(start = 12.dp, top = 64.dp, end = 16.dp, bottom = 24.dp),
                        divider = { Box(Modifier.fillMaxSize().testTag("divider")) },
                        compactContent = { Box(Modifier.fillMaxSize().testTag("compact")) },
                        primaryPane = { _, padding ->
                            Box(Modifier.fillMaxSize().padding(padding).testTag("primary-inner"))
                        },
                        secondaryPane = { _, padding ->
                            Box(Modifier.fillMaxSize().padding(padding).testTag("secondary-inner"))
                        },
                    )
                }
            }
        }

        composeRule
            .onNodeWithTag("primary-inner")
            .assertTopPositionInRootIsEqualTo(64.dp)
            .assertHeightIsEqualTo(256.dp)
            .assertLeftPositionInRootIsEqualTo(12.dp)
            .assertWidthIsEqualTo(772.dp)
        composeRule
            .onNodeWithTag("divider")
            .assertTopPositionInRootIsEqualTo(320.dp)
            .assertHeightIsEqualTo(16.dp)
        composeRule
            .onNodeWithTag("secondary-inner")
            .assertTopPositionInRootIsEqualTo(336.dp)
            .assertHeightIsEqualTo(340.dp)
            .assertLeftPositionInRootIsEqualTo(12.dp)
            .assertWidthIsEqualTo(772.dp)
    }

    @Test
    fun zeroGapSplitStillRendersInlineDivider() {
        composeRule.setContent {
            Box(Modifier.size(width = 1000.dp, height = 800.dp)) {
                AdaptiveSplitLayout(
                    primaryWidthStrategy = PrimaryWidthStrategy.PostDetailDefault,
                    isBigScreen = true,
                    unfoldedGap = 0.dp,
                    divider = { Box(Modifier.fillMaxHeight().width(1.dp).testTag("inline-divider")) },
                    compactContent = { Box(Modifier.fillMaxSize().testTag("compact")) },
                    primaryPane = { _, _ -> Box(Modifier.fillMaxSize().testTag("primary")) },
                    secondaryPane = { _, _ -> Box(Modifier.fillMaxSize().testTag("secondary")) },
                )
            }
        }

        composeRule.onNodeWithTag("primary").assertWidthIsEqualTo(460.dp)
        composeRule.onNodeWithTag("inline-divider").assertLeftPositionInRootIsEqualTo(460.dp).assertWidthIsEqualTo(1.dp)
        composeRule.onNodeWithTag("secondary").assertLeftPositionInRootIsEqualTo(461.dp).assertWidthIsEqualTo(539.dp)
        assertEquals(0, composeRule.onAllNodes(androidx.compose.ui.test.hasTestTag("compact")).fetchSemanticsNodes().size)
    }

    @Test
    fun splitPaneStateSynchronizesOuterChromeWithFoldDrivenSplitsOnMediumScreen() {
        var windowSizeClass by mutableStateOf(WindowSizeClass.Medium)
        var hinge by mutableStateOf<FoldHinge?>(null)
        var containerWidth by mutableStateOf(700.dp)
        var observedOuterChromeIsSplit by mutableStateOf(false)

        composeRule.setContent {
            CompositionLocalProvider(
                LocalWindowSizeClass provides windowSizeClass,
                LocalFoldHingeOverride provides hinge,
            ) {
                val splitState = rememberSplitPaneState()
                observedOuterChromeIsSplit = splitState.mode.isSplit
                val profileStrategy = rememberProfilePrimaryWidthStrategy(horizontalPadding = 32.dp)
                Box(Modifier.size(width = containerWidth, height = 700.dp)) {
                    AdaptiveSplitLayout(
                        primaryWidthStrategy = profileStrategy,
                        state = splitState,
                        compactContent = { Box(Modifier.fillMaxSize().testTag("profile-compact")) },
                        primaryPane = { _, _ -> Box(Modifier.fillMaxSize().testTag("profile-primary")) },
                        secondaryPane = { _, _ -> Box(Modifier.fillMaxSize().testTag("profile-secondary")) },
                    )
                }
            }
        }

        assertFalse(observedOuterChromeIsSplit)
        assertEquals(1, composeRule.onAllNodes(androidx.compose.ui.test.hasTestTag("profile-compact")).fetchSemanticsNodes().size)

        composeRule.runOnIdle {
            hinge = FoldHinge(Rect(0f, 320f, 700f, 336f), isVertical = false)
        }
        composeRule.waitForIdle()
        assertTrue(observedOuterChromeIsSplit)
        composeRule.onNodeWithTag("profile-primary").assertHeightIsEqualTo(320.dp)

        composeRule.runOnIdle {
            hinge = FoldHinge(Rect(340f, 0f, 360f, 700f), isVertical = true)
        }
        composeRule.waitForIdle()
        assertTrue(observedOuterChromeIsSplit)
        composeRule.onNodeWithTag("profile-primary").assertWidthIsEqualTo(340.dp)

        composeRule.runOnIdle {
            hinge = null
        }
        composeRule.waitForIdle()
        assertFalse(observedOuterChromeIsSplit)

        composeRule.runOnIdle {
            windowSizeClass = WindowSizeClass.Expanded
            containerWidth = 900.dp
        }
        composeRule.waitForIdle()
        assertTrue(observedOuterChromeIsSplit)
        composeRule.onNodeWithTag("profile-primary").assertWidthIsEqualTo(364.dp)

        composeRule.runOnIdle {
            windowSizeClass = WindowSizeClass.Large
            containerWidth = 1024.dp
        }
        composeRule.waitForIdle()
        assertTrue(observedOuterChromeIsSplit)
        composeRule.onNodeWithTag("profile-primary").assertWidthIsEqualTo(464.dp)
    }

    @Test
    fun derivedTitleAlphaReactsToDynamicFoldSplitChangesWithoutRememberStaleness() {
        var hinge by mutableStateOf<FoldHinge?>(null)
        var observedTitleAlpha by mutableStateOf(-1f)

        composeRule.setContent {
            CompositionLocalProvider(
                LocalWindowSizeClass provides WindowSizeClass.Medium,
                LocalFoldHingeOverride provides hinge,
            ) {
                val splitState = rememberSplitPaneState()
                val titleAlpha by remember(splitState) {
                    derivedStateOf {
                        calculateProfileTitleAlpha(
                            offset = 0f,
                            maxOffset = -200f,
                            isSplit = splitState.mode.isSplit,
                        )
                    }
                }
                observedTitleAlpha = titleAlpha
                Box(Modifier.size(width = 700.dp, height = 700.dp)) {
                    AdaptiveSplitLayout(
                        primaryWidthStrategy = PrimaryWidthStrategy.Fixed(364.dp),
                        state = splitState,
                        compactContent = { Box(Modifier.fillMaxSize()) },
                        primaryPane = { _, _ -> Box(Modifier.fillMaxSize()) },
                        secondaryPane = { _, _ -> Box(Modifier.fillMaxSize()) },
                    )
                }
            }
        }

        assertEquals(0f, observedTitleAlpha)

        composeRule.runOnIdle {
            hinge = FoldHinge(Rect(0f, 320f, 700f, 336f), isVertical = false)
        }
        composeRule.waitForIdle()
        assertEquals(1f, observedTitleAlpha)

        composeRule.runOnIdle {
            hinge = null
        }
        composeRule.waitForIdle()
        assertEquals(0f, observedTitleAlpha)
    }
}
