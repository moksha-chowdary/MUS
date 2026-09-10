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
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.mus.android.ui.theme.MusColors
import kotlin.math.sin

/**
 * MUS waveform slider — progressive reveal paradigm.
 *
 * FUNDAMENTAL RULE:
 * - LEFT OF PLAYBACK POSITION: real audio waveform with organic peaks/valleys
 * - RIGHT OF PLAYBACK POSITION: perfectly flat horizontal baseline
 *
 * At 0:00 → entire timeline is a flat line.
 * As playback progresses → waveform is revealed from left to right.
 * Seeking forward → extends revealed portion.
 * Seeking backward → retracts revealed portion.
 *
 * The waveform shape is determined by real audio amplitude data.
 * While playing, a subtle phase animation gives the wave a "living" feel.
 * While paused, the wave freezes exactly where it is.
 */
@Composable
fun WaveformSlider(
    waveformData: List<Float>, // normalized 0.0–1.0 amplitude peaks from real audio
    progress: Float, // 0.0–1.0 playback position
    isPlaying: Boolean,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    waveColor: Color = MusColors.WaveformPlayed,
    baselineColor: Color = MusColors.WaveformUnplayed,
) {
    // Subtle living animation phase — only advances while playing
    val infiniteTransition = rememberInfiniteTransition(label = "wave_life")
    val phaseRaw by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "wave_phase",
    )

    // Freeze phase when paused
    var frozenPhase by remember { mutableFloatStateOf(0f) }
    val currentPhase = if (isPlaying) {
        frozenPhase = phaseRaw
        phaseRaw
    } else {
        frozenPhase
    }

    var isDragging by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableFloatStateOf(0f) }
    val currentProgress = if (isDragging) dragProgress else progress

    // Pre-process amplitude data
    val hasWaveform = remember(waveformData) { waveformData.isNotEmpty() }

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
        val revealX = currentProgress * w

        if (!hasWaveform) {
            // No waveform data — just draw flat baseline across entire width
            drawLine(
                color = baselineColor,
                start = Offset(0f, centerY),
                end = Offset(w, centerY),
                strokeWidth = 2f,
                cap = StrokeCap.Round,
            )
        } else {
            val sampleCount = waveformData.size
            val phaseRadians = Math.toRadians(currentPhase.toDouble())
            val maxAmplitude = h * 0.38f

            // ── REVEALED WAVEFORM (left of playback position) ──
            if (revealX > 1f) {
                val topPath = Path()
                val bottomPath = Path()

                // Number of drawing points in the revealed section
                val pointCount = ((revealX / w) * sampleCount).toInt().coerceIn(1, sampleCount)
                val stepX = if (pointCount > 1) revealX / (pointCount - 1) else revealX

                for (i in 0 until pointCount) {
                    val x = i * stepX
                    val sampleIdx = ((i.toFloat() / pointCount) * (sampleCount - 1)).toInt()
                        .coerceIn(0, sampleCount - 1)
                    val amplitude = waveformData[sampleIdx]

                    // Subtle living oscillation while playing (varies per sample)
                    val lifeOffset = if (isPlaying) {
                        val freq = 2.0 + (sampleIdx % 3) * 0.5
                        (sin(phaseRadians * freq + sampleIdx * 0.3) * 0.06f * amplitude).toFloat()
                    } else 0f

                    val waveHeight = amplitude * maxAmplitude + lifeOffset * maxAmplitude

                    if (i == 0) {
                        topPath.moveTo(x, centerY - waveHeight)
                        bottomPath.moveTo(x, centerY + waveHeight)
                    } else {
                        topPath.lineTo(x, centerY - waveHeight)
                        bottomPath.lineTo(x, centerY + waveHeight)
                    }
                }

                // Draw top wave stroke
                drawPath(
                    path = topPath,
                    color = waveColor,
                    style = Stroke(
                        width = 2.5f,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    ),
                )

                // Draw bottom wave stroke (mirror, slightly dimmer for depth)
                drawPath(
                    path = bottomPath,
                    color = waveColor.copy(alpha = 0.6f),
                    style = Stroke(
                        width = 2f,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    ),
                )
            }

            // ── FLAT FUTURE BASELINE (right of playback position) ──
            if (revealX < w) {
                drawLine(
                    color = baselineColor,
                    start = Offset(revealX, centerY),
                    end = Offset(w, centerY),
                    strokeWidth = 2f,
                    cap = StrokeCap.Round,
                )
            }
        }

        // ── Playhead indicator ──
        drawCircle(
            color = MusColors.WaveformPlayhead,
            radius = 4.dp.toPx(),
            center = Offset(revealX, centerY),
        )
    }
}
