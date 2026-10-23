package com.example.widgets

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.models.PlaylistEntity
import com.example.models.Song
import com.example.ui.theme.DeepCharcoal
import com.example.ui.theme.DividerDark
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.MutedGray
import com.example.ui.theme.PrimaryWhite
import com.example.ui.theme.PureBlack
import com.example.ui.theme.SpotifyVibrantGreen
import com.example.ui.theme.SurfaceDarkCard
import com.example.utils.TimeFormatUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongOptionsBottomSheet(
    song: Song,
    sleepTimerRemainingSeconds: Long?,
    onDismiss: () -> Unit,
    onViewAlbum: (Song) -> Unit,
    onAddToPlaylist: (Song) -> Unit,
    onOpenSleepTimer: () -> Unit,
    onDownloadOffline: (Song) -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = DeepCharcoal,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 8.dp)
                    .width(44.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MutedGray.copy(alpha = 0.45f))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 28.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SongArtwork(
                    imageUrl = song.resolvedBannerUrl,
                    contentDescription = song.title,
                    modifier = Modifier.size(64.dp),
                    cornerRadius = 12.dp
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = song.title,
                        style = MaterialTheme.typography.titleLarge.copy(
                            color = PrimaryWhite,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = song.artist,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = MutedGray,
                            fontSize = 15.sp
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            HorizontalDivider(
                color = DividerDark,
                thickness = 1.dp,
                modifier = Modifier.padding(vertical = 6.dp)
            )

            // 1. View Album
            OptionSheetRow(
                icon = Icons.Default.Album,
                label = stringResource(id = R.string.option_view_album),
                showChevron = true,
                testTag = "sheet_option_view_album",
                onClick = {
                    onDismiss()
                    onViewAlbum(song)
                }
            )

            // 2. Add to Playlist
            OptionSheetRow(
                icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                label = stringResource(id = R.string.option_add_to_playlist),
                showChevron = false,
                testTag = "sheet_option_add_playlist",
                onClick = {
                    onDismiss()
                    onAddToPlaylist(song)
                }
            )

            // 3. Sleep Timer
            val sleepLabel = if (sleepTimerRemainingSeconds != null && sleepTimerRemainingSeconds > 0) {
                "Sleep Timer (${TimeFormatUtils.formatDurationMs(sleepTimerRemainingSeconds * 1000L)})"
            } else {
                stringResource(id = R.string.option_sleep_timer)
            }
            OptionSheetRow(
                icon = Icons.Default.Timer,
                label = sleepLabel,
                showChevron = false,
                testTag = "sheet_option_sleep_timer",
                onClick = {
                    onDismiss()
                    onOpenSleepTimer()
                }
            )

            // 4. Watch Video
            OptionSheetRow(
                icon = Icons.Default.SmartDisplay,
                label = stringResource(id = R.string.option_watch_video),
                showChevron = false,
                testTag = "sheet_option_watch_video",
                onClick = {
                    onDismiss()
                    val vid = song.cleanVideoId
                    val url = if (vid.startsWith("http")) vid else "https://www.youtube.com/watch?v=$vid"
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    } catch (_: Exception) {
                    }
                }
            )

            // Download Offline (My Music)
            OptionSheetRow(
                icon = Icons.Default.Download,
                label = stringResource(id = R.string.option_download_offline),
                showChevron = false,
                testTag = "sheet_option_download",
                onClick = {
                    onDismiss()
                    onDownloadOffline(song)
                }
            )
        }
    }
}

