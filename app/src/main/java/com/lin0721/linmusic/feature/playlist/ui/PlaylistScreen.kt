package com.lin0721.linmusic.feature.playlist.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lin0721.linmusic.core.player.PlayMode
import com.lin0721.linmusic.core.ui.components.MimikyuTextButton
import com.lin0721.linmusic.core.ui.components.MimikyuButton
import com.lin0721.linmusic.core.ui.components.LoginBottomSheet
import com.lin0721.linmusic.core.ui.components.WebViewLoginScreen
import com.lin0721.linmusic.core.ui.components.MimikyuDragHandle
import androidx.annotation.DrawableRes
import androidx.compose.ui.res.painterResource
import com.lin0721.linmusic.core.ui.theme.MimikyuIcons
import com.lin0721.linmusic.core.ui.theme.BottomSheetShape
import com.lin0721.linmusic.core.ui.theme.MimikyuSpacing
import com.lin0721.linmusic.core.ui.theme.ScreenSlideDurationMs
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.zIndex
import coil.compose.SubcomposeAsyncImage
import com.lin0721.linmusic.core.ui.components.CoverPlaceholder
import com.lin0721.linmusic.core.ui.components.PlaylistScreenSkeleton
import com.canhub.cropper.CropImageContract
import com.canhub.cropper.CropImageContractOptions
import com.canhub.cropper.CropImageOptions
import com.canhub.cropper.CropImageView
import com.lin0721.linmusic.LocalBottomOverlayInset
import com.lin0721.linmusic.LocalGlobalOverlayOpen
import com.lin0721.linmusic.core.model.Track
import com.lin0721.linmusic.core.model.isLikedSongsPlaylist
import com.lin0721.linmusic.core.ui.components.DraggableSongRow
import com.lin0721.linmusic.core.ui.components.SongRowData
import com.lin0721.linmusic.core.ui.theme.mimikyuStatusBarTopPadding
import java.util.Collections
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistScreen(
    playlistId: Long,
    isAlbum: Boolean = false,
    viewModel: PlaylistViewModel = koinViewModel(),
    onBack: () -> Unit,
    onArtistClick: (Long) -> Unit,
    onAlbumClick: (Long) -> Unit,
    onPlaylistClick: (Long) -> Unit = onAlbumClick,
    onNavigateToProfile: (Long) -> Unit = {}
) {
    val uiState      by viewModel.uiState.collectAsStateWithLifecycle()
    val currentTrack by viewModel.playerManager.currentTrack.collectAsStateWithLifecycle()
    val isPlaying by viewModel.playerManager.isPlaying.collectAsStateWithLifecycle()
    val playMode by viewModel.playerManager.playMode.collectAsStateWithLifecycle()
    val playContext by viewModel.playerManager.playContext.collectAsStateWithLifecycle()
    val likedSongIds by viewModel.likedSongIds.collectAsStateWithLifecycle()
    val collectState by viewModel.collectState.collectAsStateWithLifecycle()
    val userProfile  by viewModel.userProfile.collectAsStateWithLifecycle()
    val historyRecommendState by viewModel.historyRecommendState.collectAsStateWithLifecycle()
    val importState by viewModel.importState.collectAsStateWithLifecycle()
    val addMusicSearchQuery by viewModel.addMusicSearchQuery.collectAsStateWithLifecycle()
    val addMusicSearchState by viewModel.addMusicSearchState.collectAsStateWithLifecycle()
    val isSavingInfo by viewModel.isSavingInfo.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var showMimikyuShareSheet by remember { mutableStateOf(false) }
    var shareTitle by remember { mutableStateOf("") }
    var shareId by remember { mutableStateOf(0L) }

    fun sharePlaylistOrAlbum(name: String, id: Long) {
        shareTitle = name
        shareId = id
        showMimikyuShareSheet = true
    }
    // uri ，「Save」，
    // and /
    var pendingCoverUri by remember { mutableStateOf<Uri?>(null) }
    val coverCropLauncher = rememberLauncherForActivityResult(CropImageContract()) { result ->
        if (!result.isSuccessful) {
            result.error?.message?.let { com.lin0721.linmusic.core.ui.components.ToastManager.showToast(it) }
            return@rememberLauncherForActivityResult
        }
        pendingCoverUri = result.uriContent
    }
    val launchCoverCropper = {
        coverCropLauncher.launch(
            CropImageContractOptions(
                uri = null,
                cropImageOptions = CropImageOptions(
                    imageSourceIncludeCamera = false,
                    imageSourceIncludeGallery = true,
                    cropShape = CropImageView.CropShape.RECTANGLE,
                    fixAspectRatio = true,
                    aspectRatioX = 1,
                    aspectRatioY = 1,
                    outputCompressFormat = android.graphics.Bitmap.CompressFormat.JPEG,
                    outputCompressQuality = 90,
                    outputRequestWidth = 1024,
                    outputRequestHeight = 1024,
                    outputRequestSizeOptions = CropImageView.RequestSizeOptions.RESIZE_INSIDE,
                    // 、 of ，，+
                    activityBackgroundColor = android.graphics.Color.BLACK,
                    toolbarColor = android.graphics.Color.BLACK,
                    toolbarTintColor = android.graphics.Color.WHITE,
                    toolbarTitleColor = android.graphics.Color.WHITE,
                    toolbarBackButtonColor = android.graphics.Color.WHITE,
                    activityMenuIconColor = android.graphics.Color.WHITE
                )
            )
        )
    }

    var showLoginSheet by remember { mutableStateOf(false) }
    var showWebViewLogin by remember { mutableStateOf(false) }
    var showMoreMenuSheet by remember { mutableStateOf(false) }
    var showImportTargetSheet by remember { mutableStateOf(false) }
    var showEditInfoDialog by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var showAddMusicSheet by remember { mutableStateOf(false) }
    var showDownloadQualitySheet by remember { mutableStateOf(false) }
    var pendingDownloadTrack by remember { mutableStateOf<Track?>(null) }
    var isReorderMode by remember { mutableStateOf(false) }
    var isSavingOrder by remember { mutableStateOf(false) }
    var showDiscardConfirmDialog by remember { mutableStateOf(false) }
    val reorderedTracks = remember { mutableStateListOf<Track>() }
    var selectedHistoryDate by remember { mutableStateOf("Today") }

    // reorderedTracks  of  SnapshotStateList，remember(keys) ， derivedStateOf
    val isOrderChanged by remember {
        derivedStateOf {
            val current = (uiState as? PlaylistUiState.Success)?.playlist?.tracks.orEmpty()
            reorderedTracks.isNotEmpty() && reorderedTracks != current
        }
    }

    // ， of Back
    val isGlobalOverlayOpen = LocalGlobalOverlayOpen.current

    BackHandler(enabled = isReorderMode && !isGlobalOverlayOpen) {
        if (!isSavingOrder) {
            if (isOrderChanged) {
                showDiscardConfirmDialog = true
            } else {
                isReorderMode = false
            }
        }
    }



    LaunchedEffect(historyRecommendState.selectedDate) {
        selectedHistoryDate = historyRecommendState.selectedDate ?: "Today"
    }

    LaunchedEffect(viewModel) {
        viewModel.toastEvent.collect { com.lin0721.linmusic.core.ui.components.ToastManager.showToast(it) }
    }
    LaunchedEffect(playlistId, isAlbum) {
        viewModel.loadPlaylist(playlistId, isAlbum)
        if (playlistId == -1L) {
            viewModel.loadHistoryDates()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when (val state = uiState) {
            is PlaylistUiState.Loading ->
                PlaylistScreenSkeleton()
            is PlaylistUiState.Error ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Failed to load", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(MimikyuSpacing.sm))
                        Text(state.message, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                        Spacer(Modifier.height(MimikyuSpacing.md))
                        MimikyuButton(onClick = { viewModel.loadPlaylist(playlistId, isAlbum) },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)) {
                            Text("Retry", color = MaterialTheme.colorScheme.onPrimary)
                        }
                        MimikyuTextButton(onClick = onBack) { Text("Back", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            is PlaylistUiState.Success -> {
                val profile = userProfile
                val isLikedSongsPlaylistView = profile != null &&
                    isLikedSongsPlaylist(state.playlist.name, state.playlist.id, profile.uid)
                val isOwnedPlaylist = !isLikedSongsPlaylistView && (
                    state.playlist.isEditable ||
                    state.playlist.creator?.nickname == "My Playlist" ||
                    state.playlist.description?.contains("Imported playlist", ignoreCase = true) == true ||
                    (profile != null && state.playlist.id > 0L && state.playlist.creator?.userId == profile.uid) ||
                    (state.playlist.id > 0L && com.lin0721.linmusic.core.userplaylist.LocalCustomPlaylistStore.isCustomPlaylist(state.playlist.id))
                )
                val isShuffleActive = playMode == PlayMode.SHUFFLE
                // CurrentPlayQueue of Playlists（playContext  of Playlists）
                val isThisPlaylistContext = playContext == state.playlist.name
                val isCurrentlyPlayingThis = isThisPlaylistContext && isPlaying

                // / MimikyuNavHost  of （+/+），
                // ： isReorderMode  lambda  reordering，

                AnimatedContent(
                    targetState = isReorderMode,
                    transitionSpec = {
                        val forward = targetState
                        val offsetY = 40
                        if (forward) {
                            (fadeIn(tween(300, delayMillis = 100, easing = FastOutSlowInEasing))
                                    + slideInVertically(tween(300, delayMillis = 100, easing = FastOutSlowInEasing)) { offsetY })
                                .togetherWith(
                                    fadeOut(tween(200, easing = FastOutSlowInEasing))
                                            + slideOutVertically(tween(200, easing = FastOutSlowInEasing)) { -offsetY }
                                )
                        } else {
                            (fadeIn(tween(300, delayMillis = 100, easing = FastOutSlowInEasing))
                                    + slideInVertically(tween(300, delayMillis = 100, easing = FastOutSlowInEasing)) { -offsetY })
                                .togetherWith(
                                    fadeOut(tween(200, easing = FastOutSlowInEasing))
                                            + slideOutVertically(tween(200, easing = FastOutSlowInEasing)) { offsetY }
                                )
                        }.using(SizeTransform(clip = false))
                    },
                    label = "playlist_reorder_transition"
                ) { reordering ->
                if (reordering) {
                    val statusBarHeight = mimikyuStatusBarTopPadding()
                    val overlayHeight = 56.dp + statusBarHeight
                    val density = LocalDensity.current
                    val haptic = LocalHapticFeedback.current
                    val scope = rememberCoroutineScope()
                    val reorderListState = rememberLazyListState()

                    // ，， of 。
                    // 60dp（44dp  +  8dp）：，
                    // ，
                    val fallbackItemHeightPx = with(density) { 60.dp.toPx() }
                    var itemHeightPx by remember { mutableFloatStateOf(fallbackItemHeightPx) }
                    LaunchedEffect(reorderListState) {
                        snapshotFlow { reorderListState.layoutInfo.visibleItemsInfo.firstOrNull()?.size ?: 0 }
                            .collect { if (it > 0) itemHeightPx = it.toFloat() }
                    }

                    val topEdgeThreshold = with(density) { 80.dp.toPx() }
                    val bottomEdgeThreshold = with(density) { 100.dp.toPx() }
                    val bottomOverlayInsetPx = with(density) { LocalBottomOverlayInset.current.toPx() }

                    var draggedIndex by remember { mutableIntStateOf(-1) }
                    var dragOffset by remember { mutableFloatStateOf(0f) }
                    val isDraggingActive = draggedIndex >= 0

                    // 0  of ：
                    var settlingIndex by remember { mutableIntStateOf(-1) }
                    val settleOffset = remember { Animatable(0f) }

                    // LazyColumn  index  of ， of （
                    // pointerInput ）。swap  dragOffset/ draggedIndex，
                    // ——， draggedIndex Current of ，
                    // 。， draggedIndex，

                    fun maxComposedIndex(): Int =
                        reorderListState.layoutInfo.visibleItemsInfo.maxOfOrNull { it.index } ?: reorderedTracks.lastIndex
                    fun minComposedIndex(): Int =
                        reorderListState.layoutInfo.visibleItemsInfo.minOfOrNull { it.index } ?: 0

                    // /：，。
                    // ，（）——
                    // swap composed-range  dragOffset ，。
                    // and  of  itemHeightPx： dragOffset 。
                    // itemHeightPx  val，， of
                    fun clampDragOffset(offset: Float): Float = when {
                        (draggedIndex <= 0 || draggedIndex <= minComposedIndex()) && offset < 0f -> max(offset, -itemHeightPx)
                        (draggedIndex >= reorderedTracks.lastIndex || draggedIndex >= maxComposedIndex()) && offset > 0f -> min(offset, itemHeightPx)
                        else -> offset
                    }

                    // and  of ：dragOffset  and ，
                    // of 。，
                    // and  ItemTouchHelper ； of  dragOffset  of ，

                    // LazyColumn  of  of  key， key  index，
                    // ，，。
                    // ，，，
                    // index （requestScrollToItem  index、 key）
                    fun neutralizeAnchorShift(from: Int, to: Int) {
                        val anchor = reorderListState.firstVisibleItemIndex
                        if (from != anchor && to != anchor) return
                        reorderListState.requestScrollToItem(anchor, reorderListState.firstVisibleItemScrollOffset)
                    }

                    fun advanceSwaps() {
                        var swapped = false
                        while (dragOffset > itemHeightPx && draggedIndex < reorderedTracks.lastIndex && draggedIndex < maxComposedIndex()) {
                            neutralizeAnchorShift(draggedIndex, draggedIndex + 1)
                            Collections.swap(reorderedTracks, draggedIndex, draggedIndex + 1)
                            draggedIndex += 1
                            dragOffset = clampDragOffset(dragOffset - itemHeightPx)
                            swapped = true
                        }
                        while (dragOffset < -itemHeightPx && draggedIndex > 0 && draggedIndex > minComposedIndex()) {
                            neutralizeAnchorShift(draggedIndex, draggedIndex - 1)
                            Collections.swap(reorderedTracks, draggedIndex, draggedIndex - 1)
                            draggedIndex -= 1
                            dragOffset = clampDragOffset(dragOffset + itemHeightPx)
                            swapped = true
                        }
                        if (swapped) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }

                    // of （）。
                    // delay(16)： px/ ，
                    // 60Hz
                    LaunchedEffect(isDraggingActive) {
                        if (!isDraggingActive) return@LaunchedEffect
                        var edgeDurationMs = 0f
                        var lastFrameNanos = 0L
                        while (isActive && draggedIndex >= 0) {
                            val frameNanos = withFrameNanos { it }
                            val deltaMs = if (lastFrameNanos == 0L) {
                                16f
                            } else {
                                ((frameNanos - lastFrameNanos) / 1_000_000f).coerceIn(1f, 64f)
                            }
                            lastFrameNanos = frameNanos

                            val layoutInfo = reorderListState.layoutInfo
                            val draggingItem = layoutInfo.visibleItemsInfo.firstOrNull { it.index == draggedIndex }
                            if (draggingItem == null) {
                                edgeDurationMs = 0f
                                continue
                            }

                            val currentTop = draggingItem.offset + dragOffset
                            val currentBottom = currentTop + draggingItem.size
                            val effectiveViewportEnd = layoutInfo.viewportEndOffset - bottomOverlayInsetPx

                            val isNearTop = currentTop < topEdgeThreshold && draggedIndex > 0
                            val isNearBottom = currentBottom > (effectiveViewportEnd - bottomEdgeThreshold) &&
                                draggedIndex < reorderedTracks.lastIndex
                            if (!isNearTop && !isNearBottom) {
                                edgeDurationMs = 0f
                                continue
                            }

                            edgeDurationMs += deltaMs
                            // ： 1.5s  1.0  3.5
                            val timeMultiplier = 1f + (edgeDurationMs / 600f).coerceAtMost(2.5f)
                            val depthRatio = if (isNearTop) {
                                ((topEdgeThreshold - currentTop) / topEdgeThreshold).coerceIn(0f, 1f)
                            } else {
                                ((currentBottom - (effectiveViewportEnd - bottomEdgeThreshold)) / bottomEdgeThreshold).coerceIn(0f, 1f)
                            }
                            val speedPxPerSec = (depthRatio * 1000f + 300f) * timeMultiplier
                            val stepPx = speedPxPerSec * deltaMs / 1000f

                            val scrolled = reorderListState.scrollBy(if (isNearTop) -stepPx else stepPx)
                            dragOffset = clampDragOffset(dragOffset + scrolled)
                            advanceSwaps()
                        }
                    }

                    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        PlaylistReorderTopBar(
                            overlayHeight = overlayHeight,
                            statusBarHeight = statusBarHeight,
                            isSaving = isSavingOrder,
                            onCancel = {
                                if (!isSavingOrder) {
                                    if (isOrderChanged) {
                                        showDiscardConfirmDialog = true
                                    } else {
                                        isReorderMode = false
                                    }
                                }
                            },
                            onDone = {
                                if (!isOrderChanged) {
                                    isReorderMode = false
                                } else {
                                    isSavingOrder = true
                                    // of  of ，Save of （enabled=false），
                                    // of ， and
                                    draggedIndex = -1
                                    dragOffset = 0f
                                    settlingIndex = -1
                                    viewModel.updateTrackOrder(state.playlist.id, reorderedTracks.toList()) { success ->
                                        isSavingOrder = false
                                        if (success) {
                                            isReorderMode = false
                                        }
                                    }
                                }
                            }
                        )

                        LazyColumn(
                            state = reorderListState,
                            // ， of  scroll mutex
                            userScrollEnabled = !isDraggingActive,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = overlayHeight),
                            contentPadding = PaddingValues(bottom = LocalBottomOverlayInset.current + 16.dp)
                        ) {
                            itemsIndexed(
                                items = reorderedTracks,
                                key = { idx, item -> "${item.id}_$idx" }
                            ) { idx, item ->
                                val isDragging = draggedIndex == idx
                                val isSettling = settlingIndex == idx
                                Box(
                                    // of ， animateItem  translationY
                                    modifier = if (isDragging || isSettling) Modifier.zIndex(1f) else Modifier.animateItem()
                                ) {
                                    DraggableSongRow(
                                        data = SongRowData(
                                            id = item.id,
                                            title = item.name,
                                            artist = item.ar.joinToString("/") { it.name },
                                            coverUrl = item.al.picUrl
                                        ),
                                        isCurrent = currentTrack?.mediaId == item.id.toString(),
                                        isPlaying = isPlaying,
                                        isPlayed = false,
                                        isDragging = isDragging,
                                        dragOffsetY = when {
                                            isDragging -> dragOffset
                                            isSettling -> settleOffset.value
                                            else -> 0f
                                        },
                                        enabled = !isSavingOrder,
                                        onClick = {},
                                        onDragStart = {
                                            // 。 draggedIndex，
                                            // ，
                                            if (draggedIndex < 0) {
                                                settlingIndex = -1
                                                draggedIndex = idx
                                                dragOffset = 0f
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            }
                                        },
                                        onDrag = { delta ->
                                            // idx  and  draggedIndex ，，
                                            // of
                                            if (draggedIndex == idx) {
                                                dragOffset = clampDragOffset(dragOffset + delta)
                                                advanceSwaps()
                                            }
                                        },
                                        onDragEnd = {
                                            if (draggedIndex == idx) {
                                                val releasedOffset = dragOffset
                                                draggedIndex = -1
                                                dragOffset = 0f
                                                if (releasedOffset != 0f) {
                                                    settlingIndex = idx
                                                    scope.launch {
                                                        settleOffset.snapTo(releasedOffset)
                                                        settleOffset.animateTo(
                                                            targetValue = 0f,
                                                            animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
                                                        )
                                                        settlingIndex = -1
                                                    }
                                                }
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                } else {
                    PlaylistContent(
                        playlist       = state.playlist,
                        trackPlayCounts = state.trackPlayCounts,
                        canRemoveFromPlaylist = isOwnedPlaylist,
                        onRemoveFromPlaylist = { songId ->
                            viewModel.removeTrackFromPlaylist(state.playlist.id, songId)
                        },
                        onAddMusicClick = { showAddMusicSheet = true },
                        onEditOrderClick = {
                            // Save， of ，
                            viewModel.ensureAllTracksLoaded { allTracks ->
                                reorderedTracks.clear()
                                reorderedTracks.addAll(allTracks)
                                isReorderMode = true
                            }
                        },
                        onEditInfoClick = { showEditInfoDialog = true },
                        hasMoreTracks = state.hasMoreTracks,
                        isLoadingMoreTracks = state.isLoadingMoreTracks,
                        onLoadMoreTracks = { viewModel.loadMoreTracks() },
                        onLocateTrack = { trackId -> viewModel.ensureTrackLoaded(trackId) },
                        currentTrackId = currentTrack?.mediaId,
                    isPlaying      = isPlaying,
                    likedSongIds   = likedSongIds,
                    collectState   = collectState,
                    isLoggedIn     = userProfile != null,
                    userProfile    = userProfile,
                    recommendedSongs = state.recommendedSongs,
                    youMightLikeItems = state.youMightLikeItems,
                    onBack         = onBack,
                    onArtistClick  = onArtistClick,
                    onAlbumClick   = onAlbumClick,
                    onPlaylistClick = { id, isAlbumItem ->
                        if (isAlbumItem) onAlbumClick(id) else onPlaylistClick(id)
                    },
                    onToggleLike   = viewModel::toggleLikeSong,
                    onPlaySong     = { track ->
                        viewModel.playSongInList(track, state.playlist.tracks)
                    },
                    onAddToPlayNext = { track ->
                        viewModel.addTrackToPlayNext(track)
                    },
                    onAddTracksToPlayNext = { tracks ->
                        viewModel.addTracksToPlayNext(tracks)
                    },
                    onPlayAll = {
                        if (isThisPlaylistContext) {
                            // CurrentPlayQueue，PlayPause/
                            viewModel.playerManager.togglePlayPause()
                        } else {
                            val tracks = state.playlist.tracks
                            if (tracks.isNotEmpty()) {
                                // Play，CurrentPlayPlay
                                val ordered = if (isShuffleActive) tracks.shuffled() else tracks
                                viewModel.playSongInList(ordered.first(), ordered)
                            }
                        }
                    },
                    isShuffleActive = isShuffleActive,
                    isCurrentlyPlayingThis = isCurrentlyPlayingThis,
                    onShuffleToggle = {
                        // ，Play；Play，Play of
                        viewModel.playerManager.toggleShuffle()
                    },
                    onLikeClick = { songId ->
                        viewModel.prepareCollectDialog(songId)
                    },
                    onSaveCollection = { songId, items ->
                        viewModel.savePlaylistCollection(songId, items)
                    },
                    onSaveNewCollection = { name, songId ->
                        viewModel.createPlaylistAndAddSong(name, songId)
                    },
                    onRequireLogin = {
                        showLoginSheet = true
                    },
                    onRefreshRecommendations = {
                        viewModel.refreshRecommendations()
                    },
                    onAddRecommendSong = { track ->
                        viewModel.addRecommendSongToPlaylist(state.playlist.id, track)
                    },
                    isSubscribed = state.isSubscribed,
                    onSubscribeClick = {
                        if (userProfile == null) {
                            showLoginSheet = true
                        } else {
                            viewModel.toggleSubscribePlaylist()
                        }
                    },
                    onMoreClick = {
                        showMoreMenuSheet = true
                    },
                    isLikedSongsPlaylistView = isLikedSongsPlaylistView,
                    onShareClick = { sharePlaylistOrAlbum(state.playlist.name, state.playlist.id) },
                    onDownloadClick = { showDownloadQualitySheet = true },
                    onDownloadSongClick = { pendingDownloadTrack = it },
                    onHistoryClick = {},
                    historyDates = historyRecommendState.dates,
                    historySongsLoading = historyRecommendState.songsLoading,
                    showHistoryDatePicker = true,
                    selectedHistoryDate = selectedHistoryDate,
                    onSelectedHistoryDateChange = { selectedHistoryDate = it },
                    onLoadHistoryDetail = { viewModel.loadHistoryDetail(it) },
                    onLoadDailyRecommend = { viewModel.loadPlaylist(-1L) },
                    onCreatorClick = onNavigateToProfile
                )
            }
            }
        }
    }

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





        val successState = uiState as? PlaylistUiState.Success
        if (showDownloadQualitySheet && successState != null) {
            com.lin0721.linmusic.core.download.ui.DownloadQualityPickerSheet(
                onQualitySelected = { level ->
                    viewModel.downloadPlaylist(
                        successState.playlist.id, successState.playlist.name, successState.playlist.tracks, level
                    )
                    showDownloadQualitySheet = false
                },
                onDismiss = { showDownloadQualitySheet = false }
            )
        }

        if (pendingDownloadTrack != null) {
            val trackToDownload = pendingDownloadTrack!!
            com.lin0721.linmusic.core.download.ui.DownloadQualityPickerSheet(
                onQualitySelected = { level ->
                    viewModel.downloadTrack(trackToDownload, level)
                    pendingDownloadTrack = null
                },
                onDismiss = { pendingDownloadTrack = null }
            )
        }

        if (showMoreMenuSheet && successState != null) {
            val playlist = successState.playlist
            ModalBottomSheet(
                onDismissRequest = { showMoreMenuSheet = false },
                containerColor = MaterialTheme.colorScheme.background,
                shape = BottomSheetShape,
                dragHandle = { MimikyuDragHandle() }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(bottom = MimikyuSpacing.md)
                ) {
                    val firstArtist = playlist.tracks.firstOrNull()?.ar?.firstOrNull()
                    val artistName = firstArtist?.name ?: "Unknown Artist"
                    val resourceLabel = if (isAlbum) "Albums" else "Playlists"

                    val subtitleText = buildString {
                        val author = if (isAlbum) artistName else playlist.creator?.nickname
                        if (!author.isNullOrBlank()) {
                            append(author)
                        }
                        val totalTrackCount = if (playlist.trackIds.isNotEmpty()) playlist.trackIds.size else playlist.tracks.size
                        if (totalTrackCount > 0) {
                            if (isNotEmpty()) append(" · ")
                            append("${totalTrackCount} tracks")
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SubcomposeAsyncImage(
                            model = if (playlist.coverImgUrl.isNotEmpty()) "${playlist.coverImgUrl}?param=150y150" else null,
                            contentDescription = playlist.name,
                            contentScale = ContentScale.Crop,
                            loading = { CoverPlaceholder() },
                            error = { CoverPlaceholder() },
                            modifier = Modifier
                                .size(54.dp)
                                .clip(MaterialTheme.shapes.small)
                        )
                        Spacer(modifier = Modifier.width(MimikyuSpacing.md))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = playlist.name,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (subtitleText.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(MimikyuSpacing.xs))
                                Text(
                                    text = subtitleText,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    HorizontalDivider(
                        color = Color.White.copy(alpha = 0.08f),
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = MimikyuSpacing.sm)
                    )

                    val isManageable = !isAlbum && (
                        playlist.isEditable ||
                        playlist.creator?.nickname == "My Playlist" ||
                        playlist.description?.contains("Imported playlist", ignoreCase = true) == true ||
                        (userProfile != null && playlist.id > 0L && playlist.creator?.userId == userProfile?.uid) ||
                        (playlist.id > 0L && com.lin0721.linmusic.core.userplaylist.LocalCustomPlaylistStore.isCustomPlaylist(playlist.id))
                    )

                    val menuItems = buildList {
                        add(
                            PlaylistMenuItem(
                                iconRes = if (successState.isSubscribed) MimikyuIcons.HeartFilled else MimikyuIcons.Heart,
                                title = if (successState.isSubscribed) "Remove from saved $resourceLabel" else "Save $resourceLabel"
                            ) {
                                showMoreMenuSheet = false
                                if (userProfile == null) {
                                    showLoginSheet = true
                                } else {
                                    viewModel.toggleSubscribePlaylist()
                                }
                            }
                        )
                        add(
                            PlaylistMenuItem(
                                iconRes = MimikyuIcons.User,
                                title = "Go to Artist"
                            ) {
                                showMoreMenuSheet = false
                                if (firstArtist != null) {
                                    onArtistClick(firstArtist.id)
                                } else {
                                    com.lin0721.linmusic.core.ui.components.ToastManager.showToast("Artist info not found")
                                }
                            }
                        )
                        add(
                            PlaylistMenuItem(
                                iconRes = MimikyuIcons.AddToQueue,
                                title = "Add to Queue"
                            ) {
                                showMoreMenuSheet = false
                                viewModel.addTracksToPlayNext(playlist.tracks)
                            }
                        )
                        add(
                            PlaylistMenuItem(
                                iconRes = MimikyuIcons.AddToPlaylist,
                                title = "Add to Playlist"
                            ) {
                                showMoreMenuSheet = false
                                if (userProfile == null) {
                                    showLoginSheet = true
                                } else {
                                    viewModel.prepareImportTargets(playlist.id)
                                    showImportTargetSheet = true
                                }
                            }
                        )

                        add(
                            PlaylistMenuItem(
                                iconRes = MimikyuIcons.Download,
                                title = "Download $resourceLabel"
                            ) {
                                showMoreMenuSheet = false
                                showDownloadQualitySheet = true
                            }
                        )
                        if (isManageable) {
                            add(
                                PlaylistMenuItem(
                                    iconRes = MimikyuIcons.Edit,
                                    title = "Edit Playlist Info"
                                ) {
                                    showMoreMenuSheet = false
                                    showEditInfoDialog = true
                                }
                            )
                            add(
                                PlaylistMenuItem(
                                    icon = Icons.Default.Delete,
                                    title = "Delete Playlist",
                                    isDestructive = true
                                ) {
                                    showMoreMenuSheet = false
                                    showDeleteConfirmDialog = true
                                }
                            )
                        }
                    }

                    LazyColumn(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(menuItems, key = { it.title }) { item ->
                            val iconColor = if (item.isDestructive) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            val titleColor = if (item.isDestructive) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(onClick = item.onClick)
                                    .padding(horizontal = 20.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (item.iconRes != 0) {
                                    Icon(
                                        painter = painterResource(item.iconRes),
                                        contentDescription = item.title,
                                        tint = iconColor,
                                        modifier = Modifier.size(24.dp)
                                    )
                                } else if (item.icon != null) {
                                    Icon(
                                        imageVector = item.icon,
                                        contentDescription = item.title,
                                        tint = iconColor,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(MimikyuSpacing.md))
                                Text(
                                    text = item.title,
                                    color = titleColor,
                                    fontSize = 15.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }

        if (showImportTargetSheet) {
            PlaylistImportTargetSheet(
                importState = importState,
                onDismiss = { showImportTargetSheet = false },
                onSelectTarget = { targetId ->
                    viewModel.importAllTracksTo(targetId)
                    showImportTargetSheet = false
                },
                onCreateAndImport = { name ->
                    viewModel.createPlaylistAndImportAll(name)
                    showImportTargetSheet = false
                }
            )
        }

        if (showAddMusicSheet && successState != null) {
            val playlist = successState.playlist
            AddMusicToPlaylistSheet(
                searchQuery = addMusicSearchQuery,
                searchState = addMusicSearchState,
                existingTrackIds = remember(playlist.tracks) {
                    playlist.tracks.map { it.id }.toSet()
                },
                recommendedSongs = successState.recommendedSongs,
                onQueryChange = viewModel::updateAddMusicSearchQuery,
                onClearQuery = viewModel::clearAddMusicSearch,
                onAddTrack = { track, onComplete ->
                    viewModel.addTrackToPlaylist(playlist.id, track) { onComplete() }
                },
                onDismiss = {
                    showAddMusicSheet = false
                    viewModel.clearAddMusicSearch()
                }
            )
        }

        if (showEditInfoDialog && successState != null) {
            val playlist = successState.playlist
            EditPlaylistInfoDialog(
                initialName = playlist.name,
                initialDescription = playlist.description.orEmpty(),
                coverImgUrl = playlist.coverImgUrl,
                pendingCoverUri = pendingCoverUri,
                isSaving = isSavingInfo,
                onEditCoverClick = launchCoverCropper,
                onDismiss = {
                    showEditInfoDialog = false
                    pendingCoverUri = null
                },
                onConfirm = { newName, newDesc ->
                    coroutineScope.launch {
                        val stagedUri = pendingCoverUri
                        val coverBytes = stagedUri?.let { uri ->
                            withContext(Dispatchers.IO) {
                                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            }
                        }
                        if (stagedUri != null && coverBytes == null) {
                            com.lin0721.linmusic.core.ui.components.ToastManager.showToast("Failed to read cropped image")
                            return@launch
                        }
                        viewModel.updatePlaylistInfo(
                            id = playlist.id,
                            originalName = playlist.name,
                            newName = newName,
                            originalDesc = playlist.description,
                            newDesc = newDesc,
                            coverBytes = coverBytes
                        ) { success ->
                            if (success) {
                                showEditInfoDialog = false
                                pendingCoverUri = null
                            }
                        }
                    }
                }
            )
        }

        if (showDeleteConfirmDialog && successState != null) {
            val playlist = successState.playlist
            AlertDialog(
                onDismissRequest = { showDeleteConfirmDialog = false },
                title = {
                    Text(
                        text = "Delete Playlist",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Text(
                        text = "Are you sure you want to delete \"${playlist.name}\"? This action cannot be undone.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp
                    )
                },
                confirmButton = {
                    MimikyuTextButton(
                        onClick = {
                            showDeleteConfirmDialog = false
                            viewModel.deletePlaylist(playlist.id) {
                                onBack()
                            }
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Delete", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    MimikyuTextButton(onClick = { showDeleteConfirmDialog = false }) {
                        Text("Cancel", color = MaterialTheme.colorScheme.onSurface)
                    }
                },
                containerColor = MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.medium
            )
        }

        if (showDiscardConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showDiscardConfirmDialog = false },
                title = {
                    Text(
                        text = "Discard changes?",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Text(
                        text = "Track order changes have not been saved. Discard changes?",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp
                    )
                },
                confirmButton = {
                    MimikyuTextButton(
                        onClick = {
                            showDiscardConfirmDialog = false
                            isReorderMode = false
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Discard", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    MimikyuTextButton(onClick = { showDiscardConfirmDialog = false }) {
                        Text("Keep Editing", color = MaterialTheme.colorScheme.onSurface)
                    }
                },
                containerColor = MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.medium
            )
        }

        val currentCoverUrl = when (val state = uiState) {
            is PlaylistUiState.Success -> state.playlist.coverImgUrl
            else -> ""
        }
        val currentCreatorName = when (val state = uiState) {
            is PlaylistUiState.Success -> if (isAlbum) "Album" else (state.playlist.creator?.nickname ?: "Playlist")
            else -> if (isAlbum) "Album" else "Playlist"
        }

        com.lin0721.linmusic.feature.player.ui.MimikyuShareSheet(
            visible = showMimikyuShareSheet,
            onDismiss = { showMimikyuShareSheet = false },
            mediaId = shareId.toString(),
            title = shareTitle,
            artist = currentCreatorName,
            coverUrl = currentCoverUrl,
            currentPositionMs = 0L,
            lyrics = emptyList(),
            initialTab = com.lin0721.linmusic.feature.player.ui.ShareCardTab.TRACK
        )

    }
}

private data class PlaylistMenuItem(
    @DrawableRes val iconRes: Int = 0,
    val icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    val title: String,
    val isDestructive: Boolean = false,
    val onClick: () -> Unit
)
