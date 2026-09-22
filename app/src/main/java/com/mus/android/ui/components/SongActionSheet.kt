package com.mus.android.ui.components

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.mus.android.data.model.Track
import com.mus.android.ui.theme.MusColors
import com.mus.android.ui.theme.Spacing
import com.mus.android.ui.viewmodel.SongMenuViewModel

/**
 * Modern, reusable Song Action Bottom Sheet for MUS.
 * Provides rich actions (Play, Play next, Add to queue, Add to playlist, Favorite,
 * Go to album/artist, Share, View details, and safe confirmed delete/remove).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongActionMenuSheet(
    track: Track,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onToggleFavorite: () -> Unit,
    onShare: () -> Unit,
    onViewDetails: () -> Unit,
    onGoToAlbum: (() -> Unit)? = null,
    onGoToArtist: (() -> Unit)? = null,
    onChangeArtwork: (() -> Unit)? = null,   // opens manual picker
    onFixArtwork: (() -> Unit)? = null,       // auto re-fetch (legacy)
    // Cross-playlist actions when inside a playlist
    playlistId: Long? = null,
    onMoveToPlaylist: (() -> Unit)? = null,
    // Context-dependent destructive actions
    removeFromPlaylistLabel: String? = null,
    onRequestRemoveFromPlaylist: (() -> Unit)? = null,
    onRequestDeleteFromLibrary: (() -> Unit)? = null,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MusColors.SurfaceElevated,
        contentColor = MusColors.OnBackground,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.xl)
                .verticalScroll(rememberScrollState())
        ) {
            // Header with song info
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.base, vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AsyncImage(
                    model = track.artworkUri,
                    contentDescription = track.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
                Spacer(Modifier.width(Spacing.md))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MusColors.OnBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "${track.artist} • ${track.albumTitle}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MusColors.OnBackgroundSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${track.language} • ${formatDuration(track.duration)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MusColors.OnBackgroundTertiary,
                        maxLines = 1,
                    )
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = Spacing.sm),
                color = MusColors.Divider
            )

            // Primary actions
            SongActionItem(
                icon = Icons.Rounded.PlayArrow,
                title = "Play",
                onClick = onPlay,
            )

            SongActionItem(
                icon = Icons.AutoMirrored.Rounded.QueueMusic,
                title = "Play next",
                onClick = onPlayNext,
            )

            SongActionItem(
                icon = Icons.AutoMirrored.Rounded.PlaylistAdd,
                title = "Add to queue",
                onClick = onAddToQueue,
            )

            if (playlistId != null && onMoveToPlaylist != null) {
                SongActionItem(
                    icon = Icons.AutoMirrored.Rounded.DriveFileMove,
                    title = "Move to playlist",
                    onClick = onMoveToPlaylist,
                )
            }

            SongActionItem(
                icon = if (playlistId != null) Icons.Rounded.ContentCopy else Icons.Rounded.BookmarkAdd,
                title = if (playlistId != null) "Copy to playlist" else "Add to playlist",
                onClick = onAddToPlaylist,
            )

            SongActionItem(
                icon = if (track.isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                title = if (track.isFavorite) "Remove from favorites" else "Add to favorites",
                tint = if (track.isFavorite) MusColors.Favorite else MusColors.OnBackground,
                onClick = onToggleFavorite,
            )

            if (onGoToAlbum != null && track.albumId > 0) {
                SongActionItem(
                    icon = Icons.Rounded.Album,
                    title = "Go to album",
                    subtitle = track.albumTitle,
                    onClick = onGoToAlbum,
                )
            }

            if (onGoToArtist != null && track.artist.isNotBlank()) {
                SongActionItem(
                    icon = Icons.Rounded.Person,
                    title = "Go to artist",
                    subtitle = track.artist,
                    onClick = onGoToArtist,
                )
            }

            SongActionItem(
                icon = Icons.Rounded.Share,
                title = "Share",
                onClick = onShare,
            )

            if (onChangeArtwork != null) {
                SongActionItem(
                    icon = Icons.Rounded.ImageSearch,
                    title = "Change Artwork",
                    subtitle = "Search and select the correct cover",
                    onClick = onChangeArtwork,
                )
            }

            if (onFixArtwork != null) {
                SongActionItem(
                    icon = Icons.Rounded.Refresh,
                    title = "Re-fetch artwork automatically",
                    subtitle = "Clears cached cover and looks it up again",
                    onClick = onFixArtwork,
                )
            }

            SongActionItem(
                icon = Icons.Rounded.Info,
                title = "Song details",
                onClick = onViewDetails,
            )

            // Destructive actions section (visually separated)
            if (onRequestRemoveFromPlaylist != null || onRequestDeleteFromLibrary != null) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = Spacing.sm),
                    color = MusColors.Divider
                )

                if (onRequestRemoveFromPlaylist != null) {
                    SongActionItem(
                        icon = Icons.Rounded.DeleteOutline,
                        title = removeFromPlaylistLabel ?: "Remove from playlist",
                        tint = MusColors.Error,
                        textColor = MusColors.Error,
                        onClick = onRequestRemoveFromPlaylist,
                    )
                }

                if (onRequestDeleteFromLibrary != null) {
                    SongActionItem(
                        icon = Icons.Rounded.DeleteOutline,
                        title = "Delete from library",
                        tint = MusColors.Error,
                        textColor = MusColors.Error,
                        onClick = onRequestDeleteFromLibrary,
                    )
                }
            }
        }
    }
}

@Composable
private fun SongActionItem(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    tint: Color = MusColors.OnBackground,
    textColor: Color = MusColors.OnBackground,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = Spacing.base, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(Spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MusColors.OnBackgroundSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Dialog displaying technical and metadata details for a song.
 */
