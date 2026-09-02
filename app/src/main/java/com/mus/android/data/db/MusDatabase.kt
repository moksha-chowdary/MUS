package com.mus.android.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.mus.android.data.model.*

@Database(
    entities = [
        Track::class,
        Album::class,
        Artist::class,
        Playlist::class,
        PlaylistTrack::class,
        WaveformData::class,
        ArtworkPalette::class,
    ],
    version = 1,
    exportSchema = false
)
abstract class MusDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun albumDao(): AlbumDao
    abstract fun artistDao(): ArtistDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun waveformDao(): WaveformDao
    abstract fun paletteDao(): PaletteDao
}
