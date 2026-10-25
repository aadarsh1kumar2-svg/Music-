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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class FirebaseSongsService(private val context: Context) {

    companion object {
        private const val TAG = "AKCloudService"
        const val PROJECT_ID = "file-66f59"
        const val DATABASE_URL = "https://file-66f59-default-rtdb.firebaseio.com"
        const val STORAGE_BUCKET = "file-66f59.firebasestorage.app"
        private const val APP_ID = "1:237773536493:android:a7740030a05679148c60e4"
        private const val API_KEY = "AIzaSyDFYXjGLUHYu9whP7cawDwsunYR2f1elaE"

        private val RANK_FIELD_NAMES = listOf(
            "rank",
            "ranking",
            "position",
            "pos",
            "order",
            "topRank",
            "chartRank",
            "rankNumber",
            "serial",
            "index",
            "number",
            "no"
        )
    }

    private data class RawAdminSong(
        val firebaseKey: String,
        val song: Song,
        val explicitRank: Int,
        val snapshotOrder: Int,
        val updatedAt: Long
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val rankPrefs = context.applicationContext.getSharedPreferences(
        "ak_admin_rank_tracker",
        Context.MODE_PRIVATE
    )
    private val previousExplicitRanks = ConcurrentHashMap<String, Int>()
    private val previousResolvedRanks = ConcurrentHashMap<String, Int>()

    // Strictly Admin-uploaded songs ONLY (no extra default songs mixed into Admin catalog / Top Charts!)
    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val songs: StateFlow<List<Song>> = _songs.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _connectionError = MutableStateFlow<String?>(null)
    val connectionError: StateFlow<String?> = _connectionError.asStateFlow()

    private var songsRef: DatabaseReference? = null
    private var notificationsRef: DatabaseReference? = null
    private var messagesRef: DatabaseReference? = null
    private var valueListener: ValueEventListener? = null
    private val appStartTimestampMs: Long = System.currentTimeMillis()

    init {
        loadSavedRankHistory()
        initializeCloudAndListen()
    }

    private fun loadSavedRankHistory() {
        try {
            val savedResolved = rankPrefs.getString("resolved_ranks_json", null)
            if (!savedResolved.isNullOrBlank()) {
                val obj = JSONObject(savedResolved)
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val r = obj.optInt(k, -1)
                    if (r > 0) previousResolvedRanks[k] = r
                }
            }
            val savedExplicit = rankPrefs.getString("explicit_ranks_json", null)
            if (!savedExplicit.isNullOrBlank()) {
                val obj = JSONObject(savedExplicit)
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val r = obj.optInt(k, -1)
                    if (r > 0) previousExplicitRanks[k] = r
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun saveRankHistory() {
        try {
            val resolvedObj = JSONObject()
            for ((k, v) in previousResolvedRanks) {
                resolvedObj.put(k, v)
            }
            val explicitObj = JSONObject()
            for ((k, v) in previousExplicitRanks) {
                explicitObj.put(k, v)
            }
            rankPrefs.edit()
                .putString("resolved_ranks_json", resolvedObj.toString())
                .putString("explicit_ranks_json", explicitObj.toString())
                .apply()
        } catch (_: Exception) {
        }
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

            AADIFYMessagingService.initializeAndSubscribe(context)

            val database = FirebaseDatabase.getInstance(app, DATABASE_URL)
            try {
                database.setPersistenceEnabled(true)
            } catch (_: Exception) {
            }

            songsRef = database.getReference("songs")
            songsRef?.keepSynced(true)

            val listener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val rawList = parseSnapshot(snapshot)
                    _songs.value = resolveSmartSwapsAndSort(rawList)
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

            val notifListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    handleNotificationSnapshot(snapshot)
                }

                override fun onCancelled(error: DatabaseError) {}
            }
            notificationsRef = database.getReference("notifications").also {
                it.addValueEventListener(notifListener)
            }
            messagesRef = database.getReference("messages").also {
                it.addValueEventListener(notifListener)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Cloud init exception, using direct sync", e)
        }

        scope.launch {
            fetchSongsViaRest()
            fetchNotificationsViaRest()
            while (true) {
                delay(8_000L)
                fetchSongsViaRest(silent = true)
                fetchNotificationsViaRest()
            }
        }
    }

    /**
     * Smart Rank Swap & Line-Wise Ranking Engine:
     * 1. Places every song at the exact rank set by Admin (1st, 2nd, 3rd, ..., 30th).
     * 2. If Admin changes a song's rank (e.g. Song at #30 is changed to #2), the song that was
     *    previously at #2 automatically swaps to #30 (and updates in Firebase RTDB so all devices
     *    and the Admin Panel stay in sync).
     */
    @Synchronized
    private fun resolveSmartSwapsAndSort(rawList: List<RawAdminSong>): List<Song> {
        if (rawList.isEmpty()) return emptyList()
        val total = rawList.size

        // Map of current explicit ranks from Firebase (1..Int.MAX_VALUE)
        val effectiveRanks = HashMap<String, Int>()
        for (item in rawList) {
            if (item.explicitRank in 1 until Int.MAX_VALUE) {
                effectiveRanks[item.song.id] = item.explicitRank
            }
        }

        val firebaseRankUpdatesToPatch = mutableListOf<Pair<String, Int>>()

        // Step 1: Detect songs whose explicit rank in Firebase just changed compared to previousExplicitRanks/previousResolvedRanks
        val changedSongs = rawList.filter { item ->
            val newRank = item.explicitRank
            val prevExplicit = previousExplicitRanks[item.song.id]
            val prevResolved = previousResolvedRanks[item.song.id]
            newRank in 1 until Int.MAX_VALUE && (
                (prevExplicit != null && prevExplicit != newRank) ||
                    (prevExplicit == null && prevResolved != null && prevResolved != newRank)
                )
        }

        val swappedTargetIds = mutableSetOf<String>()

        for (mover in changedSongs) {
            val targetRank = mover.explicitRank
            val oldRank = previousResolvedRanks[mover.song.id]
                ?: previousExplicitRanks[mover.song.id]
                ?: (mover.snapshotOrder + 1)

            if (oldRank == targetRank) continue

            // Find the song that was sitting at targetRank
            val displaced = rawList.firstOrNull { candidate ->
                candidate.song.id != mover.song.id &&
                        candidate.song.id !in swappedTargetIds &&
                        (
                            effectiveRanks[candidate.song.id] == targetRank ||
                                previousResolvedRanks[candidate.song.id] == targetRank
                            )
            }

            if (displaced != null) {
                effectiveRanks[mover.song.id] = targetRank
                effectiveRanks[displaced.song.id] = oldRank
                swappedTargetIds.add(displaced.song.id)
                swappedTargetIds.add(mover.song.id)
                if (displaced.firebaseKey.isNotBlank() && displaced.explicitRank != oldRank) {
                    firebaseRankUpdatesToPatch.add(displaced.firebaseKey to oldRank)
                }
            }
        }

        // Step 2: Also handle any pre-existing duplicate explicit ranks in Firebase (e.g. if Admin changed
        // rank 30 -> 2 while the app was closed, so two songs in Firebase both have rank = 2 and rank 30 is vacant)
        val rankGroups = rawList
            .filter { (effectiveRanks[it.song.id] ?: Int.MAX_VALUE) in 1 until Int.MAX_VALUE }
            .groupBy { effectiveRanks[it.song.id]!! }

        val usedExplicitRanks = effectiveRanks.values.toMutableSet()
        val vacantRanks = (1..total).filter { it !in usedExplicitRanks }.toMutableList()

        for ((dupRank, itemsWithSameRank) in rankGroups) {
            if (itemsWithSameRank.size <= 1) continue

            // Determine which song is the newly moved song that should keep `dupRank`:
            // 1. A song in `changedSongs`
            // 2. Or a song with the newest `updatedAt`
            // 3. Or the song whose natural snapshot position (1-based) is farthest from `dupRank`
            //    (e.g. song at position 30 that was changed to rank 2!)
            val winner = itemsWithSameRank.maxWithOrNull(
                compareBy<RawAdminSong> { it.song.id in swappedTargetIds }
                    .thenBy { it.updatedAt }
                    .thenBy { kotlin.math.abs((it.snapshotOrder + 1) - dupRank) }
            ) ?: itemsWithSameRank.first()

            for (other in itemsWithSameRank) {
                if (other.song.id == winner.song.id) continue
                // Find the old slot that `winner` vacated (e.g. winner's previous rank or natural 1-based position)
                val winnerOldRank = previousResolvedRanks[winner.song.id]
                    ?: (winner.snapshotOrder + 1).takeIf { it in 1..total && it !in usedExplicitRanks }
                    ?: vacantRanks.firstOrNull()

                if (winnerOldRank != null) {
                    effectiveRanks[other.song.id] = winnerOldRank
                    usedExplicitRanks.add(winnerOldRank)
                    vacantRanks.remove(winnerOldRank)
                    if (other.firebaseKey.isNotBlank() && other.explicitRank != winnerOldRank) {
                        firebaseRankUpdatesToPatch.add(other.firebaseKey to winnerOldRank)
                    }
                }
            }
        }

        // Step 3: Place songs into exact 1..total slots so every song has its exact rank!
        val finalSlots = arrayOfNulls<RawAdminSong>(total)
        val unplaced = mutableListOf<RawAdminSong>()

        // Sort songs with explicit ranks first
        val sortedByExplicit = rawList.sortedWith(
            compareBy<RawAdminSong> { effectiveRanks[it.song.id] ?: Int.MAX_VALUE }
                .thenBy { it.snapshotOrder }
        )

        for (item in sortedByExplicit) {
            val desiredRank = effectiveRanks[item.song.id]
            if (desiredRank != null && desiredRank in 1..total && finalSlots[desiredRank - 1] == null) {
                finalSlots[desiredRank - 1] = item
            } else {
                unplaced.add(item)
            }
        }

        // Fill remaining slots in exact Admin line-wise order (or overflow explicit ranks)
        var cursor = 0
        for (item in unplaced) {
            while (cursor < total && finalSlots[cursor] != null) {
                cursor++
            }
            if (cursor < total) {
                finalSlots[cursor] = item
                cursor++
            }
        }

        val finalSongs = finalSlots.filterNotNull().mapIndexed { index, raw ->
            val resolvedRank = index + 1
            previousResolvedRanks[raw.song.id] = resolvedRank
            val effExp = effectiveRanks[raw.song.id]
            if (effExp != null && effExp in 1 until Int.MAX_VALUE) {
                previousExplicitRanks[raw.song.id] = effExp
            }
            raw.song.copy(rank = resolvedRank)
        }

        saveRankHistory()

        // Step 4: Patch swapped ranks back to Firebase Realtime Database so Admin Panel & all users see the swap!
        if (firebaseRankUpdatesToPatch.isNotEmpty()) {
            scope.launch {
                for ((fbKey, swappedRank) in firebaseRankUpdatesToPatch) {
                    patchSongRankInFirebase(fbKey, swappedRank)
                }
            }
        }

        return finalSongs
    }

    private fun patchSongRankInFirebase(firebaseKey: String, newRank: Int) {
        try {
            songsRef?.child(firebaseKey)?.child("rank")?.setValue(newRank)
        } catch (_: Exception) {
        }
        try {
            val payload = JSONObject().apply {
                put("rank", newRank)
            }
            val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url("$DATABASE_URL/songs/$firebaseKey.json")
                .patch(body)
                .build()
            httpClient.newCall(request).execute().close()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to patch swapped rank to Firebase for $firebaseKey", e)
        }
    }

    private fun parseSnapshot(snapshot: DataSnapshot): List<RawAdminSong> {
        val result = mutableListOf<RawAdminSong>()
        if (!snapshot.exists()) return result

        var idx = 0
        for (child in snapshot.children) {
            val parsed = parseChildSnapshot(child, idx)
            if (parsed != null && (parsed.song.videoId.isNotBlank() || parsed.song.title.isNotBlank())) {
                result.add(parsed)
                idx++
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

    private fun parseRankNumber(value: Any?): Int {
        return when (value) {
            is Number -> value.toInt().let { if (it <= 0) Int.MAX_VALUE else it }
            is String -> {
                val digits = Regex("\\d+").find(value)?.value?.toIntOrNull()
                if (digits != null && digits > 0) digits else Int.MAX_VALUE
            }
            else -> Int.MAX_VALUE
        }
    }

    private fun parseChildSnapshot(child: DataSnapshot, snapshotOrder: Int): RawAdminSong? {
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

            var explicitRank = Int.MAX_VALUE
            for (fieldName in RANK_FIELD_NAMES) {
                val parsed = parseRankNumber(child.child(fieldName).value)
                if (parsed in 1 until Int.MAX_VALUE) {
                    explicitRank = parsed
                    break
                }
            }

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

            val updatedAtObj = child.child("updatedAt").value
                ?: child.child("rankUpdatedAt").value
                ?: child.child("lastModified").value
            val updatedAt = when (updatedAtObj) {
                is Long -> updatedAtObj
                is Int -> updatedAtObj.toLong()
                is Double -> updatedAtObj.toLong()
                is String -> updatedAtObj.toLongOrNull() ?: 0L
                else -> 0L
            }

            val song = Song(
                id = id,
                title = title,
                artist = artist,
                videoId = Song.extractYoutubeVideoId(videoId),
                bannerUrl = bannerUrl,
                rank = explicitRank,
                isTopChart = isTopChart,
                timestamp = timestamp,
                allowDownload = allowDownload
            )

            RawAdminSong(
                firebaseKey = key,
                song = song,
                explicitRank = explicitRank,
                snapshotOrder = snapshotOrder,
                updatedAt = updatedAt
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
                    _songs.value = emptyList()
                    _isLoading.value = false
                    _connectionError.value = null
                    return@withContext
                }

                val rawList = parseSongsJsonRaw(bodyStr)
                _songs.value = resolveSmartSwapsAndSort(rawList)
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
        return resolveSmartSwapsAndSort(parseSongsJsonRaw(jsonStr))
    }

    private fun parseSongsJsonRaw(jsonStr: String): List<RawAdminSong> {
        val result = mutableListOf<RawAdminSong>()
        try {
            var idx = 0
            if (jsonStr.startsWith("{")) {
                val root = JSONObject(jsonStr)
                val keys = root.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val obj = root.optJSONObject(key) ?: continue
                    val parsed = parseJsonObject(key, obj, idx)
                    if (parsed != null) {
                        result.add(parsed)
                        idx++
                    }
                }
            } else if (jsonStr.startsWith("[")) {
                val arr = JSONArray(jsonStr)
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val parsed = parseJsonObject(i.toString(), obj, idx)
                    if (parsed != null) {
                        result.add(parsed)
                        idx++
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing JSON from /songs", e)
        }
        return result
    }

    private fun parseJsonObject(fallbackKey: String, obj: JSONObject, snapshotOrder: Int): RawAdminSong? {
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

        var explicitRank = Int.MAX_VALUE
        for (fieldName in RANK_FIELD_NAMES) {
            if (obj.has(fieldName)) {
                val parsed = parseRankNumber(obj.opt(fieldName))
                if (parsed in 1 until Int.MAX_VALUE) {
                    explicitRank = parsed
                    break
                }
            }
        }

        val isTopChart = obj.optBoolean("isTopChart", false)
        val timestamp = obj.optLong("timestamp", 0L)
        val updatedAt = obj.optLong(
            "updatedAt",
            obj.optLong("rankUpdatedAt", obj.optLong("lastModified", 0L))
        )

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

        val song = Song(
            id = id,
            title = title.ifBlank { "Untitled Track" },
            artist = artist,
            videoId = videoId,
            bannerUrl = bannerUrl,
            rank = explicitRank,
            isTopChart = isTopChart,
            timestamp = timestamp,
            allowDownload = allowDownload
        )

        return RawAdminSong(
            firebaseKey = fallbackKey,
            song = song,
            explicitRank = explicitRank,
            snapshotOrder = snapshotOrder,
            updatedAt = updatedAt
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

            val nextRank = (_songs.value.size) + 1
            val song = Song(
                id = id,
                title = title.trim().ifBlank { "Untitled Track" },
                artist = artist.trim().ifBlank { "Unknown Artist" },
                videoId = cleanVideoId,
                bannerUrl = resolvedBanner,
                rank = nextRank,
                timestamp = now
            )

            val payload = JSONObject().apply {
                put("id", song.id)
                put("title", song.title)
                put("artist", song.artist)
                put("videoId", song.videoId)
                put("bannerUrl", song.bannerUrl)
                put("rank", song.rank)
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
                    "rank" to song.rank,
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

    private fun handleNotificationSnapshot(snapshot: DataSnapshot) {
        if (!snapshot.exists()) return
        try {
            // Check if the snapshot itself is a direct notification object
            val directTitle = snapshot.child("title").getValue(String::class.java)
                ?: snapshot.child("heading").getValue(String::class.java)
            val directBody = snapshot.child("body").getValue(String::class.java)
                ?: snapshot.child("message").getValue(String::class.java)
                ?: snapshot.child("paragraph").getValue(String::class.java)
                ?: snapshot.child("text").getValue(String::class.java)
            if (!directTitle.isNullOrBlank() || !directBody.isNullOrBlank()) {
                val directImg = snapshot.child("imageUrl").getValue(String::class.java)
                    ?: snapshot.child("image").getValue(String::class.java)
                    ?: snapshot.child("bannerUrl").getValue(String::class.java)
                val ts = snapshot.child("timestamp").value?.toString().orEmpty()
                val key = "rtdb_direct_${directTitle.orEmpty()}_${directBody.orEmpty()}_$ts"
                AdminNotificationHelper.showAdminNotificationAsync(
                    context = context,
                    uniqueKey = key,
                    title = directTitle ?: "AADIFY",
                    body = directBody ?: "",
                    imageUrl = directImg
                )
                return
            }

            // Otherwise iterate over child notification entries
            for (child in snapshot.children) {
                val key = child.key ?: continue
                val title = child.child("title").getValue(String::class.java)
                    ?: child.child("heading").getValue(String::class.java)
                    ?: child.child("subject").getValue(String::class.java)
                    ?: ""
                val body = child.child("body").getValue(String::class.java)
                    ?: child.child("message").getValue(String::class.java)
                    ?: child.child("paragraph").getValue(String::class.java)
                    ?: child.child("text").getValue(String::class.java)
                    ?: child.child("description").getValue(String::class.java)
                    ?: ""
                val imageUrl = child.child("imageUrl").getValue(String::class.java)
                    ?: child.child("image").getValue(String::class.java)
                    ?: child.child("image_url").getValue(String::class.java)
                    ?: child.child("bannerUrl").getValue(String::class.java)
                    ?: child.child("picture").getValue(String::class.java)

                val tsObj = child.child("timestamp").value ?: child.child("createdAt").value
                val timestamp = when (tsObj) {
                    is Number -> tsObj.toLong()
                    is String -> tsObj.toLongOrNull() ?: 0L
                    else -> 0L
                }

                // Show if not yet shown on this device and either recent (within last 24h) or has no old timestamp
                val isRecentEnough = timestamp == 0L || timestamp >= (appStartTimestampMs - 24 * 60 * 60 * 1000L)
                if ((title.isNotBlank() || body.isNotBlank()) && isRecentEnough) {
                    val uniqueKey = "rtdb_${key}_${timestamp}_${title}"
                    AdminNotificationHelper.showAdminNotificationAsync(
                        context = context,
                        uniqueKey = uniqueKey,
                        title = title.ifBlank { "AADIFY" },
                        body = body,
                        imageUrl = imageUrl
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing notification snapshot", e)
        }
    }

    suspend fun fetchNotificationsViaRest() = withContext(Dispatchers.IO) {
        val endpoints = listOf("notifications", "messages")
        for (node in endpoints) {
            try {
                val request = Request.Builder()
                    .url("$DATABASE_URL/$node.json")
                    .get()
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val bodyStr = response.body?.string()?.trim().orEmpty()
                    if (bodyStr.isEmpty() || bodyStr == "null" || !bodyStr.startsWith("{")) return@use
                    val root = JSONObject(bodyStr)

                    val directTitle = root.optString("title").ifBlank { root.optString("heading") }
                    val directBody = root.optString("body").ifBlank {
                        root.optString("message").ifBlank { root.optString("paragraph") }
                    }
                    if (directTitle.isNotBlank() || directBody.isNotBlank()) {
                        val directImg = root.optString("imageUrl").ifBlank {
                            root.optString("image").ifBlank { root.optString("bannerUrl") }
                        }
                        val ts = root.optLong("timestamp", 0L)
                        val key = "rest_direct_${node}_${directTitle}_${directBody}_$ts"
                        AdminNotificationHelper.showAdminNotificationBlocking(
                            context = context,
                            uniqueKey = key,
                            title = directTitle.ifBlank { "AADIFY" },
                            body = directBody,
                            imageUrl = directImg.takeIf { it.isNotBlank() }
                        )
                        return@use
                    }

                    val keys = root.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val obj = root.optJSONObject(k) ?: continue
                        val title = obj.optString("title").ifBlank {
                            obj.optString("heading").ifBlank { obj.optString("subject") }
                        }
                        val body = obj.optString("body").ifBlank {
                            obj.optString("message").ifBlank {
                                obj.optString("paragraph").ifBlank {
                                    obj.optString("text").ifBlank { obj.optString("description") }
                                }
                            }
                        }
                        val imageUrl = obj.optString("imageUrl").ifBlank {
                            obj.optString("image").ifBlank {
                                obj.optString("image_url").ifBlank {
                                    obj.optString("bannerUrl").ifBlank { obj.optString("picture") }
                                }
                            }
                        }
                        val timestamp = obj.optLong("timestamp", obj.optLong("createdAt", 0L))
                        val isRecentEnough = timestamp == 0L || timestamp >= (appStartTimestampMs - 24 * 60 * 60 * 1000L)
                        if ((title.isNotBlank() || body.isNotBlank()) && isRecentEnough) {
                            val uniqueKey = "rtdb_${k}_${timestamp}_${title}"
                            AdminNotificationHelper.showAdminNotificationBlocking(
                                context = context,
                                uniqueKey = uniqueKey,
                                title = title.ifBlank { "AADIFY" },
                                body = body,
                                imageUrl = imageUrl.takeIf { it.isNotBlank() }
                            )
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Publishes an Admin Cloud Notification to Firebase Realtime Database (/notifications)
     * so all connected AADIFY users who allowed notifications immediately receive it with the
     * AADIFY app name, AADIFY logo, title, paragraph body, and optional banner image.
     */
    suspend fun sendCloudNotificationFromAdmin(
        title: String,
        body: String,
        imageUrl: String = ""
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val cleanTitle = title.trim().ifBlank { "AADIFY" }
            val cleanBody = body.trim()
            val cleanImg = imageUrl.trim()
            val now = System.currentTimeMillis()
            val notifId = "notif_${now}_${UUID.randomUUID().toString().take(6)}"

            val payload = JSONObject().apply {
                put("id", notifId)
                put("title", cleanTitle)
                put("body", cleanBody)
                put("message", cleanBody)
                if (cleanImg.isNotBlank()) {
                    put("imageUrl", cleanImg)
                    put("image", cleanImg)
                }
                put("appName", "AADIFY")
                put("timestamp", now)
            }

            val reqBody = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url("$DATABASE_URL/notifications/$notifId.json")
                .put(reqBody)
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("Firebase HTTP ${response.code}"))
                }
            }

            try {
                val map = mutableMapOf<String, Any>(
                    "id" to notifId,
                    "title" to cleanTitle,
                    "body" to cleanBody,
                    "message" to cleanBody,
                    "appName" to "AADIFY",
                    "timestamp" to now
                )
                if (cleanImg.isNotBlank()) {
                    map["imageUrl"] = cleanImg
                    map["image"] = cleanImg
                }
                notificationsRef?.child(notifId)?.setValue(map)
            } catch (_: Exception) {
            }

            val uniqueKey = "rtdb_${notifId}_${now}_${cleanTitle}"
            AdminNotificationHelper.showAdminNotificationBlocking(
                context = context,
                uniqueKey = uniqueKey,
                title = cleanTitle,
                body = cleanBody,
                imageUrl = cleanImg.takeIf { it.isNotBlank() }
            )

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send cloud notification", e)
            Result.failure(e)
        }
    }
}
