package dev.dimension.flare.ui.screen.settings

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ComposeUiFlags
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onParent
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.dimension.flare.R
import dev.dimension.flare.data.model.appearance.GlobalAppearance
import dev.dimension.flare.data.model.appearance.TimelineAppearance
import dev.dimension.flare.data.repository.AuthoredPlugin
import dev.dimension.flare.data.repository.PluginAuthoring
import dev.dimension.flare.data.repository.PluginAuthoringPhase
import dev.dimension.flare.di.koinGet
import dev.dimension.flare.feature.plugin.github.GitHubPluginVersionV1
import dev.dimension.flare.feature.plugin.management.PluginManagementItemV1
import dev.dimension.flare.feature.plugin.management.PluginManagementStateV1
import dev.dimension.flare.ui.component.LocalGlobalAppearance
import dev.dimension.flare.ui.component.LocalTimelineAppearance
import dev.dimension.flare.ui.model.UiText
import dev.dimension.flare.ui.theme.FlareTheme
import dev.dimension.flare.ui.theme.screenHorizontalPadding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Locale

@OptIn(ExperimentalComposeUiApi::class)
class PluginAuthoringCardTest {
    @get:Rule val compose = createComposeRule()
    private val mediaQueryIntegration = ComposeUiFlags.isMediaQueryIntegrationEnabled
    private var screenshotPluginId: String? = null

    @Before fun setUp() {
        ComposeUiFlags.isMediaQueryIntegrationEnabled = true
    }

    @After fun tearDown() {
        try {
            screenshotPluginId?.let { id -> runBlocking { koinGet<PluginAuthoring>().delete(id) } }
        } finally {
            ComposeUiFlags.isMediaQueryIntegrationEnabled = mediaQueryIntegration
        }
    }

