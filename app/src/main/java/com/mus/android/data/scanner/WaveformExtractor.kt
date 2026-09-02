package com.mus.android.data.scanner

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.mus.android.data.db.WaveformDao
import com.mus.android.data.model.WaveformData
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
 * Decodes the audio to PCM, then downsamples into ~200 normalized peak values (0.0–1.0).
 * Results are cached in Room via WaveformDao.
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
     * Returns cached waveform data if available, otherwise extracts it from the audio file.
     * Returns null if extraction fails.
     */
    suspend fun getWaveform(trackId: Long, uri: String): List<Float>? {
        // Check cache first
        waveformDao.getWaveform(trackId)?.let { cached ->
            return parsePeaks(cached.peaks)
        }

        // Extract from audio
        val peaks = withContext(Dispatchers.Default) {
            try {
                extractPeaks(Uri.parse(uri))
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        } ?: return null

        // Cache the result
        val peaksString = peaks.joinToString(",") { "%.4f".format(it) }
        waveformDao.insert(
            WaveformData(
                trackId = trackId,
                peaks = peaksString,
                sampleCount = peaks.size,
            )
        )

        return peaks
    }

    private fun parsePeaks(peaksString: String): List<Float> {
        return peaksString.split(",").mapNotNull { it.toFloatOrNull() }
    }

    /**
     * Decodes the audio file and extracts normalized amplitude peaks.
     */
    private fun extractPeaks(uri: Uri): List<Float>? {
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

            // Total PCM samples expected
            val totalSamples = (sampleRate.toLong() * channels * duration) / 1_000_000L
            val samplesPerBucket = max(1L, totalSamples / SAMPLE_COUNT)

            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val peaks = mutableListOf<Float>()
            var bucketMax = 0f
            var bucketSampleCount = 0L
            var isEos = false
            val bufferInfo = MediaCodec.BufferInfo()

            try {
                while (peaks.size < SAMPLE_COUNT) {
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
                                val normalized = abs(sample.toFloat()) / Short.MAX_VALUE
                                bucketMax = max(bucketMax, normalized)
                                bucketSampleCount++

                                if (bucketSampleCount >= samplesPerBucket) {
                                    peaks.add(bucketMax)
                                    bucketMax = 0f
                                    bucketSampleCount = 0
                                    if (peaks.size >= SAMPLE_COUNT) break
                                }
                            }
                        }
                        codec.releaseOutputBuffer(outputIndex, false)

                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            // Flush remaining bucket
                            if (bucketSampleCount > 0 && peaks.size < SAMPLE_COUNT) {
                                peaks.add(bucketMax)
                            }
                            break
                        }
                    }
                }
            } finally {
                codec.stop()
                codec.release()
            }

            // Normalize peaks to 0.0–1.0 range relative to the track's own max
            val globalMax = peaks.maxOrNull() ?: 1f
            if (globalMax > 0f) {
                return peaks.map { min(1f, it / globalMax) }
            }
            return peaks

        } catch (e: Exception) {
            e.printStackTrace()
            return null
        } finally {
            extractor.release()
        }
    }
}