@Composable
fun SongDetailsDialog(
    track: Track,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Song Details",
                style = MaterialTheme.typography.titleLarge,
                color = MusColors.OnBackground,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                DetailItem("Title", track.title)
                DetailItem("Artist", track.artist)
                if (track.albumArtist.isNotBlank() && track.albumArtist != track.artist) {
                    DetailItem("Album Artist", track.albumArtist)
                }
                DetailItem("Album", track.albumTitle)
                if (track.trackNumber > 0) {
                    DetailItem("Track", track.trackNumber.toString())
                }
                if (track.discNumber > 0) {
                    DetailItem("Disc", track.discNumber.toString())
                }
                if (!track.genre.isNullOrBlank()) {
                    DetailItem("Genre", track.genre)
                }
                if (!track.composer.isNullOrBlank()) {
                    DetailItem("Composer", track.composer)
                }
                if (track.year > 0) {
                    DetailItem("Year", track.year.toString())
                }
                DetailItem("Duration", formatDuration(track.duration))
                if (!track.codec.isNullOrBlank()) {
                    DetailItem("Audio Codec", track.codec)
                }
                if (track.sampleRate > 0) {
                    DetailItem("Sample Rate", "${track.sampleRate} Hz")
                }
                if (track.bitDepth > 0) {
                    DetailItem("Bit Depth", "${track.bitDepth}-bit")
                }
                if (track.bitrate > 0) {
                    DetailItem("Bitrate", "${track.bitrate} kbps")
                }
                if (track.size > 0) {
                    val sizeMb = track.size.toDouble() / (1024 * 1024)
                    DetailItem("File Size", "%.2f MB".format(sizeMb))
                }
                if (!track.language.isBlank()) {
                    DetailItem("Language", track.language)
                }
                val filePath = track.path ?: track.uri
                if (filePath.isNotBlank()) {
                    DetailItem("Location", filePath)
                }
                DetailItem("Metadata Source", track.metadataSource)
                val artworkSource = when {
                    track.artworkUri.isNullOrBlank() -> "None / Placeholder"
                    track.artworkUri.contains("/artwork/") -> "External Provider (Cached)"
                    track.artworkUri.contains("art_") -> "Embedded (Extracted)"
                    track.artworkUri.contains("content://media/external/audio/albumart") -> "MediaStore Album Art"
                    else -> "Embedded File"
                }
                DetailItem("Artwork Source", artworkSource)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = MusColors.OnBackground)
            }
        },
        containerColor = MusColors.SurfaceElevated,
    )
}

@Composable
private fun DetailItem(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MusColors.OnBackgroundTertiary,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MusColors.OnBackground,
        )
    }
}

/**
 * Reusable full controller handling Song Action Sheet, AddToPlaylistSheet,
 * SongDetailsDialog, and destructive deletion confirmations seamlessly.
 */
