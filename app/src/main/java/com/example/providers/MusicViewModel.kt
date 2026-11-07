package com.example.providers

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.room.Room
import com.example.models.AKMusicDatabase
import com.example.models.AdminSubscriptionConfig
import com.example.models.DownloadedSongEntity
import com.example.models.FavoriteSongEntity
import com.example.models.PlaylistEntity
import com.example.models.PlaylistSongEntity
import com.example.models.SessionHistoryEntity
import com.example.models.Song
import com.example.models.SubscriptionPlanType
import com.example.models.UserSubscriptionState
import com.example.services.AudioStreamExtractor
import com.example.services.BackstageYouTubePlayerController
import com.example.services.FamGatewayOrderSession
import com.example.services.FamGatewayService
import com.example.services.FirebaseSongsService
import com.example.services.MusicPlaybackService
import com.example.utils.CountryOption
import com.example.utils.SUPPORTED_COUNTRIES
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class MainTab {
    HOME,
    TOP_CHARTS,
    EXPLORE,
    LIBRARY
}

enum class ChartTab {
    LOCAL,
    GLOBAL
}

enum class RepeatModeState {
    OFF,
    ALL,
    ONE
}

sealed class LibrarySubScreen {
    data object Root : LibrarySubScreen()
    data object Favorites : LibrarySubScreen()
    data object MyMusic : LibrarySubScreen()
    data object Playlists : LibrarySubScreen()
    data class PlaylistDetail(val playlist: PlaylistEntity) : LibrarySubScreen()
    data object NowPlaying : LibrarySubScreen()
    data object LastSession : LibrarySubScreen()
    data class ArtistAlbumView(val artistName: String) : LibrarySubScreen()
}

class MusicViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MusicViewModel"
        val PLAYBACK_SPEEDS = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        const val FREE_DAILY_LIMIT_SECONDS = 3600 // 1 hour (3600 seconds) per day for Free users
        const val CRAZY_DEAL_WINDOW_MS = 3L * 24L * 60L * 60L * 1000L // 3 days (72 hours) for new users
    }

    private val appContext = application.applicationContext
    private val prefs = appContext.getSharedPreferences("ak_music_user_prefs", Context.MODE_PRIVATE)
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    // Real-time Internet Connectivity State (for No Internet Full-Screen Page)
    private val _isInternetAvailable = MutableStateFlow(checkNetworkConnected())
    val isInternetAvailable: StateFlow<Boolean> = _isInternetAvailable.asStateFlow()

    fun checkNetworkConnected(): Boolean {
        return try {
            val cm = connectivityManager ?: return true
            val activeNet = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(activeNet) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
        } catch (_: Exception) {
            true
        }
    }

    fun refreshNetworkState() {
        val connected = checkNetworkConnected()
        _isInternetAvailable.value = connected
        if (connected) {
            refreshFirebaseCatalog()
        }
    }

    // User Name & Password Auth State
    private val _userName = MutableStateFlow(prefs.getString("user_name", "")?.trim() ?: "")
    val userName: StateFlow<String> = _userName.asStateFlow()

    private val _userPassword = MutableStateFlow(prefs.getString("user_password", "")?.trim() ?: "")
    val userPassword: StateFlow<String> = _userPassword.asStateFlow()

    private val _isAuthenticating = MutableStateFlow(false)
    val isAuthenticating: StateFlow<Boolean> = _isAuthenticating.asStateFlow()

    private val _authErrorMessage = MutableStateFlow<String?>(null)
    val authErrorMessage: StateFlow<String?> = _authErrorMessage.asStateFlow()

    fun clearAuthError() {
        _authErrorMessage.value = null
    }

    private val _isEditNameDialogOpen = MutableStateFlow(false)
    val isEditNameDialogOpen: StateFlow<Boolean> = _isEditNameDialogOpen.asStateFlow()

    // Developer Screen Modal State
    private val _isDeveloperScreenOpen = MutableStateFlow(false)
    val isDeveloperScreenOpen: StateFlow<Boolean> = _isDeveloperScreenOpen.asStateFlow()

    fun setDeveloperScreenOpen(open: Boolean) {
        _isDeveloperScreenOpen.value = open
    }

    /**
     * Authenticates (Sign In) or registers (Create Account) a user with unique username + password
     * against Firebase Realtime Database.
     */
    fun authenticateWithNameAndPassword(
        name: String,
        password: String,
        isCreateAccount: Boolean,
        onAuthenticated: () -> Unit = {}
    ) {
        val cleanedName = name.trim()
        val cleanedPass = password.trim()
        if (cleanedName.isEmpty() || cleanedPass.isEmpty()) return
        if (_isAuthenticating.value) return

        viewModelScope.launch {
            _isAuthenticating.value = true
            _authErrorMessage.value = null

            val result = firebaseService.authenticateUserAccount(
                userName = cleanedName,
                password = cleanedPass,
                isCreateAccount = isCreateAccount
            )

            _isAuthenticating.value = false
            result.fold(
                onSuccess = { authRes ->
                    val now = System.currentTimeMillis()
                    prefs.edit()
                        .putString("user_name", authRes.userName)
                        .putString("user_password", authRes.password)
                        .putString("cloud_user_id", authRes.userId)
                        .putLong("user_created_at_ms", authRes.createdAtMs)
                        .putLong("user_last_login_ms", now)
                        .apply()

                    _userName.value = authRes.userName
                    _userPassword.value = authRes.password
                    _isEditNameDialogOpen.value = false

                    // Restore cloud subscription if active
                    if (authRes.subscriptionState != null && authRes.subscriptionState.isActive(now)) {
                        saveSubscriptionStateLocally(authRes.subscriptionState)
                    }

                    updateSubscriptionAndCrazyDealTimers()
                    firebaseService.resetAdminDeletionFlagForFreshLogin()
                    syncCurrentUserDataToAdminCloud(isExplicitLogin = true)

                    if (authRes.isNewAccount) {
                        emitSnackbar("Account created! Welcome to AADIFY, ${authRes.userName}! 🎉")
                    } else {
                        emitSnackbar("Welcome back, ${authRes.userName}! 🎵")
                    }
                    onAuthenticated()
                },
                onFailure = { err ->
                    _authErrorMessage.value = err.message ?: "Authentication failed. Please try again."
                }
            )
        }
    }

    fun saveUserName(name: String) {
        val pass = _userPassword.value.ifBlank { "aadify123" }
        authenticateWithNameAndPassword(name, pass, isCreateAccount = false)
    }

    private fun generateCloudUserId(userName: String): String {
        val slug = userName.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .take(18)
            .ifBlank { "user" }
        val shortHash = UUID.randomUUID().toString().replace("-", "").take(6).lowercase(Locale.US)
        return "user_${slug}_$shortHash"
    }

    private fun getOrCreateCloudUserId(currentName: String): String {
        val saved = prefs.getString("cloud_user_id", "")?.trim().orEmpty()
        if (saved.isNotBlank()) return saved
        val generated = generateCloudUserId(currentName)
        val now = System.currentTimeMillis()
        val editor = prefs.edit().putString("cloud_user_id", generated)
        if (prefs.getLong("user_created_at_ms", 0L) <= 0L) {
            editor.putLong("user_created_at_ms", now)
        }
        if (prefs.getLong("user_last_login_ms", 0L) <= 0L) {
            editor.putLong("user_last_login_ms", now)
        }
        editor.apply()
        return generated
    }

    fun setEditNameDialogOpen(open: Boolean) {
        _authErrorMessage.value = null
        _isEditNameDialogOpen.value = open
    }

    // Device Audio Storage Permission & Local Songs State
    private val _hasAudioStoragePermission = MutableStateFlow(checkAudioPermissionGranted())
    val hasAudioStoragePermission: StateFlow<Boolean> = _hasAudioStoragePermission.asStateFlow()

    private val _deviceLocalSongs = MutableStateFlow<List<Song>>(emptyList())
    val deviceLocalSongs: StateFlow<List<Song>> = _deviceLocalSongs.asStateFlow()

    fun checkAudioPermissionGranted(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED
    }

    fun onAudioStoragePermissionResult(granted: Boolean) {
        _hasAudioStoragePermission.value = granted || checkAudioPermissionGranted()
        if (_hasAudioStoragePermission.value) {
            loadDeviceLocalSongs()
        }
    }

    fun loadDeviceLocalSongs() {
        val granted = checkAudioPermissionGranted()
        _hasAudioStoragePermission.value = granted
        if (!granted) return

        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val list = mutableListOf<Song>()
                try {
                    val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                    } else {
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                    }

                    val projection = arrayOf(
                        MediaStore.Audio.Media._ID,
                        MediaStore.Audio.Media.TITLE,
                        MediaStore.Audio.Media.ARTIST,
                        MediaStore.Audio.Media.ALBUM_ID,
                        MediaStore.Audio.Media.DURATION,
                        MediaStore.Audio.Media.DATE_ADDED,
                        MediaStore.Audio.Media.IS_MUSIC
                    )

                    val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
                    val sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} DESC"

                    appContext.contentResolver.query(
                        collection,
                        projection,
                        selection,
                        null,
                        sortOrder
                    )?.use { cursor ->
                        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                        val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                        val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                        val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                        val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                        val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)

                        val albumArtBaseUri = Uri.parse("content://media/external/audio/albumart")

                        while (cursor.moveToNext()) {
                            val mediaId = cursor.getLong(idCol)
                            val rawTitle = cursor.getString(titleCol)?.trim().orEmpty()
                            val rawArtist = cursor.getString(artistCol)?.trim().orEmpty()
                            val albumId = cursor.getLong(albumIdCol)
                            val duration = cursor.getLong(durationCol)
                            val dateAdded = cursor.getLong(dateCol)

                            if (duration in 1..4999) continue

                            val contentUri = ContentUris.withAppendedId(
                                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                                mediaId
                            )
                            val albumArtUri = if (albumId > 0) {
                                ContentUris.withAppendedId(albumArtBaseUri, albumId).toString()
                            } else {
                                ""
                            }

                            val cleanArtist = if (rawArtist.isBlank() || rawArtist == "<unknown>") {
                                "Device Audio"
                            } else {
                                rawArtist
                            }

                            list.add(
                                Song(
                                    id = "local_media_$mediaId",
                                    title = rawTitle.ifBlank { "Audio Track #$mediaId" },
                                    artist = cleanArtist,
                                    videoId = "",
                                    bannerUrl = albumArtUri,
                                    timestamp = dateAdded * 1000L,
                                    allowDownload = false,
                                    localContentUri = contentUri.toString()
                                )
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error querying MediaStore audio files", e)
                }
                list
            }
            _deviceLocalSongs.value = loaded
        }
    }

    // Database (with non-destructive MIGRATION_1_2 so user's songs & playlists are preserved across updates!)
    private val db = Room.databaseBuilder(
        appContext,
        AKMusicDatabase::class.java,
        "ak_music_local.db"
    )
        .addMigrations(AKMusicDatabase.MIGRATION_1_2)
        .fallbackToDestructiveMigration(true)
        .build()
    private val libraryDao = db.musicLibraryDao()

    // Services
    private val firebaseService = FirebaseSongsService(appContext)
    private val audioExtractor = AudioStreamExtractor()
    private val famGatewayService = FamGatewayService()

    // Subscription & Daily Free Limit State (Admin-controllable globally and per-user)
    val subscriptionConfig: StateFlow<AdminSubscriptionConfig> = firebaseService.subscriptionConfig

    private val _perUserFreeLimitSeconds = MutableStateFlow<Int?>(
        prefs.getInt("admin_user_free_limit_seconds", -1).takeIf { it >= 60 }
    )

    val freeDailyLimitSeconds: StateFlow<Int> = combine(
        subscriptionConfig,
        _perUserFreeLimitSeconds
    ) { cfg, perUserSecs ->
        (perUserSecs ?: cfg.freeDailyLimitSeconds).coerceAtLeast(60)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, FREE_DAILY_LIMIT_SECONDS)

    private fun currentFreeDailyLimitSeconds(): Int {
        return (_perUserFreeLimitSeconds.value ?: subscriptionConfig.value.freeDailyLimitSeconds).coerceAtLeast(60)
    }

    private val _subscriptionState = MutableStateFlow(loadPersistedSubscriptionState())
    val subscriptionState: StateFlow<UserSubscriptionState> = _subscriptionState.asStateFlow()

    private val _activeSubscriptionRemainingSeconds = MutableStateFlow(0L)
    val activeSubscriptionRemainingSeconds: StateFlow<Long> = _activeSubscriptionRemainingSeconds.asStateFlow()

    private val _crazyDealRemainingSeconds = MutableStateFlow(0L)
    val crazyDealRemainingSeconds: StateFlow<Long> = _crazyDealRemainingSeconds.asStateFlow()

    private val _dailyListenedSeconds = MutableStateFlow(loadTodayListenedSeconds())
    val dailyListenedSeconds: StateFlow<Int> = _dailyListenedSeconds.asStateFlow()

    private val _isSubscriptionModalOpen = MutableStateFlow(false)
    val isSubscriptionModalOpen: StateFlow<Boolean> = _isSubscriptionModalOpen.asStateFlow()

    private val _isCrazyDealsPopupOpen = MutableStateFlow(false)
    val isCrazyDealsPopupOpen: StateFlow<Boolean> = _isCrazyDealsPopupOpen.asStateFlow()

    private val _isDailyLimitReachedModalOpen = MutableStateFlow(false)
    val isDailyLimitReachedModalOpen: StateFlow<Boolean> = _isDailyLimitReachedModalOpen.asStateFlow()

    // FamGateway Checkout Session State
    private val _isFamGatewayCheckoutOpen = MutableStateFlow(false)
    val isFamGatewayCheckoutOpen: StateFlow<Boolean> = _isFamGatewayCheckoutOpen.asStateFlow()

    private val _activeCheckoutOrder = MutableStateFlow<FamGatewayOrderSession?>(null)
    val activeCheckoutOrder: StateFlow<FamGatewayOrderSession?> = _activeCheckoutOrder.asStateFlow()

    private val _isCreatingPaymentOrder = MutableStateFlow(false)
    val isCreatingPaymentOrder: StateFlow<Boolean> = _isCreatingPaymentOrder.asStateFlow()

    private val _isUserClickedIHavePaid = MutableStateFlow(false)
    val isUserClickedIHavePaid: StateFlow<Boolean> = _isUserClickedIHavePaid.asStateFlow()

    private val _isPaymentVerifiedSuccess = MutableStateFlow(false)
    val isPaymentVerifiedSuccess: StateFlow<Boolean> = _isPaymentVerifiedSuccess.asStateFlow()

    private val _paymentStatusMessage = MutableStateFlow("Waiting for UPI payment...")
    val paymentStatusMessage: StateFlow<String> = _paymentStatusMessage.asStateFlow()

    private val _paymentErrorMessage = MutableStateFlow<String?>(null)
    val paymentErrorMessage: StateFlow<String?> = _paymentErrorMessage.asStateFlow()

    private var paymentPollingJob: Job? = null

    // Media3 Controller (Foreground MediaSessionService for Notification Bar + Lockscreen + Offline Playback)
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null
    private var isUsingExoPlayerFallback = false
    private var hasHandledEndForCurrentSong = false
    private var isCurrentSongLoadedInEngine = false
    private var lastPersistedPosBucket = -1L

    // Hidden Backstage YouTube Player Controller (Primary Engine for all Admin & Online Songs!)
    private val backstagePlayer: BackstageYouTubePlayerController = BackstageYouTubePlayerController(
        context = appContext,
        onProgressUpdate = { posMs ->
            if (!isUsingExoPlayerFallback && !hasHandledEndForCurrentSong) {
                _currentPositionMs.value = posMs
                val totalDur = _durationMs.value
                MusicPlaybackService.syncOnlineProgressAndDuration(posMs, totalDur)
                val bucket = posMs / 2000L
                if (bucket != lastPersistedPosBucket) {
                    lastPersistedPosBucket = bucket
                    prefs.edit().putLong("last_position_ms", posMs).apply()
                }
                if (totalDur >= 15_000L && posMs >= totalDur - 1_000L) {
                    hasHandledEndForCurrentSong = true
                    _isBuffering.value = false
                    _isPlaying.value = false
                    handleTrackEnded()
                }
            }
        },
        onDurationUpdate = { durMs ->
            if (!isUsingExoPlayerFallback && !hasHandledEndForCurrentSong && durMs > 0L) {
                if (_durationMs.value != durMs) {
                    _durationMs.value = durMs
                    prefs.edit().putLong("last_duration_ms", durMs).apply()
                }
                MusicPlaybackService.syncOnlineProgressAndDuration(_currentPositionMs.value, durMs)
            }
        },
        onStateChangeUpdate = { state ->
            if (!isUsingExoPlayerFallback) {
                when (state) {
                    BackstageYouTubePlayerController.STATE_PLAYING -> {
                        if (!hasHandledEndForCurrentSong) {
                            _isBuffering.value = false
                            _isPlaying.value = true
                            MusicPlaybackService.syncPlayState(true)
                        }
                    }
                    BackstageYouTubePlayerController.STATE_PAUSED -> {
                        _isBuffering.value = false
                        _isPlaying.value = false
                        MusicPlaybackService.syncPlayState(false)
                    }
                    BackstageYouTubePlayerController.STATE_BUFFERING -> {
                        if (!hasHandledEndForCurrentSong) {
                            _isBuffering.value = true
                        }
                    }
                    BackstageYouTubePlayerController.STATE_ENDED -> {
                        if (!hasHandledEndForCurrentSong) {
                            hasHandledEndForCurrentSong = true
                            _isBuffering.value = false
                            _isPlaying.value = false
                            handleTrackEnded()
                        }
                    }
                }
            }
        },
        onErrorUpdate = { _ ->
            val active = _currentSong.value ?: return@BackstageYouTubePlayerController
            if (!isUsingExoPlayerFallback && _currentPositionMs.value == 0L) {
                viewModelScope.launch {
                    if (!hasRetriedCurrentTrack) {
                        hasRetriedCurrentTrack = true
                        val altVideoId = audioExtractor.findPlayableVideoIdForSong(active)
                        if (!altVideoId.isNullOrBlank() && altVideoId != active.cleanVideoId) {
                            backstagePlayer.loadVideoById(
                                rawVideoId = altVideoId,
                                startSeconds = 0.0,
                                songTitle = active.title,
                                songArtist = active.artist,
                                artworkUrl = active.highResBannerUrl
                            )
                            return@launch
                        }
                    }
                    _isBuffering.value = false
                    _isPlaying.value = false
                    emitSnackbar("Unable to play this track right now.")
                }
            }
        },
        onMediaButtonAction = { action ->
            when (action) {
                "play" -> if (!_isPlaying.value) togglePlayPause()
                "pause" -> if (_isPlaying.value) togglePlayPause()
                "next" -> skipToNext()
                "prev" -> skipToPrevious()
            }
        },
        onUnexpectedPauseDetected = {
            MusicPlaybackService.notifyUnexpectedOnlinePause()
        }
    )

    fun attachBackstagePlayer(container: ViewGroup) {
        backstagePlayer.attachToContainer(container)
    }

    fun keepEngineAwakeInBackground() {
        if (_currentSong.value != null && !isUsingExoPlayerFallback) {
            backstagePlayer.keepAwake()
            if (_isPlaying.value) {
                MusicPlaybackService.syncPlayState(true)
            }
        }
    }

    // Navigation & Modals
    private val _selectedTab = MutableStateFlow(MainTab.HOME)
    val selectedTab: StateFlow<MainTab> = _selectedTab.asStateFlow()

    private val _librarySubScreen = MutableStateFlow<LibrarySubScreen>(LibrarySubScreen.Root)
    val librarySubScreen: StateFlow<LibrarySubScreen> = _librarySubScreen.asStateFlow()

    private val _isFullScreenPlayerOpen = MutableStateFlow(false)
    val isFullScreenPlayerOpen: StateFlow<Boolean> = _isFullScreenPlayerOpen.asStateFlow()

    private val _optionsSheetSong = MutableStateFlow<Song?>(null)
    val optionsSheetSong: StateFlow<Song?> = _optionsSheetSong.asStateFlow()

    private val _isAdminUploadSheetOpen = MutableStateFlow(false)
    val isAdminUploadSheetOpen: StateFlow<Boolean> = _isAdminUploadSheetOpen.asStateFlow()

    private val _playlistPickerSong = MutableStateFlow<Song?>(null)
    val playlistPickerSong: StateFlow<Song?> = _playlistPickerSong.asStateFlow()

    private val _isSleepTimerDialogOpen = MutableStateFlow(false)
    val isSleepTimerDialogOpen: StateFlow<Boolean> = _isSleepTimerDialogOpen.asStateFlow()

    private val _isUpNextSheetOpen = MutableStateFlow(false)
    val isUpNextSheetOpen: StateFlow<Boolean> = _isUpNextSheetOpen.asStateFlow()

    private val _isLyricsOverlayOpen = MutableStateFlow(false)
    val isLyricsOverlayOpen: StateFlow<Boolean> = _isLyricsOverlayOpen.asStateFlow()

    // Separate Search States:
    // 1) Home Search Query -> Filters played songs from Last Session on Home
    // 2) Explore Search Query -> Fast Online Search Engine with Country Patching on Explore
    private val _homeSearchQuery = MutableStateFlow("")
    val homeSearchQuery: StateFlow<String> = _homeSearchQuery.asStateFlow()

    private val _exploreSearchQuery = MutableStateFlow("")
    val exploreSearchQuery: StateFlow<String> = _exploreSearchQuery.asStateFlow()

    // Keep backward-compatible alias if referenced anywhere
    val searchQuery: StateFlow<String> = _homeSearchQuery.asStateFlow()

    private val _onlineSearchResults = MutableStateFlow<List<Song>>(emptyList())
    private val _countryTrendingSongs = MutableStateFlow<List<Song>>(emptyList())
    private val _isSearchingOnline = MutableStateFlow(false)
    val isSearchingOnline: StateFlow<Boolean> = _isSearchingOnline.asStateFlow()

    private var searchJob: Job? = null
    private var countryFetchJob: Job? = null

    private val _selectedChartTab = MutableStateFlow(ChartTab.LOCAL)
    val selectedChartTab: StateFlow<ChartTab> = _selectedChartTab.asStateFlow()

    private val _selectedCountry = MutableStateFlow(SUPPORTED_COUNTRIES.first())
    val selectedCountry: StateFlow<CountryOption> = _selectedCountry.asStateFlow()

    // Catalog Songs (Admin Firebase Catalog + Curated Default Catalog)
    val allSongs: StateFlow<List<Song>> = firebaseService.songs
    val isLoadingSongs: StateFlow<Boolean> = firebaseService.isLoading
    val firebaseError: StateFlow<String?> = firebaseService.connectionError

    // Explore Songs StateFlow (Combines Catalog + Country Trending + Live Online Search Results)
    val exploreSongs: StateFlow<List<Song>> = combine(
        firebaseService.songs,
        _exploreSearchQuery,
        _onlineSearchResults,
        _countryTrendingSongs,
        _selectedCountry
    ) { catalogSongs, query, onlineMatches, countrySongs, country ->
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            if (countrySongs.isNotEmpty()) {
                val seenIds = countrySongs.map { it.cleanVideoId.lowercase() }.filter { it.isNotBlank() }.toSet()
                val extraCatalog = catalogSongs.filter { it.cleanVideoId.lowercase() !in seenIds }
                countrySongs + extraCatalog
            } else {
                catalogSongs
            }
        } else {
            val tokens = trimmed.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
            val localPool = (catalogSongs + countrySongs).distinctBy { it.cleanVideoId.ifBlank { it.id } }
            val localMatches = localPool.filter { song ->
                val haystack = "${song.title} ${song.artist} ${song.videoId}".lowercase()
                tokens.all { token -> haystack.contains(token) } ||
                        song.title.contains(trimmed, ignoreCase = true) ||
                        song.artist.contains(trimmed, ignoreCase = true)
            }
            val existingVideoIds = localMatches.map { it.cleanVideoId.lowercase() }.toSet()
            val extraOnline = onlineMatches.filter { it.cleanVideoId.lowercase() !in existingVideoIds }
            localMatches + extraOnline
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val filteredSongs: StateFlow<List<Song>> = firebaseService.songs

    // Local Room Flows
    val favoriteEntities: StateFlow<List<FavoriteSongEntity>> = libraryDao.getAllFavorites()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val sessionHistoryEntities: StateFlow<List<SessionHistoryEntity>> = libraryDao.getRecentSessionHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val playlists: StateFlow<List<PlaylistEntity>> = libraryDao.getAllPlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allPlaylistSongs: StateFlow<List<PlaylistSongEntity>> = libraryDao.getAllPlaylistSongs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val downloadedSongs: StateFlow<List<DownloadedSongEntity>> = libraryDao.getAllDownloadedSongs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Charts Computed List (Strict Admin Ranking Order: 1, 2, 3...)
    val chartSongs: StateFlow<List<Song>> = combine(
        firebaseService.songs,
        _selectedChartTab
    ) { songs, _ ->
        songs.sortedWith(
            compareBy<Song> { if (it.rank > 0) it.rank else Int.MAX_VALUE }
                .thenByDescending { it.timestamp }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Playback State
    private val _currentSong = MutableStateFlow<Song?>(null)
    val currentSong: StateFlow<Song?> = _currentSong.asStateFlow()

    private val _queue = MutableStateFlow<List<Song>>(emptyList())
    val queue: StateFlow<List<Song>> = _queue.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    private val _currentPositionMs = MutableStateFlow(0L)
    val currentPositionMs: StateFlow<Long> = _currentPositionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _playbackSpeed = MutableStateFlow(1.0f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    private val _isShuffleEnabled = MutableStateFlow(false)
    val isShuffleEnabled: StateFlow<Boolean> = _isShuffleEnabled.asStateFlow()

    private val _repeatMode = MutableStateFlow(RepeatModeState.OFF)
    val repeatMode: StateFlow<RepeatModeState> = _repeatMode.asStateFlow()

    private val _activeStreamEngine = MutableStateFlow("Backstage YouTube Engine")
    val activeStreamEngine: StateFlow<String> = _activeStreamEngine.asStateFlow()

    // Sleep Timer
    private val _sleepTimerRemainingSeconds = MutableStateFlow<Long?>(null)
    val sleepTimerRemainingSeconds: StateFlow<Long?> = _sleepTimerRemainingSeconds.asStateFlow()
    private var sleepTimerJob: Job? = null

    // Downloading state
    private val _downloadingSongIds = MutableStateFlow<Set<String>>(emptySet())
    val downloadingSongIds: StateFlow<Set<String>> = _downloadingSongIds.asStateFlow()

    // Snackbar messages
    private val _snackbarMessage = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val snackbarMessage = _snackbarMessage.asSharedFlow()

    private var playbackJob: Job? = null
    private var hasRetriedCurrentTrack = false

    init {
        MusicPlaybackService.onSkipNextCallback = {
            viewModelScope.launch { skipToNext() }
        }
        MusicPlaybackService.onSkipPreviousCallback = {
            viewModelScope.launch { skipToPrevious() }
        }
        MusicPlaybackService.onTogglePlayPauseCallback = { shouldPlay ->
            val active = _currentSong.value
            if (active != null) {
                if (shouldPlay) {
                    refreshDailyLimitBucketIfNeeded()
                    if (!_subscriptionState.value.isActive() && _dailyListenedSeconds.value >= currentFreeDailyLimitSeconds()) {
                        viewModelScope.launch { pausePlaybackForDailyLimit() }
                    } else if (!isCurrentSongLoadedInEngine || hasHandledEndForCurrentSong) {
                        viewModelScope.launch {
                            playSong(active, _queue.value, startPositionMs = getValidResumePositionMs())
                        }
                    } else if (isUsingExoPlayerFallback) {
                        _isPlaying.value = true
                        _isBuffering.value = false
                        MusicPlaybackService.syncPlayState(true)
                    } else {
                        backstagePlayer.playVideo()
                        _isPlaying.value = true
                        _isBuffering.value = false
                        MusicPlaybackService.syncPlayState(true)
                    }
                } else {
                    prefs.edit().putLong("last_position_ms", _currentPositionMs.value).apply()
                    if (!isUsingExoPlayerFallback) {
                        backstagePlayer.pauseVideo()
                    }
                    _isPlaying.value = false
                    _isBuffering.value = false
                    MusicPlaybackService.syncPlayState(false)
                }
            }
        }
        MusicPlaybackService.onSeekToCallback = { targetPosMs ->
            viewModelScope.launch {
                seekToPosition(targetPosMs)
            }
        }
        MusicPlaybackService.onExoPlayerTrackEndedCallback = {
            viewModelScope.launch {
                if (isUsingExoPlayerFallback && !hasHandledEndForCurrentSong) {
                    hasHandledEndForCurrentSong = true
                    _isBuffering.value = false
                    _isPlaying.value = false
                    handleTrackEnded()
                }
            }
        }
        MusicPlaybackService.onExoPlayerStreamErrorCallback = {
            viewModelScope.launch {
                val active = _currentSong.value
                if (active != null && isUsingExoPlayerFallback) {
                    val vid = active.cleanVideoId
                    val resumePosMs = _currentPositionMs.value.coerceAtLeast(0L)
                    if (vid.isNotBlank()) {
                        isUsingExoPlayerFallback = false
                        _activeStreamEngine.value = "Backstage YouTube Engine"
                        MusicPlaybackService.syncNowPlaying(
                            context = appContext,
                            song = active,
                            isPlaying = true,
                            directStreamUri = null,
                            playbackSpeed = _playbackSpeed.value,
                            startPositionMs = resumePosMs
                        )
                        backstagePlayer.loadVideoById(
                            rawVideoId = vid,
                            startSeconds = resumePosMs / 1000.0,
                            songTitle = active.title,
                            songArtist = active.artist,
                            artworkUrl = active.highResBannerUrl
                        )
                    }
                }
            }
        }
        MusicPlaybackService.onKeepOnlineEngineAwakeCallback = {
            if (_currentSong.value != null && !isUsingExoPlayerFallback && !hasHandledEndForCurrentSong) {
                backstagePlayer.keepAwake()
            }
        }
        MusicPlaybackService.onExternalMediaPauseCallback = {
            viewModelScope.launch {
                prefs.edit().putLong("last_position_ms", _currentPositionMs.value).apply()
                if (!isUsingExoPlayerFallback) {
                    backstagePlayer.pauseForExternalAudio()
                }
                _isBuffering.value = false
                _isPlaying.value = false
            }
        }
        MusicPlaybackService.onExternalMediaResumeCallback = {
            viewModelScope.launch {
                val active = _currentSong.value ?: return@launch
                if (hasHandledEndForCurrentSong) return@launch
                if (isUsingExoPlayerFallback) {
                    _isBuffering.value = false
                    _isPlaying.value = true
                    MusicPlaybackService.syncPlayState(true)
                } else if (!backstagePlayer.isUserPaused) {
                    backstagePlayer.resumeAfterExternalAudio()
                    _isBuffering.value = false
                    _isPlaying.value = true
                    MusicPlaybackService.syncPlayState(true)
                }
            }
        }

        // Create hidden backstage WebView on main thread
        backstagePlayer.getOrCreateWebView()
        initializeMediaController()
        restorePersistedPlaybackState()
        startProgressPoller()
        startSubscriptionAndDailyLimitMonitor()
        registerNetworkCallbackMonitor()
        if (checkAudioPermissionGranted()) {
            loadDeviceLocalSongs()
        }
        fetchCountryTrendingSongs(_selectedCountry.value)
        setupCloudUserSyncAndAdminControl()
    }

    private var cloudUserSyncJob: Job? = null

    private fun setupCloudUserSyncAndAdminControl() {
        firebaseService.onUserDeletedByAdminCallback = {
            viewModelScope.launch {
                handleCurrentUserDeletedByAdmin()
            }
        }

        firebaseService.onAdminVipStateSyncedCallback = { cloudVipState ->
            viewModelScope.launch {
                if (cloudVipState != null && cloudVipState.isActive()) {
                    val current = _subscriptionState.value
                    val isNewOrChanged = !current.isActive() ||
                        current.planType != cloudVipState.planType ||
                        kotlin.math.abs(current.expiresAtMs - cloudVipState.expiresAtMs) > 60_000L
                    if (isNewOrChanged) {
                        saveSubscriptionStateLocally(cloudVipState)
                        _isDailyLimitReachedModalOpen.value = false
                        emitSnackbar("👑 VIP Activated (${cloudVipState.planType.displayName})!")
                    }
                }
            }
        }

        firebaseService.onAdminFreeLimitSyncedCallback = { customUserLimitSeconds ->
            viewModelScope.launch {
                if (customUserLimitSeconds != null && customUserLimitSeconds >= 60) {
                    if (_perUserFreeLimitSeconds.value != customUserLimitSeconds) {
                        _perUserFreeLimitSeconds.value = customUserLimitSeconds
                        prefs.edit().putInt("admin_user_free_limit_seconds", customUserLimitSeconds).apply()
                        if (_dailyListenedSeconds.value < customUserLimitSeconds && _isDailyLimitReachedModalOpen.value) {
                            _isDailyLimitReachedModalOpen.value = false
                        }
                    }
                }
            }
        }

        // Initial cloud sync if user is already logged in with their name:
        // Pass isExplicitLogin = false so we FIRST check if Admin deleted this user while the app was closed!
        if (_userName.value.isNotBlank()) {
            syncCurrentUserDataToAdminCloud(isExplicitLogin = false)
        }

        // Automatically sync user's live data (favorites, playlists, downloads, session history, current song) to Admin Panel
        viewModelScope.launch {
            combine(
                _userName,
                favoriteEntities,
                playlists,
                downloadedSongs,
                sessionHistoryEntities
            ) { name, favs, lists, downloads, history ->
                "${name}|${favs.size}|${lists.size}|${downloads.size}|${history.size}|${history.sumOf { it.playCount }}"
            }.collect {
                if (_userName.value.isNotBlank()) {
                    scheduleDebouncedUserCloudSync()
                }
            }
        }
    }

    private fun scheduleDebouncedUserCloudSync() {
        cloudUserSyncJob?.cancel()
        cloudUserSyncJob = viewModelScope.launch {
            delay(800L)
            syncCurrentUserDataToAdminCloud(isExplicitLogin = false)
        }
    }

    private fun handleCurrentUserDeletedByAdmin() {
        cloudUserSyncJob?.cancel()
        prefs.edit()
            .remove("user_name")
            .remove("user_password")
            .remove("cloud_user_id")
            .remove("user_created_at_ms")
            .remove("user_last_login_ms")
            .remove("sub_plan_id")
            .remove("sub_activated_at_ms")
            .remove("sub_expires_at_ms")
            .remove("sub_order_id")
            .remove("sub_utr")
            .remove("admin_user_free_limit_seconds")
            .apply()

        try {
            backstagePlayer.stopAndClear()
            MusicPlaybackService.syncPlayState(false)
        } catch (_: Exception) {
        }
        _perUserFreeLimitSeconds.value = null
        _subscriptionState.value = UserSubscriptionState()
        _activeSubscriptionRemainingSeconds.value = 0L
        _isPlaying.value = false
        _isBuffering.value = false
        _isFullScreenPlayerOpen.value = false
        _isEditNameDialogOpen.value = false
        _isSubscriptionModalOpen.value = false
        _isCrazyDealsPopupOpen.value = false
        _userName.value = ""
        _userPassword.value = ""
        emitSnackbar("Your account was permanently deleted by Admin.")
    }

    private fun syncCurrentUserDataToAdminCloud(isExplicitLogin: Boolean = false) {
        val currentName = _userName.value.trim()
        if (currentName.isEmpty()) return

        val userId = getOrCreateCloudUserId(currentName)
        val nowMs = System.currentTimeMillis()
        val createdAtMs = prefs.getLong("user_created_at_ms", nowMs).let { if (it > 0L) it else nowMs }
        val lastLoginMs = prefs.getLong("user_last_login_ms", nowMs).let { if (it > 0L) it else nowMs }
        val dateFormat = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.US)
        val formattedCreated = dateFormat.format(Date(createdAtMs))
        val formattedLogin = dateFormat.format(Date(lastLoginMs))
        val formattedNow = dateFormat.format(Date(nowMs))

        val favList = favoriteEntities.value
        val playlistList = playlists.value
        val playlistSongsList = allPlaylistSongs.value
        val downloadList = downloadedSongs.value
        val historyList = sessionHistoryEntities.value
        val activeSong = _currentSong.value
        val playingNow = _isPlaying.value
        val country = _selectedCountry.value

        val slug = currentName.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), ".")
            .trim('.')
            .ifBlank { "user" }

        val favJsonArray = JSONArray()
        favList.take(50).forEach { fav ->
            favJsonArray.put(
                JSONObject().apply {
                    put("id", fav.id)
                    put("title", fav.title)
                    put("artist", fav.artist)
                    put("videoId", fav.videoId)
                }
            )
        }

        val playlistsJsonArray = JSONArray()
        playlistList.take(30).forEach { pl ->
            val songsInPl = playlistSongsList.filter { it.playlistId == pl.playlistId }
            val songTitles = songsInPl.joinToString(", ") { it.title }
            playlistsJsonArray.put(
                JSONObject().apply {
                    put("playlistId", pl.playlistId)
                    put("name", pl.name)
                    put("songCount", songsInPl.size)
                    put("songs", songTitles)
                }
            )
        }

        val downloadsJsonArray = JSONArray()
        downloadList.take(50).forEach { dl ->
            downloadsJsonArray.put(
                JSONObject().apply {
                    put("id", dl.id)
                    put("title", dl.title)
                    put("artist", dl.artist)
                }
            )
        }

        val historyJsonArray = JSONArray()
        historyList.take(30).forEach { h ->
            historyJsonArray.put(
                JSONObject().apply {
                    put("id", h.id)
                    put("title", h.title)
                    put("artist", h.artist)
                    put("playCount", h.playCount)
                    put("playedAt", h.playedAt)
                }
            )
        }

        val totalPlays = historyList.sumOf { it.playCount }.coerceAtLeast(historyList.size)
        val deviceLabel = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}".trim()
        val nowPlayingSummary = activeSong?.let { "${it.title} - ${it.artist}" }
            ?: historyList.firstOrNull()?.let { "${it.title} - ${it.artist}" }
            ?: "No song played yet"

        val currentPassword = _userPassword.value.trim().ifBlank {
            prefs.getString("user_password", "")?.trim().orEmpty()
        }
        val activeSub = _subscriptionState.value
        val isSubActive = activeSub.isActive(nowMs)

        // AK_Music_Admin_Final.html renders `user.email || user.phone || user.mobile` directly under the user's name!
        // Put the user's password + device + live stats here so Admin can see every user's password & details at a glance.
        val adminDetailLine = buildString {
            append("🔑 Pass: ${currentPassword.ifBlank { "—" }}")
            append(" • 📱 $deviceLabel")
            if (activeSong != null) {
                append(" • 🎵 ${activeSong.title}")
            }
            append(" • Plays: $totalPlays | Favs: ${favList.size} | Lists: ${playlistList.size}")
        }

        val payload = JSONObject().apply {
            // Identity keys (covers all Admin Panel User table conventions)
            put("id", userId)
            put("uid", userId)
            put("userId", userId)
            put("name", currentName)
            put("userName", currentName)
            put("username", currentName)
            put("displayName", currentName)
            put("fullName", currentName)
            put("password", currentPassword)
            put("userPassword", currentPassword)
            put("email", adminDetailLine)
            put("phone", "Pass: ${currentPassword.ifBlank { "—" }}")

            // Status & Device Metadata
            put("status", if (isSubActive) "VIP" else "Active")
            put("isOnline", true)
            put("active", true)
            if (isSubActive) {
                put("role", "VIP")
            }
            put("loginType", "Name Login")
            put("appName", "AADIFY")
            put("platform", "Android")
            put("device", deviceLabel)
            put("deviceModel", Build.MODEL ?: "Android")
            put("androidVersion", "Android ${Build.VERSION.RELEASE}")
            put("country", "${country.flagEmoji} ${country.name}")

            // Timestamps
            put("createdAt", createdAtMs)
            put("joinedAt", createdAtMs)
            put("joinDate", formattedCreated)
            put("loginTime", formattedLogin)
            put("lastLogin", lastLoginMs)
            put("lastActive", nowMs)
            put("lastSeen", formattedNow)
            put("updatedAt", nowMs)
            put("timestamp", nowMs)

            // User Playback & Library Data ("uska Sara data")
            put("isPlaying", playingNow)
            put("currentSong", nowPlayingSummary)
            put("nowPlaying", activeSong?.title ?: "Idle")
            put("currentSongTitle", activeSong?.title ?: "")
            put("currentSongArtist", activeSong?.artist ?: "")
            put("currentSongBanner", activeSong?.highResBannerUrl ?: "")
            put("lastPlayedSong", historyList.firstOrNull()?.let { "${it.title} (${it.artist})" } ?: "None")
            put("totalPlays", totalPlays)
            put("favoritesCount", favList.size)
            put("playlistsCount", playlistList.size)
            put("downloadsCount", downloadList.size)
            put("historyCount", historyList.size)
            put("favoriteSongs", favList.joinToString(", ") { it.title }.ifBlank { "None" })
            put("playlistNames", playlistList.joinToString(", ") { it.name }.ifBlank { "None" })
            put("downloadedSongs", downloadList.joinToString(", ") { it.title }.ifBlank { "None" })
            put("recentSongsSummary", historyList.take(10).joinToString(", ") { "${it.title} (${it.playCount}x)" }.ifBlank { "None" })
            put("favorites", favJsonArray)
            put("playlists", playlistsJsonArray)
            put("downloads", downloadsJsonArray)
            put("recentSongs", historyJsonArray)

            // Subscription & Daily Free Usage Metadata for Admin Panel
            if (isSubActive) {
                put("isSubscribed", true)
                put("isVip", true)
                put("vip", true)
                put("isPremium", true)
                put("subscriptionPlan", activeSub.planType.id)
                put("vipPlan", activeSub.planType.id)
                put("subscriptionPlanName", activeSub.planType.displayName)
                put("subscriptionExpiresAt", activeSub.expiresAtMs)
                put("vipExpiresAt", activeSub.expiresAtMs)
                put(
                    "subscription",
                    JSONObject().apply {
                        put("plan", activeSub.planType.id)
                        put("planType", activeSub.planType.id)
                        put("planName", activeSub.planType.displayName)
                        put("status", "active")
                        put("startAt", activeSub.activatedAtMs)
                        put("endAt", activeSub.expiresAtMs)
                    }
                )
            }
            put("dailyListenedSeconds", _dailyListenedSeconds.value)
        }

        firebaseService.syncUserProfileToCloud(
            userId = userId,
            userPayload = payload,
            isExplicitLogin = isExplicitLogin
        )
    }

    /**
     * Restores the last selected/playing song and queue when the app is reopened so the
     * bottom mini-player never resets or loses the user's selected song!
     */
    private fun restorePersistedPlaybackState() {
        try {
            val activeFromService = MusicPlaybackService.getCurrentSyncedSong()
            val savedQueueJson = prefs.getString("last_queue_json", null)
            val restoredQueue = mutableListOf<Song>()
            if (!savedQueueJson.isNullOrBlank()) {
                val arr = JSONArray(savedQueueJson)
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val s = Song.fromJsonObject(obj)
                    if (s.id.isNotBlank() && s.title.isNotBlank()) {
                        restoredQueue.add(s)
                    }
                }
            }

            if (activeFromService != null) {
                _currentSong.value = activeFromService
                _queue.value = restoredQueue.ifEmpty { listOf(activeFromService) }
                _isPlaying.value = MusicPlaybackService.isCurrentlyPlaying()
                isCurrentSongLoadedInEngine = true
                return
            }

            val savedSongJson = prefs.getString("last_song_json", null)
            if (!savedSongJson.isNullOrBlank()) {
                val restoredSong = Song.fromJsonObject(JSONObject(savedSongJson))
                if (restoredSong.id.isNotBlank() && restoredSong.title.isNotBlank()) {
                    _currentSong.value = restoredSong
                    _queue.value = if (restoredQueue.isNotEmpty()) {
                        restoredQueue
                    } else {
                        listOf(restoredSong)
                    }
                    _currentPositionMs.value = prefs.getLong("last_position_ms", 0L).coerceAtLeast(0L)
                    _durationMs.value = prefs.getLong("last_duration_ms", 0L).coerceAtLeast(0L)
                    _isPlaying.value = false
                    _isBuffering.value = false
                    isCurrentSongLoadedInEngine = false
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to restore last song state", e)
        }
    }

    private fun getValidResumePositionMs(): Long {
        val pos = _currentPositionMs.value.coerceAtLeast(prefs.getLong("last_position_ms", 0L)).coerceAtLeast(0L)
        val dur = _durationMs.value.coerceAtLeast(prefs.getLong("last_duration_ms", 0L))
        return if (dur > 5000L && pos >= dur - 2500L) 0L else pos
    }

    private fun persistPlaybackSelection(song: Song, currentQueue: List<Song>, initialPositionMs: Long = 0L) {
        try {
            val queueArr = JSONArray()
            currentQueue.take(50).forEach { item ->
                queueArr.put(item.toJsonObject())
            }
            prefs.edit()
                .putString("last_song_json", song.toJsonObject().toString())
                .putString("last_queue_json", queueArr.toString())
                .putLong("last_position_ms", initialPositionMs.coerceAtLeast(0L))
                .apply()
        } catch (_: Exception) {
        }
    }

    private fun initializeMediaController() {
        try {
            val sessionToken = SessionToken(
                appContext,
                ComponentName(appContext, MusicPlaybackService::class.java)
            )
            val future = MediaController.Builder(appContext, sessionToken).buildAsync()
            controllerFuture = future
            future.addListener(
                {
                    try {
                        mediaController = future.get()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to connect MediaController", e)
                    }
                },
                ContextCompat.getMainExecutor(appContext)
            )
        } catch (e: Exception) {
            Log.e(TAG, "MediaController init exception", e)
        }
    }

    private fun startProgressPoller() {
        viewModelScope.launch {
            var backgroundKeepAliveTick = 0
            while (isActive) {
                if (isUsingExoPlayerFallback && isCurrentSongLoadedInEngine) {
                    val isPlayingNow = MusicPlaybackService.isExoPlaying()
                    val isBufferingNow = MusicPlaybackService.isExoBuffering()
                    _isBuffering.value = isBufferingNow
                    if (isPlayingNow) {
                        _isPlaying.value = true
                        val pos = MusicPlaybackService.getCurrentExoPositionMs()
                        val total = MusicPlaybackService.getCurrentExoDurationMs()
                        _currentPositionMs.value = pos
                        val bucket = pos / 5000L
                        if (bucket != lastPersistedPosBucket) {
                            lastPersistedPosBucket = bucket
                            prefs.edit().putLong("last_position_ms", pos).apply()
                        }
                        if (total > 0L) {
                            if (_durationMs.value != total) {
                                _durationMs.value = total
                                prefs.edit().putLong("last_duration_ms", total).apply()
                            }
                            if (!hasHandledEndForCurrentSong && total > 4000L && pos >= total - 500L) {
                                hasHandledEndForCurrentSong = true
                                handleTrackEnded()
                            }
                        }
                    }
                } else if (!isUsingExoPlayerFallback && isCurrentSongLoadedInEngine && _isPlaying.value && !hasHandledEndForCurrentSong) {
                    backgroundKeepAliveTick++
                    if (backgroundKeepAliveTick >= 8) {
                        backgroundKeepAliveTick = 0
                        backstagePlayer.keepAwake()
                    }
                }
                delay(250L)
            }
        }
    }

    fun selectTab(tab: MainTab) {
        _selectedTab.value = tab
        if (tab != MainTab.LIBRARY) {
            _librarySubScreen.value = LibrarySubScreen.Root
        }
    }

    fun openLibrarySubScreen(subScreen: LibrarySubScreen) {
        if ((subScreen is LibrarySubScreen.Playlists ||
                subScreen is LibrarySubScreen.PlaylistDetail ||
                subScreen is LibrarySubScreen.Favorites) &&
            !_subscriptionState.value.isActive()
        ) {
            emitSnackbar("👑 VIP Subscription required! Buy a subscription to unlock Playlists & Favorites.")
            _isSubscriptionModalOpen.value = true
            return
        }
        _selectedTab.value = MainTab.LIBRARY
        _librarySubScreen.value = subScreen
        if (subScreen is LibrarySubScreen.MyMusic && checkAudioPermissionGranted()) {
            loadDeviceLocalSongs()
        }
    }

    fun updateHomeSearchQuery(query: String) {
        _homeSearchQuery.value = query
    }

    fun updateSearchQuery(query: String) {
        updateExploreSearchQuery(query)
    }

    fun updateExploreSearchQuery(query: String) {
        _exploreSearchQuery.value = query
        searchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.length < 2) {
            _onlineSearchResults.value = emptyList()
            _isSearchingOnline.value = false
            return
        }
        val country = _selectedCountry.value
        searchJob = viewModelScope.launch {
            _isSearchingOnline.value = true
            delay(150L)
            val results = audioExtractor.searchSongsOnline(
                query = trimmed,
                countryCode = country.code,
                countryName = country.name
            )
            if (_exploreSearchQuery.value.trim() == trimmed) {
                _onlineSearchResults.value = results
                _isSearchingOnline.value = false
            }
        }
    }

    fun selectChartTab(tab: ChartTab) {
        _selectedChartTab.value = tab
    }

    fun selectCountry(country: CountryOption) {
        _selectedCountry.value = country
        emitSnackbar("Explore region switched to ${country.flagEmoji} ${country.name}")
        fetchCountryTrendingSongs(country)
        val currentExploreQuery = _exploreSearchQuery.value.trim()
        if (currentExploreQuery.length >= 2) {
            updateExploreSearchQuery(currentExploreQuery)
        }
    }

    private fun fetchCountryTrendingSongs(country: CountryOption) {
        countryFetchJob?.cancel()
        countryFetchJob = viewModelScope.launch {
            _isSearchingOnline.value = true
            val trending = audioExtractor.searchSongsOnline(
                query = country.trendingQuery,
                countryCode = country.code,
                countryName = country.name
            )
            _countryTrendingSongs.value = trending
            _isSearchingOnline.value = false
        }
    }

    fun openFullScreenPlayer() {
        if (_currentSong.value != null) {
            _isFullScreenPlayerOpen.value = true
        }
    }

    fun closeFullScreenPlayer() {
        _isFullScreenPlayerOpen.value = false
    }

    fun openSongOptions(song: Song) {
        _optionsSheetSong.value = song
    }

    fun closeSongOptions() {
        _optionsSheetSong.value = null
    }

    fun setAdminUploadSheetOpen(open: Boolean) {
        _isAdminUploadSheetOpen.value = open
    }

    fun openPlaylistPicker(song: Song) {
        if (!_subscriptionState.value.isActive()) {
            emitSnackbar("👑 Playlists are a VIP feature! Buy a subscription to unlock Playlists.")
            _isSubscriptionModalOpen.value = true
            return
        }
        _playlistPickerSong.value = song
    }

    fun closePlaylistPicker() {
        _playlistPickerSong.value = null
    }

    fun setSleepTimerDialogOpen(open: Boolean) {
        _isSleepTimerDialogOpen.value = open
    }

    fun setUpNextSheetOpen(open: Boolean) {
        _isUpNextSheetOpen.value = open
    }

    fun setLyricsOverlayOpen(open: Boolean) {
        _isLyricsOverlayOpen.value = open
    }

    fun playSong(
        song: Song,
        customQueue: List<Song>? = null,
        startPositionMs: Long = 0L,
        forceRestartFromBeginning: Boolean = false
    ) {
        // Enforce Admin-configured daily listening limit for Free users
        refreshDailyLimitBucketIfNeeded()
        if (!_subscriptionState.value.isActive() && _dailyListenedSeconds.value >= currentFreeDailyLimitSeconds()) {
            pausePlaybackForDailyLimit()
            return
        }

        val currentList = customQueue?.takeIf { it.isNotEmpty() }
            ?: allSongs.value.takeIf { it.isNotEmpty() }
            ?: listOf(song)

        val resolvedQueue = if (currentList.any { it.id == song.id }) {
            currentList
        } else {
            listOf(song) + currentList
        }
        _queue.value = resolvedQueue

        // If the user taps the SAME song that is currently paused mid-way, resume it from where it stopped
        // instead of restarting from 0:00!
        val isSameActiveSong = _currentSong.value?.id == song.id && !forceRestartFromBeginning && !hasHandledEndForCurrentSong
        if (isSameActiveSong && isCurrentSongLoadedInEngine && startPositionMs == 0L) {
            if (!_isPlaying.value) {
                togglePlayPause()
            }
            return
        }

        val resolvedStartMs = when {
            forceRestartFromBeginning -> 0L
            startPositionMs > 0L -> startPositionMs
            isSameActiveSong -> getValidResumePositionMs()
            else -> 0L
        }

        // Synchronously invalidate the previous track in BackstageYouTubePlayerController BEFORE
        // any coroutine suspension so trailing callbacks from the previous song can never skip the new song!
        backstagePlayer.prepareForNewTrack()

        hasRetriedCurrentTrack = false
        hasHandledEndForCurrentSong = false
        isCurrentSongLoadedInEngine = true
        _currentSong.value = song
        _currentPositionMs.value = resolvedStartMs
        if (!isSameActiveSong) {
            _durationMs.value = 0L
        }
        _isBuffering.value = true
        _isPlaying.value = true

        // Immediately sync Foreground Service notification & WakeLock so there is zero gap between tracks
        MusicPlaybackService.syncNowPlaying(
            context = appContext,
            song = song,
            isPlaying = true,
            directStreamUri = null,
            playbackSpeed = _playbackSpeed.value,
            startPositionMs = resolvedStartMs
        )

        persistPlaybackSelection(song, resolvedQueue, initialPositionMs = resolvedStartMs)

        viewModelScope.launch {
            val existing = libraryDao.getHistoryItem(song.id)
            val newCount = (existing?.playCount ?: 0) + 1
            libraryDao.upsertHistoryItem(
                SessionHistoryEntity(
                    id = song.id,
                    title = song.title,
                    artist = song.artist,
                    videoId = song.videoId,
                    bannerUrl = song.bannerUrl,
                    timestamp = song.timestamp,
                    playedAt = System.currentTimeMillis(),
                    playCount = newCount,
                    localContentUri = song.resolvedLocalUri
                )
            )
            scheduleDebouncedUserCloudSync()
        }

        playbackJob?.cancel()
        playbackJob = viewModelScope.launch {
            // 1. Check if song is a local device audio file from MediaStore (works 100% online AND offline!)
            val deviceMatchUri = song.resolvedLocalUri.ifBlank {
                _deviceLocalSongs.value.firstOrNull { it.id == song.id }?.resolvedLocalUri.orEmpty()
            }
            if (deviceMatchUri.isNotBlank()) {
                isUsingExoPlayerFallback = true
                backstagePlayer.stopAndClear()
                _activeStreamEngine.value = "Device Local Audio"
                startExoPlayerWithUri(song, deviceMatchUri, resolvedStartMs)
                return@launch
            }

            // 2. Check if song is downloaded locally for offline playback
            val downloaded = libraryDao.getDownloadedSong(song.id)
            if (downloaded != null) {
                val localFile = File(downloaded.localFilePath)
                if (localFile.exists()) {
                    isUsingExoPlayerFallback = true
                    backstagePlayer.stopAndClear()
                    _activeStreamEngine.value = "Offline Storage"
                    startExoPlayerWithUri(song, Uri.fromFile(localFile).toString(), resolvedStartMs)
                    return@launch
                }
            }

            // 3. Resolve playable YouTube Video ID (even if a legacy history/library row had a blank videoId)
            var playableVideoId = song.cleanVideoId
            if (playableVideoId.isBlank()) {
                val catalogMatch = allSongs.value.firstOrNull {
                    it.id == song.id || it.title.equals(song.title, ignoreCase = true)
                }
                playableVideoId = catalogMatch?.cleanVideoId.orEmpty()
                if (playableVideoId.isBlank()) {
                    playableVideoId = audioExtractor.findPlayableVideoIdForSong(song).orEmpty()
                }
            }

            if (playableVideoId.isBlank()) {
                _isBuffering.value = false
                _isPlaying.value = false
                emitSnackbar("Could not locate audio stream for \"${song.title}\".")
                return@launch
            }

            // 4. Primary Engine for ALL Admin & Online Songs:
            val rawTarget = song.videoId.trim()
            val isDirectAudioUrl = (rawTarget.startsWith("http://", ignoreCase = true) ||
                rawTarget.startsWith("https://", ignoreCase = true)) &&
                !rawTarget.contains("youtube.com", ignoreCase = true) &&
                !rawTarget.contains("youtu.be", ignoreCase = true)

            if (isDirectAudioUrl) {
                isUsingExoPlayerFallback = true
                backstagePlayer.stopAndClear()
                _activeStreamEngine.value = "ExoPlayer Direct Stream"
                startExoPlayerWithUri(song, rawTarget, resolvedStartMs)
            } else {
                // Instant playback via pre-warmed Backstage YouTube Engine + Foreground MediaSession Notification
                isUsingExoPlayerFallback = false
                _activeStreamEngine.value = "Backstage YouTube Engine"
                backstagePlayer.loadVideoById(
                    rawVideoId = playableVideoId,
                    startSeconds = resolvedStartMs / 1000.0,
                    songTitle = song.title,
                    songArtist = song.artist,
                    artworkUrl = song.highResBannerUrl
                )
            }
        }
    }

    private fun startExoPlayerWithUri(song: Song, streamUri: String, startPositionMs: Long = 0L) {
        _isBuffering.value = false
        _isPlaying.value = true
        MusicPlaybackService.syncNowPlaying(
            context = appContext,
            song = song,
            isPlaying = true,
            directStreamUri = streamUri,
            playbackSpeed = _playbackSpeed.value,
            startPositionMs = startPositionMs
        )
    }

    fun togglePlayPause() {
        val activeSong = _currentSong.value ?: return
        refreshDailyLimitBucketIfNeeded()
        if (!_isPlaying.value && !_subscriptionState.value.isActive() && _dailyListenedSeconds.value >= currentFreeDailyLimitSeconds()) {
            pausePlaybackForDailyLimit()
            return
        }
        MusicPlaybackService.clearExternalMediaPauseState()
        if (!isCurrentSongLoadedInEngine) {
            playSong(activeSong, _queue.value, startPositionMs = getValidResumePositionMs())
            return
        }
        if (isUsingExoPlayerFallback) {
            val nextPlay = !_isPlaying.value
            _isPlaying.value = nextPlay
            _isBuffering.value = false
            if (!nextPlay) {
                prefs.edit().putLong("last_position_ms", _currentPositionMs.value).apply()
            }
            MusicPlaybackService.syncPlayState(nextPlay)
        } else {
            if (_isPlaying.value) {
                prefs.edit().putLong("last_position_ms", _currentPositionMs.value).apply()
                backstagePlayer.pauseVideo()
                MusicPlaybackService.syncPlayState(false)
                _isPlaying.value = false
                _isBuffering.value = false
            } else {
                if (hasHandledEndForCurrentSong) {
                    playSong(activeSong, _queue.value, forceRestartFromBeginning = true)
                } else {
                    backstagePlayer.playVideo()
                    MusicPlaybackService.syncPlayState(true)
                    _isPlaying.value = true
                }
            }
        }
    }

    fun seekToPosition(positionMs: Long) {
        val maxDur = _durationMs.value.takeIf { it > 0L } ?: Long.MAX_VALUE
        val clampedMs = positionMs.coerceIn(0L, maxDur)
        _currentPositionMs.value = clampedMs
        prefs.edit().putLong("last_position_ms", clampedMs).apply()

        if (isUsingExoPlayerFallback) {
            MusicPlaybackService.syncSeekPosition(clampedMs)
        } else {
            val targetSeconds = clampedMs / 1000.0
            backstagePlayer.seekToSeconds(targetSeconds)
            MusicPlaybackService.syncOnlineProgressAndDuration(clampedMs, _durationMs.value)
        }
    }

    fun seekRelativeSeconds(deltaSeconds: Int) {
        val currentPos = _currentPositionMs.value.coerceAtLeast(0L)
        val maxDur = _durationMs.value.takeIf { it > 0L } ?: Long.MAX_VALUE
        val targetPos = (currentPos + deltaSeconds * 1000L).coerceIn(0L, maxDur)
        seekToPosition(targetPos)
    }

    fun skipToNext() {
        val current = _currentSong.value ?: return
        val list = _queue.value.ifEmpty { allSongs.value }
        if (list.isEmpty()) {
            playSong(current, listOf(current))
            return
        }

        if (_isShuffleEnabled.value && list.size > 1) {
            val candidates = list.filterNot { it.id == current.id }
            playSong(candidates.random(), list)
            return
        }

        val idx = list.indexOfFirst { it.id == current.id }
        val nextIndex = if (idx in 0 until list.lastIndex) idx + 1 else 0
        playSong(list[nextIndex], list)
    }

    fun skipToPrevious() {
        val current = _currentSong.value ?: return
        val list = _queue.value.ifEmpty { allSongs.value }
        if (list.isEmpty()) {
            playSong(current, listOf(current))
            return
        }

        val idx = list.indexOfFirst { it.id == current.id }
        val prevIndex = if (idx > 0) idx - 1 else list.lastIndex
        playSong(list[prevIndex], list)
    }

    private fun handleTrackEnded() {
        val current = _currentSong.value ?: return
        val list = _queue.value.ifEmpty { allSongs.value }
        if (list.isEmpty()) {
            playSong(current, listOf(current))
            return
        }
        when (_repeatMode.value) {
            RepeatModeState.ONE -> {
                playSong(current, list, forceRestartFromBeginning = true)
            }
            RepeatModeState.ALL, RepeatModeState.OFF -> {
                val idx = list.indexOfFirst { it.id == current.id }
                val nextIndex = if (idx in 0 until list.lastIndex) idx + 1 else 0
                playSong(list[nextIndex], list, forceRestartFromBeginning = true)
            }
        }
    }

    fun cyclePlaybackSpeed() {
        val currentIdx = PLAYBACK_SPEEDS.indexOfFirst { it == _playbackSpeed.value }
        val nextSpeed = PLAYBACK_SPEEDS[(currentIdx + 1) % PLAYBACK_SPEEDS.size]
        _playbackSpeed.value = nextSpeed
        backstagePlayer.setPlaybackSpeed(nextSpeed)
        MusicPlaybackService.syncPlaybackSpeed(nextSpeed)
        emitSnackbar("Playback speed set to ${nextSpeed}x")
    }

    fun toggleShuffle() {
        val next = !_isShuffleEnabled.value
        _isShuffleEnabled.value = next
        emitSnackbar(if (next) "Shuffle enabled" else "Shuffle disabled")
    }

    fun cycleRepeatMode() {
        if (!_subscriptionState.value.isActive()) {
            _repeatMode.value = RepeatModeState.OFF
            emitSnackbar("Repeat mode is for Premium subscribers. Upgrade to unlock Repeat!")
            _isSubscriptionModalOpen.value = true
            return
        }
        val next = when (_repeatMode.value) {
            RepeatModeState.OFF -> RepeatModeState.ALL
            RepeatModeState.ALL -> RepeatModeState.ONE
            RepeatModeState.ONE -> RepeatModeState.OFF
        }
        _repeatMode.value = next
        val label = when (next) {
            RepeatModeState.OFF -> "Repeat Off"
            RepeatModeState.ALL -> "Repeat All (Playlist Loop)"
            RepeatModeState.ONE -> "Repeat One"
        }
        emitSnackbar(label)
    }

    fun isFavorite(songId: String): Boolean {
        return favoriteEntities.value.any { it.id == songId }
    }

    fun toggleFavorite(song: Song) {
        if (!_subscriptionState.value.isActive()) {
            emitSnackbar("👑 Favorite Songs is a VIP feature! Buy a subscription to save Favorites.")
            _isSubscriptionModalOpen.value = true
            return
        }
        viewModelScope.launch {
            val currentlyFav = favoriteEntities.value.any { it.id == song.id }
            if (currentlyFav) {
                libraryDao.removeFavorite(song.id)
                emitSnackbar("Removed \"${song.title}\" from Favorites")
            } else {
                libraryDao.insertFavorite(FavoriteSongEntity.fromSong(song))
                emitSnackbar("Added \"${song.title}\" to Favorites")
            }
        }
    }

    fun createNewPlaylist(name: String, initialSong: Song? = null) {
        if (!_subscriptionState.value.isActive()) {
            emitSnackbar("Free users cannot create playlists. Buy a subscription to create playlists!")
            _isSubscriptionModalOpen.value = true
            return
        }
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val newId = libraryDao.createPlaylist(PlaylistEntity(name = trimmed))
            if (initialSong != null) {
                libraryDao.addSongToPlaylist(
                    PlaylistSongEntity(
                        playlistId = newId,
                        songId = initialSong.id,
                        title = initialSong.title,
                        artist = initialSong.artist,
                        videoId = initialSong.videoId,
                        bannerUrl = initialSong.bannerUrl,
                        timestamp = initialSong.timestamp,
                        localContentUri = initialSong.resolvedLocalUri
                    )
                )
                emitSnackbar("Created \"$trimmed\" and added \"${initialSong.title}\"")
            } else {
                emitSnackbar("Playlist \"$trimmed\" created")
            }
        }
    }

    fun addSongToExistingPlaylist(playlist: PlaylistEntity, song: Song) {
        viewModelScope.launch {
            libraryDao.addSongToPlaylist(
                PlaylistSongEntity(
                    playlistId = playlist.playlistId,
                    songId = song.id,
                    title = song.title,
                    artist = song.artist,
                    videoId = song.videoId,
                    bannerUrl = song.bannerUrl,
                    timestamp = song.timestamp,
                    localContentUri = song.resolvedLocalUri
                )
            )
            emitSnackbar("Added \"${song.title}\" to ${playlist.name}")
        }
    }

    fun deletePlaylist(playlist: PlaylistEntity) {
        viewModelScope.launch {
            libraryDao.deletePlaylist(playlist.playlistId)
            emitSnackbar("Deleted playlist \"${playlist.name}\"")
        }
    }

    fun downloadSongForOffline(song: Song) {
        if (!song.allowDownload) {
            emitSnackbar("Offline download is not enabled by Admin for this song.")
            return
        }
        if (_downloadingSongIds.value.contains(song.id)) return
        viewModelScope.launch {
            _downloadingSongIds.value = _downloadingSongIds.value + song.id
            emitSnackbar("Downloading \"${song.title}\" for offline playback...")
            val targetDir = File(appContext.filesDir, "offline_music")
            val result = audioExtractor.downloadAudioToLocalFile(song, targetDir)
            result.fold(
                onSuccess = { file ->
                    libraryDao.insertDownloadedSong(
                        DownloadedSongEntity(
                            id = song.id,
                            title = song.title,
                            artist = song.artist,
                            videoId = song.videoId,
                            bannerUrl = song.bannerUrl,
                            timestamp = song.timestamp,
                            localFilePath = file.absolutePath,
                            fileSizeBytes = file.length(),
                            localContentUri = Uri.fromFile(file).toString()
                        )
                    )
                    emitSnackbar("Downloaded \"${song.title}\" to My Music")
                },
                onFailure = { _ ->
                    emitSnackbar("Offline download unavailable for this track.")
                }
            )
            _downloadingSongIds.value = _downloadingSongIds.value - song.id
        }
    }

    fun deleteDownloadedSong(song: DownloadedSongEntity) {
        viewModelScope.launch {
            try {
                File(song.localFilePath).delete()
            } catch (_: Exception) {
            }
            libraryDao.deleteDownloadedSong(song.id)
            emitSnackbar("Removed offline copy of \"${song.title}\"")
        }
    }

    fun startSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel()
        if (minutes <= 0) {
            _sleepTimerRemainingSeconds.value = null
            emitSnackbar("Sleep timer cancelled")
            return
        }
        val totalSeconds = minutes * 60L
        _sleepTimerRemainingSeconds.value = totalSeconds
        emitSnackbar("Sleep timer set for $minutes minutes")
        sleepTimerJob = viewModelScope.launch {
            var remaining = totalSeconds
            while (remaining > 0 && isActive) {
                delay(1000L)
                remaining -= 1
                _sleepTimerRemainingSeconds.value = remaining
            }
            if (remaining <= 0L) {
                _sleepTimerRemainingSeconds.value = null
                backstagePlayer.pauseVideo()
                MusicPlaybackService.syncPlayState(false)
                _isPlaying.value = false
                emitSnackbar("Sleep timer ended — playback paused")
            }
        }
    }

    fun refreshFirebaseCatalog() {
        viewModelScope.launch {
            firebaseService.fetchSongsViaRest(silent = false)
            emitSnackbar("Library updated ✨")
        }
    }

    fun sendCloudNotificationFromAdmin(title: String, body: String, imageUrl: String = "") {
        viewModelScope.launch {
            val res = firebaseService.sendCloudNotificationFromAdmin(title, body, imageUrl)
            res.fold(
                onSuccess = {
                    emitSnackbar("Cloud Notification broadcasted to all AADIFY users! 🔔")
                },
                onFailure = { err ->
                    emitSnackbar("Failed to send notification: ${err.message}")
                }
            )
        }
    }

    fun emitSnackbar(message: String) {
        _snackbarMessage.tryEmit(message)
    }

    // =========================================================================
    // SUBSCRIPTION, CRAZY DEAL 3-DAY WINDOW, 1-HR DAILY LIMIT & FAMGATEWAY UPI
    // =========================================================================

    private fun registerNetworkCallbackMonitor() {
        try {
            val cm = connectivityManager ?: return
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    _isInternetAvailable.value = true
                }

                override fun onLost(network: Network) {
                    _isInternetAvailable.value = checkNetworkConnected()
                }

                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    val hasNet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    _isInternetAvailable.value = hasNet
                }
            })
        } catch (_: Exception) {
        }
    }

    private fun todayDateKey(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    }

    private fun utcTodayDateKey(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return fmt.format(Date())
    }

    private fun currentHourSlot1To24(): Int {
        val cal = java.util.Calendar.getInstance()
        return cal.get(java.util.Calendar.HOUR_OF_DAY) + 1
    }

    private fun loadTodayHourlySecondsMap(): MutableMap<Int, Int> {
        val map = mutableMapOf<Int, Int>()
        try {
            val raw = prefs.getString("daily_hourly_seconds_json", null)
            if (!raw.isNullOrBlank() && raw.startsWith("{")) {
                val obj = JSONObject(raw)
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val hr = k.toIntOrNull() ?: continue
                    val secs = obj.optInt(k, 0)
                    if (hr in 1..24 && secs > 0) {
                        map[hr] = secs
                    }
                }
            }
        } catch (_: Exception) {
        }
        return map
    }

    private fun saveTodayHourlySecondsMap(map: Map<Int, Int>) {
        try {
            val obj = JSONObject()
            for ((k, v) in map) {
                if (v > 0) obj.put(k.toString(), v)
            }
            prefs.edit().putString("daily_hourly_seconds_json", obj.toString()).apply()
        } catch (_: Exception) {
        }
    }

    private fun syncCurrentUserTimeUsageToAdminCloud() {
        val currentName = _userName.value.trim()
        if (currentName.isEmpty()) return
        val userId = getOrCreateCloudUserId(currentName)
        val totalSecs = _dailyListenedSeconds.value.coerceAtLeast(0)
        val hourlyMap = loadTodayHourlySecondsMap()
        firebaseService.syncUserTimeUsageToCloud(
            userId = userId,
            userName = currentName,
            utcDateKey = utcTodayDateKey(),
            localDateKey = todayDateKey(),
            totalSeconds = totalSecs,
            hourlySecondsMap = hourlyMap
        )
    }

    private fun loadTodayListenedSeconds(): Int {
        val today = todayDateKey()
        val savedDay = prefs.getString("daily_limit_date_key", "") ?: ""
        return if (savedDay == today) {
            prefs.getInt("daily_listened_seconds", 0).coerceAtLeast(0)
        } else {
            prefs.edit()
                .putString("daily_limit_date_key", today)
                .putInt("daily_listened_seconds", 0)
                .remove("daily_hourly_seconds_json")
                .apply()
            0
        }
    }

    private fun refreshDailyLimitBucketIfNeeded() {
        val today = todayDateKey()
        val savedDay = prefs.getString("daily_limit_date_key", "") ?: ""
        if (savedDay != today) {
            prefs.edit()
                .putString("daily_limit_date_key", today)
                .putInt("daily_listened_seconds", 0)
                .remove("daily_hourly_seconds_json")
                .apply()
            _dailyListenedSeconds.value = 0
            _isDailyLimitReachedModalOpen.value = false
            syncCurrentUserTimeUsageToAdminCloud()
        }
    }

    private fun loadPersistedSubscriptionState(): UserSubscriptionState {
        val planId = prefs.getString("sub_plan_id", "none") ?: "none"
        val activatedAt = prefs.getLong("sub_activated_at_ms", 0L)
        val expiresAt = prefs.getLong("sub_expires_at_ms", 0L)
        val orderId = prefs.getString("sub_order_id", "") ?: ""
        val utr = prefs.getString("sub_utr", "") ?: ""
        val state = UserSubscriptionState(
            planType = SubscriptionPlanType.fromId(planId),
            activatedAtMs = activatedAt,
            expiresAtMs = expiresAt,
            orderId = orderId,
            utr = utr
        )
        return if (state.isActive()) state else UserSubscriptionState()
    }

    private fun saveSubscriptionStateLocally(state: UserSubscriptionState) {
        prefs.edit()
            .putString("sub_plan_id", state.planType.id)
            .putLong("sub_activated_at_ms", state.activatedAtMs)
            .putLong("sub_expires_at_ms", state.expiresAtMs)
            .putString("sub_order_id", state.orderId)
            .putString("sub_utr", state.utr)
            .apply()
        _subscriptionState.value = state
        _activeSubscriptionRemainingSeconds.value = state.remainingSeconds()
    }

    private fun updateSubscriptionAndCrazyDealTimers() {
        val nowMs = System.currentTimeMillis()
        // 1. Check active subscription timer
        val currentSub = _subscriptionState.value
        if (currentSub.planType != SubscriptionPlanType.NONE) {
            if (currentSub.isActive(nowMs)) {
                _activeSubscriptionRemainingSeconds.value = currentSub.remainingSeconds(nowMs)
            } else {
                // Plan expired -> revert to Free
                saveSubscriptionStateLocally(UserSubscriptionState())
                _repeatMode.value = RepeatModeState.OFF
                emitSnackbar("Your subscription plan has expired.")
            }
        } else {
            _activeSubscriptionRemainingSeconds.value = 0L
        }

        // 2. Check Crazy Deal window for new users (configured by Admin in /premiumBanners/firstUserDeal)
        val cfg = subscriptionConfig.value
        val createdAtMs = prefs.getLong("user_created_at_ms", 0L)
        if (cfg.crazyDealEnabled && createdAtMs > 0L) {
            val validityWindowMs = cfg.crazyDealValidityHours.coerceAtLeast(1) * 3600_000L
            // Window starts from whichever is newer: user account creation OR the latest Crazy Deal published by Admin
            val baseWindowStartMs = maxOf(createdAtMs, cfg.crazyDealUpdatedAt)
            val endWindowMs = baseWindowStartMs + validityWindowMs
            val remSec = ((endWindowMs - nowMs) / 1000L).coerceAtLeast(0L)
            _crazyDealRemainingSeconds.value = remSec
        } else {
            _crazyDealRemainingSeconds.value = 0L
        }
    }

    private fun startSubscriptionAndDailyLimitMonitor() {
        updateSubscriptionAndCrazyDealTimers()
        viewModelScope.launch {
            // Initial sync of user's daily time usage to /userTimeUsage for Admin Panel
            if (_userName.value.isNotBlank()) {
                syncCurrentUserTimeUsageToAdminCloud()
            }
            while (isActive) {
                delay(1000L)
                _isInternetAvailable.value = checkNetworkConnected()
                refreshDailyLimitBucketIfNeeded()
                updateSubscriptionAndCrazyDealTimers()

                // Track active listening seconds for Free users (Admin-controllable daily limit)
                val limitSecs = currentFreeDailyLimitSeconds()
                if (!_subscriptionState.value.isActive() && _dailyListenedSeconds.value < limitSecs && _isDailyLimitReachedModalOpen.value) {
                    _isDailyLimitReachedModalOpen.value = false
                }
                if (_isPlaying.value && !_isBuffering.value && !_subscriptionState.value.isActive()) {
                    val nextSec = _dailyListenedSeconds.value + 1
                    _dailyListenedSeconds.value = nextSec

                    val hourlyMap = loadTodayHourlySecondsMap()
                    val hrSlot = currentHourSlot1To24()
                    hourlyMap[hrSlot] = (hourlyMap[hrSlot] ?: 0) + 1
                    saveTodayHourlySecondsMap(hourlyMap)

                    if (nextSec % 5 == 0 || nextSec >= limitSecs) {
                        prefs.edit()
                            .putString("daily_limit_date_key", todayDateKey())
                            .putInt("daily_listened_seconds", nextSec)
                            .apply()
                    }
                    if (nextSec % 15 == 0 || nextSec == 1 || nextSec >= limitSecs) {
                        syncCurrentUserTimeUsageToAdminCloud()
                    }
                    if (nextSec >= limitSecs) {
                        pausePlaybackForDailyLimit()
                    }
                }
            }
        }
    }

    private fun pausePlaybackForDailyLimit() {
        try {
            if (isUsingExoPlayerFallback) {
                MusicPlaybackService.syncPlayState(false)
            } else {
                backstagePlayer.pauseVideo()
                MusicPlaybackService.syncPlayState(false)
            }
        } catch (_: Exception) {
        }
        _isPlaying.value = false
        _isBuffering.value = false
        _isDailyLimitReachedModalOpen.value = true
        syncCurrentUserTimeUsageToAdminCloud()
        val limitMins = (currentFreeDailyLimitSeconds() / 60).coerceAtLeast(1)
        emitSnackbar("Your daily ${limitMins}m free limit has been reached! Buy a subscription or wait for next day.")
    }

    /**
     * Called when the user enters the Home screen after onboarding/splash.
     * Waits until the Admin Panel configuration (/subscriptionPlans & /premiumBanners) is loaded from Firebase
     * before showing the Crazy Deals popup so it NEVER flashes default values!
     */
    fun onHomeScreenEntered() {
        viewModelScope.launch {
            val currentName = _userName.value.trim()
            if (currentName.isEmpty()) return@launch
            if (_subscriptionState.value.isActive()) return@launch

            // Wait up to 2.5s for Firebase Admin config to finish loading on cold start
            var waitedMs = 0L
            while (!firebaseService.isSubscriptionConfigLoaded.value && waitedMs < 2500L) {
                delay(100L)
                waitedMs += 100L
            }

            if (_subscriptionState.value.isActive()) return@launch
            val cfg = subscriptionConfig.value
            if (!cfg.crazyDealEnabled) return@launch
            updateSubscriptionAndCrazyDealTimers()
            if (_crazyDealRemainingSeconds.value <= 0L) return@launch

            val userId = getOrCreateCloudUserId(currentName)
            val seenKey = "crazy_deal_popup_seen_${userId}_${cfg.crazyDealUpdatedAt}"
            val alreadySeen = prefs.getBoolean(seenKey, false)
            if (!alreadySeen) {
                prefs.edit().putBoolean(seenKey, true).apply()
                _isCrazyDealsPopupOpen.value = true
            }
        }
    }

    fun setCrazyDealsPopupOpen(open: Boolean) {
        _isCrazyDealsPopupOpen.value = open
    }

    fun setSubscriptionModalOpen(open: Boolean) {
        _isSubscriptionModalOpen.value = open
    }

    fun setDailyLimitReachedModalOpen(open: Boolean) {
        _isDailyLimitReachedModalOpen.value = open
    }

    /**
     * Initiates AadarshPay automated UPI payment for the chosen plan (`CRAZY_DEAL`, `WEEKLY`, `MONTHLY`, `FOREVER`).
     */
    fun startFamGatewayCheckout(planType: SubscriptionPlanType) {
        val config = subscriptionConfig.value
        val amountInr = when (planType) {
            SubscriptionPlanType.CRAZY_DEAL -> config.crazyDealPriceInr
            SubscriptionPlanType.WEEKLY, SubscriptionPlanType.CUSTOM -> config.weeklyPriceInr
            SubscriptionPlanType.MONTHLY -> config.monthlyPriceInr
            SubscriptionPlanType.FOREVER -> config.foreverPriceInr
            SubscriptionPlanType.NONE -> return
        }

        val currentName = _userName.value.trim().ifBlank { "AADIFY User" }
        val userId = getOrCreateCloudUserId(currentName)

        paymentPollingJob?.cancel()
        _isCrazyDealsPopupOpen.value = false
        _isSubscriptionModalOpen.value = false
        _isDailyLimitReachedModalOpen.value = false
        _isFamGatewayCheckoutOpen.value = true
        _isCreatingPaymentOrder.value = true
        _isUserClickedIHavePaid.value = false
        _isPaymentVerifiedSuccess.value = false
        _activeCheckoutOrder.value = null
        _paymentErrorMessage.value = null
        _paymentStatusMessage.value = "Creating AadarshPay UPI order..."

        viewModelScope.launch {
            val result = famGatewayService.createOrder(
                planType = planType,
                amountInr = amountInr,
                customerName = currentName,
                customerId = userId
            )
            _isCreatingPaymentOrder.value = false
            result.fold(
                onSuccess = { session ->
                    _activeCheckoutOrder.value = session
                    _paymentStatusMessage.value = "Pending • Waiting for UPI payment..."
                    firebaseService.logPaymentAttemptToCloud(
                        userId = userId,
                        userName = currentName,
                        session = session,
                        status = "PENDING"
                    )
                    startPollingFamGatewayOrder(session)
                },
                onFailure = { err ->
                    _paymentErrorMessage.value = err.message ?: "Could not create payment order."
                }
            )
        }
    }

    private fun startPollingFamGatewayOrder(session: FamGatewayOrderSession) {
        paymentPollingJob?.cancel()
        val currentName = _userName.value.trim().ifBlank { "AADIFY User" }
        val userId = getOrCreateCloudUserId(currentName)
        paymentPollingJob = viewModelScope.launch {
            val startTimeMs = System.currentTimeMillis()
            val maxPollDurationMs = 15 * 60 * 1000L // 15 minutes
            while (isActive && _isFamGatewayCheckoutOpen.value && !_isPaymentVerifiedSuccess.value &&
                (System.currentTimeMillis() - startTimeMs) < maxPollDurationMs
            ) {
                // Fast real-time polling: 800ms once user clicked "I Have Paid", 1400ms otherwise
                val intervalMs = if (_isUserClickedIHavePaid.value) 800L else 1400L
                delay(intervalMs)
                if (!_isFamGatewayCheckoutOpen.value || _isPaymentVerifiedSuccess.value) break

                val statusResult = famGatewayService.checkOrderStatus(session.orderId)
                statusResult.onSuccess { status ->
                    when {
                        status.isSuccess -> {
                            onFamGatewayPaymentVerified(
                                session = session,
                                utr = status.utr
                            )
                            return@launch
                        }
                        status.isFailed -> {
                            _isUserClickedIHavePaid.value = false
                            _paymentStatusMessage.value = "Payment failed or expired. Please try again."
                            firebaseService.logPaymentAttemptToCloud(
                                userId = userId,
                                userName = currentName,
                                session = session,
                                status = "FAILED"
                            )
                            return@launch
                        }
                        else -> {
                            if (_isUserClickedIHavePaid.value) {
                                _paymentStatusMessage.value = "Checking payment with bank & AadarshPay... Please wait"
                            } else {
                                _paymentStatusMessage.value = "Pending • Complete payment in UPI app"
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Triggered when the user clicks "I Have Paid" ONCE.
     * Locks the button into continuous "Checking Payment..." mode and immediately verifies
     * against the real AadarshPay / FamGateway server at high speed without requiring repeated clicks.
     */
    fun triggerImmediatePaymentVerification() {
        val session = _activeCheckoutOrder.value ?: return
        if (_isPaymentVerifiedSuccess.value) return
        _isUserClickedIHavePaid.value = true
        _paymentStatusMessage.value = "Checking payment with bank & AadarshPay... Please wait"

        // Restart polling loop so it immediately switches to 800ms turbo interval
        startPollingFamGatewayOrder(session)

        viewModelScope.launch {
            val res = famGatewayService.verifyOrderAuthoritative(session.orderId)
            res.onSuccess { status ->
                if (status.isSuccess && !_isPaymentVerifiedSuccess.value) {
                    onFamGatewayPaymentVerified(session, status.utr)
                }
            }
        }
    }

    private fun onFamGatewayPaymentVerified(session: FamGatewayOrderSession, utr: String) {
        if (_isPaymentVerifiedSuccess.value) return
        _isPaymentVerifiedSuccess.value = true
        _isUserClickedIHavePaid.value = false
        _paymentStatusMessage.value = "Payment Successful! VIP Plan Activated ✓"
        paymentPollingJob?.cancel()

        val nowMs = System.currentTimeMillis()
        val config = subscriptionConfig.value
        val durationMs = when (session.planType) {
            SubscriptionPlanType.CRAZY_DEAL -> config.crazyDealDurationDays.coerceAtLeast(1) * 86_400_000L
            SubscriptionPlanType.WEEKLY, SubscriptionPlanType.CUSTOM -> 7L * 86_400_000L
            SubscriptionPlanType.MONTHLY -> 30L * 86_400_000L
            SubscriptionPlanType.FOREVER -> 100L * 365L * 86_400_000L
            SubscriptionPlanType.NONE -> 0L
        }

        val currentSub = _subscriptionState.value
        val baseStartMs = if (currentSub.isActive(nowMs) && currentSub.expiresAtMs > nowMs && currentSub.planType != SubscriptionPlanType.FOREVER) {
            currentSub.expiresAtMs
        } else {
            nowMs
        }

        val newState = UserSubscriptionState(
            planType = session.planType,
            activatedAtMs = nowMs,
            expiresAtMs = baseStartMs + durationMs,
            orderId = session.orderId,
            utr = utr
        )

        // Immediately save VIP state locally and sync to Firebase Cloud
        saveSubscriptionStateLocally(newState)
        _isDailyLimitReachedModalOpen.value = false
        _isSubscriptionModalOpen.value = false
        _isCrazyDealsPopupOpen.value = false

        val currentName = _userName.value.trim().ifBlank { "AADIFY User" }
        val userId = getOrCreateCloudUserId(currentName)
        firebaseService.logPaymentAttemptToCloud(
            userId = userId,
            userName = currentName,
            session = session,
            status = "SUCCESS",
            utr = utr
        )
        firebaseService.syncSubscriptionToCloud(
            userId = userId,
            userName = currentName,
            state = newState,
            amountInr = session.amountInr
        )
        syncCurrentUserDataToAdminCloud(isExplicitLogin = false)

        emitSnackbar("🎉 Payment Successful! ${session.planType.displayName} activated!")

        // Show the green "Payment Successful" status button in the modal for 1.1s before auto-closing
        viewModelScope.launch {
            delay(1100L)
            _isFamGatewayCheckoutOpen.value = false
            _activeCheckoutOrder.value = null
            _isPaymentVerifiedSuccess.value = false
        }
    }

    fun closeFamGatewayCheckout() {
        paymentPollingJob?.cancel()
        val pendingSession = _activeCheckoutOrder.value
        if (pendingSession != null && !_isPaymentVerifiedSuccess.value) {
            val currentName = _userName.value.trim().ifBlank { "AADIFY User" }
            val userId = getOrCreateCloudUserId(currentName)
            firebaseService.logPaymentAttemptToCloud(
                userId = userId,
                userName = currentName,
                session = pendingSession,
                status = "CANCELLED"
            )
        }
        _isFamGatewayCheckoutOpen.value = false
        _activeCheckoutOrder.value = null
        _isUserClickedIHavePaid.value = false
        _isPaymentVerifiedSuccess.value = false
        _isCreatingPaymentOrder.value = false
        _paymentErrorMessage.value = null
    }

    override fun onCleared() {
        super.onCleared()
        paymentPollingJob?.cancel()
        firebaseService.onUserDeletedByAdminCallback = null
        firebaseService.onAdminVipStateSyncedCallback = null
        MusicPlaybackService.onSkipNextCallback = null
        MusicPlaybackService.onSkipPreviousCallback = null
        MusicPlaybackService.onTogglePlayPauseCallback = null
        MusicPlaybackService.onSeekToCallback = null
        MusicPlaybackService.onExoPlayerTrackEndedCallback = null
        MusicPlaybackService.onExoPlayerStreamErrorCallback = null
        MusicPlaybackService.onKeepOnlineEngineAwakeCallback = null
        backstagePlayer.release()
        controllerFuture?.let { MediaController.releaseFuture(it) }
    }
}
