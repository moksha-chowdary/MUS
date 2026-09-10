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
        val data: Any = if (artworkUri.startsWith("/")) {
            java.io.File(artworkUri)
        } else {
            artworkUri
        }
        val request = ImageRequest.Builder(context)
            .data(data)
            .size(128, 128) // Small bitmap for fast palette extraction
            .allowHardware(false) // AndroidX Palette requires software bitmap
            .build()

        return when (val result = imageLoader.execute(request)) {
            is SuccessResult -> (result.drawable as? BitmapDrawable)?.bitmap
            else -> null
        }
    }

    private suspend fun extractFromPalette(palette: Palette, artworkUri: String): ArtworkColors {
        // Collect candidate swatches with preference for vivid/rich colors
        val swatches = palette.swatches.sortedByDescending { it.population }
        val dominantSwatch = palette.dominantSwatch ?: swatches.firstOrNull()
        val vibrantSwatch = palette.vibrantSwatch 
            ?: palette.darkVibrantSwatch 
            ?: palette.lightVibrantSwatch
            ?: swatches.getOrNull(1)
        val mutedSwatch = palette.mutedSwatch 
            ?: palette.darkMutedSwatch 
            ?: palette.lightMutedSwatch
            ?: swatches.getOrNull(2)

        val dominantRgb = dominantSwatch?.rgb ?: 0xFF1E2840.toInt()
        val vibrantRgb = vibrantSwatch?.rgb ?: dominantRgb
        val mutedRgb = mutedSwatch?.rgb ?: dominantRgb

        // Adjust colors using HSV to guarantee rich, visible, elegant atmospheric tones
        val adjustedDominant = adjustAtmosphericColor(dominantRgb, minSat = 0.45f, maxSat = 0.85f, minVal = 0.35f, maxVal = 0.70f)
        val adjustedVibrant = adjustAtmosphericColor(vibrantRgb, minSat = 0.50f, maxSat = 0.90f, minVal = 0.40f, maxVal = 0.75f)
        val adjustedMuted = adjustAtmosphericColor(mutedRgb, minSat = 0.35f, maxSat = 0.75f, minVal = 0.28f, maxVal = 0.55f)

        // Cache in Room
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
     * Adjusts color in HSV space so it retains the album artwork's authentic hue,
     * but is guaranteed to have enough saturation and brightness to be visibly atmospheric,
     * while never blowing out into bright neon or dropping into indistinguishable black.
     */
    private fun adjustAtmosphericColor(
        argb: Int,
        minSat: Float = 0.45f,
        maxSat: Float = 0.85f,
        minVal: Float = 0.35f,
        maxVal: Float = 0.70f,
    ): Int {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(argb, hsv)
        
        // Boost saturation if too muted/grey so the background has beautiful color depth
        hsv[1] = hsv[1].coerceIn(minSat, maxSat)
        // Clamp value/brightness for a rich, dark-mode atmospheric ambient feel
        hsv[2] = hsv[2].coerceIn(minVal, maxVal)

        return android.graphics.Color.HSVToColor(255, hsv)
    }

    companion object {
        val DEFAULT_COLORS = ArtworkColors(
            dominant = Color(0xFF1E2840), // Deep twilight indigo
            vibrant = Color(0xFF2E2042),  // Deep atmospheric amethyst
            muted = Color(0xFF162032),    // Dark midnight slate
        )
    }
}

