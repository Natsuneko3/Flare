package dev.dimension.flare.data.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.nio.file.Files

class AppUpdateRepositoryTest {
    private val cacheDir = Files.createTempDirectory("flare-update-test-").toFile()

    @After
    fun tearDown() {
        cacheDir.deleteRecursively()
    }

    @Test
    fun constructionDoesNotCheckForUpdates() {
        val transport = FakeTransport()

        repository(transport)

        assertTrue(transport.urls.isEmpty())
    }

    @Test
    fun explicitCheckReturnsANewerCompatibleReleaseFromTheFixedEndpoint() {
        val response = Response(json())
        val transport = FakeTransport(response)

        val result = check(repository(transport))

        assertEquals(release(), result)
        assertEquals(listOf("https://github.com/Natsuneko3/Flare/releases/latest/download/flare-update.json"), transport.urls)
        assertTrue(response.closed)
    }

    @Test
    fun equalOrOlderVersionDoesNotOfferAnUpdate() {
        for (current in listOf(101L, 102L)) {
            assertNull(runBlocking { repository(FakeTransport(Response(json()))).checkForUpdate(current, "dev.dimension.flare", 35) })
        }
    }

    @Test
    fun rejectsMetadataForAnotherApplication() {
        assertFailure(AppUpdateFailure.INVALID_METADATA) {
            check(repository(FakeTransport(Response(json().replace("dev.dimension.flare", "com.other.app")))))
        }
    }

    @Test
    fun reportsIncompatibleAndroidVersion() {
        assertFailure(AppUpdateFailure.INCOMPATIBLE_DEVICE) {
            runBlocking { repository(FakeTransport(Response(json()))).checkForUpdate(100, "dev.dimension.flare", 25) }
        }
    }

    @Test
    fun missingReleaseHasADistinctFailureAndClosesTheResponse() {
        val response = Response("", statusCode = 404)

        assertFailure(AppUpdateFailure.NO_RELEASE) { check(repository(FakeTransport(response))) }
        assertTrue(response.closed)
    }

    @Test
    fun rejectsMalformedOrUnsupportedMetadata() {
        val invalidDocuments =
            listOf(
                "not json",
                json().replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
                json().replace("\"versionCode\":101", "\"versionCode\":0"),
                json().replace("\"versionName\":\"1.0.1\"", "\"versionName\":\"\""),
                json().replace("\"minSdk\":26", "\"minSdk\":0"),
                json().replace("\"sizeBytes\":5", "\"sizeBytes\":0"),
                json().replace(HELLO_SHA256, HELLO_SHA256.uppercase()),
                json().replace(HELLO_SHA256, "1234"),
            )

        for (document in invalidDocuments) {
            assertFailure(AppUpdateFailure.INVALID_METADATA) { check(repository(FakeTransport(Response(document)))) }
        }
    }

    @Test
    fun rejectsUnsafeReleaseTagsAndApkNamesBeforeDownloading() {
        val unsafeReleases =
            listOf(
                release().copy(releaseTag = "../elsewhere"),
                release().copy(releaseTag = "v1%2fother"),
                release().copy(releaseTag = "v1?query"),
                release().copy(apkFileName = "../flare.apk"),
                release().copy(apkFileName = "flare.apk#fragment"),
                release().copy(apkFileName = "flare.zip"),
            )
        for (release in unsafeReleases) {
            val transport = FakeTransport()
            assertFailure(AppUpdateFailure.INVALID_METADATA) { download(repository(transport), release) }
            assertTrue(transport.urls.isEmpty())
        }
    }

    @Test
    fun rejectsMetadataLargerThan64KiBEvenWithoutContentLength() {
        val response = Response(" ".repeat(65537), contentLength = -1)

        assertFailure(AppUpdateFailure.TOO_LARGE) { check(repository(FakeTransport(response))) }
        assertTrue(response.closed)
    }

    @Test
    fun rejectsApksLargerThan256MiBBeforeDownloading() {
        val transport = FakeTransport()

        assertFailure(AppUpdateFailure.TOO_LARGE) {
            download(repository(transport), release().copy(sizeBytes = 256L * 1024 * 1024 + 1))
        }
        assertTrue(transport.urls.isEmpty())
    }

    @Test
    fun permitsGitHubAssetRedirectsAndClosesEveryResponse() {
        val redirect =
            Response(
                "",
                statusCode = 302,
                location = "https://release-assets.githubusercontent.com/github-production-release-asset/1?sig=value",
            )
        val result = Response(json())
        val transport = FakeTransport(redirect, result)

        assertNotNull(check(repository(transport)))
        assertEquals(2, transport.urls.size)
        assertTrue(redirect.closed)
        assertTrue(result.closed)
    }

    @Test
    fun rejectsUntrustedOrInsecureRedirectsWithoutFollowingThem() {
        for (location in listOf(
            "http://github.com/x",
            "https://evil.example/x",
            "https://github.com.evil.example/x",
            "https://user@github.com/x",
            "https://github.com:444/x",
        )) {
            val response = Response("", statusCode = 302, location = location)
            val transport = FakeTransport(response)

            assertFailure(AppUpdateFailure.INVALID_URL) { check(repository(transport)) }
            assertEquals(1, transport.urls.size)
            assertTrue(response.closed)
        }
    }

    @Test
    fun terminatesRedirectLoops() {
        val responses = Array(6) { Response("", statusCode = 302, location = "https://github.com/loop") }
        val transport = FakeTransport(*responses)

        assertFailure(AppUpdateFailure.HTTP_ERROR) { check(repository(transport)) }
        assertEquals(6, transport.urls.size)
        assertTrue(responses.all { it.closed })
    }

