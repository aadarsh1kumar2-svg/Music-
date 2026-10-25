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
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.example.models.Song

/**
 * Custom WebView that always reports View.VISIBLE and focused to Chromium so HTML5 audio
 * decoding runs at full priority with zero throttling when covered by the UI,
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
    private val onErrorUpdate: (Int) -> Unit,
    private val onMediaButtonAction: ((String) -> Unit)? = null
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

    @Volatile
    var isUserPaused: Boolean = false
        private set

    @Volatile
    private var hasFiredEndedForCurrentTrack: Boolean = false

    @Volatile
    private var hasFiredErrorForCurrentTrack: Boolean = false

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
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    if (currentLoadedVideoId.isNotBlank()) {
                        injectVideoBridgeScript()
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (currentLoadedVideoId.isNotBlank()) {
                        injectVideoBridgeScript()
                    }
                }

                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                    super.doUpdateVisitedHistory(view, url, isReload)
                    val targetId = currentLoadedVideoId
                    if (targetId.isBlank()) return
                    if (url != null && url.contains("youtube.com/watch") && !url.contains(targetId)) {
                        if (!hasFiredEndedForCurrentTrack) {
                            hasFiredEndedForCurrentTrack = true
                            mainHandler.post {
                                onStateChangeUpdate(STATE_ENDED)
                            }
                        }
                        return
                    }
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
                            reqUrl.contains("googlevideo.com") ||
                            reqUrl.startsWith("about:blank"))
                }
            }

            resumeTimers()
        }

        webView = created
        return created
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
        }
    }

    fun keepAwake() {
        mainHandler.post {
            try {
                webView?.onResume()
                webView?.resumeTimers()
                if (!isUserPaused && currentLoadedVideoId.isNotBlank() && !hasFiredEndedForCurrentTrack) {
                    playVideo()
                }
            } catch (_: Exception) {
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
        isUserPaused = false
        hasFiredEndedForCurrentTrack = false
        hasFiredErrorForCurrentTrack = false

        mainHandler.post {
            val view = getOrCreateWebView()
            view.onResume()
            view.resumeTimers()
            val watchUrl = "https://m.youtube.com/watch?v=$cleanId&autoplay=1"
            view.loadUrl(watchUrl)
        }
    }

    /**
     * Completely stops and unloads the WebView media player so that when the user plays a
     * local device song or offline downloaded song (even while internet is ON), the WebView
     * releases all audio focus and stops all background intervals.
     */
    fun stopAndClear() {
        isUserPaused = true
        currentLoadedVideoId = ""
        hasFiredEndedForCurrentTrack = true
        mainHandler.post {
            try {
                evaluateJs(
                    """
                    (function() {
                        window.__akUserPaused = true;
                        window.__akTrackEndedFired = true;
                        var v = document.querySelector('video');
                        if (v) {
                            try { v.pause(); } catch(e) {}
                            try { v.removeAttribute('src'); v.load(); } catch(e) {}
                        }
                    })();
                    """.trimIndent()
                )
                webView?.stopLoading()
                webView?.loadUrl("about:blank")
            } catch (_: Exception) {
            }
        }
    }

    fun playVideo() {
        if (currentLoadedVideoId.isBlank()) return
        isUserPaused = false
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
                        if (v.paused && !window.__akTrackEndedFired) {
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
        isUserPaused = true
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
        if (currentLoadedVideoId.isBlank()) return
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
        if (currentLoadedVideoId.isBlank()) return
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
        val targetId = currentLoadedVideoId
        if (targetId.isBlank()) return
        val pausedFlag = if (isUserPaused) "true" else "false"
        val rate = currentPlaybackRate
        val script = """
            (function() {
                window.__akUserPaused = $pausedFlag;
                window.__akPlaybackRate = $rate;
                if (window.__akTargetVideoId !== '$targetId') {
                    window.__akTargetVideoId = '$targetId';
                    window.__akTrackEndedFired = false;
                    window.__akErrorFired = false;
                    window.__akLastPlayAttempt = 0;
                }

                // Spoof Page Visibility API so background / screen-off never pauses audio
                try {
                    Object.defineProperty(document, 'hidden', { get: function() { return false; }, configurable: true });
                    Object.defineProperty(document, 'webkitHidden', { get: function() { return false; }, configurable: true });
                    Object.defineProperty(document, 'visibilityState', { get: function() { return 'visible'; }, configurable: true });
                    Object.defineProperty(document, 'webkitVisibilityState', { get: function() { return 'visible'; }, configurable: true });
                    document.hasFocus = function() { return true; };
                } catch (e) {}

                // Route any WebView navigator.mediaSession actions directly to AndroidBridge
                // so Lock Screen and Notification buttons work 100% seamlessly!
                try {
                    if (navigator.mediaSession && typeof navigator.mediaSession.setActionHandler === 'function') {
                        navigator.mediaSession.setActionHandler('play', function() {
                            if (window.AndroidBridge) window.AndroidBridge.onMediaAction('play');
                        });
                        navigator.mediaSession.setActionHandler('pause', function() {
                            if (window.AndroidBridge) window.AndroidBridge.onMediaAction('pause');
                        });
                        navigator.mediaSession.setActionHandler('previoustrack', function() {
                            if (window.AndroidBridge) window.AndroidBridge.onMediaAction('prev');
                        });
                        navigator.mediaSession.setActionHandler('nexttrack', function() {
                            if (window.AndroidBridge) window.AndroidBridge.onMediaAction('next');
                        });
                        navigator.mediaSession.setActionHandler('stop', function() {
                            if (window.AndroidBridge) window.AndroidBridge.onMediaAction('pause');
                        });
                    }
                } catch (e) {}

                // Disable YouTube's built-in Up Next / Auto-navigation so our app controls the queue!
                try {
                    var autonavToggle = document.querySelector('.ytm-autonav-toggle-button-container button[aria-pressed="true"], .ytp-autonav-toggle-button[aria-checked="true"]');
                    if (autonavToggle) { autonavToggle.click(); }
                } catch (e) {}

                if (window.__akBridgeInstalled) return;
                window.__akBridgeInstalled = true;

                window.addEventListener('visibilitychange', function(e) { e.stopImmediatePropagation(); }, true);
                window.addEventListener('webkitvisibilitychange', function(e) { e.stopImmediatePropagation(); }, true);
                document.addEventListener('visibilitychange', function(e) { e.stopImmediatePropagation(); }, true);
                window.addEventListener('pagehide', function(e) { e.stopImmediatePropagation(); }, true);
                window.addEventListener('blur', function(e) { e.stopImmediatePropagation(); }, true);

                function notifyTrackEnded(v) {
                    if (window.__akTrackEndedFired) return;
                    window.__akTrackEndedFired = true;
                    window.__akUserPaused = true;
                    try {
                        var mp = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
                        if (mp && typeof mp.pauseVideo === 'function') { mp.pauseVideo(); }
                        if (v && !v.paused) { v.pause(); }
                    } catch (e) {}
                    if (window.AndroidBridge) {
                        window.AndroidBridge.onStateChange(0);
                    }
                }

                function tryPlayVideo(v) {
                    if (!v || window.__akUserPaused || window.__akTrackEndedFired || !v.paused || v.ended) return;
                    if (v.duration && v.duration > 8 && v.currentTime >= v.duration - 1.0) {
                        notifyTrackEnded(v);
                        return;
                    }
                    var now = Date.now();
                    if (now - (window.__akLastPlayAttempt || 0) < 650) return;
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
                                var largeBtn = document.querySelector('.ytp-large-play-button, button.ytp-large-play-button');
                                if (largeBtn) { largeBtn.click(); }
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
                    v.loop = false;
                    v.removeAttribute('loop');
                    v.muted = false;
                    v.volume = 1.0;
                    if (window.__akPlaybackRate) {
                        v.playbackRate = window.__akPlaybackRate;
                    }

                    v.addEventListener('error', function() {
                        if (!window.__akErrorFired && window.AndroidBridge) {
                            window.__akErrorFired = true;
                            window.AndroidBridge.onError(150);
                        }
                    });

                    v.addEventListener('canplay', function() {
                        if (!window.__akUserPaused && !window.__akTrackEndedFired && v.paused) {
                            tryPlayVideo(v);
                        }
                    });

                    v.addEventListener('playing', function() {
                        if (window.__akUserPaused || window.__akTrackEndedFired) {
                            v.pause();
                            return;
                        }
                        if (window.AndroidBridge) {
                            window.AndroidBridge.onStateChange(1);
                            if (v.duration && v.duration > 0) {
                                window.AndroidBridge.onDuration(v.duration);
                            }
                        }
                    });

                    v.addEventListener('play', function() {
                        if (window.__akUserPaused || window.__akTrackEndedFired) {
                            v.pause();
                            return;
                        }
                        if (window.AndroidBridge) {
                            window.AndroidBridge.onStateChange(1);
                        }
                    });

                    v.addEventListener('pause', function() {
                        var isAd = !!document.querySelector('.ad-showing, .ad-interrupting');
                        if (!isAd && v.duration && v.duration > 8 && v.currentTime >= v.duration - 1.2) {
                            notifyTrackEnded(v);
                            return;
                        }
                        if (window.__akUserPaused) {
                            if (window.AndroidBridge) {
                                window.AndroidBridge.onStateChange(2);
                            }
                        } else if (!window.__akTrackEndedFired && !v.ended) {
                            setTimeout(function() {
                                if (!window.__akUserPaused && !window.__akTrackEndedFired && v.paused && !v.ended) {
                                    tryPlayVideo(v);
                                }
                            }, 180);
                        }
                    });

                    v.addEventListener('waiting', function() {
                        if (!window.__akUserPaused && !window.__akTrackEndedFired && window.AndroidBridge) {
                            window.AndroidBridge.onStateChange(3);
                        }
                    });

                    v.addEventListener('loadedmetadata', function() {
                        v.loop = false;
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
                        if (!window.AndroidBridge || window.__akTrackEndedFired) return;
                        var isAd = !!document.querySelector('.ad-showing, .ad-interrupting');
                        if (isAd) {
                            var skipBtn = document.querySelector('.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button');
                            if (skipBtn) { skipBtn.click(); }
                            else if (v.duration && v.duration > 0 && v.duration < 120) {
                                v.currentTime = v.duration;
                            }
                            return;
                        }
                        if (v.duration && v.duration > 8 && v.currentTime >= v.duration - 0.65) {
                            notifyTrackEnded(v);
                            return;
                        }
                        if (v.currentTime >= 0) {
                            window.AndroidBridge.onProgress(v.currentTime);
                        }
                        if (v.duration && v.duration > 0) {
                            window.AndroidBridge.onDuration(v.duration);
                        }
                    });

                    v.addEventListener('ended', function() {
                        var isAd = !!document.querySelector('.ad-showing, .ad-interrupting');
                        if (!isAd) {
                            notifyTrackEnded(v);
                        }
                    });
                }

                setInterval(function() {
                    try {
                        var errEl = document.querySelector('.ytm-player-error-message-renderer, .player-error-message-container');
                        if (errEl && !window.__akErrorFired && window.AndroidBridge) {
                            window.__akErrorFired = true;
                            window.AndroidBridge.onError(150);
                            return;
                        }

                        var v = document.querySelector('video');
                        if (!v) return;
                        hookVideoElement(v);
                        if (v.loop) { v.loop = false; }

                        var isAd = !!document.querySelector('.ad-showing, .ad-interrupting');
                        if (isAd) {
                            var skipBtn = document.querySelector('.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button');
                            if (skipBtn) { skipBtn.click(); }
                            else if (v.duration && v.duration > 0 && v.duration < 120) {
                                v.currentTime = v.duration;
                            }
                            return;
                        }

                        if (!window.__akTrackEndedFired && v.duration && v.duration > 8 && v.currentTime >= v.duration - 0.65) {
                            notifyTrackEnded(v);
                            return;
                        }

                        if (v.muted) {
                            v.muted = false;
                            v.volume = 1.0;
                        }

                        var unmuteBtn = document.querySelector('.ytp-unmute, button.ytm-autonav-toggle-button');
                        if (unmuteBtn && unmuteBtn.classList && unmuteBtn.classList.contains('ytp-unmute') && unmuteBtn.offsetParent !== null) {
                            unmuteBtn.click();
                        }

                        if (!window.__akUserPaused && !window.__akTrackEndedFired && v.paused && !v.ended) {
                            tryPlayVideo(v);
                        }

                        if (!window.__akUserPaused && !window.__akTrackEndedFired && !v.paused && v.currentTime > 0 && window.AndroidBridge) {
                            window.AndroidBridge.onProgress(v.currentTime);
                            if (v.duration && v.duration > 0) {
                                window.AndroidBridge.onDuration(v.duration);
                            }
                        }
                    } catch (e) {}
                }, 300);
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
            if (currentTimeSeconds.isNaN() || currentTimeSeconds < 0.0 || hasFiredEndedForCurrentTrack || currentLoadedVideoId.isBlank()) return
            val posMs = (currentTimeSeconds * 1000.0).toLong()
            mainHandler.post {
                if (!hasFiredEndedForCurrentTrack && currentLoadedVideoId.isNotBlank()) {
                    onProgressUpdate(posMs)
                }
            }
        }

        @JavascriptInterface
        fun onDuration(durationSeconds: Double) {
            if (durationSeconds.isNaN() || durationSeconds <= 0.0 || currentLoadedVideoId.isBlank()) return
            val durMs = (durationSeconds * 1000.0).toLong()
            mainHandler.post {
                if (currentLoadedVideoId.isNotBlank()) {
                    onDurationUpdate(durMs)
                }
            }
        }

        @JavascriptInterface
        fun onStateChange(state: Int) {
            mainHandler.post {
                if (currentLoadedVideoId.isBlank()) return@post
                if (state == STATE_ENDED) {
                    if (hasFiredEndedForCurrentTrack) return@post
                    hasFiredEndedForCurrentTrack = true
                    onStateChangeUpdate(STATE_ENDED)
                    return@post
                }
                if (hasFiredEndedForCurrentTrack) return@post
                if (isUserPaused && state == STATE_PLAYING) {
                    pauseVideo()
                    return@post
                }
                onStateChangeUpdate(state)
            }
        }

        @JavascriptInterface
        fun onError(errorCode: Int) {
            if (currentLoadedVideoId.isBlank() || hasFiredErrorForCurrentTrack) return
            hasFiredErrorForCurrentTrack = true
            Log.w(TAG, "Backstage player error code: $errorCode for $currentLoadedVideoId")
            mainHandler.post {
                onErrorUpdate(errorCode)
            }
        }

        @JavascriptInterface
        fun onMediaAction(action: String) {
            mainHandler.post {
                onMediaButtonAction?.invoke(action)
            }
        }
    }
}
