package com.lin0721.linmusic.core.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lin0721.linmusic.R
import com.lin0721.linmusic.core.auth.LoginViewModel
import com.lin0721.linmusic.core.auth.UserProfile
import com.lin0721.linmusic.core.ui.theme.BottomSheetShape
import com.lin0721.linmusic.core.ui.theme.ContentSwitchDurationMs
import com.lin0721.linmusic.core.ui.theme.NeteaseRed
import com.lin0721.linmusic.core.ui.theme.SurfaceDark
import com.lin0721.linmusic.core.ui.theme.SurfaceLight
import com.lin0721.linmusic.core.ui.theme.MimikyuSpacing
import com.lin0721.linmusic.core.ui.theme.MimikyuCharcoal
import com.lin0721.linmusic.core.ui.theme.MimikyuYellow
import com.lin0721.linmusic.core.ui.theme.MimikyuSilver
import org.koin.androidx.compose.koinViewModel

private enum class LoginMode { CHOICE, EMAIL }

private fun LoginMode.depth(): Int = if (this == LoginMode.CHOICE) 0 else 1

/**
 * Modern Login Bottom Sheet
 * Supports Google Login and Email & Password authentication.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginBottomSheet(
    onDismiss: () -> Unit,
    onWebLogin: () -> Unit = {},
    onLoginSuccess: (String) -> Unit
) {
    val context = LocalContext.current
    val viewModel: LoginViewModel = koinViewModel()
    var mode by remember { mutableStateOf(LoginMode.CHOICE) }

    var showClientIdDialog by remember { mutableStateOf(false) }
    var clientIdInput by remember { mutableStateOf("") }

    if (showClientIdDialog) {
        AlertDialog(
            onDismissRequest = { showClientIdDialog = false },
            containerColor = SurfaceDark,
            title = {
                Text(
                    text = "Google Sign-In Setup",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MimikyuSilver
                )
            },
            text = {
                Column {
                    Text(
                        text = "Android Credential Manager requires an OAuth 2.0 Web Client ID from your Google Cloud Console. Paste it below to enable Google account login:",
                        fontSize = 13.sp,
                        color = Color.LightGray,
                        lineHeight = 18.sp
                    )
                    Spacer(modifier = Modifier.height(MimikyuSpacing.md))
                    PlaceholderTextField(
                        value = clientIdInput,
                        onValueChange = { clientIdInput = it },
                        placeholder = "xxxx.apps.googleusercontent.com",
                        singleLine = false,
                        minLines = 2
                    )
                }
            },
            confirmButton = {
                MimikyuButton(
                    onClick = {
                        val trimmed = clientIdInput.trim()
                        if (trimmed.isNotEmpty() && !trimmed.startsWith("your-")) {
                            viewModel.saveGoogleClientId(trimmed) {
                                showClientIdDialog = false
                                viewModel.startGoogleLogin(
                                    context = context,
                                    onSuccess = { profile ->
                                        onLoginSuccess("google_auth_token=success")
                                        ToastManager.showToast("Signed in as ${profile.nickname}")
                                        onDismiss()
                                    },
                                    onError = { msg ->
                                        ToastManager.showToast(msg)
                                    }
                                )
                            }
                        } else {
                            ToastManager.showToast("Please enter a valid Web Client ID")
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NeteaseRed)
                ) {
                    Text("Save & Sign In", color = MimikyuCharcoal, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                MimikyuTextButton(onClick = { showClientIdDialog = false }) {
                    Text("Cancel", color = Color.Gray)
                }
            }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        shape = BottomSheetShape,
        dragHandle = { MimikyuDragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = MimikyuSpacing.lg)
                .padding(bottom = 64.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AnimatedVisibility(
                visible = mode != LoginMode.CHOICE,
                enter = fadeIn(tween(ContentSwitchDurationMs, easing = FastOutSlowInEasing)) +
                    expandVertically(tween(ContentSwitchDurationMs, easing = FastOutSlowInEasing)),
                exit = fadeOut(tween(ContentSwitchDurationMs, easing = FastOutSlowInEasing)) +
                    shrinkVertically(tween(ContentSwitchDurationMs, easing = FastOutSlowInEasing))
            ) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    MimikyuIconButton(onClick = {
                        mode = LoginMode.CHOICE
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = MimikyuSilver)
                    }
                }
            }

            AnimatedContent(
                targetState = mode,
                transitionSpec = {
                    val offsetSpec = tween<androidx.compose.ui.unit.IntOffset>(ContentSwitchDurationMs, easing = FastOutSlowInEasing)
                    val alphaSpec = tween<Float>(ContentSwitchDurationMs, easing = FastOutSlowInEasing)
                    val transform = if (targetState.depth() > initialState.depth()) {
                        (slideInHorizontally(offsetSpec) { it } + fadeIn(alphaSpec)) togetherWith
                            (slideOutHorizontally(offsetSpec) { -it } + fadeOut(alphaSpec))
                    } else {
                        (slideInHorizontally(offsetSpec) { -it } + fadeIn(alphaSpec)) togetherWith
                            (slideOutHorizontally(offsetSpec) { it } + fadeOut(alphaSpec))
                    }
                    transform.using(SizeTransform(clip = true))
                },
                label = "LoginModeContent"
            ) { targetMode ->
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    when (targetMode) {
                        LoginMode.CHOICE -> LoginChoiceContent(
                            onGoogleLogin = {
                                viewModel.startGoogleLogin(
                                    context = context,
                                    onSuccess = { profile ->
                                        onLoginSuccess("google_auth_token=success")
                                        ToastManager.showToast("Signed in as ${profile.nickname}")
                                        onDismiss()
                                    },
                                    onError = { msg ->
                                        ToastManager.showToast(msg)
                                    }
                                )
                            },
                            onEmailLogin = {
                                mode = LoginMode.EMAIL
                            }
                        )
                        LoginMode.EMAIL -> EmailLoginContent(
                            viewModel = viewModel,
                            onSuccess = { profile ->
                                onLoginSuccess("google_auth_token=email_success")
                                ToastManager.showToast("Welcome, ${profile.nickname}!")
                                onDismiss()
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Clean Choice screen: Google Login + Email & Password
 */
