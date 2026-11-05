package com.example.services

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.unity3d.ads.IUnityAdsInitializationListener
import com.unity3d.ads.IUnityAdsLoadListener
import com.unity3d.ads.IUnityAdsShowListener
import com.unity3d.ads.UnityAds
import com.unity3d.ads.UnityAdsShowOptions
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Official Unity Ads Manager for AADIFY
 *
 * Unity Game ID:           800424673
 * Interstitial Ad Unit ID: BP_Interstitial_Android
 *
 * Strictly configured to show ONLY ONCE at app startup (right after the loading screen,
 * before the Continue button screen / entering the main app) and NEVER inside the app.
 */
object UnityAdsManager {

    private const val TAG = "UnityAdsManager"

    const val UNITY_GAME_ID = "800424673"
    const val INTERSTITIAL_AD_UNIT_ID = "BP_Interstitial_Android"

    private val PLACEMENT_CANDIDATES = listOf(
        "BP_Interstitial_Android",
        "Interstitial_Android",
        "800424673"
    )
    private const val TEST_MODE = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val isInitializing = AtomicBoolean(false)

    @Volatile
    private var currentActivityRef: WeakReference<Activity>? = null

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

    fun attachActivity(activity: Activity) {
        currentActivityRef = WeakReference(activity)
    }

    private fun resolveActivity(contextOrActivity: Context?): Activity? {
        var ctx = contextOrActivity
        while (ctx != null) {
            if (ctx is Activity) {
                if (!ctx.isFinishing && !ctx.isDestroyed) {
                    return ctx
                }
                break
            }
            ctx = (ctx as? ContextWrapper)?.baseContext
        }
        val cached = currentActivityRef?.get()
        if (cached != null && !cached.isFinishing && !cached.isDestroyed) {
            return cached
        }
        return null
    }

    fun hasShownStartupAd(): Boolean = hasShownStartupAdThisSession

    fun isAdReadyNow(): Boolean = isAdLoaded && !hasShownStartupAdThisSession && !isShowingAd

    /**
     * Initializes the Unity Ads SDK with Game ID 800424673 and immediately preloads
     * the Interstitial Ad Unit BP_Interstitial_Android during the app's startup loading screen.
     */
    fun initializeAndPreload(context: Context) {
        resolveActivity(context)?.let { attachActivity(it) }
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
            loadPlacementCandidateAtIndex(0)
        }
    }

    private fun loadPlacementCandidateAtIndex(index: Int) {
        if (index >= PLACEMENT_CANDIDATES.size) {
            isLoadingAd = false
            isAdLoaded = false
            notifyPendingWaiterIfAny()
            return
        }
        val placementId = PLACEMENT_CANDIDATES[index]
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
                        loadPlacementCandidateAtIndex(index + 1)
                    }
                }
            )
        } catch (t: Throwable) {
            loadPlacementCandidateAtIndex(index + 1)
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
     * Called right after the startup loading screen finishes.
     * Waits up to [maxWaitMs] for BP_Interstitial_Android to finish loading if it is still loading,
     * shows the ad once, and invokes [onComplete] as soon as the ad is closed.
     */
    fun showStartupInterstitialAfterLoading(
        context: Context?,
        maxWaitMs: Long = 3600L,
        onComplete: () -> Unit
    ) {
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

        val activity = resolveActivity(context)
        if (activity == null) {
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

            // Ensure initialization & preload are running while waiting briefly at end of loading screen
            initializeAndPreload(activity)

            val timeoutRunnable = Runnable {
                val act = resolveActivity(activity)
                if (isAdLoaded && !hasShownStartupAdThisSession && act != null) {
                    displayUnityInterstitialNow(act, finishOnce)
                } else {
                    finishOnce()
                }
            }

            pendingReadyCallback = {
                mainHandler.removeCallbacks(timeoutRunnable)
                val act = resolveActivity(activity)
                if (isAdLoaded && !hasShownStartupAdThisSession && act != null) {
                    displayUnityInterstitialNow(act, finishOnce)
                } else {
                    finishOnce()
                }
            }

            mainHandler.postDelayed(timeoutRunnable, maxWaitMs)
        }
    }

    /**
     * Called when the user clicks the "Continue" button on the intro screen.
     * NEVER blocks or delays the Continue button:
     * - If the ad finished loading late and is ready right now, shows it immediately (0ms delay).
     * - Otherwise proceeds to the Main App in 0ms so the Continue button always feels instant!
     */
    fun showIfLoadedNowOrProceedImmediately(context: Context?, onComplete: () -> Unit) {
        val act = resolveActivity(context)
        if (!hasShownStartupAdThisSession && isAdLoaded && !isShowingAd && act != null) {
            val completed = AtomicBoolean(false)
            displayUnityInterstitialNow(act) {
                if (completed.compareAndSet(false, true)) {
                    mainHandler.post { onComplete() }
                }
            }
        } else {
            hasShownStartupAdThisSession = true
            onComplete()
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
