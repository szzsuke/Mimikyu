package com.lin0721.linmusic.core.player.automix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartAutomixPlannerTest {

    @Test
    fun `same album sequential tracks trigger gapless style`() {
        val curr = TrackAnalysis(trackId = "1", durationMs = 180_000L, contentEndMs = 179_500L, audibleStartMs = 200L)
        val next = TrackAnalysis(trackId = "2", durationMs = 200_000L, audibleStartMs = 150L)

        val plan = SmartAutomixPlanner.planTransition(
            currentAnalysis = curr,
            nextAnalysis = next,
            isSameAlbumSequential = true
        )

        assertEquals(TransitionStyle.GAPLESS, plan.transitionStyle)
        assertEquals(150L, plan.fadeDurationMs)
        assertEquals(150L, plan.incomingCueMs)
    }

    @Test
    fun `matching BPM triggers DJ_BLEND with phrase alignment and bass swap`() {
        val curr = TrackAnalysis(
            trackId = "1",
            durationMs = 240_000L,
            bpm = 120.0,
            beatIntervalMs = 500L,
            contentEndMs = 238_000L,
            audibleStartMs = 100L
        )
        val next = TrackAnalysis(
            trackId = "2",
            durationMs = 210_000L,
            bpm = 124.0,
            beatIntervalMs = 484L,
            audibleStartMs = 250L
        )

        val plan = SmartAutomixPlanner.planTransition(
            currentAnalysis = curr,
            nextAnalysis = next,
            automixEnabled = true
        )

        assertEquals(TransitionStyle.DJ_BLEND, plan.transitionStyle)
        assertEquals(250L, plan.incomingCueMs)
        assertEquals(0.55f, plan.bassSwapFraction)
        assertEquals(3800L, plan.fadeDurationMs)
        assertEquals(curr.contentEndMs - plan.fadeDurationMs, plan.transitionStartMs)
    }

    @Test
    fun `octave compatible BPM half time triggers DJ_BLEND`() {
        val curr = TrackAnalysis(
            trackId = "1",
            durationMs = 200_000L,
            bpm = 80.0,
            beatIntervalMs = 750L,
            contentEndMs = 198_000L
        )
        val next = TrackAnalysis(
            trackId = "2",
            durationMs = 190_000L,
            bpm = 160.0,
            beatIntervalMs = 375L
        )

        val plan = SmartAutomixPlanner.planTransition(
            currentAnalysis = curr,
            nextAnalysis = next,
            automixEnabled = true
        )

        assertEquals(TransitionStyle.DJ_BLEND, plan.transitionStyle)
    }

    @Test
    fun `divergent BPM triggers DJ_FILTER style`() {
        val curr = TrackAnalysis(
            trackId = "1",
            durationMs = 180_000L,
            bpm = 80.0,
            beatIntervalMs = 750L,
            contentEndMs = 178_000L
        )
        val next = TrackAnalysis(
            trackId = "2",
            durationMs = 190_000L,
            bpm = 115.0,
            beatIntervalMs = 521L
        )

        val plan = SmartAutomixPlanner.planTransition(
            currentAnalysis = curr,
            nextAnalysis = next,
            automixEnabled = true
        )

        assertEquals(TransitionStyle.DJ_FILTER, plan.transitionStyle)
    }

    @Test
    fun `automix disabled respects manual configured crossfade duration`() {
        val curr = TrackAnalysis(trackId = "1", durationMs = 180_000L, contentEndMs = 180_000L)
        val next = TrackAnalysis(trackId = "2", durationMs = 180_000L)

        val plan = SmartAutomixPlanner.planTransition(
            currentAnalysis = curr,
            nextAnalysis = next,
            configuredFadeMs = 5000L,
            automixEnabled = false
        )

        assertEquals(TransitionStyle.EQUAL_POWER, plan.transitionStyle)
        assertEquals(5000L, plan.fadeDurationMs)
        assertEquals(175_000L, plan.transitionStartMs)
    }
}
