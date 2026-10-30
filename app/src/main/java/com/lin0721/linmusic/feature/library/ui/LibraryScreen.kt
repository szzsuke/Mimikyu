package com.lin0721.linmusic.feature.library.ui

import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.res.painterResource
import com.lin0721.linmusic.core.ui.theme.MimikyuIcons
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.SwapVert
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.lin0721.linmusic.core.ui.components.MimikyuIconButton
import com.lin0721.linmusic.core.ui.components.MimikyuButton
import com.lin0721.linmusic.LocalBottomOverlayInset
import com.lin0721.linmusic.LocalGlobalOverlayOpen
import com.lin0721.linmusic.core.ui.components.LoginBottomSheet
import com.lin0721.linmusic.core.ui.components.MimikyuDragHandle
import com.lin0721.linmusic.core.ui.components.WebViewLoginScreen
import com.lin0721.linmusic.core.ui.interaction.pressable
import com.lin0721.linmusic.core.ui.theme.MimikyuPress
import com.lin0721.linmusic.core.ui.theme.BottomSheetShape
import com.lin0721.linmusic.core.ui.theme.MimikyuSpacing
import com.lin0721.linmusic.core.ui.theme.PillRadius
import com.lin0721.linmusic.core.ui.theme.ScreenSlideDurationMs
import com.lin0721.linmusic.core.ui.theme.rememberMimikyuGridColumns
import org.koin.androidx.compose.koinViewModel
import androidx.compose.foundation.gestures.*
import androidx.compose.animation.core.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.zIndex
import java.util.Collections
import kotlin.math.max
import kotlin.math.min
import android.content.Intent
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import com.lin0721.linmusic.core.auth.UserProfile



