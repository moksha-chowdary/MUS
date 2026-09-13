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

            // Download album artwork once for the entire group ONLY if anchor match was HIGH confidence
            var albumArtworkUri: String? = null
            if (confidence == MetadataConfidence.HIGH && !bestMatch.artworkUrl.isNullOrBlank()) {
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
        val targetTitle = if (!MetadataUtils.isPlaceholderTitle(track.title)) track.title else {
            MetadataUtils.parseFilenameHints(track.path ?: track.uri).titleHint
        }
        if (MetadataUtils.isPlaceholderTitle(targetTitle)) return false

        val albumTitleNorm = MetadataUtils.normalizeForComparison(albumMatch.albumTitle ?: "")
        val albumResults = allResults.filter { MetadataUtils.normalizeForComparison(it.albumTitle ?: "") == albumTitleNorm }

        for (candidate in albumResults) {
            if (MetadataUtils.isTitleMatch(targetTitle, candidate.title) &&
                MetadataUtils.areVersionsCompatible(targetTitle, candidate.title)) {
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

        val hasEmbeddedArt = !track.artworkUri.isNullOrBlank() &&
                (ArtworkStorage.isEmbeddedArtwork(track.artworkUri) || track.metadataSource == MetadataSource.EMBEDDED)

        val finalArtworkUri = when {
            hasEmbeddedArt -> track.artworkUri // EMBEDDED ARTWORK MUST ALWAYS WIN!
            confidence == MetadataConfidence.HIGH && albumArtworkUri != null -> albumArtworkUri
            else -> null // MISSING COVER IS PREFERRED OVER WRONG COVER
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

        var currentTrack = track

        // Check network availability
        if (!isNetworkAvailable()) {
            Log.d(TAG, "Network unavailable. Marking track ${track.id} as NEEDS_LOOKUP.")
            val offlineTrack = currentTrack.copy(
                metadataStatus = MetadataStatus.NEEDS_LOOKUP,
                metadataLastUpdated = System.currentTimeMillis()
            )
            trackDao.update(offlineTrack)
            return offlineTrack
        }

        // Generate progressively more precise search queries
        val queries = buildProgressiveQueries(currentTrack)
        if (queries.isEmpty()) {
            Log.d(TAG, "Cannot construct valid query for track ${currentTrack.id}.")
            return currentTrack
        }

        var searchResults = emptyList<RemoteTrackMetadata>()
        var winningQuery = ""
        for (q in queries) {
            Log.i("DIAG_METADATA", "[Point 16] Progressive query attempted: '$q'")
            try {
                val results = metadataProvider.searchTrack(q, limit = 5)
                if (results.isNotEmpty()) {
                    searchResults = results
                    winningQuery = q
                    break
                }
            } catch (e: Exception) {
                Log.w(TAG, "Provider lookup failed for '$q': ${e.message}")
                if (e.message?.contains("rate limit", ignoreCase = true) == true) {
                    delay(3000L)
                }
            }
        }

        Log.i("DIAG_METADATA", "[Point 17] iTunes HTTP response/result count: ${searchResults.size} for winning query: '$winningQuery'")

        if (searchResults.isEmpty()) {
            Log.d(TAG, "No matches found for queries on track: ${currentTrack.id}")
            logMatchAudit(
                trackId = currentTrack.id,
                localTitle = currentTrack.title,
                localArtist = currentTrack.artist,
                localAlbum = currentTrack.albumTitle,
                localAlbumArtist = currentTrack.albumArtist,
                query = queries.firstOrNull() ?: "",
                candidateTitle = "NONE",
                candidateArtist = "NONE",
                candidateAlbum = "NONE",
                candidateAlbumArtist = "NONE",
                titleScore = 0, artistScore = 0, albumScore = 0, durationScore = 0, versionScore = 0,
                identityGatePassed = false,
                finalConfidence = MetadataConfidence.LOW,
                accepted = false,
                reason = "NO_CANDIDATES"
            )
            val notFoundTrack = currentTrack.copy(
                metadataStatus = MetadataStatus.NEEDS_REVIEW,
                metadataConfidence = MetadataConfidence.LOW,
                metadataLastUpdated = System.currentTimeMillis()
            )
            trackDao.update(notFoundTrack)
            return notFoundTrack
        }

        // Evaluate candidates through strict two-stage Identity Gate + Scoring
        val (bestMatch, confidence) = findBestMatch(currentTrack, searchResults, winningQuery)
        Log.i("DIAG_METADATA", "[Point 18] Selected iTunes result: '${bestMatch?.title}' by '${bestMatch?.artist}'")
        Log.i("DIAG_METADATA", "[Point 19] Calculated confidence: $confidence")

        // WRONG MATCH / UNCERTAIN MATCH = NO MATCH
        if (bestMatch == null || confidence != MetadataConfidence.HIGH) {
            Log.d(TAG, "No HIGH-confidence match for track ${currentTrack.id} (confidence=$confidence). Setting NEEDS_REVIEW.")
            val reviewTrack = currentTrack.copy(
                metadataStatus = MetadataStatus.NEEDS_REVIEW,
                metadataConfidence = confidence,
                metadataLastUpdated = System.currentTimeMillis()
            )
            trackDao.update(reviewTrack)
            return reviewTrack
        }

        // Merge metadata (strictly preserving genuine embedded fields & embedded artwork)
        val enriched = mergeMetadata(currentTrack, bestMatch, confidence)

        // Persist track update in Room
        Log.i("DIAG_METADATA", "[Point 24] Track ID being updated in Room: ${enriched.id}")
        trackDao.update(enriched)
        Log.i("DIAG_METADATA", "[Point 25] Final Track object after Room update: $enriched")

        // Synchronize Album and Artist entities in Room ONLY for genuine non-placeholder albums
        updateAlbumAndArtistEntities(enriched)

        return enriched
    }

    /**
     * Builds progressively more precise search queries.
     * Query 1: artist + title + album (if album is known and not placeholder)
     * Query 2: artist + title
     * Query 3: title + artist normalized
     * Query 4: title only (if artist is unavailable)
     */
    fun buildProgressiveQueries(track: Track): List<String> {
        val queries = mutableListOf<String>()
        val hasGenuineArtist = !MetadataUtils.isPlaceholderArtist(track.artist)
        val hasGenuineTitle = !MetadataUtils.isPlaceholderTitle(track.title)
        val hasGenuineAlbum = !MetadataUtils.isPlaceholderAlbum(track.albumTitle)
        val hints = MetadataUtils.parseFilenameHints(track.path ?: track.uri)

        val effectiveArtist = if (hasGenuineArtist) track.artist else hints.artistHint
        val effectiveTitle = if (hasGenuineTitle) track.title else hints.titleHint

        if (effectiveArtist.isNullOrBlank() && effectiveTitle.isNullOrBlank()) {
            return emptyList()
        }

        // Query 1: artist + title + album (if genuine album is known)
        if (!effectiveArtist.isNullOrBlank() && !effectiveTitle.isNullOrBlank() && hasGenuineAlbum) {
            val q1 = "${MetadataUtils.cleanNoise(effectiveArtist)} ${MetadataUtils.cleanNoise(effectiveTitle)} ${MetadataUtils.cleanNoise(track.albumTitle)}".trim()
            if (q1.isNotBlank()) queries.add(q1)
        }

        // Query 2: artist + title
        if (!effectiveArtist.isNullOrBlank() && !effectiveTitle.isNullOrBlank()) {
            val q2 = "${MetadataUtils.cleanNoise(effectiveArtist)} ${MetadataUtils.cleanNoise(effectiveTitle)}".trim()
            if (q2.isNotBlank() && q2 !in queries) queries.add(q2)
        }

        // Query 3: title + artist normalized
        if (!effectiveArtist.isNullOrBlank() && !effectiveTitle.isNullOrBlank()) {
            val q3 = "${MetadataUtils.normalizeForComparison(effectiveTitle)} ${MetadataUtils.normalizeForComparison(effectiveArtist)}".trim()
            if (q3.isNotBlank() && q3 !in queries) queries.add(q3)
        }

        // Query 4: title only (fallback if artist unknown)
        if (queries.isEmpty() && !effectiveTitle.isNullOrBlank() && !MetadataUtils.isPlaceholderTitle(effectiveTitle)) {
            val q4 = MetadataUtils.cleanNoise(effectiveTitle).trim()
            if (q4.isNotBlank()) queries.add(q4)
        }

        return queries
    }

    /**
     * Legacy buildSearchQuery returning primary query.
     */
    fun buildSearchQuery(track: Track): String {
        return buildProgressiveQueries(track).firstOrNull() ?: ""
    }

    // ── Generic Title Detection ──────────────────────────────
    private val GENERIC_TITLES = setOf(
        "intro", "introduction", "interlude", "outro", "untitled",
        "track", "instrumental", "bonus", "skit", "prelude",
    )

    private fun isGenericTitle(title: String): Boolean {
        val norm = MetadataUtils.normalizeForComparison(title)
        return norm in GENERIC_TITLES || norm.matches(Regex("""track\s*\d+"""))
    }

    /**
     * Evaluates candidate tracks using a strict two-stage process:
     * Stage 1: Hard Identity Gate (title match + artist match + version compatibility).
     * Stage 2: Score ranking among viable candidates only.
     * Duration can NEVER establish identity.
     */
    fun findBestMatch(
        track: Track,
        candidates: List<RemoteTrackMetadata>,
        queryUsed: String = ""
    ): Pair<RemoteTrackMetadata?, String> {
        if (candidates.isEmpty()) {
            logMatchAudit(
                trackId = track.id,
                localTitle = track.title,
                localArtist = track.artist,
                localAlbum = track.albumTitle,
                localAlbumArtist = track.albumArtist,
                query = queryUsed,
                candidateTitle = "NONE",
                candidateArtist = "NONE",
                candidateAlbum = "NONE",
                candidateAlbumArtist = "NONE",
                titleScore = 0, artistScore = 0, albumScore = 0, durationScore = 0, versionScore = 0,
                identityGatePassed = false,
                finalConfidence = MetadataConfidence.LOW,
                accepted = false,
                reason = "NO_CANDIDATES"
            )
            return Pair(null, MetadataConfidence.LOW)
        }

        val hints = MetadataUtils.parseFilenameHints(track.path ?: track.uri)
        val targetArtist = if (!MetadataUtils.isPlaceholderArtist(track.artist)) track.artist else hints.artistHint
        val targetTitle = if (!MetadataUtils.isPlaceholderTitle(track.title)) track.title else hints.titleHint

        val isArtistKnown = !targetArtist.isNullOrBlank() && !MetadataUtils.isPlaceholderArtist(targetArtist)
        val isTitleKnown = !targetTitle.isNullOrBlank() && !MetadataUtils.isPlaceholderTitle(targetTitle)

        if (!isTitleKnown) {
            logMatchAudit(
                trackId = track.id,
                localTitle = track.title,
                localArtist = track.artist,
                localAlbum = track.albumTitle,
                localAlbumArtist = track.albumArtist,
                query = queryUsed,
                candidateTitle = candidates.firstOrNull()?.title ?: "",
                candidateArtist = candidates.firstOrNull()?.artist ?: "",
                candidateAlbum = candidates.firstOrNull()?.albumTitle ?: "",
                candidateAlbumArtist = candidates.firstOrNull()?.albumArtist ?: "",
                titleScore = 0, artistScore = 0, albumScore = 0, durationScore = 0, versionScore = 0,
                identityGatePassed = false,
                finalConfidence = MetadataConfidence.LOW,
                accepted = false,
                reason = "UNKNOWN_TITLE"
            )
            return Pair(null, MetadataConfidence.LOW)
        }

        data class EvaluatedCandidate(
            val candidate: RemoteTrackMetadata,
            val titleScore: Int,
            val artistScore: Int,
            val albumScore: Int,
            val durationScore: Int,
            val versionScore: Int,
            val totalScore: Int,
            val identityGatePassed: Boolean,
            val rejectReason: String
        )

        val evaluated = mutableListOf<EvaluatedCandidate>()

        for (candidate in candidates) {
            // STAGE 1: HARD IDENTITY GATE
            val titlePass = MetadataUtils.isTitleMatch(targetTitle, candidate.title)
            val artistPass = if (isArtistKnown) {
                MetadataUtils.isArtistMatch(
                    localArtist = targetArtist,
                    candidateArtist = candidate.artist,
                    candidateAlbumArtist = candidate.albumArtist,
                    localComposer = track.composer
                )
            } else {
                !isGenericTitle(targetTitle) &&
                (MetadataUtils.normalizeForComparison(targetTitle) == MetadataUtils.normalizeForComparison(candidate.title))
            }
            val versionPass = MetadataUtils.areVersionsCompatible(targetTitle, candidate.title)

            val rejectReason = when {
                !titlePass -> "TITLE_MISMATCH"
                !artistPass -> "ARTIST_MISMATCH"
                !versionPass -> "VERSION_MISMATCH"
                else -> ""
            }

            val gatePassed = titlePass && artistPass && versionPass

            // STAGE 2: SCORING (FOR RANKING SURVIVORS)
            val normTargetTitle = MetadataUtils.normalizeForComparison(targetTitle)
            val normCandTitle = MetadataUtils.normalizeForComparison(candidate.title)
            val titleScore = if (titlePass) {
                if (normTargetTitle == normCandTitle) 50 else 40
            } else -25

            val normTargetArtist = if (isArtistKnown) MetadataUtils.normalizeForComparison(targetArtist) else ""
            val normCandArtist = MetadataUtils.normalizeForComparison(candidate.artist)
            val artistScore = if (artistPass) {
                if (normTargetArtist.isNotBlank() && normTargetArtist == normCandArtist) 40 else 30
            } else -20

            var albumScore = 0
            if (!MetadataUtils.isPlaceholderAlbum(track.albumTitle) && !candidate.albumTitle.isNullOrBlank()) {
                val normTrackAlbum = MetadataUtils.normalizeForComparison(track.albumTitle)
                val normCandAlbum = MetadataUtils.normalizeForComparison(candidate.albumTitle!!)
                if (normTrackAlbum == normCandAlbum) {
                    albumScore = 20
                } else if (normTrackAlbum.contains(normCandAlbum) || normCandAlbum.contains(normTrackAlbum)) {
                    albumScore = 10
                }
            }

            var durationScore = 0
            if (track.duration > 0 && candidate.durationMs > 0) {
                val diffSeconds = abs(track.duration - candidate.durationMs) / 1000
                when {
                    diffSeconds <= 5 -> durationScore = 15
                    diffSeconds <= 15 -> durationScore = 8
                    diffSeconds > 90 -> durationScore = -20
                }
            }

            val versionScore = if (!versionPass) -30 else 0
            val totalScore = titleScore + artistScore + albumScore + durationScore + versionScore

            evaluated.add(
                EvaluatedCandidate(
                    candidate = candidate,
                    titleScore = titleScore,
                    artistScore = artistScore,
                    albumScore = albumScore,
                    durationScore = durationScore,
                    versionScore = versionScore,
                    totalScore = totalScore,
                    identityGatePassed = gatePassed,
                    rejectReason = rejectReason
                )
            )
        }

        val viable = evaluated.filter { it.identityGatePassed }

        if (viable.isEmpty()) {
            val primary = evaluated.maxByOrNull { it.totalScore } ?: evaluated.first()
            logMatchAudit(
                trackId = track.id,
                localTitle = targetTitle,
                localArtist = targetArtist ?: "",
                localAlbum = track.albumTitle,
                localAlbumArtist = track.albumArtist,
                query = queryUsed,
                candidateTitle = primary.candidate.title,
                candidateArtist = primary.candidate.artist,
                candidateAlbum = primary.candidate.albumTitle ?: "",
                candidateAlbumArtist = primary.candidate.albumArtist ?: "",
                titleScore = primary.titleScore,
                artistScore = primary.artistScore,
                albumScore = primary.albumScore,
                durationScore = primary.durationScore,
                versionScore = primary.versionScore,
                identityGatePassed = false,
                finalConfidence = MetadataConfidence.LOW,
                accepted = false,
                reason = primary.rejectReason.ifBlank { "INSUFFICIENT_IDENTITY" }
            )
            return Pair(null, MetadataConfidence.LOW)
        }

        // Rank viable candidates by totalScore
        val winner = viable.maxByOrNull { it.totalScore }!!

        val confidence = when {
            isArtistKnown && winner.totalScore >= 70 -> MetadataConfidence.HIGH
            isArtistKnown && winner.totalScore >= 50 -> MetadataConfidence.MEDIUM
            !isArtistKnown && winner.totalScore >= 65 -> MetadataConfidence.MEDIUM
            else -> MetadataConfidence.LOW
        }

        val accepted = (confidence == MetadataConfidence.HIGH)
        val reason = if (accepted) "IDENTITY_VERIFIED" else "LOW_CONFIDENCE"

        logMatchAudit(
            trackId = track.id,
            localTitle = targetTitle,
            localArtist = targetArtist ?: "",
            localAlbum = track.albumTitle,
            localAlbumArtist = track.albumArtist,
            query = queryUsed,
            candidateTitle = winner.candidate.title,
            candidateArtist = winner.candidate.artist,
            candidateAlbum = winner.candidate.albumTitle ?: "",
            candidateAlbumArtist = winner.candidate.albumArtist ?: "",
            titleScore = winner.titleScore,
            artistScore = winner.artistScore,
            albumScore = winner.albumScore,
            durationScore = winner.durationScore,
            versionScore = winner.versionScore,
            identityGatePassed = true,
            finalConfidence = confidence,
            accepted = accepted,
            reason = reason
        )

        return Pair(winner.candidate, confidence)
    }

    private fun logMatchAudit(
        trackId: Long,
        localTitle: String,
        localArtist: String,
        localAlbum: String,
        localAlbumArtist: String,
        query: String,
        candidateTitle: String,
        candidateArtist: String,
        candidateAlbum: String,
        candidateAlbumArtist: String,
        titleScore: Int,
        artistScore: Int,
        albumScore: Int,
        durationScore: Int,
        versionScore: Int,
        identityGatePassed: Boolean,
        finalConfidence: String,
        accepted: Boolean,
        reason: String
    ) {
        val total = titleScore + artistScore + albumScore + durationScore + versionScore
        Log.i("DIAG_MATCH", "=== DIAG_MATCH trackId=$trackId ===")
        Log.i("DIAG_MATCH", "LOCAL: title='$localTitle' artist='$localArtist' album='$localAlbum' albumArtist='$localAlbumArtist'")
        Log.i("DIAG_MATCH", "QUERY: '$query'")
        Log.i("DIAG_MATCH", "CANDIDATE: title='$candidateTitle' artist='$candidateArtist' album='$candidateAlbum' albumArtist='$candidateAlbumArtist'")
        Log.i("DIAG_MATCH", "SCORES: title=$titleScore artist=$artistScore album=$albumScore duration=$durationScore version=$versionScore total=$total")
        Log.i("DIAG_MATCH", "IDENTITY_GATE=${if (identityGatePassed) "PASS" else "FAIL"}")
        Log.i("DIAG_MATCH", "FINAL_CONFIDENCE=$finalConfidence")
        Log.i("DIAG_MATCH", "ACCEPTED=$accepted")
        Log.i("DIAG_MATCH", "REASON=$reason")
    }

    /**
     * Merges remote metadata into the local Track while strictly preserving valid embedded fields.
     */
    suspend fun mergeMetadata(
        local: Track,
        remote: RemoteTrackMetadata,
        confidence: String
    ): Track {
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
        val finalDiscNumber = if (local.discNumber > 0) local.discNumber else remote.discNumber
        val finalYear = if (local.year > 0) local.year else remote.year
        val finalGenre = if (!local.genre.isNullOrBlank()) local.genre else remote.genre
        val finalComposer = if (!local.composer.isNullOrBlank()) local.composer else remote.composer

        // Recalculate canonical album ID based on the resolved Album Artist + Album Title
        val newAlbumId = if (!MetadataUtils.isPlaceholderAlbum(finalAlbumTitle) && !MetadataUtils.isPlaceholderArtist(finalAlbumArtist)) {
            MetadataUtils.generateAlbumId(finalAlbumArtist, finalAlbumTitle)
        } else {
            local.id
        }

        // ARTWORK ACCEPTANCE GATE:
        // 1. Embedded artwork MUST ALWAYS WIN: never replace good embedded artwork with remote iTunes artwork
        // 2. Remote artwork accepted ONLY IF confidence == HIGH
        // 3. Otherwise: artworkUri = null
        val hasEmbeddedArt = !local.artworkUri.isNullOrBlank() &&
                (ArtworkStorage.isEmbeddedArtwork(local.artworkUri) || local.metadataSource == MetadataSource.EMBEDDED)

        var finalArtworkUri: String? = null
        var artworkSavedPath: String? = null

        if (hasEmbeddedArt && ArtworkStorage.isArtworkValid(local.artworkUri)) {
            finalArtworkUri = local.artworkUri
            artworkSavedPath = local.artworkUri
            Log.i("DIAG_METADATA", "ARTWORK_GATE: Keeping genuine embedded artwork: $finalArtworkUri")
        } else if (confidence == MetadataConfidence.HIGH && !remote.artworkUrl.isNullOrBlank()) {
            val downloaded = artworkStorage.downloadAndStoreArtwork(newAlbumId, remote.artworkUrl)
            if (downloaded != null) {
                finalArtworkUri = downloaded
                artworkSavedPath = downloaded
                Log.i("DIAG_METADATA", "ARTWORK_GATE: High confidence match accepted, downloaded artwork: $finalArtworkUri")
            }
        } else {
            Log.i("DIAG_METADATA", "ARTWORK_GATE: Confidence is not HIGH ($confidence). No artwork attached.")
            finalArtworkUri = null
        }

        Log.i("DIAG_METADATA", "[Point 20] Final merged: title='$finalTitle', artist='$finalArtist', album='$finalAlbumTitle', year=$finalYear, genre=$finalGenre")
        Log.i("DIAG_METADATA", "[Point 21] Artwork URL selected: ${remote.artworkUrl}")
        Log.i("DIAG_METADATA", "[Point 22] Artwork file path saved: $artworkSavedPath")
        Log.i("DIAG_METADATA", "[Point 23] Artwork URI written to Room: $finalArtworkUri")

        val hadAnyEmbedded = hasGenuineEmbeddedTitle || hasGenuineEmbeddedArtist || hasGenuineEmbeddedAlbum || hasEmbeddedArt
        val externalFilledSomething = (!hasGenuineEmbeddedTitle && remote.title.isNotBlank()) ||
                (!hasGenuineEmbeddedArtist && remote.artist.isNotBlank()) ||
                (!hasGenuineEmbeddedAlbum && !remote.albumTitle.isNullOrBlank()) ||
                (local.artworkUri == null && finalArtworkUri != null)

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
            // Do NOT insert placeholder albums (e.g. language folders, unknown album) into Room albums table
            if (!MetadataUtils.isPlaceholderAlbum(enriched.albumTitle) && !MetadataUtils.isPlaceholderArtist(enriched.albumArtist)) {
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
