package com.lin0721.linmusic.feature.create.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lin0721.linmusic.core.ui.components.MimikyuTextButton
import com.lin0721.linmusic.core.ui.components.MimikyuButton
import com.lin0721.linmusic.core.ui.components.MimikyuSwitch
import com.lin0721.linmusic.core.ui.theme.BackgroundDark
import com.lin0721.linmusic.core.ui.theme.BottomSheetShape
import com.lin0721.linmusic.core.ui.theme.DragHandleShape
import com.lin0721.linmusic.core.ui.theme.NeteaseRed
import com.lin0721.linmusic.core.ui.theme.PillRadius
import com.lin0721.linmusic.core.ui.theme.SurfaceDark
import com.lin0721.linmusic.core.ui.theme.SurfaceLight
import com.lin0721.linmusic.core.ui.theme.TextGray
import org.koin.androidx.compose.koinViewModel
import com.lin0721.linmusic.core.ui.theme.MimikyuSpacing
import com.lin0721.linmusic.core.ui.theme.MimikyuCharcoal
import com.lin0721.linmusic.core.ui.theme.MimikyuSilver

import androidx.compose.ui.res.painterResource
import com.lin0721.linmusic.core.ui.theme.MimikyuIcons

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreatePopupMenu(
    onDismiss: () -> Unit,
    onLoginRequest: () -> Unit
) {
    val viewModel: CreateViewModel = koinViewModel()
    val userProfile by viewModel.userProfile.collectAsStateWithLifecycle()
    val isCreating by viewModel.isCreating.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showCreateDialog by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.toastEvent.collect { message ->
            com.lin0721.linmusic.core.ui.components.ToastManager.showToast(message)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PillRadius))
            .background(SurfaceDark)
            .padding(vertical = MimikyuSpacing.sm)
    ) {
        CreateMenuItem(
            iconRes = MimikyuIcons.Playlist,
            title = "Playlists",
            subtitle = "Create a playlist containing songs or collections",
            onClick = {
                if (userProfile != null) {
                    showCreateDialog = true
                } else {
                    com.lin0721.linmusic.core.ui.components.ToastManager.showToast("Please log in to create playlists")
                    onDismiss()
                    onLoginRequest()
                }
            }
        )

    }

    if (showCreateDialog) {
        CreatePlaylistDialog(
            isCreating = isCreating,
            onConfirm = { name, isPrivate ->
                viewModel.createNewPlaylist(name, isPrivate) {
                    showCreateDialog = false
                    onDismiss()
                }
            },
            onDismiss = { showCreateDialog = false }
        )
    }
}

@Composable
private fun CreateMenuItem(
    iconRes: Int = 0,
    icon: ImageVector? = null,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(SurfaceLight),
            contentAlignment = Alignment.Center
        ) {
            if (iconRes != 0) {
                Icon(
                    painter = painterResource(id = iconRes),
                    contentDescription = null,
                    tint = MimikyuSilver,
                    modifier = Modifier.size(22.dp)
                )
            } else if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MimikyuSilver,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = MimikyuSilver,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                color = TextGray,
                fontSize = 12.sp
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreatePlaylistDialog(
    isCreating: Boolean,
    onConfirm: (String, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var isPrivate by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = { if (!isCreating) onDismiss() },
        containerColor = SurfaceDark,
        shape = BottomSheetShape,
        dragHandle = {
            Box(modifier = Modifier.padding(top = 12.dp, bottom = MimikyuSpacing.xs)) {
                Surface(
                    modifier = Modifier.width(40.dp).height(4.dp),
                    shape = DragHandleShape,
                    color = Color.White.copy(alpha = 0.3f)
                ) {}
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = MimikyuSpacing.lg, vertical = MimikyuSpacing.md)
        ) {
            Text(
                text = "New Playlist",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = MimikyuSpacing.md)
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(BackgroundDark)
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (name.isEmpty()) {
                    Text("My New Playlist", color = TextGray, fontSize = 15.sp)
                }
                BasicTextField(
                    value = name,
                    onValueChange = { name = it },
                    textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                    cursorBrush = SolidColor(NeteaseRed),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Set as private playlist", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text("Visible only to you", color = TextGray, fontSize = 12.sp)
                }
                MimikyuSwitch(
                    checked = isPrivate,
                    onCheckedChange = { isPrivate = it }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                MimikyuTextButton(
                    onClick = onDismiss,
                    enabled = !isCreating
                ) {
                    Text("Cancel", color = MimikyuSilver)
                }
                Spacer(modifier = Modifier.width(16.dp))
                MimikyuButton(
                    onClick = { onConfirm(name, isPrivate) },
                    enabled = !isCreating && name.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = NeteaseRed),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    if (isCreating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = MimikyuCharcoal,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Create", color = MimikyuCharcoal, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
