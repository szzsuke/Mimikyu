package com.lin0721.linmusic.feature.player.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.annotation.DrawableRes
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.lin0721.linmusic.core.ui.theme.MimikyuIcons
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import com.lin0721.linmusic.core.ui.components.CoverPlaceholder
import com.lin0721.linmusic.core.ui.theme.BackgroundDark
import com.lin0721.linmusic.core.ui.theme.BottomSheetShape
import com.lin0721.linmusic.core.ui.theme.RadiusCompact
import com.lin0721.linmusic.core.ui.theme.DragHandleShape
import com.lin0721.linmusic.core.ui.theme.NeteaseRed
import com.lin0721.linmusic.core.ui.theme.TextGray
import com.lin0721.linmusic.core.model.getQualityDisplayName
import kotlinx.coroutines.launch
import com.lin0721.linmusic.core.ui.theme.MimikyuSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongMoreOptionsSheet(
    title: String,
    artist: String,
    coverUrl: String,
    albumName: String,
    isLiked: Boolean,
    sleepTimerRemaining: Long,
    currentQuality: String,
    onToggleLike: () -> Unit,
    onAlbumClick: () -> Unit,
    onArtistClick: () -> Unit,
    onShowTimerClick: () -> Unit,
    onQualitySelected: (String) -> Unit,
    isIntelligence: Boolean,
    onToggleIntelligence: (Boolean) -> Unit,
    onStartSimilarRoaming: () -> Unit,
    onInsertSimilarSongs: () -> Unit,
    onCollectClick: () -> Unit,
    onShareClick: () -> Unit,
    onDownloadClick: () -> Unit,

    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = BackgroundDark,
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
                .navigationBarsPadding()
                .padding(bottom = MimikyuSpacing.md)
        ) {
            // ： + Songs + Artists
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SubcomposeAsyncImage(
                    model = coverUrl.ifEmpty { null },
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    loading = { CoverPlaceholder() },
                    error = { CoverPlaceholder() },
                    modifier = Modifier
                        .size(54.dp)
                        .clip(RoundedCornerShape(RadiusCompact))
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = artist,
                        color = TextGray,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            HorizontalDivider(
                color = Color.White.copy(alpha = 0.08f),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = MimikyuSpacing.sm)
            )


            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                // 1. Albums
                OptionRow(
                    iconRes = MimikyuIcons.Album,
                    text = if (albumName.isNotBlank()) "Album: $albumName" else "Go to Album",
                    onClick = {
                        scope.launch {
                            sheetState.hide()
                            onDismiss()
                            onAlbumClick()
                        }
                    }
                )

                // 2. Artists
                OptionRow(
                    iconRes = MimikyuIcons.User,
                    text = if (artist.isNotBlank()) "Artist: $artist" else "Go to Artist",
                    onClick = {
                        scope.launch {
                            sheetState.hide()
                            onDismiss()
                            onArtistClick()
                        }
                    }
                )

                // 3. Save to Playlist
                OptionRow(
                    iconRes = MimikyuIcons.AddToPlaylist,
                    text = "Add to Playlist",
                    onClick = {
                        scope.launch {
                            sheetState.hide()
                            onDismiss()
                            onCollectClick()
                        }
                    }
                )

                // 4. Heartbeat Mode
                OptionRow(
                    iconRes = MimikyuIcons.Enhance,
                    text = if (isIntelligence) "Turn Off Heartbeat Mode" else "Turn On Heartbeat Mode",
                    onClick = {
                        scope.launch {
                            sheetState.hide()
                            onDismiss()
                            onToggleIntelligence(!isIntelligence)
                        }
                    }
                )

                // 5. Songs
                OptionRow(
                    iconRes = MimikyuIcons.Radio,
                    text = "Start Similar Song Radio",
                    onClick = {
                        scope.launch {
                            sheetState.hide()
                            onDismiss()
                            onStartSimilarRoaming()
                        }
                    }
                )

                // 6. Songs
                OptionRow(
                    iconRes = MimikyuIcons.AddToQueue,
                    text = "Play Similar Songs Next",
                    onClick = {
                        scope.launch {
                            sheetState.hide()
                            onDismiss()
                            onInsertSimilarSongs()
                        }
                    }
                )


                // 8. Download
                OptionRow(
                    iconRes = MimikyuIcons.Download,
                    text = "Download",
                    onClick = {
                        scope.launch {
                            sheetState.hide()
                            onDismiss()
                            onDownloadClick()
                        }
                    }
                )

                // Share
                OptionRow(
                    iconRes = MimikyuIcons.Share,
                    text = "Share",
                    onClick = {
                        scope.launch {
                            sheetState.hide()
                            onDismiss()
                            onShareClick()
                        }
                    }
                )

                // 9.
                var showQualityDialog by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            showQualityDialog = true
                        }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(MimikyuIcons.Tune),
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = "Sound Profile: ${getQualityDisplayName(currentQuality)}",
                        color = Color.White,
                        fontSize = 15.sp,
                        modifier = Modifier.weight(1f)
                    )
                }

                if (showQualityDialog) {
                    val qualitySheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                    val presets = listOf(
                        com.lin0721.linmusic.core.player.AudioMasteringPreset.STUDIO_MASTER,
                        com.lin0721.linmusic.core.player.AudioMasteringPreset.BASS_BOOST,
                        com.lin0721.linmusic.core.player.AudioMasteringPreset.VOCAL_ACOUSTIC,
                        com.lin0721.linmusic.core.player.AudioMasteringPreset.FLAT
                    )
                    ModalBottomSheet(
                        onDismissRequest = { showQualityDialog = false },
                        sheetState = qualitySheetState,
                        containerColor = BackgroundDark,
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
                                .navigationBarsPadding()
                                .padding(start = MimikyuSpacing.lg, end = MimikyuSpacing.lg, bottom = MimikyuSpacing.lg)
                        ) {
                            Text(
                                text = "Mastering Curve & Sound Presets",
                                color = Color.White,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.ExtraBold,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                            Text(
                                text = "Hardware 32-bit DSP with 8-band parametric curve & multi-band compressor",
                                color = Color.White.copy(alpha = 0.6f),
                                fontSize = 13.sp,
                                modifier = Modifier.padding(bottom = MimikyuSpacing.md)
                            )
                            presets.forEach { preset ->
                                val isSelected = currentQuality == preset.key ||
                                        (preset == com.lin0721.linmusic.core.player.AudioMasteringPreset.STUDIO_MASTER &&
                                                (currentQuality == "lossless" || currentQuality == "standard" || currentQuality == "exhigh" || currentQuality == "hires" || currentQuality == "jymaster"))
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(if (isSelected) Color.White.copy(alpha = 0.08f) else Color.Transparent)
                                        .clickable {
                                            onQualitySelected(preset.key)
                                            scope.launch {
                                                qualitySheetState.hide()
                                                showQualityDialog = false
                                            }
                                        }
                                        .padding(horizontal = 12.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = preset.title,
                                                color = if (isSelected) com.lin0721.linmusic.core.ui.theme.MimikyuGreen else Color.White,
                                                fontSize = 15.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = if (isSelected) com.lin0721.linmusic.core.ui.theme.MimikyuGreen.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.12f)
                                            ) {
                                                Text(
                                                    text = preset.badge,
                                                    color = if (isSelected) com.lin0721.linmusic.core.ui.theme.MimikyuGreen else Color.White.copy(alpha = 0.7f),
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = preset.subtitle,
                                            color = Color.White.copy(alpha = 0.6f),
                                            fontSize = 12.sp,
                                            lineHeight = 16.sp
                                        )
                                    }
                                    if (isSelected) {
                                        Icon(
                                            painter = painterResource(MimikyuIcons.Check),
                                            contentDescription = null,
                                            tint = com.lin0721.linmusic.core.ui.theme.MimikyuGreen,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                            }
                        }
                    }
                }

                // 10. Sleep Timer
                val timerText = if (sleepTimerRemaining > 0L) {
                    val mins = (sleepTimerRemaining + 59999L) / (60 * 1000L)
                    "Sleep Timer (${mins}m)"
                } else {
                    "Sleep Timer"
                }
                OptionRow(
                    iconRes = MimikyuIcons.Time,
                    text = timerText,
                    onClick = {
                        scope.launch {
                            sheetState.hide()
                            onDismiss()
                            onShowTimerClick()
                        }
                    }
                )


            }
        }
    }
}

@Composable
private fun OptionRow(
    @DrawableRes iconRes: Int,
    text: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = text,
            color = Color.White,
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
