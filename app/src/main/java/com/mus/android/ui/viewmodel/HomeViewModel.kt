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
import com.mus.android.ui.ambient.PaletteExtractor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

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

    val recentlyAdded: StateFlow<List<Track>> = repository.getRecentlyAdded(20)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val albums: StateFlow<List<Album>> = repository.getAllAlbums()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val randomAlbums: StateFlow<List<Album>> = repository.getRandomAlbums(10)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val playlists: StateFlow<List<com.mus.android.data.model.Playlist>> = repository.getAllPlaylists()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    init {
        checkAndScan()
    }

    fun checkAndScan() {
        viewModelScope.launch {
            if (hasMediaPermission()) {
                _needsPermission.value = false
                scanDevice()
            } else {
                _needsPermission.value = true
                _isLoading.value = false
            }
        }
    }

    fun onPermissionGranted() {
        _needsPermission.value = false
        viewModelScope.launch { scanDevice() }
    }

    private suspend fun scanDevice() {
        _isLoading.value = true
        try {
            repository.scanDevice()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        _isLoading.value = false
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
