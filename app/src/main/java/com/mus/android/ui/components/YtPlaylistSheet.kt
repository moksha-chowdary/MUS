package com.mus.android.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mus.android.data.download.TrackDownloadManager
import com.mus.android.data.enrichment.provider.OnlineSearchResult
import com.mus.android.ui.theme.MusColors
import com.mus.android.ui.theme.Spacing
import com.mus.android.ui.viewmodel.DiscoveryViewModel
import com.mus.android.ui.viewmodel.YtPlaylistSelection

/**
 * Bottom sheet for an opened YT Music playlist (account playlists or catalogue shelves).
 * Rows play inline (Apple Music behaviour); the trailing menu opens the shared
 * OnlineResultOverlay for queue/download/collection actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YtPlaylistTracksSheet(
    selection: YtPlaylistSelection,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    discovery: DiscoveryViewModel,
) {
    var menuResult by remember { mutableStateOf<OnlineSearchResult?>(null) }
    val downloadedKeys by discovery.downloadedKeys.collectAsState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MusColors.SurfaceElevated,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = selection.playlist.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MusColors.OnBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = listOfNotNull(
                            selection.playlist.author,
                            selection.playlist.trackCountText,
                        ).joinToString(" • ").ifBlank { "YouTube Music playlist" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MusColors.OnBackgroundTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                OutlinedButton(
                    onClick = {
                        selection.tracks.firstOrNull()?.let {
                            discovery.playYtPlaylistTrack(selection, it)
                        }
                    },
                    enabled = selection.tracks.isNotEmpty() && !isLoading,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MusColors.OnBackground),
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(Spacing.xs))
                    Text("Play all", style = MaterialTheme.typography.labelMedium)
                }
            }

            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = MusColors.OnBackground, strokeWidth = 2.dp)
                }
            } else if (selection.tracks.isEmpty()) {
                Text(
                    "Couldn't load this playlist's tracks",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MusColors.OnBackgroundTertiary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Spacing.lg),
                    textAlign = TextAlign.Center,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(bottom = 120.dp),
                ) {
                    items(selection.tracks, key = { it.id }) { track ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(modifier = Modifier.weight(1f)) {
                                OnlineTrackRow(
                                    result = track,
                                    onClick = { discovery.playYtPlaylistTrack(selection, track) },
                                    isDownloaded = downloadedKeys.contains(
                                        TrackDownloadManager.makeDownloadKey(track.title, track.artist)
                                    ),
                                )
                            }
                            IconButton(onClick = { menuResult = track }) {
                                Icon(
                                    Icons.Rounded.MoreVert,
                                    contentDescription = "More actions",
                                    tint = MusColors.OnBackgroundTertiary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Per-row action overlay (play/queue/download/collection)
    OnlineResultOverlay(
        result = menuResult,
        onDismiss = { menuResult = null },
        discovery = discovery,
    )
}
