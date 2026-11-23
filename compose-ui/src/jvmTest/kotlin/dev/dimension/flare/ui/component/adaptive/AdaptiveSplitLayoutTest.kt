package dev.dimension.flare.ui.component.adaptive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
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
}
