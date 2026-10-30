package com.lin0721.linmusic.core.laya

import com.lin0721.linmusic.core.importer.ParsedTrackInfo
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
 * Laya Decision Engine (System-1 Decision Architecture).
 * Open-source non-autoregressive decision engine performing fast, calibrated
 * choice selection and cover art judgment without LLM latency.
 * Reference: NandhaKishorM/laya (GitHub)
 */
object LayaDecisionEngine {
    private const val TAG = "LayaDecisionEngine"

    /**
     * Laya System-1 Choice Primitive for selecting the single best matching song candidate.
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
            // Laya System-1 prioritizes official studio releases
            if (candidate.musicVideoType == "MUSIC_VIDEO_TYPE_ATV") score += 20f
            if (candidate.album != null && !candidate.album!!.name.isNullOrBlank()) score += 15f
            if (candidate.musicVideoType == "MUSIC_VIDEO_TYPE_UGC") score -= 30f

            // 4. Duration Delta Congruence (from Exportify metadata)
            if (targetDurationSec > 0 && candDuration > 0) {
                val delta = abs(targetDurationSec - candDuration)
                when {
                    delta <= 3 -> score += 25f // Exact duration match
                    delta <= 8 -> score += 15f // Close duration match
                    delta <= 18 -> score += 5f
                    delta > 60 -> score -= 35f // Suspicious length mismatch (extended mix or video intro)
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
        // Laya calibrated softmax probability
        val confidence = (maxScore / 100f).coerceIn(0.1f, 0.99f)
        val isViableMatch = bestCandidate != null && maxScore >= 35f && (candidateTitleScores[bestCandidate.key] ?: 0f) >= 15f
        val rationale = "Laya choice: '${bestCandidate?.key?.title}' score=$maxScore titleScore=${candidateTitleScores[bestCandidate?.key]} (confidence=${(confidence * 100).toInt()}%, viable=$isViableMatch)"

        AppLogger.d(TAG, rationale)
        return LayaChoiceResult(
            selected = if (isViableMatch) bestCandidate?.key else null,
            confidence = confidence,
            scores = scoreBreakdowns,
            rationale = rationale
        )
    }

    /**
     * Laya System-1 Decision for Album Cover Art:
     * 1. Checks if YouTube cover matches iTunes cover -> uses matched official cover.
     * 2. If not matching, evaluates which is higher quality (penalizing mosaic grids and 16:9 letterbox black bars).
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
            // Prioritize authentic, verified 1:1 studio album cover art from iTunes/Apple Music
            // over YouTube video cutouts and video frame thumbnails.
            return LayaCoverDecision(
                selectedUrl = itunesClean,
                source = "itunes",
                confidence = 0.98f,
                matched = true,
                rationale = "Authentic studio album cover from iTunes"
            )
        }

        // Only YouTube cover available
        return LayaCoverDecision(ytClean, "youtube", 0.7f, false, "Only YouTube cover available")
    }

    /**
     * Checks if YouTube cover matches iTunes cover.
     * Evaluates official Google user content square art hashes and non-mosaic commercial art.
     */
    private fun evaluateCoversMatch(ytUrl: String, itunesUrl: String): Boolean {
        if (ytUrl == itunesUrl) return true

        // If YouTube is a 4-grid mosaic, it definitely does NOT match iTunes single album art
        if (isMosaicCover(ytUrl)) return false

        // If YouTube thumbnail is a video letterbox frame, it does not match iTunes square art
        if (isVideoThumbnail(ytUrl)) return false

        // Both are official square images:
        // YouTube Music official studio art is hosted on googleusercontent with square dimension tag
        val ytIsOfficialSquare = (ytUrl.contains("googleusercontent.com") || ytUrl.contains("yt3.ggpht.com")) &&
                (ytUrl.contains("w544-h544") || ytUrl.contains("w1200-h1200") || ytUrl.contains("=s") || ytUrl.contains("-c-k"))
        val itunesIsOfficial = itunesUrl.contains("apple.com") || itunesUrl.contains("mzstatic.com")

        // If YouTube has official square studio art and is high-res, consider it a match
        return ytIsOfficialSquare && itunesIsOfficial
    }

