package com.lin0721.linmusic.di

import com.lin0721.linmusic.core.auth.LoginViewModel
import com.lin0721.linmusic.feature.settings.ui.SettingsViewModel
import com.lin0721.linmusic.feature.create.ui.CreateViewModel
import com.lin0721.linmusic.feature.home.ui.HomeViewModel
import com.lin0721.linmusic.feature.music.ui.MusicViewModel
import com.lin0721.linmusic.feature.library.ui.LibraryViewModel
import com.lin0721.linmusic.feature.listendata.ui.ListenDataViewModel
import com.lin0721.linmusic.feature.localmusic.ui.LocalMusicViewModel
import com.lin0721.linmusic.feature.newworks.ui.NewWorksViewModel
import com.lin0721.linmusic.feature.player.ui.PlayerViewModel
import com.lin0721.linmusic.feature.playlist.ui.PlaylistViewModel
import com.lin0721.linmusic.feature.profile.ui.FollowListViewModel
import com.lin0721.linmusic.feature.profile.ui.ProfileViewModel
import com.lin0721.linmusic.feature.recent.ui.RecentPlayViewModel
import com.lin0721.linmusic.feature.artist.ui.ArtistViewModel
import com.lin0721.linmusic.feature.artist.ui.ArtistMvPlayerViewModel
import com.lin0721.linmusic.feature.search.ui.SearchViewModel
import com.lin0721.linmusic.feature.search.ui.PlaylistCategoryViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

/**
 * Koin ViewModel 
 *
 *  [viewModelOf]  ViewModel  of 。
 */
val viewModelModule = module {

    viewModelOf(::HomeViewModel)
    viewModelOf(::MusicViewModel)
    viewModelOf(::PlaylistViewModel)
    viewModelOf(::ArtistViewModel)
    viewModelOf(::ArtistMvPlayerViewModel)
    viewModelOf(::SearchViewModel)
    viewModelOf(::PlaylistCategoryViewModel)
    viewModel {
        LibraryViewModel(
            syncProfileAfterLoginUseCase = get(),
            createRepository = get(),
            libraryRepository = get(),
            playlistRepository = get(),
            artistRepository = get(),
            userPlaylistRepository = get(),
            likedSongsRepository = get(),
            userPreferences = get(),
            playerManager = get(),
            context = get(),
            resourceProvider = get(),
            playlistMutationBus = get(),
            songDownloadManager = get(),
            downloadPreferences = getOrNull(),
            spotifyRepository = getOrNull()
        )
    }
    viewModelOf(::RecentPlayViewModel)
    viewModelOf(::ListenDataViewModel)
    viewModelOf(::LocalMusicViewModel)
    viewModelOf(::NewWorksViewModel)
    viewModelOf(::CreateViewModel)
    viewModelOf(::PlayerViewModel)
    viewModelOf(::SettingsViewModel)
    viewModelOf(::LoginViewModel)

    viewModelOf(::ProfileViewModel)
    viewModelOf(::FollowListViewModel)

}
