package com.mus.android.data.model

import androidx.room.Entity
import androidx.room.ForeignKey

@Entity(
    tableName = "playlist_tracks",
    primaryKeys = ["playlistId", "trackId"],
    foreignKeys = [
        ForeignKey(
            entity = Playlist::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class PlaylistTrack(
    val playlistId: Long,
    val trackId: Long,
    val position: Int, // ordering within playlist
    val addedAt: Long = System.currentTimeMillis(),
)
