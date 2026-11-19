package dev.dimension.flare.ui.component.status

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import dev.dimension.flare.ui.model.TimelineCarouselLayout
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StatusMediaCarouselTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun keepsCurrentAndLookaheadItemWidthsIndependentDuringResize() {
        val ratios = listOf(2f, 3f)
        val spec = TimelineCarouselLayout.spec(ratios.size, ratios[0], ratios[1])
        val carousel = LazyListState()
        val currentWidth = mutableIntStateOf(200)
        val targetWidth = mutableIntStateOf(300)
        val lookaheadWidths = mutableListOf<Int>()
        val actualWidths = mutableListOf<Int>()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                LookaheadScope {
                    Box(
                        Modifier.height(100.dp).layout { measurable, constraints ->
                            val width = constraints.constrainWidth(if (isLookingAhead) targetWidth.intValue else currentWidth.intValue)
                            val box = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
                            layout(box.width, box.height) { box.placeRelative(0, 0) }
                        },
                    ) {
                        StatusMediaCarousel(
                            state = carousel,
                            layoutSpec = spec,
                            itemCount = ratios.size,
                            itemAspectRatio = ratios::get,
                            leadingPadding = 0.dp,
                            trailingPadding = 0.dp,
                        ) { index, modifier ->
                            Box(
                                modifier.layout { measurable, constraints ->
                                    val item = measurable.measure(constraints)
                                    if (index == 0) (if (isLookingAhead) lookaheadWidths else actualWidths).add(item.width)
                                    layout(item.width, item.height) { item.placeRelative(0, 0) }
                                },
                            )
                        }
                    }
                }
            }
        }
        compose.runOnIdle {
            assertEquals(160, actualWidths.last())
            assertEquals(240, lookaheadWidths.last())
            currentWidth.intValue = 220
        }
        compose.runOnIdle {
            assertEquals(176, actualWidths.last())
            assertTrue(lookaheadWidths.all { it == 240 }, lookaheadWidths.toString())
            targetWidth.intValue = 320
        }
        compose.runOnIdle {
            assertEquals(176, actualWidths.last())
            assertEquals(256, lookaheadWidths.last())
        }
    }

    @Test
    fun layoutWidthChangesRemeasureItemsWithoutRecomposingThemWhenHeightIsClamped() {
        val ratios = listOf(2f, 3f)
        val spec = TimelineCarouselLayout.spec(ratios.size, ratios[0], ratios[1])
        val carousel = LazyListState()
        var width by mutableStateOf(200.dp)
        val sizes = mutableListOf<IntSize>()
        val firstItemWidthByViewport = mutableMapOf<Int, Int>()
        var compositions = 0
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                Box(
                    Modifier.height(100.dp).layout { measurable, constraints ->
                        val widthPx = constraints.constrainWidth(width.roundToPx())
                        val box = measurable.measure(constraints.copy(minWidth = widthPx, maxWidth = widthPx))
                        firstItemWidthByViewport.getOrPut(widthPx) {
                            Snapshot.withoutReadObservation { carousel.layoutInfo.visibleItemsInfo.first { it.index == 0 }.size }
                        }
                        layout(box.width, box.height) { box.placeRelative(0, 0) }
                    },
                ) {
                    StatusMediaCarousel(
                        state = carousel,
                        layoutSpec = spec,
                        itemCount = ratios.size,
                        itemAspectRatio = ratios::get,
                        leadingPadding = 12.dp,
                        trailingPadding = 20.dp,
                    ) { index, modifier ->
                        if (index == 0) SideEffect { compositions++ }
                        Box(modifier.onSizeChanged { if (index == 0) sizes += it })
                    }
                }
            }
        }
        val initialCompositions = compose.runOnIdle {
            assertEquals(listOf(IntSize(134, 100)), sizes)
            compositions.also { width = 300.dp }
        }
        compose.runOnIdle {
            assertEquals(listOf(IntSize(134, 100), IntSize(214, 100)), sizes)
            assertEquals(mapOf(200 to 134, 300 to 214), firstItemWidthByViewport)
            assertEquals(initialCompositions, compositions)
        }
    }
}
