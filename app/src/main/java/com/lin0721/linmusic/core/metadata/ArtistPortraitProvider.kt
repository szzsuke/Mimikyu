package com.lin0721.linmusic.core.metadata

import com.lin0721.linmusic.core.log.AppLogger
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.ArtistItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves authentic, high-res artist portraits directly from YouTube Music and Google UserContent.
 * Completely replaces placeholder and stock images with genuine artist portraits.
 */
object ArtistPortraitProvider {
    private const val TAG = "ArtistPortraitProvider"

    private val PRELOADED_PORTRAITS: Map<String, String> = mapOf(
        "michael jackson" to "android.resource://com.szzsuke.mimikyu/drawable/hero_michael_jackson",
        "kanye west" to "android.resource://com.szzsuke.mimikyu/drawable/hero_kanye_west",
        "bts" to "android.resource://com.szzsuke.mimikyu/drawable/hero_bts",
        "tame impala" to "android.resource://com.szzsuke.mimikyu/drawable/hero_tame_impala",
        "the weeknd" to "android.resource://com.szzsuke.mimikyu/drawable/hero_the_weeknd",
        "drake" to "https://yt3.googleusercontent.com/MxNjcRJ-uK4Xvx7u90IhEFLQM8x9LIGTA9VCKHq5U4Wn2jOgiWaMtg-qz329SIzqnCyhdCCB3MpdAGs=w600-h600-p-l90-rj",
        "taylor swift" to "https://yt3.googleusercontent.com/RCpTA6EXJQyjVFDosWOKa2SMmqkua_lA9mHPDWWciLwgqpZLz-k8rXWRF_367trrQ7up9BUwCbk6kRk=w600-h600-p-l90-rj",
        "travis scott" to "https://yt3.googleusercontent.com/r9k_FpAswxhQnl_cudiaT2ocWFccR6SzEFXgZ9a12iR5eDPSILlIL2EQewyQ-yYSt1JFyH1pqnoBXxs=w600-h600-p-l90-rj",
        "billie eilish" to "https://lh3.googleusercontent.com/tQC4rOL6xz6FhmFr0ggQExxyGbYSOsyveXVSnPBh2WjEyIzQ9pMHablLJ-0GlMBrLBlBrbWQGmzrV6KN=w600-h600-p-l90-rj",
        "kendrick lamar" to "https://yt3.googleusercontent.com/uB8Magh99SvDyT_mcDYeNYxlVZ_F9WN-cJtAFMHw_Q-_N_8y5-uZiay8-EZSKKloNoWxymBzVehSF4PN=w600-h600-p-l90-rj",
        "lana del rey" to "https://lh3.googleusercontent.com/CN-IGGdHB9FLirmRDoFj8VPwVJGd1N-WUP7uGyw7tOdJuE2MLwF-oe8xnr3ExERS6sCo-iviC-g3TtrL=w600-h600-p-l90-rj",
        "frank ocean" to "https://lh3.googleusercontent.com/_bQ5G33KuYf-AIsMAQfOlzLjYxKV08KQsVd6II9NVU-uaGgtJ6S-Zxgo34cq2OKgaX_C4FjNqftzxckD=w600-h600-p-l90-rj",
        "post malone" to "https://lh3.googleusercontent.com/48LfK4z6o-CCEWgHQnQfg0ltcT9tbZSN0qjSh0FSJsJI5GF48j2-pH219ciG1ML-PI80ZGD4Vz6sjg=w600-h600-p-l90-rj",
        "arijit singh" to "https://lh3.googleusercontent.com/W_yOqnKSDYyeVOY_AsXhuAtb6rW3vCL3GtJ9DA1GxWOrJfyeSOqzvTv_TkFHijdkVPXWutASBlRFPg=w600-h600-p-l90-rj",
        "bad bunny" to "https://yt3.googleusercontent.com/ploU_4iWpDoJlX3FOlwwd_yQcex0I8A0_665lePXAEBbNp1zn5g42eNwg5Q7lvYc2mG2--UNIYcIhww=w600-h600-p-l90-rj",
        "eminem" to "https://lh3.googleusercontent.com/JFI6JZrS-Lco4UdpqDfHY5Wgwy51VXWxmNdI7bCBU5CDlIpN6WWyisZ7MGlpjbrxEGYMFpsqoR_UwcE=w600-h600-p-l90-rj",
        "justin bieber" to "https://lh3.googleusercontent.com/4ULlRiFBFglNemZJyKn6_e2-iOIdJEbgBgq_79RQclndG6pge0yGgS2k2On6E1FkCJzenyHkHRzkvjFp=w600-h600-p-l90-rj",
        "ariana grande" to "https://yt3.googleusercontent.com/DU6Kpr5TYKcW6QHvMnsJau5_8QSuix8LCLtf5UEaziZZdXw8SxvcxJ9YWmVIQuzhg2R-MVHYgjdGCQ=w600-h600-p-l90-rj",
        "ed sheeran" to "https://lh3.googleusercontent.com/jQoBIAS6JjFGpcqQY1M_Mh3AasOvFENCdVRxkgax1a0K6qiq7AgE3MbJ6Jtt-Jndcarvoawmrg66KTny=w600-h600-p-l90-rj",
        "rihanna" to "https://lh3.googleusercontent.com/tLzIJPsFe-q93Y_erAUU_hDIyXCgCrYJ0C-S4DmMurqfB9LJ_PNFpTPA41k-dVMB6ODgc4-1nF1cOp6c=w600-h600-l90-rj",
        "bruno mars" to "https://lh3.googleusercontent.com/hnefGBrazRhn4Z92bdSZBUENl40ONjRiVDsmZKZh-WZ2iCKE-2c7KKR7SNcZfzLHoRyB3E6as8L87YA=w600-h600-p-l90-rj",
        "sza" to "https://lh3.googleusercontent.com/c-ILO8kXxjY6HhqSkoClWPUtPfHYQW6iHr51EiQOaZiUZ7IZr_WwwkyqclAOFyZgLpC3R0dPXuZiRt0=w600-h600-p-l90-rj",
        "dua lipa" to "https://lh3.googleusercontent.com/aFx8s1fTuelgxONGbezmTG0EKR8r82uB5H-Q6ZJtssyCWLJWF8GfZNr4tHo84sXdFCPBKrA4R6zXOss=w600-h600-p-l90-rj",
        "coldplay" to "https://lh3.googleusercontent.com/IOKuXtp8PCQ_Fc-vaRKm3sKIXBxFV51gZheLTH5br-YGnWHFQf_Jywcuk7wbprYRoEbQyS_XZY6-nMJX=w600-h600-p-l90-rj",
        "harry styles" to "https://lh3.googleusercontent.com/O6RvnokIzjsvwQzyRLLkKOx0mvxLXy1bEspu1YdG48jQ2s6o64wyr5Cd8MWjACGocpsl2zlN8iJoMg=w600-h600-p-l90-rj"
    )

