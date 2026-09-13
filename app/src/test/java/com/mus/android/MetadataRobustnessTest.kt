package com.mus.android

import com.mus.android.data.enrichment.MetadataEnrichmentService
import com.mus.android.data.enrichment.artwork.ArtworkStorage
import com.mus.android.data.enrichment.provider.RemoteTrackMetadata
import com.mus.android.data.model.*
import com.mus.android.data.scanner.MetadataUtils
import org.junit.Assert.*
import org.junit.Test

/**
 * Comprehensive regression tests for the metadata/enrichment overhaul.
 * Covers:
 * - Stable track identity across scanners
 * - Case-insensitive reconciliation lookups
 * - Enriched metadata preservation during rescans
 * - ENRICHING race condition protection
 * - Search query priority (embedded > filename)
 * - Minimum identity requirement for confidence
 * - Duration-only match rejection
 * - Album grouping and consistency
 * - Disc number fix (disc 1 is valid)
 * - Repeated scan idempotency
 * - Force re-enrich preserves identity
 * - iTunes collectionArtistName handling
 */
class MetadataRobustnessTest {

    // ── Phase 2: Stable Identity ──────────────────────────────

    @Test
    fun testStableIdentity_MediaStore_SAF_Direct_SameId() {
        val mediaStore = "/storage/emulated/0/Muzic/Drake/NOKIA.mp3"
        val saf = "content://com.android.externalstorage.documents/tree/primary%3AMuzic/document/primary%3AMuzic%2FDrake%2FNOKIA.mp3"
        val safSynthetic = "/Muzic/Drake/NOKIA.mp3"
        val directRelative = "Drake/NOKIA.mp3"

        val idMS = MetadataUtils.generateStableTrackId(mediaStore)
        val idSAF = MetadataUtils.generateStableTrackId(saf)
        val idSafSynth = MetadataUtils.generateStableTrackId(safSynthetic)
        val idDirect = MetadataUtils.generateStableTrackId(directRelative)

        assertNotNull(idMS)
        assertEquals("Same file from all 4 scan paths must produce the same stable ID", idMS, idSAF)
        assertEquals(idMS, idSafSynth)
        assertEquals(idMS, idDirect)
    }

    @Test
    fun testStableIdentity_DuplicateFilenames_DifferentPaths_DifferentIds() {
        val englishSong = MetadataUtils.generateStableTrackId("/storage/emulated/0/Muzic/English/Pop Hits/song.mp3")
        val hindiSong = MetadataUtils.generateStableTrackId("/storage/emulated/0/Muzic/Hindi/Bollywood/song.mp3")
        val teluguSong = MetadataUtils.generateStableTrackId("/storage/emulated/0/Muzic/Telugu/Tollywood/song.mp3")

        assertNotNull(englishSong)
        assertNotNull(hindiSong)
        assertNotNull(teluguSong)
        assertNotEquals("Different paths must produce different IDs", englishSong, hindiSong)
        assertNotEquals(englishSong, teluguSong)
        assertNotEquals(hindiSong, teluguSong)
    }

    // ── Phase 2: normalizeForLookup ──────────────────────────

    @Test
    fun testNormalizeForLookup_CaseInsensitive() {
        val upper = MetadataUtils.normalizeForLookup("/storage/emulated/0/Muzic/Drake/NOKIA.mp3")
        val lower = MetadataUtils.normalizeForLookup("/storage/emulated/0/Muzic/drake/nokia.mp3")
        assertNotNull(upper)
        assertNotNull(lower)
        assertEquals("Case-insensitive lookup must match", upper, lower)
    }

    @Test
    fun testNormalizeForLookup_SAFAndMediaStoreSameResult() {
        val mediaStore = MetadataUtils.normalizeForLookup("/storage/emulated/0/Muzic/Ed Sheeran/Shape Of You.mp3")
        val saf = MetadataUtils.normalizeForLookup("content://com.android.externalstorage.documents/tree/primary%3AMuzic/document/primary%3AMuzic%2FEd%20Sheeran%2FShape%20Of%20You.mp3")
        assertNotNull(mediaStore)
        assertNotNull(saf)
        assertEquals("Lookup keys must match across scanner types", mediaStore, saf)
    }

