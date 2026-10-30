package com.lin0721.linmusic.core.canvas

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber

/**
 * Community-curated song + artist -> looping canvas video index.
 * Fills catalog gaps for songs with user-verified motion covers.
 */
object CommunityCanvas {

    private const val TAG = "CommunityCanvas"
    private const val MANIFEST = "https://vivimusicanvas.mkmdevilmi.workers.dev/canvas.json"
    private const val TTL_MS = 30L * 60 * 1000

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private data class Entry(
        val song: String,
        val artist: String,
        val album: String,
        val url: String,
    )

    @Volatile private var entries: List<Entry> = emptyList()
    @Volatile private var fetchedAtMs = 0L

    fun search(title: String, artist: String, album: String?): CanvasArtwork? {
        val index = manifest().ifEmpty { return null }

        val wantTitle = title.normalizeForMatch()
        val wantArtist = artist.normalizeForMatch()
        val wantAlbum = album?.normalizeForMatch()

        val hit = index.firstOrNull { entry ->
            val song = entry.song.normalizeForMatch()
            val credited = entry.artist.normalizeForMatch()
            val listed = entry.album.normalizeForMatch()
            val titleOk = song.isNotBlank() &&
                (wantTitle.contains(song) || song.contains(wantTitle))
            val artistOk = credited.isNotBlank() &&
                (wantArtist.contains(credited) || credited.contains(wantArtist))
            val albumOk = listed.isBlank() || wantAlbum.isNullOrBlank() || listed == wantAlbum
            titleOk && artistOk && albumOk
        } ?: return null

        Timber.tag(TAG).d("Manifest hit for '${hit.song}' by '${hit.artist}'")
        return CanvasArtwork(
            url = hit.url,
            title = hit.song,
            artist = hit.artist,
            album = hit.album.takeIf { it.isNotBlank() },
            source = CanvasSource.COMMUNITY,
        )
    }

    fun searchAlbum(album: String, artist: String): CanvasArtwork? {
        val index = manifest().ifEmpty { return null }

        val wantAlbum = album.normalizeForMatch()
        val wantArtist = artist.normalizeForMatch()
        if (wantAlbum.isBlank()) return null

        val hit = index.firstOrNull { entry ->
            val listed = entry.album.normalizeForMatch()
            val credited = entry.artist.normalizeForMatch()
            listed == wantAlbum && credited.isNotBlank() &&
                (wantArtist.contains(credited) || credited.contains(wantArtist))
        } ?: return null

        Timber.tag(TAG).d("Manifest hit for album '${hit.album}' by '${hit.artist}'")
        return CanvasArtwork(
            url = hit.url,
            title = hit.album,
            artist = hit.artist,
            album = hit.album,
            source = CanvasSource.COMMUNITY,
        )
    }

    @Synchronized
    private fun manifest(): List<Entry> {
        val now = System.currentTimeMillis()
        if (entries.isNotEmpty() && now - fetchedAtMs < TTL_MS) return entries

        val body = CanvasHttp.get(MANIFEST) ?: return entries
        val parsed = runCatching {
            json.parseToJsonElement(body).jsonArray.mapNotNull { element ->
                val obj = element.jsonObject
                val url = obj["canvas_url"]?.jsonPrimitive?.contentOrNull
                    ?: obj["url"]?.jsonPrimitive?.contentOrNull
                    ?: return@mapNotNull null
                Entry(
                    song = obj["song_title"]?.jsonPrimitive?.contentOrNull
                        ?: obj["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    artist = obj["artist_name"]?.jsonPrimitive?.contentOrNull
                        ?: obj["artist"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    album = obj["album_name"]?.jsonPrimitive?.contentOrNull
                        ?: obj["album"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    url = url,
                )
            }
        }.getOrNull()

        if (!parsed.isNullOrEmpty()) {
            entries = parsed
            fetchedAtMs = now
            Timber.tag(TAG).d("Loaded ${entries.size} community canvas entries")
        }
        return entries
    }
}
