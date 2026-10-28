package com.mus.android.data.enrichment.provider

import com.mus.android.data.music.InnerTubeMusicApi
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Online discovery backed by YouTube Music's private InnerTube catalogue
 * (WEB_REMIX search), ported from LastWave. Replaces the old YouTube Data
 * API v3 provider: no API key is needed, results carry real song metadata
 * (videoId as sourceId), and the same videoId drives playback, streaming
 * and downloads elsewhere in the app.
 */
@Singleton
class InnerTubeMusicSearchProvider @Inject constructor(
    private val innerTube: InnerTubeMusicApi,
) : OnlineMusicSearchProvider {

    override val name: String = "YouTube Music"

    override suspend fun search(query: String, limit: Int): List<OnlineSearchResult> {
        if (query.isBlank()) return emptyList()
        return try {
            innerTube.searchSongs(query = query, limit = limit).map { track ->
                OnlineSearchResult(
                    id = track.videoId,
                    title = track.title,
                    artist = track.artist,
                    album = track.album,
                    artworkUrl = track.artworkUrl,
                    source = "YouTube Music",
                    sourceId = track.videoId,
                    sourceUrl = "https://www.youtube.com/watch?v=${track.videoId}",
                    durationMs = (track.durationSeconds?.toLong() ?: 0L) * 1_000L,
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Provider contract: return empty on unavailability, never throw.
            emptyList()
        }
    }
}
