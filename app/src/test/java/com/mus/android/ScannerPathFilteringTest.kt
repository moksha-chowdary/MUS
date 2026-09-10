package com.mus.android

import com.mus.android.data.scanner.MediaStoreScanner
import org.junit.Assert.*
import org.junit.Test

class ScannerPathFilteringTest {

    private fun isPathInMuzic(path: String): Boolean {
        val normalized = path.replace("\\", "/")
        return normalized.contains("/Muzic/", ignoreCase = true) ||
                normalized.startsWith("Muzic/", ignoreCase = true) ||
                normalized.startsWith("/Muzic", ignoreCase = true)
    }

    private fun isAudioSupported(fileName: String): Boolean {
        val ext = fileName.substringAfterLast(".", "").lowercase()
        return ext in MediaStoreScanner.SUPPORTED_AUDIO_EXTENSIONS
    }

    private fun getMetadataFallback(rawTitle: String?, fileName: String, rawArtist: String?, rawAlbum: String?): Triple<String, String, String> {
        val title = when {
            !rawTitle.isNullOrBlank() && !rawTitle.equals("<unknown>", true) -> rawTitle
            else -> fileName.substringBeforeLast(".")
        }
        val artist = if (!rawArtist.isNullOrBlank() && !rawArtist.equals("<unknown>", true)) rawArtist else "Unknown Artist"
        val album = if (!rawAlbum.isNullOrBlank() && !rawAlbum.equals("<unknown>", true)) rawAlbum else "Unknown Album"
        return Triple(title, artist, album)
    }

    @Test
    fun testStrictMuzicRootEnforcement() {
        // Valid paths inside /Muzic/
        assertTrue(isPathInMuzic("/storage/emulated/0/Muzic/song1.flac"))
        assertTrue(isPathInMuzic("/storage/emulated/0/Muzic/downloads/song2.mp3"))
        assertTrue(isPathInMuzic("/storage/emulated/0/Muzic/anything/nested/song3.m4a"))
        assertTrue(isPathInMuzic("Muzic/album/track.wav"))

        // Invalid paths outside /Muzic/ - must be rejected
        assertFalse(isPathInMuzic("/storage/emulated/0/DCIM/video_audio.mp4"))
        assertFalse(isPathInMuzic("/storage/emulated/0/Download/random_song.mp3"))
        assertFalse(isPathInMuzic("/storage/emulated/0/WhatsApp/Media/WhatsApp Voice Notes/ptt.opus"))
        assertFalse(isPathInMuzic("/storage/emulated/0/Telegram/Audio/voice.ogg"))
        assertFalse(isPathInMuzic("/storage/emulated/0/Android/data/com.example/cache.mp3"))
    }

    @Test
    fun testAudioExtensionFiltering() {
        assertTrue(isAudioSupported("song.flac"))
        assertTrue(isAudioSupported("song.mp3"))
        assertTrue(isAudioSupported("song.m4a"))
        assertTrue(isAudioSupported("song.wav"))
        assertTrue(isAudioSupported("song.alac"))
        assertTrue(isAudioSupported("song.opus"))
        assertTrue(isAudioSupported("song.ogg"))
        assertTrue(isAudioSupported("song.aac"))

        assertFalse(isAudioSupported("image.jpg"))
        assertFalse(isAudioSupported("document.pdf"))
        assertFalse(isAudioSupported("video.mp4"))
        assertFalse(isAudioSupported("archive.zip"))
    }

    @Test
    fun testMetadataFallback() {
        // Missing title -> filename without extension
        val (title1, artist1, album1) = getMetadataFallback(null, "Midnight_City.mp3", null, null)
        assertEquals("Midnight_City", title1)
        assertEquals("Unknown Artist", artist1)
        assertEquals("Unknown Album", album1)

        // Title with <unknown> placeholder
        val (title2, artist2, album2) = getMetadataFallback("<unknown>", "01_Track.flac", "<unknown>", "<unknown>")
        assertEquals("01_Track", title2)
        assertEquals("Unknown Artist", artist2)
        assertEquals("Unknown Album", album2)

        // Valid metadata preserved
        val (title3, artist3, album3) = getMetadataFallback("Starboy", "file.mp3", "The Weeknd", "Starboy")
        assertEquals("Starboy", title3)
        assertEquals("The Weeknd", artist3)
        assertEquals("Starboy", album3)
    }
}
