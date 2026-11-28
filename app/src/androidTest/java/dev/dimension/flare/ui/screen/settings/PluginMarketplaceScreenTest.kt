package dev.dimension.flare.ui.screen.settings

import android.graphics.Bitmap
import android.view.View
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onParent
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.platform.app.InstrumentationRegistry
import dev.dimension.flare.R
import dev.dimension.flare.data.model.appearance.GlobalAppearance
import dev.dimension.flare.data.model.appearance.TimelineAppearance
import dev.dimension.flare.data.repository.AuthoredPlugin
import dev.dimension.flare.feature.plugin.github.GitHubPluginVersionV1
import dev.dimension.flare.feature.plugin.management.PluginManagementItemV1
import dev.dimension.flare.feature.plugin.management.PluginManagementStateV1
import dev.dimension.flare.feature.plugin.marketplace.PluginMarketplaceEntryV1
import dev.dimension.flare.feature.plugin.marketplace.PluginMarketplaceStateV1
import dev.dimension.flare.ui.component.LocalGlobalAppearance
import dev.dimension.flare.ui.component.LocalTimelineAppearance
import dev.dimension.flare.ui.model.UiText
import dev.dimension.flare.ui.theme.FlareTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class PluginMarketplaceScreenTest {
    private val mediaQueryIntegration = androidx.compose.ui.ComposeUiFlags.isMediaQueryIntegrationEnabled

    init {
        androidx.compose.ui.ComposeUiFlags.isMediaQueryIntegrationEnabled = true
    }

    @org.junit.After
    fun restoreMediaQueryIntegration() {
        androidx.compose.ui.ComposeUiFlags.isMediaQueryIntegrationEnabled = mediaQueryIntegration
    }

    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val entry =
        PluginMarketplaceEntryV1(
            id = "dev.maxlec.zhihu",
            name = "知乎",
            description = "知乎热榜、推荐、关注动态与回答/文章详情",
            repository = "https://github.com/anamaxlec/flare-zhihu-plugin",
            icon =
                "https://raw.githubusercontent.com/anamaxlec/flare-zhihu-plugin/557b6195db413fecc65468195c6646c668b64d64/assets/icon.png",
            version = "0.1.11",
            updatedAt = "2026-11-11",
            commitSha = "557b6195db413fecc65468195c6646c668b64d64",
        )
    private var state by mutableStateOf(
        PluginManagementStateV1(marketplace = PluginMarketplaceStateV1(plugins = listOf(entry), loaded = true)),
    )
    private var installedId: String? = null
    private var openedUrl: String? = null
    private var managed = false
    private var updatedId: String? = null
    private val zhihuIcon by lazy { fixtureIcon("marketplace/zhihu.png") }
    private var refreshed = false
    private var openedMarketplace = false

    @Test
    fun managementKeepsItsLayoutAndOpensTheStoreFromBeforeMore() {
        show(management = true)
        compose.onNodeWithText(context.getString(R.string.settings_plugins_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.plugin_marketplace)).assertDoesNotExist()
        val store = compose.onNodeWithContentDescription(context.getString(R.string.plugin_marketplace))
        val more = compose.onNodeWithContentDescription(context.getString(R.string.more))
        org.junit.Assert.assertTrue(
            store
                .fetchSemanticsNode()
                .boundsInRoot.center.x <
                more
                    .fetchSemanticsNode()
                    .boundsInRoot.center.x,
        )
        screenshot("management")
        store.performClick()
        compose.runOnIdle { assertEquals(true, openedMarketplace) }
    }

    @Test
    fun marketplaceDisplaysTheCatalogAndRoutesInstallAndRepositoryActions() {
        show()
        compose.onNodeWithText(context.getString(R.string.plugin_marketplace_community_notice)).assertIsDisplayed()
        compose.onNodeWithText(entry.name).assertIsDisplayed()
        compose.onNodeWithText("anamaxlec/flare-zhihu-plugin").assertIsDisplayed().performFirstLinkClick()
        compose.runOnIdle {
            assertEquals(entry.repository, openedUrl)
            openedUrl = null
        }
        compose.onNodeWithText(context.getString(R.string.plugin_marketplace_search)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.settings_plugins_title)).assertDoesNotExist()
        compose.onNodeWithContentDescription("Refresh").assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.more)).assertDoesNotExist()
        compose.onNodeWithText(entry.name, useUnmergedTree = true).onParent().performScrollTo()
        screenshot("store-available")
        compose.assertPluginActionRow(
            entry.name,
            context.getString(R.string.plugin_marketplace_install),
            "${context.getString(R.string.more)}: ${entry.name}",
            entry.repositoryFullName,
            entry.description,
            metadata("0.1.11", 11),
        )
        val list = compose.onNode(hasScrollAction() and !hasSetTextAction())
        list.performScrollToIndex(0)
        list.performTouchInput { swipeDown(startY = height * 0.1f, endY = height * 0.9f) }
        compose.runOnIdle { assertEquals(true, refreshed) }
        compose.onNodeWithText(context.getString(R.string.plugin_marketplace_repository)).assertDoesNotExist()
        compose.onNodeWithContentDescription("${context.getString(R.string.more)}: ${entry.name}").performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.plugin_marketplace_repository)).performClick()
        compose.runOnIdle { assertEquals(entry.repository, openedUrl) }
        compose.onNodeWithText(context.getString(R.string.plugin_marketplace_install)).performClick()
        compose.runOnIdle { assertEquals(entry.id, installedId) }
    }

    @Test
    fun nativeSearchFiltersNamesAndDescriptionsAndRetainsTheQueryDuringRefresh() {
        show()
        compose.onNodeWithText(context.getString(R.string.plugin_marketplace_search)).performClick()
        val input = compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog()))
        input.performTextInput("知")
        compose.onNodeWithText(context.getString(R.string.plugin_marketplace_community_notice)).assertDoesNotExist()
        compose.onNodeWithText(entry.name).assertIsDisplayed()
        input.performTextReplacement("@anamaxlec")
        compose.onNodeWithText(entry.name).assertIsDisplayed()
        input.performTextReplacement("not-a-plugin")
        compose.onNodeWithText(context.getString(R.string.empty_search_results)).assertIsDisplayed()
        input.performTextReplacement("推荐")
        compose.onNodeWithText(entry.name).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.plugin_marketplace_install)).performClick()
        compose.runOnIdle { assertEquals(entry.id, installedId) }
        compose.waitForIdle()
        screenshot("store-search")
        compose
            .onNode(
                hasScrollAction() and !hasSetTextAction(),
            ).performTouchInput { swipeDown(startY = height * 0.1f, endY = height * 0.9f) }
        compose.runOnIdle {
            assertEquals(true, refreshed)
            state = state.copy(marketplace = state.marketplace.copy(plugins = listOf(entry.copy(description = "其他简介"))))
        }
        compose.onNodeWithText(context.getString(R.string.empty_search_results)).assertIsDisplayed()
        compose.onNodeWithText("推荐").assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.clear_search)).performClick()
        compose.onNodeWithText(context.getString(R.string.plugin_marketplace_community_notice)).assertIsDisplayed()
        compose.onNodeWithText(entry.name).assertIsDisplayed()
    }

    @Test
    fun localDraftsOfferManagementAndARefreshFailureKeepsTheCatalog() {
        state =
            state.copy(
                authoredPlugins = listOf(AuthoredPlugin(entry.id, entry.name)),
                marketplace = state.marketplace.copy(error = "offline"),
            )
        show()
        compose.onNodeWithText(context.getString(R.string.plugin_marketplace_install)).assertDoesNotExist()
        compose.onNodeWithText("anamaxlec/flare-zhihu-plugin").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.plugin_marketplace_local)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.plugin_marketplace_cached)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.plugin_retry)).performClick()
        compose.runOnIdle { assertEquals(true, refreshed) }
        compose.onNodeWithText(entry.name).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.plugin_marketplace_manage)).performClick()
        compose.runOnIdle { assertEquals(true, managed) }
    }

    @Test
    fun duplicateNamesShowDistinctRepositoryLinksAndEmptyCatalogHidesNotice() {
        val duplicate =
            entry.copy(
                id = "dev.alice.zhihu",
                description = "轻量只读版知乎时间线",
                repository = "https://github.com/alice/flare-plugins?path=/plugins/zhihu-lite",
                commitSha = "b".repeat(40),
            )
        state =
            state.copy(
                marketplace = PluginMarketplaceStateV1(plugins = listOf(entry, duplicate), loaded = true),
            )
        show()
        compose.onNodeWithText(context.getString(R.string.plugin_marketplace_community_notice)).assertIsDisplayed()
        compose.onNodeWithText("anamaxlec/flare-zhihu-plugin").assertIsDisplayed()
        compose.onNodeWithText("alice/flare-plugins/plugins/zhihu-lite").assertIsDisplayed().performFirstLinkClick()
        compose.runOnIdle {
            assertEquals("https://github.com/alice/flare-plugins/tree/${duplicate.commitSha}/plugins/zhihu-lite", openedUrl)
        }
        screenshot("store-duplicates")

        compose.runOnIdle {
            state = state.copy(marketplace = PluginMarketplaceStateV1(plugins = emptyList(), loaded = true))
        }
        compose.onNodeWithText(context.getString(R.string.plugin_marketplace_community_notice)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.plugin_marketplace_empty)).assertIsDisplayed()
    }

    @Test
    fun managedCatalogAndInstalledScreenShowTheirOwnVersionsAndDates() {
        val installed =
            PluginManagementItemV1(
                pluginId = entry.id,
                platformId = "zhihu",
                nameText = UiText.Raw(entry.name),
                iconPath = zhihuIcon,
                sourceUrl = entry.repository,
                version = "0.1.8",
                updatedAt = "2026-11-09",
                availableUpdate = GitHubPluginVersionV1("repository-bundle", "0.1.11", "a".repeat(40)),
            )
        state =
            state.copy(
                plugins =
                    listOf(
                        installed,
                        PluginManagementItemV1(
                            pluginId = "dev.dimension.flare.sample.pixelfed",
                            platformId = "pixelfed",
                            nameText = UiText.Raw("Pixelfed 示例插件"),
                            iconPath = fixtureIcon("icon.png"),
                            sourceUrl = null,
                            version = "0.1.0",
                            availableUpdate = null,
                        ),
                    ),
            )
        show()
        compose.onNodeWithText(metadata("0.1.11", 11)).assertIsDisplayed()
        compose.onNodeWithText(metadata("0.1.8", 9)).assertDoesNotExist()
        val more = "${context.getString(R.string.more)}: ${entry.name}"
        compose.assertPluginActionRow(entry.name, context.getString(R.string.plugin_marketplace_manage), more, entry.repositoryFullName)
        screenshot()
        compose.onNodeWithText(context.getString(R.string.plugin_marketplace_manage)).performClick()
        compose.onNodeWithText(metadata("0.1.8", 9)).assertIsDisplayed()
        compose.onNodeWithText(metadata("0.1.11", 11)).assertDoesNotExist()
        compose.onNodeWithText("repository-bundle", substring = true).assertDoesNotExist()
        compose.onNodeWithText("anamaxlec/flare-zhihu-plugin").performFirstLinkClick()
        compose.runOnIdle {
            assertEquals(entry.repository, openedUrl)
            openedUrl = null
        }
        compose.onNodeWithText(context.getString(R.string.plugin_local_import)).assertIsDisplayed()
        compose.onNodeWithText("v0.1.0").assertIsDisplayed()
        compose.assertPluginActionRow(
            entry.name,
            context.getString(R.string.plugin_update),
            more,
            entry.repositoryFullName,
            metadata("0.1.8", 9),
        )
        val localName = "Pixelfed 示例插件"
        val localRow =
            compose
                .onNodeWithText(localName, useUnmergedTree = true)
                .onParent()
                .fetchSemanticsNode()
                .boundsInRoot
        val localMore =
            compose
                .onNodeWithContentDescription(
                    "${context.getString(R.string.more)}: $localName",
                ).fetchSemanticsNode()
                .touchBoundsInRoot
        val zhihuMenu = compose.onNodeWithContentDescription(more).fetchSemanticsNode().touchBoundsInRoot
        assertEquals("Both menus must share a vertical centerline", zhihuMenu.center.x, localMore.center.x, 1f)
        assertEquals(localRow.center.y, localMore.center.y, 2f)
        screenshot("installed")
        compose.onNodeWithContentDescription("${context.getString(R.string.more)}: ${entry.name}").performClick()
        compose.onNodeWithText(context.getString(R.string.plugin_marketplace_repository)).performClick()
        compose.runOnIdle { assertEquals(entry.repository, openedUrl) }
        compose.onNodeWithText(context.getString(R.string.plugin_update)).performClick()
        compose.runOnIdle { assertEquals(entry.id, updatedId) }
    }

    @Test
    fun incompleteLegacyMetadataDoesNotLeaveSeparators() {
        val legacy =
            entry.copy(
                repository = "https://github.com/alice/flare-plugins?path=/plugins/zhihu-lite",
                updatedAt = "not-a-date",
                commitSha = null,
            )
        state = state.copy(marketplace = state.marketplace.copy(plugins = listOf(legacy)))
        show()
        compose.onNodeWithText("v0.1.11").assertIsDisplayed()
        compose.onNodeWithText(" · ", substring = true).assertDoesNotExist()
        compose.onNodeWithText("alice/flare-plugins/plugins/zhihu-lite").performFirstLinkClick()
        compose.runOnIdle { assertEquals("https://github.com/alice/flare-plugins/tree/HEAD/plugins/zhihu-lite", openedUrl) }
        compose.runOnIdle { state = state.copy(marketplace = state.marketplace.copy(plugins = listOf(entry.copy(version = null)))) }
        compose.onNodeWithText(metadata("0.1.11", 11).substringAfter(" · ")).assertIsDisplayed()
        compose.onNodeWithText(" · ", substring = true).assertDoesNotExist()
    }

    private fun metadata(
        version: String,
        day: Int,
    ): String {
        val format = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(context.resources.configuration.locales[0])
        return "v$version · ${context.getString(R.string.plugin_updated_at, LocalDate.of(2026, 11, day).format(format))}"
    }

    private fun fixtureIcon(asset: String): String {
        val file = File(context.cacheDir, asset.substringAfterLast('/'))
        InstrumentationRegistry.getInstrumentation().context.assets.open(asset).use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        return file.absolutePath
    }

    private fun show(management: Boolean = false) {
        var showingManagement by mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(
                LocalGlobalAppearance provides GlobalAppearance(),
                LocalTimelineAppearance provides TimelineAppearance(),
                LocalUriHandler provides
                    object : UriHandler {
                        override fun openUri(uri: String) {
                            openedUrl = uri
                        }
                    },
            ) {
                FlareTheme(darkTheme = false, dynamicColor = false) {
                    if (management) {
                        AndroidPluginManagementScreen(onBack = {}, onSettings = {}, onOpenMarketplace = { openedMarketplace = true })
                    } else if (showingManagement) {
                        InstalledPluginsScreen(
                            state = state,
                            checkingUpdates = false,
                            onBack = {},
                            onOpenMarketplace = { showingManagement = false },
                            onGitHubInstall = {},
                            onCreateWithAI = {},
                            onFileInstall = {},
                            onCheckUpdates = {},
                            onRebuildIndex = {},
                            onCleanup = {},
                            onSettings = {},
                            onExtensions = {},
                            onCancelPluginUpdate = {},
                            onUninstall = {},
                            onUpdate = { updatedId = it },
                            onRetryRuntime = {},
                        )
                    } else {
                        PluginMarketplaceScreen(
                            state = state,
                            onBack = {},
                            onInstall = { installedId = it },
                            onManage = {
                                managed = true
                                showingManagement = true
                            },
                            onRefresh = { refreshed = true },
                            onIcon = { if (it == entry.id) zhihuIcon else null },
                        )
                    }
                }
            }
        }
    }

    private fun screenshot(page: String = "store") {
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(context.externalCacheDir, "flare-marketplace-android-$page.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}

internal fun SemanticsNodeInteractionsProvider.assertPluginActionRow(
    name: String,
    primary: String,
    more: String,
    vararg information: String,
) {
    val row = onNodeWithText(name, useUnmergedTree = true).onParent().fetchSemanticsNode().boundsInRoot
    val button = onNodeWithText(primary).fetchSemanticsNode().touchBoundsInRoot
    val menu = onNodeWithContentDescription(more).fetchSemanticsNode().touchBoundsInRoot
    val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
    val rtl = resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
    val minimumTarget = 48 * resources.displayMetrics.density - 1
    val ordered = if (rtl) menu.right <= button.left + 1 else button.right <= menu.left + 1
    assertTrue("Action $button must precede menu $menu without overlapping", ordered)
    assertEquals("Action and menu must share a horizontal centerline", button.center.y, menu.center.y, 1f)
    assertEquals("Actions must be centered in the plugin row", row.center.y, button.center.y, 2f)
    for (control in listOf(button, menu)) {
        assertTrue("Actions must retain a 48 dp touch target", control.width >= minimumTarget && control.height >= minimumTarget)
    }
    for (label in listOf(name) + information) {
        val text = onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val fits = if (rtl) text.left >= maxOf(button.right, menu.right) else text.right <= minOf(button.left, menu.left)
        assertTrue("Text must fit before the actions: $label", text.width > 0 && fits)
    }
}
