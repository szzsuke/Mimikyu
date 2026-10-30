package com.lin0721.linmusic.feature.search.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lin0721.linmusic.core.auth.SyncProfileAfterLoginUseCase
import com.lin0721.linmusic.core.auth.UserPreferences
import com.lin0721.linmusic.core.auth.UserProfile
import com.lin0721.linmusic.core.model.Track
import com.lin0721.linmusic.core.network.ResourceProvider
import com.lin0721.linmusic.core.network.toUserMessage
import com.lin0721.linmusic.core.player.PlayerManager
import com.lin0721.linmusic.core.player.QueueItem
import com.lin0721.linmusic.core.songlike.LoadLikedSongIdsUseCase
import com.lin0721.linmusic.core.songlike.SongLikeRepository
import com.lin0721.linmusic.core.ui.components.PlaylistCollectItem
import com.lin0721.linmusic.core.ui.components.PlaylistCollectState
import com.lin0721.linmusic.feature.playlist.domain.SongCollectDelegate
import com.lin0721.linmusic.feature.search.data.RecentSearchItem
import com.lin0721.linmusic.feature.search.data.SearchHistoryPreferences
import com.lin0721.linmusic.feature.search.data.SearchRepository
import com.lin0721.linmusic.feature.search.domain.SearchResultItem
import com.lin0721.linmusic.feature.search.domain.SearchType
import com.lin0721.linmusic.feature.search.domain.stableKey
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

import com.lin0721.linmusic.feature.search.domain.SongIdentifierEngine

