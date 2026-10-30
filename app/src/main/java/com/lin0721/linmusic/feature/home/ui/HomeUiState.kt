package com.lin0721.linmusic.feature.home.ui

import com.lin0721.linmusic.feature.home.data.PersonalizedPlaylist
import com.lin0721.linmusic.feature.home.data.DailySong
import com.lin0721.linmusic.feature.recent.domain.RecentPlaylist
import com.lin0721.linmusic.feature.home.domain.HomeShelf
import com.lin0721.linmusic.feature.home.domain.ToplistInfo

// UI
sealed interface HomeUiState {
    data object Loading : HomeUiState

    // Success state
    data class Success(val data: HomeFeedData) : HomeUiState

    data class Error(val message: String) : HomeUiState
}

// Home feed data model
data class HomeFeedData(
    val shelves: List<HomeShelf> = emptyList(),
    val recommendPlaylists: List<PersonalizedPlaylist> = emptyList(),
    val recentPlaylists: List<RecentPlaylist> = emptyList(),
    val dailySongs: List<DailySong> = emptyList(),
    val toplistItems: List<ToplistInfo> = emptyList(),
    // Pagination
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
    val isLoadingMore: Boolean = false
)
