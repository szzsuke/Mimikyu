package com.lin0721.linmusic.core.player.recommendation

import com.lin0721.linmusic.core.log.AppLogger
import com.lin0721.linmusic.core.metadata.cleanMetadataText
import com.lin0721.linmusic.core.player.QueueItem
import com.lin0721.linmusic.core.player.innertube.YouTubeIdRegistry
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.WatchEndpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "InnerTubeRadioEngine"

/**
 * Native YouTube Music "Up Next" Radio Engine.
 *
 * Uses InnerTube's WEB_REMIX next endpoint with:
 * - videoId: Seed track
 * - playlistId: "RDAMVM" + videoId (YouTube Music's prefix for infinite, algorithmic track radio)
 *
 * This taps directly into Google's deep-learning music graph for Mimikyu-tier candidate generation.
 */
object InnerTubeRadioEngine {

    /**
     * Fetches radio candidate tracks directly from YouTube Music's next/RDAMVM radio graph.
     */
    suspend fun getRadioCandidates(
        seedVideoId: String,
        count: Int = 30
    ): List<QueueItem> = withContext(Dispatchers.IO) {
        if (seedVideoId.isBlank()) return@withContext emptyList()

        try {
            // Pass RDAMVM + seedVideoId for YouTube Music's algorithmic radio graph
            val radioPlaylistId = if (seedVideoId.startsWith("RDAMVM")) seedVideoId else "RDAMVM$seedVideoId"
            val endpoint = WatchEndpoint(
                videoId = seedVideoId,
                playlistId = radioPlaylistId
            )

            val nextResult = YouTube.next(endpoint).getOrNull()
            val candidateItems = nextResult?.items.orEmpty().filter { it.id.isNotBlank() }

            // If radio items were sparse, check relatedEndpoint
            val finalItems = if (candidateItems.size >= 15) {
                candidateItems
            } else {
                val related = nextResult?.relatedEndpoint?.let { relEndpoint ->
                    YouTube.related(relEndpoint).getOrNull()?.songs.orEmpty()
                } ?: emptyList()
                (candidateItems + related).distinctBy { it.id }
            }

            AppLogger.d(TAG, "InnerTube RDAMVM radio for $seedVideoId returned ${finalItems.size} candidates")

            finalItems.take(count).mapNotNull { song ->
                toQueueItem(song)
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to query InnerTube radio for seed $seedVideoId", e)
            emptyList()
        }
    }

    /**
     * Resolves a YouTube videoId for a song with title and artist if not already known.
     */
    suspend fun resolveVideoId(
        songId: Long,
        title: String,
        artist: String
    ): String? = withContext(Dispatchers.IO) {
        // 1. Check YouTubeIdRegistry
        if (songId > 0) {
            val cached = YouTubeIdRegistry.getVideoId(songId)
            if (!cached.isNullOrBlank()) return@withContext cached
        }

        // 2. Search YouTube Music for the exact track
        val query = "$title $artist".trim()
        if (query.isBlank()) return@withContext null

        return@withContext runCatching {
            val res = YouTube.search(
                query = query,
                filter = YouTube.SearchFilter.FILTER_SONG
            ).getOrNull()

            val match = res?.items?.filterIsInstance<SongItem>()?.firstOrNull()
            if (match != null) {
                val cleanT = cleanMetadataText(match.title)
                val cleanA = cleanMetadataText(match.artists.joinToString(", ") { it.name })
                YouTubeIdRegistry.register(
                    videoId = match.id,
                    title = cleanT,
                    artist = cleanA,
                    coverUrl = match.thumbnail.orEmpty(),
                    durationSec = match.duration ?: 0
                )
                match.id
            } else null
        }.getOrNull()
    }

    private val VIDEO_WORD = Regex("""\bvideos?\b""", RegexOption.IGNORE_CASE)

    private fun toQueueItem(song: SongItem): QueueItem? {
        return try {
            // Drop video uploads and widescreen video clips to ensure pure catalogue audio
            if (song.isVideoSong) return null

            val (cleanTitle, cleanArtist) = com.lin0721.linmusic.core.metadata.extractCleanTitleAndArtist(
                song.title,
                song.artists.joinToString(", ") { it.name }
            )
            if (cleanTitle.isBlank()) return null

            // Discard titles that are purely video compilations
            if (VIDEO_WORD.containsMatchIn(cleanTitle) && !cleanTitle.contains("video games", ignoreCase = true)) {
                return null
            }

            val cachedStudio = com.lin0721.linmusic.core.metadata.SongArtworkEngine.getCachedCoverArt(cleanTitle, cleanArtist)
            val effectiveThumbnail = cachedStudio ?: song.thumbnail.orEmpty()

            val regId = YouTubeIdRegistry.register(
                videoId = song.id,
                title = cleanTitle,
                artist = cleanArtist,
                coverUrl = effectiveThumbnail,
                durationSec = song.duration ?: 0
            )

            val rawCover = YouTubeIdRegistry.getCoverUrl(regId).takeIf { it.isNotBlank() } ?: effectiveThumbnail
            val studioCover = com.lin0721.linmusic.core.metadata.SongArtworkEngine.optimizeArtworkUrl(rawCover, 1200)

            QueueItem(
                songId = regId,
                title = cleanTitle,
                artist = cleanArtist,
                coverUrl = studioCover,
                videoId = song.id,
                album = cleanMetadataText(song.album?.name.orEmpty()),
                durationSec = song.duration ?: 0
            )
        } catch (e: Exception) {
            null
        }
    }
}
