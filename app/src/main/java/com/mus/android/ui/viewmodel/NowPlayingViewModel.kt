package com.mus.android.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mus.android.data.model.Track
import com.mus.android.data.repository.MusicRepository
import com.mus.android.playback.PlaybackManager
import com.mus.android.playback.QueueContext
import com.mus.android.ui.ambient.PaletteExtractor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NowPlayingViewModel @Inject constructor(
    private val playbackManager: PlaybackManager,
    private val repository: MusicRepository,
    private val paletteExtractor: PaletteExtractor,
) : ViewModel() {

    val currentTrack: StateFlow<Track?> = playbackManager.currentTrack
    val isPlaying: StateFlow<Boolean> = playbackManager.isPlaying
    val position: StateFlow<Long> = playbackManager.position
    val duration: StateFlow<Long> = playbackManager.duration
    val queue: StateFlow<List<Track>> = playbackManager.queue
    val currentIndex: StateFlow<Int> = playbackManager.currentIndex
    val queueContext: StateFlow<QueueContext> = playbackManager.queueContext
    val shuffleEnabled: StateFlow<Boolean> = playbackManager.shuffleEnabled
    val repeatMode: StateFlow<Int> = playbackManager.repeatMode

    private val _waveformData = MutableStateFlow<List<Float>>(emptyList())
    val waveformData: StateFlow<List<Float>> = _waveformData.asStateFlow()

    private val _artworkColors = MutableStateFlow(PaletteExtractor.DEFAULT_COLORS)
    val artworkColors: StateFlow<PaletteExtractor.ArtworkColors> = _artworkColors.asStateFlow()

    init {
        // Connect to the playback service
        playbackManager.connect()

        // When track changes, extract waveform and palette
        viewModelScope.launch {
            currentTrack.filterNotNull().distinctUntilChangedBy { it.id }.collect { track ->
                // Extract waveform
                launch {
                    val waveform = repository.getWaveform(track.id, track.uri)
                    _waveformData.value = waveform ?: emptyList()
                }
                // Extract palette
                launch {
                    val colors = paletteExtractor.extractColors(track.artworkUri)
                    _artworkColors.value = colors
                }
            }
        }
    }

    fun togglePlayPause() = playbackManager.togglePlayPause()
    fun skipNext() = playbackManager.skipNext()
    fun skipPrevious() = playbackManager.skipPrevious()
    fun seekTo(fraction: Float) {
        val dur = duration.value
        if (dur > 0) playbackManager.seekTo((fraction * dur).toLong())
    }
    fun toggleShuffle() = playbackManager.toggleShuffle()
    fun cycleRepeatMode() = playbackManager.cycleRepeatMode()
    fun toggleFavorite() {
        val track = currentTrack.value ?: return
        viewModelScope.launch { repository.toggleFavorite(track.id) }
    }

    fun playQueueItem(index: Int) = playbackManager.playTrackAtIndex(index)
    fun removeFromQueue(index: Int) = playbackManager.removeFromQueue(index)
    fun clearQueue() = playbackManager.clearQueue()
    fun moveQueueItem(fromIndex: Int, toIndex: Int) = playbackManager.moveQueueItem(fromIndex, toIndex)

    fun playTrackWithQueue(track: Track, queue: List<Track>) {
        playbackManager.playTrack(track, queue)
    }

    val playlists: StateFlow<List<com.mus.android.data.model.Playlist>> = repository.getAllPlaylists()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val existingPlaylistIdsForCurrentTrack: StateFlow<Set<Long>> = currentTrack
        .flatMapLatest { track ->
            if (track != null) {
                repository.observePlaylistIdsForTrack(track.id).map { it.toSet() }
            } else {
                flowOf(emptySet())
            }
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptySet())

    fun addTrackToPlaylist(playlistId: Long, onResult: (Boolean) -> Unit = {}) {
        val track = currentTrack.value
        if (track == null) {
            onResult(false)
            return
        }
        viewModelScope.launch {
            val added = repository.addTrackToPlaylist(playlistId, track.id)
            onResult(added)
        }
    }

    fun createPlaylistAndAddTrack(name: String, onDone: (Long) -> Unit = {}) {
        val track = currentTrack.value ?: return
        viewModelScope.launch {
            val id = repository.createPlaylist(name)
            repository.addTrackToPlaylist(id, track.id)
            onDone(id)
        }
    }
}
