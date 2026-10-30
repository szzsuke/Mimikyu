package com.lin0721.linmusic.feature.player.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.rounded.AllInclusive
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.lin0721.linmusic.core.ui.components.MimikyuIconButton
import com.lin0721.linmusic.core.player.PlayMode
import com.lin0721.linmusic.core.ui.interaction.pressScale
import com.lin0721.linmusic.core.ui.theme.MimikyuPress
import com.lin0721.linmusic.core.ui.theme.InfoCardRadius
import com.lin0721.linmusic.core.ui.theme.TextGray
import com.lin0721.linmusic.core.ui.theme.MimikyuSpacing
import com.lin0721.linmusic.core.ui.theme.MimikyuIcons

// ：/、、PlayPause、、
@Composable
fun PlaybackControls(
    isPlaying: Boolean,
    isLoading: Boolean = false,
    onTogglePlay: () -> Unit,
    onPlayNext: () -> Unit,
    onPlayPrevious: () -> Unit,
    onToggleShuffle: () -> Unit,
    onToggleRepeat: () -> Unit,
    playMode: PlayMode,
    isRoaming: Boolean = false,
    onDisableRoaming: () -> Unit = {},
    isIntelligence: Boolean = false,
    onDisableIntelligence: () -> Unit = {},
    isModern: Boolean = false
) {
    val shuffleActive = isRoaming || isIntelligence || playMode == PlayMode.SHUFFLE
    val repeatActive = playMode == PlayMode.SINGLE_LOOP || playMode == PlayMode.LIST_LOOP

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
            buttonAlpha.animateTo(1f, tween(durationMillis = 220, easing = FastOutSlowInEasing))
        }
    }

    var showSpinner by remember { mutableStateOf(false) }
    LaunchedEffect(isLoading) {
        if (isLoading) {
            kotlinx.coroutines.delay(350L)
            showSpinner = true
        } else {
            showSpinner = false
        }
    }

    if (isModern) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MimikyuSpacing.lg)
                .padding(top = MimikyuSpacing.xs),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            MimikyuIconButton(
                onClick = {
                    if (isRoaming) {
                        onDisableRoaming()
                    } else if (isIntelligence) {
                        onDisableIntelligence()
                    } else {
                        onToggleShuffle()
                    }
                }
            ) {
                Icon(
                    painter = painterResource(
                        when {
                            isRoaming || isIntelligence -> MimikyuIcons.ShuffleSmart
                            else -> MimikyuIcons.Shuffle
                        }
                    ),
                    contentDescription = "Shuffle",
                    tint = if (shuffleActive) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.70f),
                    modifier = Modifier.size(22.dp)
                )
            }

            MimikyuIconButton(onClick = onPlayPrevious, style = MimikyuPress.Transport) {
                Icon(
                    painter = painterResource(MimikyuIcons.SkipPrevious),
                    contentDescription = "Previous",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }

            MimikyuIconButton(
                onClick = {
                    clickTrigger++
                    onTogglePlay()
                },
                style = MimikyuPress.Transport,
                modifier = Modifier
                    .size(56.dp)
                    .graphicsLayer {
                        alpha = buttonAlpha.value
                    }
            ) {
                if (showSpinner) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(36.dp),
                        strokeWidth = 3.dp,
                        color = Color.White
                    )
                } else {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = Color.White,
                        modifier = Modifier.size(42.dp)
                    )
                }
            }

            MimikyuIconButton(onClick = onPlayNext, style = MimikyuPress.Transport) {
                Icon(
                    painter = painterResource(MimikyuIcons.SkipNext),
                    contentDescription = "Next",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }

            MimikyuIconButton(
                onClick = onToggleRepeat
            ) {
                Icon(
                    painter = painterResource(
                        if (playMode == PlayMode.SINGLE_LOOP) MimikyuIcons.RepeatOnce
                        else MimikyuIcons.Repeat
                    ),
                    contentDescription = "Repeat",
                    tint = if (repeatActive) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.70f),
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MimikyuSpacing.lg)
                .padding(top = MimikyuSpacing.xs),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            MimikyuIconButton(
                onClick = {
                    if (isRoaming) {
                        onDisableRoaming()
                    } else if (isIntelligence) {
                        onDisableIntelligence()
                    } else {
                        onToggleShuffle()
                    }
                },
                modifier = Modifier.offset(x = (-10).dp)
            ) {
                Icon(
                    painter = painterResource(
                        when {
                            isRoaming || isIntelligence -> MimikyuIcons.ShuffleSmart
                            else -> MimikyuIcons.Shuffle
                        }
                    ),
                    contentDescription = "Shuffle",
                    tint = if (shuffleActive) MaterialTheme.colorScheme.primary else TextGray,
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
                    if (showSpinner) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(36.dp),
                            strokeWidth = 3.dp,
                            color = Color.Black
                        )
                    } else {
                        Icon(
                            if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = Color.Black,
                            modifier = Modifier.size(42.dp)
                        )
                    }
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

            MimikyuIconButton(
                onClick = onToggleRepeat,
                modifier = Modifier.offset(x = 10.dp)
            ) {
                Icon(
                    painter = painterResource(if (playMode == PlayMode.SINGLE_LOOP) MimikyuIcons.RepeatOnce else MimikyuIcons.Repeat),
                    contentDescription = "Repeat",
                    tint = if (repeatActive) MaterialTheme.colorScheme.primary else TextGray,
                    modifier = Modifier.size(26.dp)
                )
            }
        }
    }
}