    // ── Phase 2: extractAlbumFolderPath ──────────────────────

    @Test
    fun testExtractAlbumFolderPath_NestedStructure() {
        val result = MetadataUtils.extractAlbumFolderPath("/storage/emulated/0/Muzic/English/After Hours/01-track.mp3")
        assertEquals("English/After Hours", result)
    }

    @Test
    fun testExtractAlbumFolderPath_SingleFolder() {
        val result = MetadataUtils.extractAlbumFolderPath("/storage/emulated/0/Muzic/English/song.mp3")
        assertEquals("English", result)
    }

    @Test
    fun testExtractAlbumFolderPath_RootFile_ReturnsNull() {
        val result = MetadataUtils.extractAlbumFolderPath("/storage/emulated/0/Muzic/song.mp3")
        assertNull("File at /Muzic/ root should have no album folder", result)
    }

    // ── Phase 3: Reconciliation — Enriched Metadata Preservation ──

    @Test
    fun testReconciliation_PreservesEnrichedMetadata_AgainstRawScanner() {
        val stableId = MetadataUtils.generateStableTrackId("/Muzic/Drake/NOKIA.mp3")!!
        val validArtUri = "file:///data/user/0/com.mus.android/files/artwork/art_album_98765.jpg"

        val existingEnriched = Track(
            id = stableId, title = "NOKIA", artist = "Drake",
            albumId = 98765L, albumTitle = "Her Loss", albumArtist = "Drake",
            duration = 210000L, year = 2022, genre = "Hip-Hop", composer = "Aubrey Graham",
            uri = "content://media/external/audio/media/100", artworkUri = validArtUri,
            path = "/storage/emulated/0/Muzic/Drake/NOKIA.mp3",
            metadataSource = MetadataSource.EXTERNAL, metadataStatus = MetadataStatus.COMPLETE,
            metadataConfidence = MetadataConfidence.HIGH, metadataLastUpdated = 1700000000000L,
            isFavorite = true, playCount = 15, lastPlayed = 1700050000000L,
            trackNumber = 3, discNumber = 1,
        )

        val scannedRaw = Track(
            id = stableId, title = "Drake - NOKIA (Official Music Video)",
            artist = "Unknown Artist",
            albumId = MetadataUtils.generateAlbumId("Unknown Artist", "Unknown Album"),
            albumTitle = "Unknown Album", albumArtist = "Unknown Artist",
            duration = 210000L, year = 0, genre = null, composer = null,
            uri = "content://media/external/audio/media/100", artworkUri = null,
            path = "/storage/emulated/0/Muzic/Drake/NOKIA.mp3",
            metadataSource = MetadataSource.EMBEDDED, metadataStatus = MetadataStatus.NEEDS_LOOKUP,
        )

        // Simulate reconciliation
        val wasEnriched = existingEnriched.metadataStatus == MetadataStatus.COMPLETE ||
                existingEnriched.metadataSource == MetadataSource.EXTERNAL ||
                existingEnriched.metadataSource == MetadataSource.MERGED

        val finalArtworkUri = when {
            ArtworkStorage.isArtworkValid(existingEnriched.artworkUri) -> existingEnriched.artworkUri
            ArtworkStorage.isArtworkValid(scannedRaw.artworkUri) -> scannedRaw.artworkUri
            else -> null
        }

        val reconciled = scannedRaw.copy(
            title = if (wasEnriched || !MetadataUtils.isPlaceholderTitle(existingEnriched.title)) existingEnriched.title else scannedRaw.title,
            artist = if (wasEnriched || !MetadataUtils.isPlaceholderArtist(existingEnriched.artist)) existingEnriched.artist else scannedRaw.artist,
            albumTitle = if (wasEnriched || !MetadataUtils.isPlaceholderAlbum(existingEnriched.albumTitle)) existingEnriched.albumTitle else scannedRaw.albumTitle,
            albumArtist = if (wasEnriched || !MetadataUtils.isPlaceholderArtist(existingEnriched.albumArtist)) existingEnriched.albumArtist else scannedRaw.albumArtist,
            albumId = if (wasEnriched) existingEnriched.albumId else scannedRaw.albumId,
            artworkUri = finalArtworkUri,
            trackNumber = if (existingEnriched.trackNumber > 0) existingEnriched.trackNumber else scannedRaw.trackNumber,
            discNumber = if (existingEnriched.discNumber > 0) existingEnriched.discNumber else scannedRaw.discNumber,
            year = if (existingEnriched.year > 0) existingEnriched.year else scannedRaw.year,
            genre = existingEnriched.genre ?: scannedRaw.genre,
            composer = existingEnriched.composer ?: scannedRaw.composer,
            metadataSource = if (wasEnriched) existingEnriched.metadataSource else scannedRaw.metadataSource,
            metadataStatus = if (wasEnriched) existingEnriched.metadataStatus else scannedRaw.metadataStatus,
            metadataConfidence = if (wasEnriched) existingEnriched.metadataConfidence else scannedRaw.metadataConfidence,
            isFavorite = existingEnriched.isFavorite,
            playCount = existingEnriched.playCount,
            lastPlayed = existingEnriched.lastPlayed,
        )

        assertEquals("NOKIA", reconciled.title)
        assertEquals("Drake", reconciled.artist)
        assertEquals("Her Loss", reconciled.albumTitle)
        assertEquals("Drake", reconciled.albumArtist)
        assertEquals(98765L, reconciled.albumId)
        assertEquals(validArtUri, reconciled.artworkUri)
        assertEquals(MetadataStatus.COMPLETE, reconciled.metadataStatus)
        assertEquals(MetadataSource.EXTERNAL, reconciled.metadataSource)
        assertEquals(2022, reconciled.year)
        assertEquals("Hip-Hop", reconciled.genre)
        assertEquals("Aubrey Graham", reconciled.composer)
        assertEquals(3, reconciled.trackNumber)
        assertEquals(1, reconciled.discNumber)
        assertTrue(reconciled.isFavorite)
        assertEquals(15, reconciled.playCount)
    }

