package com.example

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.models.Song
import com.example.models.SubscriptionPlanType
import com.example.models.UserSubscriptionState
import com.example.providers.MusicViewModel
import com.example.security.AadifySecurityVault
import com.example.services.AudioStreamExtractor
import com.example.services.FirebaseSongsService
import com.example.services.MusicPlaybackService
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackAndSubscriptionTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("ak_music_user_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun seedVipUser(
        userName: String = "PrimeUser",
        planType: SubscriptionPlanType = SubscriptionPlanType.MONTHLY,
        durationMs: Long = 30L * 86_400_000L
    ) {
        val now = System.currentTimeMillis()
        val expires = now + durationMs
        val seal = AadifySecurityVault.computeSubscriptionIntegritySeal(
            planId = planType.id,
            activatedAtMs = now,
            expiresAtMs = expires,
            orderId = "ord_test_vip",
            utr = "utr_test_vip"
        )
        app.getSharedPreferences("ak_music_user_prefs", Context.MODE_PRIVATE).edit()
            .putString("user_name", userName)
            .putString("user_password", AadifySecurityVault.encryptSensitiveLocalString("vippass123"))
            .putString("sub_plan_id", planType.id)
            .putLong("sub_activated_at_ms", now)
            .putLong("sub_expires_at_ms", expires)
            .putString("sub_order_id", "ord_test_vip")
            .putString("sub_utr", "utr_test_vip")
            .putString("sub_vault_seal_v6", seal)
            .commit()
    }

    @Test
    fun primeUser_hasUnlimitedPlaybackPastFreeLimit_andInBackground() {
        val prefs = app.getSharedPreferences("ak_music_user_prefs", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        // Even if the device previously recorded 3 hours of free listening seconds before upgrading to VIP:
        prefs.edit()
            .putLong("limit_window_start_ms", now - 3_600_000L)
            .putInt("limit_used_seconds", 10_800)
            .putInt("device_hard_limit_used_seconds", 10_800)
            .putInt("daily_listened_seconds", 10_800)
            .putInt("admin_user_free_limit_seconds", 60)
            .commit()

        seedVipUser(userName = "AadarshVip", planType = SubscriptionPlanType.FOREVER)

        val vm = MusicViewModel(app)
        assertTrue(vm.isVerifiedVipActive())
        assertTrue(vm.subscriptionState.value.isActive())

        val testSong = Song(
            id = "yt_dQw4w9WgXcQ",
            title = "Prime Unlimited Track",
            artist = "Real Artist",
            videoId = "dQw4w9WgXcQ"
        )
        val testSong2 = Song(
            id = "yt_9bZkp7q19f0",
            title = "Second Prime Track",
            artist = "Artist Two",
            videoId = "9bZkp7q19f0"
        )

        vm.playSong(testSong, listOf(testSong, testSong2))
        ShadowLooper.idleMainLooper(3, TimeUnit.SECONDS)
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        // Must NOT trigger daily limit modal or pause for Prime user even in background!
        vm.onAppBackgroundStateChanged(true)
        ShadowLooper.idleMainLooper(3, TimeUnit.SECONDS)
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        assertFalse(vm.isDailyLimitReachedModalOpen.value)
        assertEquals(testSong.id, vm.currentSong.value?.id)
        assertTrue(vm.isPlaying.value)
    }

    @Test
    fun freeUser_enforcesDailyLimit_andDoesNotResetBefore12Hours() {
        val prefs = app.getSharedPreferences("ak_music_user_prefs", Context.MODE_PRIVATE)
        val twoHoursAgoMs = System.currentTimeMillis() - (2L * 60L * 60L * 1000L)
        prefs.edit()
            .putString("user_name", "FreeUserOne")
            .putString("user_password", AadifySecurityVault.encryptSensitiveLocalString("pass1234"))
            .putInt("admin_user_free_limit_seconds", 1800)
            .putLong("limit_window_start_ms", twoHoursAgoMs)
            .putInt("limit_used_seconds", 1800)
            .putInt("device_hard_limit_used_seconds", 1800)
            .putInt("daily_listened_seconds", 1800)
            .putInt("user_hard_limit_used_freeuserone", 1800)
            .putLong("user_hard_window_start_freeuserone", twoHoursAgoMs)
            .commit()

        val vmReopenedAfter2Hours = MusicViewModel(app)
        assertFalse(vmReopenedAfter2Hours.isVerifiedVipActive())
        // Must STILL retain the 1800s usage and NOT reset after only 2 hours!
        assertEquals(1800, vmReopenedAfter2Hours.dailyListenedSeconds.value)

        val song = Song(
            id = "yt_kJQP7kiw5Fk",
            title = "Free Limit Track",
            artist = "Artist",
            videoId = "kJQP7kiw5Fk"
        )
        vmReopenedAfter2Hours.playSong(song, listOf(song))
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        // Playing at >= limit must stop & open daily limit modal
        assertTrue(vmReopenedAfter2Hours.isDailyLimitReachedModalOpen.value)
        assertFalse(vmReopenedAfter2Hours.isPlaying.value)
    }

    @Test
    fun freeUser_resetsStrictlyAfter12Hours() {
        val prefs = app.getSharedPreferences("ak_music_user_prefs", Context.MODE_PRIVATE)
        val thirteenHoursAgoMs = System.currentTimeMillis() - (13L * 60L * 60L * 1000L)
        prefs.edit()
            .putString("user_name", "FreeUserReset")
            .putString("user_password", AadifySecurityVault.encryptSensitiveLocalString("pass1234"))
            .putInt("admin_user_free_limit_seconds", 1800)
            .putLong("limit_window_start_ms", thirteenHoursAgoMs)
            .putInt("limit_used_seconds", 1800)
            .putInt("device_hard_limit_used_seconds", 1800)
            .putInt("daily_listened_seconds", 1800)
            .putInt("user_hard_limit_used_freeuserreset", 1800)
            .putLong("user_hard_window_start_freeuserreset", thirteenHoursAgoMs)
            .commit()

        val vmAfter12Hours = MusicViewModel(app)
        assertFalse(vmAfter12Hours.isVerifiedVipActive())
        assertEquals(0, vmAfter12Hours.dailyListenedSeconds.value)
        assertFalse(vmAfter12Hours.isDailyLimitReachedModalOpen.value)
    }

    @Test
    fun cloudVipClassification_preservesActiveOnPartialSnapshot_andDeactivatesOnlyWhenExplicit() {
        val firebaseService = FirebaseSongsService(app)
        val futureEnd = System.currentTimeMillis() + 7L * 86_400_000L

        val activeJson = JSONObject().apply {
            put(
                "subscription",
                JSONObject().apply {
                    put("status", "active")
                    put("plan", "monthly")
                    put("startAt", System.currentTimeMillis() - 1000L)
                    put("endAt", futureEnd)
                }
            )
        }
        val activeResult = firebaseService.classifyCloudSubscriptionFromJson(activeJson)
        assertTrue(activeResult is FirebaseSongsService.CloudVipSyncResult.Active)

        // Partial/missing subscription node must NEVER falsely cancel an active VIP!
        val partialResult = firebaseService.classifyCloudSubscriptionFromJson(JSONObject())
        assertTrue(partialResult is FirebaseSongsService.CloudVipSyncResult.AbsentOrUnchanged)
        assertTrue(firebaseService.classifyCloudSubscriptionFromJson(null) is FirebaseSongsService.CloudVipSyncResult.AbsentOrUnchanged)

        // Explicit deactivation by Admin must deactivate VIP
        val deactivatedJson = JSONObject().apply {
            put(
                "subscription",
                JSONObject().apply {
                    put("status", "inactive")
                    put("plan", "monthly")
                }
            )
        }
        val deactivatedResult = firebaseService.classifyCloudSubscriptionFromJson(deactivatedJson)
        assertTrue(deactivatedResult is FirebaseSongsService.CloudVipSyncResult.ExplicitlyDeactivated)
    }

    @Test
    fun audioStreamExtractor_headersAndExactCacheInvalidation() {
        val extractor = AudioStreamExtractor()
        val videoId = "dQw4w9WgXcQ"
        val streamUrl = "https://rr1---sn-abcd.googlevideo.com/videoplayback?itag=140&id=123"
        val customUa = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0.0.0 Mobile Safari/537.36"
        val cookie = "VISITOR_INFO1_LIVE=xyz; YSC=abc"

        AudioStreamExtractor.registerWebViewCapturedStream(
            videoId = videoId,
            streamUrl = streamUrl,
            userAgent = customUa,
            cookie = cookie
        )

        val cached = extractor.getCachedExactYoutubeAudioForPlayback(videoId)
        assertNotNull(cached)
        assertEquals(streamUrl, cached?.streamUrl)

        val headers = AudioStreamExtractor.resolveHeadersForStreamUrl(streamUrl)
        assertEquals(customUa, headers["User-Agent"])
        assertEquals(cookie, headers["Cookie"])
        assertEquals("https://m.youtube.com", headers["Origin"])
        assertEquals("https://m.youtube.com/", headers["Referer"])

        val androidMusicUrl = "https://rr2---sn-xyz.googlevideo.com/videoplayback?c=ANDROID_MUSIC&itag=140"
        val musicHeaders = AudioStreamExtractor.resolveHeadersForStreamUrl(androidMusicUrl)
        assertEquals(AudioStreamExtractor.ANDROID_MUSIC_USER_AGENT, musicHeaders["User-Agent"])
        assertNull(musicHeaders["Origin"])

        extractor.invalidateCachedStream(videoId)
        assertNull(extractor.getCachedExactYoutubeAudioForPlayback(videoId))
    }

    @Test
    fun musicPlaybackService_syncNowPlaying_andQueueSnapshot_noCrash() {
        val serviceController = Robolectric.buildService(
            MusicPlaybackService::class.java,
            Intent(app, MusicPlaybackService::class.java).setAction(MusicPlaybackService.ACTION_WATCH_NOTIFICATIONS)
        )
        serviceController.create().startCommand(0, 1)

        val s1 = Song(id = "s1", title = "Song One", artist = "Artist One", videoId = "dQw4w9WgXcQ")
        val s2 = Song(id = "s2", title = "Song Two", artist = "Artist Two", videoId = "9bZkp7q19f0")
        MusicPlaybackService.updateQueueStateSnapshot(
            queue = listOf(s1, s2),
            isRepeatOne = false,
            isRepeatAll = true,
            isShuffle = false,
            freeDailyLimitSeconds = 1800,
            userName = "PrimeUser"
        )
        MusicPlaybackService.syncNowPlaying(
            context = app,
            song = s1,
            isPlaying = true,
            directStreamUri = null,
            playbackSpeed = 1.0f,
            startPositionMs = 0L
        )
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        assertEquals(s1.id, MusicPlaybackService.getCurrentSyncedSong()?.id)
        assertTrue(MusicPlaybackService.isCurrentlyPlaying())
        assertFalse(MusicPlaybackService.isDirectAudioModeActive())

        MusicPlaybackService.syncPlayState(false)
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        assertFalse(MusicPlaybackService.isCurrentlyPlaying())

        serviceController.destroy()
    }
}
