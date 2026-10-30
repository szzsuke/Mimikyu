package com.lin0721.linmusic.feature.player.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ViewSidebar
import androidx.compose.material.icons.outlined.ViewSidebar
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lin0721.linmusic.core.ui.components.MimikyuIconButton
import com.lin0721.linmusic.core.ui.theme.MimikyuIcons
import com.lin0721.linmusic.core.ui.components.SwipeToSkipCover
import com.lin0721.linmusic.core.ui.theme.RadiusCompact
import com.lin0721.linmusic.core.ui.theme.MimikyuSpacing

import android.graphics.Bitmap
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import com.lin0721.linmusic.core.player.QueueItem
import com.lin0721.linmusic.core.player.rememberQueueItemCoverUrl
import com.lin0721.linmusic.core.ui.components.AntiFlickerCoverImage
import com.lin0721.linmusic.core.ui.components.CoverContourShadowHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import com.lin0721.linmusic.core.canvas.CanvasArtwork
import com.lin0721.linmusic.core.canvas.CanvasRepository
import com.lin0721.linmusic.core.metadata.SongArtworkEngine
import com.lin0721.linmusic.core.preferences.SettingsPreferences
import org.koin.compose.koinInject

private val SidebarToggleGap = 4.dp

@Composable
fun FullPlayerCoverPager(
    queue: List<QueueItem>,
    currentIndex: Int,
    fallbackCoverUrl: String,
    onPlayIndex: (Int) -> Unit,
    isPlaying: Boolean = true,
    modifier: Modifier = Modifier,
    contentPadding: Dp = MimikyuSpacing.lg,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(RadiusCompact),
    elevation: Dp = 24.dp,
    pageSpacing: Dp = 16.dp,
    onCurrentBitmapLoaded: ((Bitmap) -> Unit)? = null
) {
    val pageCount = queue.size.coerceAtLeast(1)
    val safeInitialPage = currentIndex.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
    val pagerState = rememberPagerState(
        initialPage = safeInitialPage,
        pageCount = { pageCount }
    )

    var lastHandledSongId by remember { mutableLongStateOf(queue.getOrNull(currentIndex)?.songId ?: -1L) }
    val currentSongId = queue.getOrNull(currentIndex)?.songId ?: -1L
    var isProgrammaticScroll by remember { mutableStateOf(false) }

    // Bidirectional sync: when ExoPlayer track changes externally (Next/Prev, notification, auto-transition)
    LaunchedEffect(currentIndex, queue.size, currentSongId) {
        if (queue.isNotEmpty() && currentIndex in queue.indices) {
            val isSameSong = currentSongId != -1L && currentSongId == lastHandledSongId
            lastHandledSongId = currentSongId

            if (pagerState.currentPage != currentIndex && !pagerState.isScrollInProgress) {
                isProgrammaticScroll = true
                try {
                    if (isSameSong || kotlin.math.abs(pagerState.currentPage - currentIndex) > 1) {
                        // Queue reordered under the same song (e.g. toggle shuffle) or large jump:
                        // Instantly snap to target page without sweeping through the entire playlist!
                        pagerState.scrollToPage(currentIndex)
                    } else {
                        pagerState.animateScrollToPage(currentIndex)
                    }
                } finally {
                    isProgrammaticScroll = false
                }
            }
        }
    }

    // Bidirectional sync: when user swipes pager to skip (Mimikyu exact swipe-to-skip gesture)
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { settledPage ->
                if (!isProgrammaticScroll && queue.isNotEmpty() && settledPage in queue.indices && settledPage != currentIndex) {
                    onPlayIndex(settledPage)
                }
            }
    }

    val pageKeys = remember(queue) {
        queue.map { it.uniqueQueueId }
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    val settingsPreferences: SettingsPreferences = koinInject()
    val animatedCoverEnabled by settingsPreferences.animatedCoverEnabled.collectAsState(initial = true)
    val canvasOverCellular by settingsPreferences.canvasOverCellular.collectAsState(initial = true)

    HorizontalPager(
        state = pagerState,
        key = { page -> pageKeys.getOrElse(page) { page } },
        contentPadding = PaddingValues(horizontal = contentPadding),
        pageSpacing = pageSpacing,
        modifier = modifier.fillMaxWidth()
    ) { page ->
        val item = queue.getOrNull(page)
        val resolvedCoverUrl = if (item != null) {
            val raw = rememberQueueItemCoverUrl(item.coverUrl, item.songId, item.localUri, item.title, item.artist)
                .ifBlank { if (page == currentIndex) fallbackCoverUrl else "" }
            SongArtworkEngine.optimizeArtworkUrl(raw, 1200)
        } else {
            SongArtworkEngine.optimizeArtworkUrl(fallbackCoverUrl, 1200)
        }

        var canvasArtwork by remember(item?.songId, item?.title, item?.artist) {
            mutableStateOf<CanvasArtwork?>(null)
        }

        LaunchedEffect(item?.songId, item?.title, item?.artist, animatedCoverEnabled, canvasOverCellular) {
            if (page == currentIndex && animatedCoverEnabled && item != null) {
                canvasArtwork = CanvasRepository.canvasFor(
                    title = item.title,
                    artist = item.artist,
                    album = item.album,
                    videoId = item.effectiveVideoId() ?: ""
                )
            } else {
                canvasArtwork = null
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            contentAlignment = Alignment.Center
        ) {
            var isTransparentCover by remember(resolvedCoverUrl) {
                mutableStateOf(CoverContourShadowHelper.isTransparent(resolvedCoverUrl) == true)
            }
            val coroutineScope = rememberCoroutineScope()

            AntiFlickerCoverImage(
                url = resolvedCoverUrl,
                contentScale = ContentScale.Crop,
                onImageLoaded = { bitmap ->
                    if (page == currentIndex) {
                        onCurrentBitmapLoaded?.invoke(bitmap)
                    }
                    coroutineScope.launch(Dispatchers.Default) {
                        val isTrans = CoverContourShadowHelper.detectIsTransparentCover(bitmap)
                        CoverContourShadowHelper.markTransparency(resolvedCoverUrl, isTrans)
                        if (isTrans != isTransparentCover) {
                            withContext(Dispatchers.Main) {
                                isTransparentCover = isTrans
                            }
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (!isTransparentCover && elevation > 0.dp) {
                            Modifier.shadow(elevation = elevation, shape = shape, clip = false)
                        } else {
                            Modifier
                        }
                    )
                    .then(
                        if (!isTransparentCover) {
                            Modifier.clip(shape)
                        } else {
                            Modifier
                        }
                    )
            )

            // Silent animated/motion cover art overlay
            val currentCanvas = canvasArtwork
            if (page == currentIndex && currentCanvas != null && !isTransparentCover) {
                CanvasArtworkPlayer(
                    canvas = currentCanvas,
                    isPlaying = isPlaying,
                    contentMode = CanvasContentMode.CROP,
                    shape = shape,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

// Full Player Cover Art: Source bar + Mimikyu swipe-to-skip Album Art HorizontalPager
@Composable
fun FullPlayerCoverArt(
    coverUrl: String,
    playContext: String?,
    onClose: () -> Unit,
    onMoreClick: () -> Unit = {},
    queue: List<QueueItem> = emptyList(),
    currentIndex: Int = 0,
    onPlayIndex: (Int) -> Unit = {},
    isPlaying: Boolean = true,
    previousCoverUrl: String? = null,
    nextCoverUrl: String? = null,
    onSwipeToPrevious: () -> Unit = {},
    onSwipeToNext: () -> Unit = {},
    currentKey: Any = "",
    previousKey: Any? = null,
    nextKey: Any? = null,
    coverExtraInset: Dp = 0.dp,
    onToggleSidebarFullscreen: (() -> Unit)? = null,
    isSidebarFullscreen: Boolean = false,
    modifier: Modifier = Modifier,
    onCancelSwipe: () -> Boolean = { false },
    onCurrentBitmapLoaded: ((Bitmap) -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        FullPlayerSourceBar(
            playContext = playContext,
            onClose = onClose,
            onMoreClick = onMoreClick,
            onToggleSidebarFullscreen = onToggleSidebarFullscreen,
            isSidebarFullscreen = isSidebarFullscreen,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MimikyuSpacing.lg)
                .padding(bottom = 38.dp)
        )

        FullPlayerCoverPager(
            queue = queue,
            currentIndex = currentIndex,
            fallbackCoverUrl = coverUrl,
            onPlayIndex = onPlayIndex,
            isPlaying = isPlaying,
            contentPadding = 0.dp,
            shape = RoundedCornerShape(RadiusCompact),
            elevation = 24.dp,
            pageSpacing = 24.dp,
            onCurrentBitmapLoaded = onCurrentBitmapLoaded,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .padding(horizontal = MimikyuSpacing.lg + coverExtraInset)
        )
    }
}

// Play：/、Collapse、Play、More
@Composable
fun FullPlayerSourceBar(
    playContext: String?,
    onClose: () -> Unit,
    onMoreClick: () -> Unit,
    onToggleSidebarFullscreen: (() -> Unit)?,
    isSidebarFullscreen: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onToggleSidebarFullscreen != null) {
            MimikyuIconButton(
                onClick = onToggleSidebarFullscreen,
                modifier = Modifier
                    .size(32.dp)
                    .offset(x = (-4).dp)
            ) {
                Icon(
                    if (isSidebarFullscreen) Icons.Filled.ViewSidebar else Icons.Outlined.ViewSidebar,
                    contentDescription = if (isSidebarFullscreen) "Close Sidebar" else "Full Screen",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(SidebarToggleGap))
        }
        MimikyuIconButton(
            onClick = onClose,
            modifier = Modifier
                .size(32.dp)
                .offset(x = (-4).dp)
        ) {
            Icon(
                painter = painterResource(MimikyuIcons.ChevronDown),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(28.dp)
            )
        }
        AnimatedContent(
            targetState = playContext,
            transitionSpec = {
                (fadeIn(animationSpec = tween(260)) +
                        slideInVertically(animationSpec = tween(260)) { height -> height / 3 })
                    .togetherWith(
                        fadeOut(animationSpec = tween(200)) +
                                slideOutVertically(animationSpec = tween(200)) { height -> -height / 3 }
                    ).using(
                        SizeTransform(
                            clip = false,
                            sizeAnimationSpec = { _, _ -> tween(280) }
                        )
                    )
            },
            label = "play_source_transition",
            contentAlignment = Alignment.Center,
            modifier = Modifier.weight(1f)
        ) { context ->
            val (sourceText, detailText) = when (context) {
                null -> "NOW PLAYING" to null
                "Search" -> "Playing from" to "Search"
                "Daily Recommendations" -> "Playing from" to "Daily Recommendations"
                "Daily History" -> "Playing from" to "Daily History"
                "intelligence" -> "Playing from" to "Heartbeat Mode"
                else -> "Playing from playlist" to context
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = sourceText,
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (detailText != null) {
                    Text(
                        text = detailText,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        // ，Play
        if (onToggleSidebarFullscreen != null) {
            Spacer(modifier = Modifier.width(32.dp + SidebarToggleGap))
        }
        MimikyuIconButton(
            onClick = onMoreClick,
            modifier = Modifier
                .size(32.dp)
                .offset(x = 4.dp)
        ) {
            Icon(
                painter = painterResource(MimikyuIcons.More),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
