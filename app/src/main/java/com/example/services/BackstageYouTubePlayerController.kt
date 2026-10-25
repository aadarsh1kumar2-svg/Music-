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
     * Native heartbeat driven on Android's Main Looper every 450ms.
     * Unlike JS setInterval (which Chromium throttles when the app is in the background),
     * native Handler callbacks + evaluateJavascript run continuously while our Foreground
     * MusicPlaybackService holds its WakeLock, keeping audio playing non-stop across songs!
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
                evaluateJs(
                    """
                    (function() {
                        window.__akUserPaused = $pausedFlag;
                        window.__akPlaybackRate = $rate;
                        if (typeof window.__akTick === 'function') {
                            window.__akTick();
                        }
                    })();
                    """.trimIndent()
                )
                // If bridge wasn't injected yet on the page, inject it now
                injectVideoBridgeScript(token, targetId)
            } catch (_: Exception) {
            }
            if (currentLoadedVideoId.isNotBlank() && !hasFiredEndedForCurrentTrack) {
                mainHandler.postDelayed(this, 450L)
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
                    // Never fire STATE_ENDED from doUpdateVisitedHistory!
                    // If YouTube's SPA tries to auto-navigate to a related video while we haven't ended our track,
                    // block it or let our JS bridge keep control of the target video.
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

    fun keepAwake() {
        mainHandler.post {
            try {
                webView?.onResume()
                webView?.resumeTimers()
                if (!isUserPaused && currentLoadedVideoId.isNotBlank() && !hasFiredEndedForCurrentTrack) {
                    playVideo()
                    mainHandler.removeCallbacks(nativeHeartbeatRunnable)
                    mainHandler.post(nativeHeartbeatRunnable)
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

            val currentUrl = view.url.orEmpty()
            val watchUrl = "https://m.youtube.com/watch?v=$cleanId&autoplay=1"

            // If we are already on a YouTube watch page, try seamless in-page switching via
            // movie_player.loadVideoById(cleanId, 0) first! This reuses the already-unlocked
            // <video> element and audio decoder so background track transitions (1 -> 2 -> 3 -> N)
            // never get blocked by background autoplay policies!
            if (currentUrl.contains("youtube.com/watch")) {
                injectVideoBridgeScript(token, cleanId)
                try {
                    view.evaluateJavascript(
                        """
                        (function() {
                            try {
                                window.__akLoadToken = $token;
                                window.__akTargetVideoId = '$cleanId';
                                window.__akUserPaused = false;
                                window.__akTrackEndedFired = false;
                                window.__akErrorFired = false;
                                window.__akHasSeenStartOfTrack = false;
                                window.__akMaxDurationForTrack = 0;
                                window.__akLastPlayAttempt = 0;
                                var v = document.querySelector('video');
                                if (v) {
                                    try { v.currentTime = 0; } catch(e) {}
                                }
                                var mp = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
                                if (mp && typeof mp.loadVideoById === 'function') {
                                    mp.loadVideoById('$cleanId', 0);
                                    if (typeof mp.playVideo === 'function') {
                                        mp.playVideo();
                                    }
                                    return 'IN_PAGE_OK';
                                }
                            } catch (e) {}
                            return 'NEEDS_LOAD_URL';
                        })();
                        """.trimIndent()
                    ) { result ->
                        if (token != currentLoadToken || currentLoadedVideoId != cleanId) return@evaluateJavascript
                        val cleanRes = result?.replace("\"", "")?.trim().orEmpty()
                        if (cleanRes != "IN_PAGE_OK") {
                            view.loadUrl(watchUrl)
                        } else {
                            // Backup navigation if in-page player didn't start the new track within 3.2s
                            mainHandler.postDelayed({
                                if (token == currentLoadToken &&
                                    currentLoadedVideoId == cleanId &&
                                    !hasSeenValidStartForCurrentToken &&
                                    !hasFiredEndedForCurrentTrack
                                ) {
                                    view.onResume()
                                    view.resumeTimers()
                                    view.loadUrl(watchUrl)
                                }
                            }, 3200L)
                        }
                    }
                } catch (_: Exception) {
                    view.loadUrl(watchUrl)
                }
            } else {
                view.loadUrl(watchUrl)
            }

            // Start continuous native heartbeat
            mainHandler.removeCallbacks(nativeHeartbeatRunnable)
            mainHandler.postDelayed(nativeHeartbeatRunnable, 350L)

            // Stall watchdog: if the WebView still hasn't started playing after 9.5 seconds
            // (e.g. embed-restricted song or network stall), trigger fallback to AudioStreamExtractor!
            mainHandler.postDelayed({
                if (token == currentLoadToken &&
                    currentLoadedVideoId == cleanId &&
                    !hasSeenValidStartForCurrentToken &&
                    !hasFiredErrorForCurrentTrack &&
                    !isUserPaused
                ) {
                    hasFiredErrorForCurrentTrack = true
                    Log.w(TAG, "Watchdog timeout waiting for $cleanId to start; triggering extractor fallback")
                    onErrorUpdate(150)
                }
            }, 9500L)
        }
    }

    /**
     * Completely stops and unloads the WebView media player so that when the user plays a
     * local device song or offline downloaded song (even while internet is ON), the WebView
     * releases all audio focus and stops all background intervals.
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
                    var mp = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
                    if (mp && typeof mp.playVideo === 'function') {
                        try { mp.playVideo(); } catch(e) {}
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
            mainHandler.removeCallbacks(nativeHeartbeatRunnable)
            mainHandler.postDelayed(nativeHeartbeatRunnable, 400L)
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

                // Neutralize WebView's navigator.mediaSession pause/stop so Chromium's secondary
                // MediaSession NEVER conflicts with our foreground MusicPlaybackService MediaSession!
                try {
                    if (navigator.mediaSession && typeof navigator.mediaSession.setActionHandler === 'function') {
                        navigator.mediaSession.setActionHandler('play', function() {
                            if (window.AndroidBridge) window.AndroidBridge.onMediaAction('play');
                        });
                        navigator.mediaSession.setActionHandler('pause', function() {
                            // Do NOT forward WebView's automatic session pause to AndroidBridge!
                            // If user didn't pause, keep playing!
                            if (!window.__akUserPaused) {
                                var v = document.querySelector('video');
                                if (v && v.paused && !window.__akTrackEndedFired) {
                                    var p = v.play();
                                    if (p && typeof p.catch === 'function') { p.catch(function(){}); }
                                }
                            }
                        });
                        navigator.mediaSession.setActionHandler('stop', function() {
                            // Ignore Chromium session stop events
                        });
                        navigator.mediaSession.setActionHandler('previoustrack', function() {
                            if (window.AndroidBridge) window.AndroidBridge.onMediaAction('prev');
                        });
                        navigator.mediaSession.setActionHandler('nexttrack', function() {
                            if (window.AndroidBridge) window.AndroidBridge.onMediaAction('next');
                        });
                    }
                } catch (e) {}

                // Disable YouTube's built-in Up Next / Auto-navigation so our app controls the queue!
                try {
                    var autonavToggle = document.querySelector('.ytm-autonav-toggle-button-container button[aria-pressed="true"], .ytp-autonav-toggle-button[aria-checked="true"]');
                    if (autonavToggle) { autonavToggle.click(); }
                    var mpAuto = document.getElementById('movie_player');
                    if (mpAuto && typeof mpAuto.setAutonav === 'function') {
                        mpAuto.setAutonav(false);
                    }
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

                function isCurrentVideoMatchingTarget() {
                    var target = window.__akTargetVideoId || '';
                    if (!target) return false;
                    try {
                        var mp = getMoviePlayer();
                        if (mp && typeof mp.getVideoData === 'function') {
                            var data = mp.getVideoData();
                            if (data && data.video_id) {
                                return data.video_id === target;
                            }
                        }
                    } catch (e) {}
                    var href = window.location.href || '';
                    return href.indexOf(target) !== -1;
                }

                function isAdPlaying() {
                    if (document.querySelector('.ad-showing, .ad-interrupting, .ytp-ad-player-overlay')) {
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
                    if (best > 0 && isCurrentVideoMatchingTarget() && !isAdPlaying()) {
                        window.__akMaxDurationForTrack = best;
                    }
                    return best;
                }

                function dismissAnyPausePopups() {
                    try {
                        window._lact = Date.now();
                        var dialogs = document.querySelectorAll(
                            'yt-confirm-dialog-renderer button, ' +
                            'tp-yt-paper-dialog yt-button-renderer button, ' +
                            '.yt-confirm-dialog-renderer button, ' +
                            'ytm-popup-container yt-button-renderer button, ' +
                            'ytm-popup-container button[aria-label*="Yes"], ' +
                            'ytm-popup-container button[aria-label*="Continue"]'
                        );
                        for (var i = 0; i < dialogs.length; i++) {
                            var btn = dialogs[i];
                            if (btn && btn.offsetParent !== null) {
                                btn.click();
                            }
                        }
                    } catch (e) {}
                }

                function notifyTrackEnded(v) {
                    if (window.__akTrackEndedFired) return;
                    if (!isCurrentVideoMatchingTarget() || !window.__akHasSeenStartOfTrack) return;
                    window.__akTrackEndedFired = true;
                    window.__akUserPaused = true;
                    try {
                        var mp = getMoviePlayer();
                        if (mp && typeof mp.pauseVideo === 'function') { mp.pauseVideo(); }
                        if (v && !v.paused) { v.pause(); }
                    } catch (e) {}
                    if (window.AndroidBridge) {
                        window.AndroidBridge.onStateChangeToken(
                            window.__akLoadToken || 0,
                            window.__akTargetVideoId || '',
                            0
                        );
                    }
                }

                function tryPlayVideo(v) {
                    if (!v || window.__akUserPaused || window.__akTrackEndedFired || !v.paused) return;
                    if (!isCurrentVideoMatchingTarget()) return;
                    var totalDur = getTrueDuration(v);
                    if (window.__akHasSeenStartOfTrack && totalDur > 15 && v.currentTime >= totalDur - 0.5) {
                        notifyTrackEnded(v);
                        return;
                    }
                    var now = Date.now();
                    if (now - (window.__akLastPlayAttempt || 0) < 320) return;
                    window.__akLastPlayAttempt = now;

                    dismissAnyPausePopups();
                    v.muted = false;
                    v.volume = 1.0;

                    try {
                        var mp = getMoviePlayer();
                        if (mp && typeof mp.playVideo === 'function') {
                            mp.playVideo();
                        }
                    } catch (e) {}

                    try {
                        var p = v.play();
                        if (p && typeof p.catch === 'function') {
                            p.catch(function() {
                                var largeBtn = document.querySelector('.ytp-large-play-button, button.ytp-large-play-button, .ytm-autonav-bar-button');
                                if (largeBtn) { largeBtn.click(); }
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

                    v.addEventListener('error', function() {
                        if (!window.__akErrorFired && isCurrentVideoMatchingTarget() && window.AndroidBridge) {
                            window.__akErrorFired = true;
                            window.AndroidBridge.onErrorToken(
                                window.__akLoadToken || 0,
                                window.__akTargetVideoId || '',
                                150
                            );
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
                        if (!isCurrentVideoMatchingTarget()) return;
                        if (v.currentTime < 30.0) {
                            window.__akHasSeenStartOfTrack = true;
                        }
                        if (window.AndroidBridge) {
                            window.AndroidBridge.onStateChangeToken(
                                window.__akLoadToken || 0,
                                window.__akTargetVideoId || '',
                                1
                            );
                            var dur = getTrueDuration(v);
                            if (dur > 0) {
                                window.AndroidBridge.onDurationToken(
                                    window.__akLoadToken || 0,
                                    window.__akTargetVideoId || '',
                                    dur
                                );
                            }
                        }
                    });

                    v.addEventListener('play', function() {
                        if (window.__akUserPaused || window.__akTrackEndedFired) {
                            v.pause();
                            return;
                        }
                        if (!isCurrentVideoMatchingTarget()) return;
                        if (window.AndroidBridge) {
                            window.AndroidBridge.onStateChangeToken(
                                window.__akLoadToken || 0,
                                window.__akTargetVideoId || '',
                                1
                            );
                        }
                    });

                    v.addEventListener('pause', function() {
                        if (!isCurrentVideoMatchingTarget()) return;
                        var isAd = isAdPlaying();
                        var totalDur = getTrueDuration(v);
                        if (!isAd && window.__akHasSeenStartOfTrack && totalDur > 15 && v.currentTime >= totalDur - 0.85) {
                            notifyTrackEnded(v);
                            return;
                        }
                        if (window.__akUserPaused) {
                            if (window.AndroidBridge) {
                                window.AndroidBridge.onStateChangeToken(
                                    window.__akLoadToken || 0,
                                    window.__akTargetVideoId || '',
                                    2
                                );
                            }
                        } else if (!window.__akTrackEndedFired && !v.ended) {
                            setTimeout(function() {
                                if (!window.__akUserPaused && !window.__akTrackEndedFired && v.paused && !v.ended) {
                                    window.__akLastPlayAttempt = 0;
                                    tryPlayVideo(v);
                                }
                            }, 120);
                        }
                    });

                    v.addEventListener('waiting', function() {
                        if (!window.__akUserPaused && !window.__akTrackEndedFired && isCurrentVideoMatchingTarget() && window.AndroidBridge) {
                            window.AndroidBridge.onStateChangeToken(
                                window.__akLoadToken || 0,
                                window.__akTargetVideoId || '',
                                3
                            );
                        }
                    });

                    v.addEventListener('loadedmetadata', function() {
                        v.loop = false;
                        if (!isCurrentVideoMatchingTarget()) return;
                        var dur = getTrueDuration(v);
                        if (dur > 0 && window.AndroidBridge) {
                            window.AndroidBridge.onDurationToken(
                                window.__akLoadToken || 0,
                                window.__akTargetVideoId || '',
                                dur
                            );
                        }
                    });

                    v.addEventListener('durationchange', function() {
                        if (!isCurrentVideoMatchingTarget()) return;
                        var dur = getTrueDuration(v);
                        if (dur > 0 && window.AndroidBridge) {
                            window.AndroidBridge.onDurationToken(
                                window.__akLoadToken || 0,
                                window.__akTargetVideoId || '',
                                dur
                            );
                        }
                    });

                    v.addEventListener('timeupdate', function() {
                        if (!window.AndroidBridge || window.__akTrackEndedFired) return;
                        var isAd = isAdPlaying();
                        if (isAd) {
                            var skipBtn = document.querySelector('.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button');
                            if (skipBtn) { skipBtn.click(); }
                            else if (v.duration && v.duration > 0 && v.duration < 120) {
                                try { v.currentTime = v.duration - 0.1; } catch(e) {}
                            }
                            return;
                        }
                        if (!isCurrentVideoMatchingTarget()) return;

                        if (!v.paused && v.currentTime >= 0.2 && v.currentTime < 30.0) {
                            window.__akHasSeenStartOfTrack = true;
                        }

                        var totalDur = getTrueDuration(v);
                        if (window.__akHasSeenStartOfTrack && totalDur > 15 && v.currentTime >= totalDur - 0.5) {
                            notifyTrackEnded(v);
                            return;
                        }
                        if (window.__akHasSeenStartOfTrack && v.currentTime >= 0) {
                            window.AndroidBridge.onProgressToken(
                                window.__akLoadToken || 0,
                                window.__akTargetVideoId || '',
                                v.currentTime
                            );
                        }
                        if (totalDur > 0) {
                            window.AndroidBridge.onDurationToken(
                                window.__akLoadToken || 0,
                                window.__akTargetVideoId || '',
                                totalDur
                            );
                        }
                    });

                    v.addEventListener('ended', function() {
                        if (!isAdPlaying() && isCurrentVideoMatchingTarget() && window.__akHasSeenStartOfTrack) {
                            notifyTrackEnded(v);
                        }
                    });
                }

                window.__akTick = function() {
                    try {
                        window._lact = Date.now();
                        dismissAnyPausePopups();

                        var errEl = document.querySelector('.ytm-player-error-message-renderer, .player-error-message-container');
                        if (errEl && errEl.offsetParent !== null && !window.__akErrorFired && isCurrentVideoMatchingTarget() && window.AndroidBridge) {
                            window.__akErrorFired = true;
                            window.AndroidBridge.onErrorToken(
                                window.__akLoadToken || 0,
                                window.__akTargetVideoId || '',
                                150
                            );
                            return;
                        }

                        var v = document.querySelector('video');
                        if (!v) return;
                        hookVideoElement(v);
                        if (v.loop) { v.loop = false; }

                        if ( isAdPlaying() ) {
                            var skipBtn = document.querySelector('.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button');
                            if (skipBtn) { skipBtn.click(); }
                            else if (v.duration && v.duration > 0 && v.duration < 120) {
                                try { v.currentTime = v.duration - 0.1; } catch(e) {}
                            }
                            if (v.paused && !window.__akUserPaused) {
                                try { v.play(); } catch(e) {}
                            }
                            return;
                        }

                        if (!isCurrentVideoMatchingTarget()) {
                            return;
                        }

                        var mp = getMoviePlayer();
                        var mpState = -1;
                        try {
                            if (mp && typeof mp.getPlayerState === 'function') {
                                mpState = mp.getPlayerState();
                            }
                        } catch (e) {}

                        if (!v.paused && v.currentTime >= 0.2 && v.currentTime < 30.0) {
                            window.__akHasSeenStartOfTrack = true;
                        }

                        var totalDur = getTrueDuration(v);
                        if (!window.__akTrackEndedFired && window.__akHasSeenStartOfTrack) {
                            if (v.ended ||
                                (mpState === 0 && totalDur > 15 && v.currentTime >= totalDur - 4.0) ||
                                (totalDur > 15 && v.currentTime >= totalDur - 0.5)
                            ) {
                                notifyTrackEnded(v);
                                return;
                            }
                        }

                        if (v.muted) {
                            v.muted = false;
                            v.volume = 1.0;
                        }
                        if (mp && typeof mp.isMuted === 'function' && mp.isMuted()) {
                            try { mp.unMute(); mp.setVolume(100); } catch (e) {}
                        }

                        var unmuteBtn = document.querySelector('.ytp-unmute');
                        if (unmuteBtn && unmuteBtn.offsetParent !== null) {
                            unmuteBtn.click();
                        }

                        if (!window.__akUserPaused && !window.__akTrackEndedFired && (v.paused || mpState === 2 || mpState === 5 || mpState === -1) && !v.ended) {
                            tryPlayVideo(v);
                        }

                        if (!window.__akUserPaused && !window.__akTrackEndedFired && !v.paused && window.__akHasSeenStartOfTrack && v.currentTime > 0 && window.AndroidBridge) {
                            window.AndroidBridge.onProgressToken(
                                window.__akLoadToken || 0,
                                window.__akTargetVideoId || '',
                                v.currentTime
                            );
                            if (totalDur > 0) {
                                window.AndroidBridge.onDurationToken(
                                    window.__akLoadToken || 0,
                                    window.__akTargetVideoId || '',
                                    totalDur
                                );
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
        fun onProgressToken(token: Int, videoId: String, currentTimeSeconds: Double) {
            if (token != currentLoadToken || videoId != currentLoadedVideoId) return
            if (currentTimeSeconds.isNaN() || currentTimeSeconds < 0.0 || hasFiredEndedForCurrentTrack || currentLoadedVideoId.isBlank()) return
            val posMs = (currentTimeSeconds * 1000.0).toLong()
            if (posMs in 200L..30_000L) {
                hasSeenValidStartForCurrentToken = true
            }
            mainHandler.post {
                if (token == currentLoadToken && videoId == currentLoadedVideoId && !hasFiredEndedForCurrentTrack) {
                    onProgressUpdate(posMs)
                }
            }
        }

        @JavascriptInterface
        fun onDurationToken(token: Int, videoId: String, durationSeconds: Double) {
            if (token != currentLoadToken || videoId != currentLoadedVideoId) return
            if (durationSeconds.isNaN() || durationSeconds <= 0.0 || currentLoadedVideoId.isBlank()) return
            val durMs = (durationSeconds * 1000.0).toLong()
            mainHandler.post {
                if (token == currentLoadToken && videoId == currentLoadedVideoId && !hasFiredEndedForCurrentTrack) {
                    onDurationUpdate(durMs)
                }
            }
        }

        @JavascriptInterface
        fun onStateChangeToken(token: Int, videoId: String, state: Int) {
            mainHandler.post {
                if (token != currentLoadToken || videoId != currentLoadedVideoId || currentLoadedVideoId.isBlank()) {
                    return@post
                }
                if (state == STATE_ENDED) {
                    // Guard against any stale or premature ended event right after track load
                    val elapsedSinceLoad = SystemClock.elapsedRealtime() - lastLoadTimestampMs
                    if (!hasSeenValidStartForCurrentToken && elapsedSinceLoad < 12_000L) {
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
        fun onErrorToken(token: Int, videoId: String, errorCode: Int) {
            if (token != currentLoadToken || videoId != currentLoadedVideoId) return
            if (currentLoadedVideoId.isBlank() || hasFiredErrorForCurrentTrack) return
            hasFiredErrorForCurrentTrack = true
            Log.w(TAG, "Backstage player error code: $errorCode for $currentLoadedVideoId")
            mainHandler.post {
                if (token == currentLoadToken && videoId == currentLoadedVideoId) {
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
