package com.lin0721.linmusic.core.metadata

import android.util.Log
import com.lin0721.linmusic.core.canvas.CanvasHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

/**
 * High-definition artist studio art from Apple Music / iTunes.
 *
 * Provides:
 * - [heroUrl]: 1200px+ official photography studio portrait.
 * - [logoUrl]: Typeset vector/transparent typography logo of the artist.
 * - [logoAspect]: Width / height ratio of the logo.
 * - [videoUrl]: Looping motion artist square video backdrop (when available).
 * - [background]: Dominant brand background color for mesh gradients.
 * - [accent]: Accent color for text/highlights.
 * - [keyColor]: Artist play button brand color.
 */
data class AppleArtistArt(
    val heroUrl: String,
    val logoUrl: String? = null,
    val logoAspect: Float = 1f,
    val videoUrl: String? = null,
    val background: Int,
    val accent: Int,
    val keyColor: Int? = null,
)

/**
 * Resolves authentic studio photography and artist logos from Apple Music.
 *
 * Implements BitChord's Apple Artist Art scraper:
 * 1. Queries the iTunes Search API to turn artist name into an Apple Music artist page URL.
 * 2. Fetches the artist's music.apple.com page with desktop User-Agent.
 * 3. Extracts the hydration serialized-server-data JSON payload.
 * 4. Parses the hero photographer portrait (1200px), typography logo (1000px), and video backdrop.
 * 5. Uses an in-memory LRU cache so lookups are fast, non-blocking, and never repeated.
 */
object AppleArtistArtRepository {

    private const val TAG = "AppleArtistArt"
    private const val CACHE_SIZE = 64
    private const val HERO_WIDTH = 1200
    private const val LOGO_WIDTH = 1000
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private class Entry(val art: AppleArtistArt?)

    private val cache = object : LinkedHashMap<String, Entry>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>) =
            size > CACHE_SIZE
    }

    private val _updates = MutableStateFlow(0)
    val updates: StateFlow<Int> = _updates

    /**
     * Synchronously returns cached Apple studio art if already resolved, or null.
     */
    fun cached(artistName: String): AppleArtistArt? {
        val key = artistName.normalized()
        return synchronized(cache) { cache[key] }?.art
    }

    /**
     * Resolves the official Apple Music studio header art and logo for [artistName].
     * Returns null if Apple has none or on network failure. Never throws.
     */
    suspend fun artFor(artistName: String): AppleArtistArt? {
        val key = artistName.normalized()
        if (key.isEmpty()) return null
        synchronized(cache) { cache[key] }?.let { return it.art }

        val entry = withContext(Dispatchers.IO) {
            runCatching { lookup(artistName, key) }
                .onFailure { Log.d(TAG, "Apple artist lookup failed for '$artistName': ${it.message}") }
                .getOrNull()
        } ?: return null

        synchronized(cache) { cache[key] = entry }
        _updates.value++
        return entry.art
    }

    private fun lookup(artistName: String, key: String): Entry? {
        val search = get(
            "https://itunes.apple.com/search?term=${URLEncoder.encode(artistName, "UTF-8")}" +
                "&entity=musicArtist&limit=5",
        ) ?: return null
        val results = JSONObject(search).optJSONArray("results") ?: JSONArray()
        var page: String? = null
        for (i in 0 until results.length()) {
            val r = results.getJSONObject(i)
            if (r.optString("artistName").normalized() == key) {
                page = r.optString("artistLinkUrl").substringBefore('?').takeIf { it.isNotBlank() }
                    ?: r.optLong("artistId").takeIf { it > 0 }?.let { "https://music.apple.com/us/artist/$it" }
                break
            }
        }
        if (page == null) return Entry(null)

        val html = get(page) ?: return null
        return Entry(parse(html))
    }

    private fun parse(html: String): AppleArtistArt? {
        val marker = html.indexOf("id=\"serialized-server-data\"")
        if (marker < 0) return null
        val start = html.indexOf('>', marker) + 1
        val end = html.indexOf("</script>", start)
        if (start <= 0 || end < 0) return null
        val rawJson = html.substring(start, end)
        val header = findHeader(JSONObject("{\"d\":$rawJson}").get("d"))
            ?: return null

        val logo = header.optJSONObject("artistLogo")?.optJSONObject("dictionary")
        val hero = (header.optJSONObject("artwork") ?: header.optJSONObject("colorBackdropArtwork"))
            ?.optJSONObject("dictionary") ?: return null

        val logoW = logo?.optInt("width") ?: 0
        val logoH = logo?.optInt("height") ?: 0
        val heroW = hero.optInt("width").takeIf { it > 0 } ?: return null
        val heroH = hero.optInt("height").takeIf { it > 0 } ?: return null

        val heroUrl = hero.optString("url").fill(
            HERO_WIDTH, (HERO_WIDTH.toLong() * heroH / heroW).toInt(), "jpg",
        ) ?: return null

        val logoUrl = if (logo != null && logoW > 0 && logoH > 0) {
            logo.optString("url").fill(LOGO_WIDTH, (LOGO_WIDTH.toLong() * logoH / logoW).toInt(), "png")
        } else {
            null
        }

        val clip = header.optJSONObject("videoArtwork")?.optJSONObject("dictionary")
            ?.optJSONObject("motionArtistSquare1x1")
        val video = clip?.optString("video")?.takeIf { it.startsWith("http") }

        val colours = clip?.optJSONObject("previewFrame")?.takeIf { video != null } ?: hero
        val background = colours.optString("bgColor").toArgb()
            ?: hero.optString("bgColor").toArgb() ?: return null
        val accent = colours.optString("textColor1").toArgb() ?: background

        return AppleArtistArt(
            heroUrl = heroUrl,
            logoUrl = logoUrl,
            logoAspect = if (logoUrl != null && logoH > 0) logoW.toFloat() / logoH else 1f,
            videoUrl = video,
            background = background,
            accent = accent,
            keyColor = header.optString("keyColor").toArgb(),
        )
    }

    private fun findHeader(node: Any?): JSONObject? {
        when (node) {
            is JSONObject -> {
                if (node.has("artistLogo")) return node
                val keys = node.keys()
                while (keys.hasNext()) findHeader(node.opt(keys.next()))?.let { return it }
            }
            is JSONArray -> for (i in 0 until node.length()) findHeader(node.opt(i))?.let { return it }
        }
        return null
    }

    private fun String.toArgb(): Int? =
        takeIf { it.length == 6 }?.toIntOrNull(16)?.let { 0xFF000000.toInt() or it }

    private fun String.fill(w: Int, h: Int, format: String): String? {
        if (isBlank() || !contains("{w}")) return null
        return replace("{w}", w.toString())
            .replace("{h}", h.toString())
            .replace("{c}", "bb")
            .replace("{f}", format)
    }

    private fun get(url: String): String? {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        return runCatching {
            CanvasHttp.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null else response.body?.string()
            }
        }.getOrNull()
    }

    private fun String.normalized(): String =
        java.text.Normalizer.normalize(lowercase(Locale.ROOT), java.text.Normalizer.Form.NFD)
            .filter { it.isLetterOrDigit() }
}
