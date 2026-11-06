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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.models.SessionHistoryEntity
import com.example.models.Song
import com.example.ui.theme.MutedGray
import com.example.ui.theme.PrimaryWhite
import com.example.ui.theme.PureBlack
import com.example.ui.theme.SpotifyVibrantGreen
import com.example.ui.theme.SurfaceDarkCard
import com.example.widgets.SongListRow

import androidx.compose.foundation.border
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.draw.clip
import com.example.ui.theme.DeepCharcoal
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.SpotifyDarkGreenPill
import com.example.ui.theme.SpotifyGreenBorder

@Composable
fun HomeScreen(
    userName: String,
    songs: List<Song>,
    recentSessionHistory: List<SessionHistoryEntity>,
    searchQuery: String,
    isLoading: Boolean,
    currentSong: Song?,
    isPlaying: Boolean,
    isUserSubscribed: Boolean = false,
    subscriptionPlanName: String = "",
    activeSubscriptionRemainingSeconds: Long = 0L,
    dailyListenedSeconds: Int = 0,
    onOpenSubscriptionModal: () -> Unit = {},
    onSearchQueryChange: (String) -> Unit,
    onSongClick: (Song, List<Song>) -> Unit,
    onSongMoreClick: (Song) -> Unit,
    onOpenDrawerMenu: () -> Unit,
    onRefreshCatalog: () -> Unit,
    onEditUserName: () -> Unit,
    modifier: Modifier = Modifier
) {
    val allSessionSongs = remember(recentSessionHistory) {
        recentSessionHistory.map { it.toSong() }
    }

    val matchingPlayedSongs = remember(allSessionSongs, searchQuery) {
        val trimmed = searchQuery.trim()
        if (trimmed.isEmpty()) {
            allSessionSongs
        } else {
            val tokens = trimmed.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
            allSessionSongs.filter { song ->
                val haystack = "${song.title} ${song.artist}".lowercase()
                tokens.all { token -> haystack.contains(token) } ||
                        song.title.contains(trimmed, ignoreCase = true) ||
                        song.artist.contains(trimmed, ignoreCase = true)
            }
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(PureBlack)
            .testTag("home_screen"),
        contentPadding = PaddingValues(bottom = 140.dp)
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onOpenDrawerMenu,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("home_drawer_menu_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Menu,
                        contentDescription = "Drawer Menu",
                        tint = PrimaryWhite,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = SpotifyDarkGreenPill,
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier
                            .border(1.dp, SpotifyGreenBorder, RoundedCornerShape(20.dp))
                            .clickable { onOpenSubscriptionModal() }
                            .testTag("home_top_vip_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.WorkspacePremium,
                                contentDescription = "VIP",
                                tint = SpotifyVibrantGreen,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = if (isUserSubscribed) "👑 VIP Active" else "👑 Get VIP",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    color = SpotifyVibrantGreen,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    IconButton(
                        onClick = onRefreshCatalog,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("home_sync_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudSync,
                            contentDescription = "Refresh Library",
                            tint = MutedGray
                        )
                    }
                }
            }
        }

        // Greeting: "Hi There," in vibrant green + Logged-in User's Name in white
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onEditUserName() }
                    .padding(horizontal = 20.dp)
                    .padding(top = 4.dp, bottom = 14.dp)
                    .testTag("home_greeting_header")
            ) {
                Text(
                    text = stringResource(id = R.string.greeting_prefix),
                    style = MaterialTheme.typography.displayLarge.copy(
                        color = SpotifyVibrantGreen,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 36.sp
                    )
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = userName.ifBlank { stringResource(id = R.string.greeting_name) },
                    style = MaterialTheme.typography.displayMedium.copy(
                        color = PrimaryWhite,
                        fontWeight = FontWeight.Bold,
                        fontSize = 26.sp,
                        letterSpacing = 1.5.sp
                    )
                )
            }
        }

        // 1-Hour Daily Free Listening Timer OR Active VIP Status Banner on Home Screen
        item {
            if (isUserSubscribed) {
                Surface(
                    color = SpotifyDarkGreenPill,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .border(1.dp, SpotifyVibrantGreen.copy(alpha = 0.7f), RoundedCornerShape(16.dp))
                        .clickable { onOpenSubscriptionModal() }
                        .testTag("home_vip_active_banner")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Verified,
                                contentDescription = null,
                                tint = SpotifyVibrantGreen,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "👑 VIP Active • ${subscriptionPlanName.ifBlank { "Premium" }}",
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        color = PrimaryWhite,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                )
                                val timerSub = if (activeSubscriptionRemainingSeconds > 365L * 86_400L) {
                                    "Lifetime Forever VIP ♾️ • Unlimited Music, Playlists & Favorites"
                                } else {
                                    "Remaining: ${formatCountdownTimer(activeSubscriptionRemainingSeconds)} • Unlimited Music & Playlists"
                                }
                                Text(
                                    text = timerSub,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = SpotifyVibrantGreen,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                )
                            }
                        }
                    }
                }
            } else {
                val remainingFreeSeconds = (3600 - dailyListenedSeconds).coerceAtLeast(0)
                val remainingMins = remainingFreeSeconds / 60
                val remainingSecs = remainingFreeSeconds % 60
                val progressFraction = (remainingFreeSeconds / 3600f).coerceIn(0f, 1f)
                val isLowTime = remainingFreeSeconds <= 300

                Surface(
                    color = DeepCharcoal,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .border(
                            width = 1.dp,
                            color = if (isLowTime) ErrorRed.copy(alpha = 0.75f) else SpotifyGreenBorder,
                            shape = RoundedCornerShape(16.dp)
                        )
                        .clickable { onOpenSubscriptionModal() }
                        .testTag("home_daily_free_timer_card")
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.AccessTime,
                                    contentDescription = "1 Hour Free Timer",
                                    tint = if (isLowTime) ErrorRed else SpotifyVibrantGreen,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Daily Free Music Timer (1 Hr)",
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        color = PrimaryWhite,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                            }
                            Text(
                                text = String.format("%02dm %02ds / 60m left", remainingMins, remainingSecs),
                                style = MaterialTheme.typography.labelLarge.copy(
                                    color = if (isLowTime) ErrorRed else SpotifyVibrantGreen,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        LinearProgressIndicator(
                            progress = { progressFraction },
                            color = if (isLowTime) ErrorRed else SpotifyVibrantGreen,
                            trackColor = SurfaceDarkCard,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Listen to any songs for 1 hr/day • Tap for Unlimited VIP",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = MutedGray,
                                    fontSize = 11.sp
                                )
                            )
                            Text(
                                text = "👑 Upgrade",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = SpotifyVibrantGreen,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
        }

        // Home Search Bar — Specifically searches the user's Last Session played songs!
        item {
            Surface(
                color = SurfaceDarkCard,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(56.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search Last Session",
                        tint = SpotifyVibrantGreen,
                        modifier = Modifier.size(26.dp)
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (searchQuery.isEmpty()) {
                            Text(
                                text = "Search your played songs (Last Session)...",
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
                                .testTag("home_search_input")
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
            Spacer(modifier = Modifier.height(22.dp))
        }

        if (searchQuery.isNotBlank()) {
            // When searching in Home Search Box: ONLY show matching songs played in Last Session!
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Played Songs in Last Session (${matchingPlayedSongs.size})",
                        style = MaterialTheme.typography.headlineMedium.copy(
                            color = SpotifyVibrantGreen,
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                    )
                }
            }

            if (matchingPlayedSongs.isEmpty()) {
                item {
                    Surface(
                        color = SurfaceDarkCard,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.MusicNote,
                                contentDescription = null,
                                tint = SpotifyVibrantGreen,
                                modifier = Modifier.size(44.dp)
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "No played song matching \"$searchQuery\" in Last Session",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    color = PrimaryWhite,
                                    fontWeight = FontWeight.Bold
                                ),
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Home search filters songs you have already played. To discover & search any new song worldwide, open the Explore tab!",
                                style = MaterialTheme.typography.bodyMedium.copy(color = MutedGray),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            } else {
                itemsIndexed(
                    items = matchingPlayedSongs,
                    key = { idx, it -> "home_session_search_${idx}_${it.id}" }
                ) { _, song ->
                    SongListRow(
                        song = song,
                        isCurrentSong = currentSong?.id == song.id,
                        isPlaying = isPlaying,
                        onSongClick = { onSongClick(song, matchingPlayedSongs) },
                        onMoreClick = { onSongMoreClick(song) }
                    )
                }
            }
        } else {
            // Default Home View (when Home search box is empty):
            // 1. Last Session (Recently Played Songs)
            if (allSessionSongs.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(id = R.string.last_session),
                            style = MaterialTheme.typography.headlineMedium.copy(
                                color = SpotifyVibrantGreen,
                                fontWeight = FontWeight.Bold,
                                fontSize = 21.sp
                            )
                        )
                        Text(
                            text = "${allSessionSongs.size} played",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = MutedGray,
                                fontSize = 13.sp
                            )
                        )
                    }
                }
                val previewSessionSongs = allSessionSongs.take(6)
                itemsIndexed(
                    items = previewSessionSongs,
                    key = { idx, it -> "session_${idx}_${it.id}" }
                ) { _, song ->
                    SongListRow(
                        song = song,
                        isCurrentSong = currentSong?.id == song.id,
                        isPlaying = isPlaying,
                        onSongClick = { onSongClick(song, allSessionSongs) },
                        onMoreClick = { onSongMoreClick(song) }
                    )
                }
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            // 2. All Top & Recent Catalog Songs
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(id = R.string.recent_uploads),
                        style = MaterialTheme.typography.headlineMedium.copy(
                            color = SpotifyVibrantGreen,
                            fontWeight = FontWeight.Bold,
                            fontSize = 21.sp
                        )
                    )
                    if (songs.isNotEmpty()) {
                        Text(
                            text = "${songs.size} tracks",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = MutedGray,
                                fontSize = 13.sp
                            )
                        )
                    }
                }
            }

            if (isLoading && songs.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 56.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = SpotifyVibrantGreen)
                            Spacer(modifier = Modifier.height(14.dp))
                            Text(
                                text = "Loading your music library...",
                                style = MaterialTheme.typography.bodyMedium.copy(color = MutedGray)
                            )
                        }
                    }
                }
            } else {
                itemsIndexed(
                    items = songs,
                    key = { index, song -> "home_catalog_${index}_${song.id}" }
                ) { index, song ->
                    SongListRow(
                        song = song,
                        isCurrentSong = currentSong?.id == song.id,
                        isPlaying = isPlaying,
                        rankNumber = song.rank.takeIf { it in 1..99999 } ?: (index + 1),
                        onSongClick = { onSongClick(song, songs) },
                        onMoreClick = { onSongMoreClick(song) }
                    )
                }
            }
        }
    }
}
