package dev.dimension.flare.ui.screen.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import dev.dimension.flare.common.FileSystemUtilsExt
import dev.dimension.flare.data.datastore.model.AppSettings
import dev.dimension.flare.data.io.AppFileStore
import dev.dimension.flare.data.model.appearance.TimelineAppearance
import dev.dimension.flare.data.repository.AuthoredPlugin
import dev.dimension.flare.data.repository.PluginAuthoring
import dev.dimension.flare.data.repository.PluginAuthoringPhase
import dev.dimension.flare.data.repository.SettingsRepository
import dev.dimension.flare.data.translation.PluginTranslationService
import dev.dimension.flare.di.DesktopKoinApplication
import dev.dimension.flare.di.koinGet
import dev.dimension.flare.feature.plugin.PluginSubsystemV1
import dev.dimension.flare.feature.plugin.github.GitHubPluginRepositoryV1
import dev.dimension.flare.feature.plugin.github.GitHubPluginVersionV1
import dev.dimension.flare.feature.plugin.management.PluginManagementItemV1
import dev.dimension.flare.feature.plugin.management.PluginManagementPresenterV1
import dev.dimension.flare.feature.plugin.management.PluginManagementStateV1
import dev.dimension.flare.feature.plugin.marketplace.PluginMarketplace
import dev.dimension.flare.feature.plugin.marketplace.PluginMarketplaceCatalogV1
import dev.dimension.flare.feature.plugin.marketplace.PluginMarketplaceEntryV1
import dev.dimension.flare.feature.plugin.marketplace.PluginMarketplaceStateV1
import dev.dimension.flare.ui.component.LocalExternalTextResolver
import dev.dimension.flare.ui.component.LocalTimelineAppearance
import dev.dimension.flare.ui.model.UiText
import dev.dimension.flare.ui.presenter.invoke
import dev.dimension.flare.ui.presenter.settings.AiConfigPresenter
import dev.dimension.flare.ui.theme.FlareTheme
import dev.dimension.flare.ui.theme.LocalComposeWindow
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.stopKoin
import org.koin.plugin.module.dsl.startKoin
import java.awt.Color
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import javax.imageio.ImageIO

class DesktopPluginManagementScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var root: Path
    private lateinit var originalHome: String
    private lateinit var subsystem: PluginSubsystemV1
    private lateinit var scope: CoroutineScope
    private var visible by mutableStateOf(true)

    private fun screenshot(page: String) {
        ImageIO.write(
            compose.onRoot().captureToImage().toAwtImage(),
            "png",
            File(System.getProperty("java.io.tmpdir"), "flare-marketplace-desktop-$page.png"),
        )
    }

    private fun waitForIcon(
        name: String,
        path: String,
    ) {
        val original = ImageIO.read(File(path))
        val expected = Color(original.getRGB(original.width / 3, original.height / 3))
        val node = compose.onNodeWithContentDescription(name)
        compose.waitUntil(timeoutMillis = 5_000) {
            val image = node.captureToImage().toAwtImage()
            val actual = Color(image.getRGB(image.width / 3, image.height / 3))
            // This opaque part of each logo distinguishes Coil's decoded image from the initial empty frame.
            actual.green in expected.green - 8..expected.green + 8
        }
    }

    @Before
    fun setUp() {
        originalHome = System.getProperty("user.home")
        root = Files.createTempDirectory("flare-plugin-screen-test")
        System.setProperty("user.home", root.toString())
        assertEquals(root.resolve(".flare").toFile(), FileSystemUtilsExt.flareDirectory())
        val application = startKoin<DesktopKoinApplication>()
        scope = application.koin.get()
        subsystem = application.koin.get()
    }

    @After
    fun tearDown() {
        compose.runOnIdle { visible = false }
        compose.waitForIdle()
        if (::scope.isInitialized) runBlocking { scope.coroutineContext.job.cancelAndJoin() }
        if (::subsystem.isInitialized) {
            runBlocking { subsystem.runtimePool.close() }
            subsystem.github.close()
        }
        stopKoin()
        System.setProperty("user.home", originalHome)
        root.toFile().deleteRecursively()
    }

    @Test
    fun marketplaceLoadsTheSavedCatalogAndKeepsRepositoryLinksExternal() {
        val catalog = File("../docs/plugins/index.json").readText()
        val seed = PluginMarketplace(koinGet<AppFileStore>(), MockEngine { respond(catalog) })
        try {
            runBlocking { seed.load() }
        } finally {
            seed.close()
        }
        var openedUrl: String? = null
        var marketplace by mutableStateOf(false)
        compose.setContent {
            if (visible) {
                CompositionLocalProvider(
                    LocalUriHandler provides
                        object : UriHandler {
                            override fun openUri(uri: String) {
                                openedUrl = uri
                            }
                        },
                    LocalComposeWindow provides null,
                    LocalTimelineAppearance provides TimelineAppearance(),
                ) {
                    FlareTheme(isDarkTheme = false) {
                        Box(Modifier.width(620.dp).height(660.dp)) {
                            key(marketplace) {
                                DesktopPluginManagementScreen(
                                    onBack = { marketplace = false },
                                    onOpenMarketplace = { marketplace = true },
                                    marketplace = marketplace,
                                )
                            }
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("No plugins installed").assertIsDisplayed()
        compose.onNodeWithText("Installed").assertDoesNotExist()
        screenshot("management")
        compose.onNodeWithContentDescription("Marketplace").assertIsDisplayed().performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(hasText("知乎")).fetchSemanticsNodes().isNotEmpty()
        }
        val install =
            compose
                .onNodeWithText("Install")
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        val menu = compose.onNodeWithContentDescription("More: 知乎").fetchSemanticsNode().boundsInRoot
        assertTrue(install.right <= menu.left)
        assertEquals(install.center.y, menu.center.y, 1f)
        compose.onNodeWithText("anamaxlec/flare-zhihu-plugin").assertIsDisplayed().performFirstLinkClick()
        compose.runOnIdle {
            assertEquals("https://github.com/anamaxlec/flare-zhihu-plugin", openedUrl)
            openedUrl = null
        }
        compose.onNodeWithText("View repository").assertDoesNotExist()
        compose.onNodeWithContentDescription("More: 知乎").performClick()
        compose.onNodeWithText("View repository").performClick()
        compose.runOnIdle { assertEquals("https://github.com/anamaxlec/flare-zhihu-plugin", openedUrl) }
        compose.onNodeWithContentDescription("Refresh").assertIsDisplayed()
        compose.onNodeWithContentDescription("More").assertDoesNotExist()
        screenshot("store")
        val search = compose.onNode(hasSetTextAction())
        search.performTextInput("知")
        compose.onNodeWithText("知乎").assertIsDisplayed()
        search.performTextReplacement("not-a-plugin")
        compose.onNodeWithText("No search results").assertIsDisplayed()
        compose.onNodeWithText("知乎").assertDoesNotExist()
        search.performTextReplacement("推荐")
        compose.onNodeWithText("知乎").assertIsDisplayed()
        screenshot("store-search")
        search.performTextClearance()
        compose.onNodeWithText("知乎").assertIsDisplayed()
        compose.onNodeWithText("Plugins (Beta)").assertDoesNotExist()
        compose.onNodeWithText("Marketplace").assertIsDisplayed()
        compose.onNodeWithContentDescription("Go back").performClick()
        compose.onNodeWithText("No plugins installed").assertIsDisplayed()
        compose.onNodeWithContentDescription("Marketplace").assertIsDisplayed()
    }

    @Test
    fun storeAndInstalledShowTheirOwnReleaseAndLocalProvenance() {
        val commit = "90887cc6ebd88415d3b1b75037bee489fade7fcd"
        val latest =
            PluginMarketplaceEntryV1(
                id = "dev.maxlec.zhihu",
                name = "知乎",
                description = "知乎热榜、推荐、关注动态与回答/文章详情",
                repository = "https://github.com/anamaxlec/flare-zhihu-plugin",
                icon = "https://raw.githubusercontent.com/anamaxlec/flare-zhihu-plugin/$commit/assets/icon.png",
                version = "0.1.11",
                updatedAt = "2026-11-11",
                commitSha = commit,
            )
        val icon = File("../feature/plugin/test-fixtures/marketplace/zhihu.png").readBytes()
        val catalog = Json.encodeToString(PluginMarketplaceCatalogV1(1, listOf(latest)))
        val market =
            PluginMarketplace(
                koinGet<AppFileStore>(),
                MockEngine { request ->
                    if (request.url.toString() == latest.icon) respond(icon) else respond(catalog)
                },
            )
        val presenter = PluginManagementPresenterV1(subsystem, scope, marketplace = market)
        val oldLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
            presenter.loadMarketplace()
            val state = runBlocking { withTimeout(5_000) { presenter.state.first { it.marketplace.loaded } } }
            val installed =
                PluginManagementItemV1(
                    pluginId = latest.id,
                    platformId = "Zhihu",
                    nameText = UiText.Raw(latest.name),
                    version = "0.1.8",
                    updatedAt = "2026-11-09",
                    sourceUrl = latest.repository,
                    sourceCommitSha = "557b6195db413fecc65468195c6646c668b64d64",
                    availableUpdate = GitHubPluginVersionV1("v0.1.11", "0.1.11", commit),
                    iconPath = runBlocking { requireNotNull(presenter.marketplaceIcon(latest.id)) },
                )
            val local =
                PluginManagementItemV1(
                    pluginId = "dev.dimension.flare.sample.pixelfed",
                    platformId = "Pixelfed",
                    nameText = UiText.Raw("Pixelfed 示例插件"),
                    version = "0.1.0",
                    sourceUrl = null,
                    availableUpdate = null,
                    iconPath = File("../feature/plugin/sdk/skill/flare-plugin/examples/pixelfed/assets/icon.png").absolutePath,
                )
            val screenState = state.copy(plugins = listOf(installed, local))
            var marketplace by mutableStateOf(true)
            compose.setContent {
                if (visible) {
                    CompositionLocalProvider(
                        LocalComposeWindow provides null,
                        LocalTimelineAppearance provides TimelineAppearance.Default,
                    ) {
                        FlareTheme(isDarkTheme = false) {
                            Box(Modifier.width(660.dp).height(434.dp)) {
                                DesktopPluginManagementContent(
                                    screenState,
                                    presenter,
                                    subsystem,
                                    onBack = { marketplace = false },
                                    onCreateWithAI = {},
                                    onOpenMarketplace = { marketplace = true },
                                    marketplace = marketplace,
                                )
                            }
                        }
                    }
                }
            }
            compose.onNodeWithText(latest.repositoryFullName).assertIsDisplayed()
            val release = compose.onNodeWithText("v0.1.11 · 更新于 2026年11月11日").assertIsDisplayed()
            val title = compose.onNodeWithText(latest.name).fetchSemanticsNode().boundsInRoot
            val manage = compose.onNodeWithText("管理").fetchSemanticsNode().boundsInRoot
            val storeMore = compose.onNodeWithContentDescription("更多: 知乎").fetchSemanticsNode().boundsInRoot
            assertTrue("Manage must precede More", manage.right <= storeMore.left)
            assertEquals(manage.center.y, storeMore.center.y, 1f)
            val releaseBounds = release.fetchSemanticsNode().boundsInRoot
            assertTrue(releaseBounds.right < manage.left)
            assertEquals((title.top + releaseBounds.bottom) / 2, storeMore.center.y, 1f)
            compose.onNodeWithText("v0.1.8", substring = true).assertDoesNotExist()
            waitForIcon(latest.name, installed.iconPath)
            screenshot("catalog-release")
            compose.onNodeWithText("管理").performClick()
            compose.onRoot().performMouseInput { moveTo(bottomCenter) }
            compose.onNodeWithText("更新").assertIsDisplayed()
            compose.onNodeWithText("v0.1.8 · 更新于 2026年11月9日").assertIsDisplayed()
            compose.onNodeWithText("v0.1.11", substring = true).assertDoesNotExist()
            compose.onNodeWithText("本地导入").assertIsDisplayed()
            compose.onNodeWithText("v0.1.0").assertIsDisplayed()
            val update = compose.onNodeWithText("更新").fetchSemanticsNode().boundsInRoot
            val remoteMore = compose.onNodeWithContentDescription("更多: 知乎").fetchSemanticsNode().boundsInRoot
            val localMore = compose.onNodeWithContentDescription("更多: Pixelfed 示例插件").fetchSemanticsNode().boundsInRoot
            assertTrue("Update must precede More", update.right <= remoteMore.left)
            assertEquals(update.center.y, remoteMore.center.y, 1f)
            assertEquals(remoteMore.right, localMore.right, 1f)
            waitForIcon(latest.name, installed.iconPath)
            waitForIcon("Pixelfed 示例插件", local.iconPath)
            screenshot("installed-release")
        } finally {
            compose.runOnIdle { visible = false }
            presenter.close()
            market.close()
            Locale.setDefault(oldLocale)
        }
    }

    @Test
    fun marketplaceDisambiguatesDuplicateNamesAndWrapsLongTitlesOnNarrowWindow() {
        val notice =
            "Plugins in the marketplace are created by third-party community developers and are not official. Review the source repository before installing."
        val first =
            PluginMarketplaceEntryV1(
                id = "dev.maxlec.zhihu",
                name = "知乎",
                description = "知乎热榜、推荐、关注动态与回答/文章详情",
                repository = "https://github.com/anamaxlec/flare-zhihu-plugin",
                icon = "",
            )
        val second =
            first.copy(
                id = "dev.alice.zhihu",
                description = "轻量只读版知乎时间线",
                repository = "https://github.com/alice/flare-plugins?path=/plugins/zhihu-lite",
            )
        val longNamed =
            first.copy(
                id = "dev.bob.long",
                name = "Super Long Community Plugin Title That Wraps Cleanly On Narrow Screens",
                repository = "https://github.com/bob-developer/super-long-plugin",
            )
        var query by mutableStateOf("")
        var state by mutableStateOf(
            PluginManagementStateV1(
                authoredPlugins = listOf(AuthoredPlugin(first.id, first.name)),
                marketplace =
                    PluginMarketplaceStateV1(
                        plugins = listOf(first, second, longNamed),
                        loaded = true,
                    ),
            ),
        )
        compose.setContent {
            if (visible) {
                FlareTheme(isDarkTheme = false) {
                    Box(Modifier.width(448.dp).height(700.dp)) {
                        PluginMarketplaceList(
                            state = state,
                            onInstall = {},
                            onManage = {},
                            onRefresh = {},
                            onIcon = { null },
                            query = query,
                        )
                    }
                }
            }
        }
        compose.onNodeWithText(notice).assertIsDisplayed()
        compose.onNodeWithText("anamaxlec/flare-zhihu-plugin").assertIsDisplayed()
        compose.onNodeWithText("alice/flare-plugins/plugins/zhihu-lite").assertIsDisplayed()
        compose.onNodeWithText("bob-developer/super-long-plugin").assertIsDisplayed()
        compose.onNodeWithText("Manage").assertIsDisplayed()
        compose.onNodeWithText("Local plugin").assertIsDisplayed()
        compose.onNodeWithContentDescription("More: ${longNamed.name}").assertIsDisplayed()
        screenshot("store-duplicates")

        compose.runOnIdle { query = "@alice" }
        compose.onNodeWithText(notice).assertDoesNotExist()
        compose.onNodeWithText("alice/flare-plugins/plugins/zhihu-lite").assertIsDisplayed()
        compose.onNodeWithText("anamaxlec/flare-zhihu-plugin").assertDoesNotExist()

        compose.runOnIdle {
            query = ""
            state =
                state.copy(
                    marketplace =
                        PluginMarketplaceStateV1(
                            plugins = emptyList(),
                            loaded = true,
                        ),
                )
        }
        compose.onNodeWithText(notice).assertDoesNotExist()
        compose.onNodeWithText("No plugins available yet").assertIsDisplayed()
    }

    @Test
    fun allPluginSourcesRemainTogetherAfterOperationNotices() {
        val authored = AuthoredPlugin("local.ai.example", "AI plugin", phase = PluginAuthoringPhase.Validating)
        val github =
            PluginManagementItemV1(
                pluginId = "github.plugin",
                platformId = "GitHub",
                nameText = UiText.Raw("GitHub plugin"),
                version = "1.0.0",
                iconPath = "",
                sourceUrl = "https://github.com/example/plugin",
                availableUpdate = GitHubPluginVersionV1("v1.1.0", "1.1.0", "b".repeat(40)),
            )
        val state =
            PluginManagementStateV1(
                authoredPlugins = listOf(authored),
                plugins =
                    listOf(
                        github.copy(pluginId = authored.id, nameText = UiText.Raw(authored.name), sourceUrl = null, availableUpdate = null),
                        github,
                        github.copy(
                            pluginId = "file.plugin",
                            platformId = "File",
                            nameText = UiText.Raw("File plugin"),
                            sourceUrl = null,
                            availableUpdate = null,
                        ),
                    ),
                error = "Import failed",
                development =
                    dev.dimension.flare.feature.plugin.development.PluginDevelopmentStateV1(
                        active = true,
                        pluginId = authored.id,
                    ),
            )
        var updated: String? = null
        var cancelled: String? = null
        compose.setContent {
            FlareTheme(isDarkTheme = false) {
                PluginManagementList(
                    state = state,
                    onCancelPluginUpdate = { cancelled = it },
                    onUninstall = {},
                    onUpdate = { updated = it },
                    onRetryRuntime = {},
                    modifier = Modifier.width(520.dp).height(800.dp),
                )
            }
        }
        val error = compose.onNodeWithText("The plugin operation failed. Try again or restart Flare.: Import failed")
        val authoredRow =
            compose
                .onNodeWithText("AI plugin")
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        val githubRow =
            compose
                .onNodeWithText("GitHub plugin")
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        val fileRow =
            compose
                .onNodeWithText("File plugin")
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        assertTrue(error.fetchSemanticsNode().boundsInRoot.bottom < authoredRow.top)
        assertTrue(authoredRow.bottom < githubRow.top)
        assertTrue(githubRow.bottom < fileRow.top)
        assertEquals(authoredRow.left, githubRow.left)
        assertEquals(githubRow.left, fileRow.left)
        val localMenu = compose.onNodeWithContentDescription("More: File plugin").fetchSemanticsNode().boundsInRoot
        listOf("Cancel update" to "More: AI plugin", "Update" to "More: GitHub plugin").forEach { (label, menuDescription) ->
            val action = compose.onNodeWithText(label).fetchSemanticsNode().boundsInRoot
            val menu = compose.onNodeWithContentDescription(menuDescription).fetchSemanticsNode().boundsInRoot
            assertTrue(action.right <= menu.left)
            assertEquals(action.center.y, menu.center.y, 1f)
            assertEquals(localMenu.right, menu.right, 1f)
        }
        compose.onNodeWithText(authored.id).assertDoesNotExist()
        compose.onNodeWithText("Local AI-generated plugin · Revision 0").assertIsDisplayed()
        compose.onNodeWithText("example/plugin").assertIsDisplayed()
        compose.onNodeWithText("Local import").assertIsDisplayed()
        assertEquals(3, compose.onAllNodes(hasText("v1.0.0")).fetchSemanticsNodes().size)
        compose.onNodeWithText("Update").assertIsDisplayed().performClick()
        assertEquals(github.pluginId, updated)
        compose.onNodeWithText("Cancel update").assertIsDisplayed().performClick()
        assertEquals(authored.id, cancelled)
        compose.onNodeWithText("file.plugin").assertDoesNotExist()
        compose.onNodeWithContentDescription("More: File plugin").performClick()
        compose.onNodeWithText("file.plugin").assertIsDisplayed().assertIsNotEnabled()
    }

    @Test
    fun createWithAiOpensTheGeneralAssistantWithoutCreatingAPlugin() {
        var opens = 0
        compose.setContent {
            if (visible) {
                CompositionLocalProvider(
                    LocalComposeWindow provides null,
                    LocalTimelineAppearance provides TimelineAppearance(aiConfig = TimelineAppearance.AiConfig(agent = true)),
                ) {
                    FlareTheme(isDarkTheme = false) {
                        Box(Modifier.width(448.dp).height(800.dp)) {
                            DesktopPluginManagementScreen(onBack = {}, onCreateWithAI = { opens++ })
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("Create with Flare AI").assertDoesNotExist()
        compose.onNodeWithContentDescription("Add plugin").performClick()
        compose.onNodeWithText("Create with Flare AI").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Add plugin").performClick()
        compose.onNodeWithText("Create with Flare AI").performClick()
        compose.runOnIdle {
            assertEquals(2, opens)
            assertEquals(emptyList<dev.dimension.flare.data.repository.AuthoredPlugin>(), koinGet<PluginAuthoring>().plugins.value)
        }
    }

    @Test
    fun generatedPluginAndAddMenuScreenshots() {
        var openedUrl: String? = null
        val uriHandler =
            object : UriHandler {
                override fun openUri(uri: String) {
                    openedUrl = uri
                }
            }
        runBlocking { createScreenshotPlugin(koinGet<PluginAuthoring>()) }
        compose.setContent {
            if (visible) {
                CompositionLocalProvider(
                    LocalUriHandler provides uriHandler,
                    LocalComposeWindow provides null,
                    LocalTimelineAppearance provides TimelineAppearance(aiConfig = TimelineAppearance.AiConfig(agent = true)),
                ) {
                    FlareTheme(isDarkTheme = false) {
                        Box(Modifier.width(520.dp).height(660.dp)) {
                            DesktopPluginManagementScreen(onBack = {})
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("Pixelfed").assertIsDisplayed()
        compose.onNodeWithText("Enabled").assertDoesNotExist()
        compose
            .onNodeWithText(
                "Plugins are still in beta and may be incomplete. If you run into problems, report them on GitHub Issues.",
            ).performFirstLinkClick()
        compose.runOnIdle { assertEquals("https://github.com/flaresocialapp/Flare/issues", openedUrl) }
        compose.onNodeWithText("GitHub Issues").assertDoesNotExist()
        val addBounds = compose.onNodeWithContentDescription("Add plugin").fetchSemanticsNode().boundsInRoot
        val moreBounds = compose.onNodeWithContentDescription("More").fetchSemanticsNode().boundsInRoot
        assertTrue("Add plugin must precede More", addBounds.right <= moreBounds.left)
        ImageIO.write(
            compose.onRoot().captureToImage().toAwtImage(),
            "png",
            File(System.getProperty("java.io.tmpdir"), "flare-plugins-desktop.png"),
        )
        compose.onNodeWithContentDescription("More: Pixelfed").performClick()
        compose.onNodeWithText("Uninstall").assertIsDisplayed()
        ImageIO.write(
            compose.onNode(isPopup()).captureToImage().toAwtImage(),
            "png",
            File(System.getProperty("java.io.tmpdir"), "flare-plugins-desktop-item-menu.png"),
        )
        compose.onNodeWithText("Uninstall").performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithContentDescription("Add plugin").performClick()
        compose.onNodeWithText("Create with Flare AI").assertIsDisplayed()
        ImageIO.write(
            compose.onNode(isPopup()).captureToImage().toAwtImage(),
            "png",
            File(System.getProperty("java.io.tmpdir"), "flare-plugins-desktop-menu.png"),
        )
    }

    @Test
    fun installedTranslationPluginCanBeSelectedFromSettings() {
        val name = "Test translator"
        val pluginId =
            runBlocking {
                val authoring = koinGet<PluginAuthoring>()
                val draft = authoring.create("translation-provider-ui-fixture")
                authoring.write(
                    draft.id,
                    draft.revision,
                    mapOf(
                        "manifest.json" to
                            """
                            {
                              "apiVersion": 2,
                              "id": "${draft.id}",
                              "version": "1.0.0",
                              "name": "$name",
                              "translation": {}
                            }
                            """.trimIndent(),
                        "plugin.js" to
                            """
                            defineFlareTranslatePlugin({
                              async translateDocumentJson() { return null; },
                              async translateBatchDocumentJson() { return null; }
                            });
                            """.trimIndent(),
                    ),
                )
                withTimeout(10_000) {
                    koinGet<PluginTranslationService>().providers.first { providers ->
                        providers.any { it.pluginId == draft.id }
                    }
                }
                draft.id
            }

        compose.setContent {
            if (visible) {
                FlareTheme(isDarkTheme = false) {
                    Box(Modifier.width(440.dp).height(420.dp)) {
                        TranslationProviderSelector(remember { AiConfigPresenter() }.invoke())
                    }
                }
            }
        }
        compose.onNodeWithText("Google Translate (Web)").performClick()
        compose
            .onNodeWithText(name)
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        compose.onNode(isPopup()).assertDoesNotExist()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(hasText(name)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(name).assertIsDisplayed()
        val settings = runBlocking { koinGet<SettingsRepository>().appSettings.first() }
        assertEquals(AppSettings.TranslateConfig.Provider.Plugin(pluginId), settings.translateConfig.provider)
    }

    @Test
    fun developmentValidatesWithoutReloadingUntilTheUserClicks() {
        var state by mutableStateOf(
            dev.dimension.flare.feature.plugin.development.PluginDevelopmentStateV1(
                directory = "/tmp/plugin",
                active = true,
                pluginId = "fixture.plugin",
                candidateHash = "new",
                runningHash = "old",
            ),
        )
        var reloads = 0
        compose.setContent {
            FlareTheme(isDarkTheme = false) {
                PluginDevelopmentPanel(state, onOpen = {}, onReload = { reloads++ }, onCancel = {}, onStop = {})
            }
        }
        compose.onNodeWithText("Validated · ready to load").assertIsDisplayed()
        assertEquals(0, reloads)
        compose.onNodeWithText("Reload plugin").performClick()
        assertEquals(1, reloads)
        compose.runOnIdle { state = state.copy(validating = true, candidateHash = null) }
        compose.onNodeWithText("Reload plugin").assertIsNotEnabled()
        compose.onNodeWithText("Choose folder…").assertIsNotEnabled()
    }

    @Test
    fun installReviewDisclosesUnrestrictedMediaNetworking() {
        compose.setContent {
            if (visible) {
                FlareTheme(isDarkTheme = false) {
                    PluginInstallReviewDialog(
                        review =
                            dev.dimension.flare.feature.plugin.management.PluginInstallReviewV1(
                                pluginId = "fixture.plugin",
                                platformId = "Fixture",
                                name = "Fixture",
                                version = "1.0.0",
                                capabilities = emptyList(),
                                warnings = emptyList(),
                                sourceUrl = null,
                                permissions =
                                    listOf(
                                        dev.dimension.flare.data.model.PluginPermission(
                                            dev.dimension.flare.data.model.PluginPermissionType.MediaNetwork,
                                            "*",
                                        ),
                                    ),
                            ),
                        busy = false,
                        onConfirm = {},
                        onCancel = {},
                    )
                }
            }
        }
        compose.onNodeWithText("• Load media from any website, which may receive this plugin’s account data").assertIsDisplayed()
    }

    @Test
    fun localizedPluginNameAndActionsRemainVisibleWithLongRepositoryUrl() {
        val repository = GitHubPluginRepositoryV1(1, "Tlaster", "flare-plugin-github-e2e-20261028")
        val path = "plugins/long-plugin-subdirectory-with-a-distinct-name"
        val installedCommit = "a".repeat(40)
        var openedUrl: String? = null
        val plugin =
            PluginManagementItemV1(
                pluginId = "fixture.plugin",
                platformId = "Fixture",
                nameText = UiText.ExternalRef("fixture.plugin", "name", "Fixture plugin"),
                version = "1.0.0",
                iconPath = root.resolve("missing-icon.png").toString(),
                sourceUrl = "${repository.url}?path=/$path",
                sourceCommitSha = installedCommit,
                availableUpdate = GitHubPluginVersionV1("v1.1.0", "1.1.0", "b".repeat(40)),
            )
        var removed: String? = null
        var updated: String? = null
        compose.setContent {
            if (visible) {
                FlareTheme(isDarkTheme = false) {
                    CompositionLocalProvider(
                        LocalExternalTextResolver provides { "Localized plugin" },
                        LocalUriHandler provides
                            object : UriHandler {
                                override fun openUri(uri: String) {
                                    openedUrl = uri
                                }
                            },
                    ) {
                        Box(Modifier.width(448.dp).clipToBounds()) {
                            PluginItem(
                                plugin = plugin,
                                busy = false,
                                onCancelUpdate = {},
                                onUninstall = { removed = it },
                                onUpdate = { updated = it },
                            )
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("Localized plugin").assertIsDisplayed()
        compose.onNodeWithContentDescription("Localized plugin").assertExists()
        val source = compose.onNodeWithText("Tlaster/${repository.name}/$path")
        val layouts = mutableListOf<TextLayoutResult>()
        source.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.single().lineCount > 1)
        assertTrue(!layouts.single().hasVisualOverflow)
        val updateBounds = compose.onNodeWithText("Update").fetchSemanticsNode().boundsInRoot
        val moreBounds = compose.onNodeWithContentDescription("More: Localized plugin").fetchSemanticsNode().boundsInRoot
        assertTrue("The repository must end before Update", source.fetchSemanticsNode().boundsInRoot.right < updateBounds.left)
        assertTrue(updateBounds.right <= moreBounds.left)
        assertEquals(updateBounds.center.y, moreBounds.center.y, 1f)
        val row = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertEquals(row.center.y, moreBounds.center.y, 1f)
        source.assertIsDisplayed().performFirstLinkClick()
        assertEquals("${repository.url}/tree/$installedCommit/$path", openedUrl)
        val updateButton = compose.onNodeWithText("Update").assertIsDisplayed()
        updateButton.performSemanticsAction(SemanticsActions.RequestFocus)
        updateButton.performKeyInput { pressKey(Key.Enter) }
        assertEquals(plugin.pluginId, updated)
        compose.onNodeWithText("Uninstall").assertDoesNotExist()
        compose.onNodeWithContentDescription("More: Localized plugin").assertIsDisplayed().performClick()
        compose.onNodeWithText("Uninstall").assertIsDisplayed().performClick()
        assertEquals(plugin.pluginId, removed)
    }

    @Test
    fun narrowWindowGroupsInstallSourcesAndHidesAiWhenDisabled() {
        compose.setContent {
            if (visible) {
                CompositionLocalProvider(LocalComposeWindow provides null, LocalTimelineAppearance provides TimelineAppearance.Default) {
                    FlareTheme(isDarkTheme = false) {
                        // A 520 dp app window leaves 448 dp after its navigation rail.
                        Box(Modifier.width(448.dp).height(800.dp)) {
                            DesktopPluginManagementScreen(onBack = {})
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("From file…").assertDoesNotExist()
        compose.onNodeWithText("Install from GitHub").assertDoesNotExist()
        compose.onNodeWithContentDescription("Add plugin").performClick()
        compose.onNodeWithText("From file…").assertIsDisplayed()
        compose.onNodeWithText("Create with Flare AI").assertDoesNotExist()
        compose.onNodeWithText("Install from GitHub").assertIsDisplayed().performClick()
        compose.onNodeWithText("GitHub repository URL").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("GitHub repository URL").assertDoesNotExist()
    }

    @Test
    fun installReviewDisclosesTimelinePostAccessPermission() {
        compose.setContent {
            if (visible) {
                FlareTheme(isDarkTheme = false) {
                    PluginInstallReviewDialog(
                        review =
                            dev.dimension.flare.feature.plugin.management.PluginInstallReviewV1(
                                pluginId = "fixture.timeline",
                                platformId = "",
                                name = "Timeline Enricher",
                                version = "1.0.0",
                                capabilities = listOf("flare.timeline-processor/v1: processBatch"),
                                warnings = emptyList(),
                                sourceUrl = null,
                                permissions =
                                    listOf(
                                        dev.dimension.flare.data.model.PluginPermission(
                                            dev.dimension.flare.data.model.PluginPermissionType.TimelinePostAccess,
                                            "*",
                                        ),
                                    ),
                            ),
                        busy = false,
                        onConfirm = {},
                        onCancel = {},
                    )
                }
            }
        }
        compose
            .onNodeWithText(
                "• Read displayed timeline posts to attach widgets, which may send post content to permitted network origins",
            ).assertIsDisplayed()
    }

    @Test
    fun timelineProcessorToggleReflectsStateAndUpdatesPresenter() {
        val pluginId =
            runBlocking {
                val authoring = koinGet<PluginAuthoring>()
                val draft = authoring.create("timeline-processor-ui-fixture")
                authoring.write(
                    draft.id,
                    draft.revision,
                    mapOf(
                        "manifest.json" to
                            """
                            {
                              "apiVersion": 2,
                              "id": "${draft.id}",
                              "version": "1.0.0",
                              "name": "Score Widgets",
                              "permissions": {
                                "httpOrigins": ["https://api.example.com"]
                              },
                              "timelineProcessor": {
                                "apiVersion": 1
                              }
                            }
                            """.trimIndent(),
                        "plugin.js" to
                            """
                            defineFlareTimelineProcessorPlugin({
                              async processBatch() { return { results: [] }; }
                            });
                            """.trimIndent(),
                    ),
                )
                withTimeout(10_000) {
                    subsystem.running.first { running -> draft.id in running.plugins }
                }
                draft.id
            }

        compose.setContent {
            if (visible) {
                CompositionLocalProvider(
                    LocalComposeWindow provides null,
                    LocalTimelineAppearance provides TimelineAppearance.Default,
                ) {
                    FlareTheme(isDarkTheme = false) {
                        Box(Modifier.width(520.dp).height(660.dp)) {
                            DesktopPluginManagementScreen(onBack = {})
                        }
                    }
                }
            }
        }

        compose.onNodeWithText("Score Widgets").assertIsDisplayed()
        // AI-authored timeline processors start disabled until explicitly toggled on.
        val toggleNode = compose.onNodeWithText("Timeline post widgets")
        toggleNode.assertIsDisplayed().assertIsOff()

        toggleNode.performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            runBlocking {
                koinGet<SettingsRepository>().appSettings.first().enabledTimelineProcessorPlugins[pluginId] ==
                    setOf("https://api.example.com")
            }
        }
        toggleNode.assertIsOn()

        toggleNode.performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            runBlocking {
                pluginId !in koinGet<SettingsRepository>().appSettings.first().enabledTimelineProcessorPlugins
            }
        }
        toggleNode.assertIsOff()
    }
}

private suspend fun createScreenshotPlugin(authoring: PluginAuthoring) {
    val plugin = authoring.create("plugin-management-ui-fixture")
    val current = authoring.read(plugin.id)
    authoring.write(
        plugin.id,
        current.plugin.revision,
        mapOf(
            "manifest.json" to """{
            "schemaVersion":1,"apiVersion":1,"id":"${plugin.id}","version":"1.0.0",
            "defaultLocale":"en","name":"Pixelfed",
            "platform":{"id":"${plugin.platformId}","name":"Pixelfed","detector":{"priority":10},
                "loginMethods":[{"id":"oauth","interaction":"OAuth","title":"OAuth"}],
                "capabilities":{"flare.datasource.timeline/v1":{"operations":{"page":{"directions":["refresh","older"]}}}},
                "timelines":[{"id":"home","title":"Home","defaultForNewAccount":true}]}
        }""",
            "plugin.js" to """defineFlareSocialPlugin({
            detector:{async detect(){return {matched:true};}},
            login:{oauth:{async begin(){return {type:"Pending"};},async resume(){return {type:"Pending"};}}},
            capabilities:{timeline:{async page(){return {items:[]};}}}
        });""",
        ),
    )
    withTimeout(10_000) {
        authoring.plugins.first { plugins ->
            plugins.any { it.id == plugin.id && it.phase == PluginAuthoringPhase.Applied }
        }
    }
}
