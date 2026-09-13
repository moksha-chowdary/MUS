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

    private val PLACEHOLDER_ALBUMS = setOf(
        "unknown album", "<unknown>", "unknown", "muzic", "music", "audio", "songs",
        "downloads", "download", "soundtrack", "soundtracks", "ost", "single", "singles",
        "various", "various artists",
        // Top-level /Muzic/ language and genre folder names must never be treated as album titles
        "english", "telugu", "hindi", "tamil", "kannada", "malayalam", "punjabi",
        "bhojpuri", "bengali", "marathi", "gujarati", "urdu", "odia", "assamese",
        "pop", "rock", "classical", "hip-hop", "hip hop", "rap", "bollywood", "tollywood", "kollywood"
    )

    private val PLACEHOLDER_ARTISTS = setOf(
        "unknown artist", "<unknown>", "unknown", "various artists", "various", "artist",
        "track artist", "album artist", "composer", "singer", "unknown composer"
    )

    private val PLACEHOLDER_TITLES = setOf(
        "unknown track", "<unknown>", "unknown", "track 01", "track 02", "track 03",
        "track 04", "track 05", "track 06", "track 07", "track 08", "track 09", "track 10",
        "track", "audio", "music", "song", "untitled", "audiotrack", "audio track"
    )

    fun isPlaceholderArtist(artist: String?): Boolean {
        if (artist.isNullOrBlank()) return true
        val norm = normalizeString(artist)
        return norm in PLACEHOLDER_ARTISTS || norm.startsWith("unknown")
    }

    fun isPlaceholderAlbum(album: String?): Boolean {
        if (album.isNullOrBlank()) return true
        val norm = normalizeString(album)
        return norm in PLACEHOLDER_ALBUMS ||
                norm.startsWith("unknown") ||
                norm == "track" ||
                norm.matches(Regex("""album\s*\d*"""))
    }

    fun isPlaceholderTitle(title: String?): Boolean {
        if (title.isNullOrBlank()) return true
        val norm = normalizeString(title)
        return norm in PLACEHOLDER_TITLES ||
                norm.startsWith("unknown") ||
                norm.matches(Regex("""track\s*\d+""")) ||
                norm.matches(Regex("""audio\s*\d+"""))
    }

    /**
     * Dedicated, isolated artwork key for embedded artwork.
     * Prevents remote iTunes downloads from overwriting embedded file artwork on disk.
     */
    fun generateEmbeddedArtworkKey(trackId: Long): String {
        return "embedded_$trackId"
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

    // ── Robust Comparison Normalization & Identity Gates ─────

    val CRITICAL_VERSION_DESCRIPTORS = setOf(
        "remix", "live", "acoustic", "instrumental", "cover", "re-recording",
        "rerecording", "re-recorded", "demo", "choir", "slowed", "reverb",
        "orchestral", "unplugged", "club mix", "radio edit"
    )

    private val ALL_VERSION_DESCRIPTORS = CRITICAL_VERSION_DESCRIPTORS + setOf(
        "remaster", "remastered", "extended", "extended mix", "deluxe", "bonus"
    )

    /**
     * Normalizes text strictly for comparison (never alters user display metadata).
     * Decomposes Unicode, strips accents, removes punctuation, collapses whitespace,
     * and normalizes featuring and noise annotations.
     */
    fun normalizeForComparison(input: String?): String {
        if (input.isNullOrBlank()) return ""
        val decomposed = java.text.Normalizer.normalize(input, java.text.Normalizer.Form.NFD)
        val stripped = decomposed.replace(Regex("""\p{M}"""), "")
        var text = stripped.lowercase()
        // Standardize quotes and apostrophes
        text = text.replace(Regex("""[’‘`´"“”]"""), "'")
        // Standardize dashes
        text = text.replace(Regex("""[\u2013\u2014\u2015\u2212]"""), "-")
        // Strip featuring notation
        text = text.replace(Regex("""(?i)\s*[\(\[\{]\s*(?:feat\.?|ft\.?)\s+[^)\]\}]+[)\]\}]"""), " ")
        text = text.replace(Regex("""(?i)\s+feat\.?\s+.*$"""), " ")
        text = text.replace(Regex("""(?i)\s+ft\.?\s+.*$"""), " ")
        // Strip video/audio trailer noise
        text = text.replace(Regex("""(?i)\s*[\(\[\{]\s*(?:official\s*(?:music\s*)?video|official\s*audio|video|lyric\s*video|lyrics?|visualizer|audio|hd|4k)\s*[)\]\}]"""), " ")
        // Replace non-alphanumeric punctuation with spaces
        text = text.replace(Regex("""[^\p{L}\p{N}\s]"""), " ")
        return text.replace(Regex("""\s+"""), " ").trim()
    }

    /**
     * Extracts version descriptors (remix, live, acoustic, etc.) present in a title.
     */
    fun extractVersionDescriptors(title: String): Set<String> {
        val norm = normalizeForComparison(title)
        val found = mutableSetOf<String>()
        for (vd in ALL_VERSION_DESCRIPTORS) {
            if (Regex("""\b${Regex.escape(vd)}\b""").containsMatchIn(norm)) {
                val canonical = when (vd) {
                    "remastered" -> "remaster"
                    "rerecording", "re-recorded" -> "re-recording"
                    else -> vd
                }
                found.add(canonical)
            }
        }
        return found
    }

    /**
     * Validates that the remote candidate does not conflict with the local track version.
     * E.g. prevents matching an original song to a Remix, Live, Acoustic, or Instrumental.
     */
    fun areVersionsCompatible(localTitle: String, candidateTitle: String): Boolean {
        val localVersions = extractVersionDescriptors(localTitle)
        val candidateVersions = extractVersionDescriptors(candidateTitle)

        for (cd in CRITICAL_VERSION_DESCRIPTORS) {
            val canonical = when (cd) {
                "rerecording", "re-recorded" -> "re-recording"
                else -> cd
            }
            if (canonical in candidateVersions && canonical !in localVersions) {
                return false
            }
            if (canonical in localVersions && canonical !in candidateVersions) {
                return false
            }
        }
        return true
    }

    /**
     * Strict title matcher for the Identity Gate.
     * Returns true only when strong identity evidence is established.
     */
    fun isTitleMatch(localTitle: String, candidateTitle: String): Boolean {
        val normLocal = normalizeForComparison(localTitle)
        val normCand = normalizeForComparison(candidateTitle)
        if (normLocal.isBlank() || normCand.isBlank()) return false
        if (normLocal == normCand) return true

        // Strip "from <movie>" or "from <soundtrack>" suffixes common in Indian music
        val localBase = normLocal.replace(Regex("""\bfrom\s+.*$"""), "").trim()
        val candBase = normCand.replace(Regex("""\bfrom\s+.*$"""), "").trim()
        if (localBase.isNotBlank() && candBase.isNotBlank() && localBase == candBase) return true

        // Match on word boundaries (exact substring of whole words)
        val localStr = " $normLocal "
        val candStr = " $normCand "
        val localWords = normLocal.split(" ").filter { it.isNotBlank() }
        val candWords = normCand.split(" ").filter { it.isNotBlank() }

        if (localWords.size >= 2 && candStr.contains(localStr)) return true
        if (candWords.size >= 2 && localStr.contains(candStr)) return true

        // If local is a single distinct word (e.g. "FE!N"), require whole-word boundary
        if (localWords.size == 1 && localWords[0].length >= 3 && candStr.contains(localStr)) {
            return true
        }

        return false
    }

    /**
     * Splits an artist string into constituent artist names (handling multiple singers/composers).
     */
    fun splitArtistTokens(artist: String): List<String> {
        return artist.split(Regex("""(?i)\s*(?:,|&|\band\b|\bfeat\.?\b|\bft\.?\b|\bwith\b|\bx\b|/|;)\s*"""))
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    /**
     * Strict artist matcher for the Identity Gate.
     * Handles soundtrack composers, multiple singers, and collection artists.
     */
    fun isArtistMatch(
        localArtist: String,
        candidateArtist: String,
        candidateAlbumArtist: String? = null,
        localComposer: String? = null,
    ): Boolean {
        val normLocal = normalizeForComparison(localArtist)
        val normCand = normalizeForComparison(candidateArtist)
        val normCandAlbumArtist = candidateAlbumArtist?.let { normalizeForComparison(it) } ?: ""
        val normComposer = localComposer?.let { normalizeForComparison(it) } ?: ""

        if (normLocal.isBlank()) return false
        if (normLocal == normCand) return true
        if (normCandAlbumArtist.isNotBlank() && normLocal == normCandAlbumArtist) return true
        if (normComposer.isNotBlank() && (normComposer == normCand || normComposer == normCandAlbumArtist)) return true

        val localTokens = splitArtistTokens(localArtist)
        val candTokens = splitArtistTokens(candidateArtist) +
                (if (candidateAlbumArtist != null) splitArtistTokens(candidateAlbumArtist) else emptyList()) +
                (if (localComposer != null) splitArtistTokens(localComposer) else emptyList())

        // Compare individual artist tokens strictly (preventing single-word band collisions)
        for (lt in localTokens) {
            val normLt = normalizeForComparison(lt)
            if (normLt.length < 3) continue
            for (ct in candTokens) {
                val normCt = normalizeForComparison(ct)
                if (normCt.length < 3) continue
                if (normLt == normCt) {
                    return true
                }
            }
        }

        return false
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

    /**
     * Returns a normalized, lowercased Muzic-relative path for reconciliation lookups.
     * Used for case-insensitive matching when migrating from old unstable IDs.
     */
    fun normalizeForLookup(pathOrUri: String?): String? {
        if (pathOrUri.isNullOrBlank()) return null
        val relative = extractMuzicRelativePath(pathOrUri) ?: return null
        return relative.lowercase().replace('\\', '/').trim()
    }

    /**
     * Extracts the album-level folder path from a track's path.
     * For /Muzic/English/AlbumName/song.mp3 → "English/AlbumName"
     * For /Muzic/English/song.mp3 → "English"
     * For /Muzic/song.mp3 → null (root, no album folder)
     * Used for album grouping when embedded metadata is insufficient.
     */
    fun extractAlbumFolderPath(pathOrUri: String?): String? {
        if (pathOrUri.isNullOrBlank()) return null
        val relative = extractMuzicRelativePath(pathOrUri) ?: return null
        val clean = relative.trim().removePrefix("/")
        val lastSlash = clean.lastIndexOf('/')
        if (lastSlash <= 0) return null // File at root or no directory structure
        return clean.substring(0, lastSlash)
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
