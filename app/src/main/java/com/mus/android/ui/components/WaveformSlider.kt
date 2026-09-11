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

    // Sanitize waveform data so legacy unipolar cache entries immediately display bipolar
    val sanitizedData = remember(waveformData) {
        if (waveformData.isEmpty()) emptyList()
        else {
            val hasPos = waveformData.any { it > 0.05f }
            val hasNeg = waveformData.any { it < -0.05f }
            if (!hasPos || !hasNeg) {
                // Alternate signs on unipolar legacy cache data
                waveformData.mapIndexed { idx, amp ->
                    val absVal = abs(amp)
                    if (idx % 2 == 1) -absVal else absVal
                }
            } else {
                waveformData
            }
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
                val maxAmplitude = h * 0.42f
                val strokeWidthPx = 2.5.dp.toPx()
                val baselineStrokeWidthPx = 2.dp.toPx()

                // Precompute static bipolar waveform path across the full width
                val wavePath = Path()
                if (sanitizedData.size >= 2) {
                    val sampleCount = sanitizedData.size
                    wavePath.moveTo(0f, centerY)
                    var prevX = 0f
                    var prevY = centerY

                    for (i in 0 until sampleCount) {
                        val currX = (i.toFloat() / (sampleCount - 1)) * w
                        val signedAmp = sanitizedData[i].coerceIn(-1f, 1f)
                        val currY = centerY - (signedAmp * maxAmplitude)
                        val midX = (prevX + currX) / 2f
                        val midY = (prevY + currY) / 2f
                        wavePath.quadraticTo(prevX, prevY, midX, midY)
                        prevX = currX
                        prevY = currY
                    }
                    // Connect smoothly to the baseline at the right edge
                    wavePath.quadraticTo(prevX, prevY, w, centerY)
                }

                onDrawBehind {
                    val revealX = (currentProgress * w).coerceIn(0f, w)

                    // ── 1. UNPLAYED BASELINE (right of playhead) ─────────────────────────────────
                    // Always a flat horizontal line from revealX to the right edge
                    drawLine(
                        color = baselineColor,
                        start = Offset(revealX, centerY),
                        end = Offset(w, centerY),
                        strokeWidth = baselineStrokeWidthPx,
                        cap = StrokeCap.Round,
                    )

                    // ── 2. BIPOLAR WAVEFORM (left of playhead) ──────────────────────────────────
                    // Revealed via clipRect: only the played portion is visible, exactly up to revealX
                    if (sanitizedData.isNotEmpty() && currentProgress > 0.002f && revealX > 1f) {
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
                    } else {
                        // At 0:00 or empty timeline: draw flat baseline across the whole width
                        drawLine(
                            color = baselineColor,
                            start = Offset(0f, centerY),
                            end = Offset(w, centerY),
                            strokeWidth = baselineStrokeWidthPx,
                            cap = StrokeCap.Round,
                        )
                    }

                    // ── 3. TACTILE PLAYHEAD DOT ───────────────────────────────────────────────────
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
    )
}
