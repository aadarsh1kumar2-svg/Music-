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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.FilterCenterFocus
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.models.AdminChartPlaylist
import com.example.models.Song
import com.example.providers.ChartTab
import com.example.ui.theme.DividerDark
import com.example.ui.theme.MutedGray
import com.example.ui.theme.PrimaryWhite
import com.example.ui.theme.PureBlack
import com.example.ui.theme.SpotifyDarkGreenPill
import com.example.ui.theme.SpotifyGreenBorder
import com.example.ui.theme.SpotifyVibrantGreen
import com.example.ui.theme.SurfaceDarkCard
import com.example.widgets.SongArtwork
import com.example.widgets.SongListRow

@Composable
fun TopChartsScreen(
    chartSongs: List<Song>,
    selectedChartTab: ChartTab,
    currentSong: Song?,
    isPlaying: Boolean,
    onSelectChartTab: (ChartTab) -> Unit,
    onSongClick: (Song, List<Song>) -> Unit,
    onSongMoreClick: (Song) -> Unit,
    onOpenDrawerMenu: () -> Unit,
    onRefreshCatalog: () -> Unit,
    modifier: Modifier = Modifier,
    playlists: List<AdminChartPlaylist> = emptyList(),
    selectedPlaylistId: String? = null,
    openedPlaylist: AdminChartPlaylist? = null,
    openedPlaylistSongs: List<Song> = emptyList(),
    isLoadingPlaylist: Boolean = false,
    onPlaylistClick: (AdminChartPlaylist) -> Unit = {},
    onCloseOpenedPlaylist: () -> Unit = {}
) {
    if (openedPlaylist != null) {
        BackHandler {
            onCloseOpenedPlaylist()
        }
        TopChartPlaylistDetailView(
            playlist = openedPlaylist,
            songs = openedPlaylistSongs,
            isLoading = isLoadingPlaylist,
            currentSong = currentSong,
            isPlaying = isPlaying,
            onBackClick = onCloseOpenedPlaylist,
            onSongClick = onSongClick,
            onSongMoreClick = onSongMoreClick,
            modifier = modifier
        )
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PureBlack)
            .testTag("top_charts_screen")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onOpenDrawerMenu,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Menu,
                    contentDescription = "Menu",
                    tint = PrimaryWhite,
                    modifier = Modifier.size(28.dp)
                )
            }

            Text(
                text = stringResource(id = R.string.charts_title),
                style = MaterialTheme.typography.headlineMedium.copy(
                    color = PrimaryWhite,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 22.sp
                )
            )

            IconButton(
                onClick = onRefreshCatalog,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("charts_refresh_button")
            ) {
                Icon(
                    imageVector = Icons.Default.FilterCenterFocus,
                    contentDescription = "Refresh Charts",
                    tint = PrimaryWhite,
                    modifier = Modifier.size(26.dp)
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            ChartToggleTabItem(
                title = stringResource(id = R.string.charts_local),
                isSelected = selectedChartTab == ChartTab.LOCAL,
                testTag = "chart_tab_local",
                onClick = { onSelectChartTab(ChartTab.LOCAL) },
                modifier = Modifier.weight(1f)
            )
            ChartToggleTabItem(
                title = stringResource(id = R.string.charts_global),
                isSelected = selectedChartTab == ChartTab.GLOBAL,
                testTag = "chart_tab_global",
                onClick = { onSelectChartTab(ChartTab.GLOBAL) },
                modifier = Modifier.weight(1f)
            )
        }

        if (chartSongs.isEmpty() && playlists.isEmpty()) {
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
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.TrendingUp,
                            contentDescription = null,
                            tint = SpotifyVibrantGreen,
                            modifier = Modifier.size(44.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "No Chart Songs Yet",
                            style = MaterialTheme.typography.titleLarge.copy(
                                color = PrimaryWhite,
                                fontWeight = FontWeight.Bold
                            )
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Songs in your music library automatically rank here by play count (Local) and release time (Global).",
                            style = MaterialTheme.typography.bodyMedium.copy(color = MutedGray),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 140.dp)
            ) {
                if (playlists.isNotEmpty()) {
                    item(key = "top_chart_playlists_section") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp, bottom = 14.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.QueueMusic,
                                        contentDescription = null,
                                        tint = SpotifyVibrantGreen,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Chart Playlists",
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            color = PrimaryWhite,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 17.sp
                                        )
                                    )
                                }
                                Text(
                                    text = "Tap twice to open",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = MutedGray,
                                        fontSize = 11.5.sp
                                    )
                                )
                            }

                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(
                                    items = playlists,
                                    key = { "top_chart_pl_${it.id}" }
                                ) { playlist ->
                                    val isSelected = selectedPlaylistId == playlist.id
                                    TopChartPlaylistCard(
                                        playlist = playlist,
                                        isSelected = isSelected,
                                        onClick = { onPlaylistClick(playlist) }
                                    )
                                }
                            }
                        }
                    }
                }

                itemsIndexed(
                    items = chartSongs,
                    key = { index, song -> "chart_${selectedChartTab.name}_${index}_${song.id}" }
                ) { index, song ->
                    SongListRow(
                        song = song,
                        isCurrentSong = currentSong?.id == song.id,
                        isPlaying = isPlaying,
                        rankNumber = song.rank.takeIf { it in 1..99999 } ?: (index + 1),
                        subtitlePrefix = "",
                        onSongClick = { onSongClick(song, chartSongs) },
                        onMoreClick = { onSongMoreClick(song) }
                    )
                }
            }
        }
    }
}