    @Test
    fun allPluginSourcesShareOneGroupWithUpdatesOnTheMatchingItem() {
        val authored = AuthoredPlugin("local.ai.example", "AI plugin")
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
            )
        var updated: String? = null
        compose.setContent {
            MaterialTheme {
                PluginManagementList(
                    state = state,
                    onSettings = {},
                    onCancelPluginUpdate = {},
                    onUninstall = {},
                    onUpdate = { updated = it },
                    onRetryRuntime = {},
                    modifier = Modifier.width(380.dp).height(800.dp),
                )
            }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val error = compose.onNodeWithText("${context.getString(R.string.plugin_operation_failed)}: Import failed")
        val authoredRow = compose.onNodeWithText("AI plugin", useUnmergedTree = true).onParent()
        val githubRow = compose.onNodeWithText("GitHub plugin", useUnmergedTree = true).onParent()
        val fileRow = compose.onNodeWithText("File plugin", useUnmergedTree = true).onParent()
        assertTrue(error.fetchSemanticsNode().boundsInRoot.bottom <= authoredRow.fetchSemanticsNode().boundsInRoot.top)
        val inset = with(compose.density) { 4.dp.roundToPx() }
        val first = authoredRow.captureToImage().toPixelMap()
        val middle = githubRow.captureToImage().toPixelMap()
        val last = fileRow.captureToImage().toPixelMap()
        assertNotEquals(first[inset, first.height / 2], first[inset, inset])
        assertEquals(first[inset, first.height / 2], first[inset, first.height - inset])
        assertEquals(middle[inset, middle.height / 2], middle[inset, inset])
        assertEquals(middle[inset, middle.height / 2], middle[inset, middle.height - inset])
        assertEquals(last[inset, last.height / 2], last[inset, inset])
        assertNotEquals(last[inset, last.height / 2], last[inset, last.height - inset])
        val names = listOf("AI plugin", "GitHub plugin", "File plugin")
        val titleStarts =
            names.map {
                compose
                    .onNodeWithText(it, useUnmergedTree = true)
                    .fetchSemanticsNode()
                    .boundsInRoot.left
            }
        assertEquals(1, titleStarts.distinct().size)
        compose.onNodeWithText(authored.id).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.plugin_ai_source_revision, authored.revision)).assertIsDisplayed()
        compose.onNodeWithText("example/plugin").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.plugin_local_import)).assertIsDisplayed()
        compose.onAllNodesWithText("v1.0.0").assertCountEquals(3)
        compose.onNodeWithText(context.getString(R.string.plugin_update)).assertIsDisplayed().performClick()
        assertEquals(github.pluginId, updated)
        screenshot("flare-plugins-grouped-android.png")
        compose.onNodeWithText("file.plugin").assertDoesNotExist()
        compose.onNodeWithContentDescription("${context.getString(R.string.more)}: File plugin").performClick()
        compose.onNodeWithText("file.plugin").assertIsDisplayed().assertIsNotEnabled()
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
        runBlocking {
            val authoring = koinGet<PluginAuthoring>()
            val plugin = authoring.create("plugin-management-ui-fixture")
            screenshotPluginId = plugin.id
            writeScreenshotPlugin(authoring, plugin)
        }
        compose.setContent {
            CompositionLocalProvider(
                LocalGlobalAppearance provides GlobalAppearance.Default,
                LocalTimelineAppearance provides TimelineAppearance(aiConfig = TimelineAppearance.AiConfig(agent = true)),
                LocalUriHandler provides uriHandler,
            ) {
                FlareTheme(darkTheme = false, dynamicColor = false) {
                    AndroidPluginManagementScreen(onBack = {}, onSettings = {})
                }
            }
        }
        compose.onNodeWithText("Pixelfed").assertIsDisplayed()
        compose.onNodeWithText("Up to date").assertDoesNotExist()
        compose.onAllNodes(isToggleable()).assertCountEquals(0)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val linkText = context.getString(R.string.plugin_report_issue)
        compose.onNodeWithText(context.getString(R.string.plugin_beta_description, linkText)).performFirstLinkClick()
        compose.runOnIdle { assertEquals("https://github.com/flaresocialapp/Flare/issues", openedUrl) }
        compose.onNodeWithText(linkText).assertDoesNotExist()
        screenshot("flare-plugins-android.png")
        compose.onNodeWithContentDescription("${context.getString(R.string.more)}: Pixelfed").performClick()
        compose.onNodeWithText(context.getString(R.string.plugin_uninstall)).assertIsDisplayed()
        screenshot("flare-plugins-android-item-menu.png")
        compose.onNodeWithText(context.getString(R.string.plugin_uninstall)).performClick()
        compose.onNodeWithText(context.getString(android.R.string.cancel)).performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.plugin_add)).performClick()
        compose.onNodeWithText(context.getString(R.string.plugin_ai_create)).assertIsDisplayed()
        screenshot("flare-plugins-android-menu.png")
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(instrumentation.targetContext.externalCacheDir, name).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    @Test
    fun enlargedTextWrapsTheRepositoryAndKeepsBothActionsOnANarrowScreen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "Example plugin with a long name"
        val plugin =
            PluginManagementItemV1(
                pluginId = "example.plugin",
                platformId = "example",
                nameText = UiText.Raw(name),
                iconPath = "",
                version = "1.0.0",
                sourceUrl = "https://github.com/example/flare-plugins?path=/plugins/example",
                availableUpdate = GitHubPluginVersionV1("bundle", "1.1.0", "b".repeat(40)),
                sourceCommitSha = "a".repeat(40),
            )
        var updated = false
        var openedUrl: String? = null
        val uriHandler =
            object : UriHandler {
                override fun openUri(uri: String) {
                    openedUrl = uri
                }
            }
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f),
                    LocalUriHandler provides uriHandler,
                ) {
                    Box(Modifier.width(260.dp).padding(horizontal = screenHorizontalPadding)) {
                        PluginItem(
                            plugin = plugin,
                            busy = false,
                            shapesIndex = 0,
                            shapesCount = 1,
                            onSettings = {},
                            onCancelUpdate = {},
                            onUninstall = {},
                            onUpdate = { updated = true },
                        )
                    }
                }
            }
        }
        val update = context.getString(R.string.plugin_update)
        val more = "${context.getString(R.string.more)}: $name"
        compose.assertPluginActionRow(name, update, more, "example/flare-plugins/plugins/example", "v1.0.0")
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(name, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.single().lineCount > 1 && !layouts.single().hasVisualOverflow)
        layouts.clear()
        val link = compose.onNodeWithText("example/flare-plugins/plugins/example", useUnmergedTree = true)
        assertTrue(link.fetchSemanticsNode().boundsInRoot.width >= with(compose.density) { 72.dp.toPx() })
        link.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.single().lineCount > 1)
        assertTrue(!layouts.single().hasVisualOverflow)
        link.performFirstLinkClick()
        compose.runOnIdle {
            assertEquals("https://github.com/example/flare-plugins/tree/${plugin.sourceCommitSha}/plugins/example", openedUrl)
        }
        compose.onNodeWithText(update).assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(updated) }
        compose.onNodeWithContentDescription(more).assertIsDisplayed().performClick()
        compose.onNodeWithText(context.getString(R.string.plugin_uninstall)).assertIsDisplayed()
    }

    @Test
    fun timelinePermissionLabelTogglesOnlyItsOwnSwitch() {
        val plugin =
            PluginManagementItemV1(
                pluginId = "example.plugin",
                platformId = "example",
                nameText = UiText.Raw("Example"),
                version = "1.0",
                iconPath = "",
                sourceUrl = "https://github.com/example/plugin",
                availableUpdate = null,
                hasTimelineProcessor = true,
            )
        val opened = mutableListOf<String>()
        val uriHandler =
            object : UriHandler {
                override fun openUri(uri: String) {
                    opened += uri
                }
            }
        var enabled by mutableStateOf(false)
        var busy by mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalUriHandler provides uriHandler) {
                    PluginItem(
                        plugin = plugin.copy(timelineProcessorEnabled = enabled),
                        busy = busy,
                        shapesIndex = 0,
                        shapesCount = 1,
                        onSettings = {},
                        onCancelUpdate = {},
                        onUninstall = {},
                        onUpdate = {},
                        onToggleTimelineProcessor = { _, checked -> enabled = checked },
                    )
                }
            }
        }
        val label = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.plugin_timeline_processor_toggle)
        val toggle = compose.onNode(hasText(label) and isToggleable())
        val labelText = compose.onNodeWithText(label, useUnmergedTree = true)
        toggle.assertIsOff()
        labelText.performTouchInput { click() }
        toggle.assertIsOn().performClick()
        toggle.assertIsOff()
        compose.runOnIdle { busy = true }
        toggle.assertIsNotEnabled()
        labelText.performTouchInput { click() }
        compose.runOnIdle {
            assertTrue(opened.isEmpty())
            assertEquals(false, enabled)
        }
    }

    @Test
    fun pendingActionsFitNarrowCard() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag("ca-ES")) }
        val resources = context.createConfigurationContext(configuration).resources
        var busy by mutableStateOf(false)
        var canceled = false
        var uninstalls = 0
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalResources provides resources,
                    LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f),
                ) {
                    Box(Modifier.width(260.dp).padding(horizontal = screenHorizontalPadding)) {
                        PluginItem(
                            plugin =
                                PluginManagementStateV1(
                                    authoredPlugins =
                                        listOf(
                                            AuthoredPlugin(
                                                "local.ai.example",
                                                "Pixelfed",
                                                phase = PluginAuthoringPhase.WaitingForOperations,
                                                revision = 1,
                                                running = true,
                                            ),
                                        ),
                                ).items.single(),
                            shapesIndex = 0,
                            shapesCount = 1,
                            busy = busy,
                            onSettings = {},
                            onCancelUpdate = { canceled = true },
                            onUninstall = { uninstalls++ },
                            onUpdate = {},
                        )
                    }
                }
            }
        }
        compose.onAllNodes(isToggleable()).assertCountEquals(0)
        compose.onNodeWithText("Allow and continue").assertDoesNotExist()
        compose
            .onNodeWithText(
                resources.getString(R.string.plugin_ai_source_revision, 1L),
            ).assertIsDisplayed()
        compose.onNodeWithText("The current version is running").assertDoesNotExist()
        val layouts = mutableListOf<TextLayoutResult>()
        val cancelLabel = resources.getString(R.string.plugin_ai_stop)
        val more = "${resources.getString(R.string.more)}: Pixelfed"
        compose.assertPluginActionRow("Pixelfed", cancelLabel, more, resources.getString(R.string.plugin_ai_source_revision, 1L))
        compose
            .onNodeWithText(cancelLabel, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(
            "The long Catalan label must wrap without clipping",
            layouts.single().lineCount > 1 && !layouts.single().hasVisualOverflow,
        )
        compose.onNodeWithText(cancelLabel).performClick()
        compose.runOnIdle { assertTrue(canceled) }
        val uninstall = resources.getString(R.string.plugin_uninstall)
        compose.onNodeWithText(uninstall).assertDoesNotExist()
        compose.onNodeWithContentDescription(more).performClick()
        compose.onNodeWithText(uninstall).assertIsDisplayed()
        compose.runOnIdle { busy = true }
        compose.onNodeWithText(uninstall).assertIsNotEnabled()
        compose.runOnIdle { busy = false }
        compose.onNodeWithText(uninstall).performClick()
        compose.onNodeWithText(uninstall).assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, uninstalls) }
    }
}

private suspend fun writeScreenshotPlugin(
    authoring: PluginAuthoring,
    plugin: AuthoredPlugin,
) {
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
