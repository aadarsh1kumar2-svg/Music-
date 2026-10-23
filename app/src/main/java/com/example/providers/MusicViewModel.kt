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
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
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
import java.io.File

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
        prefs.edit().putString("user_name", cleaned).apply()
        _userName.value = cleaned
        _isEditNameDialogOpen.value = false
        emitSnackbar("Welcome to AADIFY, $cleaned! 🎵")
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

    // Database
    private val db = Room.databaseBuilder(
        appContext,
        AKMusicDatabase::class.java,
        "ak_music_local.db"
    ).fallbackToDestructiveMigration(true).build()
    private val libraryDao = db.musicLibraryDao()

    // Services
    private val firebaseService = FirebaseSongsService(appContext)
    private val audioExtractor = AudioStreamExtractor()

    // Media3 Controller (Foreground MediaSessionService for Notification Bar + Lockscreen + Offline Playback)
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null
    private var pendingPlaybackPair: Pair<Song, String>? = null
    private var pendingKeepAliveSong: Song? = null
    private var keepAliveActiveForSongId: String? = null
    private var isUsingExoPlayerFallback = false

    // Hidden Backstage YouTube Player Controller (Primary Engine for all Admin & Online Songs!)
    private val backstagePlayer = BackstageYouTubePlayerController(
        context = appContext,
        onProgressUpdate = { posMs ->
            if (!isUsingExoPlayerFallback) {
                _currentPositionMs.value = posMs
            }
        },
        onDurationUpdate = { durMs ->
            if (!isUsingExoPlayerFallback && durMs > 0L) {
                _durationMs.value = durMs
            }
        },
        onStateChangeUpdate = { state ->
            if (!isUsingExoPlayerFallback) {
                when (state) {
                    BackstageYouTubePlayerController.STATE_PLAYING -> {
                        _isBuffering.value = false
                        _isPlaying.value = true
                        val activeSong = _currentSong.value
                        val controller = mediaController
                        if (activeSong != null && keepAliveActiveForSongId != activeSong.id) {
                            startNotificationKeepAliveForSong(activeSong)
                        } else if (controller != null && !controller.isPlaying && controller.mediaItemCount > 0) {
                            controller.play()
                        }
                    }
                    BackstageYouTubePlayerController.STATE_PAUSED -> {
                        _isBuffering.value = false
                        _isPlaying.value = false
                        val controller = mediaController
                        if (controller != null && controller.isPlaying) {
                            controller.pause()
                        }
                    }
                    BackstageYouTubePlayerController.STATE_BUFFERING -> {
                        _isBuffering.value = true
                    }
                    BackstageYouTubePlayerController.STATE_ENDED -> {
                        _isBuffering.value = false
                        _isPlaying.value = false
                        handleTrackEnded()
                    }
                }
            }
        },
        onErrorUpdate = { _ ->
            val active = _currentSong.value ?: return@BackstageYouTubePlayerController
            if (!isUsingExoPlayerFallback) {
                isUsingExoPlayerFallback = true
                viewModelScope.launch {
                    val fallbackResult = audioExtractor.extractBestAudioStream(
                        rawVideoId = active.videoId,
                        songTitle = active.title,
                        songArtist = active.artist,
                        forceFresh = true
                    )
                    fallbackResult.fold(
                        onSuccess = { resolved ->
                            _activeStreamEngine.value = resolved.sourceEngine
                            startExoPlayerWithUri(active, resolved.streamUrl)
                        },
                        onFailure = {
                            _isBuffering.value = false
                            _isPlaying.value = false
                            emitSnackbar("Unable to play this track right now.")
                        }
                    )
                }
            }
        }
    )

    fun attachBackstagePlayer(container: ViewGroup) {
        backstagePlayer.attachToContainer(container)
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

    // Search & Live Online Search Engine
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _onlineSearchResults = MutableStateFlow<List<Song>>(emptyList())
    private var searchJob: Job? = null

    private val _selectedChartTab = MutableStateFlow(ChartTab.LOCAL)
    val selectedChartTab: StateFlow<ChartTab> = _selectedChartTab.asStateFlow()

    private val _selectedCountry = MutableStateFlow(SUPPORTED_COUNTRIES.first())
    val selectedCountry: StateFlow<CountryOption> = _selectedCountry.asStateFlow()

    // Catalog Songs & Filtered State (Admin Catalog + Live Online Search Engine)
    val allSongs: StateFlow<List<Song>> = firebaseService.songs
    val isLoadingSongs: StateFlow<Boolean> = firebaseService.isLoading
    val firebaseError: StateFlow<String?> = firebaseService.connectionError

    val filteredSongs: StateFlow<List<Song>> = combine(
        firebaseService.songs,
        _searchQuery,
        _onlineSearchResults
    ) { songs, query, onlineMatches ->
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            songs
        } else {
            val tokens = trimmed.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
            val localMatches = songs.filter { song ->
                val haystack = "${song.title} ${song.artist} ${song.videoId}".lowercase()
                tokens.all { token -> haystack.contains(token) } ||
                        song.title.contains(trimmed, ignoreCase = true) ||
                        song.artist.contains(trimmed, ignoreCase = true)
            }
            val existingVideoIds = localMatches.map { it.cleanVideoId }.toSet()
            val extraOnline = onlineMatches.filter { it.cleanVideoId !in existingVideoIds }
            localMatches + extraOnline
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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

    // Charts Computed List
    val chartSongs: StateFlow<List<Song>> = combine(
        firebaseService.songs,
        sessionHistoryEntities,
        _selectedChartTab
    ) { songs, history, chartTab ->
        val playCountMap = history.associate { it.id to it.playCount }
        when (chartTab) {
            ChartTab.LOCAL -> {
                songs.sortedWith(
                    compareByDescending<Song> { playCountMap[it.id] ?: 0 }
                        .thenByDescending { it.timestamp }
                )
            }
            ChartTab.GLOBAL -> {
                songs.sortedByDescending { it.timestamp }
            }
        }
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
                if (!isUsingExoPlayerFallback) {
                    if (shouldPlay) {
                        backstagePlayer.playVideo()
                        _isPlaying.value = true
                    } else {
                        backstagePlayer.pauseVideo()
                        _isPlaying.value = false
                    }
                }
            }
        }

        // Pre-warm hidden backstage WebView and YouTube SPA shell on main thread
        backstagePlayer.preWarmEngine()
        initializeMediaController()
        startProgressPoller()
        if (checkAudioPermissionGranted()) {
            loadDeviceLocalSongs()
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
                        val controller = future.get()
                        mediaController = controller
                        attachPlayerListener(controller)
                        pendingPlaybackPair?.let { (song, streamUri) ->
                            pendingPlaybackPair = null
                            startExoPlayerWithUri(song, streamUri)
                        }
                        pendingKeepAliveSong?.let { song ->
                            pendingKeepAliveSong = null
                            startNotificationKeepAliveForSong(song)
                        }
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

    private fun attachPlayerListener(controller: MediaController) {
        controller.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isUsingExoPlayerFallback) {
                    _isPlaying.value = isPlaying
                    if (isPlaying) {
                        _isBuffering.value = false
                    }
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (!isUsingExoPlayerFallback) return
                _isBuffering.value = (playbackState == Player.STATE_BUFFERING)
                if (playbackState == Player.STATE_READY) {
                    _isBuffering.value = false
                    _durationMs.value = controller.duration.coerceAtLeast(0L)
                } else if (playbackState == Player.STATE_ENDED) {
                    _isBuffering.value = false
                    handleTrackEnded()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (!isUsingExoPlayerFallback) return
                Log.e(TAG, "ExoPlayer PlaybackException: ${error.message}", error)
                _isBuffering.value = false
                _isPlaying.value = false
            }
        })
    }

    private fun startProgressPoller() {
        viewModelScope.launch {
            while (isActive) {
                if (isUsingExoPlayerFallback) {
                    val controller = mediaController
                    if (controller != null && controller.isPlaying) {
                        _currentPositionMs.value = controller.currentPosition.coerceAtLeast(0L)
                        val total = controller.duration
                        if (total > 0) {
                            _durationMs.value = total
                        }
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

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
        searchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.length < 2) {
            _onlineSearchResults.value = emptyList()
            return
        }
        searchJob = viewModelScope.launch {
            delay(350L)
            val results = audioExtractor.searchSongsOnline(trimmed)
            if (_searchQuery.value.trim() == trimmed) {
                _onlineSearchResults.value = results
            }
        }
    }

    fun selectChartTab(tab: ChartTab) {
        _selectedChartTab.value = tab
    }

    fun selectCountry(country: CountryOption) {
        _selectedCountry.value = country
        emitSnackbar("Explore region switched to ${country.flagEmoji} ${country.name}")
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

    fun playSong(song: Song, customQueue: List<Song>? = null) {
        val currentList = customQueue?.takeIf { it.isNotEmpty() }
            ?: allSongs.value.takeIf { it.isNotEmpty() }
            ?: listOf(song)

        _queue.value = if (currentList.any { it.id == song.id }) {
            currentList
        } else {
            listOf(song) + currentList
        }

        hasRetriedCurrentTrack = false
        _currentSong.value = song
        _currentPositionMs.value = 0L
        _durationMs.value = 0L
        _isBuffering.value = true
        _isPlaying.value = false

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
                    playCount = newCount
                )
            )
        }

        playbackJob?.cancel()
        playbackJob = viewModelScope.launch {
            // 1. Check if song is a local device audio file from MediaStore
            if (song.localContentUri.isNotBlank()) {
                isUsingExoPlayerFallback = true
                backstagePlayer.pauseVideo()
                _activeStreamEngine.value = "Device Local Audio"
                startExoPlayerWithUri(song, song.localContentUri)
                return@launch
            }

            // 2. Check if song is downloaded locally for offline playback
            val downloaded = libraryDao.getDownloadedSong(song.id)
            if (downloaded != null) {
                val localFile = File(downloaded.localFilePath)
                if (localFile.exists()) {
                    isUsingExoPlayerFallback = true
                    backstagePlayer.pauseVideo()
                    _activeStreamEngine.value = "Offline Storage"
                    startExoPlayerWithUri(song, Uri.fromFile(localFile).toString())
                    return@launch
                }
            }

            // 3. Primary Engine for ALL Admin & Online Songs:
            // Play immediately via BackstageYouTubePlayerController without AudioTrack contention
            isUsingExoPlayerFallback = false
            keepAliveActiveForSongId = null
            _activeStreamEngine.value = "Backstage YouTube Engine"
            backstagePlayer.loadVideoById(song.cleanVideoId)

            // Fallback keep-alive activation if user locks screen before STATE_PLAYING arrives
            delay(1800L)
            if (!isUsingExoPlayerFallback && _currentSong.value?.id == song.id && keepAliveActiveForSongId != song.id) {
                startNotificationKeepAliveForSong(song)
            }
        }
    }

    /**
     * Starts a silent looping audio session in MusicPlaybackService with the current song's
     * Title, Artist, and Artwork so the Android Notification Bar controls (Previous, Play/Pause, Next)
     * and background/screen-off WakeLock stay active while BackstageYouTubePlayerController streams audio.
     */
    private fun startNotificationKeepAliveForSong(song: Song) {
        val controller = mediaController
        if (controller == null) {
            pendingKeepAliveSong = song
            initializeMediaController()
            return
        }

        keepAliveActiveForSongId = song.id
        val silentUri = MusicPlaybackService.getOrCreateSilentWavUri(appContext)
        val artworkUri = if (song.highResBannerUrl.isNotBlank()) {
            Uri.parse(song.highResBannerUrl)
        } else {
            null
        }

        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.artist)
            .setDisplayTitle(song.title)
            .setSubtitle(song.artist)
        if (artworkUri != null) {
            metadataBuilder.setArtworkUri(artworkUri)
        }

        val mediaItem = MediaItem.Builder()
            .setMediaId(song.id)
            .setUri(silentUri)
            .setMediaMetadata(metadataBuilder.build())
            .build()

        controller.volume = 0f
        controller.repeatMode = Player.REPEAT_MODE_ONE
        controller.setMediaItem(mediaItem)
        controller.prepare()
        controller.play()
    }

    private fun startExoPlayerWithUri(song: Song, streamUri: String) {
        val controller = mediaController
        if (controller == null) {
            pendingPlaybackPair = song to streamUri
            initializeMediaController()
            return
        }

        val artworkUri = if (song.highResBannerUrl.isNotBlank()) {
            Uri.parse(song.highResBannerUrl)
        } else {
            null
        }

        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.artist)
            .setDisplayTitle(song.title)
            .setSubtitle(song.artist)
        if (artworkUri != null) {
            metadataBuilder.setArtworkUri(artworkUri)
        }

        val mediaItem = MediaItem.Builder()
            .setMediaId(song.id)
            .setUri(streamUri)
            .setMediaMetadata(metadataBuilder.build())
            .build()

        controller.volume = 1f
        controller.repeatMode = Player.REPEAT_MODE_OFF
        controller.setMediaItem(mediaItem)
        controller.playbackParameters = PlaybackParameters(_playbackSpeed.value)
        controller.prepare()
        controller.play()
        _isPlaying.value = true
    }

    fun togglePlayPause() {
        val activeSong = _currentSong.value ?: return
        if (isUsingExoPlayerFallback) {
            val controller = mediaController ?: return
            if (controller.isPlaying) {
                controller.pause()
                _isPlaying.value = false
            } else {
                if (controller.mediaItemCount == 0) {
                    playSong(activeSong, _queue.value)
                } else {
                    controller.play()
                    _isPlaying.value = true
                }
            }
        } else {
            if (_isPlaying.value) {
                backstagePlayer.pauseVideo()
                mediaController?.pause()
                _isPlaying.value = false
            } else {
                backstagePlayer.playVideo()
                mediaController?.play()
                _isPlaying.value = true
            }
        }
    }

    fun seekToPosition(positionMs: Long) {
        val maxDur = _durationMs.value.takeIf { it > 0L } ?: Long.MAX_VALUE
        val clampedMs = positionMs.coerceIn(0L, maxDur)
        _currentPositionMs.value = clampedMs

        if (isUsingExoPlayerFallback) {
            mediaController?.seekTo(clampedMs)
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
        val list = _queue.value
        val current = _currentSong.value ?: return
        if (list.isEmpty()) return

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
        if (_currentPositionMs.value > 4000L) {
            seekToPosition(0L)
            return
        }

        val list = _queue.value
        val current = _currentSong.value ?: return
        if (list.isEmpty()) return

        val idx = list.indexOfFirst { it.id == current.id }
        val prevIndex = if (idx > 0) idx - 1 else list.lastIndex
        playSong(list[prevIndex], list)
    }

    private fun handleTrackEnded() {
        val current = _currentSong.value ?: return
        val list = _queue.value
        when (_repeatMode.value) {
            RepeatModeState.ONE -> {
                seekToPosition(0L)
                if (isUsingExoPlayerFallback) {
                    mediaController?.play()
                } else {
                    backstagePlayer.playVideo()
                }
            }
            RepeatModeState.ALL -> {
                skipToNext()
            }
            RepeatModeState.OFF -> {
                val idx = list.indexOfFirst { it.id == current.id }
                if (idx in 0 until list.lastIndex || _isShuffleEnabled.value) {
                    skipToNext()
                } else if (list.size > 1) {
                    skipToNext()
                } else {
                    _isPlaying.value = false
                }
            }
        }
    }

    fun cyclePlaybackSpeed() {
        val currentIdx = PLAYBACK_SPEEDS.indexOfFirst { it == _playbackSpeed.value }
        val nextSpeed = PLAYBACK_SPEEDS[(currentIdx + 1) % PLAYBACK_SPEEDS.size]
        _playbackSpeed.value = nextSpeed
        backstagePlayer.setPlaybackSpeed(nextSpeed)
        mediaController?.playbackParameters = PlaybackParameters(nextSpeed)
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
                        timestamp = initialSong.timestamp
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
                    timestamp = song.timestamp
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
                            fileSizeBytes = file.length()
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
                mediaController?.pause()
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

    fun emitSnackbar(message: String) {
        _snackbarMessage.tryEmit(message)
    }

    override fun onCleared() {
        super.onCleared()
        MusicPlaybackService.onSkipNextCallback = null
        MusicPlaybackService.onSkipPreviousCallback = null
        MusicPlaybackService.onTogglePlayPauseCallback = null
        backstagePlayer.release()
        controllerFuture?.let { MediaController.releaseFuture(it) }
    }
}
