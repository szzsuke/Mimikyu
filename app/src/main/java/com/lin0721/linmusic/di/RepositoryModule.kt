package com.lin0721.linmusic.di

import com.lin0721.linmusic.core.importer.PlaylistImporter
import com.lin0721.linmusic.core.lastfm.LastFmRepository
import com.lin0721.linmusic.core.reccobeats.ReccoBeatsRepository

import com.lin0721.linmusic.core.auth.AuthRepository
import com.lin0721.linmusic.core.auth.AuthRepositoryImpl
import com.lin0721.linmusic.core.auth.SyncProfileAfterLoginUseCase
import com.lin0721.linmusic.core.auth.YouTubeAccountSyncUseCase
import com.lin0721.linmusic.core.lyrics.ExternalLyricsService
import com.lin0721.linmusic.core.playlistmutation.PlaylistMutationBus
import com.lin0721.linmusic.core.qobuz.QobuzService
import com.lin0721.linmusic.core.songlike.LoadLikedSongIdsUseCase
import com.lin0721.linmusic.feature.create.data.CreateRepository
import com.lin0721.linmusic.feature.create.data.CreateRepositoryImpl
import com.lin0721.linmusic.feature.music.data.MusicRepository
import com.lin0721.linmusic.feature.music.data.MusicRepositoryImpl
import com.lin0721.linmusic.feature.home.data.HomeRepository
import com.lin0721.linmusic.feature.home.data.HomeRepositoryImpl
import com.lin0721.linmusic.feature.artist.data.ArtistRepository
import com.lin0721.linmusic.feature.artist.data.ArtistRepositoryImpl
import com.lin0721.linmusic.feature.profile.data.ProfileRepository
import com.lin0721.linmusic.feature.profile.data.ProfileRepositoryImpl
import com.lin0721.linmusic.core.player.data.PlaybackRepository
import com.lin0721.linmusic.core.player.data.PlaybackRepositoryImpl
import com.lin0721.linmusic.core.songlike.LikedSongsRepository
import com.lin0721.linmusic.core.songlike.LikedSongsRepositoryImpl
import com.lin0721.linmusic.core.songlike.SongLikeRepository
import com.lin0721.linmusic.core.songlike.SongLikeRepositoryImpl
import com.lin0721.linmusic.core.userplaylist.UserPlaylistRepository
import com.lin0721.linmusic.core.userplaylist.UserPlaylistRepositoryImpl
import com.lin0721.linmusic.feature.library.data.LibraryRepository
import com.lin0721.linmusic.feature.library.data.LibraryRepositoryImpl
import com.lin0721.linmusic.feature.listendata.data.ListenDataRepository
import com.lin0721.linmusic.feature.listendata.data.ListenDataRepositoryImpl
import com.lin0721.linmusic.feature.newworks.data.NewWorksRepository
import com.lin0721.linmusic.feature.newworks.data.NewWorksRepositoryImpl
import com.lin0721.linmusic.feature.player.data.PlayerRepository
import com.lin0721.linmusic.feature.player.data.PlayerRepositoryImpl
import com.lin0721.linmusic.feature.playlist.data.PlaylistRepository
import com.lin0721.linmusic.feature.playlist.domain.CreatePlaylistAndAddSongUseCase
import com.lin0721.linmusic.feature.playlist.domain.SongCollectDelegate
import com.lin0721.linmusic.feature.playlist.domain.UpdatePlaylistCoverUseCase
import com.lin0721.linmusic.feature.settings.data.SettingsRepository
import com.lin0721.linmusic.feature.settings.data.SettingsRepositoryImpl
import com.lin0721.linmusic.feature.playlist.data.PlaylistRepositoryImpl
import com.lin0721.linmusic.feature.recent.data.RecentRepository
import com.lin0721.linmusic.feature.recent.data.RecentRepositoryImpl
import com.lin0721.linmusic.feature.search.data.SearchRepository
import com.lin0721.linmusic.feature.search.data.SearchRepositoryImpl
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

/**
 * Koin data repository dependency injection module.
 * YouTube Music + Qobuz + LRCLIB backend layer.
 */
