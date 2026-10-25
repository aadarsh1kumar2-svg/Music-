package com.example.services

import android.content.Context
import android.util.Log
import com.example.models.Song
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

class FirebaseSongsService(private val context: Context) {

    companion object {
        private const val TAG = "AKCloudService"
        const val PROJECT_ID = "file-66f59"
        const val DATABASE_URL = "https://file-66f59-default-rtdb.firebaseio.com"
        const val STORAGE_BUCKET = "file-66f59.firebasestorage.app"
        private const val APP_ID = "1:237773536493:android:a7740030a05679148c60e4"
        private const val API_KEY = "AIzaSyDFYXjGLUHYu9whP7cawDwsunYR2f1elaE"

        val DEFAULT_CURATED_SONGS = listOf(
            Song(
                id = "default_kesariya",
                title = "Kesariya - Brahmastra",
                artist = "Arijit Singh, Pritam",
                videoId = "BddP6PYo2gs",
                bannerUrl = "https://img.youtube.com/vi/BddP6PYo2gs/hqdefault.jpg",
                rank = 101,
                isTopChart = true,
                timestamp = 1710000020000L
            ),
            Song(
                id = "default_apna_bana_le",
                title = "Apna Bana Le - Bhediya",
                artist = "Arijit Singh, Sachin-Jigar",
                videoId = "ElZfdU54Cp8",
                bannerUrl = "https://img.youtube.com/vi/ElZfdU54Cp8/hqdefault.jpg",
                rank = 102,
                isTopChart = true,
                timestamp = 1710000019000L
            ),
            Song(
                id = "default_chaleya",
                title = "Chaleya - Jawan",
                artist = "Arijit Singh, Shilpa Rao, Anirudh",
                videoId = "VAdGW7QDJiU",
                bannerUrl = "https://img.youtube.com/vi/VAdGW7QDJiU/hqdefault.jpg",
                rank = 103,
                isTopChart = true,
                timestamp = 1710000018000L
            ),
            Song(
                id = "default_heeriye",
                title = "Heeriye",
                artist = "Jasleen Royal, Arijit Singh",
                videoId = "RLzC55ai0eo",
                bannerUrl = "https://img.youtube.com/vi/RLzC55ai0eo/hqdefault.jpg",
                rank = 104,
                isTopChart = true,
                timestamp = 1710000017000L
            ),
            Song(
                id = "default_o_maahi",
                title = "O Maahi - Dunki",
                artist = "Arijit Singh, Pritam",
                videoId = "Zlqf9cuaOBw",
                bannerUrl = "https://img.youtube.com/vi/Zlqf9cuaOBw/hqdefault.jpg",
                rank = 105,
                isTopChart = true,
                timestamp = 1710000016000L
            ),
            Song(
                id = "default_satranga",
                title = "Satranga - Animal",
                artist = "Arijit Singh, Shreyas Puranik",
                videoId = "HrnrqYxYrbk",
                bannerUrl = "https://img.youtube.com/vi/HrnrqYxYrbk/hqdefault.jpg",
                rank = 106,
                isTopChart = true,
                timestamp = 1710000015000L
            ),
            Song(
                id = "default_tauba_tauba",
                title = "Tauba Tauba - Bad Newz",
                artist = "Karan Aujla",
                videoId = "LK7-_dgAVQE",
                bannerUrl = "https://img.youtube.com/vi/LK7-_dgAVQE/hqdefault.jpg",
                rank = 107,
                isTopChart = true,
                timestamp = 1710000014000L
            ),
            Song(
                id = "default_aaj_ki_raat",
                title = "Aaj Ki Raat - Stree 2",
                artist = "Madhubanti Bagchi, Divya Kumar, Sachin-Jigar",
                videoId = "rozTr9_s77A",
                bannerUrl = "https://img.youtube.com/vi/rozTr9_s77A/hqdefault.jpg",
                rank = 108,
                isTopChart = true,
                timestamp = 1710000013000L
            ),
            Song(
                id = "default_sajni",
                title = "Sajni - Laapataa Ladies",
                artist = "Arijit Singh, Ram Sampath",
                videoId = "k3g_WjLCsXM",
                bannerUrl = "https://img.youtube.com/vi/k3g_WjLCsXM/hqdefault.jpg",
                rank = 109,
                isTopChart = true,
                timestamp = 1710000012000L
            ),
            Song(
                id = "default_husn",
                title = "Husn",
                artist = "Anuv Jain",
                videoId = "gJLVTKhTnog",
                bannerUrl = "https://img.youtube.com/vi/gJLVTKhTnog/hqdefault.jpg",
                rank = 110,
                isTopChart = true,
                timestamp = 1710000011000L
            ),
            Song(
                id = "default_tum_hi_ho",
                title = "Tum Hi Ho - Aashiqui 2",
                artist = "Arijit Singh, Mithoon",
                videoId = "IJq0yyWug1k",
                bannerUrl = "https://img.youtube.com/vi/IJq0yyWug1k/hqdefault.jpg",
                rank = 111,
                isTopChart = true,
                timestamp = 1710000010000L
            ),
            Song(
                id = "default_raataan_lambiyan",
                title = "Raataan Lambiyan - Shershaah",
                artist = "Jubin Nautiyal, Asees Kaur, Tanishk Bagchi",
                videoId = "gvyUuxdRdR4",
                bannerUrl = "https://img.youtube.com/vi/gvyUuxdRdR4/hqdefault.jpg",
                rank = 112,
                isTopChart = true,
                timestamp = 1710000009000L
            ),
            Song(
                id = "default_pasoori",
                title = "Pasoori",
                artist = "Ali Sethi, Shae Gill",
                videoId = "5Eqb_-j3FDA",
                bannerUrl = "https://img.youtube.com/vi/5Eqb_-j3FDA/hqdefault.jpg",
                rank = 113,
                isTopChart = true,
                timestamp = 1710000008000L
            ),
            Song(
                id = "default_blinding_lights",
                title = "Blinding Lights",
                artist = "The Weeknd",
                videoId = "4NRXx6U8ABQ",
                bannerUrl = "https://img.youtube.com/vi/4NRXx6U8ABQ/hqdefault.jpg",
                rank = 114,
                isTopChart = true,
                timestamp = 1710000007000L
            ),
            Song(
                id = "default_shape_of_you",
                title = "Shape of You",
                artist = "Ed Sheeran",
                videoId = "JGwWNGJdvx8",
                bannerUrl = "https://img.youtube.com/vi/JGwWNGJdvx8/hqdefault.jpg",
                rank = 115,
                isTopChart = true,
                timestamp = 1710000006000L
            ),
            Song(
                id = "default_starboy",
                title = "Starboy",
                artist = "The Weeknd, Daft Punk",
                videoId = "34Na4j8AVgA",
                bannerUrl = "https://img.youtube.com/vi/34Na4j8AVgA/hqdefault.jpg",
                rank = 116,
                isTopChart = true,
                timestamp = 1710000005000L
            ),
            Song(
                id = "default_believer",
                title = "Believer",
                artist = "Imagine Dragons",
                videoId = "7wtfhZwyrcc",
                bannerUrl = "https://img.youtube.com/vi/7wtfhZwyrcc/hqdefault.jpg",
                rank = 117,
                isTopChart = true,
                timestamp = 1710000004000L
            ),
            Song(
                id = "default_calm_down",
                title = "Calm Down",
                artist = "Rema, Selena Gomez",
                videoId = "WcIcVapfqXw",
                bannerUrl = "https://img.youtube.com/vi/WcIcVapfqXw/hqdefault.jpg",
                rank = 118,
                isTopChart = true,
                timestamp = 1710000003000L
            )
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val _songs = MutableStateFlow<List<Song>>(DEFAULT_CURATED_SONGS)
    val songs: StateFlow<List<Song>> = _songs.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _connectionError = MutableStateFlow<String?>(null)
    val connectionError: StateFlow<String?> = _connectionError.asStateFlow()

    private var songsRef: DatabaseReference? = null
    private var valueListener: ValueEventListener? = null

    init {
        initializeCloudAndListen()
    }

    private fun mergeWithDefaultCatalog(adminSongs: List<Song>): List<Song> {
        val sortedAdmin = sortCatalogTracks(adminSongs)
        val adminVideoIds = sortedAdmin.map { it.cleanVideoId.lowercase() }.filter { it.isNotBlank() }.toSet()
        val extras = DEFAULT_CURATED_SONGS.filter { it.cleanVideoId.lowercase() !in adminVideoIds }
        return sortedAdmin + extras
    }

    private fun initializeCloudAndListen() {
        try {
            val existingApps = FirebaseApp.getApps(context)
            val app = if (existingApps.isEmpty()) {
                val options = FirebaseOptions.Builder()
                    .setProjectId(PROJECT_ID)
                    .setDatabaseUrl(DATABASE_URL)
                    .setStorageBucket(STORAGE_BUCKET)
                    .setApplicationId(APP_ID)
                    .setApiKey(API_KEY)
                    .build()
                FirebaseApp.initializeApp(context, options)
            } else {
                FirebaseApp.getInstance()
            }

            val database = FirebaseDatabase.getInstance(app, DATABASE_URL)
            try {
                database.setPersistenceEnabled(true)
            } catch (_: Exception) {
            }

            songsRef = database.getReference("songs")
            songsRef?.keepSynced(true)

            val listener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val parsedList = parseSnapshot(snapshot)
                    _songs.value = mergeWithDefaultCatalog(parsedList)
                    _isLoading.value = false
                    _connectionError.value = null
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.w(TAG, "Realtime listener fallback: ${error.message}")
                    scope.launch { fetchSongsViaRest() }
                }
            }
            valueListener = listener
            songsRef?.addValueEventListener(listener)
        } catch (e: Exception) {
            Log.e(TAG, "Cloud init exception, using direct sync", e)
        }

        scope.launch {
            fetchSongsViaRest()
            while (true) {
                delay(10_000L)
                fetchSongsViaRest(silent = true)
            }
        }
    }

