package com.lin0721.linmusic.feature.home.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding

import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import com.lin0721.linmusic.core.ui.theme.MimikyuIcons
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.lin0721.linmusic.core.auth.UserProfile
import com.lin0721.linmusic.core.ui.interaction.pressable
import com.lin0721.linmusic.core.ui.theme.FilterPillInactive
import com.lin0721.linmusic.core.ui.theme.MimikyuPress
import androidx.compose.ui.text.font.FontWeight
import com.lin0721.linmusic.core.ui.theme.MimikyuSpacing
import com.lin0721.linmusic.core.ui.theme.RobotoFontFamily
import com.lin0721.linmusic.core.ui.theme.PillRadius
import com.lin0721.linmusic.core.ui.theme.PressStyle

@Composable
fun HomeSharedHeader(
    userProfile: UserProfile? = null,
    selectedTab: Int = 0,
    onTabSelected: (Int) -> Unit = {},
    secondarySelected: Boolean = false,
    onSecondarySelected: () -> Unit = {},
    showFilterPills: Boolean = true,
    backgroundColor: Color = MaterialTheme.colorScheme.background,
    onAvatarClick: () -> Unit = {},
    onSearchClick: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(2f)
            .background(backgroundColor)
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 7.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showFilterPills) {
                FilterPills(
                    selectedIndex = selectedTab,
                    onSelected = onTabSelected,
                    secondarySelected = secondarySelected,
                    onSecondarySelected = onSecondarySelected,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp)
                )
            }
        }
    }
}



private val PillShapeFull = RoundedCornerShape(PillRadius)

// ：All / Liked Songs / Discover / Trending / Charts / Podcasts
@Composable
fun FilterPills(
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    secondarySelected: Boolean = false,
    onSecondarySelected: () -> Unit = {},
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp)
) {
    val labels = remember { listOf("All", "Discover", "Local Music") }

    LazyRow(
        modifier = modifier,
        contentPadding = contentPadding,
        verticalAlignment = Alignment.CenterVertically
    ) {
        itemsIndexed(labels, key = { _, label -> label }) { index, label ->
            FilterPillChip(
                text = label,
                selected = index == selectedIndex,
                pressStyle = MimikyuPress.Pill,
                onClick = { onSelected(index) },
                modifier = Modifier.padding(start = if (index == 0) 0.dp else 12.dp)
            )
        }
    }
}

// ，/
@Composable
private fun FilterPillChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    shape: Shape = PillShapeFull,
    pressStyle: PressStyle = MimikyuPress.Pill,
    animateColors: Boolean = true,
    activeColor: Color = MaterialTheme.colorScheme.primary,
    inactiveColor: Color = FilterPillInactive,
    modifier: Modifier = Modifier
) {
    val targetBackground = if (selected) activeColor else inactiveColor
    val targetContent = if (selected) Color.Black else com.lin0721.linmusic.core.ui.theme.MimikyuSilver
    val animatedBackground by animateColorAsState(targetValue = targetBackground, label = "pillBackground")
    val animatedContent by animateColorAsState(targetValue = targetContent, label = "pillContent")
    val backgroundColor = if (animateColors) animatedBackground else targetBackground
    val contentColor = if (animateColors) animatedContent else targetContent
    Box(
        modifier = modifier
            .height(32.dp)
            .pressable(pressStyle, onClick = onClick)
            .clip(shape)
            .background(backgroundColor)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = contentColor,
            fontSize = 12.5.sp,
            fontFamily = RobotoFontFamily,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
        )
    }
}
