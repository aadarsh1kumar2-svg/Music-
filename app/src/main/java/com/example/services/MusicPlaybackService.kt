package com.example.services

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.PowerManager
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.example.MainActivity

class MusicPlaybackService : MediaSessionService() {

    companion object {
        @Volatile
        var onSkipNextCallback: (() -> Unit)? = null

        @Volatile
        var onSkipPreviousCallback: (() -> Unit)? = null

        @Volatile
        var onTogglePlayPauseCallback: ((Boolean) -> Unit)? = null
    }

    private var mediaSession: MediaSession? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()

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

        val exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        exoPlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                try {
                    if (isPlaying) {
                        if (wakeLock?.isHeld == false) wakeLock?.acquire(4 * 60 * 60 * 1000L)
                        if (wifiLock?.isHeld == false) wifiLock?.acquire()
                    }
                } catch (_: Exception) {
                }
            }
        })

        // Wrap ExoPlayer in a ForwardingPlayer that always enables Next, Previous, Play/Pause, and Stop
        // in the Android System Notification Bar and Lock Screen controls!
        val forwardingPlayer = object : ForwardingPlayer(exoPlayer) {
            override fun getAvailableCommands(): Player.Commands {
                return super.getAvailableCommands()
                    .buildUpon()
                    .add(Player.COMMAND_SEEK_TO_NEXT)
                    .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .add(Player.COMMAND_PLAY_PAUSE)
                    .add(Player.COMMAND_STOP)
                    .build()
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
                val cb = onSkipNextCallback
                if (cb != null) {
                    cb.invoke()
                } else {
                    super.seekToNext()
                }
            }

            override fun seekToNextMediaItem() {
                val cb = onSkipNextCallback
                if (cb != null) {
                    cb.invoke()
                } else {
                    super.seekToNextMediaItem()
                }
            }

            override fun seekToPrevious() {
                val cb = onSkipPreviousCallback
                if (cb != null) {
                    cb.invoke()
                } else {
                    super.seekToPrevious()
                }
            }

            override fun seekToPreviousMediaItem() {
                val cb = onSkipPreviousCallback
                if (cb != null) {
                    cb.invoke()
                } else {
                    super.seekToPreviousMediaItem()
                }
            }

            override fun play() {
                super.play()
                onTogglePlayPauseCallback?.invoke(true)
            }

            override fun pause() {
                super.pause()
                onTogglePlayPauseCallback?.invoke(false)
            }

            override fun stop() {
                super.pause()
                onTogglePlayPauseCallback?.invoke(false)
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

        mediaSession = MediaSession.Builder(this, forwardingPlayer)
            .setSessionActivity(pendingIntent)
            .build()
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
        super.onDestroy()
    }
}
