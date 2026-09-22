package com.mus.android.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mus.android.data.enrichment.provider.OnlineMusicSearchProvider
import com.mus.android.data.enrichment.provider.OnlineSearchResult
import com.mus.android.data.model.DownloadQueueItem
import com.mus.android.data.model.DownloadStatus
import com.mus.android.data.repository.MusicRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class DiscoveryViewModel @Inject constructor(
    private val providers: Set<@JvmSuppressWildcards OnlineMusicSearchProvider>,
    private val repository: MusicRepository,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _onlineResults = MutableStateFlow<List<OnlineSearchResult>>(emptyList())
    val onlineResults: StateFlow<List<OnlineSearchResult>> = _onlineResults.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    val downloadQueue: StateFlow<List<DownloadQueueItem>> = repository.getDownloadQueue()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _addToQueueResult = MutableStateFlow<String?>(null)
    val addToQueueResult: StateFlow<String?> = _addToQueueResult.asStateFlow()

    private var searchJob: Job? = null

    fun updateQuery(q: String) {
        _query.value = q
        searchJob?.cancel()
        if (q.isBlank()) {
            _onlineResults.value = emptyList()
            return
        }
        searchJob = viewModelScope.launch {
            delay(500) // debounce
            performSearch(q)
        }
    }

    fun searchOnline(q: String = _query.value) {
        searchJob?.cancel()
        if (q.isBlank()) {
            _onlineResults.value = emptyList()
            return
        }
        searchJob = viewModelScope.launch {
            performSearch(q)
        }
    }

    private suspend fun performSearch(q: String) {
        _isSearching.value = true
        try {
            val results = withContext(Dispatchers.IO) {
                // Query all configured providers in parallel and merge results
                providers.flatMap { provider ->
                    try {
                        provider.search(q, limit = 20)
                    } catch (e: Exception) {
                        emptyList()
                    }
                }
            }
            _onlineResults.value = results
        } finally {
            _isSearching.value = false
        }
    }

    /**
     * Adds an online result to the "MUS — To Download" planning list.
     * Deduplicates automatically. Never downloads audio.
     */
    fun addToDownloadQueue(result: OnlineSearchResult) {
        viewModelScope.launch {
            val added = repository.addToDownloadQueue(result)
            _addToQueueResult.value = if (added) "Added to MUS — To Download" else "Already in To Download"
        }
    }

    fun removeFromDownloadQueue(id: String) {
        viewModelScope.launch {
            repository.removeFromDownloadQueue(id)
        }
    }

    fun markDownloaded(id: String) {
        viewModelScope.launch {
            repository.updateDownloadQueueStatus(id, DownloadStatus.DOWNLOADED)
        }
    }

    fun markAddedToLibrary(id: String) {
        viewModelScope.launch {
            repository.updateDownloadQueueStatus(id, DownloadStatus.ADDED_TO_LIBRARY)
        }
    }

    fun clearAddToQueueResult() {
        _addToQueueResult.value = null
    }
}
