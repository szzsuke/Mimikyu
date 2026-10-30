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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lin0721.linmusic.core.player.domain.LyricLine
import com.lin0721.linmusic.core.player.domain.LyricParser
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.lin0721.linmusic.core.ui.theme.MimikyuSpacing

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.LocalTextStyle

import com.lin0721.linmusic.core.ui.theme.MimikyuPink
import com.lin0721.linmusic.core.ui.theme.SFProFontFamily

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
        val color = if (isPastOrCurrent) Color.White else Color.White.copy(alpha = 0.40f)
        builder.withStyle(SpanStyle(color = color)) {
            append(wordText)
        }
    } else {
        var lastIdx = 0
        for (m in matches) {
            if (m.range.first > lastIdx) {
                val normalPart = wordText.substring(lastIdx, m.range.first)
                val normalColor = if (isPastOrCurrent) Color.White else Color.White.copy(alpha = 0.40f)
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
                builder.withStyle(SpanStyle(color = Color.White.copy(alpha = 0.40f))) {
                    append(lovePart)
                }
            }
            lastIdx = m.range.last + 1
        }
        if (lastIdx < wordText.length) {
            val remainingPart = wordText.substring(lastIdx)
            val normalColor = if (isPastOrCurrent) Color.White else Color.White.copy(alpha = 0.40f)
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

// Spotify-style active lyric line above song title with fixed height to prevent element shifting
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

    val currentLine = lyrics.getOrNull(currentLyricIndex)

    val currentPos = currentPositionProvider()
    val isSinging = if (currentLine != null && !currentLine.isGap && currentLine.text.isNotBlank() && !isPureMusic) {
        val lineStart = currentLine.timeMs
        val lastWordEnd = currentLine.words.lastOrNull()?.let { lineStart + it.startOffsetMs + it.durationMs }
        val lineEnd = when {
            lastWordEnd != null && lastWordEnd > lineStart -> lastWordEnd + 350L
            currentLine.durationMs > 0L -> lineStart + currentLine.durationMs
            else -> {
                val nextTime = lyrics.getOrNull(currentLyricIndex + 1)?.timeMs
                if (nextTime != null && nextTime > lineStart) minOf(nextTime, lineStart + 5000L) else lineStart + 4500L
            }
        }
        currentPos in lineStart..lineEnd
    } else false

    val currentText = when {
        isPureMusic -> "Instrumental"
        lyrics.isEmpty() -> ""
        isSinging -> LyricParser.fixWordGaps(currentLine!!.text)
        currentLine != null && !currentLine.isGap && currentLine.text.isNotBlank() -> LyricParser.fixWordGaps(currentLine.text)
        currentLine != null && currentLine.isGap -> {
            val nextLine = lyrics.drop(currentLyricIndex + 1).firstOrNull { !it.isGap && it.text.isNotBlank() }?.text
            nextLine?.let { LyricParser.fixWordGaps(it) } ?: ""
        }
        else -> lyrics.firstOrNull { !it.isGap && it.text.isNotBlank() }?.text?.let { LyricParser.fixWordGaps(it) }.orEmpty()
    }

    val annotatedLyric = remember(currentLine, currentPos, currentText) {
        if (currentText.isBlank()) {
            AnnotatedString("")
        } else if (currentLine != null && currentLine.words.isNotEmpty()) {
            val stitchedWords = LyricParser.stitchSyllableGaps(currentLine.words)
            buildAnnotatedString {
                stitchedWords.forEach { word ->
                    val isPastOrCurrent = currentPos >= (currentLine.timeMs + word.startOffsetMs)
                    appendStyledLoveWord(
                        builder = this,
                        wordText = word.text,
                        isPastOrCurrent = isPastOrCurrent
                    )
                }
            }
        } else if (currentText.isNotBlank()) {
            val words = currentText.split(Regex("\\s+")).filter { it.isNotBlank() }
            if (words.isNotEmpty()) {
                val lineStart = currentLine?.timeMs ?: 0L
                val lineDur = (currentLine?.durationMs?.takeIf { it > 0L } ?: 3000L).coerceAtLeast(1500L)
                val step = lineDur / words.size
                buildAnnotatedString {
                    words.forEachIndexed { index, word ->
                        val wordTime = lineStart + (index * step)
                        val isPastOrCurrent = currentPos >= wordTime
                        appendStyledLoveWord(
                            builder = this,
                            wordText = word,
                            isPastOrCurrent = isPastOrCurrent
                        )
                        if (index < words.lastIndex) {
                            append(" ")
                        }
                    }
                }
            } else {
                buildAnnotatedLoveString(currentText, baseColor = Color.White, loveColor = MimikyuPink)
            }
        } else {
            AnnotatedString("")
        }
    }

    // Centered 2-Line Subtitle-Style Lyrics with cinematic drop shadow and transparent background
    // Fixed height container (62.dp) ensures controls and progress bar NEVER move or jump
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .offset(y = (-4).dp)
            .padding(horizontal = MimikyuSpacing.lg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onOpenFullScreen
            ),
        contentAlignment = Alignment.TopStart
    ) {
        if (currentText.isNotBlank()) {
            AnimatedContent(
                targetState = currentText,
                transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(200)) },
                contentAlignment = Alignment.TopStart,
                modifier = Modifier.fillMaxSize(),
                label = "active_lyric"
            ) { _ ->
                Text(
                    text = annotatedLyric,
                    modifier = Modifier.fillMaxWidth(),
                    fontSize = 15.sp,
                    fontFamily = SFProFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 21.sp,
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
        } else if (isLyricsLoading && lyrics.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.CenterStart
            ) {
                LoadingDotsAnimation(
                    color = Color.White.copy(alpha = 0.4f),
                    isPlaying = isPlaying
                )
            }
        }
    }
}

// of ，Pause
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
                    scaleX = dot3Scale.value
                    scaleY = dot3Scale.value
                    alpha = dot3Scale.value
                }
                .background(color, CircleShape)
        )
    }
}
