package com.example.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.models.DownloadedSongEntity
import com.example.models.FavoriteSongEntity
import com.example.models.PlaylistEntity
import com.example.models.PlaylistSongEntity
import com.example.models.SessionHistoryEntity
import com.example.models.Song
import com.example.providers.LibrarySubScreen
import com.example.providers.RepeatModeState
import com.example.ui.theme.DeepCharcoal
import com.example.ui.theme.DividerDark
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.MutedGray
import com.example.ui.theme.PrimaryWhite
import com.example.ui.theme.PureBlack
import com.example.ui.theme.SpotifyDarkGreenPill
import com.example.ui.theme.SpotifyGreenBorder
import com.example.ui.theme.SpotifyVibrantGreen
import com.example.ui.theme.SurfaceDarkCard
import com.example.widgets.SongListRow

@Composable
fun LibraryScreen(
    subScreen: LibrarySubScreen,
    allSongs: List<Song>,
    favorites: List<FavoriteSongEntity>,
    downloadedSongs: List<DownloadedSongEntity>,
    deviceLocalSongs: List<Song> = emptyList(),
    hasAudioStoragePermission: Boolean = false,
    playlists: List<PlaylistEntity>,
    allPlaylistSongs: List<PlaylistSongEntity>,
    sessionHistory: List<SessionHistoryEntity>,
    currentQueue: List<Song>,
    currentSong: Song?,
    isPlaying: Boolean,
    repeatMode: RepeatModeState = RepeatModeState.OFF,
    onNavigateSubScreen: (LibrarySubScreen) -> Unit,
    onSongClick: (Song, List<Song>) -> Unit,
    onSongMoreClick: (Song) -> Unit,
    onCreatePlaylist: (String) -> Unit,
    onDeletePlaylist: (PlaylistEntity) -> Unit,
    onDeleteDownloadedSong: (DownloadedSongEntity) -> Unit,
    onRequestStoragePermission: () -> Unit = {},
    onCycleRepeatMode: () -> Unit = {},
    onOpenDrawerMenu: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (subScreen != LibrarySubScreen.Root) {
        BackHandler {
            if (subScreen is LibrarySubScreen.PlaylistDetail) {
                onNavigateSubScreen(LibrarySubScreen.Playlists)
            } else {
                onNavigateSubScreen(LibrarySubScreen.Root)
            }
        }
    }

    val totalMyMusicCount = deviceLocalSongs.size + downloadedSongs.size

    when (subScreen) {
        is LibrarySubScreen.Root -> {
            LibraryRootContent(
                favoritesCount = favorites.size,
                downloadsCount = totalMyMusicCount,
                playlistsCount = playlists.size,
                sessionCount = sessionHistory.size,
                queueCount = currentQueue.size,
                onNavigateSubScreen = onNavigateSubScreen,
                onOpenDrawerMenu = onOpenDrawerMenu,
                modifier = modifier
            )
        }
        is LibrarySubScreen.Favorites -> {
            val favSongs = favorites.map { it.toSong() }
            LibrarySongCollectionView(
                title = stringResource(id = R.string.library_favorites),
                emptyMessage = "Tap the heart icon on any song to save it to your Favorites.",
                songs = favSongs,
                currentSong = currentSong,
                isPlaying = isPlaying,
                repeatMode = repeatMode,
                showPlaylistControls = favSongs.isNotEmpty(),
                onCycleRepeatMode = onCycleRepeatMode,
                onBack = { onNavigateSubScreen(LibrarySubScreen.Root) },
                onSongClick = onSongClick,
                onSongMoreClick = onSongMoreClick,
                modifier = modifier
            )
        }
        is LibrarySubScreen.MyMusic -> {
            LibraryMyMusicView(
                hasAudioStoragePermission = hasAudioStoragePermission,
                deviceLocalSongs = deviceLocalSongs,
                downloadedSongs = downloadedSongs,
                currentSong = currentSong,
                isPlaying = isPlaying,
                onBack = { onNavigateSubScreen(LibrarySubScreen.Root) },
                onSongClick = onSongClick,
                onSongMoreClick = onSongMoreClick,
                onDeleteDownloadedSong = onDeleteDownloadedSong,
                onRequestStoragePermission = onRequestStoragePermission,
                modifier = modifier
            )
        }
        is LibrarySubScreen.Playlists -> {
            LibraryPlaylistsListView(
                playlists = playlists,
                allPlaylistSongs = allPlaylistSongs,
                onBack = { onNavigateSubScreen(LibrarySubScreen.Root) },
                onOpenPlaylist = { onNavigateSubScreen(LibrarySubScreen.PlaylistDetail(it)) },
                onCreatePlaylist = onCreatePlaylist,
                onDeletePlaylist = onDeletePlaylist,
                modifier = modifier
            )
        }
        is LibrarySubScreen.PlaylistDetail -> {
            val playlistSongs = allPlaylistSongs
                .filter { it.playlistId == subScreen.playlist.playlistId }
                .sortedBy { it.addedAt }
                .map { it.toSong() }
            LibrarySongCollectionView(
                title = subScreen.playlist.name,
                emptyMessage = "Open any song player and tap the top-right 3-lines menu -> \"Add to Playlist\" to add songs here.",
                songs = playlistSongs,
                currentSong = currentSong,
                isPlaying = isPlaying,
                repeatMode = repeatMode,
                showPlaylistControls = true,
                onCycleRepeatMode = onCycleRepeatMode,
                onBack = { onNavigateSubScreen(LibrarySubScreen.Playlists) },
                onSongClick = onSongClick,
                onSongMoreClick = onSongMoreClick,
                modifier = modifier
            )
        }
        is LibrarySubScreen.NowPlaying -> {
            LibrarySongCollectionView(
                title = stringResource(id = R.string.library_now_playing),
                emptyMessage = "Play any song to populate your active Now Playing queue.",
                songs = currentQueue,
                currentSong = currentSong,
                isPlaying = isPlaying,
                repeatMode = repeatMode,
                showPlaylistControls = currentQueue.isNotEmpty(),
                onCycleRepeatMode = onCycleRepeatMode,
                onBack = { onNavigateSubScreen(LibrarySubScreen.Root) },
                onSongClick = onSongClick,
                onSongMoreClick = onSongMoreClick,
                modifier = modifier
            )
        }
        is LibrarySubScreen.LastSession -> {
            val historySongs = sessionHistory.map { it.toSong() }
            LibrarySongCollectionView(
                title = stringResource(id = R.string.last_session),
                emptyMessage = "Songs you play will appear here in your Last Session history.",
                songs = historySongs,
                currentSong = currentSong,
                isPlaying = isPlaying,
                onBack = { onNavigateSubScreen(LibrarySubScreen.Root) },
                onSongClick = onSongClick,
                onSongMoreClick = onSongMoreClick,
                modifier = modifier
            )
        }
        is LibrarySubScreen.ArtistAlbumView -> {
            val artistSongs = allSongs.filter {
                it.artist.contains(subScreen.artistName, ignoreCase = true)
            }
            LibrarySongCollectionView(
                title = subScreen.artistName,
                emptyMessage = "No other songs found for ${subScreen.artistName}.",
                songs = artistSongs,
                currentSong = currentSong,
                isPlaying = isPlaying,
                onBack = { onNavigateSubScreen(LibrarySubScreen.Root) },
                onSongClick = onSongClick,
                onSongMoreClick = onSongMoreClick,
                modifier = modifier
            )
        }
    }
}

