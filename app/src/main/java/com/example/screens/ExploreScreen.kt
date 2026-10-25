package com.example.screens

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.models.Song
import com.example.ui.theme.DeepCharcoal
import com.example.ui.theme.MutedGray
import com.example.ui.theme.PrimaryWhite
import com.example.ui.theme.PureBlack
import com.example.ui.theme.SpotifyDarkGreenPill
import com.example.ui.theme.SpotifyGreenBorder
import com.example.ui.theme.SpotifyVibrantGreen
import com.example.ui.theme.SurfaceDarkCard
import com.example.utils.CountryOption
import com.example.utils.SUPPORTED_COUNTRIES
import com.example.widgets.SongArtwork
import com.example.widgets.SongListRow

@Composable
fun ExploreScreen(
    songs: List<Song>,
    searchQuery: String,
    isSearchingOnline: Boolean = false,
    selectedCountry: CountryOption,
    currentSong: Song?,
    isPlaying: Boolean,
    onSearchQueryChange: (String) -> Unit,
    onSelectCountry: (CountryOption) -> Unit,
    onSongClick: (Song, List<Song>) -> Unit,
    onSongMoreClick: (Song) -> Unit,
    onOpenDrawerMenu: () -> Unit,
    modifier: Modifier = Modifier
) {
    var countryMenuExpanded by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(PureBlack)
            .testTag("explore_screen"),
        contentPadding = PaddingValues(bottom = 140.dp)
    ) {
        item {
            Surface(
                color = SurfaceDarkCard,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp)
                    .height(56.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onOpenDrawerMenu,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Menu,
                            contentDescription = "Menu",
                            tint = PrimaryWhite
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search Online",
                        tint = SpotifyVibrantGreen,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (searchQuery.isEmpty()) {
                            Text(
                                text = "Search any song in ${selectedCountry.name}...",
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    color = MutedGray,
                                    fontSize = 15.sp
                                )
                            )
                        }
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = onSearchQueryChange,
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                color = PrimaryWhite,
                                fontSize = 16.sp
                            ),
                            cursorBrush = SolidColor(SpotifyVibrantGreen),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("explore_search_input")
                        )
                    }
                    if (isSearchingOnline) {
                        CircularProgressIndicator(
                            color = SpotifyVibrantGreen,
                            strokeWidth = 2.dp,
                            modifier = Modifier
                                .size(22.dp)
                                .padding(end = 4.dp)
                        )
                    }
                    if (searchQuery.isNotEmpty()) {
                        IconButton(
                            onClick = { onSearchQueryChange("") },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "Clear search",
                                tint = MutedGray
                            )
                        }
                    }
                }
            }
        }

        // Country Badge Selector (e.g., "🇮🇳 India ⌄")
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(24.dp))
                            .background(SpotifyDarkGreenPill)
                            .border(
                                width = 1.dp,
                                color = SpotifyGreenBorder,
                                shape = RoundedCornerShape(24.dp)
                            )
                            .clickable { countryMenuExpanded = true }
                            .padding(horizontal = 18.dp, vertical = 10.dp)
                            .testTag("explore_country_selector"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = selectedCountry.flagEmoji,
                            fontSize = 18.sp
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = selectedCountry.name,
                            style = MaterialTheme.typography.titleMedium.copy(
                                color = PrimaryWhite,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 16.sp
                            )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = "Select Country",
                            tint = SpotifyVibrantGreen,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    DropdownMenu(
                        expanded = countryMenuExpanded,
                        onDismissRequest = { countryMenuExpanded = false },
                        modifier = Modifier.background(SurfaceDarkCard)
                    ) {
                        SUPPORTED_COUNTRIES.forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = "${option.flagEmoji}  ${option.name}",
                                        color = if (option.code == selectedCountry.code) SpotifyVibrantGreen else PrimaryWhite,
                                        fontWeight = if (option.code == selectedCountry.code) FontWeight.Bold else FontWeight.Normal
                                    )
                                },
                                onClick = {
                                    countryMenuExpanded = false
                                    onSelectCountry(option)
                                }
                            )
                        }
                    }
                }

                Text(
                    text = "${songs.size} tracks ready",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MutedGray,
                        fontSize = 13.sp
                    )
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
        }

        // If user is actively searching in Explore, show Search Results immediately at the top!
        if (searchQuery.isNotBlank()) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${selectedCountry.flagEmoji} Results for \"$searchQuery\" (${songs.size})",
                        style = MaterialTheme.typography.headlineMedium.copy(
                            color = SpotifyVibrantGreen,
                            fontWeight = FontWeight.Bold,
                            fontSize = 19.sp
                        )
                    )
                }
            }

            if (songs.isEmpty() && isSearchingOnline) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = SpotifyVibrantGreen)
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Searching songs in ${selectedCountry.name}...",
                                style = MaterialTheme.typography.bodyMedium.copy(color = MutedGray)
                            )
                        }
                    }
                }
            } else {
                items(
                    items = songs,
                    key = { "explore_search_${it.id}" }
                ) { song ->
                    SongListRow(
                        song = song,
                        isCurrentSong = currentSong?.id == song.id,
                        isPlaying = isPlaying,
                        onSongClick = { onSongClick(song, songs) },
                        onMoreClick = { onSongMoreClick(song) }
                    )
                }
            }
        } else {
            // Horizontal Sliding Carousel Cards for Trending Releases in Selected Country
            item {
                if (songs.isNotEmpty()) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        items(
                            items = songs.take(12),
                            key = { "carousel_${it.id}" }
                        ) { song ->
                            Column(
                                modifier = Modifier
                                    .width(275.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onSongClick(song, songs) }
                            ) {
                                SongArtwork(
                                    imageUrl = song.highResBannerUrl,
                                    contentDescription = song.title,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(175.dp),
                                    cornerRadius = 12.dp
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = "${song.artist} - ${song.title}",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        color = PrimaryWhite,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 16.sp
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }

            // "Video charts" Section with Modern Squared Music Cards
            item {
                Text(
                    text = stringResource(id = R.string.video_charts),
                    style = MaterialTheme.typography.headlineMedium.copy(
                        color = SpotifyVibrantGreen,
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp
                    ),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }

            item {
                val chartCategories = listOf(
                    "Trending 20 ${selectedCountry.name}" to "Chart • ${selectedCountry.flagEmoji} Top Hits",
                    "Daily Top Music Videos" to "Chart • Global & Local",
                    "Top Artists ${selectedCountry.name}" to "Chart • Most Streamed"
                )

                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(chartCategories.size) { idx ->
                        val (chartTitle, chartSubtitle) = chartCategories[idx]
                        val previewSong = songs.getOrNull(idx)

                        Column(
                            modifier = Modifier
                                .width(164.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    if (previewSong != null) {
                                        onSongClick(previewSong, songs)
                                    } else if (songs.isNotEmpty()) {
                                        onSongClick(songs.first(), songs)
                                    }
                                }
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(164.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(SurfaceDarkCard),
                                contentAlignment = Alignment.Center
                            ) {
                                if (previewSong != null) {
                                    SongArtwork(
                                        imageUrl = previewSong.resolvedBannerUrl,
                                        contentDescription = chartTitle,
                                        modifier = Modifier.fillMaxSize(),
                                        cornerRadius = 12.dp
                                    )
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(DeepCharcoal.copy(alpha = 0.45f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.MusicNote,
                                            contentDescription = null,
                                            tint = PrimaryWhite.copy(alpha = 0.85f),
                                            modifier = Modifier.size(64.dp)
                                        )
                                    }
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.MusicNote,
                                        contentDescription = null,
                                        tint = MutedGray,
                                        modifier = Modifier.size(68.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = chartTitle,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    color = PrimaryWhite,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 15.sp
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = chartSubtitle,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = MutedGray,
                                    fontSize = 13.sp
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }

            if (songs.isNotEmpty()) {
                item {
                    Text(
                        text = "${selectedCountry.flagEmoji} Discover in ${selectedCountry.name} (${songs.size})",
                        style = MaterialTheme.typography.headlineMedium.copy(
                            color = SpotifyVibrantGreen,
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        ),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
                items(
                    items = songs,
                    key = { "explore_row_${it.id}" }
                ) { song ->
                    SongListRow(
                        song = song,
                        isCurrentSong = currentSong?.id == song.id,
                        isPlaying = isPlaying,
                        onSongClick = { onSongClick(song, songs) },
                        onMoreClick = { onSongMoreClick(song) }
                    )
                }
            }
        }
    }
}
