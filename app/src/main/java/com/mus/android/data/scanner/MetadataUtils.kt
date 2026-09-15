package com.mus.android.data.scanner

import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Deterministic and collision-resistant metadata identification and resolution utilities.
 */
object MetadataUtils {
    /**
     * Generates a deterministic, collision-resistant 63-bit positive Long ID using SHA-256.
     * Prevents hash collisions and preserves stable IDs across app restarts and scans.
     */
    fun generateDeterministicId(key: String): Long {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        return ByteBuffer.wrap(digest).long and Long.MAX_VALUE
    }

    /**
     * Canonical album ID based on normalized Album Artist + Album Title.
     */
    fun generateAlbumId(albumArtist: String, albumTitle: String): Long {
        val normalized = "${normalizeString(albumArtist)}|${normalizeString(albumTitle)}"
        return generateDeterministicId("album:$normalized")
    }

    /**
     * Canonical artist ID based on normalized artist name.
     */
    fun generateArtistId(artistName: String): Long {
        val normalized = normalizeString(artistName)
        return generateDeterministicId("artist:$normalized")
    }

    /**
     * Canonical track ID based on path or URI.
     */
    fun generateTrackId(pathOrUri: String): Long {
        val normalized = normalizeString(pathOrUri)
        return generateDeterministicId("track:$normalized")
    }

    fun normalizeString(input: String): String {
        return input.trim().lowercase()
    }

    fun isPlaceholderArtist(artist: String?): Boolean {
        if (artist.isNullOrBlank()) return true
        val trimmed = artist.trim()
        return trimmed.equals("Unknown Artist", ignoreCase = true) ||
                trimmed.equals("<unknown>", ignoreCase = true) ||
                trimmed.equals("Unknown", ignoreCase = true)
    }

    fun isPlaceholderAlbum(album: String?): Boolean {
        if (album.isNullOrBlank()) return true
        val trimmed = album.trim()
        return trimmed.equals("Unknown Album", ignoreCase = true) ||
                trimmed.equals("<unknown>", ignoreCase = true) ||
                trimmed.equals("Unknown", ignoreCase = true) ||
                trimmed.equals("Muzic", ignoreCase = true) ||
                trimmed.equals("Music", ignoreCase = true)
    }

    fun isPlaceholderTitle(title: String?): Boolean {
        if (title.isNullOrBlank()) return true
        val trimmed = title.trim()
        return trimmed.equals("Unknown Track", ignoreCase = true) ||
                trimmed.equals("<unknown>", ignoreCase = true) ||
                trimmed.equals("Unknown", ignoreCase = true)
    }

    /**
     * Generates a deterministic artwork key:
     * - If album and artist are known: "album_${albumId}"
     * - If either is placeholder (undetermined album): "file_${fileHash}" using stable physical file identity
     *   (ensuring untagged tracks never collide and maintain stable artwork across rescans/reconciliation)
     */
    fun generateArtworkKey(albumArtist: String, albumTitle: String, pathOrUri: String): String {
        return if (!isPlaceholderArtist(albumArtist) && !isPlaceholderAlbum(albumTitle)) {
            "album_${generateAlbumId(albumArtist, albumTitle)}"
        } else {
            val normalized = normalizeString(pathOrUri)
            "file_${generateDeterministicId("file:$normalized")}"
        }
    }

