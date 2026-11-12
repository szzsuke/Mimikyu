package com.lin0721.linmusic.core.player

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.LruCache
import com.lin0721.linmusic.core.log.AppLogger
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "TrackEdgeAnalyzer"
private const val TAIL_SPAN_MS = 30_000L
private const val HEAD_SPAN_MS = 12_000L
private const val ANALYSIS_TIMEOUT_MS = 20_000L
private const val CODEC_TIMEOUT_US = 10_000L

/** 100ms loudness window */
data class LoudnessWindow(val startMs: Long, val dbfs: Double)

object TrackEdges {
    const val WINDOW_MS = 100L
    const val AUDIBLE_DBFS = -45.0
    private const val HEAD_PADDING_MS = 100L

    fun dbfs(sumSquares: Double, sampleCount: Long): Double {
        if (sampleCount <= 0L || sumSquares <= 0.0) return Double.NEGATIVE_INFINITY
        return 20 * log10(sqrt(sumSquares / sampleCount))
    }

    /** Find end of content excluding trailing silence */
    fun effectiveEndMs(windows: List<LoudnessWindow>): Long? =
        windows.lastOrNull { it.dbfs > AUDIBLE_DBFS }?.let { it.startMs + WINDOW_MS }

    /** Find start of audible sound excluding leading silence */
    fun effectiveStartMs(windows: List<LoudnessWindow>): Long? =
        windows.firstOrNull { it.dbfs > AUDIBLE_DBFS }?.let { (it.startMs - HEAD_PADDING_MS).coerceAtLeast(0L) }

    /**
     * Detects where the musical outro / fadeout starts in the last 30s.
     *
     * Identifies energy drops (fadeout, breakdown, outro drop) so that Automix
     * starts blending at the natural musical conclusion of the song rather than
     * at an arbitrary fixed second before the file ends.
     */
    fun detectOutroStartMs(windows: List<LoudnessWindow>, contentEndMs: Long, durationMs: Long): Long {
        if (windows.isEmpty() || contentEndMs <= 0L) {
            return (contentEndMs - 16_000L).coerceAtLeast(0L)
        }

        val audible = windows.filter { it.dbfs > AUDIBLE_DBFS && it.startMs < contentEndMs }
        if (audible.size < 20) {
            // Less than 2 seconds of audible audio in tail
            return (contentEndMs - 16_000L).coerceAtLeast(0L)
        }

        // 1. Reference loudness from the first half of the tail (when song is still going)
        val earlyAudible = audible.take((audible.size * 0.45).toInt().coerceAtLeast(5))
        val referenceDbfs = earlyAudible.map { it.dbfs }.sorted().let {
            it[(it.size * 0.75).toInt()]
        }

        // 2. Scan forward for a sustained drop of >= 5.0 dB below reference
        // (perceived volume drops by ~40%, signifying fadeout or outro breakdown)
        val dropThreshold = referenceDbfs - 5.0
        var sustainedDropStart: Long? = null
        var dropCount = 0

        for (w in audible) {
            if (w.dbfs <= dropThreshold) {
                if (sustainedDropStart == null) sustainedDropStart = w.startMs
                dropCount++
            } else {
                // Volume bounced back up
                if (dropCount < 15) { // less than 1.5s of sustained drop
                    sustainedDropStart = null
                    dropCount = 0
                }
            }
        }

        val detectedOutro = if (dropCount >= 15 && sustainedDropStart != null) {
            sustainedDropStart
        } else {
            // No gradual fadeout detected; song plays at full energy until ending.
            // Musical phrase mix-out: start 16 seconds (32 beats @ 120bpm) before content end.
            contentEndMs - 16_000L
        }

        // Clamp to a safe musical window: between 30s before end and 8s before end
        val minOutro = (contentEndMs - 28_000L).coerceAtLeast(0L)
        val maxOutro = (contentEndMs - 8_000L).coerceAtLeast(0L)
        return detectedOutro.coerceIn(minOutro, maxOutro)
    }
}

class TrackEdgeAnalyzer(private val context: Context) {

    private val endCache = LruCache<String, Long>(64)
    private val startCache = LruCache<String, Long>(64)
    private val outroCache = LruCache<String, Long>(64)

