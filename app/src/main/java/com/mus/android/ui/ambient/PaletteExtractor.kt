package com.mus.android.ui.ambient

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.blue
import androidx.core.graphics.green
import androidx.core.graphics.red
import androidx.palette.graphics.Palette
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.mus.android.data.db.PaletteDao
import com.mus.android.data.model.ArtworkPalette
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Extracts dominant colors from album artwork using AndroidX Palette.
 * Caches results in Room to avoid repeated computation.
 */
@Singleton
class PaletteExtractor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val paletteDao: PaletteDao,
    private val imageLoader: ImageLoader,
) {
    data class ArtworkColors(
        val dominant: Color,
        val vibrant: Color,
        val muted: Color,
    )

    /**
     * Returns extracted colors for the given artwork URI.
     * Uses cache first, extracts on cache miss.
     */
    suspend fun extractColors(artworkUri: String?): ArtworkColors {
        if (artworkUri.isNullOrBlank()) return DEFAULT_COLORS

        // Check cache
        paletteDao.getPalette(artworkUri)?.let { cached ->
            return ArtworkColors(
                dominant = Color(cached.dominantColor),
                vibrant = Color(cached.vibrantColor ?: cached.dominantColor),
                muted = Color(cached.mutedColor ?: cached.dominantColor),
            )
        }

        // Extract from bitmap
        val colors = withContext(Dispatchers.Default) {
            try {
                val bitmap = loadBitmap(artworkUri) ?: return@withContext null
                val palette = Palette.from(bitmap).generate()
                extractFromPalette(palette, artworkUri)
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        } ?: return DEFAULT_COLORS

        return colors
    }

    private suspend fun loadBitmap(artworkUri: String): Bitmap? {
        val request = ImageRequest.Builder(context)
            .data(Uri.parse(artworkUri))
            .size(128, 128) // Small bitmap for palette extraction
            .allowHardware(false) // Palette needs software bitmap
            .build()

        return when (val result = imageLoader.execute(request)) {
            is SuccessResult -> (result.drawable as? BitmapDrawable)?.bitmap
            else -> null
        }
    }

    private suspend fun extractFromPalette(palette: Palette, artworkUri: String): ArtworkColors {
        val dominantSwatch = palette.dominantSwatch
        val vibrantSwatch = palette.vibrantSwatch ?: palette.lightVibrantSwatch
        val mutedSwatch = palette.mutedSwatch ?: palette.darkMutedSwatch

        val dominantColor = dominantSwatch?.rgb ?: 0xFF1A1A1A.toInt()
        val vibrantColor = vibrantSwatch?.rgb
        val mutedColor = mutedSwatch?.rgb

        // Adjust colors: avoid too dark or too bright
        val adjustedDominant = adjustColor(dominantColor)
        val adjustedVibrant = adjustColor(vibrantColor ?: dominantColor)
        val adjustedMuted = adjustColor(mutedColor ?: dominantColor)

        // Cache
        paletteDao.insert(
            ArtworkPalette(
                artworkUri = artworkUri,
                dominantColor = adjustedDominant,
                vibrantColor = adjustedVibrant,
                mutedColor = adjustedMuted,
                darkVibrantColor = palette.darkVibrantSwatch?.rgb,
                darkMutedColor = palette.darkMutedSwatch?.rgb,
                lightVibrantColor = palette.lightVibrantSwatch?.rgb,
            )
        )

        return ArtworkColors(
            dominant = Color(adjustedDominant),
            vibrant = Color(adjustedVibrant),
            muted = Color(adjustedMuted),
        )
    }

    /**
     * Ensures colors aren't too dark (invisible on dark background)
     * or too bright (reduce readability). Clamp luminance.
     */
    private fun adjustColor(argb: Int): Int {
        val r = argb.red
        val g = argb.green
        val b = argb.blue
        val luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0

        // Too dark: lighten slightly
        if (luminance < 0.15) {
            val factor = 1.8f
            return android.graphics.Color.argb(
                255,
                (r * factor).toInt().coerceIn(0, 255),
                (g * factor).toInt().coerceIn(0, 255),
                (b * factor).toInt().coerceIn(0, 255)
            )
        }
        // Too bright: darken
        if (luminance > 0.85) {
            val factor = 0.6f
            return android.graphics.Color.argb(
                255,
                (r * factor).toInt().coerceIn(0, 255),
                (g * factor).toInt().coerceIn(0, 255),
                (b * factor).toInt().coerceIn(0, 255)
            )
        }
        return argb
    }

    companion object {
        val DEFAULT_COLORS = ArtworkColors(
            dominant = Color(0xFF2A2A2A),
            vibrant = Color(0xFF3A3A3A),
            muted = Color(0xFF1A1A1A),
        )
    }
}
