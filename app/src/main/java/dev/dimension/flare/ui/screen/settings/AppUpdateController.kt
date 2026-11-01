package dev.dimension.flare.ui.screen.settings

import dev.dimension.flare.data.update.AppUpdateRelease
import dev.dimension.flare.data.update.AppUpdateSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

internal sealed interface AppUpdateState {
    data object Idle : AppUpdateState

    data object Checking : AppUpdateState

    data object Latest : AppUpdateState

    data class Available(
        val release: AppUpdateRelease,
    ) : AppUpdateState

    data class Downloading(
        val release: AppUpdateRelease,
        val bytes: Long,
        val total: Long,
    ) : AppUpdateState

    data class Ready(
        val release: AppUpdateRelease,
        val file: File,
    ) : AppUpdateState

    data class Error(
        val cause: Exception,
    ) : AppUpdateState
}

internal class AppUpdateController(
    private val scope: CoroutineScope,
    private val source: AppUpdateSource,
    private val currentVersionCode: Long,
    private val applicationId: String,
    private val sdkInt: Int,
    private val cacheDir: File,
    private val validate: suspend (File, AppUpdateRelease) -> Unit,
) {
    private val mutableState = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    val state: StateFlow<AppUpdateState> = mutableState

    private var operation: Job? = null

    private val stateLock = Any()
    private var generation = 0L

    fun check() {
        if (operation?.isActive == true) return
        val request = begin(AppUpdateState.Checking)
        operation =
            scope.launch {
                try {
                    val release = source.checkForUpdate(currentVersionCode, applicationId, sdkInt)
                    publish(request, release?.let { AppUpdateState.Available(it) } ?: AppUpdateState.Latest)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    publish(request, AppUpdateState.Error(error))
                }
            }
    }

    fun download() {
        if (operation?.isActive == true) return
        val release = (state.value as? AppUpdateState.Available)?.release ?: return
        val request = begin(AppUpdateState.Downloading(release, 0, release.sizeBytes))
        operation =
            scope.launch {
                try {
                    val file =
                        source.download(release, cacheDir) { bytes, total ->
                            publish(request, AppUpdateState.Downloading(release, bytes, total))
                        }
                    validate(file, release)
                    publish(request, AppUpdateState.Ready(release, file))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    publish(request, AppUpdateState.Error(error))
                }
            }
    }

    fun cancel() {
        begin(AppUpdateState.Idle)
        operation?.cancel()
        operation = null
    }

    fun installationFailed(exception: Exception) {
        begin(AppUpdateState.Error(exception))
    }

    private fun begin(next: AppUpdateState): Long =
        synchronized(stateLock) {
            generation++
            mutableState.value = next
            generation
        }

    private fun publish(
        request: Long,
        next: AppUpdateState,
    ) {
        synchronized(stateLock) {
            if (generation == request) mutableState.value = next
        }
    }
}