@Composable
private fun LibraryRootContent(
    favoritesCount: Int,
    downloadsCount: Int,
    playlistsCount: Int,
    sessionCount: Int,
    queueCount: Int,
    onNavigateSubScreen: (LibrarySubScreen) -> Unit,
    onOpenDrawerMenu: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(PureBlack)
            .testTag("library_screen"),
        contentPadding = PaddingValues(bottom = 140.dp)
    ) {
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 14.dp)
            ) {
                IconButton(
                    onClick = onOpenDrawerMenu,
                    modifier = Modifier
                        .size(48.dp)
                        .align(Alignment.CenterStart)
                ) {
                    Icon(
                        imageVector = Icons.Default.Menu,
                        contentDescription = "Menu",
                        tint = PrimaryWhite,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Text(
                    text = stringResource(id = R.string.nav_library),
                    style = MaterialTheme.typography.headlineMedium.copy(
                        color = PrimaryWhite,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 22.sp
                    ),
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }

        // Primary Group Card: Favorites, My Music (Device Songs + Offline Downloads), Playlists
        item {
            Surface(
                color = DeepCharcoal,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    LibraryMenuCardRow(
                        icon = Icons.Default.Favorite,
                        title = stringResource(id = R.string.library_favorites),
                        countBadge = favoritesCount,
                        testTag = "library_row_favorites",
                        onClick = { onNavigateSubScreen(LibrarySubScreen.Favorites) }
                    )
                    HorizontalDivider(
                        color = DividerDark,
                        modifier = Modifier.padding(start = 88.dp, end = 20.dp)
                    )
                    LibraryMenuCardRow(
                        icon = Icons.Default.FolderSpecial,
                        title = stringResource(id = R.string.library_my_music),
                        countBadge = downloadsCount,
                        testTag = "library_row_my_music",
                        onClick = { onNavigateSubScreen(LibrarySubScreen.MyMusic) }
                    )
                    HorizontalDivider(
                        color = DividerDark,
                        modifier = Modifier.padding(start = 88.dp, end = 20.dp)
                    )
                    LibraryMenuCardRow(
                        icon = Icons.AutoMirrored.Filled.PlaylistPlay,
                        title = stringResource(id = R.string.library_playlists),
                        countBadge = playlistsCount,
                        testTag = "library_row_playlists",
                        onClick = { onNavigateSubScreen(LibrarySubScreen.Playlists) }
                    )
                }
            }
        }

        // Secondary Group Card: Now Playing Queue & Last Session History
        item {
            Surface(
                color = DeepCharcoal,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    LibraryMenuCardRow(
                        icon = Icons.AutoMirrored.Filled.QueueMusic,
                        title = stringResource(id = R.string.library_now_playing),
                        countBadge = queueCount,
                        testTag = "library_row_now_playing",
                        onClick = { onNavigateSubScreen(LibrarySubScreen.NowPlaying) }
                    )
                    HorizontalDivider(
                        color = DividerDark,
                        modifier = Modifier.padding(start = 88.dp, end = 20.dp)
                    )
                    LibraryMenuCardRow(
                        icon = Icons.Default.History,
                        title = stringResource(id = R.string.last_session),
                        countBadge = sessionCount,
                        testTag = "library_row_last_session",
                        onClick = { onNavigateSubScreen(LibrarySubScreen.LastSession) }
                    )
                }
            }
        }

        // Bottom Footer when scrolled to the end of Library
        item {
            LibraryCreatorFooter()
        }
    }
}

