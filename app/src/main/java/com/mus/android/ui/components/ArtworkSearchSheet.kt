package com.mus.android.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.mus.android.data.enrichment.provider.ArtworkSearchResult
import com.mus.android.data.model.Track
import com.mus.android.ui.theme.MusColors
import com.mus.android.ui.theme.Spacing
import com.mus.android.ui.viewmodel.ArtworkApplyState
import com.mus.android.ui.viewmodel.ArtworkSearchViewModel

/**
 * Full-screen artwork search and selection UI.
 *
 * - Pre-fills query with "Artist Title"
 * - User can edit the query freely
 * - Shows a grid of artwork candidates from the provider
 * - On tap: shows large confirmation preview
 * - On confirm: applies via [ArtworkSearchViewModel.applyToTrack] or [ArtworkSearchViewModel.applyToAlbum]
 * - Calls [onApplied] with confirmation after success
 *
 * CASE A (fromAlbum = false): only the single track is updated.
 * CASE B (fromAlbum = true):  shows "Apply to album?" prompt, updates all tracks in the album.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtworkSearchSheet(
    track: Track,
    fromAlbum: Boolean = false,
    onBack: () -> Unit,
    onApplied: () -> Unit,
    viewModel: ArtworkSearchViewModel = hiltViewModel(),
) {
    val query by viewModel.query.collectAsState()
    val results by viewModel.results.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val selectedResult by viewModel.selectedResult.collectAsState()
    val applyState by viewModel.applyState.collectAsState()

    // Confirmation dialog when fromAlbum = true
    var showAlbumConfirm by remember { mutableStateOf(false) }
    var pendingResult by remember { mutableStateOf<ArtworkSearchResult?>(null) }

    LaunchedEffect(track.id) {
        viewModel.initQuery(track)
    }

    // Observe apply completion
    LaunchedEffect(applyState) {
        if (applyState is ArtworkApplyState.Success) {
            onApplied()
            viewModel.resetApplyState()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MusColors.Background)
    ) {
        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = Spacing.sm, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back",
                    tint = MusColors.OnBackground,
                )
            }
            Text(
                "Change Artwork",
                style = MaterialTheme.typography.titleMedium,
                color = MusColors.OnBackground,
                modifier = Modifier.weight(1f),
            )
        }

        // Search bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.base, vertical = Spacing.sm)
                .background(MusColors.SurfaceVariant, RoundedCornerShape(12.dp))
                .padding(horizontal = Spacing.md, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.Search,
                contentDescription = null,
                tint = MusColors.OnBackgroundTertiary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(Spacing.sm))
            BasicTextField(
                value = query,
                onValueChange = { viewModel.updateQuery(it) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MusColors.OnBackground),
                cursorBrush = SolidColor(MusColors.OnBackground),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    if (query.isEmpty()) {
                        Text(
                            "Artist · Song title",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MusColors.OnBackgroundTertiary,
                        )
                    }
                    inner()
                },
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

        Box(modifier = Modifier.fillMaxSize()) {
            when {
                isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = MusColors.OnBackground,
                        strokeWidth = 2.dp,
                    )
                }
                results.isEmpty() && query.isNotBlank() -> {
                    Text(
                        "No results found",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MusColors.OnBackgroundTertiary,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        contentPadding = PaddingValues(
                            start = Spacing.base,
                            end = Spacing.base,
                            top = Spacing.sm,
                            bottom = 120.dp,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        items(results, key = { it.id }) { result ->
                            ArtworkResultCard(
                                result = result,
                                onClick = {
                                    if (fromAlbum) {
                                        pendingResult = result
                                        viewModel.selectResult(result)
                                        showAlbumConfirm = true
                                    } else {
                                        viewModel.selectResult(result)
                                    }
                                },
                            )
                        }
                    }
                }
            }

            // Apply state overlay
            if (applyState is ArtworkApplyState.Applying) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.4f)),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = Color.White)
                }
            }
        }
    }

    // Single-track confirmation dialog
    selectedResult?.let { result ->
        if (!fromAlbum) {
            ArtworkConfirmDialog(
                result = result,
                onCancel = { viewModel.clearSelection() },
                onApply = { viewModel.applyToTrack(track.id) },
            )
        }
    }

    // Album-scope confirmation dialog
    if (showAlbumConfirm && pendingResult != null) {
        AlertDialog(
            onDismissRequest = {
                showAlbumConfirm = false
                viewModel.clearSelection()
            },
            title = { Text("Apply to album?", color = MusColors.OnBackground) },
            text = {
                Text(
                    "Apply \"${pendingResult?.album ?: pendingResult?.title}\" artwork to all tracks in \"${track.albumTitle}\"?",
                    color = MusColors.OnBackgroundSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showAlbumConfirm = false
                    viewModel.applyToAlbum(track.albumId)
                }) {
                    Text("Apply to Album", color = MusColors.OnBackground)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    // Also offer single-track option
                    showAlbumConfirm = false
                    viewModel.applyToTrack(track.id)
                }) {
                    Text("This song only", color = MusColors.OnBackgroundSecondary)
                }
            },
            containerColor = MusColors.SurfaceElevated,
        )
    }
}

@Composable
private fun ArtworkResultCard(
    result: ArtworkSearchResult,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .background(MusColors.SurfaceVariant)
            .padding(bottom = Spacing.sm),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(MusColors.Surface),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.Album,
                contentDescription = null,
                tint = MusColors.OnBackgroundTertiary,
                modifier = Modifier.size(40.dp),
            )
            if (!result.artworkUrl.isNullOrBlank()) {
                AsyncImage(
                    model = result.artworkUrl,
                    contentDescription = result.album ?: result.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)),
                )
            }
        }

        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = result.title,
            style = MaterialTheme.typography.labelMedium,
            color = MusColors.OnBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = Spacing.sm),
        )
        Text(
            text = result.artist,
            style = MaterialTheme.typography.labelSmall,
            color = MusColors.OnBackgroundSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = Spacing.sm),
        )
        if (!result.album.isNullOrBlank()) {
            Text(
                text = result.album,
                style = MaterialTheme.typography.labelSmall,
                color = MusColors.OnBackgroundTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = Spacing.sm),
            )
        }
        if (result.year > 0) {
            Text(
                text = result.year.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MusColors.OnBackgroundTertiary,
                modifier = Modifier.padding(horizontal = Spacing.sm),
            )
        }
    }
}

@Composable
private fun ArtworkConfirmDialog(
    result: ArtworkSearchResult,
    onCancel: () -> Unit,
    onApply: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Use this artwork?", color = MusColors.OnBackground) },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(
                    modifier = Modifier
                        .size(200.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MusColors.SurfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.Album,
                        contentDescription = null,
                        tint = MusColors.OnBackgroundTertiary,
                        modifier = Modifier.size(60.dp),
                    )
                    if (!result.artworkUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = result.artworkUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                Spacer(Modifier.height(Spacing.md))
                Text(result.title, style = MaterialTheme.typography.titleMedium, color = MusColors.OnBackground)
                Text(result.artist, style = MaterialTheme.typography.bodySmall, color = MusColors.OnBackgroundSecondary)
                if (!result.album.isNullOrBlank()) {
                    Text(result.album, style = MaterialTheme.typography.bodySmall, color = MusColors.OnBackgroundTertiary)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onApply) {
                Text("Use Artwork", color = MusColors.OnBackground)
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text("Cancel", color = MusColors.OnBackgroundSecondary)
            }
        },
        containerColor = MusColors.SurfaceElevated,
    )
}
