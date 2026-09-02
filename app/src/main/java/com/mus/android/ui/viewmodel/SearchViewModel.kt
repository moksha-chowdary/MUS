package com.mus.android.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mus.android.data.model.Album
import com.mus.android.data.model.Artist
import com.mus.android.data.model.Track
import com.mus.android.data.repository.MusicRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import javax.inject.Inject

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: MusicRepository,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val debouncedQuery = _query
        .debounce(300)
        .distinctUntilChanged()

    val trackResults: StateFlow<List<Track>> = debouncedQuery
        .flatMapLatest { q ->
            if (q.isBlank()) flowOf(emptyList())
            else repository.searchTracks(q)
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val albumResults: StateFlow<List<Album>> = debouncedQuery
        .flatMapLatest { q ->
            if (q.isBlank()) flowOf(emptyList())
            else repository.searchAlbums(q)
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val artistResults: StateFlow<List<Artist>> = debouncedQuery
        .flatMapLatest { q ->
            if (q.isBlank()) flowOf(emptyList())
            else repository.searchArtists(q)
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val hasResults: StateFlow<Boolean> = combine(trackResults, albumResults, artistResults) { t, al, ar ->
        t.isNotEmpty() || al.isNotEmpty() || ar.isNotEmpty()
    }.stateIn(viewModelScope, SharingStarted.Lazily, false)

    fun updateQuery(newQuery: String) {
        _query.value = newQuery
    }
}
