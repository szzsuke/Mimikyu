package com.lin0721.linmusic.feature.search.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import coil.compose.SubcomposeAsyncImage
import com.lin0721.linmusic.core.metadata.SongArtworkEngine
import com.lin0721.linmusic.core.ui.theme.MimikyuIcons
import com.lin0721.linmusic.core.ui.theme.MimikyuYellow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lin0721.linmusic.LocalBottomOverlayInset
import com.lin0721.linmusic.core.model.Track
import com.lin0721.linmusic.core.ui.components.EmptyState
import com.lin0721.linmusic.core.ui.components.EntityCoverShape
import com.lin0721.linmusic.core.ui.components.EntityRow
import com.lin0721.linmusic.core.ui.components.EntityRowData
import com.lin0721.linmusic.core.ui.components.ErrorState
import com.lin0721.linmusic.core.ui.components.FilterChipsRow
import com.lin0721.linmusic.core.ui.components.MimikyuIconButton
import com.lin0721.linmusic.core.ui.components.SearchResultRowSkeleton
import com.lin0721.linmusic.core.ui.components.SongRow
import com.lin0721.linmusic.core.ui.components.SongRowData
import com.lin0721.linmusic.core.ui.theme.MimikyuSpacing
import com.lin0721.linmusic.feature.search.domain.SearchResultItem
import com.lin0721.linmusic.feature.search.domain.SearchType
import com.lin0721.linmusic.feature.search.domain.stableKey
import kotlinx.coroutines.flow.StateFlow

private const val TAB_ENTER_DURATION = 300
private const val TAB_EXIT_DURATION = 150

@Composable
internal fun SearchResultsContent(
    selectedType: SearchType,
    resultsByType: Map<SearchType, StateFlow<SearchResultsUiState>>,
    listStates: Map<SearchType, LazyListState>,
    currentTrackId: String?,
    isPlaying: Boolean,
    likedSongIds: Set<Long>,
    isLoggedIn: Boolean,
    onSelectType: (SearchType) -> Unit,
    onSongClick: (Track) -> Unit,
    onAlbumClick: (Long) -> Unit,
    onArtistClick: (Long) -> Unit,
    onPlaylistClick: (Long) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onLikeClick: (Long) -> Unit,
    onOpenMoreOptions: (Track) -> Unit
) {
    val types = SearchType.entries
    Column(modifier = Modifier.fillMaxSize()) {
        FilterChipsRow(
            items = remember { types.map { it.label } },
            selectedIndex = types.indexOf(selectedType),
            onSelected = { index -> types.getOrNull(index)?.let(onSelectType) },
            modifier = Modifier.padding(bottom = MimikyuSpacing.xs)
        )
        AnimatedContent(
            targetState = selectedType,
            transitionSpec = {
                fadeIn(tween(TAB_ENTER_DURATION, easing = FastOutSlowInEasing))
                    .togetherWith(fadeOut(tween(TAB_EXIT_DURATION)))
            },
            label = "search_type_tab_switch"
        ) { type ->
            val resultsState by resultsByType.getValue(type).collectAsStateWithLifecycle()
            SearchResultsList(
                state = resultsState,
                type = type,
                listState = listStates.getValue(type),
                currentTrackId = currentTrackId,
                isPlaying = isPlaying,
                likedSongIds = likedSongIds,
                isLoggedIn = isLoggedIn,
                onSongClick = onSongClick,
                onAlbumClick = onAlbumClick,
                onArtistClick = onArtistClick,
                onPlaylistClick = onPlaylistClick,
                onLoadMore = onLoadMore,
                onRetry = onRetry,
                onLikeClick = onLikeClick,
                onOpenMoreOptions = onOpenMoreOptions
            )
        }
    }
}

