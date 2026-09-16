package com.mus.android.data.enrichment.provider

data class RemoteTrackMetadata(
    val title: String,
    val artist: String,
    val albumTitle: String? = null,
    val albumArtist: String? = null,
    val trackNumber: Int = 0,
    val discNumber: Int = 1,
    val year: Int = 0,
    val genre: String? = null,
    val composer: String? = null,
    val durationMs: Long = 0L,
    val artworkUrl: String? = null,
    val artistArtworkUrl: String? = null,
)

/**
 * Replaceable abstraction for external metadata and artwork providers.
 * Decouples the application logic from any specific API implementation.
 */
interface MetadataProvider {
    val name: String
    suspend fun searchTrack(query: String, limit: Int = 5, country: String? = null): List<RemoteTrackMetadata>
}
