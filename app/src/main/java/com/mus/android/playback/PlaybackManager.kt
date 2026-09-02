package com.mus.android.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.mus.android.data.model.Track
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.google.common.util.concurrent.MoreExecutors
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaybackManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private var mediaController: MediaController? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // ── Public state ──────────────────────────────────────────
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentTrack = MutableStateFlow<Track?>(null)
    val currentTrack: StateFlow<Track?> = _currentTrack.asStateFlow()

    private val _position = MutableStateFlow(0L)
    val position: StateFlow<Long> = _position.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    private val _queue = MutableStateFlow<List<Track>>(emptyList())
    val queue: StateFlow<List<Track>> = _queue.asStateFlow()

    private val _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _shuffleEnabled = MutableStateFlow(false)
    val shuffleEnabled: StateFlow<Boolean> = _shuffleEnabled.asStateFlow()

    private val _repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF)
    val repeatMode: StateFlow<Int> = _repeatMode.asStateFlow()

    private var positionUpdateJob: Job? = null
    private var trackList: List<Track> = emptyList()

    // ── Connection ────────────────────────────────────────────
    fun connect() {
        if (mediaController != null) return
        val token = SessionToken(
            context,
            ComponentName(context, MusPlaybackService::class.java)
        )
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            try {
                mediaController = future.get()
                mediaController?.addListener(playerListener)
                startPositionUpdates()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, MoreExecutors.directExecutor())
    }

    private fun startPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = scope.launch {
            while (isActive) {
                mediaController?.let { controller ->
                    _position.value = controller.currentPosition
                    _duration.value = controller.duration.coerceAtLeast(0)
                }
                delay(200) // Update ~5 times per second
            }
        }
    }

    // ── Playback controls ─────────────────────────────────────
    fun playTrack(track: Track, queue: List<Track> = listOf(track)) {
        this.trackList = queue
        _queue.value = queue
        val index = queue.indexOf(track).coerceAtLeast(0)

        val mediaItems = queue.map { it.toMediaItem() }
        mediaController?.apply {
            setMediaItems(mediaItems, index, 0)
            prepare()
            play()
        }
    }

    fun playTrackAtIndex(index: Int) {
        mediaController?.apply {
            seekTo(index, 0)
            play()
        }
    }

    fun play() { mediaController?.play() }
    fun pause() { mediaController?.pause() }

    fun togglePlayPause() {
        mediaController?.let {
            if (it.isPlaying) it.pause() else it.play()
        }
    }

    fun skipNext() { mediaController?.seekToNextMediaItem() }
    fun skipPrevious() { mediaController?.seekToPreviousMediaItem() }

    fun seekTo(positionMs: Long) { mediaController?.seekTo(positionMs) }

    fun toggleShuffle() {
        mediaController?.let {
            val newShuffle = !it.shuffleModeEnabled
            it.shuffleModeEnabled = newShuffle
            _shuffleEnabled.value = newShuffle
        }
    }

    fun cycleRepeatMode() {
        mediaController?.let {
            val next = when (it.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
            it.repeatMode = next
            _repeatMode.value = next
        }
    }

    fun addToQueue(track: Track) {
        mediaController?.let {
            it.addMediaItem(track.toMediaItem())
            trackList = trackList + track
            _queue.value = trackList
        }
    }

    fun removeFromQueue(index: Int) {
        mediaController?.let {
            if (index in 0 until it.mediaItemCount) {
                it.removeMediaItem(index)
                trackList = trackList.toMutableList().apply { removeAt(index) }
                _queue.value = trackList
            }
        }
    }

    // ── Listener ──────────────────────────────────────────────
    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val controller = mediaController ?: return
            val index = controller.currentMediaItemIndex
            _currentIndex.value = index
            if (index in trackList.indices) {
                _currentTrack.value = trackList[index]
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                _isPlaying.value = false
            }
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            _shuffleEnabled.value = shuffleModeEnabled
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            _repeatMode.value = repeatMode
        }
    }

    // ── Helpers ────────────────────────────────────────────────
    private fun Track.toMediaItem(): MediaItem {
        return MediaItem.Builder()
            .setUri(Uri.parse(uri))
            .setMediaId(id.toString())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setAlbumTitle(albumTitle)
                    .setArtworkUri(artworkUri?.let { Uri.parse(it) })
                    .build()
            )
            .build()
    }

    fun release() {
        positionUpdateJob?.cancel()
        mediaController?.removeListener(playerListener)
        mediaController?.release()
        mediaController = null
    }
}
