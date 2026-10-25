package dev.dimension.flare.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.ExperimentalMaterial3AdaptiveNavigationSuiteApi
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.material3.rememberWideNavigationRailState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.scene.DialogSceneStrategy
import androidx.test.platform.app.InstrumentationRegistry
import dev.dimension.flare.R
import dev.dimension.flare.data.model.BottomBarBehavior
import dev.dimension.flare.data.model.BottomBarStyle
import dev.dimension.flare.data.model.appearance.GlobalAppearance
import dev.dimension.flare.ui.route.AndroidNavDisplay
import dev.dimension.flare.ui.route.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import soup.compose.material.motion.animation.materialSharedAxisZ
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3AdaptiveNavigationSuiteApi::class)
class NavigationSuiteScaffold2Test {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun navigationTransitionDoesNotDrawOverNavigationRail() {
        composeRule.mainClock.autoAdvance = false
        val backStack = mutableStateListOf<Route>(Route.Home)

        composeRule.setContent {
            MaterialTheme {
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalGlobalAppearance provides GlobalAppearance.Default,
                ) {
                    NavigationSuiteScaffold2(
                        navigationSuiteItems = {},
                        secondaryItems = {},
                        wideNavigationRailState = rememberWideNavigationRailState(),
                        modifier = Modifier.fillMaxSize(),
                        bottomBarAutoHideEnabled = false,
                        showFab = false,
                        layoutType = NavigationSuiteType.NavigationRail,
                        containerColor = NAVIGATION_RAIL_COLOR,
                    ) {
                        AndroidNavDisplay(
                            cornerShape = null,
                            onBack = { backStack.removeLastOrNull() },
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .testTag(NAV_DISPLAY_TAG),
                            sceneStrategies = emptyList(),
                            transitionSpec = { materialSharedAxisZ(true) },
                            popTransitionSpec = { materialSharedAxisZ(false) },
                            entries =
                                rememberDecoratedNavEntries(
                                    backStack = backStack,
                                    entryDecorators = emptyList(),
                                    entryProvider =
                                        entryProvider {
                                            entry<Route.Home> {
                                                Box(
                                                    Modifier
                                                        .fillMaxSize()
                                                        .background(Color.Red),
                                                )
                                            }
                                            entry<Route.Notification> {
                                                Box(
                                                    Modifier
                                                        .fillMaxSize()
                                                        .background(Color.Blue),
                                                )
                                            }
                                        },
                                ),
                        )
                    }
                }
            }
        }

        composeRule.waitForIdle()
        val navBounds =
            composeRule
                .onNodeWithTag(NAV_DISPLAY_TAG)
                .fetchSemanticsNode()
                .boundsInRoot
        val sampleX = navBounds.left.roundToInt() - 2
        val sampleY = navBounds.center.y.roundToInt()
        val railColorBeforeTransition =
            composeRule.onRoot().captureToImage().toPixelMap()[sampleX, sampleY]

        composeRule.runOnIdle {
            backStack += Route.Notification
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeBy(50)

        val railColorDuringTransition =
            composeRule.onRoot().captureToImage().toPixelMap()[sampleX, sampleY]

        assertEquals(railColorBeforeTransition.red, railColorDuringTransition.red, COLOR_TOLERANCE)
        assertEquals(railColorBeforeTransition.green, railColorDuringTransition.green, COLOR_TOLERANCE)
        assertEquals(railColorBeforeTransition.blue, railColorDuringTransition.blue, COLOR_TOLERANCE)
    }

