package com.lin0721.linmusic.core.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import com.lin0721.linmusic.core.download.DownloadPreferences
import com.lin0721.linmusic.core.download.SongDownloadManager
import com.lin0721.linmusic.core.ui.theme.MimikyuPink
import com.lin0721.linmusic.core.ui.theme.RadiusCompact
import com.lin0721.linmusic.core.ui.theme.RobotoFontFamily
import com.lin0721.linmusic.core.ui.theme.DownloadedGreen
import com.lin0721.linmusic.core.ui.theme.LocalMimikyuWindowSizeClass
import com.lin0721.linmusic.core.ui.theme.MimikyuSpacing
import com.lin0721.linmusic.core.ui.theme.MimikyuWindowSizeClass
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Spotify-accurate circular download badge in the app's accent color.
 * Displays a filled circle in accent color with a centered dark downward arrow.
 */
@Composable
fun DownloadedBadge(
    modifier: Modifier = Modifier,
    tint: Color = MimikyuPink
) {
    Icon(
        painter = painterResource(com.lin0721.linmusic.core.ui.theme.MimikyuIcons.DownloadDone),
        contentDescription = "Downloaded",
        tint = tint,
        modifier = modifier.size(13.dp)
    )
}

/**
 * Circular download progress badge filling in clockwise with MimikyuPink accent.
 */
@Composable
fun DownloadingProgressBadge(
    progress: Int,
    modifier: Modifier = Modifier,
    tint: Color = MimikyuPink
) {
    Box(
        modifier = modifier.size(13.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 1.6.dp.toPx()
            drawArc(
                color = Color.White.copy(alpha = 0.25f),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(width = strokeWidth)
            )
            drawArc(
                color = tint,
                startAngle = -90f,
                sweepAngle = 360f * (progress.coerceIn(0, 100) / 100f),
                useCenter = false,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
        }
        Icon(
            imageVector = Icons.Rounded.ArrowDownward,
            contentDescription = "Downloading",
            tint = tint,
            modifier = Modifier.size(7.dp)
        )
    }
}

// Songs of  UI ， of （Track/QueueItem ）
data class SongRowData(
    val id: Long,
    val title: String,
    val artist: String,
    val coverUrl: String?,
    val durationText: String? = null
)

// Songs：Playlist/Artist/Home/Search ，compact ，index
@Composable
fun SongRow(
    data: SongRowData,
    isActive: Boolean = false,
    isPlaying: Boolean = true,
    compact: Boolean = false,
    index: Int? = null,
    onClick: () -> Unit,
    onArtistClick: (() -> Unit)? = null,
    showDownloadBadge: Boolean = true,
    trailingSlot: @Composable RowScope.() -> Unit = {}
) {
    // Expanded //，
    val isExpanded = LocalMimikyuWindowSizeClass.current == MimikyuWindowSizeClass.Expanded
    val coverSize = when {
        compact && isExpanded -> 50.dp
        compact -> 42.dp
        isExpanded -> 56.dp
        else -> 48.dp
    }
    val coverShape: Shape = RoundedCornerShape(RadiusCompact)
    val titleFontSize = when {
        compact && isExpanded -> 15.sp
        compact -> 13.sp
        isExpanded -> 17.sp
        else -> 15.sp
    }
    val artistFontSize = when {
        compact && isExpanded -> 12.sp
        compact -> 11.sp
        isExpanded -> 14.sp
        else -> 13.sp
    }
    val coverSpacing = when {
        compact && isExpanded -> 12.dp
        compact -> 10.dp
        isExpanded -> 14.dp
        else -> 12.dp
    }
    val verticalPadding = when {
        compact && isExpanded -> 13.dp
        compact -> 9.dp
        isExpanded -> 14.dp
        else -> 10.dp
    }

    val downloadPreferences: DownloadPreferences = koinInject()
    val songDownloadManager: SongDownloadManager = koinInject()
    val isDownloaded by produceState(initialValue = false, data.id, showDownloadBadge) {
        if (showDownloadBadge) {
            downloadPreferences.downloadedQualityFor(data.id).collect { quality ->
                value = quality != null
            }
        } else {
            value = false
        }
    }
    val downloadingProgress by produceState<Int?>(initialValue = null, data.id, showDownloadBadge) {
        if (showDownloadBadge) {
            songDownloadManager.observeProgress(data.id).collect { percent ->
                value = percent
            }
        } else {
            value = null
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = MimikyuSpacing.md, vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (index != null) {
            Text(
                text = index.toString(),
                color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp,
                fontFamily = RobotoFontFamily,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(28.dp),
                textAlign = TextAlign.Start
            )
        }

        AlbumArtImage(
            url = data.coverUrl,
            songId = data.id,
            title = data.title,
            artist = data.artist,
            contentDescription = data.title,
            shape = coverShape,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(coverSize)
        )
        Spacer(Modifier.width(coverSpacing))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isActive) {
                    PlayingEqualizerBars(color = MaterialTheme.colorScheme.primary, animate = isPlaying)
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = data.title,
                    color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    fontFamily = RobotoFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = titleFontSize,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }
            Spacer(Modifier.height(MimikyuSpacing.xxs))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (showDownloadBadge) {
                    when {
                        downloadingProgress != null -> {
                            DownloadingProgressBadge(
                                progress = downloadingProgress ?: 0,
                                tint = MimikyuPink,
                                modifier = Modifier.padding(end = 5.dp)
                            )
                        }
                        isDownloaded -> {
                            DownloadedBadge(
                                tint = MimikyuPink,
                                modifier = Modifier.padding(end = 5.dp)
                            )
                        }
                    }
                }
                Text(
                    text = data.artist,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = RobotoFontFamily,
                    fontWeight = FontWeight.Normal,
                    fontSize = artistFontSize,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (onArtistClick != null) Modifier.clickable { onArtistClick() } else Modifier
                )
            }
        }

        if (data.durationText != null) {
            Text(
                text = data.durationText,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = RobotoFontFamily,
                fontWeight = FontWeight.Normal,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = MimikyuSpacing.sm)
            )
        }

        trailingSlot()
    }
}

