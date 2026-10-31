package com.lin0721.linmusic.feature.player.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.lin0721.linmusic.core.player.PlayMode
import com.lin0721.linmusic.core.player.domain.LyricLine
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.hazeChild
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.content.Intent
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lin0721.linmusic.core.preferences.SettingsPreferences
import org.koin.compose.koinInject

// ────────────────────────────────────────────────────────────────────────────
// ：Close and ，、 and Play
// ────────────────────────────────────────────────────────────────────────────
@Composable
fun FullScreenLyricsView(
    lyrics: List<LyricLine>,
    currentIndex: Int,
    isLoading: Boolean,
    title: String,
    artist: String,
    coverUrl: String? = null,
    chorusStartMs: Long? = null,
    base: Color,
    highlightColor: Color,
    onSeek: (Long) -> Unit,
    hazeState: HazeState,
    onClose: () -> Unit,
    onDragClose: () -> Unit = onClose,
    isPlaying: Boolean,
    currentPositionProvider: () -> Long,
    duration: Long,
    onTogglePlay: () -> Unit,
    onPlayNext: () -> Unit,
    onPlayPrevious: () -> Unit,
    playMode: PlayMode,
    onToggleShuffle: () -> Unit,
    onToggleRepeat: () -> Unit,
    onMoreClick: () -> Unit,
    lyricOffsetMs: Long = 0L,
    onLyricOffsetChange: (Long) -> Unit = {},
    onLyricOffsetReset: () -> Unit = {},
    onShareLyrics: (() -> Unit)? = null
) {
    val lazyListState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var timerJob by remember { mutableStateOf<Job?>(null) }
    var showSettingsSheet by remember { mutableStateOf(false) }

    val settingsPreferences: SettingsPreferences = koinInject()
    val fullScreenLyricTextSize by settingsPreferences.fullScreenLyricTextSize.collectAsStateWithLifecycle(initialValue = 32)
    val fullScreenLyricAlignment by settingsPreferences.fullScreenLyricAlignment.collectAsStateWithLifecycle(initialValue = "left")
    val fullScreenLyricSecondaryMode by settingsPreferences.fullScreenLyricSecondaryMode.collectAsStateWithLifecycle(initialValue = "translation")
    val fullScreenKaraokeAdvancedEffect by settingsPreferences.fullScreenKaraokeAdvancedEffect.collectAsStateWithLifecycle(initialValue = true)
    val fullScreenLyricLineSpacing by settingsPreferences.fullScreenLyricLineSpacing.collectAsStateWithLifecycle(initialValue = 24)
    val fullScreenLyricSecondarySpacing by settingsPreferences.fullScreenLyricSecondarySpacing.collectAsStateWithLifecycle(initialValue = 6)

    val hasTranslation = remember(lyrics) { lyrics.any { it.translation != null } }
    val hasRoma = remember(lyrics) { lyrics.any { it.roma != null } }
    val context = LocalContext.current

    val handleShareLyrics: () -> Unit = {
        val currentLyricText = lyrics.getOrNull(currentIndex)?.text?.takeIf { it.isNotBlank() }
        val shareText = if (currentLyricText != null) {
            "「$currentLyricText」\n——《$title》$artist"
        } else {
            "《$title》- $artist"
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, shareText)
        }
        context.startActivity(Intent.createChooser(intent, "Share Lyrics"))
    }

    val handleToggleSecondaryMode: () -> Unit = {
        val nextMode = when {
            hasTranslation && hasRoma -> when (fullScreenLyricSecondaryMode) {
                "translation" -> "roma"
                "roma" -> "none"
                else -> "translation"
            }
            hasTranslation -> when (fullScreenLyricSecondaryMode) {
                "translation" -> "none"
                else -> "translation"
            }
            hasRoma -> when (fullScreenLyricSecondaryMode) {
                "roma" -> "none"
                else -> "roma"
            }
            else -> "none"
        }
        scope.launch {
            settingsPreferences.saveFullScreenLyricSecondaryMode(nextMode)
        }
    }

    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenHeightPx = remember(configuration.screenHeightDp, density) {
        with(density) { configuration.screenHeightDp.dp.toPx() }
    }

    val dragState = rememberFullScreenLyricsDragState(
        lazyListState = lazyListState,
        onClose = onClose,
        onDragClose = onDragClose,
        screenHeightPx = screenHeightPx
    )

    LaunchedEffect(Unit) {
        dragState.reset()
    }

    DisposableEffect(Unit) {
        onDispose {
            dragState.reset()
        }
    }

    // of  UI ，，
    var isUserScrolling by remember { mutableStateOf(false) }

    val isPlayingState = rememberUpdatedState(isPlaying)

    // /Play，Moments，Current
    val handleSeek: (Long) -> Unit = { timeMs ->
        isUserScrolling = false
        onSeek(timeMs)
    }

    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            if (isUserScrolling) {
                isUserScrolling = false
            }
        } else {
            timerJob?.cancel()
        }
    }

    val gestureModifier = Modifier.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                if (event.type == PointerEventType.Press) {
                    timerJob?.cancel()
                    isUserScrolling = true
                } else if (event.type == PointerEventType.Release) {
                    timerJob?.cancel()
                    if (isPlayingState.value) {
                        timerJob = scope.launch {
                            delay(5000)
                            isUserScrolling = false
                        }
                    }
                }
            }
        }
    }

    val topCornerRadius by remember {
        derivedStateOf {
            if (dragState.offsetY > 0f) 24.dp else 0.dp
        }
    }

    PlayerBackdrop(
        base = base,
        mode = BackdropMode.Immersive,
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { dragState.onScreenHeightChange(it.height.toFloat()) }
            .nestedScroll(dragState.nestedScrollConnection)
            .graphicsLayer {
                translationY = dragState.offsetY
            }
            .clip(RoundedCornerShape(topStart = topCornerRadius, topEnd = topCornerRadius))
            // ，Play
            .pointerInput(Unit) {
                detectTapGestures { }
            }
    ) {

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            FullScreenLyricsHeader(
                title = title,
                artist = artist,
                coverUrl = coverUrl,
                onClose = onClose,
                onMoreClick = onMoreClick,
                onDragDelta = { delta -> dragState.onHeaderDrag(delta) },
                onDragStart = { dragState.onHeaderDragStart() },
                onDragRelease = { velocity -> dragState.handleDragRelease(velocity = velocity) }
            )
            FullScreenLyricsList(
                lyrics = lyrics,
                currentIndex = currentIndex,
                isLoading = isLoading,
                isUserScrolling = isUserScrolling,
                highlightColor = highlightColor,
                currentPositionProvider = currentPositionProvider,
                lazyListState = lazyListState,
                viewportHeightPx = dragState.viewportHeightPx,
                onViewportHeightChange = { height -> dragState.onViewportHeightChange(height) },
                gestureModifier = gestureModifier,
                fontSize = fullScreenLyricTextSize,
                alignment = fullScreenLyricAlignment,
                secondaryMode = fullScreenLyricSecondaryMode,
                lineSpacing = fullScreenLyricLineSpacing,
                secondarySpacing = fullScreenLyricSecondarySpacing,
                advancedKaraokeEffect = fullScreenKaraokeAdvancedEffect,
                isPlaying = isPlaying,
                lyricOffsetMs = lyricOffsetMs,
                onSeek = handleSeek,
                onLyricClick = { line ->
                    timerJob?.cancel()
                    handleSeek(line.timeMs)
                }
            )

            FullScreenControls(
                isPlaying = isPlaying,
                currentPositionProvider = currentPositionProvider,
                duration = duration,
                onSeek = handleSeek,
                onTogglePlay = onTogglePlay,
                onPlayNext = onPlayNext,
                onPlayPrevious = onPlayPrevious,
                playMode = playMode,
                onToggleShuffle = onToggleShuffle,
                onToggleRepeat = onToggleRepeat,
                secondaryMode = fullScreenLyricSecondaryMode,
                hasTranslation = hasTranslation,
                hasRoma = hasRoma,
                onToggleSecondaryMode = handleToggleSecondaryMode,
                onShareLyrics = onShareLyrics ?: handleShareLyrics,
                onLyricsSettingsClick = { showSettingsSheet = true },
                chorusStartMs = chorusStartMs
            )
        }

        if (showSettingsSheet) {
            FullScreenLyricsSettingsSheet(
                fontSize = fullScreenLyricTextSize,
                onFontSizeChange = { size ->
                    scope.launch { settingsPreferences.saveFullScreenLyricTextSize(size) }
                },
                lineSpacing = fullScreenLyricLineSpacing,
                onLineSpacingChange = { spacing ->
                    scope.launch { settingsPreferences.saveFullScreenLyricLineSpacing(spacing) }
                },
                secondarySpacing = fullScreenLyricSecondarySpacing,
                onSecondarySpacingChange = { spacing ->
                    scope.launch { settingsPreferences.saveFullScreenLyricSecondarySpacing(spacing) }
                },
                alignment = fullScreenLyricAlignment,
                onAlignmentChange = { align ->
                    scope.launch { settingsPreferences.saveFullScreenLyricAlignment(align) }
                },
                secondaryMode = fullScreenLyricSecondaryMode,
                onSecondaryModeChange = { mode ->
                    scope.launch { settingsPreferences.saveFullScreenLyricSecondaryMode(mode) }
                },
                hasTranslation = hasTranslation,
                hasRoma = hasRoma,
                advancedKaraokeEffect = fullScreenKaraokeAdvancedEffect,
                onAdvancedKaraokeEffectChange = { enabled ->
                    scope.launch { settingsPreferences.saveFullScreenKaraokeAdvancedEffect(enabled) }
                },
                lyricOffsetMs = lyricOffsetMs,
                onLyricOffsetChange = onLyricOffsetChange,
                onLyricOffsetReset = onLyricOffsetReset,
                onDismiss = { showSettingsSheet = false }
            )
        }
    }
}
