package com.example.services

import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Firebase Cloud Messaging (FCM) service for AADIFY.
 * Receives push notifications sent from the Admin Panel or Firebase Console with:
 * - Title
 * - Paragraph Body
 * - Optional Image URL
 * and renders them with the official AADIFY name and logo.
 */
class AADIFYMessagingService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "AADIFYMessaging"
        private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val httpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()

        private val DEFAULT_TOPICS = listOf(
            "all",
            "all_users",
            "aadify",
            "notifications",
            "broadcast",
            "users"
        )

        /**
         * Subscribes this device to all standard broadcast topics and registers its FCM token
         * in Firebase Realtime Database (/fcm_tokens and /tokens) so the Admin Panel can send
         * push messages either by topic or by token list.
         */
        fun initializeAndSubscribe(context: android.content.Context, userName: String = "") {
            AdminNotificationHelper.ensureChannel(context)
            try {
                val messaging = FirebaseMessaging.getInstance()
                messaging.isAutoInitEnabled = true
                for (topic in DEFAULT_TOPICS) {
                    messaging.subscribeToTopic(topic)
                }
                messaging.token.addOnSuccessListener { token ->
                    if (!token.isNullOrBlank()) {
                        registerTokenInCloud(token, userName)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "FCM init/subscribe note: ${e.message}")
            }
        }

        fun registerTokenInCloud(token: String, userName: String = "") {
            if (token.isBlank()) return
            ioScope.launch {
                try {
                    val safeKey = token.replace(Regex("[^A-Za-z0-9_-]"), "_").takeLast(64)
                    val payload = JSONObject().apply {
                        put("token", token)
                        if (userName.isNotBlank()) {
                            put("userName", userName)
                        }
                        put("platform", "android")
                        put("appName", "AADIFY")
                        put("updatedAt", System.currentTimeMillis())
                    }
                    val bodyStr = payload.toString()
                    val mediaType = "application/json; charset=utf-8".toMediaType()

                    val req1 = Request.Builder()
                        .url("${FirebaseSongsService.DATABASE_URL}/fcm_tokens/$safeKey.json")
                        .put(bodyStr.toRequestBody(mediaType))
                        .build()
                    httpClient.newCall(req1).execute().close()

                    val req2 = Request.Builder()
                        .url("${FirebaseSongsService.DATABASE_URL}/tokens/$safeKey.json")
                        .put(bodyStr.toRequestBody(mediaType))
                        .build()
                    httpClient.newCall(req2).execute().close()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to sync FCM token to RTDB: ${e.message}")
                }
            }
        }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        registerTokenInCloud(token)
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        try {
            val data = remoteMessage.data
            val notif = remoteMessage.notification

            val title = notif?.title
                ?.takeIf { it.isNotBlank() }
                ?: data["title"]
                ?: data["heading"]
                ?: data["subject"]
                ?: data["name"]
                ?: "AADIFY"

            val body = notif?.body
                ?.takeIf { it.isNotBlank() }
                ?: data["body"]
                ?: data["message"]
                ?: data["paragraph"]
                ?: data["text"]
                ?: data["description"]
                ?: data["content"]
                ?: data["subtitle"]
                ?: ""

            val imageUrl = notif?.imageUrl?.toString()
                ?.takeIf { it.isNotBlank() }
                ?: AdminNotificationHelper.extractImageUrlFromMap(data)

            val uniqueKey = remoteMessage.messageId
                ?: data["id"]
                ?: "fcm_${title}_${body}_${remoteMessage.sentTime}"

            kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                AdminNotificationHelper.showAdminNotificationBlocking(
                    context = applicationContext,
                    uniqueKey = uniqueKey,
                    title = title,
                    body = body,
                    imageUrl = imageUrl
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling FCM message", e)
        }
    }
}
