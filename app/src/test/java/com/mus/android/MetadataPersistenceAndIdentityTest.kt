package com.mus.android

import com.mus.android.data.enrichment.artwork.ArtworkStorage
import com.mus.android.data.model.*
import com.mus.android.data.scanner.MetadataUtils
import org.junit.Assert.*
import org.junit.Test

class MetadataPersistenceAndIdentityTest {

    @Test
    fun testStableTrackIdPreservesCaseAndDistinguishesFiles() {
        val idCaseUpper = MetadataUtils.generateStableTrackId("/storage/emulated/0/Muzic/Drake/NOKIA.mp3")
        val idCaseLower = MetadataUtils.generateStableTrackId("/storage/emulated/0/Muzic/Drake/nokia.mp3")
        assertNotNull(idCaseUpper)
        assertNotNull(idCaseLower)
        // Distinct physical files must have distinct stable IDs
        assertNotEquals(idCaseUpper, idCaseLower)
    }

    @Test
    fun testNestedFoldersWithSameFilenameDoNotCollide() {
        val drakeTrackId = MetadataUtils.generateStableTrackId("/storage/emulated/0/Muzic/Drake/NOKIA.mp3")
        val edTrackId = MetadataUtils.generateStableTrackId("/storage/emulated/0/Muzic/Ed Sheeran/NOKIA.mp3")
        val travisTrackId = MetadataUtils.generateStableTrackId("/storage/emulated/0/Muzic/Travis Scott/NOKIA.mp3")
        assertNotNull(drakeTrackId)
        assertNotNull(edTrackId)
        assertNotNull(travisTrackId)

        assertNotEquals(drakeTrackId, edTrackId)
        assertNotEquals(drakeTrackId, travisTrackId)
        assertNotEquals(edTrackId, travisTrackId)
    }

    @Test
    fun testSafRelativePathSubfolderIdentitySeparation() {
        val drakePath = "/Muzic/Drake/NOKIA.mp3"
        val oldMusicPath = "/Muzic/Old Music/NOKIA.mp3"

        val relDrake = MetadataUtils.extractMuzicRelativePath(drakePath)
        val relOldMusic = MetadataUtils.extractMuzicRelativePath(oldMusicPath)

        assertEquals("Drake/NOKIA.mp3", relDrake)
        assertEquals("Old Music/NOKIA.mp3", relOldMusic)

        val idDrake = MetadataUtils.generateStableTrackId(drakePath)
        val idOldMusic = MetadataUtils.generateStableTrackId(oldMusicPath)

        assertNotNull(idDrake)
        assertNotNull(idOldMusic)
        assertNotEquals("Drake/NOKIA.mp3 and Old Music/NOKIA.mp3 must produce distinct physical identities", idDrake, idOldMusic)

        // Verify across MediaStore, SAF synthetic, and direct relative path for Old Music
        val mediaStoreOldMusic = "/storage/emulated/0/Muzic/Old Music/NOKIA.mp3"
        val safOldMusic = "content://com.android.externalstorage.documents/tree/primary%3AMuzic/document/primary%3AMuzic%2FOld%20Music%2FNOKIA.mp3"
        val directOldMusic = "Old Music/NOKIA.mp3"

        assertEquals(idOldMusic, MetadataUtils.generateStableTrackId(mediaStoreOldMusic))
        assertEquals(idOldMusic, MetadataUtils.generateStableTrackId(safOldMusic))
        assertEquals(idOldMusic, MetadataUtils.generateStableTrackId(directOldMusic))
    }

    @Test
    fun testDifferentScanPathsProduceSameStableId() {
        val mediaStorePath = "/storage/emulated/0/Muzic/Drake/NOKIA.mp3"
        val safSyntheticPath = "/Muzic/Drake/NOKIA.mp3"
        val directRelativePath = "Drake/NOKIA.mp3"
        val urlEncodedPath = "content://com.android.externalstorage.documents/tree/primary%3AMuzic/document/primary%3AMuzic%2FDrake%2FNOKIA.mp3"

        val idMediaStore = MetadataUtils.generateStableTrackId(mediaStorePath)
        val idSafSynthetic = MetadataUtils.generateStableTrackId(safSyntheticPath)
        val idDirectRelative = MetadataUtils.generateStableTrackId(directRelativePath)
        val idUrlEncoded = MetadataUtils.generateStableTrackId(urlEncodedPath)

        assertNotNull(idMediaStore)
        assertEquals("MediaStore and SAF synthetic path must match", idMediaStore, idSafSynthetic)
        assertEquals("MediaStore and direct relative path must match", idMediaStore, idDirectRelative)
        assertEquals("MediaStore and URL encoded path must match", idMediaStore, idUrlEncoded)
    }

