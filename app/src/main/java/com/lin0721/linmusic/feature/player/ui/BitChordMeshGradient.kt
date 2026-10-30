package com.lin0721.linmusic.feature.player.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import com.lin0721.linmusic.core.log.AppLogger
import com.lin0721.linmusic.core.ui.theme.vibrant.defaultVibrantFilter
import com.lin0721.linmusic.core.ui.theme.vibrant.medianCutQuantize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * BitChord's signature fallback colors for empty or unready artwork.
 */
val BitChordFallbackColors: List<Color> = listOf(
    Color(0xFF3A1C71),
    Color(0xFFD76D77),
    Color(0xFF2B5876),
    Color(0xFFFFAF7B),
)

object BitChordMeshPaletteCache {
    private val cache = android.util.LruCache<String, List<Color>>(64)
    fun get(key: String): List<Color>? = cache.get(key)
    fun put(key: String, colors: List<Color>) { cache.put(key, colors) }
}

/**
 * BitChord 4-blob animated mesh gradient backdrop:
 * Four luminous color blobs sampled from the album art, moving on irrational orbit speeds,
 * blurred into a rich dynamic mesh with a gentle readability scrim.
 */
@Composable
fun BitChordMeshGradient(
    colors: List<Color>,
    modifier: Modifier = Modifier,
    trackKey: Any? = null,
    driftMillis: Int = 8_000,
    continuous: Boolean = true,
    blurRadius: Dp = 64.dp,
    colorFadeMillis: Int = 1400,
) {
    val tuned = (colors.ifEmpty { BitChordFallbackColors } + BitChordFallbackColors)
        .take(4)
        .map { it.tuned() }

    val colorSpec: AnimationSpec<Color> = tween(colorFadeMillis)
    val animatedColors = tuned.mapIndexed { index, color ->
        animateColorAsState(color, colorSpec, label = "bitChordMeshColor$index").value
    }
    val baseColor by animateColorAsState(tuned.first().dimmed(), colorSpec, label = "bitChordMeshBase")

    val phase = remember { Animatable(0f) }
    LaunchedEffect(trackKey, continuous) {
        if (continuous) {
            while (isActive) {
                phase.animateTo(
                    targetValue = phase.value + (2 * PI).toFloat(),
                    animationSpec = tween(driftMillis * 4, easing = LinearEasing),
                )
            }
        } else {
            phase.animateTo(
                targetValue = phase.value + (PI * 0.45f).toFloat(),
                animationSpec = tween(driftMillis, easing = FastOutSlowInEasing),
            )
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .graphicsLayer {
                scaleX = 1.3f
                scaleY = 1.3f
            }
            .background(baseColor)
            .blur(blurRadius),
    ) {
        val anchors = listOf(
            Offset(0.20f, 0.25f),
            Offset(0.80f, 0.20f),
            Offset(0.75f, 0.80f),
            Offset(0.25f, 0.75f),
        )
        val speeds = listOf(1f, -0.7f, 0.85f, -1.15f)
        val drift = phase.value

        animatedColors.forEachIndexed { index, color ->
            val anchor = anchors[index]
            val center = Offset(
                x = (anchor.x + 0.16f * cos(drift * speeds[index] + index * 1.7f)) * size.width,
                y = (anchor.y + 0.16f * sin(drift * speeds[index] * 0.9f + index * 2.3f)) * size.height,
            )
            val radius = size.maxDimension * 0.62f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(color.copy(alpha = 0.85f), color.copy(alpha = 0f)),
                    center = center,
                    radius = radius,
                ),
                radius = radius,
                center = center,
            )
        }

        // Gentle scrim so white text and controls stay legible over bright artwork
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color.Black.copy(alpha = 0.10f),
                    Color.Black.copy(alpha = 0.38f),
                ),
            ),
        )
    }
}

/**
 * Extracts 4 distinct mesh colors from an input Bitmap on a worker thread.
 */
fun extractBitChordMeshColors(bitmap: Bitmap): List<Color> {
    return try {
        val safeBitmap = if (bitmap.config == Bitmap.Config.HARDWARE) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: bitmap
        } else {
            bitmap
        }
        val scaled = Bitmap.createScaledBitmap(safeBitmap, 64, 64, true)
        val pixels = IntArray(scaled.width * scaled.height)
        scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
        val swatches = medianCutQuantize(pixels, 24, ::defaultVibrantFilter)
            .ifEmpty { medianCutQuantize(pixels, 24) { _, _, _, a -> a >= 128 } }
            .sortedByDescending { it.population }
            .map { it.rgb }
        val distinct = swatches.distinctEnough()
        when {
            distinct.isEmpty() -> BitChordFallbackColors
            distinct.size >= 4 -> distinct.take(4)
            else -> distinct.expandedToFour()
        }
    } catch (e: Exception) {
        AppLogger.d("BitChordMeshGradient", "Mesh color extraction from bitmap failed", e)
        BitChordFallbackColors
    }
}

/**
 * Loads image thumbnail via Coil and extracts 4 mesh colors.
 */
suspend fun extractBitChordMeshColorsFromUrl(context: Context, url: String): List<Color> = withContext(Dispatchers.Default) {
    if (url.isBlank()) return@withContext BitChordFallbackColors
    try {
        val request = ImageRequest.Builder(context)
            .data(url)
            .size(128)
            .allowHardware(false)
            .build()
        val drawable = context.imageLoader.execute(request).drawable ?: return@withContext BitChordFallbackColors
        val bitmap = drawable.toBitmap()
        extractBitChordMeshColors(bitmap)
    } catch (e: Exception) {
        AppLogger.d("BitChordMeshGradient", "Mesh color extraction from URL failed", e)
        BitChordFallbackColors
    }
}

private fun List<Color>.distinctEnough(): List<Color> {
    val kept = mutableListOf<Color>()
    forEach { color -> if (kept.none { it.isCloseTo(color) }) kept += color }
    return kept
}

private fun Color.isCloseTo(other: Color): Boolean {
    val a = hsl()
    val b = other.hsl()
    val hueGap = abs(a[0] - b[0]).let { min(it, 360f - it) }
    return hueGap < 15f && abs(a[2] - b[2]) < 0.12f
}

private fun List<Color>.expandedToFour(): List<Color> {
    val out = toMutableList()
    var step = 1
    while (out.size < 4) {
        out += this[(out.size - size) % size].shifted(24f * step, 0.12f * step)
        step++
    }
    return out
}

private fun Color.shifted(hue: Float, lightness: Float): Color {
    val hsl = hsl()
    hsl[0] = (hsl[0] + hue) % 360f
    hsl[2] = (hsl[2] + lightness).coerceIn(0.2f, 0.7f)
    return Color(ColorUtils.HSLToColor(hsl))
}

private fun Color.hsl(): FloatArray =
    FloatArray(3).also { ColorUtils.colorToHSL(toArgb(), it) }

private fun Color.tuned(): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(toArgb(), hsl)
    hsl[1] = (hsl[1] * 1.35f).coerceAtMost(1f)
    hsl[2] = hsl[2].coerceIn(0.28f, 0.58f)
    return Color(ColorUtils.HSLToColor(hsl))
}

private fun Color.dimmed(): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(toArgb(), hsl)
    hsl[2] = 0.12f
    return Color(ColorUtils.HSLToColor(hsl))
}
