package com.lin0721.linmusic.core.player

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToLong

/**
 * Phase-Locked Loop (PLL) playback clock for jitter-free synchronized lyrics.
 *
 * ExoPlayer's underlying playback position updates in coarse, irregular intervals
 * (typically 250ms–500ms jumps), which causes lyrics animations to stutter or skip
 * lines when relying on raw player ticks.
 *
 * This clock projects playback along high-precision monotonic time (System.nanoTime())
 * at the current playback speed, and smoothly absorbs phase error using an exponential
 * filter when player samples arrive.
 */
class LyricsPlaybackClock {
    private var previousSampleNanos = Long.MIN_VALUE
    private var previousSourcePositionMs = 0L
    private var previousSpeed = 1.0f
    private var wasAdvancing = false
    private var positionMs = 0.0

    @Synchronized
    fun reset(positionMs: Long): Long {
        val position = positionMs.coerceAtLeast(0L)
        this.positionMs = position.toDouble()
        previousSampleNanos = Long.MIN_VALUE
        previousSourcePositionMs = position
        wasAdvancing = false
        return position
    }

    @Synchronized
    fun update(
        sourcePositionMs: Long,
        sampleTimeNanos: Long = System.nanoTime(),
        speed: Float = 1.0f,
        isAdvancing: Boolean = true,
        durationMs: Long = 0L
    ): Long {
        val source = sourcePositionMs.coerceAtLeast(0L)
        val previousTime = previousSampleNanos
        val advancing = isAdvancing && speed.isFinite() && speed > 0f

        if (
            previousTime == Long.MIN_VALUE || sampleTimeNanos <= previousTime ||
            !advancing || !wasAdvancing || speed != previousSpeed ||
            source < previousSourcePositionMs || abs(source - previousSourcePositionMs) > 2000L
        ) {
            // Hard seek, rewind, or large position discontinuity: snap immediately
            positionMs = source.toDouble()
        } else {
            val elapsedMs = (sampleTimeNanos - previousTime) / NANOS_PER_MILLISECOND
            val projectedPosition = positionMs + elapsedMs * speed
            positionMs = if (source != previousSourcePositionMs) {
                val errorMs = source - projectedPosition
                if (abs(errorMs) > MAX_PHASE_ERROR_MS) {
                    source.toDouble()
                } else {
                    val correction = errorMs * (1.0 - exp(-elapsedMs / CORRECTION_TIME_MS))
                    (projectedPosition + correction).coerceAtLeast(positionMs)
                }
            } else {
                projectedPosition
            }
        }

        val maximumPositionMs = if (durationMs > 0L) durationMs.toDouble() else Long.MAX_VALUE.toDouble()
        positionMs = positionMs.coerceIn(0.0, maximumPositionMs)
        previousSampleNanos = sampleTimeNanos
        previousSourcePositionMs = source
        previousSpeed = speed
        wasAdvancing = advancing
        return positionMs.roundToLong()
    }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000.0
        const val MAX_PHASE_ERROR_MS = 60.0
        const val CORRECTION_TIME_MS = 80.0
    }
}
