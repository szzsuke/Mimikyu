package com.lin0721.linmusic.feature.player.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import com.lin0721.linmusic.core.ui.theme.MimikyuSpacing
import com.lin0721.linmusic.core.ui.theme.ScreenSlideDurationMs
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import com.lin0721.linmusic.core.download.ui.DownloadQualityPickerSheet
import com.lin0721.linmusic.core.player.rememberQueueItemCoverUrl
import com.lin0721.linmusic.core.ui.components.SwipeToSkipCover
import com.lin0721.linmusic.core.ui.components.ToastManager
import com.lin0721.linmusic.core.ui.theme.FallbackBackdropPalette
import com.lin0721.linmusic.core.ui.theme.MimikyuOrientationClass
import com.lin0721.linmusic.core.ui.theme.PaletteMemoryCache
import com.lin0721.linmusic.core.ui.theme.PanelReflowFadeFromAlpha
import com.lin0721.linmusic.core.ui.theme.PanelReflowFadeSpec
import com.lin0721.linmusic.core.ui.theme.RadiusCompact
import com.lin0721.linmusic.core.ui.theme.extractBackdropPaletteFromBitmap
import com.lin0721.linmusic.core.ui.theme.extractBackdropPaletteFromUrl
import com.lin0721.linmusic.core.ui.theme.mimikyuNavigationBarBottomPadding
import com.lin0721.linmusic.core.ui.theme.mimikyuStatusBarTopPadding
import com.lin0721.linmusic.core.ui.theme.rememberMimikyuOrientationClass
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.androidx.compose.koinViewModel

// →，
private const val WideCrossfadeSplit = 0.6f

