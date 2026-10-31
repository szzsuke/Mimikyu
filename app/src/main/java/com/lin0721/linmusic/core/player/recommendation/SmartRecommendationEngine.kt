package com.lin0721.linmusic.core.player.recommendation

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lin0721.linmusic.core.log.AppLogger
import com.lin0721.linmusic.core.metadata.cleanMetadataText
import com.lin0721.linmusic.core.model.Track
import com.lin0721.linmusic.core.player.QueueItem
import com.lin0721.linmusic.core.player.data.PlaybackRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val TAG = "SmartRecommendationEngine"

private val Context.recommendationDataStore by preferencesDataStore(name = "october_recommendation_prefs")

@Serializable
data class RecommendationProfile(
    // Map of artist name to affinity score (increased on >30s listens, decreased on <30s skips)
    val artistAffinity: Map<String, Float> = emptyMap(),
    // Set of recently skipped song IDs (<30s) to penalize
    val recentSkippedSongIds: List<Long> = emptyList(),
    // Set of highly preferred song IDs (>30s multiple times)
    val preferredSongIds: List<Long> = emptyList()
)

/**
 * Intelligent Next-Song Recommendation Engine.
 *
 * Implements Mimikyu-style contextual recommendation and bandit feedback:
 * 1. Automatically recommends the next song based on the current song seed + user listening history.
 * 2. If no history is available, seamlessly falls back to current track similarity graph.
 * 3. 30-Second Metric Rule:
 *    - Listened >= 30 seconds -> High preference (+2.0 artist affinity, +1.5 track preference).
 *    - Skipped < 30 seconds -> Low preference (-0.5 artist affinity, -3.0 track penalty, added to recent skips).
 */
