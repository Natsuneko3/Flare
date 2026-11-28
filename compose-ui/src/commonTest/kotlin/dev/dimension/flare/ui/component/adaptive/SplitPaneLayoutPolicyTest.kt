package dev.dimension.flare.ui.component.adaptive

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SplitPaneLayoutPolicyTest {
    @Test
    fun emptyOrTinyContainerReturnsCompactEvenWhenBigScreenOrFolded() {
        assertEquals(
            SplitPaneMode.Compact,
            calculateSplitPaneMode(
                containerBoundsInWindow = Rect.Zero,
                density = Density(1f),
                isBigScreen = true,
                hinge = FoldHinge(Rect(400f, 0f, 400f, 800f), isVertical = true),
                primaryWidthStrategy = PrimaryWidthStrategy.PostDetailDefault,
            ),
        )
        assertEquals(
            SplitPaneMode.Compact,
            calculateSplitPaneMode(
                containerBoundsInWindow = Rect(0f, 0f, Float.NaN, 800f),
                density = Density(1f),
                isBigScreen = true,
                hinge = null,
                primaryWidthStrategy = PrimaryWidthStrategy.PostDetailDefault,
            ),
        )
        assertEquals(
            SplitPaneMode.Compact,
            calculateSplitPaneMode(
                containerBoundsInWindow = Rect(0f, 0f, 500f, 800f),
                density = Density(1f),
                isBigScreen = true,
                hinge = null,
                primaryWidthStrategy = PrimaryWidthStrategy.PostDetailDefault,
            ),
        )
        assertFalse(SplitPaneMode.Compact.isSplit)
    }

    @Test
    fun postDetailUnfoldedClampsProportionalWidthBetween420And580() {
        val at840 =
            calculateSplitPaneMode(
                containerBoundsInWindow = Rect(0f, 0f, 840f, 900f),
                density = Density(1f),
                isBigScreen = true,
                hinge = null,
                primaryWidthStrategy = PrimaryWidthStrategy.PostDetailDefault,
                unfoldedGap = 8.dp,
            )
        assertEquals(
            SplitPaneMode.HorizontalSplit(primaryWidth = 420.dp, gapWidth = 8.dp, isFoldDriven = false),
            at840,
        )
        assertTrue(at840.isSplit)

        val at1000 =
            calculateSplitPaneMode(
                containerBoundsInWindow = Rect(0f, 0f, 1000f, 900f),
                density = Density(1f),
                isBigScreen = true,
                hinge = null,
                primaryWidthStrategy = PrimaryWidthStrategy.PostDetailDefault,
                unfoldedGap = 8.dp,
            )
        assertEquals(
            SplitPaneMode.HorizontalSplit(primaryWidth = 460.dp, gapWidth = 8.dp, isFoldDriven = false),
            at1000,
        )

        val at1400 =
            calculateSplitPaneMode(
                containerBoundsInWindow = Rect(0f, 0f, 1400f, 900f),
                density = Density(1f),
                isBigScreen = true,
                hinge = null,
                primaryWidthStrategy = PrimaryWidthStrategy.PostDetailDefault,
                unfoldedGap = 8.dp,
            )
        assertEquals(
            SplitPaneMode.HorizontalSplit(primaryWidth = 580.dp, gapWidth = 8.dp, isFoldDriven = false),
            at1400,
        )

        val compactWhenNotBigScreen =
            calculateSplitPaneMode(
                containerBoundsInWindow = Rect(0f, 0f, 700f, 900f),
                density = Density(1f),
                isBigScreen = false,
                hinge = null,
                primaryWidthStrategy = PrimaryWidthStrategy.PostDetailDefault,
            )
        assertEquals(SplitPaneMode.Compact, compactWhenNotBigScreen)
    }

    @Test
    fun subtractsContainerWindowOffsetAndRespectsRtlAndZeroWidthOledCrease() {
        val container = Rect(80f, 0f, 960f, 800f)
        val zeroCrease = FoldHinge(Rect(500f, 0f, 500f, 800f), isVertical = true)
        assertEquals(
            SplitPaneMode.HorizontalSplit(primaryWidth = 420.dp, gapWidth = 0.dp, isFoldDriven = true),
            calculateSplitPaneMode(
                containerBoundsInWindow = container,
                density = Density(1f),
                layoutDirection = LayoutDirection.Ltr,
                isBigScreen = false,
                hinge = zeroCrease,
                primaryWidthStrategy = PrimaryWidthStrategy.Fixed(332.dp),
            ),
        )

        val occluding = FoldHinge(Rect(500f, 0f, 520f, 800f), isVertical = true, isOccluding = true)
        assertEquals(
            SplitPaneMode.HorizontalSplit(primaryWidth = 440.dp, gapWidth = 20.dp, isFoldDriven = true),
            calculateSplitPaneMode(
                containerBoundsInWindow = container,
                density = Density(1f),
                layoutDirection = LayoutDirection.Rtl,
                isBigScreen = false,
                hinge = occluding,
                primaryWidthStrategy = PrimaryWidthStrategy.Fixed(332.dp),
            ),
        )
    }

    @Test
    fun horizontalTabletopFoldTakesPrecedenceOverBigScreenAndRejectsEdgeSlivers() {
        val container = Rect(0f, 0f, 900f, 700f)
        val tabletop = FoldHinge(Rect(0f, 340f, 900f, 360f), isVertical = false)
        assertEquals(
            SplitPaneMode.VerticalSplit(primaryHeight = 340.dp, gapHeight = 20.dp),
            calculateSplitPaneMode(
                containerBoundsInWindow = container,
                density = Density(1f),
                isBigScreen = true,
                hinge = tabletop,
                primaryWidthStrategy = PrimaryWidthStrategy.PostDetailDefault,
            ),
        )

        val nearTopEdge = FoldHinge(Rect(0f, 100f, 900f, 110f), isVertical = false)
        assertEquals(
            SplitPaneMode.HorizontalSplit(primaryWidth = 420.dp, gapWidth = 0.dp, isFoldDriven = false),
            calculateSplitPaneMode(
                containerBoundsInWindow = container,
                density = Density(1f),
                isBigScreen = true,
                hinge = nearTopEdge,
                primaryWidthStrategy = PrimaryWidthStrategy.PostDetailDefault,
            ),
        )

        val nearLeftEdge = FoldHinge(Rect(180f, 0f, 180f, 700f), isVertical = true)
        assertEquals(
            SplitPaneMode.Compact,
            calculateSplitPaneMode(
                containerBoundsInWindow = Rect(0f, 0f, 700f, 700f),
                density = Density(1f),
                isBigScreen = false,
                hinge = nearLeftEdge,
                primaryWidthStrategy = PrimaryWidthStrategy.PostDetailDefault,
            ),
        )
    }

    @Test
    fun partitionSplitPaddingSplitsInsetsAcrossHorizontalVerticalAndRtlModes() {
        val full = PaddingValues(start = 12.dp, top = 64.dp, end = 20.dp, bottom = 28.dp)

        val (hPrimaryRtl, hSecondaryRtl) =
            partitionSplitPadding(
                mode = SplitPaneMode.HorizontalSplit(primaryWidth = 420.dp, gapWidth = 8.dp, isFoldDriven = false),
                contentPadding = full,
                layoutDirection = LayoutDirection.Rtl,
            )
        assertEquals(12.dp, hPrimaryRtl.calculateStartPadding(LayoutDirection.Rtl))
        assertEquals(0.dp, hPrimaryRtl.calculateEndPadding(LayoutDirection.Rtl))
        assertEquals(64.dp, hPrimaryRtl.calculateTopPadding())
        assertEquals(28.dp, hPrimaryRtl.calculateBottomPadding())

        assertEquals(0.dp, hSecondaryRtl.calculateStartPadding(LayoutDirection.Rtl))
        assertEquals(20.dp, hSecondaryRtl.calculateEndPadding(LayoutDirection.Rtl))
        assertEquals(64.dp, hSecondaryRtl.calculateTopPadding())
        assertEquals(28.dp, hSecondaryRtl.calculateBottomPadding())

        val (vPrimary, vSecondary) =
            partitionSplitPadding(
                mode = SplitPaneMode.VerticalSplit(primaryHeight = 320.dp, gapHeight = 16.dp),
                contentPadding = full,
                layoutDirection = LayoutDirection.Ltr,
            )
        assertEquals(64.dp, vPrimary.calculateTopPadding())
        assertEquals(0.dp, vPrimary.calculateBottomPadding())
        assertEquals(12.dp, vPrimary.calculateStartPadding(LayoutDirection.Ltr))
        assertEquals(20.dp, vPrimary.calculateEndPadding(LayoutDirection.Ltr))

        assertEquals(0.dp, vSecondary.calculateTopPadding())
        assertEquals(28.dp, vSecondary.calculateBottomPadding())
        assertEquals(12.dp, vSecondary.calculateStartPadding(LayoutDirection.Ltr))
        assertEquals(20.dp, vSecondary.calculateEndPadding(LayoutDirection.Ltr))
    }
}
