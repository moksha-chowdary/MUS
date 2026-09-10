package com.mus.android.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import androidx.hilt.navigation.compose.hiltViewModel
import com.mus.android.data.model.Track
import com.mus.android.ui.theme.MusColors
import com.mus.android.ui.theme.Spacing
import com.mus.android.ui.viewmodel.NowPlayingViewModel

/**
 * MUS mini-player — compact bottom bar during playback.
 *
 * Features:
 * - Artwork with scale-bounce on play/pause tap
 * - Title + artist
 * - Play/pause morph button
 * - Skip next
 * - Hairline progress underline
 * - Self-contained high-frequency position collection (avoids root recomposition)
 */
@Composable
fun MiniPlayer(
    track: Track,
    isPlaying: Boolean,
    onTap: () -> Unit,
    onPlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: NowPlayingViewModel = hiltViewModel(),
    overrideProgress: Float? = null,
) {
    val position by viewModel.position.collectAsState()
    val duration by viewModel.duration.collectAsState()
    val progress = overrideProgress ?: if (duration > 0) (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f
    // Scale bounce on play/pause
    var bouncing by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (bouncing) 0.96f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 800f),
        finishedListener = { bouncing = false },
        label = "mini_bounce"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MusColors.Surface.copy(alpha = 0.95f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onTap() }
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Artwork with bounce
            AsyncImage(
                model = track.artworkUri,
                contentDescription = track.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
            )

            Spacer(Modifier.width(Spacing.md))

            // Title + Artist
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
                Text(
                    text = track.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MusColors.OnBackgroundSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.width(Spacing.sm))

            // Play/Pause
            IconButton(onClick = {
                bouncing = true
                onPlayPause()
            }) {
                PlayPauseMorphButton(
                    isPlaying = isPlaying,
                    size = 24.dp,
                )
            }

            // Skip Next
            IconButton(onClick = onSkipNext) {
                Icon(
                    Icons.Rounded.SkipNext,
                    contentDescription = "Next",
                    tint = MusColors.OnBackground,
                    modifier = Modifier.size(22.dp),
                )
            }
        }

        // Hairline progress underline
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(MusColors.Divider)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .background(MusColors.OnBackground.copy(alpha = 0.6f))
            )
        }
    }
}
