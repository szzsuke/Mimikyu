package com.lin0721.linmusic.core.player

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.lin0721.linmusic.core.log.AppLogger
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "MediaControllerHolder"

// and  MimikyuPlaybackService  of  MediaController ，Play
class MediaControllerHolder(private val context: Context) {

    private var controller: MediaController? = null

    val isConnected: Boolean get() = controller != null

    val currentPosition: Long get() = controller?.currentPosition ?: 0L

    // Back null，
    val currentPositionOrNull: Long? get() = controller?.currentPosition

    val duration: Long get() = controller?.duration ?: 0L

    val bufferedPosition: Long get() = controller?.bufferedPosition ?: 0L

    val playbackState: Int get() = controller?.playbackState ?: Player.STATE_IDLE

    val isPlaying: Boolean get() = controller?.isPlaying ?: false

    val playbackSpeed: Float get() = controller?.playbackParameters?.speed ?: 1.0f

    val currentMediaItem: MediaItem? get() = controller?.currentMediaItem

    fun isPlayingOrBuffering(): Boolean = isPlaying || playbackState == Player.STATE_BUFFERING

    var onBeforePrepare: (() -> Unit)? = null

    // ，onReady 、，
    suspend fun connect(listener: Player.Listener, onReady: (MediaController) -> Unit) {
        if (controller != null) return

        val sessionToken = SessionToken(
            context,
            ComponentName(context, MimikyuPlaybackService::class.java)
        )

        controller = suspendCancellableCoroutine { continuation ->
            val factory = MediaController.Builder(context, sessionToken).buildAsync()
            factory.addListener(
                {
                    try {
                        val mediaController = factory.get()
                        mediaController.addListener(listener)
                        AppLogger.i(TAG, "MediaController connected successfully")
                        onReady(mediaController)
                        continuation.resume(mediaController)
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "MediaController connection failed", e)
                        continuation.resumeWithException(e)
                    }
                },
                ContextCompat.getMainExecutor(context)
            )

            continuation.invokeOnCancellation {
                factory.cancel(true)
            }
        }
    }

    fun setRepeatMode(mode: PlayMode) {
        controller?.repeatMode = if (mode == PlayMode.SINGLE_LOOP)
            Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    // ，；playWhenReady=false
    fun playItem(mediaItem: MediaItem, mode: PlayMode, startPosition: Long, playWhenReady: Boolean = true) {
        controller?.apply {
            repeatMode = if (mode == PlayMode.SINGLE_LOOP)
                Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            setMediaItem(mediaItem, startPosition.coerceAtLeast(0L))
            onBeforePrepare?.invoke()
            prepare()
            if (playWhenReady) play() else pause()
        }
    }

    fun play() {
        controller?.play()
    }

    fun pause() {
        controller?.pause()
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
    }

    fun pauseAndRewind() {
        controller?.pause()
        controller?.seekTo(0)
    }

    fun stopAndClear() {
        controller?.stop()
        controller?.clearMediaItems()
    }

    val mediaItemCount: Int get() = controller?.mediaItemCount ?: 0

    val currentMediaItemIndex: Int get() = controller?.currentMediaItemIndex ?: 0

    fun hasNextMediaItem(): Boolean = controller?.hasNextMediaItem() == true

    fun peekNextMediaId(): String? {
        val c = controller ?: return null
        val nextIndex = c.currentMediaItemIndex + 1
        if (nextIndex < c.mediaItemCount) {
            return c.getMediaItemAt(nextIndex).mediaId
        }
        return null
    }

    fun seekToNextMediaItem() {
        controller?.seekToNextMediaItem()
    }

    fun syncUpcomingMediaItems(upcomingItems: List<MediaItem>) {
        val c = controller ?: return
        if (upcomingItems.isEmpty()) return
        runCatching {
            val currentIdx = c.currentMediaItemIndex
            val totalCount = c.mediaItemCount

            // Only remove items strictly after current playing index when currentIdx is valid (>= 0)
            if (currentIdx >= 0) {
                if (totalCount > currentIdx + 1) {
                    c.removeMediaItems(currentIdx + 1, totalCount)
                }
                c.addMediaItems(upcomingItems)
            } else if (totalCount == 0) {
                c.addMediaItems(upcomingItems)
            }
        }.onFailure { e ->
            com.lin0721.linmusic.core.log.AppLogger.w("MediaControllerHolder", "syncUpcomingMediaItems safely caught: ${e.message}")
        }
    }

    // deviceId  -1 ， Service  of  AudioDeviceInfo
    fun preArmStandby(mediaItem: MediaItem, startPositionMs: Long, fadeMs: Long, bassSwap: Boolean) {
        val args = Bundle().apply {
            runCatching { putBundle("media_item", mediaItem.toBundle()) }
            putString("media_id", mediaItem.mediaId)
            putString("uri", mediaItem.localConfiguration?.uri?.toString())
            putLong("start_position_ms", startPositionMs)
            putLong("fade_ms", fadeMs)
            putBoolean("bass_swap", bassSwap)
        }
        controller?.sendCustomCommand(
            SessionCommand("ACTION_PRE_ARM_STANDBY", Bundle()),
            args
        )
    }

    fun clearPreArmed() {
        controller?.sendCustomCommand(
            SessionCommand("ACTION_CLEAR_PRE_ARM", Bundle()),
            Bundle()
        )
    }

    fun setPreferredAudioDevice(deviceId: Int) {
        controller?.sendCustomCommand(
            SessionCommand("ACTION_SET_PREFERRED_AUDIO_DEVICE", Bundle()),
            Bundle().apply { putInt("device_id", deviceId) }
        )
    }
}
