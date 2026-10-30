package com.lin0721.linmusic.core.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import com.lin0721.linmusic.core.ui.theme.MimikyuSpacing
import com.lin0721.linmusic.core.ui.theme.SurfaceDark
import com.lin0721.linmusic.core.ui.theme.SurfaceLight
import com.lin0721.linmusic.core.ui.theme.MimikyuSilver

// Shimmer background using Gray 200 (SurfaceDark) base
@Composable
fun Modifier.shimmerBackground(shape: Shape = RectangleShape): Modifier {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateAnim by transition.animateFloat(
        initialValue = -600f,
        targetValue = 600f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_translate"
    )
    val base = SurfaceDark
    val highlight = SurfaceLight
    val brush = Brush.linearGradient(
        colors = listOf(base, highlight, base),
        start = Offset(translateAnim, 0f),
        end = Offset(translateAnim + 400f, 0f)
    )
    return this.background(brush = brush, shape = shape)
}

// Cover loading placeholder wireframe
@Composable
fun CoverPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SurfaceDark),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Rounded.MusicNote,
            contentDescription = null,
            tint = MimikyuSilver.copy(alpha = 0.35f),
            modifier = Modifier.fillMaxSize(0.34f)
        )
    }
}

// of ：，， loading/success ，
// SubcomposeAsyncImage 。 modifier /
@Composable
fun AntiFlickerCoverImage(
    url: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    onImageLoaded: ((android.graphics.Bitmap) -> Unit)? = null
) {
    val context = LocalContext.current
    var isLoaded by remember(url) { mutableStateOf(false) }
    var isError by remember(url) { mutableStateOf(false) }

    val cleanUrl = remember(url) {
        if (url.isBlank()) ""
        else url.replace(Regex("\\?param=\\d+y\\d+.*$"), "")
            .replace(Regex("\\?param=[^&]*"), "")
            .trim()
    }

    val fastUrl = remember(cleanUrl) {
        if (cleanUrl.isBlank()) ""
        else com.lin0721.linmusic.core.metadata.SongArtworkEngine.optimizeArtworkUrl(cleanUrl, 226)
    }
    val highUrl = remember(cleanUrl) {
        if (cleanUrl.isBlank()) ""
        else com.lin0721.linmusic.core.metadata.SongArtworkEngine.optimizeArtworkUrl(cleanUrl, 1200)
    }

    Box(modifier = modifier) {
        if (cleanUrl.isEmpty() || (!isLoaded && isError)) {
            CoverPlaceholder()
        }

        if (cleanUrl.isNotEmpty()) {
            // Progressive: bottom layer renders the fast 226x226 thumbnail instantly from CDN cache
            if (fastUrl != highUrl && fastUrl.isNotBlank()) {
                AsyncImage(
                    model = remember(fastUrl) {
                        ImageRequest.Builder(context)
                            .data(fastUrl)
                            .crossfade(false)
                            .build()
                    },
                    contentDescription = null,
                    contentScale = contentScale,
                    onState = { state ->
                        if (state is AsyncImagePainter.State.Success) {
                            isLoaded = true
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Top layer crossfades in the crisp 800x800 high-quality art once downloaded
            AsyncImage(
                model = remember(highUrl) {
                    ImageRequest.Builder(context)
                        .data(highUrl)
                        .crossfade(200)
                        .allowHardware(false)
                        .build()
                },
                contentDescription = null,
                contentScale = contentScale,
                onState = { state ->
                    when (state) {
                        is AsyncImagePainter.State.Success -> {
                            isLoaded = true
                            isError = false
                            val drawable = state.result.drawable
                            val bitmap = (drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                                ?: try {
                                    drawable.toBitmap()
                                } catch (e: Exception) {
                                    null
                                }
                            if (bitmap != null) {
                                onImageLoaded?.invoke(bitmap)
                            }
                        }
                        is AsyncImagePainter.State.Error -> {
                            if (fastUrl == highUrl) {
                                isLoaded = false
                                isError = true
                            }
                        }
                        else -> Unit
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

// EntityRow/SongRow： + ，Search
@Composable
fun SearchResultRowSkeleton() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MimikyuSpacing.md, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(
            modifier = Modifier
                .size(48.dp)
                .shimmerBackground(MaterialTheme.shapes.extraSmall)
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Spacer(
                modifier = Modifier
                    .width(160.dp)
                    .height(15.dp)
                    .shimmerBackground(RoundedCornerShape(4.dp))
            )
            Spacer(Modifier.height(8.dp))
            Spacer(
                modifier = Modifier
                    .width(100.dp)
                    .height(13.dp)
                    .shimmerBackground(RoundedCornerShape(4.dp))
            )
        }
    }
}

// /Playlists：，
@Composable
fun DiscoverySectionSkeleton() {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = MimikyuSpacing.md)) {
        repeat(3) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = MimikyuSpacing.sm),
                horizontalArrangement = Arrangement.spacedBy(MimikyuSpacing.sm)
            ) {
                Spacer(
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .shimmerBackground(RoundedCornerShape(8.dp))
                )
                Spacer(
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .shimmerBackground(RoundedCornerShape(8.dp))
                )
            }
        }
    }
}

// Playlist screen loading wireframe with Gray 200 shimmer
@Composable
fun PlaylistScreenSkeleton() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(32.dp))
        // Cover square wireframe
        Spacer(
            modifier = Modifier
                .size(200.dp)
                .shimmerBackground(RoundedCornerShape(12.dp))
        )
        Spacer(Modifier.height(24.dp))
        // Title line
        Spacer(
            modifier = Modifier
                .width(220.dp)
                .height(24.dp)
                .shimmerBackground(RoundedCornerShape(6.dp))
        )
        Spacer(Modifier.height(10.dp))
        // Subtitle line
        Spacer(
            modifier = Modifier
                .width(140.dp)
                .height(16.dp)
                .shimmerBackground(RoundedCornerShape(4.dp))
        )
        Spacer(Modifier.height(28.dp))
        // Action buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            Spacer(
                modifier = Modifier
                    .width(130.dp)
                    .height(44.dp)
                    .shimmerBackground(RoundedCornerShape(22.dp))
            )
            Spacer(Modifier.width(16.dp))
            Spacer(
                modifier = Modifier
                    .width(130.dp)
                    .height(44.dp)
                    .shimmerBackground(RoundedCornerShape(22.dp))
            )
        }
        Spacer(Modifier.height(32.dp))
        // Track rows
        repeat(5) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(
                    modifier = Modifier
                        .size(46.dp)
                        .shimmerBackground(RoundedCornerShape(6.dp))
                )
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Spacer(
                        modifier = Modifier
                            .fillMaxWidth(0.65f)
                            .height(15.dp)
                            .shimmerBackground(RoundedCornerShape(4.dp))
                    )
                    Spacer(Modifier.height(6.dp))
                    Spacer(
                        modifier = Modifier
                            .fillMaxWidth(0.40f)
                            .height(12.dp)
                            .shimmerBackground(RoundedCornerShape(4.dp))
                    )
                }
            }
        }
    }
}

