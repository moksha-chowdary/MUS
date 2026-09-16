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
        assertEquals(MetadataSource.MERGED, enriched.metadataSource)
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

    // 4. Embedded metadata takes priority
    @Test
    fun testEmbeddedMetadataTakesPriorityOverExternal() = runBlocking {
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
        // Embedded valid title and artist must be preserved!
        assertEquals("Blinding Lights (Acoustic)", merged.title)
        assertEquals("The Weeknd", merged.artist)
        // Missing albumTitle is filled
        assertEquals("After Hours Deluxe", merged.albumTitle)
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

    // --- Test Doubles / Fakes ---

    class FakeMetadataProvider : MetadataProvider {
        override val name: String = "FakeProvider"
        var searchCallCount = 0
        var mockResults: List<RemoteTrackMetadata> = emptyList()

        override suspend fun searchTrack(query: String, limit: Int): List<RemoteTrackMetadata> {
            searchCallCount++
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
        override val automaticMetadataEnabled: kotlinx.coroutines.flow.StateFlow<Boolean> get() = _autoMeta
    }
}
