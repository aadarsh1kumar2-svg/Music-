package com.example.services

import android.content.Context
import android.util.Base64
import android.util.Log
import com.example.BuildConfig
import com.example.models.AdminAppControlConfig
import com.example.models.AdminSubscriptionConfig
import com.example.models.Song
import com.example.models.SubscriptionPlanType
import com.example.models.UserSubscriptionState
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
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class CloudUserAuthResult(
    val userId: String,
    val userName: String,
    val password: String,
    val createdAtMs: Long,
    val isNewAccount: Boolean,
    val subscriptionState: UserSubscriptionState? = null,
    val secretCode: String = "",
    val todayListenedSeconds: Int = 0
)

class FirebaseSongsService(private val context: Context) {

    companion object {
        private const val TAG = "AKCloudService"
        val PROJECT_ID: String
            get() = com.example.security.AadifySecurityVault.getFirebaseProjectId()
        val DATABASE_URL: String
            get() = com.example.security.AadifySecurityVault.getFirebaseDatabaseUrl()
        val STORAGE_BUCKET: String
            get() = com.example.security.AadifySecurityVault.getFirebaseStorageBucket()
        private val APP_ID: String
            get() = com.example.security.AadifySecurityVault.getFirebaseAppId()

        private fun resolveFirebaseApiKey(): String {
            return com.example.security.AadifySecurityVault.getFirebaseApiKey()
        }

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
    private val configCachePrefs = context.applicationContext.getSharedPreferences(
        "ak_admin_config_cache",
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

    // Admin-configured Subscription Plans & Crazy Deal Config (real-time synced from /subscriptionPlans, /premiumBanners, /freeUserTimeLimitMinutes)
    private val _subscriptionConfig = MutableStateFlow(loadCachedSubscriptionConfig())
    val subscriptionConfig: StateFlow<AdminSubscriptionConfig> = _subscriptionConfig.asStateFlow()

    private val _isSubscriptionConfigLoaded = MutableStateFlow(configCachePrefs.getBoolean("has_cached_config", false))
    val isSubscriptionConfigLoaded: StateFlow<Boolean> = _isSubscriptionConfigLoaded.asStateFlow()

    // Admin-configured App Control (/appControl: Secret Code, Under Maintenance Mode, App Update Popup)
    private val _appControlConfig = MutableStateFlow(loadCachedAppControlConfig())
    val appControlConfig: StateFlow<AdminAppControlConfig> = _appControlConfig.asStateFlow()

    private var firebaseDatabase: FirebaseDatabase? = null
    private var songsRef: DatabaseReference? = null
    private var subscriptionConfigRef: DatabaseReference? = null
    private var notificationsRef: DatabaseReference? = null
    private var messagesRef: DatabaseReference? = null
    private var valueListener: ValueEventListener? = null
    private val installBaselineTimestampMs: Long =
        AdminNotificationHelper.getOrInitInstallBaselineMs(context)

    // User Identity & Admin User Panel Sync State
    var onUserDeletedByAdminCallback: (() -> Unit)? = null
    var onAdminVipStateSyncedCallback: ((UserSubscriptionState?) -> Unit)? = null
    var onAdminFreeLimitSyncedCallback: ((Int?) -> Unit)? = null
    var onAdminTimeUsageResetCallback: (() -> Unit)? = null
    @Volatile
    private var currentWatchedUserId: String? = null
    private var currentUserNodeRef: DatabaseReference? = null
    private var currentUserValueListener: ValueEventListener? = null
    private var currentUserTimeUsageRef: DatabaseReference? = null
    private var currentUserTimeUsageListener: ValueEventListener? = null
    @Volatile
    private var hasConfirmedCloudUserUpload: Boolean = false
    @Volatile
    private var hasUploadedTimeUsageNonZero: Boolean = false
    @Volatile
    private var isCurrentUserDeletedByAdmin: Boolean = false
    @Volatile
    private var lastUserUploadTimestampMs: Long = 0L

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
            val app = com.example.security.AadifySecurityVault.ensureSecureFirebaseInitialized(context)

            AADIFYMessagingService.initializeAndSubscribe(context)

            val database = FirebaseDatabase.getInstance(app, DATABASE_URL)
            firebaseDatabase = database
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

            val subConfigListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (snapshot.exists()) {
                        parseSubscriptionConfigSnapshot(snapshot)?.let { parsed ->
                            updateAndPersistSubscriptionConfig(parsed)
                        }
                    }
                }

                override fun onCancelled(error: DatabaseError) {}
            }
            subscriptionConfigRef = database.getReference("subscriptionPlans").also {
                it.keepSynced(true)
                it.addValueEventListener(subConfigListener)
            }

