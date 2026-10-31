package com.lin0721.linmusic.core.download

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

// DownloadSongs
data class DownloadTrackInfo(
    val songId: Long,
    val songName: String,
    val artistName: String,
    val albumName: String = "",
    val coverUrl: String? = null,
    val albumYear: Int = 0,
    val videoId: String? = null
)

// Download progress item with full metadata for UI
data class DownloadProgressItem(
    val workId: UUID,
    val songId: Long = 0L,
    val songName: String,
    val artistName: String = "",
    val coverUrl: String? = null,
    val progress: Int
)

fun yearFromEpochMillis(epochMillis: Long): Int {
    if (epochMillis <= 0) return 0
    if (epochMillis in 1000L..9999L) return epochMillis.toInt()
    val millis = if (epochMillis in 100_000_000L..99_999_999_999L) epochMillis * 1000L else epochMillis
    return runCatching {
        java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneOffset.UTC).year
    }.getOrDefault(0)
}

// SongsDownload
class SongDownloadManager(
    private val context: Context,
    private val downloadPreferences: DownloadPreferences
) {

    companion object {
        private const val TAG_DOWNLOAD = "song_download"
        private fun uniqueWorkName(songId: Long) = "song_download_$songId"
    }

    private val workManager get() = WorkManager.getInstance(context)
    private val trackInfoCache = ConcurrentHashMap<Long, DownloadTrackInfo>()

    suspend fun isDownloaded(songId: Long): Boolean = downloadPreferences.isDownloaded(songId)

    fun getTrackInfo(songId: Long): DownloadTrackInfo? = trackInfoCache[songId]

    fun enqueueSingle(track: DownloadTrackInfo, level: String): UUID {
        trackInfoCache[track.songId] = track
        val request = buildRequest(track, level)
        workManager.enqueueUniqueWork(uniqueWorkName(track.songId), ExistingWorkPolicy.REPLACE, request)
        return request.id
    }

    // Listen & Cache
    fun enqueueStreamCache(track: DownloadTrackInfo, level: String): UUID {
        trackInfoCache[track.songId] = track
        val request = buildRequest(track, level, batchTag = "stream_cache", batchLabel = "Listen & Cache")
        workManager.enqueueUniqueWork(uniqueWorkName(track.songId), ExistingWorkPolicy.KEEP, request)
        return request.id
    }

    // Download batch
    fun enqueueBatch(tracks: List<DownloadTrackInfo>, level: String, batchTag: String, batchLabel: String): List<UUID> =
        tracks.map { track ->
            trackInfoCache[track.songId] = track
            val request = buildRequest(track, level, batchTag = batchTag, batchLabel = batchLabel)
            workManager.enqueueUniqueWork(uniqueWorkName(track.songId), ExistingWorkPolicy.REPLACE, request)
            request.id
        }

    fun cancel(songId: Long) {
        workManager.cancelUniqueWork(uniqueWorkName(songId))
        trackInfoCache.remove(songId)
    }

    fun cancelByWorkId(workId: UUID, songId: Long = 0L) {
        workManager.cancelWorkById(workId)
        if (songId > 0L) {
            workManager.cancelUniqueWork(uniqueWorkName(songId))
            trackInfoCache.remove(songId)
        }
    }

    fun observeBatch(batchTag: String): Flow<List<WorkInfo>> = workManager.getWorkInfosByTagFlow(batchTag)

    // Observe active downloads with songId, name, artist, coverUrl, progress
    fun observeActiveDownloads(): Flow<List<DownloadProgressItem>> =
        workManager.getWorkInfosByTagFlow(TAG_DOWNLOAD).map { infos ->
            infos.filter { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
                .map { info ->
                    val songIdFromTag = info.tags.firstOrNull { it.startsWith("song_id_") }
                        ?.removePrefix("song_id_")?.toLongOrNull() ?: 0L
                    val songId = info.progress.getLong(SongDownloadWorker.KEY_PROGRESS_SONG_ID, songIdFromTag)
                    val cachedTrack = if (songId > 0L) trackInfoCache[songId] else null

                    val name = info.progress.getString(SongDownloadWorker.KEY_PROGRESS_SONG_NAME)
                        ?.takeIf { it.isNotBlank() }
                        ?: cachedTrack?.songName
                        ?: ""
                    val artist = info.progress.getString(SongDownloadWorker.KEY_PROGRESS_ARTIST_NAME)
                        ?.takeIf { it.isNotBlank() }
                        ?: cachedTrack?.artistName
                        ?: ""
                    val cover = info.progress.getString(SongDownloadWorker.KEY_PROGRESS_COVER_URL)
                        ?.takeIf { it.isNotBlank() }
                        ?: cachedTrack?.coverUrl

                    DownloadProgressItem(
                        workId = info.id,
                        songId = songId,
                        songName = name,
                        artistName = artist,
                        coverUrl = cover,
                        progress = info.progress.getInt(SongDownloadWorker.KEY_PROGRESS_PERCENT, 0)
                    )
                }
        }

    fun observeProgress(songId: Long): Flow<Int?> =
        observeActiveDownloads().map { list ->
            list.firstOrNull { it.songId == songId }?.progress
        }.distinctUntilChanged()

    fun observeSingle(songId: Long): Flow<List<WorkInfo>> =
        workManager.getWorkInfosForUniqueWorkFlow(uniqueWorkName(songId))

    private fun buildRequest(
        track: DownloadTrackInfo,
        level: String,
        batchTag: String? = null,
        batchLabel: String? = null
    ) = OneTimeWorkRequestBuilder<SongDownloadWorker>()
        .setInputData(
            SongDownloadWorker.buildInputData(
                track.songId, track.songName, track.artistName, level,
                track.albumName, track.coverUrl, track.albumYear, batchTag, batchLabel,
                track.videoId
            )
        )
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
        .addTag(TAG_DOWNLOAD)
        .addTag("song_id_${track.songId}")
        .apply { batchTag?.let { addTag(it) } }
        .build()
}
