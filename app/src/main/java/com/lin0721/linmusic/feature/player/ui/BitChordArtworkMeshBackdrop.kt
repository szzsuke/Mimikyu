package com.lin0721.linmusic.feature.player.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.random.Random

private val meshCache = object : LinkedHashMap<String, ImageBitmap>(0, 0.75f, true) {
    override fun removeEldestEntry(eldest: Map.Entry<String, ImageBitmap>) = size > 64
}

private const val MESH_GRID = 6
private const val MESH_TEX = 32
private const val MESH_VIBRANCE = 1.15f
private const val MESH_FLOOR = 0.045f

/**
 * BitChord's authentic ArtworkMeshBackdrop:
 * Reduces the album artwork to a 6x6 grid of pixel means (preserving true dark/light proportions),
 * rotates rows below the seam sideways with random shift and mirror, interpolates to 32x32 texture
 * with smoothstep curve on CPU, and stretches across screen with bilinear hardware sampler and 32dp blur.
 */
@Composable
fun BitChordArtworkMeshBackdrop(
    bitmap: Bitmap?,
    coverUrl: String? = null,
    seed: Int = 0,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var currentMesh by remember { mutableStateOf<ImageBitmap?>(null) }
    var previousMesh by remember { mutableStateOf<ImageBitmap?>(null) }
    val fade = remember { Animatable(1f) }

    LaunchedEffect(bitmap, coverUrl, seed) {
        val cacheKey = coverUrl?.takeIf { it.isNotBlank() } ?: "seed_$seed"
        val cached = meshCache[cacheKey]
        if (cached != null) {
            if (currentMesh !== cached) {
                previousMesh = currentMesh
                currentMesh = cached
                fade.snapTo(0f)
                fade.animateTo(1f, tween(700, easing = FastOutSlowInEasing))
                previousMesh = null
            }
            return@LaunchedEffect
        }

        val sourceBitmap: Bitmap? = try {
            if (bitmap != null) {
                if (bitmap.config == Bitmap.Config.HARDWARE) {
                    bitmap.copy(Bitmap.Config.ARGB_8888, false)
                } else {
                    bitmap
                }
            } else if (!coverUrl.isNullOrBlank()) {
                withContext(Dispatchers.IO) {
                    try {
                        val request = ImageRequest.Builder(context)
                            .data(coverUrl)
                            .size(120)
                            .allowHardware(false)
                            .build()
                        val drawable = context.imageLoader.execute(request).drawable
                        drawable?.toBitmap()
                    } catch (e: Exception) {
                        null
                    }
                }
            } else null
        } catch (e: Exception) {
            null
        }

        if (sourceBitmap != null) {
            val mesh = withContext(Dispatchers.Default) {
                try {
                    generateMeshTexture(sourceBitmap, seed).asImageBitmap()
                } catch (e: Exception) {
                    null
                }
            }
            if (mesh != null) {
                meshCache[cacheKey] = mesh
                previousMesh = currentMesh
                currentMesh = mesh
                fade.snapTo(0f)
                fade.animateTo(1f, tween(700, easing = FastOutSlowInEasing))
                previousMesh = null
            }
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0C0C0E))
            .blur(32.dp)
    ) {
        val width = size.width.roundToInt()
        val height = size.height.roundToInt()

        previousMesh?.let { prev ->
            drawImage(
                image = prev,
                dstSize = IntSize(width, height),
                alpha = (1f - fade.value).coerceIn(0f, 1f),
                filterQuality = FilterQuality.Low
            )
        }
        currentMesh?.let { curr ->
            drawImage(
                image = curr,
                dstSize = IntSize(width, height),
                alpha = fade.value.coerceIn(0f, 1f),
                filterQuality = FilterQuality.Low
            )
        }

        // Soft dark vertical scrim matching BitChord
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color.Black.copy(alpha = 0.08f),
                    Color.Black.copy(alpha = 0.35f),
                )
            )
        )
    }
}

