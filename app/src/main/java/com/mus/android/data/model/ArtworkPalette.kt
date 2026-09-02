package com.mus.android.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Cached color palette extracted from artwork via AndroidX Palette.
 * Stores the dominant colors as ARGB integers.
 */
@Entity(tableName = "artwork_palettes")
data class ArtworkPalette(
    @PrimaryKey val artworkUri: String,
    val dominantColor: Int,
    val vibrantColor: Int?,
    val mutedColor: Int?,
    val darkVibrantColor: Int?,
    val darkMutedColor: Int?,
    val lightVibrantColor: Int?,
    val generatedAt: Long = System.currentTimeMillis(),
)
