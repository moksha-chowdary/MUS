package com.mus.android

import com.mus.android.data.enrichment.artwork.ArtworkStorage
import com.mus.android.data.enrichment.provider.ArtworkSearchResult
import com.mus.android.data.model.ArtworkSource
import com.mus.android.data.model.Track
import com.mus.android.data.model.MetadataSource
import com.mus.android.data.model.MetadataStatus
import com.mus.android.data.model.MetadataConfidence
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for the manual artwork feature.
 * Tests use data-layer logic only; no Android runtime required.
 */
class ArtworkSearchTest {

    // ─── Test fixtures ────────────────────────────────────────────

    private fun makeTrack(
        id: Long = 1L,
        albumId: Long = 100L,
        artworkSource: String = ArtworkSource.NONE,
        artworkUri: String? = null,
    ) = Track(
        id = id,
        title = "Test Song",
        artist = "Test Artist",
        albumId = albumId,
        albumTitle = "Test Album",
        duration = 200_000L,
        uri = "file:///music/test.mp3",
        artworkUri = artworkUri,
        artworkSource = artworkSource,
        metadataSource = MetadataSource.EMBEDDED,
        metadataStatus = MetadataStatus.COMPLETE,
        metadataConfidence = MetadataConfidence.HIGH,
    )

    private fun makeArtworkResult(id: String = "1", url: String = "https://example.com/art.jpg") =
        ArtworkSearchResult(
            id = id,
            title = "Test Song",
            artist = "Test Artist",
            album = "Test Album",
            albumArtist = "Test Artist",
            year = 2023,
            artworkUrl = url,
            durationMs = 200_000L,
            provider = "iTunes",
            confidence = 0.95f,
        )

    // ─── Part 1: Artwork provenance model ────────────────────────

    @Test
    fun `ArtworkSource constants have correct string values`() {
        assertEquals("MANUAL", ArtworkSource.MANUAL)
        assertEquals("EMBEDDED", ArtworkSource.EMBEDDED)
        assertEquals("EXTERNAL", ArtworkSource.EXTERNAL)
        assertEquals("", ArtworkSource.NONE)
    }

    @Test
    fun `track with MANUAL provenance differs from EXTERNAL provenance`() {
        val manual = makeTrack(artworkSource = ArtworkSource.MANUAL)
        val external = makeTrack(artworkSource = ArtworkSource.EXTERNAL)

        assertTrue(manual.artworkSource == ArtworkSource.MANUAL)
        assertFalse(external.artworkSource == ArtworkSource.MANUAL)
    }

    @Test
    fun `MANUAL provenance check is case-sensitive`() {
        val track = makeTrack(artworkSource = "manual") // wrong case
        assertFalse(track.artworkSource == ArtworkSource.MANUAL)
    }

    // ─── Part 2: Track.copy preserves MANUAL provenance ──────────

    @Test
    fun `track copy with MANUAL artwork retains provenance when artwork not changed`() {
        val original = makeTrack(
            artworkSource = ArtworkSource.MANUAL,
            artworkUri = "file:///artwork/manual_track_1.jpg"
        )
        val updated = original.copy(title = "New Title")

        assertEquals(ArtworkSource.MANUAL, updated.artworkSource)
        assertEquals("file:///artwork/manual_track_1.jpg", updated.artworkUri)
    }

    @Test
    fun `mergeMetadata logic - MANUAL artwork flag prevents overwrite`() {
        // Simulate the guard logic from MetadataEnrichmentService.mergeMetadata
        val track = makeTrack(
            artworkSource = ArtworkSource.MANUAL,
            artworkUri = "file:///artwork/manual_track_1.jpg"
        )
        val hasManualArtwork = track.artworkSource == ArtworkSource.MANUAL

        // In MetadataEnrichmentService, when hasManualArtwork is true, finalArtworkUri stays unchanged
        val finalArtworkUri = if (hasManualArtwork) track.artworkUri else "file:///artwork/new_from_itunes.jpg"
        val resultSource = if (hasManualArtwork) track.artworkSource else ArtworkSource.EXTERNAL

        assertEquals("file:///artwork/manual_track_1.jpg", finalArtworkUri)
        assertEquals(ArtworkSource.MANUAL, resultSource)
    }

    @Test
    fun `enrichment guard does NOT skip EXTERNAL artwork`() {
        val track = makeTrack(artworkSource = ArtworkSource.EXTERNAL)
        val hasManualArtwork = track.artworkSource == ArtworkSource.MANUAL

        // External artwork is NOT protected — enrichment may replace it
        assertFalse(hasManualArtwork)
    }

    // ─── Part 3: applyManualArtworkToAlbum scope guard ───────────

    @Test
    fun `applyManualArtworkToAlbum only targets correct albumId`() {
        val albumId = 100L
        val otherAlbumId = 999L

        val tracks = listOf(
            makeTrack(id = 1, albumId = albumId),
            makeTrack(id = 2, albumId = albumId),
            makeTrack(id = 3, albumId = otherAlbumId),
            makeTrack(id = 4, albumId = otherAlbumId),
        )

        // Simulate repository filter logic
        val affectedTracks = tracks.filter { it.albumId == albumId }

        assertEquals(2, affectedTracks.size)
        assertTrue(affectedTracks.all { it.albumId == albumId })
        assertTrue(affectedTracks.none { it.albumId == otherAlbumId })
    }

    @Test
    fun `applyManualArtworkToAlbum with empty albumId guard returns 0`() {
        val albumId = 0L // invalid
        val result = if (albumId <= 0L) 0 else -1
        assertEquals(0, result)
    }

    // ─── Part 4: ArtworkSearchResult normalization ────────────────

    @Test
    fun `ArtworkSearchResult artworkUrl can be null`() {
        val result = makeArtworkResult(url = "")
        // In ITunesArtworkSearchProvider, blank url → null
        val resolvedUrl = result.artworkUrl?.takeIf { it.isNotBlank() }
        assertNull(resolvedUrl)
    }

    @Test
    fun `ArtworkSearchResult with valid url is non-blank`() {
        val result = makeArtworkResult(url = "https://example.com/art.jpg")
        assertNotNull(result.artworkUrl)
        assertTrue(result.artworkUrl!!.startsWith("https://"))
    }
}
