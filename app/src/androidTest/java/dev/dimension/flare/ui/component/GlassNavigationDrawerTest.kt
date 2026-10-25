package dev.dimension.flare.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.WideNavigationRailState
import androidx.compose.material3.WideNavigationRailValue
import androidx.compose.material3.adaptive.navigationsuite.ExperimentalMaterial3AdaptiveNavigationSuiteApi
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.material3.rememberWideNavigationRailState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import dev.dimension.flare.data.model.appearance.GlobalAppearance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3AdaptiveNavigationSuiteApi::class)
class GlassNavigationDrawerTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun modalDrawerCapturesChangingBackdropAcrossWindowsAndKeepsTextSharp() {
        val swapColors = mutableStateOf(false)
        composeRule.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalGlobalAppearance provides GlobalAppearance.Default,
                    LocalLayoutDirection provides LayoutDirection.Ltr,
                ) {
                    NavigationSuiteScaffold2(
                        navigationSuiteItems = {},
                        secondaryItems = {},
                        wideNavigationRailState =
                            rememberWideNavigationRailState(initialValue = WideNavigationRailValue.Expanded),
                        modifier = Modifier.fillMaxSize(),
                        bottomBarAutoHideEnabled = false,
                        showFab = false,
                        layoutType = NavigationSuiteType.NavigationBar,
                        railHeader = {
                            // Transparent probe inside the real modal rail, not a substitute surface.
                            Box(
                                Modifier.size(300.dp, 240.dp).testTag(DRAWER_PROBE_TAG),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "GLASS",
                                    color = Color.Black,
                                    fontSize = 24.sp,
                                    modifier = Modifier.testTag(DRAWER_TEXT_TAG),
                                )
                            }
                        },
                    ) {
                        val left = if (swapColors.value) Color.Blue else Color.Red
                        val right = if (swapColors.value) Color.Red else Color.Blue
                        Canvas(Modifier.fillMaxSize()) {
                            val split = 150.dp.toPx()
                            drawRect(left, size = Size(split, size.height))
                            drawRect(
                                right,
                                topLeft = Offset(split, 0f),
                                size = Size((size.width - split).coerceAtLeast(0f), size.height),
                            )
                        }
                    }
                }
            }
        }

        composeRule.onNodeWithTag(DRAWER_PROBE_TAG).assertIsDisplayed()
        assertDrawerBackdrop(swapped = false)
        composeRule.runOnIdle { swapColors.value = true }
        assertDrawerBackdrop(swapped = true)

        val text =
            composeRule
                .onNodeWithTag(DRAWER_TEXT_TAG)
                .assertIsDisplayed()
                .captureToImage()
                .toPixelMap()
        var solidTextPixels = 0
        for (y in 0 until text.height) {
            for (x in 0 until text.width) {
                val color = text[x, y]
                if (color.red < 0.02f && color.green < 0.02f && color.blue < 0.02f) solidTextPixels++
            }
        }
        assertTrue(
            "Opaque text strokes must remain sharp above the optical layer; solid pixels=$solidTextPixels",
            solidTextPixels > text.width * text.height / 50,
        )
    }

    private fun assertDrawerBackdrop(swapped: Boolean) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            val pixels = composeRule.onNodeWithTag(DRAWER_PROBE_TAG).captureToImage().toPixelMap()
            // Sample away from text, blur seams, and the rounded glass boundary.
            val left = pixels[pixels.width / 4, pixels.height / 3]
            val right = pixels[pixels.width * 3 / 4, pixels.height / 3]
            if (swapped) {
                left.blue > left.red + 0.08f && right.red > right.blue + 0.08f
            } else {
                left.red > left.blue + 0.08f && right.blue > right.red + 0.08f
            }
        }
    }

    @Test
    fun glassDrawerPreservesAccountExpansionSelectionAndBackDismissal() {
        lateinit var railState: WideNavigationRailState
        var selectedCount = 0
        composeRule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalGlobalAppearance provides GlobalAppearance.Default) {
                    val state = rememberWideNavigationRailState(initialValue = WideNavigationRailValue.Expanded)
                    SideEffect { railState = state }
                    NavigationSuiteScaffold2(
                        navigationSuiteItems = {},
                        secondaryItems = {
                            expandableItem(
                                icon = { Text("A") },
                                label = { Text("Account") },
                                modifier = Modifier.testTag(ACCOUNT_TAG),
                                children = {
                                    item(
                                        selected = false,
                                        onClick = { selectedCount++ },
                                        icon = { Text("H") },
                                        label = { Text("Account timeline") },
                                        modifier = Modifier.testTag(ACCOUNT_TIMELINE_TAG),
                                    )
                                },
                            )
                        },
                        wideNavigationRailState = state,
                        modifier = Modifier.fillMaxSize(),
                        bottomBarAutoHideEnabled = false,
                        showFab = false,
                        layoutType = NavigationSuiteType.NavigationBar,
                    ) {
                        Box(Modifier.fillMaxSize().testTag(HOST_CONTENT_TAG))
                    }
                }
            }
        }

        composeRule.onNodeWithTag(ACCOUNT_TIMELINE_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(ACCOUNT_TAG).assertIsDisplayed().performClick()
        composeRule.onNodeWithTag(ACCOUNT_TIMELINE_TAG).assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals(1, selectedCount) }

        Espresso.pressBack()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            railState.currentValue == WideNavigationRailValue.Collapsed && !railState.isAnimating
        }
        composeRule.onNodeWithTag(ACCOUNT_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(ACCOUNT_TIMELINE_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(HOST_CONTENT_TAG).assertIsDisplayed()
    }

    @Test
    fun dismissAnimationMovesGlassWithTheDrawerAndLeavesNoResidue() {
        var probeBounds = Rect.Zero
        lateinit var railState: WideNavigationRailState
        composeRule.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalGlobalAppearance provides GlobalAppearance.Default,
                    LocalLayoutDirection provides LayoutDirection.Ltr,
                ) {
                    val state = rememberWideNavigationRailState(initialValue = WideNavigationRailValue.Expanded)
                    SideEffect { railState = state }
                    NavigationSuiteScaffold2(
                        navigationSuiteItems = {},
                        secondaryItems = {},
                        wideNavigationRailState = state,
                        modifier = Modifier.fillMaxSize(),
                        bottomBarAutoHideEnabled = false,
                        showFab = false,
                        layoutType = NavigationSuiteType.NavigationBar,
                        railHeader = {
                            Box(
                                Modifier
                                    .size(300.dp, 240.dp)
                                    .testTag(DRAWER_PROBE_TAG)
                                    .onGloballyPositioned {
                                        probeBounds = Rect(it.positionInWindow(), Size(it.size.width.toFloat(), it.size.height.toFloat()))
                                    },
                            )
                        },
                    ) {
                        Box(Modifier.fillMaxSize().background(Color.Blue).testTag(HOST_CONTENT_TAG))
                    }
                }
            }
        }
        composeRule.onNodeWithTag(DRAWER_PROBE_TAG).assertIsDisplayed()
        composeRule.waitForIdle()
        val expanded = composeRule.runOnIdle { probeBounds }
        val sampleX = (expanded.left + expanded.width * 0.8f).roundToInt()
        val sampleY = expanded.center.y.roundToInt()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val before = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val outsideX = before.width - 32
        val initialGlass = Color(before.getPixel(sampleX, sampleY))
        val initialOutside = Color(before.getPixel(outsideX, sampleY))
        before.recycle()
        assertTrue("The probe must initially contain visible glass", initialGlass.red > initialOutside.red + 0.1f)

        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.onNodeWithContentDescription("Close rail").performClick()
            // Freeze an intermediate frame after the menu has moved past the sampled location.
            var moving = expanded
            repeat(30) {
                if (moving.left > expanded.left - expanded.width * 0.6f) {
                    composeRule.mainClock.advanceTimeByFrame()
                    composeRule.waitForIdle()
                    moving = composeRule.runOnIdle { probeBounds }
                }
            }
            assertTrue(
                "Drawer must be partway through dismissal: $moving",
                moving.right > 0f && moving.left <= expanded.left - expanded.width * 0.6f,
            )
            val during = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            val vacated = Color(during.getPixel(sampleX, sampleY))
            val scrimOnly = Color(during.getPixel(outsideX, sampleY))
            during.recycle()
            assertTrue(
                "Glass must move with the menu; vacated=$vacated, scrimOnly=$scrimOnly, bounds=$moving",
                abs(vacated.red - scrimOnly.red) < 0.04f &&
                    abs(vacated.green - scrimOnly.green) < 0.04f &&
                    abs(vacated.blue - scrimOnly.blue) < 0.04f,
            )
            composeRule.mainClock.advanceTimeBy(2_000)
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(WideNavigationRailValue.Collapsed, railState.currentValue)
        }
        composeRule.onNodeWithTag(DRAWER_PROBE_TAG).assertDoesNotExist()
        val after = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val restored = Color(after.getPixel(sampleX, sampleY))
        after.recycle()
        assertTrue(
            "Closed drawer must leave the original background",
            restored.blue > 0.98f && restored.red < 0.02f && restored.green < 0.02f,
        )
    }

    private companion object {
        const val DRAWER_PROBE_TAG = "drawer-glass-probe"
        const val DRAWER_TEXT_TAG = "drawer-glass-text"
        const val ACCOUNT_TAG = "drawer-account"
        const val ACCOUNT_TIMELINE_TAG = "drawer-account-timeline"
        const val HOST_CONTENT_TAG = "drawer-host-content"
    }
}
