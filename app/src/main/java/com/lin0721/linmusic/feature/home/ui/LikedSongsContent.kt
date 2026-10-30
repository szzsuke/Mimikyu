package com.lin0721.linmusic.feature.home.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.lin0721.linmusic.LocalBottomOverlayInset
import com.lin0721.linmusic.R
import com.lin0721.linmusic.core.auth.UserPreferences
import com.lin0721.linmusic.core.download.DownloadPreferences
import com.lin0721.linmusic.core.download.DownloadTrackInfo
import com.lin0721.linmusic.core.download.SongDownloadManager
import com.lin0721.linmusic.core.download.ui.DownloadQualityPickerSheet
import com.lin0721.linmusic.core.songlike.LikedSong
import com.lin0721.linmusic.core.ui.components.AlbumArtImage
import com.lin0721.linmusic.core.ui.components.ToastManager
import com.lin0721.linmusic.core.ui.interaction.pressable
import com.lin0721.linmusic.core.ui.theme.LikedSongsAssets
import com.lin0721.linmusic.core.ui.theme.MimikyuCharcoal
import com.lin0721.linmusic.core.ui.theme.MimikyuIcons
import com.lin0721.linmusic.core.ui.theme.MimikyuPress
import com.lin0721.linmusic.core.ui.theme.MimikyuSilver
import com.lin0721.linmusic.core.ui.theme.MimikyuSpacing
import com.lin0721.linmusic.core.ui.theme.MimikyuYellow
import com.lin0721.linmusic.core.ui.theme.RobotoFontFamily
import com.lin0721.linmusic.core.ui.theme.TextGray
import com.lin0721.linmusic.core.ui.theme.mimikyuStatusBarTopPadding
import com.lin0721.linmusic.feature.playlist.ui.SearchBarItem
import com.lin0721.linmusic.feature.playlist.ui.rememberSearchBarRevealState
import org.koin.compose.koinInject
import java.text.Collator
import java.util.Locale

private val EdgePadding = 20.dp
private val defaultCollator: Collator = Collator.getInstance(Locale.getDefault())

enum class LikedSongsSortOption(val label: String) {
    RECENT_ADDED("Recently added"),
    TITLE("Title"),
    MOST_PLAYED("Most played"),
    OLDEST_FIRST("Oldest first")
}

