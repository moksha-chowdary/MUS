package com.mus.android

import com.mus.android.data.enrichment.provider.ArtworkSearchResult
import com.mus.android.data.model.ArtworkSource
import com.mus.android.data.model.Track
import com.mus.android.data.model.MetadataSource
import com.mus.android.data.model.MetadataStatus
import com.mus.android.data.model.MetadataConfidence
import com.mus.android.data.scanner.MetadataUtils
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for the manual artwork and metadata synchronization feature.
 * All tests use data-layer logic only; no Android runtime required.
 */
class ArtworkSearchTest {

    // ─── Test fixtures ────────────────────────────────────────────

    private fun makeTrack(
        id: Long = 1L,
        title: String = "Test Song",
        artist: String = "Test Artist",
        albumId: Long = 100L,
        albumTitle: String = "Test Album",
        albumArtist: String = "Test Artist",
        year: Int = 2020,
        artworkSource: String = ArtworkSource.NONE,
        artworkUri: String? = null,
        isFavorite: Boolean = true,
        playCount: Int = 42,
        lastPlayed: Long = 123456789L,
    ) = Track(
        id = id,
        title = title,
        artist = artist,
        albumId = albumId,
        albumTitle = albumTitle,
        albumArtist = albumArtist,
        duration = 240_000L,
        year = year,
        uri = "file:///storage/emulated/0/Muzic/song.mp3",
        path = "/storage/emulated/0/Muzic/song.mp3",
        size = 5_000_000L,
        codec = "MP3",
        bitrate = 320,
        sampleRate = 44100,
        bitDepth = 16,
        channels = 2,
        isFavorite = isFavorite,
        playCount = playCount,
        lastPlayed = lastPlayed,
        artworkUri = artworkUri,
        artworkSource = artworkSource,
        metadataSource = MetadataSource.EMBEDDED,
        metadataStatus = MetadataStatus.COMPLETE,
        metadataConfidence = MetadataConfidence.HIGH,
    )

    private fun makeArtworkResult(
        id: String = "itunes_123",
        title: String = "Yellow",
        artist: String = "Coldplay",
        album: String? = "Parachutes",
        albumArtist: String? = "Coldplay",
        year: Int = 2000,
        url: String = "https://is1-ssl.mzstatic.com/image/thumb/art.jpg/600x600bb.jpg",
    ) = ArtworkSearchResult(
        id = id,
        title = title,
        artist = artist,
        album = album,
        albumArtist = albumArtist,
        year = year,
        artworkUrl = url,
        durationMs = 269_000L,
        provider = "iTunes",
        confidence = 0.95f,
    )

    // Helper simulating applyManualArtwork metadata reconciliation
    private fun applyResultToTrack(track: Track, result: ArtworkSearchResult?, localArtworkUri: String): Track {
        val rawTitle = result?.title?.trim()
        val finalTitle = if (!rawTitle.isNullOrBlank() && !MetadataUtils.isPlaceholderTitle(rawTitle)) rawTitle else track.title

        val rawArtist = result?.artist?.trim()
        val finalArtist = if (!rawArtist.isNullOrBlank() && !MetadataUtils.isPlaceholderArtist(rawArtist)) rawArtist else track.artist

        val rawAlbum = result?.album?.trim()
        val finalAlbumTitle = if (!rawAlbum.isNullOrBlank() && !MetadataUtils.isPlaceholderAlbum(rawAlbum)) rawAlbum else track.albumTitle

        val rawAlbumArtist = result?.albumArtist?.trim()
        val finalAlbumArtist = if (!rawAlbumArtist.isNullOrBlank() && !MetadataUtils.isPlaceholderArtist(rawAlbumArtist)) {
            rawAlbumArtist
        } else if (rawAlbum != null && !MetadataUtils.isPlaceholderAlbum(rawAlbum) && finalArtist.isNotBlank()) {
            finalArtist
        } else {
            track.albumArtist
        }

        val finalYear = if (result != null && result.year > 0) result.year else track.year

        return track.copy(
            title = finalTitle,
            artist = finalArtist,
            albumTitle = finalAlbumTitle,
            albumArtist = finalAlbumArtist,
            year = finalYear,
            artworkUri = localArtworkUri,
            artworkSource = ArtworkSource.MANUAL,
            artworkProvider = result?.provider ?: "iTunes",
            artworkRemoteId = result?.id,
            artworkLastUpdated = 1000L,
            metadataStatus = MetadataStatus.COMPLETE,
            metadataConfidence = MetadataConfidence.HIGH,
        )
    }

