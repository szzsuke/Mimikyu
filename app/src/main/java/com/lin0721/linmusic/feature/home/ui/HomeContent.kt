package com.lin0721.linmusic.feature.home.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lin0721.linmusic.LocalBottomOverlayInset
import com.lin0721.linmusic.R
import com.lin0721.linmusic.core.auth.RecentPlayRecord
import com.lin0721.linmusic.core.model.Track
import com.lin0721.linmusic.core.songlike.LikedSong
import com.lin0721.linmusic.core.ui.components.AlbumArtImage
import com.lin0721.linmusic.core.ui.components.MimikyuStackedSleeve
import com.lin0721.linmusic.core.ui.components.MimikyuWireframePlaylistCard
import com.lin0721.linmusic.core.ui.theme.MimikyuPress
import com.lin0721.linmusic.core.ui.theme.RobotoFontFamily
import com.lin0721.linmusic.core.ui.interaction.pressScale
import com.lin0721.linmusic.core.userplaylist.UserPlaylist

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeContent(
    recentRecords: List<RecentPlayRecord> = emptyList(),
    likedSongs: List<LikedSong> = emptyList(),
    downloadedCount: Int = 0,
    userPlaylists: List<UserPlaylist> = emptyList(),
    currentTrackId: String? = null,
    isPlaying: Boolean = false,
    onTrackClick: (Track) -> Unit = {},
    onLikedSongClick: (LikedSong) -> Unit = {},
    onLikedSongsPlaylistClick: () -> Unit = {},
    onDownloadedSongsPlaylistClick: () -> Unit = {},
    onPlaylistClick: (Long, Boolean) -> Unit = { _, _ -> },
    onNavigateToRecentPlay: () -> Unit = {},
    onConnectSpotifyClick: () -> Unit = {},
    onConnectYouTubeMusicClick: () -> Unit = {},
    onSearchClick: () -> Unit = {},
    isRefreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    // Backward-compatible parameters
    uiState: HomeUiState? = null,
    onArtistClick: (Long) -> Unit = {},
    onSongClick: (shelfTitle: String, List<com.lin0721.linmusic.feature.home.domain.HomeCard.Song>, com.lin0721.linmusic.feature.home.domain.HomeCard.Song) -> Unit = { _, _, _ -> },
    onVoiceClick: (shelfTitle: String, List<com.lin0721.linmusic.feature.home.domain.HomeCard.Voice>, com.lin0721.linmusic.feature.home.domain.HomeCard.Voice) -> Unit = { _, _, _ -> },
    onRetry: () -> Unit = {},
    onLoadMore: () -> Unit = {},
    onIntelligenceClick: () -> Unit = {},
    onRoamingClick: () -> Unit = {}
) {
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize()
    ) {
        val last10Recent = remember(recentRecords) { recentRecords.take(10) }
        val sortedPlaylists = remember(userPlaylists) {
            userPlaylists.sortedByDescending { it.playCount }
        }

        val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val contentTopPadding = statusBarTop + 56.dp

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = contentTopPadding,
                bottom = LocalBottomOverlayInset.current + 24.dp
            )
        ) {
            // ────────────────────────────────────────────────────────────────
            // 1. FIRST SHELF: "Recents" (Spotify-accurate last 10 songs)
            // ────────────────────────────────────────────────────────────────
            item(key = "shelf_recent_header") {
                RecentShelfHeader(
                    hasSongs = last10Recent.isNotEmpty(),
                    onNavigateToHistory = onNavigateToRecentPlay
                )
            }

            item(key = "shelf_recent_content") {
                if (last10Recent.isNotEmpty()) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(last10Recent, key = { "recent_${it.track.id}_${it.playedAt}" }) { record ->
                            val track = record.track
                            val isCurrent = currentTrackId == track.id.toString() ||
                                currentTrackId == com.lin0721.linmusic.core.player.innertube.YouTubeIdRegistry.getVideoId(track.id)
                            RecentTrackCard(
                                track = track,
                                isPlaying = isPlaying && isCurrent,
                                isCurrent = isCurrent,
                                onClick = { onTrackClick(track) }
                            )
                        }
                    }
                } else {
                    RecentEmptyCard(onSearchClick = onSearchClick)
                }
            }

            item(key = "spacer_after_recent") {
                Spacer(modifier = Modifier.height(28.dp))
            }

            // ────────────────────────────────────────────────────────────────
            // 2. SHELF: "Your Playlists"
            // Card 1: Liked Songs (exact same picture and style as Library)
            // Cards 2+: User's playlists sorted by listening frequency
            // When < 10 playlists: Blank playlist design cards with minimal
            // monochrome logos for YouTube Music and Spotify.
            // ────────────────────────────────────────────────────────────────
            item(key = "shelf_playlists_header") {
                YourPlaylistsHeader()
            }

            item(key = "shelf_playlists_content") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Item 1: Liked Songs Playlist Card (Exact same as Library)
                    item(key = "playlist_liked_songs") {
                        MimikyuWireframePlaylistCard(
                            title = "Liked Songs",
                            subtitle = "Playlist • ${likedSongs.size} songs",
                            trackCount = likedSongs.size,
                            isLikedSongs = true,
                            isPinned = true,
                            cardWidth = 140.dp,
                            onClick = onLikedSongsPlaylistClick
                        )
                    }

                    // Item 2: Downloaded Songs Playlist Card (Cat cover art)
                    item(key = "playlist_downloaded_songs") {
                        MimikyuWireframePlaylistCard(
                            title = "Downloaded Songs",
                            subtitle = "Playlist • $downloadedCount songs",
                            trackCount = downloadedCount,
                            isDownloadedSongs = true,
                            isPinned = true,
                            cardWidth = 140.dp,
                            onClick = onDownloadedSongsPlaylistClick
                        )
                    }

                    // Items 2+: User's Playlists in order of listen frequency
                    items(sortedPlaylists, key = { "user_pl_${it.id}" }) { playlist ->
                        MimikyuWireframePlaylistCard(
                            title = playlist.name,
                            subtitle = "Playlist • ${playlist.trackCount} tracks",
                            trackCount = playlist.trackCount,
                            coverUrl = playlist.coverImgUrl,
                            cardWidth = 140.dp,
                            onClick = { onPlaylistClick(playlist.id, false) }
                        )
                    }

                    // If < 10 playlists: blank playlist cards with minimal monochrome logos
                    if (sortedPlaylists.size < 10) {
                        item(key = "connect_yt_music") {
                            BlankConnectPlaylistCard(
                                title = "YouTube Music",
                                subtitle = if (!com.metrolist.innertube.YouTube.cookie.isNullOrBlank()) "Connected" else "Connect account",
                                iconRes = R.drawable.ic_ytm_minimal,
                                onClick = onConnectYouTubeMusicClick
                            )
                        }

                        item(key = "connect_spotify") {
                            BlankConnectPlaylistCard(
                                title = "Spotify",
                                subtitle = "Connect account",
                                iconRes = R.drawable.ic_spotify_minimal,
                                onClick = onConnectSpotifyClick
                            )
                        }
                    }
                }
            }

            // ────────────────────────────────────────────────────────────────
            // 3. THE 8 CURATED DISCOVER SHELVES:
            // 1. Made For You
            // 2. New Releases
            // 3. Mimikyu Classics
            // 4. Charts
            // 5. Trending
            // 6. Discover
            // 7. Mimikyu Singles
            // 8. Decades
            // With curated playlists inside, and NO description with shelf name!
            // ────────────────────────────────────────────────────────────────
            val shelves = (uiState as? HomeUiState.Success)?.data?.shelves.orEmpty()
            items(shelves, key = { "curated_shelf_${it.blockCode}" }) { shelf ->
                HomeShelfSection(
                    shelf = shelf,
                    onCardClick = { card ->
                        when (card) {
                            is com.lin0721.linmusic.feature.home.domain.HomeCard.Playlist -> onPlaylistClick(card.id, false)
                            is com.lin0721.linmusic.feature.home.domain.HomeCard.Album -> onPlaylistClick(card.id, true)
                            is com.lin0721.linmusic.feature.home.domain.HomeCard.Song -> {
                                val shelfSongs = shelf.cards.filterIsInstance<com.lin0721.linmusic.feature.home.domain.HomeCard.Song>()
                                onSongClick(shelf.title, shelfSongs, card)
                            }
                            else -> {}
                        }
                    }
                )
            }
        }
    }
}

