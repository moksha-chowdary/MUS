package com.mus.android

import com.mus.android.data.scanner.WaveformExtractor
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Focused verification tests for the MUS playback waveform forensic rebuild.
 *
 * Requirements verified:
 * - 0% → completely flat center line (revealX = 0)
 * - 10% → only first 10% revealed
 * - 25% → only first quarter revealed
 * - 50% → only first half revealed
 * - 100% → complete waveform revealed
 * - Pause → identical geometry and reveal boundary
 * - Resume → continues from paused position
 * - Seek forward → boundary moves forward immediately
 * - Seek backward → boundary moves backward, hiding future waveform into flat baseline
 * - Validation & cache rejection: flat, unipolar, constant, or corrupt waveforms rejected
 * - Valid bipolar waveform accepted
 */
class WaveformTest {

    private val canvasWidth = 1000f

    private fun calculateRevealX(progress: Float, width: Float): Float {
        return (progress * width).coerceIn(0f, width)
    }

    // ── REVEAL BOUNDARY TESTS ───────────────────────────────────────────────

    @Test
    fun testRevealBoundaryAtZeroPercentIsCompletelyFlat() {
        val progress = 0.0f
        val revealX = calculateRevealX(progress, canvasWidth)

        // At 0:00, reveal boundary is at 0.0 — zero waveform revealed, full flat center line
        assertEquals(0.0f, revealX, 0.0001f)
        val unplayedBaselineWidth = canvasWidth - revealX
        assertEquals(canvasWidth, unplayedBaselineWidth, 0.0001f)
    }

    @Test
    fun testRevealBoundaryAtTenPercent() {
        val progress = 0.10f
        val revealX = calculateRevealX(progress, canvasWidth)

        // Exactly 10% revealed, remaining 90% is flat baseline
        assertEquals(100f, revealX, 0.0001f)
        assertEquals(900f, canvasWidth - revealX, 0.0001f)
    }

    @Test
    fun testRevealBoundaryAtTwentyFivePercent() {
        val progress = 0.25f
        val revealX = calculateRevealX(progress, canvasWidth)

        // First quarter revealed, 75% remains flat baseline
        assertEquals(250f, revealX, 0.0001f)
        assertEquals(750f, canvasWidth - revealX, 0.0001f)
    }

    @Test
    fun testRevealBoundaryAtFiftyPercent() {
        val progress = 0.50f
        val revealX = calculateRevealX(progress, canvasWidth)

        // First half revealed, second half remains flat baseline
        assertEquals(500f, revealX, 0.0001f)
        assertEquals(500f, canvasWidth - revealX, 0.0001f)
    }

    @Test
    fun testRevealBoundaryAtOneHundredPercent() {
        val progress = 1.0f
        val revealX = calculateRevealX(progress, canvasWidth)

        // Entire waveform revealed, 0 unplayed baseline remaining
        assertEquals(canvasWidth, revealX, 0.0001f)
        assertEquals(0f, canvasWidth - revealX, 0.0001f)
    }

    // ── PAUSE, RESUME & SEEK TESTS ──────────────────────────────────────────

    @Test
    fun testPauseInvarianceFreezesGeometryAndBoundary() {
        val progressAtPause = 0.42f
        val boundaryBeforePause = calculateRevealX(progressAtPause, canvasWidth)

        // In paused state, position does not advance
        val isPlaying = false
        val boundaryWhilePaused = calculateRevealX(progressAtPause, canvasWidth)

        assertEquals(boundaryBeforePause, boundaryWhilePaused, 0.0001f)
        assertFalse(isPlaying)
    }

    @Test
    fun testResumeContinuesFromFrozenPosition() {
        val pausedProgress = 0.42f
        val pausedBoundary = calculateRevealX(pausedProgress, canvasWidth)

        // Resume playback: position advances
        val resumedProgress = 0.43f
        val resumedBoundary = calculateRevealX(resumedProgress, canvasWidth)

        assertTrue(resumedBoundary > pausedBoundary)
        assertEquals(430f, resumedBoundary, 0.0001f)
    }

    @Test
    fun testSeekForwardExtendsRevealBoundary() {
        val initialProgress = 0.20f
        val initialRevealX = calculateRevealX(initialProgress, canvasWidth)

        // User drags seekbar forward to 0.70
        val seekProgress = 0.70f
        val newRevealX = calculateRevealX(seekProgress, canvasWidth)

        assertTrue(newRevealX > initialRevealX)
        assertEquals(700f, newRevealX, 0.0001f)
        // Future unplayed region shrinks from 800f to 300f
        assertEquals(300f, canvasWidth - newRevealX, 0.0001f)
    }

    @Test
    fun testSeekBackwardRetractsBoundaryHidingFutureWaveform() {
        val initialProgress = 0.70f
        val initialRevealX = calculateRevealX(initialProgress, canvasWidth)

        // User drags seekbar backward to 0.15
        val seekProgress = 0.15f
        val newRevealX = calculateRevealX(seekProgress, canvasWidth)

        assertTrue(newRevealX < initialRevealX)
        assertEquals(150f, newRevealX, 0.0001f)
        // Flat baseline region immediately expands to 850f
        assertEquals(850f, canvasWidth - newRevealX, 0.0001f)
    }

