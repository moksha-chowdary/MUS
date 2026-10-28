package com.mus.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mus.android.data.download.TrackDownloadManager
import com.mus.android.data.enrichment.provider.OnlineSearchResult
import com.mus.android.data.model.Track
import com.mus.android.ui.components.AlbumCard
import com.mus.android.ui.components.AppleLargeTitleList
import com.mus.android.ui.components.AppleSearchField
import com.mus.android.ui.components.AppleSegmentedControl
import com.mus.android.ui.components.ArtistCard
import com.mus.android.ui.components.DownloadedBadge
import com.mus.android.ui.components.OnlineResultOverlay
import com.mus.android.ui.components.OnlineTrackRow
import com.mus.android.ui.components.SectionHeader
import com.mus.android.ui.components.SongMenuContainer
import com.mus.android.ui.components.TrackRow
import com.mus.android.ui.components.YtAlbumCard
import com.mus.android.ui.components.YtAlbumPageSheet
import com.mus.android.ui.components.YtArtistCard
import com.mus.android.ui.components.YtArtistPageSheet
import com.mus.android.ui.components.YtPlaylistCard
import com.mus.android.ui.components.YtPlaylistTracksSheet
import com.mus.android.ui.theme.FootnoteStyle
import com.mus.android.ui.theme.MusColors
import com.mus.android.ui.theme.Spacing
import com.mus.android.ui.viewmodel.DiscoveryViewModel
import com.mus.android.ui.viewmodel.SearchViewModel

// Browse suggestions shown on the YT Music tab before typing (Apple Music "Browse Categories" nod)
private val BrowseSuggestions = listOf(
    "Telugu Hits", "Hindi Top Songs", "Punjabi 2025", "Trending Now",
    "English Pop", "Lofi Mix", "Workout", "Romantic Melodies",
)

/**
 * Apple Music–style search. Online YT Music search is the primary tab; the local
 * library search is the secondary segment. Online rows show a "Downloaded" badge
 * when the song is already in the local download index.
 */
