package com.mus.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mus.android.data.enrichment.provider.OnlineSearchResult
import com.mus.android.data.model.Track
import com.mus.android.ui.components.AlbumCard
import com.mus.android.ui.components.OnlineResultSheet
import com.mus.android.ui.components.OnlineTrackRow
import com.mus.android.ui.components.SongMenuContainer
import com.mus.android.ui.components.TrackRow
import com.mus.android.ui.theme.MusColors
import com.mus.android.ui.theme.Spacing
import com.mus.android.ui.viewmodel.DiscoveryViewModel
import com.mus.android.ui.viewmodel.SearchViewModel

private enum class SearchTab { LOCAL, ONLINE }

@Composable
fun SearchScreen(
    onTrackClick: (Track, List<Track>) -> Unit,
    onAlbumClick: (Long) -> Unit,
    onArtistClick: (Long) -> Unit,
    onDownloadQueueClick: () -> Unit = {},
    viewModel: SearchViewModel = hiltViewModel(),
    discoveryViewModel: DiscoveryViewModel = hiltViewModel(),
) {
    val query by viewModel.query.collectAsState()
    val trackResults by viewModel.trackResults.collectAsState()
    val albumResults by viewModel.albumResults.collectAsState()
    val artistResults by viewModel.artistResults.collectAsState()
    val hasResults by viewModel.hasResults.collectAsState()
    val focusRequester = remember { FocusRequester() }
    var selectedTrackForMenu by remember { mutableStateOf<Track?>(null) }

    val onlineResults by discoveryViewModel.onlineResults.collectAsState()
    val isSearchingOnline by discoveryViewModel.isSearching.collectAsState()
    val addToQueueResult by discoveryViewModel.addToQueueResult.collectAsState()
    val pendingDownloadCount by discoveryViewModel.pendingDownloadCount.collectAsState()

    var activeTab by remember { mutableStateOf(SearchTab.LOCAL) }
    var selectedFilter by remember { mutableStateOf("All") }
    val filterOptions = listOf("All", "Songs", "Albums", "Artists")

    var selectedOnlineResult by remember { mutableStateOf<OnlineSearchResult?>(null) }

    // Show toast when item is added to download queue
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(addToQueueResult) {
        val msg = addToQueueResult ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Short)
        discoveryViewModel.clearAddToQueueResult()
    }

    // Sync query to discovery when on ONLINE tab
    LaunchedEffect(query, activeTab) {
        if (activeTab == SearchTab.ONLINE && query.isNotBlank()) {
            discoveryViewModel.updateQuery(query)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = Color.Transparent,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
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
                    onValueChange = {
                        viewModel.updateQuery(it)
                        if (activeTab == SearchTab.ONLINE) {
                            discoveryViewModel.updateQuery(it)
                        }
                    },
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
                                "Search songs, artists, albums...",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MusColors.OnBackgroundTertiary,
                            )
                        }
                        innerTextField()
                    }
                )
                if (query.isNotEmpty()) {
                    IconButton(
                        onClick = {
                            viewModel.updateQuery("")
                            discoveryViewModel.updateQuery("")
                        },
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

            // LOCAL / ONLINE tab row + To Download affordance
            Spacer(Modifier.height(Spacing.sm))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.base),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    SearchTabChip(
                        label = "Your Library",
                        selected = activeTab == SearchTab.LOCAL,
                        onClick = { activeTab = SearchTab.LOCAL },
                    )
                    SearchTabChip(
                        label = "Online",
                        selected = activeTab == SearchTab.ONLINE,
                        onClick = {
                            activeTab = SearchTab.ONLINE
                            if (query.isNotBlank()) discoveryViewModel.searchOnline(query)
                        },
                    )
                }

                // Compact "To Download" affordance near search tabs
                Surface(
                    onClick = onDownloadQueueClick,
                    shape = RoundedCornerShape(20.dp),
                    color = if (pendingDownloadCount > 0) MusColors.SurfaceElevated else MusColors.SurfaceVariant,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Rounded.PlaylistAdd,
                            contentDescription = "To Download",
                            tint = if (pendingDownloadCount > 0) MusColors.OnBackground else MusColors.OnBackgroundTertiary,
                            modifier = Modifier.size(15.dp),
                        )
                        Text(
                            text = "To Download",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (pendingDownloadCount > 0) MusColors.OnBackground else MusColors.OnBackgroundSecondary,
                        )
                        if (pendingDownloadCount > 0) {
                            Surface(
                                shape = CircleShape,
                                color = MusColors.OnBackground,
                            ) {
                                Text(
                                    text = "$pendingDownloadCount",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                    ),
                                    color = MusColors.Background,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                )
                            }
                        }
                    }
                }
            }

            // Filter chips — only on LOCAL tab
            if (activeTab == SearchTab.LOCAL && query.isNotBlank()) {
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

            when (activeTab) {
                SearchTab.LOCAL -> LocalSearchContent(
                    query = query,
                    hasResults = hasResults,
                    selectedFilter = selectedFilter,
                    trackResults = trackResults,
                    albumResults = albumResults,
                    artistResults = artistResults,
                    onTrackClick = onTrackClick,
                    onAlbumClick = onAlbumClick,
                    onArtistClick = onArtistClick,
                    onTrackMenuClick = { selectedTrackForMenu = it },
                )
                SearchTab.ONLINE -> OnlineSearchContent(
                    query = query,
                    results = onlineResults,
                    isSearching = isSearchingOnline,
                    pendingDownloadCount = pendingDownloadCount,
                    onResultClick = { selectedOnlineResult = it },
                    onDownloadQueueClick = onDownloadQueueClick,
                )
            }
        }
    }

    SongMenuContainer(
        selectedTrack = selectedTrackForMenu,
        onDismissMenu = { selectedTrackForMenu = null },
        onNavigateToAlbum = onAlbumClick,
    )

    selectedOnlineResult?.let { result ->
        OnlineResultSheet(
            result = result,
            onDismiss = { selectedOnlineResult = null },
            onAddToDownloadList = {
                discoveryViewModel.addToDownloadQueue(result)
                selectedOnlineResult = null
            },
        )
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
}

