package com.lin0721.linmusic.core.lyrics

import android.util.Base64
import com.lin0721.linmusic.core.log.AppLogger
import com.lin0721.linmusic.core.player.domain.LyricLine
import com.lin0721.linmusic.core.player.domain.LyricParser
import com.lin0721.linmusic.core.player.domain.WordInfo
import com.lin0721.linmusic.core.player.innertube.YouTubeIdRegistry
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.json.JSONObject
import android.content.Context
import com.lin0721.linmusic.core.preferences.SettingsPreferences
import kotlinx.coroutines.flow.firstOrNull
import java.io.File
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "ExternalLyricsService"
private const val LRCLIB_BASE = "https://lrclib.net/api"
private val HAN_REGEX = Regex("""[\u4e00-\u9fff]""")

private val SUPABASE_BLACKLIST_VIDEOS = setOf(
    "BBM57gr9fFU", // bargad by sufr (wrong song uploaded to Supabase)
    "VhRJ-3T4Rcw"  // Maharani by Karun/Arpit Bala (delete/bypass Supabase, use high-accuracy external cascade)
)

private fun isSupabaseBlacklisted(videoId: String?, title: String, artist: String): Boolean {
    val cleanVid = YouTubeIdRegistry.sanitizeVideoId(videoId)
    if (cleanVid.isNotEmpty() && SUPABASE_BLACKLIST_VIDEOS.contains(cleanVid)) return true
    val t = title.lowercase()
    val a = artist.lowercase()
    if (t.contains("maharani") && (a.contains("arpit") || a.contains("karun") || a.contains("lambo") || a.isBlank())) return true
    if (t.contains("bargad") && (a.contains("sufr") || a.contains("surf") || a.contains("arpit") || a.isBlank())) return true
    return false
}

/**
 * Multi-source synchronized lyrics engine with automatic fallback cascade.
 *
 * Tier 0: Zero-Delay Local Flash Storage (< 2ms)
 *    - context.cacheDir/lyrics_ttml/{videoId}.ttml (Apple Music word-synced TTML)
 * Tier 1: Cloud TTML Sync (~50 - 150ms)
 *    - Zoho Cloud / Object Storage ({videoId}.ttml)
 *    - BetterLyrics TTML (Apple Music syllable/word-level timestamps)
 * Tier 2: Word-by-Word Tier (syllable-level karaoke):
 *    - Kugou Music KRC (Vast catalog of word-level syllable timestamps with KRC decryption)
 *    - NetEase Cloud Music YRC (Word-level syllable timestamps)
 * Tier 3: High-Accuracy Line-Synced Tier (millisecond precision LRC):
 *    - LRCLIB (Over 20M songs; multi-query cascade: /get exact, /search track+artist, /search q=..., primary artist)
 *    - NetEase Cloud Music LRC (Accurate millisecond timestamps for Asian and global catalogs)
 *    - Kugou Music LRC (Line-synced LRC fallback)
 * Tier 4: In-memory caching for instant replay & UI transitions.
 */
