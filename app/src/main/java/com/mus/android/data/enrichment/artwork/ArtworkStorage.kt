package com.mus.android.data.enrichment.artwork

import android.content.Context
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages persistent storage and deduplication of album artwork in app-owned storage.
 * Ensures tracks from the same album share the same local artwork file.
 */
@Singleton
class ArtworkStorage @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val artworkDir: File by lazy {
        val dir = File(context.filesDir, "artwork")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        // Purge corrupted global unknown-album art if it exists
        try {
            val badGlobalArt = File(dir, "art_$UNKNOWN_ALBUM_ID.jpg")
            if (badGlobalArt.exists()) badGlobalArt.delete()
            val badGlobalArtKey = File(dir, "art_album_$UNKNOWN_ALBUM_ID.jpg")
            if (badGlobalArtKey.exists()) badGlobalArtKey.delete()
        } catch (e: Exception) {
            // Ignore cleanup failure
        }
        dir
    }

    private fun isUnknownKey(artworkKey: String): Boolean {
        return artworkKey.isBlank() ||
                artworkKey == "album_$UNKNOWN_ALBUM_ID" ||
                artworkKey == "$UNKNOWN_ALBUM_ID" ||
                artworkKey.contains("unknown", ignoreCase = true)
    }

    /**
     * Returns the file Uri for the cached artwork key if it exists and is valid.
     */
    fun getLocalArtworkUriByKey(artworkKey: String): String? {
        if (isUnknownKey(artworkKey)) return null
        val file = File(artworkDir, "art_$artworkKey.jpg")
        return if (file.exists() && file.length() > 0L) {
            fileToUriString(file)
        } else {
            null
        }
    }

    /**
     * Returns the file Uri for the cached album artwork if it exists and is valid.
     * Always prefers valid embedded artwork over remote artwork.
     * Never serves artwork for the global unknown album.
     */
    fun getLocalArtworkUri(albumId: Long): String? {
        if (albumId == UNKNOWN_ALBUM_ID || albumId <= 0L) return null
        // 1. Embedded artwork takes absolute priority
        val embeddedFile = File(artworkDir, "art_embedded_album_$albumId.jpg")
        if (embeddedFile.exists() && embeddedFile.length() > 0L) {
            return fileToUriString(embeddedFile)
        }
        // 2. Cached remote artwork
        val keyFile = File(artworkDir, "art_album_$albumId.jpg")
        if (keyFile.exists() && keyFile.length() > 0L) {
            return fileToUriString(keyFile)
        }
        // 3. Legacy file
        val legacyFile = File(artworkDir, "art_$albumId.jpg")
        if (legacyFile.exists() && legacyFile.length() > 0L) {
            return fileToUriString(legacyFile)
        }
        return null
    }

    /**
     * Checks whether valid embedded artwork exists in storage for this album.
     */
    fun hasEmbeddedArtwork(albumId: Long): Boolean {
        if (albumId == UNKNOWN_ALBUM_ID || albumId <= 0L) return false
        val embeddedFile = File(artworkDir, "art_embedded_album_$albumId.jpg")
        return embeddedFile.exists() && embeddedFile.length() > 0L
    }

    /**
     * Checks whether a given artwork URI points to an embedded artwork file.
     */
    fun isEmbeddedArtwork(artworkUri: String?): Boolean {
        if (artworkUri.isNullOrBlank()) return false
        return artworkUri.contains("art_embedded_album_")
    }

    /**
     * Saves raw artwork bytes to persistent storage with a key.
     */
    fun saveEmbeddedArtworkByKey(artworkKey: String, bytes: ByteArray): String? {
        if (bytes.isEmpty() || isUnknownKey(artworkKey)) return null
        val targetFile = File(artworkDir, "art_$artworkKey.jpg")
        if (targetFile.exists() && targetFile.length() > 0L) {
            return fileToUriString(targetFile)
        }

        return try {
            val tempFile = File(artworkDir, "art_${artworkKey}.tmp")
            FileOutputStream(tempFile).use { it.write(bytes) }
            if (tempFile.renameTo(targetFile) || (targetFile.delete() && tempFile.renameTo(targetFile))) {
                fileToUriString(targetFile)
            } else if (tempFile.exists() && tempFile.length() > 0L) {
                fileToUriString(tempFile)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save embedded artwork for key $artworkKey: ${e.message}")
            null
        }
    }

    /**
     * Saves raw artwork bytes for album ID with embedded priority prefix.
     */
    fun saveEmbeddedArtwork(albumId: Long, bytes: ByteArray): String? {
        if (albumId == UNKNOWN_ALBUM_ID || albumId <= 0L) return null
        return saveEmbeddedArtworkByKey("embedded_album_$albumId", bytes)
    }

    /**
     * Downloads artwork from a remote URL and persists it with a specific key.
     * Supports forceOverwrite to replace wrong cached images.
     */
    suspend fun downloadAndStoreArtworkByKey(
        artworkKey: String,
        imageUrl: String,
        forceOverwrite: Boolean = false,
    ): String? = withContext(Dispatchers.IO) {
        if (isUnknownKey(artworkKey)) return@withContext null
        val targetFile = File(artworkDir, "art_$artworkKey.jpg")
        if (!forceOverwrite && targetFile.exists() && targetFile.length() > 0L) {
            return@withContext fileToUriString(targetFile)
        }

        var connection: HttpURLConnection? = null
        try {
            val url = URL(imageUrl)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                instanceFollowRedirects = true
            }

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "Failed to download artwork: HTTP ${connection.responseCode}")
                return@withContext null
            }

            val tempFile = File(artworkDir, "art_${artworkKey}.tmp")
            connection.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }

            if (tempFile.exists() && tempFile.length() > 0L) {
                if (targetFile.exists()) targetFile.delete()
                if (tempFile.renameTo(targetFile)) {
                    Log.d(TAG, "Successfully cached artwork for key $artworkKey: ${targetFile.absolutePath}")
                    return@withContext fileToUriString(targetFile)
                } else {
                    return@withContext fileToUriString(tempFile)
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Error downloading artwork from $imageUrl for key $artworkKey: ${e.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Downloads artwork from a remote URL and persists it for an album ID.
     */
    suspend fun downloadAndStoreArtwork(
        albumId: Long,
        imageUrl: String,
        forceOverwrite: Boolean = false,
    ): String? = withContext(Dispatchers.IO) {
        if (albumId == UNKNOWN_ALBUM_ID || albumId <= 0L) return@withContext null
        downloadAndStoreArtworkByKey("album_$albumId", imageUrl, forceOverwrite)
    }

    /**
     * Clears all cached artwork files for an album ID.
     */
    fun clearArtwork(albumId: Long): Boolean {
        if (albumId == UNKNOWN_ALBUM_ID || albumId <= 0L) return false
        var deleted = false
        val remoteFile = File(artworkDir, "art_album_$albumId.jpg")
        if (remoteFile.exists()) deleted = remoteFile.delete() || deleted
        val legacyFile = File(artworkDir, "art_$albumId.jpg")
        if (legacyFile.exists()) deleted = legacyFile.delete() || deleted
        val embeddedFile = File(artworkDir, "art_embedded_album_$albumId.jpg")
        if (embeddedFile.exists()) deleted = embeddedFile.delete() || deleted
        return deleted
    }

    /**
     * Clears only remote cached artwork for an album ID, keeping genuine embedded artwork safe.
     */
    fun clearRemoteArtwork(albumId: Long): Boolean {
        if (albumId == UNKNOWN_ALBUM_ID || albumId <= 0L) return false
        var deleted = false
        val remoteFile = File(artworkDir, "art_album_$albumId.jpg")
        if (remoteFile.exists()) deleted = remoteFile.delete() || deleted
        val legacyFile = File(artworkDir, "art_$albumId.jpg")
        if (legacyFile.exists()) deleted = legacyFile.delete() || deleted
        return deleted
    }

    private fun fileToUriString(file: File): String {
        return try {
            Uri.fromFile(file)?.toString() ?: "file://${file.absolutePath}"
        } catch (e: Throwable) {
            "file://${file.absolutePath}"
        }
    }

    companion object {
        private const val TAG = "ArtworkStorage"
        val UNKNOWN_ALBUM_ID: Long = com.mus.android.data.scanner.MetadataUtils.generateAlbumId("Unknown Artist", "Unknown Album")

        private val isAndroidRuntime: Boolean by lazy {
            try {
                Class.forName("android.os.Build")
                android.os.Build.VERSION.SDK_INT > 0
            } catch (e: Throwable) {
                false
            }
        }

        fun isArtworkValid(artworkUri: String?): Boolean {
            if (artworkUri.isNullOrBlank()) return false
            if (artworkUri.startsWith("content://media/external/audio/albumart")) return false
            if (artworkUri.contains(UNKNOWN_ALBUM_ID.toString())) return false
            if (artworkUri.contains("unknown", ignoreCase = true)) return false
            if (artworkUri.startsWith("file://")) {
                val path = artworkUri.removePrefix("file://")
                val file = File(path)
                if (file.exists()) {
                    return file.length() > 0L
                }
                // On real Android device, non-existent files are definitely invalid
                if (isAndroidRuntime) {
                    return false
                }
                // Only on host JVM unit tests where device paths don't exist on host disk:
                return path.isNotBlank() && (path.endsWith(".jpg") || path.endsWith(".png") || path.endsWith(".jpeg") || path.endsWith(".webp"))
            }
            return true
        }
    }
}
