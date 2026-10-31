package com.lin0721.linmusic.core.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionResult
import coil.Coil
import coil.request.ImageRequest
import com.lin0721.linmusic.MainActivity
import com.lin0721.linmusic.R
import com.lin0721.linmusic.core.auth.UserPreferences
import com.lin0721.linmusic.core.log.AppLogger
import com.lin0721.linmusic.core.player.external.ExternalLyricCoordinator
import com.lin0721.linmusic.core.preferences.SettingsPreferences
import com.lin0721.linmusic.core.songlike.SongLikeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

private const val TAG = "MimikyuPlaybackService"
private const val NOTIFICATION_ID = 1001
private const val PLAYBACK_CHANNEL_ID = "mimikyu_playback_channel"
private const val PLAYBACK_CHANNEL_NAME = "Now Playing"

class MimikyuPlaybackService : MediaSessionService() {

    private val playerManager: PlayerManager by inject()
    private val settingsPreferences: SettingsPreferences by inject()
    private val userPreferences: UserPreferences by inject()
    private val songLikeRepository: SongLikeRepository by inject()
    private val externalLyricCoordinator: ExternalLyricCoordinator by inject()
    private val externalInterruptionResumeController: ExternalInterruptionResumeController by inject()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var isLikedListLoaded = false

    private var player: Player? = null
    private var crossfadePlayer: CrossfadePlayer? = null
    private var mediaSession: MediaSession? = null
    private var sessionActivityPendingIntent: PendingIntent? = null
    private var currentCoverBitmap: Bitmap? = null
    private var notificationCallback: MediaNotification.Provider.Callback? = null

    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private var audioFocusRequest: AudioFocusRequest? = null
    private var isPlayingWithOtherApps: Boolean = false
    private var hasAudioFocus: Boolean = false
    private var isBecomingNoisyRegistered: Boolean = false

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                AppLogger.i(TAG, "Audio focus lost ($focusChange). Pausing playback immediately.")
                hasAudioFocus = false
                crossfadePlayer?.pause()
                playerManager.pause()
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                // IMPORTANT: When external audio ends, reels pause, or camera/call closes,
                // do NOT auto-resume playback. Keep music paused until the user explicitly hits play.
                AppLogger.i(TAG, "Audio focus regained ($focusChange). Keeping playback paused.")
                hasAudioFocus = true
            }
        }
    }

    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                AppLogger.i(TAG, "Headphones disconnected (ACTION_AUDIO_BECOMING_NOISY). Pausing playback.")
                crossfadePlayer?.pause()
                playerManager.pause()
            }
        }
    }

    private fun requestAppAudioFocus(): Boolean {
        if (isPlayingWithOtherApps) return true
        if (hasAudioFocus) return true

        val playbackAttributes = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(playbackAttributes)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener(audioFocusChangeListener)
            .build()

        audioFocusRequest = request
        val result = audioManager.requestAudioFocus(request)
        return if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            hasAudioFocus = true
            true
        } else {
            AppLogger.w(TAG, "Audio focus request denied: $result")
            hasAudioFocus = false
            false
        }
    }

    private fun abandonAppAudioFocus() {
        if (!hasAudioFocus) return
        audioFocusRequest?.let { request ->
            audioManager.abandonAudioFocusRequest(request)
            audioFocusRequest = null
        }
        hasAudioFocus = false
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager?.getNotificationChannel(PLAYBACK_CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    PLAYBACK_CHANNEL_ID,
                    PLAYBACK_CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Control currently playing music"
                    setShowBadge(false)
                }
                manager?.createNotificationChannel(channel)
            }
        }
    }

    private fun loadCoverBitmap(coverUri: android.net.Uri?) {
        if (coverUri == null) {
            currentCoverBitmap = null
            return
        }
        serviceScope.launch(Dispatchers.IO) {
            try {
                val request = ImageRequest.Builder(this@MimikyuPlaybackService)
                    .data(coverUri)
                    .allowHardware(false)
                    .build()
                val result = Coil.imageLoader(this@MimikyuPlaybackService).execute(request)
                val drawable = result.drawable
                if (drawable is BitmapDrawable) {
                    currentCoverBitmap = drawable.bitmap
                    withContext(Dispatchers.Main) {
                        updateMediaSessionButtons()
                    }
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "Failed to load notification cover art", e)
            }
        }
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        AppLogger.i(TAG, "Service onCreate instanceId=${System.identityHashCode(this)}")

        ensureNotificationChannel()
        setMediaNotificationProvider(MimikyuNotificationProvider())

        serviceScope.launch {
            songLikeRepository.likedSongIds.collect {
                updateMediaSessionButtons()
            }
        }

        // Real-time speed measurement via DefaultBandwidthMeter
        val bandwidthMeter = NetworkSpeedMonitor.getMeter(this)

        // Allow cross-protocol redirects (HTTPS to HTTP)
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setTransferListener(bandwidthMeter)
        val defaultDataSourceFactory = DefaultDataSource.Factory(this, httpDataSourceFactory)
            .setTransferListener(bandwidthMeter)

        var isCacheEnabled = true
        var cacheMaxSize = AudioCacheManager.DEFAULT_MAX_CACHE_SIZE

        serviceScope.launch {
            settingsPreferences.streamCacheEnabled.collect { enabled ->
                isCacheEnabled = enabled
            }
        }
        serviceScope.launch {
            settingsPreferences.audioCacheMaxSize.collect { size ->
                if (size > 0L) cacheMaxSize = size
            }
        }

        // Dynamic data source using Media3 CacheDataSource with LRU SimpleCache and stable keys
        val dynamicDataSourceFactory = androidx.media3.datasource.DataSource.Factory {
            if (isCacheEnabled) {
                val cache = AudioCacheManager.getCache(this@MimikyuPlaybackService, cacheMaxSize)
                CacheDataSource.Factory()
                    .setCache(cache)
                    .setUpstreamDataSourceFactory(defaultDataSourceFactory)
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                    .createDataSource()
            } else {
                defaultDataSourceFactory.createDataSource()
            }
        }

        val mediaSourceFactory = DefaultMediaSourceFactory(dynamicDataSourceFactory)
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 30_000,
                /* maxBufferMs = */ 50_000,
                /* bufferForPlaybackMs = */ 1_500,
                /* bufferForPlaybackAfterRebufferMs = */ 3_000
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(15_000, true)
            .build()

        val primaryPlayer = ExoPlayer.Builder(this)
            .setBandwidthMeter(bandwidthMeter)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .build()

        val secondaryPlayer = ExoPlayer.Builder(this)
            .setBandwidthMeter(bandwidthMeter)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .build()

        val audioSessionListener = object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                if (audioSessionId != androidx.media3.common.C.AUDIO_SESSION_ID_UNSET && audioSessionId > 0) {
                    UltraAudioMasteringEngine.attachStudioMasterDSP(audioSessionId)
                }
            }
        }
        primaryPlayer.addListener(audioSessionListener)
        secondaryPlayer.addListener(audioSessionListener)

        if (primaryPlayer.audioSessionId > 0 && primaryPlayer.audioSessionId != androidx.media3.common.C.AUDIO_SESSION_ID_UNSET) {
            UltraAudioMasteringEngine.attachStudioMasterDSP(primaryPlayer.audioSessionId)
        }
        if (secondaryPlayer.audioSessionId > 0 && secondaryPlayer.audioSessionId != androidx.media3.common.C.AUDIO_SESSION_ID_UNSET) {
            UltraAudioMasteringEngine.attachStudioMasterDSP(secondaryPlayer.audioSessionId)
        }

        val sessionPlayer = CrossfadePlayer(
            primary = primaryPlayer,
            secondary = secondaryPlayer,
            audioAttributes = audioAttributes
        )
        sessionPlayer.setMetadataTransformer { base ->
            externalLyricCoordinator.applyToMediaMetadata(base.buildUpon(), base)
        }
        crossfadePlayer = sessionPlayer

        serviceScope.launch {
            settingsPreferences.wifiQuality.collect { qualityKey ->
                val preset = AudioMasteringPreset.fromKey(qualityKey)
                UltraAudioMasteringEngine.setPreset(preset)
            }
        }

        try {
            val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            ContextCompat.registerReceiver(
                this,
                becomingNoisyReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
            isBecomingNoisyRegistered = true
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to register becomingNoisyReceiver", e)
        }

        serviceScope.launch {
            settingsPreferences.playWithOtherApps.collect { playWithOtherApps ->
                isPlayingWithOtherApps = playWithOtherApps
                sessionPlayer.setHandleAudioFocus(!playWithOtherApps)
                if (playWithOtherApps) {
                    abandonAppAudioFocus()
                } else if (sessionPlayer.playWhenReady) {
                    requestAppAudioFocus()
                }
            }
        }

        player = sessionPlayer

        // ï¼›REORDER_TO_FRONT  Activity
        val sessionActivityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            sessionActivityIntent,
            PendingIntent.FLAG_IMMUTABLE
        )
        sessionActivityPendingIntent = pendingIntent

        mediaSession = MediaSession.Builder(this, sessionPlayer)
            .setCallback(CustomSessionCallback())
            .setSessionActivity(pendingIntent)
            .build()

        externalLyricCoordinator.onMetadataChanged = {
            sessionPlayer.notifyMetadataChanged()
        }

        externalInterruptionResumeController.start(
            scope = serviceScope,
            onResumeRequested = {
                playerManager.resume()
                sessionPlayer.play()
            }
        )

        // Songs of ï¼Œ
        sessionPlayer.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                super.onMediaItemTransition(mediaItem, reason)
                loadCoverBitmap(mediaItem?.mediaMetadata?.artworkUri)
                mediaItem?.mediaId?.toLongOrNull()?.let { songId ->
                    checkAndFetchLikedStatus(songId)
                } ?: updateMediaSessionButtons()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                super.onPlayWhenReadyChanged(playWhenReady, reason)
                if (playWhenReady) {
                    val granted = requestAppAudioFocus()
                    if (!granted && !isPlayingWithOtherApps) {
                        sessionPlayer.pause()
                        playerManager.pause()
                    }
                    externalInterruptionResumeController.onPlaybackStarted()
                } else {
                    abandonAppAudioFocus()
                    if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS) {
                        externalInterruptionResumeController.onAudioFocusLoss()
                    } else {
                        externalInterruptionResumeController.onUserOrSystemPause()
                    }
                }
            }
        })
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        val showLock = runBlocking { settingsPreferences.showLockscreen.first() }
        if (!showLock) {
            val allowedPackages = listOf(packageName, "com.android.bluetooth")
            if (controllerInfo.packageName !in allowedPackages) {
                return null
            }
        }
        return mediaSession
    }

    override fun onDestroy() {
        AppLogger.i(TAG, "Service onDestroy instanceId=${System.identityHashCode(this)}")
        if (isBecomingNoisyRegistered) {
            try {
                unregisterReceiver(becomingNoisyReceiver)
            } catch (e: Exception) {
                AppLogger.w(TAG, "Failed to unregister becomingNoisyReceiver", e)
            }
            isBecomingNoisyRegistered = false
        }
        abandonAppAudioFocus()
        externalInterruptionResumeController.release()
        externalLyricCoordinator.onMetadataChanged = null
        externalLyricCoordinator.release()
        playerManager.release()
        playerManager.saveState()
        serviceScope.cancel()
        notificationCallback = null
        currentCoverBitmap = null
        mediaSession?.run {
            release()
            mediaSession = null
        }
        crossfadePlayer?.release()
        crossfadePlayer = null
        UltraAudioMasteringEngine.releaseAll()
        player = null
        super.onDestroy()
    }

    private fun checkAndFetchLikedStatus(songId: Long) {
        serviceScope.launch {
            val profile = userPreferences.userProfile.first()
            if (profile != null && !isLikedListLoaded) {
                songLikeRepository.getLikedSongIds(profile.uid).collect { result ->
                    result.onSuccess {
                        isLikedListLoaded = true
                    }.onFailure {
                        AppLogger.w(TAG, "Failed to fetch liked songs, heart state may be desynced", it)
                    }
                }
            } else {
                updateMediaSessionButtons()
            }
        }
    }

    private fun toggleLike(songId: Long) {
        serviceScope.launch {
            val isLiked = songId in songLikeRepository.likedSongIds.value
            val targetLiked = !isLiked
            songLikeRepository.likeSong(songId, targetLiked).collect { }
        }
    }

    @OptIn(UnstableApi::class)
    private fun buildLikeButton(): CommandButton {
        val songId = player?.currentMediaItem?.mediaId?.toLongOrNull() ?: -1L
        val isLiked = songId != -1L && songId in songLikeRepository.likedSongIds.value
        val icon = if (isLiked) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED
        val displayName = if (isLiked) "Unlike" else "Like"

        return CommandButton.Builder(icon)
            .setSessionCommand(SessionCommand("ACTION_TOGGLE_LIKE", Bundle()))
            .setDisplayName(displayName)
            .setSlots(CommandButton.SLOT_BACK_SECONDARY, CommandButton.SLOT_OVERFLOW)
            .build()
    }

    @OptIn(UnstableApi::class)
    private fun buildCustomLayout(): List<CommandButton> {
        return listOf(buildLikeButton())
    }

    @OptIn(UnstableApi::class)
    private fun buildMediaButtonPreferences(): List<CommandButton> {
        return listOf(
            CommandButton.Builder(CommandButton.ICON_PREVIOUS)
                .setPlayerCommand(Player.COMMAND_SEEK_TO_PREVIOUS)
                .setDisplayName("Previous")
                .setSlots(CommandButton.SLOT_BACK)
                .build(),
            CommandButton.Builder(CommandButton.ICON_NEXT)
                .setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT)
                .setDisplayName("Next")
                .setSlots(CommandButton.SLOT_FORWARD)
                .build(),
            buildLikeButton()
        )
    }

    private fun buildAvailableSessionCommands(): SessionCommands {
        return SessionCommands.Builder()
            .add(SessionCommand("ACTION_TOGGLE_LIKE", Bundle()))
            .add(SessionCommand("ACTION_TOGGLE_PLAY_MODE", Bundle()))
            .add(SessionCommand("ACTION_SET_PREFERRED_AUDIO_DEVICE", Bundle()))
            .add(SessionCommand("ACTION_PRE_ARM_STANDBY", Bundle()))
            .add(SessionCommand("ACTION_CLEAR_PRE_ARM", Bundle()))
            .build()
    }

    @OptIn(UnstableApi::class)
    private fun updateMediaSessionButtons() {
        val session = mediaSession ?: return
        val customLayout = buildCustomLayout()
        val mediaButtonPreferences = buildMediaButtonPreferences()
        val sessionCommands = buildAvailableSessionCommands()

        session.setCustomLayout(customLayout)
        session.setMediaButtonPreferences(mediaButtonPreferences)
        for (controller in session.connectedControllers) {
            session.setAvailableCommands(
                controller,
                sessionCommands,
                MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS
            )
        }
    }

    @Suppress("DEPRECATION")
    private inner class CustomSessionCallback : MediaSession.Callback {
        @OptIn(UnstableApi::class)
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(buildAvailableSessionCommands())
                .setAvailablePlayerCommands(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS)
                .setCustomLayout(buildCustomLayout())
                .setMediaButtonPreferences(buildMediaButtonPreferences())
                .setSessionActivity(sessionActivityPendingIntent)
                .build()
        }

        override fun onPostConnect(session: MediaSession, controller: MediaSession.ControllerInfo) {
            super.onPostConnect(session, controller)
            updateMediaSessionButtons()
        }

        override fun onPlayerCommandRequest(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            playerCommand: Int
        ): Int {
            when (playerCommand) {
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> {
                    if (crossfadePlayer?.hasNextMediaItem() == true) {
                        return super.onPlayerCommandRequest(session, controller, playerCommand)
                    }
                    playerManager.playNext()
                    return SessionResult.RESULT_SUCCESS
                }
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> {
                    if (crossfadePlayer?.hasPreviousMediaItem() == true) {
                        return super.onPlayerCommandRequest(session, controller, playerCommand)
                    }
                    playerManager.playPrevious()
                    return SessionResult.RESULT_SUCCESS
                }
            }
            return super.onPlayerCommandRequest(session, controller, playerCommand)
        }

        @OptIn(UnstableApi::class)
        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): com.google.common.util.concurrent.ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                "ACTION_TOGGLE_LIKE" -> {
                    val songId = player?.currentMediaItem?.mediaId?.toLongOrNull() ?: -1L
                    if (songId != -1L) {
                        toggleLike(songId)
                    }
                    return com.google.common.util.concurrent.Futures.immediateFuture(
                        SessionResult(SessionResult.RESULT_SUCCESS)
                    )
                }
                "ACTION_TOGGLE_PLAY_MODE" -> {
                    playerManager.rotatePlayMode()
                    updateMediaSessionButtons()
                    return com.google.common.util.concurrent.Futures.immediateFuture(
                        SessionResult(SessionResult.RESULT_SUCCESS)
                    )
                }
                "ACTION_PRE_ARM_STANDBY" -> {
                    val itemBundle = args.getBundle("media_item")
                    val item = if (itemBundle != null) {
                        runCatching { MediaItem.fromBundle(itemBundle) }.getOrNull()
                    } else null
                    val uri = args.getString("uri")
                    val effectiveItem = if (item != null && item.localConfiguration?.uri != null) {
                        item
                    } else if (!uri.isNullOrBlank()) {
                        val mediaId = args.getString("media_id").orEmpty()
                        MediaItem.Builder()
                            .setMediaId(mediaId)
                            .setUri(uri)
                            .apply { item?.mediaMetadata?.let { setMediaMetadata(it) } }
                            .build()
                    } else null
                    val startPositionMs = args.getLong("start_position_ms", 0L)
                    val fadeMs = args.getLong("fade_ms", 0L)
                    val bassSwap = args.getBoolean("bass_swap", false)
                    if (effectiveItem != null) {
                        crossfadePlayer?.preArmStandby(effectiveItem, startPositionMs, fadeMs, bassSwap)
                        return com.google.common.util.concurrent.Futures.immediateFuture(
                            SessionResult(SessionResult.RESULT_SUCCESS)
                        )
                    }
                    return com.google.common.util.concurrent.Futures.immediateFuture(
                        SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE)
                    )
                }
                "ACTION_CLEAR_PRE_ARM" -> {
                    crossfadePlayer?.clearPreArmed()
                    return com.google.common.util.concurrent.Futures.immediateFuture(
                        SessionResult(SessionResult.RESULT_SUCCESS)
                    )
                }
                "ACTION_SET_PREFERRED_AUDIO_DEVICE" -> {
                    val deviceId = args.getInt("device_id", -1)
                    val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
                    val targetDevice = if (deviceId == -1) {
                        null
                    } else {
                        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                            .firstOrNull { it.id == deviceId }
                    }
                    crossfadePlayer?.setPreferredAudioDevice(targetDevice)
                    return com.google.common.util.concurrent.Futures.immediateFuture(
                        SessionResult(SessionResult.RESULT_SUCCESS)
                    )
                }
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }
    }

    @OptIn(UnstableApi::class)
    private inner class MimikyuNotificationProvider : MediaNotification.Provider {
        override fun createNotification(
            session: MediaSession,
            customLayout: com.google.common.collect.ImmutableList<CommandButton>,
            actionFactory: MediaNotification.ActionFactory,
            onNotificationChangedCallback: MediaNotification.Provider.Callback
        ): MediaNotification {
            notificationCallback = onNotificationChangedCallback
            ensureNotificationChannel()

            val currentItem = session.player.currentMediaItem
            val metadata = currentItem?.mediaMetadata
            val title = metadata?.title?.toString()?.ifBlank { null } ?: getString(R.string.app_name)
            val artist = metadata?.artist?.toString() ?: ""

            val isPlaying = session.player.isPlaying
            val playPauseIconRes = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
            val playPauseTitle = if (isPlaying) "Pause" else "Play"

            val prevAction = actionFactory.createMediaAction(
                session,
                IconCompat.createWithResource(this@MimikyuPlaybackService, android.R.drawable.ic_media_previous),
                "Previous",
                Player.COMMAND_SEEK_TO_PREVIOUS
            )
            val playPauseAction = actionFactory.createMediaAction(
                session,
                IconCompat.createWithResource(this@MimikyuPlaybackService, playPauseIconRes),
                playPauseTitle,
                Player.COMMAND_PLAY_PAUSE
            )
            val nextAction = actionFactory.createMediaAction(
                session,
                IconCompat.createWithResource(this@MimikyuPlaybackService, android.R.drawable.ic_media_next),
                "Next",
                Player.COMMAND_SEEK_TO_NEXT
            )

            val songId = currentItem?.mediaId?.toLongOrNull() ?: -1L
            val isLiked = songId != -1L && songId in songLikeRepository.likedSongIds.value
            val likeIconRes = if (isLiked) R.drawable.ic_favorite else R.drawable.ic_favorite_border
            val likeTitle = if (isLiked) "Unlike" else "Like"
            val likeAction = actionFactory.createCustomAction(
                session,
                IconCompat.createWithResource(this@MimikyuPlaybackService, likeIconRes),
                likeTitle,
                "ACTION_TOGGLE_LIKE",
                Bundle.EMPTY
            )

            val builder = NotificationCompat.Builder(this@MimikyuPlaybackService, PLAYBACK_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(artist)
                .setContentIntent(sessionActivityPendingIntent)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .setOngoing(isPlaying)
                .setShowWhen(false)
                .addAction(prevAction)
                .addAction(playPauseAction)
                .addAction(nextAction)
                .addAction(likeAction)

            currentCoverBitmap?.let { bitmap ->
                builder.setLargeIcon(bitmap)
            }

            val mediaStyle = MediaNotificationCompat.MediaStyle()
                .setShowActionsInCompactView(0, 1, 2)
                .setMediaSession(
                    android.support.v4.media.session.MediaSessionCompat.Token.fromToken(
                        session.platformToken
                    )
                )
            builder.setStyle(mediaStyle)

            return MediaNotification(NOTIFICATION_ID, builder.build())
        }

        override fun handleCustomCommand(
            session: MediaSession,
            action: String,
            extras: Bundle
        ): Boolean = false

        override fun getNotificationChannelInfo(): MediaNotification.Provider.NotificationChannelInfo =
            MediaNotification.Provider.NotificationChannelInfo(PLAYBACK_CHANNEL_ID, PLAYBACK_CHANNEL_NAME)
    }
}



