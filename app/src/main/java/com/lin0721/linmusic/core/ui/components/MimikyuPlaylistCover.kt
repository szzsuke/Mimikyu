package com.lin0721.linmusic.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.lin0721.linmusic.core.ui.theme.SurfaceDark

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape

enum class MimikyuCoverType {
    IMAGE_URL,
    DAILY_MIX,
    TOP_CHARTS,
    MOSAIC_4,
    RADIO,
    THIS_IS,
    VIBE_MIX,
    APPLE_RECIPE
}

val MIMIKYU_PASTEL_PALETTE = listOf(
    Color(0xFF7FE4C6), // Mint / Cyan
    Color(0xFFFFA27E), // Peach / Coral
    Color(0xFF7DC6F5), // Sky Blue
    Color(0xFFB490E0), // Lavender
    Color(0xFFC4E865), // Lime
    Color(0xFFF587B6), // Pink
    Color(0xFFFFB84D), // Soft Amber
    Color(0xFF6BE395), // Mint Green
    Color(0xFFE57373), // Warm Red
    Color(0xFF4DD0E1)  // Aqua
)

fun getMimikyuPastelColor(seed: String): Color {
    val idx = kotlin.math.abs(seed.hashCode()) % MIMIKYU_PASTEL_PALETTE.size
    return MIMIKYU_PASTEL_PALETTE[idx]
}

/**
 * Mimikyu & Apple Music Recipe Playlist & Mix Cover Generator.
 * Replicates the exact visual identity from Apple Music & Mimikyu Android apps:
 * - Apple Music Recipe: Dynamic multi-stop Aurora/Orb/Sweep gradients + typography overlay.
 * - Daily Mix: Split duotone background with large bold "DAILY MIX [N]" title and artist list.
 * - Radio: Solid pastel background with 3 circular artist cutouts, "RADIO" tag, and bold artist name.
 * - This Is: Classic white top header with bold "THIS IS", centered artist portrait, and bottom accent band.
 * - Vibe Mix: Full photo cover with floating bottom accent stripe container (e.g. "Pop Mix", "Chill Mix").
 * - Top Charts / Top 100: Bold high-contrast typography, chart badge.
 * - Mosaic 4-Tile: 2x2 grid of album covers for eclectic mixes.
 */