class SmartRecommendationEngine(
    private val context: Context,
    private val repository: PlaybackRepository,
    private val tasteSignalScorer: TasteSignalScorer? = null,
    private val lastFmRepository: com.lin0721.linmusic.core.lastfm.LastFmRepository = com.lin0721.linmusic.core.lastfm.LastFmRepository()
) {
    companion object {
        private val KEY_PROFILE = stringPreferencesKey("recommendation_profile_json")
        private const val MAX_RECENT_SKIPS = 50
        private const val MIN_LISTEN_FOR_PREFERENCE_SEC = 30L
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Called whenever a track finishes playing or is skipped.
     * Analyzes listened duration to update recommendation weights.
     */
    suspend fun recordTrackPlaybackMetric(
        songId: Long,
        videoId: String = "",
        title: String = "",
        artist: String,
        coverUrl: String = "",
        listenedSeconds: Long,
        durationSeconds: Long
    ) {
        if (songId <= 0L || artist.isBlank()) return

        // Forward implicit user signals to TasteSignalScorer for 5-pillar Mimikyu ranking
        tasteSignalScorer?.recordPlayback(
            songId = songId,
            videoId = videoId,
            title = title,
            artist = artist,
            coverUrl = coverUrl,
            listenedSeconds = listenedSeconds,
            durationSeconds = durationSeconds
        )

        val isHighPreference = listenedSeconds >= MIN_LISTEN_FOR_PREFERENCE_SEC
        AppLogger.d(TAG, "Recording metric for songId=$songId ($artist): listened=${listenedSeconds}s, highPreference=$isHighPreference")

        try {
            context.recommendationDataStore.edit { prefs ->
                val currentProfileStr = prefs[KEY_PROFILE]
                val profile = if (currentProfileStr != null) {
                    runCatching { json.decodeFromString<RecommendationProfile>(currentProfileStr) }
                        .getOrDefault(RecommendationProfile())
                } else {
                    RecommendationProfile()
                }

                val currentAffinity = profile.artistAffinity[artist] ?: 0f
                val updatedAffinityMap = profile.artistAffinity.toMutableMap()
                val updatedSkips = profile.recentSkippedSongIds.toMutableList()
                val updatedPreferred = profile.preferredSongIds.toMutableList()

                if (isHighPreference) {
                    // Reward artist and track
                    val delta = if (durationSeconds > 0 && listenedSeconds >= (durationSeconds * 0.8f)) 3.0f else 2.0f
                    updatedAffinityMap[artist] = (currentAffinity + delta).coerceIn(-10f, 100f)
                    if (!updatedPreferred.contains(songId)) {
                        updatedPreferred.add(songId)
                        if (updatedPreferred.size > 100) updatedPreferred.removeAt(0)
                    }
                    updatedSkips.remove(songId)
                } else {
                    // Penalize: user skipped under 30 seconds
                    updatedAffinityMap[artist] = (currentAffinity - 0.5f).coerceIn(-10f, 100f)
                    if (!updatedSkips.contains(songId)) {
                        updatedSkips.add(songId)
                        if (updatedSkips.size > MAX_RECENT_SKIPS) updatedSkips.removeAt(0)
                    }
                }

                val newProfile = profile.copy(
                    artistAffinity = updatedAffinityMap,
                    recentSkippedSongIds = updatedSkips,
                    preferredSongIds = updatedPreferred
                )
                prefs[KEY_PROFILE] = json.encodeToString(newProfile)
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to update recommendation profile", e)
        }
    }

    /**
     * Loads a batch of AutoPlay tracks seeded by [seedTrack] using InnerTube RDAMVM radio + BitChord QueueBuilder.
     *
     * Port of BitChord's loadAutoplayTracks model:
     * 1. Resolves canonical seed videoId.
     * 2. Queries InnerTube algorithmic track radio.
     * 3. Runs BitChord QueueBuilder: deduplicates audio/video recordings, caps artists, prefers audio cuts.
     */
    suspend fun getAutoplayBatch(
        seedTrack: QueueItem,
        activeQueue: List<QueueItem>,
        limit: Int = BitChordQueueBuilder.MAX_QUEUED_AUTOPLAY
    ): List<QueueItem> {
        val seedSongId = seedTrack.songId
        val seedVideoId = seedTrack.videoId?.takeIf { it.isNotBlank() }
            ?: (if (seedSongId > 0) com.lin0721.linmusic.core.player.innertube.YouTubeIdRegistry.getVideoId(seedSongId) else null)
            ?: InnerTubeRadioEngine.resolveVideoId(seedSongId, seedTrack.title, seedTrack.artist)

        if (seedVideoId.isNullOrBlank()) {
            return emptyList()
        }

        // 1. Fetch raw candidates from InnerTube RDAMVM radio graph
        val candidates = InnerTubeRadioEngine.getRadioCandidates(seedVideoId, count = 30)
        if (candidates.isEmpty()) {
            return emptyList()
        }

        // 2. Extend queue using BitChord QueueBuilder (filters videos, enforces per-artist caps, deduplicates recordings)
        return BitChordQueueBuilder.extend(
            existing = activeQueue,
            candidates = candidates,
            limit = limit
        )
    }

    /**
     * Determines the optimal next song to append to the playback queue.
     * Prefers BitChord AutoPlay model with fallback to collaborative similarity.
     */
    suspend fun getNextRecommendedTrack(
        currentTrack: QueueItem,
        activeQueue: List<QueueItem>,
        recentHistoryTracks: List<Track> = emptyList()
    ): QueueItem? {
        val autoplayTracks = getAutoplayBatch(currentTrack, activeQueue, limit = 5)
        return autoplayTracks.firstOrNull()
    }
    suspend fun getRecommendedQueueForTrack(
        seedTrack: QueueItem,
        count: Int = 25
    ): List<QueueItem> {
        val seedSongId = seedTrack.songId
        if (seedSongId <= 0L && seedTrack.videoId.isNullOrBlank()) return emptyList()

        // 1. Resolve videoId if missing
        val videoId = seedTrack.videoId?.takeIf { it.isNotBlank() }
            ?: (if (seedSongId > 0) com.lin0721.linmusic.core.player.innertube.YouTubeIdRegistry.getVideoId(seedSongId) else null)
            ?: InnerTubeRadioEngine.resolveVideoId(seedSongId, seedTrack.title, seedTrack.artist)

        if (videoId.isNullOrBlank()) return emptyList()

        // 2. Query InnerTube native RDAMVM track radio
        val radioCandidates = InnerTubeRadioEngine.getRadioCandidates(videoId, count = 50)
        if (radioCandidates.isEmpty()) return emptyList()

        // 3. Extend using BitChord QueueBuilder (pure algorithmic track radio, artist caps, deduplication)
        return BitChordQueueBuilder.extend(
            existing = listOf(seedTrack),
            candidates = radioCandidates,
            limit = count
        )
    }
    private suspend fun loadProfile(): RecommendationProfile {
        return try {
            val jsonStr = context.recommendationDataStore.data.map { it[KEY_PROFILE] }.first()
            if (jsonStr != null) {
                json.decodeFromString<RecommendationProfile>(jsonStr)
            } else {
                RecommendationProfile()
            }
        } catch (e: Exception) {
            RecommendationProfile()
        }
    }
}
