package com.lin0721.linmusic.feature.player.ui

import android.media.AudioDeviceInfo
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lin0721.linmusic.core.player.PlayMode
import com.lin0721.linmusic.core.ui.theme.PlayerBackdropPalette

const val FullPlayerFillGapCount = 3

val FullPlayerPlaybackItemKeys = listOf("cover", "active_lyric", "song_info", "progress", "controls", "actions")

fun LazyListScope.fullPlayerPlaybackSection(
    songState: PlayerSongDetailState,
    colors: PlayerBackdropPalette,
    coverUrl: String,
    queue: List<com.lin0721.linmusic.core.player.QueueItem> = emptyList(),
    currentIndex: Int = 0,
    onPlayIndex: (Int) -> Unit = {},
    previousCoverUrl: String?,
    nextCoverUrl: String?,
    onSwipeToPrevious: () -> Unit,
    onCancelSwipe: () -> Boolean,
    currentKey: Any,
    previousKey: Any? = null,
    nextKey: Any? = null,
    coverExtraInset: Dp = 0.dp,
    onToggleSidebarFullscreen: (() -> Unit)? = null,
    isSidebarFullscreen: Boolean = false,
    title: String,
    artist: String,
    playContext: String?,
    currentLyricIndex: Int,
    isPlaying: Boolean,
    playWhenReady: Boolean,
    currentPositionProvider: () -> Long,
    duration: Long,
    playMode: PlayMode,
    onClose: () -> Unit,
    onMoreClick: () -> Unit,
    onToggleLike: () -> Unit,
    onArtistClick: () -> Unit,
    onAddToPlaylist: (() -> Unit)? = null,
    onSeek: (Long) -> Unit,
    onTogglePlay: () -> Unit,
    onPlayNext: () -> Unit,
    onPlayPrevious: () -> Unit,
    onToggleShuffle: () -> Unit,
    onToggleRepeat: () -> Unit,
    onDisableRoaming: () -> Unit,
    onDisableIntelligence: () -> Unit,
    onOutputDeviceClick: () -> Unit,
    onQueueClick: () -> Unit,
    onShareClick: () -> Unit,
    onOpenFullScreenLyrics: () -> Unit = {},
    currentSongId: Long = 0L,
    onDownloadClick: () -> Unit = {},
    connectedDevice: AudioDeviceInfo? = null,
    onCurrentBitmapLoaded: ((android.graphics.Bitmap) -> Unit)? = null,
    fillGap: Dp? = null,
    fillTail: Dp = 0.dp
) {
    fun fillGapItem(index: Int) {
        if (fillGap == null) return
        item(key = "$FullPlayerFillGapKeyPrefix$index") {
            Spacer(modifier = Modifier.height(fillGap))
        }
    }

    item(key = "cover") {
        FullPlayerItemEnterAnimation {
            FullPlayerCoverArt(
                coverUrl = coverUrl,
                playContext = playContext,
                onClose = onClose,
                onMoreClick = onMoreClick,
                queue = queue,
                currentIndex = currentIndex,
                onPlayIndex = onPlayIndex,
                isPlaying = isPlaying,
                previousCoverUrl = previousCoverUrl,
                nextCoverUrl = nextCoverUrl,
                onSwipeToPrevious = onSwipeToPrevious,
                onSwipeToNext = onPlayNext,
                currentKey = currentKey,
                previousKey = previousKey,
                nextKey = nextKey,
                coverExtraInset = coverExtraInset,
                onToggleSidebarFullscreen = onToggleSidebarFullscreen,
                isSidebarFullscreen = isSidebarFullscreen,
                onCancelSwipe = onCancelSwipe,
                onCurrentBitmapLoaded = onCurrentBitmapLoaded
            )
        }
    }

    fillGapItem(1)

    item(key = "active_lyric") {
        MiniLyricLine(
            lyrics = songState.lyrics,
            currentLyricIndex = currentLyricIndex,
            isPlaying = isPlaying,
            isLyricsLoading = songState.isLyricsLoading,
            isModern = false,
            currentPositionProvider = currentPositionProvider,
            onOpenFullScreen = onOpenFullScreenLyrics
        )
    }

    item(key = "song_info") {
        SongInfo(
            title = title,
            artist = artist,
            isLiked = songState.isLiked,
            onToggleLike = onToggleLike,
            onArtistClick = onArtistClick,
            onAddToPlaylist = onAddToPlaylist,
            isModern = false,
            onMoreClick = onMoreClick
        )
    }

    fillGapItem(2)

    item(key = "progress") {
        val displayDuration = if (duration > 0L) duration else (songState.songDetail?.dt ?: 0L)
        ProgressSection(
            currentPositionProvider = currentPositionProvider,
            duration = displayDuration,
            onSeek = onSeek,
            chorusStartMs = songState.chorusStartMs,
            isModern = false
        )
    }

    fillGapItem(3)

    item(key = "controls") {
        PlaybackControls(
            isPlaying = isPlaying,
            isLoading = playWhenReady && !isPlaying,
            onTogglePlay = onTogglePlay,
            onPlayNext = onPlayNext,
            onPlayPrevious = onPlayPrevious,
            onToggleShuffle = onToggleShuffle,
            onToggleRepeat = onToggleRepeat,
            playMode = playMode,
            isRoaming = playContext == "similar_roaming",
            onDisableRoaming = onDisableRoaming,
            isIntelligence = playContext == "intelligence",
            onDisableIntelligence = onDisableIntelligence,
            isModern = false
        )
    }

    fillGapItem(4)

    item(key = "actions") {
        ActionButtons(
            onOutputDeviceClick = onOutputDeviceClick,
            onQueueClick = onQueueClick,
            onShareClick = onShareClick,
            onOpenFullScreenLyrics = onOpenFullScreenLyrics,
            connectedDevice = connectedDevice,
            isModern = false,
            onMoreClick = onMoreClick,
            currentSongId = currentSongId,
            onDownloadClick = onDownloadClick
        )
    }

    if (fillGap != null) {
        item(key = "${FullPlayerFillGapKeyPrefix}tail") {
            Spacer(modifier = Modifier.height(fillTail))
        }
    }
}