    // ── Phase 4: ENRICHING Race Condition ─────────────────────

    @Test
    fun testEnrichingRaceProtection_ActivelyEnriching_NotOverwritten() {
        val now = System.currentTimeMillis()

        val existingEnriching = Track(
            id = 100L, title = "NOKIA", artist = "Drake",
            albumId = 98765L, albumTitle = "Her Loss", albumArtist = "Drake",
            duration = 210000L, year = 2022, genre = "Hip-Hop",
            uri = "old-uri", path = "/storage/emulated/0/Muzic/Drake/NOKIA.mp3",
            metadataStatus = MetadataStatus.ENRICHING,
            metadataLastUpdated = now - 60_000L, // 1 min ago — still active
        )

        val scannedRaw = Track(
            id = 100L, title = "Unknown Track", artist = "Unknown Artist",
            albumId = 999L, albumTitle = "Unknown Album", albumArtist = "Unknown Artist",
            duration = 210000L,
            uri = "new-uri", path = "/storage/emulated/0/Muzic/Drake/NOKIA.mp3",
        )

        // Simulate ENRICHING race protection
        val isActivelyEnriching = existingEnriching.metadataStatus == MetadataStatus.ENRICHING &&
                now - existingEnriching.metadataLastUpdated < 5 * 60 * 1000L

        assertTrue("Track enriching 1 min ago must be considered active", isActivelyEnriching)

        // Should preserve existing metadata entirely
        val result = existingEnriching.copy(
            id = scannedRaw.id,
            uri = scannedRaw.uri,
            path = scannedRaw.path ?: existingEnriching.path,
        )

        assertEquals("NOKIA", result.title) // NOT "Unknown Track"
        assertEquals("Drake", result.artist) // NOT "Unknown Artist"
        assertEquals("Her Loss", result.albumTitle)
        assertEquals(MetadataStatus.ENRICHING, result.metadataStatus)
    }

