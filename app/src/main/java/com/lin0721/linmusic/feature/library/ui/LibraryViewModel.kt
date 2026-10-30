package com.lin0721.linmusic.feature.library.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lin0721.linmusic.core.auth.UserPreferences
import com.lin0721.linmusic.core.auth.UserProfile
import com.lin0721.linmusic.core.auth.SyncProfileAfterLoginUseCase
import com.lin0721.linmusic.core.download.DownloadTrackInfo
import com.lin0721.linmusic.core.download.SongDownloadManager
import com.lin0721.linmusic.core.download.yearFromEpochMillis
import com.lin0721.linmusic.core.log.AppLogger
import com.lin0721.linmusic.feature.create.data.CreateRepository
import com.lin0721.linmusic.core.userplaylist.UserPlaylistRepository
import com.lin0721.linmusic.feature.library.data.LibraryRepository
import com.lin0721.linmusic.core.player.PlayerManager
import com.lin0721.linmusic.core.player.innertube.YouTubeIdRegistry
import com.lin0721.linmusic.core.playlistmutation.PlaylistMutationBus
import com.lin0721.linmusic.core.playlistmutation.PlaylistMutationEvent
import com.lin0721.linmusic.core.network.ResourceProvider
import com.lin0721.linmusic.core.network.toUserMessage
import com.lin0721.linmusic.feature.playlist.data.PlaylistRepository
import com.lin0721.linmusic.feature.artist.data.ArtistRepository
import com.lin0721.linmusic.core.songlike.LikedSongsRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.Collator
import java.util.Locale

private const val TAG = "LibraryViewModel"

// Collator ，Android  Locale.CHINA  of
private val zhCollator: Collator = Collator.getInstance(Locale.CHINA)

enum class LibraryItemType {
    PLAYLIST, ARTIST, ALBUM, MV
}

data class LibraryItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val coverUrl: String,
    val type: LibraryItemType,
    val isPinned: Boolean = false,
    val updateTime: Long = 0,
    val trackCount: Int = 0,
    val playCount: Long = 0,
    val isLikedSongs: Boolean = false,
    val isDownloadedSongs: Boolean = false,
    val isOwnedByMe: Boolean = false
)

enum class LibraryFilter {
    PLAYLIST, ALBUM, ARTIST
}

// Playlists： LibraryFilter.PLAYLIST ，Playlists and SavePlaylists
enum class LibraryPlaylistOwnerFilter {
    MINE, OTHERS
}

enum class LibrarySortOrder {
    RECENTLY_PLAYED, NAME, CUSTOM
}

