package com.mus.android.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import com.mus.android.ui.components.AnimatedListItem
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.mus.android.data.model.Track
import com.mus.android.ui.components.SongMenuContainer
import com.mus.android.ui.components.TrackRow
import com.mus.android.ui.components.formatDuration
import com.mus.android.ui.theme.MusColors
import com.mus.android.ui.theme.Spacing
import com.mus.android.ui.viewmodel.AlbumViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumScreen(
    onBack: () -> Unit,
    onTrackSelected: (() -> Unit)? = null,
    viewModel: AlbumViewModel = hiltViewModel(),
) {
    val album by viewModel.album.collectAsState()
    val tracks by viewModel.tracks.collectAsState()
    var selectedTrackForMenu by remember { mutableStateOf<Track?>(null) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent),
        contentPadding = PaddingValues(bottom = 120.dp),
    ) {
        item {
            // Back button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.xxxl, start = Spacing.sm),
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back",
                        tint = MusColors.OnBackground,
                    )
                }
            }
        }

        item {
            // Album header
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(240.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MusColors.SurfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Album,
                        contentDescription = null,
                        tint = MusColors.OnBackgroundTertiary,
                        modifier = Modifier.size(80.dp)
                    )
                    if (!album?.artworkUri.isNullOrBlank()) {
                        AsyncImage(
                            model = album?.artworkUri,
                            contentDescription = album?.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
                Spacer(Modifier.height(Spacing.lg))
                Text(
                    text = album?.title ?: "",
                    style = MaterialTheme.typography.titleLarge,
                    color = MusColors.OnBackground,
                )
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = "${album?.artist ?: ""} • ${album?.year ?: ""} • ${tracks.size} songs",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MusColors.OnBackgroundSecondary,
                )
                Spacer(Modifier.height(Spacing.lg))

                // Play / Shuffle buttons
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    Button(
                        onClick = { 
                            viewModel.playAll(shuffle = false)
                            onTrackSelected?.invoke()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MusColors.OnBackground,
                            contentColor = MusColors.Background,
                        ),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(Spacing.xs))
                        Text("Play", style = MaterialTheme.typography.labelLarge)
                    }
                    OutlinedButton(
                        onClick = { 
                            viewModel.playAll(shuffle = true)
                            onTrackSelected?.invoke()
                        },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MusColors.OnBackground,
                        ),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Icon(Icons.Rounded.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(Spacing.xs))
                        Text("Shuffle", style = MaterialTheme.typography.labelLarge)
                    }
                }
                Spacer(Modifier.height(Spacing.lg))
            }
        }

        // Track list
        itemsIndexed(tracks, key = { _, track -> track.id }) { index, track ->
            AnimatedListItem(index = index) {
                TrackRow(
                    track = track,
                    onClick = { 
                        viewModel.playTrack(track)
                        onTrackSelected?.invoke()
                    },
                    onFavoriteToggle = { viewModel.toggleFavorite(track.id) },
                    onMoreClick = { selectedTrackForMenu = track },
                    showArtwork = false,
                    trackNumber = track.trackNumber,
                )
            }
        }
    }

    SongMenuContainer(
        selectedTrack = selectedTrackForMenu,
        onDismissMenu = { selectedTrackForMenu = null },
    )
}
