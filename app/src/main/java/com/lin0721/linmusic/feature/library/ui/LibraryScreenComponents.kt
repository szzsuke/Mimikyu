package com.lin0721.linmusic.feature.library.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.res.painterResource
import com.lin0721.linmusic.core.ui.theme.MimikyuIcons
import com.lin0721.linmusic.core.ui.components.MimikyuWireframePlaylistCard
import com.lin0721.linmusic.core.ui.components.MimikyuWireframeAlbumCard
import com.lin0721.linmusic.core.ui.components.MimikyuWireframeArtistCard
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.SubcomposeAsyncImage
import com.lin0721.linmusic.core.ui.components.CoverPlaceholder
import com.lin0721.linmusic.core.ui.components.MimikyuButton
import com.lin0721.linmusic.core.ui.interaction.pressable
import com.lin0721.linmusic.core.ui.interaction.pressScale
import com.lin0721.linmusic.core.ui.theme.MimikyuPress
import com.lin0721.linmusic.core.ui.theme.MimikyuSpacing
import com.lin0721.linmusic.core.ui.theme.LibraryVioletGradient
import com.lin0721.linmusic.core.ui.theme.LibraryBlueGreenGradient
import com.lin0721.linmusic.core.ui.theme.DownloadedGreen
import com.lin0721.linmusic.core.ui.theme.MimikyuPink
import com.lin0721.linmusic.core.ui.theme.RadiusCompact
import com.lin0721.linmusic.core.ui.theme.PillRadius
import com.lin0721.linmusic.core.ui.theme.LikedSongsAssets
import com.lin0721.linmusic.R
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext

