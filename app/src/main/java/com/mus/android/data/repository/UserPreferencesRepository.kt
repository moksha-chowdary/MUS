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

    private val _metadataResetV2Completed by lazy { MutableStateFlow(prefs.getBoolean("metadata_reset_v2_completed", false)) }
    open val metadataResetV2Completed: StateFlow<Boolean> get() = _metadataResetV2Completed.asStateFlow()

    open fun isMetadataResetV2Completed(): Boolean {
        return prefs.getBoolean("metadata_reset_v2_completed", false)
    }

    open fun setMetadataResetV2Completed(completed: Boolean) {
        prefs.edit().putBoolean("metadata_reset_v2_completed", completed).apply()
        _metadataResetV2Completed.value = completed
    }

    private val _metadataRepairV3Completed by lazy { MutableStateFlow(prefs.getBoolean("metadata_repair_v3_completed", false)) }
    open val metadataRepairV3Completed: StateFlow<Boolean> get() = _metadataRepairV3Completed.asStateFlow()

    open fun isMetadataRepairV3Completed(): Boolean {
        return prefs.getBoolean("metadata_repair_v3_completed", false)
    }

    open fun setMetadataRepairV3Completed(completed: Boolean) {
        prefs.edit().putBoolean("metadata_repair_v3_completed", completed).apply()
        _metadataRepairV3Completed.value = completed
    }

    private val _artworkRollbackCompleted by lazy { MutableStateFlow(prefs.getBoolean("artwork_rollback_completed", false)) }
    open val artworkRollbackCompleted: StateFlow<Boolean> get() = _artworkRollbackCompleted.asStateFlow()

    open fun isArtworkRollbackCompleted(): Boolean {
        return prefs.getBoolean("artwork_rollback_completed", false)
    }

    open fun setArtworkRollbackCompleted(completed: Boolean) {
        prefs.edit().putBoolean("artwork_rollback_completed", completed).apply()
        _artworkRollbackCompleted.value = completed
    }

    private val _artworkForensicFixV4Completed by lazy { MutableStateFlow(prefs.getBoolean("artwork_forensic_fix_v4_completed", false)) }
    open val artworkForensicFixV4Completed: StateFlow<Boolean> get() = _artworkForensicFixV4Completed.asStateFlow()

    open fun isArtworkForensicFixV4Completed(): Boolean {
        return prefs.getBoolean("artwork_forensic_fix_v4_completed", false)
    }

    open fun setArtworkForensicFixV4Completed(completed: Boolean) {
        prefs.edit().putBoolean("artwork_forensic_fix_v4_completed", completed).apply()
        _artworkForensicFixV4Completed.value = completed
    }

    private val _artworkRestoredV5Completed by lazy { MutableStateFlow(prefs.getBoolean("artwork_restored_v5_completed", false)) }
    open val artworkRestoredV5Completed: StateFlow<Boolean> get() = _artworkRestoredV5Completed.asStateFlow()

    open fun isArtworkRestoredV5Completed(): Boolean {
        return prefs.getBoolean("artwork_restored_v5_completed", false)
    }

    open fun setArtworkRestoredV5Completed(completed: Boolean) {
        prefs.edit().putBoolean("artwork_restored_v5_completed", completed).apply()
        _artworkRestoredV5Completed.value = completed
    }
}
