package com.mus.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Person
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.mus.android.data.download.TrackDownloadManager
import com.mus.android.data.enrichment.provider.OnlineSearchResult
import com.mus.android.data.model.AlbumPageData
import com.mus.android.data.model.ArtistAlbumItem
import com.mus.android.data.model.ArtistPageData
import com.mus.android.data.music.YouTubeMusicEntity
import com.mus.android.data.music.YouTubeMusicEntityKind
import com.mus.android.ui.theme.MusColors
import com.mus.android.ui.theme.Spacing
import com.mus.android.ui.viewmodel.DiscoveryViewModel

/**
 * Apple Music–style shelf cards + bottom-sheet pages for YouTube Music albums and
 * artists found through search. Tapping an album card opens the album page sheet
 * (full track list, plays inline); tapping an artist card opens the artist page
 * sheet (popular songs + album shelves, which drill into album pages).
 */

// ── Shelf cards ──────────────────────────────────────────────────────────────

/** Square artwork album card used in the online search "Albums" shelf. */
@Composable
fun YtAlbumCard(
    entity: YouTubeMusicEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(132.dp)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .size(132.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MusColors.SurfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.Album,
                contentDescription = null,
                tint = MusColors.OnBackgroundTertiary.copy(alpha = 0.5f),
                modifier = Modifier.size(36.dp),
            )
            if (!entity.artworkUrl.isNullOrBlank()) {
                AsyncImage(
                    model = entity.artworkUrl,
                    contentDescription = entity.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = entity.name,
            style = MaterialTheme.typography.bodyMedium,
            color = MusColors.OnBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = entity.artist ?: entity.subtitle ?: "Album",
            style = MaterialTheme.typography.bodySmall,
            color = MusColors.OnBackgroundSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Circular artist card used in the online search "Artists" shelf. */
@Composable
fun YtArtistCard(
    entity: YouTubeMusicEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(104.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(MusColors.SurfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.Person,
                contentDescription = null,
                tint = MusColors.OnBackgroundTertiary.copy(alpha = 0.5f),
                modifier = Modifier.size(36.dp),
            )
            if (!entity.artworkUrl.isNullOrBlank()) {
                AsyncImage(
                    model = entity.artworkUrl,
                    contentDescription = entity.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = entity.name,
            style = MaterialTheme.typography.bodyMedium,
            color = MusColors.OnBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Text(
            text = entity.subtitle ?: "Artist",
            style = MaterialTheme.typography.bodySmall,
            color = MusColors.OnBackgroundSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

// ── Shared row inside album/artist sheets ────────────────────────────────────

@Composable
private fun PageTrackRow(
    result: OnlineSearchResult,
    onClick: () -> Unit,
    onMenu: () -> Unit,
    isDownloaded: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f)) {
            OnlineTrackRow(
                result = result,
                onClick = onClick,
                isDownloaded = isDownloaded,
            )
        }
        IconButton(onClick = onMenu) {
            Icon(
                Icons.Rounded.MoreVert,
                contentDescription = "More actions",
                tint = MusColors.OnBackgroundTertiary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

// ── Album page sheet ─────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YtAlbumPageSheet(
    page: AlbumPageData,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    discovery: DiscoveryViewModel,
) {
    var menuIndex by remember { mutableStateOf<Int?>(null) }
    val downloadedKeys by discovery.downloadedKeys.collectAsState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MusColors.SurfaceElevated,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 120.dp),
        ) {
            item(key = "album_header") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(84.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MusColors.SurfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Rounded.Album,
                            contentDescription = null,
                            tint = MusColors.OnBackgroundTertiary.copy(alpha = 0.5f),
                            modifier = Modifier.size(30.dp),
                        )
                        if (!page.artworkUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = page.artworkUrl,
                                contentDescription = page.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    Spacer(Modifier.width(Spacing.md))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = page.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = MusColors.OnBackground,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = listOfNotNull(
                                page.artist,
                                page.releaseYear,
                                page.trackCountText,
                            ).joinToString(" • ").ifBlank { "Album" },
                            style = MaterialTheme.typography.labelSmall,
                            color = MusColors.OnBackgroundTertiary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(Spacing.sm))
                        OutlinedButton(
                            onClick = { discovery.playYtPageTrack(page.tracks, 0) },
                            enabled = page.tracks.isNotEmpty() && !isLoading,
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MusColors.OnBackground
                            ),
                        ) {
                            Icon(
                                Icons.Rounded.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(Spacing.xs))
                            Text("Play", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            if (isLoading) {
                item(key = "album_loading") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            color = MusColors.OnBackground,
                            strokeWidth = 2.dp,
                        )
                    }
                }
            } else if (page.tracks.isEmpty()) {
                item(key = "album_empty") {
                    Text(
                        "Couldn't load this album's tracks",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MusColors.OnBackgroundTertiary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(Spacing.lg),
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                items(page.tracks.size, key = { "at_${page.tracks[it].videoId ?: it}" }) { index ->
                    val track = page.tracks[index]
                    PageTrackRow(
                        result = discovery.pageTrackAsOnlineResult(track),
                        onClick = { discovery.playYtPageTrack(page.tracks, index) },
                        onMenu = { menuIndex = index },
                        isDownloaded = downloadedKeys.contains(
                            TrackDownloadManager.makeDownloadKey(track.title, track.artist)
                        ),
                    )
                }
            }
        }
    }

    // Per-row action overlay (play/queue/download/collection)
    menuIndex?.let { index ->
        page.tracks.getOrNull(index)?.let { track ->
            OnlineResultOverlay(
                result = discovery.pageTrackAsOnlineResult(track),
                onDismiss = { menuIndex = null },
                discovery = discovery,
            )
        } ?: run { menuIndex = null }
    }
}

// ── Artist page sheet ────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YtArtistPageSheet(
    page: ArtistPageData,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    discovery: DiscoveryViewModel,
) {
    var menuIndex by remember { mutableStateOf<Int?>(null) }
    val downloadedKeys by discovery.downloadedKeys.collectAsState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MusColors.SurfaceElevated,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 120.dp),
        ) {
            item(key = "artist_header") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(76.dp)
                            .clip(CircleShape)
                            .background(MusColors.SurfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Rounded.Person,
                            contentDescription = null,
                            tint = MusColors.OnBackgroundTertiary.copy(alpha = 0.5f),
                            modifier = Modifier.size(30.dp),
                        )
                        val artwork = page.artworkUrl ?: page.fallbackArtworkUrl
                        if (!artwork.isNullOrBlank()) {
                            AsyncImage(
                                model = artwork,
                                contentDescription = page.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    Spacer(Modifier.width(Spacing.md))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = page.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = MusColors.OnBackground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = listOfNotNull(
                                page.monthlyListeners ?: page.subscribers,
                            ).joinToString(" • ").ifBlank { "Artist" },
                            style = MaterialTheme.typography.labelSmall,
                            color = MusColors.OnBackgroundTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(Spacing.sm))
                        OutlinedButton(
                            onClick = { discovery.playYtPageTrack(page.topSongs, 0) },
                            enabled = page.topSongs.isNotEmpty() && !isLoading,
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MusColors.OnBackground
                            ),
                        ) {
                            Icon(
                                Icons.Rounded.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(Spacing.xs))
                            Text("Play", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            if (isLoading) {
                item(key = "artist_loading") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            color = MusColors.OnBackground,
                            strokeWidth = 2.dp,
                        )
                    }
                }
            } else {
                if (page.topSongs.isNotEmpty()) {
                    item(key = "artist_songs_header") {
                        Text(
                            "Popular",
                            style = MaterialTheme.typography.titleSmall,
                            color = MusColors.OnBackground,
                            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                        )
                    }
                    items(page.topSongs.size, key = { "ts_${page.topSongs[it].videoId ?: it}" }) { index ->
                        val track = page.topSongs[index]
                        PageTrackRow(
                            result = discovery.pageTrackAsOnlineResult(track),
                            onClick = { discovery.playYtPageTrack(page.topSongs, index) },
                            onMenu = { menuIndex = index },
                            isDownloaded = downloadedKeys.contains(
                                TrackDownloadManager.makeDownloadKey(track.title, track.artist)
                            ),
                        )
                    }
                }

                if (page.albums.isNotEmpty()) {
                    item(key = "artist_albums_header") {
                        Text(
                            "Albums",
                            style = MaterialTheme.typography.titleSmall,
                            color = MusColors.OnBackground,
                            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                        )
                    }
                    item(key = "artist_albums_row") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = Spacing.lg),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(page.albums, key = { it.browseId.ifBlank { it.title } }) { album ->
                                YtAlbumCard(
                                    entity = album.toEntity(page.name),
                                    onClick = { discovery.openYtAlbum(album.toEntity(page.name)) },
                                )
                            }
                        }
                    }
                }

                if (page.singles.isNotEmpty()) {
                    item(key = "artist_singles_header") {
                        Text(
                            "Singles",
                            style = MaterialTheme.typography.titleSmall,
                            color = MusColors.OnBackground,
                            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                        )
                    }
                    item(key = "artist_singles_row") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = Spacing.lg),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(page.singles, key = { it.browseId.ifBlank { it.title } }) { single ->
                                YtAlbumCard(
                                    entity = single.toEntity(page.name),
                                    onClick = { discovery.openYtAlbum(single.toEntity(page.name)) },
                                )
                            }
                        }
                    }
                }

                if (page.topSongs.isEmpty() && page.albums.isEmpty() && page.singles.isEmpty()) {
                    item(key = "artist_empty") {
                        Text(
                            "Couldn't load this artist's page",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MusColors.OnBackgroundTertiary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(Spacing.lg),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }

    // Per-row action overlay (play/queue/download/collection)
    menuIndex?.let { index ->
        page.topSongs.getOrNull(index)?.let { track ->
            OnlineResultOverlay(
                result = discovery.pageTrackAsOnlineResult(track),
                onDismiss = { menuIndex = null },
                discovery = discovery,
            )
        } ?: run { menuIndex = null }
    }
}

private fun ArtistAlbumItem.toEntity(artistName: String): YouTubeMusicEntity =
    YouTubeMusicEntity(
        kind = YouTubeMusicEntityKind.ALBUM,
        name = title,
        artist = artistName,
        subtitle = year ?: type,
        browseId = browseId,
        artworkUrl = artworkUrl,
    )
