package com.lin0721.linmusic.core.metadata

/**
 * Extensible noise patterns to strip non-musical upload metadata tags
 * from song titles and artist names (e.g. from YouTube / YouTube Music).
 *
 * NOTE: Preserves legitimate musical descriptors like:
 * - "(feat. Artist)"
 * - "(Remix)"
 * - "(Acoustic)"
 * - "(Live)"
 */
private val NOISE_PATTERNS = listOf(
    // Match any bracketed tag containing video/audio upload noise:
    // e.g. (Official Video), (Official Music Video), [Official Video], [Lyrics], (Lyric Video),
    // (Visualizer), [Official Audio], [4K], 【MV】, [Dance Video], etc.
    Regex("""(?i)\s*[\(\[\{【（「『][^()\[\]{}【】（）「」『』]*?\b(?:official\s*(?:music\s*|lyric\s*|visualizer\s*|performance\s*)?video|music\s*video|official\s*audio|official\s*visualizer|short\s*version|extended\s*version|lyrics?\s*(?:video)?|visualizer|audio\s*track|audio\s*oficial|video\s*oficial|clip\s*officiel|full\s*video|official\s*mv|mv|m/v|hd\s*video|4k|8k|ultra\s*hd|performance\s*video|dance\s*video|dance\s*performance)[^()\[\]{}【】（）「」『』]*?[\)\]\}】）」』]"""),
    // Trailing unbracketed noise: e.g. " - Official Video", " - Short Version", " | Official Music Video"
    Regex("""(?i)\s*[-–—|/]\s*(?:official\s*(?:music\s*|lyric\s*|visualizer\s*)?video|official\s*audio|music\s*video|short\s*version|lyrics?\s*video|video\s*oficial|clip\s*officiel|official\s*visualizer|mv|m/v).*$"""),
    // Explicit single-word or short brackets like (Lyrics), [Audio], [Video], (MV)
    Regex("""(?i)\s*[\(\[\{【（「『]\s*(?:lyrics?|audio|video|visualizer|hd|4k|mv|m/v)\s*[\)\]\}】）」』]""")
)

private val TRAILING_TOPIC_REGEX = Regex("""(?i)\s*-\s*topic\s*$""")
private val DANGLING_SEPARATORS_REGEX = Regex("""\s*[-–—|/:]\s*$""")
private val LEADING_SEPARATORS_REGEX = Regex("""^\s*[-–—|/:]\s*""")
private val MULTI_SPACE_REGEX = Regex("""\s+""")

/**
 * Comprehensive Unicode regex matching all Emoji characters, pictographs,
 * symbols, Dingbats, flags, and variations.
 */
val EMOJI_REGEX = Regex(
    "[" +
    "\\u2600-\\u27BF\\u2300-\\u23FF\\u2B50-\\u2B55\\u203C\\u2049\\u2122\\u2139\\u2194-\\u2199\\u21A9-\\u21AA" +
    "\\u25AA\\u25AB\\u25B6\\u25C0\\u25FB-\\u25FE\\u2934\\u2935\\u2B05-\\u2B07\\u2B1B\\u2B1C\\u3030\\u303D\\u3297\\u3299" +
    "\\uFE00-\\uFE0F\\u200D\\u20E3" +
    "]|" +
    "[\\uD83C-\\uD83E][\\uDC00-\\uDFFF]"
)

fun stripEmojis(raw: String?): String {
    if (raw.isNullOrBlank()) return ""
    return EMOJI_REGEX.replace(raw, "").trim()
}

/**
 * Cleans YouTube upload noise and strips all emojis from metadata strings (titles and artist names)
 * before UI display and before querying iTunes / MusicBrainz matchers.
 */
fun cleanMetadataText(raw: String?): String {
    if (raw.isNullOrBlank()) return ""

    // 0. Always strip all emojis from title/artist/metadata
    var text = EMOJI_REGEX.replace(raw, "").trim()

    // 1. Always strip trailing "- Topic" (YouTube auto-generated channel suffix)
    text = TRAILING_TOPIC_REGEX.replace(text, "")

    // 2. Strip non-musical upload-noise tags
    for (pattern in NOISE_PATTERNS) {
        text = pattern.replace(text, "")
    }

    // 3. Clean up dangling separator characters left behind (e.g. "Song Name - ")
    text = DANGLING_SEPARATORS_REGEX.replace(text, "")
    text = LEADING_SEPARATORS_REGEX.replace(text, "")

    // 4. Collapse excess whitespace
    text = MULTI_SPACE_REGEX.replace(text, " ").trim()

    // Fallback to original stripped text if non-musical stripping removed everything
    return text.ifBlank { EMOJI_REGEX.replace(raw, "").trim() }
}

val KNOWN_CHANNELS_AND_CURATORS = setOf(
    "i-pop hits", "t-series", "zee music company", "sony music india",
    "speed records", "yrf", "tips official", "tips", "white hill music",
    "geet mp3", "desi music factory", "aditya music", "lahari music",
    "saregama music", "saregama", "eros now music", "eros now",
    "sufiscore", "t-series apna punjab", "various artists", "topic",
    "vevo", "warnermusic", "universal music group", "coke studio"
)

fun isCuratorOrChannel(name: String?): Boolean {
    if (name.isNullOrBlank()) return true
    val lower = name.trim().lowercase()
    if (lower in KNOWN_CHANNELS_AND_CURATORS) return true
    if (lower.endsWith(" records") || lower.endsWith(" music company") ||
        lower.endsWith(" entertainment") || lower.endsWith(" hits") ||
        lower.endsWith(" official") || lower.endsWith(" music")
    ) return true
    return false
}

