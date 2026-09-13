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
 * Concrete MetadataProvider implementation powered by the public iTunes Search API.
 * Does not require authentication or API keys.
 *
 * Supports India storefront (`country=IN`) for Bollywood, Telugu, Tamil, and regional music,
 * falling back to US storefront for English/international tracks.
 *
 * Correctly parses `collectionArtistName` for album artist (distinct from track `artistName`
 * for compilation albums and soundtracks).
 */
@Singleton
class ITunesMetadataProvider @Inject constructor() : MetadataProvider {

    override val name: String = "iTunes"

    override suspend fun searchTrack(query: String, limit: Int): List<RemoteTrackMetadata> = withContext(Dispatchers.IO) {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isBlank()) return@withContext emptyList()

        // Keep country=IN as primary storefront since MUS is primarily used with Indian regional music,
        // falling back to US storefront if IN returns no results
        var country = "IN"

        var connection: HttpURLConnection? = null
        var attempts = 0
        while (attempts < 2) {
            attempts++
            try {
                val encodedQuery = URLEncoder.encode(trimmedQuery, "UTF-8")
                val endpoint = "https://itunes.apple.com/search?term=$encodedQuery&media=music&entity=song&limit=$limit&country=$country"
                val url = URL(endpoint)

                connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:120.0) Gecko/120.0 Firefox/120.0")
                    setRequestProperty("Accept", "application/json")
                }

                if (connection.responseCode == 429 || connection.responseCode == 403) {
                    throw java.io.IOException("iTunes rate limited: HTTP ${connection.responseCode}")
                }

                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    Log.w(TAG, "iTunes API error: HTTP ${connection.responseCode}")

