package com.mus.android.playback

import android.content.ComponentName
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
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
    private val repositoryProvider: javax.inject.Provider<com.mus.android.data.repository.MusicRepository>,
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

    private val _queueContext = MutableStateFlow(QueueContext())
    val queueContext: StateFlow<QueueContext> = _queueContext.asStateFlow()

    private var positionUpdateJob: Job? = null
    private var trackList: List<Track> = emptyList()

    private var pendingPlayTrack: Triple<Track, List<Track>, QueueContext>? = null

    // Playback threshold counting (30s or 50% of track)
    private var trackedTrackId: Long? = null
    private var trackPlayRecorded: Boolean = false
    private var cumulativePlayTimeMs: Long = 0L
    private var lastTickTimeMs: Long = 0L

    // Transition diagnostics
    private var lastTransitionIndex: Int = -1
    private var lastTransitionMediaId: String? = null

    init {
        scope.launch {
            try {
                repositoryProvider.get().idMigrations.collect { migrations ->
                    reconcileTrackIds(migrations)
                }
            } catch (e: Exception) {
                // Ignore initialization failures
            }
        }
    }

    /**
     * Safely updates track IDs in the active queue and current track if IDs were migrated during a scan.
     */
    fun reconcileTrackIds(migratedIds: Map<Long, Long>) {
        if (migratedIds.isEmpty()) return
        val currentQ = _queue.value
        val needsUpdate = currentQ.any { it.id in migratedIds } || (_currentTrack.value?.id in migratedIds)
        if (!needsUpdate) return

        val newQ = currentQ.map { track ->
            val newId = migratedIds[track.id]
            if (newId != null) track.copy(id = newId) else track
        }
        _queue.value = newQ
        trackList = newQ
        _currentTrack.value?.let { current ->
            val newId = migratedIds[current.id]
            if (newId != null) {
                _currentTrack.value = current.copy(id = newId)
            }
        }
    }

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
                pendingPlayTrack?.let { (t, q, c) ->
                    pendingPlayTrack = null
                    playTrack(t, q, c)
                }
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
                    val duration = controller.duration.coerceAtLeast(0)
                    _duration.value = duration

                    val track = _currentTrack.value
                    if (track != null) {
                        if (trackedTrackId != track.id) {
                            trackedTrackId = track.id
                            trackPlayRecorded = false
                            cumulativePlayTimeMs = 0L
                            lastTickTimeMs = 0L
                        }

                        if (controller.isPlaying) {
                            val now = System.currentTimeMillis()
                            if (lastTickTimeMs > 0) {
                                val delta = (now - lastTickTimeMs).coerceAtMost(1000)
                                cumulativePlayTimeMs += delta
                            }
                            lastTickTimeMs = now

                            if (!trackPlayRecorded) {
                                val thresholdMs = if (duration in 1..60_000) {
                                    duration / 2 // 50% for short tracks (< 1 min)
                                } else {
                                    30_000L // 30 seconds for standard tracks
                                }
                                if (cumulativePlayTimeMs >= thresholdMs) {
                                    trackPlayRecorded = true
                                    try {
                                        repositoryProvider.get().recordTrackPlayed(track.id)
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    }
                                }
                            }
                        } else {
                            lastTickTimeMs = 0L
                        }
                    }
                }
                delay(200) // Update ~5 times per second
            }
        }
    }

    // ── Playback controls ─────────────────────────────────────
    fun playTrack(
        track: Track,
        queue: List<Track> = listOf(track),
        context: QueueContext = QueueContext()
    ) {
        this.trackList = queue
        _queue.value = queue
        _currentTrack.value = track
        _queueContext.value = context
        val controller = mediaController
        if (controller == null) {
            pendingPlayTrack = Triple(track, queue, context)
            connect()
            return
        }
        val index = queue.indexOf(track).coerceAtLeast(0)
        _currentIndex.value = index
        lastTransitionIndex = index
        lastTransitionMediaId = track.id.toString()
        android.util.Log.i(TAG, "QUEUE_TRANSITION reason=PLAY_TRACK trackId=${track.id} title='${track.title}' toIndex=$index queueSize=${queue.size}")
        val mediaItems = queue.map { it.toMediaItem() }
        controller.setMediaItems(mediaItems, index, 0)
        controller.prepare()
        controller.play()
    }

    fun playTrackAtIndex(index: Int) {
        val controller = mediaController ?: return
        if (index == controller.currentMediaItemIndex && controller.isPlaying) {
            android.util.Log.d(TAG, "QUEUE_TRANSITION playTrackAtIndex: already playing at index $index, ignoring redundant seek")
            return
        }
        android.util.Log.i(TAG, "QUEUE_TRANSITION reason=PLAY_TRACK_AT_INDEX fromIndex=${controller.currentMediaItemIndex} toIndex=$index queueSize=${controller.mediaItemCount}")
        controller.seekTo(index, 0)
        controller.play()
    }

    fun play() { mediaController?.play() }
    fun pause() { mediaController?.pause() }

    fun togglePlayPause() {
        mediaController?.let {
            if (it.isPlaying) it.pause() else it.play()
        }
    }

    fun skipNext() {
        android.util.Log.i(TAG, "QUEUE_TRANSITION reason=SKIP_NEXT fromIndex=${mediaController?.currentMediaItemIndex} queueSize=${mediaController?.mediaItemCount}")
        mediaController?.seekToNextMediaItem()
    }
    fun skipPrevious() {
        android.util.Log.i(TAG, "QUEUE_TRANSITION reason=SKIP_PREVIOUS fromIndex=${mediaController?.currentMediaItemIndex} queueSize=${mediaController?.mediaItemCount}")
        mediaController?.seekToPreviousMediaItem()
    }

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

    fun playNext(track: Track) {
        val controller = mediaController
        if (controller != null && controller.mediaItemCount > 0) {
            val nextIndex = (controller.currentMediaItemIndex + 1).coerceAtMost(controller.mediaItemCount)
            controller.addMediaItem(nextIndex, track.toMediaItem())
            val list = trackList.toMutableList()
            if (nextIndex in 0..list.size) {
                list.add(nextIndex, track)
            } else {
                list.add(track)
            }
            trackList = list
            _queue.value = list
        } else {
            playTrack(track, listOf(track))
        }
    }

    fun addToQueue(track: Track) {
        val controller = mediaController
        if (controller != null && controller.mediaItemCount > 0) {
            controller.addMediaItem(track.toMediaItem())
            trackList = trackList + track
            _queue.value = trackList
        } else {
            playTrack(track, listOf(track))
        }
    }

    fun removeFromQueue(index: Int) {
        mediaController?.let {
            if (index in 0 until it.mediaItemCount) {
                val isCurrent = index == it.currentMediaItemIndex
                it.removeMediaItem(index)
                val mutable = trackList.toMutableList()
                if (index in mutable.indices) {
                    mutable.removeAt(index)
                }
                trackList = mutable
                _queue.value = mutable
                if (mutable.isEmpty()) {
                    _currentTrack.value = null
                    _isPlaying.value = false
                    _currentIndex.value = 0
                } else if (isCurrent) {
                    val newIndex = it.currentMediaItemIndex.coerceIn(0, mutable.size - 1)
                    _currentIndex.value = newIndex
                    _currentTrack.value = mutable.getOrNull(newIndex)
                }
            }
        }
    }

    fun clearQueue() {
        mediaController?.clearMediaItems()
        trackList = emptyList()
        _queue.value = emptyList()
        _currentTrack.value = null
        _isPlaying.value = false
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        val controller = mediaController ?: return
        if (fromIndex in 0 until controller.mediaItemCount && toIndex in 0 until controller.mediaItemCount) {
            controller.moveMediaItem(fromIndex, toIndex)
            val mutable = trackList.toMutableList()
            if (fromIndex in mutable.indices && toIndex in mutable.indices) {
                val item = mutable.removeAt(fromIndex)
                mutable.add(toIndex, item)
                trackList = mutable
                _queue.value = mutable
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
            val toIndex = controller.currentMediaItemIndex
            val fromIndex = lastTransitionIndex
            val toMediaId = mediaItem?.mediaId
            val fromMediaId = lastTransitionMediaId
            val reasonStr = when (reason) {
                Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> "MEDIA3_AUTO"
                Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> "SEEK"
                Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> "REPEAT"
                Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> "PLAYLIST_CHANGED"
                else -> "UNKNOWN($reason)"
            }

            if (fromIndex != -1 && toIndex < fromIndex && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                android.util.Log.w(TAG, "QUEUE_TRANSITION WARNING: Unexpected backward transition! reason=$reasonStr fromIndex=$fromIndex toIndex=$toIndex fromMediaId=$fromMediaId toMediaId=$toMediaId queueSize=${controller.mediaItemCount}")
            } else {
                android.util.Log.i(TAG, "QUEUE_TRANSITION reason=$reasonStr fromIndex=$fromIndex toIndex=$toIndex fromMediaId=$fromMediaId toMediaId=$toMediaId queueSize=${controller.mediaItemCount}")
            }

            lastTransitionIndex = toIndex
            lastTransitionMediaId = toMediaId

            _currentIndex.value = toIndex
            val mediaIdNum = toMediaId?.toLongOrNull()
            val found = if (mediaIdNum != null) trackList.find { it.id == mediaIdNum } else null
            if (found != null) {
                _currentTrack.value = found
            } else if (toIndex in trackList.indices) {
                _currentTrack.value = trackList[toIndex]
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

    // ── Artwork byte cache for cross-process notification artwork ──
    // SystemUI runs in a separate process and cannot resolve app-private file:// or content:// URIs.
    // We embed artwork bytes directly into MediaMetadata so the notification always shows artwork.
    private val artworkBytesCache = LruCache<String, ByteArray>(3)

    private fun loadArtworkBytes(artworkUri: String?): ByteArray? {
        if (artworkUri.isNullOrBlank()) return null
        artworkBytesCache.get(artworkUri)?.let { return it }

        return try {
            val uri = Uri.parse(artworkUri)
            val bytes = if (artworkUri.startsWith("file://")) {
                val path = uri.path ?: return null
                java.io.File(path).readBytes()
            } else {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }
            if (bytes != null && bytes.isNotEmpty()) {
                artworkBytesCache.put(artworkUri, bytes)
            }
            bytes
        } catch (e: Exception) {
            null
        }
    }

    // ── Helpers ────────────────────────────────────────────────
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun Track.toMediaItem(): MediaItem {
        val artBytes = loadArtworkBytes(artworkUri)
        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumArtist(albumArtist.takeIf { it.isNotBlank() })
            .setAlbumTitle(albumTitle)
            .setTrackNumber(trackNumber.takeIf { it > 0 })
            .setDiscNumber(discNumber.takeIf { it > 0 })
            .setGenre(genre)
            .setComposer(composer)
            .setRecordingYear(year.takeIf { it > 0 })

        // Always set artworkUri for in-app display
        artworkUri?.let { metadataBuilder.setArtworkUri(Uri.parse(it)) }

        // Embed artwork bytes for cross-process lock-screen/notification display
        if (artBytes != null) {
            metadataBuilder.setArtworkData(artBytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
        }

        return MediaItem.Builder()
            .setUri(Uri.parse(uri))
            .setMediaId(id.toString())
            .setMediaMetadata(metadataBuilder.build())
            .build()
    }

    fun release() {
        positionUpdateJob?.cancel()
        mediaController?.removeListener(playerListener)
        mediaController?.release()
        mediaController = null
    }

    companion object {
        private const val TAG = "PlaybackManager"
    }
}