val repositoryModule = module {

    // ─── Ktor HttpClient for Qobuz & LRCLIB (lightweight, no auth interceptors) ───
    single {
        HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                    coerceInputValues = true
                })
            }
        }
    }

    // ─── External services ───
    single { QobuzService(get()) }
    single { ExternalLyricsService(get(), androidContext(), getOrNull()) }
    single { LastFmRepository() }
    single { ReccoBeatsRepository() }
        // Auth & account (core/auth, cross-domain shared)
    singleOf(::AuthRepositoryImpl) { bind<AuthRepository>() }

    // Home data repository — YouTube Music home feed
    single<HomeRepository> {
        HomeRepositoryImpl(
            userPreferences = getOrNull(),
            lastFmRepository = getOrNull(),
            firestorePlaylistManager = getOrNull(),
            reccoBeatsRepository = getOrNull(),
            metadataEnricher = getOrNull(),
            auroraApiClient = getOrNull(),
            searchRepository = getOrNull()
        )
    }

    singleOf(::MusicRepositoryImpl) { bind<MusicRepository>() }

    // Search data repository — YouTube Music search with Last.fm & iTunes/MusicBrainz verification
    single<SearchRepository> {
        SearchRepositoryImpl(
            lastFmRepository = getOrNull(),
            metadataEnricher = getOrNull(),
            auroraApiClient = getOrNull()
        )
    }

    // Library data repository
    singleOf(::LibraryRepositoryImpl) { bind<LibraryRepository>() }

    // Single shared playback history repository
    single { com.lin0721.linmusic.core.history.PlaybackHistoryRepository(get()) }

    // Recently Played data repository
    single<RecentRepository> {
        RecentRepositoryImpl(
            playbackHistoryRepository = get()
        )
    }


    // Listening Stats repository
    single<ListenDataRepository> {
        ListenDataRepositoryImpl(
            authState = get(),
            playbackHistoryRepository = get()
        )
    }

    // Follow Artists new works data repository
    singleOf(::NewWorksRepositoryImpl) { bind<NewWorksRepository>() }

    // Artist data repository
    single<ArtistRepository> { ArtistRepositoryImpl(lastFmRepository = getOrNull(), metadataEnricher = getOrNull(), auroraApiClient = getOrNull()) }

    // Firestore Liked Songs repository (core/songlike)
    single<LikedSongsRepository> { LikedSongsRepositoryImpl(userPreferences = get()) }

    // Song heart/like data repository (core/songlike shared)
    single<SongLikeRepository> {
        SongLikeRepositoryImpl(
            apiService = get(),
            tasteSignalScorer = getOrNull<com.lin0721.linmusic.core.player.recommendation.TasteSignalScorer>(),
            likedSongsRepository = getOrNull<LikedSongsRepository>(),
            userPreferences = getOrNull<com.lin0721.linmusic.core.auth.UserPreferences>(),
            playlistMutationBus = getOrNull<com.lin0721.linmusic.core.playlistmutation.PlaylistMutationBus>()
        )
    }

    // Current user playlists list repository (core/userplaylist shared)
    singleOf(::UserPlaylistRepositoryImpl) { bind<UserPlaylistRepository>() }

    // Follow Artists list repository (core/userartist shared)

    // Playlist/Album data repository
    singleOf(::PlaylistRepositoryImpl) { bind<PlaylistRepository>() }

    // Playback engine data repository — YouTube Music + Qobuz + LRCLIB
    single<PlaybackRepository> {
        PlaybackRepositoryImpl(
            context = androidContext(),
            settingsPreferences = get(),
            qobuzService = get(),
            externalLyricsService = get(),
            userPreferences = getOrNull(),
            playbackResolver = getOrNull(),
            auroraApiClient = getOrNull()
        )
    }

    // Player detail page data repository
    singleOf(::PlayerRepositoryImpl) { bind<PlayerRepository>() }

    // Settings/level/check-in data repository
    singleOf(::SettingsRepositoryImpl) { bind<SettingsRepository>() }

    // Create playlist data repository
    singleOf(::CreateRepositoryImpl) { bind<CreateRepository>() }

    // Create playlist and add current song (cross artist/library/playlist)
    singleOf(::CreatePlaylistAndAddSongUseCase)

    // Update playlist cover
    singleOf(::UpdatePlaylistCoverUseCase)

    // "Save to playlist" dialog state and operations
    factoryOf(::SongCollectDelegate)

    // Sync profile after login
    singleOf(::SyncProfileAfterLoginUseCase)

    // YouTube Music account sync
    singleOf(::YouTubeAccountSyncUseCase)

    // Load liked song IDs
    singleOf(::LoadLikedSongIdsUseCase)

    // Cross-module playlist mutation event bus
    singleOf(::PlaylistMutationBus)

    // Profile data repository
    singleOf(::ProfileRepositoryImpl) { bind<ProfileRepository>() }

    // Playlist importer (CSV/ZIP from Mimikyu, Apple Music, etc.) with verified metadata
    single {
        PlaylistImporter(
            firestorePlaylistManager = get(),
            playlistMutationBus = get(),
            metadataEnricher = getOrNull(),
            likedSongsRepository = getOrNull<LikedSongsRepository>(),
            userPreferences = getOrNull<com.lin0721.linmusic.core.auth.UserPreferences>(),
            songLikeRepository = getOrNull<SongLikeRepository>(),
            localPlaylistStore = getOrNull<com.lin0721.linmusic.core.userplaylist.LocalCustomPlaylistStore>(),
            auroraApiClient = getOrNull()
        )
    }
}
