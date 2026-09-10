package com.mus.android

import com.mus.android.data.model.Album
import com.mus.android.data.model.Artist
import com.mus.android.data.model.Track
import com.mus.android.data.scanner.MetadataUtils
import org.junit.Assert.*
import org.junit.Test

class MetadataPipelineTest {

    @Test
    fun testMetadataFallbackResolution() {
        // 1. Missing artist -> Unknown Artist
        val res1 = MetadataUtils.resolveMetadata(
            rawTitle = null,
            rawArtist = null,
            rawAlbumArtist = null,
            rawAlbum = null,
            fileNameFallback = "test_song.flac"
        )
        assertEquals("test_song", res1.title)
        assertEquals("Unknown Artist", res1.artist)
        assertEquals("Unknown Artist", res1.albumArtist)
        assertEquals("Unknown Album", res1.album)

        // 2. Missing album artist -> falls back to artist
        val res2 = MetadataUtils.resolveMetadata(
            rawTitle = "Blinding Lights",
            rawArtist = "The Weeknd",
            rawAlbumArtist = null,
            rawAlbum = "After Hours",
            fileNameFallback = "01.mp3"
        )
        assertEquals("Blinding Lights", res2.title)
        assertEquals("The Weeknd", res2.artist)
        assertEquals("The Weeknd", res2.albumArtist)
        assertEquals("After Hours", res2.album)

        // 3. Distinct artist and album artist (featured track)
        val res3 = MetadataUtils.resolveMetadata(
            rawTitle = "Lost in the Fire",
            rawArtist = "Gesaffelstein & The Weeknd",
            rawAlbumArtist = "Gesaffelstein",
            rawAlbum = "Hyperion",
            fileNameFallback = "02.mp3"
        )
        assertEquals("Lost in the Fire", res3.title)
        assertEquals("Gesaffelstein & The Weeknd", res3.artist)
        assertEquals("Gesaffelstein", res3.albumArtist)
        assertEquals("Hyperion", res3.album)

        // 4. <unknown> placeholder tags
        val res4 = MetadataUtils.resolveMetadata(
            rawTitle = "<unknown>",
            rawArtist = "<unknown>",
            rawAlbumArtist = "<unknown>",
            rawAlbum = "<unknown>",
            fileNameFallback = "Starboy.wav"
        )
        assertEquals("Starboy", res4.title)
        assertEquals("Unknown Artist", res4.artist)
        assertEquals("Unknown Artist", res4.albumArtist)
        assertEquals("Unknown Album", res4.album)
    }

    @Test
    fun testDeterministicIdsAreStableAndCollisionResistant() {
        val albumId1 = MetadataUtils.generateAlbumId("The Weeknd", "After Hours")
        val albumId2 = MetadataUtils.generateAlbumId("  the weeknd  ", "AFTER HOURS")
        assertEquals(albumId1, albumId2)
        assertTrue(albumId1 > 0)

        val albumId3 = MetadataUtils.generateAlbumId("Gesaffelstein", "Hyperion")
        assertNotEquals(albumId1, albumId3)

        val artistId1 = MetadataUtils.generateArtistId("The Weeknd")
        val artistId2 = MetadataUtils.generateArtistId("the weeknd")
        assertEquals(artistId1, artistId2)
        assertTrue(artistId1 > 0)

        val trackId1 = MetadataUtils.generateTrackId("content://media/external/audio/media/101")
        val trackId2 = MetadataUtils.generateTrackId("content://media/external/audio/media/101")
        assertEquals(trackId1, trackId2)
        assertTrue(trackId1 > 0)
    }

