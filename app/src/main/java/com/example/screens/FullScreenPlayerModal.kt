package com.example.screens

import android.content.Intent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.R
import com.example.models.Song
import com.example.providers.RepeatModeState
import com.example.ui.theme.DeepCharcoal
import com.example.ui.theme.MutedGray
import com.example.ui.theme.PrimaryWhite
import com.example.ui.theme.PureBlack
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
    BackHandler(onBack = onCollapse)

    val context = LocalContext.current
    var isDraggingSlider by remember { mutableStateOf(false) }
    var sliderDragPosition by remember { mutableFloatStateOf(0f) }

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
                            contentDescription = "Lyrics / Info",
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

            // Center: Large high-resolution square album banner with rounded corners and subtle drop shadow
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
                // Hidden Backstage YouTube WebView directly layered behind the album banner container (exact same bounds)
                AndroidView(
                    factory = { ctx ->
                        FrameLayout(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            onAttachBackstagePlayer(this)
                        }
                    },
                    update = { container ->
                        onAttachBackstagePlayer(container)
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(18.dp))
                        .testTag("hidden_backstage_youtube_webview")
                )

                // Existing Album Banner Poster directly on top (opaque background completely covering the WebView)
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

                if (isLyricsOpen) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(18.dp))
                            .background(PureBlack.copy(alpha = 0.85f))
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = song.title,
                                style = MaterialTheme.typography.headlineMedium.copy(
                                    color = SpotifyVibrantGreen,
                                    fontWeight = FontWeight.Bold
                                ),
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Artist: ${song.artist}",
                                style = MaterialTheme.typography.bodyLarge.copy(color = PrimaryWhite),
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                            Text(
                                text = "AADIFY • Your Music Universe",
                                style = MaterialTheme.typography.bodyMedium.copy(color = MutedGray),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

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

            // 1.0x Speed Toggle Chip aligned right above the seekbar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
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
