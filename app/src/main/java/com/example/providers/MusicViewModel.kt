package com.example.providers

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import android.util.Log
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
import com.example.services.FirebaseSongsService
import com.example.services.MusicPlaybackService
import com.example.utils.CountryOption
import com.example.utils.SUPPORTED_COUNTRIES
import com.google.common.util.concurrent.ListenableFuture
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

    fun saveUserName(name: String) {
        val cleaned = name.trim()
        if (cleaned.isEmpty()) return
        prefs.edit().putString("user_name", cleaned).apply()
        _userName.value = cleaned
        _isEditNameDialogOpen.value = false
        emitSnackbar("Welcome, $cleaned! 🎵")
    }

    fun setEditNameDialogOpen(open: Boolean) {
        _isEditNameDialogOpen.value = open
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

    // Media3 Controller
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null
    private var pendingPlaybackPair: Pair<Song, String>? = null

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

    // Search & Filters
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedChartTab = MutableStateFlow(ChartTab.LOCAL)
    val selectedChartTab: StateFlow<ChartTab> = _selectedChartTab.asStateFlow()

    private val _selectedCountry = MutableStateFlow(SUPPORTED_COUNTRIES.first())
    val selectedCountry: StateFlow<CountryOption> = _selectedCountry.asStateFlow()

    // Firebase Songs & Filtered State
    val allSongs: StateFlow<List<Song>> = firebaseService.songs
    val isLoadingSongs: StateFlow<Boolean> = firebaseService.isLoading
    val firebaseError: StateFlow<String?> = firebaseService.connectionError

    val filteredSongs: StateFlow<List<Song>> = combine(
        firebaseService.songs,
        _searchQuery
    ) { songs, query ->
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            songs
        } else {
            songs.filter {
                it.title.contains(trimmed, ignoreCase = true) ||
                        it.artist.contains(trimmed, ignoreCase = true) ||
                        it.videoId.contains(trimmed, ignoreCase = true)
            }
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

    private val _activeStreamEngine = MutableStateFlow("")
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
        initializeMediaController()
        startProgressPoller()
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
                _isPlaying.value = isPlaying
                if (isPlaying) {
                    _isBuffering.value = false
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
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
                Log.e(TAG, "ExoPlayer PlaybackException: ${error.message}", error)
                val active = _currentSong.value
                if (active != null && !hasRetriedCurrentTrack) {
                    hasRetriedCurrentTrack = true
                    audioExtractor.invalidateCachedStream(active.videoId)
                    viewModelScope.launch {
                        val retryResult = audioExtractor.extractBestAudioStream(
                            rawVideoId = active.videoId,
                            songTitle = active.title,
                            songArtist = active.artist,
                            forceFresh = true
                        )
                        retryResult.fold(
                            onSuccess = { resolved ->
                                startExoPlayerWithUri(active, resolved.streamUrl)
                            },
                            onFailure = {
                                _isBuffering.value = false
                                _isPlaying.value = false
                                emitSnackbar("Stream unavailable for this track.")
                            }
                        )
                    }
                } else {
                    _isBuffering.value = false
                    _isPlaying.value = false
                    emitSnackbar("Stream unavailable for this track.")
                }
            }
        })
    }

    private fun startProgressPoller() {
        viewModelScope.launch {
            while (isActive) {
                val controller = mediaController
                if (controller != null && controller.isPlaying) {
                    _currentPositionMs.value = controller.currentPosition.coerceAtLeast(0L)
                    val total = controller.duration
                    if (total > 0) {
                        _durationMs.value = total
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
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
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
            // 1. Check if song is downloaded locally for instant offline playback
            val downloaded = libraryDao.getDownloadedSong(song.id)
            if (downloaded != null) {
                val localFile = File(downloaded.localFilePath)
                if (localFile.exists()) {
                    _activeStreamEngine.value = "Offline Storage"
                    startExoPlayerWithUri(song, Uri.fromFile(localFile).toString())
                    return@launch
                }
            }

            // 2. Native YouTube InnerTube (ANDROID_MUSIC) + Multi-Tier Fallback Audio Stream Extractor
            val extractionResult = audioExtractor.extractBestAudioStream(
                rawVideoId = song.videoId,
                songTitle = song.title,
                songArtist = song.artist
            )
            extractionResult.fold(
                onSuccess = { resolved ->
                    _activeStreamEngine.value = resolved.sourceEngine
                    startExoPlayerWithUri(song, resolved.streamUrl)
                },
                onFailure = { _ ->
                    _isBuffering.value = false
                    _isPlaying.value = false
                    emitSnackbar("Stream unavailable for this track.")
                }
            )
        }
    }

    private fun startExoPlayerWithUri(song: Song, streamUri: String) {
        val controller = mediaController
        if (controller == null) {
            pendingPlaybackPair = song to streamUri
            initializeMediaController()
            return
        }

        val metadata = MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.artist)
            .setArtworkUri(Uri.parse(song.highResBannerUrl))
            .build()

        val mediaItem = MediaItem.Builder()
            .setMediaId(song.id)
            .setUri(streamUri)
            .setMediaMetadata(metadata)
            .build()

        controller.setMediaItem(mediaItem)
        controller.playbackParameters = PlaybackParameters(_playbackSpeed.value)
        controller.prepare()
        controller.play()
        _isPlaying.value = true
    }

    fun togglePlayPause() {
        val controller = mediaController ?: return
        val activeSong = _currentSong.value ?: return
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
    }

    fun seekToPosition(positionMs: Long) {
        val controller = mediaController ?: return
        val maxDur = _durationMs.value.takeIf { it > 0L } ?: controller.duration.coerceAtLeast(1L)
        val clamped = positionMs.coerceIn(0L, maxDur)
        controller.seekTo(clamped)
        _currentPositionMs.value = clamped
    }

    fun seekRelativeSeconds(deltaSeconds: Int) {
        val controller = mediaController ?: return
        val currentPos = controller.currentPosition.coerceAtLeast(0L)
        val maxDur = _durationMs.value.takeIf { it > 0L } ?: controller.duration.coerceAtLeast(1L)
        val targetPos = (currentPos + deltaSeconds * 1000L).coerceIn(0L, maxDur)
        controller.seekTo(targetPos)
        _currentPositionMs.value = targetPos
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
        val controller = mediaController
        if (controller != null && controller.currentPosition > 4000L) {
            controller.seekTo(0L)
            _currentPositionMs.value = 0L
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
                mediaController?.play()
            }
            RepeatModeState.ALL -> {
                skipToNext()
            }
            RepeatModeState.OFF -> {
                val idx = list.indexOfFirst { it.id == current.id }
                if (idx in 0 until list.lastIndex || _isShuffleEnabled.value) {
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
            RepeatModeState.ALL -> "Repeat All"
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
                    emitSnackbar("Stream unavailable for this track.")
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
                mediaController?.pause()
                _isPlaying.value = false
                emitSnackbar("Sleep timer ended — playback paused")
            }
        }
    }

    fun refreshFirebaseCatalog() {
        viewModelScope.launch {
            firebaseService.fetchSongsViaRest(silent = false)
            emitSnackbar("Synced latest songs from Firebase")
        }
    }

    fun uploadSongToFirebase(
        title: String,
        artist: String,
        videoIdOrUrl: String,
        bannerUrl: String,
        onSuccess: () -> Unit
    ) {
        viewModelScope.launch {
            val result = firebaseService.uploadSongToFirebase(title, artist, videoIdOrUrl, bannerUrl)
            result.fold(
                onSuccess = { uploaded ->
                    emitSnackbar("Uploaded \"${uploaded.title}\" to Firebase /songs!")
                    onSuccess()
                },
                onFailure = { err ->
                    emitSnackbar("Upload failed: ${err.localizedMessage}")
                }
            )
        }
    }

    fun deleteSongFromFirebase(song: Song) {
        viewModelScope.launch {
            val result = firebaseService.deleteSongFromFirebase(song.id)
            result.fold(
                onSuccess = {
                    emitSnackbar("Removed \"${song.title}\" from Firebase")
                },
                onFailure = { err ->
                    emitSnackbar("Delete failed: ${err.localizedMessage}")
                }
            )
        }
    }

    fun emitSnackbar(message: String) {
        _snackbarMessage.tryEmit(message)
    }

    override fun onCleared() {
        super.onCleared()
        controllerFuture?.let { MediaController.releaseFuture(it) }
    }
}