@Composable
fun SongMenuContainer(
    selectedTrack: Track?,
    onDismissMenu: () -> Unit,
    playlistId: Long? = null,
    onNavigateToAlbum: ((Long) -> Unit)? = null,
    onNavigateToArtist: ((String) -> Unit)? = null,
    onTrackDeleted: (() -> Unit)? = null,
    onPlayTrack: ((Track) -> Unit)? = null,
    viewModel: SongMenuViewModel = hiltViewModel(),
) {
    var activeTrack by remember { mutableStateOf<Track?>(null) }
    var showActionSheet by remember { mutableStateOf(false) }
    var showArtworkSearch by remember { mutableStateOf(false) }

    LaunchedEffect(selectedTrack) {
        if (selectedTrack != null) {
            activeTrack = selectedTrack
            showActionSheet = true
        }
    }

    val currentTrack = activeTrack ?: return

    val context = LocalContext.current
    val playlists by viewModel.playlists.collectAsState()

    var showDetailsDialog by remember { mutableStateOf(false) }
    var showAddToPlaylistSheet by remember { mutableStateOf(false) }
    var showMoveToPlaylistSheet by remember { mutableStateOf(false) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    var showRemoveFromPlaylistConfirmation by remember { mutableStateOf(false) }
    var existingPlaylistIdsForTrack by remember { mutableStateOf<Set<Long>>(emptySet()) }

    LaunchedEffect(currentTrack.id) {
        existingPlaylistIdsForTrack = viewModel.getPlaylistIdsForTrack(currentTrack.id).toSet()
    }

    val closeAll = {
        showActionSheet = false
        showAddToPlaylistSheet = false
        showMoveToPlaylistSheet = false
        showDetailsDialog = false
        showDeleteConfirmation = false
        showRemoveFromPlaylistConfirmation = false
        showArtworkSearch = false
        activeTrack = null
        onDismissMenu()
    }

    if (showActionSheet) {
        SongActionMenuSheet(
            track = currentTrack,
            playlistId = playlistId,
            onDismiss = {
                showActionSheet = false
                if (!showAddToPlaylistSheet && !showMoveToPlaylistSheet && !showDetailsDialog && !showDeleteConfirmation && !showRemoveFromPlaylistConfirmation) {
                    closeAll()
                }
            },
            onPlay = {
                closeAll()
                if (onPlayTrack != null) {
                    onPlayTrack(currentTrack)
                } else {
                    viewModel.play(currentTrack)
                }
            },
            onPlayNext = {
                closeAll()
                viewModel.playNext(currentTrack)
            },
            onAddToQueue = {
                closeAll()
                viewModel.addToQueue(currentTrack)
            },
            onAddToPlaylist = {
                showActionSheet = false
                showAddToPlaylistSheet = true
            },
            onMoveToPlaylist = if (playlistId != null) {
                {
                    showActionSheet = false
                    showMoveToPlaylistSheet = true
                }
            } else null,
            onToggleFavorite = {
                closeAll()
                viewModel.toggleFavorite(currentTrack.id)
            },
            onShare = {
                closeAll()
                shareTrack(context, currentTrack)
            },
            onViewDetails = {
                showActionSheet = false
                showDetailsDialog = true
            },
            onGoToAlbum = if (onNavigateToAlbum != null && currentTrack.albumId > 0) {
                {
                    closeAll()
                    onNavigateToAlbum(currentTrack.albumId)
                }
            } else null,
            onGoToArtist = if (onNavigateToArtist != null && currentTrack.artist.isNotBlank()) {
                {
                    closeAll()
                    onNavigateToArtist(currentTrack.artist)
                }
            } else null,
            onChangeArtwork = {
                showActionSheet = false
                showArtworkSearch = true
            },
            onFixArtwork = {
                closeAll()
                viewModel.fixWrongArtwork(currentTrack.id)
                android.widget.Toast.makeText(
                    context,
                    "Searching for correct cover...",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            },
            removeFromPlaylistLabel = if (playlistId != null) "Remove from this playlist" else null,
            onRequestRemoveFromPlaylist = if (playlistId != null) {
                {
                    showActionSheet = false
                    showRemoveFromPlaylistConfirmation = true
                }
            } else null,
            onRequestDeleteFromLibrary = if (playlistId == null) {
                {
                    showActionSheet = false
                    showDeleteConfirmation = true
                }
            } else null,
        )
    }

    // Add To Playlist / Copy to Playlist sub-sheet
    if (showAddToPlaylistSheet) {
        AddToPlaylistSheet(
            playlists = playlists,
            title = if (playlistId != null) "Copy to Playlist" else "Add to Playlist",
            alreadyInPlaylistIds = existingPlaylistIdsForTrack,
            onPlaylistSelected = { chosenPlaylistId ->
                val chosen = playlists.find { it.id == chosenPlaylistId }
                viewModel.addToPlaylist(chosenPlaylistId, currentTrack.id) { added ->
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
                closeAll()
            },
            onCreateNew = { name ->
                viewModel.createPlaylistAndAddTrack(name, currentTrack.id)
                android.widget.Toast.makeText(
                    context,
                    "Created \"$name\" and added track",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                closeAll()
            },
            onDismiss = { closeAll() }
        )
    }

    // Move To Playlist sub-sheet
    if (showMoveToPlaylistSheet && playlistId != null) {
        AddToPlaylistSheet(
            playlists = playlists,
            title = "Move to Playlist",
            excludePlaylistId = playlistId,
            alreadyInPlaylistIds = existingPlaylistIdsForTrack,
            onPlaylistSelected = { chosenPlaylistId ->
                val chosen = playlists.find { it.id == chosenPlaylistId }
                viewModel.moveBetweenPlaylists(playlistId, chosenPlaylistId, currentTrack.id) {
                    android.widget.Toast.makeText(
                        context,
                        "Moved to \"${chosen?.name ?: "playlist"}\"",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    onTrackDeleted?.invoke()
                }
                closeAll()
            },
            onCreateNew = { name ->
                viewModel.createPlaylistAndMoveTrack(name, playlistId, currentTrack.id) {
                    android.widget.Toast.makeText(
                        context,
                        "Created \"$name\" and moved track",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    onTrackDeleted?.invoke()
                }
                closeAll()
            },
            onDismiss = { closeAll() }
        )
    }

    // Song details dialog
    if (showDetailsDialog) {
        SongDetailsDialog(
            track = currentTrack,
            onDismiss = { closeAll() }
        )
    }

    // Remove from playlist confirmation dialog
    if (showRemoveFromPlaylistConfirmation && playlistId != null) {
        AlertDialog(
            onDismissRequest = { closeAll() },
            title = {
                Text("Remove from Playlist?", color = MusColors.OnBackground)
            },
            text = {
                Text(
                    "Are you sure you want to remove \"${currentTrack.title}\" from this playlist? The song will remain in your library.",
                    color = MusColors.OnBackgroundSecondary,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.removeFromPlaylist(playlistId, currentTrack.id)
                        onTrackDeleted?.invoke()
                        closeAll()
                    }
                ) {
                    Text("Remove", color = MusColors.Error)
                }
            },
            dismissButton = {
                TextButton(onClick = { closeAll() }) {
                    Text("Cancel", color = MusColors.OnBackgroundSecondary)
                }
            },
            containerColor = MusColors.SurfaceElevated,
        )
    }

    // Delete from library confirmation dialog
    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { closeAll() },
            title = {
                Text("Delete from Library?", color = MusColors.OnBackground)
            },
            text = {
                Text(
                    "Are you sure you want to remove \"${currentTrack.title}\" from your library? The physical audio file on your device storage will not be deleted.",
                    color = MusColors.OnBackgroundSecondary,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteFromLibrary(currentTrack.id)
                        onTrackDeleted?.invoke()
                        closeAll()
                    }
                ) {
                    Text("Delete", color = MusColors.Error)
                }
            },
            dismissButton = {
                TextButton(onClick = { closeAll() }) {
                    Text("Cancel", color = MusColors.OnBackgroundSecondary)
                }
            },
            containerColor = MusColors.SurfaceElevated,
        )
    }

    // Artwork search — full screen overlay
    if (showArtworkSearch) {
        ArtworkSearchSheet(
            track = currentTrack,
            fromAlbum = false,
            onBack = {
                showArtworkSearch = false
                onDismissMenu()
            },
            onApplied = {
                showArtworkSearch = false
                android.widget.Toast.makeText(
                    context,
                    "Artwork updated",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                onDismissMenu()
            },
        )
    }
}


private fun shareTrack(context: Context, track: Track) {
    val shareText = "Listening to \"${track.title}\" by ${track.artist} on MUS"
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, track.title)
        putExtra(Intent.EXTRA_TEXT, shareText)
    }
    context.startActivity(Intent.createChooser(intent, "Share Song"))
}
