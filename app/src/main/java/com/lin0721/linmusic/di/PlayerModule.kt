package com.lin0721.linmusic.di

import com.lin0721.linmusic.core.player.PlayerManager
import com.lin0721.linmusic.core.player.ExternalInterruptionResumeController
import com.lin0721.linmusic.core.player.external.ExternalLyricCoordinator
import com.lin0721.linmusic.core.player.resolver.PlaybackResolver
import com.lin0721.linmusic.core.player.resolver.PlaybackResolverImpl
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val playerModule = module {
    single<PlaybackResolver> { PlaybackResolverImpl(androidContext(), getOrNull(), get()) }
    single { com.lin0721.linmusic.core.player.recommendation.TasteSignalScorer(androidContext()) }
    single { com.lin0721.linmusic.core.player.recommendation.SmartRecommendationEngine(androidContext(), get(), get()) }
    single { com.lin0721.linmusic.core.player.recommendation.DailyMixGenerator(tasteSignalScorer = get(), playbackHistoryRepository = getOrNull(), auroraApiClient = getOrNull(), isrcMetadataResolver = getOrNull()) }
    single { PlayerManager(androidContext(), get(), get(), get(), get(), get(), get(), getOrNull(), get(), getOrNull(), getOrNull()) }
    single { ExternalLyricCoordinator(androidContext(), get(), get(), get()) }
    single { ExternalInterruptionResumeController(androidContext(), get()) }
}
