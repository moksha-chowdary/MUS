package com.mus.android.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mus.android.data.model.Playlist
import com.mus.android.data.model.Track
import com.mus.android.data.repository.MusicRepository
import com.mus.android.playback.PlaybackManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SongMenuViewModel @Inject constructor(
    private val repository: MusicRepository,
    private val playbackManager: PlaybackManager,
) : ViewModel() {

    val playlists: StateFlow<List<Playlist>> = repository.getAllPlaylists()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun play(track: Track) {
        playbackManager.playTrack(track, listOf(track))
    }

    fun playNext(track: Track) {
        playbackManager.playNext(track)
    }

    fun addToQueue(track: Track) {
        playbackManager.addToQueue(track)
    }

    fun toggleFavorite(trackId: Long) {
        viewModelScope.launch {
            repository.toggleFavorite(trackId)
        }
    }

    fun addToPlaylist(playlistId: Long, trackId: Long, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val added = repository.addTrackToPlaylist(playlistId, trackId)
            onResult(added)
        }
    }

    fun createPlaylistAndAddTrack(name: String, trackId: Long) {
        viewModelScope.launch {
            val newId = repository.createPlaylist(name)
            repository.addTrackToPlaylist(newId, trackId)
        }
    }

    suspend fun getPlaylistIdsForTrack(trackId: Long): List<Long> {
        return repository.getPlaylistIdsForTrack(trackId)
    }

    fun createPlaylistAndMoveTrack(name: String, fromPlaylistId: Long, trackId: Long, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            val newId = repository.createPlaylist(name)
            repository.moveTracks(fromPlaylistId, newId, listOf(trackId))
            onDone()
        }
    }

    fun moveBetweenPlaylists(fromPlaylistId: Long, toPlaylistId: Long, trackId: Long, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            repository.moveTracks(fromPlaylistId, toPlaylistId, listOf(trackId))
            onDone()
        }
    }

    fun copyToPlaylist(toPlaylistId: Long, trackId: Long, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            repository.copyTracks(toPlaylistId, listOf(trackId))
            onDone()
        }
    }

    fun deleteFromLibrary(trackId: Long) {
        viewModelScope.launch {
            repository.deleteTrack(trackId)
        }
    }

    fun removeFromPlaylist(playlistId: Long, trackId: Long) {
        viewModelScope.launch {
            repository.removeTrackFromPlaylist(playlistId, trackId)
        }
    }
}
