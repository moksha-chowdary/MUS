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
 *
 * Supports album-group enrichment: tracks belonging to the same album are
 * enriched together with a single lookup, ensuring consistent album identity,
 * artwork, and metadata across all tracks in the group.
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
    private var activeBatchJob: Job? = null

    /**
     * Cancels any active enrichment batch and clears in-flight track tracking.
     */
    fun cancelAllEnrichment() {
        activeBatchJob?.cancel()
        activeBatchJob = null
        inFlightTrackIds.clear()
        Log.i(TAG, "All in-flight enrichment cancelled")
    }

    // ── Album Grouping Data Structures ────────────────────────
    data class AlbumGroup(
        val groupKey: String,
        val tracks: List<Track>,
        val anchorTrack: Track,
    )

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
     * Groups tracks by album before enriching to ensure consistent album identity.
     * Non-blocking; returns immediately.
     */
    fun enqueueEnrichment(tracks: List<Track>) {
        if (!userPreferences.automaticMetadataEnabled.value) {
            Log.d(TAG, "Automatic metadata enrichment is disabled in settings.")
            return
        }

        val tracksToProcess = tracks.filter { needsEnrichment(it) }
        if (tracksToProcess.isEmpty()) return

        activeBatchJob?.cancel()
        activeBatchJob = serviceScope.launch {
            // Group tracks into album candidates for batch enrichment
            val albumGroups = groupTracksIntoAlbumCandidates(tracksToProcess)
            val ungroupedTracks = mutableListOf<Track>()

            for (group in albumGroups) {
                if (group.tracks.size >= 2) {
                    // Album group: enrich as a group
                    concurrencySemaphore.withPermit {
                        throttleRequest()
                        enrichAlbumGroup(group)
                    }
                } else {
                    // Single track: enrich individually
                    ungroupedTracks.addAll(group.tracks)
                }
            }

            // Process ungrouped/single tracks individually
            for (track in ungroupedTracks) {
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

    // ── Album Grouping ────────────────────────────────────────

    /**
     * Groups tracks into album candidates for batch enrichment.
     * Priority:
     * 1. Valid embedded albumArtist + albumTitle
     * 2. Valid albumTitle + artist information
     * 3. Canonical containing folder beneath /Muzic/ (but NOT the entire language folder)
     */
    fun groupTracksIntoAlbumCandidates(tracks: List<Track>): List<AlbumGroup> {
        val groups = mutableMapOf<String, MutableList<Track>>()

        for (track in tracks) {
            val groupKey = determineAlbumGroupKey(track)
            groups.getOrPut(groupKey) { mutableListOf() }.add(track)
        }

        return groups.map { (key, groupTracks) ->
            val anchor = selectAnchorTrack(groupTracks)
            AlbumGroup(
                groupKey = key,
                tracks = groupTracks,
                anchorTrack = anchor,
            )
        }
    }

    /**
     * Determines the album group key for a track.
     * Uses metadata when available, falls back to folder structure.
     */
    private fun determineAlbumGroupKey(track: Track): String {
        val hasAlbum = !MetadataUtils.isPlaceholderAlbum(track.albumTitle)
        val hasAlbumArtist = !MetadataUtils.isPlaceholderArtist(track.albumArtist)
        val hasArtist = !MetadataUtils.isPlaceholderArtist(track.artist)

        // 1. Best: albumArtist + albumTitle (canonical grouping)
        if (hasAlbum && hasAlbumArtist) {
            return "meta:${MetadataUtils.normalizeString(track.albumArtist)}|${MetadataUtils.normalizeString(track.albumTitle)}"
        }

        // 2. Good: albumTitle + artist
        if (hasAlbum && hasArtist) {
            return "meta:${MetadataUtils.normalizeString(track.artist)}|${MetadataUtils.normalizeString(track.albumTitle)}"
        }

        // 3. Fallback: containing folder (but NOT the language folder itself)
        val albumFolder = MetadataUtils.extractAlbumFolderPath(track.path ?: track.uri)
        if (albumFolder != null) {
            // Only use folder if it has more than one segment (i.e. not just "English" or "Hindi")
            val segments = albumFolder.split("/")
            if (segments.size >= 2) {
                return "folder:${albumFolder.lowercase()}"
            }
        }

        // 4. Last resort: unique per track
        return "single:${track.id}"
    }

    /**
     * Selects the track with the strongest metadata as the album anchor.
     * Prefers: valid embedded album → valid albumArtist → valid title → has track number.
     */
    fun selectAnchorTrack(tracks: List<Track>): Track {
        return tracks.maxByOrNull { track ->
            var score = 0
            if (!MetadataUtils.isPlaceholderAlbum(track.albumTitle)) score += 40
            if (!MetadataUtils.isPlaceholderArtist(track.albumArtist)) score += 30
            if (!MetadataUtils.isPlaceholderArtist(track.artist)) score += 20
            if (!MetadataUtils.isPlaceholderTitle(track.title)) score += 15
            if (track.trackNumber > 0) score += 10
            if (track.year > 0) score += 5
            if (hasValidArtwork(track)) score += 5
            score
        } ?: tracks.first()
    }

    /**
     * Enriches an entire album group using a single lookup.
     * 1. Uses the anchor track to identify the album
     * 2. Applies consistent album identity to all tracks in the group
     * 3. Per-track verification ensures unrelated tracks are not forced
     */
    private suspend fun enrichAlbumGroup(group: AlbumGroup) {
        val anchor = group.anchorTrack
        Log.i(TAG, "ALBUM_GROUP: Enriching group '${group.groupKey}' with ${group.tracks.size} tracks, anchor='${anchor.title}' by '${anchor.artist}'")

        // Mark all tracks as ENRICHING
        val now = System.currentTimeMillis()
        for (track in group.tracks) {
            if (inFlightTrackIds.add(track.id)) {
                trackDao.update(track.copy(
                    metadataStatus = MetadataStatus.ENRICHING,
                    metadataLastUpdated = now,
                ))
            }
        }

        try {
            // Build search query from anchor
            val query = buildSearchQuery(anchor)
            if (query.isBlank()) {
                Log.d(TAG, "Cannot construct query for album group anchor ${anchor.id}")
                finishAlbumGroupTracks(group, null, null)
                return
            }

            if (!isNetworkAvailable()) {
                Log.d(TAG, "Network unavailable for album group enrichment")
                finishAlbumGroupTracks(group, null, null)
                return
            }

            Log.i("DIAG_METADATA", "ALBUM_GROUP_QUERY: '$query'")
            val searchResults = try {
                metadataProvider.searchTrack(query, limit = 10)
            } catch (e: Exception) {
                Log.w(TAG, "Provider lookup failed for album group '$query': ${e.message}")
                if (e.message?.contains("rate limit", ignoreCase = true) == true) {
                    delay(3000L)
                }
                finishAlbumGroupTracks(group, null, null)
                return
            }

            if (searchResults.isEmpty()) {
                finishAlbumGroupTracks(group, null, null)
                return
            }

            // Find best album match using anchor
            val (bestMatch, confidence) = findBestMatch(anchor, searchResults)

            if (bestMatch == null || confidence == MetadataConfidence.LOW) {
                Log.d(TAG, "ALBUM_GROUP: Low confidence match for group '${group.groupKey}'")
                // Mark all tracks as NEEDS_REVIEW
                for (track in group.tracks) {
                    val current = trackDao.getTrackById(track.id) ?: track
                    trackDao.update(current.copy(
                        metadataStatus = MetadataStatus.NEEDS_REVIEW,
                        metadataConfidence = MetadataConfidence.LOW,
                        metadataLastUpdated = System.currentTimeMillis(),
                    ))
                    inFlightTrackIds.remove(track.id)
                }
                return
            }

            // Apply album-level metadata to all verified tracks
            val albumTitle = bestMatch.albumTitle ?: anchor.albumTitle
            val albumArtist = bestMatch.albumArtist ?: bestMatch.artist
            val canonicalAlbumId = MetadataUtils.generateAlbumId(albumArtist, albumTitle)
            val albumYear = if (bestMatch.year > 0) bestMatch.year else anchor.year

            // Download album artwork once for the entire group
            var albumArtworkUri: String? = artworkStorage.getLocalArtworkUri(canonicalAlbumId)
            if (albumArtworkUri == null && !bestMatch.artworkUrl.isNullOrBlank()) {
                albumArtworkUri = artworkStorage.downloadAndStoreArtwork(canonicalAlbumId, bestMatch.artworkUrl)
            }

            Log.i("DIAG_METADATA", "ALBUM_GROUP_RESULT: album='$albumTitle', artist='$albumArtist', albumId=$canonicalAlbumId, artwork=$albumArtworkUri")

            // Apply to each track with per-track verification
            for (track in group.tracks) {
                try {
                    val current = trackDao.getTrackById(track.id) ?: track
                    val isCompatible = verifyTrackBelongsToAlbum(current, bestMatch, searchResults)

                    if (isCompatible) {
                        val enriched = applyAlbumGroupMetadata(
                            current, bestMatch, canonicalAlbumId, albumTitle, albumArtist,
                            albumYear, albumArtworkUri, confidence
                        )
                        trackDao.update(enriched)
                        updateAlbumAndArtistEntities(enriched)
                        Log.i("DIAG_METADATA", "ALBUM_GROUP_TRACK_OK: track=${enriched.id} title='${enriched.title}' -> album='${enriched.albumTitle}'")
                    } else {
                        // Track doesn't belong — enrich individually or mark for review
                        Log.i("DIAG_METADATA", "ALBUM_GROUP_TRACK_REJECT: track=${current.id} title='${current.title}' doesn't match album '$albumTitle'")
                        enrichTrackInternal(current, forceRefresh = false)
                    }
                } finally {
                    inFlightTrackIds.remove(track.id)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Album group enrichment failed: ${e.message}")
            finishAlbumGroupTracks(group, null, null)
        }
    }

    private suspend fun finishAlbumGroupTracks(group: AlbumGroup, status: String?, confidence: String?) {
        for (track in group.tracks) {
            try {
                val current = trackDao.getTrackById(track.id) ?: track
                trackDao.update(current.copy(
                    metadataStatus = status ?: MetadataStatus.FAILED,
                    metadataConfidence = confidence ?: MetadataConfidence.LOW,
                    metadataLastUpdated = System.currentTimeMillis(),
                ))
            } finally {
                inFlightTrackIds.remove(track.id)
            }
        }
    }

    /**
     * Verifies that a track is compatible with the album match.
     * Checks title, artist, and duration signals.
     */
    private fun verifyTrackBelongsToAlbum(track: Track, albumMatch: RemoteTrackMetadata, allResults: List<RemoteTrackMetadata>): Boolean {
        val trackTitleNorm = MetadataUtils.normalizeString(track.title)
        val trackArtistNorm = MetadataUtils.normalizeString(track.artist)

        // Check if any result from the same album matches this track
        val albumTitleNorm = MetadataUtils.normalizeString(albumMatch.albumTitle ?: "")
        val albumResults = allResults.filter { MetadataUtils.normalizeString(it.albumTitle ?: "") == albumTitleNorm }

        for (candidate in albumResults) {
            val candidateTitleNorm = MetadataUtils.normalizeString(candidate.title)
            val titleMatch = candidateTitleNorm == trackTitleNorm ||
                    candidateTitleNorm.contains(trackTitleNorm) ||
                    trackTitleNorm.contains(candidateTitleNorm)

            if (titleMatch) return true
        }

        // If the track title is a placeholder, it's compatible if artist matches
        if (MetadataUtils.isPlaceholderTitle(track.title)) {
            return !MetadataUtils.isPlaceholderArtist(track.artist) &&
                    (trackArtistNorm.contains(MetadataUtils.normalizeString(albumMatch.artist)) ||
                     MetadataUtils.normalizeString(albumMatch.artist).contains(trackArtistNorm))
        }

        // For poorly tagged files, check if file is in the same folder (already grouped)
        // and the album match seems reasonable
        val hints = MetadataUtils.parseFilenameHints(track.path ?: track.uri)
        val hintTitleNorm = MetadataUtils.normalizeString(hints.titleHint)
        for (candidate in albumResults) {
            val candidateTitleNorm = MetadataUtils.normalizeString(candidate.title)
            if (candidateTitleNorm.contains(hintTitleNorm) || hintTitleNorm.contains(candidateTitleNorm)) {
                return true
            }
        }

        return false
    }

    /**
     * Applies album-group metadata to a single track, preserving valid embedded fields.
     */
    private fun applyAlbumGroupMetadata(
        track: Track,
        match: RemoteTrackMetadata,
        albumId: Long,
        albumTitle: String,
        albumArtist: String,
        albumYear: Int,
        albumArtworkUri: String?,
        confidence: String,
    ): Track {
        val hasGenuineEmbeddedTitle = track.metadataSource == MetadataSource.EMBEDDED &&
                !MetadataUtils.isPlaceholderTitle(track.title) &&
                !track.title.contains(" - ")

        val hasGenuineEmbeddedArtist = track.metadataSource == MetadataSource.EMBEDDED &&
                !MetadataUtils.isPlaceholderArtist(track.artist)

        val hasGenuineEmbeddedAlbum = track.metadataSource == MetadataSource.EMBEDDED &&
                !MetadataUtils.isPlaceholderAlbum(track.albumTitle)

        // For title/artist: preserve embedded, use individual match if available
        val finalTitle = if (hasGenuineEmbeddedTitle) track.title else {
            if (!MetadataUtils.isPlaceholderTitle(track.title)) track.title else match.title
        }
        val finalArtist = if (hasGenuineEmbeddedArtist) track.artist else {
            if (!MetadataUtils.isPlaceholderArtist(track.artist)) track.artist else match.artist
        }

        // For album-level fields: use the group's canonical values
        val finalAlbumTitle = if (hasGenuineEmbeddedAlbum) track.albumTitle else albumTitle
        val finalAlbumArtist = albumArtist

        val finalTrackNumber = if (track.trackNumber > 0) track.trackNumber else match.trackNumber
        val finalDiscNumber = if (track.discNumber > 0) track.discNumber else match.discNumber
        val finalYear = if (track.year > 0) track.year else albumYear
        val finalGenre = if (!track.genre.isNullOrBlank()) track.genre else match.genre
        val finalComposer = if (!track.composer.isNullOrBlank()) track.composer else match.composer

        val finalArtworkUri = when {
            hasValidArtwork(track) -> track.artworkUri
            albumArtworkUri != null -> albumArtworkUri
            else -> track.artworkUri
        }

        val hadAnyEmbedded = hasGenuineEmbeddedTitle || hasGenuineEmbeddedArtist || hasGenuineEmbeddedAlbum || hasValidArtwork(track)
        val externalFilledSomething = (!hasGenuineEmbeddedTitle && match.title.isNotBlank()) ||
                (!hasGenuineEmbeddedArtist && match.artist.isNotBlank()) ||
                (!hasGenuineEmbeddedAlbum && albumTitle.isNotBlank()) ||
                (track.artworkUri == null && finalArtworkUri != null)

        val metadataSource = when {
            hadAnyEmbedded && externalFilledSomething -> MetadataSource.MERGED
            externalFilledSomething -> MetadataSource.EXTERNAL
            else -> MetadataSource.EMBEDDED
        }

        val isNowComplete = !MetadataUtils.isPlaceholderTitle(finalTitle) &&
                !MetadataUtils.isPlaceholderArtist(finalArtist) &&
                !MetadataUtils.isPlaceholderAlbum(finalAlbumTitle) &&
                !MetadataUtils.isPlaceholderArtist(finalAlbumArtist) &&
                finalArtworkUri != null

        val metadataStatus = if (isNowComplete) MetadataStatus.COMPLETE else MetadataStatus.PARTIAL

        return track.copy(
            title = finalTitle,
            artist = finalArtist,
            albumArtist = finalAlbumArtist,
            albumTitle = finalAlbumTitle,
            albumId = albumId,
            trackNumber = finalTrackNumber,
            discNumber = finalDiscNumber,
            year = finalYear,
            genre = finalGenre,
            composer = finalComposer,
            artworkUri = finalArtworkUri,
            metadataSource = metadataSource,
            metadataStatus = metadataStatus,
            metadataConfidence = confidence,
            metadataLastUpdated = System.currentTimeMillis(),
        )
    }

    // ── Individual Track Enrichment ───────────────────────────

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

        // Step 3: Construct search query — EMBEDDED METADATA FIRST, then filename hints
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
     * PRIORITY: valid embedded metadata FIRST, then filename hints as fallback.
     */
    fun buildSearchQuery(track: Track): String {
        val hasArtist = !MetadataUtils.isPlaceholderArtist(track.artist)
        val hasTitle = !MetadataUtils.isPlaceholderTitle(track.title)
        val hints = MetadataUtils.parseFilenameHints(track.path ?: track.uri)

        return when {
            // HIGHEST PRIORITY: Valid embedded metadata
            hasArtist && hasTitle ->
                "${MetadataUtils.cleanNoise(track.artist)} ${MetadataUtils.cleanNoise(track.title)}"
            // SECOND: Cleaned/normalized existing metadata + filename hints
            hasTitle && hints.artistHint != null ->
                "${hints.artistHint} ${MetadataUtils.cleanNoise(track.title)}"
            hasArtist && hints.titleHint.isNotBlank() ->
                "${MetadataUtils.cleanNoise(track.artist)} ${hints.titleHint}"
            // THIRD: Filename hints only
            hints.artistHint != null && hints.titleHint.isNotBlank() ->
                "${hints.artistHint} ${hints.titleHint}"
            hasTitle ->
                MetadataUtils.cleanNoise(track.title)
            hints.titleHint.isNotBlank() ->
                hints.titleHint
            // LAST RESORT: Whatever the filename parsing could extract
            else ->
                hints.cleanSearchQuery
        }.trim()
    }

    // ── Generic Title Detection ──────────────────────────────
    private val GENERIC_TITLES = setOf(
        "intro", "introduction", "interlude", "outro", "untitled",
        "track", "instrumental", "bonus", "skit", "prelude",
    )

    private fun isGenericTitle(title: String): Boolean {
        val norm = MetadataUtils.normalizeString(title)
        return norm in GENERIC_TITLES || norm.matches(Regex("""track\s*\d+"""))
    }

    /**
     * Scores candidates and assigns a Confidence level (HIGH, MEDIUM, LOW).
     * Requires meaningful identity evidence from at least one strong signal (title or artist).
     * Duration alone NEVER establishes identity.
     */
    fun findBestMatch(
        track: Track,
        candidates: List<RemoteTrackMetadata>
    ): Pair<RemoteTrackMetadata?, String> {
        if (candidates.isEmpty()) return Pair(null, MetadataConfidence.LOW)

        var bestCandidate: RemoteTrackMetadata? = null
        var highestScore = -100
        var bestTitleMatched = false
        var bestArtistMatched = false

        val hints = MetadataUtils.parseFilenameHints(track.path ?: track.uri)
        val targetArtist = if (!MetadataUtils.isPlaceholderArtist(track.artist)) track.artist else hints.artistHint
        val targetTitle = if (!MetadataUtils.isPlaceholderTitle(track.title)) track.title else hints.titleHint

        val targetTitleNorm = MetadataUtils.normalizeString(targetTitle ?: "")
        val targetArtistNorm = MetadataUtils.normalizeString(targetArtist ?: "")

        val isArtistKnown = targetArtistNorm.isNotBlank() && targetArtistNorm != "unknown artist"
        val isTitleKnown = targetTitleNorm.isNotBlank() && targetTitleNorm != "unknown track"

        for (candidate in candidates) {
            var score = 0
            var titleMatched = false
            var artistMatched = false
            val candidateTitleNorm = MetadataUtils.normalizeString(candidate.title)
            val candidateArtistNorm = MetadataUtils.normalizeString(candidate.artist)

            // Title Matching
            if (isTitleKnown) {
                when {
                    candidateTitleNorm == targetTitleNorm -> {
                        score += 50
                        titleMatched = true
                    }
                    candidateTitleNorm.contains(targetTitleNorm) || targetTitleNorm.contains(candidateTitleNorm) -> {
                        score += 40
                        titleMatched = true
                    }
                    else -> score -= 25
                }
            }

            // Artist Matching
            if (isArtistKnown) {
                when {
                    candidateArtistNorm == targetArtistNorm -> {
                        score += 40
                        artistMatched = true
                    }
                    candidateArtistNorm.contains(targetArtistNorm) || targetArtistNorm.contains(candidateArtistNorm) -> {
                        score += 30
                        artistMatched = true
                    }
                    else -> score -= 20
                }
            }

            // Album Matching (supporting signal)
            if (!MetadataUtils.isPlaceholderAlbum(track.albumTitle) && !candidate.albumTitle.isNullOrBlank()) {
                val trackAlbumNorm = MetadataUtils.normalizeString(track.albumTitle)
                val candidateAlbumNorm = MetadataUtils.normalizeString(candidate.albumTitle!!)
                if (trackAlbumNorm == candidateAlbumNorm || trackAlbumNorm.contains(candidateAlbumNorm) || candidateAlbumNorm.contains(trackAlbumNorm)) {
                    score += 15
                }
            }

            // Duration Matching (SUPPORTING signal, never primary)
            if (track.duration > 0 && candidate.durationMs > 0) {
                val diffSeconds = abs(track.duration - candidate.durationMs) / 1000
                when {
                    diffSeconds <= 5 -> score += 15
                    diffSeconds <= 15 -> score += 8
                    diffSeconds > 90 -> score -= 20
                }
            }

            // Generic title penalty
            if (isTitleKnown && isGenericTitle(targetTitleNorm)) {
                score -= 15
            }

            if (score > highestScore) {
                highestScore = score
                bestCandidate = candidate
                bestTitleMatched = titleMatched
                bestArtistMatched = artistMatched
            }
        }

        // MINIMUM IDENTITY REQUIREMENT:
        // At least one strong identity signal (title or artist) must have matched.
        // Duration alone is NEVER sufficient.
        val hasIdentityEvidence = bestTitleMatched || bestArtistMatched

        // Generic title without artist match cannot be HIGH confidence
        val isGenericWithoutArtist = isGenericTitle(targetTitleNorm) && !bestArtistMatched

        val confidence = when {
            !hasIdentityEvidence -> MetadataConfidence.LOW
            isGenericWithoutArtist -> if (highestScore >= 25) MetadataConfidence.MEDIUM else MetadataConfidence.LOW
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
        // FIX: discNumber > 0 (not > 1) — disc 1 is valid metadata
        val finalDiscNumber = if (local.discNumber > 0) local.discNumber else remote.discNumber
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