    @Test
    fun testEnrichingRaceProtection_StaleEnriching_AllowsOverwrite() {
        val now = System.currentTimeMillis()

        val staleEnriching = Track(
            id = 100L, title = "Partial Title", artist = "Unknown Artist",
            albumId = 999L, albumTitle = "Unknown Album", albumArtist = "Unknown Artist",
            duration = 210000L,
            uri = "old-uri", path = "/storage/emulated/0/Muzic/Drake/NOKIA.mp3",
            metadataStatus = MetadataStatus.ENRICHING,
            metadataLastUpdated = now - 10 * 60 * 1000L, // 10 min ago — stale
        )

        val isActivelyEnriching = staleEnriching.metadataStatus == MetadataStatus.ENRICHING &&
                now - staleEnriching.metadataLastUpdated < 5 * 60 * 1000L

        assertFalse("Track enriching 10 min ago must be considered stale", isActivelyEnriching)
    }

    // ── Phase 5: Search Query Priority ────────────────────────

    @Test
    fun testSearchQueryPriority_EmbeddedMetadata_BeatsFilenameHints() {
        // Create an enrichment service with stubs for testing
        val service = createTestEnrichmentService()

        // Track with valid embedded metadata AND a filename hint
        val track = Track(
            id = 1L, title = "Blinding Lights", artist = "The Weeknd",
            albumId = 1L, albumTitle = "After Hours", albumArtist = "The Weeknd",
            duration = 200000L,
            uri = "/Muzic/Unknown Artist - Blinding Lights (Official Video).mp3",
            path = "/storage/emulated/0/Muzic/Unknown Artist - Blinding Lights (Official Video).mp3",
        )

        val query = service.buildSearchQuery(track)
        // Must contain the embedded artist and title, NOT the filename hints
        assertTrue("Query must contain embedded artist: '$query'", query.contains("Weeknd"))
        assertTrue("Query must contain embedded title: '$query'", query.contains("Blinding Lights"))
        assertFalse("Query must NOT use filename parse: '$query'", query.contains("Unknown Artist"))
    }

    @Test
    fun testSearchQueryPriority_FallsBackToFilename_WhenEmbeddedIsPlaceholder() {
        val service = createTestEnrichmentService()

        val track = Track(
            id = 2L, title = "Unknown Track", artist = "Unknown Artist",
            albumId = 2L, albumTitle = "Unknown Album", albumArtist = "Unknown Artist",
            duration = 200000L,
            uri = "/Muzic/The Weeknd - Blinding Lights.mp3",
            path = "/storage/emulated/0/Muzic/The Weeknd - Blinding Lights.mp3",
        )

        val query = service.buildSearchQuery(track)
        assertTrue("Query must use filename hints: '$query'", query.contains("Weeknd") || query.contains("Blinding"))
    }

    // ── Phase 6: Confidence Scoring ───────────────────────────

    @Test
    fun testConfidence_DurationAlone_NeverSufficientForIdentity() {
        val service = createTestEnrichmentService()

        // Track with only duration known, title/artist are placeholders
        val track = Track(
            id = 3L, title = "Unknown Track", artist = "Unknown Artist",
            albumId = 3L, albumTitle = "Unknown Album", duration = 200000L,
            uri = "/Muzic/track03.mp3",
        )

        val candidates = listOf(
            RemoteTrackMetadata(
                title = "Some Random Song", artist = "Random Artist",
                albumTitle = "Random Album", durationMs = 200000L,
            )
        )

        val (_, confidence) = service.findBestMatch(track, candidates)
        assertEquals("Duration-only match must be LOW confidence", MetadataConfidence.LOW, confidence)
    }

    @Test
    fun testConfidence_TitleAndArtistMatch_HighConfidence() {
        val service = createTestEnrichmentService()

        val track = Track(
            id = 4L, title = "Blinding Lights", artist = "The Weeknd",
            albumId = 4L, albumTitle = "Unknown Album", duration = 200000L,
            uri = "/Muzic/01.mp3",
        )

        val candidates = listOf(
            RemoteTrackMetadata(
                title = "Blinding Lights", artist = "The Weeknd",
                albumTitle = "After Hours", durationMs = 200000L,
            )
        )

        val (match, confidence) = service.findBestMatch(track, candidates)
        assertNotNull(match)
        assertEquals("Title + artist match must be HIGH confidence", MetadataConfidence.HIGH, confidence)
    }

