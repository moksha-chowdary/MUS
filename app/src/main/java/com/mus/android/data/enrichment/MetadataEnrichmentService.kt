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
import com.mus.android.data.classifier.LanguageClassifier
import com.mus.android.data.model.*
import com.mus.android.data.repository.UserPreferencesRepository
import com.mus.android.data.scanner.MetadataUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import java.util.Locale
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

    // iTunes Search is an unauthenticated endpoint limited to roughly 20 calls/minute.
    // The previous 600ms gap allowed ~100/minute, i.e. about 5x over the limit, so a
    // library scan reliably tripped HTTP 403 and stopped returning artwork partway through.
    @Volatile private var backoffUntil = 0L

    private suspend fun throttleRequest() {
        val cooldown = backoffUntil - System.currentTimeMillis()
        if (cooldown > 0L) delay(cooldown)

        val waitTime = synchronized(rateLimitLock) {
            val now = System.currentTimeMillis()
            val elapsed = now - lastRequestTime
            if (elapsed < MIN_REQUEST_GAP_MS) {
                val toWait = MIN_REQUEST_GAP_MS - elapsed
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

    /** Pauses all provider traffic after Apple rejects us, so one 403 doesn't cascade. */
    private fun enterBackoff() {
        backoffUntil = System.currentTimeMillis() + RATE_LIMIT_COOLDOWN_MS
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
        // Must go through the SAME semaphore + throttle as the automatic path.
        // "Refresh All Metadata" loops this over the whole library, so without the
        // throttle it fires unlimited back-to-back iTunes requests, Apple returns
        // HTTP 403/429 for the rest of the run, and no artwork downloads at all.
        concurrencySemaphore.withPermit {
            throttleRequest()
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
    }

    /**
     * Determines whether a track is missing essential metadata or artwork.
     * Independently evaluates title, artist, album, album artist, and artwork.
     * Having artwork alone NEVER marks a track complete if text metadata is missing.
     */
    fun needsEnrichment(track: Track): Boolean {
        when (track.metadataStatus) {
            MetadataStatus.COMPLETE -> {
                // If it was marked COMPLETE without HIGH confidence, it must be re-evaluated
                if (track.metadataConfidence != MetadataConfidence.HIGH) return true
                return false
            }
            MetadataStatus.NEEDS_REVIEW -> return true // Re-evaluate rather than freezing bad rows
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
        // CRITICAL: Never overwrite artwork with MANUAL provenance — the user explicitly chose it.
        val hasManualArtwork = track.artworkSource == com.mus.android.data.model.ArtworkSource.MANUAL
        var currentTrack = track
        if (!hasValidArtwork(currentTrack) && !hasManualArtwork) {
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

        // Step 3: Build an ordered list of queries to try. The first entry is exactly the
        // query this code has always produced, so normal behaviour is unchanged. The extra
        // entry is a bare song-title retry, used only when the specific query finds nothing
        // usable — it rescues downloaded files whose filename carries a site name, bitrate
        // or uploader tag next to the real title.
        val queries = buildSearchQueries(currentTrack)
        if (queries.isEmpty()) {
            Log.d(TAG, "Cannot construct query for track ${currentTrack.id}.")
            return currentTrack
        }

        val regionalCountry = determineCountryForTrack(currentTrack)
        val countriesToTry = if (regionalCountry.equals("US", ignoreCase = true)) {
            listOf("US")
        } else {
            listOf(regionalCountry, "US")
        }

        var bestMatch: RemoteTrackMetadata? = null
        var confidence = MetadataConfidence.LOW
        var sawAnyResult = false
        var attemptCount = 0

        searchLoop@ for (country in countriesToTry) {
            for (query in queries) {
                if (attemptCount > 0) throttleRequest()
                attemptCount++
                Log.i("DIAG_METADATA", "[Point 16] iTunes query attempt $attemptCount (country=$country): '$query'")

                val searchResults = try {
                    metadataProvider.searchTrack(query, limit = 5, country = country)
                } catch (e: Exception) {
                    Log.w(TAG, "Provider lookup failed for '$query' in $country: ${e.message}")
                    if (e.message?.contains("rate limit", ignoreCase = true) == true) {
                        // Stop hammering Apple for a while — every subsequent track in this run
                        // would otherwise fail the same way and end up with no artwork.
                        enterBackoff()
                    }
                    val errorTrack = currentTrack.copy(
                        metadataStatus = MetadataStatus.NEEDS_LOOKUP,
                        metadataLastUpdated = System.currentTimeMillis()
                    )
                    trackDao.update(errorTrack)
                    return errorTrack
                }

                Log.i("DIAG_METADATA", "[Point 17] iTunes result count for attempt $attemptCount (country=$country): ${searchResults.size}")
                if (searchResults.isEmpty()) continue
                sawAnyResult = true

                val (candidate, candidateConfidence) = findBestMatch(currentTrack, searchResults)
                if (candidate != null && candidateConfidence == MetadataConfidence.HIGH) {
                    bestMatch = candidate
                    confidence = candidateConfidence
                    break@searchLoop
                }
                if (candidate != null && bestMatch == null) {
                    bestMatch = candidate
                    confidence = candidateConfidence
                }
            }
        }

        if (!sawAnyResult) {
            Log.d(TAG, "No matches found for any query variant of track ${currentTrack.id}")
            val notFoundTrack = currentTrack.copy(
                metadataStatus = MetadataStatus.FAILED,
                metadataLastUpdated = System.currentTimeMillis()
            )
            trackDao.update(notFoundTrack)
            return notFoundTrack
        }

        Log.i("DIAG_METADATA", "[Point 18] Selected iTunes result: '${bestMatch?.title}' by '${bestMatch?.artist}'")
        Log.i("DIAG_METADATA", "[Point 19] Calculated confidence: $confidence")

        if (bestMatch == null || confidence == MetadataConfidence.LOW) {
            Log.d(TAG, "Match confidence is LOW. Retaining existing metadata and marking FAILED.")
            val failedTrack = currentTrack.copy(
                metadataStatus = MetadataStatus.FAILED,
                metadataConfidence = MetadataConfidence.LOW,
                metadataLastUpdated = System.currentTimeMillis()
            )
            trackDao.update(failedTrack)
            return failedTrack
        }

        // Step 5: Merge metadata (strictly preserving genuine embedded fields)
        val enriched = mergeMetadata(currentTrack, bestMatch, confidence, forceRefresh = forceRefresh)

        // Step 6: Persist track update in Room
        Log.i("DIAG_METADATA", "[Point 24] Track ID being updated in Room: ${enriched.id}")
        trackDao.update(enriched)
        Log.i("DIAG_METADATA", "[Point 25] Final Track object after Room update: $enriched")

        // Step 7: Synchronize Album and Artist entities in Room
        updateAlbumAndArtistEntities(enriched)

        return enriched
    }

    /**
     * Determines the optimal storefront country code for iTunes lookup based on
     * language classification or device locale, defaulting to "IN" for regional Indian music.
     */
    fun determineCountryForTrack(track: Track): String {
        val lang = track.language.lowercase(Locale.ROOT)
        if (lang in setOf("telugu", "tamil", "hindi")) {
            return "IN"
        }
        val systemKey = LanguageClassifier.languageToSystemKey(track.language)
        if (systemKey in setOf(LanguageClassifier.SystemKey.TELUGU, LanguageClassifier.SystemKey.TAMIL, LanguageClassifier.SystemKey.HINDI)) {
            return "IN"
        }

        val classifiedLang = LanguageClassifier.classify(
            title = track.title,
            artist = track.artist,
            album = track.albumTitle,
            path = track.path ?: track.uri,
            genre = track.genre
        )
        if (classifiedLang in setOf(LanguageClassifier.TELUGU, LanguageClassifier.TAMIL, LanguageClassifier.HINDI)) {
            return "IN"
        }

        val deviceCountry = try {
            Locale.getDefault().country?.takeIf { it.isNotBlank() }?.uppercase(Locale.ROOT)
        } catch (e: Throwable) {
            null
        }
        return deviceCountry ?: "IN"
    }

    /**
     * Ordered query variants to try against the provider, most specific first.
     *
     * [0] = the existing buildSearchQuery result — unchanged behaviour.
     * [1] = the bare song title. This is the "just search the downloaded song's title"
     *       path: when a filename is "Some Song (128kbps) - SiteName", the combined
     *       artist+title query matches nothing, but the title alone usually does.
     */
    fun buildSearchQueries(track: Track): List<String> {
        val primary = buildSearchQuery(track)
        val hints = MetadataUtils.parseFilenameHints(track.path ?: track.uri)

        val rawTitle = when {
            hints.titleHint.isNotBlank() && hints.titleHint != "Unknown Track" -> hints.titleHint
            !MetadataUtils.isPlaceholderTitle(track.title) -> track.title
            else -> ""
        }
        val titleOnly = MetadataUtils.cleanNoise(rawTitle).trim()

        return listOf(primary, titleOnly)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
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
     * Scores candidates and assigns a Confidence level.
     * Requires minimum title similarity (>= 0.60), disqualifies candidates with
     * large duration mismatches (> 30s) or missing local title signal, and requires
     * a minimum score of 50 to accept (resolving strictly to HIGH or LOW).
     */
    fun findBestMatch(
        track: Track,
        candidates: List<RemoteTrackMetadata>
    ): Pair<RemoteTrackMetadata?, String> {
        if (candidates.isEmpty()) return Pair(null, MetadataConfidence.LOW)

        val hints = MetadataUtils.parseFilenameHints(track.path ?: track.uri)
        val targetArtist = if (!MetadataUtils.isPlaceholderArtist(track.artist)) track.artist else hints.artistHint
        val targetTitle = if (!MetadataUtils.isPlaceholderTitle(track.title)) track.title else hints.titleHint

        val isTitleKnown = !MetadataUtils.isPlaceholderTitle(targetTitle) && !targetTitle.isNullOrBlank()
        if (!isTitleKnown) {
            // Never guess if there is no usable local title signal
            return Pair(null, MetadataConfidence.LOW)
        }

        val targetTitleStr = targetTitle!!
        val isArtistKnown = !MetadataUtils.isPlaceholderArtist(targetArtist) && !targetArtist.isNullOrBlank()

        var bestCandidate: RemoteTrackMetadata? = null
        var highestScore = -100

        for (candidate in candidates) {
            // 1. Title Similarity Check (HARD REQUIREMENT)
            // Never accept on artist + duration alone!
            val titleSim = MetadataUtils.calculateTitleSimilarity(targetTitleStr, candidate.title)
            if (titleSim < 0.60) {
                continue
            }

            // 2. Duration Mismatch Check (HARD REQUIREMENT)
            // Treat a large duration mismatch (> 30s) as disqualifying
            var diffSeconds = 0L
            if (track.duration > 0 && candidate.durationMs > 0) {
                diffSeconds = abs(track.duration - candidate.durationMs) / 1000
                if (diffSeconds > 30) {
                    continue
                }
            }

            // 3. Scoring
            var score = (titleSim * 50).toInt()
            if (titleSim >= 0.95) {
                score += 5 // bonus for near-exact match
            }

            // Artist Matching
            if (isArtistKnown) {
                val artistSim = MetadataUtils.calculateTitleSimilarity(targetArtist!!, candidate.artist)
                val targetArtistNorm = MetadataUtils.normalizeString(targetArtist)
                val candidateArtistNorm = MetadataUtils.normalizeString(candidate.artist)

                when {
                    targetArtistNorm == candidateArtistNorm || artistSim >= 0.9 -> score += 35
                    artistSim >= 0.6 || candidateArtistNorm.contains(targetArtistNorm) || targetArtistNorm.contains(candidateArtistNorm) -> score += 20
                    artistSim < 0.3 -> score -= 25 // severe artist mismatch penalty
                    else -> score += 5
                }
            }

            // Duration Matching
            if (track.duration > 0 && candidate.durationMs > 0) {
                when {
                    diffSeconds <= 5 -> score += 15
                    diffSeconds <= 15 -> score += 10
                    diffSeconds <= 30 -> score += 0
                }
            }

            if (score > highestScore) {
                highestScore = score
                bestCandidate = candidate
            }
        }

        // Require minimum absolute score of 50 to accept
        return if (bestCandidate != null && highestScore >= 50) {
            Pair(bestCandidate, MetadataConfidence.HIGH)
        } else {
            Pair(null, MetadataConfidence.LOW)
        }
    }

    /**
     * Applies confident remote metadata to the local Track via direct overwrite.
     * Overwrites song title, artist, album name, track number, year, genre directly.
     * Preserves valid embedded artwork if present, otherwise downloads remote cover art.
     *
     * IMPORTANT: If the track has MANUAL artwork provenance the artwork block is skipped entirely.
     * The user's explicit choice is never overwritten by automatic enrichment.
     */
    suspend fun mergeMetadata(
        local: Track,
        remote: RemoteTrackMetadata,
        confidence: String,
        forceRefresh: Boolean = false,
    ): Track {
        val hasManualArtwork = local.artworkSource == com.mus.android.data.model.ArtworkSource.MANUAL

        // If the track has MANUAL provenance, user's explicit metadata choices are authoritative:
        // do not silently revert title/artist/album if local already has valid, non-placeholder values.
        val finalTitle = if (hasManualArtwork && !MetadataUtils.isPlaceholderTitle(local.title)) local.title else remote.title
        val finalArtist = if (hasManualArtwork && !MetadataUtils.isPlaceholderArtist(local.artist)) local.artist else remote.artist
        val finalAlbumTitle = if (hasManualArtwork && !MetadataUtils.isPlaceholderAlbum(local.albumTitle)) local.albumTitle else (remote.albumTitle ?: local.albumTitle)
        val finalAlbumArtist = if (hasManualArtwork && !MetadataUtils.isPlaceholderArtist(local.albumArtist)) local.albumArtist else (remote.albumArtist ?: finalArtist)
        val finalTrackNumber = if (remote.trackNumber > 0) remote.trackNumber else local.trackNumber
        val finalDiscNumber = if (remote.discNumber > 1) remote.discNumber else local.discNumber
        val finalYear = if (hasManualArtwork && local.year > 0) local.year else if (remote.year > 0) remote.year else local.year
        val finalGenre = if (!remote.genre.isNullOrBlank()) remote.genre else local.genre
        val finalComposer = if (!remote.composer.isNullOrBlank()) remote.composer else local.composer

        // Canonical album ID based on the resolved Album Artist + Album Title
        val newAlbumId = MetadataUtils.generateAlbumId(finalAlbumArtist, finalAlbumTitle)

        // Artwork resolution: preserve valid embedded artwork if exists, otherwise download/reuse remote.
        // CRITICAL: MANUAL artwork provenance is respected unconditionally — user selection wins.
        var finalArtworkUri = local.artworkUri
        var artworkSavedPath: String? = null

        if (hasManualArtwork) {
            // Preserve the user's explicitly chosen artwork; only log for diagnostics.
            artworkSavedPath = local.artworkUri
            Log.i("DIAG_METADATA", "[Point 20a] Artwork preserved (MANUAL provenance) — skipping automatic artwork resolution for track ${local.id}")
        } else {
            val hasEmbedded = artworkStorage.isEmbeddedArtwork(local.artworkUri) ||
                    artworkStorage.hasEmbeddedArtwork(newAlbumId) ||
                    artworkStorage.hasEmbeddedArtwork(local.albumId)

            if (hasEmbedded) {
                finalArtworkUri = artworkStorage.getLocalArtworkUri(newAlbumId)
                    ?: artworkStorage.getLocalArtworkUri(local.albumId)
                    ?: local.artworkUri
                artworkSavedPath = finalArtworkUri
            } else {
                val cachedArt = artworkStorage.getLocalArtworkUri(newAlbumId)
                if (cachedArt != null && !forceRefresh) {
                    finalArtworkUri = cachedArt
                    artworkSavedPath = cachedArt
                } else if (!remote.artworkUrl.isNullOrBlank()) {
                    val downloaded = artworkStorage.downloadAndStoreArtwork(newAlbumId, remote.artworkUrl, forceOverwrite = forceRefresh)
                    if (downloaded != null) {
                        finalArtworkUri = downloaded
                        artworkSavedPath = downloaded
                    }
                } else if (cachedArt != null) {
                    finalArtworkUri = cachedArt
                    artworkSavedPath = cachedArt
                } else {
                    artworkSavedPath = local.artworkUri
                }
            }
        }

        Log.i("DIAG_METADATA", "[Point 20] Final direct overwrite: title='$finalTitle', artist='$finalArtist', album='$finalAlbumTitle', year=$finalYear, genre=$finalGenre")
        Log.i("DIAG_METADATA", "[Point 21] Artwork URL selected: ${remote.artworkUrl}")
        Log.i("DIAG_METADATA", "[Point 22] Artwork file path saved: $artworkSavedPath")
        Log.i("DIAG_METADATA", "[Point 23] Artwork URI written to Room: $finalArtworkUri")

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
            // Preserve MANUAL artwork provenance — do not downgrade to EXTERNAL
            artworkSource = if (hasManualArtwork) local.artworkSource else com.mus.android.data.model.ArtworkSource.EXTERNAL,
            artworkProvider = if (hasManualArtwork) local.artworkProvider else null,
            artworkRemoteId = if (hasManualArtwork) local.artworkRemoteId else null,
            artworkLastUpdated = if (hasManualArtwork) local.artworkLastUpdated else System.currentTimeMillis(),
            metadataSource = MetadataSource.EXTERNAL,
            metadataStatus = MetadataStatus.COMPLETE,
            metadataConfidence = MetadataConfidence.HIGH,
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
                        artworkUri = enriched.artworkUri ?: existingAlbum.artworkUri,
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
            // Never assign an album cover to artist artwork — leave it null unless genuine artist art exists
            val artistNames = setOf(enriched.artist, enriched.albumArtist).filter { !MetadataUtils.isPlaceholderArtist(it) }
            for (artistName in artistNames) {
                val artistId = MetadataUtils.generateArtistId(artistName)
                val existingArtist = artistDao.getArtistById(artistId)
                if (existingArtist != null) {
                    // Retain existing artist entity as-is without assigning album cover
                } else {
                    artistDao.insertAll(listOf(
                        Artist(
                            id = artistId,
                            name = artistName,
                            artworkUri = null, // Don't use album cover as artist artwork
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
        /** ~20 requests/minute — under the iTunes Search unauthenticated ceiling. */
        private const val MIN_REQUEST_GAP_MS = 3_000L
        /** Quiet period after a 403/429 before any further provider calls. */
        private const val RATE_LIMIT_COOLDOWN_MS = 60_000L
    }
}