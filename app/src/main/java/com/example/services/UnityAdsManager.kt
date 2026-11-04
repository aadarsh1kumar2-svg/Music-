package com.example.services

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.unity3d.ads.IUnityAdsInitializationListener
import com.unity3d.ads.IUnityAdsLoadListener
import com.unity3d.ads.IUnityAdsShowListener
import com.unity3d.ads.UnityAds
import com.unity3d.ads.UnityAdsShowOptions
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Official Unity Ads Manager for AADIFY
 *
 * Unity Game ID:           800424673
 * Interstitial Ad Unit ID: 800424673
 *
 * Strictly configured to show ONLY ONCE at app startup (right after the loading screen,
 * before the Continue screen / entering the main app) and NEVER inside the app during music playback.
 */
object UnityAdsManager {

    private const val TAG = "UnityAdsManager"

    const val UNITY_GAME_ID = "800424673"
    const val INTERSTITIAL_AD_UNIT_ID = "800424673"
    private const val FALLBACK_INTERSTITIAL_PLACEMENT_ID = "Interstitial_Android"
    private const val TEST_MODE = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val isInitializing = AtomicBoolean(false)

    @Volatile
    private var isAdLoaded: Boolean = false

    @Volatile
    private var isLoadingAd: Boolean = false

    @Volatile
    private var isShowingAd: Boolean = false

    @Volatile
    private var loadedPlacementId: String = INTERSTITIAL_AD_UNIT_ID

    @Volatile
    private var hasShownStartupAdThisSession: Boolean = false

    @Volatile
    private var pendingReadyCallback: (() -> Unit)? = null

    fun hasShownStartupAd(): Boolean = hasShownStartupAdThisSession

    /**
     * Initializes the Unity Ads SDK with Game ID 800424673 and immediately preloads
     * the Interstitial Ad Unit 800424673 during the app's startup loading screen.
     */
    fun initializeAndPreload(context: Context) {
        if (hasShownStartupAdThisSession) return
        val appContext = context.applicationContext
        mainHandler.post {
            try {
                if (UnityAds.isInitialized) {
                    preloadInterstitialAd()
                    return@post
                }
                if (!isInitializing.compareAndSet(false, true)) {
                    return@post
                }
                UnityAds.initialize(
                    appContext,
                    UNITY_GAME_ID,
                    TEST_MODE,
                    object : IUnityAdsInitializationListener {
                        override fun onInitializationComplete() {
                            isInitializing.set(false)
                            Log.d(TAG, "Unity Ads initialized successfully with Game ID: $UNITY_GAME_ID")
                            preloadInterstitialAd()
                        }

                        override fun onInitializationFailed(
                            error: UnityAds.UnityAdsInitializationError?,
                            message: String?
                        ) {
                            isInitializing.set(false)
                            Log.w(TAG, "Unity Ads initialization failed: $error - $message")
                            notifyPendingWaiterIfAny()
                        }
                    }
                )
            } catch (t: Throwable) {
                isInitializing.set(false)
                Log.w(TAG, "Unity Ads initialize exception: ${t.message}")
                notifyPendingWaiterIfAny()
            }
        }
    }

    fun preloadInterstitialAd() {
        if (hasShownStartupAdThisSession || isAdLoaded || isLoadingAd) return
        if (!UnityAds.isInitialized) return

        mainHandler.post {
            if (hasShownStartupAdThisSession || isAdLoaded || isLoadingAd) return@post
            isLoadingAd = true
            loadPlacementWithFallback(INTERSTITIAL_AD_UNIT_ID, allowFallback = true)
        }
    }

