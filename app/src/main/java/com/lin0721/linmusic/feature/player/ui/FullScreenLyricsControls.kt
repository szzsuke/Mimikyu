package com.lin0721.linmusic.feature.player.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.res.painterResource
import com.lin0721.linmusic.core.ui.theme.MimikyuIcons
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lin0721.linmusic.core.ui.components.MimikyuIconButton
import com.lin0721.linmusic.core.player.PlayMode
import com.lin0721.linmusic.core.ui.interaction.pressable
import com.lin0721.linmusic.core.ui.theme.BottomSheetShape
import com.lin0721.linmusic.core.ui.theme.DragHandleShape
import com.lin0721.linmusic.core.ui.theme.MimikyuPress
import com.lin0721.linmusic.core.ui.theme.MimikyuSpacing
import kotlin.math.roundToInt

// ────────────────────────────────────────────────────────────────────────────
// （/、Share、）
// ────────────────────────────────────────────────────────────────────────────
@Composable
fun FullScreenLyricsToolbar(
    secondaryMode: String,
    hasTranslation: Boolean,
    chorusStartMs: Long? = null,
    hasRoma: Boolean,
    onToggleSecondaryMode: () -> Unit,
    onShareClick: () -> Unit,
    onSettingsClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // ：/（A / A）
        val hasAnySecondary = hasTranslation || hasRoma
        val (badgeText, badgeSub) = when (secondaryMode) {
            "roma" -> "RO" to "A"
            else -> "TR" to "A"
        }
        val badgeTint = when {
            !hasAnySecondary -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
            secondaryMode == "none" -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
            else -> Color.White
        }
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .pressable(if (hasAnySecondary) MimikyuPress.Icon else MimikyuPress.None) {
                    if (hasAnySecondary) onToggleSecondaryMode()
                },
            contentAlignment = Alignment.CenterStart
        ) {
            Row(
                verticalAlignment = Alignment.Bottom
            ) {
                Text(
                    text = badgeText,
                    color = badgeTint,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = badgeSub,
                    color = badgeTint,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.padding(start = 1.dp, bottom = 1.dp)
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Share button
            MimikyuIconButton(onClick = onShareClick) {
                Icon(
                    painter = painterResource(MimikyuIcons.Share),
                    contentDescription = "Share lyrics",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(24.dp)
                )
            }

            // Settings button
            if (onSettingsClick != null) {
                MimikyuIconButton(onClick = onSettingsClick) {
                    Icon(
                        painter = painterResource(MimikyuIcons.Tune),
                        contentDescription = "Lyrics settings",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

// ────────────────────────────────────────────────────────────────────────────
// iOS （、、 steps ）
// ────────────────────────────────────────────────────────────────────────────
@Composable
fun LyricCapsuleSlider(
    value: Int,
    onValueChange: (Int) -> Unit,
    valueRange: ClosedFloatingPointRange<Float> = 16f..32f,
    modifier: Modifier = Modifier,
    startLabel: String = "A",
    endLabel: String = "A"
) {
    var componentWidthPx by remember { mutableFloatStateOf(1f) }
    val progressFraction = remember(value, valueRange) {
        ((value.toFloat() - valueRange.start) / (valueRange.endInclusive - valueRange.start)).coerceIn(0f, 1f)
    }

    val updateValueFromPosition = rememberUpdatedState { xPx: Float ->
        if (componentWidthPx > 0) {
            val fraction = (xPx / componentWidthPx).coerceIn(0f, 1f)
            val rawValue = valueRange.start + fraction * (valueRange.endInclusive - valueRange.start)
            onValueChange(rawValue.roundToInt())
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .onSizeChanged { componentWidthPx = it.width.toFloat() }
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.12f))
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = { offset ->
                        updateValueFromPosition.value(offset.x)
                    }
                )
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        updateValueFromPosition.value(offset.x)
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        updateValueFromPosition.value(change.position.x)
                    }
                )
            }
    ) {

        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(progressFraction)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
        )


        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = startLabel,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White.copy(alpha = 0.85f)
            )
            Text(
                text = endLabel,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White.copy(alpha = 0.85f)
            )
        }
    }
}

@Composable
private fun LyricSliderSetting(
    label: String,
    valueText: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    startLabel: String = "A",
    endLabel: String = "A"
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp)
            Text(
                text = valueText,
                color = MaterialTheme.colorScheme.primary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        LyricCapsuleSlider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            startLabel = startLabel,
            endLabel = endLabel
        )
    }
}

