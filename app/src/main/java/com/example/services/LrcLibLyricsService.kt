package com.example.services

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs

data class SyncedLyricLine(
    val timeMs: Long,
    val text: String
)

sealed class LyricsUiState {
    data object Idle : LyricsUiState()
    data object Loading : LyricsUiState()
    data class Success(
        val trackTitle: String,
        val artistName: String,
        val syncedLines: List<SyncedLyricLine>,
        val plainLines: List<String>,
        val isSynced: Boolean
    ) : LyricsUiState()
    data object NotFound : LyricsUiState()
}

object LrcLibLyricsService {
    private const val BASE_URL = "https://lrclib.net"
    private const val USER_AGENT = "AADIFY Music Player v4.0 (https://lrclib.net)"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    private val lyricsCache = ConcurrentHashMap<String, LyricsUiState.Success>()

    private val GENERIC_ARTIST_NAMES = setOf(
        "ak music",
        "aadify",
        "unknown",
        "unknown artist",
        "various artists",
        "youtube",
        "official",
        "audio",
        "music"
    )

    suspend fun fetchLyrics(
        songId: String,
        rawTitle: String,
        rawArtist: String,
        durationMs: Long
    ): LyricsUiState = withContext(Dispatchers.IO) {
        val cleanTitle = cleanSongTitle(rawTitle)
        val cleanArtist = cleanArtistName(rawArtist)
        val cacheKey = "${songId}_${cleanTitle.lowercase()}_${cleanArtist.lowercase()}"

        lyricsCache[cacheKey]?.let { return@withContext it }

        if (cleanTitle.isBlank()) {
            return@withContext LyricsUiState.NotFound
        }

        val durationSeconds = if (durationMs > 5_000L) (durationMs / 1000L).toInt() else 0
        val isGenericArtist = cleanArtist.isBlank() || GENERIC_ARTIST_NAMES.contains(cleanArtist.lowercase())

        try {
            // 1. Try exact /api/get when we have a real artist name
            if (!isGenericArtist) {
                val getUrl = buildString {
                    append("$BASE_URL/api/get?track_name=")
                    append(urlEncode(cleanTitle))
                    append("&artist_name=")
                    append(urlEncode(cleanArtist))
                    if (durationSeconds > 0) {
                        append("&duration=$durationSeconds")
                    }
                }
                val directMatch = executeSingleObjectRequest(getUrl)
                if (directMatch != null) {
                    lyricsCache[cacheKey] = directMatch
                    return@withContext directMatch
                }
            }

            // 2. Try /api/search with track_name & artist_name (if artist is not generic)
            if (!isGenericArtist) {
                val searchWithArtistUrl =
                    "$BASE_URL/api/search?track_name=${urlEncode(cleanTitle)}&artist_name=${urlEncode(cleanArtist)}"
                val artistSearchMatch = executeSearchArrayRequest(searchWithArtistUrl, durationSeconds)
                if (artistSearchMatch != null) {
                    lyricsCache[cacheKey] = artistSearchMatch
                    return@withContext artistSearchMatch
                }
            }

            // 3. Try /api/search?q=<cleanTitle + cleanArtist> (if artist is not generic)
            if (!isGenericArtist) {
                val combinedQuery = "$cleanTitle $cleanArtist".trim()
                val combinedSearchUrl = "$BASE_URL/api/search?q=${urlEncode(combinedQuery)}"
                val combinedMatch = executeSearchArrayRequest(combinedSearchUrl, durationSeconds)
                if (combinedMatch != null) {
                    lyricsCache[cacheKey] = combinedMatch
                    return@withContext combinedMatch
                }
            }

            // 4. Try /api/search?q=<cleanTitle> (works even when song artist in DB is "AK Music")
            val titleOnlySearchUrl = "$BASE_URL/api/search?q=${urlEncode(cleanTitle)}"
            val titleOnlyMatch = executeSearchArrayRequest(titleOnlySearchUrl, durationSeconds)
            if (titleOnlyMatch != null) {
                lyricsCache[cacheKey] = titleOnlyMatch
                return@withContext titleOnlyMatch
            }

            // 5. If rawTitle had a hyphen (e.g. "Artist - Song" or "Song - Artist"), try the first segment alone
            val firstSegment = rawTitle.substringBefore("-").substringBefore("|").let { cleanSongTitle(it) }
            if (firstSegment.isNotBlank() && !firstSegment.equals(cleanTitle, ignoreCase = true)) {
                val fallbackUrl = "$BASE_URL/api/search?q=${urlEncode(firstSegment)}"
                val fallbackMatch = executeSearchArrayRequest(fallbackUrl, durationSeconds)
                if (fallbackMatch != null) {
                    lyricsCache[cacheKey] = fallbackMatch
                    return@withContext fallbackMatch
                }
            }
        } catch (_: Exception) {
        }

        LyricsUiState.NotFound
    }

