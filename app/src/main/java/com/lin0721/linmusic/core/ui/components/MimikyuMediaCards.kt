package com.lin0721.linmusic.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.lin0721.linmusic.core.ui.interaction.pressScale
import com.lin0721.linmusic.core.ui.theme.DownloadedGreen
import com.lin0721.linmusic.core.ui.theme.MimikyuIcons
import com.lin0721.linmusic.core.ui.theme.MimikyuPink
import com.lin0721.linmusic.core.ui.theme.MimikyuPress
import com.lin0721.linmusic.core.ui.theme.MimikyuSilver
import com.lin0721.linmusic.core.ui.theme.RobotoFontFamily
import com.lin0721.linmusic.core.ui.theme.SurfaceDark
import com.lin0721.linmusic.core.ui.theme.LikedSongsAssets
import com.lin0721.linmusic.R

/**
 * Standard Stacked Card / Vinyl Sleeve Tab peaking out from the top of the card.
 * Replicates the tactile stacked card physical wireframe from Mimikyu redesign.
 */
@Composable
fun MimikyuStackedSleeve(
    color: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth(0.92f)
            .height(14.dp)
            .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
            .background(color)
    )
}

/**
 * Circular dark badge with bright Mimikyu green pushpin icon in the top-right corner.
 */
@Composable
fun MimikyuPinnedBadge(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(Color(0xFF141414).copy(alpha = 0.94f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(MimikyuIcons.Pin),
            contentDescription = "Pinned",
            tint = Color(0xFF1DB954),
            modifier = Modifier.size(15.dp)
        )
    }
}

