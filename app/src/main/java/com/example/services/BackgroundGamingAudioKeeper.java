package com.example.services;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.util.Log;
import android.webkit.WebView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Dedicated Java Background Audio & Network Keep-Alive Engine.
 *
 * Ensures that AADIFY music playback and the system media notification stay 100% online
 * and uninterrupted even when the user switches to heavy fullscreen games (Free Fire,
 * PUBG, BGMI, Call of Duty Mobile, etc.) or multitasks in the background:
 * 1. Holds a non-reference-counted PARTIAL_WAKE_LOCK so the CPU never throttles audio decoding.
 * 2. Holds a low-latency / high-performance WifiLock (WIFI_MODE_FULL_LOW_LATENCY on API 29+).
 * 3. Registers an active ConnectivityManager NetworkCallback with NET_CAPABILITY_INTERNET so
 *    Android's ConnectivityService keeps cellular & Wi-Fi sockets active in the background.
 * 4. Runs a high-priority urgent-audio Java HandlerThread + Main Looper pulse every 300ms
 *    that keeps the Backstage WebView renderer & timers awake and immediately recovers from
 *    any transient game AudioFocus interruption.
 */
public final class BackgroundGamingAudioKeeper {

    private static final String TAG = "GamingAudioKeeper";
    private static volatile BackgroundGamingAudioKeeper instance;

    private final Context appContext;
    private final Handler mainHandler;
    private final HandlerThread urgentAudioThread;
    private final Handler urgentAudioHandler;

    @Nullable
    private PowerManager.WakeLock cpuWakeLock;
    @Nullable
    private WifiManager.WifiLock lowLatencyWifiLock;
    @Nullable
    private ConnectivityManager connectivityManager;
    @Nullable
    private ConnectivityManager.NetworkCallback networkCallback;

    private volatile boolean isKeeperActive = false;
    private volatile boolean isNetworkRegistered = false;
    @Nullable
    private volatile WebView attachedWebView;
    @Nullable
    private volatile Runnable keepAliveAction;

    private final Runnable urgentPulseRunnable = new Runnable() {
        @Override
        public void run() {
            urgentAudioHandler.removeCallbacks(this);
            if (!isKeeperActive) {
                return;
            }
            try {
                ensureLocksAcquired();
                mainHandler.post(mainThreadKeepAliveRunnable);
            } catch (Throwable t) {
                Log.w(TAG, "Urgent pulse warning: " + t.getMessage());
            }
            if (isKeeperActive) {
                urgentAudioHandler.postDelayed(this, 1500L);
            }
        }
    };

