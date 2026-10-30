package com.lin0721.linmusic.core.player

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.lin0721.linmusic.core.download.DownloadPreferences
import com.lin0721.linmusic.core.localmusic.LocalCoverArtCache
import com.lin0721.linmusic.core.metadata.SongArtworkEngine
import com.lin0721.linmusic.core.player.innertube.YouTubeIdRegistry
import org.koin.compose.koinInject

@Composable
fun rememberQueueItemCoverUrl(
    coverUrl: String,
    songId: Long,
    localUri: String?,
    title: String = "",
    artist: String = ""
): String {
    // 1. Local track cover resolution
    val coverCache: LocalCoverArtCache = koinInject()
    val downloadPreferences: DownloadPreferences = koinInject()
    val localResolved by produceState(initialValue = "", songId, localUri) {
        val sourceUri = localUri?.let { Uri.parse(it) }
            ?: downloadPreferences.findVerifiedRecord(songId)?.mediaStoreUri?.let { Uri.parse(it) }
        value = sourceUri?.let { coverCache.coverUriFor(it) }?.toString() ?: ""
    }
    if (localResolved.isNotBlank()) return localResolved

    // 2. Return the song's genuine cover upscaled to 1200px HD
    val regCover = YouTubeIdRegistry.getCoverUrl(songId).takeIf { it.isNotBlank() }
    val candidate = coverUrl.ifBlank { regCover.orEmpty() }
    return SongArtworkEngine.optimizeArtworkUrl(candidate, SongArtworkEngine.PLAYER_ART_PX)
}
