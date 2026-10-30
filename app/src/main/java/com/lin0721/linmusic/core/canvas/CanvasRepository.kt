package com.lin0721.linmusic.core.canvas

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Finds the looping video that belongs behind a track's or a release's cover art —
 * Apple's motion artwork, Tidal's video cover, Community canvas.
 *
 * Results are cached (misses included) to prevent duplicate lookups on repeated visits.
 */
object CanvasRepository {

    private const val TAG = "CanvasRepository"
    private const val CACHE_SIZE = 64

    private class Entry(val artwork: CanvasArtwork?, val withAlbum: Boolean)

    private val cache = object : LinkedHashMap<String, Entry>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>) =
            size > CACHE_SIZE
    }

    private val lock = Mutex()

    /**
     * Resolves the canvas for a track. Never throws.
     */
    suspend fun canvasFor(
        title: String,
        artist: String,
        album: String? = null,
        videoId: String = ""
    ): CanvasArtwork? {
        val cleanTitle = title.cleaned()
        val cleanArtist = artist.cleaned()
        if (cleanTitle.isBlank() || cleanArtist.isBlank()) return null

        val cacheId = if (videoId.isNotBlank()) videoId else "$cleanTitle|$cleanArtist"
        val key = "song|$cacheId"

        return resolve(key, album != null) {
            firstHit(
                { AppleMusicCanvas.search(cleanTitle, cleanArtist, album) },
                { TidalCanvas.search(cleanTitle, cleanArtist, album) },
                { CommunityCanvas.search(cleanTitle, cleanArtist, album) },
            ) { it.matches(cleanTitle, cleanArtist, album) }
        }
    }

    /**
     * Cached canvas if already resolved, without hitting network.
     */
    fun cached(title: String, artist: String, videoId: String = ""): CanvasArtwork? {
        val cacheId = if (videoId.isNotBlank()) videoId else "${title.cleaned()}|${artist.cleaned()}"
        val key = "song|$cacheId"
        return synchronized(cache) { cache[key]?.artwork }
    }

    /**
     * The canvas for a release/album page header.
     */
    suspend fun canvasForAlbum(album: String, artist: String): CanvasArtwork? {
        val name = album.cleaned()
        val credit = artist.cleaned()
        if (name.isBlank() || credit.isBlank()) return null

        return resolve("album|$name|$credit", withAlbum = true) {
            firstHit(
                { AppleMusicCanvas.searchAlbum(name, credit) },
                { TidalCanvas.searchAlbum(name, credit) },
                { CommunityCanvas.searchAlbum(name, credit) },
            ) { it.matches(name, credit, name) }
        }
    }

    private suspend fun resolve(
        key: String,
        withAlbum: Boolean,
        lookUp: suspend () -> CanvasArtwork?,
    ): CanvasArtwork? = lock.withLock {
        synchronized(cache) {
            cache[key]?.let { if (it.reusable(withAlbum)) return@withLock it.artwork }
        }
        val found = withContext(Dispatchers.IO) { lookUp() }
        synchronized(cache) { cache[key] = Entry(found, withAlbum) }
        found
    }

    private fun Entry.reusable(withAlbum: Boolean): Boolean =
        artwork != null || this.withAlbum || !withAlbum

    private suspend fun firstHit(
        vararg sources: suspend () -> CanvasArtwork?,
        accept: (CanvasArtwork) -> Boolean,
    ): CanvasArtwork? {
        for (source in sources) {
            val found = runCatching { source() }
                .onFailure { Timber.tag(TAG).d("Canvas source error: ${it.message}") }
                .getOrNull()
                ?: continue
            if (!accept(found)) {
                Timber.tag(TAG).d("Rejected canvas '${found.title}' by '${found.artist}'")
                continue
            }
            return found
        }
        return null
    }

    private fun String.cleaned(): String = replace(NOISE, " ")
        .substringBefore(" | ")
        .replace(Regex("""\s+"""), " ")
        .trim()
        .ifBlank { this }

    private val NOISE = Regex(
        """\((?:from|official|lyrical|video|audio)[^)]*\)|\[[^]]*]|""" +
            """\b(?:official (?:video|audio|music video)|lyrical|full song|4k video)\b""",
        RegexOption.IGNORE_CASE,
    )
}