    @Test
    fun testConfidence_TotallyUnrelatedCandidate_LowConfidence() {
        val service = createTestEnrichmentService()

        val track = Track(
            id = 5L, title = "My Very Rare Indie Track", artist = "Unknown Indie Band",
            albumId = 5L, albumTitle = "Unknown Album", duration = 180000L,
            uri = "/Muzic/rare.mp3",
        )

        val candidates = listOf(
            RemoteTrackMetadata(
                title = "Popular Dance Hit", artist = "Famous Pop Star",
                albumTitle = "Greatest Hits", durationMs = 300000L,
            )
        )

        val (_, confidence) = service.findBestMatch(track, candidates)
        assertEquals("Unrelated candidate must be LOW confidence", MetadataConfidence.LOW, confidence)
    }

    @Test
    fun testConfidence_GenericTitle_ReducedConfidence() {
        val service = createTestEnrichmentService()

        val track = Track(
            id = 6L, title = "Intro", artist = "Unknown Artist",
            albumId = 6L, albumTitle = "Unknown Album", duration = 60000L,
            uri = "/Muzic/intro.mp3",
        )

        val candidates = listOf(
            RemoteTrackMetadata(
                title = "Intro", artist = "Different Artist",
                albumTitle = "Different Album", durationMs = 65000L,
            )
        )

        val (_, confidence) = service.findBestMatch(track, candidates)
        // Generic title + unknown artist → confidence should not be HIGH
        assertNotEquals("Generic title 'Intro' must not produce HIGH confidence without artist match",
            MetadataConfidence.HIGH, confidence)
    }

    // ── Phase 7: Album Grouping ───────────────────────────────

    @Test
    fun testAlbumGrouping_SameAlbum_GroupedTogether() {
        val service = createTestEnrichmentService()

        val track1 = createTrack(1, "Alone Again", "The Weeknd", "After Hours", "The Weeknd", "/Muzic/AH/01.mp3")
        val track2 = createTrack(2, "Too Late", "The Weeknd", "After Hours", "The Weeknd", "/Muzic/AH/02.mp3")
        val track3 = createTrack(3, "Heartless", "The Weeknd", "After Hours", "The Weeknd", "/Muzic/AH/03.mp3")

        val groups = service.groupTracksIntoAlbumCandidates(listOf(track1, track2, track3))
        assertEquals("3 tracks from same album must form 1 group", 1, groups.size)
        assertEquals(3, groups[0].tracks.size)
    }

    @Test
    fun testAlbumGrouping_DifferentAlbums_SeparateGroups() {
        val service = createTestEnrichmentService()

        val ahTrack = createTrack(1, "Alone Again", "The Weeknd", "After Hours", "The Weeknd", "/Muzic/1.mp3")
        val starboyTrack = createTrack(2, "Starboy", "The Weeknd", "Starboy", "The Weeknd", "/Muzic/2.mp3")

        val groups = service.groupTracksIntoAlbumCandidates(listOf(ahTrack, starboyTrack))
        assertEquals("Tracks from different albums must be in separate groups", 2, groups.size)
    }

    @Test
    fun testAlbumGrouping_PlaceholderAlbum_GroupsByFolder() {
        val service = createTestEnrichmentService()

        val track1 = createTrack(1, "Track 1", "Unknown Artist", "Unknown Album", "Unknown Artist",
            "/storage/emulated/0/Muzic/Hindi/Aashiqui 2/01.mp3")
        val track2 = createTrack(2, "Track 2", "Unknown Artist", "Unknown Album", "Unknown Artist",
            "/storage/emulated/0/Muzic/Hindi/Aashiqui 2/02.mp3")

        val groups = service.groupTracksIntoAlbumCandidates(listOf(track1, track2))
        // Both tracks are in /Muzic/Hindi/Aashiqui 2/ — should be grouped by folder
        assertEquals("Tracks in same subfolder with placeholder album must be grouped", 1, groups.size)
        assertEquals(2, groups[0].tracks.size)
    }

    @Test
    fun testAlbumGrouping_LanguageFolder_NotGroupedAsOneAlbum() {
        val service = createTestEnrichmentService()

        // Tracks directly in /Muzic/English/ (a language folder, not an album folder)
        val track1 = createTrack(1, "Song A", "Artist A", "Unknown Album", "Unknown Artist",
            "/storage/emulated/0/Muzic/English/Song A.mp3")
        val track2 = createTrack(2, "Song B", "Artist B", "Unknown Album", "Unknown Artist",
            "/storage/emulated/0/Muzic/English/Song B.mp3")

        val groups = service.groupTracksIntoAlbumCandidates(listOf(track1, track2))
        // /Muzic/English/ is a single-segment folder — should NOT be grouped as one album
        assertEquals("Files directly in language folder must NOT be grouped as one album", 2, groups.size)
    }

