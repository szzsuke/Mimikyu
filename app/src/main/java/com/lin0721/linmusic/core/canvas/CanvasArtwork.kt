package com.lin0721.linmusic.core.canvas

import java.text.Normalizer
import java.util.Locale

/**
 * Which provider a clip came from.
 */
enum class CanvasSource {
    APPLE,
    TIDAL,
    COMMUNITY,
    SPOTIFY,
    OTHER
}

/**
 * A looping video that stands in for a track's or album's cover art —
 * what Apple calls motion artwork and Spotify/Tidal call Canvas/video cover.
 *
 * [url] is what the player mounts (e.g. HLS .m3u8 or MP4).
 * [fallbackUrl] is tried once if [url] errors (e.g. Apple's alternate rendition).
 * The metadata fields allow strict matching verification against what's playing.
 */
data class CanvasArtwork(
    val url: String,
    val fallbackUrl: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val source: CanvasSource = CanvasSource.OTHER,
) {
    /**
     * Whether this clip authentically belongs to the track or album we asked about.
     * Title and artists must match once punctuation, case, and accents are normalized.
     */
    fun matches(wantTitle: String, wantArtist: String, wantAlbum: String?): Boolean {
        val titleOk = title == null || wantTitle.isBlank() ||
            title.normalizeForMatch() == wantTitle.normalizeForMatch()

        val titleArtists = splitArtists(wantArtist)
        val ourArtists = splitArtists(artist.orEmpty())
        val artistOk = artist == null || wantArtist.isBlank() ||
            (titleArtists.isNotEmpty() && ourArtists.isNotEmpty() &&
                titleArtists.all { want -> ourArtists.any { it == want } })

        val albumOk = album.isNullOrBlank() || wantAlbum.isNullOrBlank() ||
            album.normalizeForMatch() == wantAlbum.normalizeForMatch()

        return titleOk && artistOk && albumOk
    }
}

/**
 * Normalizes title or artist text for resilient matching across services:
 * Strips combining diacritical marks, case, non-alphanumeric punctuation,
 * and collapses whitespace.
 */
fun String.normalizeForMatch(): String =
    Normalizer.normalize(this, Normalizer.Form.NFD)
        .replace(Regex("""\p{InCombiningDiacriticalMarks}+"""), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("""[^a-z0-9\s]"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()

/**
 * Splits credit strings into individual artist names.
 * Accounts for commas, ampersands, "feat.", "ft.", "featuring", "with", and "x".
 */
fun splitArtists(raw: String): List<String> =
    raw.split(ARTIST_SEPARATORS)
        .map { it.normalizeForMatch() }
        .filter { it.isNotBlank() }

private val ARTIST_SEPARATORS = Regex(
    """(?:\s*,\s*|\s*&\s*|\s+×\s+|\s+x\s+|\bfeat\.?\b|\bft\.?\b|\bfeaturing\b|\bwith\b)""",
    RegexOption.IGNORE_CASE,
)