// ────────────────────────────────────────────────────────────────────────────
// （、、、）
// ────────────────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullScreenLyricsSettingsSheet(
    fontSize: Int,
    onFontSizeChange: (Int) -> Unit,
    lineSpacing: Int,
    onLineSpacingChange: (Int) -> Unit,
    secondarySpacing: Int,
    onSecondarySpacingChange: (Int) -> Unit,
    alignment: String,
    onAlignmentChange: (String) -> Unit,
    secondaryMode: String,
    onSecondaryModeChange: (String) -> Unit,
    hasTranslation: Boolean,
    hasRoma: Boolean,
    advancedKaraokeEffect: Boolean = true,
    onAdvancedKaraokeEffectChange: (Boolean) -> Unit = {},
    lyricOffsetMs: Long = 0L,
    onLyricOffsetChange: (Long) -> Unit = {},
    onLyricOffsetReset: () -> Unit = {},
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
        shape = BottomSheetShape,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = MimikyuSpacing.xs)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(DragHandleShape)
                    .background(Color.White.copy(alpha = 0.3f))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = MimikyuSpacing.lg)
                .padding(bottom = MimikyuSpacing.lg)
        ) {
            Text(
                text = "Lyrics Settings",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.padding(bottom = MimikyuSpacing.md)
            )

            LyricSliderSetting(
                label = "Font Size",
                valueText = "${fontSize} sp",
                value = fontSize,
                onValueChange = onFontSizeChange,
                valueRange = 16f..44f
            )

            LyricSliderSetting(
                label = "Line Spacing",
                valueText = "${lineSpacing} dp",
                value = lineSpacing,
                onValueChange = onLineSpacingChange,
                valueRange = 8f..48f,
                startLabel = "Tight",
                endLabel = "Loose"
            )

            LyricSliderSetting(
                label = "Secondary Text Spacing",
                valueText = "${secondarySpacing} dp",
                value = secondarySpacing,
                onValueChange = onSecondarySpacingChange,
                valueRange = 0f..24f,
                startLabel = "Tight",
                endLabel = "Loose"
            )

            Spacer(modifier = Modifier.height(MimikyuSpacing.sm))

            // （ /  / ）
            Text(
                text = "Lyrics Alignment",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
            )
            val alignments = listOf(
                "left" to "Left",
                "center" to "Center",
                "right" to "Right"
            )
            alignments.forEach { (key, label) ->
                val isSelected = alignment == key
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onAlignmentChange(key) }
                        .padding(vertical = 12.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = label,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        fontSize = 15.sp
                    )
                    if (isSelected) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            Spacer(modifier = Modifier.height(MimikyuSpacing.sm))

            // （ /  /  ）
            Text(
                text = "Secondary Text",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
            )
            val secondaryOptions = listOf(
                "translation" to ("Translation" to hasTranslation),
                "roma" to ("Romaji" to hasRoma),
                "none" to ("Off (Original only)" to true)
            )
            secondaryOptions.forEach { (key, pair) ->
                val (label, isAvailable) = pair
                val isSelected = secondaryMode == key
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = isAvailable) { onSecondaryModeChange(key) }
                        .padding(vertical = 12.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (isAvailable) label else "$label (None)",
                        color = when {
                            isSelected -> MaterialTheme.colorScheme.primary
                            isAvailable -> MaterialTheme.colorScheme.onSurface
                            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        },
                        fontSize = 15.sp
                    )
                    if (isSelected) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            Spacer(modifier = Modifier.height(MimikyuSpacing.sm))


            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onAdvancedKaraokeEffectChange(!advancedKaraokeEffect) }
                    .padding(vertical = 8.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Word-by-word Flow Effect",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 15.sp
                    )
                    Text(
                        text = "Enable soft edge gradient animation",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }
                Switch(
                    checked = advancedKaraokeEffect,
                    onCheckedChange = onAdvancedKaraokeEffectChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.primary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                    )
                )
            }

            Spacer(modifier = Modifier.height(MimikyuSpacing.sm))

            // (±ms)
            Text(
                text = "Manual Timing Offset (±ms)",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val offsetText = when {
                    lyricOffsetMs > 0 -> "+${lyricOffsetMs} ms"
                    lyricOffsetMs < 0 -> "${lyricOffsetMs} ms"
                    else -> "Synced (0 ms)"
                }
                Text(
                    text = offsetText,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = { onLyricOffsetChange(-500L) },
                        contentPadding = PaddingValues(horizontal = 6.dp)
                    ) { Text("-0.5s", fontSize = 12.sp) }

                    TextButton(
                        onClick = { onLyricOffsetChange(-100L) },
                        contentPadding = PaddingValues(horizontal = 6.dp)
                    ) { Text("-0.1s", fontSize = 12.sp) }

                    if (lyricOffsetMs != 0L) {
                        TextButton(
                            onClick = onLyricOffsetReset,
                            contentPadding = PaddingValues(horizontal = 6.dp)
                        ) { Text("Reset", fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
                    }

                    TextButton(
                        onClick = { onLyricOffsetChange(100L) },
                        contentPadding = PaddingValues(horizontal = 6.dp)
                    ) { Text("+0.1s", fontSize = 12.sp) }

                    TextButton(
                        onClick = { onLyricOffsetChange(500L) },
                        contentPadding = PaddingValues(horizontal = 6.dp)
                    ) { Text("+0.5s", fontSize = 12.sp) }
                }
            }
        }
    }
}
// ────────────────────────────────────────────────────────────────────────────
// of Play（ +  + Play）
// ────────────────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullScreenControls(
    isPlaying: Boolean,
    currentPositionProvider: () -> Long,
    duration: Long,
    onSeek: (Long) -> Unit,
    onTogglePlay: () -> Unit,
    onPlayNext: () -> Unit,
    onPlayPrevious: () -> Unit,
    playMode: PlayMode,
    onToggleShuffle: () -> Unit,
    onToggleRepeat: () -> Unit,
    secondaryMode: String,
    hasTranslation: Boolean,
    hasRoma: Boolean,
    onToggleSecondaryMode: () -> Unit,
    onShareLyrics: () -> Unit,
    onLyricsSettingsClick: (() -> Unit)? = null,
    chorusStartMs: Long? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = MimikyuSpacing.lg)
            .padding(bottom = 20.dp)
    ) {
        // 1. Toolbar (Secondary mode toggle, Share, Lyrics settings)
        FullScreenLyricsToolbar(
            secondaryMode = secondaryMode,
            hasTranslation = hasTranslation,
            hasRoma = hasRoma,
            onToggleSecondaryMode = onToggleSecondaryMode,
            onShareClick = onShareLyrics,
            onSettingsClick = onLyricsSettingsClick
        )

        Spacer(modifier = Modifier.height(MimikyuSpacing.xs))

        // 2. Progress Bar matching ExoPlayer
        ProgressSection(
            currentPositionProvider = currentPositionProvider,
            duration = duration,
            onSeek = onSeek,
            modifier = Modifier,
            chorusStartMs = chorusStartMs,
            isModern = false
        )

        Spacer(modifier = Modifier.height(MimikyuSpacing.sm))

        // 4. Play（）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = MimikyuSpacing.xs),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .pressable(MimikyuPress.Icon) { onToggleShuffle() },
                contentAlignment = Alignment.CenterStart
            ) {
                Icon(
                    painter = painterResource(MimikyuIcons.Shuffle),
                    contentDescription = null,
                    tint = if (playMode == PlayMode.SHUFFLE) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(26.dp)
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(28.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MimikyuIconButton(onClick = onPlayPrevious, style = MimikyuPress.Transport) {
                    Icon(
                        painter = painterResource(MimikyuIcons.SkipPrevious),
                        contentDescription = "Previous",
                        tint = Color.White,
                        modifier = Modifier.size(36.dp)
                    )
                }

                val playInteraction = remember { MutableInteractionSource() }
                val isPressed by playInteraction.collectIsPressedAsState()
                val buttonAlpha = remember { Animatable(1f) }
                var clickTrigger by remember { mutableIntStateOf(0) }

                LaunchedEffect(isPressed) {
                    if (isPressed) {
                        buttonAlpha.animateTo(0.70f, tween(durationMillis = 80))
                    } else if (clickTrigger == 0) {
                        buttonAlpha.animateTo(1f, tween(durationMillis = 200, easing = FastOutSlowInEasing))
                    }
                }

                LaunchedEffect(clickTrigger) {
                    if (clickTrigger > 0) {
                        buttonAlpha.snapTo(0.70f)
                    }
                    buttonAlpha.animateTo(1f, tween(durationMillis = 220, easing = FastOutSlowInEasing))
                }

                FloatingActionButton(
                    onClick = {
                        clickTrigger++
                        onTogglePlay()
                    },
                    containerColor = Color.White,
                    shape = CircleShape,
                    interactionSource = playInteraction,
                    modifier = Modifier
                        .size(72.dp)
                        .graphicsLayer {
                            alpha = buttonAlpha.value
                        }
                ) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = Color.Black,
                        modifier = Modifier.size(42.dp)
                    )
                }

                MimikyuIconButton(onClick = onPlayNext, style = MimikyuPress.Transport) {
                    Icon(
                        painter = painterResource(MimikyuIcons.SkipNext),
                        contentDescription = "Next",
                        tint = Color.White,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }

            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .pressable(MimikyuPress.Icon) { onToggleRepeat() },
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(
                    painter = painterResource(if (playMode == PlayMode.SINGLE_LOOP) MimikyuIcons.RepeatOnce else MimikyuIcons.Repeat),
                    contentDescription = null,
                    tint = if (playMode == PlayMode.SINGLE_LOOP || playMode == PlayMode.LIST_LOOP) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(26.dp)
                )
            }
        }
    }
}

