package com.lin0721.linmusic.core.lyrics

import com.lin0721.linmusic.core.player.domain.LyricLine
import com.lin0721.linmusic.core.player.domain.LyricParser
import com.lin0721.linmusic.core.player.domain.LyricParser.estimateTextDurationMs
import com.mocharealm.accompanist.lyrics.core.model.ISyncedLine
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeAlignment
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeSyllable
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine

/**
 * Adapter converting Mimikyu's internal [LyricLine] domain model
 * into Accompanist's high-performance [SyncedLyrics] data structures.
 *
 * Automatically detects instrumental gaps between vocal lines and rescales
 * artificially-stretched syllables so that Apple Music breathing dots
 * pulse during instrumental sections.
 */
private val HAN_REGEX = Regex("""[\u4e00-\u9fff]""")

fun List<LyricLine>.toAccompanistSyncedLyrics(
    title: String = "",
    artist: String = ""
): SyncedLyrics {
    val syncedLines = mutableListOf<ISyncedLine>()

    val queryHasHan = HAN_REGEX.containsMatchIn(title) || HAN_REGEX.containsMatchIn(artist)

    val sanitizedInput = if (!queryHasHan) {
        this.filter { line ->
            line.isGap || !line.text.any { c -> c in '\u4e00'..'\u9fff' }
        }.map { line ->
            val transHasHan = line.translation?.any { c -> c in '\u4e00'..'\u9fff' } ?: false
            if (transHasHan) line.copy(translation = null) else line
        }
    } else {
        this
    }

    val nonGapLines = sanitizedInput.filter { !it.isGap && (it.text.isNotBlank() || it.words.isNotEmpty()) }
    val gapLines = sanitizedInput.filter { it.isGap }

    for (i in nonGapLines.indices) {
        val line = nonGapLines[i]
        val nextLine = nonGapLines.getOrNull(i + 1)
        val startMs = line.timeMs.toInt()

        val nextLineStartMs = nextLine?.timeMs
        val wordSpan = if (line.words.isNotEmpty()) {
            line.words.maxOf { it.startOffsetMs + it.durationMs }.coerceAtLeast(1L)
        } else {
            0L
        }
        val estimatedVocalDuration = estimateTextDurationMs(line.text)

        // The natural vocal duration of this line (including any duration parsed from line format or words)
        val naturalVocalDuration = when {
            wordSpan > 0L -> maxOf(wordSpan, line.durationMs)
            line.durationMs > 0L -> line.durationMs
            else -> estimatedVocalDuration
        }

        // Check if there is an explicit instrumental gap after this line of at least 3.5s (3,500ms)
        val explicitGapAfter = gapLines.firstOrNull {
            it.timeMs in line.timeMs until (nextLineStartMs ?: Long.MAX_VALUE) && it.durationMs >= 3500L
        }

        // Calculate time gap between end of vocals/sustain and next line start
        val pauseAfterVocals = if (nextLineStartMs != null) nextLineStartMs - (line.timeMs + naturalVocalDuration) else Long.MAX_VALUE
        val totalLineSpan = if (nextLineStartMs != null) nextLineStartMs - line.timeMs else 0L

        // Only insert or display the ... interlude indicator if the time gap is at least 3,500ms (3.5 seconds).
        // Short vocal pauses or breaths under 3.5s must NOT trigger an interlude.
        val isInstrumentalInterlude = explicitGapAfter != null || pauseAfterVocals >= 3500L

        val endMs = if (nextLineStartMs != null) {
            if (isInstrumentalInterlude) {
                // Real instrumental interlude (>= 3.5s gap):
                // Allow a trailing vocal sustain buffer after singing ends (e.g. 500ms) so trailing notes
                // are not cut off prematurely, but ensure at least 3,500ms remains for the interlude dots.
                val sustainBuffer = if (line.words.isNotEmpty() && line.durationMs <= wordSpan) 500L else 0L
                val vocalEnd = line.timeMs + naturalVocalDuration + sustainBuffer
                val maxEndAllowed = nextLineStartMs - 3500L
                val targetEnd = minOf(vocalEnd, maxEndAllowed).coerceAtLeast(line.timeMs + naturalVocalDuration)
                targetEnd.toInt()
            } else {
                // Short vocal pause, breath, or vocal sustain (< 3.5s gap):
                // Do NOT trigger an interlude. Extend the line end to nextLineStartMs so the singer's
                // trailing sustain remains active and focus seamlessly transitions to the next line.
                nextLineStartMs.toInt()
            }
        } else {
            (startMs + maxOf(naturalVocalDuration, 3000L)).toInt()
        }

        // Only rescale if words were artificially stretched across a massive instrumental break (>10s total span)
        val shouldScaleWords = isInstrumentalInterlude && totalLineSpan >= 10_000L && wordSpan > (estimatedVocalDuration + 5000L)
        val wordScaleFactor = if (shouldScaleWords && wordSpan > 0L) {
            (endMs - startMs).toFloat() / wordSpan
        } else 1f

        val alignment = if (line.isOppositeSide) KaraokeAlignment.End else KaraokeAlignment.Start

        val fixedText = LyricParser.fixWordGaps(line.text)
        val effectiveWords = if (line.words.isNotEmpty()) {
            LyricParser.alignWordsWithLineText(line.words, fixedText)
        } else {
            val dur = if (naturalVocalDuration > 0L) naturalVocalDuration else LyricParser.estimateTextDurationMs(fixedText)
            LyricParser.synthesizeWordTimings(line.copy(durationMs = dur, text = fixedText))
        }

        if (effectiveWords.isNotEmpty()) {
            val syllables = effectiveWords.mapIndexed { idx, word ->
                val text = word.text
                val rawOffset = if (shouldScaleWords) (word.startOffsetMs * wordScaleFactor).toLong() else word.startOffsetMs
                val rawDur = if (shouldScaleWords) (word.durationMs * wordScaleFactor).toLong() else word.durationMs
                val sylStart = (line.timeMs + rawOffset).toInt()
                val sylDuration = rawDur.toInt().coerceAtLeast(10)
                val sylEnd = (sylStart + sylDuration).coerceAtLeast(sylStart)
                KaraokeSyllable(
                    content = text,
                    start = sylStart,
                    end = sylEnd
                )
            }

            // Check if this is an accompaniment vocal (e.g. wrapped in parentheses like "(Yeah)")
            val isParenthesized = line.text.trim().let { it.startsWith("(") && it.endsWith(")") }
            if (isParenthesized) {
                syncedLines.add(
                    KaraokeLine.AccompanimentKaraokeLine(
                        syllables = syllables,
                        translation = line.translation?.takeIf { it.isNotBlank() },
                        alignment = alignment,
                        start = startMs,
                        end = endMs,
                        phonetic = line.roma?.takeIf { it.isNotBlank() }
                    )
                )
            } else {
                syncedLines.add(
                    KaraokeLine.MainKaraokeLine(
                        syllables = syllables,
                        translation = line.translation?.takeIf { it.isNotBlank() },
                        alignment = alignment,
                        start = startMs,
                        end = endMs,
                        phonetic = line.roma?.takeIf { it.isNotBlank() }
                    )
                )
            }
        } else {
            // Line-level synchronized fallback
            syncedLines.add(
                SyncedLine(
                    content = line.text,
                    start = startMs,
                    end = endMs,
                    translation = line.translation?.takeIf { it.isNotBlank() },
                    phonetic = line.roma?.takeIf { it.isNotBlank() }
                )
            )
        }
    }

    return SyncedLyrics(
        lines = syncedLines,
        title = title
    )
}