@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onLikedSongsClick: () -> Unit = {},
    onDownloadedSongsClick: () -> Unit = {},
    onPlaylistClick: (Long) -> Unit,
    onArtistClick: (Long) -> Unit,
    onAlbumClick: (Long) -> Unit,
    onBack: () -> Unit,
    onOpenSidebar: () -> Unit = {},
    onLoginScreenVisibilityChanged: (Boolean) -> Unit = {},
    onNavigateToLocalMusic: () -> Unit = {}
) {
    val viewModel: LibraryViewModel = koinViewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val userProfile by viewModel.userProfile.collectAsStateWithLifecycle()
    val selectedFilter by viewModel.selectedFilter.collectAsStateWithLifecycle()
    val selectedPlaylistOwnerFilter by viewModel.selectedPlaylistOwnerFilter.collectAsStateWithLifecycle()
    val sortOrder by viewModel.sortOrder.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val isGridView by viewModel.isGridView.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showLoginSheet by remember { mutableStateOf(false) }
    var showWebViewLogin by remember { mutableStateOf(false) }


    LaunchedEffect(showWebViewLogin) {
        onLoginScreenVisibilityChanged(showWebViewLogin)
    }

    LaunchedEffect(viewModel) {
        viewModel.toastEvent.collect { message ->
            com.lin0721.linmusic.core.ui.components.ToastManager.showToast(message)
        }
    }

    var showCreateDialog by remember { mutableStateOf(false) }
    var isSearchActive by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }

    var activeOptionsItem by remember { mutableStateOf<LibraryItem?>(null) }
    var downloadQualityTarget by remember { mutableStateOf<LibraryItem?>(null) }
    var deletePlaylistTarget by remember { mutableStateOf<LibraryItem?>(null) }
    var isReorderMode by remember { mutableStateOf(false) }
    val reorderedPlaylists = remember { mutableStateListOf<LibraryItem>() }

    // ， of Back
    BackHandler(enabled = isReorderMode && !LocalGlobalOverlayOpen.current) {
        isReorderMode = false
    }

    fun shareItem(item: LibraryItem) {
        val (typeLabel, path) = when (item.type) {
            LibraryItemType.PLAYLIST -> "Playlists" to "playlist"
            LibraryItemType.ALBUM -> "Albums" to "album"
            LibraryItemType.ARTIST -> "Artists" to "artist"
            LibraryItemType.MV -> "MV" to "mv"
        }
        val shareText = "${item.title} https://music.163.com/$path?id=${item.id}"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, shareText)
        }
        context.startActivity(Intent.createChooser(intent, "Share $typeLabel"))
    }

    val scope = rememberCoroutineScope()


    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        AnimatedContent(
            targetState = isReorderMode,
            transitionSpec = {
                val forward = targetState
                val offsetY = 40
                if (forward) {
                    (fadeIn(tween(300, delayMillis = 100, easing = FastOutSlowInEasing))
                            + slideInVertically(tween(300, delayMillis = 100, easing = FastOutSlowInEasing)) { offsetY })
                        .togetherWith(
                            fadeOut(tween(200, easing = FastOutSlowInEasing))
                                    + slideOutVertically(tween(200, easing = FastOutSlowInEasing)) { -offsetY }
                        )
                } else {
                    (fadeIn(tween(300, delayMillis = 100, easing = FastOutSlowInEasing))
                            + slideInVertically(tween(300, delayMillis = 100, easing = FastOutSlowInEasing)) { -offsetY })
                        .togetherWith(
                            fadeOut(tween(200, easing = FastOutSlowInEasing))
                                    + slideOutVertically(tween(200, easing = FastOutSlowInEasing)) { offsetY }
                        )
                }.using(SizeTransform(clip = false))
            },
            label = "library_reorder_transition"
        ) { reordering ->
            if (reordering) {
                LibraryReorderView(
                    playlists = reorderedPlaylists,
                    onCancel = { isReorderMode = false },
                    onDone = {
                        viewModel.savePlaylistOrder(reorderedPlaylists.toList())
                        isReorderMode = false
                    }
                )
            } else {
                Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            // 1.  (SearchExpand)
            AnimatedContent(
                targetState = isSearchActive,
                transitionSpec = {
                    fadeIn() togetherWith fadeOut()
                },
                label = "top_bar_search"
            ) { active ->
                if (active) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                            .padding(horizontal = MimikyuSpacing.md),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MimikyuIconButton(onClick = {
                            isSearchActive = false
                            viewModel.updateSearchQuery("")
                        }) {
                            Icon(painter = painterResource(MimikyuIcons.ArrowLeft), contentDescription = "Back", tint = MaterialTheme.colorScheme.onSurface)
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp)
                                .clip(RoundedCornerShape(PillRadius))
                                .background(MaterialTheme.colorScheme.surface)
                                .padding(horizontal = MimikyuSpacing.md),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (searchQuery.isEmpty()) {
                                Text("Search your library...", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                            }
                            BasicTextField(
                                value = searchQuery,
                                onValueChange = { viewModel.updateSearchQuery(it) },
                                textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                        }

                        if (searchQuery.isNotEmpty()) {
                            MimikyuIconButton(onClick = { viewModel.updateSearchQuery("") }) {
                                Icon(painter = painterResource(MimikyuIcons.Close), contentDescription = "Clear", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                            .padding(horizontal = MimikyuSpacing.md),
                        verticalAlignment = Alignment.CenterVertically
                    ) {


                        Text(
                            text = "Your Library",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )

                        MimikyuIconButton(onClick = { isSearchActive = true }) {
                            Icon(painter = painterResource(MimikyuIcons.Search), contentDescription = "Search", tint = MaterialTheme.colorScheme.onSurface)
                        }

                        MimikyuIconButton(onClick = {
                            showCreateDialog = true
                        }) {
                            Icon(painter = painterResource(MimikyuIcons.Plus), contentDescription = "Create playlist", tint = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }

            val successState = uiState as? LibraryUiState.Success
                // 2.
                LibraryFilterPillsRow(
                    selectedFilter = selectedFilter,
                    playlistCount = successState?.playlistCount ?: 0,
                    albumCount = successState?.albumCount ?: 0,
                    artistCount = successState?.artistCount ?: 0,
                    onSelect = { viewModel.toggleFilter(it) },
                    onClear = { viewModel.clearFilter() },
                    selectedPlaylistOwnerFilter = selectedPlaylistOwnerFilter,
                    onSelectPlaylistOwnerFilter = { viewModel.togglePlaylistOwnerFilter(it) }
                )

                // 3.
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when (val state = uiState) {
                        is LibraryUiState.Loading -> {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        is LibraryUiState.Error -> {
                            Box(
                                modifier = Modifier.fillMaxSize().padding(MimikyuSpacing.xl),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = state.message,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 14.sp,
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(modifier = Modifier.height(MimikyuSpacing.md))
                                    MimikyuButton(
                                        onClick = { viewModel.loadLibraryData() },
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                    ) {
                                        Text("Retry", color = MaterialTheme.colorScheme.onPrimary)
                                    }
                                }
                            }
                        }
                        is LibraryUiState.Success -> {
                            if (state.filteredItems.isEmpty()) {
                                Box(
                                    modifier = Modifier.fillMaxSize().padding(MimikyuSpacing.xl),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = if (searchQuery.isNotEmpty()) "No items found" else "Library is empty, add some music!",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 14.sp,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            } else if (isGridView) {
                                val gridColumns = rememberMimikyuGridColumns(compact = 3, expandedPortrait = 5, expandedLandscape = 7)
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(bottom = LocalBottomOverlayInset.current + 16.dp, top = 8.dp, start = MimikyuSpacing.md, end = MimikyuSpacing.md)
                                ) {
                                    item(key = "library_sort_bar") {
                                        LibrarySortAndFilterBar(
                                            sortOrder = sortOrder,
                                            isGridView = isGridView,
                                            onSortClick = { showSortMenu = true },
                                            onToggleGridView = { viewModel.updateGridView(!isGridView) },
                                            modifier = Modifier.padding(bottom = MimikyuSpacing.sm)
                                        )
                                    }
                                    val rows = state.filteredItems.chunked(gridColumns)
                                    items(rows, key = { row -> row.joinToString(separator = "_") { it.id } }) { row ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            row.forEach { item ->
                                                LibraryGridItem(
                                                    item = item,
                                                    modifier = Modifier.weight(1f),
                                                    onClick = {
                                                        if (item.isLikedSongs || item.id == "liked_songs") {
                                                            onLikedSongsClick()
                                                        } else if (item.isDownloadedSongs || item.id == "downloaded_songs") {
                                                            onDownloadedSongsClick()
                                                        } else if (item.type == LibraryItemType.PLAYLIST) {
                                                            item.id.toLongOrNull()?.let { onPlaylistClick(it) }
                                                        } else if (item.type == LibraryItemType.ARTIST) {
                                                            item.id.toLongOrNull()?.let { onArtistClick(it) }
                                                        } else {
                                                            item.id.toLongOrNull()?.let { onAlbumClick(it) }
                                                        }
                                                    },
                                                    onLongClick = {
                                                        if (item.id != "-2") {
                                                            activeOptionsItem = item
                                                        }
                                                    }
                                                )
                                            }
                                            repeat(gridColumns - row.size) {
                                                Spacer(modifier = Modifier.weight(1f))
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(12.dp))
                                    }
                                }
                            } else {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(bottom = LocalBottomOverlayInset.current + 16.dp, top = 8.dp)
                                ) {
                                    item(key = "library_sort_bar") {
                                        LibrarySortAndFilterBar(
                                            sortOrder = sortOrder,
                                            isGridView = isGridView,
                                            onSortClick = { showSortMenu = true },
                                            onToggleGridView = { viewModel.updateGridView(!isGridView) },
                                            modifier = Modifier.padding(start = MimikyuSpacing.md, end = MimikyuSpacing.md, bottom = MimikyuSpacing.sm)
                                        )
                                    }
                                    items(state.filteredItems, key = { "${it.type}_${it.id}" }) { item ->
                                        LibraryItemRow(
                                            item = item,
                                            onClick = {
                                                if (item.isLikedSongs || item.id == "liked_songs") {
                                                    onLikedSongsClick()
                                                } else if (item.isDownloadedSongs || item.id == "downloaded_songs") {
                                                    onDownloadedSongsClick()
                                                } else if (item.type == LibraryItemType.PLAYLIST) {
                                                    item.id.toLongOrNull()?.let { onPlaylistClick(it) }
                                                } else if (item.type == LibraryItemType.ARTIST) {
                                                    item.id.toLongOrNull()?.let { onArtistClick(it) }
                                                } else {
                                                    item.id.toLongOrNull()?.let { onAlbumClick(it) }
                                                }
                                            },
                                            onLongClick = {
                                                if (item.id != "-2") {
                                                    activeOptionsItem = item
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }


                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .align(Alignment.TopCenter)
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color.Black.copy(alpha = 0.85f),
                                        Color.Black.copy(alpha = 0.35f),
                                        Color.Transparent
                                    )
                                )
                            )
                    )
                }
            }
        }
    }

        // CreatePlaylists
        if (showCreateDialog) {
            LibraryCreatePlaylistSheet(
                onDismiss = { showCreateDialog = false },
                onCreate = { name, tracks ->
                    viewModel.createPlaylist(name, tracks)
                    showCreateDialog = false
                }
            )
        }

        // and  WebView
        if (showLoginSheet) {
            LoginBottomSheet(
                onDismiss = { showLoginSheet = false },
                onWebLogin = {
                    showLoginSheet = false
                    showWebViewLogin = true
                },
                onLoginSuccess = { cookies ->
                    showLoginSheet = false
                    viewModel.handleLoginSuccess(cookies)
                }
            )
        }

        AnimatedVisibility(
            visible = showWebViewLogin,
            enter = slideInVertically(tween(ScreenSlideDurationMs)) { it } + fadeIn(tween(ScreenSlideDurationMs)),
            exit = slideOutVertically(tween(ScreenSlideDurationMs)) { it } + fadeOut(tween(ScreenSlideDurationMs))
        ) {
            WebViewLoginScreen(
                onClose = { showWebViewLogin = false },
                onLoginSuccess = { cookies ->
                    showWebViewLogin = false
                    viewModel.handleLoginSuccess(cookies)
                }
            )
        }

        if (showSortMenu) {
            LibrarySortMenuSheet(
                currentSortOrder = sortOrder,
                onSelect = { order ->
                    viewModel.updateSortOrder(order)
                    showSortMenu = false
                },
                onDismiss = { showSortMenu = false }
            )
        }


        if (activeOptionsItem != null) {
            LibraryItemOptionsSheet(
                item = activeOptionsItem!!,
                onDismiss = { activeOptionsItem = null },
                onTogglePin = { viewModel.togglePin(it) },
                onEditOrder = {
                    reorderedPlaylists.clear()
                    reorderedPlaylists.addAll(viewModel.getPlaylistsForReorder())
                    isReorderMode = true
                },
                onShare = { shareItem(it) },
                onDownload = { item ->
                    activeOptionsItem = null
                    downloadQualityTarget = item
                },
                onDeletePlaylist = { playlistId ->
                    val target = (uiState as? LibraryUiState.Success)?.allItems?.find { it.id == playlistId.toString() }
                    if (target != null) {
                        deletePlaylistTarget = target
                    }
                },
                onUnsubscribePlaylist = { playlistId ->
                    viewModel.unsubscribePlaylist(playlistId)
                },
                onUnsubscribeAlbum = { albumId ->
                    viewModel.unsubscribeAlbum(albumId)
                },
                onUnsubscribeArtist = { artistId ->
                    viewModel.unsubscribeArtist(artistId)
                }
            )
        }

        // Download
        if (downloadQualityTarget != null) {
            val target = downloadQualityTarget!!
            com.lin0721.linmusic.core.download.ui.DownloadQualityPickerSheet(
                onQualitySelected = { level ->
                    viewModel.downloadLibraryItem(target, level)
                    downloadQualityTarget = null
                },
                onDismiss = { downloadQualityTarget = null }
            )
        }

        // DeletePlaylists
        if (deletePlaylistTarget != null) {
            val target = deletePlaylistTarget!!
            AlertDialog(
                onDismissRequest = { deletePlaylistTarget = null },
                title = { Text("Delete Playlist", fontWeight = FontWeight.Bold) },
                text = { Text("Are you sure you want to delete playlist \"${target.title}\"? This cannot be undone.") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            target.id.toLongOrNull()?.let { viewModel.deletePlaylist(it) }
                            deletePlaylistTarget = null
                        }
                    ) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { deletePlaylistTarget = null }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

// Playlists
@Composable
private fun LibraryReorderView(
    playlists: MutableList<LibraryItem>,
    onCancel: () -> Unit,
    onDone: () -> Unit
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val reorderListState = rememberLazyListState()

    val fallbackItemHeightPx = with(density) { 76.dp.toPx() }
    var itemHeightPx by remember { mutableFloatStateOf(fallbackItemHeightPx) }
    LaunchedEffect(reorderListState) {
        snapshotFlow { reorderListState.layoutInfo.visibleItemsInfo.firstOrNull()?.size ?: 0 }
            .collect { if (it > 0) itemHeightPx = it.toFloat() }
    }

    val topEdgeThreshold = with(density) { 80.dp.toPx() }
    val bottomEdgeThreshold = with(density) { 100.dp.toPx() }

    var draggedIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val isDraggingActive = draggedIndex >= 0

    var settlingIndex by remember { mutableIntStateOf(-1) }
    val settleOffset = remember { Animatable(0f) }

    fun maxComposedIndex(): Int =
        reorderListState.layoutInfo.visibleItemsInfo.maxOfOrNull { it.index } ?: playlists.lastIndex
    fun minComposedIndex(): Int =
        reorderListState.layoutInfo.visibleItemsInfo.minOfOrNull { it.index } ?: 0

    fun clampDragOffset(offset: Float): Float = when {
        (draggedIndex <= 0 || draggedIndex <= minComposedIndex()) && offset < 0f -> max(offset, -itemHeightPx)
        (draggedIndex >= playlists.lastIndex || draggedIndex >= maxComposedIndex()) && offset > 0f -> min(offset, itemHeightPx)
        else -> offset
    }

    fun neutralizeAnchorShift(from: Int, to: Int) {
        val anchor = reorderListState.firstVisibleItemIndex
        if (from != anchor && to != anchor) return
        reorderListState.requestScrollToItem(anchor, reorderListState.firstVisibleItemScrollOffset)
    }

    fun advanceSwaps() {
        var swapped = false
        while (dragOffset > itemHeightPx && draggedIndex < playlists.lastIndex && draggedIndex < maxComposedIndex()) {
            neutralizeAnchorShift(draggedIndex, draggedIndex + 1)
            Collections.swap(playlists, draggedIndex, draggedIndex + 1)
            draggedIndex += 1
            dragOffset = clampDragOffset(dragOffset - itemHeightPx)
            swapped = true
        }
        while (dragOffset < -itemHeightPx && draggedIndex > 0 && draggedIndex > minComposedIndex()) {
            neutralizeAnchorShift(draggedIndex, draggedIndex - 1)
            Collections.swap(playlists, draggedIndex, draggedIndex - 1)
            draggedIndex -= 1
            dragOffset = clampDragOffset(dragOffset + itemHeightPx)
            swapped = true
        }
        if (swapped) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }


    LaunchedEffect(isDraggingActive) {
        if (!isDraggingActive) return@LaunchedEffect
        var lastFrameTimeNs = 0L
        var edgeDurationMs = 0L
        while (isActive && draggedIndex >= 0) {
            val frameTimeNs = withFrameNanos { it }
            if (lastFrameTimeNs == 0L) {
                lastFrameTimeNs = frameTimeNs
                continue
            }
            val deltaMs = ((frameTimeNs - lastFrameTimeNs) / 1_000_000L).coerceIn(1L, 32L)
            lastFrameTimeNs = frameTimeNs

            val layoutInfo = reorderListState.layoutInfo
            val draggingItem = layoutInfo.visibleItemsInfo.firstOrNull { it.index == draggedIndex }
            if (draggingItem == null) continue

            val currentTop = draggingItem.offset + dragOffset
            val currentBottom = currentTop + itemHeightPx
            val viewportEnd = layoutInfo.viewportEndOffset.toFloat()

            val isNearTop = currentTop < topEdgeThreshold && draggedIndex > 0
            val isNearBottom = currentBottom > (viewportEnd - bottomEdgeThreshold) &&
                draggedIndex < playlists.lastIndex

            if (!isNearTop && !isNearBottom) {
                edgeDurationMs = 0L
                continue
            }
            edgeDurationMs += deltaMs

            val timeMultiplier = 1f + (edgeDurationMs / 600f).coerceAtMost(2.5f)
            val depthRatio = if (isNearTop) {
                ((topEdgeThreshold - currentTop) / topEdgeThreshold).coerceIn(0f, 1f)
            } else {
                ((currentBottom - (viewportEnd - bottomEdgeThreshold)) / bottomEdgeThreshold).coerceIn(0f, 1f)
            }
            val speedPxPerSec = (depthRatio * 1000f + 300f) * timeMultiplier
            val stepPx = speedPxPerSec * deltaMs / 1000f

            val scrolled = reorderListState.scrollBy(if (isNearTop) -stepPx else stepPx)
            dragOffset = clampDragOffset(dragOffset + scrolled)
            advanceSwaps()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(horizontal = MimikyuSpacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MimikyuIconButton(onClick = onCancel) {
                Icon(
                    painter = painterResource(MimikyuIcons.ArrowLeft),
                    contentDescription = "Cancel",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                text = "Reorder Playlists",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = MimikyuSpacing.sm)
            )
            MimikyuButton(
                onClick = onDone,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "Done",
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        LazyColumn(
            state = reorderListState,
            userScrollEnabled = !isDraggingActive,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = LocalBottomOverlayInset.current + 16.dp, top = MimikyuSpacing.xs)
        ) {
            itemsIndexed(
                items = playlists,
                key = { idx, item -> "${item.id}_$idx" }
            ) { idx, item ->
                val isDragging = draggedIndex == idx
                val isSettling = settlingIndex == idx
                Box(
                    modifier = if (isDragging || isSettling) Modifier.zIndex(1f) else Modifier.animateItem()
                ) {
                    DraggablePlaylistRow(
                        item = item,
                        isDragging = isDragging,
                        dragOffsetY = when {
                            isDragging -> dragOffset
                            isSettling -> settleOffset.value
                            else -> 0f
                        },
                        enabled = true,
                        onDragStart = {
                            if (draggedIndex < 0) {
                                settlingIndex = -1
                                draggedIndex = idx
                                dragOffset = 0f
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                        },
                        onDrag = { delta ->
                            if (draggedIndex == idx) {
                                dragOffset = clampDragOffset(dragOffset + delta)
                                advanceSwaps()
                            }
                        },
                        onDragEnd = {
                            if (draggedIndex == idx) {
                                val releasedOffset = dragOffset
                                draggedIndex = -1
                                dragOffset = 0f
                                if (releasedOffset != 0f) {
                                    settlingIndex = idx
                                    scope.launch {
                                        settleOffset.snapTo(releasedOffset)
                                        settleOffset.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
                                        settlingIndex = -1
                                    }
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun LibrarySortAndFilterBar(
    sortOrder: LibrarySortOrder,
    isGridView: Boolean,
    onSortClick: () -> Unit,
    onToggleGridView: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.pressable(MimikyuPress.Pill) { onSortClick() },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(MimikyuIcons.Sort),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = when (sortOrder) {
                    LibrarySortOrder.RECENTLY_PLAYED -> "Recently Played"
                    LibrarySortOrder.NAME -> "Alphabetical"
                    LibrarySortOrder.CUSTOM -> "Custom"
                },
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        }

        MimikyuIconButton(
            onClick = onToggleGridView,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                painter = painterResource(if (isGridView) MimikyuIcons.ListView else MimikyuIcons.GridView),
                contentDescription = "Switch view",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
