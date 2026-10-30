package com.lin0721.linmusic.core.ui.theme

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import com.lin0721.linmusic.core.log.AppLogger
import com.lin0721.linmusic.core.ui.theme.vibrant.VibrantSwatch
import com.lin0721.linmusic.core.ui.theme.vibrant.defaultVibrantFilter
import com.lin0721.linmusic.core.ui.theme.vibrant.hslToRgb
import com.lin0721.linmusic.core.ui.theme.vibrant.medianCutQuantize
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "ColorExtraction"

// Play of ：base/textHighlight Play UI  of
data class PlayerBackdropPalette(
    val base: Color,
    val textHighlight: Color,
    val bottomColor: Color = base,
)

// candidate swatch ，/，。
// （RGB ） HSL ：HSL  0 or 1
// ， 1  of "High Saturation"，
private const val MEANINGFUL_CHROMA = 0.03f

// of ，Play and Play
val FallbackBackdropPalette = PlayerBackdropPalette(
    base = FallbackBase,
    textHighlight = lerp(FallbackBase, Color.White, 0.85f),
    bottomColor = FallbackBase,
)

// node-vibrant ：64 、quality=5
private const val VIBRANT_COLOR_COUNT = 64
private const val VIBRANT_QUALITY = 5

// of （）， ArtistBackdrop  of  640 ，
private const val DECODE_MAX_DIMENSION = 640

// Palette extraction strictly runs on Dispatchers.Default, NEVER blocking the main thread
suspend fun extractBackdropPaletteFromUrl(context: Context, url: String): PlayerBackdropPalette = withContext(Dispatchers.Default) {
    val canonicalUrl = url.substringBefore("?param=")
    if (canonicalUrl.isBlank()) return@withContext FallbackBackdropPalette
    try {
        val request = ImageRequest.Builder(context)
            .data(canonicalUrl)
            .size(DECODE_MAX_DIMENSION, DECODE_MAX_DIMENSION)
            .allowHardware(false)
            .build()
        val drawable = context.imageLoader.execute(request).drawable ?: return@withContext FallbackBackdropPalette
        extractBackdropPalette(drawable)
    } catch (e: Exception) {
        AppLogger.d(TAG, "Color extraction request failed, using default dark palette", e)
        FallbackBackdropPalette
    }
}

suspend fun extractBaseColorFromUrl(context: Context, url: String): Color {
    return extractBackdropPaletteFromUrl(context, url).base
}

suspend fun extractBackdropPaletteFromBitmap(bitmap: Bitmap): PlayerBackdropPalette = withContext(Dispatchers.Default) {
    extractBackdropPaletteFromBitmapSync(bitmap)
}

fun extractBackdropPalette(drawable: android.graphics.drawable.Drawable): PlayerBackdropPalette {
    return try {
        val bitmap = drawable.toBitmap()
        extractBackdropPaletteFromBitmapSync(bitmap)
    } catch (e: Exception) {
        AppLogger.d(TAG, "Color extraction failed, using default dark palette", e)
        FallbackBackdropPalette
    }
}

fun extractBottomColorFromBitmap(bitmap: Bitmap): Color {
    return try {
        val safeBitmap = if (bitmap.config == Bitmap.Config.HARDWARE) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: bitmap
        } else {
            bitmap
        }
        val width = safeBitmap.width
        val height = safeBitmap.height
        if (width <= 0 || height <= 0) return FallbackBase

        val startY = (height * 0.95f).toInt().coerceIn(0, height - 1)
        val sampleHeight = (height - startY).coerceAtLeast(1)
        val pixels = IntArray(width * sampleHeight)
        safeBitmap.getPixels(pixels, 0, width, 0, startY, width, sampleHeight)

        var totalR = 0L
        var totalG = 0L
        var totalB = 0L
        val count = pixels.size.toLong().coerceAtLeast(1L)

        for (pixel in pixels) {
            totalR += (pixel shr 16) and 0xFF
            totalG += (pixel shr 8) and 0xFF
            totalB += pixel and 0xFF
        }

        val avgR = (totalR / count).toInt().coerceIn(0, 255)
        val avgG = (totalG / count).toInt().coerceIn(0, 255)
        val avgB = (totalB / count).toInt().coerceIn(0, 255)

        Color(avgR, avgG, avgB)
    } catch (e: Exception) {
        AppLogger.d(TAG, "Failed to extract bottom color from bitmap", e)
        FallbackBase
    }
}

