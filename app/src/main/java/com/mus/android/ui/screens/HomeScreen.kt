package com.mus.android.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mus.android.R
import com.mus.android.data.enrichment.provider.OnlineSearchResult
import com.mus.android.data.model.Track
import com.mus.android.data.music.HomeShelfItem
import com.mus.android.ui.components.AlbumCard
import com.mus.android.ui.components.AppleIconButton
import com.mus.android.ui.components.AppleLargeTitleList
import com.mus.android.ui.components.CatalogPlaylistCard
import com.mus.android.ui.components.CatalogSongCard
import com.mus.android.ui.components.OnlineResultOverlay
import com.mus.android.ui.components.PlaylistCard
import com.mus.android.ui.components.SectionHeader
import com.mus.android.ui.components.Shelf
import com.mus.android.ui.components.SongMenuContainer
import com.mus.android.ui.components.TrackRow
import com.mus.android.ui.components.YtPlaylistCard
import com.mus.android.ui.components.YtPlaylistTracksSheet
import com.mus.android.ui.theme.MusColors
import com.mus.android.ui.theme.Spacing
import com.mus.android.ui.viewmodel.DiscoveryViewModel
import com.mus.android.ui.viewmodel.HomeViewModel
import com.mus.android.ui.viewmodel.PermissionState

/**
 * Apple Music–style "Listen Now" home.
 *
 * The YouTube Music catalogue is the primary source: its home-feed shelves render first,
 * followed by the signed-in account's YT playlists, then the local library content
 * (Quick Picks, Recently Added, Albums, Collections). Background gradients and the
 * dark MUS palette are unchanged.
 */
