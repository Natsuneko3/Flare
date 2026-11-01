package dev.dimension.flare.common

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import dev.dimension.flare.data.update.AppUpdateRelease
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
class AppUpdateInstallerTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val applicationId get() = context.packageName
    private val installed get() = AppUpdatePackageMetadata(applicationId, 10, 26, setOf("signer-a"))
    private val archive get() = AppUpdatePackageMetadata(applicationId, 11, 26, setOf("signer-a"))
    private val release
        get() =
            AppUpdateRelease(
                schemaVersion = 1,
                applicationId = applicationId,
                versionCode = 11,
                versionName = "1.1",
                minSdk = 26,
                apkFileName = "flare.apk",
                sha256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                sizeBytes = 3,
                releaseTag = "v1.1",
                releaseNotes = "Test update",
            )
    private lateinit var file: File
    private lateinit var installer: AppUpdateInstaller

    @Before
    fun setUp() {
        file = File.createTempFile("app-update-", ".apk", context.cacheDir)
        file.writeText("abc")
        installer = AppUpdateInstaller(context)
    }

    @After
    fun tearDown() {
        file.delete()
    }

    @Test
    fun acceptsNewerMatchingPackageWithTheSameSigner() {
        validatePolicy()
    }

    @Test
    fun rejectsAnArchiveForAnotherPackage() {
        assertFailure(AppUpdateInstallFailure.WRONG_PACKAGE) {
            validatePolicy(archive = archive.copy(packageName = "other.app"))
        }
    }

    @Test
    fun rejectsMetadataForAnotherPackageEvenWhenArchiveMatchesIt() {
        assertFailure(AppUpdateInstallFailure.WRONG_PACKAGE) {
            validatePolicy(
                release = release.copy(applicationId = "other.app"),
                archive = archive.copy(packageName = "other.app"),
            )
        }
    }

    @Test
    fun rejectsVersionThatDoesNotMatchRelease() {
        assertFailure(AppUpdateInstallFailure.WRONG_VERSION) {
            validatePolicy(archive = archive.copy(versionCode = 12))
        }
    }

    @Test
    fun rejectsTheAlreadyInstalledVersion() {
        assertFailure(AppUpdateInstallFailure.WRONG_VERSION) {
            validatePolicy(installed = installed.copy(versionCode = 11))
        }
    }

    @Test
    fun rejectsDowngradeEvenWhenReleaseMetadataMatchesArchive() {
        assertFailure(AppUpdateInstallFailure.WRONG_VERSION) {
            validatePolicy(installed = installed.copy(versionCode = 12))
        }
    }

    @Test
    fun rejectsAnArchiveThatNeedsANewerAndroidVersion() {
        assertFailure(AppUpdateInstallFailure.INCOMPATIBLE_DEVICE) {
            validatePolicy(archive = archive.copy(minSdk = 36))
        }
    }

    @Test
    fun rejectsReleaseMetadataThatNeedsANewerAndroidVersion() {
        assertFailure(AppUpdateInstallFailure.INCOMPATIBLE_DEVICE) {
            validatePolicy(release = release.copy(minSdk = 36))
        }
    }

    @Test
    fun rejectsAnUnrelatedSigner() {
        assertFailure(AppUpdateInstallFailure.WRONG_SIGNATURE) {
            validatePolicy(archive = archive.copy(signerSha256 = setOf("signer-b")))
        }
    }

    @Test
    fun rejectsAnAdditionalSignerEvenIfOneSignerMatches() {
        assertFailure(AppUpdateInstallFailure.WRONG_SIGNATURE) {
            validatePolicy(archive = archive.copy(signerSha256 = setOf("signer-a", "signer-b")))
        }
    }

    @Test
    fun acceptsTheSameMultipleSignersRegardlessOfOrder() {
        validatePolicy(
            installed = installed.copy(signerSha256 = linkedSetOf("signer-b", "signer-a")),
            archive = archive.copy(signerSha256 = linkedSetOf("signer-a", "signer-b")),
        )
    }

    @Test
    fun rejectsMissingArchiveSigningInformation() {
        assertFailure(AppUpdateInstallFailure.WRONG_SIGNATURE) {
            validatePolicy(archive = archive.copy(signerSha256 = null))
        }
    }

    @Test
    fun rejectsMissingInstalledSigningInformation() {
        assertFailure(AppUpdateInstallFailure.WRONG_SIGNATURE) {
            validatePolicy(installed = installed.copy(signerSha256 = null))
        }
    }

    @Test
    fun rejectsEmptySigningInformationEvenWhenBothSetsMatch() {
        assertFailure(AppUpdateInstallFailure.WRONG_SIGNATURE) {
            validatePolicy(
                installed = installed.copy(signerSha256 = emptySet()),
                archive = archive.copy(signerSha256 = emptySet()),
            )
        }
    }

    @Test
    fun rejectsAMissingDownloadedFile() {
        file.delete()

        assertFailure(AppUpdateInstallFailure.FILE_MISSING) { validateFile() }
    }

    @Test
    fun rejectsAnIncorrectSizeBeforeParsingTheArchive() {
        assertFailure(AppUpdateInstallFailure.CHECKSUM_MISMATCH) {
            validateFile(release.copy(sizeBytes = 4))
        }
    }

    @Test
    fun rejectsModifiedBytesEvenWhenTheSizeMatches() {
        file.writeText("abd")

        assertFailure(AppUpdateInstallFailure.CHECKSUM_MISMATCH) { validateFile() }
    }

    @Test
    fun rejectsAnUnparseableArchiveEvenWhenTheChecksumMatches() {
        assertFailure(AppUpdateInstallFailure.INVALID_APK) { validateFile() }
    }

    @Test
    fun validatesDownloadedBytesAndPackageManagerSigningInformation() {
        registerPackages()

        validateFile()
    }

    @Test
    fun checksTheActualInstalledVersionAtInstallationTime() {
        registerPackages(installedVersion = 12)

        assertFailure(AppUpdateInstallFailure.WRONG_VERSION) { validateFile() }
    }

    @Test
    fun rejectsDifferentPackageManagerCertificates() {
        registerPackages(archiveSignatures = arrayOf(Signature("bb")))

        assertFailure(AppUpdateInstallFailure.WRONG_SIGNATURE) { validateFile() }
    }

    @Test
    fun doesNotFallBackToLegacySignaturesWhenModernSigningInformationIsMissing() {
        registerPackages()
        val unsigned = packageInfo(11, arrayOf(Signature("aa"))).apply { signingInfo = null }
        shadowOf(context.packageManager).setPackageArchiveInfo(file.absolutePath, unsigned)

        assertFailure(AppUpdateInstallFailure.WRONG_SIGNATURE) { validateFile() }
    }

    @Test
    @Config(sdk = [26])
    fun validatesLegacySignaturesOnAndroidEight() {
        registerPackages()

        validateFile()
    }

    @Test
    fun permissionIntentOpensThisApplicationsUnknownSourceSettings() {
        val intent = installer.permissionIntent()

        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, intent.action)
        assertEquals("package:$applicationId", intent.dataString)
        assertNull(shadowOf(context).nextStartedActivity)
    }

    @Test
    fun detectsWhenUnknownSourcePermissionIsGrantedOrRevoked() {
        shadowOf(context.packageManager).setCanRequestPackageInstalls(true)
        assertTrue(installer.canRequestInstalls())

        shadowOf(context.packageManager).setCanRequestPackageInstalls(false)
        assertFalse(installer.canRequestInstalls())
    }

    @Test
    @Config(shadows = [UpdateFileProviderShadow::class])
    fun installIntentGrantsReadAccessToTheCachedApkWithoutLaunchingIt() {
        val intent = installer.installIntent(file)

        assertEquals(context, UpdateFileProviderShadow.requestedContext)
        assertEquals("$applicationId.provider", UpdateFileProviderShadow.requestedAuthority)
        assertEquals(file, UpdateFileProviderShadow.requestedFile)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("application/vnd.android.package-archive", intent.type)
        assertEquals("content://update-test.provider/cache/verified.apk", intent.dataString)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertNull(shadowOf(context).nextStartedActivity)
    }

    private fun validatePolicy(
        release: AppUpdateRelease = this.release,
        installed: AppUpdatePackageMetadata = this.installed,
        archive: AppUpdatePackageMetadata = this.archive,
    ) {
        AppUpdateInstallPolicy.validate(applicationId, 35, release, installed, archive)
    }

    private fun validateFile(release: AppUpdateRelease = this.release) {
        runBlocking { installer.validate(file, release) }
    }

    private fun registerPackages(
        installedVersion: Int = 10,
        archiveSignatures: Array<Signature> = arrayOf(Signature("aa")),
    ) {
        shadowOf(context.packageManager).installPackage(packageInfo(installedVersion, arrayOf(Signature("aa"))))
        shadowOf(context.packageManager).setPackageArchiveInfo(file.absolutePath, packageInfo(11, archiveSignatures))
    }

    @Suppress("DEPRECATION")
    private fun packageInfo(
        version: Int,
        signers: Array<Signature>,
    ): PackageInfo =
        PackageInfo().apply {
            packageName = applicationId
            versionCode = version
            applicationInfo = ApplicationInfo(context.applicationInfo).apply { minSdkVersion = 26 }
            signatures = signers
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                signingInfo = Shadow.newInstanceOf(SigningInfo::class.java).apply { shadowOf(this).setSignatures(signers) }
            }
        }

    private fun assertFailure(
        reason: AppUpdateInstallFailure,
        block: () -> Unit,
    ) {
        val exception = assertThrows(AppUpdateInstallException::class.java, block)
        assertEquals(reason, exception.reason)
    }
}

// Config.NONE does not load app XML resources. Keep the URI factory as the test boundary;
// the installer must pass its own authority and file and preserve the provider's result.
@Implements(FileProvider::class, isInAndroidSdk = false)
class UpdateFileProviderShadow {
    companion object {
        var requestedContext: Context? = null
        var requestedAuthority: String? = null
        var requestedFile: File? = null

        @JvmStatic
        @Implementation
        fun getUriForFile(
            context: Context,
            authority: String,
            file: File,
        ): Uri {
            requestedContext = context
            requestedAuthority = authority
            requestedFile = file
            return Uri.parse("content://update-test.provider/cache/verified.apk")
        }
    }
}