/**
 * Mimikyu Wireframe Playlist Card.
 * Matches:
 * 1. "Liked Songs" (Green cover + glowing green heart outline + pin badge + count on right)
 * 2. "Daily Mix" (Artist background + organic wavy bottom banner + "Daily Mix N" + count on right)
 * 3. "Genre/Mood Mix" (Rock Mix, Chill Mix, Pop Mix with left vertical accent bar + title + count)
 * 4. "Standard Playlist" (Dark charcoal cover + clean gray music note + count on right)
 * 5. "Folder" (Dark charcoal cover + folder outline icon + count on right)
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MimikyuWireframePlaylistCard(
    title: String,
    subtitle: String,
    trackCount: Int? = null,
    trackCountColor: Color? = null,
    coverUrl: String = "",
    isLikedSongs: Boolean = false,
    isDownloadedSongs: Boolean = false,
    isDailyMix: Boolean = false,
    dailyMixNumber: Int = 1,
    isGenreMix: Boolean = false,
    accentColor: Color? = null,
    isFolder: Boolean = false,
    isPinned: Boolean = false,
    cardWidth: Dp = Dp.Unspecified,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }

    // Palette & sleeve colors
    val (primaryAccent, sleeveColor) = remember(title, isLikedSongs, isDownloadedSongs, isDailyMix, dailyMixNumber, isGenreMix, accentColor) {
        when {
            isLikedSongs -> Color(0xFFFF9BA5) to Color(0xFF1F1F20)
            isDownloadedSongs -> Color(0xFF9E8BFF) to Color(0xFF1F1F20)
            isDailyMix -> {
                when (dailyMixNumber % 6) {
                    1 -> Color(0xFF7FE4C6) to Color(0xFF234C43) // Mint / Teal
                    2 -> Color(0xFFFFA29E) to Color(0xFF56282D) // Coral / Peach
                    3 -> Color(0xFFB4E6C4) to Color(0xFF334B3C) // Sage Green
                    4 -> Color(0xFFB490E0) to Color(0xFF3E2D55) // Lavender
                    5 -> Color(0xFFFFEE58) to Color(0xFF4C471E) // Yellow
                    else -> Color(0xFF4DD0E1) to Color(0xFF1D4045) // Aqua
                }
            }
            isGenreMix -> {
                val acc = accentColor ?: when {
                    title.contains("Rock", ignoreCase = true) -> Color(0xFFC4E865)
                    title.contains("Chill", ignoreCase = true) -> Color(0xFFFFEE58)
                    title.contains("Pop", ignoreCase = true) -> Color(0xFF4DD0E1)
                    else -> Color(0xFF1DB954)
                }
                val sleeve = when {
                    title.contains("Rock", ignoreCase = true) -> Color(0xFF263558)
                    title.contains("Chill", ignoreCase = true) -> Color(0xFF4C471E)
                    title.contains("Pop", ignoreCase = true) -> Color(0xFF1D4045)
                    else -> Color(0xFF1F1F20)
                }
                acc to sleeve
            }
            else -> (accentColor ?: Color(0xFFFF9BA5)) to Color(0xFF1F1F20)
        }
    }

    val countDisplayColor = trackCountColor ?: when {
        isLikedSongs -> MimikyuPink
        isDownloadedSongs -> Color(0xFFB490E0)
        isDailyMix || isGenreMix -> primaryAccent
        else -> Color(0xFF888888)
    }

    val widthMod = if (cardWidth != Dp.Unspecified) Modifier.width(cardWidth) else Modifier.fillMaxWidth()

    Column(
        modifier = modifier
            .then(widthMod)
            .pressScale(MimikyuPress.Card, interactionSource)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        // ── Stacked Cover Box (Top sleeve tab + Main 1:1 cover) ──
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.TopCenter
        ) {
            // 1. Stacked sleeve tab sticking out 6dp at the top
            MimikyuStackedSleeve(
                color = sleeveColor,
                modifier = Modifier.align(Alignment.TopCenter)
            )

            // 2. Main 1:1 Cover Box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF18232C))
            ) {
                when {
                    // Case 0: Downloaded songs
                    isDownloadedSongs -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xFF1F1F20)),
                            contentAlignment = Alignment.Center
                        ) {
                            AsyncImage(
                                model = ImageRequest.Builder(LocalContext.current)
                                    .data(R.drawable.downloaded_songs_cover)
                                    .error(R.drawable.downloaded_songs_cover)
                                    .fallback(R.drawable.downloaded_songs_cover)
                                    .crossfade(true)
                                    .build(),
                                contentDescription = "Downloaded Songs",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }

                    // Case 1: Liked songs
                    isLikedSongs -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xFF1F1F20)),
                            contentAlignment = Alignment.Center
                        ) {
                            AsyncImage(
                                model = ImageRequest.Builder(LocalContext.current)
                                    .data(LikedSongsAssets.COVER_URL)
                                    .error(R.drawable.liked_songs_cover)
                                    .fallback(R.drawable.liked_songs_cover)
                                    .crossfade(true)
                                    .build(),
                                contentDescription = "Liked Songs",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }

                    // Case 2: Daily Mix (Cover + Wavy Pastel Banner + Mimikyu Logo)
                    isDailyMix -> {
                        // Background image / artwork
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
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.verticalGradient(
                                            listOf(sleeveColor, Color(0xFF181818))
                                        )
                                    )
                            )
                        }

                        // Mimikyu Logo top-left
                        Icon(
                            painter = painterResource(MimikyuIcons.Logo),
                            contentDescription = "Mimikyu",
                            tint = MimikyuSilver,
                            modifier = Modifier
                                .padding(8.dp)
                                .size(16.dp)
                                .align(Alignment.TopStart)
                        )

                        // Bottom organic wave banner
                        DailyMixWavyBottomBanner(
                            mixTitle = title.ifBlank { "Daily Mix $dailyMixNumber" },
                            waveColor = primaryAccent,
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.BottomCenter)
                        )
                    }

                    // Case 3: Genre / Mood Mix (Cover + Left Accent Bar + Title)
                    isGenreMix -> {
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
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.verticalGradient(
                                            listOf(sleeveColor, Color(0xFF181818))
                                        )
                                    )
                            )
                        }

                        // Mimikyu Logo top-left
                        Icon(
                            painter = painterResource(MimikyuIcons.Logo),
                            contentDescription = "Mimikyu",
                            tint = MimikyuSilver,
                            modifier = Modifier
                                .padding(8.dp)
                                .size(16.dp)
                                .align(Alignment.TopStart)
                        )

                        // Dark gradient scrim behind bottom title
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .align(Alignment.BottomCenter)
                                .background(
                                    Brush.verticalGradient(
                                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.82f))
                                    )
                                )
                        )

                        // Bottom left accent bar + title
                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(horizontal = 10.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                .width(4.dp)
                                .height(15.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(primaryAccent)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = title,
                                color = MimikyuSilver,
                                fontSize = 13.5.sp,
                                fontFamily = RobotoFontFamily,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    // Case 4: Folder Card
                    isFolder -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(SurfaceDark),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(MimikyuIcons.Folder),
                                contentDescription = "Folder",
                                tint = MimikyuPink,
                                modifier = Modifier.size(46.dp)
                            )
                        }
                    }

                    // Case 5: Standard Playlist (Image or Music Note placeholder)
                    else -> {
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
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(SurfaceDark),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painter = painterResource(MimikyuIcons.MusicNote),
                                    contentDescription = "Playlist",
                                    tint = Color(0xFF8E8E93),
                                    modifier = Modifier.size(46.dp)
                                )
                            }
                        }
                    }
                }

                // Pinned badge in top-right
                if (isPinned) {
                    MimikyuPinnedBadge(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                    )
                }
            }
        }

        // ── Row 1: Title (left) & Track count (right) ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                color = MimikyuSilver,
                fontSize = 14.sp,
                fontFamily = RobotoFontFamily,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            if (trackCount != null) {
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "$trackCount",
                    color = countDisplayColor,
                    fontSize = 13.sp,
                    fontFamily = RobotoFontFamily,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // ── Row 2: Subtitle / Artists (Muted gray, max 2 lines) ──
        if (subtitle.isNotBlank()) {
            Text(
                text = subtitle,
                color = Color(0xFF888888),
                fontSize = 12.sp,
                fontFamily = RobotoFontFamily,
                fontWeight = FontWeight.Normal,
                maxLines = 2,
                lineHeight = 15.sp,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

/**
 * Organic wavy pastel banner at the bottom of Daily Mix cards (matches Image 1).
 */
