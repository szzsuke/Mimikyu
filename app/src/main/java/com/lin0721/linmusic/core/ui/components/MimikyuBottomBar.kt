package com.lin0721.linmusic.core.ui.components

import android.media.AudioDeviceInfo
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddBox
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.painterResource
import com.lin0721.linmusic.R
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import com.lin0721.linmusic.Screen
import com.lin0721.linmusic.core.player.QueueItem
import com.lin0721.linmusic.core.player.rememberQueueItemCoverUrl
import com.lin0721.linmusic.core.ui.interaction.pressable
import com.lin0721.linmusic.core.ui.theme.*
import com.lin0721.linmusic.feature.player.ui.deviceIcon
import com.lin0721.linmusic.feature.player.ui.deviceLabel
import com.lin0721.linmusic.feature.player.ui.rememberCurrentOutputDevice
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.delay

// ────────────────────────────────────────────────────────────────────────────
// AM++ (Apple Music Plus Plus)  Mini Player
// ────────────────────────────────────────────────────────────────────────────

@Composable
fun MiniPlayerCard(
    currentTrack: MediaItem?,
    isPlaying: Boolean,
    isLoading: Boolean = false,
    currentPositionProvider: () -> Long,
    duration: Long,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
    onDrag: ((Float) -> Unit)? = null,
    onDragEnd: ((Float) -> Unit)? = null,
    previousQueueItem: QueueItem? = null,
    nextQueueItem: QueueItem? = null,
    onPrevious: () -> Unit = {},
    onCancelPendingSkip: () -> Boolean = { false },
    isLiked: Boolean = false,
    onLikeClick: () -> Unit = {},
    onAddToPlaylistClick: (() -> Unit)? = null,
    expanded: Boolean = false
) {
    if (currentTrack == null) return

    val context = LocalContext.current
    val cleanCoverUrl = remember(currentTrack.mediaMetadata.artworkUri) {
        currentTrack.mediaMetadata.artworkUri?.toString() ?: ""
    }

    var colorPalette by remember(currentTrack.mediaId) {
        mutableStateOf(
            PaletteMemoryCache.get(currentTrack.mediaId) ?: FallbackBackdropPalette
        )
    }

    LaunchedEffect(currentTrack.mediaId, cleanCoverUrl) {
        if (PaletteMemoryCache.get(currentTrack.mediaId) == null && cleanCoverUrl.isNotEmpty()) {
            val palette = extractBackdropPaletteFromUrl(context, cleanCoverUrl)
            colorPalette = palette
            PaletteMemoryCache.put(currentTrack.mediaId, palette)
        }
    }

    val isPlayingState = isPlaying

    val targetStartColor = if (isPlayingState) {
        colorPalette.base.saturateIfChromatic(0.75f).darken(0.12f)
    } else {
        SurfaceDark
    }

    val targetEndColor = if (isPlayingState) {
        colorPalette.base.darken(0.48f)
    } else {
        BackgroundDark
    }

    val animatedStartColor by animateColorAsState(
        targetValue = targetStartColor,
        animationSpec = tween(550),
        label = "mini_player_grad_start"
    )

    val animatedEndColor by animateColorAsState(
        targetValue = targetEndColor,
        animationSpec = tween(550),
        label = "mini_player_grad_end"
    )

    val miniPlayerGradient = remember(animatedStartColor, animatedEndColor) {
        Brush.horizontalGradient(
            colors = listOf(animatedStartColor, animatedEndColor)
        )
    }

    val targetBorderColor = if (isPlayingState) {
        animatedStartColor.copy(alpha = 0.28f)
    } else {
        Color.White.copy(alpha = 0.08f)
    }
    val animatedBorderColor by animateColorAsState(
        targetValue = targetBorderColor,
        animationSpec = tween(500),
        label = "mini_player_border"
    )

    val spotShadowColor = if (isPlayingState) {
        animatedStartColor.copy(alpha = 0.40f)
    } else {
        Color.Black.copy(alpha = 0.65f)
    }

    val swipeState = rememberSwipeToSkipCoverState()
    val isActivelySwiping = swipeState.isDragActive || swipeState.settleJob?.isActive == true
    val swipeDirection = swipeState.syncCurrentKey(
        key = currentTrack.mediaId,
        previousKey = previousQueueItem?.songId?.toString(),
        nextKey = nextQueueItem?.songId?.toString()
    )

    // Snapshot track during active drag gesture so sliding card stays consistent
    var trackDuringSwipe by remember { mutableStateOf(currentTrack) }
    var previousDuringSwipe by remember { mutableStateOf(previousQueueItem) }
    var nextDuringSwipe by remember { mutableStateOf(nextQueueItem) }

    if (swipeState.isDragActive) {
        // Preserve while user is physically dragging
    } else if (isActivelySwiping && swipeState.isTransitioning) {
        // Preserve while settle animation finishes
    } else {
        trackDuringSwipe = currentTrack
        previousDuringSwipe = previousQueueItem
        nextDuringSwipe = nextQueueItem
    }

    val displayedTrack = if (isActivelySwiping && swipeState.isTransitioning) trackDuringSwipe else currentTrack
    val displayedPrevious = if (isActivelySwiping && swipeState.isTransitioning) previousDuringSwipe else previousQueueItem
    val displayedNext = if (isActivelySwiping && swipeState.isTransitioning) nextDuringSwipe else nextQueueItem

    val canSwipeToPrevious = displayedPrevious != null
    val canSwipeToNext = displayedNext != null
    val displayedCleanCoverUrl = remember(displayedTrack.mediaMetadata.artworkUri) {
        displayedTrack.mediaMetadata.artworkUri?.toString() ?: ""
    }
    var pendingSlideInKey by remember { mutableStateOf<Any?>(null) }
    if (swipeDirection != null) {
        pendingSlideInKey = currentTrack.mediaId
    }
    LaunchedEffect(pendingSlideInKey) {
        if (pendingSlideInKey != null) {
            animate(swipeState.offsetX, 0f, 0f, SwipeCoverSpringSpec) { value, _ ->
                swipeState.offsetX = value
            }
        }
    }

    // Mimikyu Floating Mini Player Card (12dp corners as in user screenshot)
    val miniPlayerCornerRadius = 12.dp
    val miniPlayerShape = RoundedCornerShape(miniPlayerCornerRadius)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .shadow(
                elevation = 8.dp,
                shape = miniPlayerShape,
                ambientColor = Color.Black.copy(alpha = 0.45f),
                spotColor = spotShadowColor
            )
            .clip(miniPlayerShape)
            .background(miniPlayerGradient)
            .border(1.dp, animatedBorderColor, miniPlayerShape)
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { delta ->
                    onDrag?.invoke(delta)
                },
                onDragStopped = { velocity ->
                    onDragEnd?.invoke(velocity)
                }
            )
            .then(
                rememberSwipeToSkipDragModifier(
                    state = swipeState,
                    canSwipeToPrevious = canSwipeToPrevious,
                    canSwipeToNext = canSwipeToNext,
                    onConfirmPrevious = onPrevious,
                    onConfirmNext = onNext,
                    onCancelPending = onCancelPendingSkip
                )
            )
            .clickable(onClick = onClick)
    ) {
        val connectedDevice = rememberCurrentOutputDevice()
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 8.dp, end = 10.dp)
        ) {
            MiniPlayerSlidingContent(
                state = swipeState,
                currentKey = displayedTrack.mediaId,
                currentTitle = displayedTrack.mediaMetadata.title?.toString() ?: "Unknown Title",
                currentArtist = displayedTrack.mediaMetadata.artist?.toString().orEmpty(),
                currentCoverUrl = displayedCleanCoverUrl,
                previousQueueItem = displayedPrevious,
                nextQueueItem = displayedNext,
                connectedDevice = connectedDevice,
                isPlaying = isPlaying,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Favorite / Like Button
                MimikyuIconButton(
                    onClick = onLikeClick,
                    modifier = Modifier.size(36.dp)
                ) {
                    val heartIcon = if (isLiked) MimikyuIcons.HeartFilled else MimikyuIcons.Heart
                    val heartIconTint = if (isLiked) {
                        MimikyuYellow
                    } else if (isPlaying) {
                        MimikyuSilver.copy(alpha = 0.90f)
                    } else {
                        MimikyuLightGray
                    }
                    Icon(
                        painter = painterResource(heartIcon),
                        contentDescription = if (isLiked) "Unlike" else "Like",
                        tint = heartIconTint,
                        modifier = Modifier
                            .size(24.dp)
                            .offset(y = (-1.5).dp)
                    )
                }

                // Play / Pause / Loading Button (Matches User Screenshot)
                MimikyuIconButton(
                    onClick = onTogglePlay,
                    modifier = Modifier.size(36.dp)
                ) {
                    val isOptimisticLoading = isLoading || (!isPlaying && displayedTrack.localConfiguration?.uri?.toString() == "about:blank")
                    if (isOptimisticLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MimikyuSilver
                        )
                    } else {
                        val playIconTint = if (isPlaying) MimikyuSilver else MimikyuLightGray
                        Icon(
                            painter = painterResource(if (isPlaying) MimikyuIcons.Pause else MimikyuIcons.Play),
                            contentDescription = "Play/Pause",
                            tint = playIconTint,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }

        // Mimikyu Progress bar at bottom of miniplayer
        MiniPlayerProgress(
            currentPositionProvider = currentPositionProvider,
            duration = duration,
            isPlaying = isPlaying,
            isConnectActive = connectedDevice != null,
            activeColorOverride = if (isPlayingState) colorPalette.base.toPastelProgressColor() else null,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

private class MiniPlayerSlideLayer(
    val key: Any,
    val title: String,
    val artist: String,
    val coverUrl: String,
    val connectedDevice: AudioDeviceInfo?,
    val translationXProvider: () -> Float
)

@Composable
private fun MiniPlayerSlidingContent(
    state: SwipeToSkipCoverState,
    currentKey: Any,
    currentTitle: String,
    currentArtist: String,
    currentCoverUrl: String,
    previousQueueItem: QueueItem?,
    nextQueueItem: QueueItem?,
    connectedDevice: AudioDeviceInfo?,
    isPlaying: Boolean = true,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clipToBounds()
            .onSizeChanged { state.containerWidthPx = it.width.toFloat() }
    ) {
        val layers = buildList {
            previousQueueItem?.let { item ->
                add(
                    MiniPlayerSlideLayer(
                        key = item.songId.toString(),
                        title = item.title,
                        artist = item.artist,
                        coverUrl = rememberQueueItemCoverUrl(item.coverUrl, item.songId, item.localUri, item.title, item.artist),
                        connectedDevice = null,
                        translationXProvider = { state.offsetX - state.containerWidthPx }
                    )
                )
            }
            add(
                MiniPlayerSlideLayer(
                    key = currentKey,
                    title = currentTitle,
                    artist = currentArtist,
                    coverUrl = currentCoverUrl,
                    connectedDevice = connectedDevice,
                    translationXProvider = { state.offsetX }
                )
            )
            nextQueueItem?.let { item ->
                add(
                    MiniPlayerSlideLayer(
                        key = if (item.songId == previousQueueItem?.songId) "${item.songId}-next" else item.songId.toString(),
                        title = item.title,
                        artist = item.artist,
                        coverUrl = rememberQueueItemCoverUrl(item.coverUrl, item.songId, item.localUri, item.title, item.artist),
                        connectedDevice = null,
                        translationXProvider = { state.offsetX + state.containerWidthPx }
                    )
                )
            }
        }
        layers.forEach { layer ->
            key(layer.key) {
                MiniPlayerContentRow(
                    title = layer.title,
                    artist = layer.artist,
                    coverUrl = layer.coverUrl,
                    connectedDevice = layer.connectedDevice,
                    isPlaying = isPlaying,
                    translationXProvider = layer.translationXProvider
                )
            }
        }
    }
}

@Composable
private fun MiniPlayerContentRow(
    title: String,
    artist: String,
    coverUrl: String,
    connectedDevice: AudioDeviceInfo?,
    isPlaying: Boolean = true,
    translationXProvider: () -> Float
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { translationX = translationXProvider() }
    ) {
        // 42x42dp cover with 6dp rounded corners matching screenshot
        AntiFlickerCoverImage(
            url = coverUrl,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(6.dp))
                .graphicsLayer {
                    alpha = if (isPlaying) 1f else 0.85f
                }
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            if (connectedDevice != null && artist.isNotBlank()) {
                val titleAndArtist = buildAnnotatedString {
                    withStyle(
                        SpanStyle(
                            color = if (isPlaying) MimikyuSilver else MimikyuSilver.copy(alpha = 0.82f),
                            fontWeight = FontWeight.SemiBold
                        )
                    ) {
                        append(title)
                    }
                    withStyle(
                        SpanStyle(
                            color = MimikyuLightGray,
                            fontWeight = FontWeight.Normal
                        )
                    ) {
                        append(" · $artist")
                    }
                }
                Text(
                    text = titleAndArtist,
                    fontSize = 14.sp,
                    fontFamily = RobotoFontFamily,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(
                        fontFamily = RobotoFontFamily,
                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                        lineHeight = 17.sp
                    ),
                    modifier = Modifier.basicMarquee()
                )
            } else {
                Text(
                    text = title,
                    color = if (isPlaying) MimikyuSilver else MimikyuSilver.copy(alpha = 0.82f),
                    fontSize = 14.sp,
                    fontFamily = RobotoFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(
                        fontFamily = RobotoFontFamily,
                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                        lineHeight = 17.sp
                    ),
                    modifier = Modifier.basicMarquee()
                )
            }

            if (connectedDevice != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(MimikyuIcons.Devices),
                        contentDescription = null,
                        tint = OctoberRed,
                        modifier = Modifier.size(11.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = deviceLabel(connectedDevice),
                        color = OctoberRed,
                        fontSize = 11.5.sp,
                        fontFamily = RobotoFontFamily,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(
                            fontFamily = RobotoFontFamily,
                            platformStyle = PlatformTextStyle(includeFontPadding = false),
                            lineHeight = 13.sp
                        )
                    )
                }
            } else if (artist.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = artist,
                    color = MimikyuLightGray,
                    fontSize = 12.sp,
                    fontFamily = RobotoFontFamily,
                    fontWeight = FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(
                        fontFamily = RobotoFontFamily,
                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                        lineHeight = 14.sp
                    )
                )
            }
        }
    }
}

