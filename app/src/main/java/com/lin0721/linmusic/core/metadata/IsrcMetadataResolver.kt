package com.lin0721.linmusic.core.metadata

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.util.concurrent.TimeUnit

@Serializable
data class ResolvedTrackMetadata(
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationSec: Int = 0,
    val coverUrl: String? = null,
    val source: String
)

@Serializable
private data class DeezerArtist(
    val name: String = "",
    @SerialName("picture_medium") val pictureMedium: String? = null
)

@Serializable
private data class DeezerAlbum(
    val title: String = "",
    @SerialName("cover_xl") val coverXl: String? = null,
    @SerialName("cover_big") val coverBig: String? = null,
    @SerialName("cover_medium") val coverMedium: String? = null
)

@Serializable
private data class DeezerError(
    val message: String = "",
    val code: Int = 0
)

@Serializable
private data class DeezerTrackResponse(
    val id: Long? = null,
    val title: String? = null,
    val duration: Int? = null,
    val artist: DeezerArtist? = null,
    val album: DeezerAlbum? = null,
    val error: DeezerError? = null
)

/**
 * Resolves track metadata from an ISRC code via Deezer.
 * Never queries services in parallel.
 */
class IsrcMetadataResolver(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    suspend fun resolveTrackByIsrc(isrc: String): ResolvedTrackMetadata? = withContext(Dispatchers.IO) {
        val cleanIsrc = isrc.trim().uppercase()
        if (cleanIsrc.isBlank()) return@withContext null

        // 1. Primary: Deezer
        try {
            val deezerResult = resolveDeezer(cleanIsrc)
            if (deezerResult != null) {
                Timber.tag("IsrcResolver").d("Resolved ISRC $cleanIsrc via Deezer: ${deezerResult.title}")
                return@withContext deezerResult
            }
        } catch (e: Exception) {
            Timber.tag("IsrcResolver").w("Deezer failover for $cleanIsrc: ${e.message}")
        }

        null
    }

    private fun resolveDeezer(isrc: String): ResolvedTrackMetadata? {
        val url = "https://api.deezer.com/track/isrc:$isrc"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mimikyu/1.0 (Android)")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            val parsed = json.decodeFromString<DeezerTrackResponse>(body)
            if (parsed.error != null || parsed.title.isNullOrBlank()) return null

            val cover = parsed.album?.coverXl ?: parsed.album?.coverBig ?: parsed.album?.coverMedium
            return ResolvedTrackMetadata(
                title = parsed.title,
                artist = parsed.artist?.name ?: "",
                album = parsed.album?.title,
                durationSec = parsed.duration ?: 0,
                coverUrl = cover,
                source = "deezer"
            )
        }
    }
}