// ────────────────────────────────────────────────────────────────────────────
// ：（）All；，
// "×"Cancel、All
// ────────────────────────────────────────────────────────────────────────────
@Composable
fun LibraryFilterPillsRow(
    selectedFilter: LibraryFilter?,
    playlistCount: Int,
    albumCount: Int,
    artistCount: Int,
    onSelect: (LibraryFilter) -> Unit,
    onClear: () -> Unit,
    selectedPlaylistOwnerFilter: LibraryPlaylistOwnerFilter? = null,
    onSelectPlaylistOwnerFilter: (LibraryPlaylistOwnerFilter) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val filters = listOf(
        LibraryFilter.PLAYLIST to "Playlists${if (playlistCount > 0) " $playlistCount" else ""}",
        LibraryFilter.ALBUM to "Albums${if (albumCount > 0) " $albumCount" else ""}",
        LibraryFilter.ARTIST to "Artists${if (artistCount > 0) " $artistCount" else ""}"
    )
    val visibleFilters = if (selectedFilter == null) filters else filters.filter { it.first == selectedFilter }
    val ownerFilters = listOf(
        LibraryPlaylistOwnerFilter.MINE to "Created by me",
        LibraryPlaylistOwnerFilter.OTHERS to "Others"
    )

    // of ：
    val placementSpec = spring<androidx.compose.ui.unit.IntOffset>(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = Spring.StiffnessMediumLow
    )
    val fadeInSpec = spring<Float>(stiffness = Spring.StiffnessMedium)
    val fadeOutSpec = spring<Float>(stiffness = Spring.StiffnessMedium)

    LazyRow(
        modifier = modifier.padding(top = MimikyuSpacing.sm, bottom = 10.dp),
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selectedFilter != null) {
            item(key = "clear_filter") {
                Box(
                    modifier = Modifier
                        .animateItem(fadeInSpec, placementSpec, fadeOutSpec)
                        .pressable(MimikyuPress.Pill) { onClear() }
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.1f))
                        .size(36.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(MimikyuIcons.Close),
                        contentDescription = "Clear Filter",
                        tint = Color.LightGray,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
        items(visibleFilters, key = { it.first }) { (filter, label) ->
            val isSelected = filter == selectedFilter
            val bgColor by animateColorAsState(
                targetValue = if (isSelected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.1f),
                animationSpec = tween(220),
                label = "pill_bg"
            )
            val contentColor by animateColorAsState(
                targetValue = if (isSelected) MaterialTheme.colorScheme.onPrimary else Color.LightGray,
                animationSpec = tween(220),
                label = "pill_content"
            )
            Box(
                modifier = Modifier
                    .animateItem(fadeInSpec, placementSpec, fadeOutSpec)
                    .height(36.dp)
                    .pressable(MimikyuPress.Pill) { onSelect(filter) }
                    .clip(RoundedCornerShape(PillRadius))
                    .background(bgColor)
                    .animateContentSize(spring(stiffness = Spring.StiffnessMedium))
                    .padding(horizontal = 18.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    color = contentColor,
                    fontSize = 14.sp
                )
            }
        }
        // Playlists："Playlists"
        if (selectedFilter == LibraryFilter.PLAYLIST) {
            items(ownerFilters, key = { "owner_${it.first}" }) { (ownerFilter, label) ->
                val isSelected = ownerFilter == selectedPlaylistOwnerFilter
                val bgColor by animateColorAsState(
                    targetValue = if (isSelected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.1f),
                    animationSpec = tween(220),
                    label = "owner_pill_bg"
                )
                val contentColor by animateColorAsState(
                    targetValue = if (isSelected) MaterialTheme.colorScheme.onPrimary else Color.LightGray,
                    animationSpec = tween(220),
                    label = "owner_pill_content"
                )
                Box(
                    modifier = Modifier
                        .animateItem(fadeInSpec, placementSpec, fadeOutSpec)
                        .height(36.dp)
                        .pressable(MimikyuPress.Pill) { onSelectPlaylistOwnerFilter(ownerFilter) }
                        .clip(RoundedCornerShape(PillRadius))
                        .background(bgColor)
                        .padding(horizontal = 18.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        color = contentColor,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}

// ────────────────────────────────────────────────────────────────────────────

// ────────────────────────────────────────────────────────────────────────────
@Composable
fun NotLoggedInView(onLoginClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(MimikyuSpacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(MimikyuIcons.Locked),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp)
            )
        }

        Spacer(modifier = Modifier.height(MimikyuSpacing.lg))

        Text(
            text = "Unlock Your Music Library",
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Log in to sync your playlists, followed artists, and albums.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            lineHeight = 18.sp,
            modifier = Modifier.padding(horizontal = MimikyuSpacing.md)
        )

        Spacer(modifier = Modifier.height(MimikyuSpacing.xl))

        MimikyuButton(
            onClick = onLoginClick,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            shape = RoundedCornerShape(10.dp),
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 12.dp)
        ) {
            Text(
                text = "Log In Now",
                color = MaterialTheme.colorScheme.onPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

// of
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryItemRow(
    item: LibraryItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = MimikyuSpacing.md, vertical = MimikyuSpacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // （Artists，Playlists/Albums，Songs）
        if (item.isLikedSongs) {
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .clip(RoundedCornerShape(RadiusCompact))
                    .background(Color(0xFF1F1F20)),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(LikedSongsAssets.COVER_URL)
                        .error(R.drawable.liked_songs_cover)
                        .fallback(R.drawable.liked_songs_cover)
                        .crossfade(true)
                        .build(),
                    contentDescription = "Liked Songs",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        } else if (item.isDownloadedSongs || item.id == "downloaded_songs") {
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .clip(RoundedCornerShape(RadiusCompact))
                    .background(Color(0xFF1F1F20)),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(R.drawable.downloaded_songs_cover)
                        .error(R.drawable.downloaded_songs_cover)
                        .fallback(R.drawable.downloaded_songs_cover)
                        .crossfade(true)
                        .build(),
                    contentDescription = "Downloaded Songs",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        } else if (item.id == "-2") {
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .clip(RoundedCornerShape(RadiusCompact))
                    .background(
                        Brush.linearGradient(
                            colors = LibraryBlueGreenGradient
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.TrendingUp,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(24.dp)
                )
            }
        } else {
            val shape = if (item.type == LibraryItemType.ARTIST) CircleShape else RoundedCornerShape(RadiusCompact)

            SubcomposeAsyncImage(
                model = "${item.coverUrl}?param=150y150",
                contentDescription = item.title,
                modifier = Modifier
                    .size(60.dp)
                    .clip(shape),
                contentScale = ContentScale.Crop,
                loading = { CoverPlaceholder() },
                error = { CoverPlaceholder() }
            )
        }

        Spacer(modifier = Modifier.width(MimikyuSpacing.md))


        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(MimikyuSpacing.xs))

            Row(verticalAlignment = Alignment.CenterVertically) {
                // ， of
                if (item.isPinned) {
                    Icon(
                        painter = painterResource(MimikyuIcons.PinFilled),
                        contentDescription = "Pinned",
                        tint = MimikyuPink,
                        modifier = Modifier
                            .size(13.dp)
                            .padding(end = MimikyuSpacing.xs)
                    )
                }

                Text(
                    text = when (item.type) {
                        LibraryItemType.PLAYLIST -> {
                            if (item.trackCount > 0) "${item.subtitle} • ${item.trackCount} tracks" else item.subtitle
                        }
                        else -> item.subtitle
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryGridItem(
    item: LibraryItem,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {}
) {
    when (item.type) {
        LibraryItemType.ARTIST -> {
            MimikyuWireframeArtistCard(
                name = item.title,
                avatarUrl = item.coverUrl,
                isPinned = item.isPinned,
                onClick = onClick,
                onLongClick = onLongClick,
                modifier = modifier
            )
        }
        LibraryItemType.ALBUM -> {
            MimikyuWireframeAlbumCard(
                title = item.title,
                artistName = item.subtitle,
                coverUrl = item.coverUrl,
                trackCount = item.trackCount.takeIf { it > 0 },
                isPinned = item.isPinned,
                onClick = onClick,
                onLongClick = onLongClick,
                modifier = modifier
            )
        }
        else -> {
            val isLikedSongs = item.isLikedSongs || item.id == "liked_songs" || item.title.equals("Liked Songs", ignoreCase = true)
            val isDownloadedSongs = item.isDownloadedSongs || item.id == "downloaded_songs" || item.title.equals("Downloaded Songs", ignoreCase = true)
            val isDailyMix = item.title.startsWith("Daily Mix", ignoreCase = true)
            val dailyMixNum = Regex("""\d+""").find(item.title)?.value?.toIntOrNull() ?: 1
            val isGenreMix = item.title.endsWith("Mix", ignoreCase = true)
            val isFolder = item.subtitle.contains("folder", ignoreCase = true) || item.title.contains("folder", ignoreCase = true)

            val trackCount = item.trackCount.takeIf { it > 0 } ?: when {
                isDailyMix -> if (dailyMixNum == 2) 32 else 50
                isGenreMix -> if (item.title.contains("Chill", ignoreCase = true)) 32 else 50
                isLikedSongs -> 12
                else -> null
            }

            MimikyuWireframePlaylistCard(
                title = item.title,
                subtitle = item.subtitle,
                trackCount = trackCount,
                coverUrl = item.coverUrl,
                isLikedSongs = isLikedSongs,
                isDownloadedSongs = isDownloadedSongs,
                isDailyMix = isDailyMix,
                dailyMixNumber = dailyMixNum,
                isGenreMix = isGenreMix,
                isFolder = isFolder,
                isPinned = item.isPinned,
                onClick = onClick,
                onLongClick = onLongClick,
                modifier = modifier
            )
        }
    }
}

// of Playlists
@Composable
fun DraggablePlaylistRow(
    item: LibraryItem,
    isDragging: Boolean,
    dragOffsetY: Float,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    enabled: Boolean = true
) {
    val liftSpec = remember { spring<Dp>(stiffness = Spring.StiffnessMediumLow) }
    val elevation by animateDpAsState(
        targetValue = if (isDragging) 8.dp else 0.dp,
        animationSpec = liftSpec,
        label = "drag_playlist_elev"
    )
    val isLifted = isDragging || dragOffsetY != 0f
    val bgColor by animateColorAsState(
        targetValue = if (isDragging) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.background,
        label = "playlist_row_bg"
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
            .padding(horizontal = MimikyuSpacing.md, vertical = MimikyuSpacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SubcomposeAsyncImage(
            model = "${item.coverUrl}?param=150y150",
            contentDescription = item.title,
            modifier = Modifier
                .size(60.dp)
                .clip(RoundedCornerShape(RadiusCompact)),
            contentScale = ContentScale.Crop,
            loading = { CoverPlaceholder() },
            error = { CoverPlaceholder() }
        )

        Spacer(modifier = Modifier.width(MimikyuSpacing.md))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(MimikyuSpacing.xs))

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (item.isPinned) {
                    Icon(
                        painter = painterResource(MimikyuIcons.PinFilled),
                        contentDescription = "Pinned",
                        tint = MimikyuPink,
                        modifier = Modifier
                            .size(13.dp)
                            .padding(end = MimikyuSpacing.xs)
                    )
                }
                Text(
                    text = if (item.trackCount > 0) "${item.subtitle} • ${item.trackCount} tracks" else item.subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Icon(
            painter = painterResource(MimikyuIcons.ListView),
            contentDescription = "Drag to reorder",
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
                .padding(10.dp)
        )
    }
}