@Composable
private fun TopChartPlaylistCard(
    playlist: AdminChartPlaylist,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        color = SurfaceDarkCard,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .width(164.dp)
            .border(
                width = if (isSelected) 1.5.dp else 1.dp,
                color = if (isSelected) SpotifyVibrantGreen else DividerDark,
                shape = RoundedCornerShape(14.dp)
            )
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .testTag("top_chart_playlist_card_${playlist.id}")
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(112.dp)
            ) {
                SongArtwork(
                    imageUrl = playlist.resolvedCoverUrl,
                    contentDescription = playlist.title,
                    modifier = Modifier.fillMaxSize(),
                    cornerRadius = 10.dp
                )
                if (isSelected) {
                    Surface(
                        color = SpotifyDarkGreenPill.copy(alpha = 0.94f),
                        shape = RoundedCornerShape(50),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 6.dp)
                            .border(1.dp, SpotifyGreenBorder, RoundedCornerShape(50))
                    ) {
                        Text(
                            text = "Tap again to open",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = SpotifyVibrantGreen,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.5.sp
                            ),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = playlist.title,
                style = MaterialTheme.typography.titleSmall.copy(
                    color = if (isSelected) SpotifyVibrantGreen else PrimaryWhite,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(2.dp))

            val subLabel = when {
                playlist.songs.isNotEmpty() -> "${playlist.songs.size} songs"
                playlist.sourceType == "youtube" -> "YouTube Playlist"
                playlist.sourceType == "spotify" -> "Spotify Playlist"
                else -> "Playlist"
            }
            Text(
                text = subLabel,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = MutedGray,
                    fontSize = 12.sp
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun TopChartPlaylistDetailView(
    playlist: AdminChartPlaylist,
    songs: List<Song>,
    isLoading: Boolean,
    currentSong: Song?,
    isPlaying: Boolean,
    onBackClick: () -> Unit,
    onSongClick: (Song, List<Song>) -> Unit,
    onSongMoreClick: (Song) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PureBlack)
            .testTag("top_chart_playlist_detail")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBackClick,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("top_chart_playlist_back_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back to Top Charts",
                    tint = PrimaryWhite
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = playlist.title,
                style = MaterialTheme.typography.titleLarge.copy(
                    color = PrimaryWhite,
                    fontWeight = FontWeight.Bold,
                    fontSize = 19.sp
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }

        Surface(
            color = SurfaceDarkCard,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .border(1.dp, DividerDark, RoundedCornerShape(14.dp))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SongArtwork(
                    imageUrl = playlist.resolvedCoverUrl,
                    contentDescription = playlist.title,
                    modifier = Modifier.size(72.dp),
                    cornerRadius = 12.dp
                )
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = playlist.title,
                        style = MaterialTheme.typography.titleMedium.copy(
                            color = PrimaryWhite,
                            fontWeight = FontWeight.Bold
                        ),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (songs.isNotEmpty()) "${songs.size} songs" else "Fetching playlist tracks...",
                        style = MaterialTheme.typography.bodySmall.copy(color = MutedGray)
                    )
                }
                if (songs.isNotEmpty()) {
                    Button(
                        onClick = { onSongClick(songs.first(), songs) },
                        shape = RoundedCornerShape(50),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SpotifyVibrantGreen,
                            contentColor = PureBlack
                        ),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        modifier = Modifier.height(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Play All",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Play",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        when {
            isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = SpotifyVibrantGreen)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Loading playlist songs...",
                            style = MaterialTheme.typography.bodyMedium.copy(color = MutedGray)
                        )
                    }
                }
            }
            songs.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No playable tracks found in this playlist yet.",
                        style = MaterialTheme.typography.bodyMedium.copy(color = MutedGray),
                        textAlign = TextAlign.Center
                    )
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 140.dp)
                ) {
                    itemsIndexed(
                        items = songs,
                        key = { index, song -> "pl_${playlist.id}_${index}_${song.id}" }
                    ) { index, song ->
                        SongListRow(
                            song = song,
                            isCurrentSong = currentSong?.id == song.id,
                            isPlaying = isPlaying,
                            rankNumber = index + 1,
                            subtitlePrefix = "",
                            onSongClick = { onSongClick(song, songs) },
                            onMoreClick = { onSongMoreClick(song) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChartToggleTabItem(
    title: String,
    isSelected: Boolean,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp)
            .testTag(testTag),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(
                color = if (isSelected) PrimaryWhite else MutedGray,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                fontSize = 16.sp
            )
        )
        Spacer(modifier = Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .width(56.dp)
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (isSelected) SpotifyVibrantGreen else PureBlack)
        )
    }
}
