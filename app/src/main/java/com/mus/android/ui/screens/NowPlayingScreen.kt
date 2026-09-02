package com.mus.android.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.Player
import coil.compose.AsyncImage
import com.mus.android.ui.ambient.AmbientGradientBackground
import com.mus.android.ui.components.PlayPauseMorphButton
import com.mus.android.ui.components.QualityBadge
import com.mus.android.ui.components.WaveformSlider
import com.mus.android.ui.components.formatDuration
import com.mus.android.ui.theme.MusColors
import com.mus.android.ui.theme.Spacing
import com.mus.android.ui.theme.TimestampStyle
import com.mus.android.ui.viewmodel.NowPlayingViewModel

@Composable
fun NowPlayingScreen(
    onBack: () -> Unit,
    onQueueClick: () -> Unit,
    viewModel: NowPlayingViewModel = hiltViewModel(),
) {
    val track by viewModel.currentTrack.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val position by viewModel.position.collectAsState()
    val duration by viewModel.duration.collectAsState()
    val waveformData by viewModel.waveformData.collectAsState()
    val artworkColors by viewModel.artworkColors.collectAsState()
    val shuffleEnabled by viewModel.shuffleEnabled.collectAsState()
    val repeatMode by viewModel.repeatMode.collectAsState()
    val playlists by viewModel.playlists.collectAsState()

    var showAddToPlaylist by remember { mutableStateOf(false) }

    val progress = if (duration > 0) position.toFloat() / duration.toFloat() else 0f

    Box(modifier = Modifier.fillMaxSize()) {
        // Ambient gradient background — full intensity
        AmbientGradientBackground(
            colors = artworkColors,
            intensity = 1f,
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.sm),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.Rounded.KeyboardArrowDown,
                        contentDescription = "Close",
                        tint = MusColors.OnBackground,
                        modifier = Modifier.size(28.dp),
                    )
                }
                Text(
                    "Now Playing",
                    style = MaterialTheme.typography.labelLarge,
                    color = MusColors.OnBackgroundSecondary,
                )
                IconButton(onClick = onQueueClick) {
                    Icon(
                        Icons.AutoMirrored.Rounded.QueueMusic,
                        contentDescription = "Queue",
                        tint = MusColors.OnBackground,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }

            Spacer(Modifier.height(Spacing.xl))

            // Artwork — large, centered
            AnimatedContent(
                targetState = track?.artworkUri,
                transitionSpec = {
                    (fadeIn(animationSpec = tween(400)) + slideInVertically { it / 20 })
                        .togetherWith(fadeOut(animationSpec = tween(400)))
                },
                label = "artwork_transition"
            ) { artworkUri ->
                AsyncImage(
                    model = artworkUri,
                    contentDescription = track?.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(16.dp))
                )
            }

            Spacer(Modifier.height(Spacing.xl))

            // Track info with crossfade + slide on change
            AnimatedContent(
                targetState = track,
                transitionSpec = {
                    (fadeIn(tween(300)) + slideInVertically { 8 })
                        .togetherWith(fadeOut(tween(200)))
                },
                label = "track_info"
            ) { currentTrack ->
                Column(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = currentTrack?.title ?: "—",
                                style = MaterialTheme.typography.titleMedium,
                                color = MusColors.OnBackground,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = currentTrack?.artist ?: "—",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MusColors.OnBackgroundSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        // Add to Playlist
                        IconButton(onClick = { showAddToPlaylist = true }) {
                            Icon(
                                imageVector = Icons.Rounded.PlaylistAdd,
                                contentDescription = "Add to playlist",
                                tint = MusColors.OnBackgroundSecondary,
                                modifier = Modifier.size(24.dp),
                            )
                        }

                        // Favorite
                        IconButton(onClick = { viewModel.toggleFavorite() }) {
                            Icon(
                                imageVector = if (currentTrack?.isFavorite == true)
                                    Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                contentDescription = "Favorite",
                                tint = if (currentTrack?.isFavorite == true)
                                    MusColors.Favorite else MusColors.OnBackgroundSecondary,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                    // Quality badge
                    if (currentTrack?.codec != null) {
                        Spacer(Modifier.height(Spacing.xs))
                        QualityBadge(codec = currentTrack.codec)
                    }
                }
            }

            Spacer(Modifier.height(Spacing.lg))

            // Waveform slider
            WaveformSlider(
                waveformData = waveformData,
                progress = progress,
                isPlaying = isPlaying,
                onSeek = { viewModel.seekTo(it) },
                modifier = Modifier.fillMaxWidth(),
            )

            // Timestamps — dot-matrix style
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.xs),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = formatDuration(position),
                    style = TimestampStyle,
                    color = MusColors.OnBackgroundSecondary,
                )
                Text(
                    text = formatDuration(duration),
                    style = TimestampStyle,
                    color = MusColors.OnBackgroundTertiary,
                )
            }

            Spacer(Modifier.height(Spacing.xl))

            // Playback controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Shuffle
                IconButton(onClick = { viewModel.toggleShuffle() }) {
                    Icon(
                        Icons.Rounded.Shuffle,
                        contentDescription = "Shuffle",
                        tint = if (shuffleEnabled) MusColors.OnBackground
                               else MusColors.OnBackgroundTertiary,
                        modifier = Modifier.size(22.dp),
                    )
                }

                // Previous
                IconButton(
                    onClick = { viewModel.skipPrevious() },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        Icons.Rounded.SkipPrevious,
                        contentDescription = "Previous",
                        tint = MusColors.OnBackground,
                        modifier = Modifier.size(32.dp),
                    )
                }

                // Play/Pause (large, shape morph)
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(32.dp))
                        .background(MusColors.OnBackground.copy(alpha = 0.1f))
                        .clickable { viewModel.togglePlayPause() },
                    contentAlignment = Alignment.Center,
                ) {
                    PlayPauseMorphButton(
                        isPlaying = isPlaying,
                        size = 32.dp,
                        color = MusColors.OnBackground,
                    )
                }

                // Next
                IconButton(
                    onClick = { viewModel.skipNext() },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        Icons.Rounded.SkipNext,
                        contentDescription = "Next",
                        tint = MusColors.OnBackground,
                        modifier = Modifier.size(32.dp),
                    )
                }

                // Repeat
                IconButton(onClick = { viewModel.cycleRepeatMode() }) {
                    Icon(
                        imageVector = when (repeatMode) {
                            Player.REPEAT_MODE_ONE -> Icons.Rounded.RepeatOne
                            else -> Icons.Rounded.Repeat
                        },
                        contentDescription = "Repeat",
                        tint = when (repeatMode) {
                            Player.REPEAT_MODE_OFF -> MusColors.OnBackgroundTertiary
                            else -> MusColors.OnBackground
                        },
                        modifier = Modifier.size(22.dp),
                    )
                }
            }

            Spacer(Modifier.weight(1f))
        }

        if (showAddToPlaylist) {
            com.mus.android.ui.components.AddToPlaylistSheet(
                playlists = playlists,
                onPlaylistSelected = {
                    viewModel.addTrackToPlaylist(it)
                    showAddToPlaylist = false
                },
                onCreateNew = {
                    viewModel.createPlaylistAndAddTrack(it)
                    showAddToPlaylist = false
                },
                onDismiss = { showAddToPlaylist = false }
            )
        }
    }
}
