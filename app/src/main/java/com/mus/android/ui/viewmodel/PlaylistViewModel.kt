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

    val currentPlaylistId: Long = playlistId

    private val _playlist = MutableStateFlow<Playlist?>(null)
    val playlist: StateFlow<Playlist?> = _playlist.asStateFlow()

    val tracks: StateFlow<List<Track>> = repository.getPlaylistTracks(playlistId)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val allPlaylists: StateFlow<List<Playlist>> = repository.getAllPlaylists()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val allLibraryTracks: StateFlow<List<Track>> = repository.getAllTracks()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _selectedTrackIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedTrackIds: StateFlow<Set<Long>> = _selectedTrackIds.asStateFlow()

    private val _isSelectionMode = MutableStateFlow(false)
    val isSelectionMode: StateFlow<Boolean> = _isSelectionMode.asStateFlow()

    init {
        viewModelScope.launch {
            _playlist.value = repository.getPlaylistById(playlistId)
        }
    }

    fun startSelectionMode(initialTrackId: Long) {
        _selectedTrackIds.value = setOf(initialTrackId)
        _isSelectionMode.value = true
    }

    fun toggleTrackSelection(trackId: Long) {
        val current = _selectedTrackIds.value
        val next = if (trackId in current) current - trackId else current + trackId
        if (next.isEmpty()) {
            _selectedTrackIds.value = emptySet()
            _isSelectionMode.value = false
        } else {
            _selectedTrackIds.value = next
        }
    }

    fun selectAll() {
        _selectedTrackIds.value = tracks.value.map { it.id }.toSet()
    }

    fun clearSelection() {
        _selectedTrackIds.value = emptySet()
        _isSelectionMode.value = false
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

    fun removeSelectedTracks(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            val ids = _selectedTrackIds.value.toList()
            if (ids.isNotEmpty()) {
                repository.removeTracksFromPlaylist(playlistId, ids)
                clearSelection()
                onDone()
            }
        }
    }

    fun addTracksToCurrentPlaylist(trackIds: List<Long>, onDone: (Int) -> Unit = {}) {
        viewModelScope.launch {
            if (trackIds.isNotEmpty()) {
                val added = repository.addTracksToPlaylist(playlistId, trackIds)
                onDone(added)
            } else {
                onDone(0)
            }
        }
    }

    fun moveSelectedTracks(destPlaylistId: Long, onDone: (Int) -> Unit = {}) {
        viewModelScope.launch {
            val ids = _selectedTrackIds.value.toList()
            if (ids.isNotEmpty()) {
                val moved = repository.moveTracks(playlistId, destPlaylistId, ids)
                clearSelection()
                onDone(moved)
            } else {
                onDone(0)
            }
        }
    }

    fun copySelectedTracks(destPlaylistId: Long, onDone: (Int) -> Unit = {}) {
        viewModelScope.launch {
            val ids = _selectedTrackIds.value.toList()
            if (ids.isNotEmpty()) {
                val copied = repository.copyTracks(destPlaylistId, ids)
                clearSelection()
                onDone(copied)
            } else {
                onDone(0)
            }
        }
    }

    fun createPlaylistAndMoveSelected(name: String, onDone: (Int) -> Unit = {}) {
        viewModelScope.launch {
            val ids = _selectedTrackIds.value.toList()
            if (ids.isNotEmpty()) {
                val newId = repository.createPlaylist(name)
                val moved = repository.moveTracks(playlistId, newId, ids)
                clearSelection()
                onDone(moved)
            } else {
                onDone(0)
            }
        }
    }

    fun createPlaylistAndCopySelected(name: String, onDone: (Int) -> Unit = {}) {
        viewModelScope.launch {
            val ids = _selectedTrackIds.value.toList()
            if (ids.isNotEmpty()) {
                val newId = repository.createPlaylist(name)
                val copied = repository.copyTracks(newId, ids)
                clearSelection()
                onDone(copied)
            } else {
                onDone(0)
            }
        }
    }

    fun playNextSelected() {
        val selected = tracks.value.filter { it.id in _selectedTrackIds.value }
        if (selected.isNotEmpty()) {
            selected.reversed().forEach { playbackManager.playNext(it) }
            clearSelection()
        }
    }

    fun addToQueueSelected() {
        val selected = tracks.value.filter { it.id in _selectedTrackIds.value }
        if (selected.isNotEmpty()) {
            selected.forEach { playbackManager.addToQueue(it) }
            clearSelection()
        }
    }

    fun renamePlaylist(newName: String) {
        viewModelScope.launch {
            repository.renamePlaylist(playlistId, newName)
            _playlist.value = repository.getPlaylistById(playlistId)
        }
    }

    fun deletePlaylist(onDeleted: () -> Unit = {}) {
        viewModelScope.launch {
            repository.deletePlaylist(playlistId)
            onDeleted()
        }
    }
}
