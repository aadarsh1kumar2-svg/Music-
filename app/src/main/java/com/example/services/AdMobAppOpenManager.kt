package com.example.services

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.appopen.AppOpenAd
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Official Google AdMob App Open Ad Manager
 *
 * App ID:     ca-app-pub-7121400650721795~5863033828
 * Ad Unit ID: ca-app-pub-7121400650721795/5753468595
 */
object AdMobAppOpenManager {

    private const val TAG = "AdMobAppOpenManager"
    const val ADMOB_APP_ID = "ca-app-pub-7121400650721795~5863033828"
    const val APP_OPEN_AD_UNIT_ID = "ca-app-pub-7121400650721795/5753468595"

    // AdMob App Open Ads expire after 4 hours
    private const val AD_EXPIRATION_MS = 4L * 60L * 60L * 1000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val isSdkInitialized = AtomicBoolean(false)

    @Volatile
    private var appOpenAd: AppOpenAd? = null

    @Volatile
    private var isLoadingAd: Boolean = false

    @Volatile
    private var isShowingAd: Boolean = false

    @Volatile
    private var loadTimeElapsedRealtimeMs: Long = 0L

    @Volatile
    private var pendingShowCallback: (() -> Unit)? = null

    fun initializeAndPreload(context: Context) {
        val appContext = context.applicationContext
        mainHandler.post {
            try {
                if (isSdkInitialized.compareAndSet(false, true)) {
                    MobileAds.initialize(appContext) {
                        preloadAppOpenAd(appContext)
                    }
                } else {
                    preloadAppOpenAd(appContext)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "AdMob initialization error: ${t.message}")
            }
        }
    }

    fun preloadAppOpenAd(context: Context) {
        val appContext = context.applicationContext
        mainHandler.post {
            if (isLoadingAd || isAdAvailable()) {
                return@post
            }
            isLoadingAd = true
            try {
                val request = AdRequest.Builder().build()
                AppOpenAd.load(
                    appContext,
                    APP_OPEN_AD_UNIT_ID,
                    request,
                    object : AppOpenAd.AppOpenAdLoadCallback() {
                        override fun onAdLoaded(ad: AppOpenAd) {
                            appOpenAd = ad
                            isLoadingAd = false
                            loadTimeElapsedRealtimeMs = SystemClock.elapsedRealtime()
                            Log.d(TAG, "AdMob App Open Ad loaded successfully.")
                            val waitingCallback = pendingShowCallback
                            if (waitingCallback != null) {
                                pendingShowCallback = null
                                waitingCallback.invoke()
                            }
                        }

                        override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                            isLoadingAd = false
                            appOpenAd = null
                            Log.w(TAG, "AdMob App Open Ad failed to load: ${loadAdError.message}")
                            val waitingCallback = pendingShowCallback
                            if (waitingCallback != null) {
                                pendingShowCallback = null
                                waitingCallback.invoke()
                            }
                        }
                    }
                )
            } catch (t: Throwable) {
                isLoadingAd = false
                val waitingCallback = pendingShowCallback
                if (waitingCallback != null) {
                    pendingShowCallback = null
                    waitingCallback.invoke()
                }
            }
        }
    }

    private fun isAdAvailable(): Boolean {
        val ad = appOpenAd ?: return false
        val ageMs = SystemClock.elapsedRealtime() - loadTimeElapsedRealtimeMs
        return ageMs in 0 until AD_EXPIRATION_MS && ad.responseInfo != null
    }

    /**
     * Shows the Google AdMob App Open Ad when the user opens the app and clicks Continue.
     * - If the ad is already preloaded, shows it immediately with AdMob's built-in "Continue to app" / Close UI.
     * - If still loading, waits up to 2.5s for it to finish loading, then shows it or proceeds smoothly to the app.
     */
    fun showAdOnAppOpen(activity: Activity?, onComplete: () -> Unit) {
        val hasCompleted = AtomicBoolean(false)
        val finishOnce = {
            if (hasCompleted.compareAndSet(false, true)) {
                pendingShowCallback = null
                mainHandler.post {
                    onComplete()
                }
            }
        }

        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            finishOnce()
            return
        }

        mainHandler.post {
            if (isShowingAd) {
                finishOnce()
                return@post
            }

            if (isAdAvailable()) {
                displayLoadedAdNow(activity, finishOnce)
                return@post
            }

            // If ad is currently loading, wait briefly (max 2.2s) so it can display if ready
            preloadAppOpenAd(activity.applicationContext)
            val timeoutRunnable = Runnable {
                if (isAdAvailable() && !activity.isFinishing && !activity.isDestroyed) {
                    displayLoadedAdNow(activity, finishOnce)
                } else {
                    finishOnce()
                }
            }
            pendingShowCallback = {
                mainHandler.removeCallbacks(timeoutRunnable)
                if (isAdAvailable() && !activity.isFinishing && !activity.isDestroyed) {
                    displayLoadedAdNow(activity, finishOnce)
                } else {
                    finishOnce()
                }
            }
            mainHandler.postDelayed(timeoutRunnable, 2200L)
        }
    }

    private fun displayLoadedAdNow(activity: Activity, onCompleteOnce: () -> Unit) {
        val ad = appOpenAd
        if (ad == null || activity.isFinishing || activity.isDestroyed) {
            onCompleteOnce()
            return
        }
        try {
            // If music is already playing, keep AdMob muted so it never interrupts music
            if (MusicPlaybackService.isCurrentlyPlaying()) {
                try {
                    MobileAds.setAppMuted(true)
                    MobileAds.setAppVolume(0f)
                } catch (_: Throwable) {
                }
            }

            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    appOpenAd = null
                    isShowingAd = false
                    preloadAppOpenAd(activity.applicationContext)
                    onCompleteOnce()
                }

                override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                    appOpenAd = null
                    isShowingAd = false
                    Log.w(TAG, "AdMob App Open Ad failed to show: ${adError.message}")
                    preloadAppOpenAd(activity.applicationContext)
                    onCompleteOnce()
                }

                override fun onAdShowedFullScreenContent() {
                    isShowingAd = true
                }
            }

            isShowingAd = true
            ad.show(activity)
        } catch (t: Throwable) {
            appOpenAd = null
            isShowingAd = false
            onCompleteOnce()
        }
    }
}
