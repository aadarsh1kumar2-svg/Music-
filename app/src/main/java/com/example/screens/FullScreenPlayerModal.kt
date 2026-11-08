package com.example.screens

import android.content.Intent
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MusicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.models.Song
import com.example.providers.RepeatModeState
import com.example.services.LrcLibLyricsService
import com.example.services.LyricsUiState
import com.example.ui.theme.DeepCharcoal
import com.example.ui.theme.DividerDark
import com.example.ui.theme.MutedGray
import com.example.ui.theme.PrimaryWhite
import com.example.ui.theme.PureBlack
import com.example.ui.theme.SpotifyDarkGreenPill
import com.example.ui.theme.SpotifyGreenBorder
import com.example.ui.theme.SpotifyVibrantGreen
import com.example.ui.theme.SurfaceDarkCard
import com.example.utils.TimeFormatUtils
import com.example.widgets.SongArtwork
import com.example.widgets.SongListRow

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun FullScreenPlayerModal(
    song: Song,
    queue: List<Song>,
    isPlaying: Boolean,
    isBuffering: Boolean,
    isFavorite: Boolean,
    currentPositionMs: Long,
    durationMs: Long,
    playbackSpeed: Float,
    isShuffleEnabled: Boolean,
    repeatMode: RepeatModeState,
    isUpNextOpen: Boolean,
    isLyricsOpen: Boolean,
    onCollapse: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSeekRelativeSeconds: (Int) -> Unit = {},
    onCycleSpeed: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenSongOptions: (Song) -> Unit,
    onSetUpNextOpen: (Boolean) -> Unit,
    onSetLyricsOpen: (Boolean) -> Unit,
    onSelectQueueSong: (Song) -> Unit,
    onAttachBackstagePlayer: (ViewGroup) -> Unit = {}
) {
    BackHandler {
        if (isLyricsOpen) {
            onSetLyricsOpen(false)
        } else {
            onCollapse()
        }
    }

    val context = LocalContext.current
    var isDraggingSlider by remember { mutableStateOf(false) }
    var sliderDragPosition by remember { mutableFloatStateOf(0f) }

    // LRCLIB Lyrics State
    var lyricsState by remember(song.id) { mutableStateOf<LyricsUiState>(LyricsUiState.Idle) }
    var retryNonce by remember(song.id) { mutableIntStateOf(0) }

    // Automatically fetch lyrics when song changes or when Lyrics panel is opened / retried
    LaunchedEffect(song.id, song.title, song.artist, isLyricsOpen, retryNonce) {
        if (isLyricsOpen || lyricsState is LyricsUiState.Idle) {
            if (lyricsState !is LyricsUiState.Success) {
                lyricsState = LyricsUiState.Loading
                lyricsState = LrcLibLyricsService.fetchLyrics(
                    songId = song.id,
                    rawTitle = song.title,
                    rawArtist = song.artist,
                    durationMs = durationMs
                )
            }
        }
    }

    val safeDuration = durationMs.coerceAtLeast(1L)
    val currentSliderFraction = if (isDraggingSlider) {
        sliderDragPosition
    } else if (durationMs > 0L) {
        (currentPositionMs.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        DeepCharcoal,
                        PureBlack,
                        PureBlack
                    )
                )
            )
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .testTag("full_screen_player_modal")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top Bar: Down chevron (collapse), lyrics icon, share icon, and 3-lines menu on the right corner
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onCollapse,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("full_player_collapse_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Collapse Player",
                        tint = PrimaryWhite,
                        modifier = Modifier.size(32.dp)
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    IconButton(
                        onClick = { onSetLyricsOpen(!isLyricsOpen) },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("full_player_lyrics_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lyrics,
                            contentDescription = "Lyrics",
                            tint = if (isLyricsOpen) SpotifyVibrantGreen else PrimaryWhite,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    IconButton(
                        onClick = {
                            val shareUrl = if (song.cleanVideoId.isNotBlank()) {
                                "https://www.youtube.com/watch?v=${song.cleanVideoId}"
                            } else {
                                song.title
                            }
                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(
                                    Intent.EXTRA_TEXT,
                                    "Listen to ${song.title} by ${song.artist} on AADIFY: $shareUrl"
                                )
                            }
                            context.startActivity(Intent.createChooser(sendIntent, "Share Song"))
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("full_player_share_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share Song",
                            tint = PrimaryWhite,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    // Top-Right 3-Lines Menu Button (opens bottom sheet with Add to Playlist & options)
                    IconButton(
                        onClick = { onOpenSongOptions(song) },
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(SurfaceDarkCard.copy(alpha = 0.75f))
                            .testTag("full_player_more_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Menu,
                            contentDescription = "Player Menu (Add to Playlist)",
                            tint = PrimaryWhite,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Center: Large high-resolution square album banner OR Synced LRCLIB Lyrics Overlay
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .aspectRatio(1f)
                    .shadow(
                        elevation = 24.dp,
                        shape = RoundedCornerShape(18.dp),
                        clip = false
                    ),
                contentAlignment = Alignment.Center
            ) {
                // Album Banner Poster
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(18.dp))
                        .background(DeepCharcoal)
                ) {
                    SongArtwork(
                        imageUrl = song.highResBannerUrl,
                        contentDescription = song.title,
                        modifier = Modifier.fillMaxSize(),
                        cornerRadius = 18.dp
                    )
                }

                // Beautiful LRCLIB Synced & Plain Lyrics Overlay
                if (isLyricsOpen) {
                    LrcLibLyricsOverlayPanel(
                        song = song,
                        lyricsState = lyricsState,
                        currentPositionMs = currentPositionMs,
                        onSeekTo = onSeekTo,
                        onRetry = {
                            lyricsState = LyricsUiState.Loading
                            retryNonce++
                        },
                        onClose = { onSetLyricsOpen(false) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Song Details: Auto-scrolling marquee title, artist name
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.headlineLarge.copy(
                        color = PrimaryWhite,
                        fontWeight = FontWeight.Bold,
                        fontSize = 24.sp
                    ),
                    maxLines = 1,
                    modifier = Modifier
                        .fillMaxWidth()
                        .basicMarquee()
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = song.artist,
                    style = MaterialTheme.typography.titleMedium.copy(
                        color = MutedGray,
                        fontWeight = FontWeight.Normal,
                        fontSize = 16.sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Lyrics Pill Button (left) + 1.0x Speed Toggle Chip (right) above the seekbar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = if (isLyricsOpen) SpotifyDarkGreenPill else SurfaceDarkCard,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .border(
                            width = 1.dp,
                            color = if (isLyricsOpen) SpotifyVibrantGreen else DividerDark,
                            shape = RoundedCornerShape(20.dp)
                        )
                        .clickable { onSetLyricsOpen(!isLyricsOpen) }
                        .testTag("full_player_lyrics_pill_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lyrics,
                            contentDescription = "Lyrics",
                            tint = if (isLyricsOpen) SpotifyVibrantGreen else PrimaryWhite,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Lyrics",
                            style = MaterialTheme.typography.labelLarge.copy(
                                color = if (isLyricsOpen) SpotifyVibrantGreen else PrimaryWhite,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.5.sp
                            )
                        )
                    }
                }

                Surface(
                    color = SurfaceDarkCard,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .clickable(onClick = onCycleSpeed)
                        .testTag("full_player_speed_chip")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Speed,
                            contentDescription = "Playback Speed",
                            tint = MutedGray,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "${playbackSpeed}x",
                            style = MaterialTheme.typography.labelLarge.copy(
                                color = MutedGray,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp
                            )
                        )
                    }
                }
            }

            // Custom Draggable Green Slider with Elapsed Time (left) and Total Duration (right)
            Slider(
                value = currentSliderFraction,
                onValueChange = { fraction ->
                    isDraggingSlider = true
                    sliderDragPosition = fraction
                },
                onValueChangeFinished = {
                    isDraggingSlider = false
                    if (durationMs > 0L) {
                        onSeekTo((sliderDragPosition * durationMs).toLong())
                    }
                },
                colors = SliderDefaults.colors(
                    thumbColor = PrimaryWhite,
                    activeTrackColor = SpotifyVibrantGreen,
                    inactiveTrackColor = SurfaceDarkCard
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("full_player_seekbar")
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val displayPos = if (isDraggingSlider && durationMs > 0L) {
                    (sliderDragPosition * durationMs).toLong()
                } else {
                    currentPositionMs
                }
                Text(
                    text = TimeFormatUtils.formatDurationMs(displayPos),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = PrimaryWhite,
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp
                    )
                )
                Text(
                    text = TimeFormatUtils.formatDurationMs(durationMs),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = PrimaryWhite,
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp
                    )
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Controls: Shuffle toggle, Previous track, Big white circular Play/Pause button with black icon, Next track, Repeat toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onToggleShuffle,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("full_player_shuffle_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Shuffle,
                        contentDescription = "Shuffle",
                        tint = if (isShuffleEnabled) SpotifyVibrantGreen else MutedGray,
                        modifier = Modifier.size(26.dp)
                    )
                }

                IconButton(
                    onClick = onSkipPrevious,
                    modifier = Modifier
                        .size(56.dp)
                        .testTag("full_player_prev_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipPrevious,
                        contentDescription = "Previous Track",
                        tint = PrimaryWhite,
                        modifier = Modifier.size(36.dp)
                    )
                }

                // Big white circular Play/Pause button with black icon
                Box(
                    modifier = Modifier
                        .size(74.dp)
                        .clip(CircleShape)
                        .background(PrimaryWhite)
                        .clickable(onClick = onTogglePlayPause)
                        .testTag("full_player_play_pause_button"),
                    contentAlignment = Alignment.Center
                ) {
                    if (isBuffering) {
                        CircularProgressIndicator(
                            color = PureBlack,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(32.dp)
                        )
                    } else {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = PureBlack,
                            modifier = Modifier.size(38.dp)
                        )
                    }
                }

                IconButton(
                    onClick = onSkipNext,
                    modifier = Modifier
                        .size(56.dp)
                        .testTag("full_player_next_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "Next Track",
                        tint = PrimaryWhite,
                        modifier = Modifier.size(36.dp)
                    )
                }

                IconButton(
                    onClick = onCycleRepeat,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("full_player_repeat_button")
                ) {
                    Icon(
                        imageVector = if (repeatMode == RepeatModeState.ONE) {
                            Icons.Default.RepeatOne
                        } else {
                            Icons.Default.Repeat
                        },
                        contentDescription = "Repeat",
                        tint = if (repeatMode != RepeatModeState.OFF) SpotifyVibrantGreen else MutedGray,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Quick Actions Row: Heart / Favorite on left, 10s Rewind & 10s Fast-Forward on right
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onToggleFavorite,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("full_player_favorite_button")
                ) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "Favorite",
                        tint = if (isFavorite) SpotifyVibrantGreen else PrimaryWhite,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(
                        onClick = { onSeekRelativeSeconds(-10) },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("full_player_rewind_10s_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Replay10,
                            contentDescription = "Rewind 10 seconds",
                            tint = MutedGray,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    IconButton(
                        onClick = { onSeekRelativeSeconds(10) },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("full_player_forward_10s_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Forward10,
                            contentDescription = "Fast Forward 10 seconds",
                            tint = MutedGray,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.weight(0.2f))

            // Bottom: Draggable / Tappable "Up Next" Queue Handle
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .clickable { onSetUpNextOpen(true) }
                    .padding(vertical = 10.dp)
                    .testTag("full_player_up_next_handle"),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .width(42.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MutedGray.copy(alpha = 0.6f))
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(id = R.string.up_next),
                    style = MaterialTheme.typography.titleMedium.copy(
                        color = PrimaryWhite,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                )
            }
        }

        // Up Next Queue Modal Bottom Sheet
        if (isUpNextOpen) {
            val queueSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
            ModalBottomSheet(
                onDismissRequest = { onSetUpNextOpen(false) },
                sheetState = queueSheetState,
                containerColor = DeepCharcoal
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 28.dp)
                ) {
                    Text(
                        text = "Up Next (${queue.size} tracks)",
                        style = MaterialTheme.typography.headlineMedium.copy(
                            color = PrimaryWhite,
                            fontWeight = FontWeight.Bold
                        ),
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                    )
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(360.dp),
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        items(items = queue, key = { "upnext_${it.id}" }) { item ->
                            SongListRow(
                                song = item,
                                isCurrentSong = item.id == song.id,
                                isPlaying = isPlaying,
                                onSongClick = {
                                    onSelectQueueSong(item)
                                    onSetUpNextOpen(false)
                                },
                                onMoreClick = {
                                    onOpenSongOptions(item)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LrcLibLyricsOverlayPanel(
    song: Song,
    lyricsState: LyricsUiState,
    currentPositionMs: Long,
    onSeekTo: (Long) -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(18.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        PureBlack.copy(alpha = 0.88f),
                        DeepCharcoal.copy(alpha = 0.94f),
                        PureBlack.copy(alpha = 0.96f)
                    )
                )
            )
            .border(
                width = 1.dp,
                color = SpotifyGreenBorder.copy(alpha = 0.7f),
                shape = RoundedCornerShape(18.dp)
            )
            .testTag("lrclib_lyrics_panel")
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Lyrics Panel Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        color = SpotifyDarkGreenPill,
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.border(
                            width = 1.dp,
                            color = SpotifyVibrantGreen.copy(alpha = 0.7f),
                            shape = RoundedCornerShape(50)
                        )
                    ) {
                        val badgeText = when (lyricsState) {
                            is LyricsUiState.Success -> if (lyricsState.isSynced) "● SYNCED LYRICS" else "LYRICS"
                            is LyricsUiState.Loading -> "LOADING LYRICS..."
                            else -> "LYRICS"
                        }
                        Text(
                            text = badgeText,
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = SpotifyVibrantGreen,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 10.sp,
                                letterSpacing = 0.8.sp
                            ),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }

                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close Lyrics",
                        tint = MutedGray,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            HorizontalDividerSimple()

            // Main Body: Loading / Synced Lyrics / Plain Lyrics / No Lyrics Found
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                when (lyricsState) {
                    is LyricsUiState.Idle,
                    is LyricsUiState.Loading -> {
                        LyricsLoadingAnimation(songTitle = song.title)
                    }

                    is LyricsUiState.NotFound -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp)
                                .testTag("no_lyrics_found_view")
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(CircleShape)
                                    .background(SurfaceDarkCard),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MusicOff,
                                    contentDescription = "No Lyrics Found",
                                    tint = SpotifyVibrantGreen,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "No Lyrics Found",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    color = PrimaryWhite,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 19.sp
                                ),
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "${song.title} • ${song.artist}",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = MutedGray
                                ),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                            Button(
                                onClick = onRetry,
                                shape = RoundedCornerShape(50),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = SpotifyVibrantGreen,
                                    contentColor = PureBlack
                                ),
                                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Retry",
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Retry",
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        color = PureBlack,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                            }
                        }
                    }

                    is LyricsUiState.Success -> {
                        if (lyricsState.isSynced && lyricsState.syncedLines.isNotEmpty()) {
                            SyncedLyricsListView(
                                syncedLines = lyricsState.syncedLines,
                                currentPositionMs = currentPositionMs,
                                onSeekTo = onSeekTo
                            )
                        } else {
                            PlainLyricsListView(
                                plainLines = lyricsState.plainLines
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SyncedLyricsListView(
    syncedLines: List<com.example.services.SyncedLyricLine>,
    currentPositionMs: Long,
    onSeekTo: (Long) -> Unit
) {
    val listState = rememberLazyListState()

    val activeIndex by remember(syncedLines, currentPositionMs) {
        derivedStateOf {
            val idx = syncedLines.indexOfLast { it.timeMs <= currentPositionMs + 150L }
            if (idx < 0) 0 else idx
        }
    }

    // Auto-scroll smoothly to keep the active lyric line centered
    LaunchedEffect(activeIndex, syncedLines.size) {
        if (syncedLines.isNotEmpty() && activeIndex in syncedLines.indices) {
            try {
                listState.animateScrollToItem(
                    index = activeIndex,
                    scrollOffset = -160
                )
            } catch (_: Exception) {
            }
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .testTag("synced_lyrics_list"),
        contentPadding = PaddingValues(top = 64.dp, bottom = 96.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        itemsIndexed(
            items = syncedLines,
            key = { index, line -> "sync_${index}_${line.timeMs}" }
        ) { index, line ->
            val isCurrent = index == activeIndex
            val isPast = index < activeIndex

            val targetColor by animateColorAsState(
                targetValue = when {
                    isCurrent -> SpotifyVibrantGreen
                    isPast -> MutedGray.copy(alpha = 0.48f)
                    else -> PrimaryWhite.copy(alpha = 0.82f)
                },
                animationSpec = tween(durationMillis = 260),
                label = "lyricColor"
            )

            val targetScale by animateFloatAsState(
                targetValue = if (isCurrent) 1.05f else 0.96f,
                animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
                label = "lyricScale"
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .scale(targetScale)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (isCurrent) SpotifyDarkGreenPill.copy(alpha = 0.72f) else Color.Transparent
                    )
                    .then(
                        if (isCurrent) {
                            Modifier.border(
                                width = 1.dp,
                                color = SpotifyGreenBorder.copy(alpha = 0.85f),
                                shape = RoundedCornerShape(12.dp)
                            )
                        } else {
                            Modifier
                        }
                    )
                    .clickable {
                        onSeekTo(line.timeMs)
                    }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = line.text,
                    style = MaterialTheme.typography.titleMedium.copy(
                        color = targetColor,
                        fontWeight = if (isCurrent) FontWeight.ExtraBold else FontWeight.SemiBold,
                        fontSize = if (isCurrent) 19.sp else 15.5.sp,
                        lineHeight = if (isCurrent) 25.sp else 21.sp
                    ),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun PlainLyricsListView(
    plainLines: List<String>
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("plain_lyrics_list"),
        contentPadding = PaddingValues(vertical = 24.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        itemsIndexed(
            items = plainLines,
            key = { index, _ -> "plain_$index" }
        ) { _, line ->
            Text(
                text = line,
                style = MaterialTheme.typography.bodyLarge.copy(
                    color = PrimaryWhite.copy(alpha = 0.92f),
                    fontWeight = FontWeight.Medium,
                    fontSize = 16.sp,
                    lineHeight = 24.sp
                ),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun LyricsLoadingAnimation(songTitle: String) {
    val infiniteTransition = rememberInfiniteTransition(label = "lyricsLoading")
    val barScales = listOf(0, 140, 280, 420, 560).map { delayMs ->
        infiniteTransition.animateFloat(
            initialValue = 0.3f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 520, delayMillis = delayMs, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "bar_$delayMs"
        )
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp)
            .testTag("lyrics_loading_animation")
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.height(44.dp)
        ) {
            barScales.forEach { scaleState ->
                Box(
                    modifier = Modifier
                        .width(6.dp)
                        .height((40f * scaleState.value).dp)
                        .clip(RoundedCornerShape(50))
                        .background(SpotifyVibrantGreen)
                )
            }
        }
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = "Fetching Lyrics...",
            style = MaterialTheme.typography.titleMedium.copy(
                color = SpotifyVibrantGreen,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            ),
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = songTitle,
            style = MaterialTheme.typography.bodySmall.copy(
                color = MutedGray
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun HorizontalDividerSimple() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(DividerDark.copy(alpha = 0.7f))
    )
}
