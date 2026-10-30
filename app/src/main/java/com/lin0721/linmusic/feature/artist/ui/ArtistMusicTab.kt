package com.lin0721.linmusic.feature.artist.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import com.lin0721.linmusic.core.ui.theme.MimikyuIcons
import com.lin0721.linmusic.core.ui.theme.MimikyuYellow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lin0721.linmusic.core.ui.components.MimikyuIconButton
import com.lin0721.linmusic.core.model.Track
import com.lin0721.linmusic.core.ui.components.FilterChipsRow
import com.lin0721.linmusic.core.ui.components.SongRow
import com.lin0721.linmusic.core.ui.components.SongRowData
import com.lin0721.linmusic.core.ui.theme.MimikyuSpacing

private val MUSIC_SUB_TABS = listOf("Top 50", "All Songs")

// Tab: 「50」（） and 「AllSongs」（）
fun LazyListScope.artistMusicTab(
    hotSongs: List<Track>,
    allSongs: List<Track>,
    musicSubTab: Int,
    onMusicSubTabSelected: (Int) -> Unit,
    allSongsLoadingMore: Boolean,
    likedSongIds: Set<Long>,
    currentTrackId: String?,
    isPlaying: Boolean,
    isLoggedIn: Boolean,
    onPlaySong: (Track) -> Unit,
    onLikeClick: (Long) -> Unit,
    onOpenCollectSheet: (Long) -> Unit,
    onOpenMoreOptions: (Track) -> Unit,
    onRequireLogin: () -> Unit
) {
    item(key = "music_sub_tab") {
        FilterChipsRow(
            items = MUSIC_SUB_TABS,
            selectedIndex = musicSubTab,
            onSelected = onMusicSubTabSelected
        )
    }

    val songs = if (musicSubTab == 0) hotSongs else allSongs

    if (songs.isEmpty() && !(musicSubTab == 1 && allSongsLoadingMore)) {
        item(key = "empty_songs") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .background(MaterialTheme.colorScheme.background),
                contentAlignment = Alignment.Center
            ) {
                Text("No songs available", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
        }
    } else {
        itemsIndexed(songs, key = { _, track -> "${musicSubTab}_${track.id}" }) { index, track ->
            val isActive = track.id.toString() == currentTrackId
            SongRow(
                data = SongRowData(
                    id = track.id,
                    title = track.name,
                    artist = track.ar.joinToString(" • ") { it.name },
                    coverUrl = track.al.picUrl
                ),
                isActive = isActive,
                isPlaying = isPlaying,
                index = index + 1,
                onClick = { onPlaySong(track) },
                trailingSlot = {
                    val isLiked = track.id in likedSongIds
                    MimikyuIconButton(
                        onClick = {
                            if (!isLoggedIn) {
                                onRequireLogin()
                            } else {
                                onOpenCollectSheet(track.id)
                                onLikeClick(track.id)
                            }
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            painter = painterResource(if (isLiked) MimikyuIcons.HeartFilled else MimikyuIcons.Heart),
                            contentDescription = "Like song",
                            tint = if (isLiked) MimikyuYellow else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
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

        if (musicSubTab == 1 && allSongsLoadingMore) {
            item(key = "all_songs_loading_more") {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = MimikyuSpacing.md),
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