fun extractBackdropPaletteFromBitmapSync(bitmap: Bitmap): PlayerBackdropPalette {
    return try {
        val bottomColor = extractBottomColorFromBitmap(bitmap)
        val scaled = scaleDownByQuality(bitmap, VIBRANT_QUALITY)
        val pixels = IntArray(scaled.width * scaled.height)
        scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)

        val quantized = medianCutQuantize(pixels, VIBRANT_COLOR_COUNT, ::defaultVibrantFilter)
        if (quantized.isEmpty()) return FallbackBackdropPalette.copy(bottomColor = bottomColor)

        // No chroma signal
        if (isGrayscaleSwatches(quantized)) {
            val base = grayscaleBaseColor(quantized)
            return PlayerBackdropPalette(
                base = base,
                textHighlight = lerp(base, Color.White, 0.85f),
                bottomColor = bottomColor,
            )
        }

        val base = pickBaseColor(quantized)
        PlayerBackdropPalette(
            base = base,
            textHighlight = lerp(base, Color.White, 0.85f),
            bottomColor = bottomColor,
        )
    } catch (e: Exception) {
        AppLogger.d(TAG, "Color extraction failed, using default dark palette", e)
        FallbackBackdropPalette
    }
}

// node-vibrant  of  Image.scaleDown： 1/quality ，
private fun scaleDownByQuality(bitmap: Bitmap, quality: Int): Bitmap {
    val ratio = 1f / quality
    val targetWidth = (bitmap.width * ratio).toInt().coerceAtLeast(1)
    val targetHeight = (bitmap.height * ratio).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
}

// quantized swatch ； swatch "No chroma signal"
internal fun isGrayscaleSwatches(swatches: List<VibrantSwatch>): Boolean =
    swatches.none { it.chroma >= MEANINGFUL_CHROMA }

// of ：（） of ，
// Play、 of
internal fun grayscaleBaseColor(swatches: List<VibrantSwatch>): Color {
    val dominant = swatches.maxByOrNull { it.population } ?: return FallbackBase
    val lightness = dominant.hsl[2].coerceIn(0.20f, 0.35f)
    return Color(android.graphics.Color.HSVToColor(floatArrayOf(0f, 0f, lightness)))
}

internal const val MIN_BASE_CHROMA = 0.15f
internal const val NEUTRAL_BASE_CHROMA = 0.04f

// Spotify-style atmospheric backdrop color extraction:
// Selects the dominant atmospheric hue from artwork swatches and normalizes lightness to 0.30-0.38
// and saturation to 0.22-0.48 so the background matches Spotify's rich artwork glow without washing out.
internal fun pickBaseColor(swatches: List<VibrantSwatch>): Color {
    val nonPureSwatches = swatches.filter {
        it.population > 0 && it.hsl[2] in 0.04f..0.96f
    }
    if (nonPureSwatches.isEmpty()) return FallbackBase

    // 1. Check if there are chromatic swatches (actual color) vs pure neutral
    val chromatic = nonPureSwatches.filter { it.chroma >= 0.05f }

    val chosen: VibrantSwatch = if (chromatic.isNotEmpty()) {
        // Find the dominant color in the artwork.
        // Population is the most important factor: what color actually covers the album!
        // We give a slight boost to chromatic presence so rich album tones win over neutral backgrounds if both exist,
        // but population dominates (75%) so tiny accents never hijack the backdrop.
        val maxPop = chromatic.maxOf { it.population }.toFloat()
        chromatic.maxBy { swatch ->
            val popScore = swatch.population / maxPop
            val chromaScore = (swatch.chroma.coerceIn(0f, 0.4f) / 0.4f)
            popScore * 0.75f + chromaScore * 0.25f
        }
    } else {
        // For black/white/monochrome covers, pick the largest population non-pure swatch
        nonPureSwatches.maxBy { it.population }
    }

    // 2. Keep the EXACT hue and natural saturation of the album cover!
    val h = chosen.hsl[0]
    val s = if (chosen.chroma >= 0.05f) {
        chosen.hsl[1].coerceIn(0.20f, 0.75f)
    } else {
        chosen.hsl[1].coerceIn(0.02f, 0.20f)
    }
    // Deep, atmospheric lightness matching Spotify player backdrop standards (0.18f - 0.34f)
    val l = chosen.hsl[2].coerceIn(0.18f, 0.34f)

    val (r, g, b) = hslToRgb(h, s, l)
    return Color(r, g, b)
}

// ，×；， 0
private fun weightedMeanHue(swatches: List<VibrantSwatch>): Float? {
    var x = 0.0
    var y = 0.0
    for (swatch in swatches) {
        val weight = swatch.population.toDouble() * swatch.chroma
        val angle = swatch.hsl[0] * 2.0 * PI
        x += weight * cos(angle)
        y += weight * sin(angle)
    }
    if (x == 0.0 && y == 0.0) return null
    val hue = (atan2(y, x) / (2.0 * PI)).toFloat()
    return if (hue < 0f) hue + 1f else hue
}

private fun hueDistance(a: Float, b: Float): Float {
    val d = abs(a - b) % 1f
    return min(d, 1f - d)
}

object PaletteMemoryCache {
    private val cache = android.util.LruCache<String, PlayerBackdropPalette>(150)

    fun get(mediaId: String): PlayerBackdropPalette? = cache.get(mediaId)

    fun put(mediaId: String, palette: PlayerBackdropPalette) {
        cache.put(mediaId, palette)
    }
}
