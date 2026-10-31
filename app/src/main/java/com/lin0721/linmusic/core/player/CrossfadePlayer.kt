package com.lin0721.linmusic.core.player

import android.media.AudioDeviceInfo
import android.os.Handler
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.lin0721.linmusic.core.log.AppLogger

private const val TAG = "CrossfadePlayer"
private const val FADE_STEP_MS = 16L

// Play： ExoPlayer ， Player。
// crossfadeMs  of Play，；
// setMediaItems  of  future ，SimpleBasePlayer
@OptIn(UnstableApi::class)
class CrossfadePlayer(
    private val primary: ExoPlayer,
    private val secondary: ExoPlayer,
    private val audioAttributes: AudioAttributes
) : ForwardingSimpleBasePlayer(primary) {

    private val handler = Handler(primary.applicationLooper)

    private var handleAudioFocus = true
    private var metadataTransformer: ((MediaMetadata) -> MediaMetadata)? = null

    private var pendingIncoming: ExoPlayer? = null
    private var pendingFuture: SettableFuture<Unit>? = null
    private var pendingFadeMs = 0L
    private var pendingBassSwap = false

    private var preArmedPlayer: ExoPlayer? = null
    private var preArmedMediaId: String? = null
    private var preArmedFadeMs: Long = 0L
    private var preArmedBassSwap: Boolean = false
    private var preArmedStartPositionMs: Long = 0L

    fun preArmStandby(mediaItem: MediaItem, startPositionMs: Long, fadeMs: Long, bassSwap: Boolean) {
        if (outgoing != null) {
            AppLogger.i(TAG, "preArmStandby: outgoing crossfade in progress, skipping pre-arm")
            return
        }
        val standby = other(active)
        if (standby === preArmedPlayer && preArmedMediaId == mediaItem.mediaId) {
            preArmedFadeMs = fadeMs
            preArmedBassSwap = bassSwap
            preArmedStartPositionMs = startPositionMs
            AppLogger.i(TAG, "preArmStandby: updated parameters for existing pre-armed track ${mediaItem.mediaId}")
            return
        }
        clearPreArmed()
        preArmedPlayer = standby
        preArmedMediaId = mediaItem.mediaId
        preArmedFadeMs = fadeMs
        preArmedBassSwap = bassSwap
        preArmedStartPositionMs = startPositionMs
        standby.stop()
        standby.clearMediaItems()
        standby.volume = 0f
        standby.repeatMode = active.repeatMode
        standby.setMediaItem(mediaItem, startPositionMs.coerceAtLeast(0L))
        standby.playWhenReady = false
        standby.prepare()
        AppLogger.i(TAG, "preArmStandby: pre-armed standby player for track ${mediaItem.mediaId} at ${startPositionMs}ms (fadeMs=$fadeMs, bassSwap=$bassSwap)")
    }

    fun clearPreArmed() {
        val armed = preArmedPlayer ?: return
        clearPreArmedTrackingOnly()
        armed.stop()
        armed.clearMediaItems()
        armed.volume = 1f
        AppLogger.i(TAG, "clearPreArmed: standby player cleared")
    }

    private fun clearPreArmedTrackingOnly() {
        preArmedPlayer = null
        preArmedMediaId = null
        preArmedFadeMs = 0L
        preArmedBassSwap = false
        preArmedStartPositionMs = 0L
    }

    private var outgoing: ExoPlayer? = null
    private var outgoingStartGain = 1f
    private var fadeStartElapsedMs = 0L
    private var fadeDurationMs = 0L
    private var isBassSwapActive = false

    private val active: ExoPlayer
        get() = if (player === secondary) secondary else primary

    private val fadeStep = object : Runnable {
        override fun run() {
            val out = outgoing ?: return
            val progress = ((SystemClock.elapsedRealtime() - fadeStartElapsedMs).toFloat() / fadeDurationMs).coerceIn(0f, 1f)
            out.volume = CrossfadePolicy.fadeOutGain(progress, outgoingStartGain, bassSwap = isBassSwapActive)
            active.volume = CrossfadePolicy.fadeInGain(progress)
            if (progress >= 1f) {
                releaseOutgoing()
            } else {
                handler.postDelayed(this, FADE_STEP_MS)
            }
        }
    }

    init {
        primary.setAudioAttributes(audioAttributes, false)
        secondary.setAudioAttributes(audioAttributes, false)
        val internalListener = InternalListener()
        primary.addListener(internalListener)
        secondary.addListener(internalListener)
    }

    fun setHandleAudioFocus(handle: Boolean) {
        handleAudioFocus = handle
        active.setAudioAttributes(audioAttributes, false)
    }

    fun setPreferredAudioDevice(device: AudioDeviceInfo?) {
        primary.setPreferredAudioDevice(device)
        secondary.setPreferredAudioDevice(device)
    }

    fun setMetadataTransformer(transformer: ((MediaMetadata) -> MediaMetadata)?) {
        metadataTransformer = transformer
        invalidateState()
    }


    fun notifyMetadataChanged() {
        invalidateState()
    }

    override fun getState(): State {
        val base = super.getState()
        // /Next，TracksPlay
        val commands = base.availableCommands.buildUpon()
            .add(Player.COMMAND_SEEK_TO_NEXT)
            .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .build()
        // ， 1，
        val builder = base.buildUpon().setAvailableCommands(commands).setVolume(1f)
        val transformer = metadataTransformer
        if (transformer != null && !base.timeline.isEmpty) {
            builder.setPlaylist(base.timeline, base.currentTracks, transformer(base.currentMetadata))
        }
        return builder.build()
    }

    // BUFFERING；， READY，
    // PlayerManager Play，
    override fun getPlaceholderState(suggestedPlaceholderState: State): State {
        if (pendingIncoming == null) return suggestedPlaceholderState
        return suggestedPlaceholderState.buildUpon()
            .setPlaybackState(Player.STATE_READY)
            .build()
    }

    override fun handleSetMediaItems(
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long
    ): ListenableFuture<*> {
        val extras = mediaItems.getOrNull(startIndex.coerceAtLeast(0))?.mediaMetadata?.extras
        val fadeMs = extras?.getLong(CrossfadePolicy.EXTRA_CROSSFADE_MS, 0L) ?: 0L
        val startOffsetMs = extras?.getLong(CrossfadePolicy.EXTRA_CROSSFADE_START_MS, 0L) ?: 0L
        val requestedItem = mediaItems.getOrNull(startIndex.coerceAtLeast(0))

        val currentActiveItem = active.currentMediaItem
        val isForceReload = extras?.getBoolean("forceReload", false) ?: false

        if (!isForceReload && requestedItem != null && currentActiveItem != null &&
            requestedItem.mediaId == currentActiveItem.mediaId &&
            active.playbackState != Player.STATE_IDLE &&
            active.playbackState != Player.STATE_ENDED &&
            startPositionMs <= 0L && fadeMs <= 0L
        ) {
            AppLogger.i(TAG, "Idempotent guard: track ${requestedItem.mediaId} is already active in CrossfadePlayer")
            return Futures.immediateVoidFuture()
        }

        // ： of Play， future ，
        // SimpleBasePlayer Play，
        val bassSwap = extras?.getBoolean(CrossfadePolicy.EXTRA_BASS_SWAP, false) ?: false


        pendingFuture?.let { stale -> handler.post { stale.set(Unit) } }
        pendingFuture = null
        val abandoned = pendingIncoming
        pendingIncoming = null

        val standby = other(active)
        if (fadeMs > 0L && (active.isPlaying || active.playWhenReady) && standby === preArmedPlayer && preArmedMediaId == requestedItem?.mediaId) {
            AppLogger.i(TAG, "handleSetMediaItems: standby player pre-armed for ${requestedItem?.mediaId}, playbackState=${standby.playbackState}")
            stopOutgoing()
            active.pauseAtEndOfMediaItems = true
            if (active.mediaItemCount > 1) {
                val cur = active.currentMediaItemIndex
                if (cur >= 0 && active.mediaItemCount > cur + 1) {
                    active.removeMediaItems(cur + 1, active.mediaItemCount)
                }
            }
            clearPreArmedTrackingOnly()
            if (standby.playbackState == Player.STATE_READY) {
                AppLogger.i(TAG, "handleSetMediaItems: standby is STATE_READY, executing instant 0ms crossfade blend!")
                standby.playWhenReady = true
                executeTransition(standby, fadeMs, bassSwap)
                return Futures.immediateVoidFuture()
            } else {
                AppLogger.i(TAG, "handleSetMediaItems: standby is still buffering (${standby.playbackState}), waiting for ready...")
                standby.playWhenReady = true
                val future = SettableFuture.create<Unit>()
                pendingIncoming = standby
                pendingFuture = future
                pendingFadeMs = fadeMs
                pendingBassSwap = bassSwap
                return future
            }
        }

        if (fadeMs <= 0L || !active.isPlaying) {
            clearPreArmed()
            abandoned?.run {
                stop()
                clearMediaItems()
            }
            releaseOutgoing()
            return super.handleSetMediaItems(mediaItems, startIndex, startPositionMs)
        }
        clearPreArmed()
        return startCrossfade(mediaItems, startIndex, maxOf(startPositionMs, startOffsetMs), fadeMs, bassSwap)
    }

    // /Next onPlayerCommandRequest  PlayerManager；
    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        if (seekCommand != 0) {
            clearPreArmed()
        }
        return when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> {
                if (active.hasNextMediaItem()) {
                    active.seekToNextMediaItem()
                    Futures.immediateVoidFuture()
                } else {
                    Futures.immediateVoidFuture()
                }
            }
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> {
                if (active.hasPreviousMediaItem()) {
                    active.seekToPreviousMediaItem()
                    Futures.immediateVoidFuture()
                } else {
                    Futures.immediateVoidFuture()
                }
            }
            else -> super.handleSeek(mediaItemIndex, positionMs, seekCommand)
        }
    }

    override fun handleAddMediaItems(index: Int, mediaItems: MutableList<MediaItem>): ListenableFuture<*> {
        active.addMediaItems(index.coerceAtLeast(0), mediaItems)
        return Futures.immediateVoidFuture()
    }

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
        active.removeMediaItems(fromIndex, toIndex)
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (!playWhenReady) {
            completePendingImmediately()
            releaseOutgoing()
        }
        return super.handleSetPlayWhenReady(playWhenReady)
    }

    override fun handleStop(): ListenableFuture<*> {
        completePendingImmediately()
        releaseOutgoing()
        return super.handleStop()
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        other(active).repeatMode = repeatMode
        return super.handleSetRepeatMode(repeatMode)
    }

    override fun handleRelease(): ListenableFuture<*> {
        clearPreArmed()
        handler.removeCallbacksAndMessages(null)
        pendingFuture?.set(Unit)
        pendingFuture = null
        pendingIncoming = null
        outgoing = null
        primary.release()
        secondary.release()
        return Futures.immediateVoidFuture()
    }

    private fun startCrossfade(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
        fadeMs: Long,
        bassSwap: Boolean = false
    ): ListenableFuture<*> {
        stopOutgoing()
        if (active.mediaItemCount > 1) {
            val cur = active.currentMediaItemIndex
            if (cur >= 0 && active.mediaItemCount > cur + 1) {
                active.removeMediaItems(cur + 1, active.mediaItemCount)
            }
        }
        val incoming = other(active)
        incoming.stop()
        incoming.volume = 0f
        incoming.repeatMode = active.repeatMode
        incoming.setMediaItems(mediaItems, startIndex, startPositionMs)
        incoming.playWhenReady = true
        incoming.prepare()

        val future = SettableFuture.create<Unit>()
        pendingIncoming = incoming
        pendingFuture = future
        pendingFadeMs = fadeMs
        pendingBassSwap = bassSwap
        return future
    }

    private fun executeTransition(incoming: ExoPlayer, fadeMs: Long, bassSwap: Boolean) {
        val previous = active
        takeOver(incoming)
        outgoing = previous
        outgoingStartGain = previous.volume
        fadeStartElapsedMs = SystemClock.elapsedRealtime()
        fadeDurationMs = fadeMs.coerceAtLeast(1L)
        isBassSwapActive = bassSwap
        AppLogger.i(TAG, "executeTransition: executing crossfade blend fadeDurationMs=$fadeDurationMs bassSwap=$isBassSwapActive")
        handler.post(fadeStep)
    }

    private fun onIncomingReady(incoming: ExoPlayer) {
        if (pendingIncoming !== incoming || incoming.playbackState != Player.STATE_READY) return
        executeTransition(incoming, pendingFadeMs, pendingBassSwap)
        finishPending()
    }

    // PlayPlay，Pause
    private fun takeOver(incoming: ExoPlayer) {
        val previous = active
        previous.setAudioAttributes(audioAttributes, false)
        incoming.setAudioAttributes(audioAttributes, false)
        setPlayer(incoming)
    }

    private fun completePendingImmediately(playWhenReady: Boolean = true) {
        val incoming = pendingIncoming ?: return
        val previous = active
        takeOver(incoming)
        incoming.volume = 1f
        if (!playWhenReady) incoming.playWhenReady = false
        previous.stop()
        previous.clearMediaItems()
        previous.volume = 1f
        finishPending()
    }

    private fun finishPending() {
        pendingIncoming = null
        val future = pendingFuture
        pendingFuture = null
        future?.set(Unit)
    }

    // ：，CurrentPlay
    private fun releaseOutgoing() {
        val hadOutgoing = outgoing != null
        stopOutgoing()
        if (hadOutgoing) active.volume = 1f
    }

    private fun stopOutgoing() {
        handler.removeCallbacks(fadeStep)
        val out = outgoing ?: return
        outgoing = null
        out.stop()
        out.clearMediaItems()
        out.volume = 1f
    }

    private fun other(player: ExoPlayer): ExoPlayer = if (player === primary) secondary else primary

    private inner class InternalListener : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            val exo = player as? ExoPlayer ?: return
            if (exo === pendingIncoming && exo.playbackState == Player.STATE_READY) {
                // ExoPlayer ，
                handler.post { onIncomingReady(exo) }
            }
            // ：，， of  READY
            if (exo === active && pendingIncoming != null &&
                (exo.playbackState == Player.STATE_ENDED || exo.playerError != null)
            ) {
                handler.post { completePendingImmediately() }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            // ， PlayerManager  of
            val incoming = pendingIncoming ?: return
            if (incoming.playerError == null) return
            AppLogger.w(TAG, "Crossfade target failed to load, switching directly", error)
            completePendingImmediately()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // PauseCurrentPlay：， of Pause
            if (playWhenReady || active.playWhenReady) return
            completePendingImmediately(playWhenReady = false)
            releaseOutgoing()
        }
    }
}
