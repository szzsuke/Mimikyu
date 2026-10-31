package com.lin0721.linmusic.core.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import java.util.Collections
import com.lin0721.linmusic.core.download.DownloadPreferences
import com.lin0721.linmusic.core.download.DownloadTrackInfo
import com.lin0721.linmusic.core.download.SongDownloadManager
import com.lin0721.linmusic.core.localmusic.LocalCoverArtCache
import com.lin0721.linmusic.core.log.AppLogger
import com.lin0721.linmusic.core.network.AppError
import com.lin0721.linmusic.core.player.data.PlaybackRepository
import com.lin0721.linmusic.core.player.resolver.OfficialAudioTrackResolver
import com.lin0721.linmusic.core.preferences.SettingsPreferences
import com.lin0721.linmusic.core.player.automix.FastTrackAnalyzer
import com.lin0721.linmusic.core.player.automix.SmartAutomixPlanner
import com.lin0721.linmusic.core.player.automix.TransitionStyle
import com.lin0721.linmusic.core.player.recommendation.BitChordQueueBuilder
import com.lin0721.linmusic.core.sponsorblock.SponsorBlockClient
import com.lin0721.linmusic.core.sponsorblock.SponsorBlockController
import com.lin0721.linmusic.core.player.innertube.YouTubeIdRegistry
import com.lin0721.linmusic.core.ui.components.ToastManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "PlayerManager"

// Retry of ï¼Œ
private val NETWORK_RETRY_DELAYS_MS = longArrayOf(2000L, 5000L, 10000L)

// Queue of ï¼Œ of
private const val QUEUE_MOVE_SAVE_DEBOUNCE_MS = 400L

// Play of ï¼Œ
private const val PERIODIC_STATE_SAVE_INTERVAL_MS = 3000L

// Prefetched stream with expiration timestamp
private data class PrefetchedStream(val songId: Long, val videoId: String, val url: String, val expiresAtMs: Long)

