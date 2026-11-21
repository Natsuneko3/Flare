package dev.dimension.flare.ui.component.status

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.dimension.flare.data.model.TimelineDisplayMode
import dev.dimension.flare.data.model.VideoAutoplay
import dev.dimension.flare.data.model.appearance.TimelineAppearance
import dev.dimension.flare.ui.component.LocalNetworkImageAllowHardware
import dev.dimension.flare.ui.component.LocalTimelineAppearance
import dev.dimension.flare.ui.component.platform.PlatformTextStyle
import dev.dimension.flare.ui.humanizer.PlatformFormatter
import dev.dimension.flare.ui.model.UiCommunityNote
import dev.dimension.flare.ui.model.UiPluginWidgetCard
import dev.dimension.flare.ui.model.UiTranslatableText
import dev.dimension.flare.ui.model.UiWidgetAction
import dev.dimension.flare.ui.model.UiWidgetAlign
import dev.dimension.flare.ui.model.UiWidgetBadge
import dev.dimension.flare.ui.model.UiWidgetButton
import dev.dimension.flare.ui.model.UiWidgetButtonStyle
import dev.dimension.flare.ui.model.UiWidgetCornerStyle
import dev.dimension.flare.ui.model.UiWidgetGap
import dev.dimension.flare.ui.model.UiWidgetNode
import dev.dimension.flare.ui.model.UiWidgetSparklineHeight
import dev.dimension.flare.ui.model.UiWidgetTextStyle
import dev.dimension.flare.ui.model.UiWidgetTone
import dev.dimension.flare.ui.model.createSampleStatus
import dev.dimension.flare.ui.model.createSampleUser
import dev.dimension.flare.ui.render.toUiPlainText
import dev.dimension.flare.ui.theme.PlatformTheme
import io.github.composefluent.FluentTheme
import kotlinx.collections.immutable.persistentListOf
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class PluginWidgetStackComponentTest {
    @Test
    fun multiCardPagerEqualizesHeightToTallestCardAndRendersFlatSparklineSafely() =
        runComposeUiTest {
            val tallCard =
                UiPluginWidgetCard(
                    pluginId = "p1",
                    pluginName = "Scores",
                    pluginIconPath = null,
                    id = "c1",
                    title = "NBA Live",
                    body =
                        persistentListOf(
                            UiWidgetNode.Text("Line 1"),
                            UiWidgetNode.Text("Line 2"),
                            UiWidgetNode.Text("Line 3"),
                        ),
                )
            val shortCard =
                UiPluginWidgetCard(
                    pluginId = "p2",
                    pluginName = "Stocks",
                    pluginIconPath = null,
                    id = "c2",
                    title = "AAPL",
                    body = persistentListOf(UiWidgetNode.Sparkline(persistentListOf(100.0, 100.0, 100.0))),
                )
            setContent {
                PluginWidgetStackComponent(cards = persistentListOf(tallCard, shortCard))
            }
            onNodeWithText("NBA Live").assertIsDisplayed()
            val heightBefore = onNodeWithTag("plugin_widget_stack").fetchSemanticsNode().boundsInRoot.height
            onNodeWithTag("plugin_widget_pager").performTouchInput { swipeLeft() }
            waitForIdle()
            onNodeWithText("AAPL").assertIsDisplayed()
            val heightAfter = onNodeWithTag("plugin_widget_stack").fetchSemanticsNode().boundsInRoot.height
            assertTrue(abs(heightBefore - heightAfter) <= 1f)
        }

    @Test
    fun shortFirstCardPreEqualizesToTallestLaterCardBeforeSwiping() =
        runComposeUiTest {
            val shortCard =
                UiPluginWidgetCard(
                    pluginId = "p1",
                    pluginName = "Stocks",
                    pluginIconPath = null,
                    id = "short",
                    title = "Short First",
                    body = persistentListOf(UiWidgetNode.Text("One line")),
                )
            val tallCard =
                UiPluginWidgetCard(
                    pluginId = "p2",
                    pluginName = "Scores",
                    pluginIconPath = null,
                    id = "tall",
                    title = "Tall Second",
                    body =
                        persistentListOf(
                            UiWidgetNode.Text("Row 1"),
                            UiWidgetNode.Text("Row 2"),
                            UiWidgetNode.Text("Row 3"),
                            UiWidgetNode.Text("Row 4"),
                        ),
                )
            setContent {
                Box(Modifier.width(360.dp)) {
                    PluginWidgetStackComponent(cards = persistentListOf(shortCard, tallCard))
                }
            }
            onNodeWithText("Short First").assertIsDisplayed()
            val initialHeight = onNodeWithTag("plugin_widget_stack").fetchSemanticsNode().boundsInRoot.height
            onNodeWithTag("plugin_widget_pager").performTouchInput { swipeLeft() }
            waitForIdle()
            onNodeWithText("Tall Second").assertIsDisplayed()
            val swipedHeight = onNodeWithTag("plugin_widget_stack").fetchSemanticsNode().boundsInRoot.height
            assertTrue(abs(initialHeight - swipedHeight) <= 1f)
        }

    @Test
    fun singleCardRendersDirectlyWithoutPagerOrIndicatorDots() =
        runComposeUiTest {
            val singleCard =
                UiPluginWidgetCard(
                    pluginId = "p1",
                    pluginName = "Decryptor",
                    pluginIconPath = null,
                    id = "gpg-1",
                    title = "Decrypted PGP",
                    badge = UiWidgetBadge("VERIFIED", UiWidgetTone.Positive),
                    body =
                        persistentListOf(
                            UiWidgetNode.Text(
                                text = "Secret payload",
                                style = UiWidgetTextStyle.Code,
                                copyable = true,
                            ),
                        ),
                )
            setContent {
                Box(Modifier.width(360.dp)) {
                    PluginWidgetStackComponent(cards = persistentListOf(singleCard))
                }
            }
            onNodeWithTag("plugin_widget_stack").assertIsDisplayed()
            onNodeWithText("Decrypted PGP").assertIsDisplayed()
            onNodeWithText("VERIFIED").assertIsDisplayed()
            onNodeWithText("Secret payload").assertIsDisplayed()
            onNodeWithTag("plugin_widget_pager").assertDoesNotExist()
            onNodeWithTag("plugin_widget_dots").assertDoesNotExist()
        }

    @Test
    fun nestedMultiCardSwipeDoesNotAdvanceParentTabPager() =
        runComposeUiTest {
            val card1 =
                UiPluginWidgetCard(
                    pluginId = "p1",
                    pluginName = "Market",
                    pluginIconPath = null,
                    id = "c1",
                    title = "First Card",
                    body = persistentListOf(UiWidgetNode.Text("First content")),
                )
            val card2 =
                UiPluginWidgetCard(
                    pluginId = "p1",
                    pluginName = "Market",
                    pluginIconPath = null,
                    id = "c2",
                    title = "Second Card",
                    body = persistentListOf(UiWidgetNode.Text("Second content")),
                )
            var outerPage = 0
            setContent {
                val parentPagerState = rememberPagerState(pageCount = { 2 })
                outerPage = parentPagerState.currentPage
                Box(Modifier.width(380.dp)) {
                    HorizontalPager(state = parentPagerState, modifier = Modifier.fillMaxWidth()) { page ->
                        if (page == 0) {
                            PluginWidgetStackComponent(cards = persistentListOf(card1, card2))
                        } else {
                            Box(Modifier.fillMaxWidth())
                        }
                    }
                }
            }
            onNodeWithText("First Card").assertIsDisplayed()
            onNodeWithTag("plugin_widget_pager").performTouchInput { swipeLeft() }
            waitForIdle()
            onNodeWithText("Second Card").assertIsDisplayed()
            assertEquals(0, outerPage)
        }

    @Test
    fun rendersAllNodeSubtypesAndRoutesCardAndNestedActionsIndependently() =
        runComposeUiTest {
            val openedUrls = mutableListOf<String>()
            var copiedEntry: ClipEntry? = null
            var refreshCount = 0
            var expanded by mutableStateOf(false)
            var refreshing by mutableStateOf(false)
            val iconFile =
                Files.createTempFile("plugin-widget-icon-", ".png").toFile().apply {
                    deleteOnExit()
                    val img = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
                    ImageIO.write(img, "png", this)
                }

            val clipboard =
                object : Clipboard {
                    override suspend fun getClipEntry(): ClipEntry? = copiedEntry

                    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
                        copiedEntry = clipEntry
                    }
                }
            val uriHandler =
                object : UriHandler {
                    override fun openUri(uri: String) {
                        openedUrls += uri
                    }
                }

            setContent {
                val card =
                    UiPluginWidgetCard(
                        pluginId = "org.example.all",
                        pluginName = "Omni Widget",
                        pluginIconPath = iconFile.absolutePath,
                        id = "omni-1",
                        title = "Omni Card",
                        badge = UiWidgetBadge("LIVE", UiWidgetTone.Accent),
                        accentTone = UiWidgetTone.Accent,
                        refreshable = true,
                        isRefreshing = refreshing,
                        cardAction = UiWidgetAction.OpenUrl("https://example.com/card"),
                        onRefresh = { refreshCount++ },
                        body =
                            persistentListOf(
                                UiWidgetNode.Row(
                                    gap = UiWidgetGap.Md,
                                    align = UiWidgetAlign.Start,
                                    children =
                                        persistentListOf(
                                            UiWidgetNode.Stat(
                                                label = "BTC",
                                                value = "$98,500",
                                                delta = "+4.2%",
                                                deltaTone = UiWidgetTone.Positive,
                                                subtext = "24h Spot",
                                                weight = 2f,
                                            ),
                                            UiWidgetNode.Sparkline(
                                                points = persistentListOf(94.0, 96.5, 98.5),
                                                tone = UiWidgetTone.Positive,
                                                minLabel = "94k",
                                                maxLabel = "99k",
                                                height = UiWidgetSparklineHeight.Md,
                                                weight = 1f,
                                            ),
                                        ),
                                ),
                                UiWidgetNode.Divider,
                                UiWidgetNode.Matchup(
                                    left = UiWidgetNode.MatchupSide(name = "LAL", subtitle = "Lakers", score = "108"),
                                    right = UiWidgetNode.MatchupSide(name = "GSW", subtitle = "Warriors", score = "105"),
                                    status = "Q4 02:14",
                                    statusTone = UiWidgetTone.Warning,
                                ),
                                UiWidgetNode.SegmentedBar(
                                    segments =
                                        persistentListOf(
                                            UiWidgetNode.SegmentedBar.Segment("Yes", "68%", 68.0, UiWidgetTone.Positive),
                                            UiWidgetNode.SegmentedBar.Segment("No", "32%", 32.0, UiWidgetTone.Negative),
                                        ),
                                ),
                                UiWidgetNode.KeyValue(
                                    items =
                                        persistentListOf(
                                            UiWidgetNode.KeyValue.Item(
                                                label = "Contract",
                                                value = "0xabc123",
                                                action = UiWidgetAction.Copy("0xabc123", "Contract"),
                                            ),
                                        ),
                                ),
                                UiWidgetNode.BadgeRow(
                                    badges =
                                        persistentListOf(
                                            UiWidgetBadge("Trending", UiWidgetTone.Warning),
                                            UiWidgetBadge("Verified", UiWidgetTone.Positive),
                                        ),
                                ),
                                UiWidgetNode.Column(
                                    gap = UiWidgetGap.Xs,
                                    children =
                                        persistentListOf(
                                            UiWidgetNode.Text(
                                                text = "-----BEGIN DECODED-----",
                                                style = UiWidgetTextStyle.Code,
                                                copyable = true,
                                            ),
                                            UiWidgetNode.Image(
                                                url = "https://example.com/chart.png",
                                                alt = "Market chart",
                                                aspectRatio = 2f,
                                                cornerStyle = UiWidgetCornerStyle.Rounded,
                                            ),
                                        ),
                                ),
                                UiWidgetNode.Collapsible(
                                    id = "details-1",
                                    summary = "More details",
                                    expanded = expanded,
                                    onToggle = { expanded = !expanded },
                                    children =
                                        persistentListOf(
                                            UiWidgetNode.Text("Hidden breakdown line", style = UiWidgetTextStyle.Caption),
                                        ),
                                ),
                            ),
                        actions =
                            persistentListOf(
                                UiWidgetButton(
                                    label = "Open Market",
                                    tone = UiWidgetTone.Accent,
                                    style = UiWidgetButtonStyle.Primary,
                                    action = UiWidgetAction.OpenUrl("https://example.com/market"),
                                ),
                                UiWidgetButton(
                                    label = "Copy Ticker",
                                    action = UiWidgetAction.Copy("BTC-USD"),
                                ),
                                UiWidgetButton(
                                    label = "Reload",
                                    action = UiWidgetAction.Refresh,
                                ),
                            ),
                    )
                CompositionLocalProvider(
                    LocalUriHandler provides uriHandler,
                    LocalClipboard provides clipboard,
                    LocalNetworkImageAllowHardware provides false,
                ) {
                    Box(Modifier.width(400.dp)) {
                        PluginWidgetStackComponent(cards = persistentListOf(card))
                    }
                }
            }

            onNodeWithText("Omni Card").assertIsDisplayed()
            onNodeWithText("$98,500").assertIsDisplayed()
            onNodeWithText("+4.2%").assertIsDisplayed()
            onNodeWithText("LAL").assertIsDisplayed()
            onNodeWithText("GSW").assertIsDisplayed()
            onNodeWithText("Q4 02:14").assertIsDisplayed()
            onNodeWithText("Yes · 68%").assertIsDisplayed()
            onNodeWithText("Trending").assertIsDisplayed()
            onNodeWithContentDescription("Market chart").assertIsDisplayed()
            onNodeWithText("Hidden breakdown line").assertDoesNotExist()

            // Header refresh button invokes onRefresh without triggering whole-card OpenUrl
            onNodeWithTag("plugin_widget_refresh_button").performClick()
            waitForIdle()
            assertEquals(1, refreshCount)
            assertTrue(openedUrls.isEmpty())

            // Collapsible toggles without triggering whole-card OpenUrl
            onNodeWithText("More details").performClick()
            waitForIdle()
            onNodeWithText("Hidden breakdown line").assertIsDisplayed()
            assertTrue(openedUrls.isEmpty())

            // Copyable Code block copies text without triggering whole-card OpenUrl
            onNodeWithText("-----BEGIN DECODED-----").performClick()
            waitForIdle()
            assertEquals(
                "-----BEGIN DECODED-----",
                (copiedEntry?.nativeClipEntry as? Transferable)?.getTransferData(DataFlavor.stringFlavor),
            )
            assertTrue(openedUrls.isEmpty())

            // KeyValue row action copies text
            onNodeWithText("0xabc123").performClick()
            waitForIdle()
            assertEquals(
                "0xabc123",
                (copiedEntry?.nativeClipEntry as? Transferable)?.getTransferData(DataFlavor.stringFlavor),
            )

            // Footer buttons route OpenUrl, Copy, and Refresh
            onNodeWithText("Open Market").performClick()
            onNodeWithText("Copy Ticker").performClick()
            onNodeWithText("Reload").performClick()
            waitForIdle()
            assertEquals(listOf("https://example.com/market"), openedUrls)
            assertEquals(
                "BTC-USD",
                (copiedEntry?.nativeClipEntry as? Transferable)?.getTransferData(DataFlavor.stringFlavor),
            )
            assertEquals(2, refreshCount)

            // Tapping non-interactive card title invokes whole-card cardAction
            onNodeWithText("Omni Card").performClick()
            waitForIdle()
            assertEquals(listOf("https://example.com/market", "https://example.com/card"), openedUrls)

            // Switching to refreshing state replaces button with spinner
            refreshing = true
            waitForIdle()
            onNodeWithTag("plugin_widget_refresh_spinner", useUnmergedTree = true).assertIsDisplayed()
            onNodeWithTag("plugin_widget_refresh_button", useUnmergedTree = true).assertDoesNotExist()
        }

    @Test
    fun commonStatusComponentPlacesWidgetStackAfterCommunityNoteAndExcludesQuotesAndGalleryMode() =
        runComposeUiTest {
            startKoin {
                modules(
                    module {
                        single<PlatformFormatter> {
                            object : PlatformFormatter {
                                override fun formatNumber(number: Long) = number.toString()

                                override fun formatRelativeInstant(instant: Instant) = "5m"

                                override fun formatFullInstant(instant: Instant) = "5m"

                                override fun formatAbsoluteInstant(instant: Instant) = "5m"
                            }
                        }
                    },
                )
            }
            try {
                val widgetCard =
                    UiPluginWidgetCard(
                        pluginId = "p1",
                        pluginName = "Scores",
                        pluginIconPath = null,
                        id = "c1",
                        title = "Widget Card Title",
                        body = persistentListOf(UiWidgetNode.Text("Widget body")),
                    )
                val post =
                    createSampleStatus(createSampleUser().copy(nameInternal = "Author".toUiPlainText(), avatar = null)).copy(
                        content = UiTranslatableText("Post body".toUiPlainText()),
                        communityNote =
                            UiCommunityNote(
                                title = "Community Note Header",
                                body = "Context body".toUiPlainText(),
                                url = null,
                            ),
                        pluginWidgets = persistentListOf(widgetCard),
                        actions = persistentListOf(),
                    )
                var isQuote by mutableStateOf(false)
                var displayMode by mutableStateOf(TimelineDisplayMode.Plain)

                setContent {
                    FluentTheme {
                        CompositionLocalProvider(
                            LocalTimelineAppearance provides
                                TimelineAppearance(
                                    showPlatformLogo = false,
                                    videoAutoplay = VideoAutoplay.NEVER,
                                ),
                            LocalEffectiveTimelineDisplayMode provides displayMode,
                            PlatformTextStyle provides PlatformTheme.typography.body,
                            LocalDensity provides Density(1f),
                        ) {
                            Box(Modifier.width(380.dp)) {
                                CommonStatusComponent(
                                    item = post,
                                    isQuote = isQuote,
                                    isClickable = false,
                                )
                            }
                        }
                    }
                }

                val noteBottom = onNodeWithText("Context body", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.bottom
                val stackTop = onNodeWithTag("plugin_widget_stack").fetchSemanticsNode().boundsInRoot.top
                assertTrue(stackTop > noteBottom, "Expected plugin widget stack ($stackTop) below community note ($noteBottom)")

                // Quoted posts must exclude plugin widgets
                isQuote = true
                waitForIdle()
                onNodeWithTag("plugin_widget_stack").assertDoesNotExist()

                // Gallery mode must exclude plugin widgets
                isQuote = false
                displayMode = TimelineDisplayMode.Gallery
                waitForIdle()
                onNodeWithTag("plugin_widget_stack").assertDoesNotExist()
            } finally {
                stopKoin()
            }
        }
}
