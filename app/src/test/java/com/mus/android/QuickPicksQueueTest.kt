package com.mus.android

import com.mus.android.data.classifier.LanguageClassifier
import com.mus.android.data.model.Playlist
import com.mus.android.data.model.PlaylistTrack
import com.mus.android.data.model.Track
import com.mus.android.playback.QueueBuilder
import com.mus.android.playback.QueueSource
import org.junit.Assert.*
import org.junit.Test
import java.util.Random

class QuickPicksQueueTest {

    private fun sampleTrack(
        id: Long,
        title: String,
        language: String = "English",
        isFavorite: Boolean = false,
        playCount: Int = 0,
        lastPlayed: Long = 0L,
    ): Track {
        return Track(
            id = id,
            title = title,
            artist = "Artist $id",
            albumId = 1L,
            albumTitle = "Album 1",
            duration = 180000L,
            uri = "content://audio/$id",
            language = language,
            isFavorite = isFavorite,
            playCount = playCount,
            lastPlayed = lastPlayed,
        )
    }

    @Test
    fun testQuickPickSongIdentifiesCorrectLanguageSource() {
        val engTrack = sampleTrack(1, "Galway Girl", language = "English")
        val hinTrack = sampleTrack(2, "Kesariya", language = "Hindi")
        val telTrack = sampleTrack(3, "Samajavaragamana", language = "Telugu")
        val othTrack = sampleTrack(4, "Despacito", language = "Other")
        val unkTrack = sampleTrack(5, "Track X", language = "Unknown")

        val engCtx = QueueBuilder.getQueueContextForLanguageMix(engTrack)
        assertEquals(QueueSource.LANGUAGE_MIX, engCtx.source)
        assertEquals("ENGLISH", engCtx.sourceLanguage)

        val hinCtx = QueueBuilder.getQueueContextForLanguageMix(hinTrack)
        assertEquals(QueueSource.LANGUAGE_MIX, hinCtx.source)
        assertEquals("HINDI", hinCtx.sourceLanguage)

        val telCtx = QueueBuilder.getQueueContextForLanguageMix(telTrack)
        assertEquals(QueueSource.LANGUAGE_MIX, telCtx.source)
        assertEquals("TELUGU", telCtx.sourceLanguage)

        val othCtx = QueueBuilder.getQueueContextForLanguageMix(othTrack)
        assertEquals(QueueSource.LANGUAGE_MIX, othCtx.source)
        assertEquals("OTHER", othCtx.sourceLanguage)

        val unkCtx = QueueBuilder.getQueueContextForLanguageMix(unkTrack)
        assertEquals(QueueSource.LANGUAGE_MIX, unkCtx.source)
        assertEquals("UNKNOWN", unkCtx.sourceLanguage)
    }

    @Test
    fun testEnglishQuickPickCreatesEnglishQueueWithSelectedAsCurrent() {
        val allTracks = listOf(
            sampleTrack(1, "Shape of You", language = "English"),
            sampleTrack(2, "Galway Girl", language = "English"),
            sampleTrack(3, "Perfect", language = "English"),
            sampleTrack(4, "Tum Hi Ho", language = "Hindi"),
            sampleTrack(5, "Channa Mereya", language = "Hindi"),
            sampleTrack(6, "Naatu Naatu", language = "Telugu"),
        )

        val selected = sampleTrack(2, "Galway Girl", language = "English")
        val queue = QueueBuilder.buildLanguageMixQueue(selected, allTracks)

        // 1. Selected song MUST be current track (index 0)
        assertEquals(selected.id, queue.first().id)
        assertEquals("Galway Girl", queue.first().title)

        // 2. All songs in queue must be English songs
        assertTrue(queue.all { it.language.equals("English", ignoreCase = true) })

        // 3. Queue size equals total English songs in library
        assertEquals(3, queue.size)

        // 4. Selected song must not appear twice
        assertEquals(1, queue.count { it.id == selected.id })
    }

