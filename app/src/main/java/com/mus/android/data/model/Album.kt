package com.mus.android.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "albums",
    indices = [
        Index(value = ["artist", "title"], unique = true)
    ]
)
data class Album(
    @PrimaryKey val id: Long,
    val title: String,
    val artist: String,
    val artworkUri: String? = null,
    val year: Int = 0,
    val trackCount: Int = 0,
    val totalDuration: Long = 0, // milliseconds
)