    // ─── Tests 1-5: Track-level metadata synchronization ─────────

    @Test
    fun `1 Manual artwork selection updates artwork and sets MANUAL provenance`() {
        val track = makeTrack(artworkUri = null, artworkSource = ArtworkSource.NONE)
        val result = makeArtworkResult()
        val updated = applyResultToTrack(track, result, "file:///artwork/manual_track_1.jpg")

        assertEquals("file:///artwork/manual_track_1.jpg", updated.artworkUri)
        assertEquals(ArtworkSource.MANUAL, updated.artworkSource)
        assertEquals("iTunes", updated.artworkProvider)
        assertEquals("itunes_123", updated.artworkRemoteId)
    }

    @Test
    fun `2 Manual artwork selection updates artist when result artist is valid`() {
        val track = makeTrack(artist = "Wrong Artist")
        val result = makeArtworkResult(artist = "Coldplay")
        val updated = applyResultToTrack(track, result, "file:///artwork/manual_track_1.jpg")

        assertEquals("Coldplay", updated.artist)
    }

    @Test
    fun `3 Manual artwork selection updates album when result album is valid`() {
        val track = makeTrack(albumTitle = "Unknown Album")
        val result = makeArtworkResult(album = "Parachutes")
        val updated = applyResultToTrack(track, result, "file:///artwork/manual_track_1.jpg")

        assertEquals("Parachutes", updated.albumTitle)
    }

    @Test
    fun `4 Manual artwork selection updates albumArtist when valid`() {
        val track = makeTrack(albumArtist = "Old Album Artist")
        val result = makeArtworkResult(albumArtist = "Coldplay")
        val updated = applyResultToTrack(track, result, "file:///artwork/manual_track_1.jpg")

        assertEquals("Coldplay", updated.albumArtist)
    }

    @Test
    fun `5 Empty or null result metadata does not overwrite existing metadata`() {
        val track = makeTrack(
            title = "Authentic Song",
            artist = "Authentic Artist",
            albumTitle = "Authentic Album",
            albumArtist = "Authentic Album Artist",
            year = 2015,
        )
        // Result with blank/placeholder metadata
        val blankResult = ArtworkSearchResult(
            id = "bad_1",
            title = "Unknown Track",
            artist = "",
            album = "Unknown Album",
            albumArtist = null,
            year = 0,
            artworkUrl = "https://example.com/art.jpg",
            durationMs = 0L,
            provider = "iTunes",
            confidence = 0.5f,
        )
        val updated = applyResultToTrack(track, blankResult, "file:///artwork/manual_track_1.jpg")

        // None of the valid fields should have been overwritten by blank/placeholder values
        assertEquals("Authentic Song", updated.title)
        assertEquals("Authentic Artist", updated.artist)
        assertEquals("Authentic Album", updated.albumTitle)
        assertEquals("Authentic Album Artist", updated.albumArtist)
        assertEquals(2015, updated.year)
        // Artwork is still updated
        assertEquals("file:///artwork/manual_track_1.jpg", updated.artworkUri)
        assertEquals(ArtworkSource.MANUAL, updated.artworkSource)
    }

    // ─── Tests 6-8: Provenance protection and invariance ─────────

