package com.lin0721.linmusic.core.player

import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// Progress tracker: only runs coroutine loop when player.isPlaying == true (audio actively playing)
class PlaybackProgressTracker(
    private val scope: CoroutineScope,
    private val controller: MediaControllerHolder
) {

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    private val _updateInterval = MutableStateFlow(25L) // 16–50 ms
    val updateInterval: StateFlow<Long> = _updateInterval.asStateFlow()

    private val clock = LyricsPlaybackClock()
    private var progressJob: Job? = null
    private var onTickCallback: (suspend (positionMs: Long) -> Unit)? = null

    private var isHeld: Boolean = false
    private var heldPosition: Long = 0L
    private var heldDuration: Long = 0L

    fun hold(positionMs: Long, durationMs: Long) {
        isHeld = true
        heldPosition = positionMs
        heldDuration = durationMs
        _currentPosition.value = positionMs
        if (durationMs > 0L) {
            _duration.value = durationMs
        }
    }

    fun releaseHold() {
        if (!isHeld) return
        isHeld = false
        val curPos = controller.currentPosition
        clock.reset(curPos)
        _currentPosition.value = curPos
        updateDurationFromController()
    }

    fun setUpdateInterval(intervalMs: Long) {
        _updateInterval.value = intervalMs.coerceIn(16L, 50L)
    }

    fun syncImmediatePosition() {
        if (isHeld) return
        val curPos = controller.currentPosition
        clock.reset(curPos)
        _currentPosition.value = curPos
    }

    fun setPosition(positionMs: Long) {
        if (isHeld) return
        clock.reset(positionMs)
        _currentPosition.value = positionMs
    }

    fun setDuration(durationMs: Long) {
        if (isHeld) return
        _duration.value = durationMs
    }

    fun updateDurationFromController() {
        if (isHeld) return
        val dur = controller.duration
        if (dur > 0L) {
            _duration.value = dur
        }
    }

    fun resetTo(startPosition: Long, preserveDuration: Boolean = false) {
        if (isHeld) return
        clock.reset(startPosition)
        _currentPosition.value = startPosition
        if (!preserveDuration) {
            _duration.value = 0L
        }
    }

    fun start(isPlaying: () -> Boolean = { controller.isPlaying }, onTick: suspend (positionMs: Long) -> Unit) {
        onTickCallback = onTick
        updatePlayingState(controller.isPlaying)
    }

    fun updatePlayingState(isPlaying: Boolean) {
        progressJob?.cancel()
        progressJob = null
        if (!isHeld) {
            val curPos = controller.currentPosition
            clock.reset(curPos)
            _currentPosition.value = curPos
        }

        if (isPlaying && controller.playbackState == Player.STATE_READY) {
            progressJob = scope.launch {
                while (isActive && controller.isPlaying && controller.playbackState == Player.STATE_READY) {
                    if (!isHeld) {
                        val rawPos = controller.currentPosition
                        val speed = controller.playbackSpeed
                        val smoothPos = clock.update(
                            sourcePositionMs = rawPos,
                            speed = speed,
                            isAdvancing = true,
                            durationMs = _duration.value
                        )
                        _currentPosition.value = smoothPos
                        onTickCallback?.invoke(smoothPos)
                    } else {
                        _currentPosition.value = heldPosition
                        if (heldDuration > 0L) _duration.value = heldDuration
                    }
                    delay(_updateInterval.value.coerceIn(16L, 50L))
                }
            }
        }
    }
}
