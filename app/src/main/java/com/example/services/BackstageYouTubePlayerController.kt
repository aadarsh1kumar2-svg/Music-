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
 * Custom WebView that always reports View.VISIBLE, shown, and window-focused to Chromium
 * so HTML5 audio decoding runs at full priority with zero throttling even when the user
 * is actively typing, scrolling, or multitasking inside other apps, or when the screen is off.
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

    override fun isShown(): Boolean = true

    override fun hasWindowFocus(): Boolean = true

    override fun onPause() {
        // Never allow backgrounding or game launches to pause the Chromium audio engine
        super.onResume()
        resumeTimers()
    }
}

class BackstageYouTubePlayerController(
    private val context: Context,
    private val onProgressUpdate: (Long) -> Unit,
    private val onDurationUpdate: (Long) -> Unit,
    private val onStateChangeUpdate: (Int) -> Unit,
    private val onErrorUpdate: (Int) -> Unit,
    private val onMediaButtonAction: ((String) -> Unit)? = null,
    private val onUnexpectedPauseDetected: (() -> Unit)? = null
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
    var isPausedByExternalAudio: Boolean = false
        private set

    @Volatile
    private var currentSongTitle: String = "AADIFY Music"

    @Volatile
    private var currentSongArtist: String = "AADIFY"

    @Volatile
    private var currentSongArtworkUrl: String = ""

    @Volatile
    private var hasFiredEndedForCurrentTrack: Boolean = false

    @Volatile
    private var hasFiredErrorForCurrentTrack: Boolean = false

    @Volatile
    private var hasSeenValidStartForCurrentToken: Boolean = false

    @Volatile
    private var lastLoadTimestampMs: Long = 0L

    @Volatile
    private var lastKnownPositionSeconds: Double = 0.0

    @Volatile
    private var pendingResumeTargetSeconds: Double = 0.0

    fun getLastKnownPositionMs(): Long = (lastKnownPositionSeconds * 1000.0).toLong().coerceAtLeast(0L)

    private fun escapeJsSingleQuoted(input: String): String {
        return input
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", " ")
            .replace("\r", " ")
    }

    private val fallbackUrlReloadRunnable = Runnable {
        val targetId = currentLoadedVideoId
        if (targetId.isBlank() || hasFiredEndedForCurrentTrack || hasSeenValidStartForCurrentToken || isUserPaused) {
            return@Runnable
        }
        val safeStartSec = pendingResumeTargetSeconds.coerceAtLeast(lastKnownPositionSeconds).coerceAtLeast(0.0)
        val startParam = if (safeStartSec >= 1.0) "&t=${safeStartSec.toInt()}s" else ""
        val watchUrl = "https://m.youtube.com/watch?v=$targetId&autoplay=1$startParam"
        try {
            webView?.loadUrl(watchUrl)
        } catch (_: Exception) {
        }
    }

    /**
     * High-priority native heartbeat on Android's Main Looper every 500ms.
     * Keeps WebView timers and renderer awake while the user is typing or scrolling in other apps,
     * and preserves exact playback position if the stream ever recovers from an interruption.
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
                val extPausedFlag = if (isPausedByExternalAudio) "true" else "false"
                val rate = currentPlaybackRate
                val resumeSec = pendingResumeTargetSeconds.coerceAtLeast(0.0)
                val safeTitle = escapeJsSingleQuoted(currentSongTitle)
                val safeArtist = escapeJsSingleQuoted(currentSongArtist)
                val safeArtwork = escapeJsSingleQuoted(currentSongArtworkUrl)
                webView?.evaluateJavascript(
                    """
                    (function() {
                        window.__akUserPaused = $pausedFlag;
                        window.__akExternalPaused = $extPausedFlag;
                        window.__akPlaybackRate = $rate;
                        window.__akSongTitle = '$safeTitle';
                        window.__akSongArtist = '$safeArtist';
                        window.__akSongArtwork = '$safeArtwork';
                        if (window.__akLoadToken !== $token || window.__akTargetVideoId !== '$targetId') {
                            window.__akLoadToken = $token;
                            window.__akTargetVideoId = '$targetId';
                            window.__akTrackEndedFired = false;
                            window.__akErrorFired = false;
                            window.__akHasSeenStartOfTrack = ${if (resumeSec >= 1.0) "true" else "false"};
                            window.__akMaxDurationForTrack = 0;
                            window.__akResumeTargetSeconds = $resumeSec;
                            window.__akLastGoodTime = $resumeSec;
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
                mainHandler.postDelayed(this, 500L)
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
            // Use LAYER_TYPE_NONE so stopping MainActivity's hardware surface in background never suspends Chromium!
            setLayerType(View.LAYER_TYPE_NONE, null)
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
        try {
            BackgroundGamingAudioKeeper.getInstance(context).attachWebView(created)
            if (currentLoadedVideoId.isBlank()) {
                created.loadUrl("https://m.youtube.com")
            }
        } catch (_: Exception) {
        }
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
        isPausedByExternalAudio = false
        hasFiredEndedForCurrentTrack = true
        hasFiredErrorForCurrentTrack = false
        hasSeenValidStartForCurrentToken = false
        lastKnownPositionSeconds = 0.0
        pendingResumeTargetSeconds = 0.0
        mainHandler.removeCallbacks(nativeHeartbeatRunnable)
        mainHandler.removeCallbacks(fallbackUrlReloadRunnable)
    }

    /**
     * Called continuously by MusicPlaybackService (Foreground Service) and MusicViewModel
     * to keep WebView timers and audio active even when the user is typing on the keyboard
     * or scrolling in other apps.
     */
    fun keepAwake() {
        mainHandler.post {
            try {
                webView?.onResume()
                webView?.resumeTimers()
                if (!isUserPaused && !isPausedByExternalAudio && currentLoadedVideoId.isNotBlank() && !hasFiredEndedForCurrentTrack) {
                    evaluateJs(
                        """
                        (function() {
                            window.__akUserPaused = false;
                            window.__akExternalPaused = false;
                            if (typeof window.__akTick === 'function') {
                                window.__akTick();
                            }
                        })();
                        """.trimIndent()
                    )
                }
            } catch (_: Exception) {
            }
        }
    }

    fun loadVideoById(
        rawVideoId: String,
        startSeconds: Double = 0.0,
        songTitle: String = "",
        songArtist: String = "",
        artworkUrl: String = ""
    ) {
        val cleanId = Song.extractYoutubeVideoId(rawVideoId)
        if (cleanId.isBlank()) {
            onErrorUpdate(-1)
            return
        }
        val safeStartSec = startSeconds.coerceAtLeast(0.0)
        val token = ++currentLoadToken
        currentLoadedVideoId = cleanId
        isUserPaused = false
        isPausedByExternalAudio = false
        if (songTitle.isNotBlank()) currentSongTitle = songTitle
        if (songArtist.isNotBlank()) currentSongArtist = songArtist
        currentSongArtworkUrl = artworkUrl.ifBlank { "https://img.youtube.com/vi/$cleanId/hqdefault.jpg" }
        hasFiredEndedForCurrentTrack = false
        hasFiredErrorForCurrentTrack = false
        hasSeenValidStartForCurrentToken = safeStartSec >= 1.0
        lastKnownPositionSeconds = safeStartSec
        pendingResumeTargetSeconds = safeStartSec
        lastLoadTimestampMs = SystemClock.elapsedRealtime()

        val safeTitle = escapeJsSingleQuoted(currentSongTitle)
        val safeArtist = escapeJsSingleQuoted(currentSongArtist)
        val safeArtwork = escapeJsSingleQuoted(currentSongArtworkUrl)

        mainHandler.post {
            val view = getOrCreateWebView()
            view.onResume()
            view.resumeTimers()
            try {
                val keeper = BackgroundGamingAudioKeeper.getInstance(context)
                keeper.attachWebView(view)
                keeper.startGamingKeepAlive()
            } catch (_: Exception) {
            }

            val hasResumeStart = if (safeStartSec >= 1.0) "true" else "false"
            val startParam = if (safeStartSec >= 1.0) "&t=${safeStartSec.toInt()}s" else ""
            val watchUrl = "https://m.youtube.com/watch?v=$cleanId&autoplay=1$startParam"

            mainHandler.removeCallbacks(fallbackUrlReloadRunnable)
            mainHandler.removeCallbacks(nativeHeartbeatRunnable)

            view.evaluateJavascript(
                """
                (function() {
                    window.__akLoadToken = $token;
                    window.__akTargetVideoId = '$cleanId';
                    window.__akUserPaused = false;
                    window.__akExternalPaused = false;
                    window.__akSongTitle = '$safeTitle';
                    window.__akSongArtist = '$safeArtist';
                    window.__akSongArtwork = '$safeArtwork';
                    window.__akTrackEndedFired = false;
                    window.__akErrorFired = false;
                    window.__akHasSeenStartOfTrack = $hasResumeStart;
                    window.__akMaxDurationForTrack = 0;
                    window.__akResumeTargetSeconds = $safeStartSec;
                    window.__akLastGoodTime = $safeStartSec;
                    window.__akLastReportedDur = 0;
                    var mp = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
                    if (mp && typeof mp.loadVideoById === 'function' && location.hostname.indexOf('youtube.com') !== -1) {
                        try {
                            mp.loadVideoById('$cleanId', $safeStartSec);
                            if (typeof mp.unMute === 'function') mp.unMute();
                            if (typeof mp.setVolume === 'function') mp.setVolume(100);
                            if (typeof mp.playVideo === 'function') mp.playVideo();
                            var v = document.querySelector('video');
                            if (v) {
                                v.muted = false;
                                v.volume = 1.0;
                                var p = v.play();
                                if (p && typeof p.catch === 'function') p.catch(function(){});
                            }
                            return 'LOADED_IN_PLACE';
                        } catch (e) {}
                    }
                    var vOld = document.querySelector('video');
                    if (vOld) {
                        try { vOld.pause(); } catch (e2) {}
                    }
                    return 'NEEDS_URL_LOAD';
                })();
                """.trimIndent()
            ) { result ->
                if (currentLoadToken != token) return@evaluateJavascript
                if (result?.contains("LOADED_IN_PLACE") == true) {
                    injectVideoBridgeScript(token, cleanId)
                    mainHandler.postDelayed(fallbackUrlReloadRunnable, 1600L)
                } else {
                    view.loadUrl(watchUrl)
                }
            }

            mainHandler.postDelayed(nativeHeartbeatRunnable, 120L)
        }
    }

    /**
     * Completely stops and unloads the WebView media player ONLY when the user switches to a
     * local device song or offline downloaded song.
     */
    fun stopAndClear() {
        currentLoadToken++
        isUserPaused = true
        isPausedByExternalAudio = false
        currentLoadedVideoId = ""
        hasFiredEndedForCurrentTrack = true
        hasSeenValidStartForCurrentToken = false
        lastKnownPositionSeconds = 0.0
        pendingResumeTargetSeconds = 0.0
        mainHandler.removeCallbacks(nativeHeartbeatRunnable)
        mainHandler.removeCallbacks(fallbackUrlReloadRunnable)
        mainHandler.post {
            try {
                evaluateJs(
                    """
                    (function() {
                        window.__akUserPaused = true;
                        window.__akExternalPaused = false;
                        window.__akTrackEndedFired = true;
                        window.__akTargetVideoId = '';
                        var v = document.querySelector('video');
                        if (v) {
                            try {
                                if (v.__akOrigPause) v.__akOrigPause();
                                else v.pause();
                            } catch(e) {}
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
        val targetId = currentLoadedVideoId
        if (targetId.isBlank()) return
        isUserPaused = false
        isPausedByExternalAudio = false
        val savedSec = lastKnownPositionSeconds.coerceAtLeast(0.0)
        mainHandler.post {
            webView?.onResume()
            webView?.resumeTimers()
            try {
                BackgroundGamingAudioKeeper.getInstance(context).startGamingKeepAlive()
            } catch (_: Exception) {
            }
            webView?.evaluateJavascript(
                """
                (function() {
                    window.__akUserPaused = false;
                    window.__akExternalPaused = false;
                    var mp = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
                    var matched = false;
                    try {
                        if (mp && typeof mp.getVideoData === 'function') {
                            var vd = mp.getVideoData();
                            if (vd && vd.video_id === '$targetId') {
                                matched = true;
                            }
                        }
                    } catch (e) {}
                    if (!matched && (window.location.href || '').indexOf('$targetId') === -1) {
                        return 'NEEDS_RELOAD';
                    }
                    var v = document.querySelector('video');
                    if (!v) {
                        return 'NEEDS_RELOAD';
                    }
                    v.muted = false;
                    v.volume = 1.0;
                    var bestSaved = Math.max(window.__akLastGoodTime || 0, $savedSec || 0);
                    if (bestSaved > 2.0 && v.currentTime < 1.0) {
                        window.__akResumeTargetSeconds = bestSaved;
                        try {
                            if (mp && typeof mp.seekTo === 'function') {
                                mp.seekTo(bestSaved, true);
                            }
                            v.currentTime = bestSaved;
                        } catch(e) {}
                    }
                    if (v.paused && !window.__akTrackEndedFired) {
                        if (mp && typeof mp.playVideo === 'function') {
                            try { mp.playVideo(); } catch(e) {}
                        }
                        var p = v.play();
                        if (p && typeof p.catch === 'function') { p.catch(function(){}); }
                    }
                    if (typeof window.__akSyncMediaSession === 'function') {
                        window.__akSyncMediaSession();
                    }
                    return 'OK';
                })();
                """.trimIndent()
            ) { result ->
                if (result?.contains("NEEDS_RELOAD") == true && currentLoadedVideoId.isNotBlank()) {
                    loadVideoById(
                        rawVideoId = currentLoadedVideoId,
                        startSeconds = savedSec,
                        songTitle = currentSongTitle,
                        songArtist = currentSongArtist,
                        artworkUrl = currentSongArtworkUrl
                    )
                }
            }
            mainHandler.removeCallbacks(nativeHeartbeatRunnable)
            mainHandler.postDelayed(nativeHeartbeatRunnable, 400L)
        }
    }

    fun pauseVideo() {
        isUserPaused = true
        isPausedByExternalAudio = false
        mainHandler.post {
            evaluateJs(
                """
                (function() {
                    window.__akUserPaused = true;
                    window.__akExternalPaused = false;
                    var v = document.querySelector('video');
                    if (v && v.currentTime > 0.5) {
                        window.__akLastGoodTime = v.currentTime;
                    }
                    var mp = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
                    if (mp && typeof mp.pauseVideo === 'function') {
                        try { mp.pauseVideo(); } catch(e) {}
                    }
                    if (v && !v.paused) {
                        try {
                            if (v.__akOrigPause) v.__akOrigPause();
                            else v.pause();
                        } catch(e) {}
                    }
                    if (typeof window.__akSyncMediaSession === 'function') {
                        window.__akSyncMediaSession();
                    }
                })();
                """.trimIndent()
            )
        }
    }

    /**
     * Pauses playback temporarily when the user starts playing a video or audio in another app,
     * preserving the exact playback position so it can automatically resume when the external video ends.
     */
    fun pauseForExternalAudio() {
        if (isUserPaused || currentLoadedVideoId.isBlank()) return
        isPausedByExternalAudio = true
        mainHandler.post {
            evaluateJs(
                """
                (function() {
                    window.__akExternalPaused = true;
                    var v = document.querySelector('video');
                    if (v && v.currentTime > 0.5) {
                        window.__akLastGoodTime = v.currentTime;
                    }
                    var mp = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
                    if (mp && typeof mp.pauseVideo === 'function') {
                        try { mp.pauseVideo(); } catch(e) {}
                    }
                    if (v && !v.paused) {
                        try {
                            if (v.__akOrigPause) v.__akOrigPause();
                            else v.pause();
                        } catch(e) {}
                    }
                    if (typeof window.__akSyncMediaSession === 'function') {
                        window.__akSyncMediaSession();
                    }
                })();
                """.trimIndent()
            )
        }
    }

    /**
     * Automatically resumes playback from the exact position where it paused as soon as the
     * external video/audio in the other app stops or closes.
     */
    fun resumeAfterExternalAudio() {
        if (isUserPaused || currentLoadedVideoId.isBlank() || hasFiredEndedForCurrentTrack) return
        isPausedByExternalAudio = false
        playVideo()
    }

    fun seekToSeconds(targetSeconds: Double) {
        if (currentLoadedVideoId.isBlank()) return
        val clamped = targetSeconds.coerceAtLeast(0.0)
        lastKnownPositionSeconds = clamped
        pendingResumeTargetSeconds = clamped
        hasSeenValidStartForCurrentToken = true
        mainHandler.post {
            evaluateJs(
                """
                (function() {
                    window.__akHasSeenStartOfTrack = true;
                    window.__akLastGoodTime = $clamped;
                    window.__akResumeTargetSeconds = 0;
                    var mp = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
                    if (mp && typeof mp.seekTo === 'function') {
                        try { mp.seekTo($clamped, true); } catch(e) {}
                    }
                    var v = document.querySelector('video');
                    if (v) {
                        try { v.currentTime = $clamped; } catch(e) {}
                        if (!window.__akUserPaused && !window.__akExternalPaused && v.paused) {
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

    fun scheduleUnexpectedPauseRecovery() {
        val delays = longArrayOf(40L, 180L, 420L, 850L, 1500L)
        for (d in delays) {
            mainHandler.postDelayed({
                if (!isUserPaused && !isPausedByExternalAudio && currentLoadedVideoId.isNotBlank() && !hasFiredEndedForCurrentTrack) {
                    keepAwake()
                }
            }, d)
        }
    }

    private fun injectVideoBridgeScript(token: Int = currentLoadToken, targetId: String = currentLoadedVideoId) {
        if (targetId.isBlank()) return
        val pausedFlag = if (isUserPaused) "true" else "false"
        val extPausedFlag = if (isPausedByExternalAudio) "true" else "false"
        val rate = currentPlaybackRate
        val resumeSec = pendingResumeTargetSeconds.coerceAtLeast(0.0)
        val hasStartFlag = if (resumeSec >= 1.0) "true" else "false"
        val safeTitle = escapeJsSingleQuoted(currentSongTitle)
        val safeArtist = escapeJsSingleQuoted(currentSongArtist)
        val safeArtwork = escapeJsSingleQuoted(currentSongArtworkUrl)
        val script = """
            (function() {
                window.__akUserPaused = $pausedFlag;
                window.__akExternalPaused = $extPausedFlag;
                window.__akPlaybackRate = $rate;
                window.__akSongTitle = '$safeTitle';
                window.__akSongArtist = '$safeArtist';
                window.__akSongArtwork = '$safeArtwork';
                if (window.__akLoadToken !== $token || window.__akTargetVideoId !== '$targetId') {
                    window.__akLoadToken = $token;
                    window.__akTargetVideoId = '$targetId';
                    window.__akTrackEndedFired = false;
                    window.__akErrorFired = false;
                    window.__akHasSeenStartOfTrack = $hasStartFlag;
                    window.__akMaxDurationForTrack = 0;
                    window.__akLastPlayAttempt = 0;
                    window.__akResumeTargetSeconds = $resumeSec;
                    window.__akLastGoodTime = $resumeSec;
                    window.__akLastReportedDur = 0;
                }

                // Spoof Page Visibility API & Focus so background / typing / scrolling in other apps never pauses audio
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

                window.__akSyncMediaSession = function() {
                    try {
                        if (!navigator.mediaSession) return;
                        var title = window.__akSongTitle || 'AADIFY Music';
                        var artist = window.__akSongArtist || 'AADIFY';
                        var art = window.__akSongArtwork || '';
                        if (typeof MediaMetadata !== 'undefined') {
                            var metaKey = title + '|' + artist + '|' + art;
                            if (window.__akLastMetaKey !== metaKey || !navigator.mediaSession.metadata) {
                                window.__akLastMetaKey = metaKey;
                                navigator.mediaSession.metadata = new MediaMetadata({
                                    title: title,
                                    artist: artist,
                                    album: 'AADIFY Music',
                                    artwork: art ? [
                                        { src: art, sizes: '96x96', type: 'image/jpeg' },
                                        { src: art, sizes: '128x128', type: 'image/jpeg' },
                                        { src: art, sizes: '192x192', type: 'image/jpeg' },
                                        { src: art, sizes: '256x256', type: 'image/jpeg' },
                                        { src: art, sizes: '384x384', type: 'image/jpeg' },
                                        { src: art, sizes: '512x512', type: 'image/jpeg' }
                                    ] : []
                                });
                            }
                        }
                        navigator.mediaSession.playbackState = (window.__akUserPaused || window.__akExternalPaused) ? 'paused' : 'playing';
                        if (typeof navigator.mediaSession.setActionHandler === 'function') {
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
                            navigator.mediaSession.setActionHandler('seekto', function(details) {
                                if (details && typeof details.seekTime === 'number' && window.AndroidBridge && window.AndroidBridge.onMediaSeekTo) {
                                    window.AndroidBridge.onMediaSeekTo(details.seekTime);
                                }
                            });
                        }
                    } catch (e) {}
                };
                window.__akSyncMediaSession();

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

                function isCurrentVideoMatchingTarget() {
                    var target = window.__akTargetVideoId || '';
                    if (!target) return false;
                    try {
                        var mp = getMoviePlayer();
                        if (mp && typeof mp.getVideoData === 'function') {
                            var vd = mp.getVideoData();
                            if (vd && typeof vd.video_id === 'string' && vd.video_id.length > 0) {
                                return vd.video_id === target;
                            }
                        }
                    } catch (e) {}
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
                    if (!isCurrentVideoMatchingTarget() || isAdPlaying()) return 0;
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
                    if (best >= 25 && window.__akHasSeenStartOfTrack) {
                        window.__akMaxDurationForTrack = best;
                    }
                    return best;
                }

                function applyResumeSeekIfNeeded(v) {
                    if (!v || !isCurrentVideoMatchingTarget() || isAdPlaying()) return;
                    var targetSec = window.__akResumeTargetSeconds || 0;
                    if (targetSec > 1.0 && v.readyState >= 1) {
                        var dur = getTrueDuration(v);
                        if (dur <= 0 || targetSec < dur - 2.0) {
                            if (v.currentTime < targetSec - 1.5) {
                                try {
                                    var mp = getMoviePlayer();
                                    if (mp && typeof mp.seekTo === 'function') {
                                        mp.seekTo(targetSec, true);
                                    }
                                    v.currentTime = targetSec;
                                    window.__akLastGoodTime = targetSec;
                                    window.__akHasSeenStartOfTrack = true;
                                } catch (e) {}
                            }
                            if (v.currentTime >= targetSec - 1.5) {
                                window.__akResumeTargetSeconds = 0;
                            }
                        } else {
                            window.__akResumeTargetSeconds = 0;
                        }
                    }
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
                    if (!isCurrentVideoMatchingTarget() || !window.__akHasSeenStartOfTrack) return;
                    window.__akTrackEndedFired = true;
                    window.__akLastGoodTime = 0;
                    window.__akResumeTargetSeconds = 0;
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
                    if (!v || window.__akUserPaused || window.__akExternalPaused || window.__akTrackEndedFired || !v.paused || v.ended) return;
                    if (!isCurrentVideoMatchingTarget()) return;
                    var totalDur = getTrueDuration(v);
                    if (window.__akHasSeenStartOfTrack && totalDur >= 25 && v.currentTime >= totalDur - 0.5) {
                        notifyTrackEnded(v);
                        return;
                    }
                    var now = Date.now();
                    if (now - (window.__akLastPlayAttempt || 0) < 25) return;
                    window.__akLastPlayAttempt = now;

                    dismissContinueWatchingDialog();
                    applyResumeSeekIfNeeded(v);
                    v.muted = false;
                    v.volume = 1.0;

                    try {
                        var mp = getMoviePlayer();
                        if (mp && typeof mp.playVideo === 'function') {
                            try { mp.playVideo(); } catch (e1) {}
                        }
                        var p = v.play();
                        if (p && typeof p.catch === 'function') {
                            p.catch(function() {
                                if (mp && typeof mp.playVideo === 'function') {
                                    try { mp.playVideo(); } catch (e2) {}
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
                        if (!isCurrentVideoMatchingTarget()) return;
                        applyResumeSeekIfNeeded(v);
                        if (!window.__akUserPaused && !window.__akExternalPaused && !window.__akTrackEndedFired && v.paused) {
                            tryPlayVideo(v);
                        }
                    });

                    v.addEventListener('playing', function() {
                        if (window.__akUserPaused || window.__akExternalPaused) {
                            try { v.pause(); } catch (e) {}
                            return;
                        }
                        if (!isCurrentVideoMatchingTarget()) return;
                        applyResumeSeekIfNeeded(v);
                        if (!isAdPlaying() && v.currentTime >= 0.05 && (v.currentTime < 12.0 || (window.__akResumeTargetSeconds || 0) >= 1.0)) {
                            window.__akHasSeenStartOfTrack = true;
                        }
                        window.__akLastReportedState = 1;
                        if (typeof window.__akSyncMediaSession === 'function') {
                            window.__akSyncMediaSession();
                        }
                        if (window.AndroidBridge) {
                            window.AndroidBridge.onStateChange(1);
                            var dur = getTrueDuration(v);
                            if (dur > 0) {
                                window.AndroidBridge.onDuration(dur);
                            }
                        }
                    });

                    v.addEventListener('play', function() {
                        if (window.__akUserPaused || window.__akExternalPaused) {
                            try { v.pause(); } catch (e) {}
                            return;
                        }
                        if (!isCurrentVideoMatchingTarget()) return;
                        applyResumeSeekIfNeeded(v);
                        window.__akLastReportedState = 1;
                        if (typeof window.__akSyncMediaSession === 'function') {
                            window.__akSyncMediaSession();
                        }
                        if (window.AndroidBridge) {
                            window.AndroidBridge.onStateChange(1);
                        }
                    });

                    v.addEventListener('pause', function() {
                        if (!isCurrentVideoMatchingTarget()) return;
                        var isAd = isAdPlaying();
                        var totalDur = getTrueDuration(v);
                        if (!isAd && window.__akHasSeenStartOfTrack && totalDur >= 25 && v.currentTime >= totalDur - 0.85) {
                            notifyTrackEnded(v);
                            return;
                        }
                        if (window.__akUserPaused || window.__akExternalPaused) {
                            window.__akLastReportedState = 2;
                            if (typeof window.__akSyncMediaSession === 'function') {
                                window.__akSyncMediaSession();
                            }
                            if (window.AndroidBridge) {
                                window.AndroidBridge.onStateChange(2);
                            }
                        } else if (!window.__akTrackEndedFired && !v.ended) {
                            // Immediately recover if a game (Free Fire / PUBG) or background switch steals audio focus!
                            var resumeDelays = [25, 90, 180, 350, 650, 1100];
                            for (var rIdx = 0; rIdx < resumeDelays.length; rIdx++) {
                                (function(delayMs) {
                                    setTimeout(function() {
                                        if (!window.__akUserPaused && !window.__akExternalPaused && !window.__akTrackEndedFired && v.paused && !v.ended) {
                                            tryPlayVideo(v);
                                        }
                                    }, delayMs);
                                })(resumeDelays[rIdx]);
                            }
                            if (window.AndroidBridge && typeof window.AndroidBridge.onUnexpectedPause === 'function') {
                                window.AndroidBridge.onUnexpectedPause();
                            }
                        }
                    });

                    v.addEventListener('waiting', function() {
                        if (!window.__akUserPaused && !window.__akExternalPaused && !window.__akTrackEndedFired && isCurrentVideoMatchingTarget() && window.AndroidBridge) {
                            window.__akLastReportedState = 3;
                            window.AndroidBridge.onStateChange(3);
                        }
                    });

                    v.addEventListener('loadedmetadata', function() {
                        v.loop = false;
                        if (!isCurrentVideoMatchingTarget()) return;
                        applyResumeSeekIfNeeded(v);
                        var dur = getTrueDuration(v);
                        if (dur > 0 && window.AndroidBridge) {
                            window.AndroidBridge.onDuration(dur);
                        }
                    });

                    v.addEventListener('durationchange', function() {
                        if (!isCurrentVideoMatchingTarget()) return;
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
                        if (!isCurrentVideoMatchingTarget()) return;

                        applyResumeSeekIfNeeded(v);

                        if (!v.paused && !v.ended && v.currentTime >= 0.05) {
                            if (!window.__akHasSeenStartOfTrack) {
                                // Only mark start of track once we see a fresh timestamp near the start (not a leftover timestamp from the previous track!)
                                if (v.currentTime < 12.0 || (window.__akResumeTargetSeconds || 0) >= 1.0) {
                                    window.__akHasSeenStartOfTrack = true;
                                }
                            }
                            if (window.__akHasSeenStartOfTrack && v.currentTime > 0.5) {
                                window.__akLastGoodTime = v.currentTime;
                            }
                        }

                        var totalDur = getTrueDuration(v);
                        if (window.__akHasSeenStartOfTrack && totalDur >= 25 && v.currentTime >= totalDur - 0.5) {
                            notifyTrackEnded(v);
                            return;
                        }
                        if (window.__akHasSeenStartOfTrack && v.currentTime >= 0) {
                            window.AndroidBridge.onProgress(v.currentTime);
                            if (window.__akLastReportedState !== 1 && !v.paused) {
                                window.__akLastReportedState = 1;
                                window.AndroidBridge.onStateChange(1);
                            }
                        }
                        if (totalDur > 0 && Math.abs((window.__akLastReportedDur || 0) - totalDur) > 0.5) {
                            window.__akLastReportedDur = totalDur;
                            window.AndroidBridge.onDuration(totalDur);
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
                        dismissContinueWatchingDialog();
                        if (typeof window.__akSyncMediaSession === 'function') {
                            window.__akSyncMediaSession();
                        }

                        var v = document.querySelector('video');
                        if (!v) return;
                        hookVideoElement(v);
                        if (v.loop) { v.loop = false; }
                        if (window.__akPlaybackRate && Math.abs(v.playbackRate - window.__akPlaybackRate) > 0.05) {
                            try { v.playbackRate = window.__akPlaybackRate; } catch (e) {}
                        }

                        if (isAdPlaying()) {
                            var skipBtn = document.querySelector('.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button');
                            if (skipBtn) { skipBtn.click(); }
                            if (v.paused && !window.__akUserPaused && !window.__akExternalPaused) {
                                try { v.play(); } catch(e) {}
                            }
                            return;
                        }

                        if (!isCurrentVideoMatchingTarget()) {
                            return;
                        }

                        applyResumeSeekIfNeeded(v);

                        if (!v.paused && !v.ended && v.currentTime >= 0.05) {
                            if (!window.__akHasSeenStartOfTrack) {
                                if (v.currentTime < 12.0 || (window.__akResumeTargetSeconds || 0) >= 1.0) {
                                    window.__akHasSeenStartOfTrack = true;
                                }
                            }
                            if (window.__akHasSeenStartOfTrack && v.currentTime > 0.5) {
                                window.__akLastGoodTime = v.currentTime;
                            }
                        }

                        var totalDur = getTrueDuration(v);
                        if (!window.__akTrackEndedFired && window.__akHasSeenStartOfTrack) {
                            var mpState = -1;
                            try {
                                var mp = getMoviePlayer();
                                if (mp && typeof mp.getPlayerState === 'function') {
                                    mpState = mp.getPlayerState();
                                }
                            } catch (e) {}
                            if (v.ended || mpState === 0 || (totalDur >= 25 && v.currentTime >= totalDur - 0.5)) {
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

                        if (!window.__akUserPaused && !window.__akExternalPaused && !window.__akTrackEndedFired && v.paused && !v.ended) {
                            tryPlayVideo(v);
                        }

                        if (!window.__akUserPaused && !window.__akExternalPaused && !window.__akTrackEndedFired && !v.paused && window.__akHasSeenStartOfTrack && v.currentTime > 0 && window.AndroidBridge) {
                            window.AndroidBridge.onProgress(v.currentTime);
                            if (totalDur > 0 && Math.abs((window.__akLastReportedDur || 0) - totalDur) > 0.5) {
                                window.__akLastReportedDur = totalDur;
                                window.AndroidBridge.onDuration(totalDur);
                            }
                        }
                    } catch (e) {}
                };

                setInterval(function() {
                    if (typeof window.__akTick === 'function') {
                        window.__akTick();
                    }
                }, 380);
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
        mainHandler.removeCallbacks(fallbackUrlReloadRunnable)
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
        fun onUnexpectedPause() {
            mainHandler.post {
                if (!isUserPaused && !isPausedByExternalAudio && currentLoadedVideoId.isNotBlank() && !hasFiredEndedForCurrentTrack) {
                    if (onUnexpectedPauseDetected != null) {
                        onUnexpectedPauseDetected.invoke()
                    } else {
                        scheduleUnexpectedPauseRecovery()
                    }
                }
            }
        }

        @JavascriptInterface
        fun onProgress(currentTimeSeconds: Double) {
            val tokenSnapshot = currentLoadToken
            if (currentTimeSeconds.isNaN() || currentTimeSeconds < 0.0 || hasFiredEndedForCurrentTrack || currentLoadedVideoId.isBlank()) return
            if (currentTimeSeconds > 0.5) {
                lastKnownPositionSeconds = currentTimeSeconds
                if (pendingResumeTargetSeconds > 0.0 && currentTimeSeconds >= pendingResumeTargetSeconds - 1.5) {
                    pendingResumeTargetSeconds = 0.0
                }
            }
            val posMs = (currentTimeSeconds * 1000.0).toLong()
            if (posMs >= 100L) {
                hasSeenValidStartForCurrentToken = true
                mainHandler.removeCallbacks(fallbackUrlReloadRunnable)
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
                    if (!hasSeenValidStartForCurrentToken || elapsedSinceLoad < 4_000L) {
                        return@post
                    }
                    if (hasFiredEndedForCurrentTrack) return@post
                    hasFiredEndedForCurrentTrack = true
                    lastKnownPositionSeconds = 0.0
                    pendingResumeTargetSeconds = 0.0
                    mainHandler.removeCallbacks(nativeHeartbeatRunnable)
                    mainHandler.removeCallbacks(fallbackUrlReloadRunnable)
                    onStateChangeUpdate(STATE_ENDED)
                    return@post
                }
                if (hasFiredEndedForCurrentTrack) return@post
                if (state == STATE_PLAYING) {
                    hasSeenValidStartForCurrentToken = true
                    mainHandler.removeCallbacks(fallbackUrlReloadRunnable)
                    if (isUserPaused) {
                        pauseVideo()
                        return@post
                    }
                    if (isPausedByExternalAudio) {
                        pauseForExternalAudio()
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

        @JavascriptInterface
        fun onMediaSeekTo(seekSeconds: Double) {
            mainHandler.post {
                if (!seekSeconds.isNaN() && seekSeconds >= 0.0) {
                    seekToSeconds(seekSeconds)
                    onProgressUpdate((seekSeconds * 1000.0).toLong())
                }
            }
        }
    }
}
