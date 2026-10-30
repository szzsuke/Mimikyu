package com.lin0721.linmusic.feature.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import com.lin0721.linmusic.core.ui.components.MimikyuIconButton
import com.lin0721.linmusic.core.ui.theme.MimikyuSpacing
import com.lin0721.linmusic.core.ui.theme.MimikyuIcons

// 封面下方的歌名歌手行，右侧带收藏按钮与添加到播放列表/曲库按钮
@Composable
fun SongInfo(
    title: String,
    artist: String,
    isLiked: Boolean,
    onToggleLike: () -> Unit,
    onArtistClick: (() -> Unit)? = null,
    onAddToPlaylist: (() -> Unit)? = null,
    isModern: Boolean = false,
    onMoreClick: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MimikyuSpacing.lg)
            .padding(top = 8.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = MimikyuSpacing.md)
        ) {
            Text(
                text = title,
                color = Color.White,
                fontSize = if (isModern) 22.sp else 20.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier.basicMarquee()
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = artist,
                color = Color.White.copy(alpha = 0.7f),
                fontSize = if (isModern) 15.sp else 14.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (onArtistClick != null) Modifier.clickable(onClick = onArtistClick) else Modifier
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (isModern) 12.dp else 16.dp)
        ) {
            MimikyuIconButton(
                onClick = onToggleLike,
                modifier = Modifier
            ) {
                Icon(
                    painter = painterResource(
                        if (isLiked) MimikyuIcons.HeartFilled
                        else MimikyuIcons.Heart
                    ),
                    contentDescription = if (isLiked) "Remove from Your Library" else "Save to Your Library",
                    tint = if (isLiked) MaterialTheme.colorScheme.primary else Color.White,
                    modifier = Modifier.size(if (isModern) 24.dp else 26.dp)
                )
            }

            if (!isModern && onAddToPlaylist != null) {
                MimikyuIconButton(
                    onClick = onAddToPlaylist
                ) {
                    Icon(
                        painter = painterResource(MimikyuIcons.AddToPlaylist),
                        contentDescription = "Add to library/playlist",
                        tint = Color.White,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
        }
    }
}
