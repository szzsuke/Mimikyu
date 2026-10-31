package com.lin0721.linmusic.core.player.recommendation

import com.lin0721.linmusic.core.player.QueueItem
import java.util.Locale

/**
 * Builds the radio station that plays on after a one-off song or at the end of the queue.
 *
 * Direct port of BitChord's QueueBuilder with:
 * - Title & artist deduplication across audio and video cuts (isSameRecording)
 * - Per-artist caps (PER_ARTIST_LIMIT = 2) to prevent one artist dominating the station
 * - Seed artist allowance (SEED_ARTIST_LIMIT = 4)
 * - Audio catalog preference over music video cuts
 */
object BitChordQueueBuilder {

    /** No more than this many tracks by one artist in a single batch. */
    const val PER_ARTIST_LIMIT = 2

    /** The artist the station was seeded on gets more room, but not the run of it. */
    const val SEED_ARTIST_LIMIT = 4

    /** Most AutoPlay-suggested tracks kept queued ahead of the current one at once. */
    const val MAX_QUEUED_AUTOPLAY = 10

    /**
     * The subset of [candidates] worth appending after [existing]: nothing
     * already queued, nothing repeated, at most [limit] tracks.
     */
    fun extend(
        existing: List<QueueItem>,
        candidates: List<QueueItem>,
        limit: Int = MAX_QUEUED_AUTOPLAY
    ): List<QueueItem> {
        val taken = existing.toMutableList()
        val perArtist = mutableMapOf<String, Int>()
        val seedArtists = existing.lastOrNull()?.artist?.let(::artistSet).orEmpty()
        val out = mutableListOf<QueueItem>()

        // A mix pairs tracks with music video uploads - the catalogue audio cut is
        // what a music app wants; videos only stand in when there is nothing else.
        val songsOnly = candidates.filterNot { it.isVideo || isVideoTrack(it) }
        val ordered = songsOnly.ifEmpty { candidates }

        for (candidate in ordered) {
            if (out.size >= limit) break
            if (taken.any { isSameRecording(it, candidate) }) continue

            val artists = artistSet(candidate.artist)
            val key = artists.minOrNull()
            if (key != null) {
                val cap = if (artists.any { it in seedArtists }) {
                    SEED_ARTIST_LIMIT
                } else {
                    PER_ARTIST_LIMIT
                }
                val played = perArtist[key] ?: 0
                if (played >= cap) continue
                perArtist[key] = played + 1
            }

            taken += candidate
            out += candidate
        }
        return out
    }

    /**
     * Whether two entries are the same recording. Video and audio cuts of a song
     * share title and lead artist but differ in videoId or songId.
     */
    fun isSameRecording(a: QueueItem, b: QueueItem): Boolean {
        if (!a.videoId.isNullOrBlank() && !b.videoId.isNullOrBlank() && a.videoId == b.videoId) return true
        if (a.songId > 0 && b.songId > 0 && a.songId == b.songId) return true

        val title = normalisedTitle(a.title)
        if (title.isEmpty() || title != normalisedTitle(b.title)) return false

        // One shared name is enough. Billing order varies between audio and video cuts.
        val left = artistSet(a.artist)
        val right = artistSet(b.artist)
        return left.isEmpty() || right.isEmpty() || left.any { it in right }
    }

    /**
     * Normalises a title by removing noise, parenthetical audio/video tags, and punctuation.
     * e.g. "Kesariya (From \"Brahmastra\") | Official Video" -> "kesariya"
     */
    fun normalisedTitle(raw: String): String = raw.lowercase(Locale.ROOT)
        .substringBefore(" | ")
        .replace(NOISE, " ")
        .replace(PUNCTUATION, " ")
        .replace(SPACES, " ")
        .trim()

    /**
     * Splits artist credits into individual artists, stripping topic channels and punctuation.
     */
    fun artistSet(raw: String): Set<String> = raw.lowercase(Locale.ROOT)
        .replace(TOPIC, " ")
        .split(",", "&", "Â·", "â€¢", ";", " feat", " ft.", " ft ", " x ", " with ")
        .map { it.replace(PUNCTUATION, " ").replace(SPACES, " ").trim() }
        .filter { it.isNotEmpty() }
        .toSet()

    private fun isVideoTrack(item: QueueItem): Boolean {
        val t = item.title.lowercase(Locale.ROOT)
        return t.contains("official video") || t.contains("music video") ||
                t.contains("lyrical video") || t.contains("visualizer")
    }

    private val NOISE = Regex(
        """\((?:official|lyric|lyrics|lyrical|audio|video|visuali[sz]er|full song|hd|4k)[^)]*\)""" +
                """|\(from[^)]*\)""" +
                """|\[[^]]*]""" +
                """|\b(?:official (?:video|audio|music video)|lyrical video|full video|4k video)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val TOPIC = Regex("""\s*-\s*topic\b""", RegexOption.IGNORE_CASE)
    private val PUNCTUATION = Regex("""[^\p{L}\p{N}]+""")
    private val SPACES = Regex("""\s+""")
}

