package dev.dimension.flare.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class GlassNavigationSurfaceTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun glassShowsBackdropColorsWhileKeepingForegroundSharp() {
        composeRule.setContent {
            MaterialTheme {
                val hazeState = rememberHazeState()
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(240.dp, 120.dp).testTag(GLASS_TAG)) {
                        Canvas(Modifier.matchParentSize().hazeSource(hazeState)) {
                            drawRect(Color.Red, size = Size(size.width / 2f, size.height))
                            drawRect(
                                Color.Blue,
                                topLeft = Offset(size.width / 2f, 0f),
                                size = Size(size.width / 2f, size.height),
                            )
                        }
                        GlassNavigationSurface(
                            hazeState = hazeState,
                            modifier = Modifier.matchParentSize(),
                            shape = RoundedCornerShape(24.dp),
                        ) {
                            Box(Modifier.size(16.dp).background(Color.White).testTag(FOREGROUND_TAG))
                        }
                    }
                }
            }
        }

        // Source capture and the glass RenderEffect are submitted on separate rendering passes.
        var pixels: PixelMap? = null
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onNodeWithTag(GLASS_TAG).captureToImage().toPixelMap().also { pixels = it }.let {
                val left = it[it.width / 4, it.height / 2]
                val right = it[it.width * 3 / 4, it.height / 2]
                left.red > left.blue + 0.08f && right.blue > right.red + 0.08f
            }
        }
        val glass = checkNotNull(pixels)
        val left = glass[glass.width / 4, glass.height / 2]
        val right = glass[glass.width * 3 / 4, glass.height / 2]
        assertTrue("Left backdrop should show through the glass: $left", left.red > left.blue + 0.08f)
        assertTrue("Right backdrop should show through the glass: $right", right.blue > right.red + 0.08f)
        assertTrue("Glass tint should soften the source's pure red: $left", left.green > 0.02f)
        assertTrue("Glass tint should soften the source's pure blue: $right", right.green > 0.02f)

        val foreground = composeRule.onNodeWithTag(FOREGROUND_TAG).captureToImage().toPixelMap()
        for (x in listOf(1, foreground.width / 2, foreground.width - 2)) {
            val pixel = foreground[x, foreground.height / 2]
            assertTrue(
                "The foreground must remain white and sharp at x=$x: $pixel",
                pixel.red > 0.98f && pixel.green > 0.98f && pixel.blue > 0.98f,
            )
        }
    }

    private companion object {
        const val GLASS_TAG = "glass-surface"
        const val FOREGROUND_TAG = "glass-foreground"
    }
}
