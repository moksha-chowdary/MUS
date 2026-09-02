package com.mus.android.data.scanner

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.mus.android.data.model.Album
import com.mus.android.data.model.Artist
import com.mus.android.data.model.Track
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Scans the device's MediaStore for all audio files and returns
 * Track, Album, and Artist objects.
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

    suspend fun scan(): ScanResult = withContext(Dispatchers.IO) {
        val tracks = mutableListOf<Track>()
        val albumsMap = mutableMapOf<Long, Album>()
        val artistsMap = mutableMapOf<String, Artist>()

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
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
        )

        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} > 5000"
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

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val title = cursor.getString(titleCol) ?: "Unknown"
                val artist = cursor.getString(artistCol) ?: "Unknown Artist"
                val albumTitle = cursor.getString(albumCol) ?: "Unknown Album"
                val albumId = cursor.getLong(albumIdCol)
                val duration = cursor.getLong(durationCol)
                val trackNumber = cursor.getInt(trackCol)
                val year = cursor.getInt(yearCol)
                val size = cursor.getLong(sizeCol)
                val mimeType = cursor.getString(mimeCol) ?: ""
                val dateAdded = cursor.getLong(dateAddedCol) * 1000
                val dateModified = cursor.getLong(dateModifiedCol) * 1000
                val filePath = if (dataCol >= 0) cursor.getString(dataCol) else null

                val contentUri = ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id
                )

                val artworkUri = ContentUris.withAppendedId(
                    Uri.parse("content://media/external/audio/albumart"), albumId
                ).toString()

                val codec = mimeTypeToCodec(mimeType)
                val bitrate = if (duration > 0) ((size * 8) / (duration / 1000)).toInt() else 0
                val language = com.mus.android.data.classifier.LanguageClassifier.classify(
                    title = title,
                    artist = artist,
                    album = albumTitle,
                    path = filePath
                )

                tracks.add(
                    Track(
                        id = id,
                        title = title,
                        artist = artist,
                        albumId = albumId,
                        albumTitle = albumTitle,
                        duration = duration,
                        trackNumber = trackNumber,
                        year = year,
                        uri = contentUri.toString(),
                        artworkUri = artworkUri,
                        codec = codec,
                        bitrate = bitrate / 1000, // kbps
                        size = size,
                        dateAdded = dateAdded,
                        dateModified = dateModified,
                        path = filePath,
                        language = language,
                    )
                )

                // Aggregate albums
                if (albumId !in albumsMap) {
                    albumsMap[albumId] = Album(
                        id = albumId,
                        title = albumTitle,
                        artist = artist,
                        artworkUri = artworkUri,
                        year = year,
                        trackCount = 0,
                        totalDuration = 0,
                    )
                }
                albumsMap[albumId] = albumsMap[albumId]!!.let {
                    it.copy(trackCount = it.trackCount + 1, totalDuration = it.totalDuration + duration)
                }

                // Aggregate artists
                val artistKey = artist.lowercase()
                if (artistKey !in artistsMap) {
                    artistsMap[artistKey] = Artist(
                        id = artistKey.hashCode().toLong(),
                        name = artist,
                        artworkUri = artworkUri, // use first track's album art
                        albumCount = 0,
                        trackCount = 0,
                    )
                }
                artistsMap[artistKey] = artistsMap[artistKey]!!.let {
                    it.copy(trackCount = it.trackCount + 1)
                }
            }
        }

        // Count unique albums per artist
        val albumsByArtist = tracks.groupBy { it.artist.lowercase() }
            .mapValues { (_, tracks) -> tracks.map { it.albumId }.distinct().size }

        val artists = artistsMap.map { (key, artist) ->
            artist.copy(albumCount = albumsByArtist[key] ?: 0)
        }

        ScanResult(
            tracks = tracks,
            albums = albumsMap.values.toList(),
            artists = artists,
        )
    }

    private fun mimeTypeToCodec(mimeType: String): String = when {
        mimeType.contains("flac") -> "FLAC"
        mimeType.contains("mp4a") || mimeType.contains("m4a") || mimeType.contains("mp4") -> "AAC"
        mimeType.contains("mpeg") || mimeType.contains("mp3") -> "MP3"
        mimeType.contains("ogg") || mimeType.contains("opus") -> "Opus"
        mimeType.contains("wav") || mimeType.contains("wave") -> "WAV"
        mimeType.contains("alac") -> "ALAC"
        mimeType.contains("aac") -> "AAC"
        else -> mimeType.substringAfterLast("/").uppercase()
    }
}
