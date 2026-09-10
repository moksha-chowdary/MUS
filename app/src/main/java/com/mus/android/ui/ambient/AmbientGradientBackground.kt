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

        // Base dark background
        drawRect(Color(0xFF0A0A0A))

        val radian1 = Math.toRadians(driftPhase.toDouble())
        val radian2 = Math.toRadians(driftPhase2.toDouble())

        // Gradient blob 1 — dominant color, upper area
        val offset1 = Offset(
            x = w * (0.3f + 0.15f * cos(radian1).toFloat()),
            y = h * (0.25f + 0.1f * sin(radian1 * 0.7).toFloat())
        )

        // Gradient blob 2 — vibrant color, center-right
        val offset2 = Offset(
            x = w * (0.7f + 0.12f * sin(radian2).toFloat()),
            y = h * (0.5f + 0.15f * cos(radian2 * 0.5).toFloat())
        )

        // Gradient blob 3 — muted color, bottom-left
        val offset3 = Offset(
            x = w * (0.4f + 0.1f * sin(radian1 * 1.3).toFloat()),
            y = h * (0.75f + 0.08f * cos(radian2 * 0.8).toFloat())
        )

        val radius = w * 0.7f

        // Draw radial gradients with intensity control
        val alpha = (0.45f * intensity).coerceIn(0f, 0.5f)

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf<Color>(animatedDominant.copy(alpha = alpha), Color.Transparent),
                center = offset1,
                radius = radius,
            ),
            radius = radius,
            center = offset1,
        )

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf<Color>(animatedVibrant.copy(alpha = alpha * 0.8f), Color.Transparent),
                center = offset2,
                radius = radius * 0.85f,
            ),
            radius = radius * 0.85f,
            center = offset2,
        )

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf<Color>(animatedMuted.copy(alpha = alpha * 0.6f), Color.Transparent),
                center = offset3,
                radius = radius * 0.75f,
            ),
            radius = radius * 0.75f,
            center = offset3,
        )

        // Dark scrim for readability — stronger when colors are brighter
        drawRect(Color.Black.copy(alpha = 0.35f * intensity.coerceAtLeast(0.3f)))
    }
}

private val EaseInOutCubic = CubicBezierEasing(0.65f, 0f, 0.35f, 1f)
