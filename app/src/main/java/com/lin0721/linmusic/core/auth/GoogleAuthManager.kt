package com.lin0721.linmusic.core.auth

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.lin0721.linmusic.R
import com.lin0721.linmusic.core.log.AppLogger
import kotlinx.coroutines.flow.firstOrNull
import java.security.MessageDigest
import java.util.UUID

private const val TAG = "GoogleAuthManager"

class GoogleAuthManager(
    private val context: Context,
    private val userPreferences: UserPreferences
) {
    private val credentialManager = CredentialManager.create(context)

    private fun findActivity(context: Context): Activity? {
        var currentContext = context
        while (currentContext is ContextWrapper) {
            if (currentContext is Activity) return currentContext
            currentContext = currentContext.baseContext
        }
        return null
    }

    suspend fun getEffectiveClientId(context: Context): String {
        val saved = userPreferences.googleClientId.firstOrNull()?.trim().orEmpty()
        if (saved.isNotEmpty() && !saved.startsWith("your-") && !saved.contains("placeholder")) {
            return saved
        }
        val fromXml = try {
            context.getString(R.string.google_web_client_id).trim()
        } catch (e: Exception) {
            ""
        }
        if (fromXml.isNotEmpty() && !fromXml.startsWith("your-") && !fromXml.contains("placeholder")) {
            return fromXml
        }
        return ""
    }

    suspend fun isConfigured(context: Context): Boolean {
        val id = getEffectiveClientId(context)
        return id.isNotEmpty() && !id.startsWith("your-")
    }

    suspend fun saveClientId(clientId: String) {
        userPreferences.saveGoogleClientId(clientId)
    }

    suspend fun signIn(context: Context): Result<UserProfile> {
        val activity = findActivity(context) ?: return Result.failure(
            IllegalStateException("Activity context not found")
        )

        val serverClientId = getEffectiveClientId(activity)

        if (serverClientId.isEmpty()) {
            AppLogger.w(TAG, "Google Web Client ID is not configured (strings.xml or custom setting)")
            return Result.failure(
                IllegalStateException("Please set your Google Web Client ID in strings.xml (google_web_client_id)")
            )
        }

        val rawNonce = UUID.randomUUID().toString()
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(rawNonce.toByteArray())
        val hashedNonce = digest.fold("") { str, it -> str + "%02x".format(it) }

        // Use GetSignInWithGoogleOption for explicit button click sign-in
        val signInWithGoogleOption = GetSignInWithGoogleOption.Builder(serverClientId)
            .setNonce(hashedNonce)
            .build()

        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(serverClientId)
            .setAutoSelectEnabled(false)
            .setNonce(hashedNonce)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(signInWithGoogleOption)
            .addCredentialOption(googleIdOption)
            .build()

        return try {
            val response = credentialManager.getCredential(
                context = activity,
                request = request
            )
            val credential = response.credential

            if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                val email = googleIdTokenCredential.id
                val displayName = googleIdTokenCredential.displayName
                    ?: email.substringBefore("@").replaceFirstChar { it.uppercase() }
                val avatarUrl = googleIdTokenCredential.profilePictureUri?.toString() ?: ""

                val uid = (email.hashCode().toLong() and 0x7FFFFFFF).coerceAtLeast(100000L)

                val userProfile = UserProfile(
                    uid = uid,
                    nickname = displayName,
                    avatarUrl = avatarUrl
                )

                // Sign in with Firebase Auth so Firestore and Firebase features are authenticated
                try {
                    val firebaseAuth = com.google.firebase.auth.FirebaseAuth.getInstance()
                    val firebaseCred = com.google.firebase.auth.GoogleAuthProvider.getCredential(googleIdTokenCredential.idToken, null)
                    firebaseAuth.signInWithCredential(firebaseCred).awaitTask()
                    AppLogger.i(TAG, "Firebase Auth successfully linked with Google credential")
                } catch (fe: Exception) {
                    AppLogger.w(TAG, "Firebase Auth sign-in warning: ${fe.message}", fe)
                }

                userPreferences.saveUserProfile(userProfile)
                userPreferences.saveCookies("google_auth_token=${googleIdTokenCredential.idToken}")

                AppLogger.i(TAG, "Google Sign-In successful for: $email, nickname: $displayName")
                Result.success(userProfile)
            } else {
                AppLogger.w(TAG, "Unexpected credential type: ${credential.type}")
                Result.failure(IllegalStateException("Unexpected credential returned"))
            }
        } catch (e: GetCredentialCancellationException) {
            AppLogger.i(TAG, "User cancelled Google Sign-In")
            Result.failure(e)
        } catch (e: NoCredentialException) {
            AppLogger.e(TAG, "No credentials available. Ensure the Android app SHA-1 fingerprint is added in Google Cloud Console.", e)
            Result.failure(
                IllegalStateException("No Google account credentials available. Please ensure your Android app SHA-1 fingerprint is added in Google Cloud Console.")
            )
        } catch (e: GetCredentialException) {
            AppLogger.e(TAG, "Credential Manager error: ${e.message}", e)
            Result.failure(e)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Google Sign-In failed", e)
            Result.failure(e)
        }
    }

    
    suspend fun signInAsLocalUser(
        name: String = "Nishkarsh Shrivastava",
        email: String = "nishkarsh.24bcg10106@vitbhopal.ac.in"
    ): UserProfile {
        val uid = (email.hashCode().toLong() and 0x7FFFFFFF).coerceAtLeast(100000L)
        val profile = UserProfile(
            uid = uid,
            nickname = name,
            avatarUrl = "https://lh3.googleusercontent.com/a/ACg8ocIS19D9sR8vj3Fh5Q51X2P0_Y=s96-c",
            username = "nishkarsh"
        )
        try {
            val auth = com.google.firebase.auth.FirebaseAuth.getInstance()
            if (auth.currentUser == null) {
                auth.signInAnonymously().awaitTask()
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Anonymous auth note: ${e.message}")
        }
        userPreferences.saveUserProfile(profile)
        userPreferences.saveCookies("google_auth_token=auth_$uid")
        AppLogger.i(TAG, "Local Mimikyu user session activated for $name ($email)")
        return profile
    }

    suspend fun signInWithEmail(email: String, password: String): Result<UserProfile> {
        val trimmedEmail = email.trim()
        val trimmedPassword = password.trim()
        if (trimmedEmail.isEmpty() || trimmedPassword.isEmpty()) {
            return Result.failure(IllegalArgumentException("Email and password cannot be empty"))
        }
        return try {
            val auth = com.google.firebase.auth.FirebaseAuth.getInstance()
            val result = auth.signInWithEmailAndPassword(trimmedEmail, trimmedPassword).awaitTask()
            val user = result.user ?: return Result.failure(IllegalStateException("No user account returned"))
            val displayName = user.displayName?.takeIf { it.isNotBlank() }
                ?: trimmedEmail.substringBefore("@").replaceFirstChar { it.uppercase() }
            val avatarUrl = user.photoUrl?.toString()?.takeIf { it.isNotBlank() && !it.contains("dicebear") }.orEmpty()

            val uid = (trimmedEmail.hashCode().toLong() and 0x7FFFFFFF).coerceAtLeast(100000L)
            val profile = UserProfile(uid = uid, nickname = displayName, avatarUrl = avatarUrl)
            userPreferences.saveUserProfile(profile)
            userPreferences.saveCookies("google_auth_token=email_login")
            AppLogger.i(TAG, "Email login successful for $trimmedEmail")
            Result.success(profile)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Email login error: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun signUpWithEmail(email: String, password: String, nickname: String): Result<UserProfile> {
        val trimmedEmail = email.trim()
        val trimmedPassword = password.trim()
        if (trimmedEmail.isEmpty() || trimmedPassword.length < 6) {
            return Result.failure(IllegalArgumentException("Please enter a valid email and at least a 6-character password"))
        }
        return try {
            val auth = com.google.firebase.auth.FirebaseAuth.getInstance()
            val result = auth.createUserWithEmailAndPassword(trimmedEmail, trimmedPassword).awaitTask()
            val user = result.user ?: return Result.failure(IllegalStateException("Failed to register account"))
            val effectiveName = nickname.trim().ifEmpty {
                trimmedEmail.substringBefore("@").replaceFirstChar { it.uppercase() }
            }
            val updateReq = com.google.firebase.auth.UserProfileChangeRequest.Builder()
                .setDisplayName(effectiveName)
                .build()
            user.updateProfile(updateReq).awaitTask()

            val uid = (trimmedEmail.hashCode().toLong() and 0x7FFFFFFF).coerceAtLeast(100000L)
            val profile = UserProfile(uid = uid, nickname = effectiveName, avatarUrl = "")
            userPreferences.saveUserProfile(profile)
            userPreferences.saveCookies("google_auth_token=email_signup")
            AppLogger.i(TAG, "Email sign-up successful for $trimmedEmail, nickname: $effectiveName")
            Result.success(profile)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Email sign-up error: ${e.message}", e)
            Result.failure(e)
        }
    }
}
