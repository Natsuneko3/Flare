package dev.dimension.flare.ui.component.adaptive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

@Stable
public class SplitPaneState internal constructor(
    initialMode: SplitPaneMode,
) {
    public var mode: SplitPaneMode by mutableStateOf(initialMode)
        private set

    internal fun updateMode(newMode: SplitPaneMode) {
        if (mode != newMode) {
            mode = newMode
        }
    }
}

@Composable
public fun rememberSplitPaneState(initialIsSplit: Boolean = false): SplitPaneState =
    remember {
        SplitPaneState(
            initialMode =
                if (initialIsSplit) {
                    SplitPaneMode.HorizontalSplit(primaryWidth = 0.dp, gapWidth = 0.dp, isFoldDriven = false)
                } else {
                    SplitPaneMode.Compact
                },
        )
    }

public fun partitionSplitPadding(
    mode: SplitPaneMode,
    contentPadding: PaddingValues,
    layoutDirection: LayoutDirection,
): Pair<PaddingValues, PaddingValues> {
    val start = contentPadding.calculateStartPadding(layoutDirection)
    val end = contentPadding.calculateEndPadding(layoutDirection)
    val top = contentPadding.calculateTopPadding()
    val bottom = contentPadding.calculateBottomPadding()
    return when (mode) {
        SplitPaneMode.Compact -> contentPadding to contentPadding
        is SplitPaneMode.HorizontalSplit ->
            PaddingValues(start = start, top = top, end = 0.dp, bottom = bottom) to
                PaddingValues(start = 0.dp, top = top, end = end, bottom = bottom)
        is SplitPaneMode.VerticalSplit ->
            PaddingValues(start = start, top = top, end = end, bottom = 0.dp) to
                PaddingValues(start = start, top = 0.dp, end = end, bottom = bottom)
    }
}

@Composable
public fun AdaptiveSplitLayout(
    primaryWidthStrategy: PrimaryWidthStrategy,
    modifier: Modifier = Modifier,
    state: SplitPaneState = rememberSplitPaneState(),
    isBigScreen: Boolean = dev.dimension.flare.ui.component.platform.isBigScreen(),
    hinge: FoldHinge? = rememberActiveFoldHinge(),
    unfoldedGap: Dp = 0.dp,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    divider: @Composable (SplitPaneMode) -> Unit = {},
    compactContent: @Composable (PaddingValues) -> Unit,
    primaryPane: @Composable (SplitPaneMode, PaddingValues) -> Unit,
    secondaryPane: @Composable (SplitPaneMode, PaddingValues) -> Unit,
) {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    var windowOffset by remember { mutableStateOf(Offset.Zero) }

    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxSize()
                .onGloballyPositioned { coordinates ->
                    val pos = coordinates.positionInWindow()
                    if (windowOffset != pos) {
                        windowOffset = pos
                    }
                },
    ) {
        val containerBounds =
            Rect(
                offset = windowOffset,
                size = Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat()),
            )
        val mode =
            calculateSplitPaneMode(
                containerBoundsInWindow = containerBounds,
                density = density,
                layoutDirection = layoutDirection,
                isBigScreen = isBigScreen,
                hinge = hinge,
                primaryWidthStrategy = primaryWidthStrategy,
                unfoldedGap = unfoldedGap,
            )
        SideEffect {
            state.updateMode(mode)
        }
        val (primaryPadding, secondaryPadding) = partitionSplitPadding(mode, contentPadding, layoutDirection)

        when (mode) {
            SplitPaneMode.Compact -> {
                Box(Modifier.fillMaxSize()) {
                    compactContent(contentPadding)
                }
            }

            is SplitPaneMode.HorizontalSplit -> {
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.width(mode.primaryWidth).fillMaxHeight()) {
                        primaryPane(mode, primaryPadding)
                    }
                    if (mode.gapWidth > 0.dp) {
                        Box(Modifier.width(mode.gapWidth).fillMaxHeight()) {
                            divider(mode)
                        }
                    } else {
                        divider(mode)
                    }
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        secondaryPane(mode, secondaryPadding)
                    }
                }
            }

            is SplitPaneMode.VerticalSplit -> {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.height(mode.primaryHeight).fillMaxWidth()) {
                        primaryPane(mode, primaryPadding)
                    }
                    if (mode.gapHeight > 0.dp) {
                        Box(Modifier.height(mode.gapHeight).fillMaxWidth()) {
                            divider(mode)
                        }
                    } else {
                        divider(mode)
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        secondaryPane(mode, secondaryPadding)
                    }
                }
            }
        }
    }
}
