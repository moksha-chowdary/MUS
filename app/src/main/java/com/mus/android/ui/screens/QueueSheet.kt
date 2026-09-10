package com.mus.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.mus.android.data.model.Track
import com.mus.android.playback.QueueSource
import com.mus.android.ui.components.AnimatedListItem
import com.mus.android.ui.theme.MusColors
import com.mus.android.ui.theme.Spacing
import com.mus.android.ui.viewmodel.NowPlayingViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(
    onDismiss: () -> Unit,
    viewModel: NowPlayingViewModel = hiltViewModel(),
) {
    val queue by viewModel.queue.collectAsState()
    val currentIndex by viewModel.currentIndex.collectAsState()
    val queueContext by viewModel.queueContext.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()

    val currentTrack = queue.getOrNull(currentIndex)
    val upcomingTracks = remember(queue, currentIndex) {
        if (currentIndex in queue.indices && currentIndex + 1 <= queue.size) {
            queue.subList(currentIndex + 1, queue.size)
        } else {
            emptyList()
        }
    }

    // ── Drag state — visual only during drag, single commit on drop ──
    var draggingFromIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }

    val density = LocalDensity.current
    val itemHeightPx = with(density) { 64.dp.toPx() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MusColors.SurfaceElevated,
        contentColor = MusColors.OnBackground,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.xl)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.base, vertical = Spacing.xs),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        "Queue",
                        style = MaterialTheme.typography.titleMedium,
                        color = MusColors.OnBackground,
                        fontWeight = FontWeight.SemiBold,
                    )
                    val subtitleText = when (queueContext.source) {
                        QueueSource.LANGUAGE_MIX -> {
                            val lang = queueContext.sourceLanguage ?: "Mix"
                            "$lang Mix • ${queue.size} songs"
                        }
                        else -> "${queue.size} songs"
                    }
                    Text(
                        subtitleText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MusColors.OnBackgroundSecondary,
                    )
                }

                if (queue.isNotEmpty()) {
                    TextButton(
                        onClick = {
                            viewModel.clearQueue()
                            onDismiss()
                        }
                    ) {
                        Text(
                            "Clear Queue",
                            color = MusColors.Error,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }

            Spacer(Modifier.height(Spacing.sm))

            if (queue.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Spacing.xxl),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Queue is empty",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MusColors.OnBackgroundTertiary,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(bottom = Spacing.lg),
                ) {
                    // ── NOW PLAYING SECTION ──────────────────────────────────
                    if (currentTrack != null) {
                        item {
                            Text(
                                "NOW PLAYING",
                                style = MaterialTheme.typography.labelSmall,
                                color = MusColors.OnBackgroundTertiary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = Spacing.base, vertical = Spacing.xs),
                            )
                        }

                        item {
                            NowPlayingQueueCard(
                                track = currentTrack,
                                isPlaying = isPlaying,
                                onRemove = { viewModel.removeFromQueue(currentIndex) },
                            )
                            Spacer(Modifier.height(Spacing.md))
                        }
                    }

                    // ── UP NEXT SECTION ─────────────────────────────────────
                    item {
                        Text(
                            "UP NEXT",
                            style = MaterialTheme.typography.labelSmall,
                            color = MusColors.OnBackgroundTertiary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = Spacing.base, vertical = Spacing.xs),
                        )
                    }

                    if (upcomingTracks.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = Spacing.xl),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "No upcoming songs",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MusColors.OnBackgroundTertiary,
                                )
                            }
                        }
                    } else {
                        itemsIndexed(
                            items = upcomingTracks,
                            // STABLE KEY: track.id only — not index-based
                            key = { _, track -> track.id }
                        ) { uIndex, track ->
                            val actualQueueIndex = currentIndex + 1 + uIndex
                            val isBeingDragged = draggingFromIndex == uIndex

                            AnimatedListItem(index = uIndex) {
                                UpNextTrackRow(
                                    track = track,
                                    isDragging = isBeingDragged,
                                    dragOffsetY = if (isBeingDragged) dragOffsetY else 0f,
                                    onLongPressDrag = {
                                        // Long-press ANYWHERE on the row to initiate drag
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = {
                                                draggingFromIndex = uIndex
                                                dragOffsetY = 0f
                                            },
                                            onDrag = { change, dragAmount ->
                                                change.consume()
                                                dragOffsetY += dragAmount.y
                                            },
                                            onDragEnd = {
                                                val from = draggingFromIndex
                                                if (from != null) {
                                                    val shift = (dragOffsetY / itemHeightPx).toInt()
                                                    val target = (from + shift).coerceIn(0, upcomingTracks.lastIndex)
                                                    if (from != target) {
                                                        // Single Media3 commit on drop only
                                                        val actualFrom = currentIndex + 1 + from
                                                        val actualTo = currentIndex + 1 + target
                                                        viewModel.moveQueueItem(actualFrom, actualTo)
                                                    }
                                                }
                                                draggingFromIndex = null
                                                dragOffsetY = 0f
                                            },
                                            onDragCancel = {
                                                draggingFromIndex = null
                                                dragOffsetY = 0f
                                            }
                                        )
                                    },
                                    onClick = {
                                        viewModel.playQueueItem(actualQueueIndex)
                                    },
                                    onRemove = {
                                        viewModel.removeFromQueue(actualQueueIndex)
                                    },
                                    onPlayNext = {
                                        // Move to top of Up Next (currentIndex + 1)
                                        if (actualQueueIndex != currentIndex + 1) {
                                            viewModel.moveQueueItem(actualQueueIndex, currentIndex + 1)
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NowPlayingQueueCard(
    track: Track,
    isPlaying: Boolean,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.base)
            .background(MusColors.SurfaceVariant.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
            .padding(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Artwork
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MusColors.SurfaceElevated),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = MusColors.OnBackgroundTertiary,
                modifier = Modifier.size(24.dp),
            )
            if (!track.artworkUri.isNullOrBlank()) {
                AsyncImage(
                    model = track.artworkUri,
                    contentDescription = track.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        Spacer(Modifier.width(Spacing.md))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.titleSmall,
                color = MusColors.OnBackground,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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

        Spacer(Modifier.width(Spacing.xs))

        // Playing status badge
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(MusColors.OnBackground.copy(alpha = 0.1f))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(if (isPlaying) Color(0xFF4CAF50) else MusColors.OnBackgroundTertiary, CircleShape)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = if (isPlaying) "Playing" else "Paused",
                    style = MaterialTheme.typography.labelSmall,
                    color = MusColors.OnBackgroundSecondary,
                )
            }
        }

        IconButton(
            onClick = onRemove,
            modifier = Modifier.size(36.dp),
        ) {
            Icon(
                Icons.Rounded.Close,
                contentDescription = "Remove current song",
                tint = MusColors.OnBackgroundTertiary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun UpNextTrackRow(
    track: Track,
    isDragging: Boolean,
    dragOffsetY: Float,
    onLongPressDrag: suspend androidx.compose.ui.input.pointer.PointerInputScope.() -> Unit,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    onPlayNext: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                if (isDragging) {
                    translationY = dragOffsetY
                    scaleX = 1.03f
                    scaleY = 1.03f
                    shadowElevation = 12.dp.toPx()
                    alpha = 0.95f
                }
            }
            .background(if (isDragging) MusColors.SurfaceElevated else MusColors.Transparent)
            // Long-press anywhere on the row to initiate drag
            .pointerInput(Unit, onLongPressDrag)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.base, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Drag Handle (visual indicator only — drag is now on entire row)
        Icon(
            Icons.Rounded.DragHandle,
            contentDescription = "Drag to reorder",
            tint = if (isDragging) MusColors.OnBackground else MusColors.OnBackgroundTertiary,
            modifier = Modifier.size(20.dp),
        )

        Spacer(Modifier.width(Spacing.sm))

        // Artwork thumbnail
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MusColors.SurfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = MusColors.OnBackgroundTertiary,
                modifier = Modifier.size(20.dp),
            )
            if (!track.artworkUri.isNullOrBlank()) {
                AsyncImage(
                    model = track.artworkUri,
                    contentDescription = track.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        Spacer(Modifier.width(Spacing.md))

        // Title and artist
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MusColors.OnBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${track.artist} • ${track.language}",
                style = MaterialTheme.typography.bodySmall,
                color = MusColors.OnBackgroundTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Direct remove action
        IconButton(
            onClick = onRemove,
            modifier = Modifier.size(36.dp),
        ) {
            Icon(
                Icons.Rounded.Close,
                contentDescription = "Remove from queue",
                tint = MusColors.OnBackgroundTertiary,
                modifier = Modifier.size(16.dp),
            )
        }

        // 3-dot overflow menu
        Box {
            IconButton(
                onClick = { showMenu = true },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    Icons.Rounded.MoreVert,
                    contentDescription = "Queue item options",
                    tint = MusColors.OnBackgroundTertiary,
                    modifier = Modifier.size(18.dp),
                )
            }

            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
                modifier = Modifier.background(MusColors.SurfaceElevated),
            ) {
                DropdownMenuItem(
                    text = { Text("Play Next", color = MusColors.OnBackground) },
                    leadingIcon = {
                        Icon(
                            Icons.Rounded.SkipNext,
                            contentDescription = null,
                            tint = MusColors.OnBackground,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    onClick = {
                        showMenu = false
                        onPlayNext()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Remove from Queue", color = MusColors.Error) },
                    leadingIcon = {
                        Icon(
                            Icons.Rounded.DeleteOutline,
                            contentDescription = null,
                            tint = MusColors.Error,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    onClick = {
                        showMenu = false
                        onRemove()
                    }
                )
            }
        }
    }
}
