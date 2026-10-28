package com.mus.android.di

import android.content.Context
import androidx.room.Room
import coil.ImageLoader
import com.mus.android.data.db.*
import com.mus.android.playback.glyph.GlyphController
import com.mus.android.playback.glyph.StubGlyphController
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): MusDatabase {
        return Room.databaseBuilder(
            context,
            MusDatabase::class.java,
            "mus_database"
        )
            .addMigrations(
                MusDatabase.MIGRATION_3_4,
                MusDatabase.MIGRATION_4_5,
                MusDatabase.MIGRATION_5_6,
                MusDatabase.MIGRATION_6_7,
            )
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides fun provideTrackDao(db: MusDatabase): TrackDao = db.trackDao()
    @Provides fun provideAlbumDao(db: MusDatabase): AlbumDao = db.albumDao()
    @Provides fun provideArtistDao(db: MusDatabase): ArtistDao = db.artistDao()
    @Provides fun providePlaylistDao(db: MusDatabase): PlaylistDao = db.playlistDao()
    @Provides fun provideWaveformDao(db: MusDatabase): WaveformDao = db.waveformDao()
    @Provides fun providePaletteDao(db: MusDatabase): PaletteDao = db.paletteDao()
    @Provides fun provideDownloadQueueDao(db: MusDatabase): com.mus.android.data.db.DownloadQueueDao = db.downloadQueueDao()
    @Provides fun provideDownloadedTrackDao(db: MusDatabase): DownloadedTrackDao = db.downloadedTrackDao()

    /** Shared application-lifetime scope for downloads and startup jobs. */
    @Provides
    @Singleton
    @Named("applicationScope")
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Shared HTTP client for the InnerTube client, NewPipe extractor and the
     * download pipeline (ported from LastWave's NetworkModule).
     */
    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideImageLoader(@ApplicationContext context: Context): ImageLoader {
        return ImageLoader.Builder(context)
            .memoryCache {
                coil.memory.MemoryCache.Builder(context)
                    .maxSizePercent(0.25)
                    .strongReferencesEnabled(true)
                    .build()
            }
            .diskCache {
                coil.disk.DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache"))
                    .maxSizeBytes(100L * 1024 * 1024)
                    .build()
            }
            .respectCacheHeaders(false)
            .crossfade(false)
            .build()
    }

    @Provides
    @Singleton
    fun provideGlyphController(): GlyphController {
        return StubGlyphController()
    }
}
