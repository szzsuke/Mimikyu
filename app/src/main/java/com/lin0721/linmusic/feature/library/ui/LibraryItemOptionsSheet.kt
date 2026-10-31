package com.lin0721.linmusic.feature.library.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import com.lin0721.linmusic.core.ui.components.CoverPlaceholder
import com.lin0721.linmusic.core.ui.components.MimikyuDragHandle
import androidx.annotation.DrawableRes
import androidx.compose.ui.res.painterResource
import com.lin0721.linmusic.core.ui.theme.MimikyuIcons
import com.lin0721.linmusic.core.ui.theme.BottomSheetShape
import com.lin0721.linmusic.core.ui.theme.DownloadedGreen
import com.lin0721.linmusic.core.ui.theme.MimikyuPink
import com.lin0721.linmusic.core.ui.theme.LibraryVioletGradient
import com.lin0721.linmusic.core.ui.theme.MimikyuSpacing
import com.lin0721.linmusic.core.ui.theme.RadiusCompact


private data class LibraryOptionsMenuItem(
    @DrawableRes val iconRes: Int = 0,
    val icon: ImageVector? = null,
    val title: String,
    val isDestructive: Boolean = false,
    val onClick: () -> Unit
)


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryItemOptionsSheet(
    item: LibraryItem,
    onDismiss: () -> Unit,
    onTogglePin: (String) -> Unit,
    onEditOrder: () -> Unit,
    onShare: (LibraryItem) -> Unit,
    onDownload: (LibraryItem) -> Unit,
    onDeletePlaylist: (Long) -> Unit,
    onUnsubscribePlaylist: (Long) -> Unit,
    onUnsubscribeAlbum: (Long) -> Unit,
    onUnsubscribeArtist: (Long) -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.background,
        shape = BottomSheetShape,
        dragHandle = { MimikyuDragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = MimikyuSpacing.md)
        ) {
            // 1. （ +  + ）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (item.isLikedSongs) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(RoundedCornerShape(RadiusCompact))
                            .background(Brush.linearGradient(LibraryVioletGradient)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(MimikyuIcons.HeartFilled),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                } else {
                    val shape = if (item.type == LibraryItemType.ARTIST) CircleShape else RoundedCornerShape(RadiusCompact)
                    SubcomposeAsyncImage(
                        model = if (item.coverUrl.isNotEmpty()) "${item.coverUrl}?param=150y150" else null,
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        loading = { CoverPlaceholder() },
                        error = { CoverPlaceholder() },
                        modifier = Modifier
                            .size(54.dp)
                            .clip(shape)
                    )
                }

                Spacer(modifier = Modifier.width(MimikyuSpacing.md))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(MimikyuSpacing.xs))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (item.isPinned) {
                            Icon(
                                painter = painterResource(MimikyuIcons.PinFilled),
                                contentDescription = "Pinned",
                                tint = MimikyuPink,
                                modifier = Modifier
                                    .size(12.dp)
                                    .padding(end = MimikyuSpacing.xs)
                            )
                        }
                        Text(
                            text = item.subtitle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            HorizontalDivider(
                color = Color.White.copy(alpha = 0.08f),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = MimikyuSpacing.sm)
            )

            // 2.
            val menuItems = buildList {
                val pinTitle = if (item.isPinned) "Unpin" else "Pin to top"

                when (item.type) {
                    LibraryItemType.PLAYLIST -> {
                        if (item.isLikedSongs) {
                            // "Liked Songs"
                            add(
                                LibraryOptionsMenuItem(
                                    iconRes = if (item.isPinned) MimikyuIcons.PinFilled else MimikyuIcons.Pin,
                                    title = pinTitle,
                                    onClick = {
                                        onDismiss()
                                        onTogglePin(item.id)
                                    }
                                )
                            )
                            add(
                                LibraryOptionsMenuItem(
                                    iconRes = MimikyuIcons.Share,
                                    title = "Share playlist",
                                    onClick = {
                                        onDismiss()
                                        onShare(item)
                                    }
                                )
                            )
                            add(
                                LibraryOptionsMenuItem(
                                    iconRes = MimikyuIcons.Download,
                                    title = "Download playlist",
                                    onClick = {
                                        onDismiss()
                                        onDownload(item)
                                    }
                                )
                            )
                        } else {
                            add(
                                LibraryOptionsMenuItem(
                                    iconRes = if (item.isPinned) MimikyuIcons.PinFilled else MimikyuIcons.Pin,
                                    title = pinTitle,
                                    onClick = {
                                        onDismiss()
                                        onTogglePin(item.id)
                                    }
                                )
                            )
                            add(
                                LibraryOptionsMenuItem(
                                    iconRes = MimikyuIcons.Sort,
                                    title = "Change position",
                                    onClick = {
                                        onDismiss()
                                        onEditOrder()
                                    }
                                )
                            )
                            add(
                                LibraryOptionsMenuItem(
                                    iconRes = MimikyuIcons.Share,
                                    title = "Share playlist",
                                    onClick = {
                                        onDismiss()
                                        onShare(item)
                                    }
                                )
                            )
                            add(
                                LibraryOptionsMenuItem(
                                    iconRes = MimikyuIcons.Download,
                                    title = "Download playlist",
                                    onClick = {
                                        onDismiss()
                                        onDownload(item)
                                    }
                                )
                            )
                            if (item.isOwnedByMe) {
                                add(
                                    LibraryOptionsMenuItem(
                                        icon = Icons.Default.Delete,
                                        title = "Delete playlist",
                                        isDestructive = true,
                                        onClick = {
                                            onDismiss()
                                            item.id.toLongOrNull()?.let { onDeletePlaylist(it) }
                                        }
                                    )
                                )
                            } else {
                                add(
                                    LibraryOptionsMenuItem(
                                        iconRes = MimikyuIcons.HeartFilled,
                                        title = "Remove from favorites",
                                        isDestructive = true,
                                        onClick = {
                                            onDismiss()
                                            item.id.toLongOrNull()?.let { onUnsubscribePlaylist(it) }
                                        }
                                    )
                                )
                            }
                        }
                    }

                    LibraryItemType.ALBUM -> {
                        add(
                            LibraryOptionsMenuItem(
                                iconRes = if (item.isPinned) MimikyuIcons.PinFilled else MimikyuIcons.Pin,
                                title = pinTitle,
                                onClick = {
                                    onDismiss()
                                    onTogglePin(item.id)
                                }
                            )
                        )
                        add(
                            LibraryOptionsMenuItem(
                                iconRes = MimikyuIcons.HeartFilled,
                                title = "Remove from favorites",
                                isDestructive = true,
                                onClick = {
                                    onDismiss()
                                    item.id.toLongOrNull()?.let { onUnsubscribeAlbum(it) }
                                }
                            )
                        )
                        add(
                            LibraryOptionsMenuItem(
                                iconRes = MimikyuIcons.Share,
                                title = "Share album",
                                onClick = {
                                    onDismiss()
                                    onShare(item)
                                }
                            )
                        )
                        add(
                            LibraryOptionsMenuItem(
                                iconRes = MimikyuIcons.Download,
                                title = "Download album",
                                onClick = {
                                    onDismiss()
                                    onDownload(item)
                                }
                            )
                        )
                    }

                    LibraryItemType.ARTIST -> {
                        add(
                            LibraryOptionsMenuItem(
                                iconRes = if (item.isPinned) MimikyuIcons.PinFilled else MimikyuIcons.Pin,
                                title = pinTitle,
                                onClick = {
                                    onDismiss()
                                    onTogglePin(item.id)
                                }
                            )
                        )
                        add(
                            LibraryOptionsMenuItem(
                                iconRes = MimikyuIcons.User,
                                title = "Unfollow artist",
                                isDestructive = true,
                                onClick = {
                                    onDismiss()
                                    item.id.toLongOrNull()?.let { onUnsubscribeArtist(it) }
                                }
                            )
                        )
                        add(
                            LibraryOptionsMenuItem(
                                iconRes = MimikyuIcons.Share,
                                title = "Share artist",
                                onClick = {
                                    onDismiss()
                                    onShare(item)
                                }
                            )
                        )
                    }

                    LibraryItemType.MV -> Unit
                }
            }

            // 3.
            LazyColumn(
                modifier = Modifier.fillMaxWidth()
            ) {
                items(menuItems, key = { it.title }) { menuItem ->
                    val iconColor = if (menuItem.isDestructive) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    val titleColor = if (menuItem.isDestructive) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = menuItem.onClick)
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (menuItem.iconRes != 0) {
                            Icon(
                                painter = painterResource(menuItem.iconRes),
                                contentDescription = menuItem.title,
                                tint = iconColor,
                                modifier = Modifier.size(24.dp)
                            )
                        } else if (menuItem.icon != null) {
                            Icon(
                                imageVector = menuItem.icon,
                                contentDescription = menuItem.title,
                                tint = iconColor,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(MimikyuSpacing.md))
                        Text(
                            text = menuItem.title,
                            color = titleColor,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
