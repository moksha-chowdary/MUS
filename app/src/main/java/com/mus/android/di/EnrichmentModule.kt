package com.mus.android.di

import com.mus.android.data.enrichment.provider.ArtworkSearchProvider
import com.mus.android.data.enrichment.provider.ITunesArtworkSearchProvider
import com.mus.android.data.enrichment.provider.ITunesMetadataProvider
import com.mus.android.data.enrichment.provider.ITunesOnlineSearchProvider
import com.mus.android.data.enrichment.provider.MetadataProvider
import com.mus.android.data.enrichment.provider.OnlineMusicSearchProvider
import com.mus.android.data.enrichment.provider.YouTubeMusicSearchProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class EnrichmentModule {

    /** Automatic metadata enrichment provider (existing). */
    @Binds
    @Singleton
    abstract fun bindMetadataProvider(
        provider: ITunesMetadataProvider
    ): MetadataProvider

    /** Artwork search provider for manual artwork correction. */
    @Binds
    @Singleton
    abstract fun bindArtworkSearchProvider(
        provider: ITunesArtworkSearchProvider
    ): ArtworkSearchProvider

    /** Online music discovery providers — multibinding allows adding more providers later. */
    @Binds
    @IntoSet
    @Singleton
    abstract fun bindYouTubeSearchProvider(
        provider: YouTubeMusicSearchProvider
    ): OnlineMusicSearchProvider

    @Binds
    @IntoSet
    @Singleton
    abstract fun bindITunesOnlineSearchProvider(
        provider: ITunesOnlineSearchProvider
    ): OnlineMusicSearchProvider
}
