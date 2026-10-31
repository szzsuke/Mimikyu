package com.lin0721.linmusic.feature.settings.ui

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.RadioButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import com.lin0721.linmusic.core.preferences.SettingsPreferences
import com.lin0721.linmusic.LocalBottomOverlayInset
import com.lin0721.linmusic.core.player.CrossfadePolicy
import com.lin0721.linmusic.feature.player.ui.LyricCapsuleSlider
import com.lin0721.linmusic.core.ui.components.ToastManager
import com.lin0721.linmusic.core.ui.theme.MimikyuSpacing

private fun resolveDisplayPath(context: android.content.Context, uriString: String?): String {
    if (uriString.isNullOrBlank()) {
        return "${Environment.getExternalStorageDirectory().absolutePath}/Music/October"
    }
    val uri = Uri.parse(uriString)
    return runCatching {
        val treeDocId = DocumentsContract.getTreeDocumentId(uri)
        val (volumeId, relativePath) = treeDocId.split(":", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        if (volumeId == "primary") {
            "${Environment.getExternalStorageDirectory().absolutePath}/$relativePath".trimEnd('/')
        } else {
            "$volumeId/$relativePath".trimEnd('/')
        }
    }.getOrElse {
        DocumentFile.fromTreeUri(context, uri)?.name ?: "Custom Folder"
    }
}

private fun resolveInitialFolderUri(uriString: String?): Uri {
    if (!uriString.isNullOrBlank()) {
        return Uri.parse(uriString)
    }
    runCatching {
        val defaultDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "October")
        if (!defaultDir.exists()) {
            defaultDir.mkdirs()
        }
    }
    return DocumentsContract.buildDocumentUri(
        "com.android.externalstorage.documents",
        "primary:Music/October"
    )
}

