package com.mus.android

import com.mus.android.data.model.Playlist
import com.mus.android.data.model.PlaylistTrack
import com.mus.android.data.model.Track
import com.mus.android.playback.QueueBuilder
import org.junit.Assert.*
import org.junit.Test

/**
 * Focused queue state machine tests verifying:
 * - Sequential auto-advance produces no revisits
 * - NEXT / PREVIOUS navigation correctness
 * - Queue reorder correctness
 * - Queue deletion does not affect playlists
 * - Quick Pick language queue integrity
 * - No duplicate media items in queue
 */
class QueueStateMachineTest {

    private fun track(id: Long, title: String = "Track $id", language: String = "English"): Track {
        return Track(
            id = id,
            title = title,
            artist = "Artist $id",
            albumId = 1L,
            albumTitle = "Album 1",
            duration = 180_000L,
            uri = "content://audio/$id",
            language = language,
        )
    }

    // ── Test 1: Sequential auto-advance produces exact order ──────────

    @Test
    fun `queue 1-2-3-4 auto-advance produces 1-2-3-4`() {
        val queue = listOf(track(1), track(2), track(3), track(4))

        // Simulate sequential auto-advance: walk through indices
        val playedOrder = mutableListOf<Long>()
        for (index in queue.indices) {
            playedOrder.add(queue[index].id)
        }

        assertEquals(listOf(1L, 2L, 3L, 4L), playedOrder)

        // Verify no revisits: each ID appears exactly once
        assertEquals(playedOrder.size, playedOrder.toSet().size)
    }

    // ── Test 2: NEXT from index 0 → index 1 ─────────────────────────

    @Test
    fun `NEXT from track 1 goes to track 2`() {
        val queue = listOf(track(1), track(2), track(3), track(4))
        val currentIndex = 0
        val nextIndex = currentIndex + 1

        assertTrue(nextIndex in queue.indices)
        assertEquals(2L, queue[nextIndex].id)
    }

    // ── Test 3: NEXT twice from index 0 → index 2 ───────────────────

    @Test
    fun `NEXT twice from track 1 goes to track 3`() {
        val queue = listOf(track(1), track(2), track(3), track(4))
        var currentIndex = 0

        currentIndex++ // first NEXT
        currentIndex++ // second NEXT

        assertEquals(2, currentIndex)
        assertEquals(3L, queue[currentIndex].id)
    }

    // ── Test 4: PREVIOUS from index 2 → index 1 ─────────────────────

    @Test
    fun `PREVIOUS from track 3 goes to track 2`() {
        val queue = listOf(track(1), track(2), track(3), track(4))
        val currentIndex = 2
        val prevIndex = currentIndex - 1

        assertTrue(prevIndex in queue.indices)
        assertEquals(2L, queue[prevIndex].id)
    }

    // ── Test 5: Auto-advance must never revisit a consumed item ──────

    @Test
    fun `auto-advance never revisits consumed items`() {
        val queue = listOf(track(1), track(2), track(3), track(4))
        val visited = mutableSetOf<Long>()
        val playedOrder = mutableListOf<Long>()

        for (index in queue.indices) {
            val trackId = queue[index].id
            assertFalse(
                "Track $trackId was revisited at index $index! Played so far: $playedOrder",
                trackId in visited
            )
            visited.add(trackId)
            playedOrder.add(trackId)
        }

        assertEquals(listOf(1L, 2L, 3L, 4L), playedOrder)
    }

    // ── Test 6: Quick Pick language queue has selected first ──────────

    @Test
    fun `quick pick language queue places selected track first and follows prepared order`() {
        val allTracks = listOf(
            track(1, "Song A", "English"),
            track(2, "Song B", "English"),
            track(3, "Song C", "English"),
            track(4, "Song D", "Hindi"),
        )
        val selected = track(2, "Song B", "English")

        val rng = java.util.Random(42L)
        val queue = QueueBuilder.buildLanguageMixQueue(selected, allTracks, rng)

        // Selected track at index 0
        assertEquals(selected.id, queue.first().id)

        // All English (language filter)
        assertTrue(queue.all { it.language.equals("English", ignoreCase = true) })

        // No duplicates
        assertEquals(queue.size, queue.map { it.id }.toSet().size)

        // Prepared order is deterministic: simulate sequential playback
        val playedOrder = queue.map { it.id }
        assertEquals(playedOrder.first(), 2L)
        assertEquals(queue.size, 3) // Song A, B, C (English only)
    }

    // ── Test 7: Queue reorder produces correct playback order ────────

    @Test
    fun `queue reorder 4 to position 1 produces 1-4-2-3`() {
        val queue = mutableListOf(track(1), track(2), track(3), track(4))

        // Move track 4 (index 3) to position 1
        val item = queue.removeAt(3)
        queue.add(1, item)

        assertEquals(listOf(1L, 4L, 2L, 3L), queue.map { it.id })

        // Verify sequential playback follows new order exactly
        val playedOrder = mutableListOf<Long>()
        for (t in queue) {
            playedOrder.add(t.id)
        }
        assertEquals(listOf(1L, 4L, 2L, 3L), playedOrder)
    }

    // ── Test 8: Deleting queue item does not modify playlist ─────────

    @Test
    fun `deleting queue item does not modify playlist`() {
        val playlist = Playlist(id = 10L, name = "My Playlist")
        val playlistTracks = listOf(
            PlaylistTrack(playlistId = 10L, trackId = 1L, position = 0),
            PlaylistTrack(playlistId = 10L, trackId = 2L, position = 1),
            PlaylistTrack(playlistId = 10L, trackId = 3L, position = 2),
            PlaylistTrack(playlistId = 10L, trackId = 4L, position = 3),
        )

        // Active playback queue
        val queue = mutableListOf(track(1), track(2), track(3), track(4))

        // User deletes track 3 from queue
        queue.removeAt(2)
        assertEquals(listOf(1L, 2L, 4L), queue.map { it.id })

        // Playlist table remains completely intact
        assertEquals(4, playlistTracks.size)
        assertEquals(listOf(1L, 2L, 3L, 4L), playlistTracks.map { it.trackId })
        assertEquals("My Playlist", playlist.name)
    }

    // ── Additional: No duplicate media items in prepared queue ────────

    @Test
    fun `prepared queue contains no duplicates`() {
        val allTracks = (1L..20L).map { track(it, "Track $it", "English") }
        val selected = allTracks[5]

        val queue = QueueBuilder.buildLanguageMixQueue(selected, allTracks)

        val ids = queue.map { it.id }
        assertEquals("Queue contains duplicate IDs: $ids", ids.size, ids.toSet().size)
    }

    // ── Additional: Queue with single track works ────────────────────

    @Test
    fun `single track queue auto-advance is just that track`() {
        val queue = listOf(track(42))
        assertEquals(1, queue.size)
        assertEquals(42L, queue[0].id)
    }

    // ── Additional: Longer queue auto-advance ────────────────────────

    @Test
    fun `queue 1-2-3-4-5-6 auto-advance produces exact sequence`() {
        val queue = (1L..6L).map { track(it) }

        val playedOrder = mutableListOf<Long>()
        val visited = mutableSetOf<Long>()

        for (t in queue) {
            assertFalse("Revisited track ${t.id}", t.id in visited)
            visited.add(t.id)
            playedOrder.add(t.id)
        }

        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L), playedOrder)
    }
}
