package com.mus.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import com.mus.android.data.music.YouTubeMusicTrack
import com.mus.android.data.music.YouTubePlaylistSummary
import com.mus.android.ui.theme.MusColors

private val CatalogArtworkSize = 150.dp

/**
 * Apple Music–style square catalogue card for a YT Music song
 * (used in Home "Top Picks" shelves and similar).
 */
@Composable
fun CatalogSongCard(
    track: YouTubeMusicTrack,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(CatalogArtworkSize)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .size(CatalogArtworkSize)
                .clip(RoundedCornerShape(10.dp))
                .background(MusColors.SurfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = MusColors.OnBackgroundTertiary,
                modifier = Modifier.size(36.dp),
            )
            if (!track.artworkUrl.isNullOrBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(track.artworkUrl)
                        .size(360)
                        .crossfade(false)
                        .build(),
                    contentDescription = track.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Text(
            text = track.title,
            style = MaterialTheme.typography.labelLarge,
            color = MusColors.OnBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = track.artist,
            style = MaterialTheme.typography.bodySmall,
            color = MusColors.OnBackgroundSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** Apple Music–style square catalogue card for a YT Music playlist/album shelf item. */
@Composable
fun CatalogPlaylistCard(
    playlist: YouTubePlaylistSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(CatalogArtworkSize)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .size(CatalogArtworkSize)
                .clip(RoundedCornerShape(10.dp))
                .background(MusColors.SurfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = MusColors.OnBackgroundTertiary,
                modifier = Modifier.size(36.dp),
            )
            if (!playlist.artworkUrl.isNullOrBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(playlist.artworkUrl)
                        .size(360)
                        .crossfade(false)
                        .build(),
                    contentDescription = playlist.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Text(
            text = playlist.title,
            style = MaterialTheme.typography.labelLarge,
            color = MusColors.OnBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = listOfNotNull(playlist.author, playlist.trackCountText).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MusColors.OnBackgroundSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** Compact card for the user's own YT Music account playlists shelf. */
@Composable
fun YtPlaylistCard(
    playlist: YouTubePlaylistSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(120.dp)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(MusColors.SurfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = MusColors.OnBackgroundTertiary,
                modifier = Modifier.size(30.dp),
            )
            if (!playlist.artworkUrl.isNullOrBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(playlist.artworkUrl)
                        .size(300)
                        .crossfade(false)
                        .build(),
                    contentDescription = playlist.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Text(
            text = playlist.title,
            style = MaterialTheme.typography.labelMedium,
            color = MusColors.OnBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}
