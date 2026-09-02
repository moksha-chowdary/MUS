package com.mus.android.ui.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mus.android.data.model.Playlist
import com.mus.android.data.model.Track
import com.mus.android.data.repository.MusicRepository
import com.mus.android.playback.PlaybackManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlaylistViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: MusicRepository,
    private val playbackManager: PlaybackManager,
) : ViewModel() {

    private val playlistId: Long = savedStateHandle.get<Long>("playlistId") ?: 0L

    private val _playlist = MutableStateFlow<Playlist?>(null)
    val playlist: StateFlow<Playlist?> = _playlist.asStateFlow()

    val tracks: StateFlow<List<Track>> = repository.getPlaylistTracks(playlistId)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    init {
        viewModelScope.launch {
            _playlist.value = repository.getPlaylistById(playlistId)
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

    fun removeTrack(trackId: Long) {
        viewModelScope.launch {
            repository.removeTrackFromPlaylist(playlistId, trackId)
        }
    }

    fun renamePlaylist(newName: String) {
        viewModelScope.launch {
            repository.renamePlaylist(playlistId, newName)
            _playlist.value = repository.getPlaylistById(playlistId)
        }
    }

    fun deletePlaylist() {
        viewModelScope.launch {
            repository.deletePlaylist(playlistId)
        }
    }
}
