package com.mus.android.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.mus.android.data.model.Track
import com.mus.android.ui.theme.MusColors
import com.mus.android.ui.theme.Spacing

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackRow(
    track: Track,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    isSelected: Boolean = false,
    isInSelectionMode: Boolean = false,
    onFavoriteToggle: (() -> Unit)? = null,
    onMoreClick: (() -> Unit)? = null,
    showArtwork: Boolean = true,
    trackNumber: Int? = null,
    modifier: Modifier = Modifier,
) {
    val clickModifier = if (onLongClick != null) {
        Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick
        )
    } else {
        Modifier.clickable(onClick = onClick)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(clickModifier)
            .padding(horizontal = Spacing.base, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Selection Checkbox
        if (isInSelectionMode) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onClick() },
                colors = CheckboxDefaults.colors(
                    checkedColor = MusColors.OnBackground,
                    checkmarkColor = MusColors.Background,
                    uncheckedColor = MusColors.OnBackgroundTertiary,
                ),
                modifier = Modifier.padding(end = Spacing.xs)
            )
        }

        // Track number (hidden in selection mode)
        if (trackNumber != null && !isInSelectionMode) {
            Text(
                text = trackNumber.toString(),
                style = MaterialTheme.typography.bodyMedium,
                color = MusColors.OnBackgroundTertiary,
                modifier = Modifier.width(28.dp),
            )
        }

        if (showArtwork) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MusColors.SurfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = MusColors.OnBackgroundTertiary,
                    modifier = Modifier.size(24.dp)
                )
                if (!track.artworkUri.isNullOrBlank()) {
                    val context = LocalContext.current
                    val request = remember(track.artworkUri) {
                        ImageRequest.Builder(context)
                            .data(track.artworkUri)
                            .size(144, 144)
                            .crossfade(false)
                            .build()
                    }
                    AsyncImage(
                        model = request,
                        contentDescription = track.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
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

        // Favorite (hidden during selection mode)
        if (onFavoriteToggle != null && !isInSelectionMode) {
            IconButton(onClick = onFavoriteToggle, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = if (track.isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    contentDescription = "Favorite",
                    tint = if (track.isFavorite) MusColors.Favorite else MusColors.OnBackgroundTertiary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        // More (hidden during selection mode)
        if (onMoreClick != null && !isInSelectionMode) {
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
        Box(
            modifier = Modifier
                .size(160.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MusColors.SurfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.Album,
                contentDescription = null,
                tint = MusColors.OnBackgroundTertiary,
                modifier = Modifier.size(56.dp)
            )
            if (!artworkUri.isNullOrBlank()) {
                val context = LocalContext.current
                val request = remember(artworkUri) {
                    ImageRequest.Builder(context)
                        .data(artworkUri)
                        .size(480, 480)
                        .crossfade(false)
                        .build()
                }
                AsyncImage(
                    model = request,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
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
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(RoundedCornerShape(60.dp))
                .background(MusColors.SurfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.Person,
                contentDescription = null,
                tint = MusColors.OnBackgroundTertiary,
                modifier = Modifier.size(48.dp)
            )
            if (!artworkUri.isNullOrBlank()) {
                val context = LocalContext.current
                val request = remember(artworkUri) {
                    ImageRequest.Builder(context)
                        .data(artworkUri)
                        .size(360, 360)
                        .crossfade(false)
                        .build()
                }
                AsyncImage(
                    model = request,
                    contentDescription = name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
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

@Composable
fun PlaylistCard(
    name: String,
    trackCount: Int = 0,
    artworkUri: String? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(140.dp)
            .clickable { onClick() }
            .padding(Spacing.xs),
    ) {
        Box(
            modifier = Modifier
                .size(140.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MusColors.SurfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = MusColors.OnBackgroundTertiary,
                modifier = Modifier.size(48.dp)
            )
            if (!artworkUri.isNullOrBlank()) {
                val context = LocalContext.current
                val request = remember(artworkUri) {
                    ImageRequest.Builder(context)
                        .data(artworkUri)
                        .size(420, 420)
                        .crossfade(false)
                        .build()
                }
                AsyncImage(
                    model = request,
                    contentDescription = name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = name,
            style = MaterialTheme.typography.titleSmall,
            color = MusColors.OnBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (trackCount > 0) {
            Text(
                text = "$trackCount tracks",
                style = MaterialTheme.typography.bodySmall,
                color = MusColors.OnBackgroundSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

