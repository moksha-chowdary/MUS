package com.mus.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.mus.android.data.enrichment.provider.OnlineSearchResult
import com.mus.android.ui.theme.MusColors
import com.mus.android.ui.theme.Spacing

/**
 * A row representing an online music search result.
 * Visually distinct from local TrackRow — shows source badge.
 * Tapping opens the OnlineResultSheet (handled by the parent).
 *
 * This is a discovery/planning surface. Tapping does NOT play audio.
 */
@Composable
fun OnlineTrackRow(
    result: OnlineSearchResult,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.base, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Thumbnail
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MusColors.SurfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = MusColors.OnBackgroundTertiary,
                modifier = Modifier.size(20.dp),
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

        Spacer(Modifier.width(Spacing.md))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = result.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MusColors.OnBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    append(result.artist)
                    if (!result.album.isNullOrBlank()) append(" · ${result.album}")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MusColors.OnBackgroundSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.width(Spacing.sm))

        // Source badge — clearly signals this is NOT a local track
        Surface(
            shape = RoundedCornerShape(4.dp),
            color = MusColors.SurfaceVariant,
        ) {
            Text(
                text = result.source,
                style = MaterialTheme.typography.labelSmall,
                color = MusColors.OnBackgroundSecondary,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
            )
        }
    }
}
