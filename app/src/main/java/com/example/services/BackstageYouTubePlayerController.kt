package com.example.services

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.example.models.Song
import java.io.ByteArrayInputStream

/**
 * Custom WebView that always reports View.VISIBLE and focused to Chromium so HTML5 audio
 * decoding runs at full priority with zero throttling when covered by the Album Banner,
 * when minimized to the background, or when the device screen is turned off.
 */
class BackstageAudioWebView(context: Context) : WebView(context) {
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(View.VISIBLE)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, View.VISIBLE)
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(true)
    }
}

class BackstageYouTubePlayerController(
    private val context: Context,
    private val onProgressUpdate: (Long) -> Unit,
    private val onDurationUpdate: (Long) -> Unit,
    private val onStateChangeUpdate: (Int) -> Unit,
    private val onErrorUpdate: (Int) -> Unit
) {
    companion object {
        private const val TAG = "BackstageYTPlayer"
        const val STATE_UNSTARTED = -1
        const val STATE_ENDED = 0
        const val STATE_PLAYING = 1
        const val STATE_PAUSED = 2
        const val STATE_BUFFERING = 3
        const val STATE_CUED = 5
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var webView: BackstageAudioWebView? = null
    private var currentLoadedVideoId: String = ""
    private var currentPlaybackRate: Float = 1.0f
    private var userRequestedPause: Boolean = false
    private var isPlayerPageLoaded: Boolean = false

    @SuppressLint("SetJavaScriptEnabled")
    fun getOrCreateWebView(): BackstageAudioWebView {
        val existing = webView
        if (existing != null) {
            return existing
        }

        val created = BackstageAudioWebView(context.applicationContext).apply {
            setBackgroundColor(Color.BLACK)
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            isFocusable = false
            isFocusableInTouchMode = false
            keepScreenOn = true

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
                } catch (_: Exception) {
                }
            }

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                loadsImagesAutomatically = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                cacheMode = WebSettings.LOAD_DEFAULT
                useWideViewPort = true
                loadWithOverviewMode = true
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                userAgentString =
                    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
            }

            try {
                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(this, true)
            } catch (_: Exception) {
            }

            addJavascriptInterface(AndroidBridge(), "AndroidBridge")

            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                    return true
                }
            }

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val url = request?.url?.toString()?.lowercase() ?: return null
                    // Block ad networks, telemetry, and heavy channel avatars so 100% of bandwidth
                    // goes straight to the audio stream for instant, zero-buffer playback!
                    if (url.contains("doubleclick.net") ||
                        url.contains("googlesyndication.com") ||
                        url.contains("googleadservices.com") ||
                        url.contains("/youtubei/v1/log_event") ||
                        url.contains("/api/stats/ads") ||
                        url.contains("/pagead/") ||
                        url.contains("yt3.ggpht.com")
                    ) {
                        return WebResourceResponse(
                            "text/plain",
                            "utf-8",
                            ByteArrayInputStream(ByteArray(0))
                        )
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    injectVideoBridgeScript()
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (url != null && url.contains("youtube.com/watch")) {
                        isPlayerPageLoaded = true
                    }
                    injectVideoBridgeScript()
                }

                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                    super.doUpdateVisitedHistory(view, url, isReload)
                    injectVideoBridgeScript()
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    val reqUrl = request?.url?.toString() ?: return false
                    return !(reqUrl.contains("youtube.com") ||
                            reqUrl.contains("youtu.be") ||
                            reqUrl.contains("google.com") ||
                            reqUrl.contains("googlevideo.com"))
                }
            }

            resumeTimers()
        }

        webView = created
        return created
    }

    /**
     * Pre-warms YouTube connection & player runtime at app launch so the very first song
     * clicked by the user starts playing almost instantaneously.
     */
    fun preWarmEngine() {
        mainHandler.post {
            val view = getOrCreateWebView()
            if (currentLoadedVideoId.isEmpty() && view.url.isNullOrBlank()) {
                view.loadUrl("https://m.youtube.com/?hl=en")
            }
        }
    }

    fun attachToContainer(container: ViewGroup) {
        val view = getOrCreateWebView()
        val currentParent = view.parent as? ViewGroup
        if (currentParent !== container) {
            currentParent?.removeView(view)
            container.removeAllViews()
            container.addView(
                view,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            view.onResume()
            view.resumeTimers()
            if (!userRequestedPause && currentLoadedVideoId.isNotBlank()) {
                mainHandler.postDelayed({ playVideo() }, 50L)
            }
        }
    }

    fun loadVideoById(rawVideoId: String) {
        val cleanId = Song.extractYoutubeVideoId(rawVideoId)
        if (cleanId.isBlank()) {
            onErrorUpdate(-1)
            return
        }
        currentLoadedVideoId = cleanId
        userRequestedPause = false

        mainHandler.post {
            val view = getOrCreateWebView()
            view.onResume()
            view.resumeTimers()

            val currentUrl = view.url.orEmpty()
            // Fast SPA Track Switch: If #movie_player is already loaded on m.youtube.com/watch,
            // switch video via player API in 0ms without a full page reload!
            if (isPlayerPageLoaded && currentUrl.contains("youtube.com/watch")) {
                view.evaluateJavascript(
                    """
                    (function() {
                        try {
                            window.__akUserPaused = false;
                            window.__akTargetVideoId = '$cleanId';
                            var mp = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
                            if (mp && typeof mp.loadVideoById === 'function') {
                                mp.loadVideoById('$cleanId', 0, 'tiny');
                                if (typeof mp.setPlaybackQualityRange === 'function') {
                                    mp.setPlaybackQualityRange('tiny', 'tiny');
                                }
                                if (typeof mp.playVideo === 'function') {
                                    mp.playVideo();
                                }
                                return 'SPA_OK';
                            }
                        } catch (e) {}
                        return 'RELOAD';
                    })();
                    """.trimIndent()
                ) { result ->
                    if (result == null || !result.contains("SPA_OK")) {
                        val watchUrl = "https://m.youtube.com/watch?v=$cleanId&autoplay=1"
                        view.loadUrl(watchUrl)
                    } else {
                        injectVideoBridgeScript()
                    }
                }
            } else {
                val watchUrl = "https://m.youtube.com/watch?v=$cleanId&autoplay=1"
                view.loadUrl(watchUrl)
            }
        }
    }

    fun playVideo() {
        userRequestedPause = false
        mainHandler.post {
            webView?.onResume()
            webView?.resumeTimers()
            evaluateJs(
                """
                (function() {
                    window.__akUserPaused = false;
                    var mp = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
                    if (mp && typeof mp.playVideo === 'function') {
                        mp.playVideo();
                    }
                    var v = document.querySelector('video');
                    if (v) {
                        v.muted = false;
                        v.volume = 1.0;
                        if (v.paused) {
                            var p = v.play();
                            if (p && typeof p.catch === 'function') { p.catch(function(){}); }
                        }
                    }
                })();
                """.trimIndent()
            )
        }
    }

    fun pauseVideo() {
        userRequestedPause = true
        mainHandler.post {
            evaluateJs(
                """
                (function() {
                    window.__akUserPaused = true;
                    var mp = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
                    if (mp && typeof mp.pauseVideo === 'function') {
                        mp.pauseVideo();
                    }
                    var v = document.querySelector('video');
                    if (v && !v.paused) {
                        v.pause();
                    }
                })();
                """.trimIndent()
            )
        }
    }

    fun seekToSeconds(targetSeconds: Double) {
        val clamped = targetSeconds.coerceAtLeast(0.0)
        mainHandler.post {
            evaluateJs(
                """
                (function() {
                    var mp = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
                    if (mp && typeof mp.seekTo === 'function') {
                        mp.seekTo($clamped, true);
                    }
                    var v = document.querySelector('video');
                    if (v) {
                        v.currentTime = $clamped;
                        if (!window.__akUserPaused && v.paused) {
                            var p = v.play();
                            if (p && typeof p.catch === 'function') { p.catch(function(){}); }
                        }
                    }
                })();
                """.trimIndent()
            )
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        currentPlaybackRate = speed
        mainHandler.post {
            evaluateJs(
                """
                (function() {
                    window.__akPlaybackRate = $speed;
                    var mp = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
                    if (mp && typeof mp.setPlaybackRate === 'function') {
                        mp.setPlaybackRate($speed);
                    }
                    var v = document.querySelector('video');
                    if (v) {
                        v.playbackRate = $speed;
                    }
                })();
                """.trimIndent()
            )
        }
    }

    private fun injectVideoBridgeScript() {
        val pausedFlag = if (userRequestedPause) "true" else "false"
        val rate = currentPlaybackRate
        val script = """
            (function() {
                window.__akUserPaused = $pausedFlag;
                window.__akPlaybackRate = $rate;

                // 1. Spoof Page Visibility API so background / screen-off never pauses audio
                try {
                    Object.defineProperty(document, 'hidden', { get: function() { return false; }, configurable: true });
                    Object.defineProperty(document, 'webkitHidden', { get: function() { return false; }, configurable: true });
                    Object.defineProperty(document, 'visibilityState', { get: function() { return 'visible'; }, configurable: true });
                    Object.defineProperty(document, 'webkitVisibilityState', { get: function() { return 'visible'; }, configurable: true });
                    document.hasFocus = function() { return true; };
                } catch (e) {}

                // 2. Lock YouTube player to 'tiny' (144p) video quality so 95% of bandwidth goes to crystal-clear audio!
                function optimizePlayerBandwidth() {
                    try {
                        var mp = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
                        if (mp) {
                            if (typeof mp.setPlaybackQualityRange === 'function') {
                                mp.setPlaybackQualityRange('tiny', 'tiny');
                            }
                            if (typeof mp.setPlaybackQuality === 'function') {
                                mp.setPlaybackQuality('tiny');
                            }
                            if (typeof mp.unMute === 'function') {
                                mp.unMute();
                            }
                            if (typeof mp.setVolume === 'function') {
                                mp.setVolume(100);
                            }
                        }
                    } catch (e) {}
                }
                optimizePlayerBandwidth();

                if (window.__akBridgeInstalled) return;
                window.__akBridgeInstalled = true;
                window.__akLastPlayAttempt = 0;

                window.addEventListener('visibilitychange', function(e) { e.stopImmediatePropagation(); }, true);
                window.addEventListener('webkitvisibilitychange', function(e) { e.stopImmediatePropagation(); }, true);
                document.addEventListener('visibilitychange', function(e) { e.stopImmediatePropagation(); }, true);
                window.addEventListener('pagehide', function(e) { e.stopImmediatePropagation(); }, true);
                window.addEventListener('blur', function(e) { e.stopImmediatePropagation(); }, true);

                function safePlayVideo(v) {
                    if (!v || window.__akUserPaused || !v.paused || v.ended) return;
                    var now = Date.now();
                    if (now - window.__akLastPlayAttempt < 1100) return;
                    window.__akLastPlayAttempt = now;

                    v.muted = false;
                    v.volume = 1.0;
                    var p = v.play();
                    if (p && typeof p.catch === 'function') {
                        p.catch(function() {
                            var mp = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
                            if (mp && typeof mp.playVideo === 'function') {
                                mp.playVideo();
                            } else {
                                var btn = document.querySelector('.ytp-large-play-button, .ytp-play-button, button.player-control-play-pause-icon');
                                if (btn) { btn.click(); }
                            }
                        });
                    }
                }

                function hookVideoElement(v) {
                    if (!v || v.__akHooked) return;
                    v.__akHooked = true;
                    v.setAttribute('playsinline', 'true');
                    v.setAttribute('webkit-playsinline', 'true');
                    v.setAttribute('preload', 'auto');
                    v.preload = 'auto';
                    v.playsInline = true;
                    v.muted = false;
                    v.volume = 1.0;
                    if (window.__akPlaybackRate) {
                        v.playbackRate = window.__akPlaybackRate;
                    }

                    optimizePlayerBandwidth();

                    v.addEventListener('canplay', function() {
                        optimizePlayerBandwidth();
                        if (!window.__akUserPaused && v.paused) {
                            safePlayVideo(v);
                        }
                    });

                    v.addEventListener('playing', function() {
                        optimizePlayerBandwidth();
                        if (window.AndroidBridge) {
                            window.AndroidBridge.onStateChange(1);
                            if (v.duration && v.duration > 0) {
                                window.AndroidBridge.onDuration(v.duration);
                            }
                        }
                    });

                    v.addEventListener('play', function() {
                        if (window.AndroidBridge) {
                            window.AndroidBridge.onStateChange(1);
                        }
                    });

                    v.addEventListener('pause', function() {
                        if (window.__akUserPaused) {
                            if (window.AndroidBridge) {
                                window.AndroidBridge.onStateChange(2);
                            }
                        } else if (!v.ended && v.readyState >= 2) {
                            setTimeout(function() {
                                if (!window.__akUserPaused && v.paused && !v.ended) {
                                    safePlayVideo(v);
                                }
                            }, 150);
                        }
                    });

                    v.addEventListener('waiting', function() {
                        if (window.AndroidBridge) {
                            window.AndroidBridge.onStateChange(3);
                        }
                    });

                    v.addEventListener('loadedmetadata', function() {
                        optimizePlayerBandwidth();
                        if (v.duration && v.duration > 0 && window.AndroidBridge) {
                            window.AndroidBridge.onDuration(v.duration);
                        }
                    });

                    v.addEventListener('durationchange', function() {
                        if (v.duration && v.duration > 0 && window.AndroidBridge) {
                            window.AndroidBridge.onDuration(v.duration);
                        }
                    });

                    v.addEventListener('timeupdate', function() {
                        if (!window.AndroidBridge) return;
                        var isAd = !!document.querySelector('.ad-showing, .ad-interrupting');
                        if (isAd) {
                            var skipBtn = document.querySelector('.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button');
                            if (skipBtn) { skipBtn.click(); }
                            else if (v.duration && v.duration > 0 && v.duration < 120) {
                                v.currentTime = v.duration;
                            }
                            return;
                        }
                        if (v.currentTime >= 0) {
                            window.AndroidBridge.onProgress(v.currentTime);
                        }
                        if (v.duration && v.duration > 0) {
                            window.AndroidBridge.onDuration(v.duration);
                        }
                        if (!v.paused && v.readyState >= 2) {
                            window.AndroidBridge.onStateChange(1);
                        }
                    });

                    v.addEventListener('ended', function() {
                        var isAd = !!document.querySelector('.ad-showing, .ad-interrupting');
                        if (!isAd && window.AndroidBridge) {
                            window.AndroidBridge.onStateChange(0);
                        }
                    });
                }

                setInterval(function() {
                    try {
                        var v = document.querySelector('video');
                        if (!v) return;
                        hookVideoElement(v);

                        var isAd = !!document.querySelector('.ad-showing, .ad-interrupting');
                        if (isAd) {
                            var skipBtn = document.querySelector('.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button');
                            if (skipBtn) { skipBtn.click(); }
                            else if (v.duration && v.duration > 0 && v.duration < 120) {
                                v.currentTime = v.duration;
                            }
                            return;
                        }

                        if (v.muted) {
                            v.muted = false;
                            v.volume = 1.0;
                        }

                        // Only trigger play if readyState >= 2 (enough data buffered) or initial unstarted state,
                        // respecting the 1100ms cooldown so we never interrupt Chromium's audio pre-buffer!
                        if (!window.__akUserPaused && v.paused && !v.ended) {
                            safePlayVideo(v);
                        }

                        if (!v.paused && v.currentTime > 0 && window.AndroidBridge) {
                            window.AndroidBridge.onStateChange(1);
                            window.AndroidBridge.onProgress(v.currentTime);
                            if (v.duration && v.duration > 0) {
                                window.AndroidBridge.onDuration(v.duration);
                            }
                        }
                    } catch (e) {}
                }, 350);
            })();
        """.trimIndent()
        evaluateJs(script)
    }

    private fun evaluateJs(script: String) {
        try {
            webView?.evaluateJavascript(script, null)
        } catch (e: Exception) {
            Log.e(TAG, "JS evaluation error: ${e.message}")
        }
    }

    fun release() {
        mainHandler.post {
            try {
                webView?.apply {
                    (parent as? ViewGroup)?.removeView(this)
                    stopLoading()
                    destroy()
                }
            } catch (_: Exception) {
            }
            webView = null
        }
    }

    inner class AndroidBridge {
        @JavascriptInterface
        fun onReady() {
            mainHandler.post {
                injectVideoBridgeScript()
            }
        }

        @JavascriptInterface
        fun onProgress(currentTimeSeconds: Double) {
            if (currentTimeSeconds.isNaN() || currentTimeSeconds < 0.0) return
            val posMs = (currentTimeSeconds * 1000.0).toLong()
            mainHandler.post {
                onProgressUpdate(posMs)
            }
        }

        @JavascriptInterface
        fun onDuration(durationSeconds: Double) {
            if (durationSeconds.isNaN() || durationSeconds <= 0.0) return
            val durMs = (durationSeconds * 1000.0).toLong()
            mainHandler.post {
                onDurationUpdate(durMs)
            }
        }

        @JavascriptInterface
        fun onStateChange(state: Int) {
            mainHandler.post {
                onStateChangeUpdate(state)
            }
        }

        @JavascriptInterface
        fun onError(errorCode: Int) {
            Log.w(TAG, "Backstage player error code: $errorCode for $currentLoadedVideoId")
            mainHandler.post {
                onErrorUpdate(errorCode)
            }
        }
    }
}
