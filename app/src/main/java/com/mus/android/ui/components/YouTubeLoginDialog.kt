package com.mus.android.ui.components

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mus.android.ui.theme.MusColors

/**
 * WebView sign-in flow for YouTube Music (ported from LastWave's
 * YouTubeLoginScreen). The user signs in inside the embedded WebView; once a
 * SAPISID-bearing cookie set is detected on music.youtube.com, the captured
 * Cookie header is handed to [onCookiesCaptured].
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YouTubeLoginDialog(
    onDismiss: () -> Unit,
    onCookiesCaptured: (String) -> Unit,
) {
    var captured by remember { mutableStateOf(false) }

    fun tryCapture(url: String?) {
        if (captured || url == null) return
        if (!url.contains("music.youtube.com") && !url.contains("www.youtube.com")) return
        val cookie = runCatching { CookieManager.getInstance().getCookie("https://music.youtube.com") }
            .getOrNull()
            ?: return
        val hasSapisid = listOf("__Secure-3PAPISID", "SAPISID", "APISID").any { name ->
            cookie.split(';').any { it.trim().startsWith("$name=") }
        }
        if (hasSapisid) {
            captured = true
            onCookiesCaptured(cookie)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(if (captured) "Done" else "Cancel") }
        },
        title = { Text("Sign in to YouTube Music", color = MusColors.OnBackground) },
        text = {
            Column {
                Text(
                    "Sign in with your Google account. MUS only reads the session " +
                        "cookies needed to browse YouTube Music with your account.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MusColors.OnBackgroundSecondary,
                )
                Spacer(Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(420.dp),
                ) {
                    AndroidView(
                        factory = { context ->
                            WebView(context).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                CookieManager.getInstance().setAcceptCookie(true)
                                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(
                                        view: WebView,
                                        request: WebResourceRequest,
                                    ): Boolean = false

                                    override fun onPageFinished(view: WebView, url: String?) {
                                        try {
                                            CookieManager.getInstance().flush()
                                        } catch (_: Exception) {}
                                        tryCapture(url)
                                    }
                                }
                                loadUrl("https://music.youtube.com/")
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        },
        containerColor = MusColors.SurfaceElevated,
    )
}

/**
 * Fallback cookie paste dialog: accepts a raw Cookie header, Netscape
 * cookies.txt export, or JSON from browser extensions (via CookieNormalizer).
 */
@Composable
fun CookiePasteDialog(
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
    error: String?,
) {
    var text by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { onSubmit(text) },
                enabled = text.isNotBlank(),
            ) { Text("Connect") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        title = { Text("Paste YouTube cookies", color = MusColors.OnBackground) },
        text = {
            Column {
                Text(
                    "Export cookies from music.youtube.com (signed in) and paste them here: " +
                        "a Cookie header, cookies.txt, or JSON.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MusColors.OnBackgroundSecondary,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 240.dp),
                    placeholder = { Text("PAPISID=…; SAPISID=…; …", color = MusColors.OnBackgroundTertiary) },
                    isError = error != null,
                    supportingText = error?.let {
                        { Text(it, color = MaterialTheme.colorScheme.error) }
                    },
                )
            }
        },
        containerColor = MusColors.SurfaceElevated,
    )
}