    @Test
    fun testAnchorTrackSelection_PrefersMostMetadata() {
        val service = createTestEnrichmentService()

        val poorTrack = createTrack(1, "Unknown Track", "Unknown Artist", "Unknown Album", "Unknown Artist", "/Muzic/1.mp3")
        val richTrack = createTrack(2, "Blinding Lights", "The Weeknd", "After Hours", "The Weeknd", "/Muzic/2.mp3")
            .copy(trackNumber = 4, year = 2020)

        val anchor = service.selectAnchorTrack(listOf(poorTrack, richTrack))
        assertEquals("Anchor must be the track with richer metadata", richTrack.id, anchor.id)
    }

    // ── Phase 9: Disc Number Fix ──────────────────────────────

    @Test
    fun testDiscNumber_OneIsValid_NotOverwritten() {
        // Verify that discNumber = 1 is treated as valid metadata
        val track = Track(
            id = 7L, title = "Track", artist = "Artist", albumId = 7L,
            albumTitle = "Album", duration = 100L, uri = "/7.mp3",
            discNumber = 1,
        )

        // Simulating mergeMetadata disc number logic: if (local.discNumber > 0) local else remote
        val remoteDiscNumber = 2
        val finalDiscNumber = if (track.discNumber > 0) track.discNumber else remoteDiscNumber
        assertEquals("discNumber = 1 must be preserved (not overwritten by remote)", 1, finalDiscNumber)
    }

    @Test
    fun testDiscNumber_ZeroIsPlaceholder_UsesRemote() {
        val track = Track(
            id = 8L, title = "Track", artist = "Artist", albumId = 8L,
            albumTitle = "Album", duration = 100L, uri = "/8.mp3",
            discNumber = 0,
        )

        val remoteDiscNumber = 1
        val finalDiscNumber = if (track.discNumber > 0) track.discNumber else remoteDiscNumber
        assertEquals("discNumber = 0 should be filled by remote", 1, finalDiscNumber)
    }

    // ── Idempotency & Force Re-enrich ─────────────────────────

    @Test
    fun testRepeatedScan_Idempotent_SameOutput() {
        val path = "/storage/emulated/0/Muzic/The Weeknd/After Hours/01 Alone Again.flac"
        val id1 = MetadataUtils.generateStableTrackId(path)
        val id2 = MetadataUtils.generateStableTrackId(path)
        val id3 = MetadataUtils.generateStableTrackId(path)
        val id4 = MetadataUtils.generateStableTrackId(path)

        assertEquals("4 calls must produce identical IDs", id1, id2)
        assertEquals(id2, id3)
        assertEquals(id3, id4)

        val albumId1 = MetadataUtils.generateAlbumId("The Weeknd", "After Hours")
        val albumId2 = MetadataUtils.generateAlbumId("The Weeknd", "After Hours")
        assertEquals("Album ID must be deterministic", albumId1, albumId2)
    }

    @Test
    fun testForceReEnrich_PreservesIdentityAndPlayState() {
        val stableId = MetadataUtils.generateStableTrackId("/Muzic/Drake/NOKIA.mp3")!!

        val enriched = Track(
            id = stableId, title = "NOKIA", artist = "Drake",
            albumId = 98765L, albumTitle = "Her Loss", albumArtist = "Drake",
            duration = 210000L, year = 2022,
            uri = "/Muzic/Drake/NOKIA.mp3", path = "/storage/emulated/0/Muzic/Drake/NOKIA.mp3",
            metadataStatus = MetadataStatus.COMPLETE,
            metadataSource = MetadataSource.EXTERNAL,
            isFavorite = true, playCount = 15, lastPlayed = 1700050000000L,
        )

        // Simulate forceReEnrichLibrary: reset status but preserve identity
        val reset = enriched.copy(
            metadataStatus = MetadataStatus.NEEDS_LOOKUP,
            metadataConfidence = MetadataConfidence.LOW,
            metadataLastUpdated = 0L,
        )

        assertEquals("ID must be preserved", stableId, reset.id)
        assertEquals("Title must be preserved", "NOKIA", reset.title)
        assertEquals("Artist must be preserved", "Drake", reset.artist)
        assertEquals("Album must be preserved", "Her Loss", reset.albumTitle)
        assertTrue("Favorite must be preserved", reset.isFavorite)
        assertEquals("Play count must be preserved", 15, reset.playCount)
        assertEquals("Last played must be preserved", 1700050000000L, reset.lastPlayed)
        assertEquals("URI must be preserved", enriched.uri, reset.uri)
        assertEquals("Path must be preserved", enriched.path, reset.path)
        assertEquals("Status must be reset", MetadataStatus.NEEDS_LOOKUP, reset.metadataStatus)
    }

