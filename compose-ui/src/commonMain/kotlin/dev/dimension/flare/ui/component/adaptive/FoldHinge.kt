package dev.dimension.flare.ui.component.adaptive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.geometry.Rect

@Immutable
public data class FoldHinge(
    val boundsInWindow: Rect,
    val isVertical: Boolean,
    val isSeparating: Boolean = true,
    val isOccluding: Boolean = false,
)

public val LocalFoldHingeOverride: ProvidableCompositionLocal<FoldHinge?> =
    compositionLocalOf { null }

@Composable
public expect fun rememberActiveFoldHinge(): FoldHinge?
