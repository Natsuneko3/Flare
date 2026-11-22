package dev.dimension.flare.ui.screen.media

import android.graphics.Bitmap
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ComposeUiFlags
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import dev.dimension.flare.R
import dev.dimension.flare.data.model.ProxyRef
import dev.dimension.flare.data.model.appearance.GlobalAppearance
import dev.dimension.flare.data.network.ProxyNetwork
import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.component.LocalGlobalAppearance
import dev.dimension.flare.ui.component.media
import dev.dimension.flare.ui.model.UiMedia
import dev.dimension.flare.ui.model.toUiImage
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.UUID
import kotlin.random.Random

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalComposeUiApi::class)
class ImageLoadingIndicatorTest {
    private val originalMediaQueryIntegration = ComposeUiFlags.isMediaQueryIntegrationEnabled

    @get:Rule val compose = createComposeRule()

    @Before
    fun enableMediaQueries() {
        ComposeUiFlags.isMediaQueryIntegrationEnabled = true
    }

    @After
    fun restoreMediaQueryIntegration() {
        ComposeUiFlags.isMediaQueryIntegrationEnabled = originalMediaQueryIntegration
    }

    @Test
    fun fastLoadsStayHiddenAndKnownProgressReplacesTheAnimationInPlace() {
        val pending = mutableStateOf(true)
        val progress = mutableStateOf<Float?>(null)
        val description = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.media_image_loading)
        val indicator = compose.onNodeWithContentDescription(description)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialExpressiveTheme {
                ImageLoadingIndicator(pending.value) { progress.value }
            }
        }
        compose.mainClock.advanceTimeBy(160)
        indicator.assertDoesNotExist()
        compose.runOnIdle { pending.value = false }
        compose.mainClock.advanceTimeBy(600)
        indicator.assertDoesNotExist()

        compose.runOnIdle { pending.value = true }
        compose.mainClock.advanceTimeBy(1_000)
        val bounds = indicator.fetchSemanticsNode().boundsInRoot
        indicator.assert(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate))
        for (fraction in listOf(0.25f, 0.75f, 1f)) {
            compose.runOnIdle { progress.value = fraction }
            compose.mainClock.advanceTimeByFrame()
            indicator.assert(hasProgressBarRangeInfo(ProgressBarRangeInfo(fraction, 0f..1f)))
            assertEquals(bounds, indicator.fetchSemanticsNode().boundsInRoot)
        }
        compose.runOnIdle { pending.value = false }
        compose.mainClock.advanceTimeBy(1_000)
        indicator.assertDoesNotExist()
    }

    @Test
    fun loadingIndicatorMovesWithTheImageWhenSwipingToALocalImage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val description = context.getString(R.string.media_image_loading)
        // Leave the first response pending while dragging to the second page.
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val pending = requireNotNull("http://127.0.0.1:${server.localPort}/image".toUiImage(proxy = ProxyRef.Global))
            val local = requireNotNull("android.resource://${context.packageName}/${R.mipmap.ic_launcher}".toUiImage())
            showImages(pending, local)
            compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty() }
            val pager = compose.onNode(hasScrollToIndexAction())
            val viewport = pager.fetchSemanticsNode().boundsInRoot
            val indicator = compose.onNodeWithContentDescription(description)
            val center = indicator.fetchSemanticsNode().boundsInRoot.center
            assertEquals(viewport.center.x, center.x, 2f)
            assertEquals(viewport.center.y, center.y, 2f)

            pager.performTouchInput {
                down(this.center)
                moveBy(Offset(-width * 0.3f, 0f))
            }
            val dragged = indicator.fetchSemanticsNode().boundsInRoot.center
            assertTrue(dragged.x < center.x - viewport.width * 0.2f)
            assertEquals(center.y, dragged.y, 2f)
            pager.performTouchInput {
                moveBy(Offset(-width * 0.45f, 0f))
                up()
            }
            indicator.assertIsNotDisplayed()
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 28)
    fun viewerShowsTheProgressOfAnAlreadyDownloadingImage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val description = context.getString(R.string.media_image_loading)
        val random = Random(17)
        val bitmap = Bitmap.createBitmap(IntArray(256 * 256) { random.nextInt() }, 256, 256, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()

        fun awaitProgress(received: Int) {
            val range = ProgressBarRangeInfo(received.toFloat() / bytes.size, 0f..1f)
            compose.waitUntil(10_000) { compose.onAllNodes(hasProgressBarRangeInfo(range)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription(description).assert(hasProgressBarRangeInfo(range))
        }
        ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 10_000
            val url = "http://127.0.0.1:${server.localPort}/${UUID.randomUUID()}.png"
            val media = requireNotNull(url.toUiImage(proxy = ProxyRef.Global))
            val snapshot = runBlocking { ProxyNetwork.snapshot(media.proxy) }
            val preview =
                context.imageLoader.enqueue(
                    ImageRequest
                        .Builder(context)
                        .media(media, snapshot)
                        .size(64)
                        .build(),
                )
            try {
                server.accept().use { connection ->
                    connection.soTimeout = 10_000
                    val reader = connection.getInputStream().bufferedReader()
                    while (reader.readLine()?.isNotEmpty() == true) { }
                    val output = connection.getOutputStream()
                    val headers = "HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                    output.write(headers.toByteArray())
                    val chunk = 32 * 1024
                    output.write(bytes, 0, chunk)
                    output.flush()
                    showImages(media)
                    awaitProgress(chunk)
                    output.write(bytes, chunk, chunk)
                    output.flush()
                    awaitProgress(chunk * 2)
                    output.write(bytes, chunk * 2, bytes.size - chunk * 2)
                }
                assertTrue(runBlocking { withTimeout(10_000) { preview.job.await() } } is SuccessResult)
                compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isEmpty() }
                server.soTimeout = 250
                assertThrows(SocketTimeoutException::class.java) { server.accept().close() }
            } finally {
                preview.dispose()
            }
        }
    }

    @Test
    fun failedOriginalDismissesTheLoadingIndicator() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val description = context.getString(R.string.media_image_loading)
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 10_000
            showImages(requireNotNull("http://127.0.0.1:${server.localPort}/missing".toUiImage(proxy = ProxyRef.Global)))
            server.accept().use { connection ->
                connection.soTimeout = 10_000
                val reader = connection.getInputStream().bufferedReader()
                while (reader.readLine()?.isNotEmpty() == true) { }
                compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty() }
                connection.getOutputStream().write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            }
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isEmpty() }
        }
    }

    @Test
    fun failedPostLookupDismissesTheLoadingIndicator() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val description = context.getString(R.string.media_image_loading)
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 10_000
            val host = "127.0.0.1:${server.localPort}"
            compose.setContent {
                CompositionLocalProvider(LocalGlobalAppearance provides GlobalAppearance()) {
                    StatusMediaScreen(
                        statusKey = MicroBlogKey("123", host),
                        accountType = AccountType.GuestHost(host),
                        index = 0,
                        preview = "android.resource://${context.packageName}/${R.mipmap.ic_launcher}",
                        onDismiss = {},
                        toAltText = {},
                        uriHandler = LocalUriHandler.current,
                    )
                }
            }
            server.accept().use {
                compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty() }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithContentDescription(context.getString(R.string.navigate_back)).assertIsDisplayed()
    }

    private fun showImages(vararg images: UiMedia) {
        compose.setContent {
            CompositionLocalProvider(LocalGlobalAppearance provides GlobalAppearance()) {
                RawMediaScreen(persistentListOf(*images), 0, null, {}, {}, LocalUriHandler.current)
            }
        }
    }
}
