package com.mus.android.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.Player
import coil.compose.AsyncImage
import com.mus.android.ui.ambient.AmbientGradientBackground
import com.mus.android.ui.components.DepthCarousel
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
    val artworkColors by viewModel.artworkColors.collectAsState()
    val shuffleEnabled by viewModel.shuffleEnabled.collectAsState()
    val repeatMode by viewModel.repeatMode.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    val existingPlaylistIds by viewModel.existingPlaylistIdsForCurrentTrack.collectAsState()
    val queue by viewModel.queue.collectAsState()
    val currentIndex by viewModel.currentIndex.collectAsState()
    val context = LocalContext.current

    val upcomingTracks = remember(queue, currentIndex) {
        if (currentIndex in queue.indices && currentIndex + 1 < queue.size) {
            queue.subList(currentIndex + 1, queue.size)
        } else {
            emptyList()
        }
    }

    var showAddToPlaylist by remember { mutableStateOf(false) }

    // Artwork swipe pager backed by queue index
    val pagerState = rememberPagerState(
        initialPage = currentIndex.coerceAtLeast(0),
        pageCount = { if (queue.isNotEmpty()) queue.size else 1 }
    )

    // Guard flag: true while we are programmatically scrolling the pager in response to
    // a Media3 track change. This prevents the settledPage LaunchedEffect from calling
    // playQueueItem() for our own animations, which would create a feedback loop:
    //   Media3 advance → pager animate → settledPage fires → seekTo → Media3 advance again
    var programmaticScroll by remember { mutableStateOf(false) }

    // Sync pager when track changes from Media3 playback / skip buttons
    LaunchedEffect(currentIndex, queue.size) {
        if (!pagerState.isScrollInProgress && queue.isNotEmpty() && currentIndex in queue.indices && pagerState.currentPage != currentIndex) {
            programmaticScroll = true
            pagerState.animateScrollToPage(currentIndex)
            programmaticScroll = false
        }
    }

    // When USER swipes to another page, commit track change to Media3.
    // Skipped when the scroll was triggered programmatically by the effect above.
    LaunchedEffect(pagerState.settledPage) {
        if (!programmaticScroll && queue.isNotEmpty() && pagerState.settledPage in queue.indices && pagerState.settledPage != currentIndex) {
            viewModel.playQueueItem(pagerState.settledPage)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Touch isolation: swallow taps on empty background areas so nothing reaches underlying destinations,
            // while allowing all child gestures (HorizontalPager swipes, buttons, sliders) to function unhindered.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { /* Swallow taps on empty background space */ }
            )
    ) {
        // Ambient gradient background — full atmospheric intensity
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

            // Artwork — HorizontalPager swipe between tracks with physical card feel
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
                contentPadding = PaddingValues(horizontal = 20.dp),
                pageSpacing = 16.dp,
            ) { page ->
                val pageTrack = queue.getOrNull(page) ?: track
                val pageOffset = (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
                val absOffset = kotlin.math.abs(pageOffset).coerceIn(0f, 1f)
                val scale = 1f - (absOffset * 0.10f)
                val rotation = (pageOffset * -2.5f).coerceIn(-5f, 5f)
                val alpha = 1f - (absOffset * 0.35f)

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            rotationZ = rotation
                            this.alpha = alpha
                        }
                        .clip(RoundedCornerShape(20.dp))
                        .background(MusColors.SurfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Album,
                        contentDescription = null,
                        tint = MusColors.OnBackgroundTertiary.copy(alpha = 0.5f),
                        modifier = Modifier.size(80.dp)
                    )
                    if (!pageTrack?.artworkUri.isNullOrBlank()) {
                        AsyncImage(
                            model = pageTrack?.artworkUri,
                            contentDescription = pageTrack?.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
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
                                imageVector = Icons.AutoMirrored.Rounded.PlaylistAdd,
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

            // Isolated Playback Progress (Waveform Slider + Dot-Matrix Timestamps)
            // Confines rapid position recompositions to this section only.
            PlaybackProgressSection(
                viewModel = viewModel,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(Spacing.xl))

            // Playback controls — mathematically centered layout (Part 14)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Left control group (Shuffle + Previous)
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = { viewModel.toggleShuffle() },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            Icons.Rounded.Shuffle,
                            contentDescription = "Shuffle",
                            tint = if (shuffleEnabled) MusColors.OnBackground
                                   else MusColors.OnBackgroundTertiary,
                            modifier = Modifier.size(22.dp),
                        )
                    }

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
                }

                // Centered Play/Pause Button
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

                // Right control group (Next + Repeat)
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
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

                    IconButton(
                        onClick = { viewModel.cycleRepeatMode() },
                        modifier = Modifier.size(48.dp),
                    ) {
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
            }

            if (upcomingTracks.isNotEmpty()) {
                Spacer(Modifier.height(Spacing.xl))
                DepthCarousel(
                    upcomingTracks = upcomingTracks,
                    currentIndex = currentIndex,
                    onTrackClick = { _, actualIndex ->
                        viewModel.playQueueItem(actualIndex)
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(Spacing.xxl))
        }

        if (showAddToPlaylist) {
            com.mus.android.ui.components.AddToPlaylistSheet(
                playlists = playlists,
                alreadyInPlaylistIds = existingPlaylistIds,
                onPlaylistSelected = { chosenPlaylistId ->
                    val chosen = playlists.find { it.id == chosenPlaylistId }
                    val isAlready = chosenPlaylistId in existingPlaylistIds
                    if (isAlready) {
                        android.widget.Toast.makeText(
                            context,
                            "Already in \"${chosen?.name ?: "playlist"}\"",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                        showAddToPlaylist = false
                    } else {
                        viewModel.addTrackToPlaylist(chosenPlaylistId) { added ->
                            if (added) {
                                android.widget.Toast.makeText(
                                    context,
                                    "Added to \"${chosen?.name ?: "playlist"}\"",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                android.widget.Toast.makeText(
                                    context,
                                    "Already in \"${chosen?.name ?: "playlist"}\"",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                        showAddToPlaylist = false
                    }
                },
                onCreateNew = { name ->
                    viewModel.createPlaylistAndAddTrack(name) {
                        android.widget.Toast.makeText(
                            context,
                            "Created \"$name\" and added song",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                    showAddToPlaylist = false
                },
                onDismiss = { showAddToPlaylist = false }
            )
        }
    }
}

/**
 * Isolated playback progress section: High-frequency position collection (~5Hz)
 * is strictly isolated here so that the NowPlayingScreen, artwork HorizontalPager,
 * metadata, ambient gradient, and controls do NOT recompose during playback ticks.
 */
@Composable
private fun PlaybackProgressSection(
    viewModel: NowPlayingViewModel,
    modifier: Modifier = Modifier,
) {
    val position by viewModel.position.collectAsState()
    val duration by viewModel.duration.collectAsState()
    val waveformData by viewModel.waveformData.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val progress = if (duration > 0) position.toFloat() / duration.toFloat() else 0f

    Column(modifier = modifier) {
        // Waveform slider — single bipolar progressive reveal
        WaveformSlider(
            waveformData = waveformData,
            progress = progress,
            isPlaying = isPlaying,
            onSeek = { viewModel.seekTo(it) },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(Spacing.xs))

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
    }
}
