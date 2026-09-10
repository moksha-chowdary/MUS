package com.mus.android.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
    version = 5,
    exportSchema = false
)
abstract class MusDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun albumDao(): AlbumDao
    abstract fun artistDao(): ArtistDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun waveformDao(): WaveformDao
    abstract fun paletteDao(): PaletteDao

    companion object {
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracks ADD COLUMN artistArtworkUri TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE tracks ADD COLUMN metadataSource TEXT NOT NULL DEFAULT 'EMBEDDED'")
                db.execSQL("ALTER TABLE tracks ADD COLUMN metadataStatus TEXT NOT NULL DEFAULT 'COMPLETE'")
                db.execSQL("ALTER TABLE tracks ADD COLUMN metadataConfidence TEXT NOT NULL DEFAULT 'HIGH'")
                db.execSQL("ALTER TABLE tracks ADD COLUMN metadataLastUpdated INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE playlists ADD COLUMN systemKey TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE playlists ADD COLUMN isSystemPlaylist INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE waveforms ADD COLUMN status TEXT NOT NULL DEFAULT 'READY'")
            }
        }
    }
}
