package com.lin0721.linmusic.feature.search.domain

import com.lin0721.linmusic.core.model.Album
import com.lin0721.linmusic.core.model.Artist
import com.lin0721.linmusic.core.model.PlaylistDetail
import com.lin0721.linmusic.core.model.Track

// Search， SearchType  of ，
sealed interface SearchResultItem {
    data class SongItem(val track: Track) : SearchResultItem
    data class AlbumItem(val album: Album) : SearchResultItem
    data class ArtistItem(val artist: Artist) : SearchResultItem
    data class PlaylistItem(val playlist: PlaylistDetail) : SearchResultItem
}

val SearchResultItem.stableKey: String
    get() = when (this) {
        is SearchResultItem.SongItem -> "song_${track.id}"
        is SearchResultItem.AlbumItem -> "album_${album.id}"
        is SearchResultItem.ArtistItem -> "artist_${artist.id}"
        is SearchResultItem.PlaylistItem -> "playlist_${playlist.id}"
    }

// SearchType  of Search
data class SearchPageResult(
    val items: List<SearchResultItem>,
    val totalCount: Int,
    val hasMore: Boolean,
    // of （）， offset ，
    // of  items.size ，Block Artist of  of
    val rawFetchedCount: Int
)
