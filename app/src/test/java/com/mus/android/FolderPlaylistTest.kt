package com.mus.android

import com.mus.android.data.model.Album
import com.mus.android.data.model.MetadataConfidence
import com.mus.android.data.model.MetadataSource
import com.mus.android.data.model.MetadataStatus
import com.mus.android.data.model.Playlist
import com.mus.android.data.model.PlaylistTrack
import com.mus.android.data.model.Track
import com.mus.android.data.scanner.MetadataUtils
import org.junit.Assert.*
import org.junit.Test

class FolderPlaylistTest {

    private fun createTrack(
        id: Long,
        title: String,
        path: String,
        albumTitle: String = "Test Album",
        albumId: Long = 1L,
        trackNumber: Int = 0,
        metadataStatus: String = MetadataStatus.COMPLETE,
        metadataSource: String = MetadataSource.EXTERNAL
    ): Track {
        return Track(
            id = id,
            title = title,
            artist = "The Weeknd",
            albumId = albumId,
            albumTitle = albumTitle,
            duration = 200000L,
            trackNumber = trackNumber,
            uri = path,
            path = path,
            metadataStatus = metadataStatus,
            metadataSource = metadataSource,
            metadataConfidence = MetadataConfidence.HIGH
        )
    }

    // ── Test 1: Folder Extraction Logic ─────────────────────────

    @Test
    fun testExtractMuzicDirectFolder_standardPaths() {
        assertEquals("English", MetadataUtils.extractMuzicDirectFolder("/storage/emulated/0/Muzic/English/song1.flac"))
        assertEquals("Telugu", MetadataUtils.extractMuzicDirectFolder("/storage/emulated/0/Muzic/Telugu/song2.flac"))
        assertEquals("Hindi", MetadataUtils.extractMuzicDirectFolder("/storage/emulated/0/Muzic/Hindi/song3.flac"))
        assertEquals("Tamil", MetadataUtils.extractMuzicDirectFolder("/storage/emulated/0/Muzic/Tamil/song4.flac"))
        assertEquals("Workout", MetadataUtils.extractMuzicDirectFolder("/storage/emulated/0/Muzic/Workout/song5.flac"))
        assertEquals("Favorites", MetadataUtils.extractMuzicDirectFolder("/storage/emulated/0/Muzic/Favorites/song6.flac"))
        assertEquals("Chill", MetadataUtils.extractMuzicDirectFolder("/storage/emulated/0/Muzic/Chill/song7.flac"))
    }

    @Test
    fun testExtractMuzicDirectFolder_nestedFoldersCollapseToTopLevel() {
        // Nested subdirectories must belong to the direct top-level child of Muzic
        assertEquals("English", MetadataUtils.extractMuzicDirectFolder("/storage/emulated/0/Muzic/English/Pop/song.flac"))
        assertEquals("English", MetadataUtils.extractMuzicDirectFolder("/storage/emulated/0/Muzic/English/Rock/Sub/song2.flac"))
        assertEquals("Telugu", MetadataUtils.extractMuzicDirectFolder("/storage/emulated/0/Muzic/Telugu/Folk/Classical/track.mp3"))
    }

    @Test
    fun testExtractMuzicDirectFolder_rootSongsReturnNull() {
        // Files directly in the root of Muzic/ must NOT produce a playlist
        assertNull(MetadataUtils.extractMuzicDirectFolder("/storage/emulated/0/Muzic/random_song.flac"))
        assertNull(MetadataUtils.extractMuzicDirectFolder("/storage/emulated/0/Muzic/root_track.mp3"))
        assertNull(MetadataUtils.extractMuzicDirectFolder("content://media/external/audio/media/101"))
    }

    @Test
    fun testExtractMuzicDirectFolder_safTreeUris() {
        val safUri = "content://com.android.externalstorage.documents/tree/primary%3AMuzic/document/primary%3AMuzic%2FEnglish%2Fsong1.flac"
        assertEquals("English", MetadataUtils.extractMuzicDirectFolder(safUri))

        val safNestedUri = "content://com.android.externalstorage.documents/tree/primary%3AMuzic/document/primary%3AMuzic%2FTelugu%2FMass%2Fsong2.flac"
        assertEquals("Telugu", MetadataUtils.extractMuzicDirectFolder(safNestedUri))

        val safRootUri = "content://com.android.externalstorage.documents/tree/primary%3AMuzic/document/primary%3AMuzic%2Frandom_song.flac"
        assertNull(MetadataUtils.extractMuzicDirectFolder(safRootUri))
    }

