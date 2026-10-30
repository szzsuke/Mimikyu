package com.lin0721.linmusic.feature.search.domain

import com.lin0721.linmusic.core.model.Track
import com.lin0721.linmusic.feature.search.data.SearchRepository
import com.lin0721.linmusic.feature.search.domain.SearchResultItem
import com.lin0721.linmusic.feature.search.domain.SearchType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class IdentifiedSong(
    val title: String,
    val artist: String,
    val album: String = "",
    val coverUrl: String = "",
    val previewUrl: String? = null,
    val durationMs: Long = 0L,
    val mimikyuTrack: Track? = null,
    val source: String = "Apple Music"
)

/**
 * Intelligent Song Identifier Engine.
 * Resolves songs using iTunes Search API, Deezer music catalogue,
 * and seamlessly correlates with Mimikyu's internal SearchRepository for instant full playback.
 */
class SongIdentifierEngine(
    private val searchRepository: SearchRepository
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    suspend fun identifyFromQuery(query: String): IdentifiedSong? = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return@withContext null

        Timber.tag("SongIdentifier").d("Identifying song for query: '%s'", cleanQuery)

        // 1. Primary: Search Mimikyu internal track repository directly
        val mimikyuTrack: Track? = try {
            val searchFlow = searchRepository.search(cleanQuery, SearchType.SONG, offset = 0, limit = 5)
            val pageResult = searchFlow.firstOrNull()?.getOrNull()
            pageResult?.items
                ?.filterIsInstance<SearchResultItem.SongItem>()
                ?.firstOrNull()
                ?.track
        } catch (e: Exception) {
            Timber.tag("SongIdentifier").w(e, "Mimikyu repo correlation failed for: %s", cleanQuery)
            null
        }

        if (mimikyuTrack != null) {
            val hdCover = com.lin0721.linmusic.core.metadata.SongArtworkEngine.optimizeArtworkUrl(
                mimikyuTrack.al.picUrl,
                com.lin0721.linmusic.core.metadata.SongArtworkEngine.PLAYER_ART_PX
            )
            return@withContext IdentifiedSong(
                title = mimikyuTrack.name,
                artist = mimikyuTrack.ar.joinToString(", ") { it.name },
                album = mimikyuTrack.al.name,
                coverUrl = hdCover,
                previewUrl = null,
                durationMs = mimikyuTrack.dt,
                mimikyuTrack = mimikyuTrack,
                source = "Mimikyu Music"
            )
        }

        // 2. Fallback to Deezer public catalogue
        val deezerResult = tryDeezerSearch(cleanQuery)
        if (deezerResult != null) {
            return@withContext deezerResult
        }

        null
    }

    private fun tryDeezerSearch(query: String): IdentifiedSong? {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val request = Request.Builder()
                .url("https://api.deezer.com/search?q=$encoded&limit=3")
                .header("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:109.0) Gecko/119.0 Firefox/119.0")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val json = JSONObject(body)
                val data = json.optJSONArray("data") ?: return null
                if (data.length() == 0) return null

                val item = data.getJSONObject(0)
                val title = item.optString("title")
                val artistObj = item.optJSONObject("artist")
                val artistName = artistObj?.optString("name").orEmpty()
                val albumObj = item.optJSONObject("album")
                val albumTitle = albumObj?.optString("title").orEmpty()
                val cover = albumObj?.optString("cover_xl")
                    ?: albumObj?.optString("cover_big")
                    ?: albumObj?.optString("cover_medium").orEmpty()
                val preview = item.optString("preview")
                val duration = item.optLong("duration", 0L) * 1000L

                IdentifiedSong(
                    title = title,
                    artist = artistName,
                    album = albumTitle,
                    coverUrl = cover,
                    previewUrl = preview.takeIf { it.isNotBlank() },
                    durationMs = duration,
                    source = "Deezer"
                )
            }
        } catch (e: Exception) {
            Timber.tag("SongIdentifier").w(e, "Deezer lookup failed")
            null
        }
    }
}
