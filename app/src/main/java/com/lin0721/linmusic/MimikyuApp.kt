package com.lin0721.linmusic

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.ExperimentalMaterial3Api
import com.lin0721.linmusic.core.songlike.toLikedSong
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lin0721.linmusic.core.auth.UserPreferences
import com.lin0721.linmusic.feature.home.ui.GetStartedScreen
import com.lin0721.linmusic.core.ui.components.LoginBottomSheet
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.lin0721.linmusic.core.ui.components.ImportPlaylistDialog
import com.lin0721.linmusic.core.ui.components.PlaylistCollectSheet
import com.lin0721.linmusic.core.ui.components.ToastManager
import com.lin0721.linmusic.core.ui.interaction.pressable
import com.lin0721.linmusic.core.ui.theme.ScreenSlideDurationMs
import com.lin0721.linmusic.core.ui.components.MiniPlayerCard
import com.lin0721.linmusic.core.ui.theme.MimikyuPress
import com.lin0721.linmusic.core.ui.theme.BackgroundBlack
import com.lin0721.linmusic.core.ui.theme.BackgroundDark
import com.lin0721.linmusic.core.ui.theme.InfoCardRadius
import com.lin0721.linmusic.core.ui.theme.MimikyuSpacing
import com.lin0721.linmusic.core.update.UpdateManager
import com.lin0721.linmusic.core.update.UpdateUiState
import com.lin0721.linmusic.core.preferences.SettingsPreferences
import com.lin0721.linmusic.feature.home.ui.HomeViewModel
import com.lin0721.linmusic.feature.settings.ui.UpdateDialog
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import com.lin0721.linmusic.core.ui.theme.LocalMimikyuWindowSizeClass
import com.lin0721.linmusic.core.ui.theme.MimikyuWindowSizeClass
import com.lin0721.linmusic.core.ui.theme.rememberMimikyuWindowSizeClass
import com.lin0721.linmusic.core.ui.theme.LocalMimikyuOrientationClass
import com.lin0721.linmusic.core.ui.theme.rememberMimikyuOrientationClass
import com.lin0721.linmusic.feature.player.ui.PlayerDockPanel
import com.lin0721.linmusic.core.ui.theme.PanelFullscreenSpec
import com.lin0721.linmusic.core.ui.theme.PanelRiseSpec
import com.lin0721.linmusic.core.ui.theme.rememberMimikyuPlayerPanelWidth
import com.lin0721.linmusic.core.ui.theme.ProvideMimikyuContentSizeClass
import com.lin0721.linmusic.core.ui.theme.LocalMimikyuSystemBarsConsumed
import kotlin.math.roundToInt

private val SidebarWidth = 310.dp

// Play of ， and  MiniPlayerCard ， InfoCardRadius
private val PanelRiseStartRadius = 8.dp

// ， and  of
private const val PanelRiseFadeFraction = 0.4f

