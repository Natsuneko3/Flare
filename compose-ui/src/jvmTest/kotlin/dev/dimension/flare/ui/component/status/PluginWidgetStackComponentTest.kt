package dev.dimension.flare.ui.component.status

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.dimension.flare.data.model.TimelineDisplayMode
import dev.dimension.flare.data.model.VideoAutoplay
import dev.dimension.flare.data.model.appearance.TimelineAppearance
import dev.dimension.flare.model.MicroBlogKey
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
import io.github.composefluent.darkColors
import io.github.composefluent.lightColors
import kotlinx.collections.immutable.persistentListOf
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class PluginWidgetStackComponentTest {
    @Test
    fun unlabeledSparklineAnnouncesItsDirectionAndFractionalRangeInChinese() {
        val previousLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
            runComposeUiTest {
                val card =
                    UiPluginWidgetCard(
                        pluginId = "market",
                        pluginName = "Market",
                        pluginIconPath = null,
                        id = "price",
                        title = "Price",
                        body = persistentListOf(UiWidgetNode.Sparkline(persistentListOf(2.5, 1.75, 1.25))),
                        cardAction = UiWidgetAction.OpenUrl("https://example.com/market"),
                    )
                setContent { PluginWidgetStackComponent(persistentListOf(card)) }
                val chart = onNodeWithContentDescription("下降趋势").fetchSemanticsNode()
                assertEquals("最低 1.25，最高 2.5", chart.config[SemanticsProperties.StateDescription])
            }
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

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
            assertEquals(
                "Low 100, high 100",
                onNodeWithContentDescription("Flat trend").fetchSemanticsNode().config[SemanticsProperties.StateDescription],
            )
            val heightAfter = onNodeWithTag("plugin_widget_stack").fetchSemanticsNode().boundsInRoot.height
            assertTrue(abs(heightBefore - heightAfter) <= 1f)
        }

    @Test
    fun shortFirstCardHasTallestHeightBeforeMouseAndKeyboardPageSelection() =
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
            val second = onNodeWithContentDescription("Page 2 of 2")
            second.performClick()
            waitForIdle()
            onNodeWithText("Tall Second").assertIsDisplayed()
            second.assertIsSelected()
            val swipedHeight = onNodeWithTag("plugin_widget_stack").fetchSemanticsNode().boundsInRoot.height
            assertTrue(abs(initialHeight - swipedHeight) <= 1f)
            second.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            second.performKeyInput {
                keyDown(Key.ShiftLeft)
                pressKey(Key.Tab)
                keyUp(Key.ShiftLeft)
            }
            val first = onNodeWithContentDescription("Page 1 of 2")
            first.assertIsFocused().performKeyInput { pressKey(Key.Enter) }
            onNodeWithText("Short First").assertIsDisplayed()
            first.assertIsSelected()
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
                    LocalDensity provides Density(1f, fontScale = 1.15f),
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
            assertEquals(
                "Low 94k, high 99k",
                onNodeWithContentDescription("Rising trend")
                    .fetchSemanticsNode()
                    .config[SemanticsProperties.StateDescription],
            )
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
            onNodeWithText("Omni Card", useUnmergedTree = true).performClick()
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

    @Test
    fun postWidgetsFollowWarningRevealAcrossRefreshAndReuse() =
        runComposeUiTest {
            val card =
                UiPluginWidgetCard(
                    pluginId = "p1",
                    pluginName = "Scores",
                    pluginIconPath = null,
                    id = "score",
                    title = "Final score",
                    body = persistentListOf(UiWidgetNode.Text("2–1")),
                )
            var post by mutableStateOf(
                createSampleStatus(createSampleUser()).copy(
                    user = null,
                    content = UiTranslatableText("Match details".toUiPlainText()),
                    contentWarning = UiTranslatableText("Spoiler".toUiPlainText()),
                    actions = persistentListOf(),
                ),
            )
            var autoExpand by mutableStateOf(false)
            var mounted by mutableStateOf(true)

            setContent {
                FluentTheme {
                    CompositionLocalProvider(LocalTimelineAppearance provides TimelineAppearance(expandContentWarning = autoExpand)) {
                        val history = rememberSaveableStateHolder()
                        if (mounted) {
                            history.SaveableStateProvider("post") {
                                CommonStatusComponent(post, isClickable = false, forceHideActions = true)
                            }
                        }
                    }
                }
            }

            val stack = onNodeWithTag("plugin_widget_stack")
            post = post.copy(pluginWidgets = persistentListOf(card))
            stack.assertDoesNotExist()
            onNodeWithText("Show more").performClick()
            onNodeWithText("Final score").assertIsDisplayed()

            post = post.copy(pluginWidgets = persistentListOf(card.copy(title = "Updated score")))
            onNodeWithText("Updated score").assertIsDisplayed()
            onNodeWithText("Show less").performClick()
            stack.assertDoesNotExist()

            onNodeWithText("Show more").performClick()
            post = post.copy(statusKey = MicroBlogKey("other-post", "example.com"))
            stack.assertDoesNotExist()
            autoExpand = true
            stack.assertIsDisplayed()
            autoExpand = false
            stack.assertDoesNotExist()

            onNodeWithText("Show more").performClick()
            stack.assertIsDisplayed()
            mounted = false
            stack.assertDoesNotExist()
            post = post.copy(contentWarning = UiTranslatableText("New warning".toUiPlainText()))
            mounted = true
            stack.assertDoesNotExist()
        }

    @Test
    fun headerTitleUsesFullWidthUnweightedRowSiblingsCoexistKeyValuePreservesLabelAndPrimaryButtonsContrast() =
        runComposeUiTest {
            val longHeaderTitle = "Long Card Header Title That Should Expand Well Beyond Half Of The Card Width"
            val longDigestValue = "0x" + "a1b2c3d4".repeat(25) // 202 chars
            var darkTheme by mutableStateOf(false)
            val buttonBgByLabel = mutableMapOf<String, Color>()

            val card =
                UiPluginWidgetCard(
                    pluginId = "org.example.layout",
                    pluginName = "Layout Check",
                    pluginIconPath = null,
                    id = "layout-1",
                    title = longHeaderTitle,
                    refreshable = false,
                    body =
                        persistentListOf(
                            UiWidgetNode.Row(
                                align = UiWidgetAlign.SpaceBetween,
                                gap = UiWidgetGap.Sm,
                                children =
                                    persistentListOf(
                                        UiWidgetNode.Column(
                                            gap = UiWidgetGap.Xs,
                                            children =
                                                persistentListOf(
                                                    UiWidgetNode.Text("Left Col"),
                                                ),
                                        ),
                                        UiWidgetNode.BadgeRow(
                                            badges = persistentListOf(UiWidgetBadge("MID", UiWidgetTone.Accent)),
                                        ),
                                        UiWidgetNode.Text(
                                            text = "CODE_TAG",
                                            style = UiWidgetTextStyle.Code,
                                        ),
                                    ),
                            ),
                            UiWidgetNode.KeyValue(
                                items =
                                    persistentListOf(
                                        UiWidgetNode.KeyValue.Item(
                                            label = "Tx Hash",
                                            value = longDigestValue,
                                            action = UiWidgetAction.Copy(longDigestValue, "Tx Hash"),
                                        ),
                                    ),
                            ),
                        ),
                    actions =
                        persistentListOf(
                            UiWidgetButton("Primary Neutral", UiWidgetTone.Neutral, UiWidgetButtonStyle.Primary, UiWidgetAction.Refresh),
                            UiWidgetButton("Primary Accent", UiWidgetTone.Accent, UiWidgetButtonStyle.Primary, UiWidgetAction.Refresh),
                            UiWidgetButton("Primary Positive", UiWidgetTone.Positive, UiWidgetButtonStyle.Primary, UiWidgetAction.Refresh),
                            UiWidgetButton("Primary Negative", UiWidgetTone.Negative, UiWidgetButtonStyle.Primary, UiWidgetAction.Refresh),
                            UiWidgetButton("Primary Warning", UiWidgetTone.Warning, UiWidgetButtonStyle.Primary, UiWidgetAction.Refresh),
                        ),
                )

            setContent {
                FluentTheme(colors = if (darkTheme) darkColors() else lightColors()) {
                    buttonBgByLabel["Primary Neutral"] = PlatformTheme.colorScheme.primary
                    buttonBgByLabel["Primary Accent"] = PlatformTheme.colorScheme.primary
                    buttonBgByLabel["Primary Positive"] = widgetToneColor(UiWidgetTone.Positive)
                    buttonBgByLabel["Primary Negative"] = PlatformTheme.colorScheme.error
                    buttonBgByLabel["Primary Warning"] = widgetToneColor(UiWidgetTone.Warning)
                    CompositionLocalProvider(LocalDensity provides Density(1f)) {
                        Box(Modifier.width(400.dp)) {
                            PluginWidgetStackComponent(cards = persistentListOf(card))
                        }
                    }
                }
            }

            val stackBounds = onNodeWithTag("plugin_widget_stack").fetchSemanticsNode().boundsInRoot

            // (b) Long card header title uses >50% of card width when no refresh button or badge blocks it
            val titleBounds = onNodeWithText(longHeaderTitle, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue(
                titleBounds.width > stackBounds.width * 0.5f,
                "Expected header title width (${titleBounds.width}) > 50% of card width (${stackBounds.width})",
            )

            // (c) Unweighted Column / BadgeRow / Code Text inside Row(align = SpaceBetween) all receive positive width side-by-side
            val leftColBounds = onNodeWithText("Left Col", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val midBadgeBounds = onNodeWithText("MID", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val rightCodeBounds = onNodeWithText("CODE_TAG", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue(leftColBounds.width > 0f, "Expected Left Col positive width, got ${leftColBounds.width}")
            assertTrue(midBadgeBounds.width > 0f, "Expected MID badge positive width, got ${midBadgeBounds.width}")
            assertTrue(rightCodeBounds.width > 0f, "Expected CODE_TAG positive width, got ${rightCodeBounds.width}")
            assertTrue(
                leftColBounds.right <= midBadgeBounds.left,
                "Expected Left Col (${leftColBounds.right}) left of MID (${midBadgeBounds.left})",
            )
            assertTrue(
                midBadgeBounds.right <= rightCodeBounds.left,
                "Expected MID (${midBadgeBounds.right}) left of CODE_TAG (${rightCodeBounds.left})",
            )

            // (d) KeyValue with 200+ char value preserves positive width on both item.label and trailing Copy icon
            val labelBounds = onNodeWithText("Tx Hash", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val valueBounds = onNodeWithText(longDigestValue, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val copyIconBounds =
                onNodeWithTag("plugin_widget_kv_copy_icon", useUnmergedTree = true)
                    .fetchSemanticsNode()
                    .boundsInRoot
            assertTrue(labelBounds.width > 20f, "Expected Tx Hash label positive width, got ${labelBounds.width}")
            assertTrue(valueBounds.left >= labelBounds.right, "Expected value (${valueBounds.left}) after label (${labelBounds.right})")
            assertTrue(copyIconBounds.width >= 9f, "Expected Copy icon positive width, got ${copyIconBounds.width}")
            assertTrue(
                copyIconBounds.right <= stackBounds.right,
                "Expected Copy icon (${copyIconBounds.right}) inside card right edge (${stackBounds.right})",
            )

            // (a) Primary action button foreground/background luminance contrast across all tones in light & dark themes + M3 palettes
            for (isDark in listOf(false, true)) {
                darkTheme = isDark
                waitForIdle()
                for ((buttonLabel, bgColor) in buttonBgByLabel) {
                    val layouts = mutableListOf<TextLayoutResult>()
                    onNodeWithText(buttonLabel, useUnmergedTree = true)
                        .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                    val layoutResult = layouts.single()
                    val textColor = layoutResult.layoutInput.style.color
                    val ratio = wcagContrastRatio(bgColor, textColor)
                    assertTrue(
                        ratio >= 4.5f,
                        "Expected WCAG AA contrast >= 4.5 for $buttonLabel (dark=$isDark), got $ratio (bg=$bgColor, fg=$textColor)",
                    )
                }
            }
            // Verify Material 3 light & dark primary/error pairs also maintain >= 4.5 contrast
            val m3Pairs =
                listOf(
                    Color(0xFF6750A4) to Color.White, // M3 light primary / onPrimary
                    Color(0xFFD0BCFF) to Color(0xFF381E72), // M3 dark primary / onPrimary
                    Color(0xFFF2B8B5) to Color(0xFF601410), // M3 dark error / onError
                )
            for ((bg, onBg) in m3Pairs) {
                val fg = contrastingForegroundFor(bg, onBg)
                assertTrue(wcagContrastRatio(bg, fg) >= 4.5f)
            }
        }

    @Test
    fun stackHeightCacheInvalidatesOnFontScaleChangePostIdentityResetsPagerStatPreservesDeltaAndOpenUrlBlocksUserInfo() =
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
                val openedUrls = mutableListOf<String>()
                val uriHandler =
                    object : UriHandler {
                        override fun openUri(uri: String) {
                            openedUrls += uri
                        }
                    }

                val longStatValue = "$123,456,789,012,345,678.90 USD PER CONTRACT"
                val cardOne =
                    UiPluginWidgetCard(
                        pluginId = "p1",
                        pluginName = "Market",
                        pluginIconPath = null,
                        id = "c1",
                        title = "Page One",
                        body =
                            persistentListOf(
                                UiWidgetNode.Stat(
                                    label = "Notional",
                                    value = longStatValue,
                                    delta = "+12.4%",
                                    deltaTone = UiWidgetTone.Positive,
                                ),
                            ),
                        actions =
                            persistentListOf(
                                UiWidgetButton(
                                    label = "Cred Link",
                                    action = UiWidgetAction.OpenUrl("https://user:secret@evil.example/steal"),
                                ),
                                UiWidgetButton(
                                    label = "Empty User Link",
                                    action = UiWidgetAction.OpenUrl("https://@evil.example/steal"),
                                ),
                            ),
                    )
                val cardTwo =
                    UiPluginWidgetCard(
                        pluginId = "p1",
                        pluginName = "Market",
                        pluginIconPath = null,
                        id = "c2",
                        title = "Page Two",
                        body =
                            persistentListOf(
                                UiWidgetNode.Text("Multi-line detail A"),
                                UiWidgetNode.Text("Multi-line detail B"),
                                UiWidgetNode.Text("Multi-line detail C"),
                            ),
                    )

                val user = createSampleUser().copy(nameInternal = "Author".toUiPlainText(), avatar = null)
                val postA =
                    createSampleStatus(user).copy(
                        statusKey = MicroBlogKey("post-a", "example.com"),
                        content = UiTranslatableText("Post A".toUiPlainText()),
                        pluginWidgets = persistentListOf(cardOne, cardTwo),
                        actions = persistentListOf(),
                    )
                val postB =
                    postA.copy(
                        statusKey = MicroBlogKey("post-b", "example.com"),
                        content = UiTranslatableText("Post B".toUiPlainText()),
                    )

                var activePost by mutableStateOf(postA)
                var fontScale by mutableStateOf(1f)

                setContent {
                    FluentTheme {
                        CompositionLocalProvider(
                            LocalTimelineAppearance provides
                                TimelineAppearance(
                                    showPlatformLogo = false,
                                    videoAutoplay = VideoAutoplay.NEVER,
                                ),
                            LocalEffectiveTimelineDisplayMode provides TimelineDisplayMode.Plain,
                            PlatformTextStyle provides PlatformTheme.typography.body,
                            LocalDensity provides Density(density = 1f, fontScale = fontScale),
                            LocalUriHandler provides uriHandler,
                        ) {
                            Box(Modifier.width(340.dp)) {
                                CommonStatusComponent(
                                    item = activePost,
                                    isQuote = false,
                                    isClickable = false,
                                )
                            }
                        }
                    }
                }

                onNodeWithText("Page One").assertIsDisplayed()
                val stackBounds = onNodeWithTag("plugin_widget_stack").fetchSemanticsNode().boundsInRoot
                val heightAtScale1 = stackBounds.height

                // Long Stat value must not clip delta off-card
                val statValueBounds = onNodeWithText(longStatValue).fetchSemanticsNode().boundsInRoot
                val deltaBounds = onNodeWithText("+12.4%").fetchSemanticsNode().boundsInRoot
                assertTrue(statValueBounds.width > 0f)
                assertTrue(deltaBounds.width > 20f, "Expected Stat delta positive width, got ${deltaBounds.width}")
                assertTrue(
                    deltaBounds.right <= stackBounds.right,
                    "Expected delta (${deltaBounds.right}) inside card (${stackBounds.right})",
                )

                // OpenUrl with userinfo is blocked
                onNodeWithText("Cred Link").performClick()
                onNodeWithText("Empty User Link").performClick()
                waitForIdle()
                assertTrue(openedUrls.isEmpty(), "Expected userinfo URLs to be blocked, got $openedUrls")

                // Increasing fontScale invalidates StackHeightCache and grows stack height
                fontScale = 1.6f
                waitForIdle()
                val heightAtScale16 = onNodeWithTag("plugin_widget_stack").fetchSemanticsNode().boundsInRoot.height
                assertTrue(
                    heightAtScale16 > heightAtScale1 + 15f,
                    "Expected stack height at fontScale=1.6 ($heightAtScale16) > height at 1.0 ($heightAtScale1 + 15)",
                )

                // Swiping to page 2 on Post A and then swapping non-LazyColumn slot to Post B resets pager to page 1
                onNodeWithTag("plugin_widget_pager").performTouchInput { swipeLeft() }
                waitForIdle()
                onNodeWithText("Page Two").assertIsDisplayed()

                activePost = postB
                waitForIdle()
                onNodeWithText("Page One").assertIsDisplayed()
            } finally {
                stopKoin()
            }
        }

    @Test
    fun multiCardPagerPreservesSelectedIdentityAcrossEarlierCardInsertRemoveAndResetsWhenRemoved() =
        runComposeUiTest {
            fun makeCard(
                pluginId: String,
                id: String,
                title: String,
            ) = UiPluginWidgetCard(
                pluginId = pluginId,
                pluginName = pluginId,
                pluginIconPath = null,
                id = id,
                title = title,
                body = persistentListOf(UiWidgetNode.Text("Body $title")),
            )

            val cardA = makeCard("p1", "a", "Card A")
            val cardB = makeCard("p2", "b", "Card B")
            val cardC = makeCard("p3", "c", "Card C")
            val cardCDup = makeCard("p3", "c", "Card C Dup")

            assertEquals(
                listOf("p2:b", "p3:c", "p3:c#1"),
                uniquePluginWidgetCardIdentities(listOf(cardB, cardC, cardCDup)),
            )

            var currentCards by mutableStateOf(persistentListOf(cardB, cardC))
            setContent {
                Box(Modifier.width(360.dp)) {
                    PluginWidgetStackComponent(
                        cards = currentCards,
                        postIdentity = "post-identity-1",
                    )
                }
            }

            onNodeWithText("Card B").assertIsDisplayed()
            onNodeWithContentDescription("Page 1 of 2").assertIsSelected()

            // Swipe to Card C (page 2 of 2)
            onNodeWithTag("plugin_widget_pager").performTouchInput { swipeLeft() }
            waitForIdle()
            onNodeWithText("Card C").assertIsDisplayed()
            onNodeWithContentDescription("Page 2 of 2").assertIsSelected()

            // Prepending an earlier plugin's card preserves Card C as selected (now page 3 of 3)
            currentCards = persistentListOf(cardA, cardB, cardC)
            waitForIdle()
            onNodeWithText("Card C").assertIsDisplayed()
            onNodeWithContentDescription("Page 3 of 3").assertIsSelected()

            // Removing the earlier plugin's card still preserves Card C (now page 2 of 2)
            currentCards = persistentListOf(cardB, cardC)
            waitForIdle()
            onNodeWithText("Card C").assertIsDisplayed()
            onNodeWithContentDescription("Page 2 of 2").assertIsSelected()

            // Removing the selected Card C resets to page 0 (Card A, page 1 of 2)
            currentCards = persistentListOf(cardA, cardB)
            waitForIdle()
            onNodeWithText("Card A").assertIsDisplayed()
            onNodeWithContentDescription("Page 1 of 2").assertIsSelected()
        }

    @Test
    fun copyFeedbackShowsCheckmarkAndAutoResetsAfterTimeout() =
        runComposeUiTest {
            val card =
                UiPluginWidgetCard(
                    pluginId = "org.example.copy",
                    pluginName = "Copy Test",
                    pluginIconPath = null,
                    id = "copy-1",
                    title = "Copyable Card",
                    body =
                        persistentListOf(
                            UiWidgetNode.Text(
                                text = "Copyable code",
                                style = UiWidgetTextStyle.Code,
                                copyable = true,
                            ),
                            UiWidgetNode.KeyValue(
                                items =
                                    persistentListOf(
                                        UiWidgetNode.KeyValue.Item(
                                            label = "Hash",
                                            value = "0xdeadbeef",
                                            action = UiWidgetAction.Copy("0xdeadbeef"),
                                        ),
                                    ),
                            ),
                        ),
                    actions =
                        persistentListOf(
                            UiWidgetButton(
                                label = "Copy Token",
                                action = UiWidgetAction.Copy("tok-123"),
                            ),
                        ),
                )

            setContent {
                Box(Modifier.width(360.dp)) {
                    PluginWidgetStackComponent(cards = persistentListOf(card))
                }
            }

            onNodeWithTag("plugin_widget_text_copy_icon", useUnmergedTree = true).assertIsDisplayed()
            onNodeWithTag("plugin_widget_kv_copy_glyph", useUnmergedTree = true).assertIsDisplayed()
            onNodeWithTag("plugin_widget_button_copied_icon", useUnmergedTree = true).assertDoesNotExist()

            onNodeWithText("Copyable code").performClick()
            onNodeWithText("0xdeadbeef").performClick()
            onNodeWithText("Copy Token").performClick()
            waitForIdle()

            onNodeWithTag("plugin_widget_text_copied_icon", useUnmergedTree = true).assertIsDisplayed()
            onNodeWithTag("plugin_widget_kv_copied_check", useUnmergedTree = true).assertIsDisplayed()
            onNodeWithTag("plugin_widget_button_copied_icon", useUnmergedTree = true).assertIsDisplayed()

            mainClock.advanceTimeBy(1_600L)
            waitForIdle()

            onNodeWithTag("plugin_widget_text_copy_icon", useUnmergedTree = true).assertIsDisplayed()
            onNodeWithTag("plugin_widget_kv_copy_glyph", useUnmergedTree = true).assertIsDisplayed()
            onNodeWithTag("plugin_widget_button_copied_icon", useUnmergedTree = true).assertDoesNotExist()
        }
}
