package com.mus.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import com.mus.android.ui.components.AnimatedListItem
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mus.android.data.model.Track
import com.mus.android.ui.components.AlbumCard
import com.mus.android.ui.components.SongMenuContainer
import com.mus.android.ui.components.TrackRow
import com.mus.android.ui.theme.MusColors
import com.mus.android.ui.theme.Spacing
import com.mus.android.ui.viewmodel.SearchViewModel

@Composable
fun SearchScreen(
    onTrackClick: (Track, List<Track>) -> Unit,
    onAlbumClick: (Long) -> Unit,
    onArtistClick: (Long) -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val query by viewModel.query.collectAsState()
    val trackResults by viewModel.trackResults.collectAsState()
    val albumResults by viewModel.albumResults.collectAsState()
    val artistResults by viewModel.artistResults.collectAsState()
    val hasResults by viewModel.hasResults.collectAsState()
    val focusRequester = remember { FocusRequester() }
    var selectedTrackForMenu by remember { mutableStateOf<Track?>(null) }

    var selectedFilter by remember { mutableStateOf("All") }
    val filterOptions = listOf("All", "Songs", "Albums", "Artists")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
    ) {
        Spacer(Modifier.height(Spacing.xxxl))

        // Search bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.base)
                .background(MusColors.SurfaceVariant, MaterialTheme.shapes.medium)
                .padding(horizontal = Spacing.md, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.Search,
                contentDescription = null,
                tint = MusColors.OnBackgroundTertiary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(Spacing.sm))
            BasicTextField(
                value = query,
                onValueChange = { viewModel.updateQuery(it) },
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MusColors.OnBackground,
                ),
                cursorBrush = SolidColor(MusColors.OnBackground),
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
                decorationBox = { innerTextField ->
                    if (query.isEmpty()) {
                        Text(
                            "Search songs, albums, artists...",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MusColors.OnBackgroundTertiary,
                        )
                    }
                    innerTextField()
                }
            )
            if (query.isNotEmpty()) {
                IconButton(
                    onClick = { viewModel.updateQuery("") },
                    modifier = Modifier.size(20.dp),
                ) {
                    Icon(
                        Icons.Rounded.Clear,
                        contentDescription = "Clear",
                        tint = MusColors.OnBackgroundTertiary,
                    )
                }
            }
        }

        // Filter chips when query is not blank
        if (query.isNotBlank()) {
            Spacer(Modifier.height(Spacing.sm))
            LazyRow(
                contentPadding = PaddingValues(horizontal = Spacing.base),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                items(filterOptions) { filter ->
                    val isSelected = selectedFilter == filter
                    FilterChip(
                        selected = isSelected,
                        onClick = { selectedFilter = filter },
                        label = { Text(filter, style = MaterialTheme.typography.labelSmall) },
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
        }

        Spacer(Modifier.height(Spacing.sm))

        if (query.isEmpty()) {
            // Empty state
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Search your music library",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MusColors.OnBackgroundTertiary,
                    textAlign = TextAlign.Center,
                )
            }
        } else if (!hasResults) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "No results for \"$query\"",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MusColors.OnBackgroundTertiary,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(bottom = 120.dp),
            ) {
                // Artists section
                if ((selectedFilter == "All" || selectedFilter == "Artists") && artistResults.isNotEmpty()) {
                    item {
                        Text(
                            "Artists",
                            style = MaterialTheme.typography.titleSmall,
                            color = MusColors.OnBackground,
                            modifier = Modifier.padding(horizontal = Spacing.base, vertical = Spacing.xs),
                        )
                        Spacer(Modifier.height(Spacing.xs))
                    }
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = Spacing.base),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                        ) {
                            items(artistResults, key = { it.id }) { artist ->
                                com.mus.android.ui.components.ArtistCard(
                                    name = artist.name,
                                    artworkUri = artist.artworkUri,
                                    onClick = { onArtistClick(artist.id) },
                                )
                            }
                        }
                        Spacer(Modifier.height(Spacing.md))
                    }
                }

                // Albums section
                if ((selectedFilter == "All" || selectedFilter == "Albums") && albumResults.isNotEmpty()) {
                    item {
                        Text(
                            "Albums",
                            style = MaterialTheme.typography.titleSmall,
                            color = MusColors.OnBackground,
                            modifier = Modifier.padding(horizontal = Spacing.base, vertical = Spacing.xs),
                        )
                        Spacer(Modifier.height(Spacing.xs))
                    }
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = Spacing.base),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        ) {
                            items(albumResults, key = { it.id }) { album ->
                                com.mus.android.ui.components.AlbumCard(
                                    title = album.title,
                                    artist = album.artist,
                                    artworkUri = album.artworkUri,
                                    onClick = { onAlbumClick(album.id) },
                                )
                            }
                        }
                        Spacer(Modifier.height(Spacing.md))
                    }
                }

                // Songs section
                if ((selectedFilter == "All" || selectedFilter == "Songs") && trackResults.isNotEmpty()) {
                    item {
                        Text(
                            "Songs",
                            style = MaterialTheme.typography.titleSmall,
                            color = MusColors.OnBackground,
                            modifier = Modifier.padding(horizontal = Spacing.base, vertical = Spacing.xs),
                        )
                    }
                    itemsIndexed(trackResults, key = { _, track -> track.id }) { index, track ->
                        AnimatedListItem(index = index) {
                            TrackRow(
                                track = track,
                                onClick = { onTrackClick(track, trackResults) },
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

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
}
