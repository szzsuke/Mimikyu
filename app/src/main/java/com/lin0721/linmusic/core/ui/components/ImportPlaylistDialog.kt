package com.lin0721.linmusic.core.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lin0721.linmusic.core.importer.ImportPlaylistResult
import com.lin0721.linmusic.core.importer.PlaylistImporter
import com.lin0721.linmusic.core.ui.theme.MimikyuSpacing
import com.lin0721.linmusic.core.ui.theme.PillRadius
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportPlaylistDialog(
    onDismiss: () -> Unit,
    importer: PlaylistImporter = koinInject()
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var isImporting by remember { mutableStateOf(false) }
    var currentStatus by remember { mutableStateOf("") }
    var progressCurrent by remember { mutableIntStateOf(0) }
    var progressTotal by remember { mutableIntStateOf(0) }
    var importResults by remember { mutableStateOf<List<ImportPlaylistResult>?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var linkUrlText by remember { mutableStateOf("") }

    val handleLinkImport: () -> Unit = {
        val input = linkUrlText.trim()
        if (input.isNotBlank()) {
            isImporting = true
            errorMessage = null
            importResults = null
            coroutineScope.launch {
                importer.importFromLinkUrl(
                    urlOrId = input,
                    onProgress = { status, cur, tot ->
                        currentStatus = status
                        progressCurrent = cur
                        progressTotal = tot
                    }
                ).onSuccess { results ->
                    isImporting = false
                    importResults = results
                    val hasLikedSongs = results.any { it.playlistId == 0L || it.playlistName.equals("Liked Songs", ignoreCase = true) }
                    if (hasLikedSongs) {
                        ToastManager.showToast("Imported tracks directly to your Liked Songs!")
                    } else {
                        ToastManager.showToast("Imported ${results.size} playlist(s) to your cloud account!")
                    }
                }.onFailure { err ->
                    isImporting = false
                    errorMessage = err.message ?: "Failed to import from link"
                }
            }
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            isImporting = true
            errorMessage = null
            importResults = null
            coroutineScope.launch {
                importer.importFromUri(
                    context = context,
                    uri = uri,
                    onProgress = { status, cur, tot ->
                        currentStatus = status
                        progressCurrent = cur
                        progressTotal = tot
                    }
                ).onSuccess { results ->
                    isImporting = false
                    importResults = results
                    val hasLikedSongs = results.any { it.playlistId == 0L || it.playlistName.equals("Liked Songs", ignoreCase = true) }
                    if (hasLikedSongs) {
                        ToastManager.showToast("Imported tracks directly to your Liked Songs!")
                    } else {
                        ToastManager.showToast("Imported ${results.size} playlist(s) to your cloud account!")
                    }
                }.onFailure { err ->
                    isImporting = false
                    errorMessage = err.message ?: "Failed to import playlist"
                }
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = { if (!isImporting) onDismiss() },
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header icon
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (importResults != null) Icons.Default.CheckCircle else Icons.Outlined.FileUpload,
                    contentDescription = null,
                    tint = if (importResults != null) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
            }

            Spacer(Modifier.height(16.dp))

            Text(
                text = if (importResults != null) "Import Complete!" else "Import Playlist",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(Modifier.height(6.dp))

            Text(
                text = if (importResults != null) {
                    "Your playlist has been saved to your Mimikyu account and is automatically synced across all your devices."
                } else {
                    "Import playlists or your Liked Songs from Spotify, Apple Music, or Soundiiz by selecting a .CSV file or .ZIP archive (up to 5MB)."
                },
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                lineHeight = 18.sp
            )

            Spacer(Modifier.height(20.dp))

            // Importing state with progress
            if (isImporting) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.background)
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(36.dp),
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 3.dp
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = currentStatus.ifBlank { "Processing..." },
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center
                    )
                    if (progressTotal > 0) {
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { progressCurrent.toFloat() / progressTotal.toFloat() },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = Color.White.copy(alpha = 0.1f)
                        )
                    }
                }
            } else if (importResults != null) {
                // Success state list
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.background)
                        .padding(16.dp)
                ) {
                    importResults!!.forEach { res ->
                        val isLikedSongs = res.playlistId == 0L || res.playlistName.equals("Liked Songs", ignoreCase = true)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = if (isLikedSongs) Icons.Default.Favorite else Icons.AutoMirrored.Outlined.QueueMusic,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = res.playlistName,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = if (isLikedSongs) {
                                        "${res.resolvedCount} tracks added directly to your Liked Songs"
                                    } else {
                                        "${res.resolvedCount} tracks matched & saved to Cloud"
                                    },
                                    fontSize = 12.sp,
                                    color = Color(0xFF4CAF50)
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))

                MimikyuButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    shape = RoundedCornerShape(PillRadius)
                ) {
                    Text("Done", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
                }
            } else {
                // Initial selection state
                if (errorMessage != null) {
                    Text(
                        text = errorMessage!!,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                }

                // File format hint card
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(alpha = 0.05f))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.CloudDone,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = "Playlists and Liked Songs are synced to your cloud account and automatically restored if you switch devices.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.height(16.dp))

                // Universal Link Input Field
                OutlinedTextField(
                    value = linkUrlText,
                    onValueChange = { linkUrlText = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Paste playlist, album, or track link", fontSize = 13.sp) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    trailingIcon = {
                        if (linkUrlText.isNotBlank()) {
                            IconButton(onClick = { linkUrlText = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear", modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                )

                Spacer(Modifier.height(10.dp))

                MimikyuButton(
                    onClick = { handleLinkImport() },
                    enabled = linkUrlText.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    shape = RoundedCornerShape(PillRadius)
                ) {
                    Text(
                        text = "Import from Link",
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }

                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    HorizontalDivider(modifier = Modifier.weight(1f), color = Color.White.copy(alpha = 0.15f))
                    Text(
                        text = "  OR  ",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    HorizontalDivider(modifier = Modifier.weight(1f), color = Color.White.copy(alpha = 0.15f))
                }

                Spacer(Modifier.height(16.dp))

                // Select File Button
                MimikyuButton(
                    onClick = {
                        filePickerLauncher.launch(
                            arrayOf(
                                "text/csv",
                                "text/comma-separated-values",
                                "application/zip",
                                "application/x-zip-compressed"
                            )
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    shape = RoundedCornerShape(PillRadius)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.FolderZip,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Select .CSV or .ZIP File (Max 5MB)",
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
            }
        }
    }
}
