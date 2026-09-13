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
     * Never serves artwork for the global unknown album.
     */
    fun getLocalArtworkUri(albumId: Long): String? {
        if (albumId == UNKNOWN_ALBUM_ID || albumId <= 0L) return null
        val keyFile = File(artworkDir, "art_album_$albumId.jpg")
        if (keyFile.exists() && keyFile.length() > 0L) {
            return fileToUriString(keyFile)
        }
        val legacyFile = File(artworkDir, "art_$albumId.jpg")
        return if (legacyFile.exists() && legacyFile.length() > 0L) {
            fileToUriString(legacyFile)
        } else {
            null
        }
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
     * Returns the file Uri for the cached embedded artwork for a specific track.
     */
    fun getEmbeddedArtworkUri(trackId: Long): String? {
        val file = File(artworkDir, "art_embedded_$trackId.jpg")
        return if (file.exists() && file.length() > 0L) {
            fileToUriString(file)
        } else {
            null
        }
    }

    /**
     * Saves raw embedded artwork bytes isolated per track.
     */
    fun saveEmbeddedArtworkForTrack(trackId: Long, bytes: ByteArray): String? {
        return saveEmbeddedArtworkByKey("embedded_$trackId", bytes)
    }

    /**
     * Saves raw artwork bytes for album ID.
     */
    fun saveEmbeddedArtwork(albumId: Long, bytes: ByteArray): String? {
        if (albumId == UNKNOWN_ALBUM_ID || albumId <= 0L) return null
        return saveEmbeddedArtworkByKey("album_$albumId", bytes)
    }

    /**
     * Downloads artwork from a remote URL and persists it with a specific key.
     */
    suspend fun downloadAndStoreArtworkByKey(artworkKey: String, imageUrl: String): String? = withContext(Dispatchers.IO) {
        if (isUnknownKey(artworkKey)) return@withContext null
        val targetFile = File(artworkDir, "art_$artworkKey.jpg")
        if (targetFile.exists() && targetFile.length() > 0L) {
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
    suspend fun downloadAndStoreArtwork(albumId: Long, imageUrl: String): String? = withContext(Dispatchers.IO) {
        if (albumId == UNKNOWN_ALBUM_ID || albumId <= 0L) return@withContext null
        downloadAndStoreArtworkByKey("album_$albumId", imageUrl)
    }

    private fun fileToUriString(file: File): String {
        return try {
            Uri.fromFile(file)?.toString() ?: "file://${file.absolutePath}"
        } catch (e: Throwable) {
            "file://${file.absolutePath}"
        }
    }

    /**
     * Deletes only remote/generated iTunes album artwork (art_album_*.jpg, art_*.tmp),
     * preserving genuine extracted embedded artwork (art_embedded_*.jpg).
     */
     fun clearRemoteArtwork(): Int {
        var count = 0
        try {
            val dir = File(context.filesDir, "artwork")
            if (dir.exists()) {
                dir.listFiles()?.forEach { file ->
                    if (file.isFile && (file.name.startsWith("art_album_") || file.name.endsWith(".tmp") || (file.name.startsWith("art_") && !file.name.startsWith("art_embedded_")))) {
                        if (file.delete()) count++
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error clearing remote artwork: ${e.message}")
        }
        return count
    }

    /**
     * Clears all cached and generated artwork files in app-owned storage.
     * Deletes only MUS artwork files (e.g. art_*.jpg, art_album_*.jpg, art_*.tmp).
     * Returns the count of deleted files.
     */
    fun clearAllArtwork(): Int {
        var count = 0
        try {
            val dir = File(context.filesDir, "artwork")
            if (dir.exists()) {
                dir.listFiles()?.forEach { file ->
                    if (file.isFile && (file.name.startsWith("art_") || file.name.endsWith(".jpg") || file.name.endsWith(".tmp"))) {
                        if (file.delete()) {
                            count++
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error clearing artwork cache: ${e.message}")
        }
        return count
    }

    /**
     * Clears Coil image disk cache in context.cacheDir/image_cache.
     * Returns count of deleted cache entries.
     */
    fun clearImageCache(): Int {
        var count = 0
        try {
            val coilCache = context.cacheDir.resolve("image_cache")
            if (coilCache.exists()) {
                coilCache.listFiles()?.forEach { file ->
                    if (file.deleteRecursively()) count++
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error clearing Coil image cache: ${e.message}")
        }
        return count
    }

    companion object {
        private const val TAG = "ArtworkStorage"
        val UNKNOWN_ALBUM_ID: Long = com.mus.android.data.scanner.MetadataUtils.generateAlbumId("Unknown Artist", "Unknown Album")

        fun isEmbeddedArtwork(artworkUri: String?): Boolean {
            if (artworkUri.isNullOrBlank()) return false
            return artworkUri.contains("art_embedded_")
        }

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
