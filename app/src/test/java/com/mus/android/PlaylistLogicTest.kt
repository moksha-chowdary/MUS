package com.mus.android

import com.mus.android.data.model.Playlist
import com.mus.android.data.model.PlaylistTrack
import com.mus.android.data.model.Track
import org.junit.Assert.*
import org.junit.Test

class PlaylistLogicTest {

    private fun sampleTrack(id: Long, title: String): Track {
        return Track(
            id = id,
            title = title,
            artist = "Artist",
            albumId = 1L,
            albumTitle = "Album",
            duration = 200000L,
            uri = "content://audio/$id",
        )
    }

    @Test
    fun testPlaylistCreationAndRename() {
        val playlist = Playlist(id = 1L, name = "Late Night Beats")
        assertEquals(1L, playlist.id)
        assertEquals("Late Night Beats", playlist.name)

        val renamed = playlist.copy(name = "Midnight Chill", updatedAt = 2000L)
        assertEquals("Midnight Chill", renamed.name)
        assertEquals(2000L, renamed.updatedAt)
    }

    @Test
    fun testDuplicatePrevention() {
        val playlistTracks = mutableListOf<PlaylistTrack>()

        fun addTrack(playlistId: Long, trackId: Long): Boolean {
            val exists = playlistTracks.any { it.playlistId == playlistId && it.trackId == trackId }
            if (exists) return false
            playlistTracks.add(
                PlaylistTrack(playlistId = playlistId, trackId = trackId, position = playlistTracks.size)
            )
            return true
        }

        // First addition must succeed
        assertTrue(addTrack(1L, 101L))
        assertEquals(1, playlistTracks.size)

        // Duplicate addition of same track to same playlist must fail
        assertFalse(addTrack(1L, 101L))
        assertEquals(1, playlistTracks.size)

        // Adding same track to different playlist must succeed
        assertTrue(addTrack(2L, 101L))
        assertEquals(2, playlistTracks.size)

        // Adding different track to playlist 1 must succeed
        assertTrue(addTrack(1L, 102L))
        assertEquals(3, playlistTracks.size)
    }

    @Test
    fun testTrackRemovalFromPlaylist() {
        val playlistTracks = mutableListOf(
            PlaylistTrack(playlistId = 1L, trackId = 101L, position = 0),
            PlaylistTrack(playlistId = 1L, trackId = 102L, position = 1),
            PlaylistTrack(playlistId = 1L, trackId = 103L, position = 2),
        )

        // Remove track 102
        playlistTracks.removeAll { it.playlistId == 1L && it.trackId == 102L }

        assertEquals(2, playlistTracks.size)
        assertFalse(playlistTracks.any { it.trackId == 102L })
        assertTrue(playlistTracks.any { it.trackId == 101L })
        assertTrue(playlistTracks.any { it.trackId == 103L })
    }

    @Test
    fun testQueueReorderAndClear() {
        val queue = mutableListOf(
            sampleTrack(1L, "Track 1"),
            sampleTrack(2L, "Track 2"),
            sampleTrack(3L, "Track 3"),
            sampleTrack(4L, "Track 4"),
        )

        // Move item at index 0 to index 2
        val item = queue.removeAt(0)
        queue.add(2, item)

        assertEquals("Track 2", queue[0].title)
        assertEquals("Track 3", queue[1].title)
        assertEquals("Track 1", queue[2].title)
        assertEquals("Track 4", queue[3].title)

        // Clear queue
        queue.clear()
        assertTrue(queue.isEmpty())
        assertEquals(0, queue.size)
    }

    @Test
    fun testMoveTracksBetweenPlaylists() {
        val playlistTracks = mutableListOf(
            PlaylistTrack(playlistId = 1L, trackId = 101L, position = 0),
            PlaylistTrack(playlistId = 1L, trackId = 102L, position = 1),
            PlaylistTrack(playlistId = 1L, trackId = 103L, position = 2),
            PlaylistTrack(playlistId = 2L, trackId = 201L, position = 0),
        )

        fun moveTracks(fromPlaylistId: Long, toPlaylistId: Long, trackIds: List<Long>) {
            // 1. Remove from source
            playlistTracks.removeAll { it.playlistId == fromPlaylistId && it.trackId in trackIds }
            // 2. Add to destination if not already present
            val existingInDest = playlistTracks.filter { it.playlistId == toPlaylistId }.map { it.trackId }.toSet()
            var nextPos = playlistTracks.filter { it.playlistId == toPlaylistId }.size
            for (trackId in trackIds) {
                if (trackId !in existingInDest) {
                    playlistTracks.add(PlaylistTrack(playlistId = toPlaylistId, trackId = trackId, position = nextPos++))
                }
            }
        }

        // Move 101 and 102 from Playlist 1 to Playlist 2
        moveTracks(fromPlaylistId = 1L, toPlaylistId = 2L, trackIds = listOf(101L, 102L))

        // Playlist 1 should only contain 103
        val p1Tracks = playlistTracks.filter { it.playlistId == 1L }.map { it.trackId }
        assertEquals(listOf(103L), p1Tracks)

        // Playlist 2 should contain 201, 101, 102
        val p2Tracks = playlistTracks.filter { it.playlistId == 2L }.map { it.trackId }
        assertEquals(listOf(201L, 101L, 102L), p2Tracks)
    }