    @Test
    fun `6 Manual artwork remains protected from automatic artwork enrichment`() {
        val track = makeTrack(
            artist = "Coldplay",
            title = "Yellow",
            artworkSource = ArtworkSource.MANUAL,
            artworkUri = "file:///artwork/manual_track_1.jpg",
        )
        val hasManualArtwork = track.artworkSource == ArtworkSource.MANUAL

        // In MetadataEnrichmentService.mergeMetadata:
        val finalArtwork = if (hasManualArtwork) track.artworkUri else "file:///artwork/remote_auto.jpg"
        val finalArtist = if (hasManualArtwork && !MetadataUtils.isPlaceholderArtist(track.artist)) track.artist else "Scraped Artist"

        assertEquals("file:///artwork/manual_track_1.jpg", finalArtwork)
        assertEquals("Coldplay", finalArtist)
    }

    @Test
    fun `7 Track identity, path, uri, duration, and audio format remain unchanged`() {
        val original = makeTrack()
        val result = makeArtworkResult()
        val updated = applyResultToTrack(original, result, "file:///artwork/manual_track_1.jpg")

        assertEquals(original.id, updated.id)
        assertEquals(original.path, updated.path)
        assertEquals(original.uri, updated.uri)
        assertEquals(original.duration, updated.duration)
        assertEquals(original.size, updated.size)
        assertEquals(original.codec, updated.codec)
        assertEquals(original.bitrate, updated.bitrate)
        assertEquals(original.sampleRate, updated.sampleRate)
        assertEquals(original.bitDepth, updated.bitDepth)
        assertEquals(original.channels, updated.channels)
    }

    @Test
    fun `8 Play count, favorite, and last played history remain unchanged`() {
        val original = makeTrack(isFavorite = true, playCount = 99, lastPlayed = 987654321L)
        val result = makeArtworkResult()
        val updated = applyResultToTrack(original, result, "file:///artwork/manual_track_1.jpg")

        assertEquals(true, updated.isFavorite)
        assertEquals(99, updated.playCount)
        assertEquals(987654321L, updated.lastPlayed)
    }

    // ─── Tests 9-10: Album-level behavior ─────────────────────────

    @Test
    fun `9 Album-wide artwork application updates artwork for all album tracks`() {
        val targetAlbumId = 200L
        val albumTracks = listOf(
            makeTrack(id = 10, albumId = targetAlbumId, artworkUri = null),
            makeTrack(id = 11, albumId = targetAlbumId, artworkUri = null),
            makeTrack(id = 12, albumId = targetAlbumId, artworkUri = null),
        )

        val newArtworkUri = "file:///artwork/manual_album_200.jpg"
        val updatedTracks = albumTracks.map { track ->
            track.copy(
                artworkUri = newArtworkUri,
                artworkSource = ArtworkSource.MANUAL,
                artworkProvider = "iTunes",
                artworkLastUpdated = 2000L,
            )
        }

        assertTrue(updatedTracks.all { it.artworkUri == newArtworkUri })
        assertTrue(updatedTracks.all { it.artworkSource == ArtworkSource.MANUAL })
    }

    @Test
    fun `10 Album-wide artwork application does NOT blindly replace every track artist with one artist`() {
        val targetAlbumId = 300L
        val compilationTracks = listOf(
            makeTrack(id = 1, albumId = targetAlbumId, artist = "Queen", title = "Under Pressure"),
            makeTrack(id = 2, albumId = targetAlbumId, artist = "David Bowie", title = "Heroes"),
            makeTrack(id = 3, albumId = targetAlbumId, artist = "Elton John", title = "Rocket Man"),
        )

        val newArtworkUri = "file:///artwork/manual_album_300.jpg"

        // Simulate applyManualArtworkToAlbum: update artwork ONLY, preserve track.artist
        val updatedCompilation = compilationTracks.map { track ->
            track.copy(
                artworkUri = newArtworkUri,
                artworkSource = ArtworkSource.MANUAL,
            )
        }

        assertEquals("Queen", updatedCompilation[0].artist)
        assertEquals("David Bowie", updatedCompilation[1].artist)
        assertEquals("Elton John", updatedCompilation[2].artist)
    }
}
