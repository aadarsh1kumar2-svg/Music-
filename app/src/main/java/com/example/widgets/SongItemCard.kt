package com.example.widgets

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.example.models.Song
import com.example.ui.theme.DeepCharcoal
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.MutedGray
import com.example.ui.theme.PrimaryWhite
import com.example.ui.theme.SpotifyVibrantGreen
import com.example.ui.theme.SurfaceDarkCard

@Composable
fun SongArtwork(
    imageUrl: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 12.dp,
    fallbackVideoId: String = ""
) {
    val shape = RoundedCornerShape(cornerRadius)
    val candidateUrls = remember(imageUrl, fallbackVideoId) {
        val list = mutableListOf<String>()
        val trimmed = imageUrl.trim()
        if (trimmed.isNotEmpty()) {
            // If someone pasted a YouTube watch/short URL into bannerUrl, convert it directly to thumbnail
            if (trimmed.contains("youtube.com/watch", ignoreCase = true) ||
                trimmed.contains("youtu.be/", ignoreCase = true) ||
                trimmed.contains("youtube.com/shorts/", ignoreCase = true)
            ) {
                val extracted = Song.extractYoutubeVideoId(trimmed)
                if (extracted.isNotBlank()) {
                    list.add("https://i.ytimg.com/vi/$extracted/hqdefault.jpg")
                    list.add("https://img.youtube.com/vi/$extracted/mqdefault.jpg")
                }
            } else {
                list.add(trimmed)
            }
        }
        val vid = fallbackVideoId.trim().ifBlank {
            val match = Regex("""/vi/([a-zA-Z0-9_-]{11})/""").find(trimmed)
            match?.groupValues?.getOrNull(1).orEmpty()
        }
        if (vid.isNotBlank()) {
            val hq1 = "https://i.ytimg.com/vi/$vid/hqdefault.jpg"
            val hq2 = "https://img.youtube.com/vi/$vid/hqdefault.jpg"
            val mq = "https://i.ytimg.com/vi/$vid/mqdefault.jpg"
            if (hq1 !in list) list.add(hq1)
            if (hq2 !in list) list.add(hq2)
            if (mq !in list) list.add(mq)
        }
        list
    }

    var attemptIndex by remember(candidateUrls) { mutableIntStateOf(0) }
    val activeUrl = candidateUrls.getOrNull(attemptIndex).orEmpty()

    val painter = rememberAsyncImagePainter(
        model = ImageRequest.Builder(LocalContext.current)
            .data(activeUrl.ifBlank { null })
            .crossfade(true)
            .build(),
        onError = {
            if (attemptIndex + 1 < candidateUrls.size) {
                attemptIndex += 1
            }
        }
    )

    val state = painter.state

    Box(
        modifier = modifier
            .clip(shape)
            .background(
                Brush.linearGradient(
                    colors = listOf(SurfaceDarkCard, DeepCharcoal)
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        if (state !is AsyncImagePainter.State.Success) {
            Icon(
                imageVector = Icons.Default.MusicNote,
                contentDescription = null,
                tint = if (state is AsyncImagePainter.State.Error) {
                    SpotifyVibrantGreen.copy(alpha = 0.75f)
                } else {
                    MutedGray.copy(alpha = 0.5f)
                },
                modifier = Modifier.size(28.dp)
            )
        }
        if (activeUrl.isNotBlank()) {
            Image(
                painter = painter,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
fun SongListRow(
    song: Song,
    isCurrentSong: Boolean,
    isPlaying: Boolean,
    onSongClick: () -> Unit,
    onMoreClick: () -> Unit,
    modifier: Modifier = Modifier,
    rankNumber: Int? = null,
    subtitlePrefix: String = "Song • ",
    onDeleteClick: (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onSongClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag("song_row_${song.id}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(contentAlignment = Alignment.Center) {
            SongArtwork(
                imageUrl = song.resolvedBannerUrl,
                contentDescription = song.title,
                modifier = Modifier.size(60.dp),
                cornerRadius = 12.dp,
                fallbackVideoId = song.cleanVideoId
            )
            if (isCurrentSong && isPlaying) {
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(DeepCharcoal.copy(alpha = 0.65f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.GraphicEq,
                        contentDescription = "Now Playing",
                        tint = SpotifyVibrantGreen,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            val displayTitle = if (rankNumber != null) {
                "$rankNumber. ${song.title}"
            } else {
                song.title
            }
            Text(
                text = displayTitle,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = 17.sp,
                    fontWeight = if (rankNumber != null || isCurrentSong) FontWeight.Bold else FontWeight.SemiBold,
                    color = if (isCurrentSong) SpotifyVibrantGreen else PrimaryWhite
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "$subtitlePrefix${song.artist}",
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 14.sp,
                    color = MutedGray
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (onDeleteClick != null) {
            IconButton(
                onClick = onDeleteClick,
                modifier = Modifier
                    .size(44.dp)
                    .testTag("song_delete_${song.id}")
            ) {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = "Delete Song from Playlist",
                    tint = ErrorRed
                )
            }
        }

        IconButton(
            onClick = onMoreClick,
            modifier = Modifier
                .size(48.dp)
                .testTag("song_more_${song.id}")
        ) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = "Song Options",
                tint = PrimaryWhite
            )
        }
    }
}
