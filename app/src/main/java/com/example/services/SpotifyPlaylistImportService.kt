package com.example.services

import android.net.Uri
import android.util.Base64
import com.example.models.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

data class ImportedSpotifyTrack(
    val title: String,
    val artist: String,
    val coverUrl: String,
    val durationMs: Int = 210_000,
    val videoId: String = ""
)

data class ImportedSpotifyCollection(
    val title: String,
    val coverUrl: String,
    val sourceUrl: String,
    val tracks: List<ImportedSpotifyTrack>
)

data class SharedPlaylistPayload(
    val name: String,
    val songs: List<ImportedSpotifyTrack>
)

class SpotifyPlaylistImportService {

    companion object {
        const val OFFICIAL_SHARE_DOMAIN = "https://aadify.xo.je"
        const val SHARE_QUERY_PARAM = "aadify_playlist"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /**
     * Generates a shareable link using https://aadify.xo.je/?aadify_playlist=<payload>
     * If the recipient has AADIFY installed, Android opens AADIFY via intent-filter and imports the playlist.
     * If the recipient does not have AADIFY installed, Chrome opens https://aadify.xo.je where they can download the app.
     */
    fun encodeSharedPlaylistLink(playlistName: String, songs: List<Song>): String {
        val root = JSONObject()
        root.put("n", playlistName.trim().ifBlank { "My Playlist" })
        val arr = JSONArray()
        songs.take(80).forEach { song ->
            val item = JSONArray()
            item.put(song.title.trim())
            item.put(song.artist.trim())
            item.put(song.videoId.trim())
            item.put(song.bannerUrl.trim())
            arr.put(item)
        }
        root.put("s", arr)

        val jsonBytes = root.toString().toByteArray(Charsets.UTF_8)
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { gzip ->
            gzip.write(jsonBytes)
        }
        val encoded = Base64.encodeToString(
            bos.toByteArray(),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        return "$OFFICIAL_SHARE_DOMAIN/?$SHARE_QUERY_PARAM=$encoded"
    }

    /**
     * Decodes a shared playlist from an https://aadify.xo.je/?aadify_playlist=... URL or share text.
     */
    fun decodeSharedPlaylistFromUrlOrText(rawInput: String): SharedPlaylistPayload? {
        return try {
            val trimmed = rawInput.trim()
            if (trimmed.isBlank()) return null

            val token = extractShareToken(trimmed) ?: return null
            val compressedBytes = Base64.decode(token, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            val jsonStr = GZIPInputStream(ByteArrayInputStream(compressedBytes)).use { gzip ->
                gzip.readBytes().toString(Charsets.UTF_8)
            }
            val root = JSONObject(jsonStr)
            val name = root.optString("n", "Shared Playlist").trim().ifBlank { "Shared Playlist" }
            val songsArray = root.optJSONArray("s") ?: JSONArray()
            val tracks = mutableListOf<ImportedSpotifyTrack>()
            for (i in 0 until songsArray.length()) {
                val row = songsArray.optJSONArray(i) ?: continue
                val title = row.optString(0, "").trim()
                val artist = row.optString(1, "Unknown Artist").trim().ifBlank { "Unknown Artist" }
                val videoId = row.optString(2, "").trim()
                val bannerUrl = row.optString(3, "").trim().ifBlank {
                    if (videoId.isNotBlank()) "https://i.ytimg.com/vi/$videoId/hqdefault.jpg" else ""
                }
                if (title.isNotBlank()) {
                    tracks.add(
                        ImportedSpotifyTrack(
                            title = title,
                            artist = artist,
                            coverUrl = bannerUrl,
                            videoId = videoId
                        )
                    )
                }
            }
            if (tracks.isEmpty()) null else SharedPlaylistPayload(name = name, songs = tracks)
        } catch (_: Exception) {
            null
        }
    }

    private fun extractShareToken(text: String): String? {
        val urlRegex = Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)
        val candidateUrls = urlRegex.findAll(text).map { it.value }.toList().ifEmpty { listOf(text) }
        for (candidate in candidateUrls) {
            try {
                val uri = Uri.parse(candidate)
                val param = uri.getQueryParameter(SHARE_QUERY_PARAM)
                if (!param.isNullOrBlank()) return param.trim()
            } catch (_: Exception) {
            }
            val idx = candidate.indexOf("$SHARE_QUERY_PARAM=")
            if (idx >= 0) {
                val sub = candidate.substring(idx + SHARE_QUERY_PARAM.length + 1)
                    .substringBefore("&")
                    .substringBefore(" ")
                    .trim()
                if (sub.isNotBlank()) return sub
            }
        }
        return null
    }

    /**
     * Imports a Spotify Playlist, Track/Song, or Album from any Spotify link or share text.
     */
    suspend fun importFromSpotifyUrl(rawInput: String): Result<ImportedSpotifyCollection> = withContext(Dispatchers.IO) {
        try {
            val extractedUrl = extractSpotifyUrl(rawInput)
                ?: return@withContext Result.failure(IllegalArgumentException("Valid Spotify playlist ya song link paste karein."))

            val resolvedUrl = resolveRedirectsIfNeeded(extractedUrl)
            val target = parseSpotifyEntityTarget(resolvedUrl)
                ?: parseSpotifyEntityTarget(extractedUrl)
                ?: return@withContext Result.failure(IllegalArgumentException("Spotify playlist ya song link samajh nahi aaya. Open Spotify link copy karke paste karein."))

            val (entityType, entityId) = target
            val canonicalUrl = "https://open.spotify.com/$entityType/$entityId"
            val embedUrl = "https://open.spotify.com/embed/$entityType/$entityId"

            // 1. Primary extraction from Spotify Embed __NEXT_DATA__ JSON
            val embedHtml = fetchHtml(embedUrl)
            if (embedHtml.isNotBlank()) {
                val parsedFromEmbed = parseFromEmbedHtml(embedHtml, entityType, canonicalUrl)
                if (parsedFromEmbed != null && parsedFromEmbed.tracks.isNotEmpty()) {
                    return@withContext Result.success(parsedFromEmbed)
                }
            }

            // 2. Fallback extraction from main Spotify page HTML + oEmbed
            val mainHtml = fetchHtml(canonicalUrl)
            val parsedFromMain = parseFromMainHtmlAndOEmbed(mainHtml, entityType, entityId, canonicalUrl)
            if (parsedFromMain != null && parsedFromMain.tracks.isNotEmpty()) {
                return@withContext Result.success(parsedFromMain)
            }

            Result.failure(IllegalStateException("Is Spotify link se songs load nahi ho paaye. Make sure playlist Public hai."))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun extractSpotifyUrl(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null

        // Handle spotify:playlist:ID or spotify:track:ID URI format
        val uriMatch = Regex("""spotify:(playlist|track|album):([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE).find(trimmed)
        if (uriMatch != null) {
            val type = uriMatch.groupValues[1].lowercase()
            val id = uriMatch.groupValues[2]
            return "https://open.spotify.com/$type/$id"
        }

        val urlMatch = Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE).find(trimmed)
        if (urlMatch != null) {
            return urlMatch.value.trimEnd('.', ',', ')', ']')
        }

        if (trimmed.contains("spotify.com/", ignoreCase = true) || trimmed.contains("spotify.link/", ignoreCase = true)) {
            return "https://${trimmed.removePrefix("https://").removePrefix("http://")}"
        }
        return null
    }

    private fun resolveRedirectsIfNeeded(url: String): String {
        return try {
            val req = Request.Builder()
                .url(url)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                )
                .build()
            httpClient.newCall(req).execute().use { response ->
                response.request.url.toString()
            }
        } catch (_: Exception) {
            url
        }
    }

    private fun parseSpotifyEntityTarget(url: String): Pair<String, String>? {
        val regex = Regex("""open\.spotify\.com/(?:intl-[a-zA-Z-]+/)?(?:embed/)?(playlist|track|album)/([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)
        val match = regex.find(url) ?: return null
        return match.groupValues[1].lowercase() to match.groupValues[2]
    }

    private fun fetchHtml(url: String): String {
        return try {
            val req = Request.Builder()
                .url(url)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                )
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return ""
                resp.body?.string().orEmpty()
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun parseFromEmbedHtml(
        html: String,
        entityType: String,
        canonicalUrl: String
    ): ImportedSpotifyCollection? {
        return try {
            val nextDataMatch = Regex(
                """<script\s+id="__NEXT_DATA__"\s+type="application/json"[^>]*>(.*?)</script>""",
                setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
            ).find(html) ?: return null

            val jsonStr = nextDataMatch.groupValues[1]
            val root = JSONObject(jsonStr)
            val entity = root
                .optJSONObject("props")
                ?.optJSONObject("pageProps")
                ?.optJSONObject("state")
                ?.optJSONObject("data")
                ?.optJSONObject("entity")
                ?: return null

            val collectionTitle = entity.optString("name")
                .ifBlank { entity.optString("title") }
                .trim()
                .ifBlank { "Spotify Playlist" }

            val coverUrl = extractEntityCoverUrl(entity)
            val tracks = mutableListOf<ImportedSpotifyTrack>()

            if (entityType == "track") {
                val trackTitle = collectionTitle
                val artistName = extractArtistsFromEntity(entity)
                val durationMs = entity.optInt("duration", 210_000).coerceAtLeast(30_000)
                tracks.add(
                    ImportedSpotifyTrack(
                        title = trackTitle,
                        artist = artistName,
                        coverUrl = coverUrl,
                        durationMs = durationMs
                    )
                )
                return ImportedSpotifyCollection(
                    title = trackTitle,
                    coverUrl = coverUrl,
                    sourceUrl = canonicalUrl,
                    tracks = tracks
                )
            }

            val trackList = entity.optJSONArray("trackList") ?: JSONArray()
            for (i in 0 until trackList.length()) {
                val item = trackList.optJSONObject(i) ?: continue
                val title = item.optString("title").trim()
                if (title.isBlank()) continue
                val subtitle = item.optString("subtitle")
                    .replace("\u00a0", " ")
                    .trim()
                    .ifBlank { "Spotify Artist" }
                val durationMs = item.optInt("duration", 210_000).coerceAtLeast(30_000)
                tracks.add(
                    ImportedSpotifyTrack(
                        title = title,
                        artist = subtitle,
                        coverUrl = coverUrl,
                        durationMs = durationMs
                    )
                )
            }

            if (tracks.isEmpty()) null else ImportedSpotifyCollection(
                title = collectionTitle,
                coverUrl = coverUrl,
                sourceUrl = canonicalUrl,
                tracks = tracks
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun extractEntityCoverUrl(entity: JSONObject): String {
        val coverSources = entity.optJSONObject("coverArt")?.optJSONArray("sources")
        if (coverSources != null && coverSources.length() > 0) {
            val firstUrl = coverSources.optJSONObject(0)?.optString("url").orEmpty()
            if (firstUrl.isNotBlank()) return firstUrl
        }
        val visualImage = entity.optJSONObject("visualIdentity")?.optJSONArray("image")
        if (visualImage != null && visualImage.length() > 0) {
            val best = visualImage.optJSONObject(visualImage.length() - 1)?.optString("url")
                ?: visualImage.optJSONObject(0)?.optString("url")
            if (!best.isNullOrBlank()) return best
        }
        return ""
    }

    private fun extractArtistsFromEntity(entity: JSONObject): String {
        val subtitle = entity.optString("subtitle").trim()
        if (subtitle.isNotBlank()) return subtitle.replace("\u00a0", " ")

        val artists = entity.optJSONArray("artists")
        if (artists != null && artists.length() > 0) {
            val names = mutableListOf<String>()
            for (i in 0 until artists.length()) {
                val name = artists.optJSONObject(i)?.optString("name")?.trim().orEmpty()
                if (name.isNotBlank()) names.add(name)
            }
            if (names.isNotEmpty()) return names.joinToString(", ")
        }
        return "Spotify Artist"
    }

    private fun parseFromMainHtmlAndOEmbed(
        html: String,
        entityType: String,
        entityId: String,
        canonicalUrl: String
    ): ImportedSpotifyCollection? {
        return try {
            var title = extractMetaContent(html, "og:title")
            var coverUrl = extractMetaContent(html, "og:image")
            val description = extractMetaContent(html, "og:description")

            if (title.isBlank() || coverUrl.isBlank()) {
                val oembedJson = fetchHtml("https://open.spotify.com/oembed?url=$canonicalUrl")
                if (oembedJson.isNotBlank()) {
                    val obj = JSONObject(oembedJson)
                    if (title.isBlank()) title = obj.optString("title").trim()
                    if (coverUrl.isBlank()) coverUrl = obj.optString("thumbnail_url").trim()
                }
            }

            val cleanTitle = title
                .replace(Regex("""\s*\|\s*Spotify.*$""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""^Spotify\s*-\s*""", RegexOption.IGNORE_CASE), "")
                .trim()
                .ifBlank { "Spotify ${entityType.replaceFirstChar { it.uppercase() }} $entityId" }

            val tracks = mutableListOf<ImportedSpotifyTrack>()
            if (entityType == "track") {
                // e.g. description: "Listen to <Song> on Spotify. <Artist> · Song · 2024."
                val artist = description
                    .substringAfter("on Spotify.", "")
                    .substringBefore("·")
                    .trim()
                    .ifBlank {
                        description.substringBefore("·").trim().ifBlank { "Spotify Artist" }
                    }
                tracks.add(
                    ImportedSpotifyTrack(
                        title = cleanTitle,
                        artist = artist,
                        coverUrl = coverUrl
                    )
                )
            } else {
                // Attempt to extract track rows from main page HTML if any
                val trackMatches = Regex(
                    """"name"\s*:\s*"([^"\\]+)"\s*,\s*"uri"\s*:\s*"spotify:track:[A-Za-z0-9]+""""
                ).findAll(html)
                for (m in trackMatches) {
                    val tName = decodeHtmlEntities(m.groupValues[1].trim())
                    if (tName.isNotBlank() && tracks.none { it.title.equals(tName, ignoreCase = true) }) {
                        tracks.add(
                            ImportedSpotifyTrack(
                                title = tName,
                                artist = "Spotify",
                                coverUrl = coverUrl
                            )
                        )
                    }
                }
            }

            if (tracks.isEmpty()) null else ImportedSpotifyCollection(
                title = cleanTitle,
                coverUrl = coverUrl,
                sourceUrl = canonicalUrl,
                tracks = tracks
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun extractMetaContent(html: String, property: String): String {
        val regex = Regex(
            """<meta[^>]+(?:property|name)=["']$property["'][^>]+content=["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        )
        val match = regex.find(html) ?: return ""
        return decodeHtmlEntities(match.groupValues[1].trim())
    }

    private fun decodeHtmlEntities(input: String): String {
        return input
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
    }
}