@Composable
fun PlaybackDownloadSettingsView(viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val autoPlayNext by viewModel.autoPlayNext.collectAsStateWithLifecycle()
    val playWithOtherApps by viewModel.playWithOtherApps.collectAsStateWithLifecycle()
    val resumeAfterExternalInterruption by viewModel.resumeAfterExternalInterruption.collectAsStateWithLifecycle()
    val crossfadeEnabled by viewModel.crossfadeEnabled.collectAsStateWithLifecycle()
    val automixEnabled by viewModel.automixEnabled.collectAsStateWithLifecycle()
    val crossfadeDurationMs by viewModel.crossfadeDurationMs.collectAsStateWithLifecycle()
    val streamCacheEnabled by viewModel.streamCacheEnabled.collectAsStateWithLifecycle()
    val downloadFolderUri by viewModel.downloadFolderUri.collectAsStateWithLifecycle()
    val downloadLyricsEnabled by viewModel.downloadLyricsEnabled.collectAsStateWithLifecycle()
    val animatedCoverEnabled by viewModel.animatedCoverEnabled.collectAsStateWithLifecycle()
    val canvasOverCellular by viewModel.canvasOverCellular.collectAsStateWithLifecycle()

    val folderPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        // ，Download
        viewModel.updateDownloadFolderUri(uri.toString())
        ToastManager.showToast("Download directory updated")
    }

    val downloadFolderSubtitle = remember(downloadFolderUri) {
        resolveDisplayPath(context, downloadFolderUri)
    }

    // Play and Download of
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(MimikyuSpacing.md),
        contentPadding = PaddingValues(top = 8.dp, bottom = LocalBottomOverlayInset.current + 16.dp)
    ) {
        item {
            SettingsGroupCard(SettingsSubMenu.PLAYBACK_DOWNLOAD.sectionTitles[0]) {
                SettingsSwitchRow(
                    title = "Autoplay recommended songs",
                    subtitle = "Automatically play similar recommendations when current track ends",
                    checked = autoPlayNext,
                    onCheckedChange = { viewModel.updateAutoPlayNext(it) }
                )
                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                SettingsSwitchRow(
                    title = "Play alongside other apps",
                    subtitle = "Prevent other apps from interrupting playback",
                    checked = playWithOtherApps,
                    onCheckedChange = { viewModel.updatePlayWithOtherApps(it) }
                )
                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                SettingsSwitchRow(
                    title = "Resume after external audio stops",
                    subtitle = "Automatically resume playback when interrupting media stops",
                    checked = resumeAfterExternalInterruption,
                    onCheckedChange = { viewModel.updateResumeAfterExternalInterruption(it) }
                )
                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                SettingsSwitchRow(
                    title = "Crossfade",
                    subtitle = "Crossfade smoothly into next song near the end (not on manual skip)",
                    checked = crossfadeEnabled,
                    onCheckedChange = { viewModel.updateCrossfadeEnabled(it) }
                )
                if (crossfadeEnabled) {
                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                    SettingsSwitchRow(
                        title = "Automix (Smart Transitions)",
                        subtitle = "Beat-align, trim silence, and perform midpoint bass swap between songs like Apple Music & Spotify",
                        checked = automixEnabled,
                        onCheckedChange = { viewModel.updateAutomixEnabled(it) }
                    )
                    if (automixEnabled) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Automix Blend Mode", color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp)
                                Text(
                                    text = "Smart (Dynamic 4-8 Bars)",
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Transitions adapt dynamically to tempo, phrase structure, and silence boundaries with seamless bass handover.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                fontSize = 12.sp,
                                lineHeight = 16.sp
                            )
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Manual Transition Duration", color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp)
                                Text(
                                    text = "${crossfadeDurationMs} ms",
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            LyricCapsuleSlider(
                                value = crossfadeDurationMs,
                                onValueChange = { viewModel.updateCrossfadeDurationMs(CrossfadePolicy.normalizeDurationMs(it)) },
                                valueRange = CrossfadePolicy.MIN_DURATION_MS.toFloat()..CrossfadePolicy.MAX_DURATION_MS.toFloat(),
                                startLabel = "Short",
                                endLabel = "Long"
                            )
                        }
                    }
                }

                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                SettingsSwitchRow(
                    title = "Animated Cover Art",
                    subtitle = "Stream looping motion/canvas cover art when available (Apple Music & Tidal)",
                    checked = animatedCoverEnabled,
                    onCheckedChange = { viewModel.updateAnimatedCoverEnabled(it) }
                )
                if (animatedCoverEnabled) {
                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                    SettingsSwitchRow(
                        title = "Stream Motion Art on Cellular",
                        subtitle = "Allow streaming motion cover clips when connected to mobile data",
                        checked = canvasOverCellular,
                        onCheckedChange = { viewModel.updateCanvasOverCellular(it) }
                    )
                }
            }
        }
        item {
            SettingsGroupCard(SettingsSubMenu.PLAYBACK_DOWNLOAD.sectionTitles[1]) {
                SettingsSwitchRow(
                    title = "Listen & Cache",
                    subtitle = "Automatically cache played songs at current quality to Listen & Cache folder",
                    checked = streamCacheEnabled,
                    onCheckedChange = { viewModel.updateStreamCacheEnabled(it) }
                )
                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                SettingsRow(
                    title = "Download Folder",
                    subtitle = downloadFolderSubtitle,
                    onClick = {
                        val initialUri = resolveInitialFolderUri(downloadFolderUri)
                        folderPickerLauncher.launch(initialUri)
                    }
                )
                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                SettingsSwitchRow(
                    title = "Download Lyrics",
                    subtitle = "Save a matching .lrc lyric file when downloading songs",
                    checked = downloadLyricsEnabled,
                    onCheckedChange = { viewModel.updateDownloadLyricsEnabled(it) }
                )
                if (!downloadFolderUri.isNullOrBlank()) {
                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                    SettingsRow(
                        title = "Reset default download folder",
                        subtitle = "Reset to Music/October",
                        onClick = {
                            viewModel.updateDownloadFolderUri(null)
                            ToastManager.showToast("Restored default download directory")
                        }
                    )
                }
            }
        }
    }
}
