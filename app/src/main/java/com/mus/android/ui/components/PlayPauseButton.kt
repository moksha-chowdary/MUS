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
        val t = morphProgress

        // Mathematically and optically centered morph between Play triangle (t=0) and Pause bars (t=1)
        // Left shape: left bar <-> left trapezoid of play triangle
        val leftPath = Path().apply {
            moveTo(
                lerp(0.28f * w, 0.24f * w, t),
                lerp(0.18f * h, 0.18f * h, t)
            )
            lineTo(
                lerp(0.54f * w, 0.40f * w, t),
                lerp(0.34f * h, 0.18f * h, t)
            )
            lineTo(
                lerp(0.54f * w, 0.40f * w, t),
                lerp(0.66f * h, 0.82f * h, t)
            )
            lineTo(
                lerp(0.28f * w, 0.24f * w, t),
                lerp(0.82f * h, 0.82f * h, t)
            )
            close()
        }

        // Right shape: right bar <-> right tip of play triangle
        val rightPath = Path().apply {
            moveTo(
                lerp(0.54f * w, 0.60f * w, t),
                lerp(0.34f * h, 0.18f * h, t)
            )
            lineTo(
                lerp(0.80f * w, 0.76f * w, t),
                lerp(0.50f * h, 0.18f * h, t)
            )
            lineTo(
                lerp(0.80f * w, 0.76f * w, t),
                lerp(0.50f * h, 0.82f * h, t)
            )
            lineTo(
                lerp(0.54f * w, 0.60f * w, t),
                lerp(0.66f * h, 0.82f * h, t)
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
