package dev.dimension.flare.ui.screen.settings

import dev.dimension.flare.data.update.AppUpdateRelease
import dev.dimension.flare.data.update.AppUpdateSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class AppUpdateControllerTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val release =
        AppUpdateRelease(
            1,
            "dev.dimension.flare.videofix",
            1781,
            "1.7.8-custom.1",
            26,
            "Flare.apk",
            "a".repeat(64),
            3,
            "android-test",
            "Update notes",
        )

    @Test fun constructingControllerDoesNotCheckOrDownload() =
        runTest {
            val source = FakeSource()
            val controller = controller(source)
            advanceUntilIdle()
            assertEquals(AppUpdateState.Idle, controller.state.value)
            assertEquals(0, source.checks)
            assertEquals(0, source.downloads)
        }

    @Test fun checkIsExplicitAndDownloadRequiresASecondAction() =
        runTest {
            val source = FakeSource()
            var validated = 0
            val controller = controller(source) { _, _ -> validated++ }
            controller.check()
            assertEquals(AppUpdateState.Checking, controller.state.value)
            advanceUntilIdle()
            assertEquals(AppUpdateState.Available(release), controller.state.value)
            assertEquals(1, source.checks)
            assertEquals(0, source.downloads)
            controller.download()
            advanceUntilIdle()
            assertTrue(controller.state.value is AppUpdateState.Ready)
            assertEquals(1, source.downloads)
            assertEquals(1, validated)
        }

    @Test fun equalVersionShowsLatestAndCannotDownload() =
        runTest {
            val source = FakeSource().apply { result = null }
            val controller = controller(source)
            controller.check()
            advanceUntilIdle()
            assertEquals(AppUpdateState.Latest, controller.state.value)
            controller.download()
            advanceUntilIdle()
            assertEquals(0, source.downloads)
        }

    @Test fun validationFailureNeverBecomesReady() =
        runTest {
            val controller = controller(FakeSource()) { _, _ -> throw IOException("Wrong signer") }
            controller.check()
            advanceUntilIdle()
            controller.download()
            advanceUntilIdle()
            assertTrue(controller.state.value is AppUpdateState.Error)
        }

    @Test fun repeatedCheckAndCancellationDoNotStartAnotherRequest() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val source = FakeSource().apply { waitFor = gate }
            val controller = controller(source)
            controller.check()
            controller.check()
            runCurrent()
            assertEquals(1, source.checks)
            controller.cancel()
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(AppUpdateState.Idle, controller.state.value)
            assertEquals(0, source.downloads)
        }

    private fun kotlinx.coroutines.test.TestScope.controller(
        source: AppUpdateSource,
        validate: suspend (File, AppUpdateRelease) -> Unit = { _, _ -> },
    ) = AppUpdateController(this, source, 1780, release.applicationId, 36, temporaryFolder.root, validate)

    private inner class FakeSource : AppUpdateSource {
        var checks = 0
        var downloads = 0
        var result: AppUpdateRelease? = release
        var waitFor: CompletableDeferred<Unit>? = null

        override suspend fun checkForUpdate(
            currentVersionCode: Long,
            applicationId: String,
            sdkInt: Int,
        ): AppUpdateRelease? {
            checks++
            waitFor?.await()
            return result
        }

        override suspend fun download(
            release: AppUpdateRelease,
            cacheDir: File,
            onProgress: (Long, Long) -> Unit,
        ): File {
            downloads++
            onProgress(3, 3)
            return File(cacheDir, "update.apk").apply { writeText("apk") }
        }
    }
}