@Composable
private fun LoginChoiceContent(
    onGoogleLogin: () -> Unit,
    onEmailLogin: () -> Unit
) {
    Text(
        text = "Sign In",
        fontSize = 28.sp,
        fontWeight = FontWeight.ExtraBold,
        color = MimikyuSilver,
        modifier = Modifier.padding(bottom = MimikyuSpacing.sm)
    )
    Text(
        text = "Sign in to save playlists and sync across devices",
        fontSize = 13.sp,
        color = Color.Gray,
        modifier = Modifier.padding(bottom = MimikyuSpacing.xl)
    )

    // Primary: Google Login
    GoogleLoginButton(onClick = onGoogleLogin)

    Spacer(modifier = Modifier.height(MimikyuSpacing.md))

    // Primary 2: Email & Password
    LoginOptionButton(
        text = "Email & Password",
        icon = Icons.Default.Email,
        isPrimary = true,
        onClick = onEmailLogin
    )
}

/**
 * Email & Password Login / Registration Content
 */
@Composable
private fun EmailLoginContent(
    viewModel: LoginViewModel,
    onSuccess: (UserProfile) -> Unit
) {
    var isSignUp by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var nickname by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Text(
        text = if (isSignUp) "Create Account" else "Email Sign In",
        fontSize = 24.sp,
        fontWeight = FontWeight.Bold,
        color = MimikyuSilver,
        modifier = Modifier.padding(bottom = MimikyuSpacing.xs)
    )
    Text(
        text = if (isSignUp) "Sign up with your email to start syncing" else "Enter your email and password to continue",
        fontSize = 13.sp,
        color = Color.Gray,
        modifier = Modifier.padding(bottom = MimikyuSpacing.lg)
    )

    // Mode Toggle Tabs
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceLight)
            .padding(4.dp)
    ) {
        MimikyuButton(
            onClick = {
                isSignUp = false
                errorMessage = null
            },
            modifier = Modifier.weight(1f).height(40.dp),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (!isSignUp) NeteaseRed else Color.Transparent
            ),
            elevation = null
        ) {
            Text(
                text = "Sign In",
                fontSize = 14.sp,
                fontWeight = if (!isSignUp) FontWeight.Bold else FontWeight.Normal,
                color = if (!isSignUp) MimikyuCharcoal else MimikyuSilver
            )
        }

        MimikyuButton(
            onClick = {
                isSignUp = true
                errorMessage = null
            },
            modifier = Modifier.weight(1f).height(40.dp),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isSignUp) NeteaseRed else Color.Transparent
            ),
            elevation = null
        ) {
            Text(
                text = "Create Account",
                fontSize = 14.sp,
                fontWeight = if (isSignUp) FontWeight.Bold else FontWeight.Normal,
                color = if (isSignUp) MimikyuCharcoal else MimikyuSilver
            )
        }
    }

    Spacer(modifier = Modifier.height(MimikyuSpacing.lg))

    if (isSignUp) {
        PlaceholderTextField(
            value = nickname,
            onValueChange = {
                nickname = it
                errorMessage = null
            },
            placeholder = "Nickname (optional)",
            singleLine = true
        )
        Spacer(modifier = Modifier.height(MimikyuSpacing.sm))
    }

    PlaceholderTextField(
        value = email,
        onValueChange = {
            email = it
            errorMessage = null
        },
        placeholder = "Email address",
        singleLine = true
    )

    Spacer(modifier = Modifier.height(MimikyuSpacing.sm))

    OutlinedTextField(
        value = password,
        onValueChange = {
            password = it
            errorMessage = null
        },
        placeholder = { Text(if (isSignUp) "Password (min 6 characters)" else "Password", color = Color.Gray) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                Icon(
                    imageVector = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                    contentDescription = "Toggle password visibility",
                    tint = Color.Gray
                )
            }
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = NeteaseRed,
            unfocusedBorderColor = SurfaceLight,
            focusedTextColor = MimikyuSilver,
            unfocusedTextColor = MimikyuSilver,
            focusedContainerColor = SurfaceLight.copy(alpha = 0.5f),
            unfocusedContainerColor = SurfaceLight.copy(alpha = 0.5f)
        )
    )

    if (errorMessage != null) {
        Spacer(modifier = Modifier.height(MimikyuSpacing.sm))
        Text(
            text = errorMessage.orEmpty(),
            fontSize = 13.sp,
            color = NeteaseRed,
            modifier = Modifier.fillMaxWidth()
        )
    }

    Spacer(modifier = Modifier.height(MimikyuSpacing.xl))

    MimikyuButton(
        onClick = {
            if (email.isBlank() || password.isBlank()) {
                errorMessage = "Please enter both email and password"
                return@MimikyuButton
            }
            if (isSignUp && password.length < 6) {
                errorMessage = "Password must be at least 6 characters"
                return@MimikyuButton
            }

            isLoading = true
            errorMessage = null

            if (isSignUp) {
                viewModel.signUpWithEmail(
                    email = email,
                    password = password,
                    nickname = nickname,
                    onSuccess = { profile ->
                        isLoading = false
                        onSuccess(profile)
                    },
                    onError = { err ->
                        isLoading = false
                        errorMessage = err
                    }
                )
            } else {
                viewModel.loginWithEmail(
                    email = email,
                    password = password,
                    onSuccess = { profile ->
                        isLoading = false
                        onSuccess(profile)
                    },
                    onError = { err ->
                        isLoading = false
                        errorMessage = err
                    }
                )
            }
        },
        enabled = !isLoading,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = NeteaseRed),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp, pressedElevation = 2.dp)
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                color = MimikyuCharcoal,
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp
            )
        } else {
            Text(
                text = if (isSignUp) "Create Account" else "Sign In",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = MimikyuCharcoal
            )
        }
    }
}

/**
 * Google Login Button
 */
@Composable
private fun GoogleLoginButton(
    onClick: () -> Unit
) {
    MimikyuButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = SurfaceDark,
            contentColor = MimikyuSilver
        ),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 4.dp,
            pressedElevation = 2.dp
        )
    ) {
        Image(
            painter = painterResource(id = R.drawable.ic_google),
            contentDescription = "Google Logo",
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = "Google Login",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = MimikyuSilver
        )
    }
}

/**
 * Login Option Button
 */
@Composable
private fun LoginOptionButton(
    text: String,
    icon: ImageVector,
    isPrimary: Boolean,
    onClick: () -> Unit
) {
    MimikyuButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = NeteaseRed
        ),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 4.dp,
            pressedElevation = 2.dp
        )
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = MimikyuCharcoal
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = text,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = MimikyuCharcoal
        )
    }
}
