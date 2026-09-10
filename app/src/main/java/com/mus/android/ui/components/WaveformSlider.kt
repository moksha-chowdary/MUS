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
        val revealX = (currentProgress * w).coerceIn(0f, w)

        // ── 1. BASELINE ACROSS TIMELINE ──
        // Subtly visible track guide under unrevealed section
        drawLine(
            color = baselineColor,
            start = Offset(revealX, centerY),
            end = Offset(w, centerY),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round,
        )

        if (!hasWaveform || currentProgress <= 0.002f) {
            // At 0:00 or with no audio data, the entire timeline is a PERFECTLY FLAT straight horizontal line.
            drawLine(
                color = baselineColor,
                start = Offset(0f, centerY),
                end = Offset(w, centerY),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
        } else {
            val sampleCount = waveformData.size
            val phaseRadians = Math.toRadians(currentPhase.toDouble())
            val maxAmplitude = h * 0.42f

            // How many physical audio samples have elapsed up to revealX
            // Sample i naturally lives at x = (i / (sampleCount - 1)) * w
            val playedSampleCount = ((currentProgress * (sampleCount - 1)).toInt() + 1).coerceIn(1, sampleCount)

            if (revealX > 2f && playedSampleCount > 0) {
                // Collect points strictly from x = 0 to x = revealX
                val points = ArrayList<Offset>(playedSampleCount + 2)

                for (i in 0 until playedSampleCount) {
                    val rawX = (i.toFloat() / (sampleCount - 1)) * w
                    val x = rawX.coerceAtMost(revealX)
                    val baseAmp = waveformData[i].coerceIn(0f, 1f)

                    // Subtle living wave breathing (only active when playing)
                    val lifeOffset = if (isPlaying) {
                        (sin(phaseRadians * 2.5 + i * 0.35) * 0.08f * baseAmp).toFloat()
                    } else {
                        (sin(Math.toRadians(frozenPhase.toDouble()) * 2.5 + i * 0.35) * 0.08f * baseAmp).toFloat()
                    }
                    val currentHeight = ((baseAmp + lifeOffset).coerceIn(0.02f, 1f)) * maxAmplitude
                    points.add(Offset(x, currentHeight))
                }

                // Final point smoothly anchors into the flat baseline at (revealX, 0)
                points.add(Offset(revealX, 0f))

                if (points.isNotEmpty()) {
                    val topPath = Path()
                    val bottomPath = Path()

                    topPath.moveTo(0f, centerY - points[0].y)
                    bottomPath.moveTo(0f, centerY + points[0].y)

                    // Construct smooth Bezier curves connecting real audio peaks
                    for (i in 1 until points.size) {
                        val prev = points[i - 1]
                        val curr = points[i]
                        val midX = (prev.x + curr.x) / 2f
                        val midYTop = centerY - (prev.y + curr.y) / 2f
                        val midYBottom = centerY + (prev.y + curr.y) / 2f

                        topPath.quadraticTo(prev.x, centerY - prev.y, midX, midYTop)
                        bottomPath.quadraticTo(prev.x, centerY + prev.y, midX, midYBottom)
                    }

                    // Complete the curve right into (revealX, centerY)
                    val lastPoint = points.last()
                    topPath.lineTo(lastPoint.x, centerY)
                    bottomPath.lineTo(lastPoint.x, centerY)

                    // Draw the primary smooth audio sound wave (top)
                    drawPath(
                        path = topPath,
                        color = waveColor,
                        style = Stroke(
                            width = 2.5.dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round,
                        ),
                    )

                    // Draw the mirrored bottom reflection wave for acoustic depth
                    drawPath(
                        path = bottomPath,
                        color = waveColor.copy(alpha = 0.50f),
                        style = Stroke(
                            width = 2.dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round,
                        ),
                    )
                }
            }
        }

        // ── 2. TACTILE PLAYHEAD DOT ──
        // Exactly at the boundary between the revealed soundwave and the flat baseline
        drawCircle(
            color = MusColors.WaveformPlayhead.copy(alpha = 0.25f),
            radius = 6.dp.toPx(),
            center = Offset(revealX, centerY),
        )
        drawCircle(
            color = MusColors.WaveformPlayhead,
            radius = 3.5.dp.toPx(),
            center = Offset(revealX, centerY),
        )
    }
}
