package dev.dimension.flare.ui.component.adaptive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.dimension.flare.ui.component.platform.adaptiveSideContentSize

@Immutable
public sealed interface PrimaryWidthStrategy {
    public fun calculate(containerWidth: Dp): Dp

    @Immutable
    public data class Fixed(
        val width: Dp,
    ) : PrimaryWidthStrategy {
        override fun calculate(containerWidth: Dp): Dp =
            width.coerceAtMost(containerWidth).coerceAtLeast(0.dp)
    }

    @Immutable
    public data class Proportional(
        val fraction: Float,
        val min: Dp,
        val max: Dp,
    ) : PrimaryWidthStrategy {
        override fun calculate(containerWidth: Dp): Dp =
            (containerWidth * fraction)
                .coerceIn(min, max)
                .coerceAtMost(containerWidth)
                .coerceAtLeast(0.dp)
    }

    public companion object {
        public val PostDetailDefault: PrimaryWidthStrategy =
            Proportional(fraction = 0.46f, min = 420.dp, max = 580.dp)
    }
}

@Composable
public fun rememberProfilePrimaryWidthStrategy(horizontalPadding: Dp = 0.dp): PrimaryWidthStrategy {
    val sideWidth by adaptiveSideContentSize()
    return remember(sideWidth, horizontalPadding) {
        PrimaryWidthStrategy.Fixed(sideWidth + horizontalPadding)
    }
}

@Immutable
public sealed interface SplitPaneMode {
    public val isSplit: Boolean
        get() = this !is Compact

    public data object Compact : SplitPaneMode

    @Immutable
    public data class HorizontalSplit(
        val primaryWidth: Dp,
        val gapWidth: Dp,
        val isFoldDriven: Boolean,
    ) : SplitPaneMode

    @Immutable
    public data class VerticalSplit(
        val primaryHeight: Dp,
        val gapHeight: Dp,
    ) : SplitPaneMode
}

public fun calculateSplitPaneMode(
    containerBoundsInWindow: Rect,
    density: Density,
    layoutDirection: LayoutDirection = LayoutDirection.Ltr,
    isBigScreen: Boolean,
    hinge: FoldHinge?,
    primaryWidthStrategy: PrimaryWidthStrategy,
    unfoldedGap: Dp = 0.dp,
    minPaneWidth: Dp = 240.dp,
    minPaneHeight: Dp = 160.dp,
): SplitPaneMode {
    if (!containerBoundsInWindow.isFiniteAndPositive() ||
        !density.density.isFinite() ||
        density.density <= 0f
    ) {
        return SplitPaneMode.Compact
    }

    if (hinge != null && hinge.boundsInWindow.isFiniteCoords()) {
        if (!hinge.isVertical) {
            val topPx = hinge.boundsInWindow.top - containerBoundsInWindow.top
            val bottomPx = hinge.boundsInWindow.bottom - containerBoundsInWindow.top
            if (bottomPx >= topPx) {
                val topHeight = with(density) { topPx.toDp() }
                val gapHeight = with(density) { (bottomPx - topPx).coerceAtLeast(0f).toDp() }
                val bottomHeight = with(density) { (containerBoundsInWindow.height - bottomPx).toDp() }
                if (topHeight >= minPaneHeight && bottomHeight >= minPaneHeight) {
                    return SplitPaneMode.VerticalSplit(
                        primaryHeight = topHeight,
                        gapHeight = gapHeight,
                    )
                }
            }
        } else {
            val leftPx = hinge.boundsInWindow.left - containerBoundsInWindow.left
            val rightPx = hinge.boundsInWindow.right - containerBoundsInWindow.left
            if (rightPx >= leftPx) {
                val leftWidth = with(density) { leftPx.toDp() }
                val gapWidth = with(density) { (rightPx - leftPx).coerceAtLeast(0f).toDp() }
                val rightWidth = with(density) { (containerBoundsInWindow.width - rightPx).toDp() }
                val primaryWidth = if (layoutDirection == LayoutDirection.Ltr) leftWidth else rightWidth
                val secondaryWidth = if (layoutDirection == LayoutDirection.Ltr) rightWidth else leftWidth
                if (primaryWidth >= minPaneWidth && secondaryWidth >= minPaneWidth) {
                    return SplitPaneMode.HorizontalSplit(
                        primaryWidth = primaryWidth,
                        gapWidth = gapWidth,
                        isFoldDriven = true,
                    )
                }
            }
        }
    }

    if (isBigScreen) {
        val containerWidth = with(density) { containerBoundsInWindow.width.toDp() }
        val primaryWidth = primaryWidthStrategy.calculate(containerWidth)
        val safeGap = unfoldedGap.coerceAtLeast(0.dp)
        if (primaryWidth >= minPaneWidth && containerWidth - primaryWidth - safeGap >= minPaneWidth) {
            return SplitPaneMode.HorizontalSplit(
                primaryWidth = primaryWidth,
                gapWidth = safeGap,
                isFoldDriven = false,
            )
        }
    }

    return SplitPaneMode.Compact
}

private fun Rect.isFiniteCoords(): Boolean =
    left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite()

private fun Rect.isFiniteAndPositive(): Boolean =
    isFiniteCoords() && width > 0f && height > 0f
