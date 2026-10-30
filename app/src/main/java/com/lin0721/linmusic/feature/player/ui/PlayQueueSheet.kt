package com.lin0721.linmusic.feature.player.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.ui.res.painterResource
import com.lin0721.linmusic.core.ui.theme.MimikyuIcons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.lin0721.linmusic.core.ui.components.MimikyuIconButton
import com.lin0721.linmusic.core.ui.components.MimikyuTextButton
import com.lin0721.linmusic.core.ui.components.MimikyuButton
import com.lin0721.linmusic.core.player.PlayMode
import com.lin0721.linmusic.core.player.QueueItem
import com.lin0721.linmusic.core.player.rememberQueueItemCoverUrl
import com.lin0721.linmusic.core.ui.components.DraggableSongRow
import com.lin0721.linmusic.core.ui.components.SongRowData
import com.lin0721.linmusic.core.ui.theme.BackgroundDark
import com.lin0721.linmusic.core.ui.theme.BottomSheetShape
import com.lin0721.linmusic.core.ui.theme.DragHandleShape
import com.lin0721.linmusic.core.ui.theme.NeteaseRed
import com.lin0721.linmusic.core.ui.components.SwipeDeleteBackground
import com.lin0721.linmusic.core.ui.theme.SurfaceDark
import com.lin0721.linmusic.core.ui.theme.SurfaceLight
import com.lin0721.linmusic.core.ui.theme.TextGray
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.lin0721.linmusic.core.ui.theme.MimikyuSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayQueueSheet(
    queue: List<QueueItem>,
    currentIndex: Int,
    playMode: PlayMode,
    playContext: String?,
    isPlaying: Boolean,
    sleepTimerRemaining: Long,
    onPlayAtIndex: (Int) -> Unit,
    onRemoveAtIndex: (Int) -> Unit,
    onMoveItem: (from: Int, to: Int) -> Unit,
    onToggleShuffle: () -> Unit,
    onClearQueue: () -> Unit,
    onDisableRoaming: () -> Unit,
    onDisableIntelligence: () -> Unit,
    onShowTimerClick: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val isRoaming = playContext == "similar_roaming"

    LaunchedEffect(currentIndex, playMode) {
        if (isRoaming) {
            listState.scrollToItem(0)
        } else if (currentIndex in queue.indices) {
            // Played of  = 1 (Played) + currentIndex (Played of Songs)
            val scrollIndex = if (currentIndex > 0) currentIndex + 1 else 0
            listState.scrollToItem(scrollIndex)
        }
    }


    var draggedIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var settlingIndex by remember { mutableIntStateOf(-1) }
    val settleOffset = remember { Animatable(0f) }
    val isDraggingActive = draggedIndex >= 0
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    var showClearConfirmDialog by remember { mutableStateOf(false) }

    // "Playing Next"，All of
    val upcomingStart = currentIndex + 1

    val upcomingKeys = remember(queue, upcomingStart, playMode) {
        val seen = mutableMapOf<Long, Int>()
        queue.drop(upcomingStart).map { item ->
            val occurrence = seen.getOrElse(item.songId) { 0 }
            seen[item.songId] = occurrence + 1
            "${playMode.name}_upcoming_${item.songId}_$occurrence"
        }
    }

    // composed range  layoutInfo，
    val playedBlockCount = if (!isRoaming && currentIndex > 0) 1 + currentIndex else 0
    val currentBlockCount = if (currentIndex in queue.indices) 2 else 0
    val upcomingFirstLazyIndex = playedBlockCount + currentBlockCount + 1
    fun lazyIndexOf(queueIndex: Int): Int = upcomingFirstLazyIndex + (queueIndex - upcomingStart)
    fun queueIndexOf(lazyIndex: Int): Int = upcomingStart + (lazyIndex - upcomingFirstLazyIndex)
    val fallbackItemHeightPx = with(density) { 60.dp.toPx() }
    fun draggedItemHeight(): Float =
        listState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.index == lazyIndexOf(draggedIndex) }
            ?.size?.toFloat() ?: fallbackItemHeightPx

    // of ， draggedIndex  of
    fun maxComposedQueueIndex(): Int =
        listState.layoutInfo.visibleItemsInfo.maxOfOrNull { it.index }
            ?.let { queueIndexOf(it) } ?: queue.lastIndex
    fun minComposedQueueIndex(): Int =
        listState.layoutInfo.visibleItemsInfo.minOfOrNull { it.index }
            ?.let { queueIndexOf(it) } ?: upcomingStart

    // ， and  of ： dragOffset
    fun clampDragOffset(offset: Float): Float {
        val itemHeight = draggedItemHeight()
        return when {
            (draggedIndex <= upcomingStart || draggedIndex <= minComposedQueueIndex()) && offset < 0f ->
                max(offset, -itemHeight)
            (draggedIndex >= queue.lastIndex || draggedIndex >= maxComposedQueueIndex()) && offset > 0f ->
                min(offset, itemHeight)
            else -> offset
        }
    }

    // and  of ：dragOffset  and ，
    // of
    // LazyColumn  of  of  key， key  index，
    // ，，。
    // ，，，
    // index （requestScrollToItem  index、 key）
    fun neutralizeAnchorShift(from: Int, to: Int) {
        val anchor = listState.firstVisibleItemIndex
        if (lazyIndexOf(from) != anchor && lazyIndexOf(to) != anchor) return
        listState.requestScrollToItem(anchor, listState.firstVisibleItemScrollOffset)
    }

    fun advanceSwaps() {
        var swapped = false
        val itemHeight = draggedItemHeight()
        while (dragOffset > itemHeight && draggedIndex < queue.lastIndex && draggedIndex < maxComposedQueueIndex()) {
            neutralizeAnchorShift(draggedIndex, draggedIndex + 1)
            onMoveItem(draggedIndex, draggedIndex + 1)
            draggedIndex += 1
            dragOffset = clampDragOffset(dragOffset - itemHeight)
            swapped = true
        }
        while (dragOffset < -itemHeight && draggedIndex > upcomingStart && draggedIndex > minComposedQueueIndex()) {
            neutralizeAnchorShift(draggedIndex, draggedIndex - 1)
            onMoveItem(draggedIndex, draggedIndex - 1)
            draggedIndex -= 1
            dragOffset = clampDragOffset(dragOffset + itemHeight)
            swapped = true
        }
        if (swapped) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    // 。， px/
    val topEdgeThreshold = with(density) { 80.dp.toPx() }
    val bottomEdgeThreshold = with(density) { 100.dp.toPx() }
    LaunchedEffect(isDraggingActive) {
        if (!isDraggingActive) return@LaunchedEffect
        var edgeDurationMs = 0f
        var lastFrameNanos = 0L
        while (isActive && draggedIndex >= 0) {
            val frameNanos = withFrameNanos { it }
            val deltaMs = if (lastFrameNanos == 0L) {
                16f
            } else {
                ((frameNanos - lastFrameNanos) / 1_000_000f).coerceIn(1f, 64f)
            }
            lastFrameNanos = frameNanos

            val layoutInfo = listState.layoutInfo
            val draggingItem = layoutInfo.visibleItemsInfo.firstOrNull { it.index == lazyIndexOf(draggedIndex) }
            if (draggingItem == null) {
                edgeDurationMs = 0f
                continue
            }

            val currentTop = draggingItem.offset + dragOffset
            val currentBottom = currentTop + draggingItem.size
            val viewportEnd = layoutInfo.viewportEndOffset

            val isNearTop = currentTop < topEdgeThreshold && draggedIndex > upcomingStart
            val isNearBottom = currentBottom > (viewportEnd - bottomEdgeThreshold) && draggedIndex < queue.lastIndex
            if (!isNearTop && !isNearBottom) {
                edgeDurationMs = 0f
                continue
            }

            edgeDurationMs += deltaMs
            // ： 1.5s  1.0  3.5
            val timeMultiplier = 1f + (edgeDurationMs / 600f).coerceAtMost(2.5f)
            val depthRatio = if (isNearTop) {
                ((topEdgeThreshold - currentTop) / topEdgeThreshold).coerceIn(0f, 1f)
            } else {
                ((currentBottom - (viewportEnd - bottomEdgeThreshold)) / bottomEdgeThreshold).coerceIn(0f, 1f)
            }
            val speedPxPerSec = (depthRatio * 1000f + 300f) * timeMultiplier
            val stepPx = speedPxPerSec * deltaMs / 1000f

            val scrolled = listState.scrollBy(if (isNearTop) -stepPx else stepPx)
            dragOffset = clampDragOffset(dragOffset + scrolled)
            advanceSwaps()
        }
    }

    if (showClearConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showClearConfirmDialog = false },
            title = { Text("Clear Queue", color = Color.White, fontWeight = FontWeight.Bold) },
            text = { Text("Are you sure you want to clear the queue?", color = TextGray, fontSize = 14.sp) },
            confirmButton = {
                MimikyuTextButton(
                    onClick = {
                        onClearQueue()
                        showClearConfirmDialog = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = NeteaseRed)
                ) {
                    Text("Yes", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                MimikyuTextButton(onClick = { showClearConfirmDialog = false }) {
                    Text("Cancel", color = Color.White)
                }
            },
            containerColor = SurfaceDark,
            shape = RoundedCornerShape(10.dp)
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = BackgroundDark,
        shape = BottomSheetShape,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = MimikyuSpacing.xs)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(DragHandleShape)
                    .background(Color.White.copy(alpha = 0.3f))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.7f)
        ) {
            QueueHeader(
                queueSize = queue.size,
                onClearClick = { showClearConfirmDialog = true },
                onDismiss = onDismiss
            )

            PlayModeInfoRow(
                playMode = playMode,
                playContext = playContext,
                onToggleShuffle = onToggleShuffle,
                onDisableRoaming = onDisableRoaming,
                onDisableIntelligence = onDisableIntelligence
            )

            HorizontalDivider(
                color = Color.White.copy(alpha = 0.08f),
                modifier = Modifier.padding(horizontal = MimikyuSpacing.md)
            )

            LazyColumn(
                state = listState,

                userScrollEnabled = !isDraggingActive,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 8.dp)
            ) {
                // 1. "Played" ()
                if (!isRoaming && currentIndex > 0) {
                    item(key = "${playMode.name}_header_played") {
                        SectionLabel("Played")
                    }
                    itemsIndexed(
                        items = queue.subList(0, currentIndex),
                        key = { _, item -> "${item.uniqueQueueId}_played" }
                    ) { idx, item ->
                        val dismissState = rememberSwipeToDismissBoxState(
                            confirmValueChange = { value ->
                                if (value == SwipeToDismissBoxValue.EndToStart) {
                                    onRemoveAtIndex(idx)
                                    true
                                } else false
                            }
                        )
                        SwipeToDismissBox(
                            state = dismissState,
                            backgroundContent = { SwipeDeleteBackground() },
                            enableDismissFromStartToEnd = false
                        ) {
                            DraggableSongRow(
                                data = SongRowData(
                                    id = item.songId,
                                    title = item.title,
                                    artist = item.artist,
                                    coverUrl = rememberQueueItemCoverUrl(item.coverUrl, item.songId, item.localUri, item.title, item.artist)
                                ),
                                isCurrent = false,
                                isPlaying = false,
                                isPlayed = true,
                                isDragging = false,
                                dragOffsetY = 0f,
                                onClick = { onPlayAtIndex(idx) },
                                onDragStart = {},
                                onDrag = { _ -> },
                                onDragEnd = {}
                            )
                        }
                    }
                }

                // 2. "Now Playing"
                if (currentIndex in queue.indices) {
                    item(key = "header_current") {
                        SectionLabel("Now Playing")
                    }
                    item(key = "${queue[currentIndex].uniqueQueueId}_current") {
                        if (isRoaming) {
                            // Delete
                            DraggableSongRow(
                                data = SongRowData(
                                    id = queue[currentIndex].songId,
                                    title = queue[currentIndex].title,
                                    artist = queue[currentIndex].artist,
                                    coverUrl = rememberQueueItemCoverUrl(
                                        queue[currentIndex].coverUrl, queue[currentIndex].songId, queue[currentIndex].localUri,
                                        queue[currentIndex].title, queue[currentIndex].artist
                                    )
                                ),
                                isCurrent = true,
                                isPlaying = isPlaying,
                                isPlayed = false,
                                isDragging = false,
                                dragOffsetY = 0f,
                                onClick = { onPlayAtIndex(currentIndex) },
                                onDragStart = {},
                                onDrag = { _ -> },
                                onDragEnd = {}
                            )
                        } else {
                            val dismissState = rememberSwipeToDismissBoxState(
                                confirmValueChange = { value ->
                                    if (value == SwipeToDismissBoxValue.EndToStart) {
                                        onRemoveAtIndex(currentIndex)
                                        true
                                    } else false
                                }
                            )
                            SwipeToDismissBox(
                                state = dismissState,
                                backgroundContent = { SwipeDeleteBackground() },
                                enableDismissFromStartToEnd = false
                            ) {
                                DraggableSongRow(
                                    data = SongRowData(
                                        id = queue[currentIndex].songId,
                                        title = queue[currentIndex].title,
                                        artist = queue[currentIndex].artist,
                                        coverUrl = rememberQueueItemCoverUrl(
                                            queue[currentIndex].coverUrl, queue[currentIndex].songId, queue[currentIndex].localUri,
                                            queue[currentIndex].title, queue[currentIndex].artist
                                        )
                                    ),
                                    isCurrent = true,
                                    isPlaying = isPlaying,
                                    isPlayed = false,
                                    isDragging = false,
                                    dragOffsetY = 0f,
                                    onClick = { onPlayAtIndex(currentIndex) },
                                    onDragStart = {},
                                    onDrag = { _ -> },
                                    onDragEnd = {}
                                )
                            }
                        }
                    }
                }

                // 3. "Playing Next" ()
                if (!isRoaming) {
                    if (upcomingStart < queue.size) {
                        item(key = "header_upcoming") {
                            SectionLabel(
                                if (playMode == PlayMode.SHUFFLE) "Shuffle source: ${playContext ?: ""}"
                                else "Playing Next"
                            )
                        }
                        itemsIndexed(
                            items = queue.subList(upcomingStart, queue.size),
                            key = { _, item -> item.uniqueQueueId }
                        ) { idx, item ->
                            val actualIndex = upcomingStart + idx
                            val isDragging = draggedIndex == actualIndex
                            val isSettling = settlingIndex == actualIndex
                            val dismissState = rememberSwipeToDismissBoxState(
                                confirmValueChange = { value ->
                                    if (value == SwipeToDismissBoxValue.EndToStart) {
                                        onRemoveAtIndex(actualIndex)
                                        true
                                    } else false
                                }
                            )
                            Box(
                                // of ， animateItem  translationY
                                modifier = if (isDragging || isSettling) Modifier.zIndex(1f) else Modifier.animateItem()
                            ) {
                                SwipeToDismissBox(
                                    state = dismissState,
                                    backgroundContent = { SwipeDeleteBackground() },
                                    enableDismissFromStartToEnd = false,

                                    enableDismissFromEndToStart = !isDraggingActive
                                ) {
                                    DraggableSongRow(
                                        data = SongRowData(
                                            id = item.songId,
                                            title = item.title,
                                            artist = item.artist,
                                            coverUrl = rememberQueueItemCoverUrl(item.coverUrl, item.songId, item.localUri, item.title, item.artist)
                                        ),
                                        isCurrent = false,
                                        isPlaying = false,
                                        isPlayed = false,
                                        isDragging = isDragging,
                                        dragOffsetY = when {
                                            isDragging -> dragOffset
                                            isSettling -> settleOffset.value
                                            else -> 0f
                                        },
                                        onClick = { onPlayAtIndex(actualIndex) },
                                        onDragStart = {
                                            // 。 draggedIndex，
                                            // ，Queue
                                            if (draggedIndex < 0) {
                                                settlingIndex = -1
                                                draggedIndex = actualIndex
                                                dragOffset = 0f
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            }
                                        },
                                        onDrag = { delta ->
                                            // actualIndex  and  draggedIndex ，，
                                            // of
                                            if (draggedIndex == actualIndex) {
                                                dragOffset = clampDragOffset(dragOffset + delta)
                                                advanceSwaps()
                                            }
                                        },
                                        onDragEnd = {
                                            if (draggedIndex == actualIndex) {
                                                val releasedOffset = dragOffset
                                                draggedIndex = -1
                                                dragOffset = 0f
                                                if (releasedOffset != 0f) {
                                                    settlingIndex = actualIndex
                                                    scope.launch {
                                                        settleOffset.snapTo(releasedOffset)
                                                        settleOffset.animateTo(
                                                            targetValue = 0f,
                                                            animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
                                                        )
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
            }

            BottomActionRow(
                playMode = playMode,
                playContext = playContext,
                sleepTimerRemaining = sleepTimerRemaining,
                onToggleShuffle = onToggleShuffle,
                onDisableRoaming = onDisableRoaming,
                onDisableIntelligence = onDisableIntelligence,
                onShowTimerClick = {
                    scope.launch {
                        sheetState.hide()
                        onDismiss()
                        onShowTimerClick()
                    }
                }
            )
        }
    }
}

@Composable
private fun QueueHeader(
    queueSize: Int,
    onClearClick: () -> Unit,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "Queue",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        if (queueSize > 1) {
            MimikyuIconButton(
                onClick = onClearClick,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.DeleteOutline,
                    contentDescription = "Clear Queue",
                    tint = TextGray,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            "$queueSize songs",
            color = TextGray,
            fontSize = 13.sp,
            modifier = Modifier.padding(end = MimikyuSpacing.sm)
        )
        MimikyuIconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
            Icon(painter = painterResource(MimikyuIcons.Close), contentDescription = null, tint = TextGray, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun PlayModeInfoRow(
    playMode: PlayMode,
    playContext: String?,
    onToggleShuffle: () -> Unit,
    onDisableRoaming: () -> Unit,
    onDisableIntelligence: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = MimikyuSpacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val isRoaming = playContext == "similar_roaming"
        val isIntelligence = playContext == "intelligence"
        val iconRes: Int
        val label: String
        when {
            isRoaming -> {
                iconRes = MimikyuIcons.ShuffleSmart
                label = "Roaming"
            }
            isIntelligence -> {
                iconRes = MimikyuIcons.ShuffleSmart
                label = "Heartbeat"
            }
            playMode == PlayMode.LIST_LOOP -> {
                iconRes = MimikyuIcons.Repeat
                label = "Repeat All"
            }
            playMode == PlayMode.SINGLE_LOOP -> {
                iconRes = MimikyuIcons.RepeatOnce
                label = "Repeat One"
            }
            playMode == PlayMode.SEQUENTIAL -> {
                iconRes = MimikyuIcons.Repeat
                label = "Sequential"
            }
            else -> {
                iconRes = MimikyuIcons.Shuffle
                label = "Shuffle"
            }
        }
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(SurfaceDark)
                .clickable {
                    when {
                        isRoaming -> onDisableRoaming()
                        isIntelligence -> onDisableIntelligence()
                        else -> onToggleShuffle()
                    }
                }
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(painter = painterResource(iconRes), contentDescription = null, tint = NeteaseRed, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, color = Color.White, fontSize = 12.sp)
        }
        if (!playContext.isNullOrBlank() && !isRoaming && !isIntelligence) {
            Spacer(Modifier.width(12.dp))
            Text(
                "From: $playContext",
                color = TextGray,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = TextGray,
        fontSize = 12.sp,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
    )
}

@Composable
private fun BottomActionRow(
    playMode: PlayMode,
    playContext: String?,
    sleepTimerRemaining: Long,
    onToggleShuffle: () -> Unit,
    onDisableRoaming: () -> Unit,
    onDisableIntelligence: () -> Unit,
    onShowTimerClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MimikyuSpacing.md, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        val isRoaming = playContext == "similar_roaming"
        val isIntelligence = playContext == "intelligence"
        MimikyuButton(
            onClick = {
                when {
                    isRoaming -> onDisableRoaming()
                    isIntelligence -> onDisableIntelligence()
                    else -> onToggleShuffle()
                }
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isRoaming || isIntelligence || playMode == PlayMode.SHUFFLE) NeteaseRed else SurfaceDark
            ),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .weight(1f)
                .height(44.dp)
        ) {
            Icon(
                painter = painterResource(
                    when {
                        isRoaming || isIntelligence -> MimikyuIcons.ShuffleSmart
                        else -> MimikyuIcons.Shuffle
                    }
                ),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                when {
                    isRoaming -> "Turn off roaming"
                    isIntelligence -> "Turn off heartbeat"
                    else -> "Shuffle"
                },
                color = Color.White,
                fontSize = 13.sp
            )
        }
        val timerText = if (sleepTimerRemaining > 0L) {
            val totalSeconds = sleepTimerRemaining / 1000L
            val mins = totalSeconds / 60
            val secs = totalSeconds % 60
            "%d:%02d".format(mins, secs)
        } else {
            "Sleep Timer"
        }
        MimikyuButton(
            onClick = onShowTimerClick,
            colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .weight(1f)
                .height(44.dp)
        ) {
            Icon(painter = painterResource(MimikyuIcons.Time), contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(timerText, color = Color.White, fontSize = 13.sp)
        }
    }
}