@Composable
fun MimikyuPlaylistCover(
    title: String,
    subtitle: String = "",
    coverUrl: String = "",
    coverType: MimikyuCoverType = MimikyuCoverType.IMAGE_URL,
    primaryColor: Color = Color(0xFF1DB954),
    secondaryColor: Color = Color(0xFF191414),
    mosaicImages: List<String> = emptyList(),
    modifier: Modifier = Modifier
) {
    val cornerRadius = 8.dp

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(cornerRadius))
            .background(SurfaceDark)
    ) {
        when (coverType) {
            MimikyuCoverType.APPLE_RECIPE -> {
                AppleMusicPlaylistCover(
                    title = title,
                    subtitle = subtitle,
                    coverUrl = coverUrl,
                    mosaicImages = mosaicImages,
                    cornerRadius = cornerRadius,
                    modifier = Modifier.fillMaxSize()
                )
            }

            MimikyuCoverType.RADIO -> {
                val bg = if (primaryColor != Color(0xFF1DB954)) primaryColor else getMimikyuPastelColor(title)
                RadioCoverContent(
                    artistName = title,
                    centerCover = coverUrl,
                    sideCovers = mosaicImages,
                    backgroundColor = bg
                )
            }

            MimikyuCoverType.THIS_IS -> {
                val accent = if (primaryColor != Color(0xFF1DB954)) primaryColor else getMimikyuPastelColor(title)
                ThisIsCoverContent(
                    artistName = title,
                    artistCover = coverUrl,
                    accentColor = accent
                )
            }

            MimikyuCoverType.VIBE_MIX -> {
                val accent = if (primaryColor != Color(0xFF1DB954)) primaryColor else getMimikyuPastelColor(title)
                VibeMixCoverContent(
                    mixTitle = title,
                    backgroundCover = coverUrl,
                    accentColor = accent
                )
            }

            MimikyuCoverType.DAILY_MIX -> {
                DailyMixCoverContent(
                    title = title,
                    artists = subtitle,
                    topColor = primaryColor,
                    bottomColor = secondaryColor
                )
            }

            MimikyuCoverType.TOP_CHARTS -> {
                TopChartsCoverContent(
                    title = title,
                    subtitle = subtitle,
                    accentColor = primaryColor
                )
            }

            MimikyuCoverType.MOSAIC_4 -> {
                if (mosaicImages.size >= 4) {
                    AppleMusicPlaylistCover(
                        title = title,
                        mosaicImages = mosaicImages.take(4),
                        forcedStyle = AppleCoverStyle.FOUR_UP_MOSAIC,
                        cornerRadius = cornerRadius,
                        modifier = Modifier.fillMaxSize()
                    )
                } else if (coverUrl.isNotBlank()) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(coverUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    AppleMusicPlaylistCover(
                        title = title,
                        subtitle = subtitle,
                        cornerRadius = cornerRadius,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            MimikyuCoverType.IMAGE_URL -> {
                if (coverUrl.isNotBlank()) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(coverUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    // Apple Music automated recipe cover for dynamic/empty playlists
                    AppleMusicPlaylistCover(
                        title = title,
                        subtitle = subtitle,
                        mosaicImages = mosaicImages,
                        cornerRadius = cornerRadius,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

@Composable
private fun DailyMixCoverContent(
    title: String,
    artists: String,
    topColor: Color,
    bottomColor: Color
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Top Split: Vibrant Color Block with Title
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.68f)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(topColor, topColor.copy(alpha = 0.85f))
                    )
                )
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            // Waveform indicator
            Icon(
                imageVector = Icons.Rounded.GraphicEq,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.5f),
                modifier = Modifier
                    .size(24.dp)
                    .align(Alignment.TopEnd)
            )

            Column(
                modifier = Modifier.align(Alignment.BottomStart)
            ) {
                Text(
                    text = "OCTOBER",
                    color = Color.White.copy(alpha = 0.65f),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = title.uppercase(),
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Black,
                    lineHeight = 20.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Bottom Split: Dark Contrast Bar with Artist names
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.32f)
                .background(bottomColor)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                text = if (artists.isNotBlank()) artists else "Personalized for you",
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 10.sp,
                lineHeight = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun TopChartsCoverContent(
    title: String,
    subtitle: String,
    accentColor: Color
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = listOf(accentColor, accentColor.copy(alpha = 0.7f), Color(0xFF121212))
                )
            )
            .padding(12.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "OCTOBER CHARTS",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Icon(
                    imageVector = Icons.Rounded.GraphicEq,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.size(18.dp)
                )
            }

            Column {
                Text(
                    text = title.uppercase(),
                    color = Color.White,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Black,
                    lineHeight = 22.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (subtitle.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = subtitle,
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun MosaicCoverContent(images: List<String>) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            MosaicTile(url = images[0], modifier = Modifier.weight(1f))
            Box(modifier = Modifier.size(1.dp).fillMaxHeight().background(Color.Black))
            MosaicTile(url = images[1], modifier = Modifier.weight(1f))
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color.Black))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            MosaicTile(url = images[2], modifier = Modifier.weight(1f))
            Box(modifier = Modifier.size(1.dp).fillMaxHeight().background(Color.Black))
            MosaicTile(url = images.getOrNull(3) ?: images[0], modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun MosaicTile(url: String, modifier: Modifier = Modifier) {
    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
            .data(url)
            .crossfade(true)
            .build(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.fillMaxSize()
    )
}

@Composable
private fun RadioCoverContent(
    artistName: String,
    centerCover: String,
    sideCovers: List<String>,
    backgroundColor: Color
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
            .padding(10.dp)
    ) {
        // Top Header: October icon + "RADIO" tag
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.GraphicEq,
                contentDescription = null,
                tint = Color(0xFF191414).copy(alpha = 0.7f),
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = "RADIO",
                color = Color(0xFF191414),
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.2.sp
            )
        }

        // Center Area: 3 circular artist cutouts
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .height(80.dp),
            contentAlignment = Alignment.Center
        ) {
            val leftImg = sideCovers.getOrNull(0) ?: centerCover
            val rightImg = sideCovers.getOrNull(1) ?: centerCover

            // Left Circle
            if (leftImg.isNotBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(leftImg)
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .offset(x = (-34).dp)
                        .size(44.dp)
                        .clip(CircleShape)
                )
            }

            // Right Circle
            if (rightImg.isNotBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(rightImg)
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .offset(x = 34.dp)
                        .size(44.dp)
                        .clip(CircleShape)
                )
            }

            // Center Circle (dominant)
            if (centerCover.isNotBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(centerCover)
                        .crossfade(true)
                        .build(),
                    contentDescription = artistName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(62.dp)
                        .clip(CircleShape)
                        .border(2.dp, backgroundColor, CircleShape)
                )
            }
        }

        // Bottom Artist Name
        Text(
            text = artistName,
            color = Color(0xFF121212),
            fontSize = 17.sp,
            fontWeight = FontWeight.Black,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart)
        )
    }
}

@Composable
private fun ThisIsCoverContent(
    artistName: String,
    artistCover: String,
    accentColor: Color
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Top White Bar: THIS IS
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.28f)
                .background(Color.White)
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.GraphicEq,
                    contentDescription = null,
                    tint = Color.Black.copy(alpha = 0.6f),
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = "THIS IS",
                    color = Color.Black,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.4.sp
                )
                Spacer(modifier = Modifier.size(14.dp))
            }
        }

        // Center Artist Portrait
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.48f)
                .background(Color(0xFF202020))
        ) {
            if (artistCover.isNotBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(artistCover)
                        .crossfade(true)
                        .build(),
                    contentDescription = artistName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // Bottom Accent Band with Artist Name
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.24f)
                .background(accentColor)
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = artistName,
                color = Color.Black,
                fontSize = 14.sp,
                fontWeight = FontWeight.Black,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun VibeMixCoverContent(
    mixTitle: String,
    backgroundCover: String,
    accentColor: Color
) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (backgroundCover.isNotBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(backgroundCover)
                    .crossfade(true)
                    .build(),
                contentDescription = mixTitle,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(accentColor.copy(alpha = 0.6f), Color(0xFF1E1E1E))
                        )
                    )
            )
        }

        // October indicator top left
        Icon(
            imageVector = Icons.Rounded.GraphicEq,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.7f),
            modifier = Modifier
                .padding(8.dp)
                .size(16.dp)
                .align(Alignment.TopStart)
        )

        // Floating Bottom Banner
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(horizontal = 8.dp, vertical = 8.dp)
                .height(24.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.White)
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(5.dp)
                        .background(accentColor)
                )
                Text(
                    text = mixTitle,
                    color = Color(0xFF121212),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 6.dp)
                )
            }
        }
    }
}
