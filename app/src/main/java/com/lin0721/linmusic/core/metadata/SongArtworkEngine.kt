package com.lin0721.linmusic.core.metadata

/**
 * High-precision artwork generation & resolution engine for tracks and albums.
 *
 * Faithfully mirrors BitChord's native artwork logic:
 * 1. Takes the track's authentic artwork URL and upscales to HD (1200x1200) without external catalog lookups.
 * 2. Rewrites YouTube Music (Google UserContent / ggpht) size parameters to `=w1200-h1200-l90-rj` or `=s1200`.
 * 3. Rewrites YouTube video thumbnails (ytimg.com) to `/maxresdefault.jpg` for full HD clarity.
 * 4. Provides ladder rungs matching BitChord: PLAYER_ART_PX (1200), HEADER_ART_PX (720), CARD_ART_PX (480), ROW_ART_PX (160).
 */
object SongArtworkEngine {

    const val ROW_ART_PX = 160
    const val CARD_ART_PX = 480
    const val HEADER_ART_PX = 720
    const val NOTIFICATION_ART_PX = 544
    const val PLAYER_ART_PX = 1200

    val SQUARE_SIZES = listOf(60, 120, 226, 544, 1200)

    private val RESIZABLE_GOOGLE = Regex("""(?:googleusercontent\.com|ggpht\.com)""")
    private val YTIMG_REGEX = Regex("""/(?:default|mqdefault|hqdefault|sddefault)\.jpg""")
    private val TRAILING_EQUAL_PARAM = Regex("""=[^/=]*$""")
    private val SIZE_HINT = Regex("""w\d+-h\d+""")
    private val PARAM_SIZE_HINT = Regex("""=s\d+""")

    fun isGoogleImageHost(url: String): Boolean {
        val host = url.substringAfter("://", "")
            .ifEmpty { url.substringAfter("//", "") }
            .substringBefore('/')
            .substringBefore(':')
            .substringBefore('?')
            .lowercase()
        return host == "ggpht.com" || host.endsWith(".ggpht.com") ||
            host == "googleusercontent.com" || host.endsWith(".googleusercontent.com")
    }

    /**
     * Resolves the best HD properly-sized cover art URL for a song.
     * Takes the track's authentic cover and enhances it to HD (1200px) cleanly without external catalog queries.
     */
    fun resolveCoverArt(
        title: String = "",
        artist: String = "",
        album: String? = null,
        fallbackUrl: String? = null
    ): String {
        return optimizeArtworkUrl(fallbackUrl.orEmpty(), PLAYER_ART_PX)
    }

    fun getCachedCoverArt(title: String, artist: String): String? = null

    fun getCachedOrOptimized(title: String, artist: String, fallbackUrl: String? = null): String {
        return optimizeArtworkUrl(fallbackUrl.orEmpty(), PLAYER_ART_PX)
    }

    /**
     * Optimizes artwork URL to an exact standard cached CDN pixel size.
     * Rewrites Google UserContent parameters up to 1200px studio masters and
     * video thumbnails to maxresdefault.jpg.
     */
    fun optimizeArtworkUrl(url: String, targetPx: Int = PLAYER_ART_PX): String {
        if (url.isBlank()) return url

        var clean = url.trim()
        if (clean.contains("?param=")) {
            clean = clean.substringBefore("?param=")
        }

        // Any URL with w<n>-h<n> size hint (YouTube Music CDN, mirrors, proxies)
        val sized = if (SIZE_HINT.containsMatchIn(clean)) {
            clean.replace(SIZE_HINT, "w$targetPx-h$targetPx")
        } else {
            clean
        }

        // Google UserContent / ggpht (YouTube Music album and artist art)
        if (isGoogleImageHost(clean) || RESIZABLE_GOOGLE.containsMatchIn(clean)) {
            if (PARAM_SIZE_HINT.containsMatchIn(sized)) {
                return sized.replace(PARAM_SIZE_HINT, "=s$targetPx")
            }
            if (SIZE_HINT.containsMatchIn(clean)) {
                return sized
            }
            val withoutQuery = if (clean.contains("?")) clean.substringBefore("?") else clean
            val base = if (TRAILING_EQUAL_PARAM.containsMatchIn(withoutQuery)) {
                withoutQuery.replace(TRAILING_EQUAL_PARAM, "")
            } else {
                withoutQuery
            }
            return "$base=w$targetPx-h$targetPx-l90-rj"
        }

        if (SIZE_HINT.containsMatchIn(clean)) {
            return sized
        }

        // YouTube video thumbnails (i.ytimg.com / img.youtube.com)
        if (clean.contains("ytimg.com") || clean.contains("youtube.com")) {
            return if (targetPx >= 500) {
                if (clean.contains("/hqdefault.jpg") || clean.contains("/default.jpg") || clean.contains("/mqdefault.jpg") || clean.contains("/sddefault.jpg")) {
                    clean.replace(YTIMG_REGEX, "/maxresdefault.jpg")
                } else clean
            } else {
                clean
            }
        }

        // Spotify CDN (scdn.co): upgrade 300px/64px tokens to 640x640
        if (clean.contains("scdn.co") && clean.contains("/image/")) {
            return clean.replace("ab67616d00001e02", "ab67616d0000b273")
                .replace("ab67616d00004851", "ab67616d0000b273")
        }

        // NetEase CDN: upgrade tiny thumbnails to 500x500
        if (clean.contains("163.com")) {
            val base = clean.substringBefore("?")
            return "$base?param=500y500"
        }

        return clean
    }

    /**
     * BitChord sizing ladder helper: upgrades or downscales artwork to the specified resolution.
     */
    fun artworkAt(url: String?, px: Int): String? {
        if (url.isNullOrBlank()) return null
        return optimizeArtworkUrl(url, px)
    }

    fun isStudioCoverUrl(url: String): Boolean {
        if (url.isBlank()) return false
        val lower = url.lowercase()
        return lower.contains("w1200-h1200") ||
            lower.contains("=s1200") ||
            lower.contains("maxresdefault.jpg") ||
            lower.startsWith("content://") ||
            lower.startsWith("file://")
    }
}

/**
 * Extension function matching BitChord's [Song.artworkAt] and [String.artworkAt].
 */
fun String?.artworkAt(px: Int): String? = SongArtworkEngine.artworkAt(this, px)
