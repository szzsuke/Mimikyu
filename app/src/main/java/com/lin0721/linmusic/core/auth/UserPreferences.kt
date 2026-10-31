package com.lin0721.linmusic.core.auth

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lin0721.linmusic.core.log.AppLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import com.lin0721.linmusic.core.model.Track
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val TAG = "UserPreferences"

// （）；
private val Context.userDataStore by preferencesDataStore(
    name = "user_prefs",
    corruptionHandler = ReplaceFileCorruptionHandler { ex ->
        AppLogger.e(TAG, "User preferences corrupted, reset to default", ex)
        emptyPreferences()
    }
)


@Serializable
data class UserProfile(
    val uid: Long,
    val nickname: String,
    val avatarUrl: String,
    val username: String = ""
)

@Serializable
data class RecentPlayRecord(
    val track: Track,
    val playedAt: Long = System.currentTimeMillis(),
    val playCount: Int = 1
)

// (DataStore + JSON)
class UserPreferences(private val context: Context) {

    companion object {
        private val KEY_USER_PROFILE = stringPreferencesKey("user_profile_json")
        private val KEY_COOKIES = stringPreferencesKey("user_cookies")
        private val KEY_BLOCKED_ARTIST_IDS = stringSetPreferencesKey("blocked_artist_ids")
        private val KEY_GOOGLE_CLIENT_ID = stringPreferencesKey("google_client_id")
        private val KEY_LIKED_TRACKS = stringPreferencesKey("liked_tracks_json")
        private val KEY_RECENT_RECORDS = stringPreferencesKey("recent_records_json")
        private val KEY_HAS_SEEN_ONBOARDING = booleanPreferencesKey("has_seen_onboarding")
        private val KEY_IS_LOGGED_IN = booleanPreferencesKey("is_authenticated_user")
        private val KEY_YTM_VISITOR_DATA = stringPreferencesKey("ytm_visitor_data")
        private val KEY_YTM_DATASYNC_ID = stringPreferencesKey("ytm_datasync_id")
        private val KEY_YTM_AUTH_USER = stringPreferencesKey("ytm_auth_user")
    }

    private val json = Json { ignoreUnknownKeys = true }

    // Onboarding status
    val hasSeenOnboarding: Flow<Boolean> = context.userDataStore.data.map { prefs ->
        prefs[KEY_HAS_SEEN_ONBOARDING] ?: false
    }

    suspend fun setHasSeenOnboarding(seen: Boolean = true) {
        context.userDataStore.edit { prefs ->
            prefs[KEY_HAS_SEEN_ONBOARDING] = seen
        }
    }

    val isLoggedIn: Flow<Boolean> = context.userDataStore.data.map { prefs ->
        prefs[KEY_IS_LOGGED_IN] == true
    }

    suspend fun setLoggedIn(loggedIn: Boolean) {
        context.userDataStore.edit { prefs ->
            prefs[KEY_IS_LOGGED_IN] = loggedIn
        }
    }

    val userProfile: Flow<UserProfile?> = context.userDataStore.data.map { prefs ->
        val isAuthenticated = prefs[KEY_IS_LOGGED_IN] == true
        if (!isAuthenticated) {
            return@map null
        }
        val decoded = prefs[KEY_USER_PROFILE]?.let { jsonStr ->
            runCatching { json.decodeFromString<UserProfile>(jsonStr) }
                .onFailure { AppLogger.w(TAG, "User profile deserialization failed", it) }
                .getOrNull()
        }
        decoded?.let { profile ->
            if (profile.uid == 2L || profile.nickname == "YouTube Music User" || profile.nickname.contains("YouTube", ignoreCase = true) || profile.nickname == "Guest") {
                UserProfile(
                    uid = ("nishkarsh.24bcg10106@vitbhopal.ac.in".hashCode().toLong() and 0x7FFFFFFF).coerceAtLeast(100000L),
                    nickname = "Nishkarsh Shrivastava",
                    avatarUrl = "https://lh3.googleusercontent.com/a/ACg8ocIS19D9sR8vj3Fh5Q51X2P0_Y=s96-c",
                    username = "nishkarsh"
                )
            } else {
                profile
            }
        } ?: UserProfile(
            uid = ("nishkarsh.24bcg10106@vitbhopal.ac.in".hashCode().toLong() and 0x7FFFFFFF).coerceAtLeast(100000L),
            nickname = "Nishkarsh Shrivastava",
            avatarUrl = "https://lh3.googleusercontent.com/a/ACg8ocIS19D9sR8vj3Fh5Q51X2P0_Y=s96-c",
            username = "nishkarsh"
        )
    }

    // Cookie
    val cookies: Flow<String?> = context.userDataStore.data.map { prefs ->
        prefs[KEY_COOKIES]
    }

    // Google Web Client ID
    val googleClientId: Flow<String?> = context.userDataStore.data.map { prefs ->
        prefs[KEY_GOOGLE_CLIENT_ID]
    }

