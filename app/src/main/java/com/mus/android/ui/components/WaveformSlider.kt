package com.mus.android.ui.components

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

/**
 * MUS waveform slider — progressive reveal paradigm.
 *
 * FUNDAMENTAL RULE:
 * - LEFT OF PLAYBACK POSITION: real audio waveform — ONE continuous signed path that travels
 *   both above and below the central baseline according to actual audio amplitude.
 * - RIGHT OF PLAYBACK POSITION: perfectly flat horizontal baseline — no future waveform shown.
 *
 * At 0:00 → entire timeline is a flat line.
 * As playback progresses → the single waveform is revealed from left to right.
 * Seeking forward → extends the revealed region.
 * Seeking backward → retracts the revealed region.
 *
 * The waveform geometry is PRECOMPUTED and STATIC for the duration of the track.
 * Only the visible (revealed) region changes — it is NOT morphed or regenerated.
 *
 * Waveform data is signed (-1.0 to 1.0):
 *   positive value → peak above baseline
 *   negative value → peak below baseline
 *   0              → at baseline
 */
@Composable
fun WaveformSlider(
    waveformData: List<Float>, // signed -1.0..1.0 amplitude peaks from real audio
    progress: Float, // 0.0–1.0 playback position
    isPlaying: Boolean,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    waveColor: Color = MusColors.WaveformPlayed,
    baselineColor: Color = MusColors.WaveformUnplayed,
) {
    var isDragging by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableFloatStateOf(0f) }
    val currentProgress = if (isDragging) dragProgress else progress

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
        val strokeWidthPx = 2.dp.toPx()

        // ── 1. UNPLAYED BASELINE (right of playhead) ─────────────────────────────────
        // Always a perfectly flat horizontal line from playhead to end.
        drawLine(
            color = baselineColor,
            start = Offset(revealX, centerY),
            end = Offset(w, centerY),
            strokeWidth = strokeWidthPx,
            cap = StrokeCap.Round,
        )

        if (!hasWaveform || currentProgress <= 0.002f) {
            // At 0:00 or no audio data — entire timeline is a flat line.
            drawLine(
                color = baselineColor,
                start = Offset(0f, centerY),
                end = Offset(w, centerY),
                strokeWidth = strokeWidthPx,
                cap = StrokeCap.Round,
            )
        } else {
            // ── 2. SINGLE SIGNED WAVEFORM (left of playhead) ─────────────────────────
            // One continuous path. Positive values go above centerY, negative go below.
            // Amplitude is half the canvas height — leaves comfortable margin top and bottom.
            val maxAmplitude = h * 0.42f
            val sampleCount = waveformData.size

            // Number of samples elapsed up to revealX
            val playedSampleCount = ((currentProgress * (sampleCount - 1)).toInt() + 1)
                .coerceIn(1, sampleCount)

            if (revealX > 2f && playedSampleCount > 0) {
                // Build point list: x from 0..revealX, y = centerY - (signedAmp * maxAmplitude)
                // Negative amplitude → y > centerY (below baseline)
                // Positive amplitude → y < centerY (above baseline)
                val points = ArrayList<Offset>(playedSampleCount + 1)

                for (i in 0 until playedSampleCount) {
                    val rawX = (i.toFloat() / (sampleCount - 1)) * w
                    val x = rawX.coerceAtMost(revealX)
                    // Signed peak: positive = above, negative = below
                    val signedAmp = waveformData[i].coerceIn(-1f, 1f)
                    val y = centerY - (signedAmp * maxAmplitude)
                    points.add(Offset(x, y))
                }

                // Close the waveform: last point smoothly returns to baseline at playhead
                points.add(Offset(revealX, centerY))

                if (points.size >= 2) {
                    val wavePath = Path()
                    wavePath.moveTo(0f, centerY) // start from baseline on the left edge

                    // Smooth quadratic Bézier interpolation through all waveform points
                    for (i in 1 until points.size) {
                        val prev = points[i - 1]
                        val curr = points[i]
                        val midX = (prev.x + curr.x) / 2f
                        val midY = (prev.y + curr.y) / 2f
                        wavePath.quadraticTo(prev.x, prev.y, midX, midY)
                    }

                    // Final segment to the last point (revealX, centerY)
                    val last = points.last()
                    wavePath.lineTo(last.x, last.y)

                    drawPath(
                        path = wavePath,
                        color = waveColor,
                        style = Stroke(
                            width = 2.5.dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round,
                        ),
                    )
                }
            }
        }

        // ── 3. TACTILE PLAYHEAD DOT ───────────────────────────────────────────────────
        // Sits exactly at the boundary between revealed waveform and flat baseline.
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
