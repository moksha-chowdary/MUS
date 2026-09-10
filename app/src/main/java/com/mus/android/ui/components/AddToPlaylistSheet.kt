package com.mus.android.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mus.android.data.model.Playlist
import com.mus.android.ui.theme.MusColors
import com.mus.android.ui.theme.Spacing

/**
 * Bottom sheet for adding/moving a track to an existing playlist or creating a new one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistSheet(
    playlists: List<Playlist>,
    title: String = "Add to Playlist",
    excludePlaylistId: Long? = null,
    alreadyInPlaylistIds: Set<Long> = emptySet(),
    onPlaylistSelected: (Long) -> Unit,
    onCreateNew: ((String) -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    val displayPlaylists = remember(playlists, excludePlaylistId) {
        if (excludePlaylistId != null) {
            playlists.filter { it.id != excludePlaylistId }
        } else {
            playlists
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MusColors.SurfaceElevated,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.xl),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MusColors.OnBackground,
                modifier = Modifier.padding(horizontal = Spacing.base, vertical = Spacing.sm),
            )

            // Create new
            if (onCreateNew != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showCreateDialog = true }
                        .padding(horizontal = Spacing.base, vertical = Spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.Add,
                        contentDescription = "Create",
                        tint = MusColors.OnBackground,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(Spacing.md))
                    Text(
                        "Create new playlist",
                        style = MaterialTheme.typography.titleSmall,
                        color = MusColors.OnBackground,
                    )
                }
            }

            // Existing playlists
            if (displayPlaylists.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Spacing.md, horizontal = Spacing.base),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "No playlists available",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MusColors.OnBackgroundTertiary,
                    )
                }
            } else {
                displayPlaylists.forEach { playlist ->
                    val isAlreadyIn = playlist.id in alreadyInPlaylistIds
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPlaylistSelected(playlist.id) }
                            .padding(horizontal = Spacing.base, vertical = Spacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Rounded.QueueMusic,
                            contentDescription = null,
                            tint = if (isAlreadyIn) MusColors.OnBackgroundTertiary else MusColors.OnBackgroundSecondary,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(Modifier.width(Spacing.md))
                        Text(
                            text = playlist.name,
                            style = MaterialTheme.typography.titleSmall,
                            color = if (isAlreadyIn) MusColors.OnBackgroundSecondary else MusColors.OnBackground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (isAlreadyIn) {
                            Spacer(Modifier.width(Spacing.sm))
                            Text(
                                text = "✓ Added",
                                style = MaterialTheme.typography.labelMedium,
                                color = MusColors.OnBackgroundSecondary,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("New Playlist", color = MusColors.OnBackground) },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    placeholder = { Text("Playlist name") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = MusColors.OnBackground,
                        unfocusedTextColor = MusColors.OnBackground,
                        cursorColor = MusColors.OnBackground,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newName.isNotBlank()) {
                        onCreateNew?.invoke(newName.trim())
                        newName = ""
                        showCreateDialog = false
                    }
                }) { Text("Create", color = MusColors.OnBackground) }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text("Cancel", color = MusColors.OnBackgroundSecondary)
                }
            },
            containerColor = MusColors.SurfaceElevated,
        )
    }
}
