package com.example.models

enum class SubscriptionPlanType(
    val id: String,
    val title: String,
    val badge: String,
    val defaultDurationDays: Int
) {
    NONE(
        id = "none",
        title = "Free Plan",
        badge = "FREE TIER • 1 HR/DAY",
        defaultDurationDays = 0
    ),
    CRAZY_DEAL(
        id = "crazy_deal",
        title = "First User Deal",
        badge = "CHEAPEST NEW USER OFFER 🔥",
        defaultDurationDays = 7
    ),
    WEEKLY(
        id = "weekly",
        title = "Weekly Plan",
        badge = "7 DAYS ACCESS",
        defaultDurationDays = 7
    ),
    MONTHLY(
        id = "monthly",
        title = "Monthly Plan",
        badge = "MOST POPULAR • 30 DAYS",
        defaultDurationDays = 30
    ),
    FOREVER(
        id = "forever",
        title = "Forever Plan",
        badge = "LIFETIME VIP • UNLIMITED",
        defaultDurationDays = 36500
    ),
    CUSTOM(
        id = "custom",
        title = "VIP Custom Plan",
        badge = "ADMIN VIP ACCESS 👑",
        defaultDurationDays = 7
    );

    val displayName: String
        get() = title

    companion object {
        fun fromId(rawId: String): SubscriptionPlanType {
            val clean = rawId.trim().lowercase()
            if (clean.isEmpty() || clean == "none" || clean == "free") return NONE
            return entries.firstOrNull { it.id == clean }
                ?: when {
                    clean.contains("custom") -> CUSTOM
                    clean.contains("crazy") || clean.contains("cheap") || clean.contains("first") || clean.contains("deal") -> CRAZY_DEAL
                    clean.contains("week") -> WEEKLY
                    clean.contains("month") -> MONTHLY
                    clean.contains("forever") || clean.contains("life") -> FOREVER
                    else -> NONE
                }
        }
    }
}

/**
 * Live Admin-editable subscription pricing and banner configuration synced from Firebase Realtime Database.
 * Matches AK_Music_Admin_Final.html paths:
 * - `/subscriptionPlans` (`weekly`, `monthly`, `forever`, `firstUserDeal`)
 * - `/premiumBanners/premium` (`image`, `title`, `subtitle`, `heading`, `description`, `enabled`)
 * - `/premiumBanners/firstUserDeal` (`image`, `title`, `description`, `price`, `duration`, `validity`, `enabled`)
 */
data class AdminSubscriptionConfig(
    val weeklyPriceInr: Int = 29,
    val monthlyPriceInr: Int = 79,
    val foreverPriceInr: Int = 499,
    val crazyDealPriceInr: Int = 9,
    val crazyDealDurationDays: Int = 7,
    val crazyDealValidityHours: Int = 72,
    val crazyDealEnabled: Boolean = true,
    val crazyDealTitle: String = "CRAZY DEALS • FIRST USER SPECIAL 🔥",
    val crazyDealSubtitle: String = "Unlock full AADIFY Premium at our cheapest price ever — exclusive for new users!",
    val crazyDealBannerUrl: String = "https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?auto=format&fit=crop&w=1000&q=80",
    val premiumEnabled: Boolean = true,
    val premiumTitle: String = "AADIFY Premium",
    val premiumSubtitle: String = "Unlimited music, Playlists & Repeat mode",
    val premiumHeading: String = "Unlock Non-Stop Listening",
    val premiumDescription: String = "No daily limit • Create Unlimited Playlists • Repeat Songs",
    val premiumBannerUrl: String = "https://images.unsplash.com/photo-1470225620780-dba8ba36b745?auto=format&fit=crop&w=1000&q=80",
    val freeDailyLimitMinutes: Int = 60,
    val freeDailyLimitSeconds: Int = 3600
)

data class UserSubscriptionState(
    val planType: SubscriptionPlanType = SubscriptionPlanType.NONE,
    val activatedAtMs: Long = 0L,
    val expiresAtMs: Long = 0L,
    val orderId: String = "",
    val utr: String = ""
) {
    val isForever: Boolean
        get() = planType == SubscriptionPlanType.FOREVER || expiresAtMs >= 4_000_000_000_000L

    fun isActive(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (planType == SubscriptionPlanType.NONE) return false
        if (isForever) return true
        return expiresAtMs > nowMs
    }

    fun remainingSeconds(nowMs: Long = System.currentTimeMillis()): Long {
        if (!isActive(nowMs)) return 0L
        if (isForever) return 36500L * 86_400L
        return ((expiresAtMs - nowMs) / 1000L).coerceAtLeast(0L)
    }
}