    private fun sortCatalogTracks(list: List<Song>): List<Song> {
        return list.sortedWith(
            compareBy<Song> { if (it.rank in 1..99) it.rank else Int.MAX_VALUE }
                .thenByDescending { it.timestamp }
        )
    }

    private fun parseSnapshot(snapshot: DataSnapshot): List<Song> {
        val result = mutableListOf<Song>()
        if (!snapshot.exists()) return result

        for (child in snapshot.children) {
            val song = parseChildSnapshot(child)
            if (song != null && (song.videoId.isNotBlank() || song.title.isNotBlank())) {
                result.add(song)
            }
        }
        return result
    }

    private fun parseBooleanField(value: Any?): Boolean {
        return when (value) {
            is Boolean -> value
            is String -> value.equals("true", ignoreCase = true) ||
                    value.equals("1", ignoreCase = true) ||
                    value.equals("yes", ignoreCase = true) ||
                    value.equals("enabled", ignoreCase = true)
            is Number -> value.toInt() == 1
            else -> false
        }
    }

    private fun parseChildSnapshot(child: DataSnapshot): Song? {
        return try {
            val key = child.key ?: ""
            val id = child.child("id").getValue(String::class.java)?.takeIf { it.isNotBlank() } ?: key
            val title = child.child("title").getValue(String::class.java)
                ?: child.child("name").getValue(String::class.java)
                ?: ""
            val artist = child.child("artist").getValue(String::class.java)
                ?: child.child("subtitle").getValue(String::class.java)
                ?: "Unknown Artist"
            val videoId = child.child("videoId").getValue(String::class.java)
                ?: child.child("youtubeId").getValue(String::class.java)
                ?: child.child("url").getValue(String::class.java)
                ?: ""
            val bannerUrl = child.child("bannerUrl").getValue(String::class.java)
                ?: child.child("thumbnail").getValue(String::class.java)
                ?: child.child("image").getValue(String::class.java)
                ?: ""

            val rankObj = child.child("rank").value
            val rank = when (rankObj) {
                is Long -> rankObj.toInt()
                is Int -> rankObj
                is Double -> rankObj.toInt()
                is String -> rankObj.toIntOrNull() ?: Int.MAX_VALUE
                else -> Int.MAX_VALUE
            }.let { if (it <= 0) Int.MAX_VALUE else it }

            val isTopChart = parseBooleanField(child.child("isTopChart").value)

            val allowDownload = listOf(
                "allowDownload",
                "canDownload",
                "isDownloadable",
                "downloadable",
                "download",
                "downloadEnabled",
                "allowOffline",
                "offline"
            ).any { fieldName ->
                parseBooleanField(child.child(fieldName).value)
            }

            val timestampObj = child.child("timestamp").value
            val timestamp = when (timestampObj) {
                is Long -> timestampObj
                is Int -> timestampObj.toLong()
                is Double -> timestampObj.toLong()
                is String -> timestampObj.toLongOrNull() ?: 0L
                else -> 0L
            }

            Song(
                id = id,
                title = title,
                artist = artist,
                videoId = Song.extractYoutubeVideoId(videoId),
                bannerUrl = bannerUrl,
                rank = rank,
                isTopChart = isTopChart,
                timestamp = timestamp,
                allowDownload = allowDownload
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse child song snapshot", e)
            null
        }
    }

    suspend fun fetchSongsViaRest(silent: Boolean = false) = withContext(Dispatchers.IO) {
        if (!silent && _songs.value.isEmpty()) {
            _isLoading.value = true
        }
        try {
            val request = Request.Builder()
                .url("$DATABASE_URL/songs.json")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    if (!silent) {
                        _connectionError.value = "Network error (${response.code})"
                    }
                    _isLoading.value = false
                    return@withContext
                }

                val bodyStr = response.body?.string()?.trim() ?: "null"
                if (bodyStr == "null" || bodyStr.isEmpty()) {
                    _songs.value = DEFAULT_CURATED_SONGS
                    _isLoading.value = false
                    _connectionError.value = null
                    return@withContext
                }

                val list = parseSongsJson(bodyStr)
                _songs.value = mergeWithDefaultCatalog(list)
                _connectionError.value = null
                _isLoading.value = false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Cloud sync failed", e)
            if (!silent && _songs.value.isEmpty()) {
                _connectionError.value = "Network error while syncing tracks"
            }
            _isLoading.value = false
        }
    }

