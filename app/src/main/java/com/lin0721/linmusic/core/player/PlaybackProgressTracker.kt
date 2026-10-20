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

    fun setUpdateInterval(intervalMs: Long) {
        _updateInterval.value = intervalMs.coerceIn(16L, 50L)
    }

    fun syncImmediatePosition() {
        val curPos = controller.currentPosition
        clock.reset(curPos)
        _currentPosition.value = curPos
    }

    fun setPosition(positionMs: Long) {
        clock.reset(positionMs)
        _currentPosition.value = positionMs
    }

    fun setDuration(durationMs: Long) {
        _duration.value = durationMs
    }

    fun updateDurationFromController() {
        val dur = controller.duration
        if (dur > 0L) {
            _duration.value = dur
        }
    }

    fun resetTo(startPosition: Long, preserveDuration: Boolean = false) {
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
        val curPos = controller.currentPosition
        clock.reset(curPos)
        _currentPosition.value = curPos

        if (isPlaying && controller.playbackState == Player.STATE_READY) {
            progressJob = scope.launch {
                while (isActive && controller.isPlaying && controller.playbackState == Player.STATE_READY) {
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
                    delay(_updateInterval.value.coerceIn(16L, 50L))
                }
            }
        }
    }
}
