package com.lin0721.linmusic.core.download

import android.content.ContentValues
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.kyant.taglib.Picture
import com.kyant.taglib.TagLib
import com.lin0721.linmusic.core.download.data.DownloadApi
import com.lin0721.linmusic.core.log.AppLogger
import com.lin0721.linmusic.core.lyrics.ExternalLyricsService
import com.lin0721.linmusic.core.lyrics.toAccompanistSyncedLyrics
import com.lin0721.linmusic.core.player.data.PlaybackRepository
import com.lin0721.linmusic.core.player.domain.LyricParser
import com.lin0721.linmusic.core.player.innertube.AudioQuality
import com.lin0721.linmusic.core.player.innertube.InnerTubeXPlayer
import com.lin0721.linmusic.core.player.innertube.YouTubeIdRegistry
import com.lin0721.linmusic.core.preferences.SettingsPreferences
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.mocharealm.accompanist.lyrics.core.exporter.TTMLExporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

private const val TAG = "SongDownloadWorker"
private const val MAX_ATTEMPTS = 3

class SongDownloadWorker(
    context: Context,
    params: WorkerParameters,
    private val downloadApi: DownloadApi,
    private val downloadPreferences: DownloadPreferences,
    private val settingsPreferences: SettingsPreferences,
    private val notificationHelper: DownloadNotificationHelper,
    private val playbackRepository: PlaybackRepository,
    private val downloadClient: OkHttpClient,
    private val externalLyricsService: ExternalLyricsService
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_SONG_ID = "song_id"
        const val KEY_SONG_NAME = "song_name"
        const val KEY_ARTIST_NAME = "artist_name"
        const val KEY_ALBUM_NAME = "album_name"
        const val KEY_COVER_URL = "cover_url"
        const val KEY_ALBUM_YEAR = "album_year"
        const val KEY_LEVEL = "level"
        const val KEY_ERROR = "error"
        const val KEY_BATCH_TAG = "batch_tag"
        const val KEY_BATCH_LABEL = "batch_label"
        const val KEY_VIDEO_ID = "video_id"
        const val KEY_PROGRESS_SONG_ID = "progress_song_id"
        const val KEY_PROGRESS_SONG_NAME = "progress_song_name"
        const val KEY_PROGRESS_ARTIST_NAME = "progress_artist_name"
        const val KEY_PROGRESS_COVER_URL = "progress_cover_url"
        const val KEY_PROGRESS_PERCENT = "progress_percent"

        fun buildInputData(
            songId: Long,
            songName: String,
            artistName: String,
            level: String,
            albumName: String = "",
            coverUrl: String? = null,
            albumYear: Int = 0,
            batchTag: String? = null,
            batchLabel: String? = null,
            videoId: String? = null
        ) = workDataOf(
            KEY_SONG_ID to songId,
            KEY_SONG_NAME to songName,
            KEY_ARTIST_NAME to artistName,
            KEY_ALBUM_NAME to albumName,
            KEY_COVER_URL to coverUrl,
            KEY_ALBUM_YEAR to albumYear,
            KEY_LEVEL to level,
            KEY_BATCH_TAG to batchTag,
            KEY_BATCH_LABEL to batchLabel,
            KEY_VIDEO_ID to videoId
        )
    }

    private val songId: Long get() = inputData.getLong(KEY_SONG_ID, -1)
    private val songName: String get() = inputData.getString(KEY_SONG_NAME) ?: ""
    private val artistName: String get() = inputData.getString(KEY_ARTIST_NAME) ?: ""
    private val albumName: String get() = inputData.getString(KEY_ALBUM_NAME) ?: ""
    private val coverUrl: String? get() = inputData.getString(KEY_COVER_URL)
    private val albumYear: Int get() = inputData.getInt(KEY_ALBUM_YEAR, 0)
    private val level: String get() = inputData.getString(KEY_LEVEL) ?: "standard"
    private val batchTag: String? get() = inputData.getString(KEY_BATCH_TAG)
    private val batchLabel: String get() = inputData.getString(KEY_BATCH_LABEL) ?: "Songs"
    private val inputVideoId: String? get() = inputData.getString(KEY_VIDEO_ID)

    override suspend fun doWork(): Result {
        val songId = this.songId
        if (songId <= 0 && songName.isBlank()) {
            AppLogger.e(TAG, "Download task missing parameters: songId=$songId songName=$songName")
            return Result.failure(workDataOf(KEY_ERROR to "Missing parameters"))
        }

        if (batchTag != null && batchTag != "stream_cache") {
            setForegroundAsync(buildForegroundInfo(0))
        }
        publishProgress(0)

        var localTemp: File? = null
        var cleanup: (() -> Unit)? = null
        try {
            val cm = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val effectiveVideoId = inputVideoId
                ?: YouTubeIdRegistry.getVideoId(songId)
                ?: resolveVideoIdFallback()

            val (streamUrl, extension) = resolveStreamUrl(effectiveVideoId, cm)
            val displayName = "${sanitizeFileName("$artistName - $songName")}.$extension"
            val mimeType = mimeTypeFor(extension)

            val tempFile = File(applicationContext.cacheDir, "dl_${songId}_${System.currentTimeMillis()}.$extension")
            localTemp = tempFile
            val downloadedSize = downloadToFile(tempFile, streamUrl)
            if (downloadedSize == null) {
                onTerminalFailure("Download interrupted")
                return Result.failure(workDataOf(KEY_ERROR to "Download interrupted"))
            }

            // Tagging with metadata & cover art
            runCatching { writeTags(tempFile, extension) }
                .onFailure { AppLogger.w(TAG, "Failed to write tags for songId=$songId", it) }
            val finalFileSize = tempFile.length()

            // Fetch and generate syllable-synced TTML and LRC lyrics
            val (ttmlText, lrcText) = runCatching {
                fetchLyrics(songId, effectiveVideoId)
            }.getOrDefault(null to null)

            val customFolderUri = settingsPreferences.downloadFolderUri.first()
            val finalUri: Uri? = if (customFolderUri != null) {
                val directory = resolveCustomDirectory(customFolderUri)
                if (directory == null) {
                    onTerminalFailure("Custom download directory unavailable. Please reselect in Settings.")
                    return Result.failure(workDataOf(KEY_ERROR to "Custom download directory unavailable"))
                }
                val finalName = uniqueNameIn(directory, displayName)
                val doc = runCatching { directory.createFile(mimeType, finalName) }.getOrNull()
                if (doc == null) {
                    onTerminalFailure("Failed to create local file")
                    return Result.failure(workDataOf(KEY_ERROR to "SAF createFile failed"))
                }
                cleanup = { doc.delete() }
                if (copyFileToUri(tempFile, doc.uri)) {
                    writeLyricsToDirectory(directory, finalName, ttmlText, lrcText)
                    doc.uri
                } else {
                    null
                }
            } else {
                // Save to app-private downloads directory with .nomedia so phone music player / MediaStore cannot see it
                val privateDir = File(
                    applicationContext.getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC)
                        ?: File(applicationContext.filesDir, "music"),
                    "MimikyuDownloads"
                ).apply {
                    mkdirs()
                    runCatching { File(this, ".nomedia").createNewFile() }
                }
                val finalName = uniqueFileNameIn(privateDir, displayName)
                val targetFile = File(privateDir, finalName)
                cleanup = { targetFile.delete() }
                if (runCatching { tempFile.copyTo(targetFile, overwrite = true) }.isSuccess) {
                    writeLyricsToPrivateDirectory(privateDir, finalName, ttmlText, lrcText)
                    Uri.fromFile(targetFile)
                } else {
                    null
                }
            }

            if (finalUri == null) {
                cleanup?.invoke()
                onTerminalFailure("Download interrupted")
                return Result.failure(workDataOf(KEY_ERROR to "Download interrupted"))
            }

            downloadPreferences.addRecord(
                DownloadRecord(
                    songId = songId,
                    mediaStoreUri = finalUri.toString(),
                    quality = level,
                    downloadedAt = System.currentTimeMillis(),
                    fileSize = finalFileSize,
                    songName = songName,
                    artistName = artistName,
                    coverUrl = coverUrl.orEmpty()
                )
            )

            onTerminalSuccess()
            return Result.success()
        } catch (e: CancellationException) {
            cleanup?.invoke()
            throw e
        } catch (e: Exception) {
            AppLogger.e(TAG, "Download error: songId=$songId", e)
            cleanup?.invoke()
            return if (runAttemptCount < MAX_ATTEMPTS) {
                Result.retry()
            } else {
                onTerminalFailure(e.message ?: "Download exception")
                Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Download exception")))
            }
        } finally {
            localTemp?.delete()
        }
    }

    private suspend fun resolveVideoIdFallback(): String? = withContext(Dispatchers.IO) {
        val q = "$artistName - $songName".trim().ifBlank { songName }
        runCatching {
            YouTube.search(q, YouTube.SearchFilter.FILTER_SONG).getOrNull()
                ?.items?.filterIsInstance<SongItem>()?.firstOrNull()?.id
        }.getOrNull()
    }

    private suspend fun resolveStreamUrl(
        videoId: String?,
        cm: ConnectivityManager
    ): Pair<String, String> {
        val deezerArl = settingsPreferences.deezerArl.first()
        val tidalToken = settingsPreferences.tidalToken.first()

        if (level == "lossless" && deezerArl.isNotBlank()) {
            val deezerUrl = tryDeezerDownloadUrl(artistName, songName)
            if (deezerUrl != null) return deezerUrl to "flac"
        } else if (level == "master" && tidalToken.isNotBlank()) {
            val tidalUrl = tryTidalDownloadUrl(artistName, songName, tidalToken)
            if (tidalUrl != null) return tidalUrl to "flac"
        }

        if (videoId.isNullOrBlank()) {
            throw IllegalStateException("Unable to resolve audio stream for '$songName'")
        }

        val audioQuality = if (level == "standard") AudioQuality.AUTO else AudioQuality.HIGH
        val playback = InnerTubeXPlayer.playerResponseForPlayback(
            videoId = videoId,
            audioQuality = audioQuality,
            connectivityManager = cm
        ).getOrThrow()

        val streamUrl = playback.streamUrl
        val ext = when {
            streamUrl.contains("audio%2Fmp4") || streamUrl.contains("audio/mp4") || streamUrl.contains("itag=140") -> "m4a"
            streamUrl.contains("audio%2Fwebm") || streamUrl.contains("audio/webm") || streamUrl.contains("itag=251") -> "opus"
            else -> "m4a"
        }
        return streamUrl to ext
    }

    private fun tryDeezerDownloadUrl(artist: String, title: String): String? = runCatching {
        val query = java.net.URLEncoder.encode("artist:\"$artist\" track:\"$title\"", "UTF-8")
        val req = Request.Builder().url("https://api.deezer.com/search?q=$query&limit=1").build()
        downloadClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            null
        }
    }.getOrNull()

    private fun tryTidalDownloadUrl(artist: String, title: String, token: String): String? = runCatching {
        null
    }.getOrNull()

    private suspend fun fetchLyrics(songId: Long, videoId: String?): Pair<String?, String?> {
        val durationSec = YouTubeIdRegistry.getDurationSec(songId)
        val lines = externalLyricsService.fetchLyrics(songName, artistName, durationSec, videoId)
        if (lines.isEmpty()) return null to null

        val ttml = runCatching {
            val accompanist = lines.toAccompanistSyncedLyrics()
            TTMLExporter.export(accompanist)
        }.getOrNull() ?: LyricParser.toTtml(lines, songName, artistName)

        val lrc = lines.sortedBy { it.timeMs }.joinToString("\n") { line ->
            val totalCentis = line.timeMs / 10
            val minutes = totalCentis / 6000
            val seconds = (totalCentis / 100) % 60
            val centis = totalCentis % 100
            "[%02d:%02d.%02d]%s".format(minutes, seconds, centis, line.text)
        }

        externalLyricsService.saveToDiskCache(videoId, songName, artistName, lines)

        return (if (ttml.isNotBlank()) ttml else null) to (if (lrc.isNotBlank()) lrc else null)
    }

    private fun onTerminalSuccess() {
        val tag = batchTag
        if (tag == "stream_cache") return
        if (tag == null) {
            // Single download notification suppressed per user request
            return
        }
        val settledExcludingSelf = batchSettledCount(tag)
        val (_, total) = batchCounts(tag)
        if (settledExcludingSelf + 1 >= total) {
            notificationHelper.showBatchSummary(tag, batchLabel, total)
        }
    }

    private fun onTerminalFailure(reason: String) {
        val tag = batchTag
        if (tag == "stream_cache") {
            AppLogger.w(TAG, "Background cache save failed: $reason songId=$songId")
            return
        }
        if (tag == null) {
            // Single download notification suppressed per user request
            return
        }
        val settledExcludingSelf = batchSettledCount(tag)
        val (_, total) = batchCounts(tag)
        if (settledExcludingSelf + 1 >= total) {
            notificationHelper.showBatchSummary(tag, batchLabel, total)
        }
    }

    private fun batchCounts(tag: String): Pair<Int, Int> {
        val infos = WorkManager.getInstance(applicationContext).getWorkInfosByTag(tag).get()
        val total = infos.size
        val finished = infos.count { it.state.isFinished }
        return finished to total
    }

    private fun batchSettledCount(tag: String): Int {
        val infos = WorkManager.getInstance(applicationContext).getWorkInfosByTag(tag).get()
        return infos.count { it.state.isFinished }
    }

    private fun buildForegroundInfo(progress: Int): ForegroundInfo {
        val notification = if (batchTag != null) {
            val (finished, total) = batchCounts(batchTag!!)
            notificationHelper.buildBatchProgressNotification(batchLabel, finished, total)
        } else {
            notificationHelper.buildProgressNotification(songName, progress)
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                notificationHelper.notificationIdFor(songId),
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(notificationHelper.notificationIdFor(songId), notification)
        }
    }

    private suspend fun publishProgress(percent: Int) {
        setProgress(
            workDataOf(
                KEY_PROGRESS_SONG_ID to songId,
                KEY_PROGRESS_SONG_NAME to songName,
                KEY_PROGRESS_ARTIST_NAME to artistName,
                KEY_PROGRESS_COVER_URL to coverUrl,
                KEY_PROGRESS_PERCENT to percent
            )
        )
    }

    private fun relativePath(): String {
        val base = Environment.DIRECTORY_MUSIC + File.separator + "Mimikyu"
        val tag = batchTag ?: return base
        val sub = sanitizeFileName(batchLabel)
        return "$base${File.separator}$sub"
    }

    private fun insertPendingMediaStoreEntry(displayName: String, mimeType: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
            put(MediaStore.Audio.Media.TITLE, songName)
            put(MediaStore.Audio.Media.ARTIST, artistName)
            if (albumName.isNotBlank()) put(MediaStore.Audio.Media.ALBUM, albumName)
            if (albumYear > 0) put(MediaStore.Audio.Media.YEAR, albumYear)
            put(MediaStore.Audio.Media.RELATIVE_PATH, relativePath())
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        return applicationContext.contentResolver.insert(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values
        )
    }

    private fun finalizePendingMediaStoreEntry(uri: Uri) {
        val values = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
        applicationContext.contentResolver.update(uri, values, null, null)
    }

    private fun resolveCustomDirectory(customFolderUri: String): DocumentFile? {
        val root = runCatching {
            DocumentFile.fromTreeUri(applicationContext, Uri.parse(customFolderUri))
        }.getOrNull() ?: return null
        if (!root.exists() || !root.canWrite()) return null
        val tag = batchTag ?: return root
        val folderName = sanitizeFileName(batchLabel)
        return root.findFile(folderName)?.takeIf { it.isDirectory }
            ?: root.createDirectory(folderName)
    }

    private fun uniqueFileNameIn(directory: File, displayName: String): String {
        if (!File(directory, displayName).exists()) return displayName
        val dot = displayName.lastIndexOf('.')
        return if (dot > 0) "${displayName.substring(0, dot)}_${System.currentTimeMillis()}${displayName.substring(dot)}"
        else "${displayName}_${System.currentTimeMillis()}"
    }

    private fun writeLyricsToPrivateDirectory(
        directory: File,
        audioDisplayName: String,
        ttmlText: String?,
        lrcText: String?
    ) {
        val dot = audioDisplayName.lastIndexOf('.')
        val base = if (dot > 0) audioDisplayName.substring(0, dot) else audioDisplayName
        if (!ttmlText.isNullOrBlank()) {
            runCatching { File(directory, "$base.ttml").writeText(ttmlText) }
        }
        if (!lrcText.isNullOrBlank()) {
            runCatching { File(directory, "$base.lrc").writeText(lrcText) }
        }
    }

    private fun uniqueNameIn(directory: DocumentFile, displayName: String): String {
        if (directory.findFile(displayName) == null) return displayName
        val dot = displayName.lastIndexOf('.')
        return if (dot > 0) "${displayName.substring(0, dot)}_${System.currentTimeMillis()}${displayName.substring(dot)}"
        else "${displayName}_${System.currentTimeMillis()}"
    }

    private fun writeTags(file: File, extension: String) {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE).use { pfd ->
            val fd = pfd.dup().detachFd()
            val metadata = TagLib.getMetadata(fd, readPictures = false)
            val propertyMap = HashMap<String, Array<String>>(metadata?.propertyMap ?: emptyMap())
            propertyMap["TITLE"] = arrayOf(songName)
            propertyMap["ARTIST"] = arrayOf(artistName)
            if (albumName.isNotBlank()) {
                propertyMap["ALBUM"] = arrayOf(albumName)
            }
            if (albumYear > 0) {
                propertyMap["DATE"] = arrayOf(albumYear.toString())
            }
            TagLib.savePropertyMap(pfd.dup().detachFd(), propertyMap)
        }

        coverUrl?.let { url -> fetchCoverBytes(url) }?.let { coverBytes ->
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE).use { pfd ->
                val picture = Picture(
                    data = coverBytes,
                    description = "Front Cover",
                    pictureType = "Front Cover",
                    mimeType = "image/jpeg"
                )
                TagLib.savePictures(pfd.dup().detachFd(), arrayOf(picture))
            }
        }
    }

    private fun lrcFileNameFor(audioDisplayName: String): String {
        val dot = audioDisplayName.lastIndexOf('.')
        return if (dot > 0) "${audioDisplayName.substring(0, dot)}.lrc" else "$audioDisplayName.lrc"
    }

    private fun ttmlFileNameFor(audioDisplayName: String): String {
        val dot = audioDisplayName.lastIndexOf('.')
        return if (dot > 0) "${audioDisplayName.substring(0, dot)}.ttml" else "$audioDisplayName.ttml"
    }

    private fun writeLyricsToDirectory(
        directory: DocumentFile,
        audioFileName: String,
        ttmlText: String?,
        lrcText: String?
    ) {
        if (!ttmlText.isNullOrBlank()) {
            runCatching {
                val ttmlName = ttmlFileNameFor(audioFileName)
                val doc = directory.findFile(ttmlName) ?: directory.createFile("application/xml", ttmlName) ?: return@runCatching
                applicationContext.contentResolver.openOutputStream(doc.uri)?.use {
                    it.write(ttmlText.toByteArray(Charsets.UTF_8))
                }
            }.onFailure { AppLogger.w(TAG, "Failed to write TTML file: songId=$songId", it) }
        }
        if (!lrcText.isNullOrBlank()) {
            runCatching {
                val lrcName = lrcFileNameFor(audioFileName)
                val doc = directory.findFile(lrcName) ?: directory.createFile("application/octet-stream", lrcName) ?: return@runCatching
                applicationContext.contentResolver.openOutputStream(doc.uri)?.use {
                    it.write(lrcText.toByteArray(Charsets.UTF_8))
                }
            }.onFailure { AppLogger.w(TAG, "Failed to write LRC file: songId=$songId", it) }
        }
    }

    private fun writeLyricsToMediaStore(
        audioDisplayName: String,
        ttmlText: String?,
        lrcText: String?
    ) {
        if (!ttmlText.isNullOrBlank()) {
            runCatching {
                val ttmlName = ttmlFileNameFor(audioDisplayName)
                val values = ContentValues().apply {
                    put(MediaStore.Files.FileColumns.DISPLAY_NAME, ttmlName)
                    put(MediaStore.Files.FileColumns.MIME_TYPE, "application/xml")
                    put(MediaStore.Files.FileColumns.RELATIVE_PATH, relativePath())
                    put(MediaStore.Files.FileColumns.IS_PENDING, 1)
                }
                val uri = applicationContext.contentResolver.insert(
                    MediaStore.Files.getContentUri("external"), values
                ) ?: return@runCatching
                applicationContext.contentResolver.openOutputStream(uri)?.use {
                    it.write(ttmlText.toByteArray(Charsets.UTF_8))
                }
                val clearPending = ContentValues().apply { put(MediaStore.Files.FileColumns.IS_PENDING, 0) }
                applicationContext.contentResolver.update(uri, clearPending, null, null)
            }.onFailure { AppLogger.w(TAG, "Failed to write TTML to MediaStore: songId=$songId", it) }
        }
        if (!lrcText.isNullOrBlank()) {
            runCatching {
                val lrcName = lrcFileNameFor(audioDisplayName)
                val values = ContentValues().apply {
                    put(MediaStore.Files.FileColumns.DISPLAY_NAME, lrcName)
                    put(MediaStore.Files.FileColumns.MIME_TYPE, "application/octet-stream")
                    put(MediaStore.Files.FileColumns.RELATIVE_PATH, relativePath())
                    put(MediaStore.Files.FileColumns.IS_PENDING, 1)
                }
                val uri = applicationContext.contentResolver.insert(
                    MediaStore.Files.getContentUri("external"), values
                ) ?: return@runCatching
                applicationContext.contentResolver.openOutputStream(uri)?.use {
                    it.write(lrcText.toByteArray(Charsets.UTF_8))
                }
                val clearPending = ContentValues().apply { put(MediaStore.Files.FileColumns.IS_PENDING, 0) }
                applicationContext.contentResolver.update(uri, clearPending, null, null)
            }.onFailure { AppLogger.w(TAG, "Failed to write LRC to MediaStore: songId=$songId", it) }
        }
    }

    private fun fetchCoverBytes(url: String): ByteArray? = runCatching {
        val sized = if (url.contains('?')) url else "$url?param=500y500"
        val request = Request.Builder().url(sized).build()
        downloadClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null else response.body?.bytes()
        }
    }.getOrNull()

    private suspend fun downloadToFile(file: File, url: String): Long? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).build()
        downloadClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                AppLogger.e(TAG, "Download HTTP failed: code=${response.code} songId=$songId")
                return@withContext null
            }
            val body = response.body ?: return@withContext null
            val total = body.contentLength()
            var written = 0L
            var lastProgress = -1
            file.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        out.write(buffer, 0, read)
                        written += read
                        if (total > 0) {
                            val progress = (written * 100 / total).toInt()
                            if (progress != lastProgress) {
                                lastProgress = progress
                                publishProgress(progress)
                            }
                        }
                    }
                }
            }
            if (total > 0 && written != total) null else written
        }
    }

    private suspend fun copyFileToUri(file: File, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val output = applicationContext.contentResolver.openOutputStream(uri) ?: return@runCatching false
            output.use { out ->
                file.inputStream().use { input -> input.copyTo(out) }
            }
            true
        }.getOrDefault(false)
    }

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "song_$songId" }

    private fun mimeTypeFor(extension: String): String = when (extension) {
        "flac" -> "audio/flac"
        "mp3" -> "audio/mpeg"
        "m4a", "aac" -> "audio/mp4"
        "opus", "ogg" -> "audio/ogg"
        "wav" -> "audio/wav"
        else -> "audio/*"
    }
}
