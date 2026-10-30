package com.lin0721.linmusic.core.metadata

/**
 * Utility for upscaling YouTube Music and video thumbnails to high resolution.
 *
 * For YouTube Music (Google UserContent / ggpht), dynamically replaces the size suffix
 * with `=w1200-h1200-l90-rj` (or `=w544-h544-l90-rj`).
 * For YouTube video thumbnails (ytimg.com), replaces standard/low-res filenames
 * with maxresdefault.jpg.
 */
object YouTubeThumbnailUpscaler {

    private val YTIMG_REGEX = Regex("""/(default|mqdefault|hqdefault|sddefault)\.jpg""")

    /**
     * Upscales a thumbnail URL to high resolution (1200x1200 for usercontent or sddefault for ytimg).
     */
    fun upscale(url: String?, targetPx: Int = SongArtworkEngine.PLAYER_ART_PX): String? {
        if (url.isNullOrBlank()) return null
        return SongArtworkEngine.optimizeArtworkUrl(url, targetPx)
    }

    fun forPlayer(url: String?): String? = upscale(url, SongArtworkEngine.PLAYER_ART_PX)
    fun forHeader(url: String?): String? = upscale(url, SongArtworkEngine.HEADER_ART_PX)
    fun forCard(url: String?): String? = upscale(url, SongArtworkEngine.CARD_ART_PX)
    fun forRow(url: String?): String? = upscale(url, SongArtworkEngine.ROW_ART_PX)
    fun forNotification(url: String?): String? = upscale(url, SongArtworkEngine.NOTIFICATION_ART_PX)
}
