package com.lin0721.linmusic.feature.player.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lin0721.linmusic.core.ui.theme.MimikyuSpacing
import com.lin0721.linmusic.core.ui.theme.InfoCardRadius
import com.lin0721.linmusic.core.ui.theme.darken
import com.lin0721.linmusic.core.ui.theme.lighten
import com.lin0721.linmusic.core.ui.theme.saturateIfChromatic
import com.lin0721.linmusic.core.player.domain.LyricLine
import com.lin0721.linmusic.core.player.domain.lyricLineKey
import com.lin0721.linmusic.core.ui.theme.SFProFontFamily

private val LYRICS_CARD_BLUR_RADIUS = 32.dp

private val ShowLyricsButtonTopGap = MimikyuSpacing.md

// Lyrics Preview，
private val LyricItemSpacing = 14.dp
private val LyricItemHeightNoTransEst = 36.dp
private val LyricItemHeightWithTransEst = 56.dp
private val LyricItemStride = LyricItemHeightNoTransEst + LyricItemSpacing
private const val VisibleLyricLines = 4
// 「4 」；Current，Moments
private val LyricsBaseViewportHeight = LyricItemHeightNoTransEst * VisibleLyricLines + LyricItemSpacing * (VisibleLyricLines - 1)
private val LyricTranslationExtraHeight = 24.dp

// ────────────────────────────────────────────────────────────────────────────
// Play of Lyrics Preview（ +  + Expand）
// ────────────────────────────────────────────────────────────────────────────
@Composable
fun LyricsCard(
    lyrics: List<LyricLine>,
    currentIndex: Int,
    isLoading: Boolean,
    base: Color,
    onOpenFullScreen: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "fluid_mesh")


    val darkCenterX by infiniteTransition.animateFloat(
        initialValue = 1.20f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(15000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dark_x"
    )
    val darkCenterY by infiniteTransition.animateFloat(
        initialValue = 1.20f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(13000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dark_y"
    )
    val darkRadiusScale by infiniteTransition.animateFloat(
        initialValue = 0.40f,
        targetValue = 0.50f,
        animationSpec = infiniteRepeatable(
            animation = tween(10000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dark_radius"
    )

    // Match the background cover art color family directly without artificial neon saturation
    val cardFill = remember(base) { base.darken(0.48f).copy(alpha = 0.88f) }
    val cardDarkBlob = remember(base) { base.darken(0.62f).copy(alpha = 0.95f) }
    val cardLightBlob = remember(base) { base.lighten(0.06f).copy(alpha = 0.40f) }
    val inactiveLyricColor = remember(base) { Color.White.copy(alpha = 0.45f) }

    // Current， LyricsPreview Current Text  of  onTextLayout ，Moments
    var currentLineWrapLines by remember { mutableIntStateOf(1) }
    val hasCurrentTranslation = lyrics.getOrNull(currentIndex)?.translation != null
    val extraWrapLines = (currentLineWrapLines - 1).coerceAtLeast(0)
    val targetViewportHeight = LyricsBaseViewportHeight +
        LyricItemHeightNoTransEst * extraWrapLines +
        (if (hasCurrentTranslation) LyricTranslationExtraHeight else 0.dp)
    val animatedViewportHeight by animateDpAsState(
        targetValue   = targetViewportHeight,
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        label         = "lyrics_viewport_height"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MimikyuSpacing.md, vertical = MimikyuSpacing.sm)
            .clip(RoundedCornerShape(InfoCardRadius))
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .blur(LYRICS_CARD_BLUR_RADIUS)
                .drawBehind {
                    val baseSize = size.minDimension
                    drawSingleHueMesh(
                        fill = cardFill,
                        lightBlob = cardLightBlob,
                        lightCenter = Offset(size.width * (1f - darkCenterX), size.height * (1f - darkCenterY)),
                        lightRadius = baseSize * 0.45f,
                        darkBlob = cardDarkBlob,
                        darkCenter = Offset(size.width * darkCenterX, size.height * darkCenterY),
                        darkRadius = baseSize * darkRadiusScale
                    )
                }
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Text(
                "Lyrics Preview",
                color = Color.White,
                fontSize = 17.sp,
                fontFamily = SFProFontFamily,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(MimikyuSpacing.md))

            Box(modifier = Modifier.height(animatedViewportHeight)) {
                if (isLoading) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    }
                } else if (lyrics.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No lyrics available",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            fontSize = 15.sp
                        )
                    }
                } else {
                    LyricsPreview(
                        lyrics = lyrics,
                        currentIndex = currentIndex,
                        inactiveColor = inactiveLyricColor,
                        onCurrentLineLayout = { currentLineWrapLines = it }
                    )
                }
            }

            Spacer(modifier = Modifier.height(ShowLyricsButtonTopGap))

            PlayerCapsuleButton(text = "Show Lyrics", onClick = onOpenFullScreen)
        }
    }
}