// ────────────────────────────────────────────────────────────────────────────
// Components for Shelf 1: Recents
// ────────────────────────────────────────────────────────────────────────────

@Composable
private fun RecentShelfHeader(
    hasSongs: Boolean,
    onNavigateToHistory: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Recents",
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = RobotoFontFamily
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onNavigateToHistory)
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                text = "Show all",
                color = Color(0xFFB3B3B3),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = RobotoFontFamily
            )
            Icon(
                imageVector = Icons.Rounded.ArrowForward,
                contentDescription = "Show all",
                tint = Color(0xFFB3B3B3),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun RecentTrackCard(
    track: Track,
    isPlaying: Boolean,
    isCurrent: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier
            .width(116.dp)
            .pressScale(MimikyuPress.Card, interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
    ) {
        Box(
            modifier = Modifier
                .size(116.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF1E1E1E))
        ) {
            AlbumArtImage(
                url = track.al.picUrl,
                contentDescription = track.name,
                songId = track.id,
                title = track.name,
                artist = track.ar.joinToString { it.name },
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxSize()
            )

            if (isCurrent && isPlaying) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.GraphicEq,
                        contentDescription = "Playing",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = track.name,
            color = if (isCurrent) MaterialTheme.colorScheme.primary else Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontFamily = RobotoFontFamily
        )

        val artistText = track.ar.joinToString { it.name }.ifBlank { "Unknown Artist" }
        Text(
            text = "Song • $artistText",
            color = Color(0xFF8E8E93),
            fontSize = 11.sp,
            fontWeight = FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontFamily = RobotoFontFamily
        )
    }
}

