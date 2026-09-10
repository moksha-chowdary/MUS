package com.mus.android.ui.ambient

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlin.math.cos
import kotlin.math.sin

/**
 * Full-screen ambient gradient background derived from artwork colors.
 * Renders multiple softly-blended radial gradients that drift slowly,
 * with a dark scrim overlay for readability.
 *
 * The effect is: artwork colors light the interface from behind.
 */
@Composable
fun AmbientGradientBackground(
    colors: PaletteExtractor.ArtworkColors,
    modifier: Modifier = Modifier,
    intensity: Float = 1f, // 1.0 = full (Now Playing), 0.3 = subtle (mini-player), 0.1 = very subtle (home)
) {
    // Animate color transitions when track changes (~1s crossfade)
    val animatedDominant by animateColorAsState(
        targetValue = colors.dominant,
        animationSpec = tween(durationMillis = 1000, easing = EaseInOutCubic),
        label = "dominant"
    )
    val animatedVibrant by animateColorAsState(
        targetValue = colors.vibrant,
        animationSpec = tween(durationMillis = 1000, easing = EaseInOutCubic),
        label = "vibrant"
    )
    val animatedMuted by animateColorAsState(
        targetValue = colors.muted,
        animationSpec = tween(durationMillis = 1000, easing = EaseInOutCubic),
        label = "muted"
    )

    // Slow drift animation (60-90 second cycle)
    val infiniteTransition = rememberInfiniteTransition(label = "gradient_drift")
    val driftPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 75_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "drift"
    )

    val driftPhase2 by infiniteTransition.animateFloat(
        initialValue = 120f,
        targetValue = 480f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 90_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "drift2"
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        // Base atmospheric dark tone
        drawRect(Color(0xFF090A0E))

        val radian1 = Math.toRadians(driftPhase.toDouble())
        val radian2 = Math.toRadians(driftPhase2.toDouble())

        // Blob 1 — Dominant color, sweeps upper and middle-left
        val offset1 = Offset(
            x = w * (0.35f + 0.20f * cos(radian1).toFloat()),
            y = h * (0.28f + 0.15f * sin(radian1 * 0.8).toFloat())
        )

        // Blob 2 — Vibrant color, sweeps middle-right and upper
        val offset2 = Offset(
            x = w * (0.68f + 0.18f * sin(radian2).toFloat()),
            y = h * (0.45f + 0.18f * cos(radian2 * 0.6).toFloat())
        )

        // Blob 3 — Muted/tertiary color, sweeps lower-center
        val offset3 = Offset(
            x = w * (0.45f + 0.15f * sin(radian1 * 1.2).toFloat()),
            y = h * (0.72f + 0.12f * cos(radian2 * 0.9).toFloat())
        )

        val radius1 = w * 0.85f
        val radius2 = w * 0.80f
        val radius3 = w * 0.75f

        // Alpha scales with intensity: NowPlaying has full glow (~0.75), main screens have elegant subtle glow (~0.45)
        val alpha1 = (0.75f * intensity).coerceIn(0.15f, 0.85f)
        val alpha2 = (0.65f * intensity).coerceIn(0.12f, 0.75f)
        val alpha3 = (0.55f * intensity).coerceIn(0.10f, 0.65f)

        // Draw flowing radial gradient meshes
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(animatedDominant.copy(alpha = alpha1), Color.Transparent),
                center = offset1,
                radius = radius1,
            ),
            radius = radius1,
            center = offset1,
        )

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(animatedVibrant.copy(alpha = alpha2), Color.Transparent),
                center = offset2,
                radius = radius2,
            ),
            radius = radius2,
            center = offset2,
        )

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(animatedMuted.copy(alpha = alpha3), Color.Transparent),
                center = offset3,
                radius = radius3,
            ),
            radius = radius3,
            center = offset3,
        )

        // Subtle dark vertical gradient scrim to preserve high-contrast text readability at top and bottom
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color.Black.copy(alpha = 0.20f),
                    Color.Transparent,
                    Color.Black.copy(alpha = 0.35f),
                )
            )
        )
    }
}

private val EaseInOutCubic = CubicBezierEasing(0.65f, 0f, 0.35f, 1f)
