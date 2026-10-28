package com.mus.android.data.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import com.mus.android.MainActivity
import com.mus.android.data.db.DownloadQueueDao
import com.mus.android.data.db.DownloadedTrackDao
import com.mus.android.data.db.DownloadedTrackEntity
import com.mus.android.data.model.DownloadStatus
import com.mus.android.data.music.InnerTubeMusicApi
import com.mus.android.data.music.TextMatch
import com.mus.android.data.music.YouTubeAudioStream
import com.mus.android.data.music.YouTubeMusicTrack
import com.mus.android.data.repository.UserPreferencesRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Live progress for one download slot. */
data class DownloadProgress(
    val key: String,
    val title: String,
    val artist: String,
    val progressPercent: Int = 0,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = -1L,
    val formatBadge: String = "AUDIO",
    val isWaitingForConnection: Boolean = false,
    val isFinished: Boolean = false,
    val error: String? = null,
)

private data class DownloadTransfer(
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val contentType: String,
)

private data class DownloadRange(val start: Long, val endInclusive: Long) {
    val length: Long get() = endInclusive - start + 1L
}

private data class ParsedContentRange(
    val start: Long,
    val endInclusive: Long,
    val total: Long,
)

private class DownloadHttpException(val statusCode: Int) :
    IOException("HTTP $statusCode downloading track")

private class DownloadProtocolException(message: String) : IOException(message)

private class DownloadInterruptedException(message: String, cause: Throwable? = null) :
    IOException(message, cause)

/**
 * YouTube Music download pipeline, ported from LastWave's TrackDownloadManager.
 *
 * Flow: resolve best match → resolve stream (Opus preferred for lossless
 * remux) → parallel byte-range transfer → lossless remux (WebM→Ogg Opus,
 * FLAC-in-MP4→native FLAC) → container-aware tagging (Vorbis/iTunes/ID3 +
 * cover art) → MediaStore write → Room insert keyed by the unique trackKey.
 */
