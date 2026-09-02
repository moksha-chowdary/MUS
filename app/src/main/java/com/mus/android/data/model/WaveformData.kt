package com.mus.android.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Cached waveform amplitude data for a track.
 * Stores normalized peak amplitudes (0.0–1.0) as a comma-separated string
 * for efficient Room storage. Typically ~200 samples per track.
 */
@Entity(tableName = "waveforms")
data class WaveformData(
    @PrimaryKey val trackId: Long,
    val peaks: String, // comma-separated floats, e.g. "0.12,0.45,0.78,..."
    val sampleCount: Int,
    val generatedAt: Long = System.currentTimeMillis(),
)