@Composable
private fun OptionSheetRow(
    icon: ImageVector,
    label: String,
    showChevron: Boolean,
    testTag: String,
    tint: androidx.compose.ui.graphics.Color = PrimaryWhite,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (tint == PrimaryWhite) MutedGray else tint,
            modifier = Modifier.size(26.dp)
        )
        Spacer(modifier = Modifier.width(20.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium.copy(
                color = tint,
                fontWeight = FontWeight.Medium,
                fontSize = 17.sp
            ),
            modifier = Modifier.weight(1f)
        )
        if (showChevron) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MutedGray,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
fun SleepTimerDialog(
    remainingSeconds: Long?,
    onSelectMinutes: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(5, 15, 30, 45, 60)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDarkCard,
        title = {
            Text(
                text = "Sleep Timer",
                style = MaterialTheme.typography.headlineMedium.copy(color = PrimaryWhite)
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (remainingSeconds != null && remainingSeconds > 0) {
                    Text(
                        text = "Active timer: ${TimeFormatUtils.formatDurationMs(remainingSeconds * 1000L)} remaining",
                        style = MaterialTheme.typography.bodyMedium.copy(color = SpotifyVibrantGreen)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
                options.forEach { mins ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(DeepCharcoal)
                            .clickable {
                                onSelectMinutes(mins)
                                onDismiss()
                            }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "$mins minutes",
                            style = MaterialTheme.typography.bodyLarge.copy(color = PrimaryWhite)
                        )
                        Icon(
                            imageVector = Icons.Default.Timer,
                            contentDescription = null,
                            tint = SpotifyVibrantGreen,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                if (remainingSeconds != null) {
                    OutlinedButton(
                        onClick = {
                            onSelectMinutes(0)
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = ErrorRed)
                    ) {
                        Text("Turn Off Timer")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = MutedGray)
            }
        }
    )
}

@Composable
fun AddToPlaylistDialog(
    song: Song,
    playlists: List<PlaylistEntity>,
    onCreateAndAdd: (String, Song) -> Unit,
    onAddToExisting: (PlaylistEntity, Song) -> Unit,
    onDismiss: () -> Unit
) {
    var newPlaylistName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDarkCard,
        title = {
            Text(
                text = "Add to Playlist",
                style = MaterialTheme.typography.headlineMedium.copy(color = PrimaryWhite)
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.bodyMedium.copy(color = SpotifyVibrantGreen),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = newPlaylistName,
                        onValueChange = { newPlaylistName = it },
                        placeholder = { Text("New playlist name", color = MutedGray) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = SpotifyVibrantGreen,
                            unfocusedBorderColor = DividerDark,
                            focusedTextColor = PrimaryWhite,
                            unfocusedTextColor = PrimaryWhite
                        )
                    )
                    Button(
                        onClick = {
                            if (newPlaylistName.isNotBlank()) {
                                onCreateAndAdd(newPlaylistName, song)
                                onDismiss()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SpotifyVibrantGreen,
                            contentColor = PureBlack
                        )
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Create")
                    }
                }

                if (playlists.isNotEmpty()) {
                    Text(
                        text = "Existing Playlists",
                        style = MaterialTheme.typography.labelLarge.copy(color = MutedGray)
                    )
                    LazyColumn(
                        modifier = Modifier.height(180.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(playlists, key = { it.playlistId }) { playlist ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(DeepCharcoal)
                                    .clickable {
                                        onAddToExisting(playlist, song)
                                        onDismiss()
                                    }
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.PlaylistAdd,
                                    contentDescription = null,
                                    tint = SpotifyVibrantGreen
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = playlist.name,
                                    style = MaterialTheme.typography.bodyLarge.copy(color = PrimaryWhite)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = MutedGray)
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminUploadSongBottomSheet(
    onDismiss: () -> Unit,
    onUploadSong: (title: String, artist: String, videoId: String, bannerUrl: String) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var artist by remember { mutableStateOf("") }
    var videoId by remember { mutableStateOf("") }
    var bannerUrl by remember { mutableStateOf("") }
    var validationError by remember { mutableStateOf<String?>(null) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = DeepCharcoal
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.CloudUpload,
                    contentDescription = null,
                    tint = SpotifyVibrantGreen,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Add Track to Library",
                        style = MaterialTheme.typography.headlineMedium.copy(
                            color = PrimaryWhite,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Text(
                        text = "Syncs directly to your cloud catalog in real-time",
                        style = MaterialTheme.typography.bodyMedium.copy(color = MutedGray)
                    )
                }
            }

            OutlinedTextField(
                value = title,
                onValueChange = {
                    title = it
                    validationError = null
                },
                label = { Text("Song Title *") },
                placeholder = { Text("e.g., Kesariya") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("admin_input_title"),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = SpotifyVibrantGreen,
                    unfocusedBorderColor = DividerDark,
                    focusedLabelColor = SpotifyVibrantGreen,
                    unfocusedLabelColor = MutedGray,
                    focusedTextColor = PrimaryWhite,
                    unfocusedTextColor = PrimaryWhite
                )
            )

            OutlinedTextField(
                value = artist,
                onValueChange = {
                    artist = it
                    validationError = null
                },
                label = { Text("Artist Name *") },
                placeholder = { Text("e.g., Arijit Singh") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("admin_input_artist"),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = SpotifyVibrantGreen,
                    unfocusedBorderColor = DividerDark,
                    focusedLabelColor = SpotifyVibrantGreen,
                    unfocusedLabelColor = MutedGray,
                    focusedTextColor = PrimaryWhite,
                    unfocusedTextColor = PrimaryWhite
                )
            )

            OutlinedTextField(
                value = videoId,
                onValueChange = {
                    videoId = it
                    validationError = null
                },
                label = { Text("YouTube Video ID or URL *") },
                placeholder = { Text("e.g., BddP6PYo2gs or https://youtu.be/...") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("admin_input_videoid"),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = SpotifyVibrantGreen,
                    unfocusedBorderColor = DividerDark,
                    focusedLabelColor = SpotifyVibrantGreen,
                    unfocusedLabelColor = MutedGray,
                    focusedTextColor = PrimaryWhite,
                    unfocusedTextColor = PrimaryWhite
                )
            )

            OutlinedTextField(
                value = bannerUrl,
                onValueChange = { bannerUrl = it },
                label = { Text("Album Art / Banner URL (Optional)") },
                placeholder = { Text("Auto-uses YouTube HD thumbnail if blank") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("admin_input_banner"),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = SpotifyVibrantGreen,
                    unfocusedBorderColor = DividerDark,
                    focusedLabelColor = SpotifyVibrantGreen,
                    unfocusedLabelColor = MutedGray,
                    focusedTextColor = PrimaryWhite,
                    unfocusedTextColor = PrimaryWhite
                )
            )

            if (validationError != null) {
                Text(
                    text = validationError!!,
                    color = ErrorRed,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Button(
                onClick = {
                    if (title.isBlank() || videoId.isBlank()) {
                        validationError = "Please enter both Song Title and YouTube Video ID / URL."
                    } else {
                        onUploadSong(title, artist, videoId, bannerUrl)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("admin_submit_song_button"),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SpotifyVibrantGreen,
                    contentColor = PureBlack
                )
            ) {
                Text(
                    text = "Publish Track",
                    style = MaterialTheme.typography.titleMedium.copy(
                        color = PureBlack,
                        fontWeight = FontWeight.Bold
                    )
                )
            }
        }
    }
}
