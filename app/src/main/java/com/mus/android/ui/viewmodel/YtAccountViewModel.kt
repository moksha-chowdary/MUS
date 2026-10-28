package com.mus.android.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mus.android.data.ytmusic.CookieNormalizer
import com.mus.android.data.ytmusic.YtConnection
import com.mus.android.data.ytmusic.YtMusicAuthManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class YtAccountViewModel @Inject constructor(
    private val authManager: YtMusicAuthManager,
) : ViewModel() {

    val connection: StateFlow<YtConnection> = authManager.connection
        .stateIn(viewModelScope, SharingStarted.Eagerly, YtConnection.DISCONNECTED)

    fun connectCookies(rawCookieHeader: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            try {
                val normalized = CookieNormalizer.normalize(rawCookieHeader)
                authManager.connect(normalized, "Google account")
                onResult(true, "YouTube Music account connected")
            } catch (e: IllegalArgumentException) {
                onResult(false, e.message ?: "Invalid cookies")
            } catch (e: Exception) {
                onResult(false, "Could not connect: ${e.message}")
            }
        }
    }

    fun signOut(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            authManager.signOut()
            onDone()
        }
    }
}
