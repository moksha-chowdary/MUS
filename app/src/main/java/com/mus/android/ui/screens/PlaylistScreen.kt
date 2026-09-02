package com.mus.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mus.android.ui.components.TrackRow
import com.mus.android.ui.theme.MusColors
import com.mus.android.ui.theme.Spacing
import com.mus.android.ui.viewmodel.PlaylistViewModel

@Composable
fun PlaylistScreen(
    onBack: () -> Unit,
    viewModel: PlaylistViewModel = hiltViewModel(),
) {
    val playlist by viewModel.playlist.collectAsState()
    val tracks by viewModel.tracks.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MusColors.Background),
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
                // Playlist icon
                Icon(
                    Icons.Rounded.QueueMusic,
                    contentDescription = null,
                    tint = MusColors.OnBackgroundSecondary,
                    modifier = Modifier
                        .size(120.dp)
                        .background(MusColors.SurfaceVariant, RoundedCornerShape(16.dp))
                        .padding(32.dp),
                )
                Spacer(Modifier.height(Spacing.lg))
                Text(
                    text = playlist?.name ?: "",
                    style = MaterialTheme.typography.titleLarge,
                    color = MusColors.OnBackground,
                )
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = "${tracks.size} songs",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MusColors.OnBackgroundSecondary,
                )
                Spacer(Modifier.height(Spacing.lg))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    Button(
                        onClick = { viewModel.playAll(shuffle = false) },
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
                        onClick = { viewModel.playAll(shuffle = true) },
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

        if (tracks.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Spacing.xxl),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "No songs in this playlist",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MusColors.OnBackgroundTertiary,
                    )
                }
            }
        }

        items(tracks) { track ->
            TrackRow(
                track = track,
                onClick = { viewModel.playTrack(track) },
                onMoreClick = { viewModel.removeTrack(track.id) },
            )
        }
    }
}
