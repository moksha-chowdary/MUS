package com.mus.android.data.enrichment.provider

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ArtworkSearchProvider backed by the public iTunes Search API.
 * Returns up to `limit` candidates ranked by relevance.
 * Visually identical results (same artist+album normalized) are deduplicated.
 *
 * All network I/O runs on Dispatchers.IO (enforced by the interface contract).
 */
@Singleton
class ITunesArtworkSearchProvider @Inject constructor() : ArtworkSearchProvider {

    override val name: String = "iTunes"

    override suspend fun searchArtwork(query: String, limit: Int): List<ArtworkSearchResult> =
        withContext(Dispatchers.IO) {
            val trimmedQuery = query.trim()
            if (trimmedQuery.isBlank()) return@withContext emptyList()

            var connection: HttpURLConnection? = null
            try {
                val encodedQuery = URLEncoder.encode(trimmedQuery, "UTF-8")
                // Fetch more than requested so we can deduplicate and still return `limit` results
                val fetchLimit = (limit * 2).coerceAtMost(50)
                val endpoint =
                    "https://itunes.apple.com/search?term=$encodedQuery&media=music&entity=song&limit=$fetchLimit"
                connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8_000
                    readTimeout = 8_000
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:120.0) Gecko/120.0 Firefox/120.0")
                    setRequestProperty("Accept", "application/json")
                }

                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    Log.w(TAG, "iTunes artwork search HTTP ${connection.responseCode} for '$trimmedQuery'")
                    return@withContext emptyList()
                }

                val json = connection.inputStream.bufferedReader().use(BufferedReader::readText)
                parseAndDeduplicate(json, limit)
            } catch (e: Exception) {
                Log.w(TAG, "iTunes artwork search failed for '$trimmedQuery': ${e.message}")
                emptyList()
            } finally {
                connection?.disconnect()
            }
        }

    private fun parseAndDeduplicate(jsonString: String, limit: Int): List<ArtworkSearchResult> {
        val results = mutableListOf<ArtworkSearchResult>()
        val seenKeys = mutableSetOf<String>()

        try {
            val root = JSONObject(jsonString)
            val items = root.optJSONArray("results") ?: return emptyList()

            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue

                val trackName = item.optString("trackName").trim().takeIf { it.isNotBlank() } ?: continue
                val artistName = item.optString("artistName").trim().takeIf { it.isNotBlank() } ?: continue
                val collection = item.optString("collectionName").trim().takeIf { it.isNotBlank() }
                val trackId = item.optLong("trackId", 0L)

                // Deduplication key: normalize artist + album
                val dedupeKey = "${artistName.lowercase().trim()}_${(collection ?: trackName).lowercase().trim()}"
                if (!seenKeys.add(dedupeKey)) continue

                val releaseDate = item.optString("releaseDate")
                val year = if (releaseDate.length >= 4) releaseDate.substring(0, 4).toIntOrNull() ?: 0 else 0

                val rawArtwork = item.optString("artworkUrl100").takeIf { it.isNotBlank() }
                val hiResArtwork = rawArtwork?.replace("100x100bb.jpg", "600x600bb.jpg")
                    ?.replace("100x100", "600x600")

                val durationMs = item.optLong("trackTimeMillis", 0L)

                results.add(
                    ArtworkSearchResult(
                        id = trackId.toString(),
                        title = trackName,
                        artist = artistName,
                        album = collection,
                        albumArtist = artistName,
                        year = year,
                        artworkUrl = hiResArtwork,
                        durationMs = durationMs,
                        provider = name,
                        confidence = 1.0f,
                    )
                )
                if (results.size >= limit) break
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing iTunes artwork search results", e)
        }
        return results
    }

    companion object {
        private const val TAG = "ITunesArtworkSearch"
    }
}