@Composable
private fun LibraryCreatorFooter() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 28.dp, bottom = 16.dp)
            .testTag("library_creator_footer"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Created by Aadarsh Kumar",
            style = MaterialTheme.typography.labelSmall.copy(
                color = MutedGray.copy(alpha = 0.72f),
                fontWeight = FontWeight.Medium,
                fontSize = 11.5.sp,
                letterSpacing = 0.5.sp
            ),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun LibraryMenuCardRow(
    icon: ImageVector,
    title: String,
    countBadge: Int,
    testTag: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 18.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(SurfaceDarkCard),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = PrimaryWhite,
                modifier = Modifier.size(26.dp)
            )
        }

        Spacer(modifier = Modifier.width(18.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge.copy(
                color = PrimaryWhite,
                fontWeight = FontWeight.Medium,
                fontSize = 19.sp
            ),
            modifier = Modifier.weight(1f)
        )

        if (countBadge > 0) {
            Text(
                text = countBadge.toString(),
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = MutedGray,
                    fontSize = 14.sp
                ),
                modifier = Modifier.padding(end = 8.dp)
            )
        }

        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MutedGray,
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
private fun LibrarySongCollectionView(
    title: String,
    emptyMessage: String,
    songs: List<Song>,
    currentSong: Song?,
    isPlaying: Boolean,
    repeatMode: RepeatModeState = RepeatModeState.OFF,
    showPlaylistControls: Boolean = false,
    onCycleRepeatMode: () -> Unit = {},
    onBack: () -> Unit,
    onSongClick: (Song, List<Song>) -> Unit,
    onSongMoreClick: (Song) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PureBlack)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("library_subscreen_back")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = PrimaryWhite
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium.copy(
                    color = PrimaryWhite,
                    fontWeight = FontWeight.Bold
                ),
                modifier = Modifier.weight(1f)
            )
        }

        // Play All & Repeat Controls for Playlists / Collections
        if (showPlaylistControls && songs.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { onSongClick(songs.first(), songs) },
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SpotifyVibrantGreen,
                        contentColor = PureBlack
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("playlist_play_all_button")
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Play All (${songs.size})",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }

                val isRepeatActive = repeatMode != RepeatModeState.OFF
                OutlinedButton(
                    onClick = {
                        val wasOff = repeatMode == RepeatModeState.OFF
                        onCycleRepeatMode()
                        if (wasOff && songs.isNotEmpty() && songs.none { it.id == currentSong?.id }) {
                            onSongClick(songs.first(), songs)
                        }
                    },
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (isRepeatActive) SpotifyDarkGreenPill else SurfaceDarkCard,
                        contentColor = if (isRepeatActive) SpotifyVibrantGreen else PrimaryWhite
                    ),
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("playlist_repeat_button")
                ) {
                    Icon(
                        imageVector = if (repeatMode == RepeatModeState.ONE) {
                            Icons.Default.RepeatOne
                        } else {
                            Icons.Default.Repeat
                        },
                        contentDescription = "Repeat Playlist",
                        tint = if (isRepeatActive) SpotifyVibrantGreen else PrimaryWhite
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = when (repeatMode) {
                            RepeatModeState.OFF -> "Repeat: OFF"
                            RepeatModeState.ALL -> "Repeat: ON"
                            RepeatModeState.ONE -> "Repeat: 1"
                        },
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
        }

        if (songs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    color = SurfaceDarkCard,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = emptyMessage,
                        style = MaterialTheme.typography.bodyLarge.copy(color = MutedGray),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(24.dp)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 140.dp)
            ) {
                items(items = songs, key = { "${title}_${it.id}" }) { song ->
                    SongListRow(
                        song = song,
                        isCurrentSong = currentSong?.id == song.id,
                        isPlaying = isPlaying,
                        onSongClick = { onSongClick(song, songs) },
                        onMoreClick = { onSongMoreClick(song) }
                    )
                }
                item {
                    LibraryCreatorFooter()
                }
            }
        }
    }
}

