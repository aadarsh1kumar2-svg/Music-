package com.example.screens

import android.annotation.SuppressLint
import android.view.TextureView
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.example.security.AadifySecurityVault
import com.example.services.AudioStreamExtractor
import com.example.services.ResolvedAudioStream
import com.example.ui.theme.DeepCharcoal
import com.example.ui.theme.MutedGray
import com.example.ui.theme.PrimaryWhite
import com.example.ui.theme.PureBlack
import com.example.ui.theme.SpotifyDarkGreenPill
import com.example.ui.theme.SpotifyGreenBorder
import com.example.ui.theme.SpotifyVibrantGreen
import kotlinx.coroutines.delay

@OptIn(UnstableApi::class)
@Composable
fun PromoVideoAdOverlay(
    resolvedStream: ResolvedAudioStream?,
    onAdCompleted: () -> Unit
) {
    val context = LocalContext.current
    val adVideoId = remember { AadifySecurityVault.getPromoAdVideoId() }
    val latestOnCompleted by rememberUpdatedState(onAdCompleted)
    var hasCompleted by remember { mutableStateOf(false) }

    fun finishAdOnce() {
        if (!hasCompleted) {
            hasCompleted = true
            latestOnCompleted()
        }
    }

    BackHandler(enabled = true) {
        // Ad cannot be dismissed early by Back button; next song plays automatically when video ends
    }

    var remainingSeconds by remember { mutableIntStateOf(-1) }
    var progressFraction by remember { mutableFloatStateOf(0f) }
    var isVideoReady by remember { mutableStateOf(false) }
    var useWebViewFallback by remember { mutableStateOf(false) }

    // Wait up to 2.2s for direct MP4 extraction before engaging WebView fallback
    LaunchedEffect(resolvedStream) {
        if (resolvedStream == null) {
            delay(2_200L)
            if (resolvedStream == null && !hasCompleted) {
                useWebViewFallback = true
            }
        } else {
            useWebViewFallback = false
        }
    }

    // Safety watchdog: never leave user permanently stuck if video fails to load offline
    LaunchedEffect(isVideoReady) {
        if (!isVideoReady) {
            delay(18_000L)
            if (!isVideoReady && !hasCompleted) {
                finishAdOnce()
            }
        }
    }

    Dialog(
        onDismissRequest = { /* Mandatory Ad until video finishes */ },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            color = PureBlack,
            modifier = Modifier
                .fillMaxSize()
                .testTag("promo_video_ad_overlay")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = SpotifyDarkGreenPill,
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.border(1.dp, SpotifyGreenBorder, RoundedCornerShape(50))
                    ) {
                        Text(
                            text = "SPONSORED AD",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = SpotifyVibrantGreen,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 0.8.sp
                            ),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }

                    Text(
                        text = if (remainingSeconds > 0) {
                            "Next song in ${remainingSeconds}s"
                        } else {
                            "Next song plays after video"
                        },
                        style = MaterialTheme.typography.labelMedium.copy(
                            color = PrimaryWhite,
                            fontWeight = FontWeight.SemiBold
                        ),
                        modifier = Modifier.testTag("promo_ad_countdown_text")
                    )
                }

                // Video Stage
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(DeepCharcoal)
                        .border(1.dp, SpotifyGreenBorder, RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (resolvedStream != null && !useWebViewFallback) {
                        NativeExoVideoAdPlayer(
                            stream = resolvedStream,
                            onProgress = { posMs, durMs ->
                                isVideoReady = true
                                if (durMs > 0L) {
                                    progressFraction = (posMs.toFloat() / durMs.toFloat()).coerceIn(0f, 1f)
                                    val rem = ((durMs - posMs) / 1000L).toInt().coerceAtLeast(0)
                                    remainingSeconds = rem
                                }
                            },
                            onEnded = { finishAdOnce() },
                            onError = { useWebViewFallback = true }
                        )
                    } else if (useWebViewFallback) {
                        WebViewVideoAdFallback(
                            videoId = adVideoId,
                            onProgress = { curSec, durSec ->
                                isVideoReady = true
                                if (durSec > 0f) {
                                    progressFraction = (curSec / durSec).coerceIn(0f, 1f)
                                    remainingSeconds = (durSec - curSec).toInt().coerceAtLeast(0)
                                }
                            },
                            onEnded = { finishAdOnce() }
                        )
                    }

                    if (!isVideoReady) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(
                                color = SpotifyVibrantGreen,
                                modifier = Modifier.size(38.dp)
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "Loading Ad Video...",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = MutedGray,
                                    fontWeight = FontWeight.Medium
                                )
                            )
                        }
                    }
                }

                // Footer Progress
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    LinearProgressIndicator(
                        progress = { progressFraction },
                        color = SpotifyVibrantGreen,
                        trackColor = Color(0xFF282828),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Your next song will start automatically when this video ends.",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MutedGray,
                            textAlign = TextAlign.Center
                        ),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun NativeExoVideoAdPlayer(
    stream: ResolvedAudioStream,
    onProgress: (posMs: Long, durMs: Long) -> Unit,
    onEnded: () -> Unit,
    onError: () -> Unit
) {
    val context = LocalContext.current
    val latestOnProgress by rememberUpdatedState(onProgress)
    val latestOnEnded by rememberUpdatedState(onEnded)
    val latestOnError by rememberUpdatedState(onError)

    val exoPlayer = remember(stream.streamUrl) {
        val headers = AudioStreamExtractor.resolveHeadersForStreamUrl(stream.streamUrl)
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(stream.userAgent)
            .setDefaultRequestProperties(headers)
            .setAllowCrossProtocolRedirects(true)
        val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory)
            .createMediaSource(MediaItem.fromUri(stream.streamUrl))

        ExoPlayer.Builder(context)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true
            )
            .build().apply {
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED) {
                            latestOnEnded()
                        } else if (playbackState == Player.STATE_READY) {
                            latestOnProgress(currentPosition, duration)
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        latestOnError()
                    }
                })
                setMediaSource(mediaSource)
                prepare()
                playWhenReady = true
            }
    }

    LaunchedEffect(exoPlayer) {
        while (true) {
            if (exoPlayer.playbackState == Player.STATE_ENDED) {
                latestOnEnded()
                break
            }
            if (exoPlayer.duration > 0L) {
                latestOnProgress(exoPlayer.currentPosition, exoPlayer.duration)
            }
            delay(300L)
        }
    }

    DisposableEffect(exoPlayer) {
        onDispose {
            try {
                exoPlayer.release()
            } catch (_: Exception) {
            }
        }
    }

    AndroidView(
        factory = { ctx ->
            TextureView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                exoPlayer.setVideoTextureView(this)
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebViewVideoAdFallback(
    videoId: String,
    onProgress: (curSec: Float, durSec: Float) -> Unit,
    onEnded: () -> Unit
) {
    val latestOnProgress by rememberUpdatedState(onProgress)
    val latestOnEnded by rememberUpdatedState(onEnded)
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            try {
                webViewRef?.stopLoading()
                webViewRef?.destroy()
            } catch (_: Exception) {
            }
            webViewRef = null
        }
    }

    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                webChromeClient = WebChromeClient()

                val bridge = object {
                    @JavascriptInterface
                    fun notifyEnded() {
                        post { latestOnEnded() }
                    }

                    @JavascriptInterface
                    fun notifyProgress(cur: Float, dur: Float) {
                        post { latestOnProgress(cur, dur) }
                    }
                }
                addJavascriptInterface(bridge, "AadifyAdBridge")

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        view?.evaluateJavascript(
                            """
                            (function() {
                              if (window.__aadifyAdBound) return;
                              window.__aadifyAdBound = true;
                              var t = setInterval(function() {
                                var v = document.querySelector('video');
                                if (!v) return;
                                if (!v.__adHooked) {
                                  v.__adHooked = true;
                                  v.addEventListener('ended', function() {
                                    if (window.AadifyAdBridge) window.AadifyAdBridge.notifyEnded();
                                  });
                                }
                                if (v.paused && v.readyState >= 2) {
                                  v.muted = false;
                                  var p = v.play();
                                  if (p && p.catch) {
                                    p.catch(function() { v.muted = true; v.play(); });
                                  }
                                }
                                if (v.duration && isFinite(v.duration) && v.duration > 0) {
                                  if (window.AadifyAdBridge) window.AadifyAdBridge.notifyProgress(v.currentTime || 0, v.duration);
                                  if (v.currentTime >= v.duration - 0.35) {
                                    clearInterval(t);
                                    if (window.AadifyAdBridge) window.AadifyAdBridge.notifyEnded();
                                  }
                                }
                              }, 400);
                            })();
                            """.trimIndent(),
                            null
                        )
                    }
                }

                loadUrl("https://m.youtube.com/watch?v=$videoId&autoplay=1")
                webViewRef = this
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}