private val FEAT_REGEX = Regex("""(?i)\s*[\(\[\{]?\s*(?:feat\.?|ft\.?|featuring)\s+([^\)\]\}]+)[\)\]\}]?""")

/**
 * Extracts and separates pure track title and clean artist name.
 * Handles cases where upload title combines "Artist - Title", "Title - Artist",
 * "Artist ft. Collaborator - Title", or where uploader is a channel (e.g. "I-POP Hits").
 */
fun extractCleanTitleAndArtist(rawTitle: String, rawArtist: String): Pair<String, String> {
    val cleanT = cleanMetadataText(rawTitle)
    var cleanA = cleanMetadataText(rawArtist)

    if (isCuratorOrChannel(cleanA)) {
        cleanA = ""
    }

    if (cleanT.isBlank()) return Pair(cleanT, cleanA)

    val compoundSeparator = Regex("""\s+[-–—:|]\s+""")
    if (compoundSeparator.containsMatchIn(cleanT)) {
        val parts = cleanT.split(compoundSeparator, limit = 2).map { it.trim() }
        if (parts.size == 2) {
            val p0 = parts[0]
            val p1 = parts[1]

            // If one part has "ft." or "feat.", it is almost certainly the artist credit with collaborators
            if (FEAT_REGEX.containsMatchIn(p0) && !FEAT_REGEX.containsMatchIn(p1)) {
                val artists = formatFeaturedArtists(p0)
                return Pair(cleanMetadataText(p1), artists)
            } else if (FEAT_REGEX.containsMatchIn(p1) && !FEAT_REGEX.containsMatchIn(p0)) {
                val artists = formatFeaturedArtists(p1)
                return Pair(cleanMetadataText(p0), artists)
            }

            // If artist was empty or a curator, first part is usually artist and second is title
            if (cleanA.isBlank()) {
                return Pair(cleanMetadataText(p1), cleanMetadataText(p0))
            }

            // Check if left part matches the artist
            val p0Lower = p0.lowercase()
            val aLower = cleanA.lowercase()
            if (p0Lower == aLower || p0Lower.contains(aLower) || aLower.contains(p0Lower)) {
                return Pair(cleanMetadataText(p1), cleanA)
            }

            // Check if right part matches the artist
            val p1Lower = p1.lowercase()
            if (p1Lower == aLower || p1Lower.contains(aLower) || aLower.contains(p1Lower)) {
                return Pair(cleanMetadataText(p0), cleanA)
            }
        }
    }

    // Handle title containing "ft." or "feat." without a dash separator
    if (FEAT_REGEX.containsMatchIn(cleanT)) {
        val match = FEAT_REGEX.find(cleanT)
        if (match != null) {
            val featured = match.groupValues.getOrNull(1)?.trim().orEmpty()
            val beforeFeat = cleanT.substring(0, match.range.first).trim()
            val afterFeat = cleanT.substring(match.range.last + 1).trim()

            if (cleanA.isBlank()) {
                // If artist was curator or blank, beforeFeat is lead artist or title
                val artists = if (beforeFeat.isNotBlank() && featured.isNotBlank()) {
                    "$beforeFeat, $featured"
                } else beforeFeat.ifBlank { featured }

                val finalTitle = afterFeat.ifBlank { beforeFeat }
                return Pair(cleanMetadataText(finalTitle), cleanMetadataText(artists))
            } else {
                // If artist is known, strip "ft. ..." from title and append featured to artist
                val cleanTitleWithoutFeat = (beforeFeat + " " + afterFeat).trim()
                val combinedArtist = if (featured.isNotBlank() && !cleanA.contains(featured, ignoreCase = true)) {
                    "$cleanA, $featured"
                } else cleanA
                return Pair(cleanMetadataText(cleanTitleWithoutFeat), cleanMetadataText(combinedArtist))
            }
        }
    }

    return Pair(cleanT, cleanA)
}

private fun formatFeaturedArtists(raw: String): String {
    val match = FEAT_REGEX.find(raw) ?: return cleanMetadataText(raw)
    val lead = raw.substring(0, match.range.first).trim()
    val featured = match.groupValues.getOrNull(1)?.trim().orEmpty()
    return if (lead.isNotBlank() && featured.isNotBlank()) {
        "${cleanMetadataText(lead)}, ${cleanMetadataText(featured)}"
    } else cleanMetadataText(raw)
}

/**
 * Normalizes a track title for deduplication in recommendation rails (The Mimikyu Polish).
 * Strips noise descriptors like (Remastered), (Live), (Radio Edit), (Bonus Track),
 * "feat. Artist", punctuation, and collapses to lowercase alphanumeric.
 */
fun normalizeTrackTitleForDeduplication(raw: String?): String {
    if (raw.isNullOrBlank()) return ""
    var text = cleanMetadataText(raw).lowercase()
    // Strip parenthesized / bracketed version tags
    text = text.replace(Regex("""(?i)\s*[\(\[\{].*?(remaster|live|radio edit|deluxe|version|edit|bonus|feat|ft\.).*?[\)\]\}]"""), "")
    // Strip trailing feat. / ft. clauses
    text = text.replace(Regex("""(?i)\b(feat\.|ft\.|featuring)\b.*"""), "")
    // Strip non-alphanumeric characters
    text = text.replace(Regex("""[^a-z0-9]"""), "")
    return text.trim()
}
