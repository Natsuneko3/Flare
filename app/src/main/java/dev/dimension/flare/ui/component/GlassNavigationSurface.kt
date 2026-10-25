package dev.dimension.flare.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazePerformanceMode
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.glass.ChromaticAberrationMode
import dev.chrisbanes.haze.glass.GlassStyle
import dev.chrisbanes.haze.glass.RefractionProfile
import dev.chrisbanes.haze.glass.hazeGlass

/** A source-backed glass surface; its icons and labels stay outside the optical effect. */
@OptIn(ExperimentalHazeApi::class)
@Composable
internal fun GlassNavigationSurface(
    hazeState: HazeState,
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(50),
    tint: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val border =
        BorderStroke(
            0.5.dp,
            Brush.verticalGradient(
                listOf(Color.White.copy(alpha = 0.38f), Color.White.copy(alpha = 0.08f)),
            ),
        )
    val glassContent: @Composable () -> Unit = {
        Box(
            modifier =
                Modifier.navigationGlass(
                    hazeState = hazeState,
                    shape = shape,
                    tint = tint,
                ),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
    if (onClick == null) {
        Surface(
            modifier = modifier,
            shape = shape,
            color = Color.Transparent,
            contentColor = contentColor,
            shadowElevation = 6.dp,
            border = border,
            content = glassContent,
        )
    } else {
        Surface(
            onClick = onClick,
            modifier = modifier,
            shape = shape,
            color = Color.Transparent,
            contentColor = contentColor,
            shadowElevation = 6.dp,
            border = border,
            content = glassContent,
        )
    }
}

/** Shares the same source-backed optics with the modal rail without replacing its gestures. */
@OptIn(ExperimentalHazeApi::class)
@Composable
internal fun Modifier.navigationGlass(
    hazeState: HazeState,
    shape: RoundedCornerShape,
    tint: Color = MaterialTheme.colorScheme.surface,
    dispersion: Boolean = false,
): Modifier {
    val background = MaterialTheme.colorScheme.surface
    val dark = background.luminance() < 0.5f
    val style =
        remember(shape, tint, background, dark, dispersion) {
            GlassStyle.regular.then {
                shape(shape)
                backgroundColor(background)
                tint(
                    tint.copy(
                        alpha =
                            if (dispersion) {
                                if (dark) 0.24f else 0.32f
                            } else {
                                if (dark) 0.32f else 0.48f
                            },
                    ),
                )
                // Follow the app's theme, including an override of the system appearance.
                whitePoint(if (dark) -0.18f else 0.35f)
                specularIntensity(if (dark) 0.38f else 0.48f)
                ambientResponse(if (dark) 0.08f else 0.15f)
                contrast(if (dark) 0.08f else 0f)
                chromaMultiplier(if (dark) 1.1f else 1.5f)
                edgeShadow(Color.Black.copy(alpha = if (dark) 0.32f else 0.14f))
                if (dispersion) {
                    // Keep the center diffuse and concentrate the RGB split at the glass edge.
                    optics(
                        refractionStrength = 0.7f,
                        refractionHeightFraction = 0.15f,
                        refractionDisplacement = 32.dp,
                        refractionProfile = RefractionProfile.Edge(24.dp),
                        depth = 0.8f,
                        blurRadius = 22.dp,
                        refractionDetailIntensity = 0f,
                    )
                }
                chromaticAberrationStrength(if (dispersion) 0.18f else 0f)
                chromaticAberrationMode(ChromaticAberrationMode.Simple)
            }
        }
    return hazeGlass(
        input = HazeInput.Sources(hazeState),
        style = style,
        performanceMode = HazePerformanceMode.Balanced,
    ).then(
        if (dispersion) {
            Modifier.border(
                width = 0.5.dp,
                brush =
                    Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = 0.45f), Color.White.copy(alpha = 0.10f)),
                    ),
                shape = shape,
            )
        } else {
            Modifier
        },
    )
}
