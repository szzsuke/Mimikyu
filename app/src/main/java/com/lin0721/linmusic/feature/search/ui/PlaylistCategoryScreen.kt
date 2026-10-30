package com.lin0721.linmusic.feature.search.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.SubcomposeAsyncImage
import com.lin0721.linmusic.core.ui.components.CoverPlaceholder
import com.lin0721.linmusic.core.ui.components.MimikyuIconButton
import com.lin0721.linmusic.LocalBottomOverlayInset
import com.lin0721.linmusic.core.model.PlaylistDetail
import com.lin0721.linmusic.core.ui.components.DiscoverySectionSkeleton
import com.lin0721.linmusic.core.ui.components.EmptyState
import com.lin0721.linmusic.core.ui.components.ErrorState
import com.lin0721.linmusic.core.ui.components.ToastManager
import com.lin0721.linmusic.core.ui.interaction.pressable
import com.lin0721.linmusic.core.ui.theme.MimikyuPress
import com.lin0721.linmusic.core.ui.theme.MimikyuSpacing
import com.lin0721.linmusic.core.ui.theme.rememberMimikyuGridColumns
import org.koin.androidx.compose.koinViewModel

// Curated PlaylistsPlay， and  feature/playlist  of ，
private fun formatPlayCount(count: Long): String = when {
    count >= 100000000 -> "${(count / 10000000) / 10.0}B"
    count >= 10000 -> "${(count / 1000) / 10.0}M"
    else -> count.toString()
}

private val HEADER_HEIGHT = 220.dp
private val SIMPLE_HEADER_HEIGHT = 56.dp

@Composable
fun PlaylistCategoryScreen(
    category: String,
    viewModel: PlaylistCategoryViewModel = koinViewModel(),
    onBack: () -> Unit,
    onPlaylistClick: (id: Long) -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(category) {
        viewModel.load(category)
    }

    LaunchedEffect(viewModel) {
        viewModel.toastEvent.collect { ToastManager.showToast(it) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            when (val state = uiState) {
                PlaylistCategoryUiState.Loading -> {
                    SimpleHeader(category)
                    Column(modifier = Modifier.fillMaxSize().padding(top = MimikyuSpacing.md)) {
                        DiscoverySectionSkeleton()
                        Spacer(Modifier.height(MimikyuSpacing.lg))
                        DiscoverySectionSkeleton()
                    }
                }
                PlaylistCategoryUiState.Empty -> {
                    SimpleHeader(category)
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        EmptyState(icon = Icons.Rounded.LibraryMusic, title = "No playlists in this category")
                    }
                }
                is PlaylistCategoryUiState.Error -> {
                    SimpleHeader(category)
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        ErrorState(message = state.message, onRetry = { viewModel.retry() })
                    }
                }
                is PlaylistCategoryUiState.Success -> {
                    val gridState = rememberLazyGridState()
                    val shouldLoadMore by remember(state.hasMore, state.isLoadingMore) {
                        derivedStateOf {
                            // 0 ，Playlists of  grid index  +1
                            val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                            lastVisible >= state.playlists.size - 3 && state.hasMore && !state.isLoadingMore
                        }
                    }
                    LaunchedEffect(shouldLoadMore) {
                        if (shouldLoadMore) viewModel.loadMore()
                    }

                    val columns = rememberMimikyuGridColumns(compact = 2, expandedPortrait = 4, expandedLandscape = 6)
                    val uniquePlaylists = remember(state.playlists) { state.playlists.distinctBy { it.id } }
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(columns),
                        state = gridState,
                        horizontalArrangement = Arrangement.spacedBy(MimikyuSpacing.sm),
                        verticalArrangement = Arrangement.spacedBy(MimikyuSpacing.sm),
                        contentPadding = PaddingValues(
                            start = MimikyuSpacing.md,
                            end = MimikyuSpacing.md,
                            bottom = LocalBottomOverlayInset.current + 16.dp
                        ),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
                            CategoryHeroHeader(
                                category = category,
                                coverUrl = state.playlists.first().coverImgUrl,
                                total = state.total,
                                modifier = Modifier.padding(bottom = MimikyuSpacing.sm)
                            )
                        }

                        items(uniquePlaylists, key = { it.id }) { playlist ->
                            PlaylistCategoryCard(playlist = playlist, onClick = { onPlaylistClick(playlist.id) })
                        }

                        if (state.isLoadingMore) {
                            item(key = "loading", span = { GridItemSpan(maxLineSpan) }) {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(MimikyuSpacing.md),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        MimikyuIconButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(MimikyuSpacing.xs)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.3f))
        ) {
            Icon(
                Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "Back",
                tint = Color.White
            )
        }
    }
}

// （//） of ，Back
@Composable
private fun SimpleHeader(category: String) {
    Column(modifier = Modifier.fillMaxWidth().statusBarsPadding()) {
        Spacer(Modifier.height(SIMPLE_HEADER_HEIGHT))
        Text(
            text = category,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = MimikyuSpacing.md)
        )
    }
}

// ：Play（Back of ） of Playlist Cover
@Composable
private fun CategoryHeroHeader(
    category: String,
    coverUrl: String,
    total: Int,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(HEADER_HEIGHT)
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        if (coverUrl.isNotBlank()) {
            SubcomposeAsyncImage(
                model = "${coverUrl}?param=500y500",
                contentDescription = null,
                contentScale = ContentScale.Crop,
                loading = { CoverPlaceholder() },
                error = { CoverPlaceholder() },
                modifier = Modifier.fillMaxSize()
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.35f),
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.75f)
                        )
                    )
                )
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = MimikyuSpacing.md)
                .padding(bottom = MimikyuSpacing.md)
        ) {
            Text(
                text = category,
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "$total Featured Playlists",
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 13.sp
            )
        }
    }
}

@Composable
private fun PlaylistCategoryCard(playlist: PlaylistDetail, onClick: () -> Unit) {
    Column(modifier = Modifier.pressable(MimikyuPress.Card, onClick = onClick)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (playlist.coverImgUrl.isNotBlank()) {
                SubcomposeAsyncImage(
                    model = "${playlist.coverImgUrl}?param=300y300",
                    contentDescription = playlist.name,
                    contentScale = ContentScale.Crop,
                    loading = { CoverPlaceholder() },
                    error = { CoverPlaceholder() },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        Spacer(Modifier.height(MimikyuSpacing.xs))
        Text(
            text = playlist.name,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 14.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (playlist.playCount > 0) {
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(12.dp)
                )
                Text(
                    text = formatPlayCount(playlist.playCount),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 1
                )
            }
        }
    }
}
