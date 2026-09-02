package com.mus.android.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mus.android.ui.theme.MusColors

/**
 * Play/Pause button with shape morph animation.
 * Pause bars morph into play triangle and back — no icon swap.
 */
@Composable
fun PlayPauseMorphButton(
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    color: Color = MusColors.OnBackground,
) {
    // 0 = play triangle, 1 = pause bars
    val morphProgress by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0f,
        animationSpec = tween(
            durationMillis = 300,
            easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
        ),
        label = "play_pause_morph"
    )

    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val padding = w * 0.2f

        val t = morphProgress

        // Interpolate between play triangle and pause bars
        // Play triangle: three points forming a right-pointing triangle
        // Pause bars: two vertical rectangles

        // Left shape (left bar / left side of triangle)
        val leftPath = Path().apply {
            // Top-left corner
            moveTo(
                lerp(padding + w * 0.05f, padding, t),           // x
                lerp(padding * 0.8f, padding, t)                  // y
            )
            // Top-right corner
            lineTo(
                lerp(w * 0.55f, padding + w * 0.15f, t),         // x
                lerp(h * 0.5f, padding, t)                         // y
            )
            // Bottom-right corner
            lineTo(
                lerp(w * 0.55f, padding + w * 0.15f, t),         // x
                lerp(h * 0.5f, h - padding, t)                    // y
            )
            // Bottom-left corner
            lineTo(
                lerp(padding + w * 0.05f, padding, t),           // x
                lerp(h - padding * 0.8f, h - padding, t)          // y
            )
            close()
        }

        // Right shape (right bar / right side of triangle)
        val rightPath = Path().apply {
            moveTo(
                lerp(w * 0.55f, w - padding - w * 0.15f, t),     // x
                lerp(h * 0.5f, padding, t)                         // y
            )
            lineTo(
                lerp(w - padding * 0.8f, w - padding, t),        // x
                lerp(h * 0.5f, padding, t)                         // y
            )
            lineTo(
                lerp(w - padding * 0.8f, w - padding, t),        // x
                lerp(h * 0.5f, h - padding, t)                    // y
            )
            lineTo(
                lerp(w * 0.55f, w - padding - w * 0.15f, t),     // x
                lerp(h * 0.5f, h - padding, t)                    // y
            )
            close()
        }

        drawPath(leftPath, color, style = Fill)
        drawPath(rightPath, color, style = Fill)
    }
}

private fun lerp(start: Float, end: Float, fraction: Float): Float {
    return start + (end - start) * fraction
}
