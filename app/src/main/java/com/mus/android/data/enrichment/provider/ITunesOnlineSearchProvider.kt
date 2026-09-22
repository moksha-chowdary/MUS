package com.mus.android.data.enrichment.provider

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OnlineMusicSearchProvider backed by the public iTunes Search API.
 * Returns music tracks with artist, album, artwork, and duration.
 * No API key required.
 */
@Singleton
class ITunesOnlineSearchProvider @Inject constructor() : OnlineMusicSearchProvider {

    override val name: String = "iTunes"

    override suspend fun search(query: String, limit: Int): List<OnlineSearchResult> =
        withContext(Dispatchers.IO) {
            val trimmedQuery = query.trim()
            if (trimmedQuery.isBlank()) return@withContext emptyList()

            var connection: HttpURLConnection? = null
            try {
                val encodedQuery = URLEncoder.encode(trimmedQuery, "UTF-8")
                val endpoint =
                    "https://itunes.apple.com/search?term=$encodedQuery&media=music&entity=song&limit=${limit.coerceAtMost(50)}"
                connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8_000
                    readTimeout = 8_000
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:120.0) Gecko/120.0 Firefox/120.0")
                }

                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    Log.w(TAG, "iTunes online search HTTP ${connection.responseCode}")
                    return@withContext emptyList()
                }

                val json = connection.inputStream.bufferedReader().use { it.readText() }
                parseResults(json, limit)
            } catch (e: Exception) {
                Log.w(TAG, "iTunes online search failed: ${e.message}")
                emptyList()
            } finally {
                connection?.disconnect()
            }
        }

    private fun parseResults(jsonString: String, limit: Int): List<OnlineSearchResult> {
        val results = mutableListOf<OnlineSearchResult>()
        try {
            val root = JSONObject(jsonString)
            val items = root.optJSONArray("results") ?: return emptyList()

            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val trackId = item.optLong("trackId", 0L)
                if (trackId == 0L) continue

                val title = item.optString("trackName").trim().takeIf { it.isNotBlank() } ?: continue
                val artist = item.optString("artistName").trim().takeIf { it.isNotBlank() } ?: continue
                val album = item.optString("collectionName").trim().takeIf { it.isNotBlank() }
                val durationMs = item.optLong("trackTimeMillis", 0L)

                val rawArtwork = item.optString("artworkUrl100").takeIf { it.isNotBlank() }
                val hiResArtwork = rawArtwork?.replace("100x100bb.jpg", "600x600bb.jpg")
                    ?.replace("100x100", "600x600")

                val trackUrl = item.optString("trackViewUrl").takeIf { it.isNotBlank() }
                    ?: "https://music.apple.com/search?term=${URLEncoder.encode("$artist $title", "UTF-8")}"

                results.add(
                    OnlineSearchResult(
                        id = trackId.toString(),
                        title = title,
                        artist = artist,
                        album = album,
                        artworkUrl = hiResArtwork,
                        source = name,
                        sourceId = trackId.toString(),
                        sourceUrl = trackUrl,
                        durationMs = durationMs,
                    )
                )
                if (results.size >= limit) break
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing iTunes online search results", e)
        }
        return results
    }

    companion object {
        private const val TAG = "ITunesOnlineSearch"
    }
}