// Search
@Composable
private fun SearchResultsList(
    state: SearchResultsUiState,
    type: SearchType,
    listState: LazyListState,
    currentTrackId: String?,
    isPlaying: Boolean,
    likedSongIds: Set<Long>,
    isLoggedIn: Boolean,
    onSongClick: (Track) -> Unit,
    onAlbumClick: (Long) -> Unit,
    onArtistClick: (Long) -> Unit,
    onPlaylistClick: (Long) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onLikeClick: (Long) -> Unit,
    onOpenMoreOptions: (Track) -> Unit
) {
    when (state) {
        SearchResultsUiState.Idle, SearchResultsUiState.Loading -> {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(6) { SearchResultRowSkeleton() }
            }
        }
        SearchResultsUiState.Empty -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    iconRes = MimikyuIcons.Search,
                    title = "No results found for ${type.label}"
                )
            }
        }
        is SearchResultsUiState.Error -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                ErrorState(message = state.message, onRetry = onRetry)
            }
        }
        is SearchResultsUiState.Success -> {
            val uniqueItems = remember(state.items) {
                state.items.distinctBy { it.stableKey }
            }

            if (type == SearchType.ALL) {
                val songs = remember(uniqueItems) { uniqueItems.filterIsInstance<SearchResultItem.SongItem>() }
                val artists = remember(uniqueItems) { uniqueItems.filterIsInstance<SearchResultItem.ArtistItem>() }
                val albums = remember(uniqueItems) { uniqueItems.filterIsInstance<SearchResultItem.AlbumItem>() }
                val playlists = remember(uniqueItems) { uniqueItems.filterIsInstance<SearchResultItem.PlaylistItem>() }
                val topSong = remember(songs) { songs.firstOrNull()?.track }

                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(bottom = LocalBottomOverlayInset.current + 16.dp)
                ) {
                    // 1. Promoted Top Result Card
                    if (topSong != null) {
                        item(key = "search:top_result:${topSong.id}") {
                            TopResultCard(
                                track = topSong,
                                isCurrentlyPlaying = currentTrackId == topSong.id.toString(),
                                isPlaying = isPlaying,
                                onPlay = { onSongClick(topSong) },
                                onPlaylist = { onOpenMoreOptions(topSong) },
                                onOpenMoreOptions = { onOpenMoreOptions(topSong) }
                            )
                        }
                    }

                    // 2. Songs Section
                    if (songs.isNotEmpty()) {
                        item(key = "section:songs:title") {
                            SectionHeader(title = "Songs")
                        }
                        items(songs, key = { it.stableKey }) { item ->
                            val track = item.track
                            val isActive = currentTrackId == track.id.toString()
                            SongRow(
                                data = SongRowData(
                                    id = track.id,
                                    title = track.name,
                                    artist = track.ar.joinToString(" / ") { it.name },
                                    coverUrl = track.al.picUrl
                                ),
                                isActive = isActive,
                                isPlaying = isPlaying,
                                onClick = { onSongClick(track) },
                                trailingSlot = {
                                    if (isLoggedIn && track.id in likedSongIds) {
                                        MimikyuIconButton(
                                            onClick = { onLikeClick(track.id) },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                painter = painterResource(MimikyuIcons.HeartFilled),
                                                contentDescription = "Liked song",
                                                tint = MimikyuYellow,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                    MimikyuIconButton(
                                        onClick = { onOpenMoreOptions(track) },
                                        modifier = Modifier.size(32.dp).padding(end = MimikyuSpacing.xs)
                                    ) {
                                        Icon(
                                            painter = painterResource(MimikyuIcons.More),
                                            contentDescription = "More options",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 72.dp, end = 16.dp),
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                            )
                        }
                    }

                    // 3. Artists Section
                    if (artists.isNotEmpty()) {
                        item(key = "section:artists:title") {
                            SectionHeader(title = "Artists")
                        }
                        items(artists, key = { it.stableKey }) { item ->
                            EntityRow(
                                data = EntityRowData(
                                    id = item.artist.id,
                                    title = item.artist.name,
                                    subtitle = "Artist",
                                    coverUrl = item.artist.picUrl,
                                    coverShape = EntityCoverShape.Circle
                                ),
                                onClick = { onArtistClick(item.artist.id) }
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 72.dp, end = 16.dp),
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                            )
                        }
                    }

                    // 4. Albums Section
                    if (albums.isNotEmpty()) {
                        item(key = "section:albums:title") {
                            SectionHeader(title = "Albums")
                        }
                        items(albums, key = { it.stableKey }) { item ->
                            EntityRow(
                                data = EntityRowData(
                                    id = item.album.id,
                                    title = item.album.name,
                                    subtitle = item.album.artists.joinToString(" / ") { it.name }.ifBlank { "Album" },
                                    coverUrl = item.album.picUrl,
                                    coverShape = EntityCoverShape.Rounded
                                ),
                                onClick = { onAlbumClick(item.album.id) }
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 72.dp, end = 16.dp),
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                            )
                        }
                    }

                    // 5. Playlists Section
                    if (playlists.isNotEmpty()) {
                        item(key = "section:playlists:title") {
                            SectionHeader(title = "Playlists")
                        }
                        items(playlists, key = { it.stableKey }) { item ->
                            EntityRow(
                                data = EntityRowData(
                                    id = item.playlist.id,
                                    title = item.playlist.name,
                                    subtitle = listOfNotNull(
                                        item.playlist.creator?.nickname,
                                        if (item.playlist.trackCount > 0) "${item.playlist.trackCount} songs" else null
                                    ).joinToString(" · ").ifBlank { "Playlist" },
                                    coverUrl = item.playlist.coverImgUrl,
                                    coverShape = EntityCoverShape.Rounded
                                ),
                                onClick = { onPlaylistClick(item.playlist.id) }
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 72.dp, end = 16.dp),
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                            )
                        }
                    }
                }
            } else {
                val shouldLoadMore by remember(state.hasMore, state.isLoadingMore) {
                    derivedStateOf {
                        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                        lastVisible >= state.items.size - 5 && state.hasMore && !state.isLoadingMore
                    }
                }
                LaunchedEffect(shouldLoadMore) {
                    if (shouldLoadMore) onLoadMore()
                }

                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(bottom = LocalBottomOverlayInset.current + 16.dp)
                ) {
                    item(key = "header") {
                        Text(
                            "${state.totalCount} ${type.label} found",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = MimikyuSpacing.md, vertical = MimikyuSpacing.sm)
                        )
                    }

                    items(uniqueItems, key = { it.stableKey }) { item ->
                        when (item) {
                            is SearchResultItem.SongItem -> {
                                val track = item.track
                                val isActive = currentTrackId == track.id.toString()
                                SongRow(
                                    data = SongRowData(
                                        id = track.id,
                                        title = track.name,
                                        artist = track.ar.joinToString(" / ") { it.name },
                                        coverUrl = track.al.picUrl
                                    ),
                                    isActive = isActive,
                                    isPlaying = isPlaying,
                                    onClick = { onSongClick(track) },
                                    trailingSlot = {
                                        if (isLoggedIn && track.id in likedSongIds) {
                                            MimikyuIconButton(
                                                onClick = { onLikeClick(track.id) },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(
                                                    painter = painterResource(MimikyuIcons.HeartFilled),
                                                    contentDescription = "Liked song",
                                                    tint = MimikyuYellow,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                        }
                                        MimikyuIconButton(
                                            onClick = { onOpenMoreOptions(track) },
                                            modifier = Modifier.size(32.dp).padding(end = MimikyuSpacing.xs)
                                        ) {
                                            Icon(
                                                painter = painterResource(MimikyuIcons.More),
                                                contentDescription = "More options",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                )
                            }
                            is SearchResultItem.AlbumItem -> EntityRow(
                                data = EntityRowData(
                                    id = item.album.id,
                                    title = item.album.name,
                                    subtitle = item.album.artists.joinToString(" / ") { it.name },
                                    coverUrl = item.album.picUrl,
                                    coverShape = EntityCoverShape.Rounded
                                ),
                                onClick = { onAlbumClick(item.album.id) }
                            )
                            is SearchResultItem.ArtistItem -> EntityRow(
                                data = EntityRowData(
                                    id = item.artist.id,
                                    title = item.artist.name,
                                    coverUrl = item.artist.picUrl,
                                    coverShape = EntityCoverShape.Circle
                                ),
                                onClick = { onArtistClick(item.artist.id) }
                            )
                            is SearchResultItem.PlaylistItem -> EntityRow(
                                data = EntityRowData(
                                    id = item.playlist.id,
                                    title = item.playlist.name,
                                    subtitle = listOfNotNull(
                                        item.playlist.creator?.nickname,
                                        if (item.playlist.trackCount > 0) "${item.playlist.trackCount} songs" else null
                                    ).joinToString(" · "),
                                    coverUrl = item.playlist.coverImgUrl,
                                    coverShape = EntityCoverShape.Rounded
                                ),
                                onClick = { onPlaylistClick(item.playlist.id) }
                            )
                        }
                    }

                    if (state.isLoadingMore) {
                        item(key = "loading") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(MimikyuSpacing.md),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 6.dp)
    )
}

@Composable
private fun TopResultCard(
    track: Track,
    isCurrentlyPlaying: Boolean,
    isPlaying: Boolean,
    onPlay: () -> Unit,
    onPlaylist: () -> Unit,
    onOpenMoreOptions: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .clickable(onClick = onPlay)
            .padding(16.dp)
    ) {
        Text(
            text = "Top result",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val coverModel = remember(track.al.picUrl) {
                SongArtworkEngine.optimizeArtworkUrl(track.al.picUrl, 544)
            }
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF282828)),
                contentAlignment = Alignment.Center
            ) {
                SubcomposeAsyncImage(
                    model = coverModel,
                    contentDescription = track.name,
                    contentScale = ContentScale.Crop,
                    loading = {
                        Box(modifier = Modifier.fillMaxSize().background(Color(0xFF282828)), contentAlignment = Alignment.Center) {
                            Icon(painter = painterResource(MimikyuIcons.Track), contentDescription = null, tint = Color(0xFF717171), modifier = Modifier.size(28.dp))
                        }
                    },
                    error = {
                        Box(modifier = Modifier.fillMaxSize().background(Color(0xFF282828)), contentAlignment = Alignment.Center) {
                            Icon(painter = painterResource(MimikyuIcons.Track), contentDescription = null, tint = Color(0xFF717171), modifier = Modifier.size(28.dp))
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
                if (isCurrentlyPlaying) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center
                    ) {
                        com.lin0721.linmusic.core.ui.components.PlayingEqualizerBars(
                            color = Color(0xFF1ED760),
                            animate = isPlaying
                        )
                    }
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = track.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isCurrentlyPlaying) Color(0xFF1ED760) else MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = track.ar.joinToString(" / ") { it.name },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            MimikyuIconButton(
                onClick = onOpenMoreOptions,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    painter = painterResource(MimikyuIcons.More),
                    contentDescription = "More options",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onPlay,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                shape = RoundedCornerShape(percent = 50),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp)
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text("Play", fontWeight = FontWeight.SemiBold)
            }
            OutlinedButton(
                onClick = onPlaylist,
                shape = RoundedCornerShape(percent = 50),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text("Playlist", fontWeight = FontWeight.Medium)
            }
        }
    }
}
