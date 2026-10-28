package com.mus.android.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mus.android.data.db.DownloadedTrackDao
import com.mus.android.data.download.TrackDownloadManager
import com.mus.android.data.enrichment.provider.OnlineMusicSearchProvider
import com.mus.android.data.enrichment.provider.OnlineSearchResult
import com.mus.android.data.model.AlbumPageData
import com.mus.android.data.model.ArtistPageData
import com.mus.android.data.model.DownloadQueueItem
import com.mus.android.data.model.DownloadStatus
import com.mus.android.data.model.Playlist
import com.mus.android.data.model.Track
import com.mus.android.data.music.HomePage
import com.mus.android.data.music.HomeShelf
import com.mus.android.data.music.InnerTubeMusicApi
import com.mus.android.data.music.YouTubeMusicEntity
import com.mus.android.data.music.YouTubeMusicTrack
import com.mus.android.data.music.YouTubePlaylistSummary
import com.mus.android.data.repository.MusicRepository
import com.mus.android.data.ytmusic.YtMusicAuthManager
import com.mus.android.playback.PlayableTrack
import com.mus.android.playback.PlaybackManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val downloadedTrackDao: DownloadedTrackDao,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _onlineResults = MutableStateFlow<List<OnlineSearchResult>>(emptyList())
    val onlineResults: StateFlow<List<OnlineSearchResult>> = _onlineResults.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    /** Albums / artists matched by the online search — Apple Music–style sections. */
    private val _onlineAlbums = MutableStateFlow<List<YouTubeMusicEntity>>(emptyList())
    val onlineAlbums: StateFlow<List<YouTubeMusicEntity>> = _onlineAlbums.asStateFlow()

    private val _onlineArtists = MutableStateFlow<List<YouTubeMusicEntity>>(emptyList())
    val onlineArtists: StateFlow<List<YouTubeMusicEntity>> = _onlineArtists.asStateFlow()

    val downloadQueue: StateFlow<List<DownloadQueueItem>> = repository.getDownloadQueue()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val pendingDownloadCount: StateFlow<Int> = repository.observePendingDownloadCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /** Live progress from the download pipeline (keyed by trackKey). */
    val downloadProgress = downloadManager.downloads

    /** Set of trackKeys already sitting in the local download index (drives the
     *  "Downloaded" badge on online search rows). */
    val downloadedKeys: StateFlow<Set<String>> = downloadedTrackDao.getAll()
        .map { rows ->
            rows.mapTo(HashSet(rows.size)) { TrackDownloadManager.makeDownloadKey(it.title, it.artist) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    fun isDownloaded(result: OnlineSearchResult): Boolean =
        downloadedKeys.value.contains(TrackDownloadManager.makeDownloadKey(result.title, result.artist))

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

    // ── YT Music album / artist pages (opened from search results) ───────

    private val _selectedYtAlbum = MutableStateFlow<AlbumPageData?>(null)
    val selectedYtAlbum: StateFlow<AlbumPageData?> = _selectedYtAlbum.asStateFlow()

    private val _ytAlbumLoading = MutableStateFlow(false)
    val ytAlbumLoading: StateFlow<Boolean> = _ytAlbumLoading.asStateFlow()

    private val _selectedYtArtist = MutableStateFlow<ArtistPageData?>(null)
    val selectedYtArtist: StateFlow<ArtistPageData?> = _selectedYtArtist.asStateFlow()

    private val _ytArtistLoading = MutableStateFlow(false)
    val ytArtistLoading: StateFlow<Boolean> = _ytArtistLoading.asStateFlow()

    /** Opens a YT Music album page (from search results or an artist's album shelf). */
    fun openYtAlbum(entity: YouTubeMusicEntity) {
        if (entity.browseId.isBlank() || _ytAlbumLoading.value) return
        _ytAlbumLoading.value = true
        viewModelScope.launch {
            val page = withContext(Dispatchers.IO) {
                runCatching {
                    innerTube.fetchAlbumPage(entity.browseId, entity.name, entity.artist ?: "")
                }.getOrNull()
            }
            _selectedYtAlbum.value = page
            _ytAlbumLoading.value = false
        }
    }

    fun closeYtAlbum() {
        _selectedYtAlbum.value = null
    }

    /** Opens a YT Music artist page (top songs + album shelves). */
    fun openYtArtist(entity: YouTubeMusicEntity) {
        if (entity.browseId.isBlank() || _ytArtistLoading.value) return
        _ytArtistLoading.value = true
        viewModelScope.launch {
            val page = withContext(Dispatchers.IO) {
                runCatching {
                    innerTube.fetchArtistPage(entity.browseId, entity.name)
                }.getOrNull()
            }
            _selectedYtArtist.value = page
            _ytArtistLoading.value = false
        }
    }

    fun closeYtArtist() {
        _selectedYtArtist.value = null
    }

    private fun PlayableTrack.toOnlineResult(): OnlineSearchResult = OnlineSearchResult(
        id = videoId ?: internalTrackId ?: title,
        title = title,
        artist = artist,
        album = album,
        artworkUrl = artworkUrl,
        source = "YouTube Music",
        sourceId = videoId ?: "",
        sourceUrl = videoId?.let { "https://music.youtube.com/watch?v=$it" } ?: "",
        durationMs = durationMs ?: 0L,
    )

    /** Plays one track of a YT album/artist page, using the page's tracks as the queue. */
    fun playYtPageTrack(tracks: List<PlayableTrack>, index: Int) {
        if (index !in tracks.indices) return
        val queue = tracks.map { it.toOnlineResult() }
        playbackManager.playOnline(queue[index], queue)
    }

    /** Overlay actions (download / queue / collection) for a page track. */
    fun pageTrackAsOnlineResult(track: PlayableTrack): OnlineSearchResult = track.toOnlineResult()

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
            _onlineAlbums.value = emptyList()
            _onlineArtists.value = emptyList()
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
            _onlineAlbums.value = emptyList()
            _onlineArtists.value = emptyList()
            return
        }
        searchJob = viewModelScope.launch {
            performSearch(q)
        }
    }

    private suspend fun performSearch(q: String) {
        _isSearching.value = true
        try {
            coroutineScope {
                val songs = async(Dispatchers.IO) {
                    // Query all configured providers in parallel and merge results
                    providers.flatMap { provider ->
                        try {
                            provider.search(q, limit = 20)
                        } catch (e: Exception) {
                            emptyList()
                        }
                    }
                }
                val albums = async(Dispatchers.IO) {
                    runCatching { innerTube.searchAlbums(q, limit = 12) }.getOrDefault(emptyList())
                }
                val artists = async(Dispatchers.IO) {
                    runCatching { innerTube.searchArtists(q, limit = 12) }.getOrDefault(emptyList())
                }
                _onlineResults.value = songs.await()
                _onlineAlbums.value = albums.await()
                _onlineArtists.value = artists.await()
            }
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

    // ── YouTube Music home feed (primary catalogue) ──────────────────────

    /** User-created playlists, surfaced as "collections" for downloaded songs. */
    val collections: StateFlow<List<Playlist>> = repository.getAllPlaylists()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _homeShelves = MutableStateFlow<List<HomeShelf>>(emptyList())
    val homeShelves: StateFlow<List<HomeShelf>> = _homeShelves.asStateFlow()

    private val _homeLoading = MutableStateFlow(false)
    val homeLoading: StateFlow<Boolean> = _homeLoading.asStateFlow()

    private var homeContinuation: String? = null
    private var homeLoadAttempted = false

    /** Fetches the YT Music catalogue home feed. Works signed-out too (public InnerTube browse). */
    fun loadHomeFeedIfEmpty(force: Boolean = false) {
        if (!force && (homeLoadAttempted || _homeShelves.value.isNotEmpty())) return
        homeLoadAttempted = true
        _homeLoading.value = true
        viewModelScope.launch {
            try {
                val page = withContext(Dispatchers.IO) { innerTube.fetchHomePage() }
                homeContinuation = page.continuationToken
                _homeShelves.value = page.shelves
            } catch (_: Exception) {
                // catalogue is best-effort; local shelves still render
            } finally {
                _homeLoading.value = false
            }
        }
    }

    /** Appends the next page of catalogue shelves (paged LazyColumn calls this on end-reached). */
    fun loadMoreHome() {
        val token = homeContinuation ?: return
        if (_homeLoading.value) return
        homeContinuation = null
        _homeLoading.value = true
        viewModelScope.launch {
            try {
                val page = withContext(Dispatchers.IO) { innerTube.fetchHomeContinuation(token) }
                homeContinuation = page.continuationToken
                _homeShelves.value = (_homeShelves.value + page.shelves).distinctBy { it.title }
            } catch (_: Exception) {
            } finally {
                _homeLoading.value = false
            }
        }
    }

    /** Streams a catalogue song straight from the YT Music home feed. */
    fun playCatalogTrack(track: YouTubeMusicTrack) {
        val result = track.toOnlineResult()
        playbackManager.playOnline(result, listOf(result))
    }

    /** Public conversion for catalogue cards (Home shelves → OnlineResultOverlay). */
    fun asOnlineResult(track: YouTubeMusicTrack): OnlineSearchResult = track.toOnlineResult()

    /** Downloads a catalogue song straight from the YT Music home feed. */
    fun downloadCatalogTrack(track: YouTubeMusicTrack) {
        downloadOnline(track.toOnlineResult())
    }

    // ── Collections (user playlists as named albums for downloaded songs) ─

    /**
     * Adds a downloaded song (matched by trackKey) to a collection playlist.
     * If the song is still downloading or not yet scanned into the library, the
     * request is parked and resolved automatically after the post-download rescan.
     */
    fun addToCollection(trackKey: String, playlistId: Long) {
        viewModelScope.launch { resolveCollectionAdd(trackKey, playlistId) }
    }

    /** Creates a new collection and adds the song to it (Telugu / Hindi / …). */
    fun createCollectionAndAdd(collectionName: String, trackKey: String) {
        viewModelScope.launch {
            try {
                val id = repository.createPlaylist(collectionName.trim())
                resolveCollectionAdd(trackKey, id)
            } catch (_: Exception) {
                _addToQueueResult.value = "Couldn't create collection"
            }
        }
    }

    private suspend fun resolveCollectionAdd(trackKey: String, playlistId: Long) {
        val downloaded = withContext(Dispatchers.IO) { downloadedTrackDao.findByTrackKey(trackKey) }
        val track = downloaded?.let { findLocalTrackFor(it.filePath, it.title, it.artist) }
        if (track != null) {
            val ok = withContext(Dispatchers.IO) { repository.addTrackToPlaylist(playlistId, track.id) }
            val name = withContext(Dispatchers.IO) { repository.getPlaylistById(playlistId)?.name } ?: "collection"
            _addToQueueResult.value = if (ok) "Added to $name" else "Already in $name"
        } else {
            // Not in the library yet — queue it; the post-download rescan will resolve it.
            pendingCollectionAdds[trackKey] = playlistId
            _addToQueueResult.value = "Will be added to collection once downloaded"
        }
    }

    private suspend fun findLocalTrackFor(filePath: String?, title: String, artist: String): Track? {
        val tracks = withContext(Dispatchers.IO) { repository.getAllTracks().first() }
        val normTitle = title.trim().lowercase()
        val normArtist = artist.trim().lowercase()
        filePath?.let { path ->
            val normalizedPath = path.trim().lowercase()
            tracks.firstOrNull { it.path?.trim()?.lowercase() == normalizedPath }?.let { return it }
            val fileName = normalizedPath.substringAfterLast('/')
            if (fileName.isNotBlank()) {
                tracks.firstOrNull { it.path?.trim()?.lowercase()?.endsWith(fileName) == true }?.let { return it }
            }
        }
        tracks.firstOrNull {
            it.title.trim().lowercase() == normTitle && it.artist.trim().lowercase() == normArtist
        }?.let { return it }
        return tracks.firstOrNull {
            it.title.trim().lowercase() == normTitle && it.artist.trim().lowercase().contains(normArtist)
        }
    }

    // ── Post-download auto-rescan + parked collection adds ───────────────

    private val pendingCollectionAdds = HashMap<String, Long>()
    private val collectionMutex = Mutex()
    private val rescanMutex = Mutex()
    private var finishedKeysSeen = HashSet<String>()
    private var rescanJob: Job? = null

    init {
        // When a download finishes: schedule one debounced library rescan so the file
        // appears in the local library immediately, then resolve parked collection adds.
        viewModelScope.launch {
            downloadManager.downloads.collect { progress ->
                val newlyFinished = progress.values.filter { it.isFinished && it.error == null }
                    .map { it.key }
                    .filter { finishedKeysSeen.add(it) }
                if (newlyFinished.isEmpty()) return@collect
                schedulePostDownloadRescan()
            }
        }
    }

    private fun schedulePostDownloadRescan() {
        rescanJob?.cancel()
        rescanJob = viewModelScope.launch {
            delay(2500) // let parallel downloads in the same batch finish too
            if (rescanMutex.isLocked) return@launch
            rescanMutex.withLock {
                try {
                    val customUri = repository.getCustomMuzicUri()?.let { android.net.Uri.parse(it) }
                    withContext(Dispatchers.IO) { repository.scanDevice(customUri) }
                } catch (_: Exception) {
                }
                // Resolve parked "add to collection" requests now that the library is fresh.
                val pending = collectionMutex.withLock { HashMap(pendingCollectionAdds) }
                pending.forEach { (trackKey, playlistId) ->
                    val downloaded = withContext(Dispatchers.IO) { downloadedTrackDao.findByTrackKey(trackKey) }
                    val track = downloaded?.let { findLocalTrackFor(it.filePath, it.title, it.artist) }
                    if (track != null) {
                        try {
                            withContext(Dispatchers.IO) { repository.addTrackToPlaylist(playlistId, track.id) }
                            val name = withContext(Dispatchers.IO) { repository.getPlaylistById(playlistId)?.name }
                            _addToQueueResult.value = "Added to ${name ?: "collection"}"
                        } catch (_: Exception) {
                        }
                        collectionMutex.withLock { pendingCollectionAdds.remove(trackKey) }
                    }
                }
            }
        }
    }
}
