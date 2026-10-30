package com.lin0721.linmusic.feature.search.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.painterResource
import com.lin0721.linmusic.core.ui.theme.MimikyuIcons
import com.lin0721.linmusic.core.ui.theme.MimikyuSilver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.lin0721.linmusic.core.ui.components.MimikyuIconButton
import com.lin0721.linmusic.core.ui.components.MimikyuTextButton
import com.lin0721.linmusic.core.ui.interaction.pressable
import com.lin0721.linmusic.core.ui.theme.MimikyuPress
import com.lin0721.linmusic.core.ui.theme.MimikyuSpacing
import com.lin0721.linmusic.core.ui.theme.PillRadius

private const val BAR_ANIM_DURATION = 250

// SearchCollapse，SearchCancel
@Composable
internal fun SearchTopBar(
    mode: SearchMode,
    query: String,
    placeholder: String,
    avatarUrl: String?,
    focusRequester: FocusRequester,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onActivate: () -> Unit,
    onFieldFocused: () -> Unit,
    onCancel: () -> Unit,
    onOpenSidebar: () -> Unit,
    onAvatarClickLoggedOut: () -> Unit,
    placeholderInfo: com.lin0721.linmusic.feature.search.domain.SearchPlaceholderInfo? = null,
    onOpenSongIdentifier: () -> Unit = {}
) {
    val isDiscovery = mode == SearchMode.Discovery
    val barTween = tween<Float>(BAR_ANIM_DURATION, easing = FastOutSlowInEasing)

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MimikyuSpacing.md)
                .padding(top = MimikyuSpacing.sm, bottom = MimikyuSpacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!isDiscovery) {
                MimikyuIconButton(
                    onClick = onCancel,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        painter = painterResource(MimikyuIcons.ArrowLeft),
                        contentDescription = "Back",
                        tint = MimikyuSilver,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
            }

            SearchField(
                isDiscovery = isDiscovery,
                query = query,
                placeholder = "What do you want to listen to?",
                placeholderInfo = placeholderInfo,
                focusRequester = focusRequester,
                onQueryChange = onQueryChange,
                onSubmit = onSubmit,
                onActivate = onActivate,
                onFieldFocused = onFieldFocused,
                onOpenSongIdentifier = onOpenSongIdentifier,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun SearchField(
    isDiscovery: Boolean,
    query: String,
    placeholder: String,
    placeholderInfo: com.lin0721.linmusic.feature.search.domain.SearchPlaceholderInfo? = null,
    focusRequester: FocusRequester,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onActivate: () -> Unit,
    onFieldFocused: () -> Unit,
    onOpenSongIdentifier: () -> Unit,
    modifier: Modifier = Modifier
) {
    val containerColor = Color(0xFF242426)
    val hintColor = Color(0xFFA7A7A7)

    Row(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(containerColor)
            .then(
                if (isDiscovery) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onActivate
                    )
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isDiscovery) {
            if (placeholderInfo != null && placeholderInfo.imageUrl.isNotBlank()) {
                val shape = if (placeholderInfo.isArtist) CircleShape else RoundedCornerShape(4.dp)
                AsyncImage(
                    model = placeholderInfo.imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(24.dp)
                        .clip(shape)
                )
            } else {
                Icon(
                    painter = painterResource(MimikyuIcons.Search),
                    contentDescription = null,
                    tint = hintColor,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = placeholderInfo?.title?.takeIf { it.isNotBlank() } ?: placeholder,
                color = if (placeholderInfo != null) MimikyuSilver else hintColor,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            MimikyuIconButton(
                onClick = onOpenSongIdentifier,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    painter = painterResource(MimikyuIcons.Mic),
                    contentDescription = "Song Identifier",
                    tint = MimikyuSilver,
                    modifier = Modifier.size(20.dp)
                )
            }
        } else {
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp),
                cursorBrush = SolidColor(Color(0xFF1ED760)),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text(
                                text = placeholder,
                                color = hintColor,
                                fontSize = 15.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        inner()
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
                    .onFocusChanged { if (it.isFocused) onFieldFocused() }
            )
            AnimatedVisibility(
                visible = query.isNotEmpty(),
                enter = fadeIn(tween(150)),
                exit = fadeOut(tween(150))
            ) {
                MimikyuIconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        painter = painterResource(MimikyuIcons.Close),
                        contentDescription = "Clear search",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            AnimatedVisibility(
                visible = query.isEmpty(),
                enter = fadeIn(tween(150)),
                exit = fadeOut(tween(150))
            ) {
                MimikyuIconButton(onClick = onOpenSongIdentifier) {
                    Icon(
                        painter = painterResource(MimikyuIcons.Mic),
                        contentDescription = "Song Identifier",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
