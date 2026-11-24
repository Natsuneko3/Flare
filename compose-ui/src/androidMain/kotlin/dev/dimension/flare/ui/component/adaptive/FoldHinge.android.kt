package dev.dimension.flare.ui.component.adaptive

import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable

@Composable
public actual fun rememberActiveFoldHinge(): FoldHinge? {
    LocalFoldHingeOverride.current?.let { return it }
    val posture = currentWindowAdaptiveInfoV2().windowPosture
    val hinge = posture.hingeList.firstOrNull { !it.isFlat || it.isOccluding } ?: return null
    return FoldHinge(
        boundsInWindow = hinge.bounds,
        isVertical = hinge.isVertical,
        isSeparating = hinge.isSeparating,
        isOccluding = hinge.isOccluding,
    )
}
