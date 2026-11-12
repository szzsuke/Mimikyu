package com.lin0721.linmusic.feature.player.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lin0721.linmusic.core.player.automix.AutomixStatus

@Composable
fun AutomixMixingBadge(
    status: AutomixStatus,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "automix_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween<Float>(750, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    val dotColor = when {
        status.isMixing -> Color(0xFF1ED760) // Spotify bright green
        status.isArmed -> Color(0xFF00E5FF)  // Electric Cyan
        status.isAnalyzing -> Color(0xFFFFB300) // Amber
        else -> Color(0xFF1ED760)
    }

    val borderColor = when {
        status.isMixing -> Color(0xFF1ED760).copy(alpha = 0.5f)
        status.isArmed -> Color(0xFF00E5FF).copy(alpha = 0.4f)
        else -> Color(0x33FFFFFF)
    }

    val text = if (status.displayText.isNotBlank()) {
        status.displayText
    } else if (status.isMixing) {
        "MIXING"
    } else if (status.isAnalyzing) {
        "AUTOMIX \u2022 ANALYZING..."
    } else {
        "AUTOMIX"
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(CircleShape)
            .background(Color(0xD9121212))
            .border(1.dp, borderColor, CircleShape)
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(dotColor.copy(alpha = pulseAlpha))
        )
        Spacer(modifier = Modifier.width(7.dp))
        Text(
            text = text,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp
        )
    }
}
