package com.mus.android.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.mus.android.data.model.Track
import com.mus.android.ui.theme.MusColors
import com.mus.android.ui.theme.MusMotion
import com.mus.android.ui.theme.Spacing

/**
 * Depth-stacked carousel for upcoming tracks in the playback queue.
 *
 * Implements a progressive 3D depth aesthetic where upcoming tracks are stacked
 * with visual perspective:
 * - Card 0 (next track): closest, full scale (1.0), full opacity (1.0)
 * - Card 1: offset, scale 0.94, opacity 0.82
 * - Card 2: offset further, scale 0.88, opacity 0.65
 * - Card 3: offset further, scale 0.82, opacity 0.45
 *
 * Tapping any card plays that item in the queue.
 */
@Composable
fun DepthCarousel(
    upcomingTracks: List<Track>,
    currentIndex: Int,
    onTrackClick: (track: Track, actualQueueIndex: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (upcomingTracks.isEmpty()) return

    val visibleCount = minOf(upcomingTracks.size, 3)
    val displayTracks = remember(upcomingTracks) { upcomingTracks.take(visibleCount) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.base)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "UP NEXT",
                style = MaterialTheme.typography.labelSmall,
                color = MusColors.OnBackgroundTertiary,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
            )
            Text(
                "${upcomingTracks.size} in queue",
                style = MaterialTheme.typography.bodySmall,
                color = MusColors.OnBackgroundSecondary,
            )
        }

        Spacer(Modifier.height(Spacing.sm))

        // Stack container
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(84.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            // Render from back (visibleCount - 1) to front (0) so front has highest z-order
            for (i in (visibleCount - 1) downTo 0) {
                val track = displayTracks[i]
                val actualQueueIndex = currentIndex + 1 + i

                val targetScale = 1f - (i * 0.05f)
                val targetOffsetY = (i * 8).dp
                val targetAlpha = 1f - (i * 0.22f)

                val animatedScale by animateFloatAsState(
                    targetValue = targetScale,
                    animationSpec = MusMotion.trackChangeSpec(),
                    label = "depth_scale_$i"
                )
                val animatedAlpha by animateFloatAsState(
                    targetValue = targetAlpha,
                    animationSpec = MusMotion.trackChangeSpec(),
                    label = "depth_alpha_$i"
                )

                DepthStackCard(
                    track = track,
                    scale = animatedScale,
                    offsetY = targetOffsetY,
                    alpha = animatedAlpha,
                    zIndex = (visibleCount - i).toFloat(),
                    onClick = { onTrackClick(track, actualQueueIndex) },
                )
            }
        }
    }
}

@Composable
private fun DepthStackCard(
    track: Track,
    scale: Float,
    offsetY: androidx.compose.ui.unit.Dp,
    alpha: Float,
    zIndex: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .offset(y = offsetY)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            }
            .zIndex(zIndex)
            .shadow(
                elevation = (4 * scale).dp,
                shape = RoundedCornerShape(12.dp),
                clip = false
            )
            .clip(RoundedCornerShape(12.dp))
            .background(MusColors.SurfaceElevated)
            .clickable { onClick() }
            .padding(Spacing.sm)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Artwork
            AsyncImage(
                model = track.artworkUri,
                contentDescription = track.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MusColors.SurfaceVariant)
            )

            Spacer(Modifier.width(Spacing.md))

            // Track metadata
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MusColors.OnBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = track.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MusColors.OnBackgroundSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.width(Spacing.sm))

            Icon(
                Icons.Rounded.PlayArrow,
                contentDescription = "Play next",
                tint = MusColors.OnBackgroundTertiary,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
