package com.example

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
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
import org.junit.After
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
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackAndSubscriptionTest {

    private lateinit var app: Application
    private lateinit var viewModelStore: ViewModelStore

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        viewModelStore = ViewModelStore()
        MusicPlaybackService.resetForTests()
        app.getSharedPreferences("ak_music_user_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        ShadowLooper.idleMainLooper()
    }

    @After
    fun tearDown() {
        viewModelStore.clear()
        MusicPlaybackService.resetForTests()
        ShadowLooper.idleMainLooper()
    }

    private fun createVm(key: String = "default"): MusicViewModel {
        val factory = object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                return MusicViewModel(app) as T
            }
        }
        return ViewModelProvider(viewModelStore, factory)[key, MusicViewModel::class.java]
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

        val vm = createVm("prime_unlimited")
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
        ShadowLooper.idleMainLooper(500, TimeUnit.MILLISECONDS)

        // Must NOT trigger daily limit modal or pause for Prime user even in background!
        vm.onAppBackgroundStateChanged(true)
        ShadowLooper.idleMainLooper(3, TimeUnit.SECONDS)
        ShadowLooper.idleMainLooper(500, TimeUnit.MILLISECONDS)

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

        val vmReopenedAfter2Hours = createVm("free_limit")
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
        ShadowLooper.idleMainLooper(500, TimeUnit.MILLISECONDS)

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

        val vmAfter12Hours = createVm("free_reset")
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
        ShadowLooper.idleMainLooper(500, TimeUnit.MILLISECONDS)

        assertEquals(s1.id, MusicPlaybackService.getCurrentSyncedSong()?.id)
        assertTrue(MusicPlaybackService.isCurrentlyPlaying())
        assertFalse(MusicPlaybackService.isDirectAudioModeActive())

        MusicPlaybackService.syncPlayState(false)
        ShadowLooper.idleMainLooper(500, TimeUnit.MILLISECONDS)
        assertFalse(MusicPlaybackService.isCurrentlyPlaying())

        serviceController.destroy()
    }

    @Test
    fun musicPlaybackService_notificationPersistsAcrossTrackTransitionsAndPause() {
        val serviceController = Robolectric.buildService(
            MusicPlaybackService::class.java,
            Intent(app, MusicPlaybackService::class.java).setAction(MusicPlaybackService.ACTION_WATCH_NOTIFICATIONS)
        )
        val service = serviceController.create().get()
        serviceController.startCommand(0, 1)

        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val shadowNm = Shadows.shadowOf(nm)

        val s1 = Song(id = "n1", title = "First Song", artist = "Artist 1", videoId = "dQw4w9WgXcQ")
        val s2 = Song(id = "n2", title = "Second Song", artist = "Artist 2", videoId = "9bZkp7q19f0")

        MusicPlaybackService.syncNowPlaying(
            context = app,
            song = s1,
            isPlaying = true,
            directStreamUri = null,
            playbackSpeed = 1.0f,
            startPositionMs = 0L
        )
        ShadowLooper.idleMainLooper(500, TimeUnit.MILLISECONDS)

        var notif = shadowNm.getNotification(1001)
        assertNotNull(notif)
        assertEquals(4, notif.actions.size)
        assertEquals("Previous", notif.actions[0].title.toString())
        assertEquals("Pause", notif.actions[1].title.toString())
        assertEquals("Next", notif.actions[2].title.toString())
        assertEquals("Stop", notif.actions[3].title.toString())

        // Simulate Media3 calling onUpdateNotification(session, false) during track transition / pause
        val mediaSessionField = MusicPlaybackService::class.java.getDeclaredField("mediaSession")
        mediaSessionField.isAccessible = true
        val mediaSession = mediaSessionField.get(service) as? androidx.media3.session.MediaSession
        assertNotNull(mediaSession)
        service.onUpdateNotification(mediaSession!!, false)
        ShadowLooper.idleMainLooper(500, TimeUnit.MILLISECONDS)

        // Notification MUST stay visible and NOT hide!
        notif = shadowNm.getNotification(1001)
        assertNotNull(notif)

        // Transition to Song 2 and pause -> notification MUST stay visible with Play action & Song 2 title
        MusicPlaybackService.syncNowPlaying(
            context = app,
            song = s2,
            isPlaying = true,
            directStreamUri = null,
            playbackSpeed = 1.0f,
            startPositionMs = 0L
        )
        ShadowLooper.idleMainLooper(500, TimeUnit.MILLISECONDS)
        MusicPlaybackService.syncPlayState(false)
        ShadowLooper.idleMainLooper(500, TimeUnit.MILLISECONDS)

        notif = shadowNm.getNotification(1001)
        assertNotNull(notif)
        assertEquals("Second Song", notif.extras.getString(android.app.Notification.EXTRA_TITLE))
        assertEquals("Play", notif.actions[1].title.toString())

        serviceController.destroy()
    }

    @Test
    fun musicViewModel_preCachedTrackTransitionsImmediatelyWithoutSilentReset() {
        seedVipUser(userName = "FastSwitchVip", planType = SubscriptionPlanType.MONTHLY)
        val serviceController = Robolectric.buildService(
            MusicPlaybackService::class.java,
            Intent(app, MusicPlaybackService::class.java).setAction(MusicPlaybackService.ACTION_WATCH_NOTIFICATIONS)
        )
        serviceController.create().startCommand(0, 1)

        val s1 = Song(id = "fast_1", title = "Romantic Track One", artist = "Arijit Singh", videoId = "dQw4w9WgXcQ")
        val s2 = Song(id = "fast_2", title = "Romantic Track Two", artist = "Arijit Singh", videoId = "9bZkp7q19f0")

        // Pre-seed exact direct audio stream in cache for s2 (simulating background pre-fetch)
        AudioStreamExtractor.registerWebViewCapturedStream(
            videoId = "9bZkp7q19f0",
            streamUrl = "https://rr1---sn-fast.googlevideo.com/videoplayback?itag=140&id=fast2",
            userAgent = AudioStreamExtractor.EXOPLAYER_USER_AGENT
        )

        val vm = createVm("fast_switch")
        val predicted = vm.computeNextAutoTrack(s1, listOf(s1, s2))
        assertEquals(s2.id, predicted.id)

        // Playing predicted track with pre-cached stream immediately switches directly to ExoPlayer
        vm.playSong(predicted, listOf(s1, s2))
        ShadowLooper.idleMainLooper(500, TimeUnit.MILLISECONDS)

        assertEquals(s2.id, vm.currentSong.value?.id)
        assertTrue(vm.isPlaying.value)
        assertEquals("ExoPlayer Native Audio", vm.activeStreamEngine.value)
        assertTrue(MusicPlaybackService.isDirectAudioModeActive())

        serviceController.destroy()
    }

    @Test
    fun musicPlaybackService_notificationActionButtons_workAndKeepNotificationPermanent() {
        val serviceController = Robolectric.buildService(
            MusicPlaybackService::class.java,
            Intent(app, MusicPlaybackService::class.java).setAction(MusicPlaybackService.ACTION_WATCH_NOTIFICATIONS)
        )
        val service = serviceController.create().get()
        serviceController.startCommand(0, 1)

        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val shadowNm = Shadows.shadowOf(nm)

        val s1 = Song(id = "btn_1", title = "Track Alpha", artist = "Artist A", videoId = "dQw4w9WgXcQ")
        var nextClicks = 0
        var prevClicks = 0
        var lastPlayToggle: Boolean? = null
        MusicPlaybackService.onSkipNextCallback = { nextClicks++ }
        MusicPlaybackService.onSkipPreviousCallback = { prevClicks++ }
        MusicPlaybackService.onTogglePlayPauseCallback = { playing -> lastPlayToggle = playing }

        MusicPlaybackService.syncNowPlaying(
            context = app,
            song = s1,
            isPlaying = true,
            directStreamUri = null,
            playbackSpeed = 1.0f,
            startPositionMs = 0L
        )
        ShadowLooper.idleMainLooper(500, TimeUnit.MILLISECONDS)

        service.onStartCommand(Intent(app, MusicPlaybackService::class.java).setAction(MusicPlaybackService.ACTION_NOTIFICATION_NEXT), 0, 2)
        service.onStartCommand(Intent(app, MusicPlaybackService::class.java).setAction(MusicPlaybackService.ACTION_NOTIFICATION_PREV), 0, 3)
        service.onStartCommand(Intent(app, MusicPlaybackService::class.java).setAction(MusicPlaybackService.ACTION_NOTIFICATION_PLAY_PAUSE), 0, 4)
        ShadowLooper.idleMainLooper(500, TimeUnit.MILLISECONDS)

        assertEquals(1, nextClicks)
        assertEquals(1, prevClicks)
        assertEquals(false, lastPlayToggle)

        val notif = shadowNm.getNotification(1001)
        assertNotNull(notif)
        assertEquals("Play", notif.actions[1].title.toString())

        MusicPlaybackService.onSkipNextCallback = null
        MusicPlaybackService.onSkipPreviousCallback = null
        MusicPlaybackService.onTogglePlayPauseCallback = null
        serviceController.destroy()
    }

    @Test
    fun freeUser_triggersYoutubeAdAfter3Songs_pausesMusic_andAutoPlaysNextOnCompletion() {
        val prefs = app.getSharedPreferences("ak_music_user_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("user_name", "FreeAdUser")
            .putString("user_password", AadifySecurityVault.encryptSensitiveLocalString("pass1234"))
            .commit()

        assertEquals("fWvlsYrWIs4", AadifySecurityVault.getPromoAdVideoId())

        val vm = createVm("free_ad_cycle")
        assertFalse(vm.isVerifiedVipActive())

        val s1 = Song(id = "ad_s1", title = "Track 1", artist = "Artist 1", videoId = "dQw4w9WgXcQ")
        val s2 = Song(id = "ad_s2", title = "Track 2", artist = "Artist 2", videoId = "9bZkp7q19f0")
        val s3 = Song(id = "ad_s3", title = "Track 3", artist = "Artist 3", videoId = "kJQP7kiw5Fk")
        val s4 = Song(id = "ad_s4", title = "Track 4", artist = "Artist 4", videoId = "RgKAFK5djSk")
        val queue = listOf(s1, s2, s3, s4)

        // Song 1, 2, 3 play without Ad
        vm.playSong(s1, queue)
        ShadowLooper.idleMainLooper(200, TimeUnit.MILLISECONDS)
        assertFalse(vm.isVideoAdPlaying.value)
        assertEquals(s1.id, vm.currentSong.value?.id)

        vm.playSong(s2, queue)
        ShadowLooper.idleMainLooper(200, TimeUnit.MILLISECONDS)
        assertFalse(vm.isVideoAdPlaying.value)
        assertEquals(s2.id, vm.currentSong.value?.id)

        vm.playSong(s3, queue)
        ShadowLooper.idleMainLooper(200, TimeUnit.MILLISECONDS)
        assertFalse(vm.isVideoAdPlaying.value)
        assertEquals(s3.id, vm.currentSong.value?.id)

        // Transitioning after 3rd song triggers the Ad and pauses music playback
        vm.playSong(s4, queue)
        ShadowLooper.idleMainLooper(200, TimeUnit.MILLISECONDS)
        assertTrue(vm.isVideoAdPlaying.value)
        assertFalse(vm.isPlaying.value)

        // Cannot resume song playback while Ad is active
        vm.togglePlayPause()
        assertFalse(vm.isPlaying.value)

        // When Ad finishes, Ad closes and next song (s4) plays automatically
        vm.onVideoAdCompleted()
        ShadowLooper.idleMainLooper(200, TimeUnit.MILLISECONDS)
        assertFalse(vm.isVideoAdPlaying.value)
        assertEquals(s4.id, vm.currentSong.value?.id)
        assertTrue(vm.isPlaying.value)
    }

    @Test
    fun vipUser_neverSeesAdsAfter3OrMoreSongs() {
        seedVipUser(userName = "VipNoAdUser", planType = SubscriptionPlanType.MONTHLY)
        val vm = createVm("vip_no_ad")
        assertTrue(vm.isVerifiedVipActive())

        val s1 = Song(id = "v1", title = "VIP 1", artist = "A1", videoId = "dQw4w9WgXcQ")
        val s2 = Song(id = "v2", title = "VIP 2", artist = "A2", videoId = "9bZkp7q19f0")
        val s3 = Song(id = "v3", title = "VIP 3", artist = "A3", videoId = "kJQP7kiw5Fk")
        val s4 = Song(id = "v4", title = "VIP 4", artist = "A4", videoId = "RgKAFK5djSk")
        val s5 = Song(id = "v5", title = "VIP 5", artist = "A5", videoId = "fJ9rUzIMcZQ")
        val queue = listOf(s1, s2, s3, s4, s5)

        for (song in queue) {
            vm.playSong(song, queue)
            ShadowLooper.idleMainLooper(200, TimeUnit.MILLISECONDS)
            assertFalse(vm.isVideoAdPlaying.value)
            assertEquals(song.id, vm.currentSong.value?.id)
            assertTrue(vm.isPlaying.value)
        }
    }

    @Test
    fun logoutUser_clearsLocalSessionAndResetsUserName() {
        seedVipUser(userName = "SessionUser", planType = SubscriptionPlanType.WEEKLY)
        val vm = createVm("logout_test")
        assertEquals("SessionUser", vm.userName.value)
        assertTrue(vm.subscriptionState.value.isActive())

        val song = Song(id = "log_s1", title = "Playing Track", artist = "Artist", videoId = "dQw4w9WgXcQ")
        vm.playSong(song, listOf(song))
        ShadowLooper.idleMainLooper(200, TimeUnit.MILLISECONDS)
        assertTrue(vm.isPlaying.value)

        vm.logoutUser()
        ShadowLooper.idleMainLooper(200, TimeUnit.MILLISECONDS)

        assertEquals("", vm.userName.value)
        assertEquals("", vm.userPassword.value)
        assertFalse(vm.isPlaying.value)
        assertNull(vm.currentSong.value)
        assertFalse(vm.subscriptionState.value.isActive())

        val prefs = app.getSharedPreferences("ak_music_user_prefs", Context.MODE_PRIVATE)
        assertEquals("", prefs.getString("user_name", ""))
        assertEquals("", prefs.getString("user_password", ""))
    }
}