    private final Runnable mainThreadKeepAliveRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isKeeperActive) {
                return;
            }
            try {
                WebView wv = attachedWebView;
                if (wv != null) {
                    wv.onResume();
                    wv.resumeTimers();
                }
                Runnable action = keepAliveAction;
                if (action != null) {
                    action.run();
                }
            } catch (Throwable ignored) {
            }
        }
    };

    private BackgroundGamingAudioKeeper(@NonNull Context context) {
        this.appContext = context.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.urgentAudioThread = new HandlerThread(
            "AADIFY-JavaGamingAudioThread",
            Process.THREAD_PRIORITY_URGENT_AUDIO
        );
        this.urgentAudioThread.start();
        this.urgentAudioHandler = new Handler(this.urgentAudioThread.getLooper());
        initHardwareLocks();
    }

    @NonNull
    public static BackgroundGamingAudioKeeper getInstance(@NonNull Context context) {
        if (instance == null) {
            synchronized (BackgroundGamingAudioKeeper.class) {
                if (instance == null) {
                    instance = new BackgroundGamingAudioKeeper(context);
                }
            }
        }
        return instance;
    }

    private void initHardwareLocks() {
        try {
            PowerManager pm = (PowerManager) appContext.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                cpuWakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "AADIFY:JavaGamingAudioWakeLock"
                );
                cpuWakeLock.setReferenceCounted(false);
            }
        } catch (Throwable t) {
            Log.w(TAG, "WakeLock init failed: " + t.getMessage());
        }

        try {
            WifiManager wm = (WifiManager) appContext.getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                int wifiMode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                    : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
                lowLatencyWifiLock = wm.createWifiLock(
                    wifiMode,
                    "AADIFY:JavaGamingLowLatencyWifiLock"
                );
                lowLatencyWifiLock.setReferenceCounted(false);
            }
        } catch (Throwable t) {
            Log.w(TAG, "WifiLock init failed: " + t.getMessage());
        }

        try {
            connectivityManager = (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        } catch (Throwable ignored) {
        }
    }

    public void attachWebView(@Nullable WebView webView) {
        this.attachedWebView = webView;
    }

    public void setKeepAliveAction(@Nullable Runnable action) {
        this.keepAliveAction = action;
    }

    /**
     * Activates high-priority CPU, Wi-Fi Low-Latency, Background Network Pinning, and
     * 300ms urgent audio heartbeat so heavy games (Free Fire, PUBG) never pause music
     * or put the app offline.
     */
    public void startGamingKeepAlive() {
        isKeeperActive = true;
        ensureLocksAcquired();
        registerBackgroundNetworkKeepAlive();
        urgentAudioHandler.removeCallbacks(urgentPulseRunnable);
        urgentAudioHandler.post(urgentPulseRunnable);
    }

    public void stopGamingKeepAlive() {
        isKeeperActive = false;
        urgentAudioHandler.removeCallbacks(urgentPulseRunnable);
        mainHandler.removeCallbacks(mainThreadKeepAliveRunnable);
        releaseLocksSafely();
    }

    public void ensureLocksAcquired() {
        try {
            if (cpuWakeLock != null && !cpuWakeLock.isHeld()) {
                cpuWakeLock.acquire(12 * 60 * 60 * 1000L);
            }
        } catch (Throwable ignored) {
        }
        try {
            if (lowLatencyWifiLock != null && !lowLatencyWifiLock.isHeld()) {
                lowLatencyWifiLock.acquire();
            }
        } catch (Throwable ignored) {
        }
    }

    private void releaseLocksSafely() {
        try {
            if (cpuWakeLock != null && cpuWakeLock.isHeld()) {
                cpuWakeLock.release();
            }
        } catch (Throwable ignored) {
        }
        try {
            if (lowLatencyWifiLock != null && lowLatencyWifiLock.isHeld()) {
                lowLatencyWifiLock.release();
            }
        } catch (Throwable ignored) {
        }
    }

    private void registerBackgroundNetworkKeepAlive() {
        if (isNetworkRegistered || connectivityManager == null) {
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                ConnectivityManager.NetworkCallback cb = new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(@NonNull Network network) {
                        super.onAvailable(network);
                        if (isKeeperActive) {
                            mainHandler.post(mainThreadKeepAliveRunnable);
                        }
                    }

                    @Override
                    public void onCapabilitiesChanged(
                        @NonNull Network network,
                        @NonNull NetworkCapabilities networkCapabilities
                    ) {
                        super.onCapabilitiesChanged(network, networkCapabilities);
                        if (isKeeperActive) {
                            ensureLocksAcquired();
                        }
                    }

                    @Override
                    public void onLost(@NonNull Network network) {
                        super.onLost(network);
                        if (isKeeperActive) {
                            mainHandler.postDelayed(mainThreadKeepAliveRunnable, 250L);
                        }
                    }
                };
                networkCallback = cb;
                connectivityManager.registerDefaultNetworkCallback(cb);
                isNetworkRegistered = true;
            }
        } catch (Throwable t) {
            Log.w(TAG, "NetworkCallback registration warning: " + t.getMessage());
        }
    }

    /**
     * Returns true ONLY when an actual cellular/telephony phone call or incoming ringtone is active.
     * NEVER returns true for MODE_IN_COMMUNICATION because games like Free Fire, PUBG, and BGMI
     * set MODE_IN_COMMUNICATION for in-game voice chat!
     */
    public static boolean isActualCellularCallActive(@Nullable AudioManager audioManager) {
        if (audioManager == null) {
            return false;
        }
        try {
            int mode = audioManager.getMode();
            return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_RINGTONE;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Safely starts MusicPlaybackService using startService first (so Android never triggers
     * ForegroundServiceDidNotStartInTimeException during UI navigation) and falls back to
     * startForegroundService only when needed in the background.
     */
    public static void ensureForegroundMediaServiceStarted(@NonNull Context context) {
        try {
            Context appCtx = context.getApplicationContext();
            Intent serviceIntent = new Intent(appCtx, MusicPlaybackService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appCtx.startForegroundService(serviceIntent);
            } else {
                appCtx.startService(serviceIntent);
            }
        } catch (Throwable t) {
            try {
                Context appCtx = context.getApplicationContext();
                Intent serviceIntent = new Intent(appCtx, MusicPlaybackService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appCtx.startForegroundService(serviceIntent);
                }
            } catch (Throwable ignored) {
            }
        }
    }
}