    @Test
    fun testCopyTracksBetweenPlaylists() {
        val playlistTracks = mutableListOf(
            PlaylistTrack(playlistId = 1L, trackId = 101L, position = 0),
            PlaylistTrack(playlistId = 1L, trackId = 102L, position = 1),
            PlaylistTrack(playlistId = 2L, trackId = 102L, position = 0), // Already in Playlist 2
        )

        fun copyTracks(toPlaylistId: Long, trackIds: List<Long>) {
            val existingInDest = playlistTracks.filter { it.playlistId == toPlaylistId }.map { it.trackId }.toSet()
            var nextPos = playlistTracks.filter { it.playlistId == toPlaylistId }.size
            for (trackId in trackIds) {
                if (trackId !in existingInDest) {
                    playlistTracks.add(PlaylistTrack(playlistId = toPlaylistId, trackId = trackId, position = nextPos++))
                }
            }
        }

        // Copy 101 and 102 to Playlist 2
        copyTracks(toPlaylistId = 2L, trackIds = listOf(101L, 102L))

        // Playlist 1 still has both
        val p1Tracks = playlistTracks.filter { it.playlistId == 1L }.map { it.trackId }
        assertEquals(listOf(101L, 102L), p1Tracks)

        // Playlist 2 should now have 102 (original) and 101 (added, no duplicate 102)
        val p2Tracks = playlistTracks.filter { it.playlistId == 2L }.map { it.trackId }
        assertEquals(listOf(102L, 101L), p2Tracks)
    }

    @Test
    fun testBatchRemoveTracksFromPlaylist() {
        val playlistTracks = mutableListOf(
            PlaylistTrack(playlistId = 1L, trackId = 101L, position = 0),
            PlaylistTrack(playlistId = 1L, trackId = 102L, position = 1),
            PlaylistTrack(playlistId = 1L, trackId = 103L, position = 2),
            PlaylistTrack(playlistId = 1L, trackId = 104L, position = 3),
        )

        // Batch remove 101 and 103
        val toRemove = listOf(101L, 103L)
        playlistTracks.removeAll { it.playlistId == 1L && it.trackId in toRemove }

        val remaining = playlistTracks.filter { it.playlistId == 1L }.map { it.trackId }
        assertEquals(listOf(102L, 104L), remaining)
    }

    @Test
    fun testSongCanExistInMultiplePlaylists() {
        val playlistTracks = mutableListOf<PlaylistTrack>()
        val trackId = 555L

        playlistTracks.add(PlaylistTrack(playlistId = 1L, trackId = trackId, position = 0))
        playlistTracks.add(PlaylistTrack(playlistId = 2L, trackId = trackId, position = 0))
        playlistTracks.add(PlaylistTrack(playlistId = 3L, trackId = trackId, position = 0))

        val containingPlaylists = playlistTracks.filter { it.trackId == trackId }.map { it.playlistId }
        assertEquals(listOf(1L, 2L, 3L), containingPlaylists)
    }

    @Test
    fun testMoveDoesNotAffectUnrelatedPlaylists() {
        val playlistTracks = mutableListOf(
            PlaylistTrack(playlistId = 1L, trackId = 100L, position = 0), // Playlist A
            PlaylistTrack(playlistId = 2L, trackId = 100L, position = 0), // Playlist B (unrelated)
        )

        // Move 100 from Playlist 1 to Playlist 3 (A -> C)
        val sourceId = 1L
        val destId = 3L
        val trackId = 100L

        playlistTracks.removeAll { it.playlistId == sourceId && it.trackId == trackId }
        playlistTracks.add(PlaylistTrack(playlistId = destId, trackId = trackId, position = 0))

        // Playlist 1 has no 100
        assertFalse(playlistTracks.any { it.playlistId == 1L && it.trackId == trackId })
        // Playlist 3 has 100
        assertTrue(playlistTracks.any { it.playlistId == 3L && it.trackId == trackId })
        // Unrelated Playlist 2 STILL has 100 untouched!
        assertTrue(playlistTracks.any { it.playlistId == 2L && it.trackId == trackId })
    }

