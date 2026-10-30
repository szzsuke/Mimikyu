package com.lin0721.linmusic.core.auth

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import com.lin0721.linmusic.core.log.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "LoginViewModel"
private const val QR_LOGIN_URL_PREFIX = "https://music.163.com/login?codekey="
private const val POLL_INTERVAL_MS = 2500L
private const val QR_SIZE_PX = 480
private const val MAX_CONSECUTIVE_POLL_FAILURES = 5

sealed interface QrLoginState {
    data object Idle : QrLoginState
    data object Loading : QrLoginState
    data class WaitingScan(val qrBitmap: Bitmap) : QrLoginState
    data class WaitingConfirm(val qrBitmap: Bitmap) : QrLoginState
    data object Expired : QrLoginState
    data class Error(val message: String) : QrLoginState
}

// of  Google /  and  Cookie ， LoginBottomSheet
class LoginViewModel(
    private val authRepository: AuthRepository,
    private val googleAuthManager: GoogleAuthManager
) : ViewModel() {

    private val _qrState = MutableStateFlow<QrLoginState>(QrLoginState.Idle)
    val qrState: StateFlow<QrLoginState> = _qrState.asStateFlow()

    private var pollJob: Job? = null

    suspend fun isGoogleAuthReady(context: Context): Boolean = googleAuthManager.isConfigured(context)

    fun saveGoogleClientId(clientId: String, onSaved: () -> Unit) {
        viewModelScope.launch {
            googleAuthManager.saveClientId(clientId)
            onSaved()
        }
    }

    // Google  (Credential Manager)
    fun startGoogleLogin(
        context: Context,
        onSuccess: (UserProfile) -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            val result = googleAuthManager.signIn(context)
            result.fold(
                onSuccess = { profile ->
                    onSuccess(profile)
                },
                onFailure = { error ->
                    if (error is GetCredentialCancellationException) {
                        AppLogger.i(TAG, "User cancelled Google Sign-In")
                    } else {
                        AppLogger.w(TAG, "Google Sign-In failed", error)
                        onError(error.message ?: "Google Sign-In failed")
                    }
                }
            )
        }
    }

    fun quickLogin(
        name: String = "Guest",
        email: String = "guest@mimikyu.app",
        onSuccess: (UserProfile) -> Unit
    ) {
        viewModelScope.launch {
            val profile = googleAuthManager.signInAsLocalUser(name, email)
            onSuccess(profile)
        }
    }

    fun loginWithEmail(
        email: String,
        password: String,
        onSuccess: (UserProfile) -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            val result = googleAuthManager.signInWithEmail(email, password)
            result.fold(
                onSuccess = onSuccess,
                onFailure = { onError(it.message ?: "Sign-in failed") }
            )
        }
    }

    fun signUpWithEmail(
        email: String,
        password: String,
        nickname: String,
        onSuccess: (UserProfile) -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            val result = googleAuthManager.signUpWithEmail(email, password, nickname)
            result.fold(
                onSuccess = onSuccess,
                onFailure = { onError(it.message ?: "Sign-up failed") }
            )
        }
    }

    // ： key →  →
    fun startQrLogin(onLoginSuccess: (String) -> Unit) {
        stopQrPolling()
        _qrState.value = QrLoginState.Loading
        viewModelScope.launch {
            val key = authRepository.getQrKey().first().getOrNull()?.unikey
            if (key.isNullOrEmpty()) {
                _qrState.value = QrLoginState.Error("Failed to generate QR code, please retry")
                return@launch
            }
            val bitmap = try {
                withContext(Dispatchers.Default) { generateQrBitmap(QR_LOGIN_URL_PREFIX + key) }
            } catch (e: Exception) {
                AppLogger.e(TAG, "QR code generation failed", e)
                _qrState.value = QrLoginState.Error("Failed to generate QR code, please retry")
                return@launch
            }
            _qrState.value = QrLoginState.WaitingScan(bitmap)
            pollQrStatus(key, bitmap, onLoginSuccess)
        }
    }

    private fun pollQrStatus(key: String, bitmap: Bitmap, onLoginSuccess: (String) -> Unit) {
        pollJob = viewModelScope.launch {
            var consecutiveFailures = 0
            while (true) {
                delay(POLL_INTERVAL_MS)
                val response = authRepository.checkQrStatus(key).first().getOrNull()
                if (response == null) {
                    consecutiveFailures++
                    if (consecutiveFailures >= MAX_CONSECUTIVE_POLL_FAILURES) {
                        _qrState.value = QrLoginState.Error("Network error, please retry")
                        return@launch
                    }
                    continue
                }
                consecutiveFailures = 0
                when (response.code) {
                    800 -> {
                        _qrState.value = QrLoginState.Expired
                        return@launch
                    }
                    801 -> _qrState.value = QrLoginState.WaitingScan(bitmap)
                    802 -> _qrState.value = QrLoginState.WaitingConfirm(bitmap)
                    803 -> {
                        val cookies = response.cookies
                        if (cookies.isNullOrEmpty()) {
                            AppLogger.e(TAG, "QR login returned 803 but Set-Cookie not parsed")
                            _qrState.value = QrLoginState.Error("Logged in but failed to obtain session, please retry")
                        } else {
                            onLoginSuccess(cookies)
                        }
                        return@launch
                    }
                    else -> {
                        val errorMsg = response.message?.takeIf { it.isNotBlank() } ?: "QR scan error (${response.code})"
                        AppLogger.w(TAG, "QR polling error: code=${response.code}, message=$errorMsg")
                        _qrState.value = QrLoginState.Error(errorMsg)
                        return@launch
                    }
                }
            }
        }
    }

    fun stopQrPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    // Close/Reset， of
    fun resetQrState() {
        stopQrPolling()
        _qrState.value = QrLoginState.Idle
    }

    // Cookie ： Cookie ， MUSIC_U
    fun submitCookieLogin(rawCookie: String, onLoginSuccess: (String) -> Unit): Boolean {
        val trimmed = rawCookie.trim()
        val cookieString = when {
            trimmed.isEmpty() -> return false
            trimmed.contains("MUSIC_U=") -> trimmed
            !trimmed.contains("=") && !trimmed.contains(";") && !trimmed.contains(" ") -> "MUSIC_U=$trimmed"
            else -> return false
        }
        onLoginSuccess(cookieString)
        return true
    }

    // setPixels
    private fun generateQrBitmap(content: String): Bitmap {
        val matrix: BitMatrix = MultiFormatWriter().encode(
            content, BarcodeFormat.QR_CODE, QR_SIZE_PX, QR_SIZE_PX
        )
        val pixels = IntArray(QR_SIZE_PX * QR_SIZE_PX)
        for (y in 0 until QR_SIZE_PX) {
            val rowOffset = y * QR_SIZE_PX
            for (x in 0 until QR_SIZE_PX) {
                pixels[rowOffset + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        val bitmap = Bitmap.createBitmap(QR_SIZE_PX, QR_SIZE_PX, Bitmap.Config.RGB_565)
        bitmap.setPixels(pixels, 0, QR_SIZE_PX, 0, 0, QR_SIZE_PX, QR_SIZE_PX)
        return bitmap
    }

    override fun onCleared() {
        stopQrPolling()
        super.onCleared()
    }
}
