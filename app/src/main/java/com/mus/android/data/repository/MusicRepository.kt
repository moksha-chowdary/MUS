package com.mus.android.data.repository

import androidx.room.withTransaction
import com.mus.android.data.db.*
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
) {
    private val _idMigrations = kotlinx.coroutines.flow.MutableSharedFlow<Map<Long, Long>>(replay = 0, extraBufferCapacity = 1)
    val idMigrations: kotlinx.coroutines.flow.SharedFlow<Map<Long, Long>> = _idMigrations

    init {
        CoroutineScope(Dispatchers.IO).launch {
            ensureDefaultPlaylists()
        }
    }

    suspend fun ensureDefaultPlaylists() = withContext(Dispatchers.IO) {
        for (def in com.mus.android.data.classifier.LanguageClassifier.SYSTEM_PLAYLISTS) {
            val existing = playlistDao.getPlaylistBySystemKey(def.systemKey)
                ?: playlistDao.getPlaylistByName(def.name)
            if (existing == null) {
                playlistDao.insertPlaylist(
                    Playlist(
                        name = def.name,
                        systemKey = def.systemKey,
                        isSystemPlaylist = true
                    )
                )
            } else if (existing.systemKey != def.systemKey || !existing.isSystemPlaylist) {
                playlistDao.updatePlaylist(
                    existing.copy(
                        systemKey = def.systemKey,
                        isSystemPlaylist = true
                    )
                )
            }
        }
    }

    suspend fun reconcileLanguagePlaylists(tracks: List<Track>) = withContext(Dispatchers.IO) {
        if (tracks.isEmpty()) return@withContext
        ensureDefaultPlaylists()

        val tracksBySystemKey = tracks.groupBy { track ->
            com.mus.android.data.classifier.LanguageClassifier.languageToSystemKey(track.language)
        }

        for ((systemKey, groupTracks) in tracksBySystemKey) {
            val playlist = playlistDao.getPlaylistBySystemKey(systemKey) ?: continue
            val existingTrackIds = playlistDao.getTrackIdsForPlaylist(playlist.id).toSet()
            val newTracksToAdd = groupTracks.filter { it.id !in existingTrackIds }
            if (newTracksToAdd.isEmpty()) continue

            var position = existingTrackIds.size
            val playlistTracks = newTracksToAdd.map { track ->
                PlaylistTrack(
                    playlistId = playlist.id,
                    trackId = track.id,
                    position = position++,
                )
            }
            playlistDao.insertPlaylistTracks(playlistTracks)
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

        // Automatically organize tracks into Telugu, Tamil, Hindi, English, and Other system playlists
        reconcileLanguagePlaylists(mergedTracks)

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

    // ── Waveform ──────────────────────────────────────────────
    suspend fun getWaveform(trackId: Long, uri: String): List<Float>? {
        return waveformExtractor.getWaveform(trackId, uri)
    }
}
