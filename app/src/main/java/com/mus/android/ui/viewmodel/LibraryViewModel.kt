package com.mus.android.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mus.android.data.db.DownloadedTrackDao
import com.mus.android.data.download.TrackDownloadManager
import com.mus.android.data.model.Album
import com.mus.android.data.model.Artist
import com.mus.android.data.model.Playlist
import com.mus.android.data.model.Track
import com.mus.android.data.repository.MusicRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repository: MusicRepository,
    private val downloadedTrackDao: DownloadedTrackDao,
) : ViewModel() {

    val allTracks: StateFlow<List<Track>> = repository.getAllTracks()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /** Local tracks whose artist+title key exists in the download index (FLAC/Muzic downloads). */
    val downloadedTracks: StateFlow<List<Track>> = combine(allTracks, downloadedTrackDao.getAll()) { tracks, rows ->
        val keys = rows.mapTo(HashSet(rows.size)) { it.trackKey }
        tracks.filter { keys.contains(TrackDownloadManager.makeDownloadKey(it.title, it.artist)) }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _selectedLanguageFilter = MutableStateFlow<String>("All")
    val selectedLanguageFilter: StateFlow<String> = _selectedLanguageFilter.asStateFlow()

    val filteredTracks: StateFlow<List<Track>> = combine(
        allTracks,
        downloadedTracks,
        _selectedLanguageFilter,
    ) { tracks, downloaded, filter ->
        when (filter) {
            "All" -> tracks
            "Downloaded" -> downloaded
            "Hindi" -> tracks.filter { it.language.equals("Hindi", ignoreCase = true) }
            "English" -> tracks.filter { it.language.equals("English", ignoreCase = true) }
            "Other", "Regional" -> tracks.filter {
                it.language.equals("Other", ignoreCase = true) ||
                it.language.equals("Regional", ignoreCase = true)
            }
            "Unknown" -> tracks.filter {
                it.language.equals("Unknown", ignoreCase = true) || it.language.isBlank()
            }
            else -> tracks
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val albums: StateFlow<List<Album>> = repository.getAllAlbums()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val artists: StateFlow<List<Artist>> = repository.getAllArtists()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val playlists: StateFlow<List<Playlist>> = repository.getAllPlaylists()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val favorites: StateFlow<List<Track>> = repository.getFavorites()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val pendingDownloadCount: StateFlow<Int> = repository.observePendingDownloadCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun setLanguageFilter(language: String) {
        _selectedLanguageFilter.value = language
    }

    fun toggleFavorite(trackId: Long) {
        viewModelScope.launch { repository.toggleFavorite(trackId) }
    }

    fun createPlaylist(name: String) {
        viewModelScope.launch { repository.createPlaylist(name) }
    }

    fun deletePlaylist(playlistId: Long) {
        viewModelScope.launch { repository.deletePlaylist(playlistId) }
    }
}
