package com.lin0721.linmusic.feature.search.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.NorthWest
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.lin0721.linmusic.core.metadata.SongArtworkEngine
import com.lin0721.linmusic.core.ui.components.PlayingEqualizerBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import com.lin0721.linmusic.LocalBottomOverlayInset
import com.lin0721.linmusic.core.metadata.ArtistPortraitProvider
import com.lin0721.linmusic.core.ui.components.EmptyState
import com.lin0721.linmusic.core.ui.components.MimikyuIconButton
import com.lin0721.linmusic.core.ui.interaction.pressable
import com.lin0721.linmusic.core.ui.theme.MimikyuPress
import com.lin0721.linmusic.core.ui.theme.MimikyuSpacing
import com.lin0721.linmusic.core.ui.theme.RadiusCompact
import com.lin0721.linmusic.core.ui.theme.MimikyuIcons
import com.lin0721.linmusic.feature.search.data.RecentItemType
import com.lin0721.linmusic.feature.search.data.RecentSearchItem
import com.lin0721.linmusic.feature.search.domain.SearchSuggestion

private val LeadingSize = 40.dp

@Composable
internal fun SearchTypingPanel(
    query: String,
    suggestions: List<SearchSuggestion>,
    recentItems: List<RecentSearchItem>,
    currentTrackId: String? = null,
    isPlaying: Boolean = false,
    onSubmitKeyword: (String) -> Unit,
    onFillQuery: (String) -> Unit,
    onArtistClick: (Long) -> Unit,
    onAlbumClick: (Long) -> Unit,
    onPlaylistClick: (Long) -> Unit = {},
    onPlayRecentSong: (RecentSearchItem) -> Unit = {},
    onPlaySongMatch: (SearchSuggestion.SongMatch) -> Unit = {},
    onAddSongToLibrary: (Long) -> Unit = {},
    onRemoveRecentItem: (String) -> Unit,
    onClearRecentItems: () -> Unit = {}
) {
    val contentPadding = PaddingValues(bottom = LocalBottomOverlayInset.current + MimikyuSpacing.md)

    // ── 1. Query is empty: Show BitChord-style Recent searches ──
    if (query.isBlank()) {
        if (recentItems.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 96.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                EmptyState(
                    iconRes = MimikyuIcons.Search,
                    title = "Play what you love",
                    subtitle = "Search for artists, songs, albums, or playlists"
                )
            }
            return
        }

        val uniqueRecentItems = remember(recentItems) { recentItems.distinctBy { it.id } }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding
        ) {
            item(key = "recents_header") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Recent searches",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "Clear",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clip(RoundedCornerShape(percent = 50))
                            .clickable(onClick = onClearRecentItems)
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            items(uniqueRecentItems, key = { it.id }) { item ->
                val isSongActive = item.type == RecentItemType.SONG &&
                    (currentTrackId != null && (currentTrackId == item.rawId.toString() || currentTrackId == item.id))

                RecentItemRow(
                    item = item,
                    isCurrentlyPlaying = isSongActive,
                    isPlaying = isPlaying,
                    onClick = {
                        when (item.type) {
                            RecentItemType.ARTIST -> {
                                if (item.rawId > 0L) onArtistClick(item.rawId)
                                else onSubmitKeyword(item.title)
                            }
                            RecentItemType.SONG -> {
                                onPlayRecentSong(item)
                            }
                            RecentItemType.ALBUM -> {
                                if (item.rawId > 0L) onAlbumClick(item.rawId)
                                else onSubmitKeyword(item.title)
                            }
                            RecentItemType.PLAYLIST -> {
                                if (item.rawId > 0L) onPlaylistClick(item.rawId)
                                else onSubmitKeyword(item.title)
                            }
                            RecentItemType.KEYWORD -> {
                                onSubmitKeyword(item.title)
                            }
                        }
                    },
                    onAddSongToLibrary = onAddSongToLibrary,
                    onRemove = { onRemoveRecentItem(item.id) }
                )
            }
        }
        return
    }

    // ── 2. Query is not empty: Show BitChord-style Recommendations / Typeahead ──
    val highlight = MaterialTheme.colorScheme.primary
    val uniqueSuggestions = remember(suggestions) { suggestions.distinctBy { it.stableKey } }
    val keywordSuggestions = remember(uniqueSuggestions) {
        uniqueSuggestions.filterIsInstance<SearchSuggestion.Keyword>().take(3)
    }
    val mediaSuggestions = remember(uniqueSuggestions) {
        uniqueSuggestions.filter { it !is SearchSuggestion.Keyword }
    }

    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = contentPadding) {
        // Top row: Search exact query
        item(key = "submit_query") {
            PanelRow(
                onClick = { onSubmitKeyword(query) },
                leading = { LeadingIcon(Icons.Rounded.Search, tint = MaterialTheme.colorScheme.onSurface) },
                title = AnnotatedString("Search \"$query\""),
                titleWeight = FontWeight.Bold
            )
        }

        // Compact text suggestions (capped at 3)
        items(keywordSuggestions, key = { it.stableKey }) { suggestion ->
            val title = remember(suggestion.text, query, highlight) {
                highlightMatch(suggestion.text, query, SpanStyle(color = highlight))
            }
            PanelRow(
                onClick = { onSubmitKeyword(suggestion.text) },
                leading = { LeadingIcon(Icons.Rounded.Search) },
                title = title,
                trailing = {
                    MimikyuIconButton(onClick = { onFillQuery(suggestion.text) }) {
                        Icon(
                            Icons.Rounded.NorthWest,
                            contentDescription = "Fill ${suggestion.text}",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            )
        }

        // Horizontal divider between text completions and live media rows
        if (mediaSuggestions.isNotEmpty()) {
            item(key = "typeahead_divider") {
                androidx.compose.material3.HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                )
            }
        }

        // Live media typeahead rows (playable songs, artists, albums)
        items(mediaSuggestions, key = { it.stableKey }) { suggestion ->
            when (suggestion) {
                is SearchSuggestion.SongMatch -> {
                    val isSongActive = currentTrackId != null &&
                        (currentTrackId == suggestion.id.toString() || (suggestion.rawVideoId.isNotBlank() && currentTrackId == suggestion.rawVideoId))
                    TypeaheadSongRow(
                        suggestion = suggestion,
                        isCurrent = isSongActive,
                        isPlaying = isPlaying,
                        onPlay = { onPlaySongMatch(suggestion) },
                        onFill = { onFillQuery(suggestion.text) }
                    )
                }
                is SearchSuggestion.ArtistMatch -> {
                    val title = remember(suggestion.text, query, highlight) {
                        highlightMatch(suggestion.text, query, SpanStyle(color = highlight))
                    }
                    PanelRow(
                        onClick = { onArtistClick(suggestion.id) },
                        leading = { SuggestionCover(suggestion.avatarUrl, MimikyuIcons.User, circle = true) },
                        title = title,
                        subtitle = "Artist",
                        trailing = {
                            MimikyuIconButton(onClick = { onFillQuery(suggestion.text) }) {
                                Icon(
                                    Icons.Rounded.NorthWest,
                                    contentDescription = "Fill ${suggestion.text}",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    )
                }
                is SearchSuggestion.AlbumMatch -> {
                    val title = remember(suggestion.text, query, highlight) {
                        highlightMatch(suggestion.text, query, SpanStyle(color = highlight))
                    }
                    PanelRow(
                        onClick = { onAlbumClick(suggestion.id) },
                        leading = { SuggestionCover(null, MimikyuIcons.Album, circle = false) },
                        title = title,
                        subtitle = listOf("Album", suggestion.artistName).filter { it.isNotBlank() }.joinToString(" · "),
                        trailing = {
                            MimikyuIconButton(onClick = { onFillQuery(suggestion.text) }) {
                                Icon(
                                    Icons.Rounded.NorthWest,
                                    contentDescription = "Fill ${suggestion.text}",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    )
                }
                is SearchSuggestion.Keyword -> Unit
            }
        }
    }
}

@Composable
private fun RecentItemRow(
    item: RecentSearchItem,
    isCurrentlyPlaying: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onAddSongToLibrary: (Long) -> Unit,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RecentItemImage(item = item, size = 52.dp, isCurrentlyPlaying = isCurrentlyPlaying, isPlaying = isPlaying)

        Spacer(modifier = Modifier.width(14.dp))

        Column(
            modifier = Modifier.weight(1f)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = item.title,
                    color = if (isCurrentlyPlaying) Color(0xFF1ED760) else Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (item.isVerified) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        painter = painterResource(MimikyuIcons.VerifiedCheck),
                        contentDescription = "Verified Artist",
                        tint = Color.Unspecified,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(3.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (item.isExplicit) {
                    Box(
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color.White.copy(alpha = 0.65f))
                            .padding(horizontal = 4.dp, vertical = 0.5.dp)
                    ) {
                        Text(
                            text = "E",
                            color = Color.Black,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Text(
                    text = item.subtitle,
                    color = Color.White.copy(alpha = 0.65f),
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Action Buttons
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (item.type == RecentItemType.SONG) {
                IconButton(
                    onClick = { onAddSongToLibrary(item.rawId) },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        painter = painterResource(MimikyuIcons.PlusCircle),
                        contentDescription = "Add to playlist",
                        tint = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
            }

            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Remove from recents",
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun RecentItemImage(
    item: RecentSearchItem,
    size: Dp = 52.dp,
    isCurrentlyPlaying: Boolean = false,
    isPlaying: Boolean = false
) {
    val isArtist = item.type == RecentItemType.ARTIST
    val shape = if (isArtist) CircleShape else RoundedCornerShape(8.dp)
    val fallbackIcon = when (item.type) {
        RecentItemType.ARTIST -> MimikyuIcons.User
        RecentItemType.SONG -> MimikyuIcons.Track
        RecentItemType.ALBUM -> MimikyuIcons.Album
        RecentItemType.PLAYLIST -> MimikyuIcons.Playlist
        RecentItemType.KEYWORD -> MimikyuIcons.Search
    }

    val imageModel = remember(item.imageUrl, item.title, item.type) {
        when {
            item.imageUrl.isNotBlank() -> SongArtworkEngine.optimizeArtworkUrl(item.imageUrl, 120)
            isArtist -> ArtistPortraitProvider.getCachedPortrait(item.title).takeIf { it.isNotBlank() }
            else -> null
        }
    }

    Box(
        modifier = Modifier
            .size(size)
            .clip(shape)
            .background(Color(0xFF282828)),
        contentAlignment = Alignment.Center
    ) {
        if (imageModel != null) {
            SubcomposeAsyncImage(
                model = imageModel,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                loading = {
                    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF282828)), contentAlignment = Alignment.Center) {
                        Icon(painter = painterResource(fallbackIcon), contentDescription = null, tint = Color(0xFF717171), modifier = Modifier.size(24.dp))
                    }
                },
                error = {
                    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF282828)), contentAlignment = Alignment.Center) {
                        Icon(painter = painterResource(fallbackIcon), contentDescription = null, tint = Color(0xFF717171), modifier = Modifier.size(24.dp))
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                painter = painterResource(fallbackIcon),
                contentDescription = null,
                tint = Color(0xFF717171),
                modifier = Modifier.size(24.dp)
            )
        }

        if (isCurrentlyPlaying) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center
            ) {
                PlayingEqualizerBars(color = Color(0xFF1ED760), animate = isPlaying)
            }
        }
    }
}

@Composable
private fun TypeaheadSongRow(
    suggestion: SearchSuggestion.SongMatch,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onPlay: () -> Unit,
    onFill: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val coverModel = remember(suggestion.coverUrl) {
            SongArtworkEngine.optimizeArtworkUrl(suggestion.coverUrl, 120)
        }
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF282828)),
            contentAlignment = Alignment.Center
        ) {
            SubcomposeAsyncImage(
                model = coverModel,
                contentDescription = suggestion.text,
                contentScale = ContentScale.Crop,
                loading = {
                    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF282828)), contentAlignment = Alignment.Center) {
                        Icon(painter = painterResource(MimikyuIcons.Track), contentDescription = null, tint = Color(0xFF717171), modifier = Modifier.size(24.dp))
                    }
                },
                error = {
                    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF282828)), contentAlignment = Alignment.Center) {
                        Icon(painter = painterResource(MimikyuIcons.Track), contentDescription = null, tint = Color(0xFF717171), modifier = Modifier.size(24.dp))
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            if (isCurrent) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center
                ) {
                    PlayingEqualizerBars(color = Color(0xFF1ED760), animate = isPlaying)
                }
            }
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = suggestion.text,
                color = if (isCurrent) Color(0xFF1ED760) else Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = listOf("Song", suggestion.artistName).filter { it.isNotBlank() }.joinToString(" · "),
                color = Color.White.copy(alpha = 0.65f),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Direct action / fill button
        Row(verticalAlignment = Alignment.CenterVertically) {
            MimikyuIconButton(onClick = onPlay, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = "Play immediately",
                    tint = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.size(20.dp)
                )
            }
            MimikyuIconButton(onClick = onFill, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Rounded.NorthWest,
                    contentDescription = "Fill ${suggestion.text}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

private val SearchSuggestion.stableKey: String
    get() = when (this) {
        is SearchSuggestion.ArtistMatch -> "artist_$id"
        is SearchSuggestion.AlbumMatch -> "album_$id"
        is SearchSuggestion.SongMatch -> "song_$id"
        is SearchSuggestion.Keyword -> "keyword_$text"
    }

@Composable
private fun PanelRow(
    onClick: () -> Unit,
    leading: @Composable () -> Unit,
    title: AnnotatedString,
    subtitle: String? = null,
    titleWeight: FontWeight = FontWeight.Normal,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressable(MimikyuPress.Row, onClick = onClick)
            .padding(start = MimikyuSpacing.md, end = MimikyuSpacing.xs, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        leading()
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f).padding(vertical = 4.dp)) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 15.sp,
                fontWeight = titleWeight,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        if (trailing != null) {
            trailing()
        } else {
            Spacer(modifier = Modifier.width(MimikyuSpacing.md - MimikyuSpacing.xs))
        }
    }
}

@Composable
private fun LeadingIcon(iconRes: Int, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Box(modifier = Modifier.size(LeadingSize), contentAlignment = Alignment.Center) {
        Icon(painter = painterResource(iconRes), contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun LeadingIcon(icon: ImageVector, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Box(modifier = Modifier.size(LeadingSize), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun SuggestionCover(url: String?, fallbackIconRes: Int, circle: Boolean) {
    val shape = if (circle) CircleShape else RoundedCornerShape(RadiusCompact)
    val placeholder = @Composable {
        Box(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(painter = painterResource(fallbackIconRes), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
    Box(modifier = Modifier.size(LeadingSize).clip(shape)) {
        if (url.isNullOrBlank()) {
            placeholder()
        } else {
            SubcomposeAsyncImage(
                model = "$url?param=120y120",
                contentDescription = null,
                contentScale = ContentScale.Crop,
                loading = { placeholder() },
                error = { placeholder() },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

internal fun highlightMatch(text: String, query: String, style: SpanStyle): AnnotatedString {
    val needle = query.trim()
    if (needle.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        var start = 0
        while (start < text.length) {
            val index = text.indexOf(needle, startIndex = start, ignoreCase = true)
            if (index < 0) break
            append(text.substring(start, index))
            withStyle(style) { append(text.substring(index, index + needle.length)) }
            start = index + needle.length
        }
        if (start < text.length) append(text.substring(start))
    }
}
