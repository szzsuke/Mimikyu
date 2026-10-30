package com.lin0721.linmusic.feature.account.ui

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.lin0721.linmusic.core.auth.UserPreferences
import com.lin0721.linmusic.core.auth.UserProfile
import com.lin0721.linmusic.core.friend.FollowEntity
import com.lin0721.linmusic.core.friend.FriendManager
import com.lin0721.linmusic.core.friend.MutualFriendActivity
import com.lin0721.linmusic.core.ui.components.LoginBottomSheet
import com.lin0721.linmusic.core.ui.components.ToastManager
import com.lin0721.linmusic.core.ui.components.WebViewLoginScreen
import com.lin0721.linmusic.core.ui.interaction.pressable
import com.lin0721.linmusic.core.ui.theme.BackgroundDark
import com.lin0721.linmusic.core.ui.theme.SurfaceDark
import com.lin0721.linmusic.core.ui.theme.SurfaceLight
import com.lin0721.linmusic.core.ui.theme.MimikyuYellow
import com.lin0721.linmusic.core.ui.theme.MimikyuCharcoal
import com.lin0721.linmusic.core.ui.theme.MimikyuSilver
import com.lin0721.linmusic.core.ui.theme.MimikyuPress
import com.lin0721.linmusic.core.ui.theme.MimikyuIcons
import com.lin0721.linmusic.core.ui.theme.ScreenSlideDurationMs
import com.lin0721.linmusic.feature.home.ui.HomeViewModel
import com.lin0721.linmusic.feature.profile.ui.FollowListMode
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(
    onBack: () -> Unit = {},
    onNavigateToFriendsActivity: () -> Unit = {},
    onNavigateToProfile: (Long) -> Unit = {},
    onNavigateToRecentPlay: () -> Unit = {},
    onNavigateToListenData: () -> Unit = {},
    onNavigateToMessage: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onNavigateToFollowList: (Long, FollowListMode) -> Unit = { _, _ -> },
    onNavigateToEditProfile: () -> Unit = {},
    onImportPlaylist: () -> Unit = {},
    onLoginClick: () -> Unit = {},
    onLogout: () -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val userPreferences: UserPreferences = koinInject()
    val friendManager: FriendManager = koinInject()
    val homeViewModel: HomeViewModel = koinViewModel()

    val userProfile: UserProfile? by userPreferences.userProfile.collectAsStateWithLifecycle(initialValue = null)
    val isUserLoggedIn by userPreferences.isLoggedIn.collectAsStateWithLifecycle(initialValue = false)
    val followersCount by friendManager.observeFollowersCount().collectAsStateWithLifecycle(initialValue = 0)
    val followingCount by friendManager.observeFollowingCount().collectAsStateWithLifecycle(initialValue = 0)
    val mutualActivities by friendManager.getMutualFriendsActivityFlow().collectAsStateWithLifecycle(initialValue = emptyList())

    val isLoggedIn = isUserLoggedIn && userProfile != null && (userProfile?.uid ?: 0L) > 0L

    var activeSocialSheet by remember { mutableStateOf<SocialSheetType?>(null) }
    var showLoginSheet by remember { mutableStateOf(false) }
    var showWebViewLogin by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = BackgroundDark
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .statusBarsPadding()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 100.dp)
        ) {
            // ─────────────────────────────────────────────────────────────
            // 1. User Profile Card
            // ─────────────────────────────────────────────────────────────
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = SurfaceDark,
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f))
                ) {
                    if (isLoggedIn) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(18.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val uid = userProfile?.uid?.hashCode()?.toLong() ?: 0L
                                        onNavigateToProfile(uid)
                                    },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Circular Profile Picture
                                if (!userProfile?.avatarUrl.isNullOrEmpty()) {
                                    AsyncImage(
                                        model = userProfile?.avatarUrl,
                                        contentDescription = "Avatar",
                                        modifier = Modifier
                                            .size(70.dp)
                                            .clip(CircleShape),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(70.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFF282828)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Person,
                                            contentDescription = null,
                                            tint = MimikyuSilver,
                                            modifier = Modifier.size(38.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(16.dp))

                                // Name & Username Column
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = userProfile?.nickname.orEmpty(),
                                        color = MimikyuSilver,
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )

                                    val usernameHandle = userProfile?.username?.ifBlank {
                                        userProfile?.nickname?.lowercase()?.replace(" ", "_")
                                    } ?: ""
                                    if (usernameHandle.isNotBlank()) {
                                        Text(
                                            text = "@$usernameHandle",
                                            color = MimikyuYellow,
                                            fontSize = 13.5.sp,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                // Action Icons: Pencil (Edit) & Share
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconButton(onClick = onNavigateToEditProfile) {
                                        Icon(
                                            imageVector = Icons.Default.Edit,
                                            contentDescription = "Edit Profile",
                                            tint = Color.White,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }

                                    IconButton(onClick = {
                                        val currentUsername = userProfile?.username?.ifBlank {
                                            userProfile?.nickname?.lowercase()?.replace(" ", "_")
                                        } ?: "user"
                                        val shareLink = "https://mimikyu.app/u/$currentUsername?uid=${userProfile?.uid ?: ""}"
                                        val shareText = "Add me on Mimikyu! Listen together: $shareLink"
                                        val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_TEXT, shareText)
                                        }
                                        context.startActivity(Intent.createChooser(sendIntent, "Share Profile"))
                                    }) {
                                        Icon(
                                            imageVector = Icons.Default.Share,
                                            contentDescription = "Share Profile",
                                            tint = MimikyuSilver,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            // Followers & Following Clickable Stat Columns
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(SurfaceLight, RoundedCornerShape(12.dp))
                                    .padding(vertical = 10.dp, horizontal = 16.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    modifier = Modifier
                                        .clickable { activeSocialSheet = SocialSheetType.FOLLOWERS }
                                        .padding(horizontal = 16.dp, vertical = 4.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = "$followersCount",
                                        color = MimikyuSilver,
                                        fontSize = 17.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Followers",
                                        color = Color(0xFFB3B3B3),
                                        fontSize = 12.sp
                                    )
                                }

                                Box(
                                    modifier = Modifier
                                        .height(24.dp)
                                        .width(1.dp)
                                        .background(Color.White.copy(alpha = 0.12f))
                                )

                                Column(
                                    modifier = Modifier
                                        .clickable { activeSocialSheet = SocialSheetType.FOLLOWING }
                                        .padding(horizontal = 16.dp, vertical = 4.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = "$followingCount",
                                        color = MimikyuSilver,
                                        fontSize = 17.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Following",
                                        color = Color(0xFFB3B3B3),
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    } else {
                        // Logged-out state: Clean Login / Sign Up Card
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(68.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF262626)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Person,
                                    contentDescription = null,
                                    tint = Color(0xFFB3B3B3),
                                    modifier = Modifier.size(38.dp)
                                )
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            Text(
                                text = "Sign in to Mimikyu",
                                color = MimikyuSilver,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            Text(
                                text = "Log in or create an account to sync your playlists, favorites, and music stats across devices.",
                                color = Color(0xFFA0A0A0),
                                fontSize = 13.5.sp,
                                textAlign = TextAlign.Center,
                                lineHeight = 19.sp
                            )

                            Spacer(modifier = Modifier.height(20.dp))

                            Button(
                                onClick = {
                                    showLoginSheet = true
                                    onLoginClick()
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MimikyuYellow,
                                    contentColor = MimikyuCharcoal
                                ),
                                shape = RoundedCornerShape(24.dp)
                            ) {
                                Text(
                                    text = "Log In or Sign Up",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MimikyuCharcoal
                                )
                            }
                        }
                    }
                }
            }



            // ─────────────────────────────────────────────────────────────
            // 3. Navigation Shortcuts
            // ─────────────────────────────────────────────────────────────
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = SurfaceDark,
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                    ) {
                        AccountNavigationRow(
                            iconRes = MimikyuIcons.Time,
                            title = "Listening History",
                            onClick = onNavigateToListenData
                        )
                        AccountNavigationRow(
                            iconRes = MimikyuIcons.RecentlyPlayed,
                            title = "Recently Played",
                            onClick = onNavigateToRecentPlay
                        )
                        AccountNavigationRow(
                            iconRes = MimikyuIcons.Folder,
                            title = "Import Playlist",
                            onClick = onImportPlaylist
                        )
                        AccountNavigationRow(
                            iconRes = MimikyuIcons.Settings,
                            title = "Settings & Privacy",
                            onClick = onNavigateToSettings
                        )
                    }
                }
            }

            // ─────────────────────────────────────────────────────────────
            // 4. Sign Out Button
            // ─────────────────────────────────────────────────────────────
            if (isLoggedIn) {
                item {
                    Spacer(modifier = Modifier.height(4.dp))
                    Button(
                        onClick = onLogout,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MimikyuYellow,
                            contentColor = MimikyuCharcoal
                        ),
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Logout,
                            contentDescription = "Sign Out",
                            tint = MimikyuCharcoal,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Sign Out",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = MimikyuCharcoal
                        )
                    }
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Followers / Following Modal BottomSheet
    // ─────────────────────────────────────────────────────────────
    activeSocialSheet?.let { sheetType ->
        SocialFollowersSheet(
            sheetType = sheetType,
            friendManager = friendManager,
            onDismiss = { activeSocialSheet = null }
        )
    }

    // ─────────────────────────────────────────────────────────────
    // Authentication / Login Flow
    // ─────────────────────────────────────────────────────────────
    if (showLoginSheet) {
        LoginBottomSheet(
            onDismiss = { showLoginSheet = false },
            onWebLogin = {
                showLoginSheet = false
                showWebViewLogin = true
            },
            onLoginSuccess = { cookies ->
                showLoginSheet = false
                homeViewModel.handleLoginSuccess(cookies)
            }
        )
    }

    AnimatedVisibility(
        visible = showWebViewLogin,
        enter = slideInVertically(tween(ScreenSlideDurationMs)) { it } + fadeIn(tween(ScreenSlideDurationMs)),
        exit = slideOutVertically(tween(ScreenSlideDurationMs)) { it } + fadeOut(tween(ScreenSlideDurationMs))
    ) {
        WebViewLoginScreen(
            onClose = { showWebViewLogin = false },
            onLoginSuccess = { cookies ->
                showWebViewLogin = false
                homeViewModel.handleLoginSuccess(cookies)
            }
        )
    }
}

