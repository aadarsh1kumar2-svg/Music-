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

enum class LyricsLanguageMode(val label: String) {
    BOTH("Hinglish + Original"),
    HINGLISH("Hinglish"),
    ORIGINAL("Original")
}

data class SyncedLyricLine(
    val timeMs: Long,
    val text: String,
    val hinglishText: String = text,
    val originalText: String = text
)

sealed class LyricsUiState {
    data object Idle : LyricsUiState()
    data object Loading : LyricsUiState()
    data class Success(
        val trackTitle: String,
        val artistName: String,
        val syncedLines: List<SyncedLyricLine>,
        val plainLines: List<String>,
        val plainDualLines: List<SyncedLyricLine> = emptyList(),
        val isSynced: Boolean
    ) : LyricsUiState()
    data object NotFound : LyricsUiState()
}

object LrcLibLyricsService {
    private const val BASE_URL = "https://lrclib.net"
    private const val USER_AGENT = "AADIFY Music Player v5.0 (https://lrclib.net)"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
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

        // Extract candidate (track, artist) pairs from titles like "Anuv Jain - INAAM" or "INAAM - Anuv Jain"
        val candidatePairs = buildCandidateTrackArtistPairs(rawTitle, cleanTitle, cleanArtist, isGenericArtist)

        try {
            // Stage 1: Try exact /api/get for top (track, artist) pairs
            for ((trackCand, artistCand) in candidatePairs) {
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

            // Stage 2: Try /api/search?track_name=...&artist_name=... for candidate pairs
            for ((trackCand, artistCand) in candidatePairs) {
                if (trackCand.isNotBlank() && artistCand.isNotBlank()) {
                    val searchUrl =
                        "$BASE_URL/api/search?track_name=${urlEncode(trackCand)}&artist_name=${urlEncode(artistCand)}"
                    val match = executeSearchArrayRequest(searchUrl, durationSeconds, trackCand, artistCand)
                    if (match != null) {
                        lyricsCache[cacheKey] = match
                        return@withContext match
                    }
                }
            }

            // Stage 3: Build distinct general search queries (/api/search?q=...)
            val searchQueries = LinkedHashSet<String>()
            for ((trackCand, artistCand) in candidatePairs) {
                if (trackCand.isNotBlank() && artistCand.isNotBlank()) {
                    searchQueries.add("$trackCand $artistCand".trim())
                }
            }
            if (cleanTitle.isNotBlank()) {
                searchQueries.add(cleanTitle)
            }
            for ((trackCand, _) in candidatePairs) {
                if (trackCand.isNotBlank()) {
                    searchQueries.add(trackCand)
                }
            }
            val coreStrippedTitle = stripExtraSubtitleWords(cleanTitle)
            if (coreStrippedTitle.isNotBlank() && coreStrippedTitle.length >= 2) {
                searchQueries.add(coreStrippedTitle)
                if (!isGenericArtist) {
                    searchQueries.add("$coreStrippedTitle $cleanArtist")
                }
            }

            for (query in searchQueries) {
                if (query.isBlank()) continue
                val url = "$BASE_URL/api/search?q=${urlEncode(query)}"
                val match = executeSearchArrayRequest(url, durationSeconds, query, cleanArtist)
                if (match != null) {
                    lyricsCache[cacheKey] = match
                    return@withContext match
                }
            }

            // Stage 4: Try track_name-only search on LRCLIB for each segment
            val trackOnlyCandidates = LinkedHashSet<String>()
            for ((trackCand, _) in candidatePairs) {
                if (trackCand.isNotBlank()) trackOnlyCandidates.add(trackCand)
            }
            if (coreStrippedTitle.isNotBlank()) trackOnlyCandidates.add(coreStrippedTitle)

            for (trackCand in trackOnlyCandidates) {
                val url = "$BASE_URL/api/search?track_name=${urlEncode(trackCand)}"
                val match = executeSearchArrayRequest(url, durationSeconds, trackCand, cleanArtist)
                if (match != null) {
                    lyricsCache[cacheKey] = match
                    return@withContext match
                }
            }

            // Stage 5: Fallback to lyrics.ovh API if LRCLIB had no entry
            for ((trackCand, artistCand) in candidatePairs) {
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

        // Check if rawTitle or cleanTitle contains " - ", " – ", " — ", or ":"
        val prePipe = rawTitle.substringBefore("|").trim()
        val segments = prePipe.split(Regex("""\s*[-–—:]\s*"""))
            .map { cleanSongTitle(it) }
            .filter { it.isNotBlank() && !GENERIC_ARTIST_NAMES.contains(it.lowercase(Locale.US)) }

        if (segments.size >= 2) {
            val first = segments[0]
            val second = segments[1]
            // In Indian/YouTube music titles, both "Artist - Song" (e.g. "Anuv Jain - INAAM")
            // and "Song - Artist" are very common. Try both!
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
                val dualPlain = lines.mapIndexed { idx, line ->
                    val (hinglish, original) = buildDualLanguageTexts(line)
                    SyncedLyricLine(
                        timeMs = idx * 3000L,
                        text = line,
                        hinglishText = hinglish,
                        originalText = original
                    )
                }
                LyricsUiState.Success(
                    trackTitle = track,
                    artistName = artist,
                    syncedLines = emptyList(),
                    plainLines = lines,
                    plainDualLines = dualPlain,
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
                plainDualLines = syncedLines,
                isSynced = true
            )
        }

        if (plainRaw.isNotBlank()) {
            val lines = plainRaw.lines()
                .map { it.trim() }
                .filter { it.isNotBlank() }
            if (lines.isNotEmpty()) {
                val dualLines = lines.mapIndexed { idx, line ->
                    val (hinglish, original) = buildDualLanguageTexts(line)
                    SyncedLyricLine(
                        timeMs = idx * 3000L,
                        text = line,
                        hinglishText = hinglish,
                        originalText = original
                    )
                }
                return LyricsUiState.Success(
                    trackTitle = trackName,
                    artistName = artistName,
                    syncedLines = emptyList(),
                    plainLines = lines,
                    plainDualLines = dualLines,
                    isSynced = false
                )
            }
        }

        return null
    }

    /**
     * Parses LRC format lines and enriches every line with BOTH:
     * - `hinglishText` (Roman script)
     * - `originalText` (Original script: Devanagari Hindi / Native script)
     */
    fun parseSyncedLrc(lrcContent: String): List<SyncedLyricLine> {
        val result = mutableListOf<SyncedLyricLine>()
        val timeTagRegex = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")

        for (rawLine in lrcContent.lines()) {
            val matches = timeTagRegex.findAll(rawLine).toList()
            if (matches.isEmpty()) continue

            val lyricText = rawLine.replace(timeTagRegex, "").trim()
            val displayLyric = lyricText.ifBlank { "♪ • • ♪" }
            val (hinglish, original) = buildDualLanguageTexts(displayLyric)

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
                        text = displayLyric,
                        hinglishText = hinglish,
                        originalText = original
                    )
                )
            }
        }

