package com.mus.android.ui.components

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.mus.android.data.download.TrackDownloadManager
import com.mus.android.data.enrichment.provider.OnlineSearchResult
import com.mus.android.data.model.Playlist
import com.mus.android.ui.viewmodel.DiscoveryViewModel

/**
 * Shared stateful overlay for an online result: the action sheet plus the
 * "Add to collection" picker, with live download state and toast feedback.
 * Used identically by Home and Search so both surfaces behave the same.
 */
@Composable
fun OnlineResultOverlay(
    result: OnlineSearchResult?,
    onDismiss: () -> Unit,
    discovery: DiscoveryViewModel,
) {
    if (result == null) return
    val context = LocalContext.current
    var showCollectionPicker by remember(result) { mutableStateOf(false) }
    val trackKey = remember(result) {
        TrackDownloadManager.makeDownloadKey(result.title, result.artist)
    }

    val progressMap by discovery.downloadProgress.collectAsState()
    val downloadedKeys by discovery.downloadedKeys.collectAsState()
    val collections by discovery.collections.collectAsState()
    val toastMessage by discovery.addToQueueResult.collectAsState()

    // Compute reactive download state for this result
    val downloadState = progressMap[trackKey]?.let { p ->
        when {
            p.isFinished && p.error == null -> "DONE"
            p.error != null -> "ERROR"
            else -> "DOWNLOADING"
        }
    }
    val isDownloaded = downloadedKeys.contains(trackKey)

    LaunchedEffect(toastMessage) {
        toastMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            discovery.clearAddToQueueResult()
        }
    }

    OnlineResultSheet(
        result = result,
        onDismiss = onDismiss,
        onAddToDownloadList = { discovery.addToDownloadQueue(result) },
        onPlay = { discovery.playOnline(result) },
        onAddToQueue = { discovery.addOnlineToQueue(result) },
        onDownload = { discovery.downloadOnline(result) },
        downloadState = downloadState,
        isDownloaded = isDownloaded,
        onAddToCollection = { showCollectionPicker = true },
    )

    if (showCollectionPicker) {
        AddToPlaylistSheet(
            playlists = collections,
            title = "Add to Collection",
            onPlaylistSelected = { playlistId ->
                discovery.addToCollection(trackKey, playlistId)
                showCollectionPicker = false
                onDismiss()
            },
            onCreateNew = { name ->
                discovery.createCollectionAndAdd(name, trackKey)
                showCollectionPicker = false
                onDismiss()
            },
            onDismiss = { showCollectionPicker = false },
        )
    }
}
