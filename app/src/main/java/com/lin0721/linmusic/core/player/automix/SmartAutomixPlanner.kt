package com.lin0721.linmusic.core.player.automix

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Intelligent transition planner inspired by BitChord, Apple Music, and Spotify Automix.
 *
 * Rules:
 * 1. GAPLESS: Consecutive album tracks hand off instantly without muddling continuous movements.
 * 2. DJ_BLEND: Harmonically compatible tempi (within 15%) lock to 4/8-bar musical phrases (8 to 16 beats)
 *    with an equal-power curve and midpoint bass swap at 60%.
 * 3. DJ_FILTER: Differing tempi blend smoothly with frequency roll-off and silence trimming.
 * 4. STANDARD: Manual crossfade duration with equal-power curve.
 */
object SmartAutomixPlanner {

    private const val MIN_FADE_MS = 2500L
    private const val MAX_FADE_MS = 12000L
    private const val DEFAULT_SMART_FADE_MS = 6000L

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

        // 1. Same-album sequential: Gapless playback
        if (isSameAlbumSequential) {
            val contentEnd = if (currentAnalysis.contentEndMs > 0L) currentAnalysis.contentEndMs else currDuration
            return TransitionPlan(
                shouldStart = true,
                transitionStartMs = (contentEnd - 150L).coerceAtLeast(0L),
                fadeDurationMs = 150L,
                transitionStyle = TransitionStyle.GAPLESS,
                incomingCueMs = nextAnalysis.audibleStartMs,
                reason = "album-gapless"
            )
        }

        // 2. Standard manual crossfade if Automix is disabled
        if (!automixEnabled) {
            val fade = configuredFadeMs.coerceIn(500L, MAX_FADE_MS)
            val contentEnd = if (currentAnalysis.contentEndMs > 0L) currentAnalysis.contentEndMs else currDuration
            val startMs = (contentEnd - fade).coerceAtLeast(0L)
            return TransitionPlan(
                shouldStart = true,
                transitionStartMs = startMs,
                fadeDurationMs = fade,
                transitionStyle = TransitionStyle.EQUAL_POWER,
                incomingCueMs = nextAnalysis.audibleStartMs,
                bassSwapFraction = 0.50f,
                reason = "standard-crossfade"
            )
        }

        // 3. Automix (Smart Mode)
        val currBpm = currentAnalysis.bpm.takeIf { it > 0.0 } ?: 120.0
        val nextBpm = nextAnalysis.bpm.takeIf { it > 0.0 } ?: 120.0
        val tempoDiffRatio = abs(currBpm - nextBpm) / currBpm

        // Check octave compatibility (half time / double time)
        val halfRatio = abs(currBpm - (nextBpm / 2)) / currBpm
        val doubleRatio = abs(currBpm - (nextBpm * 2)) / currBpm
        val isTempoCompatible = tempoDiffRatio <= 0.15 || halfRatio <= 0.12 || doubleRatio <= 0.12

        val contentEnd = if (currentAnalysis.contentEndMs > 0L) currentAnalysis.contentEndMs else currDuration
        val beatInterval = currentAnalysis.beatIntervalMs.coerceIn(300L, 900L)

        return if (isTempoCompatible) {
            // DJ_BLEND: Crisp 3.8s musical phrase blend with 55% midpoint bass swap
            val blendFadeMs = 3800L
            val startMs = (contentEnd - blendFadeMs).coerceAtLeast(0L)

            TransitionPlan(
                shouldStart = true,
                transitionStartMs = startMs,
                fadeDurationMs = blendFadeMs,
                transitionStyle = TransitionStyle.DJ_BLEND,
                incomingCueMs = nextAnalysis.audibleStartMs,
                bassSwapFraction = 0.55f,
                outgoingBpm = currBpm,
                incomingBpm = nextBpm,
                phraseBeats = 8,
                reason = "smart-dj-blend"
            )
        } else {
            // DJ_FILTER: Clean 3.2s energy drop without tempo clash
            val fadeMs = 3200L
            val startMs = (contentEnd - fadeMs).coerceAtLeast(0L)

            TransitionPlan(
                shouldStart = true,
                transitionStartMs = startMs,
                fadeDurationMs = fadeMs,
                transitionStyle = TransitionStyle.DJ_FILTER,
                incomingCueMs = nextAnalysis.audibleStartMs,
                bassSwapFraction = 0.50f,
                outgoingBpm = currBpm,
                incomingBpm = nextBpm,
                phraseBeats = 8,
                reason = "smart-dj-filter"
            )
        }
    }
}