    @Test
    fun fullscreenDialogOverlayIsNotClippedToNavigationContent() {
        val backStack = mutableStateListOf<Route>(Route.Home, Route.Notification)

        composeRule.setContent {
            MaterialTheme {
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalGlobalAppearance provides GlobalAppearance.Default,
                ) {
                    NavigationSuiteScaffold2(
                        navigationSuiteItems = {},
                        secondaryItems = {},
                        wideNavigationRailState = rememberWideNavigationRailState(),
                        modifier = Modifier.fillMaxSize(),
                        bottomBarAutoHideEnabled = false,
                        showFab = false,
                        layoutType = NavigationSuiteType.NavigationRail,
                        containerColor = NAVIGATION_RAIL_COLOR,
                    ) {
                        AndroidNavDisplay(
                            cornerShape = null,
                            onBack = { backStack.removeLastOrNull() },
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .testTag(NAV_DISPLAY_TAG),
                            sceneStrategies = listOf(DialogSceneStrategy()),
                            transitionSpec = { materialSharedAxisZ(true) },
                            popTransitionSpec = { materialSharedAxisZ(false) },
                            entries =
                                rememberDecoratedNavEntries(
                                    backStack = backStack,
                                    entryDecorators = emptyList(),
                                    entryProvider =
                                        entryProvider {
                                            entry<Route.Home> {
                                                Box(
                                                    Modifier
                                                        .fillMaxSize()
                                                        .background(Color.Red),
                                                )
                                            }
                                            entry<Route.Notification>(
                                                metadata =
                                                    DialogSceneStrategy.dialog(
                                                        DialogProperties(
                                                            usePlatformDefaultWidth = false,
                                                            decorFitsSystemWindows = false,
                                                        ),
                                                    ),
                                            ) {
                                                Box(
                                                    Modifier
                                                        .fillMaxSize()
                                                        .background(MEDIA_OVERLAY_COLOR)
                                                        .testTag(MEDIA_OVERLAY_TAG),
                                                )
                                            }
                                        },
                                ),
                        )
                    }
                }
            }
        }

        composeRule.waitForIdle()
        val navigationContentBounds =
            composeRule
                .onNodeWithTag(NAV_DISPLAY_TAG)
                .fetchSemanticsNode()
                .boundsInWindow
        val overlayBounds =
            composeRule
                .onNodeWithTag(MEDIA_OVERLAY_TAG)
                .fetchSemanticsNode()
                .boundsInWindow