    fun parseSongsJson(jsonStr: String): List<Song> {
        val result = mutableListOf<Song>()
        try {
            if (jsonStr.startsWith("{")) {
                val root = JSONObject(jsonStr)
                val keys = root.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val obj = root.optJSONObject(key) ?: continue
                    val song = parseJsonObject(key, obj)
                    if (song != null) result.add(song)
                }
            } else if (jsonStr.startsWith("[")) {
                val arr = JSONArray(jsonStr)
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val song = parseJsonObject(i.toString(), obj)
                    if (song != null) result.add(song)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing JSON from /songs", e)
        }
        return result
    }

    private fun parseJsonObject(fallbackKey: String, obj: JSONObject): Song? {
        val id = obj.optString("id").ifBlank { fallbackKey }
        val title = obj.optString("title").ifBlank { obj.optString("name") }
        val artist = obj.optString("artist").ifBlank { obj.optString("subtitle", "Unknown Artist") }
        val rawVideoId = obj.optString("videoId").ifBlank {
            obj.optString("youtubeId").ifBlank { obj.optString("url") }
        }
        val videoId = Song.extractYoutubeVideoId(rawVideoId)
        val bannerUrl = obj.optString("bannerUrl").ifBlank {
            obj.optString("thumbnail").ifBlank { obj.optString("image") }
        }
        val rawRank = obj.optInt("rank", Int.MAX_VALUE)
        val rank = if (rawRank <= 0) Int.MAX_VALUE else rawRank
        val isTopChart = obj.optBoolean("isTopChart", false)
        val timestamp = obj.optLong("timestamp", System.currentTimeMillis())

        val allowDownload = listOf(
            "allowDownload",
            "canDownload",
            "isDownloadable",
            "downloadable",
            "download",
            "downloadEnabled",
            "allowOffline",
            "offline"
        ).any { fieldName ->
            if (!obj.has(fieldName)) false else parseBooleanField(obj.opt(fieldName))
        }

        if (title.isBlank() && videoId.isBlank()) return null

        return Song(
            id = id,
            title = title.ifBlank { "Untitled Track" },
            artist = artist,
            videoId = videoId,
            bannerUrl = bannerUrl,
            rank = rank,
            isTopChart = isTopChart,
            timestamp = timestamp,
            allowDownload = allowDownload
        )
    }

