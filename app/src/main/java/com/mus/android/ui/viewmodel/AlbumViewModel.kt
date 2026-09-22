package com.mus.android.ui.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mus.android.data.model.Album
import com.mus.android.data.model.Track
import com.mus.android.data.repository.MusicRepository
import com.mus.android.playback.PlaybackManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AlbumViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: MusicRepository,
    private val playbackManager: PlaybackManager,
) : ViewModel() {

    private val albumId: Long = savedStateHandle.get<Long>("albumId") ?: 0L

    private val _album = MutableStateFlow<Album?>(null)
    val album: StateFlow<Album?> = _album.asStateFlow()

    val tracks: StateFlow<List<Track>> = repository.getTracksByAlbum(albumId)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    init {
        refreshAlbum()
    }

    fun refreshAlbum() {
        viewModelScope.launch {
            _album.value = repository.getAlbumById(albumId)
        }
    }

    fun playAll(shuffle: Boolean = false) {
        val trackList = tracks.value
        if (trackList.isEmpty()) return
        if (shuffle) {
            val shuffled = trackList.shuffled()
            playbackManager.playTrack(shuffled.first(), shuffled)
        } else {
            playbackManager.playTrack(trackList.first(), trackList)
        }
    }

    fun playTrack(track: Track) {
        playbackManager.playTrack(track, tracks.value)
    }

    fun toggleFavorite(trackId: Long) {
        viewModelScope.launch { repository.toggleFavorite(trackId) }
    }
}
