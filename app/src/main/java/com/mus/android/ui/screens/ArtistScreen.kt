package com.mus.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
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
import com.mus.android.ui.components.AlbumCard
import com.mus.android.ui.components.SongMenuContainer
import com.mus.android.ui.components.TrackRow
import com.mus.android.ui.theme.MusColors
import com.mus.android.ui.theme.Spacing
import com.mus.android.ui.viewmodel.ArtistViewModel

@Composable
fun ArtistScreen(
    onBack: () -> Unit,
    onAlbumClick: (Long) -> Unit,
    viewModel: ArtistViewModel = hiltViewModel(),
) {
    val artist by viewModel.artist.collectAsState()
    val albums by viewModel.albums.collectAsState()
    val tracks by viewModel.tracks.collectAsState()
    var selectedTrackForMenu by remember { mutableStateOf<Track?>(null) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent),
        contentPadding = PaddingValues(bottom = 120.dp),
    ) {
        item {
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
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AsyncImage(
                    model = artist?.artworkUri,
                    contentDescription = artist?.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(160.dp)
                        .clip(RoundedCornerShape(80.dp)),
                )
                Spacer(Modifier.height(Spacing.lg))
                Text(
                    text = artist?.name ?: "",
                    style = MaterialTheme.typography.titleLarge,
                    color = MusColors.OnBackground,
                )
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = "${artist?.albumCount ?: 0} albums • ${artist?.trackCount ?: 0} songs",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MusColors.OnBackgroundSecondary,
                )
                Spacer(Modifier.height(Spacing.lg))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    Button(
                        onClick = { viewModel.playAll(shuffle = false) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MusColors.OnBackground,
                            contentColor = MusColors.Background,
                        ),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(Spacing.xs))
                        Text("Play", style = MaterialTheme.typography.labelLarge)
                    }
                    OutlinedButton(
                        onClick = { viewModel.playAll(shuffle = true) },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MusColors.OnBackground),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Icon(Icons.Rounded.Shuffle, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(Spacing.xs))
                        Text("Shuffle", style = MaterialTheme.typography.labelLarge)
                    }
                }
                Spacer(Modifier.height(Spacing.xl))
            }
        }

        // Albums by this artist
        if (albums.isNotEmpty()) {
            item {
                Text(
                    "Albums",
                    style = MaterialTheme.typography.titleSmall,
                    color = MusColors.OnBackground,
                    modifier = Modifier.padding(horizontal = Spacing.base),
                )
                Spacer(Modifier.height(Spacing.sm))
            }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = Spacing.md),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    items(albums) { album ->
                        AlbumCard(
                            title = album.title,
                            artist = album.artist,
                            artworkUri = album.artworkUri,
                            onClick = { onAlbumClick(album.id) },
                        )
                    }
                }
                Spacer(Modifier.height(Spacing.xl))
            }
        }

        // All tracks
        item {
            Text(
                "Songs",
                style = MaterialTheme.typography.titleSmall,
                color = MusColors.OnBackground,
                modifier = Modifier.padding(horizontal = Spacing.base),
            )
            Spacer(Modifier.height(Spacing.sm))
        }
        items(tracks) { track ->
            TrackRow(
                track = track,
                onClick = { viewModel.playTrack(track) },
                onMoreClick = { selectedTrackForMenu = track },
            )
        }
    }

    SongMenuContainer(
        selectedTrack = selectedTrackForMenu,
        onDismissMenu = { selectedTrackForMenu = null },
        onNavigateToAlbum = onAlbumClick,
    )
}
