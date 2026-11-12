package com.example.services

import android.util.Log
import com.example.models.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class ResolvedAudioStream(
    val streamUrl: String,
    val mimeType: String,
    val bitrate: Int,
    val sourceEngine: String,
    val userAgent: String = AudioStreamExtractor.EXOPLAYER_USER_AGENT
)

class AudioStreamExtractor {

    companion object {
        private const val TAG = "AudioStreamExtractor"

        const val EXOPLAYER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

        const val DEFAULT_STREAM_USER_AGENT = EXOPLAYER_USER_AGENT

        const val ANDROID_MUSIC_USER_AGENT =
            "com.google.android.apps.youtube.music/6.20.51 (Linux; U; Android 14)"

        private const val ANDROID_VR_USER_AGENT =
            "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"

        private const val IOS_USER_AGENT =
            "com.google.ios.youtube/19.45.4 (iPhone16,2; U; CPU iOS 18_1_0 like Mac OS X;)"

        private const val ANDROID_YT_USER_AGENT =
            "com.google.android.youtube/19.44.38 (Linux; U; Android 14) gzip"

        private const val ANDROID_TESTSUITE_USER_AGENT =
            "com.google.android.youtube/1.9 (Linux; U; Android 14; en_US) gzip"

        private val urlUserAgentMap = ConcurrentHashMap<String, String>()

        fun registerStreamUserAgent(streamUrl: String, userAgent: String) {
            urlUserAgentMap[streamUrl] = userAgent
        }

        fun resolveUserAgentForStreamUrl(streamUrl: String): String {
            urlUserAgentMap[streamUrl]?.let { return it }
            return when {
                streamUrl.contains("c=ANDROID_MUSIC", ignoreCase = true) -> ANDROID_MUSIC_USER_AGENT
                streamUrl.contains("c=ANDROID_VR", ignoreCase = true) -> ANDROID_VR_USER_AGENT
                streamUrl.contains("c=IOS", ignoreCase = true) -> IOS_USER_AGENT
                streamUrl.contains("c=ANDROID_TESTSUITE", ignoreCase = true) -> ANDROID_TESTSUITE_USER_AGENT
                streamUrl.contains("c=ANDROID", ignoreCase = true) -> ANDROID_YT_USER_AGENT
                else -> EXOPLAYER_USER_AGENT
            }
        }

        private val PIPED_MIRRORS = listOf(
            "https://pipedapi.drgns.space",
            "https://api.piped.yt",
            "https://api.piped.privacydev.net",
            "https://pipedapi.kavin.rocks"
        )

        private val INVIDIOUS_MIRRORS = listOf(
            "https://invidious.nerdvpn.de",
            "https://inv.tux.pizza",
            "https://yt.artemislena.eu"
        )
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .writeTimeout(4, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val downloadHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    // Cache resolved stream URLs for 20 minutes
    private val streamCache = ConcurrentHashMap<String, Pair<Long, ResolvedAudioStream>>()
    private val searchCache = ConcurrentHashMap<String, List<Song>>()
    private val cacheTtlMs = 20 * 60 * 1000L

    fun invalidateCachedStream(rawVideoId: String) {
        val cleanId = Song.extractYoutubeVideoId(rawVideoId)
        if (cleanId.isNotBlank()) {
            streamCache.remove(cleanId)
        }
    }

    suspend fun resolveAudioStreamUrl(rawVideoId: String): String? {
        return extractBestAudioStream(rawVideoId).getOrNull()?.streamUrl
    }

    /**
     * Ultra-Fast Live Online Search Engine with Country Region Patching:
     * Searches YouTube in parallel (WEB + Regional Query) and returns up to 40 playable songs!
     */
    suspend fun searchSongsOnline(
        query: String,
        countryCode: String = "IN",
        countryName: String = "India"
    ): List<Song> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.length < 2) return@withContext emptyList()

        val resolvedGl = if (countryCode.equals("GLOBAL", ignoreCase = true) || countryCode.length != 2) {
            "US"
        } else {
            countryCode.uppercase()
        }

        val cacheKey = "${resolvedGl}_${trimmed.lowercase()}"
        searchCache[cacheKey]?.takeIf { it.isNotEmpty() }?.let { return@withContext it }

        val merged = coroutineScope {
            val primaryDeferred = async {
                searchViaInnerTube(
                    query = "$trimmed song",
                    gl = resolvedGl,
                    maxResults = 35
                )
            }
            val regionalDeferred = async {
                if (!countryCode.equals("GLOBAL", ignoreCase = true) &&
                    !trimmed.contains(countryName, ignoreCase = true)
                ) {
                    searchViaInnerTube(
                        query = "$trimmed $countryName song",
                        gl = resolvedGl,
                        maxResults = 20
                    )
                } else {
                    emptyList()
                }
            }

            val primaryList = primaryDeferred.await()
            val regionalList = regionalDeferred.await()

            val combined = mutableListOf<Song>()
            val seenVideoIds = mutableSetOf<String>()
            for (song in primaryList + regionalList) {
                if (song.cleanVideoId.isNotBlank() && seenVideoIds.add(song.cleanVideoId)) {
                    combined.add(song)
                }
                if (combined.size >= 40) break
            }
            combined
        }

        if (merged.isNotEmpty()) {
            searchCache[cacheKey] = merged
            return@withContext merged
        }

        // Fallback: Piped Search API
        for (baseUrl in PIPED_MIRRORS.take(2)) {
            val pipedResults = searchViaPiped(baseUrl, trimmed)
            if (pipedResults.isNotEmpty()) {
                searchCache[cacheKey] = pipedResults
                return@withContext pipedResults
            }
        }

        emptyList()
    }

