package dev.dimension.flare.ui.component.profile

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import dev.dimension.flare.data.model.appearance.GlobalAppearance
import dev.dimension.flare.data.model.appearance.TimelineAppearance
import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.component.ComposeInAppNotification
import dev.dimension.flare.ui.component.LocalGlobalAppearance
import dev.dimension.flare.ui.component.LocalShareImageLoading
import dev.dimension.flare.ui.component.LocalTimelineAppearance
import dev.dimension.flare.ui.component.ShareImageLoading
import dev.dimension.flare.ui.component.platform.LocalWindowSizeClass
import dev.dimension.flare.ui.component.platform.WindowSizeClass
import dev.dimension.flare.ui.humanizer.PlatformFormatter
import dev.dimension.flare.ui.model.UiHandle
import dev.dimension.flare.ui.model.UiProfile
import dev.dimension.flare.ui.model.UiState
import dev.dimension.flare.ui.model.createSampleUser
import dev.dimension.flare.ui.model.toUiImage
import dev.dimension.flare.ui.presenter.profile.FollowButtonState
import dev.dimension.flare.ui.presenter.profile.ProfileState
import dev.dimension.flare.ui.render.toUiPlainText
import dev.dimension.flare.ui.screen.home.ProfileScreen
import dev.dimension.flare.ui.screen.share.ShareCardDialog
import dev.dimension.flare.ui.screen.share.ShareCardPreviewFrame
import dev.dimension.flare.ui.screen.share.SharePreviewStyle
import dev.dimension.flare.ui.screen.share.crossPostInitialText
import dev.dimension.flare.ui.theme.FlareTheme
import dev.dimension.flare.ui.theme.LocalComposeWindow
import kotlinx.collections.immutable.persistentListOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.time.Instant

class ProfileShareContentTest {
    @get:Rule
    val compose = createComposeRule()

    private val owner =
        object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
    private var ownsKoin = false

