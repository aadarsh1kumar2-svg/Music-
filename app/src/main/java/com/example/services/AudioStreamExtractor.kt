package com.example.services

import android.util.Log
import com.example.models.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class ResolvedAudioStream(
    val streamUrl: String,
    val mimeType: String,
    val bitrate: Int,
    val sourceEngine: String,
    val userAgent: String = AudioStreamExtractor.DEFAULT_STREAM_USER_AGENT
)

class AudioStreamExtractor {

    companion object {
        private const val TAG = "AudioStreamExtractor"

        const val DEFAULT_STREAM_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        // Attempt 2: Piped API endpoints (starting with https://api.piped.privacydev.net/streams/$videoId)
        private val PIPED_INSTANCES = listOf(
            "https://api.piped.privacydev.net",
            "https://pipedapi.kavin.rocks",
            "https://pipedapi.tokhmi.xyz",
            "https://pipedapi.moomoo.me",
            "https://pipedapi-libre.kavin.rocks",
            "https://api.piped.projectsegfau.lt",
            "https://pipedapi.adminforge.de"
        )

        // Attempt 3: Invidious API endpoints (starting with https://inv.tux.pizza/api/v1/videos/$videoId)
        private val INVIDIOUS_INSTANCES = listOf(
            "https://inv.tux.pizza",
            "https://invidious.nerdvpn.de",
            "https://invidious.jing.rocks",
            "https://yt.artemislena.eu"
        )
    }

    // Strict 6-second timeout client per attempt to prevent infinite buffering/hangs
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .writeTimeout(6, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    // Cache resolved stream URLs for 25 minutes to allow instant replay/seeking
    private val streamCache = ConcurrentHashMap<String, Pair<Long, ResolvedAudioStream>>()
    private val cacheTtlMs = 25 * 60 * 1000L

    fun invalidateCachedStream(rawVideoId: String) {
        val cleanId = Song.extractYoutubeVideoId(rawVideoId)
        if (cleanId.isNotBlank()) {
            streamCache.remove(cleanId)
        }
    }

    /**
     * Dedicated Multi-Tier Audio Stream Extractor with strict 6-second timeout per attempt.
     * Returns the direct audio-only stream URL or null if all tiers fail.
     */
    suspend fun resolveAudioStreamUrl(rawVideoId: String): String? {
        return extractBestAudioStream(rawVideoId).getOrNull()?.streamUrl
    }

    suspend fun extractBestAudioStream(rawVideoId: String, forceFresh: Boolean = false): Result<ResolvedAudioStream> =
        withContext(Dispatchers.IO) {
            val videoId = Song.extractYoutubeVideoId(rawVideoId)
            if (videoId.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("Stream unavailable for this track."))
            }

            if (rawVideoId.startsWith("http", ignoreCase = true) &&
                (rawVideoId.contains(".mp3", ignoreCase = true) ||
                        rawVideoId.contains(".m4a", ignoreCase = true) ||
                        rawVideoId.contains(".ogg", ignoreCase = true) ||
                        rawVideoId.contains(".opus", ignoreCase = true) ||
                        rawVideoId.contains(".wav", ignoreCase = true))
            ) {
                val direct = ResolvedAudioStream(
                    streamUrl = rawVideoId.trim(),
                    mimeType = "audio/mp4",
                    bitrate = 192000,
                    sourceEngine = "Direct Audio Stream",
                    userAgent = DEFAULT_STREAM_USER_AGENT
                )
                return@withContext Result.success(direct)
            }

            if (!forceFresh) {
                val cached = streamCache[videoId]
                if (cached != null && (System.currentTimeMillis() - cached.first) < cacheTtlMs) {
                    return@withContext Result.success(cached.second)
                }
            }

            // Tier 1: YouTube Audio Manifest Extraction (6-second timeout)
            val attempt1 = withTimeoutOrNull(6_000L) {
                extractViaYoutubeInnerTube(videoId)
            }
            if (attempt1 != null) {
                streamCache[videoId] = System.currentTimeMillis() to attempt1
                return@withContext Result.success(attempt1)
            }

            // Tier 2 (Fallback 1): Piped API (https://api.piped.privacydev.net/streams/$videoId + mirrors, 6-second timeout)
            val attempt2 = withTimeoutOrNull(6_000L) {
                extractViaPipedApi(videoId)
            }
            if (attempt2 != null) {
                streamCache[videoId] = System.currentTimeMillis() to attempt2
                return@withContext Result.success(attempt2)
            }

            // Tier 3 (Fallback 2): Invidious API (https://inv.tux.pizza/api/v1/videos/$videoId + mirrors, 6-second timeout)
            val attempt3 = withTimeoutOrNull(6_000L) {
                extractViaInvidiousApi(videoId)
            }
            if (attempt3 != null) {
                streamCache[videoId] = System.currentTimeMillis() to attempt3
                return@withContext Result.success(attempt3)
            }

            Result.failure(
                RuntimeException("Stream unavailable for this track.")
            )
        }