@Composable
fun HomeScreen(
    onAlbumClick: (Long) -> Unit,
    onPlaylistClick: (Long) -> Unit = {},
    onSettingsClick: () -> Unit = {},
    onTrackClick: (Track, List<Track>) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
    discoveryViewModel: DiscoveryViewModel = hiltViewModel(),
) {
    val isLoading by viewModel.isLoading.collectAsState()
    val needsPermission by viewModel.needsPermission.collectAsState()
    val permissionState by viewModel.permissionState.collectAsState()
    val tracks by viewModel.tracks.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val recentlyAdded by viewModel.recentlyAdded.collectAsState()
    val albums by viewModel.albums.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    val quickPicks by viewModel.quickPicks.collectAsState()

    val quickPickPages = remember(quickPicks) { quickPicks.chunked(4) }
    val quickPickPagerState = rememberPagerState(pageCount = { quickPickPages.size })

    var selectedTrackForMenu by remember { mutableStateOf<Track?>(null) }

    // Catalogue state (YT Music primary catalogue + account playlists)
    val homeShelves by discoveryViewModel.homeShelves.collectAsState()
    val ytPlaylists by discoveryViewModel.ytPlaylists.collectAsState()
    val selectedYtPlaylist by discoveryViewModel.selectedYtPlaylist.collectAsState()
    val ytPlaylistLoading by discoveryViewModel.ytPlaylistLoading.collectAsState()
    var catalogResult by remember { mutableStateOf<OnlineSearchResult?>(null) }

    val context = LocalContext.current
    val activity = context as? Activity
    val lifecycleOwner = LocalLifecycleOwner.current

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
            viewModel.setCustomMuzicFolder(treeUri)
        }
    }

    val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    // Reactively check permission when returning from App Settings
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.checkAndScan()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.onPermissionGranted()
        } else {
            val isPermanentlyDenied = activity != null &&
                !ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
            viewModel.onPermissionDenied(isPermanentlyDenied)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
    ) {
        when {
            needsPermission -> {
                val titleText = when (permissionState) {
                    PermissionState.PERMANENTLY_DENIED -> "Permission Required in Settings"
                    PermissionState.DENIED -> "Access Denied"
                    else -> "MUS needs access to your music"
                }

                val subtitleText = when (permissionState) {
                    PermissionState.PERMANENTLY_DENIED -> "Audio permission was permanently denied. Please open App Settings and allow access to audio files to scan your music."
                    PermissionState.DENIED -> "Audio access is required so MUS can scan and play your local music files. Please grant permission to continue."
                    else -> "Grant permission to scan your device for audio files"
                }

                val buttonText = when (permissionState) {
                    PermissionState.PERMANENTLY_DENIED -> "Open Settings"
                    else -> "Grant Permission"
                }

                // Permission request screen
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(Spacing.xxl),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_mus_logo),
                        contentDescription = null,
                        tint = Color.Unspecified,
                        modifier = Modifier.size(64.dp),
                    )
                    Spacer(Modifier.height(Spacing.xl))
                    Text(
                        titleText,
                        style = MaterialTheme.typography.titleMedium,
                        color = MusColors.OnBackground,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        subtitleText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MusColors.OnBackgroundSecondary,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(Spacing.xl))
                    Button(
                        onClick = {
                            if (permissionState == PermissionState.PERMANENTLY_DENIED) {
                                val intent = Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", context.packageName, null)
                                )
                                context.startActivity(intent)
                            } else {
                                permissionLauncher.launch(permission)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MusColors.OnBackground,
                            contentColor = MusColors.Background,
                        ),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(buttonText)
                    }

                    if (permissionState == PermissionState.DENIED) {
                        Spacer(Modifier.height(Spacing.sm))
                        TextButton(
                            onClick = {
                                val intent = Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", context.packageName, null)
                                )
                                context.startActivity(intent)
                            }
                        ) {
                            Text(
                                "Open App Settings",
                                color = MusColors.OnBackgroundSecondary,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            }

            isLoading && tracks.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            color = MusColors.OnBackgroundSecondary,
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.height(Spacing.base))
                        Text(
                            "Scanning your Muzic...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MusColors.OnBackgroundSecondary,
                        )
                    }
                }
            }

            errorMessage != null && tracks.isEmpty() -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(Spacing.xxl),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        Icons.Rounded.Warning,
                        contentDescription = null,
                        tint = MusColors.Error,
                        modifier = Modifier.size(48.dp),
                    )
                    Spacer(Modifier.height(Spacing.md))
                    Text(
                        "Scan Failed",
                        style = MaterialTheme.typography.titleMedium,
                        color = MusColors.OnBackground,
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        errorMessage ?: "Unable to scan Muzic folder",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MusColors.OnBackgroundSecondary,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(Spacing.xl))
                    Button(
                        onClick = { viewModel.refreshLibrary() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MusColors.OnBackground,
                            contentColor = MusColors.Background,
                        ),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text("Retry")
                    }
                }
            }

            else -> {
                LaunchedEffect(Unit) {
                    discoveryViewModel.loadHomeFeedIfEmpty()
                    discoveryViewModel.loadYtPlaylistsIfConnected()
                }

                AppleLargeTitleList(
                    title = "Listen Now",
                    trailing = {
                        AppleIconButton(
                            icon = Icons.Rounded.Settings,
                            contentDescription = "Settings",
                            onClick = onSettingsClick,
                            tint = MusColors.OnBackgroundSecondary,
                            iconSize = 22.dp,
                        )
                    },
                ) {
                    // ── 1. YT MUSIC CATALOGUE — the primary source ──────────────
                    homeShelves.forEach { shelf ->
                        if (shelf.items.isEmpty()) return@forEach
                        item(key = "cat_${shelf.title}") {
                            Shelf(title = shelf.title) {
                                items(shelf.items.size) { index ->
                                    when (val item = shelf.items[index]) {
                                        is HomeShelfItem.Track -> CatalogSongCard(
                                            track = item.track,
                                            onClick = {
                                                catalogResult = discoveryViewModel.asOnlineResult(item.track)
                                            },
                                        )
                                        is HomeShelfItem.Playlist -> CatalogPlaylistCard(
                                            playlist = item.playlist,
                                            onClick = { discoveryViewModel.openYtPlaylist(item.playlist) },
                                        )
                                    }
                                }
                            }
                        }
                        item(key = "cat_gap_${shelf.title}") { Spacer(Modifier.height(Spacing.xl)) }
                    }

                    // ── 2. YOUR YOUTUBE MUSIC PLAYLISTS ──────────────────────────
                    if (ytPlaylists.isNotEmpty()) {
                        item(key = "yt_pl_header") {
                            SectionHeader(
                                title = "Your YouTube Music playlists",
                                modifier = Modifier.padding(top = Spacing.xs),
                            )
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

                    // ── 3. QUICK PICKS (local) ───────────────────────────────────
                    if (quickPicks.isNotEmpty()) {
                        item(key = "qp_header") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "Quick Picks",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MusColors.OnBackground,
                                )
                                if (quickPickPages.size > 1) {
                                    Text(
                                        text = "${quickPickPagerState.currentPage + 1} / ${quickPickPages.size}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MusColors.OnBackgroundTertiary,
                                    )
                                }
                            }
                            Spacer(Modifier.height(Spacing.xs))
                        }

                        item(key = "qp_pager") {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                HorizontalPager(
                                    state = quickPickPagerState,
                                    modifier = Modifier.fillMaxWidth(),
                                ) { pageIndex ->
                                    val pageTracks = quickPickPages.getOrElse(pageIndex) { emptyList() }
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        pageTracks.forEach { track ->
                                            TrackRow(
                                                track = track,
                                                onClick = { viewModel.playQuickPick(track) },
                                                onFavoriteToggle = { viewModel.toggleFavorite(track.id) },
                                                onMoreClick = { selectedTrackForMenu = track },
                                            )
                                        }
                                    }
                                }

                                if (quickPickPages.size > 1) {
                                    Spacer(Modifier.height(Spacing.xs))
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = Spacing.xs),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        repeat(quickPickPages.size) { index ->
                                            val isSelected = quickPickPagerState.currentPage == index
                                            Box(
                                                modifier = Modifier
                                                    .padding(horizontal = 3.dp)
                                                    .size(if (isSelected) 6.dp else 4.dp)
                                                    .background(
                                                        color = if (isSelected) MusColors.OnBackground
                                                        else MusColors.OnBackgroundTertiary.copy(alpha = 0.4f),
                                                        shape = CircleShape
                                                    )
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        item(key = "qp_gap") { Spacer(Modifier.height(Spacing.xl)) }
                    }

                    // ── 4. RECENTLY ADDED (local) ────────────────────────────────
                    if (recentlyAdded.isNotEmpty()) {
                        item(key = "ra_header") {
                            SectionHeader("Recently Added")
                            Spacer(Modifier.height(Spacing.xs))
                        }
                        items(
                            recentlyAdded.take(4),
                            key = { "ra_${it.id}" },
                        ) { track ->
                            TrackRow(
                                track = track,
                                onClick = { onTrackClick(track, recentlyAdded) },
                                onMoreClick = { selectedTrackForMenu = track },
                            )
                        }
                        item(key = "ra_gap") { Spacer(Modifier.height(Spacing.xl)) }
                    }

                    // ── 5. YOUR ALBUMS (local) ───────────────────────────────────
                    if (albums.isNotEmpty()) {
                        item(key = "al_header") {
                            SectionHeader("Your Albums")
                            Spacer(Modifier.height(12.dp))
                        }
                        item(key = "al_row") {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 20.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
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
                        item(key = "al_gap") { Spacer(Modifier.height(Spacing.xl)) }
                    }

                    // ── 6. COLLECTIONS (local playlists incl. Telugu / Hindi) ────
                    if (playlists.isNotEmpty()) {
                        item(key = "col_header") {
                            SectionHeader("Collections")
                            Spacer(Modifier.height(12.dp))
                        }
                        item(key = "col_row") {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 20.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                items(playlists, key = { it.id }) { pl ->
                                    PlaylistCard(
                                        name = pl.name,
                                        onClick = { onPlaylistClick(pl.id) },
                                    )
                                }
                            }
                        }
                        item(key = "col_gap") { Spacer(Modifier.height(Spacing.xl)) }
                    }
                }
            }
        }

        // Catalogue song actions (play / queue / FLAC download / add to collection)
        OnlineResultOverlay(
            result = catalogResult,
            onDismiss = { catalogResult = null },
            discovery = discoveryViewModel,
        )

        // Opened YT playlist (account or catalogue) with its tracks
        selectedYtPlaylist?.let { selection ->
            YtPlaylistTracksSheet(
                selection = selection,
                isLoading = ytPlaylistLoading,
                onDismiss = { discoveryViewModel.closeYtPlaylist() },
                discovery = discoveryViewModel,
            )
        }

        SongMenuContainer(
            selectedTrack = selectedTrackForMenu,
            onDismissMenu = { selectedTrackForMenu = null },
            onNavigateToAlbum = onAlbumClick,
            onPlayTrack = { track ->
                if (quickPicks.any { it.id == track.id }) {
                    viewModel.playQuickPick(track)
                } else {
                    onTrackClick(track, listOf(track))
                }
            },
        )
    }
}
