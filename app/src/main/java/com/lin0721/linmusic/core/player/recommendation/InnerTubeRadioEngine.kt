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
 * Direct port of BitChord's radio candidate retrieval tapping into Google's deep-learning music graph.
 */
object InnerTubeRadioEngine {

    /**
     * Fetches radio candidate tracks directly from YouTube Music's next/RDAMVM radio graph.
     */
    suspend fun getRadioCandidates(
        seedVideoId: String,
        count: Int = 35
    ): List<QueueItem> = withContext(Dispatchers.IO) {
        if (seedVideoId.isBlank()) return@withContext emptyList()

        try {
            // 1. Primary: Try RDAMVM algorithmic radio endpoint (exact BitChord radio mix)
            val radioPlaylistId = if (seedVideoId.startsWith("RDAMVM")) seedVideoId else "RDAMVM$seedVideoId"
            val endpoint = WatchEndpoint(
                videoId = seedVideoId,
                playlistId = radioPlaylistId
            )

            val primaryResult = YouTube.next(endpoint).getOrNull()
            var candidateItems = primaryResult?.items.orEmpty().filter { it.id.isNotBlank() }

            // 2. Secondary fallback: If RDAMVM was empty or refused, query bare video next endpoint
            if (candidateItems.isEmpty()) {
                val fallbackEndpoint = WatchEndpoint(videoId = seedVideoId)
                val fallbackResult = YouTube.next(fallbackEndpoint).getOrNull()
                candidateItems = fallbackResult?.items.orEmpty().filter { it.id.isNotBlank() }
            }

            // 3. Related tracks: augment with related songs if sparse
            val relatedItems = if (candidateItems.size < 15) {
                primaryResult?.relatedEndpoint?.let { relEndpoint ->
                    YouTube.related(relEndpoint).getOrNull()?.songs.orEmpty()
                } ?: emptyList()
            } else emptyList()

            val combined = (candidateItems + relatedItems)
                .filter { it.id.isNotBlank() && it.id != seedVideoId }
                .distinctBy { it.id }

            AppLogger.d(TAG, "InnerTube radio for $seedVideoId returned ${combined.size} raw candidates")

            combined.take(count).mapNotNull { song ->
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

        // 2. Clean query using BitChord title & artist normalizers for precise matching
        val normTitle = BitChordQueueBuilder.normalisedTitle(title)
        val primaryArtist = BitChordQueueBuilder.artistSet(artist).firstOrNull().orEmpty()
        val query = if (primaryArtist.isNotBlank()) "$normTitle $primaryArtist" else normTitle
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
            val rawTitle = cleanMetadataText(song.title)
            val rawArtist = cleanMetadataText(song.artists.joinToString(", ") { it.name })
            if (rawTitle.isBlank()) return null

            val (extractedTitle, extractedArtist) = com.lin0721.linmusic.core.metadata.extractCleanTitleAndArtist(
                rawTitle,
                rawArtist
            )

            val cleanTitle = if (extractedTitle.isNotBlank()) extractedTitle else rawTitle
            val cleanArtist = if (extractedArtist.isNotBlank()) extractedArtist else rawArtist.ifBlank { "Unknown Artist" }

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