    private fun loadPlacementWithFallback(placementId: String, allowFallback: Boolean) {
        try {
            UnityAds.load(
                placementId,
                object : IUnityAdsLoadListener {
                    override fun onUnityAdsAdLoaded(loadedId: String?) {
                        isLoadingAd = false
                        isAdLoaded = true
                        loadedPlacementId = loadedId?.takeIf { it.isNotBlank() } ?: placementId
                        Log.d(TAG, "Unity Interstitial Ad loaded for placement: $loadedPlacementId")
                        notifyPendingWaiterIfAny()
                    }

                    override fun onUnityAdsFailedToLoad(
                        failedId: String?,
                        error: UnityAds.UnityAdsLoadError?,
                        message: String?
                    ) {
                        Log.w(TAG, "Unity Interstitial Ad failed to load ($failedId): $error - $message")
                        if (allowFallback && placementId != FALLBACK_INTERSTITIAL_PLACEMENT_ID) {
                            loadPlacementWithFallback(FALLBACK_INTERSTITIAL_PLACEMENT_ID, allowFallback = false)
                        } else {
                            isLoadingAd = false
                            isAdLoaded = false
                            notifyPendingWaiterIfAny()
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            isLoadingAd = false
            isAdLoaded = false
            notifyPendingWaiterIfAny()
        }
    }

    private fun notifyPendingWaiterIfAny() {
        val cb = pendingReadyCallback
        if (cb != null) {
            pendingReadyCallback = null
            mainHandler.post { cb.invoke() }
        }
    }

    /**
     * Shows the Unity Interstitial Ad ONCE right after the startup loading screen completes
     * (before the Continue screen / entering the main app).
     * Never shows again inside the app after startup.
     */
    fun showStartupInterstitialAd(activity: Activity?, onComplete: () -> Unit) {
        val completed = AtomicBoolean(false)
        val finishOnce = {
            if (completed.compareAndSet(false, true)) {
                pendingReadyCallback = null
                mainHandler.post {
                    onComplete()
                }
            }
        }

        if (hasShownStartupAdThisSession) {
            finishOnce()
            return
        }

        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            finishOnce()
            return
        }

        mainHandler.post {
            if (hasShownStartupAdThisSession || isShowingAd) {
                finishOnce()
                return@post
            }

            if (isAdLoaded) {
                displayUnityInterstitialNow(activity, finishOnce)
                return@post
            }

            // Ensure initialization & preload are running and wait briefly (up to 2.5s) after loading screen
            initializeAndPreload(activity.applicationContext)

            val timeoutRunnable = Runnable {
                if (isAdLoaded && !hasShownStartupAdThisSession && !activity.isFinishing && !activity.isDestroyed) {
                    displayUnityInterstitialNow(activity, finishOnce)
                } else {
                    finishOnce()
                }
            }

            pendingReadyCallback = {
                mainHandler.removeCallbacks(timeoutRunnable)
                if (isAdLoaded && !hasShownStartupAdThisSession && !activity.isFinishing && !activity.isDestroyed) {
                    displayUnityInterstitialNow(activity, finishOnce)
                } else {
                    finishOnce()
                }
            }

            mainHandler.postDelayed(timeoutRunnable, 2500L)
        }
    }

    private fun displayUnityInterstitialNow(activity: Activity, onCompleteOnce: () -> Unit) {
        if (hasShownStartupAdThisSession || !isAdLoaded || activity.isFinishing || activity.isDestroyed) {
            onCompleteOnce()
            return
        }
        try {
            isShowingAd = true
            isAdLoaded = false
            hasShownStartupAdThisSession = true
            val placementToShow = loadedPlacementId.ifBlank { INTERSTITIAL_AD_UNIT_ID }

            UnityAds.show(
                activity,
                placementToShow,
                UnityAdsShowOptions(),
                object : IUnityAdsShowListener {
                    override fun onUnityAdsShowStart(placementId: String?) {
                        Log.d(TAG, "Unity Interstitial Ad started: $placementId")
                    }

                    override fun onUnityAdsShowClick(placementId: String?) {
                        Log.d(TAG, "Unity Interstitial Ad clicked: $placementId")
                    }

                    override fun onUnityAdsShowComplete(
                        placementId: String?,
                        state: UnityAds.UnityAdsShowCompletionState?
                    ) {
                        Log.d(TAG, "Unity Interstitial Ad completed: $placementId ($state)")
                        isShowingAd = false
                        onCompleteOnce()
                    }

                    override fun onUnityAdsShowFailure(
                        placementId: String?,
                        error: UnityAds.UnityAdsShowError?,
                        message: String?
                    ) {
                        Log.w(TAG, "Unity Interstitial Ad show failed ($placementId): $error - $message")
                        isShowingAd = false
                        onCompleteOnce()
                    }
                }
            )
        } catch (t: Throwable) {
            isShowingAd = false
            onCompleteOnce()
        }
    }
}