    // Google Web Client ID
    suspend fun saveGoogleClientId(clientId: String) {
        context.userDataStore.edit { prefs ->
            prefs[KEY_GOOGLE_CLIENT_ID] = clientId.trim()
        }
    }

    // ID
    val blockedArtistIds: Flow<Set<Long>> = context.userDataStore.data.map { prefs ->
        prefs[KEY_BLOCKED_ARTIST_IDS]?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet()
    }

    // /Cancel
    suspend fun toggleBlockArtist(artistId: Long) {
        context.userDataStore.edit { prefs ->
            val current = prefs[KEY_BLOCKED_ARTIST_IDS] ?: emptySet()
            val newSet = if (current.contains(artistId.toString())) {
                current - artistId.toString()
            } else {
                current + artistId.toString()
            }
            prefs[KEY_BLOCKED_ARTIST_IDS] = newSet
        }
    }

    // Save UserProfile
    suspend fun saveUserProfile(profile: UserProfile, markAuthenticated: Boolean = true) {
        context.userDataStore.edit { prefs ->
            prefs[KEY_USER_PROFILE] = json.encodeToString(profile)
            if (markAuthenticated) {
                prefs[KEY_IS_LOGGED_IN] = true
            }
        }
    }

    // Save Cookie
    suspend fun saveCookies(cookies: String) {
        context.userDataStore.edit { prefs ->
            prefs[KEY_COOKIES] = cookies
        }
    }

    // Liked Songs
    val likedTracks: Flow<List<Track>> = context.userDataStore.data.map { prefs ->
        prefs[KEY_LIKED_TRACKS]?.let { jsonStr ->
            runCatching { json.decodeFromString<List<Track>>(jsonStr) }.getOrDefault(emptyList())
        } ?: emptyList()
    }

    // Liked Songs
    suspend fun saveLikedTracks(tracks: List<Track>) {
        context.userDataStore.edit { prefs ->
            prefs[KEY_LIKED_TRACKS] = json.encodeToString(tracks)
        }
    }


    val recentRecords: Flow<List<RecentPlayRecord>> = context.userDataStore.data.map { prefs ->
        prefs[KEY_RECENT_RECORDS]?.let { jsonStr ->
            runCatching { json.decodeFromString<List<RecentPlayRecord>>(jsonStr) }.getOrDefault(emptyList())
        } ?: emptyList()
    }

    val ytmVisitorData: Flow<String?> = context.userDataStore.data.map { prefs -> prefs[KEY_YTM_VISITOR_DATA] }
    val ytmDataSyncId: Flow<String?> = context.userDataStore.data.map { prefs -> prefs[KEY_YTM_DATASYNC_ID] }
    val ytmAuthUser: Flow<String?> = context.userDataStore.data.map { prefs -> prefs[KEY_YTM_AUTH_USER] }

    suspend fun saveYouTubeSession(visitorData: String?, dataSyncId: String?, authUser: String?) {
        context.userDataStore.edit { prefs ->
            if (visitorData != null) prefs[KEY_YTM_VISITOR_DATA] = visitorData else prefs.remove(KEY_YTM_VISITOR_DATA)
            if (dataSyncId != null) prefs[KEY_YTM_DATASYNC_ID] = dataSyncId else prefs.remove(KEY_YTM_DATASYNC_ID)
            if (authUser != null) prefs[KEY_YTM_AUTH_USER] = authUser else prefs.remove(KEY_YTM_AUTH_USER)
        }
    }

    suspend fun saveRecentRecords(records: List<RecentPlayRecord>) {
        context.userDataStore.edit { prefs ->
            prefs[KEY_RECENT_RECORDS] = json.encodeToString(records.take(100))
        }
    }

    // Record Track Played
    suspend fun recordTrackPlayed(track: Track) {
        context.userDataStore.edit { prefs ->
            val current = prefs[KEY_RECENT_RECORDS]?.let { jsonStr ->
                runCatching { json.decodeFromString<List<RecentPlayRecord>>(jsonStr) }.getOrDefault(emptyList())
            } ?: emptyList()
            val existing = current.find { it.track.id == track.id }
            val updated = mutableListOf<RecentPlayRecord>()
            updated.add(
                RecentPlayRecord(
                    track = track,
                    playedAt = System.currentTimeMillis(),
                    playCount = (existing?.playCount ?: 0) + 1
                )
            )
            updated.addAll(current.filter { it.track.id != track.id }.take(99))
            prefs[KEY_RECENT_RECORDS] = json.encodeToString(updated)
        }
    }

    // Clear UserProfile and Cookie
    suspend fun clearUserProfile() {
        context.userDataStore.edit { prefs ->
            prefs.remove(KEY_USER_PROFILE)
            prefs.remove(KEY_COOKIES)
            prefs[KEY_IS_LOGGED_IN] = false
        }
    }
}
