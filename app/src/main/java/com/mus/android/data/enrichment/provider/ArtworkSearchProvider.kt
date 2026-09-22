package com.mus.android.data.enrichment.provider

/**
 * Normalized result returned by any ArtworkSearchProvider.
 * The UI works against this type and never knows which provider produced it.
 */
data class ArtworkSearchResult(
    val id: String,           // provider-scoped unique ID
    val title: String,
    val artist: String,
    val album: String?,
    val albumArtist: String?,
    val year: Int,
    val artworkUrl: String?,  // remote URL for display; stored locally only after user confirmation
    val durationMs: Long,
    val provider: String,     // e.g. "iTunes"
    val confidence: Float,    // 0.0–1.0 relevance score
)

/**
 * Provider-agnostic interface for searching artwork candidates.
 * Implementations may wrap iTunes, Deezer, MusicBrainz, etc.
 * The UI depends only on this interface.
 */
interface ArtworkSearchProvider {
    val name: String

    /**
     * Searches for artwork candidates matching the query.
     * Must run on Dispatchers.IO; callers guarantee coroutine context.
     * Returns an empty list (never throws) when no results or network unavailable.
     */
    suspend fun searchArtwork(query: String, limit: Int = 12): List<ArtworkSearchResult>
}
