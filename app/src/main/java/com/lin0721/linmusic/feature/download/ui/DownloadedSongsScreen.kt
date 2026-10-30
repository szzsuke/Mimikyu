package com.lin0721.linmusic.feature.download.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.lin0721.linmusic.LocalBottomOverlayInset
import com.lin0721.linmusic.R
import com.lin0721.linmusic.core.download.DownloadPreferences
import com.lin0721.linmusic.core.download.DownloadRecord
import com.lin0721.linmusic.core.localmusic.LocalCoverArtCache
import com.lin0721.linmusic.core.player.PlayerManager
import com.lin0721.linmusic.core.player.QueueItem
import com.lin0721.linmusic.core.ui.components.ToastManager
import com.lin0721.linmusic.core.ui.interaction.pressable
import com.lin0721.linmusic.core.ui.theme.*
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.text.Collator
import java.util.Locale

private val EdgePadding = 20.dp
private val defaultCollator: Collator = Collator.getInstance(Locale.getDefault())

enum class DownloadedSortOption(val label: String) {
    RECENT_DOWNLOADED("Recently downloaded"),
    TITLE("Title"),
    ARTIST("Artist"),
    FILE_SIZE("File size")
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return ""
    val mb = bytes.toDouble() / (1024 * 1024)
    return "%.1f MB".format(mb)
}

