package com.lin0721.linmusic.core.auth

import com.lin0721.linmusic.core.log.AppLogger
import com.lin0721.linmusic.core.metadata.MetadataEnricher
import com.lin0721.linmusic.core.metadata.cleanMetadataText
import com.lin0721.linmusic.core.metadata.toTrack
import com.lin0721.linmusic.core.player.innertube.YouTubeIdRegistry
import com.lin0721.linmusic.core.ui.components.YouTubeSessionData
import com.lin0721.linmusic.core.userplaylist.LocalCustomPlaylistStore
import com.lin0721.linmusic.core.userplaylist.StoredCustomPlaylist
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.PlaylistItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext

private const val TAG = "YouTubeAccountSync"

class YouTubeAccountSyncUseCase(
    private val userPreferences: UserPreferences,
    private val localPlaylistStore: LocalCustomPlaylistStore,
    private val metadataEnricher: MetadataEnricher? = null
) {

    suspend fun syncSession(session: YouTubeSessionData): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            AppLogger.d(TAG, "Starting sync for YouTube Music session")

            // 1. Save session to preferences & apply to InnerTube
            userPreferences.saveCookies(session.cookies)
            userPreferences.saveYouTubeSession(
                visitorData = session.visitorData,
                dataSyncId = session.dataSyncId,
                authUser = session.authUser
            )

            YouTube.cookie = session.cookies
            YouTube.useLoginForBrowse = true
            if (!session.visitorData.isNullOrBlank()) YouTube.visitorData = session.visitorData
            if (!session.dataSyncId.isNullOrBlank()) YouTube.dataSyncId = session.dataSyncId
            if (!session.authUser.isNullOrBlank()) YouTube.authUser = session.authUser

            // 2. Never sync or overwrite user profile with YouTube Music info per user requirement

            // 3. Sync Liked Songs ("LM") - Union old liked songs with YouTube Music liked songs
            runCatching {
                val likedPage = YouTube.playlist("LM").getOrNull()
                if (likedPage != null && likedPage.songs.isNotEmpty()) {
                    val ytTracks = likedPage.songs.map { it.toTrack(metadataEnricher) }
                    val currentLiked = userPreferences.likedTracks.firstOrNull().orEmpty()
                    val mergedTracks = (currentLiked + ytTracks).distinctBy {
                        if (it.id > 0L) it.id else "${it.name.lowercase().trim()}_${it.ar.firstOrNull()?.name?.lowercase()?.trim()}"
                    }
                    userPreferences.saveLikedTracks(mergedTracks)
                    AppLogger.d(TAG, "Merged ${currentLiked.size} old liked songs with ${ytTracks.size} YouTube liked songs (total ${mergedTracks.size})")
                }
            }.onFailure { AppLogger.w(TAG, "Failed to sync liked songs", it) }

            // 4. Sync Recents / History
            runCatching {
                val historyPage = YouTube.musicHistory().getOrNull()
                if (historyPage != null) {
                    val historySongs = historyPage.sections?.flatMap { it.songs }.orEmpty().distinctBy { it.id }
                    if (historySongs.isNotEmpty()) {
                        val records = historySongs.take(50).mapIndexed { index, song ->
                            RecentPlayRecord(
                                track = song.toTrack(metadataEnricher),
                                playedAt = System.currentTimeMillis() - (index * 60_000L),
                                playCount = 1
                            )
                        }
                        userPreferences.saveRecentRecords(records)
                        AppLogger.d(TAG, "Synced ${records.size} recent records from YouTube history")
                    }
                }
            }.onFailure { AppLogger.w(TAG, "Failed to sync listening history", it) }

            // 5. Sync User Playlists
            runCatching {
                val libraryResult = YouTube.library("FEmusic_liked_playlists").getOrNull()
                    ?: YouTube.library("FEmusic_library_landing").getOrNull()
                if (libraryResult != null) {
                    val playlists = libraryResult.items.filterIsInstance<PlaylistItem>()
                        .filterNot {
                            it.id == "LM" || it.id.endsWith("VLLM") ||
                            it.id == "SE" || it.id.endsWith("VLSE") ||
                            it.title.contains("Episodes for later", ignoreCase = true)
                        }
                    playlists.forEach { pl ->
                        val plId = YouTubeIdRegistry.registerPlaylist(pl.id)
                        localPlaylistStore.saveOrUpdatePlaylist(
                            StoredCustomPlaylist(
                                id = plId,
                                name = cleanMetadataText(pl.title),
                                coverImgUrl = pl.thumbnail.orEmpty(),
                                description = cleanMetadataText(pl.description ?: pl.author?.name ?: ""),
                                tracks = emptyList(),
                                updateTime = System.currentTimeMillis()
                            )
                        )
                    }
                    AppLogger.d(TAG, "Synced ${playlists.size} user playlists from YouTube Music")
                }
            }.onFailure { AppLogger.w(TAG, "Failed to sync user playlists", it) }

            Unit
        }
    }

    suspend fun restoreSessionIfAvailable() = withContext(Dispatchers.IO) {
        val cookies = userPreferences.cookies.firstOrNull()
        if (!cookies.isNullOrBlank()) {
            YouTube.cookie = cookies
            YouTube.useLoginForBrowse = true
            val visitorData = userPreferences.ytmVisitorData.firstOrNull()
            val dataSyncId = userPreferences.ytmDataSyncId.firstOrNull()
            val authUser = userPreferences.ytmAuthUser.firstOrNull()
            if (!visitorData.isNullOrBlank()) YouTube.visitorData = visitorData
            if (!dataSyncId.isNullOrBlank()) YouTube.dataSyncId = dataSyncId
            if (!authUser.isNullOrBlank()) YouTube.authUser = authUser
            AppLogger.d(TAG, "Restored YouTube Music session from preferences")
        }
    }
}
