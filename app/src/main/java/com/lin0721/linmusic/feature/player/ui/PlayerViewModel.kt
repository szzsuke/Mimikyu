package com.lin0721.linmusic.feature.player.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lin0721.linmusic.core.preferences.FullPlayerCardLayout
import com.lin0721.linmusic.core.preferences.FullPlayerCardSetting
import com.lin0721.linmusic.core.preferences.SettingsPreferences
import com.lin0721.linmusic.core.auth.UserPreferences
import com.lin0721.linmusic.core.download.DownloadTrackInfo
import com.lin0721.linmusic.core.download.SongDownloadManager
import com.lin0721.linmusic.core.model.Album
import com.lin0721.linmusic.core.model.Artist
import com.lin0721.linmusic.core.model.ArtistAlbum
import com.lin0721.linmusic.core.player.innertube.YouTubeIdRegistry
import com.lin0721.linmusic.feature.player.domain.SongWikiCreatorRole
import com.lin0721.linmusic.core.model.ArtistDetailInfo
import com.lin0721.linmusic.core.model.Track
import com.lin0721.linmusic.core.model.ArtistInfo
import com.lin0721.linmusic.core.log.AppLogger
import com.lin0721.linmusic.core.player.domain.LyricLine
import com.lin0721.linmusic.feature.artist.data.ArtistRepository
import com.lin0721.linmusic.core.songlike.LoadLikedSongIdsUseCase
import com.lin0721.linmusic.core.songlike.SongLikeRepository
import com.lin0721.linmusic.core.songlike.LikedSong
import com.lin0721.linmusic.core.songlike.LikedSongsRepository
import com.lin0721.linmusic.core.songlike.toLikedSong
import com.lin0721.linmusic.core.playlistmutation.PlaylistMutationBus
import com.lin0721.linmusic.core.playlistmutation.PlaylistMutationEvent
import kotlinx.coroutines.flow.flowOf
import com.lin0721.linmusic.core.ui.components.PlaylistCollectItem
import com.lin0721.linmusic.core.ui.components.PlaylistCollectState
import com.lin0721.linmusic.feature.playlist.domain.SongCollectDelegate
import com.lin0721.linmusic.core.player.data.PlaybackRepository
import com.lin0721.linmusic.feature.player.data.PlayerRepository
import com.lin0721.linmusic.core.player.PlayerManager
import com.lin0721.linmusic.core.player.QueueItem
import com.lin0721.linmusic.feature.player.domain.SongWikiData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import com.lin0721.linmusic.core.network.ResourceProvider
import com.lin0721.linmusic.core.network.toUserMessage

// of
data class ArtistCardItem(
    val artistId: Long,
    val artistName: String,
    val artistDetail: ArtistDetailInfo? = null,
    val fansCount: Long? = null,
    val mimikyuMonthlyListeners: Long? = null,
    val isFollowed: Boolean = false,
    val isLoading: Boolean = false
)

// CurrentPlaySongs of Details：/SongsDetails/Artists， of  loading/nullable
data class PlayerSongDetailState(
    val songDetail: Track? = null,
    val lyrics: List<LyricLine> = emptyList(),
    val isLyricsLoading: Boolean = false,
    val songWiki: SongWikiData? = null,
    val isSongWikiLoading: Boolean = false,
    val chorusStartMs: Long? = null,
    val similarArtists: List<ArtistInfo> = emptyList(),
    val isSimilarArtistsLoading: Boolean = false,
    val artistAlbums: List<ArtistAlbum> = emptyList(),
    val isArtistAlbumsLoading: Boolean = false,
    val isLiked: Boolean = false,
    val artists: List<ArtistCardItem> = emptyList(),
    val selectedArtistIndex: Int = 0
) {
    val currentArtistItem: ArtistCardItem?
        get() = artists.getOrNull(selectedArtistIndex) ?: artists.firstOrNull()

    val artistDetail: ArtistDetailInfo?
        get() = currentArtistItem?.artistDetail
    val isArtistDetailLoading: Boolean
        get() = currentArtistItem?.isLoading ?: false
    val artistFansCount: Long?
        get() = currentArtistItem?.fansCount
    val artistMonthlyListeners: Long?
        get() = currentArtistItem?.mimikyuMonthlyListeners
    val isArtistFollowed: Boolean
        get() = currentArtistItem?.isFollowed ?: false
}

