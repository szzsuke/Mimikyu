package com.lin0721.linmusic.feature.home.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lin0721.linmusic.core.auth.AuthRepository
import com.lin0721.linmusic.core.auth.UserPreferences
import com.lin0721.linmusic.core.auth.UserProfile
import com.lin0721.linmusic.core.api.AccountInfoResponse
import com.lin0721.linmusic.core.log.AppLogger
import com.lin0721.linmusic.feature.home.data.DailySong
import com.lin0721.linmusic.core.auth.SyncProfileAfterLoginUseCase
import com.lin0721.linmusic.feature.home.data.HomeRepository
import com.lin0721.linmusic.feature.recent.data.RecentRepository
import com.lin0721.linmusic.feature.home.data.PersonalizedData
import com.lin0721.linmusic.feature.home.domain.HomeCard
import com.lin0721.linmusic.core.player.data.PlaybackRepository
import com.lin0721.linmusic.feature.home.domain.ToplistInfo
import com.lin0721.linmusic.R
import com.lin0721.linmusic.core.model.Track
import com.lin0721.linmusic.core.network.ResourceProvider
import com.lin0721.linmusic.core.network.toUserMessage
import com.lin0721.linmusic.core.songlike.LikedSong
import com.lin0721.linmusic.core.songlike.LikedSongsRepository
import com.lin0721.linmusic.core.songlike.toLikedSong
import com.lin0721.linmusic.core.player.innertube.YouTubeIdRegistry
import com.lin0721.linmusic.core.model.Album
import com.lin0721.linmusic.core.model.Artist
import com.lin0721.linmusic.core.playlistmutation.PlaylistMutationBus
import com.lin0721.linmusic.core.playlistmutation.PlaylistMutationEvent
import com.lin0721.linmusic.core.songlike.SongLikeRepository
import com.lin0721.linmusic.core.ui.components.PlaylistCollectItem
import com.lin0721.linmusic.core.ui.components.PlaylistCollectState
import com.lin0721.linmusic.feature.playlist.domain.SongCollectDelegate
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.lin0721.linmusic.core.player.PlayerManager
import com.lin0721.linmusic.core.player.QueueItem

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.lin0721.linmusic.feature.home.data.cache.HomeShelfCacheStore

private const val TAG = "HomeViewModel"