private fun formatTotalDuration(totalSeconds: Long): String {
    if (totalSeconds <= 0L) return ""
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> if (minutes > 0) "$hours hr $minutes min" else "$hours hr"
        minutes > 0 -> if (seconds > 0) "$minutes min $seconds sec" else "$minutes min"
        else -> "$seconds sec"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LikedSongsSortSheet(
    sortOption: LikedSongsSortOption,
    onSortOptionChange: (LikedSongsSortOption) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = {
            BottomSheetDefaults.DragHandle(
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = MimikyuSpacing.md)
                .padding(bottom = MimikyuSpacing.lg)
        ) {
            Text(
                text = "Sort by",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = MimikyuSpacing.md)
            )

            LikedSongsSortOption.entries.forEach { option ->
                val isSelected = option == sortOption
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onSortOptionChange(option) }
                        .padding(vertical = 14.dp, horizontal = MimikyuSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = option.label,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Selected",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Spotify-Style Liked Songs Screen Content.
 * - Ambient halftone stars background (~260dp) smoothly fading into #121213
 * - No playlist cover (kept only in Library screen)
 * - Header with Text ("Liked Songs", count + total duration), Spotify-style Download All, Shuffle, Play All
 * - Custom sorting options: Recently added, Title, Most played, Oldest first
 * - Reorder button with playlist reorder icon and move functions
 * - Pinned translucent search bar with back button when opened from Library
 */
@Composable
fun LikedSongsContent(
    likedSongs: List<LikedSong>,
    uid: String?,
    onPlaySongAt: (Int) -> Unit,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    onToggleLike: (LikedSong) -> Unit,
    onSignInClick: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val songDownloadManager: SongDownloadManager = koinInject()
    val userPreferences: UserPreferences = koinInject()
    val recentPlays by userPreferences.recentRecords.collectAsStateWithLifecycle(initialValue = emptyList())
    val playCountMap = remember(recentPlays) {
        recentPlays.associate { it.track.name.lowercase().trim() to it.playCount }
    }

    // Valid songs filtered and deduplicated
    val validSongs = remember(likedSongs) {
        likedSongs.filter { it.videoId.isNotBlank() && it.title.isNotBlank() }.distinctBy { it.videoId }
    }

    var searchQuery by remember { mutableStateOf("") }
    var sortOption by remember { mutableStateOf(LikedSongsSortOption.RECENT_ADDED) }
    var showSortSheet by remember { mutableStateOf(false) }
    var showDownloadQualitySheet by remember { mutableStateOf(false) }
    var isReorderMode by remember { mutableStateOf(false) }

    val reorderedSongs = remember(validSongs) {
        mutableStateListOf<LikedSong>().apply { addAll(validSongs) }
    }
    LaunchedEffect(validSongs) {
        if (!isReorderMode) {
            reorderedSongs.clear()
            reorderedSongs.addAll(validSongs)
        }
    }

    // Sorted songs
    val sortedSongs = remember(validSongs, sortOption, playCountMap, isReorderMode, reorderedSongs.toList()) {
        if (isReorderMode) {
            reorderedSongs.toList()
        } else {
            when (sortOption) {
                LikedSongsSortOption.RECENT_ADDED -> validSongs
                LikedSongsSortOption.OLDEST_FIRST -> validSongs.asReversed()
                LikedSongsSortOption.TITLE -> validSongs.sortedWith(compareBy(defaultCollator) { it.title })
                LikedSongsSortOption.MOST_PLAYED -> validSongs.sortedByDescending {
                    playCountMap[it.title.lowercase().trim()] ?: 0
                }
            }
        }
    }

    // Filtered by search query
    val displaySongs = remember(sortedSongs, searchQuery) {
        if (searchQuery.isBlank()) sortedSongs
        else sortedSongs.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
            it.artist.contains(searchQuery, ignoreCase = true) ||
            it.album?.contains(searchQuery, ignoreCase = true) == true
        }
    }

    val listState = rememberLazyListState()
    val searchBarRevealState = rememberSearchBarRevealState(
        listState = listState,
        isDailyRecommend = false
    )
    val searchFullyRevealed = searchBarRevealState.isOpen

    BackHandler(enabled = searchBarRevealState.isOpen) {
        searchBarRevealState.close()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF121213))
    ) {
        // ── 1. Ambient Halftone Stars Background (~260dp) fading into solid #121213 ──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(LikedSongsAssets.BG_URL)
                    .error(R.drawable.liked_songs_bg)
                    .fallback(R.drawable.liked_songs_bg)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )

            // Vertical gradient blending into base #121213
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.0f to Color.Transparent,
                            0.35f to Color(0x55121213),
                            0.70f to Color(0xCC121213),
                            1.0f to Color(0xFF121213)
                        )
                    )
            )
        }

        // ── 1b. Pinned Translucent Search Bar with Back Button (When opened from Library) ──
        if (onBack != null) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 6.dp)
                    .zIndex(10f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Back button on the side
                Icon(
                    painter = painterResource(MimikyuIcons.ArrowLeft),
                    contentDescription = "Back",
                    tint = MimikyuSilver,
                    modifier = Modifier
                        .size(26.dp)
                        .pressable(MimikyuPress.Icon) { onBack() }
                )

                Spacer(Modifier.width(10.dp))

                // Translucent search bar matching playlist search bar
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp)
                        .clip(RoundedCornerShape(19.dp))
                        .background(Color.White.copy(alpha = 0.12f))
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(MimikyuIcons.Search),
                        contentDescription = null,
                        tint = MimikyuSilver,
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        singleLine = true,
                        textStyle = TextStyle(
                            color = Color.White,
                            fontSize = 13.5.sp,
                            fontFamily = RobotoFontFamily
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        decorationBox = { innerTextField ->
                            if (searchQuery.isEmpty()) {
                                Text(
                                    text = "Find in liked songs",
                                    color = MimikyuSilver.copy(alpha = 0.60f),
                                    fontSize = 13.5.sp,
                                    fontFamily = RobotoFontFamily
                                )
                            }
                            innerTextField()
                        },
                        modifier = Modifier.weight(1f)
                    )
                    if (searchQuery.isNotEmpty()) {
                        Icon(
                            painter = painterResource(MimikyuIcons.Close),
                            contentDescription = "Clear",
                            tint = MimikyuSilver,
                            modifier = Modifier
                                .size(17.dp)
                                .pressable(MimikyuPress.Icon) { searchQuery = "" }
                        )
                    }
                }
            }
        }

        // ── 2. Scrollable Content (List translated with searchBarRevealState) ──
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = LocalBottomOverlayInset.current + 24.dp),
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (onBack == null) {
                        Modifier
                            .graphicsLayer { translationY = searchBarRevealState.revealPx }
                            .nestedScroll(searchBarRevealState.connection)
                    } else {
                        Modifier
                    }
                )
        ) {
            // Spotify-Style Header (Text + Action Row, NO playlist cover)
            item(key = "liked_header") {
                SpotifyLikedSongsHeader(
                    trackCount = validSongs.size,
                    totalDurationSec = validSongs.sumOf { it.durationSec },
                    currentSortOption = sortOption,
                    isReorderMode = isReorderMode,
                    enabled = validSongs.isNotEmpty(),
                    hasBackButton = onBack != null,
                    onPlay = onPlayAll,
                    onShuffle = onShuffle,
                    onDownloadAll = {
                        if (validSongs.isNotEmpty()) {
                            showDownloadQualitySheet = true
                        } else {
                            ToastManager.showToast("No songs to download")
                        }
                    },
                    onSortClick = { showSortSheet = true },
                    onReorderClick = {
                        isReorderMode = !isReorderMode
                        if (isReorderMode) {
                            ToastManager.showToast("Reorder mode active")
                        }
                    },
                    onSearchClick = { searchBarRevealState.open() }
                )
            }

            // The song listing begins here: right at the foot of the ambient image (~260dp)
            when {
                displaySongs.isNotEmpty() -> {
                    item(key = "section_title") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = EdgePadding, end = EdgePadding, top = 8.dp, bottom = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Bottom
                        ) {
                            Text(
                                text = if (searchQuery.isNotBlank()) "Search Results" else "Your Collection",
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 16.sp,
                                fontFamily = RobotoFontFamily,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${displaySongs.size} songs",
                                color = TextGray,
                                fontSize = 12.sp,
                                fontFamily = RobotoFontFamily,
                                fontWeight = FontWeight.Normal
                            )
                        }
                    }

                    itemsIndexed(
                        items = displaySongs,
                        key = { index, song -> "${song.videoId}_$index" }
                    ) { index, song ->
                        LikedSongRow(
                            index = index,
                            song = song,
                            isReorderMode = isReorderMode,
                            onPlay = {
                                val originalIndex = validSongs.indexOfFirst { it.videoId == song.videoId }
                                onPlaySongAt(if (originalIndex >= 0) originalIndex else index)
                            },
                            onToggleLike = { onToggleLike(song) },
                            onMoveUp = if (isReorderMode && index > 0) {
                                {
                                    val item = reorderedSongs.removeAt(index)
                                    reorderedSongs.add(index - 1, item)
                                }
                            } else null,
                            onMoveDown = if (isReorderMode && index < displaySongs.lastIndex) {
                                {
                                    val item = reorderedSongs.removeAt(index)
                                    reorderedSongs.add(index + 1, item)
                                }
                            } else null
                        )
                    }
                }
                searchQuery.isNotBlank() -> {
                    item(key = "no_search_results") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No songs matching \"$searchQuery\"",
                                color = TextGray,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
                uid == null -> {
                    item(key = "signed_out_state") {
                        LikedSongsSignedOutState(onSignInClick = onSignInClick)
                    }
                }
                else -> {
                    item(key = "empty_state") {
                        LikedSongsEmptyState()
                    }
                }
            }
        }

        // ── 3. Slide-up / Pull-down Search Bar Item (Home Tab only) ──
        if (onBack == null) {
            SearchBarItem(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                topPadding = 75.dp,
                backgroundColor = Color(0xFF1E1E20),
                isOpen = searchFullyRevealed,
                onClose = { searchBarRevealState.close() },
                modifier = Modifier
                    .onSizeChanged { searchBarRevealState.searchBarHeightPx = it.height.toFloat() }
                    .graphicsLayer {
                        translationY = searchBarRevealState.revealPx - searchBarRevealState.searchBarHeightPx
                    }
            )
        }

        // ── 4. Sort Options Sheet ──
        if (showSortSheet) {
            LikedSongsSortSheet(
                sortOption = sortOption,
                onSortOptionChange = {
                    sortOption = it
                    showSortSheet = false
                },
                onDismiss = { showSortSheet = false }
            )
        }

        // ── 5. Download Quality Picker Sheet for Download All ──
        if (showDownloadQualitySheet) {
            DownloadQualityPickerSheet(
                onQualitySelected = { level ->
                    showDownloadQualitySheet = false
                    val downloadTracks = validSongs.map { song ->
                        val track = song.toTrack()
                        DownloadTrackInfo(
                            songId = track.id,
                            songName = song.title,
                            artistName = song.artist,
                            albumName = song.album.orEmpty(),
                            coverUrl = song.thumbnailUrl.takeIf { it.isNotBlank() },
                            albumYear = 0,
                            videoId = song.videoId
                        )
                    }
                    songDownloadManager.enqueueBatch(
                        downloadTracks,
                        level,
                        batchTag = "playlist_liked_songs",
                        batchLabel = "Liked Songs"
                    )
                    ToastManager.showToast("Added ${downloadTracks.size} songs to download queue")
                },
                onDismiss = { showDownloadQualitySheet = false }
            )
        }
    }
}

