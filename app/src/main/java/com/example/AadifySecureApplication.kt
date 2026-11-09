package com.example

import android.app.Application
import android.content.Context
import com.example.security.AadifySecurityVault

class AadifySecureApplication : Application() {

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        if (base != null) {
            try {
                AadifySecurityVault.ensureSecureFirebaseInitialized(base)
            } catch (_: Throwable) {
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            AadifySecurityVault.ensureSecureFirebaseInitialized(this)
        } catch (_: Throwable) {
        }
    }
}
