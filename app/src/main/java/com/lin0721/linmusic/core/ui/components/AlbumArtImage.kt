package com.lin0721.linmusic.core.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.lin0721.linmusic.core.metadata.ThumbnailUpscaleInterceptor
import com.lin0721.linmusic.core.player.innertube.YouTubeIdRegistry
import com.lin0721.linmusic.core.ui.theme.RadiusCompact

/**
 * Unified, reusable album artwork component for Mimikyu.
 *
 * Ensures consistent high-resolution image rendering across Home grid, Search,
 * Playlists, Library, and Player screens using BitChord's native thumbnail upscaler.
 */
@Composable
fun AlbumArtImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    songId: Long? = null,
    title: String? = null,
    artist: String? = null,
    shape: Shape = RoundedCornerShape(RadiusCompact),
    contentScale: ContentScale = ContentScale.Crop,
    loading: @Composable () -> Unit = { CoverPlaceholder() },
    error: @Composable () -> Unit = { CoverPlaceholder() }
) {
    val context = LocalContext.current
    val effectiveUrl = remember(url, songId) {
        val regCover = songId?.let { YouTubeIdRegistry.getCoverUrl(it) }?.takeIf { it.isNotBlank() }
        val raw = regCover ?: url.orEmpty()
        ThumbnailUpscaleInterceptor.resolveUrl(raw)
    }

    val request = remember(effectiveUrl) {
        if (effectiveUrl.isNotBlank()) {
            ImageRequest.Builder(context)
                .data(effectiveUrl)
                .crossfade(true)
                .build()
        } else null
    }

    Box(modifier = modifier.clip(shape)) {
        if (request != null) {
            SubcomposeAsyncImage(
                model = request,
                contentDescription = contentDescription,
                contentScale = contentScale,
                loading = { loading() },
                error = { error() },
                modifier = Modifier.matchParentSize()
            )
        } else {
            loading()
        }
    }
}
