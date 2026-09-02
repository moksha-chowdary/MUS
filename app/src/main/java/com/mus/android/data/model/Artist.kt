package com.mus.android.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "artists")
data class Artist(
    @PrimaryKey val id: Long,
    val name: String,
    val artworkUri: String? = null,
    val albumCount: Int = 0,
    val trackCount: Int = 0,
)
