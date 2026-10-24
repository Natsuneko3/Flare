package dev.dimension.flare.ui.component

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.compose.KoinApplication
import org.koin.core.context.stopKoin
import org.koin.dsl.koinConfiguration
import org.koin.dsl.module
import java.io.File
import kotlin.math.abs

/** Exercises decoded pixels on the device, including reuse of the timeline's shared player. */
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class VideoPlayerAspectRatioTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val cache = Media3VideoCacheManager(context)
    private lateinit var manager: SurfaceBindingManager

    @Before
    fun wakeScreen() {
        for (command in listOf("input keyevent KEYCODE_WAKEUP", "wm dismiss-keyguard")) {
            android.os.ParcelFileDescriptor
                .AutoCloseInputStream(
                    instrumentation.uiAutomation.executeShellCommand(command),
                ).use { it.readBytes() }
        }
    }

    @After
    fun releasePlayer() {
        if (::manager.isInitialized) instrumentation.runOnMainSync { manager.close() }
        stopKoin()
        cache.cache.release()
    }

    @Test
    fun decodedPixelsKeepTheirAspectRatioWhenPreviewSizeAndVideoChange() {
        val portrait = fixture("portrait-square.mp4")
        val landscape = fixture("landscape-square.mp4")
        val uri = mutableStateOf(portrait)
        val portraitCard = mutableStateOf(false)
        val scale = mutableStateOf<ContentScale>(ContentScale.Crop)
        instrumentation.runOnMainSync { manager = SurfaceBindingManager(context, cache) }
        val playerModule = module { single { manager } }
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    KoinApplication(configuration = koinConfiguration { modules(playerModule) }) {
                        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                            VideoPlayer(
                                uri = uri.value,
                                previewUri = null,
                                contentDescription = "Aspect ratio regression video",
                                muted = true,
                                keepScreenOn = true,
                                contentScale = scale.value,
                                modifier = if (portraitCard.value) Modifier.size(180.dp, 320.dp) else Modifier.size(320.dp, 180.dp),
                            )
                        }
                    }
                }
            }
            assertSquarePixels("portrait-crop-landscape", sourceWidth = 360)
            instrumentation.runOnMainSync { portraitCard.value = true }
            assertSquarePixels("portrait-crop-portrait", sourceWidth = 360)
            instrumentation.runOnMainSync { uri.value = landscape }
            assertSquarePixels("landscape-crop-portrait", sourceWidth = 640)
            instrumentation.runOnMainSync { scale.value = ContentScale.Fit }
            assertSquarePixels("landscape-fit-portrait", sourceWidth = 640)
            instrumentation.runOnMainSync { portraitCard.value = false }
            assertSquarePixels("landscape-fit-landscape", sourceWidth = 640)
            instrumentation.runOnMainSync { uri.value = portrait }
            assertSquarePixels("portrait-fit-landscape", sourceWidth = 360)
        }
    }

    @Test
    fun recycledTimelineCardsKeepDecodedPixelsSquare() {
        val clips =
            List(24) { index ->
                fixture(if (index % 2 == 0) "portrait-square.mp4" else "landscape-square.mp4", "card-$index.mp4")
            }
        val selected = mutableStateOf(0)
        val grid = LazyStaggeredGridState()
        val scope = CoroutineScope(Dispatchers.Main)
        val playback = TimelinePlaybackCoordinator(scope)
        playback.multipleColumns = true
        instrumentation.runOnMainSync { manager = SurfaceBindingManager(context, cache) }
        val playerModule = module { single { manager } }
        try {
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        KoinApplication(configuration = koinConfiguration { modules(playerModule) }) {
                            LaunchedEffect(grid, playback) {
                                snapshotFlow { grid.isScrollInProgress }.distinctUntilChanged().collect { scrolling ->
                                    playback.setScrolling(grid, scrolling, vertical = true)
                                }
                            }
                            CompositionLocalProvider(LocalTimelinePlayback provides playback) {
                                LazyVerticalStaggeredGrid(
                                    columns = StaggeredGridCells.Fixed(2),
                                    state = grid,
                                    modifier =
                                        Modifier
                                            .fillMaxSize()
                                            .background(Color.Black)
                                            .onGloballyPositioned { playback.viewport = it.boundsInWindow() },
                                ) {
                                    items(count = 24, key = { it }, contentType = { "video" }) { index ->
                                        val isPortrait = index % 2 == 0
                                        VideoPlayer(
                                            uri = clips[index],
                                            previewUri = null,
                                            contentDescription = null,
                                            autoPlay = selected.value == index,
                                            muted = true,
                                            keepScreenOn = true,
                                            modifier =
                                                Modifier
                                                    .fillMaxWidth()
                                                    .aspectRatio(if (isPortrait) 9f / 16f else 16f / 9f)
                                                    .clip(RoundedCornerShape(16.dp)),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                for (index in listOf(0, 1, 6, 7, 12, 13, 0, 1)) {
                    runBlocking {
                        withContext(Dispatchers.Main) {
                            selected.value = index
                            grid.scrollToItem(index)
                        }
                    }
                    assertSquarePixels(
                        "recycled-card-$index",
                        sourceWidth = if (index % 2 == 0) 360 else 640,
                        sourceUri = clips[index],
                    )
                }
            }
        } finally {
            instrumentation.runOnMainSync { playback.close() }
            scope.cancel()
        }
    }

    private fun fixture(
        name: String,
        targetName: String = name,
    ): String {
        val file = File(context.cacheDir, targetName)
        instrumentation.context.assets
            .open(name)
            .use { input -> file.outputStream().use { input.copyTo(it) } }
        return file.toURI().toString()
    }

    private fun assertSquarePixels(
        label: String,
        sourceWidth: Int,
        sourceUri: String? = null,
    ) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        var ready = false
        while (!ready && SystemClock.elapsedRealtime() < deadline) {
            instrumentation.runOnMainSync {
                ready =
                    manager.player.isPlaying && manager.player.videoSize.width == sourceWidth &&
                    (
                        sourceUri == null || manager.player.currentMediaItem
                            ?.localConfiguration
                            ?.uri
                            .toString() == sourceUri
                    )
            }
            if (!ready) Thread.sleep(20)
        }
        assertTrue("$label: video did not start", ready)
        // Allow the decoder and the native surface transaction to display several frames.
        Thread.sleep(800)
        val frameDeadline = SystemClock.elapsedRealtime() + 10_000
        var marker: Pair<Int, Int>?
        do {
            marker = captureMarker(label)
            if (marker == null) Thread.sleep(100)
        } while (marker == null && SystemClock.elapsedRealtime() < frameDeadline)
        assertTrue("$label: decoded green marker was not visible", marker != null)
        val (width, height) = checkNotNull(marker)
        android.util.Log.i("VideoAspectRatioTest", "$label: marker=${width}x$height")
        assertTrue("$label: square stretched to ${width}x$height", abs(width - height) <= 3)
    }

    private fun captureMarker(label: String): Pair<Int, Int>? {
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            var left = bitmap.width
            var top = bitmap.height
            var right = -1
            var bottom = -1
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            for (y in 0 until bitmap.height) {
                for (x in 0 until bitmap.width) {
                    val pixel = pixels[y * bitmap.width + x]
                    if (
                        android.graphics.Color.green(pixel) > 190 &&
                        android.graphics.Color.red(pixel) < 70 &&
                        android.graphics.Color.blue(pixel) < 70
                    ) {
                        left = minOf(left, x)
                        top = minOf(top, y)
                        right = maxOf(right, x)
                        bottom = maxOf(bottom, y)
                    }
                }
            }
            if (right <= left || bottom <= top) return null
            val output = File(context.getExternalFilesDir(null), "aspect-ratio-$label.png")
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            return (right - left + 1) to (bottom - top + 1)
        } finally {
            bitmap.recycle()
        }
    }
}
