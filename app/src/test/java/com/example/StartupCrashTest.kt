package com.example

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.providers.MusicViewModel
import com.example.security.AadifySecurityVault
import com.example.services.MusicPlaybackService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StartupCrashTest {

    @Test
    fun testApplicationAndMainActivityFreshLaunch_noCrash() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertTrue(AadifySecurityVault.getFirebaseDatabaseUrl().startsWith("https://"))
        assertTrue(AadifySecurityVault.getFirebaseProjectId().isNotBlank())

        val vm = MusicViewModel(app)
        assertEquals(MusicViewModel.CURRENT_APP_UPDATE_SECRET_CODE, vm.userSecretNumber.value)
        assertEquals(12L * 60L * 60L * 1000L, MusicViewModel.LIMIT_RESET_WINDOW_MS)
        assertFalse(vm.isVerifiedVipActive())

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        controller.pause().resume()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        controller.pause().stop().destroy()
    }

    @Test
    fun testReturningFreeUserLaunchThroughHome_noCrash() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefs = app.getSharedPreferences("ak_music_user_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .clear()
            .putString("user_name", "FreeTester")
            .putString("user_password", AadifySecurityVault.encryptSensitiveLocalString("pass1234"))
            .commit()

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        // Advance through SplashScreen (2200ms) -> DeveloperIntro -> HomeScreen + onHomeScreenEntered
        ShadowLooper.idleMainLooper(3, java.util.concurrent.TimeUnit.SECONDS)
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        controller.pause().stop().destroy()
    }

    @Test
    fun testReturningVipUserLaunch_noCrash() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefs = app.getSharedPreferences("ak_music_user_prefs", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val expires = now + 7L * 86_400_000L
        val seal = AadifySecurityVault.computeSubscriptionIntegritySeal(
            planId = "weekly",
            activatedAtMs = now,
            expiresAtMs = expires,
            orderId = "ord_123",
            utr = "utr_123"
        )
        prefs.edit()
            .clear()
            .putString("user_name", "Aadarsh")
            .putString("user_password", AadifySecurityVault.encryptSensitiveLocalString("pass1234"))
            .putString("sub_plan_id", "weekly")
            .putLong("sub_activated_at_ms", now)
            .putLong("sub_expires_at_ms", expires)
            .putString("sub_order_id", "ord_123")
            .putString("sub_utr", "utr_123")
            .putString("sub_vault_seal_v6", seal)
            .commit()

        val vm = MusicViewModel(app)
        assertTrue(vm.isVerifiedVipActive())

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        ShadowLooper.idleMainLooper(3, java.util.concurrent.TimeUnit.SECONDS)
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        controller.pause().stop().destroy()
    }

    @Test
    fun testMusicPlaybackServiceStart_noCrash() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val serviceController = Robolectric.buildService(
            MusicPlaybackService::class.java,
            Intent(app, MusicPlaybackService::class.java).setAction(MusicPlaybackService.ACTION_WATCH_NOTIFICATIONS)
        )
        serviceController.create().startCommand(0, 1)
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        serviceController.destroy()
    }
}
