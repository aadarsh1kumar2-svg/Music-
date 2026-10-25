package com.example.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
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
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MusicPlaybackService : MediaSessionService() {

    private data class SyncRequest(
        val song: Song,
        val isPlaying: Boolean,
        val directStreamUri: String?,
        val playbackSpeed: Float
    )

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "aadify_playback_channel_v2"
        private const val NOTIFICATION_ID = 1001
        private val mainHandler = Handler(Looper.getMainLooper())

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

        fun getCurrentSyncedSong(): Song? = pendingSync?.song

        fun isCurrentlyPlaying(): Boolean = pendingSync?.isPlaying == true

        /**
         * Updates the Foreground MediaSessionService directly in-process so there is zero IPC
         * URI stripping and all Notification Bar & Lock Screen buttons (Previous, Play/Pause, Next, Stop)
         * work instantaneously on every Android version.
         */
        fun syncNowPlaying(
            context: Context,
            song: Song,
            isPlaying: Boolean,
            directStreamUri: String? = null,
            playbackSpeed: Float = 1.0f
        ) {
            val req = SyncRequest(song, isPlaying, directStreamUri, playbackSpeed)
            pendingSync = req
            mainHandler.post {
                val svc = instance
                if (svc != null) {
                    svc.applySyncRequest(req)
                } else {
                    try {
                        val intent = Intent(context.applicationContext, MusicPlaybackService::class.java)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.applicationContext.startForegroundService(intent)
                        } else {
                            context.applicationContext.startService(intent)
                        }
                    } catch (_: Exception) {
                        try {
                            val intent = Intent(context.applicationContext, MusicPlaybackService::class.java)
                            context.applicationContext.startService(intent)
                        } catch (_: Exception) {
                        }
                    }
                }
            }
        }

        fun syncPlayState(isPlaying: Boolean) {
            pendingSync = pendingSync?.copy(isPlaying = isPlaying)
            mainHandler.post {
                instance?.applyPlayStateInternal(isPlaying)
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
         * Generates a 180-second (3-minute) silent WAV file used by MusicPlaybackService to keep the
         * Foreground MediaSession, WakeLock, WifiLock, Lock Screen, and Notification Bar
         * media controls active while BackstageYouTubePlayerController streams online audio.
         */
        fun getOrCreateSilentWavUri(context: Context): String {
            val file = File(context.cacheDir, "aadify_keepalive_silence_180s.wav")
            if (file.exists() && file.length() > 10_000L) {
                return Uri.fromFile(file).toString()
            }
            try {
                val sampleRate = 4000
                val durationSeconds = 180
                val numSamples = sampleRate * durationSeconds
                val dataSize = numSamples // 8-bit unsigned mono (0x80 = silence)
                val buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)

                // RIFF header
                buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
                buffer.putInt(36 + dataSize)
                buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
                // fmt subchunk
                buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
                buffer.putInt(16) // PCM
                buffer.putShort(1.toShort()) // AudioFormat = 1 (PCM)
                buffer.putShort(1.toShort()) // NumChannels = 1
                buffer.putInt(sampleRate)
                buffer.putInt(sampleRate) // ByteRate (8-bit mono = 1 byte/sample)
                buffer.putShort(1.toShort()) // BlockAlign = 1
                buffer.putShort(8.toShort()) // BitsPerSample = 8
                // data subchunk
                buffer.put("data".toByteArray(Charsets.US_ASCII))
                buffer.putInt(dataSize)
                val silenceBytes = ByteArray(dataSize) { 0x80.toByte() }
                buffer.put(silenceBytes)

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
    private var isInternalSync: Boolean = false
    private var isDirectAudioMode: Boolean = false

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        instance = this

        ensurePublicLockscreenNotificationChannel()

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

        // Pass handleAudioFocus = false so ExoPlayer never steals audio focus from BackstageAudioWebView!
        val exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(audioAttributes, false)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        exoPlayerInstance = exoPlayer

        exoPlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                try {
                    if (isPlaying) {
                        if (wakeLock?.isHeld == false) wakeLock?.acquire(6 * 60 * 60 * 1000L)
                        if (wifiLock?.isHeld == false) wifiLock?.acquire()
                    }
                } catch (_: Exception) {
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (isInternalSync) return
                if (isDirectAudioMode && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    onExoPlayerTrackEndedCallback?.invoke()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (isInternalSync) return
                if (isDirectAudioMode && playbackState == Player.STATE_ENDED) {
                    onExoPlayerTrackEndedCallback?.invoke()
                }
            }
        })

        // Wrap ExoPlayer in a ForwardingPlayer that ALWAYS enables Next, Previous, Play/Pause, and Stop
        // in the Android System Notification Bar and Lock Screen (password screen) controls!
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
                    onTogglePlayPauseCallback?.invoke(true)
                }
            }

            override fun pause() {
                super.pause()
                if (!isInternalSync) {
                    onTogglePlayPauseCallback?.invoke(false)
                }
            }

            override fun setPlayWhenReady(playWhenReady: Boolean) {
                super.setPlayWhenReady(playWhenReady)
                if (!isInternalSync) {
                    onTogglePlayPauseCallback?.invoke(playWhenReady)
                }
            }

            override fun stop() {
                super.pause()
                if (!isInternalSync) {
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

    private fun ensurePublicLockscreenNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
                val channel = NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "AADIFY Music Playback",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Shows Lock Screen and Notification Bar playback controls"
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    setShowBadge(false)
                }
                nm.createNotificationChannel(channel)
            } catch (_: Exception) {
            }
        }
    }

    private fun applySyncRequest(req: SyncRequest) {
        val player = exoPlayerInstance ?: return
        isInternalSync = true
        try {
            val song = req.song
            val silentUri = getOrCreateSilentWavUri(this)
            val activeUri = req.directStreamUri?.takeIf { it.isNotBlank() } ?: silentUri
            isDirectAudioMode = !req.directStreamUri.isNullOrBlank()

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
            val metadata = metadataBuilder.build()

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

            player.volume = if (isDirectAudioMode) 1.0f else 0.0f
            player.repeatMode = if (isDirectAudioMode) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ONE
            player.playbackParameters = PlaybackParameters(req.playbackSpeed)
            player.setMediaItems(listOf(prevItem, currentItem, nextItem), /* startIndex = */ 1, /* startPositionMs = */ 0L)
            player.prepare()
            if (req.isPlaying) {
                player.play()
                try {
                    if (wakeLock?.isHeld == false) wakeLock?.acquire(6 * 60 * 60 * 1000L)
                    if (wifiLock?.isHeld == false) wifiLock?.acquire()
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
        } catch (_: Exception) {
        } finally {
            isInternalSync = false
        }
    }

    private fun applyPlayStateInternal(shouldPlay: Boolean) {
        val player = exoPlayerInstance ?: return
        isInternalSync = true
        try {
            if (shouldPlay) {
                if (player.mediaItemCount == 0 && pendingSync != null) {
                    applySyncRequest(pendingSync!!)
                } else {
                    if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) {
                        player.prepare()
                    }
                    player.play()
                }
                try {
                    if (wakeLock?.isHeld == false) wakeLock?.acquire(6 * 60 * 60 * 1000L)
                    if (wifiLock?.isHeld == false) wifiLock?.acquire()
                } catch (_: Exception) {
                }
            } else {
                player.pause()
            }
            mediaSession?.let { session ->
                try {
                    onUpdateNotification(session, shouldPlay)
                } catch (_: Exception) {
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
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
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
