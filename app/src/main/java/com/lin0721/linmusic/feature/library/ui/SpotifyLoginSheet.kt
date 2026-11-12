package com.lin0721.linmusic.feature.library.ui

import android.annotation.SuppressLint
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.lin0721.linmusic.core.spotify.SpotifyAuth
import com.lin0721.linmusic.core.ui.components.MimikyuDragHandle
import com.lin0721.linmusic.core.ui.theme.BottomSheetShape
import com.lin0721.linmusic.core.ui.theme.MimikyuIcons
import com.lin0721.linmusic.core.ui.theme.MimikyuSpacing

private const val SPOTIFY_GREEN = 0xFF1DB954

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SpotifyLoginSheet(
    onDismiss: () -> Unit,
    onSuccess: (spDc: String, spKey: String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var isPageLoading by remember { mutableStateOf(true) }
    var captured by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = BottomSheetShape,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { MimikyuDragHandle() },
        modifier = Modifier.fillMaxHeight(0.92f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = MimikyuSpacing.md)
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = MimikyuSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color(SPOTIFY_GREEN)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(com.lin0721.linmusic.R.drawable.ic_spotify_minimal),
                            contentDescription = "Spotify",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(MimikyuSpacing.md))
                    Column {
                        Text(
                            text = "Connect Spotify",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Log in to sync your Spotify playlists",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White)
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        val cookieManager = CookieManager.getInstance()
                        cookieManager.setAcceptCookie(true)

                        fun extractCookies(url: String?): Boolean {
                            if (captured) return true
                            val urls = listOf(
                                "https://open.spotify.com",
                                "https://accounts.spotify.com",
                                "https://spotify.com"
                            )
                            cookieManager.flush()
                            var foundSpDc = ""
                            var foundSpKey = ""

                            for (u in urls) {
                                val cookieString = cookieManager.getCookie(u) ?: continue
                                val parts = cookieString.split(";").map { it.trim() }
                                for (part in parts) {
                                    val idx = part.indexOf('=')
                                    if (idx <= 0) continue
                                    val k = part.substring(0, idx).trim()
                                    val v = part.substring(idx + 1).trim()
                                    if (k == "sp_dc" && v.isNotBlank()) foundSpDc = v
                                    if (k == "sp_key" && v.isNotBlank()) foundSpKey = v
                                }
                            }

                            if (foundSpDc.isNotBlank()) {
                                captured = true
                                onSuccess(foundSpDc, foundSpKey)
                                return true
                            }
                            return false
                        }

                        WebView(ctx).apply {
                            layoutParams = FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            cookieManager.setAcceptThirdPartyCookies(this, true)

                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                databaseEnabled = true
                                setSupportMultipleWindows(true)
                                userAgentString =
                                    "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
                            }

                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                    isPageLoading = true
                                    extractCookies(url)
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    isPageLoading = false
                                    extractCookies(url)
                                }

                                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                    val url = request?.url?.toString()
                                    if (extractCookies(url)) return true
                                    return false
                                }
                            }

                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    if (newProgress >= 90) isPageLoading = false
                                }
                            }

                            loadUrl(SpotifyAuth.LOGIN_URL)
                        }
                    }
                )

                if (isPageLoading) {
                    CircularProgressIndicator(
                        color = Color(SPOTIFY_GREEN),
                        modifier = Modifier.size(36.dp).align(Alignment.Center)
                    )
                }
            }
        }
    }
}

