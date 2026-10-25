package com.example.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.FilterCenterFocus
import androidx.compose.material.icons.filled.Menu
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.models.Song
import com.example.providers.ChartTab
import com.example.ui.theme.MutedGray
import com.example.ui.theme.PrimaryWhite
import com.example.ui.theme.PureBlack
import com.example.ui.theme.SpotifyVibrantGreen
import com.example.ui.theme.SurfaceDarkCard
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
    modifier: Modifier = Modifier
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

        if (chartSongs.isEmpty()) {
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
                itemsIndexed(
                    items = chartSongs,
                    key = { _, song -> "chart_${selectedChartTab.name}_${song.id}" }
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
