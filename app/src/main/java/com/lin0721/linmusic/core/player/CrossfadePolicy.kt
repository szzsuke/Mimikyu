package com.lin0721.linmusic.core.player

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

object CrossfadePolicy {

    // MediaItem extras for crossfade and Automix
    const val EXTRA_CROSSFADE_MS = "crossfadeMs"
    const val EXTRA_CROSSFADE_START_MS = "crossfadeStartMs"
    const val EXTRA_BASS_SWAP = "bassSwap"
    const val EXTRA_TRANSITION_STYLE = "transitionStyle"

    const val MIN_DURATION_MS = 500
    const val MAX_DURATION_MS = 12_000
    const val DURATION_STEP_MS = 500
    const val DEFAULT_DURATION_MS = 3000

    private const val BASE_PREFETCH_WINDOW_MS = 8000L

    // Next
    const val ANALYSIS_WINDOW_MS = 45_000L

    fun normalizeDurationMs(durationMs: Int): Int {
        val clamped = durationMs.coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)
        return (clamped + DURATION_STEP_MS / 2) / DURATION_STEP_MS * DURATION_STEP_MS
    }

    // ï¼š of ï¼Œ
    fun autoFadeMs(durationMs: Int, effectiveDurationMs: Long): Long {
        if (effectiveDurationMs <= 0L) return 0L
        return minOf(normalizeDurationMs(durationMs).toLong(), effectiveDurationMs / 2)
    }

    // ï¼Œ
    fun triggerPositionMs(effectiveEndMs: Long, fadeMs: Long): Long =
        (effectiveEndMs - fadeMs).coerceAtLeast(0L)

    // ï¼›ã€ã€Play
    fun fadeMsFor(
        autoTransition: Boolean,
        enabled: Boolean,
        isPlaying: Boolean,
        startPositionMs: Long,
        autoFadeMs: Long
    ): Long {
        if (!autoTransition || !enabled || !isPlaying || startPositionMs > 0L) return 0L
        return autoFadeMs.coerceAtLeast(0L)
    }

    fun prefetchWindowMs(enabled: Boolean): Long =
        if (enabled) ANALYSIS_WINDOW_MS else BASE_PREFETCH_WINDOW_MS

    // Constant-power sinusoidal fade-out with optional midpoint bass swap
    fun fadeOutGain(progress: Float, startGain: Float, bassSwap: Boolean = false): Float {
        val p = progress.coerceIn(0f, 1f)
        val base = startGain * cos(p * PI / 2).toFloat()
        if (!bassSwap || p <= 0.55f) return base
        // Midpoint bass handover: steepen outgoing tail so incoming kick & bassline punch through cleanly
        val swapProgress = ((p - 0.55f) / 0.45f).coerceIn(0f, 1f)
        val ducking = (1f - swapProgress * 0.35f)
        return (base * ducking).coerceAtLeast(0f)
    }

    fun fadeInGain(progress: Float): Float =
        sin(progress.coerceIn(0f, 1f) * PI / 2).toFloat()
}