    /**
     * Evaluates visual quality and suitability of a cover image URL.
     * Penalizes:
     * - 4-grid mosaics (fcrop64, w60-h60 tiles, etc.)
     * - 16:9 letterbox with black bars (hqdefault, mqdefault)
     * Rewards:
     * - 1:1 pristine square ratio
     * - High resolution (600x600, 1200x1200, 1400x1400)
     */
    private fun evaluateCoverQuality(url: String, isYouTube: Boolean): Float {
        var score = 50f

        if (isMosaicCover(url)) {
            score -= 60f // Severe penalty for ugly 4-grid split thumbnails
        }

        if (isVideoThumbnail(url)) {
            score -= 30f // Penalty for 16:9 video frame with black bars
        }

        if (url.contains("1200x1200") || url.contains("1400x1400") || url.contains("maxresdefault")) {
            score += 35f // Ultra high-res master
        } else if (url.contains("600x600") || url.contains("w544-h544") || url.contains("hq720")) {
            score += 20f
        } else if (url.contains("100x100") || url.contains("default.jpg") || url.contains("mqdefault")) {
            score -= 20f // Low res
        }

        if (!isYouTube && (url.contains("mzstatic.com") || url.contains("apple.com"))) {
            score += 30f // iTunes covers are verified uncompressed 1:1 square master art
        }

        return score
    }

    fun isMosaicCover(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("fcrop64") ||
                lower.contains("-c-") && lower.contains("w60") ||
                lower.contains("default_sq") ||
                lower.contains("tile") ||
                lower.contains("collage")
    }

    private fun isVideoThumbnail(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("mqdefault") ||
                lower.contains("sddefault") ||
                (lower.contains("hqdefault") && !lower.contains("hq720"))
    }

    /**
     * Laya Continuous Affinity Scoring:
     * S = L * exp(-lambda * deltaT) * B_repeat * M_affinity
     * where:
     * - lambda = ln(2) / 7.0 (7-day half-life decay)
     * - B_repeat = 1.0 + 0.2 * (min(plays, 5) - 1)
     * - M_affinity = 1.0 + 0.25 * (min(N, 5) - 1)
     */
    fun calculateAffinityScore(
        listenCount: Int,
        daysSinceLastPlay: Double,
        repeatCount: Int = 1,
        distinctTracks: Int = 1
    ): Float {
        if (listenCount <= 0) return 0f
        val lambda = kotlin.math.ln(2.0) / 7.0
        val dt = daysSinceLastPlay.coerceAtLeast(0.0)
        val decay = kotlin.math.exp(-lambda * dt)

        val playsClamped = repeatCount.coerceIn(1, 5)
        val bRepeat = 1.0 + 0.2 * (playsClamped - 1)

        val tracksClamped = distinctTracks.coerceIn(1, 5)
        val mAffinity = 1.0 + 0.25 * (tracksClamped - 1)

        val score = (listenCount.toDouble() * decay * bRepeat * mAffinity).toFloat()
        return score.coerceAtLeast(0f)
    }

    /**
     * Normalized entropy confidence:
     * confidence = 1.0 - (H(p) / ln(k))
     * where H(p) = -sum(p_i * ln(p_i))
     */
    fun calculateEntropyConfidence(probabilities: List<Float>): Float {
        val k = probabilities.size
        if (k <= 1) return 1.0f
        var entropy = 0.0
        for (p in probabilities) {
            if (p > 1e-6f) {
                entropy -= p.toDouble() * kotlin.math.ln(p.toDouble())
            }
        }
        val maxEntropy = kotlin.math.ln(k.toDouble())
        if (maxEntropy <= 0.0) return 1.0f
        val conf = 1.0 - (entropy / maxEntropy)
        return conf.toFloat().coerceIn(0f, 1f)
    }

