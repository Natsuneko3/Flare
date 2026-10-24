package dev.dimension.flare.common

import android.app.Application
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import okio.Buffer
import okio.ByteString.Companion.decodeHex
import okio.FileSystem
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
class AnimatedDecoderFactoryTest {
    private lateinit var imageLoader: ImageLoader
    private lateinit var options: Options

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        imageLoader = ImageLoader.Builder(context).build()
        options = Options(context)
    }

    @After
    fun tearDown() {
        imageLoader.shutdown()
    }

    @Test
    fun jpegSkipsBothAnimationParsersWithoutCreatingATemporaryFile() {
        val jpeg = "ffd8ffe000104a46494600010100000100010000".decodeHex().toByteArray() + ByteArray(64 * 1024)
        assertRejectedWithoutMaterializing(AnimatedPngDecoder.Factory(), jpeg, mimeType = "image/png")
        assertRejectedWithoutMaterializing(AnimatedWebPDecoder.Factory(), jpeg, mimeType = "image/webp")
    }

    @Test
    fun unrelatedAndTruncatedSourcesRemainAvailableToOtherDecoders() {
        val sources =
            listOf(
                byteArrayOf(),
                "GIF89a".toByteArray(),
                "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".toByteArray(),
                "89504e470d0a1a".decodeHex().toByteArray(),
                "5249464600000000574542".decodeHex().toByteArray(),
            )
        for (bytes in sources) {
            assertRejectedWithoutMaterializing(AnimatedPngDecoder.Factory(), bytes)
            assertRejectedWithoutMaterializing(AnimatedWebPDecoder.Factory(), bytes)
        }
    }

    @Test
    fun riffWaveIsNotMistakenForWebP() {
        assertRejectedWithoutMaterializing(
            AnimatedWebPDecoder.Factory(),
            "524946461000000057415645666d7420".decodeHex().toByteArray(),
        )
    }

    @Test
    fun apngAnimationHeaderIsRecognizedWithoutDependingOnMimeType() {
        // PNG signature followed by an acTL chunk declaring one frame and infinite looping.
        val bytes = "89504e470d0a1a0a000000086163544c0000000100000000b42de9a0".decodeHex().toByteArray()
        for (mimeType in listOf(null, "image/jpeg")) {
            withSource(bytes, mimeType) { source, result ->
                assertNotNull(AnimatedPngDecoder.Factory().create(result, options, imageLoader))
                assertArrayEquals(bytes, source.source().readByteArray())
            }
        }
    }

    @Test
    fun webPAnimationHeaderIsRecognizedWithoutDependingOnMimeType() {
        // RIFF/WEBP followed by VP8X for a 1x1 canvas, with the animation flag set.
        val bytes = "524946461600000057454250565038580a00000002000000000000000000".decodeHex().toByteArray()
        for (mimeType in listOf(null, "image/jpeg")) {
            withSource(bytes, mimeType) { source, result ->
                assertNotNull(AnimatedWebPDecoder.Factory().create(result, options, imageLoader))
                assertArrayEquals(bytes, source.source().readByteArray())
            }
        }
    }

    @Test
    fun matchingSignaturesStillRequireAnimationMetadata() {
        // An ordinary PNG with only an IEND chunk and a WebP VP8X header without animation.
        val png = "89504e470d0a1a0a0000000049454e44ae426082".decodeHex().toByteArray()
        val webP = "524946461600000057454250565038580a00000000000000000000000000".decodeHex().toByteArray()
        withSource(png) { _, result ->
            assertNull(AnimatedPngDecoder.Factory().create(result, options, imageLoader))
        }
        withSource(webP) { _, result ->
            assertNull(AnimatedWebPDecoder.Factory().create(result, options, imageLoader))
        }
    }

    private fun assertRejectedWithoutMaterializing(
        factory: Decoder.Factory,
        bytes: ByteArray,
        mimeType: String? = null,
    ) {
        withSource(bytes, mimeType) { source, result ->
            assertNull(factory.create(result, options, imageLoader))
            assertNull("An unrelated image must not be copied to a temporary file", source.fileOrNull())
            assertArrayEquals("The next decoder must receive the original bytes", bytes, source.source().readByteArray())
        }
    }

    private fun withSource(
        bytes: ByteArray,
        mimeType: String? = null,
        block: (ImageSource, SourceFetchResult) -> Unit,
    ) {
        ImageSource(Buffer().write(bytes), FileSystem.SYSTEM).use { source ->
            block(source, SourceFetchResult(source, mimeType, DataSource.NETWORK))
        }
    }
}
