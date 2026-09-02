package com.mus.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mus.android.ui.theme.MusColors
import com.mus.android.ui.theme.Spacing
import com.mus.android.ui.viewmodel.NowPlayingViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(
    onDismiss: () -> Unit,
    viewModel: NowPlayingViewModel = hiltViewModel(),
) {
    val queue by viewModel.queue.collectAsState()
    val currentIndex by viewModel.currentIndex.collectAsState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MusColors.SurfaceElevated,
        contentColor = MusColors.OnBackground,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.xl),
        ) {
            Text(
                "Queue",
                style = MaterialTheme.typography.titleMedium,
                color = MusColors.OnBackground,
                modifier = Modifier.padding(horizontal = Spacing.base, vertical = Spacing.sm),
            )
            Text(
                "${queue.size} songs",
                style = MaterialTheme.typography.bodySmall,
                color = MusColors.OnBackgroundSecondary,
                modifier = Modifier.padding(horizontal = Spacing.base),
            )
            Spacer(Modifier.height(Spacing.md))

            LazyColumn {
                itemsIndexed(queue) { index, track ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.playQueueItem(index) }
                            .background(
                                if (index == currentIndex) MusColors.SurfaceVariant.copy(alpha = 0.5f)
                                else MusColors.Transparent
                            )
                            .padding(horizontal = Spacing.base, vertical = Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Rounded.DragHandle,
                            contentDescription = "Drag",
                            tint = MusColors.OnBackgroundTertiary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(Spacing.md))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                track.title,
                                style = MaterialTheme.typography.titleSmall,
                                color = if (index == currentIndex) MusColors.OnBackground
                                        else MusColors.OnBackgroundSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                track.artist,
                                style = MaterialTheme.typography.bodySmall,
                                color = MusColors.OnBackgroundTertiary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(
                            onClick = { viewModel.removeFromQueue(index) },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = "Remove",
                                tint = MusColors.OnBackgroundTertiary,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