// of ：，
private class PanelRevealShape(
    private val top: Float,
    private val bottom: Float,
    private val radius: Float
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(RoundRect(0f, top, size.width, bottom, CornerRadius(radius)))
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MimikyuApp() {
    val viewModel: HomeViewModel = koinViewModel()
    val settingsPreferences: SettingsPreferences = koinInject()
    val showCreateEntry by settingsPreferences.showCreateEntry.collectAsStateWithLifecycle(initialValue = true)
    val currentTrack by viewModel.playerManager.currentTrack.collectAsStateWithLifecycle()
    val previousQueueItem by viewModel.playerManager.previousQueueItem.collectAsStateWithLifecycle()
    val nextQueueItem by viewModel.playerManager.nextQueueItem.collectAsStateWithLifecycle()
    val isPlaying by viewModel.playerManager.isPlaying.collectAsStateWithLifecycle()
    val isLoading by viewModel.playerManager.isLoading.collectAsStateWithLifecycle()
    // Play："Paused"， of  isPlaying
    val miniPlayerShowPause by viewModel.playerManager.playWhenReady.collectAsStateWithLifecycle()
    val currentPositionState = viewModel.playerManager.currentPosition.collectAsStateWithLifecycle()
    val currentPositionProvider = { currentPositionState.value }
    val duration by viewModel.playerManager.duration.collectAsStateWithLifecycle()
    val userProfile by viewModel.userProfile.collectAsStateWithLifecycle()
    val collectState by viewModel.collectState.collectAsStateWithLifecycle()
    val likedVideoIds by viewModel.likedVideoIds.collectAsStateWithLifecycle()
    val likedSongIds by viewModel.likedSongIds.collectAsStateWithLifecycle()
    val likedTracks by viewModel.likedTracks.collectAsStateWithLifecycle()
    val isMiniPlayerLiked = remember(currentTrack, likedVideoIds, likedSongIds, likedTracks) {
        val mediaId = currentTrack?.mediaId
        val numId = mediaId?.toLongOrNull()
        val vid = currentTrack?.mediaMetadata?.extras?.getString("videoId")
            ?: (if (numId != null) com.lin0721.linmusic.core.player.innertube.YouTubeIdRegistry.getVideoId(numId) else com.lin0721.linmusic.core.player.innertube.YouTubeIdRegistry.sanitizeVideoId(mediaId).takeIf { it.isNotBlank() })
        (vid != null && vid.isNotBlank() && vid in likedVideoIds) ||
        (numId != null && numId > 0L && numId in likedSongIds) ||
        likedTracks.any { track ->
            (numId != null && track.id == numId) ||
            (vid != null && vid.isNotBlank() && (com.lin0721.linmusic.core.player.innertube.YouTubeIdRegistry.getVideoId(track.id) == vid || track.toLikedSong()?.videoId == vid))
        }
    }

    val playerSheet = rememberMimikyuPlayerSheetState()
    val navigation = rememberMimikyuNavigationState()

    var showCreateSheet by remember { mutableStateOf(false) }
    // Song Identifier， and Play

    var isLoginScreenVisible by remember { mutableStateOf(false) }
    // MV Play：/Play，
    var isMvFullscreen by remember { mutableStateOf(false) }
    // MV PlayExpand：Expand MiniPlayer，
    var isMvCommentsOpen by remember { mutableStateOf(false) }
    // Play +  of ，
    var bottomOverlayHeight by remember { mutableStateOf(0.dp) }
    // ， MimikyuBottomOverlay
    val windowSizeClass = rememberMimikyuWindowSizeClass()
    // ， and
    val orientationClass = rememberMimikyuOrientationClass()
    // Expanded PlayExpand； and  playerSheet  of 。
    // Expand（ Tab ）
    var isPanelExpanded by remember { mutableStateOf(false) }
    val isPanelVisible = windowSizeClass == MimikyuWindowSizeClass.Expanded && isPanelExpanded
    // Expand：rise （）；
    // （、、、）。Collapse，
    val panelRise = remember { Animatable(0f) }
    var isPanelDocked by remember { mutableStateOf(false) }
    // ： of ；Collapse Expanded
    var isPanelFullscreen by remember { mutableStateOf(false) }
    val panelFullscreen = remember { Animatable(0f) }
    LaunchedEffect(isPanelVisible, windowSizeClass) {
        if (!isPanelVisible) isPanelFullscreen = false
        if (windowSizeClass != MimikyuWindowSizeClass.Expanded) {
            isPanelDocked = false
            panelRise.snapTo(0f)
            panelFullscreen.snapTo(0f)
        } else if (isPanelVisible) {
            panelRise.animateTo(1f, PanelRiseSpec)
            isPanelDocked = true
        } else {
            isPanelDocked = false
            panelRise.animateTo(0f, PanelRiseSpec)
        }
    }
    val isPanelShown by remember { derivedStateOf { panelRise.value > 0f } }
    var hasPanelBeenShown by remember { mutableStateOf(false) }
    LaunchedEffect(isPanelShown) {
        if (isPanelShown) hasPanelBeenShown = true
    }
    LaunchedEffect(isPanelFullscreen) {
        panelFullscreen.animateTo(if (isPanelFullscreen) 1f else 0f, PanelFullscreenSpec)
    }
    val panelWidth = rememberMimikyuPlayerPanelWidth()
    // of ，Play of
    var contentLayerWidth by remember { mutableStateOf(0.dp) }
    // ，； consumeWindowInsets ， of
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navigationBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // mini  of "Save to Playlist"， null
    var miniCollectSongId by remember { mutableStateOf<Long?>(null) }
    var showImportPlaylistDialog by remember { mutableStateOf(false) }

    val hazeState = remember { HazeState() }
    val density = LocalDensity.current

    val toastMessage = rememberGlobalToastMessage()

    fun handleBack() {
        val shouldReopenPlayer = navigation.navigateBack()
        if (shouldReopenPlayer) {
            playerSheet.animateTo(true, 0f)
        }
    }

    LaunchedEffect(currentTrack?.mediaId) {
        val originId = navigation.playerNavOriginMediaId
        val currentId = currentTrack?.mediaId
        if (originId != null && currentId != null && currentId != originId) {
            navigation.resetPlayerNavigation()
        }
    }

    // Back and Back：CloseBack。
    // activeTab != Home Current tab  1， handleBack()  tab，。
    // PlayBack——Expand，
    // of Collapse/Close，Back
    val isAnyOverlayOpen = playerSheet.isOpen || navigation.isNavigatingFromPlayer ||
            showCreateSheet || showImportPlaylistDialog || navigation.showMusicNewWorks || navigation.canNavigateBack ||
            navigation.activeTab != Screen.Home

    BackHandler(enabled = isAnyOverlayOpen) {
        when {
            showImportPlaylistDialog -> showImportPlaylistDialog = false
            playerSheet.isOpen -> {
                navigation.resetPlayerNavigation()
                playerSheet.animateTo(false, 0f)
            }
            navigation.isNavigatingFromPlayer -> handleBack()
            showCreateSheet -> showCreateSheet = false
            navigation.showMusicNewWorks -> navigation.updateShowMusicNewWorks(false)
            navigation.canNavigateBack || navigation.activeTab != Screen.Home -> handleBack()
        }
    }

    // BackClose Sidebar； of Back，
    BackHandler(enabled = isPanelFullscreen) {
        isPanelFullscreen = false
    }

    val userPreferences: UserPreferences = koinInject()
    val coroutineScope = rememberCoroutineScope()
    val hasSeenOnboardingState = userPreferences.hasSeenOnboarding.collectAsStateWithLifecycle(initialValue = null)
    var showOnboardingLoginSheet by remember { mutableStateOf(false) }

    if (hasSeenOnboardingState.value == null) {
        Box(modifier = Modifier.fillMaxSize().background(BackgroundDark))
        return
    }

    if (hasSeenOnboardingState.value == false) {
        GetStartedScreen(
            onGetStarted = {
                if (userProfile == null) {
                    showOnboardingLoginSheet = true
                } else {
                    coroutineScope.launch {
                        userPreferences.setHasSeenOnboarding(true)
                    }
                }
            },
            onSkip = {
                coroutineScope.launch {
                    userPreferences.setHasSeenOnboarding(true)
                }
            }
        )

        if (showOnboardingLoginSheet) {
            LoginBottomSheet(
                onDismiss = { showOnboardingLoginSheet = false },
                onWebLogin = {
                    showOnboardingLoginSheet = false
                    isLoginScreenVisible = true
                },
                onLoginSuccess = { cookies ->
                    showOnboardingLoginSheet = false
                    coroutineScope.launch {
                        userPreferences.setHasSeenOnboarding(true)
                    }
                    viewModel.handleLoginSuccess(cookies)
                }
            )
        }
        return
    }

    CompositionLocalProvider(
        LocalMimikyuWindowSizeClass provides windowSizeClass,
        LocalMimikyuOrientationClass provides orientationClass
    ) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged {
                playerSheet.onScreenSizeChanged(it.height.toFloat())
                contentLayerWidth = with(density) { it.width.toDp() }
            }
            .background(if (isPanelDocked) BackgroundBlack else BackgroundDark)
    ) {
            Row(modifier = Modifier.fillMaxSize()) {

                Spacer(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(if (isPanelDocked) MimikyuSpacing.sm else 0.dp)
                )
                CompositionLocalProvider(
                    LocalBottomOverlayInset provides bottomOverlayHeight,
                    LocalGlobalOverlayOpen provides (playerSheet.isOpen || showCreateSheet)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .then(
                                if (isPanelDocked) {
                                    // and ， of ，
                                    Modifier
                                        .padding(top = statusBarTop, bottom = navigationBarBottom)
                                        .consumeWindowInsets(WindowInsets.statusBars.union(WindowInsets.navigationBars))
                                        .clip(RoundedCornerShape(InfoCardRadius))
                                        .background(BackgroundDark)
                                } else {
                                    Modifier
                                }
                            )
                            .then(if (playerSheet.isOpen) Modifier.haze(hazeState) else Modifier)
                    ) {
                        // ； and ，，
                        ProvideMimikyuContentSizeClass(
                            reservedWidth = if (isPanelDocked) panelWidth + MimikyuSpacing.sm * 3 else 0.dp
                        ) {
                            CompositionLocalProvider(LocalMimikyuSystemBarsConsumed provides isPanelDocked) {
                                MimikyuNavHost(
                                    currentScreen = navigation.currentScreen,
                                    homeViewModel = viewModel,
                                    homeTab = navigation.homeTab,
                                    showMusicNewWorks = navigation.showMusicNewWorks,
                                    searchAutoFocus = navigation.searchAutoFocus,
                                    searchAutoFocusTrigger = navigation.searchAutoFocusTrigger,
                                    onOpenSidebar = { },
                                    onLoginScreenVisibilityChanged = { isLoginScreenVisible = it },
                                    onNavigateToLikedSongs = { navigation.openLikedSongs() },
                                    onNavigateToDownloadedSongs = { navigation.openDownloadedSongs() },
                                    onNavigateToPlaylist = { id, isAlbum -> navigation.openPlaylist(id, isAlbum) },
                                    onNavigateToArtist = { id -> navigation.openArtist(id) },
                                    onNavigateToMv = { id, name -> navigation.openMvPlayer(id, name) },
                                    onMvFullscreenChanged = { isMvFullscreen = it },
                                    onMvCommentsVisibilityChanged = { isMvCommentsOpen = it },
                                    onNavigateToPlaylistCategory = { category -> navigation.openPlaylistCategory(category) },
                                    onNavigateToProfile = { uid -> navigation.openProfile(uid) },
                                    onNavigateToFollowList = { uid, mode -> navigation.openFollowList(uid, mode) },
                                    onNavigateToRecentPlay = { navigation.openRecentPlay() },
                                    onNavigateToListenData = { navigation.openListenData() },
                                    onNavigateToMessage = { navigation.openMessage() },
                                    onNavigateToSettings = { navigation.navigateTo(Screen.Settings) },
                                    onNavigateToFriendsActivity = { navigation.openFriendsActivity() },
                                    onNavigateToEditProfile = { navigation.openEditProfile() },
                                    onImportPlaylist = { showImportPlaylistDialog = true },
                                    onLogout = { viewModel.logout() },
                                    onHomeTabSelected = { navigation.selectHomeTab(it) },
                                    onShowMusicNewWorksChanged = { navigation.updateShowMusicNewWorks(it) },
                                    onNavigateToSearch = { navigation.openSearch(autoFocus = true) },
                                    onNavigateToLocalMusic = { navigation.openLocalMusic() },
                                    onBack = { handleBack() }
                                )
                            }
                        }

                        // Create
                        if (showCreateSheet) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.4f))
                                    .pressable(MimikyuPress.None) { showCreateSheet = false }
                            )
                        }

                        // graphicsLayer  of  Box  of
                        MimikyuBottomOverlay(
                            modifier = Modifier.align(Alignment.BottomCenter),
                            currentScreen = navigation.currentScreen,
                            showCreateSheet = showCreateSheet,
                            isLoginScreenVisible = isLoginScreenVisible,
                            isMvFullscreen = isMvFullscreen,
                            isMvCommentsOpen = isMvCommentsOpen,
                            isPanelDocked = isPanelDocked,
                            currentTrack = currentTrack,
                            isPlaying = miniPlayerShowPause,
                            isLoading = isLoading,
                            currentPositionProvider = currentPositionProvider,
                            duration = duration,
                            hazeState = hazeState,
                            onTogglePlay = { viewModel.togglePlayPause() },
                            onNext = { viewModel.playerManager.playNext() },
                            onMiniPlayerClick = {
                                if (windowSizeClass == MimikyuWindowSizeClass.Expanded) {
                                    isPanelExpanded = true
                                } else {
                                    playerSheet.animateTo(true, 0f)
                                }
                            },
                            onMiniPlayerDrag = { delta ->
                                if (windowSizeClass != MimikyuWindowSizeClass.Expanded) playerSheet.onDrag(delta)
                            },
                            onMiniPlayerDragEnd = { velocity ->
                                if (windowSizeClass != MimikyuWindowSizeClass.Expanded) playerSheet.onDragEnd(velocity)
                            },
                            previousQueueItem = previousQueueItem,
                            nextQueueItem = nextQueueItem,
                            onMiniPlayerPrevious = { viewModel.playerManager.skipToPrevious() },
                            onCancelPendingSkip = { viewModel.playerManager.cancelPendingSkip() },
                            isMiniPlayerLiked = isMiniPlayerLiked,
                            onMiniPlayerLikeClick = {
                                viewModel.toggleLikeCurrentTrack()
                            },
                            onCreateDismiss = { showCreateSheet = false },
                            onNavigate = { navigation.openTab(it) },
                            onSearchLongClick = { navigation.openSearch(autoFocus = true) },
                            onCreateClick = { showCreateSheet = !showCreateSheet },
                            showCreateEntry = showCreateEntry,
                            onOverlayHeightChanged = { bottomOverlayHeight = it }
                        )
                    }
                }

                // Play： +
                Spacer(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(if (isPanelDocked) panelWidth + MimikyuSpacing.sm * 2 else 0.dp)
                )
            }

            // Play： of ， and ， and  sm 。
            // and  of  sm， and ；
            // ，， FullPlayerScreen 。
            // sm ，
            if ((isPanelShown || hasPanelBeenShown) && windowSizeClass == MimikyuWindowSizeClass.Expanded) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        // Expand，Collapse（、），ExpandPlay
                        .then(if (isPanelShown) Modifier else Modifier.layout { _, _ -> layout(0, 0) {} })
                        .padding(top = statusBarTop, end = MimikyuSpacing.sm, bottom = navigationBarBottom)
                        .layout { measurable, constraints ->
                            val sidebarWidthPx = panelWidth.roundToPx()
                            val fullscreenWidthPx = constraints.maxWidth - MimikyuSpacing.sm.roundToPx()
                            val progress = panelFullscreen.value.coerceIn(0f, 1f)
                            val width = (sidebarWidthPx + (fullscreenWidthPx - sidebarWidthPx) * progress)
                                .roundToInt()
                                .coerceAtLeast(sidebarWidthPx)
                            val placeable = measurable.measure(Constraints.fixed(width, constraints.maxHeight))
                            layout(width, constraints.maxHeight) { placeable.place(0, 0) }
                        }
                        .fillMaxHeight()
                        .graphicsLayer {
                            val progress = panelRise.value.coerceIn(0f, 1f)
                            val miniBottom = size.height - MimikyuSpacing.sm.toPx()
                            val miniTop = miniBottom - ExpandedBottomBarHeight.toPx()
                            val radius = PanelRiseStartRadius.toPx() +
                                (InfoCardRadius.toPx() - PanelRiseStartRadius.toPx()) * progress
                            clip = true
                            shape = PanelRevealShape(
                                top = miniTop * (1f - progress),
                                bottom = miniBottom + (size.height - miniBottom) * progress,
                                radius = radius
                            )
                            alpha = (progress / PanelRiseFadeFraction).coerceAtMost(1f)
                        }
                ) {
                    PlayerDockPanel(
                        currentTrack = currentTrack,
                        isPlaying = isPlaying,
                        currentPositionProvider = currentPositionProvider,
                        duration = duration,
                        onTogglePlay = { viewModel.togglePlayPause() },
                        onSeek = { viewModel.playerManager.seekTo(it) },
                        onClose = { isPanelExpanded = false },
                        onDragClose = { _, _ -> isPanelExpanded = false },
                        // of ，Close Sidebar
                        onArtistClick = { artistId ->
                            isPanelFullscreen = false
                            navigation.openArtist(artistId)
                        },
                        onAlbumClick = { albumId ->
                            isPanelFullscreen = false
                            navigation.openPlaylist(albumId, isAlbum = true)
                        },
                        onNavigateToProfile = { uid ->
                            isPanelFullscreen = false
                            navigation.openProfile(uid)
                        },
                        isFullscreen = isPanelFullscreen,
                        onToggleFullscreen = { isPanelFullscreen = !isPanelFullscreen },
                        fullscreenProgress = { panelFullscreen.value },
                        // and  of ： sm
                        fullscreenWidth = contentLayerWidth - MimikyuSpacing.sm * 2
                    )
                }
            }
        }

        MimikyuFullPlayerOverlay(
            currentTrack = currentTrack,
            screenHeightPx = playerSheet.screenHeightPx,
            isPlayerOpen = playerSheet.isOpen,
            playerOffsetY = playerSheet.offsetY,
            isPlaying = isPlaying,
            currentPositionProvider = currentPositionProvider,
            duration = duration,
            onTogglePlay = { viewModel.togglePlayPause() },
            onSeek = { viewModel.playerManager.seekTo(it) },
            onClose = {
                navigation.resetPlayerNavigation()
                playerSheet.animateTo(false, 0f)
            },
            onDragClose = { offset, velocity ->
                navigation.resetPlayerNavigation()
                playerSheet.animateTo(false, velocity, offset)
            },
            onArtistClick = { artistId ->
                navigation.navigateFromPlayer(currentTrack?.mediaId) {
                    navigation.openArtist(artistId)
                }
                playerSheet.animateTo(false, 0f)
            },
            onAlbumClick = { albumId ->
                navigation.navigateFromPlayer(currentTrack?.mediaId) {
                    navigation.openPlaylist(albumId, isAlbum = true)
                }
                playerSheet.animateTo(false, 0f)
            },
            onNavigateToProfile = { uid ->
                navigation.navigateFromPlayer(currentTrack?.mediaId) {
                    navigation.openProfile(uid)
                }
                playerSheet.animateTo(false, 0f)
            },
            modifier = Modifier.zIndex(1f)
        )


        // 5. Custom Toast
        MimikyuToastHost(toastMessage = toastMessage)

        // 6. ，
        val updateManager: UpdateManager = koinInject()
        val updateState by updateManager.uiState.collectAsStateWithLifecycle()
        val isDialogVisible by updateManager.isDialogVisible.collectAsStateWithLifecycle()
        if (isDialogVisible && updateState !is UpdateUiState.Idle) {
            UpdateDialog(
                state = updateState,
                onDismiss = { updateManager.dismiss() },
                onIgnore = { updateManager.ignoreCurrentVersion() },
                onStartDownload = { updateManager.startDownload() },
                onInstall = { updateManager.retryInstall() }
            )
        }

        // 7. mini  of "Save to Playlist"，Play
        miniCollectSongId?.let { songId ->
            PlaylistCollectSheet(
                songId = songId,
                collectState = collectState,
                onDismiss = { miniCollectSongId = null },
                onSaveCollection = { id, items -> viewModel.savePlaylistCollection(id, items) },
                onSaveNewCollection = { name, id -> viewModel.createPlaylistAndAddSong(name, id) }
            )
        }

        // 8. （ CSV / ZIP）
        if (showImportPlaylistDialog) {
            ImportPlaylistDialog(
                onDismiss = { showImportPlaylistDialog = false }
            )
        }
    }
}

// Subscribe Toast ， 2 Clear
@Composable
private fun rememberGlobalToastMessage(): String? {
    var message by remember { mutableStateOf<String?>(null) }
    var trigger by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        ToastManager.toastFlow.collect { msg ->
            message = msg
            trigger++
        }
    }

    LaunchedEffect(trigger) {
        if (message != null) {
            kotlinx.coroutines.delay(2000)
            message = null
        }
    }

    return message
}
