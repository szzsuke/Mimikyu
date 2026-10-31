package com.lin0721.linmusic

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.media3.common.MediaItem
import com.lin0721.linmusic.core.download.ui.DownloadProgressBanner
import com.lin0721.linmusic.core.player.QueueItem
import com.lin0721.linmusic.core.ui.components.CustomToast
import com.lin0721.linmusic.core.ui.components.MimikyuNavigationBar
import com.lin0721.linmusic.core.ui.components.MiniPlayerCard
import com.lin0721.linmusic.feature.create.ui.CreatePopupMenu
import com.lin0721.linmusic.feature.player.ui.FullPlayerScreen
import dev.chrisbanes.haze.HazeState
import com.lin0721.linmusic.core.ui.theme.MimikyuSpacing
import com.lin0721.linmusic.core.ui.theme.LocalMimikyuWindowSizeClass
import com.lin0721.linmusic.core.ui.theme.MimikyuWindowSizeClass
import com.lin0721.linmusic.core.ui.theme.rememberMimikyuPlayerPanelWidth

// Play +  of ，，
val LocalBottomOverlayInset = staticCompositionLocalOf { 0.dp }

// Expanded  and  of ，（ 10dp×2 +  52dp +  2dp），
// Play of 。 IntrinsicSize： of
internal val ExpandedBottomBarHeight = 65.dp

// Tab  of
private val ExpandedNavigationBarMaxWidth = 560.dp

// Play//Create。 of  BackHandler（Collapse、Search）
// ， Compose Back of  BackHandler ，
// Back， of
val LocalGlobalOverlayOpen = staticCompositionLocalOf { false }

// ────────────────────────────────────────────────────────────────────────────
// ：Create + Play + M3
// ────────────────────────────────────────────────────────────────────────────
@Composable
fun MimikyuBottomOverlay(
    modifier: Modifier = Modifier,
    currentScreen: Screen,
    showCreateSheet: Boolean,
    isLoginScreenVisible: Boolean,
    isMvFullscreen: Boolean,
    isMvCommentsOpen: Boolean = false,
    isPanelDocked: Boolean = false,
    currentTrack: MediaItem?,
    isPlaying: Boolean,
    isLoading: Boolean = false,
    currentPositionProvider: () -> Long,
    duration: Long,
    hazeState: HazeState,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onMiniPlayerClick: () -> Unit,
    onMiniPlayerDrag: (Float) -> Unit,
    onMiniPlayerDragEnd: (Float) -> Unit,
    previousQueueItem: QueueItem? = null,
    nextQueueItem: QueueItem? = null,
    onMiniPlayerPrevious: () -> Unit = {},
    onCancelPendingSkip: () -> Boolean = { false },
    isMiniPlayerLiked: Boolean = false,
    onMiniPlayerLikeClick: () -> Unit = {},
    onCreateDismiss: () -> Unit,
    onNavigate: (Screen) -> Unit,
    onSearchLongClick: () -> Unit = {},
    onCreateClick: () -> Unit,
    showCreateEntry: Boolean = true,
    onOverlayHeightChanged: (Dp) -> Unit = {}
) {
    val density = LocalDensity.current
    Column(
        modifier = modifier
            .fillMaxWidth()
    ) {
        // 0. Create
        AnimatedVisibility(
            visible = showCreateSheet && !isLoginScreenVisible,
            enter = slideInVertically(
                initialOffsetY = { it / 2 },
                animationSpec = tween(250, easing = FastOutSlowInEasing)
            ) + fadeIn(tween(200)),
            exit = slideOutVertically(
                targetOffsetY = { it / 2 },
                animationSpec = tween(200, easing = FastOutSlowInEasing)
            ) + fadeOut(tween(150))
        ) {
            Box(
                modifier = Modifier
                    .padding(horizontal = MimikyuSpacing.md)
                    .padding(bottom = 12.dp)
            ) {
                CreatePopupMenu(
                    onDismiss = onCreateDismiss,
                    onLoginRequest = {
                        // TODO:
                    }
                )
            }
        }

        // Play + Dock Overlays: Transparent with vertical gradient as in Mimikyu UI
        val windowSizeClass = LocalMimikyuWindowSizeClass.current
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Transparent,
                        0.22f to Color.Black.copy(alpha = 0.25f),
                        0.55f to Color.Black.copy(alpha = 0.55f),
                        1.0f to Color.Black.copy(alpha = 0.75f)
                    )
                )
                .onSizeChanged {
                    onOverlayHeightChanged(with(density) { it.height.toDp() })
                }
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // Download
                AnimatedVisibility(
                    visible = false,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    DownloadProgressBanner(modifier = Modifier.fillMaxWidth())
                }

                if (windowSizeClass == MimikyuWindowSizeClass.Expanded) {
                    // ：Tab  + Play，，
                    // ；
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 14.dp)
                            .padding(bottom = 4.dp)
                            .padding(bottom = MimikyuSpacing.sm)
                            .height(ExpandedBottomBarHeight),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        AnimatedVisibility(
                            visible = !isLoginScreenVisible && !isMvFullscreen && !isMvCommentsOpen,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut(),
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        ) {
                            // Collapse Tab ；
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                MimikyuNavigationBar(
                                    currentScreen = currentScreen,
                                    onNavigate = onNavigate,
                                    onSearchLongClick = onSearchLongClick,
                                    onCreateClick = onCreateClick,
                                    isCreateMenuOpen = showCreateSheet,
                                    showCreateEntry = showCreateEntry,
                                    expanded = true,
                                    modifier = Modifier
                                        .then(
                                            if (isPanelDocked) Modifier.widthIn(max = ExpandedNavigationBarMaxWidth)
                                            else Modifier
                                        )
                                        .fillMaxHeight()
                                )
                            }
                        }

                        // ，、，/。
                        // and  of  spacedBy，/ 8dp
                        if (!isPanelDocked) {
                            AnimatedVisibility(
                                visible = currentTrack != null && !isLoginScreenVisible && !isMvFullscreen && !isMvCommentsOpen,
                                enter = fadeIn() + expandHorizontally(),
                                exit = fadeOut() + shrinkHorizontally(),
                                modifier = Modifier.fillMaxHeight()
                            ) {
                                MiniPlayerCard(
                                    hazeState = hazeState,
                                    currentTrack = currentTrack,
                                    isPlaying = isPlaying,
                                    isLoading = isLoading,
                                    currentPositionProvider = currentPositionProvider,
                                    duration = duration,
                                    onTogglePlay = onTogglePlay,
                                    onNext = onNext,
                                    onClick = onMiniPlayerClick,
                                    onDrag = onMiniPlayerDrag,
                                    onDragEnd = onMiniPlayerDragEnd,
                                    previousQueueItem = previousQueueItem,
                                    nextQueueItem = nextQueueItem,
                                    onPrevious = onMiniPlayerPrevious,
                                    onCancelPendingSkip = onCancelPendingSkip,
                                    isLiked = isMiniPlayerLiked,
                                    onLikeClick = onMiniPlayerLikeClick,
                                    expanded = true,
                                    // Expand，
                                    modifier = Modifier
                                        .padding(start = MimikyuSpacing.sm)
                                        .width(rememberMimikyuPlayerPanelWidth())
                                        .fillMaxHeight()
                                )
                            }
                        }
                    }
                } else {
                    // ：Play、，（）
                    AnimatedVisibility(
                        visible = currentTrack != null && !isLoginScreenVisible && !isMvFullscreen && !isMvCommentsOpen,
                        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        MiniPlayerCard(
                            hazeState = hazeState,
                            currentTrack = currentTrack,
                            isPlaying = isPlaying,
                            isLoading = isLoading,
                            currentPositionProvider = currentPositionProvider,
                            duration = duration,
                            onTogglePlay = onTogglePlay,
                            onNext = onNext,
                            onClick = onMiniPlayerClick,
                            onDrag = onMiniPlayerDrag,
                            onDragEnd = onMiniPlayerDragEnd,
                            previousQueueItem = previousQueueItem,
                            nextQueueItem = nextQueueItem,
                            onPrevious = onMiniPlayerPrevious,
                            onCancelPendingSkip = onCancelPendingSkip,
                            isLiked = isMiniPlayerLiked,
                            onLikeClick = onMiniPlayerLikeClick,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp)
                        )
                    }

                    AnimatedVisibility(
                        visible = !isLoginScreenVisible && !isMvFullscreen && !isMvCommentsOpen,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        MimikyuNavigationBar(
                            currentScreen = currentScreen,
                            onNavigate = onNavigate,
                            onSearchLongClick = onSearchLongClick,
                            onCreateClick = onCreateClick,
                            isCreateMenuOpen = showCreateSheet,
                            showCreateEntry = showCreateEntry
                        )
                    }
                }
            }
        }
    }
}

