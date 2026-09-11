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
import com.mus.android.data.model.Track
import com.mus.android.ui.components.AlbumCard
import com.mus.android.ui.components.PlaylistCard
import com.mus.android.ui.components.SongMenuContainer
import com.mus.android.ui.components.TrackRow
import com.mus.android.ui.theme.MusColors
import com.mus.android.ui.theme.Spacing
import com.mus.android.ui.viewmodel.HomeViewModel
import com.mus.android.ui.viewmodel.PermissionState

@Composable
fun HomeScreen(
    onAlbumClick: (Long) -> Unit,
    onPlaylistClick: (Long) -> Unit = {},
    onSettingsClick: () -> Unit = {},
    onTrackClick: (Track, List<Track>) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val isLoading by viewModel.isLoading.collectAsState()
    val needsPermission by viewModel.needsPermission.collectAsState()
    val permissionState by viewModel.permissionState.collectAsState()
    val tracks by viewModel.tracks.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val recentlyAdded by viewModel.recentlyAdded.collectAsState()
    val mostPlayed by viewModel.mostPlayed.collectAsState()
    val recentlyPlayed by viewModel.recentlyPlayed.collectAsState()
    val albums by viewModel.albums.collectAsState()
    val randomAlbums by viewModel.randomAlbums.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    val quickPicks by viewModel.quickPicks.collectAsState()

    val quickPickPages = remember(quickPicks) { quickPicks.chunked(4) }
    val quickPickPagerState = rememberPagerState(pageCount = { quickPickPages.size })

    val systemPlaylists = remember(playlists) {
        playlists.filter { it.isSystemPlaylist || com.mus.android.data.classifier.LanguageClassifier.isDefaultPlaylist(it.name) }
    }
    val userPlaylists = remember(playlists) {
        playlists.filter { !it.isSystemPlaylist }
    }

    var selectedTrackForMenu by remember { mutableStateOf<Track?>(null) }

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

            tracks.isEmpty() -> {
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
                        "Your Muzic folder is empty",
                        style = MaterialTheme.typography.titleMedium,
                        color = MusColors.OnBackground,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        "MUS organizes audio files strictly inside your dedicated /Muzic/ folder and its subdirectories. Add songs to /Muzic/ or choose a custom folder to begin.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MusColors.OnBackgroundSecondary,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(Spacing.xl))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (!viewModel.doesMuzicDirectoryExist()) {
                            Button(
                                onClick = {
                                    viewModel.createMuzicDirectory()
                                    viewModel.refreshLibrary()
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MusColors.OnBackground,
                                    contentColor = MusColors.Background,
                                ),
                                shape = RoundedCornerShape(8.dp),
                            ) {
                                Text("Add Music")
                            }
                        }
                        OutlinedButton(
                            onClick = { folderPickerLauncher.launch(null) },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MusColors.OnBackground),
                        ) {
                            Text("Choose Muzic Folder")
                        }
                        IconButton(onClick = { viewModel.refreshLibrary() }) {
                            Icon(
                                Icons.Rounded.Refresh,
                                contentDescription = "Rescan",
                                tint = MusColors.OnBackground,
                            )
                        }
                    }
                    Spacer(Modifier.height(Spacing.md))
                    TextButton(onClick = onSettingsClick) {
                        Text(
                            "Vault Settings",
                            style = MaterialTheme.typography.labelMedium,
                            color = MusColors.OnBackgroundSecondary,
                        )
                    }
                }
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 120.dp), // space for mini-player + nav
                ) {
                    // Header
                    item {
                        Spacer(Modifier.height(Spacing.xxxl))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Spacing.base),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_mus_logo),
                                    contentDescription = "MUS Logo",
                                    tint = Color.Unspecified,
                                    modifier = Modifier.size(28.dp),
                                )
                                Spacer(Modifier.width(Spacing.sm))
                                Text(
                                    "MUS",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MusColors.OnBackground,
                                )
                            }

                            IconButton(onClick = onSettingsClick) {
                                Icon(
                                    Icons.Rounded.Settings,
                                    contentDescription = "Settings",
                                    tint = MusColors.OnBackgroundSecondary,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                        }
                        Spacer(Modifier.height(Spacing.xl))
                    }

                    // 1. QUICK PICKS — top position, 8 songs, 4 per page
                    if (quickPicks.isNotEmpty()) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = Spacing.base),
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

                        item {
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
                        item { Spacer(Modifier.height(Spacing.xl)) }
                    }

                    // 2. LANGUAGE MIXES (Telugu, Tamil, Hindi, English, Other)
                    if (systemPlaylists.isNotEmpty()) {
                        item {
                            Text(
                                "Language Mixes",
                                style = MaterialTheme.typography.titleMedium,
                                color = MusColors.OnBackground,
                                modifier = Modifier.padding(horizontal = Spacing.base),
                            )
                            Spacer(Modifier.height(Spacing.md))
                        }
                        item {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = Spacing.base),
                                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                            ) {
                                items(systemPlaylists, key = { it.id }) { pl ->
                                    val tag = when {
                                        pl.name.contains("Telugu", ignoreCase = true) || pl.systemKey == "TELUGU" -> "TE"
                                        pl.name.contains("Tamil", ignoreCase = true) || pl.systemKey == "TAMIL" -> "TA"
                                        pl.name.contains("Hindi", ignoreCase = true) || pl.systemKey == "HINDI" -> "HI"
                                        pl.name.contains("English", ignoreCase = true) || pl.systemKey == "ENGLISH" -> "EN"
                                        else -> "MIX"
                                    }
                                    Column(
                                        modifier = Modifier
                                            .width(150.dp)
                                            .clickable { onPlaylistClick(pl.id) }
                                            .background(MusColors.SurfaceVariant, RoundedCornerShape(10.dp))
                                            .padding(Spacing.md),
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(42.dp)
                                                .background(MusColors.SurfaceElevated, RoundedCornerShape(8.dp)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(tag, style = MaterialTheme.typography.labelMedium, color = MusColors.OnBackground)
                                        }
                                        Spacer(Modifier.height(Spacing.sm))
                                        Text(
                                            pl.name,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MusColors.OnBackground,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            "Auto-mix",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MusColors.OnBackgroundSecondary
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(Spacing.xl))
                        }
                    }

                    // 3. YOUR ALBUMS
                    if (albums.isNotEmpty()) {
                        item {
                            Text(
                                "Your Albums",
                                style = MaterialTheme.typography.titleMedium,
                                color = MusColors.OnBackground,
                                modifier = Modifier.padding(horizontal = Spacing.base),
                            )
                            Spacer(Modifier.height(Spacing.sm))
                        }
                        item {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = Spacing.base),
                                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
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
                            Spacer(Modifier.height(Spacing.xl))
                        }
                    }

                    // 4. PLAYLISTS (User custom playlists)
                    if (userPlaylists.isNotEmpty()) {
                        item {
                            Text(
                                "Playlists",
                                style = MaterialTheme.typography.titleMedium,
                                color = MusColors.OnBackground,
                                modifier = Modifier.padding(horizontal = Spacing.base),
                            )
                            Spacer(Modifier.height(Spacing.sm))
                        }
                        item {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = Spacing.base),
                                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                            ) {
                                items(userPlaylists, key = { it.id }) { pl ->
                                    PlaylistCard(
                                        name = pl.name,
                                        trackCount = 0,
                                        artworkUri = null,
                                        onClick = { onPlaylistClick(pl.id) },
                                    )
                                }
                            }
                            Spacer(Modifier.height(Spacing.xl))
                        }
                    }
                }
            }
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