@Composable
private fun LibraryMyMusicView(
    hasAudioStoragePermission: Boolean,
    deviceLocalSongs: List<Song>,
    downloadedSongs: List<DownloadedSongEntity>,
    currentSong: Song?,
    isPlaying: Boolean,
    onBack: () -> Unit,
    onSongClick: (Song, List<Song>) -> Unit,
    onSongMoreClick: (Song) -> Unit,
    onDeleteDownloadedSong: (DownloadedSongEntity) -> Unit,
    onRequestStoragePermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    val downloadedAsSongs = remember(downloadedSongs) { downloadedSongs.map { it.toSong() } }
    val combinedOfflineQueue = remember(deviceLocalSongs, downloadedAsSongs) {
        deviceLocalSongs + downloadedAsSongs
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PureBlack)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = PrimaryWhite
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "My Music (Offline & Device)",
                style = MaterialTheme.typography.headlineMedium.copy(
                    color = PrimaryWhite,
                    fontWeight = FontWeight.Bold
                )
            )
        }

        if (!hasAudioStoragePermission) {
            // Permission Required Banner Card
            Surface(
                color = SurfaceDarkCard,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .border(
                        width = 1.dp,
                        color = SpotifyGreenBorder.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(16.dp)
                    )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .background(SpotifyDarkGreenPill, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.LibraryMusic,
                            contentDescription = null,
                            tint = SpotifyVibrantGreen,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "Storage Permission Required",
                        style = MaterialTheme.typography.titleLarge.copy(
                            color = PrimaryWhite,
                            fontWeight = FontWeight.Bold
                        ),
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Tap the button below to give permission so AADIFY can load and play all offline songs stored on your device.",
                        style = MaterialTheme.typography.bodyMedium.copy(color = MutedGray),
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(18.dp))
                    Button(
                        onClick = onRequestStoragePermission,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SpotifyVibrantGreen,
                            contentColor = PureBlack
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("give_storage_permission_button")
                    ) {
                        Text(
                            text = "Tap Button to Give Permission",
                            style = MaterialTheme.typography.titleMedium.copy(
                                color = PureBlack,
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }
                }
            }
        }

        if (hasAudioStoragePermission && combinedOfflineQueue.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    color = SurfaceDarkCard,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "No offline music files found on your device yet.",
                        style = MaterialTheme.typography.bodyLarge.copy(color = MutedGray),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(24.dp)
                    )
                }
            }
        } else if (combinedOfflineQueue.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { onSongClick(combinedOfflineQueue.first(), combinedOfflineQueue) },
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SpotifyVibrantGreen,
                        contentColor = PureBlack
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Play All Offline Music (${combinedOfflineQueue.size} Songs)",
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 140.dp)
            ) {
                if (deviceLocalSongs.isNotEmpty()) {
                    item {
                        Text(
                            text = "Device Storage Songs (${deviceLocalSongs.size})",
                            style = MaterialTheme.typography.titleMedium.copy(
                                color = SpotifyVibrantGreen,
                                fontWeight = FontWeight.Bold
                            ),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                        )
                    }
                    items(items = deviceLocalSongs, key = { "dev_${it.id}" }) { song ->
                        SongListRow(
                            song = song,
                            isCurrentSong = currentSong?.id == song.id,
                            isPlaying = isPlaying,
                            subtitlePrefix = "Phone Storage • ",
                            onSongClick = { onSongClick(song, combinedOfflineQueue) },
                            onMoreClick = { onSongMoreClick(song) }
                        )
                    }
                }

                if (downloadedSongs.isNotEmpty()) {
                    item {
                        Text(
                            text = "Downloaded in App (${downloadedSongs.size})",
                            style = MaterialTheme.typography.titleMedium.copy(
                                color = SpotifyVibrantGreen,
                                fontWeight = FontWeight.Bold
                            ),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                        )
                    }
                    items(items = downloadedSongs, key = { "dl_${it.id}" }) { entity ->
                        val song = entity.toSong()
                        val sizeMb = String.format("%.1f MB", entity.fileSizeBytes / (1024f * 1024f))
                        SongListRow(
                            song = song,
                            isCurrentSong = currentSong?.id == song.id,
                            isPlaying = isPlaying,
                            subtitlePrefix = "Offline ($sizeMb) • ",
                            onSongClick = { onSongClick(song, combinedOfflineQueue) },
                            onMoreClick = { onDeleteDownloadedSong(entity) }
                        )
                    }
                }

                item {
                    LibraryCreatorFooter()
                }
            }
        }
    }
}

