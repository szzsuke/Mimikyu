package com.lin0721.linmusic.feature.playlist.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lin0721.linmusic.LocalBottomOverlayInset
import com.lin0721.linmusic.core.model.Track
import com.lin0721.linmusic.core.ui.components.PlaylistCollectItem
import com.lin0721.linmusic.core.ui.components.PlaylistCollectSheet
import com.lin0721.linmusic.core.ui.components.PlaylistCollectState
import com.lin0721.linmusic.core.ui.components.SearchResultRowSkeleton
import com.lin0721.linmusic.core.model.PlaylistDetail
import com.lin0721.linmusic.core.ui.theme.FallbackBase
import com.lin0721.linmusic.core.ui.theme.mimikyuStatusBarTopPadding
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.max

// TopBar （）
private val TOP_BAR_HEIGHT = 56.dp

private val COVER_MAX_SIZE = 260.dp

// ────────────────────────────────────────────────────────────────────────────
// ： +
// ────────────────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistContent(
    playlist: PlaylistDetail,
    currentTrackId: String?,
    isPlaying: Boolean,
    likedSongIds: Set<Long>,
    collectState: PlaylistCollectState,
    isLoggedIn: Boolean,
    userProfile: com.lin0721.linmusic.core.auth.UserProfile? = null,
    recommendedSongs: List<Track>,
    onBack: () -> Unit,
    onArtistClick: (Long) -> Unit,
    onAlbumClick: (Long) -> Unit,
    onToggleLike: (Long, Boolean) -> Unit,
    onPlaySong: (Track) -> Unit,
    onAddToPlayNext: (Track) -> Unit,
    onAddTracksToPlayNext: ((List<Track>) -> Unit)? = null,
    onPlayAll: () -> Unit,
    onShuffleToggle: () -> Unit,
    isShuffleActive: Boolean,
    isCurrentlyPlayingThis: Boolean,
    onLikeClick: (Long) -> Unit,
    onSaveCollection: (Long, List<PlaylistCollectItem>) -> Unit,
    onSaveNewCollection: (String, Long) -> Unit,
    onRequireLogin: () -> Unit,
    onRefreshRecommendations: () -> Unit,
    onAddRecommendSong: (Track) -> Unit,
    isSubscribed: Boolean,
    onSubscribeClick: () -> Unit,
    onMoreClick: () -> Unit,
    isLikedSongsPlaylistView: Boolean = false,
    onShareClick: () -> Unit = {},
    onDownloadClick: () -> Unit = {},
    onDownloadSongClick: (Track) -> Unit = {},
    onHistoryClick: () -> Unit = {},
    historyDates: List<String> = emptyList(),
    historySongsLoading: Boolean = false,
    showHistoryDatePicker: Boolean = false,
    selectedHistoryDate: String = "Today",
    onSelectedHistoryDateChange: (String) -> Unit = {},
    onLoadHistoryDetail: (String) -> Unit = {},
    onLoadDailyRecommend: () -> Unit = {},
    canRemoveFromPlaylist: Boolean = false,
    onRemoveFromPlaylist: (Long) -> Unit = {},
    onAddMusicClick: () -> Unit = {},
    onEditOrderClick: () -> Unit = {},
    onEditInfoClick: () -> Unit = {},
    hasMoreTracks: Boolean = false,
    isLoadingMoreTracks: Boolean = false,
    onLoadMoreTracks: () -> Unit = {},
    onLocateTrack: (Long) -> Unit = {},
    onCreatorClick: (Long) -> Unit = {},
    trackPlayCounts: Map<Long, Int> = emptyMap(),
    youMightLikeItems: List<CuratedMixItem> = emptyList(),
    onPlaylistClick: (Long, Boolean) -> Unit = { id, _ -> onAlbumClick(id) }
) {
    val density = LocalDensity.current

    val isDailyRecommend = playlist.id == -1L || playlist.id == -2L

    // Search item 0，Daily Recommendations
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    val canLoadMoreTracks = hasMoreTracks && !isLoadingMoreTracks
    LaunchedEffect(listState, canLoadMoreTracks) {
        if (!canLoadMoreTracks) return@LaunchedEffect
        snapshotFlow {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisible >= info.totalItemsCount - 3
        }
            .distinctUntilChanged()
            .collect { if (it) onLoadMoreTracks() }
    }

    var searchQuery by remember { mutableStateOf("") }
    var sortOption by remember { mutableStateOf(PlaylistSortOption.DEFAULT) }
    var showSortSheet by remember { mutableStateOf(false) }
    val sortedTracks = remember(playlist.tracks, sortOption) { sortOption.sort(playlist.tracks) }
    val filteredTracks = remember(sortedTracks, searchQuery) {
        if (searchQuery.isBlank()) sortedTracks
        else sortedTracks.filter {
            it.name.contains(searchQuery, true) || it.ar.any { a -> a.name.contains(searchQuery, true) }
        }
    }

    // ── CurrentPlaySongs：/ of
    val targetTrackId = currentTrackId?.toLongOrNull()
    val belongsToPlaylist = targetTrackId != null && if (playlist.trackIds.isNotEmpty()) {
        playlist.trackIds.any { it.id == targetTrackId }
    } else {
        playlist.tracks.any { it.id == targetTrackId }
    }
    // LazyColumn item count before track list: header + optional date pickers
    val trackListStartIndex = 1 +
        (if (playlist.id == -1L && showHistoryDatePicker) 1 else 0) +
        (if (playlist.id == -2L) 1 else 0)

    val targetIndexInFiltered = if (targetTrackId == null) -1 else filteredTracks.indexOfFirst { it.id == targetTrackId }
    val isTargetVisibleOnScreen by remember(targetIndexInFiltered, trackListStartIndex, historySongsLoading) {
        derivedStateOf {
            if (targetIndexInFiltered < 0 || historySongsLoading) return@derivedStateOf false
            val itemIndex = trackListStartIndex + targetIndexInFiltered
            listState.layoutInfo.visibleItemsInfo.any { it.index == itemIndex }
        }
    }
    val showLocateButton = belongsToPlaylist && !historySongsLoading && !isTargetVisibleOnScreen

    // of ，
    var dominantColor by remember { mutableStateOf(FallbackBase) }


    val statusBarHeight = mimikyuStatusBarTopPadding()
    // Overlay ： + (56dp)
    val overlayHeight = TOP_BAR_HEIGHT + statusBarHeight

    var pendingLocateTrackId by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(pendingLocateTrackId, filteredTracks) {
        val id = pendingLocateTrackId ?: return@LaunchedEffect
        val index = filteredTracks.indexOfFirst { it.id == id }
        if (index < 0) return@LaunchedEffect
        val itemIndex = trackListStartIndex + index
        // ，，
        listState.animateScrollToItem(itemIndex)
        val info = listState.layoutInfo
        val itemInfo = info.visibleItemsInfo.find { it.index == itemIndex }
        if (itemInfo != null) {
            // overlayHeight ，
            val overlayHeightPx = with(density) { overlayHeight.toPx() }
            val visibleTop = info.viewportStartOffset + overlayHeightPx
            val visibleCenter = (visibleTop + info.viewportEndOffset) / 2f
            val itemCenter = itemInfo.offset + itemInfo.size / 2f
            val delta = itemCenter - visibleCenter
            if (kotlin.math.abs(delta) > 1f) listState.animateScrollBy(delta)
        }
        pendingLocateTrackId = null
    }

    // 0f→1f（）；header  item 0，Playlists
    val collapseThresholdPx = with(density) { 300.dp.toPx() }
    val progress by remember {
        derivedStateOf {
            if (listState.firstVisibleItemIndex == 0) {
                (listState.firstVisibleItemScrollOffset / collapseThresholdPx).coerceIn(0f, 1f)
            } else {
                1f
            }
        }
    }

    val coverSize: Dp     = COVER_MAX_SIZE
    // ：，65%
    val coverAlpha        = 1f - (progress / 0.65f).coerceIn(0f, 1f)

    // Play：
    val dockedYPx = with(density) { (overlayHeight - 24.dp).toPx() }
    // of  Y（ PlaylistHeaderItem ，
    // ，）
    // listState.firstVisibleItemScrollOffset
    // ：Play/Pause，，
    var playButtonBaselineYPx by remember { mutableFloatStateOf(Float.MAX_VALUE) }

    var collectSongId by remember { mutableStateOf<Long?>(null) }
    var activeSongMoreOptions by remember { mutableStateOf<Track?>(null) }

    val searchBarRevealState = rememberSearchBarRevealState(
        listState        = listState,
        isDailyRecommend = isDailyRecommend
    )
    val searchFullyRevealed = searchBarRevealState.isOpen

    // SearchExpandBack：Search，Playlists
    BackHandler(enabled = searchBarRevealState.isOpen) {
        searchBarRevealState.close()
    }

    Box(modifier = Modifier.fillMaxSize()) {

        // ── 1. ： revealPx ，Search ───────────
        LazyColumn(
            state          = listState,
            contentPadding = PaddingValues(bottom = LocalBottomOverlayInset.current + 16.dp),
            modifier       = Modifier
                .graphicsLayer { translationY = searchBarRevealState.revealPx }
                .nestedScroll(searchBarRevealState.connection)
        ) {
            // Item 0： Hero；SearchExpandConfirmCollapse，Search
            item(key = "header") {
                AnimatedVisibility(
                    visible = !searchFullyRevealed,
                    enter   = fadeIn() + expandVertically(expandFrom = Alignment.Top),
                    exit    = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top)
                ) {
                    PlaylistHeaderItem(
                        playlist            = playlist,
                        coverSize           = coverSize,
                        coverAlpha          = coverAlpha,
                        progress            = progress,
                        statusBarHeight     = statusBarHeight,
                        dominantColor       = dominantColor,
                        onColorCalculated   = { dominantColor = it },
                        onPlayAll              = onPlayAll,
                        onShuffleToggle        = onShuffleToggle,
                        isShuffleActive        = isShuffleActive,
                        isCurrentlyPlayingThis = isCurrentlyPlayingThis,
                        isSubscribed        = isSubscribed,
                        onSubscribeClick    = onSubscribeClick,
                        onMoreClick         = onMoreClick,
                        isLikedSongsPlaylistView = isLikedSongsPlaylistView,
                        isOwnedPlaylist     = canRemoveFromPlaylist,
                        userProfile         = userProfile,
                        onAddMusicClick     = onAddMusicClick,
                        onEditOrderClick    = {
                            sortOption = PlaylistSortOption.DEFAULT
                            onEditOrderClick()
                        },
                        onShareClick        = onShareClick,
                        onDownloadClick     = onDownloadClick,
                        onAddToQueueClick   = {
                            if (playlist.tracks.isNotEmpty()) {
                                onAddTracksToPlayNext?.invoke(playlist.tracks)
                            }
                        },
                        onSearchClick       = { searchBarRevealState.open() },
                        onSortClick         = { showSortSheet = true },
                        onHistoryClick      = onHistoryClick,
                        selectedHistoryDate = selectedHistoryDate,
                        onCreatorClick      = onCreatorClick,
                        onPlayButtonPositioned = { y ->
                            if (listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0) {
                                playButtonBaselineYPx = y
                            }
                        },
                        onBack = onBack
                    )
                }
            }

            if (playlist.id == -1L && showHistoryDatePicker) {
                item(key = "history_date_picker") {
                    PlaylistHistoryDateRow(
                        historyDates                = historyDates,
                        selectedHistoryDate         = selectedHistoryDate,
                        onSelectedHistoryDateChange = onSelectedHistoryDateChange,
                        onLoadHistoryDetail         = onLoadHistoryDetail,
                        onLoadDailyRecommend        = onLoadDailyRecommend
                    )
                }
            }

            if (playlist.id == -2L) {
                item(key = "user_record_filter") {
                    PlaylistRecordFilterRow(
                        selectedHistoryDate         = selectedHistoryDate,
                        onSelectedHistoryDateChange = onSelectedHistoryDateChange,
                        onLoadHistoryDetail         = onLoadHistoryDetail
                    )
                }
            }

            if (historySongsLoading) {
                items(6, key = { "history_skel_$it" }) {
                    SearchResultRowSkeleton()
                }
            } else {
                playlistTrackItems(
                    tracks             = filteredTracks,
                    currentTrackId     = currentTrackId,
                    isPlaying          = isPlaying,
                    likedSongIds       = likedSongIds,
                    isLoggedIn         = isLoggedIn,
                    onPlaySong         = onPlaySong,
                    onLikeClick        = onLikeClick,
                    onOpenCollectSheet = { collectSongId = it },
                    onMoreClick        = { activeSongMoreOptions = it },
                    trackPlayCounts    = trackPlayCounts
                )

                if (isLoadingMoreTracks) {
                    item(key = "track_load_more") {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }

            // RecommendedSongs；SearchExpand，Search
            if (recommendedSongs.isNotEmpty() && !searchFullyRevealed) {
                playlistRecommendItems(
                    recommendedSongs         = recommendedSongs,
                    currentTrackId           = currentTrackId,
                    isPlaying                = isPlaying,
                    onRefreshRecommendations = onRefreshRecommendations,
                    onPlaySong               = onPlaySong,
                    onAddRecommendSong       = onAddRecommendSong
                )
            }

            // ── Mimikyu "You might also like" 2-Column Curated Grid (ss_latest_1920.jpg) ──
            if (!searchFullyRevealed && !isDailyRecommend) {
                playlistYouMightLikeSection(
                    items = youMightLikeItems,
                    onItemClick = onPlaylistClick
                )
            }
        }

        // ── 2. Search：， revealPx Expand ─────
        if (!isDailyRecommend) {
            SearchBarItem(
                query              = searchQuery,
                onQueryChange      = { searchQuery = it },
                topPadding         = overlayHeight,
                backgroundColor    = dominantColor,
                isOpen             = searchFullyRevealed,
                onClose            = { searchBarRevealState.close() },
                modifier           = Modifier
                    .onSizeChanged { searchBarRevealState.searchBarHeightPx = it.height.toFloat() }
                    .graphicsLayer {
                        translationY = searchBarRevealState.revealPx - searchBarRevealState.searchBarHeightPx
                    }
            )
        }

        // ── 3.  Overlay ───────────────────────────────────────────────
        PlaylistTopBar(
            title           = playlist.name,
            progress        = if (searchFullyRevealed) 1f else progress,
            overlayHeight   = overlayHeight,
            statusBarHeight = statusBarHeight,
            dominantColor   = dominantColor,
            // SearchExpand：BackSearch，Playlists
            onBack          = {
                if (searchBarRevealState.isOpen) searchBarRevealState.close() else onBack()
            },
            onScrollToTop   = {
                coroutineScope.launch {
                    listState.animateScrollToItem(0)
                }
            }
        )

        // Play、；SearchExpand
        if (!isDailyRecommend && !searchFullyRevealed) {
            PlaylistDockedPlayButton(
                dockedOffsetYProvider = {
                    val naturalY = if (listState.firstVisibleItemIndex == 0) {
                        playButtonBaselineYPx - listState.firstVisibleItemScrollOffset
                    } else {
                        -Float.MAX_VALUE
                    }
                    max(naturalY, dockedYPx)
                },
                isCurrentlyPlayingThis = isCurrentlyPlayingThis,
                onPlayAll              = onPlayAll
            )
        }

        // Locate currently playing track，Playlists
        if (showLocateButton) {
            FloatingActionButton(
                onClick = {
                    val id = targetTrackId ?: return@FloatingActionButton
                    if (searchQuery.isNotEmpty()) searchQuery = ""
                    pendingLocateTrackId = id
                    if (sortedTracks.none { it.id == id }) {
                        onLocateTrack(id)
                    }
                },
                containerColor = MaterialTheme.colorScheme.primary,
                shape = CircleShape,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = LocalBottomOverlayInset.current + 16.dp)
                    .size(48.dp)
            ) {
                if (isLoadingMoreTracks) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.CenterFocusStrong,
                        contentDescription = "Locate currently playing track",
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }
    }

    if (collectSongId != null) {
        PlaylistCollectSheet(
            songId = collectSongId!!,
            collectState = collectState,
            onDismiss = { collectSongId = null },
            onSaveCollection = onSaveCollection,
            onSaveNewCollection = onSaveNewCollection
        )
    }

    if (activeSongMoreOptions != null) {
        val track = activeSongMoreOptions!!
        PlaylistSongOptionsSheet(
            track = track,
            isLiked = track.id in likedSongIds,
            isLoggedIn = isLoggedIn,
            onDismiss = { activeSongMoreOptions = null },
            onAddToPlayNext = onAddToPlayNext,
            onToggleLike = onToggleLike,
            onCollectClick = { songId ->
                collectSongId = songId
                onLikeClick(songId)
            },
            onArtistClick = onArtistClick,
            onAlbumClick = onAlbumClick,
            onRequireLogin = onRequireLogin,
            canRemoveFromPlaylist = canRemoveFromPlaylist,
            onRemoveFromPlaylist = onRemoveFromPlaylist,
            onDownloadClick = onDownloadSongClick
        )
    }

    if (showSortSheet) {
        PlaylistSortSheet(
            sortOption = sortOption,
            onSortOptionChange = {
                sortOption = it
                showSortSheet = false
            },
            onDismiss = { showSortSheet = false }
        )
    }
}

