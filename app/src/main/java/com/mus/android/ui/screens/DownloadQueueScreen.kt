package com.mus.android.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.mus.android.data.model.DownloadQueueItem
import com.mus.android.data.model.DownloadStatus
import com.mus.android.ui.theme.MusColors
import com.mus.android.ui.theme.Spacing
import com.mus.android.ui.viewmodel.DiscoveryViewModel

/**
 * "MUS — To Download" planning list screen.
 *
 * Shows all items the user has saved from online discovery.
 * Actions: Open Source, Remove, Mark Downloaded, Mark Added to Library.
 *
 * MUS does NOT download or delete audio files from this screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadQueueScreen(
    onBack: () -> Unit,
    viewModel: DiscoveryViewModel = hiltViewModel(),
) {
    val queue by viewModel.downloadQueue.collectAsState()
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
    ) {
        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = Spacing.sm, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back",
                    tint = MusColors.OnBackground,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "MUS — To Download",
                    style = MaterialTheme.typography.titleMedium,
                    color = MusColors.OnBackground,
                )
                Text(
                    "${queue.size} item${if (queue.size != 1) "s" else ""}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MusColors.OnBackgroundSecondary,
                )
            }
        }

        HorizontalDivider(color = MusColors.Divider)

        if (queue.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.AutoMirrored.Rounded.PlaylistAdd,
                        contentDescription = null,
                        tint = MusColors.OnBackgroundTertiary,
                        modifier = Modifier.size(48.dp),
                    )
                    Spacer(Modifier.height(Spacing.md))
                    Text(
                        "Your discovery queue is empty",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MusColors.OnBackgroundTertiary,
                    )
                    Text(
                        "Search online and add songs to discover later",
                        style = MaterialTheme.typography.bodySmall,
                        color = MusColors.OnBackgroundTertiary,
                    )
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(bottom = 120.dp),
            ) {
                items(queue, key = { it.id }) { item ->
                    DownloadQueueRow(
                        item = item,
                        onOpenSource = {
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.sourceUrl)))
                            } catch (e: Exception) { /* no browser */ }
                        },
                        onRemove = { viewModel.removeFromDownloadQueue(item.id) },
                        onMarkDownloaded = { viewModel.markDownloaded(item.id) },
                        onMarkAddedToLibrary = { viewModel.markAddedToLibrary(item.id) },
                    )
                    HorizontalDivider(color = MusColors.Divider)
                }
            }
        }
    }
}

@Composable
private fun DownloadQueueRow(
    item: DownloadQueueItem,
    onOpenSource: () -> Unit,
    onRemove: () -> Unit,
    onMarkDownloaded: () -> Unit,
    onMarkAddedToLibrary: () -> Unit,
) {
    var showActions by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showActions = !showActions }
            .padding(horizontal = Spacing.base, vertical = Spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Thumbnail
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MusColors.SurfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = MusColors.OnBackgroundTertiary,
                    modifier = Modifier.size(20.dp),
                )
                if (!item.artworkUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = item.artworkUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            Spacer(Modifier.width(Spacing.md))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MusColors.OnBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = item.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MusColors.OnBackgroundSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Source badge
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MusColors.SurfaceVariant,
                    ) {
                        Text(
                            text = item.source,
                            style = MaterialTheme.typography.labelSmall,
                            color = MusColors.OnBackgroundSecondary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    // Status chip
                    StatusChip(item.status)
                }
            }

            IconButton(onClick = { showActions = !showActions }) {
                Icon(
                    if (showActions) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = "Actions",
                    tint = MusColors.OnBackgroundTertiary,
                )
            }
        }

        // Expanded action row
        if (showActions) {
            Spacer(Modifier.height(Spacing.sm))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                OutlinedButton(
                    onClick = onOpenSource,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MusColors.OnBackground),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = Spacing.sm, vertical = 6.dp),
                ) {
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Open", style = MaterialTheme.typography.labelSmall)
                }

                if (item.status == DownloadStatus.TO_DOWNLOAD) {
                    OutlinedButton(
                        onClick = onMarkDownloaded,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MusColors.OnBackground),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = Spacing.sm, vertical = 6.dp),
                    ) {
                        Icon(Icons.Rounded.CheckCircleOutline, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Downloaded", style = MaterialTheme.typography.labelSmall)
                    }
                }

                if (item.status == DownloadStatus.DOWNLOADED) {
                    OutlinedButton(
                        onClick = onMarkAddedToLibrary,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MusColors.OnBackground),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = Spacing.sm, vertical = 6.dp),
                    ) {
                        Icon(Icons.Rounded.LibraryAdd, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("In Library", style = MaterialTheme.typography.labelSmall)
                    }
                }

                OutlinedButton(
                    onClick = onRemove,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MusColors.Error),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = Spacing.sm, vertical = 6.dp),
                ) {
                    Icon(Icons.Rounded.DeleteOutline, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Remove", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun StatusChip(status: String) {
    val (label, color) = when (status) {
        DownloadStatus.DOWNLOADED      -> "Downloaded" to MusColors.OnBackground
        DownloadStatus.ADDED_TO_LIBRARY -> "In Library" to MusColors.OnBackground
        DownloadStatus.FAILED         -> "Failed" to MusColors.Error
        else                          -> "To Download" to MusColors.OnBackgroundTertiary
    }
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = color.copy(alpha = 0.12f),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
