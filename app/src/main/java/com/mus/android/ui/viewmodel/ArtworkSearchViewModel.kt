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

sealed class ArtworkApplyState {
    object Idle : ArtworkApplyState()
    object Applying : ArtworkApplyState()
    object Success : ArtworkApplyState()
    data class Error(val message: String) : ArtworkApplyState()
}

@HiltViewModel
class ArtworkSearchViewModel @Inject constructor(
    private val artworkSearchProvider: ArtworkSearchProvider,
    private val repository: MusicRepository,
) : ViewModel() {

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

    fun initQuery(track: Track) {
        val initial = buildInitialQuery(track)
        _query.value = initial
        searchArtwork(initial)
    }

    private fun buildInitialQuery(track: Track): String {
        val artist = track.artist.trim()
        val title = track.title.trim()
        return if (artist.isNotBlank() && title.isNotBlank()) "$artist $title"
        else title.ifBlank { artist }
    }

    fun updateQuery(q: String) {
        _query.value = q
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(400)
            if (_query.value == q && q.isNotBlank()) {
                performSearch(q)
            }
        }
    }

    fun searchArtwork(q: String = _query.value) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            performSearch(q)
        }
    }

    private suspend fun performSearch(q: String) {
        if (q.isBlank()) {
            _results.value = emptyList()
            return
        }
        _isLoading.value = true
        try {
            _results.value = artworkSearchProvider.searchArtwork(q, limit = 12)
        } finally {
            _isLoading.value = false
        }
    }

    fun selectResult(result: ArtworkSearchResult) {
        _selectedResult.value = result
    }

    fun clearSelection() {
        _selectedResult.value = null
    }

    /** Apply the selected artwork to a single track only. */
    fun applyToTrack(trackId: Long) {
        val result = _selectedResult.value ?: return
        val url = result.artworkUrl ?: return
        _applyState.value = ArtworkApplyState.Applying
        viewModelScope.launch {
            val updated = repository.applyManualArtwork(
                trackId = trackId,
                artworkUrl = url,
                provider = result.provider,
                remoteId = result.id,
            )
            _applyState.value = if (updated != null) ArtworkApplyState.Success
            else ArtworkApplyState.Error("Failed to download artwork")
        }
    }

    /** Apply the selected artwork to all tracks in the album. */
    fun applyToAlbum(albumId: Long) {
        val result = _selectedResult.value ?: return
        val url = result.artworkUrl ?: return
        _applyState.value = ArtworkApplyState.Applying
        viewModelScope.launch {
            val count = repository.applyManualArtworkToAlbum(
                albumId = albumId,
                artworkUrl = url,
                provider = result.provider,
                remoteId = result.id,
            )
            _applyState.value = if (count > 0) ArtworkApplyState.Success
            else ArtworkApplyState.Error("Failed to apply artwork to album")
        }
    }

    fun resetApplyState() {
        _applyState.value = ArtworkApplyState.Idle
    }
}
