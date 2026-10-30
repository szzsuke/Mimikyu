package com.lin0721.linmusic.core.ui.theme

import androidx.compose.foundation.LocalIndication
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import com.lin0721.linmusic.core.ui.interaction.MimikyuPressIndication

private val DarkColorScheme = darkColorScheme(
    primary = MimikyuYellow,
    onPrimary = MimikyuCharcoal,
    background = BackgroundDark,
    onBackground = MimikyuWhite,
    surface = MimikyuCharcoal,
    onSurface = MimikyuWhite,
    surfaceVariant = SurfaceLight,
    onSurfaceVariant = TextGray,
    error = Color(0xFFE91429),
    onError = MimikyuSilver
)

// Mimikyu  of  Material3
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MimikyuTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        shapes = MimikyuShapes
    ) {
        CompositionLocalProvider(
            LocalIndication provides MimikyuPressIndication.Default,
            LocalRippleConfiguration provides null,
            content = content
        )
    }
}