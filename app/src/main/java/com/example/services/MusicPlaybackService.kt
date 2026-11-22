package com.example.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.util.LruCache
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ForwardingTimeline
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.MediaStyleNotificationHelper
import com.example.MainActivity
import com.example.R
import com.example.models.Song
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

class MusicPlaybackService : MediaSessionService() {

    private data class SyncRequest(
        val song: Song,
        val isPlaying: Boolean,
        val directStreamUri: String?,
        val playbackSpeed: Float,
        val startPositionMs: Long = 0L
    )

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "aadify_playback_channel_v3"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_NOTIFICATION_PREV = "com.example.services.ACTION_NOTIFICATION_PREV"
        const val ACTION_NOTIFICATION_PLAY_PAUSE = "com.example.services.ACTION_NOTIFICATION_PLAY_PAUSE"
        const val ACTION_NOTIFICATION_NEXT = "com.example.services.ACTION_NOTIFICATION_NEXT"
        const val ACTION_NOTIFICATION_STOP = "com.example.services.ACTION_NOTIFICATION_STOP"
        const val ACTION_WATCH_NOTIFICATIONS = "com.example.services.ACTION_WATCH_NOTIFICATIONS"

        private val mainHandler = Handler(Looper.getMainLooper())
        private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val artworkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
        private val artworkBytesCache = object : LruCache<String, ByteArray>(24) {}

        @Volatile
        private var instance: MusicPlaybackService? = null

        private val sessionIdCounter = java.util.concurrent.atomic.AtomicLong(0L)

        @Volatile
        private var isForegroundServiceStarted: Boolean = false

        @Volatile
        private var isBackgroundNotifWatcherStarted: Boolean = false

        @Volatile
        private var pendingSync: SyncRequest? = null

        @Volatile
        private var requestedSyncRevision: Long = 0L

        @Volatile
        private var appliedSyncRevision: Long = 0L

        @Volatile
        private var appliedMediaId: String = ""

        @Volatile
        private var queueSnapshot: List<Song> = emptyList()

        @Volatile
        private var repeatOneSnapshot: Boolean = false

        @Volatile
        private var repeatAllSnapshot: Boolean = false

        @Volatile
        private var shuffleSnapshot: Boolean = false

        @Volatile
        private var freeDailyLimitSecondsSnapshot: Int = 3600

        @Volatile
        private var activeUserNameSnapshot: String = ""

        @Volatile
        private var autonomousFreeSongsSinceAd: Int = 0

        @Volatile
        private var autonomousPendingNextSong: Song? = null

        private const val PROMO_AUDIO_AD_ID = com.example.providers.MusicViewModel.PROMO_AUDIO_AD_SONG_ID

        private val autonomousAudioExtractor by lazy { AudioStreamExtractor() }

        @Volatile
        var onSkipNextCallback: (() -> Unit)? = null

        @Volatile
        var onSkipPreviousCallback: (() -> Unit)? = null

        @Volatile
        var onTogglePlayPauseCallback: ((Boolean) -> Unit)? = null

        @Volatile
        var onSeekToCallback: ((Long) -> Unit)? = null

        @Volatile
        var onExoPlayerTrackEndedCallback: (() -> Unit)? = null

        @Volatile
        var onExoPlayerStreamErrorCallback: (() -> Unit)? = null

        @Volatile
        var onKeepOnlineEngineAwakeCallback: (() -> Unit)? = null

        @Volatile
        var onExternalMediaPauseCallback: (() -> Unit)? = null

        @Volatile
        var onExternalMediaResumeCallback: (() -> Unit)? = null

        @Volatile
        private var isKeepAliveScheduled: Boolean = false

        @Volatile
        var isPausedByExternalMedia: Boolean = false
            private set

        private val serviceKeepAliveRunnable = object : Runnable {
            override fun run() {
                mainHandler.removeCallbacks(this)
                val currentReq = pendingSync
                if (currentReq != null && currentReq.isPlaying && !isPausedByExternalMedia) {
                    isKeepAliveScheduled = true
                    instance?.ensureServiceLocksAndCheckBufferingStall()
                    if (instance?.isDirectAudioMode != true) {
                        onKeepOnlineEngineAwakeCallback?.invoke()
                    }
                    mainHandler.postDelayed(this, 1500L)
                } else {
                    isKeepAliveScheduled = false
                }
            }
        }

        fun getCurrentSyncedSong(): Song? = pendingSync?.song

        fun isCurrentlyPlaying(): Boolean = pendingSync?.isPlaying == true

        fun isDirectAudioModeActive(): Boolean = instance?.isDirectAudioMode == true

        @androidx.annotation.VisibleForTesting
        internal fun resetForTests() {
            mainHandler.removeCallbacksAndMessages(null)
            isKeepAliveScheduled = false
            isPausedByExternalMedia = false
            isForegroundServiceStarted = false
            pendingSync = null
            appliedMediaId = ""
            queueSnapshot = emptyList()
            autonomousFreeSongsSinceAd = 0
            autonomousPendingNextSong = null
            onSkipNextCallback = null
            onSkipPreviousCallback = null
            onTogglePlayPauseCallback = null
            onSeekToCallback = null
            onExoPlayerTrackEndedCallback = null
            onExoPlayerStreamErrorCallback = null
            onKeepOnlineEngineAwakeCallback = null
            onExternalMediaPauseCallback = null
            onExternalMediaResumeCallback = null
        }

        fun updateQueueStateSnapshot(
            queue: List<Song>,
            isRepeatOne: Boolean,
            isRepeatAll: Boolean,
            isShuffle: Boolean,
            freeDailyLimitSeconds: Int,
            userName: String
        ) {
            queueSnapshot = queue
            repeatOneSnapshot = isRepeatOne
            repeatAllSnapshot = isRepeatAll
            shuffleSnapshot = isShuffle
            freeDailyLimitSecondsSnapshot = freeDailyLimitSeconds.coerceAtLeast(60)
            activeUserNameSnapshot = userName
        }

        private fun getCachedArtworkBytesForSong(song: Song?): ByteArray? {
            if (song == null) return null
            val bannerUrl = song.highResBannerUrl
            if (bannerUrl.isNotBlank()) {
                artworkBytesCache.get(bannerUrl)?.let { return it }
            }
            val cleanVid = song.cleanVideoId
            if (cleanVid.length == 11) {
                artworkBytesCache.get("yt_$cleanVid")?.let { return it }
                artworkBytesCache.get("https://img.youtube.com/vi/$cleanVid/hqdefault.jpg")?.let { return it }
            }
            return null
        }

        fun prefetchArtwork(song: Song) {
            val bannerUrl = song.highResBannerUrl
            val cleanVid = song.cleanVideoId
            if (bannerUrl.isBlank() && cleanVid.isBlank()) return
            if (getCachedArtworkBytesForSong(song) != null) return
            ioScope.launch {
                val maxResYt = if (cleanVid.length == 11) "https://img.youtube.com/vi/$cleanVid/maxresdefault.jpg" else ""
                val sdResYt = if (cleanVid.length == 11) "https://img.youtube.com/vi/$cleanVid/sddefault.jpg" else ""
                val hqYt = if (cleanVid.length == 11) "https://img.youtube.com/vi/$cleanVid/hqdefault.jpg" else ""
                val bytes = (if (maxResYt.isNotBlank()) downloadCompressedArtworkBytes(maxResYt) else null)
                    ?: (if (sdResYt.isNotBlank()) downloadCompressedArtworkBytes(sdResYt) else null)
                    ?: (if (bannerUrl.startsWith("http", ignoreCase = true)) downloadCompressedArtworkBytes(bannerUrl) else null)
                    ?: (if (hqYt.isNotBlank() && hqYt != bannerUrl) downloadCompressedArtworkBytes(hqYt) else null)
                    ?: return@launch
                if (bannerUrl.isNotBlank()) artworkBytesCache.put(bannerUrl, bytes)
                if (cleanVid.length == 11) artworkBytesCache.put("yt_$cleanVid", bytes)
                if (hqYt.isNotBlank()) artworkBytesCache.put(hqYt, bytes)
            }
        }