// Playback facade: exposes state and control entries to UI, delegating to resolver and components
class PlayerManager(
    private val context: Context,
    private val playbackPreferences: PlaybackPreferences,
    private val repository: PlaybackRepository,
    private val settingsPreferences: SettingsPreferences,
    private val downloadPreferences: DownloadPreferences,
    private val localCoverArtCache: LocalCoverArtCache,
    private val songDownloadManager: SongDownloadManager,
    private val metadataEnricher: com.lin0721.linmusic.core.metadata.MetadataEnricher? = null,
    private val playbackResolver: com.lin0721.linmusic.core.player.resolver.PlaybackResolver? = null,
    private val playRecorder: PlayRecorder? = null,
    private val recommendationEngine: com.lin0721.linmusic.core.player.recommendation.SmartRecommendationEngine? = null
) : Player.Listener {

    private val recorder: PlayRecorder = playRecorder ?: PlayRecorder(com.lin0721.linmusic.core.auth.AuthState())

    private val effectiveResolver: com.lin0721.linmusic.core.player.resolver.PlaybackResolver =
        playbackResolver ?: com.lin0721.linmusic.core.player.resolver.PlaybackResolverImpl(context, null, settingsPreferences)

    private val effectiveRecommendationEngine: com.lin0721.linmusic.core.player.recommendation.SmartRecommendationEngine =
        recommendationEngine ?: com.lin0721.linmusic.core.player.recommendation.SmartRecommendationEngine(context, repository)

    companion object {
        const val CONTEXT_INTELLIGENCE = "intelligence"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // Playï¼šPlayï¼Œã€‚ isPlaying  falseï¼Œ
    private val _playWhenReady = MutableStateFlow(false)
    val playWhenReady: StateFlow<Boolean> = _playWhenReady.asStateFlow()

    private val _currentTrack = MutableStateFlow<MediaItem?>(null)
    val currentTrack: StateFlow<MediaItem?> = _currentTrack.asStateFlow()

    // "Connect Device" of  idï¼›null ï¼Œ
    private val _preferredOutputDeviceId = MutableStateFlow<Int?>(null)
    val preferredOutputDeviceId: StateFlow<Int?> = _preferredOutputDeviceId.asStateFlow()

    private val controllerHolder = MediaControllerHolder(context)
    private val playbackQueue = PlaybackQueue()
    private val progress = PlaybackProgressTracker(scope, controllerHolder)
    private val stateStore = PlaybackStateStore(scope, playbackPreferences)
    private val coverPreloader = TrackCoverPreloader(context)
    private val sleepTimer = SleepTimer(scope) { pause() }
    private val roaming = SimilarRoamingController(scope, repository, settingsPreferences, playbackQueue, stateStore)
    private val networkGuard = PlaybackNetworkGuard(
        context = context,
        scope = scope,
        settingsPreferences = settingsPreferences,
        isPlaying = { _isPlaying.value },
        onPauseRequested = { pause() }
    )
    private val sponsorBlockController = SponsorBlockController(
        scope = scope,
        sponsorBlockClient = SponsorBlockClient(),
        isEnabled = { true },
        currentPositionProvider = { progress.currentPosition.value },
        onSeekRequested = { posMs -> seekTo(posMs) },
        onSkippedNotice = { /* Silent - do not show pop up */ }
    )

    val currentPosition: StateFlow<Long> = progress.currentPosition
    val duration: StateFlow<Long> = progress.duration
    val positionUpdateInterval: StateFlow<Long> = progress.updateInterval
    val sleepTimerRemaining: StateFlow<Long> = sleepTimer.remaining
    val playContext: StateFlow<String?> = playbackQueue.playContext
    val currentIndex: StateFlow<Int> = playbackQueue.currentIndex
    val currentMediaItemIndex: StateFlow<Int> = playbackQueue.currentIndex
    val playMode: StateFlow<PlayMode> = playbackQueue.playMode
    val queue: StateFlow<List<QueueItem>> = playbackQueue.items

    // CurrentPlayQueueï¼Œ localUri
    val currentQueueItem: StateFlow<QueueItem?> = combine(playbackQueue.items, playbackQueue.currentIndex) { items, index ->
        if (index in items.indices) items[index] else null
    }.stateIn(scope, SharingStarted.Eagerly, null)

    // Artistsï¼šQueueï¼Œ null
    val previousQueueItem: StateFlow<QueueItem?> = combine(playbackQueue.items, playbackQueue.currentIndex) { items, index ->
        if (items.size > 1 && index in items.indices) items[(index - 1 + items.size) % items.size] else null
    }.stateIn(scope, SharingStarted.Eagerly, null)

    val nextQueueItem: StateFlow<QueueItem?> = combine(playbackQueue.items, playbackQueue.currentIndex) { items, index ->
        if (items.size > 1 && index in items.indices) items[(index + 1) % items.size] else null
    }.stateIn(scope, SharingStarted.Eagerly, null)

    private var activeLoadJob: Job? = null
    private var moveSaveJob: Job? = null
    private var consecutiveErrors = 0
    private var isPlayingLowQuality = false
    private var stallWatchdogJob: Job? = null
    private var upgradeMonitorJob: Job? = null

    // Artistsï¼š fetchUrlAndPlay  of Queueï¼ŒplayItem
    private var pendingSkipFromIndex: Int? = null

    // ï¼ŒReset 0
    private var pendingStartPosition: Long = 0L

    // "Renewing stream url and resuming playback" of ï¼ŒClearï¼ŒRetry
    private var streamErrorRecoverySongId: Long? = null

    // PlaySave of
    private var lastPeriodicSaveElapsedMs: Long = 0L

    // Crossfade & Automix settings
    @Volatile private var crossfadeEnabled = false
    @Volatile private var automixEnabled = true
    @Volatile private var crossfadeDurationMs = CrossfadePolicy.DEFAULT_DURATION_MS

    // Automix & Crossfade state
    private var autoCrossfadeTriggeredSongId: Long = -1L
    private var autoCrossfadeTriggeredTimeMs: Long = 0L
    private var lastMediaItemTransitionMs: Long = 0L
    private var lastAutoAdvanceMs: Long = 0L
    private var pendingAutoFadeMs: Long = 0L
    private var pendingBassSwap: Boolean = false
    private var autoCrossfadeScheduledSongId: Long = -1L
    private var autoCrossfadeJob: Job? = null
    private val edgeAnalyzer = TrackEdgeAnalyzer(context)
    private val fastTrackAnalyzer = FastTrackAnalyzer(context, edgeAnalyzer)
    private var pendingTargetSongId: Long? = null

    // of Play (songId, uri)ï¼ŒCurrent of
    private var requestedSource: Pair<Long, String>? = null
    // Next of  (songId, )ï¼Œ
    private var pendingStartOffset: Pair<Long, Long>? = null

    private var prefetchJob: Job? = null
    private var prefetchedStream: PrefetchedStream? = null
    private var prefetchTriggeredForSongId: Long = -1L

    private val streamCacheTriggeredSongIds = Collections.synchronizedSet(mutableSetOf<Long>())

    // Clock tracking for playback duration
    private var trackStartElapsedMs: Long = 0L
    private var trackPausedAccumMs: Long = 0L
    private var trackLastPauseElapsedMs: Long? = null

    // Deferred queue restoration on cold launch
    private val queueRestored = CompletableDeferred<Unit>()

    suspend fun awaitQueueRestored() = queueRestored.await()

    init {
        AppLogger.i(TAG, "PlayerManager initialized instanceId=${System.identityHashCode(this)}")
        networkGuard.register()

        scope.launch {
            playbackQueue.setPlayMode(stateStore.loadPlayMode())
            try {
                val qs = stateStore.loadQueueState()
                playbackQueue.restore(qs.queue, qs.currentIndex, qs.playContext)
                qs.queue.forEach { item ->
                    val vid = item.effectiveVideoId()
                    YouTubeIdRegistry.registerMetadata(
                        songId = item.songId,
                        title = item.title,
                        artist = item.artist,
                        coverUrl = item.coverUrl,
                        videoId = vid
                    )
                }
            } finally {
                queueRestored.complete(Unit)
            }
        }

        // playMode ï¼ŒnewValue != oldValue
        scope.launch {
            stateStore.playModeChanges.collectLatest { newMode ->
                if (playbackQueue.playMode.value != newMode) {
                    applyMode(newMode, playbackQueue.currentItem())
                }
            }
        }

        scope.launch {
            settingsPreferences.crossfadeEnabled.collect { crossfadeEnabled = it }
        }
        scope.launch {
            settingsPreferences.automixEnabled.collect { automixEnabled = it }
        }
        scope.launch {
            settingsPreferences.crossfadeDurationMs.collect { crossfadeDurationMs = it }
        }
        controllerHolder.onBeforePrepare = {
            if (pendingStartPosition <= 0L) {
                progress.setPosition(0L)
                progress.setDuration(0L)
            } else {
                progress.setPosition(pendingStartPosition)
            }
        }

        progress.start(
            onTick = { positionMs ->
                sponsorBlockController.onProgressTick(positionMs)
                val dur = progress.duration.value
                val songId = _currentTrack.value?.mediaId?.toLongOrNull() ?: -1L
                if (dur > 0L && songId != -1L) {
                    val remainingMs = dur - positionMs
                    roaming.onProgressTick(songId, remainingMs)
                    maybeAutoRecommendNextSong(songId, remainingMs)
                    maybePrefetchNextTrackUrl(remainingMs)
                    maybeScheduleAutoCrossfade(songId, remainingMs, dur)
                    maybeCheckStreamCacheProgress(songId, positionMs, dur)
                }

                recorder.onPlaybackTick(isPlaying = _isPlaying.value, durationMs = dur)

                val now = SystemClock.elapsedRealtime()
                if (now - lastPeriodicSaveElapsedMs >= PERIODIC_STATE_SAVE_INTERVAL_MS) {
                    lastPeriodicSaveElapsedMs = now
                    saveState()
                }
            }
        )
    }

    
    fun updateCurrentTrackArtwork(songId: Long, artworkUrl: String) {
        if (artworkUrl.isBlank()) return
        val current = _currentTrack.value ?: return
        if (current.mediaId != songId.toString()) return
        val currentArt = current.mediaMetadata.artworkUri?.toString()
        if (currentArt != artworkUrl) {
            AppLogger.i(TAG, "Updating artwork for playing track $songId to $artworkUrl")
            val updatedMetadata = current.mediaMetadata.buildUpon()
                .setArtworkUri(android.net.Uri.parse(artworkUrl))
                .build()
            val updatedMediaItem = current.buildUpon()
                .setMediaMetadata(updatedMetadata)
                .build()
            _currentTrack.value = updatedMediaItem
        }
    }

    fun updateCurrentTrackMetadata(songId: Long, enriched: com.lin0721.linmusic.core.metadata.EnrichedMetadata) {
        val current = _currentTrack.value ?: return
        if (current.mediaId != songId.toString()) return

        val builder = current.mediaMetadata.buildUpon()
        var changed = false

        val currentArt = current.mediaMetadata.artworkUri?.toString().orEmpty()
        if (!enriched.artworkUrl.isNullOrBlank() && currentArt != enriched.artworkUrl) {
            if (currentArt.isBlank()) {
                builder.setArtworkUri(android.net.Uri.parse(enriched.artworkUrl))
                changed = true
            }
        }
        if (enriched.title.isNotBlank() && current.mediaMetadata.title?.toString() != enriched.title) {
            builder.setTitle(enriched.title)
            changed = true
        }
        if (enriched.artist.isNotBlank() && current.mediaMetadata.artist?.toString() != enriched.artist) {
            builder.setArtist(enriched.artist)
            changed = true
        }
        if (!enriched.albumName.isNullOrBlank() && current.mediaMetadata.albumTitle?.toString() != enriched.albumName) {
            builder.setAlbumTitle(enriched.albumName)
            changed = true
        }

        if (changed) {
            AppLogger.i(TAG, "Updating verified metadata for playing track $songId: '${enriched.title}' by '${enriched.artist}' [${enriched.artSource}]")
            val updatedMediaItem = current.buildUpon()
                .setMediaMetadata(builder.build())
                .build()
            _currentTrack.value = updatedMediaItem
        }
    }

    fun setPositionUpdateInterval(intervalMs: Long) {
        progress.setUpdateInterval(intervalMs)
    }

    fun setSleepTimer(minutes: Int) {
        sleepTimer.start(minutes)
    }

    suspend fun initController() {
        if (controllerHolder.isConnected) return

        // Await queue restoration from persistent storage
        awaitQueueRestored()

        val lastTrack = stateStore.loadLastTrack()
        if (lastTrack != null && _currentTrack.value == null) {
            _currentTrack.value = lastTrack.mediaItem
            progress.setPosition(lastTrack.positionMs)
            if (lastTrack.durationMs > 0L) {
                progress.setDuration(lastTrack.durationMs)
            }
            resetTrackTiming(startPlaying = false)

            if (playbackQueue.isEmpty) {
                val qi = QueueItem(
                    songId = lastTrack.songId,
                    title = lastTrack.title,
                    artist = lastTrack.artist,
                    coverUrl = lastTrack.coverUrl,
                    videoId = lastTrack.videoId,
                    album = lastTrack.album,
                    durationSec = (lastTrack.durationMs / 1000).toInt()
                )
                playbackQueue.replaceWithSingle(qi)
            }
        }

        controllerHolder.connect(this) { mediaController ->
            if (mediaController.currentMediaItem != null) {
                _currentTrack.value = mediaController.currentMediaItem
                _isPlaying.value = mediaController.isPlaying
                progress.setPosition(mediaController.currentPosition)
                progress.setDuration(mediaController.duration)
                resetTrackTiming(startPlaying = mediaController.isPlaying)
            }

            mediaController.repeatMode = if (playbackQueue.playMode.value == PlayMode.SINGLE_LOOP)
                Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        }
    }

    // QueuePlay
    fun playQueue(items: List<QueueItem>, startIndex: Int, playContext: String? = null) {
        if (items.isEmpty()) return

        val enrichedItems = items.map { item ->
            if (item.videoId.isNullOrBlank()) {
                val vid = YouTubeIdRegistry.getVideoId(item.songId)
                if (!vid.isNullOrBlank()) item.copy(videoId = vid) else item
            } else item
        }

        if (playContext == SimilarRoamingController.CONTEXT_ROAMING) {
            roaming.prepare()
        } else if (playContext == CONTEXT_INTELLIGENCE) {
            // Heartbeat Mode of Queueï¼ŒClose
            playbackQueue.takeSnapshot()
        }

        // Now Playing of Songs IDï¼ˆ of ï¼ŒQueueCurrentï¼‰
        val playingSongId = _currentTrack.value?.mediaId?.toLongOrNull()
            ?: playbackQueue.currentItem()?.songId

        playbackQueue.setPlayContext(playContext)
        playbackQueue.replaceAll(enrichedItems, startIndex)
        consecutiveErrors = 0
        saveQueueState()

        val currentIndex = playbackQueue.currentIndex.value
        val targetItem = playbackQueue.itemAt(currentIndex)
        val isCurrentPlayingTrack = targetItem != null && targetItem.songId == playingSongId

        if ((playContext == CONTEXT_INTELLIGENCE || playContext == SimilarRoamingController.CONTEXT_ROAMING) && isCurrentPlayingTrack) {
            // CurrentNow Playing of ï¼Œ and Reset
            roaming.prefetchOnPlay(targetItem.songId, currentIndex)
            return
        }

        fetchUrlAndPlay(currentIndex)
    }

    // TracksPlayï¼ˆï¼ŒCreate 1 Queueï¼‰
    fun playAudio(songId: Long, url: String, title: String, artist: String, coverUrl: String, startPosition: Long = 0, playContext: String? = null) {
        val vid = YouTubeIdRegistry.getVideoId(songId)
        val item = QueueItem(songId = songId, title = title, artist = artist, coverUrl = coverUrl, videoId = vid)
        playbackQueue.replaceWithSingle(item)
        playbackQueue.setPlayContext(playContext)
        consecutiveErrors = 0
        pendingStartPosition = startPosition

        controllerHolder.playItem(item.toMediaItem(url, playContext), playbackQueue.playMode.value, startPosition)
    }

    /**
     * Plays a single track immediately and dynamically populates the upcoming queue with
     * Mimikyu-style recommendations seeded by this track + user listening history / affinity + Last.fm.
     * Prevents linear playback of search results or album tracklists.
     */
    fun playWithDynamicRadio(track: QueueItem, context: String? = "Radio") {
        val enriched = if (track.videoId.isNullOrBlank()) {
            val vid = YouTubeIdRegistry.getVideoId(track.songId)
            if (!vid.isNullOrBlank()) track.copy(videoId = vid) else track
        } else track

        // 1. Immediately replace queue with the chosen track and start playing
        playbackQueue.replaceAll(listOf(enriched), 0)
        playbackQueue.setPlayContext(context)
        consecutiveErrors = 0
        saveQueueState()
        fetchUrlAndPlay(0)

        // 2. Concurrently fetch and enqueue 25 recommendations based on this track + history
        scope.launch {
            try {
                val recommended = effectiveRecommendationEngine.getRecommendedQueueForTrack(
                    seedTrack = enriched,
                    count = 25
                )
                if (recommended.isNotEmpty()) {
                    val curIdx = playbackQueue.currentIndex.value
                    val curItem = playbackQueue.itemAt(curIdx)
                    if (curItem?.songId == enriched.songId) {
                        playbackQueue.insertNext(recommended)
                        saveQueueState()
                        preResolveAndQueueUpcoming(curIdx)
                        AppLogger.i(TAG, "Enqueued ${recommended.size} dynamic radio recommendations for '${enriched.title}'")
                    }
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "Failed to fetch dynamic radio queue", e)
            }
        }
    }

    // Songsâ€œNextâ€Play
    fun addToPlayNext(items: List<QueueItem>) {
        if (items.isEmpty()) return
        val enrichedItems = items.map { item ->
            if (item.videoId.isNullOrBlank()) {
                val vid = YouTubeIdRegistry.getVideoId(item.songId)
                if (!vid.isNullOrBlank()) item.copy(videoId = vid) else item
            } else item
        }
        if (playbackQueue.isEmpty) {
            playQueue(enrichedItems, 0)
            return
        }

        playbackQueue.insertNext(enrichedItems)
        saveQueueState()
    }

    // "Next"Playï¼ŒQueue
    fun playNextNow(item: QueueItem, startPosition: Long = 0L) {
        val enriched = if (item.videoId.isNullOrBlank()) {
            val vid = YouTubeIdRegistry.getVideoId(item.songId)
            if (!vid.isNullOrBlank()) item.copy(videoId = vid) else item
        } else item
        val targetIndex = if (playbackQueue.isEmpty) {
            playbackQueue.replaceAll(listOf(enriched), 0)
            0
        } else {
            playbackQueue.insertNext(listOf(enriched))
            playbackQueue.currentIndex.value + 1
        }
        consecutiveErrors = 0
        saveQueueState()
        fetchUrlAndPlay(targetIndex, startPosition.coerceAtLeast(0L))
    }

    private var lastPlayNextMs = 0L
    private var lastPlayPrevMs = 0L

    fun playNext() {
        if (playbackQueue.isEmpty) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastPlayNextMs < 350L) {
            AppLogger.w(TAG, "playNext debounced: called again within ${now - lastPlayNextMs}ms")
            return
        }
        lastPlayNextMs = now
        consecutiveErrors = 0
        val curIdx = playbackQueue.currentIndex.value
        val nextIdx = playbackQueue.nextIndex()

        // 0ms instant skip if next item is already buffered in native ExoPlayer queue
        val nextItem = playbackQueue.itemAt(nextIdx)
        if (nextItem != null && controllerHolder.hasNextMediaItem()) {
            val nextSongId = nextItem.songId.toString()
            if (controllerHolder.peekNextMediaId() == nextSongId) {
                AppLogger.i(TAG, "[Playback] 0ms instant skip: advancing directly via ExoPlayer native queue")
                playbackQueue.setCurrentIndex(nextIdx)
                saveQueueState()
                _playWhenReady.value = true
                controllerHolder.seekToNextMediaItem()
                return
            }
        }

        if (curIdx >= playbackQueue.size - 1 && playbackQueue.playMode.value != PlayMode.SINGLE_LOOP && playbackQueue.playMode.value != PlayMode.LIST_LOOP) {
            val currentItem = playbackQueue.itemAt(curIdx)
            if (currentItem != null) {
                scope.launch {
                    val nextTrack = effectiveRecommendationEngine.getNextRecommendedTrack(
                        currentTrack = currentItem,
                        activeQueue = playbackQueue.items.value
                    )
                    if (nextTrack != null) {
                        playbackQueue.insertNext(listOf(nextTrack))
                        saveQueueState()
                        fetchUrlAndPlay(curIdx + 1, debounceMs = 250L)
                        return@launch
                    }
                    fetchUrlAndPlay(playbackQueue.nextIndex(), debounceMs = 250L)
                }
                return
            }
        }
        fetchUrlAndPlay(nextIdx, debounceMs = 250L)
    }

    fun playPrevious() {
        if (playbackQueue.isEmpty) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastPlayPrevMs < 350L) {
            AppLogger.w(TAG, "playPrevious debounced: called again within ${now - lastPlayPrevMs}ms")
            return
        }
        lastPlayPrevMs = now
        val position = controllerHolder.currentPosition
        if (position > 3000) {
            seekTo(0)
            return
        }
        consecutiveErrors = 0
        fetchUrlAndPlay(playbackQueue.previousIndex(), debounceMs = 250L)
    }

    fun skipToPrevious() {
        if (playbackQueue.isEmpty) return
        consecutiveErrors = 0
        fetchUrlAndPlay(playbackQueue.previousIndex(), debounceMs = 250L)
    }

    fun playAtIndex(index: Int) {
        if (index < 0 || index >= playbackQueue.size) return
        val targetItem = playbackQueue.itemAt(index)
        val curItem = playbackQueue.currentItem()
        if (index == playbackQueue.currentIndex.value &&
            targetItem != null &&
            curItem?.songId == targetItem.songId &&
            controllerHolder.isPlayingOrBuffering()
        ) {
            AppLogger.i(TAG, "playAtIndex($index): track is already playing; skipping redundant reload")
            return
        }
        consecutiveErrors = 0
        fetchUrlAndPlay(index, debounceMs = 250L)
    }

    fun removeFromQueue(index: Int) {
        if (index < 0 || index >= playbackQueue.size || playbackQueue.size <= 1) return
        val replayIndex = playbackQueue.removeAt(index)
        if (replayIndex >= 0) fetchUrlAndPlay(replayIndex)
        saveQueueState()
    }

    fun moveInQueue(from: Int, to: Int) {
        if (!playbackQueue.move(from, to)) return
        moveSaveJob?.cancel()
        moveSaveJob = scope.launch {
            delay(QUEUE_MOVE_SAVE_DEBOUNCE_MS)
            saveQueueState()
        }
    }

    fun toggleShuffle() {
        val currentItem = playbackQueue.currentItem()
        when (playbackQueue.playMode.value) {
            PlayMode.SHUFFLE -> applyMode(PlayMode.SEQUENTIAL, currentItem)
            else -> applyMode(PlayMode.SHUFFLE, currentItem)
        }
    }

    fun toggleRepeat() {
        val currentItem = playbackQueue.currentItem()
        val nextMode = when (playbackQueue.playMode.value) {
            PlayMode.SEQUENTIAL -> PlayMode.LIST_LOOP
            PlayMode.LIST_LOOP -> PlayMode.SINGLE_LOOP
            PlayMode.SINGLE_LOOP -> PlayMode.SEQUENTIAL
            PlayMode.SHUFFLE -> PlayMode.SINGLE_LOOP
        }
        applyMode(nextMode, currentItem)
    }

    fun rotatePlayMode() {
        val currentItem = playbackQueue.currentItem()
        val nextMode = when (playbackQueue.playMode.value) {
            PlayMode.SEQUENTIAL -> PlayMode.LIST_LOOP
            PlayMode.LIST_LOOP -> PlayMode.SHUFFLE
            PlayMode.SHUFFLE -> PlayMode.SINGLE_LOOP
            PlayMode.SINGLE_LOOP -> PlayMode.SEQUENTIAL
        }
        applyMode(nextMode, currentItem)
    }

    private fun applyMode(newMode: PlayMode, currentItem: QueueItem?) {
        playbackQueue.applyMode(newMode, currentItem)
        controllerHolder.setRepeatMode(newMode)
        stateStore.savePlayMode(newMode)
        saveQueueState()

        // Sync upcoming queue with new order immediately
        prefetchedStream = null
        val activeIdx = playbackQueue.currentIndex.value
        if (activeIdx >= 0) {
            preloadUpcomingCovers(activeIdx)
            preResolveAndQueueUpcoming(activeIdx)
        }
    }

    fun pause() {
        _playWhenReady.value = false
        controllerHolder.pause()
        saveState()
    }

    // Playï¼ˆ MV Playï¼‰"Wi-Fi Only Playback"ï¼Œ and Play of
    suspend fun shouldBlockPlaybackOnMobile(): Boolean = networkGuard.blockPlaybackOnMobile()

    fun resume() {
        val item = _currentTrack.value
        if (item != null && item.localConfiguration == null) {
            scope.launch {
                awaitQueueRestored()
                val indexToPlay = playbackQueue.currentIndex.value.coerceAtLeast(0)
                val pos = progress.currentPosition.value
                _playWhenReady.value = true
                fetchUrlAndPlay(indexToPlay, pos, playWhenReady = true)
            }
            return
        }
        _playWhenReady.value = true
        controllerHolder.play()
    }

    fun seekTo(positionMs: Long) {
        autoCrossfadeScheduledSongId = -1L
        autoCrossfadeTriggeredSongId = -1L
        autoCrossfadeJob?.cancel()
        autoCrossfadeJob = null
        pendingAutoFadeMs = 0L
        // Preserve pre-armed standby player on seek within same track

        controllerHolder.seekTo(positionMs)
        progress.setPosition(positionMs)
        val songId = _currentTrack.value?.mediaId?.toLongOrNull() ?: -1L
        if (songId != -1L) {
            val dur = progress.duration.value
            if (dur > 0L) {
                val remainingMs = dur - positionMs
                maybeScheduleAutoCrossfade(songId, remainingMs, dur)
            }
            maybeCheckStreamCacheProgress(songId, positionMs, progress.duration.value)
        }
        saveState()
    }

    fun setPreferredAudioDevice(deviceId: Int) {
        controllerHolder.setPreferredAudioDevice(deviceId)
        _preferredOutputDeviceId.value = deviceId
    }

    fun togglePlayPause() {
        val item = _currentTrack.value ?: return
        if (!_isPlaying.value && item.localConfiguration == null) {
            scope.launch {
                awaitQueueRestored()
                if (playbackQueue.isEmpty) {
                    val songId = item.mediaId.toLongOrNull() ?: return@launch
                    val title = item.mediaMetadata.title?.toString().orEmpty()
                    val artist = item.mediaMetadata.artist?.toString().orEmpty()
                    val cover = item.mediaMetadata.artworkUri?.toString().orEmpty()
                    val videoId = item.mediaMetadata.extras?.getString("videoId")
                    val album = item.mediaMetadata.albumTitle?.toString()
                    val qi = QueueItem(
                        songId = songId,
                        title = title,
                        artist = artist,
                        coverUrl = cover,
                        videoId = videoId,
                        album = album
                    )
                    playbackQueue.replaceWithSingle(qi)
                }
                _playWhenReady.value = true
                val indexToPlay = playbackQueue.currentIndex.value.coerceAtLeast(0)
                fetchUrlAndPlay(indexToPlay, progress.currentPosition.value, playWhenReady = true)
            }
            return
        }
        if (_isPlaying.value) pause() else resume()
    }

    fun saveState() {
        val item = _currentTrack.value ?: return
        val songId = item.mediaId.toLongOrNull() ?: -1L
        if (songId == -1L) return

        val duration = controllerHolder.duration.takeIf { it > 0L } ?: progress.duration.value
        val videoId = item.mediaMetadata.extras?.getString("videoId")
            ?: YouTubeIdRegistry.getVideoId(songId)
            ?: ""

        val album = item.mediaMetadata.albumTitle?.toString()
            ?: item.mediaMetadata.extras?.getString("album")
            ?: YouTubeIdRegistry.getAlbumName(songId)

        stateStore.savePlaybackState {
            PlaybackState(
                songId = songId,
                title = item.mediaMetadata.title?.toString() ?: "",
                artist = item.mediaMetadata.artist?.toString() ?: "",
                album = album,
                coverUrl = item.mediaMetadata.artworkUri?.toString() ?: "",
                lastPositionMs = controllerHolder.currentPositionOrNull ?: progress.currentPosition.value,
                durationMs = duration,
                videoId = videoId,
                isPlaying = _isPlaying.value
            )
        }
    }

    // Clear queue keeping only current playing song and synchronize persistent state
    fun clearQueue() {
        val currentTrackItem = playbackQueue.keepOnlyCurrent()

        if (currentTrackItem != null) {
            _isPlaying.value = false
            progress.setPosition(0L)

            controllerHolder.pauseAndRewind()

            saveQueueState()
            val videoId = currentTrackItem.effectiveVideoId() ?: YouTubeIdRegistry.getVideoId(currentTrackItem.songId) ?: ""
            stateStore.savePlaybackState {
                PlaybackState(
                    songId = currentTrackItem.songId,
                    title = currentTrackItem.title,
                    artist = currentTrackItem.artist,
                    album = currentTrackItem.album ?: "",
                    coverUrl = _currentTrack.value?.mediaMetadata?.artworkUri?.toString() ?: currentTrackItem.coverUrl,
                    lastPositionMs = 0L,
                    durationMs = progress.duration.value,
                    videoId = videoId,
                    isPlaying = false
                )
            }
        } else {
            _currentTrack.value = null
            _isPlaying.value = false
            progress.setPosition(0L)
            progress.setDuration(0L)

            controllerHolder.stopAndClear()

            saveQueueState()
            stateStore.savePlaybackState {
                PlaybackState(
                    songId = -1L,
                    title = "",
                    artist = "",
                    album = "",
                    coverUrl = "",
                    lastPositionMs = 0L,
                    durationMs = 0L,
                    videoId = "",
                    isPlaying = false
                )
            }
        }
    }

    private fun saveQueueState() {
        stateStore.saveQueue(playbackQueue)
    }

    private fun fetchUrlAndPlay(
        index: Int,
        startPosition: Long = 0,
        networkRetryAttempt: Int = 0,
        prefetchedUrl: String? = null,
        playWhenReady: Boolean = true,
        autoTransition: Boolean = false,
        forceRefresh: Boolean = false,
        debounceMs: Long = 0L
    ) {
        val item = playbackQueue.itemAt(index) ?: return

        // 0. Idempotent Guard: if this track is already playing or buffering on active ExoPlayer,
        // and we are not forcing a refresh, skip reload completely to prevent 0:00 restarts!
        val currentPlayingTrackId = _currentTrack.value?.mediaId
        if (!forceRefresh && currentPlayingTrackId != null &&
            currentPlayingTrackId == item.songId.toString() &&
            controllerHolder.playbackState != Player.STATE_IDLE &&
            controllerHolder.playbackState != Player.STATE_ENDED &&
            startPosition <= 0L
        ) {
            AppLogger.i(TAG, "Idempotent guard: track ${item.songId} '${item.title}' is already active and not IDLE; skipping reload.")
            if (playWhenReady && !_isPlaying.value) {
                resume()
            }
            return
        }

        activeLoadJob?.cancel()
        stallWatchdogJob?.cancel()
        upgradeMonitorJob?.cancel()
        roaming.cancel()
        networkGuard.cancelRecoveryWait()
        pendingStartPosition = startPosition

        val fromIndex = playbackQueue.currentIndex.value
        playbackQueue.setCurrentIndex(index)
        pendingTargetSongId = item.songId
        saveQueueState()

        // 1. Optimistic UI Updates: immediately set current track and loading/buffering state
        val originalCover = item.coverUrl.takeIf { it.isNotBlank() }
        val cached = metadataEnricher?.getCached(item.songId.toString())
            ?: metadataEnricher?.getCachedByQuery(item.title, item.artist)
        val effectiveArt = originalCover
            ?: cached?.artworkUrl?.takeIf { it.isNotBlank() }
            ?: YouTubeIdRegistry.getCoverUrl(item.songId).takeIf { it.isNotBlank() }
            ?: ""
        val effectiveTitle = cached?.title?.takeIf { it.isNotBlank() } ?: item.title
        val effectiveArtist = cached?.artist?.takeIf { it.isNotBlank() } ?: item.artist

        val optimisticItem = item.copy(
            title = effectiveTitle,
            artist = effectiveArtist,
            coverUrl = effectiveArt
        )
        val optimisticMediaItem = optimisticItem.toMediaItem(item.localUri ?: "about:blank", playbackQueue.playContext.value, effectiveArt)
        _currentTrack.value = optimisticMediaItem
        _playWhenReady.value = playWhenReady
        if (autoTransition) {
            _isPlaying.value = true
            _isLoading.value = false
        } else {
            _isPlaying.value = false
            _isLoading.value = true
            if (startPosition <= 0L) {
                progress.resetTo(0L, preserveDuration = false)
                progress.setPosition(0L)
                progress.setDuration(0L)
            } else {
                progress.resetTo(startPosition, preserveDuration = true)
            }
        }

        // Preload next 2 album covers (N+1 and N+2) in Coil memory cache immediately
        preloadUpcomingCovers(index)

        // 4. Background Lazy-Loading for Lyrics: prefetch immediately on Dispatchers.IO
        scope.launch(Dispatchers.IO) {
            runCatching {
                if (effectiveTitle.isNotBlank()) {
                    repository.getLyrics(item.songId).firstOrNull()
                }
            }
        }

        activeLoadJob = scope.launch {
            if (debounceMs > 0L) {
                delay(debounceMs)
            }
            // Local external audio played directly
            if (item.localUri != null) {
                progress.resetTo(startPosition, preserveDuration = startPosition > 0L)
                val artworkUri = localCoverArtCache.coverUriFor(android.net.Uri.parse(item.localUri))?.toString()
                    ?: item.coverUrl
                val mediaItem = item.toMediaItem(item.localUri, playbackQueue.playContext.value, artworkUri)
                controllerHolder.playItem(mediaItem.withCrossfade(autoTransition, startPosition), playbackQueue.playMode.value, startPosition, playWhenReady)
        preArmNextUpcomingTrack(index)
                        preResolveAndQueueUpcoming(index)
        val remainingInQueue = playbackQueue.size - 1 - index
        if (remainingInQueue < 3) {
            maybeAutoRecommendNextSong(item.songId, remainingMs = 0L, forceImmediate = true)
        }
                return@launch
            }

            // Local downloaded track prioritized
            val localRecord = downloadPreferences.findVerifiedRecord(item.songId)

            if (localRecord == null && networkGuard.blockPlaybackOnMobile()) {
                pendingStartPosition = 0L
                _isLoading.value = false
                return@launch
            }

            pendingSkipFromIndex = fromIndex
            progress.resetTo(startPosition, preserveDuration = startPosition > 0L)
            roaming.prefetchOnPlay(item.songId, index)

            if (localRecord != null) {
                val artworkUri = localCoverArtCache.coverUriFor(android.net.Uri.parse(localRecord.mediaStoreUri))?.toString()
                    ?: item.coverUrl
                val mediaItem = item.toMediaItem(localRecord.mediaStoreUri, playbackQueue.playContext.value, artworkUri)
                controllerHolder.playItem(mediaItem.withCrossfade(autoTransition, startPosition), playbackQueue.playMode.value, startPosition, playWhenReady)
        preArmNextUpcomingTrack(index)
                        preResolveAndQueueUpcoming(index)
        val remainingInQueue = playbackQueue.size - 1 - index
        if (remainingInQueue < 3) {
            maybeAutoRecommendNextSong(item.songId, remainingMs = 0L, forceImmediate = true)
        }
                return@launch
            }

            val cached = metadataEnricher?.getCached(item.songId.toString())
                ?: metadataEnricher?.getCachedByQuery(item.title, item.artist)
            val effectiveArt = cached?.artworkUrl ?: item.coverUrl
            val effectiveTitle = cached?.title?.takeIf { it.isNotBlank() } ?: item.title
            val effectiveArtist = cached?.artist?.takeIf { it.isNotBlank() } ?: item.artist

            metadataEnricher?.enrichInBackground(
                songId = item.songId.toString(),
                title = effectiveTitle,
                artist = effectiveArtist,
                album = cached?.albumName ?: YouTubeIdRegistry.getAlbumName(item.songId),
                fallbackCoverUrl = effectiveArt
            ) { enriched ->
                playbackQueue.updateItemMetadata(
                    songId = item.songId,
                    title = enriched.title.takeIf { it.isNotBlank() },
                    artist = enriched.artist.takeIf { it.isNotBlank() },
                    coverUrl = enriched.artworkUrl
                )
                updateCurrentTrackMetadata(item.songId, enriched)
            }

            val atvResult = runCatching {
                OfficialAudioTrackResolver.resolve(
                    rawVideoId = item.effectiveVideoId(),
                    rawTitle = item.title,
                    rawArtist = item.artist,
                    currentDurationSec = item.durationSec,
                    currentCoverUrl = item.coverUrl
                )
            }.getOrNull()

            val effectiveItem = if (atvResult != null) {
                val preservedCover = if (item.coverUrl.isNotBlank()) item.coverUrl else atvResult.coverUrl
                val updated = item.copy(
                    videoId = atvResult.videoId,
                    title = atvResult.title,
                    artist = atvResult.artist,
                    coverUrl = preservedCover,
                    durationSec = atvResult.durationSec,
                    album = atvResult.albumName ?: item.album
                )
                playbackQueue.updateItemMetadata(
                    songId = item.songId,
                    title = atvResult.title,
                    artist = atvResult.artist,
                    coverUrl = preservedCover,
                    videoId = atvResult.videoId,
                    album = atvResult.albumName ?: item.album
                )
                YouTubeIdRegistry.register(
                    videoId = atvResult.videoId,
                    title = atvResult.title,
                    artist = atvResult.artist,
                    durationSec = atvResult.durationSec,
                    album = atvResult.albumName ?: item.album.orEmpty(),
                    coverUrl = preservedCover
                )
                updated
            } else {
                item
            }

            val effectiveVideoId = effectiveItem.effectiveVideoId()
            val vidToResolve = effectiveVideoId ?: YouTubeIdRegistry.getVideoId(effectiveItem.songId) ?: ""
            val finalArt = if (item.coverUrl.isNotBlank()) {
                com.lin0721.linmusic.core.metadata.SongArtworkEngine.optimizeArtworkUrl(item.coverUrl, com.lin0721.linmusic.core.metadata.SongArtworkEngine.PLAYER_ART_PX)
            } else {
                atvResult?.coverUrl ?: effectiveArt
            }

            // Explicit prefetched URL argument check
            if (prefetchedUrl != null) {
                AppLogger.i(TAG, "[Playback] videoId=$vidToResolve using direct prefetched stream")
                val mediaItem = effectiveItem.toMediaItem(prefetchedUrl, playbackQueue.playContext.value, finalArt)
                pendingSkipFromIndex = null
                controllerHolder.playItem(mediaItem.withCrossfade(autoTransition, startPosition), playbackQueue.playMode.value, startPosition, playWhenReady)
        preArmNextUpcomingTrack(index)
                        preResolveAndQueueUpcoming(index)
        val remainingInQueue = playbackQueue.size - 1 - index
        if (remainingInQueue < 3) {
            maybeAutoRecommendNextSong(item.songId, remainingMs = 0L, forceImmediate = true)
        }
                return@launch
            }

            // Check if cached prefetched stream is still valid and not expired
            val validPrefetched = if (!forceRefresh) {
                prefetchedStream?.takeIf {
                    it.songId == effectiveItem.songId && it.expiresAtMs > System.currentTimeMillis() + 60_000L
                }
            } else null

            if (validPrefetched != null) {
                prefetchedStream = null
                AppLogger.i(TAG, "[Playback] videoId=${validPrefetched.videoId} using cached prefetched stream (expires in ${(validPrefetched.expiresAtMs - System.currentTimeMillis()) / 1000}s)")
                val mediaItem = effectiveItem.toMediaItem(validPrefetched.url, playbackQueue.playContext.value, finalArt)
                pendingSkipFromIndex = null
                controllerHolder.playItem(mediaItem.withCrossfade(autoTransition, startPosition), playbackQueue.playMode.value, startPosition, playWhenReady)
        preArmNextUpcomingTrack(index)
                        preResolveAndQueueUpcoming(index)
        val remainingInQueue = playbackQueue.size - 1 - index
        if (remainingInQueue < 3) {
            maybeAutoRecommendNextSong(item.songId, remainingMs = 0L, forceImmediate = true)
        }
                return@launch
            }
            prefetchedStream = null

            AppLogger.i(TAG, "[Playback] videoId=$vidToResolve resolving fresh stream for '${effectiveItem.title}' by '${effectiveItem.artist}' (forceRefresh=$forceRefresh)")

            val resolveResult = if (vidToResolve.isNotBlank()) {
                effectiveResolver.resolve(
                    videoId = vidToResolve,
                    songId = effectiveItem.songId,
                    title = effectiveItem.title,
                    artist = effectiveItem.artist,
                    durationSec = effectiveItem.durationSec,
                    forceRefresh = forceRefresh
                )
            } else {
                repository.getSongUrl(item.songId).first().map { url ->
                    com.lin0721.linmusic.core.player.resolver.ResolvedPlaybackData(
                        videoId = "",
                        streamUrl = url,
                        expiresAtMs = System.currentTimeMillis() + 4 * 3600 * 1000L
                    )
                }
            }

            resolveResult.onSuccess { resolvedData ->
                AppLogger.i(TAG, "[Playback] videoId=${resolvedData.videoId} stream resolved, starting playback at positionMs=$startPosition")
                if (resolvedData.videoId.isNotBlank()) {
                    YouTubeIdRegistry.registerMetadata(
                        songId = effectiveItem.songId,
                        title = effectiveItem.title,
                        artist = effectiveItem.artist,
                        coverUrl = finalArt,
                        videoId = resolvedData.videoId
                    )
                    sponsorBlockController.onTrackChanged(effectiveItem.songId, resolvedData.videoId, startPosition)
                }
                if (resolvedData.videoId.isNotBlank() && effectiveItem.videoId != resolvedData.videoId) {
                    playbackQueue.updateItemMetadata(
                        songId = effectiveItem.songId,
                        videoId = resolvedData.videoId,
                        album = effectiveItem.album
                    )
                    saveQueueState()
                }
                val updatedItem = if (resolvedData.videoId.isNotBlank() && effectiveItem.videoId != resolvedData.videoId) {
                    effectiveItem.copy(videoId = resolvedData.videoId)
                } else {
                    effectiveItem
                }
                playSongAdaptively(
                    item = updatedItem,
                    resolvedData = resolvedData,
                    startPosition = startPosition,
                    effectiveArt = finalArt,
                    autoTransition = autoTransition,
                    playWhenReady = playWhenReady,
                    index = index
                )
            }.onFailure { throwable ->
                _isLoading.value = false
                AppLogger.w(TAG, "[Playback] Stream resolution failed songId=${item.songId} videoId=$vidToResolve", throwable)
                if (throwable is AppError.NetworkError) {
                    handleNetworkFailure(index, startPosition, networkRetryAttempt, playWhenReady, autoTransition)
                } else {
                    skipToNextOnError(index)
                }
            }
        }
    }

    private fun playSongAdaptively(
        item: QueueItem,
        resolvedData: com.lin0721.linmusic.core.player.resolver.ResolvedPlaybackData,
        startPosition: Long,
        effectiveArt: String,
        autoTransition: Boolean,
        playWhenReady: Boolean,
        index: Int
    ) {
        stallWatchdogJob?.cancel()
        upgradeMonitorJob?.cancel()

        val hqUrl = resolvedData.highQualityUrl ?: resolvedData.streamUrl
        val lqUrl = resolvedData.lowQualityUrl ?: resolvedData.streamUrl

        // 1. Only start in Low Quality if measured bandwidth is ALREADY proven to be < 300 kbps
        val startInLow = NetworkSpeedMonitor.isVerySlowInternet(context) && lqUrl.isNotBlank() && lqUrl != hqUrl
        isPlayingLowQuality = startInLow

        val initialUrl = if (startInLow) lqUrl else hqUrl
        AppLogger.i(TAG, "[AdaptiveAudio] Starting playback in ${if (startInLow) "LOW" else "HIGH"} quality (speedEstimate=${NetworkSpeedMonitor.currentBitrateEstimate(context)} bps)")

        val mediaItem = item.toMediaItem(initialUrl, playbackQueue.playContext.value, effectiveArt)
        pendingSkipFromIndex = null
        controllerHolder.playItem(
            mediaItem.withCrossfade(autoTransition, startPosition),
            playbackQueue.playMode.value,
            startPosition,
            playWhenReady
        )
                preResolveAndQueueUpcoming(index)
        val remainingInQueue = playbackQueue.size - 1 - index
        if (remainingInQueue < 3) {
            maybeAutoRecommendNextSong(item.songId, remainingMs = 0L, forceImmediate = true)
        }

        if (!startInLow && lqUrl.isNotBlank() && lqUrl != hqUrl) {
            startStallWatchdog(item, hqUrl, lqUrl, effectiveArt)
        } else if (startInLow && hqUrl.isNotBlank() && hqUrl != lqUrl) {
            startUpgradeMonitor(item, hqUrl, effectiveArt)
        }
    }

    // 2. FALLBACK: If High Quality stalls in BUFFERING for > 2.5 seconds, drop to Low Quality immediately
    private fun startStallWatchdog(
        item: QueueItem,
        hqUrl: String,
        lqUrl: String,
        effectiveArt: String
    ) {
        stallWatchdogJob?.cancel()
        stallWatchdogJob = scope.launch {
            delay(2_500L) // Wait 2.5 seconds
            if (controllerHolder.playbackState == Player.STATE_BUFFERING && !isPlayingLowQuality) {
                AppLogger.w(TAG, "[AdaptiveAudio] High Quality stalled > 2.5s in STATE_BUFFERING! Hot-swapping to emergency low-quality stream")
                isPlayingLowQuality = true
                val exactPos = controllerHolder.currentPosition.coerceAtLeast(0L)
                val lowMediaItem = item.toMediaItem(lqUrl, playbackQueue.playContext.value, effectiveArt)
                controllerHolder.playItem(lowMediaItem, playbackQueue.playMode.value, exactPos, playWhenReady = _playWhenReady.value)

                // Start monitor to upgrade back once internet recovers
                startUpgradeMonitor(item, hqUrl, effectiveArt)
            }
        }
    }

    // 3. AUTO-UPGRADE: As soon as internet recovers above 600 kbps, switch back to High Quality seamlessly
    private fun startUpgradeMonitor(
        item: QueueItem,
        hqUrl: String,
        effectiveArt: String
    ) {
        upgradeMonitorJob?.cancel()
        upgradeMonitorJob = scope.launch {
            while (isPlayingLowQuality) {
                delay(4_000L) // Check every 4 seconds

                val internetRecovered = NetworkSpeedMonitor.isFastInternetRecovered(context)
                val safeBufferAhead = (controllerHolder.bufferedPosition - controllerHolder.currentPosition) > 8_000L

                if (internetRecovered && safeBufferAhead && controllerHolder.isPlaying) {
                    AppLogger.i(TAG, "[AdaptiveAudio] Speed recovered >= 600 kbps (buffer ahead > 8s)! Hot-swapping back to High Quality at exact position")
                    isPlayingLowQuality = false
                    val exactPos = controllerHolder.currentPosition.coerceAtLeast(0L)
                    val hqMediaItem = item.toMediaItem(hqUrl, playbackQueue.playContext.value, effectiveArt)
                    controllerHolder.playItem(hqMediaItem, playbackQueue.playMode.value, exactPos, playWhenReady = true)
                    break
                }
            }
        }
    }

    // Artistsï¼šSongs of Playã€Playï¼Œ
    // CurrentSongsPlayï¼›ï¼ˆPlayï¼‰Back false
    fun cancelPendingSkip(): Boolean {
        val fromIndex = pendingSkipFromIndex ?: return false
        activeLoadJob?.cancel()
        pendingSkipFromIndex = null
        playbackQueue.setCurrentIndex(fromIndex)
        saveQueueState()
        // resetTo() /Clearï¼ŒPlay of
        progress.setPosition(controllerHolder.currentPosition)
        progress.updateDurationFromController()
        return true
    }

    private fun isWifiConnected(): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = cm?.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }.getOrDefault(false)

    // PlayListen & Cache
    private fun maybeCheckStreamCacheProgress(songId: Long, positionMs: Long, durationMs: Long) {
        if (durationMs <= 0L || positionMs * 5 < durationMs * 4) return
        if (streamCacheTriggeredSongIds.contains(songId)) return
        val currentItem = playbackQueue.currentItem() ?: return
        if (currentItem.songId != songId || currentItem.localUri != null) return
        maybeTriggerStreamCache(currentItem)
    }

    private fun maybeTriggerStreamCache(item: QueueItem) {
        if (item.songId <= 0L || item.localUri != null) return
        if (!streamCacheTriggeredSongIds.add(item.songId)) return
        scope.launch(Dispatchers.IO) {
            val enabled = settingsPreferences.streamCacheEnabled.first()
            if (!enabled) {
                streamCacheTriggeredSongIds.remove(item.songId)
                return@launch
            }
            val isDownloaded = downloadPreferences.findVerifiedRecord(item.songId) != null
            if (isDownloaded) return@launch

            val quality = if (isWifiConnected()) {
                settingsPreferences.wifiQuality.first()
            } else {
                settingsPreferences.mobileQuality.first()
            }

            val trackInfo = DownloadTrackInfo(
                songId = item.songId,
                songName = item.title,
                artistName = item.artist,
                coverUrl = item.coverUrl,
                videoId = YouTubeIdRegistry.getVideoId(item.songId)
            )
            songDownloadManager.enqueueStreamCache(trackInfo, quality)
        }
    }

    // Prefetch next track stream before current track ends, respecting expiration
    private fun maybePrefetchNextTrackUrl(remainingMs: Long) {
        if (remainingMs > CrossfadePolicy.prefetchWindowMs(crossfadeEnabled)) return
        if (playbackQueue.playMode.value == PlayMode.SINGLE_LOOP) return
        val nextItem = playbackQueue.nextItemByMode() ?: return
        if (nextItem.localUri != null) return
        if (prefetchTriggeredForSongId == nextItem.songId) return
        prefetchTriggeredForSongId = nextItem.songId

        prefetchJob?.cancel()
        prefetchJob = scope.launch {
            if (downloadPreferences.findVerifiedRecord(nextItem.songId) != null) return@launch
            val nextVideoId = nextItem.effectiveVideoId() ?: YouTubeIdRegistry.getVideoId(nextItem.songId) ?: ""
            if (nextVideoId.isNotBlank()) {
                val resolveResult = effectiveResolver.resolve(
                    videoId = nextVideoId,
                    songId = nextItem.songId,
                    title = nextItem.title,
                    artist = nextItem.artist,
                    durationSec = nextItem.durationSec
                )
                resolveResult.onSuccess { data ->
                    prefetchedStream = PrefetchedStream(
                        songId = nextItem.songId,
                        videoId = nextVideoId,
                        url = data.streamUrl,
                        expiresAtMs = data.expiresAtMs
                    )
                    AppLogger.i(TAG, "[Playback] videoId=$nextVideoId stream prefetched (expires in ${(data.expiresAtMs - System.currentTimeMillis()) / 1000}s)")
                }
            }
        }
    }

    // Play next song on natural track completion
    private fun playNextOnEnded() {
        if (playbackQueue.isEmpty) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastAutoAdvanceMs < 1500L) {
            AppLogger.w(TAG, "playNextOnEnded debounced: called again within ${now - lastAutoAdvanceMs}ms")
            return
        }
        lastAutoAdvanceMs = now

        consecutiveErrors = 0
        val curIdx = playbackQueue.currentIndex.value
        val isAtEnd = curIdx >= playbackQueue.size - 1

        if (isAtEnd && playbackQueue.playMode.value != PlayMode.SINGLE_LOOP && playbackQueue.playMode.value != PlayMode.LIST_LOOP) {
            val currentItem = playbackQueue.itemAt(curIdx)
            if (currentItem != null) {
                scope.launch {
                    val nextTracks = effectiveRecommendationEngine.getAutoplayBatch(
                        seedTrack = currentItem,
                        activeQueue = playbackQueue.items.value,
                        limit = BitChordQueueBuilder.MAX_QUEUED_AUTOPLAY
                    )
                    if (nextTracks.isNotEmpty()) {
                        playbackQueue.append(nextTracks)
                        saveQueueState()
                    preResolveAndQueueUpcoming(playbackQueue.currentIndex.value)
                        fetchUrlAndPlay(curIdx + 1, autoTransition = true)
                        return@launch
                    }
                    val nextTrack = effectiveRecommendationEngine.getNextRecommendedTrack(
                        currentTrack = currentItem,
                        activeQueue = playbackQueue.items.value
                    )
                    if (nextTrack != null) {
                        playbackQueue.insertNext(listOf(nextTrack))
                        saveQueueState()
                        preResolveTracks(listOf(nextTrack))
                        fetchUrlAndPlay(curIdx + 1, autoTransition = true)
                        return@launch
                    }
                    val nextIndex = playbackQueue.nextIndex()
                    fetchUrlAndPlay(nextIndex, autoTransition = true)
                }
                return
            }
        }
        val nextIndex = playbackQueue.nextIndex()
        val nextItem = playbackQueue.itemAt(nextIndex)

        val isCrossfading = canAutoCrossfade() && pendingAutoFadeMs > 0L

        // 0ms instant transition if ExoPlayer native queue already holds next item AND crossfade is NOT active
        if (!isCrossfading && nextItem != null && controllerHolder.hasNextMediaItem()) {
            val nextSongId = nextItem.songId.toString()
            if (controllerHolder.peekNextMediaId() == nextSongId) {
                AppLogger.i(TAG, "[Playback] playNextOnEnded: advancing seamlessly via ExoPlayer native queue")
                playbackQueue.setCurrentIndex(nextIndex)
                saveQueueState()
                _playWhenReady.value = true
                controllerHolder.seekToNextMediaItem()
                return
            }
        }

        val validUrl = prefetchedStream?.takeIf {
            it.songId == nextItem?.songId && it.expiresAtMs > System.currentTimeMillis() + 60_000L
        }?.url
        prefetchedStream = null
        fetchUrlAndPlay(nextIndex, prefetchedUrl = validUrl, autoTransition = true)
    }


    // Pre-arm standby player on Player 2 immediately at 0:00 of track N
    private fun preArmNextUpcomingTrack(currentIndex: Int) {
        if (!canAutoCrossfade()) return
        val currentItem = playbackQueue.itemAt(currentIndex) ?: return
        val nextItem = playbackQueue.nextItemByMode() ?: return
        if (playbackQueue.playMode.value == PlayMode.SINGLE_LOOP) return

        scope.launch(Dispatchers.IO) {
            try {
                val streamData = resolveStreamForItem(nextItem)
                if (streamData != null && streamData.streamUrl.isNotBlank()) {
                    prefetchedStream = PrefetchedStream(
                        songId = nextItem.songId,
                        videoId = streamData.videoId,
                        url = streamData.streamUrl,
                        expiresAtMs = streamData.expiresAtMs
                    )

                    val currDur = (currentItem.durationSec * 1000L).takeIf { it > 0L } ?: 180_000L
                    val nextDur = (nextItem.durationSec * 1000L).takeIf { it > 0L } ?: 180_000L

                    val currAnalysis = fastTrackAnalyzer.analyzeTrack(
                        trackId = currentItem.songId.toString(),
                        uri = currentItem.localUri ?: requestedSource?.second,
                        durationMs = currDur,
                        title = currentItem.title,
                        artist = currentItem.artist
                    )
                    val nextAnalysis = fastTrackAnalyzer.analyzeTrack(
                        trackId = nextItem.songId.toString(),
                        uri = streamData.streamUrl,
                        durationMs = nextDur,
                        title = nextItem.title,
                        artist = nextItem.artist
                    )

                    val isSameAlbum = !currentItem.album.isNullOrBlank() &&
                            currentItem.album.equals(nextItem.album, ignoreCase = true)

                    val plan = SmartAutomixPlanner.planTransition(
                        currentAnalysis = currAnalysis,
                        nextAnalysis = nextAnalysis,
                        isSameAlbumSequential = isSameAlbum,
                        configuredFadeMs = crossfadeDurationMs.toLong(),
                        automixEnabled = automixEnabled
                    )

                    val fadeMs = plan.fadeDurationMs
                    if (fadeMs > 0L) {
                        pendingStartOffset = nextItem.songId to plan.incomingCueMs
                        pendingBassSwap = (plan.transitionStyle == TransitionStyle.DJ_BLEND)
                        val cached = metadataEnricher?.getCached(nextItem.songId.toString())
                            ?: metadataEnricher?.getCachedByQuery(nextItem.title, nextItem.artist)
                        val effectiveArt = cached?.artworkUrl ?: nextItem.coverUrl
                        val mediaItem = nextItem.toMediaItem(
                            url = streamData.streamUrl,
                            playContext = playbackQueue.playContext.value,
                            artworkUri = effectiveArt
                        )
                        withContext(Dispatchers.Main) {
                            if (playbackQueue.currentIndex.value == currentIndex) {
                                controllerHolder.preArmStandby(
                                    mediaItem = mediaItem,
                                    startPositionMs = plan.incomingCueMs,
                                    fadeMs = fadeMs,
                                    bassSwap = pendingBassSwap
                                )
                                AppLogger.i(TAG, "Standby player armed early for '${nextItem.title}' (fadeMs=$fadeMs, cueMs=${plan.incomingCueMs}, triggerMs=${plan.transitionStartMs})")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "preArmNextUpcomingTrack error: ${e.message}")
            }
        }
    }

    // Automix and Smart Crossfade scheduling
    private fun maybeScheduleAutoCrossfade(songId: Long, remainingMs: Long, durationMs: Long) {
        if (!canAutoCrossfade()) return
        if (autoCrossfadeScheduledSongId == songId || autoCrossfadeTriggeredSongId == songId) return
        if (remainingMs > CrossfadePolicy.ANALYSIS_WINDOW_MS) return
        autoCrossfadeScheduledSongId = songId
        autoCrossfadeJob?.cancel()
        autoCrossfadeJob = scope.launch {
            val sourceUri = requestedSource?.takeIf { it.first == songId }?.second
            val currentItem = playbackQueue.itemAt(playbackQueue.currentIndex.value)
            val nextItem = playbackQueue.nextItemByMode() ?: return@launch

            // 1. Ultra-fast analysis of current track
            val currAnalysis = fastTrackAnalyzer.analyzeTrack(
                trackId = songId.toString(),
                uri = sourceUri,
                durationMs = durationMs,
                title = currentItem?.title.orEmpty(),
                artist = currentItem?.artist.orEmpty()
            )

            // 2. Ultra-fast analysis of next track (incoming)
            val nextUri = nextItem.localUri
                ?: downloadPreferences.findVerifiedRecord(nextItem.songId)?.mediaStoreUri
                ?: prefetchedStream?.takeIf {
                    it.songId == nextItem.songId && it.expiresAtMs > System.currentTimeMillis() + 60_000L
                }?.url

            val nextAnalysis = fastTrackAnalyzer.analyzeTrack(
                trackId = nextItem.songId.toString(),
                uri = nextUri,
                durationMs = (nextItem.durationSec * 1000L).takeIf { it > 0L } ?: 180_000L,
                title = nextItem.title,
                artist = nextItem.artist
            )

            val isSameAlbum = currentItem != null && !currentItem.album.isNullOrBlank() &&
                    currentItem.album.equals(nextItem.album, ignoreCase = true)

            // 3. Plan intelligent transition
            val plan = SmartAutomixPlanner.planTransition(
                currentAnalysis = currAnalysis,
                nextAnalysis = nextAnalysis,
                isSameAlbumSequential = isSameAlbum,
                configuredFadeMs = crossfadeDurationMs.toLong(),
                automixEnabled = automixEnabled
            )

            val fadeMs = plan.fadeDurationMs
            if (fadeMs <= 0L) return@launch
            val triggerMs = plan.transitionStartMs
            pendingStartOffset = nextItem.songId to plan.incomingCueMs
            pendingBassSwap = (plan.transitionStyle == TransitionStyle.DJ_BLEND)

            AppLogger.i(TAG, "Automix plan: style=${plan.transitionStyle} trigger=${triggerMs}ms/${durationMs}ms fade=${fadeMs}ms cue=${plan.incomingCueMs}ms bassSwap=${plan.bassSwapFraction}")

            // 4. Pre-arm standby player on Player 2 so it enters STATE_READY well before triggerMs arrives!
            launch(Dispatchers.IO) {
                try {
                    val streamData = resolveStreamForItem(nextItem)
                    if (streamData != null && streamData.streamUrl.isNotBlank()) {
                        val cached = metadataEnricher?.getCached(nextItem.songId.toString())
                            ?: metadataEnricher?.getCachedByQuery(nextItem.title, nextItem.artist)
                        val effectiveArt = cached?.artworkUrl ?: nextItem.coverUrl
                        val mediaItem = nextItem.toMediaItem(
                            url = streamData.streamUrl,
                            playContext = playbackQueue.playContext.value,
                            artworkUri = effectiveArt
                        )
                        prefetchedStream = PrefetchedStream(
                            songId = nextItem.songId,
                            videoId = streamData.videoId,
                            url = streamData.streamUrl,
                            expiresAtMs = streamData.expiresAtMs
                        )
                        withContext(Dispatchers.Main) {
                            if (_currentTrack.value?.mediaId?.toLongOrNull() == songId) {
                                controllerHolder.preArmStandby(
                                    mediaItem = mediaItem,
                                    startPositionMs = plan.incomingCueMs,
                                    fadeMs = fadeMs,
                                    bassSwap = pendingBassSwap
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Failed to pre-arm standby player: ${e.message}")
                }
            }

            while (true) {
                if (!canAutoCrossfade() || _currentTrack.value?.mediaId?.toLongOrNull() != songId) return@launch
                val positionMs = controllerHolder.currentPosition
                if (!_isPlaying.value) {
                    delay(500L)
                    continue
                }
                val waitMs = triggerMs - positionMs
                if (waitMs <= 0L || positionMs >= currAnalysis.contentEndMs) break
                delay(waitMs.coerceAtMost(500L))
            }

            autoCrossfadeTriggeredSongId = songId
            autoCrossfadeTriggeredTimeMs = SystemClock.elapsedRealtime()
            pendingAutoFadeMs = fadeMs
            AppLogger.i(TAG, "Triggering Automix transition: songId=$songId style=${plan.transitionStyle} fadeMs=$fadeMs")
            playNextOnEnded()
        }
    }

    private fun canAutoCrossfade(): Boolean =
        (crossfadeEnabled || automixEnabled) && playbackQueue.playMode.value != PlayMode.SINGLE_LOOP && playbackQueue.size > 1

    private fun MediaItem.withCrossfade(autoTransition: Boolean, startPosition: Long): MediaItem {
        val songId = mediaId.toLongOrNull()
        val uri = localConfiguration?.uri?.toString()
        if (songId != null && uri != null) requestedSource = songId to uri
        val isAllowed = crossfadeEnabled || automixEnabled
        val fadeMs = CrossfadePolicy.fadeMsFor(
            autoTransition = autoTransition,
            enabled = isAllowed,
            isPlaying = _isPlaying.value,
            startPositionMs = startPosition,
            autoFadeMs = pendingAutoFadeMs
        )
        if (fadeMs <= 0L) return this
        val startOffsetMs = pendingStartOffset?.takeIf { it.first == songId }?.second ?: 0L
        val bassSwapActive = pendingBassSwap
        pendingAutoFadeMs = 0L
        pendingBassSwap = false
        val extras = Bundle(mediaMetadata.extras ?: Bundle()).apply {
            putLong(CrossfadePolicy.EXTRA_CROSSFADE_MS, fadeMs)
            if (startOffsetMs > 0L) putLong(CrossfadePolicy.EXTRA_CROSSFADE_START_MS, startOffsetMs)
            if (bassSwapActive) putBoolean(CrossfadePolicy.EXTRA_BASS_SWAP, true)
        }
        return buildUpon()
            .setMediaMetadata(mediaMetadata.buildUpon().setExtras(extras).build())
            .build()
    }

    // Retryï¼ˆï¼‰ï¼ŒRetryDiscardï¼Œ
    private fun handleNetworkFailure(index: Int, startPosition: Long, attempt: Int, playWhenReady: Boolean, autoTransition: Boolean) {
        if (attempt < NETWORK_RETRY_DELAYS_MS.size) {
            val delayMs = NETWORK_RETRY_DELAYS_MS[attempt]
            activeLoadJob = scope.launch {
                delay(delayMs)
                fetchUrlAndPlay(index, startPosition, attempt + 1, playWhenReady = playWhenReady, autoTransition = autoTransition)
            }
            return
        }

        val songId = playbackQueue.itemAt(index)?.songId
        AppLogger.e(TAG, "Network error after $attempt retries, waiting for connection to resume songId=$songId index=$index")
        networkGuard.awaitNetworkRecovery {
            fetchUrlAndPlay(index, startPosition, playWhenReady = playWhenReady)
        }
    }

    fun release() {
        reportPlayedTrack()
        networkGuard.unregister()
        sleepTimer.cancel()
        activeLoadJob?.cancel()
        prefetchJob?.cancel()
        roaming.cancel()
    }

    private var lastStartPlayReportedSongId: Long = -1L
    private var lastStartPlayReportedTimeMs: Long = 0L

    // Reports song start to repository, which updates userPreferences.recordTrackPlayed() for Recents
    private fun reportStartPlay(mediaItem: MediaItem) {
        val songId = mediaItem.mediaId.toLongOrNull() ?: return
        val now = SystemClock.elapsedRealtime()
        if (songId == lastStartPlayReportedSongId && now - lastStartPlayReportedTimeMs < 2000L) {
            return
        }
        lastStartPlayReportedSongId = songId
        lastStartPlayReportedTimeMs = now

        val title = mediaItem.mediaMetadata.title?.toString().orEmpty()
        val artist = mediaItem.mediaMetadata.artist?.toString().orEmpty()
        val cover = mediaItem.mediaMetadata.artworkUri?.toString().orEmpty()
        if (title.isNotBlank()) {
            com.lin0721.linmusic.core.player.innertube.YouTubeIdRegistry.registerMetadata(
                songId = songId,
                title = title,
                artist = artist,
                coverUrl = cover
            )
        }

        scope.launch {
            repository.reportStartPlay(songId).collect { result ->
                result.onFailure { AppLogger.w(TAG, "Scrobble report startplay failed: songId=$songId", it) }
            }
        }
    }

    private var lastAutoRecommendTriggeredSongId: Long = -1L
    private var autoplayLoadInProgress = false

    private fun maybeAutoRecommendNextSong(songId: Long, remainingMs: Long, forceImmediate: Boolean = false) {
        val curIdx = playbackQueue.currentIndex.value
        val itemCount = playbackQueue.size
        val singleLoop = playbackQueue.playMode.value == PlayMode.SINGLE_LOOP
        if (singleLoop) return

        // BitChord trigger condition: when queue runs low (< 3 tracks ahead) OR forced immediately on track start
        val roomLow = (curIdx >= itemCount - 3)
        if (!roomLow && !forceImmediate) return
        if (lastAutoRecommendTriggeredSongId == songId && !forceImmediate) return
        if (autoplayLoadInProgress) return
        val currentItem = playbackQueue.itemAt(curIdx) ?: return
        lastAutoRecommendTriggeredSongId = songId
        autoplayLoadInProgress = true

        scope.launch {
            try {
                val nextTracks = effectiveRecommendationEngine.getAutoplayBatch(
                    seedTrack = currentItem,
                    activeQueue = playbackQueue.items.value,
                    limit = BitChordQueueBuilder.MAX_QUEUED_AUTOPLAY
                )
                if (nextTracks.isNotEmpty()) {
                    playbackQueue.append(nextTracks)
                    saveQueueState()
                    preResolveAndQueueUpcoming(playbackQueue.currentIndex.value)
                    AppLogger.i(TAG, "BitChord AutoPlay enqueued ${nextTracks.size} tracks after '${currentItem.title}'")
                } else {
                    val single = effectiveRecommendationEngine.getNextRecommendedTrack(
                        currentTrack = currentItem,
                        activeQueue = playbackQueue.items.value
                    )
                    if (single != null) {
                        playbackQueue.insertNext(listOf(single))
                        saveQueueState()
                        preResolveAndQueueUpcoming(playbackQueue.currentIndex.value)
                        AppLogger.i(TAG, "Recommendation single-enqueued track: '${single.title}'")
                    }
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "Failed to load BitChord autoplay tracks", e)
            } finally {
                autoplayLoadInProgress = false
            }
        }
    }

    // Playback completion and metric reporting with 30s preference threshold
    private fun reportPlayedTrack() {
        val songId = _currentTrack.value?.mediaId?.toLongOrNull() ?: return
        val playedSeconds = currentTrackPlayedMs() / 1000
        val durationSeconds = progress.duration.value / 1000
        val title = _currentTrack.value?.mediaMetadata?.title?.toString().orEmpty()
        val artist = _currentTrack.value?.mediaMetadata?.artist?.toString().orEmpty()
        val coverUrl = _currentTrack.value?.mediaMetadata?.artworkUri?.toString().orEmpty()
        val videoId = com.lin0721.linmusic.core.player.innertube.YouTubeIdRegistry.getVideoId(songId).orEmpty()

        scope.launch {
            effectiveRecommendationEngine.recordTrackPlaybackMetric(
                songId = songId,
                videoId = videoId,
                title = title,
                artist = artist,
                coverUrl = coverUrl,
                listenedSeconds = playedSeconds,
                durationSeconds = durationSeconds
            )
        }

        if (playedSeconds < 5) {
            AppLogger.i(TAG, "Scrobble report skipped (<5s): songId=$songId playedSeconds=$playedSeconds")
            return
        }
        AppLogger.i(TAG, "Scrobble report triggered: songId=$songId playedSeconds=$playedSeconds")
        scope.launch {
            repository.reportPlayEnd(songId, playedSeconds).collect { result ->
                result.onFailure { AppLogger.w(TAG, "Scrobble report play failed: songId=$songId", it) }
            }
        }
    }

    // CurrentPlay of ï¼ŒPauseï¼ˆPaused of ï¼‰
    private fun currentTrackPlayedMs(): Long {
        val now = SystemClock.elapsedRealtime()
        val ongoingPauseMs = trackLastPauseElapsedMs?.let { now - it } ?: 0L
        return (now - trackStartElapsedMs - trackPausedAccumMs - ongoingPauseMs).coerceAtLeast(0L)
    }

    // /Playï¼ŒReset
    private fun resetTrackTiming(startPlaying: Boolean = _isPlaying.value) {
        trackStartElapsedMs = SystemClock.elapsedRealtime()
        trackPausedAccumMs = 0L
        trackLastPauseElapsedMs = if (startPlaying) null else trackStartElapsedMs
    }

    private fun skipToNextOnError(failedIndex: Int) {
        consecutiveErrors++
        if (consecutiveErrors >= 3 || playbackQueue.size <= 1) {
            AppLogger.e(TAG, "$consecutiveErrors consecutive playback errors, stopping auto-skip failedIndex=$failedIndex queueSize=${playbackQueue.size}")
            _playWhenReady.value = false
            scope.launch {
                Toast.makeText(context, "Unable to get playback URL for this song", Toast.LENGTH_SHORT).show()
            }
            return
        }
        fetchUrlAndPlay(playbackQueue.nextIndexFrom(failedIndex))
    }

    // CurrentSongsï¼ˆï¼‰
    fun reloadCurrentTrack() {
        val index = playbackQueue.currentIndex.value
        if (index >= 0 && index < playbackQueue.size) {
            val currentPos = controllerHolder.currentPosition
            fetchUrlAndPlay(index, currentPos)
        }
    }

    // Close of Queue
    fun disableRoaming() {
        roaming.disable()
    }

    // CloseHeartbeat Mode of Queue
    fun disableIntelligence() {
        if (playbackQueue.playContext.value != CONTEXT_INTELLIGENCE) return
        playbackQueue.restoreSnapshot()
        saveQueueState()
    }

    // ExoPlayer  of  playWhenReadyï¼š of ï¼ˆã€ of Pauseï¼‰
    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        _playWhenReady.value = playWhenReady
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        _isPlaying.value = isPlaying
        if (isPlaying) {
            _isLoading.value = false
            consecutiveErrors = 0
            streamRecoveryAttemptCount = 0
            streamErrorRecoverySongId = null
            trackLastPauseElapsedMs?.let { trackPausedAccumMs += SystemClock.elapsedRealtime() - it }
            trackLastPauseElapsedMs = null
        } else {
            saveState()
            trackLastPauseElapsedMs = SystemClock.elapsedRealtime()
        }
        progress.updatePlayingState(isPlaying)
        if (isPlaying) {
            progress.syncImmediatePosition()
        }
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int
    ) {
        progress.setPosition(newPosition.positionMs)
        if (reason == Player.DISCONTINUITY_REASON_SEEK) {
            autoCrossfadeScheduledSongId = -1L
            autoCrossfadeTriggeredSongId = -1L
            autoCrossfadeJob?.cancel()
            autoCrossfadeJob = null
            pendingAutoFadeMs = 0L
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        lastMediaItemTransitionMs = SystemClock.elapsedRealtime()
        AppLogger.i(TAG, "Track changed: songId=${mediaItem?.mediaId} reason=${transitionReasonName(reason)}")
        reportPlayedTrack()
        resetTrackTiming()
        // Play
        val newSongId = mediaItem?.mediaId?.toLongOrNull()
        if (SystemClock.elapsedRealtime() - autoCrossfadeTriggeredTimeMs > 10_000L) {
            autoCrossfadeTriggeredSongId = -1L
            pendingAutoFadeMs = 0L
        }
        if (newSongId != autoCrossfadeScheduledSongId) {
            autoCrossfadeJob?.cancel()
            autoCrossfadeScheduledSongId = -1L
        }
        if (pendingStartOffset?.first != newSongId) pendingStartOffset = null
        _currentTrack.value = mediaItem
        playbackQueue.setPlayContext(mediaItem?.mediaMetadata?.extras?.getString("playContext"))
        recorder.resetSession()
        if (mediaItem != null) {
            reportStartPlay(mediaItem)
            val songId = mediaItem.mediaId.toLongOrNull()
            if (songId != null) {
                val matchingIdx = playbackQueue.items.value.indexOfFirst { it.songId == songId }
                pendingTargetSongId = null
                if (matchingIdx >= 0 && matchingIdx != playbackQueue.currentIndex.value) {
                    playbackQueue.setCurrentIndex(matchingIdx)
                    saveQueueState()
                }
            }
            val activeIdx = playbackQueue.currentIndex.value
            preloadUpcomingCovers(activeIdx)
            preResolveAndQueueUpcoming(activeIdx)

            val matchedItem = if (songId != null) {
                playbackQueue.items.value.find { it.songId == songId }
            } else null

            val videoId = mediaItem.mediaMetadata.extras?.getString("videoId")
                ?.takeIf { YouTubeIdRegistry.sanitizeVideoId(it).length == 11 }
                ?: (songId?.let { YouTubeIdRegistry.getVideoId(it) })
                ?: matchedItem?.effectiveVideoId()
                ?: mediaItem.mediaId

            val title = mediaItem.mediaMetadata.title?.toString().orEmpty()
            val artist = mediaItem.mediaMetadata.artist?.toString().orEmpty()
            val album = songId?.let { YouTubeIdRegistry.getAlbumName(it) } ?: mediaItem.mediaMetadata.albumTitle?.toString()
            val cover = mediaItem.mediaMetadata.artworkUri?.toString().orEmpty()
            val durationSec = songId?.let { YouTubeIdRegistry.getDurationSec(it).toLong() } ?: (progress.duration.value / 1000)
            recorder.onTrackStarted(
                videoId = videoId,
                title = title,
                artist = artist,
                artistId = null,
                album = album,
                thumbnailUrl = cover,
                durationSec = durationSec
            )
            val rawId = mediaItem.mediaId
            val effectiveVideoId = mediaItem.mediaMetadata.extras?.getString("videoId")
                ?.takeIf { YouTubeIdRegistry.sanitizeVideoId(it).length == 11 }
                ?: (if (newSongId != null) YouTubeIdRegistry.getVideoId(newSongId)?.takeIf { it.length == 11 } else null)
                ?: matchedItem?.effectiveVideoId()?.takeIf { it.length == 11 }
                ?: playbackQueue.currentItem()?.effectiveVideoId()?.takeIf { it.length == 11 }
                ?: (if (rawId.length == 11 && !rawId.contains("/")) rawId else null)

            val initialPos = if (pendingStartPosition > 0L) pendingStartPosition else 0L
            pendingStartPosition = 0L

            if (!effectiveVideoId.isNullOrBlank()) {
                sponsorBlockController.onTrackChanged(newSongId ?: -1L, effectiveVideoId, initialPos)
            }

            if (newSongId != null) {
                val currentArt = mediaItem.mediaMetadata.artworkUri?.toString()
                metadataEnricher?.enrichInBackground(
                    songId = newSongId.toString(),
                    title = title,
                    artist = artist,
                    album = album,
                    fallbackCoverUrl = currentArt,
                    durationMs = durationSec * 1000L
                ) { enriched ->
                    playbackQueue.updateItemMetadata(
                        songId = newSongId,
                        title = enriched.title.takeIf { it.isNotBlank() },
                        artist = enriched.artist.takeIf { it.isNotBlank() },
                        coverUrl = enriched.artworkUrl
                    )
                    updateCurrentTrackMetadata(newSongId, enriched)
                }
                // Trigger synchronized lyrics prefetch for transitioning track
                scope.launch(Dispatchers.IO) {
                    runCatching {
                        repository.getLyrics(newSongId).firstOrNull()
                    }
                }
            }
            // ï¼šï¼ŒReset 0
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
                if (pendingStartPosition > 0L) {
                    progress.setPosition(pendingStartPosition)
                    pendingStartPosition = 0L
                } else {
                    progress.setPosition(0L)
                }
            }
            progress.updateDurationFromController()
            saveState()
        } else {
            sponsorBlockController.reset()
        }
    }

    // READYï¼Œ onPlaybackStateChangedï¼Œ
    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        progress.updateDurationFromController()
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        AppLogger.i(TAG, "Playback state changed: ${playbackStateName(playbackState)}")
        if (playbackState == Player.STATE_BUFFERING) {
            _isLoading.value = true
            progress.updatePlayingState(false)
        } else if (playbackState == Player.STATE_READY) {
            _isLoading.value = false
            progress.updateDurationFromController()
            preloadUpcomingCovers(playbackQueue.currentIndex.value)
            preloadUpcomingLyrics(playbackQueue.currentIndex.value)
            progress.updatePlayingState(controllerHolder.isPlaying)
            if (!isPlayingLowQuality) {
                stallWatchdogJob?.cancel()
            }
        } else if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) {
            _isLoading.value = false
            progress.updatePlayingState(false)
        }
        // Tracks ExoPlayer REPEAT_MODE_ONE ï¼Œ STATE_ENDED
        if (playbackState == Player.STATE_ENDED && playbackQueue.playMode.value != PlayMode.SINGLE_LOOP) {
            val now = SystemClock.elapsedRealtime()

            // 1. If crossfade was recently triggered for outgoing song, ignore ENDED from outgoing player
            if (now - autoCrossfadeTriggeredTimeMs < 10_000L) {
                AppLogger.i(TAG, "Crossfade recently triggered (${now - autoCrossfadeTriggeredTimeMs}ms ago), ignoring ENDED")
                return
            }

            // 2. If track transition occurred very recently (< 2.5s), this ENDED is a stale event from previous track
            if (now - lastMediaItemTransitionMs < 2500L) {
                AppLogger.i(TAG, "MediaItem transition occurred recently (${now - lastMediaItemTransitionMs}ms ago), ignoring stale ENDED")
                return
            }

            // 3. Debounce rapid auto-advance calls
            if (now - lastAutoAdvanceMs < 2500L) {
                AppLogger.i(TAG, "Auto-advance occurred recently (${now - lastAutoAdvanceMs}ms ago), debouncing ENDED")
                return
            }

            // 4. If current track has barely started (< 3s) and has a valid duration, it cannot have ended naturally
            val currentPos = controllerHolder.currentPosition
            val duration = progress.duration.value
            if (currentPos < 3000L && duration > 8000L) {
                AppLogger.w(TAG, "Ignoring false STATE_ENDED: track just started at ${currentPos}ms / ${duration}ms")
                return
            }

            val endedSongId = _currentTrack.value?.mediaId?.toLongOrNull()
            if (endedSongId != null && endedSongId == autoCrossfadeTriggeredSongId) {
                AppLogger.i(TAG, "Crossfade already triggered, ignoring ENDED: songId=$endedSongId")
                return
            }

            if (controllerHolder.hasNextMediaItem()) {
                AppLogger.i(TAG, "ExoPlayer has next buffered media item in native queue, ignoring redundant ENDED")
                return
            }

            playNextOnEnded()
        }
    }

    private var streamRecoveryAttemptCount = 0
    private val maxStreamRecoveryAttempts = 3

    override fun onPlayerError(error: PlaybackException) {
        super.onPlayerError(error)
        _isLoading.value = false
        AppLogger.e(TAG, "[Playback] ExoPlayer reported error errorCode=${error.errorCodeName} songId=${_currentTrack.value?.mediaId}", error)
        if (tryRecoverStreamError(error)) return
        scope.launch {
            Toast.makeText(context, "Current track cannot be played, skipped automatically", Toast.LENGTH_SHORT).show()
        }
        skipToNextOnError(playbackQueue.currentIndex.value)
    }

    /**
     * Automatic playback recovery for expired/invalid stream URLs.
     * When ExoPlayer reports a playback/network/source error:
     * 1. Preserves current YouTube video ID and playback position.
     * 2. Invalidates the cached stream in PlaybackResolver.
     * 3. Resolves a fresh stream URL.
     * 4. Recreates/reprepares the MediaItem with the fresh URL.
     * 5. Restores the previous position and resumes playback.
     * Bounded to [maxStreamRecoveryAttempts] to prevent infinite loops.
     */
    private fun tryRecoverStreamError(error: PlaybackException): Boolean {
        val index = playbackQueue.currentIndex.value
        val item = (if (index in 0 until playbackQueue.size) playbackQueue.itemAt(index) else null)
            ?: playbackQueue.currentItem()
            ?: return false
        if (item.localUri != null) return false

        val videoId = item.effectiveVideoId() ?: YouTubeIdRegistry.getVideoId(item.songId) ?: ""
        val position = controllerHolder.currentPositionOrNull ?: progress.currentPosition.value

        AppLogger.w(TAG, "[Playback] videoId=$videoId playback error errorCode=${error.errorCodeName} message=${error.message}")

        if (streamRecoveryAttemptCount >= maxStreamRecoveryAttempts) {
            AppLogger.e(TAG, "[Playback] videoId=$videoId stream recovery exhausted max attempts ($maxStreamRecoveryAttempts)")
            streamRecoveryAttemptCount = 0
            return false
        }

        streamRecoveryAttemptCount++
        AppLogger.i(TAG, "[Playback] videoId=$videoId refreshing stream retryAttempt=$streamRecoveryAttemptCount positionMs=$position")

        if (videoId.isNotBlank()) {
            effectiveResolver.invalidate(videoId)
        }

        fetchUrlAndPlay(index.coerceAtLeast(0), position, playWhenReady = _playWhenReady.value, forceRefresh = true)
        return true
    }

    fun preloadUpcomingCovers(currentIndex: Int) {
        val items = playbackQueue.items.value
        if (items.isEmpty()) return
        val count = items.size
        // Whenever song N starts playing, immediately tell Coil to pre-fetch album art for song N+1 and N+2
        val nextIndices = if (count > 2) {
            listOf((currentIndex + 1) % count, (currentIndex + 2) % count)
        } else if (count == 2) {
            listOf((currentIndex + 1) % count)
        } else {
            emptyList()
        }

        scope.launch(Dispatchers.IO) {
            for (nextIdx in nextIndices) {
                if (nextIdx in items.indices && nextIdx != currentIndex) {
                    val nextItem = items[nextIdx]
                    val cached = metadataEnricher?.getCached(nextItem.songId.toString())
                        ?: metadataEnricher?.getCachedByQuery(nextItem.title, nextItem.artist)
                    if (cached == null) {
                        metadataEnricher?.enrichInBackground(
                            songId = nextItem.songId.toString(),
                            title = nextItem.title,
                            artist = nextItem.artist,
                            fallbackCoverUrl = nextItem.coverUrl
                        )
                    }
                    val rawCover = cached?.artworkUrl ?: nextItem.coverUrl
                    val localUri = nextItem.localUri
                    val effectiveCover = if (!rawCover.isNullOrBlank()) {
                        rawCover
                    } else if (localUri != null) {
                        localCoverArtCache.coverUriFor(android.net.Uri.parse(localUri))?.toString().orEmpty()
                    } else {
                        val verified = downloadPreferences.findVerifiedRecord(nextItem.songId)
                        verified?.mediaStoreUri?.let { uriStr ->
                            localCoverArtCache.coverUriFor(android.net.Uri.parse(uriStr))?.toString()
                        }.orEmpty()
                    }
                    if (effectiveCover.isNotBlank()) {
                        coverPreloader.preload(effectiveCover)
                    }
                }
            }
        }
        preloadUpcomingLyrics(currentIndex)
    }

    fun preloadUpcomingLyrics(currentIndex: Int) {
        val items = playbackQueue.items.value
        if (items.isEmpty()) return
        val count = items.size
        val nextIndices = if (count > 2) {
            listOf((currentIndex + 1) % count, (currentIndex + 2) % count)
        } else if (count == 2) {
            listOf((currentIndex + 1) % count)
        } else {
            emptyList()
        }

        scope.launch(Dispatchers.IO) {
            for (nextIdx in nextIndices) {
                if (nextIdx in items.indices && nextIdx != currentIndex) {
                    val nextItem = items[nextIdx]
                    if (nextItem.title.isNotBlank()) {
                        YouTubeIdRegistry.registerMetadata(
                            songId = nextItem.songId,
                            title = nextItem.title,
                            artist = nextItem.artist,
                            coverUrl = nextItem.coverUrl,
                            videoId = nextItem.videoId
                        )
                        runCatching {
                            repository.getLyrics(nextItem.songId).firstOrNull()
                        }
                    }
                }
            }
        }
    }

    suspend fun resolveStreamForItem(item: QueueItem): com.lin0721.linmusic.core.player.resolver.ResolvedPlaybackData? {
        if (item.localUri != null) {
            return com.lin0721.linmusic.core.player.resolver.ResolvedPlaybackData(
                videoId = "",
                streamUrl = item.localUri,
                expiresAtMs = Long.MAX_VALUE
            )
        }
        val verified = downloadPreferences.findVerifiedRecord(item.songId)
        if (verified != null) {
            return com.lin0721.linmusic.core.player.resolver.ResolvedPlaybackData(
                videoId = "",
                streamUrl = verified.mediaStoreUri,
                expiresAtMs = Long.MAX_VALUE
            )
        }

        val atvResult = runCatching {
            OfficialAudioTrackResolver.resolve(
                rawVideoId = item.effectiveVideoId(),
                rawTitle = item.title,
                rawArtist = item.artist,
                currentDurationSec = item.durationSec,
                currentCoverUrl = item.coverUrl
            )
        }.getOrNull()

        val effectiveItem = if (atvResult != null) {
            val preservedCover = if (item.coverUrl.isNotBlank()) item.coverUrl else atvResult.coverUrl
            playbackQueue.updateItemMetadata(
                songId = item.songId,
                title = atvResult.title,
                artist = atvResult.artist,
                coverUrl = preservedCover,
                videoId = atvResult.videoId,
                album = atvResult.albumName ?: item.album
            )
            YouTubeIdRegistry.register(
                videoId = atvResult.videoId,
                title = atvResult.title,
                artist = atvResult.artist,
                durationSec = atvResult.durationSec,
                album = atvResult.albumName ?: item.album.orEmpty(),
                coverUrl = preservedCover
            )
            item.copy(
                videoId = atvResult.videoId,
                title = atvResult.title,
                artist = atvResult.artist,
                coverUrl = preservedCover,
                durationSec = atvResult.durationSec,
                album = atvResult.albumName ?: item.album
            )
        } else item

        val effectiveVideoId = effectiveItem.effectiveVideoId()
        val vidToResolve = effectiveVideoId ?: YouTubeIdRegistry.getVideoId(effectiveItem.songId) ?: ""

        if (vidToResolve.isNotBlank()) {
            val peeked = effectiveResolver.peek(vidToResolve)
            if (peeked != null && !peeked.isExpired()) {
                return peeked
            }
            return effectiveResolver.resolve(
                videoId = vidToResolve,
                songId = effectiveItem.songId,
                title = effectiveItem.title,
                artist = effectiveItem.artist,
                durationSec = effectiveItem.durationSec,
                forceRefresh = false
            ).getOrNull()
        }

        if (effectiveItem.title.isNotBlank()) {
            YouTubeIdRegistry.registerMetadata(
                songId = effectiveItem.songId,
                title = effectiveItem.title,
                artist = effectiveItem.artist,
                coverUrl = effectiveItem.coverUrl,
                videoId = effectiveItem.videoId
            )
        }
        return repository.getSongUrl(effectiveItem.songId).firstOrNull()?.getOrNull()?.let { url ->
            val vid = YouTubeIdRegistry.getVideoId(effectiveItem.songId) ?: ""
            com.lin0721.linmusic.core.player.resolver.ResolvedPlaybackData(
                videoId = vid,
                streamUrl = url,
                expiresAtMs = System.currentTimeMillis() + 4 * 3600 * 1000L
            )
        }
    }

    fun preResolveTracks(items: List<QueueItem>) {
        if (items.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            for (item in items.take(3)) {
                runCatching {
                    resolveStreamForItem(item)
                    if (item.title.isNotBlank()) {
                        YouTubeIdRegistry.registerMetadata(
                            songId = item.songId,
                            title = item.title,
                            artist = item.artist,
                            coverUrl = item.coverUrl,
                            videoId = item.videoId
                        )
                        repository.getLyrics(item.songId).firstOrNull()
                    }
                }
            }
        }
    }

    private var upcomingQueueJob: Job? = null

    fun preResolveAndQueueUpcoming(currentIndex: Int) {
        val items = playbackQueue.items.value
        if (items.size <= 1) return
        val count = items.size

        preloadUpcomingLyrics(currentIndex)

        upcomingQueueJob?.cancel()
        upcomingQueueJob = scope.launch(Dispatchers.IO) {
            val upcomingIndices = if (count > 2) {
                listOf((currentIndex + 1) % count, (currentIndex + 2) % count)
            } else {
                listOf((currentIndex + 1) % count)
            }

            val resolvedMediaItems = mutableListOf<MediaItem>()

            for (nextIdx in upcomingIndices) {
                if (nextIdx in items.indices && nextIdx != currentIndex) {
                    val rawNextItem = items[nextIdx]
                    val streamData = runCatching { resolveStreamForItem(rawNextItem) }.getOrNull()
                    val nextItem = playbackQueue.itemAt(nextIdx) ?: rawNextItem
                    if (streamData != null && streamData.streamUrl.isNotBlank()) {
                        val cached = metadataEnricher?.getCached(nextItem.songId.toString())
                            ?: metadataEnricher?.getCachedByQuery(nextItem.title, nextItem.artist)
                        val effectiveArt = cached?.artworkUrl ?: nextItem.coverUrl
                        val mediaItem = nextItem.toMediaItem(
                            url = streamData.streamUrl,
                            playContext = playbackQueue.playContext.value,
                            artworkUri = effectiveArt
                        )
                        resolvedMediaItems.add(mediaItem)
                    }
                }
            }

            if (resolvedMediaItems.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    controllerHolder.syncUpcomingMediaItems(resolvedMediaItems)
                    AppLogger.i(TAG, "[Playback] Added ${resolvedMediaItems.size} pre-resolved upcoming items to native ExoPlayer queue")
                }
            }
        }
    }

    private fun playbackStateName(state: Int) = when (state) {
        Player.STATE_IDLE -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> "READY"
        Player.STATE_ENDED -> "ENDED"
        else -> "UNKNOWN($state)"
    }

    private fun transitionReasonName(reason: Int) = when (reason) {
        Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> "AUTO"
        Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> "SEEK"
        Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> "REPEAT"
        Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> "PLAYLIST_CHANGED"
        else -> "UNKNOWN($reason)"
    }
}