    private fun extractViaYoutubeInnerTube(videoId: String): ResolvedAudioStream? {
        val clients = listOf(
            InnerTubeClientConfig(
                clientName = "ANDROID_VR",
                clientVersion = "1.60.19",
                userAgent = "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
                osName = "Android",
                osVersion = "12L",
                androidSdkVersion = 32
            ),
            InnerTubeClientConfig(
                clientName = "ANDROID",
                clientVersion = "19.44.38",
                userAgent = "com.google.android.youtube/19.44.38 (Linux; U; Android 14) gzip",
                osName = "Android",
                osVersion = "14",
                androidSdkVersion = 34
            ),
            InnerTubeClientConfig(
                clientName = "IOS",
                clientVersion = "19.45.4",
                userAgent = "com.google.ios.youtube/19.45.4 (iPhone16,2; U; CPU iOS 18_1_0 like Mac OS X;)",
                osName = "iOS",
                osVersion = "18.1.0.22B83",
                androidSdkVersion = null
            )
        )

        for (client in clients) {
            try {
                val contextClient = JSONObject().apply {
                    put("clientName", client.clientName)
                    put("clientVersion", client.clientVersion)
                    put("hl", "en")
                    put("gl", "IN")
                    put("osName", client.osName)
                    put("osVersion", client.osVersion)
                    if (client.androidSdkVersion != null) {
                        put("androidSdkVersion", client.androidSdkVersion)
                    }
                }
                val payload = JSONObject().apply {
                    put("videoId", videoId)
                    put("contentCheckOk", true)
                    put("racyCheckOk", true)
                    put("context", JSONObject().put("client", contextClient))
                }

                val request = Request.Builder()
                    .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
                    .header("User-Agent", client.userAgent)
                    .header("Content-Type", "application/json")
                    .header("Accept", "*/*")
                    .header("X-YouTube-Client-Name", if (client.clientName == "IOS") "5" else "3")
                    .header("X-YouTube-Client-Version", client.clientVersion)
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val body = response.body?.string() ?: return@use
                    val json = JSONObject(body)
                    val streamingData = json.optJSONObject("streamingData") ?: return@use
                    val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats") ?: return@use

                    val bestStream = pickHighestBitrateAudio(
                        formats = adaptiveFormats,
                        engineName = "YouTube Audio (${client.clientName})",
                        userAgent = client.userAgent
                    )
                    if (bestStream != null) {
                        return bestStream
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "InnerTube (${client.clientName}) failed for $videoId: ${e.message}")
            }
        }
        return null
    }

    private fun pickHighestBitrateAudio(
        formats: JSONArray,
        engineName: String,
        userAgent: String
    ): ResolvedAudioStream? {
        var bestUrl: String? = null
        var bestMime = "audio/mp4"
        var bestBitrate = -1

        for (i in 0 until formats.length()) {
            val item = formats.optJSONObject(i) ?: continue
            val mimeType = item.optString("mimeType", "").ifBlank { item.optString("type", "") }
            val url = item.optString("url", "")
            if (url.isBlank() || !mimeType.startsWith("audio/", ignoreCase = true)) continue

            val bitrate = item.optInt("bitrate", 0)
            val bonus = if (mimeType.contains("mp4", ignoreCase = true)) 15000 else 0
            val score = bitrate + bonus
            if (score > bestBitrate) {
                bestBitrate = score
                bestUrl = url
                bestMime = mimeType.substringBefore(";").trim()
            }
        }

        return if (bestUrl != null) {
            ResolvedAudioStream(
                streamUrl = bestUrl,
                mimeType = bestMime,
                bitrate = bestBitrate,
                sourceEngine = engineName,
                userAgent = userAgent
            )
        } else {
            null
        }
    }