@Composable
private fun SearchTabChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MusColors.OnBackground,
            selectedLabelColor = MusColors.Background,
            containerColor = MusColors.SurfaceVariant,
            labelColor = MusColors.OnBackgroundSecondary,
        ),
        border = null,
    )
}

@Composable
private fun LocalSearchContent(
    query: String,
    hasResults: Boolean,
    selectedFilter: String,
    trackResults: List<Track>,
    albumResults: List<com.mus.android.data.model.Album>,
    artistResults: List<com.mus.android.data.model.Artist>,
    onTrackClick: (Track, List<Track>) -> Unit,
    onAlbumClick: (Long) -> Unit,
    onArtistClick: (Long) -> Unit,
    onTrackMenuClick: (Track) -> Unit,
) {
    if (query.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "Search your music library",
                style = MaterialTheme.typography.bodyMedium,
                color = MusColors.OnBackgroundTertiary,
                textAlign = TextAlign.Center,
            )
        }
    } else if (!hasResults) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "No results for \"$query\"",
                style = MaterialTheme.typography.bodyMedium,
                color = MusColors.OnBackgroundTertiary,
                textAlign = TextAlign.Center,
            )
        }
    } else {
        LazyColumn(contentPadding = PaddingValues(bottom = 120.dp)) {
            if ((selectedFilter == "All" || selectedFilter == "Artists") && artistResults.isNotEmpty()) {
                item {
                    SectionHeader("Artists")
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

            if ((selectedFilter == "All" || selectedFilter == "Albums") && albumResults.isNotEmpty()) {
                item {
                    SectionHeader("Albums")
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = Spacing.base),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        items(albumResults, key = { it.id }) { album ->
                            AlbumCard(
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

            if ((selectedFilter == "All" || selectedFilter == "Songs") && trackResults.isNotEmpty()) {
                item { SectionHeader("Songs") }
                items(trackResults, key = { it.id }) { track ->
                    TrackRow(
                        track = track,
                        onClick = { onTrackClick(track, trackResults) },
                        onMoreClick = { onTrackMenuClick(track) },
                    )
                }
            }
        }
    }
}

@Composable
private fun OnlineSearchContent(
    query: String,
    results: List<OnlineSearchResult>,
    isSearching: Boolean,
    pendingDownloadCount: Int,
    onResultClick: (OnlineSearchResult) -> Unit,
    onDownloadQueueClick: () -> Unit,
) {
    when {
        query.isEmpty() -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    Icon(
                        Icons.Rounded.Search,
                        contentDescription = null,
                        tint = MusColors.OnBackgroundTertiary,
                        modifier = Modifier.size(48.dp),
                    )
                    Text(
                        "Search for songs, artists, or albums online",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MusColors.OnBackgroundTertiary,
                        textAlign = TextAlign.Center,
                    )
                    OutlinedButton(
                        onClick = onDownloadQueueClick,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MusColors.OnBackground),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Rounded.PlaylistAdd,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(Spacing.xs))
                        Text(
                            if (pendingDownloadCount > 0) "Open \"To Download\" ($pendingDownloadCount)"
                            else "Open \"To Download\" Planning List",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }
        isSearching -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    color = MusColors.OnBackground,
                    strokeWidth = 2.dp,
                )
            }
        }
        results.isEmpty() -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    Text(
                        "No online results for \"$query\"",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MusColors.OnBackgroundTertiary,
                        textAlign = TextAlign.Center,
                    )
                    OutlinedButton(
                        onClick = onDownloadQueueClick,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MusColors.OnBackground),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Rounded.PlaylistAdd,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(Spacing.xs))
                        Text(
                            if (pendingDownloadCount > 0) "Open \"To Download\" ($pendingDownloadCount)"
                            else "Open \"To Download\" Planning List",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }
        else -> {
            LazyColumn(contentPadding = PaddingValues(bottom = 120.dp)) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.base, vertical = Spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SectionHeaderInline("Online Results")
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onDownloadQueueClick) {
                            Icon(
                                Icons.AutoMirrored.Rounded.PlaylistAdd,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MusColors.OnBackgroundSecondary,
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                if (pendingDownloadCount > 0) "To Download ($pendingDownloadCount)" else "To Download",
                                style = MaterialTheme.typography.labelSmall,
                                color = MusColors.OnBackgroundSecondary,
                            )
                        }
                    }
                }

                items(results, key = { it.id }) { result ->
                    OnlineTrackRow(
                        result = result,
                        onClick = { onResultClick(result) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MusColors.OnBackground,
        modifier = Modifier.padding(horizontal = Spacing.base, vertical = Spacing.xs),
    )
}

@Composable
private fun SectionHeaderInline(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MusColors.OnBackground,
    )
}
