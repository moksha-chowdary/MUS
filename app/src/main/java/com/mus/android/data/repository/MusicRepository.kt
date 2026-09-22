package com.mus.android.data.repository

import androidx.room.withTransaction
import com.mus.android.data.db.*
import com.mus.android.data.model.*
import com.mus.android.data.scanner.MediaStoreScanner
import com.mus.android.data.scanner.MetadataUtils
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
    private val downloadQueueDao: DownloadQueueDao,
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
            checkAndRunMetadataMigration()
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

            val merged = result.tracks.map { scannedTrack ->
                val scannedMuzicPath = scannedTrack.path?.let { com.mus.android.data.scanner.MetadataUtils.extractMuzicRelativePath(it) }
                val existing = existingById[scannedTrack.id]
                    ?: existingByUri[scannedTrack.path ?: scannedTrack.uri]
                    ?: scannedMuzicPath?.let { existingByMuzicPath[it] }
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
                        year = if (existing.year > 0) existing.year else scannedTrack.year,
                        genre = existing.genre ?: scannedTrack.genre,
                        language = if (wasEnriched || (existing.language.isNotBlank() && existing.language != "Unknown")) {
                            existing.language
                        } else {
                            scannedTrack.language
                        },
                        composer = existing.composer ?: scannedTrack.composer,
                        metadataSource = if (wasEnriched) existing.metadataSource else scannedTrack.metadataSource,
                        metadataStatus = if (wasEnriched) existing.metadataStatus else scannedTrack.metadataStatus,
                        metadataConfidence = if (wasEnriched) existing.metadataConfidence else scannedTrack.metadataConfidence,
                        metadataLastUpdated = if (existing.metadataLastUpdated > 0) existing.metadataLastUpdated else scannedTrack.metadataLastUpdated,
                        isFavorite = existing.isFavorite,
                        playCount = existing.playCount,
                        lastPlayed = existing.lastPlayed
                    )
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

        // Use metadataStatus as the state machine to filter enrichment queue (Requirement 3)
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

    suspend fun refreshAllMetadata() = withContext(Dispatchers.IO) {
        val allTracks = trackDao.getAllTracksOnce()
        for (track in allTracks) {
            enrichmentService.enrichTrack(track, forceRefresh = true)
        }
    }

    suspend fun refreshTrackMetadata(trackId: Long) = withContext(Dispatchers.IO) {
        val track = trackDao.getTrackById(trackId) ?: return@withContext
        enrichmentService.enrichTrack(track, forceRefresh = true)
    }

    suspend fun clearArtworkAndReenrich(trackId: Long): Track? = withContext(Dispatchers.IO) {
        val track = trackDao.getTrackById(trackId) ?: return@withContext null
        artworkStorage.clearArtwork(track.albumId)

        val resetTrack = track.copy(
            artworkUri = null,
            metadataStatus = MetadataStatus.NEEDS_LOOKUP,
            metadataConfidence = MetadataConfidence.LOW,
            metadataLastUpdated = 0L,
        )
        trackDao.update(resetTrack)

        val album = albumDao.getAlbumById(track.albumId)
        if (album != null) {
            albumDao.insertAll(listOf(album.copy(artworkUri = null)))
        }

        enrichmentService.enrichTrack(resetTrack, forceRefresh = true)
    }

    private suspend fun checkAndRunMetadataMigration() {
        if (userPreferences.getMetadataMigrationVersion() < 2) {
            reverifyLibrary(forceAll = false)
            userPreferences.setMetadataMigrationVersion(2)
        }
    }

    /**
     * Re-evaluates metadata and artwork across the library.
     * Resets tracks that were matched at low/medium confidence, flagged for review,
     * or missing fields. Safely purges non-embedded remote artwork cache while preserving
     * embedded artwork. Safe to call multiple times.
     */
    suspend fun reverifyLibrary(forceAll: Boolean = false) = withContext(Dispatchers.IO) {
        val allTracks = trackDao.getAllTracksOnce()
        val tracksToReverify = mutableListOf<Track>()

        for (track in allTracks) {
            val isLowConfidence = track.metadataConfidence != MetadataConfidence.HIGH
            val isReviewOrPartial = track.metadataStatus == MetadataStatus.NEEDS_REVIEW ||
                    track.metadataStatus == MetadataStatus.PARTIAL ||
                    track.metadataStatus == MetadataStatus.FAILED
            val isPlaceholderText = MetadataUtils.isPlaceholderTitle(track.title) ||
                    MetadataUtils.isPlaceholderArtist(track.artist) ||
                    MetadataUtils.isPlaceholderAlbum(track.albumTitle)

            if (forceAll || isLowConfidence || isReviewOrPartial || isPlaceholderText) {
                // Safely purge remote artwork if no embedded artwork is present
                val hasEmbedded = artworkStorage.isEmbeddedArtwork(track.artworkUri) ||
                        artworkStorage.hasEmbeddedArtwork(track.albumId)
                if (!hasEmbedded) {
                    artworkStorage.clearRemoteArtwork(track.albumId)
                    val album = albumDao.getAlbumById(track.albumId)
                    if (album != null && !artworkStorage.isEmbeddedArtwork(album.artworkUri)) {
                        albumDao.insertAll(listOf(album.copy(artworkUri = null)))
                    }
                }

                val resetTrack = track.copy(
                    artworkUri = if (!hasEmbedded) null else track.artworkUri,
                    metadataStatus = MetadataStatus.NEEDS_LOOKUP,
                    metadataConfidence = MetadataConfidence.LOW,
                    metadataLastUpdated = 0L,
                )
                trackDao.update(resetTrack)
                tracksToReverify.add(resetTrack)
            }
        }

        if (tracksToReverify.isNotEmpty()) {
            enrichmentService.enqueueEnrichment(tracksToReverify)
        }
    }

    // ── Waveform ──────────────────────────────────────────────
    suspend fun getWaveform(trackId: Long, uri: String): List<Float>? {
        return waveformExtractor.getWaveform(trackId, uri)
    }

    // ── Manual Artwork ────────────────────────────────────────

    /**
     * Applies a user-selected artwork result to a single track.
     * Downloads the artwork to persistent storage, writes MANUAL provenance,
     * and updates only that track's Room row.
     *
     * Does NOT trigger any rescan or metadata enrichment.
     * Does NOT affect any other tracks.
     *
     * Returns the updated Track, or null if the track was not found.
     */
    suspend fun applyManualArtwork(
        trackId: Long,
        artworkUrl: String,
        provider: String,
        remoteId: String?,
    ): Track? = withContext(Dispatchers.IO) {
        val track = trackDao.getTrackById(trackId) ?: return@withContext null

        // Download artwork to persistent local storage using a MANUAL-scoped key
        val artworkKey = "manual_track_${trackId}"
        val localUri = artworkStorage.downloadAndStoreArtworkByKey(
            artworkKey = artworkKey,
            imageUrl = artworkUrl,
            forceOverwrite = true,
        ) ?: return@withContext null

        val updated = track.copy(
            artworkUri = localUri,
            artworkSource = ArtworkSource.MANUAL,
            artworkProvider = provider,
            artworkRemoteId = remoteId,
            artworkLastUpdated = System.currentTimeMillis(),
        )
        trackDao.update(updated)

        // Also update the Album row so the album page reflects the change immediately
        val album = albumDao.getAlbumById(track.albumId)
        if (album != null) {
            albumDao.insertAll(listOf(album.copy(artworkUri = localUri)))
        }
        updated
    }

    /**
     * Applies a user-selected artwork to every track that belongs to the given album.
     * Scoped strictly to tracks matching [albumId] — never affects tracks in other albums,
     * even if they share the same artist name.
     *
     * Downloads a single copy of the artwork and shares the local URI across all affected tracks.
     *
     * Returns the number of tracks updated.
     */
    suspend fun applyManualArtworkToAlbum(
        albumId: Long,
        artworkUrl: String,
        provider: String,
        remoteId: String?,
    ): Int = withContext(Dispatchers.IO) {
        if (albumId <= 0L) return@withContext 0

        // Download once, share across all affected tracks
        val artworkKey = "manual_album_${albumId}"
        val localUri = artworkStorage.downloadAndStoreArtworkByKey(
            artworkKey = artworkKey,
            imageUrl = artworkUrl,
            forceOverwrite = true,
        ) ?: return@withContext 0

        // Fetch only tracks that belong to this exact album identity
        val albumTracks = trackDao.getAllTracksOnce().filter { it.albumId == albumId }
        val now = System.currentTimeMillis()

        var updatedCount = 0
        database.withTransaction {
            for (track in albumTracks) {
                val updated = track.copy(
                    artworkUri = localUri,
                    artworkSource = ArtworkSource.MANUAL,
                    artworkProvider = provider,
                    artworkRemoteId = remoteId,
                    artworkLastUpdated = now,
                )
                trackDao.update(updated)
                updatedCount++
            }

            // Update Album entity
            val album = albumDao.getAlbumById(albumId)
            if (album != null) {
                albumDao.insertAll(listOf(album.copy(artworkUri = localUri)))
            }
        }
        updatedCount
    }

    // ── Download Queue ("MUS — To Download") ──────────────────

    /** Observes the full To Download list, ordered by date added desc. */
    fun getDownloadQueue(): kotlinx.coroutines.flow.Flow<List<DownloadQueueItem>> =
        downloadQueueDao.observeAll()

    /** Observes the count of items still in TO_DOWNLOAD status. */
    fun observePendingDownloadCount(): kotlinx.coroutines.flow.Flow<Int> =
        downloadQueueDao.observePendingCount()

    /**
     * Adds an online result to the To Download planning list.
     * Deduplicates by "${source}_${sourceId}" — adding the same source+ID twice is a no-op.
     *
     * MUS never downloads audio. This is a metadata-only planning record.
     *
     * Returns true if the item was newly added, false if it was already present.
     */
    suspend fun addToDownloadQueue(
        result: com.mus.android.data.enrichment.provider.OnlineSearchResult,
    ): Boolean = withContext(Dispatchers.IO) {
        val id = "${result.source}_${result.sourceId}"
        if (downloadQueueDao.exists(id) > 0) return@withContext false

        val item = DownloadQueueItem(
            id = id,
            title = result.title,
            artist = result.artist,
            album = result.album,
            artworkUrl = result.artworkUrl,
            source = result.source,
            sourceId = result.sourceId,
            sourceUrl = result.sourceUrl,
            dateAdded = System.currentTimeMillis(),
            status = DownloadStatus.TO_DOWNLOAD,
        )
        downloadQueueDao.insert(item) > 0
    }

    /**
     * Removes an item from the To Download list by its composite ID.
     * Does NOT delete any local audio files.
     */
    suspend fun removeFromDownloadQueue(id: String) = withContext(Dispatchers.IO) {
        downloadQueueDao.deleteById(id)
    }

    /**
     * Updates the status of a To Download item (e.g. mark as DOWNLOADED, ADDED_TO_LIBRARY, FAILED).
     */
    suspend fun updateDownloadQueueStatus(id: String, status: String) = withContext(Dispatchers.IO) {
        downloadQueueDao.updateStatus(id, status)
    }

    suspend fun getDownloadQueueItemById(id: String): DownloadQueueItem? = withContext(Dispatchers.IO) {
        downloadQueueDao.getById(id)
    }
}
