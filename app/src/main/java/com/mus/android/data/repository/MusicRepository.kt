package com.mus.android.data.repository

import androidx.room.withTransaction
import com.mus.android.data.db.*
import com.mus.android.data.enrichment.artwork.ArtworkStorage
import com.mus.android.data.model.*
import com.mus.android.data.scanner.MediaStoreScanner
import com.mus.android.data.scanner.WaveformExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MusicRepository @Inject constructor(
    private val database: MusDatabase,
    private val trackDao: TrackDao,
    private val albumDao: AlbumDao,
    private val artistDao: ArtistDao,
    private val playlistDao: PlaylistDao,
    private val waveformDao: WaveformDao,
    private val scanner: MediaStoreScanner,
    private val waveformExtractor: WaveformExtractor,
    private val userPreferences: UserPreferencesRepository,
    private val enrichmentService: com.mus.android.data.enrichment.MetadataEnrichmentService,
    private val artworkStorage: com.mus.android.data.enrichment.artwork.ArtworkStorage,
) {
    private val _idMigrations = kotlinx.coroutines.flow.MutableSharedFlow<Map<Long, Long>>(replay = 0, extraBufferCapacity = 1)
    val idMigrations: kotlinx.coroutines.flow.SharedFlow<Map<Long, Long>> = _idMigrations

    init {
        CoroutineScope(Dispatchers.IO).launch {
            reconcileFolderPlaylists()
        }
    }

    suspend fun ensureDefaultPlaylists() = withContext(Dispatchers.IO) {
        reconcileFolderPlaylists()
    }

    suspend fun reconcileLanguagePlaylists(tracks: List<Track>) = withContext(Dispatchers.IO) {
        reconcileFolderPlaylists(tracks)
    }

    suspend fun reconcileFolderPlaylists(
        tracks: List<Track> = emptyList(),
        targetUri: android.net.Uri? = null
    ) = withContext(Dispatchers.IO) {
        val uri = targetUri ?: userPreferences.customMuzicUri.value?.let { android.net.Uri.parse(it) }
        val allTracks = if (tracks.isNotEmpty()) tracks else trackDao.getAllTracksOnce()
        val discoveredFolders = scanner.discoverDirectFolders(uri).toMutableSet()

        // Also discover folders from track paths or URIs
        for (track in allTracks) {
            val folder = com.mus.android.data.scanner.MetadataUtils.extractMuzicDirectFolder(track.path ?: track.uri)
            if (folder != null) {
                discoveredFolders.add(folder)
            }
        }

        // If no tracks exist and no folders could be discovered (e.g. before initial scan or permission grant), do nothing
        if (allTracks.isEmpty() && discoveredFolders.isEmpty()) return@withContext

        // Map tracks strictly by their direct Muzic folder
        val tracksByFolder = mutableMapOf<String, MutableList<Track>>()
        for (folder in discoveredFolders) {
            tracksByFolder[folder] = mutableListOf()
        }
        for (track in allTracks) {
            val folder = com.mus.android.data.scanner.MetadataUtils.extractMuzicDirectFolder(track.path ?: track.uri)
            if (folder != null) {
                tracksByFolder.getOrPut(folder) { mutableListOf() }.add(track)
            }
        }

        database.withTransaction {
            val existingPlaylists = playlistDao.getAllPlaylistsOnce()
            val validFolderNamesLower = discoveredFolders.map { it.lowercase() }.toSet()

            // 1. Remove stale playlists (e.g., deleted folders or old hardcoded/system playlists)
            // and deduplicate playlists with the same name
            val existingByName = existingPlaylists.groupBy { it.name.lowercase() }
            val activePlaylistsByName = mutableMapOf<String, Playlist>()

            for ((nameLower, pls) in existingByName) {
                if (nameLower !in validFolderNamesLower) {
                    // Stale playlist
                    for (pl in pls) {
                        playlistDao.clearPlaylistTracks(pl.id)
                        playlistDao.deletePlaylistById(pl.id)
                    }
                } else {
                    // Keep the first one, delete any duplicate rows with the same name
                    activePlaylistsByName[nameLower] = pls.first()
                    if (pls.size > 1) {
                        for (pl in pls.drop(1)) {
                            playlistDao.clearPlaylistTracks(pl.id)
                            playlistDao.deletePlaylistById(pl.id)
                        }
                    }
                }
            }

            // 2. Ensure each discovered folder has a corresponding Playlist
            val folderPlaylists = mutableMapOf<String, Playlist>()
            for (folder in discoveredFolders) {
                val existing = activePlaylistsByName[folder.lowercase()]
                val playlist = if (existing != null) {
                    // Update name casing if changed
                    if (existing.name != folder || existing.isSystemPlaylist || existing.systemKey != null) {
                        val updated = existing.copy(
                            name = folder,
                            isSystemPlaylist = false,
                            systemKey = null,
                            updatedAt = System.currentTimeMillis()
                        )
                        playlistDao.updatePlaylist(updated)
                        updated
                    } else {
                        existing
                    }
                } else {
                    val newId = playlistDao.insertPlaylist(
                        Playlist(
                            name = folder,
                            isSystemPlaylist = false,
                            systemKey = null,
                            createdAt = System.currentTimeMillis(),
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                    playlistDao.getPlaylistById(newId) ?: Playlist(id = newId, name = folder)
                }
                folderPlaylists[folder] = playlist
            }

            // 3. Reconcile track memberships for each folder
            for ((folder, folderTracks) in tracksByFolder) {
                val playlist = folderPlaylists[folder] ?: continue

                // Stable deterministic ordering: trackNumber -> title -> id
                val sortedTracks = folderTracks.sortedWith(
                    compareBy<Track> { if (it.trackNumber > 0) it.trackNumber else Int.MAX_VALUE }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
                        .thenBy { it.id }
                )
                val expectedTrackIds = sortedTracks.map { it.id }
                val currentTrackIds = playlistDao.getTrackIdsForPlaylist(playlist.id)

                // If membership or ordering differs, update playlist_tracks
                if (currentTrackIds != expectedTrackIds) {
                    playlistDao.clearPlaylistTracks(playlist.id)
                    if (sortedTracks.isNotEmpty()) {
                        var position = 0
                        val playlistTracks = sortedTracks.map { track ->
                            PlaylistTrack(
                                playlistId = playlist.id,
                                trackId = track.id,
                                position = position++,
                                addedAt = System.currentTimeMillis()
                            )
                        }
                        playlistDao.insertPlaylistTracks(playlistTracks)
                    }
                    playlistDao.updatePlaylist(playlist.copy(updatedAt = System.currentTimeMillis()))
                }
            }
        }
    }

    // ── Scanning ──────────────────────────────────────────────
    suspend fun scanDevice(safTreeUri: android.net.Uri? = null): MediaStoreScanner.ScanResult = withContext(Dispatchers.IO) {
        ensureArtworkForensicFixCompleted()
        ensureArtworkRollbackRebuildCompleted()
        ensureMetadataRepairV3Completed()
        ensureMetadataResetV2Completed()
        val targetUri = safTreeUri ?: userPreferences.customMuzicUri.value?.let { android.net.Uri.parse(it) }
        val result = scanner.scan(targetUri)

        val migratedIds = mutableMapOf<Long, Long>()
        val mergedTracks = database.withTransaction {
            val existingTracks = trackDao.getAllTracksOnce()
            val existingById = existingTracks.associateBy { it.id }
            val existingByUri = existingTracks.associateBy { it.path ?: it.uri }
            // Path-based fallback: match by Muzic-relative path for migration from old ID formats
            val existingByMuzicPath = existingTracks
                .mapNotNull { track ->
                    val path = track.path ?: return@mapNotNull null
                    val relative = com.mus.android.data.scanner.MetadataUtils.extractMuzicRelativePath(path) ?: return@mapNotNull null
                    relative to track
                }
                .toMap()
            // Case-insensitive fallback for robustness across filesystem variations
            val existingByMuzicPathLower = existingTracks
                .mapNotNull { track ->
                    val path = track.path ?: return@mapNotNull null
                    val normalized = com.mus.android.data.scanner.MetadataUtils.normalizeForLookup(path) ?: return@mapNotNull null
                    normalized to track
                }
                .toMap()

            val merged = result.tracks.map { scannedTrack ->
                val scannedMuzicPath = scannedTrack.path?.let { com.mus.android.data.scanner.MetadataUtils.extractMuzicRelativePath(it) }
                val scannedMuzicPathLower = scannedTrack.path?.let { com.mus.android.data.scanner.MetadataUtils.normalizeForLookup(it) }
                val existing = existingById[scannedTrack.id]
                    ?: existingByUri[scannedTrack.path ?: scannedTrack.uri]
                    ?: scannedMuzicPath?.let { existingByMuzicPath[it] }
                    ?: scannedMuzicPathLower?.let { existingByMuzicPathLower[it] }
                if (existing != null) {
                    // Safe ID migration for old database: preserve playlist memberships and waveforms
                    if (existing.id != scannedTrack.id) {
                        migratedIds[existing.id] = scannedTrack.id
                        try {
                            database.openHelper.writableDatabase.execSQL(
                                "UPDATE OR IGNORE playlist_tracks SET trackId = ? WHERE trackId = ?",
                                arrayOf(scannedTrack.id, existing.id)
                            )
                            database.openHelper.writableDatabase.execSQL(
                                "DELETE FROM playlist_tracks WHERE trackId = ?",
                                arrayOf(existing.id)
                            )
                            database.openHelper.writableDatabase.execSQL(
                                "UPDATE OR IGNORE waveforms SET trackId = ? WHERE trackId = ?",
                                arrayOf(scannedTrack.id, existing.id)
                            )
                            database.openHelper.writableDatabase.execSQL(
                                "DELETE FROM waveforms WHERE trackId = ?",
                                arrayOf(existing.id)
                            )
                        } catch (e: Exception) {
                            android.util.Log.w("MusicRepository", "Failed migrating foreign keys from ${existing.id} to ${scannedTrack.id}: ${e.message}")
                        }
                    }

                    // ENRICHING RACE PROTECTION: If a track is actively being enriched,
                    // preserve its entire existing state — do NOT overwrite with scanner data.
                    val isActivelyEnriching = existing.metadataStatus == MetadataStatus.ENRICHING &&
                            System.currentTimeMillis() - existing.metadataLastUpdated < 5 * 60 * 1000L
                    if (isActivelyEnriching) {
                        android.util.Log.d("DIAG_METADATA", "ENRICHING_RACE_PROTECT: Track ${existing.id} is actively enriching, preserving existing metadata")
                        existing.copy(
                            id = scannedTrack.id,
                            uri = scannedTrack.uri,
                            path = scannedTrack.path ?: existing.path,
                            size = if (scannedTrack.size > 0) scannedTrack.size else existing.size,
                            dateModified = if (scannedTrack.dateModified > 0) scannedTrack.dateModified else existing.dateModified,
                        )
                    } else {
                        val wasEnriched = existing.metadataStatus == MetadataStatus.COMPLETE ||
                                existing.metadataStatus == MetadataStatus.PARTIAL ||
                                existing.metadataStatus == MetadataStatus.NEEDS_REVIEW ||
                                existing.metadataSource == MetadataSource.EXTERNAL ||
                                existing.metadataSource == MetadataSource.MERGED

                        // Valid existing artwork check — purge shared unknown album art
                        val finalArtworkUri = when {
                            com.mus.android.data.enrichment.artwork.ArtworkStorage.isArtworkValid(existing.artworkUri) -> existing.artworkUri
                            com.mus.android.data.enrichment.artwork.ArtworkStorage.isArtworkValid(scannedTrack.artworkUri) -> scannedTrack.artworkUri
                            else -> null
                        }

                        val finalTitle = if (wasEnriched || !com.mus.android.data.scanner.MetadataUtils.isPlaceholderTitle(existing.title)) {
                            existing.title
                        } else {
                            scannedTrack.title
                        }

                        val finalArtist = if (wasEnriched || !com.mus.android.data.scanner.MetadataUtils.isPlaceholderArtist(existing.artist)) {
                            existing.artist
                        } else {
                            scannedTrack.artist
                        }

                        val finalAlbumArtist = if (wasEnriched || !com.mus.android.data.scanner.MetadataUtils.isPlaceholderArtist(existing.albumArtist)) {
                            existing.albumArtist
                        } else {
                            scannedTrack.albumArtist
                        }

                        val finalAlbumTitle = if (wasEnriched || !com.mus.android.data.scanner.MetadataUtils.isPlaceholderAlbum(existing.albumTitle)) {
                            existing.albumTitle
                        } else {
                            scannedTrack.albumTitle
                        }

                        val finalAlbumId = if (wasEnriched) existing.albumId else scannedTrack.albumId

                        scannedTrack.copy(
                            title = finalTitle,
                            artist = finalArtist,
                            albumArtist = finalAlbumArtist,
                            albumTitle = finalAlbumTitle,
                            albumId = finalAlbumId,
                            artworkUri = finalArtworkUri,
                            trackNumber = if (existing.trackNumber > 0) existing.trackNumber else scannedTrack.trackNumber,
                            discNumber = if (existing.discNumber > 0) existing.discNumber else scannedTrack.discNumber,
                            year = if (existing.year > 0) existing.year else scannedTrack.year,
                            genre = existing.genre ?: scannedTrack.genre,
                            language = if (wasEnriched || (existing.language.isNotBlank() && existing.language != "Unknown")) {
                                existing.language
                            } else {
                                scannedTrack.language
                            },
                            composer = existing.composer ?: scannedTrack.composer,
                            artistArtworkUri = existing.artistArtworkUri ?: scannedTrack.artistArtworkUri,
                            metadataSource = if (wasEnriched) existing.metadataSource else scannedTrack.metadataSource,
                            metadataStatus = if (wasEnriched) existing.metadataStatus else scannedTrack.metadataStatus,
                            metadataConfidence = if (wasEnriched) existing.metadataConfidence else scannedTrack.metadataConfidence,
                            metadataLastUpdated = if (existing.metadataLastUpdated > 0) existing.metadataLastUpdated else scannedTrack.metadataLastUpdated,
                            isFavorite = existing.isFavorite,
                            playCount = existing.playCount,
                            lastPlayed = existing.lastPlayed
                        )
                    }
                } else {
                    scannedTrack
                }
            }

            // Stale removal and upsert for tracks
            val newTrackIds = merged.map { it.id }.toSet()
            val staleTrackIds = existingTracks.map { it.id }.filter { it !in newTrackIds }
            if (staleTrackIds.isNotEmpty()) {
                trackDao.deleteTracksByIds(staleTrackIds)
            }
            if (merged.isNotEmpty()) {
                trackDao.insertAll(merged)
            }

            // Re-aggregate albums and artists from the MERGED tracks (preserving enriched state)
            val aggregated = scanner.aggregateScanResult(merged)

            // Stale removal and upsert for albums (preserving valid enriched album artwork)
            val existingAlbums = albumDao.getAllAlbumsOnce()
            val newAlbumIds = aggregated.albums.map { it.id }.toSet()
            val staleAlbumIds = existingAlbums.map { it.id }.filter { it !in newAlbumIds }
            if (staleAlbumIds.isNotEmpty()) {
                albumDao.deleteAlbumsByIds(staleAlbumIds)
            }
            val existingAlbumMap = existingAlbums.associateBy { it.id }
            val preservedAlbums = aggregated.albums.map { album ->
                val existing = existingAlbumMap[album.id]
                if (existing != null) {
                    val finalArt = when {
                        com.mus.android.data.enrichment.artwork.ArtworkStorage.isArtworkValid(album.artworkUri) -> album.artworkUri
                        com.mus.android.data.enrichment.artwork.ArtworkStorage.isArtworkValid(existing.artworkUri) -> existing.artworkUri
                        else -> null
                    }
                    album.copy(artworkUri = finalArt)
                } else {
                    album
                }
            }
            if (preservedAlbums.isNotEmpty()) {
                albumDao.insertAll(preservedAlbums)
            }

            // Stale removal and upsert for artists (preserving valid enriched artist artwork)
            val existingArtists = artistDao.getAllArtistsOnce()
            val newArtistIds = aggregated.artists.map { it.id }.toSet()
            val staleArtistIds = existingArtists.map { it.id }.filter { it !in newArtistIds }
            if (staleArtistIds.isNotEmpty()) {
                artistDao.deleteArtistsByIds(staleArtistIds)
            }
            val existingArtistMap = existingArtists.associateBy { it.id }
            val preservedArtists = aggregated.artists.map { artist ->
                val existing = existingArtistMap[artist.id]
                if (existing != null) {
                    val finalArt = when {
                        com.mus.android.data.enrichment.artwork.ArtworkStorage.isArtworkValid(artist.artworkUri) -> artist.artworkUri
                        com.mus.android.data.enrichment.artwork.ArtworkStorage.isArtworkValid(existing.artworkUri) -> existing.artworkUri
                        else -> null
                    }
                    artist.copy(artworkUri = finalArt)
                } else {
                    artist
                }
            }
            if (preservedArtists.isNotEmpty()) {
                artistDao.insertAll(preservedArtists)
            }

            merged
        }

        if (migratedIds.isNotEmpty()) {
            _idMigrations.tryEmit(migratedIds)
        }

        // Automatically organize tracks into folder-derived playlists (English, Telugu, Hindi, etc.)
        reconcileFolderPlaylists(mergedTracks, targetUri)

        // Use metadataStatus as the state machine to filter enrichment queue (Requirement 9)
        val tracksNeedingEnrichment = mergedTracks.filter { track ->
            when (track.metadataStatus) {
                MetadataStatus.COMPLETE -> false
                MetadataStatus.NEEDS_REVIEW -> false
                MetadataStatus.ENRICHING -> {
                    // Stale lock after 5 minutes across app restarts
                    System.currentTimeMillis() - track.metadataLastUpdated > 5 * 60 * 1000L
                }
                MetadataStatus.FAILED -> {
                    // Retry failed lookups if more than 24 hours have elapsed
                    System.currentTimeMillis() - track.metadataLastUpdated > 24 * 60 * 60 * 1000L
                }
                MetadataStatus.NEEDS_LOOKUP, MetadataStatus.PARTIAL -> true
                else -> false
            }
        }
        enrichmentService.enqueueEnrichment(tracksNeedingEnrichment)

        result.copy(tracks = mergedTracks)
    }

    private suspend fun getOrCreatePlaylist(name: String): Playlist {
        val existing = playlistDao.getPlaylistByName(name)
        if (existing != null) return existing
        val newId = playlistDao.insertPlaylist(Playlist(name = name))
        return playlistDao.getPlaylistById(newId) ?: Playlist(id = newId, name = name)
    }

    // ── Tracks ────────────────────────────────────────────────
    fun getAllTracks(): Flow<List<Track>> = trackDao.getAllTracks()
    fun getRecentlyAdded(limit: Int = 20): Flow<List<Track>> = trackDao.getRecentlyAdded(limit)
    fun getMostPlayed(limit: Int = 20): Flow<List<Track>> = trackDao.getMostPlayed(limit)
    fun getRecentlyPlayed(limit: Int = 20): Flow<List<Track>> = trackDao.getRecentlyPlayed(limit)
    suspend fun recordTrackPlayed(trackId: Long) = trackDao.recordPlay(trackId, System.currentTimeMillis())
    suspend fun getTrackById(id: Long): Track? = trackDao.getTrackById(id)
    fun getTracksByAlbum(albumId: Long): Flow<List<Track>> = trackDao.getTracksByAlbum(albumId)
    fun getTracksByArtist(artist: String): Flow<List<Track>> = trackDao.getTracksByArtist(artist)
    fun getTracksByLanguage(language: String): Flow<List<Track>> = trackDao.getTracksByLanguage(language)
    fun searchTracks(query: String): Flow<List<Track>> = trackDao.search(query)
    suspend fun getTrackCount(): Int = trackDao.getTrackCount()
    suspend fun deleteTrack(trackId: Long) = trackDao.deleteTrackById(trackId)

    // ── Favorites ─────────────────────────────────────────────
    fun getFavorites(): Flow<List<Track>> = trackDao.getFavorites()
    suspend fun toggleFavorite(trackId: Long) {
        val track = trackDao.getTrackById(trackId) ?: return
        trackDao.setFavorite(trackId, !track.isFavorite)
    }

    // ── Albums ────────────────────────────────────────────────
    fun getAllAlbums(): Flow<List<Album>> = albumDao.getAllAlbums()
    suspend fun getAlbumById(id: Long): Album? = albumDao.getAlbumById(id)
    fun getAlbumsByArtist(artist: String): Flow<List<Album>> = albumDao.getAlbumsByArtist(artist)
    fun searchAlbums(query: String): Flow<List<Album>> = albumDao.search(query)
    fun getRandomAlbums(limit: Int = 10): Flow<List<Album>> = albumDao.getRandomAlbums(limit)

    // ── Artists ───────────────────────────────────────────────
    fun getAllArtists(): Flow<List<Artist>> = artistDao.getAllArtists()
    suspend fun getArtistById(id: Long): Artist? = artistDao.getArtistById(id)
    fun searchArtists(query: String): Flow<List<Artist>> = artistDao.search(query)

    // ── Playlists ─────────────────────────────────────────────
    fun getAllPlaylists(): Flow<List<Playlist>> = playlistDao.getAllPlaylists()
    suspend fun getPlaylistById(id: Long): Playlist? = playlistDao.getPlaylistById(id)
    fun getPlaylistTracks(playlistId: Long): Flow<List<Track>> = playlistDao.getPlaylistTracks(playlistId)

    suspend fun createPlaylist(name: String): Long {
        return playlistDao.insertPlaylist(Playlist(name = name))
    }

    suspend fun renamePlaylist(playlistId: Long, name: String) {
        val playlist = playlistDao.getPlaylistById(playlistId) ?: return
        playlistDao.updatePlaylist(playlist.copy(name = name, updatedAt = System.currentTimeMillis()))
    }

    suspend fun deletePlaylist(playlistId: Long) {
        val playlist = playlistDao.getPlaylistById(playlistId) ?: return
        playlistDao.clearPlaylistTracks(playlistId)
        playlistDao.deletePlaylistById(playlistId)
    }

    suspend fun isTrackInPlaylist(playlistId: Long, trackId: Long): Boolean {
        return playlistDao.isTrackInPlaylist(playlistId, trackId) > 0
    }

    suspend fun addTrackToPlaylist(playlistId: Long, trackId: Long): Boolean {
        val track = trackDao.getTrackById(trackId) ?: return false
        if (isTrackInPlaylist(playlistId, trackId)) {
            return false // Already in playlist
        }
        val maxPos = playlistDao.getMaxPosition(playlistId) ?: -1
        val inserted = playlistDao.insertPlaylistTrack(
            PlaylistTrack(
                playlistId = playlistId,
                trackId = trackId,
                position = maxPos + 1,
            )
        )
        if (inserted <= 0) return false
        val playlist = playlistDao.getPlaylistById(playlistId) ?: return true
        playlistDao.updatePlaylist(playlist.copy(updatedAt = System.currentTimeMillis()))
        return true
    }

    suspend fun removeTrackFromPlaylist(playlistId: Long, trackId: Long) {
        playlistDao.removeTrackFromPlaylist(playlistId, trackId)
    }

    suspend fun getPlaylistIdsForTrack(trackId: Long): List<Long> {
        return playlistDao.getPlaylistIdsForTrack(trackId)
    }

    fun observePlaylistIdsForTrack(trackId: Long): Flow<List<Long>> {
        return playlistDao.observePlaylistIdsForTrack(trackId)
    }

    suspend fun moveTracks(sourcePlaylistId: Long, destinationPlaylistId: Long, trackIds: List<Long>): Int {
        return playlistDao.moveTracksTransaction(sourcePlaylistId, destinationPlaylistId, trackIds)
    }

    suspend fun copyTracks(destinationPlaylistId: Long, trackIds: List<Long>): Int {
        return playlistDao.copyTracksTransaction(destinationPlaylistId, trackIds)
    }

    suspend fun addTracksToPlaylist(playlistId: Long, trackIds: List<Long>): Int {
        return copyTracks(playlistId, trackIds)
    }

    suspend fun removeTracksFromPlaylist(playlistId: Long, trackIds: List<Long>) {
        playlistDao.removeTracksTransaction(playlistId, trackIds)
    }

    suspend fun getPlaylistTrackCount(playlistId: Long): Int {
        return playlistDao.getPlaylistTrackCount(playlistId)
    }

    // ── Local Storage Vault (/Muzic/) ─────────────────────────
    fun doesMuzicDirectoryExist(): Boolean = scanner.doesMuzicDirectoryExist()
    fun createMuzicDirectory(): Boolean = scanner.createMuzicDirectory()
    fun getCustomMuzicUri(): String? = userPreferences.customMuzicUri.value
    fun setCustomMuzicUri(uriString: String?) = userPreferences.setCustomMuzicUri(uriString)
    fun isAudioFocusEnabled(): Boolean = userPreferences.audioFocusEnabled.value
    fun setAudioFocusEnabled(enabled: Boolean) = userPreferences.setAudioFocusEnabled(enabled)

    fun isAutomaticMetadataEnabled(): Boolean = userPreferences.automaticMetadataEnabled.value
    fun setAutomaticMetadataEnabled(enabled: Boolean) = userPreferences.setAutomaticMetadataEnabled(enabled)

    /**
     * Force re-enriches the entire library by resetting metadata status to NEEDS_LOOKUP
     * for all tracks, then re-enqueueing them for enrichment through the improved pipeline.
     * Preserves: track identity, playlists, favorites, play counts, file paths.
     * Does NOT create duplicate tracks.
     */
    suspend fun forceReEnrichLibrary() = withContext(Dispatchers.IO) {
        val allTracks = trackDao.getAllTracksOnce()
        val resetTracks = allTracks.map { track ->
            track.copy(
                metadataStatus = MetadataStatus.NEEDS_LOOKUP,
                metadataConfidence = MetadataConfidence.LOW,
                metadataLastUpdated = 0L,
                // Preserve: id, title, artist, album, path, uri, favorites, playCount, lastPlayed, language
            )
        }
        if (resetTracks.isNotEmpty()) {
            trackDao.insertAll(resetTracks)
        }
        android.util.Log.i("DIAG_METADATA", "FORCE_RE_ENRICH: Reset ${resetTracks.size} tracks to NEEDS_LOOKUP")
        enrichmentService.enqueueEnrichment(resetTracks)
    }

    /**
     * Backward-compatible alias for forceReEnrichLibrary.
     */
    suspend fun refreshAllMetadata() = forceReEnrichLibrary()

    suspend fun refreshTrackMetadata(trackId: Long) = withContext(Dispatchers.IO) {
        val track = trackDao.getTrackById(trackId) ?: return@withContext
        enrichmentService.enrichTrack(track, forceRefresh = true)
    }

    // ── Waveform ──────────────────────────────────────────────
    suspend fun getWaveform(trackId: Long, uri: String): List<Float>? {
        return waveformExtractor.getWaveform(trackId, uri)
    }

    // ── One-Time & Manual Metadata Reset ─────────────────────

    /**
     * One-time clean metadata reset:
     * Erases old metadata/enrichment/artwork state in Room and disk cache
     * while strictly preserving:
     * - Track IDs (stable identity)
     * - File paths & URIs
     * - Technical specs (duration, size, dateAdded, dateModified, codec, sampleRate, bitDepth, bitrate, channels)
     * - Favorite status
     * - Play counts & last played timestamp
     * - User playlists & playlist memberships
     * - Language classifications
     */
    suspend fun performMetadataReset(): MetadataResetResult = withContext(Dispatchers.IO) {
        android.util.Log.i("DIAG_METADATA_RESET", "=== METADATA RESET STARTED ===")
        android.util.Log.i("DIAG_METADATA_RESET", "resetStarted: true")

        // Abort any ongoing enrichment tasks to prevent race conditions
        enrichmentService.cancelAllEnrichment()

        val existingTracks = trackDao.getAllTracksOnce()
        val tracksFound = existingTracks.size
        android.util.Log.i("DIAG_METADATA_RESET", "tracksFound: $tracksFound")

        var albumIdsCleared = 0
        val resetTracks = existingTracks.map { track ->
            if (track.albumId != 0L && track.albumId != com.mus.android.data.enrichment.artwork.ArtworkStorage.UNKNOWN_ALBUM_ID) {
                albumIdsCleared++
            }
            track.copy(
                albumId = 0L,
                albumTitle = "",
                albumArtist = "",
                title = "",
                artist = "",
                genre = null,
                composer = null,
                trackNumber = 0,
                discNumber = 0,
                year = 0,
                artworkUri = null,
                artistArtworkUri = null,
                metadataStatus = MetadataStatus.NEEDS_LOOKUP,
                metadataSource = MetadataSource.EMBEDDED,
                metadataConfidence = MetadataConfidence.LOW,
                metadataLastUpdated = 0L,
            )
        }

        if (resetTracks.isNotEmpty()) {
            trackDao.insertAll(resetTracks)
        }

        // Wipe old generated albums, artists, and artwork palettes
        albumDao.deleteAll()
        artistDao.deleteAll()
        try {
            database.paletteDao().deleteAll()
        } catch (e: Exception) {
            android.util.Log.w("MusicRepository", "Error clearing palettes: ${e.message}")
        }

        // Delete all old generated artwork cache
        val deletedArt = artworkStorage.clearAllArtwork()
        artworkStorage.clearImageCache()

        android.util.Log.i("DIAG_METADATA_RESET", "tracksReset: ${resetTracks.size}")
        android.util.Log.i("DIAG_METADATA_RESET", "albumIdsCleared: $albumIdsCleared")
        android.util.Log.i("DIAG_METADATA_RESET", "artworkFilesDeleted: $deletedArt")
        android.util.Log.i("DIAG_METADATA_RESET", "resetCompleted: true")
        android.util.Log.i("DIAG_METADATA_RESET", "=== METADATA RESET COMPLETED ===")

        MetadataResetResult(
            tracksFound = tracksFound,
            tracksReset = resetTracks.size,
            albumIdsCleared = albumIdsCleared,
            artworkFilesDeleted = deletedArt,
            resetStarted = true,
            resetCompleted = true
        )
    }

    /**
     * One-time repair mechanism for Metadata and Artwork (V3).
     * 1. Cancels in-flight enrichment
     * 2. Clears remote artwork and cache
     * 3. Clears derived albums, artists, palettes
     * 4. Resets track metadata in DB to force fresh embedded extraction via scanDevice()
     * 5. Runs scanDevice() with strict identity matching
     */
    suspend fun repairMetadataAndArtwork(): MetadataResetResult = withContext(Dispatchers.IO) {
        android.util.Log.i("DIAG_METADATA_RESET", "=== METADATA & ARTWORK REPAIR V3 STARTED ===")
        enrichmentService.cancelAllEnrichment()

        val deletedArt = artworkStorage.clearAllArtwork()
        artworkStorage.clearImageCache()

        albumDao.deleteAll()
        artistDao.deleteAll()
        try {
            database.paletteDao().deleteAll()
        } catch (e: Exception) {
            android.util.Log.w("MusicRepository", "Error clearing palettes: ${e.message}")
        }

        val existingTracks = trackDao.getAllTracksOnce()
        val resetTracks = existingTracks.map { track ->
            track.copy(
                albumId = 0L,
                albumTitle = "",
                albumArtist = "",
                title = "",
                artist = "",
                genre = null,
                composer = null,
                trackNumber = 0,
                discNumber = 0,
                year = 0,
                artworkUri = null,
                artistArtworkUri = null,
                metadataStatus = MetadataStatus.NEEDS_LOOKUP,
                metadataSource = MetadataSource.EMBEDDED,
                metadataConfidence = MetadataConfidence.LOW,
                metadataLastUpdated = 0L,
            )
        }

        if (resetTracks.isNotEmpty()) {
            trackDao.insertAll(resetTracks)
        }

        userPreferences.setMetadataRepairV3Completed(true)
        userPreferences.setMetadataResetV2Completed(true)

        scanDevice()

        android.util.Log.i("DIAG_METADATA_RESET", "=== METADATA & ARTWORK REPAIR V3 COMPLETED ===")
        MetadataResetResult(
            tracksFound = existingTracks.size,
            tracksReset = resetTracks.size,
            albumIdsCleared = existingTracks.count { it.albumId != 0L },
            artworkFilesDeleted = deletedArt,
            resetStarted = true,
            resetCompleted = true
        )
    }

    /**
     * One-time clean artwork rebuild mechanism following rollback of retrieval pipeline:
     * 1. Cancels in-flight enrichment
     * 2. Clears remote/generated iTunes album artwork files and temporary cache
     * 3. Clears dynamic palette color cache
     * 4. Resets artwork URIs on tracks without genuine embedded artwork to null
     * 5. Strictly preserves music files, favorites, play counts, playlists, and stable IDs
     * 6. Triggers background enrichment using the restored retrieval implementation
     */
    suspend fun rebuildArtworkCleanly(): MetadataResetResult = withContext(Dispatchers.IO) {
        android.util.Log.i("DIAG_ARTWORK_RESET", "=== CLEAN ARTWORK REBUILD STARTED ===")
        enrichmentService.cancelAllEnrichment()

        val deletedArt = artworkStorage.clearRemoteArtwork()
        artworkStorage.clearImageCache()

        try {
            database.paletteDao().deleteAll()
        } catch (e: Exception) {
            android.util.Log.w("MusicRepository", "Error clearing palettes: ${e.message}")
        }

        val existingTracks = trackDao.getAllTracksOnce()
        val tracksToReset = existingTracks.map { track ->
            val hasEmbedded = !track.artworkUri.isNullOrBlank() &&
                    (ArtworkStorage.isEmbeddedArtwork(track.artworkUri) || track.metadataSource == MetadataSource.EMBEDDED)
            if (!hasEmbedded) {
                track.copy(
                    artworkUri = null,
                    artistArtworkUri = null,
                    metadataStatus = MetadataStatus.NEEDS_LOOKUP,
                    metadataConfidence = MetadataConfidence.LOW,
                    metadataLastUpdated = 0L,
                )
            } else {
                track
            }
        }
        if (tracksToReset.isNotEmpty()) {
            trackDao.insertAll(tracksToReset)
        }

        val existingAlbums = albumDao.getAllAlbumsOnce()
        val resetAlbums = existingAlbums.map { album ->
            val hasEmbedded = !album.artworkUri.isNullOrBlank() && ArtworkStorage.isEmbeddedArtwork(album.artworkUri)
            if (!hasEmbedded) {
                album.copy(artworkUri = null)
            } else {
                album
            }
        }
        if (resetAlbums.isNotEmpty()) {
            albumDao.insertAll(resetAlbums)
        }

        userPreferences.setArtworkForensicFixV4Completed(true)
        userPreferences.setArtworkRollbackCompleted(true)

        val tracksNeedingArt = trackDao.getAllTracksOnce().filter { enrichmentService.needsEnrichment(it) }
        enrichmentService.enqueueEnrichment(tracksNeedingArt)

        android.util.Log.i("DIAG_ARTWORK_RESET", "=== CLEAN ARTWORK REBUILD COMPLETED ===")
        MetadataResetResult(
            tracksFound = existingTracks.size,
            tracksReset = tracksToReset.count { it.artworkUri == null },
            albumIdsCleared = 0,
            artworkFilesDeleted = deletedArt,
            resetStarted = true,
            resetCompleted = true
        )
    }

    /**
     * Ensures the one-time forensic artwork clean rebuild is executed once to purge bad/corrupted remote art.
     */
    suspend fun ensureArtworkForensicFixCompleted(): Boolean = withContext(Dispatchers.IO) {
        if (!userPreferences.isArtworkForensicFixV4Completed()) {
            userPreferences.setArtworkForensicFixV4Completed(true)
            userPreferences.setArtworkRollbackCompleted(true)
            rebuildArtworkCleanly()
            true
        } else {
            false
        }
    }

    /**
     * Ensures the one-time artwork rollback clean rebuild is executed once across app versions.
     */
    suspend fun ensureArtworkRollbackRebuildCompleted(): Boolean = withContext(Dispatchers.IO) {
        if (!userPreferences.isArtworkRollbackCompleted()) {
            userPreferences.setArtworkRollbackCompleted(true)
            rebuildArtworkCleanly()
            true
        } else {
            false
        }
    }

    /**
     * Ensures the one-time V3 clean metadata and artwork repair is executed once and only once.
     */
    suspend fun ensureMetadataRepairV3Completed(): Boolean = withContext(Dispatchers.IO) {
        if (!userPreferences.isMetadataRepairV3Completed()) {
            userPreferences.setMetadataRepairV3Completed(true)
            repairMetadataAndArtwork()
            true
        } else {
            false
        }
    }

    /**
     * Ensures the one-time clean metadata reset is executed once and only once across app versions.
     */
    suspend fun ensureMetadataResetV2Completed(): Boolean = withContext(Dispatchers.IO) {
        if (!userPreferences.isMetadataResetV2Completed()) {
            performMetadataReset()
            userPreferences.setMetadataResetV2Completed(true)
            true
        } else {
            false
        }
    }

    /**
     * Manual user-initiated or diagnostic action:
     * Clears all old metadata and artwork, then triggers a fresh scan and enrichment.
     */
    suspend fun resetAndRebuildMetadata(): MetadataResetResult = withContext(Dispatchers.IO) {
        val result = performMetadataReset()
        userPreferences.setMetadataResetV2Completed(true)
        userPreferences.setMetadataRepairV3Completed(true)
        userPreferences.setArtworkRollbackCompleted(true)
        scanDevice()
        result
    }
}

data class MetadataResetResult(
    val tracksFound: Int,
    val tracksReset: Int,
    val albumIdsCleared: Int,
    val artworkFilesDeleted: Int,
    val resetStarted: Boolean = true,
    val resetCompleted: Boolean = true,
)