        private fun downloadCompressedArtworkBytes(url: String): ByteArray? {
            return try {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", AudioStreamExtractor.EXOPLAYER_USER_AGENT)
                    .get()
                    .build()
                artworkHttpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return null
                    val rawBytes = resp.body?.bytes() ?: return null
                    if (rawBytes.isEmpty()) return null
                    val rawBmp = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size) ?: return null
                    // Ignore YouTube's tiny 120x90 placeholder thumbnail returned when maxres/sddefault does not exist
                    if ((url.contains("maxresdefault.jpg") || url.contains("sddefault.jpg")) && rawBmp.width < 400) return null
                    // Strip black top/bottom letterbox bars from 4:3 YouTube thumbnails (sddefault 640x480 / hqdefault 480x360)
                    val cropped = when {
                        url.contains("sddefault.jpg") && rawBmp.width == 640 && rawBmp.height == 480 ->
                            Bitmap.createBitmap(rawBmp, 0, 60, 640, 360)
                        url.contains("hqdefault.jpg") && rawBmp.width == 480 && rawBmp.height == 360 ->
                            Bitmap.createBitmap(rawBmp, 60, 45, 360, 270)
                        else -> rawBmp
                    }
                    // Center-crop to crisp 1:1 square >= 512px so Lock Screen & Quick Settings tiles never blur or stretch
                    val side = minOf(cropped.width, cropped.height)
                    val square = if (cropped.width != cropped.height && side > 0) {
                        val xOff = (cropped.width - side) / 2
                        val yOff = (cropped.height - side) / 2
                        Bitmap.createBitmap(cropped, xOff, yOff, side, side)
                    } else {
                        cropped
                    }
                    val targetDim = 512
                    val scaled = if (square.width != targetDim || square.height != targetDim) {
                        Bitmap.createScaledBitmap(square, targetDim, targetDim, true)
                    } else {
                        square
                    }
                    val out = ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.JPEG, 95, out)
                    out.toByteArray()
                }
            } catch (_: Exception) {
                null
            }
        }

        /**
         * Starts a persistent background notification watcher so that once the user opens the app
         * and allows notifications, Admin notifications from Firebase (/notifications, /messages, /broadcast)
         * and FCM arrive even when the user closes the app.
         */
        fun ensureBackgroundNotificationWatcher(
            context: Context,
            startServiceIfNeeded: Boolean = true
        ) {
            if (isBackgroundNotifWatcherStarted) return
            isBackgroundNotifWatcherStarted = true
            val appCtx = context.applicationContext
            AADIFYMessagingService.initializeAndSubscribe(appCtx)
            if (startServiceIfNeeded && instance == null) {
                try {
                    val intent = Intent(appCtx, MusicPlaybackService::class.java).apply {
                        action = ACTION_WATCH_NOTIFICATIONS
                    }
                    appCtx.startService(intent)
                } catch (_: Exception) {
                }
            }
            ioScope.launch {
                try {
                    getOrCreateSilentWavUri(appCtx)
                } catch (_: Exception) {
                }
                // Notifications are delivered in real time via FCM (AADIFYMessagingService) + RTDB limitToLast(10) listener.
                // Removed the aggressive 15-second 4-endpoint REST polling loop to prevent RTDB bandwidth spikes!
            }
        }

        fun notifyUnexpectedOnlinePause() {
            mainHandler.post {
                val svc = instance
                if (svc != null) {
                    svc.handleUnexpectedOnlinePauseInternal()
                } else {
                    onKeepOnlineEngineAwakeCallback?.invoke()
                }
            }
        }

        fun clearExternalMediaPauseState() {
            isPausedByExternalMedia = false
            mainHandler.post {
                instance?.cancelExternalMediaResumeWatch()
            }
        }

        /**
         * Updates the Foreground MediaSessionService directly in-process so there is zero IPC
         * URI stripping and all Notification Bar, Lock Screen, and Quick Settings Media Tile
         * buttons (Previous, Play/Pause, Next, Stop) work instantaneously on every Android version.
         */
        fun syncNowPlaying(
            context: Context,
            song: Song,
            isPlaying: Boolean,
            directStreamUri: String? = null,
            playbackSpeed: Float = 1.0f,
            startPositionMs: Long = 0L
        ) {
            if (isPlaying) {
                isPausedByExternalMedia = false
            }
            val rev = ++requestedSyncRevision
            val req = SyncRequest(song, isPlaying, directStreamUri, playbackSpeed, startPositionMs.coerceAtLeast(0L))
            pendingSync = req
            mainHandler.post {
                mainHandler.removeCallbacks(serviceKeepAliveRunnable)
                if (isPlaying) {
                    isKeepAliveScheduled = true
                    mainHandler.postDelayed(serviceKeepAliveRunnable, 1200L)
                } else {
                    isKeepAliveScheduled = false
                }
                if (!isForegroundServiceStarted) {
                    BackgroundGamingAudioKeeper.ensureForegroundMediaServiceStarted(context)
                }
                val svc = instance
                if (svc != null) {
                    svc.applySyncRequest(req, rev)
                } else {
                    BackgroundGamingAudioKeeper.ensureForegroundMediaServiceStarted(context)
                }
            }
        }

        fun syncPlayState(isPlaying: Boolean) {
            pendingSync = pendingSync?.copy(isPlaying = isPlaying)
            mainHandler.post {
                if (isPlaying) {
                    isPausedByExternalMedia = false
                    instance?.cancelExternalMediaResumeWatch()
                    if (!isKeepAliveScheduled && pendingSync != null) {
                        isKeepAliveScheduled = true
                        mainHandler.removeCallbacks(serviceKeepAliveRunnable)
                        mainHandler.postDelayed(serviceKeepAliveRunnable, 1200L)
                    }
                } else {
                    isKeepAliveScheduled = false
                    mainHandler.removeCallbacks(serviceKeepAliveRunnable)
                }
                instance?.applyPlayStateInternal(isPlaying, forceNotificationUpdate = true)
            }
        }

        fun syncSeekPosition(positionMs: Long) {
            mainHandler.post {
                instance?.applySeekInternal(positionMs)
            }
        }

        fun syncOnlineProgressAndDuration(positionMs: Long, durationMs: Long) {
            mainHandler.post {
                instance?.updateOnlineProgressAndDurationInternal(positionMs, durationMs)
            }
        }

        fun syncPlaybackSpeed(speed: Float) {
            pendingSync = pendingSync?.copy(playbackSpeed = speed)
            mainHandler.post {
                instance?.applySpeedInternal(speed)
            }
        }

        private fun isExoStateFreshForCurrentSong(): Boolean {
            val svc = instance ?: return false
            if (!svc.isDirectAudioMode) return false
            val targetSongId = pendingSync?.song?.id ?: return false
            return appliedSyncRevision == requestedSyncRevision && appliedMediaId == targetSongId
        }

        fun getCurrentExoPositionMs(): Long {
            if (!isExoStateFreshForCurrentSong()) return 0L
            return instance?.exoPlayerInstance?.currentPosition?.coerceAtLeast(0L) ?: 0L
        }

        fun getCurrentExoDurationMs(): Long {
            if (!isExoStateFreshForCurrentSong()) return 0L
            return instance?.exoPlayerInstance?.duration?.coerceAtLeast(0L) ?: 0L
        }

        fun isExoPlaying(): Boolean {
            if (!isExoStateFreshForCurrentSong()) return false
            return instance?.exoPlayerInstance?.isPlaying == true
        }

        fun isExoBuffering(): Boolean {
            if (!isExoStateFreshForCurrentSong()) return true
            return instance?.exoPlayerInstance?.playbackState == Player.STATE_BUFFERING
        }

        /**
         * Generates a 600-second (10-minute) standard 16-bit signed PCM silent WAV file used by MusicPlaybackService
         * to keep the Foreground MediaSession, WakeLock, WifiLock, Quick Settings Media Tile, Lock Screen,
         * and Notification Bar media controls active while BackstageYouTubePlayerController streams online audio.
         */
        @Volatile
        private var cachedSilentWavUri: String? = null

        fun getOrCreateSilentWavUri(context: Context): String {
            cachedSilentWavUri?.let { return it }
            val file = File(context.cacheDir, "aadify_keepalive_16bit_600s_v2.wav")
            if (file.exists() && file.length() > 4_000_000L) {
                return Uri.fromFile(file).toString().also { cachedSilentWavUri = it }
            }
            try {
                val sampleRate = 4000
                val durationSeconds = 600
                val numSamples = sampleRate * durationSeconds
                val bytesPerSample = 2 // 16-bit signed PCM (0x0000 = pure silence)
                val dataSize = numSamples * bytesPerSample
                val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)

                // RIFF header
                header.put("RIFF".toByteArray(Charsets.US_ASCII))
                header.putInt(36 + dataSize)
                header.put("WAVE".toByteArray(Charsets.US_ASCII))
                // fmt subchunk
                header.put("fmt ".toByteArray(Charsets.US_ASCII))
                header.putInt(16) // PCM
                header.putShort(1.toShort()) // AudioFormat = 1 (PCM)
                header.putShort(1.toShort()) // NumChannels = 1 (Mono)
                header.putInt(sampleRate)
                header.putInt(sampleRate * bytesPerSample) // ByteRate
                header.putShort(bytesPerSample.toShort()) // BlockAlign
                header.putShort(16.toShort()) // BitsPerSample = 16
                // data subchunk (all zero bytes = 16-bit signed silence)
                header.put("data".toByteArray(Charsets.US_ASCII))
                header.putInt(dataSize)

                FileOutputStream(file).use { out ->
                    out.write(header.array())
                    val chunk = ByteArray(64 * 1024)
                    var remaining = dataSize
                    while (remaining > 0) {
                        val toWrite = minOf(remaining, chunk.size)
                        out.write(chunk, 0, toWrite)
                        remaining -= toWrite
                    }
                }
            } catch (_: Exception) {
            }
            return Uri.fromFile(file).toString().also { cachedSilentWavUri = it }
        }
    }

    private var mediaSession: MediaSession? = null
    private var exoPlayerInstance: ExoPlayer? = null
    private var defaultLogoBitmap: Bitmap? = null
    private var currentActiveMetadata: MediaMetadata = MediaMetadata.EMPTY
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var audioPlaybackCallback: AudioManager.AudioPlaybackCallback? = null
    private var isInternalSync: Boolean = false
    private var isDirectAudioMode: Boolean = false
    private var syncedOnlinePositionMs: Long = 0L
    private var syncedOnlineDurationMs: Long = 0L
    private var zeroExternalMediaConsecutiveChecks: Int = 0
    private var lastServiceWakeLockRefreshElapsedMs: Long = 0L
    private var lastDirectExoPosMs: Long = -1L
    private var directExoStallSinceElapsedMs: Long = 0L
    private var lastAutonomousFreeTickElapsedMs: Long = 0L

    private val externalMediaResumeWatcherRunnable = object : Runnable {
        override fun run() {
            mainHandler.removeCallbacks(this)
            if (!isPausedByExternalMedia) return
            if (!isPhoneCallActive()) {
                zeroExternalMediaConsecutiveChecks++
                if (zeroExternalMediaConsecutiveChecks >= 2) {
                    triggerAutoResumeFromExternalMedia()
                    return
                }
            } else {
                zeroExternalMediaConsecutiveChecks = 0
            }
            mainHandler.postDelayed(this, 400L)
        }
    }

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // Only pause if an actual cellular phone call is active; NEVER pause when a game (Free Fire, PUBG) requests focus!
                if (isPhoneCallActive() && pendingSync?.isPlaying == true && !isPausedByExternalMedia) {
                    enterExternalMediaPausedState()
                } else if (pendingSync?.isPlaying == true && !isPausedByExternalMedia) {
                    onKeepOnlineEngineAwakeCallback?.invoke()
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (isPausedByExternalMedia && !isPhoneCallActive()) {
                    mainHandler.postDelayed({
                        if (isPausedByExternalMedia && !isPhoneCallActive()) {
                            triggerAutoResumeFromExternalMedia()
                        }
                    }, 250L)
                }
            }
        }
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        instance = this
        audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        try {
            val keeper = BackgroundGamingAudioKeeper.getInstance(this)
            keeper.setKeepAliveAction {
                if (pendingSync?.isPlaying == true && !isPausedByExternalMedia) {
                    onKeepOnlineEngineAwakeCallback?.invoke()
                }
            }
            keeper.startGamingKeepAlive()
        } catch (_: Exception) {
        }

        ensurePublicLockscreenNotificationChannel()
        registerSystemAudioPlaybackMonitor()

        try {
            val notifProvider = DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(NOTIFICATION_CHANNEL_ID)
                .setNotificationId(NOTIFICATION_ID)
                .build()
            setMediaNotificationProvider(notifProvider)
        } catch (_: Exception) {
        }

        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = pm?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "AADIFY:BackgroundAudioWakeLock"
            )?.apply {
                setReferenceCounted(false)
            }

            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifiLock = wm?.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "AADIFY:BackgroundAudioWifiLock"
            )?.apply {
                setReferenceCounted(false)
            }
        } catch (_: Exception) {
        }

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(AudioStreamExtractor.EXOPLAYER_USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(12_000)
            .setReadTimeoutMs(12_000)

        val resolvingHttpFactory = ResolvingDataSource.Factory(httpDataSourceFactory) { dataSpec ->
            val streamUrl = dataSpec.uri.toString()
            val resolvedHeaders = AudioStreamExtractor.resolveHeadersForStreamUrl(streamUrl)
            dataSpec.buildUpon()
                .setHttpRequestHeaders(resolvedHeaders)
                .build()
        }

        val dataSourceFactory = DefaultDataSource.Factory(this, resolvingHttpFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                15_000, // minBufferMs
                50_000, // maxBufferMs
                450,    // bufferForPlaybackMs: start song audio in <0.5s instead of 2.5s!
                1_200   // bufferForPlaybackAfterRebufferMs
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        // Pass handleAudioFocus = false on ExoPlayer so ExoPlayer never steals audio focus from BackstageAudioWebView!
        val exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, false)
            .setHandleAudioBecomingNoisy(false)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        exoPlayerInstance = exoPlayer

        exoPlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                try {
                    if (isPlaying || pendingSync?.isPlaying == true) {
                        ensureServiceLocksHeld()
                    }
                } catch (_: Exception) {
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (isInternalSync) return
                val itemId = mediaItem?.mediaId.orEmpty()
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) {
                    if (itemId.endsWith("_next")) {
                        dispatchSkipNext()
                        return
                    } else if (itemId.endsWith("_prev")) {
                        dispatchSkipPrevious()
                        return
                    }
                }
                if (isDirectAudioMode && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    dispatchDirectTrackEnded()
                } else if (!isDirectAudioMode && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && pendingSync?.isPlaying == true) {
                    isInternalSync = true
                    try {
                        exoPlayer.seekTo(1, 0L)
                        exoPlayer.play()
                    } catch (_: Exception) {
                    } finally {
                        isInternalSync = false
                    }
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (isInternalSync) return
                if (isDirectAudioMode && playbackState == Player.STATE_ENDED) {
                    dispatchDirectTrackEnded()
                } else if (!isDirectAudioMode && playbackState == Player.STATE_ENDED && pendingSync?.isPlaying == true) {
                    isInternalSync = true
                    try {
                        exoPlayer.seekTo(1, 0L)
                        exoPlayer.play()
                    } catch (_: Exception) {
                    } finally {
                        isInternalSync = false
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (isDirectAudioMode) {
                    isDirectAudioMode = false
                    val cb = onExoPlayerStreamErrorCallback
                    if (cb != null) {
                        cb.invoke()
                    } else {
                        recoverAutonomousDirectStream()
                    }
                }
            }
        })

        // Wrap ExoPlayer in a ForwardingPlayer that exposes clean, evenly spaced
        // Previous / Play-Pause / Next controls in Quick Settings Media Tile, Notification Bar & Lock Screen!
        val forwardingPlayer = object : ForwardingPlayer(exoPlayer) {
            private fun buildAllCommands(base: Player.Commands): Player.Commands {
                return base.buildUpon()
                    .remove(Player.COMMAND_STOP)
                    .add(Player.COMMAND_PLAY_PAUSE)
                    .add(Player.COMMAND_SEEK_TO_NEXT)
                    .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                    .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
                    .add(Player.COMMAND_GET_TIMELINE)
                    .add(Player.COMMAND_GET_METADATA)
                    .build()
            }

            override fun getAvailableCommands(): Player.Commands {
                return buildAllCommands(super.getAvailableCommands())
            }

            override fun isCommandAvailable(command: Int): Boolean {
                return when (command) {
                    Player.COMMAND_STOP -> false
                    Player.COMMAND_SEEK_TO_NEXT,
                    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_PREVIOUS,
                    Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                    Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_PLAY_PAUSE,
                    Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_GET_TIMELINE,
                    Player.COMMAND_GET_METADATA -> true
                    else -> super.isCommandAvailable(command)
                }
            }

            override fun hasNextMediaItem(): Boolean = true

            override fun hasPreviousMediaItem(): Boolean = true

            override fun getMaxSeekToPreviousPosition(): Long = Long.MAX_VALUE

            override fun getMediaMetadata(): MediaMetadata {
                if (currentActiveMetadata != MediaMetadata.EMPTY) {
                    return currentActiveMetadata
                }
                return super.getMediaMetadata()
            }

            override fun getCurrentTimeline(): Timeline {
                val baseTimeline = super.getCurrentTimeline()
                if (!isDirectAudioMode && !baseTimeline.isEmpty) {
                    val onlineDurUs = if (syncedOnlineDurationMs > 0L) syncedOnlineDurationMs * 1000L else C.TIME_UNSET
                    return object : ForwardingTimeline(baseTimeline) {
                        override fun getWindow(
                            windowIndex: Int,
                            window: Window,
                            defaultPositionProjectionUs: Long
                        ): Window {
                            super.getWindow(windowIndex, window, defaultPositionProjectionUs)
                            if (windowIndex == 1) {
                                window.durationUs = onlineDurUs
                                window.isSeekable = syncedOnlineDurationMs > 0L
                                window.isDynamic = false
                            }
                            return window
                        }

                        override fun getPeriod(
                            periodIndex: Int,
                            period: Period,
                            setIds: Boolean
                        ): Period {
                            super.getPeriod(periodIndex, period, setIds)
                            if (periodIndex == 1) {
                                period.durationUs = onlineDurUs
                            }
                            return period
                        }
                    }
                }
                return baseTimeline
            }

            override fun getDuration(): Long {
                if (!isDirectAudioMode) {
                    return if (syncedOnlineDurationMs > 0L) syncedOnlineDurationMs else C.TIME_UNSET
                }
                return super.getDuration()
            }

            override fun getContentDuration(): Long {
                if (!isDirectAudioMode) {
                    return if (syncedOnlineDurationMs > 0L) syncedOnlineDurationMs else C.TIME_UNSET
                }
                return super.getContentDuration()
            }

            override fun getCurrentPosition(): Long {
                if (!isDirectAudioMode) {
                    return if (syncedOnlineDurationMs > 0L) {
                        syncedOnlinePositionMs.coerceIn(0L, syncedOnlineDurationMs)
                    } else {
                        syncedOnlinePositionMs.coerceAtLeast(0L)
                    }
                }
                return super.getCurrentPosition()
            }

            override fun getContentPosition(): Long {
                if (!isDirectAudioMode) {
                    return if (syncedOnlineDurationMs > 0L) {
                        syncedOnlinePositionMs.coerceIn(0L, syncedOnlineDurationMs)
                    } else {
                        syncedOnlinePositionMs.coerceAtLeast(0L)
                    }
                }
                return super.getContentPosition()
            }

            override fun seekToNext() {
                if (!isInternalSync) {
                    dispatchSkipNext()
                }
            }

            override fun seekToNextMediaItem() {
                if (!isInternalSync) {
                    dispatchSkipNext()
                }
            }

            override fun seekToPrevious() {
                if (!isInternalSync) {
                    dispatchSkipPrevious()
                }
            }

            override fun seekToPreviousMediaItem() {
                if (!isInternalSync) {
                    dispatchSkipPrevious()
                }
            }

            override fun seekToDefaultPosition(mediaItemIndex: Int) {
                if (!isInternalSync) {
                    if (mediaItemIndex > 1) {
                        dispatchSkipNext()
                        return
                    } else if (mediaItemIndex < 1) {
                        dispatchSkipPrevious()
                        return
                    }
                }
                super.seekToDefaultPosition(mediaItemIndex)
            }

            override fun seekTo(positionMs: Long) {
                if (!isInternalSync && pendingSync?.song?.id == PROMO_AUDIO_AD_ID && !isPersistedVipActiveNow()) {
                    return
                }
                if (!isInternalSync && !isDirectAudioMode) {
                    val clamped = positionMs.coerceAtLeast(0L)
                    syncedOnlinePositionMs = clamped
                    onSeekToCallback?.invoke(clamped)
                    return
                }
                super.seekTo(positionMs)
            }

            override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
                if (!isInternalSync) {
                    if (pendingSync?.song?.id == PROMO_AUDIO_AD_ID && !isPersistedVipActiveNow()) {
                        return
                    }
                    if (mediaItemIndex > 1) {
                        dispatchSkipNext()
                        return
                    } else if (mediaItemIndex < 1) {
                        dispatchSkipPrevious()
                        return
                    }
                    if (!isDirectAudioMode) {
                        val clamped = positionMs.coerceAtLeast(0L)
                        syncedOnlinePositionMs = clamped
                        onSeekToCallback?.invoke(clamped)
                        return
                    }
                }
                super.seekTo(mediaItemIndex, positionMs)
            }

            override fun getPlayWhenReady(): Boolean {
                if (pendingSync != null && !isPausedByExternalMedia) {
                    if (!isDirectAudioMode || pendingSync?.isPlaying == true) {
                        return pendingSync?.isPlaying == true
                    }
                }
                return super.getPlayWhenReady()
            }

            override fun isPlaying(): Boolean {
                if (pendingSync != null && !isPausedByExternalMedia) {
                    if (!isDirectAudioMode) {
                        return pendingSync?.isPlaying == true
                    }
                    if (pendingSync?.isPlaying == true && super.getPlaybackState() == Player.STATE_ENDED) {
                        return true
                    }
                }
                return super.isPlaying()
            }

            override fun getPlaybackState(): Int {
                if (!isDirectAudioMode && pendingSync != null) {
                    return Player.STATE_READY
                }
                val baseState = super.getPlaybackState()
                if (isDirectAudioMode && pendingSync?.isPlaying == true &&
                    (baseState == Player.STATE_ENDED || baseState == Player.STATE_IDLE)
                ) {
                    return Player.STATE_BUFFERING
                }
                return baseState
            }

            override fun play() {
                if (isDirectAudioMode) {
                    super.play()
                }
                if (!isInternalSync) {
                    isPausedByExternalMedia = false
                    cancelExternalMediaResumeWatch()
                    pendingSync = pendingSync?.copy(isPlaying = true)
                    applyPlayStateInternal(true, forceNotificationUpdate = true)
                    onTogglePlayPauseCallback?.invoke(true)
                }
            }

            override fun pause() {
                if (isDirectAudioMode) {
                    super.pause()
                }
                if (!isInternalSync) {
                    isPausedByExternalMedia = false
                    cancelExternalMediaResumeWatch()
                    pendingSync = pendingSync?.copy(isPlaying = false)
                    applyPlayStateInternal(false, forceNotificationUpdate = true)
                    onTogglePlayPauseCallback?.invoke(false)
                }
            }

            override fun setPlayWhenReady(playWhenReady: Boolean) {
                if (isDirectAudioMode) {
                    super.setPlayWhenReady(playWhenReady)
                }
                if (!isInternalSync) {
                    if (playWhenReady) {
                        isPausedByExternalMedia = false
                        cancelExternalMediaResumeWatch()
                    }
                    pendingSync = pendingSync?.copy(isPlaying = playWhenReady)
                    applyPlayStateInternal(playWhenReady, forceNotificationUpdate = true)
                    onTogglePlayPauseCallback?.invoke(playWhenReady)
                }
            }

            override fun stop() {
                if (isDirectAudioMode) {
                    super.pause()
                }
                if (!isInternalSync) {
                    isPausedByExternalMedia = false
                    cancelExternalMediaResumeWatch()
                    pendingSync = pendingSync?.copy(isPlaying = false)
                    applyPlayStateInternal(false, forceNotificationUpdate = true)
                    onTogglePlayPauseCallback?.invoke(false)
                }
            }
        }

        val activityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            activityIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val callback = object : MediaSession.Callback {
            override fun onConnect(
                session: MediaSession,
                controller: MediaSession.ControllerInfo
            ): MediaSession.ConnectionResult {
                val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                    .remove(Player.COMMAND_STOP)
                    .add(Player.COMMAND_PLAY_PAUSE)
                    .add(Player.COMMAND_SEEK_TO_NEXT)
                    .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                    .build()
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailablePlayerCommands(playerCommands)
                    .build()
            }

            override fun onAddMediaItems(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo,
                mediaItems: MutableList<MediaItem>
            ): ListenableFuture<MutableList<MediaItem>> {
                val fallbackSilentUri = Uri.parse(getOrCreateSilentWavUri(this@MusicPlaybackService))
                val updated = mediaItems.map { item ->
                    val resolvedUri = item.localConfiguration?.uri
                        ?: item.requestMetadata.mediaUri
                        ?: fallbackSilentUri
                    item.buildUpon().setUri(resolvedUri).build()
                }.toMutableList()
                return Futures.immediateFuture(updated)
            }
        }

        mediaSession = MediaSession.Builder(this, forwardingPlayer)
            .setId("aadify_session_${sessionIdCounter.incrementAndGet()}")
            .setSessionActivity(pendingIntent)
            .setCallback(callback)
            .build()

        pendingSync?.let { req ->
            applySyncRequest(req, requestedSyncRevision)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        ensureBackgroundNotificationWatcher(applicationContext, startServiceIfNeeded = false)
        val action = intent?.action
        val activeSong = pendingSync?.song
        val activePlaying = pendingSync?.isPlaying ?: false
        val cachedBytes = getCachedArtworkBytesForSong(activeSong)
        when (action) {
            ACTION_WATCH_NOTIFICATIONS -> {
                if (activeSong != null) {
                    postOrUpdateForegroundMediaNotification(activeSong, activePlaying, cachedBytes)
                }
                return START_STICKY
            }
            ACTION_NOTIFICATION_PREV -> {
                postOrUpdateForegroundMediaNotification(activeSong, activePlaying, cachedBytes)
                dispatchSkipPrevious()
                return START_STICKY
            }
            ACTION_NOTIFICATION_PLAY_PAUSE -> {
                val nextPlay = !(pendingSync?.isPlaying ?: false)
                if (nextPlay && onTogglePlayPauseCallback == null && !canFreeOrVipUserContinuePlayback()) {
                    postOrUpdateForegroundMediaNotification(activeSong, activePlaying, cachedBytes)
                    return START_STICKY
                }
                isPausedByExternalMedia = false
                cancelExternalMediaResumeWatch()
                pendingSync = pendingSync?.copy(isPlaying = nextPlay)
                applyPlayStateInternal(nextPlay, forceNotificationUpdate = true)
                onTogglePlayPauseCallback?.invoke(nextPlay)
                return START_STICKY
            }
            ACTION_NOTIFICATION_NEXT -> {
                postOrUpdateForegroundMediaNotification(activeSong, activePlaying, cachedBytes)
                dispatchSkipNext()
                return START_STICKY
            }
            ACTION_NOTIFICATION_STOP -> {
                isPausedByExternalMedia = false
                cancelExternalMediaResumeWatch()
                pendingSync = pendingSync?.copy(isPlaying = false)
                applyPlayStateInternal(false, forceNotificationUpdate = true)
                onTogglePlayPauseCallback?.invoke(false)
                return START_STICKY
            }
        }
        if (activeSong != null) {
            postOrUpdateForegroundMediaNotification(activeSong, pendingSync?.isPlaying ?: true, cachedBytes)
        }
        return START_STICKY
    }

    @OptIn(UnstableApi::class)
    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        val activeSong = pendingSync?.song ?: return
        val isPlayingNow = pendingSync?.isPlaying == true
        val cachedBytes = getCachedArtworkBytesForSong(activeSong)
        postOrUpdateForegroundMediaNotification(activeSong, isPlayingNow, cachedBytes)
    }

    private fun buildServiceActionPendingIntent(requestCode: Int, action: String): PendingIntent {
        val intent = Intent(this, MusicPlaybackService::class.java).setAction(action)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(this, requestCode, intent, flags)
        } else {
            PendingIntent.getService(this, requestCode, intent, flags)
        }
    }

    @OptIn(UnstableApi::class)
    private fun postOrUpdateForegroundMediaNotification(
        song: Song?,
        isPlaying: Boolean,
        artworkBytes: ByteArray? = null
    ) {
        try {
            ensurePublicLockscreenNotificationChannel()
            val activityIntent = Intent(this, MainActivity::class.java).apply {
                this.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val contentPendingIntent = PendingIntent.getActivity(
                this,
                0,
                activityIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val prevIntent = buildServiceActionPendingIntent(101, ACTION_NOTIFICATION_PREV)
            val playPauseIntent = buildServiceActionPendingIntent(102, ACTION_NOTIFICATION_PLAY_PAUSE)
            val nextIntent = buildServiceActionPendingIntent(103, ACTION_NOTIFICATION_NEXT)

            val artworkBitmap = try {
                val bytes = artworkBytes ?: getCachedArtworkBytesForSong(song)
                if (bytes != null && bytes.isNotEmpty()) {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                } else {
                    defaultLogoBitmap ?: BitmapFactory.decodeResource(resources, R.drawable.aadify_logo)?.also {
                        defaultLogoBitmap = it
                    }
                }
            } catch (_: Exception) {
                null
            }

            // Strictly 3 clean, evenly balanced transport buttons: Previous, Play/Pause, Next (no stray Stop 'X' button)
            val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_aadify_notification)
                .setContentTitle(song?.title?.ifBlank { "AADIFY" } ?: "AADIFY")
                .setContentText(song?.artist?.ifBlank { "Playing" } ?: "Playing")
                .setSubText("AADIFY Music")
                .setContentIntent(contentPendingIntent)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .addAction(
                    NotificationCompat.Action(
                        android.R.drawable.ic_media_previous,
                        "Previous",
                        prevIntent
                    )
                )
                .addAction(
                    NotificationCompat.Action(
                        if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                        if (isPlaying) "Pause" else "Play",
                        playPauseIntent
                    )
                )
                .addAction(
                    NotificationCompat.Action(
                        android.R.drawable.ic_media_next,
                        "Next",
                        nextIntent
                    )
                )

            if (artworkBitmap != null) {
                builder.setLargeIcon(artworkBitmap)
            }

            mediaSession?.let { session ->
                try {
                    builder.setStyle(
                        MediaStyleNotificationHelper.MediaStyle(session)
                            .setShowActionsInCompactView(0, 1, 2)
                    )
                } catch (_: Exception) {
                }
            }

            val notification = builder.build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            isForegroundServiceStarted = true
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e("MusicPlaybackService", "Failed to startForeground in postOrUpdateForegroundMediaNotification", e)
        }
    }

    private fun registerSystemAudioPlaybackMonitor() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val cb = object : AudioManager.AudioPlaybackCallback() {
                    override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
                        super.onPlaybackConfigChanged(configs)
                        if (isPausedByExternalMedia && !isPhoneCallActive()) {
                            mainHandler.postDelayed({
                                if (isPausedByExternalMedia && !isPhoneCallActive()) {
                                    triggerAutoResumeFromExternalMedia()
                                }
                            }, 350L)
                        }
                    }
                }
                audioPlaybackCallback = cb
                audioManager?.registerAudioPlaybackCallback(cb, mainHandler)
            } catch (_: Exception) {
            }
        }
    }

    private fun isPhoneCallActive(): Boolean {
        return BackgroundGamingAudioKeeper.isActualCellularCallActive(audioManager)
    }

    /**
     * Invoked when BackstageYouTubePlayerController's <video> element pauses unexpectedly.
     * - If an actual phone/VoIP call is active, pauses cleanly until the call ends.
     * - Otherwise, NEVER pauses our own playback; immediately wakes and resumes the online engine!
     */
    private fun handleUnexpectedOnlinePauseInternal() {
        if (isPausedByExternalMedia || pendingSync?.isPlaying != true) return

        if (isPhoneCallActive()) {
            enterExternalMediaPausedState()
            return
        }

        // Immediately wake and resume the online engine so playback never stops on Song 2, Song 3, or in background
        onKeepOnlineEngineAwakeCallback?.invoke()
        val recoveryDelays = longArrayOf(120L, 320L, 700L, 1300L)
        for (d in recoveryDelays) {
            mainHandler.postDelayed({
                if (pendingSync?.isPlaying == true && !isPausedByExternalMedia) {
                    onKeepOnlineEngineAwakeCallback?.invoke()
                }
            }, d)
        }
    }

    private fun enterExternalMediaPausedState() {
        if (isPausedByExternalMedia) return
        isPausedByExternalMedia = true
        zeroExternalMediaConsecutiveChecks = 0
        pendingSync = pendingSync?.copy(isPlaying = false)

        mainHandler.removeCallbacks(serviceKeepAliveRunnable)
        isKeepAliveScheduled = false

        val player = exoPlayerInstance
        isInternalSync = true
        try {
            player?.pause()
            val activeSong = pendingSync?.song
            val cachedBytes = activeSong?.highResBannerUrl?.takeIf { it.isNotBlank() }?.let { artworkBytesCache.get(it) }
            postOrUpdateForegroundMediaNotification(activeSong, false, cachedBytes)
        } catch (_: Exception) {
        } finally {
            isInternalSync = false
        }

        // Notify ViewModel & BackstagePlayer to pause cleanly at current timestamp
        onExternalMediaPauseCallback?.invoke()

        // Request delayed audio focus so Android notifies us if focus returns, and start watcher
        requestAudioFocusForExternalWait()
        mainHandler.removeCallbacks(externalMediaResumeWatcherRunnable)
        mainHandler.postDelayed(externalMediaResumeWatcherRunnable, 400L)
    }

    private fun triggerAutoResumeFromExternalMedia() {
        if (!isPausedByExternalMedia) return
        isPausedByExternalMedia = false
        zeroExternalMediaConsecutiveChecks = 0
        mainHandler.removeCallbacks(externalMediaResumeWatcherRunnable)

        if (!isDirectAudioMode) {
            abandonAudioFocusIfHeld()
        }

        pendingSync = pendingSync?.copy(isPlaying = true)
        applyPlayStateInternal(shouldPlay = true, forceNotificationUpdate = true)

        if (!isKeepAliveScheduled) {
            isKeepAliveScheduled = true
            mainHandler.removeCallbacks(serviceKeepAliveRunnable)
            mainHandler.postDelayed(serviceKeepAliveRunnable, 500L)
        }

        onExternalMediaResumeCallback?.invoke()
    }

    private fun cancelExternalMediaResumeWatch() {
        zeroExternalMediaConsecutiveChecks = 0
        mainHandler.removeCallbacks(externalMediaResumeWatcherRunnable)
    }

    private fun requestAudioFocusForExternalWait() {
        val am = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = audioFocusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAcceptsDelayedFocusGain(true)
                    .setWillPauseWhenDucked(true)
                    .setOnAudioFocusChangeListener(audioFocusChangeListener, mainHandler)
                    .build().also { audioFocusRequest = it }
                am.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(
                    audioFocusChangeListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN
                )
            }
        } catch (_: Exception) {
        }
    }

    private fun abandonAudioFocusIfHeld() {
        val am = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(audioFocusChangeListener)
            }
        } catch (_: Exception) {
        }
    }

    private fun ensurePublicLockscreenNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
                val channel = NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "AADIFY Music Playback",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Shows Quick Settings, Lock Screen, and Notification Bar playback controls"
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                }
                nm.createNotificationChannel(channel)
            } catch (_: Exception) {
            }
        }
    }

    private fun buildMediaMetadataForSong(song: Song, artworkBytes: ByteArray?, durationMs: Long = 0L): MediaMetadata {
        val artworkUri = if (song.highResBannerUrl.isNotBlank()) {
            Uri.parse(song.highResBannerUrl)
        } else {
            null
        }
        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.artist)
            .setAlbumTitle("AADIFY Music")
            .setDisplayTitle(song.title)
            .setSubtitle(song.artist)
            .setIsBrowsable(false)
            .setIsPlayable(true)
        if (durationMs > 0L) {
            metadataBuilder.setDurationMs(durationMs)
        }
        if (artworkUri != null) {
            metadataBuilder.setArtworkUri(artworkUri)
        }
        if (artworkBytes != null && artworkBytes.isNotEmpty()) {
            metadataBuilder.setArtworkData(artworkBytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
        }
        return metadataBuilder.build()
    }

    private fun updateOnlineProgressAndDurationInternal(positionMs: Long, durationMs: Long) {
        if (isDirectAudioMode) return
        val player = exoPlayerInstance ?: return
        var shouldTriggerPositionDiscontinuity = false
        if (positionMs >= 0L) {
            val drift = kotlin.math.abs(player.currentPosition - positionMs)
            syncedOnlinePositionMs = positionMs
            if (drift > 2200L) {
                shouldTriggerPositionDiscontinuity = true
            }
        }
        if (durationMs > 0L && kotlin.math.abs(syncedOnlineDurationMs - durationMs) > 500L) {
            syncedOnlineDurationMs = durationMs
            val activeSong = pendingSync?.song
            if (activeSong != null) {
                val cachedArt = getCachedArtworkBytesForSong(activeSong)
                currentActiveMetadata = buildMediaMetadataForSong(activeSong, cachedArt, durationMs)
            }
            shouldTriggerPositionDiscontinuity = true
        }
        if (shouldTriggerPositionDiscontinuity && player.mediaItemCount >= 3) {
            isInternalSync = true
            try {
                val targetSeekMs = syncedOnlinePositionMs.coerceIn(0L, 590_000L)
                player.seekTo(1, targetSeekMs)
            } catch (_: Exception) {
            } finally {
                isInternalSync = false
            }
        }
    }

    private fun ensureServiceLocksHeld() {
        try {
            val nowElapsed = android.os.SystemClock.elapsedRealtime()
            if (wakeLock?.isHeld == false || (nowElapsed - lastServiceWakeLockRefreshElapsedMs) > 20L * 60L * 1000L) {
                wakeLock?.acquire(6 * 60 * 60 * 1000L)
                lastServiceWakeLockRefreshElapsedMs = nowElapsed
            }
            if (wifiLock?.isHeld == false) {
                wifiLock?.acquire()
            }
            BackgroundGamingAudioKeeper.getInstance(this).startGamingKeepAlive()
        } catch (_: Exception) {
        }
    }

    private fun releaseServiceLocksWhenPaused() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Exception) {
        }
        try {
            BackgroundGamingAudioKeeper.getInstance(this).stopGamingKeepAlive()
        } catch (_: Exception) {
        }
    }

    private fun ensureServiceLocksAndCheckBufferingStall() {
        ensureServiceLocksHeld()
        if (onExoPlayerTrackEndedCallback == null && !canFreeOrVipUserContinuePlayback()) {
            // UI ViewModel was destroyed while in background and Free user hit daily limit -> pause cleanly
            pendingSync = pendingSync?.copy(isPlaying = false)
            applyPlayStateInternal(shouldPlay = false, forceNotificationUpdate = true)
            return
        }
        if (isDirectAudioMode && pendingSync?.isPlaying == true) {
            val player = exoPlayerInstance ?: return
            val curPos = player.currentPosition
            val nowElapsed = android.os.SystemClock.elapsedRealtime()
            if (player.playbackState == Player.STATE_BUFFERING && curPos == lastDirectExoPosMs) {
                if (directExoStallSinceElapsedMs == 0L) {
                    directExoStallSinceElapsedMs = nowElapsed
                } else if (nowElapsed - directExoStallSinceElapsedMs >= 16_000L) {
                    directExoStallSinceElapsedMs = nowElapsed
                    val cb = onExoPlayerStreamErrorCallback
                    if (cb != null) {
                        cb.invoke()
                    } else {
                        recoverAutonomousDirectStream()
                    }
                }
            } else {
                lastDirectExoPosMs = curPos
                directExoStallSinceElapsedMs = 0L
            }
        }
    }

    private fun dispatchSkipNext() {
        if (pendingSync?.song?.id == PROMO_AUDIO_AD_ID && !isPersistedVipActiveNow()) {
            return
        }
        val cb = onSkipNextCallback
        if (cb != null) {
            cb.invoke()
        } else {
            advanceAutonomousQueue(forward = true)
        }
    }

    private fun dispatchSkipPrevious() {
        if (pendingSync?.song?.id == PROMO_AUDIO_AD_ID && !isPersistedVipActiveNow()) {
            return
        }
        val cb = onSkipPreviousCallback
        if (cb != null) {
            cb.invoke()
        } else {
            advanceAutonomousQueue(forward = false)
        }
    }

    private fun dispatchDirectTrackEnded() {
        val cb = onExoPlayerTrackEndedCallback
        if (cb != null) {
            cb.invoke()
        } else {
            handleAutonomousTrackEnded()
        }
    }

    private fun isPersistedVipActiveNow(): Boolean {
        return try {
            val prefs = applicationContext.getSharedPreferences("ak_music_user_prefs", Context.MODE_PRIVATE)
            val planId = prefs.getString("sub_plan_id", "none") ?: "none"
            val activatedAt = prefs.getLong("sub_activated_at_ms", 0L)
            val expiresAt = prefs.getLong("sub_expires_at_ms", 0L)
            val orderId = prefs.getString("sub_order_id", "") ?: ""
            val utr = prefs.getString("sub_utr", "") ?: ""
            val state = com.example.models.UserSubscriptionState(
                planType = com.example.models.SubscriptionPlanType.fromId(planId),
                activatedAtMs = activatedAt,
                expiresAtMs = expiresAt,
                orderId = orderId,
                utr = utr
            )
            if (!state.isActive(System.currentTimeMillis())) return false
            val storedSeal = prefs.getString("sub_vault_seal_v6", "").orEmpty()
            com.example.security.AadifySecurityVault.verifySubscriptionIntegritySeal(
                storedSeal = storedSeal,
                planId = state.planType.id,
                activatedAtMs = activatedAt,
                expiresAtMs = expiresAt,
                orderId = orderId,
                utr = utr
            )
        } catch (_: Exception) {
            false
        }
    }

    private fun canFreeOrVipUserContinuePlayback(): Boolean {
        if (isPersistedVipActiveNow()) {
            lastAutonomousFreeTickElapsedMs = 0L
            return true
        }
        return try {
            val prefs = applicationContext.getSharedPreferences("ak_music_user_prefs", Context.MODE_PRIVATE)
            val nowMs = System.currentTimeMillis()
            val normUser = activeUserNameSnapshot.trim().lowercase(java.util.Locale.US)
            val globalWin = prefs.getLong("limit_window_start_ms", 0L)
            val userWin = if (normUser.isNotBlank()) {
                prefs.getLong("user_hard_window_start_${normUser}", 0L)
            } else {
                0L
            }
            val winStart = when {
                globalWin > 0L && userWin > 0L -> minOf(globalWin, userWin)
                globalWin > 0L -> globalWin
                else -> userWin
            }
            val resetWindowMs = 12L * 60L * 60L * 1000L
            var used = if (winStart > 0L && (nowMs - winStart) >= resetWindowMs) {
                0
            } else {
                val baseUsed = maxOf(
                    prefs.getInt("limit_used_seconds", 0),
                    prefs.getInt("device_hard_limit_used_seconds", 0)
                )
                if (normUser.isNotBlank()) {
                    maxOf(baseUsed, prefs.getInt("user_hard_limit_used_${normUser}", 0))
                } else {
                    baseUsed
                }
            }
            val nowElapsed = android.os.SystemClock.elapsedRealtime()
            if (lastAutonomousFreeTickElapsedMs > 0L) {
                val deltaSecs = ((nowElapsed - lastAutonomousFreeTickElapsedMs) / 1000L).toInt().coerceIn(0, 5)
                if (deltaSecs > 0) {
                    used += deltaSecs
                    val effectiveWinStart = if (winStart > 0L && (nowMs - winStart) < resetWindowMs) winStart else nowMs
                    prefs.edit()
                        .putLong("limit_window_start_ms", effectiveWinStart)
                        .putInt("limit_used_seconds", used)
                        .putInt("device_hard_limit_used_seconds", used)
                        .apply {
                            if (normUser.isNotBlank()) {
                                putInt("user_hard_limit_used_${normUser}", used)
                                putLong("user_hard_window_start_${normUser}", effectiveWinStart)
                            }
                        }
                        .apply()
                    lastAutonomousFreeTickElapsedMs = nowElapsed
                }
            } else {
                lastAutonomousFreeTickElapsedMs = nowElapsed
            }
            used < freeDailyLimitSecondsSnapshot.coerceAtLeast(60)
        } catch (_: Exception) {
            true
        }
    }

    private fun handleAutonomousTrackEnded() {
        val current = pendingSync?.song ?: return
        if (!canFreeOrVipUserContinuePlayback()) {
            pendingSync = pendingSync?.copy(isPlaying = false)
            applyPlayStateInternal(shouldPlay = false, forceNotificationUpdate = true)
            return
        }
        if (current.id == PROMO_AUDIO_AD_ID) {
            autonomousFreeSongsSinceAd = 0
            val pendingNext = autonomousPendingNextSong
            autonomousPendingNextSong = null
            if (pendingNext != null) {
                playSongAutonomously(pendingNext, bypassAdGate = true)
                return
            }
            val fallback = queueSnapshot.firstOrNull()
            if (fallback != null) {
                playSongAutonomously(fallback, bypassAdGate = true)
            }
            return
        }
        val list = queueSnapshot
        if (repeatOneSnapshot || list.isEmpty()) {
            playSongAutonomously(current)
            return
        }
        advanceAutonomousQueue(forward = true)
    }

    private fun advanceAutonomousQueue(forward: Boolean) {
        val current = pendingSync?.song ?: return
        if (!canFreeOrVipUserContinuePlayback()) {
            pendingSync = pendingSync?.copy(isPlaying = false)
            applyPlayStateInternal(shouldPlay = false, forceNotificationUpdate = true)
            return
        }
        val list = queueSnapshot.ifEmpty { listOf(current) }
        val nextSong = if (shuffleSnapshot && list.size > 1) {
            list.filter { it.id != current.id }.randomOrNull() ?: list.first()
        } else {
            val idx = list.indexOfFirst { it.id == current.id }
            val nextIdx = if (forward) {
                if (idx in 0 until list.lastIndex) idx + 1 else 0
            } else {
                if (idx > 0) idx - 1 else list.lastIndex
            }
            list[nextIdx]
        }
        playSongAutonomously(nextSong)
    }

    private fun recoverAutonomousDirectStream() {
        val current = pendingSync?.song ?: return
        val resumePosMs = exoPlayerInstance?.currentPosition?.coerceAtLeast(0L) ?: 0L
        if (!canFreeOrVipUserContinuePlayback()) {
            pendingSync = pendingSync?.copy(isPlaying = false)
            applyPlayStateInternal(shouldPlay = false, forceNotificationUpdate = true)
            return
        }
        ioScope.launch {
            val vid = current.cleanVideoId
            if (vid.length == 11) {
                autonomousAudioExtractor.invalidateCachedStream(vid)
                val fresh = autonomousAudioExtractor.extractExactYoutubeDirectAudioForPlayback(vid, forceFresh = true)
                if (fresh != null) {
                    syncNowPlaying(
                        context = applicationContext,
                        song = current,
                        isPlaying = true,
                        directStreamUri = fresh.streamUrl,
                        playbackSpeed = pendingSync?.playbackSpeed ?: 1.0f,
                        startPositionMs = resumePosMs
                    )
                }
            }
        }
    }

    private fun playSongAutonomously(song: Song, bypassAdGate: Boolean = false) {
        if (!bypassAdGate && song.id != PROMO_AUDIO_AD_ID && !isPersistedVipActiveNow()) {
            val prefs = applicationContext.getSharedPreferences("ak_music_user_prefs", Context.MODE_PRIVATE)
            val adsEnabled = prefs.getBoolean("ads_enabled", false)
            val adVid = prefs.getString("ads_video_id", "")?.trim().orEmpty()
            val adLabel = prefs.getString("ads_label", "")?.trim().orEmpty().ifBlank { "Sponsored Audio Ad" }
            if (adsEnabled && adVid.length == 11) {
                autonomousFreeSongsSinceAd += 1
                if (autonomousFreeSongsSinceAd >= com.example.providers.MusicViewModel.FREE_SONGS_PER_AD) {
                    autonomousFreeSongsSinceAd = 0
                    autonomousPendingNextSong = song
                    val adSong = Song(
                        id = PROMO_AUDIO_AD_ID,
                        title = adLabel,
                        artist = "AADIFY",
                        videoId = adVid,
                        bannerUrl = "https://img.youtube.com/vi/$adVid/hqdefault.jpg",
                        allowDownload = false
                    )
                    playSongAutonomously(adSong, bypassAdGate = true)
                    return
                }
            } else {
                autonomousFreeSongsSinceAd = 0
            }
        }
        prefetchArtwork(song)
        ioScope.launch {
            val localUri = song.resolvedLocalUri
            val streamUri = if (localUri.isNotBlank()) {
                localUri
            } else {
                val vid = song.cleanVideoId
                if (vid.length == 11) {
                    autonomousAudioExtractor.extractExactYoutubeDirectAudioForPlayback(vid)?.streamUrl
                } else {
                    null
                }
            }
            if (!streamUri.isNullOrBlank()) {
                syncNowPlaying(
                    context = applicationContext,
                    song = song,
                    isPlaying = true,
                    directStreamUri = streamUri,
                    playbackSpeed = pendingSync?.playbackSpeed ?: 1.0f,
                    startPositionMs = 0L
                )
            }
            // Pre-cache the following track in queueSnapshot for 0ms gapless autonomous transition
            val list = queueSnapshot
            if (list.isNotEmpty()) {
                val curIdx = list.indexOfFirst { it.id == song.id }
                val nextIdx = if (curIdx in 0 until list.lastIndex) curIdx + 1 else 0
                val followUp = list[nextIdx]
                if (followUp.id != song.id) {
                    prefetchArtwork(followUp)
                    val nextVid = followUp.cleanVideoId
                    if (nextVid.length == 11 && followUp.resolvedLocalUri.isBlank()) {
                        autonomousAudioExtractor.extractExactYoutubeDirectAudioForPlayback(nextVid)
                    }
                }
            }
        }
    }

    private fun applySyncRequest(req: SyncRequest, revision: Long = requestedSyncRevision) {
        val player = exoPlayerInstance ?: return
        isInternalSync = true
        try {
            val song = req.song
            val directUri = req.directStreamUri?.takeIf { it.isNotBlank() }
            isDirectAudioMode = directUri != null
            appliedSyncRevision = revision
            appliedMediaId = song.id
            lastDirectExoPosMs = -1L
            directExoStallSinceElapsedMs = 0L
            syncedOnlinePositionMs = req.startPositionMs.coerceAtLeast(0L)
            syncedOnlineDurationMs = 0L
            val silentUri = getOrCreateSilentWavUri(this)
            // Use silent WAV fallback ONLY when directStreamUri is null
            val activeUri = directUri ?: silentUri

            if (isDirectAudioMode && req.isPlaying) {
                requestAudioFocusForExternalWait()
            } else if (!isDirectAudioMode && !isPausedByExternalMedia) {
                // Let BackstageAudioWebView own the primary audio focus when streaming online so ExoPlayer never steals it
                abandonAudioFocusIfHeld()
            }

            val bannerUrl = song.highResBannerUrl
            val cachedArt = getCachedArtworkBytesForSong(song)
            val metadata = buildMediaMetadataForSong(song, cachedArt, syncedOnlineDurationMs)
            currentActiveMetadata = metadata

            // Build a 3-item queue [prevSentinel, activeItem, nextSentinel] starting at index 1
            // so ExoPlayer natively reports hasPreviousMediaItem() = true and hasNextMediaItem() = true!
            val prevItem = MediaItem.Builder()
                .setMediaId("${song.id}_prev")
                .setUri(silentUri)
                .setMediaMetadata(metadata)
                .build()

            val currentItem = MediaItem.Builder()
                .setMediaId(song.id)
                .setUri(activeUri)
                .setMediaMetadata(metadata)
                .build()

            val nextItem = MediaItem.Builder()
                .setMediaId("${song.id}_next")
                .setUri(silentUri)
                .setMediaMetadata(metadata)
                .build()

            // Always keep volume = 1.0f (silentUri is already 16-bit PCM digital 0x0000 silence!)
            // so Android's Quick Settings Media Tile ("Music Palette") never treats the session as muted/inactive!
            player.volume = 1.0f
            player.repeatMode = if (isDirectAudioMode) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ONE
            player.playbackParameters = PlaybackParameters(req.playbackSpeed)
            val startPos = req.startPositionMs.coerceAtLeast(0L)
            player.setMediaItems(listOf(prevItem, currentItem, nextItem), /* startIndex = */ 1, /* startPositionMs = */ startPos)
            player.prepare()
            if (req.isPlaying) {
                player.play()
                ensureServiceLocksHeld()
            } else {
                if (!isDirectAudioMode) {
                    player.play()
                } else {
                    player.pause()
                }
                releaseServiceLocksWhenPaused()
            }
            postOrUpdateForegroundMediaNotification(song, req.isPlaying, cachedArt)

            // If artwork bytes are not cached yet, download immediately (0ms delay) and update MediaMetadata
            // so the Quick Settings Media Tile and Lock Screen display crisp album art without blur!
            if (cachedArt == null && (bannerUrl.startsWith("http", ignoreCase = true) || song.cleanVideoId.isNotBlank())) {
                fetchAndAttachArtworkAsync(song, bannerUrl, activeUri, silentUri)
            }
        } catch (_: Exception) {
        } finally {
            isInternalSync = false
        }
    }

    private fun fetchAndAttachArtworkAsync(
        song: Song,
        bannerUrl: String,
        activeUri: String,
        silentUri: String
    ) {
        ioScope.launch {
            if (pendingSync?.song?.id != song.id) return@launch
            val cleanVid = song.cleanVideoId
            val maxResYtBanner = if (cleanVid.length == 11) {
                "https://img.youtube.com/vi/$cleanVid/maxresdefault.jpg"
            } else {
                ""
            }
            val sdResYtBanner = if (cleanVid.length == 11) {
                "https://img.youtube.com/vi/$cleanVid/sddefault.jpg"
            } else {
                ""
            }
            val fallbackYtBanner = if (cleanVid.length == 11) {
                "https://img.youtube.com/vi/$cleanVid/hqdefault.jpg"
            } else {
                ""
            }
            val bytes = (if (maxResYtBanner.isNotBlank()) downloadCompressedArtworkBytes(maxResYtBanner) else null)
                ?: (if (sdResYtBanner.isNotBlank()) downloadCompressedArtworkBytes(sdResYtBanner) else null)
                ?: (if (bannerUrl.startsWith("http", ignoreCase = true)) downloadCompressedArtworkBytes(bannerUrl) else null)
                ?: (if (fallbackYtBanner.isNotBlank() && fallbackYtBanner != bannerUrl) downloadCompressedArtworkBytes(fallbackYtBanner) else null)
                ?: return@launch
            if (bannerUrl.isNotBlank()) {
                artworkBytesCache.put(bannerUrl, bytes)
            }
            if (cleanVid.length == 11) {
                artworkBytesCache.put("yt_$cleanVid", bytes)
            }
            if (fallbackYtBanner.isNotBlank()) {
                artworkBytesCache.put(fallbackYtBanner, bytes)
            }
            mainHandler.post {
                val currentSynced = pendingSync?.song ?: return@post
                if (currentSynced.id != song.id) return@post
                val player = exoPlayerInstance ?: return@post
                isInternalSync = true
                try {
                    val enrichedMetadata = buildMediaMetadataForSong(song, bytes, syncedOnlineDurationMs)
                    currentActiveMetadata = enrichedMetadata
                    player.setPlaylistMetadata(enrichedMetadata)
                    val updatedPrev = MediaItem.Builder()
                        .setMediaId("${song.id}_prev")
                        .setUri(silentUri)
                        .setMediaMetadata(enrichedMetadata)
                        .build()
                    val updatedNext = MediaItem.Builder()
                        .setMediaId("${song.id}_next")
                        .setUri(silentUri)
                        .setMediaMetadata(enrichedMetadata)
                        .build()
                    if (player.mediaItemCount >= 3) {
                        player.replaceMediaItem(0, updatedPrev)
                        player.replaceMediaItem(2, updatedNext)
                    }
                    val playingNow = pendingSync?.isPlaying == true
                    postOrUpdateForegroundMediaNotification(song, playingNow, bytes)
                } catch (_: Exception) {
                } finally {
                    isInternalSync = false
                }
            }
        }
    }

    private fun applyPlayStateInternal(shouldPlay: Boolean, forceNotificationUpdate: Boolean = true) {
        val player = exoPlayerInstance ?: return
        isInternalSync = true
        try {
            player.volume = 1.0f
            if (shouldPlay) {
                if (isDirectAudioMode) {
                    requestAudioFocusForExternalWait()
                }
                if (player.mediaItemCount == 0 && pendingSync != null) {
                    applySyncRequest(pendingSync!!, requestedSyncRevision)
                } else {
                    if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) {
                        player.prepare()
                    }
                    if (!player.isPlaying || !player.playWhenReady) {
                        player.play()
                    }
                    if (!isDirectAudioMode && player.mediaItemCount >= 3) {
                        val targetSeekMs = syncedOnlinePositionMs.coerceIn(0L, 590_000L)
                        player.seekTo(1, targetSeekMs)
                    }
                }
                ensureServiceLocksHeld()
            } else {
                lastAutonomousFreeTickElapsedMs = 0L
                releaseServiceLocksWhenPaused()
                if (isDirectAudioMode) {
                    if (player.isPlaying || player.playWhenReady) {
                        player.pause()
                    }
                } else {
                    // Keep silent keep-alive WAV active in background even while paused so Android
                    // never suspends the WebView or MediaSession process; nudge seek position so
                    // MediaSession re-evaluates ForwardingPlayer.isPlaying() = false immediately!
                    if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) {
                        player.prepare()
                    }
                    if (!player.isPlaying || !player.playWhenReady) {
                        player.play()
                    }
                    if (player.mediaItemCount >= 3) {
                        val targetSeekMs = syncedOnlinePositionMs.coerceIn(0L, 590_000L)
                        player.seekTo(1, targetSeekMs)
                    }
                }
            }
            if (forceNotificationUpdate) {
                val activeSong = pendingSync?.song
                val cachedBytes = getCachedArtworkBytesForSong(activeSong)
                postOrUpdateForegroundMediaNotification(activeSong, shouldPlay, cachedBytes)
            }
        } catch (_: Exception) {
        } finally {
            isInternalSync = false
        }
    }

    private fun applySeekInternal(positionMs: Long) {
        val player = exoPlayerInstance ?: return
        isInternalSync = true
        try {
            player.seekTo(1, positionMs.coerceAtLeast(0L))
        } catch (_: Exception) {
        } finally {
            isInternalSync = false
        }
    }

    private fun applySpeedInternal(speed: Float) {
        val player = exoPlayerInstance ?: return
        isInternalSync = true
        try {
            player.playbackParameters = PlaybackParameters(speed)
        } catch (_: Exception) {
        } finally {
            isInternalSync = false
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep background notification watcher alive and schedule a quick restart if the task is swiped away
        try {
            val restartIntent = Intent(applicationContext, MusicPlaybackService::class.java).apply {
                action = ACTION_WATCH_NOTIFICATIONS
            }
            val pendingRestart = PendingIntent.getService(
                applicationContext,
                777,
                restartIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT
            )
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager
            alarmManager?.set(
                android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                android.os.SystemClock.elapsedRealtime() + 2500L,
                pendingRestart
            )
        } catch (_: Exception) {
        }
        ensureBackgroundNotificationWatcher(applicationContext, startServiceIfNeeded = false)
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        isForegroundServiceStarted = false
        mainHandler.removeCallbacks(externalMediaResumeWatcherRunnable)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                audioPlaybackCallback?.let { audioManager?.unregisterAudioPlaybackCallback(it) }
            } catch (_: Exception) {
            }
        }
        abandonAudioFocusIfHeld()
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Exception) {
        }
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        exoPlayerInstance = null
        super.onDestroy()
    }
}
