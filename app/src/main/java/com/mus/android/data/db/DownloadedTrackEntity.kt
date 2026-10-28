package com.mus.android.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "downloaded_tracks",
    indices = [Index(value = ["trackKey"], unique = true)],
)
data class DownloadedTrackEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    // Normalized "${artist}_${title}" (see TrackDownloadManager.makeDownloadKey).
    // Enforced unique so a duplicate download REPLACEs the existing row
    // instead of creating a second one.
    val trackKey: String = "",
    val title: String,
    val artist: String,
    val album: String = "",
    val artworkUrl: String? = null,
    val filePath: String,
    val mediaStoreUri: String? = null,
    val fileSizeBytes: Long = 0L,
    val formatBadge: String = "AUDIO",
    val durationMs: Long = 0L,
    val bitrateKbps: Int? = null,
    val isLossless: Boolean = false,
    val downloadedAtMillis: Long = System.currentTimeMillis(),
)
