package com.mus.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.mus.android.data.model.Track
import com.mus.android.ui.theme.MusColors
import com.mus.android.ui.theme.Spacing

/**
 * Bottom sheet to search and pick songs from the library to add directly into a playlist.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddSongsToPlaylistSheet(
    playlistName: String,
    allTracks: List<Track>,
    alreadyInTrackIds: Set<Long>,
    onAddTracks: (List<Long>) -> Unit,
    onDismiss: () -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
    val selectedTrackIds = remember { mutableStateMapOf<Long, Boolean>() }

    val filteredTracks = remember(allTracks, searchQuery) {
        if (searchQuery.isBlank()) {
            allTracks
        } else {
            val q = searchQuery.trim().lowercase()
            allTracks.filter {
                it.title.lowercase().contains(q) || it.artist.lowercase().contains(q)
            }
        }
    }

    val selectedCount = selectedTrackIds.count { it.value }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MusColors.SurfaceElevated,
        modifier = Modifier.fillMaxHeight(0.88f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .padding(horizontal = Spacing.base),
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Spacing.sm),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Add Songs",
                        style = MaterialTheme.typography.titleLarge,
                        color = MusColors.OnBackground,
                    )
                    Text(
                        text = "to \"$playlistName\"",
                        style = MaterialTheme.typography.bodySmall,
                        color = MusColors.OnBackgroundSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = "Close",
                        tint = MusColors.OnBackgroundSecondary,
                    )
                }
            }

            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Spacing.xs),
                placeholder = {
                    Text("Search songs or artists...", color = MusColors.OnBackgroundTertiary)
                },
                leadingIcon = {
                    Icon(
                        Icons.Rounded.Search,
                        contentDescription = null,
                        tint = MusColors.OnBackgroundTertiary,
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MusColors.OnBackground,
                    unfocusedBorderColor = MusColors.SurfaceVariant,
                    focusedTextColor = MusColors.OnBackground,
                    unfocusedTextColor = MusColors.OnBackground,
                    cursorColor = MusColors.OnBackground,
                ),
            )

            Spacer(Modifier.height(Spacing.xs))

            // Track list
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = Spacing.xs),
            ) {
                if (filteredTracks.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(Spacing.xxl),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                if (searchQuery.isBlank()) "No songs in library" else "No matching songs found",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MusColors.OnBackgroundTertiary,
                            )
                        }
                    }
                }

                items(filteredTracks, key = { it.id }) { track ->
                    val isAlreadyIn = track.id in alreadyInTrackIds
                    val isSelected = selectedTrackIds[track.id] == true

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !isAlreadyIn) {
                                selectedTrackIds[track.id] = !isSelected
                            }
                            .padding(vertical = Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Artwork thumbnail
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MusColors.SurfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (track.artworkUri != null) {
                                AsyncImage(
                                    model = track.artworkUri,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }

                        Spacer(Modifier.width(Spacing.md))

                        // Title & Artist
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = track.title,
                                style = MaterialTheme.typography.titleSmall,
                                color = if (isAlreadyIn) MusColors.OnBackgroundSecondary else MusColors.OnBackground,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = track.artist,
                                style = MaterialTheme.typography.bodySmall,
                                color = MusColors.OnBackgroundTertiary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        Spacer(Modifier.width(Spacing.sm))

                        if (isAlreadyIn) {
                            Text(
                                text = "In Playlist",
                                style = MaterialTheme.typography.labelSmall,
                                color = MusColors.OnBackgroundTertiary,
                                modifier = Modifier
                                    .background(
                                        MusColors.SurfaceVariant,
                                        RoundedCornerShape(6.dp),
                                    )
                                    .padding(horizontal = Spacing.xs, vertical = 2.dp),
                            )
                        } else {
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = { checked ->
                                    selectedTrackIds[track.id] = checked
                                },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = MusColors.OnBackground,
                                    checkmarkColor = MusColors.Background,
                                    uncheckedColor = MusColors.OnBackgroundSecondary,
                                ),
                            )
                        }
                    }
                }
            }

            // Bottom Add Button
            if (selectedCount > 0) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Spacing.md),
                    color = MusColors.SurfaceElevated,
                ) {
                    Button(
                        onClick = {
                            val idsToAdd = selectedTrackIds.filter { it.value }.keys.toList()
                            onAddTracks(idsToAdd)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MusColors.OnBackground,
                            contentColor = MusColors.Background,
                        ),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(
                            Icons.Rounded.Check,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(Spacing.sm))
                        Text(
                            text = "Add $selectedCount ${if (selectedCount == 1) "Song" else "Songs"} to Playlist",
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            } else {
                Spacer(Modifier.height(Spacing.lg))
            }
        }
    }
}
