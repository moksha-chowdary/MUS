package com.mus.android.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.mus.android.ui.theme.MusColors

/**
 * The MUS waveform slider.
 *
 * - Paused: single flat horizontal line (flatlined)
 * - Playing: waveform from real amplitude data
 * - Played portion: full opacity
 * - Unplayed portion: dimmed (~25-30% opacity)
 * - Transitions between states via morph animation
 */
@Composable
fun WaveformSlider(
    waveformData: List<Float>, // normalized 0.0–1.0 amplitude peaks
    progress: Float, // 0.0–1.0
    isPlaying: Boolean,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    playedColor: Color = MusColors.WaveformPlayed,
    unplayedColor: Color = MusColors.WaveformUnplayed,
) {
    // Morph: 0 = flatline, 1 = full waveform
    val waveformVisibility by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0f,
        animationSpec = tween(
            durationMillis = 600,
            easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
        ),
        label = "waveform_morph"
    )

    var isDragging by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableFloatStateOf(0f) }

    val currentProgress = if (isDragging) dragProgress else progress
    val effectiveData = remember(waveformData) {
        if (waveformData.isEmpty()) List(200) { 0.5f } else waveformData
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val fraction = (offset.x / size.width).coerceIn(0f, 1f)
                    onSeek(fraction)
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        isDragging = true
                        dragProgress = (offset.x / size.width).coerceIn(0f, 1f)
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        dragProgress = (change.position.x / size.width).coerceIn(0f, 1f)
                    },
                    onDragEnd = {
                        onSeek(dragProgress)
                        isDragging = false
                    },
                    onDragCancel = {
                        isDragging = false
                    }
                )
            }
    ) {
        val w = size.width
        val h = size.height
        val centerY = h / 2f
        val barCount = effectiveData.size
        val totalBarWidth = w / barCount
        val barWidth = (totalBarWidth * 0.6f).coerceIn(1f, 3.5f)
        val maxBarHeight = h * 0.8f

        for (i in effectiveData.indices) {
            val x = (i.toFloat() / barCount) * w + totalBarWidth / 2f
            val amplitude = effectiveData[i]

            // Morph between flat line (tiny height) and full waveform
            val flatHeight = 2f // flat line thickness
            val waveHeight = amplitude * maxBarHeight
            val barHeight = flatHeight + (waveHeight - flatHeight) * waveformVisibility

            val isPlayed = (x / w) <= currentProgress
            val color = if (isPlayed) playedColor else unplayedColor

            drawLine(
                color = color,
                start = Offset(x, centerY - barHeight / 2),
                end = Offset(x, centerY + barHeight / 2),
                strokeWidth = barWidth,
                cap = StrokeCap.Round,
            )
        }

        // Playhead indicator
        val playheadX = currentProgress * w
        drawCircle(
            color = MusColors.WaveformPlayhead,
            radius = 4.dp.toPx(),
            center = Offset(playheadX, centerY),
        )
    }
}