    private fun extractViaPipedApi(videoId: String): ResolvedAudioStream? {
        for (baseUrl in PIPED_INSTANCES) {
            try {
                val request = Request.Builder()
                    .url("$baseUrl/streams/$videoId")
                    .header("User-Agent", DEFAULT_STREAM_USER_AGENT)
                    .header("Accept", "*/*")
                    .get()
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val body = response.body?.string() ?: return@use
                    val json = JSONObject(body)
                    val audioStreams = json.optJSONArray("audioStreams") ?: return@use

                    var bestUrl: String? = null
                    var bestMime = "audio/mp4"
                    var bestBitrate = -1

                    for (i in 0 until audioStreams.length()) {
                        val stream = audioStreams.optJSONObject(i) ?: continue
                        val url = stream.optString("url", "")
                        if (url.isBlank()) continue
                        val mimeType = stream.optString("mimeType", "audio/mp4")
                        val bitrate = stream.optInt("bitrate", 0)
                        val bonus = if (mimeType.contains("mp4", ignoreCase = true) ||
                            mimeType.contains("m4a", ignoreCase = true)
                        ) 10000 else 0

                        if (bitrate + bonus > bestBitrate) {
                            bestBitrate = bitrate + bonus
                            bestUrl = url
                            bestMime = mimeType
                        }
                    }

                    if (bestUrl != null) {
                        return ResolvedAudioStream(
                            streamUrl = bestUrl,
                            mimeType = bestMime,
                            bitrate = bestBitrate,
                            sourceEngine = "Piped API ($baseUrl)",
                            userAgent = DEFAULT_STREAM_USER_AGENT
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Piped instance $baseUrl failed for $videoId: ${e.message}")
            }
        }
        return null
    }

    private fun extractViaInvidiousApi(videoId: String): ResolvedAudioStream? {
        for (baseUrl in INVIDIOUS_INSTANCES) {
            try {
                val request = Request.Builder()
                    .url("$baseUrl/api/v1/videos/$videoId")
                    .header("User-Agent", DEFAULT_STREAM_USER_AGENT)
                    .header("Accept", "*/*")
                    .get()
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val body = response.body?.string() ?: return@use
                    val json = JSONObject(body)
                    val adaptiveFormats = json.optJSONArray("adaptiveFormats") ?: return@use
                    val resolved = pickHighestBitrateAudio(
                        formats = adaptiveFormats,
                        engineName = "Invidious ($baseUrl)",
                        userAgent = DEFAULT_STREAM_USER_AGENT
                    )
                    if (resolved != null) return resolved
                }
            } catch (e: Exception) {
                Log.w(TAG, "Invidious instance $baseUrl failed: ${e.message}")
            }
        }
        return null
    }

    suspend fun downloadAudioToLocalFile(
        song: Song,
        targetDir: File
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val streamResult = extractBestAudioStream(song.videoId)
            val resolved = streamResult.getOrElse { return@withContext Result.failure(it) }

            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }
            val extension = if (resolved.mimeType.contains("webm") || resolved.mimeType.contains("opus")) {
                "webm"
            } else {
                "m4a"
            }
            val safeId = song.id.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            val destFile = File(targetDir, "${safeId}.$extension")

            val request = Request.Builder()
                .url(resolved.streamUrl)
                .header("User-Agent", DEFAULT_STREAM_USER_AGENT)
                .header("Accept", "*/*")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        RuntimeException("Download failed with HTTP ${response.code}")
                    )
                }
                val body = response.body ?: return@withContext Result.failure(
                    RuntimeException("Empty audio response body")
                )
                FileOutputStream(destFile).use { output ->
                    body.byteStream().copyTo(output)
                }
            }
            Result.success(destFile)
        } catch (e: Exception) {
            Log.e(TAG, "Download audio failed for ${song.title}", e)
            Result.failure(e)
        }
    }

    private data class InnerTubeClientConfig(
        val clientName: String,
        val clientVersion: String,
        val userAgent: String,
        val osName: String,
        val osVersion: String,
        val androidSdkVersion: Int?
    )
}
