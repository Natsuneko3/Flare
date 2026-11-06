package dev.dimension.flare.ui.screen.settings

import android.content.Context
import android.os.Build
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import compose.icons.FontAwesomeIcons
import compose.icons.fontawesomeicons.Solid
import compose.icons.fontawesomeicons.solid.Download
import dev.dimension.flare.BuildConfig
import dev.dimension.flare.R
import dev.dimension.flare.common.AppUpdateInstallException
import dev.dimension.flare.common.AppUpdateInstaller
import dev.dimension.flare.data.update.AppUpdateException
import dev.dimension.flare.data.update.AppUpdateFailure
import dev.dimension.flare.data.update.AppUpdateRepository
import dev.dimension.flare.ui.component.ThemeIconData
import dev.dimension.flare.ui.component.ThemedIcon
import dev.dimension.flare.ui.theme.single
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File

internal class AppUpdateViewModel(
    context: Context,
) : ViewModel() {
    val installer = AppUpdateInstaller(context.applicationContext)
    val controller =
        AppUpdateController(
            scope = viewModelScope,
            source = AppUpdateRepository(),
            currentVersionCode = BuildConfig.VERSION_CODE.toLong(),
            applicationId = context.packageName,
            sdkInt = Build.VERSION.SDK_INT,
            cacheDir = File(context.cacheDir, "updates"),
            validate = installer::validate,
        )
}

@Composable
internal fun AppUpdateSetting(
    context: Context = LocalContext.current,
    model: AppUpdateViewModel = viewModel { AppUpdateViewModel(context.applicationContext) },
) {
    val state by model.controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    var preparingInstall by remember { mutableStateOf(false) }
    var pendingPermissionFile by rememberSaveable { mutableStateOf<String?>(null) }
    val installerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    val launchInstaller: () -> Unit = {
        val ready = model.controller.state.value as? AppUpdateState.Ready
        if (ready != null && !preparingInstall) {
            preparingInstall = true
            scope.launch {
                try {
                    // Recheck both the file and installed package after returning from Settings.
                    model.installer.validate(ready.file, ready.release)
                    installerLauncher.launch(model.installer.installIntent(ready.file))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    model.controller.installationFailed(error)
                } finally {
                    preparingInstall = false
                }
            }
        }
    }
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val expectedFile = pendingPermissionFile
            pendingPermissionFile = null
            val ready = model.controller.state.value as? AppUpdateState.Ready
            if (expectedFile != null && expectedFile == ready?.permissionKey()) {
                if (model.installer.canRequestInstalls()) {
                    launchInstaller()
                } else {
                    model.controller.installationFailed(SecurityException("Install permission not granted"))
                }
            }
        }
    AppUpdateSettingContent(
        state = state,
        preparingInstall = preparingInstall,
        onCheck = {
            pendingPermissionFile = null
            model.controller.check()
        },
        onDownload = model.controller::download,
        onDismiss = {
            pendingPermissionFile = null
            model.controller.cancel()
        },
        onInstall = {
            try {
                if (model.installer.canRequestInstalls()) {
                    launchInstaller()
                } else {
                    pendingPermissionFile = (model.controller.state.value as? AppUpdateState.Ready)?.permissionKey()
                    permissionLauncher.launch(model.installer.permissionIntent())
                }
            } catch (error: Exception) {
                pendingPermissionFile = null
                model.controller.installationFailed(error)
            }
        },
    )
}

private fun AppUpdateState.Ready.permissionKey() = "${release.versionCode}:${release.sha256}:${file.absolutePath}"

@Composable
internal fun AppUpdateSettingContent(
    state: AppUpdateState,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
    preparingInstall: Boolean = false,
    onInstall: () -> Unit,
) {
    SegmentedListItem(
        onClick = onCheck,
        shapes = ListItemDefaults.single(),
        content = { Text(stringResource(R.string.app_update_check)) },
        supportingContent = { Text(stringResource(R.string.app_update_current, BuildConfig.VERSION_NAME)) },
        leadingContent = {
            ThemedIcon(FontAwesomeIcons.Solid.Download, contentDescription = null, color = ThemeIconData.Color.SapphireBlue)
        },
    )
    if (state == AppUpdateState.Idle) return
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = { if (!preparingInstall) onDismiss() },
        title = { Text(stringResource(R.string.app_update_check)) },
        text = {
            Column(
                Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (state) {
                    AppUpdateState.Idle -> {
                        Unit
                    }

                    AppUpdateState.Checking -> {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.app_update_checking))
                    }

                    AppUpdateState.Latest -> {
                        Text(stringResource(R.string.app_update_latest, BuildConfig.VERSION_NAME))
                    }

                    is AppUpdateState.Available -> {
                        Text(stringResource(R.string.app_update_available, state.release.versionName))
                        Text(Formatter.formatFileSize(context, state.release.sizeBytes))
                        Text(state.release.releaseNotes.ifBlank { stringResource(R.string.app_update_no_notes) })
                    }

                    is AppUpdateState.Downloading -> {
                        Text(stringResource(R.string.app_update_downloading))
                        LinearProgressIndicator(
                            progress = { (state.bytes.toFloat() / state.total.coerceAtLeast(1)).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("${Formatter.formatFileSize(context, state.bytes)} / ${Formatter.formatFileSize(context, state.total)}")
                    }

                    is AppUpdateState.Ready -> {
                        Text(stringResource(R.string.app_update_ready, state.release.versionName))
                        if (preparingInstall) CircularProgressIndicator()
                    }

                    is AppUpdateState.Error -> {
                        Text(updateErrorMessage(state.cause))
                    }
                }
            }
        },
        confirmButton = {
            when (state) {
                is AppUpdateState.Available -> {
                    TextButton(onClick = onDownload) { Text(stringResource(R.string.app_update_download)) }
                }

                is AppUpdateState.Ready -> {
                    TextButton(onClick = onInstall, enabled = !preparingInstall) {
                        Text(stringResource(R.string.app_update_install))
                    }
                }

                is AppUpdateState.Error -> {
                    TextButton(onClick = onCheck) { Text(stringResource(R.string.app_update_retry)) }
                }

                AppUpdateState.Latest -> {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.app_update_done)) }
                }

                else -> {
                    Unit
                }
            }
        },
        dismissButton = {
            if (state != AppUpdateState.Latest) {
                TextButton(onClick = onDismiss, enabled = !preparingInstall) { Text(stringResource(R.string.app_update_cancel)) }
            }
        },
    )
}

@Composable
private fun updateErrorMessage(error: Exception): String =
    stringResource(
        when (error) {
            is SecurityException -> {
                R.string.app_update_permission_required
            }

            is AppUpdateInstallException -> {
                R.string.app_update_invalid_package
            }

            is AppUpdateException -> {
                when (error.reason) {
                    AppUpdateFailure.NO_RELEASE -> R.string.app_update_no_release
                    AppUpdateFailure.INCOMPATIBLE_DEVICE -> R.string.app_update_incompatible
                    AppUpdateFailure.STORAGE_ERROR -> R.string.app_update_storage_error
                    AppUpdateFailure.NETWORK, AppUpdateFailure.HTTP_ERROR -> R.string.app_update_network_error
                    else -> R.string.app_update_invalid_package
                }
            }

            else -> {
                R.string.app_update_network_error
            }
        },
    )
