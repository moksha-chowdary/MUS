package com.mus.android.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tracks")
data class Track(
    @PrimaryKey val id: Long,
    val title: String,
    val artist: String,
    val albumId: Long,
    val albumTitle: String,
    val duration: Long, // milliseconds
    val trackNumber: Int = 0,
    val year: Int = 0,
    val uri: String, // content:// URI or file path
    val artworkUri: String? = null,
    val codec: String? = null,
    val sampleRate: Int = 0, // Hz
    val bitDepth: Int = 0,
    val bitrate: Int = 0, // kbps
    val channels: Int = 2,
    val size: Long = 0, // bytes
    val isFavorite: Boolean = false,
    val dateAdded: Long = 0,
    val dateModified: Long = 0,
    val path: String? = null,
    val language: String = "English",
)
