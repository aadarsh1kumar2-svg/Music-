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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.FilterCenterFocus
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
    openedPlaylist: AdminChartPlaylist? = null,
    openedPlaylistSongs: List<Song> = emptyList(),
    isLoadingPlaylist: Boolean = false,
    onPlaylistClick: (AdminChartPlaylist) -> Unit = {},
    onClosePlaylist: () -> Unit = {},
    onPlayAllPlaylist: () -> Unit = {}
) {
    // Dedicated full Playlist Page when a Spotify playlist row is clicked:
    // Top = Banner, Below = Playlist Name + Play All button, Below = All Playlist Songs
    if (openedPlaylist != null) {
        TopChartPlaylistDetailPage(
            playlist = openedPlaylist,
            songs = openedPlaylistSongs,
            isLoading = isLoadingPlaylist,
            currentSong = currentSong,
            isPlaying = isPlaying,
            onBack = onClosePlaylist,
            onPlayAll = onPlayAllPlaylist,
            onSongClick = onSongClick,
            onSongMoreClick = onSongMoreClick,
            modifier = modifier
        )
        return
    }

    // Only Spotify playlists are displayed in Top Charts (YouTube playlists are excluded)
    val spotifyPlaylists = playlists.filter { it.sourceType == "spotify" || Song.isSpotifyUrl(it.playlistUrl) }

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

        if (chartSongs.isEmpty() && spotifyPlaylists.isEmpty()) {
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
                // Spotify Playlists displayed just like Song rows (Banner + Playlist Name)
                // Tapping a playlist opens its dedicated full Playlist Page with Banner, Name, Play All & Songs.
                if (spotifyPlaylists.isNotEmpty()) {
                    items(
                        items = spotifyPlaylists,
                        key = { "top_chart_pl_row_${it.id}" }
                    ) { playlist ->
                        TopChartPlaylistSongStyleRow(
                            playlist = playlist,
                            onClick = { onPlaylistClick(playlist) }
                        )
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
private fun TopChartPlaylistDetailPage(
    playlist: AdminChartPlaylist,
    songs: List<Song>,
    isLoading: Boolean,
    currentSong: Song?,
    isPlaying: Boolean,
    onBack: () -> Unit,
    onPlayAll: () -> Unit,
    onSongClick: (Song, List<Song>) -> Unit,
    onSongMoreClick: (Song) -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBack)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PureBlack)
            .testTag("top_chart_playlist_detail_page")
    ) {
        // Top App Bar with Back button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("playlist_detail_back_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = PrimaryWhite,
                    modifier = Modifier.size(26.dp)
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "Playlist",
                style = MaterialTheme.typography.titleLarge.copy(
                    color = PrimaryWhite,
                    fontWeight = FontWeight.Bold,
                    fontSize = 19.sp
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 148.dp)
        ) {
            // Hero Header: Banner on top -> Playlist Name below -> Play All button
            item(key = "playlist_detail_header") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    SpotifyDarkGreenPill.copy(alpha = 0.45f),
                                    PureBlack
                                )
                            )
                        )
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val resolvedHeroBanner = playlist.resolvedCoverUrl
                        .ifBlank { songs.firstOrNull { it.highResBannerUrl.isNotBlank() }?.highResBannerUrl.orEmpty() }

                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = SurfaceDarkCard,
                        shadowElevation = 10.dp,
                        modifier = Modifier
                            .size(210.dp)
                            .border(1.dp, SpotifyGreenBorder.copy(alpha = 0.55f), RoundedCornerShape(18.dp))
                    ) {
                        SongArtwork(
                            imageUrl = resolvedHeroBanner,
                            contentDescription = playlist.title,
                            modifier = Modifier.fillMaxSize(),
                            cornerRadius = 18.dp
                        )
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    Text(
                        text = playlist.title,
                        style = MaterialTheme.typography.headlineSmall.copy(
                            color = PrimaryWhite,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 23.sp
                        ),
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("playlist_detail_title")
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    val effectiveCount = maxOf(songs.size, playlist.itemCount)
                    val subtitleText = if (effectiveCount > 0) {
                        "Spotify Playlist • $effectiveCount songs"
                    } else {
                        "Spotify Playlist"
                    }
                    Text(
                        text = subtitleText,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = MutedGray,
                            fontWeight = FontWeight.Medium
                        ),
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(18.dp))

                    Button(
                        onClick = onPlayAll,
                        enabled = !isLoading && songs.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SpotifyVibrantGreen,
                            contentColor = PureBlack,
                            disabledContainerColor = SurfaceDarkCard,
                            disabledContentColor = MutedGray
                        ),
                        shape = RoundedCornerShape(50),
                        contentPadding = PaddingValues(horizontal = 32.dp, vertical = 12.dp),
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("playlist_detail_play_all_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Play All",
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Play All",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 15.5.sp
                            )
                        )
                    }
                }
                HorizontalDivider(color = DividerDark, modifier = Modifier.padding(top = 6.dp, bottom = 8.dp))
            }

            when {
                isLoading -> {
                    item(key = "playlist_loading_indicator") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 40.dp, horizontal = 24.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                color = SpotifyVibrantGreen,
                                strokeWidth = 3.dp,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(14.dp))
                            Text(
                                text = "Loading Spotify playlist songs...",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = MutedGray,
                                    fontWeight = FontWeight.Medium
                                )
                            )
                        }
                    }
                }
                songs.isEmpty() -> {
                    item(key = "playlist_empty_state") {
                        Text(
                            text = "No playable songs found in this Spotify playlist right now.",
                            style = MaterialTheme.typography.bodyMedium.copy(color = MutedGray),
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 36.dp)
                        )
                    }
                }
                else -> {
                    itemsIndexed(
                        items = songs,
                        key = { idx, plSong -> "pl_detail_${playlist.id}_${idx}_${plSong.id}" }
                    ) { idx, plSong ->
                        SongListRow(
                            song = plSong,
                            isCurrentSong = currentSong?.id == plSong.id,
                            isPlaying = isPlaying,
                            rankNumber = idx + 1,
                            subtitlePrefix = "",
                            onSongClick = { onSongClick(plSong, songs) },
                            onMoreClick = { onSongMoreClick(plSong) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TopChartPlaylistSongStyleRow(
    playlist: AdminChartPlaylist,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("top_chart_playlist_row_${playlist.id}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(60.dp)) {
            SongArtwork(
                imageUrl = playlist.resolvedCoverUrl,
                contentDescription = playlist.title,
                modifier = Modifier.fillMaxSize(),
                cornerRadius = 10.dp
            )
            Surface(
                color = PureBlack.copy(alpha = 0.78f),
                shape = RoundedCornerShape(topStart = 6.dp, bottomEnd = 10.dp),
                modifier = Modifier.align(Alignment.BottomEnd)
            ) {
                Icon(
                    imageVector = Icons.Default.QueueMusic,
                    contentDescription = "Playlist",
                    tint = SpotifyVibrantGreen,
                    modifier = Modifier
                        .padding(3.dp)
                        .size(14.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = playlist.title,
                style = MaterialTheme.typography.titleMedium.copy(
                    color = PrimaryWhite,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.5.sp
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(3.dp))
            val effectiveCount = maxOf(playlist.songs.size, playlist.itemCount)
            val subText = if (effectiveCount > 0) {
                "Spotify Playlist • $effectiveCount songs"
            } else {
                "Spotify Playlist"
            }
            Text(
                text = subText,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = MutedGray,
                    fontSize = 12.5.sp
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = "Open Playlist",
            tint = MutedGray,
            modifier = Modifier.size(24.dp)
        )
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