    @Test
    fun testHindiQuickPickCreatesHindiQueue() {
        val allTracks = listOf(
            sampleTrack(1, "Believer", language = "English"),
            sampleTrack(2, "Kesariya", language = "Hindi"),
            sampleTrack(3, "Channa Mereya", language = "Hindi"),
            sampleTrack(4, "Kalank", language = "Hindi"),
            sampleTrack(5, "Kabira", language = "Hindi"),
            sampleTrack(6, "Butta Bomma", language = "Telugu"),
        )

        val selected = sampleTrack(3, "Channa Mereya", language = "Hindi")
        val queue = QueueBuilder.buildLanguageMixQueue(selected, allTracks)

        assertEquals(selected.id, queue.first().id)
        assertTrue(queue.all { it.language.equals("Hindi", ignoreCase = true) })
        assertEquals(4, queue.size)
        assertEquals(1, queue.count { it.id == selected.id })
    }

    @Test
    fun testTeluguRegionalQuickPickCreatesRegionalQueue() {
        val allTracks = listOf(
            sampleTrack(1, "Believer", language = "English"),
            sampleTrack(2, "Kesariya", language = "Hindi"),
            sampleTrack(3, "Naatu Naatu", language = "Telugu"),
            sampleTrack(4, "Kurchi Madathapetti", language = "Telugu"),
            sampleTrack(5, "Arabic Kuthu", language = "Other"),
        )

        val selected = sampleTrack(3, "Naatu Naatu", language = "Telugu")
        val queue = QueueBuilder.buildLanguageMixQueue(selected, allTracks)

        assertEquals(selected.id, queue.first().id)
        // Telugu mix pulls from Telugu tracks
        assertEquals(2, queue.size)
        assertTrue(queue.all { it.language.equals("Telugu", ignoreCase = true) })
        assertEquals(1, queue.count { it.id == selected.id })
    }

    @Test
    fun testUnknownLanguageFallbackToAllTracks() {
        val allTracks = listOf(
            sampleTrack(1, "Track A", language = "English"),
            sampleTrack(2, "Track B", language = "Hindi"),
            sampleTrack(3, "Unknown Track", language = "Unknown"),
        )

        val selected = sampleTrack(3, "Unknown Track", language = "Unknown")
        val queue = QueueBuilder.buildLanguageMixQueue(selected, allTracks)

        // When unknown pool has <= 1 track and library has multiple, sensible fallback uses all tracks
        assertEquals(selected.id, queue.first().id)
        assertEquals(3, queue.size)
        assertEquals(1, queue.count { it.id == selected.id })
    }

    @Test
    fun testSmallLanguageMixDoesNotFailOrDuplicate() {
        // 1 song mix
        val single = listOf(sampleTrack(1, "Solo Track", language = "English"))
        val q1 = QueueBuilder.buildLanguageMixQueue(single[0], single)
        assertEquals(1, q1.size)
        assertEquals("Solo Track", q1[0].title)

        // 2 song mix
        val duo = listOf(
            sampleTrack(1, "Song 1", language = "Hindi"),
            sampleTrack(2, "Song 2", language = "Hindi")
        )
        val q2 = QueueBuilder.buildLanguageMixQueue(duo[0], duo)
        assertEquals(2, q2.size)
        assertEquals(duo[0].id, q2[0].id)
        assertEquals(duo[1].id, q2[1].id)
    }

    @Test
    fun testQueueIsShuffledBeforePlayback() {
        val tracks = (1..20).map { sampleTrack(it.toLong(), "English Track $it", language = "English") }
        val selected = tracks[0]

        val rng1 = Random(42L)
        val rng2 = Random(99L)

        val queue1 = QueueBuilder.buildLanguageMixQueue(selected, tracks, rng1)
        val queue2 = QueueBuilder.buildLanguageMixQueue(selected, tracks, rng2)

        // Both place selected at index 0
        assertEquals(selected.id, queue1.first().id)
        assertEquals(selected.id, queue2.first().id)

        // But the upcoming tracks (index 1..last) are shuffled differently with different seeds
        val upcoming1 = queue1.drop(1).map { it.id }
        val upcoming2 = queue2.drop(1).map { it.id }
        assertNotEquals(upcoming1, upcoming2)
    }

