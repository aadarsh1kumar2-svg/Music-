package com.example.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.net.http.SslError
import android.os.Message
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.ui.theme.DeepCharcoal
import com.example.ui.theme.MutedGray
import com.example.ui.theme.PrimaryWhite
import com.example.ui.theme.PureBlack
import com.example.ui.theme.SpotifyVibrantGreen
import kotlinx.coroutines.delay

const val ADSTERRA_SMARTLINK_URL = "https://araplhn.org/4/f5b2b67bce7e3251ca63709cb8ad1b64"
const val ADSTERRA_SMARTLINK_ID = "6154655"
private const val STARTUP_AD_TIMER_SECONDS = 30

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun StartupSmartlinkAdScreen(
    onCloseAdAndEnterApp: () -> Unit
) {
    val context = LocalContext.current
    var remainingSeconds by rememberSaveable { mutableIntStateOf(STARTUP_AD_TIMER_SECONDS) }
    var isPageLoading by remember { mutableStateOf(true) }
    var loadProgress by remember { mutableIntStateOf(15) }
    var adWebViewRef by remember { mutableStateOf<WebView?>(null) }

    val isTimerCompleted = remainingSeconds <= 0

    // Count down 30 seconds on launch; once 0 is reached, show the Cut (X) button in the top-right corner
    LaunchedEffect(Unit) {
        while (remainingSeconds > 0) {
            delay(1000L)
            remainingSeconds -= 1
        }
    }

    // Block accidental back press while the 30s timer is running; allow closing once 30s completes
    BackHandler {
        if (isTimerCompleted) {
            cleanupAdWebView(adWebViewRef)
            adWebViewRef = null
            onCloseAdAndEnterApp()
        } else {
            val wv = adWebViewRef
            if (wv != null && wv.canGoBack()) {
                wv.goBack()
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            cleanupAdWebView(adWebViewRef)
            adWebViewRef = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PureBlack)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .testTag("startup_smartlink_ad_screen")
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top bar with sponsor badge on left and 30s Timer / Cut (X) button in top-right corner
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DeepCharcoal)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(SpotifyVibrantGreen.copy(alpha = 0.16f))
                            .border(
                                width = 1.dp,
                                color = SpotifyVibrantGreen.copy(alpha = 0.45f),
                                shape = RoundedCornerShape(6.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "SPONSORED • #$ADSTERRA_SMARTLINK_ID",
                            color = SpotifyVibrantGreen,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = if (isTimerCompleted) "Ready to continue" else "Please wait ${remainingSeconds}s...",
                        color = MutedGray,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                // Top-right corner: 30s countdown timer -> Cut (X) button
                AnimatedContent(
                    targetState = isTimerCompleted,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "AdTimerOrCutButton"
                ) { canClose ->
                    if (canClose) {
                        Surface(
                            color = SpotifyVibrantGreen,
                            shape = RoundedCornerShape(50),
                            shadowElevation = 6.dp,
                            modifier = Modifier
                                .height(38.dp)
                                .clip(RoundedCornerShape(50))
                                .clickable {
                                    cleanupAdWebView(adWebViewRef)
                                    adWebViewRef = null
                                    onCloseAdAndEnterApp()
                                }
                                .testTag("startup_ad_cut_button")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close Ad",
                                    tint = PureBlack,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Cut",
                                    color = PureBlack,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .height(38.dp)
                                .clip(RoundedCornerShape(50))
                                .background( Color(0xFF1E1E1E))
                                .border(
                                    width = 1.dp,
                                    color = SpotifyVibrantGreen.copy(alpha = 0.55f),
                                    shape = RoundedCornerShape(50)
                                )
                                .padding(horizontal = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator(
                                    progress = {
                                        (remainingSeconds.toFloat() / STARTUP_AD_TIMER_SECONDS.toFloat()).coerceIn(0f, 1f)
                                    },
                                    modifier = Modifier.size(18.dp),
                                    color = SpotifyVibrantGreen,
                                    trackColor = Color.White.copy(alpha = 0.15f),
                                    strokeWidth = 2.2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "${remainingSeconds}s",
                                    color = PrimaryWhite,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.testTag("startup_ad_timer_text")
                                )
                            }
                        }
                    }
                }
            }

            if (isPageLoading) {
                LinearProgressIndicator(
                    progress = { (loadProgress / 100f).coerceIn(0.05f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                    color = SpotifyVibrantGreen,
                    trackColor = DeepCharcoal
                )
            }

            // Strictly In-App WebView (Never launches Chrome or external browsers!)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(PureBlack)
            ) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            setBackgroundColor(AndroidColor.BLACK)

                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                databaseEnabled = true
                                loadsImagesAutomatically = true
                                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                useWideViewPort = true
                                loadWithOverviewMode = true
                                cacheMode = WebSettings.LOAD_DEFAULT
                                mediaPlaybackRequiresUserGesture = true
                                javaScriptCanOpenWindowsAutomatically = false
                                // Intercept any target="_blank" or window.open inside the same WebView
                                setSupportMultipleWindows(true)
                                userAgentString =
                                    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                            }

                            try {
                                val cookieManager = CookieManager.getInstance()
                                cookieManager.setAcceptCookie(true)
                                cookieManager.setAcceptThirdPartyCookies(this, true)
                            } catch (_: Exception) {
                            }

                            // Prevent any external download prompt from opening external browser
                            setDownloadListener { _, _, _, _, _ ->
                                // Ignore file download triggers inside ad redirects
                            }

                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    loadProgress = newProgress
                                    if (newProgress >= 85) {
                                        isPageLoading = false
                                    }
                                }

                                override fun onCreateWindow(
                                    view: WebView?,
                                    isDialog: Boolean,
                                    isUserGesture: Boolean,
                                    resultMsg: Message?
                                ): Boolean {
                                    // Keep all popups / target="_blank" links strictly inside this in-app WebView
                                    val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                                    val tempWebView = WebView(ctx).apply {
                                        webViewClient = object : WebViewClient() {
                                            override fun shouldOverrideUrlLoading(
                                                v: WebView?,
                                                request: WebResourceRequest?
                                            ): Boolean {
                                                val targetUrl = request?.url?.toString().orEmpty()
                                                handleInAppAdUrl(view, targetUrl)
                                                try {
                                                    v?.destroy()
                                                } catch (_: Exception) {
                                                }
                                                return true
                                            }
                                        }
                                    }
                                    transport.webView = tempWebView
                                    resultMsg.sendToTarget()
                                    return true
                                }
                            }

                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    super.onPageStarted(view, url, favicon)
                                    isPageLoading = true
                                    injectInAppAdGuardJs(view)
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    isPageLoading = false
                                    injectInAppAdGuardJs(view)
                                }

                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): Boolean {
                                    val targetUrl = request?.url?.toString().orEmpty()
                                    return handleInAppAdUrl(view, targetUrl)
                                }

                                @Deprecated("Deprecated in Java")
                                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                                    return handleInAppAdUrl(view, url.orEmpty())
                                }

                                override fun onReceivedSslError(
                                    view: WebView?,
                                    handler: SslErrorHandler?,
                                    error: SslError?
                                ) {
                                    handler?.cancel()
                                    isPageLoading = false
                                }

                                override fun onReceivedError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    error: WebResourceError?
                                ) {
                                    super.onReceivedError(view, request, error)
                                    if (request?.isForMainFrame == true) {
                                        isPageLoading = false
                                    }
                                }
                            }

                            adWebViewRef = this
                            loadUrl(ADSTERRA_SMARTLINK_URL)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // Floating top-right close button overlay when 30s completes for effortless one-tap closing
                if (isTimerCompleted) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp)
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(PureBlack.copy(alpha = 0.82f))
                            .border(1.5.dp, SpotifyVibrantGreen, CircleShape)
                            .clickable {
                                cleanupAdWebView(adWebViewRef)
                                adWebViewRef = null
                                onCloseAdAndEnterApp()
                            }
                            .testTag("floating_startup_ad_cut_icon"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cut Ad",
                            tint = SpotifyVibrantGreen,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Ensures all Smartlink redirects stay 100% inside the app's WebView and NEVER launch
 * Chrome, Play Store, or any external Intent.
 */
private fun handleInAppAdUrl(webView: WebView?, rawUrl: String): Boolean {
    val trimmed = rawUrl.trim()
    if (trimmed.isEmpty()) return true

    val lower = trimmed.lowercase()
    if (lower.startsWith("http://") || lower.startsWith("https://")) {
        // Keep normal http/https navigation inside this WebView (unless it's an external Play Store redirect)
        if (lower.contains("play.google.com/store") || lower.contains("market.android.com")) {
            return true
        }
        return false
    }

    // If an ad redirect uses intent://..., extract its https fallback URL and load it inside this WebView
    if (lower.startsWith("intent://")) {
        try {
            val fallbackMarker = "S.browser_fallback_url="
            val idx = trimmed.indexOf(fallbackMarker)
            if (idx != -1) {
                val start = idx + fallbackMarker.length
                val end = trimmed.indexOf(';', start).let { if (it == -1) trimmed.length else it }
                val decodedFallback = Uri.decode(trimmed.substring(start, end))
                if (decodedFallback.startsWith("http://", ignoreCase = true) ||
                    decodedFallback.startsWith("https://", ignoreCase = true)
                ) {
                    webView?.loadUrl(decodedFallback)
                }
            }
        } catch (_: Exception) {
        }
        // Always return true so Android never launches Chrome or an external Activity
        return true
    }

    // Block all other external schemes (market://, tel:, mailto:, chrome://, etc.) from leaving the app
    return true
}

/**
 * Keeps window.open and target="_blank" links inside the same WebView and mutes any ad audio
 * so it never interferes with AADIFY music playback.
 */
private fun injectInAppAdGuardJs(webView: WebView?) {
    if (webView == null) return
    try {
        webView.evaluateJavascript(
            """
            (function() {
                try {
                    window.open = function(url) {
                        if (url && (url.indexOf('http://') === 0 || url.indexOf('https://') === 0)) {
                            window.location.href = url;
                        }
                        return window;
                    };
                    var links = document.querySelectorAll('a[target="_blank"]');
                    for (var i = 0; i < links.length; i++) {
                        links[i].setAttribute('target', '_self');
                    }
                    var mediaEls = document.querySelectorAll('video, audio');
                    for (var j = 0; j < mediaEls.length; j++) {
                        mediaEls[j].muted = true;
                        mediaEls[j].volume = 0;
                    }
                } catch(e) {}
            })();
            """.trimIndent(),
            null
        )
    } catch (_: Exception) {
    }
}

private fun cleanupAdWebView(webView: WebView?) {
    if (webView == null) return
    try {
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.stopLoading()
        webView.loadUrl("about:blank")
        webView.clearHistory()
        webView.destroy()
    } catch (_: Exception) {
    }
}
