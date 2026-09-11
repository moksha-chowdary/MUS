package com.mus.android.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.mus.android.data.scanner.WaveformExtractor
import com.mus.android.ui.theme.MusColors
import kotlin.math.abs

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
 * The waveform geometry is PRECOMPUTED and cached via drawWithCache.
 * Dynamic updates during playback only adjust the reveal clipping boundary — zero allocations!
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

    // Reject flat, unipolar, or constant legacy cached waveforms — render calm baseline until authentic data is ready
    val validWaveformData = remember(waveformData) {
        if (waveformData.size >= 50 && WaveformExtractor.isValidWaveform(waveformData)) {
            waveformData
        } else if (waveformData.isNotEmpty()) {
            val hasPos = waveformData.any { it > 0.05f }
            val hasNeg = waveformData.any { it < -0.05f }
            val span = (waveformData.maxOrNull() ?: 0f) - (waveformData.minOrNull() ?: 0f)
            if (hasPos && hasNeg && span >= 0.15f) waveformData else emptyList()
        } else {
            emptyList()
        }
    }

    Spacer(
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
            .drawWithCache {
                val w = size.width
                val h = size.height
                val centerY = h / 2f
                val maxAmplitude = h * 0.40f
                val strokeWidthPx = 2.5.dp.toPx()
                val baselineStrokeWidthPx = 1.75.dp.toPx()

                // Precompute static bipolar waveform path across the full width using Catmull-Rom cubic splines
                val wavePath = Path()
                if (validWaveformData.size >= 2) {
                    val sampleCount = validWaveformData.size
                    val pointsX = FloatArray(sampleCount) { i -> (i.toFloat() / (sampleCount - 1)) * w }
                    val pointsY = FloatArray(sampleCount) { i ->
                        centerY - (validWaveformData[i].coerceIn(-1f, 1f) * maxAmplitude)
                    }

                    // Anchor seamlessly to center baseline at the very beginning
                    wavePath.moveTo(0f, centerY)
                    wavePath.lineTo(pointsX[0], pointsY[0])

                    // Smooth Catmull-Rom cubic spline interpolation through real audio peaks & troughs
                    for (i in 0 until sampleCount - 1) {
                        val p0x = if (i > 0) pointsX[i - 1] else pointsX[i]
                        val p0y = if (i > 0) pointsY[i - 1] else centerY
                        val p1x = pointsX[i]
                        val p1y = pointsY[i]
                        val p2x = pointsX[i + 1]
                        val p2y = pointsY[i + 1]
                        val p3x = if (i + 2 < sampleCount) pointsX[i + 2] else pointsX[i + 1]
                        val p3y = if (i + 2 < sampleCount) pointsY[i + 2] else centerY

                        val cp1x = p1x + (p2x - p0x) / 6f
                        val cp1y = p1y + (p2y - p0y) / 6f
                        val cp2x = p2x - (p3x - p1x) / 6f
                        val cp2y = p2y - (p3y - p1y) / 6f

                        wavePath.cubicTo(cp1x, cp1y, cp2x, cp2y, p2x, p2y)
                    }

                    // Anchor seamlessly to center baseline at the very end
                    wavePath.lineTo(w, centerY)
                }

                onDrawBehind {
                    val revealX = (currentProgress * w).coerceIn(0f, w)

                    // ── 1. UNPLAYED BASELINE (right of playback position) ────────────────────────
                    // Flat horizontal center line from revealX to the right edge.
                    // At 0:00 (revealX == 0f), this line spans the entire canvas width: 0..w
                    drawLine(
                        color = baselineColor,
                        start = Offset(revealX, centerY),
                        end = Offset(w, centerY),
                        strokeWidth = baselineStrokeWidthPx,
                        cap = StrokeCap.Round,
                    )

                    // Subtle baseline under played region as central reference axis
                    if (revealX > 1f) {
                        drawLine(
                            color = baselineColor.copy(alpha = baselineColor.alpha * 0.40f),
                            start = Offset(0f, centerY),
                            end = Offset(revealX, centerY),
                            strokeWidth = baselineStrokeWidthPx * 0.75f,
                            cap = StrokeCap.Round,
                        )
                    }

                    // ── 2. BIPOLAR WAVEFORM (left of playback position) ─────────────────────────
                    // Revealed strictly up to revealX; everything beyond remains the flat baseline.
                    if (validWaveformData.isNotEmpty() && currentProgress > 0.001f && revealX > 1f) {
                        clipRect(left = 0f, top = 0f, right = revealX, bottom = h) {
                            drawPath(
                                path = wavePath,
                                color = waveColor,
                                style = Stroke(
                                    width = strokeWidthPx,
                                    cap = StrokeCap.Round,
                                    join = StrokeJoin.Round,
                                ),
                            )
                        }

                        // Connect seamlessly to the center baseline at the playback boundary (revealX)
                        val exactIdx = currentProgress * (validWaveformData.size - 1)
                        val i0 = exactIdx.toInt().coerceIn(0, validWaveformData.size - 1)
                        val i1 = (i0 + 1).coerceIn(0, validWaveformData.size - 1)
                        val t = (exactIdx - i0).coerceIn(0f, 1f)
                        val ampAtBoundary = validWaveformData[i0] + t * (validWaveformData[i1] - validWaveformData[i0])
                        val yAtBoundary = centerY - (ampAtBoundary.coerceIn(-1f, 1f) * maxAmplitude)

                        if (abs(yAtBoundary - centerY) > 0.5f) {
                            drawLine(
                                color = waveColor,
                                start = Offset(revealX, yAtBoundary),
                                end = Offset(revealX, centerY),
                                strokeWidth = strokeWidthPx,
                                cap = StrokeCap.Round,
                            )
                        }
                    }

                    // ── 3. TACTILE PLAYHEAD INDICATOR ───────────────────────────────────────────
                    // Sits exactly at (revealX, centerY) where played waveform meets unplayed baseline.
                    if (currentProgress > 0.001f || isDragging) {
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
            }
    )
}
