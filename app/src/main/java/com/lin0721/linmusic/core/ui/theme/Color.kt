package com.lin0721.linmusic.core.ui.theme

import androidx.compose.ui.graphics.Color

// HSV ，/，。
// coerceIn ：/ ≤ 0.35，
// ，；"Darker"，
fun Color.lighten(amount: Float): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.RGBToHSV((red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt(), hsv)
    hsv[2] = (hsv[2] + (1f - hsv[2]) * amount).coerceIn(0f, 1f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

fun Color.darken(amount: Float): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.RGBToHSV((red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt(), hsv)
    hsv[2] = (hsv[2] * (1f - amount)).coerceIn(0f, 1f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

// HSV ； 0（/），，
// of
fun Color.saturate(amount: Float): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.RGBToHSV((red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt(), hsv)
    if (hsv[1] <= 0f) return this
    hsv[1] = (hsv[1] + (1f - hsv[1]) * amount).coerceIn(0f, 1f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

// base ： base  NEUTRAL_BASE_CHROMA  of ，；
// ， of
fun Color.saturateIfChromatic(amount: Float): Color {
    val chroma = maxOf(red, green, blue) - minOf(red, green, blue)
    val weight = ((chroma - NEUTRAL_BASE_CHROMA) / (MIN_BASE_CHROMA - NEUTRAL_BASE_CHROMA)).coerceIn(0f, 1f)
    return saturate(amount * weight)
}

/**
 * Transforms an album color into a soft pastel shade with the exact same tone and brightness as MimikyuPink.
 */
fun Color.toPastelProgressColor(): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.RGBToHSV(
        (red * 255f).toInt().coerceIn(0, 255),
        (green * 255f).toInt().coerceIn(0, 255),
        (blue * 255f).toInt().coerceIn(0, 255),
        hsv
    )
    val hue = if (hsv[1] < 0.05f) 354f else hsv[0]
    hsv[0] = hue
    hsv[1] = 0.42f
    hsv[2] = 0.98f
    return Color(android.graphics.Color.HSVToColor(hsv))
}

val BackgroundDark = Color(0xFF121213)
val SurfaceDark = Color(0xFF1F1F20)
val SurfaceLight = Color(0xFF2C2C2E)
val TextGray = Color(0xFFA5A5A8)

// Official Mimikyu "Black #121213 X Quartz Pink #FF9BA5 X Off White #F3F2F5" Color Palette
val MimikyuPink = Color(0xFFFF9BA5)
val MimikyuDarkPink = Color(0xFFE58791)
val MimikyuYellow = MimikyuPink
val MimikyuDarkYellow = MimikyuDarkPink
val MimikyuCharcoal = Color(0xFF121213)
val MimikyuDarkCharcoal = Color(0xFF0D0D0E)
val MimikyuMediumCharcoal = Color(0xFF1F1F20)
val MimikyuLightGray = Color(0xFFA5A5A8)
val MimikyuOffWhite = Color(0xFFF3F2F5)
val MimikyuSilver = MimikyuOffWhite
val MimikyuWhite = MimikyuOffWhite

// ExoPlayer Progress Bar Background: Stone White (Stone Gray #C6C6C6)
val MimikyuStoneWhite = Color(0xFFC6C6C6)
val ProgressTrackStoneWhite = MimikyuStoneWhite

// Core palette aliases mapped to Quartz Pink, Black & Off White
val MimikyuMagenta = MimikyuPink
val MimikyuDarkMagenta = MimikyuDarkPink
val MimikyuGreen = MimikyuPink
val MimikyuDarkGreen = MimikyuDarkPink
val MimikyuBlack = MimikyuCharcoal
val MimikyuDarkGray = MimikyuCharcoal
val MimikyuMediumGray = MimikyuMediumCharcoal

// Brand Color Palette (Synchronized with Mimikyu)
val OctoberRed = MimikyuPink
val OctoberCrimson = MimikyuDarkPink
val OctoberCharcoal = MimikyuCharcoal
val OctoberWhite = MimikyuOffWhite

val NeteaseRed = MimikyuPink
val GradientStart = MimikyuCharcoal
val BackgroundBlack = Color(0xFF121213)

// Category Navigation Pill Selected
val NavPillSelected = Color(0xFF2C2C2E)

// Filter Pill Inactive (Shade lighter #1F1F20)
val FilterPillInactive = Color(0xFF1F1F20)

// Toast
val ToastBackground = MimikyuMediumCharcoal

// Web Login Background
val WebLoginBackground = Color(0xFF121213)

// Fallback base
val FallbackBase = MimikyuCharcoal

// Playlists/Artists placeholder
val CoverPlaceholderDark = MimikyuMediumCharcoal

// Sleep Timer warning
val TimerWarningRed = Color(0xFFFF5252)

// Library gradient (Liked Songs Quartz Pink Gradient)
val LibraryVioletGradient = listOf(
    Color(0xFFFF9BA5),
    Color(0xFFE58791),
    Color(0xFFD07580)
)

// Library Blue Green gradient
val LibraryBlueGreenGradient = listOf(
    Color(0xFFFF9BA5),
    Color(0xFFE58791)
)

// Download
val DownloadedGreen = MimikyuYellow

// SVIP
val SvipGold = Color(0xFFD4A017)

// of 。Heartbeat Mode、，

val EntryDailyGradient = listOf(Color(0xFFE5484D), Color(0xFF8C1116))
val EntryHotGradient = listOf(Color(0xFFF08C1A), Color(0xFF9C4A05))
val EntryHeartGradient = listOf(Color(0xFFA33FC4), Color(0xFF4B1268))
val EntryRadarGradient = listOf(Color(0xFF2F7FD6), Color(0xFF123F70))
val EntryRoamingGradient = listOf(Color(0xFF3FAE5C), Color(0xFF175C2C))