    @Test
    fun testExtractMuzicDirectFolder_windowsBackslashes() {
        assertEquals("English", MetadataUtils.extractMuzicDirectFolder("""C:\Users\User\Muzic\English\song1.flac"""))
        assertEquals("Telugu", MetadataUtils.extractMuzicDirectFolder("""D:\Muzic\Telugu\Subfolder\song2.flac"""))
        assertNull(MetadataUtils.extractMuzicDirectFolder("""C:\Users\User\Muzic\random_root_song.flac"""))
    }

    // ── Test 2: In-Memory Playlist Reconciliation Simulation ────

    private class TestPlaylistStore {
        val playlists = mutableListOf<Playlist>()
        val playlistTracks = mutableListOf<PlaylistTrack>()
        var nextId = 1L

        fun reconcile(tracks: List<Track>, discoveredFolders: Set<String>) {
            val allFolders = discoveredFolders.toMutableSet()
            for (track in tracks) {
                val folder = MetadataUtils.extractMuzicDirectFolder(track.path ?: track.uri)
                if (folder != null) allFolders.add(folder)
            }

            val validFoldersLower = allFolders.map { it.lowercase() }.toSet()

            // 1. Prune stale playlists and remove duplicates
            val byName = playlists.groupBy { it.name.lowercase() }
            val active = mutableMapOf<String, Playlist>()
            for ((nameLower, list) in byName) {
                if (nameLower !in validFoldersLower) {
                    for (pl in list) {
                        playlistTracks.removeAll { it.playlistId == pl.id }
                        playlists.removeAll { it.id == pl.id }
                    }
                } else {
                    active[nameLower] = list.first()
                    if (list.size > 1) {
                        for (pl in list.drop(1)) {
                            playlistTracks.removeAll { it.playlistId == pl.id }
                            playlists.removeAll { it.id == pl.id }
                        }
                    }
                }
            }

            // 2. Ensure each folder has a playlist
            val folderPlaylists = mutableMapOf<String, Playlist>()
            for (folder in allFolders) {
                val existing = active[folder.lowercase()]
                val pl = if (existing != null) {
                    val updated = existing.copy(name = folder, isSystemPlaylist = false, systemKey = null)
                    val idx = playlists.indexOfFirst { it.id == existing.id }
                    if (idx >= 0) playlists[idx] = updated
                    updated
                } else {
                    val created = Playlist(id = nextId++, name = folder)
                    playlists.add(created)
                    created
                }
                folderPlaylists[folder] = pl
            }

            // 3. Reconcile membership
            val tracksByFolder = mutableMapOf<String, MutableList<Track>>()
            for (folder in allFolders) {
                tracksByFolder[folder] = mutableListOf()
            }
            for (track in tracks) {
                val folder = MetadataUtils.extractMuzicDirectFolder(track.path ?: track.uri)
                if (folder != null) {
                    tracksByFolder.getOrPut(folder) { mutableListOf() }.add(track)
                }
            }

            for ((folder, fTracks) in tracksByFolder) {
                val pl = folderPlaylists[folder] ?: continue
                val sorted = fTracks.sortedWith(
                    compareBy<Track> { if (it.trackNumber > 0) it.trackNumber else Int.MAX_VALUE }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
                        .thenBy { it.id }
                )
                val expectedIds = sorted.map { it.id }
                val currentIds = playlistTracks.filter { it.playlistId == pl.id }.map { it.trackId }

                if (currentIds != expectedIds) {
                    playlistTracks.removeAll { it.playlistId == pl.id }
                    var pos = 0
                    for (t in sorted) {
                        playlistTracks.add(PlaylistTrack(playlistId = pl.id, trackId = t.id, position = pos++))
                    }
                }
            }
        }
    }

