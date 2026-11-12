package com.lin0721.linmusic.feature.search.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.lin0721.linmusic.core.ui.components.MimikyuIconButton
import com.lin0721.linmusic.core.ui.theme.MimikyuIcons
import com.lin0721.linmusic.core.ui.theme.MimikyuPink
import com.lin0721.linmusic.core.ui.theme.MimikyuSpacing

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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF212121)) // Dual tone top bar background (Spotify charcoal)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 14.dp, vertical = 8.dp),
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
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
            }

            SearchField(
                isDiscovery = isDiscovery,
                query = query,
                placeholder = "What do you want to play?",
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

        HorizontalDivider(
            thickness = 0.5.dp,
            color = Color.Black.copy(alpha = 0.45f)
        )
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
    val containerColor = Color(0xFF333333) // Spotify search field background
    val hintColor = Color(0xFFB3B3B3)

    Row(
        modifier = modifier
            .height(38.dp) // Sleek and thin
            .clip(CircleShape) // Rounded pill shape
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
                        .size(22.dp)
                        .clip(shape)
                )
            } else {
                Icon(
                    painter = painterResource(MimikyuIcons.Search),
                    contentDescription = null,
                    tint = hintColor,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = placeholderInfo?.title?.takeIf { it.isNotBlank() } ?: placeholder,
                color = if (placeholderInfo != null) Color.White else hintColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            MimikyuIconButton(
                onClick = onOpenSongIdentifier,
                modifier = Modifier.size(30.dp)
            ) {
                Icon(
                    painter = painterResource(MimikyuIcons.Mic),
                    contentDescription = "Song Identifier",
                    tint = hintColor,
                    modifier = Modifier.size(18.dp)
                )
            }
        } else {
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Normal
                ),
                cursorBrush = SolidColor(MimikyuPink),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text(
                                text = placeholder,
                                color = hintColor,
                                fontSize = 14.sp,
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

            // Spotify circular filled cross icon
            AnimatedVisibility(
                visible = query.isNotEmpty(),
                enter = fadeIn(tween(150)),
                exit = fadeOut(tween(150))
            ) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF757575))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { onQueryChange("") }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Clear search",
                        tint = Color(0xFF212121),
                        modifier = Modifier.size(12.dp)
                    )
                }
            }

            AnimatedVisibility(
                visible = query.isEmpty(),
                enter = fadeIn(tween(150)),
                exit = fadeOut(tween(150))
            ) {
                MimikyuIconButton(
                    onClick = onOpenSongIdentifier,
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        painter = painterResource(MimikyuIcons.Mic),
                        contentDescription = "Song Identifier",
                        tint = hintColor,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
