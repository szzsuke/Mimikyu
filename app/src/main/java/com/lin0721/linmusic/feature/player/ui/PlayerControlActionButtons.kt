package com.lin0721.linmusic.feature.player.ui

import android.media.AudioDeviceInfo
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SpeakerGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lin0721.linmusic.core.ui.components.MimikyuIconButton
import com.lin0721.linmusic.core.ui.interaction.pressable
import com.lin0721.linmusic.core.ui.theme.MimikyuPress
import com.lin0721.linmusic.core.ui.theme.InfoCardRadius
import com.lin0721.linmusic.core.ui.theme.TextGray
import com.lin0721.linmusic.core.ui.theme.MimikyuSpacing
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lin0721.linmusic.core.download.SongDownloadManager
import com.lin0721.linmusic.core.download.DownloadPreferences
import com.lin0721.linmusic.core.download.SongDownloadWorker
import com.lin0721.linmusic.core.ui.components.ToastManager
import com.lin0721.linmusic.core.ui.theme.MimikyuPink
import org.koin.compose.koinInject


// ：、Lyrics、PlayQueue
// connectedDevice Current（/），、，；Play
@Composable
fun ActionButtons(
    onOutputDeviceClick: () -> Unit,
    onQueueClick: () -> Unit,
    onShareClick: () -> Unit = {},
    onOpenFullScreenLyrics: () -> Unit = onShareClick,
    connectedDevice: AudioDeviceInfo? = null,
    isModern: Boolean = false,
    onMoreClick: () -> Unit = {},
    currentSongId: Long = 0L,
    onDownloadClick: () -> Unit = {}
) {
    if (isModern) {
        val devicePrimary = MaterialTheme.colorScheme.primary
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MimikyuSpacing.lg)
                .padding(top = MimikyuSpacing.xs, bottom = MimikyuSpacing.sm),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .weight(1f, fill = false)
                    .pressable(MimikyuPress.None, onClick = onOutputDeviceClick)
            ) {
                Icon(
                    painter = androidx.compose.ui.res.painterResource(
                        connectedDevice?.let { deviceIcon(it.type) } ?: com.lin0721.linmusic.core.ui.theme.MimikyuIcons.Devices
                    ),
                    contentDescription = "Connect device",
                    tint = devicePrimary,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = connectedDevice?.let { deviceLabel(it) }?.ifBlank { "This device" } ?: "This device",
                    color = devicePrimary,
                    fontSize = 12.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                MimikyuIconButton(
                    onClick = onOpenFullScreenLyrics,
                    modifier = Modifier.offset(x = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Lyrics,
                        contentDescription = "Lyrics",
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(20.dp)
                    )
                }
                PlayerDownloadButton(
                    songId = currentSongId,
                    onDownloadClick = onDownloadClick,
                    tint = Color.White.copy(alpha = 0.85f),
                    size = 20.dp
                )
                MimikyuIconButton(onClick = onQueueClick) {
                    Icon(
                        painter = androidx.compose.ui.res.painterResource(com.lin0721.linmusic.core.ui.theme.MimikyuIcons.Queue),
                        contentDescription = "Queue",
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    } else {
        val outputDeviceTint = if (connectedDevice != null) MaterialTheme.colorScheme.primary else TextGray

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MimikyuSpacing.lg)
                .padding(top = MimikyuSpacing.sm, bottom = MimikyuSpacing.md),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .pressable(MimikyuPress.None, onClick = onOutputDeviceClick)
            ) {
                MimikyuIconButton(
                    onClick = onOutputDeviceClick,
                    modifier = Modifier.offset(x = (-12).dp)
                ) {
                    if (connectedDevice != null) {
                        Icon(androidx.compose.ui.res.painterResource(deviceIcon(connectedDevice.type)), contentDescription = "Connect device", tint = outputDeviceTint, modifier = Modifier.size(24.dp))
                    } else {
                        Icon(androidx.compose.ui.res.painterResource(com.lin0721.linmusic.core.ui.theme.MimikyuIcons.Devices), contentDescription = "Connect device", tint = outputDeviceTint, modifier = Modifier.size(24.dp))
                    }
                }
                if (connectedDevice != null) {
                    Text(
                        text = deviceLabel(connectedDevice),
                        color = outputDeviceTint,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .offset(x = (-8).dp)
                            .widthIn(max = 140.dp)
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                PlayerDownloadButton(
                    songId = currentSongId,
                    onDownloadClick = onDownloadClick,
                    tint = TextGray,
                    size = 24.dp
                )
                MimikyuIconButton(
                    onClick = onQueueClick,
                    modifier = Modifier.offset(x = 12.dp)
                ) {
                    Icon(
                        painter = androidx.compose.ui.res.painterResource(com.lin0721.linmusic.core.ui.theme.MimikyuIcons.Queue),
                        contentDescription = "Queue",
                        tint = TextGray,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}


@Composable
fun PlayerDownloadButton(
    songId: Long,
    onDownloadClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Color.White.copy(alpha = 0.85f),
    size: androidx.compose.ui.unit.Dp = 22.dp
) {
    val songDownloadManager: SongDownloadManager = koinInject()
    val downloadPreferences: DownloadPreferences = koinInject()

    val records by downloadPreferences.records.collectAsStateWithLifecycle(initialValue = emptyList())
    val isDownloaded = remember(records, songId) {
        songId > 0 && records.any { it.songId == songId }
    }

    val workInfos by songDownloadManager.observeSingle(songId)
        .collectAsStateWithLifecycle(initialValue = emptyList())

    val isDownloading = remember(workInfos) {
        workInfos.any {
            it.state == androidx.work.WorkInfo.State.RUNNING ||
            it.state == androidx.work.WorkInfo.State.ENQUEUED
        }
    }

    var justClickedDownload by remember(songId) { mutableStateOf(false) }

    LaunchedEffect(isDownloaded) {
        if (isDownloaded) {
            justClickedDownload = false
        }
    }

    val activeDownloading = (isDownloading || justClickedDownload) && !isDownloaded

    MimikyuIconButton(
        onClick = {
            if (isDownloaded) {
                ToastManager.showToast("Song already downloaded")
            } else if (activeDownloading) {
                ToastManager.showToast("Downloading...")
            } else {
                justClickedDownload = true
                onDownloadClick()
            }
        },
        modifier = modifier
    ) {
        if (isDownloaded) {
            Icon(
                painter = androidx.compose.ui.res.painterResource(com.lin0721.linmusic.core.ui.theme.MimikyuIcons.DownloadDone),
                contentDescription = "Downloaded",
                tint = MimikyuPink,
                modifier = Modifier.size(size)
            )
        } else {
            Icon(
                painter = androidx.compose.ui.res.painterResource(com.lin0721.linmusic.core.ui.theme.MimikyuIcons.Download),
                contentDescription = if (activeDownloading) "Downloading" else "Download",
                tint = if (activeDownloading) MimikyuPink else tint,
                modifier = Modifier.size(size)
            )
        }
    }
}