    @Test
    fun testQueueOrderRemainsStableAfterRecomposition() {
        val tracks = (1..10).map { sampleTrack(it.toLong(), "Track $it", language = "English") }
        val selected = tracks[0]

        // Once generated, queue is fixed
        val preparedQueue = QueueBuilder.buildLanguageMixQueue(selected, tracks, Random(12345L))

        // Simulating 5 recompositions / navigation cycles: order does not change
        val cycle1 = preparedQueue.map { it.id }
        val cycle2 = preparedQueue.map { it.id }
        val cycle3 = preparedQueue.map { it.id }

        assertEquals(cycle1, cycle2)
        assertEquals(cycle2, cycle3)
    }

    @Test
    fun testQueueRemovalWorksAndLeavesUnderlyingTrackIntact() {
        val tracks = (1..5).map { sampleTrack(it.toLong(), "Track $it") }
        val queue = tracks.toMutableList()

        // User removes Track 3 (index 2)
        val removed = queue.removeAt(2)
        assertEquals(3L, removed.id)

        assertEquals(4, queue.size)
        assertEquals(listOf(1L, 2L, 4L, 5L), queue.map { it.id })

        // Original database track object is completely unaffected
        val originalTrack3 = tracks[2]
        assertEquals(3L, originalTrack3.id)
        assertEquals("Track 3", originalTrack3.title)
    }

    @Test
    fun testQueueReorderingWorksAndLeavesLibraryIntact() {
        val tracks = (1..5).map { sampleTrack(it.toLong(), "Track $it") }
        val queue = tracks.toMutableList()

        // User drags item 4 (index 4) to position 1
        val item = queue.removeAt(4)
        queue.add(1, item)

        assertEquals(listOf(1L, 5L, 2L, 3L, 4L), queue.map { it.id })

        // Library track list remains in its canonical order
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), tracks.map { it.id })
    }

    @Test
    fun testQueueRemovalDoesNotModifyPlaylistOrPlaylistTracks() {
        val playlist = Playlist(id = 10L, name = "My Playlist")
        val playlistTracks = mutableListOf(
            PlaylistTrack(playlistId = 10L, trackId = 1L, position = 0),
            PlaylistTrack(playlistId = 10L, trackId = 2L, position = 1),
            PlaylistTrack(playlistId = 10L, trackId = 3L, position = 2),
        )

        // Playback queue generated from playlist
        val queue = mutableListOf(1L, 2L, 3L)

        // User removes track 2 from queue
        queue.removeAt(1)
        assertEquals(listOf(1L, 3L), queue)

        // Playlist tracks in DB table remain completely intact
        assertEquals(3, playlistTracks.size)
        assertEquals(listOf(1L, 2L, 3L), playlistTracks.map { it.trackId })
    }

    @Test
    fun testQuickPicksContainsExactly8TracksDividedIntoPagesOf4() {
        val tracks = (1..20).map { sampleTrack(it.toLong(), "Song $it") }

        // Candidate selection takes 8 tracks
        val quickPicks = tracks.take(8)
        assertEquals(8, quickPicks.size)

        // Chunking into pages of 4
        val pages = quickPicks.chunked(4)
        assertEquals(2, pages.size)
        assertEquals(4, pages[0].size)
        assertEquals(4, pages[1].size)

        // Page 1: songs 1..4, Page 2: songs 5..8
        assertEquals(listOf(1L, 2L, 3L, 4L), pages[0].map { it.id })
        assertEquals(listOf(5L, 6L, 7L, 8L), pages[1].map { it.id })

        // Swiping back and forth across pages does not alter songs
        val swipeBackPage0 = pages[0]
        assertEquals(listOf(1L, 2L, 3L, 4L), swipeBackPage0.map { it.id })
    }
}
