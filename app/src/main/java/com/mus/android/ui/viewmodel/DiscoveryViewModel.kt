package com.mus.android.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mus.android.data.download.TrackDownloadManager
import com.mus.android.data.enrichment.provider.OnlineMusicSearchProvider
import com.mus.android.data.enrichment.provider.OnlineSearchResult
import com.mus.android.data.model.DownloadQueueItem
import com.mus.android.data.model.DownloadStatus
import com.mus.android.data.music.InnerTubeMusicApi
import com.mus.android.data.music.YouTubeMusicTrack
import com.mus.android.data.music.YouTubePlaylistSummary
import com.mus.android.data.repository.MusicRepository
import com.mus.android.data.ytmusic.YtMusicAuthManager
import com.mus.android.playback.PlaybackManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** A YT Music playlist opened in the app, with its tracks ready for playback. */
data class YtPlaylistSelection(
    val playlist: YouTubePlaylistSummary,
    val tracks: List<OnlineSearchResult>,
)

@HiltViewModel
class DiscoveryViewModel @Inject constructor(
    private val providers: Set<@JvmSuppressWildcards OnlineMusicSearchProvider>,
    private val repository: MusicRepository,
    private val playbackManager: PlaybackManager,
    private val downloadManager: TrackDownloadManager,
    private val innerTube: InnerTubeMusicApi,
    private val ytAuth: YtMusicAuthManager,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _onlineResults = MutableStateFlow<List<OnlineSearchResult>>(emptyList())
    val onlineResults: StateFlow<List<OnlineSearchResult>> = _onlineResults.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    val downloadQueue: StateFlow<List<DownloadQueueItem>> = repository.getDownloadQueue()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val pendingDownloadCount: StateFlow<Int> = repository.observePendingDownloadCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /** Live progress from the download pipeline (keyed by trackKey). */
    val downloadProgress = downloadManager.downloads

    private val _addToQueueResult = MutableStateFlow<String?>(null)
    val addToQueueResult: StateFlow<String?> = _addToQueueResult.asStateFlow()

    // ── YouTube Music library playlists (signed-in account) ──────────────

    private val _ytPlaylists = MutableStateFlow<List<YouTubePlaylistSummary>>(emptyList())
    val ytPlaylists: StateFlow<List<YouTubePlaylistSummary>> = _ytPlaylists.asStateFlow()

    private val _ytPlaylistsLoading = MutableStateFlow(false)
    val ytPlaylistsLoading: StateFlow<Boolean> = _ytPlaylistsLoading.asStateFlow()

    private val _selectedYtPlaylist = MutableStateFlow<YtPlaylistSelection?>(null)
    val selectedYtPlaylist: StateFlow<YtPlaylistSelection?> = _selectedYtPlaylist.asStateFlow()

    private val _ytPlaylistLoading = MutableStateFlow(false)
    val ytPlaylistLoading: StateFlow<Boolean> = _ytPlaylistLoading.asStateFlow()

    private var playlistsLoadAttempted = false

    private fun ytConnected(): Boolean = ytAuth.connection.value.isConnected

    /** Loads the account's YT Music playlists once per screen visit.
     *  No-op when signed out; failures hide the section silently. */
    fun loadYtPlaylistsIfConnected() {
        if (!ytConnected() || playlistsLoadAttempted) return
        playlistsLoadAttempted = true
        _ytPlaylistsLoading.value = true
        viewModelScope.launch {
            try {
                _ytPlaylists.value = withContext(Dispatchers.IO) {
                    innerTube.fetchLibraryPlaylists()
                }
            } catch (_: Exception) {
                _ytPlaylists.value = emptyList()
            } finally {
                _ytPlaylistsLoading.value = false
            }
        }
    }

    fun openYtPlaylist(playlist: YouTubePlaylistSummary) {
        if (_ytPlaylistLoading.value) return
        _ytPlaylistLoading.value = true
        viewModelScope.launch {
            val tracks = try {
                withContext(Dispatchers.IO) {
                    innerTube.fetchPlaylistTracks(playlist.id, limit = 300)
                }.map { it.toOnlineResult() }
            } catch (_: Exception) {
                emptyList()
            }
            _selectedYtPlaylist.value = YtPlaylistSelection(playlist, tracks)
            _ytPlaylistLoading.value = false
        }
    }

    fun closeYtPlaylist() {
        _selectedYtPlaylist.value = null
    }

    /** Plays one playlist track with the rest of the playlist as the queue. */
    fun playYtPlaylistTrack(selection: YtPlaylistSelection, start: OnlineSearchResult) {
        playbackManager.playOnline(start, selection.tracks.ifEmpty { listOf(start) })
    }

    fun addYtPlaylistTrackToQueue(track: OnlineSearchResult) {
        playbackManager.addOnlineToQueue(track)
    }

    private fun YouTubeMusicTrack.toOnlineResult(): OnlineSearchResult = OnlineSearchResult(
        id = videoId,
        title = title,
        artist = artist,
        album = album,
        artworkUrl = artworkUrl,
        source = "YouTube Music",
        sourceId = videoId,
        sourceUrl = "https://music.youtube.com/watch?v=$videoId",
        durationMs = (durationSeconds ?: 0) * 1_000L,
    )

    private var searchJob: Job? = null

    fun updateQuery(q: String) {
        _query.value = q
        searchJob?.cancel()
        if (q.isBlank()) {
            _onlineResults.value = emptyList()
            return
        }
        searchJob = viewModelScope.launch {
            delay(500) // debounce
            performSearch(q)
        }
    }

    fun searchOnline(q: String = _query.value) {
        searchJob?.cancel()
        if (q.isBlank()) {
            _onlineResults.value = emptyList()
            return
        }
        searchJob = viewModelScope.launch {
            performSearch(q)
        }
    }

    private suspend fun performSearch(q: String) {
        _isSearching.value = true
        try {
            val results = withContext(Dispatchers.IO) {
                // Query all configured providers in parallel and merge results
                providers.flatMap { provider ->
                    try {
                        provider.search(q, limit = 20)
                    } catch (e: Exception) {
                        emptyList()
                    }
                }
            }
            _onlineResults.value = results
        } finally {
            _isSearching.value = false
        }
    }

    /**
     * Streams the online result through the InnerTube-backed playback
     * resolver (yt:// → googlevideo + PoToken).
     */
    fun playOnline(result: OnlineSearchResult) {
        playbackManager.playOnline(result, _onlineResults.value.filter {
            it.source.startsWith("YouTube", ignoreCase = true)
        }.ifEmpty { listOf(result) })
    }

    fun addOnlineToQueue(result: OnlineSearchResult) {
        playbackManager.addOnlineToQueue(result)
    }

    /**
     * Starts a real download: stream resolution → parallel transfer →
     * lossless remux → tagging → MediaStore → Room trackKey index.
     */
    fun downloadOnline(result: OnlineSearchResult) {
        val videoId = result.sourceId.ifBlank { result.id }
        downloadManager.downloadTrack(
            title = result.title,
            artist = result.artist,
            album = result.album,
            artworkUrl = result.artworkUrl,
            videoId = videoId,
            durationMs = result.durationMs.takeIf { it > 0 },
            queueItemId = "${result.source}_${videoId}",
        )
    }

    /** UI state helper: current download state for a result's videoId. */
    fun downloadStateFor(result: OnlineSearchResult): String? {
        val videoId = result.sourceId.ifBlank { result.id }
        val key = TrackDownloadManager.makeDownloadKey(result.title, result.artist)
        val progress = downloadProgress.value[key] ?: return null
        return when {
            progress.isFinished && progress.error == null -> "DONE"
            progress.error != null -> "ERROR"
            else -> "DOWNLOADING"
        }.let { if (it == "DONE") it else it }
    }

    /**
     * Adds an online result to the "MUS — To Download" planning list.
     * Deduplicates automatically.
     */
    fun addToDownloadQueue(result: OnlineSearchResult) {
        viewModelScope.launch {
            val added = repository.addToDownloadQueue(result)
            _addToQueueResult.value = if (added) "Added to MUS — To Download" else "Already in To Download"
        }
    }

    fun removeFromDownloadQueue(id: String) {
        viewModelScope.launch {
            repository.removeFromDownloadQueue(id)
        }
    }

    fun markDownloaded(id: String) {
        viewModelScope.launch {
            repository.updateDownloadQueueStatus(id, DownloadStatus.DOWNLOADED)
        }
    }

    fun markAddedToLibrary(id: String) {
        viewModelScope.launch {
            repository.updateDownloadQueueStatus(id, DownloadStatus.ADDED_TO_LIBRARY)
        }
    }

    fun clearAddToQueueResult() {
        _addToQueueResult.value = null
    }
}