    @Test
    fun testReconcile_initialFolders() {
        val store = TestPlaylistStore()
        val tracks = listOf(
            createTrack(1L, "Song 1", "/storage/emulated/0/Muzic/English/song1.flac"),
            createTrack(2L, "Song 2", "/storage/emulated/0/Muzic/Telugu/song2.flac"),
            createTrack(3L, "Song 3", "/storage/emulated/0/Muzic/Hindi/song3.flac"),
        )
        val folders = setOf("English", "Telugu", "Hindi")

        store.reconcile(tracks, folders)

        assertEquals(3, store.playlists.size)
        val names = store.playlists.map { it.name }.toSet()
        assertEquals(setOf("English", "Telugu", "Hindi"), names)

        val englishPl = store.playlists.first { it.name == "English" }
        val englishTracks = store.playlistTracks.filter { it.playlistId == englishPl.id }
        assertEquals(1, englishTracks.size)
        assertEquals(1L, englishTracks.first().trackId)
    }

    @Test
    fun testReconcile_albumsRemainMetadataBased() {
        // Songs inside English/ belong to different albums: "After Hours" and "The Highlights"
        val store = TestPlaylistStore()
        val tracks = listOf(
            createTrack(1L, "Blinding Lights", "/storage/emulated/0/Muzic/English/Blinding Lights.flac", albumTitle = "After Hours", albumId = 10L),
            createTrack(2L, "Save Your Tears", "/storage/emulated/0/Muzic/English/Save Your Tears.flac", albumTitle = "The Highlights", albumId = 20L),
        )

        store.reconcile(tracks, setOf("English"))

        // Playlist is English
        assertEquals(1, store.playlists.size)
        assertEquals("English", store.playlists.first().name)
        val plTracks = store.playlistTracks.filter { it.playlistId == store.playlists.first().id }
        assertEquals(2, plTracks.size)

        // Albums remain separated by metadata
        val albumGrouping = tracks.groupBy { it.albumTitle }
        assertEquals(2, albumGrouping.size)
        assertTrue(albumGrouping.containsKey("After Hours"))
        assertTrue(albumGrouping.containsKey("The Highlights"))
    }

    @Test
    fun testReconcile_addFolder() {
        val store = TestPlaylistStore()
        val initialTracks = listOf(
            createTrack(1L, "Song 1", "/storage/emulated/0/Muzic/English/song1.flac"),
        )
        store.reconcile(initialTracks, setOf("English"))
        assertEquals(1, store.playlists.size)

        // Add Tamil folder and songs
        val updatedTracks = listOf(
            createTrack(1L, "Song 1", "/storage/emulated/0/Muzic/English/song1.flac"),
            createTrack(2L, "Song 2", "/storage/emulated/0/Muzic/Tamil/song2.flac"),
        )
        store.reconcile(updatedTracks, setOf("English", "Tamil"))

        assertEquals(2, store.playlists.size)
        assertTrue(store.playlists.any { it.name == "Tamil" })
        assertTrue(store.playlists.any { it.name == "English" })
    }

    @Test
    fun testReconcile_deleteFolderSafelyPreservesTracks() {
        val store = TestPlaylistStore()
        val tracks = listOf(
            createTrack(1L, "Song 1", "/storage/emulated/0/Muzic/English/song1.flac"),
            createTrack(2L, "Song 2", "/storage/emulated/0/Muzic/Tamil/song2.flac"),
        )
        store.reconcile(tracks, setOf("English", "Tamil"))
        assertEquals(2, store.playlists.size)

        // Tamil folder deleted on disk
        val remainingTracks = listOf(
            createTrack(1L, "Song 1", "/storage/emulated/0/Muzic/English/song1.flac"),
        )
        store.reconcile(remainingTracks, setOf("English"))

        // Tamil playlist removed
        assertEquals(1, store.playlists.size)
        assertEquals("English", store.playlists.first().name)
        assertFalse(store.playlists.any { it.name == "Tamil" })

        // Playlist tracks for Tamil are cleaned up
        assertEquals(1, store.playlistTracks.size)
        assertEquals(1L, store.playlistTracks.first().trackId)
    }

    @Test
    fun testReconcile_moveSongBetweenFolders() {
        val store = TestPlaylistStore()
        val song1Initial = createTrack(1L, "Song", "/storage/emulated/0/Muzic/English/song.flac")
        store.reconcile(listOf(song1Initial), setOf("English", "Telugu"))

        val englishPl = store.playlists.first { it.name == "English" }
        val teluguPl = store.playlists.first { it.name == "Telugu" }

        assertTrue(store.playlistTracks.any { it.playlistId == englishPl.id && it.trackId == 1L })
        assertFalse(store.playlistTracks.any { it.playlistId == teluguPl.id && it.trackId == 1L })

        // User moves song to Telugu folder
        val song1Moved = createTrack(1L, "Song", "/storage/emulated/0/Muzic/Telugu/song.flac")
        store.reconcile(listOf(song1Moved), setOf("English", "Telugu"))

        // English must NO LONGER contain the song, Telugu must contain it
        assertFalse(store.playlistTracks.any { it.playlistId == englishPl.id && it.trackId == 1L })
        assertTrue(store.playlistTracks.any { it.playlistId == teluguPl.id && it.trackId == 1L })
        // Exactly one occurrence across all playlists
        assertEquals(1, store.playlistTracks.filter { it.trackId == 1L }.size)
    }

