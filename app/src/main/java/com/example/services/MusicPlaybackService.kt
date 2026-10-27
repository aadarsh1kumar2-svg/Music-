package com.example.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
import android.util.LruCache
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.example.MainActivity
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
        private const val NOTIFICATION_CHANNEL_ID = "aadify_playback_channel_v2"
        private const val NOTIFICATION_ID = 1001
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

        @Volatile
        private var pendingSync: SyncRequest? = null

        @Volatile
        var onSkipNextCallback: (() -> Unit)? = null

        @Volatile
        var onSkipPreviousCallback: (() -> Unit)? = null

        @Volatile
        var onTogglePlayPauseCallback: ((Boolean) -> Unit)? = null

        @Volatile
        var onExoPlayerTrackEndedCallback: (() -> Unit)? = null

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
                if (pendingSync?.isPlaying == true && !isPausedByExternalMedia) {
                    isKeepAliveScheduled = true
                    onKeepOnlineEngineAwakeCallback?.invoke()
                    mainHandler.postDelayed(this, 500L)
                } else {
                    isKeepAliveScheduled = false
                }
            }
        }

        fun getCurrentSyncedSong(): Song? = pendingSync?.song

        fun isCurrentlyPlaying(): Boolean = pendingSync?.isPlaying == true

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
            val req = SyncRequest(song, isPlaying, directStreamUri, playbackSpeed, startPositionMs.coerceAtLeast(0L))
            pendingSync = req
            mainHandler.post {
                if (isPlaying) {
                    mainHandler.removeCallbacks(serviceKeepAliveRunnable)
                    mainHandler.postDelayed(serviceKeepAliveRunnable, 500L)
                } else {
                    mainHandler.removeCallbacks(serviceKeepAliveRunnable)
                }
                val svc = instance
                if (svc != null) {
                    svc.applySyncRequest(req)
                } else {
                    BackgroundGamingAudioKeeper.ensureForegroundMediaServiceStarted(context)
                }
            }
        }

        fun syncPlayState(isPlaying: Boolean) {
            val stateChanged = pendingSync?.isPlaying != isPlaying
            pendingSync = pendingSync?.copy(isPlaying = isPlaying)
            mainHandler.post {
                if (isPlaying) {
                    isPausedByExternalMedia = false
                    instance?.cancelExternalMediaResumeWatch()
                    if (!isKeepAliveScheduled) {
                        isKeepAliveScheduled = true
                        mainHandler.removeCallbacks(serviceKeepAliveRunnable)
                        mainHandler.postDelayed(serviceKeepAliveRunnable, 500L)
                    }
                } else {
                    isKeepAliveScheduled = false
                    mainHandler.removeCallbacks(serviceKeepAliveRunnable)
                }
                instance?.applyPlayStateInternal(isPlaying, forceNotificationUpdate = stateChanged)
            }
        }

        fun syncSeekPosition(positionMs: Long) {
            mainHandler.post {
                instance?.applySeekInternal(positionMs)
            }
        }

        fun syncPlaybackSpeed(speed: Float) {
            pendingSync = pendingSync?.copy(playbackSpeed = speed)
            mainHandler.post {
                instance?.applySpeedInternal(speed)
            }
        }

        fun getCurrentExoPositionMs(): Long {
            return instance?.exoPlayerInstance?.currentPosition?.coerceAtLeast(0L) ?: 0L
        }

        fun getCurrentExoDurationMs(): Long {
            return instance?.exoPlayerInstance?.duration?.coerceAtLeast(0L) ?: 0L
        }

        fun isExoPlaying(): Boolean {
            return instance?.exoPlayerInstance?.isPlaying == true
        }

        /**
         * Generates a 60-second standard 16-bit signed PCM silent WAV file used by MusicPlaybackService
         * to keep the Foreground MediaSession, WakeLock, WifiLock, Quick Settings Media Tile, Lock Screen,
         * and Notification Bar media controls active while BackstageYouTubePlayerController streams online audio.
         */
        fun getOrCreateSilentWavUri(context: Context): String {
            val file = File(context.cacheDir, "aadify_keepalive_16bit_60s.wav")
            if (file.exists() && file.length() > 100_000L) {
                return Uri.fromFile(file).toString()
            }
            try {
                val sampleRate = 8000
                val durationSeconds = 60
                val numSamples = sampleRate * durationSeconds
                val bytesPerSample = 2 // 16-bit signed PCM (0x0000 = pure silence)
                val dataSize = numSamples * bytesPerSample
                val buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)

                // RIFF header
                buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
                buffer.putInt(36 + dataSize)
                buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
                // fmt subchunk
                buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
                buffer.putInt(16) // PCM
                buffer.putShort(1.toShort()) // AudioFormat = 1 (PCM)
                buffer.putShort(1.toShort()) // NumChannels = 1 (Mono)
                buffer.putInt(sampleRate)
                buffer.putInt(sampleRate * bytesPerSample) // ByteRate
                buffer.putShort(bytesPerSample.toShort()) // BlockAlign
                buffer.putShort(16.toShort()) // BitsPerSample = 16
                // data subchunk (all zero bytes = 16-bit signed silence)
                buffer.put("data".toByteArray(Charsets.US_ASCII))
                buffer.putInt(dataSize)
                buffer.position(44 + dataSize)

                FileOutputStream(file).use { out ->
                    out.write(buffer.array())
                }
            } catch (_: Exception) {
            }
            return Uri.fromFile(file).toString()
        }
    }

    private var mediaSession: MediaSession? = null
    private var exoPlayerInstance: ExoPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var audioPlaybackCallback: AudioManager.AudioPlaybackCallback? = null
    private var isInternalSync: Boolean = false
    private var isDirectAudioMode: Boolean = false
    private var zeroExternalMediaConsecutiveChecks: Int = 0

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
            val resolvedUserAgent = AudioStreamExtractor.resolveUserAgentForStreamUrl(streamUrl)
            dataSpec.buildUpon()
                .setHttpRequestHeaders(
                    mapOf(
                        "User-Agent" to resolvedUserAgent,
                        "Accept" to "*/*"
                    )
                )
                .build()
        }

        val dataSourceFactory = DefaultDataSource.Factory(this, resolvingHttpFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

        // Pass handleAudioFocus = false on ExoPlayer so ExoPlayer never steals audio focus from BackstageAudioWebView!
        val exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(audioAttributes, false)
            .setHandleAudioBecomingNoisy(false)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        exoPlayerInstance = exoPlayer

        exoPlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                try {
                    if (isPlaying || pendingSync?.isPlaying == true) {
                        if (wakeLock?.isHeld == false) wakeLock?.acquire(6 * 60 * 60 * 1000L)
                        if (wifiLock?.isHeld == false) wifiLock?.acquire()
                    }
                } catch (_: Exception) {
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (isInternalSync) return
                val itemId = mediaItem?.mediaId.orEmpty()
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) {
                    if (itemId.endsWith("_next")) {
                        onSkipNextCallback?.invoke()
                        return
                    } else if (itemId.endsWith("_prev")) {
                        onSkipPreviousCallback?.invoke()
                        return
                    }
                }
                if (isDirectAudioMode && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    onExoPlayerTrackEndedCallback?.invoke()
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
                    onExoPlayerTrackEndedCallback?.invoke()
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
        })

        // Wrap ExoPlayer in a ForwardingPlayer that ALWAYS enables Next, Previous, Play/Pause, and Stop
        // in the Android System Quick Settings Media Tile, Notification Bar, and Lock Screen controls!
        val forwardingPlayer = object : ForwardingPlayer(exoPlayer) {
            private fun buildAllCommands(base: Player.Commands): Player.Commands {
                return base.buildUpon()
                    .add(Player.COMMAND_PLAY_PAUSE)
                    .add(Player.COMMAND_STOP)
                    .add(Player.COMMAND_SEEK_TO_NEXT)
                    .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                    .build()
            }

            override fun addListener(listener: Player.Listener) {
                super.addListener(object : Player.Listener by listener {
                    override fun onAvailableCommandsChanged(availableCommands: Player.Commands) {
                        listener.onAvailableCommandsChanged(buildAllCommands(availableCommands))
                    }
                })
            }

            override fun getAvailableCommands(): Player.Commands {
                return buildAllCommands(super.getAvailableCommands())
            }

            override fun isCommandAvailable(command: Int): Boolean {
                return when (command) {
                    Player.COMMAND_SEEK_TO_NEXT,
                    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_PREVIOUS,
                    Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                    Player.COMMAND_PLAY_PAUSE,
                    Player.COMMAND_STOP -> true
                    else -> super.isCommandAvailable(command)
                }
            }

            override fun hasNextMediaItem(): Boolean = true

            override fun hasPreviousMediaItem(): Boolean = true

            override fun seekToNext() {
                if (!isInternalSync) {
                    onSkipNextCallback?.invoke()
                }
            }

            override fun seekToNextMediaItem() {
                if (!isInternalSync) {
                    onSkipNextCallback?.invoke()
                }
            }

            override fun seekToPrevious() {
                if (!isInternalSync) {
                    onSkipPreviousCallback?.invoke()
                }
            }

            override fun seekToPreviousMediaItem() {
                if (!isInternalSync) {
                    onSkipPreviousCallback?.invoke()
                }
            }

            override fun seekToDefaultPosition(mediaItemIndex: Int) {
                if (!isInternalSync) {
                    if (mediaItemIndex > 1) {
                        onSkipNextCallback?.invoke()
                        return
                    } else if (mediaItemIndex < 1) {
                        onSkipPreviousCallback?.invoke()
                        return
                    }
                }
                super.seekToDefaultPosition(mediaItemIndex)
            }

            override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
                if (!isInternalSync) {
                    if (mediaItemIndex > 1) {
                        onSkipNextCallback?.invoke()
                        return
                    } else if (mediaItemIndex < 1) {
                        onSkipPreviousCallback?.invoke()
                        return
                    }
                }
                super.seekTo(mediaItemIndex, positionMs)
            }

            override fun play() {
                super.play()
                if (!isInternalSync) {
                    isPausedByExternalMedia = false
                    cancelExternalMediaResumeWatch()
                    onTogglePlayPauseCallback?.invoke(true)
                }
            }

            override fun pause() {
                super.pause()
                if (!isInternalSync) {
                    isPausedByExternalMedia = false
                    cancelExternalMediaResumeWatch()
                    onTogglePlayPauseCallback?.invoke(false)
                }
            }

            override fun setPlayWhenReady(playWhenReady: Boolean) {
                super.setPlayWhenReady(playWhenReady)
                if (!isInternalSync) {
                    if (playWhenReady) {
                        isPausedByExternalMedia = false
                        cancelExternalMediaResumeWatch()
                    }
                    onTogglePlayPauseCallback?.invoke(playWhenReady)
                }
            }

            override fun stop() {
                super.pause()
                if (!isInternalSync) {
                    isPausedByExternalMedia = false
                    cancelExternalMediaResumeWatch()
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
                    .add(Player.COMMAND_PLAY_PAUSE)
                    .add(Player.COMMAND_STOP)
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
            .setSessionActivity(pendingIntent)
            .setCallback(callback)
            .build()

        pendingSync?.let { req ->
            applySyncRequest(req)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
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
            mediaSession?.let { session ->
                onUpdateNotification(session, false)
            }
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
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Shows Quick Settings, Lock Screen, and Notification Bar playback controls"
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    setShowBadge(false)
                }
                nm.createNotificationChannel(channel)
            } catch (_: Exception) {
            }
        }
    }

    private fun buildMediaMetadataForSong(song: Song, artworkBytes: ByteArray?): MediaMetadata {
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
        if (artworkUri != null) {
            metadataBuilder.setArtworkUri(artworkUri)
        }
        if (artworkBytes != null && artworkBytes.isNotEmpty()) {
            metadataBuilder.setArtworkData(artworkBytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
        }
        return metadataBuilder.build()
    }

    private fun applySyncRequest(req: SyncRequest) {
        val player = exoPlayerInstance ?: return
        isInternalSync = true
        try {
            val song = req.song
            val silentUri = getOrCreateSilentWavUri(this)
            val activeUri = req.directStreamUri?.takeIf { it.isNotBlank() } ?: silentUri
            isDirectAudioMode = !req.directStreamUri.isNullOrBlank()

            if (isDirectAudioMode && req.isPlaying) {
                requestAudioFocusForExternalWait()
            } else if (!isDirectAudioMode && !isPausedByExternalMedia) {
                // Let BackstageAudioWebView own the primary audio focus when streaming online so ExoPlayer never steals it
                abandonAudioFocusIfHeld()
            }

            val bannerUrl = song.highResBannerUrl
            val cachedArt = if (bannerUrl.isNotBlank()) artworkBytesCache.get(bannerUrl) else null
            val metadata = buildMediaMetadataForSong(song, cachedArt)

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
            val startPos = if (isDirectAudioMode) req.startPositionMs.coerceAtLeast(0L) else 0L
            player.setMediaItems(listOf(prevItem, currentItem, nextItem), /* startIndex = */ 1, /* startPositionMs = */ startPos)
            player.prepare()
            if (req.isPlaying) {
                player.play()
                try {
                    if (wakeLock?.isHeld == false) wakeLock?.acquire(6 * 60 * 60 * 1000L)
                    if (wifiLock?.isHeld == false) wifiLock?.acquire()
                    BackgroundGamingAudioKeeper.getInstance(this).startGamingKeepAlive()
                } catch (_: Exception) {
                }
            } else {
                player.pause()
            }
            mediaSession?.let { session ->
                try {
                    onUpdateNotification(session, req.isPlaying)
                } catch (_: Exception) {
                }
            }

            // If artwork bytes are not cached yet, download asynchronously and update MediaMetadata
            // so the Quick Settings Media Tile and Lock Screen display the album cover crisp & fast!
            if (cachedArt == null && bannerUrl.startsWith("http", ignoreCase = true)) {
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
            val bytes = downloadCompressedArtworkBytes(bannerUrl) ?: return@launch
            artworkBytesCache.put(bannerUrl, bytes)
            mainHandler.post {
                val currentSynced = pendingSync?.song ?: return@post
                if (currentSynced.id != song.id) return@post
                // Do not call replaceMediaItem while ExoPlayer is streaming a direct audio file,
                // as replaceMediaItem on the active index can interrupt direct audio playback
                if (isDirectAudioMode) return@post
                val player = exoPlayerInstance ?: return@post
                isInternalSync = true
                try {
                    val enrichedMetadata = buildMediaMetadataForSong(song, bytes)
                    val updatedItem = MediaItem.Builder()
                        .setMediaId(song.id)
                        .setUri(activeUri.ifBlank { silentUri })
                        .setMediaMetadata(enrichedMetadata)
                        .build()
                    if (player.mediaItemCount >= 3) {
                        player.replaceMediaItem(1, updatedItem)
                    }
                    mediaSession?.let { session ->
                        onUpdateNotification(session, pendingSync?.isPlaying == true)
                    }
                } catch (_: Exception) {
                } finally {
                    isInternalSync = false
                }
            }
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
                val bmp = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size) ?: return null
                val maxDim = 512
                val scaled = if (bmp.width > maxDim || bmp.height > maxDim) {
                    val ratio = minOf(maxDim.toFloat() / bmp.width, maxDim.toFloat() / bmp.height)
                    Bitmap.createScaledBitmap(
                        bmp,
                        (bmp.width * ratio).toInt().coerceAtLeast(1),
                        (bmp.height * ratio).toInt().coerceAtLeast(1),
                        true
                    )
                } else {
                    bmp
                }
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 86, out)
                out.toByteArray()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun applyPlayStateInternal(shouldPlay: Boolean, forceNotificationUpdate: Boolean = true) {
        val player = exoPlayerInstance ?: return
        val wasPlayWhenReady = player.playWhenReady
        isInternalSync = true
        try {
            player.volume = 1.0f
            if (shouldPlay) {
                if (isDirectAudioMode) {
                    requestAudioFocusForExternalWait()
                }
                if (player.mediaItemCount == 0 && pendingSync != null) {
                    applySyncRequest(pendingSync!!)
                } else {
                    if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) {
                        player.prepare()
                    }
                    if (!player.isPlaying || !player.playWhenReady) {
                        player.play()
                    }
                }
                try {
                    if (wakeLock?.isHeld == false) wakeLock?.acquire(6 * 60 * 60 * 1000L)
                    if (wifiLock?.isHeld == false) wifiLock?.acquire()
                    BackgroundGamingAudioKeeper.getInstance(this).startGamingKeepAlive()
                } catch (_: Exception) {
                }
            } else {
                if (player.isPlaying || player.playWhenReady) {
                    player.pause()
                }
            }
            if (forceNotificationUpdate || wasPlayWhenReady != shouldPlay) {
                mediaSession?.let { session ->
                    try {
                        onUpdateNotification(session, shouldPlay)
                    } catch (_: Exception) {
                    }
                }
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
        val player = mediaSession?.player
        if (player == null || (!player.playWhenReady && !isPausedByExternalMedia) || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
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
