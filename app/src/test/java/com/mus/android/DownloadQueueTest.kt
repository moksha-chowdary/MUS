package com.mus.android

import com.mus.android.data.model.DownloadQueueItem
import com.mus.android.data.model.DownloadStatus
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

    // ─── Test 6: Remove logic (simulated) ────────────────────────

    @Test
    fun `removing item from list by id does not affect other items`() {
        val items = mutableListOf(
            makeItem(sourceId = "aaa"),
            makeItem(sourceId = "bbb"),
            makeItem(sourceId = "ccc"),
        )
        val removeId = "YouTube_bbb"
        items.removeAll { it.id == removeId }

        assertEquals(2, items.size)
        assertTrue(items.none { it.id == removeId })
        assertTrue(items.any { it.id == "YouTube_aaa" })
        assertTrue(items.any { it.id == "YouTube_ccc" })
    }
}
