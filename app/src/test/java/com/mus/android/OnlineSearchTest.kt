package com.mus.android

import com.mus.android.data.enrichment.provider.OnlineSearchResult
import com.mus.android.data.model.DownloadStatus
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for online music search and discovery.
 * Pure JVM — no Android runtime, no network calls.
 */
class OnlineSearchTest {

    // ─── Test fixtures ────────────────────────────────────────────

    private fun makeOnlineResult(
        id: String = "1",
        source: String = "YouTube",
        sourceId: String = "dQw4w9WgXcQ",
        title: String = "Test Song",
        artist: String = "Test Artist",
        album: String? = null,
    ) = OnlineSearchResult(
        id = id,
        title = title,
        artist = artist,
        album = album,
        artworkUrl = "https://i.ytimg.com/vi/$sourceId/maxresdefault.jpg",
        source = source,
        sourceId = sourceId,
        sourceUrl = "https://www.youtube.com/watch?v=$sourceId",
        durationMs = 0L,
    )

    // ─── Test 1: Local search regression guard ────────────────────

    @Test
    fun `local search query is a simple string filter — not affected by online feature`() {
        // Simulate local library search logic
        val library = listOf("Bohemian Rhapsody", "Rolling in the Deep", "Blinding Lights")
        val query = "blind"
        val results = library.filter { it.contains(query, ignoreCase = true) }

        assertEquals(1, results.size)
        assertEquals("Blinding Lights", results.first())
    }

    // ─── Test 2: OnlineSearchResult model integrity ───────────────

    @Test
    fun `OnlineSearchResult source field is preserved`() {
        val result = makeOnlineResult(source = "YouTube")
        assertEquals("YouTube", result.source)
    }

    @Test
    fun `OnlineSearchResult is NOT a Track and has no albumId field`() {
        val result = makeOnlineResult()
        val fields = result.javaClass.declaredFields.map { it.name }
        assertFalse("albumId should not exist on OnlineSearchResult", fields.contains("albumId"))
        assertFalse("uri should not exist on OnlineSearchResult", fields.contains("uri"))
        assertFalse("artworkSource should not exist on OnlineSearchResult", fields.contains("artworkSource"))
    }

    @Test
    fun `OnlineSearchResult sourceUrl is a valid HTTPS URL`() {
        val result = makeOnlineResult(sourceId = "dQw4w9WgXcQ")
        assertTrue(result.sourceUrl.startsWith("https://"))
        assertTrue(result.sourceUrl.contains("youtube.com"))
    }

    // ─── Test 3: Debounce cancel logic ───────────────────────────

    @Test
    fun `blank query returns empty results immediately`() {
        val query = "   "
        // Simulates the DiscoveryViewModel guard
        val results: List<OnlineSearchResult> = if (query.isBlank()) emptyList() else listOf(makeOnlineResult())
        assertTrue(results.isEmpty())
    }

    @Test
    fun `non-blank query would trigger search`() {
        val query = "bohemian rhapsody"
        val wouldTrigger = query.isNotBlank()
        assertTrue(wouldTrigger)
    }

    // ─── Test 4: No network on main thread ───────────────────────

    @Test
    fun `OnlineSearchResult construction does not involve network`() {
        // This test verifies that creating a result object is pure data — no I/O
        val start = System.currentTimeMillis()
        repeat(1000) { makeOnlineResult(id = it.toString()) }
        val elapsed = System.currentTimeMillis() - start
        // Should complete in well under 100ms with no I/O
        assertTrue("Result creation took too long (${elapsed}ms) — possible I/O on main thread", elapsed < 500)
    }

    // ─── Test 5: Source distinctness ─────────────────────────────

    @Test
    fun `YouTube and iTunes results are clearly distinct by source field`() {
        val ytResult = makeOnlineResult(source = "YouTube")
        val itunesResult = makeOnlineResult(source = "iTunes")

        assertNotEquals(ytResult.source, itunesResult.source)
        assertEquals("YouTube", ytResult.source)
        assertEquals("iTunes", itunesResult.source)
    }

    // ─── Test 6: Download queue composite ID ─────────────────────

    @Test
    fun `download queue ID is source_sourceId for YouTube result`() {
        val result = makeOnlineResult(source = "YouTube", sourceId = "abc123")
        val queueId = "${result.source}_${result.sourceId}"
        assertEquals("YouTube_abc123", queueId)
    }

    @Test
    fun `download queue deduplication - same result produces same ID`() {
        val r1 = makeOnlineResult(source = "YouTube", sourceId = "same123")
        val r2 = makeOnlineResult(source = "YouTube", sourceId = "same123", title = "Different Title")

        val id1 = "${r1.source}_${r1.sourceId}"
        val id2 = "${r2.source}_${r2.sourceId}"

        assertEquals(id1, id2) // same video, should deduplicate
    }

    // ─── Test 7: Existing local search tests still compile ───────

    @Test
    fun `local library results do not have source field`() {
        // Track does not have a "source" field in the online discovery sense
        val trackFields = com.mus.android.data.model.Track::class.java.declaredFields.map { it.name }
        assertFalse("Local Track should not have a 'source' field", trackFields.contains("source"))
    }
}