class ExternalLyricsService(
    private val httpClient: HttpClient,
    private val context: Context? = null,
    private val settingsPreferences: SettingsPreferences? = null
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val memoryCache = ConcurrentHashMap<String, List<LyricLine>>()

    fun getCached(
        title: String,
        artist: String,
        durationSec: Int,
        videoId: String? = null
    ): List<LyricLine>? {
        if (title.isBlank()) return null
        val cleanTitle = cleanSearchKeyword(title).ifBlank { title.trim() }
        val cleanArtist = cleanSearchKeyword(artist).ifBlank { artist.trim() }
        val cleanVid = YouTubeIdRegistry.sanitizeVideoId(videoId).takeIf { it.isNotEmpty() }
        val cacheKey = if (cleanVid != null) "vid:$cleanVid::$cleanTitle::$cleanArtist::$durationSec" else "$cleanTitle::$cleanArtist::$durationSec"

        // 1. In-memory cache
        memoryCache[cacheKey]?.takeIf { it.isNotEmpty() }?.let {
            return filterHeaderMetadata(it, title, artist)
        }

        // 2. Local flash storage (< 2ms)
        if (context != null) {
            val file = getDiskCacheFile(cleanVid, cleanTitle, cleanArtist)
            if (file != null && file.exists() && file.length() > 0) {
                val diskLyrics = runCatching {
                    val ttmlContent = file.readText()
                    val parsed = LyricParser.parseTtml(ttmlContent)
                    if (parsed.isNotEmpty()) parsed else null
                }.getOrNull()

                if (diskLyrics != null) {
                    val hasMismatch = hasLanguageMismatch(title, artist, diskLyrics)
                    val lastLine = diskLyrics.lastOrNull { !it.isGap }
                    val isDurationMismatch = if (durationSec > 0 && lastLine != null) {
                        val lyricEndSec = (lastLine.timeMs + lastLine.durationMs) / 1000
                        lyricEndSec > 0 && kotlin.math.abs(lyricEndSec - durationSec) > 35
                    } else false

                    if (hasMismatch || isDurationMismatch) {
                        AppLogger.w(TAG, "Disk cache TTML invalid for '$cleanTitle' (langMismatch=$hasMismatch, durMismatch=$isDurationMismatch), invalidating cache")
                        file.delete()
                    } else {
                        val sanitized = filterHeaderMetadata(diskLyrics, title, artist)
                        memoryCache[cacheKey] = sanitized
                        AppLogger.d(TAG, "Instant lyrics hit from local TTML storage for '$cleanTitle' (< 2ms)")
                        return sanitized
                    }
                }
            }
        }
        return null
    }

    suspend fun fetchLyrics(
        title: String,
        artist: String,
        durationSec: Int,
        videoId: String? = null
    ): List<LyricLine> {
        if (title.isBlank()) return emptyList()

        val cleanTitle = cleanSearchKeyword(title).ifBlank { title.trim() }
        val cleanArtist = cleanSearchKeyword(artist).ifBlank { artist.trim() }
        val cleanVid = YouTubeIdRegistry.sanitizeVideoId(videoId).takeIf { it.isNotEmpty() }
        val cacheKey = if (cleanVid != null) "vid:$cleanVid::$cleanTitle::$cleanArtist::$durationSec" else "$cleanTitle::$cleanArtist::$durationSec"

        // 1. In-memory cache
        memoryCache[cacheKey]?.let { cached ->
            if (cached.isNotEmpty()) {
                AppLogger.d(TAG, "Returning in-memory cached lyrics for '$cleanTitle' by '$cleanArtist'")
                return filterHeaderMetadata(cached, title, artist)
            }
        }

        val isBlacklistedFromSupabase = isSupabaseBlacklisted(cleanVid, cleanTitle, cleanArtist)

        // 2. Local flash storage check (< 2 ms)
        if (context != null) {
            val file = getDiskCacheFile(cleanVid, cleanTitle, cleanArtist)
            if (isBlacklistedFromSupabase && file != null && file.exists()) {
                // If previously cached from Supabase or corrupted, purge so accurate external lyrics are fetched
                file.delete()
            } else if (file != null && file.exists() && file.length() > 0) {
                val diskLyrics = runCatching {
                    val text = file.readText()
                    val parsed = LyricParser.parseTtml(text)
                    if (parsed.isNotEmpty()) parsed else null
                }.getOrNull()

                if (diskLyrics != null) {
                    val hasMismatch = hasLanguageMismatch(title, artist, diskLyrics)
                    val lastLine = diskLyrics.lastOrNull { !it.isGap }
                    val isDurationMismatch = if (durationSec > 0 && lastLine != null) {
                        val lyricEndSec = (lastLine.timeMs + lastLine.durationMs) / 1000
                        lyricEndSec > 0 && kotlin.math.abs(lyricEndSec - durationSec) > 35
                    } else false

                    if (hasMismatch || isDurationMismatch) {
                        AppLogger.w(TAG, "Disk cache TTML invalid for '$cleanTitle' (langMismatch=$hasMismatch, durMismatch=$isDurationMismatch), invalidating cache")
                        file.delete()
                    } else {
                        val processed = if (diskLyrics.any { it.isSynced && !it.isGap }) LyricParser.processLinesWithGaps(diskLyrics) else diskLyrics
                        val sanitized = filterHeaderMetadata(processed, title, artist)
                        memoryCache[cacheKey] = sanitized
                        AppLogger.i(TAG, "Zero-delay lyrics loaded from local flash storage for '$cleanTitle' (< 2ms)")
                        return sanitized
                    }
                }
            }
        }

        val resolvedVid = cleanVid ?: YouTubeIdRegistry.findVideoIdByTitleArtist(cleanTitle, cleanArtist, durationSec)

        // 3. Priority 1: YouLyPlus Word-Level TTML / Apple Music Syllables
        // Evaluated before Supabase as top-priority source
        val youLyTtml = fetchYouLyPlusTtml(cleanTitle, cleanArtist, durationSec)
        if (youLyTtml.isNotEmpty()) {
            val processed = if (youLyTtml.any { it.isSynced && !it.isGap }) LyricParser.processLinesWithGaps(youLyTtml) else youLyTtml
            val sanitized = filterHeaderMetadata(processed, title, artist)
            memoryCache[cacheKey] = sanitized
            saveToDiskCache(cleanVid ?: resolvedVid, cleanTitle, cleanArtist, sanitized)
            AppLogger.i(TAG, "Loaded word-level TTML from YouLyPlus (Priority 1) for '$cleanTitle'")
            return sanitized
        }

        // 4. Priority 2: Supabase Cloud Object Storage fetch (~50 to 150 ms)
        // If YouLyPlus does NOT have TTML for the song, check Supabase
        val isResolvedBlacklisted = isBlacklistedFromSupabase || isSupabaseBlacklisted(resolvedVid, cleanTitle, cleanArtist)
        if (!isResolvedBlacklisted && !resolvedVid.isNullOrBlank() && !resolvedVid.startsWith("track_") && !resolvedVid.startsWith("placeholder_")) {
            val cloudLyrics = fetchZohoCloudTtml(resolvedVid, cleanTitle, cleanArtist, durationSec)
            if (cloudLyrics.isNotEmpty()) {
                val processed = if (cloudLyrics.any { it.isSynced && !it.isGap }) LyricParser.processLinesWithGaps(cloudLyrics) else cloudLyrics
                val sanitized = filterHeaderMetadata(processed, title, artist)
                memoryCache[cacheKey] = sanitized
                saveToDiskCache(resolvedVid, cleanTitle, cleanArtist, sanitized)
                AppLogger.i(TAG, "Loaded TTML lyrics from Supabase Cloud (Priority 2) for '$cleanTitle' (videoId=$resolvedVid)")
                return sanitized
            }
        }

        // 5. Priority 3: ArchiveTune-inspired concurrent multi-source cascade (SimpMusic, Unison, YouLyPlus LRC, BetterLyrics, LRCLIB, Kugou, NetEase)
        val rawLyrics = fetchLyricsRaw(cleanTitle, cleanArtist, durationSec, resolvedVid)
        if (rawLyrics.isNotEmpty()) {
            val processed = if (rawLyrics.any { it.isSynced && !it.isGap }) LyricParser.processLinesWithGaps(rawLyrics) else rawLyrics
            val sanitized = filterHeaderMetadata(processed, title, artist)
            memoryCache[cacheKey] = sanitized
            saveToDiskCache(cleanVid, cleanTitle, cleanArtist, sanitized)
            return sanitized
        }
        return emptyList()
    }

    /**
     * Extract prioritized candidate (title, artist) pairs from search keywords.
     * Handles YouTube compound titles like "Coldplay - Yellow", "Farebi - Chaar Diwaari x Raftaar",
     * "sufr, Arpit Bala - bargad", or featured artist credits.
     */
    fun extractCandidatePairs(rawTitle: String, rawArtist: String): List<Pair<String, String>> {
        val cleanT = cleanSearchKeyword(rawTitle).ifBlank { rawTitle.trim() }
        val cleanA = cleanSearchKeyword(rawArtist).ifBlank { rawArtist.trim() }
        val pairs = mutableListOf<Pair<String, String>>()

        // 1. Check if title contains artist-title or title-artist separator like " - ", " – ", " — "
        val separators = listOf(" - ", " – ", " — ")
        for (sep in separators) {
            if (cleanT.contains(sep)) {
                val parts = cleanT.split(sep, limit = 2)
                val p0 = parts[0].trim()
                val p1 = parts[1].trim()
                if (p0.isNotEmpty() && p1.isNotEmpty()) {
                    val p0MatchesArtist = cleanA.isNotEmpty() && isArtistMatching(cleanA, p0)
                    val p1MatchesArtist = cleanA.isNotEmpty() && isArtistMatching(cleanA, p1)

                    if (p0MatchesArtist) {
                        // p0 is artist, p1 is title
                        pairs.add(Pair(p1, cleanA))
                        pairs.add(Pair(p1, p0))
                    } else if (p1MatchesArtist) {
                        // p1 is artist, p0 is title
                        pairs.add(Pair(p0, cleanA))
                        pairs.add(Pair(p0, p1))
                    } else {
                        // Guess: p0 is artist, p1 is title (standard YouTube pattern)
                        pairs.add(Pair(p1, p0))
                        pairs.add(Pair(p0, p1))
                    }
                }
                break
            }
        }

        // 2. Add raw cleaned (title, artist)
        if (cleanT.isNotEmpty()) {
            pairs.add(Pair(cleanT, cleanA))
        }

        // Specific alias additions for known songs
        if (cleanT.equals("bargad", ignoreCase = true)) {
            pairs.add(Pair("bargad", "sufr"))
            pairs.add(Pair("bargad", "Arpit Bala"))
        } else if (cleanT.equals("maharani", ignoreCase = true)) {
            pairs.add(Pair("maharani", "karun"))
            pairs.add(Pair("maharani", "arpit bala"))
        }

        // 3. Try each artist if multiple artists present (e.g. "sufr, Arpit Bala, toorjo dey" -> "sufr", "Arpit Bala")
        if (cleanA.isNotEmpty()) {
            val splitArtists = cleanA.split(Regex("""[\s,;&/、|\\+x]+""")).map { it.trim() }.filter { it.length >= 2 }
            for (art in splitArtists) {
                val normalizedArt = if (art.equals("surf", ignoreCase = true)) "sufr" else art
                if (pairs.isNotEmpty()) {
                    pairs.add(Pair(pairs.first().first, normalizedArt))
                }
                pairs.add(Pair(cleanT, normalizedArt))
            }
        }

        // 4. Try title-only (blank artist) as fallback
        if (cleanT.isNotEmpty()) {
            if (pairs.isNotEmpty() && pairs.first().first != cleanT) {
                pairs.add(Pair(pairs.first().first, ""))
            }
            pairs.add(Pair(cleanT, ""))
        }

        // Deduplicate preserving order
        val seen = mutableSetOf<String>()
        val result = mutableListOf<Pair<String, String>>()
        for ((t, a) in pairs) {
            val key = "${t.lowercase()}::${a.lowercase()}"
            if (t.isNotEmpty() && key !in seen) {
                seen.add(key)
                result.add(Pair(t, a))
            }
        }
        return result
    }

    private data class LyricCandidate(
        val providerName: String,
        val lyrics: List<LyricLine>,
        val quality: Int, // 2 = Word-synced (TTML/KRC/YRC), 1 = Line-synced (LRC), 0 = Plain
        val priority: Int
    )

    private fun lyricsQuality(lines: List<LyricLine>): Int = when {
        lines.any { it.words.isNotEmpty() } -> 2
        lines.any { it.isSynced && !it.isGap } -> 1
        lines.isNotEmpty() -> 0
        else -> -1
    }

    private fun levenshteinDistance(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j
        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost
                )
            }
        }
        return dp[s1.length][s2.length]
    }

    private fun stringSimilarity(s1: String, s2: String): Double {
        val n1 = normalizeString(s1)
        val n2 = normalizeString(s2)
        if (n1 == n2) return 1.0
        val maxLen = maxOf(n1.length, n2.length)
        if (maxLen == 0) return 1.0
        val dist = levenshteinDistance(n1, n2)
        return 1.0 - (dist.toDouble() / maxLen.toDouble())
    }

    /**
     * ArchiveTune-inspired Provider 1: SimpMusic
     * Direct YouTube videoId lookup. Returns exact crowd-sourced synchronized lyrics with 0% mismatch.
     */
    private suspend fun fetchSimpMusic(videoId: String, durationSec: Int): List<LyricLine> {
        return runCatching {
            val url = "https://api-lyrics.simpmusic.org/v1/$videoId"
            val responseText = httpClient.get(url) {
                header("User-Agent", "SimpMusicLyrics/1.0 (Android)")
                header("Accept", "application/json")
            }.bodyAsText()

            val json = JSONObject(responseText)
            if (!json.optBoolean("success", false)) return@runCatching emptyList()
            val data = json.optJSONArray("data") ?: return@runCatching emptyList()
            if (data.length() == 0) return@runCatching emptyList()

            val candidates = mutableListOf<JSONObject>()
            for (i in 0 until data.length()) {
                candidates.add(data.getJSONObject(i))
            }

            val best = if (durationSec > 0 && candidates.size > 1) {
                candidates.minByOrNull { kotlin.math.abs(it.optInt("duration", 0) - durationSec) } ?: candidates.first()
            } else {
                candidates.first()
            }

            val synced = best.optString("syncedLyrics", "").takeIf { it.isNotBlank() }
            val plain = best.optString("plainLyrics", "").takeIf { it.isNotBlank() }
            val lyricsText = synced ?: plain ?: return@runCatching emptyList()

            val parsed = if (synced != null) LyricParser.parseLrc(lyricsText) else LyricParser.parsePlainLyrics(lyricsText)
            if (isValidLyrics(parsed)) {
                AppLogger.i(TAG, "SimpMusic hit for videoId '$videoId' (${parsed.size} lines, synced=${synced != null})")
                parsed
            } else {
                emptyList()
            }
        }.getOrDefault(emptyList())
    }

    /**
     * ArchiveTune-inspired Provider 2: Unison
     * Video ID and metadata search for synchronized lyrics.
     */
    private suspend fun fetchUnison(videoId: String, title: String, artist: String, durationSec: Int): List<LyricLine> {
        val vid = YouTubeIdRegistry.sanitizeVideoId(videoId).takeIf { it.isNotEmpty() }
        if (!vid.isNullOrBlank() && !vid.startsWith("track_") && !vid.startsWith("placeholder_")) {
            val byVid = runCatching {
                val resp = httpClient.get("https://unison.boidu.dev/lyrics") {
                    parameter("v", vid)
                    header("Accept", "application/json")
                    header("User-Agent", "ArchiveTune/1.0")
                }.bodyAsText()

                val json = JSONObject(resp)
                if (json.optBoolean("success", false)) {
                    val data = json.optJSONObject("data")
                    val lyrics = data?.optString("lyrics", "") ?: ""
                    if (lyrics.isNotBlank()) {
                        val parsed = if (lyrics.contains("<tt") || lyrics.contains("<span")) {
                            LyricParser.parseTtml(lyrics)
                        } else {
                            LyricParser.parseLrc(lyrics)
                        }
                        if (isValidLyrics(parsed) && !hasLanguageMismatch(title, artist, parsed)) {
                            AppLogger.i(TAG, "Unison videoId hit for '$vid' (${parsed.size} lines)")
                            parsed
                        } else emptyList()
                    } else emptyList()
                } else emptyList()
            }.getOrDefault(emptyList())
            if (byVid.isNotEmpty()) return byVid
        }
        return emptyList()
    }

    /**
     * Priority 1 Provider: YouLyPlus Word-Level TTML / Apple Music Syllables.
     * Evaluated BEFORE Supabase as the top-priority source for word-level lyrics.
     */
    private suspend fun fetchYouLyPlusTtml(title: String, artist: String, durationSec: Int): List<LyricLine> {
        val baseUrls = listOf(
            "https://lyricsplus.binimum.org",
            "https://lyricsplus.prjktla.my.id",
            "https://lyricsplus.atomix.one"
        )
        val pairs = extractCandidatePairs(title, artist)
        for (baseUrl in baseUrls) {
            for ((candTitle, candArtist) in pairs) {
                // 1. Try TTML word-level
                val ttmlLines = runCatching {
                    val resp = httpClient.get("$baseUrl/v1/ttml/get") {
                        parameter("title", candTitle)
                        if (candArtist.isNotBlank()) parameter("artist", candArtist)
                        if (durationSec > 0) parameter("duration", durationSec)
                        header("Accept", "application/json")
                        header("User-Agent", "ArchiveTune/1.0")
                    }.bodyAsText()
                    val obj = JSONObject(resp)
                    val ttml = obj.optString("ttml", "")
                    if (ttml.isNotBlank() && (ttml.contains("<tt") || ttml.contains("<span") || ttml.contains("<p"))) {
                        val parsed = LyricParser.parseTtml(ttml)
                        if (isValidLyrics(parsed) && !hasLanguageMismatch(title, artist, parsed) && parsed.any { it.words.isNotEmpty() }) {
                            val lastLine = parsed.lastOrNull { !it.isGap }
                            if (durationSec <= 0 || lastLine == null || kotlin.math.abs((lastLine.timeMs + lastLine.durationMs) / 1000 - durationSec) <= 15) {
                                AppLogger.i(TAG, "YouLyPlus Priority TTML hit for '$candTitle' by '$candArtist' (${parsed.size} lines)")
                                return@runCatching parsed
                            }
                        }
                    }
                    emptyList()
                }.getOrDefault(emptyList())

                if (ttmlLines.isNotEmpty()) return ttmlLines

                // 2. Try JSON lyrics array with word-level syllabus
                val jsonLines = runCatching {
                    val resp = httpClient.get("$baseUrl/v2/lyrics/get") {
                        parameter("title", candTitle)
                        if (candArtist.isNotBlank()) parameter("artist", candArtist)
                        if (durationSec > 0) parameter("duration", durationSec)
                        header("Accept", "application/json")
                        header("User-Agent", "ArchiveTune/1.0")
                    }.bodyAsText()
                    val obj = JSONObject(resp)
                    val lyricsArray = obj.optJSONArray("lyrics")
                    if (lyricsArray != null && lyricsArray.length() > 0) {
                        val lines = mutableListOf<LyricLine>()
                        for (i in 0 until lyricsArray.length()) {
                            val lineObj = lyricsArray.getJSONObject(i)
                            val timeMs = lineObj.optLong("time", -1L)
                            if (timeMs < 0L) continue
                            val durMs = lineObj.optLong("duration", 0L)
                            val text = lineObj.optString("text", "")
                            val syllabusArray = lineObj.optJSONArray("syllabus")
                            val words = mutableListOf<WordInfo>()
                            if (syllabusArray != null) {
                                for (j in 0 until syllabusArray.length()) {
                                    val sObj = syllabusArray.getJSONObject(j)
                                    val sTime = sObj.optLong("time", timeMs)
                                    val sDur = sObj.optLong("duration", 0L)
                                    val sText = sObj.optString("text", "")
                                    if (sText.isNotEmpty()) {
                                        val offset = (sTime - timeMs).coerceAtLeast(0L)
                                        words.add(WordInfo(text = sText, startOffsetMs = offset, durationMs = sDur))
                                    }
                                }
                            }
                            val stitchedWords = LyricParser.stitchSyllableGaps(words)
                            val cleanText = LyricParser.fixWordGaps(text)
                            lines.add(LyricLine(timeMs = timeMs, durationMs = durMs, text = cleanText, isSynced = true, words = stitchedWords))
                        }
                        if (isValidLyrics(lines) && !hasLanguageMismatch(title, artist, lines) && lines.any { it.words.isNotEmpty() }) {
                            AppLogger.i(TAG, "YouLyPlus Priority word-synced JSON hit for '$candTitle' by '$candArtist' (${lines.size} lines)")
                            return@runCatching lines
                        }
                    }
                    emptyList()
                }.getOrDefault(emptyList())

                if (jsonLines.isNotEmpty()) return jsonLines
            }
        }
        return emptyList()
    }

    /**
     * Multi-mirror client for Apple Music word-level TTML or line-synced LRC.
     */
    private suspend fun fetchYouLyPlus(title: String, artist: String, durationSec: Int): List<LyricLine> {
        val wordSynced = fetchYouLyPlusTtml(title, artist, durationSec)
        if (wordSynced.isNotEmpty()) return wordSynced

        val baseUrls = listOf(
            "https://lyricsplus.binimum.org",
            "https://lyricsplus.prjktla.my.id",
            "https://lyricsplus.atomix.one"
        )
        val pairs = extractCandidatePairs(title, artist)
        for (baseUrl in baseUrls) {
            for ((candTitle, candArtist) in pairs) {
                val lrcLines = runCatching {
                    val resp = httpClient.get("$baseUrl/v2/lyrics/get") {
                        parameter("title", candTitle)
                        if (candArtist.isNotBlank()) parameter("artist", candArtist)
                        if (durationSec > 0) parameter("duration", durationSec)
                        header("Accept", "application/json")
                        header("User-Agent", "ArchiveTune/1.0")
                    }.bodyAsText()
                    val obj = JSONObject(resp)
                    val lrc = obj.optString("lyrics", "")
                    if (lrc.isNotBlank() && lrc.contains("[")) {
                        val parsed = LyricParser.parseLrc(lrc)
                        if (isValidLyrics(parsed) && !hasLanguageMismatch(title, artist, parsed)) {
                            AppLogger.i(TAG, "YouLyPlus LRC hit for '$candTitle' by '$candArtist' (${parsed.size} lines)")
                            return@runCatching parsed
                        }
                    }
                    emptyList()
                }.getOrDefault(emptyList())

                if (lrcLines.isNotEmpty()) return lrcLines
            }
        }
        return emptyList()
    }

    /**
     * ArchiveTune-inspired concurrent multi-source lyrics cascade.
     * Launches verified providers simultaneously with quality grading:
     * - Quality 2: Word-by-word TTML / KRC / YRC karaoke
     * - Quality 1: High-precision line-synced LRC
     * - Quality 0: Plain lyrics
     *
     * Grants a grace period if line-synced arrives first to allow word-synced TTML to complete.
     */
    private suspend fun fetchLyricsRaw(
        cleanTitle: String,
        cleanArtist: String,
        durationSec: Int,
        videoId: String? = null
    ): List<LyricLine> = coroutineScope {
        AppLogger.i(TAG, "Initiating concurrent lyrics cascade for '$cleanTitle' by '$cleanArtist' (duration: ${durationSec}s, videoId=$videoId)")
        val isHanTrack = HAN_REGEX.containsMatchIn(cleanTitle) || HAN_REGEX.containsMatchIn(cleanArtist)

        val channel = Channel<LyricCandidate>(Channel.UNLIMITED)
        val jobs = mutableListOf<Job>()

        val cleanVid = YouTubeIdRegistry.sanitizeVideoId(videoId).takeIf { it.isNotEmpty() }

        // 1. SimpMusic (1:1 YouTube videoId lookup -> 0% wrong song mismatch)
        if (!cleanVid.isNullOrBlank()) {
            jobs += launch(Dispatchers.IO) {
                try {
                    val res = fetchSimpMusic(cleanVid, durationSec)
                    if (res.isNotEmpty()) {
                        val q = lyricsQuality(res)
                        channel.trySend(LyricCandidate("SimpMusic", res, q, priority = if (q == 2) 0 else 2))
                    }
                } catch (e: Exception) {
                    AppLogger.d(TAG, "SimpMusic lookup skipped: ${e.message}")
                }
            }

            // 2. Unison (videoId + metadata lookup)
            jobs += launch(Dispatchers.IO) {
                try {
                    val res = fetchUnison(cleanVid, cleanTitle, cleanArtist, durationSec)
                    if (res.isNotEmpty()) {
                        channel.trySend(LyricCandidate("Unison", res, lyricsQuality(res), priority = 2))
                    }
                } catch (e: Exception) {
                    AppLogger.d(TAG, "Unison lookup skipped: ${e.message}")
                }
            }
        }

        // 3. YouLyPlus (Apple Music Word-level TTML & LRC)
        jobs += launch(Dispatchers.IO) {
            try {
                val res = fetchYouLyPlus(cleanTitle, cleanArtist, durationSec)
                if (res.isNotEmpty()) {
                    channel.trySend(LyricCandidate("YouLyPlus", res, lyricsQuality(res), priority = 1))
                }
            } catch (e: Exception) {
                AppLogger.d(TAG, "YouLyPlus lookup skipped: ${e.message}")
            }
        }

        // 4. BetterLyrics TTML (Apple Music Word-level)
        jobs += launch(Dispatchers.IO) {
            try {
                val res = fetchBetterLyrics(cleanTitle, cleanArtist, durationSec)
                if (res.isNotEmpty() && res.any { it.isSynced && !it.isGap }) {
                    channel.trySend(LyricCandidate("BetterLyrics", res, lyricsQuality(res), priority = 1))
                }
            } catch (e: Exception) {
                AppLogger.d(TAG, "BetterLyrics lookup skipped: ${e.message}")
            }
        }

        // 5. LRCLIB (Line-synced LRC with strict duration & Levenshtein matching)
        jobs += launch(Dispatchers.IO) {
            try {
                val res = fetchLrcLib(cleanTitle, cleanArtist, durationSec)
                if (res.isNotEmpty()) {
                    val calibrated = calibrateIntroTimings(filterHeaderMetadata(res, cleanTitle, cleanArtist), cleanTitle)
                    channel.trySend(LyricCandidate("LRCLIB", calibrated, lyricsQuality(calibrated), priority = 3))
                }
            } catch (e: Exception) {
                AppLogger.d(TAG, "LRCLIB lookup skipped: ${e.message}")
            }
        }

        // 6. Kugou Music KRC (Word-level) or LRC
        jobs += launch(Dispatchers.IO) {
            try {
                val krc = fetchKugouKrc(cleanTitle, cleanArtist, durationSec)
                if (krc.isNotEmpty() && krc.any { it.words.isNotEmpty() }) {
                    channel.trySend(LyricCandidate("KugouKRC", krc, 2, priority = if (isHanTrack) 1 else 3))
                } else {
                    val lrc = fetchKugouLrc(cleanTitle, cleanArtist, durationSec)
                    if (lrc.isNotEmpty()) {
                        channel.trySend(LyricCandidate("KugouLRC", lrc, 1, priority = 5))
                    }
                }
            } catch (e: Exception) {
                AppLogger.d(TAG, "Kugou lookup skipped: ${e.message}")
            }
        }

        // 7. NetEase Cloud Music YRC (Word-level) or LRC
        jobs += launch(Dispatchers.IO) {
            try {
                val yrc = fetchNetEaseYrc(cleanTitle, cleanArtist, durationSec)
                if (yrc.isNotEmpty() && yrc.any { it.words.isNotEmpty() }) {
                    channel.trySend(LyricCandidate("NetEaseYRC", yrc, 2, priority = if (isHanTrack) 2 else 4))
                } else {
                    val lrc = fetchNetEaseLrc(cleanTitle, cleanArtist, durationSec)
                    if (lrc.isNotEmpty()) {
                        channel.trySend(LyricCandidate("NetEaseLRC", lrc, 1, priority = 5))
                    }
                }
            } catch (e: Exception) {
                AppLogger.d(TAG, "NetEase lookup skipped: ${e.message}")
            }
        }

        var best: LyricCandidate? = null
        val overallDeadline = SystemClock.elapsedRealtime() + 6500L
        var qualityDeadline = overallDeadline

        try {
            while (SystemClock.elapsedRealtime() < minOf(overallDeadline, qualityDeadline)) {
                val remainingMs = minOf(overallDeadline, qualityDeadline) - SystemClock.elapsedRealtime()
                if (remainingMs <= 0L) break

                val candidate = withTimeoutOrNull(remainingMs) {
                    channel.receive()
                } ?: break

                val currentBest = best
                if (currentBest == null ||
                    candidate.quality > currentBest.quality ||
                    (candidate.quality == currentBest.quality && candidate.priority < currentBest.priority)
                ) {
                    best = candidate
                    AppLogger.d(TAG, "Candidate elected: ${candidate.providerName} (quality=${candidate.quality}, priority=${candidate.priority}, lines=${candidate.lyrics.size})")

                    if (candidate.quality == 2) {
                        if (candidate.priority <= 1) {
                            // Top tier word-level karaoke (YouLyPlus / BetterLyrics / SimpMusic): resolve immediately
                            break
                        } else {
                            // Lower tier word-level: allow 250ms for Apple Music TTML to finish
                            qualityDeadline = minOf(qualityDeadline, SystemClock.elapsedRealtime() + 250L)
                        }
                    } else if (candidate.quality == 1) {
                        // Line-synced arrived: allow up to 800ms for a word-synced candidate to arrive
                        qualityDeadline = minOf(qualityDeadline, SystemClock.elapsedRealtime() + 800L)
                    }
                }
            }
        } finally {
            jobs.forEach { it.cancel() }
            channel.close()
        }

        if (best != null) {
            AppLogger.i(TAG, "Cascade winner: ${best.providerName} (${best.lyrics.size} lines, quality=${best.quality}) for '$cleanTitle'")
            return@coroutineScope filterHeaderMetadata(best.lyrics, cleanTitle, cleanArtist)
        }

        // 8. Plain Lyrics fallback as absolute last resort
        try {
            val plain = fetchPlainLyricsFallback(cleanTitle, cleanArtist)
            if (plain.isNotEmpty()) {
                AppLogger.i(TAG, "Loaded unsynced plain lyrics as last resort for '$cleanTitle'")
                return@coroutineScope plain
            }
        } catch (e: Exception) {
            AppLogger.d(TAG, "Plain lyrics fallback failed: ${e.message}")
        }

        AppLogger.w(TAG, "All lyrics sources exhausted with no match for '$cleanTitle' by '$cleanArtist'")
        emptyList()
    }

    /**
     * Source 1: BetterLyrics TTML (Apple Music Word-Level / Line-Level)
     */
    private suspend fun fetchBetterLyrics(title: String, artist: String, durationSec: Int): List<LyricLine> {
        val pairs = extractCandidatePairs(title, artist)
        for ((candTitle, candArtist) in pairs) {
            try {
                val responseText = httpClient.get("https://api.betterlyrics.org/getLyrics") {
                    header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    parameter("s", candTitle)
                    if (candArtist.isNotBlank()) {
                        parameter("a", candArtist)
                    }
                }.bodyAsText()

                val jsonObj = JSONObject(responseText)
                val ttml = jsonObj.optString("ttml", "")
                if (ttml.isNotBlank() && (ttml.contains("<span") || ttml.contains("<p") || ttml.contains("<tt"))) {
                    val lines = LyricParser.parseTtml(ttml)
                    if (isValidLyrics(lines) && !hasLanguageMismatch(title, artist, lines)) {
                        val lastLine = lines.lastOrNull { !it.isGap }
                        if (durationSec > 0 && lastLine != null) {
                            val lyricEndSec = (lastLine.timeMs + lastLine.durationMs) / 1000
                            if (lyricEndSec > 0 && kotlin.math.abs(lyricEndSec - durationSec) > 35) {
                                AppLogger.w(TAG, "BetterLyrics rejected: lyric end ${lyricEndSec}s differs from track duration ${durationSec}s > 35s")
                                continue
                            }
                        }
                        // Synthesize word timings for lines with only line-level timestamps for smooth karaoke glow
                        val enhancedLines = lines.map { line ->
                            if (!line.isGap && line.words.isEmpty() && line.durationMs > 0) {
                                line.copy(words = LyricParser.synthesizeWordTimings(line))
                            } else {
                                line
                            }
                        }
                        AppLogger.i(TAG, "BetterLyrics hit for '$candTitle' by '$candArtist' (${enhancedLines.size} lines)")
                        return enhancedLines
                    }
                }

                // Also check if BetterLyrics returned "lrc" or "lyrics"
                val lrc = jsonObj.optString("lrc", "").ifBlank { jsonObj.optString("lyrics", "") }
                if (lrc.isNotBlank() && lrc.contains("[")) {
                    val lines = LyricParser.parseLrc(lrc)
                    if (isValidLyrics(lines) && !hasLanguageMismatch(title, artist, lines)) {
                        AppLogger.i(TAG, "BetterLyrics LRC hit for '$candTitle' by '$candArtist' (${lines.size} lines)")
                        return lines
                    }
                }
            } catch (e: Exception) {
                AppLogger.d(TAG, "BetterLyrics attempt for '$candTitle' by '$candArtist' failed: ${e.message}")
            }
        }
        return emptyList()
    }

    /**
     * Source 2: Kugou Music KRC (Word-Level Syllables)
     */
    private suspend fun fetchKugouKrc(title: String, artist: String, durationSec: Int): List<LyricLine> {
        val isHanTrack = HAN_REGEX.containsMatchIn(title) || HAN_REGEX.containsMatchIn(artist)
        if (!isHanTrack && artist.isBlank()) return emptyList()

        val query = if (artist.isNotBlank()) "$title $artist".trim() else title.trim()

        try {
            val searchJsonStr = httpClient.get("http://msearchcdn.kugou.com/api/v3/search/song") {
                header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                parameter("keyword", query)
                parameter("page", "1")
                parameter("pagesize", "10")
            }.bodyAsText()

            val searchObj = JSONObject(searchJsonStr)
            val infoList = searchObj.optJSONObject("data")?.optJSONArray("info") ?: return emptyList()
            if (infoList.length() == 0) return emptyList()

            val candidatesList = mutableListOf<Triple<String, Int, String>>()
            for (i in 0 until infoList.length()) {
                val item = infoList.getJSONObject(i)
                val hash = item.optString("hash")
                val dur = item.optInt("duration", 0)
                val singer = item.optString("singername", "")
                val filename = item.optString("filename", "")
                if (hash.isNotBlank()) {
                    if (!isHanTrack && (HAN_REGEX.containsMatchIn(singer) || HAN_REGEX.containsMatchIn(filename))) {
                        continue
                    }
                    if (artist.isNotBlank() && !isArtistMatching(artist, singer) && !filename.contains(artist, ignoreCase = true)) {
                        continue
                    }
                    if (!isHanTrack && durationSec > 0 && dur > 0 && kotlin.math.abs(dur - durationSec) > 15) {
                        continue
                    }
                    candidatesList.add(Triple(hash, dur, singer))
                }
            }

            val sortedCandidates = if (durationSec > 0) {
                candidatesList.sortedBy { kotlin.math.abs(it.second - durationSec) }
            } else {
                candidatesList
            }

            for ((songHash, _, _) in sortedCandidates.take(3)) {
                val candidateJsonStr = httpClient.get("http://lyrics.kugou.com/search") {
                    header("User-Agent", "Mozilla/5.0")
                    parameter("ver", "1")
                    parameter("man", "yes")
                    parameter("client", "pc")
                    parameter("hash", songHash)
                }.bodyAsText()

                val candidateObj = JSONObject(candidateJsonStr)
                val candidates = candidateObj.optJSONArray("candidates") ?: continue
                if (candidates.length() == 0) continue

                for (cIdx in 0 until candidates.length().coerceAtMost(5)) {
                    val candidate = candidates.getJSONObject(cIdx)
                    val candSinger = candidate.optString("singer", "")
                    if (!isHanTrack && HAN_REGEX.containsMatchIn(candSinger)) {
                        continue
                    }
                    if (artist.isNotBlank() && candSinger.isNotBlank() && !isArtistMatching(artist, candSinger)) {
                        continue
                    }

                    val cid = candidate.optString("id")
                    val accessKey = candidate.optString("accesskey")
                    if (cid.isBlank() || accessKey.isBlank()) continue

                    val downloadJsonStr = httpClient.get("http://lyrics.kugou.com/download") {
                        header("User-Agent", "Mozilla/5.0")
                        parameter("ver", "1")
                        parameter("client", "pc")
                        parameter("id", cid)
                        parameter("accesskey", accessKey)
                        parameter("fmt", "krc")
                        parameter("charset", "utf8")
                    }.bodyAsText()

                    val downloadObj = JSONObject(downloadJsonStr)
                    val contentB64 = downloadObj.optString("content")
                    if (contentB64.isBlank()) continue

                    val krcText = decryptKrc(contentB64) ?: continue
                    val lines = LyricParser.parseKrc(krcText)
                    if (isValidLyrics(lines) && !hasLanguageMismatch(title, artist, lines) && lines.any { it.words.isNotEmpty() }) {
                        return lines
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.d(TAG, "Kugou KRC attempt for '$query' failed: ${e.message}")
        }
        return emptyList()
    }

    /**
     * Source 3: NetEase Cloud Music YRC (Word-Level Syllables)
     */
    private suspend fun fetchNetEaseYrc(title: String, artist: String, durationSec: Int): List<LyricLine> {
        val isHanTrack = HAN_REGEX.containsMatchIn(title) || HAN_REGEX.containsMatchIn(artist)
        if (!isHanTrack && artist.isBlank()) return emptyList()

        val query = if (artist.isNotBlank()) "$title $artist".trim() else title.trim()

        try {
            val searchJsonStr = httpClient.get("https://music.163.com/api/search/get") {
                header("User-Agent", "Mozilla/5.0 (Linux; Android 10; Mobile)")
                parameter("s", query)
                parameter("type", "1")
                parameter("offset", "0")
                parameter("limit", "5")
            }.bodyAsText()

            val searchObj = JSONObject(searchJsonStr)
            val songs = searchObj.optJSONObject("result")?.optJSONArray("songs") ?: return emptyList()
            if (songs.length() == 0) return emptyList()

            val songIdList = mutableListOf<Pair<Long, Double>>()
            for (i in 0 until songs.length()) {
                val songObj = songs.getJSONObject(i)
                val id = songObj.optLong("id")
                val durMs = songObj.optLong("duration", 0L)
                val durSec = durMs / 1000.0

                val artistsArr = songObj.optJSONArray("artists")
                val artistNames = mutableListOf<String>()
                if (artistsArr != null) {
                    for (aIdx in 0 until artistsArr.length()) {
                        artistNames.add(artistsArr.getJSONObject(aIdx).optString("name", ""))
                    }
                }
                val combinedArtist = artistNames.joinToString(", ")
                if (!isHanTrack && HAN_REGEX.containsMatchIn(combinedArtist)) {
                    continue
                }
                if (artist.isNotBlank() && !isArtistMatching(artist, combinedArtist)) {
                    continue
                }

                val diff = if (durationSec > 0 && durSec > 0) kotlin.math.abs(durSec - durationSec) else 0.0
                val maxDiff = if (!isHanTrack) 15.0 else 35.0
                if (durationSec > 0 && durSec > 0 && diff > maxDiff) {
                    continue
                }
                songIdList.add(Pair(id, diff))
            }

            val sortedSongs = if (durationSec > 0) {
                songIdList.sortedBy { it.second }
            } else {
                songIdList
            }

            for ((songId, _) in sortedSongs.take(3)) {
                val lyricJsonStr = httpClient.get("https://music.163.com/api/song/lyric") {
                    header("User-Agent", "Mozilla/5.0 (Linux; Android 10; Mobile)")
                    parameter("id", songId.toString())
                    parameter("lv", "-1")
                    parameter("kv", "-1")
                    parameter("tv", "-1")
                    parameter("yv", "1")
                }.bodyAsText()

                val lyricObj = JSONObject(lyricJsonStr)
                val yrcText = lyricObj.optJSONObject("yrc")?.optString("lyric")
                if (!yrcText.isNullOrBlank()) {
                    val lines = LyricParser.parseYrc(yrcText)
                    if (isValidLyrics(lines) && !hasLanguageMismatch(title, artist, lines) && lines.any { it.words.isNotEmpty() }) {
                        return lines
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.d(TAG, "NetEase YRC attempt for '$query' failed: ${e.message}")
        }
        return emptyList()
    }

    /**
     * Source 4: LRCLIB (Line-Level Synced LRC)
     * Queries /get exact and /search cascade using extracted title/artist pairs.
     * Calibrates intro timings and synthesizes syllable rhythms for smooth karaoke.
     */
    private suspend fun fetchLrcLib(title: String, artist: String, durationSec: Int): List<LyricLine> {
        val pairs = extractCandidatePairs(title, artist)

        fun pickBestSyncedCandidate(results: List<LrcLibResponse>, expectedArtist: String, targetTitle: String): List<LyricLine>? {
            val candidates = results.filter { r ->
                if (r.syncedLyrics.isNullOrBlank()) return@filter false
                val durDiff = if (durationSec > 0 && r.duration > 0.0) kotlin.math.abs(r.duration - durationSec) else 0.0
                // ArchiveTune accuracy rule: songs with >3.5s duration difference are different versions/songs
                val durationMatches = durationSec <= 0 || r.duration <= 0.0 || durDiff <= 3.5
                val artistMatches = expectedArtist.isBlank() || isArtistMatching(expectedArtist, r.artistName) || (artist.isNotBlank() && isArtistMatching(artist, r.artistName))
                val titleSim = stringSimilarity(targetTitle, r.trackName)
                val titleMatches = targetTitle.isBlank() || titleSim >= 0.65 ||
                        r.trackName.contains(targetTitle, ignoreCase = true) ||
                        targetTitle.contains(r.trackName, ignoreCase = true)
                durationMatches && artistMatches && titleMatches
            }
            if (candidates.isEmpty()) return null

            val bestMatch = if (durationSec > 0) {
                candidates.minByOrNull { kotlin.math.abs(it.duration - durationSec) }
            } else {
                candidates.firstOrNull()
            }

            if (bestMatch?.syncedLyrics != null) {
                val diff = if (durationSec > 0 && bestMatch.duration > 0) kotlin.math.abs(bestMatch.duration - durationSec) else 0.0
                val durationOk = durationSec <= 0 || bestMatch.duration <= 0.0 || diff <= 3.5
                val artistOk = expectedArtist.isBlank() || isArtistMatching(expectedArtist, bestMatch.artistName) || (artist.isNotBlank() && isArtistMatching(artist, bestMatch.artistName))
                if (durationOk && artistOk) {
                    val lines = LyricParser.parseLrc(bestMatch.syncedLyrics)
                    if (isValidLyrics(lines) && !hasLanguageMismatch(title, artist, lines)) {
                        AppLogger.i(TAG, "lrclib candidate accepted: '${bestMatch.trackName}' by '${bestMatch.artistName}' (dur: ${bestMatch.duration}s, diff: ${diff}s)")
                        return lines
                    }
                }
            }
            return null
        }

        for ((candTitle, candArtist) in pairs) {
            // 1. Try exact match /get
            if (candArtist.isNotBlank()) {
                try {
                    val exactResponse = httpClient.get("$LRCLIB_BASE/get") {
                        header("User-Agent", "October/1.0 (Android; OpenSource Music Player)")
                        parameter("track_name", candTitle)
                        parameter("artist_name", candArtist)
                        if (durationSec > 0) {
                            parameter("duration", durationSec.toString())
                        }
                    }.bodyAsText()

                    val exactResult = json.decodeFromString<LrcLibResponse>(exactResponse)
                    val syncedLyrics = exactResult.syncedLyrics
                    if (!syncedLyrics.isNullOrBlank()) {
                        val durationDiff = if (durationSec > 0 && exactResult.duration > 0.0) {
                            kotlin.math.abs(exactResult.duration - durationSec)
                        } else 0.0
                        if (durationSec <= 0 || durationDiff <= 4.0) {
                            val lines = LyricParser.parseLrc(syncedLyrics)
                            if (isValidLyrics(lines) && !hasLanguageMismatch(title, artist, lines)) {
                                AppLogger.i(TAG, "lrclib.net /get exact synced match accepted for '$candTitle' by '$candArtist' (diff: ${durationDiff}s)")
                                return lines
                            }
                        }
                    }
                } catch (e: Exception) {
                    AppLogger.d(TAG, "lrclib /get exact skipped for '$candTitle': ${e.message}")
                }
            }

            // 2. Try /search with track_name and artist_name
            try {
                val searchResponse = httpClient.get("$LRCLIB_BASE/search") {
                    header("User-Agent", "October/1.0 (Android; OpenSource Music Player)")
                    parameter("track_name", candTitle)
                    if (candArtist.isNotBlank()) {
                        parameter("artist_name", candArtist)
                    }
                }.bodyAsText()

                val searchResults = json.decodeFromString<List<LrcLibResponse>>(searchResponse)
                val lines = pickBestSyncedCandidate(searchResults, candArtist, candTitle)
                if (lines != null) return lines
            } catch (e: Exception) {
                AppLogger.d(TAG, "lrclib /search (track+artist) failed for '$candTitle': ${e.message}")
            }

            // 3. Try /search with general query q="$candTitle $candArtist"
            val queryStr = if (candArtist.isNotBlank()) "$candTitle $candArtist".trim() else candTitle.trim()
            try {
                val queryResponse = httpClient.get("$LRCLIB_BASE/search") {
                    header("User-Agent", "October/1.0 (Android; OpenSource Music Player)")
                    parameter("q", queryStr)
                }.bodyAsText()

                val queryResults = json.decodeFromString<List<LrcLibResponse>>(queryResponse)
                val lines = pickBestSyncedCandidate(queryResults, candArtist, candTitle)
                if (lines != null) return lines
            } catch (e: Exception) {
                AppLogger.d(TAG, "lrclib /search (q=$queryStr) failed: ${e.message}")
            }
        }

        return emptyList()
    }

    /**
     * Source 5: NetEase Cloud Music Synced LRC
     */
    private suspend fun fetchNetEaseLrc(title: String, artist: String, durationSec: Int): List<LyricLine> {
        val isHanTrack = HAN_REGEX.containsMatchIn(title) || HAN_REGEX.containsMatchIn(artist)
        if (!isHanTrack && artist.isBlank()) return emptyList()

        val query = if (artist.isNotBlank()) "$title $artist".trim() else title.trim()

        try {
            val searchJsonStr = httpClient.get("https://music.163.com/api/search/get") {
                header("User-Agent", "Mozilla/5.0 (Linux; Android 10; Mobile)")
                parameter("s", query)
                parameter("type", "1")
                parameter("offset", "0")
                parameter("limit", "5")
            }.bodyAsText()

            val searchObj = JSONObject(searchJsonStr)
            val songs = searchObj.optJSONObject("result")?.optJSONArray("songs") ?: return emptyList()
            if (songs.length() == 0) return emptyList()

            for (i in 0 until songs.length().coerceAtMost(3)) {
                val songObj = songs.getJSONObject(i)
                val id = songObj.optLong("id")
                val durMs = songObj.optLong("duration", 0L)
                val durSec = durMs / 1000.0

                val artistsArr = songObj.optJSONArray("artists")
                val artistNames = mutableListOf<String>()
                if (artistsArr != null) {
                    for (aIdx in 0 until artistsArr.length()) {
                        artistNames.add(artistsArr.getJSONObject(aIdx).optString("name", ""))
                    }
                }
                val candArtist = artistNames.joinToString(", ")
                if (!isHanTrack && HAN_REGEX.containsMatchIn(candArtist)) {
                    continue
                }
                if (artist.isNotBlank() && candArtist.isNotBlank() && !isArtistMatching(artist, candArtist)) {
                    continue
                }

                if (durationSec > 0 && durSec > 0 && kotlin.math.abs(durSec - durationSec) > 20.0) {
                    continue
                }

                val lyricJsonStr = httpClient.get("https://music.163.com/api/song/lyric") {
                    header("User-Agent", "Mozilla/5.0 (Linux; Android 10; Mobile)")
                    parameter("id", id.toString())
                    parameter("lv", "-1")
                    parameter("kv", "-1")
                    parameter("tv", "-1")
                }.bodyAsText()

                val lyricObj = JSONObject(lyricJsonStr)
                val lrcText = lyricObj.optJSONObject("lrc")?.optString("lyric")
                if (!lrcText.isNullOrBlank() && lrcText.contains("[")) {
                    val lines = LyricParser.parseLrc(lrcText)
                    if (isValidLyrics(lines) && !hasLanguageMismatch(title, artist, lines)) {
                        return lines
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.d(TAG, "NetEase LRC attempt for '$query' failed: ${e.message}")
        }
        return emptyList()
    }

    /**
     * Source 6: Kugou Music Synced LRC
     */
    private suspend fun fetchKugouLrc(title: String, artist: String, durationSec: Int): List<LyricLine> {
        val isHanTrack = HAN_REGEX.containsMatchIn(title) || HAN_REGEX.containsMatchIn(artist)
        if (!isHanTrack && artist.isBlank()) return emptyList()

        val query = if (artist.isNotBlank()) "$title $artist".trim() else title.trim()

        try {
            val searchJsonStr = httpClient.get("http://msearchcdn.kugou.com/api/v3/search/song") {
                header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                parameter("keyword", query)
                parameter("page", "1")
                parameter("pagesize", "5")
            }.bodyAsText()

            val searchObj = JSONObject(searchJsonStr)
            val infoList = searchObj.optJSONObject("data")?.optJSONArray("info") ?: return emptyList()
            if (infoList.length() == 0) return emptyList()

            for (i in 0 until infoList.length().coerceAtMost(3)) {
                val item = infoList.getJSONObject(i)
                val hash = item.optString("hash")
                val dur = item.optInt("duration", 0)
                val singer = item.optString("singername", "")
                val filename = item.optString("filename", "")
                if (hash.isBlank()) continue

                if (!isHanTrack && (HAN_REGEX.containsMatchIn(singer) || HAN_REGEX.containsMatchIn(filename))) {
                    continue
                }
                if (artist.isNotBlank() && singer.isNotBlank() && !isArtistMatching(artist, singer) && !filename.contains(artist, ignoreCase = true)) {
                    continue
                }

                if (durationSec > 0 && dur > 0 && kotlin.math.abs(dur - durationSec) > 20) {
                    continue
                }

                val candidateJsonStr = httpClient.get("http://lyrics.kugou.com/search") {
                    header("User-Agent", "Mozilla/5.0")
                    parameter("ver", "1")
                    parameter("man", "yes")
                    parameter("client", "pc")
                    parameter("hash", hash)
                }.bodyAsText()

                val candidateObj = JSONObject(candidateJsonStr)
                val candidates = candidateObj.optJSONArray("candidates") ?: continue
                if (candidates.length() == 0) continue

                val firstCand = candidates.getJSONObject(0)
                val cid = firstCand.optString("id")
                val accessKey = firstCand.optString("accesskey")
                val candSinger = firstCand.optString("singer", "")
                if (cid.isBlank() || accessKey.isBlank()) continue

                if (!isHanTrack && HAN_REGEX.containsMatchIn(candSinger)) {
                    continue
                }
                if (artist.isNotBlank() && candSinger.isNotBlank() && !isArtistMatching(artist, candSinger)) {
                    continue
                }

                val downloadJsonStr = httpClient.get("http://lyrics.kugou.com/download") {
                    header("User-Agent", "Mozilla/5.0")
                    parameter("ver", "1")
                    parameter("client", "pc")
                    parameter("id", cid)
                    parameter("accesskey", accessKey)
                    parameter("fmt", "lrc")
                    parameter("charset", "utf8")
                }.bodyAsText()

                val downloadObj = JSONObject(downloadJsonStr)
                val contentB64 = downloadObj.optString("content")
                if (contentB64.isNotBlank()) {
                    val lrcText = try {
                        Base64.decode(contentB64, Base64.DEFAULT).toString(Charsets.UTF_8)
                    } catch (_: Exception) {
                        null
                    }
                    if (!lrcText.isNullOrBlank() && lrcText.contains("[")) {
                        val lines = LyricParser.parseLrc(lrcText)
                        if (isValidLyrics(lines) && !hasLanguageMismatch(title, artist, lines)) {
                            return lines
                        }
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.d(TAG, "Kugou LRC attempt for '$query' failed: ${e.message}")
        }
        return emptyList()
    }

    /**
     * Source 7: Plain Text Lyrics (ABSOLUTE LAST RESORT)
     * If no synchronized lyrics exist anywhere, fetch plain lyrics and return them with isSynced = false.
     * October will display them statically without fake timer jumps or arbitrary auto-scroll.
     */
    private suspend fun fetchPlainLyricsFallback(title: String, artist: String): List<LyricLine> {
        val pairs = extractCandidatePairs(title, artist)
        for ((candTitle, candArtist) in pairs) {
            val query = if (candArtist.isNotBlank()) "$candTitle $candArtist".trim() else candTitle.trim()
            try {
                val searchResponse = httpClient.get("$LRCLIB_BASE/search") {
                    header("User-Agent", "October/1.0 (Android; OpenSource Music Player)")
                    parameter("q", query)
                }.bodyAsText()

                val results = json.decodeFromString<List<LrcLibResponse>>(searchResponse)
                val candidate = results.firstOrNull { r ->
                    !r.plainLyrics.isNullOrBlank() && (candArtist.isBlank() || isArtistMatching(candArtist, r.artistName))
                } ?: results.firstOrNull { !it.plainLyrics.isNullOrBlank() }

                if (candidate?.plainLyrics != null) {
                    val lines = LyricParser.parsePlainLyrics(candidate.plainLyrics)
                    if (lines.isNotEmpty() && !hasLanguageMismatch(title, artist, lines)) {
                        return lines
                    }
                }
            } catch (_: Exception) {}
        }
        return emptyList()
    }

    /**
     * Decrypt Kugou KRC XOR encryption and decompress zlib stream
     */
    private fun decryptKrc(base64Content: String): String? {
        return try {
            val raw = Base64.decode(base64Content, Base64.DEFAULT)
            if (raw.size <= 4) return null
            val key = intArrayOf(64, 71, 97, 119, 94, 50, 116, 71, 81, 54, 49, 45, 206, 210, 110, 105)
            val decrypted = ByteArray(raw.size - 4)
            for (i in 4 until raw.size) {
                val keyByte = key[(i - 4) % 16]
                decrypted[i - 4] = ((raw[i].toInt() and 0xFF) xor keyByte).toByte()
            }

            // Attempt standard zlib decompress
            try {
                val inflater = InflaterInputStream(ByteArrayInputStream(decrypted))
                val bytes = inflater.readBytes()
                if (bytes.isNotEmpty()) {
                    return bytes.toString(Charsets.UTF_8)
                }
            } catch (_: Exception) {
                // Fallback to raw deflate (nowrap = true)
                val rawInflater = Inflater(true)
                val inflater = InflaterInputStream(ByteArrayInputStream(decrypted), rawInflater)
                val bytes = inflater.readBytes()
                if (bytes.isNotEmpty()) {
                    return bytes.toString(Charsets.UTF_8)
                }
            }
            null
        } catch (e: Exception) {
            AppLogger.d(TAG, "KRC decryption failed: ${e.message}")
            null
        }
    }

    private val ARTIST_STOPWORDS = setOf(
        "the", "a", "an", "and", "or", "feat", "ft", "featuring", "dj", "mc", "dr",
        "mr", "ms", "mrs", "lil", "big", "young", "boy", "girl", "band", "orchestra",
        "trio", "duo", "group", "soundtrack", "ost", "records", "music", "official",
        "vs", "with", "by", "in", "on", "of", "to", "at", "from"
    )

    private fun isArtistMatching(expected: String, candidate: String): Boolean {
        if (expected.isBlank() || candidate.isBlank()) return false
        val expClean = normalizeString(expected)
        val candClean = normalizeString(candidate)
        if (expClean.isEmpty() || candClean.isEmpty()) return false
        if (expClean == candClean) return true
        if (expClean in candClean || candClean in expClean) return true

        val expTokens = expClean.split(Regex("""[\s,;&/、|\\+x]+"""))
            .map { it.trim() }
            .filter { it.length >= 3 && it !in ARTIST_STOPWORDS }
        val candTokens = candClean.split(Regex("""[\s,;&/、|\\+x]+"""))
            .map { it.trim() }
            .filter { it.length >= 3 && it !in ARTIST_STOPWORDS }

        if (expTokens.isEmpty() || candTokens.isEmpty()) return false

        return expTokens.any { t ->
            candTokens.any { c ->
                t == c || (t.length >= 4 && c.length >= 4 && (t.startsWith(c) || c.startsWith(t)))
            }
        }
    }

    private fun normalizeString(input: String): String {
        val normalized = java.text.Normalizer.normalize(input, java.text.Normalizer.Form.NFD)
        return normalized.replace(Regex("""\p{Mn}"""), "")
            .lowercase()
            .replace(Regex("""[^a-z0-9\u4e00-\u9fa5\u0900-\u097f]"""), " ")
            .trim()
    }

    private fun hasLanguageMismatch(title: String, artist: String, lines: List<LyricLine>): Boolean {
        val nonGapLines = lines.filter { !it.isGap }
        if (nonGapLines.isEmpty()) return false

        val queryHasHan = HAN_REGEX.containsMatchIn(title) || HAN_REGEX.containsMatchIn(artist)

        if (!queryHasHan) {
            val hanChars = nonGapLines.sumOf { line -> line.text.count { c -> c in '\u4e00'..'\u9fff' } }
            if (hanChars > 0) {
                AppLogger.w(TAG, "Rejected lyrics for '$title': contains $hanChars Han characters for non-Chinese track")
                return true
            }
        }
        return false
    }

    private val explicitMetadataTagRegex = Regex(
        """(?i)^(?:\[|\()? *(?:""" +
        """engineered(?: by)?|assistant engineer(?:ed)?(?: by)?|recording engineer(?: by)?|""" +
        """mix(?:ed|ing)?(?: by)?|master(?:ed|ing)?(?: by)?|sound engineer(?: by)?|""" +
        """photograph(?:y)?(?: by)?|photo(?:s)?(?: by)?|artwork(?: by)?|cover(?: art)?(?: by)?|""" +
        """directed by|video by|produced by|co-produced by|producer|executive producer(?: by)?|""" +
        """written by|composed by|lyrics by|words by|music by|arranged by|orchestrated by|""" +
        """programmed by|programming by|additional production(?: by)?|additional vocals(?: by)?|""" +
        """recorded (?:at|by)|mixed (?:at|by)|mastered (?:at|by)|studio|""" +
        """synced by|sync by|sync|lrc by|transcribed by|subtitles by|timed by|timing by|""" +
        """copyright|all rights reserved|published by|publisher|label|records|recordings|""" +
        """title|track|song|artist|singer|album|作词|作曲|编曲|歌手|歌曲|专辑|演唱|制作|监制|混音|母带""" +
        """) *(?:[:：\-–—]|by\b|\bat\b|\|) *.*$|""" +
        """(?i)^--+.*(?:lrc|lyrics?).*--+$|""" +
        """(?i)^www\.[a-z0-9\.\-]+\.[a-z]{2,}.*""",
        RegexOption.IGNORE_CASE
    )

    fun calibrateIntroTimings(lines: List<LyricLine>, title: String): List<LyricLine> {
        val cleanT = cleanSearchKeyword(title).lowercase().trim()
        if (cleanT.contains("flashing lights")) {
            // "Flashing Lights" intro hook: 4 identical "Flashing lights" lines at 23.6s, 28.9s, 34.15s, 39.4s
            var flashIndex = 0
            val flashTimestamps = listOf(23_600L, 28_900L, 34_150L, 39_400L)
            val updated = lines.mapNotNull { line ->
                if (line.isGap && line.timeMs in 23_000L..42_000L) {
                    return@mapNotNull null
                }
                val norm = line.text.lowercase().trim()
                if (norm == "flashing lights" && flashIndex < flashTimestamps.size && line.timeMs < 42_000L) {
                    val targetMs = flashTimestamps[flashIndex++]
                    line.copy(timeMs = targetMs, durationMs = 2_600L, words = emptyList())
                } else {
                    line
                }
            }
            return LyricParser.processLinesWithGaps(updated.filter { !it.isGap })
        }
        return lines
    }

    fun filterHeaderMetadata(lines: List<LyricLine>, title: String, artist: String): List<LyricLine> {
        if (lines.isEmpty()) return emptyList()

        val cleanT = normalizeString(cleanSearchKeyword(title))
        val cleanA = normalizeString(cleanSearchKeyword(artist))

        val filtered = lines.filterIndexed { index, line ->
            if (line.isGap) return@filterIndexed true

            val trimmed = line.text.trim()
            val normText = normalizeString(trimmed)
            if (normText.isEmpty()) return@filterIndexed false

            // 1. Explicit production, engineering, photography, and crew credits (applies anywhere in song)
            if (explicitMetadataTagRegex.matches(trimmed)) {
                AppLogger.d(TAG, "Stripped explicit credits/metadata line: '${line.text}'")
                return@filterIndexed false
            }

            // 2. Pure isolated title tag at 0s-3s (metadata tag before vocals start).
            // NOTE: Only stripped if strictly the bare song title alone before intro finishes;
            // actual sung lines containing the song name are preserved.
            if (index <= 1 && line.timeMs <= 3_000L && cleanT.isNotEmpty() && normText == cleanT) {
                val nextLineTime = lines.getOrNull(index + 1)?.timeMs ?: 0L
                if (nextLineTime >= 5_000L || line.durationMs == 0L) {
                    AppLogger.d(TAG, "Stripped isolated title metadata tag at start: '${line.text}'")
                    return@filterIndexed false
                }
            }

            // 3. Isolated artist credit line at 0s
            if (index == 0 && line.timeMs == 0L && cleanA.isNotEmpty() && normText == cleanA) {
                AppLogger.d(TAG, "Stripped isolated artist credit line at 0s: '${line.text}'")
                return@filterIndexed false
            }

            // 4. Intro header tags (first 4 lines / before 25s)
            if (line.timeMs <= 25_000L && index < 4) {
                // Bracketed feat/ft header lines (e.g. "(feat. Dwele)", "[ft. Drake]")
                val isBracketed = trimmed.startsWith("(") || trimmed.startsWith("[") || trimmed.startsWith("（") || trimmed.startsWith("【")
                val cleanNorm = normText.replace(" ", "")
                if ((isBracketed || cleanNorm.startsWith("feat") || cleanNorm.startsWith("ft")) &&
                    (cleanNorm.contains("feat") || cleanNorm.contains("ft") || cleanNorm.contains("featuring"))) {
                    AppLogger.d(TAG, "Stripped feat header line: '${line.text}'")
                    return@filterIndexed false
                }

                // Compound metadata lines like "Song - Artist" or ending with "Lyrics"
                if (cleanT.isNotEmpty() && cleanA.isNotEmpty()) {
                    val hasSeparator = trimmed.contains("-") || trimmed.contains("—") || trimmed.contains("/") || trimmed.contains("|")
                    if (hasSeparator && normText.contains(cleanT) && normText.contains(cleanA)) {
                        AppLogger.d(TAG, "Stripped compound metadata header line: '${line.text}'")
                        return@filterIndexed false
                    }
                }
                if (trimmed.endsWith("lyrics", ignoreCase = true) && (normText.contains(cleanT) || normText.contains(cleanA))) {
                    AppLogger.d(TAG, "Stripped title lyrics header line: '${line.text}'")
                    return@filterIndexed false
                }
            }

            true
        }

        val queryHasHan = HAN_REGEX.containsMatchIn(title) || HAN_REGEX.containsMatchIn(artist)

        val languageSanitized = if (!queryHasHan) {
            filtered.filter { line ->
                line.isGap || !line.text.any { c -> c in '\u4e00'..'\u9fff' }
            }.map { line ->
                val transHasHan = line.translation?.any { c -> c in '\u4e00'..'\u9fff' } ?: false
                if (transHasHan) line.copy(translation = null) else line
            }
        } else {
            filtered
        }

        val gapProcessed = if (languageSanitized.size != lines.size) {
            LyricParser.processLinesWithGaps(languageSanitized.filter { !it.isGap })
        } else {
            languageSanitized
        }
        return gapProcessed.map { line ->
            if (line.isGap) {
                line
            } else {
                val stitched = LyricParser.stitchSyllableGaps(line.words)
                val fixedText = LyricParser.fixWordGaps(line.text)
                line.copy(text = fixedText, words = stitched)
            }
        }
    }

    private fun isValidLyrics(lines: List<LyricLine>): Boolean {
        val nonGap = lines.filter { !it.isGap && it.text.isNotBlank() }
        return nonGap.size >= 3
    }

    /**
     * Clean titles like "Blinding Lights (Official Music Video)", "Tum Se (From \"Laila Majnu\")", or "Farebi (Explicit) ft. Raftaar"
     * to improve search hit rates across all global lyric databases.
     */
    fun cleanSearchKeyword(text: String): String {
        val cleaned = text
            .replace(Regex("""[\(\[\{【（「『][^\)\]\}】）」』]*(?:official|video|audio|lyrics?|visualizer|mv|m/v|hd|4k|full|remastered|remaster|live|deluxe|bonus|anniversary|radio\s+edit|slowed|sped\s+up|speed\s+up|reverb|from\s+|soundtrack|explicit|clean|version|single|ep|album|theme|ost|performance|dance)[^\)\]\}】）」』]*[\)\]\}】）」』]""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*\|\s*.*"""), "")
            .replace(Regex("""\s*-\s*(?:official|video|audio|lyrics?|topic|visualizer|mv|m/v|hd|4k|\d{4}\s*remaster).*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*feat\.?\s+.*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*ft\.?\s+.*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""[\(\[\{【（「『]\s*[\)\]\}】）」』]"""), "")
            .replace(Regex("""["'«“”]"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
        return cleaned.ifBlank { text.trim() }
    }

    private suspend fun fetchZohoCloudTtml(
        videoId: String,
        cleanTitle: String,
        cleanArtist: String,
        durationSec: Int
    ): List<LyricLine> {
        if (videoId.isBlank() || videoId.startsWith("track_") || videoId.startsWith("placeholder_") || isSupabaseBlacklisted(videoId, cleanTitle, cleanArtist)) {
            AppLogger.d(TAG, "Skipping Supabase Cloud TTML: videoId=$videoId (blacklisted or invalid)")
            return emptyList()
        }
        val configuredBase = settingsPreferences?.zohoLyricsBaseUrl?.firstOrNull()?.trim()
        val baseUrl = if (configuredBase.isNullOrBlank()) {
            com.lin0721.linmusic.BuildConfig.SUPABASE_LYRICS_BASE_URL
        } else configuredBase

        return runCatching {
            val ttmlUrl = "${baseUrl.trimEnd('/')}/$videoId.ttml"
            AppLogger.d(TAG, "Querying Supabase Cloud TTML lyrics endpoint: $ttmlUrl")
            val anonKey = com.lin0721.linmusic.BuildConfig.SUPABASE_ANON_KEY
            val responseText = httpClient.get(ttmlUrl) {
                header("User-Agent", "MimikyuApp/1.0 (Android)")
                if (anonKey.isNotBlank()) {
                    header("apikey", anonKey)
                    header("Authorization", "Bearer $anonKey")
                }
            }.bodyAsText()

            if (responseText.isNotBlank() && (responseText.contains("<tt") || responseText.contains("<span") || responseText.contains("<p"))) {
                val parsed = LyricParser.parseTtml(responseText)
                if (isValidLyrics(parsed)) {
                    val lastLine = parsed.lastOrNull { !it.isGap }
                    if (durationSec > 0 && lastLine != null) {
                        val lyricEndSec = (lastLine.timeMs + lastLine.durationMs) / 1000
                        if (lyricEndSec > 0 && kotlin.math.abs(lyricEndSec - durationSec) > 30) {
                            AppLogger.w(TAG, "Supabase TTML rejected for '$cleanTitle': lyric end ${lyricEndSec}s differs from track duration ${durationSec}s > 30s")
                            return@runCatching emptyList()
                        }
                    }
                    parsed
                } else emptyList()
            } else {
                emptyList()
            }
        }.onFailure {
            AppLogger.d(TAG, "Supabase Cloud fetch failed or 404 for videoId=$videoId: ${it.message}")
        }.getOrDefault(emptyList())
    }

    fun saveToDiskCache(
        videoId: String?,
        cleanTitle: String,
        cleanArtist: String,
        lines: List<LyricLine>
    ) {
        if (context == null || lines.isEmpty()) return
        try {
            val file = getDiskCacheFile(videoId, cleanTitle, cleanArtist) ?: return
            val ttmlString = LyricParser.toTtml(lines, cleanTitle, cleanArtist)
            file.parentFile?.mkdirs()
            file.writeText(ttmlString)
            AppLogger.d(TAG, "Cached TTML lyrics to disk: ${file.absolutePath} (${file.length()} bytes)")
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to save TTML to disk cache: ${e.message}")
        }
    }

    fun getDiskCacheFile(
        videoId: String?,
        cleanTitle: String,
        cleanArtist: String
    ): File? {
        val ctx = context ?: return null
        val dir = File(ctx.cacheDir, "lyrics_ttml").apply { mkdirs() }
        return if (!videoId.isNullOrBlank()) {
            File(dir, "$videoId.ttml")
        } else {
            val safeKey = "${cleanTitle}_$cleanArtist".replace(Regex("[^a-zA-Z0-9_]"), "_")
            File(dir, "$safeKey.ttml")
        }
    }
}

// ---- lrclib.net API response model ----

@Serializable
data class LrcLibResponse(
    val id: Long = 0,
    val trackName: String = "",
    val artistName: String = "",
    val albumName: String = "",
    val duration: Double = 0.0,
    val instrumental: Boolean = false,
    val plainLyrics: String? = null,
    val syncedLyrics: String? = null
)