        return result.sortedBy { it.timeMs }
    }

    /**
     * Produces `(hinglishText, originalText)` for any lyric line:
     * 1. If the input line contains Devanagari (Hindi) script, `originalText` is the Devanagari line
     *    and `hinglishText` is transliterated into smooth Roman Hinglish.
     * 2. If the input line is in Roman script (Hinglish), `hinglishText` is the Roman line
     *    and `originalText` is transliterated into Hindi Devanagari script so both Hinglish & Original
     *    language scripts are displayed together!
     */
    fun buildDualLanguageTexts(rawLine: String): Pair<String, String> {
        val trimmed = rawLine.trim()
        if (trimmed.isBlank() || trimmed == "♪ • • ♪") {
            return "♪ • • ♪" to "♪ • • ♪"
        }

        // Case 1: Line already contains both scripts separated by "/" or "|"
        if (containsDevanagari(trimmed) && containsLatinLetters(trimmed)) {
            val parts = trimmed.split(Regex("""\s*[|/]\s*""")).map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.size >= 2) {
                val devPart = parts.firstOrNull { containsDevanagari(it) }
                val latPart = parts.firstOrNull { !containsDevanagari(it) && containsLatinLetters(it) }
                if (devPart != null && latPart != null) {
                    return latPart to devPart
                }
            }
        }

        // Case 2: Line is in Devanagari (Hindi) script -> generate Hinglish (Roman)
        if (containsDevanagari(trimmed)) {
            val hinglish = transliterateDevanagariToHinglish(trimmed)
            return hinglish.ifBlank { trimmed } to trimmed
        }

        // Case 3: Line is in non-Latin native script (Punjabi Gurmukhi, Tamil, Telugu, etc.)
        if (!containsLatinLetters(trimmed)) {
            return trimmed to trimmed
        }

        // Case 4: Line is in Roman script -> transliterate Hinglish to Hindi Devanagari for Original Language line
        val devanagariOriginal = transliterateHinglishToDevanagari(trimmed)
        return trimmed to devanagariOriginal
    }

    private fun containsDevanagari(text: String): Boolean {
        return text.any { it.code in 0x0900..0x097F }
    }

    private fun containsLatinLetters(text: String): Boolean {
        return text.any { it in 'a'..'z' || it in 'A'..'Z' }
    }

    // Common Hinglish lyric dictionary for natural Devanagari rendering
    private val HINGLISH_WORD_DICTIONARY = mapOf(
        "kya" to "क्या", "kyu" to "क्यूँ", "kyun" to "क्यूँ", "kyon" to "क्यों",
        "tum" to "तुम", "tumko" to "तुमको", "tumhe" to "तुम्हें", "tumhein" to "तुम्हें",
        "tumhare" to "तुम्हारे", "tumhara" to "तुम्हारा", "tumhari" to "तुम्हारी", "tumse" to "तुमसे",
        "hum" to "हम", "humko" to "हमको", "humein" to "हमें", "hume" to "हमें", "humse" to "हमसे",
        "hamara" to "हमारा", "hamari" to "हमारी", "hamare" to "हमारे",
        "main" to "मैं", "mai" to "मैं", "maine" to "मैंने", "mujhe" to "मुझे", "mujhko" to "मुझको", "mujhse" to "मुझसे",
        "mera" to "मेरा", "meri" to "मेरी", "mere" to "मेरे",
        "tu" to "तू", "tune" to "तूने", "tujhe" to "तुझे", "tujhko" to "तुझको", "tujhse" to "तुझसे",
        "tera" to "तेरा", "teri" to "तेरी", "tere" to "तेरे",
        "wo" to "वो", "woh" to "वो", "ye" to "ये", "yeh" to "यह", "is" to "इस", "us" to "उस",
        "hai" to "है", "hain" to "हैं", "ho" to "हो", "hoon" to "हूँ", "hun" to "हूँ",
        "tha" to "था", "thi" to "थी", "the" to "थे",
        "na" to "ना", "nahi" to "नहीं", "nahin" to "नहीं", "mat" to "मत",
        "aur" to "और", "ya" to "या", "par" to "पर", "pe" to "पे", "se" to "से",
        "ko" to "को", "ka" to "का", "ki" to "की", "ke" to "के",
        "mein" to "में", "me" to "में", "bhi" to "भी", "hi" to "ही", "to" to "तो", "toh" to "तो",
        "dil" to "दिल", "pyar" to "प्यार", "pyaar" to "प्यार", "ishq" to "इश्क़", "mohabbat" to "मोहब्बत",
        "jaan" to "जान", "jaana" to "जाना", "jaane" to "जाने", "jaaneman" to "जानेमन",
        "yaad" to "याद", "yaadein" to "यादें", "yaadon" to "यादों",
        "raat" to "रात", "raatein" to "रातें", "din" to "दिन", "shaam" to "शाम", "subah" to "सुबह",
        "ankhein" to "आँखें", "aankhein" to "आँखें", "aankhon" to "आँखों", "aankh" to "आँख",
        "baat" to "बात", "baatein" to "बातें", "baaton" to "बातों",
        "saath" to "साथ", "sath" to "साथ", "haath" to "हाथ", "hath" to "हाथ",
        "zindagi" to "ज़िंदगी", "duniya" to "दुनिया", "jahan" to "जहाँ", "kahan" to "कहाँ", "yahan" to "यहाँ", "wahan" to "वहाँ",
        "kabhi" to "कभी", "kuch" to "कुछ", "kuchh" to "कुछ", "koi" to "कोई", "sab" to "सब",
        "ab" to "अब", "jab" to "जब", "tab" to "तब", "kab" to "कब",
        "kaise" to "कैसे", "kaisa" to "कैसा", "kaisi" to "कैसी", "jaise" to "जैसे", "aisa" to "ऐसा", "aisi" to "ऐसी",
        "kitna" to "कितना", "kitni" to "कितनी", "kitne" to "कितने", "itna" to "इतना", "itni" to "इतनी",
        "karte" to "करते", "karta" to "करता", "karti" to "करती", "kar" to "कर", "karo" to "करो", "kiya" to "किया",
        "kehte" to "कहते", "kehta" to "कहता", "kehti" to "कहती", "kaha" to "कहा", "kaho" to "कहो",
        "pehle" to "पहले", "pehlay" to "पहले", "panay" to "पाने", "paane" to "पाने", "paana" to "पाना",
        "chah" to "चाह", "chaah" to "चाह", "chahat" to "चाहत", "chahte" to "चाहते",
        "inaam" to "इनाम", "ilzaam" to "इल्ज़ाम", "naam" to "नाम", "kaam" to "काम",
        "dekho" to "देखो", "dekha" to "देखा", "dekhe" to "देखे", "dekh" to "देख",
        "suno" to "सुनो", "suna" to "सुना", "sun" to "सुन",
        "chalo" to "चलो", "chala" to "चला", "chali" to "चली", "chal" to "चल",
        "aao" to "आओ", "aaya" to "आया", "aayi" to "आई", "aaye" to "आए", "aa" to "आ",
        "gaya" to "गया", "gayi" to "गई", "gaye" to "गए", "ja" to "जा", "jao" to "जाओ",
        "raha" to "रहा", "rahi" to "रही", "rahe" to "रहे", "rehna" to "रहना",
        "mil" to "मिल", "mila" to "मिला", "mili" to "मिली", "mile" to "मिले", "milna" to "मिलना",
        "khuda" to "ख़ुदा", "rab" to "रब", "maula" to "मौला", "dua" to "दुआ",
        "hawa" to "हवा", "aasman" to "आसमान", "aasmaan" to "आसमान", "chand" to "चाँद", "taare" to "तारे",
        "sapna" to "सपना", "sapne" to "सपने", "khwab" to "ख़्वाब", "khwaab" to "ख़्वाब",
        "apna" to "अपना", "apni" to "अपनी", "apne" to "अपने", "paraya" to "पराया",
        "sajna" to "सजना", "saajana" to "साजना", "mahi" to "माही", "maahi" to "माही", "piya" to "पिया", "sanam" to "सनम",
        "bewafa" to "बेवफ़ा", "wafa" to "वफ़ा", "dard" to "दर्द", "gham" to "ग़म", "khushi" to "ख़ुशी",
        "muskan" to "मुस्कान", "hansi" to "हँसी", "aansu" to "आँसू",
        "raasta" to "रास्ता", "raaste" to "रास्ते", "manzil" to "मंज़िल", "safar" to "सफ़र",
        "pal" to "पल", "lamha" to "लम्हा", "lamhe" to "लम्हे", "waqt" to "वक़्त",
        "phir" to "फिर", "fir" to "फिर", "bina" to "बिना", "bin" to "बिन", "sang" to "संग"
    )

    private fun transliterateHinglishToDevanagari(line: String): String {
        val tokens = Regex("""([A-Za-z']+)|([^A-Za-z']+)""").findAll(line)
        val out = StringBuilder()
        for (match in tokens) {
            val token = match.value
            if (token.first().isLetter()) {
                val lower = token.lowercase(Locale.US).replace("'", "")
                val dictHit = HINGLISH_WORD_DICTIONARY[lower]
                if (dictHit != null) {
                    out.append(dictHit)
                } else {
                    out.append(phoneticRomanToDevanagariWord(lower))
                }
            } else {
                out.append(token)
            }
        }
        return out.toString()
    }

    private fun phoneticRomanToDevanagariWord(word: String): String {
        if (word.isEmpty()) return ""
        val sb = StringBuilder()
        var i = 0
        val n = word.length

        while (i < n) {
            val isStart = (i == 0)
            val rem = word.substring(i)

            // Check vowels at word start
            if (isStart) {
                when {
                    rem.startsWith("aa") -> { sb.append("आ"); i += 2; continue }
                    rem.startsWith("ai") -> { sb.append("ऐ"); i += 2; continue }
                    rem.startsWith("au") -> { sb.append("औ"); i += 2; continue }
                    rem.startsWith("ee") -> { sb.append("ई"); i += 2; continue }
                    rem.startsWith("oo") -> { sb.append("ऊ"); i += 2; continue }
                    rem.startsWith("a") -> { sb.append("अ"); i += 1; continue }
                    rem.startsWith("i") -> { sb.append("इ"); i += 1; continue }
                    rem.startsWith("u") -> { sb.append("उ"); i += 1; continue }
                    rem.startsWith("e") -> { sb.append("ए"); i += 1; continue }
                    rem.startsWith("o") -> { sb.append("ओ"); i += 1; continue }
                }
            }

            // Match consonant (up to 3 chars like chh, ksh)
            val (consDeva, consLen) = when {
                rem.startsWith("chh") -> "छ" to 3
                rem.startsWith("ksh") -> "क्ष" to 3
                rem.startsWith("kh") -> "ख" to 2
                rem.startsWith("gh") -> "घ" to 2
                rem.startsWith("ch") -> "च" to 2
                rem.startsWith("jh") -> "झ" to 2
                rem.startsWith("th") -> "थ" to 2
                rem.startsWith("dh") -> "ध" to 2
                rem.startsWith("ph") -> "फ" to 2
                rem.startsWith("bh") -> "भ" to 2
                rem.startsWith("sh") -> "श" to 2
                rem.startsWith("gy") -> "ज्ञ" to 2
                rem.startsWith("k") -> "क" to 1
                rem.startsWith("q") -> "क़" to 1
                rem.startsWith("g") -> "ग" to 1
                rem.startsWith("c") -> "क" to 1
                rem.startsWith("j") -> "ज" to 1
                rem.startsWith("z") -> "ज़" to 1
                rem.startsWith("t") -> "त" to 1
                rem.startsWith("d") -> "द" to 1
                rem.startsWith("n") -> "न" to 1
                rem.startsWith("p") -> "प" to 1
                rem.startsWith("f") -> "फ़" to 1
                rem.startsWith("b") -> "ब" to 1
                rem.startsWith("m") -> "म" to 1
                rem.startsWith("y") -> "य" to 1
                rem.startsWith("r") -> "र" to 1
                rem.startsWith("l") -> "ल" to 1
                rem.startsWith("v") || rem.startsWith("w") -> "व" to 1
                rem.startsWith("s") -> "स" to 1
                rem.startsWith("h") -> "ह" to 1
                else -> "" to 0
            }

            if (consLen > 0) {
                sb.append(consDeva)
                i += consLen
                if (i >= n) break
                val afterCons = word.substring(i)
                when {
                    afterCons.startsWith("aa") -> { sb.append("ा"); i += 2 }
                    afterCons.startsWith("ai") || afterCons.startsWith("ay") -> { sb.append("ै"); i += 2 }
                    afterCons.startsWith("au") -> { sb.append("ौ"); i += 2 }
                    afterCons.startsWith("ee") -> { sb.append("ी"); i += 2 }
                    afterCons.startsWith("oo") -> { sb.append("ू"); i += 2 }
                    afterCons.startsWith("a") -> {
                        // In Hinglish, trailing 'a' at the end of a word is long 'ा' (e.g. "tera" -> "तेरा")
                        if (i == n - 1) {
                            sb.append("ा")
                        }
                        i += 1
                    }
                    afterCons.startsWith("i") -> {
                        sb.append(if (i == n - 1) "ी" else "ि")
                        i += 1
                    }
                    afterCons.startsWith("u") -> { sb.append("ु"); i += 1 }
                    afterCons.startsWith("e") -> { sb.append("े"); i += 1 }
                    afterCons.startsWith("o") -> { sb.append("ो"); i += 1 }
                    else -> {
                        // Consonant cluster (add halant if another consonant immediately follows)
                        if (i < n && !isRomanVowel(word[i])) {
                            sb.append("्")
                        }
                    }
                }
            } else {
                // Standalone vowel inside word
                when {
                    rem.startsWith("aa") -> { sb.append("ा"); i += 2 }
                    rem.startsWith("ee") -> { sb.append("ी"); i += 2 }
                    rem.startsWith("oo") -> { sb.append("ू"); i += 2 }
                    rem.startsWith("a") -> { sb.append("ा"); i += 1 }
                    rem.startsWith("i") -> { sb.append("ि"); i += 1 }
                    rem.startsWith("u") -> { sb.append("ु"); i += 1 }
                    rem.startsWith("e") -> { sb.append("े"); i += 1 }
                    rem.startsWith("o") -> { sb.append("ो"); i += 1 }
                    else -> { i += 1 }
                }
            }
        }
        return sb.toString()
    }

    private fun isRomanVowel(c: Char): Boolean {
        return c == 'a' || c == 'e' || c == 'i' || c == 'o' || c == 'u'
    }

    private fun transliterateDevanagariToHinglish(text: String): String {
        val consonantMap: Map<Char, String> = mapOf(
            'क' to "k", 'ख' to "kh", 'ग' to "g", 'घ' to "gh", 'ङ' to "n",
            'च' to "ch", 'छ' to "chh", 'ज' to "j", 'झ' to "jh", 'ञ' to "n",
            'ट' to "t", 'ठ' to "th", 'ड' to "d", 'ढ' to "dh", 'ण' to "n",
            'त' to "t", 'थ' to "th", 'द' to "d", 'ध' to "dh", 'न' to "n",
            'प' to "p", 'फ' to "ph", 'ब' to "b", 'भ' to "bh", 'म' to "m",
            'य' to "y", 'र' to "r", 'ल' to "l", 'व' to "v",
            'श' to "sh", 'ष' to "sh", 'स' to "s", 'ह' to "h"
        )
        val vowelMap = mapOf(
            'अ' to "a", 'आ' to "aa", 'इ' to "i", 'ई' to "ee",
            'उ' to "u", 'ऊ' to "oo", 'ऋ' to "ri",
            'ए' to "e", 'ऐ' to "ai", 'ओ' to "o", 'औ' to "au"
        )
        val matraMap = mapOf(
            'ा' to "aa", 'ि' to "i", 'ी' to "ee",
            'ु' to "u", 'ू' to "oo", 'ृ' to "ri",
            'े' to "e", 'ै' to "ai", 'ो' to "o", 'ौ' to "au",
            'ं' to "n", 'ँ' to "n", 'ः' to "h"
        )

        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            val next = text.getOrNull(i + 1)

            if (consonantMap.containsKey(ch)) {
                val base = consonantMap[ch] ?: ""
                sb.append(base)
                when {
                    next == '्' -> {
                        i += 2
                        continue
                    }
                    next == '़' -> {
                        val afterNukta = text.getOrNull(i + 2)
                        if (afterNukta != null && matraMap.containsKey(afterNukta)) {
                            sb.append(matraMap[afterNukta])
                            i += 3
                        } else {
                            if (afterNukta != null && afterNukta.code in 0x0900..0x097F) {
                                sb.append("a")
                            }
                            i += 2
                        }
                        continue
                    }
                    next != null && matraMap.containsKey(next) -> {
                        sb.append(matraMap[next])
                        i += 2
                        continue
                    }
                    else -> {
                        val isWordEnd = next == null || next.isWhitespace() || next !in '\u0900'..'\u097F'
                        if (!isWordEnd) {
                            sb.append("a")
                        }
                        i += 1
                        continue
                    }
                }
            } else if (vowelMap.containsKey(ch)) {
                sb.append(vowelMap[ch])
            } else if (matraMap.containsKey(ch)) {
                sb.append(matraMap[ch])
            } else if (ch != '्' && ch != '़') {
                sb.append(ch)
            }
            i += 1
        }
        return sb.toString()
            .replace(Regex("""\s+"""), " ")
            .trim()
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
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
