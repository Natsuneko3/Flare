package dev.dimension.flare.ui.component.status

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import dev.dimension.flare.data.model.appearance.GlobalAppearance
import dev.dimension.flare.data.model.appearance.TimelineAppearance
import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.component.LocalGlobalAppearance
import dev.dimension.flare.ui.component.LocalTimelineAppearance
import dev.dimension.flare.ui.humanizer.PlatformFormatter
import dev.dimension.flare.ui.model.ClickEvent
import dev.dimension.flare.ui.model.TranslationDisplayState
import dev.dimension.flare.ui.model.UiState
import dev.dimension.flare.ui.model.UiTimelineV2
import dev.dimension.flare.ui.model.UiTranslatableText
import dev.dimension.flare.ui.model.createSampleUser
import dev.dimension.flare.ui.presenter.status.StatusSharePresenter
import dev.dimension.flare.ui.render.toUi
import dev.dimension.flare.ui.render.toUiPlainText
import dev.dimension.flare.ui.screen.status.action.StatusShareReplyDialog
import dev.dimension.flare.ui.theme.FlareTheme
import kotlinx.collections.immutable.persistentListOf
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.File
import javax.imageio.ImageIO
import kotlin.time.Instant

class StatusShareContentTest {
    @get:Rule val composeRule = createComposeRule()

    companion object {
        private var ownsKoin = false

        @JvmStatic @BeforeClass
        fun setUpFormatting() {
            if (GlobalContext.getOrNull() == null) {
                startKoin {
                    modules(
                        module {
                            single<PlatformFormatter> {
                                object : PlatformFormatter {
                                    override fun formatNumber(number: Long) = number.toString()

                                    override fun formatRelativeInstant(instant: Instant) = "now"

                                    override fun formatFullInstant(instant: Instant) = "2026-01-01"

                                    override fun formatAbsoluteInstant(instant: Instant) = "2026-01-01"
                                }
                            }
                        },
                    )
                }
                ownsKoin = true
            }
        }

        @JvmStatic @AfterClass
        fun stopFormatting() {
            if (ownsKoin) stopKoin()
        }
    }

