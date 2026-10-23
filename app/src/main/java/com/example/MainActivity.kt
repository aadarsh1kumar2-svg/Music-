package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.providers.LibrarySubScreen
import com.example.providers.MainTab
import com.example.providers.MusicViewModel
import com.example.screens.ExploreScreen
import com.example.screens.FullScreenPlayerModal
import com.example.screens.HomeScreen
import com.example.screens.LibraryScreen
import com.example.screens.NameOnboardingScreen
import com.example.screens.SplashScreen
import com.example.screens.TopChartsScreen
import com.example.ui.theme.DeepCharcoal
import com.example.ui.theme.DividerDark
import com.example.ui.theme.MutedGray
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.PrimaryWhite
import com.example.ui.theme.PureBlack
import com.example.ui.theme.SpotifyDarkGreenPill
import com.example.ui.theme.SpotifyVibrantGreen
import com.example.ui.theme.SurfaceDarkCard
import com.example.widgets.AddToPlaylistDialog
import com.example.widgets.BottomNavBar
import com.example.widgets.PersistentMiniPlayer
import com.example.widgets.SleepTimerDialog
import com.example.widgets.SongOptionsBottomSheet
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                AKMusicAppRoot()
            }
        }
    }
}

@Composable
fun AKMusicAppRoot(
    musicViewModel: MusicViewModel = viewModel()
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val snackbarHostState = remember { SnackbarHostState() }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    val userName by musicViewModel.userName.collectAsStateWithLifecycle()
    val isEditNameDialogOpen by musicViewModel.isEditNameDialogOpen.collectAsStateWithLifecycle()
    val selectedTab by musicViewModel.selectedTab.collectAsStateWithLifecycle()
    val librarySubScreen by musicViewModel.librarySubScreen.collectAsStateWithLifecycle()
    val allSongs by musicViewModel.allSongs.collectAsStateWithLifecycle()
    val filteredSongs by musicViewModel.filteredSongs.collectAsStateWithLifecycle()
    val chartSongs by musicViewModel.chartSongs.collectAsStateWithLifecycle()
    val searchQuery by musicViewModel.searchQuery.collectAsStateWithLifecycle()
    val isLoadingSongs by musicViewModel.isLoadingSongs.collectAsStateWithLifecycle()
    val selectedChartTab by musicViewModel.selectedChartTab.collectAsStateWithLifecycle()
    val selectedCountry by musicViewModel.selectedCountry.collectAsStateWithLifecycle()

    val favorites by musicViewModel.favoriteEntities.collectAsStateWithLifecycle()
    val sessionHistory by musicViewModel.sessionHistoryEntities.collectAsStateWithLifecycle()
    val playlists by musicViewModel.playlists.collectAsStateWithLifecycle()
    val allPlaylistSongs by musicViewModel.allPlaylistSongs.collectAsStateWithLifecycle()
    val downloadedSongs by musicViewModel.downloadedSongs.collectAsStateWithLifecycle()

    val currentSong by musicViewModel.currentSong.collectAsStateWithLifecycle()
    val queue by musicViewModel.queue.collectAsStateWithLifecycle()
    val isPlaying by musicViewModel.isPlaying.collectAsStateWithLifecycle()
    val isBuffering by musicViewModel.isBuffering.collectAsStateWithLifecycle()
    val currentPositionMs by musicViewModel.currentPositionMs.collectAsStateWithLifecycle()
    val durationMs by musicViewModel.durationMs.collectAsStateWithLifecycle()
    val playbackSpeed by musicViewModel.playbackSpeed.collectAsStateWithLifecycle()
    val isShuffleEnabled by musicViewModel.isShuffleEnabled.collectAsStateWithLifecycle()
    val repeatMode by musicViewModel.repeatMode.collectAsStateWithLifecycle()

    val isFullScreenPlayerOpen by musicViewModel.isFullScreenPlayerOpen.collectAsStateWithLifecycle()
    val optionsSheetSong by musicViewModel.optionsSheetSong.collectAsStateWithLifecycle()
    val isAdminUploadSheetOpen by musicViewModel.isAdminUploadSheetOpen.collectAsStateWithLifecycle()
    val playlistPickerSong by musicViewModel.playlistPickerSong.collectAsStateWithLifecycle()
    val isSleepTimerDialogOpen by musicViewModel.isSleepTimerDialogOpen.collectAsStateWithLifecycle()
    val sleepTimerRemainingSeconds by musicViewModel.sleepTimerRemainingSeconds.collectAsStateWithLifecycle()
    val isUpNextSheetOpen by musicViewModel.isUpNextSheetOpen.collectAsStateWithLifecycle()
    val isLyricsOverlayOpen by musicViewModel.isLyricsOverlayOpen.collectAsStateWithLifecycle()

    var showSplash by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(1700L)
        showSplash = false
    }

    LaunchedEffect(musicViewModel) {
        musicViewModel.snackbarMessage.collectLatest { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    if (selectedTab != MainTab.HOME && !isFullScreenPlayerOpen && userName.isNotBlank()) {
        BackHandler {
            musicViewModel.selectTab(MainTab.HOME)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PureBlack)
    ) {
        if (!showSplash && (userName.isBlank() || isEditNameDialogOpen)) {
            NameOnboardingScreen(
                initialName = userName,
                onSubmitName = { entered ->
                    musicViewModel.saveUserName(entered)
                }
            )
        } else {
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    ModalDrawerSheet(
                        drawerContainerColor = DeepCharcoal,
                        modifier = Modifier.width(300.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.MusicNote,
                                contentDescription = null,
                                tint = SpotifyVibrantGreen,
                                modifier = Modifier.height(40.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "AK Music 🎵",
                                style = MaterialTheme.typography.headlineMedium.copy(
                                    color = PrimaryWhite,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Hi There, $userName",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = SpotifyVibrantGreen,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp
                                )
                            )
                            Text(
                                text = "Your Music Universe",
                                style = MaterialTheme.typography.labelSmall.copy(color = MutedGray)
                            )
                        }

                        HorizontalDivider(color = DividerDark)
                        Spacer(modifier = Modifier.height(8.dp))

                        NavigationDrawerItem(
                            label = { Text("Home") },
                            icon = { Icon(Icons.Default.Home, contentDescription = null) },
                            selected = selectedTab == MainTab.HOME,
                            onClick = {
                                musicViewModel.selectTab(MainTab.HOME)
                                coroutineScope.launch { drawerState.close() }
                            },
                            colors = NavigationDrawerItemDefaults.colors(
                                selectedContainerColor = SpotifyDarkGreenPill,
                                selectedIconColor = SpotifyVibrantGreen,
                                selectedTextColor = SpotifyVibrantGreen,
                                unselectedIconColor = MutedGray,
                                unselectedTextColor = PrimaryWhite
                            ),
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )

                        NavigationDrawerItem(
                            label = { Text("Top Charts") },
                            icon = { Icon(Icons.AutoMirrored.Filled.TrendingUp, contentDescription = null) },
                            selected = selectedTab == MainTab.TOP_CHARTS,
                            onClick = {
                                musicViewModel.selectTab(MainTab.TOP_CHARTS)
                                coroutineScope.launch { drawerState.close() }
                            },
                            colors = NavigationDrawerItemDefaults.colors(
                                selectedContainerColor = SpotifyDarkGreenPill,
                                selectedIconColor = SpotifyVibrantGreen,
                                selectedTextColor = SpotifyVibrantGreen,
                                unselectedIconColor = MutedGray,
                                unselectedTextColor = PrimaryWhite
                            ),
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )

                        NavigationDrawerItem(
                            label = { Text("Explore") },
                            icon = { Icon(Icons.Default.Explore, contentDescription = null) },
                            selected = selectedTab == MainTab.EXPLORE,
                            onClick = {
                                musicViewModel.selectTab(MainTab.EXPLORE)
                                coroutineScope.launch { drawerState.close() }
                            },
                            colors = NavigationDrawerItemDefaults.colors(
                                selectedContainerColor = SpotifyDarkGreenPill,
                                selectedIconColor = SpotifyVibrantGreen,
                                selectedTextColor = SpotifyVibrantGreen,
                                unselectedIconColor = MutedGray,
                                unselectedTextColor = PrimaryWhite
                            ),
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )

                        NavigationDrawerItem(
                            label = { Text("Library & Favorites") },
                            icon = { Icon(Icons.Default.LibraryMusic, contentDescription = null) },
                            selected = selectedTab == MainTab.LIBRARY,
                            onClick = {
                                musicViewModel.selectTab(MainTab.LIBRARY)
                                coroutineScope.launch { drawerState.close() }
                            },
                            colors = NavigationDrawerItemDefaults.colors(
                                selectedContainerColor = SpotifyDarkGreenPill,
                                selectedIconColor = SpotifyVibrantGreen,
                                selectedTextColor = SpotifyVibrantGreen,
                                unselectedIconColor = MutedGray,
                                unselectedTextColor = PrimaryWhite
                            ),
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )

                        HorizontalDivider(
                            color = DividerDark,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )

                        NavigationDrawerItem(
                            label = { Text("Change User Name ($userName)") },
                            icon = { Icon(Icons.Default.Person, contentDescription = null) },
                            selected = false,
                            onClick = {
                                coroutineScope.launch { drawerState.close() }
                                musicViewModel.setEditNameDialogOpen(true)
                            },
                            colors = NavigationDrawerItemDefaults.colors(
                                unselectedIconColor = MutedGray,
                                unselectedTextColor = PrimaryWhite
                            ),
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )

                        NavigationDrawerItem(
                            label = { Text("Sleep Timer") },
                            icon = { Icon(Icons.Default.Timer, contentDescription = null) },
                            selected = sleepTimerRemainingSeconds != null,
                            onClick = {
                                coroutineScope.launch { drawerState.close() }
                                musicViewModel.setSleepTimerDialogOpen(true)
                            },
                            colors = NavigationDrawerItemDefaults.colors(
                                selectedContainerColor = SpotifyDarkGreenPill,
                                selectedIconColor = SpotifyVibrantGreen,
                                selectedTextColor = SpotifyVibrantGreen,
                                unselectedIconColor = MutedGray,
                                unselectedTextColor = PrimaryWhite
                            ),
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                    }
                }
            ) {
                Scaffold(
                    containerColor = PureBlack,
                    contentWindowInsets = WindowInsets.safeDrawing,
                    snackbarHost = {
                        SnackbarHost(hostState = snackbarHostState) { data ->
                            Snackbar(
                                snackbarData = data,
                                containerColor = SurfaceDarkCard,
                                contentColor = PrimaryWhite,
                                actionColor = SpotifyVibrantGreen,
                                shape = RoundedCornerShape(12.dp)
                            )
                        }
                    },
                    bottomBar = {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(PureBlack)
                        ) {
                            val activeSong = currentSong
                            if (activeSong != null) {
                                val isFav = favorites.any { it.id == activeSong.id }
                                PersistentMiniPlayer(
                                    song = activeSong,
                                    isPlaying = isPlaying,
                                    isBuffering = isBuffering,
                                    isFavorite = isFav,
                                    currentPositionMs = currentPositionMs,
                                    durationMs = durationMs,
                                    onExpandPlayer = { musicViewModel.openFullScreenPlayer() },
                                    onToggleFavorite = { musicViewModel.toggleFavorite(activeSong) },
                                    onTogglePlayPause = { musicViewModel.togglePlayPause() },
                                    onSkipNext = { musicViewModel.skipToNext() }
                                )
                            }

                            BottomNavBar(
                                selectedTab = selectedTab,
                                onSelectTab = { musicViewModel.selectTab(it) }
                            )
                        }
                    }
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = innerPadding.calculateTopPadding())
                    ) {
                        when (selectedTab) {
                            MainTab.HOME -> {
                                HomeScreen(
                                    userName = userName,
                                    songs = filteredSongs,
                                    recentSessionHistory = sessionHistory,
                                    searchQuery = searchQuery,
                                    isLoading = isLoadingSongs,
                                    currentSong = currentSong,
                                    isPlaying = isPlaying,
                                    onSearchQueryChange = { musicViewModel.updateSearchQuery(it) },
                                    onSongClick = { song, list -> musicViewModel.playSong(song, list) },
                                    onSongMoreClick = { musicViewModel.openSongOptions(it) },
                                    onOpenDrawerMenu = {
                                        coroutineScope.launch { drawerState.open() }
                                    },
                                    onRefreshCatalog = {
                                        musicViewModel.refreshFirebaseCatalog()
                                    },
                                    onEditUserName = {
                                        musicViewModel.setEditNameDialogOpen(true)
                                    }
                                )
                            }
                            MainTab.TOP_CHARTS -> {
                                TopChartsScreen(
                                    chartSongs = chartSongs,
                                    selectedChartTab = selectedChartTab,
                                    currentSong = currentSong,
                                    isPlaying = isPlaying,
                                    onSelectChartTab = { musicViewModel.selectChartTab(it) },
                                    onSongClick = { song, list -> musicViewModel.playSong(song, list) },
                                    onSongMoreClick = { musicViewModel.openSongOptions(it) },
                                    onOpenDrawerMenu = {
                                        coroutineScope.launch { drawerState.open() }
                                    },
                                    onRefreshCatalog = {
                                        musicViewModel.refreshFirebaseCatalog()
                                    }
                                )
                            }
                            MainTab.EXPLORE -> {
                                ExploreScreen(
                                    songs = filteredSongs,
                                    searchQuery = searchQuery,
                                    selectedCountry = selectedCountry,
                                    currentSong = currentSong,
                                    isPlaying = isPlaying,
                                    onSearchQueryChange = { musicViewModel.updateSearchQuery(it) },
                                    onSelectCountry = { musicViewModel.selectCountry(it) },
                                    onSongClick = { song, list -> musicViewModel.playSong(song, list) },
                                    onSongMoreClick = { musicViewModel.openSongOptions(it) },
                                    onOpenDrawerMenu = {
                                        coroutineScope.launch { drawerState.open() }
                                    }
                                )
                            }
                            MainTab.LIBRARY -> {
                                LibraryScreen(
                                    subScreen = librarySubScreen,
                                    allSongs = allSongs,
                                    favorites = favorites,
                                    downloadedSongs = downloadedSongs,
                                    playlists = playlists,
                                    allPlaylistSongs = allPlaylistSongs,
                                    sessionHistory = sessionHistory,
                                    currentQueue = queue,
                                    currentSong = currentSong,
                                    isPlaying = isPlaying,
                                    onNavigateSubScreen = { musicViewModel.openLibrarySubScreen(it) },
                                    onSongClick = { song, list -> musicViewModel.playSong(song, list) },
                                    onSongMoreClick = { musicViewModel.openSongOptions(it) },
                                    onCreatePlaylist = { musicViewModel.createNewPlaylist(it) },
                                    onDeletePlaylist = { musicViewModel.deletePlaylist(it) },
                                    onDeleteDownloadedSong = { musicViewModel.deleteDownloadedSong(it) },
                                    onOpenDrawerMenu = {
                                        coroutineScope.launch { drawerState.open() }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        // Keep Backstage YouTube Engine attached when Full-Screen Player is collapsed
        if (!isFullScreenPlayerOpen) {
            AndroidView(
                factory = { ctx ->
                    FrameLayout(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(1, 1)
                        musicViewModel.attachBackstagePlayer(this)
                    }
                },
                update = { container ->
                    musicViewModel.attachBackstagePlayer(container)
                },
                modifier = Modifier
                    .width(1.dp)
                    .height(1.dp)
                    .alpha(0.01f)
            )
        }

        // Slide-Up Full-Screen Player Modal
        val activePlayerSong = currentSong
        AnimatedVisibility(
            visible = isFullScreenPlayerOpen && activePlayerSong != null,
            enter = slideInVertically(
                initialOffsetY = { it },
                animationSpec = tween(durationMillis = 320)
            ) + fadeIn(animationSpec = tween(250)),
            exit = slideOutVertically(
                targetOffsetY = { it },
                animationSpec = tween(durationMillis = 280)
            ) + fadeOut(animationSpec = tween(200))
        ) {
            if (activePlayerSong != null) {
                val isFav = favorites.any { it.id == activePlayerSong.id }
                FullScreenPlayerModal(
                    song = activePlayerSong,
                    queue = queue,
                    isPlaying = isPlaying,
                    isBuffering = isBuffering,
                    isFavorite = isFav,
                    currentPositionMs = currentPositionMs,
                    durationMs = durationMs,
                    playbackSpeed = playbackSpeed,
                    isShuffleEnabled = isShuffleEnabled,
                    repeatMode = repeatMode,
                    isUpNextOpen = isUpNextSheetOpen,
                    isLyricsOpen = isLyricsOverlayOpen,
                    onCollapse = { musicViewModel.closeFullScreenPlayer() },
                    onTogglePlayPause = { musicViewModel.togglePlayPause() },
                    onSkipPrevious = { musicViewModel.skipToPrevious() },
                    onSkipNext = { musicViewModel.skipToNext() },
                    onSeekTo = { musicViewModel.seekToPosition(it) },
                    onSeekRelativeSeconds = { musicViewModel.seekRelativeSeconds(it) },
                    onCycleSpeed = { musicViewModel.cyclePlaybackSpeed() },
                    onToggleShuffle = { musicViewModel.toggleShuffle() },
                    onCycleRepeat = { musicViewModel.cycleRepeatMode() },
                    onToggleFavorite = { musicViewModel.toggleFavorite(activePlayerSong) },
                    onOpenSongOptions = { musicViewModel.openSongOptions(it) },
                    onSetUpNextOpen = { musicViewModel.setUpNextSheetOpen(it) },
                    onSetLyricsOpen = { musicViewModel.setLyricsOverlayOpen(it) },
                    onSelectQueueSong = { musicViewModel.playSong(it, queue) },
                    onAttachBackstagePlayer = { container ->
                        musicViewModel.attachBackstagePlayer(container)
                    }
                )
            }
        }

        // 3-Dot Song Options Bottom Sheet
        val sheetSong = optionsSheetSong
        if (sheetSong != null) {
            SongOptionsBottomSheet(
                song = sheetSong,
                sleepTimerRemainingSeconds = sleepTimerRemainingSeconds,
                onDismiss = { musicViewModel.closeSongOptions() },
                onViewAlbum = { song ->
                    musicViewModel.closeFullScreenPlayer()
                    musicViewModel.openLibrarySubScreen(LibrarySubScreen.ArtistAlbumView(song.artist))
                },
                onAddToPlaylist = { song ->
                    musicViewModel.openPlaylistPicker(song)
                },
                onOpenSleepTimer = {
                    musicViewModel.setSleepTimerDialogOpen(true)
                },
                onDownloadOffline = { song ->
                    musicViewModel.downloadSongForOffline(song)
                }
            )
        }

        // Add to Playlist Dialog
        val targetPlaylistSong = playlistPickerSong
        if (targetPlaylistSong != null) {
            AddToPlaylistDialog(
                song = targetPlaylistSong,
                playlists = playlists,
                onCreateAndAdd = { name, song ->
                    musicViewModel.createNewPlaylist(name, song)
                },
                onAddToExisting = { playlist, song ->
                    musicViewModel.addSongToExistingPlaylist(playlist, song)
                },
                onDismiss = { musicViewModel.closePlaylistPicker() }
            )
        }

        // Sleep Timer Dialog
        if (isSleepTimerDialogOpen) {
            SleepTimerDialog(
                remainingSeconds = sleepTimerRemainingSeconds,
                onSelectMinutes = { mins ->
                    musicViewModel.startSleepTimer(mins)
                },
                onDismiss = { musicViewModel.setSleepTimerDialogOpen(false) }
            )
        }

        // Animated Neon Splash Screen Overlay on Launch
        AnimatedVisibility(
            visible = showSplash,
            enter = fadeIn(),
            exit = fadeOut(animationSpec = tween(durationMillis = 450))
        ) {
            SplashScreen()
        }
    }
}