// Play： of 。animate  false（Pause），Pause of ，；
// Animatable  InfiniteTransition，Pause value ，PlayCurrent
@Composable
fun PlayingEqualizerBars(color: Color, animate: Boolean) {
    val bar1 = remember { Animatable(4f) }
    val bar2 = remember { Animatable(13f) }
    val bar3 = remember { Animatable(8f) }

    LaunchedEffect(animate) {
        if (!animate) return@LaunchedEffect
        coroutineScope {
            launch {
                while (isActive) {
                    bar1.animateTo(14f, tween(600, easing = FastOutSlowInEasing))
                    bar1.animateTo(4f, tween(600, easing = FastOutSlowInEasing))
                }
            }
            launch {
                while (isActive) {
                    bar2.animateTo(6f, tween(750, easing = FastOutSlowInEasing))
                    bar2.animateTo(13f, tween(750, easing = FastOutSlowInEasing))
                }
            }
            launch {
                while (isActive) {
                    bar3.animateTo(15f, tween(500, easing = FastOutSlowInEasing))
                    bar3.animateTo(8f, tween(500, easing = FastOutSlowInEasing))
                }
            }
        }
    }

    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.height(16.dp)
    ) {
        listOf(bar1.value, bar2.value, bar3.value).forEach { barHeight ->
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(barHeight.dp)
                    .background(color, RoundedCornerShape(1.dp))
            )
        }
    }
}

// of Songs，PlayQueue and Playlists；
// enabled=false ，Save of
@Composable
fun DraggableSongRow(
    data: SongRowData,
    isCurrent: Boolean,
    isPlaying: Boolean,
    isPlayed: Boolean,
    isDragging: Boolean,
    dragOffsetY: Float,
    onClick: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    enabled: Boolean = true
) {
    val liftSpec = remember { spring<Dp>(stiffness = Spring.StiffnessMediumLow) }
    val elevation by animateDpAsState(
        targetValue = if (isDragging) 8.dp else 0.dp,
        animationSpec = liftSpec,
        label = "drag_elev"
    )
    val isLifted = isDragging || dragOffsetY != 0f
    val bgColor by animateColorAsState(
        when {
            isDragging -> MaterialTheme.colorScheme.surfaceVariant
            isCurrent -> MaterialTheme.colorScheme.surface
            else -> MaterialTheme.colorScheme.background
        },
        label = "row_bg"
    )

    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isLifted) Modifier
                    .zIndex(1f)
                    .graphicsLayer { translationY = dragOffsetY }
                    .shadow(elevation, RoundedCornerShape(8.dp))
                else Modifier
            )
            .background(bgColor)
            .clickable(onClick = onClick)
            .padding(start = 20.dp, end = MimikyuSpacing.sm, top = MimikyuSpacing.sm, bottom = MimikyuSpacing.sm)
            .then(if (isPlayed) Modifier.alpha(0.5f) else Modifier),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(44.dp)) {
            AlbumArtImage(
                url = data.coverUrl,
                songId = data.id,
                title = data.title,
                artist = data.artist,
                contentDescription = data.title,
                shape = RoundedCornerShape(RadiusCompact),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        if (isCurrent) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(RadiusCompact))
                    .background(Color.Black.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (isPlaying) Icons.Rounded.GraphicEq else Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                data.title,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                fontFamily = RobotoFontFamily,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            val downloadPreferences: DownloadPreferences = koinInject()
            val songDownloadManager: SongDownloadManager = koinInject()
            val isDownloaded by produceState(initialValue = false, data.id) {
                downloadPreferences.downloadedQualityFor(data.id).collect { quality ->
                    value = quality != null
                }
            }
            val downloadingProgress by produceState<Int?>(initialValue = null, data.id) {
                songDownloadManager.observeProgress(data.id).collect { percent ->
                    value = percent
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    downloadingProgress != null -> {
                        DownloadingProgressBadge(
                            progress = downloadingProgress ?: 0,
                            tint = MimikyuPink,
                            modifier = Modifier.padding(end = 5.dp)
                        )
                    }
                    isDownloaded -> {
                        DownloadedBadge(
                            tint = MimikyuPink,
                            modifier = Modifier.padding(end = 5.dp)
                        )
                    }
                }
                Text(
                    data.artist,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    fontFamily = RobotoFontFamily,
                    fontWeight = FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }



        Icon(
            Icons.Default.Menu,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(44.dp)
                .then(
                    if (enabled) {
                        Modifier.pointerInput(Unit) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { currentOnDragStart() },
                                onDrag = { change, offset ->
                                    change.consume()
                                    currentOnDrag(offset.y)
                                },
                                onDragEnd = { currentOnDragEnd() },
                                onDragCancel = { currentOnDragEnd() }
                            )
                        }
                    } else {
                        Modifier
                    }
                )
                .padding(12.dp)
        )
    }
}