@Composable
private fun DailyMixWavyBottomBanner(
    mixTitle: String,
    waveColor: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.height(44.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val path = Path().apply {
                moveTo(0f, h * 0.35f)
                cubicTo(
                    w * 0.22f, -h * 0.1f,
                    w * 0.42f, h * 0.65f,
                    w * 0.72f, h * 0.35f
                )
                quadraticTo(
                    w * 0.88f, h * 0.18f,
                    w, h * 0.42f
                )
                lineTo(w, h)
                lineTo(0f, h)
                close()
            }
            drawPath(path, color = waveColor)
        }

        Text(
            text = mixTitle,
            color = Color(0xFF121212),
            fontSize = 13.5.sp,
            fontFamily = RobotoFontFamily,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(bottom = 7.dp, start = 8.dp, end = 8.dp)
        )
    }
}

/**
 * Mimikyu Wireframe Album Card (matches Image 3 & Image 5).
 * Features:
 * - Stacked sleeve at top.
 * - Square artwork with 8dp rounded corners.
 * - Optional pinned badge in top-right.
 * - Row 1: Album name on left, optional track count on right.
 * - Row 2: Artist name in muted gray.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MimikyuWireframeAlbumCard(
    title: String,
    artistName: String,
    coverUrl: String,
    trackCount: Int? = null,
    isPinned: Boolean = false,
    cardWidth: Dp = Dp.Unspecified,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val widthMod = if (cardWidth != Dp.Unspecified) Modifier.width(cardWidth) else Modifier.fillMaxWidth()

    Column(
        modifier = modifier
            .then(widthMod)
            .pressScale(MimikyuPress.Card, interactionSource)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        // Stacked sleeve at top
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.TopCenter
        ) {
            MimikyuStackedSleeve(
                color = Color(0xFF282828),
                modifier = Modifier.align(Alignment.TopCenter)
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF282828))
            ) {
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
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF333333)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(MimikyuIcons.Album),
                            contentDescription = "Album",
                            tint = Color(0xFF8E8E93),
                            modifier = Modifier.size(46.dp)
                        )
                    }
                }

                if (isPinned) {
                    MimikyuPinnedBadge(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                    )
                }
            }
        }

        // Row 1: Album Title & Track count
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                color = MimikyuSilver,
                fontSize = 14.sp,
                fontFamily = RobotoFontFamily,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            if (trackCount != null) {
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "$trackCount",
                    color = Color(0xFF888888),
                    fontSize = 13.sp,
                    fontFamily = RobotoFontFamily,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // Row 2: Artist Name
        if (artistName.isNotBlank()) {
            Text(
                text = artistName,
                color = Color(0xFF888888),
                fontSize = 12.sp,
                fontFamily = RobotoFontFamily,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

/**
 * Mimikyu Wireframe Artist Card (matches Image 4 & Image 5).
 * Features:
 * - Perfect circular image.
 * - Optional pinned badge at top-right edge.
 * - Centered artist name below (no subtitle, clean minimalist aesthetic).
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MimikyuWireframeArtistCard(
    name: String,
    avatarUrl: String,
    isPinned: Boolean = false,
    circleSize: Dp = Dp.Unspecified,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val widthMod = if (circleSize != Dp.Unspecified) Modifier.width(circleSize) else Modifier.fillMaxWidth()

    Column(
        modifier = modifier
            .then(widthMod)
            .pressScale(MimikyuPress.Card, interactionSource)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Circle Avatar with optional Pinned Badge
        val circleBoxMod = if (circleSize != Dp.Unspecified) {
            Modifier.size(circleSize)
        } else {
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
        }

        Box(
            modifier = circleBoxMod,
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(SurfaceDark),
                contentAlignment = Alignment.Center
            ) {
                if (avatarUrl.isNotBlank()) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(avatarUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    val fontSize = if (circleSize != Dp.Unspecified) (circleSize.value * 0.28f).sp else 32.sp
                    Text(
                        text = name.firstOrNull()?.uppercase() ?: "?",
                        color = MimikyuSilver.copy(alpha = 0.7f),
                        fontSize = fontSize,
                        fontFamily = RobotoFontFamily,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (isPinned) {
                MimikyuPinnedBadge(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = (-4).dp, y = 4.dp)
                )
            }
        }

        // Centered Artist Name
        Text(
            text = name,
            color = MimikyuSilver,
            fontSize = 14.sp,
            fontFamily = RobotoFontFamily,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
        )
    }
}
