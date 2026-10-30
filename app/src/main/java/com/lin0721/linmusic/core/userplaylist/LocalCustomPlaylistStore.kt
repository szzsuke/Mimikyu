package com.lin0721.linmusic.core.userplaylist

import android.content.Context
import com.lin0721.linmusic.core.log.AppLogger
import com.lin0721.linmusic.core.model.PlaylistCreator
import com.lin0721.linmusic.core.model.PlaylistDetail
import com.lin0721.linmusic.core.model.PlaylistTrackId
import com.lin0721.linmusic.core.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class StoredCustomPlaylist(
    val id: Long,
    val name: String,
    val coverImgUrl: String = "",
    val description: String = "",
    val tracks: List<Track> = emptyList(),
    val updateTime: Long = System.currentTimeMillis()
)

class LocalCustomPlaylistStore(private val context: Context) {
    companion object {
        private val customPlaylistIds = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

        fun isCustomPlaylist(id: Long): Boolean = customPlaylistIds.contains(id)
        fun registerCustomPlaylistId(id: Long) { customPlaylistIds.add(id) }
        fun unregisterCustomPlaylistId(id: Long) { customPlaylistIds.remove(id) }
    }

    private val file = File(context.filesDir, "custom_playlists.json")
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    suspend fun getPlaylists(): List<UserPlaylist> = mutex.withLock {
        withContext(Dispatchers.IO) {
            loadFromDisk()
                .filterNot { it.name.contains("Episodes for later", ignoreCase = true) }
                .map { it.toUserPlaylist() }
        }
    }

    suspend fun getPlaylistDetail(id: Long): PlaylistDetail? = mutex.withLock {
        withContext(Dispatchers.IO) {
            loadFromDisk().find { it.id == id }?.toPlaylistDetail()
        }
    }

    suspend fun createPlaylist(name: String, tracks: List<Track>): PlaylistDetail = mutex.withLock {
        withContext(Dispatchers.IO) {
            val list = loadFromDisk().toMutableList()
            val id = (System.currentTimeMillis() and 0x7FFFFFFFFFFFFFFFL)
            val cover = tracks.firstOrNull()?.al?.picUrl.orEmpty()
            val newPlaylist = StoredCustomPlaylist(
                id = id,
                name = name.trim().ifBlank { "New Playlist" },
                coverImgUrl = cover,
                tracks = tracks,
                updateTime = System.currentTimeMillis()
            )
            list.add(0, newPlaylist)
            customPlaylistIds.add(id)
            saveToDisk(list)
            newPlaylist.toPlaylistDetail()
        }
    }

    suspend fun saveOrUpdatePlaylist(playlist: StoredCustomPlaylist) = mutex.withLock {
        if (playlist.name.contains("Episodes for later", ignoreCase = true)) return@withLock
        withContext(Dispatchers.IO) {
            val list = loadFromDisk().toMutableList()
            val idx = list.indexOfFirst { it.id == playlist.id }
            if (idx >= 0) {
                list[idx] = playlist
            } else {
                list.add(0, playlist)
            }
            customPlaylistIds.add(playlist.id)
            saveToDisk(list)
        }
    }

    suspend fun deletePlaylist(id: Long) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val list = loadFromDisk().filterNot { it.id == id }
            customPlaylistIds.remove(id)
            saveToDisk(list)
        }
    }

    suspend fun addTracks(id: Long, newTracks: List<Track>) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val list = loadFromDisk().map { pl ->
                if (pl.id == id) {
                    val combined = (pl.tracks + newTracks).distinctBy { it.id }
                    val cover = pl.coverImgUrl.ifBlank { combined.firstOrNull()?.al?.picUrl.orEmpty() }
                    pl.copy(tracks = combined, coverImgUrl = cover, updateTime = System.currentTimeMillis())
                } else pl
            }
            saveToDisk(list)
        }
    }

    suspend fun removeTracks(id: Long, trackIds: List<Long>) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val list = loadFromDisk().map { pl ->
                if (pl.id == id) {
                    val remaining = pl.tracks.filterNot { it.id in trackIds }
                    pl.copy(tracks = remaining, updateTime = System.currentTimeMillis())
                } else pl
            }
            saveToDisk(list)
        }
    }

    suspend fun updateTrackOrder(id: Long, trackIds: List<Long>) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val list = loadFromDisk().map { pl ->
                if (pl.id == id) {
                    val trackMap = pl.tracks.associateBy { it.id }
                    val reordered = trackIds.mapNotNull { trackMap[it] }
                    pl.copy(tracks = reordered, updateTime = System.currentTimeMillis())
                } else pl
            }
            saveToDisk(list)
        }
    }

    private fun loadFromDisk(): List<StoredCustomPlaylist> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val text = file.readText()
            if (text.isBlank()) emptyList() else {
                val parsed = json.decodeFromString<List<StoredCustomPlaylist>>(text)
                val cleaned = parsed.filterNot { it.name.contains("Episodes for later", ignoreCase = true) }
                if (cleaned.size != parsed.size) {
                    saveToDisk(cleaned)
                }
                cleaned.forEach { customPlaylistIds.add(it.id) }
                cleaned
            }
        }.getOrElse {
            AppLogger.e("LocalCustomPlaylistStore", "Failed to parse custom_playlists.json", it)
            emptyList()
        }
    }

    private fun saveToDisk(list: List<StoredCustomPlaylist>) {
        runCatching {
            file.writeText(json.encodeToString(list))
        }.onFailure {
            AppLogger.e("LocalCustomPlaylistStore", "Failed to write custom_playlists.json", it)
        }
    }

    private fun StoredCustomPlaylist.toUserPlaylist(): UserPlaylist = UserPlaylist(
        id = id,
        name = name,
        coverImgUrl = coverImgUrl,
        playCount = 0L,
        trackCount = tracks.size,
        userId = 0L,
        creator = PlaylistCreator(userId = 0L, nickname = "My Playlist", avatarUrl = ""),
        updateTime = updateTime
    )

    private fun StoredCustomPlaylist.toPlaylistDetail(): PlaylistDetail = PlaylistDetail(
        id = id,
        name = name,
        coverImgUrl = coverImgUrl,
        description = description,
        playCount = 0L,
        subscribed = false,
        creator = PlaylistCreator(userId = 0L, nickname = "My Playlist", avatarUrl = ""),
        tracks = tracks,
        trackCount = tracks.size,
        trackIds = tracks.map { PlaylistTrackId(it.id) },
        isEditable = true
    )
}
