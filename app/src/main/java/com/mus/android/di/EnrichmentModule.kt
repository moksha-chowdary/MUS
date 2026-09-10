package com.mus.android.di

import com.mus.android.data.enrichment.provider.ITunesMetadataProvider
import com.mus.android.data.enrichment.provider.MetadataProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class EnrichmentModule {

    @Binds
    @Singleton
    abstract fun bindMetadataProvider(
        provider: ITunesMetadataProvider
    ): MetadataProvider
}