    private fun executeSingleObjectRequest(url: String): LyricsUiState.Success? {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) return null
                val json = JSONObject(body)
                parseLrcLibJsonObject(json)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun executeSearchArrayRequest(url: String, targetDurationSeconds: Int): LyricsUiState.Success? {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) return null
                val array = JSONArray(body)
                if (array.length() == 0) return null

                val candidates = mutableListOf<Pair<Int, LyricsUiState.Success>>()
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val parsed = parseLrcLibJsonObject(obj) ?: continue
                    val itemDuration = obj.optDouble("duration", 0.0).toInt()
                    val durationDiff = if (targetDurationSeconds > 0 && itemDuration > 0) {
                        abs(targetDurationSeconds - itemDuration)
                    } else {
                        30
                    }
                    // Score candidate: prioritize synced lyrics + close duration match
                    val score = (if (parsed.isSynced) 1000 else 200) - durationDiff.coerceAtMost(300) - (i * 5)
                    candidates.add(score to parsed)
                }

                candidates.maxByOrNull { it.first }?.second
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseLrcLibJsonObject(json: JSONObject): LyricsUiState.Success? {
        if (json.optBoolean("instrumental", false)) {
            return null
        }
        val trackName = json.optString("trackName", "").ifBlank { json.optString("name", "") }
        val artistName = json.optString("artistName", "")
        val syncedRaw = json.optString("syncedLyrics", "").takeIf { it != "null" }.orEmpty().trim()
        val plainRaw = json.optString("plainLyrics", "").takeIf { it != "null" }.orEmpty().trim()

        val syncedLines = if (syncedRaw.isNotBlank()) parseSyncedLrc(syncedRaw) else emptyList()
        if (syncedLines.isNotEmpty()) {
            val plainFallback = syncedLines.map { it.text }
            return LyricsUiState.Success(
                trackTitle = trackName,
                artistName = artistName,
                syncedLines = syncedLines,
                plainLines = plainFallback,
                isSynced = true
            )
        }

        if (plainRaw.isNotBlank()) {
            val lines = plainRaw.lines()
                .map { it.trim() }
                .filter { it.isNotBlank() }
            if (lines.isNotEmpty()) {
                return LyricsUiState.Success(
                    trackTitle = trackName,
                    artistName = artistName,
                    syncedLines = emptyList(),
                    plainLines = lines,
                    isSynced = false
                )
            }
        }

        return null
    }

    /**
     * Parses LRC format lines such as:
     * [00:12.34] Tera mera rishta
     * [01:05.120] Ishq de fanniyar
     */
    fun parseSyncedLrc(lrcContent: String): List<SyncedLyricLine> {
        val result = mutableListOf<SyncedLyricLine>()
        val timeTagRegex = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")

        for (rawLine in lrcContent.lines()) {
            val matches = timeTagRegex.findAll(rawLine).toList()
            if (matches.isEmpty()) continue

            val lyricText = rawLine.replace(timeTagRegex, "").trim()
            // Skip empty instrumental markers if blank, or display a musical note indicator
            val displayLyric = lyricText.ifBlank { "♪ • • ♪" }

            for (match in matches) {
                val minutes = match.groupValues[1].toLongOrNull() ?: 0L
                val seconds = match.groupValues[2].toLongOrNull() ?: 0L
                val fractionRaw = match.groupValues.getOrNull(3).orEmpty()
                val millis = when (fractionRaw.length) {
                    1 -> (fractionRaw.toLongOrNull() ?: 0L) * 100L
                    2 -> (fractionRaw.toLongOrNull() ?: 0L) * 10L
                    3 -> fractionRaw.toLongOrNull() ?: 0L
                    else -> 0L
                }
                val totalMs = (minutes * 60_000L) + (seconds * 1_000L) + millis
                result.add(SyncedLyricLine(timeMs = totalMs, text = displayLyric))
            }
        }

        return result.sortedBy { it.timeMs }
    }

    private fun cleanSongTitle(rawTitle: String): String {
        return rawTitle
            .replace(Regex("""\([^)]*\)"""), " ")
            .replace(Regex("""\[[^\]]*]"""), " ")
            .replace(
                Regex(
                    """(?i)\b(official\s+video|official\s+music\s+video|official\s+audio|full\s+video|full\s+song|lyrical\s+video|lyrics|lyrical|hd|4k|8k|audio|video|remastered|slowed|reverb|lofi|remix)\b"""
                ),
                " "
            )
            .substringBefore("|")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .trim('-', '_', ':', '•')
            .trim()
    }

    private fun cleanArtistName(rawArtist: String): String {
        return rawArtist
            .replace(Regex("""\([^)]*\)"""), " ")
            .replace(Regex("""\[[^\]]*]"""), " ")
            .substringBefore(",")
            .substringBefore("&")
            .substringBefore("feat.")
            .substringBefore("ft.")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun urlEncode(value: String): String {
        return try {
            URLEncoder.encode(value, "UTF-8")
        } catch (_: Exception) {
            value
        }
    }
}