    /**
     * Extracts the Muzic-relative path from any absolute path or URI.
     * Preserves original filename case (physical file identity).
     * Normalizes only: URL encoding, path separators, and /Muzic/ prefix detection.
     * Examples:
     *   "/storage/emulated/0/Muzic/Drake/NOKIA.mp3" -> "Drake/NOKIA.mp3"
     *   "content://...Muzic%2FDrake%2FNOKIA.mp3"    -> "Drake/NOKIA.mp3"
     * Returns null if no /Muzic/ segment can be found.
     */
    fun extractMuzicRelativePath(pathOrUri: String): String? {
        if (pathOrUri.isBlank()) return null
        // Decode URL encoding if present
        val decoded = try {
            java.net.URLDecoder.decode(pathOrUri, "UTF-8")
        } catch (e: Exception) {
            pathOrUri
        }
        // Normalize backslashes to forward slashes
        val normalized = decoded.replace('\\', '/')
        val lowerNormalized = normalized.lowercase()
        // 1. Search for /muzic/ or :muzic/ (SAF document URIs) boundary
        val muzicSlashIdx = lowerNormalized.lastIndexOf("/muzic/")
        val muzicColonIdx = lowerNormalized.lastIndexOf(":muzic/")
        val muzicIdx = maxOf(muzicSlashIdx, muzicColonIdx)
        if (muzicIdx >= 0) {
            val relative = normalized.substring(muzicIdx + 7).trim().removePrefix("/")
            if (relative.isNotBlank()) return relative
        }
        // 2. Search for leading muzic/ without slash
        if (lowerNormalized.startsWith("muzic/")) {
            val relative = normalized.substring("muzic/".length).trim().removePrefix("/")
            if (relative.isNotBlank()) return relative
        }
        // 3. Direct relative path (e.g. from SAF recursion: "Drake/NOKIA.mp3")
        if (!normalized.startsWith("/") && !normalized.contains("://")) {
            val trimmed = normalized.trim().removePrefix("/")
            if (trimmed.isNotBlank()) return trimmed
        }
        return null
    }

    /**
     * Extracts the direct child folder name under Muzic/ for a given path or URI.
     * Returns the top-level folder name (e.g. "English" for "Muzic/English/song.flac" or
     * "Muzic/English/Pop/song.flac").
     * Returns null if the file is directly in the Muzic/ root directory (e.g. "Muzic/song.flac")
     * or if no valid relative path can be resolved.
     */
    fun extractMuzicDirectFolder(pathOrUri: String?): String? {
        if (pathOrUri.isNullOrBlank()) return null
        val relative = extractMuzicRelativePath(pathOrUri) ?: return null
        val clean = relative.trim().removePrefix("/").removePrefix("\\")
        val slashIdx = clean.indexOfAny(charArrayOf('/', '\\'))
        if (slashIdx <= 0) {
            // File is at root of Muzic (e.g. "random_song.flac"), not in any subfolder
            return null
        }
        val folder = clean.substring(0, slashIdx).trim()
        return if (folder.isNotBlank()) folder else null
    }

    /**
     * Generates a stable, deterministic track ID from a file path.
     * Uses the Muzic-relative path so the same physical file always gets the same ID
     * regardless of whether MediaStore, SAF, or direct directory scan discovers it.
     * Preserves original case in the identity hash (case-sensitive file identity).
     * Returns null if no stable path can be determined (caller should use a fallback).
     */
    fun generateStableTrackId(filePath: String?): Long? {
        if (filePath.isNullOrBlank()) return null
        val relative = extractMuzicRelativePath(filePath) ?: return null
        return generateDeterministicId("track_path:$relative")
    }

    data class FilenameHints(
        val artistHint: String?,
        val titleHint: String,
        val cleanSearchQuery: String,
    )

    private val JUNK_SUFFIX_REGEX = Regex(
        """(?i)[\(\[\{]\s*(?:official\s*(?:music\s*)?video|official\s*audio|audio|video|lyric\s*video|lyrics?|visualizer|visualiser|live|hd|4k|remastered|explicit|clean|radio\s*edit|prod\.?[^)\]}]+|produced\s+by[^)\]}]+|from\s+[^)\]}]+|ft\.?[^)\]}]+|feat\.?[^)\]}]+)\s*[)\]\}]"""
    )
    private val LEADING_TRACK_NUMBER_REGEX = Regex("""^\d+[\s._-]+""")

    fun cleanNoise(text: String): String {
        var cleaned = text.substringBefore("|").substringBefore("｜")
        cleaned = JUNK_SUFFIX_REGEX.replace(cleaned, " ")
        cleaned = cleaned.replace(Regex("""\s+"""), " ").trim()
        return cleaned
    }

