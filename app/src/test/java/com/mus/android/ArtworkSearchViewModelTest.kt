package com.mus.android

import com.mus.android.data.enrichment.provider.ArtworkSearchProvider
import com.mus.android.data.enrichment.provider.ArtworkSearchResult
import com.mus.android.data.model.ArtworkSource
import com.mus.android.data.model.Track
import com.mus.android.ui.viewmodel.ArtworkApplyState
import com.mus.android.ui.viewmodel.ArtworkScope
import com.mus.android.ui.viewmodel.ArtworkSearchViewModel
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class ArtworkSearchViewModelTest {

    private fun makeTrack(
        id: Long,
        title: String,
        artist: String,
        artworkUri: String? = null,
        artworkSource: String = ArtworkSource.NONE,
    ) = Track(
        id = id,
        title = title,
        artist = artist,
        albumId = id * 10,
        albumTitle = "$title Album",
        albumArtist = artist,
        duration = 180_000L,
        year = 2024,
        uri = "file:///storage/emulated/0/Muzic/$title.mp3",
        path = "/storage/emulated/0/Muzic/$title.mp3",
        size = 4_000_000L,
        artworkUri = artworkUri,
        artworkSource = artworkSource,
    )

    private fun makeArtworkResult(id: String, title: String, artist: String, url: String) = ArtworkSearchResult(
        id = id,
        title = title,
        artist = artist,
        album = "$title Album",
        albumArtist = artist,
        year = 2024,
        artworkUrl = url,
        durationMs = 180_000L,
        provider = "iTunes",
        confidence = 0.95f,
    )

    /**
     * TEST 1: STALE STATE PREVENTION ACROSS TWO SEQUENTIAL TRACKS
     *
     * Given: Song 1, Song 2
     * Open artwork editor for Song 1.
     * Search/select/apply artwork.
     * Then open artwork editor for Song 2.
     * Assert:
     *   targetTrackId == Song 2.id
     *   query corresponds to Song 2
     *   Song 1 results are not present
     *   selectedResult == null
     * Then cancel.
     * Assert:
     *   Song 1 artwork is still the manually selected artwork
     *   Song 1 artworkSource == MANUAL
     *   Song 2 is unchanged
     */
    @Test
    fun test1_staleStatePreventionAcrossTracks() = runBlocking {
        var song1 = makeTrack(1L, "Blinding Lights", "The Weeknd")
        val song2 = makeTrack(2L, "Get Lucky", "Daft Punk")

        val song1Result = makeArtworkResult("res_1", "Blinding Lights", "The Weeknd", "https://art.com/song1.jpg")
        val song2Result = makeArtworkResult("res_2", "Get Lucky", "Daft Punk", "https://art.com/song2.jpg")

        val provider = object : ArtworkSearchProvider {
            override val name: String get() = "TestProvider"

            override suspend fun searchArtwork(query: String, limit: Int): List<ArtworkSearchResult> {
                return when {
                    query.contains("Weeknd", ignoreCase = true) -> listOf(song1Result)
                    query.contains("Daft", ignoreCase = true) -> listOf(song2Result)
                    else -> emptyList()
                }
            }
        }

        val testScope = CoroutineScope(Dispatchers.Unconfined)
        val viewModel = ArtworkSearchViewModel(
            artworkSearchProvider = provider,
            trackApplier = { trackId, url, prov, remoteId, res ->
                if (trackId == song1.id) {
                    song1 = song1.copy(
                        artworkUri = "file:///app/art_manual_1.jpg",
                        artworkSource = ArtworkSource.MANUAL,
                        artworkProvider = prov,
                        artworkRemoteId = remoteId,
                    )
                    song1
                } else null
            },
            scope = testScope,
        )

        // 1. Open artwork editor for Song 1
        viewModel.startSessionForTrack(song1)
        assertEquals(song1.id, viewModel.currentSession.value?.targetTrackId)
        assertEquals("The Weeknd Blinding Lights", viewModel.query.value)
        assertEquals(listOf(song1Result), viewModel.results.value)

        // 2. Select and apply artwork for Song 1
        viewModel.selectResult(song1Result)
        assertEquals(song1Result, viewModel.selectedResult.value)
        viewModel.applyToTrack(song1.id)
        assertEquals(ArtworkApplyState.Success, viewModel.applyState.value)
        assertEquals("file:///app/art_manual_1.jpg", song1.artworkUri)
        assertEquals(ArtworkSource.MANUAL, song1.artworkSource)

        // Close Song 1 session (as sheet onApplied does)
        viewModel.resetSession()
        assertNull(viewModel.selectedResult.value)
        assertNull(viewModel.currentSession.value)

        // 3. Open artwork editor for Song 2
        viewModel.startSessionForTrack(song2)

        // Assert: Song 2 has clean target identity and no residual Song 1 state
        assertEquals(song2.id, viewModel.currentSession.value?.targetTrackId)
        assertEquals("Daft Punk Get Lucky", viewModel.query.value)
        assertFalse(viewModel.results.value.contains(song1Result))
        assertEquals(listOf(song2Result), viewModel.results.value)
        assertNull("selectedResult must be null when opening a new track session", viewModel.selectedResult.value)

        // 4. User presses Cancel on Song 2
        viewModel.resetSession()

        // Assert: Song 1 artwork remains intact; Song 2 is unchanged
        assertEquals("file:///app/art_manual_1.jpg", song1.artworkUri)
        assertEquals(ArtworkSource.MANUAL, song1.artworkSource)
        assertNull(song2.artworkUri)
        assertEquals(ArtworkSource.NONE, song2.artworkSource)
    }

    /**
     * TEST 2 — SEARCH RACE CONDITION
     *
     * Start Song 1 search.
     * Before it completes: close Song 1 session.
     * Open Song 2. Start Song 2 search.
     * Complete Song 1 request after Song 2 starts.
     * Assert: Song 1 results do NOT appear in Song 2 UI.
     */
    @Test
    fun test2_searchRaceCondition() = runBlocking {
        val song1 = makeTrack(1L, "Song One", "Artist One")
        val song2 = makeTrack(2L, "Song Two", "Artist Two")

        val song1Result = makeArtworkResult("res_1", "Song One", "Artist One", "https://art.com/1.jpg")
        val song2Result = makeArtworkResult("res_2", "Song Two", "Artist Two", "https://art.com/2.jpg")

        val song1Gate = CompletableDeferred<Unit>()
        val provider = object : ArtworkSearchProvider {
            override val name: String get() = "TestProvider"

            override suspend fun searchArtwork(query: String, limit: Int): List<ArtworkSearchResult> {
                return if (query.contains("One")) {
                    song1Gate.await() // Hold Song 1 until released
                    listOf(song1Result)
                } else if (query.contains("Two")) {
                    listOf(song2Result)
                } else {
                    emptyList()
                }
            }
        }

        val testScope = CoroutineScope(Dispatchers.Default)
        val viewModel = ArtworkSearchViewModel(
            artworkSearchProvider = provider,
            trackApplier = { _, _, _, _, _ -> null },
            scope = testScope,
        )

        // 1. Start Song 1 search
        viewModel.startSessionForTrack(song1)

        // 2. Before Song 1 completes, close Song 1 session
        viewModel.resetSession()
        assertNull(viewModel.currentSession.value)

        // 3. Open Song 2 and start Song 2 search
        viewModel.startSessionForTrack(song2)

        // Wait a brief moment for Song 2 search to populate
        delay(100)
        assertEquals(listOf(song2Result), viewModel.results.value)

        // 4. Now release Song 1 request (completes after Song 2 started)
        song1Gate.complete(Unit)
        delay(100)

        // Assert: Song 1 results NEVER overwrite or appear in Song 2 session
        assertEquals("Song 1 results must be discarded when session changes", listOf(song2Result), viewModel.results.value)
        assertEquals(song2.id, viewModel.currentSession.value?.targetTrackId)
    }

    /**
     * TEST 3 — RAPID SWITCH
     *
     * Open: Song 1 → close → Song 2 → close → Song 3 → open.
     * Assert each artwork session has the correct target.
     */
    @Test
    fun test3_rapidSwitch() = runBlocking {
        val song1 = makeTrack(101L, "Alpha", "Artist A")
        val song2 = makeTrack(102L, "Beta", "Artist B")
        val song3 = makeTrack(103L, "Gamma", "Artist C")

        val provider = object : ArtworkSearchProvider {
            override val name: String get() = "TestProvider"

            override suspend fun searchArtwork(query: String, limit: Int): List<ArtworkSearchResult> = emptyList()
        }

        val testScope = CoroutineScope(Dispatchers.Unconfined)
        val viewModel = ArtworkSearchViewModel(
            artworkSearchProvider = provider,
            trackApplier = { _, _, _, _, _ -> null },
            scope = testScope,
        )

        // Song 1
        viewModel.startSessionForTrack(song1)
        assertEquals(song1.id, viewModel.currentSession.value?.targetTrackId)
        assertEquals("Artist A Alpha", viewModel.query.value)

        // Close Song 1
        viewModel.resetSession()
        assertNull(viewModel.currentSession.value)

        // Song 2
        viewModel.startSessionForTrack(song2)
        assertEquals(song2.id, viewModel.currentSession.value?.targetTrackId)
        assertEquals("Artist B Beta", viewModel.query.value)

        // Close Song 2
        viewModel.resetSession()
        assertNull(viewModel.currentSession.value)

        // Song 3
        viewModel.startSessionForTrack(song3)
        assertEquals(song3.id, viewModel.currentSession.value?.targetTrackId)
        assertEquals("Artist C Gamma", viewModel.query.value)
        assertEquals(ArtworkScope.TRACK, viewModel.currentSession.value?.scope)
    }

    /**
     * TEST 4 — CANCEL IS NON-DESTRUCTIVE
     *
     * Apply artwork to Song 1.
     * Open Song 2 artwork.
     * Cancel.
     * Assert Song 1 remains exactly unchanged.
     */
    @Test
    fun test4_cancelIsNonDestructive() = runBlocking {
        val song1Initial = makeTrack(1L, "Song 1", "Artist 1")
        var song1Persisted = song1Initial
        val song2Persisted = makeTrack(2L, "Song 2", "Artist 2")

        val song1Result = makeArtworkResult("s1_res", "Song 1", "Artist 1", "https://artwork.com/s1.jpg")
        val deleteCalledForSong1 = AtomicBoolean(false)

        val provider = object : ArtworkSearchProvider {
            override val name: String get() = "TestProvider"

            override suspend fun searchArtwork(query: String, limit: Int): List<ArtworkSearchResult> =
                if (query.contains("Song 1")) listOf(song1Result) else emptyList()
        }

        val testScope = CoroutineScope(Dispatchers.Unconfined)
        val viewModel = ArtworkSearchViewModel(
            artworkSearchProvider = provider,
            trackApplier = { trackId, url, prov, remoteId, res ->
                if (trackId == 1L) {
                    song1Persisted = song1Persisted.copy(
                        artworkUri = "file:///data/user/0/com.mus.android/files/artwork/art_manual_track_1.jpg",
                        artworkSource = ArtworkSource.MANUAL,
                        artworkProvider = prov,
                        artworkRemoteId = remoteId,
                        artworkLastUpdated = 1700000000L,
                    )
                    song1Persisted
                } else null
            },
            scope = testScope,
        )

        // 1. Apply artwork to Song 1
        viewModel.startSessionForTrack(song1Persisted)
        viewModel.selectResult(song1Result)
        viewModel.applyToTrack(song1Persisted.id)
        viewModel.resetSession()

        val song1AfterApply = song1Persisted
        assertEquals("file:///data/user/0/com.mus.android/files/artwork/art_manual_track_1.jpg", song1AfterApply.artworkUri)
        assertEquals(ArtworkSource.MANUAL, song1AfterApply.artworkSource)
        assertEquals("s1_res", song1AfterApply.artworkRemoteId)

        // 2. Open Song 2 artwork session
        viewModel.startSessionForTrack(song2Persisted)
        assertEquals(song2Persisted.id, viewModel.currentSession.value?.targetTrackId)

        // 3. User cancels Song 2 artwork session
        viewModel.resetSession()

        // 4. Assert Song 1 is completely untouched
        assertFalse("Cancel must never invoke delete or clear on another track", deleteCalledForSong1.get())
        assertEquals(song1AfterApply.artworkUri, song1Persisted.artworkUri)
        assertEquals(song1AfterApply.artworkSource, song1Persisted.artworkSource)
        assertEquals(song1AfterApply.artworkProvider, song1Persisted.artworkProvider)
        assertEquals(song1AfterApply.artworkRemoteId, song1Persisted.artworkRemoteId)
        assertEquals(song1AfterApply.artworkLastUpdated, song1Persisted.artworkLastUpdated)
        assertEquals(song1AfterApply.title, song1Persisted.title)
        assertEquals(song1AfterApply.artist, song1Persisted.artist)
        assertEquals(song1AfterApply.albumTitle, song1Persisted.albumTitle)
        assertEquals(song1AfterApply.albumArtist, song1Persisted.albumArtist)
        assertEquals(song1AfterApply.year, song1Persisted.year)
    }
}
