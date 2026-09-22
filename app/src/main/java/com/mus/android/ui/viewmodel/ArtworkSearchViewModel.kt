package com.mus.android.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mus.android.data.enrichment.provider.ArtworkSearchProvider
import com.mus.android.data.enrichment.provider.ArtworkSearchResult
import com.mus.android.data.model.Track
import com.mus.android.data.repository.MusicRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class ArtworkScope {
    TRACK,
    ALBUM,
}

data class ArtworkSession(
    val sessionId: Long,
    val targetTrackId: Long?,
    val targetAlbumId: Long?,
    val scope: ArtworkScope,
    val initialQuery: String,
)

sealed class ArtworkApplyState {
    object Idle : ArtworkApplyState()
    object Applying : ArtworkApplyState()
    object Success : ArtworkApplyState()
    data class Error(val message: String) : ArtworkApplyState()
}

@HiltViewModel
class ArtworkSearchViewModel(
    private val artworkSearchProvider: ArtworkSearchProvider,
    private val repository: MusicRepository?,
    private val testScope: kotlinx.coroutines.CoroutineScope? = null,
) : ViewModel() {

    @Inject
    constructor(
        artworkSearchProvider: ArtworkSearchProvider,
        repository: MusicRepository?,
    ) : this(artworkSearchProvider, repository, null)

    internal var trackArtworkApplier: (suspend (Long, String, String, String?, ArtworkSearchResult?) -> Track?)? = null
    internal var albumArtworkApplier: (suspend (Long, String, String, String?, ArtworkSearchResult?) -> Int)? = null

    /** Constructor for testing without requiring a full MusicRepository graph. */
    constructor(
        artworkSearchProvider: ArtworkSearchProvider,
        trackApplier: suspend (Long, String, String, String?, ArtworkSearchResult?) -> Track?,
        albumApplier: (suspend (Long, String, String, String?, ArtworkSearchResult?) -> Int)? = null,
        scope: kotlinx.coroutines.CoroutineScope? = null,
    ) : this(artworkSearchProvider, null, scope) {
        this.trackArtworkApplier = trackApplier
        this.albumArtworkApplier = albumApplier
    }

    private val scope: kotlinx.coroutines.CoroutineScope get() = testScope ?: viewModelScope

    private val _currentSession = MutableStateFlow<ArtworkSession?>(null)
    val currentSession: StateFlow<ArtworkSession?> = _currentSession.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _results = MutableStateFlow<List<ArtworkSearchResult>>(emptyList())
    val results: StateFlow<List<ArtworkSearchResult>> = _results.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _selectedResult = MutableStateFlow<ArtworkSearchResult?>(null)
    val selectedResult: StateFlow<ArtworkSearchResult?> = _selectedResult.asStateFlow()

    private val _applyState = MutableStateFlow<ArtworkApplyState>(ArtworkApplyState.Idle)
    val applyState: StateFlow<ArtworkApplyState> = _applyState.asStateFlow()

    private var searchJob: Job? = null
    private var sessionCounter = 0L

    /**
     * Starts or resets an artwork editing session for a specific track.
     * Atomically clears all prior queries, results, selections, and pending search jobs.
     */
    fun startSessionForTrack(track: Track) {
        val current = _currentSession.value
        if (current != null && current.targetTrackId == track.id && current.scope == ArtworkScope.TRACK) {
            return
        }

        searchJob?.cancel()
        searchJob = null
        val nextSessionId = ++sessionCounter
        val initialQuery = buildInitialTrackQuery(track)

        val newSession = ArtworkSession(
            sessionId = nextSessionId,
            targetTrackId = track.id,
            targetAlbumId = null,
            scope = ArtworkScope.TRACK,
            initialQuery = initialQuery,
        )

        _currentSession.value = newSession
        _query.value = initialQuery
        _results.value = emptyList()
        _selectedResult.value = null
        _isLoading.value = false
        _applyState.value = ArtworkApplyState.Idle

        if (initialQuery.isNotBlank()) {
            executeSearch(initialQuery, nextSessionId)
        }
    }

    /**
     * Starts or resets an artwork editing session for an album.
     * Atomically clears all prior queries, results, selections, and pending search jobs.
     */
    fun startSessionForAlbum(albumId: Long, albumTitle: String, artist: String) {
        val current = _currentSession.value
        if (current != null && current.targetAlbumId == albumId && current.scope == ArtworkScope.ALBUM) {
            return
        }

        searchJob?.cancel()
        searchJob = null
        val nextSessionId = ++sessionCounter
        val initialQuery = buildInitialAlbumQuery(artist, albumTitle)

        val newSession = ArtworkSession(
            sessionId = nextSessionId,
            targetTrackId = null,
            targetAlbumId = albumId,
            scope = ArtworkScope.ALBUM,
            initialQuery = initialQuery,
        )

        _currentSession.value = newSession
        _query.value = initialQuery
        _results.value = emptyList()
        _selectedResult.value = null
        _isLoading.value = false
        _applyState.value = ArtworkApplyState.Idle

        if (initialQuery.isNotBlank()) {
            executeSearch(initialQuery, nextSessionId)
        }
    }

    /**
     * Resets the current artwork session and clears all transient state.
     * Strictly non-destructive: does not mutate or clear persisted track artwork in Room.
     */
    fun resetSession() {
        searchJob?.cancel()
        searchJob = null
        ++sessionCounter
        _currentSession.value = null
        _query.value = ""
        _results.value = emptyList()
        _selectedResult.value = null
        _isLoading.value = false
        _applyState.value = ArtworkApplyState.Idle
    }

    /** Legacy alias for [startSessionForTrack]. */
    fun initQuery(track: Track) {
        startSessionForTrack(track)
    }

    private fun buildInitialTrackQuery(track: Track): String {
        val artist = track.artist.trim()
        val title = track.title.trim()
        return if (artist.isNotBlank() && title.isNotBlank()) "$artist $title"
        else title.ifBlank { artist }
    }

    private fun buildInitialAlbumQuery(artist: String, albumTitle: String): String {
        val art = artist.trim()
        val alb = albumTitle.trim()
        return if (art.isNotBlank() && alb.isNotBlank()) "$art $alb"
        else alb.ifBlank { art }
    }

    fun updateQuery(q: String) {
        val session = _currentSession.value ?: return
        val currentSessionId = session.sessionId
        _query.value = q
        searchJob?.cancel()
        if (q.isBlank()) {
            _results.value = emptyList()
            _isLoading.value = false
            return
        }
        searchJob = scope.launch {
            delay(400)
            if (_currentSession.value?.sessionId == currentSessionId && _query.value == q) {
                executeSearchInternal(q, currentSessionId)
            }
        }
    }

    fun searchArtwork(q: String = _query.value) {
        val session = _currentSession.value ?: return
        val currentSessionId = session.sessionId
        searchJob?.cancel()
        searchJob = scope.launch {
            executeSearchInternal(q, currentSessionId)
        }
    }

    private fun executeSearch(q: String, sessionId: Long) {
        searchJob?.cancel()
        searchJob = scope.launch {
            executeSearchInternal(q, sessionId)
        }
    }

    private suspend fun executeSearchInternal(q: String, sessionId: Long) {
        if (_currentSession.value?.sessionId != sessionId) return
        if (q.isBlank()) {
            _results.value = emptyList()
            _isLoading.value = false
            return
        }
        _isLoading.value = true
        try {
            val searchResults = artworkSearchProvider.searchArtwork(q, limit = 12)
            if (_currentSession.value?.sessionId == sessionId) {
                _results.value = searchResults
            }
        } catch (e: Exception) {
            if (_currentSession.value?.sessionId == sessionId) {
                _results.value = emptyList()
            }
        } finally {
            if (_currentSession.value?.sessionId == sessionId) {
                _isLoading.value = false
            }
        }
    }

    fun selectResult(result: ArtworkSearchResult) {
        if (_currentSession.value == null) return
        _selectedResult.value = result
    }

    fun clearSelection() {
        _selectedResult.value = null
    }

    /** Apply the selected artwork to a single track only. */
    fun applyToTrack(trackId: Long) {
        val session = _currentSession.value ?: return
        if (session.scope != ArtworkScope.TRACK || session.targetTrackId != trackId) return
        val result = _selectedResult.value ?: return
        val url = result.artworkUrl ?: return
        _applyState.value = ArtworkApplyState.Applying
        scope.launch {
            val updated = trackArtworkApplier?.invoke(trackId, url, result.provider, result.id, result)
                ?: repository?.applyManualArtwork(
                    trackId = trackId,
                    artworkUrl = url,
                    provider = result.provider,
                    remoteId = result.id,
                    result = result,
                )
            if (_currentSession.value?.sessionId == session.sessionId) {
                _applyState.value = if (updated != null) ArtworkApplyState.Success
                else ArtworkApplyState.Error("Failed to download artwork")
            }
        }
    }

    /** Apply the selected artwork to all tracks in the album. */
    fun applyToAlbum(albumId: Long) {
        val session = _currentSession.value ?: return
        if (session.scope != ArtworkScope.ALBUM || session.targetAlbumId != albumId) return
        val result = _selectedResult.value ?: return
        val url = result.artworkUrl ?: return
        _applyState.value = ArtworkApplyState.Applying
        scope.launch {
            val count = albumArtworkApplier?.invoke(albumId, url, result.provider, result.id, result)
                ?: repository?.applyManualArtworkToAlbum(
                    albumId = albumId,
                    artworkUrl = url,
                    provider = result.provider,
                    remoteId = result.id,
                    result = result,
                ) ?: 0
            if (_currentSession.value?.sessionId == session.sessionId) {
                _applyState.value = if (count > 0) ArtworkApplyState.Success
                else ArtworkApplyState.Error("Failed to apply artwork to album")
            }
        }
    }

    fun resetApplyState() {
        _applyState.value = ArtworkApplyState.Idle
    }
}