class LibraryViewModel(
    private val syncProfileAfterLoginUseCase: SyncProfileAfterLoginUseCase,
    private val createRepository: CreateRepository,
    private val libraryRepository: LibraryRepository,
    private val playlistRepository: PlaylistRepository,
    private val artistRepository: ArtistRepository,
    private val userPlaylistRepository: UserPlaylistRepository,
    private val likedSongsRepository: LikedSongsRepository,
    private val userPreferences: UserPreferences,
    val playerManager: PlayerManager,
    private val context: Context,
    private val resourceProvider: ResourceProvider,
    private val playlistMutationBus: PlaylistMutationBus,
    private val songDownloadManager: SongDownloadManager,
    private val downloadPreferences: com.lin0721.linmusic.core.download.DownloadPreferences? = null
) : ViewModel() {

    private val sharedPrefs = context.getSharedPreferences("library_prefs", Context.MODE_PRIVATE)
    private val _pinnedIds = MutableStateFlow<Set<String>>(getPinnedIdsFromPrefs())

    private val _uiState = MutableStateFlow<LibraryUiState>(LibraryUiState.Loading)
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    private val _toastEvent = MutableSharedFlow<String>()
    val toastEvent: SharedFlow<String> = _toastEvent.asSharedFlow()

    val userProfile: StateFlow<UserProfile?> = userPreferences.userProfile.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = null
    )

    // null （All），
    private val _selectedFilter = MutableStateFlow<LibraryFilter?>(null)
    val selectedFilter: StateFlow<LibraryFilter?> = _selectedFilter.asStateFlow()

    // Playlists，/Reset
    private val _selectedPlaylistOwnerFilter = MutableStateFlow<LibraryPlaylistOwnerFilter?>(null)
    val selectedPlaylistOwnerFilter: StateFlow<LibraryPlaylistOwnerFilter?> = _selectedPlaylistOwnerFilter.asStateFlow()

    private val _sortOrder = MutableStateFlow(getSortOrderFromPrefs())
    val sortOrder: StateFlow<LibrarySortOrder> = _sortOrder.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _isGridView = MutableStateFlow(getGridViewFromPrefs())
    val isGridView: StateFlow<Boolean> = _isGridView.asStateFlow()

    init {
        viewModelScope.launch {
            userPreferences.userProfile.collect { profile ->
                loadLibraryData(profile)
            }
        }

        viewModelScope.launch {
            likedSongsRepository.likedSongs.collect {
                if (_uiState.value is LibraryUiState.Success) {
                    loadLibraryData()
                }
            }
        }

        viewModelScope.launch {
            downloadPreferences?.records?.collect {
                if (_uiState.value is LibraryUiState.Success) {
                    loadLibraryData()
                }
            }
        }

        viewModelScope.launch {
            playlistMutationBus.events.collect { event ->
                when (event) {
                    is PlaylistMutationEvent.Deleted -> {
                        val current = _uiState.value
                        if (current is LibraryUiState.Success) {
                            val idStr = event.playlistId.toString()
                            val updatedAll = current.allItems.filterNot { it.id == idStr && it.type == LibraryItemType.PLAYLIST }
                            _uiState.value = current.copy(allItems = updatedAll)
                            applyFilterAndSort()
                        }
                        loadLibraryData()
                    }
                    is PlaylistMutationEvent.Renamed -> {
                        val current = _uiState.value
                        if (current is LibraryUiState.Success) {
                            val idStr = event.playlistId.toString()
                            val updatedAll = current.allItems.map { item ->
                                if (item.id == idStr && item.type == LibraryItemType.PLAYLIST) {
                                    item.copy(title = event.newName)
                                } else {
                                    item
                                }
                            }
                            _uiState.value = current.copy(allItems = updatedAll)
                            applyFilterAndSort()
                        }
                        loadLibraryData()
                    }
                    is PlaylistMutationEvent.DescriptionUpdated -> {
                        loadLibraryData()
                    }
                    is PlaylistMutationEvent.CoverUpdated -> {
                        loadLibraryData()
                    }
                    is PlaylistMutationEvent.Created -> {
                        loadLibraryData()
                    }
                    is PlaylistMutationEvent.Updated -> {
                        loadLibraryData()
                    }
                }
            }
        }
    }

    private fun getPinnedIdsFromPrefs(): Set<String> {
        return sharedPrefs.getStringSet("pinned_ids", emptySet()) ?: emptySet()
    }

    private fun savePinnedIdsToPrefs(ids: Set<String>) {
        sharedPrefs.edit().putStringSet("pinned_ids", ids).apply()
    }

    private fun getCustomPlaylistOrderFromPrefs(): List<String> {
        val raw = sharedPrefs.getString("custom_playlist_order", "") ?: ""
        return if (raw.isBlank()) emptyList() else raw.split(",")
    }

    private fun saveCustomPlaylistOrderToPrefs(order: List<String>) {
        sharedPrefs.edit().putString("custom_playlist_order", order.joinToString(",")).apply()
    }

    private fun getSortOrderFromPrefs(): LibrarySortOrder {
        val raw = sharedPrefs.getString("sort_order", null)
        return raw?.let { runCatching { LibrarySortOrder.valueOf(it) }.getOrNull() } ?: LibrarySortOrder.RECENTLY_PLAYED
    }

    private fun saveSortOrderToPrefs(order: LibrarySortOrder) {
        sharedPrefs.edit().putString("sort_order", order.name).apply()
    }

    private fun getGridViewFromPrefs(): Boolean {
        // Grid view is default (true), with user preference persisting when switched to list
        return sharedPrefs.getBoolean("is_grid_view", true)
    }

    fun updateGridView(isGrid: Boolean) {
        _isGridView.value = isGrid
        sharedPrefs.edit().putBoolean("is_grid_view", isGrid).apply()
    }

    fun loadLibraryData(profileOverride: UserProfile? = null) {
        val profile = profileOverride ?: userProfile.value
        val isRefresh = _uiState.value is LibraryUiState.Success

        viewModelScope.launch {
            if (!isRefresh) {
                _uiState.value = LibraryUiState.Loading
            }

            try {
                // 1. Playlists
                val playlistsDeferred = async {
                    val uid = profile?.uid ?: 0L
                    val result = userPlaylistRepository.getUserPlaylists(uid).firstOrNull()
                    result?.exceptionOrNull()?.let { AppLogger.w(TAG, "Failed to fetch playlists", it) }
                    result?.getOrNull() ?: emptyList()
                }

                // 2. Artists: Legacy Chinese artists removed (no unrequested NetEase artist sublist)
                
                // 3. Albums
                val albumsDeferred = async {
                    val result = libraryRepository.getCollectedAlbums().firstOrNull()
                    result?.exceptionOrNull()?.let { AppLogger.w(TAG, "Failed to fetch saved albums", it) }
                    result?.getOrNull() ?: emptyList()
                }

                // 4. Save
                val subcountDeferred = async {
                    val result = libraryRepository.getUserSubcount().firstOrNull()
                    result?.exceptionOrNull()?.let { AppLogger.w(TAG, "Failed to fetch saved stats", it) }
                    result?.getOrNull()
                }

                val playlists = playlistsDeferred.await()
                val albums = albumsDeferred.await()
                val subcount = subcountDeferred.await()

                // Filter out NetEase liked songs playlist (we use Firestore Liked Songs) and Episodes for later
                val filteredPlaylists = playlists.filterIndexed { index, playlist ->
                    !(index == 0 && profile != null && playlist.creator?.userId == profile.uid) &&
                    !playlist.name.contains("Liked") &&
                    !playlist.name.contains("Episodes for later", ignoreCase = true)
                }

                // (Mapping)
                val mappedPlaylists = filteredPlaylists.map { playlist ->
                    LibraryItem(
                        id = playlist.id.toString(),
                        title = playlist.name,
                        subtitle = "Playlists · ${playlist.creator?.nickname ?: ""}",
                        coverUrl = playlist.coverImgUrl,
                        type = LibraryItemType.PLAYLIST,
                        updateTime = playlist.updateTime,
                        trackCount = playlist.trackCount,
                        playCount = playlist.playCount,
                        isLikedSongs = false,
                        isOwnedByMe = profile != null && playlist.creator?.userId == profile.uid
                    )
                }.toMutableList()

                // Prepend Firestore / Local Liked Songs entry
                val firestoreLikes = likedSongsRepository.likedSongs.value
                val localLikes = userPreferences.likedTracks.firstOrNull() ?: emptyList()
                val totalLikedCount = maxOf(firestoreLikes.size, localLikes.size)
                val likedSongsItem = LibraryItem(
                    id = "liked_songs",
                    title = "Liked Songs",
                    subtitle = "Playlist · $totalLikedCount songs",
                    coverUrl = "",
                    type = LibraryItemType.PLAYLIST,
                    updateTime = System.currentTimeMillis(),
                    trackCount = totalLikedCount,
                    playCount = 0,
                    isLikedSongs = true,
                    isOwnedByMe = true
                )
                mappedPlaylists.add(0, likedSongsItem)

                // Prepend Downloaded Songs entry (offline files)
                val downloadedList = downloadPreferences?.records?.firstOrNull() ?: emptyList()
                val downloadedSongsItem = LibraryItem(
                    id = "downloaded_songs",
                    title = "Downloaded Songs",
                    subtitle = "Playlist · ${downloadedList.size} songs",
                    coverUrl = "",
                    type = LibraryItemType.PLAYLIST,
                    updateTime = System.currentTimeMillis(),
                    trackCount = downloadedList.size,
                    playCount = 0,
                    isDownloadedSongs = true,
                    isOwnedByMe = true
                )
                mappedPlaylists.add(1, downloadedSongsItem)

                val mappedArtists = emptyList<LibraryItem>()

                val mappedAlbums = albums.map { album ->
                    LibraryItem(
                        id = album.id.toString(),
                        title = album.name,
                        subtitle = "Albums · ${album.artists.joinToString(" • ") { it.name }}",
                        coverUrl = album.picUrl,
                        type = LibraryItemType.ALBUM,
                        updateTime = album.subTime
                    )
                }

                val combinedItems = mappedPlaylists + mappedArtists + mappedAlbums

                _uiState.update { state ->
                    LibraryUiState.Success(
                        allItems = combinedItems,
                        filteredItems = (state as? LibraryUiState.Success)?.filteredItems ?: emptyList(),
                        artistCount = 0,
                        playlistCount = subcount?.playlistCount ?: playlists.size,
                        albumCount = subcount?.albumCount ?: albums.size
                    )
                }

                applyFilterAndSort()

            } catch (e: Exception) {
                AppLogger.e(TAG, "Music library failed to load: isRefresh=$isRefresh", e)
                if (isRefresh) {
                    _toastEvent.emit(e.toUserMessage(resourceProvider))
                } else {
                    _uiState.value = LibraryUiState.Error(e.toUserMessage(resourceProvider))
                }
            }
        }
    }

    private fun applyFilterAndSort() {
        val state = _uiState.value as? LibraryUiState.Success ?: return
        val pinned = _pinnedIds.value
        val filter = _selectedFilter.value
        val ownerFilter = _selectedPlaylistOwnerFilter.value
        val sort = _sortOrder.value
        val query = _searchQuery.value

        var list = state.allItems.map { item ->
            item.copy(isPinned = pinned.contains(item.id))
        }

        if (query.isNotBlank()) {
            list = list.filter {
                it.title.contains(query, ignoreCase = true) ||
                it.subtitle.contains(query, ignoreCase = true)
            }
        }

        list = when (filter) {
            null -> list.filter { it.type != LibraryItemType.MV }
            LibraryFilter.PLAYLIST -> list.filter { it.type == LibraryItemType.PLAYLIST }
            LibraryFilter.ALBUM -> list.filter { it.type == LibraryItemType.ALBUM }
            LibraryFilter.ARTIST -> list.filter { it.type == LibraryItemType.ARTIST }
        }

        // Playlists：Created by me / Create of （"Liked Songs" and "Top Tracks" of ）
        if (filter == LibraryFilter.PLAYLIST && ownerFilter != null) {
            list = list.filter { item ->
                val ownedByMe = item.isOwnedByMe || item.isLikedSongs || item.isDownloadedSongs || item.id == "-2"
                if (ownerFilter == LibraryPlaylistOwnerFilter.MINE) ownedByMe else !ownedByMe
            }
        }

        val pinnedItems = list.filter { it.isPinned }
        val unpinnedItems = list.filter { !it.isPinned }

        val customPlaylistOrder = getCustomPlaylistOrderFromPrefs()
        val customOrderMap = customPlaylistOrder.mapIndexed { index, id -> id to index }.toMap()

        val sortedUnpinned = when (sort) {
            LibrarySortOrder.RECENTLY_PLAYED -> {
                unpinnedItems.sortedByDescending { it.updateTime }
            }
            LibrarySortOrder.NAME -> {
                unpinnedItems.sortedWith(compareBy(zhCollator) { it.title })
            }
            LibrarySortOrder.CUSTOM -> {
                // Custom of （Playlists、 of ），
                unpinnedItems.sortedWith(compareBy<LibraryItem> { item ->
                    if (item.type == LibraryItemType.PLAYLIST) {
                        customOrderMap[item.id] ?: -1
                    } else {
                        Int.MAX_VALUE
                    }
                }.thenByDescending { it.updateTime })
            }
        }

        val finalList = pinnedItems + sortedUnpinned

        _uiState.update { current ->
            if (current is LibraryUiState.Success) current.copy(filteredItems = finalList) else current
        }
    }

    fun togglePin(itemId: String) {
        val currentPinned = _pinnedIds.value.toMutableSet()
        val willPin = !currentPinned.contains(itemId)
        if (willPin) {
            currentPinned.add(itemId)
        } else {
            currentPinned.remove(itemId)
        }
        _pinnedIds.value = currentPinned
        savePinnedIdsToPrefs(currentPinned)
        applyFilterAndSort()

        val title = (_uiState.value as? LibraryUiState.Success)?.allItems?.firstOrNull { it.id == itemId }?.title
        if (title != null) {
            viewModelScope.launch {
                _toastEvent.emit("${if (willPin) "Pinned" else "Unpinned"}: $title")
            }
        }
    }

    fun createPlaylist(name: String, tracks: List<com.lin0721.linmusic.core.model.Track> = emptyList()) {
        viewModelScope.launch {
            val localStore = org.koin.core.context.GlobalContext.get().getOrNull<com.lin0721.linmusic.core.userplaylist.LocalCustomPlaylistStore>()
            if (localStore != null) {
                localStore.createPlaylist(name, tracks)
                loadLibraryData()
                _toastEvent.emit("Playlist \"$name\" created with ${tracks.size} songs!")
            } else {
                createRepository.createPlaylist(name).collect { result ->
                    result.onSuccess {
                        loadLibraryData()
                        _toastEvent.emit("Playlist created successfully!")
                    }.onFailure { e ->
                        _toastEvent.emit(e.toUserMessage(resourceProvider))
                    }
                }
            }
        }
    }

    // ： of Cancel，All；ResetPlaylists
    fun toggleFilter(filter: LibraryFilter) {
        _selectedFilter.value = if (_selectedFilter.value == filter) null else filter
        _selectedPlaylistOwnerFilter.value = null
        applyFilterAndSort()
    }

    fun clearFilter() {
        _selectedFilter.value = null
        _selectedPlaylistOwnerFilter.value = null
        applyFilterAndSort()
    }

    // Playlists： of Cancel，AllPlaylists
    fun togglePlaylistOwnerFilter(filter: LibraryPlaylistOwnerFilter) {
        _selectedPlaylistOwnerFilter.value = if (_selectedPlaylistOwnerFilter.value == filter) null else filter
        applyFilterAndSort()
    }

    fun updateSortOrder(order: LibrarySortOrder) {
        _sortOrder.value = order
        saveSortOrderToPrefs(order)
        applyFilterAndSort()
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
        applyFilterAndSort()
    }

    fun handleLoginSuccess(cookies: String) {
        viewModelScope.launch {
            _toastEvent.emit("Logged in successfully, syncing library...")
            val profile = syncProfileAfterLoginUseCase(cookies) ?: return@launch
            loadLibraryData(profile)
        }
    }

    fun savePlaylistOrder(orderedItems: List<LibraryItem>) {
        val playlistIds = orderedItems.filter { it.type == LibraryItemType.PLAYLIST }.map { it.id }
        saveCustomPlaylistOrderToPrefs(playlistIds)
        _sortOrder.value = LibrarySortOrder.CUSTOM
        saveSortOrderToPrefs(LibrarySortOrder.CUSTOM)
        applyFilterAndSort()
        viewModelScope.launch {
            _toastEvent.emit("Playlist order saved")
        }
    }

    // of ：Save of Custom， of
    fun getPlaylistsForReorder(): List<LibraryItem> {
        val state = _uiState.value as? LibraryUiState.Success ?: return emptyList()
        val playlists = state.allItems.filter { it.type == LibraryItemType.PLAYLIST }
        val customOrderMap = getCustomPlaylistOrderFromPrefs().mapIndexed { index, id -> id to index }.toMap()
        return playlists.sortedWith(
            compareBy<LibraryItem> { customOrderMap[it.id] ?: -1 }.thenByDescending { it.updateTime }
        )
    }

    fun deletePlaylist(playlistId: Long) {
        viewModelScope.launch {
            playlistRepository.deletePlaylist(playlistId).collect { result ->
                result.onSuccess {
                    _toastEvent.emit("Playlist deleted")
                    playlistMutationBus.emit(PlaylistMutationEvent.Deleted(playlistId))
                    loadLibraryData()
                }.onFailure { e ->
                    _toastEvent.emit(e.toUserMessage(resourceProvider))
                }
            }
        }
    }

    fun unsubscribePlaylist(playlistId: Long) {
        viewModelScope.launch {
            playlistRepository.subscribePlaylist(playlistId, subscribe = false).collect { result ->
                result.onSuccess {
                    _toastEvent.emit("Removed playlist from favorites")
                    loadLibraryData()
                }.onFailure { e ->
                    _toastEvent.emit(e.toUserMessage(resourceProvider))
                }
            }
        }
    }

    fun unsubscribeAlbum(albumId: Long) {
        viewModelScope.launch {
            playlistRepository.subscribeAlbum(albumId, subscribe = false).collect { result ->
                result.onSuccess {
                    _toastEvent.emit("Removed album from favorites")
                    loadLibraryData()
                }.onFailure { e ->
                    _toastEvent.emit(e.toUserMessage(resourceProvider))
                }
            }
        }
    }

    fun unsubscribeArtist(artistId: Long) {
        viewModelScope.launch {
            artistRepository.subscribeArtist(artistId, subscribe = false).collect { result ->
                result.onSuccess {
                    _toastEvent.emit("Unfollowed artist")
                    loadLibraryData()
                }.onFailure { e ->
                    _toastEvent.emit(e.toUserMessage(resourceProvider))
                }
            }
        }
    }

    // DownloadPlaylistsAlbums
    fun downloadLibraryItem(item: LibraryItem, level: String) {
        val id = item.id.toLongOrNull()
        if (id == null) {
            viewModelScope.launch { _toastEvent.emit("Playlist/album info incomplete, unable to download") }
            return
        }
        val isAlbum = item.type == LibraryItemType.ALBUM
        val detailFlow = if (isAlbum) playlistRepository.getAlbumDetail(id) else playlistRepository.getPlaylistDetail(id)
        viewModelScope.launch {
            detailFlow.collect { result ->
                result.onSuccess { detail ->
                    if (detail.tracks.isEmpty()) {
                        _toastEvent.emit(if (isAlbum) "No downloadable songs in this album" else "No downloadable songs in this playlist")
                    } else {
                        val tracks = detail.tracks.map { track ->
                            DownloadTrackInfo(
                                track.id, track.name, track.ar.joinToString("/") { it.name },
                                track.al.name, track.al.picUrl.takeIf { it.isNotBlank() },
                                yearFromEpochMillis(track.publishTime),
                                videoId = YouTubeIdRegistry.getVideoId(track.id)
                            )
                        }
                        songDownloadManager.enqueueBatch(
                            tracks, level, batchTag = "library_${item.type}_$id", batchLabel = item.title
                        )
                        _toastEvent.emit("Added ${tracks.size} songs to download queue")
                    }
                }.onFailure { e ->
                    _toastEvent.emit(e.toUserMessage(resourceProvider))
                }
            }
        }
    }
}
