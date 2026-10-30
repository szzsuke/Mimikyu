package com.lin0721.linmusic.feature.artist.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lin0721.linmusic.core.model.ArtistDetailInfo
import com.lin0721.linmusic.core.ui.theme.MimikyuSpacing

// ， Header，Artists and
@Composable
fun ArtistNameSection(artist: ArtistDetailInfo, progress: Float, tintColor: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(310.dp),
        contentAlignment = Alignment.BottomStart
    ) {
        // ：， and Back of
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(tintColor.copy(alpha = progress))
        )

        // ArtistsFollowers of
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.6f)
                        )
                    )
                )
                .padding(horizontal = MimikyuSpacing.md)
                .padding(bottom = MimikyuSpacing.md, top = MimikyuSpacing.lg)
        ) {
            if (!artist.logoUrl.isNullOrBlank()) {
                var logoFailed by androidx.compose.runtime.remember(artist.logoUrl) {
                    androidx.compose.runtime.mutableStateOf(false)
                }
                if (!logoFailed) {
                    coil.compose.AsyncImage(
                        model = artist.logoUrl,
                        contentDescription = artist.name,
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                        alignment = Alignment.BottomStart,
                        onError = { logoFailed = true },
                        modifier = Modifier
                            .heightIn(max = 72.dp)
                            .fillMaxWidth(0.75f)
                    )
                } else {
                    Text(
                        text = artist.name,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 44.sp,
                        fontWeight = FontWeight.Black,
                        lineHeight = 48.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else {
                Text(
                    text = artist.name,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Black,
                    lineHeight = 48.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(6.dp))

            // Artists of
            val chineseName = (artist.trans?.takeIf { it.isNotBlank() }
                ?: artist.alias?.firstOrNull { it.isNotBlank() })?.takeIf { it != artist.name }

            if (!chineseName.isNullOrBlank()) {
                Text(
                    text = chineseName,
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            } else {
                Spacer(Modifier.height(18.dp))
            }
        }
    }
}
