package com.lin0721.linmusic.feature.playlist.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.res.painterResource
import com.lin0721.linmusic.R
import com.lin0721.linmusic.core.ui.theme.OctoberRed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Shuffle
import com.lin0721.linmusic.core.ui.theme.MimikyuIcons
import com.lin0721.linmusic.core.auth.UserProfile
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.draw.shadow
import com.lin0721.linmusic.core.ui.interaction.pressable
import com.lin0721.linmusic.core.ui.theme.MimikyuPress
import com.lin0721.linmusic.core.ui.components.CoverPlaceholder
import com.lin0721.linmusic.core.ui.theme.SurfaceDark
import com.lin0721.linmusic.core.ui.theme.TextGray
import com.lin0721.linmusic.core.ui.theme.darken
import com.lin0721.linmusic.core.ui.theme.extractBaseColorFromUrl
import com.lin0721.linmusic.core.model.PlaylistDetail

// ────────────────────────────────────────────────────────────────────────────
// Mimikyu-Style Immersive Hero Banner (LazyColumn Item 0)
// ────────────────────────────────────────────────────────────────────────────
@Composable
fun PlaylistHeaderItem(
    playlist: PlaylistDetail,
    coverSize: Dp,
    coverAlpha: Float,
    progress: Float,
    statusBarHeight: Dp,
    dominantColor: Color,
    onColorCalculated: (Color) -> Unit,
    onPlayAll: () -> Unit,
    onShuffleToggle: () -> Unit,
    isShuffleActive: Boolean,
    isCurrentlyPlayingThis: Boolean,
    isSubscribed: Boolean,
    onSubscribeClick: () -> Unit,
    onMoreClick: () -> Unit,
    isLikedSongsPlaylistView: Boolean = false,
    isOwnedPlaylist: Boolean = false,
    isAlbum: Boolean = false,
    userProfile: UserProfile? = null,
    onAddMusicClick: () -> Unit = {},
    onEditOrderClick: () -> Unit = {},
    onShareClick: () -> Unit = {},
    onDownloadClick: () -> Unit = {},
    onAddToQueueClick: () -> Unit = {},
    onSearchClick: () -> Unit = {},
    onSortClick: () -> Unit = {},
    onHistoryClick: () -> Unit = {},
    selectedHistoryDate: String = "Today",
    onCreatorClick: (Long) -> Unit = {},
    onPlayButtonPositioned: (Float) -> Unit = {},
    onBack: () -> Unit = {}
) {
    var showDescriptionSheet by remember { mutableStateOf(false) }
    var hasDescOverflow by remember(playlist.description) { mutableStateOf(false) }

    val todayStr = remember {
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
    }
    val displayDateStr = remember(selectedHistoryDate) {
        if (selectedHistoryDate == "Today") {
            todayStr
        } else {
            val parts = selectedHistoryDate.split("-")
            if (parts.size == 3) {
                "${parts[0]}-${parts[1]}-${parts[2]}"
            } else {
                selectedHistoryDate
            }
        }
    }

    val titleAlpha = 1f - ((progress - 0.4f) / 0.4f).coerceIn(0f, 1f)
    val animatedDominant by animateColorAsState(
        targetValue = dominantColor,
        animationSpec = tween(800),
        label = "playlist_header_color"
    )
    val gradientTop = remember(animatedDominant) { animatedDominant.darken(0.35f) }

    // Featured artists text for Mimikyu-style subtitle preview
    val featuredArtistsText = remember(playlist.tracks, playlist.description, playlist.creator) {
        val names = playlist.tracks.take(4)
            .flatMap { it.ar }
            .map { it.name.trim() }
            .filter { it.isNotEmpty() && !it.equals("YouTube Music", ignoreCase = true) }
            .distinct()
        when {
            names.isNotEmpty() -> names.joinToString(", ") + if (playlist.tracks.size > 4) ", and more" else ""
            playlist.description?.isNotBlank() == true &&
                !playlist.description.equals("YouTube Music", ignoreCase = true) &&
                !playlist.description.equals("Curated Playlist", ignoreCase = true) &&
                !playlist.description.equals("Playlist", ignoreCase = true) -> playlist.description
            playlist.creator?.nickname?.isNotBlank() == true &&
                !playlist.creator.nickname.equals("YouTube Music", ignoreCase = true) &&
                !playlist.creator.nickname.equals("Mimikyu", ignoreCase = true) &&
                !playlist.creator.nickname.equals("October", ignoreCase = true) -> playlist.creator.nickname
            else -> ""
        }
    }

    // Calculate total duration
    val totalDurationMs = remember(playlist.tracks) { playlist.tracks.sumOf { it.dt } }
    val durationFormatted = remember(totalDurationMs) {
        if (totalDurationMs > 0) {
            val totalSecs = totalDurationMs / 1000
            val hours = totalSecs / 3600
            val mins = (totalSecs % 3600) / 60
            if (hours > 0) "${hours}h ${mins}min" else if (mins > 0) "${mins} min" else "< 1 min"
        } else ""
    }

    val context = LocalContext.current
    val coverRequest = remember(playlist.coverImgUrl) {
        ImageRequest.Builder(context)
            .data(if (playlist.coverImgUrl.isNotBlank()) "${playlist.coverImgUrl}?param=600y600" else null)
            .allowHardware(false)
            .crossfade(true)
            .build()
    }

    LaunchedEffect(playlist.coverImgUrl) {
        if (playlist.coverImgUrl.isNotEmpty()) {
            onColorCalculated(extractBaseColorFromUrl(context, playlist.coverImgUrl))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    0.0f to gradientTop.copy(alpha = 0.95f),
                    0.40f to gradientTop.copy(alpha = 0.50f),
                    0.80f to MaterialTheme.colorScheme.background,
                    1.0f to MaterialTheme.colorScheme.background
                )
            )
            .padding(top = statusBarHeight)
    ) {
        // ── 1. Top Search Playlist & Sort Row ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(MimikyuIcons.ArrowLeft),
                contentDescription = "Back",
                tint = Color.White,
                modifier = Modifier
                    .size(24.dp)
                    .pressable(MimikyuPress.Icon) { onBack() }
            )

            Spacer(Modifier.width(12.dp))

            // Search pill box
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White.copy(alpha = 0.12f))
                    .pressable(MimikyuPress.Card) { onSearchClick() }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Search playlist",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    painter = painterResource(MimikyuIcons.Search),
                    contentDescription = "Search",
                    tint = Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(Modifier.width(10.dp))

            // Sort button
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .pressable(MimikyuPress.Icon) { onSortClick() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(MimikyuIcons.Sort),
                    contentDescription = "Sort playlist",
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        // ── 2. Centered Square Cover Art ──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp, bottom = 18.dp),
            contentAlignment = Alignment.Center
        ) {
            // Centered square cover card
            Box(
                modifier = Modifier
                    .size(210.dp)
                    .shadow(elevation = 16.dp, shape = RoundedCornerShape(8.dp), spotColor = Color.Black)
                    .clip(RoundedCornerShape(8.dp))
                    .background(SurfaceDark)
            ) {
                SubcomposeAsyncImage(
                    model              = coverRequest,
                    contentDescription = playlist.name,
                    contentScale       = ContentScale.Crop,
                    loading            = { CoverPlaceholder() },
                    error              = { CoverPlaceholder() },
                    modifier           = Modifier.fillMaxSize()
                )

                // White Mimikyu logo badge in top-left
                Image(
                    painter = painterResource(R.drawable.ic_mimikyu_logo),
                    contentDescription = "Mimikyu",
                    modifier = Modifier
                        .padding(10.dp)
                        .size(18.dp)
                        .align(Alignment.TopStart)
                )
            }
        }

        // ── 3. Title & Subtitle Section ──
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            Text(
                text = playlist.name,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp,
                lineHeight = 28.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(8.dp))

            val totalTrackCount = if (playlist.trackIds.isNotEmpty()) playlist.trackIds.size else playlist.tracks.size
            val songText = if (totalTrackCount > 0) "$totalTrackCount songs" else ""

            if (isOwnedPlaylist) {
                // Creator info row: circular avatar + creator username (no + for collaboration)
                val creatorName = remember(playlist.creator, userProfile) {
                    playlist.creator?.nickname?.takeIf { it.isNotBlank() && it != "My Playlist" && it != "YouTube Music" }
                        ?: userProfile?.nickname?.takeIf { it.isNotBlank() }
                        ?: userProfile?.username?.takeIf { it.isNotBlank() }
                        ?: "You"
                }
                val avatarUrl = playlist.creator?.avatarUrl?.takeIf { it.isNotBlank() }
                    ?: userProfile?.avatarUrl?.takeIf { it.isNotBlank() }
                    ?: ""

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (avatarUrl.isNotEmpty()) {
                        AsyncImage(
                            model = avatarUrl,
                            contentDescription = creatorName,
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF444444)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = creatorName.firstOrNull()?.uppercase() ?: "U",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(Modifier.width(8.dp))

                    Text(
                        text = creatorName,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(Modifier.height(6.dp))

                // Globe icon + song count and total duration
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Public,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    val metaText = listOf(songText, durationFormatted)
                        .filter { it.isNotBlank() }
                        .joinToString(" • ")
                    Text(
                        text = metaText,
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else {
                if (featuredArtistsText.isNotBlank()) {
                    Text(
                        text = featuredArtistsText,
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Public,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    val metaText = listOf(songText, durationFormatted)
                        .filter { it.isNotBlank() }
                        .joinToString(" • ")
                    Text(
                        text = metaText,
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // ── 3. Mimikyu Action Bar Row ──
        if (playlist.id != -1L && playlist.id != -2L) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left Actions: Add songs / Library + Reorder + Download + More
                Row(
                    horizontalArrangement = Arrangement.spacedBy(22.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 1. Add songs (if owned/created/imported) or Save/Subscribe (if external)
                    if (isOwnedPlaylist) {
                        Icon(
                            painter = painterResource(MimikyuIcons.PlusCircle),
                            contentDescription = "Add songs to playlist",
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier
                                .size(26.dp)
                                .pressable(MimikyuPress.Icon) { onAddMusicClick() }
                        )

                        // Reorder icon in tray for owned playlist
                        if (playlist.tracks.isNotEmpty()) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.Sort,
                                contentDescription = "Reorder playlist",
                                tint = Color.White.copy(alpha = 0.85f),
                                modifier = Modifier
                                    .size(24.dp)
                                    .pressable(MimikyuPress.Icon) { onEditOrderClick() }
                            )
                        }
                    } else if (!isLikedSongsPlaylistView) {
                        Icon(
                            painter = painterResource(if (isSubscribed) MimikyuIcons.CheckCircle else MimikyuIcons.PlusCircle),
                            contentDescription = if (isSubscribed) "Added to your library" else "Add to library",
                            tint = if (isSubscribed) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.72f),
                            modifier = Modifier
                                .size(26.dp)
                                .pressable(MimikyuPress.Icon) { onSubscribeClick() }
                        )
                    }

                    // 2. Download icon
                    Icon(
                        painter = painterResource(MimikyuIcons.Download),
                        contentDescription = "Download",
                        tint = Color.White.copy(alpha = 0.72f),
                        modifier = Modifier
                            .size(24.dp)
                            .pressable(MimikyuPress.Icon) { onDownloadClick() }
                    )

                    // 3. More options icon (...)
                    Icon(
                        painter = painterResource(MimikyuIcons.More),
                        contentDescription = "More",
                        tint = Color.White.copy(alpha = 0.72f),
                        modifier = Modifier
                            .size(24.dp)
                            .pressable(MimikyuPress.Icon) { onMoreClick() }
                    )
                }

                // Right Actions: Shuffle + Circular Play Button
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 5. Shuffle icon
                    Icon(
                        painter = painterResource(MimikyuIcons.Shuffle),
                        contentDescription = "Shuffle",
                        tint = if (isShuffleActive) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.72f),
                        modifier = Modifier
                            .size(26.dp)
                            .pressable(MimikyuPress.Icon) { onShuffleToggle() }
                    )

                    // 6. Anchor play button positioned for floating button tracking
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .onGloballyPositioned { coordinates ->
                                onPlayButtonPositioned(coordinates.positionInRoot().y)
                            }
                    )
                }
            }
        }
    }

    if (showDescriptionSheet) {
        PlaylistDescriptionSheet(
            playlist = playlist,
            onDismiss = { showDescriptionSheet = false }
        )
    }
}