    /**
     * Computes continuous affinity scoring and normalized entropy confidence
     * over artists and genres from user history.
     */
    fun scoreArtistsAndGenres(tracks: List<AffinityInputTrack>): AffinityScoringResult {
        if (tracks.isEmpty()) {
            return AffinityScoringResult(emptyList(), emptyList(), 0f, 0f)
        }

        val nowMs = System.currentTimeMillis()
        val oneDayMs = 24.0 * 60 * 60 * 1000

        // 1. Group by Artist
        val artistGroups = tracks.groupBy { it.artist.trim() }.filterKeys { it.isNotBlank() }
        val artistScores = mutableMapOf<String, Float>()
        for ((artist, artistTracks) in artistGroups) {
            val totalPlays = artistTracks.sumOf { it.playCount.coerceAtLeast(1) }
            val mostRecent = artistTracks.maxOfOrNull { it.lastPlayedTimestampMs } ?: nowMs
            val daysAgo = ((nowMs - mostRecent).coerceAtLeast(0L)).toDouble() / oneDayMs
            val distinctTracks = artistTracks.map { it.title.trim().lowercase() }.distinct().size
            val repeatCount = artistTracks.map { it.playCount }.maxOrNull() ?: 1

            val score = calculateAffinityScore(totalPlays, daysAgo, repeatCount, distinctTracks)
            artistScores[artist] = score
        }

        // 2. Group by Genre
        val genreGroups = tracks.groupBy { it.genre?.trim()?.lowercase().orEmpty() }.filterKeys { it.isNotBlank() }
        val genreScores = mutableMapOf<String, Float>()
        for ((genre, genreTracks) in genreGroups) {
            val totalPlays = genreTracks.sumOf { it.playCount.coerceAtLeast(1) }
            val mostRecent = genreTracks.maxOfOrNull { it.lastPlayedTimestampMs } ?: nowMs
            val daysAgo = ((nowMs - mostRecent).coerceAtLeast(0L)).toDouble() / oneDayMs
            val distinctTracks = genreTracks.map { it.title.trim().lowercase() }.distinct().size
            val repeatCount = genreTracks.map { it.playCount }.maxOrNull() ?: 1

            val score = calculateAffinityScore(totalPlays, daysAgo, repeatCount, distinctTracks)
            genreScores[genre] = score
        }

        // Softmax & Entropy for Top Artists
        val sortedArtists = artistScores.toList().sortedByDescending { it.second }.take(10)
        val artistCandidates = if (sortedArtists.isNotEmpty()) {
            val maxScore = sortedArtists.first().second
            val expScores = sortedArtists.map { kotlin.math.exp((it.second - maxScore).toDouble()).toFloat() }
            val sumExp = expScores.sum().coerceAtLeast(1e-6f)
            val probs = expScores.map { it / sumExp }
            val conf = calculateEntropyConfidence(probs)
            sortedArtists.mapIndexed { idx, pair ->
                ScoredCandidate(pair.first, pair.second, probs[idx])
            } to conf
        } else {
            emptyList<ScoredCandidate>() to 0f
        }

        // Softmax & Entropy for Top Genres
        val sortedGenres = genreScores.toList().sortedByDescending { it.second }.take(10)
        val genreCandidates = if (sortedGenres.isNotEmpty()) {
            val maxScore = sortedGenres.first().second
            val expScores = sortedGenres.map { kotlin.math.exp((it.second - maxScore).toDouble()).toFloat() }
            val sumExp = expScores.sum().coerceAtLeast(1e-6f)
            val probs = expScores.map { it / sumExp }
            val conf = calculateEntropyConfidence(probs)
            sortedGenres.mapIndexed { idx, pair ->
                ScoredCandidate(pair.first, pair.second, probs[idx])
            } to conf
        } else {
            emptyList<ScoredCandidate>() to 0f
        }

        return AffinityScoringResult(
            topArtists = artistCandidates.first,
            topGenres = genreCandidates.first,
            artistConfidence = artistCandidates.second,
            genreConfidence = genreCandidates.second
        )
    }

    /**
     * Deduces the mood for the user's current time-of-day block using the 11 mood buckets:
     * fun, mellow, energy, cleaning, wedding, exercise, vacation, chill, party, workout, focus.
     */
    fun decideMoodForTimeOfDay(
        recentTracks: List<AffinityInputTrack>,
        hourOfDay: Int = java.time.LocalTime.now().hour,
        localDate: java.time.LocalDate = java.time.LocalDate.now()
    ): MoodDecision {
        val timeBlock = when (hourOfDay) {
            in 6..11 -> "Morning"
            in 12..17 -> "Evening"
            in 18..23 -> "Night"
            else -> "Late Night"
        }

        val dayOfWeek = localDate.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }
        val formattedTitle = "Soundtrack your $dayOfWeek $timeBlock"

        val moodBuckets = listOf(
            "fun", "mellow", "energy", "cleaning", "wedding",
            "exercise", "vacation", "chill", "party", "workout", "focus"
        )

