package com.mus.android.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Represents an online music discovery candidate saved to the "MUS — To Download" planning list.
 *
 * This is a metadata-only record: MUS never downloads or extracts copyrighted audio.
 * The user plans acquisitions here; actual file placement into /Muzic/ is done externally.
 *
 * Primary key is "${source}_${sourceId}" to provide stable deduplication across restarts.
 */
@Entity(tableName = "download_queue")
data class DownloadQueueItem(
    @PrimaryKey val id: String,          // "${source}_${sourceId}", e.g. "YouTube_dQw4w9WgXcQ"
    val title: String,
    val artist: String,
    val album: String? = null,
    val artworkUrl: String? = null,       // Remote thumbnail/cover URL (not downloaded to disk)
    val source: String,                   // "YouTube", "iTunes", etc.
    val sourceId: String,                 // Provider-native ID (videoId, trackId, …)
    val sourceUrl: String,                // Canonical URL to open in browser / official app
    val dateAdded: Long = System.currentTimeMillis(),
    val status: String = DownloadStatus.TO_DOWNLOAD,
)
