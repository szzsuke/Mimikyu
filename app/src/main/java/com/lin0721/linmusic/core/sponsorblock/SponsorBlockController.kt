package com.lin0721.linmusic.core.sponsorblock

import com.lin0721.linmusic.core.log.AppLogger
import com.lin0721.linmusic.core.player.innertube.YouTubeIdRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private const val TAG = "SponsorBlockController"

/**
 * Monitors playback progress against SponsorBlock non-music segments (intros/outros/interludes).
 * Automatically seeks past skipped segments and shows a user notification.
 */
class SponsorBlockController(
    private val scope: CoroutineScope,
    private val sponsorBlockClient: SponsorBlockClient,
    private val isEnabled: () -> Boolean = { true },
    private val currentPositionProvider: () -> Long = { 0L },
    private val onSeekRequested: (positionMs: Long) -> Unit,
    private val onSkippedNotice: (message: String) -> Unit
) {
    private var currentVideoId: String? = null
    private var currentSongId: Long = -1L
    private var currentSegments: List<SponsorSegment> = emptyList()
    private val skippedSegmentKeys = mutableSetOf<String>()
    private var fetchJob: Job? = null

    fun onTrackChanged(songId: Long, explicitVideoId: String? = null, initialPosMs: Long = 0L) {
        val rawVid = explicitVideoId?.takeIf { it.isNotBlank() } ?: (if (songId > 0L) YouTubeIdRegistry.getVideoId(songId) else null)
        val cleanVid = YouTubeIdRegistry.sanitizeVideoId(rawVid)

        if (cleanVid.isBlank()) {
            AppLogger.d(TAG, "SponsorBlock: invalid or missing videoId '$cleanVid' for songId=$songId")
            return
        }

        if (currentVideoId == cleanVid && currentSegments.isNotEmpty()) {
            return
        }

        fetchJob?.cancel()
        currentSongId = songId
        currentVideoId = cleanVid
        skippedSegmentKeys.clear()
        currentSegments = emptyList()

        fetchJob = scope.launch {
            val segments = sponsorBlockClient.getSkipSegments(cleanVid)
            if (currentVideoId == cleanVid) {
                currentSegments = segments
                AppLogger.d(TAG, "Active SponsorBlock segments for $cleanVid: ${segments.size}")
                val currentPos = currentPositionProvider().takeIf { it > 0L } ?: initialPosMs
                checkAndSkip(currentPos)
            }
        }
    }

    // Overload for backward compatibility
    fun onTrackChanged(songId: Long, initialPosMs: Long) {
        onTrackChanged(songId, null, initialPosMs)
    }

    fun onProgressTick(currentPosMs: Long) {
        checkAndSkip(currentPosMs)
    }

    private fun checkAndSkip(positionMs: Long) {
        if (!isEnabled()) return
        if (currentSegments.isEmpty()) return

        for (segment in currentSegments) {
            val key = "${segment.startMs}_${segment.endMs}"
            if (skippedSegmentKeys.contains(key)) continue

            // If current playback is inside the segment (or entering within 300ms)
            val isInSegment = positionMs >= (segment.startMs - 300L) && positionMs < (segment.endMs - 150L)
            if (isInSegment) {
                skippedSegmentKeys.add(key)
                AppLogger.i(TAG, "Auto-skipping SponsorBlock segment [${segment.startMs}ms - ${segment.endMs}ms] at pos ${positionMs}ms")
                onSeekRequested(segment.endMs)

                val notice = if (segment.startMs <= 3000L) {
                    "Skipped non-music intro"
                } else if (segment.category == "sponsor") {
                    "Skipped sponsor segment"
                } else {
                    "Skipped non-music section"
                }
                onSkippedNotice(notice)
                break
            }
        }
    }

    fun reset() {
        fetchJob?.cancel()
        currentSongId = -1L
        currentVideoId = null
        currentSegments = emptyList()
        skippedSegmentKeys.clear()
    }
}
