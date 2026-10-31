package com.lin0721.linmusic.feature.player.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lin0721.linmusic.core.player.domain.LyricLine
import com.lin0721.linmusic.core.player.domain.LyricParser
import com.lin0721.linmusic.core.ui.theme.MimikyuPink
import com.lin0721.linmusic.core.ui.theme.MimikyuSpacing
import com.lin0721.linmusic.core.ui.theme.SFProFontFamily
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeAlignment
import com.mocharealm.accompanist.lyrics.ui.composable.lyrics.KaraokeBreathingDots
import com.mocharealm.accompanist.lyrics.ui.composable.lyrics.KaraokeBreathingDotsDefaults
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal val LOVE_WORDS_REGEX = Regex(
    "\\b(love|loves|loved|loving|lover|lovers|lovely|darling|darlin|darlin'|sweetheart|sweethearts|honey|sweetie|sweeties|dear|dearest|babe|babes|baby|kiss|kisses|kissed|kissing|hug|hugs|hugged|hugging|cherish|cherishes|cherished|cherishing|adore|adores|adored|adoring|beloved|heart|hearts|heartbeat|heartbeats|heartbreak|heartbroken|romance|romantic|passion|passionate|affection|affectionate|forever|amour|amore)\\b",
    RegexOption.IGNORE_CASE
)

fun appendStyledLoveWord(
    builder: AnnotatedString.Builder,
    wordText: String,
    isPastOrCurrent: Boolean
) {
    val matches = LOVE_WORDS_REGEX.findAll(wordText).toList()
    if (matches.isEmpty()) {
        val color = if (isPastOrCurrent) Color.White else Color.White.copy(alpha = 0.30f)
        builder.withStyle(SpanStyle(color = color)) {
            append(wordText)
        }
    } else {
        var lastIdx = 0
        for (m in matches) {
            if (m.range.first > lastIdx) {
                val normalPart = wordText.substring(lastIdx, m.range.first)
                val normalColor = if (isPastOrCurrent) Color.White else Color.White.copy(alpha = 0.30f)
                builder.withStyle(SpanStyle(color = normalColor)) {
                    append(normalPart)
                }
            }
            val lovePart = wordText.substring(m.range.first, m.range.last + 1)
            if (isPastOrCurrent) {
                builder.withStyle(SpanStyle(color = MimikyuPink, fontWeight = FontWeight.Bold)) {
                    append(lovePart)
                }
            } else {
                builder.withStyle(SpanStyle(color = Color.White.copy(alpha = 0.30f))) {
                    append(lovePart)
                }
            }
            lastIdx = m.range.last + 1
        }
        if (lastIdx < wordText.length) {
            val remainingPart = wordText.substring(lastIdx)
            val normalColor = if (isPastOrCurrent) Color.White else Color.White.copy(alpha = 0.30f)
            builder.withStyle(SpanStyle(color = normalColor)) {
                append(remainingPart)
            }
        }
    }
}

fun buildAnnotatedLoveString(
    text: String,
    baseColor: Color = Color.White,
    loveColor: Color = MimikyuPink,
    isHighlighted: Boolean = true
): AnnotatedString {
    if (!isHighlighted || loveColor == baseColor) {
        return AnnotatedString(text, spanStyles = listOf(AnnotatedString.Range(SpanStyle(color = baseColor), 0, text.length)))
    }
    val matches = LOVE_WORDS_REGEX.findAll(text).toList()
    if (matches.isEmpty()) {
        return AnnotatedString(text, spanStyles = listOf(AnnotatedString.Range(SpanStyle(color = baseColor), 0, text.length)))
    }
    return buildAnnotatedString {
        var lastIdx = 0
        for (m in matches) {
            if (m.range.first > lastIdx) {
                val normalPart = text.substring(lastIdx, m.range.first)
                withStyle(SpanStyle(color = baseColor)) {
                    append(normalPart)
                }
            }
            val lovePart = text.substring(m.range.first, m.range.last + 1)
            withStyle(SpanStyle(color = loveColor, fontWeight = FontWeight.Bold)) {
                append(lovePart)
            }
            lastIdx = m.range.last + 1
        }
        if (lastIdx < text.length) {
            val remainingPart = text.substring(lastIdx)
            withStyle(SpanStyle(color = baseColor)) {
                append(remainingPart)
            }
        }
    }
}

