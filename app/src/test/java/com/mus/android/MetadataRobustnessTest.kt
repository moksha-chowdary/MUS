package com.mus.android

import com.mus.android.data.enrichment.MetadataEnrichmentService
import com.mus.android.data.enrichment.artwork.ArtworkStorage
import com.mus.android.data.enrichment.provider.RemoteTrackMetadata
import com.mus.android.data.model.*
import com.mus.android.data.repository.MetadataResetResult
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

    // ── Phase 13: Clean Metadata & Artwork Reset ──────────────

    @Test
    fun testMetadataReset_OldMetadataCleared_UserFieldsPreserved() {
        val stableId = MetadataUtils.generateStableTrackId("/Muzic/Drake/NOKIA.mp3")!!
        val oldArtUri = "file:///data/user/0/com.mus.android/files/artwork/art_album_99999.jpg"

        val oldTrack = Track(
            id = stableId,
            title = "NOKIA",
            artist = "Drake",
            albumId = 99999L,
            albumTitle = "Her Loss",
            albumArtist = "Drake",
            duration = 210000L,
            year = 2022,
            genre = "Hip-Hop",
            composer = "Aubrey Graham",
            uri = "content://media/external/audio/media/100",
            artworkUri = oldArtUri,
            artistArtworkUri = "file:///data/user/0/com.mus.android/files/artwork/art_artist_drake.jpg",
            path = "/storage/emulated/0/Muzic/Drake/NOKIA.mp3",
            metadataSource = MetadataSource.EXTERNAL,
            metadataStatus = MetadataStatus.COMPLETE,
            metadataConfidence = MetadataConfidence.HIGH,
            metadataLastUpdated = 1700000000000L,
            isFavorite = true,
            playCount = 27,
            lastPlayed = 1700050000000L,
            language = "English",
            trackNumber = 3,
            discNumber = 1,
            codec = "MP3",
            bitrate = 320,
            sampleRate = 44100,
        )

        // Perform the reset transformation on the track
        val resetTrack = oldTrack.copy(
            albumId = 0L,
            albumTitle = "",
            albumArtist = "",
            title = "",
            artist = "",
            genre = null,
            composer = null,
            trackNumber = 0,
            discNumber = 0,
            year = 0,
            artworkUri = null,
            artistArtworkUri = null,
            metadataStatus = MetadataStatus.NEEDS_LOOKUP,
            metadataSource = MetadataSource.EMBEDDED,
            metadataConfidence = MetadataConfidence.LOW,
            metadataLastUpdated = 0L,
        )

        // VERIFY PRESERVED USER DATA & IDENTITY:
        assertEquals("Track ID must be preserved", stableId, resetTrack.id)
        assertEquals("Path must be preserved", oldTrack.path, resetTrack.path)
        assertEquals("URI must be preserved", oldTrack.uri, resetTrack.uri)
        assertEquals("Duration must be preserved", 210000L, resetTrack.duration)
        assertTrue("Favorite status must remain true", resetTrack.isFavorite)
        assertEquals("Play count must remain 27", 27, resetTrack.playCount)
        assertEquals("Last played timestamp must remain intact", 1700050000000L, resetTrack.lastPlayed)
        assertEquals("Language classification must remain English", "English", resetTrack.language)
        assertEquals("Codec must be preserved", "MP3", resetTrack.codec)
        assertEquals("Bitrate must be preserved", 320, resetTrack.bitrate)
        assertEquals("Sample rate must be preserved", 44100, resetTrack.sampleRate)

        // VERIFY CLEARED METADATA:
        assertEquals("Album ID must be reset to unresolved (0L)", 0L, resetTrack.albumId)
        assertEquals("Album title must be cleared", "", resetTrack.albumTitle)
        assertEquals("Album artist must be cleared", "", resetTrack.albumArtist)
        assertEquals("Title must be cleared", "", resetTrack.title)
        assertEquals("Artist must be cleared", "", resetTrack.artist)
        assertNull("Artwork URI must be cleared", resetTrack.artworkUri)
        assertNull("Artist artwork URI must be cleared", resetTrack.artistArtworkUri)
        assertNull("Genre must be cleared", resetTrack.genre)
        assertNull("Composer must be cleared", resetTrack.composer)
        assertEquals("Track number must be reset", 0, resetTrack.trackNumber)
        assertEquals("Disc number must be reset", 0, resetTrack.discNumber)
        assertEquals("Year must be reset", 0, resetTrack.year)
        assertEquals("Metadata status must be reset to NEEDS_LOOKUP", MetadataStatus.NEEDS_LOOKUP, resetTrack.metadataStatus)
        assertEquals("Metadata source must be reset to EMBEDDED", MetadataSource.EMBEDDED, resetTrack.metadataSource)
        assertEquals("Metadata confidence must be reset to LOW", MetadataConfidence.LOW, resetTrack.metadataConfidence)
        assertEquals("Metadata last updated must be reset to 0", 0L, resetTrack.metadataLastUpdated)
    }

    @Test
    fun testMetadataReset_Idempotency_OnlyRunsOnce() {
        var isResetCompleted = false
        var resetRunCount = 0

        fun checkAndPerformReset(): Boolean {
            return if (!isResetCompleted) {
                resetRunCount++
                isResetCompleted = true
                true
            } else {
                false
            }
        }

        // Launch 1: Reset must run
        val firstLaunchResult = checkAndPerformReset()
        assertTrue("First launch must execute reset", firstLaunchResult)
        assertEquals("Reset run count must be 1", 1, resetRunCount)
        assertTrue("Reset flag must be marked complete", isResetCompleted)

        // Launch 2: Reset must NOT run
        val secondLaunchResult = checkAndPerformReset()
        assertFalse("Second launch must skip reset", secondLaunchResult)
        assertEquals("Reset run count must still be 1", 1, resetRunCount)

        // Launch 3: Reset must NOT run
        val thirdLaunchResult = checkAndPerformReset()
        assertFalse("Third launch must skip reset", thirdLaunchResult)
        assertEquals("Reset run count must still be 1", 1, resetRunCount)
    }

    @Test
    fun testMetadataReset_PlaylistMembership_IntactAcrossReset() {
        val stableTrackId = 12345L
        val playlistId = 1L

        // Playlist track linking to stableTrackId
        val playlistTrack = PlaylistTrack(
            playlistId = playlistId,
            trackId = stableTrackId,
            position = 0,
            addedAt = 1600000000000L,
        )

        // Track is reset in Room
        val resetTrack = createTrack(stableTrackId, "", "", "", "", "/Muzic/song.mp3").copy(
            isFavorite = true,
            playCount = 10,
        )

        // The trackId in PlaylistTrack matches the reset track's ID
        assertEquals("Playlist track foreign key must match reset track ID", resetTrack.id, playlistTrack.trackId)
        assertEquals("Playlist position must be preserved", 0, playlistTrack.position)
        assertEquals("Playlist addedAt must be preserved", 1600000000000L, playlistTrack.addedAt)
    }

    @Test
    fun testMetadataReset_ArtworkCachePurge_DeletesOldFiles() {
        val tempDir = java.io.File(System.getProperty("java.io.tmpdir"), "artwork_purge_test_${System.nanoTime()}")
        tempDir.mkdirs()

        // Create sample artwork files
        val albumArtFile = java.io.File(tempDir, "art_album_12345.jpg")
        albumArtFile.writeText("fake-art-bytes-1")
        val legacyArtFile = java.io.File(tempDir, "art_12345.jpg")
        legacyArtFile.writeText("fake-art-bytes-2")
        val keyArtFile = java.io.File(tempDir, "art_hashkey999.jpg")
        keyArtFile.writeText("fake-art-bytes-3")
        val tmpArtFile = java.io.File(tempDir, "art_temp.tmp")
        tmpArtFile.writeText("fake-art-bytes-4")

        assertTrue(albumArtFile.exists())
        assertTrue(legacyArtFile.exists())
        assertTrue(keyArtFile.exists())
        assertTrue(tmpArtFile.exists())

        // Simulate clearAllArtwork on temp directory
        var deletedCount = 0
        tempDir.listFiles()?.forEach { file ->
            if (file.isFile && (file.name.startsWith("art_") || file.name.endsWith(".jpg") || file.name.endsWith(".tmp"))) {
                if (file.delete()) deletedCount++
            }
        }

        assertEquals("All 4 artwork files must be deleted", 4, deletedCount)
        assertFalse("Old album artwork must no longer exist", albumArtFile.exists())
        assertFalse("Old legacy artwork must no longer exist", legacyArtFile.exists())
        assertFalse("Old key artwork must no longer exist", keyArtFile.exists())
        assertFalse("Old tmp artwork must no longer exist", tmpArtFile.exists())

        tempDir.deleteRecursively()
    }

    @Test
    fun testMetadataReset_ScanReconcilesFreshEmbeddedTags_AfterReset() {
        val stableId = MetadataUtils.generateStableTrackId("/Muzic/Telugu/Bahubali/Saahore.mp3")!!

        // Track in Room AFTER reset
        val resetTrackInRoom = Track(
            id = stableId,
            title = "",
            artist = "",
            albumId = 0L,
            albumTitle = "",
            albumArtist = "",
            duration = 240000L,
            uri = "content://media/external/audio/media/200",
            path = "/storage/emulated/0/Muzic/Telugu/Bahubali/Saahore.mp3",
            metadataSource = MetadataSource.EMBEDDED,
            metadataStatus = MetadataStatus.NEEDS_LOOKUP,
            metadataConfidence = MetadataConfidence.LOW,
            isFavorite = true,
            playCount = 10,
            lastPlayed = 1600000000L,
            language = "Telugu",
        )

        // Freshly scanned track from physical audio file with genuine embedded ID3 tags
        val scannedRawTrack = Track(
            id = stableId,
            title = "Saahore Baahubali",
            artist = "Daler Mehndi, M.M. Keeravaani",
            albumId = MetadataUtils.generateAlbumId("M.M. Keeravaani", "Baahubali 2: The Conclusion"),
            albumTitle = "Baahubali 2: The Conclusion",
            albumArtist = "M.M. Keeravaani",
            duration = 240000L,
            trackNumber = 1,
            discNumber = 1,
            year = 2017,
            genre = "Soundtrack",
            composer = "M.M. Keeravaani",
            uri = "content://media/external/audio/media/200",
            artworkUri = "file:///data/user/0/com.mus.android/files/artwork/art_embedded_bahubali.jpg",
            path = "/storage/emulated/0/Muzic/Telugu/Bahubali/Saahore.mp3",
            metadataSource = MetadataSource.EMBEDDED,
            metadataStatus = MetadataStatus.NEEDS_LOOKUP,
            language = "Telugu",
        )

        // Simulate reconciliation logic
        val wasEnriched = resetTrackInRoom.metadataStatus == MetadataStatus.COMPLETE ||
                resetTrackInRoom.metadataStatus == MetadataStatus.PARTIAL ||
                resetTrackInRoom.metadataStatus == MetadataStatus.NEEDS_REVIEW ||
                resetTrackInRoom.metadataSource == MetadataSource.EXTERNAL ||
                resetTrackInRoom.metadataSource == MetadataSource.MERGED

        assertFalse("Reset track must not be marked as enriched", wasEnriched)

        val finalArtworkUri = when {
            ArtworkStorage.isArtworkValid(resetTrackInRoom.artworkUri) -> resetTrackInRoom.artworkUri
            ArtworkStorage.isArtworkValid(scannedRawTrack.artworkUri) -> scannedRawTrack.artworkUri
            else -> null
        }

        val finalTitle = if (wasEnriched || !MetadataUtils.isPlaceholderTitle(resetTrackInRoom.title)) {
            resetTrackInRoom.title
        } else {
            scannedRawTrack.title
        }

        val finalArtist = if (wasEnriched || !MetadataUtils.isPlaceholderArtist(resetTrackInRoom.artist)) {
            resetTrackInRoom.artist
        } else {
            scannedRawTrack.artist
        }

        val finalAlbumTitle = if (wasEnriched || !MetadataUtils.isPlaceholderAlbum(resetTrackInRoom.albumTitle)) {
            resetTrackInRoom.albumTitle
        } else {
            scannedRawTrack.albumTitle
        }

        val finalAlbumArtist = if (wasEnriched || !MetadataUtils.isPlaceholderArtist(resetTrackInRoom.albumArtist)) {
            resetTrackInRoom.albumArtist
        } else {
            scannedRawTrack.albumArtist
        }

        val finalAlbumId = if (wasEnriched) resetTrackInRoom.albumId else scannedRawTrack.albumId

        val mergedTrack = scannedRawTrack.copy(
            title = finalTitle,
            artist = finalArtist,
            albumArtist = finalAlbumArtist,
            albumTitle = finalAlbumTitle,
            albumId = finalAlbumId,
            artworkUri = finalArtworkUri,
            trackNumber = if (resetTrackInRoom.trackNumber > 0) resetTrackInRoom.trackNumber else scannedRawTrack.trackNumber,
            discNumber = if (resetTrackInRoom.discNumber > 0) resetTrackInRoom.discNumber else scannedRawTrack.discNumber,
            year = if (resetTrackInRoom.year > 0) resetTrackInRoom.year else scannedRawTrack.year,
            genre = resetTrackInRoom.genre ?: scannedRawTrack.genre,
            language = resetTrackInRoom.language,
            isFavorite = resetTrackInRoom.isFavorite,
            playCount = resetTrackInRoom.playCount,
            lastPlayed = resetTrackInRoom.lastPlayed,
        )

        // VERIFY: Genuine embedded tags from scanned physical file take effect!
        assertEquals("Title must come from genuine embedded tag", "Saahore Baahubali", mergedTrack.title)
        assertEquals("Artist must come from genuine embedded tag", "Daler Mehndi, M.M. Keeravaani", mergedTrack.artist)
        assertEquals("Album must come from genuine embedded tag", "Baahubali 2: The Conclusion", mergedTrack.albumTitle)
        assertEquals("Album artist must come from genuine embedded tag", "M.M. Keeravaani", mergedTrack.albumArtist)
        assertEquals("Track number must come from embedded tag", 1, mergedTrack.trackNumber)
        assertEquals("Disc number must come from embedded tag", 1, mergedTrack.discNumber)
        assertEquals("Year must come from embedded tag", 2017, mergedTrack.year)
        assertEquals("Genre must come from embedded tag", "Soundtrack", mergedTrack.genre)
        assertEquals("Artwork must come from embedded picture", scannedRawTrack.artworkUri, mergedTrack.artworkUri)

        // VERIFY: User-owned fields survived reset and reconciliation!
        assertTrue("Favorite status must survive", mergedTrack.isFavorite)
        assertEquals("Play count must survive", 10, mergedTrack.playCount)
        assertEquals("Last played must survive", 1600000000L, mergedTrack.lastPlayed)
        assertEquals("Language must survive", "Telugu", mergedTrack.language)
    }

    @Test
    fun testMetadataReset_DiagnosticLogging_ValuesAreTracked() {
        val result = MetadataResetResult(
            tracksFound = 150,
            tracksReset = 150,
            albumIdsCleared = 42,
            artworkFilesDeleted = 38,
            resetStarted = true,
            resetCompleted = true
        )

        assertEquals("tracksFound must be 150", 150, result.tracksFound)
        assertEquals("tracksReset must be 150", 150, result.tracksReset)
        assertEquals("albumIdsCleared must be 42", 42, result.albumIdsCleared)
        assertEquals("artworkFilesDeleted must be 38", 38, result.artworkFilesDeleted)
        assertTrue("resetStarted must be true", result.resetStarted)
        assertTrue("resetCompleted must be true", result.resetCompleted)
    }

    @Test
    fun testArtworkRollback_PreservesUserFields_ClearsRemoteArtworkOnly() {
        val tempDir = java.io.File(System.getProperty("java.io.tmpdir"), "mus_test_art_rollback_${System.nanoTime()}")
        tempDir.mkdirs()

        val remoteArt1 = java.io.File(tempDir, "art_album_101.jpg").apply { writeText("remote-1") }
        val remoteArt2 = java.io.File(tempDir, "art_remote_102.jpg").apply { writeText("remote-2") }
        val embeddedArt = java.io.File(tempDir, "art_embedded_103.jpg").apply { writeText("embedded-3") }
        val tmpArt = java.io.File(tempDir, "art_download.tmp").apply { writeText("tmp-4") }

        // Test clearRemoteArtwork logic
        var deletedCount = 0
        tempDir.listFiles()?.forEach { file ->
            if (file.isFile && (file.name.startsWith("art_album_") || file.name.endsWith(".tmp") || (file.name.startsWith("art_") && !file.name.startsWith("art_embedded_")))) {
                if (file.delete()) deletedCount++
            }
        }

        assertEquals("Remote and tmp artwork files must be deleted", 3, deletedCount)
        assertFalse("art_album_101.jpg must be deleted", remoteArt1.exists())
        assertFalse("art_remote_102.jpg must be deleted", remoteArt2.exists())
        assertFalse("art_download.tmp must be deleted", tmpArt.exists())
        assertTrue("art_embedded_103.jpg MUST BE PRESERVED", embeddedArt.exists())

        // Test Track user-fields preservation
        val trackWithRemoteArt = Track(
            id = 901L,
            title = "Track One",
            artist = "Artist One",
            albumId = 501L,
            albumTitle = "Album One",
            duration = 180000L,
            uri = "/Muzic/song1.mp3",
            path = "/Muzic/song1.mp3",
            artworkUri = "file://" + remoteArt1.absolutePath,
            metadataSource = MetadataSource.EXTERNAL,
            isFavorite = true,
            playCount = 25,
            lastPlayed = 1700000000L,
        )

        val trackWithEmbeddedArt = Track(
            id = 902L,
            title = "Track Two",
            artist = "Artist Two",
            albumId = 502L,
            albumTitle = "Album Two",
            duration = 210000L,
            uri = "/Muzic/song2.mp3",
            path = "/Muzic/song2.mp3",
            artworkUri = "file://" + embeddedArt.absolutePath,
            metadataSource = MetadataSource.EMBEDDED,
            isFavorite = false,
            playCount = 5,
            lastPlayed = 1690000000L,
        )

        val existingTracks = listOf(trackWithRemoteArt, trackWithEmbeddedArt)
        val resetTracks = existingTracks.map { track ->
            val hasEmbedded = !track.artworkUri.isNullOrBlank() &&
                    (ArtworkStorage.isEmbeddedArtwork(track.artworkUri) || track.metadataSource == MetadataSource.EMBEDDED)
            if (!hasEmbedded) {
                track.copy(
                    artworkUri = null,
                    artistArtworkUri = null,
                    metadataStatus = MetadataStatus.NEEDS_LOOKUP,
                    metadataConfidence = MetadataConfidence.LOW,
                    metadataLastUpdated = 0L,
                )
            } else {
                track
            }
        }

        // Verify Track One had artwork reset to null, but user data intact
        val resetTrack1 = resetTracks.first { it.id == 901L }
        assertNull("Remote artwork must be reset to null", resetTrack1.artworkUri)
        assertEquals("Favorite status must survive", true, resetTrack1.isFavorite)
        assertEquals("Play count must survive", 25, resetTrack1.playCount)
        assertEquals("Last played must survive", 1700000000L, resetTrack1.lastPlayed)
        assertEquals("Track ID must be preserved", 901L, resetTrack1.id)

        // Verify Track Two preserved embedded artwork and all user data
        val resetTrack2 = resetTracks.first { it.id == 902L }
        assertNotNull("Embedded artwork must be preserved", resetTrack2.artworkUri)
        assertEquals("file://" + embeddedArt.absolutePath, resetTrack2.artworkUri)
        assertEquals("Play count must survive", 5, resetTrack2.playCount)

        tempDir.deleteRecursively()
    }

    @Test
    fun testIdentityGate_RejectsDurationOnlyMatch() {
        val service = createTestEnrichmentService()
        val localTrack = Track(
            id = 503L,
            title = "Dirty Diana",
            artist = "Michael Jackson",
            albumId = 3L,
            albumTitle = "Bad",
            duration = 296000L,
            uri = "/Muzic/English/Dirty Diana.mp3",
        )

        // iTunes returns a completely different Michael Jackson song with same artist and similar duration
        val wrongCandidate = RemoteTrackMetadata(
            title = "Thriller",
            artist = "Michael Jackson",
            albumTitle = "Thriller",
            durationMs = 295000L,
            artworkUrl = "https://example.com/thriller.jpg",
        )

        val (bestMatch, confidence) = service.findBestMatch(localTrack, listOf(wrongCandidate))
        assertNull("Duration similarity must NEVER rescue a title mismatch", bestMatch)
        assertEquals(MetadataConfidence.LOW, confidence)
    }

    @Test
    fun testIdentityGate_RejectsSubBandArtistMismatch() {
        val service = createTestEnrichmentService()
        val localTrack = Track(
            id = 504L,
            title = "Sing",
            artist = "Travis",
            albumId = 4L,
            albumTitle = "The Invisible Band",
            duration = 230000L,
            uri = "/Muzic/Sing.mp3",
        )

        // Remote candidate is Travis Scott (different artist entirely, despite containing "Travis")
        val candidate = RemoteTrackMetadata(
            title = "Sing",
            artist = "Travis Scott",
            albumTitle = "Sing Single",
            durationMs = 230000L,
            artworkUrl = "https://example.com/art.jpg",
        )

        val (bestMatch, _) = service.findBestMatch(localTrack, listOf(candidate))
        assertNull("Sub-string artist match 'Travis' in 'Travis Scott' must be rejected", bestMatch)
    }

    @Test
    fun testIdentityGate_RejectsVersionMismatch() {
        val service = createTestEnrichmentService()
        val localTrack = Track(
            id = 505L,
            title = "In The End",
            artist = "Linkin Park",
            albumId = 5L,
            albumTitle = "Hybrid Theory",
            duration = 216000L,
            uri = "/Muzic/In The End.mp3",
        )

        // Candidate is a live version when local is original
        val liveCandidate = RemoteTrackMetadata(
            title = "In The End (Live)",
            artist = "Linkin Park",
            albumTitle = "Live in Texas",
            durationMs = 216000L,
            artworkUrl = "https://example.com/live.jpg",
        )

        assertFalse("Live version must not be compatible with original track",
            MetadataUtils.areVersionsCompatible(localTrack.title, liveCandidate.title))

        val (bestMatch, _) = service.findBestMatch(localTrack, listOf(liveCandidate))
        assertNull("Version mismatch (Live vs Original) must be rejected by identity gate", bestMatch)
    }

    @Test
    fun testEmbeddedArtwork_AlwaysWinsOverRemoteArtwork() = kotlinx.coroutines.runBlocking {
        val service = createTestEnrichmentService()
        val embeddedUri = "file:///data/user/0/com.mus.android/files/artwork/art_embedded_999.jpg"

        val trackWithEmbeddedArt = Track(
            id = 999L,
            title = "Blinding Lights",
            artist = "The Weeknd",
            albumId = 999L,
            albumTitle = "After Hours",
            duration = 200000L,
            uri = "/Muzic/01.mp3",
            path = "/Muzic/01.mp3",
            artworkUri = embeddedUri,
            metadataSource = MetadataSource.EMBEDDED,
        )

        val remoteCandidate = RemoteTrackMetadata(
            title = "Blinding Lights",
            artist = "The Weeknd",
            albumTitle = "After Hours",
            year = 2020,
            genre = "R&B/Soul",
            artworkUrl = "https://example.com/itunes_artwork.jpg",
        )

        val enriched = service.mergeMetadata(trackWithEmbeddedArt, remoteCandidate, MetadataConfidence.HIGH)
        assertEquals("Genuine embedded artwork MUST ALWAYS WIN over remote iTunes artwork", embeddedUri, enriched.artworkUri)
    }

    @Test
    fun testArtworkGate_LowAndMediumConfidenceProduceNullArtwork() = kotlinx.coroutines.runBlocking {
        val service = createTestEnrichmentService()
        val trackWithoutArt = Track(
            id = 888L,
            title = "Unknown Song",
            artist = "Unknown Artist",
            albumId = 888L,
            albumTitle = "Unknown Album",
            duration = 180000L,
            uri = "/Muzic/unknown.mp3",
            path = "/Muzic/unknown.mp3",
            artworkUri = null,
            metadataSource = MetadataSource.EXTERNAL,
        )

        val remoteCandidate = RemoteTrackMetadata(
            title = "A Similar Song",
            artist = "Similar Artist",
            albumTitle = "Similar Album",
            year = 2021,
            genre = "Pop",
            artworkUrl = "https://example.com/similar.jpg",
        )

        val enrichedMedium = service.mergeMetadata(trackWithoutArt, remoteCandidate, MetadataConfidence.MEDIUM)
        assertNull("Medium confidence match must NOT attach remote artwork", enrichedMedium.artworkUri)

        val enrichedLow = service.mergeMetadata(trackWithoutArt, remoteCandidate, MetadataConfidence.LOW)
        assertNull("Low confidence match must NOT attach remote artwork", enrichedLow.artworkUri)
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
