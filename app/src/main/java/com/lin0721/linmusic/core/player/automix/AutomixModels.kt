package com.lin0721.linmusic.core.player.automix

/**
 * Automix crossfade mode.
 * - SMART: Apple Music / Spotify / BitChord style Automix with tempo/phrase alignment, silence trimming, and bass swap.
 * - STANDARD: Classic manual crossfade duration.
 */
enum class CrossfadeMode {
    STANDARD,
    SMART
}

/**
 * Transition style for a planned crossfade.
 */
enum class TransitionStyle {
    /** Constant-power sinusoidal fade (equal loudness). */
    EQUAL_POWER,

    /** Consecutive tracks on the same album: instant, seamless gapless handoff. */
    GAPLESS,

    /** Beat-aligned blend on 4/8-bar phrase with midpoint bass swap for matching/compatible tempi. */
    DJ_BLEND,

    /** Filtered handoff with high/low-pass roll-off for tracks with divergent tempi. */
    DJ_FILTER
}

/**
 * Concrete transition plan produced for a pair of tracks.
 */
data class TransitionPlan(
    val shouldStart: Boolean = false,
    val transitionStartMs: Long = 0L,
    val fadeDurationMs: Long = 6000L,
    val transitionStyle: TransitionStyle = TransitionStyle.DJ_BLEND,
    /** Exact millisecond where incoming track playback should be cued (skips pre-roll silence). */
    val incomingCueMs: Long = 0L,
    /** Point during the crossfade (0.0 to 1.0) where the bass swap occurs (e.g. 0.60). */
    val bassSwapFraction: Float = 0.60f,
    val outgoingBpm: Double = 120.0,
    val incomingBpm: Double = 120.0,
    val phraseBeats: Int = 16,
    val reason: String = ""
)

/**
 * Lightweight acoustic and structural edge analysis for a track.
 */
data class TrackAnalysis(
    val trackId: String = "",
    val durationMs: Long = 0L,
    val bpm: Double = 120.0,
    val beatIntervalMs: Long = 500L,
    /** First audible transient in milliseconds (trims pre-roll silence). */
    val audibleStartMs: Long = 0L,
    /** End of musical content in milliseconds (trims trailing outro silence). */
    val contentEndMs: Long = 0L,
    /** Approximate intro duration in milliseconds. */
    val introDurationMs: Long = 0L,
    /** Timestamp where outro begins. */
    val outroStartMs: Long = 0L,
    val isAnalyzed: Boolean = false
)

