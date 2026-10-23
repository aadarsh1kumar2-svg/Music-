package com.example.services

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.example.models.Song

/**
 * Hidden Backstage YouTube IFrame Player Engine layered directly behind the album banner.
 * Uses a persistent WebView with JavaScript, DomStorage, and mediaPlaybackRequiresUserGesture = false,
 * communicating playback progress, duration, and state changes back to Kotlin via AndroidBridge.
 */
class BackstageAudioWebView(context: Context) : WebView(context) {
    override fun onWindowVisibilityChanged(visibility: Int) {
        // Keep the backstage WebView active even when covered or when the app is in background
        super.onWindowVisibilityChanged(View.VISIBLE)
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
    private var isPlayerReady = false
    private var pendingVideoId: String? = null
    private var currentLoadedVideoId: String = ""
    private var currentPlaybackRate: Float = 1.0f

    @SuppressLint("SetJavaScriptEnabled")
    fun getOrCreateWebView(): BackstageAudioWebView {
        val existing = webView
        if (existing != null) {
            return existing
        }

        val created = BackstageAudioWebView(context.applicationContext).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            isFocusable = false
            isFocusableInTouchMode = false

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                databaseEnabled = true
                loadsImagesAutomatically = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                cacheMode = WebSettings.LOAD_DEFAULT
                userAgentString =
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
            }

            addJavascriptInterface(AndroidBridge(), "AndroidBridge")

            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                    return true
                }
            }

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    return false
                }
            }

            loadDataWithBaseURL(
                "https://www.youtube.com",
                buildIFramePlayerHtml(),
                "text/html",
                "UTF-8",
                null
            )
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
        }
    }

    fun loadVideoById(rawVideoId: String) {
        val cleanId = Song.extractYoutubeVideoId(rawVideoId)
        if (cleanId.isBlank()) {
            onErrorUpdate(-1)
            return
        }
        currentLoadedVideoId = cleanId
        pendingVideoId = cleanId

        mainHandler.post {
            getOrCreateWebView()
            if (isPlayerReady) {
                val safeId = cleanId.replace("'", "\\'")
                evaluateJs("if (window.ytPlayer && window.ytPlayer.loadVideoById) { window.ytPlayer.loadVideoById('$safeId'); window.ytPlayer.playVideo(); }")
            }
        }
    }

    fun playVideo() {
        mainHandler.post {
            if (isPlayerReady) {
                evaluateJs("if (window.ytPlayer && window.ytPlayer.playVideo) { window.ytPlayer.playVideo(); }")
            } else if (!pendingVideoId.isNullOrBlank()) {
                loadVideoById(pendingVideoId!!)
            }
        }
    }

    fun pauseVideo() {
        mainHandler.post {
            if (isPlayerReady) {
                evaluateJs("if (window.ytPlayer && window.ytPlayer.pauseVideo) { window.ytPlayer.pauseVideo(); }")
            }
        }
    }

    fun seekToSeconds(targetSeconds: Double) {
        val clamped = targetSeconds.coerceAtLeast(0.0)
        mainHandler.post {
            if (isPlayerReady) {
                evaluateJs("if (window.ytPlayer && window.ytPlayer.seekTo) { window.ytPlayer.seekTo($clamped, true); }")
            }
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        currentPlaybackRate = speed
        mainHandler.post {
            if (isPlayerReady) {
                evaluateJs("if (window.ytPlayer && window.ytPlayer.setPlaybackRate) { window.ytPlayer.setPlaybackRate($speed); }")
            }
        }
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
            isPlayerReady = false
        }
    }

    inner class AndroidBridge {
        @JavascriptInterface
        fun onReady() {
            mainHandler.post {
                isPlayerReady = true
                val targetId = pendingVideoId
                if (!targetId.isNullOrBlank()) {
                    val safeId = targetId.replace("'", "\\'")
                    evaluateJs("if (window.ytPlayer && window.ytPlayer.loadVideoById) { window.ytPlayer.loadVideoById('$safeId'); window.ytPlayer.setPlaybackRate($currentPlaybackRate); window.ytPlayer.playVideo(); }")
                }
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
            Log.w(TAG, "YouTube IFrame Player error code: $errorCode for $currentLoadedVideoId")
            mainHandler.post {
                onErrorUpdate(errorCode)
            }
        }
    }

    private fun buildIFramePlayerHtml(): String {
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
                <style>
                    html, body {
                        margin: 0;
                        padding: 0;
                        width: 100%;
                        height: 100%;
                        background-color: #000000;
                        overflow: hidden;
                    }
                    #player {
                        width: 100%;
                        height: 100%;
                        border: 0;
                    }
                </style>
            </head>
            <body>
                <div id="player"></div>
                <script>
                    var tag = document.createElement('script');
                    tag.src = "https://www.youtube.com/iframe_api";
                    var firstScriptTag = document.getElementsByTagName('script')[0];
                    firstScriptTag.parentNode.insertBefore(tag, firstScriptTag);

                    window.ytPlayer = null;
                    var progressTimer = null;

                    function onYouTubeIframeAPIReady() {
                        window.ytPlayer = new YT.Player('player', {
                            width: '100%',
                            height: '100%',
                            videoId: '',
                            playerVars: {
                                'controls': 0,
                                'autoplay': 1,
                                'playsinline': 1,
                                'rel': 0,
                                'fs': 0,
                                'modestbranding': 1,
                                'iv_load_policy': 3,
                                'disablekb': 1,
                                'origin': 'https://www.youtube.com'
                            },
                            events: {
                                'onReady': onPlayerReady,
                                'onStateChange': onPlayerStateChange,
                                'onError': onPlayerError
                            }
                        });
                    }

                    function onPlayerReady(event) {
                        if (window.AndroidBridge && window.AndroidBridge.onReady) {
                            window.AndroidBridge.onReady();
                        }
                        startProgressPolling();
                    }

                    function onPlayerStateChange(event) {
                        var state = event.data;
                        if (window.AndroidBridge && window.AndroidBridge.onStateChange) {
                            window.AndroidBridge.onStateChange(state);
                        }
                        syncTimeAndDuration();
                    }

                    function onPlayerError(event) {
                        if (window.AndroidBridge && window.AndroidBridge.onError) {
                            window.AndroidBridge.onError(event.data || -1);
                        }
                    }

                    function syncTimeAndDuration() {
                        try {
                            if (!window.ytPlayer) return;
                            if (typeof window.ytPlayer.getDuration === 'function') {
                                var dur = window.ytPlayer.getDuration();
                                if (dur && dur > 0 && window.AndroidBridge && window.AndroidBridge.onDuration) {
                                    window.AndroidBridge.onDuration(dur);
                                }
                            }
                            if (typeof window.ytPlayer.getCurrentTime === 'function') {
                                var cur = window.ytPlayer.getCurrentTime();
                                if (cur >= 0 && window.AndroidBridge && window.AndroidBridge.onProgress) {
                                    window.AndroidBridge.onProgress(cur);
                                }
                            }
                        } catch (e) {}
                    }

                    function startProgressPolling() {
                        if (progressTimer) clearInterval(progressTimer);
                        progressTimer = setInterval(function() {
                            syncTimeAndDuration();
                        }, 250);
                    }
                </script>
            </body>
            </html>
        """.trimIndent()
    }
}
