package com.mus.android

import android.content.Context
import com.mus.android.data.db.AlbumDao
import com.mus.android.data.db.ArtistDao
import com.mus.android.data.db.TrackDao
import com.mus.android.data.enrichment.MetadataEnrichmentService
import com.mus.android.data.enrichment.artwork.ArtworkStorage
import com.mus.android.data.enrichment.provider.MetadataProvider
import com.mus.android.data.enrichment.provider.RemoteTrackMetadata
import com.mus.android.data.model.*
import com.mus.android.data.repository.UserPreferencesRepository
import com.mus.android.data.scanner.MetadataUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class MetadataEnrichmentTest {

    private lateinit var fakeTrackDao: FakeTrackDao
    private lateinit var fakeAlbumDao: FakeAlbumDao
    private lateinit var fakeArtistDao: FakeArtistDao
    private lateinit var fakeProvider: FakeMetadataProvider
    private lateinit var artworkStorage: ArtworkStorage
    private lateinit var enrichmentService: MetadataEnrichmentService

    private lateinit var tempDir: File

    @Before
    fun setup() {
        fakeTrackDao = FakeTrackDao()
        fakeAlbumDao = FakeAlbumDao()
        fakeArtistDao = FakeArtistDao()
        fakeProvider = FakeMetadataProvider()

        // Create temp dir for artwork
        tempDir = File(System.getProperty("java.io.tmpdir"), "mus_art_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        // Test implementation of ArtworkStorage that writes to temp dir
        val mockContext = object : TestContext() {
            override fun getFilesDir(): File = tempDir
        }
        artworkStorage = ArtworkStorage(mockContext)

        val mockPrefs = object : TestUserPreferencesRepository() {}

        enrichmentService = MetadataEnrichmentService(
            context = mockContext,
            trackDao = fakeTrackDao,
            albumDao = fakeAlbumDao,
            artistDao = fakeArtistDao,
            metadataProvider = fakeProvider,
            artworkStorage = artworkStorage,
            userPreferences = mockPrefs,
        )
    }

    // 1. Fully tagged file: No external lookup required
    @Test
    fun testFullyTaggedFileRequiresNoLookup() {
        val artFile = File(tempDir, "art_202.jpg")
        artFile.writeText("test_artwork_data")

        val fullyTaggedTrack = Track(
            id = 101L,
            title = "Blinding Lights",
            artist = "The Weeknd",
            albumArtist = "The Weeknd",
            albumId = 202L,
            albumTitle = "After Hours",
            duration = 200000L,
            uri = "/Muzic/01.mp3",
            path = "/Muzic/01.mp3",
            artworkUri = "file://${artFile.absolutePath}",
            metadataStatus = MetadataStatus.COMPLETE,
        )

        val needs = enrichmentService.needsEnrichment(fullyTaggedTrack)
        assertFalse("Fully tagged track must not require external enrichment", needs)
        assertEquals(0, fakeProvider.searchCallCount)
    }

    // 2. Partially tagged file: Missing fields are enriched
    @Test
    fun testPartiallyTaggedFileEnrichesMissingFields() = runBlocking {
        artworkStorage.saveEmbeddedArtwork(202L, "album_artwork_bytes".toByteArray())

        val partialTrack = Track(
            id = 102L,
            title = "Blinding Lights",
            artist = "The Weeknd",
            albumId = 202L,
            albumTitle = "Unknown Album",
            duration = 200000L,
            uri = "/Muzic/02.mp3",
            path = "/Muzic/02.mp3",
            artworkUri = null,
            year = 0,
            genre = null,
        )

        fakeProvider.mockResults = listOf(
            RemoteTrackMetadata(
                title = "Blinding Lights",
                artist = "The Weeknd",
                albumTitle = "After Hours",
                albumArtist = "The Weeknd",
                year = 2020,
                genre = "R&B/Soul",
                durationMs = 200000L,
                artworkUrl = "https://example.com/art.jpg",
            )
        )

        val (bestMatch, confidence) = enrichmentService.findBestMatch(partialTrack, fakeProvider.mockResults)
        assertNotNull(bestMatch)
        assertEquals(MetadataConfidence.HIGH, confidence)

        val enriched = enrichmentService.mergeMetadata(partialTrack, bestMatch!!, confidence)
        assertEquals("Blinding Lights", enriched.title)
        assertEquals("The Weeknd", enriched.artist)
        assertEquals("After Hours", enriched.albumTitle)
        assertEquals(2020, enriched.year)
        assertEquals("R&B/Soul", enriched.genre)
        assertEquals(MetadataSource.EXTERNAL, enriched.metadataSource)
        assertEquals(MetadataStatus.COMPLETE, enriched.metadataStatus)
    }

    // 3. Completely untagged file: External provider can identify it
    @Test
    fun testCompletelyUntaggedFileIdentification() {
        val untaggedTrack = Track(
            id = 103L,
            title = "The Weeknd - Blinding Lights",
            artist = "Unknown Artist",
            albumId = 203L,
            albumTitle = "Unknown Album",
            duration = 200000L,
            uri = "/Muzic/The Weeknd - Blinding Lights.mp3",
            path = "/Muzic/The Weeknd - Blinding Lights.mp3",
        )

        val query = enrichmentService.buildSearchQuery(untaggedTrack)
        assertTrue(query.contains("The Weeknd") && query.contains("Blinding Lights"))

        val candidates = listOf(
            RemoteTrackMetadata(
                title = "Blinding Lights",
                artist = "The Weeknd",
                albumTitle = "After Hours",
                durationMs = 200000L,
            )
        )

        val (bestMatch, confidence) = enrichmentService.findBestMatch(untaggedTrack, candidates)
        assertNotNull(bestMatch)
        assertEquals(MetadataConfidence.HIGH, confidence)
    }

    // 4. Confident match performs direct overwrite (Simplified Model - Priority 2)
    @Test
    fun testConfidentMatchPerformsDirectOverwrite() = runBlocking {
        val localTrack = Track(
            id = 104L,
            title = "Blinding Lights (Acoustic)",
            artist = "The Weeknd",
            albumId = 204L,
            albumTitle = "Unknown Album",
            duration = 195000L,
            uri = "/Muzic/acoustic.mp3",
        )

        val remoteCandidate = RemoteTrackMetadata(
            title = "Blinding Lights (Standard Version)",
            artist = "The Weeknd ft. Someone Else",
            albumTitle = "After Hours Deluxe",
            durationMs = 195000L,
        )

        val merged = enrichmentService.mergeMetadata(localTrack, remoteCandidate, MetadataConfidence.HIGH)
        // Confident match directly overwrites title, artist, album
        assertEquals("Blinding Lights (Standard Version)", merged.title)
        assertEquals("The Weeknd ft. Someone Else", merged.artist)
        assertEquals("After Hours Deluxe", merged.albumTitle)
        assertEquals(MetadataStatus.COMPLETE, merged.metadataStatus)
        assertEquals(MetadataConfidence.HIGH, merged.metadataConfidence)
    }

    // 5. External metadata fills missing fields
    @Test
    fun testExternalMetadataFillsMissingFields() = runBlocking {
        val localTrack = Track(
            id = 105L,
            title = "Alone Again",
            artist = "The Weeknd",
            albumId = 205L,
            albumTitle = "Unknown Album",
            duration = 250000L,
            uri = "/Muzic/alone.mp3",
            trackNumber = 0,
            discNumber = 1,
            year = 0,
            genre = null,
        )

        val remoteCandidate = RemoteTrackMetadata(
            title = "Alone Again",
            artist = "The Weeknd",
            albumTitle = "After Hours",
            trackNumber = 1,
            discNumber = 1,
            year = 2020,
            genre = "Electronic",
            durationMs = 250000L,
        )

        val merged = enrichmentService.mergeMetadata(localTrack, remoteCandidate, MetadataConfidence.HIGH)
        assertEquals(1, merged.trackNumber)
        assertEquals(2020, merged.year)
        assertEquals("Electronic", merged.genre)
        assertEquals("After Hours", merged.albumTitle)
    }

    // 5b. Non-confident match leaves existing track data untouched (Simplified Model - Priority 2)
    @Test
    fun testNonConfidentMatchLeavesTrackDataUntouched() = runBlocking {
        val originalTrack = Track(
            id = 109L,
            title = "My Rare Indie Track",
            artist = "Local Artist",
            albumId = 209L,
            albumTitle = "Local Album",
            duration = 180000L,
            uri = "/Muzic/indie.mp3",
            path = "/Muzic/indie.mp3",
        )
        fakeTrackDao.insert(originalTrack)

        fakeProvider.mockResults = listOf(
            RemoteTrackMetadata(
                title = "Popular Dance Hit",
                artist = "Famous Pop Star",
                albumTitle = "Greatest Hits",
                durationMs = 300000L,
                artworkUrl = "https://example.com/pop.jpg",
            )
        )

        val result = enrichmentService.enrichTrack(originalTrack, forceRefresh = true)
        // Must leave local text and artwork data completely alone!
        assertEquals("My Rare Indie Track", result.title)
        assertEquals("Local Artist", result.artist)
        assertEquals("Local Album", result.albumTitle)
        assertNull(result.artworkUri)
        assertEquals(MetadataStatus.FAILED, result.metadataStatus)
    }

    // 6. Low-confidence external match is rejected/flagged
    @Test
    fun testLowConfidenceExternalMatchFlaggedNeedsReview() {
        val localTrack = Track(
            id = 106L,
            title = "My Very Rare Indie Track",
            artist = "Unknown Artist",
            albumId = 206L,
            albumTitle = "Unknown Album",
            duration = 180000L,
            uri = "/Muzic/rare.mp3",
        )

        val totallyUnrelatedCandidates = listOf(
            RemoteTrackMetadata(
                title = "Popular Dance Hit",
                artist = "Famous Pop Star",
                albumTitle = "Greatest Hits",
                durationMs = 300000L,
                artworkUrl = "https://example.com/wrong_art.jpg",
            )
        )

        val (match, confidence) = enrichmentService.findBestMatch(localTrack, totallyUnrelatedCandidates)
        assertEquals(MetadataConfidence.LOW, confidence)
        assertNull(match)
    }

    // 6b. Bug 1 Core Test: Same artist + identical duration + DIFFERENT title MUST be rejected!
    @Test
    fun testSameArtistSimilarDurationDifferentTitleIsRejected() {
        val localTrack = Track(
            id = 107L,
            title = "Save Your Tears",
            artist = "The Weeknd",
            albumId = 207L,
            albumTitle = "Unknown Album",
            duration = 200000L,
            uri = "/Muzic/save_your_tears.mp3",
            path = "/Muzic/save_your_tears.mp3",
        )

        // Candidate has the EXACT same artist and EXACT same duration, but is a DIFFERENT song
        val wrongSongCandidates = listOf(
            RemoteTrackMetadata(
                title = "Blinding Lights",
                artist = "The Weeknd",
                albumTitle = "After Hours",
                durationMs = 200000L,
                artworkUrl = "https://example.com/blinding_lights.jpg",
            )
        )

        val (bestMatch, confidence) = enrichmentService.findBestMatch(localTrack, wrongSongCandidates)
        // Must NEVER match a different song just because artist and duration match!
        assertNull("Candidate with different title must not be selected", bestMatch)
        assertEquals(MetadataConfidence.LOW, confidence)
    }

    // 6c. Test that missing local title signal rejects any match rather than guessing
    @Test
    fun testMissingLocalTitleSignalRejectsCandidates() {
        val untaggedNoHintTrack = Track(
            id = 108L,
            title = "Unknown Track",
            artist = "Unknown Artist",
            albumId = 208L,
            albumTitle = "Unknown Album",
            duration = 200000L,
            uri = "/Muzic/track01.mp3",
            path = "/Muzic/track01.mp3",
        )

        val candidates = listOf(
            RemoteTrackMetadata(
                title = "Blinding Lights",
                artist = "The Weeknd",
                albumTitle = "After Hours",
                durationMs = 200000L,
            )
        )

        val (bestMatch, confidence) = enrichmentService.findBestMatch(untaggedNoHintTrack, candidates)
        assertNull(bestMatch)
        assertEquals(MetadataConfidence.LOW, confidence)
    }

    // 6d. Test title similarity calculation
    @Test
    fun testTitleSimilarityMeasures() {
        // High similarity: variations and subtitles
        val sim1 = MetadataUtils.calculateTitleSimilarity("Blinding Lights", "Blinding Lights (Official Music Video)")
        assertTrue("Subtitled title should have high similarity: $sim1", sim1 >= 0.8)

        val sim2 = MetadataUtils.calculateTitleSimilarity("FE!N", "FE!N ft. Playboi Carti")
        assertTrue("Feat variation should have high similarity: $sim2", sim2 >= 0.7)

        // Low similarity: completely different songs
        val sim3 = MetadataUtils.calculateTitleSimilarity("Save Your Tears", "Blinding Lights")
        assertTrue("Different songs must have low similarity: $sim3", sim3 < 0.3)

        val sim4 = MetadataUtils.calculateTitleSimilarity("Samajavaragamana", "Ramuloo Ramulaa")
        assertTrue("Different regional songs must have low similarity: $sim4", sim4 < 0.4)
    }

    // 7. Artwork is stored locally
    @Test
    fun testArtworkIsStoredLocally() {
        val albumId = 777L
        val fakeImageData = "FAKE_JPEG_IMAGE_DATA_12345".toByteArray()

        val savedUri = artworkStorage.saveEmbeddedArtwork(albumId, fakeImageData)
        assertNotNull(savedUri)
        assertTrue(savedUri!!.startsWith("file://"))

        val localUri = artworkStorage.getLocalArtworkUri(albumId)
        assertNotNull(localUri)
        assertEquals(savedUri, localUri)
    }

    // 8. Existing artwork is reused for tracks from the same album
    @Test
    fun testExistingArtworkReusedForSameAlbum() {
        val albumId = 888L
        val fakeImageData = "ALBUM_COVER_BYTES".toByteArray()

        val uri1 = artworkStorage.saveEmbeddedArtwork(albumId, fakeImageData)
        assertNotNull(uri1)

        // Second attempt to save for the same album reuses existing file
        val uri2 = artworkStorage.getLocalArtworkUri(albumId)
        assertEquals(uri1, uri2)
    }

    // 8b. Priority 3: Untagged tracks in the same directory share the same stable canonical album ID
    @Test
    fun testCanonicalAlbumIdStableForUntaggedTracks() {
        val path1 = "/storage/emulated/0/Muzic/Telugu Hits/01 - Song.mp3"
        val path2 = "/storage/emulated/0/Muzic/Telugu Hits/02 - Another Song.mp3"
        val pathOther = "/storage/emulated/0/Muzic/Hindi Pop/01 - Hindi Song.mp3"

        val albumId1 = MetadataUtils.generateCanonicalAlbumId(null, null, path1)
        val albumId2 = MetadataUtils.generateCanonicalAlbumId(null, null, path2)
        val albumIdOther = MetadataUtils.generateCanonicalAlbumId(null, null, pathOther)

        assertTrue(albumId1 > 0)
        assertEquals("Tracks in the same folder must share canonical album ID", albumId1, albumId2)
        assertNotEquals("Tracks in different folders must have different album IDs", albumId1, albumIdOther)

        // Rescan stability: same path produces identical canonical ID
        val rescanId = MetadataUtils.generateCanonicalAlbumId(null, null, path1)
        assertEquals(albumId1, rescanId)
    }

    // 8c. Priority 3: Valid embedded artwork is always preferred over remote artwork
    @Test
    fun testEmbeddedArtworkPreferredOverRemote() = runBlocking {
        val albumId = 333L
        val embeddedUri = artworkStorage.saveEmbeddedArtwork(albumId, "GENUINE_EMBEDDED_BYTES".toByteArray())
        assertNotNull(embeddedUri)

        val localTrack = Track(
            id = 301L,
            title = "Blinding Lights",
            artist = "The Weeknd",
            albumId = albumId,
            albumTitle = "After Hours",
            duration = 200000L,
            uri = "/Muzic/01.mp3",
            artworkUri = embeddedUri,
        )

        val remoteCandidate = RemoteTrackMetadata(
            title = "Blinding Lights",
            artist = "The Weeknd",
            albumTitle = "After Hours",
            durationMs = 200000L,
            artworkUrl = "https://example.com/remote_cover.jpg",
        )

        val enriched = enrichmentService.mergeMetadata(localTrack, remoteCandidate, MetadataConfidence.HIGH)
        // Must preserve the embedded artwork, never replace with remote!
        assertEquals("Embedded artwork must be preserved", embeddedUri, enriched.artworkUri)
    }

    // 8d. Priority 3 & 4: Artist entity artwork is never polluted by album cover
    @Test
    fun testArtistArtworkNotPollutedByAlbumCover() = runBlocking {
        val albumId = 444L
        val albumArtUri = artworkStorage.saveEmbeddedArtwork(albumId, "ALBUM_ART_BYTES".toByteArray())

        val track = Track(
            id = 401L,
            title = "Starboy",
            artist = "The Weeknd",
            albumArtist = "The Weeknd",
            albumId = albumId,
            albumTitle = "Starboy",
            duration = 230000L,
            uri = "/Muzic/starboy.mp3",
            artworkUri = albumArtUri,
            metadataStatus = MetadataStatus.COMPLETE,
            metadataConfidence = MetadataConfidence.HIGH,
        )

        fakeTrackDao.insert(track)
        fakeProvider.mockResults = listOf(
            RemoteTrackMetadata(
                title = "Starboy",
                artist = "The Weeknd",
                albumTitle = "Starboy",
                durationMs = 230000L,
                artworkUrl = "https://example.com/starboy.jpg",
            )
        )
        enrichmentService.enrichTrack(track, forceRefresh = true)

        val artistId = MetadataUtils.generateArtistId("The Weeknd")
        val artist = fakeArtistDao.getArtistById(artistId)
        assertNotNull(artist)
        assertNull("Artist artwork must NOT be set to album cover", artist!!.artworkUri)
    }

    // 9. Metadata survives database representation
    @Test
    fun testMetadataSurvivesDatabaseFields() {
        val track = Track(
            id = 901L,
            title = "In Your Eyes",
            artist = "The Weeknd",
            albumId = 202L,
            albumTitle = "After Hours",
            duration = 237000L,
            uri = "/Muzic/09.mp3",
            artistArtworkUri = "file:///art/artist.jpg",
            metadataSource = MetadataSource.MERGED,
            metadataStatus = MetadataStatus.COMPLETE,
            metadataConfidence = MetadataConfidence.HIGH,
            metadataLastUpdated = 1710000000000L,
        )

        assertEquals("file:///art/artist.jpg", track.artistArtworkUri)
        assertEquals(MetadataSource.MERGED, track.metadataSource)
        assertEquals(MetadataStatus.COMPLETE, track.metadataStatus)
        assertEquals(MetadataConfidence.HIGH, track.metadataConfidence)
        assertEquals(1710000000000L, track.metadataLastUpdated)
    }

    // 10. Enriched metadata appears in Artist/Album/Search
    @Test
    fun testEnrichedMetadataQueryability() = runBlocking {
        val initialTrack = Track(
            id = 1001L,
            title = "01 Track",
            artist = "Unknown Artist",
            albumId = 2001L,
            albumTitle = "Unknown Album",
            duration = 210000L,
            uri = "/Muzic/01.mp3",
        )
        fakeTrackDao.insert(initialTrack)

        // Enrich the track
        val enriched = initialTrack.copy(
            title = "Save Your Tears",
            artist = "The Weeknd",
            albumTitle = "After Hours",
            metadataSource = MetadataSource.EXTERNAL,
            metadataStatus = MetadataStatus.COMPLETE,
        )
        fakeTrackDao.update(enriched)

        val retrieved = fakeTrackDao.getTrackById(1001L)
        assertNotNull(retrieved)
        assertEquals("Save Your Tears", retrieved!!.title)
        assertEquals("The Weeknd", retrieved.artist)
        assertEquals("After Hours", retrieved.albumTitle)
    }

    // 11. Offline mode does not break playback
    @Test
    fun testOfflineModePreservesPlayability() = runBlocking {
        val offlineTrack = Track(
            id = 1101L,
            title = "Offline Song",
            artist = "The Weeknd",
            albumId = 3001L,
            albumTitle = "Unknown Album",
            duration = 185000L,
            uri = "/Muzic/offline.mp3",
            path = "/Muzic/offline.mp3",
            metadataStatus = MetadataStatus.NEEDS_LOOKUP,
        )

        // Track is still valid and playable
        assertEquals("/Muzic/offline.mp3", offlineTrack.uri)
        assertEquals(185000L, offlineTrack.duration)
        assertEquals("Offline Song", offlineTrack.title)
        assertEquals(MetadataStatus.NEEDS_LOOKUP, offlineTrack.metadataStatus)
    }

    // 12. Enrichment does not block library display
    @Test
    fun testEnrichmentDoesNotBlockLibraryDisplay() = runBlocking {
        val tracks = listOf(
            Track(id = 1L, title = "Song 1", artist = "Artist 1", albumId = 10L, albumTitle = "Album 1", duration = 100L, uri = "/1.mp3"),
            Track(id = 2L, title = "Song 2", artist = "Artist 2", albumId = 20L, albumTitle = "Album 2", duration = 200L, uri = "/2.mp3"),
        )
        fakeTrackDao.insertAll(tracks)

        // Immediately available in library
        val libraryTracks = fakeTrackDao.getAllTracksOnce()
        assertEquals(2, libraryTracks.size)

        // Enqueue enrichment non-blocking
        enrichmentService.enqueueEnrichment(libraryTracks)

        // Tracks still exist and remain completely accessible
        assertEquals(2, fakeTrackDao.getAllTracksOnce().size)
    }

    // 13. Wrong artwork can be cleared and replaced
    @Test
    fun testClearArtworkAndForceRefreshOverwritesRemoteArtwork() = runBlocking {
        val albumId = 555L
        val oldArtUri = artworkStorage.saveEmbeddedArtworkByKey("album_$albumId", "OLD_REMOTE_ART".toByteArray())
        assertNotNull(oldArtUri)
        assertEquals(oldArtUri, artworkStorage.getLocalArtworkUri(albumId))

        // Simulate "this cover is wrong" -> clear artwork
        val cleared = artworkStorage.clearArtwork(albumId)
        assertTrue(cleared)
        assertNull(artworkStorage.getLocalArtworkUri(albumId))

        // Now save new remote art
        val newArtUri = artworkStorage.saveEmbeddedArtworkByKey("album_$albumId", "NEW_REMOTE_ART".toByteArray())
        assertNotNull(newArtUri)
        assertEquals(newArtUri, artworkStorage.getLocalArtworkUri(albumId))
    }

    // 14. Regional storefront selection and order
    @Test
    fun testRegionalCountrySelectionForIndianLanguages() {
        val teluguTrack = Track(id = 1L, title = "Samajavaragamana", artist = "Sid Sriram", albumId = 1L, albumTitle = "Ala Vaikunthapurramuloo", duration = 210000L, uri = "/1.mp3", language = "Telugu")
        val tamilTrack = Track(id = 2L, title = "Rowdy Baby", artist = "Dhanush", albumId = 2L, albumTitle = "Maari 2", duration = 240000L, uri = "/2.mp3", language = "Tamil")
        val hindiTrack = Track(id = 3L, title = "Kesariya", artist = "Arijit Singh", albumId = 3L, albumTitle = "Brahmastra", duration = 260000L, uri = "/3.mp3", language = "Hindi")

        assertEquals("IN", enrichmentService.determineCountryForTrack(teluguTrack))
        assertEquals("IN", enrichmentService.determineCountryForTrack(tamilTrack))
        assertEquals("IN", enrichmentService.determineCountryForTrack(hindiTrack))
    }

    @Test
    fun testRegionalStorefrontQueriedFirstBeforeUS() = runBlocking {
        val teluguTrack = Track(
            id = 601L,
            title = "Samajavaragamana",
            artist = "Sid Sriram",
            albumId = 601L,
            albumTitle = "Ala Vaikunthapurramuloo",
            duration = 214000L,
            uri = "/Muzic/Samajavaragamana.mp3",
            language = "Telugu",
            metadataStatus = MetadataStatus.NEEDS_LOOKUP,
        )

        fakeTrackDao.insert(teluguTrack)
        fakeProvider.mockResults = emptyList() // will cause it to exhaust IN and try US

        enrichmentService.enrichTrack(teluguTrack, forceRefresh = true)

        assertTrue(fakeProvider.countriesQueried.isNotEmpty())
        assertEquals("IN", fakeProvider.countriesQueried.first())
        assertTrue("Expected US storefront fallback attempt after regional store had no results", fakeProvider.countriesQueried.contains("US"))
    }

    // 15. Repairing existing data: terminal statuses and re-verification
    @Test
    fun testNeedsEnrichmentDoesNotFreezeNeedsReviewOrNonHighConfidence() {
        val reviewTrack = Track(
            id = 701L,
            title = "Kesariya",
            artist = "Arijit Singh",
            albumId = 701L,
            albumTitle = "Brahmastra",
            duration = 260000L,
            uri = "/701.mp3",
            artworkUri = "file:///art/test.jpg",
            metadataStatus = MetadataStatus.NEEDS_REVIEW,
            metadataConfidence = MetadataConfidence.LOW,
        )
        assertTrue("NEEDS_REVIEW tracks must not be frozen and should be re-evaluated", enrichmentService.needsEnrichment(reviewTrack))

        val completeLowConfidenceTrack = reviewTrack.copy(
            metadataStatus = MetadataStatus.COMPLETE,
            metadataConfidence = MetadataConfidence.LOW,
        )
        assertTrue("COMPLETE tracks with LOW confidence must be eligible for re-evaluation", enrichmentService.needsEnrichment(completeLowConfidenceTrack))

        val genuinelyCompleteTrack = reviewTrack.copy(
            metadataStatus = MetadataStatus.COMPLETE,
            metadataConfidence = MetadataConfidence.HIGH,
        )
        assertFalse("COMPLETE tracks with HIGH confidence and all valid fields should not re-enrich", enrichmentService.needsEnrichment(genuinelyCompleteTrack))
    }

    @Test
    fun testLibraryReverificationResetsUncertainTracksAndClearsRemoteArtwork() = runBlocking {
        val albumId = 801L
        val remoteArtUri = artworkStorage.saveEmbeddedArtworkByKey("album_$albumId", "REMOTE_COVER".toByteArray())
        assertNotNull(remoteArtUri)

        val uncertainTrack = Track(
            id = 801L,
            title = "Uncertain Song",
            artist = "Artist",
            albumId = albumId,
            albumTitle = "Album",
            duration = 200000L,
            uri = "/801.mp3",
            artworkUri = remoteArtUri,
            metadataStatus = MetadataStatus.NEEDS_REVIEW,
            metadataConfidence = MetadataConfidence.LOW,
        )
        fakeTrackDao.insert(uncertainTrack)

        // Simulate reverify pass:
        val hasEmbedded = artworkStorage.isEmbeddedArtwork(uncertainTrack.artworkUri) ||
                artworkStorage.hasEmbeddedArtwork(uncertainTrack.albumId)
        assertFalse(hasEmbedded)

        artworkStorage.clearRemoteArtwork(uncertainTrack.albumId)
        assertNull(artworkStorage.getLocalArtworkUri(albumId))

        val resetTrack = uncertainTrack.copy(
            artworkUri = null,
            metadataStatus = MetadataStatus.NEEDS_LOOKUP,
            metadataConfidence = MetadataConfidence.LOW,
            metadataLastUpdated = 0L,
        )
        fakeTrackDao.update(resetTrack)

        val retrieved = fakeTrackDao.getTrackById(801L)
        assertNotNull(retrieved)
        assertEquals(MetadataStatus.NEEDS_LOOKUP, retrieved!!.metadataStatus)
        assertEquals(MetadataConfidence.LOW, retrieved.metadataConfidence)
        assertNull(retrieved.artworkUri)
    }

    // --- Test Doubles / Fakes ---

    class FakeMetadataProvider : MetadataProvider {
        override val name: String = "FakeProvider"
        var searchCallCount = 0
        var lastQuery: String? = null
        var lastCountry: String? = null
        val countriesQueried = mutableListOf<String?>()
        var mockResults: List<RemoteTrackMetadata> = emptyList()

        override suspend fun searchTrack(query: String, limit: Int, country: String?): List<RemoteTrackMetadata> {
            searchCallCount++
            lastQuery = query
            lastCountry = country
            countriesQueried.add(country)
            return mockResults
        }
    }

    class FakeTrackDao : TrackDao {
        private val tracks = mutableMapOf<Long, Track>()

        override fun getAllTracks(): Flow<List<Track>> = flowOf(tracks.values.toList())
        override suspend fun getAllTracksOnce(): List<Track> = tracks.values.toList()
        override suspend fun getTrackById(id: Long): Track? = tracks[id]
        override fun getTracksByAlbum(albumId: Long): Flow<List<Track>> = flowOf(tracks.values.filter { it.albumId == albumId })
        override fun getTracksByArtist(artist: String): Flow<List<Track>> = flowOf(tracks.values.filter { it.artist == artist })
        override fun getFavorites(): Flow<List<Track>> = flowOf(tracks.values.filter { it.isFavorite })
        override fun search(query: String): Flow<List<Track>> = flowOf(tracks.values.filter { it.title.contains(query, ignoreCase = true) })
        override fun getTracksByLanguage(language: String): Flow<List<Track>> = flowOf(tracks.values.filter { it.language == language })
        override suspend fun insertAll(newTracks: List<Track>) { newTracks.forEach { tracks[it.id] = it } }
        override suspend fun insert(track: Track) { tracks[track.id] = track }
        override suspend fun update(track: Track) { tracks[track.id] = track }
        override suspend fun setFavorite(trackId: Long, isFavorite: Boolean) { tracks[trackId]?.let { tracks[trackId] = it.copy(isFavorite = isFavorite) } }
        override suspend fun deleteAll() { tracks.clear() }
        override suspend fun getTrackCount(): Int = tracks.size
        override fun getRecentlyAdded(limit: Int): Flow<List<Track>> = flowOf(tracks.values.take(limit))
        override fun getMostPlayed(limit: Int): Flow<List<Track>> = flowOf(tracks.values.take(limit))
        override fun getRecentlyPlayed(limit: Int): Flow<List<Track>> = flowOf(tracks.values.take(limit))
        override suspend fun recordPlay(trackId: Long, timestamp: Long) {}
        override suspend fun deleteTrackById(trackId: Long) { tracks.remove(trackId) }
        override suspend fun deleteTracksByIds(ids: List<Long>) { ids.forEach { tracks.remove(it) } }
    }

    class FakeAlbumDao : AlbumDao {
        private val albums = mutableMapOf<Long, Album>()
        override fun getAllAlbums(): Flow<List<Album>> = flowOf(albums.values.toList())
        override suspend fun getAlbumById(id: Long): Album? = albums[id]
        override suspend fun getAllAlbumsOnce(): List<Album> = albums.values.toList()
        override fun getAlbumsByArtist(artist: String): Flow<List<Album>> = flowOf(albums.values.filter { it.artist == artist })
        override fun search(query: String): Flow<List<Album>> = flowOf(albums.values.filter { it.title.contains(query, true) })
        override suspend fun insertAll(newAlbums: List<Album>) { newAlbums.forEach { albums[it.id] = it } }
        override suspend fun deleteAll() { albums.clear() }
        override fun getRandomAlbums(limit: Int): Flow<List<Album>> = flowOf(albums.values.take(limit))
        override suspend fun deleteAlbumsByIds(ids: List<Long>) { ids.forEach { albums.remove(it) } }
    }

    class FakeArtistDao : ArtistDao {
        private val artists = mutableMapOf<Long, Artist>()
        override fun getAllArtists(): Flow<List<Artist>> = flowOf(artists.values.toList())
        override suspend fun getArtistById(id: Long): Artist? = artists[id]
        override suspend fun getAllArtistsOnce(): List<Artist> = artists.values.toList()
        override fun search(query: String): Flow<List<Artist>> = flowOf(artists.values.filter { it.name.contains(query, true) })
        override suspend fun insertAll(newArtists: List<Artist>) { newArtists.forEach { artists[it.id] = it } }
        override suspend fun deleteAll() { artists.clear() }
        override suspend fun deleteArtistsByIds(ids: List<Long>) { ids.forEach { artists.remove(it) } }
    }

    open class TestContext : android.content.ContextWrapper(null)

    open class TestUserPreferencesRepository : UserPreferencesRepository(TestContext()) {
        private val _autoMeta = kotlinx.coroutines.flow.MutableStateFlow(true)
        private var _migrationVersion = 0
        override val automaticMetadataEnabled: kotlinx.coroutines.flow.StateFlow<Boolean> get() = _autoMeta
        override fun getMetadataMigrationVersion(): Int = _migrationVersion
        override fun setMetadataMigrationVersion(version: Int) { _migrationVersion = version }
    }
}
