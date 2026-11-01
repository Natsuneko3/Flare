package dev.dimension.flare.data.update

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import javax.net.ssl.HttpsURLConnection

@Serializable
internal data class AppUpdateRelease(
    val schemaVersion: Int,
    val applicationId: String,
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int,
    val apkFileName: String,
    val sha256: String,
    val sizeBytes: Long,
    val releaseTag: String,
    val releaseNotes: String,
)

internal enum class AppUpdateFailure {
    NO_RELEASE,
    NETWORK,
    INVALID_METADATA,
    INCOMPATIBLE_DEVICE,
    HTTP_ERROR,
    INVALID_URL,
    TOO_LARGE,
    SIZE_MISMATCH,
    CHECKSUM_MISMATCH,
    STORAGE_ERROR,
}

internal class AppUpdateException(
    val reason: AppUpdateFailure,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

internal fun interface UpdateHttpTransport {
    fun open(url: URL): UpdateHttpResponse
}

internal interface UpdateHttpResponse : Closeable {
    val statusCode: Int
    val contentLength: Long
    val location: String?
    val body: InputStream
}

internal interface AppUpdateSource {
    suspend fun checkForUpdate(
        currentVersionCode: Long,
        applicationId: String,
        sdkInt: Int,
    ): AppUpdateRelease?

    suspend fun download(
        release: AppUpdateRelease,
        cacheDir: File,
        onProgress: (bytes: Long, total: Long) -> Unit,
    ): File
}

internal class AppUpdateRepository(
    private val transport: UpdateHttpTransport = HttpsUpdateHttpTransport(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AppUpdateSource {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun checkForUpdate(
        currentVersionCode: Long,
        applicationId: String,
        sdkInt: Int,
    ): AppUpdateRelease? =
        withContext(ioDispatcher) {
            request(CHECK_TIMEOUT_MILLIS) {
                val release =
                    openFollowingRedirects(URL(METADATA_URL)).use { response ->
                        if (response.statusCode == 404) {
                            throw AppUpdateException(AppUpdateFailure.NO_RELEASE, "No update release has been published")
                        }
                        requireSuccessful(response)
                        val document = readMetadata(response)
                        try {
                            json.decodeFromString<AppUpdateRelease>(document)
                        } catch (error: SerializationException) {
                            throw AppUpdateException(AppUpdateFailure.INVALID_METADATA, "Invalid update metadata", error)
                        } catch (error: IllegalArgumentException) {
                            throw AppUpdateException(AppUpdateFailure.INVALID_METADATA, "Invalid update metadata", error)
                        }
                    }
                validateRelease(release)
                if (release.applicationId != applicationId) {
                    throw AppUpdateException(AppUpdateFailure.INVALID_METADATA, "The release is for another application")
                }
                when {
                    release.versionCode <= currentVersionCode -> {
                        null
                    }

                    release.minSdk > sdkInt -> {
                        throw AppUpdateException(AppUpdateFailure.INCOMPATIBLE_DEVICE, "The update requires a newer Android version")
                    }

                    else -> {
                        release
                    }
                }
            }
        }

    override suspend fun download(
        release: AppUpdateRelease,
        cacheDir: File,
        onProgress: (bytes: Long, total: Long) -> Unit,
    ): File =
        withContext(ioDispatcher) {
            validateRelease(release)
            request(DOWNLOAD_TIMEOUT_MILLIS) {
                var partialFile: File? = null
                try {
                    openFollowingRedirects(URL("$RELEASES_URL/${release.releaseTag}/${release.apkFileName}")).use { response ->
                        requireSuccessful(response)
                        if (response.contentLength > MAX_APK_BYTES) {
                            throw AppUpdateException(AppUpdateFailure.TOO_LARGE, "The APK exceeds the download size limit")
                        }
                        if (response.contentLength >= 0 && response.contentLength != release.sizeBytes) {
                            throw AppUpdateException(AppUpdateFailure.SIZE_MISMATCH, "The APK size differs from the release metadata")
                        }
                        partialFile =
                            storage {
                                if (!cacheDir.isDirectory && !cacheDir.mkdirs()) {
                                    throw IOException("Cannot create update cache directory")
                                }
                                File.createTempFile("flare-${release.versionCode}-", ".part", cacheDir)
                            }
                        val digest = MessageDigest.getInstance("SHA-256")
                        var downloaded = 0L
                        storage { FileOutputStream(checkNotNull(partialFile)) }.use { output ->
                            val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                            onProgress(0, release.sizeBytes)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = response.body.read(buffer)
                                currentCoroutineContext().ensureActive()
                                if (count < 0) break
                                if (count == 0) continue
                                downloaded += count
                                if (downloaded > release.sizeBytes) {
                                    throw AppUpdateException(AppUpdateFailure.SIZE_MISMATCH, "The APK contains more bytes than expected")
                                }
                                storage { output.write(buffer, 0, count) }
                                digest.update(buffer, 0, count)
                                onProgress(downloaded, release.sizeBytes)
                            }
                            storage { output.fd.sync() }
                        }
                        if (downloaded != release.sizeBytes) {
                            throw AppUpdateException(AppUpdateFailure.SIZE_MISMATCH, "The APK download is incomplete")
                        }
                        val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
                        if (actualHash != release.sha256) {
                            throw AppUpdateException(
                                AppUpdateFailure.CHECKSUM_MISMATCH,
                                "The APK checksum does not match the release metadata",
                            )
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    val completed = File(cacheDir, "flare-${release.versionCode}-${release.sha256}.apk")
                    storage {
                        Files.move(
                            checkNotNull(partialFile).toPath(),
                            completed.toPath(),
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING,
                        )
                    }
                    completed
                } finally {
                    partialFile?.delete()
                }
            }
        }

    private fun validateRelease(release: AppUpdateRelease) {
        if (
            release.schemaVersion != 1 ||
            release.applicationId.isBlank() ||
            release.versionCode <= 0 ||
            release.versionName.isBlank() ||
            release.versionName.length > 256 ||
            release.minSdk <= 0 ||
            release.sizeBytes <= 0 ||
            !SAFE_PATH_SEGMENT.matches(release.releaseTag) ||
            !SAFE_PATH_SEGMENT.matches(release.apkFileName) ||
            !release.apkFileName.endsWith(".apk") ||
            !SHA256.matches(release.sha256)
        ) {
            throw AppUpdateException(AppUpdateFailure.INVALID_METADATA, "The update metadata contains invalid fields")
        }
        if (release.sizeBytes > MAX_APK_BYTES) {
            throw AppUpdateException(AppUpdateFailure.TOO_LARGE, "The APK exceeds the download size limit")
        }
    }

    private suspend fun readMetadata(response: UpdateHttpResponse): String {
        if (response.contentLength > MAX_METADATA_BYTES) {
            throw AppUpdateException(AppUpdateFailure.TOO_LARGE, "The update metadata exceeds the size limit")
        }
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = response.body.read(buffer)
            currentCoroutineContext().ensureActive()
            if (count < 0) break
            if (count == 0) continue
            if (output.size() + count > MAX_METADATA_BYTES) {
                throw AppUpdateException(AppUpdateFailure.TOO_LARGE, "The update metadata exceeds the size limit")
            }
            output.write(buffer, 0, count)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private suspend fun openFollowingRedirects(initialUrl: URL): UpdateHttpResponse {
        var url = initialUrl
        for (redirectCount in 0..MAX_REDIRECTS) {
            currentCoroutineContext().ensureActive()
            requireAllowedUrl(url)
            val response = transport.open(url)
            if (response.statusCode !in REDIRECT_STATUS_CODES) return response
            response.use {
                if (redirectCount == MAX_REDIRECTS) {
                    throw AppUpdateException(AppUpdateFailure.HTTP_ERROR, "Too many update redirects")
                }
                val location = response.location
                if (location.isNullOrBlank()) {
                    throw AppUpdateException(AppUpdateFailure.HTTP_ERROR, "The update redirect is missing its destination")
                }
                url =
                    try {
                        URL(url, location)
                    } catch (error: IOException) {
                        throw AppUpdateException(AppUpdateFailure.INVALID_URL, "The update redirect is invalid", error)
                    }
                requireAllowedUrl(url)
            }
        }
        throw AppUpdateException(AppUpdateFailure.HTTP_ERROR, "Too many update redirects")
    }

    private fun requireAllowedUrl(url: URL) {
        if (
            url.protocol != "https" ||
            url.host.lowercase(Locale.ROOT) !in ALLOWED_HOSTS ||
            url.port !in listOf(-1, 443) ||
            url.userInfo != null ||
            url.ref != null
        ) {
            throw AppUpdateException(AppUpdateFailure.INVALID_URL, "The update URL is not trusted")
        }
        try {
            url.toURI()
        } catch (error: Exception) {
            throw AppUpdateException(AppUpdateFailure.INVALID_URL, "The update URL is invalid", error)
        }
    }

    private fun requireSuccessful(response: UpdateHttpResponse) {
        if (response.statusCode != 200) {
            throw AppUpdateException(AppUpdateFailure.HTTP_ERROR, "The update server returned HTTP ${response.statusCode}")
        }
    }

    private suspend fun <T> request(
        timeoutMillis: Long,
        block: suspend () -> T,
    ): T =
        try {
            withTimeout(timeoutMillis) { block() }
        } catch (error: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw AppUpdateException(AppUpdateFailure.NETWORK, "The update request timed out", error)
        } catch (error: IOException) {
            currentCoroutineContext().ensureActive()
            throw AppUpdateException(AppUpdateFailure.NETWORK, "The update request failed", error)
        }

    private fun <T> storage(block: () -> T): T =
        try {
            block()
        } catch (error: IOException) {
            throw AppUpdateException(AppUpdateFailure.STORAGE_ERROR, "Unable to save the update APK", error)
        } catch (error: SecurityException) {
            throw AppUpdateException(AppUpdateFailure.STORAGE_ERROR, "Unable to access the update cache", error)
        }

    private companion object {
        const val METADATA_URL = "https://github.com/Natsuneko3/Flare/releases/latest/download/flare-update.json"
        const val RELEASES_URL = "https://github.com/Natsuneko3/Flare/releases/download"
        const val MAX_METADATA_BYTES = 64 * 1024
        const val MAX_APK_BYTES = 256L * 1024 * 1024
        const val DOWNLOAD_BUFFER_BYTES = 64 * 1024
        const val CHECK_TIMEOUT_MILLIS = 60_000L
        const val DOWNLOAD_TIMEOUT_MILLIS = 10 * 60_000L
        const val MAX_REDIRECTS = 5
        val SAFE_PATH_SEGMENT = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        val SHA256 = Regex("[a-f0-9]{64}")
        val REDIRECT_STATUS_CODES = setOf(301, 302, 303, 307, 308)
        val ALLOWED_HOSTS = setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")
    }
}

private class HttpsUpdateHttpTransport : UpdateHttpTransport {
    override fun open(url: URL): UpdateHttpResponse {
        val connection = url.openConnection() as HttpsURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.useCaches = false
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("User-Agent", "Flare-Android-Updater")
            val responseCode = connection.responseCode
            val responseLength = connection.contentLengthLong
            val redirectLocation = connection.getHeaderField("Location")
            val responseBody = if (responseCode == 200) connection.inputStream else ByteArrayInputStream(byteArrayOf())
            return object : UpdateHttpResponse {
                override val statusCode = responseCode
                override val contentLength = responseLength
                override val location = redirectLocation
                override val body = responseBody

                override fun close() {
                    try {
                        body.close()
                    } finally {
                        connection.disconnect()
                    }
                }
            }
        } catch (error: Exception) {
            connection.disconnect()
            throw error
        }
    }
}