/**
 * Spotify-Style Liked Songs Header:
 * - Large title and track count
 * - Action row with Download All (circled downward arrow), Sort pill, Reorder button, Shuffle, and Play All
 */
@Composable
private fun SpotifyLikedSongsHeader(
    trackCount: Int,
    totalDurationSec: Long,
    currentSortOption: LikedSongsSortOption,
    isReorderMode: Boolean,
    enabled: Boolean,
    hasBackButton: Boolean,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onDownloadAll: () -> Unit,
    onSortClick: () -> Unit,
    onReorderClick: () -> Unit,
    onSearchClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = EdgePadding)
            .padding(
                top = if (hasBackButton) 54.dp + mimikyuStatusBarTopPadding() else 87.dp,
                bottom = 12.dp
            )
    ) {
        // Title: Spotify-style prominent bold heading
        Text(
            text = "Liked Songs",
            color = Color.White,
            fontSize = 28.sp,
            fontFamily = RobotoFontFamily,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        // Subtitle: trackCount songs · totalDuration
        val durationFormatted = formatTotalDuration(totalDurationSec)
        val countText = if (trackCount == 1) "1 song" else "$trackCount songs"
        val subtitle = if (trackCount > 0 && durationFormatted.isNotBlank()) {
            "$countText · $durationFormatted"
        } else {
            countText
        }
        Text(
            text = subtitle,
            color = MimikyuSilver.copy(alpha = 0.75f),
            fontSize = 13.sp,
            fontFamily = RobotoFontFamily,
            fontWeight = FontWeight.Normal,
            modifier = Modifier.padding(top = 4.dp, bottom = 14.dp)
        )

        // Spotify-style Action Row: Left (Download, Sort, Reorder, [Search if !hasBackButton]) | Right (Shuffle, Play)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left Action Controls
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Spotify Download All Button: Circle with downward arrow
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .pressable(MimikyuPress.Icon) { onDownloadAll() }
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.10f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(MimikyuIcons.Download),
                        contentDescription = "Download All",
                        tint = MimikyuSilver,
                        modifier = Modifier.size(17.dp)
                    )
                }

                // Sort Button: Circular icon button matching rest of app
                val isSorted = currentSortOption != LikedSongsSortOption.RECENT_ADDED
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .pressable(MimikyuPress.Icon) { onSortClick() }
                        .clip(CircleShape)
                        .background(
                            if (isSorted) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                            else Color.White.copy(alpha = 0.10f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(MimikyuIcons.Sort),
                        contentDescription = "Sort",
                        tint = if (isSorted) MaterialTheme.colorScheme.primary else MimikyuSilver,
                        modifier = Modifier.size(17.dp)
                    )
                }

                // Reorder Button: using playlist reorder icon Icons.AutoMirrored.Rounded.Sort
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .pressable(MimikyuPress.Icon) { onReorderClick() }
                        .clip(CircleShape)
                        .background(
                            if (isReorderMode) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                            else Color.White.copy(alpha = 0.10f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.Sort,
                        contentDescription = "Reorder",
                        tint = if (isReorderMode) MaterialTheme.colorScheme.primary else MimikyuSilver,
                        modifier = Modifier.size(19.dp)
                    )
                }

                // Search trigger button: ONLY shown if !hasBackButton (library mode has pinned search bar at top)
                if (!hasBackButton) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .pressable(MimikyuPress.Icon) { onSearchClick() }
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.10f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(MimikyuIcons.Search),
                            contentDescription = "Search",
                            tint = MimikyuSilver,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }
            }

            // Right Action Controls: Shuffle + Play All
            if (enabled) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Shuffle Button
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .pressable(MimikyuPress.Icon) { onShuffle() }
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.08f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(MimikyuIcons.Shuffle),
                            contentDescription = "Shuffle",
                            tint = MimikyuSilver,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Large Circular Play All Button (Quartz / MimikyuPink)
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .pressable(MimikyuPress.Icon) { onPlay() }
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(MimikyuIcons.Play),
                            contentDescription = "Play All",
                            tint = Color.Black,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LikedSongRow(
    index: Int,
    song: LikedSong,
    isReorderMode: Boolean,
    onPlay: () -> Unit,
    onToggleLike: () -> Unit,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPlay() }
            .padding(horizontal = EdgePadding, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Track number index
        Text(
            text = "${index + 1}",
            color = TextGray,
            fontSize = 12.sp,
            modifier = Modifier.width(22.dp)
        )

        // Album cover
        val songId = remember(song.videoId) {
            com.lin0721.linmusic.core.player.innertube.YouTubeIdRegistry.register(
                videoId = song.videoId,
                title = song.title,
                artist = song.artist,
                coverUrl = song.thumbnailUrl
            )
        }
        AlbumArtImage(
            url = song.thumbnailUrl,
            songId = songId,
            title = song.title,
            artist = song.artist,
            contentDescription = song.title,
            shape = RoundedCornerShape(8.dp),
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(42.dp)
        )

        // Title and artist
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
        ) {
            Text(
                text = song.title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                fontFamily = RobotoFontFamily,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val artistText = song.artist.ifBlank { song.album.orEmpty() }
            val downloadPreferences: DownloadPreferences = koinInject()
            val isDownloaded by produceState(initialValue = false, songId) {
                downloadPreferences.downloadedQualityFor(songId).collect { quality ->
                    value = quality != null
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 2.dp)
            ) {
                if (isDownloaded) {
                    com.lin0721.linmusic.core.ui.components.DownloadedBadge(
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 5.dp)
                    )
                }
                Text(
                    text = artistText,
                    color = TextGray,
                    fontSize = 12.sp,
                    fontFamily = RobotoFontFamily,
                    fontWeight = FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (isReorderMode) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (onMoveUp != null) {
                    IconButton(
                        onClick = onMoveUp,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            painter = painterResource(MimikyuIcons.ChevronUp),
                            contentDescription = "Move Up",
                            tint = MimikyuSilver,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                if (onMoveDown != null) {
                    IconButton(
                        onClick = onMoveDown,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            painter = painterResource(MimikyuIcons.ChevronDown),
                            contentDescription = "Move Down",
                            tint = MimikyuSilver,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.Sort,
                    contentDescription = "Reorder handle",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(28.dp)
                        .padding(4.dp)
                )
            }
        } else {
            // Heart like toggle button
            IconButton(
                onClick = onToggleLike,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    painter = painterResource(MimikyuIcons.HeartFilled),
                    contentDescription = "Unlike",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun LikedSongsEmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(68.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.06f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(MimikyuIcons.Heart),
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.4f),
                modifier = Modifier.size(34.dp)
            )
        }

        Spacer(modifier = Modifier.height(18.dp))

        Text(
            text = "No Liked Songs Yet",
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "Tap the heart icon on any song in the home feed or player to add it to your Liked Songs collection.",
            color = TextGray,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun LikedSongsSignedOutState(onSignInClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(68.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.06f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(MimikyuIcons.Locked),
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.size(32.dp)
            )
        }

        Spacer(modifier = Modifier.height(18.dp))

        Text(
            text = "Sign in to see your Liked Songs",
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "Log in with your Google or Mimikyu account to sync and access your favorite tracks anywhere.",
            color = TextGray,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(20.dp))

        Button(
            onClick = onSignInClick,
            colors = ButtonDefaults.buttonColors(
                containerColor = MimikyuYellow,
                contentColor = MimikyuCharcoal
            ),
            shape = RoundedCornerShape(20.dp),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp)
        ) {
            Text(
                text = "Sign In",
                fontWeight = FontWeight.Bold,
                color = MimikyuCharcoal,
                fontSize = 14.sp
            )
        }
    }
}
