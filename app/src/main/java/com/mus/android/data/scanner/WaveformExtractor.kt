package com.mus.android.data.scanner

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import com.mus.android.data.db.WaveformDao
import com.mus.android.data.model.WaveformData
import com.mus.android.data.model.WaveformStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.nio.ByteOrder
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Extracts real waveform amplitude data from audio files using MediaExtractor + MediaCodec.
 *
 * Requirements:
 * - Real audio data decoded from 16-bit PCM.
 * - Single signed bipolar waveform moving naturally: above → center → below → center → above.
 * - Compact representation (SAMPLE_COUNT = 160 normalized signed displacement values).
 * - Robust across file:// and content:// (including SAF document tree URIs).
 * - Invalid cache detection & automatic re-extraction.
 * - No flat fallbacks (never synthesizes 0.5f fake data).
 * - Diagnostic extraction logging (WAVEFORM_EXTRACT).
 */
@Singleton
class WaveformExtractor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val waveformDao: WaveformDao,
) {
    companion object {
        const val TAG = "WAVEFORM_EXTRACT"
        /** Number of amplitude points to produce per track */
        const val SAMPLE_COUNT = 160
        private const val TIMEOUT_US = 10_000L

        /**
         * Validates whether cached peaks represent a genuine, dynamic bipolar waveform.
         * Detects flat, unipolar, constant, or corrupt waveforms from previous builds.
         */
        fun isValidWaveform(peaks: List<Float>): Boolean {
            if (peaks.size < 50) return false
            val min = peaks.minOrNull() ?: return false
            val max = peaks.maxOrNull() ?: return false
            val span = max - min
            if (span < 0.15f) return false // Flat or nearly flat

            val hasPositive = peaks.any { it > 0.05f }
            val hasNegative = peaks.any { it < -0.05f }
            if (!hasPositive || !hasNegative) return false // Unipolar

            // Check for identical repeated values
            val allIdentical = peaks.all { abs(it - peaks[0]) < 0.001f }
            if (allIdentical) return false

            // Check for zero crossings (must traverse the center baseline)
            val zeroCrossings = (0 until peaks.size - 1).count {
                (peaks[it] >= 0f && peaks[it + 1] < 0f) || (peaks[it] < 0f && peaks[it + 1] >= 0f)
            }
            if (zeroCrossings < 2) return false

            // Reject legacy synthetic periodic waves where values repeat identically across fixed periods (e.g. period 12)
            if (peaks.size >= 36) {
                for (period in 6..24) {
                    val matchCount = (0 until peaks.size - period).count {
                        abs(peaks[it + period] - peaks[it]) < 0.001f
                    }
                    if (matchCount > (peaks.size - period) * 0.75) {
                        return false // Periodic synthetic wave
                    }
                }
            }

            return true
        }
    }

    /**
     * Returns cached waveform data if valid, otherwise extracts from the audio file.
     * Never returns synthetic/fake data.
     */
    suspend fun getWaveform(trackId: Long, uri: String): List<Float>? {
        // Check cache first
        waveformDao.getWaveform(trackId)?.let { cached ->
            if (cached.status == WaveformStatus.EXTRACTING) return null // Extraction in progress
            if (cached.status == WaveformStatus.READY) {
                val peaks = parsePeaks(cached.peaks)
                if (isValidWaveform(peaks)) {
                    return peaks
                } else {
                    Log.d(TAG, "Invalid/stale cached waveform for trackId=$trackId, invalidating for re-extraction")
                    waveformDao.insert(
                        WaveformData(
                            trackId = trackId,
                            peaks = "",
                            sampleCount = 0,
                            status = WaveformStatus.FAILED,
                        )
                    )
                }
            }
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

        val startTime = System.currentTimeMillis()

        // Extract from audio source on background thread
        val result = withContext(Dispatchers.Default) {
            try {
                extractBipolarAudioWaveform(trackId, uri, startTime)
            } catch (e: Exception) {
                Log.e(TAG, "Extraction failed for trackId=$trackId, uri=$uri", e)
                null
            }
        }

        if (result == null || result.isEmpty() || !isValidWaveform(result)) {
            Log.w(TAG, "Extraction produced null or invalid waveform for trackId=$trackId, recording FAILED")
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

        // Cache successful result in Room
        val peaksString = result.joinToString(",") { String.format(Locale.US, "%.4f", it) }
        waveformDao.insert(
            WaveformData(
                trackId = trackId,
                peaks = peaksString,
                sampleCount = result.size,
                status = WaveformStatus.READY,
            )
        )

        return result
    }

    /**
     * Re-attempts extraction for all waveforms with non-READY status.
     */
    suspend fun repairWaveforms(getUriForTrack: suspend (Long) -> String?) {
        val needsRepair = waveformDao.getWaveformsNeedingRepair()
        for (waveform in needsRepair) {
            val uri = getUriForTrack(waveform.trackId) ?: continue
            getWaveform(waveform.trackId, uri)
        }
    }

    private fun parsePeaks(peaksString: String): List<Float> {
        if (peaksString.isBlank()) return emptyList()
        return peaksString.split(",").mapNotNull { it.toFloatOrNull() }
    }

    /**
     * Configures the MediaExtractor data source reliably across all MUS audio sources:
     * - content:// (including SAF Document URIs via openFileDescriptor)
     * - file:// and raw filesystem paths via FileDescriptor
     * - fallback to context Uri
     */
    private fun setExtractorDataSource(extractor: MediaExtractor, uriString: String): String {
        val parsedUri = Uri.parse(uriString)
        val scheme = parsedUri.scheme

        if (scheme == "content") {
            try {
                val pfd = context.contentResolver.openFileDescriptor(parsedUri, "r")
                if (pfd != null) {
                    extractor.setDataSource(pfd.fileDescriptor)
                    pfd.close()
                    return "content_pfd"
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed openFileDescriptor for $uriString, attempting context URI fallback: ${e.message}")
            }
            extractor.setDataSource(context, parsedUri, null)
            return "content_uri"
        } else if (scheme == "file" || uriString.startsWith("/")) {
            try {
                val filePath = parsedUri.path ?: uriString.removePrefix("file://")
                val file = File(filePath)
                if (file.exists()) {
                    FileInputStream(file).use { fis ->
                        extractor.setDataSource(fis.fd)
                    }
                    return "file_fd"
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed FileInputStream for $uriString, attempting context fallback: ${e.message}")
            }
        }

        extractor.setDataSource(context, parsedUri, null)
        return "fallback_uri"
    }

    /**
     * Decodes PCM audio and extracts a single organic bipolar waveform derived 100% from the audio file:
     * - Divides track duration into SAMPLE_COUNT (160) windows.
     * - Removes DC bias and runs a 2-pole low-pass filter on PCM audio to track authentic low-frequency displacement.
     * - Measures authentic dynamic loudness envelope (Peak + RMS).
     * - Modulates magnitude by real audio displacement with tanh saturation. Zero synthetic sine waves!
     * - Applies a 3-point smoothing filter [0.15, 0.70, 0.15] to eliminate single-sample noise spikes.
     * - Normalizes to ±0.85 with dynamic headroom.
     */
    private fun extractBipolarAudioWaveform(trackId: Long, uriString: String, startTimeMs: Long): List<Float>? {
        val extractor = MediaExtractor()
        val sourceType: String
        try {
            sourceType = setExtractorDataSource(extractor, uriString)

            val audioTrackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                val format = extractor.getTrackFormat(i)
                format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: run {
                Log.e(TAG, "No audio track found in $uriString")
                return null
            }

            extractor.selectTrack(audioTrackIndex)
            val format = extractor.getTrackFormat(audioTrackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION)
            } else 0L

            if (durationUs <= 0) {
                Log.e(TAG, "Invalid audio duration ($durationUs us) in $uriString")
                return null
            }

            val totalPcmSamples = (sampleRate.toLong() * channels * durationUs) / 1_000_000L
            val samplesPerWindow = max(1L, totalPcmSamples / SAMPLE_COUNT)

            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val sampleRateFloat = sampleRate.toFloat()
            val alphaDc = (2.0 * PI * 0.05 / sampleRateFloat).toFloat().coerceIn(0.000001f, 0.001f)
            val fc = 0.35f
            val alphaLp = (2.0 * PI * fc / sampleRateFloat).toFloat().coerceIn(0.00001f, 0.01f)

            var dcEstimate = 0f
            var lp1 = 0f
            var lp2 = 0f

            // Accumulate window statistics from real PCM audio
            val windowMagnitudes = mutableListOf<Float>()
            val windowDisplacements = mutableListOf<Float>()

            var windowSumSquares = 0.0
            var windowPeakAbs = 0f
            var windowSampleCount = 0L
            var isEos = false
            val bufferInfo = MediaCodec.BufferInfo()

            try {
                while (windowMagnitudes.size < SAMPLE_COUNT) {
                    // Feed input buffer
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

                    // Read output buffer
                    val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                    if (outputIndex >= 0) {
                        val outputBuffer = codec.getOutputBuffer(outputIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            outputBuffer.order(ByteOrder.nativeOrder())
                            val shortBuffer = outputBuffer.asShortBuffer()
                            val count = shortBuffer.remaining()

                            for (i in 0 until count) {
                                val rawSample = shortBuffer.get()
                                val norm = rawSample.toFloat() / Short.MAX_VALUE

                                // 1. DC removal
                                dcEstimate += alphaDc * (norm - dcEstimate)
                                val ac = norm - dcEstimate
                                val absAc = abs(ac)

                                // 2. 2-pole low-pass filter tracking authentic musical phrasing
                                lp1 += alphaLp * (ac - lp1)
                                lp2 += alphaLp * (lp1 - lp2)

                                // 3. Window energy accumulation
                                windowSumSquares += (ac * ac)
                                if (absAc > windowPeakAbs) {
                                    windowPeakAbs = absAc
                                }
                                windowSampleCount++

                                if (windowSampleCount >= samplesPerWindow) {
                                    val rms = sqrt(windowSumSquares / windowSampleCount).toFloat()
                                    // Combine peak and RMS for a natural dynamic volume envelope
                                    val mag = (windowPeakAbs * 0.65f + rms * 0.35f).coerceIn(0f, 1f)

                                    windowMagnitudes.add(mag)
                                    windowDisplacements.add(lp2)

                                    windowSumSquares = 0.0
                                    windowPeakAbs = 0f
                                    windowSampleCount = 0

                                    if (windowMagnitudes.size >= SAMPLE_COUNT) break
                                }
                            }
                        }
                        codec.releaseOutputBuffer(outputIndex, false)

                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            if (windowSampleCount > 0 && windowMagnitudes.size < SAMPLE_COUNT) {
                                val rms = sqrt(windowSumSquares / windowSampleCount).toFloat()
                                val mag = (windowPeakAbs * 0.65f + rms * 0.35f).coerceIn(0f, 1f)
                                windowMagnitudes.add(mag)
                                windowDisplacements.add(lp2)
                            }
                            break
                        }
                    }
                }
            } finally {
                codec.stop()
                codec.release()
            }

            if (windowMagnitudes.isEmpty()) return null

            // Pad up to SAMPLE_COUNT if track ended slightly early
            while (windowMagnitudes.size < SAMPLE_COUNT) {
                windowMagnitudes.add(windowMagnitudes.lastOrNull() ?: 0.05f)
                windowDisplacements.add(windowDisplacements.lastOrNull() ?: 0f)
            }

            // Combine authentic audio displacement and loudness envelope:
            // Zero synthetic sine wave. The wave is 100% determined by the audio file.
            val maxDisp = windowDisplacements.maxOfOrNull { abs(it) } ?: 0.0001f
            val rawBipolar = ArrayList<Float>(SAMPLE_COUNT)

            for (i in 0 until SAMPLE_COUNT) {
                val mag = windowMagnitudes[i]
                val disp = windowDisplacements[i]
                val dispNorm = if (maxDisp > 0f) (disp / maxDisp).coerceIn(-1f, 1f) else 0f
                val signFactor = kotlin.math.tanh(dispNorm * 2.2f)
                val signedValue = mag * signFactor
                rawBipolar.add(signedValue)
            }

            // Apply 3-point smoothing filter [0.15, 0.70, 0.15] to eliminate single-sample noise spikes
            val smoothed = ArrayList<Float>(SAMPLE_COUNT)
            for (i in 0 until SAMPLE_COUNT) {
                val prev = rawBipolar.getOrElse(i - 1) { rawBipolar[i] }
                val curr = rawBipolar[i]
                val next = rawBipolar.getOrElse(i + 1) { rawBipolar[i] }
                val smoothVal = (0.15f * prev) + (0.70f * curr) + (0.15f * next)
                smoothed.add(smoothVal)
            }

            // Normalize so loudest peak reaches ~0.85 (preserves dynamic headroom)
            val globalMax = smoothed.maxOfOrNull { abs(it) } ?: 1f
            if (globalMax <= 0f) return smoothed

            val normTarget = 0.85f
            val scaleFactor = normTarget / globalMax
            val finalPeaks = smoothed.map { (it * scaleFactor).coerceIn(-1f, 1f) }

            // Diagnostic logging as requested by Requirement 21
            val elapsedMs = System.currentTimeMillis() - startTimeMs
            val minVal = finalPeaks.minOrNull() ?: 0f
            val maxVal = finalPeaks.maxOrNull() ?: 0f
            val meanVal = finalPeaks.average()
            val zeroCrossings = (0 until finalPeaks.size - 1).count {
                (finalPeaks[it] >= 0f && finalPeaks[it + 1] < 0f) || (finalPeaks[it] < 0f && finalPeaks[it + 1] >= 0f)
            }

            Log.d(
                TAG,
                "trackId=$trackId uri=$uriString duration=${durationUs / 1000}ms sampleCount=${finalPeaks.size} " +
                "min=${String.format(Locale.US, "%.3f", minVal)} max=${String.format(Locale.US, "%.3f", maxVal)} " +
                "mean=${String.format(Locale.US, "%.3f", meanVal)} zeroCrossings=$zeroCrossings " +
                "extractionTimeMs=${elapsedMs} source=$sourceType"
            )

            return finalPeaks

        } catch (e: Exception) {
            Log.e(TAG, "Error extracting waveform for trackId=$trackId from $uriString", e)
            return null
        } finally {
            extractor.release()
        }
    }
}