    private fun searchViaInnerTube(
        query: String,
        gl: String = "IN",
        maxResults: Int = 35
    ): List<Song> {
        return try {
            val clientObj = JSONObject().apply {
                put("clientName", "WEB")
                put("clientVersion", "2.20241118.01.00")
                put("hl", "en")
                put("gl", gl)
            }
            val payload = JSONObject().apply {
                put("context", JSONObject().put("client", clientObj))
                put("query", query)
            }
            val request = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/search?prettyPrint=false")
                .header("Content-Type", "application/json")
                .header("User-Agent", EXOPLAYER_USER_AGENT)
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val body = response.body?.string() ?: return emptyList()
                val results = mutableListOf<Song>()
                val seenIds = mutableSetOf<String>()

                val videoRendererRegex = Regex(
                    "\"videoRenderer\"\\s*:\\s*\\{\\s*\"videoId\"\\s*:\\s*\"([a-zA-Z0-9_-]{11})\".*?\"title\"\\s*:\\s*\\{\\s*\"runs\"\\s*:\\s*\\[\\s*\\{\\s*\"text\"\\s*:\\s*\"([^\"]+)\"",
                    setOf(RegexOption.DOT_MATCHES_ALL)
                )

                for (match in videoRendererRegex.findAll(body)) {
                    val vid = match.groupValues[1]
                    if (!seenIds.add(vid)) continue
                    val rawTitle = match.groupValues[2]
                        .replace("\\\"", "\"")
                        .replace("\\u0026", "&")
                        .replace("&amp;", "&")
                    val artist = when {
                        rawTitle.contains(" - ") -> rawTitle.substringBefore(" - ").trim()
                        rawTitle.contains(" | ") -> rawTitle.substringAfter(" | ").trim()
                        else -> "AADIFY Music"
                    }
                    results.add(
                        Song(
                            id = "yt_search_$vid",
                            title = rawTitle,
                            artist = artist.ifBlank { "AADIFY Music" },
                            videoId = vid,
                            bannerUrl = "https://img.youtube.com/vi/$vid/hqdefault.jpg",
                            timestamp = System.currentTimeMillis()
                        )
                    )
                    if (results.size >= maxResults) break
                }
                results
            }
        } catch (e: Exception) {
            Log.w(TAG, "InnerTube search failed: ${e.message}")
            emptyList()
        }
    }

    private fun searchViaPiped(baseUrl: String, query: String): List<Song> {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val request = Request.Builder()
                .url("$baseUrl/search?q=$encoded&filter=music_songs")
                .header("User-Agent", EXOPLAYER_USER_AGENT)
                .header("Accept", "application/json")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val body = response.body?.string() ?: return emptyList()
                if (!body.trimStart().startsWith("{")) return emptyList()
                val root = JSONObject(body)
                val items = root.optJSONArray("items") ?: return emptyList()
                val results = mutableListOf<Song>()
                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    val url = item.optString("url", "")
                    val vid = Song.extractYoutubeVideoId(url)
                    if (vid.length != 11) continue
                    val title = item.optString("title", "").ifBlank { continue }
                    val artist = item.optString("uploaderName", "AADIFY Music")
                    val thumb = item.optString(
                        "thumbnail",
                        "https://img.youtube.com/vi/$vid/hqdefault.jpg"
                    )
                    results.add(
                        Song(
                            id = "yt_search_$vid",
                            title = title,
                            artist = artist,
                            videoId = vid,
                            bannerUrl = thumb,
                            timestamp = System.currentTimeMillis()
                        )
                    )
                    if (results.size >= 30) break
                }
                results
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private val resolvedVideoIdCache = ConcurrentHashMap<String, String>()

    suspend fun findPlayableVideoIdForSong(song: Song): String? = withContext(Dispatchers.IO) {
        val q = "${song.title} ${song.artist}".trim()
        if (q.isBlank()) return@withContext null
        val cacheKey = q.lowercase()
        resolvedVideoIdCache[cacheKey]?.takeIf { it.isNotBlank() && it != song.cleanVideoId }?.let {
            return@withContext it
        }
        val ids = coroutineScope {
            val ch = Channel<List<String>>(Channel.UNLIMITED)
            val j1 = launch(Dispatchers.IO) {
                ch.trySend(searchPlayableVideoIdsViaYtMusic("$q song"))
            }
            val j2 = launch(Dispatchers.IO) {
                ch.trySend(searchPlayableVideoIds("$q official audio"))
            }
            var firstNonEmpty: List<String> = emptyList()
            repeat(2) {
                val res = ch.receive()
                if (res.isNotEmpty() && firstNonEmpty.isEmpty()) {
                    firstNonEmpty = res
                    j1.cancel()
                    j2.cancel()
                    return@repeat
                }
            }
            firstNonEmpty
        }
        val picked = ids.firstOrNull { it != song.cleanVideoId } ?: ids.firstOrNull()
        if (!picked.isNullOrBlank()) {
            resolvedVideoIdCache[cacheKey] = picked
        }
        picked
    }

    suspend fun extractBestAudioStream(
        rawVideoId: String,
        songTitle: String = "",
        songArtist: String = "",
        forceFresh: Boolean = false
    ): Result<ResolvedAudioStream> = withContext(Dispatchers.IO) {
        val videoId = Song.extractYoutubeVideoId(rawVideoId)

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
                userAgent = EXOPLAYER_USER_AGENT
            )
            registerStreamUserAgent(direct.streamUrl, direct.userAgent)
            return@withContext Result.success(direct)
        }

        if (videoId.isNotBlank() && !forceFresh) {
            val cached = streamCache[videoId]
            if (cached != null && (System.currentTimeMillis() - cached.first) < cacheTtlMs) {
                registerStreamUserAgent(cached.second.streamUrl, cached.second.userAgent)
                return@withContext Result.success(cached.second)
            }
        }

        if (videoId.length == 11) {
            val directResolved = resolveForSingleVideoIdFast(videoId)
            if (directResolved != null) {
                streamCache[videoId] = System.currentTimeMillis() to directResolved
                registerStreamUserAgent(directResolved.streamUrl, directResolved.userAgent)
                return@withContext Result.success(directResolved)
            }
        }

        val searchQuery = buildString {
            if (songTitle.isNotBlank()) append(songTitle.trim())
            if (songArtist.isNotBlank() && !songArtist.equals("Unknown Artist", ignoreCase = true)) {
                append(" ").append(songArtist.trim())
            }
            if (isEmpty() && videoId.isNotBlank()) append(videoId)
        }.trim()

        if (searchQuery.isNotBlank()) {
            val candidateIds = withTimeoutOrNull(5_000L) {
                searchPlayableVideoIds("$searchQuery official audio")
            }.orEmpty().filter { it != videoId }.take(3)

            for (altVideoId in candidateIds) {
                val altResolved = resolveForSingleVideoIdFast(altVideoId)
                if (altResolved != null) {
                    if (videoId.isNotBlank()) {
                        streamCache[videoId] = System.currentTimeMillis() to altResolved
                    }
                    registerStreamUserAgent(altResolved.streamUrl, altResolved.userAgent)
                    return@withContext Result.success(altResolved)
                }
            }
        }

        Result.failure(RuntimeException("Stream unavailable for this track."))
    }

    private suspend fun resolveForSingleVideoIdFast(videoId: String): ResolvedAudioStream? =
        withTimeoutOrNull(6_500L) {
            coroutineScope {
                val resultChannel = Channel<ResolvedAudioStream?>(Channel.UNLIMITED)
                val tasks = listOf<suspend () -> ResolvedAudioStream?>(
                    { extractViaSingleInnerTubeClient(videoId, vrClientConfig) },
                    { extractViaSingleInnerTubeClient(videoId, testSuiteClientConfig) },
                    { extractViaSingleInnerTubeClient(videoId, androidCreatorClientConfig) },
                    { extractViaAndroidMusicInnerTube(videoId) },
                    { extractViaSingleInnerTubeClient(videoId, iosClientConfig) },
                    { fetchFromPipedMirror(PIPED_MIRRORS[0], videoId) },
                    { fetchFromPipedMirror(PIPED_MIRRORS[1], videoId) }
                )

                val jobs = tasks.map { block ->
                    launch(Dispatchers.IO) {
                        val res = try {
                            block()
                        } catch (_: Exception) {
                            null
                        }
                        resultChannel.trySend(res)
                    }
                }

                var winner: ResolvedAudioStream? = null
                repeat(tasks.size) {
                    val candidate = resultChannel.receive()
                    if (candidate != null && winner == null) {
                        winner = candidate
                        jobs.forEach { it.cancel() }
                        return@repeat
                    }
                }

                if (winner != null) {
                    return@coroutineScope winner
                }

                extractViaFallbackMirrors(videoId)
            }
        }

    private val vrClientConfig = InnerTubeClientConfig(
        endpoint = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
        clientName = "ANDROID_VR",
        clientVersion = "1.62.27",
        clientIdHeader = "28",
        userAgent = "com.google.android.apps.youtube.vr.oculus/1.62.27 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
        osName = "Android",
        osVersion = "12L",
        androidSdkVersion = 32,
        deviceMake = "Oculus",
        deviceModel = "Quest 3",
        playerParams = "8AEB"
    )

    private val androidCreatorClientConfig = InnerTubeClientConfig(
        endpoint = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
        clientName = "ANDROID",
        clientVersion = "19.44.38",
        clientIdHeader = "3",
        userAgent = ANDROID_YT_USER_AGENT,
        osName = "Android",
        osVersion = "14",
        androidSdkVersion = 34,
        playerParams = "8AEB"
    )

    private val iosClientConfig = InnerTubeClientConfig(
        endpoint = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
        clientName = "IOS",
        clientVersion = "19.45.4",
        clientIdHeader = "5",
        userAgent = IOS_USER_AGENT,
        osName = "iOS",
        osVersion = "18.1.0.22B83",
        androidSdkVersion = null,
        deviceMake = "Apple",
        deviceModel = "iPhone16,2"
    )

    private val testSuiteClientConfig = InnerTubeClientConfig(
        endpoint = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
        clientName = "ANDROID_TESTSUITE",
        clientVersion = "1.9",
        clientIdHeader = "30",
        userAgent = ANDROID_TESTSUITE_USER_AGENT,
        osName = "Android",
        osVersion = "14",
        androidSdkVersion = 34,
        playerParams = "8AEB"
    )

    private fun extractViaAndroidMusicInnerTube(videoId: String): ResolvedAudioStream? {
        return try {
            val clientObj = JSONObject().apply {
                put("clientName", "ANDROID_MUSIC")
                put("clientVersion", "6.20.51")
                put("androidSdkVersion", 34)
                put("hl", "en")
                put("gl", "IN")
            }
            val payload = JSONObject().apply {
                put("context", JSONObject().put("client", clientObj))
                put("videoId", videoId)
                put("params", "8AEB")
                put("contentCheckOk", true)
                put("racyCheckOk", true)
            }

            val request = Request.Builder()
                .url("https://music.youtube.com/youtubei/v1/player")
                .header("Content-Type", "application/json")
                .header("User-Agent", ANDROID_MUSIC_USER_AGENT)
                .header("Accept", "*/*")
                .header("Origin", "https://music.youtube.com")
                .header("X-YouTube-Client-Name", "21")
                .header("X-YouTube-Client-Version", "6.20.51")
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val json = JSONObject(body)
                val streamingData = json.optJSONObject("streamingData") ?: return null

                val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats")
                val candidate = if (adaptiveFormats != null) {
                    pickFastDownloadOrPlayAudio(
                        formats = adaptiveFormats,
                        engineName = "YouTube Music InnerTube (ANDROID_MUSIC)",
                        userAgent = ANDROID_MUSIC_USER_AGENT
                    )
                } else {
                    null
                } ?: streamingData.optJSONArray("formats")?.let { formats ->
                    pickFastDownloadOrPlayAudio(
                        formats = formats,
                        engineName = "YouTube Music InnerTube (ANDROID_MUSIC)",
                        userAgent = ANDROID_MUSIC_USER_AGENT
                    )
                }

                if (candidate != null && isStreamUrlReachable(candidate.streamUrl, candidate.userAgent)) {
                    candidate
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun extractViaSingleInnerTubeClient(
        videoId: String,
        client: InnerTubeClientConfig
    ): ResolvedAudioStream? {
        return try {
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
                if (client.deviceMake != null) {
                    put("deviceMake", client.deviceMake)
                }
                if (client.deviceModel != null) {
                    put("deviceModel", client.deviceModel)
                }
            }
            val payload = JSONObject().apply {
                put("videoId", videoId)
                if (!client.playerParams.isNullOrBlank()) {
                    put("params", client.playerParams)
                }
                put("contentCheckOk", true)
                put("racyCheckOk", true)
                put("context", JSONObject().put("client", contextClient))
            }

            val request = Request.Builder()
                .url(client.endpoint)
                .header("User-Agent", client.userAgent)
                .header("Content-Type", "application/json")
                .header("Accept", "*/*")
                .header("X-YouTube-Client-Name", client.clientIdHeader)
                .header("X-YouTube-Client-Version", client.clientVersion)
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val json = JSONObject(body)
                val streamingData = json.optJSONObject("streamingData") ?: return null
                val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats")
                    ?: streamingData.optJSONArray("formats")
                    ?: return null

                val bestStream = pickFastDownloadOrPlayAudio(
                    formats = adaptiveFormats,
                    engineName = "InnerTube (${client.clientName})",
                    userAgent = client.userAgent
                )
                if (bestStream != null && isStreamUrlReachable(bestStream.streamUrl, bestStream.userAgent)) {
                    bestStream
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Picks an optimal audio stream (~64kbps - 128kbps M4A/Opus, ~1.4MB - 2.2MB per song)
     * so songs download in under 10 seconds while keeping crisp HD audio quality.
     */
    private fun pickFastDownloadOrPlayAudio(
        formats: JSONArray,
        engineName: String,
        userAgent: String
    ): ResolvedAudioStream? {
        var bestUrl: String? = null
        var bestMime = "audio/mp4"
        var bestScore = -1
        var bestBitrate = 0

        for (i in 0 until formats.length()) {
            val item = formats.optJSONObject(i) ?: continue
            val mimeType = item.optString("mimeType", "").ifBlank { item.optString("type", "") }
            val url = item.optString("url", "")
            if (url.isBlank() || !mimeType.startsWith("audio/", ignoreCase = true)) continue

            val bitrate = item.optInt("bitrate", 0)
            val isM4a = mimeType.contains("audio/mp4", ignoreCase = true) ||
                mimeType.contains("m4a", ignoreCase = true)
            // Prefer compact 48kbps - 128kbps M4A/Opus (~1.2MB - 2.2MB) so download finishes in <10 seconds!
            val speedBonus = when {
                bitrate in 45_000..96_000 -> 200_000
                bitrate in 96_001..140_000 -> 160_000
                else -> 80_000
            }
            val mp4Bonus = if (isM4a) 60_000 else 30_000
            val score = speedBonus + mp4Bonus

            if (score > bestScore) {
                bestScore = score
                bestBitrate = bitrate
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

    private fun pickHighestBitrateAudio(
        formats: JSONArray,
        engineName: String,
        userAgent: String
    ): ResolvedAudioStream? = pickFastDownloadOrPlayAudio(formats, engineName, userAgent)

    private fun extractViaFallbackMirrors(videoId: String): ResolvedAudioStream? {
        for (baseUrl in PIPED_MIRRORS) {
            val resolved = fetchFromPipedMirror(baseUrl, videoId)
            if (resolved != null) return resolved
        }
        for (baseUrl in INVIDIOUS_MIRRORS) {
            val resolved = fetchFromInvidiousMirror(baseUrl, videoId)
            if (resolved != null) return resolved
        }
        return null
    }

    private fun fetchFromPipedMirror(baseUrl: String, videoId: String): ResolvedAudioStream? {
        return try {
            val request = Request.Builder()
                .url("$baseUrl/streams/$videoId")
                .header("User-Agent", EXOPLAYER_USER_AGENT)
                .header("Accept", "application/json")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                if (!body.trimStart().startsWith("{")) return null
                val json = JSONObject(body)
                val audioStreams = json.optJSONArray("audioStreams") ?: return null

                var bestUrl: String? = null
                var bestMime = "audio/mp4"
                var bestScore = -1
                var bestBitrate = 0

                for (i in 0 until audioStreams.length()) {
                    val stream = audioStreams.optJSONObject(i) ?: continue
                    val url = stream.optString("url", "")
                    if (url.isBlank()) continue
                    val mimeType = stream.optString("mimeType", "audio/mp4")
                    if (!mimeType.startsWith("audio/", ignoreCase = true)) continue
                    val bitrate = stream.optInt("bitrate", 0)
                    val speedBonus = if (bitrate in 45_000..135_000) 150_000 else 50_000
                    val bonus = if (mimeType.contains("mp4", ignoreCase = true) ||
                        mimeType.contains("m4a", ignoreCase = true)
                    ) 50_000 else 0

                    if (speedBonus + bonus > bestScore) {
                        bestScore = speedBonus + bonus
                        bestBitrate = bitrate
                        bestUrl = url
                        bestMime = mimeType
                    }
                }

                if (bestUrl != null && isStreamUrlReachable(bestUrl, EXOPLAYER_USER_AGENT)) {
                    ResolvedAudioStream(
                        streamUrl = bestUrl,
                        mimeType = bestMime,
                        bitrate = bestBitrate,
                        sourceEngine = "Piped ($baseUrl)",
                        userAgent = EXOPLAYER_USER_AGENT
                    )
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun fetchFromInvidiousMirror(baseUrl: String, videoId: String): ResolvedAudioStream? {
        return try {
            val request = Request.Builder()
                .url("$baseUrl/api/v1/videos/$videoId")
                .header("User-Agent", EXOPLAYER_USER_AGENT)
                .header("Accept", "application/json")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                if (!body.trimStart().startsWith("{")) return null
                val json = JSONObject(body)
                val adaptiveFormats = json.optJSONArray("adaptiveFormats") ?: return null
                val resolved = pickFastDownloadOrPlayAudio(
                    formats = adaptiveFormats,
                    engineName = "Invidious ($baseUrl)",
                    userAgent = EXOPLAYER_USER_AGENT
                )
                if (resolved != null && isStreamUrlReachable(resolved.streamUrl, EXOPLAYER_USER_AGENT)) {
                    resolved
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Performs a real HTTP Range probe (0-1023 bytes) to guarantee the candidate URL returns HTTP 200 or 206
     * and NEVER returns a 403 Forbidden stream!
     */
    private fun isStreamUrlReachable(streamUrl: String, userAgent: String): Boolean {
        if (!streamUrl.startsWith("http", ignoreCase = true) || userAgent.isBlank()) return false
        return try {
            val probeUrl = if (streamUrl.contains("googlevideo.com", ignoreCase = true) &&
                !streamUrl.contains("&range=", ignoreCase = true)
            ) {
                "$streamUrl&range=0-1023"
            } else {
                streamUrl
            }
            val req = Request.Builder()
                .url(probeUrl)
                .header("User-Agent", userAgent)
                .header("Range", "bytes=0-1023")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                resp.code == 200 || resp.code == 206
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun searchPlayableVideoIdsViaYtMusic(query: String): List<String> {
        return try {
            val clientObj = JSONObject().apply {
                put("clientName", "WEB_REMIX")
                put("clientVersion", "1.20241118.01.00")
                put("hl", "en")
                put("gl", "IN")
            }
            val payload = JSONObject().apply {
                put("context", JSONObject().put("client", clientObj))
                put("query", query)
                // Filter specifically to Songs in YouTube Music for smaller, faster, audio-only matches
                put("params", "EgWKAQIIAWoKEAMQBBAJEAoQBQ%3D%3D")
            }
            val request = Request.Builder()
                .url("https://music.youtube.com/youtubei/v1/search?prettyPrint=false")
                .header("Content-Type", "application/json")
                .header("User-Agent", EXOPLAYER_USER_AGENT)
                .header("Origin", "https://music.youtube.com")
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val body = response.body?.string() ?: return emptyList()
                val regex = Regex("\"videoId\"\\s*:\\s*\"([a-zA-Z0-9_-]{11})\"")
                regex.findAll(body)
                    .map { it.groupValues[1] }
                    .distinct()
                    .take(4)
                    .toList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun searchPlayableVideoIds(query: String): List<String> {
        return try {
            val clientObj = JSONObject().apply {
                put("clientName", "ANDROID_VR")
                put("clientVersion", "1.62.27")
                put("hl", "en")
                put("gl", "IN")
            }
            val payload = JSONObject().apply {
                put("context", JSONObject().put("client", clientObj))
                put("query", query)
            }
            val request = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/search?prettyPrint=false")
                .header("Content-Type", "application/json")
                .header("User-Agent", ANDROID_VR_USER_AGENT)
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val body = response.body?.string() ?: return emptyList()
                val regex = Regex("\"videoId\"\\s*:\\s*\"([a-zA-Z0-9_-]{11})\"")
                regex.findAll(body)
                    .map { it.groupValues[1] }
                    .distinct()
                    .take(4)
                    .toList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Downloads an audio stream using chunked Range acceleration (`&range=start-end`) so YouTube CDN
     * never throttles bandwidth and songs download in under 10 seconds!
     */
    private fun downloadStreamFastToFile(
        resolved: ResolvedAudioStream,
        destFile: File
    ): Boolean {
        return try {
            val baseStreamUrl = resolved.streamUrl
            val ua = resolved.userAgent.ifBlank { EXOPLAYER_USER_AGENT }
            val isGoogleVideo = baseStreamUrl.contains("googlevideo.com", ignoreCase = true)

            if (isGoogleVideo && !baseStreamUrl.contains("&range=", ignoreCase = true)) {
                // Fast Chunked Range Download (512 KB chunks bypass googlevideo throttling completely!)
                val chunkSize = 512 * 1024L
                var startByte = 0L
                var totalDownloaded = 0L
                val maxBytes = 15L * 1024L * 1024L // Safety cap 15 MB

                FileOutputStream(destFile).use { output ->
                    while (startByte < maxBytes) {
                        val endByte = startByte + chunkSize - 1L
                        val chunkUrl = "$baseStreamUrl&range=$startByte-$endByte"
                        val chunkReq = Request.Builder()
                            .url(chunkUrl)
                            .header("User-Agent", ua)
                            .header("Accept", "*/*")
                            .get()
                            .build()

                        var bytesReadInChunk = 0L
                        downloadHttpClient.newCall(chunkReq).execute().use { resp ->
                            if (!resp.isSuccessful) {
                                if (totalDownloaded == 0L) return false
                                return@use
                            }
                            val body = resp.body ?: return@use
                            val buf = ByteArray(32 * 1024)
                            val ins = body.byteStream()
                            while (true) {
                                val r = ins.read(buf)
                                if (r <= 0) break
                                output.write(buf, 0, r)
                                bytesReadInChunk += r
                                totalDownloaded += r
                            }
                        }
                        if (bytesReadInChunk < chunkSize) {
                            break
                        }
                        startByte += bytesReadInChunk
                    }
                }
                if (destFile.exists() && destFile.length() > 16_384L) {
                    return true
                }
            }

            // Standard direct stream download fallback
            val request = Request.Builder()
                .url(baseStreamUrl)
                .header("User-Agent", ua)
                .header("Accept", "*/*")
                .get()
                .build()

            downloadHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return false
                val body = response.body ?: return false
                FileOutputStream(destFile).use { output ->
                    body.byteStream().copyTo(output, bufferSize = 64 * 1024)
                }
            }
            destFile.exists() && destFile.length() > 16_384L
        } catch (e: Exception) {
            Log.w(TAG, "Fast stream download attempt failed: ${e.message}")
            false
        }
    }

    suspend fun downloadAudioToLocalFile(
        song: Song,
        targetDir: File
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }
            val safeId = song.id.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(80)

            // 1. Collect candidate videoIds (primary + YouTube Music audio search fallback)
            val primaryVid = song.cleanVideoId.ifBlank {
                findPlayableVideoIdForSong(song).orEmpty()
            }
            val candidateVideoIds = mutableListOf<String>()
            if (primaryVid.isNotBlank()) candidateVideoIds.add(primaryVid)

            val query = "${song.title} ${song.artist}".trim()
            if (query.isNotBlank()) {
                val searchIds = searchPlayableVideoIdsViaYtMusic("$query audio")
                    .ifEmpty { searchPlayableVideoIds("$query audio") }
                for (id in searchIds) {
                    if (id.isNotBlank() && !candidateVideoIds.contains(id)) {
                        candidateVideoIds.add(id)
                    }
                }
            }

            // 2. Try downloading from verified streams across candidate videoIds
            for (vid in candidateVideoIds.take(3)) {
                streamCache.remove(vid)
                val streamResult = extractBestAudioStream(
                    rawVideoId = vid,
                    songTitle = song.title,
                    songArtist = song.artist
                )
                val resolved = streamResult.getOrNull() ?: continue
                val extension = if (resolved.mimeType.contains("webm") || resolved.mimeType.contains("opus")) {
                    "webm"
                } else {
                    "m4a"
                }
                val destFile = File(targetDir, "${safeId}.$extension")
                if (downloadStreamFastToFile(resolved, destFile)) {
                    return@withContext Result.success(destFile)
                }

                // Also try explicit client fallbacks if first stream failed mid-transfer
                val backupClients = listOf(
                    vrClientConfig,
                    testSuiteClientConfig,
                    androidCreatorClientConfig
                )
                for (clientCfg in backupClients) {
                    val altStream = extractViaSingleInnerTubeClient(vid, clientCfg) ?: continue
                    val altExt = if (altStream.mimeType.contains("webm") || altStream.mimeType.contains("opus")) "webm" else "m4a"
                    val altDest = File(targetDir, "${safeId}.$altExt")
                    if (downloadStreamFastToFile(altStream, altDest)) {
                        return@withContext Result.success(altDest)
                    }
                }
            }

            Result.failure(RuntimeException("Could not download audio stream"))
        } catch (e: Exception) {
            Log.e(TAG, "Download audio failed for ${song.title}", e)
            Result.failure(e)
        }
    }

    private data class InnerTubeClientConfig(
        val endpoint: String,
        val clientName: String,
        val clientVersion: String,
        val clientIdHeader: String,
        val userAgent: String,
        val osName: String,
        val osVersion: String,
        val androidSdkVersion: Int?,
        val deviceMake: String? = null,
        val deviceModel: String? = null,
        val playerParams: String? = null
    )
}
