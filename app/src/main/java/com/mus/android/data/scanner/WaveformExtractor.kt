package com.mus.android.data.scanner

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.mus.android.data.db.WaveformDao
import com.mus.android.data.model.WaveformData
import com.mus.android.data.model.WaveformStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Extracts real waveform amplitude data from audio files using MediaExtractor + MediaCodec.
 * Decodes the audio to PCM, then downsamples into ~200 SIGNED amplitude values (-1.0..1.0).
 *
 * Each bucket records the sample with the largest absolute value, preserving its original sign.
 * This produces a single waveform that naturally travels both above and below the baseline,
 * reflecting actual audio directionality.
 *
 * Results are cached in Room via WaveformDao. Stale magnitude-only (all-positive) entries
 * are automatically invalidated and re-extracted on next load.
 */
@Singleton
class WaveformExtractor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val waveformDao: WaveformDao,
) {
    companion object {
        /** Number of amplitude samples to produce per track */
        const val SAMPLE_COUNT = 200
        private const val TIMEOUT_US = 10_000L
    }

    /**
     * Returns cached waveform data if available and READY (with signed peaks), otherwise extracts
     * from the audio file. Returns null if extraction fails — never returns synthetic/fake data.
     * Failed extractions are recorded in Room for later background repair.
     */
    suspend fun getWaveform(trackId: Long, uri: String): List<Float>? {
        // Check cache first
        waveformDao.getWaveform(trackId)?.let { cached ->
            if (cached.status == WaveformStatus.EXTRACTING) return null // In progress
            if (cached.status == WaveformStatus.READY) {
                val peaks = parsePeaks(cached.peaks)
                // Detect stale unipolar (all-positive or all-negative) entries from old extractor builds.
                val hasPositive = peaks.any { it > 0.05f }
                val hasNegative = peaks.any { it < -0.05f }
                if (!hasPositive || !hasNegative) {
                    // Invalidate so we re-extract with proper bipolar signed data
                    waveformDao.insert(
                        WaveformData(
                            trackId = trackId,
                            peaks = "",
                            sampleCount = 0,
                            status = WaveformStatus.FAILED,
                        )
                    )
                    // Fall through to extraction below
                } else {
                    return peaks
                }
            }
            // FAILED or NEEDS_REPAIR — fall through to re-extract
        }

        // Mark as extracting
        waveformDao.insert(
            WaveformData(
                trackId = trackId,
                peaks = "",
                sampleCount = 0,
                status = WaveformStatus.EXTRACTING,
            )
        )

        // Extract from audio on background thread
        val peaks = withContext(Dispatchers.Default) {
            try {
                extractSignedPeaks(Uri.parse(uri))
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }

        if (peaks == null || peaks.isEmpty()) {
            // Record failure for background repair
            waveformDao.insert(
                WaveformData(
                    trackId = trackId,
                    peaks = "",
                    sampleCount = 0,
                    status = WaveformStatus.FAILED,
                )
            )
            return null
        }

        // Cache the successful result (signed floats serialise fine in comma-separated format)
        val peaksString = peaks.joinToString(",") { String.format(java.util.Locale.US, "%.4f", it) }
        waveformDao.insert(
            WaveformData(
                trackId = trackId,
                peaks = peaksString,
                sampleCount = peaks.size,
                status = WaveformStatus.READY,
            )
        )

        return peaks
    }

    /**
     * Re-attempts extraction for all waveforms with non-READY status.
     * Call this during background idle time.
     */
    suspend fun repairWaveforms(getUriForTrack: suspend (Long) -> String?) {
        val needsRepair = waveformDao.getWaveformsNeedingRepair()
        for (waveform in needsRepair) {
            val uri = getUriForTrack(waveform.trackId) ?: continue
            getWaveform(waveform.trackId, uri) // Re-attempts extraction
        }
    }

    private fun parsePeaks(peaksString: String): List<Float> {
        return peaksString.split(",").mapNotNull { it.toFloatOrNull() }
    }

    /**
     * Decodes the audio file and extracts BIPOLAR amplitude peaks.
     *
     * Divides audio into 100 windows. For each window, extracts the genuine positive peak
     * (+P_k >= 0) and genuine negative peak (-N_k <= 0) from actual PCM samples.
     * Storing positive peaks at even indices and negative peaks at odd indices creates
     * one continuous waveform path that naturally oscillates above and below the baseline,
     * directly reflecting the real dynamics, quiet sections, loud choruses, and transients.
     */
    private fun extractSignedPeaks(uri: Uri): List<Float>? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)

            // Find audio track
            val audioTrackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                val format = extractor.getTrackFormat(i)
                format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null

            extractor.selectTrack(audioTrackIndex)
            val format = extractor.getTrackFormat(audioTrackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION)
            } else 0L

            if (duration <= 0) return null

            // We produce SAMPLE_COUNT (200) points by analyzing 100 windows (pos + neg per window)
            val windowCount = SAMPLE_COUNT / 2
            val totalSamples = (sampleRate.toLong() * channels * duration) / 1_000_000L
            val samplesPerWindow = max(1L, totalSamples / windowCount)

            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val rawPeaks = mutableListOf<Float>()
            var windowMaxPositive = 0f
            var windowMaxNegative = 0f
            var windowSampleCount = 0L
            var isEos = false
            val bufferInfo = MediaCodec.BufferInfo()

            try {
                while (rawPeaks.size < SAMPLE_COUNT) {
                    // Feed input
                    if (!isEos) {
                        val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                        if (inputIndex >= 0) {
                            val inputBuffer = codec.getInputBuffer(inputIndex) ?: continue
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(
                                    inputIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                isEos = true
                            } else {
                                codec.queueInputBuffer(
                                    inputIndex, 0, sampleSize,
                                    extractor.sampleTime, 0
                                )
                                extractor.advance()
                            }
                        }
                    }

                    // Read output
                    val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                    if (outputIndex >= 0) {
                        val outputBuffer = codec.getOutputBuffer(outputIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            outputBuffer.order(ByteOrder.nativeOrder())
                            // Process PCM 16-bit samples
                            val shortBuffer = outputBuffer.asShortBuffer()
                            val sampleCount = shortBuffer.remaining()

                            for (i in 0 until sampleCount) {
                                val sample = shortBuffer.get()
                                val norm = sample.toFloat() / Short.MAX_VALUE
                                if (norm > windowMaxPositive) {
                                    windowMaxPositive = norm
                                }
                                if (norm < windowMaxNegative) {
                                    windowMaxNegative = norm
                                }
                                windowSampleCount++

                                if (windowSampleCount >= samplesPerWindow) {
                                    rawPeaks.add(windowMaxPositive)
                                    rawPeaks.add(windowMaxNegative)
                                    windowMaxPositive = 0f
                                    windowMaxNegative = 0f
                                    windowSampleCount = 0
                                    if (rawPeaks.size >= SAMPLE_COUNT) break
                                }
                            }
                        }
                        codec.releaseOutputBuffer(outputIndex, false)

                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            if (windowSampleCount > 0 && rawPeaks.size < SAMPLE_COUNT) {
                                rawPeaks.add(windowMaxPositive)
                                rawPeaks.add(windowMaxNegative)
                            }
                            break
                        }
                    }
                }
            } finally {
                codec.stop()
                codec.release()
            }

            if (rawPeaks.isEmpty()) return null

            // Normalise peaks to -1..1 relative to the track's max absolute value.
            // Target ~0.85 to preserve dynamic headroom while giving rich visual presence.
            val globalAbsMax = rawPeaks.maxOfOrNull { abs(it) } ?: 1f
            if (globalAbsMax <= 0f) return rawPeaks

            val normTarget = 0.85f
            val scaleFactor = normTarget / globalAbsMax
            return rawPeaks.map { (it * scaleFactor).coerceIn(-1f, 1f) }

        } catch (e: Exception) {
            e.printStackTrace()
            return null
        } finally {
            extractor.release()
        }
    }
}
