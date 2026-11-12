package com.lin0721.linmusic.core.player

import com.lin0721.linmusic.core.log.AppLogger
import com.lin0721.linmusic.core.preferences.SettingsPreferences
import com.lin0721.linmusic.core.player.data.PlaybackRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val TAG = "SimilarRoamingController"

// ：PlayQueueSongs，Queue
class SimilarRoamingController(
    private val scope: CoroutineScope,
    private val repository: PlaybackRepository,
    private val settingsPreferences: SettingsPreferences,
    private val queue: PlaybackQueue,
    private val stateStore: PlaybackStateStore
) {

    companion object {
        const val CONTEXT_ROAMING = "similar_roaming"

        // Current of
        private const val PREFETCH_THRESHOLD_MS = 15000L
    }

    private var roamingJob: Job? = null

    // of Songs，
    private var lastTriggeredSongId = -1L

    val isRoaming: Boolean get() = queue.playContext.value == CONTEXT_ROAMING

    fun cancel() {
        roamingJob?.cancel()
    }

    // Queue，
    fun prepare() {
        queue.takeSnapshot()
        queue.setPlayMode(PlayMode.LIST_LOOP)
        stateStore.savePlayMode(PlayMode.LIST_LOOP)
    }

    // ，Songs
    fun prefetchOnPlay(songId: Long, index: Int) {
        if (!isRoaming) return
        roamingJob = scope.launch {
            appendSimilarSongs(songId, index)
        }
    }

    // PlayQueue
    suspend fun onProgressTick(songId: Long, remainingMs: Long) {
        if (!isRoaming) return
        val curIdx = queue.currentIndex.value
        if (curIdx != queue.size - 1) return
        if (remainingMs > PREFETCH_THRESHOLD_MS) return
        if (lastTriggeredSongId == songId) return
        if (!settingsPreferences.autoPlayNext.first()) return

        lastTriggeredSongId = songId
        appendSimilarSongs(songId, curIdx)
    }

    // Close of Queue
    fun disable() {
        if (!isRoaming) return
        roamingJob?.cancel()
        queue.restoreSnapshot()
        stateStore.saveQueue(queue)
    }

    // SongsPlayQueue
    private suspend fun appendSimilarSongs(songId: Long, index: Int) {
        repository.getSimilarSongs(songId).collect { result ->
            result.onSuccess { simiSongs ->
                if (simiSongs.isNotEmpty() && isRoaming) {
                    val simiItems = simiSongs.map { track ->
                        QueueItem(
                            songId = track.id,
                            title = track.name,
                            artist = track.ar.joinToString("/") { it.name },
                            coverUrl = track.al.picUrl
                        )
                    }
                    queue.append(simiItems)
                    stateStore.saveQueue(queue)
                }
            }.onFailure {
                AppLogger.w(TAG, "Radio continuation of similar songs failed: songId=$songId", it)
            }
        }
    }
}
