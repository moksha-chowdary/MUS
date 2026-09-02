package com.mus.android.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mus.android.data.model.Track
import com.mus.android.ui.components.AlbumCard
import com.mus.android.ui.components.TrackRow
import com.mus.android.ui.theme.MusColors
import com.mus.android.ui.theme.Spacing
import com.mus.android.ui.viewmodel.HomeViewModel

@Composable
fun HomeScreen(
    onAlbumClick: (Long) -> Unit,
    onPlaylistClick: (Long) -> Unit = {},
    onTrackClick: (Track, List<Track>) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val isLoading by viewModel.isLoading.collectAsState()
    val needsPermission by viewModel.needsPermission.collectAsState()
    val recentlyAdded by viewModel.recentlyAdded.collectAsState()
    val albums by viewModel.albums.collectAsState()
    val randomAlbums by viewModel.randomAlbums.collectAsState()
    val playlists by viewModel.playlists.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.onPermissionGranted()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MusColors.Background)
    ) {
        when {
            needsPermission -> {
                // Permission request screen
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(Spacing.xxl),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        Icons.Rounded.MusicNote,
                        contentDescription = null,
                        tint = MusColors.OnBackgroundTertiary,
                        modifier = Modifier.size(64.dp),
                    )
                    Spacer(Modifier.height(Spacing.xl))
                    Text(
                        "MUS needs access to your music",
                        style = MaterialTheme.typography.titleMedium,
                        color = MusColors.OnBackground,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        "Grant permission to scan your device for audio files",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MusColors.OnBackgroundSecondary,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(Spacing.xl))
                    Button(
                        onClick = {
                            val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                Manifest.permission.READ_MEDIA_AUDIO
                            } else {
                                Manifest.permission.READ_EXTERNAL_STORAGE
                            }
                            permissionLauncher.launch(permission)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MusColors.OnBackground,
                            contentColor = MusColors.Background,
                        )
                    ) {
                        Text("Grant Permission")
                    }
                }
            }

            isLoading -> {
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
                            "Scanning your music...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MusColors.OnBackgroundSecondary,
                        )
                    }
                }
            }

            albums.isEmpty() -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(Spacing.xxl),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        Icons.Rounded.MusicNote,
                        contentDescription = null,
                        tint = MusColors.OnBackgroundTertiary,
                        modifier = Modifier.size(64.dp),
                    )
                    Spacer(Modifier.height(Spacing.xl))
                    Text(
                        "No music found",
                        style = MaterialTheme.typography.titleMedium,
                        color = MusColors.OnBackground,
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        "Add some music files to your device and tap refresh",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MusColors.OnBackgroundSecondary,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(Spacing.xl))
                    IconButton(onClick = { viewModel.refreshLibrary() }) {
                        Icon(
                            Icons.Rounded.Refresh,
                            contentDescription = "Refresh",
                            tint = MusColors.OnBackground,
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
                        Text(
                            "MUS",
                            style = MaterialTheme.typography.titleLarge,
                            color = MusColors.OnBackground,
                            modifier = Modifier.padding(horizontal = Spacing.base),
                        )
                        Spacer(Modifier.height(Spacing.xl))
                    }

                    // Albums section
                    if (randomAlbums.isNotEmpty()) {
                        item {
                            Text(
                                "Albums",
                                style = MaterialTheme.typography.titleMedium,
                                color = MusColors.OnBackground,
                                modifier = Modifier.padding(horizontal = Spacing.base),
                            )
                            Spacer(Modifier.height(Spacing.md))
                        }
                        item {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = Spacing.md),
                                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                            ) {
                                items(randomAlbums) { album ->
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

                    // Language Mixes section
                    val langPlaylists = playlists.filter {
                        it.name.startsWith("Hindi") || it.name.startsWith("English") || it.name.startsWith("Regional")
                    }
                    if (langPlaylists.isNotEmpty()) {
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
                                contentPadding = PaddingValues(horizontal = Spacing.md),
                                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                            ) {
                                items(langPlaylists) { pl ->
                                    val tag = when {
                                        pl.name.startsWith("Hindi") -> "HI"
                                        pl.name.startsWith("English") -> "EN"
                                        else -> "REG"
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

                    // Recently added section
                    if (recentlyAdded.isNotEmpty()) {
                        item {
                            Text(
                                "Recently Added",
                                style = MaterialTheme.typography.titleMedium,
                                color = MusColors.OnBackground,
                                modifier = Modifier.padding(horizontal = Spacing.base),
                            )
                            Spacer(Modifier.height(Spacing.sm))
                        }
                        items(recentlyAdded.take(10)) { track ->
                            TrackRow(
                                track = track,
                                onClick = { onTrackClick(track, recentlyAdded) },
                            )
                        }
                    }
                }
            }
        }
    }
}
