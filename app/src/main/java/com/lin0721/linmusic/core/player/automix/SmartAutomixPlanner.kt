package com.lin0721.linmusic.core.player.automix

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Intelligent transition planner ported and adapted from BitChord / Orchard / Spotify Automix.
 *
 * Rules:
 * 1. GAPLESS: Consecutive album tracks hand off instantly (150ms) without muddling continuous movements.
 * 2. DJ_BLEND: Harmonically compatible tempi (aligned via octave folding within 15%) lock to
 *    musical phrases (16 beats / 4 bars) with an equal-power curve and midpoint bass swap at 55%.
 * 3. DJ_FILTER: Differing tempi blend smoothly with frequency roll-off over 8 to 12 beats.
 * 4. SMART OUTRO ANCHORING: The transition begins at the detected musical outro start (outroStartMs)
 *    rather than a fixed offset from the file end, ensuring track transitions blend musical energy
 *    seamlessly without sudden silence or arbitrary cuts.
 */
object SmartAutomixPlanner {

    private const val MIN_FADE_MS = 5000L
    private const val MAX_FADE_MS = 14000L
    private const val DEFAULT_FADE_MS = 8000L

    /**
     * Halves or doubles [incomingBpm] until it is as close as possible to [outgoingBpm],
     * matching BitChord's octave alignment (e.g. 70 BPM vs 140 BPM, 85 BPM vs 170 BPM).
     */
    fun alignTempoOctave(outgoingBpm: Double, incomingBpm: Double): Double {
        if (outgoingBpm <= 0 || incomingBpm <= 0) return incomingBpm
        var aligned = incomingBpm
        while (aligned / outgoingBpm > 1.5) aligned /= 2.0
        while (aligned / outgoingBpm < 0.67) aligned *= 2.0
        return aligned
    }

    fun planTransition(
        currentAnalysis: TrackAnalysis,
        nextAnalysis: TrackAnalysis,
        isSameAlbumSequential: Boolean = false,
        configuredFadeMs: Long = 3000L,
        automixEnabled: Boolean = true
    ): TransitionPlan {
        val currDuration = currentAnalysis.durationMs
        if (currDuration <= 0L) {
            return TransitionPlan(
                shouldStart = false,
                fadeDurationMs = configuredFadeMs,
                transitionStyle = TransitionStyle.EQUAL_POWER,
                reason = "invalid-duration"
            )
        }

        val contentEnd = if (currentAnalysis.contentEndMs > 0L) {
            currentAnalysis.contentEndMs.coerceAtMost(currDuration)
        } else {
            currDuration
        }
        val incomingCueMs = nextAnalysis.audibleStartMs.coerceAtLeast(0L)

        // 1. Same-album sequential: Gapless playback
        if (isSameAlbumSequential) {
            return TransitionPlan(
                shouldStart = true,
                transitionStartMs = (contentEnd - 150L).coerceAtLeast(0L),
                fadeDurationMs = 150L,
                transitionStyle = TransitionStyle.GAPLESS,
                incomingCueMs = incomingCueMs,
                reason = "album-gapless"
            )
        }

        // 2. Standard manual crossfade if Automix is disabled
        if (!automixEnabled) {
            val fade = configuredFadeMs.coerceIn(500L, MAX_FADE_MS)
            val startMs = (contentEnd - fade).coerceAtLeast(0L)
            return TransitionPlan(
                shouldStart = true,
                transitionStartMs = startMs,
                fadeDurationMs = fade,
                transitionStyle = TransitionStyle.EQUAL_POWER,
                incomingCueMs = incomingCueMs,
                bassSwapFraction = 0.50f,
                reason = "standard-crossfade"
            )
        }

        // 3. Automix (Smart DJ Mode)
        val currBpm = currentAnalysis.bpm.takeIf { it > 0.0 } ?: 120.0
        val nextBpm = nextAnalysis.bpm.takeIf { it > 0.0 } ?: 120.0

        // BitChord Octave-aligned tempo match
        val alignedNextBpm = alignTempoOctave(currBpm, nextBpm)
        val tempoDiffRatio = abs(currBpm - alignedNextBpm) / currBpm
        val isTempoCompatible = tempoDiffRatio <= 0.15

        val beatIntervalMs = currentAnalysis.beatIntervalMs.takeIf { it > 0L }
            ?: (60_000.0 / currBpm).toLong().coerceIn(300L, 1000L)

        // Musical overlap: 16 beats (4 bars) for DJ_BLEND, 10-12 beats for DJ_FILTER
        val desiredBeats = if (isTempoCompatible) 16 else 10
        val phraseFadeMs = (desiredBeats * beatIntervalMs).coerceIn(MIN_FADE_MS, MAX_FADE_MS)

        // Outro anchor: if outro was detected within a sensible window before contentEnd
        val outroStart = currentAnalysis.outroStartMs
        val (transitionStartMs, actualFadeMs) = if (outroStart in (contentEnd - 24_000L) until (contentEnd - MIN_FADE_MS)) {
            // Track has a natural musical outro/breakdown: start mix right where the outro begins and blend to contentEnd
            val fade = (contentEnd - outroStart).coerceIn(MIN_FADE_MS, MAX_FADE_MS)
            (contentEnd - fade).coerceAtLeast(0L) to fade
        } else {
            // Standard musical phrase anchor: end mix at contentEnd, start phraseFadeMs earlier
            val start = (contentEnd - phraseFadeMs).coerceAtLeast(0L)
            start to phraseFadeMs
        }

        return if (isTempoCompatible) {
            // DJ_BLEND: 16-beat musical phrase blend with 55% midpoint bass swap
            TransitionPlan(
                shouldStart = true,
                transitionStartMs = transitionStartMs,
                fadeDurationMs = actualFadeMs,
                transitionStyle = TransitionStyle.DJ_BLEND,
                incomingCueMs = incomingCueMs,
                bassSwapFraction = 0.55f,
                outgoingBpm = currBpm,
                incomingBpm = nextBpm,
                phraseBeats = 16,
                reason = "smart-dj-blend"
            )
        } else {
            // DJ_FILTER: Differing tempi - energy roll-off with 50% midpoint transition
            TransitionPlan(
                shouldStart = true,
                transitionStartMs = transitionStartMs,
                fadeDurationMs = actualFadeMs,
                transitionStyle = TransitionStyle.DJ_FILTER,
                incomingCueMs = incomingCueMs,
                bassSwapFraction = 0.50f,
                outgoingBpm = currBpm,
                incomingBpm = nextBpm,
                phraseBeats = 10,
                reason = "smart-dj-filter"
            )
        }
    }
}
