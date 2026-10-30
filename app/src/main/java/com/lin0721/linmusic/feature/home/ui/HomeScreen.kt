package com.lin0721.linmusic.feature.home.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lin0721.linmusic.LocalBottomOverlayInset
import com.lin0721.linmusic.core.ui.components.AdaptiveContentWidth
import com.lin0721.linmusic.core.ui.components.LoginBottomSheet
import com.lin0721.linmusic.core.ui.components.ToastManager
import com.lin0721.linmusic.core.ui.components.WebViewLoginScreen
import com.lin0721.linmusic.core.ui.components.YouTubeMusicLoginScreen
import com.lin0721.linmusic.core.ui.theme.MimikyuSpacing
import com.lin0721.linmusic.core.ui.theme.ScreenSlideDurationMs
import com.lin0721.linmusic.feature.search.ui.MimikyuBrowseCard
import com.lin0721.linmusic.feature.search.ui.discoverItems
import com.lin0721.linmusic.feature.search.ui.genreItems
import org.koin.androidx.compose.koinViewModel

private const val TAB_ALL = 0
private const val TAB_DISCOVER = 1
private const val TAB_LOCAL_MUSIC = 2

@Composable
fun HomeScreen(
    viewModel: HomeViewModel = koinViewModel(),
    selectedTab: Int = TAB_ALL,
    onTabSelected: (Int) -> Unit = {},
    showNewWorksFeed: Boolean = false,
    onShowNewWorksFeedChanged: (Boolean) -> Unit = {},
    onPlaylistClick: (Long, Boolean) -> Unit = { _, _ -> },
    onArtistClick: (Long) -> Unit = {},
    onRadioClick: (Long) -> Unit = {},
    onMvClick: (Long, String) -> Unit = { _, _ -> },
    onSearchClick: () -> Unit = {},
    onOpenSidebar: () -> Unit = {},
    onPlaylistCategoryClick: (category: String) -> Unit = {},
    onNavigateToRecentPlay: () -> Unit = {},
    onNavigateToLikedSongs: () -> Unit = {},
    onNavigateToDownloadedSongs: () -> Unit = {},
    onLoginScreenVisibilityChanged: (Boolean) -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val userProfile by viewModel.userProfile.collectAsStateWithLifecycle()
    val likedSongs by viewModel.likedSongs.collectAsStateWithLifecycle()
    val downloadPrefs: com.lin0721.linmusic.core.download.DownloadPreferences = org.koin.compose.koinInject()
    val downloadedRecords by downloadPrefs.records.collectAsStateWithLifecycle(initialValue = emptyList())
    val currentUid = viewModel.currentUid ?: userProfile?.uid?.toString()

    var showLoginSheet by remember { mutableStateOf(false) }
    var showWebViewLogin by remember { mutableStateOf(false) }
    var showYouTubeMusicLogin by remember { mutableStateOf(false) }

    LaunchedEffect(showWebViewLogin, showYouTubeMusicLogin) {
        onLoginScreenVisibilityChanged(showWebViewLogin || showYouTubeMusicLogin)
    }

    LaunchedEffect(viewModel) {
        viewModel.toastEvent.collect { message ->
            ToastManager.showToast(message)
        }
    }

    val onAvatarClick: () -> Unit = {
        if (userProfile != null) {
            onOpenSidebar()
        } else {
            showLoginSheet = true
        }
    }

    val homeListState = androidx.compose.foundation.lazy.rememberLazyListState()
    val recentRecords by viewModel.recentRecords.collectAsStateWithLifecycle()
    val currentTrack by viewModel.playerManager.currentTrack.collectAsStateWithLifecycle()
    val isPlaying by viewModel.playerManager.isPlaying.collectAsStateWithLifecycle()
    val userPlaylists by viewModel.userPlaylists.collectAsStateWithLifecycle()

    if (selectedTab != TAB_ALL) {
        BackHandler {
            onTabSelected(TAB_ALL)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            when (selectedTab) {
                TAB_DISCOVER -> {
                    AdaptiveContentWidth {
                        LazyColumn(
                            contentPadding = PaddingValues(
                                start = MimikyuSpacing.md,
                                end = MimikyuSpacing.md,
                                top = 84.dp,
                                bottom = LocalBottomOverlayInset.current + MimikyuSpacing.md
                            ),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            item(key = "browse_all_header") {
                                Text(
                                    text = "Browse all",
                                    color = Color.White,
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(top = 14.dp, bottom = 12.dp)
                                )
                            }

                            item(key = "discover_header") {
                                Text(
                                    text = "Discover",
                                    color = Color.White,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(bottom = 12.dp)
                                )
                            }

                            itemsIndexed(discoverItems.chunked(2), key = { index, _ -> "discover_row_$index" }) { _, pair ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    MimikyuBrowseCard(
                                        item = pair[0],
                                        onClick = { onPlaylistCategoryClick(pair[0].tag) },
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (pair.size > 1) {
                                        MimikyuBrowseCard(
                                            item = pair[1],
                                            onClick = { onPlaylistCategoryClick(pair[1].tag) },
                                            modifier = Modifier.weight(1f)
                                        )
                                    } else {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }

                            item(key = "genres_header") {
                                Text(
                                    text = "Genres",
                                    color = Color.White,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(top = 16.dp, bottom = 12.dp)
                                )
                            }

                            itemsIndexed(genreItems.chunked(2), key = { index, _ -> "genre_row_$index" }) { _, pair ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    MimikyuBrowseCard(
                                        item = pair[0],
                                        onClick = { onPlaylistCategoryClick(pair[0].tag) },
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (pair.size > 1) {
                                        MimikyuBrowseCard(
                                            item = pair[1],
                                            onClick = { onPlaylistCategoryClick(pair[1].tag) },
                                            modifier = Modifier.weight(1f)
                                        )
                                    } else {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }

                TAB_LOCAL_MUSIC -> {
                    Box(modifier = Modifier.fillMaxSize().padding(top = 75.dp)) {
                        com.lin0721.linmusic.feature.localmusic.ui.LocalMusicScreen(
                            showTopBar = false,
                            onBack = { onTabSelected(TAB_ALL) },
                            onArtistClick = onArtistClick,
                            onAlbumClick = { albumId -> onPlaylistClick(albumId, true) },
                            onLoginScreenVisibilityChanged = onLoginScreenVisibilityChanged
                        )
                    }
                }

                else -> HomeContent(
                    uiState = uiState,
                    recentRecords = recentRecords,
                    likedSongs = likedSongs,
                    userPlaylists = userPlaylists,
                    currentTrackId = currentTrack?.mediaId,
                    isPlaying = isPlaying,
                    onTrackClick = { track -> viewModel.playTrack(track, recentRecords.map { it.track }) },
                    onLikedSongClick = { song -> viewModel.playLikedSong(song, likedSongs) },
                    onLikedSongsPlaylistClick = onNavigateToLikedSongs,
                    onDownloadedSongsPlaylistClick = onNavigateToDownloadedSongs,
                    downloadedCount = downloadedRecords.size,
                    onSongClick = { shelfTitle, songs, song -> viewModel.playShelfSong(shelfTitle, songs, song) },
                    onPlaylistClick = onPlaylistClick,
                    onNavigateToRecentPlay = onNavigateToRecentPlay,
                    onConnectSpotifyClick = {
                        ToastManager.showToast("Spotify account linking setup")
                    },
                    onConnectYouTubeMusicClick = {
                        showYouTubeMusicLogin = true
                    },
                    onSearchClick = onSearchClick,
                    isRefreshing = isRefreshing,
                    onRefresh = { viewModel.refreshHomeData() },
                    listState = homeListState
                )
            }
        }

        HomeSharedHeader(
            userProfile = userProfile,
            selectedTab = selectedTab,
            onTabSelected = onTabSelected,
            secondarySelected = false,
            onSecondarySelected = {},
            showFilterPills = true,
            backgroundColor = MaterialTheme.colorScheme.background,
            onAvatarClick = onAvatarClick
        )

        if (showLoginSheet) {
            LoginBottomSheet(
                onDismiss = { showLoginSheet = false },
                onWebLogin = {
                    showLoginSheet = false
                    showWebViewLogin = true
                },
                onLoginSuccess = { cookies ->
                    showLoginSheet = false
                    viewModel.handleLoginSuccess(cookies)
                }
            )
        }

        AnimatedVisibility(
            visible = showWebViewLogin,
            enter = slideInVertically(tween(ScreenSlideDurationMs)) { it } + fadeIn(tween(ScreenSlideDurationMs)),
            exit = slideOutVertically(tween(ScreenSlideDurationMs)) { it } + fadeOut(tween(ScreenSlideDurationMs))
        ) {
            WebViewLoginScreen(
                onClose = { showWebViewLogin = false },
                onLoginSuccess = { cookies ->
                    showWebViewLogin = false
                    viewModel.handleLoginSuccess(cookies)
                }
            )
        }

        AnimatedVisibility(
            visible = showYouTubeMusicLogin,
            enter = slideInVertically(tween(ScreenSlideDurationMs)) { it } + fadeIn(tween(ScreenSlideDurationMs)),
            exit = slideOutVertically(tween(ScreenSlideDurationMs)) { it } + fadeOut(tween(ScreenSlideDurationMs))
        ) {
            YouTubeMusicLoginScreen(
                onClose = { showYouTubeMusicLogin = false },
                onLoginSuccess = { session ->
                    showYouTubeMusicLogin = false
                    viewModel.connectYouTubeAccount(session)
                }
            )
        }
    }
}
