package com.lin0721.linmusic.feature.search.domain

// Search，apiValue  cloudsearch  of  type （）
enum class SearchType(val apiValue: Int, val label: String) {
    ALL(0, "All"),
    SONG(1, "Songs"),
    ALBUM(10, "Albums"),
    ARTIST(100, "Artists"),
    PLAYLIST(1000, "Playlists")
}
