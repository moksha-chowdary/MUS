package com.mus.android

import com.mus.android.data.model.Track
import org.junit.Assert.*
import org.junit.Test

class TrackOrderingTest {

    private fun sampleTrack(id: Long, title: String, playCount: Int = 0, lastPlayed: Long = 0L, isFavorite: Boolean = false): Track {
        return Track(
            id = id,
            title = title,
            artist = "Artist $id",
            albumId = id,
            albumTitle = "Album $id",
            duration = 180000L,
            uri = "content://audio/$id",
            playCount = playCount,
            lastPlayed = lastPlayed,
            isFavorite = isFavorite,
        )
    }

    @Test
    fun testRecentlyPlayedOrdering() {
        val tracks = listOf(
            sampleTrack(1L, "Older", lastPlayed = 1000L),
            sampleTrack(2L, "Newest", lastPlayed = 5000L),
            sampleTrack(3L, "Middle", lastPlayed = 3000L),
            sampleTrack(4L, "Never Played", lastPlayed = 0L),
        )

        // Filter out unplayed tracks and sort descending by lastPlayed
        val recentlyPlayed = tracks
            .filter { it.lastPlayed > 0 }
            .sortedByDescending { it.lastPlayed }

        assertEquals(3, recentlyPlayed.size)
        assertEquals("Newest", recentlyPlayed[0].title)
        assertEquals("Middle", recentlyPlayed[1].title)
        assertEquals("Older", recentlyPlayed[2].title)
    }

    @Test
    fun testMostPlayedOrdering() {
        val tracks = listOf(
            sampleTrack(1L, "Rank 3", playCount = 5),
            sampleTrack(2L, "Rank 1", playCount = 42),
            sampleTrack(3L, "Rank 2", playCount = 18),
            sampleTrack(4L, "Zero Plays", playCount = 0),
        )

        // Filter out zero play tracks and sort descending by playCount
        val mostPlayed = tracks
            .filter { it.playCount > 0 }
            .sortedByDescending { it.playCount }

        assertEquals(3, mostPlayed.size)
        assertEquals("Rank 1", mostPlayed[0].title)
        assertEquals(42, mostPlayed[0].playCount)
        assertEquals("Rank 2", mostPlayed[1].title)
        assertEquals(18, mostPlayed[1].playCount)
        assertEquals("Rank 3", mostPlayed[2].title)
        assertEquals(5, mostPlayed[2].playCount)
    }

    @Test
    fun testPlayCountIncrementAndTimestamp() {
        val track = sampleTrack(10L, "Track 10", playCount = 3, lastPlayed = 1000L)
        val timestamp = System.currentTimeMillis()

        val updated = track.copy(
            playCount = track.playCount + 1,
            lastPlayed = timestamp,
        )

        assertEquals(4, updated.playCount)
        assertEquals(timestamp, updated.lastPlayed)
    }

    @Test
    fun testFavoriteToggle() {
        val track = sampleTrack(20L, "Track 20", isFavorite = false)
        val favorited = track.copy(isFavorite = !track.isFavorite)
        assertTrue(favorited.isFavorite)

        val unfavorited = favorited.copy(isFavorite = !favorited.isFavorite)
        assertFalse(unfavorited.isFavorite)
    }
}