@Composable
fun LyricsPreview(
    lyrics: List<LyricLine>,
    currentIndex: Int,
    inactiveColor: Color,
    onCurrentLineLayout: (Int) -> Unit
) {
    val displayLyrics = remember(lyrics) {
        val nonGaps = lyrics.filter { !it.isGap && it.text.isNotBlank() }
        if (nonGaps.isNotEmpty()) nonGaps else lyrics.filter { it.text.isNotBlank() }
    }

    val currentLine = lyrics.getOrNull(currentIndex)
    val displayCurrentIndex = remember(currentIndex, displayLyrics) {
        if (displayLyrics.isEmpty()) 0
        else {
            val idx = displayLyrics.indexOf(currentLine)
            if (idx >= 0) idx
            else {
                val currentTime = currentLine?.timeMs ?: 0L
                val nextIdx = displayLyrics.indexOfFirst { it.timeMs >= currentTime }
                if (nextIdx >= 0) nextIdx else (displayLyrics.size - 1).coerceAtLeast(0)
            }
        }
    }

    val itemSpacingDp = LyricItemSpacing
    val itemHeightNoDpEst    = LyricItemHeightNoTransEst
    val itemHeightWithTransDpEst = LyricItemHeightWithTransEst
    val itemStrideDp      = LyricItemStride

    val density       = LocalDensity.current
    val lazyListState = rememberLazyListState()
    var cardHeightPx by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(displayCurrentIndex) {
        if (displayCurrentIndex < 0 || displayCurrentIndex >= displayLyrics.size) return@LaunchedEffect

        val cardHeightDp = with(density) { cardHeightPx.toDp() }
        val linesAboveCentre = (cardHeightDp / 2 / itemStrideDp).toInt()

        if (displayCurrentIndex < linesAboveCentre) {
            lazyListState.animateScrollToItem(index = 0, scrollOffset = 0)
            return@LaunchedEffect
        }
        val hasTranslation   = displayLyrics[displayCurrentIndex].translation != null
        val itemHeightPx     = with(density) {
            if (hasTranslation) itemHeightWithTransDpEst.toPx() else itemHeightNoDpEst.toPx()
        }
        val centreOffsetPx = -(((cardHeightPx - itemHeightPx) / 2f).toInt())
        lazyListState.animateScrollToItem(
            index       = displayCurrentIndex,
            scrollOffset = centreOffsetPx
        )
    }

    LazyColumn(
        state   = lazyListState,
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .onSizeChanged { cardHeightPx = it.height.toFloat() },
        verticalArrangement = Arrangement.spacedBy(itemSpacingDp),
        userScrollEnabled   = false,
        contentPadding = PaddingValues(top = 0.dp, bottom = with(density) { (cardHeightPx / 2).toDp() })
    ) {
        itemsIndexed(items = displayLyrics, key = ::lyricLineKey) { index, line ->
            // Instrumental of
            if (line.text.isBlank()) return@itemsIndexed

            val isCurrent = index == displayCurrentIndex
            val distance  = kotlin.math.abs(index - displayCurrentIndex).coerceAtMost(4)

            val targetScale = if (isCurrent) 1.15f
                              else (1f - distance * 0.07f).coerceAtLeast(0.85f)
            val animatedScale by animateFloatAsState(
                targetValue   = targetScale,
                animationSpec = spring(dampingRatio = 0.7f, stiffness = 300f),
                label         = "lyric_scale_$index"
            )

            val targetAlpha = if (isCurrent) 1f
                              else (0.85f - distance * 0.12f).coerceAtLeast(0.4f)
            val animatedAlpha by animateFloatAsState(
                targetValue   = targetAlpha,
                animationSpec = tween(300, easing = FastOutSlowInEasing),
                label         = "lyric_alpha_$index"
            )

            val targetTransAlpha = if (isCurrent) 0.85f else 0.7f
            val animatedTransAlpha by animateFloatAsState(
                targetValue   = targetTransAlpha,
                animationSpec = tween(300, easing = FastOutSlowInEasing),
                label         = "lyric_trans_alpha_$index"
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .graphicsLayer {
                        scaleX = animatedScale
                        scaleY = animatedScale
                        alpha  = animatedAlpha
                        transformOrigin = TransformOrigin(0f, 0.5f)
                    }
            ) {
                val mainFontSize = if (isCurrent) 20.sp else 18.sp
                val transFontSize = if (isCurrent) 16.sp else 15.sp
                val displayText = remember(line) {
                    if (line.words.isNotEmpty() && !line.text.contains(" ") && line.words.size > 1 && line.words.any { it.text.endsWith(" ") }) {
                        line.words.joinToString("") { it.text }.trim()
                    } else {
                        line.text
                    }
                }
                val annotatedDisplayText = remember(displayText, isCurrent, inactiveColor) {
                    buildAnnotatedLoveString(
                        text = displayText,
                        baseColor = if (isCurrent) Color.White else inactiveColor,
                        loveColor = com.lin0721.linmusic.core.ui.theme.MimikyuPink,
                        isHighlighted = isCurrent
                    )
                }
                Text(
                    text       = annotatedDisplayText,
                    fontSize   = mainFontSize,
                    fontFamily = SFProFontFamily,
                    lineHeight = (mainFontSize.value * 1.35f).sp,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign  = TextAlign.Start,
                    onTextLayout = { if (isCurrent) onCurrentLineLayout(it.lineCount) },
                    modifier   = Modifier.fillMaxWidth()
                )
                if (line.translation != null) {
                    Spacer(modifier = Modifier.height(MimikyuSpacing.xs))
                    Text(
                        text      = line.translation,
                        fontSize  = transFontSize,
                        fontFamily = SFProFontFamily,
                        lineHeight = (transFontSize.value * 1.35f).sp,
                        color     = (if (isCurrent) Color.White else inactiveColor).copy(alpha = animatedTransAlpha),
                        textAlign = TextAlign.Start,
                        modifier  = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}
