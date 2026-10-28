package com.mus.android.playback

import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import com.mus.android.data.music.InnerTubeMusicApi
import dagger.Lazy
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Translates `yt://<videoId>` DataSpecs into expiring googlevideo URLs at
 * open time, ported from LastWave's stream resolution. Resolution runs on
 * ExoPlayer's loading thread (never the main thread) and reuses the
 * InnerTube client's stream cache, PoToken fast path and NewPipe fallback.
 */
@Singleton
class YtPlaybackResolver @Inject constructor(
    private val innerTube: Lazy<InnerTubeMusicApi>,
) : ResolvingDataSource.Resolver {

    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        val uri = dataSpec.uri
        if (!uri.scheme.equals(YT_SCHEME, ignoreCase = true)) return dataSpec
        val videoId = videoIdOf(uri) ?: return dataSpec
        val stream = runBlocking {
            withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
                innerTube.get().resolveAudioStream(videoId)
            }
        } ?: throw IOException("Could not resolve YouTube stream for $videoId")
        return dataSpec.buildUpon()
            .setUri(Uri.parse(stream.url))
            .setHttpRequestHeaders(stream.requestHeaders)
            .build()
    }

    override fun resolveReportedUri(uri: Uri): Uri = uri

    companion object {
        const val YT_SCHEME = "yt"
        private const val RESOLVE_TIMEOUT_MS = 25_000L

        /** Stable negative id space for remote tracks — never collides with the local library. */
        fun remoteTrackId(videoId: String): Long =
            -(videoId.hashCode().toLong() and 0x7FFFFFFFL) - 1L

        /** `yt://VIDEOID` or `yt://play/VIDEOID` → VIDEOID (case preserved). */
        fun videoIdOf(uri: Uri): String? {
            val raw = uri.toString()
            if (!raw.startsWith("$YT_SCHEME://", ignoreCase = true)) return null
            return raw.substring(YT_SCHEME.length + 3)
                .substringBefore('/')
                .trim()
                .takeIf { it.isNotEmpty() }
        }
    }
}