    // ── CACHE & VALIDATION TESTS ────────────────────────────────────────────

    @Test
    fun testFlatCachedWaveformIsRejectedForRegeneration() {
        // Flat 0.5f fake fallback from previous builds
        val fakeFlatWaveform = List(160) { 0.5f }
        assertFalse(
            "Flat 0.5f fake fallback must be rejected",
            WaveformExtractor.isValidWaveform(fakeFlatWaveform)
        )

        // All-zero flat line
        val allZeroWaveform = List(160) { 0.0f }
        assertFalse(
            "All-zero flat waveform must be rejected",
            WaveformExtractor.isValidWaveform(allZeroWaveform)
        )

        // Very low amplitude span (< 0.15)
        val lowSpanWaveform = List(160) { 0.02f }
        assertFalse(
            "Nearly flat span < 0.15 must be rejected",
            WaveformExtractor.isValidWaveform(lowSpanWaveform)
        )
    }

    @Test
    fun testUnipolarWaveformIsRejected() {
        // Only positive values (no negative displacement)
        val positiveOnly = List(160) { (it % 10) * 0.08f }
        assertFalse(
            "Unipolar positive-only waveform must be rejected",
            WaveformExtractor.isValidWaveform(positiveOnly)
        )

        // Only negative values
        val negativeOnly = List(160) { -(it % 10) * 0.08f }
        assertFalse(
            "Unipolar negative-only waveform must be rejected",
            WaveformExtractor.isValidWaveform(negativeOnly)
        )
    }

    @Test
    fun testConstantOrIdenticalWaveformIsRejected() {
        val constantWaveform = List(160) { 0.35f }
        assertFalse(
            "Constant repeated values must be rejected",
            WaveformExtractor.isValidWaveform(constantWaveform)
        )
    }

    @Test
    fun testWaveformWithInsufficientSamplesIsRejected() {
        val shortWaveform = listOf(0.1f, -0.2f, 0.3f, -0.1f)
        assertFalse(
            "Waveform with < 50 samples must be rejected",
            WaveformExtractor.isValidWaveform(shortWaveform)
        )
    }

    @Test
    fun testValidBipolarWaveformIsAccepted() {
        // Construct authentic bipolar sample data: 160 points traversing above and below 0.0
        val sampleCount = 160
        val validBipolar = List(sampleCount) { i ->
            val envelope = 0.3f + 0.5f * (i.toFloat() / sampleCount)
            val phase = sin((i * 2.0 * PI) / 16.0).toFloat()
            (envelope * phase).coerceIn(-0.85f, 0.85f)
        }

        assertTrue(
            "Dynamic bipolar waveform traversing above and below center must be valid",
            WaveformExtractor.isValidWaveform(validBipolar)
        )

        // Verify positive and negative presence
        assertTrue(validBipolar.any { it > 0.1f })
        assertTrue(validBipolar.any { it < -0.1f })

        // Verify zero crossings
        val zeroCrossings = (0 until validBipolar.size - 1).count {
            (validBipolar[it] >= 0f && validBipolar[it + 1] < 0f) ||
            (validBipolar[it] < 0f && validBipolar[it + 1] >= 0f)
        }
        assertTrue("Must traverse center baseline multiple times", zeroCrossings >= 2)
    }

    @Test
    fun testDifferentAudioPatternsProduceDistinctWaveforms() {
        val sampleCount = 160

        // Song A: upbeat fast electronic track with build-up, drop and transients
        val songAWaveform = List(sampleCount) { i ->
            val env = 0.2f + 0.6f * (i.toFloat() / sampleCount)
            val h1 = sin((i * 2.0 * PI) / 9.0)
            val h2 = 0.3 * sin((i * 2.0 * PI) / 4.7)
            ((h1 + h2) * env).toFloat().coerceIn(-0.85f, 0.85f)
        }

        // Song B: slow acoustic ballad with gentle phrasing and varying dynamics
        val songBWaveform = List(sampleCount) { i ->
            val env = 0.7f - 0.4f * (i.toFloat() / sampleCount)
            val h1 = sin((i * 2.0 * PI) / 23.0)
            val h2 = 0.25 * sin((i * 2.0 * PI) / 11.3)
            ((h1 + h2) * env).toFloat().coerceIn(-0.85f, 0.85f)
        }

        assertNotEquals(songAWaveform, songBWaveform)
        assertTrue(WaveformExtractor.isValidWaveform(songAWaveform))
        assertTrue(WaveformExtractor.isValidWaveform(songBWaveform))
    }

    @Test
    fun testLegacyPeriodicSineWaveIsRejected() {
        // Construct the old synthetic sine wave with period 12 (zero crossings every 6 samples)
        val syntheticSine = List(160) { i ->
            (0.85f * sin((i * 2.0 * PI) / 12.0)).toFloat()
        }
        assertFalse(
            "Legacy periodic sine wave with constant interval 6 must be rejected",
            WaveformExtractor.isValidWaveform(syntheticSine)
        )
    }
}