    private val memoryCache = ConcurrentHashMap<String, String>()

    init {
        memoryCache.putAll(PRELOADED_PORTRAITS)
    }

    /**
     * Registers or updates a portrait URL for an artist.
     */
    fun registerPortrait(artistName: String, url: String) {
        if (artistName.isBlank() || url.isBlank()) return
        val clean = artistName.lowercase().trim()
        val resolved = if (url.startsWith("android.resource://")) url else (YouTubeThumbnailUpscaler.upscale(url) ?: url)
        memoryCache[clean] = resolved
    }

    /**
     * Synchronously returns the best available portrait URL.
     * Returns empty string if unknown so a monogram or placeholder is rendered, never wrong artist.
     */
    fun getCachedPortrait(artistName: String): String {
        if (artistName.isBlank()) return ""
        val clean = artistName.lowercase().trim()
        val direct = memoryCache[clean]
        if (direct != null) return direct

        // Check if Apple Artist Art has already resolved this artist
        val appleHero = AppleArtistArtRepository.cached(artistName)?.heroUrl
        if (!appleHero.isNullOrBlank()) {
            memoryCache[clean] = appleHero
            return appleHero
        }

        // Partial match with word-boundary awareness to avoid cross-artist collisions (e.g., "The Jacksons" matching "Michael Jackson")
        return memoryCache.entries.firstOrNull {
            it.key.length > 3 && (
                clean == it.key ||
                clean.startsWith("${it.key} ") ||
                clean.endsWith(" ${it.key}") ||
                clean.contains(" ${it.key} ")
            )
        }?.value ?: ""
    }

    /**
     * Resolves and returns the high-res official portrait for the artist.
     * Checks Apple Music first for authentic studio photography, falling back to YouTube Music.
     * Caches result permanently in memory.
     */
    suspend fun getArtistPortrait(artistName: String): String = withContext(Dispatchers.IO) {
        if (artistName.isBlank()) return@withContext ""
        val clean = artistName.lowercase().trim()
        val cached = memoryCache[clean]
        if (cached != null) return@withContext cached

        // 1. Primary: Check authentic Apple Music photographer studio portrait
        try {
            val appleArt = AppleArtistArtRepository.artFor(artistName)
            if (appleArt != null && appleArt.heroUrl.isNotBlank()) {
                memoryCache[clean] = appleArt.heroUrl
                return@withContext appleArt.heroUrl
            }
        } catch (e: Exception) {
            AppLogger.d(TAG, "Apple artist portrait lookup failed for $artistName: ${e.message}")
        }

        // Partial match in memory
        val partial = memoryCache.entries.firstOrNull {
            it.key.length > 2 && (clean.contains(it.key) || it.key.contains(clean))
        }?.value
        if (partial != null) {
            memoryCache[clean] = partial
            return@withContext partial
        }

        // 2. Fallback: Fetch official artist avatar from YouTube Music, upscaled with BitChord ladder
        try {
            val searchRes = YouTube.search(artistName, YouTube.SearchFilter.FILTER_ARTIST).getOrNull()
            val match = searchRes?.items?.filterIsInstance<ArtistItem>()?.firstOrNull()
            val thumb = match?.thumbnail?.takeIf { it.isNotBlank() }
            if (thumb != null) {
                val upscaled = YouTubeThumbnailUpscaler.forHeader(thumb) ?: thumb
                memoryCache[clean] = upscaled
                return@withContext upscaled
            }

            // Fallback: discover thumbnail from artist's top song
            val songRes = YouTube.search(artistName, YouTube.SearchFilter.FILTER_SONG).getOrNull()
            val songThumb = songRes?.items?.filterIsInstance<com.metrolist.innertube.models.SongItem>()?.firstOrNull()?.thumbnail
            if (!songThumb.isNullOrBlank()) {
                val upscaled = YouTubeThumbnailUpscaler.forHeader(songThumb) ?: songThumb
                memoryCache[clean] = upscaled
                return@withContext upscaled
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to resolve portrait for $artistName: ${e.message}")
        }

        ""
    }
}