@Composable
fun SearchScreen(
    onTrackClick: (Track, List<Track>) -> Unit,
    onAlbumClick: (Long) -> Unit,
    onArtistClick: (Long) -> Unit,
    onDownloadQueueClick: () -> Unit = {},
    viewModel: SearchViewModel = hiltViewModel(),
    discoveryViewModel: DiscoveryViewModel = hiltViewModel(),
) {
    // Local search state
    val query by viewModel.query.collectAsState()
    val trackResults by viewModel.trackResults.collectAsState()
    val albumResults by viewModel.albumResults.collectAsState()
    val artistResults by viewModel.artistResults.collectAsState()
    val hasResults by viewModel.hasResults.collectAsState()

    // Online (YT Music) state
    val onlineResults by discoveryViewModel.onlineResults.collectAsState()
    val onlineAlbums by discoveryViewModel.onlineAlbums.collectAsState()
    val onlineArtists by discoveryViewModel.onlineArtists.collectAsState()
    val isSearching by discoveryViewModel.isSearching.collectAsState()
    val ytPlaylists by discoveryViewModel.ytPlaylists.collectAsState()
    val selectedYtPlaylist by discoveryViewModel.selectedYtPlaylist.collectAsState()
    val ytPlaylistLoading by discoveryViewModel.ytPlaylistLoading.collectAsState()
    val selectedYtAlbum by discoveryViewModel.selectedYtAlbum.collectAsState()
    val ytAlbumLoading by discoveryViewModel.ytAlbumLoading.collectAsState()
    val selectedYtArtist by discoveryViewModel.selectedYtArtist.collectAsState()
    val ytArtistLoading by discoveryViewModel.ytArtistLoading.collectAsState()
    val downloadedKeys by discoveryViewModel.downloadedKeys.collectAsState()
    val pendingDownloadCount by discoveryViewModel.pendingDownloadCount.collectAsState()

    // 0 = YT Music (primary), 1 = Your Library — Apple Music order, online-first default
    var activeTab by remember { mutableStateOf(0) }
    var selectedOnlineResult by remember { mutableStateOf<OnlineSearchResult?>(null) }
    var selectedTrackForMenu by remember { mutableStateOf<Track?>(null) }

    LaunchedEffect(Unit) { discoveryViewModel.loadYtPlaylistsIfConnected() }

    AppleLargeTitleList(title = "Search") {
        item(key = "search_field") {
            AppleSearchField(
                value = query,
                onValueChange = { q ->
                    viewModel.updateQuery(q)
                    discoveryViewModel.updateQuery(q)
                },
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }

        item(key = "segment") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppleSegmentedControl(
                    options = listOf("YT Music", "Your Library"),
                    selected = activeTab,
                    onSelect = { activeTab = it },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (pendingDownloadCount > 0) {
            item(key = "to_download") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MusColors.SurfaceVariant,
                        modifier = Modifier.clickable { onDownloadQueueClick() },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Rounded.DownloadDone,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MusColors.OnBackgroundSecondary,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "To Download ($pendingDownloadCount)",
                                style = FootnoteStyle,
                                color = MusColors.OnBackgroundSecondary,
                            )
                        }
                    }
                }
            }
        }

        if (activeTab == 0) {
            // ── YT MUSIC (online, primary) ──────────────────────────────
            if (query.isBlank()) {
                item(key = "browse_header") {
                    SectionHeader(
                        title = "Browse",
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                }
                item(key = "browse_chips") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        BrowseSuggestions.chunked(2).forEach { rowChips ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                rowChips.forEach { suggestion ->
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = MusColors.SurfaceVariant,
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable {
                                                viewModel.updateQuery(suggestion)
                                                discoveryViewModel.updateQuery(suggestion)
                                            },
                                    ) {
                                        Text(
                                            text = suggestion,
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MusColors.OnBackground,
                                            maxLines = 1,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
                                        )
                                    }
                                }
                                if (rowChips.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
                item(key = "browse_gap") { Spacer(Modifier.height(Spacing.xl)) }

                if (ytPlaylists.isNotEmpty()) {
                    item(key = "yt_pl_header") {
                        SectionHeader("Your YouTube Music playlists")
                        Spacer(Modifier.height(12.dp))
                    }
                    item(key = "yt_pl_row") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(ytPlaylists, key = { it.id }) { playlist ->
                                YtPlaylistCard(
                                    playlist = playlist,
                                    onClick = { discoveryViewModel.openYtPlaylist(playlist) },
                                )
                            }
                        }
                    }
                    item(key = "yt_pl_gap") { Spacer(Modifier.height(Spacing.xl)) }
                }
            } else if (isSearching) {
                item(key = "searching") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = MusColors.OnBackgroundSecondary, strokeWidth = 2.dp)
                    }
                }
            } else if (onlineResults.isEmpty()) {
                item(key = "no_online") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 40.dp, vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            Icons.Rounded.SearchOff,
                            contentDescription = null,
                            tint = MusColors.OnBackgroundTertiary,
                            modifier = Modifier.size(40.dp),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "No results on YouTube Music",
                            style = MaterialTheme.typography.titleSmall,
                            color = MusColors.OnBackground,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            "Try a different spelling or check the Library tab.",
                            style = FootnoteStyle,
                            color = MusColors.OnBackgroundSecondary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            } else {
                item(key = "online_header") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Songs",
                            style = MaterialTheme.typography.titleMedium,
                            color = MusColors.OnBackground,
                        )
                        Text(
                            "${onlineResults.size} results",
                            style = FootnoteStyle,
                            color = MusColors.OnBackgroundTertiary,
                        )
                    }
                }
                items(onlineResults.size, key = { "on_${onlineResults[it].id}" }) { index ->
                    val result = onlineResults[index]
                    OnlineTrackRow(
                        result = result,
                        onClick = { selectedOnlineResult = result },
                        isDownloaded = downloadedKeys.contains(
                            TrackDownloadManager.makeDownloadKey(result.title, result.artist)
                        ),
                    )
                }

                // ── ALBUMS (Apple Music–style section) ──────────────────────
                if (onlineAlbums.isNotEmpty()) {
                    item(key = "online_albums_header") {
                        SectionHeader(
                            title = "Albums",
                            modifier = Modifier.padding(top = Spacing.lg),
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                    item(key = "online_albums_row") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(onlineAlbums, key = { it.browseId }) { album ->
                                YtAlbumCard(
                                    entity = album,
                                    onClick = { discoveryViewModel.openYtAlbum(album) },
                                )
                            }
                        }
                    }
                }

                // ── ARTISTS (Apple Music–style section) ─────────────────────
                if (onlineArtists.isNotEmpty()) {
                    item(key = "online_artists_header") {
                        SectionHeader(
                            title = "Artists",
                            modifier = Modifier.padding(top = Spacing.lg),
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                    item(key = "online_artists_row") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(onlineArtists, key = { it.browseId }) { artist ->
                                YtArtistCard(
                                    entity = artist,
                                    onClick = { discoveryViewModel.openYtArtist(artist) },
                                )
                            }
                        }
                    }
                }
                item(key = "online_bottom_gap") { Spacer(Modifier.height(Spacing.xl)) }
            }
        } else {
            // ── YOUR LIBRARY (local search) ─────────────────────────────
            if (query.isBlank()) {
                item(key = "local_prompt") {
                    Text(
                        "Search your downloaded songs, albums and artists.",
                        style = FootnoteStyle,
                        color = MusColors.OnBackgroundSecondary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 32.dp),
                        textAlign = TextAlign.Center,
                    )
                }
            } else if (!hasResults) {
                item(key = "no_local") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 40.dp, vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            Icons.Rounded.SearchOff,
                            contentDescription = null,
                            tint = MusColors.OnBackgroundTertiary,
                            modifier = Modifier.size(40.dp),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Nothing in your library",
                            style = MaterialTheme.typography.titleSmall,
                            color = MusColors.OnBackground,
                        )
                    }
                }
            } else {
                if (artistResults.isNotEmpty()) {
                    item(key = "local_artists") {
                        SectionHeader("Artists")
                        Spacer(Modifier.height(12.dp))
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(artistResults, key = { it.id }) { artist ->
                                ArtistCard(
                                    name = artist.name,
                                    artworkUri = artist.artworkUri,
                                    onClick = { onArtistClick(artist.id) },
                                )
                            }
                        }
                        Spacer(Modifier.height(Spacing.xl))
                    }
                }
                if (albumResults.isNotEmpty()) {
                    item(key = "local_albums") {
                        SectionHeader("Albums")
                        Spacer(Modifier.height(12.dp))
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
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
                        Spacer(Modifier.height(Spacing.xl))
                    }
                }
                if (trackResults.isNotEmpty()) {
                    item(key = "local_songs") {
                        SectionHeader("Songs")
                        Spacer(Modifier.height(Spacing.xs))
                    }
                    items(trackResults.size, key = { "lt_${trackResults[it].id}" }) { index ->
                        val track = trackResults[index]
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

    // Online result actions (play / queue / FLAC download / add to collection)
    OnlineResultOverlay(
        result = selectedOnlineResult,
        onDismiss = { selectedOnlineResult = null },
        discovery = discoveryViewModel,
    )

    // Opened YT playlist sheet
    selectedYtPlaylist?.let { selection ->
        YtPlaylistTracksSheet(
            selection = selection,
            isLoading = ytPlaylistLoading,
            onDismiss = { discoveryViewModel.closeYtPlaylist() },
            discovery = discoveryViewModel,
        )
    }

    // Opened YT album page (from search Albums section or artist page shelves)
    selectedYtAlbum?.let { page ->
        YtAlbumPageSheet(
            page = page,
            isLoading = ytAlbumLoading,
            onDismiss = { discoveryViewModel.closeYtAlbum() },
            discovery = discoveryViewModel,
        )
    }

    // Opened YT artist page (popular songs + album shelves)
    selectedYtArtist?.let { page ->
        YtArtistPageSheet(
            page = page,
            isLoading = ytArtistLoading,
            onDismiss = { discoveryViewModel.closeYtArtist() },
            discovery = discoveryViewModel,
        )
    }

    SongMenuContainer(
        selectedTrack = selectedTrackForMenu,
        onDismissMenu = { selectedTrackForMenu = null },
        onNavigateToAlbum = onAlbumClick,
    )
}