private const val TAG = "PlayerViewModel"

class PlayerViewModel(
    private val loadLikedSongIdsUseCase: LoadLikedSongIdsUseCase,
    private val context: Context,
    private val playerRepository: PlayerRepository,
    private val playbackRepository: PlaybackRepository,
    private val artistRepository: ArtistRepository,
    private val songLikeRepository: SongLikeRepository,
    private val songCollectDelegate: SongCollectDelegate,
    val playerManager: PlayerManager,
    private val userPreferences: UserPreferences,
    private val settingsPreferences: SettingsPreferences,
    private val resourceProvider: ResourceProvider,
    private val songDownloadManager: SongDownloadManager,
    private val lastFmRepository: com.lin0721.linmusic.core.lastfm.LastFmRepository? = null,
    private val mimikyuArtistStatsRepository: com.lin0721.linmusic.core.metadata.MimikyuArtistStatsRepository? = null,
    private val likedSongsRepository: LikedSongsRepository? = null,
    private val playlistMutationBus: PlaylistMutationBus? = null
) : ViewModel() {

    // WiFi  of Play
    val wifiQuality = settingsPreferences.wifiQuality.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = "lossless"
    )

    // of Play
    val mobileQuality = settingsPreferences.mobileQuality.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = "standard"
    )

    val playerStyle: StateFlow<String> = settingsPreferences.playerStyle.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SettingsPreferences.PLAYER_STYLE_NEW
    )

    fun updatePlayerStyle(style: String) {
        viewModelScope.launch {
            settingsPreferences.savePlayerStyle(style)
        }
    }

    // Current WiFi
    fun isWifiConnected(): Boolean {
        return kotlin.runCatching {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val activeNetwork = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
            capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
        }.onFailure { AppLogger.w(TAG, "Wi-Fi status check error", it) }.getOrDefault(false)
    }

    // MomentsCurrent of Play Flow
    val activeQuality: StateFlow<String> = settingsPreferences.wifiQuality
        .combine(settingsPreferences.mobileQuality) { wifi, mobile ->
            if (isWifiConnected()) wifi else mobile
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = "standard"
        )

    // DownloadCurrentPlaySongs
    fun downloadCurrentSong(
        songId: Long,
        songName: String,
        artistName: String,
        albumName: String,
        coverUrl: String?,
        albumYear: Int,
        level: String
    ) {
        viewModelScope.launch {
            if (songId <= 0) {
                _toastEvent.emit("Song info incomplete, unable to download")
                return@launch
            }
            songDownloadManager.enqueueSingle(
                DownloadTrackInfo(
                    songId, songName, artistName, albumName, coverUrl, albumYear,
                    videoId = YouTubeIdRegistry.getVideoId(songId)
                ),
                level
            )
            _toastEvent.emit("Added to download queue")
        }
    }

    // Play and
    val fullPlayerCardLayout: StateFlow<List<FullPlayerCardSetting>> = settingsPreferences.fullPlayerCardLayout.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = FullPlayerCardLayout.DEFAULT
    )

    fun saveFullPlayerCardLayout(layout: List<FullPlayerCardSetting>) {
        viewModelScope.launch {
            settingsPreferences.saveFullPlayerCardLayout(layout)
        }
    }

    // Current of CurrentSongsPlay
    fun updateQuality(quality: String) {
        viewModelScope.launch {
            if (isWifiConnected()) {
                settingsPreferences.saveWifiQuality(quality)
            } else {
                settingsPreferences.saveMobileQuality(quality)
            }
            val preset = com.lin0721.linmusic.core.player.AudioMasteringPreset.fromKey(quality)
            com.lin0721.linmusic.core.player.UltraAudioMasteringEngine.setPreset(preset)
        }
    }

    private val _currentLyricIndex = MutableStateFlow(-1)
    val currentLyricIndex: StateFlow<Int> = _currentLyricIndex.asStateFlow()

    private val _songLyricOffset = MutableStateFlow(0L)
    val songLyricOffset: StateFlow<Long> = _songLyricOffset.asStateFlow()

    fun updateLyricOffset(deltaMs: Long) {
        val songId = currentSongId
        if (songId <= 0L) return
        val newOffset = _songLyricOffset.value + deltaMs
        _songLyricOffset.value = newOffset
        viewModelScope.launch {
            settingsPreferences.saveSongLyricOffset(songId, newOffset)
        }
    }

    fun resetLyricOffset() {
        val songId = currentSongId
        if (songId <= 0L) return
        _songLyricOffset.value = 0L
        viewModelScope.launch {
            settingsPreferences.saveSongLyricOffset(songId, 0L)
        }
    }

    private val _songDetailState = MutableStateFlow(PlayerSongDetailState())
    val songDetailState: StateFlow<PlayerSongDetailState> = _songDetailState.asStateFlow()

    private var trackSideEffectJob: Job? = null

    fun playSongAtIndex(index: Int) {
        trackSideEffectJob?.cancel()
        clearState(isLiked = false)
        playerManager.playAtIndex(index)
    }

    fun playNext() {
        trackSideEffectJob?.cancel()
        clearState(isLiked = false)
        playerManager.playNext()
    }

    fun playPrevious() {
        trackSideEffectJob?.cancel()
        clearState(isLiked = false)
        playerManager.playPrevious()
    }



    val userProfile = userPreferences.userProfile.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = null
    )

    private val _toastEvent = MutableSharedFlow<String>()
    val toastEvent: SharedFlow<String> = _toastEvent.asSharedFlow()

    val collectState: StateFlow<PlaylistCollectState> = songCollectDelegate.state

    private var currentSongId: Long = -1L

    init {
        loadLikedSongIds()
        observeLikedState()
        observeTrackChanges()
        observePosition()
    }

    private fun observeLikedState() {
        viewModelScope.launch {
            val vidsFlow = likedSongsRepository?.likedVideoIds ?: flowOf(emptySet())
            val sidsFlow = likedSongsRepository?.likedSongIds ?: flowOf(emptySet())
            combine(
                playerManager.currentTrack,
                songLikeRepository.likedSongIds,
                vidsFlow,
                sidsFlow,
                userPreferences.likedTracks
            ) { track, neteaseLikedIds, firestoreVids, firestoreSids, localLiked ->
                if (track == null) return@combine false
                val mediaId = track.mediaId
                val numId = mediaId?.toLongOrNull() ?: (if (currentSongId > 0L) currentSongId else null)
                val vid = track.mediaMetadata.extras?.getString("videoId")
                    ?: (if (numId != null) YouTubeIdRegistry.getVideoId(numId) else YouTubeIdRegistry.sanitizeVideoId(mediaId).takeIf { it.isNotBlank() })
                (vid != null && vid.isNotBlank() && vid in firestoreVids) ||
                (numId != null && numId > 0L && (numId in neteaseLikedIds || numId in firestoreSids)) ||
                localLiked.any { l ->
                    (numId != null && l.id == numId) ||
                    (vid != null && vid.isNotBlank() && (YouTubeIdRegistry.getVideoId(l.id) == vid || l.toLikedSong()?.videoId == vid))
                }
            }.distinctUntilChanged()
                .collect { isLiked ->
                    _songDetailState.update { it.copy(isLiked = isLiked) }
                }
        }
    }

    private fun loadLikedSongIds() {
        viewModelScope.launch {
            userPreferences.userProfile.collect { profile ->
                if (profile != null) {
                    loadLikedSongIdsUseCase()
                }
            }
        }
    }

    fun toggleLike() {
        val currentMedia = playerManager.currentTrack.value ?: return
        viewModelScope.launch {
            val rawMediaId = currentMedia.mediaId
            val numericMediaId = rawMediaId?.toLongOrNull() ?: (if (currentSongId > 0L) currentSongId else null)
            var videoId = currentMedia.mediaMetadata.extras?.getString("videoId")
                ?: (if (numericMediaId != null) YouTubeIdRegistry.getVideoId(numericMediaId) else YouTubeIdRegistry.sanitizeVideoId(rawMediaId).takeIf { it.isNotBlank() })

            var songId = numericMediaId
                ?: (if (!videoId.isNullOrBlank()) YouTubeIdRegistry.register(videoId) else -1L)

            var title = currentMedia.mediaMetadata.title?.toString()?.takeIf { it.isNotBlank() }
                ?: (if (songId > 0L) YouTubeIdRegistry.getTitle(songId).takeIf { it.isNotBlank() } else null)

            var artist = currentMedia.mediaMetadata.artist?.toString()?.takeIf { it.isNotBlank() }
                ?: (if (songId > 0L) YouTubeIdRegistry.getArtist(songId).takeIf { it.isNotBlank() } else null)

            var album = currentMedia.mediaMetadata.albumTitle?.toString()?.takeIf { it.isNotBlank() }
                ?: currentMedia.mediaMetadata.extras?.getString("album")?.takeIf { it.isNotBlank() }
                ?: (if (songId > 0L) YouTubeIdRegistry.getAlbumName(songId).takeIf { it.isNotBlank() } else null)

            var cover = currentMedia.mediaMetadata.artworkUri?.toString()?.takeIf { it.isNotBlank() }
                ?: (if (songId > 0L) YouTubeIdRegistry.getCoverUrl(songId).takeIf { it.isNotBlank() } else null)

            var durationSec = (if (songId > 0L) YouTubeIdRegistry.getDurationSec(songId) else 0)
            if (durationSec <= 0) {
                durationSec = currentMedia.mediaMetadata.extras?.getInt("durationSec") ?: 0
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

            val isCurrentlyLiked = (likedSongsRepository?.isLiked(resolvedVid) == true)
                || (songId > 0L && likedSongsRepository?.isLiked(songId) == true)
                || (songId > 0L && songId in songLikeRepository.likedSongIds.value)
                || userPreferences.likedTracks.firstOrNull()?.any { it.id == songId || YouTubeIdRegistry.getVideoId(it.id) == resolvedVid } == true

            if (isCurrentlyLiked) {
                likedSongsRepository?.unlike(resolvedVid)
                val existing = userPreferences.likedTracks.firstOrNull() ?: emptyList()
                val updated = existing.filterNot { it.id == songId || YouTubeIdRegistry.getVideoId(it.id) == resolvedVid }
                userPreferences.saveLikedTracks(updated)
                if (songId > 0L) {
                    songLikeRepository.likeSong(songId, false).collect { }
                }
                playlistMutationBus?.emit(PlaylistMutationEvent.Updated(0L))
                _toastEvent.emit("Removed from Library")
            } else {
                likedSongsRepository?.like(likedSong)
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
                playlistMutationBus?.emit(PlaylistMutationEvent.Updated(0L))
                _toastEvent.emit("Added to Library")
            }
        }
    }

    // "Save to playlist"Playlists
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

    private fun observeTrackChanges() {
        viewModelScope.launch {
            playerManager.currentTrack
                .map { it?.mediaId?.toLongOrNull() ?: -1L }
                .distinctUntilChanged()
                .collectLatest { songId ->
                    trackSideEffectJob?.cancel()
                    if (songId != -1L && songId != currentSongId) {
                        currentSongId = songId
                        val isLiked = songId > 0L && songId in songLikeRepository.likedSongIds.value
                        val cachedLyrics = playbackRepository.getCachedLyrics(songId)
                        if (cachedLyrics != null && cachedLyrics.isNotEmpty()) {
                            _songDetailState.value = PlayerSongDetailState(
                                lyrics = cachedLyrics,
                                isLyricsLoading = false,
                                isLiked = isLiked
                            )
                            _currentLyricIndex.value = findLyricIndex(cachedLyrics, 0L)
                            _songLyricOffset.value = 0L
                        } else {
                            clearState(isLiked)
                        }
                        trackSideEffectJob = launch(Dispatchers.IO) {
                            launch {
                                settingsPreferences.getSongLyricOffset(songId).collect {
                                    _songLyricOffset.value = it
                                }
                            }
                            launch { loadLyrics(songId) }
                            launch { loadSongDetail(songId) }
                            launch { loadSongWiki(songId) }
                            launch { loadChorus(songId) }
                        }
                    }
                }
        }
    }

    private fun clearState(isLiked: Boolean = false) {
        _songDetailState.value = PlayerSongDetailState(isLiked = isLiked)
        _currentLyricIndex.value = -1
        _songLyricOffset.value = 0L
    }

    private fun observePosition() {
        viewModelScope.launch {
            combine(
                playerManager.currentPosition,
                _songLyricOffset
            ) { positionMs, offsetMs ->
                positionMs + offsetMs
            }.collectLatest { effectivePosition ->
                val lines = _songDetailState.value.lyrics
                if (lines.isEmpty()) return@collectLatest
                _currentLyricIndex.value = findLyricIndex(lines, effectivePosition)
            }
        }
    }

    private suspend fun loadLyrics(songId: Long) {
        if (_songDetailState.value.lyrics.isEmpty()) {
            _songDetailState.update { it.copy(isLyricsLoading = true) }
        }
        playbackRepository.getLyrics(songId).collect { result ->
            if (currentSongId != songId) return@collect
            result.onSuccess { lines ->
                if (currentSongId == songId) {
                    _songDetailState.update { it.copy(lyrics = lines, isLyricsLoading = false) }
                }
            }.onFailure {
                if (currentSongId == songId && _songDetailState.value.lyrics.isEmpty()) {
                    _songDetailState.update { it.copy(lyrics = emptyList(), isLyricsLoading = false) }
                }
            }
        }
    }

    private suspend fun loadSongDetail(songId: Long) {
        playerRepository.getSongDetail(songId).collect { result ->
            if (currentSongId != songId) return@collect
            val track = result.getOrNull() ?: run {
                val current = playerManager.currentTrack.value
                val title = YouTubeIdRegistry.getTitle(songId).ifEmpty { current?.mediaMetadata?.title?.toString() ?: "" }
                val artist = YouTubeIdRegistry.getArtist(songId).ifEmpty { current?.mediaMetadata?.artist?.toString() ?: "" }
                if (title.isNotBlank() || artist.isNotBlank()) {
                    val aId = if (artist.isNotBlank()) YouTubeIdRegistry.registerArtistByName(artist) else 0L
                    val artistsList = if (aId > 0) listOf(Artist(id = aId, name = artist)) else emptyList()
                    val albumName = YouTubeIdRegistry.getAlbumName(songId)
                    Track(
                        id = songId,
                        name = title,
                        ar = artistsList,
                        al = Album(
                            id = if (albumName.isNotBlank()) albumName.hashCode().toLong() and 0x7FFFFFFFL else 0L,
                            name = albumName,
                            picUrl = YouTubeIdRegistry.getCoverUrl(songId)
                        ),
                        dt = YouTubeIdRegistry.getDurationSec(songId).toLong() * 1000L
                    )
                } else null
            }

            if (track != null) {
                val enrichedArtists = track.ar.map { ar ->
                    if (ar.id > 0) ar
                    else {
                        val aId = YouTubeIdRegistry.registerArtistByName(ar.name)
                        ar.copy(id = aId)
                    }
                }.let { list ->
                    if (list.isEmpty()) {
                        val artName = YouTubeIdRegistry.getArtist(songId)
                        if (artName.isNotBlank()) {
                            val split = artName.split(Regex("""[,/、|\\+x]|(\s+&\s+)|(\bfeat\.?\b)|(\bft\.?\b)""", RegexOption.IGNORE_CASE))
                                .map { it.trim() }
                                .filter { it.isNotBlank() }
                                .ifEmpty { listOf(artName) }
                            split.map { aName ->
                                Artist(id = YouTubeIdRegistry.registerArtistByName(aName), name = aName)
                            }
                        } else emptyList()
                    } else list
                }
                val fixedTrack = if (enrichedArtists != track.ar) track.copy(ar = enrichedArtists) else track
                _songDetailState.update { it.copy(songDetail = fixedTrack) }
                val validArtists = fixedTrack.ar.filter { it.id > 0 }
                if (validArtists.isNotEmpty()) {
                    val initialItems = validArtists.map { ar ->
                        ArtistCardItem(
                            artistId = ar.id,
                            artistName = ar.name,
                            isLoading = true
                        )
                    }
                    _songDetailState.update {
                        it.copy(
                            artists = initialItems,
                            selectedArtistIndex = 0
                        )
                    }
                    coroutineScope {
                        // All of Details and Follow
                        validArtists.forEach { ar ->
                            launch { loadSingleArtistCard(ar.id) }
                        }
                        // of MoreAlbums and Recommended
                        val firstId = validArtists.first().id
                        launch { loadArtistAlbums(firstId) }
                        launch { loadSimilarArtists(firstId) }
                    }
                } else {
                    _songDetailState.update {
                        it.copy(
                            artists = emptyList(),
                            selectedArtistIndex = 0,
                            artistAlbums = emptyList(),
                            similarArtists = emptyList()
                        )
                    }
                }
            }
        }
    }

    // SongsDetails and
    private suspend fun loadSongWiki(songId: Long) {
        _songDetailState.update { it.copy(isSongWikiLoading = true) }
        playerRepository.getSongWiki(songId).collect { result ->
            val wiki = result.getOrNull() ?: run {
                val album = YouTubeIdRegistry.getAlbumName(songId)
                val artist = YouTubeIdRegistry.getArtist(songId)
                if (album.isNotBlank() || artist.isNotBlank()) {
                    SongWikiData(
                        album = album,
                        creators = artist,
                        creatorRoles = if (artist.isNotBlank()) listOf(SongWikiCreatorRole(roleName = "Artist", artistNames = artist.split(",").map { it.trim() })) else emptyList()
                    )
                } else null
            }
            _songDetailState.update { it.copy(songWiki = wiki) }
        }
        _songDetailState.update { it.copy(isSongWikiLoading = false) }
    }

    // Songs id
    private suspend fun loadChorus(songId: Long) {
        if (songId <= 0L) return
        playerRepository.getChorusStartTime(songId).collect { result ->
            _songDetailState.update { it.copy(chorusStartMs = result.getOrNull()) }
        }
    }

    private suspend fun loadSimilarArtists(artistId: Long) {
        _songDetailState.update { it.copy(isSimilarArtistsLoading = true) }
        artistRepository.getSimilarArtists(artistId).collect { result ->
            result.onSuccess { artists ->
                _songDetailState.update { it.copy(similarArtists = artists) }
            }.onFailure {
                _songDetailState.update { it.copy(similarArtists = emptyList()) }
            }
        }
        _songDetailState.update { it.copy(isSimilarArtistsLoading = false) }
    }

    // of 、Followers and Follow
    private suspend fun loadSingleArtistCard(artistId: Long) = coroutineScope {
        var detail: ArtistDetailInfo? = null
        var fans: Long? = null
        var followed = false

        val detailJob = launch {
            artistRepository.getArtistDetail(artistId).collect { res ->
                detail = res.getOrNull()
            }
        }
        val fansJob = launch {
            artistRepository.getArtistFansCount(artistId).collect { res ->
                fans = res.getOrNull()
            }
        }
        val followJob = launch {
            artistRepository.checkArtistFollowed(artistId).collect { res ->
                followed = res.getOrDefault(false)
            }
        }
        joinAll(detailJob, fansJob, followJob)

        val fallbackName = YouTubeIdRegistry.getArtistName(artistId).ifBlank {
            _songDetailState.value.artists.find { it.artistId == artistId }?.artistName ?: ""
        }

        var currentDetail = detail ?: ArtistDetailInfo(
            id = artistId,
            name = fallbackName,
            cover = "",
            avatar = "",
            briefDesc = "",
            albumSize = 0,
            musicSize = 0,
            identifyTag = emptyList()
        )

        val artistLookupName = currentDetail.name.ifBlank { fallbackName }
        var mimikyuMonthly: Long? = null

        // Last.fm authentic listener and bio data
        if (lastFmRepository != null && artistLookupName.isNotBlank()) {
            val lastFmInfo = lastFmRepository.getArtistInfo(artistLookupName)
            if (lastFmInfo != null) {
                if (lastFmInfo.listeners > 0L) {
                    fans = lastFmInfo.listeners
                }
                if (currentDetail.briefDesc.isBlank() && lastFmInfo.bioSummary.isNotBlank()) {
                    currentDetail = currentDetail.copy(briefDesc = lastFmInfo.bioSummary)
                }
            }
        }

        // Kworb authentic monthly listeners
        val mimikyuStats = mimikyuArtistStatsRepository?.getStats(artistLookupName)
        if (mimikyuStats != null && mimikyuStats.monthlyListeners > 0L) {
            mimikyuMonthly = mimikyuStats.monthlyListeners
            if (fans == null || fans == 0L) {
                fans = mimikyuStats.monthlyListeners
            }
        }

        if (artistLookupName.isNotBlank()) {
            // Audiometa API for monthly listeners and artist image
            runCatching {
                val audiometaInfo = com.lin0721.linmusic.core.audiometa.AudiometaRepository().getArtistInfo(artistLookupName)
                val metaMonthly = audiometaInfo?.mimikyuMonthlyListeners
                if (metaMonthly != null && metaMonthly > 0L && mimikyuMonthly == null) {
                    mimikyuMonthly = metaMonthly
                    if (fans == null || fans == 0L) fans = metaMonthly
                }
                val artistImg = audiometaInfo?.images?.firstOrNull()
                if (!artistImg.isNullOrBlank() && currentDetail.cover.isBlank() && currentDetail.avatar.isBlank()) {
                    currentDetail = currentDetail.copy(cover = artistImg, avatar = artistImg)
                }
            }
        }

        // If cover is still empty, fallback to the current track's album cover if available
        if (currentDetail.cover.isBlank() && currentDetail.avatar.isBlank()) {
            val trackCover = _songDetailState.value.songDetail?.al?.picUrl ?: ""
            if (trackCover.isNotBlank()) {
                currentDetail = currentDetail.copy(cover = trackCover, avatar = trackCover)
            }
        }

        _songDetailState.update { state ->
            val updated = state.artists.map { item ->
                if (item.artistId == artistId) {
                    item.copy(
                        artistDetail = currentDetail,
                        fansCount = fans,
                        mimikyuMonthlyListeners = mimikyuMonthly,
                        isFollowed = followed,
                        isLoading = false
                    )
                } else item
            }
            state.copy(artists = updated)
        }
    }

    private suspend fun loadArtistAlbums(artistId: Long) {
        _songDetailState.update { it.copy(isArtistAlbumsLoading = true) }
        artistRepository.getArtistAlbums(artistId).collect { result ->
            result.onSuccess { page ->
                _songDetailState.update { it.copy(artistAlbums = page.albums) }
            }.onFailure {
                _songDetailState.update { it.copy(artistAlbums = emptyList()) }
            }
        }
        _songDetailState.update { it.copy(isArtistAlbumsLoading = false) }
    }

    private fun findLyricIndex(lines: List<LyricLine>, positionMs: Long): Int {
        if (lines.none { it.isSynced && !it.isGap }) return -1
        var lo = 0
        var hi = lines.size - 1
        var result = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (lines[mid].timeMs <= positionMs) {
                result = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return result
    }



    private var artistRelatedJob: Job? = null

    // of ， of MoreAlbums and
    fun selectArtist(index: Int) {
        val state = _songDetailState.value
        if (index < 0 || index >= state.artists.size || index == state.selectedArtistIndex) return
        _songDetailState.update { it.copy(selectedArtistIndex = index) }
        val targetArtist = state.artists[index]
        artistRelatedJob?.cancel()
        artistRelatedJob = viewModelScope.launch {
            launch { loadArtistAlbums(targetArtist.artistId) }
            launch { loadSimilarArtists(targetArtist.artistId) }
        }
    }

    // Artists of Follow（Current of ）
    fun toggleArtistFollow(targetArtistId: Long? = null) {
        val state = _songDetailState.value
        val artistId = targetArtistId ?: state.currentArtistItem?.artistId ?: return
        if (artistId <= 0) return

        val targetItem = state.artists.find { it.artistId == artistId } ?: state.currentArtistItem ?: return
        val targetFollow = !targetItem.isFollowed
        viewModelScope.launch {
            artistRepository.subscribeArtist(artistId, targetFollow).collect { result ->
                result.onSuccess {
                    _songDetailState.update { currState ->
                        val updated = currState.artists.map { item ->
                            if (item.artistId == artistId) item.copy(isFollowed = targetFollow) else item
                        }
                        currState.copy(artists = updated)
                    }
                }
            }
        }
    }

    fun seekToTime(timeMs: Long) {
        playerManager.seekTo(timeMs)
    }

    val sleepTimerRemaining: StateFlow<Long> = playerManager.sleepTimerRemaining

    fun setSleepTimer(minutes: Int) {
        playerManager.setSleepTimer(minutes)
    }

    // Songs
    fun startSimilarSongsRoaming(songId: Long, currentTitle: String, currentArtist: String, currentCoverUrl: String) {
        viewModelScope.launch {
            playbackRepository.getSimilarSongs(songId).collect { result ->
                result.onSuccess { simiSongs ->
                    if (simiSongs.isNotEmpty()) {
                        val currentItem = QueueItem(songId, currentTitle, currentArtist, currentCoverUrl)
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
                        _toastEvent.emit("Similar tracks roaming started")
                    } else {
                        _toastEvent.emit("No similar tracks found")
                    }
                }.onFailure {
                    _toastEvent.emit(it.toUserMessage(resourceProvider))
                }
            }
        }
    }

    // Heartbeat Mode，CurrentPlaySongs
    fun startIntelligenceMode(songId: Long, currentTitle: String, currentArtist: String, currentCoverUrl: String) {
        viewModelScope.launch {
            playbackRepository.getIntelligenceSongs(songId, 0).collect { result ->
                result.onSuccess { tracks ->
                    if (tracks.isNotEmpty()) {
                        val currentItem = QueueItem(songId, currentTitle, currentArtist, currentCoverUrl)
                        val items = listOf(currentItem) + tracks.map { track ->
                            QueueItem(
                                songId = track.id,
                                title = track.name,
                                artist = track.ar.joinToString("/") { it.name },
                                coverUrl = track.al.picUrl
                            )
                        }
                        playerManager.playQueue(items, 0, playContext = PlayerManager.CONTEXT_INTELLIGENCE)
                        _toastEvent.emit("Heartbeat mode enabled")
                    } else {
                        _toastEvent.emit("Failed to load recommendations")
                    }
                }.onFailure {
                    _toastEvent.emit(it.toUserMessage(resourceProvider))
                }
            }
        }
    }

    // SongsNext
    fun insertSimilarSongs(songId: Long) {
        viewModelScope.launch {
            playbackRepository.getSimilarSongs(songId).collect { result ->
                result.onSuccess { simiSongs ->
                    val firstSong = simiSongs.firstOrNull()
                    if (firstSong != null) {
                        val simiItem = QueueItem(
                            songId = firstSong.id,
                            title = firstSong.name,
                            artist = firstSong.ar.joinToString("/") { it.name },
                            coverUrl = firstSong.al.picUrl
                        )
                        playerManager.addToPlayNext(listOf(simiItem))
                        _toastEvent.emit("Added '${firstSong.name}' to play next")
                    } else {
                        _toastEvent.emit("No similar tracks available")
                    }
                }.onFailure {
                    _toastEvent.emit(it.toUserMessage(resourceProvider))
                }
            }
        }
    }

    // ClearPlayQueue
    fun clearQueue() {
        playerManager.clearQueue()
    }
}