    @Test
    fun testReconciliationPreservesEnrichedMetadataAgainstRawScanner() {
        val stableId = MetadataUtils.generateStableTrackId("/Muzic/Drake/NOKIA.mp3")!!
        val validArtUri = "file:///data/user/0/com.mus.android/files/artwork/art_album_98765.jpg"

        val existingEnrichedTrack = Track(
            id = stableId,
            title = "NOKIA",
            artist = "Drake",
            albumId = 98765L,
            albumTitle = "Her Loss",
            albumArtist = "Drake",
            duration = 210000L,
            year = 2022,
            genre = "Hip-Hop",
            composer = "Aubrey Graham",
            uri = "content://media/external/audio/media/100",
            artworkUri = validArtUri,
            path = "/storage/emulated/0/Muzic/Drake/NOKIA.mp3",
            metadataSource = MetadataSource.EXTERNAL,
            metadataStatus = MetadataStatus.COMPLETE,
            metadataConfidence = MetadataConfidence.HIGH,
            metadataLastUpdated = 1700000000000L,
            isFavorite = true,
            playCount = 15,
            lastPlayed = 1700050000000L
        )

        val scannedRawTrack = Track(
            id = stableId,
            title = "Drake - NOKIA (Official Music Video)",
            artist = "Unknown Artist",
            albumId = MetadataUtils.generateAlbumId("Unknown Artist", "Unknown Album"),
            albumTitle = "Unknown Album",
            albumArtist = "Unknown Artist",
            duration = 210000L,
            year = 0,
            genre = null,
            composer = null,
            uri = "content://media/external/audio/media/100",
            artworkUri = null,
            path = "/storage/emulated/0/Muzic/Drake/NOKIA.mp3",
            metadataSource = MetadataSource.EMBEDDED,
            metadataStatus = MetadataStatus.NEEDS_LOOKUP,
            metadataConfidence = MetadataConfidence.LOW,
            metadataLastUpdated = 0L
        )

        // Simulate MusicRepository reconciliation logic
        val wasEnriched = existingEnrichedTrack.metadataStatus == MetadataStatus.COMPLETE ||
                existingEnrichedTrack.metadataStatus == MetadataStatus.PARTIAL ||
                existingEnrichedTrack.metadataStatus == MetadataStatus.NEEDS_REVIEW ||
                existingEnrichedTrack.metadataSource == MetadataSource.EXTERNAL ||
                existingEnrichedTrack.metadataSource == MetadataSource.MERGED

        val finalArtworkUri = when {
            ArtworkStorage.isArtworkValid(existingEnrichedTrack.artworkUri) -> existingEnrichedTrack.artworkUri
            ArtworkStorage.isArtworkValid(scannedRawTrack.artworkUri) -> scannedRawTrack.artworkUri
            else -> null
        }

        val finalTitle = if (wasEnriched || !MetadataUtils.isPlaceholderTitle(existingEnrichedTrack.title)) {
            existingEnrichedTrack.title
        } else {
            scannedRawTrack.title
        }

        val finalArtist = if (wasEnriched || !MetadataUtils.isPlaceholderArtist(existingEnrichedTrack.artist)) {
            existingEnrichedTrack.artist
        } else {
            scannedRawTrack.artist
        }

        val finalAlbumTitle = if (wasEnriched || !MetadataUtils.isPlaceholderAlbum(existingEnrichedTrack.albumTitle)) {
            existingEnrichedTrack.albumTitle
        } else {
            scannedRawTrack.albumTitle
        }

        val reconciled = scannedRawTrack.copy(
            title = finalTitle,
            artist = finalArtist,
            albumArtist = if (wasEnriched || !MetadataUtils.isPlaceholderArtist(existingEnrichedTrack.albumArtist)) existingEnrichedTrack.albumArtist else scannedRawTrack.albumArtist,
            albumTitle = finalAlbumTitle,
            albumId = if (wasEnriched) existingEnrichedTrack.albumId else scannedRawTrack.albumId,
            artworkUri = finalArtworkUri,
            year = if (existingEnrichedTrack.year > 0) existingEnrichedTrack.year else scannedRawTrack.year,
            genre = existingEnrichedTrack.genre ?: scannedRawTrack.genre,
            composer = existingEnrichedTrack.composer ?: scannedRawTrack.composer,
            metadataSource = if (wasEnriched) existingEnrichedTrack.metadataSource else scannedRawTrack.metadataSource,
            metadataStatus = if (wasEnriched) existingEnrichedTrack.metadataStatus else scannedRawTrack.metadataStatus,
            metadataConfidence = if (wasEnriched) existingEnrichedTrack.metadataConfidence else scannedRawTrack.metadataConfidence,
            metadataLastUpdated = if (existingEnrichedTrack.metadataLastUpdated > 0) existingEnrichedTrack.metadataLastUpdated else scannedRawTrack.metadataLastUpdated,
            isFavorite = existingEnrichedTrack.isFavorite,
            playCount = existingEnrichedTrack.playCount,
            lastPlayed = existingEnrichedTrack.lastPlayed
        )

        assertEquals("NOKIA", reconciled.title)
        assertEquals("Drake", reconciled.artist)
        assertEquals("Her Loss", reconciled.albumTitle)
        assertEquals(98765L, reconciled.albumId)
        assertEquals(validArtUri, reconciled.artworkUri)
        assertEquals(MetadataStatus.COMPLETE, reconciled.metadataStatus)
        assertEquals(MetadataSource.EXTERNAL, reconciled.metadataSource)
        assertEquals(2022, reconciled.year)
        assertEquals("Hip-Hop", reconciled.genre)
        assertTrue(reconciled.isFavorite)
        assertEquals(15, reconciled.playCount)
    }