            // Exact path used by Admin.html for Free User Daily Limit in minutes: /freeUserTimeLimitMinutes
            val freeTimeLimitMinutesListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (snapshot.exists()) {
                        val current = _subscriptionConfig.value
                        val parsedSecs = parseFreeLimitSecondsFromRawValue(
                            snapshot.value,
                            isExplicitSeconds = false,
                            isExplicitMinutes = true
                        ) ?: parseFreeLimitSecondsFromSnapshot(snapshot, current.freeDailyLimitSeconds)
                        if (parsedSecs >= 60) {
                            updateAndPersistSubscriptionConfig(
                                current.copy(
                                    freeDailyLimitMinutes = (parsedSecs / 60).coerceAtLeast(1),
                                    freeDailyLimitSeconds = parsedSecs
                                )
                            )
                        }
                    }
                }

                override fun onCancelled(error: DatabaseError) {}
            }
            database.getReference("freeUserTimeLimitMinutes").also {
                it.keepSynced(true)
                it.addValueEventListener(freeTimeLimitMinutesListener)
            }

            val bannersListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (snapshot.exists()) {
                        parsePremiumBannersSnapshot(snapshot)?.let { updated ->
                            updateAndPersistSubscriptionConfig(updated)
                        }
                    }
                }

                override fun onCancelled(error: DatabaseError) {}
            }
            database.getReference("premiumBanners").also {
                it.keepSynced(true)
                it.addValueEventListener(bannersListener)
            }

            // Admin App Settings (/appControl): Secret Code, Maintenance Mode, and App Update Popup
            val appControlListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (snapshot.exists()) {
                        parseAppControlSnapshot(snapshot)?.let { updated ->
                            updateAndPersistAppControlConfig(updated)
                        }
                    } else {
                        updateAndPersistAppControlConfig(AdminAppControlConfig())
                    }
                }

                override fun onCancelled(error: DatabaseError) {}
            }
            database.getReference("appControl").also {
                it.keepSynced(true)
                it.addValueEventListener(appControlListener)
            }

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
            database.getReference("notification").addValueEventListener(notifListener)
            database.getReference("broadcast").addValueEventListener(notifListener)
        } catch (e: Exception) {
            Log.e(TAG, "Cloud init exception, using direct sync", e)
        }

        scope.launch {
            fetchSongsViaRest()
            fetchSubscriptionConfigViaRest(seedIfMissing = true)
            fetchAppControlViaRest()
            fetchNotificationsViaRest()
            while (true) {
                delay(6_000L)
                try {
                    firebaseDatabase?.goOnline()
                } catch (_: Exception) {
                }
                fetchSongsViaRest(silent = true)
                fetchSubscriptionConfigViaRest(seedIfMissing = false)
                fetchAppControlViaRest()
                fetchNotificationsViaRest()
                checkCurrentUserStatusInCloud()
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

            val downloadFieldNames = listOf(
                "allowDownload",
                "canDownload",
                "isDownloadable",
                "downloadable",
                "download",
                "downloadEnabled",
                "allowOffline",
                "offline"
            )
            val presentDownloadFields = downloadFieldNames.filter { child.hasChild(it) }
            val allowDownload = if (presentDownloadFields.isEmpty()) {
                true // Matches Admin.html: `const dlOn = song.allowDownload !== false;`
            } else {
                presentDownloadFields.any { fieldName ->
                    parseBooleanField(child.child(fieldName).value)
                }
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

        val downloadFieldNames = listOf(
            "allowDownload",
            "canDownload",
            "isDownloadable",
            "downloadable",
            "download",
            "downloadEnabled",
            "allowOffline",
            "offline"
        )
        val presentDownloadFields = downloadFieldNames.filter { obj.has(it) }
        val allowDownload = if (presentDownloadFields.isEmpty()) {
            true // Matches Admin.html: `const dlOn = song.allowDownload !== false;`
        } else {
            presentDownloadFields.any { fieldName ->
                parseBooleanField(obj.opt(fieldName))
            }
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

    private fun normalizeEpochMillis(rawTs: Long): Long {
        return if (rawTs in 1_000_000_000L..9_999_999_999L) {
            rawTs * 1000L
        } else {
            rawTs
        }
    }

    private fun handleNotificationSnapshot(snapshot: DataSnapshot) {
        if (!snapshot.exists()) return
        try {
            val nodeName = "rtdb_${snapshot.key ?: "notifications"}"
            val isFirstSeed = !AdminNotificationHelper.isNodeBaselineSeeded(context, nodeName)

            // Check if the snapshot itself is a direct notification object
            val directTitle = snapshot.child("title").getValue(String::class.java)
                ?: snapshot.child("heading").getValue(String::class.java)
                ?: snapshot.child("subject").getValue(String::class.java)
                ?: snapshot.child("name").getValue(String::class.java)
            val directBody = snapshot.child("body").getValue(String::class.java)
                ?: snapshot.child("message").getValue(String::class.java)
                ?: snapshot.child("paragraph").getValue(String::class.java)
                ?: snapshot.child("text").getValue(String::class.java)
                ?: snapshot.child("description").getValue(String::class.java)
                ?: snapshot.child("content").getValue(String::class.java)
            if (!directTitle.isNullOrBlank() || !directBody.isNullOrBlank()) {
                val directImg = AdminNotificationHelper.extractImageUrlFromSnapshot(snapshot)
                val rawTsObj = snapshot.child("timestamp").value
                    ?: snapshot.child("createdAt").value
                    ?: snapshot.child("time").value
                val rawTs = when (rawTsObj) {
                    is Number -> rawTsObj.toLong()
                    is String -> rawTsObj.toLongOrNull() ?: 0L
                    else -> 0L
                }
                val ts = normalizeEpochMillis(rawTs)
                val key = "rtdb_direct_${directTitle.orEmpty()}_${directBody.orEmpty()}_$ts"
                val isOldNotification = if (ts > 0L) {
                    ts < (installBaselineTimestampMs - 15_000L)
                } else {
                    isFirstSeed
                }
                if (isOldNotification) {
                    AdminNotificationHelper.markNotificationSilentlySeen(
                        context = context,
                        uniqueKey = key,
                        title = directTitle ?: "AADIFY",
                        body = directBody ?: ""
                    )
                } else {
                    AdminNotificationHelper.showAdminNotificationAsync(
                        context = context,
                        uniqueKey = key,
                        title = directTitle ?: "AADIFY",
                        body = directBody ?: "",
                        imageUrl = directImg
                    )
                }
                if (isFirstSeed) {
                    AdminNotificationHelper.markNodeBaselineSeeded(context, nodeName)
                }
                return
            }

            // Otherwise iterate over child notification entries
            for (child in snapshot.children) {
                val key = child.key ?: continue
                val title = child.child("title").getValue(String::class.java)
                    ?: child.child("heading").getValue(String::class.java)
                    ?: child.child("subject").getValue(String::class.java)
                    ?: child.child("name").getValue(String::class.java)
                    ?: ""
                val body = child.child("body").getValue(String::class.java)
                    ?: child.child("message").getValue(String::class.java)
                    ?: child.child("paragraph").getValue(String::class.java)
                    ?: child.child("text").getValue(String::class.java)
                    ?: child.child("description").getValue(String::class.java)
                    ?: child.child("content").getValue(String::class.java)
                    ?: child.child("subtitle").getValue(String::class.java)
                    ?: ""
                if (title.isBlank() && body.isBlank()) continue

                val imageUrl = AdminNotificationHelper.extractImageUrlFromSnapshot(child)
                val tsObj = child.child("timestamp").value
                    ?: child.child("createdAt").value
                    ?: child.child("time").value
                val rawTimestamp = when (tsObj) {
                    is Number -> tsObj.toLong()
                    is String -> tsObj.toLongOrNull() ?: 0L
                    else -> 0L
                }
                val timestamp = normalizeEpochMillis(rawTimestamp)
                val uniqueKey = "rtdb_${key}_${timestamp}_${title}"

                // Strict "No Old Notifications" rule:
                // 1. Any notification with a timestamp before installBaselineTimestampMs is old -> mark seen silently.
                // 2. Any notification without a timestamp present during the initial first seed is old -> mark seen silently.
                val isOldNotification = if (timestamp > 0L) {
                    timestamp < (installBaselineTimestampMs - 15_000L)
                } else {
                    isFirstSeed
                }

                if (isOldNotification) {
                    AdminNotificationHelper.markNotificationSilentlySeen(
                        context = context,
                        uniqueKey = uniqueKey,
                        title = title.ifBlank { "AADIFY" },
                        body = body
                    )
                } else {
                    AdminNotificationHelper.showAdminNotificationAsync(
                        context = context,
                        uniqueKey = uniqueKey,
                        title = title.ifBlank { "AADIFY" },
                        body = body,
                        imageUrl = imageUrl
                    )
                }
            }
            if (isFirstSeed) {
                AdminNotificationHelper.markNodeBaselineSeeded(context, nodeName)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing notification snapshot", e)
        }
    }

    suspend fun fetchNotificationsViaRest() = withContext(Dispatchers.IO) {
        val endpoints = listOf("notifications", "messages", "notification", "broadcast")
        for (node in endpoints) {
            try {
                val nodeName = "rtdb_$node"
                val isFirstSeed = !AdminNotificationHelper.isNodeBaselineSeeded(context, nodeName)

                val request = Request.Builder()
                    .url("$DATABASE_URL/$node.json")
                    .get()
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val bodyStr = response.body?.string()?.trim().orEmpty()
                    if (bodyStr.isEmpty() || bodyStr == "null" || !bodyStr.startsWith("{")) {
                        if (isFirstSeed) {
                            AdminNotificationHelper.markNodeBaselineSeeded(context, nodeName)
                        }
                        return@use
                    }
                    val root = JSONObject(bodyStr)

                    val directTitle = root.optString("title").ifBlank {
                        root.optString("heading").ifBlank { root.optString("subject") }
                    }
                    val directBody = root.optString("body").ifBlank {
                        root.optString("message").ifBlank {
                            root.optString("paragraph").ifBlank {
                                root.optString("text").ifBlank { root.optString("description") }
                            }
                        }
                    }
                    if (directTitle.isNotBlank() || directBody.isNotBlank()) {
                        val directImg = AdminNotificationHelper.extractImageUrlFromJson(root)
                        val ts = normalizeEpochMillis(
                            root.optLong("timestamp", root.optLong("createdAt", root.optLong("time", 0L)))
                        )
                        val key = "rest_direct_${node}_${directTitle}_${directBody}_$ts"
                        val isOldNotification = if (ts > 0L) {
                            ts < (installBaselineTimestampMs - 15_000L)
                        } else {
                            isFirstSeed
                        }
                        if (isOldNotification) {
                            AdminNotificationHelper.markNotificationSilentlySeen(
                                context = context,
                                uniqueKey = key,
                                title = directTitle.ifBlank { "AADIFY" },
                                body = directBody
                            )
                        } else {
                            AdminNotificationHelper.showAdminNotificationBlocking(
                                context = context,
                                uniqueKey = key,
                                title = directTitle.ifBlank { "AADIFY" },
                                body = directBody,
                                imageUrl = directImg
                            )
                        }
                        if (isFirstSeed) {
                            AdminNotificationHelper.markNodeBaselineSeeded(context, nodeName)
                        }
                        return@use
                    }

                    val keys = root.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val obj = root.optJSONObject(k) ?: continue
                        val title = obj.optString("title").ifBlank {
                            obj.optString("heading").ifBlank {
                                obj.optString("subject").ifBlank { obj.optString("name") }
                            }
                        }
                        val body = obj.optString("body").ifBlank {
                            obj.optString("message").ifBlank {
                                obj.optString("paragraph").ifBlank {
                                    obj.optString("text").ifBlank {
                                        obj.optString("description").ifBlank {
                                            obj.optString("content").ifBlank { obj.optString("subtitle") }
                                        }
                                    }
                                }
                            }
                        }
                        if (title.isBlank() && body.isBlank()) continue

                        val imageUrl = AdminNotificationHelper.extractImageUrlFromJson(obj)
                        val rawTimestamp = obj.optLong("timestamp", obj.optLong("createdAt", obj.optLong("time", 0L)))
                        val timestamp = normalizeEpochMillis(rawTimestamp)
                        val uniqueKey = "rtdb_${k}_${timestamp}_${title}"

                        val isOldNotification = if (timestamp > 0L) {
                            timestamp < (installBaselineTimestampMs - 15_000L)
                        } else {
                            isFirstSeed
                        }

                        if (isOldNotification) {
                            AdminNotificationHelper.markNotificationSilentlySeen(
                                context = context,
                                uniqueKey = uniqueKey,
                                title = title.ifBlank { "AADIFY" },
                                body = body
                            )
                        } else {
                            AdminNotificationHelper.showAdminNotificationBlocking(
                                context = context,
                                uniqueKey = uniqueKey,
                                title = title.ifBlank { "AADIFY" },
                                body = body,
                                imageUrl = imageUrl
                            )
                        }
                    }
                    if (isFirstSeed) {
                        AdminNotificationHelper.markNodeBaselineSeeded(context, nodeName)
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

    /**
     * Resets the deletion lock when a user explicitly submits their Name on the login screen.
     */
    fun resetAdminDeletionFlagForFreshLogin() {
        isCurrentUserDeletedByAdmin = false
        hasConfirmedCloudUserUpload = false
        hasUploadedTimeUsageNonZero = false
    }

    /**
     * Syncs the logged-in user's identity (Name) and full activity/library data to Firebase
     * Realtime Database (/users/{userId} and /akMusic/users/{userId}) so the Admin Panel's
     * User Panel displays every user and their complete data in real time.
     */
    fun syncUserProfileToCloud(
        userId: String,
        userPayload: JSONObject,
        isExplicitLogin: Boolean = false
    ) {
        val cleanId = userId.trim()
        if (cleanId.isEmpty()) return
        if (isExplicitLogin) {
            isCurrentUserDeletedByAdmin = false
        }
        if (isCurrentUserDeletedByAdmin) return

        val payloadUserName = userPayload.optString("userName")
            .ifBlank { userPayload.optString("name") }
            .trim()
        if (payloadUserName.isNotBlank()) {
            currentWatchedUserName = payloadUserName
        }

        scope.launch {
            try {
                // Unless this is a brand-new login/signup that just verified/created /users/{cleanId},
                // ALWAYS check if Admin deleted /users/{cleanId} BEFORE patching so a deleted user is NEVER re-created!
                if (!isExplicitLogin) {
                    val stillExists = verifyUserStillExistsBeforeUpdate(cleanId)
                    if (!stillExists) {
                        triggerAdminDeletionCleanup(cleanId)
                        return@launch
                    }
                }

                lastUserUploadTimestampMs = System.currentTimeMillis()
                val jsonBytes = userPayload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

                val primaryReq = Request.Builder()
                    .url("$DATABASE_URL/users/$cleanId.json")
                    .patch(jsonBytes)
                    .build()

                var uploadedOk = false
                httpClient.newCall(primaryReq).execute().use { resp ->
                    if (resp.isSuccessful) {
                        uploadedOk = true
                    }
                }

                // Also mirror to /akMusic/users/{userId} for Admin Panels reading nested akMusic/users
                try {
                    val mirrorBody = userPayload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                    val mirrorReq = Request.Builder()
                        .url("$DATABASE_URL/akMusic/users/$cleanId.json")
                        .patch(mirrorBody)
                        .build()
                    httpClient.newCall(mirrorReq).execute().close()
                } catch (_: Exception) {
                }

                if (uploadedOk && !isCurrentUserDeletedByAdmin) {
                    lastUserUploadTimestampMs = System.currentTimeMillis()
                    hasConfirmedCloudUserUpload = true
                    attachRealtimeUserWatchListener(cleanId)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to sync user profile to cloud: ${e.message}")
            }
        }
    }

    @Volatile
    private var currentWatchedUserName: String = ""

    private fun verifyUserStillExistsBeforeUpdate(userId: String): Boolean {
        return try {
            val req = Request.Builder()
                .url("$DATABASE_URL/users/$userId.json")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return true
                val body = resp.body?.string()?.trim().orEmpty()
                if (body.isEmpty() || body == "null") {
                    return false
                }
                if (body.startsWith("{")) {
                    val obj = JSONObject(body)
                    if (isMarkedDeletedInJson(obj)) return false
                }
                true
            }
        } catch (_: Exception) {
            true
        }
    }

    private fun isMarkedDeletedInJson(obj: JSONObject): Boolean {
        if (obj.optBoolean("deleted", false) || obj.optBoolean("isDeleted", false)) return true
        val status = obj.optString("status", "").trim().lowercase()
        return status == "deleted" || status == "removed" || status == "banned" || status == "blocked"
    }

    private fun attachRealtimeUserWatchListener(userId: String) {
        if (currentWatchedUserId == userId && currentUserValueListener != null) return
        try {
            currentUserValueListener?.let { oldListener ->
                currentUserNodeRef?.removeEventListener(oldListener)
            }
            currentUserTimeUsageListener?.let { oldTimeListener ->
                currentUserTimeUsageRef?.removeEventListener(oldTimeListener)
            }
        } catch (_: Exception) {
        }

        currentWatchedUserId = userId
        hasUploadedTimeUsageNonZero = false
        val db = firebaseDatabase ?: return
        val ref = db.getReference("users").child(userId)
        currentUserNodeRef = ref

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!hasConfirmedCloudUserUpload || isCurrentUserDeletedByAdmin) return
                val elapsedSinceWrite = System.currentTimeMillis() - lastUserUploadTimestampMs
                if (elapsedSinceWrite < 1_500L) {
                    // Still check if Admin granted or deactivated a VIP plan or custom free limit even within 1.5s
                    if (snapshot.exists() && snapshot.value != null) {
                        val syncedVip = parseCloudSubscriptionFromSnapshot(snapshot)
                        onAdminVipStateSyncedCallback?.invoke(syncedVip)
                        val userFreeLimitSecs = parseUserSpecificFreeLimitFromSnapshot(snapshot)
                        if (userFreeLimitSecs != null) {
                            onAdminFreeLimitSyncedCallback?.invoke(userFreeLimitSecs)
                        }
                    }
                    return
                }

                if (!snapshot.exists() || snapshot.value == null) {
                    triggerAdminDeletionCleanup(userId)
                    return
                }
                val deletedFlag = snapshot.child("deleted").getValue(Boolean::class.java) == true ||
                    snapshot.child("isDeleted").getValue(Boolean::class.java) == true
                val statusStr = snapshot.child("status").getValue(String::class.java)?.trim()?.lowercase().orEmpty()
                if (deletedFlag || statusStr == "deleted" || statusStr == "removed" || statusStr == "banned") {
                    triggerAdminDeletionCleanup(userId)
                    return
                }

                // Check if Admin granted, modified, or deactivated VIP / Subscription or Free User Time Limit on this user node
                val syncedVip = parseCloudSubscriptionFromSnapshot(snapshot)
                onAdminVipStateSyncedCallback?.invoke(syncedVip)
                val userFreeLimitSecs = parseUserSpecificFreeLimitFromSnapshot(snapshot)
                if (userFreeLimitSecs != null) {
                    onAdminFreeLimitSyncedCallback?.invoke(userFreeLimitSecs)
                }
            }

            override fun onCancelled(error: DatabaseError) {}
        }
        currentUserValueListener = listener
        try {
            ref.addValueEventListener(listener)
        } catch (_: Exception) {
        }

        // Also watch /userTimeUsage/{userId} so Admin clicking "Reset today's usage" immediately resets local usage
        val timeRef = db.getReference("userTimeUsage").child(userId)
        currentUserTimeUsageRef = timeRef
        val timeListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!hasConfirmedCloudUserUpload || isCurrentUserDeletedByAdmin) return
                if (snapshot.exists() && snapshot.value != null) {
                    val cloudSecs = (snapshot.child("totalSeconds").value as? Number)?.toInt()
                        ?: (snapshot.child("usedSeconds").value as? Number)?.toInt()
                        ?: 0
                    if (cloudSecs > 0) {
                        hasUploadedTimeUsageNonZero = true
                    }
                } else {
                    if (hasUploadedTimeUsageNonZero) {
                        hasUploadedTimeUsageNonZero = false
                        onAdminTimeUsageResetCallback?.invoke()
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {}
        }
        currentUserTimeUsageListener = timeListener
        try {
            timeRef.addValueEventListener(timeListener)
        } catch (_: Exception) {
        }
    }

    private suspend fun checkCurrentUserStatusInCloud() {
        val userId = currentWatchedUserId ?: return
        if (!hasConfirmedCloudUserUpload || isCurrentUserDeletedByAdmin) return
        val elapsedSinceWrite = System.currentTimeMillis() - lastUserUploadTimestampMs

        try {
            val primaryReq = Request.Builder()
                .url("$DATABASE_URL/users/$userId.json")
                .get()
                .build()
            httpClient.newCall(primaryReq).execute().use { resp ->
                if (!resp.isSuccessful) return
                val body = resp.body?.string()?.trim().orEmpty()
                if (body.isEmpty() || body == "null") {
                    if (elapsedSinceWrite >= 2_000L) {
                        triggerAdminDeletionCleanup(userId)
                    }
                    return
                }
                if (body.startsWith("{")) {
                    val obj = JSONObject(body)
                    if (isMarkedDeletedInJson(obj)) {
                        if (elapsedSinceWrite >= 2_000L) {
                            triggerAdminDeletionCleanup(userId)
                        }
                        return
                    }
                    val syncedVip = parseCloudSubscriptionFromJson(obj)
                    onAdminVipStateSyncedCallback?.invoke(syncedVip)
                    val userFreeLimitSecs = parseUserSpecificFreeLimitFromJson(obj)
                    if (userFreeLimitSecs != null) {
                        onAdminFreeLimitSyncedCallback?.invoke(userFreeLimitSecs)
                    }
                }
            }

            if (hasUploadedTimeUsageNonZero) {
                val timeReq = Request.Builder()
                    .url("$DATABASE_URL/userTimeUsage/$userId.json")
                    .get()
                    .build()
                httpClient.newCall(timeReq).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string()?.trim().orEmpty()
                        if (body.isEmpty() || body == "null") {
                            hasUploadedTimeUsageNonZero = false
                            onAdminTimeUsageResetCallback?.invoke()
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    @Synchronized
    private fun triggerAdminDeletionCleanup(userId: String) {
        if (isCurrentUserDeletedByAdmin) return
        isCurrentUserDeletedByAdmin = true
        hasConfirmedCloudUserUpload = false
        val watchedName = currentWatchedUserName

        try {
            currentUserValueListener?.let { listener ->
                currentUserNodeRef?.removeEventListener(listener)
            }
            currentUserValueListener = null
            currentUserNodeRef = null
            currentWatchedUserId = null
        } catch (_: Exception) {
        }

        // Permanently delete /users/{userId}, /akMusic/users/{userId}, and /userAccounts/{normalizedKey}
        scope.launch {
            val pathsToDelete = mutableListOf(
                "users/$userId",
                "akMusic/users/$userId"
            )
            val normKey = normalizeUsernameKey(watchedName)
            if (normKey.isNotBlank()) {
                pathsToDelete.add("userAccounts/$normKey")
            }
            for (path in pathsToDelete) {
                try {
                    val delReq = Request.Builder()
                        .url("$DATABASE_URL/$path.json")
                        .delete()
                        .build()
                    httpClient.newCall(delReq).execute().close()
                } catch (_: Exception) {
                }
            }
        }

        onUserDeletedByAdminCallback?.invoke()
    }

    // =========================================================================
    // SUBSCRIPTION PLANS & CRAZY DEAL CONFIG (ADMIN PANEL EDITABLE)
    // =========================================================================

    private fun parsePriceValue(raw: Any?, fallback: Int): Int {
        return when (raw) {
            is Number -> raw.toInt().takeIf { it > 0 } ?: fallback
            is String -> {
                val cleaned = Regex("\\d+").find(raw)?.value?.toIntOrNull()
                if (cleaned != null && cleaned > 0) cleaned else fallback
            }
            else -> fallback
        }
    }

    private fun parseFreeLimitSecondsFromRawValue(raw: Any?, isExplicitSeconds: Boolean, isExplicitMinutes: Boolean): Int? {
        val num = when (raw) {
            is Number -> raw.toInt()
            is String -> Regex("\\d+").find(raw)?.value?.toIntOrNull()
            else -> null
        } ?: return null
        if (num <= 0) return null
        return when {
            isExplicitSeconds -> num.coerceAtLeast(60)
            isExplicitMinutes -> (num * 60).coerceAtLeast(60)
            // If generic key (e.g., "freeTimer", "freeLimit", "freeTime"): values <= 300 are minutes, > 300 are seconds
            num <= 300 -> (num * 60).coerceAtLeast(60)
            else -> num.coerceAtLeast(60)
        }
    }

    private fun parseFreeLimitSecondsFromSnapshot(snapshot: DataSnapshot, fallbackSeconds: Int): Int {
        // Direct primitive node (e.g., /freeTimer = 45)
        val directVal = snapshot.value
        if (directVal is Number || directVal is String) {
            parseFreeLimitSecondsFromRawValue(directVal, isExplicitSeconds = false, isExplicitMinutes = false)?.let {
                return it
            }
        }

        val minutesKeys = listOf(
            "freeDailyLimitMinutes", "freeLimitMinutes", "freeTimeMinutes", "dailyFreeMinutes",
            "freeMinutes", "freeTimerMinutes", "dailyLimitMinutes", "timeLimitMinutes",
            "freeUserMinutes", "freeUserTimeMinutes", "freeListeningMinutes", "limitMinutes"
        )
        for (k in minutesKeys) {
            parseFreeLimitSecondsFromRawValue(snapshot.child(k).value, isExplicitSeconds = false, isExplicitMinutes = true)?.let {
                return it
            }
        }

        val secondsKeys = listOf(
            "freeDailyLimitSeconds", "freeLimitSeconds", "freeTimeSeconds",
            "dailyLimitSeconds", "freeTimerSeconds", "limitSeconds"
        )
        for (k in secondsKeys) {
            parseFreeLimitSecondsFromRawValue(snapshot.child(k).value, isExplicitSeconds = true, isExplicitMinutes = false)?.let {
                return it
            }
        }

        val genericKeys = listOf(
            "freeTimer", "freeTime", "freeLimit", "dailyLimit", "dailyFreeLimit",
            "freeUserLimit", "freeUserTime", "timeLimit", "freePlanLimit", "freeTierLimit", "free"
        )
        for (k in genericKeys) {
            val child = snapshot.child(k)
            val raw = child.child("minutes").value
                ?: child.child("limit").value
                ?: child.child("time").value
                ?: child.child("duration").value
                ?: child.value
            parseFreeLimitSecondsFromRawValue(raw, isExplicitSeconds = false, isExplicitMinutes = false)?.let {
                return it
            }
        }

        return fallbackSeconds
    }

    private fun parseFreeLimitSecondsFromJson(obj: JSONObject, fallbackSeconds: Int): Int {
        val minutesKeys = listOf(
            "freeDailyLimitMinutes", "freeLimitMinutes", "freeTimeMinutes", "dailyFreeMinutes",
            "freeMinutes", "freeTimerMinutes", "dailyLimitMinutes", "timeLimitMinutes",
            "freeUserMinutes", "freeUserTimeMinutes", "freeListeningMinutes", "limitMinutes"
        )
        for (k in minutesKeys) {
            if (obj.has(k)) {
                parseFreeLimitSecondsFromRawValue(obj.opt(k), isExplicitSeconds = false, isExplicitMinutes = true)?.let {
                    return it
                }
            }
        }

        val secondsKeys = listOf(
            "freeDailyLimitSeconds", "freeLimitSeconds", "freeTimeSeconds",
            "dailyLimitSeconds", "freeTimerSeconds", "limitSeconds"
        )
        for (k in secondsKeys) {
            if (obj.has(k)) {
                parseFreeLimitSecondsFromRawValue(obj.opt(k), isExplicitSeconds = true, isExplicitMinutes = false)?.let {
                    return it
                }
            }
        }

        val genericKeys = listOf(
            "freeTimer", "freeTime", "freeLimit", "dailyLimit", "dailyFreeLimit",
            "freeUserLimit", "freeUserTime", "timeLimit", "freePlanLimit", "freeTierLimit", "free"
        )
        for (k in genericKeys) {
            if (obj.has(k)) {
                val subObj = obj.optJSONObject(k)
                val raw = if (subObj != null) {
                    subObj.opt("minutes") ?: subObj.opt("limit") ?: subObj.opt("time") ?: subObj.opt("duration")
                } else {
                    obj.opt(k)
                }
                parseFreeLimitSecondsFromRawValue(raw, isExplicitSeconds = false, isExplicitMinutes = false)?.let {
                    return it
                }
            }
        }

        return fallbackSeconds
    }

    private fun parseUserSpecificFreeLimitFromSnapshot(snapshot: DataSnapshot): Int? {
        // Only check explicit Admin-assigned per-user free limit keys (ignoring the app's own synced status keys unless changed)
        val adminPerUserMinutesKeys = listOf(
            "adminFreeLimitMinutes", "customFreeLimitMinutes", "freeLimitMinutes",
            "freeTimeMinutes", "freeTimerMinutes", "dailyLimitMinutes", "timeLimitMinutes",
            "freeDailyLimitMinutes"
        )
        for (k in adminPerUserMinutesKeys) {
            parseFreeLimitSecondsFromRawValue(snapshot.child(k).value, isExplicitSeconds = false, isExplicitMinutes = true)?.let {
                return it
            }
        }
        val adminPerUserGenericKeys = listOf("freeTimer", "freeLimit", "freeTime", "dailyLimit", "timeLimit")
        for (k in adminPerUserGenericKeys) {
            parseFreeLimitSecondsFromRawValue(snapshot.child(k).value, isExplicitSeconds = false, isExplicitMinutes = false)?.let {
                return it
            }
        }
        val subSnap = snapshot.child("subscription")
        if (subSnap.exists()) {
            for (k in adminPerUserMinutesKeys + adminPerUserGenericKeys) {
                parseFreeLimitSecondsFromRawValue(subSnap.child(k).value, isExplicitSeconds = false, isExplicitMinutes = false)?.let {
                    return it
                }
            }
        }
        return null
    }

    private fun parseUserSpecificFreeLimitFromJson(obj: JSONObject): Int? {
        val adminPerUserMinutesKeys = listOf(
            "adminFreeLimitMinutes", "customFreeLimitMinutes", "freeLimitMinutes",
            "freeTimeMinutes", "freeTimerMinutes", "dailyLimitMinutes", "timeLimitMinutes",
            "freeDailyLimitMinutes"
        )
        for (k in adminPerUserMinutesKeys) {
            if (obj.has(k)) {
                parseFreeLimitSecondsFromRawValue(obj.opt(k), isExplicitSeconds = false, isExplicitMinutes = true)?.let {
                    return it
                }
            }
        }
        val adminPerUserGenericKeys = listOf("freeTimer", "freeLimit", "freeTime", "dailyLimit", "timeLimit")
        for (k in adminPerUserGenericKeys) {
            if (obj.has(k)) {
                parseFreeLimitSecondsFromRawValue(obj.opt(k), isExplicitSeconds = false, isExplicitMinutes = false)?.let {
                    return it
                }
            }
        }
        val subObj = obj.optJSONObject("subscription")
        if (subObj != null) {
            for (k in adminPerUserMinutesKeys + adminPerUserGenericKeys) {
                if (subObj.has(k)) {
                    parseFreeLimitSecondsFromRawValue(subObj.opt(k), isExplicitSeconds = false, isExplicitMinutes = false)?.let {
                        return it
                    }
                }
            }
        }
        return null
    }

    private fun loadCachedSubscriptionConfig(): AdminSubscriptionConfig {
        val def = AdminSubscriptionConfig()
        if (!configCachePrefs.getBoolean("has_cached_config", false)) return def
        return try {
            val mins = configCachePrefs.getInt("free_limit_minutes", def.freeDailyLimitMinutes).coerceAtLeast(1)
            val secs = configCachePrefs.getInt("free_limit_seconds", mins * 60).coerceAtLeast(60)
            AdminSubscriptionConfig(
                weeklyPriceInr = configCachePrefs.getInt("weekly_price", def.weeklyPriceInr),
                monthlyPriceInr = configCachePrefs.getInt("monthly_price", def.monthlyPriceInr),
                foreverPriceInr = configCachePrefs.getInt("forever_price", def.foreverPriceInr),
                crazyDealPriceInr = configCachePrefs.getInt("crazy_price", def.crazyDealPriceInr),
                crazyDealDurationDays = configCachePrefs.getInt("crazy_duration_days", def.crazyDealDurationDays),
                crazyDealValidityHours = configCachePrefs.getInt("crazy_validity_hours", def.crazyDealValidityHours),
                crazyDealEnabled = configCachePrefs.getBoolean("crazy_enabled", def.crazyDealEnabled),
                crazyDealTitle = configCachePrefs.getString("crazy_title", def.crazyDealTitle) ?: def.crazyDealTitle,
                crazyDealSubtitle = configCachePrefs.getString("crazy_subtitle", def.crazyDealSubtitle) ?: def.crazyDealSubtitle,
                crazyDealBannerUrl = configCachePrefs.getString("crazy_banner_url", def.crazyDealBannerUrl) ?: def.crazyDealBannerUrl,
                crazyDealUpdatedAt = configCachePrefs.getLong("crazy_updated_at", 0L),
                premiumEnabled = configCachePrefs.getBoolean("premium_enabled", def.premiumEnabled),
                premiumTitle = configCachePrefs.getString("premium_title", def.premiumTitle) ?: def.premiumTitle,
                premiumSubtitle = configCachePrefs.getString("premium_subtitle", def.premiumSubtitle) ?: def.premiumSubtitle,
                premiumHeading = configCachePrefs.getString("premium_heading", def.premiumHeading) ?: def.premiumHeading,
                premiumDescription = configCachePrefs.getString("premium_description", def.premiumDescription) ?: def.premiumDescription,
                premiumBannerUrl = configCachePrefs.getString("premium_banner_url", def.premiumBannerUrl) ?: def.premiumBannerUrl,
                freeDailyLimitMinutes = mins,
                freeDailyLimitSeconds = secs
            )
        } catch (_: Exception) {
            def
        }
    }

    private fun updateAndPersistSubscriptionConfig(newConfig: AdminSubscriptionConfig) {
        _subscriptionConfig.value = newConfig
        _isSubscriptionConfigLoaded.value = true
        try {
            configCachePrefs.edit()
                .putBoolean("has_cached_config", true)
                .putInt("weekly_price", newConfig.weeklyPriceInr)
                .putInt("monthly_price", newConfig.monthlyPriceInr)
                .putInt("forever_price", newConfig.foreverPriceInr)
                .putInt("crazy_price", newConfig.crazyDealPriceInr)
                .putInt("crazy_duration_days", newConfig.crazyDealDurationDays)
                .putInt("crazy_validity_hours", newConfig.crazyDealValidityHours)
                .putBoolean("crazy_enabled", newConfig.crazyDealEnabled)
                .putString("crazy_title", newConfig.crazyDealTitle)
                .putString("crazy_subtitle", newConfig.crazyDealSubtitle)
                .putString("crazy_banner_url", newConfig.crazyDealBannerUrl)
                .putLong("crazy_updated_at", newConfig.crazyDealUpdatedAt)
                .putBoolean("premium_enabled", newConfig.premiumEnabled)
                .putString("premium_title", newConfig.premiumTitle)
                .putString("premium_subtitle", newConfig.premiumSubtitle)
                .putString("premium_heading", newConfig.premiumHeading)
                .putString("premium_description", newConfig.premiumDescription)
                .putString("premium_banner_url", newConfig.premiumBannerUrl)
                .putInt("free_limit_minutes", newConfig.freeDailyLimitMinutes)
                .putInt("free_limit_seconds", newConfig.freeDailyLimitSeconds)
                .apply()
        } catch (_: Exception) {
        }
    }

    private fun parseSubscriptionConfigSnapshot(snapshot: DataSnapshot): AdminSubscriptionConfig? {
        return try {
            val current = _subscriptionConfig.value
            val weekly = parsePriceValue(
                snapshot.child("weekly").value
                    ?: snapshot.child("weeklyPrice").value
                    ?: snapshot.child("weekly").child("price").value,
                current.weeklyPriceInr
            )
            val monthly = parsePriceValue(
                snapshot.child("monthly").value
                    ?: snapshot.child("monthlyPrice").value
                    ?: snapshot.child("monthly").child("price").value,
                current.monthlyPriceInr
            )
            val forever = parsePriceValue(
                snapshot.child("forever").value
                    ?: snapshot.child("foreverPrice").value
                    ?: snapshot.child("lifetimePrice").value
                    ?: snapshot.child("forever").child("price").value,
                current.foreverPriceInr
            )
            val crazyPrice = parsePriceValue(
                snapshot.child("firstUserDeal").value
                    ?: snapshot.child("crazyDealPrice").value
                    ?: snapshot.child("cheapestPrice").value
                    ?: snapshot.child("firstUserPrice").value
                    ?: snapshot.child("crazyDeal").child("price").value
                    ?: snapshot.child("crazy_deal").value,
                current.crazyDealPriceInr
            )

            current.copy(
                weeklyPriceInr = weekly,
                monthlyPriceInr = monthly,
                foreverPriceInr = forever,
                crazyDealPriceInr = crazyPrice
            )
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing subscriptionPlans snapshot", e)
            null
        }
    }

    private fun parsePremiumBannersSnapshot(snapshot: DataSnapshot): AdminSubscriptionConfig? {
        return try {
            var updated = _subscriptionConfig.value
            val premiumSnap = snapshot.child("premium")
            if (premiumSnap.exists()) {
                val img = premiumSnap.child("image").getValue(String::class.java)?.trim().orEmpty()
                val title = premiumSnap.child("title").getValue(String::class.java)?.trim().orEmpty()
                val subtitle = premiumSnap.child("subtitle").getValue(String::class.java)?.trim().orEmpty()
                val heading = premiumSnap.child("heading").getValue(String::class.java)?.trim().orEmpty()
                val desc = premiumSnap.child("description").getValue(String::class.java)?.trim().orEmpty()
                val enabled = premiumSnap.child("enabled").getValue(Boolean::class.java) ?: updated.premiumEnabled
                updated = updated.copy(
                    premiumBannerUrl = img.ifBlank { updated.premiumBannerUrl },
                    premiumTitle = title.ifBlank { updated.premiumTitle },
                    premiumSubtitle = subtitle.ifBlank { updated.premiumSubtitle },
                    premiumHeading = heading.ifBlank { updated.premiumHeading },
                    premiumDescription = desc.ifBlank { updated.premiumDescription },
                    premiumEnabled = enabled
                )
            }
            val dealSnap = snapshot.child("firstUserDeal")
            if (dealSnap.exists()) {
                val img = dealSnap.child("image").getValue(String::class.java)?.trim().orEmpty()
                val title = dealSnap.child("title").getValue(String::class.java)?.trim().orEmpty()
                val desc = dealSnap.child("description").getValue(String::class.java)?.trim().orEmpty()
                val price = parsePriceValue(dealSnap.child("price").value, updated.crazyDealPriceInr)
                val duration = parsePriceValue(dealSnap.child("duration").value, updated.crazyDealDurationDays)
                val validity = parsePriceValue(dealSnap.child("validity").value, updated.crazyDealValidityHours)
                val enabled = dealSnap.child("enabled").getValue(Boolean::class.java) ?: updated.crazyDealEnabled
                val updatedAt = (dealSnap.child("updatedAt").value as? Number)?.toLong() ?: updated.crazyDealUpdatedAt
                updated = updated.copy(
                    crazyDealBannerUrl = img.ifBlank { updated.crazyDealBannerUrl },
                    crazyDealTitle = title.ifBlank { updated.crazyDealTitle },
                    crazyDealSubtitle = desc.ifBlank { updated.crazyDealSubtitle },
                    crazyDealPriceInr = price,
                    crazyDealDurationDays = duration,
                    crazyDealValidityHours = validity,
                    crazyDealEnabled = enabled,
                    crazyDealUpdatedAt = updatedAt
                )
            }
            updated
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing premiumBanners snapshot", e)
            null
        }
    }

    suspend fun fetchSubscriptionConfigViaRest(seedIfMissing: Boolean = false) = withContext(Dispatchers.IO) {
        var workingConfig = _subscriptionConfig.value
        var foundPlans = false

        // 1. Read /subscriptionPlans (Admin.html: weekly, monthly, forever, firstUserDeal)
        try {
            val req = Request.Builder()
                .url("$DATABASE_URL/subscriptionPlans.json")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string()?.trim().orEmpty()
                    if (body.isNotEmpty() && body != "null" && body.startsWith("{")) {
                        foundPlans = true
                        val obj = JSONObject(body)
                        val weekly = parsePriceValue(obj.opt("weekly"), workingConfig.weeklyPriceInr)
                        val monthly = parsePriceValue(obj.opt("monthly"), workingConfig.monthlyPriceInr)
                        val forever = parsePriceValue(obj.opt("forever"), workingConfig.foreverPriceInr)
                        val dealPrice = parsePriceValue(obj.opt("firstUserDeal"), workingConfig.crazyDealPriceInr)
                        workingConfig = workingConfig.copy(
                            weeklyPriceInr = weekly,
                            monthlyPriceInr = monthly,
                            foreverPriceInr = forever,
                            crazyDealPriceInr = dealPrice
                        )
                    }
                }
            }
        } catch (_: Exception) {
        }

        // 2. Read /premiumBanners (Admin.html: Banner 1 /premiumBanners/premium & Banner 2 /premiumBanners/firstUserDeal)
        try {
            val bannerReq = Request.Builder()
                .url("$DATABASE_URL/premiumBanners.json")
                .get()
                .build()
            httpClient.newCall(bannerReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string()?.trim().orEmpty()
                    if (body.isNotEmpty() && body != "null" && body.startsWith("{")) {
                        val root = JSONObject(body)
                        val premiumObj = root.optJSONObject("premium")
                        if (premiumObj != null) {
                            val img = premiumObj.optString("image").trim()
                            val title = premiumObj.optString("title").trim()
                            val subtitle = premiumObj.optString("subtitle").trim()
                            val heading = premiumObj.optString("heading").trim()
                            val desc = premiumObj.optString("description").trim()
                            val enabled = premiumObj.optBoolean("enabled", workingConfig.premiumEnabled)
                            workingConfig = workingConfig.copy(
                                premiumBannerUrl = img.ifBlank { workingConfig.premiumBannerUrl },
                                premiumTitle = title.ifBlank { workingConfig.premiumTitle },
                                premiumSubtitle = subtitle.ifBlank { workingConfig.premiumSubtitle },
                                premiumHeading = heading.ifBlank { workingConfig.premiumHeading },
                                premiumDescription = desc.ifBlank { workingConfig.premiumDescription },
                                premiumEnabled = enabled
                            )
                        }
                        val dealObj = root.optJSONObject("firstUserDeal")
                        if (dealObj != null) {
                            val img = dealObj.optString("image").trim()
                            val title = dealObj.optString("title").trim()
                            val desc = dealObj.optString("description").trim()
                            val price = parsePriceValue(dealObj.opt("price"), workingConfig.crazyDealPriceInr)
                            val duration = parsePriceValue(dealObj.opt("duration"), workingConfig.crazyDealDurationDays)
                            val validity = parsePriceValue(dealObj.opt("validity"), workingConfig.crazyDealValidityHours)
                            val enabled = dealObj.optBoolean("enabled", workingConfig.crazyDealEnabled)
                            val updatedAt = dealObj.optLong("updatedAt", workingConfig.crazyDealUpdatedAt)
                            workingConfig = workingConfig.copy(
                                crazyDealBannerUrl = img.ifBlank { workingConfig.crazyDealBannerUrl },
                                crazyDealTitle = title.ifBlank { workingConfig.crazyDealTitle },
                                crazyDealSubtitle = desc.ifBlank { workingConfig.crazyDealSubtitle },
                                crazyDealPriceInr = price,
                                crazyDealDurationDays = duration,
                                crazyDealValidityHours = validity,
                                crazyDealEnabled = enabled,
                                crazyDealUpdatedAt = updatedAt
                            )
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }

        // 3. Read /freeUserTimeLimitMinutes (Admin.html: Free user daily listening limit in minutes, 1..1440)
        try {
            val req = Request.Builder()
                .url("$DATABASE_URL/freeUserTimeLimitMinutes.json")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string()?.trim().orEmpty()
                    if (body.isNotEmpty() && body != "null") {
                        val parsedSecs = if (body.startsWith("{")) {
                            parseFreeLimitSecondsFromJson(JSONObject(body), workingConfig.freeDailyLimitSeconds)
                        } else {
                            parseFreeLimitSecondsFromRawValue(body.trim('"'), isExplicitSeconds = false, isExplicitMinutes = true)
                                ?: workingConfig.freeDailyLimitSeconds
                        }
                        if (parsedSecs >= 60) {
                            workingConfig = workingConfig.copy(
                                freeDailyLimitMinutes = (parsedSecs / 60).coerceAtLeast(1),
                                freeDailyLimitSeconds = parsedSecs
                            )
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }

        // Commit atomic update & persist to cache so there is zero UI flicker
        updateAndPersistSubscriptionConfig(workingConfig)

        // Seed /subscriptionPlans in Firebase only if completely missing
        if (!foundPlans && seedIfMissing) {
            try {
                val seedPlans = JSONObject().apply {
                    put("weekly", workingConfig.weeklyPriceInr)
                    put("monthly", workingConfig.monthlyPriceInr)
                    put("forever", workingConfig.foreverPriceInr)
                    put("firstUserDeal", workingConfig.crazyDealPriceInr)
                }
                val body = seedPlans.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                val req = Request.Builder()
                    .url("$DATABASE_URL/subscriptionPlans.json")
                    .put(body)
                    .build()
                httpClient.newCall(req).execute().close()
            } catch (_: Exception) {
            }
        }
    }

    // =========================================================================
    // ADMIN APP CONTROL (/appControl: SECRET CODE, MAINTENANCE MODE, APP UPDATE)
    // =========================================================================

    private fun loadCachedAppControlConfig(): AdminAppControlConfig {
        val def = AdminAppControlConfig()
        if (!configCachePrefs.getBoolean("has_cached_app_control", false)) return def
        return try {
            AdminAppControlConfig(
                secretCode = configCachePrefs.getString("app_ctrl_secret_code", def.secretCode) ?: def.secretCode,
                userPanelEnabled = configCachePrefs.getBoolean("app_ctrl_panel_enabled", def.userPanelEnabled),
                maintenanceMessage = configCachePrefs.getString("app_ctrl_maint_msg", def.maintenanceMessage) ?: def.maintenanceMessage,
                updateEnabled = configCachePrefs.getBoolean("app_ctrl_update_enabled", def.updateEnabled),
                updateLink = configCachePrefs.getString("app_ctrl_update_link", def.updateLink) ?: def.updateLink,
                updateMessage = configCachePrefs.getString("app_ctrl_update_msg", def.updateMessage) ?: def.updateMessage,
                closeAppButtonEnabled = configCachePrefs.getBoolean("app_ctrl_close_btn", def.closeAppButtonEnabled),
                updatedAt = configCachePrefs.getLong("app_ctrl_updated_at", def.updatedAt)
            )
        } catch (_: Exception) {
            def
        }
    }

    private fun updateAndPersistAppControlConfig(newConfig: AdminAppControlConfig) {
        _appControlConfig.value = newConfig
        try {
            configCachePrefs.edit()
                .putBoolean("has_cached_app_control", true)
                .putString("app_ctrl_secret_code", newConfig.secretCode)
                .putBoolean("app_ctrl_panel_enabled", newConfig.userPanelEnabled)
                .putString("app_ctrl_maint_msg", newConfig.maintenanceMessage)
                .putBoolean("app_ctrl_update_enabled", newConfig.updateEnabled)
                .putString("app_ctrl_update_link", newConfig.updateLink)
                .putString("app_ctrl_update_msg", newConfig.updateMessage)
                .putBoolean("app_ctrl_close_btn", newConfig.closeAppButtonEnabled)
                .putLong("app_ctrl_updated_at", newConfig.updatedAt)
                .apply()
        } catch (_: Exception) {
        }
    }

    private fun parseAppControlSnapshot(snapshot: DataSnapshot): AdminAppControlConfig? {
        return try {
            val def = AdminAppControlConfig()
            val secretCode = when (val raw = snapshot.child("secretCode").value) {
                is Number -> raw.toLong().toString()
                is String -> raw.trim()
                else -> ""
            }
            val userPanelEnabled = snapshot.child("userPanelEnabled").value?.let {
                parseBooleanField(it)
            } ?: true
            val maintenanceMessage = snapshot.child("maintenanceMessage").getValue(String::class.java)?.trim()
                .orEmpty()
                .ifBlank { def.maintenanceMessage }
            val updateEnabled = snapshot.child("updateEnabled").value?.let {
                parseBooleanField(it)
            } ?: false
            val updateLink = snapshot.child("updateLink").getValue(String::class.java)?.trim().orEmpty()
            val updateMessage = snapshot.child("updateMessage").getValue(String::class.java)?.trim()
                .orEmpty()
                .ifBlank { def.updateMessage }
            val closeAppButtonEnabled = snapshot.child("closeAppButtonEnabled").value?.let {
                parseBooleanField(it)
            } ?: true
            val updatedAt = (snapshot.child("updatedAt").value as? Number)?.toLong() ?: 0L

            AdminAppControlConfig(
                secretCode = secretCode,
                userPanelEnabled = userPanelEnabled,
                maintenanceMessage = maintenanceMessage,
                updateEnabled = updateEnabled,
                updateLink = updateLink,
                updateMessage = updateMessage,
                closeAppButtonEnabled = closeAppButtonEnabled,
                updatedAt = updatedAt
            )
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing appControl snapshot", e)
            null
        }
    }

    suspend fun fetchAppControlViaRest() = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("$DATABASE_URL/appControl.json")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string()?.trim().orEmpty()
                    if (body.isEmpty() || body == "null") {
                        updateAndPersistAppControlConfig(AdminAppControlConfig())
                    } else if (body.startsWith("{")) {
                        val obj = JSONObject(body)
                        val def = AdminAppControlConfig()
                        val rawSecret = obj.opt("secretCode")
                        val secretCode = when (rawSecret) {
                            is Number -> rawSecret.toLong().toString()
                            is String -> rawSecret.trim()
                            else -> obj.optString("secretCode", "").trim()
                        }
                        val userPanelEnabled = if (obj.has("userPanelEnabled")) {
                            parseBooleanField(obj.opt("userPanelEnabled"))
                        } else {
                            true
                        }
                        val maintenanceMessage = obj.optString("maintenanceMessage", "").trim()
                            .ifBlank { def.maintenanceMessage }
                        val updateEnabled = if (obj.has("updateEnabled")) {
                            parseBooleanField(obj.opt("updateEnabled"))
                        } else {
                            false
                        }
                        val updateLink = obj.optString("updateLink", "").trim()
                        val updateMessage = obj.optString("updateMessage", "").trim()
                            .ifBlank { def.updateMessage }
                        val closeAppButtonEnabled = if (obj.has("closeAppButtonEnabled")) {
                            parseBooleanField(obj.opt("closeAppButtonEnabled"))
                        } else {
                            true
                        }
                        val updatedAt = obj.optLong("updatedAt", 0L)

                        updateAndPersistAppControlConfig(
                            AdminAppControlConfig(
                                secretCode = secretCode,
                                userPanelEnabled = userPanelEnabled,
                                maintenanceMessage = maintenanceMessage,
                                updateEnabled = updateEnabled,
                                updateLink = updateLink,
                                updateMessage = updateMessage,
                                closeAppButtonEnabled = closeAppButtonEnabled,
                                updatedAt = updatedAt
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    // =========================================================================
    // UNIQUE USERNAME + PASSWORD AUTHENTICATION (SIGN IN & CREATE ACCOUNT)
    // =========================================================================

    private fun normalizeUsernameKey(rawName: String): String {
        return rawName.trim()
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9_.-]"), "_")
            .trim('_')
    }

    /**
     * Authenticates or registers a user in Firebase Realtime Database (/userAccounts and /users).
     * - Guarantees 100% unique usernames across all users: no two users can register the same username.
     * - Sign In verifies username + password against Firebase and restores any active subscription.
     */
    suspend fun authenticateUserAccount(
        userName: String,
        password: String,
        isCreateAccount: Boolean
    ): Result<CloudUserAuthResult> = withContext(Dispatchers.IO) {
        val cleanName = userName.trim()
        val cleanPassword = password.trim()
        if (cleanName.length < 2) {
            return@withContext Result.failure(IllegalArgumentException("Username must be at least 2 characters."))
        }
        if (cleanPassword.length < 4) {
            return@withContext Result.failure(IllegalArgumentException("Password must be at least 4 characters."))
        }

        val normalizedKey = normalizeUsernameKey(cleanName)
        if (normalizedKey.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Please enter a valid username."))
        }

        try {
            // 1. Check /userAccounts/{normalizedKey} first
            var existingAccountObj: JSONObject? = null
            val accReq = Request.Builder()
                .url("$DATABASE_URL/userAccounts/$normalizedKey.json")
                .get()
                .build()
            httpClient.newCall(accReq).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(Exception("Network error (${resp.code}). Please check your internet connection."))
                }
                val body = resp.body?.string()?.trim().orEmpty()
                if (body.isNotEmpty() && body != "null" && body.startsWith("{")) {
                    val parsed = JSONObject(body)
                    if (!isMarkedDeletedInJson(parsed)) {
                        existingAccountObj = parsed
                    }
                }
            }

            // 2. O(1) Direct User Node Lookup (scales to 1,00,000+ users without downloading all of /users.json!)
            var existingUserNodeObj: JSONObject? = null
            var existingUserNodeKey: String? = null
            val mappedUserId = existingAccountObj?.optString("userId", "")?.trim().orEmpty()
            if (mappedUserId.isNotBlank()) {
                val directUserReq = Request.Builder()
                    .url("$DATABASE_URL/users/$mappedUserId.json")
                    .get()
                    .build()
                httpClient.newCall(directUserReq).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string()?.trim().orEmpty()
                        if (body.isNotEmpty() && body != "null" && body.startsWith("{")) {
                            val uObj = JSONObject(body)
                            if (!isMarkedDeletedInJson(uObj)) {
                                existingUserNodeObj = uObj
                                existingUserNodeKey = mappedUserId
                            }
                        }
                    }
                }
            }

            // Fallback indexed query only if account mapping didn't exist yet
            if (existingUserNodeObj == null) {
                val encodedName = java.net.URLEncoder.encode("\"$cleanName\"", "UTF-8")
                val indexedUsersReq = Request.Builder()
                    .url("$DATABASE_URL/users.json?orderBy=%22userName%22&equalTo=$encodedName")
                    .get()
                    .build()
                httpClient.newCall(indexedUsersReq).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string()?.trim().orEmpty()
                        if (body.isNotEmpty() && body != "null" && body.startsWith("{")) {
                            val root = JSONObject(body)
                            val keys = root.keys()
                            while (keys.hasNext()) {
                                val k = keys.next()
                                val uObj = root.optJSONObject(k) ?: continue
                                if (isMarkedDeletedInJson(uObj)) continue
                                existingUserNodeObj = uObj
                                existingUserNodeKey = k
                                break
                            }
                        }
                    }
                }
            }

            val nowMs = System.currentTimeMillis()

            if (isCreateAccount) {
                // CREATE ACCOUNT MODE:
                // Only block if the user STILL exists in /users (if Admin deleted them from /users, allow re-creating!)
                if (existingUserNodeObj != null) {
                    return@withContext Result.failure(
                        IllegalStateException("Username \"$cleanName\" is already taken! Please choose a different username or Sign In.")
                    )
                }

                val slug = normalizedKey.take(18).ifBlank { "user" }
                val shortHash = UUID.randomUUID().toString().replace("-", "").take(6).lowercase(Locale.US)
                val newUserId = "user_${slug}_$shortHash"

                val accountPayload = JSONObject().apply {
                    put("userId", newUserId)
                    put("userName", cleanName)
                    put("username", cleanName)
                    put("normalizedUsername", normalizedKey)
                    put("password", cleanPassword)
                    put("createdAt", nowMs)
                    put("lastLogin", nowMs)
                    put("status", "Active")
                }

                val putBody = accountPayload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                val putReq = Request.Builder()
                    .url("$DATABASE_URL/userAccounts/$normalizedKey.json")
                    .put(putBody)
                    .build()
                httpClient.newCall(putReq).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        return@withContext Result.failure(Exception("Failed to create account (HTTP ${resp.code})."))
                    }
                }

                // Immediately create the user record in /users/{newUserId} as well so Admin Panel sees the user & password right away
                val initialUserPayload = JSONObject().apply {
                    put("id", newUserId)
                    put("uid", newUserId)
                    put("userId", newUserId)
                    put("name", cleanName)
                    put("userName", cleanName)
                    put("username", cleanName)
                    put("displayName", cleanName)
                    put("password", cleanPassword)
                    put("email", "🔑 Pass: $cleanPassword")
                    put("status", "Active")
                    put("createdAt", nowMs)
                    put("lastLogin", nowMs)
                }
                val userBody = initialUserPayload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                httpClient.newCall(
                    Request.Builder()
                        .url("$DATABASE_URL/users/$newUserId.json")
                        .put(userBody)
                        .build()
                ).execute().close()

                return@withContext Result.success(
                    CloudUserAuthResult(
                        userId = newUserId,
                        userName = cleanName,
                        password = cleanPassword,
                        createdAtMs = nowMs,
                        isNewAccount = true,
                        subscriptionState = null
                    )
                )
            } else {
                // SIGN IN MODE:
                // If the user was deleted from /users by Admin, permanently remove any leftover /userAccounts entry and reject login!
                if (existingUserNodeObj == null) {
                    if (existingAccountObj != null) {
                        try {
                            httpClient.newCall(
                                Request.Builder()
                                    .url("$DATABASE_URL/userAccounts/$normalizedKey.json")
                                    .delete()
                                    .build()
                            ).execute().close()
                        } catch (_: Exception) {
                        }
                        return@withContext Result.failure(
                            IllegalArgumentException("Account \"$cleanName\" was permanently deleted by Admin. Please Create a new account.")
                        )
                    }
                    return@withContext Result.failure(
                        IllegalArgumentException("Username \"$cleanName\" not found! Tap \"Create your account\" below to register.")
                    )
                }

                val matchedRecord = existingUserNodeObj
                val savedPassword = existingUserNodeObj.optString("password", "").trim()
                    .ifBlank { existingAccountObj?.optString("password", "")?.trim().orEmpty() }

                if (savedPassword.isNotBlank() && savedPassword != cleanPassword) {
                    return@withContext Result.failure(
                        IllegalArgumentException("Incorrect password for \"$cleanName\". Please try again.")
                    )
                }

                val resolvedUserId = matchedRecord.optString("userId")
                    .ifBlank { matchedRecord.optString("id") }
                    .ifBlank { existingUserNodeKey.orEmpty() }
                    .ifBlank {
                        val slug = normalizedKey.take(18).ifBlank { "user" }
                        "user_${slug}_${UUID.randomUUID().toString().take(6)}"
                    }

                val resolvedDisplayName = matchedRecord.optString("userName")
                    .ifBlank { matchedRecord.optString("name") }
                    .ifBlank { cleanName }

                val createdAtMs = matchedRecord.optLong("createdAt", 0L)
                    .takeIf { it > 0L }
                    ?: existingAccountObj?.optLong("createdAt", 0L)?.takeIf { it > 0L }
                    ?: nowMs

                // Parse any active subscription stored in cloud for this user
                val cloudSub = parseCloudSubscriptionFromJson(existingUserNodeObj)
                val savedSecretCode = matchedRecord.optString("secretCode", "").trim()
                    .ifBlank { existingAccountObj?.optString("secretCode", "")?.trim().orEmpty() }

                // Ensure /userAccounts/{normalizedKey} is up-to-date with the verified password & userId
                val updatedAccountPayload = JSONObject().apply {
                    put("userId", resolvedUserId)
                    put("userName", resolvedDisplayName)
                    put("username", resolvedDisplayName)
                    put("normalizedUsername", normalizedKey)
                    put("password", cleanPassword)
                    put("createdAt", createdAtMs)
                    put("lastLogin", nowMs)
                    put("status", "Active")
                    if (savedSecretCode.isNotBlank()) {
                        put("secretCode", savedSecretCode)
                    }
                    if (cloudSub != null) {
                        put("subscriptionPlan", cloudSub.planType.id)
                        put("subscriptionActivatedAt", cloudSub.activatedAtMs)
                        put("subscriptionExpiresAt", cloudSub.expiresAtMs)
                        put("subscriptionOrderId", cloudSub.orderId)
                        put("subscriptionUtr", cloudSub.utr)
                    }
                }
                try {
                    val putBody = updatedAccountPayload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                    val putReq = Request.Builder()
                        .url("$DATABASE_URL/userAccounts/$normalizedKey.json")
                        .put(putBody)
                        .build()
                    httpClient.newCall(putReq).execute().close()
                } catch (_: Exception) {
                }

                // Also fetch today's cloud listening time for this user so logging out & back in never resets their daily usage
                val todayLocalDateKey = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date(nowMs))
                val todayUtcDateKey = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                }.format(java.util.Date(nowMs))
                var cloudTodayListenedSeconds = 0
                val recordDateKey = matchedRecord.optString("dailyListenedDate", "").trim()
                if (recordDateKey == todayLocalDateKey || recordDateKey == todayUtcDateKey) {
                    cloudTodayListenedSeconds = maxOf(
                        cloudTodayListenedSeconds,
                        matchedRecord.optInt("dailyListenedSeconds", 0),
                        matchedRecord.optInt("todaySeconds", 0),
                        matchedRecord.optInt("usedSeconds", 0)
                    )
                }
                try {
                    val usageReq = Request.Builder()
                        .url("$DATABASE_URL/userTimeUsage/$resolvedUserId.json")
                        .get()
                        .build()
                    httpClient.newCall(usageReq).execute().use { usageResp ->
                        if (usageResp.isSuccessful) {
                            val usageBody = usageResp.body?.string()?.trim().orEmpty()
                            if (usageBody.isNotEmpty() && usageBody != "null" && usageBody.startsWith("{")) {
                                val usageObj = JSONObject(usageBody)
                                val dailyObj = usageObj.optJSONObject("daily")
                                val todayEntry = dailyObj?.optJSONObject(todayLocalDateKey)
                                    ?: dailyObj?.optJSONObject(todayUtcDateKey)
                                if (todayEntry != null) {
                                    cloudTodayListenedSeconds = maxOf(
                                        cloudTodayListenedSeconds,
                                        todayEntry.optInt("totalSeconds", 0),
                                        todayEntry.optInt("usedSeconds", 0),
                                        todayEntry.optInt("listeningSeconds", 0)
                                    )
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                }

                return@withContext Result.success(
                    CloudUserAuthResult(
                        userId = resolvedUserId,
                        userName = resolvedDisplayName,
                        password = cleanPassword,
                        createdAtMs = createdAtMs,
                        isNewAccount = false,
                        subscriptionState = cloudSub,
                        secretCode = savedSecretCode,
                        todayListenedSeconds = cloudTodayListenedSeconds
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Authentication failed", e)
            return@withContext Result.failure(
                Exception("Connection error while verifying account. Please check your internet.")
            )
        }
    }

    private fun resolveAdminPlanType(rawPlanStr: String, hasExplicitVipBoolean: Boolean): SubscriptionPlanType {
        val cleaned = rawPlanStr.trim().lowercase(Locale.US)
        return when {
            cleaned.contains("custom") -> SubscriptionPlanType.CUSTOM
            cleaned.contains("week") -> SubscriptionPlanType.WEEKLY
            cleaned.contains("month") -> SubscriptionPlanType.MONTHLY
            cleaned.contains("forever") || cleaned.contains("life") || cleaned.contains("perm") || cleaned.contains("unlimit") -> SubscriptionPlanType.FOREVER
            cleaned.contains("crazy") || cleaned.contains("deal") || cleaned.contains("cheap") || cleaned.contains("first") -> SubscriptionPlanType.CRAZY_DEAL
            cleaned == "vip" || cleaned == "premium" || cleaned == "pro" || cleaned == "true" || cleaned == "1" || cleaned == "active" -> SubscriptionPlanType.FOREVER
            cleaned.isBlank() || cleaned == "none" || cleaned == "free" -> {
                if (hasExplicitVipBoolean) SubscriptionPlanType.FOREVER else SubscriptionPlanType.NONE
            }
            else -> {
                val parsed = SubscriptionPlanType.fromId(cleaned)
                if (parsed != SubscriptionPlanType.NONE) parsed
                else if (hasExplicitVipBoolean) SubscriptionPlanType.FOREVER
                else SubscriptionPlanType.NONE
            }
        }
    }

    private fun parseCloudSubscriptionFromSnapshot(snapshot: DataSnapshot): UserSubscriptionState? {
        return try {
            val subSnap = snapshot.child("subscription")
            val subStatus = subSnap.child("status").getValue(String::class.java)?.trim()?.lowercase().orEmpty()
            // If Admin deactivated the subscription (status == 'inactive'), immediately return null!
            if (subSnap.exists() && (
                    subStatus == "inactive" ||
                        subStatus == "deactivated" ||
                        subStatus == "cancelled" ||
                        subStatus == "expired" ||
                        subStatus == "none" ||
                        subStatus == "off" ||
                        subStatus == "false"
                    )
            ) {
                return null
            }

            val isSubNodeActive = subStatus == "active" || subStatus == "success" || subStatus == "complimentary"

            val isVipFlag = isSubNodeActive ||
                (!subSnap.exists() && (
                    parseBooleanField(snapshot.child("isVip").value) ||
                        parseBooleanField(snapshot.child("vip").value) ||
                        parseBooleanField(snapshot.child("isVIP").value) ||
                        parseBooleanField(snapshot.child("isSubscribed").value) ||
                        parseBooleanField(snapshot.child("isPremium").value) ||
                        snapshot.child("role").getValue(String::class.java)?.equals("vip", ignoreCase = true) == true ||
                        snapshot.child("status").getValue(String::class.java)?.equals("vip", ignoreCase = true) == true
                    ))

            val rawPlan = if (subSnap.exists()) {
                (
                    subSnap.child("plan").getValue(String::class.java)
                        ?: subSnap.child("planType").getValue(String::class.java)
                        ?: ""
                    ).trim()
            } else {
                (
                    snapshot.child("vipPlan").getValue(String::class.java)
                        ?: snapshot.child("vipType").getValue(String::class.java)
                        ?: snapshot.child("subscriptionPlan").getValue(String::class.java)
                        ?: snapshot.child("plan").getValue(String::class.java)
                        ?: ""
                    ).trim()
            }

            val planType = resolveAdminPlanType(rawPlan, isVipFlag)
            if (planType == SubscriptionPlanType.NONE) return null

            val nowMs = System.currentTimeMillis()
            val activatedAt = (
                (subSnap.child("startAt").value as? Number)?.toLong()
                    ?: (subSnap.child("grantedAt").value as? Number)?.toLong()
                    ?: (subSnap.child("activatedAtMs").value as? Number)?.toLong()
                    ?: (snapshot.child("subscriptionActivatedAt").value as? Number)?.toLong()
                    ?: (snapshot.child("vipActivatedAt").value as? Number)?.toLong()
                    ?: nowMs
                ).let { if (it > 0L) it else nowMs }

            val customDays = (
                (subSnap.child("days").value as? Number)?.toInt()
                    ?: (snapshot.child("vipDays").value as? Number)?.toInt()
                    ?: (snapshot.child("subscriptionDays").value as? Number)?.toInt()
                    ?: 0
                )

            val rawExpiresAt = (
                (subSnap.child("endAt").value as? Number)?.toLong()
                    ?: (subSnap.child("expiresAtMs").value as? Number)?.toLong()
                    ?: (snapshot.child("vipExpiresAt").value as? Number)?.toLong()
                    ?: (snapshot.child("subscriptionExpiresAt").value as? Number)?.toLong()
                    ?: 0L
                )

            val defaultDurationMs = when {
                customDays > 0 -> customDays * 86_400_000L
                planType == SubscriptionPlanType.WEEKLY -> 7L * 86_400_000L
                planType == SubscriptionPlanType.MONTHLY -> 30L * 86_400_000L
                planType == SubscriptionPlanType.CRAZY_DEAL -> _subscriptionConfig.value.crazyDealDurationDays.coerceAtLeast(1) * 86_400_000L
                else -> 100L * 365L * 86_400_000L
            }

            val resolvedExpiresAt = when {
                planType == SubscriptionPlanType.FOREVER -> activatedAt + 100L * 365L * 86_400_000L
                rawExpiresAt > 0L -> rawExpiresAt
                else -> activatedAt + defaultDurationMs
            }
            val orderId = subSnap.child("orderId").getValue(String::class.java)
                ?: snapshot.child("subscriptionOrderId").getValue(String::class.java)
                ?: "admin_vip_grant"
            val utr = subSnap.child("utr").getValue(String::class.java)
                ?: snapshot.child("subscriptionUtr").getValue(String::class.java)
                ?: "ADMIN_VIP"

            val state = UserSubscriptionState(
                planType = planType,
                activatedAtMs = activatedAt,
                expiresAtMs = resolvedExpiresAt,
                orderId = orderId,
                utr = utr
            )
            if (state.isActive(nowMs)) state else null
        } catch (_: Exception) {
            null
        }
    }

    private fun parseCloudSubscriptionFromJson(obj: JSONObject?): UserSubscriptionState? {
        if (obj == null) return null
        val subObj = obj.optJSONObject("subscription")
        val subStatus = subObj?.optString("status", "")?.trim()?.lowercase().orEmpty()
        // If Admin deactivated the subscription (status == 'inactive'), immediately return null!
        if (subObj != null && (
                subStatus == "inactive" ||
                    subStatus == "deactivated" ||
                    subStatus == "cancelled" ||
                    subStatus == "expired" ||
                    subStatus == "none" ||
                    subStatus == "off" ||
                    subStatus == "false"
                )
        ) {
            return null
        }

        val isSubNodeActive = subStatus == "active" || subStatus == "success" || subStatus == "complimentary"

        val isVipFlag = isSubNodeActive ||
            (subObj == null && (
                parseBooleanField(obj.opt("isVip")) ||
                    parseBooleanField(obj.opt("vip")) ||
                    parseBooleanField(obj.opt("isVIP")) ||
                    parseBooleanField(obj.opt("isSubscribed")) ||
                    parseBooleanField(obj.opt("isPremium")) ||
                    obj.optString("role", "").equals("vip", ignoreCase = true) ||
                    obj.optString("status", "").equals("vip", ignoreCase = true)
                ))

        val rawPlan = if (subObj != null) {
            subObj.optString("plan").ifBlank { subObj.optString("planType") }.trim()
        } else {
            obj.optString("vipPlan")
                .ifBlank { obj.optString("vipType") }
                .ifBlank { obj.optString("subscriptionPlan") }
                .ifBlank { obj.optString("plan") }
                .trim()
        }

        val planType = resolveAdminPlanType(rawPlan, isVipFlag)
        if (planType == SubscriptionPlanType.NONE) {
            return null
        }

        val nowMs = System.currentTimeMillis()
        val subStartAt = subObj?.optLong("startAt", 0L)?.takeIf { it > 0L }
            ?: subObj?.optLong("grantedAt", 0L)?.takeIf { it > 0L }
            ?: subObj?.optLong("activatedAtMs", 0L)?.takeIf { it > 0L }
        val activatedAt = (
            subStartAt
                ?: obj.optLong("subscriptionActivatedAt", 0L).takeIf { it > 0L }
                ?: obj.optLong("vipActivatedAt", 0L).takeIf { it > 0L }
                ?: nowMs
            )

        val customDays = subObj?.optInt("days", 0)?.takeIf { it > 0 }
            ?: obj.optInt("vipDays", obj.optInt("subscriptionDays", 0))
        val subEndAt = subObj?.optLong("endAt", 0L)?.takeIf { it > 0L }
            ?: subObj?.optLong("expiresAtMs", 0L)?.takeIf { it > 0L }
        val rawExpiresAt = subEndAt
            ?: obj.optLong("vipExpiresAt", 0L).takeIf { it > 0L }
            ?: obj.optLong("subscriptionExpiresAt", 0L).takeIf { it > 0L }
            ?: 0L

        val defaultDurationMs = when {
            customDays > 0 -> customDays * 86_400_000L
            planType == SubscriptionPlanType.WEEKLY -> 7L * 86_400_000L
            planType == SubscriptionPlanType.MONTHLY -> 30L * 86_400_000L
            planType == SubscriptionPlanType.CRAZY_DEAL -> _subscriptionConfig.value.crazyDealDurationDays.coerceAtLeast(1) * 86_400_000L
            else -> 100L * 365L * 86_400_000L
        }

        val resolvedExpiresAt = when {
            planType == SubscriptionPlanType.FOREVER -> activatedAt + 100L * 365L * 86_400_000L
            rawExpiresAt > 0L -> rawExpiresAt
            else -> activatedAt + defaultDurationMs
        }
        val orderId = subObj?.optString("orderId").orEmpty()
            .ifBlank { obj.optString("subscriptionOrderId") }
            .ifBlank { "admin_vip_grant" }
        val utr = subObj?.optString("utr").orEmpty()
            .ifBlank { obj.optString("subscriptionUtr") }
            .ifBlank { "ADMIN_VIP" }

        val state = UserSubscriptionState(
            planType = planType,
            activatedAtMs = activatedAt,
            expiresAtMs = resolvedExpiresAt,
            orderId = orderId,
            utr = utr
        )
        return if (state.isActive(nowMs)) state else null
    }

    /**
     * Clears legacy top-level VIP flags in `/users/{userId}` and `/userAccounts/{normalizedKey}`
     * when Admin deactivates the user's plan (`subscription.status = "inactive"`).
     */
    fun clearDeactivatedSubscriptionFlagsInCloud(userId: String, userName: String) {
        val cleanId = userId.trim()
        if (cleanId.isEmpty() || isCurrentUserDeletedByAdmin) return
        scope.launch {
            try {
                val clearPatch = JSONObject().apply {
                    put("isVip", false)
                    put("vip", false)
                    put("isVIP", false)
                    put("isSubscribed", false)
                    put("isPremium", false)
                    put("role", "User")
                    put("vipPlan", "none")
                    put("subscriptionPlan", "none")
                    put("subscriptionPlanName", "Free")
                }
                val body = clearPatch.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                httpClient.newCall(
                    Request.Builder()
                        .url("$DATABASE_URL/users/$cleanId.json")
                        .patch(body)
                        .build()
                ).execute().close()

                val normKey = normalizeUsernameKey(userName)
                if (normKey.isNotBlank()) {
                    val accBody = clearPatch.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                    httpClient.newCall(
                        Request.Builder()
                            .url("$DATABASE_URL/userAccounts/$normKey.json")
                            .patch(accBody)
                            .build()
                    ).execute().close()
                }
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Syncs the user's played song history to `/songHistory/{userId}` so Admin Panel's
     * "Played songs" and "Analyse -> Top Played Songs" stay 100% synced.
     */
    fun syncSongHistoryToCloud(userId: String, historyArray: JSONArray) {
        val cleanId = userId.trim()
        if (cleanId.isEmpty() || isCurrentUserDeletedByAdmin) return
        scope.launch {
            try {
                val mapObj = JSONObject()
                for (i in 0 until historyArray.length()) {
                    val item = historyArray.optJSONObject(i) ?: continue
                    val key = "song_${i}_${item.optString("songId").replace(Regex("[^a-zA-Z0-9_-]"), "_")}"
                    mapObj.put(key, item)
                }
                val body = mapObj.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                httpClient.newCall(
                    Request.Builder()
                        .url("$DATABASE_URL/songHistory/$cleanId.json")
                        .put(body)
                        .build()
                ).execute().close()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Syncs the user's daily and hourly listening time usage to `/userTimeUsage/{userId}`
     * in the exact structure read by Admin.html (`getTodayUsage` and `renderUserTime`).
     */
    fun syncUserTimeUsageToCloud(
        userId: String,
        userName: String,
        utcDateKey: String,
        localDateKey: String,
        totalSeconds: Int,
        hourlySecondsMap: Map<Int, Int>
    ) {
        val cleanId = userId.trim()
        if (cleanId.isEmpty() || isCurrentUserDeletedByAdmin) return
        if (totalSeconds > 0) {
            hasUploadedTimeUsageNonZero = true
        }
        scope.launch {
            try {
                val now = System.currentTimeMillis()
                val hourlyObj = JSONObject()
                for ((hourKey, secs) in hourlySecondsMap.toSortedMap()) {
                    if (secs > 0) {
                        // Admin.html renders `mins = secs > 10000 ? Math.round(secs / 60) : Math.round(secs)` + " min"
                        val mins = ((secs + 59) / 60).coerceAtLeast(1)
                        hourlyObj.put(hourKey.toString(), mins)
                    }
                }
                if (hourlyObj.length() == 0 && totalSeconds > 0) {
                    val mins = ((totalSeconds + 59) / 60).coerceAtLeast(1)
                    hourlyObj.put("1", mins)
                }

                val dayEntry = JSONObject().apply {
                    put("totalSeconds", totalSeconds)
                    put("usedSeconds", totalSeconds)
                    put("listeningSeconds", totalSeconds)
                    put("totalMinutes", totalSeconds / 60)
                    put("hourly", hourlyObj)
                    put("updatedAt", now)
                }

                val dailyMap = JSONObject().apply {
                    put(utcDateKey, dayEntry)
                    if (localDateKey != utcDateKey) {
                        put(localDateKey, dayEntry)
                    }
                }

                val payload = JSONObject().apply {
                    put("userId", cleanId)
                    put("userName", userName)
                    put("totalSeconds", totalSeconds)
                    put("usedSeconds", totalSeconds)
                    put("listeningSeconds", totalSeconds)
                    put("hourly", hourlyObj)
                    put("daily", dailyMap)
                    put("updatedAt", now)
                }

                val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                httpClient.newCall(
                    Request.Builder()
                        .url("$DATABASE_URL/userTimeUsage/$cleanId.json")
                        .patch(body)
                        .build()
                ).execute().close()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Logs every payment attempt ("PENDING", "SUCCESS", "FAILED", "CANCELLED") to Firebase Realtime Database
     * (/paymentHistory/{orderId}, /payments/{orderId}, /subscriptions/{orderId}, and /users/{userId}/paymentHistory/{orderId})
     * so AK_Music_Admin_Final.html's Payment History page displays every transaction.
     */
    fun logPaymentAttemptToCloud(
        userId: String,
        userName: String,
        session: FamGatewayOrderSession,
        status: String,
        utr: String = ""
    ) {
        scope.launch {
            try {
                val now = System.currentTimeMillis()
                val dateFmt = java.text.SimpleDateFormat("dd MMM yyyy, hh:mm:ss a", Locale.US)
                val formattedDate = dateFmt.format(java.util.Date(now))
                val orderKey = session.orderId.ifBlank { "order_${userId}_$now" }

                val recordPayload = JSONObject().apply {
                    put("id", orderKey)
                    put("orderId", orderKey)
                    put("transactionId", utr.ifBlank { orderKey })
                    put("paymentId", orderKey)
                    put("userId", userId)
                    put("uid", userId)
                    put("userName", userName)
                    put("username", userName)
                    put("name", userName)
                    put("planType", session.planType.id)
                    put("plan", session.planType.id)
                    put("planName", session.planType.displayName)
                    put("currency", "INR")
                    put("amount", session.amountInr)
                    put("amountInr", session.amountInr)
                    put("price", session.amountInr)
                    put("status", status.uppercase(Locale.US))
                    put("paymentStatus", status.uppercase(Locale.US))
                    put("gateway", "AadarshPay")
                    put("paymentMethod", "AadarshPay UPI")
                    put("utr", utr)
                    put("createdAt", now)
                    put("updatedAt", now)
                    put("timestamp", now)
                    put("date", formattedDate)
                }

                for (node in listOf("paymentHistory/$orderKey", "payments/$orderKey", "subscriptions/$orderKey", "users/$userId/paymentHistory/$orderKey")) {
                    try {
                        val body = recordPayload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                        httpClient.newCall(
                            Request.Builder()
                                .url("$DATABASE_URL/$node.json")
                                .put(body)
                                .build()
                        ).execute().close()
                    } catch (_: Exception) {
                    }
                }

                val userPaymentPatch = JSONObject().apply {
                    put("lastPaymentOrderId", orderKey)
                    put("lastPaymentPlan", session.planType.id)
                    put("lastPaymentPlanName", session.planType.displayName)
                    put("lastPaymentAmount", session.amountInr)
                    put("lastPaymentStatus", status.uppercase(Locale.US))
                    put("lastPaymentUtr", utr)
                    put("lastPaymentGateway", "AadarshPay")
                    put("lastPaymentDate", formattedDate)
                    put("lastPaymentUpdatedAt", now)
                }
                val patchBody = userPaymentPatch.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                httpClient.newCall(
                    Request.Builder()
                        .url("$DATABASE_URL/users/$userId.json")
                        .patch(patchBody)
                        .build()
                ).execute().close()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to log payment attempt to cloud", e)
            }
        }
    }

    /**
     * Syncs a verified AadarshPay subscription purchase to Firebase Realtime Database
     * (/subscriptions/{orderId}, /paymentHistory/{orderId}, /payments/{orderId}, /users/{userId}, and /userAccounts/{normalizedUsername})
     * matching AK_Music_Admin_Final.html's exact schema (`user.subscription.status = "active"`, `user.subscription.plan = ...`).
     */
    fun syncSubscriptionToCloud(
        userId: String,
        userName: String,
        state: UserSubscriptionState,
        amountInr: Int
    ) {
        scope.launch {
            try {
                val now = System.currentTimeMillis()
                val dateFmt = java.text.SimpleDateFormat("dd MMM yyyy, hh:mm:ss a", Locale.US)
                val formattedDate = dateFmt.format(java.util.Date(now))
                val orderKey = state.orderId.ifBlank { "sub_${userId}_$now" }
                val paymentPayload = JSONObject().apply {
                    put("id", orderKey)
                    put("orderId", orderKey)
                    put("transactionId", state.utr.ifBlank { orderKey })
                    put("paymentId", orderKey)
                    put("userId", userId)
                    put("uid", userId)
                    put("userName", userName)
                    put("username", userName)
                    put("name", userName)
                    put("planType", state.planType.id)
                    put("plan", state.planType.id)
                    put("planName", state.planType.displayName)
                    put("currency", "INR")
                    put("amount", amountInr)
                    put("amountInr", amountInr)
                    put("price", amountInr)
                    put("activatedAtMs", state.activatedAtMs)
                    put("expiresAtMs", state.expiresAtMs)
                    put("utr", state.utr)
                    put("status", "SUCCESS")
                    put("paymentStatus", "SUCCESS")
                    put("gateway", "AadarshPay")
                    put("paymentMethod", "AadarshPay UPI")
                    put("date", formattedDate)
                    put("updatedAt", now)
                    put("timestamp", now)
                }
                for (node in listOf("subscriptions/$orderKey", "paymentHistory/$orderKey", "payments/$orderKey", "users/$userId/paymentHistory/$orderKey")) {
                    try {
                        val body = paymentPayload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                        httpClient.newCall(
                            Request.Builder()
                                .url("$DATABASE_URL/$node.json")
                                .put(body)
                                .build()
                        ).execute().close()
                    } catch (_: Exception) {
                    }
                }

                // Exact format read by AK_Music_Admin_Final.html (`user.subscription.status === 'active'`, `user.subscription.plan`)
                val adminCompatibleSubObj = JSONObject().apply {
                    put("plan", state.planType.id)
                    put("planType", state.planType.id)
                    put("planName", state.planType.displayName)
                    put("status", "active")
                    put("source", "AadarshPay")
                    put("startAt", state.activatedAtMs)
                    put("endAt", state.expiresAtMs)
                    put("activatedAtMs", state.activatedAtMs)
                    put("expiresAtMs", state.expiresAtMs)
                    put("orderId", orderKey)
                    put("utr", state.utr)
                }

                val userPatch = JSONObject().apply {
                    put("isVip", true)
                    put("vip", true)
                    put("isSubscribed", true)
                    put("role", "VIP")
                    put("vipPlan", state.planType.id)
                    put("subscriptionPlan", state.planType.id)
                    put("subscriptionPlanName", state.planType.displayName)
                    put("subscriptionActivatedAt", state.activatedAtMs)
                    put("subscriptionExpiresAt", state.expiresAtMs)
                    put("vipExpiresAt", state.expiresAtMs)
                    put("subscriptionOrderId", state.orderId)
                    put("subscriptionUtr", state.utr)
                    put("lastPaymentOrderId", orderKey)
                    put("lastPaymentPlan", state.planType.id)
                    put("lastPaymentPlanName", state.planType.displayName)
                    put("lastPaymentAmount", amountInr)
                    put("lastPaymentStatus", "SUCCESS")
                    put("lastPaymentUtr", state.utr)
                    put("lastPaymentGateway", "AadarshPay")
                    put("lastPaymentDate", formattedDate)
                    put("lastPaymentUpdatedAt", now)
                    put("subscription", adminCompatibleSubObj)
                }
                val patchBody = userPatch.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                httpClient.newCall(
                    Request.Builder()
                        .url("$DATABASE_URL/users/$userId.json")
                        .patch(patchBody)
                        .build()
                ).execute().close()

                val normalizedKey = normalizeUsernameKey(userName)
                if (normalizedKey.isNotBlank()) {
                    val accPatchBody = userPatch.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                    httpClient.newCall(
                        Request.Builder()
                            .url("$DATABASE_URL/userAccounts/$normalizedKey.json")
                            .patch(accPatchBody)
                            .build()
                    ).execute().close()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to sync subscription to cloud", e)
            }
        }
    }

    /**
     * Backs up the user's full custom Playlists, Spotify Playlists (with original Spotify coverUrl
     * and individual song covers), and Favorites to `/userPlaylistsBackup/{userId}` (and `/userPlaylistsBackupByName/{normKey}`)
     * so logging in never loses or deletes old playlists.
     */
    fun syncUserPlaylistsBackupToCloud(
        userId: String,
        userName: String,
        backupPayload: JSONObject
    ) {
        val cleanId = userId.trim()
        if (cleanId.isEmpty() || isCurrentUserDeletedByAdmin) return
        scope.launch {
            try {
                val bodyStr = backupPayload.toString()
                val body = bodyStr.toRequestBody("application/json; charset=utf-8".toMediaType())
                httpClient.newCall(
                    Request.Builder()
                        .url("$DATABASE_URL/userPlaylistsBackup/$cleanId.json")
                        .patch(body)
                        .build()
                ).execute().close()

                val normKey = normalizeUsernameKey(userName)
                if (normKey.isNotBlank()) {
                    val byNameBody = bodyStr.toRequestBody("application/json; charset=utf-8".toMediaType())
                    httpClient.newCall(
                        Request.Builder()
                            .url("$DATABASE_URL/userPlaylistsBackupByName/$normKey.json")
                            .patch(byNameBody)
                            .build()
                    ).execute().close()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to sync user playlists backup to cloud", e)
            }
        }
    }

    /**
     * Fetches the user's backed-up Playlists, Spotify Playlists, and Favorites from Firebase
     * so they can be merged/restored into local Room storage upon login.
     */
    suspend fun fetchUserPlaylistsBackupFromCloud(
        userId: String,
        userName: String
    ): JSONObject? = withContext(Dispatchers.IO) {
        val cleanId = userId.trim()
        val normKey = normalizeUsernameKey(userName)
        val candidateUrls = buildList {
            if (cleanId.isNotBlank()) {
                add("$DATABASE_URL/userPlaylistsBackup/$cleanId.json")
            }
            if (normKey.isNotBlank()) {
                add("$DATABASE_URL/userPlaylistsBackupByName/$normKey.json")
            }
            if (cleanId.isNotBlank()) {
                add("$DATABASE_URL/users/$cleanId.json")
            }
        }

        for (url in candidateUrls) {
            try {
                val req = Request.Builder().url(url).get().build()
                httpClient.newCall(req).execute().use { res ->
                    if (res.isSuccessful) {
                        val str = res.body?.string()?.trim().orEmpty()
                        if (str.isNotEmpty() && str != "null" && str.startsWith("{")) {
                            val obj = JSONObject(str)
                            val hasPlaylists = obj.optJSONArray("customPlaylists")?.length()?.let { it > 0 } == true ||
                                obj.optJSONArray("spotifyPlaylists")?.length()?.let { it > 0 } == true ||
                                obj.optJSONArray("playlists")?.length()?.let { it > 0 } == true ||
                                obj.optJSONArray("favorites")?.length()?.let { it > 0 } == true
                            if (hasPlaylists) {
                                return@withContext obj
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        null
    }
}
