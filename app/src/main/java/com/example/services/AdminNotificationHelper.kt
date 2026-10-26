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
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.example.MainActivity
import com.example.R
import com.example.models.Song
import com.google.firebase.database.DataSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Displays rich system notifications sent from the Admin Panel (via Firebase Cloud Messaging
 * or Firebase Realtime Database).
 * Layout:
 * - Header: Music Icon (R.drawable.ic_aadify_notification) + App Name ("AADIFY")
 * - Title: Bold, distinct headline typography
 * - Body: Clean, readable multi-line paragraph text
 * - Image (Optional): Full-width BigPicture banner below the body (shown both collapsed and expanded,
 *   with NO right-side square app logo)
 */
object AdminNotificationHelper {
    private const val TAG = "AdminNotifHelper"
    const val CHANNEL_ID = "aadify_admin_broadcast_channel"
    private const val CHANNEL_NAME = "AADIFY Official Notifications"
    private const val PREFS_NAME = "ak_shown_admin_notifications"

    private val IMAGE_FIELD_NAMES = listOf(
        "imageUrl",
        "image",
        "image_url",
        "imgUrl",
        "img_url",
        "img",
        "url",
        "link",
        "imageLink",
        "image_link",
        "bannerUrl",
        "banner_url",
        "banner",
        "thumbnail",
        "thumb",
        "picture",
        "photo",
        "photoUrl",
        "photo_url",
        "mediaUrl",
        "media_url",
        "media",
        "icon",
        "iconUrl",
        "attachment",
        "file",
        "src",
        "bigPicture",
        "big_picture",
        "poster"
    )