private enum class SocialSheetType {
    FOLLOWERS,
    FOLLOWING
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SocialFollowersSheet(
    sheetType: SocialSheetType,
    friendManager: FriendManager,
    onDismiss: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val followersList by friendManager.getFollowersList().collectAsStateWithLifecycle(initialValue = emptyList())
    val followingList by friendManager.getFollowingList().collectAsStateWithLifecycle(initialValue = emptyList())

    var followingFilter by remember { mutableStateOf("All") } // "All", "Artists", "Friends"

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SurfaceDark,
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
        ) {
            // Header Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (sheetType == SocialSheetType.FOLLOWERS) "Followers" else "Following",
                    color = MimikyuSilver,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onDismiss) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "Close", tint = MimikyuSilver)
                }
            }

            // Filter Chips for Following List
            if (sheetType == SocialSheetType.FOLLOWING) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("All", "Artists").forEach { filter ->
                        val selected = followingFilter == filter
                        FilterChip(
                            selected = selected,
                            onClick = { followingFilter = filter },
                            label = { Text(filter) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MimikyuYellow,
                                selectedLabelColor = MimikyuCharcoal,
                                containerColor = Color(0xFF18232C),
                                labelColor = Color.White
                            ),
                            shape = RoundedCornerShape(20.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            val itemsToDisplay: List<FollowEntity> = if (sheetType == SocialSheetType.FOLLOWERS) {
                followersList
            } else {
                when (followingFilter) {
                    "Artists" -> followingList.filter { it.type == "artist" }
                    "Friends" -> followingList.filter { it.type == "user" }
                    else -> followingList
                }
            }

            if (itemsToDisplay.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (sheetType == SocialSheetType.FOLLOWERS) "No followers yet" else "No $followingFilter followed yet",
                        color = Color(0xFF888888),
                        fontSize = 14.sp
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)
                ) {
                    items(itemsToDisplay, key = { it.id }) { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (item.avatarUrl.isNotBlank()) {
                                AsyncImage(
                                    model = item.avatarUrl,
                                    contentDescription = item.displayName,
                                    modifier = Modifier
                                        .size(46.dp)
                                        .clip(CircleShape),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(46.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF333333)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Person,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(14.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.displayName.ifBlank { item.username },
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val badgeText = if (item.type == "artist") "Artist" else if (item.username.isNotBlank()) "@${item.username}" else "Friend"
                                Text(
                                    text = badgeText,
                                    color = if (item.type == "artist") MimikyuYellow else Color(0xFF888888),
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            if (sheetType == SocialSheetType.FOLLOWING) {
                                OutlinedButton(
                                    onClick = {
                                        coroutineScope.launch {
                                            friendManager.unfollowTarget(item.id, isArtist = item.type == "artist")
                                        }
                                    },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFB3B3B3)),
                                    border = BorderStroke(1.dp, Color(0xFF555555)),
                                    shape = RoundedCornerShape(16.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
                                ) {
                                    Text("Unfollow", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountNavigationRow(
    iconRes: Int,
    title: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressable(MimikyuPress.Row) { onClick() }
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = Color(0xFFCCCCCC),
            modifier = Modifier.size(20.dp)
        )

        Spacer(modifier = Modifier.width(16.dp))

        Text(
            text = title,
            color = MimikyuSilver,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )

        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
            contentDescription = null,
            tint = Color(0xFF666666),
            modifier = Modifier.size(13.dp)
        )
    }
}
