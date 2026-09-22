package com.mus.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.mus.android.data.classifier.LanguageClassifier
import com.mus.android.data.model.Playlist
import com.mus.android.data.model.Track
import com.mus.android.ui.components.AlbumCard
import com.mus.android.ui.components.SongMenuContainer
import com.mus.android.ui.components.TrackRow
import com.mus.android.ui.theme.MusColors
import com.mus.android.ui.theme.Spacing
import com.mus.android.ui.viewmodel.LibraryViewModel

@Composable
fun LibraryScreen(
    onAlbumClick: (Long) -> Unit,
    onArtistClick: (Long) -> Unit,
    onPlaylistClick: (Long) -> Unit,
    onTrackClick: (Track, List<Track>) -> Unit,
    onDownloadQueueClick: () -> Unit = {},
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val albums by viewModel.albums.collectAsState()
    val artists by viewModel.artists.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    val favorites by viewModel.favorites.collectAsState()
    val pendingDownloadCount by viewModel.pendingDownloadCount.collectAsState()
    val allTracks by viewModel.allTracks.collectAsState()
    val filteredTracks by viewModel.filteredTracks.collectAsState()
    val selectedLanguageFilter by viewModel.selectedLanguageFilter.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Playlists", "Albums", "Artists", "Songs", "Favorites")

    var showCreateDialog by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }
    var playlistToDelete by remember { mutableStateOf<Playlist?>(null) }
    var selectedTrackForMenu by remember { mutableStateOf<Track?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
    ) {
        Spacer(Modifier.height(Spacing.xxxl))

        Text(
            "Library",
            style = MaterialTheme.typography.titleLarge,
            color = MusColors.OnBackground,
            modifier = Modifier.padding(horizontal = Spacing.base),
        )

        Spacer(Modifier.height(Spacing.base))

        // Tab row
        ScrollableTabRow(
            selectedTabIndex = selectedTab,
            containerColor = Color.Transparent,
            contentColor = MusColors.OnBackground,
            edgePadding = Spacing.base,
            indicator = { tabPositions ->
                if (selectedTab < tabPositions.size) {
                    TabRowDefaults.SecondaryIndicator(
                        Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                        color = MusColors.OnBackground,
                        height = 2.dp,
                    )
                }
            },
            divider = {},
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = {
                        Text(
                            title,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (selectedTab == index) MusColors.OnBackground
                                    else MusColors.OnBackgroundTertiary,
                        )
                    }
                )
            }
        }

        Spacer(Modifier.height(Spacing.sm))

        when (selectedTab) {
            0 -> {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 120.dp),
                ) {
                    // Create playlist button
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showCreateDialog = true }
                                .padding(horizontal = Spacing.base, vertical = Spacing.md),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Rounded.Add,
                                contentDescription = "Create Playlist",
                                tint = MusColors.OnBackground,
                                modifier = Modifier
                                    .size(48.dp)
                                    .background(MusColors.SurfaceVariant, RoundedCornerShape(6.dp))
                                    .padding(12.dp),
                            )
                            Spacer(Modifier.width(Spacing.md))
                            Text(
                                "Create Playlist",
                                style = MaterialTheme.typography.titleSmall,
                                color = MusColors.OnBackground,
                            )
                        }
                    }

                    // Favorites shortcut
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedTab = 4 }
                                .padding(horizontal = Spacing.base, vertical = Spacing.md),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Rounded.Favorite,
                                contentDescription = null,
                                tint = MusColors.Favorite,
                                modifier = Modifier
                                    .size(48.dp)
                                    .background(MusColors.SurfaceVariant, RoundedCornerShape(6.dp))
                                    .padding(12.dp),
                            )
                            Spacer(Modifier.width(Spacing.md))
                            Column {
                                Text(
                                    "Favorites",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MusColors.OnBackground,
                                )
                                Text(
                                    "${favorites.size} songs",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MusColors.OnBackgroundSecondary,
                                )
                            }
                        }
                    }

                    // MUS — To Download shortcut
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onDownloadQueueClick() }
                                .padding(horizontal = Spacing.base, vertical = Spacing.md),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.AutoMirrored.Rounded.PlaylistAdd,
                                contentDescription = null,
                                tint = MusColors.OnBackground,
                                modifier = Modifier
                                    .size(48.dp)
                                    .background(MusColors.SurfaceVariant, RoundedCornerShape(6.dp))
                                    .padding(12.dp),
                            )
                            Spacer(Modifier.width(Spacing.md))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "MUS — To Download",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MusColors.OnBackground,
                                )
                                Text(
                                    if (pendingDownloadCount > 0) "$pendingDownloadCount songs to discover"
                                    else "Download planning list",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MusColors.OnBackgroundSecondary,
                                )
                            }
                            if (pendingDownloadCount > 0) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MusColors.OnBackground.copy(alpha = 0.12f),
                                ) {
                                    Text(
                                        text = "$pendingDownloadCount",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MusColors.OnBackground,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }

                    // All Playlists
                    if (playlists.isNotEmpty()) {
                        items(playlists, key = { it.id }) { playlist ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPlaylistClick(playlist.id) }
                                    .padding(horizontal = Spacing.base, vertical = Spacing.md),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Rounded.QueueMusic,
                                    contentDescription = null,
                                    tint = MusColors.OnBackgroundSecondary,
                                    modifier = Modifier
                                        .size(48.dp)
                                        .background(MusColors.SurfaceVariant, RoundedCornerShape(6.dp))
                                        .padding(12.dp),
                                )
                                Spacer(Modifier.width(Spacing.md))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        playlist.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MusColors.OnBackground,
                                    )
                                    Text(
                                        "Playlist",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MusColors.OnBackgroundSecondary,
                                    )
                                }
                                IconButton(
                                    onClick = { playlistToDelete = playlist }
                                ) {
                                    Icon(
                                        Icons.Rounded.DeleteOutline,
                                        contentDescription = "Delete Playlist",
                                        tint = MusColors.OnBackgroundTertiary,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            1 -> {
                // Albums grid
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(150.dp),
                    contentPadding = PaddingValues(
                        start = Spacing.md,
                        end = Spacing.md,
                        bottom = 120.dp,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    items(albums, key = { it.id }) { album ->
                        AlbumCard(
                            title = album.title,
                            artist = album.artist,
                            artworkUri = album.artworkUri,
                            onClick = { onAlbumClick(album.id) },
                        )
                    }
                }
            }

            2 -> {
                // Artists
                LazyColumn(contentPadding = PaddingValues(bottom = 120.dp)) {
                    items(artists, key = { it.id }) { artist ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onArtistClick(artist.id) }
                                .padding(horizontal = Spacing.base, vertical = Spacing.md),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AsyncImage(
                                model = artist.artworkUri,
                                contentDescription = artist.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(24.dp)),
                            )
                            Spacer(Modifier.width(Spacing.md))
                            Column {
                                Text(
                                    artist.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MusColors.OnBackground,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "${artist.albumCount} albums • ${artist.trackCount} songs",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MusColors.OnBackgroundSecondary,
                                )
                            }
                        }
                    }
                }
            }

            3 -> {
                // All songs with Language Filter Chips
                Column(modifier = Modifier.fillMaxSize()) {
                    val hasUnknown = remember(allTracks) {
                        allTracks.any { it.language.equals("Unknown", ignoreCase = true) || it.language.isBlank() }
                    }
                    val filterOptions = remember(allTracks.size, hasUnknown) {
                        buildList {
                            add("All" to "All (${allTracks.size})")
                            add("Hindi" to "Hindi")
                            add("English" to "English")
                            add("Other" to "Other")
                            if (hasUnknown) {
                                add("Unknown" to "Unknown")
                            }
                        }
                    }
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = Spacing.base, vertical = Spacing.xs),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        items(filterOptions) { (key, label) ->
                            FilterChip(
                                selected = selectedLanguageFilter == key,
                                onClick = { viewModel.setLanguageFilter(key) },
                                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MusColors.OnBackground,
                                    selectedLabelColor = MusColors.Background,
                                    containerColor = MusColors.SurfaceVariant,
                                    labelColor = MusColors.OnBackgroundSecondary,
                                ),
                                border = null,
                            )
                        }
                    }

                    if (filteredTracks.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(Spacing.xxl),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "No songs found for $selectedLanguageFilter",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MusColors.OnBackgroundTertiary,
                            )
                        }
                    } else {
                        LazyColumn(contentPadding = PaddingValues(bottom = 120.dp)) {
                            items(filteredTracks, key = { it.id }) { track ->
                                TrackRow(
                                    track = track,
                                    onClick = { onTrackClick(track, filteredTracks) },
                                    onFavoriteToggle = { viewModel.toggleFavorite(track.id) },
                                    onMoreClick = { selectedTrackForMenu = track },
                                )
                            }
                        }
                    }
                }
            }

            4 -> {
                // Favorites
                if (favorites.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "No favorites yet",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MusColors.OnBackgroundTertiary,
                        )
                    }
                } else {
                    LazyColumn(contentPadding = PaddingValues(bottom = 120.dp)) {
                        items(favorites, key = { it.id }) { track ->
                            TrackRow(
                                track = track,
                                onClick = { onTrackClick(track, favorites) },
                                onFavoriteToggle = { viewModel.toggleFavorite(track.id) },
                                onMoreClick = { selectedTrackForMenu = track },
                            )
                        }
                    }
                }
            }
        }
    }

    SongMenuContainer(
        selectedTrack = selectedTrackForMenu,
        onDismissMenu = { selectedTrackForMenu = null },
        onNavigateToAlbum = onAlbumClick,
    )

    // Create playlist dialog
    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false; newPlaylistName = "" },
            title = { Text("New Playlist", color = MusColors.OnBackground) },
            text = {
                OutlinedTextField(
                    value = newPlaylistName,
                    onValueChange = { newPlaylistName = it },
                    placeholder = { Text("Playlist name") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = MusColors.OnBackground,
                        unfocusedTextColor = MusColors.OnBackground,
                        cursorColor = MusColors.OnBackground,
                        focusedBorderColor = MusColors.OnBackgroundSecondary,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newPlaylistName.isNotBlank()) {
                        viewModel.createPlaylist(newPlaylistName.trim())
                        newPlaylistName = ""
                        showCreateDialog = false
                    }
                }) {
                    Text("Create", color = MusColors.OnBackground)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false; newPlaylistName = "" }) {
                    Text("Cancel", color = MusColors.OnBackgroundSecondary)
                }
            },
            containerColor = MusColors.SurfaceElevated,
        )
    }

    if (playlistToDelete != null) {
        val target = playlistToDelete!!
        AlertDialog(
            onDismissRequest = { playlistToDelete = null },
            title = {
                Text("Delete Playlist", color = MusColors.OnBackground)
            },
            text = {
                Text(
                    "Are you sure you want to delete \"${target.name}\"? The songs will remain in your library.",
                    color = MusColors.OnBackgroundSecondary,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deletePlaylist(target.id)
                        playlistToDelete = null
                    }
                ) {
                    Text("Delete", color = MusColors.Error)
                }
            },
            dismissButton = {
                TextButton(onClick = { playlistToDelete = null }) {
                    Text("Cancel", color = MusColors.OnBackgroundSecondary)
                }
            },
            containerColor = MusColors.SurfaceElevated,
        )
    }
}
