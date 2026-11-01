package dev.dimension.flare.common

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import dev.dimension.flare.data.update.AppUpdateRelease
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest

internal enum class AppUpdateInstallFailure {
    INVALID_APK,
    WRONG_PACKAGE,
    WRONG_VERSION,
    WRONG_SIGNATURE,
    INCOMPATIBLE_DEVICE,
    CHECKSUM_MISMATCH,
    FILE_MISSING,
}

internal class AppUpdateInstallException(
    val reason: AppUpdateInstallFailure,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

internal data class AppUpdatePackageMetadata(
    val packageName: String,
    val versionCode: Long,
    val minSdk: Int,
    val signerSha256: Set<String>?,
)

internal object AppUpdateInstallPolicy {
    fun validate(
        applicationId: String,
        sdkInt: Int,
        release: AppUpdateRelease,
        installed: AppUpdatePackageMetadata,
        archive: AppUpdatePackageMetadata,
    ) {
        if (release.applicationId != applicationId || archive.packageName != applicationId || installed.packageName != applicationId) {
            throw AppUpdateInstallException(AppUpdateInstallFailure.WRONG_PACKAGE, "The update is for a different application.")
        }
        if (archive.versionCode != release.versionCode || archive.versionCode <= installed.versionCode) {
            throw AppUpdateInstallException(
                AppUpdateInstallFailure.WRONG_VERSION,
                "The update version does not match or is no longer newer.",
            )
        }
        if (archive.minSdk > sdkInt || release.minSdk > sdkInt) {
            throw AppUpdateInstallException(AppUpdateInstallFailure.INCOMPATIBLE_DEVICE, "The update requires a newer Android version.")
        }
        if (installed.signerSha256.isNullOrEmpty() ||
            archive.signerSha256.isNullOrEmpty() ||
            installed.signerSha256 != archive.signerSha256
        ) {
            throw AppUpdateInstallException(
                AppUpdateInstallFailure.WRONG_SIGNATURE,
                "The update is not signed by the installed application's signer.",
            )
        }
    }
}

internal class AppUpdateInstaller(
    context: Context,
) {
    private val context = context.applicationContext

    suspend fun validate(
        file: File,
        release: AppUpdateRelease,
    ): Unit =
        withContext(Dispatchers.IO) {
            validateDownloadedFile(file, release)
            currentCoroutineContext().ensureActive()
            try {
                val packageManager = context.packageManager
                val flags = signingFlags()
                val archive =
                    packageManager.getPackageArchiveInfo(file.absolutePath, flags)
                        ?: throw AppUpdateInstallException(
                            AppUpdateInstallFailure.INVALID_APK,
                            "The downloaded file is not a readable APK.",
                        )
                val installed = packageManager.getPackageInfo(context.packageName, flags)
                AppUpdateInstallPolicy.validate(
                    applicationId = context.packageName,
                    sdkInt = Build.VERSION.SDK_INT,
                    release = release,
                    installed = installed.updateMetadata(),
                    archive = archive.updateMetadata(),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: AppUpdateInstallException) {
                throw error
            } catch (error: Exception) {
                throw AppUpdateInstallException(
                    AppUpdateInstallFailure.INVALID_APK,
                    "Unable to read application package information.",
                    error,
                )
            }
        }

    fun canRequestInstalls(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun permissionIntent(): Intent =
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        )

    fun installIntent(file: File): Intent =
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(
                FileProvider.getUriForFile(context, "${context.packageName}.provider", file),
                "application/vnd.android.package-archive",
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    private suspend fun validateDownloadedFile(
        file: File,
        release: AppUpdateRelease,
    ) {
        if (!file.isFile || !file.canRead()) {
            throw AppUpdateInstallException(AppUpdateInstallFailure.FILE_MISSING, "The downloaded update is missing or unreadable.")
        }
        if (file.length() != release.sizeBytes) {
            throw AppUpdateInstallException(AppUpdateInstallFailure.CHECKSUM_MISMATCH, "The downloaded update size does not match.")
        }
        val digest = MessageDigest.getInstance("SHA-256")
        var bytesRead = 0L
        try {
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                    bytesRead += count
                }
            }
        } catch (error: FileNotFoundException) {
            throw AppUpdateInstallException(AppUpdateInstallFailure.FILE_MISSING, "The downloaded update is missing or unreadable.", error)
        } catch (error: IOException) {
            throw AppUpdateInstallException(AppUpdateInstallFailure.INVALID_APK, "Unable to read the downloaded update.", error)
        }
        if (bytesRead != release.sizeBytes || !digest.digest().hexString().equals(release.sha256, ignoreCase = true)) {
            throw AppUpdateInstallException(AppUpdateInstallFailure.CHECKSUM_MISMATCH, "The downloaded update checksum does not match.")
        }
    }

    @Suppress("DEPRECATION")
    private fun signingFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }

    @Suppress("DEPRECATION")
    private fun PackageInfo.updateMetadata(): AppUpdatePackageMetadata {
        val applicationInfo =
            applicationInfo
                ?: throw AppUpdateInstallException(AppUpdateInstallFailure.INVALID_APK, "Application metadata is missing from the APK.")
        // Compare current signers exactly. Signing history alone must not authorize an update.
        val currentSigners =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                signingInfo?.apkContentsSigners
            } else {
                signatures
            }
        val signerSha256 =
            currentSigners
                ?.map { signature ->
                    val certificate = signature.toByteArray()
                    if (certificate.isEmpty()) {
                        throw AppUpdateInstallException(AppUpdateInstallFailure.WRONG_SIGNATURE, "The signing certificate is empty.")
                    }
                    MessageDigest.getInstance("SHA-256").digest(certificate).hexString()
                }?.toSet()
        return AppUpdatePackageMetadata(
            packageName = packageName.orEmpty(),
            versionCode = PackageInfoCompat.getLongVersionCode(this),
            minSdk = applicationInfo.minSdkVersion,
            signerSha256 = signerSha256,
        )
    }
}

private fun ByteArray.hexString(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