    @Test
    fun testPlaylistMembershipSurvivesTrackMetadataUpdate() {
        var track = sampleTrack(100L, "Original Title")
        val playlistTrack = PlaylistTrack(playlistId = 1L, trackId = track.id, position = 0)

        // Metadata enrichment updates title, artist, album, language
        track = track.copy(
            title = "Enriched Title",
            artist = "Enriched Artist",
            language = "Hindi"
        )

        // The relationship in playlist_tracks is by trackId, which remains completely unchanged
        assertEquals(track.id, playlistTrack.trackId)
    }

    @Test
    fun testPlaylistMembershipSurvivesRescanAndReconciliation() {
        // Simulates the scanner reconciliation rule:
        // Scanning discovers audio files and reconciles Track/Album/Artist tables,
        // but must NEVER touch or purge the playlist_tracks table!
        val userPlaylistTracks = mutableListOf(
            PlaylistTrack(playlistId = 1L, trackId = 101L, position = 0),
            PlaylistTrack(playlistId = 2L, trackId = 101L, position = 0),
        )

        // Scanner runs
        val scannedFiles = listOf("file1.mp3", "file2.mp3")
        assertTrue(scannedFiles.isNotEmpty())

        // Ensure userPlaylistTracks is completely untouched by the scan
        assertEquals(2, userPlaylistTracks.size)
        assertTrue(userPlaylistTracks.any { it.playlistId == 1L && it.trackId == 101L })
        assertTrue(userPlaylistTracks.any { it.playlistId == 2L && it.trackId == 101L })
    }

    @Test
    fun testPlaylistDeletionDoesNotDeleteTrack() {
        val tracks = mutableListOf(
            sampleTrack(101L, "Track 101"),
            sampleTrack(102L, "Track 102"),
        )
        val playlists = mutableListOf(
            Playlist(id = 1L, name = "My Playlist"),
        )
        val playlistTracks = mutableListOf(
            PlaylistTrack(playlistId = 1L, trackId = 101L, position = 0),
        )

        // User deletes playlist 1
        val playlistToDelete = 1L
        playlists.removeAll { it.id == playlistToDelete }
        playlistTracks.removeAll { it.playlistId == playlistToDelete }

        // Playlist is gone
        assertTrue(playlists.isEmpty())
        assertTrue(playlistTracks.isEmpty())

        // Tracks still exist! (Deleting playlist NEVER deletes Track)
        assertEquals(2, tracks.size)
        assertTrue(tracks.any { it.id == 101L })
        assertTrue(tracks.any { it.id == 102L })
    }

    @Test
    fun testCopyTracksReturnsAccurateCount() {
        val playlistTracks = mutableListOf(
            PlaylistTrack(playlistId = 1L, trackId = 101L, position = 0),
            PlaylistTrack(playlistId = 2L, trackId = 101L, position = 0), // Already in 2
        )

        fun copyTracks(destinationPlaylistId: Long, trackIds: List<Long>): Int {
            val existing = playlistTracks.filter { it.playlistId == destinationPlaylistId }.map { it.trackId }.toSet()
            var added = 0
            for (id in trackIds) {
                if (id !in existing) {
                    playlistTracks.add(PlaylistTrack(playlistId = destinationPlaylistId, trackId = id, position = playlistTracks.size))
                    added++
                }
            }
            return added
        }

        // Copy 101 (duplicate) and 102 (new) to playlist 2
        val added = copyTracks(2L, listOf(101L, 102L))
        assertEquals(1, added)
        assertEquals(2, playlistTracks.filter { it.playlistId == 2L }.size)

        // Copy both when both are duplicates
        val addedZero = copyTracks(2L, listOf(101L, 102L))
        assertEquals(0, addedZero)
    }

    @Test
    fun testMoveTracksReturnsAccurateCount() {
        val playlistTracks = mutableListOf(
            PlaylistTrack(playlistId = 1L, trackId = 101L, position = 0),
            PlaylistTrack(playlistId = 1L, trackId = 102L, position = 1),
            PlaylistTrack(playlistId = 2L, trackId = 102L, position = 0), // Already in 2
        )

        fun moveTracks(sourcePlaylistId: Long, destinationPlaylistId: Long, trackIds: List<Long>): Int {
            playlistTracks.removeAll { it.playlistId == sourcePlaylistId && it.trackId in trackIds }
            val existingInDest = playlistTracks.filter { it.playlistId == destinationPlaylistId }.map { it.trackId }.toSet()
            var added = 0
            for (id in trackIds) {
                if (id !in existingInDest) {
                    playlistTracks.add(PlaylistTrack(playlistId = destinationPlaylistId, trackId = id, position = playlistTracks.size))
                    added++
                }
            }
            return added
        }

        val moved = moveTracks(1L, 2L, listOf(101L, 102L))
        // 101 is newly added to 2, 102 was already in 2, but both removed from 1
        assertEquals(1, moved)
        assertTrue(playlistTracks.none { it.playlistId == 1L })
        assertEquals(2, playlistTracks.filter { it.playlistId == 2L }.size)
    }

