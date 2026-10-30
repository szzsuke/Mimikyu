package com.lin0721.linmusic.feature.player.ui

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.view.TextureView
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.lin0721.linmusic.core.canvas.CanvasArtwork
import timber.log.Timber

enum class CanvasContentMode {
    CROP,
    FIT_PORTRAIT
}

private const val TAG = "CanvasArtworkPlayer"

/**
 * Renders a looping motion artwork/canvas video seamlessly over still album art.
 *
 * Mutes audio output completely so song playback is never interrupted or ducked.
 * Renders silently on a TextureView and smoothly fades in once the first frame is painted.
 */
@OptIn(UnstableApi::class)
@Composable
fun CanvasArtworkPlayer(
    canvas: CanvasArtwork,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    contentMode: CanvasContentMode = CanvasContentMode.CROP,
    alignPortraitTop: Boolean = false,
    shape: Shape? = null,
    onRenderedChanged: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    var isRendered by remember(canvas.url) { mutableStateOf(false) }
    var currentUrl by remember(canvas.url) { mutableStateOf(canvas.url) }
    var triedFallback by remember(canvas.url) { mutableStateOf(false) }
    var videoWidth by remember { mutableFloatStateOf(0f) }
    var videoHeight by remember { mutableFloatStateOf(0f) }

    val alpha by animateFloatAsState(
        targetValue = if (isRendered) 1f else 0f,
        animationSpec = tween(400),
        label = "canvas_fade"
    )

    val currentPlaying by rememberUpdatedState(isPlaying)

    val player = remember(context) {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context))
            .build().apply {
                repeatMode = Player.REPEAT_MODE_ALL
                volume = 0f
                // Disable audio track completely
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_AUDIO, true)
                    .build()
            }
    }

    DisposableEffect(player) {
        onDispose {
            player.stop()
            player.release()
        }
    }

    LaunchedEffect(currentUrl) {
        isRendered = false
        onRenderedChanged(false)
        val mediaItem = MediaItem.fromUri(currentUrl)
        player.setMediaItem(mediaItem)
        player.prepare()
        if (currentPlaying) {
            player.play()
        }
    }

    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            if (!player.isPlaying && player.playbackState != Player.STATE_ENDED) {
                player.play()
            }
        } else {
            player.pause()
        }
    }

    DisposableEffect(player, canvas, currentUrl) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                isRendered = true
                onRenderedChanged(true)
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    videoWidth = videoSize.width.toFloat()
                    videoHeight = videoSize.height.toFloat()
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Timber.tag(TAG).w("Error playing canvas $currentUrl: ${error.message}")
                if (!triedFallback && canvas.fallbackUrl != null && canvas.fallbackUrl != currentUrl) {
                    triedFallback = true
                    currentUrl = canvas.fallbackUrl
                } else {
                    isRendered = false
                    onRenderedChanged(false)
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
        }
    }

    val boxModifier = modifier
        .alpha(alpha)
        .then(if (shape != null) Modifier.clip(shape) else Modifier)

    Box(modifier = boxModifier) {
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                            player.setVideoTextureView(this@apply)
                            applyVideoMatrix(this@apply, width, height, videoWidth, videoHeight, contentMode, alignPortraitTop)
                        }

                        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                            applyVideoMatrix(this@apply, width, height, videoWidth, videoHeight, contentMode, alignPortraitTop)
                        }

                        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                            player.clearVideoTextureView(this@apply)
                            return true
                        }

                        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
                    }
                }
            },
            update = { textureView ->
                applyVideoMatrix(
                    textureView,
                    textureView.width,
                    textureView.height,
                    videoWidth,
                    videoHeight,
                    contentMode,
                    alignPortraitTop
                )
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

private fun applyVideoMatrix(
    textureView: TextureView,
    viewWidth: Int,
    viewHeight: Int,
    videoWidth: Float,
    videoHeight: Float,
    contentMode: CanvasContentMode,
    alignPortraitTop: Boolean
) {
    if (viewWidth <= 0 || viewHeight <= 0 || videoWidth <= 0 || videoHeight <= 0) return

    val matrix = Matrix()
    val viewAspect = viewWidth.toFloat() / viewHeight.toFloat()
    val videoAspect = videoWidth / videoHeight

    val scaleX: Float
    val scaleY: Float

    when (contentMode) {
        CanvasContentMode.CROP -> {
            if (videoAspect > viewAspect) {
                scaleY = 1f
                scaleX = (videoAspect / viewAspect)
            } else {
                scaleX = 1f
                scaleY = (viewAspect / videoAspect)
            }
        }
        CanvasContentMode.FIT_PORTRAIT -> {
            if (videoAspect > viewAspect) {
                scaleX = 1f
                scaleY = viewAspect / videoAspect
            } else {
                scaleX = 1f
                scaleY = 1f
            }
        }
    }

    val pivotX = viewWidth / 2f
    val pivotY = if (alignPortraitTop) 0f else viewHeight / 2f
    matrix.setScale(scaleX, scaleY, pivotX, pivotY)
    textureView.setTransform(matrix)
}
