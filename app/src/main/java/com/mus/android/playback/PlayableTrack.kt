package com.mus.android.playback

/**
 * A lightweight, playback-ready track descriptor. Catalogue pages (artist /
 * album / radio / related) hand these to the player; playback resolves the
 * selected track's audio stream on demand (and, at most, the next one).
 */
data class PlayableTrack(
    val title: String,
    val artist: String,
    val album: String? = null,
    val artworkUrl: String? = null,
    val videoId: String? = null,
    /** Stable library id when one exists. Never a title or artist string. */
    val internalTrackId: String? = null,
    val playbackUrl: String? = null,
    val playbackMimeType: String? = null,
    val durationMs: Long? = null,
)