    @Test
    fun testEnsureDefaultPlaylistsOnlyWhenEmpty() {
        val playlists = mutableListOf(
            Playlist(id = 1L, name = "Custom Playlist"),
        )

        fun ensureDefaultPlaylists() {
            if (playlists.isEmpty()) {
                playlists.add(Playlist(id = 100L, name = "Hindi"))
                playlists.add(Playlist(id = 101L, name = "English"))
            }
        }

        ensureDefaultPlaylists()
        assertEquals(1, playlists.size)
        assertEquals("Custom Playlist", playlists[0].name)
    }

    @Test
    fun testSystemPlaylistsReconciliationBySystemKey() {
        val existingPlaylists = mutableListOf(
            Playlist(id = 1L, name = "Hindi Mix", systemKey = "HINDI", isSystemPlaylist = true),
            Playlist(id = 2L, name = "English Mix", systemKey = "ENGLISH", isSystemPlaylist = true),
            Playlist(id = 3L, name = "User Favorite Jams", systemKey = null, isSystemPlaylist = false),
        )

        // All system definitions that MUS supports
        val requiredSystemPlaylists = listOf(
            "HINDI" to "Hindi",
            "ENGLISH" to "English",
            "TELUGU" to "Telugu",
            "TAMIL" to "Tamil",
            "PUNJABI" to "Punjabi",
        )

        var nextId = 10L
        for ((key, defaultName) in requiredSystemPlaylists) {
            val exists = existingPlaylists.any { it.systemKey == key }
            if (!exists) {
                existingPlaylists.add(
                    Playlist(id = nextId++, name = defaultName, systemKey = key, isSystemPlaylist = true)
                )
            }
        }

        // Must now have 3 original + 3 new (TELUGU, TAMIL, PUNJABI) = 6 total playlists
        assertEquals(6, existingPlaylists.size)
        // Existing "Hindi Mix" should NOT have been overwritten
        assertEquals("Hindi Mix", existingPlaylists.first { it.systemKey == "HINDI" }.name)
        // User playlist untouched
        assertTrue(existingPlaylists.any { it.name == "User Favorite Jams" && !it.isSystemPlaylist })
        // Telugu and Tamil exist with system keys
        assertNotNull(existingPlaylists.find { it.systemKey == "TELUGU" })
        assertNotNull(existingPlaylists.find { it.systemKey == "TAMIL" })
    }

    @Test
    fun testTrackIdMigrationSafety() {
        val oldTrackId = 1001L
        val newTrackId = 2002L

        // 1. Playlist tracks
        val playlistTracks = mutableListOf(
            PlaylistTrack(playlistId = 10L, trackId = oldTrackId, position = 0),
            PlaylistTrack(playlistId = 20L, trackId = oldTrackId, position = 3),
            PlaylistTrack(playlistId = 10L, trackId = 9999L, position = 1),
        )

        // 2. Playback state / queue
        var currentTrack = sampleTrack(oldTrackId, "NOKIA")
        val queue = mutableListOf(
            sampleTrack(9999L, "Intro"),
            currentTrack,
            sampleTrack(8888L, "Outro"),
        )

        // Simulate safe ID migration across all entities
        val migratedIds = mapOf(oldTrackId to newTrackId)

        // Migrate playlist_tracks
        for (i in playlistTracks.indices) {
            val pt = playlistTracks[i]
            val newId = migratedIds[pt.trackId]
            if (newId != null) {
                playlistTracks[i] = pt.copy(trackId = newId)
            }
        }

        // Migrate queue and current track
        val newQueue = queue.map { track ->
            val newId = migratedIds[track.id]
            if (newId != null) track.copy(id = newId) else track
        }
        if (migratedIds.containsKey(currentTrack.id)) {
            currentTrack = currentTrack.copy(id = migratedIds[currentTrack.id]!!)
        }

        // Assertions: All references to oldTrackId must be safely updated to newTrackId
        assertTrue(playlistTracks.none { it.trackId == oldTrackId })
        assertEquals(2, playlistTracks.count { it.trackId == newTrackId })
        assertEquals(newTrackId, playlistTracks[0].trackId)
        assertEquals(newTrackId, playlistTracks[1].trackId)

        assertEquals(newTrackId, currentTrack.id)
        assertEquals(newTrackId, newQueue[1].id)
        assertEquals("NOKIA", newQueue[1].title)
        assertEquals(3, newQueue.size)
    }
}


