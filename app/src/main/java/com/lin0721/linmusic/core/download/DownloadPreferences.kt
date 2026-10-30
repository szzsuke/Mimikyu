package com.lin0721.linmusic.core.download

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lin0721.linmusic.core.log.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val TAG = "DownloadPreferences"

private val Context.downloadDataStore by preferencesDataStore(name = "download_prefs")

// Download
@Serializable
data class DownloadRecord(
    val songId: Long,
    val mediaStoreUri: String,
    val quality: String,
    val downloadedAt: Long,
    val fileSize: Long,
    val songName: String = "",
    val artistName: String = "",
    val coverUrl: String = "",
    val durationSec: Long = 0L
)

// Download Folder Uri
fun isDefaultDownloadDirectoryUri(uriString: String): Boolean =
    runCatching {
        val uri = Uri.parse(uriString)
        uri.authority == MediaStore.AUTHORITY ||
            uri.scheme == "file" ||
            uriString.contains("MimikyuDownloads") ||
            uriString.contains("downloads")
    }.getOrDefault(false)

// Download
class DownloadPreferences(private val context: Context) {

    companion object {
        private val KEY_RECORDS = stringPreferencesKey("download_records")
        private val json = Json { ignoreUnknownKeys = true }
    }

    val records: Flow<List<DownloadRecord>> = context.downloadDataStore.data.map { prefs ->
        decodeRecords(prefs[KEY_RECORDS])
    }

    suspend fun isDownloaded(songId: Long): Boolean = downloadedQualityFor(songId).first() != null

    // Download，
    fun downloadedQualityFor(songId: Long): Flow<String?> = records
        .map { list -> list.firstOrNull { it.songId == songId } }
        .distinctUntilChanged()
        .map { record -> record?.let { verifyOrPurge(it) }?.quality }

    // of Download
    suspend fun findVerifiedRecord(songId: Long): DownloadRecord? =
        records.first().firstOrNull { it.songId == songId }?.let { verifyOrPurge(it) }


    private suspend fun verifyOrPurge(record: DownloadRecord): DownloadRecord? {
        if (!isDefaultDownloadDirectoryUri(record.mediaStoreUri)) {
            removeRecord(record.songId)
            return null
        }
        return if (fileExists(record.mediaStoreUri)) record else {
            removeRecord(record.songId)
            null
        }
    }

    // Uri
    private suspend fun fileExists(uriString: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val uri = Uri.parse(uriString)
            if (uri.scheme == "file") {
                val path = uri.path ?: return@runCatching false
                val f = java.io.File(path)
                f.exists() && f.length() > 0
            } else {
                context.contentResolver.openInputStream(uri)?.use { true } ?: false
            }
        }.getOrDefault(false)
    }

    // AddDownload
    suspend fun addRecord(record: DownloadRecord) {
        context.downloadDataStore.edit { prefs ->
            val updated = decodeRecords(prefs[KEY_RECORDS]).filterNot { it.songId == record.songId } + record
            prefs[KEY_RECORDS] = json.encodeToString(updated)
        }
    }

    suspend fun removeRecord(songId: Long) {
        val record = records.first().find { it.songId == songId }
        if (record != null) {
            runCatching {
                val uri = Uri.parse(record.mediaStoreUri)
                if (uri.scheme == "file") {
                    val f = java.io.File(uri.path ?: "")
                    if (f.exists()) f.delete()
                    val dot = f.name.lastIndexOf('.')
                    val base = if (dot > 0) f.name.substring(0, dot) else f.name
                    f.parentFile?.let { p ->
                        java.io.File(p, "$base.ttml").delete()
                        java.io.File(p, "$base.lrc").delete()
                    }
                } else {
                    context.contentResolver.delete(uri, null, null)
                }
            }
        }
        context.downloadDataStore.edit { prefs ->
            val updated = decodeRecords(prefs[KEY_RECORDS]).filterNot { it.songId == songId }
            prefs[KEY_RECORDS] = json.encodeToString(updated)
        }
    }

    private fun decodeRecords(raw: String?): List<DownloadRecord> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<DownloadRecord>>(raw) }
            .onFailure { AppLogger.w(TAG, "Failed to deserialize download history", it) }
            .getOrDefault(emptyList())
    }
}