    @Test
    fun replyRowExposesOneToggleAndNeverNavigatesThePost() {
        val reply =
            post("reply", "Original body").copy(
                user = createSampleUser().copy(avatar = null, banner = null),
                content = UiTranslatableText("Original body".toUiPlainText(), "Selectable reply".toUiPlainText()),
                contentWarning = UiTranslatableText("Original warning".toUiPlainText(), "Translated warning".toUiPlainText()),
                translationDisplayState = TranslationDisplayState.Translated,
            )
        var appearance by mutableStateOf(TimelineAppearance(showTranslateButton = false, expandContentWarning = true))
        var selected by mutableStateOf(false)
        var confirmed = false
        var cancelled = false
        val state =
            object : StatusSharePresenter.State {
                override val status = UiState.Success<UiTimelineV2>(reply)
                override val replies = persistentListOf<UiTimelineV2>(reply)
                override val selectedReplies = persistentListOf<UiTimelineV2>()
                override val expandableReplies = persistentListOf<MicroBlogKey>()
                override val selecting = true
                override val selectedCount get() = if (selected) 1 else 0
                override val loading = false
                override val error: Throwable? = null
                override val hasMore = false

                override fun isSelected(key: MicroBlogKey) = selected

                override fun toggleReply(key: MicroBlogKey) {
                    selected = !selected
                }

                override fun selectReplies() {}

                override fun clearSelection() {
                    selected = false
                }

                override fun cancelSelection() {
                    cancelled = true
                }

                override fun confirmSelection() {
                    confirmed = true
                }

                override fun loadMore() {}

                override fun loadReplyChildren(key: MicroBlogKey) {}
            }
        composeRule.setContent {
            FlareTheme(isDarkTheme = false) {
                CompositionLocalProvider(
                    LocalGlobalAppearance provides GlobalAppearance(),
                    LocalTimelineAppearance provides appearance,
                ) {
                    StatusShareReplyDialog(state)
                }
            }
        }
        composeRule.onNodeWithContentDescription("Translated warning", substring = true).assertIsOff()
        composeRule.onAllNodesWithContentDescription("Original body", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithContentDescription("Original warning", substring = true).assertCountEquals(0)
        composeRule
            .onNodeWithContentDescription("Selectable reply", substring = true)
            .assertIsOff()
            .performClick()
            .assertIsOn()
        composeRule.runOnIdle { assertTrue(selected) }
        ImageIO.write(
            composeRule.onNode(isPopup()).captureToImage().toAwtImage(),
            "png",
            File(System.getProperty("java.io.tmpdir"), "flare-share-replies-desktop.png"),
        )
        composeRule.onNodeWithContentDescription("Selectable reply", substring = true).performClick().assertIsOff()
        composeRule.runOnIdle { assertFalse(selected) }
        val selector = composeRule.onNodeWithContentDescription("Selectable reply", substring = true)
        selector.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        selector.performKeyInput {
            keyDown(Key.ShiftLeft)
            pressKey(Key.Tab)
            keyUp(Key.ShiftLeft)
        }
        composeRule.onNodeWithText("Cancel").assertIsFocused()
        selector.performKeyInput { pressKey(Key.Tab) }
        selector.assertIsFocused()
        selector.performKeyInput { pressKey(Key.Enter) }
        selector.assertIsOn()
        composeRule.runOnIdle { assertTrue(selected) }
        composeRule.onNodeWithText("Clear").performClick()
        selector.assertIsOff()
        composeRule.onNodeWithText("Done").performClick()
        composeRule.runOnIdle { assertTrue(confirmed) }
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.runOnIdle { assertTrue(cancelled) }
        composeRule.runOnIdle {
            appearance = appearance.copy(aiConfig = appearance.aiConfig.copy(showOriginalWithTranslation = true))
        }
        composeRule.onNodeWithContentDescription("Original body", substring = true).assertIsOff()
        composeRule.onNodeWithContentDescription("Original warning", substring = true).assertIsOff()
        composeRule.onNodeWithContentDescription("Selectable reply", substring = true).assertIsOff()
        composeRule.runOnIdle { appearance = appearance.copy(expandContentWarning = false) }
        composeRule.onNodeWithContentDescription("Translated warning", substring = true).assertIsOff()
        composeRule.onAllNodesWithContentDescription("Original body", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithContentDescription("Selectable reply", substring = true).assertCountEquals(0)
    }

    @Test
    fun screenshotKeepsEqualPaddingOnEveryEdge() {
        val root = post("centered", "Centered post")
        composeRule.setContent {
            FlareTheme(isDarkTheme = false) {
                CompositionLocalProvider(
                    LocalGlobalAppearance provides GlobalAppearance(),
                    LocalTimelineAppearance provides TimelineAppearance(showTranslateButton = false),
                ) {
                    Box(Modifier.testTag("screenshot").background(Color.Blue).padding(64.dp)) {
                        Box(Modifier.width(360.dp).background(Color.Red).padding(16.dp)) {
                            StatusShareContent(root, root.statusKey, emptyList())
                        }
                    }
                }
            }
        }
        val bitmap = composeRule.onNodeWithTag("screenshot").captureToImage().toAwtImage()
        val padding = with(composeRule.density) { 64.dp.roundToPx() }
        val columns = (0 until bitmap.width).filter { bitmap.getRGB(it, bitmap.height / 2) == java.awt.Color.RED.rgb }
        val rows = (0 until bitmap.height).filter { bitmap.getRGB(bitmap.width / 2, it) == java.awt.Color.RED.rgb }
        assertEquals("Left padding", padding, columns.first())
        assertEquals("Right padding", padding, bitmap.width - columns.last() - 1)
        assertEquals("Top padding", padding, rows.first())
        assertEquals("Bottom padding", padding, bitmap.height - rows.last() - 1)
    }

    @Test
    fun screenshotReplyIgnoresTimelineLineLimitAndDoesNotContainPickerControls() {
        val text = (1..8).joinToString("\n") { "Reply line $it" }
        val root = post("root", "Root post")
        val reply = post("reply", text)
        composeRule.setContent {
            FlareTheme(isDarkTheme = false) {
                CompositionLocalProvider(
                    LocalGlobalAppearance provides GlobalAppearance(),
                    LocalTimelineAppearance provides TimelineAppearance(lineLimit = 1, showTranslateButton = false),
                ) {
                    Box(Modifier.width(360.dp)) {
                        StatusShareContent(root, root.statusKey, listOf(reply))
                    }
                }
            }
        }
        val layouts = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(8, layouts.single().lineCount)
        assertFalse(layouts.single().hasVisualOverflow)
        composeRule.onAllNodes(isToggleable()).assertCountEquals(0)
        composeRule
            .onAllNodes(
                androidx.compose.ui.test
                    .hasText("2026-01-01"),
            ).assertCountEquals(1)
    }

    @Test
    fun shareContentAndReplyPickerHideAiAgentIconWhenEnabled() {
        val author = createSampleUser().copy(avatar = null, banner = null)
        val root = post("root", "Root post").copy(user = author)
        val reply = post("reply", "Reply post").copy(user = author)
        var agentEnabled by mutableStateOf(false)
        var mode by mutableStateOf("baseline")
        val state =
            object : StatusSharePresenter.State {
                override val status = UiState.Success<UiTimelineV2>(root)
                override val replies = persistentListOf<UiTimelineV2>(reply)
                override val selectedReplies = persistentListOf<UiTimelineV2>(reply)
                override val expandableReplies = persistentListOf<MicroBlogKey>()
                override val selecting = true
                override val selectedCount = 1
                override val loading = false
                override val error: Throwable? = null
                override val hasMore = false

                override fun isSelected(key: MicroBlogKey) = true

                override fun toggleReply(key: MicroBlogKey) {}

                override fun selectReplies() {}

                override fun clearSelection() {}

                override fun cancelSelection() {}

                override fun confirmSelection() {}

                override fun loadMore() {}

                override fun loadReplyChildren(key: MicroBlogKey) {}
            }
        composeRule.setContent {
            FlareTheme(isDarkTheme = false) {
                CompositionLocalProvider(
                    LocalGlobalAppearance provides GlobalAppearance(),
                    LocalTimelineAppearance provides
                        TimelineAppearance(
                            aiConfig = TimelineAppearance.AiConfig(agent = agentEnabled),
                            showTranslateButton = false,
                        ),
                ) {
                    when (mode) {
                        "baseline" -> {
                            Box(Modifier.width(360.dp)) {
                                StatusItem(item = root)
                            }
                        }

                        "picker" -> {
                            Box(Modifier.width(360.dp).testTag("reply-picker")) {
                                StatusShareReplyList(state)
                            }
                        }

                        else -> {
                            Box(Modifier.width(360.dp)) {
                                StatusShareContent(root, root.statusKey, listOf(reply))
                            }
                        }
                    }
                }
            }
        }
        val baselineDescriptions =
            composeRule
                .onAllNodes(hasClickAction())
                .fetchSemanticsNodes()
                .flatMap { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
                .toSet()
        composeRule.runOnIdle { agentEnabled = true }
        val insightDescription =
            composeRule
                .onAllNodes(hasClickAction())
                .fetchSemanticsNodes()
                .flatMap { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
                .single { it !in baselineDescriptions }
        composeRule.onAllNodesWithContentDescription(insightDescription).assertCountEquals(1)
        composeRule.runOnIdle { mode = "share" }
        composeRule.onAllNodesWithContentDescription(insightDescription).assertCountEquals(0)
        composeRule.runOnIdle {
            mode = "picker"
            agentEnabled = false
        }
        val expectedPicker = composeRule.onNodeWithTag("reply-picker").captureToImage().toAwtImage()
        composeRule.runOnIdle { agentEnabled = true }
        val actualPicker = composeRule.onNodeWithTag("reply-picker").captureToImage().toAwtImage()
        assertEquals(expectedPicker.width, actualPicker.width)
        assertEquals(expectedPicker.height, actualPicker.height)
        for (y in 0 until expectedPicker.height) {
            for (x in 0 until expectedPicker.width) {
                assertEquals(
                    "Reply picker pixel ($x, $y) must not change when AI agent is enabled",
                    expectedPicker.getRGB(x, y),
                    actualPicker.getRGB(x, y),
                )
            }
        }
    }

    private fun post(
        id: String,
        text: String,
    ) = UiTimelineV2.Post(
        platformId = "Mastodon",
        images = persistentListOf(),
        sensitive = false,
        contentWarning = null,
        user = null,
        content = UiTranslatableText(text.toUiPlainText()),
        actions = persistentListOf(),
        poll = null,
        statusKey = MicroBlogKey(id, "example.invalid"),
        card = null,
        createdAt = Instant.parse("2026-01-01T00:00:00Z").toUi(),
        clickEvent = ClickEvent.Noop,
        accountType = AccountType.Guest,
    )
}