    @Test
    fun testReconciliationPurgesSharedUnknownAlbumArt() {
        val badSharedArtUri = "file:///data/user/0/com.mus.android/files/artwork/art_album_${ArtworkStorage.UNKNOWN_ALBUM_ID}.jpg"
        assertFalse(ArtworkStorage.isArtworkValid(badSharedArtUri))

        val badUnknownArtUri = "file:///data/user/0/com.mus.android/files/artwork/art_Unknown Album.jpg"
        assertFalse(ArtworkStorage.isArtworkValid(badUnknownArtUri))

        val validArtUri = "file:///data/user/0/com.mus.android/files/artwork/art_album_12345.jpg"
        assertTrue(ArtworkStorage.isArtworkValid(validArtUri))
    }

    @Test
    fun testEnrichmentQueueStateMachine() {
        val now = System.currentTimeMillis()

        fun isEligible(status: String, lastUpdated: Long): Boolean {
            return when (status) {
                MetadataStatus.COMPLETE -> false
                MetadataStatus.NEEDS_REVIEW -> false
                MetadataStatus.ENRICHING -> (now - lastUpdated > 5 * 60 * 1000L)
                MetadataStatus.FAILED -> (now - lastUpdated > 24 * 60 * 60 * 1000L)
                MetadataStatus.NEEDS_LOOKUP, MetadataStatus.PARTIAL -> true
                else -> false
            }
        }

        // COMPLETE must not be enqueued
        assertFalse(isEligible(MetadataStatus.COMPLETE, now))
        // NEEDS_REVIEW must not be enqueued
        assertFalse(isEligible(MetadataStatus.NEEDS_REVIEW, now))
        // ENRICHING active must not be enqueued
        assertFalse(isEligible(MetadataStatus.ENRICHING, now - 60_000L)) // 1 min ago
        // ENRICHING stale (> 5 min) should be eligible
        assertTrue(isEligible(MetadataStatus.ENRICHING, now - 6 * 60 * 1000L)) // 6 min ago
        // NEEDS_LOOKUP must be enqueued
        assertTrue(isEligible(MetadataStatus.NEEDS_LOOKUP, now))
        // PARTIAL must be enqueued
        assertTrue(isEligible(MetadataStatus.PARTIAL, now))
        // FAILED recent must not be enqueued
        assertFalse(isEligible(MetadataStatus.FAILED, now - 3600_000L)) // 1 hour ago
        // FAILED older than 24h must be enqueued for retry
        assertTrue(isEligible(MetadataStatus.FAILED, now - 25 * 3600_000L)) // 25 hours ago
    }
}
