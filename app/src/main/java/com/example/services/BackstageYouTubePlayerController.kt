package com.example.services

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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

    @Volatile
    private var currentLoadedVideoId: String = ""

    @Volatile
    private var currentLoadToken: Int = 0

    @Volatile
    private var currentPlaybackRate: Float = 1.0f

    @Volatile
    var isUserPaused: Boolean = false
        private set

    @Volatile
    private var hasFiredEndedForCurrentTrack: Boolean = false

    @Volatile
    private var hasFiredErrorForCurrentTrack: Boolean = false

    @Volatile
    private var hasSeenValidStartForCurrentToken: Boolean = false

    @Volatile
    private var lastLoadTimestampMs: Long = 0L

    /**
     * Lightweight native heartbeat on Android's Main Looper every 600ms.
     * Keeps WebView timers awake in background and executes window.__akTick() without
     * ever interrupting or resetting an already-playing <video> element.
     */
    private val nativeHeartbeatRunnable = object : Runnable {
        override fun run() {
            mainHandler.removeCallbacks(this)
            val targetId = currentLoadedVideoId
            if (targetId.isBlank() || hasFiredEndedForCurrentTrack) {
                return
            }
            try {
                webView?.onResume()
                webView?.resumeTimers()
                val token = currentLoadToken
                val pausedFlag = if (isUserPaused) "true" else "false"
                val rate = currentPlaybackRate
                webView?.evaluateJavascript(
                    """
                    (function() {
                        window.__akUserPaused = $pausedFlag;
                        window.__akPlaybackRate = $rate;
                        if (window.__akLoadToken !== $token || window.__akTargetVideoId !== '$targetId') {
                            window.__akLoadToken = $token;
                            window.__akTargetVideoId = '$targetId';
                            window.__akTrackEndedFired = false;
                            window.__akErrorFired = false;
                            window.__akHasSeenStartOfTrack = false;
                            window.__akMaxDurationForTrack = 0;
                        }
                        if (typeof window.__akTick === 'function') {
                            window.__akTick();
                            return 'OK';
                        }
                        return 'NEEDS_BRIDGE';
                    })();
                    """.trimIndent()
                ) { res ->
                    if (res?.contains("NEEDS_BRIDGE") == true && currentLoadedVideoId.isNotBlank()) {
                        injectVideoBridgeScript(currentLoadToken, currentLoadedVideoId)
                    }
                }
            } catch (_: Exception) {
            }
            if (currentLoadedVideoId.isNotBlank() && !hasFiredEndedForCurrentTrack) {
                mainHandler.postDelayed(this, 600L)
            }
        }
    }

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
                    val targetId = currentLoadedVideoId
                    val token = currentLoadToken
                    if (targetId.isNotBlank()) {
                        injectVideoBridgeScript(token, targetId)
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    val targetId = currentLoadedVideoId
                    val token = currentLoadToken
                    if (targetId.isNotBlank()) {
                        injectVideoBridgeScript(token, targetId)
                    }
                }

                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                    super.doUpdateVisitedHistory(view, url, isReload)
                    val targetId = currentLoadedVideoId
                    val token = currentLoadToken
                    if (targetId.isBlank()) return
                    injectVideoBridgeScript(token, targetId)
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

    /**
     * Synchronously invalidates the previous track token BEFORE any async database/network checks
     * in MusicViewModel so trailing progress/ended callbacks from the previous song are 100% ignored.
     */
    fun prepareForNewTrack() {
        currentLoadToken++
        currentLoadedVideoId = ""
        hasFiredEndedForCurrentTrack = true
        hasFiredErrorForCurrentTrack = false
        hasSeenValidStartForCurrentToken = false
        mainHandler.removeCallbacks(nativeHeartbeatRunnable)
    }

    /**
     * Keeps WebView timers and renderer active in background WITHOUT calling mp.playVideo()
     * on an already-playing video.
     */
    fun keepAwake() {
        mainHandler.post {
            try {
                webView?.onResume()
                webView?.resumeTimers()
                if (!isUserPaused && currentLoadedVideoId.isNotBlank() && !hasFiredEndedForCurrentTrack) {
                    evaluateJs(
                        """
                        (function() {
                            window.__akUserPaused = false;
                            var v = document.querySelector('video');
                            if (v && v.paused && !window.__akTrackEndedFired && !v.ended) {
                                v.muted = false;
                                v.volume = 1.0;
                                var p = v.play();
                                if (p && typeof p.catch === 'function') { p.catch(function(){}); }
                            }
                        })();
                        """.trimIndent()
                    )
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
        val token = ++currentLoadToken
        currentLoadedVideoId = cleanId
        isUserPaused = false
        hasFiredEndedForCurrentTrack = false
        hasFiredErrorForCurrentTrack = false
        hasSeenValidStartForCurrentToken = false
        lastLoadTimestampMs = SystemClock.elapsedRealtime()

        mainHandler.post {
            val view = getOrCreateWebView()
            view.onResume()
            view.resumeTimers()

            // Immediately disarm any old <video> on the previous page before loading the new URL
            evaluateJs(
                """
                (function() {
                    window.__akLoadToken = $token;
                    window.__akTargetVideoId = '$cleanId';
                    window.__akUserPaused = false;
                    window.__akTrackEndedFired = false;
                    window.__akErrorFired = false;
                    window.__akHasSeenStartOfTrack = false;
                    window.__akMaxDurationForTrack = 0;
                    var v = document.querySelector('video');
                    if (v) {
                        try { v.currentTime = 0; } catch(e) {}
                    }
                })();
                """.trimIndent()
            )

            val watchUrl = "https://m.youtube.com/watch?v=$cleanId&autoplay=1"
            view.loadUrl(watchUrl)

            mainHandler.removeCallbacks(nativeHeartbeatRunnable)
            mainHandler.postDelayed(nativeHeartbeatRunnable, 500L)
        }
    }

    /**
     * Completely stops and unloads the WebView media player ONLY when the user switches to a
     * local device song or offline downloaded song.
     */
    fun stopAndClear() {
        currentLoadToken++
        isUserPaused = true
        currentLoadedVideoId = ""
        hasFiredEndedForCurrentTrack = true
        hasSeenValidStartForCurrentToken = false
        mainHandler.removeCallbacks(nativeHeartbeatRunnable)
        mainHandler.post {
            try {
                evaluateJs(
                    """
                    (function() {
                        window.__akUserPaused = true;
                        window.__akTrackEndedFired = true;
                        window.__akTargetVideoId = '';
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
                    var v = document.querySelector('video');
                    if (v) {
                        v.muted = false;
                        v.volume = 1.0;
                        if (v.paused && !window.__akTrackEndedFired) {
                            var mp = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
                            if (mp && typeof mp.playVideo === 'function') {
                                try { mp.playVideo(); } catch(e) {}
                            }
                            var p = v.play();
                            if (p && typeof p.catch === 'function') { p.catch(function(){}); }
                        }
                    }
                })();
                """.trimIndent()
            )
            mainHandler.removeCallbacks(nativeHeartbeatRunnable)
            mainHandler.postDelayed(nativeHeartbeatRunnable, 500L)
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
                        try { mp.pauseVideo(); } catch(e) {}
                    }
                    var v = document.querySelector('video');
                    if (v && !v.paused) {
                        try { v.pause(); } catch(e) {}
                    }
                })();
                """.trimIndent()
            )
        }
    }

    fun seekToSeconds(targetSeconds: Double) {
        if (currentLoadedVideoId.isBlank()) return
        val clamped = targetSeconds.coerceAtLeast(0.0)
        hasSeenValidStartForCurrentToken = true
        mainHandler.post {
            evaluateJs(
                """
                (function() {
                    window.__akHasSeenStartOfTrack = true;
                    var mp = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
                    if (mp && typeof mp.seekTo === 'function') {
                        try { mp.seekTo($clamped, true); } catch(e) {}
                    }
                    var v = document.querySelector('video');
                    if (v) {
                        try { v.currentTime = $clamped; } catch(e) {}
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
                        try { mp.setPlaybackRate($speed); } catch(e) {}
                    }
                    var v = document.querySelector('video');
                    if (v) {
                        try { v.playbackRate = $speed; } catch(e) {}
                    }
                })();
                """.trimIndent()
            )
        }
    }

    private fun injectVideoBridgeScript(token: Int = currentLoadToken, targetId: String = currentLoadedVideoId) {
        if (targetId.isBlank()) return
        val pausedFlag = if (isUserPaused) "true" else "false"
        val rate = currentPlaybackRate
        val script = """
            (function() {
                window.__akUserPaused = $pausedFlag;
                window.__akPlaybackRate = $rate;
                if (window.__akLoadToken !== $token || window.__akTargetVideoId !== '$targetId') {
                    window.__akLoadToken = $token;
                    window.__akTargetVideoId = '$targetId';
                    window.__akTrackEndedFired = false;
                    window.__akErrorFired = false;
                    window.__akHasSeenStartOfTrack = false;
                    window.__akMaxDurationForTrack = 0;
                    window.__akLastPlayAttempt = 0;
                }

                // Spoof Page Visibility API & Focus so background / screen-off never pauses audio
                try {
                    Object.defineProperty(document, 'hidden', { get: function() { return false; }, configurable: true });
                    Object.defineProperty(document, 'webkitHidden', { get: function() { return false; }, configurable: true });
                    Object.defineProperty(document, 'visibilityState', { get: function() { return 'visible'; }, configurable: true });
                    Object.defineProperty(document, 'webkitVisibilityState', { get: function() { return 'visible'; }, configurable: true });
                    document.hasFocus = function() { return true; };
                } catch (e) {}

                // Keep YouTube's internal last-activity timer fresh so it NEVER shows "Video paused. Continue watching?"
                try {
                    window._lact = Date.now();
                } catch (e) {}

                // Isolate WebView's navigator.mediaSession pause/stop so Chromium's secondary
                // MediaSession NEVER conflicts with our foreground MusicPlaybackService MediaSession!
                try {
                    if (navigator.mediaSession && typeof navigator.mediaSession.setActionHandler === 'function') {
                        navigator.mediaSession.setActionHandler('play', function() {
                            if (window.AndroidBridge) window.AndroidBridge.onMediaAction('play');
                        });
                        navigator.mediaSession.setActionHandler('pause', function() {
                            if (!window.__akUserPaused) {
                                var v = document.querySelector('video');
                                if (v && v.paused && !window.__akTrackEndedFired) {
                                    var p = v.play();
                                    if (p && typeof p.catch === 'function') { p.catch(function(){}); }
                                }
                            }
                        });
                        navigator.mediaSession.setActionHandler('stop', function() {});
                        navigator.mediaSession.setActionHandler('previoustrack', function() {
                            if (window.AndroidBridge) window.AndroidBridge.onMediaAction('prev');
                        });
                        navigator.mediaSession.setActionHandler('nexttrack', function() {
                            if (window.AndroidBridge) window.AndroidBridge.onMediaAction('next');
                        });
                    }
                } catch (e) {}

                // Disable YouTube's built-in Up Next / Auto-navigation so our app controls the queue
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
                document.addEventListener('blur', function(e) { e.stopImmediatePropagation(); }, true);

                function getMoviePlayer() {
                    return document.getElementById('movie_player') || document.querySelector('.html5-video-player');
                }

                function isCurrentUrlMatchingTarget() {
                    var target = window.__akTargetVideoId || '';
                    if (!target) return false;
                    var href = window.location.href || '';
                    return href.indexOf(target) !== -1;
                }

                function isAdPlaying() {
                    if (document.querySelector('.ad-showing, .ad-interrupting, .ytp-ad-player-overlay, .ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button')) {
                        return true;
                    }
                    try {
                        var mp = getMoviePlayer();
                        if (mp && typeof mp.getAdState === 'function' && mp.getAdState() === 1) {
                            return true;
                        }
                    } catch (e) {}
                    return false;
                }

                function getTrueDuration(v) {
                    var domDur = (v && v.duration && isFinite(v.duration) && v.duration > 0) ? v.duration : 0;
                    var mpDur = 0;
                    try {
                        var mp = getMoviePlayer();
                        if (mp && typeof mp.getDuration === 'function') {
                            var d = mp.getDuration();
                            if (d && isFinite(d) && d > 0) mpDur = d;
                        }
                    } catch (e) {}
                    var best = Math.max(domDur, mpDur, window.__akMaxDurationForTrack || 0);
                    if (best >= 30 && isCurrentUrlMatchingTarget() && !isAdPlaying()) {
                        window.__akMaxDurationForTrack = best;
                    }
                    return best;
                }

                function dismissContinueWatchingDialog() {
                    try {
                        window._lact = Date.now();
                        var confirmBtns = document.querySelectorAll(
                            'yt-confirm-dialog-renderer button, ' +
                            '.yt-confirm-dialog-renderer button'
                        );
                        for (var i = 0; i < confirmBtns.length; i++) {
                            var btn = confirmBtns[i];
                            if (btn && btn.offsetParent !== null) {
                                btn.click();
                            }
                        }
                    } catch (e) {}
                }

                function notifyTrackEnded(v) {
                    if (window.__akTrackEndedFired) return;
                    if (!isCurrentUrlMatchingTarget() || !window.__akHasSeenStartOfTrack) return;
                    window.__akTrackEndedFired = true;
                    window.__akUserPaused = true;
                    try {
                        var mp = getMoviePlayer();
                        if (mp && typeof mp.pauseVideo === 'function') { mp.pauseVideo(); }
                        if (v && !v.paused) { v.pause(); }
                    } catch (e) {}
                    if (window.AndroidBridge) {
                        window.AndroidBridge.onStateChange(0);
                    }
                }

                function tryPlayVideo(v) {
                    if (!v || window.__akUserPaused || window.__akTrackEndedFired || !v.paused || v.ended) return;
                    if (!isCurrentUrlMatchingTarget()) return;
                    var totalDur = getTrueDuration(v);
                    if (window.__akHasSeenStartOfTrack && totalDur >= 45 && v.currentTime >= totalDur - 0.5) {
                        notifyTrackEnded(v);
                        return;
                    }
                    var now = Date.now();
                    if (now - (window.__akLastPlayAttempt || 0) < 500) return;
                    window.__akLastPlayAttempt = now;

                    dismissContinueWatchingDialog();
                    v.muted = false;
                    v.volume = 1.0;

                    try {
                        var p = v.play();
                        if (p && typeof p.catch === 'function') {
                            p.catch(function() {
                                var mp = getMoviePlayer();
                                if (mp && typeof mp.playVideo === 'function') {
                                    mp.playVideo();
                                } else {
                                    var largeBtn = document.querySelector('.ytp-large-play-button, button.ytp-large-play-button');
                                    if (largeBtn) { largeBtn.click(); }
                                }
                            });
                        }
                    } catch (e) {}
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

                    v.addEventListener('canplay', function() {
                        if (!window.__akUserPaused && !window.__akTrackEndedFired && v.paused) {
                            tryPlayVideo(v);
                        }
                    });

                    v.addEventListener('playing', function() {
                        if (window.__akUserPaused) {
                            v.pause();
                            return;
                        }
                        if (!isCurrentUrlMatchingTarget()) return;
                        window.__akHasSeenStartOfTrack = true;
                        if (window.AndroidBridge) {
                            window.AndroidBridge.onStateChange(1);
                            var dur = getTrueDuration(v);
                            if (dur > 0) {
                                window.AndroidBridge.onDuration(dur);
                            }
                        }
                    });

                    v.addEventListener('play', function() {
                        if (window.__akUserPaused) {
                            v.pause();
                            return;
                        }
                        if (!isCurrentUrlMatchingTarget()) return;
                        window.__akHasSeenStartOfTrack = true;
                        if (window.AndroidBridge) {
                            window.AndroidBridge.onStateChange(1);
                        }
                    });

                    v.addEventListener('pause', function() {
                        if (!isCurrentUrlMatchingTarget()) return;
                        var isAd = isAdPlaying();
                        var totalDur = getTrueDuration(v);
                        if (!isAd && window.__akHasSeenStartOfTrack && totalDur >= 45 && v.currentTime >= totalDur - 0.85) {
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
                                    window.__akLastPlayAttempt = 0;
                                    tryPlayVideo(v);
                                }
                            }, 150);
                        }
                    });

                    v.addEventListener('waiting', function() {
                        if (!window.__akUserPaused && !window.__akTrackEndedFired && isCurrentUrlMatchingTarget() && window.AndroidBridge) {
                            window.AndroidBridge.onStateChange(3);
                        }
                    });

                    v.addEventListener('loadedmetadata', function() {
                        v.loop = false;
                        if (!isCurrentUrlMatchingTarget()) return;
                        var dur = getTrueDuration(v);
                        if (dur > 0 && window.AndroidBridge) {
                            window.AndroidBridge.onDuration(dur);
                        }
                    });

                    v.addEventListener('durationchange', function() {
                        if (!isCurrentUrlMatchingTarget()) return;
                        var dur = getTrueDuration(v);
                        if (dur > 0 && window.AndroidBridge) {
                            window.AndroidBridge.onDuration(dur);
                        }
                    });

                    v.addEventListener('timeupdate', function() {
                        if (!window.AndroidBridge || window.__akTrackEndedFired) return;
                        if (isAdPlaying()) {
                            var skipBtn = document.querySelector('.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button');
                            if (skipBtn) { skipBtn.click(); }
                            return;
                        }
                        if (!isCurrentUrlMatchingTarget()) return;

                        if (!v.paused && v.currentTime >= 0.2 && v.currentTime < 40.0) {
                            window.__akHasSeenStartOfTrack = true;
                        }

                        var totalDur = getTrueDuration(v);
                        if (window.__akHasSeenStartOfTrack && totalDur >= 45 && v.currentTime >= totalDur - 0.5) {
                            notifyTrackEnded(v);
                            return;
                        }
                        if (window.__akHasSeenStartOfTrack && v.currentTime >= 0) {
                            window.AndroidBridge.onProgress(v.currentTime);
                            window.AndroidBridge.onStateChange(1);
                        }
                        if (totalDur > 0) {
                            window.AndroidBridge.onDuration(totalDur);
                        }
                    });

                    v.addEventListener('ended', function() {
                        if (!isAdPlaying() && isCurrentUrlMatchingTarget() && window.__akHasSeenStartOfTrack) {
                            notifyTrackEnded(v);
                        }
                    });
                }

                window.__akTick = function() {
                    try {
                        window._lact = Date.now();
                        dismissContinueWatchingDialog();

                        var v = document.querySelector('video');
                        if (!v) return;
                        hookVideoElement(v);
                        if (v.loop) { v.loop = false; }

                        if (isAdPlaying()) {
                            var skipBtn = document.querySelector('.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button');
                            if (skipBtn) { skipBtn.click(); }
                            if (v.paused && !window.__akUserPaused) {
                                try { v.play(); } catch(e) {}
                            }
                            return;
                        }

                        if (!isCurrentUrlMatchingTarget()) {
                            return;
                        }

                        if (!v.paused && v.currentTime >= 0.2 && v.currentTime < 40.0) {
                            window.__akHasSeenStartOfTrack = true;
                        }

                        var totalDur = getTrueDuration(v);
                        if (!window.__akTrackEndedFired && window.__akHasSeenStartOfTrack) {
                            if (v.ended || (totalDur >= 45 && v.currentTime >= totalDur - 0.5)) {
                                notifyTrackEnded(v);
                                return;
                            }
                        }

                        if (v.muted) {
                            v.muted = false;
                            v.volume = 1.0;
                        }

                        var unmuteBtn = document.querySelector('.ytp-unmute');
                        if (unmuteBtn && unmuteBtn.offsetParent !== null) {
                            unmuteBtn.click();
                        }

                        if (!window.__akUserPaused && !window.__akTrackEndedFired && v.paused && !v.ended) {
                            tryPlayVideo(v);
                        }

                        if (!window.__akUserPaused && !window.__akTrackEndedFired && !v.paused && window.__akHasSeenStartOfTrack && v.currentTime > 0 && window.AndroidBridge) {
                            window.AndroidBridge.onProgress(v.currentTime);
                            if (totalDur > 0) {
                                window.AndroidBridge.onDuration(totalDur);
                            }
                        }
                    } catch (e) {}
                };

                setInterval(function() {
                    if (typeof window.__akTick === 'function') {
                        window.__akTick();
                    }
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
        mainHandler.removeCallbacks(nativeHeartbeatRunnable)
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
                injectVideoBridgeScript(currentLoadToken, currentLoadedVideoId)
            }
        }

        @JavascriptInterface
        fun onProgress(currentTimeSeconds: Double) {
            val tokenSnapshot = currentLoadToken
            if (currentTimeSeconds.isNaN() || currentTimeSeconds < 0.0 || hasFiredEndedForCurrentTrack || currentLoadedVideoId.isBlank()) return
            val posMs = (currentTimeSeconds * 1000.0).toLong()
            if (posMs in 200L..40_000L) {
                hasSeenValidStartForCurrentToken = true
            }
            mainHandler.post {
                if (tokenSnapshot == currentLoadToken && !hasFiredEndedForCurrentTrack && currentLoadedVideoId.isNotBlank()) {
                    onProgressUpdate(posMs)
                }
            }
        }

        @JavascriptInterface
        fun onDuration(durationSeconds: Double) {
            val tokenSnapshot = currentLoadToken
            if (durationSeconds.isNaN() || durationSeconds <= 0.0 || currentLoadedVideoId.isBlank()) return
            val durMs = (durationSeconds * 1000.0).toLong()
            mainHandler.post {
                if (tokenSnapshot == currentLoadToken && !hasFiredEndedForCurrentTrack && currentLoadedVideoId.isNotBlank()) {
                    onDurationUpdate(durMs)
                }
            }
        }

        @JavascriptInterface
        fun onStateChange(state: Int) {
            val tokenSnapshot = currentLoadToken
            mainHandler.post {
                if (tokenSnapshot != currentLoadToken || currentLoadedVideoId.isBlank()) {
                    return@post
                }
                if (state == STATE_ENDED) {
                    val elapsedSinceLoad = SystemClock.elapsedRealtime() - lastLoadTimestampMs
                    if (!hasSeenValidStartForCurrentToken && elapsedSinceLoad < 15_000L) {
                        return@post
                    }
                    if (hasFiredEndedForCurrentTrack) return@post
                    hasFiredEndedForCurrentTrack = true
                    mainHandler.removeCallbacks(nativeHeartbeatRunnable)
                    onStateChangeUpdate(STATE_ENDED)
                    return@post
                }
                if (hasFiredEndedForCurrentTrack) return@post
                if (state == STATE_PLAYING) {
                    hasSeenValidStartForCurrentToken = true
                    if (isUserPaused) {
                        pauseVideo()
                        return@post
                    }
                }
                onStateChangeUpdate(state)
            }
        }

        @JavascriptInterface
        fun onError(errorCode: Int) {
            val tokenSnapshot = currentLoadToken
            if (currentLoadedVideoId.isBlank() || hasFiredErrorForCurrentTrack) return
            hasFiredErrorForCurrentTrack = true
            Log.w(TAG, "Backstage player error code: $errorCode for $currentLoadedVideoId")
            mainHandler.post {
                if (tokenSnapshot == currentLoadToken && currentLoadedVideoId.isNotBlank()) {
                    onErrorUpdate(errorCode)
                }
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