// ViewModel
class HomeViewModel(
    private val context: Context,
    private val syncProfileAfterLoginUseCase: SyncProfileAfterLoginUseCase,
    private val playbackRepository: PlaybackRepository,
    private val homeRepository: HomeRepository,
    private val recentRepository: RecentRepository,
    val playerManager: PlayerManager,
    private val userPreferences: UserPreferences,
    private val authRepository: AuthRepository,
    private val resourceProvider: ResourceProvider,
    private val likedSongsRepository: LikedSongsRepository,
    private val songLikeRepository: SongLikeRepository,
    private val playlistMutationBus: PlaylistMutationBus,
    private val songCollectDelegate: SongCollectDelegate,
    private val userPlaylistRepository: com.lin0721.linmusic.core.userplaylist.UserPlaylistRepository,
    private val youTubeAccountSyncUseCase: com.lin0721.linmusic.core.auth.YouTubeAccountSyncUseCase? = null
) : ViewModel() {

    private val homeShelfCacheStore = HomeShelfCacheStore(context)

    private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    val userProfile: StateFlow<UserProfile?> = userPreferences.userProfile
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val currentUid: String? get() = likedSongsRepository.getCurrentUid()

    private val _toastEvent = MutableSharedFlow<String>()
    val toastEvent: SharedFlow<String> = _toastEvent.asSharedFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    // Firestore-backed Liked Songs and Video IDs
    val likedSongs: StateFlow<List<LikedSong>> = likedSongsRepository.likedSongs
    val likedVideoIds: StateFlow<Set<String>> = likedSongsRepository.likedVideoIds
    val likedSongIds: StateFlow<Set<Long>> = likedSongsRepository.likedSongIds
    val likedTracks: StateFlow<List<Track>> = userPreferences.likedTracks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentRecords: StateFlow<List<com.lin0721.linmusic.core.auth.RecentPlayRecord>> = userPreferences.recentRecords
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _userPlaylists = MutableStateFlow<List<com.lin0721.linmusic.core.userplaylist.UserPlaylist>>(emptyList())
    val userPlaylists: StateFlow<List<com.lin0721.linmusic.core.userplaylist.UserPlaylist>> = _userPlaylists.asStateFlow()

    val collectState: StateFlow<PlaylistCollectState> = songCollectDelegate.state


    init {
        loadHomeData()
        loadUserPlaylists()
        viewModelScope.launch {
            playerManager.initController()
        }
        viewModelScope.launch {
            likedSongsRepository.errorEvents.collect { errorMsg ->
                _toastEvent.emit(errorMsg)
            }
        }
        viewModelScope.launch {
            userPreferences.userProfile.collect {
                loadUserPlaylists()
            }
        }
        viewModelScope.launch {
            playlistMutationBus.events.collect {
                loadUserPlaylists()
            }
        }
        // ponytail: simple delay loop, no WorkManager. Reuses existing refreshHomeData().
        viewModelScope.launch {
            val refreshIntervalMs = 3 * 60 * 60 * 1_000L // 3 hours
            while (true) {
                delay(refreshIntervalMs)
                refreshHomeData()
            }
        }
    }

    fun prepareCollectDialog(songId: Long) {
        viewModelScope.launch {
            songCollectDelegate.prepare(songId, likedSongIds.value) { _toastEvent.emit(it) }
        }
    }

    fun savePlaylistCollection(songId: Long, items: List<PlaylistCollectItem>) {
        viewModelScope.launch {
            songCollectDelegate.save(
                songId = songId,
                items = items,
                likedSongIds = likedSongIds.value,
                onToast = { _toastEvent.emit(it) },
                onLikedChanged = { }
            )
        }
    }

    fun createPlaylistAndAddSong(name: String, songId: Long) {
        viewModelScope.launch {
            songCollectDelegate.createAndAdd(name, songId, likedSongIds.value) { _toastEvent.emit(it) }
        }
    }

    fun loadUserPlaylists() {
        viewModelScope.launch {
            val uid = userProfile.value?.uid ?: 0L
            userPlaylistRepository.getUserPlaylists(uid).collect { result ->
                result.onSuccess { list ->
                    // Order of how frequently user listens to them (playCount descending)
                    _userPlaylists.value = list.sortedByDescending { it.playCount }
                }
            }
        }
    }

    fun loadHomeData(refresh: Boolean = false) {
        viewModelScope.launch {
            if (!refresh) {
                // 1. Stale-While-Revalidate: Immediately read cached shelves from local DB (< 30ms) to render with zero spinner
                val cached = withContext(Dispatchers.IO) {
                    homeShelfCacheStore.getCachedHomeFeed()
                }
                if (cached != null) {
                    _uiState.value = HomeUiState.Success(cached)
                } else {
                    _uiState.value = HomeUiState.Loading
                }
            } else {
                _isRefreshing.value = true
            }

            // 2. Fetch fresh feed in background on Dispatchers.IO
            val result = withContext(Dispatchers.IO) {
                fetchHomeFeedData(refresh)
            }
            result.fold(
                onSuccess = { freshData ->
                    _uiState.value = HomeUiState.Success(freshData)
                    if (freshData.shelves.isNotEmpty()) {
                        withContext(Dispatchers.IO) {
                            homeShelfCacheStore.saveHomeFeed(freshData)
                        }
                    }
                },
                onFailure = { error ->
                    val current = (_uiState.value as? HomeUiState.Success)?.data
                    if (current == null || current.shelves.isEmpty()) {
                        _uiState.value = HomeUiState.Error(error.toUserMessage(resourceProvider))
                    } else {
                        AppLogger.w(TAG, "Background home refresh failed: ${error.message}")
                    }
                }
            )
            _isRefreshing.value = false
        }
    }

    // ：CurrentClear，，、
    fun refreshHomeData() {
        if (_isRefreshing.value) return
        _isRefreshing.value = true
        loadUserPlaylists()

        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                fetchHomeFeedData(refresh = true)
            }
            result.fold(
                onSuccess = { freshData ->
                    _uiState.value = HomeUiState.Success(freshData)
                    if (freshData.shelves.isNotEmpty()) {
                        withContext(Dispatchers.IO) {
                            homeShelfCacheStore.saveHomeFeed(freshData)
                        }
                    }
                },
                onFailure = { error ->
                    val current = (_uiState.value as? HomeUiState.Success)?.data
                    if (current == null || current.shelves.isEmpty()) {
                        _uiState.value = HomeUiState.Error(error.toUserMessage(resourceProvider))
                    } else {
                        _toastEvent.emit(error.toUserMessage(resourceProvider))
                    }
                }
            )
            _isRefreshing.value = false
        }
    }

    // ，；
    private suspend fun fetchHomeFeedData(refresh: Boolean): Result<HomeFeedData> = try {
        coroutineScope {
            val blockPageDeferred = async {
                homeRepository.getHomeBlockPage(refresh = refresh).first()
            }

            val playlistsDeferred = async {
                runCatching { homeRepository.getPersonalizedPlaylists().first() }
                    .getOrDefault(Result.success(PersonalizedData()))
            }

            val recentDeferred = async {
                runCatching { recentRepository.getRecentPlaylists().first() }
                    .getOrDefault(Result.success(emptyList()))
            }

            val dailySongsDeferred = async {
                runCatching { homeRepository.getDailyRecommendSongs().first() }
                    .getOrDefault(Result.success(emptyList()))
            }

            val toplistDeferred = async {
                runCatching { homeRepository.getToplistDetail().first() }
                    .getOrDefault(Result.success(emptyList<ToplistInfo>()))
            }

            val blockPageResult = blockPageDeferred.await()
            val playlistsResult = playlistsDeferred.await()
            val recentResult = recentDeferred.await()
            val dailySongsResult = dailySongsDeferred.await()
            val toplistResult = toplistDeferred.await()

            val blockPage = blockPageResult.getOrNull()
            if (blockPage != null) {
                Result.success(
                    HomeFeedData(
                        shelves = blockPage.shelves,
                        recommendPlaylists = playlistsResult.getOrNull()?.playlists.orEmpty(),
                        recentPlaylists = recentResult.getOrDefault(emptyList()),
                        dailySongs = dailySongsResult.getOrDefault(emptyList()),
                        toplistItems = toplistResult.getOrDefault(emptyList()),
                        nextCursor = blockPage.nextCursor,
                        hasMore = blockPage.hasMore
                    )
                )
            } else {
                Result.failure(
                    blockPageResult.exceptionOrNull()
                        ?: RuntimeException(resourceProvider.getString(R.string.app_error_biz_default))
                )
            }
        }
    } catch (e: Exception) {
        Result.failure(e)
    }

    // 。， hasMore  false
    fun loadMoreShelves() {
        val current = _uiState.value as? HomeUiState.Success ?: return
        val cursor = current.data.nextCursor ?: return
        if (current.data.isLoadingMore) return

        _uiState.value = HomeUiState.Success(current.data.copy(isLoadingMore = true))

        viewModelScope.launch {
            val result = runCatching { homeRepository.getHomeBlockPage(cursor = cursor).first() }
                .getOrNull()
                ?.getOrNull()

            val latest = _uiState.value as? HomeUiState.Success ?: return@launch
            _uiState.value = if (result == null) {
                // ，Collapse
                AppLogger.w(TAG, "Pagination failed, stopping shelf loading")
                HomeUiState.Success(latest.data.copy(isLoadingMore = false, hasMore = false, nextCursor = null))
            } else {
                HomeUiState.Success(
                    latest.data.copy(
                        shelves = latest.data.shelves + result.shelves,
                        nextCursor = result.nextCursor,
                        hasMore = result.hasMore,
                        isLoadingMore = false
                    )
                )
            }
        }
    }

    fun playDailySong(index: Int = 0) {
        val state = uiState.value
        if (state is HomeUiState.Success && state.data.dailySongs.isNotEmpty()) {
            val queueItems = state.data.dailySongs.map { song ->
                val vid = com.lin0721.linmusic.core.player.innertube.YouTubeIdRegistry.getVideoId(song.id).orEmpty()
                val studioCover = com.lin0721.linmusic.core.player.innertube.YouTubeIdRegistry.getCoverUrl(song.id).takeIf { it.isNotBlank() } ?: song.al.picUrl
                val durSec = com.lin0721.linmusic.core.player.innertube.YouTubeIdRegistry.getDurationSec(song.id)
                QueueItem(
                    songId = song.id,
                    title = song.name,
                    artist = song.ar.joinToString { it.name },
                    coverUrl = studioCover,
                    videoId = vid.ifBlank { null },
                    album = song.al.name.takeIf { it.isNotBlank() },
                    durationSec = durSec
                )
            }
            playerManager.playQueue(queueItems, index.coerceIn(0, queueItems.size - 1), "Daily Mix")
        } else {
            viewModelScope.launch { _toastEvent.emit("No daily mix songs") }
        }
    }

    fun playShelfSong(shelfTitle: String, songs: List<HomeCard.Song>, song: HomeCard.Song) {
        val vid = com.lin0721.linmusic.core.player.innertube.YouTubeIdRegistry.getVideoId(song.id).orEmpty()
        val item = QueueItem(
            songId = song.id,
            title = song.title,
            artist = song.caption,
            coverUrl = song.coverUrl,
            videoId = vid.ifBlank { null }
        )
        playerManager.playWithDynamicRadio(item, context = shelfTitle)
    }

    fun playShelfVoice(shelfTitle: String, voices: List<HomeCard.Voice>, voice: HomeCard.Voice) {
        val item = QueueItem(voice.songId, voice.title, voice.caption, voice.coverUrl)
        playerManager.playWithDynamicRadio(item, context = shelfTitle)
    }

    fun playTrack(track: Track, contextList: List<Track> = emptyList()) {
        val vid = YouTubeIdRegistry.getVideoId(track.id) ?: track.id.toString()
        val cover = YouTubeIdRegistry.getCoverUrl(track.id) ?: track.al.picUrl
        val item = QueueItem(
            songId = track.id,
            title = track.name,
            artist = track.ar.joinToString { it.name },
            coverUrl = cover,
            videoId = vid.ifBlank { null },
            album = track.al.name.takeIf { it.isNotBlank() },
            durationSec = (track.dt / 1000L).toInt()
        )
        if (contextList.size > 1) {
            val queue = contextList.map { t ->
                val v = YouTubeIdRegistry.getVideoId(t.id) ?: t.id.toString()
                val c = YouTubeIdRegistry.getCoverUrl(t.id) ?: t.al.picUrl
                QueueItem(
                    songId = t.id,
                    title = t.name,
                    artist = t.ar.joinToString { it.name },
                    coverUrl = c,
                    videoId = v.ifBlank { null },
                    album = t.al.name.takeIf { it.isNotBlank() },
                    durationSec = (t.dt / 1000L).toInt()
                )
            }
            val index = queue.indexOfFirst { it.songId == track.id }.coerceAtLeast(0)
            playerManager.playQueue(queue, index, playContext = "Home")
        } else {
            playerManager.playWithDynamicRadio(item, context = "Home")
        }
    }

    fun playLikedSong(song: LikedSong, songs: List<LikedSong> = emptyList()) {
        val list = if (songs.isNotEmpty()) songs else listOf(song)
        val queueItems = list.map { it.toQueueItem() }
        val index = queueItems.indexOfFirst { it.videoId == song.videoId }.coerceAtLeast(0)
        playerManager.playQueue(queueItems, index, playContext = "Home")
    }

    // Play Liked Songs through normal YouTube Music playback path
    fun playLikedSongs(startIndex: Int = 0, shuffle: Boolean = false) {

        val songs = likedSongs.value.filter { it.videoId.isNotBlank() && it.title.isNotBlank() }
        if (songs.isEmpty()) {
            viewModelScope.launch { _toastEvent.emit("No liked songs yet") }
            return
        }
        val queueItems = songs.map { it.toQueueItem() }
        val finalQueue = if (shuffle) queueItems.shuffled() else queueItems
        val index = if (shuffle) 0 else startIndex.coerceIn(0, finalQueue.size - 1)
        playerManager.playQueue(finalQueue, index, playContext = "Liked Songs")
    }

    // Toggle liked song (LikedSong model)
    fun toggleLikeSong(song: LikedSong) {
        viewModelScope.launch {
            val vid = YouTubeIdRegistry.sanitizeVideoId(song.videoId).ifBlank { song.videoId }
            val isLiked = likedSongsRepository.isLiked(vid)
            if (isLiked) {
                likedSongsRepository.unlike(vid)
                val existing = userPreferences.likedTracks.firstOrNull() ?: emptyList()
                val updated = existing.filterNot { it.toLikedSong()?.videoId == vid || YouTubeIdRegistry.getVideoId(it.id) == vid }
                userPreferences.saveLikedTracks(updated)
                val sid = YouTubeIdRegistry.register(vid)
                if (sid > 0L) {
                    songLikeRepository.likeSong(sid, false).collect { }
                }
                playlistMutationBus.emit(PlaylistMutationEvent.Updated(0L))
                _toastEvent.emit("Removed from Library")
            } else {
                likedSongsRepository.like(song)
                val existing = userPreferences.likedTracks.firstOrNull() ?: emptyList()
                val track = song.toTrack()
                val updated = if (existing.none { it.id == track.id || YouTubeIdRegistry.getVideoId(it.id) == vid }) {
                    listOf(track) + existing
                } else existing
                userPreferences.saveLikedTracks(updated)
                if (track.id > 0L) {
                    songLikeRepository.likeSong(track.id, true).collect { }
                }
                playlistMutationBus.emit(PlaylistMutationEvent.Updated(0L))
                _toastEvent.emit("Added to Library")
            }
        }
    }

    // Toggle liked song (Track model)
    fun toggleLikeSong(track: Track) {
        viewModelScope.launch {
            val vid = YouTubeIdRegistry.getVideoId(track.id) ?: track.id.toString()
            val isLiked = likedSongsRepository.isLiked(vid) || likedSongsRepository.isLiked(track.id)
            if (isLiked) {
                likedSongsRepository.unlike(vid)
                val existing = userPreferences.likedTracks.firstOrNull() ?: emptyList()
                val updated = existing.filterNot { it.id == track.id || YouTubeIdRegistry.getVideoId(it.id) == vid }
                userPreferences.saveLikedTracks(updated)
                if (track.id > 0L) {
                    songLikeRepository.likeSong(track.id, false).collect { }
                }
                playlistMutationBus.emit(PlaylistMutationEvent.Updated(0L))
                _toastEvent.emit("Removed from Library")
            } else {
                val likedSong = track.toLikedSong() ?: LikedSong(
                    videoId = vid,
                    title = track.name,
                    artist = track.ar.firstOrNull()?.name.orEmpty(),
                    album = track.al.name.takeIf { it.isNotBlank() },
                    durationSec = track.dt / 1000L,
                    thumbnailUrl = track.al.picUrl
                )
                likedSongsRepository.like(likedSong)
                val existing = userPreferences.likedTracks.firstOrNull() ?: emptyList()
                val updated = if (existing.none { it.id == track.id || YouTubeIdRegistry.getVideoId(it.id) == vid }) {
                    listOf(track) + existing
                } else existing
                userPreferences.saveLikedTracks(updated)
                if (track.id > 0L) {
                    songLikeRepository.likeSong(track.id, true).collect { }
                }
                playlistMutationBus.emit(PlaylistMutationEvent.Updated(0L))
                _toastEvent.emit("Added to Library")
            }
        }
    }

    // Toggle like for current playing track (MiniPlayer & notification/shortcuts)
    fun toggleLikeCurrentTrack() {
        val current = playerManager.currentTrack.value ?: return
        viewModelScope.launch {
            val rawMediaId = current.mediaId
            val numericMediaId = rawMediaId?.toLongOrNull()
            var videoId = current.mediaMetadata.extras?.getString("videoId")
                ?: (if (numericMediaId != null) YouTubeIdRegistry.getVideoId(numericMediaId) else YouTubeIdRegistry.sanitizeVideoId(rawMediaId).takeIf { it.isNotBlank() })

            var songId = numericMediaId
                ?: (if (!videoId.isNullOrBlank()) YouTubeIdRegistry.register(videoId) else -1L)

            var title = current.mediaMetadata.title?.toString()?.takeIf { it.isNotBlank() }
                ?: (if (songId > 0L) YouTubeIdRegistry.getTitle(songId).takeIf { it.isNotBlank() } else null)

            var artist = current.mediaMetadata.artist?.toString()?.takeIf { it.isNotBlank() }
                ?: (if (songId > 0L) YouTubeIdRegistry.getArtist(songId).takeIf { it.isNotBlank() } else null)

            var album = current.mediaMetadata.albumTitle?.toString()?.takeIf { it.isNotBlank() }
                ?: current.mediaMetadata.extras?.getString("album")?.takeIf { it.isNotBlank() }
                ?: (if (songId > 0L) YouTubeIdRegistry.getAlbumName(songId).takeIf { it.isNotBlank() } else null)

            var cover = current.mediaMetadata.artworkUri?.toString()?.takeIf { it.isNotBlank() }
                ?: (if (songId > 0L) YouTubeIdRegistry.getCoverUrl(songId).takeIf { it.isNotBlank() } else null)

            var durationSec = (if (songId > 0L) YouTubeIdRegistry.getDurationSec(songId) else 0)
            if (durationSec <= 0) {
                durationSec = current.mediaMetadata.extras?.getInt("durationSec") ?: 0
            }
            if (durationSec <= 0 && playerManager.duration.value > 0) {
                durationSec = (playerManager.duration.value / 1000L).toInt()
            }

            // Missing Metadata Guard: resolve missing fields from InnerTube if album or details are blank
            if (title.isNullOrBlank() || artist.isNullOrBlank() || album.isNullOrBlank() || cover.isNullOrBlank() || durationSec <= 0) {
                if (!videoId.isNullOrBlank()) {
                    try {
                        val query = if (!title.isNullOrBlank() && !artist.isNullOrBlank()) "$title $artist" else videoId
                        val searchRes = com.metrolist.innertube.YouTube.search(query, com.metrolist.innertube.YouTube.SearchFilter.FILTER_SONG).getOrNull()
                        val match = searchRes?.items?.filterIsInstance<com.metrolist.innertube.models.SongItem>()
                            ?.firstOrNull { it.id == videoId }
                            ?: searchRes?.items?.filterIsInstance<com.metrolist.innertube.models.SongItem>()?.firstOrNull()

                        if (match != null) {
                            if (title.isNullOrBlank()) title = match.title
                            if (artist.isNullOrBlank()) artist = match.artists.joinToString(", ") { it.name }
                            if (album.isNullOrBlank()) album = match.album?.name
                            if (cover.isNullOrBlank()) cover = match.thumbnail
                            if (durationSec <= 0 && match.duration != null) durationSec = match.duration ?: 0
                        }
                    } catch (e: Exception) {
                        AppLogger.w(TAG, "Metadata resolution error: ${e.message}")
                    }
                }
            }

            // Safe fallbacks to guarantee database insert never silently fails
            val resolvedTitle = title?.ifBlank { "Unknown Title" } ?: "Unknown Title"
            val resolvedArtist = artist?.ifBlank { "Unknown Artist" } ?: "Unknown Artist"
            val resolvedAlbum = album?.ifBlank { "$resolvedTitle - Single" } ?: "$resolvedTitle - Single"
            val resolvedCover = cover.orEmpty()
            val resolvedVid = videoId?.ifBlank { null }
                ?: YouTubeIdRegistry.findVideoIdByTitleArtist(resolvedTitle, resolvedArtist)
                ?: (if (songId > 0L) YouTubeIdRegistry.getVideoId(songId) else null)
                ?: (if (songId > 0L) songId.toString() else "track_${System.currentTimeMillis()}")

            songId = YouTubeIdRegistry.register(
                videoId = resolvedVid,
                title = resolvedTitle,
                artist = resolvedArtist,
                durationSec = durationSec,
                album = resolvedAlbum,
                coverUrl = resolvedCover
            )

            val albumId = (resolvedAlbum.hashCode().toLong() and 0x7FFFFFFFL)
            YouTubeIdRegistry.registerAlbum(resolvedAlbum)

            val track = Track(
                id = songId,
                name = resolvedTitle,
                ar = listOf(Artist(id = (resolvedArtist.hashCode().toLong() and 0x7FFFFFFFL), name = resolvedArtist)),
                al = Album(id = albumId, name = resolvedAlbum, picUrl = resolvedCover),
                dt = durationSec.toLong() * 1000L
            )

            val likedSong = LikedSong(
                videoId = resolvedVid,
                title = resolvedTitle,
                artist = resolvedArtist,
                album = resolvedAlbum,
                durationSec = durationSec.toLong(),
                thumbnailUrl = resolvedCover
            )

            val isCurrentlyLiked = likedSongsRepository.isLiked(resolvedVid)
                || (songId > 0L && likedSongsRepository.isLiked(songId))
                || userPreferences.likedTracks.firstOrNull()?.any { it.id == songId || YouTubeIdRegistry.getVideoId(it.id) == resolvedVid } == true

            if (isCurrentlyLiked) {
                likedSongsRepository.unlike(resolvedVid)
                val existing = userPreferences.likedTracks.firstOrNull() ?: emptyList()
                val updated = existing.filterNot { it.id == songId || YouTubeIdRegistry.getVideoId(it.id) == resolvedVid }
                userPreferences.saveLikedTracks(updated)
                if (songId > 0L) {
                    songLikeRepository.likeSong(songId, false).collect { }
                }
                playlistMutationBus.emit(PlaylistMutationEvent.Updated(0L))
                _toastEvent.emit("Removed from Library")
            } else {
                likedSongsRepository.like(likedSong)
                val existing = userPreferences.likedTracks.firstOrNull() ?: emptyList()
                val updated = if (existing.none { it.id == songId || YouTubeIdRegistry.getVideoId(it.id) == resolvedVid }) {
                    listOf(track) + existing
                } else {
                    existing
                }
                userPreferences.saveLikedTracks(updated)
                if (songId > 0L) {
                    songLikeRepository.likeSong(songId, true).collect { }
                }
                playlistMutationBus.emit(PlaylistMutationEvent.Updated(0L))
                _toastEvent.emit("Added to Library")
            }
        }
    }

    fun togglePlayPause() {
        playerManager.togglePlayPause()
    }

    fun handleLoginSuccess(cookies: String) {
        viewModelScope.launch {
            val profile = syncProfileAfterLoginUseCase(cookies) ?: return@launch
            _toastEvent.emit("Logged in successfully. Welcome back, ${profile.nickname}!")
            loadHomeData()
        }
    }

    fun connectYouTubeAccount(session: com.lin0721.linmusic.core.ui.components.YouTubeSessionData) {
        viewModelScope.launch {
            _toastEvent.emit("Connecting YouTube Music account...")
            val result = youTubeAccountSyncUseCase?.syncSession(session)
            if (result?.isSuccess == true) {
                _toastEvent.emit("YouTube Music connected successfully!")
            } else {
                _toastEvent.emit("Connected, refreshing home...")
            }
            loadUserPlaylists()
            loadHomeData(refresh = true)
        }
    }

    fun logout() {
        viewModelScope.launch {
            authRepository.logout().collect { }
            try {
                com.google.firebase.auth.FirebaseAuth.getInstance().signOut()
            } catch (e: Exception) {
                AppLogger.w(TAG, "FirebaseAuth signOut error: ${e.message}")
            }
            userPreferences.clearUserProfile()
            _toastEvent.emit("Logged out")
            loadHomeData()
        }
    }

    // Songs
    fun startRoaming() {
        val current = playerManager.currentTrack.value
        if (current != null) {
            val songId = current.mediaId?.toLongOrNull() ?: return
            val title = current.mediaMetadata.title?.toString() ?: ""
            val artist = current.mediaMetadata.artist?.toString() ?: ""
            val coverUrl = current.mediaMetadata.artworkUri?.toString() ?: ""
            viewModelScope.launch {
                playbackRepository.getSimilarSongs(songId).collect { result ->
                    result.onSuccess { simiSongs ->
                        if (simiSongs.isNotEmpty()) {
                            val currentItem = QueueItem(songId, title, artist, coverUrl)
                            val simiItems = simiSongs.map { track ->
                                QueueItem(
                                    songId = track.id,
                                    title = track.name,
                                    artist = track.ar.joinToString("/") { it.name },
                                    coverUrl = track.al.picUrl
                                )
                            }
                            val roamingQueue = listOf(currentItem) + simiItems
                            playerManager.playQueue(roamingQueue, 0, playContext = "similar_roaming")
                            _toastEvent.emit("Started similar songs roaming")
                        } else {
                            _toastEvent.emit("No similar songs found")
                        }
                    }.onFailure {
                        _toastEvent.emit(it.toUserMessage(resourceProvider))
                    }
                }
            }
        } else {
            val state = uiState.value
            if (state is HomeUiState.Success && state.data.dailySongs.isNotEmpty()) {
                val firstSong = state.data.dailySongs.first()
                viewModelScope.launch {
                    playbackRepository.getSimilarSongs(firstSong.id).collect { result ->
                        result.onSuccess { simiSongs ->
                            val currentItem = QueueItem(firstSong.id, firstSong.name, firstSong.ar.joinToString("/") { it.name }, firstSong.al.picUrl)
                            val simiItems = simiSongs.map { track ->
                                QueueItem(track.id, track.name, track.ar.joinToString("/") { it.name }, track.al.picUrl)
                            }
                            val roamingQueue = listOf(currentItem) + simiItems
                            playerManager.playQueue(roamingQueue, 0, playContext = "similar_roaming")
                            _toastEvent.emit("Roaming mode started from \"${firstSong.name}\"")
                        }.onFailure {
                            _toastEvent.emit(it.toUserMessage(resourceProvider))
                        }
                    }
                }
            } else {
                viewModelScope.launch { _toastEvent.emit("No songs in playlist and cannot fetch daily mix") }
            }
        }
    }

    // Heartbeat Mode
    fun startIntelligenceMode() {
        val current = playerManager.currentTrack.value
        if (current != null) {
            val songId = current.mediaId?.toLongOrNull() ?: return
            viewModelScope.launch {
                playbackRepository.getIntelligenceSongs(songId, 0).collect { result ->
                    result.onSuccess { tracks ->
                        if (tracks.isNotEmpty()) {
                            val currentItem = QueueItem(
                                songId,
                                current.mediaMetadata.title?.toString() ?: "",
                                current.mediaMetadata.artist?.toString() ?: "",
                                current.mediaMetadata.artworkUri?.toString() ?: ""
                            )
                            val items = listOf(currentItem) + tracks.map { track ->
                                QueueItem(track.id, track.name, track.ar.joinToString("/") { it.name }, track.al.picUrl)
                            }
                            playerManager.playQueue(items, 0, playContext = PlayerManager.CONTEXT_INTELLIGENCE)
                            _toastEvent.emit("Heartbeat mode enabled")
                        } else {
                            _toastEvent.emit("Failed to fetch heartbeat recommendations")
                        }
                    }.onFailure {
                        _toastEvent.emit(it.toUserMessage(resourceProvider))
                    }
                }
            }
        } else {
            val state = uiState.value
            if (state is HomeUiState.Success && state.data.dailySongs.isNotEmpty()) {
                val firstSong = state.data.dailySongs.first()
                viewModelScope.launch {
                    playbackRepository.getIntelligenceSongs(firstSong.id, 0).collect { result ->
                        result.onSuccess { tracks ->
                            val currentItem = QueueItem(firstSong.id, firstSong.name, firstSong.ar.joinToString("/") { it.name }, firstSong.al.picUrl)
                            val items = listOf(currentItem) + tracks.map { track ->
                                QueueItem(track.id, track.name, track.ar.joinToString("/") { it.name }, track.al.picUrl)
                            }
                            playerManager.playQueue(items, 0, playContext = PlayerManager.CONTEXT_INTELLIGENCE)
                            _toastEvent.emit("Heartbeat mode started from \"${firstSong.name}\"")
                        }.onFailure {
                            _toastEvent.emit(it.toUserMessage(resourceProvider))
                        }
                    }
                }
            } else {
                viewModelScope.launch { _toastEvent.emit("Please play a song first") }
            }
        }
    }

    // of Mix
    fun playArtist(artistName: String) {
        viewModelScope.launch {
            _toastEvent.emit("Loading $artistName songs & mix...")
            val queueItems = mutableListOf<QueueItem>()
            val seenVideoIds = mutableSetOf<String>()

            // 1.
            val songsRes = runCatching {
                com.metrolist.innertube.YouTube.search(artistName, com.metrolist.innertube.YouTube.SearchFilter.FILTER_SONG).getOrNull()
            }.getOrNull()
            val songItems = songsRes?.items?.filterIsInstance<com.metrolist.innertube.models.SongItem>() ?: emptyList()
            for (song in songItems.take(15)) {
                if (seenVideoIds.add(song.id)) {
                    val cleanT = com.lin0721.linmusic.core.metadata.cleanMetadataText(song.title)
                    val cleanA = com.lin0721.linmusic.core.metadata.cleanMetadataText(song.artists.joinToString(", ") { it.name })
                    val songId = com.lin0721.linmusic.core.player.innertube.YouTubeIdRegistry.register(
                        videoId = song.id,
                        title = cleanT,
                        artist = cleanA,
                        coverUrl = song.thumbnail.orEmpty()
                    )
                    val vid = song.id
                    val studioCover = com.lin0721.linmusic.core.player.innertube.YouTubeIdRegistry.getCoverUrl(songId).takeIf { it.isNotBlank() } ?: song.thumbnail.orEmpty()
                    queueItems.add(
                        QueueItem(
                            songId = songId,
                            title = cleanT,
                            artist = cleanA,
                            coverUrl = studioCover,
                            videoId = vid,
                            album = com.lin0721.linmusic.core.metadata.cleanMetadataText(song.album?.name.orEmpty()),
                            durationSec = song.duration ?: 0
                        )
                    )
                }
            }

            // 2.  of Mix/
            val mixPlaylistRes = runCatching {
                val plSearch = com.metrolist.innertube.YouTube.search("$artistName mix", com.metrolist.innertube.YouTube.SearchFilter.FILTER_COMMUNITY_PLAYLIST).getOrNull()
                    ?: com.metrolist.innertube.YouTube.search("$artistName playlist", com.metrolist.innertube.YouTube.SearchFilter.FILTER_COMMUNITY_PLAYLIST).getOrNull()
                val plItem = plSearch?.items?.filterIsInstance<com.metrolist.innertube.models.PlaylistItem>()?.firstOrNull()
                if (plItem != null) {
                    com.metrolist.innertube.YouTube.playlist(plItem.id).getOrNull()
                } else null
            }.getOrNull()

            mixPlaylistRes?.songs?.let { plSongs ->
                for (song in plSongs.take(25)) {
                    if (seenVideoIds.add(song.id)) {
                        val cleanT = com.lin0721.linmusic.core.metadata.cleanMetadataText(song.title)
                        val cleanA = com.lin0721.linmusic.core.metadata.cleanMetadataText(song.artists.joinToString(", ") { it.name })
                        val songId = com.lin0721.linmusic.core.player.innertube.YouTubeIdRegistry.register(
                            videoId = song.id,
                            title = cleanT,
                            artist = cleanA,
                            coverUrl = song.thumbnail.orEmpty()
                        )
                        val vid = song.id
                        val studioCover = com.lin0721.linmusic.core.player.innertube.YouTubeIdRegistry.getCoverUrl(songId).takeIf { it.isNotBlank() } ?: song.thumbnail.orEmpty()
                        queueItems.add(
                            QueueItem(
                                songId = songId,
                                title = cleanT,
                                artist = cleanA,
                                coverUrl = studioCover,
                                videoId = vid,
                                album = com.lin0721.linmusic.core.metadata.cleanMetadataText(song.album?.name.orEmpty()),
                                durationSec = song.duration ?: 0
                            )
                        )
                    }
                }
            }

            if (queueItems.isNotEmpty()) {
                playerManager.playQueue(queueItems, 0, playContext = "$artistName Mix")
                _toastEvent.emit("Playing $artistName Mix")
            } else {
                _toastEvent.emit("Could not load songs for $artistName")
            }
        }
    }
}
