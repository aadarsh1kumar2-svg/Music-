package com.example.providers

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.ContentUris
import android.content.pm.PackageManager
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
import com.example.models.DownloadedSongEntity
import com.example.models.FavoriteSongEntity
import com.example.models.PlaylistEntity
import com.example.models.PlaylistSongEntity
import com.example.models.SessionHistoryEntity
import com.example.models.Song
import com.example.services.AudioStreamExtractor
import com.example.services.BackstageYouTubePlayerController
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
    }

    private val appContext = application.applicationContext
    private val prefs = appContext.getSharedPreferences("ak_music_user_prefs", android.content.Context.MODE_PRIVATE)

    // User Name State (First-time login)
    private val _userName = MutableStateFlow(prefs.getString("user_name", "")?.trim() ?: "")
    val userName: StateFlow<String> = _userName.asStateFlow()

    private val _isEditNameDialogOpen = MutableStateFlow(false)
    val isEditNameDialogOpen: StateFlow<Boolean> = _isEditNameDialogOpen.asStateFlow()

    // Developer Screen Modal State
    private val _isDeveloperScreenOpen = MutableStateFlow(false)
    val isDeveloperScreenOpen: StateFlow<Boolean> = _isDeveloperScreenOpen.asStateFlow()

    fun setDeveloperScreenOpen(open: Boolean) {
        _isDeveloperScreenOpen.value = open
    }

    fun saveUserName(name: String) {
        val cleaned = name.trim()
        if (cleaned.isEmpty()) return
        val previousName = _userName.value
        val now = System.currentTimeMillis()
        if (prefs.getLong("user_created_at_ms", 0L) <= 0L) {
            prefs.edit().putLong("user_created_at_ms", now).apply()
        }
        // Ensure a stable cloud_user_id exists (or create a fresh one if logging in for the first time / after Admin deletion)
        val existingUserId = prefs.getString("cloud_user_id", "")?.trim().orEmpty()
        val resolvedUserId = if (existingUserId.isNotBlank() && previousName.isNotBlank()) {
            existingUserId
        } else {
            generateCloudUserId(cleaned)
        }
        prefs.edit()
            .putString("user_name", cleaned)
            .putString("cloud_user_id", resolvedUserId)
            .putLong("user_last_login_ms", now)
            .apply()
        _userName.value = cleaned
        _isEditNameDialogOpen.value = false
        firebaseService.resetAdminDeletionFlagForFreshLogin()
        syncCurrentUserDataToAdminCloud(isExplicitLogin = true)
        emitSnackbar("Welcome to AADIFY, $cleaned! 🎵")
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
                val bucket = posMs / 2000L
                if (bucket != lastPersistedPosBucket) {
                    lastPersistedPosBucket = bucket
                    prefs.edit().putLong("last_position_ms", posMs).apply()
                }
            }
        },
        onDurationUpdate = { durMs ->
            if (!isUsingExoPlayerFallback && !hasHandledEndForCurrentSong && durMs > 0L) {
                if (_durationMs.value != durMs) {
                    _durationMs.value = durMs
                    prefs.edit().putLong("last_duration_ms", durMs).apply()
                }
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
        if (_currentSong.value != null && _isPlaying.value && !isUsingExoPlayerFallback) {
            backstagePlayer.keepAwake()
            MusicPlaybackService.syncPlayState(true)
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
            viewModelScope.launch {
                val active = _currentSong.value ?: return@launch
                if (!isCurrentSongLoadedInEngine && shouldPlay) {
                    playSong(active, _queue.value, startPositionMs = getValidResumePositionMs())
                    return@launch
                }
                if (isUsingExoPlayerFallback) {
                    _isPlaying.value = shouldPlay
                    if (shouldPlay) {
                        _isBuffering.value = false
                    } else {
                        prefs.edit().putLong("last_position_ms", _currentPositionMs.value).apply()
                    }
                } else {
                    if (shouldPlay) {
                        backstagePlayer.playVideo()
                        _isPlaying.value = true
                        _isBuffering.value = false
                    } else {
                        prefs.edit().putLong("last_position_ms", _currentPositionMs.value).apply()
                        backstagePlayer.pauseVideo()
                        _isPlaying.value = false
                        _isBuffering.value = false
                    }
                }
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
            if (_currentSong.value != null && _isPlaying.value && !isUsingExoPlayerFallback && !hasHandledEndForCurrentSong) {
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

        // Initial cloud sync if user is already logged in with their name
        if (_userName.value.isNotBlank()) {
            syncCurrentUserDataToAdminCloud(isExplicitLogin = true)
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
            .remove("cloud_user_id")
            .remove("user_created_at_ms")
            .remove("user_last_login_ms")
            .apply()

        try {
            backstagePlayer.stopAndClear()
            MusicPlaybackService.syncPlayState(false)
        } catch (_: Exception) {
        }
        _isPlaying.value = false
        _isBuffering.value = false
        _isFullScreenPlayerOpen.value = false
        _isEditNameDialogOpen.value = false
        _userName.value = ""
        emitSnackbar("Your account was removed by Admin. Please enter your name to login again.")
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
            put("email", "$slug@aadify.app")

            // Status & Device Metadata
            put("status", "Active")
            put("isOnline", true)
            put("active", true)
            put("role", "User")
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

    override fun onCleared() {
        super.onCleared()
        firebaseService.onUserDeletedByAdminCallback = null
        MusicPlaybackService.onSkipNextCallback = null
        MusicPlaybackService.onSkipPreviousCallback = null
        MusicPlaybackService.onTogglePlayPauseCallback = null
        MusicPlaybackService.onExoPlayerTrackEndedCallback = null
        MusicPlaybackService.onExoPlayerStreamErrorCallback = null
        MusicPlaybackService.onKeepOnlineEngineAwakeCallback = null
        backstagePlayer.release()
        controllerFuture?.let { MediaController.releaseFuture(it) }
    }
}