                    // If India storefront returned empty/error, fallback to US
                    if (country == "IN" && attempts < 2) {
                        connection.disconnect()
                        connection = null
                        Log.d(TAG, "Retrying with US storefront for '$trimmedQuery'")
                        val fallbackEndpoint = "https://itunes.apple.com/search?term=$encodedQuery&media=music&entity=song&limit=$limit&country=US"
                        val fallbackUrl = URL(fallbackEndpoint)
                        connection = (fallbackUrl.openConnection() as HttpURLConnection).apply {
                            requestMethod = "GET"
                            connectTimeout = 8000
                            readTimeout = 8000
                            setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:120.0) Gecko/120.0 Firefox/120.0")
                            setRequestProperty("Accept", "application/json")
                        }
                        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                            return@withContext emptyList()
                        }
                    } else {
                        return@withContext emptyList()
                    }
                }

                val responseText = connection.inputStream.bufferedReader().use(BufferedReader::readText)
                val results = parseResults(responseText)

                // If India storefront returned no results, try US storefront
                if (results.isEmpty() && country == "IN" && attempts < 2) {
                    connection.disconnect()
                    connection = null
                    Log.d(TAG, "India storefront returned no results, retrying with US for '$trimmedQuery'")
                    val fallbackEndpoint = "https://itunes.apple.com/search?term=$encodedQuery&media=music&entity=song&limit=$limit&country=US"
                    val fallbackUrl = URL(fallbackEndpoint)
                    connection = (fallbackUrl.openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 8000
                        readTimeout = 8000
                        setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:120.0) Gecko/120.0 Firefox/120.0")
                        setRequestProperty("Accept", "application/json")
                    }
                    if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                        val fallbackText = connection.inputStream.bufferedReader().use(BufferedReader::readText)
                        return@withContext parseResults(fallbackText)
                    }
                    return@withContext emptyList()
                }

                return@withContext results
            } catch (e: java.net.UnknownHostException) {
                if (attempts < 2) {
                    Log.d(TAG, "DNS resolution failed, retrying in 1s for '$trimmedQuery'...")
                    kotlinx.coroutines.delay(1000L)
                    continue
                }
                Log.w(TAG, "Failed searching iTunes metadata for query '$trimmedQuery': ${e.message}")
                throw e
            } catch (e: java.io.IOException) {
                Log.w(TAG, "Failed searching iTunes metadata for query '$trimmedQuery': ${e.message}")
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed searching iTunes metadata for query '$trimmedQuery': ${e.message}")
                return@withContext emptyList()
            } finally {
                connection?.disconnect()
            }
        }
        emptyList()
    }

    private fun parseResults(jsonString: String): List<RemoteTrackMetadata> {
        val resultsList = mutableListOf<RemoteTrackMetadata>()
        try {
            val root = JSONObject(jsonString)
            val count = root.optInt("resultCount", 0)
            if (count == 0) return emptyList()

            val items = root.optJSONArray("results") ?: return emptyList()
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue

                val trackName = item.optString("trackName").trim()
                val artistName = item.optString("artistName").trim()
                if (trackName.isBlank() || artistName.isBlank()) continue

                val collectionName = item.optString("collectionName").takeIf { it.isNotBlank() }
                val trackNumber = item.optInt("trackNumber", 0)
                val discNumber = item.optInt("discNumber", 1)
                val genre = item.optString("primaryGenreName").takeIf { it.isNotBlank() }
                val durationMs = item.optLong("trackTimeMillis", 0L)

                // FIX: Parse collectionArtistName for album artist (distinct from track artistName)
                // This is critical for compilation albums and soundtracks
                val collectionArtistName = item.optString("collectionArtistName").takeIf { it.isNotBlank() }
                val albumArtist = collectionArtistName ?: artistName

                // Year extraction from releaseDate e.g. "2020-03-20T07:00:00Z"
                val releaseDate = item.optString("releaseDate")
                val year = if (releaseDate.length >= 4) {
                    releaseDate.substring(0, 4).toIntOrNull() ?: 0
                } else 0

                // Artwork: Upgrade standard 100x100 to 600x600 for sharp cover art
                val rawArtworkUrl = item.optString("artworkUrl100").takeIf { it.isNotBlank() }
                val hiResArtworkUrl = rawArtworkUrl?.let { url ->
                    if (url.contains("100x100bb.jpg")) {
                        url.replace("100x100bb.jpg", "600x600bb.jpg")
                    } else if (url.contains("100x100")) {
                        url.replace("100x100", "600x600")
                    } else {
                        url
                    }
                }

                resultsList.add(
                    RemoteTrackMetadata(
                        title = trackName,
                        artist = artistName,
                        albumTitle = collectionName,
                        albumArtist = albumArtist,
                        trackNumber = trackNumber,
                        discNumber = discNumber,
                        year = year,
                        genre = genre,
                        composer = null,
                        durationMs = durationMs,
                        artworkUrl = hiResArtworkUrl,
                        artistArtworkUrl = null,
                    )
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing iTunes JSON response", e)
        }
        return resultsList
    }

    /**
     * Heuristic to determine if a search query likely targets Indian/regional music.
     * Uses common Hindi/Telugu/Tamil keywords and artist names.
     */
    private fun isLikelyIndianMusic(query: String): Boolean {
        val lower = query.lowercase()
        val indianIndicators = listOf(
            // Language/genre indicators
            "bollywood", "tollywood", "kollywood", "hindi", "telugu", "tamil",
            "punjabi", "bhangra", "ghazal", "qawwali", "bhajan", "devotional",
            "carnatic", "hindustani", "filmi",
            // Common Hindi tokens
            "dil", "pyaar", "ishq", "tera", "mera", "tum", "hum", "zindagi",
            "duniya", "mohabbat", "deewana", "sajna", "rabba", "khuda",
            // Common Bollywood artists
            "arijit", "shreya", "atif", "neha kakkar", "kumar sanu", "kishore kumar",
            "lata mangeshkar", "a.r. rahman", "ar rahman", "pritam", "vishal",
            // Telugu/Tamil indicators
            "thaman", "anirudh", "sid sriram", "srivalli", "naatu", "pushpa",
        )
        return indianIndicators.any { lower.contains(it) }
    }

    companion object {
        private const val TAG = "ITunesMetadataProvider"
    }
}
