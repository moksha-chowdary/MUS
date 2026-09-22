package com.mus.android.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.MoreVert
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
import com.mus.android.ui.components.ArtworkSearchSheet
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
    var showArtworkPicker by remember { mutableStateOf(false) }
    var showAlbumMenu by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent),
        contentPadding = PaddingValues(bottom = 120.dp),
    ) {
        item {
            // Top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.xxxl, start = Spacing.sm, end = Spacing.sm),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back",
                        tint = MusColors.OnBackground,
                    )
                }

                Box {
                    IconButton(onClick = { showAlbumMenu = true }) {
                        Icon(
                            Icons.Rounded.MoreVert,
                            contentDescription = "Album options",
                            tint = MusColors.OnBackground,
                        )
                    }

                    DropdownMenu(
                        expanded = showAlbumMenu,
                        onDismissRequest = { showAlbumMenu = false },
                        containerColor = MusColors.SurfaceElevated,
                    ) {
                        DropdownMenuItem(
                            text = { Text("Change Album Artwork", color = MusColors.OnBackground) },
                            leadingIcon = {
                                Icon(
                                    Icons.Rounded.Image,
                                    contentDescription = null,
                                    tint = MusColors.OnBackgroundSecondary,
                                )
                            },
                            onClick = {
                                showAlbumMenu = false
                                showArtworkPicker = true
                            },
                        )
                    }
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
                        .background(MusColors.SurfaceVariant)
                        .clickable { showArtworkPicker = true },
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
                    // Edit badge overlay
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(Spacing.sm)
                            .size(32.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MusColors.Surface.copy(alpha = 0.85f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Edit,
                            contentDescription = "Change artwork",
                            tint = MusColors.OnBackground,
                            modifier = Modifier.size(16.dp),
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
        items(tracks, key = { it.id }) { track ->
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

    SongMenuContainer(
        selectedTrack = selectedTrackForMenu,
        onDismissMenu = { selectedTrackForMenu = null },
    )

    val representativeTrack = remember(album, tracks) {
        tracks.firstOrNull() ?: album?.let { alb ->
            Track(
                id = -1L,
                title = alb.title,
                artist = alb.artist,
                albumId = alb.id,
                albumTitle = alb.title,
                duration = 0L,
                uri = "",
            )
        }
    }

    if (showArtworkPicker && representativeTrack != null) {
        ArtworkSearchSheet(
            track = representativeTrack,
            fromAlbum = true,
            onBack = { showArtworkPicker = false },
            onApplied = {
                showArtworkPicker = false
                viewModel.refreshAlbum()
            },
        )
    }
}
