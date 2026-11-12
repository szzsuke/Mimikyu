package com.lin0721.linmusic.feature.library.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lin0721.linmusic.core.model.PlaylistCreator
import com.lin0721.linmusic.core.model.PlaylistDetail
import com.lin0721.linmusic.core.model.Track
import com.lin0721.linmusic.core.player.PlayMode
import com.lin0721.linmusic.core.player.PlayerManager
import com.lin0721.linmusic.core.player.QueueItem
import com.lin0721.linmusic.core.player.innertube.YouTubeIdRegistry
import com.lin0721.linmusic.core.spotify.SpotifyPlaylist
import com.lin0721.linmusic.core.ui.components.MimikyuIconButton
import com.lin0721.linmusic.core.ui.components.PlaylistCollectState
import com.lin0721.linmusic.core.ui.components.PlaylistScreenSkeleton
import com.lin0721.linmusic.core.ui.theme.MimikyuSpacing
import com.lin0721.linmusic.feature.playlist.ui.PlaylistContent
import com.lin0721.linmusic.feature.playlist.ui.PlaylistImportState
import com.lin0721.linmusic.feature.playlist.ui.PlaylistImportTargetSheet
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpotifyPlaylistDetailSheet(
    playlist: SpotifyPlaylist?,
    isLikedSongs: Boolean = false,
    tracks: List<Track>,
    isLoading: Boolean,
    onBack: () -> Unit,
    playerManager: PlayerManager,
    importState: PlaylistImportState,
    onImportToPlaylist: (Long, List<Track>) -> Unit,
    onCreateAndImport: (String, List<Track>) -> Unit
) {
    val title = if (isLikedSongs) "Liked Songs" else playlist?.name ?: "Spotify Playlist"
    val coverUrl = if (isLikedSongs) "" else playlist?.images?.firstOrNull()?.url.orEmpty()
    val ownerName = if (isLikedSongs) "Spotify" else playlist?.owner?.displayName ?: "Spotify"

    val currentTrack by playerManager.currentTrack.collectAsStateWithLifecycle()
    val isPlaying by playerManager.isPlaying.collectAsStateWithLifecycle()
    val playMode by playerManager.playMode.collectAsStateWithLifecycle()
    val playContext by playerManager.playContext.collectAsStateWithLifecycle()

    val isThisPlaylistContext = playContext == title
    val isCurrentlyPlayingThis = isThisPlaylistContext && isPlaying
    val isShuffleActive = playMode == PlayMode.SHUFFLE

    var showImportSheet by remember { mutableStateOf(false) }

    fun playAt(index: Int, shuffle: Boolean = false) {
        if (tracks.isEmpty()) return
        val queueItems = tracks.map { t ->
            val vid = YouTubeIdRegistry.getVideoId(t.id).orEmpty()
            val rawCover = YouTubeIdRegistry.getCoverUrl(t.id).takeIf { it.isNotBlank() } ?: t.al.picUrl
            QueueItem(
                songId = t.id,
                title = t.name,
                artist = t.ar.joinToString("/") { it.name },
                coverUrl = rawCover,
                localUri = t.localUri,
                videoId = vid.ifBlank { null },
                album = t.al.name.ifBlank { null },
                durationSec = (t.dt / 1000).toInt()
            )
        }
        val targetQueue = if (shuffle) queueItems.shuffled() else queueItems
        val targetIndex = if (shuffle) 0 else index.coerceIn(0, targetQueue.lastIndex)
        playerManager.playQueue(targetQueue, targetIndex, title)
    }

    val playlistDetailId = remember(playlist?.id, isLikedSongs) {
        if (isLikedSongs) 999900L else (abs(playlist?.id.hashCode().toLong()) + 100000L)
    }

    val playlistDetail = remember(playlist, tracks, isLikedSongs) {
        PlaylistDetail(
            id = playlistDetailId,
            name = title,
            coverImgUrl = coverUrl,
            description = playlist?.description ?: if (isLikedSongs) "Your Spotify Liked Songs" else null,
            playCount = 0L,
            subscribed = false,
            creator = PlaylistCreator(
                userId = 0L,
                nickname = ownerName,
                avatarUrl = ""
            ),
            tracks = tracks,
            trackCount = tracks.size,
            isEditable = false
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize()) {
                PlaylistScreenSkeleton()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .height(56.dp)
                        .padding(horizontal = MimikyuSpacing.md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MimikyuIconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        } else {
            PlaylistContent(
                playlist = playlistDetail,
                currentTrackId = currentTrack?.mediaId,
                isPlaying = isPlaying,
                likedSongIds = emptySet(),
                collectState = PlaylistCollectState(),
                isLoggedIn = true,
                userProfile = null,
                recommendedSongs = emptyList(),
                onBack = onBack,
                onArtistClick = {},
                onAlbumClick = {},
                onToggleLike = { _, _ -> },
                onPlaySong = { track ->
                    val idx = tracks.indexOfFirst { it.id == track.id }
                    if (idx >= 0) playAt(idx)
                },
                onAddToPlayNext = { track ->
                    val vid = YouTubeIdRegistry.getVideoId(track.id).orEmpty()
                    val rawCover = YouTubeIdRegistry.getCoverUrl(track.id).takeIf { it.isNotBlank() } ?: track.al.picUrl
                    val item = QueueItem(
                        songId = track.id,
                        title = track.name,
                        artist = track.ar.joinToString("/") { it.name },
                        coverUrl = rawCover,
                        localUri = track.localUri,
                        videoId = vid.ifBlank { null },
                        album = track.al.name.ifBlank { null },
                        durationSec = (track.dt / 1000).toInt()
                    )
                    playerManager.addToPlayNext(listOf(item))
                },
                onAddTracksToPlayNext = { trackList ->
                    val items = trackList.map { track ->
                        val vid = YouTubeIdRegistry.getVideoId(track.id).orEmpty()
                        val rawCover = YouTubeIdRegistry.getCoverUrl(track.id).takeIf { it.isNotBlank() } ?: track.al.picUrl
                        QueueItem(
                            songId = track.id,
                            title = track.name,
                            artist = track.ar.joinToString("/") { it.name },
                            coverUrl = rawCover,
                            localUri = track.localUri,
                            videoId = vid.ifBlank { null },
                            album = track.al.name.ifBlank { null },
                            durationSec = (track.dt / 1000).toInt()
                        )
                    }
                    playerManager.addToPlayNext(items)
                },
                onPlayAll = {
                    if (isThisPlaylistContext) {
                        playerManager.togglePlayPause()
                    } else {
                        playAt(0, shuffle = isShuffleActive)
                    }
                },
                onShuffleToggle = {
                    playerManager.toggleShuffle()
                },
                isShuffleActive = isShuffleActive,
                isCurrentlyPlayingThis = isCurrentlyPlayingThis,
                onLikeClick = {},
                onSaveCollection = { _, _ -> },
                onSaveNewCollection = { _, _ -> },
                onRequireLogin = {},
                onRefreshRecommendations = {},
                onAddRecommendSong = {},
                isSubscribed = false,
                onSubscribeClick = { showImportSheet = true },
                onMoreClick = { showImportSheet = true },
                isLikedSongsPlaylistView = isLikedSongs,
                canRemoveFromPlaylist = false
            )
        }

        if (showImportSheet) {
            PlaylistImportTargetSheet(
                importState = importState,
                onDismiss = { showImportSheet = false },
                onSelectTarget = { targetId ->
                    onImportToPlaylist(targetId, tracks)
                    showImportSheet = false
                },
                onCreateAndImport = { name ->
                    onCreateAndImport(name, tracks)
                    showImportSheet = false
                }
            )
        }
    }
}
