package com.lin0721.linmusic.di

import com.lin0721.linmusic.core.metadata.MetadataCache
import com.lin0721.linmusic.core.metadata.MetadataEnricher
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val metadataModule = module {
    single { MetadataCache(androidContext()) }
    single { MetadataEnricher(androidContext(), get()) }
    single { com.lin0721.linmusic.core.metadata.MimikyuArtistStatsRepository(androidContext()) }
    single { com.lin0721.linmusic.core.metadata.IsrcMetadataResolver() }
}