// of
private const val PortraitFullscreenColumnFraction = 0.62f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullPlayerScreen(
    currentTrack: MediaItem?,
    isPlaying: Boolean,
    currentPositionProvider: () -> Long,
    duration: Long,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
    onClose: () -> Unit,
    isPlayerOpen: Boolean,
    onArtistClick: (Long) -> Unit,
    onAlbumClick: (Long) -> Unit,
    onNavigateToProfile: (Long) -> Unit = {},
    onDragClose: (Float, Float) -> Unit = { _, _ -> },
    // ：，
    fitCoverAboveNavigationBar: Boolean = false,
    // ，
    // ，
    fitCoverToViewport: Boolean = false,
    // Play of ，、
    contentMaxWidth: Dp = Dp.Unspecified,
    onToggleSidebarFullscreen: (() -> Unit)? = null,
    isSidebarFullscreen: Boolean = false,
    // → of （0 、1 ） and  of 。，
    // 、；，
    sidebarFullscreenProgress: (() -> Float)? = null,
    fullscreenContentWidth: Dp = Dp.Unspecified
) {
    if (currentTrack == null) return

    val context = LocalContext.current
    val viewModel: PlayerViewModel = koinViewModel()
    val songDetailState by viewModel.songDetailState.collectAsStateWithLifecycle()
    val songDetail = songDetailState.songDetail
    // Play："Paused"， of  isPlaying；
    // //QueueOthers of  isPlaying ，
    val playWhenReady by viewModel.playerManager.playWhenReady.collectAsStateWithLifecycle()
    val currentLyricIndex by viewModel.currentLyricIndex.collectAsStateWithLifecycle()
    val songLyricOffset by viewModel.songLyricOffset.collectAsStateWithLifecycle()
    val lyricPositionProvider = remember(currentPositionProvider, songLyricOffset) {
        { currentPositionProvider() + songLyricOffset }
    }
    val playContext by viewModel.playerManager.playContext.collectAsStateWithLifecycle()
    val sleepTimerRemaining by viewModel.sleepTimerRemaining.collectAsStateWithLifecycle()
    val activeQuality by viewModel.activeQuality.collectAsStateWithLifecycle()
    val collectState by viewModel.collectState.collectAsStateWithLifecycle()
    val playMode by viewModel.playerManager.playMode.collectAsStateWithLifecycle()
    val queue by viewModel.playerManager.queue.collectAsStateWithLifecycle()
    val currentQueueIndex by viewModel.playerManager.currentIndex.collectAsStateWithLifecycle()
    val currentQueueItem by viewModel.playerManager.currentQueueItem.collectAsStateWithLifecycle()
    val previousQueueItem by viewModel.playerManager.previousQueueItem.collectAsStateWithLifecycle()
    val nextQueueItem by viewModel.playerManager.nextQueueItem.collectAsStateWithLifecycle()
    var showQueueSheet by remember { mutableStateOf(false) }
    var isLyricsFullScreen by remember { mutableStateOf(false) }
    var showMoreOptionsSheet by remember { mutableStateOf(false) }
    var showTimerSheet by remember { mutableStateOf(false) }
    val userProfile by viewModel.userProfile.collectAsStateWithLifecycle()
    var collectSongId by remember { mutableStateOf<Long?>(null) }
    var showOutputDeviceSheet by remember { mutableStateOf(false) }
    var showDownloadQualitySheet by remember { mutableStateOf(false) }
    var showCardEditorSheet by remember { mutableStateOf(false) }
    val cardLayout by viewModel.fullPlayerCardLayout.collectAsStateWithLifecycle()
    val connectedDevice = rememberCurrentOutputDevice()


    LaunchedEffect(viewModel) {
        viewModel.toastEvent.collect { message ->
            ToastManager.showToast(message)
        }
    }

    BackHandler(enabled = showQueueSheet) {
        showQueueSheet = false
    }

    BackHandler(enabled = isLyricsFullScreen) {
        isLyricsFullScreen = false
    }

    BackHandler(enabled = showMoreOptionsSheet) {
        showMoreOptionsSheet = false
    }

    BackHandler(enabled = showTimerSheet) {
        showTimerSheet = false
    }

    BackHandler(enabled = showOutputDeviceSheet) {
        showOutputDeviceSheet = false
    }

    BackHandler(enabled = showCardEditorSheet) {
        showCardEditorSheet = false
    }

    BackHandler(enabled = showDownloadQualitySheet) {
        showDownloadQualitySheet = false
    }

    // of Current（）
    var isWideLyricsVisible by remember { mutableStateOf(false) }

    // and  of  of
    DisposableEffect(isLyricsFullScreen || isWideLyricsVisible, isPlaying) {
        if ((isLyricsFullScreen || isWideLyricsVisible) && isPlaying) {
            viewModel.playerManager.setPositionUpdateInterval(50L)
        } else {
            viewModel.playerManager.setPositionUpdateInterval(1000L)
        }
        onDispose {
            viewModel.playerManager.setPositionUpdateInterval(1000L)
        }
    }

    var colorPalette by remember(currentTrack.mediaId) {
        mutableStateOf(
            PaletteMemoryCache.get(currentTrack.mediaId) ?: FallbackBackdropPalette
        )
    }
    val colors = rememberFullPlayerColors(colorPalette)
    var newStyleMeshColors by remember(currentTrack.mediaId) {
        mutableStateOf(
            BitChordMeshPaletteCache.get(currentTrack.mediaId) ?: BitChordFallbackColors
        )
    }

    val title = com.lin0721.linmusic.core.metadata.cleanMetadataText(currentTrack.mediaMetadata.title?.toString() ?: "")
    val artist = com.lin0721.linmusic.core.metadata.cleanMetadataText(currentTrack.mediaMetadata.artist?.toString() ?: "")
    val rawCoverUrl = currentTrack.mediaMetadata.artworkUri?.toString().orEmpty()
    val fallbackCoverUrl = currentQueueItem?.let {
        rememberQueueItemCoverUrl(it.coverUrl, it.songId, it.localUri, it.title, it.artist)
    }.orEmpty()
    val baseCoverUrl = rawCoverUrl.ifBlank { fallbackCoverUrl }
    val coverUrl = com.lin0721.linmusic.core.metadata.SongArtworkEngine.optimizeArtworkUrl(baseCoverUrl, 1200)

    val previousCoverUrl = previousQueueItem?.let {
        val raw = rememberQueueItemCoverUrl(it.coverUrl, it.songId, it.localUri, it.title, it.artist)
        com.lin0721.linmusic.core.metadata.SongArtworkEngine.optimizeArtworkUrl(raw, 1200)
    }
    val nextCoverUrl = nextQueueItem?.let {
        val raw = rememberQueueItemCoverUrl(it.coverUrl, it.songId, it.localUri, it.title, it.artist)
        com.lin0721.linmusic.core.metadata.SongArtworkEngine.optimizeArtworkUrl(raw, 1200)
    }

    // ， of ；， and
    LaunchedEffect(coverUrl, currentTrack.mediaId) {
        if (coverUrl.isNotEmpty()) {
            val palette = extractBackdropPaletteFromUrl(context, coverUrl)
            colorPalette = palette
            PaletteMemoryCache.put(currentTrack.mediaId, palette)

            val cachedMesh = BitChordMeshPaletteCache.get(currentTrack.mediaId)
            if (cachedMesh != null) {
                newStyleMeshColors = cachedMesh
            } else {
                withContext(Dispatchers.Default) {
                    val meshColors = extractBitChordMeshColorsFromUrl(context, coverUrl)
                    withContext(Dispatchers.Main) {
                        newStyleMeshColors = meshColors
                    }
                    BitChordMeshPaletteCache.put(currentTrack.mediaId, meshColors)
                }
            }
        }
    }

    // /Artists：Queue、currentTrack ，
    // of /
    val previousKeyStr = previousQueueItem?.songId?.toString()
    val nextKeyStr = nextQueueItem?.songId?.toString()
    var displayedTitle by remember { mutableStateOf(title) }
    var displayedArtist by remember { mutableStateOf(artist) }
    if ((previousKeyStr != null && previousKeyStr == currentTrack.mediaId) ||
        (nextKeyStr != null && nextKeyStr == currentTrack.mediaId)
    ) {
        // Queue，，， currentTrack
    } else {
        displayedTitle = title
        displayedArtist = artist
    }

    val resolveCurrentArtistId: () -> Long = {
        val existing = songDetail?.ar?.firstOrNull()?.id?.takeIf { it > 0L }
        if (existing != null) existing
        else {
            val aName = displayedArtist.ifBlank { songDetail?.ar?.firstOrNull()?.name ?: artist }
            if (aName.isNotBlank()) {
                val primary = aName.split(Regex("""[,/、|\\+x]|(\s+&\s+)|(\bfeat\.?\b)|(\bft\.?\b)""", RegexOption.IGNORE_CASE))
                    .firstOrNull()?.trim() ?: aName
                com.lin0721.linmusic.core.player.innertube.YouTubeIdRegistry.registerArtistByName(primary)
            } else 0L
        }
    }

    var showMimikyuShareSheet by remember { mutableStateOf(false) }
    var shareSheetInitialTab by remember { mutableStateOf(ShareCardTab.TRACK) }

    fun shareCurrentSong(initialTab: ShareCardTab = ShareCardTab.TRACK) {
        com.lin0721.linmusic.core.ui.components.ToastManager.showToast("Feature under development")
    }

    val listState = rememberLazyListState()
    val hazeState = remember { HazeState() }
    val scrollMetrics = rememberFullPlayerScrollMetrics(listState)
    val density = LocalDensity.current
    val navigationBarBottomPadding = mimikyuNavigationBarBottomPadding()
    val coverFitInsetPx = rememberCoverFitInsetPx(
        listState = listState,
        enabled = fitCoverToViewport || fitCoverAboveNavigationBar,
        bottomReservePx = if (fitCoverAboveNavigationBar) with(density) { navigationBarBottomPadding.toPx() } else 0f
    )
    val coverExtraInset = with(density) { coverFitInsetPx.toDp() }
    val allCardsHidden = cardLayout.none { it.visible }
    val fillTail = MimikyuSpacing.md + navigationBarBottomPadding
    val fillGap = rememberFullPlayerFillGap(
        listState = listState,
        enabled = allCardsHidden,
        gapCount = FullPlayerFillGapCount,
        contentKeys = FullPlayerPlaybackItemKeys,
        bottomReserve = fillTail
    )

    val isLandscape = rememberMimikyuOrientationClass() == MimikyuOrientationClass.Landscape
    val fullscreenProgress: () -> Float = { sidebarFullscreenProgress?.invoke()?.coerceIn(0f, 1f) ?: 0f }
    val canUseWideLayout = sidebarFullscreenProgress != null && isLandscape && fullscreenContentWidth.isSpecified
    val showColumnLayout by remember(canUseWideLayout) {
        derivedStateOf { !canUseWideLayout || fullscreenProgress() < WideCrossfadeSplit }
    }
    val showWideLayout by remember(canUseWideLayout) {
        derivedStateOf { canUseWideLayout && fullscreenProgress() > WideCrossfadeSplit }
    }
    // ： of ，
    val fullscreenColumnMaxWidth = if (fullscreenContentWidth.isSpecified) {
        fullscreenContentWidth * PortraitFullscreenColumnFraction
    } else {
        Dp.Unspecified
    }
    val isColumnFullscreenWidth by remember(sidebarFullscreenProgress, isLandscape) {
        derivedStateOf { sidebarFullscreenProgress != null && !isLandscape && fullscreenProgress() >= 1f }
    }
    val columnReflowAlpha = remember { Animatable(1f) }
    var hasSettledColumnWidth by remember { mutableStateOf(false) }
    LaunchedEffect(isColumnFullscreenWidth) {
        if (hasSettledColumnWidth) {
            columnReflowAlpha.snapTo(PanelReflowFadeFromAlpha)
            columnReflowAlpha.animateTo(1f, PanelReflowFadeSpec)
        }
        hasSettledColumnWidth = true
    }

    // ， MutableState
    val offsetYState = remember { mutableStateOf(0f) }
    var offsetY by offsetYState
    val isScrollGestureActiveState = remember { mutableStateOf(false) }
    var isScrollGestureActive by isScrollGestureActiveState
    val isGestureStartedAtTopState = remember { mutableStateOf(true) }
    var isGestureStartedAtTop by isGestureStartedAtTopState

    LaunchedEffect(isPlayerOpen) {
        if (isPlayerOpen) {
            offsetY = 0f
            isScrollGestureActive = false
            isGestureStartedAtTop = true
            isLyricsFullScreen = false
        }
    }

    var screenHeightPx by remember { mutableStateOf(0f) }
    val topCornerRadius by remember {
        derivedStateOf {
            if (offsetY > 0f) 24.dp else 0.dp
        }
    }
    val coroutineScope = rememberCoroutineScope()
    var dragReleaseJob by remember { mutableStateOf<Job?>(null) }

    // ClosePlay， of
    fun handleDragRelease(velocity: Float = 0f) {
        dragReleaseJob?.cancel()
        dragReleaseJob = coroutineScope.launch {
            val shouldClose = if (isGestureStartedAtTop) {
                offsetY > screenHeightPx * 0.10f || velocity > 450f
            } else {
                offsetY > screenHeightPx * 0.20f
            }

            if (offsetY > 0f && shouldClose) {
                val finalOffset = offsetY
                offsetY = 0f
                onDragClose(finalOffset, velocity)
            } else {
                animate(
                    initialValue = offsetY,
                    targetValue = 0f,
                    initialVelocity = velocity,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                ) { value, _ ->
                    offsetY = value.coerceAtLeast(0f)
                }
            }
        }
    }

    val nestedScrollConnection = rememberFullPlayerNestedScrollConnection(
        listState = listState,
        offsetYState = offsetYState,
        isScrollGestureActiveState = isScrollGestureActiveState,
        isGestureStartedAtTopState = isGestureStartedAtTopState,
        onDragRelease = { handleDragRelease(velocity = it) }
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { screenHeightPx = it.height.toFloat() }
            .nestedScroll(nestedScrollConnection)
            .graphicsLayer {
                translationY = offsetY
            }
            .clip(RoundedCornerShape(topStart = topCornerRadius, topEnd = topCornerRadius))
            .background(MaterialTheme.colorScheme.background)
    ) {
        PlayerBackdrop(
            base = colors.base,
            mode = BackdropMode.Collapsed,
            translationYProvider = { if (showColumnLayout) scrollMetrics.backgroundTranslationY else 0f },
            modifier = Modifier.fillMaxSize()
        )

        // ：、 and 。、，
        if (showColumnLayout) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val crossfade = if (canUseWideLayout) {
                            (1f - fullscreenProgress() / WideCrossfadeSplit).coerceIn(0f, 1f)
                        } else {
                            1f
                        }
                        alpha = crossfade * columnReflowAlpha.value
                    }
            ) {


                LazyColumn(
                    state = listState,
                    userScrollEnabled = true,
                    modifier = Modifier
                        .align(if (canUseWideLayout) Alignment.TopEnd else Alignment.TopCenter)
                        .fillMaxHeight()
                        .widthIn(max = if (isColumnFullscreenWidth) fullscreenColumnMaxWidth else contentMaxWidth)
                        .fillMaxWidth()
                        .haze(hazeState),
                    contentPadding = PaddingValues(
                        top = mimikyuStatusBarTopPadding(),
                        bottom = (if (allCardsHidden) MimikyuSpacing.md else 80.dp) + mimikyuNavigationBarBottomPadding()
                    )
                ) {
                    fullPlayerPlaybackSection(
                        songState = songDetailState,
                        colors = colors,
                        coverUrl = coverUrl,
                        queue = queue,
                        currentIndex = currentQueueIndex,
                        onPlayIndex = viewModel.playerManager::playAtIndex,
                        previousCoverUrl = previousCoverUrl,
                        nextCoverUrl = nextCoverUrl,
                        onSwipeToPrevious = viewModel.playerManager::skipToPrevious,
                        onCancelSwipe = viewModel.playerManager::cancelPendingSkip,
                        currentKey = currentTrack.mediaId,
                        previousKey = previousQueueItem?.songId?.toString(),
                        nextKey = nextQueueItem?.songId?.toString(),
                        coverExtraInset = coverExtraInset,
                        onToggleSidebarFullscreen = onToggleSidebarFullscreen,
                        isSidebarFullscreen = isSidebarFullscreen,
                        title = displayedTitle,
                        artist = displayedArtist,
                        playContext = playContext,
                        currentLyricIndex = currentLyricIndex,
                        isPlaying = isPlaying,
                        playWhenReady = playWhenReady,
                        currentPositionProvider = currentPositionProvider,
                        duration = duration,
                        playMode = playMode,
                        onClose = onClose,
                        onMoreClick = { showMoreOptionsSheet = true },
                        onToggleLike = viewModel::toggleLike,
                        onArtistClick = {
                            val aId = resolveCurrentArtistId()
                            if (aId > 0L) onArtistClick(aId)
                        },
                        onAddToPlaylist = {
                            val songId = currentTrack.mediaId.toLongOrNull()
                                ?: currentQueueItem?.songId
                                ?: (displayedTitle.hashCode().toLong().let { if (it == 0L) 1L else kotlin.math.abs(it) })
                            collectSongId = songId
                            viewModel.prepareCollectDialog(songId)
                        },
                        onSeek = onSeek,
                        onTogglePlay = onTogglePlay,
                        onPlayNext = viewModel.playerManager::playNext,
                        onPlayPrevious = viewModel.playerManager::playPrevious,
                        onToggleShuffle = viewModel.playerManager::toggleShuffle,
                        onToggleRepeat = viewModel.playerManager::toggleRepeat,
                        onDisableRoaming = { viewModel.playerManager.disableRoaming() },
                        onDisableIntelligence = { viewModel.playerManager.disableIntelligence() },
                        onOutputDeviceClick = { showOutputDeviceSheet = true },
                        onQueueClick = { showQueueSheet = true },
                        onShareClick = { shareCurrentSong() },
                        onOpenFullScreenLyrics = { isLyricsFullScreen = true },
                        connectedDevice = connectedDevice,
                        onCurrentBitmapLoaded = { bitmap ->
                            coroutineScope.launch(Dispatchers.Default) {
                                val palette = extractBackdropPaletteFromBitmap(bitmap)
                                val meshColors = extractBitChordMeshColors(bitmap)
                                withContext(Dispatchers.Main) {
                                    colorPalette = palette
                                    newStyleMeshColors = meshColors
                                }
                                PaletteMemoryCache.put(currentTrack.mediaId, palette)
                                BitChordMeshPaletteCache.put(currentTrack.mediaId, meshColors)
                            }
                        },
                        fillGap = if (allCardsHidden) fillGap else null,
                        fillTail = fillTail
                    )

                    fullPlayerInfoSection(
                        songState = songDetailState,
                        colors = colors,
                        currentLyricIndex = currentLyricIndex,
                        cardLayout = cardLayout,
                        onOpenFullScreenLyrics = { isLyricsFullScreen = true },
                        onFollowArtistClick = { artistId -> viewModel.toggleArtistFollow(artistId) },
                        onArtistClick = onArtistClick,
                        onEditCardsClick = { showCardEditorSheet = true },
                        onAlbumClick = onAlbumClick,
                        onSelectArtist = { index -> viewModel.selectArtist(index) }
                    )
                }

                FullPlayerTopBar(
                    onClose = onClose,
                    title = title,
                    artist = artist,
                    showTitle = scrollMetrics.showTitleInBar,
                    isPlaying = isPlaying,
                    onTogglePlay = onTogglePlay,
                    isLiked = songDetailState.isLiked,
                    onToggleLike = viewModel::toggleLike,
                    backgroundColor = colors.base,
                    currentPositionProvider = currentPositionProvider,
                    duration = duration,
                    onArtistClick = {
                        val aId = resolveCurrentArtistId()
                        if (aId > 0L) onArtistClick(aId)
                    },
                    modifier = Modifier.draggable(
                        orientation = Orientation.Vertical,
                        state = rememberDraggableState { delta ->
                            offsetY = (offsetY + delta).coerceAtLeast(0f)
                        },
                        onDragStarted = {
                            isGestureStartedAtTop = true
                        },
                        onDragStopped = { velocity ->
                            handleDragRelease(velocity = velocity)
                        }
                    )
                )

            }
        }

        // ：、，，
        if (showWideLayout) {
            FullPlayerWideLayout(
                sourceBar = { barModifier ->
                    FullPlayerSourceBar(
                        playContext = playContext,
                        onClose = onClose,
                        onMoreClick = { showMoreOptionsSheet = true },
                        onToggleSidebarFullscreen = onToggleSidebarFullscreen,
                        isSidebarFullscreen = isSidebarFullscreen,
                        modifier = barModifier
                    )
                },
                cover = { coverModifier ->
                    FullPlayerCoverPager(
                        queue = queue,
                        currentIndex = currentQueueIndex,
                        fallbackCoverUrl = coverUrl,
                        onPlayIndex = viewModel.playerManager::playAtIndex,
                        contentPadding = 0.dp,
                        modifier = coverModifier
                    )
                },
                playbackControls = {
                    SongInfo(
                        title = displayedTitle,
                        artist = displayedArtist,
                        isLiked = songDetailState.isLiked,
                        onToggleLike = viewModel::toggleLike,
                        onArtistClick = {
                            val aId = resolveCurrentArtistId()
                            if (aId > 0L) onArtistClick(aId)
                        },
                        onAddToPlaylist = {
                            val songId = currentTrack.mediaId.toLongOrNull()
                                ?: currentQueueItem?.songId
                                ?: (displayedTitle.hashCode().toLong().let { if (it == 0L) 1L else kotlin.math.abs(it) })
                            collectSongId = songId
                            viewModel.prepareCollectDialog(songId)
                        }
                    )
                    ProgressSection(
                        currentPositionProvider = currentPositionProvider,
                        duration = if (duration > 0L) duration else (songDetail?.dt ?: 0L),
                        onSeek = onSeek,
                        chorusStartMs = songDetailState.chorusStartMs
                    )
                    PlaybackControls(
                        isPlaying = playWhenReady,
                        onTogglePlay = onTogglePlay,
                        onPlayNext = viewModel.playerManager::playNext,
                        onPlayPrevious = viewModel.playerManager::playPrevious,
                        onToggleShuffle = viewModel.playerManager::toggleShuffle,
                        onToggleRepeat = viewModel.playerManager::toggleRepeat,
                        playMode = playMode,
                        isRoaming = playContext == "similar_roaming",
                        onDisableRoaming = { viewModel.playerManager.disableRoaming() },
                        isIntelligence = playContext == "intelligence",
                        onDisableIntelligence = { viewModel.playerManager.disableIntelligence() }
                    )
                    ActionButtons(
                        onOutputDeviceClick = { showOutputDeviceSheet = true },
                        onQueueClick = { showQueueSheet = true },
                        onShareClick = { shareCurrentSong() },
                        connectedDevice = connectedDevice
                    )
                },
                lyrics = songDetailState.lyrics,
                isLyricsLoading = songDetailState.isLyricsLoading,
                currentLyricIndex = currentLyricIndex,
                highlightColor = colors.textHighlight,
                currentPositionProvider = lyricPositionProvider,
                isPlaying = isPlaying,
                onLyricClick = { line -> viewModel.seekToTime(line.timeMs) },
                onLyricsVisibleChange = { isWideLyricsVisible = it },
                infoCards = {
                    fullPlayerInfoGrid(
                        songState = songDetailState,
                        colors = colors,
                        cardLayout = cardLayout,
                        onFollowArtistClick = { artistId -> viewModel.toggleArtistFollow(artistId) },
                        onArtistClick = onArtistClick,
                        onAlbumClick = onAlbumClick,
                        onSelectArtist = { index -> viewModel.selectArtist(index) },
                        onEditCardsClick = { showCardEditorSheet = true }
                    )
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .wrapContentWidth(align = Alignment.End, unbounded = true)
                    .width(fullscreenContentWidth)
                    .padding(
                        top = mimikyuStatusBarTopPadding(),
                        bottom = mimikyuNavigationBarBottomPadding()
                    )
                    .graphicsLayer {
                        alpha = ((fullscreenProgress() - WideCrossfadeSplit) / (1f - WideCrossfadeSplit))
                            .coerceIn(0f, 1f)
                    }
            )
        }

        FullPlayerLyricsOverlay(
            visible = isLyricsFullScreen,
            songState = songDetailState,
            colors = colors,
            currentLyricIndex = currentLyricIndex,
            title = title,
            artist = artist,
            hazeState = hazeState,
            isPlaying = isPlaying,
            currentPositionProvider = lyricPositionProvider,
            duration = duration,
            playMode = playMode,
            onSeek = { timeMs ->
                viewModel.seekToTime(timeMs)
            },
            onClose = { isLyricsFullScreen = false },
            onTogglePlay = onTogglePlay,
            onPlayNext = viewModel.playerManager::playNext,
            onPlayPrevious = viewModel.playerManager::playPrevious,
            onToggleShuffle = viewModel.playerManager::toggleShuffle,
            onToggleRepeat = viewModel.playerManager::toggleRepeat,
            onMoreClick = { showMoreOptionsSheet = true },
            lyricOffsetMs = songLyricOffset,
            onLyricOffsetChange = viewModel::updateLyricOffset,
            onLyricOffsetReset = viewModel::resetLyricOffset,
            onShareLyrics = { shareCurrentSong(ShareCardTab.LYRICS) }
        )

        FullPlayerSheets(
            songState = songDetailState,
            showQueueSheet = showQueueSheet,
            showMoreOptionsSheet = showMoreOptionsSheet,
            collectSongId = collectSongId,
            collectState = collectState,
            showTimerSheet = showTimerSheet,
            showOutputDeviceSheet = showOutputDeviceSheet,
            queue = queue,
            currentQueueIndex = currentQueueIndex,
            playMode = playMode,
            playContext = playContext,
            isPlaying = isPlaying,
            title = title,
            artist = artist,
            coverUrl = coverUrl,
            sleepTimerRemaining = sleepTimerRemaining,
            activeQuality = activeQuality,
            onPlayAtIndex = { viewModel.playerManager.playAtIndex(it) },
            onRemoveAtIndex = { viewModel.playerManager.removeFromQueue(it) },
            onMoveQueueItem = { from, to -> viewModel.playerManager.moveInQueue(from, to) },
            onToggleShuffle = viewModel.playerManager::toggleShuffle,
            onClearQueue = {
                viewModel.clearQueue()
                showQueueSheet = false
            },
            onDisableRoaming = { viewModel.playerManager.disableRoaming() },
            onDisableIntelligence = { viewModel.playerManager.disableIntelligence() },
            onQueueDismiss = { showQueueSheet = false },
            onToggleLike = viewModel::toggleLike,
            onAlbumClick = {
                val albumId = songDetail?.al?.id?.takeIf { it > 0L }
                    ?: (songDetail?.al?.name?.takeIf { it.isNotBlank() }?.let { com.lin0721.linmusic.core.player.innertube.YouTubeIdRegistry.registerAlbum(it) } ?: 0L)
                if (albumId > 0L) {
                    showMoreOptionsSheet = false
                    onAlbumClick(albumId)
                } else {
                    ToastManager.showToast("Album not found")
                }
            },
            onArtistClick = {
                val artistId = resolveCurrentArtistId()
                if (artistId > 0L) {
                    showMoreOptionsSheet = false
                    onArtistClick(artistId)
                } else {
                    ToastManager.showToast("Artist not found")
                }
            },
            onShowTimerClick = {
                showTimerSheet = true
            },
            onQualitySelected = viewModel::updateQuality,
            onToggleIntelligence = { checked ->
                if (checked) {
                    val songId = currentTrack.mediaId.toLongOrNull() ?: 0L
                    viewModel.startIntelligenceMode(songId, title, artist, coverUrl)
                } else {
                    viewModel.playerManager.disableIntelligence()
                }
            },
            onStartSimilarRoaming = {
                val songId = currentTrack.mediaId.toLongOrNull() ?: 0L
                viewModel.startSimilarSongsRoaming(songId, title, artist, coverUrl)
            },
            onInsertSimilarSongs = {
                val songId = currentTrack.mediaId.toLongOrNull() ?: 0L
                viewModel.insertSimilarSongs(songId)
            },
            onCollectClick = {
                val songId = currentTrack.mediaId.toLongOrNull()
                if (songId != null) {
                    collectSongId = songId
                    viewModel.prepareCollectDialog(songId)
                }
            },
            onShareClick = { shareCurrentSong() },
            onDownloadClick = { showDownloadQualitySheet = true },
            onSaveCollection = { songId, items -> viewModel.savePlaylistCollection(songId, items) },
            onSaveNewCollection = { name, songId -> viewModel.createPlaylistAndAddSong(name, songId) },
            onCollectDismiss = { collectSongId = null },
            onMoreOptionsDismiss = { showMoreOptionsSheet = false },
            onSetTimer = { minutes ->
                viewModel.setSleepTimer(minutes)
                showTimerSheet = false
            },
            onTimerDismiss = { showTimerSheet = false },
            onOutputDeviceSelected = { deviceId -> viewModel.playerManager.setPreferredAudioDevice(deviceId) },
            onOutputDeviceDismiss = { showOutputDeviceSheet = false },

        )

        MimikyuShareSheet(
            visible = showMimikyuShareSheet,
            onDismiss = { showMimikyuShareSheet = false },
            mediaId = currentTrack.mediaId,
            title = displayedTitle,
            artist = displayedArtist,
            coverUrl = coverUrl,
            currentPositionMs = lyricPositionProvider(),
            lyrics = songDetailState.lyrics,
            currentLyricIndex = currentLyricIndex,
            baseColor = colors.base,
            initialTab = shareSheetInitialTab
        )

        if (showDownloadQualitySheet) {
            DownloadQualityPickerSheet(
                songId = currentTrack.mediaId.toLongOrNull(),
                maxDownloadLevel = songDetail?.privilege?.dlLevel,
                onQualitySelected = { level ->
                    val songId = currentTrack.mediaId.toLongOrNull() ?: 0L
                    val artistNames = songDetail?.ar?.joinToString("/") { it.name }?.takeIf { it.isNotBlank() } ?: artist
                    val albumName = songDetail?.al?.name ?: ""
                    val albumYear = com.lin0721.linmusic.core.download.yearFromEpochMillis(songDetail?.publishTime ?: 0)
                    viewModel.downloadCurrentSong(songId, title, artistNames, albumName, coverUrl, albumYear, level)
                    showDownloadQualitySheet = false
                },
                onDismiss = { showDownloadQualitySheet = false }
            )
        }

        if (showCardEditorSheet) {
            FullPlayerCardEditorSheet(
                layout = cardLayout,
                onLayoutChange = viewModel::saveFullPlayerCardLayout,
                onDismiss = { showCardEditorSheet = false }
            )
        }
    }
}
