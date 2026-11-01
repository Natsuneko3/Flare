package dev.dimension.flare.ui.screen.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import dev.dimension.flare.BuildConfig
import dev.dimension.flare.R
import dev.dimension.flare.data.update.AppUpdateException
import dev.dimension.flare.data.update.AppUpdateFailure
import dev.dimension.flare.data.update.AppUpdateRelease
import dev.dimension.flare.data.update.AppUpdateSource
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

class AppUpdateSettingTest {
    @get:Rule val composeRule = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val release =
        AppUpdateRelease(
            1,
            context.packageName,
            1782,
            "test-update",
            26,
            "Flare.apk",
            "a".repeat(64),
            3,
            "android-test",
            "Test release notes",
        )

    @Test fun checksAndDownloadsOnlyAfterSeparateButtonPresses() {
        val source = FakeSource()
        var installs = 0
        render(source) { installs++ }
        composeRule.runOnIdle {
            assertEquals(0, source.checks)
            assertEquals(0, source.downloads)
        }
        composeRule.onNodeWithText(text(R.string.app_update_check)).performClick()
        composeRule.onNodeWithText(text(R.string.app_update_available, release.versionName)).assertIsDisplayed()
        composeRule.onNodeWithText("Test release notes").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0, source.downloads) }
        composeRule.onNodeWithText(text(R.string.app_update_download)).performClick()
        composeRule.onNodeWithText(text(R.string.app_update_install)).assertIsDisplayed().performClick()
        composeRule.runOnIdle {
            assertEquals(1, source.checks)
            assertEquals(1, source.downloads)
            assertEquals(1, installs)
        }
    }

    @Test fun latestVersionDoesNotOfferDownload() {
        val source = FakeSource().apply { result = null }
        render(source)
        composeRule.onNodeWithText(text(R.string.app_update_check)).performClick()
        composeRule.onNodeWithText(text(R.string.app_update_latest, BuildConfig.VERSION_NAME)).assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.app_update_download)).assertDoesNotExist()
        composeRule.onNodeWithText(text(R.string.app_update_done)).performClick()
        composeRule.runOnIdle { assertEquals(0, source.downloads) }
    }

    @Test fun failedCheckOffersExplicitRetry() {
        val source = FakeSource().apply { fail = true }
        render(source)
        composeRule.onNodeWithText(text(R.string.app_update_check)).performClick()
        composeRule.onNodeWithText(text(R.string.app_update_network_error)).assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(1, source.checks)
            source.fail = false
        }
        composeRule.onNodeWithText(text(R.string.app_update_retry)).performClick()
        composeRule.onNodeWithText(text(R.string.app_update_download)).assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(2, source.checks) }
    }

    @Test fun cancellingDownloadDoesNotLaterOpenTheInstaller() {
        val gate = CompletableDeferred<Unit>()
        val source = FakeSource().apply { downloadGate = gate }
        var installs = 0
        render(source) { installs++ }
        composeRule.onNodeWithText(text(R.string.app_update_check)).performClick()
        composeRule.onNodeWithText(text(R.string.app_update_download)).performClick()
        composeRule.onNodeWithText(text(R.string.app_update_downloading)).assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.app_update_cancel)).performClick()
        composeRule.runOnIdle { gate.complete(Unit) }
        composeRule.onNodeWithText(text(R.string.app_update_install)).assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(0, installs) }
    }

    private fun render(
        source: AppUpdateSource,
        onInstall: () -> Unit = {},
    ) {
        composeRule.setContent {
            MaterialTheme {
                val scope = rememberCoroutineScope()
                val controller =
                    remember {
                        AppUpdateController(scope, source, 1781, context.packageName, 36, context.cacheDir) { _, _ -> }
                    }
                val state by controller.state.collectAsState()
                AppUpdateSettingContent(
                    state = state,
                    onCheck = controller::check,
                    onDownload = controller::download,
                    onDismiss = controller::cancel,
                    onInstall = onInstall,
                )
            }
        }
    }

    private fun text(
        id: Int,
        vararg args: Any,
    ) = context.getString(id, *args)

    private inner class FakeSource : AppUpdateSource {
        var checks = 0
        var downloads = 0
        var result: AppUpdateRelease? = release
        var fail = false
        var downloadGate: CompletableDeferred<Unit>? = null

        override suspend fun checkForUpdate(
            currentVersionCode: Long,
            applicationId: String,
            sdkInt: Int,
        ): AppUpdateRelease? {
            checks++
            if (fail) throw AppUpdateException(AppUpdateFailure.NETWORK, "offline")
            return result
        }

        override suspend fun download(
            release: AppUpdateRelease,
            cacheDir: File,
            onProgress: (Long, Long) -> Unit,
        ): File {
            downloads++
            onProgress(1, 3)
            downloadGate?.await()
            return File(cacheDir, "ui-test-only.apk")
        }
    }
}
