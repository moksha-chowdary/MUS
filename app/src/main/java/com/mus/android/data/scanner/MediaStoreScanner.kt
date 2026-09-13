package com.mus.android.data.scanner

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import com.mus.android.data.model.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Scans the dedicated /Muzic/ local storage vault (or a user-selected SAF tree)
 * for audio files and extracts canonical metadata (title, artist, album artist, album,
 * duration, track number, disc number, year, genre, composer, artwork, and codec).
 *
 * Scopes strictly to /Muzic/ and its subdirectories, never scanning DCIM, Downloads,
 * WhatsApp, Pictures, etc.
 */
@Singleton
class MediaStoreScanner @Inject constructor(
    @ApplicationContext private val context: Context
) {
    data class ScanResult(
        val tracks: List<Track>,
        val albums: List<Album>,
        val artists: List<Artist>,
    )

    data class ParsedMetadata(
        val title: String,
        val artist: String,
        val albumArtist: String,
        val albumTitle: String,
        val duration: Long,
        val trackNumber: Int,
        val discNumber: Int,
        val year: Int,
        val genre: String?,
        val composer: String?,
        val artworkUri: String?,
        val mimeType: String,
        val isEmbeddedTitleValid: Boolean,
        val isEmbeddedArtistValid: Boolean,
        val isEmbeddedAlbumValid: Boolean,
        val hasEmbeddedArtwork: Boolean,
        val rawTitle: String?,
        val rawArtist: String?,
        val rawAlbum: String?,
        val rawAlbumArtist: String?,
    )

    suspend fun scan(safTreeUri: Uri? = null): ScanResult = withContext(Dispatchers.IO) {
        if (safTreeUri != null) {
            val safResult = scanSaf(safTreeUri)
            if (safResult.tracks.isNotEmpty()) {
                Log.d(TAG, "SAF scan discovered ${safResult.tracks.size} tracks")
                return@withContext safResult
            }
        }

        // Primary on standard devices: MediaStore scoped strictly to /Muzic/
        val mediaStoreResult = scanMediaStore()
        if (mediaStoreResult.tracks.isNotEmpty()) {
            Log.d(TAG, "MediaStore scan discovered ${mediaStoreResult.tracks.size} tracks")
            return@withContext mediaStoreResult
        }

        // Direct directory fallback: If MediaStore has not indexed /Muzic/ yet
        val muzicDir = getMuzicDirectory()
        if (muzicDir.exists() && muzicDir.isDirectory) {
            val directResult = scanDirectDirectory(muzicDir)
            if (directResult.tracks.isNotEmpty()) {
                Log.d(TAG, "Direct directory scan discovered ${directResult.tracks.size} tracks")
                return@withContext directResult
            }
        }

        mediaStoreResult
    }

    private fun scanSaf(treeUri: Uri): ScanResult {
        val tracks = mutableListOf<Track>()

        try {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)

            // Start traversal with empty relative path (root of the selected Muzic tree)
            traverseSafDirectory(treeUri, childrenUri, tracks, "")
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning SAF tree: $treeUri", e)
        }

        return aggregateScanResult(tracks)
    }

    /**
     * Recursively traverses a SAF directory tree, accumulating tracks.
     * @param currentRelativePath The path from the Muzic root to this directory,
     *        e.g. "" for root, "Drake" for /Muzic/Drake/, "Drake/Singles" for /Muzic/Drake/Singles/
     */
    private fun traverseSafDirectory(treeUri: Uri, childrenUri: Uri, tracks: MutableList<Track>, currentRelativePath: String) {
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )

        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            val modCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

            while (cursor.moveToNext()) {
                val childId = cursor.getString(idCol)
                val displayName = cursor.getString(nameCol) ?: ""
                val mimeType = cursor.getString(mimeCol) ?: ""
                val size = cursor.getLong(sizeCol)
                val lastModified = cursor.getLong(modCol)

                if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                    val subDirUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, childId)
                    // Append this directory's name to the relative path
                    val childRelativePath = if (currentRelativePath.isEmpty()) displayName else "$currentRelativePath/$displayName"
                    traverseSafDirectory(treeUri, subDirUri, tracks, childRelativePath)
                } else if (isSupportedAudio(displayName, mimeType)) {
                    val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                    val parsedMeta = extractMetadataFromSafUri(documentUri, displayName)

                    // Build full relative path: e.g. "Drake/NOKIA.mp3"
                    val fullRelativePath = if (currentRelativePath.isEmpty()) displayName else "$currentRelativePath/$displayName"
                    // Construct a synthetic absolute path for stable ID generation
                    val syntheticPath = "/Muzic/$fullRelativePath"
                    val trackId = MetadataUtils.generateStableTrackId(syntheticPath) ?: MetadataUtils.generateTrackId(documentUri.toString())
                    val albumId = if (!MetadataUtils.isPlaceholderAlbum(parsedMeta.albumTitle) && !MetadataUtils.isPlaceholderArtist(parsedMeta.albumArtist)) {
                        MetadataUtils.generateAlbumId(parsedMeta.albumArtist, parsedMeta.albumTitle)
                    } else {
                        trackId
                    }
                    val effectiveMime = if (parsedMeta.mimeType.isNotBlank()) parsedMeta.mimeType else mimeType
                    val codec = mimeTypeToCodec(effectiveMime, displayName)
                    val language = com.mus.android.data.classifier.LanguageClassifier.classify(
                        title = parsedMeta.title,
                        artist = parsedMeta.artist,
                        album = parsedMeta.albumTitle,
                        path = fullRelativePath,
                        genre = parsedMeta.genre
                    )

                    val hasAnyEmbedded = parsedMeta.isEmbeddedTitleValid || parsedMeta.isEmbeddedArtistValid || parsedMeta.isEmbeddedAlbumValid || parsedMeta.hasEmbeddedArtwork
                    val isComplete = parsedMeta.isEmbeddedTitleValid && parsedMeta.isEmbeddedArtistValid && parsedMeta.isEmbeddedAlbumValid && parsedMeta.hasEmbeddedArtwork

                    val initialStatus = if (isComplete) MetadataStatus.COMPLETE else MetadataStatus.NEEDS_LOOKUP
                    val initialSource = if (hasAnyEmbedded) MetadataSource.EMBEDDED else MetadataSource.EXTERNAL
                    val initialConfidence = if (isComplete) MetadataConfidence.HIGH else MetadataConfidence.LOW

                    tracks.add(
                        Track(
                            id = trackId,
                            title = parsedMeta.title,
                            artist = parsedMeta.artist,
                            albumArtist = parsedMeta.albumArtist,
                            albumId = albumId,
                            albumTitle = parsedMeta.albumTitle,
                            duration = parsedMeta.duration,
                            trackNumber = parsedMeta.trackNumber,
                            discNumber = parsedMeta.discNumber,
                            year = parsedMeta.year,
                            genre = parsedMeta.genre,
                            composer = parsedMeta.composer,
                            uri = documentUri.toString(),
                            artworkUri = parsedMeta.artworkUri,
                            codec = codec,
                            size = size,
                            dateAdded = lastModified,
                            dateModified = lastModified,
                            path = syntheticPath, // Full path with /Muzic/ prefix for stable reconciliation
                            language = language,
                            metadataSource = initialSource,
                            metadataStatus = initialStatus,
                            metadataConfidence = initialConfidence,
                        )
                    )
                }
            }
        }
    }

    private fun extractMetadataFromSafUri(documentUri: Uri, displayNameFallback: String): ParsedMetadata {
        val retriever = MediaMetadataRetriever()
        try {
            context.contentResolver.openFileDescriptor(documentUri, "r")?.use { pfd ->
                retriever.setDataSource(pfd.fileDescriptor)
                return extractMetadataFromRetriever(retriever, displayNameFallback, documentUri.toString())
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaMetadataRetriever failed on SAF URI $documentUri: ${e.message}")
        } finally {
            try { retriever.release() } catch (e: Exception) {}
        }

        // Fallback when retriever cannot open descriptor
        val fallback = MetadataUtils.resolveMetadata(null, null, null, null, displayNameFallback)
        return ParsedMetadata(
            title = fallback.title,
            artist = fallback.artist,
            albumArtist = fallback.albumArtist,
            albumTitle = fallback.album,
            duration = 0L,
            trackNumber = 0,
            discNumber = 1,
            year = 0,
            genre = null,
            composer = null,
            artworkUri = null,
            mimeType = "",
            isEmbeddedTitleValid = false,
            isEmbeddedArtistValid = false,
            isEmbeddedAlbumValid = false,
            hasEmbeddedArtwork = false,
            rawTitle = null,
            rawArtist = null,
            rawAlbum = null,
            rawAlbumArtist = null,
        )
    }

    private fun scanDirectDirectory(dir: File): ScanResult {
        val audioFiles = mutableListOf<File>()
        collectAudioFiles(dir, audioFiles)

        val tracks = mutableListOf<Track>()

        for (file in audioFiles) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                val parsedMeta = extractMetadataFromRetriever(retriever, file.name, file.absolutePath)

                val trackId = MetadataUtils.generateStableTrackId(file.absolutePath) ?: MetadataUtils.generateTrackId(file.absolutePath)
                val albumId = if (!MetadataUtils.isPlaceholderAlbum(parsedMeta.albumTitle) && !MetadataUtils.isPlaceholderArtist(parsedMeta.albumArtist)) {
                    MetadataUtils.generateAlbumId(parsedMeta.albumArtist, parsedMeta.albumTitle)
                } else {
                    trackId
                }
                val codec = mimeTypeToCodec(parsedMeta.mimeType, file.absolutePath)
                val language = com.mus.android.data.classifier.LanguageClassifier.classify(
                    title = parsedMeta.title,
                    artist = parsedMeta.artist,
                    album = parsedMeta.albumTitle,
                    path = file.absolutePath,
                    genre = parsedMeta.genre
                )

                val hasAnyEmbedded = parsedMeta.isEmbeddedTitleValid || parsedMeta.isEmbeddedArtistValid || parsedMeta.isEmbeddedAlbumValid || parsedMeta.hasEmbeddedArtwork
                val isComplete = parsedMeta.isEmbeddedTitleValid && parsedMeta.isEmbeddedArtistValid && parsedMeta.isEmbeddedAlbumValid && parsedMeta.hasEmbeddedArtwork

                val initialStatus = if (isComplete) MetadataStatus.COMPLETE else MetadataStatus.NEEDS_LOOKUP
                val initialSource = if (hasAnyEmbedded) MetadataSource.EMBEDDED else MetadataSource.EXTERNAL
                val initialConfidence = if (isComplete) MetadataConfidence.HIGH else MetadataConfidence.LOW

                tracks.add(
                    Track(
                        id = trackId,
                        title = parsedMeta.title,
                        artist = parsedMeta.artist,
                        albumArtist = parsedMeta.albumArtist,
                        albumId = albumId,
                        albumTitle = parsedMeta.albumTitle,
                        duration = parsedMeta.duration,
                        trackNumber = parsedMeta.trackNumber,
                        discNumber = parsedMeta.discNumber,
                        year = parsedMeta.year,
                        genre = parsedMeta.genre,
                        composer = parsedMeta.composer,
                        uri = Uri.fromFile(file).toString(),
                        artworkUri = parsedMeta.artworkUri,
                        codec = codec,
                        size = file.length(),
                        dateAdded = file.lastModified(),
                        dateModified = file.lastModified(),
                        path = file.absolutePath,
                        language = language,
                        metadataSource = initialSource,
                        metadataStatus = initialStatus,
                        metadataConfidence = initialConfidence,
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed reading file metadata: ${file.absolutePath}", e)
            } finally {
                try { retriever.release() } catch (e: Exception) {}
            }
        }

        return aggregateScanResult(tracks)
    }

    private fun scanMediaStore(): ScanResult {
        val tracks = mutableListOf<Track>()

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val projection = mutableListOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DISPLAY_NAME,
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Audio.Media.RELATIVE_PATH)
            }
        }.toTypedArray()

        // Broad audio matching within /Muzic/ folder without restrictive IS_MUSIC or duration filters
        val muzicPathClause = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "(LOWER(${MediaStore.Audio.Media.RELATIVE_PATH}) LIKE 'muzic/%' OR LOWER(${MediaStore.Audio.Media.RELATIVE_PATH}) = 'muzic/' OR LOWER(${MediaStore.Audio.Media.RELATIVE_PATH}) = 'muzic' OR LOWER(${MediaStore.Audio.Media.DATA}) LIKE '%/muzic/%')"
        } else {
            "(LOWER(${MediaStore.Audio.Media.DATA}) LIKE '%/muzic/%')"
        }
        val selection = muzicPathClause
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        context.contentResolver.query(
            collection, projection, selection, null, sortOrder
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val trackCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val yearCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            val dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val dateModifiedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
            val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
            val displayNameCol = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
            val relPathCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
            } else -1

            while (cursor.moveToNext()) {
                val mediaStoreId = cursor.getLong(idCol)
                val rawTitle = cursor.getString(titleCol)
                val rawArtist = cursor.getString(artistCol)
                val rawAlbum = cursor.getString(albumCol)
                val mediaStoreAlbumId = cursor.getLong(albumIdCol)
                val duration = cursor.getLong(durationCol)
                val trackNumber = cursor.getInt(trackCol)
                val year = cursor.getInt(yearCol)
                val size = cursor.getLong(sizeCol)
                val mimeType = cursor.getString(mimeCol) ?: ""
                val dateAdded = cursor.getLong(dateAddedCol) * 1000
                val dateModified = cursor.getLong(dateModifiedCol) * 1000
                val rawDataPath = if (dataCol >= 0) cursor.getString(dataCol) else null
                val displayName = if (displayNameCol >= 0) cursor.getString(displayNameCol) else null
                val relPath = if (relPathCol >= 0) cursor.getString(relPathCol) else null

                val filePath = rawDataPath ?: if (!relPath.isNullOrBlank() && !displayName.isNullOrBlank()) {
                    "$relPath$displayName"
                } else null
                val fileNameFallback = displayName ?: filePath?.substringAfterLast("/")

                val contentUri = ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mediaStoreId
                )

                // Supplement with retriever to extract rich tags (Album Artist, Disc Number, Genre, Composer)
                var rawAlbumArtist: String? = null
                var discNumber = 1
                var genre: String? = null
                var composer: String? = null
                var artworkUri: String? = null

                val effectiveRawAlbum = if (!MetadataUtils.isPlaceholderAlbum(rawAlbum)) rawAlbum else null
                val effectiveRawArtist = if (!MetadataUtils.isPlaceholderArtist(rawArtist)) rawArtist else null
                val effectiveRawTitle = if (!MetadataUtils.isPlaceholderTitle(rawTitle)) rawTitle else null

                val retriever = MediaMetadataRetriever()
                try {
                    context.contentResolver.openFileDescriptor(contentUri, "r")?.use { pfd ->
                        retriever.setDataSource(pfd.fileDescriptor)
                        rawAlbumArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                        val discStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)
                        discNumber = discStr?.substringBefore("/")?.trim()?.toIntOrNull() ?: 1
                        genre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)?.trim()
                        composer = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COMPOSER)?.trim()

                        val pic = retriever.embeddedPicture
                        if (pic != null) {
                            val stableTrackId = MetadataUtils.generateStableTrackId(filePath) ?: mediaStoreId
                            val artKey = MetadataUtils.generateEmbeddedArtworkKey(stableTrackId)
                            val artDir = File(context.filesDir, "artwork")
                            if (!artDir.exists()) artDir.mkdirs()
                            val artFile = File(artDir, "art_$artKey.jpg")
                            if (!artFile.exists() || artFile.length() == 0L) {
                                try {
                                    FileOutputStream(artFile).use { it.write(pic) }
                                } catch (e: Exception) {
                                    Log.w(TAG, "Failed writing cached embedded artwork for key $artKey", e)
                                }
                            }
                            if (artFile.exists() && artFile.length() > 0L) {
                                artworkUri = Uri.fromFile(artFile).toString()
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Ignore retriever failures on MediaStore query
                } finally {
                    try { retriever.release() } catch (e: Exception) {}
                }

                val resolved = MetadataUtils.resolveMetadata(
                    rawTitle = effectiveRawTitle,
                    rawArtist = effectiveRawArtist,
                    rawAlbumArtist = rawAlbumArtist,
                    rawAlbum = effectiveRawAlbum,
                    fileNameFallback = fileNameFallback
                )

                val canonicalAlbumId = if (!MetadataUtils.isPlaceholderAlbum(resolved.album) && !MetadataUtils.isPlaceholderArtist(resolved.albumArtist)) {
                    MetadataUtils.generateAlbumId(resolved.albumArtist, resolved.album)
                } else {
                    mediaStoreId
                }
                val codec = mimeTypeToCodec(mimeType, filePath)
                val language = com.mus.android.data.classifier.LanguageClassifier.classify(
                    title = resolved.title,
                    artist = resolved.artist,
                    album = resolved.album,
                    path = filePath,
                    genre = genre
                )

                val hasAnyEmbedded = resolved.isEmbeddedTitleValid || resolved.isEmbeddedArtistValid || resolved.isEmbeddedAlbumValid || (artworkUri != null)
                val isComplete = resolved.isEmbeddedTitleValid && resolved.isEmbeddedArtistValid && resolved.isEmbeddedAlbumValid && (artworkUri != null)

                val initialStatus = if (isComplete) MetadataStatus.COMPLETE else MetadataStatus.NEEDS_LOOKUP
                val initialSource = if (hasAnyEmbedded) MetadataSource.EMBEDDED else MetadataSource.EXTERNAL
                val initialConfidence = if (isComplete) MetadataConfidence.HIGH else MetadataConfidence.LOW

                val stableId = MetadataUtils.generateStableTrackId(filePath) ?: mediaStoreId
                tracks.add(
                    Track(
                        id = stableId,
                        title = resolved.title,
                        artist = resolved.artist,
                        albumArtist = resolved.albumArtist,
                        albumId = canonicalAlbumId,
                        albumTitle = resolved.album,
                        duration = duration,
                        trackNumber = trackNumber,
                        discNumber = discNumber,
                        year = year,
                        genre = genre,
                        composer = composer,
                        uri = contentUri.toString(),
                        artworkUri = artworkUri,
                        codec = codec,
                        size = size,
                        dateAdded = dateAdded,
                        dateModified = dateModified,
                        path = filePath,
                        language = language,
                        metadataSource = initialSource,
                        metadataStatus = initialStatus,
                        metadataConfidence = initialConfidence,
                    )
                )
            }
        }

        return aggregateScanResult(tracks)
    }

    private fun extractMetadataFromRetriever(
        retriever: MediaMetadataRetriever,
        fileNameFallback: String,
        pathOrUri: String,
    ): ParsedMetadata {
        val rawTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
        val rawArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
        val rawAlbumArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
        val rawAlbum = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
        val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
        val trackNumStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
        val discNumStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)
        val yearStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)
            ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
        val genre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)?.trim()
        val composer = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COMPOSER)?.trim()
        val mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) ?: ""

        val resolved = MetadataUtils.resolveMetadata(
            rawTitle = rawTitle,
            rawArtist = rawArtist,
            rawAlbumArtist = rawAlbumArtist,
            rawAlbum = rawAlbum,
            fileNameFallback = fileNameFallback
        )

        val duration = durationStr?.toLongOrNull() ?: 0L
        val trackNumber = trackNumStr?.substringBefore("/")?.trim()?.toIntOrNull() ?: 0
        val discNumber = discNumStr?.substringBefore("/")?.trim()?.toIntOrNull() ?: 1
        val year = yearStr?.let { Regex("""\b(19\d\d|20\d\d)\b""").find(it)?.value?.toIntOrNull() } ?: 0

        // Extract embedded artwork using isolated per-track key
        var artworkUri: String? = null
        val picture = retriever.embeddedPicture
        if (picture != null) {
            val stableTrackId = MetadataUtils.generateStableTrackId(pathOrUri) ?: MetadataUtils.generateDeterministicId("track:$pathOrUri")
            val artKey = MetadataUtils.generateEmbeddedArtworkKey(stableTrackId)
            val artDir = File(context.filesDir, "artwork")
            if (!artDir.exists()) artDir.mkdirs()
            val artFile = File(artDir, "art_$artKey.jpg")
            if (!artFile.exists() || artFile.length() == 0L) {
                try {
                    FileOutputStream(artFile).use { it.write(picture) }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed writing cached embedded artwork for key $artKey", e)
                }
            }
            if (artFile.exists() && artFile.length() > 0L) {
                artworkUri = Uri.fromFile(artFile).toString()
            }
        }

        // Diagnostic trace logging for points 1-13
        Log.i("DIAG_METADATA", "=== TRACK SCAN DIAGNOSTICS ===")
        Log.i("DIAG_METADATA", "[Point 1] Exact file path discovered: $pathOrUri")
        Log.i("DIAG_METADATA", "[Point 2] MIME type: $mimeType")
        Log.i("DIAG_METADATA", "[Point 3] URI: $pathOrUri")
        Log.i("DIAG_METADATA", "[Point 4] Embedded title: $rawTitle")
        Log.i("DIAG_METADATA", "[Point 5] Embedded artist: $rawArtist")
        Log.i("DIAG_METADATA", "[Point 6] Embedded album: $rawAlbum")
        Log.i("DIAG_METADATA", "[Point 7] Embedded album artist: $rawAlbumArtist")
        Log.i("DIAG_METADATA", "[Point 8] Embedded year: $yearStr")
        Log.i("DIAG_METADATA", "[Point 9] Embedded genre: $genre")
        Log.i("DIAG_METADATA", "[Point 10] Embedded artwork present: ${picture != null}")
        Log.i("DIAG_METADATA", "[Point 11] Final title after scanner: ${resolved.title}")
        Log.i("DIAG_METADATA", "[Point 12] Final artist after scanner: ${resolved.artist}")
        Log.i("DIAG_METADATA", "[Point 13] Final album after scanner: ${resolved.album}")

        return ParsedMetadata(
            title = resolved.title,
            artist = resolved.artist,
            albumArtist = resolved.albumArtist,
            albumTitle = resolved.album,
            duration = duration,
            trackNumber = trackNumber,
            discNumber = discNumber,
            year = year,
            genre = genre,
            composer = composer,
            artworkUri = artworkUri,
            mimeType = mimeType,
            isEmbeddedTitleValid = resolved.isEmbeddedTitleValid,
            isEmbeddedArtistValid = resolved.isEmbeddedArtistValid,
            isEmbeddedAlbumValid = resolved.isEmbeddedAlbumValid,
            hasEmbeddedArtwork = (picture != null),
            rawTitle = rawTitle,
            rawArtist = rawArtist,
            rawAlbum = rawAlbum,
            rawAlbumArtist = rawAlbumArtist,
        )
    }

    fun aggregateScanResult(tracks: List<Track>): ScanResult {
        val albumsMap = mutableMapOf<Long, Album>()
        val artistsMap = mutableMapOf<String, Artist>()

        for (track in tracks) {
            val albumId = track.albumId

            // Do not create aggregate Album entities for placeholder albums (e.g. language folders, unknown album)
            if (!MetadataUtils.isPlaceholderAlbum(track.albumTitle)) {
                // Album is grouped primarily by Album Artist, falling back to Track Artist
                val effectiveArtist = if (track.albumArtist.isNotBlank() && !MetadataUtils.isPlaceholderArtist(track.albumArtist)) {
                    track.albumArtist
                } else if (track.artist.isNotBlank() && !MetadataUtils.isPlaceholderArtist(track.artist)) {
                    track.artist
                } else {
                    "Unknown Artist"
                }

                if (albumId !in albumsMap) {
                    albumsMap[albumId] = Album(
                        id = albumId,
                        title = track.albumTitle,
                        artist = effectiveArtist,
                        artworkUri = track.artworkUri,
                        year = track.year,
                        trackCount = 0,
                        totalDuration = 0,
                    )
                }
                albumsMap[albumId] = albumsMap[albumId]!!.let { existing ->
                    val bestArt = when {
                        com.mus.android.data.enrichment.artwork.ArtworkStorage.isArtworkValid(existing.artworkUri) -> existing.artworkUri
                        com.mus.android.data.enrichment.artwork.ArtworkStorage.isArtworkValid(track.artworkUri) -> track.artworkUri
                        else -> existing.artworkUri ?: track.artworkUri
                    }
                    existing.copy(
                        artist = if (existing.artist.isNotBlank() && !MetadataUtils.isPlaceholderArtist(existing.artist)) existing.artist else effectiveArtist,
                        trackCount = existing.trackCount + 1,
                        totalDuration = existing.totalDuration + track.duration,
                        artworkUri = bestArt,
                        year = if (existing.year == 0 && track.year > 0) track.year else existing.year
                    )
                }
            }

            // Artist aggregation: record primary album artist
            val albumArtistKey = track.albumArtist.trim().lowercase()
            if (albumArtistKey.isNotBlank() && albumArtistKey !in artistsMap) {
                artistsMap[albumArtistKey] = Artist(
                    id = MetadataUtils.generateArtistId(track.albumArtist),
                    name = track.albumArtist,
                    artworkUri = track.artworkUri,
                    albumCount = 0,
                    trackCount = 0,
                )
            }

            // Also record individual track artist if distinct (e.g. featured artist)
            val trackArtistKey = track.artist.trim().lowercase()
            if (trackArtistKey.isNotBlank() && trackArtistKey != albumArtistKey && trackArtistKey !in artistsMap) {
                artistsMap[trackArtistKey] = Artist(
                    id = MetadataUtils.generateArtistId(track.artist),
                    name = track.artist,
                    artworkUri = track.artworkUri,
                    albumCount = 0,
                    trackCount = 0,
                )
            }
        }

        // Count unique albums and total songs per artist
        val finalArtists = artistsMap.map { (_, artist) ->
            val albumCount = albumsMap.values.count { it.artist.equals(artist.name, ignoreCase = true) }
            val trackCount = tracks.count {
                it.artist.equals(artist.name, ignoreCase = true) || it.albumArtist.equals(artist.name, ignoreCase = true)
            }
            val artworkUri = artist.artworkUri
                ?: albumsMap.values.firstOrNull { it.artist.equals(artist.name, ignoreCase = true) }?.artworkUri
                ?: tracks.firstOrNull { it.artist.equals(artist.name, ignoreCase = true) || it.albumArtist.equals(artist.name, ignoreCase = true) }?.artworkUri

            artist.copy(
                albumCount = albumCount,
                trackCount = trackCount,
                artworkUri = artworkUri,
            )
        }

        return ScanResult(
            tracks = tracks,
            albums = albumsMap.values.toList(),
            artists = finalArtists,
        )
    }

    private fun collectAudioFiles(dir: File, list: MutableList<File>) {
        if (!dir.exists() || !dir.isDirectory) return
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (child.isDirectory) {
                collectAudioFiles(child, list)
            } else if (isSupportedAudio(child.name, "")) {
                list.add(child)
            }
        }
    }

    private fun isSupportedAudio(name: String, mimeType: String): Boolean {
        val ext = name.substringAfterLast(".", "").lowercase()
        return ext in SUPPORTED_AUDIO_EXTENSIONS || mimeType.startsWith("audio/")
    }

    private fun mimeTypeToCodec(mimeType: String, path: String? = null): String {
        val ext = path?.substringAfterLast(".", "")?.lowercase() ?: ""
        return when {
            mimeType.contains("flac") || ext == "flac" -> "FLAC"
            mimeType.contains("alac") || ext == "alac" -> "ALAC"
            mimeType.contains("wav") || mimeType.contains("wave") || ext == "wav" -> "WAV"
            mimeType.contains("opus") || ext == "opus" -> "Opus"
            mimeType.contains("ogg") || ext == "ogg" -> "OGG"
            mimeType.contains("mp3") || mimeType.contains("mpeg") || ext == "mp3" -> "MP3"
            mimeType.contains("aac") -> "AAC"
            mimeType.contains("mp4a") || mimeType.contains("m4a") || ext == "m4a" -> {
                if (mimeType.contains("alac")) "ALAC" else "AAC"
            }
            else -> mimeType.substringAfterLast("/").uppercase()
        }
    }

    fun getMuzicDirectory(): File {
        return File(Environment.getExternalStorageDirectory(), "Muzic")
    }

    fun doesMuzicDirectoryExist(): Boolean {
        return try {
            getMuzicDirectory().exists()
        } catch (e: Exception) {
            false
        }
    }

    fun createMuzicDirectory(): Boolean {
        return try {
            val dir = getMuzicDirectory()
            if (!dir.exists()) dir.mkdirs() else true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Discovers direct child directories under the Muzic root (via direct filesystem
     * traversal and/or SAF tree query).
     */
    fun discoverDirectFolders(targetUri: Uri? = null): Set<String> {
        val folders = mutableSetOf<String>()
        // 1. Direct filesystem check
        try {
            val muzicDir = getMuzicDirectory()
            if (muzicDir.exists() && muzicDir.isDirectory) {
                muzicDir.listFiles { file -> file.isDirectory }?.forEach { sub ->
                    val name = sub.name.trim()
                    if (name.isNotBlank() && !name.startsWith(".")) {
                        folders.add(name)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to inspect direct folders from Muzic directory", e)
        }

        // 2. SAF tree query if a custom SAF URI is provided
        if (targetUri != null) {
            try {
                val docId = DocumentsContract.getTreeDocumentId(targetUri)
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(targetUri, docId)
                val projection = arrayOf(
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                )
                context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                    val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    while (cursor.moveToNext()) {
                        val mime = cursor.getString(mimeCol) ?: ""
                        val name = cursor.getString(nameCol) ?: ""
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR && name.isNotBlank() && !name.startsWith(".")) {
                            folders.add(name.trim())
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to query direct folders from SAF tree: $targetUri", e)
            }
        }

        return folders
    }

    companion object {
        private const val TAG = "MediaStoreScanner"
        val SUPPORTED_AUDIO_EXTENSIONS = setOf("mp3", "flac", "m4a", "aac", "wav", "ogg", "opus", "alac")
    }
}