    suspend fun uploadSongToFirebase(
        title: String,
        artist: String,
        videoIdOrUrl: String,
        bannerUrl: String
    ): Result<Song> = withContext(Dispatchers.IO) {
        try {
            val cleanVideoId = Song.extractYoutubeVideoId(videoIdOrUrl)
            if (cleanVideoId.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("Valid YouTube Video ID or URL is required"))
            }
            val id = "song_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
            val now = System.currentTimeMillis()
            val resolvedBanner = bannerUrl.trim().ifBlank {
                "https://img.youtube.com/vi/$cleanVideoId/hqdefault.jpg"
            }

            val song = Song(
                id = id,
                title = title.trim().ifBlank { "Untitled Track" },
                artist = artist.trim().ifBlank { "Unknown Artist" },
                videoId = cleanVideoId,
                bannerUrl = resolvedBanner,
                timestamp = now
            )

            val payload = JSONObject().apply {
                put("id", song.id)
                put("title", song.title)
                put("artist", song.artist)
                put("videoId", song.videoId)
                put("bannerUrl", song.bannerUrl)
                put("timestamp", song.timestamp)
            }

            val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url("$DATABASE_URL/songs/$id.json")
                .put(body)
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("Firebase HTTP ${response.code}"))
                }
            }

            try {
                val map = mapOf(
                    "id" to song.id,
                    "title" to song.title,
                    "artist" to song.artist,
                    "videoId" to song.videoId,
                    "bannerUrl" to song.bannerUrl,
                    "timestamp" to song.timestamp
                )
                songsRef?.child(id)?.setValue(map)
            } catch (_: Exception) {
            }

            fetchSongsViaRest(silent = true)
            Result.success(song)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to upload song to Firebase", e)
            Result.failure(e)
        }
    }

    suspend fun deleteSongFromFirebase(songId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$DATABASE_URL/songs/$songId.json")
                .delete()
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("Firebase HTTP ${response.code}"))
                }
            }
            try {
                songsRef?.child(songId)?.removeValue()
            } catch (_: Exception) {
            }
            fetchSongsViaRest(silent = true)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