@Composable
fun DownloadedSongsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val downloadPreferences: DownloadPreferences = koinInject()
    val playerManager: PlayerManager = koinInject()
    val localCoverArtCache: LocalCoverArtCache = koinInject()
    val scope = rememberCoroutineScope()

    val records by downloadPreferences.records.collectAsStateWithLifecycle(initialValue = emptyList())

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        DownloadedSongsContent(
            records = records,
            onBack = onBack,
            onPlaySongAt = { playList, index ->
                val items = playList.map { rec ->
                    QueueItem(
                        songId = rec.songId,
                        title = rec.songName,
                        artist = rec.artistName,
                        coverUrl = rec.coverUrl,
                        localUri = rec.mediaStoreUri
                    )
                }
                playerManager.playQueue(items, index.coerceIn(0, items.lastIndex))
            },
            onPlayAll = { playList, shuffle ->
                if (playList.isEmpty()) return@DownloadedSongsContent
                val items = playList.map { rec ->
                    QueueItem(
                        songId = rec.songId,
                        title = rec.songName,
                        artist = rec.artistName,
                        coverUrl = rec.coverUrl,
                        localUri = rec.mediaStoreUri
                    )
                }
                val finalItems = if (shuffle) items.shuffled() else items
                playerManager.playQueue(finalItems, 0)
            },
            onPlayNext = { rec ->
                val item = QueueItem(
                    songId = rec.songId,
                    title = rec.songName,
                    artist = rec.artistName,
                    coverUrl = rec.coverUrl,
                    localUri = rec.mediaStoreUri
                )
                playerManager.addToPlayNext(listOf(item))
                ToastManager.showToast("Added to play next")
            },
            onDeleteRecord = { rec ->
                scope.launch {
                    downloadPreferences.removeRecord(rec.songId)
                    ToastManager.showToast("Removed from downloads")
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadedSongsContent(
    records: List<DownloadRecord>,
    onBack: () -> Unit,
    onPlaySongAt: (List<DownloadRecord>, Int) -> Unit,
    onPlayAll: (List<DownloadRecord>, Boolean) -> Unit,
    onPlayNext: (DownloadRecord) -> Unit,
    onDeleteRecord: (DownloadRecord) -> Unit,
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    var sortOption by remember { mutableStateOf(DownloadedSortOption.RECENT_DOWNLOADED) }
    var showSortSheet by remember { mutableStateOf(false) }
    var selectedRecordForOptions by remember { mutableStateOf<DownloadRecord?>(null) }
    var recordToDelete by remember { mutableStateOf<DownloadRecord?>(null) }

    val listState = rememberLazyListState()

    BackHandler { onBack() }

    val displayRecords = remember(records, searchQuery, sortOption) {
        val filtered = if (searchQuery.isBlank()) {
            records
        } else {
            val q = searchQuery.trim().lowercase()
            records.filter { it.songName.lowercase().contains(q) || it.artistName.lowercase().contains(q) }
        }
        when (sortOption) {
            DownloadedSortOption.RECENT_DOWNLOADED -> filtered.sortedByDescending { it.downloadedAt }
            DownloadedSortOption.TITLE -> filtered.sortedWith { a, b -> defaultCollator.compare(a.songName, b.songName) }
            DownloadedSortOption.ARTIST -> filtered.sortedWith { a, b -> defaultCollator.compare(a.artistName, b.artistName) }
            DownloadedSortOption.FILE_SIZE -> filtered.sortedByDescending { it.fileSize }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // ── 1. Top Ambient Banner (Pixel Galaxy) fading into background ──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(270.dp)
        ) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(R.drawable.downloaded_songs_bg)
                    .error(R.drawable.downloaded_songs_bg)
                    .fallback(R.drawable.downloaded_songs_bg)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
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

        // ── 2. Top Bar: Back Button + Pinned Search Bar ──
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 14.dp, vertical = 6.dp)
                .zIndex(10f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(MimikyuIcons.ArrowLeft),
                contentDescription = "Back",
                tint = MimikyuSilver,
                modifier = Modifier
                    .size(26.dp)
                    .pressable(MimikyuPress.Icon) { onBack() }
            )

            Spacer(Modifier.width(10.dp))

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
                                text = "Find in downloaded songs",
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

        // ── 3. Scrollable List ──
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = LocalBottomOverlayInset.current + 24.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            // Header
            item(key = "downloaded_header") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = EdgePadding)
                        .padding(top = 54.dp + mimikyuStatusBarTopPadding(), bottom = 12.dp)
                ) {
                    Text(
                        text = "Downloaded Songs",
                        color = Color.White,
                        fontSize = 28.sp,
                        fontFamily = RobotoFontFamily,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    val totalBytes = records.sumOf { it.fileSize }
                    val countText = if (records.size == 1) "1 song" else "${records.size} songs"
                    val sizeText = formatBytes(totalBytes)
                    val subtitle = if (sizeText.isNotBlank()) "$countText · $sizeText" else countText

                    Text(
                        text = subtitle,
                        color = MimikyuSilver.copy(alpha = 0.75f),
                        fontSize = 13.sp,
                        fontFamily = RobotoFontFamily,
                        fontWeight = FontWeight.Normal,
                        modifier = Modifier.padding(top = 4.dp, bottom = 14.dp)
                    )

                    // Spotify-style Action Row: Left (Sort) | Right (Shuffle, Play)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Sort Button
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color.White.copy(alpha = 0.08f))
                                .clickable { showSortSheet = true }
                                .padding(horizontal = 12.dp, vertical = 7.dp)
                        ) {
                            Icon(
                                painter = painterResource(MimikyuIcons.Sort),
                                contentDescription = "Sort",
                                tint = MimikyuSilver,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = sortOption.label,
                                color = MimikyuSilver,
                                fontSize = 12.sp,
                                fontFamily = RobotoFontFamily
                            )
                        }

                        // Right Action Controls: Shuffle + Play All
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .pressable(MimikyuPress.Icon) { onPlayAll(displayRecords, true) },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painter = painterResource(MimikyuIcons.Shuffle),
                                    contentDescription = "Shuffle",
                                    tint = MimikyuSilver,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .pressable(MimikyuPress.Icon) { onPlayAll(displayRecords, false) }
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

            // Song list items
            when {
                displayRecords.isNotEmpty() -> {
                    itemsIndexed(
                        items = displayRecords,
                        key = { index, rec -> "${rec.songId}_$index" }
                    ) { index, rec ->
                        DownloadedSongRow(
                            index = index,
                            record = rec,
                            onPlay = { onPlaySongAt(displayRecords, index) },
                            onOptionsClick = { selectedRecordForOptions = rec }
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
                                text = "No downloaded songs matching \"$searchQuery\"",
                                color = TextGray,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
                else -> {
                    item(key = "empty_downloads") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 64.dp, horizontal = 32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    painter = painterResource(MimikyuIcons.Download),
                                    contentDescription = null,
                                    tint = MimikyuSilver.copy(alpha = 0.4f),
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(Modifier.height(16.dp))
                                Text(
                                    text = "No Downloaded Songs",
                                    color = Color.White,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = "Download any song or playlist in the app to listen offline anywhere.",
                                    color = TextGray,
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── 4. Sort Options Sheet ──
        if (showSortSheet) {
            ModalBottomSheet(
                onDismissRequest = { showSortSheet = false },
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

                    DownloadedSortOption.entries.forEach { option ->
                        val isSelected = option == sortOption
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    sortOption = option
                                    showSortSheet = false
                                }
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

        // ── 5. Song Options Bottom Sheet ──
        selectedRecordForOptions?.let { rec ->
            ModalBottomSheet(
                onDismissRequest = { selectedRecordForOptions = null },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = MimikyuSpacing.md)
                        .padding(bottom = MimikyuSpacing.lg)
                ) {
                    Text(
                        text = rec.songName,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = rec.artistName,
                        fontSize = 13.sp,
                        color = TextGray,
                        modifier = Modifier.padding(bottom = MimikyuSpacing.md)
                    )

                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

                    // Play Next
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onPlayNext(rec)
                                selectedRecordForOptions = null
                            }
                            .padding(vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Play next",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 15.sp
                        )
                    }

                    // Delete from downloads
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                recordToDelete = rec
                                selectedRecordForOptions = null
                            }
                            .padding(vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Delete from downloads",
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        // ── 6. Confirm Delete Dialog ──
        recordToDelete?.let { rec ->
            AlertDialog(
                onDismissRequest = { recordToDelete = null },
                title = { Text("Delete Download?") },
                text = { Text("Are you sure you want to delete \"${rec.songName}\" from your offline downloads?") },
                confirmButton = {
                    Button(
                        onClick = {
                            onDeleteRecord(rec)
                            recordToDelete = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Delete")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { recordToDelete = null }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

@Composable
private fun DownloadedSongRow(
    index: Int,
    record: DownloadRecord,
    onPlay: () -> Unit,
    onOptionsClick: () -> Unit
) {
    val context = LocalContext.current
    val localCoverArtCache: LocalCoverArtCache = koinInject()
    var cachedCoverUri by remember(record.mediaStoreUri) { mutableStateOf<Uri?>(null) }

    LaunchedEffect(record.mediaStoreUri) {
        if (record.coverUrl.isBlank()) {
            cachedCoverUri = runCatching {
                localCoverArtCache.coverUriFor(Uri.parse(record.mediaStoreUri))
            }.getOrNull()
        }
    }

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

        // Cover art
        val coverData = when {
            record.coverUrl.isNotBlank() -> record.coverUrl
            cachedCoverUri != null -> cachedCoverUri
            else -> R.drawable.downloaded_songs_cover
        }

        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(coverData)
                .error(R.drawable.downloaded_songs_cover)
                .fallback(R.drawable.downloaded_songs_cover)
                .crossfade(true)
                .build(),
            contentDescription = record.songName,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(8.dp))
        )

        // Title and artist
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
        ) {
            Text(
                text = record.songName,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                fontFamily = RobotoFontFamily,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 2.dp)
            ) {
                // Spotify-style Green Downloaded icon
                Icon(
                    painter = painterResource(MimikyuIcons.Download),
                    contentDescription = "Downloaded",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(12.dp)
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = record.artistName,
                    color = TextGray,
                    fontSize = 12.sp,
                    fontFamily = RobotoFontFamily,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (record.fileSize > 0) {
                    Text(
                        text = " • ${formatBytes(record.fileSize)}",
                        color = TextGray.copy(alpha = 0.65f),
                        fontSize = 11.sp,
                        fontFamily = RobotoFontFamily
                    )
                }
            }
        }

        // Three-dots options button
        IconButton(
            onClick = onOptionsClick,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = "Options",
                tint = TextGray,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

