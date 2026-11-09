package com.example.services

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
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
    private const val USER_AGENT = "AADIFY Music Player v5.0 (https://lrclib.net)"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val lyricsCache = ConcurrentHashMap<String, LyricsUiState.Success>()

    private val GENERIC_ARTIST_NAMES = setOf(
        "ak music",
        "aadify",
        "unknown",
        "unknown artist",
        "various artists",
        "device audio",
        "youtube",
        "official",
        "audio",
        "music",
        "t-series",
        "tseries",
        "zee music company",
        "sony music india",
        "saregama",
        "tips official",
        "yrf",
        "speed records",
        "desi melodies",
        "white hill music"
    )

    suspend fun fetchLyrics(
        songId: String,
        rawTitle: String,
        rawArtist: String,
        durationMs: Long
    ): LyricsUiState = withContext(Dispatchers.IO) {
        val cleanTitle = cleanSongTitle(rawTitle)
        val cleanArtist = cleanArtistName(rawArtist)
        val cacheKey = "${songId}_${cleanTitle.lowercase(Locale.US)}_${cleanArtist.lowercase(Locale.US)}"

        lyricsCache[cacheKey]?.let { return@withContext it }

        if (cleanTitle.isBlank() && rawTitle.isBlank()) {
            return@withContext LyricsUiState.NotFound
        }

        val durationSeconds = if (durationMs > 5_000L) (durationMs / 1000L).toInt() else 0
        val isGenericArtist = cleanArtist.isBlank() || GENERIC_ARTIST_NAMES.contains(cleanArtist.lowercase(Locale.US))

        // Extract candidate (track, artist) pairs from titles like "Anuv Jain - INAAM" or "Sheesha | Mitta Ror"
        val candidatePairs = buildCandidateTrackArtistPairs(rawTitle, cleanTitle, cleanArtist, isGenericArtist)

        try {
            // Step 1: Fast full-text search queries (/api/search?q=...) — finds both "Artist - Song" and "Song" in 1 request!
            val searchQueries = LinkedHashSet<String>()
            if (!isGenericArtist && cleanTitle.isNotBlank()) {
                searchQueries.add("$cleanTitle $cleanArtist".trim())
            }
            if (cleanTitle.isNotBlank()) {
                // Replace hyphens with space so "Anuv Jain - INAAM" becomes "Anuv Jain INAAM"
                val spaceNormalized = cleanTitle.replace(Regex("""\s*[-–—:]\s*"""), " ").replace(Regex("""\s+"""), " ").trim()
                if (spaceNormalized.isNotBlank()) {
                    searchQueries.add(spaceNormalized)
                }
                searchQueries.add(cleanTitle)
            }
            for ((trackCand, artistCand) in candidatePairs) {
                if (trackCand.isNotBlank() && artistCand.isNotBlank()) {
                    searchQueries.add("$trackCand $artistCand".trim())
                }
            }
            val coreStrippedTitle = stripExtraSubtitleWords(cleanTitle)
            if (coreStrippedTitle.isNotBlank() && coreStrippedTitle.length >= 2) {
                if (!isGenericArtist) {
                    searchQueries.add("$coreStrippedTitle $cleanArtist".trim())
                }
                searchQueries.add(coreStrippedTitle)
            }
            for ((trackCand, _) in candidatePairs) {
                if (trackCand.isNotBlank() && trackCand.length >= 2) {
                    searchQueries.add(trackCand)
                }
            }

            // Try top 4 distinct search queries on LRCLIB /api/search?q=...
            for (query in searchQueries.take(4)) {
                if (query.isBlank()) continue
                val url = "$BASE_URL/api/search?q=${urlEncode(query)}"
                val match = executeSearchArrayRequest(url, durationSeconds, query, if (isGenericArtist) "" else cleanArtist)
                if (match != null) {
                    lyricsCache[cacheKey] = match
                    return@withContext match
                }
            }

            // Step 2: Try exact /api/get for top (track, artist) pairs
            for ((trackCand, artistCand) in candidatePairs.take(2)) {
                if (trackCand.isNotBlank() && artistCand.isNotBlank()) {
                    val getUrl = buildString {
                        append("$BASE_URL/api/get?track_name=")
                        append(urlEncode(trackCand))
                        append("&artist_name=")
                        append(urlEncode(artistCand))
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
            }

            // Step 3: Try track_name-only search on LRCLIB for the main song name
            val trackOnlyTarget = candidatePairs.firstOrNull()?.first?.ifBlank { coreStrippedTitle } ?: coreStrippedTitle
            if (trackOnlyTarget.isNotBlank()) {
                val url = "$BASE_URL/api/search?track_name=${urlEncode(trackOnlyTarget)}"
                val match = executeSearchArrayRequest(url, durationSeconds, trackOnlyTarget, if (isGenericArtist) "" else cleanArtist)
                if (match != null) {
                    lyricsCache[cacheKey] = match
                    return@withContext match
                }
            }

            // Step 4: Fallback to lyrics.ovh API if LRCLIB had no entry
            for ((trackCand, artistCand) in candidatePairs.take(2)) {
                if (trackCand.isNotBlank() && artistCand.isNotBlank()) {
                    val ovhMatch = fetchFromLyricsOvhFallback(trackCand, artistCand)
                    if (ovhMatch != null) {
                        lyricsCache[cacheKey] = ovhMatch
                        return@withContext ovhMatch
                    }
                }
            }
        } catch (_: Exception) {
        }

        LyricsUiState.NotFound
    }

    private fun buildCandidateTrackArtistPairs(
        rawTitle: String,
        cleanTitle: String,
        cleanArtist: String,
        isGenericArtist: Boolean
    ): List<Pair<String, String>> {
        val pairs = mutableListOf<Pair<String, String>>()

        val prePipe = rawTitle.substringBefore("|").trim()
        val segments = prePipe.split(Regex("""\s*[-–—:]\s*"""))
            .map { cleanSongTitle(it) }
            .filter { it.isNotBlank() && !GENERIC_ARTIST_NAMES.contains(it.lowercase(Locale.US)) }

        if (segments.size >= 2) {
            val first = segments[0]
            val second = segments[1]
            // Both "Artist - Song" (e.g. "Anuv Jain - INAAM") and "Song - Artist" are common
            pairs.add(second to first)
            pairs.add(first to second)
            if (!isGenericArtist) {
                pairs.add(first to cleanArtist)
                pairs.add(second to cleanArtist)
            }
        }

        if (!isGenericArtist && cleanTitle.isNotBlank()) {
            pairs.add(cleanTitle to cleanArtist)
            val stripped = stripExtraSubtitleWords(cleanTitle)
            if (stripped.isNotBlank() && !stripped.equals(cleanTitle, ignoreCase = true)) {
                pairs.add(stripped to cleanArtist)
            }
        }

        if (segments.size >= 2) {
            pairs.add(segments[1] to "")
            pairs.add(segments[0] to "")
        }

        if (cleanTitle.isNotBlank()) {
            pairs.add(cleanTitle to "")
        }

        return pairs.distinctBy { "${it.first.lowercase(Locale.US)}|${it.second.lowercase(Locale.US)}" }
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

    private fun executeSearchArrayRequest(
        url: String,
        targetDurationSeconds: Int,
        expectedTrack: String = "",
        expectedArtist: String = ""
    ): LyricsUiState.Success? {
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

                val expTrackLow = expectedTrack.lowercase(Locale.US).trim()
                val expArtistLow = expectedArtist.lowercase(Locale.US).trim()

                val candidates = mutableListOf<Pair<Int, LyricsUiState.Success>>()
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val parsed = parseLrcLibJsonObject(obj) ?: continue
                    val itemDuration = obj.optDouble("duration", 0.0).toInt()
                    val durationDiff = if (targetDurationSeconds > 0 && itemDuration > 0) {
                        abs(targetDurationSeconds - itemDuration)
                    } else {
                        25
                    }
                    val itemTrackLow = parsed.trackTitle.lowercase(Locale.US)
                    val itemArtistLow = parsed.artistName.lowercase(Locale.US)

                    var score = (if (parsed.isSynced) 1000 else 320) - durationDiff.coerceAtMost(260) - (i * 4)
                    if (expTrackLow.isNotBlank() && (itemTrackLow == expTrackLow || itemTrackLow.contains(expTrackLow) || expTrackLow.contains(itemTrackLow))) {
                        score += 250
                    }
                    if (expArtistLow.isNotBlank() && !GENERIC_ARTIST_NAMES.contains(expArtistLow) &&
                        (itemArtistLow.contains(expArtistLow) || expArtistLow.contains(itemArtistLow))
                    ) {
                        score += 220
                    }
                    candidates.add(score to parsed)
                }

                candidates.maxByOrNull { it.first }?.second
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchFromLyricsOvhFallback(track: String, artist: String): LyricsUiState.Success? {
        return try {
            val url = "https://api.lyrics.ovh/v1/${urlEncode(artist)}/${urlEncode(track)}"
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
                val rawLyrics = json.optString("lyrics", "").trim()
                if (rawLyrics.isBlank()) return null
                val lines = rawLyrics.lines()
                    .map { it.trim() }
                    .filter { it.isNotBlank() && !it.startsWith("Paroles de la chanson", ignoreCase = true) }
                if (lines.isEmpty()) return null
                LyricsUiState.Success(
                    trackTitle = track,
                    artistName = artist,
                    syncedLines = emptyList(),
                    plainLines = lines,
                    isSynced = false
                )
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

    fun parseSyncedLrc(lrcContent: String): List<SyncedLyricLine> {
        val result = mutableListOf<SyncedLyricLine>()
        val timeTagRegex = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")

        for (rawLine in lrcContent.lines()) {
            val matches = timeTagRegex.findAll(rawLine).toList()
            if (matches.isEmpty()) continue

            val lyricText = rawLine.replace(timeTagRegex, "").trim()
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
                result.add(
                    SyncedLyricLine(
                        timeMs = totalMs,
                        text = displayLyric
                    )
                )
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
                    """(?i)\b(official\s+video|official\s+music\s+video|official\s+audio|full\s+video|full\s+song|lyrical\s+video|lyrics|lyrical|hd|4k|8k|audio|video|remastered|slowed|reverb|lofi|lo-fi|remix|unplugged|mashup|version)\b"""
                ),
                " "
            )
            .substringBefore("|")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .trim('-', '_', ':', '•', '–', '—')
            .trim()
    }

    private fun stripExtraSubtitleWords(title: String): String {
        return title
            .replace(Regex("""(?i)\b(from|feat\.?|ft\.?|prod\.?|by|starring|ost|song)\b.*$"""), " ")
            .substringBefore("-")
            .substringBefore("–")
            .substringBefore("—")
            .substringBefore(":")
            .replace(Regex("""\s+"""), " ")
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
            .substringBefore("|")
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