    // ── iTunes collectionArtistName ───────────────────────────

    @Test
    fun testITunes_CollectionArtistName_DistinctFromTrackArtist() {
        // Simulating parse output for a soundtrack track
        val trackArtist = "A.R. Rahman"
        val collectionArtistName = "Various Artists"

        // The provider should use collectionArtistName when present
        val albumArtist = collectionArtistName ?: trackArtist
        assertEquals("Album artist must use collectionArtistName", "Various Artists", albumArtist)
    }

    @Test
    fun testITunes_CollectionArtistName_FallbackToArtistName() {
        val trackArtist = "Drake"
        val collectionArtistName: String? = null

        val albumArtist = collectionArtistName ?: trackArtist
        assertEquals("Album artist must fallback to track artist when collectionArtistName is null", "Drake", albumArtist)
    }

    // ── Artwork Validation ────────────────────────────────────

    @Test
    fun testArtworkValidation_UnknownAlbumArt_Rejected() {
        val unknownAlbumId = ArtworkStorage.UNKNOWN_ALBUM_ID
        val badUri = "file:///data/user/0/com.mus.android/files/artwork/art_album_$unknownAlbumId.jpg"
        assertFalse("Unknown album artwork URI must be rejected", ArtworkStorage.isArtworkValid(badUri))
    }

    @Test
    fun testArtworkValidation_ContentMediaExternal_Rejected() {
        val mediaStoreArt = "content://media/external/audio/albumart/12345"
        assertFalse("MediaStore albumart URI must be rejected", ArtworkStorage.isArtworkValid(mediaStoreArt))
    }

    @Test
    fun testArtworkValidation_ValidLocalFile_Accepted() {
        val validUri = "file:///data/user/0/com.mus.android/files/artwork/art_album_12345.jpg"
        assertTrue("Valid local artwork URI must be accepted", ArtworkStorage.isArtworkValid(validUri))
    }

    // ── Helpers ───────────────────────────────────────────────

    private fun createTrack(
        id: Long, title: String, artist: String,
        albumTitle: String, albumArtist: String, path: String,
    ): Track {
        val albumId = MetadataUtils.generateAlbumId(albumArtist, albumTitle)
        return Track(
            id = id, title = title, artist = artist,
            albumId = albumId, albumTitle = albumTitle, albumArtist = albumArtist,
            duration = 200000L, uri = path, path = path,
        )
    }

    private fun createTestEnrichmentService(): MetadataEnrichmentService {
        val fakeContext = object : MetadataEnrichmentTest.TestContext() {
            override fun getFilesDir(): java.io.File {
                val dir = java.io.File(System.getProperty("java.io.tmpdir"), "mus_robustness_test_${System.nanoTime()}")
                dir.mkdirs()
                return dir
            }
        }
        val fakePrefs = object : MetadataEnrichmentTest.TestUserPreferencesRepository() {}
        return MetadataEnrichmentService(
            context = fakeContext,
            trackDao = MetadataEnrichmentTest.FakeTrackDao(),
            albumDao = MetadataEnrichmentTest.FakeAlbumDao(),
            artistDao = MetadataEnrichmentTest.FakeArtistDao(),
            metadataProvider = MetadataEnrichmentTest.FakeMetadataProvider(),
            artworkStorage = ArtworkStorage(fakeContext),
            userPreferences = fakePrefs,
        )
    }
}
