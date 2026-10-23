package com.example.services

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
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
 * Custom WebView that always reports View.VISIBLE to Chromium so HTML5 audio/video
 * playback is never throttled or paused when covered by the Album Banner poster
 * or when the screen is minimized.
 */
class BackstageAudioWebView(context: Context) : WebView(context) {
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(View.VISIBLE)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, View.VISIBLE)
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

    @SuppressLint("SetJavaScriptEnabled")
    fun getOrCreateWebView(): BackstageAudioWebView {
        val existing = webView
        if (existing != null) {
            return existing
        }

        val created = BackstageAudioWebView(context.applicationContext).apply {
            setBackgroundColor(Color.BLACK)
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            isFocusable = false
            isFocusableInTouchMode = false
            keepScreenOn = true

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

                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    super.onProgressChanged(view, newProgress)
                    if (newProgress >= 35) {
                        injectVideoBridgeScript()
                    }
                }
            }

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    injectVideoBridgeScript()
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
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
                    // Keep only youtube.com / google pages inside the backstage player
                    return !(reqUrl.contains("youtube.com") ||
                            reqUrl.contains("youtu.be") ||
                            reqUrl.contains("google.com") ||
                            reqUrl.contains("googlevideo.com"))
                }
            }
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
            if (!userRequestedPause && currentLoadedVideoId.isNotBlank()) {
                mainHandler.postDelayed({ playVideo() }, 80L)
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
            val watchUrl = "https://m.youtube.com/watch?v=$cleanId&autoplay=1"
            view.loadUrl(watchUrl)
        }
    }

    fun playVideo() {
        userRequestedPause = false
        mainHandler.post {
            evaluateJs(
                """
                (function() {
                    window.__akUserPaused = false;
                    if (window.ytPlayer && typeof window.ytPlayer.playVideo === 'function') {
                        window.ytPlayer.playVideo();
                    }
                    var v = document.querySelector('video');
                    if (v) {
                        v.muted = false;
                        v.volume = 1.0;
                        var p = v.play();
                        if (p && typeof p.catch === 'function') { p.catch(function(){}); }
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
                    if (window.ytPlayer && typeof window.ytPlayer.pauseVideo === 'function') {
                        window.ytPlayer.pauseVideo();
                    }
                    var v = document.querySelector('video');
                    if (v) {
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
                    if (window.ytPlayer && typeof window.ytPlayer.seekTo === 'function') {
                        window.ytPlayer.seekTo($clamped, true);
                    }
                    var v = document.querySelector('video');
                    if (v) {
                        v.currentTime = $clamped;
                        if (!window.__akUserPaused) {
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
                    if (window.ytPlayer && typeof window.ytPlayer.setPlaybackRate === 'function') {
                        window.ytPlayer.setPlaybackRate($speed);
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
                if (window.__akBridgeInstalled) return;
                window.__akBridgeInstalled = true;

                function hookVideoElement(v) {
                    if (!v || v.__akHooked) return;
                    v.__akHooked = true;
                    v.setAttribute('playsinline', 'true');
                    v.setAttribute('webkit-playsinline', 'true');
                    v.playsInline = true;
                    v.muted = false;
                    v.volume = 1.0;
                    if (window.__akPlaybackRate) {
                        v.playbackRate = window.__akPlaybackRate;
                    }

                    v.addEventListener('playing', function() {
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
                        if (window.__akUserPaused && window.AndroidBridge) {
                            window.AndroidBridge.onStateChange(2);
                        }
                    });

                    v.addEventListener('waiting', function() {
                        if (window.AndroidBridge) {
                            window.AndroidBridge.onStateChange(3);
                        }
                    });

                    v.addEventListener('loadedmetadata', function() {
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

                        // Skip ads automatically if any pre-roll appears
                        var isAd = !!document.querySelector('.ad-showing, .ad-interrupting');
                        if (isAd) {
                            var skipBtn = document.querySelector('.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button');
                            if (skipBtn) { skipBtn.click(); }
                            else if (v.duration && v.duration > 0 && v.duration < 120) {
                                v.currentTime = v.duration;
                            }
                            return;
                        }

                        // Ensure unmuted and playing unless user paused
                        if (v.muted) {
                            v.muted = false;
                            v.volume = 1.0;
                        }

                        if (!window.__akUserPaused && v.paused && !v.ended) {
                            var playPromise = v.play();
                            if (playPromise && typeof playPromise.catch === 'function') {
                                playPromise.catch(function() {
                                    var playBtn = document.querySelector('.ytp-large-play-button, .ytp-play-button, button.player-control-play-pause-icon');
                                    if (playBtn) { playBtn.click(); }
                                });
                            }
                        }

                        if (!v.paused && v.currentTime > 0 && window.AndroidBridge) {
                            window.AndroidBridge.onStateChange(1);
                            window.AndroidBridge.onProgress(v.currentTime);
                            if (v.duration && v.duration > 0) {
                                window.AndroidBridge.onDuration(v.duration);
                            }
                        }
                    } catch (e) {}
                }, 250);
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
