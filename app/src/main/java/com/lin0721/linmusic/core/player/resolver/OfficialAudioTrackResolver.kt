package com.lin0721.linmusic.core.player.resolver

import com.lin0721.linmusic.core.log.AppLogger
import com.lin0721.linmusic.core.metadata.YouTubeThumbnailUpscaler
import com.lin0721.linmusic.core.metadata.cleanMetadataText
import com.lin0721.linmusic.core.player.innertube.YouTubeIdRegistry
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_ATV
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Resolves YouTube Music Videos (OMV/UGC) to their canonical Official Audio Track (ATV).
 *
 * Ensures that songs played from playlists:
 * 1. Play pure studio audio without music video skits, pauses, or intros.
 * 2. Carry 1:1 square high-res album covers from Google UserContent.
 * 3. Have exact studio audio duration for 100% synchronized lyrics matching.
 * 4. Map to the canonical ATV video ID used by cloud TTML lyrics and SponsorBlock.
 */
object OfficialAudioTrackResolver {
    private const val TAG = "OfficialAudioTrackResolver"

    data class AtvResolutionResult(
        val videoId: String,
        val title: String,
        val artist: String,
        val durationSec: Int,
        val coverUrl: String,
        val albumName: String? = null,
        val isAtv: Boolean = true
    )

    private val cache = ConcurrentHashMap<String, AtvResolutionResult>()

    /**
     * Pre-populates the cache with a known ATV track to avoid redundant network searches.
     */
    fun cacheAtv(
        videoId: String,
        title: String,
        artist: String,
        durationSec: Int,
        coverUrl: String,
        albumName: String? = null
    ) {
        if (videoId.isBlank()) return
        val cleanT = cleanMetadataText(title)
        val cleanA = cleanMetadataText(artist)
        val upscaledCover = YouTubeThumbnailUpscaler.upscale(coverUrl) ?: coverUrl

        val result = AtvResolutionResult(
            videoId = videoId,
            title = cleanT,
            artist = cleanA,
            durationSec = durationSec,
            coverUrl = upscaledCover,
            albumName = albumName?.let { cleanMetadataText(it) }?.takeIf { it.isNotBlank() },
            isAtv = true
        )
        cache[videoId] = result
        val sig = makeSignature(cleanT, cleanA)
        if (sig.isNotBlank()) cache[sig] = result
    }

    private fun makeSignature(title: String, artist: String): String {
        val t = cleanMetadataText(title).lowercase().trim()
        val a = cleanMetadataText(artist).lowercase().trim()
        return if (t.isNotBlank()) "$t|||$a" else ""
    }