// ────────────────────────────────────────────────────────────────────────────
// Play（ playerOffsetY /）
// ────────────────────────────────────────────────────────────────────────────
@Composable
fun MimikyuFullPlayerOverlay(
    currentTrack: MediaItem?,
    screenHeightPx: Float,
    isPlayerOpen: Boolean,
    playerOffsetY: Float,
    isPlaying: Boolean,
    currentPositionProvider: () -> Long,
    duration: Long,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
    onClose: () -> Unit,
    onDragClose: (Float, Float) -> Unit,
    onArtistClick: (Long) -> Unit,
    onAlbumClick: (Long) -> Unit,
    onNavigateToProfile: (Long) -> Unit = {},
    modifier: Modifier = Modifier
) {
    // Play，Close nestedScroll
    if (currentTrack != null && screenHeightPx > 0f && isPlayerOpen) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .offset { IntOffset(0, playerOffsetY.toInt()) }
                .clip(
                    RoundedCornerShape(
                        topStart = if (playerOffsetY > 0f) 24.dp else 0.dp,
                        topEnd = if (playerOffsetY > 0f) 24.dp else 0.dp
                    )
                )
        ) {
            FullPlayerScreen(
                currentTrack = currentTrack,
                isPlaying = isPlaying,
                currentPositionProvider = currentPositionProvider,
                duration = duration,
                onTogglePlay = onTogglePlay,
                onSeek = onSeek,
                onClose = onClose,
                onDragClose = onDragClose,
                isPlayerOpen = isPlayerOpen,
                onArtistClick = onArtistClick,
                onAlbumClick = onAlbumClick,
                onNavigateToProfile = onNavigateToProfile,
                fitCoverAboveNavigationBar = true
            )
        }
    }
}

// ────────────────────────────────────────────────────────────────────────────
// Custom Toast
// ────────────────────────────────────────────────────────────────────────────
@Composable
fun MimikyuToastHost(toastMessage: String?) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter
    ) {
        AnimatedVisibility(
            visible = toastMessage != null,
            enter = slideInVertically(
                initialOffsetY = { it },
                animationSpec = tween(300, easing = FastOutSlowInEasing)
            ) + fadeIn(tween(250)),
            exit = slideOutVertically(
                targetOffsetY = { it },
                animationSpec = tween(250, easing = FastOutSlowInEasing)
            ) + fadeOut(tween(200)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 120.dp)
                .zIndex(999f)
        ) {
            toastMessage?.let { msg ->
                CustomToast(message = msg)
            }
        }
    }
}
