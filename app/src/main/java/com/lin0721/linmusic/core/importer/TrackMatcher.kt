package com.lin0721.linmusic.core.importer

import com.lin0721.linmusic.core.log.AppLogger
import com.metrolist.innertube.models.SongItem
import kotlin.math.abs

data class LayaChoiceResult<T>(
    val selected: T?,
    val confidence: Float,
    val scores: Map<String, Float> = emptyMap(),
    val rationale: String = ""
)

data class LayaCoverDecision(
    val selectedUrl: String,
    val source: String, // "youtube", "itunes", "matched"
    val confidence: Float,
    val matched: Boolean,
    val rationale: String
)

/**
 * TrackMatcher provides fast, calibrated candidate selection and cover art judgment
 * for playlist import and track playback resolution.
 */
object TrackMatcher {
    private const val TAG = "TrackMatcher"

    /**
     * Selects the single best matching song candidate from a search query.
     * Evaluates title semantics, artist alignment, studio release tier (ATV vs UGC),
     * duration delta congruence, and version consistency.
     */
    fun selectBestSong(target: ParsedTrackInfo, candidates: List<SongItem>): LayaChoiceResult<SongItem> {
        if (candidates.isEmpty()) {
            return LayaChoiceResult(null, 0f, emptyMap(), "No candidates available")
        }

        val targetTitle = target.title.lowercase().trim()
        val targetArtist = target.artist.lowercase().trim()
        val primaryTargetArtist = targetArtist.substringBefore(',').substringBefore('&').substringBefore('/').trim()
        val targetDurationSec = (target.durationMs / 1000).toInt()

        val candidateScores = mutableMapOf<SongItem, Float>()
        val scoreBreakdowns = mutableMapOf<String, Float>()
        val candidateTitleScores = mutableMapOf<SongItem, Float>()

        for (candidate in candidates) {
            var score = 0f
            var titleScore = 0f
            val candTitle = candidate.title.lowercase().trim()
            val candArtist = candidate.artists.joinToString(" ") { it.name }.lowercase().trim()
            val candDuration = candidate.duration ?: 0

            // 1. Title Congruence
            if (candTitle == targetTitle) {
                titleScore += 50f
            } else if (candTitle.replace(Regex("[^a-z0-9]"), "") == targetTitle.replace(Regex("[^a-z0-9]"), "")) {
                titleScore += 42f
            } else if (candTitle.contains(targetTitle) || targetTitle.contains(candTitle)) {
                titleScore += 26f
            } else {
                val targetWords = targetTitle.split(Regex("[\\s,._\\-]+")).filter { it.length > 2 }.toSet()
                val candWords = candTitle.split(Regex("[\\s,._\\-]+")).filter { it.length > 2 }.toSet()
                if (targetWords.isNotEmpty() && candWords.isNotEmpty()) {
                    val overlap = targetWords.intersect(candWords).size
                    titleScore += (overlap.toFloat() / targetWords.size.toFloat() * 22f).coerceAtMost(22f)
                }
            }
            score += titleScore
            candidateTitleScores[candidate] = titleScore

            // 2. Artist Congruence
            if (primaryTargetArtist.isNotBlank()) {
                if (candArtist.contains(primaryTargetArtist) || primaryTargetArtist.contains(candArtist)) {
                    score += 35f
                } else {
                    val targetTokens = targetArtist.split(Regex("[\\s,._\\-]+")).filter { it.length > 2 }.toSet()
                    val candTokens = candArtist.split(Regex("[\\s,._\\-]+")).toSet()
                    if (targetTokens.isNotEmpty() && candTokens.isNotEmpty()) {
                        val overlap = targetTokens.intersect(candTokens).size
                        score += (overlap.toFloat() / targetTokens.size.toFloat() * 20f).coerceAtMost(20f)
                    } else {
                        score -= 15f // Artist mismatch penalty
                    }
                }
            }

            // 3. Audio Release Tier (ATV vs UGC)
            if (candidate.musicVideoType == "MUSIC_VIDEO_TYPE_ATV") score += 20f
            if (candidate.album != null && !candidate.album!!.name.isNullOrBlank()) score += 15f
            if (candidate.musicVideoType == "MUSIC_VIDEO_TYPE_UGC") score -= 30f

            // 4. Duration Delta Congruence
            if (targetDurationSec > 0 && candDuration > 0) {
                val delta = abs(targetDurationSec - candDuration)
                when {
                    delta <= 3 -> score += 25f // Exact duration match
                    delta <= 8 -> score += 15f // Close duration match
                    delta <= 18 -> score += 5f
                    delta > 60 -> score -= 35f // Suspicious length mismatch
                    delta > 30 -> score -= 15f
                }
            }

            // 5. Version / Remix Consistency
            val targetIsLive = targetTitle.contains("live")
            val targetIsCover = targetTitle.contains("cover")
            val targetIsRemix = targetTitle.contains("remix")
            val targetIsKaraoke = targetTitle.contains("karaoke")

            val candIsLive = candTitle.contains("live")
            val candIsCover = candTitle.contains("cover")
            val candIsRemix = candTitle.contains("remix")
            val candIsKaraoke = candTitle.contains("karaoke")

            if (!targetIsLive && candIsLive) score -= 25f
            if (!targetIsCover && candIsCover) score -= 35f
            if (!targetIsRemix && candIsRemix) score -= 20f
            if (!targetIsKaraoke && candIsKaraoke) score -= 35f

            if (targetIsRemix && candIsRemix) score += 15f
            if (targetIsLive && candIsLive) score += 15f

            candidateScores[candidate] = score
            scoreBreakdowns["${candidate.id}: ${candidate.title}"] = score
        }

        val bestCandidate = candidateScores.maxByOrNull { it.value }
        val maxScore = bestCandidate?.value ?: 0f
        val confidence = (maxScore / 100f).coerceIn(0.1f, 0.99f)
        val isViableMatch = bestCandidate != null && maxScore >= 35f && (candidateTitleScores[bestCandidate.key] ?: 0f) >= 15f
        val rationale = "Match choice: '${bestCandidate?.key?.title}' score=$maxScore titleScore=${candidateTitleScores[bestCandidate?.key]} (confidence=${(confidence * 100).toInt()}%, viable=$isViableMatch)"

        AppLogger.d(TAG, rationale)
        return LayaChoiceResult(
            selected = if (isViableMatch) bestCandidate?.key else null,
            confidence = confidence,
            scores = scoreBreakdowns,
            rationale = rationale
        )
    }