    @Test
    fun testVirtualOrganizationRegardlessOfPhysicalFolders() {
        // Physical layout:
        // /Muzic/random/a.flac
        // /Muzic/downloads/b.m4a
        // /Muzic/whatever/c.mp3
        val fileA = createTestTrack(
            path = "/storage/emulated/0/Muzic/random/a.flac",
            title = "Alone Again",
            artist = "The Weeknd",
            albumArtist = "The Weeknd",
            album = "After Hours",
            trackNum = 1
        )
        val fileB = createTestTrack(
            path = "/storage/emulated/0/Muzic/downloads/b.m4a",
            title = "Too Late",
            artist = "The Weeknd",
            albumArtist = "The Weeknd",
            album = "After Hours",
            trackNum = 2
        )
        val fileC = createTestTrack(
            path = "/storage/emulated/0/Muzic/whatever/c.mp3",
            title = "Hyperion",
            artist = "Gesaffelstein",
            albumArtist = "Gesaffelstein",
            album = "Hyperion",
            trackNum = 1
        )

        val tracks = listOf(fileA, fileB, fileC)

        // Group by Album
        val albumsMap = mutableMapOf<Long, Album>()
        for (t in tracks) {
            val albumId = t.albumId
            val existing = albumsMap[albumId]
            if (existing == null) {
                albumsMap[albumId] = Album(
                    id = albumId,
                    title = t.albumTitle,
                    artist = t.albumArtist,
                    trackCount = 1,
                    totalDuration = t.duration
                )
            } else {
                albumsMap[albumId] = existing.copy(
                    trackCount = existing.trackCount + 1,
                    totalDuration = existing.totalDuration + t.duration
                )
            }
        }

        // Verify albums
        assertEquals(2, albumsMap.size)
        val afterHours = albumsMap.values.find { it.title == "After Hours" }
        assertNotNull(afterHours)
        assertEquals("The Weeknd", afterHours!!.artist)
        assertEquals(2, afterHours.trackCount)

        val hyperion = albumsMap.values.find { it.title == "Hyperion" }
        assertNotNull(hyperion)
        assertEquals("Gesaffelstein", hyperion!!.artist)
        assertEquals(1, hyperion.trackCount)

        // Verify tracks for After Hours
        val afterHoursTracks = tracks.filter { it.albumId == afterHours.id }.sortedBy { it.trackNumber }
        assertEquals(2, afterHoursTracks.size)
        assertEquals("Alone Again", afterHoursTracks[0].title)
        assertEquals("Too Late", afterHoursTracks[1].title)
    }

    @Test
    fun testAlbumArtistVsTrackArtistGrouping() {
        // Track where track artist is a collaboration, but album artist is the primary artist
        val track = createTestTrack(
            path = "/storage/emulated/0/Muzic/collab.mp3",
            title = "Lost in the Fire",
            artist = "Gesaffelstein & The Weeknd",
            albumArtist = "Gesaffelstein",
            album = "Hyperion",
            trackNum = 4
        )

        // Album must belong to Gesaffelstein
        val albumId = MetadataUtils.generateAlbumId(track.albumArtist, track.albumTitle)
        assertEquals(track.albumId, albumId)

        // Track retains full artist
        assertEquals("Gesaffelstein & The Weeknd", track.artist)
        assertEquals("Gesaffelstein", track.albumArtist)
    }

    @Test
    fun testTrackModelRichMetadataPreservation() {
        val track = Track(
            id = 555L,
            title = "Starboy",
            artist = "The Weeknd feat. Daft Punk",
            albumArtist = "The Weeknd",
            albumId = MetadataUtils.generateAlbumId("The Weeknd", "Starboy"),
            albumTitle = "Starboy",
            duration = 230000L,
            trackNumber = 1,
            discNumber = 1,
            genre = "R&B",
            composer = "Abel Tesfaye",
            year = 2016,
            uri = "content://media/external/audio/media/555",
            artworkUri = "content://media/external/audio/albumart/555",
            codec = "FLAC",
            isFavorite = true,
            playCount = 15,
            lastPlayed = 1700000000000L,
        )

        assertEquals("The Weeknd feat. Daft Punk", track.artist)
        assertEquals("The Weeknd", track.albumArtist)
        assertEquals("R&B", track.genre)
        assertEquals("Abel Tesfaye", track.composer)
        assertEquals(1, track.discNumber)
        assertEquals(1, track.trackNumber)
        assertEquals(2016, track.year)
        assertEquals("FLAC", track.codec)
        assertTrue(track.isFavorite)
        assertEquals(15, track.playCount)
    }