@Singleton
class TrackDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val innerTube: InnerTubeMusicApi,
    private val audioTagWriter: AudioTagWriter,
    okHttpClient: OkHttpClient,
    private val downloadedTrackDao: DownloadedTrackDao,
    private val downloadQueueDao: DownloadQueueDao,
    private val userPreferences: UserPreferencesRepository,
    @Named("applicationScope") private val applicationScope: CoroutineScope,
) {
    companion object {
        const val CHANNEL_ID = "mus_downloads"
        const val ACTION_CANCEL_DOWNLOAD = "com.mus.android.ACTION_CANCEL_DOWNLOAD"
        const val EXTRA_DOWNLOAD_KEY = "download_key"
        private const val PUBLIC_DIR_NAME = "Muzic"
        // 128 KB keeps per-connection buffers out of the large-object heap.
        private const val DOWNLOAD_BUFFER_SIZE = 128 * 1024
        private const val PARALLEL_YOUTUBE_PARTS = 4
        private const val MAX_CONCURRENT_DOWNLOADS = 5
        private const val MAX_TRANSFER_RETRIES = 6
        private const val YT_STREAM_RESOLVE_TIMEOUT_MS = 20_000L
        private const val MIN_PARALLEL_DOWNLOAD_BYTES = 2L * 1024 * 1024
        private const val MIN_VALID_AUDIO_BYTES = 1_024L
        private const val RETRY_POLL_INTERVAL_MS = 500L
        private const val RETRY_BASE_DELAY_MS = 1_000L
        private const val RETRY_MAX_DELAY_MS = 30_000L
        private const val DOWNLOAD_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"
        private val CONTENT_RANGE_PATTERN = Regex("""bytes\s+(\d+)-(\d+)/(\d+)""", RegexOption.IGNORE_CASE)

        /** Normalized dedupe key — identical to LastWave's trackKey contract. */
        fun makeDownloadKey(title: String, artist: String): String =
            "${artist.trim().lowercase()}_${title.trim().lowercase()}"

        fun sanitizeFilename(name: String): String = name
            .replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "_")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .trim('.')
            .take(120)
            .ifBlank { "audio" }
    }

    // Dedicated HTTP client with extended timeouts and bounded pooling.
    private val downloadClient = okHttpClient.newBuilder()
        .dispatcher(Dispatcher().apply {
            maxRequests = 40
            maxRequestsPerHost = 24
        })
        .connectionPool(ConnectionPool(20, 5, TimeUnit.MINUTES))
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.MINUTES)
        .build()

    private val notificationManager: NotificationManager? by lazy {
        runCatching {
            context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        }.getOrNull()
    }
    private val activeKeys = ConcurrentHashMap.newKeySet<String>()
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val activeUris = ConcurrentHashMap<String, Uri>()
    private val activeFiles = ConcurrentHashMap<String, File>()
    private val downloadSlots = Semaphore(MAX_CONCURRENT_DOWNLOADS)

    private val _downloads = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val downloads: StateFlow<Map<String, DownloadProgress>> = _downloads.asStateFlow()

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = notificationManager ?: return
        runCatching {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Downloads",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "YouTube Music download progress"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    fun isDownloading(trackKey: String): Boolean = activeKeys.contains(trackKey)

    fun cancelDownload(key: String) {
        activeJobs.remove(key)?.cancel()
    }

    fun cancelDownload(title: String, artist: String) {
        cancelDownload(makeDownloadKey(title, artist))
    }

    /**
     * Enqueues a YouTube Music download. Deduplicates on the normalized
     * trackKey; the actual work runs on the injected application scope so a
     * UI lifecycle can never cancel an in-flight download.
     */
    fun downloadTrack(
        title: String,
        artist: String,
        album: String? = null,
        artworkUrl: String? = null,
        videoId: String? = null,
        durationMs: Long? = null,
        queueItemId: String? = null,
    ) {
        val finalTitle = title.trim().ifBlank { "Unknown title" }
        val finalArtist = artist.trim().ifBlank { "Unknown artist" }
        val key = makeDownloadKey(finalTitle, finalArtist)
        if (!activeKeys.add(key)) return // already downloading
        val job = applicationScope.launch {
            downloadSlots.acquire()
            try {
                runDownloadJob(
                    key = key,
                    title = finalTitle,
                    artist = finalArtist,
                    album = album,
                    artworkUrl = artworkUrl,
                    videoId = videoId,
                    durationMsHint = durationMs,
                    queueItemId = queueItemId,
                )
            } finally {
                downloadSlots.release()
                activeKeys.remove(key)
                activeJobs.remove(key)
            }
        }
        activeJobs[key] = job
    }

    private suspend fun runDownloadJob(
        key: String,
        title: String,
        artist: String,
        album: String?,
        artworkUrl: String?,
        videoId: String?,
        durationMsHint: Long?,
        queueItemId: String?,
    ) {
        val notifId = (key.hashCode() and 0x7FFFFFFF) or 0x40000000
        var tempDownloadFile: File? = null
        var destinationUri: Uri? = null
        var destinationFile: File? = null

        fun updateProgress(progress: DownloadProgress) {
            _downloads.update { it + (progress.key to progress) }
        }

        try {
            // Already downloaded? (trackKey unique REPLACE makes a re-run an update.)
            val existing = downloadedTrackDao.findByTrackKey(key)
            if (existing != null && existing.filePath.startsWith("/")) {
                val f = File(existing.filePath)
                if (f.exists() && f.length() > 0) {
                    updateProgress(
                        DownloadProgress(
                            key = key, title = existing.title, artist = existing.artist,
                            progressPercent = 100, isFinished = true,
                            formatBadge = existing.formatBadge,
                        ),
                    )
                    markQueueDownloaded(queueItemId)
                    return
                }
            }

            updateProgress(DownloadProgress(key = key, title = title, artist = artist))
            runCatching { showProgressNotification(notifId, key, title, artist, 0, indeterminate = true) }

            // 1. Metadata recovery: exact song details for the given videoId,
            //    or a best-match search when only title/artist are known.
            var resolvedVideoId = videoId?.takeIf { it.isNotBlank() }
            var finalTitle = title
            var finalArtist = artist
            var resolvedAlbum = album?.takeIf { it.isNotBlank() }
            var resolvedArtworkUrl = artworkUrl
            var durationMs = durationMsHint ?: 0L

            if (resolvedVideoId != null) {
                val details = runCatching { innerTube.fetchSongDetails(resolvedVideoId) }.getOrNull()
                if (details != null) {
                    if (title.isBlank() || title == "Unknown title") finalTitle = details.title
                    if (artist.isBlank() || artist == "Unknown artist") finalArtist = details.artist
                    if (resolvedAlbum.isNullOrBlank()) resolvedAlbum = details.album
                    if (resolvedArtworkUrl.isNullOrBlank()) resolvedArtworkUrl = details.artworkUrl
                    if (durationMs <= 0) durationMs = (details.durationSeconds ?: 0) * 1_000L
                }
            } else {
                val match: YouTubeMusicTrack = innerTube.findBestMatch(finalTitle, finalArtist)
                resolvedVideoId = match.videoId
                finalTitle = match.title.ifBlank { finalTitle }
                finalArtist = match.artist.ifBlank { finalArtist }
                if (resolvedAlbum.isNullOrBlank()) resolvedAlbum = match.album
                if (resolvedArtworkUrl.isNullOrBlank()) resolvedArtworkUrl = match.artworkUrl
                if (durationMs <= 0) durationMs = (match.durationSeconds ?: 0) * 1_000L
            }

            val finalKey = makeDownloadKey(finalTitle, finalArtist)
            if (finalKey != key) {
                // Metadata changed the key: if that track is already on disk, stop here.
                val existingByKey = downloadedTrackDao.findByTrackKey(finalKey)
                if (existingByKey != null) {
                    updateProgress(
                        DownloadProgress(
                            key = key, title = finalTitle, artist = finalArtist,
                            progressPercent = 100, isFinished = true,
                            formatBadge = existingByKey.formatBadge,
                        ),
                    )
                    markQueueDownloaded(queueItemId)
                    return
                }
            }

            // 2. Resolve the source stream. The authenticated InnerTube player
            //    path runs first (FLAC lossless renditions rank first when the
            //    signed-in account has them), then the NewPipe extractor; every
            //    candidate is byte-probed before use.
            var extension = "m4a"
            var mimeType = "audio/mp4"
            var formatBadge = "AUDIO"
            var isLossless = false
            var stream: YouTubeAudioStream? = null
            if (resolvedVideoId != null) {
                val candidates = buildList {
                    add(runCatching {
                        innerTube.peekCachedStream(resolvedVideoId)
                    }.getOrNull())
                    add(runCatching {
                        withTimeoutOrNull(YT_STREAM_RESOLVE_TIMEOUT_MS) {
                            innerTube.resolveAudioStream(resolvedVideoId, preferLossless = true)
                        }
                    }.getOrNull())
                    add(runCatching {
                        withTimeoutOrNull(YT_STREAM_RESOLVE_TIMEOUT_MS) { innerTube.resolveDownloadStream(resolvedVideoId) }
                    }.getOrNull())
                }.filterNotNull()
                stream = candidates.firstOrNull { s ->
                    runCatching { probeStream(s) }.getOrDefault(false)
                } ?: candidates.firstOrNull()
            }
            if (stream == null || stream.url.isBlank()) {
                throw IOException("Could not resolve a downloadable audio stream for \"$finalTitle\"")
            }

            // 3. Transfer to a temp file (parallel byte ranges with resume).
            val temp = File.createTempFile("mus_dl_", ".bin", context.cacheDir)
            tempDownloadFile = temp
            var bytesTotal = 0L
            val transfer = downloadToTempFile(
                downloadKey = key,
                url = stream.url,
                target = temp,
                requestHeaders = stream.requestHeaders,
                expectedContentLength = stream.contentLength,
                useParallelRanges = true,
                onConnectionStateChanged = { waiting ->
                    _downloads.update { map ->
                        val current = map[key] ?: return@update map
                        map + (key to current.copy(isWaitingForConnection = waiting))
                    }
                },
                onProgress = { downloaded, total ->
                    bytesTotal = total
                    val percent = if (total > 0) ((downloaded * 100) / total).toInt().coerceIn(0, 99) else 0
                    updateProgress(
                        DownloadProgress(
                            key = key, title = finalTitle, artist = finalArtist,
                            progressPercent = percent,
                            bytesDownloaded = downloaded,
                            totalBytes = total,
                            formatBadge = formatBadge,
                        ),
                    )
                },
            )
            val bytesReadTotal = transfer.bytesDownloaded
            val totalBytesRecorded = transfer.totalBytes

            // 4. Container sniffing decides the real extension/mime.
            when (detectValidContainer(temp)) {
                "flac" -> { extension = "flac"; mimeType = "audio/flac"; formatBadge = "FLAC"; isLossless = true }
                "m4a" -> { extension = "m4a"; mimeType = "audio/mp4"; formatBadge = "M4A" }
                "webm" -> { extension = "webm"; mimeType = "audio/webm"; formatBadge = "WEBM" }
                "opus" -> { extension = "opus"; mimeType = "audio/ogg"; formatBadge = "OPUS" }
                "mp3" -> { extension = "mp3"; mimeType = "audio/mpeg"; formatBadge = "MP3" }
                else -> {}
            }

            // 5. Lossless remux: WebM Opus → Ogg Opus for universal
            //    player & tag compatibility, and FLAC-in-MP4 → native FLAC so
            //    lossless downloads are real .flac files. Nothing is re-encoded.
            var currentAudioFile = temp
            if (extension == "webm") {
                val opusFile = File.createTempFile("mus_remux_", ".opus", context.cacheDir)
                if (WebmOpusRemuxer.remux(currentAudioFile, opusFile)) {
                    currentAudioFile.delete()
                    currentAudioFile = opusFile
                    tempDownloadFile = opusFile
                    extension = "opus"
                    mimeType = "audio/ogg"
                    formatBadge = "OPUS"
                } else {
                    opusFile.delete()
                }
            }
            if (extension == "m4a" && Mp4FlacRemuxer.isMp4Flac(currentAudioFile)) {
                val flacFile = File.createTempFile("mus_remux_", ".flac", context.cacheDir)
                if (Mp4FlacRemuxer.remux(currentAudioFile, flacFile)) {
                    currentAudioFile.delete()
                    currentAudioFile = flacFile
                    tempDownloadFile = flacFile
                    extension = "flac"
                    mimeType = "audio/flac"
                    formatBadge = "FLAC"
                    isLossless = true
                } else {
                    flacFile.delete()
                }
            }

            val safeFilename = sanitizeFilename("$finalArtist - $finalTitle") + ".$extension"

            // 6. Resolve exact duration from the downloaded file.
            val durationRetriever = MediaMetadataRetriever()
            try {
                durationRetriever.setDataSource(currentAudioFile.absolutePath)
                durationRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()?.takeIf { it > 0 }?.let { durationMs = it }
                if (resolvedAlbum.isNullOrBlank()) {
                    resolvedAlbum = durationRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                        ?.takeIf(String::isNotBlank)
                }
            } catch (_: Exception) {
            } finally {
                runCatching { durationRetriever.release() }
            }

            // Artwork chain: search artwork → the video's own YouTube thumbnail.
            if (resolvedArtworkUrl.isNullOrBlank()) {
                resolvedArtworkUrl = resolvedVideoId?.takeIf { it.isNotBlank() }
                    ?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }
            }

            // 7. Embed tags + cover art (container-aware).
            val metadataEmbedded = audioTagWriter.embedMetadata(
                audioFile = currentAudioFile,
                title = finalTitle,
                artist = finalArtist,
                album = resolvedAlbum,
                artworkUrl = resolvedArtworkUrl,
                year = null,
            )
            if (!metadataEmbedded) {
                android.util.Log.w("TrackDownloadManager", "Could not safely embed audio metadata; preserving audio file")
            }

            // 8. Transfer the tagged file into public storage (MediaStore).
            currentCoroutineContext().ensureActive()
            val (destStream, uri, file) = openPublicOutputStream(
                filename = safeFilename,
                mimeType = mimeType,
                title = finalTitle,
                artist = finalArtist,
                album = resolvedAlbum,
                durationMs = durationMs,
            )
            destinationUri = uri
            destinationFile = file
            if (uri != null) activeUris[key] = uri
            if (file != null) activeFiles[key] = file

            val taggedFileLength = currentAudioFile.length()
            val copiedBytes = currentAudioFile.inputStream().use { input ->
                destStream.use { output ->
                    val copied = input.copyTo(output, DOWNLOAD_BUFFER_SIZE)
                    output.flush()
                    if (output is FileOutputStream) output.fd.sync()
                    copied
                }
            }
            if (copiedBytes != taggedFileLength) {
                throw IOException("Public file copy truncated: received $copiedBytes of $taggedFileLength bytes")
            }

            // 9. Publish the MediaStore row + scan plain files.
            finalizePublicFile(uri)
            if (file != null) {
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(file.absolutePath),
                    arrayOf(mimeType),
                    null,
                )
            }

            val publicMusicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), PUBLIC_DIR_NAME)
            val expectedPublicFile = File(publicMusicDir, safeFilename)
            val finalPath = file?.absolutePath
                ?: expectedPublicFile.takeIf { it.exists() && it.length() > 0 }?.absolutePath
                ?: uri?.toString()
                ?: expectedPublicFile.absolutePath

            // 10. Persist to Room, keyed by the unique trackKey (REPLACE).
            val entity = DownloadedTrackEntity(
                trackKey = finalKey,
                title = finalTitle,
                artist = finalArtist,
                album = resolvedAlbum.orEmpty(),
                artworkUrl = resolvedArtworkUrl,
                filePath = finalPath,
                mediaStoreUri = uri?.toString(),
                fileSizeBytes = currentAudioFile.length(),
                formatBadge = formatBadge,
                durationMs = durationMs,
                bitrateKbps = stream.bitrate.takeIf { it > 0 }?.div(1000),
                isLossless = isLossless,
                downloadedAtMillis = System.currentTimeMillis(),
            )
            downloadedTrackDao.insert(entity)

            updateProgress(
                DownloadProgress(
                    key = key,
                    title = finalTitle,
                    artist = finalArtist,
                    progressPercent = 100,
                    bytesDownloaded = bytesReadTotal,
                    totalBytes = if (totalBytesRecorded > 0) totalBytesRecorded else bytesReadTotal,
                    formatBadge = formatBadge,
                    isFinished = true,
                ),
            )
            markQueueDownloaded(queueItemId)
            runCatching { showCompletedNotification(notifId, finalTitle, finalArtist, formatBadge) }
        } catch (cancelled: CancellationException) {
            destinationUri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
            destinationFile?.let { runCatching { if (it.exists()) it.delete() } }
            notificationManager?.cancel(notifId)
            _downloads.update { it - key }
            throw cancelled
        } catch (error: Exception) {
            destinationUri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
            destinationFile?.let { runCatching { if (it.exists()) it.delete() } }
            updateProgress(
                DownloadProgress(
                    key = key,
                    title = title,
                    artist = artist,
                    error = error.localizedMessage ?: error.message ?: "Download failed",
                ),
            )
            markQueueFailed(queueItemId)
            runCatching {
                showErrorNotification(notifId, key, title, artist, error.localizedMessage ?: "Failed")
            }
        } finally {
            activeUris.remove(key)
            activeFiles.remove(key)
            tempDownloadFile?.let { runCatching { if (it.exists()) it.delete() } }
        }
    }

    private suspend fun markQueueDownloaded(queueItemId: String?) {
        val id = queueItemId ?: return
        runCatching { downloadQueueDao.updateStatus(id, DownloadStatus.DOWNLOADED) }
    }

    private suspend fun markQueueFailed(queueItemId: String?) {
        val id = queueItemId ?: return
        runCatching { downloadQueueDao.updateStatus(id, DownloadStatus.FAILED) }
    }

    // ---------------------------------------------------------------------
    // Transfer (parallel byte ranges + resumable single stream)
    // ---------------------------------------------------------------------

    private suspend fun downloadToTempFile(
        downloadKey: String,
        url: String,
        target: File,
        requestHeaders: Map<String, String>,
        expectedContentLength: Long?,
        useParallelRanges: Boolean,
        onConnectionStateChanged: (Boolean) -> Unit,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): DownloadTransfer = withContext(Dispatchers.IO) {
        val parallelLength = expectedContentLength
            ?.takeIf { useParallelRanges && it >= MIN_PARALLEL_DOWNLOAD_BYTES }
        if (parallelLength != null) {
            try {
                return@withContext downloadInParallel(
                    url = url,
                    target = target,
                    requestHeaders = requestHeaders,
                    totalLength = parallelLength,
                    onConnectionStateChanged = onConnectionStateChanged,
                    onProgress = onProgress,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                android.util.Log.w("TrackDownloadManager", "Validated range download unavailable; using one stream", error)
                truncateFile(target)
            }
        }

        downloadSingleStream(
            url = url,
            target = target,
            requestHeaders = requestHeaders,
            expectedContentLength = expectedContentLength,
            onConnectionStateChanged = onConnectionStateChanged,
            onProgress = onProgress,
        )
    }

    private suspend fun downloadInParallel(
        url: String,
        target: File,
        requestHeaders: Map<String, String>,
        totalLength: Long,
        onConnectionStateChanged: (Boolean) -> Unit,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): DownloadTransfer = kotlinx.coroutines.coroutineScope {
        val partSize = (totalLength + PARALLEL_YOUTUBE_PARTS - 1L) / PARALLEL_YOUTUBE_PARTS
        val ranges = (0 until PARALLEL_YOUTUBE_PARTS).mapNotNull { index ->
            val start = index * partSize
            if (start >= totalLength) return@mapNotNull null
            DownloadRange(start, minOf(totalLength - 1L, start + partSize - 1L))
        }
        RandomAccessFile(target, "rw").use { it.setLength(totalLength) }

        val downloadedBytes = AtomicLong(0L)
        val contentTypes = ranges.map { range ->
            async {
                var downloadedBytesInRange = 0L
                retryInterruptedTransfer(onConnectionStateChanged) {
                    downloadRange(
                        url = url,
                        target = target,
                        requestHeaders = requestHeaders,
                        range = DownloadRange(range.start + downloadedBytesInRange, range.endInclusive),
                        expectedTotal = totalLength,
                    ) { delta ->
                        downloadedBytesInRange += delta
                        onProgress(downloadedBytes.addAndGet(delta.toLong()), totalLength)
                    }
                }
            }
        }.awaitAll()

        val finalLength = downloadedBytes.get()
        if (finalLength != totalLength || target.length() != totalLength) {
            throw IOException("Parallel download truncated: received $finalLength of $totalLength bytes")
        }
        RandomAccessFile(target, "rw").use { it.fd.sync() }
        DownloadTransfer(
            bytesDownloaded = finalLength,
            totalBytes = totalLength,
            contentType = contentTypes.firstOrNull { it.isNotBlank() }.orEmpty(),
        )
    }

    private suspend fun downloadRange(
        url: String,
        target: File,
        requestHeaders: Map<String, String>,
        range: DownloadRange,
        expectedTotal: Long,
        onBytesRead: (Int) -> Unit,
    ): String = suspendCancellableCoroutine { continuation ->
        val request = buildDownloadRequest(url, requestHeaders, range)
        val call = downloadClient.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val contentType = response.use { currentResponse ->
                        validateMediaResponse(currentResponse, expectedCode = 206)
                        val parsedRange = parseContentRange(currentResponse.header("Content-Range"))
                            ?: throw DownloadProtocolException("Missing Content-Range for parallel download")
                        if (parsedRange.start != range.start ||
                            parsedRange.endInclusive != range.endInclusive ||
                            parsedRange.total != expectedTotal
                        ) {
                            throw DownloadProtocolException("Mismatched Content-Range ${currentResponse.header("Content-Range")}")
                        }

                        val body = currentResponse.body
                            ?: throw DownloadProtocolException("Empty range response body")
                        val bodyLength = body.contentLength()
                        if (bodyLength > 0L && bodyLength != range.length) {
                            throw DownloadProtocolException("Range length mismatch: received $bodyLength of ${range.length} bytes")
                        }

                        RandomAccessFile(target, "rw").use { output ->
                            output.seek(range.start)
                            body.byteStream().use { input ->
                                val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                                var remaining = range.length
                                while (remaining > 0L) {
                                    if (!continuation.isActive) throw CancellationException("Download cancelled")
                                    val read = try {
                                        input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                    } catch (error: IOException) {
                                        throw DownloadInterruptedException("Connection interrupted while reading audio", error)
                                    }
                                    if (read < 0) {
                                        throw DownloadInterruptedException("Range truncated with $remaining bytes remaining")
                                    }
                                    output.write(buffer, 0, read)
                                    remaining -= read
                                    onBytesRead(read)
                                }
                            }
                        }
                        currentResponse.header("Content-Type").orEmpty()
                    }
                    if (continuation.isActive) continuation.resume(contentType)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }

    private suspend fun downloadSingleStream(
        url: String,
        target: File,
        requestHeaders: Map<String, String>,
        expectedContentLength: Long?,
        onConnectionStateChanged: (Boolean) -> Unit,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): DownloadTransfer {
        val expectedLength = expectedContentLength?.takeIf { it > 0L }
        return retryInterruptedTransfer(onConnectionStateChanged) {
            if (expectedLength != null && target.length() > expectedLength) truncateFile(target)
            if (expectedLength != null && target.length() == expectedLength && expectedLength >= MIN_VALID_AUDIO_BYTES) {
                onProgress(expectedLength, expectedLength)
                return@retryInterruptedTransfer DownloadTransfer(expectedLength, expectedLength, "")
            }
            downloadSingleStreamAttempt(
                url = url,
                target = target,
                requestHeaders = requestHeaders,
                expectedContentLength = expectedLength,
                onProgress = onProgress,
            )
        }
    }

    private suspend fun downloadSingleStreamAttempt(
        url: String,
        target: File,
        requestHeaders: Map<String, String>,
        expectedContentLength: Long?,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): DownloadTransfer = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            val resumeOffset = target.length().coerceAtLeast(0L)
            val call = downloadClient.newCall(
                buildDownloadRequest(url, requestHeaders, range = null, resumeOffset = resumeOffset),
            )
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val transfer = response.use { currentResponse ->
                            validateMediaResponse(currentResponse)
                            val body = currentResponse.body
                                ?: throw DownloadProtocolException("Empty response body")
                            val responseLength = body.contentLength().takeIf { it > 0L }
                            val partialRange = if (currentResponse.code == 206) {
                                parseContentRange(currentResponse.header("Content-Range"))
                            } else {
                                null
                            }
                            if (partialRange != null && (partialRange.start != resumeOffset ||
                                    partialRange.endInclusive + 1L != partialRange.total)
                            ) {
                                throw DownloadProtocolException("Server returned an unexpected audio range")
                            }
                            if (partialRange != null && expectedContentLength != null &&
                                partialRange.total != expectedContentLength
                            ) {
                                throw DownloadProtocolException(
                                    "Download size changed: expected $expectedContentLength, received ${partialRange.total}",
                                )
                            }

                            val totalLength = partialRange?.total
                                ?: responseLength
                                ?: expectedContentLength?.takeIf { it > 0L }
                                ?: -1L

                            val append = resumeOffset > 0L && partialRange != null
                            var downloadedBytes = if (append) resumeOffset else 0L
                            FileOutputStream(target, append).use { output ->
                                body.byteStream().use { input ->
                                    val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                                    while (true) {
                                        if (!continuation.isActive) throw CancellationException("Download cancelled")
                                        val read = try {
                                            input.read(buffer)
                                        } catch (error: IOException) {
                                            throw DownloadInterruptedException("Connection interrupted while reading audio", error)
                                        }
                                        if (read < 0) break
                                        output.write(buffer, 0, read)
                                        downloadedBytes += read
                                        onProgress(downloadedBytes, totalLength)
                                    }
                                }
                                output.flush()
                                output.fd.sync()
                            }
                            if (downloadedBytes < MIN_VALID_AUDIO_BYTES) {
                                throw DownloadProtocolException("Downloaded audio payload is too small")
                            }
                            if (totalLength > 0L && downloadedBytes != totalLength) {
                                if (downloadedBytes < totalLength) {
                                    throw DownloadInterruptedException(
                                        "Download truncated: received $downloadedBytes of $totalLength bytes",
                                    )
                                }
                                throw DownloadProtocolException(
                                    "Download exceeded expected size: received $downloadedBytes of $totalLength bytes",
                                )
                            }

                            DownloadTransfer(
                                bytesDownloaded = downloadedBytes,
                                totalBytes = totalLength.takeIf { it > 0L } ?: downloadedBytes,
                                contentType = currentResponse.header("Content-Type").orEmpty(),
                            )
                        }
                        if (continuation.isActive) continuation.resume(transfer)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            })
        }
    }

    private suspend fun <T> retryInterruptedTransfer(
        onConnectionStateChanged: (Boolean) -> Unit,
        transfer: suspend () -> T,
    ): T {
        var failureCount = 0
        while (true) {
            try {
                return transfer()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: IOException) {
                if (!error.isReconnectableTransferFailure()) throw error
                failureCount++
                if (failureCount > MAX_TRANSFER_RETRIES) throw error
                onConnectionStateChanged(true)
                try {
                    awaitRetryOpportunity(failureCount)
                } finally {
                    onConnectionStateChanged(false)
                }
            }
        }
    }

    private suspend fun awaitRetryOpportunity(failureCount: Int) {
        val retryDelayMs = (RETRY_BASE_DELAY_MS * (1L shl (failureCount - 1).coerceIn(0, 5)))
            .coerceAtMost(RETRY_MAX_DELAY_MS)
        var elapsedMs = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            if (elapsedMs >= retryDelayMs && hasUsableNetwork()) return
            delay(RETRY_POLL_INTERVAL_MS)
            elapsedMs += RETRY_POLL_INTERVAL_MS
        }
    }

    private fun hasUsableNetwork(): Boolean = runCatching {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return@runCatching true
        val network = manager.activeNetwork ?: return@runCatching false
        val capabilities = manager.getNetworkCapabilities(network) ?: return@runCatching false
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(true)

    private fun IOException.isReconnectableTransferFailure(): Boolean {
        if (this is DownloadProtocolException) return false
        if (this is DownloadHttpException) {
            return statusCode == 408 || statusCode == 425 || statusCode == 429 || statusCode in 500..599
        }
        return generateSequence<Throwable>(this) { it.cause }
            .take(8)
            .any {
                it is DownloadInterruptedException ||
                    it is EOFException ||
                    it is ConnectException ||
                    it is NoRouteToHostException ||
                    it is SocketException ||
                    it is UnknownHostException ||
                    it is InterruptedIOException
            }
    }

    private fun buildDownloadRequest(
        url: String,
        requestHeaders: Map<String, String>,
        range: DownloadRange?,
        resumeOffset: Long = 0L,
    ): Request {
        val builder = Request.Builder().url(url)
        requestHeaders.forEach { (name, value) -> builder.header(name, value) }
        if (requestHeaders.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            builder.header("User-Agent", DOWNLOAD_USER_AGENT)
        }
        builder.header("Accept", "*/*")
        builder.header("Accept-Encoding", "identity")
        when {
            range != null -> builder.header("Range", "bytes=${range.start}-${range.endInclusive}")
            resumeOffset > 0L -> builder.header("Range", "bytes=$resumeOffset-")
        }
        return builder.build()
    }

    private fun validateMediaResponse(response: Response, expectedCode: Int? = null) {
        if (response.code != 200 && response.code != 206) {
            throw DownloadHttpException(response.code)
        }
        if (expectedCode != null && response.code != expectedCode) {
            throw DownloadProtocolException("Server does not support resumable byte ranges")
        }

        val contentType = response.header("Content-Type").orEmpty().lowercase()
        if (contentType.contains("text/html") ||
            contentType.contains("application/json") ||
            contentType.contains("text/plain")
        ) {
            throw DownloadProtocolException("Invalid download payload ($contentType)")
        }
    }

    private fun parseContentRange(header: String?): ParsedContentRange? {
        val match = header?.trim()?.let(CONTENT_RANGE_PATTERN::matchEntire) ?: return null
        return ParsedContentRange(
            start = match.groupValues[1].toLongOrNull() ?: return null,
            endInclusive = match.groupValues[2].toLongOrNull() ?: return null,
            total = match.groupValues[3].toLongOrNull() ?: return null,
        )
    }

    private fun truncateFile(file: File) {
        RandomAccessFile(file, "rw").use { it.setLength(0L) }
    }

    private fun probeStream(stream: YouTubeAudioStream): Boolean = runCatching {
        val request = Request.Builder()
            .url(stream.url)
            .header("Accept-Encoding", "identity")
            .header("Range", "bytes=0-1")
            .apply { stream.requestHeaders.forEach { (n, v) -> header(n, v) } }
            .build()
        val call = downloadClient.newCall(request)
        call.timeout().timeout(6_000L, TimeUnit.MILLISECONDS)
        call.execute().use { response ->
            val contentType = response.header("Content-Type").orEmpty().lowercase()
            val validType = !contentType.contains("text/html") &&
                !contentType.contains("application/json") &&
                !contentType.contains("text/plain")
            (response.code == 200 || response.code == 206) && validType
        }
    }.getOrDefault(false)

    private fun detectValidContainer(file: File): String? = runCatching {
        if (file.length() < 12L) return@runCatching null
        val header = ByteArray(12)
        RandomAccessFile(file, "r").use { input ->
            if (input.read(header) != header.size) return@runCatching null
        }
        when {
            header.copyOfRange(0, 4).contentEquals(byteArrayOf('f'.code.toByte(), 'L'.code.toByte(), 'a'.code.toByte(), 'C'.code.toByte())) -> "flac"
            header.copyOfRange(4, 8).contentEquals(byteArrayOf('f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte())) -> "m4a"
            header.copyOfRange(0, 4).contentEquals(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())) -> "webm"
            header.copyOfRange(0, 4).contentEquals(byteArrayOf('O'.code.toByte(), 'g'.code.toByte(), 'g'.code.toByte(), 'S'.code.toByte())) -> "opus"
            header.copyOfRange(0, 3).contentEquals(byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte())) ||
                ((header[0].toInt() and 0xFF) == 0xFF && (header[1].toInt() and 0xE0) == 0xE0) -> "mp3"
            else -> null
        }
    }.getOrNull()

    // ---------------------------------------------------------------------
    // Storage
    // ---------------------------------------------------------------------

    /**
     * Storage chain for finished downloads:
     *  1. Direct file write into the device's /Muzic/ vault — exact folder the
     *     user named — when All files access is granted (or on legacy-storage
     *     devices). The MediaStore scanner already indexes that tree.
     *  2. The SAF tree picked via "Choose Muzic Folder" (no permissions needed).
     *  3. MediaStore Music/Muzic (Q+; always permitted) so a download never fails.
     *  4. App-scoped music directory fallback.
     */
    private fun openPublicOutputStream(
        filename: String,
        mimeType: String,
        title: String,
        artist: String,
        album: String?,
        durationMs: Long = 0L,
    ): Triple<java.io.OutputStream, Uri?, File?> {
        val resolver = context.contentResolver

        // 1. /Muzic/ vault via direct file paths.
        directVaultOutputStream(filename)?.let { return it }

        // 2. User-picked SAF tree (the "Choose Muzic Folder" flow).
        safTreeOutputStream(filename, mimeType)?.let { return it }

        val safeDir = sanitizeFilename(PUBLIC_DIR_NAME)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val audioMime = when {
                mimeType.contains("mp4") || mimeType.contains("m4a") || mimeType.contains("aac") -> "audio/mp4"
                mimeType.contains("flac") -> "audio/flac"
                mimeType.contains("mp3") || mimeType.contains("mpeg") -> "audio/mpeg"
                mimeType.contains("webm") -> "audio/webm"
                mimeType.contains("ogg") || mimeType.contains("opus") -> "audio/ogg"
                mimeType.contains("wav") -> "audio/x-wav"
                else -> "audio/mp4"
            }
            val audioRelativePath = "${Environment.DIRECTORY_MUSIC}/$safeDir"

            // Remove any pre-existing entry with the same filename in the same
            // folder so Android doesn't append (1), (2), etc.
            runCatching {
                resolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Audio.Media._ID),
                    "${MediaStore.Audio.Media.DISPLAY_NAME} = ? AND ${MediaStore.Audio.Media.RELATIVE_PATH} = ?",
                    arrayOf(filename, "$audioRelativePath/"),
                    null,
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndex(MediaStore.Audio.Media._ID)
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val oldUri = Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id.toString())
                        runCatching { resolver.delete(oldUri, null, null) }
                    }
                }
            }

            val audioContentValues = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, filename)
                put(MediaStore.Audio.Media.MIME_TYPE, audioMime)
                put(MediaStore.Audio.Media.RELATIVE_PATH, audioRelativePath)
                put(MediaStore.Audio.Media.TITLE, title)
                put(MediaStore.Audio.Media.ARTIST, artist)
                put(MediaStore.Audio.Media.ALBUM_ARTIST, artist)
                if (!album.isNullOrBlank()) put(MediaStore.Audio.Media.ALBUM, album)
                if (durationMs > 0) put(MediaStore.Audio.Media.DURATION, durationMs)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }

            val audioUri = runCatching { resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, audioContentValues) }.getOrNull()
            if (audioUri != null) {
                val stream = resolver.openOutputStream(audioUri, "wt")
                    ?: resolver.openOutputStream(audioUri)
                if (stream != null) return Triple(stream, audioUri, null)
            }
        }

        // Fallback: app-scoped music directory.
        val fallbackBase = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            ?: File(context.filesDir, "music").apply { if (!exists()) mkdirs() }
        if (!fallbackBase.exists()) fallbackBase.mkdirs()
        val fallbackFile = File(fallbackBase, filename)
        return Triple(FileOutputStream(fallbackFile), null, fallbackFile)
    }

    /** Attempts a direct File write into /storage/emulated/0/Muzic/. Works when
     *  All files access is granted (API 30+) or on legacy-storage devices; null
     *  otherwise so the chain falls through. */
    private fun directVaultOutputStream(filename: String): Triple<java.io.OutputStream, Uri?, File?>? {
        return runCatching<Triple<java.io.OutputStream, Uri?, File?>?> {
            val vault = File(Environment.getExternalStorageDirectory(), PUBLIC_DIR_NAME)
            if (!vault.exists() && !vault.mkdirs()) return@runCatching null
            val target = File(vault, filename)
            if (target.exists()) target.delete()
            Triple(java.io.FileOutputStream(target) as java.io.OutputStream, null, target)
        }.getOrNull()
    }

    /** Attempts a write into the SAF tree persisted by "Choose Muzic Folder". */
    private fun safTreeOutputStream(filename: String, mimeType: String): Triple<java.io.OutputStream, Uri?, File?>? {
        val treeUriString = userPreferences.customMuzicUri.value ?: return null
        val resolver = context.contentResolver
        return runCatching<Triple<java.io.OutputStream, Uri?, File?>?> {
            val treeUri = Uri.parse(treeUriString)
            val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
            val rootDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, rootDocId)
            // Replace same-named children so the provider doesn't append " (1)".
            runCatching {
                resolver.query(
                    childrenUri,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    ),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    while (cursor.moveToNext()) {
                        if (cursor.getString(nameCol) == filename) {
                            val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(idCol))
                            runCatching { DocumentsContract.deleteDocument(resolver, childUri) }
                        }
                    }
                }
            }
            val docUri = DocumentsContract.createDocument(resolver, rootDocUri, mimeType, filename)
                ?: return@runCatching null
            val stream = resolver.openOutputStream(docUri, "wt")
                ?: resolver.openOutputStream(docUri)
                ?: return@runCatching null
            Triple(stream, docUri, null)
        }.getOrNull()
    }

    private fun finalizePublicFile(uri: Uri?) {
        if (uri == null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                val values = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
                context.contentResolver.update(uri, values, null, null)
            }
        }
    }

    // ---------------------------------------------------------------------
    // Notifications
    // ---------------------------------------------------------------------

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun cancelIntent(key: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        key.hashCode() and 0x7FFFFFFF,
        Intent(context, DownloadCancelReceiver::class.java).apply {
            action = ACTION_CANCEL_DOWNLOAD
            putExtra(EXTRA_DOWNLOAD_KEY, key)
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun showProgressNotification(
        notifId: Int,
        key: String,
        title: String,
        artist: String,
        percent: Int,
        indeterminate: Boolean = false,
    ) {
        val manager = notificationManager ?: return
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(artist)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(contentIntent())
            .addAction(0, "Cancel", cancelIntent(key))
        if (indeterminate) {
            builder.setProgress(0, 0, true)
        } else {
            builder.setProgress(100, percent.coerceIn(0, 100), false)
        }
        runCatching {
            if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < 33
            ) {
                manager.notify(notifId, builder.build())
            }
        }
    }

    private fun showCompletedNotification(notifId: Int, title: String, artist: String, badge: String) {
        val manager = notificationManager ?: return
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Downloaded")
            .setContentText("$title — $artist ($badge)")
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(contentIntent())
            .build()
        runCatching {
            if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < 33
            ) {
                manager.notify(notifId, notification)
            }
        }
    }

    private fun showErrorNotification(notifId: Int, key: String, title: String, artist: String, message: String) {
        val manager = notificationManager ?: return
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Download failed")
            .setContentText("$title — $message")
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(contentIntent())
            .build()
        runCatching {
            if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < 33
            ) {
                manager.notify(notifId, notification)
            }
        }
    }
}
