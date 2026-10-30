package com.lin0721.linmusic.feature.home.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lin0721.linmusic.core.ui.theme.NeteaseRed
import com.lin0721.linmusic.core.ui.theme.RobotoFontFamily
import com.lin0721.linmusic.core.ui.theme.MimikyuSilver
import com.lin0721.linmusic.core.ui.theme.MimikyuCharcoal

@Composable
fun SectionHeader(title: String, showAction: Boolean = true) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 36.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )
        if (showAction) {
            Text(text = "Show All", color = Color.Gray, fontSize = 12.sp)
        }
    }
}

@Composable
fun LoadingIndicator(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(200.dp),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            color = NeteaseRed,
            strokeWidth = 3.dp,
            modifier = Modifier.size(36.dp)
        )
    }
}

@Composable
fun CleanErrorState(
    modifier: Modifier = Modifier,
    title: String = "Something went wrong",
    subtitle: String = "Please try again in a moment.",
    buttonText: String = "Refresh",
    onRefresh: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = title,
            color = MimikyuSilver,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = RobotoFontFamily,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = subtitle,
            color = MimikyuSilver.copy(alpha = 0.75f),
            fontSize = 14.sp,
            fontFamily = RobotoFontFamily,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(20.dp))
        Button(
            onClick = onRefresh,
            shape = RoundedCornerShape(percent = 50),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = Color.Black
            ),
            contentPadding = PaddingValues(horizontal = 32.dp, vertical = 12.dp)
        ) {
            Text(
                text = buttonText,
                color = Color.Black,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = RobotoFontFamily
            )
        }
    }
}

@Composable
fun SpotifyStyleErrorState(
    modifier: Modifier = Modifier,
    title: String = "Something went wrong",
    subtitle: String = "Please try again in a moment.",
    buttonText: String = "Refresh",
    onRefresh: () -> Unit
) {
    CleanErrorState(
        modifier = modifier,
        title = title,
        subtitle = subtitle,
        buttonText = buttonText,
        onRefresh = onRefresh
    )
}

@Composable
fun ErrorContent(
    message: String = "",
    onRetry: () -> Unit
) {
    CleanErrorState(onRefresh = onRetry)
}

