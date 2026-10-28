package com.mus.android.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.mus.android.data.db.DownloadedTrackEntity
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
        DownloadQueueItem::class,
        DownloadedTrackEntity::class,
    ],
    version = 7,
    exportSchema = false
)
abstract class MusDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun albumDao(): AlbumDao
    abstract fun artistDao(): ArtistDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun waveformDao(): WaveformDao
    abstract fun paletteDao(): PaletteDao
    abstract fun downloadQueueDao(): DownloadQueueDao
    abstract fun downloadedTrackDao(): DownloadedTrackDao

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

        /**
         * v5 → v6:
         * - Add artwork provenance columns to tracks (all nullable / empty-defaulted to preserve existing rows).
         * - Create the download_queue table for the "MUS — To Download" planning list.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Artwork provenance on tracks
                db.execSQL("ALTER TABLE tracks ADD COLUMN artworkSource TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE tracks ADD COLUMN artworkProvider TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE tracks ADD COLUMN artworkRemoteId TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE tracks ADD COLUMN artworkLastUpdated INTEGER NOT NULL DEFAULT 0")

                // "MUS — To Download" planning list
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS download_queue (
                        id TEXT NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL,
                        artist TEXT NOT NULL,
                        album TEXT,
                        artworkUrl TEXT,
                        source TEXT NOT NULL,
                        sourceId TEXT NOT NULL,
                        sourceUrl TEXT NOT NULL,
                        dateAdded INTEGER NOT NULL,
                        status TEXT NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }
        /**
         * v6 → v7: Room trackKey index for downloaded YouTube Music tracks.
         * Creates the downloaded_tracks table with a UNIQUE trackKey column
         * (normalized "${'$'}{artist}_${'$'}{title}") so duplicate downloads REPLACE
         * the existing row instead of creating a second one.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS downloaded_tracks (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        trackKey TEXT NOT NULL DEFAULT '',
                        title TEXT NOT NULL,
                        artist TEXT NOT NULL,
                        album TEXT NOT NULL DEFAULT '',
                        artworkUrl TEXT,
                        filePath TEXT NOT NULL,
                        mediaStoreUri TEXT,
                        fileSizeBytes INTEGER NOT NULL DEFAULT 0,
                        formatBadge TEXT NOT NULL DEFAULT 'AUDIO',
                        durationMs INTEGER NOT NULL DEFAULT 0,
                        bitrateKbps INTEGER,
                        isLossless INTEGER NOT NULL DEFAULT 0,
                        downloadedAtMillis INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_downloaded_tracks_trackKey ON downloaded_tracks(trackKey)"
                )
            }
        }
    }
}
