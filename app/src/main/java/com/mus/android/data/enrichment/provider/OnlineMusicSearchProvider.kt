package com.mus.android.data.enrichment.provider

/**
 * Normalized result returned by any OnlineMusicSearchProvider.
 * Clearly distinct from the local Track model — never merged into the local library automatically.
 */
data class OnlineSearchResult(
    val id: String,               // provider-scoped unique ID (e.g. YouTube videoId)
    val title: String,
    val artist: String,           // channel name for YouTube, artist name for iTunes
    val album: String? = null,    // album name from iTunes; null for YouTube video results
    val artworkUrl: String?,      // remote thumbnail / cover URL
    val source: String,           // "YouTube", "iTunes", etc.
    val sourceId: String,         // provider-native ID
    val sourceUrl: String,        // canonical URL to open in browser / official app
    val durationMs: Long = 0L,
)

/**
 * Provider-agnostic interface for online music discovery.
 * Implementations use YouTube Data API, iTunes, etc.
 * The UI and DownloadQueueRepository depend only on this interface.
 */
interface OnlineMusicSearchProvider {
    val name: String

    /**
     * Searches online music sources for the given query.
     * Must run on Dispatchers.IO; callers guarantee coroutine context.
     * Returns an empty list (never throws) when unavailable.
     */
    suspend fun search(query: String, limit: Int = 20): List<OnlineSearchResult>
}
