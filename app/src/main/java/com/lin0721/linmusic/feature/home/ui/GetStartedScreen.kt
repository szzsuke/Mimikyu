package com.lin0721.linmusic.feature.home.ui

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.lin0721.linmusic.R
import com.lin0721.linmusic.core.ui.theme.BackgroundDark
import com.lin0721.linmusic.core.ui.theme.MimikyuYellow
import com.lin0721.linmusic.core.ui.theme.MimikyuCharcoal
import com.lin0721.linmusic.core.ui.theme.MimikyuSilver

@Composable
fun GetStartedScreen(
    onGetStarted: () -> Unit,
    onSkip: () -> Unit
) {
    val context = LocalContext.current
    var mediaPlayerRef by remember { mutableStateOf<MediaPlayer?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            mediaPlayerRef?.run {
                if (isPlaying) stop()
                release()
            }
            mediaPlayerRef = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark)
    ) {
        // 1. Cinematic background video container - fills full screen seamlessly without clipping artifacts
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds()
        ) {
            AndroidView(
                factory = { ctx ->
                    val textureView = TextureView(ctx)
                    textureView.apply {
                        layoutParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT
                        )
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            private fun applyCenterCropTransform(viewW: Int, viewH: Int, player: MediaPlayer?) {
                                if (viewW <= 0 || viewH <= 0 || player == null) return
                                val vW = player.videoWidth.toFloat()
                                val vH = player.videoHeight.toFloat()
                                if (vW <= 0f || vH <= 0f) return

                                val scaleX = viewW.toFloat() / vW
                                val scaleY = viewH.toFloat() / vH
                                val maxScale = maxOf(scaleX, scaleY)

                                val matrix = Matrix()
                                matrix.setScale(
                                    (vW * maxScale) / viewW.toFloat(),
                                    (vH * maxScale) / viewH.toFloat(),
                                    viewW / 2f,
                                    viewH / 2f
                                )
                                setTransform(matrix)
                            }

                            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                                try {
                                    val player = MediaPlayer().apply {
                                        setSurface(Surface(surface))
                                        val afd = ctx.resources.openRawResourceFd(R.raw.welcome_video)
                                        setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                                        afd.close()
                                        isLooping = true
                                        setVolume(0f, 0f)
                                        setOnVideoSizeChangedListener { mp, _, _ ->
                                            applyCenterCropTransform(textureView.width, textureView.height, mp)
                                        }
                                        prepareAsync()
                                        setOnPreparedListener { mp ->
                                            applyCenterCropTransform(textureView.width, textureView.height, mp)
                                            mp.start()
                                        }
                                    }
                                    mediaPlayerRef = player
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }

                            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                                applyCenterCropTransform(width, height, mediaPlayerRef)
                            }

                            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                                mediaPlayerRef?.run {
                                    if (isPlaying) stop()
                                    release()
                                }
                                mediaPlayerRef = null
                                return true
                            }

                            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // 2. Black gradient overlay flowing upward to smoothly cover bottom 40-45% of the screen
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.00f to Color.Black.copy(alpha = 0.40f), // Soft status bar shadow
                            0.12f to Color.Transparent,
                            0.50f to Color.Transparent,               // Upper 50% crisp cinematic view
                            0.58f to Color.Black.copy(alpha = 0.25f), // Smooth upward transition begins
                            0.66f to Color.Black.copy(alpha = 0.65f),
                            0.74f to Color.Black.copy(alpha = 0.92f),
                            0.80f to Color.Black,                     // Completely solid black covering all content & artifacts
                            1.00f to Color.Black
                        )
                    )
                )
        )

        // 2. Content area at the bottom over the solid black background
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 28.dp, vertical = 20.dp)
        ) {
            Text(
                text = "Music without\nborders",
                color = MimikyuSilver,
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 46.sp
            )

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "Create playlists, find new tracks and listen to\nyour favorite music anytime!",
                color = Color(0xFFA0A0A0),
                fontSize = 15.sp,
                lineHeight = 22.sp
            )

            Spacer(modifier = Modifier.height(34.dp))

            // Big Capsule Get Started Button
            Button(
                onClick = onGetStarted,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MimikyuYellow,
                    contentColor = MimikyuCharcoal
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text(
                    text = "Get started",
                    color = MimikyuCharcoal,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Skip, not now Text Button
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                TextButton(
                    onClick = onSkip,
                    modifier = Modifier.height(44.dp)
                ) {
                    Text(
                        text = "Skip, not now",
                        color = Color(0xFFA0A0A0),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}