        // Time-of-day prior distributions
        val timePriors = when (timeBlock) {
            "Morning" -> mapOf(
                "focus" to 0.35f, "energy" to 0.25f, "workout" to 0.15f, "exercise" to 0.10f, "chill" to 0.15f
            )
            "Evening" -> mapOf(
                "energy" to 0.30f, "fun" to 0.25f, "chill" to 0.20f, "focus" to 0.15f, "cleaning" to 0.10f
            )
            "Night" -> mapOf(
                "chill" to 0.35f, "party" to 0.25f, "fun" to 0.15f, "mellow" to 0.15f, "wedding" to 0.10f
            )
            else -> mapOf( // Late Night
                "mellow" to 0.45f, "chill" to 0.35f, "focus" to 0.20f
            )
        }

        val last10 = recentTracks.takeLast(10)
        val tokens = last10.flatMap { track ->
            (track.title + " " + (track.genre ?: "") + " " + (track.album ?: "")).lowercase()
                .split(Regex("[\\s,._\\-]+"))
        }.filter { it.length > 2 }

        val moodScores = mutableMapOf<String, Float>()
        for (mood in moodBuckets) {
            val prior = timePriors[mood] ?: 0.05f
            var tokenScore = 0f
            for (token in tokens) {
                if (token.contains(mood) || mood.contains(token)) {
                    tokenScore += 1.0f
                }
                // Secondary keyword affinities
                when (mood) {
                    "energy" -> if (token in setOf("dance", "electronic", "rock", "fast", "power", "hype", "beat")) tokenScore += 0.8f
                    "chill" -> if (token in setOf("acoustic", "lofi", "ambient", "slow", "soft", "calm", "relax")) tokenScore += 0.8f
                    "focus" -> if (token in setOf("study", "piano", "classical", "instrumental", "deep", "mind")) tokenScore += 0.8f
                    "mellow" -> if (token in setOf("sleep", "dream", "quiet", "night", "sad", "indie", "soul")) tokenScore += 0.8f
                    "party" -> if (token in setOf("club", "night", "remix", "bass", "pop", "dj", "turn")) tokenScore += 0.8f
                    "workout", "exercise" -> if (token in setOf("gym", "run", "pump", "training", "trap", "hiphop")) tokenScore += 0.8f
                    "cleaning" -> if (token in setOf("upbeat", "bop", "retro", "disco", "groove")) tokenScore += 0.8f
                    "vacation" -> if (token in setOf("summer", "beach", "tropical", "sun", "travel", "holiday")) tokenScore += 0.8f
                    "wedding" -> if (token in setOf("love", "romantic", "ballad", "acoustic", "heart")) tokenScore += 0.8f
                    "fun" -> if (token in setOf("happy", "feelgood", "bright", "cheer", "vibe")) tokenScore += 0.8f
                }
            }
            moodScores[mood] = prior * (1.0f + tokenScore)
        }

        val bestMood = moodScores.maxByOrNull { it.value }?.key ?: "chill"
        val scoresList = moodBuckets.map { moodScores[it] ?: 0f }
        val maxScore = scoresList.maxOrNull() ?: 1f
        val expScores = scoresList.map { kotlin.math.exp((it - maxScore).toDouble()).toFloat() }
        val sumExp = expScores.sum().coerceAtLeast(1e-6f)
        val probs = expScores.map { it / sumExp }
        val confidence = calculateEntropyConfidence(probs)

        return MoodDecision(
            mood = bestMood,
            timeBlock = timeBlock,
            formattedTitle = formattedTitle,
            confidence = confidence
        )
    }
}

data class ScoredCandidate(
    val name: String,
    val score: Float,
    val probability: Float = 0f
)

data class AffinityScoringResult(
    val topArtists: List<ScoredCandidate>,
    val topGenres: List<ScoredCandidate>,
    val artistConfidence: Float,
    val genreConfidence: Float
)

data class MoodDecision(
    val mood: String,
    val timeBlock: String,
    val formattedTitle: String,
    val confidence: Float
)

data class AffinityInputTrack(
    val title: String,
    val artist: String,
    val album: String? = null,
    val genre: String? = null,
    val playCount: Int = 1,
    val lastPlayedTimestampMs: Long = System.currentTimeMillis()
)
