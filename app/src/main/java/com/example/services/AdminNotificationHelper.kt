package com.example.services

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Displays rich system notifications sent from the Admin Panel (via Firebase Cloud Messaging
 * or Firebase Realtime Database /notifications).
 * Layout:
 * - Header: Music Icon (R.drawable.ic_aadify_notification) + App Name ("AADIFY")
 * - Title: Bold, distinct headline typography
 * - Body: Clean, readable multi-line paragraph text
 * - Image (Optional): Full-width BigPicture banner below the body (with NO right-side square logo)
 */
object AdminNotificationHelper {
    private const val TAG = "AdminNotifHelper"
    const val CHANNEL_ID = "aadify_admin_broadcast_channel"
    private const val CHANNEL_NAME = "AADIFY Official Notifications"
    private const val PREFS_NAME = "ak_shown_admin_notifications"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Notifications and announcements from AADIFY"
                    enableLights(true)
                    lightColor = Color.parseColor("#1DB954")
                    enableVibration(true)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    setShowBadge(true)
                }
                nm.createNotificationChannel(channel)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to create notification channel", e)
            }
        }
    }

    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
    }

    fun isNotificationAlreadyShown(context: Context, uniqueKey: String): Boolean {
        if (uniqueKey.isBlank()) return false
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val seenSet = prefs.getStringSet("seen_keys", emptySet()) ?: emptySet()
        return uniqueKey in seenSet
    }

    fun markNotificationShown(context: Context, uniqueKey: String) {
        if (uniqueKey.isBlank()) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = (prefs.getStringSet("seen_keys", emptySet()) ?: emptySet()).toMutableSet()
        current.add(uniqueKey)
        val trimmed = if (current.size > 300) current.toList().takeLast(250).toSet() else current
        prefs.edit().putStringSet("seen_keys", trimmed).apply()
    }

    private fun formatPremiumTitle(rawTitle: String): CharSequence {
        val text = rawTitle.trim()
        if (text.isEmpty()) return text
        return SpannableString(text).apply {
            setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(TypefaceSpan("sans-serif-medium"), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(RelativeSizeSpan(1.05f), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun formatPremiumBody(rawBody: String): CharSequence {
        val text = rawBody.trim()
        if (text.isEmpty()) return text
        return SpannableString(text).apply {
            setSpan(TypefaceSpan("sans-serif"), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    fun showAdminNotificationAsync(
        context: Context,
        uniqueKey: String,
        title: String,
        body: String,
        imageUrl: String? = null
    ) {
        val appCtx = context.applicationContext
        scope.launch {
            showAdminNotificationBlocking(
                context = appCtx,
                uniqueKey = uniqueKey,
                title = title,
                body = body,
                imageUrl = imageUrl
            )
        }
    }

    fun showAdminNotificationBlocking(
        context: Context,
        uniqueKey: String,
        title: String,
        body: String,
        imageUrl: String? = null
    ) {
        try {
            val cleanTitle = title.trim().ifBlank { context.getString(R.string.app_name) }
            val cleanBody = body.trim()
            if (cleanTitle.isBlank() && cleanBody.isBlank()) return

            val dedupeKey = uniqueKey.ifBlank { "${cleanTitle}_${cleanBody}_${imageUrl.orEmpty()}" }
            if (isNotificationAlreadyShown(context, dedupeKey)) {
                return
            }

            ensureChannel(context)
            if (!hasNotificationPermission(context)) {
                Log.w(TAG, "POST_NOTIFICATIONS not granted yet; skipping display")
                return
            }

            markNotificationShown(context, dedupeKey)

            val styledTitle = formatPremiumTitle(cleanTitle)
            val styledBody = formatPremiumBody(cleanBody)

            val remoteBannerBitmap = imageUrl?.trim()?.takeIf {
                it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)
            }?.let { url ->
                downloadBitmap(url)
            }

            val launchIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                abs(dedupeKey.hashCode()),
                launchIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            // Do NOT setLargeIcon(appLogoBitmap) so no square app logo appears on the right side of the text!
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_aadify_notification)
                .setColor(Color.parseColor("#1DB954"))
                .setContentTitle(styledTitle)
                .setContentText(styledBody)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setShowWhen(true)
                .setWhen(System.currentTimeMillis())
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)

            if (remoteBannerBitmap != null) {
                val bigPicStyle = NotificationCompat.BigPictureStyle()
                    .bigPicture(remoteBannerBitmap)
                    .bigLargeIcon(null as Bitmap?)
                    .setBigContentTitle(styledTitle)
                    .setSummaryText(styledBody)
                builder.setStyle(bigPicStyle)
            } else {
                val bigTextStyle = NotificationCompat.BigTextStyle()
                    .bigText(styledBody)
                    .setBigContentTitle(styledTitle)
                builder.setStyle(bigTextStyle)
            }

            val notificationId = 5000 + (abs(dedupeKey.hashCode()) % 90000)
            NotificationManagerCompat.from(context).notify(notificationId, builder.build())
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException posting notification", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error showing admin notification", e)
        }
    }

    private fun downloadBitmap(url: String): Bitmap? {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Android 14; Mobile)")
                .get()
                .build()
            httpClient.newCall(req).execute().use { response ->
                if (!response.isSuccessful) return null
                val bytes = response.body?.bytes() ?: return null
                if (bytes.isEmpty()) return null
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to download notification image: $url", e)
            null
        }
    }
}