    @Test
    fun downloadsOnlyTheDerivedAssetUrlAndPublishesVerifiedBytes() {
        val response = Response("hello")
        val transport = FakeTransport(response)
        val progress = mutableListOf<Pair<Long, Long>>()

        val file = runBlocking { repository(transport).download(release(), cacheDir) { bytes, total -> progress += bytes to total } }

        assertEquals(listOf("https://github.com/Natsuneko3/Flare/releases/download/v1.0.1/flare.apk"), transport.urls)
        assertArrayEquals("hello".toByteArray(), file.readBytes())
        assertEquals(cacheDir.canonicalFile, file.parentFile.canonicalFile)
        assertTrue(file.name.endsWith(".apk"))
        assertTrue(progress.isNotEmpty())
        assertEquals(5L to 5L, progress.last())
        assertTrue(progress.all { it.first in 0L..5L && it.second == 5L })
        assertTrue(response.closed)
        assertFalse(cacheDir.listFiles().orEmpty().any { it.name.endsWith(".part") })
    }

    @Test
    fun checksumMismatchRemovesThePartialDownload() {
        assertFailure(AppUpdateFailure.CHECKSUM_MISMATCH) {
            download(repository(FakeTransport(Response("HELLO"))))
        }
        assertTrue(cacheDir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun truncatedOrOverlongBodyRemovesThePartialDownload() {
        for (body in listOf("hell", "hello!")) {
            assertFailure(AppUpdateFailure.SIZE_MISMATCH) {
                download(repository(FakeTransport(Response(body, contentLength = -1))))
            }
            assertTrue(cacheDir.listFiles().orEmpty().isEmpty())
        }
    }

    @Test
    fun mismatchingHttpContentLengthCannotProduceAnApk() {
        val response = Response("hello", contentLength = 7)

        assertFailure(AppUpdateFailure.SIZE_MISMATCH) { download(repository(FakeTransport(response))) }
        assertTrue(cacheDir.listFiles().orEmpty().isEmpty())
        assertTrue(response.closed)
    }

    @Test
    fun cancellationPropagatesAndRemovesThePartialDownload() {
        val response = Response("hello")
        val cancellation = CancellationException("User dismissed update")

        val actual =
            assertThrows(CancellationException::class.java) {
                runBlocking { repository(FakeTransport(response)).download(release(), cacheDir) { _, _ -> throw cancellation } }
            }

        assertEquals(cancellation.message, actual.message)
        assertTrue(cacheDir.listFiles().orEmpty().isEmpty())
        assertTrue(response.closed)
    }

    @Test
    fun readFailureClosesResponseAndRemovesThePartialDownload() {
        val response =
            Response(
                contentLength = -1,
                stream =
                    object : InputStream() {
                        override fun read(): Int = throw IOException("Connection interrupted")
                    },
            )

        assertFailure(AppUpdateFailure.NETWORK) { download(repository(FakeTransport(response))) }
        assertTrue(cacheDir.listFiles().orEmpty().isEmpty())
        assertTrue(response.closed)
    }

    @Test
    fun aFailedDownloadPreservesExistingFiles() {
        val existing = File(cacheDir, "previous.apk").apply { writeText("previous verified update") }

        assertFailure(AppUpdateFailure.CHECKSUM_MISMATCH) { download(repository(FakeTransport(Response("HELLO")))) }

        assertEquals("previous verified update", existing.readText())
        assertEquals(listOf(existing), cacheDir.listFiles().orEmpty().toList())
    }

    private fun repository(transport: UpdateHttpTransport): AppUpdateRepository = AppUpdateRepository(transport, Dispatchers.Unconfined)

    private fun check(repository: AppUpdateRepository): AppUpdateRelease? =
        runBlocking { repository.checkForUpdate(100, "dev.dimension.flare", 35) }

    private fun download(
        repository: AppUpdateRepository,
        release: AppUpdateRelease = release(),
    ): File = runBlocking { repository.download(release, cacheDir) { _, _ -> } }

    private fun assertFailure(
        reason: AppUpdateFailure,
        block: () -> Unit,
    ) {
        assertEquals(reason, assertThrows(AppUpdateException::class.java, block).reason)
    }

    private fun release(): AppUpdateRelease =
        AppUpdateRelease(1, "dev.dimension.flare", 101, "1.0.1", 26, "flare.apk", HELLO_SHA256, 5, "v1.0.1", "Fixes and improvements")

    private fun json(): String =
        """{"schemaVersion":1,"applicationId":"dev.dimension.flare","versionCode":101,"versionName":"1.0.1","minSdk":26,"apkFileName":"flare.apk","sha256":"$HELLO_SHA256","sizeBytes":5,"releaseTag":"v1.0.1","releaseNotes":"Fixes and improvements"}"""

    private class FakeTransport(
        vararg responses: UpdateHttpResponse,
    ) : UpdateHttpTransport {
        private val responses = ArrayDeque(responses.toList())
        val urls = mutableListOf<String>()

        override fun open(url: URL): UpdateHttpResponse {
            urls += url.toExternalForm()
            return responses.removeFirst()
        }
    }

    private class Response(
        text: String = "",
        override val statusCode: Int = 200,
        override val contentLength: Long = text.toByteArray().size.toLong(),
        override val location: String? = null,
        stream: InputStream = ByteArrayInputStream(text.toByteArray()),
    ) : UpdateHttpResponse {
        override val body = stream
        var closed = false

        override fun close() {
            closed = true
            body.close()
        }
    }

    private companion object {
        const val HELLO_SHA256 = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
    }
}
