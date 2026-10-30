package com.lin0721.linmusic.core.userplaylist

import com.lin0721.linmusic.core.auth.UserPreferences
import com.lin0721.linmusic.core.firebase.FirestorePlaylistManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow

class UserPlaylistRepositoryImpl(
    private val apiService: UserPlaylistApi,
    private val firestorePlaylistManager: FirestorePlaylistManager? = null,
    private val localPlaylistStore: LocalCustomPlaylistStore? = null,
    private val userPreferences: UserPreferences? = null
) : UserPlaylistRepository {

    override fun getUserPlaylists(uid: Long, limit: Int): Flow<Result<List<UserPlaylist>>> = flow {
        val localPlaylists = localPlaylistStore?.getPlaylists().orEmpty()
        val cookies = userPreferences?.cookies?.firstOrNull().orEmpty()
        val hasNeteaseAuth = cookies.contains("MUSIC_U")
        val remotePlaylists = when {
            firestorePlaylistManager?.isFirebaseUser() == true -> {
                firestorePlaylistManager.getUserPlaylists(uid)
            }
            // Only call NetEase if explicitly signed into NetEase with authenticated MUSIC_U cookie
            // and NOT a dummy/synced/Google uid (such as uid == 1L from YouTube Music)
            hasNeteaseAuth && uid > 1000L -> {
                runCatching {
                    apiService.getUserPlaylists(UserPlaylistRequest(uid = uid, limit = limit)).playlist
                }.getOrDefault(emptyList())
            }
            else -> emptyList()
        }
        val combined = (localPlaylists + remotePlaylists).distinctBy { it.id }
            .filterNot { it.name.contains("Episodes for later", ignoreCase = true) }
        emit(Result.success(combined))
    }
}
