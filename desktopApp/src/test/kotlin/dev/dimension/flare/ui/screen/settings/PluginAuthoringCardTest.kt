package dev.dimension.flare.ui.screen.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import dev.dimension.flare.data.repository.AuthoredPlugin
import dev.dimension.flare.data.repository.PluginAuthoringPhase
import dev.dimension.flare.feature.plugin.management.PluginManagementStateV1
import dev.dimension.flare.ui.theme.FlareTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.Locale

class PluginAuthoringCardTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun localPluginUsesTheCommonItemWithSourceAndRevision() {
        val plugin = AuthoredPlugin("local.ai.example", "Same name", phase = PluginAuthoringPhase.Applied, revision = 7)
        var busy by mutableStateOf(false)
        var uninstalls = 0
        compose.setContent {
            FlareTheme(isDarkTheme = false) {
                PluginItem(
                    PluginManagementStateV1(authoredPlugins = listOf(plugin)).items.single(),
                    busy = busy,
                    onCancelUpdate = {},
                    onUninstall = { uninstalls++ },
                    onUpdate = {},
                )
            }
        }
        compose.onNodeWithText(plugin.name).assertIsDisplayed()
        compose.onNodeWithContentDescription(plugin.name).assertExists()
        compose.onNodeWithText(plugin.id).assertDoesNotExist()
        compose.onNodeWithText("Local AI-generated plugin · Revision 7").assertIsDisplayed()
        compose.onNodeWithText("Up to date").assertDoesNotExist()
        compose.onNodeWithText("Allow and continue").assertDoesNotExist()
        compose.onNodeWithText("Enabled").assertDoesNotExist()
        compose.onNodeWithText("Uninstall").assertDoesNotExist()
        compose.onNodeWithContentDescription("More: Same name").performClick()
        compose.onNodeWithText("Uninstall").assertIsDisplayed()
        compose.runOnIdle { busy = true }
        compose.onNodeWithText("Uninstall").assertIsNotEnabled()
        compose.runOnIdle { busy = false }
        compose.onNodeWithText("Uninstall").performClick()
        compose.onNodeWithText("Uninstall").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, uninstalls) }
    }

    @Test
    fun revalidationCanBeStoppedWithoutAnAgentRun() {
        var currentPlugin by mutableStateOf(
            AuthoredPlugin("test.plugin", "Test plugin", phase = PluginAuthoringPhase.Validating, revision = 1, running = true),
        )
        var stops = 0
        compose.setContent {
            FlareTheme(isDarkTheme = false) {
                Box(Modifier.width(300.dp)) {
                    PluginItem(
                        plugin = PluginManagementStateV1(authoredPlugins = listOf(currentPlugin)).items.single(),
                        busy = false,
                        onCancelUpdate = {
                            stops++
                            currentPlugin = currentPlugin.copy(phase = PluginAuthoringPhase.Idle)
                        },
                        onUninstall = {},
                        onUpdate = {},
                    )
                }
            }
        }

        val layouts = mutableListOf<TextLayoutResult>()
        compose
            .onNodeWithText(
                "Cancel update",
                useUnmergedTree = true,
            ).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(!layouts.single().hasVisualOverflow)
        val revision = compose.onNodeWithText("Local AI-generated plugin · Revision 1").assertIsDisplayed()
        val cancel = compose.onNodeWithText("Cancel update").fetchSemanticsNode().boundsInRoot
        val more = compose.onNodeWithContentDescription("More: Test plugin").fetchSemanticsNode().boundsInRoot
        assertTrue("The revision must stay in the information column", revision.fetchSemanticsNode().boundsInRoot.right < cancel.left)
        assertTrue("Cancel must precede More", cancel.right <= more.left)
        assertEquals(cancel.center.y, more.center.y, 1f)
        val row = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertEquals(row.center.y, more.center.y, 1f)
        compose.onNodeWithText("Cancel update").assertIsDisplayed().performClick()
        compose.onNodeWithText("Cancel update").assertDoesNotExist()
        compose.onNodeWithText("Ready to edit").assertDoesNotExist()
        compose.onNodeWithText("The current version is running").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, stops) }
    }

    @Test
    fun longTranslatedActionWrapsWithRoomForThePluginName() {
        val previousLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ca-ES"))
            val plugin = AuthoredPlugin("test.plugin", "Un complement de prova", phase = PluginAuthoringPhase.Validating, running = true)
            compose.setContent {
                FlareTheme(isDarkTheme = false) {
                    Box(Modifier.width(272.dp)) {
                        PluginItem(
                            PluginManagementStateV1(authoredPlugins = listOf(plugin)).items.single(),
                            busy = false,
                            onCancelUpdate = {},
                            onUninstall = {},
                            onUpdate = {},
                        )
                    }
                }
            }
            val label = "Cancel·lar actualització"
            val layouts = mutableListOf<TextLayoutResult>()
            compose
                .onNodeWithText(label, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue(layouts.single().lineCount > 1)
            assertTrue(!layouts.single().hasVisualOverflow)
            val name = compose.onNodeWithText(plugin.name).fetchSemanticsNode().boundsInRoot
            val cancel = compose.onNodeWithText(label).fetchSemanticsNode().boundsInRoot
            val menu =
                compose
                    .onNodeWithContentDescription("Més: ${plugin.name}")
                    .assertIsDisplayed()
                    .fetchSemanticsNode()
                    .boundsInRoot
            assertTrue(name.width >= cancel.width / 2 && name.right < cancel.left)
            assertTrue(cancel.right <= menu.left)
            assertEquals(cancel.center.y, menu.center.y, 1f)
        } finally {
            Locale.setDefault(previousLocale)
        }
    }
}