    suspend fun effectiveEndMs(key: String, uri: String, durationMs: Long): Long? {
        endCache.get(key)?.let { return it }
        if (durationMs <= 0L) return null
        val windows = decodeWindows(uri, (durationMs - TAIL_SPAN_MS).coerceAtLeast(0L), Long.MAX_VALUE) ?: return null
        return TrackEdges.effectiveEndMs(windows)?.also { endCache.put(key, it) }
    }

    suspend fun effectiveStartMs(key: String, uri: String): Long? {
        startCache.get(key)?.let { return it }
        val windows = decodeWindows(uri, 0L, HEAD_SPAN_MS) ?: return null
        return TrackEdges.effectiveStartMs(windows)?.also { startCache.put(key, it) }
    }

    suspend fun detectOutroStartMs(key: String, uri: String, durationMs: Long, contentEndMs: Long): Long {
        outroCache.get(key)?.let { return it }
        if (durationMs <= 0L) return (durationMs - 16_000L).coerceAtLeast(0L)
        val windows = decodeWindows(uri, (durationMs - TAIL_SPAN_MS).coerceAtLeast(0L), Long.MAX_VALUE)
        val outro = if (windows != null) {
            TrackEdges.detectOutroStartMs(windows, contentEndMs, durationMs)
        } else {
            (contentEndMs - 16_000L).coerceAtLeast(0L)
        }
        outroCache.put(key, outro)
        return outro
    }

    private suspend fun decodeWindows(uri: String, fromMs: Long, toMs: Long): List<LoudnessWindow>? =
        withContext(Dispatchers.IO) {
            val timeout = if (uri.startsWith("http")) 3000L else ANALYSIS_TIMEOUT_MS
            withTimeoutOrNull(timeout) {
                runCatching { decode(uri, fromMs, toMs) }
                    .onFailure { AppLogger.w(TAG, "Edge decode failed from=$fromMs to=$toMs: ${it.message}") }
                    .getOrNull()
            }
        }

    private suspend fun decode(uri: String, fromMs: Long, toMs: Long): List<LoudnessWindow>? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            if (uri.startsWith("http")) {
                extractor.setDataSource(uri, mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"))
            } else {
                extractor.setDataSource(context, Uri.parse(uri), null)
            }
            val trackIndex = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            extractor.selectTrack(trackIndex)
            extractor.seekTo(fromMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()

            val accumulator = WindowAccumulator(fromMs, toMs)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var floatPcm = false

            while (!outputDone) {
                coroutineContext.ensureActive()
                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buffer = decoder.getInputBuffer(inIndex) ?: return null
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0 || extractor.sampleTime / 1000 > toMs) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = decoder.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFormat = decoder.outputFormat
                        sampleRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        floatPcm = outFormat.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    }
                    outIndex >= 0 -> {
                        val out = decoder.getOutputBuffer(outIndex)
                        if (out != null && info.size > 0 && sampleRate > 0 && channels > 0) {
                            out.position(info.offset)
                            out.limit(info.offset + info.size)
                            out.order(ByteOrder.nativeOrder())
                            val frameCount = if (floatPcm) info.size / 4 / channels else info.size / 2 / channels
                            for (frame in 0 until frameCount) {
                                var frameSquares = 0.0
                                for (ch in 0 until channels) {
                                    val sample = if (floatPcm) {
                                        out.float.toDouble()
                                    } else {
                                        out.short / 32768.0
                                    }
                                    frameSquares += sample * sample
                                }
                                val timeMs = info.presentationTimeUs / 1000 + frame * 1000L / sampleRate
                                accumulator.add(timeMs, frameSquares / channels)
                            }
                        }
                        decoder.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            return accumulator.finish()
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private class WindowAccumulator(private val fromMs: Long, private val toMs: Long) {
        private val windows = mutableListOf<LoudnessWindow>()
        private var windowStartMs = -1L
        private var sumSquares = 0.0
        private var count = 0L

        fun add(timeMs: Long, meanSquare: Double) {
            if (timeMs < fromMs || timeMs > toMs) return
            val start = timeMs / TrackEdges.WINDOW_MS * TrackEdges.WINDOW_MS
            if (start != windowStartMs) {
                flush()
                windowStartMs = start
            }
            sumSquares += meanSquare
            count++
        }

        fun finish(): List<LoudnessWindow> {
            flush()
            return windows
        }

        private fun flush() {
            if (windowStartMs >= 0 && count > 0) {
                windows += LoudnessWindow(windowStartMs, TrackEdges.dbfs(sumSquares, count))
            }
            sumSquares = 0.0
            count = 0
        }
    }
}