class SearchViewModel(
    private val repository: SearchRepository,
    private val historyPreferences: SearchHistoryPreferences,
    val playerManager: PlayerManager,
    userPreferences: UserPreferences,
    private val resourceProvider: ResourceProvider,
    private val songCollectDelegate: SongCollectDelegate,
    private val loadLikedSongIdsUseCase: LoadLikedSongIdsUseCase,
    private val songLikeRepository: SongLikeRepository,
    private val syncProfileAfterLoginUseCase: SyncProfileAfterLoginUseCase,
    private val tasteSignalScorer: com.lin0721.linmusic.core.player.recommendation.TasteSignalScorer? = null
) : ViewModel() {

    val songIdentifierEngine: SongIdentifierEngine by lazy { SongIdentifierEngine(repository) }

    val userProfile: StateFlow<UserProfile?> = userPreferences.userProfile
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // Search of Songs and Save， Artist/Playlist
    val likedSongIds: StateFlow<Set<Long>> = songLikeRepository.likedSongIds

    val collectState: StateFlow<PlaylistCollectState> = songCollectDelegate.state

    val history: StateFlow<List<String>> = historyPreferences.history
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentItems: StateFlow<List<RecentSearchItem>> = historyPreferences.recentItems
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _discoveryState = MutableStateFlow<DiscoveryUiState>(DiscoveryUiState.Loading)
    val discoveryState: StateFlow<DiscoveryUiState> = _discoveryState.asStateFlow()

    private val _inputState = MutableStateFlow(SearchInputState())
    val inputState: StateFlow<SearchInputState> = _inputState.asStateFlow()

    private val _mode = MutableStateFlow(SearchMode.Discovery)
    val mode: StateFlow<SearchMode> = _mode.asStateFlow()

    // of Tracks， and Play
    private val _featuredTrack = MutableStateFlow<Track?>(null)
    val featuredTrack: StateFlow<Track?> = _featuredTrack.asStateFlow()

    private val _selectedType = MutableStateFlow(SearchType.ALL)
    val selectedType: StateFlow<SearchType> = _selectedType.asStateFlow()

    private val _placeholderInfo = MutableStateFlow<com.lin0721.linmusic.feature.search.domain.SearchPlaceholderInfo?>(null)
    val placeholderInfo: StateFlow<com.lin0721.linmusic.feature.search.domain.SearchPlaceholderInfo?> = _placeholderInfo.asStateFlow()

    fun updatePlaceholder(info: com.lin0721.linmusic.feature.search.domain.SearchPlaceholderInfo) {
        _placeholderInfo.value = info
    }

    private val _resultsByType: Map<SearchType, MutableStateFlow<SearchResultsUiState>> =
        SearchType.entries.associateWith { MutableStateFlow(SearchResultsUiState.Idle) }
    val resultsByType: Map<SearchType, StateFlow<SearchResultsUiState>> = _resultsByType

    private val _toastEvent = MutableSharedFlow<String>()
    val toastEvent: SharedFlow<String> = _toastEvent.asSharedFlow()

    // Tab  of  offset，Back（ SearchPageResult.rawFetchedCount ）
    private val offsetByType = mutableMapOf<SearchType, Int>()
    private var searchJob: Job? = null
    private var suggestJob: Job? = null

    init {
        loadDiscoveryData()
        loadLikedSongIds()
    }

    fun loadLikedSongIds() {
        viewModelScope.launch {
            loadLikedSongIdsUseCase()
        }
    }

    // NextPlay
    fun addTrackToPlayNext(track: Track) {
        val queueItem = QueueItem(track.id, track.name, track.ar.joinToString("/") { it.name }, track.al.picUrl)
        playerManager.addToPlayNext(listOf(queueItem))
        viewModelScope.launch { _toastEvent.emit("Added to play next") }
    }

    fun prepareCollectDialog(songId: Long) {
        viewModelScope.launch {
            songCollectDelegate.prepare(songId, songLikeRepository.likedSongIds.value) { _toastEvent.emit(it) }
        }
    }

    fun savePlaylistCollection(songId: Long, items: List<PlaylistCollectItem>) {
        viewModelScope.launch {
            songCollectDelegate.save(
                songId = songId,
                items = items,
                likedSongIds = songLikeRepository.likedSongIds.value,
                onToast = { _toastEvent.emit(it) },
                onLikedChanged = { newLiked ->
                    songLikeRepository.syncLikedSongIds(newLiked)
                }
            )
        }
    }

    fun createPlaylistAndAddSong(name: String, songId: Long) {
        viewModelScope.launch {
            songCollectDelegate.createAndAdd(name, songId, songLikeRepository.likedSongIds.value) { _toastEvent.emit(it) }
        }
    }

    // Songs"Like"，「More Options」（ and  of Save）
    fun toggleLikeSong(songId: Long, like: Boolean) {
        viewModelScope.launch {
            songLikeRepository.likeSong(songId, like).collect { result ->
                result.onSuccess {
                    _toastEvent.emit(if (like) "Added to Liked Songs" else "Removed from Liked Songs")
                }.onFailure { e ->
                    _toastEvent.emit(e.toUserMessage(resourceProvider))
                }
            }
        }
    }

    fun handleLoginSuccess(cookies: String) {
        viewModelScope.launch {
            if (syncProfileAfterLoginUseCase(cookies) == null) return@launch
            _toastEvent.emit("Logged in successfully, syncing data...")
            loadLikedSongIds()
        }
    }

    private fun loadDiscoveryData() {
        viewModelScope.launch {
            _discoveryState.value = DiscoveryUiState.Loading

            val keywordDeferred = async { repository.getDefaultSearchKeyword().firstOrNull() }
            val hotSearchDeferred = async { repository.getHotSearches().firstOrNull() }
            val tagsDeferred = async { repository.getPlaylistTags().firstOrNull() }

            val keywordResult = keywordDeferred.await()
            val hotSearchResult = hotSearchDeferred.await()
            val tagsResult = tagsDeferred.await()

            val failures = listOfNotNull(keywordResult, hotSearchResult, tagsResult).mapNotNull { it.exceptionOrNull() }
            val allFailed = keywordResult?.getOrNull() == null &&
                hotSearchResult?.getOrNull() == null &&
                tagsResult?.getOrNull() == null

            if (allFailed && failures.isNotEmpty()) {
                _discoveryState.value = DiscoveryUiState.Error(failures.first().toUserMessage(resourceProvider))
                return@launch
            }

            val hotSearches = hotSearchResult?.getOrNull() ?: emptyList()
            _discoveryState.value = DiscoveryUiState.Success(
                defaultKeyword = keywordResult?.getOrNull() ?: "Search for music",
                hotSearches = hotSearches,
                playlistTags = tagsResult?.getOrNull() ?: emptyList()
            )
            failures.firstOrNull()?.let { _toastEvent.emit(it.toUserMessage(resourceProvider)) }
            hotSearches.firstOrNull()?.let { loadFeaturedTrack(it.keyword) }
        }
    }

    private suspend fun loadFeaturedTrack(keyword: String) {
        _featuredTrack.value = null
        repository.search(keyword, SearchType.SONG, offset = 0, limit = 1).firstOrNull()
            ?.getOrNull()
            ?.items
            ?.firstNotNullOfOrNull { (it as? SearchResultItem.SongItem)?.track }
            ?.let { _featuredTrack.value = it }
    }

    // Failed to load of Retry，UI
    fun retryDiscovery() {
        loadDiscoveryData()
    }

    // SearchFailed to load of Retry：Current Tab Current，
    fun retrySearch() {
        val type = _selectedType.value
        val keyword = _inputState.value.query
        if (keyword.isBlank()) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch { runSearch(keyword, type, isLoadMore = false) }
    }

    fun activateSearch() {
        if (_mode.value == SearchMode.Discovery) _mode.value = SearchMode.Typing
    }

    // ，
    fun editQuery() {
        if (_mode.value != SearchMode.Results) return
        _mode.value = SearchMode.Typing
        requestSuggestions(_inputState.value.query)
    }

    fun cancelSearch() {
        _mode.value = SearchMode.Discovery
        resetSearchState()
    }

    private fun resetSearchState() {
        searchJob?.cancel()
        suggestJob?.cancel()
        _inputState.value = SearchInputState()
        offsetByType.clear()
        _resultsByType.values.forEach { it.value = SearchResultsUiState.Idle }
    }

    // ，Search
    fun updateQuery(newQuery: String) {
        _inputState.value = _inputState.value.copy(query = newQuery)
        if (_mode.value == SearchMode.Results) _mode.value = SearchMode.Typing
        requestSuggestions(newQuery)
    }

    private fun requestSuggestions(query: String) {
        suggestJob?.cancel()
        if (query.isBlank()) {
            _inputState.value = _inputState.value.copy(suggestions = emptyList(), suggestionQuery = "")
            return
        }
        suggestJob = viewModelScope.launch {
            delay(300)
            repository.getSuggestions(query).firstOrNull()?.onSuccess { suggestions ->
                _inputState.value = _inputState.value.copy(suggestions = suggestions, suggestionQuery = query)
            }
        }
    }

    // Search：
    fun searchWithKeyword(keyword: String) {
        if (keyword.isBlank()) return
        searchJob?.cancel()
        suggestJob?.cancel()
        _mode.value = SearchMode.Results
        _inputState.value = SearchInputState(query = keyword)
        viewModelScope.launch { historyPreferences.addKeyword(keyword) }

        // ， Tab  of ，
        _resultsByType.forEach { (type, state) ->
            if (type != _selectedType.value) state.value = SearchResultsUiState.Idle
        }
        offsetByType.clear()

        searchJob = viewModelScope.launch {
            runSearch(keyword, _selectedType.value, isLoadMore = false)
        }
    }

    fun selectType(type: SearchType) {
        if (_selectedType.value == type) return
        _selectedType.value = type
        val query = _inputState.value.query
        if (query.isNotBlank() && _resultsByType.getValue(type).value is SearchResultsUiState.Idle) {
            searchJob?.cancel()
            searchJob = viewModelScope.launch { runSearch(query, type, isLoadMore = false) }
        }
    }

    fun loadMore() {
        val type = _selectedType.value
        val keyword = _inputState.value.query
        if (keyword.isBlank()) return
        val current = _resultsByType.getValue(type).value
        if (current !is SearchResultsUiState.Success || current.isLoadingMore || !current.hasMore) return

        searchJob?.cancel()
        searchJob = viewModelScope.launch { runSearch(keyword, type, isLoadMore = true) }
    }

    private suspend fun runSearch(keyword: String, type: SearchType, isLoadMore: Boolean) {
        val stateFlow = _resultsByType.getValue(type)
        if (isLoadMore) {
            val current = stateFlow.value
            if (current !is SearchResultsUiState.Success || current.isLoadingMore || !current.hasMore) return
            stateFlow.value = current.copy(isLoadingMore = true)
        } else {
            offsetByType[type] = 0
            stateFlow.value = SearchResultsUiState.Loading
        }

        val offset = if (isLoadMore) offsetByType[type] ?: 0 else 0
        repository.search(keyword, type, offset = offset).firstOrNull()?.let { result ->
            result.onSuccess { page ->
                offsetByType[type] = offset + page.rawFetchedCount
                val mergedItems = if (isLoadMore) {
                    val existing = (stateFlow.value as? SearchResultsUiState.Success)?.items.orEmpty()
                    val existingKeys = existing.map { it.stableKey }.toHashSet()
                    existing + page.items.filter { existingKeys.add(it.stableKey) }
                } else {
                    page.items.distinctBy { it.stableKey }
                }
                if (!isLoadMore && mergedItems.isNotEmpty()) {
                    when (val first = mergedItems.firstOrNull()) {
                        is SearchResultItem.SongItem -> {
                            val cover = first.track.al.picUrl
                            if (cover.isNotBlank()) {
                                _placeholderInfo.value = com.lin0721.linmusic.feature.search.domain.SearchPlaceholderInfo(
                                    title = first.track.name,
                                    subtitle = first.track.ar.firstOrNull()?.name.orEmpty(),
                                    imageUrl = cover,
                                    isArtist = false
                                )
                            }
                        }
                        is SearchResultItem.AlbumItem -> {
                            val cover = first.album.picUrl
                            if (cover.isNotBlank()) {
                                _placeholderInfo.value = com.lin0721.linmusic.feature.search.domain.SearchPlaceholderInfo(
                                    title = first.album.name,
                                    subtitle = first.album.artists.firstOrNull()?.name.orEmpty(),
                                    imageUrl = cover,
                                    isArtist = false
                                )
                            }
                        }
                        is SearchResultItem.ArtistItem -> {
                            val avatar = first.artist.picUrl.ifBlank { first.artist.img1v1Url }
                            if (avatar.isNotBlank()) {
                                _placeholderInfo.value = com.lin0721.linmusic.feature.search.domain.SearchPlaceholderInfo(
                                    title = first.artist.name,
                                    imageUrl = avatar,
                                    isArtist = true
                                )
                            }
                        }
                        else -> {}
                    }
                }
                stateFlow.value = if (mergedItems.isEmpty()) {
                    SearchResultsUiState.Empty
                } else {
                    SearchResultsUiState.Success(
                        items = mergedItems,
                        totalCount = page.totalCount,
                        hasMore = page.hasMore,
                        isLoadingMore = false
                    )
                }
            }.onFailure { error ->
                val current = stateFlow.value
                if (isLoadMore && current is SearchResultsUiState.Success) {
                    stateFlow.value = current.copy(isLoadingMore = false)
                    _toastEvent.emit(error.toUserMessage(resourceProvider))
                } else {
                    stateFlow.value = SearchResultsUiState.Error(error.toUserMessage(resourceProvider))
                }
            }
        }
    }

    fun clearHistory() {
        viewModelScope.launch { historyPreferences.clear() }
    }

    fun removeHistory(keyword: String) {
        viewModelScope.launch { historyPreferences.removeKeyword(keyword) }
    }

    fun removeRecentItem(id: String) {
        viewModelScope.launch { historyPreferences.removeRecentItem(id) }
    }

    fun addRecentItem(item: RecentSearchItem) {
        viewModelScope.launch { historyPreferences.addRecentItem(item) }
    }

    fun playFeaturedTrack() {
        val track = _featuredTrack.value ?: return
        val item = QueueItem(track.id, track.name, track.ar.joinToString { it.name }, track.al.picUrl)
        viewModelScope.launch {
            tasteSignalScorer?.recordSearched(
                songId = item.songId,
                title = item.title,
                artist = item.artist,
                coverUrl = item.coverUrl
            )
        }
        playerManager.playWithDynamicRadio(item, "Search Featured")
    }

    fun playSong(track: Track) {
        val item = QueueItem(
            songId = track.id,
            title = track.name,
            artist = track.ar.joinToString { it.name },
            coverUrl = track.al.picUrl
        )
        viewModelScope.launch {
            historyPreferences.addRecentItem(
                RecentSearchItem(
                    id = "song_${track.id}",
                    title = track.name,
                    subtitle = "Song • " + track.ar.joinToString { it.name },
                    imageUrl = track.al.picUrl,
                    type = com.lin0721.linmusic.feature.search.data.RecentItemType.SONG,
                    rawId = track.id,
                    artistName = track.ar.joinToString { it.name }
                )
            )
            tasteSignalScorer?.recordSearched(
                songId = item.songId,
                title = item.title,
                artist = item.artist,
                coverUrl = item.coverUrl
            )
        }
        playerManager.playWithDynamicRadio(item, "Search")
    }

    fun playSongMatch(suggestion: com.lin0721.linmusic.feature.search.domain.SearchSuggestion.SongMatch) {
        val songId = suggestion.id
        val item = QueueItem(
            songId = songId,
            title = suggestion.text,
            artist = suggestion.artistName,
            coverUrl = suggestion.coverUrl
        )
        viewModelScope.launch {
            historyPreferences.addRecentItem(
                RecentSearchItem(
                    id = "song_$songId",
                    title = suggestion.text,
                    subtitle = "Song • " + suggestion.artistName,
                    imageUrl = suggestion.coverUrl,
                    type = com.lin0721.linmusic.feature.search.data.RecentItemType.SONG,
                    rawId = songId,
                    artistName = suggestion.artistName
                )
            )
            tasteSignalScorer?.recordSearched(
                songId = item.songId,
                title = item.title,
                artist = item.artist,
                coverUrl = item.coverUrl
            )
        }
        playerManager.playWithDynamicRadio(item, "Search Recommendation")
    }

    fun recordArtistClick(artistId: Long, name: String, avatarUrl: String) {
        viewModelScope.launch {
            historyPreferences.addRecentItem(
                RecentSearchItem(
                    id = "artist_$artistId",
                    title = name,
                    subtitle = "Artist",
                    imageUrl = avatarUrl,
                    type = com.lin0721.linmusic.feature.search.data.RecentItemType.ARTIST,
                    rawId = artistId
                )
            )
        }
    }

    fun recordAlbumClick(albumId: Long, name: String, artistName: String, coverUrl: String) {
        viewModelScope.launch {
            historyPreferences.addRecentItem(
                RecentSearchItem(
                    id = "album_$albumId",
                    title = name,
                    subtitle = if (artistName.isNotBlank()) "Album • $artistName" else "Album",
                    imageUrl = coverUrl,
                    type = com.lin0721.linmusic.feature.search.data.RecentItemType.ALBUM,
                    rawId = albumId,
                    artistName = artistName
                )
            )
        }
    }

    fun recordPlaylistClick(playlistId: Long, name: String, creator: String, coverUrl: String) {
        viewModelScope.launch {
            historyPreferences.addRecentItem(
                RecentSearchItem(
                    id = "playlist_$playlistId",
                    title = name,
                    subtitle = if (creator.isNotBlank()) "Playlist • $creator" else "Playlist",
                    imageUrl = coverUrl,
                    type = com.lin0721.linmusic.feature.search.data.RecentItemType.PLAYLIST,
                    rawId = playlistId
                )
            )
        }
    }

    fun playRecentSong(recentItem: RecentSearchItem) {
        val item = QueueItem(
            songId = recentItem.rawId,
            title = recentItem.title,
            artist = recentItem.artistName.ifBlank { recentItem.subtitle.removePrefix("Song • ") },
            coverUrl = recentItem.imageUrl
        )
        viewModelScope.launch {
            tasteSignalScorer?.recordSearched(
                songId = item.songId,
                title = item.title,
                artist = item.artist,
                coverUrl = item.coverUrl
            )
        }
        playerManager.playWithDynamicRadio(item, "Search")
    }
}
