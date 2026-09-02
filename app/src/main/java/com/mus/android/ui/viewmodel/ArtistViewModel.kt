package com.mus.android.ui.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mus.android.data.model.Artist
import com.mus.android.data.model.Album
import com.mus.android.data.model.Track
import com.mus.android.data.repository.MusicRepository
import com.mus.android.playback.PlaybackManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ArtistViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: MusicRepository,
    private val playbackManager: PlaybackManager,
) : ViewModel() {

    private val artistId: Long = savedStateHandle.get<Long>("artistId") ?: 0L

    private val _artist = MutableStateFlow<Artist?>(null)
    val artist: StateFlow<Artist?> = _artist.asStateFlow()

    private val _albums = MutableStateFlow<List<Album>>(emptyList())
    val albums: StateFlow<List<Album>> = _albums.asStateFlow()

    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    val tracks: StateFlow<List<Track>> = _tracks.asStateFlow()

    init {
        viewModelScope.launch {
            val a = repository.getArtistById(artistId) ?: return@launch
            _artist.value = a
            repository.getAlbumsByArtist(a.name).collect { _albums.value = it }
        }
        viewModelScope.launch {
            val a = repository.getArtistById(artistId) ?: return@launch
            repository.getTracksByArtist(a.name).collect { _tracks.value = it }
        }
    }

    fun playTrack(track: Track) {
        playbackManager.playTrack(track, tracks.value)
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
}