    @Test
    fun testRescanReconciliationPreservesUserPlayState() {
        val existingTrack1 = createTestTrack(
            path = "/storage/emulated/0/Muzic/song1.flac",
            title = "Song One",
            artist = "Artist A",
            albumArtist = "Artist A",
            album = "Album 1",
            trackNum = 1
        ).copy(isFavorite = true, playCount = 42, lastPlayed = 1690000000000L)

        val existingTrack2 = createTestTrack(
            path = "/storage/emulated/0/Muzic/deleted_song.flac",
            title = "Deleted Song",
            artist = "Artist B",
            albumArtist = "Artist B",
            album = "Album 2",
            trackNum = 1
        )

        val existingTracks = listOf(existingTrack1, existingTrack2)

        // Rescan finds song1 and a newly added song3, but deleted_song is gone
        val freshlyScannedTrack1 = createTestTrack(
            path = "/storage/emulated/0/Muzic/song1.flac",
            title = "Song One",
            artist = "Artist A",
            albumArtist = "Artist A",
            album = "Album 1",
            trackNum = 1
        ) // default playCount = 0, isFavorite = false

        val freshlyScannedTrack3 = createTestTrack(
            path = "/storage/emulated/0/Muzic/new_song.flac",
            title = "Song Three",
            artist = "Artist C",
            albumArtist = "Artist C",
            album = "Album 3",
            trackNum = 1
        )

        val scannedTracks = listOf(freshlyScannedTrack1, freshlyScannedTrack3)

        // Simulate atomic reconciliation
        val stateMapById = existingTracks.associate { it.id to Triple(it.isFavorite, it.playCount, it.lastPlayed) }
        val stateMapByUri = existingTracks.associate { (it.path ?: it.uri) to Triple(it.isFavorite, it.playCount, it.lastPlayed) }

        val mergedTracks = scannedTracks.map { track ->
            val savedState = stateMapById[track.id] ?: stateMapByUri[track.path ?: track.uri]
            if (savedState != null) {
                track.copy(
                    isFavorite = savedState.first,
                    playCount = savedState.second,
                    lastPlayed = savedState.third
                )
            } else {
                track
            }
        }

        val newTrackIds = mergedTracks.map { it.id }.toSet()
        val staleTrackIds = existingTracks.map { it.id }.filter { it !in newTrackIds }

        // Assertions
        assertEquals(2, mergedTracks.size)
        assertEquals(1, staleTrackIds.size)
        assertEquals(existingTrack2.id, staleTrackIds[0]) // deleted_song is flagged as stale

        val mergedSong1 = mergedTracks.find { it.id == existingTrack1.id }
        assertNotNull(mergedSong1)
        assertTrue(mergedSong1!!.isFavorite)
        assertEquals(42, mergedSong1.playCount)
        assertEquals(1690000000000L, mergedSong1.lastPlayed)

        val mergedSong3 = mergedTracks.find { it.id == freshlyScannedTrack3.id }
        assertNotNull(mergedSong3)
        assertFalse(mergedSong3!!.isFavorite)
        assertEquals(0, mergedSong3.playCount)
    }

    @Test
    fun testMedia3MetadataSurvival() {
        val track = Track(
            id = 1234L,
            title = "In the Night",
            artist = "The Weeknd",
            albumArtist = "The Weeknd",
            albumId = MetadataUtils.generateAlbumId("The Weeknd", "Beauty Behind the Madness"),
            albumTitle = "Beauty Behind the Madness",
            duration = 235000L,
            trackNumber = 9,
            discNumber = 1,
            genre = "Pop",
            composer = "Abel Tesfaye, Max Martin",
            year = 2015,
            uri = "content://media/external/audio/media/1234",
            artworkUri = "content://media/external/audio/albumart/1234",
            codec = "FLAC"
        )

        // Simulate PlaybackManager.toMediaItem() metadata mapping
        val meta = androidx.media3.common.MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumArtist(track.albumArtist.takeIf { it.isNotBlank() })
            .setAlbumTitle(track.albumTitle)
            .setTrackNumber(track.trackNumber.takeIf { it > 0 })
            .setDiscNumber(track.discNumber.takeIf { it > 0 })
            .setGenre(track.genre)
            .setComposer(track.composer)
            .setRecordingYear(track.year.takeIf { it > 0 })
            .build()

        assertEquals("In the Night", meta.title?.toString())
        assertEquals("The Weeknd", meta.artist?.toString())
        assertEquals("The Weeknd", meta.albumArtist?.toString())
        assertEquals("Beauty Behind the Madness", meta.albumTitle?.toString())
        assertEquals(9, meta.trackNumber)
        assertEquals(1, meta.discNumber)
        assertEquals("Pop", meta.genre?.toString())
        assertEquals("Abel Tesfaye, Max Martin", meta.composer?.toString())
        assertEquals(2015, meta.recordingYear)
        assertEquals(track.id.toString(), "1234")
        assertEquals(track.uri, "content://media/external/audio/media/1234")
        assertEquals(track.artworkUri, "content://media/external/audio/albumart/1234")
    }

    @Test
    fun testPhysicalPathRemainsUntouched() {
        val originalPath = "/storage/emulated/0/Muzic/deeply/nested/random/song.flac"
        val track = createTestTrack(
            path = originalPath,
            title = "Song",
            artist = "Artist",
            albumArtist = "Artist",
            album = "Album",
            trackNum = 1
        )

        assertEquals(originalPath, track.path)
        assertEquals(originalPath, track.uri)
    }

    private fun createTestTrack(
        path: String,
        title: String,
        artist: String,
        albumArtist: String,
        album: String,
        trackNum: Int,
    ): Track {
        val albumId = MetadataUtils.generateAlbumId(albumArtist, album)
        val trackId = MetadataUtils.generateTrackId(path)
        return Track(
            id = trackId,
            title = title,
            artist = artist,
            albumArtist = albumArtist,
            albumId = albumId,
            albumTitle = album,
            duration = 200000L,
            trackNumber = trackNum,
            discNumber = 1,
            year = 2020,
            uri = path,
            path = path,
            codec = "MP3"
        )
    }
}
