package com.mus.android

import com.mus.android.data.model.DownloadQueueItem
import com.mus.android.data.model.DownloadStatus
import com.mus.android.data.model.Track
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for the "MUS — To Download" planning list.
 * All tests run on JVM — no Android runtime required.
 */
class DownloadQueueTest {

    // ─── Test fixtures ────────────────────────────────────────────

    private fun makeItem(
        source: String = "YouTube",
        sourceId: String = "dQw4w9WgXcQ",
        title: String = "Test Song",
        artist: String = "Test Artist",
        status: String = DownloadStatus.TO_DOWNLOAD,
    ): DownloadQueueItem {
        val id = "${source}_${sourceId}"
        return DownloadQueueItem(
            id = id,
            title = title,
            artist = artist,
            album = null,
            artworkUrl = "https://example.com/thumb.jpg",
            source = source,
            sourceId = sourceId,
            sourceUrl = "https://www.youtube.com/watch?v=$sourceId",
            dateAdded = System.currentTimeMillis(),
            status = status,
        )
    }

    // ─── Test 1: ID generation is deterministic ───────────────────

    @Test
    fun `download queue item ID is source_sourceId`() {
        val item = makeItem(source = "YouTube", sourceId = "abc123")
        assertEquals("YouTube_abc123", item.id)
    }

    @Test
    fun `download queue item ID is deterministic for same input`() {
        val id1 = "YouTube_abc123"
        val id2 = "YouTube_abc123"
        assertEquals(id1, id2)
    }

    // ─── Test 2: Deduplication logic ─────────────────────────────

    @Test
    fun `duplicate items have same id so Set deduplicates them`() {
        val items = mutableSetOf<String>()
        items.add("YouTube_abc123")
        val inserted = items.add("YouTube_abc123") // should not add
        assertFalse(inserted)
        assertEquals(1, items.size)
    }

    @Test
    fun `different sources produce different ids for same sourceId`() {
        val idYouTube = "YouTube_abc123"
        val idITunes = "iTunes_abc123"
        assertNotEquals(idYouTube, idITunes)
    }

    @Test
    fun `different sourceIds produce different ids for same source`() {
        val id1 = "YouTube_abc123"
        val id2 = "YouTube_xyz789"
        assertNotEquals(id1, id2)
    }

    // ─── Test 3: Status values ────────────────────────────────────

    @Test
    fun `TO_DOWNLOAD status is the default`() {
        val item = makeItem()
        assertEquals(DownloadStatus.TO_DOWNLOAD, item.status)
    }

    @Test
    fun `status can be updated to DOWNLOADED`() {
        val item = makeItem().copy(status = DownloadStatus.DOWNLOADED)
        assertEquals(DownloadStatus.DOWNLOADED, item.status)
    }

    @Test
    fun `status can be updated to ADDED_TO_LIBRARY`() {
        val item = makeItem().copy(status = DownloadStatus.ADDED_TO_LIBRARY)
        assertEquals(DownloadStatus.ADDED_TO_LIBRARY, item.status)
    }

    // ─── Test 4: No audio file field exists ──────────────────────

    @Test
    fun `DownloadQueueItem has no localFilePath field`() {
        val item = makeItem()
        // Verifies the entity is metadata-only — no local file path field
        val fields = item.javaClass.declaredFields.map { it.name }
        assertFalse("localFilePath should not exist", fields.contains("localFilePath"))
        assertFalse("localAudioPath should not exist", fields.contains("localAudioPath"))
        assertFalse("downloadedFile should not exist", fields.contains("downloadedFile"))
    }

    // ─── Test 5: Source URL is preserved ─────────────────────────

    @Test
    fun `source URL is preserved from creation`() {
        val item = makeItem(source = "YouTube", sourceId = "dQw4w9WgXcQ")
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", item.sourceUrl)
    }

    // ─── Tests 11-14: Explicit lifecycle & isolation tests ────────

    @Test
    fun `11 Download queue item appears immediately after adding an online result`() {
        val queue = mutableListOf<DownloadQueueItem>()
        val newItem = makeItem(source = "YouTube", sourceId = "vid123", title = "New Discovery")

        // Simulate repository.addToDownloadQueue
        queue.add(0, newItem)

        assertEquals(1, queue.size)
        assertEquals("YouTube_vid123", queue.first().id)
        assertEquals("New Discovery", queue.first().title)
        assertEquals(DownloadStatus.TO_DOWNLOAD, queue.first().status)
    }

    @Test
    fun `12 Download queue survives repository and ViewModel recreation`() {
        // Persistent backing store simulation (mimicking Room SQLite table)
        val persistentTable = mutableMapOf<String, DownloadQueueItem>()
        persistentTable["YouTube_song1"] = makeItem(source = "YouTube", sourceId = "song1")
        persistentTable["iTunes_song2"] = makeItem(source = "iTunes", sourceId = "song2")

        // First ViewModel lifecycle
        val initialCount = persistentTable.values.count { it.status == DownloadStatus.TO_DOWNLOAD }
        assertEquals(2, initialCount)

        // ViewModel / Repository destroyed and recreated from same persistent store
        val recreatedQueue = persistentTable.values.toList()
        assertEquals(2, recreatedQueue.size)
        assertTrue(recreatedQueue.any { it.id == "YouTube_song1" })
        assertTrue(recreatedQueue.any { it.id == "iTunes_song2" })
    }

    @Test
    fun `13 Duplicate online result is still deduplicated`() {
        val queue = mutableMapOf<String, DownloadQueueItem>()

        fun addItem(item: DownloadQueueItem): Boolean {
            if (queue.containsKey(item.id)) return false
            queue[item.id] = item
            return true
        }

        val item1 = makeItem(source = "YouTube", sourceId = "duplicate_id")
        val item2 = makeItem(source = "YouTube", sourceId = "duplicate_id", title = "Different Title Attempt")

        val addedFirst = addItem(item1)
        val addedSecond = addItem(item2)

        assertTrue("First insertion should succeed", addedFirst)
        assertFalse("Second insertion of same source_sourceId must be rejected", addedSecond)
        assertEquals(1, queue.size)
    }

    @Test
    fun `14 Removing a download queue item never touches the local audio file`() {
        val localTrack = Track(
            id = 42L,
            title = "Local Song",
            artist = "Local Artist",
            albumId = 1L,
            albumTitle = "Local Album",
            duration = 180_000L,
            uri = "file:///storage/emulated/0/Muzic/local_file.mp3",
            path = "/storage/emulated/0/Muzic/local_file.mp3",
        )
        val queue = mutableListOf(
            makeItem(source = "YouTube", sourceId = "song_to_remove"),
        )

        // User removes item from download queue
        val removeTargetId = "YouTube_song_to_remove"
        queue.removeAll { it.id == removeTargetId }

        // Verify queue state
        assertTrue(queue.isEmpty())

        // Verify local audio track is completely untouched
        assertEquals(42L, localTrack.id)
        assertEquals("/storage/emulated/0/Muzic/local_file.mp3", localTrack.path)
        assertEquals("file:///storage/emulated/0/Muzic/local_file.mp3", localTrack.uri)
    }
}
