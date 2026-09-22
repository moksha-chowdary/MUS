package com.mus.android.data.model

/**
 * Artwork provenance hierarchy.
 * MANUAL > EMBEDDED > EXTERNAL > NONE
 * A MANUAL artwork must never be overwritten by automatic enrichment.
 */
object ArtworkSource {
    const val MANUAL   = "MANUAL"    // User explicitly selected this artwork
    const val EMBEDDED = "EMBEDDED"  // Extracted from the audio file's embedded tags
    const val EXTERNAL = "EXTERNAL"  // Downloaded from a remote metadata provider
    const val NONE     = ""          // No artwork present
}