// ────────────────────────────────────────────────────────────────────────────
// Mimikyu Mini Player Progress Bar with Thumb Dot (Matches User Screenshot)
// ────────────────────────────────────────────────────────────────────────────

@Composable
fun MiniPlayerProgress(
    currentPositionProvider: () -> Long,
    duration: Long,
    isPlaying: Boolean = true,
    isConnectActive: Boolean = false,
    activeColorOverride: Color? = null,
    modifier: Modifier = Modifier
) {
    val bottomCornerShape = RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)
    val currentPosition = currentPositionProvider()
    val progress = if (duration > 0) (currentPosition.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val targetActiveColor = when {
        isConnectActive -> OctoberRed
        isPlaying -> activeColorOverride ?: MimikyuPink
        else -> Color(0xFF9E9E9E)
    }
    val activeColor by animateColorAsState(
        targetValue = targetActiveColor,
        animationSpec = tween(400),
        label = "mini_player_prog_color"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(2.5.dp),
        contentAlignment = Alignment.BottomStart
    ) {
        // Track line
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.5.dp)
                .clip(bottomCornerShape)
                .background(MimikyuStoneWhite.copy(alpha = 0.40f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .fillMaxHeight()
                    .background(activeColor)
            )
        }
    }
}

// ────────────────────────────────────────────────────────────────────────────
// Mimikyu Dock (Transparent with gradient as shown in screenshot)
// ────────────────────────────────────────────────────────────────────────────