    @Test
    fun testReconcile_repeatedRescansProduceNoDuplicates() {
        val store = TestPlaylistStore()
        val tracks = listOf(
            createTrack(1L, "Song 1", "/storage/emulated/0/Muzic/English/song1.flac"),
            createTrack(2L, "Song 2", "/storage/emulated/0/Muzic/Telugu/song2.flac"),
            createTrack(3L, "Song 3", "/storage/emulated/0/Muzic/Hindi/song3.flac"),
        )
        val folders = setOf("English", "Telugu", "Hindi")

        // Rescan 5 times
        repeat(5) {
            store.reconcile(tracks, folders)
        }

        assertEquals(3, store.playlists.size)
        assertEquals(3, store.playlistTracks.size)
        assertEquals(setOf("English", "Telugu", "Hindi"), store.playlists.map { it.name }.toSet())
    }

    @Test
    fun testReconcile_replacesOldPlaylists() {
        val store = TestPlaylistStore()
        // Inject legacy playlists: "Regional", "Other (Regional & Global)"
        store.playlists.add(Playlist(id = 100L, name = "Regional", isSystemPlaylist = true))
        store.playlists.add(Playlist(id = 101L, name = "Other (Regional & Global)", isSystemPlaylist = true))
        store.playlistTracks.add(PlaylistTrack(playlistId = 100L, trackId = 1L, position = 0))

        val tracks = listOf(
            createTrack(1L, "Song 1", "/storage/emulated/0/Muzic/English/song1.flac"),
            createTrack(2L, "Song 2", "/storage/emulated/0/Muzic/Telugu/song2.flac"),
        )
        store.reconcile(tracks, setOf("English", "Telugu"))

        // Old legacy playlists must be completely pruned
        assertFalse(store.playlists.any { it.name == "Regional" })
        assertFalse(store.playlists.any { it.name == "Other (Regional & Global)" })
        assertEquals(2, store.playlists.size)
        assertEquals(setOf("English", "Telugu"), store.playlists.map { it.name }.toSet())
    }

    @Test
    fun testReconcile_deterministicOrdering() {
        val store = TestPlaylistStore()
        val tracks = listOf(
            createTrack(3L, "Zebra Song", "/storage/emulated/0/Muzic/English/z.flac", trackNumber = 3),
            createTrack(1L, "Alpha Song", "/storage/emulated/0/Muzic/English/a.flac", trackNumber = 1),
            createTrack(2L, "Beta Song", "/storage/emulated/0/Muzic/English/b.flac", trackNumber = 2),
        )
        store.reconcile(tracks, setOf("English"))

        val englishPl = store.playlists.first { it.name == "English" }
        val orderedTrackIds = store.playlistTracks
            .filter { it.playlistId == englishPl.id }
            .sortedBy { it.position }
            .map { it.trackId }

        // Must be sorted by trackNumber: 1L, 2L, 3L
        assertEquals(listOf(1L, 2L, 3L), orderedTrackIds)
    }

    @Test
    fun testReconcile_enrichedMetadataRemainsIntact() {
        val enrichedSong = createTrack(
            id = 42L,
            title = "Blinding Lights",
            path = "/storage/emulated/0/Muzic/English/Blinding Lights.flac",
            albumTitle = "After Hours",
            metadataStatus = MetadataStatus.COMPLETE,
            metadataSource = MetadataSource.EXTERNAL
        )

        val store = TestPlaylistStore()
        store.reconcile(listOf(enrichedSong), setOf("English"))

        // Verify track metadata fields were untouched
        assertEquals(MetadataStatus.COMPLETE, enrichedSong.metadataStatus)
        assertEquals(MetadataSource.EXTERNAL, enrichedSong.metadataSource)
        assertEquals("After Hours", enrichedSong.albumTitle)
    }
}