private fun generateMeshTexture(source: Bitmap, seed: Int): Bitmap {
    val width = source.width
    val height = source.height
    if (width < 1 || height < 1) {
        val blank = Bitmap.createBitmap(MESH_TEX, MESH_TEX, Bitmap.Config.ARGB_8888)
        blank.eraseColor(0xFF0C0C0E.toInt())
        return blank
    }

    val cols = MESH_GRID.coerceAtMost(width)
    val rows = MESH_GRID.coerceAtMost(height)
    val rowStep = (height / 128).coerceAtLeast(1)
    val colStep = (width / 128).coerceAtLeast(1)

    val cells = rows * cols
    val red = LongArray(cells)
    val green = LongArray(cells)
    val blue = LongArray(cells)
    val count = IntArray(cells)

    val line = IntArray(width)
    var y = 0
    while (y < height) {
        source.getPixels(line, 0, width, 0, y, width, 1)
        val rowBase = ((height - 1 - y) * rows / height) * cols
        var x = 0
        while (x < width) {
            val cell = rowBase + x * cols / width
            val pixel = line[x]
            red[cell] += (pixel shr 16) and 0xFF
            green[cell] += (pixel shr 8) and 0xFF
            blue[cell] += pixel and 0xFF
            count[cell]++
            x += colStep
        }
        y += rowStep
    }

    val grid = IntArray(cells) { cell ->
        val n = count[cell].coerceAtLeast(1)
        val r = (red[cell] / n).toInt().coerceIn(0, 255)
        val g = (green[cell] / n).toInt().coerceIn(0, 255)
        val b = (blue[cell] / n).toInt().coerceIn(0, 255)
        lifted(android.graphics.Color.rgb(r, g, b))
    }

    val rotated = rotatedBelowSeam(grid, cols, rows, seed)
    val resampledTexels = resampled(rotated, cols, rows, MESH_TEX)
    val meshBitmap = Bitmap.createBitmap(MESH_TEX, MESH_TEX, Bitmap.Config.ARGB_8888)
    meshBitmap.setPixels(resampledTexels, 0, MESH_TEX, 0, 0, MESH_TEX, MESH_TEX)
    return meshBitmap
}

private fun lifted(color: Int): Int {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(color, hsl)
    hsl[1] = (hsl[1] * MESH_VIBRANCE).coerceAtMost(1f)
    hsl[2] = hsl[2].coerceAtLeast(MESH_FLOOR)
    return ColorUtils.HSLToColor(hsl)
}

private fun rotatedBelowSeam(grid: IntArray, cols: Int, rows: Int, seed: Int): IntArray {
    if (rows <= 1) return grid
    val random = Random(seed)
    val mirror = random.nextBoolean()
    val shift = random.nextInt(cols)
    val out = grid.copyOf()
    for (row in 1 until rows) {
        val base = row * cols
        for (x in 0 until cols) {
            val src = if (mirror) cols - 1 - x else x
            out[base + x] = grid[base + (src + shift) % cols]
        }
    }
    return out
}

private fun resampled(grid: IntArray, cols: Int, rows: Int, size: Int): IntArray {
    val out = IntArray(size * size)
    for (ty in 0 until size) {
        val fy = (ty + 0.5f) / size * rows - 0.5f
        val y0 = floor(fy).toInt().coerceIn(0, rows - 1)
        val y1 = (y0 + 1).coerceAtMost(rows - 1)
        val wy = smoothstep(fy - y0)
        for (tx in 0 until size) {
            val fx = (tx + 0.5f) / size * cols - 0.5f
            val x0 = floor(fx).toInt().coerceIn(0, cols - 1)
            val x1 = (x0 + 1).coerceAtMost(cols - 1)
            val wx = smoothstep(fx - x0)
            val top = lerpArgb(grid[y0 * cols + x0], grid[y0 * cols + x1], wx)
            val bottom = lerpArgb(grid[y1 * cols + x0], grid[y1 * cols + x1], wx)
            out[ty * size + tx] = lerpArgb(top, bottom, wy)
        }
    }
    return out
}

private fun smoothstep(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}

private fun lerpArgb(from: Int, to: Int, t: Float): Int {
    if (t <= 0f) return from
    if (t >= 1f) return to
    fun channel(shift: Int): Int {
        val a = (from shr shift) and 0xFF
        val b = (to shr shift) and 0xFF
        return (a + ((b - a) * t)).roundToInt().coerceIn(0, 255)
    }
    val a = channel(24)
    val r = channel(16)
    val g = channel(8)
    val b = channel(0)
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}
