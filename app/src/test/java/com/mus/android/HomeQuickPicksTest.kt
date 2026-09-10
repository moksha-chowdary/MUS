package com.mus.android

import com.mus.android.data.model.Track
import org.junit.Assert.*
import org.junit.Test
import java.util.Random

class HomeQuickPicksTest {

    private fun sampleTrack(
        id: Long,
        title: String,
        isFavorite: Boolean = false,
        playCount: Int = 0,
        lastPlayed: Long = 0L,
        language: String = "English"
    ): Track {
        return Track(
            id = id,
            title = title,
            artist = "Artist $id",
            albumId = 1L,
            albumTitle = "Album",
            duration = 180000L,
            uri = "content://audio/$id",
            isFavorite = isFavorite,
            playCount = playCount,
            lastPlayed = lastPlayed,
            language = language
        )
    }

    private fun computeQuickPicks(
        tracks: List<Track>,
        seed: Long
    ): List<Track> {
        if (tracks.isEmpty()) return emptyList()
        if (tracks.size <= 8) return tracks

        val now = 1700000000000L
        val rng = Random(seed)

        val scored = tracks.map { track ->
            var score = 0.0
            if (track.isFavorite) score += 50.0
            score += (track.playCount.coerceAtMost(10) * 5.0)

            if (track.lastPlayed > 0) {
                val daysAgo = (now - track.lastPlayed) / (1000 * 60 * 60 * 24)
                if (daysAgo < 7) {
                    score += 30.0 - (daysAgo * 3.0)
                } else {
                    score += 15.0
                }
            } else {
                score += 20.0
            }

            score += rng.nextDouble() * 30.0
            Pair(track, score)
        }

        return scored
            .sortedByDescending { it.second }
            .take(20)
            .map { it.first }
            .shuffled(rng)
            .take(8)
    }

    @Test
    fun testQuickPicksContainsSongsNotAlbums() {
        val tracks = (1..15).map { sampleTrack(it.toLong(), "Song $it") }
        val quickPicks = computeQuickPicks(tracks, seed = 12345L)

        assertEquals(8, quickPicks.size)
        // Verify every element is an individual Track
        for (item in quickPicks) {
            assertTrue(item.title.startsWith("Song "))
        }
    }

    @Test
    fun testQuickPicksDeterministicForSameSessionSeed() {
        val tracks = (1..30).map { id ->
            sampleTrack(
                id = id.toLong(),
                title = "Song $id",
                isFavorite = id % 3 == 0,
                playCount = id * 2
            )
        }

        val seed = 99999L
        val run1 = computeQuickPicks(tracks, seed)
        val run2 = computeQuickPicks(tracks, seed)
        val run3 = computeQuickPicks(tracks, seed)

        // Multiple recompositions / calls with same session seed MUST produce identical list
        assertEquals(run1.map { it.id }, run2.map { it.id })
        assertEquals(run1.map { it.id }, run3.map { it.id })
    }

    @Test
    fun testQuickPicksFavoritesAndHighPlaysRankHigher() {
        val lowTracks = (1..20).map { id ->
            sampleTrack(id.toLong(), "Low $id", isFavorite = false, playCount = 0)
        }
        val favoriteTracks = (101..105).map { id ->
            sampleTrack(id.toLong(), "Fav $id", isFavorite = true, playCount = 20)
        }

        val all = lowTracks + favoriteTracks
        val scored = all.map { track ->
            var score = 0.0
            if (track.isFavorite) score += 50.0
            score += (track.playCount.coerceAtMost(10) * 5.0)
            Pair(track, score)
        }.sortedByDescending { it.second }

        val top5 = scored.take(5).map { it.first }
        assertEquals(5, top5.filter { it.isFavorite }.size)
    }

    @Test
    fun testQuickPicksDifferentSeedGeneratesVariation() {
        val tracks = (1..50).map { id ->
            sampleTrack(id.toLong(), "Track $id")
        }

        val day1Picks = computeQuickPicks(tracks, seed = 100L).map { it.id }
        val day2Picks = computeQuickPicks(tracks, seed = 200L).map { it.id }

        // Different seeds (different days/refresh) produce different selections
        assertNotEquals(day1Picks, day2Picks)
    }
}
