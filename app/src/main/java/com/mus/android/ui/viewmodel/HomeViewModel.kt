package com.mus.android.ui.viewmodel

import android.app.Application
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mus.android.data.model.Album
import com.mus.android.data.model.Track
import com.mus.android.data.repository.MusicRepository
import com.mus.android.playback.PlaybackManager
import com.mus.android.playback.QueueBuilder
import com.mus.android.ui.ambient.PaletteExtractor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class PermissionState {
    NOT_REQUESTED,
    DENIED,
    PERMANENTLY_DENIED,
    GRANTED,
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    application: Application,
    private val repository: MusicRepository,
    private val playbackManager: PlaybackManager,
) : AndroidViewModel(application) {

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _needsPermission = MutableStateFlow(false)
    val needsPermission: StateFlow<Boolean> = _needsPermission.asStateFlow()

    private val _permissionState = MutableStateFlow(PermissionState.NOT_REQUESTED)
    val permissionState: StateFlow<PermissionState> = _permissionState.asStateFlow()

    val tracks: StateFlow<List<Track>> = repository.getAllTracks()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    val recentlyAdded: StateFlow<List<Track>> = repository.getRecentlyAdded(20)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val mostPlayed: StateFlow<List<Track>> = repository.getMostPlayed(20)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val recentlyPlayed: StateFlow<List<Track>> = repository.getRecentlyPlayed(20)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val albums: StateFlow<List<Album>> = repository.getAllAlbums()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val randomAlbums: StateFlow<List<Album>> = repository.getRandomAlbums(10)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val playlists: StateFlow<List<com.mus.android.data.model.Playlist>> = repository.getAllPlaylists()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _quickPicks = MutableStateFlow<List<Track>>(emptyList())
    val quickPicks: StateFlow<List<Track>> = _quickPicks.asStateFlow()

    private var currentSessionSeed: Long = System.currentTimeMillis() / (1000 * 60 * 60 * 24)

    fun refreshQuickPicks(forceNewSeed: Boolean = true) {
        if (forceNewSeed) {
            currentSessionSeed = System.currentTimeMillis()
        }
        generateQuickPicks(tracks.value, seed = currentSessionSeed)
    }

    fun generateQuickPicks(allTracks: List<Track>, seed: Long = currentSessionSeed) {
        if (allTracks.isEmpty()) {
            _quickPicks.value = emptyList()
            return
        }
        if (allTracks.size <= 8) {
            _quickPicks.value = allTracks
            return
        }

        val now = System.currentTimeMillis()
        val rng = java.util.Random(seed)

        // Candidate scoring based on local library signals (Part 10)
        val scoredCandidates = allTracks.map { track ->
            var score = 0.0

            // 1. Favorites boost (+50)
            if (track.isFavorite) score += 50.0

            // 2. Play count weighting (+up to 50)
            score += (track.playCount.coerceAtMost(10) * 5.0)

            // 3. Recently played recency weighting
            if (track.lastPlayed > 0) {
                val daysAgo = (now - track.lastPlayed) / (1000 * 60 * 60 * 24)
                if (daysAgo < 7) {
                    score += 30.0 - (daysAgo * 3.0)
                } else {
                    score += 15.0 // Rediscovery
                }
            } else {
                score += 20.0 // Discovery
            }

            // 4. Deterministic session jitter (+0..30)
            score += rng.nextDouble() * 30.0

            Pair(track, score)
        }

        // Pick top candidates, shuffle with session rng, and take 8 songs
        val topCandidates = scoredCandidates
            .sortedByDescending { it.second }
            .take(20)
            .map { it.first }
            .shuffled(rng)
            .take(8)

        _quickPicks.value = topCandidates
    }

    fun playQuickPick(track: Track) {
        val all = if (tracks.value.isNotEmpty()) tracks.value else listOf(track)
        val queue = QueueBuilder.buildLanguageMixQueue(track, all)
        val context = QueueBuilder.getQueueContextForLanguageMix(track)
        playbackManager.playTrack(track, queue, context)
    }

    fun toggleFavorite(trackId: Long) {
        viewModelScope.launch {
            repository.toggleFavorite(trackId)
        }
    }

    fun doesMuzicDirectoryExist(): Boolean = repository.doesMuzicDirectoryExist()

    fun createMuzicDirectory() {
        viewModelScope.launch {
            repository.createMuzicDirectory()
            scanDevice()
        }
    }

    init {
        viewModelScope.launch {
            tracks.collect { allTracks ->
                if (allTracks.isNotEmpty() && _quickPicks.value.isEmpty()) {
                    generateQuickPicks(allTracks)
                }
            }
        }
        checkAndScan()
    }

    fun checkAndScan() {
        viewModelScope.launch {
            if (hasMediaPermission()) {
                _permissionState.value = PermissionState.GRANTED
                _needsPermission.value = false
                scanDevice()
            } else {
                if (_permissionState.value == PermissionState.GRANTED) {
                    _permissionState.value = PermissionState.NOT_REQUESTED
                }
                _needsPermission.value = true
                _isLoading.value = false
            }
        }
    }

    fun onPermissionGranted() {
        _permissionState.value = PermissionState.GRANTED
        _needsPermission.value = false
        viewModelScope.launch { scanDevice() }
    }

    fun onPermissionDenied(permanentlyDenied: Boolean) {
        _permissionState.value = if (permanentlyDenied) {
            PermissionState.PERMANENTLY_DENIED
        } else {
            PermissionState.DENIED
        }
        _needsPermission.value = true
        _isLoading.value = false
    }

    private suspend fun scanDevice() {
        _isLoading.value = true
        _errorMessage.value = null
        try {
            repository.scanDevice()
        } catch (e: Exception) {
            _errorMessage.value = e.localizedMessage ?: "Failed scanning music library"
            e.printStackTrace()
        } finally {
            _isLoading.value = false
        }
    }

    fun getCustomMuzicUri(): String? = repository.getCustomMuzicUri()

    fun setCustomMuzicFolder(uri: android.net.Uri) {
        repository.setCustomMuzicUri(uri.toString())
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            try {
                repository.scanDevice(uri)
            } catch (e: Exception) {
                _errorMessage.value = e.localizedMessage ?: "Failed scanning custom folder"
                e.printStackTrace()
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun isAudioFocusEnabled(): Boolean = repository.isAudioFocusEnabled()

    fun setAudioFocusEnabled(enabled: Boolean) {
        repository.setAudioFocusEnabled(enabled)
    }

    fun isAutomaticMetadataEnabled(): Boolean = repository.isAutomaticMetadataEnabled()

    fun setAutomaticMetadataEnabled(enabled: Boolean) {
        repository.setAutomaticMetadataEnabled(enabled)
    }

    fun refreshMetadata() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                repository.refreshAllMetadata()
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun reverifyLibrary() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                repository.reverifyLibrary()
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun refreshLibrary() {
        viewModelScope.launch { scanDevice() }
    }

    private fun hasMediaPermission(): Boolean {
        val context = getApplication<Application>()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }
    }
}
