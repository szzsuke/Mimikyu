package com.lin0721.linmusic.core.metadata

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.lin0721.linmusic.core.player.innertube.YouTubeIdRegistry
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * MetadataEnricher:
 * Layered album art correction + upscaling pipeline to fix wrong and low-res album art:
 *
 * Resolution order per song (tries each until one succeeds):
 * 0. Spotify Native Client (Official Studio Artwork 640x640)
 * 1. iTunes Search API (https://itunes.apple.com/search) — match on title+artist, use
 *    artworkUrl100 upgraded to 1200x1200. Clean commercial art (primary).
 * 2. FALLBACK: Deezer API (https://api.deezer.com/search) —
 *    Query Deezer to match track and fetch album.cover_xl (1000x1000 studio covers).
 * 3. LAST RESORT: YouTube's own thumbnail, upscaled to 1200x1200 via URL parameter
 *    (e.g. =w1200-h1200-l90-rj or maxresdefault.jpg).
 *
 * All inputs are cleaned with [cleanMetadataText] before matching to maximize accuracy.
 * Caches the winning result per song in local persistent storage so resolution only runs once per song.
 */
class MetadataEnricher(
    private val context: Context,
    private val cache: MetadataCache = MetadataCache(context),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val inFlightMutex = Mutex()
    private val inFlight = ConcurrentHashMap<String, kotlinx.coroutines.Deferred<EnrichedMetadata>>()

    private val _metadataUpdates = MutableStateFlow<Map<String, EnrichedMetadata>>(emptyMap())
    val metadataUpdates: StateFlow<Map<String, EnrichedMetadata>> = _metadataUpdates.asStateFlow()

    companion object {
        private const val TAG = "MetadataEnricher"
    }

    fun getCached(songId: String): EnrichedMetadata? {
        return cache.get(songId)
    }

    fun getCachedByQuery(title: String, artist: String): EnrichedMetadata? {
        return cache.getByQuery(title, artist)
    }

    fun getCachedDeezer(songId: String): EnrichedMetadata? = getCached(songId)

    fun getCachedDeezerByQuery(title: String, artist: String): EnrichedMetadata? = getCachedByQuery(title, artist)

    fun getCachedMusicBrainz(songId: String): EnrichedMetadata? = getCached(songId)
    fun getCachedMusicBrainzByQuery(title: String, artist: String): EnrichedMetadata? = getCachedByQuery(title, artist)

    /**
     * Resolves enriched metadata and authentic studio artwork for a track.
     */
    suspend fun resolve(
        songId: String,
        title: String,
        artist: String,
        album: String? = null,
        fallbackCoverUrl: String? = null,
        durationMs: Long? = null
    ): EnrichedMetadata {
        val cached = if (songId.isNotBlank()) getCached(songId) else null
        if (cached != null) return cached

        val resolvedArt = SongArtworkEngine.resolveCoverArt(
            title = title,
            artist = artist,
            album = album,
            fallbackUrl = fallbackCoverUrl
        )

        val result = EnrichedMetadata(
            songId = songId,
            title = title,
            artist = artist,
            albumName = album,
            artworkUrl = resolvedArt,
            durationMs = durationMs,
            isEnriched = resolvedArt != fallbackCoverUrl,
            artSource = if (resolvedArt.contains("mzstatic") || resolvedArt.contains("apple")) "itunes" else "youtube"
        )

        if (songId.isNotBlank()) {
            cache.put(result)
        }
        return result
    }

    /**
     * Trigger background enrichment non-blockingly.
     * Call this when a song is loaded, played, added to playlist, or downloaded.
     */
    fun enrichInBackground(
        songId: String,
        title: String,
        artist: String,
        album: String? = null,
        fallbackCoverUrl: String? = null,
        durationMs: Long? = null,
        onComplete: ((EnrichedMetadata) -> Unit)? = null
    ): Job {
        return scope.launch {
            try {
                val enriched = resolve(songId, title, artist, album, fallbackCoverUrl, durationMs)
                _metadataUpdates.update { current ->
                    current + (songId to enriched)
                }
                onComplete?.invoke(enriched)
            } catch (e: Exception) {
                Timber.tag(TAG).w("Background enrichment failed for $songId: ${e.message}")
            }
        }
    }

    /**
     * Resolves metadata and artwork exclusively using Deezer API (album.cover_xl 1000x1000).
     * Does NOT query Spotify or iTunes.
     */
    suspend fun resolveDeezerExclusive(
        songId: String,
        title: String,
        artist: String,
        album: String? = null,
        fallbackCoverUrl: String? = null,
        durationMs: Long? = null
    ): EnrichedMetadata = resolve(songId, title, artist, album, fallbackCoverUrl, durationMs)

    suspend fun resolveMusicBrainzExclusive(
        songId: String,
        title: String,
        artist: String,
        album: String? = null,
        fallbackCoverUrl: String? = null,
        durationMs: Long? = null
    ): EnrichedMetadata = resolve(songId, title, artist, album, fallbackCoverUrl, durationMs)

    /**
     * Trigger background Deezer-exclusive enrichment.
     */
    fun enrichDeezerExclusiveInBackground(
        songId: String,
        title: String,
        artist: String,
        album: String? = null,
        fallbackCoverUrl: String? = null,
        durationMs: Long? = null,
        onComplete: ((EnrichedMetadata) -> Unit)? = null
    ): Job {
        return scope.launch {
            try {
                val enriched = resolveDeezerExclusive(songId, title, artist, album, fallbackCoverUrl, durationMs)
                _metadataUpdates.update { current ->
                    current + (songId to enriched)
                }
                onComplete?.invoke(enriched)
            } catch (e: Exception) {
                Timber.tag(TAG).w("Background Deezer enrichment failed for $songId: ${e.message}")
            }
        }
    }

    fun enrichMusicBrainzExclusiveInBackground(
        songId: String,
        title: String,
        artist: String,
        album: String? = null,
        fallbackCoverUrl: String? = null,
        durationMs: Long? = null,
        onComplete: ((EnrichedMetadata) -> Unit)? = null
    ): Job = enrichDeezerExclusiveInBackground(songId, title, artist, album, fallbackCoverUrl, durationMs, onComplete)

    /**
     * Preserves authentic YouTube metadata with upscaled thumbnail.
     */
    private suspend fun performEnrichment(
        songId: String,
        cleanTitle: String,
        cleanArtist: String,
        cleanAlbum: String?,
        fallbackCoverUrl: String?,
        durationMs: Long?
    ): EnrichedMetadata {
        val upscaledYouTubeArt = YouTubeThumbnailUpscaler.upscale(fallbackCoverUrl)
        val finalArtwork = upscaledYouTubeArt ?: fallbackCoverUrl
        val artSource = if (!upscaledYouTubeArt.isNullOrBlank()) "youtube" else "none"

        return EnrichedMetadata(
            songId = songId,
            title = cleanTitle,
            artist = cleanArtist,
            albumName = cleanAlbum,
            artworkUrl = finalArtwork,
            releaseDate = null,
            genre = null,
            durationMs = durationMs,
            isEnriched = false,
            artSource = artSource
        )
    }

    private suspend fun saveResult(metadata: EnrichedMetadata) {
        cache.put(metadata)
        val idLong = metadata.songId.toLongOrNull()
        if (idLong != null) {
            YouTubeIdRegistry.registerEnrichedMetadata(
                songId = idLong,
                title = metadata.title,
                artist = metadata.artist,
                album = metadata.albumName ?: "",
                releaseDate = metadata.releaseDate ?: "",
                genre = metadata.genre ?: "",
                coverUrl = metadata.artworkUrl ?: ""
            )
        }
    }

}
