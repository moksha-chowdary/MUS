package com.mus.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mus.android.data.model.Track
import com.mus.android.ui.components.AlbumCard
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MusColors.Background)
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

        Spacer(Modifier.height(Spacing.base))

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
                // Albums
                if (albumResults.isNotEmpty()) {
                    item {
                        Text(
                            "Albums",
                            style = MaterialTheme.typography.labelLarge,
                            color = MusColors.OnBackgroundSecondary,
                            modifier = Modifier.padding(horizontal = Spacing.base, vertical = Spacing.sm),
                        )
                    }
                    items(albumResults.take(5)) { album ->
                        TrackRow(
                            track = Track(
                                id = album.id,
                                title = album.title,
                                artist = album.artist,
                                albumId = album.id,
                                albumTitle = album.title,
                                duration = album.totalDuration,
                                uri = "",
                                artworkUri = album.artworkUri,
                            ),
                            onClick = { onAlbumClick(album.id) },
                            showArtwork = true,
                        )
                    }
                }

                // Songs
                if (trackResults.isNotEmpty()) {
                    item {
                        Text(
                            "Songs",
                            style = MaterialTheme.typography.labelLarge,
                            color = MusColors.OnBackgroundSecondary,
                            modifier = Modifier.padding(horizontal = Spacing.base, vertical = Spacing.sm),
                        )
                    }
                    items(trackResults) { track ->
                        TrackRow(
                            track = track,
                            onClick = { onTrackClick(track, trackResults) },
                        )
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
}