    @Before
    fun setUp() {
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
                        single { ComposeInAppNotification() }
                    },
                )
            }
            ownsKoin = true
        }
    }

    @After
    fun tearDown() {
        try {
            compose.runOnIdle { owner.viewModelStore.clear() }
        } finally {
            if (ownsKoin) {
                stopKoin()
                ownsKoin = false
            }
        }
    }

    @Test
    fun shareCardDialogHidesShareLinkWhenUrlIsNullAndKeepsImageActionsEnabled() {
        val profile = sampleProfile()
        compose.setContent {
            FlareTheme(isDarkTheme = false) {
                CompositionLocalProvider(
                    LocalComposeWindow provides null,
                    LocalGlobalAppearance provides GlobalAppearance(),
                    LocalTimelineAppearance provides TimelineAppearance(),
                ) {
                    ShareCardDialog(
                        isReady = true,
                        shareUrl = null,
                        fileName = "profile_alice.png",
                        imageLoadingKey = profile,
                        onBack = {},
                        onCrossPost = {},
                    ) {
                        ProfileShareContent(profile = profile)
                    }
                }
            }
        }

        compose.onNodeWithText("Share link").assertDoesNotExist()
        compose.onNodeWithText("Cross-post").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Share screenshot").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Save screenshot").assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun shareCardDialogShowsShareLinkWhenUrlPresent() {
        val profile = sampleProfile(url = "https://example.org/@alice")
        compose.setContent {
            FlareTheme(isDarkTheme = false) {
                CompositionLocalProvider(
                    LocalComposeWindow provides null,
                    LocalGlobalAppearance provides GlobalAppearance(),
                    LocalTimelineAppearance provides TimelineAppearance(),
                ) {
                    ShareCardDialog(
                        isReady = true,
                        shareUrl = profile.url,
                        fileName = "profile_alice.png",
                        imageLoadingKey = profile,
                        onBack = {},
                        onCrossPost = {},
                    ) {
                        ProfileShareContent(profile = profile)
                    }
                }
            }
        }

        compose.onNodeWithText("Share link").assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun shareCardDialogTreatsBlankUrlAsAbsentAndDisablesImageActionsUntilReady() {
        var isReady by mutableStateOf(false)
        var shareUrl by mutableStateOf<String?>("   ")
        compose.setContent {
            FlareTheme(isDarkTheme = false) {
                CompositionLocalProvider(
                    LocalComposeWindow provides null,
                    LocalGlobalAppearance provides GlobalAppearance(),
                    LocalTimelineAppearance provides TimelineAppearance(),
                ) {
                    ShareCardDialog(
                        isReady = isReady,
                        shareUrl = shareUrl,
                        fileName = "profile_alice.png",
                        imageLoadingKey = isReady,
                        onBack = {},
                        onCrossPost = {},
                    ) {
                        ProfileShareContent(profile = null)
                    }
                }
            }
        }

        compose.onNodeWithText("Share link").assertDoesNotExist()
        compose.onNodeWithText("Cross-post").assertIsNotEnabled()
        compose.onNodeWithText("Share screenshot").assertIsNotEnabled()
        compose.onNodeWithText("Save screenshot").assertIsNotEnabled()

        compose.runOnIdle {
            shareUrl = "  https://example.org/@alice  "
        }
        compose.onNodeWithText("Share link").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Cross-post").assertIsNotEnabled()

        compose.runOnIdle {
            isReady = true
        }
        compose.onNodeWithText("Cross-post").assertIsEnabled()
        compose.onNodeWithText("Share screenshot").assertIsEnabled()
        compose.onNodeWithText("Save screenshot").assertIsEnabled()
    }

    @Test
    fun crossPostInitialTextFormatsNonBlankUrlAndStaysEmptyWhenNullOrBlank() {
        assertEquals("", crossPostInitialText(null))
        assertEquals("", crossPostInitialText(""))
        assertEquals("", crossPostInitialText("   "))
        assertEquals("\n\nhttps://example.org/@alice", crossPostInitialText("https://example.org/@alice"))
        assertEquals("\n\nhttps://example.org/@alice", crossPostInitialText("  https://example.org/@alice  "))
    }

    @Test
    fun profileShareScreenshotKeepsEqualCardPaddingOnEveryEdge() {
        val bannerFile = Files.createTempFile("desktop-profile-banner", ".png").toFile().apply { deleteOnExit() }
        val bannerImage = BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB)
        val graphics = bannerImage.createGraphics()
        graphics.color = Color.RED
        graphics.fillRect(0, 0, bannerImage.width, bannerImage.height)
        graphics.dispose()
        ImageIO.write(bannerImage, "png", bannerFile)

        val profile = sampleProfile().copy(banner = bannerFile.toURI().toString().toUiImage())
        val imageLoading = ShareImageLoading()
        var imageReady by mutableStateOf(false)

        compose.setContent {
            LaunchedEffect(imageLoading) {
                imageLoading.awaitReady()
                imageReady = true
            }
            FlareTheme(isDarkTheme = false) {
                CompositionLocalProvider(
                    LocalGlobalAppearance provides GlobalAppearance(),
                    LocalTimelineAppearance provides TimelineAppearance(),
                    LocalShareImageLoading provides imageLoading,
                    LocalDensity provides Density(1f),
                ) {
                    ShareCardPreviewFrame(
                        previewStyle = SharePreviewStyle.Card,
                        modifier = Modifier.testTag("screenshot"),
                    ) {
                        Box(modifier = Modifier.testTag("card-content")) {
                            ProfileShareContent(profile = profile)
                        }
                    }
                }
            }
        }

        compose.waitUntil(timeoutMillis = 10_000) { imageReady }
        compose.waitForIdle()

        val padding = 64f
        val frameBounds = compose.onNodeWithTag("screenshot").fetchSemanticsNode().boundsInRoot
        val cardBounds = compose.onNodeWithTag("card-content").fetchSemanticsNode().boundsInRoot
        assertEquals(padding, cardBounds.left - frameBounds.left, 0.5f)
        assertEquals(padding, frameBounds.right - cardBounds.right, 0.5f)
        assertEquals(padding, cardBounds.top - frameBounds.top, 0.5f)
        assertEquals(padding, frameBounds.bottom - cardBounds.bottom, 0.5f)

        val bitmap = compose.onNodeWithTag("screenshot").captureToImage().toAwtImage()
        val paddingPx = 64

        fun isRedPixel(
            x: Int,
            y: Int,
        ): Boolean {
            val c = Color(bitmap.getRGB(x, y))
            return c.red > 200 && c.green < 50 && c.blue < 50
        }

        assertTrue(isRedPixel(bitmap.width / 2, paddingPx + 20))
        assertFalse(isRedPixel(paddingPx + 1, paddingPx + 1))
        assertFalse(isRedPixel(bitmap.width - paddingPx - 2, paddingPx + 1))

        for (y in 0 until paddingPx) {
            for (x in 0 until bitmap.width) {
                assertFalse("Banner bled into top padding at ($x, $y)", isRedPixel(x, y))
            }
        }
        for (y in 0 until bitmap.height) {
            for (x in 0 until paddingPx) {
                assertFalse("Banner bled into left padding at ($x, $y)", isRedPixel(x, y))
            }
            for (x in (bitmap.width - paddingPx) until bitmap.width) {
                assertFalse("Banner bled into right padding at ($x, $y)", isRedPixel(x, y))
            }
        }
    }

    @Test
    fun profileScreenShowsShareButtonForBothOwnAndOtherProfile() {
        val profile = sampleProfile()
        var isMe by mutableStateOf(true)
        var windowSizeClass by mutableStateOf(WindowSizeClass.Compact)
        val clickedKeys = mutableListOf<MicroBlogKey>()

        compose.setContent {
            FlareTheme(isDarkTheme = false) {
                CompositionLocalProvider(
                    LocalViewModelStoreOwner provides owner,
                    LocalWindowSizeClass provides windowSizeClass,
                    LocalGlobalAppearance provides GlobalAppearance(),
                    LocalTimelineAppearance provides
                        TimelineAppearance(
                            aiConfig = TimelineAppearance.AiConfig(agent = false),
                        ),
                ) {
                    ProfileScreen(
                        accountType = AccountType.Guest,
                        userKey = profile.key,
                        onProfileShareClick = { clickedKeys += it },
                        profileState = {
                            fakeProfileState(
                                profile = profile,
                                isMe = UiState.Success(isMe),
                            )
                        },
                    )
                }
            }
        }

        compose.onNodeWithContentDescription("Share").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(listOf(profile.key), clickedKeys)
            isMe = false
        }
        compose.onNodeWithContentDescription("Share").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(listOf(profile.key, profile.key), clickedKeys)
            windowSizeClass = WindowSizeClass.Expanded
        }
        compose.onNodeWithContentDescription("Share").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(listOf(profile.key, profile.key, profile.key), clickedKeys)
        }
    }

    private fun sampleProfile(url: String? = null): UiProfile =
        createSampleUser().copy(
            key = MicroBlogKey("alice", "example.org"),
            handle = UiHandle("alice", "example.org", displayOverride = "@alice@example.org"),
            nameInternal = "Alice Rivera".toUiPlainText(),
            avatar = null,
            banner = null,
            url = url,
        )

    private fun fakeProfileState(
        profile: UiProfile,
        isMe: UiState<Boolean>,
    ): ProfileState =
        object : ProfileState(
            userState = UiState.Success(profile),
            relationState = UiState.Loading(),
            followButtonState = UiState.Success(FollowButtonState.Follow),
            isMe = isMe,
            actions = persistentListOf(),
            myAccountKey = UiState.Success(profile.key),
            tabs = UiState.Success(persistentListOf()),
        ) {
            override fun follow(userKey: MicroBlogKey) = Unit

            override fun unfollow(userKey: MicroBlogKey) = Unit

            override fun unblock(userKey: MicroBlogKey) = Unit

            override fun report(userKey: MicroBlogKey) = Unit

            override fun retryTabs() = Unit
        }
}
