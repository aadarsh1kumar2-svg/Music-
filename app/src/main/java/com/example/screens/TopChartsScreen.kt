package com.example.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.FilterCenterFocus
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
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
import com.example.ui.theme.DeepCharcoal
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
    expandedPlaylistId: String? = null,
    expandedPlaylistSongs: List<Song> = emptyList(),
    isLoadingPlaylist: Boolean = false,
    onPlaylistClick: (AdminChartPlaylist) -> Unit = {}
) {
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
                // Admin Playlists rendered in the exact same vertical Song-row layout!
                // Single click expands all playlist songs directly underneath; clicking any song plays immediately.
                if (playlists.isNotEmpty()) {
                    items(
                        items = playlists,
                        key = { "top_chart_pl_row_${it.id}" }
                    ) { playlist ->
                        val isExpanded = expandedPlaylistId == playlist.id
                        Column(modifier = Modifier.fillMaxWidth()) {
                            TopChartPlaylistSongStyleRow(
                                playlist = playlist,
                                isExpanded = isExpanded,
                                loadedCount = if (isExpanded && expandedPlaylistSongs.isNotEmpty()) expandedPlaylistSongs.size else playlist.songs.size,
                                onClick = { onPlaylistClick(playlist) }
                            )

                            AnimatedVisibility(
                                visible = isExpanded,
                                enter = expandVertically(),
                                exit = shrinkVertically()
                            ) {
                                Surface(
                                    color = DeepCharcoal,
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 10.dp)
                                        .border(1.dp, DividerDark, RoundedCornerShape(14.dp))
                                        .testTag("top_chart_playlist_expanded_${playlist.id}")
                                ) {
                                    when {
                                        isLoadingPlaylist -> {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 16.dp, vertical = 18.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.Center
                                            ) {
                                                CircularProgressIndicator(
                                                    color = SpotifyVibrantGreen,
                                                    strokeWidth = 2.5.dp,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                                Spacer(modifier = Modifier.width(12.dp))
                                                Text(
                                                    text = "Loading playlist songs...",
                                                    style = MaterialTheme.typography.bodyMedium.copy(
                                                        color = MutedGray,
                                                        fontWeight = FontWeight.Medium
                                                    )
                                                )
                                            }
                                        }
                                        expandedPlaylistSongs.isEmpty() -> {
                                            Text(
                                                text = "No playable tracks found in this playlist yet.",
                                                style = MaterialTheme.typography.bodySmall.copy(color = MutedGray),
                                                textAlign = TextAlign.Center,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(16.dp)
                                            )
                                        }
                                        else -> {
                                            Column(modifier = Modifier.fillMaxWidth()) {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.SpaceBetween
                                                ) {
                                                    Text(
                                                        text = "${expandedPlaylistSongs.size} songs in playlist",
                                                        style = MaterialTheme.typography.labelMedium.copy(
                                                            color = SpotifyVibrantGreen,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                    )
                                                    Surface(
                                                        color = SpotifyVibrantGreen,
                                                        shape = RoundedCornerShape(50),
                                                        modifier = Modifier
                                                            .clip(RoundedCornerShape(50))
                                                            .clickable {
                                                                onSongClick(expandedPlaylistSongs.first(), expandedPlaylistSongs)
                                                            }
                                                            .testTag("top_chart_playlist_play_all_${playlist.id}")
                                                    ) {
                                                        Row(
                                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Icon(
                                                                imageVector = Icons.Default.PlayArrow,
                                                                contentDescription = "Play All",
                                                                tint = PureBlack,
                                                                modifier = Modifier.size(16.dp)
                                                            )
                                                            Spacer(modifier = Modifier.width(4.dp))
                                                            Text(
                                                                text = "Play All",
                                                                style = MaterialTheme.typography.labelSmall.copy(
                                                                    color = PureBlack,
                                                                    fontWeight = FontWeight.ExtraBold
                                                                )
                                                            )
                                                        }
                                                    }
                                                }
                                                HorizontalDivider(color = DividerDark)
                                                expandedPlaylistSongs.forEachIndexed { idx, plSong ->
                                                    SongListRow(
                                                        song = plSong,
                                                        isCurrentSong = currentSong?.id == plSong.id,
                                                        isPlaying = isPlaying,
                                                        rankNumber = idx + 1,
                                                        subtitlePrefix = "",
                                                        onSongClick = { onSongClick(plSong, expandedPlaylistSongs) },
                                                        onMoreClick = { onSongMoreClick(plSong) }
                                                    )
                                                }
                                            }
                                        }
                                    }
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
private fun TopChartPlaylistSongStyleRow(
    playlist: AdminChartPlaylist,
    isExpanded: Boolean,
    loadedCount: Int,
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
                    color = if (isExpanded) SpotifyVibrantGreen else PrimaryWhite,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.5.sp
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(3.dp))
            val effectiveCount = maxOf(loadedCount, playlist.itemCount)
            val platformLabel = when (playlist.sourceType) {
                "youtube" -> "YouTube Playlist"
                "spotify" -> "Spotify Playlist"
                else -> "Playlist"
            }
            val subText = if (effectiveCount > 0) {
                "$platformLabel • $effectiveCount songs"
            } else {
                "$platformLabel • Tap to view songs"
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

        Surface(
            color = if (isExpanded) SpotifyDarkGreenPill else SurfaceDarkCard,
            shape = RoundedCornerShape(50),
            modifier = Modifier
                .size(36.dp)
                .border(
                    width = 1.dp,
                    color = if (isExpanded) SpotifyGreenBorder else DividerDark,
                    shape = RoundedCornerShape(50)
                )
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (isExpanded) "Collapse Playlist" else "Expand Playlist",
                    tint = if (isExpanded) SpotifyVibrantGreen else PrimaryWhite,
                    modifier = Modifier.size(22.dp)
                )
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
