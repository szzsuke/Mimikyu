package com.lin0721.linmusic.core.player

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.lin0721.linmusic.core.metadata.YouTubeThumbnailUpscaler
import com.lin0721.linmusic.core.metadata.cleanMetadataText
import com.lin0721.linmusic.core.player.innertube.YouTubeIdRegistry
import kotlinx.serialization.Serializable

enum class PlayMode { SEQUENTIAL, LIST_LOOP, SINGLE_LOOP, SHUFFLE }

@Serializable
data class QueueItem(
    val songId: Long,
    val title: String,
    val artist: String,
    val coverUrl: String,
    // Local external audio URI, played locally if non-null
    val localUri: String? = null,
    // Persistent YouTube Video ID - primary durable identity of playable songs
    val videoId: String? = null,
    val album: String? = null,
    val durationSec: Int = 0,
    val uniqueQueueId: String = java.util.UUID.randomUUID().toString(),
    val isVideo: Boolean = false
) {
    /**
     * Resolves the effective YouTube Video ID for this item, falling back to registry if needed.
     */
    fun effectiveVideoId(): String? {
        return videoId?.takeIf { it.isNotBlank() } ?: YouTubeIdRegistry.getVideoId(songId)
    }

    fun toMediaItem(url: String, playContext: String? = null, artworkUri: String? = coverUrl): MediaItem {
        val cleanTitle = cleanMetadataText(title)
        val cleanArtist = cleanMetadataText(artist)
        val vid = effectiveVideoId()
        val upscaledArt = YouTubeThumbnailUpscaler.upscale(artworkUri) ?: artworkUri

        val bundle = Bundle().apply {
            putLong("songId", songId)
            if (!vid.isNullOrBlank()) putString("videoId", vid)
            if (playContext != null) putString("playContext", playContext)
            if (!album.isNullOrBlank()) putString("album", album)
            if (durationSec > 0) putInt("durationSec", durationSec)
        }

        val metadata = MediaMetadata.Builder()
            .setTitle(cleanTitle)
            .setArtist(cleanArtist)
            .setAlbumTitle(album?.takeIf { it.isNotBlank() })
            .setArtworkUri(upscaledArt?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) })
            .setExtras(bundle)
            .build()

        return MediaItem.Builder()
            .setUri(url)
            .setMediaId(songId.toString())
            .setMediaMetadata(metadata)
            .setCustomCacheKey(stableCacheKey(url, vid))
            .build()
    }

    /**
     * Stable disk cache key tied to the song identity (YouTube Video ID).
     * This ensures that when an expiring stream URL is refreshed, Media3's SimpleCache
     * recognizes already-buffered local chunks without re-downloading identical media.
     */
    private fun stableCacheKey(url: String, vid: String?): String? {
        val uri = Uri.parse(url)
        if (uri.scheme != "http" && uri.scheme != "https") return null
        return if (!vid.isNullOrBlank()) {
            "yt_$vid"
        } else {
            "song_$songId"
        }
    }
}