    private val NON_IMAGE_TEXT_KEYS = setOf(
        "id",
        "key",
        "title",
        "heading",
        "subject",
        "name",
        "body",
        "message",
        "paragraph",
        "text",
        "description",
        "content",
        "subtitle",
        "timestamp",
        "createdat",
        "created_at",
        "updatedat",
        "updated_at",
        "appname",
        "app_name",
        "type",
        "status",
        "topic"
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
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

    /**
     * Universal image URL extractor for Firebase DataSnapshot.
     * Finds the image URL regardless of what field name the Admin Panel used (e.g. imageUrl, image, url, link, img, banner, etc.).
     */
    fun extractImageUrlFromSnapshot(snapshot: DataSnapshot): String? {
        try {
            // 1. Direct match on known image field names (exact or case-insensitive)
            for (field in IMAGE_FIELD_NAMES) {
                val v = snapshot.child(field).value
                if (v is String && looksLikeImageLinkOrData(v)) {
                    return cleanCandidateUrl(v)
                } else if (v is Map<*, *>) {
                    val nestedUrl = v["url"]?.toString() ?: v["src"]?.toString() ?: v["link"]?.toString()
                    if (!nestedUrl.isNullOrBlank() && looksLikeImageLinkOrData(nestedUrl)) {
                        return cleanCandidateUrl(nestedUrl)
                    }
                }
            }

            // 2. Case-insensitive scan across all children + nested containers (data, notification, payload, media)
            for (child in snapshot.children) {
                val k = child.key?.trim().orEmpty()
                val kLower = k.lowercase()
                val value = child.value
                if (value is String) {
                    val trimmed = value.trim()
                    if (IMAGE_FIELD_NAMES.any { it.equals(k, ignoreCase = true) } && trimmed.isNotBlank()) {
                        return cleanCandidateUrl(trimmed)
                    }
                    if (kLower !in NON_IMAGE_TEXT_KEYS && looksLikeImageLinkOrData(trimmed)) {
                        return cleanCandidateUrl(trimmed)
                    }
                } else if (child.hasChildren() && kLower in setOf("data", "notification", "payload", "media", "image", "extra")) {
                    val nested = extractImageUrlFromSnapshot(child)
                    if (!nested.isNullOrBlank()) return nested
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    /**
     * Universal image URL extractor for JSONObject (REST sync from Firebase RTDB).
     */
    fun extractImageUrlFromJson(obj: JSONObject): String? {
        try {
            for (field in IMAGE_FIELD_NAMES) {
                if (obj.has(field)) {
                    val raw = obj.opt(field)
                    if (raw is String && raw.isNotBlank() && raw != "null") {
                        return cleanCandidateUrl(raw)
                    } else if (raw is JSONObject) {
                        val nested = raw.optString("url").ifBlank {
                            raw.optString("src").ifBlank { raw.optString("link") }
                        }
                        if (nested.isNotBlank()) return cleanCandidateUrl(nested)
                    }
                }
            }

            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val kLower = k.lowercase()
                val raw = obj.opt(k)
                if (raw is String) {
                    val trimmed = raw.trim()
                    if (IMAGE_FIELD_NAMES.any { it.equals(k, ignoreCase = true) } && trimmed.isNotBlank() && trimmed != "null") {
                        return cleanCandidateUrl(trimmed)
                    }
                    if (kLower !in NON_IMAGE_TEXT_KEYS && looksLikeImageLinkOrData(trimmed)) {
                        return cleanCandidateUrl(trimmed)
                    }
                } else if (raw is JSONObject && kLower in setOf("data", "notification", "payload", "media", "image", "extra")) {
                    val nested = extractImageUrlFromJson(raw)
                    if (!nested.isNullOrBlank()) return nested
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    /**
     * Universal image URL extractor for FCM data map.
     */
    fun extractImageUrlFromMap(data: Map<String, String>): String? {
        for (field in IMAGE_FIELD_NAMES) {
            val v = data[field]?.trim()
            if (!v.isNullOrBlank() && v != "null") return cleanCandidateUrl(v)
        }
        for ((k, v) in data) {
            val trimmed = v.trim()
            if (trimmed.isBlank() || trimmed == "null") continue
            if (IMAGE_FIELD_NAMES.any { it.equals(k, ignoreCase = true) }) {
                return cleanCandidateUrl(trimmed)
            }
            if (k.lowercase() !in NON_IMAGE_TEXT_KEYS && looksLikeImageLinkOrData(trimmed)) {
                return cleanCandidateUrl(trimmed)
            }
        }
        return null
    }

    private fun looksLikeImageLinkOrData(raw: String): Boolean {
        val s = raw.trim().removeSurrounding("\"").removeSurrounding("'")
        if (s.length < 6 || s.equals("null", ignoreCase = true)) return false
        if (s.startsWith("http://", ignoreCase = true) ||
            s.startsWith("https://", ignoreCase = true) ||
            s.startsWith("data:image/", ignoreCase = true) ||
            s.startsWith("www.", ignoreCase = true) ||
            s.startsWith("//")
        ) {
            return true
        }
        val lower = s.lowercase()
        return lower.contains(".jpg") ||
            lower.contains(".jpeg") ||
            lower.contains(".png") ||
            lower.contains(".webp") ||
            lower.contains(".gif") ||
            lower.contains("imgur.com") ||
            lower.contains("ibb.co") ||
            lower.contains("postimg") ||
            lower.contains("drive.google.com") ||
            lower.contains("googleusercontent.com") ||
            lower.contains("firebasestorage") ||
            lower.contains("unsplash.com") ||
            lower.contains("pexels.com") ||
            lower.contains("ytimg.com") ||
            lower.contains("youtube.com") ||
            lower.contains("youtu.be")
    }

    private fun cleanCandidateUrl(raw: String): String {
        var s = raw.trim().removeSurrounding("\"").removeSurrounding("'").trim()
        if (s.startsWith("data:image/", ignoreCase = true)) return s
        val urlRegex = Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)
        urlRegex.find(s)?.value?.let { matched ->
            s = matched
        }
        if (s.startsWith("//")) {
            s = "https:$s"
        } else if (!s.startsWith("http://", ignoreCase = true) && !s.startsWith("https://", ignoreCase = true)) {
            s = "https://$s"
        }
        return s
    }

    fun getOrInitInstallBaselineMs(context: Context): Long {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getLong("install_baseline_ms", 0L)
        if (existing > 0L) {
            return existing
        }
        val now = System.currentTimeMillis()
        prefs.edit().putLong("install_baseline_ms", now).apply()
        return now
    }

    fun isNodeBaselineSeeded(context: Context, nodeName: String): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean("baseline_seeded_$nodeName", false)
    }

    fun markNodeBaselineSeeded(context: Context, nodeName: String) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean("baseline_seeded_$nodeName", true).apply()
    }

    fun markNotificationSilentlySeen(
        context: Context,
        uniqueKey: String,
        title: String,
        body: String
    ) {
        val cleanTitle = title.trim().ifBlank { "AADIFY" }
        val cleanBody = body.trim()
        val contentDedupeKey = "${cleanTitle.lowercase()}|${cleanBody.lowercase()}"
        if (uniqueKey.isNotBlank()) {
            markNotificationShown(context, uniqueKey, withImage = true)
        }
        markNotificationShown(context, contentDedupeKey, withImage = true)
    }

    private fun isNotificationAlreadyShown(
        context: Context,
        dedupeKey: String,
        hasImageToAttach: Boolean
    ): Boolean {
        if (dedupeKey.isBlank()) return false
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val seenWithImage = prefs.getStringSet("seen_with_image_keys", emptySet()) ?: emptySet()
        if (dedupeKey in seenWithImage) {
            return true
        }
        // If this call has an image to attach, and previously we only showed the notification WITHOUT an image,
        // allow it through so it upgrades the notification with the image banner!
        if (hasImageToAttach) {
            return false
        }
        val seenSet = prefs.getStringSet("seen_keys", emptySet()) ?: emptySet()
        return dedupeKey in seenSet
    }

    private fun markNotificationShown(
        context: Context,
        dedupeKey: String,
        withImage: Boolean
    ) {
        if (dedupeKey.isBlank()) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = (prefs.getStringSet("seen_keys", emptySet()) ?: emptySet()).toMutableSet()
        current.add(dedupeKey)
        val trimmed = if (current.size > 500) current.toList().takeLast(400).toSet() else current

        val editor = prefs.edit().putStringSet("seen_keys", trimmed)
        if (withImage) {
            val currentImg = (prefs.getStringSet("seen_with_image_keys", emptySet()) ?: emptySet()).toMutableSet()
            currentImg.add(dedupeKey)
            val trimmedImg = if (currentImg.size > 500) currentImg.toList().takeLast(400).toSet() else currentImg
            editor.putStringSet("seen_with_image_keys", trimmedImg)
        }
        editor.apply()
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

    suspend fun showAdminNotificationBlocking(
        context: Context,
        uniqueKey: String,
        title: String,
        body: String,
        imageUrl: String? = null
    ) {
        try {
            val cleanTitle = title.trim().ifBlank { context.getString(R.string.app_name) }
            var cleanBody = body.trim()
            if (cleanTitle.isBlank() && cleanBody.isBlank()) return

            // If imageUrl wasn't passed in a separate field, check if the admin pasted an image link inside the body text
            var candidateImageUrl = imageUrl?.trim()?.takeIf { it.isNotBlank() && it != "null" }
            if (candidateImageUrl.isNullOrBlank()) {
                val urlInBody = Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)
                    .findAll(cleanBody)
                    .map { it.value }
                    .firstOrNull { looksLikeImageLinkOrData(it) }
                if (!urlInBody.isNullOrBlank()) {
                    candidateImageUrl = urlInBody
                    val strippedBody = cleanBody.replace(urlInBody, "").trim()
                    if (strippedBody.isNotBlank()) {
                        cleanBody = strippedBody
                    }
                }
            }

            val hasCandidateImage = !candidateImageUrl.isNullOrBlank()
            val contentDedupeKey = "${cleanTitle.lowercase()}|${cleanBody.lowercase()}"
            val primaryDedupeKey = uniqueKey.ifBlank { contentDedupeKey }

            if (isNotificationAlreadyShown(context, primaryDedupeKey, hasCandidateImage) &&
                isNotificationAlreadyShown(context, contentDedupeKey, hasCandidateImage)
            ) {
                return
            }

            ensureChannel(context)
            if (!hasNotificationPermission(context)) {
                Log.w(TAG, "POST_NOTIFICATIONS not granted yet; skipping display")
                return
            }

            // Download & decode the image banner BEFORE marking as shown, with retry if needed
            var remoteBannerBitmap: Bitmap? = null
            if (!candidateImageUrl.isNullOrBlank()) {
                val cleanedUrl = cleanCandidateUrl(candidateImageUrl)
                remoteBannerBitmap = resolveAndDownloadBannerBitmap(context, cleanedUrl)
                if (remoteBannerBitmap == null) {
                    delay(600L)
                    remoteBannerBitmap = resolveAndDownloadBannerBitmap(context, cleanedUrl)
                }
            }

            val attachedImage = remoteBannerBitmap != null
            markNotificationShown(context, primaryDedupeKey, withImage = attachedImage)
            markNotificationShown(context, contentDedupeKey, withImage = attachedImage)

            val styledTitle = formatPremiumTitle(cleanTitle)
            val styledBody = formatPremiumBody(cleanBody)

            val launchIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                abs(contentDedupeKey.hashCode()),
                launchIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            // Do NOT setLargeIcon(appLogoBitmap) so no square app logo ever appears on the right side!
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
                    .showBigPictureWhenCollapsed(true)
                builder.setStyle(bigPicStyle)
            } else {
                val bigTextStyle = NotificationCompat.BigTextStyle()
                    .bigText(styledBody)
                    .setBigContentTitle(styledTitle)
                builder.setStyle(bigTextStyle)
            }

            // Deterministic notificationId per Title+Body so if an image upgrades an earlier text-only
            // notification, it replaces it in-place with the full image banner!
            val notificationId = 5000 + (abs(contentDedupeKey.hashCode()) % 90000)

            // Also dismiss any plain system-tray FCM notification that lacks the image for the same title
            cancelDuplicateSystemTrayNotification(context, notificationId, cleanTitle)

            NotificationManagerCompat.from(context).notify(notificationId, builder.build())

            // Ensure background music playback is never interrupted by the notification alert sound
            if (MusicPlaybackService.isCurrentlyPlaying()) {
                val wakeDelays = longArrayOf(0L, 120L, 350L, 800L, 1500L, 2500L)
                for (d in wakeDelays) {
                    mainHandler.postDelayed({
                        if (MusicPlaybackService.isCurrentlyPlaying()) {
                            MusicPlaybackService.onKeepOnlineEngineAwakeCallback?.invoke()
                        }
                    }, d)
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException posting notification", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error showing admin notification", e)
        }
    }

    private fun cancelDuplicateSystemTrayNotification(
        context: Context,
        ourNotificationId: Int,
        cleanTitle: String
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
                for (sbn in nm.activeNotifications) {
                    if (sbn.id == ourNotificationId || sbn.id == 1001) continue
                    val extras = sbn.notification.extras
                    val activeTitle = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
                    val isFcmTag = sbn.tag?.contains("FCM-Notification", ignoreCase = true) == true
                    if (isFcmTag || activeTitle.equals(cleanTitle, ignoreCase = true)) {
                        if (sbn.tag != null) {
                            nm.cancel(sbn.tag, sbn.id)
                        } else {
                            nm.cancel(sbn.id)
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Resolves any image link (Direct image, Google Drive, Dropbox, Google Images, YouTube thumbnail,
     * ImgBB/Imgur/Unsplash webpage with og:image, Base64 data URI, HTTP or HTTPS) and returns a
     * scaled software Bitmap guaranteed to fit within Android's Notification Binder limit.
     */
    private suspend fun resolveAndDownloadBannerBitmap(context: Context, rawUrl: String): Bitmap? {
        try {
            val trimmed = rawUrl.trim()
            if (trimmed.isEmpty()) return null

            // 1. Base64 Data URI support (data:image/png;base64,...)
            if (trimmed.startsWith("data:image/", ignoreCase = true)) {
                val commaIndex = trimmed.indexOf(',')
                if (commaIndex != -1 && commaIndex + 1 < trimmed.length) {
                    val base64Part = trimmed.substring(commaIndex + 1)
                    val bytes = Base64.decode(base64Part, Base64.DEFAULT)
                    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (decoded != null) {
                        return normalizeNotificationBitmap(decoded)
                    }
                }
            }

            // 2. Transform common sharing links into direct image download URLs
            val normalizedUrl = transformSharingUrlToDirectImage(trimmed)

            // 3. Try downloading via OkHttp (with automatic HTML og:image extraction if the link is a webpage)
            val fromHttp = downloadBitmapViaOkHttp(normalizedUrl, depth = 0)
            if (fromHttp != null) {
                return normalizeNotificationBitmap(fromHttp)
            }

            // 4. If normalizedUrl used http://, also try https:// (and vice versa)
            val toggledSchemeUrl = when {
                normalizedUrl.startsWith("http://", ignoreCase = true) ->
                    "https://" + normalizedUrl.substring(7)
                normalizedUrl.startsWith("https://", ignoreCase = true) ->
                    "http://" + normalizedUrl.substring(8)
                else -> null
            }
            if (toggledSchemeUrl != null) {
                val fromToggled = downloadBitmapViaOkHttp(toggledSchemeUrl, depth = 0)
                if (fromToggled != null) {
                    return normalizeNotificationBitmap(fromToggled)
                }
            }

            // 5. Fallback to Coil ImageLoader
            val imageLoader = ImageLoader(context.applicationContext)
            val request = ImageRequest.Builder(context.applicationContext)
                .data(normalizedUrl)
                .allowHardware(false)
                .build()
            val result = imageLoader.execute(request)
            if (result is SuccessResult) {
                val bmp = (result.drawable as? BitmapDrawable)?.bitmap
                if (bmp != null) {
                    return normalizeNotificationBitmap(bmp)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error resolving notification image: $rawUrl", e)
        }
        return null
    }

    private fun transformSharingUrlToDirectImage(url: String): String {
        try {
            val uri = Uri.parse(url)
            val host = uri.host?.lowercase().orEmpty()

            // Google Images search result link (https://www.google.com/imgres?imgurl=...)
            if (host.contains("google.") && uri.path?.contains("imgres") == true) {
                val imgUrlParam = uri.getQueryParameter("imgurl")
                if (!imgUrlParam.isNullOrBlank()) {
                    return URLDecoder.decode(imgUrlParam, "UTF-8")
                }
            }

            // Google Drive sharing link (https://drive.google.com/file/d/FILE_ID/view or ?id=FILE_ID)
            if (host.contains("drive.google.com") || host.contains("docs.google.com")) {
                val fileIdFromPath = Regex("""/file/d/([a-zA-Z0-9_-]+)""").find(url)?.groupValues?.getOrNull(1)
                val fileId = fileIdFromPath ?: uri.getQueryParameter("id")
                if (!fileId.isNullOrBlank()) {
                    return "https://drive.google.com/uc?export=download&id=$fileId"
                }
            }

            // Dropbox sharing link
            if (host.contains("dropbox.com")) {
                return url.replace("?dl=0", "?raw=1").replace("&dl=0", "&raw=1")
            }

            // YouTube video or shorts link -> high-res video thumbnail
            if ((host.contains("youtube.com") || host.contains("youtu.be")) && !host.contains("ytimg.com")) {
                val ytId = Song.extractYoutubeVideoId(url)
                if (ytId.length == 11) {
                    return "https://img.youtube.com/vi/$ytId/hqdefault.jpg"
                }
            }
        } catch (_: Exception) {
        }
        return url
    }

    private fun downloadBitmapViaOkHttp(url: String, depth: Int): Bitmap? {
        if (depth > 2) return null
        return try {
            val req = Request.Builder()
                .url(url)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                )
                .header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                .get()
                .build()

            httpClient.newCall(req).execute().use { response ->
                if (!response.isSuccessful) return null
                val contentType = response.header("Content-Type")?.lowercase().orEmpty()
                val bytes = response.body?.bytes() ?: return null
                if (bytes.isEmpty()) return null

                // Try decoding directly as an image first
                val directBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (directBitmap != null) {
                    return directBitmap
                }

                // If the URL pointed to an HTML wrapper page (e.g. ibb.co, imgur, postimages, pinterest, etc.),
                // extract the <meta property="og:image" content="..."> or twitter:image URL and download it!
                if (contentType.contains("text/html") || bytes.size < 500_000) {
                    val html = String(bytes, Charsets.UTF_8)
                    val extractedImageUrl = extractOgImageFromHtml(html, url)
                    if (!extractedImageUrl.isNullOrBlank() && extractedImageUrl != url) {
                        return downloadBitmapViaOkHttp(extractedImageUrl, depth + 1)
                    }
                }
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to download notification image ($url): ${e.message}")
            null
        }
    }

    private fun extractOgImageFromHtml(html: String, baseUrl: String): String? {
        val patterns = listOf(
            Regex("""<meta[^>]+(?:property|name)=["'](?:og:image|og:image:secure_url|twitter:image|twitter:image:src)["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""<meta[^>]+content=["']([^"']+)["'][^>]+(?:property|name)=["'](?:og:image|og:image:secure_url|twitter:image|twitter:image:src)["']""", RegexOption.IGNORE_CASE),
            Regex("""<link[^>]+rel=["']image_src["'][^>]+href=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        )
        for (regex in patterns) {
            val match = regex.find(html)?.groupValues?.getOrNull(1)?.trim()
            if (!match.isNullOrBlank()) {
                return resolveRelativeUrl(baseUrl, match.replace("&amp;", "&"))
            }
        }
        return null
    }

    private fun resolveRelativeUrl(baseUrl: String, candidate: String): String {
        if (candidate.startsWith("http://", ignoreCase = true) || candidate.startsWith("https://", ignoreCase = true)) {
            return candidate
        }
        if (candidate.startsWith("//")) {
            return "https:$candidate"
        }
        return try {
            val baseUri = Uri.parse(baseUrl)
            "${baseUri.scheme ?: "https"}://${baseUri.host}$candidate"
        } catch (_: Exception) {
            candidate
        }
    }

    /**
     * Ensures the Bitmap is a software ARGB_8888 Bitmap scaled to max 1024x512 so it renders
     * crisply in Android's BigPictureStyle and never triggers TransactionTooLargeException.
     */
    private fun normalizeNotificationBitmap(source: Bitmap): Bitmap {
        val soft = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && source.config == Bitmap.Config.HARDWARE) {
            source.copy(Bitmap.Config.ARGB_8888, false) ?: source
        } else {
            source
        }
        val maxWidth = 1024
        val maxHeight = 576
        val w = soft.width
        val h = soft.height
        if (w <= 0 || h <= 0) return soft
        if (w <= maxWidth && h <= maxHeight) return soft

        val ratio = minOf(maxWidth.toFloat() / w.toFloat(), maxHeight.toFloat() / h.toFloat())
        val targetW = (w * ratio).toInt().coerceAtLeast(1)
        val targetH = (h * ratio).toInt().coerceAtLeast(1)
        return try {
            Bitmap.createScaledBitmap(soft, targetW, targetH, true)
        } catch (_: Exception) {
            soft
        }
    }
}