        assertTrue(
            "Expected the fullscreen dialog to extend beyond the navigation content: " +
                "overlay=$overlayBounds, navigationContent=$navigationContentBounds",
            overlayBounds.left < navigationContentBounds.left,
        )
    }

    @Test
    fun floatingNavigationHidesForMediaAndRestoresWithoutLosingContent() {
        verifyBottomNavigationVisibility(BottomBarStyle.Floating)
    }

    @Test
    fun classicNavigationHidesForMediaAndRestoresWithoutLosingContent() {
        verifyBottomNavigationVisibility(BottomBarStyle.Classic)
    }

    private fun verifyBottomNavigationVisibility(style: BottomBarStyle) {
        val navigationVisible = mutableStateOf(true)
        var reportedShowing = false
        var reportedHeight = 0.dp
        var systemBottomInset = 0.dp
        var contentMounts = 0
        var navigationClicks = 0
        var composeClicks = 0
        val composeDescription =
            InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.compose_title)

        composeRule.setContent {
            MaterialTheme {
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalGlobalAppearance provides
                        GlobalAppearance.Default.copy(
                            bottomBarStyle = style,
                            bottomBarBehavior = BottomBarBehavior.AlwaysShow,
                        ),
                ) {
                    NavigationSuiteScaffold2(
                        navigationSuiteItems = {
                            item(
                                selected = true,
                                onClick = { navigationClicks++ },
                                icon = { Text("Home") },
                                modifier = Modifier.testTag(HOME_ITEM_TAG),
                            )
                        },
                        secondaryItems = {},
                        wideNavigationRailState = rememberWideNavigationRailState(),
                        modifier = Modifier.fillMaxSize(),
                        navigationVisible = navigationVisible.value,
                        bottomBarAutoHideEnabled = false,
                        showFab = true,
                        onFabClicked = { composeClicks++ },
                        layoutType = NavigationSuiteType.NavigationBar,
                    ) {
                        val showing = LocalBottomBarShowing.current
                        val height = LocalBottomBarHeight.current
                        val bottomInset = with(LocalDensity.current) { WindowInsets.systemBars.getBottom(this).toDp() }
                        SideEffect {
                            reportedShowing = showing
                            reportedHeight = height
                            systemBottomInset = bottomInset
                        }
                        DisposableEffect(Unit) {
                            contentMounts++
                            onDispose {}
                        }
                        var count by remember { mutableIntStateOf(0) }
                        Box(Modifier.fillMaxSize()) {
                            Text(
                                text = "Content $count",
                                modifier =
                                    Modifier
                                        .padding(32.dp)
                                        .testTag(CONTENT_STATE_TAG)
                                        .clickable { count++ },
                            )
                        }
                    }
                }
            }
        }

        composeRule.onNodeWithTag(HOME_ITEM_TAG).assertIsDisplayed().performClick()
        composeRule.onNodeWithContentDescription(composeDescription).assertIsDisplayed().performClick()
        composeRule.onNodeWithTag(CONTENT_STATE_TAG).performClick().assertTextEquals("Content 1")
        composeRule.runOnIdle {
            assertTrue(reportedShowing)
            assertTrue(reportedHeight > systemBottomInset)
            assertEquals(1, navigationClicks)
            assertEquals(1, composeClicks)
            navigationVisible.value = false
        }

        composeRule.onNodeWithTag(HOME_ITEM_TAG).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(composeDescription).assertDoesNotExist()
        composeRule.onNodeWithTag(CONTENT_STATE_TAG).assertTextEquals("Content 1")
        composeRule.runOnIdle {
            assertFalse(reportedShowing)
            assertEquals(systemBottomInset, reportedHeight)
            assertEquals(1, contentMounts)
            navigationVisible.value = true
        }

        composeRule.onNodeWithTag(HOME_ITEM_TAG).assertIsDisplayed().performClick()
        composeRule.onNodeWithContentDescription(composeDescription).assertIsDisplayed().performClick()
        composeRule.onNodeWithTag(CONTENT_STATE_TAG).assertTextEquals("Content 1")
        composeRule.runOnIdle {
            assertTrue(reportedShowing)
            assertTrue(reportedHeight > systemBottomInset)
            assertEquals(1, contentMounts)
            assertEquals(2, navigationClicks)
            assertEquals(2, composeClicks)
        }
    }

    @Test
    fun hiddenRailReclaimsItsWidthAndRestoresWithoutRemountingContent() {
        val navigationVisible = mutableStateOf(true)
        var contentMounts = 0
        composeRule.setContent {
            MaterialTheme {
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalGlobalAppearance provides GlobalAppearance.Default,
                ) {
                    NavigationSuiteScaffold2(
                        navigationSuiteItems = {
                            item(
                                selected = true,
                                onClick = {},
                                icon = { Text("Home") },
                                modifier = Modifier.testTag(HOME_ITEM_TAG),
                            )
                        },
                        secondaryItems = {},
                        wideNavigationRailState = rememberWideNavigationRailState(),
                        modifier = Modifier.fillMaxSize().testTag(SCAFFOLD_TAG),
                        navigationVisible = navigationVisible.value,
                        bottomBarAutoHideEnabled = false,
                        showFab = false,
                        layoutType = NavigationSuiteType.NavigationRail,
                    ) {
                        DisposableEffect(Unit) {
                            contentMounts++
                            onDispose {}
                        }
                        Box(Modifier.fillMaxSize().testTag(NAV_DISPLAY_TAG))
                    }
                }
            }
        }

        composeRule.onNodeWithTag(HOME_ITEM_TAG).assertIsDisplayed()
        val before = composeRule.onNodeWithTag(NAV_DISPLAY_TAG).fetchSemanticsNode().boundsInRoot
        val scaffold = composeRule.onNodeWithTag(SCAFFOLD_TAG).fetchSemanticsNode().boundsInRoot
        assertTrue(before.left > scaffold.left)
        composeRule.runOnIdle { navigationVisible.value = false }

        composeRule.onNodeWithTag(HOME_ITEM_TAG).assertDoesNotExist()
        val hidden = composeRule.onNodeWithTag(NAV_DISPLAY_TAG).fetchSemanticsNode().boundsInRoot
        assertEquals(scaffold.left, hidden.left, 1f)
        assertEquals(scaffold.width, hidden.width, 1f)
        composeRule.runOnIdle {
            assertEquals(1, contentMounts)
            navigationVisible.value = true
        }

        composeRule.onNodeWithTag(HOME_ITEM_TAG).assertIsDisplayed()
        val restored = composeRule.onNodeWithTag(NAV_DISPLAY_TAG).fetchSemanticsNode().boundsInRoot
        assertEquals(before.left, restored.left, 1f)
        assertEquals(before.width, restored.width, 1f)
        composeRule.runOnIdle { assertEquals(1, contentMounts) }
    }

    private companion object {
        const val HOME_ITEM_TAG = "home-item"
        const val CONTENT_STATE_TAG = "content-state"
        const val SCAFFOLD_TAG = "scaffold"
        const val NAV_DISPLAY_TAG = "nav-display"
        const val MEDIA_OVERLAY_TAG = "media-overlay"
        val NAVIGATION_RAIL_COLOR = Color.Green
        val MEDIA_OVERLAY_COLOR = Color.Magenta
        const val COLOR_TOLERANCE = 0.01f
    }
}
