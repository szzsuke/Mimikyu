package com.lin0721.linmusic.feature.account.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.lin0721.linmusic.core.auth.UserPreferences
import com.lin0721.linmusic.core.auth.UserProfile
import com.lin0721.linmusic.core.friend.FriendManager
import com.lin0721.linmusic.core.ui.components.ToastManager
import com.lin0721.linmusic.core.ui.theme.BackgroundDark
import com.lin0721.linmusic.core.ui.theme.MimikyuYellow
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@OptIn(FlowPreview::class)
@Composable
fun EditProfileScreen(
    onBack: () -> Unit,
    friendManager: FriendManager = koinInject(),
    userPreferences: UserPreferences = koinInject()
) {
    val coroutineScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val currentProfile by userPreferences.userProfile.collectAsStateWithLifecycle(initialValue = null)

    var displayName by remember(currentProfile?.nickname) {
        mutableStateOf(currentProfile?.nickname.orEmpty())
    }
    var username by remember(currentProfile?.username) {
        mutableStateOf(currentProfile?.username.orEmpty())
    }
    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isUsernameAvailable by remember { mutableStateOf(true) }
    var isCheckingUsername by remember { mutableStateOf(false) }

    val isGoogleUser = remember {
        val user = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
        user?.providerData?.any { it.providerId == "google.com" } == true
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null && isGoogleUser) {
            selectedImageUri = uri
        }
    }

    // Debounced username availability check
    LaunchedEffect(username) {
        val trimmed = username.trim().lowercase()
        val currentUsername = currentProfile?.username.orEmpty().lowercase()
        if (trimmed.length < 3 || trimmed == currentUsername) {
            isUsernameAvailable = true
            isCheckingUsername = false
            return@LaunchedEffect
        }
        isCheckingUsername = true
        kotlinx.coroutines.delay(400) // Debounce
        friendManager.isUsernameAvailable(trimmed).collect { available ->
            isUsernameAvailable = available
            isCheckingUsername = false
        }
    }

    val usernameRegex = Regex("^[a-zA-Z0-9_.]+$")
    val isDisplayNameValid = displayName.trim().isNotEmpty() && displayName.trim().length <= 40
    val isUsernameValid = username.trim().length in 3..25 && username.trim().matches(usernameRegex)
    val canSave = isDisplayNameValid && isUsernameValid && isUsernameAvailable && !isSaving && !isCheckingUsername

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack, enabled = !isSaving) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = "Edit Profile",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )

                IconButton(
                    onClick = {
                        focusManager.clearFocus()
                        coroutineScope.launch {
                            isSaving = true
                            errorMessage = null
                            val res = friendManager.updateUserProfile(
                                newDisplayName = displayName,
                                newUsername = username,
                                newAvatarUri = if (isGoogleUser) selectedImageUri else null
                            )
                            isSaving = false
                            if (res.isSuccess) {
                                ToastManager.showToast("Profile updated successfully!")
                                onBack()
                            } else {
                                errorMessage = res.exceptionOrNull()?.message ?: "Failed to update profile"
                            }
                        }
                    },
                    enabled = canSave
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(
                            color = MimikyuYellow,
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Save",
                            tint = if (canSave) MimikyuYellow else Color.Gray
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(20.dp))

            // ── Avatar Picker ──
            Box(
                modifier = Modifier
                    .size(110.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF282828))
                    .then(
                        if (isGoogleUser) {
                            Modifier.clickable(enabled = !isSaving) {
                                photoPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            }
                        } else Modifier
                    ),
                contentAlignment = Alignment.Center
            ) {
                when {
                    selectedImageUri != null && isGoogleUser -> {
                        AsyncImage(
                            model = selectedImageUri,
                            contentDescription = "Selected Avatar",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }
                    isGoogleUser && !currentProfile?.avatarUrl.isNullOrEmpty() -> {
                        AsyncImage(
                            model = currentProfile?.avatarUrl,
                            contentDescription = "Current Avatar",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }
                    else -> {
                        Icon(
                            imageVector = Icons.Rounded.Person,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.6f),
                            modifier = Modifier.size(60.dp)
                        )
                    }
                }

                // Overlay Camera Badge (Google sign-in users only)
                if (isGoogleUser) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.35f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CameraAlt,
                            contentDescription = "Change photo",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            }

            if (!isGoogleUser) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Profile photo is managed by Google Sign-In",
                    color = Color.Gray,
                    fontSize = 12.sp
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "Tap to change photo",
                color = Color(0xFF1DB954),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.height(30.dp))

            // ── Display Name Field ──
            OutlinedTextField(
                value = displayName,
                onValueChange = {
                    if (it.length <= 40) displayName = it
                    errorMessage = null
                },
                label = { Text("Name") },
                placeholder = { Text("Your name") },
                singleLine = true,
                isError = !isDisplayNameValid && displayName.isNotEmpty(),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Next
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF1DB954),
                    unfocusedBorderColor = Color(0xFF404040),
                    focusedLabelColor = Color(0xFF1DB954),
                    unfocusedLabelColor = Color(0xFFB3B3B3),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(18.dp))

            // ── Username Field ──
            OutlinedTextField(
                value = username,
                onValueChange = {
                    val filtered = it.filter { char -> char.isLetterOrDigit() || char == '_' || char == '.' }
                    if (filtered.length <= 25) username = filtered
                    errorMessage = null
                },
                label = { Text("Username") },
                placeholder = { Text("username") },
                leadingIcon = {
                    Text(
                        text = "@",
                        color = Color(0xFF1DB954),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                },
                trailingIcon = {
                    if (isCheckingUsername) {
                        CircularProgressIndicator(
                            color = Color(0xFF1DB954),
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                    } else if (username.length >= 3) {
                        if (isUsernameAvailable) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Available",
                                tint = Color(0xFF1DB954),
                                modifier = Modifier.size(18.dp)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Taken",
                                tint = Color(0xFFFF5252),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                },
                singleLine = true,
                isError = (!isUsernameValid && username.isNotEmpty()) || !isUsernameAvailable,
                supportingText = {
                    if (!isUsernameAvailable) {
                        Text("@$username is already taken", color = Color(0xFFFF5252), fontSize = 12.sp)
                    } else if (!isUsernameValid && username.isNotEmpty()) {
                        Text("3-25 letters, numbers, _ or .", color = Color(0xFFFF5252), fontSize = 12.sp)
                    } else {
                        Text("Friends can find and add you with @$username", color = Color(0xFF888888), fontSize = 12.sp)
                    }
                },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF1DB954),
                    unfocusedBorderColor = Color(0xFF404040),
                    focusedLabelColor = Color(0xFF1DB954),
                    unfocusedLabelColor = Color(0xFFB3B3B3),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )

            // Error banner if any
            if (!errorMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = errorMessage.orEmpty(),
                    color = Color(0xFFFF5252),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }
    }
}