/**
 * Spotify/Apple Music style mini lyric bar above the playback controls.
 * Features:
 * - Fluid syllable/word flowing karaoke animation matching the full lyrics page.
 * - Apple Music pulsing breathing dots (...) during song intro and instrumental interludes (>= 3.2s).
 * - SF Pro Bold typography, letterSpacing = (-0.5).sp, 14.sp (2px down from 16sp).
 * - Fixed 48.dp container height ensuring player controls NEVER jump or shift.
 */
@Composable
fun MiniLyricLine(
    lyrics: List<LyricLine>,
    currentLyricIndex: Int,
    isPlaying: Boolean,
    isLyricsLoading: Boolean = false,
    isModern: Boolean = false,
    currentPositionProvider: () -> Long = { 0L },
    onOpenFullScreen: () -> Unit = {}
) {
    val isPureMusic = lyrics.size == 1 && lyrics[0].text == "Instrumental"
    val currentPos = currentPositionProvider()

    val nonGapLines = remember(lyrics) {
        lyrics.filter { !it.isGap && it.text.isNotBlank() }
    }
    val firstSungLine = nonGapLines.firstOrNull()

    // 1. Song Intro interlude: When waiting for first sung line (gap >= 3.2s)
    val isIntroInterlude = !isPureMusic && firstSungLine != null &&
        firstSungLine.timeMs >= 3200L && currentPos < firstSungLine.timeMs

    // 2. Active line calculation
    val currentLine = lyrics.getOrNull(currentLyricIndex)

    // Calculate natural vocal duration and end of current vocals
    val vocalDuration = when {
        currentLine == null -> 0L
        currentLine.words.isNotEmpty() -> currentLine.words.maxOf { it.startOffsetMs + it.durationMs }.coerceAtLeast(1L)
        currentLine.durationMs > 0L -> currentLine.durationMs
        else -> LyricParser.estimateTextDurationMs(currentLine.text)
    }
    val vocalEnd = (currentLine?.timeMs ?: 0L) + vocalDuration

    // Next sung line after current
    val nextSungLine = remember(lyrics, currentLyricIndex) {
        lyrics.drop(currentLyricIndex + 1).firstOrNull { !it.isGap && it.text.isNotBlank() }
    }

    // 3. Between-lines instrumental interlude: Gap >= 3.2s after vocals end until next sung line
    val isBetweenLinesInterlude = !isPureMusic && !isIntroInterlude && currentLine != null && nextSungLine != null &&
        (nextSungLine.timeMs - vocalEnd >= 3200L) &&
        (currentPos >= (vocalEnd + 350L) && currentPos < nextSungLine.timeMs)

    // 4. Explicit gap line interlude (if currentLine is marked isGap)
    val isExplicitGapInterlude = !isPureMusic && !isIntroInterlude && currentLine != null && currentLine.isGap &&
        nextSungLine != null && (nextSungLine.timeMs - currentLine.timeMs >= 3200L) &&
        (currentPos in currentLine.timeMs until nextSungLine.timeMs)

    val activeInterlude: Pair<Int, Int>? = when {
        isIntroInterlude -> Pair(0, firstSungLine.timeMs.toInt())
        isBetweenLinesInterlude -> Pair((vocalEnd + 350L).toInt(), nextSungLine.timeMs.toInt())
        isExplicitGapInterlude -> Pair(currentLine.timeMs.toInt(), nextSungLine.timeMs.toInt())
        else -> null
    }

    // Determine the line text to display when not in interlude
    val displayLine = when {
        isPureMusic -> LyricLine(timeMs = 0L, durationMs = 0L, text = "Instrumental")
        lyrics.isEmpty() -> null
        currentLine != null && !currentLine.isGap && currentLine.text.isNotBlank() -> currentLine
        currentLine != null && currentLine.isGap -> nextSungLine ?: firstSungLine
        else -> firstSungLine
    }

    val fixedDisplayText = remember(displayLine) {
        displayLine?.text?.let { LyricParser.fixWordGaps(it) }.orEmpty()
    }

    val effectiveWords = remember(displayLine, fixedDisplayText) {
        if (displayLine == null || fixedDisplayText.isBlank() || isPureMusic) {
            emptyList()
        } else if (displayLine.words.isNotEmpty()) {
            LyricParser.alignWordsWithLineText(displayLine.words, fixedDisplayText)
        } else {
            val dur = if (displayLine.durationMs > 0L) displayLine.durationMs else LyricParser.estimateTextDurationMs(fixedDisplayText)
            LyricParser.synthesizeWordTimings(displayLine.copy(durationMs = dur, text = fixedDisplayText))
        }
    }

    val unlitColor = Color.White.copy(alpha = 0.30f)
    val litColor = Color.White

    val lineStart = displayLine?.timeMs ?: 0L
    val annotatedLyric = remember(displayLine, effectiveWords, currentPos) {
        if (displayLine == null || displayLine.text.isBlank()) {
            AnnotatedString("")
        } else if (isPureMusic) {
            buildAnnotatedLoveString("Instrumental", baseColor = litColor, loveColor = MimikyuPink)
        } else if (effectiveWords.isEmpty()) {
            buildAnnotatedLoveString(fixedDisplayText, baseColor = litColor, loveColor = MimikyuPink)
        } else {
            buildAnnotatedString {
                effectiveWords.forEachIndexed { _, word ->
                    val wStart = lineStart + word.startOffsetMs
                    val wEnd = wStart + word.durationMs.coerceAtLeast(1L)
                    val wordText = word.text
                    val isLove = LOVE_WORDS_REGEX.containsMatchIn(wordText)
                    val activeLitColor = if (isLove) MimikyuPink else litColor

                    when {
                        currentPos >= wEnd -> {
                            // Word has been sung completely
                            withStyle(SpanStyle(color = activeLitColor, fontWeight = FontWeight.Bold)) {
                                append(wordText)
                            }
                        }
                        currentPos < wStart -> {
                            // Word has not been reached yet
                            withStyle(SpanStyle(color = unlitColor, fontWeight = FontWeight.Bold)) {
                                append(wordText)
                            }
                        }
                        else -> {
                            // Flowing progressive karaoke animation across active word!
                            val frac = ((currentPos - wStart).toFloat() / (wEnd - wStart).toFloat()).coerceIn(0f, 1f)
                            val litLen = (frac * wordText.length).toInt().coerceIn(0, wordText.length)
                            val litPart = wordText.substring(0, litLen)
                            val unlitPart = wordText.substring(litLen)

                            if (litPart.isNotEmpty()) {
                                withStyle(SpanStyle(color = activeLitColor, fontWeight = FontWeight.Bold)) {
                                    append(litPart)
                                }
                            }
                            if (unlitPart.isNotEmpty()) {
                                val transitioningChar = unlitPart.substring(0, 1)
                                val remainingUnlit = unlitPart.substring(1)
                                val subFrac = (frac * wordText.length) - litLen
                                val transColor = activeLitColor.copy(alpha = 0.30f + 0.65f * subFrac)
                                withStyle(SpanStyle(color = transColor, fontWeight = FontWeight.Bold)) {
                                    append(transitioningChar)
                                }
                                if (remainingUnlit.isNotEmpty()) {
                                    withStyle(SpanStyle(color = unlitColor, fontWeight = FontWeight.Bold)) {
                                        append(remainingUnlit)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Fixed height container (48.dp) ensures controls and progress bar NEVER move or jump
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .offset(y = (-2).dp)
            .padding(horizontal = MimikyuSpacing.lg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onOpenFullScreen
            ),
        contentAlignment = Alignment.CenterStart
    ) {
        val targetStateKey = when {
            activeInterlude != null -> "INTERLUDE_${activeInterlude.first}_${activeInterlude.second}"
            isLyricsLoading && lyrics.isEmpty() -> "LOADING"
            displayLine != null && displayLine.text.isNotBlank() -> "LINE_${displayLine.timeMs}_${displayLine.text.hashCode()}"
            else -> "EMPTY"
        }

        AnimatedContent(
            targetState = targetStateKey,
            transitionSpec = {
                fadeIn(tween(250, easing = FastOutSlowInEasing)) togetherWith
                    fadeOut(tween(200, easing = FastOutSlowInEasing))
            },
            contentAlignment = Alignment.CenterStart,
            modifier = Modifier.fillMaxSize(),
            label = "mini_lyric_transition"
        ) { key ->
            when {
                key.startsWith("INTERLUDE_") && activeInterlude != null -> {
                    KaraokeBreathingDots(
                        alignment = KaraokeAlignment.Start,
                        startTimeMs = activeInterlude.first,
                        endTimeMs = activeInterlude.second,
                        currentTimeProvider = { currentPos.toInt() },
                        horizontalPadding = 0.dp,
                        lineHeight = 24.dp,
                        defaults = KaraokeBreathingDotsDefaults(
                            number = 3,
                            size = 8.dp,
                            margin = 6.dp,
                            breathingDotsColor = Color.White
                        )
                    )
                }
                key == "LOADING" -> {
                    LoadingDotsAnimation(
                        color = Color.White.copy(alpha = 0.45f),
                        isPlaying = isPlaying,
                        dotSize = 7.dp,
                        spacing = 5.dp
                    )
                }
                key.startsWith("LINE_") && annotatedLyric.text.isNotBlank() -> {
                    Text(
                        text = annotatedLyric,
                        modifier = Modifier.fillMaxWidth(),
                        fontSize = 14.sp,
                        fontFamily = SFProFontFamily,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.5).sp,
                        lineHeight = 20.sp,
                        textAlign = TextAlign.Start,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = LocalTextStyle.current.copy(
                            shadow = Shadow(
                                color = Color.Black.copy(alpha = 0.85f),
                                offset = Offset(0f, 1.5f),
                                blurRadius = 6f
                            )
                        )
                    )
                }
                else -> {
                    Spacer(modifier = Modifier.size(1.dp))
                }
            }
        }
    }
}

@Composable
fun LoadingDotsAnimation(
    color: Color,
    isPlaying: Boolean,
    dotSize: androidx.compose.ui.unit.Dp = 8.dp,
    spacing: androidx.compose.ui.unit.Dp = 6.dp
) {
    val dot1Scale = remember { Animatable(0.3f) }
    val dot2Scale = remember { Animatable(0.3f) }
    val dot3Scale = remember { Animatable(0.3f) }

    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            launch {
                dot1Scale.animateTo(
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(600, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    )
                )
            }
            launch {
                delay(150)
                dot2Scale.animateTo(
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(600, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    )
                )
            }
            launch {
                delay(300)
                dot3Scale.animateTo(
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(600, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    )
                )
            }
        } else {
            launch { dot1Scale.animateTo(0.3f, tween(200)) }
            launch { dot2Scale.animateTo(0.3f, tween(200)) }
            launch { dot3Scale.animateTo(0.3f, tween(200)) }
        }
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(20.dp)
    ) {
        Box(
            modifier = Modifier
                .size(dotSize)
                .graphicsLayer {
                    scaleX = dot1Scale.value
                    scaleY = dot1Scale.value
                    alpha = dot1Scale.value
                }
                .background(color, CircleShape)
        )
        Box(
            modifier = Modifier
                .size(dotSize)
                .graphicsLayer {
                    scaleX = dot2Scale.value
                    scaleY = dot2Scale.value
                    alpha = dot2Scale.value
                }
                .background(color, CircleShape)
        )
        Box(
            modifier = Modifier
                .size(dotSize)
                .graphicsLayer {
                    scaleX = dot2Scale.value
                    scaleY = dot2Scale.value
                    alpha = dot2Scale.value
                }
                .background(color, CircleShape)
        )
    }
}
