package com.lin0721.linmusic.core.metadata

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

@Serializable
data class DeezerSearchResult(
    val data: List<DeezerSearchTrack> = emptyList(),
    val total: Int = 0,
    val error: DeezerSearchError? = null
)

@Serializable
data class DeezerSearchError(
    val type: String = "",
    val message: String = "",
    val code: Int = 0
)

@Serializable
data class DeezerSearchTrack(
    val id: Long = 0,
    val title: String = "",
    @SerialName("title_short") val titleShort: String? = null,
    val duration: Int = 0, // duration in seconds
    val artist: DeezerSearchArtist? = null,
    val album: DeezerSearchAlbum? = null
) {
    /**
     * Extracts best quality studio artwork URL:
     * cover_xl (1000x1000) -> cover_big (500x500) -> cover_medium (250x250) -> cover
     */
    fun getHighResArtworkUrl(): String? {
        return album?.coverXl ?: album?.coverBig ?: album?.coverMedium ?: album?.cover
    }
}

@Serializable
data class DeezerSearchArtist(
    val id: Long = 0,
    val name: String = "",
    @SerialName("picture_xl") val pictureXl: String? = null,
    @SerialName("picture_big") val pictureBig: String? = null,
    @SerialName("picture_medium") val pictureMedium: String? = null
)

@Serializable
data class DeezerSearchAlbum(
    val id: Long = 0,
    val title: String = "",
    @SerialName("cover_xl") val coverXl: String? = null,
    @SerialName("cover_big") val coverBig: String? = null,
    @SerialName("cover_medium") val coverMedium: String? = null,
    val cover: String? = null
)

class DeezerSearchClient(
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

    suspend fun searchTracks(query: String, limit: Int = 5): List<DeezerSearchTrack> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val encodedTerm = try {
            URLEncoder.encode(query, "UTF-8")
        } catch (e: Exception) {
            query.replace(" ", "+")
        }
        val url = "https://api.deezer.com/search?q=$encodedTerm&limit=$limit"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:109.0) Gecko/119.0 Firefox/119.0")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.tag("DeezerSearchClient").w("Deezer search failed with HTTP ${response.code}")
                    return@withContext emptyList()
                }
                val bodyString = response.body?.string() ?: return@withContext emptyList()
                val parsed = json.decodeFromString<DeezerSearchResult>(bodyString)
                if (parsed.error != null) {
                    Timber.tag("DeezerSearchClient").w("Deezer error: ${parsed.error.message}")
                    return@withContext emptyList()
                }
                parsed.data
            }
        } catch (e: Exception) {
            Timber.tag("DeezerSearchClient").w("Deezer search network error for query='$query': ${e.message}")
            emptyList()
        }
    }

    suspend fun searchSongsByTitleAndArtist(title: String, artist: String, limit: Int = 5): List<DeezerSearchTrack> {
        val combinedQuery = "$title $artist".trim()
        val firstAttempt = searchTracks(combinedQuery, limit)
        if (firstAttempt.isNotEmpty()) return firstAttempt

        if (title.isNotBlank()) {
            return searchTracks(title, limit)
        }
        return emptyList()
    }
}
