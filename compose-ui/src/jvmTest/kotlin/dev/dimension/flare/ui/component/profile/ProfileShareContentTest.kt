package dev.dimension.flare.ui.component.profile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.assertTopPositionInRootIsEqualTo
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import coil3.ColorImage
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.decode.DataSource
import coil3.intercept.Interceptor
import coil3.request.SuccessResult
import com.fleeksoft.ksoup.Ksoup
import dev.dimension.flare.data.model.appearance.TimelineAppearance
import dev.dimension.flare.ui.component.LocalTimelineAppearance
import dev.dimension.flare.ui.humanizer.PlatformFormatter
import dev.dimension.flare.ui.model.ClickEvent
import dev.dimension.flare.ui.model.TranslationDisplayState
import dev.dimension.flare.ui.model.UiHandle
import dev.dimension.flare.ui.model.UiProfile
import dev.dimension.flare.ui.model.createSampleUser
import dev.dimension.flare.ui.model.toUiImage
import dev.dimension.flare.ui.render.toUi
import dev.dimension.flare.ui.render.toUiPlainText
import io.github.composefluent.FluentTheme
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.test.assertTrue
import kotlin.time.Instant

class ProfileShareContentTest {
    @get:Rule
    val compose = createComposeRule()

    companion object {
        private var ownsKoin = false

        @JvmStatic
        @BeforeClass
        fun setUpFormatting() {
            if (GlobalContext.getOrNull() == null) {
                startKoin {
                    modules(
                        module {
                            single<PlatformFormatter> {
                                // Formatter.platformFormatter is a process-wide lazy singleton; match StatusHeaderSpacingTest's values.
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
                ownsKoin = true
            }
        }

        @JvmStatic
        @AfterClass
        fun stopFormatting() {
            if (ownsKoin) stopKoin()
        }
    }

    @OptIn(DelicateCoilApi::class)
    @Test
    fun profileShareContentShowsProfileDetailsWithoutFollowControlsOrClicks() {
        val bannerUrl = "memory://share-banner.png"
        val avatarUrl = "memory://share-avatar.png"
        val previousLoader = SingletonImageLoader.get(PlatformContext.INSTANCE)
        SingletonImageLoader.setUnsafe(
            ImageLoader
                .Builder(PlatformContext.INSTANCE)
                .components {
                    add(
                        Interceptor { chain ->
                            when (chain.request.data.toString()) {
                                bannerUrl, avatarUrl ->
                                    SuccessResult(ColorImage(0xFF336699.toInt(), 20, 20), chain.request, DataSource.MEMORY)

                                else -> chain.proceed()
                            }
                        },
                    )
                }.build(),
        )
        try {
            val openedUris = mutableListOf<String>()
            val uriHandler =
                object : UriHandler {
                    override fun openUri(uri: String) {
                        openedUris += uri
                    }
                }
            val profile =
                createSampleUser().copy(
                    nameInternal = "Alice Rivera".toUiPlainText(),
                    handle = UiHandle("alice", "example.org", displayOverride = "@alice@example.org"),
                    banner = bannerUrl.toUiImage(),
                    avatar = avatarUrl.toUiImage(),
                    clickEvent = ClickEvent.Deeplink("flare://profile/alice"),
                    mark = persistentListOf(UiProfile.Mark.Verified, UiProfile.Mark.Bot),
                    description =
                        Ksoup
                            .parse("<p>Building federated apps on <a href=\"https://example.org/flare\">Flare</a>.</p>")
                            .body()
                            .toUi(),
                    bottomContent =
                        UiProfile.BottomContent.Fields(
                            fields =
                                persistentMapOf(
                                    "Pronouns" to "she/her".toUiPlainText(),
                                    "Website" to Ksoup.parse("<a href=\"https://example.org\">example.org</a>").body().toUi(),
                                ),
                        ),
                    matrices =
                        UiProfile.Matrices(
                            fansCount = 1000,
                            followsCount = 500,
                            statusesCount = 300,
                        ),
                )

            compose.setContent {
                FluentTheme {
                    CompositionLocalProvider(
                        LocalTimelineAppearance provides TimelineAppearance(),
                        LocalDensity provides Density(1f),
                        LocalUriHandler provides uriHandler,
                    ) {
                        Box(Modifier.width(360.dp)) {
                            ProfileShareContent(profile = profile)
                        }
                    }
                }
            }

            compose.onNodeWithText("Alice Rivera", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithText("@alice@example.org", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithText("@alice@example.org", useUnmergedTree = true).assertLeftPositionInRootIsEqualTo(16.dp)
            compose.onNodeWithContentDescription("Profile banner for Alice Rivera").assertTopPositionInRootIsEqualTo(0.dp)
            compose.onNodeWithContentDescription("Profile banner for Alice Rivera").assertHeightIsEqualTo(150.dp)
            compose.onNodeWithContentDescription("Verified profile").assertIsDisplayed()
            compose.onNodeWithContentDescription("Bot account").assertIsDisplayed()
            compose.onNodeWithText("Building federated apps on Flare.", substring = true, useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithText("Pronouns", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithText("she/her", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithText("Website", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithText("example.org", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithText("300 Posts", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithText("500 Following", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithText("1000 Followers", useUnmergedTree = true).assertIsDisplayed()

            listOf("Follow", "Following", "Requested", "Request follow", "Blocked", "Follows you").forEach { label ->
                compose.onNodeWithText(label).assertDoesNotExist()
            }
            compose.onNodeWithContentDescription("Profile banner for Alice Rivera").assertHasNoClickAction()
            compose.onNodeWithContentDescription("Profile picture for Alice Rivera").assertHasNoClickAction()

            val clickableNodes = compose.onAllNodes(hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes()
            clickableNodes.forEachIndexed { index, _ ->
                compose.onAllNodes(hasClickAction(), useUnmergedTree = true)[index].performClick()
            }
            compose.onNodeWithText("300 Posts", useUnmergedTree = true).performClick()
            compose.onNodeWithText("500 Following", useUnmergedTree = true).performClick()
            compose.onNodeWithText("1000 Followers", useUnmergedTree = true).performClick()
            compose.onNodeWithText("example.org", useUnmergedTree = true).performClick()
            compose.onNodeWithText("Building federated apps on Flare.", substring = true, useUnmergedTree = true).performClick()
            compose.runOnIdle {
                assertTrue(openedUris.isEmpty())
            }
        } finally {
            SingletonImageLoader.setUnsafe(previousLoader)
        }
    }

    @Test
    fun profileShareContentShowsIconifyFieldsAndNullPlaceholder() {
        val openedUris = mutableListOf<String>()
        val uriHandler =
            object : UriHandler {
                override fun openUri(uri: String) {
                    openedUris += uri
                }
            }
        val iconifyProfile =
            createSampleUser().copy(
                nameInternal = "Kenji Sato".toUiPlainText(),
                handle = UiHandle("kenji", "example.jp", displayOverride = "@kenji"),
                banner = null,
                avatar = null,
                translationDisplayState = TranslationDisplayState.Translated,
                bottomContent =
                    UiProfile.BottomContent.Iconify(
                        items =
                            persistentMapOf(
                                UiProfile.BottomContent.Iconify.Icon.Location to "Tokyo".toUiPlainText(),
                                UiProfile.BottomContent.Iconify.Icon.Url to
                                    Ksoup.parse("<a href=\"https://kenji.example\">https://kenji.example</a>").body().toUi(),
                                UiProfile.BottomContent.Iconify.Icon.Verify to "verified.example".toUiPlainText(),
                            ),
                    ),
            )
        var profile by mutableStateOf<UiProfile?>(iconifyProfile)

        compose.setContent {
            FluentTheme {
                CompositionLocalProvider(
                    LocalTimelineAppearance provides TimelineAppearance(),
                    LocalDensity provides Density(1f),
                    LocalUriHandler provides uriHandler,
                ) {
                    Box(Modifier.width(360.dp)) {
                        ProfileShareContent(profile = profile)
                    }
                }
            }
        }

        compose.onNodeWithContentDescription("Translated").assertIsDisplayed()
        compose.onNodeWithContentDescription("Location").assertIsDisplayed()
        compose.onNodeWithText("Tokyo", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Website").assertIsDisplayed()
        compose.onNodeWithText("https://kenji.example", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Verified link").assertIsDisplayed()
        compose.onNodeWithText("verified.example", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("https://kenji.example", useUnmergedTree = true).performClick()
        compose.runOnIdle {
            assertTrue(openedUris.isEmpty())
            profile = null
        }

        compose.onNodeWithText("Loading user").assertIsDisplayed()
        compose.onNodeWithText("Loading user").assertTopPositionInRootIsEqualTo(150.dp)
    }
}
