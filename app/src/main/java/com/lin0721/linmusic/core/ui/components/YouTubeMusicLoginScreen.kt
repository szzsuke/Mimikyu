package com.lin0721.linmusic.core.ui.components

import android.annotation.SuppressLint
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

private const val TAG = "YouTubeMusicLogin"
private const val MUSIC_ORIGIN = "https://music.youtube.com"
private const val LOGIN_URL =
    "https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com"

private val API_SID_NAMES = setOf("SAPISID", "__Secure-3PAPISID", "__Secure-1PAPISID", "LOGIN_INFO")

private fun hasApiSid(cookieHeader: String): Boolean =
    cookieHeader.split(';').any { entry ->
        val name = entry.substringBefore('=').trim()
        val value = entry.substringAfter('=', "").trim()
        name in API_SID_NAMES && value.isNotEmpty()
    }

private const val YTCFG_PROBE = """
(function () {
  try {
    if (!window.ytcfg || !window.ytcfg.get) return null;
    var get = function (key) {
      var value = window.ytcfg.get(key);
      return (value === undefined || value === null || value === '') ? null : String(value);
    };
    return {
      loggedIn: String(!!window.ytcfg.get('LOGGED_IN')),
      pageId: get('DELEGATED_SESSION_ID'),
      dataSyncId: get('DATASYNC_ID'),
      authUser: get('SESSION_INDEX'),
      visitorData: get('VISITOR_DATA'),
      clientVersion: get('INNERTUBE_CLIENT_VERSION')
    };
  } catch (e) {
    return null;
  }
})()
"""

private val jsonParser = Json { ignoreUnknownKeys = true }

private fun String?.parseConfig(): JsonObject? =
    this?.let { runCatching { jsonParser.parseToJsonElement(it).jsonObject }.getOrNull() }

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

data class YouTubeSessionData(
    val cookies: String,
    val visitorData: String? = null,
    val dataSyncId: String? = null,
    val authUser: String? = null,
    val pageId: String? = null
)

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YouTubeMusicLoginScreen(
    onClose: () -> Unit,
    onLoginSuccess: (YouTubeSessionData) -> Unit,
    modifier: Modifier = Modifier
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var pageReady by remember { mutableStateOf(false) }
    var isCapturing by remember { mutableStateOf(false) }
    var showManualCookieDialog by remember { mutableStateOf(false) }
    var manualCookieText by remember { mutableStateOf("") }

    fun captureSession(onUnavailable: () -> Unit = {}) {
        val view = webView ?: return
        val cookies = CookieManager.getInstance().getCookie(MUSIC_ORIGIN)
        if (cookies.isNullOrBlank() || !hasApiSid(cookies)) {
            Log.d(TAG, "No API SID cookie available yet")
            onUnavailable()
            return
        }

        CookieManager.getInstance().flush()

        view.evaluateJavascript(YTCFG_PROBE) { raw ->
            val config = raw.parseConfig()
            val visitorData = config?.string("visitorData")
            val dataSyncId = config?.string("dataSyncId")
            val authUser = config?.string("authUser")
            val pageId = config?.string("pageId")

            Log.d(TAG, "Captured YouTube Music session successfully")
            onLoginSuccess(
                YouTubeSessionData(
                    cookies = cookies,
                    visitorData = visitorData,
                    dataSyncId = dataSyncId,
                    authUser = authUser,
                    pageId = pageId
                )
            )
        }
    }

    if (showManualCookieDialog) {
        AlertDialog(
            onDismissRequest = { showManualCookieDialog = false },
            title = { Text("Import Cookies") },
            text = {
                Column {
                    Text(
                        "Paste your YouTube Music cookies below (must include SAPISID or LOGIN_INFO):",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = manualCookieText,
                        onValueChange = { manualCookieText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        placeholder = { Text("SAPISID=...; __Secure-3PAPISID=...") },
                        maxLines = 5
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = manualCookieText.trim()
                        if (trimmed.isNotBlank()) {
                            showManualCookieDialog = false
                            onLoginSuccess(
                                YouTubeSessionData(
                                    cookies = trimmed
                                )
                            )
                        }
                    },
                    enabled = manualCookieText.isNotBlank()
                ) {
                    Text("Connect")
                }
            },
            dismissButton = {
                TextButton(onClick = { showManualCookieDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Connect YouTube Music",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = if (pageReady) "Ready to connect profile" else "Sign in to your Google Account",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }

                IconButton(onClick = { showManualCookieDialog = true }) {
                    Icon(
                        imageVector = Icons.Rounded.Key,
                        contentDescription = "Paste Cookies",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                if (pageReady) {
                    FilledTonalButton(
                        onClick = {
                            if (!isCapturing) {
                                isCapturing = true
                                captureSession { isCapturing = false }
                            }
                        },
                        enabled = !isCapturing
                    ) {
                        Text(if (isCapturing) "Syncing..." else "Use Account")
                    }
                }
            }

            AndroidView(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                factory = { context ->
                    WebView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            setSupportZoom(true)
                            builtInZoomControls = true
                            displayZoomControls = false
                        }

                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                val isMusicOrigin = url?.startsWith(MUSIC_ORIGIN) == true
                                val cookies = CookieManager.getInstance().getCookie(MUSIC_ORIGIN)
                                val hasValidSession = !cookies.isNullOrBlank() && hasApiSid(cookies)

                                if (isMusicOrigin && hasValidSession) {
                                    pageReady = true
                                    captureSession()
                                } else {
                                    pageReady = hasValidSession
                                }
                            }

                            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                                super.doUpdateVisitedHistory(view, url, isReload)
                                val cookies = CookieManager.getInstance().getCookie(MUSIC_ORIGIN)
                                if (url?.startsWith(MUSIC_ORIGIN) == true && !cookies.isNullOrBlank() && hasApiSid(cookies)) {
                                    pageReady = true
                                    captureSession()
                                }
                            }
                        }

                        webView = this
                        loadUrl(LOGIN_URL)
                    }
                }
            )
        }
    }
}