@Composable
private fun LibraryPlaylistsListView(
    playlists: List<PlaylistEntity>,
    allPlaylistSongs: List<PlaylistSongEntity>,
    onBack: () -> Unit,
    onOpenPlaylist: (PlaylistEntity) -> Unit,
    onCreatePlaylist: (String) -> Unit,
    onDeletePlaylist: (PlaylistEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var playlistName by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PureBlack)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = PrimaryWhite
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(id = R.string.library_playlists),
                    style = MaterialTheme.typography.headlineMedium.copy(
                        color = PrimaryWhite,
                        fontWeight = FontWeight.Bold
                    )
                )
            }

            Button(
                onClick = { showCreateDialog = true },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SpotifyVibrantGreen,
                    contentColor = PureBlack
                ),
                modifier = Modifier.testTag("create_playlist_button")
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text("New", fontWeight = FontWeight.Bold)
            }
        }

        if (playlists.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    color = SurfaceDarkCard,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "No custom playlists yet. Tap \"New\" above or use the top-right 3-lines menu inside the song player to create one.",
                        style = MaterialTheme.typography.bodyLarge.copy(color = MutedGray),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(24.dp)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(items = playlists, key = { it.playlistId }) { playlist ->
                    val trackCount = allPlaylistSongs.count { it.playlistId == playlist.playlistId }
                    Surface(
                        color = SurfaceDarkCard,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onOpenPlaylist(playlist) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.PlaylistPlay,
                                contentDescription = null,
                                tint = SpotifyVibrantGreen,
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = playlist.name,
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        color = PrimaryWhite,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                                Text(
                                    text = "$trackCount songs",
                                    style = MaterialTheme.typography.bodyMedium.copy(color = MutedGray)
                                )
                            }
                            IconButton(onClick = { onDeletePlaylist(playlist) }) {
                                Icon(
                                    imageVector = Icons.Default.DeleteOutline,
                                    contentDescription = "Delete playlist",
                                    tint = ErrorRed
                                )
                            }
                        }
                    }
                }
                item {
                    LibraryCreatorFooter()
                }
            }
        }
    }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            containerColor = SurfaceDarkCard,
            title = {
                Text("Create Playlist", color = PrimaryWhite)
            },
            text = {
                OutlinedTextField(
                    value = playlistName,
                    onValueChange = { playlistName = it },
                    placeholder = { Text("Playlist Name", color = MutedGray) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SpotifyVibrantGreen,
                        unfocusedBorderColor = DividerDark,
                        focusedTextColor = PrimaryWhite,
                        unfocusedTextColor = PrimaryWhite
                    )
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (playlistName.isNotBlank()) {
                            onCreatePlaylist(playlistName)
                            playlistName = ""
                            showCreateDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SpotifyVibrantGreen,
                        contentColor = PureBlack
                    )
                ) {
                    Text("Create", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text("Cancel", color = MutedGray)
                }
            }
        )
    }
}
