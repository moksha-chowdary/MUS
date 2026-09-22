package com.mus.android.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.mus.android.data.enrichment.provider.OnlineSearchResult
import com.mus.android.ui.theme.MusColors
import com.mus.android.ui.theme.Spacing

/**
 * Bottom sheet shown when the user taps an online search result.
 * Provides:
 *   - Add to MUS To Download (always available)
 *   - Open Source (opens official URL in browser)
 *   - Add to YouTube Playlist (shown if YouTube OAuth is configured, gated)
 *
 * MUS does NOT download audio. This is a discovery / planning surface only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnlineResultSheet(
    result: OnlineSearchResult,
    onDismiss: () -> Unit,
    onAddToDownloadList: () -> Unit,
    youTubeOAuthConfigured: Boolean = false,
    onAddToYouTubePlaylist: (() -> Unit)? = null,
) {
    val context = LocalContext.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MusColors.SurfaceElevated,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        ) {
            // Header: Artwork + Title + Artist
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MusColors.SurfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.MusicNote,
                        contentDescription = null,
                        tint = MusColors.OnBackgroundTertiary,
                        modifier = Modifier.size(30.dp),
                    )
                    if (!result.artworkUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = result.artworkUrl,
                            contentDescription = result.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                Spacer(Modifier.width(Spacing.md))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = result.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MusColors.OnBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = result.artist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MusColors.OnBackgroundSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!result.album.isNullOrBlank()) {
                        Text(
                            text = result.album,
                            style = MaterialTheme.typography.bodySmall,
                            color = MusColors.OnBackgroundTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                // Source badge
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = if (result.source == "YouTube") MusColors.Error.copy(alpha = 0.15f)
                    else MusColors.SurfaceVariant,
                ) {
                    Text(
                        text = result.source,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (result.source == "YouTube") MusColors.Error
                        else MusColors.OnBackgroundSecondary,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = Spacing.md),
                color = MusColors.Divider,
            )

            // Actions
            OnlineResultAction(
                icon = Icons.AutoMirrored.Rounded.PlaylistAdd,
                title = "Add to MUS — To Download",
                subtitle = "Save to your personal download planning list",
                onClick = {
                    onAddToDownloadList()
                    onDismiss()
                },
            )

            OnlineResultAction(
                icon = Icons.AutoMirrored.Rounded.OpenInNew,
                title = "Open Source",
                subtitle = result.sourceUrl,
                onClick = {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(result.sourceUrl)))
                    } catch (e: Exception) { /* no browser */ }
                    onDismiss()
                },
            )

            if (result.source == "YouTube") {
                if (youTubeOAuthConfigured && onAddToYouTubePlaylist != null) {
                    OnlineResultAction(
                        icon = Icons.AutoMirrored.Rounded.QueueMusic,
                        title = "Add to YouTube Playlist",
                        subtitle = "Add to \"MUS — To Download\" on YouTube",
                        onClick = {
                            onAddToYouTubePlaylist()
                            onDismiss()
                        },
                    )
                } else {
                    OnlineResultAction(
                        icon = Icons.Rounded.LinkOff,
                        title = "Connect YouTube to enable this",
                        subtitle = "Set youtube.oauth.client_id in local.properties",
                        enabled = false,
                        onClick = {},
                    )
                }
            }
        }
    }
}

@Composable
private fun OnlineResultAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val alpha = if (enabled) 1f else 0.4f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (enabled) it.clickableNoRipple(onClick) else it }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MusColors.OnBackground.copy(alpha = alpha),
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(Spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MusColors.OnBackground.copy(alpha = alpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MusColors.OnBackgroundTertiary.copy(alpha = alpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// Utility: clickable without ripple (used for disabled-state items)
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    composed {
        this.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick,
        )
    }