private data class MimikyuTab(
    val label: String,
    val inactiveIconRes: Int,
    val activeIconRes: Int,
    val screen: Screen?
)

@Composable
fun MimikyuNavigationBar(
    currentScreen: Screen,
    onNavigate: (Screen) -> Unit,
    onSearchLongClick: () -> Unit = {},
    onCreateClick: () -> Unit = {},
    isCreateMenuOpen: Boolean = false,
    showCreateEntry: Boolean = true,
    expanded: Boolean = false,
    modifier: Modifier = Modifier
) {
    val tabs = remember(showCreateEntry) {
        buildList {
            add(MimikyuTab("Home", MimikyuIcons.Home, MimikyuIcons.HomeFilled, Screen.Home))
            add(MimikyuTab("Library", MimikyuIcons.Library, MimikyuIcons.LibraryFilled, Screen.Library))
            add(MimikyuTab("Search", MimikyuIcons.Search, MimikyuIcons.SearchFilled, Screen.Search))
            if (showCreateEntry) {
                add(MimikyuTab("Create", 0, 0, null))
            }
            add(MimikyuTab("Me", MimikyuIcons.User, MimikyuIcons.UserFilled, Screen.Account))
        }
    }

    val dockShape = if (expanded) RoundedCornerShape(InfoCardRadius) else RoundedCornerShape(0.dp)
    val outerModifier = if (expanded) {
        modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .clip(dockShape)
    } else {
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
    }

    Box(
        modifier = outerModifier
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Black.copy(alpha = 0.25f),
                        Color.Black.copy(alpha = 0.55f),
                        Color.Black.copy(alpha = 0.75f)
                    )
                )
            )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (expanded) Modifier.fillMaxHeight() else Modifier.height(50.dp)
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                tabs.forEach { tab ->
                    val isSelected = when (tab.screen) {
                        is Screen.Home -> currentScreen is Screen.Home
                        is Screen.Library -> currentScreen is Screen.Library ||
                                             currentScreen is Screen.Playlist ||
                                             currentScreen is Screen.LikedSongs ||
                                             currentScreen is Screen.LocalMusic
                        is Screen.Search -> currentScreen is Screen.Search
                        is Screen.Account -> currentScreen is Screen.Account ||
                                             currentScreen is Screen.Settings ||
                                             currentScreen is Screen.Profile
                        null -> isCreateMenuOpen
                        else -> currentScreen == tab.screen
                    }

                    val isSearchTab = tab.screen is Screen.Search

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .pressable(
                                style = MimikyuPress.Tab,
                                onLongClick = if (isSearchTab) onSearchLongClick else null
                            ) {
                                if (tab.screen != null) {
                                    onNavigate(tab.screen)
                                } else {
                                    onCreateClick()
                                }
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        if (tab.screen != null) {
                            Icon(
                                painter = painterResource(if (isSelected) tab.activeIconRes else tab.inactiveIconRes),
                                contentDescription = tab.label,
                                tint = if (isSelected) MimikyuSilver else MimikyuLightGray,
                                modifier = Modifier.size(22.dp)
                            )
                        } else {
                            Icon(
                                painter = painterResource(if (isCreateMenuOpen) MimikyuIcons.Close else MimikyuIcons.Plus),
                                contentDescription = tab.label,
                                tint = if (isCreateMenuOpen) MimikyuSilver else MimikyuLightGray,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = tab.label,
                            color = if (isSelected) MimikyuSilver else MimikyuLightGray,
                            fontSize = 10.5.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                            style = TextStyle(
                                platformStyle = PlatformTextStyle(includeFontPadding = false)
                            )
                        )
                    }
                }
            }
        }
    }
}
