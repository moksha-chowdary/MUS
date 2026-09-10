package com.mus.android.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import com.mus.android.ui.components.AnimatedListItem
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mus.android.data.classifier.LanguageClassifier
import com.mus.android.data.model.Track
import com.mus.android.ui.components.AddSongsToPlaylistSheet
import com.mus.android.ui.components.AddToPlaylistSheet
import com.mus.android.ui.components.SongMenuContainer
import com.mus.android.ui.components.TrackRow
import com.mus.android.ui.theme.MusColors
import com.mus.android.ui.theme.Spacing
import com.mus.android.ui.viewmodel.PlaylistViewModel

@Composable
fun PlaylistScreen(
    onBack: () -> Unit,
    viewModel: PlaylistViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val playlist by viewModel.playlist.collectAsState()
    val tracks by viewModel.tracks.collectAsState()
    val allPlaylists by viewModel.allPlaylists.collectAsState()
    val allLibraryTracks by viewModel.allLibraryTracks.collectAsState()
    val selectedTrackIds by viewModel.selectedTrackIds.collectAsState()
    val isSelectionMode by viewModel.isSelectionMode.collectAsState()

    var showDeleteDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showAddSongsSheet by remember { mutableStateOf(false) }
    var showBatchAddToPlaylistSheet by remember { mutableStateOf(false) }
    var showBatchMoveToPlaylistSheet by remember { mutableStateOf(false) }
    var showBatchRemoveDialog by remember { mutableStateOf(false) }
    var renameText by remember(playlist) { mutableStateOf(playlist?.name ?: "") }
    var selectedTrackForMenu by remember { mutableStateOf<Track?>(null) }
    val isCustomPlaylist = playlist != null

    BackHandler(enabled = isSelectionMode) {
        viewModel.clearSelection()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MusColors.Background),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top App Bar
            if (isSelectionMode) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MusColors.SurfaceElevated)
                        .padding(top = Spacing.xxxl, start = Spacing.sm, end = Spacing.sm, bottom = Spacing.xs),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { viewModel.clearSelection() }) {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = "Cancel Selection",
                                tint = MusColors.OnBackground,
                            )
                        }
                        Spacer(Modifier.width(Spacing.xs))
                        Text(
                            text = "${selectedTrackIds.size} selected",
                            style = MaterialTheme.typography.titleMedium,
                            color = MusColors.OnBackground,
                        )
                    }

                    TextButton(
                        onClick = {
                            if (selectedTrackIds.size == tracks.size) {
                                viewModel.clearSelection()
                            } else {
                                viewModel.selectAll()
                            }
                        }
                    ) {
                        Text(
                            text = if (selectedTrackIds.size == tracks.size) "Deselect All" else "Select All",
                            color = MusColors.OnBackground,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            } else {
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

                    if (isCustomPlaylist) {
                        Row {
                            IconButton(onClick = {
                                renameText = playlist?.name ?: ""
                                showRenameDialog = true
                            }) {
                                Icon(
                                    Icons.Rounded.Edit,
                                    contentDescription = "Rename Playlist",
                                    tint = MusColors.OnBackgroundSecondary,
                                )
                            }
                            IconButton(onClick = { showDeleteDialog = true }) {
                                Icon(
                                    Icons.Rounded.DeleteOutline,
                                    contentDescription = "Delete Playlist",
                                    tint = MusColors.OnBackgroundSecondary,
                                )
                            }
                        }
                    }
                }
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = if (isSelectionMode) 140.dp else 120.dp),
            ) {
                if (!isSelectionMode) {
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
                                if (tracks.isNotEmpty()) {
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
                                FilledTonalButton(
                                    onClick = { showAddSongsSheet = true },
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = MusColors.SurfaceElevated,
                                        contentColor = MusColors.OnBackground,
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                ) {
                                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(Spacing.xs))
                                    Text("Add Songs", style = MaterialTheme.typography.labelLarge)
                                }
                            }
                            Spacer(Modifier.height(Spacing.lg))
                        }
                    }
                }

                if (tracks.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(Spacing.xxl),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                "No songs in this playlist",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MusColors.OnBackgroundTertiary,
                            )
                            Spacer(Modifier.height(Spacing.md))
                            Button(
                                onClick = { showAddSongsSheet = true },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MusColors.OnBackground,
                                    contentColor = MusColors.Background,
                                ),
                                shape = RoundedCornerShape(8.dp),
                            ) {
                                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(Spacing.xs))
                                Text("Add Songs", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }

                itemsIndexed(tracks, key = { _, track -> track.id }) { index, track ->
                    AnimatedListItem(index = index) {
                        val isSelected = track.id in selectedTrackIds
                        TrackRow(
                            track = track,
                            isInSelectionMode = isSelectionMode,
                            isSelected = isSelected,
                            onClick = {
                                if (isSelectionMode) {
                                    viewModel.toggleTrackSelection(track.id)
                                } else {
                                    viewModel.playTrack(track)
                                }
                            },
                            onLongClick = {
                                if (isSelectionMode) {
                                    viewModel.toggleTrackSelection(track.id)
                                } else {
                                    viewModel.startSelectionMode(track.id)
                                }
                            },
                            onMoreClick = {
                                if (!isSelectionMode) {
                                    selectedTrackForMenu = track
                                }
                            },
                        )
                    }
                }
            }
        }

        // Floating multi-select action bar
        if (isSelectionMode && selectedTrackIds.isNotEmpty()) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 72.dp, start = Spacing.base, end = Spacing.base)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MusColors.SurfaceElevated,
                tonalElevation = 8.dp,
                shadowElevation = 8.dp,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { showBatchAddToPlaylistSheet = true }) {
                        Icon(
                            Icons.Rounded.BookmarkAdd,
                            contentDescription = "Add to playlist",
                            tint = MusColors.OnBackground,
                        )
                    }
                    IconButton(onClick = { showBatchMoveToPlaylistSheet = true }) {
                        Icon(
                            Icons.AutoMirrored.Rounded.DriveFileMove,
                            contentDescription = "Move to playlist",
                            tint = MusColors.OnBackground,
                        )
                    }
                    IconButton(onClick = { viewModel.playNextSelected() }) {
                        Icon(
                            Icons.AutoMirrored.Rounded.QueueMusic,
                            contentDescription = "Play next",
                            tint = MusColors.OnBackground,
                        )
                    }
                    IconButton(onClick = { viewModel.addToQueueSelected() }) {
                        Icon(
                            Icons.AutoMirrored.Rounded.PlaylistAdd,
                            contentDescription = "Add to queue",
                            tint = MusColors.OnBackground,
                        )
                    }
                    IconButton(onClick = { showBatchRemoveDialog = true }) {
                        Icon(
                            Icons.Rounded.DeleteOutline,
                            contentDescription = "Remove from playlist",
                            tint = MusColors.Error,
                        )
                    }
                }
            }
        }
    }

    // Batch Add to Playlist Sheet
    if (showBatchAddToPlaylistSheet) {
        AddToPlaylistSheet(
            playlists = allPlaylists,
            title = "Add Selected to Playlist",
            excludePlaylistId = playlist?.id,
            onPlaylistSelected = { chosenPlaylistId ->
                val chosen = allPlaylists.find { it.id == chosenPlaylistId }
                val count = selectedTrackIds.size
                viewModel.copySelectedTracks(chosenPlaylistId) { addedCount ->
                    if (addedCount == count) {
                        android.widget.Toast.makeText(
                            context,
                            "Added $count songs to \"${chosen?.name ?: "playlist"}\"",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    } else if (addedCount > 0) {
                        android.widget.Toast.makeText(
                            context,
                            "Added $addedCount songs to \"${chosen?.name ?: "playlist"}\" (${count - addedCount} already present)",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        android.widget.Toast.makeText(
                            context,
                            "All selected songs already in \"${chosen?.name ?: "playlist"}\"",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                showBatchAddToPlaylistSheet = false
            },
            onCreateNew = { name ->
                val count = selectedTrackIds.size
                viewModel.createPlaylistAndCopySelected(name) {
                    android.widget.Toast.makeText(
                        context,
                        "Created \"$name\" and added $count songs",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
                showBatchAddToPlaylistSheet = false
            },
            onDismiss = { showBatchAddToPlaylistSheet = false },
        )
    }

    // Batch Move to Playlist Sheet
    if (showBatchMoveToPlaylistSheet && playlist != null) {
        AddToPlaylistSheet(
            playlists = allPlaylists,
            title = "Move Selected to Playlist",
            excludePlaylistId = playlist?.id,
            onPlaylistSelected = { chosenPlaylistId ->
                val chosen = allPlaylists.find { it.id == chosenPlaylistId }
                val count = selectedTrackIds.size
                viewModel.moveSelectedTracks(chosenPlaylistId) { movedCount ->
                    if (movedCount == count) {
                        android.widget.Toast.makeText(
                            context,
                            "Moved $count songs to \"${chosen?.name ?: "playlist"}\"",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    } else if (movedCount > 0) {
                        android.widget.Toast.makeText(
                            context,
                            "Moved $movedCount songs to \"${chosen?.name ?: "playlist"}\"",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        android.widget.Toast.makeText(
                            context,
                            "All selected songs already in \"${chosen?.name ?: "playlist"}\"",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                showBatchMoveToPlaylistSheet = false
            },
            onCreateNew = { name ->
                val count = selectedTrackIds.size
                viewModel.createPlaylistAndMoveSelected(name) {
                    android.widget.Toast.makeText(
                        context,
                        "Created \"$name\" and moved $count songs",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
                showBatchMoveToPlaylistSheet = false
            },
            onDismiss = { showBatchMoveToPlaylistSheet = false },
        )
    }

    // Add Songs to Playlist Sheet
    if (showAddSongsSheet && playlist != null) {
        val existingTrackIds = remember(tracks) { tracks.map { it.id }.toSet() }
        AddSongsToPlaylistSheet(
            playlistName = playlist?.name ?: "Playlist",
            allTracks = allLibraryTracks,
            alreadyInTrackIds = existingTrackIds,
            onAddTracks = { trackIds ->
                viewModel.addTracksToCurrentPlaylist(trackIds) { addedCount ->
                    if (addedCount > 0) {
                        android.widget.Toast.makeText(
                            context,
                            "Added $addedCount ${if (addedCount == 1) "song" else "songs"} to \"${playlist?.name}\"",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        android.widget.Toast.makeText(
                            context,
                            "Selected songs are already in this playlist",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                showAddSongsSheet = false
            },
            onDismiss = { showAddSongsSheet = false }
        )
    }

    // Batch Remove confirmation dialog
    if (showBatchRemoveDialog && playlist != null) {
        AlertDialog(
            onDismissRequest = { showBatchRemoveDialog = false },
            title = {
                Text("Remove Songs?", color = MusColors.OnBackground)
            },
            text = {
                Text(
                    "Are you sure you want to remove ${selectedTrackIds.size} songs from \"${playlist?.name}\"? The songs will remain in your library.",
                    color = MusColors.OnBackgroundSecondary,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showBatchRemoveDialog = false
                        viewModel.removeSelectedTracks()
                    }
                ) {
                    Text("Remove", color = MusColors.Error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatchRemoveDialog = false }) {
                    Text("Cancel", color = MusColors.OnBackgroundSecondary)
                }
            },
            containerColor = MusColors.SurfaceElevated,
        )
    }

    SongMenuContainer(
        selectedTrack = selectedTrackForMenu,
        onDismissMenu = { selectedTrackForMenu = null },
        playlistId = playlist?.id,
    )

    if (showDeleteDialog && playlist != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = {
                Text("Delete Playlist", color = MusColors.OnBackground)
            },
            text = {
                Text(
                    "Are you sure you want to delete \"${playlist?.name}\"? The songs will remain in your library.",
                    color = MusColors.OnBackgroundSecondary,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        viewModel.deletePlaylist {
                            onBack()
                        }
                    }
                ) {
                    Text("Delete", color = MusColors.Error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel", color = MusColors.OnBackgroundSecondary)
                }
            },
            containerColor = MusColors.SurfaceElevated,
        )
    }

    if (showRenameDialog && playlist != null) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = {
                Text("Rename Playlist", color = MusColors.OnBackground)
            },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = MusColors.OnBackground,
                        unfocusedTextColor = MusColors.OnBackground,
                        focusedBorderColor = MusColors.OnBackground,
                        unfocusedBorderColor = MusColors.OnBackgroundSecondary,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = renameText.trim()
                        if (trimmed.isNotBlank()) {
                            viewModel.renamePlaylist(trimmed)
                        }
                        showRenameDialog = false
                    }
                ) {
                    Text("Save", color = MusColors.OnBackground)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("Cancel", color = MusColors.OnBackgroundSecondary)
                }
            },
            containerColor = MusColors.SurfaceElevated,
        )
    }
}