    /**
     * Decision for Album Cover Art:
     * 1. Checks if iTunes cover exists -> prioritizes authentic studio album art.
     * 2. Falls back to YouTube cover.
     */
    fun chooseBestCover(
        title: String,
        artist: String,
        ytCoverUrl: String?,
        itunesCoverUrl: String?
    ): LayaCoverDecision {
        val ytClean = ytCoverUrl?.trim().orEmpty()
        val itunesClean = itunesCoverUrl?.trim().orEmpty()

        if (ytClean.isBlank() && itunesClean.isBlank()) {
            return LayaCoverDecision("", "none", 0f, false, "No cover candidates available")
        }
        if (itunesClean.isNotBlank()) {
            return LayaCoverDecision(
                selectedUrl = itunesClean,
                source = "itunes",
                confidence = 0.98f,
                matched = true,
                rationale = "Authentic studio album cover from iTunes"
            )
        }

        return LayaCoverDecision(ytClean, "youtube", 0.7f, false, "Only YouTube cover available")
    }

    fun isMosaicCover(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("fcrop64") ||
                lower.contains("-c-") && lower.contains("w60") ||
                lower.contains("default_sq") ||
                lower.contains("tile") ||
                lower.contains("collage")
    }

    fun isVideoThumbnail(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("mqdefault") ||
                lower.contains("sddefault") ||
                (lower.contains("hqdefault") && !lower.contains("hq720"))
    }
}