    /**
     * Parses filename to extract artist/title candidates and search query.
     * Examples:
     * "Drake - NOKIA (Official Music Video)" -> artist: "Drake", title: "NOKIA"
     * "Travis Scott - FE!N ft. Playboi Carti" -> artist: "Travis Scott", title: "FE!N"
     * "FE!N" -> artist: null, title: "FE!N"
     */
    fun parseFilenameHints(fileNameFallback: String?): FilenameHints {
        if (fileNameFallback.isNullOrBlank()) {
            return FilenameHints(null, "Unknown Track", "Unknown Track")
        }
        val nameWithoutPath = fileNameFallback.substringAfterLast("/").substringAfterLast("\\").trim()
        val baseName = if (nameWithoutPath.contains(".")) nameWithoutPath.substringBeforeLast(".").trim() else nameWithoutPath
        val noTrackNum = LEADING_TRACK_NUMBER_REGEX.replace(baseName, "").trim()

        // Check for artist - title separators: " - ", " – ", " — "
        val parts = noTrackNum.split(Regex("""\s+[-–—]\s+"""), limit = 2)
        return if (parts.size >= 2) {
            val rawArtist = parts[0].trim()
            val rawTitle = parts[1].trim()
            val cleanedTitle = cleanNoise(rawTitle).ifBlank { rawTitle }
            val cleanedArtist = cleanNoise(rawArtist).ifBlank { rawArtist }
            val query = "$cleanedArtist $cleanedTitle".trim()
            FilenameHints(
                artistHint = cleanedArtist.ifBlank { null },
                titleHint = cleanedTitle.ifBlank { "Unknown Track" },
                cleanSearchQuery = query
            )
        } else {
            val cleaned = cleanNoise(noTrackNum).ifBlank { noTrackNum }
            FilenameHints(
                artistHint = null,
                titleHint = cleaned.ifBlank { "Unknown Track" },
                cleanSearchQuery = cleaned
            )
        }
    }

    /**
     * Implements fallback rules:
     * - Uses true embedded tags when valid.
     * - If Artist is missing: Uses filename artist hint if available, else "Unknown Artist"
     * - If Title is missing: Uses filename title hint if available, else "Unknown Track"
     * - If Album Artist is missing: Uses Artist when available, else "Unknown Artist"
     * - If Album is missing: Album = "Unknown Album"
     */
    fun resolveMetadata(
        rawTitle: String?,
        rawArtist: String?,
        rawAlbumArtist: String?,
        rawAlbum: String?,
        fileNameFallback: String?,
    ): ResolvedMetadata {
        val isEmbeddedTitleValid = !isPlaceholderTitle(rawTitle)
        val isEmbeddedArtistValid = !isPlaceholderArtist(rawArtist)
        val isEmbeddedAlbumArtistValid = !isPlaceholderArtist(rawAlbumArtist)
        val isEmbeddedAlbumValid = !isPlaceholderAlbum(rawAlbum)

        val hints = parseFilenameHints(fileNameFallback)

        val finalTitle = when {
            isEmbeddedTitleValid -> rawTitle!!.trim()
            else -> hints.titleHint
        }

        val finalArtist = when {
            isEmbeddedArtistValid -> rawArtist!!.trim()
            hints.artistHint != null -> hints.artistHint
            else -> "Unknown Artist"
        }

        val finalAlbumArtist = when {
            isEmbeddedAlbumArtistValid -> rawAlbumArtist!!.trim()
            finalArtist != "Unknown Artist" -> finalArtist
            else -> "Unknown Artist"
        }

        val finalAlbum = when {
            isEmbeddedAlbumValid -> rawAlbum!!.trim()
            else -> "Unknown Album"
        }

        return ResolvedMetadata(
            title = finalTitle,
            artist = finalArtist,
            albumArtist = finalAlbumArtist,
            album = finalAlbum,
            isEmbeddedTitleValid = isEmbeddedTitleValid,
            isEmbeddedArtistValid = isEmbeddedArtistValid,
            isEmbeddedAlbumValid = isEmbeddedAlbumValid,
            artistCandidate = hints.artistHint,
            titleCandidate = hints.titleHint,
        )
    }

    data class ResolvedMetadata(
        val title: String,
        val artist: String,
        val albumArtist: String,
        val album: String,
        val isEmbeddedTitleValid: Boolean,
        val isEmbeddedArtistValid: Boolean,
        val isEmbeddedAlbumValid: Boolean,
        val artistCandidate: String?,
        val titleCandidate: String,
    )
}
