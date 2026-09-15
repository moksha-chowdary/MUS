package com.mus.android.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages persistent user preferences including SAF-selected /Muzic/ tree URI,
 * audio focus preferences, and player settings.
 */
@Singleton
open class UserPreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs by lazy { context.getSharedPreferences("mus_user_prefs", Context.MODE_PRIVATE) }

    private val _customMuzicUri by lazy { MutableStateFlow(prefs.getString("custom_muzic_uri", null)) }
    open val customMuzicUri: StateFlow<String?> get() = _customMuzicUri.asStateFlow()

    private val _audioFocusEnabled by lazy { MutableStateFlow(prefs.getBoolean("audio_focus_enabled", true)) }
    open val audioFocusEnabled: StateFlow<Boolean> get() = _audioFocusEnabled.asStateFlow()

    private val _automaticMetadataEnabled by lazy { MutableStateFlow(prefs.getBoolean("automatic_metadata_enabled", true)) }
    open val automaticMetadataEnabled: StateFlow<Boolean> get() = _automaticMetadataEnabled.asStateFlow()

    open fun setCustomMuzicUri(uriString: String?) {
        prefs.edit().putString("custom_muzic_uri", uriString).apply()
        _customMuzicUri.value = uriString
    }

    open fun setAudioFocusEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("audio_focus_enabled", enabled).apply()
        _audioFocusEnabled.value = enabled
    }

    open fun setAutomaticMetadataEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("automatic_metadata_enabled", enabled).apply()
        _automaticMetadataEnabled.value = enabled
    }
}