    /**
     * Resolves an official audio track (ATV) for the given song.
     * If the song is already an ATV or cached, returns immediately.
     * Otherwise, queries YouTube with [YouTube.SearchFilter.FILTER_SONG] to find the studio track.
     */
    suspend fun resolve(
        rawVideoId: String?,
        rawTitle: String,
        rawArtist: String,
        currentDurationSec: Int = 0,
        currentCoverUrl: String? = null
    ): AtvResolutionResult? {
        val cleanT = cleanMetadataText(rawTitle)
        val cleanA = cleanMetadataText(rawArtist)
        val cleanVid = rawVideoId?.let { YouTubeIdRegistry.sanitizeVideoId(it) }?.takeIf { it.length == 11 }

        // 1. In-memory cache hit by videoId
        if (cleanVid != null) {
            cache[cleanVid]?.let { return it }
        }

        // 2. In-memory cache hit by title + artist signature
        val sig = makeSignature(cleanT, cleanA)
        if (sig.isNotBlank()) {
            cache[sig]?.let {
                if (cleanVid != null) cache[cleanVid] = it
                return it
            }
        }

        if (cleanT.isBlank()) return null

        // 3. Search for official studio audio track using YouTube.SearchFilter.FILTER_SONG
        return runCatching {
            val query = if (cleanA.isNotBlank()) "$cleanT $cleanA" else cleanT
            AppLogger.i(TAG, "Resolving canonical ATV audio track for '$query' (rawVid=$cleanVid)")

            val searchResult = YouTube.search(query, YouTube.SearchFilter.FILTER_SONG).getOrNull()
            val songItems = searchResult?.items?.filterIsInstance<SongItem>().orEmpty()

            if (songItems.isEmpty()) {
                AppLogger.w(TAG, "No ATV song items found for query '$query'")
                return@runCatching null
            }

            val isExplicitLive = cleanT.contains("live", ignoreCase = true)
            val isExplicitAcoustic = cleanT.contains("acoustic", ignoreCase = true)
            val isExplicitRemix = cleanT.contains("remix", ignoreCase = true)

            // Filter out unwanted variations unless explicitly in track title
            val filtered = songItems.filter { song ->
                val tLower = song.title.lowercase()
                if (!isExplicitLive && (tLower.contains("(live") || tLower.contains("[live") || tLower.contains("live at"))) return@filter false
                if (!isExplicitAcoustic && (tLower.contains("(acoustic") || tLower.contains("[acoustic"))) return@filter false
                if (!isExplicitRemix && (tLower.contains("(remix") || tLower.contains("[remix"))) return@filter false

                val d = song.duration ?: 0
                // Reject extremely short previews (< 40s) or long mixes (> 900s)
                d == 0 || d in 40..900
            }

            val pool = if (filtered.isNotEmpty()) filtered else songItems

            // Best match ranking:
            // 1. Closest duration match if current duration is known (> 30s)
            // 2. Contains primary artist name
            // 3. Official album presence
            val primaryArtist = cleanA.split(",", "/", "&").firstOrNull()?.trim()?.lowercase().orEmpty()
            val candidate = pool.minByOrNull { song ->
                var penalty = 0
                val songDur = song.duration ?: 0
                if (currentDurationSec > 30 && songDur > 30) {
                    penalty += abs(songDur - currentDurationSec)
                }
                val artistMatches = song.artists.any { it.name.lowercase().contains(primaryArtist) }
                if (!artistMatches && primaryArtist.isNotBlank()) {
                    penalty += 100
                }
                if (song.album == null) {
                    penalty += 30
                }
                penalty
            } ?: pool.first()

            val atvVideoId = candidate.id
            val (cleanCandidateTitle, cleanCandidateArtist) = com.lin0721.linmusic.core.metadata.extractCleanTitleAndArtist(
                candidate.title,
                candidate.artists.joinToString(", ") { it.name }
            )
            val atvTitle = cleanCandidateTitle.ifBlank { cleanT }
            val atvArtist = cleanCandidateArtist.ifBlank { cleanA }
            val atvDuration = candidate.duration?.takeIf { it > 0 } ?: currentDurationSec
            val atvAlbum = cleanMetadataText(candidate.album?.name ?: "")
            val atvCover = com.lin0721.linmusic.core.metadata.SongArtworkEngine.optimizeArtworkUrl(
                candidate.thumbnail.ifBlank { currentCoverUrl.orEmpty() },
                com.lin0721.linmusic.core.metadata.SongArtworkEngine.PLAYER_ART_PX
            )

            val result = AtvResolutionResult(
                videoId = atvVideoId,
                title = atvTitle,
                artist = atvArtist,
                durationSec = atvDuration,
                coverUrl = atvCover,
                albumName = atvAlbum.takeIf { it.isNotBlank() },
                isAtv = true
            )

            // Cache under original videoId (so subsequent plays of this playlist track skip search),
            // canonical ATV videoId, and signature
            if (cleanVid != null) cache[cleanVid] = result
            cache[atvVideoId] = result
            if (sig.isNotBlank()) cache[sig] = result

            // Register in YouTubeIdRegistry so player, metadata and lyrics all resolve canonical ATV
            YouTubeIdRegistry.register(
                videoId = atvVideoId,
                title = result.title,
                artist = result.artist,
                durationSec = result.durationSec,
                album = result.albumName.orEmpty(),
                coverUrl = result.coverUrl
            )

            AppLogger.i(TAG, "Resolved ATV for '$cleanT': rawVid=$cleanVid -> canonicalVid=$atvVideoId, dur=${result.durationSec}s, art=${result.coverUrl.take(45)}...")
            result
        }.getOrNull()
    }
}
