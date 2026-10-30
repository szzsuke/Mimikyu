package com.lin0721.linmusic.core.ui.model

import androidx.compose.ui.graphics.Color
import com.lin0721.linmusic.core.player.QueueItem
import com.lin0721.linmusic.core.ui.components.MimikyuCoverType
import com.lin0721.linmusic.core.ui.components.ThisIsCoverSpec

/**
 * A single generated Mimikyu-style mix, station, or playlist.
 */
data class DailyMixPlaylist(
    /** Virtual playlist ID */
    val id: Long,
    val title: String,
    val subtitle: String,
    val coverUrl: String,
    val coverType: MimikyuCoverType = MimikyuCoverType.IMAGE_URL,
    val primaryColor: Color = Color(0xFF1DB954),
    val mosaicImages: List<String> = emptyList(),
    val tracks: List<QueueItem> = emptyList(),
    val isArtistProfile: Boolean = false,
    val isAlbum: Boolean = false,
    val artistId: Long? = null,
    val seedQuery: String = "",
    val seedVideoId: String = ""
)

/**
 * Grouped shelf output consumed by Home screen and cache store.
 */
data class MimikyuShelvesResult(
    val topMixes: List<DailyMixPlaylist> = emptyList(),
    val recommendedStations: List<DailyMixPlaylist> = emptyList(),
    val bestOfArtists: List<DailyMixPlaylist> = emptyList(),
    val favoriteArtists: List<DailyMixPlaylist> = emptyList(),
    val moreLikeMixes: List<DailyMixPlaylist> = emptyList(),
    val moreLikeArtistName: String = "",
    val moreLikeArtistCover: String = "",
    val madeForYourVibe: List<ThisIsCoverSpec> = emptyList(),
    val albumsYouLike: List<DailyMixPlaylist> = emptyList(),
    val soundtrackYourDay: List<DailyMixPlaylist> = emptyList(),
    val soundtrackTitle: String = "Soundtrack your day",
    val popularShelves: List<DailyMixPlaylist> = emptyList(),
    val genreMixShelves: List<DailyMixPlaylist> = emptyList()
)
