package com.mus.android

import com.mus.android.data.model.Track
import org.junit.Assert.*
import org.junit.Test

class TrackModelTest {

    @Test
    fun testTrackDefaultValues() {
        val track = Track(
            id = 1001L,
            title = "Starboy",
            artist = "The Weeknd",
            albumId = 501L,
            albumTitle = "Starboy",
            duration = 230000L,
            uri = "content://media/external/audio/media/1001",
        )

        assertEquals(1001L, track.id)
        assertEquals("Starboy", track.title)
        assertEquals("The Weeknd", track.artist)
        assertEquals(0, track.playCount)
        assertEquals(0L, track.lastPlayed)
        assertFalse(track.isFavorite)
        assertEquals("English", track.language)
    }

    @Test
    fun testTrackPlayHistoryUpdate() {
        val original = Track(
            id = 2002L,
            title = "Kesariya",
            artist = "Arijit Singh",
            albumId = 602L,
            albumTitle = "Brahmastra",
            duration = 268000L,
            uri = "content://media/external/audio/media/2002",
            language = "Hindi",
        )

        val updated = original.copy(
            playCount = original.playCount + 1,
            lastPlayed = 1700000000000L,
            isFavorite = true
        )

        assertEquals(1, updated.playCount)
        assertEquals(1700000000000L, updated.lastPlayed)
        assertTrue(updated.isFavorite)
    }
}
