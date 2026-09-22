package com.mus.android.data.enrichment.provider

import android.util.Log
import com.mus.android.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OnlineMusicSearchProvider backed by the official YouTube Data API v3.
 *
 * Uses the `search.list` endpoint with:
 *   - type=video
 *   - videoCategoryId=10 (Music)
 *   - relevanceLanguage from device locale
 *
 * Requires BuildConfig.YOUTUBE_API_KEY to be set (via local.properties youtube.api.key).
 * If the key is blank the provider returns an empty list and logs a warning.
 *
 * MUS does NOT download or extract audio from YouTube.
 * Results are discovery candidates only.
 */
@Singleton
class YouTubeMusicSearchProvider @Inject constructor() : OnlineMusicSearchProvider {

    override val name: String = "YouTube"

    private val apiKey: String get() = BuildConfig.YOUTUBE_API_KEY

    override suspend fun search(query: String, limit: Int): List<OnlineSearchResult> =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) {
                Log.w(TAG, "YouTube API key not configured. Set youtube.api.key in local.properties.")
                return@withContext emptyList()
            }

            val trimmedQuery = query.trim()
            if (trimmedQuery.isBlank()) return@withContext emptyList()

            var connection: HttpURLConnection? = null
            try {
                val encodedQuery = URLEncoder.encode(trimmedQuery, "UTF-8")
                val locale = java.util.Locale.getDefault()
                val regionCode = locale.country.takeIf { it.isNotBlank() } ?: "US"
                val langCode = locale.language.takeIf { it.isNotBlank() } ?: "en"

                val url = "https://www.googleapis.com/youtube/v3/search" +
                        "?part=snippet" +
                        "&q=$encodedQuery" +
                        "&type=video" +
                        "&videoCategoryId=10" +
                        "&maxResults=${limit.coerceAtMost(50)}" +
                        "&regionCode=$regionCode" +
                        "&relevanceLanguage=$langCode" +
                        "&key=$apiKey"

                connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8_000
                    readTimeout = 8_000
                }

                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    Log.w(TAG, "YouTube search HTTP ${connection.responseCode} for '$trimmedQuery'")
                    return@withContext emptyList()
                }

                val json = connection.inputStream.bufferedReader().use { it.readText() }
                parseResults(json)
            } catch (e: Exception) {
                Log.w(TAG, "YouTube search failed for '$trimmedQuery': ${e.message}")
                emptyList()
            } finally {
                connection?.disconnect()
            }
        }

    private fun parseResults(jsonString: String): List<OnlineSearchResult> {
        val results = mutableListOf<OnlineSearchResult>()
        try {
            val root = JSONObject(jsonString)
            val items = root.optJSONArray("items") ?: return emptyList()

            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val idObj = item.optJSONObject("id") ?: continue
                val videoId = idObj.optString("videoId").takeIf { it.isNotBlank() } ?: continue

                val snippet = item.optJSONObject("snippet") ?: continue
                val title = snippet.optString("title").trim().takeIf { it.isNotBlank() } ?: continue
                val channel = snippet.optString("channelTitle").trim().ifBlank { "YouTube" }

                // Best available thumbnail: maxres → high → medium → default
                val thumbnails = snippet.optJSONObject("thumbnails")
                val artworkUrl = thumbnails?.run {
                    optJSONObject("maxres")?.optString("url")
                        ?: optJSONObject("high")?.optString("url")
                        ?: optJSONObject("medium")?.optString("url")
                        ?: optJSONObject("default")?.optString("url")
                }?.takeIf { it.isNotBlank() }

                results.add(
                    OnlineSearchResult(
                        id = videoId,
                        title = title,
                        artist = channel,
                        album = null,
                        artworkUrl = artworkUrl,
                        source = name,
                        sourceId = videoId,
                        sourceUrl = "https://www.youtube.com/watch?v=$videoId",
                        durationMs = 0L, // duration requires a videos.list call; omitted for performance
                    )
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing YouTube search results", e)
        }
        return results
    }

    companion object {
        private const val TAG = "YouTubeSearchProvider"
    }
}