@Composable
private fun RecentEmptyCard(
    onSearchClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF18181A))
            .border(1.dp, Color(0xFF28282B), RoundedCornerShape(12.dp))
            .clickable(onClick = onSearchClick)
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "No recent songs yet",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = RobotoFontFamily
                )
                Text(
                    text = "Play any song to start your listening journey",
                    color = Color(0xFF8E8E93),
                    fontSize = 11.sp,
                    fontFamily = RobotoFontFamily
                )
            }
        }
    }
}

// ────────────────────────────────────────────────────────────────────────────
// Components for Shelf 2: Your Playlists
// ────────────────────────────────────────────────────────────────────────────

@Composable
private fun YourPlaylistsHeader() {
    Text(
        text = "Your Playlists",
        color = Color.White,
        fontSize = 22.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = RobotoFontFamily,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
    )
}

/**
 * Blank playlist design card with minimal monochrome platform logo.
 */
@Composable
private fun BlankConnectPlaylistCard(
    title: String,
    subtitle: String,
    @DrawableRes iconRes: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .width(140.dp)
            .pressScale(MimikyuPress.Card, interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.TopCenter
        ) {
            // Stacked sleeve tab sticking out 6dp at the top
            MimikyuStackedSleeve(
                color = Color(0xFF1F1F20),
                modifier = Modifier.align(Alignment.TopCenter)
            )

            // Main 1:1 Cover Box: Blank playlist card design
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF18181A))
                    .border(1.dp, Color(0xFF28282B), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = title,
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(44.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = title,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontFamily = RobotoFontFamily
        )

        Text(
            text = subtitle,
            color = Color(0xFF8E8E93),
            fontSize = 11.sp,
            fontWeight = FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontFamily = RobotoFontFamily
        )
    }
}
