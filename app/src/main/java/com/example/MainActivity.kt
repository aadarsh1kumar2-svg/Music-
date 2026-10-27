package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Verified
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.providers.LibrarySubScreen
import com.example.providers.MainTab
import com.example.providers.MusicViewModel
import com.example.screens.DeveloperIntroScreen
import com.example.screens.ExploreScreen
import com.example.screens.FullScreenPlayerModal
import com.example.screens.HomeScreen
import com.example.screens.LibraryScreen
import com.example.screens.NameOnboardingScreen
import com.example.screens.SplashScreen
import com.example.screens.StartupSmartlinkAdScreen
import com.example.screens.TopChartsScreen
import com.example.services.BackgroundGamingAudioKeeper
import com.example.ui.theme.DeepCharcoal
import com.example.ui.theme.DividerDark
import com.example.ui.theme.MutedGray
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.PrimaryWhite
import com.example.ui.theme.PureBlack
import com.example.ui.theme.SpotifyDarkGreenPill
import com.example.ui.theme.SpotifyGreenBorder
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

private enum class LaunchStage {
    ENTER_NAME,
    LOADING,
    DEVELOPER_INTRO,
    STARTUP_AD,
    MAIN_APP
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            BackgroundGamingAudioKeeper.getInstance(this)
            BackgroundGamingAudioKeeper.ensureForegroundMediaServiceStarted(this)
        } catch (_: Exception) {
        }
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

    val audioPermissionName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        musicViewModel.onAudioStoragePermissionResult(granted)
        if (!granted) {
            musicViewModel.emitSnackbar("Storage permission is needed to show device songs in My Music.")
        }
    }

    val multiPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val audioGranted = results[audioPermissionName] == true || musicViewModel.checkAudioPermissionGranted()
        musicViewModel.onAudioStoragePermissionResult(audioGranted)
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val notifGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!notifGranted) {
                multiPermissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            }
        }
        if (musicViewModel.checkAudioPermissionGranted()) {
            musicViewModel.onAudioStoragePermissionResult(true)
        }
    }

    val userName by musicViewModel.userName.collectAsStateWithLifecycle()
    val isEditNameDialogOpen by musicViewModel.isEditNameDialogOpen.collectAsStateWithLifecycle()
    val isDeveloperScreenOpen by musicViewModel.isDeveloperScreenOpen.collectAsStateWithLifecycle()
    val hasAudioStoragePermission by musicViewModel.hasAudioStoragePermission.collectAsStateWithLifecycle()
    val deviceLocalSongs by musicViewModel.deviceLocalSongs.collectAsStateWithLifecycle()

    val selectedTab by musicViewModel.selectedTab.collectAsStateWithLifecycle()
    val librarySubScreen by musicViewModel.librarySubScreen.collectAsStateWithLifecycle()
    val allSongs by musicViewModel.allSongs.collectAsStateWithLifecycle()
    val exploreSongs by musicViewModel.exploreSongs.collectAsStateWithLifecycle()
    val chartSongs by musicViewModel.chartSongs.collectAsStateWithLifecycle()
    val homeSearchQuery by musicViewModel.homeSearchQuery.collectAsStateWithLifecycle()
    val exploreSearchQuery by musicViewModel.exploreSearchQuery.collectAsStateWithLifecycle()
    val isSearchingOnline by musicViewModel.isSearchingOnline.collectAsStateWithLifecycle()
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
    val playlistPickerSong by musicViewModel.playlistPickerSong.collectAsStateWithLifecycle()
    val isSleepTimerDialogOpen by musicViewModel.isSleepTimerDialogOpen.collectAsStateWithLifecycle()
    val sleepTimerRemainingSeconds by musicViewModel.sleepTimerRemainingSeconds.collectAsStateWithLifecycle()
    val isUpNextSheetOpen by musicViewModel.isUpNextSheetOpen.collectAsStateWithLifecycle()
    val isLyricsOverlayOpen by musicViewModel.isLyricsOverlayOpen.collectAsStateWithLifecycle()

    // Launch flow:
    // - New User (userName is blank): ENTER_NAME -> LOADING -> DEVELOPER_INTRO -> MAIN_APP
    // - Returning User (userName is already saved): LOADING -> DEVELOPER_INTRO -> MAIN_APP
    var launchStage by rememberSaveable {
        mutableStateOf(
            if (userName.isBlank()) LaunchStage.ENTER_NAME else LaunchStage.LOADING
        )
    }

    // If Admin deletes the user from the Admin Panel while the app is open, immediately return to Name Login screen
    LaunchedEffect(userName) {
        if (userName.isBlank() && launchStage != LaunchStage.ENTER_NAME) {
            launchStage = LaunchStage.ENTER_NAME
        }
    }

    LaunchedEffect(launchStage) {
        if (launchStage == LaunchStage.LOADING) {
            delay(1800L)
            launchStage = LaunchStage.DEVELOPER_INTRO
        }
    }

    LaunchedEffect(musicViewModel) {
        musicViewModel.snackbarMessage.collectLatest { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    if (launchStage == LaunchStage.MAIN_APP && selectedTab != MainTab.HOME && !isFullScreenPlayerOpen && !isEditNameDialogOpen) {
        BackHandler {
            musicViewModel.selectTab(MainTab.HOME)
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_RESUME) {
                musicViewModel.keepEngineAwakeInBackground()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PureBlack)
    ) {
        // Permanent Backstage YouTube Engine layer (never detached when opening/closing player modal!)
        Box(
            modifier = Modifier
                .width(240.dp)
                .height(240.dp)
        ) {
            AndroidView(
                factory = { ctx ->
                    FrameLayout(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        musicViewModel.attachBackstagePlayer(this)
                    }
                },
                update = { container ->
                    musicViewModel.attachBackstagePlayer(container)
                },
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(PureBlack)
            )
        }

        when {
            launchStage == LaunchStage.ENTER_NAME -> {
                NameOnboardingScreen(
                    initialName = userName,
                    onSubmitName = { entered ->
                        musicViewModel.saveUserName(entered)
                        launchStage = LaunchStage.LOADING
                    }
                )
            }
            launchStage == LaunchStage.LOADING -> {
                SplashScreen()
            }
            launchStage == LaunchStage.DEVELOPER_INTRO -> {
                DeveloperIntroScreen(
                    onContinue = {
                        launchStage = LaunchStage.STARTUP_AD
                    }
                )
            }
            launchStage == LaunchStage.STARTUP_AD -> {
                StartupSmartlinkAdScreen(
                    onCloseAdAndEnterApp = {
                        launchStage = LaunchStage.MAIN_APP
                    }
                )
            }
            isEditNameDialogOpen -> {
                NameOnboardingScreen(
                    initialName = userName,
                    onSubmitName = { entered ->
                        musicViewModel.saveUserName(entered)
                    }
                )
            }
            else -> {
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
                                Image(
                                    painter = painterResource(id = R.drawable.aadify_logo),
                                    contentDescription = "AADIFY Logo",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier
                                        .size(56.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "AADIFY",
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

                            // Developer Section in Left 3-Lines Drawer
                            NavigationDrawerItem(
                                label = { Text("Developer") },
                                icon = { Icon(Icons.Default.Code, contentDescription = "Developer") },
                                selected = isDeveloperScreenOpen,
                                onClick = {
                                    coroutineScope.launch { drawerState.close() }
                                    musicViewModel.setDeveloperScreenOpen(true)
                                },
                                colors = NavigationDrawerItemDefaults.colors(
                                    selectedContainerColor = SpotifyDarkGreenPill,
                                    selectedIconColor = SpotifyVibrantGreen,
                                    selectedTextColor = SpotifyVibrantGreen,
                                    unselectedIconColor = SpotifyVibrantGreen,
                                    unselectedTextColor = PrimaryWhite
                                ),
                                modifier = Modifier
                                    .padding(horizontal = 12.dp)
                                    .testTag("drawer_developer_item")
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
                                        songs = allSongs,
                                        recentSessionHistory = sessionHistory,
                                        searchQuery = homeSearchQuery,
                                        isLoading = isLoadingSongs,
                                        currentSong = currentSong,
                                        isPlaying = isPlaying,
                                        onSearchQueryChange = { musicViewModel.updateHomeSearchQuery(it) },
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
                                        songs = exploreSongs,
                                        searchQuery = exploreSearchQuery,
                                        isSearchingOnline = isSearchingOnline,
                                        selectedCountry = selectedCountry,
                                        currentSong = currentSong,
                                        isPlaying = isPlaying,
                                        onSearchQueryChange = { musicViewModel.updateExploreSearchQuery(it) },
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
                                        deviceLocalSongs = deviceLocalSongs,
                                        hasAudioStoragePermission = hasAudioStoragePermission,
                                        playlists = playlists,
                                        allPlaylistSongs = allPlaylistSongs,
                                        sessionHistory = sessionHistory,
                                        currentQueue = queue,
                                        currentSong = currentSong,
                                        isPlaying = isPlaying,
                                        repeatMode = repeatMode,
                                        onNavigateSubScreen = { musicViewModel.openLibrarySubScreen(it) },
                                        onSongClick = { song, list -> musicViewModel.playSong(song, list) },
                                        onSongMoreClick = { musicViewModel.openSongOptions(it) },
                                        onCreatePlaylist = { musicViewModel.createNewPlaylist(it) },
                                        onDeletePlaylist = { musicViewModel.deletePlaylist(it) },
                                        onDeleteDownloadedSong = { musicViewModel.deleteDownloadedSong(it) },
                                        onRequestStoragePermission = {
                                            if (musicViewModel.checkAudioPermissionGranted()) {
                                                musicViewModel.onAudioStoragePermissionResult(true)
                                            } else {
                                                audioPermissionLauncher.launch(audioPermissionName)
                                            }
                                        },
                                        onCycleRepeatMode = { musicViewModel.cycleRepeatMode() },
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
        }

        // Slide-Up Full-Screen Player Modal
        val activePlayerSong = currentSong
        AnimatedVisibility(
            visible = isFullScreenPlayerOpen && activePlayerSong != null && launchStage == LaunchStage.MAIN_APP,
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
                    onSelectQueueSong = { musicViewModel.playSong(it, queue) }
                )
            }
        }

        // Song Options Bottom Sheet (from song row or top-right 3-lines in player)
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

        // Developer Page Dialog / Modal
        if (isDeveloperScreenOpen) {
            DeveloperProfileDialog(
                onDismiss = { musicViewModel.setDeveloperScreenOpen(false) }
            )
        }
    }
}

@Composable
fun DeveloperProfileDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val typewriterFont = remember { FontFamily(Font(R.font.jetbrains_mono)) }
    val displayFont = remember { FontFamily(Font(R.font.space_grotesk)) }

    val fullDeveloperStory = remember {
        "> developer: Aadarsh Kumar (Part of HIS)\n" +
            "> app_engine: AADIFY v2.0 Pro\n\n" +
            "\"I crafted AADIFY with countless hours of hard work, late-night debugging, and pure passion for music lovers.\n\n" +
            "Every single system — from non-stop background playback while using other apps, real-time Cloud Top Charts, and Global Country Search to Offline Device Audio — was engineered by Aadarsh Kumar as a proud part of HIS.\""
    }

    var typedCharCount by remember { mutableIntStateOf(0) }
    var cursorVisible by remember { mutableStateOf(true) }
    var replayTrigger by remember { mutableIntStateOf(0) }

    LaunchedEffect(replayTrigger) {
        typedCharCount = 0
        val totalLength = fullDeveloperStory.length
        while (typedCharCount < totalLength) {
            delay(18L)
            typedCharCount++
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(480L)
            cursorVisible = !cursorVisible
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = DeepCharcoal,
            shape = RoundedCornerShape(26.dp),
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .border(
                    width = 1.5.dp,
                    color = SpotifyGreenBorder,
                    shape = RoundedCornerShape(26.dp)
                )
                .testTag("developer_profile_dialog")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Top Bar with Terminal Dots + Title + Close Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFFF5F56))
                        )
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFFFBD2E))
                        )
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(SpotifyVibrantGreen)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "DEVELOPER STUDIO",
                            style = MaterialTheme.typography.labelLarge.copy(
                                color = SpotifyVibrantGreen,
                                fontFamily = typewriterFont,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp,
                                fontSize = 12.sp
                            )
                        )
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = MutedGray
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Logo + Verified Creator Badge
                Box(contentAlignment = Alignment.BottomEnd) {
                    Image(
                        painter = painterResource(id = R.drawable.aadify_logo),
                        contentDescription = "AADIFY Logo",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .size(86.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .border(
                                width = 1.5.dp,
                                color = SpotifyVibrantGreen,
                                shape = RoundedCornerShape(20.dp)
                            )
                    )
                    Surface(
                        color = PureBlack,
                        shape = CircleShape,
                        modifier = Modifier.size(26.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Verified,
                            contentDescription = "Verified Creator",
                            tint = SpotifyVibrantGreen,
                            modifier = Modifier
                                .padding(2.dp)
                                .fillMaxSize()
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Surface(
                    color = SpotifyDarkGreenPill,
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.border(
                        width = 1.dp,
                        color = SpotifyGreenBorder,
                        shape = RoundedCornerShape(50)
                    )
                ) {
                    Text(
                        text = "FOUNDER & LEAD ARCHITECT • PART OF HIS",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = SpotifyVibrantGreen,
                            fontFamily = typewriterFont,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            letterSpacing = 0.8.sp
                        ),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Aadarsh Kumar",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        color = PrimaryWhite,
                        fontFamily = displayFont,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 26.sp
                    ),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Live Typewriter Story Terminal Box
                Surface(
                    color = PureBlack,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            width = 1.dp,
                            color = SpotifyGreenBorder.copy(alpha = 0.7f),
                            shape = RoundedCornerShape(16.dp)
                        )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "about_aadarsh.sh",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = SpotifyVibrantGreen,
                                    fontFamily = typewriterFont,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp
                                )
                            )
                            IconButton(
                                onClick = { replayTrigger++ },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Replay,
                                    contentDescription = "Replay Typing",
                                    tint = SpotifyVibrantGreen,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        HorizontalDivider(
                            color = DividerDark,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )

                        val visibleText = fullDeveloperStory.take(typedCharCount)
                        val cursorChar = if (cursorVisible) " ▋" else "  "

                        Text(
                            text = visibleText + cursorChar,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = PrimaryWhite,
                                fontFamily = typewriterFont,
                                fontSize = 12.8.sp,
                                lineHeight = 20.sp
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("developer_typewriter_text")
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Contact Card
                Surface(
                    color = SurfaceDarkCard,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Contact to Developer",
                            style = MaterialTheme.typography.titleMedium.copy(
                                color = PrimaryWhite,
                                fontFamily = displayFont,
                                fontWeight = FontWeight.Bold,
                                fontSize = 17.sp
                            )
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Telegram: @Devacoding1",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = SpotifyVibrantGreen,
                                fontFamily = typewriterFont,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.5.sp
                            )
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Button(
                            onClick = {
                                try {
                                    val tgIntent = Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse("https://t.me/Devacoding1")
                                    ).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(tgIntent)
                                } catch (_: Exception) {
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = SpotifyVibrantGreen,
                                contentColor = PureBlack
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("developer_telegram_go_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Go",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    color = PureBlack,
                                    fontFamily = displayFont,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 17.sp
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}
