package dev.dimension.flare.ui.component

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.assertTopPositionInRootIsEqualTo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.dimension.flare.data.model.appearance.TimelineAppearance
import dev.dimension.flare.ui.model.UiHandle
import dev.dimension.flare.ui.model.createSampleUser
import dev.dimension.flare.ui.model.toUiImage
import dev.dimension.flare.ui.render.toUiPlainText
import dev.dimension.flare.ui.theme.PlatformTheme
import io.github.composefluent.FluentTheme
import org.junit.Rule
import org.junit.Test
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.assertEquals

class CommonProfileHeaderTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun profileHeaderShowsALargerBoldNameAndUpdatesTheDisplayHandle() {
        val user = createSampleUser()
        val name = "Alex Rivera"
        var handle by mutableStateOf(UiHandle("bsky.app", "bsky.social", displayOverride = "@bsky.app"))
        var expectedSize = 0f

        compose.setContent {
            FluentTheme {
                CompositionLocalProvider(LocalTimelineAppearance provides TimelineAppearance()) {
                    expectedSize = PlatformTheme.typography.title.fontSize.value + 4
                    CommonProfileHeader(
                        banner = null,
                        avatar = null,
                        displayName = name.toUiPlainText(),
                        userKey = user.key,
                        handle = handle,
                    )
                }
            }
        }

        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(name, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val style = layouts.single().layoutInput.style
        assertEquals(expectedSize, style.fontSize.value)
        assertEquals(FontWeight.Bold, style.fontWeight)
        compose.onNodeWithText("@bsky.app").assertIsDisplayed()
        compose.onNodeWithText("@bsky.app@bsky.social").assertDoesNotExist()
        compose.runOnIdle { handle = handle.copy(displayOverride = "@updated.example") }
        compose.onNodeWithText("@updated.example").assertIsDisplayed()
        compose.onNodeWithText("@bsky.app").assertDoesNotExist()
    }

    @Test
    fun plainProfileDetailsUseOnlyTheContentInset() {
        val user = createSampleUser()
        var useCard by mutableStateOf(true)

        compose.setContent {
            FluentTheme {
                CompositionLocalProvider(LocalTimelineAppearance provides TimelineAppearance()) {
                    CommonProfileHeader(
                        banner = null,
                        avatar = null,
                        displayName = user.name,
                        userKey = user.key,
                        handle = UiHandle("reader", "example.org", displayOverride = "@reader"),
                        useCard = useCard,
                    )
                }
            }
        }

        compose.onNodeWithText("@reader").assertLeftPositionInRootIsEqualTo(32.dp)
        compose.runOnIdle { useCard = false }
        compose.onNodeWithText("@reader").assertLeftPositionInRootIsEqualTo(16.dp)
    }

    @Test
    fun profileHeaderOmitsStatusBarInsetWhenDisabled() {
        val bannerFile = Files.createTempFile("profile-banner", ".png").toFile().apply { deleteOnExit() }
        ImageIO.write(BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB), "png", bannerFile)
        val user = createSampleUser()
        var includeStatusBarInset by mutableStateOf(true)

        compose.setContent {
            FluentTheme {
                CompositionLocalProvider(
                    LocalTimelineAppearance provides TimelineAppearance(),
                    LocalDensity provides Density(1f),
                ) {
                    CommonProfileHeader(
                        banner = bannerFile.toURI().toString().toUiImage(),
                        avatar = null,
                        displayName = "Alex Rivera".toUiPlainText(),
                        userKey = user.key,
                        handle = UiHandle("alex", "example.org", displayOverride = "@alex"),
                        includeStatusBarInset = includeStatusBarInset,
                        statusBarTopInset = 48.dp,
                    )
                }
            }
        }

        compose.onNodeWithContentDescription("Profile banner for Alex Rivera").assertHeightIsEqualTo(198.dp)
        compose.onNodeWithContentDescription("Profile picture for Alex Rivera").assertTopPositionInRootIsEqualTo(150.dp)
        compose.runOnIdle { includeStatusBarInset = false }
        compose.onNodeWithContentDescription("Profile banner for Alex Rivera").assertHeightIsEqualTo(150.dp)
        compose.onNodeWithContentDescription("Profile picture for Alex Rivera").assertTopPositionInRootIsEqualTo(102.dp)
    }
}
