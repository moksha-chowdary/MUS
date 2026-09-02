package com.mus.android.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.mus.android.data.model.Track
import com.mus.android.ui.theme.MusColors
import com.mus.android.ui.theme.Spacing

@Composable
fun TrackRow(
    track: Track,
    onClick: () -> Unit,
    onFavoriteToggle: (() -> Unit)? = null,
    onMoreClick: (() -> Unit)? = null,
    showArtwork: Boolean = true,
    trackNumber: Int? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = Spacing.base, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Track number or artwork
        if (trackNumber != null) {
            Text(
                text = trackNumber.toString(),
                style = MaterialTheme.typography.bodyMedium,
                color = MusColors.OnBackgroundTertiary,
                modifier = Modifier.width(28.dp),
            )
        }

        if (showArtwork) {
            AsyncImage(
                model = track.artworkUri,
                contentDescription = track.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(6.dp))
            )
            Spacer(Modifier.width(Spacing.md))
        }

        Column(
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.titleSmall,
                color = MusColors.OnBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${track.artist} • ${track.language} • ${formatDuration(track.duration)}",
                style = MaterialTheme.typography.bodySmall,
                color = MusColors.OnBackgroundSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Favorite
        if (onFavoriteToggle != null) {
            IconButton(onClick = onFavoriteToggle, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = if (track.isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    contentDescription = "Favorite",
                    tint = if (track.isFavorite) MusColors.Favorite else MusColors.OnBackgroundTertiary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        // More
        if (onMoreClick != null) {
            IconButton(onClick = onMoreClick, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Rounded.MoreVert,
                    contentDescription = "More",
                    tint = MusColors.OnBackgroundTertiary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
fun AlbumCard(
    title: String,
    artist: String,
    artworkUri: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(160.dp)
            .clickable { onClick() }
            .padding(Spacing.xs),
    ) {
        AsyncImage(
            model = artworkUri,
            contentDescription = title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(160.dp)
                .clip(RoundedCornerShape(8.dp))
        )
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MusColors.OnBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = artist,
            style = MaterialTheme.typography.bodySmall,
            color = MusColors.OnBackgroundSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun ArtistCard(
    name: String,
    artworkUri: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(120.dp)
            .clickable { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AsyncImage(
            model = artworkUri,
            contentDescription = name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(120.dp)
                .clip(RoundedCornerShape(60.dp)) // circular
        )
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = name,
            style = MaterialTheme.typography.labelLarge,
            color = MusColors.OnBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun QualityBadge(
    codec: String?,
    modifier: Modifier = Modifier,
) {
    if (codec.isNullOrBlank()) return
    Text(
        text = codec,
        style = MaterialTheme.typography.labelSmall,
        color = MusColors.OnBackgroundTertiary,
        modifier = modifier
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
