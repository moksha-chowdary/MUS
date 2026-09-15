package com.mus.android.data.enrichment

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.mus.android.data.db.AlbumDao
import com.mus.android.data.db.ArtistDao
import com.mus.android.data.db.TrackDao
import com.mus.android.data.enrichment.artwork.ArtworkStorage
import com.mus.android.data.enrichment.provider.MetadataProvider
import com.mus.android.data.enrichment.provider.RemoteTrackMetadata
import com.mus.android.data.model.*
import com.mus.android.data.repository.UserPreferencesRepository
import com.mus.android.data.scanner.MetadataUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Core service orchestrating automatic metadata and artwork enrichment.
 * Preserves all valid embedded metadata, identifies missing metadata via a
 * replaceable MetadataProvider, verifies match confidence, persists downloaded
 * artwork locally, and updates Room asynchronously without blocking playback.
 */
@Singleton
class MetadataEnrichmentService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val trackDao: TrackDao,
    private val albumDao: AlbumDao,
    private val artistDao: ArtistDao,
    private val metadataProvider: MetadataProvider,
    private val artworkStorage: ArtworkStorage,
    private val userPreferences: UserPreferencesRepository,
) {
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val concurrencySemaphore = Semaphore(1) // Serialized to strictly prevent iTunes 429 rate limiting
    private val inFlightTrackIds = ConcurrentHashMap.newKeySet<Long>()
    private var lastRequestTime = 0L
    private val rateLimitLock = Any()

    private suspend fun throttleRequest() {
        val waitTime = synchronized(rateLimitLock) {
            val now = System.currentTimeMillis()
            val elapsed = now - lastRequestTime
            if (elapsed < 600L) {
                val toWait = 600L - elapsed
                lastRequestTime = now + toWait
                toWait
            } else {
                lastRequestTime = now
                0L
            }
        }
        if (waitTime > 0L) {
            delay(waitTime)
        }
    }

    /**
     * Enqueues a list of tracks for background metadata enrichment.
     * Non-blocking; returns immediately.
     */
    fun enqueueEnrichment(tracks: List<Track>) {
        if (!userPreferences.automaticMetadataEnabled.value) {
            Log.d(TAG, "Automatic metadata enrichment is disabled in settings.")
            return
        }

        serviceScope.launch {
            for (track in tracks) {
                if (needsEnrichment(track)) {
                    concurrencySemaphore.withPermit {
                        throttleRequest()
                        if (inFlightTrackIds.add(track.id)) {
                            try {
                                enrichTrackInternal(track, forceRefresh = false)
                            } finally {
                                inFlightTrackIds.remove(track.id)
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Manually triggers metadata enrichment for a single track.
     * Can be invoked directly from UI or ViewModel for "Refresh Metadata".
     */
    suspend fun enrichTrack(track: Track, forceRefresh: Boolean = true): Track = withContext(Dispatchers.IO) {
        if (inFlightTrackIds.add(track.id)) {
            try {
                enrichTrackInternal(track, forceRefresh)
            } finally {
                inFlightTrackIds.remove(track.id)
            }
        } else {
            track
        }
    }

    /**
     * Determines whether a track is missing essential metadata or artwork.
     * Independently evaluates title, artist, album, album artist, and artwork.
     * Having artwork alone NEVER marks a track complete if text metadata is missing.
     */
    fun needsEnrichment(track: Track): Boolean {
        when (track.metadataStatus) {
            MetadataStatus.COMPLETE -> return false
            MetadataStatus.NEEDS_REVIEW -> return false
            MetadataStatus.ENRICHING -> {
                // Do not enqueue again unless stale (> 5 minutes)
                val isStale = System.currentTimeMillis() - track.metadataLastUpdated > 5 * 60 * 1000L
                if (!isStale) return false
            }
            MetadataStatus.FAILED -> {
                // Retry according to retry policy (> 24 hours)
                val shouldRetry = System.currentTimeMillis() - track.metadataLastUpdated > 24 * 60 * 60 * 1000L
                if (!shouldRetry) return false
            }
            MetadataStatus.NEEDS_LOOKUP, MetadataStatus.PARTIAL -> { /* evaluate fields */ }
        }

        val isTitlePlaceholder = MetadataUtils.isPlaceholderTitle(track.title)
        val isArtistPlaceholder = MetadataUtils.isPlaceholderArtist(track.artist)
        val isAlbumPlaceholder = MetadataUtils.isPlaceholderAlbum(track.albumTitle)
        val isAlbumArtistPlaceholder = MetadataUtils.isPlaceholderArtist(track.albumArtist)
        val hasArtwork = hasValidArtwork(track)

        // Having artwork does NOT make metadata complete.
        // If title, artist, album, or album artist is missing/placeholder, it MUST still enrich!
        if (isTitlePlaceholder || isArtistPlaceholder || isAlbumPlaceholder || isAlbumArtistPlaceholder || !hasArtwork) {
            return true
        }

        return track.metadataStatus == MetadataStatus.NEEDS_LOOKUP ||
                track.metadataStatus == MetadataStatus.PARTIAL
    }

    private fun hasValidArtwork(track: Track): Boolean {
        return ArtworkStorage.isArtworkValid(track.artworkUri)
    }

    private suspend fun enrichTrackInternal(track: Track, forceRefresh: Boolean): Track {
        // Diagnostic Points 14 & 15
        val isEnrichmentNeeded = needsEnrichment(track)
        val shouldEnrich = forceRefresh || isEnrichmentNeeded
        val reason = when {
            forceRefresh -> "forceRefresh requested"
            MetadataUtils.isPlaceholderArtist(track.artist) -> "artist is placeholder/missing: '${track.artist}'"
            MetadataUtils.isPlaceholderAlbum(track.albumTitle) -> "album is placeholder/missing: '${track.albumTitle}'"
            MetadataUtils.isPlaceholderTitle(track.title) -> "title is placeholder/missing: '${track.title}'"
            !hasValidArtwork(track) -> "artwork is missing"
            track.metadataStatus == MetadataStatus.NEEDS_LOOKUP -> "status is NEEDS_LOOKUP"
            track.metadataStatus == MetadataStatus.PARTIAL -> "status is PARTIAL"
            else -> "already complete and has valid artwork"
        }
        Log.i("DIAG_METADATA", "=== TRACK ENRICHMENT DIAGNOSTICS ===")
        Log.i("DIAG_METADATA", "[Point 14] Whether enrichment was triggered: $shouldEnrich (trackId=${track.id})")
        Log.i("DIAG_METADATA", "[Point 15] Why enrichment was triggered or skipped: $reason")

        if (!shouldEnrich) {
            return track
        }

        // Step 1: Check existing cached artwork ONLY if album and artist are known (never for placeholders)
        var currentTrack = track
        if (!hasValidArtwork(currentTrack)) {
            if (!MetadataUtils.isPlaceholderAlbum(currentTrack.albumTitle) &&
                !MetadataUtils.isPlaceholderArtist(currentTrack.albumArtist)
            ) {
                val cachedArtUri = artworkStorage.getLocalArtworkUri(currentTrack.albumId)
                if (cachedArtUri != null) {
                    currentTrack = currentTrack.copy(artworkUri = cachedArtUri)
                    trackDao.update(currentTrack)
                }
            }
        }

        // Check if fully complete after artwork reuse
        if (!forceRefresh && !needsEnrichment(currentTrack)) {
            if (currentTrack.metadataStatus != MetadataStatus.COMPLETE) {
                val updated = currentTrack.copy(
                    metadataStatus = MetadataStatus.COMPLETE,
                    metadataConfidence = MetadataConfidence.HIGH,
                    metadataLastUpdated = System.currentTimeMillis()
                )
                trackDao.update(updated)
                return updated
            }
            return currentTrack
        }

        // Step 2: Check network availability
        if (!isNetworkAvailable()) {
            Log.d(TAG, "Network unavailable. Marking track ${track.id} as NEEDS_LOOKUP.")
            val offlineTrack = currentTrack.copy(
                metadataStatus = MetadataStatus.NEEDS_LOOKUP,
                metadataLastUpdated = System.currentTimeMillis()
            )
            trackDao.update(offlineTrack)
            return offlineTrack
        }

        // Step 3: Construct search query using cleaned artist and title hints
        val query = buildSearchQuery(currentTrack)
        if (query.isBlank()) {
            Log.d(TAG, "Cannot construct query for track ${currentTrack.id}.")
            return currentTrack
        }

        Log.i("DIAG_METADATA", "[Point 16] Exact iTunes query generated: '$query'")
        val searchResults = try {
            metadataProvider.searchTrack(query, limit = 5)
        } catch (e: Exception) {
            Log.w(TAG, "Provider lookup failed for '$query': ${e.message}")
            if (e.message?.contains("rate limit", ignoreCase = true) == true) {
                delay(3000L)
            }
            val errorTrack = currentTrack.copy(
                metadataStatus = MetadataStatus.NEEDS_LOOKUP,
                metadataLastUpdated = System.currentTimeMillis()
            )
            trackDao.update(errorTrack)
            return errorTrack
        }

        Log.i("DIAG_METADATA", "[Point 17] iTunes HTTP response/result count: ${searchResults.size}")

        if (searchResults.isEmpty()) {
            Log.d(TAG, "No matches found for query: '$query'")
            val notFoundTrack = currentTrack.copy(
                metadataStatus = MetadataStatus.FAILED,
                metadataLastUpdated = System.currentTimeMillis()
            )
            trackDao.update(notFoundTrack)
            return notFoundTrack
        }

        // Step 4: Evaluate candidates and calculate match confidence
        val (bestMatch, confidence) = findBestMatch(currentTrack, searchResults)
        Log.i("DIAG_METADATA", "[Point 18] Selected iTunes result: '${bestMatch?.title}' by '${bestMatch?.artist}'")
        Log.i("DIAG_METADATA", "[Point 19] Calculated confidence: $confidence")

        if (bestMatch == null || confidence == MetadataConfidence.LOW) {
            Log.d(TAG, "Match confidence is LOW. Retaining existing metadata and marking NEEDS_REVIEW.")
            val reviewTrack = currentTrack.copy(
                metadataStatus = MetadataStatus.NEEDS_REVIEW,
                metadataConfidence = MetadataConfidence.LOW,
                metadataLastUpdated = System.currentTimeMillis()
            )
            trackDao.update(reviewTrack)
            return reviewTrack
        }

        // Step 5: Merge metadata (strictly preserving genuine embedded fields)
        val enriched = mergeMetadata(currentTrack, bestMatch, confidence)

        // Step 6: Persist track update in Room
        Log.i("DIAG_METADATA", "[Point 24] Track ID being updated in Room: ${enriched.id}")
        trackDao.update(enriched)
        Log.i("DIAG_METADATA", "[Point 25] Final Track object after Room update: $enriched")

        // Step 7: Synchronize Album and Artist entities in Room
        updateAlbumAndArtistEntities(enriched)

        return enriched
    }

    /**
     * Builds the most specific search query possible from available metadata and filename hints.
     */
    fun buildSearchQuery(track: Track): String {
        val hints = MetadataUtils.parseFilenameHints(track.path ?: track.uri)
        val hasArtist = !MetadataUtils.isPlaceholderArtist(track.artist)
        val hasTitle = !MetadataUtils.isPlaceholderTitle(track.title)

        return when {
            hints.artistHint != null && hints.titleHint.isNotBlank() ->
                "${hints.artistHint} ${hints.titleHint}"
            hasArtist && hasTitle ->
                "${MetadataUtils.cleanNoise(track.artist)} ${MetadataUtils.cleanNoise(track.title)}"
            hints.titleHint.isNotBlank() ->
                hints.titleHint
            hasTitle ->
                MetadataUtils.cleanNoise(track.title)
            else ->
                hints.cleanSearchQuery
        }.trim()
    }

    /**
     * Scores candidates and assigns a Confidence level (HIGH, MEDIUM, LOW).
     */
    fun findBestMatch(
        track: Track,
        candidates: List<RemoteTrackMetadata>
    ): Pair<RemoteTrackMetadata?, String> {
        if (candidates.isEmpty()) return Pair(null, MetadataConfidence.LOW)

        var bestCandidate: RemoteTrackMetadata? = null
        var highestScore = -100

        val hints = MetadataUtils.parseFilenameHints(track.path ?: track.uri)
        val targetArtist = if (!MetadataUtils.isPlaceholderArtist(track.artist)) track.artist else hints.artistHint
        val targetTitle = if (!MetadataUtils.isPlaceholderTitle(track.title)) track.title else hints.titleHint

        val targetTitleNorm = MetadataUtils.normalizeString(targetTitle ?: "")
        val targetArtistNorm = MetadataUtils.normalizeString(targetArtist ?: "")

        val isArtistKnown = targetArtistNorm.isNotBlank() && targetArtistNorm != "unknown artist"
        val isTitleKnown = targetTitleNorm.isNotBlank() && targetTitleNorm != "unknown track"

        for (candidate in candidates) {
            var score = 0
            val candidateTitleNorm = MetadataUtils.normalizeString(candidate.title)
            val candidateArtistNorm = MetadataUtils.normalizeString(candidate.artist)

            // Title Matching
            if (isTitleKnown) {
                when {
                    candidateTitleNorm == targetTitleNorm -> score += 50
                    candidateTitleNorm.contains(targetTitleNorm) || targetTitleNorm.contains(candidateTitleNorm) -> score += 40
                    else -> score -= 25
                }
            }

            // Artist Matching
            if (isArtistKnown) {
                when {
                    candidateArtistNorm == targetArtistNorm -> score += 40
                    candidateArtistNorm.contains(targetArtistNorm) || targetArtistNorm.contains(candidateArtistNorm) -> score += 30
                    else -> score -= 20
                }
            }

            // Duration Matching (if available)
            if (track.duration > 0 && candidate.durationMs > 0) {
                val diffSeconds = abs(track.duration - candidate.durationMs) / 1000
                when {
                    diffSeconds <= 5 -> score += 20
                    diffSeconds <= 15 -> score += 10
                    diffSeconds > 90 -> score -= 20
                }
            }

            if (score > highestScore) {
                highestScore = score
                bestCandidate = candidate
            }
        }

        val confidence = when {
            highestScore >= 45 -> MetadataConfidence.HIGH
            highestScore >= 25 -> MetadataConfidence.MEDIUM
            else -> MetadataConfidence.LOW
        }

        return Pair(bestCandidate, confidence)
    }

    /**
     * Merges remote metadata into the local Track while strictly preserving valid embedded fields.
     */
    suspend fun mergeMetadata(
        local: Track,
        remote: RemoteTrackMetadata,
        confidence: String
    ): Track {
        // Evaluate genuine embedded title:
        // If title contains " - " or "[", it came from filename fallback, NOT genuine embedded ID3 tag.
        val hasGenuineEmbeddedTitle = local.metadataSource == MetadataSource.EMBEDDED &&
                !MetadataUtils.isPlaceholderTitle(local.title) &&
                !local.title.contains(" - ")

        val hasGenuineEmbeddedArtist = local.metadataSource == MetadataSource.EMBEDDED &&
                !MetadataUtils.isPlaceholderArtist(local.artist)

        val hasGenuineEmbeddedAlbum = local.metadataSource == MetadataSource.EMBEDDED &&
                !MetadataUtils.isPlaceholderAlbum(local.albumTitle)

        val hasGenuineEmbeddedAlbumArtist = local.metadataSource == MetadataSource.EMBEDDED &&
                !MetadataUtils.isPlaceholderArtist(local.albumArtist)

        val finalTitle = if (hasGenuineEmbeddedTitle) local.title else remote.title
        val finalArtist = if (hasGenuineEmbeddedArtist) local.artist else remote.artist
        val finalAlbumTitle = if (hasGenuineEmbeddedAlbum) local.albumTitle else (remote.albumTitle ?: local.albumTitle)
        val finalAlbumArtist = if (hasGenuineEmbeddedAlbumArtist) local.albumArtist else (remote.albumArtist ?: finalArtist)
        val finalTrackNumber = if (local.trackNumber > 0) local.trackNumber else remote.trackNumber
        val finalDiscNumber = if (local.discNumber > 1) local.discNumber else remote.discNumber
        val finalYear = if (local.year > 0) local.year else remote.year
        val finalGenre = if (!local.genre.isNullOrBlank()) local.genre else remote.genre
        val finalComposer = if (!local.composer.isNullOrBlank()) local.composer else remote.composer

        // Recalculate canonical album ID based on the resolved Album Artist + Album Title
        val newAlbumId = MetadataUtils.generateAlbumId(finalAlbumArtist, finalAlbumTitle)

        // Artwork resolution: Embedded -> Local Cache for newAlbumId -> Local Cache for local.albumId -> Remote Provider -> Fallback
        var finalArtworkUri = local.artworkUri
        var artworkSavedPath: String? = null
        if (!hasValidArtwork(local)) {
            val cachedArt = artworkStorage.getLocalArtworkUri(newAlbumId)
                ?: artworkStorage.getLocalArtworkUri(local.albumId)
            if (cachedArt != null) {
                finalArtworkUri = cachedArt
                artworkSavedPath = cachedArt
            } else if (!remote.artworkUrl.isNullOrBlank()) {
                val downloaded = artworkStorage.downloadAndStoreArtwork(newAlbumId, remote.artworkUrl)
                if (downloaded != null) {
                    finalArtworkUri = downloaded
                    artworkSavedPath = downloaded
                }
            }
        } else {
            artworkSavedPath = local.artworkUri
        }

        Log.i("DIAG_METADATA", "[Point 20] Final merged: title='$finalTitle', artist='$finalArtist', album='$finalAlbumTitle', year=$finalYear, genre=$finalGenre")
        Log.i("DIAG_METADATA", "[Point 21] Artwork URL selected: ${remote.artworkUrl}")
        Log.i("DIAG_METADATA", "[Point 22] Artwork file path saved: $artworkSavedPath")
        Log.i("DIAG_METADATA", "[Point 23] Artwork URI written to Room: $finalArtworkUri")

        // Determine metadata source per Requirement 2
        val hadAnyEmbedded = hasGenuineEmbeddedTitle || hasGenuineEmbeddedArtist || hasGenuineEmbeddedAlbum || hasValidArtwork(local)
        val externalFilledSomething = (!hasGenuineEmbeddedTitle && remote.title.isNotBlank()) ||
                (!hasGenuineEmbeddedArtist && remote.artist.isNotBlank()) ||
                (!hasGenuineEmbeddedAlbum && !remote.albumTitle.isNullOrBlank()) ||
                (local.artworkUri == null && finalArtworkUri != null)

        val metadataSource = when {
            hadAnyEmbedded && externalFilledSomething -> MetadataSource.MERGED
            externalFilledSomething -> MetadataSource.EXTERNAL
            else -> MetadataSource.EMBEDDED
        }

        // Determine metadata status per Requirement 3 (all must be valid)
        val isNowComplete = !MetadataUtils.isPlaceholderTitle(finalTitle) &&
                !MetadataUtils.isPlaceholderArtist(finalArtist) &&
                !MetadataUtils.isPlaceholderAlbum(finalAlbumTitle) &&
                !MetadataUtils.isPlaceholderArtist(finalAlbumArtist) &&
                finalArtworkUri != null

        val metadataStatus = if (isNowComplete) MetadataStatus.COMPLETE else MetadataStatus.PARTIAL

        return local.copy(
            title = finalTitle,
            artist = finalArtist,
            albumArtist = finalAlbumArtist,
            albumTitle = finalAlbumTitle,
            albumId = newAlbumId,
            trackNumber = finalTrackNumber,
            discNumber = finalDiscNumber,
            year = finalYear,
            genre = finalGenre,
            composer = finalComposer,
            artworkUri = finalArtworkUri,
            metadataSource = metadataSource,
            metadataStatus = metadataStatus,
            metadataConfidence = confidence,
            metadataLastUpdated = System.currentTimeMillis()
        )
    }

    private suspend fun updateAlbumAndArtistEntities(enriched: Track) {
        try {
            // Upsert Album
            val existingAlbum = albumDao.getAlbumById(enriched.albumId)
            if (existingAlbum != null) {
                albumDao.insertAll(listOf(
                    existingAlbum.copy(
                        title = enriched.albumTitle,
                        artist = enriched.albumArtist,
                        artworkUri = existingAlbum.artworkUri ?: enriched.artworkUri,
                        year = if (existingAlbum.year == 0 && enriched.year > 0) enriched.year else existingAlbum.year
                    )
                ))
            } else {
                albumDao.insertAll(listOf(
                    Album(
                        id = enriched.albumId,
                        title = enriched.albumTitle,
                        artist = enriched.albumArtist,
                        artworkUri = enriched.artworkUri,
                        year = enriched.year,
                        trackCount = 1,
                        totalDuration = enriched.duration
                    )
                ))
            }

            // Upsert Artist (both track artist and album artist)
            val artistNames = setOf(enriched.artist, enriched.albumArtist).filter { !MetadataUtils.isPlaceholderArtist(it) }
            for (artistName in artistNames) {
                val artistId = MetadataUtils.generateArtistId(artistName)
                val existingArtist = artistDao.getArtistById(artistId)
                if (existingArtist != null) {
                    if (existingArtist.artworkUri.isNullOrBlank() && !enriched.artworkUri.isNullOrBlank()) {
                        artistDao.insertAll(listOf(existingArtist.copy(artworkUri = enriched.artworkUri)))
                    }
                } else {
                    artistDao.insertAll(listOf(
                        Artist(
                            id = artistId,
                            name = artistName,
                            artworkUri = enriched.artworkUri,
                            albumCount = 1,
                            trackCount = 1
                        )
                    ))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed updating Album or Artist entities: ${e.message}")
        }
    }

    private fun isNetworkAvailable(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
            val activeNetwork = cm.activeNetwork ?: return true
            val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return true
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            true
        }
    }

    companion object {
        private const val TAG = "MetadataEnrichment"
    }
}
