package com.mus.android.data.repository

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
    private val trackDao: TrackDao,
    private val albumDao: AlbumDao,
    private val artistDao: ArtistDao,
    private val playlistDao: PlaylistDao,
    private val waveformDao: WaveformDao,
    private val scanner: MediaStoreScanner,
    private val waveformExtractor: WaveformExtractor,
) {
    init {
        CoroutineScope(Dispatchers.IO).launch {
            ensureDefaultPlaylists()
        }
    }

    suspend fun ensureDefaultPlaylists() = withContext(Dispatchers.IO) {
        getOrCreatePlaylist(com.mus.android.data.classifier.LanguageClassifier.PLAYLIST_HINDI)
        getOrCreatePlaylist(com.mus.android.data.classifier.LanguageClassifier.PLAYLIST_ENGLISH)
        getOrCreatePlaylist(com.mus.android.data.classifier.LanguageClassifier.PLAYLIST_REGIONAL)
    }

    // ── Scanning ──────────────────────────────────────────────
    suspend fun scanDevice(): MediaStoreScanner.ScanResult = withContext(Dispatchers.IO) {
        val result = scanner.scan()
        trackDao.deleteAll()
        albumDao.deleteAll()
        artistDao.deleteAll()
        // Preserve favorites
        val existingFavorites = mutableSetOf<Long>()
        // Insert fresh scan data
        trackDao.insertAll(result.tracks)
        albumDao.insertAll(result.albums)
        artistDao.insertAll(result.artists)

        // Automatically populate default language playlists: Hindi, English, Regional
        populateLanguagePlaylists(result.tracks)

        result
    }

    private suspend fun populateLanguagePlaylists(tracks: List<Track>) {
        val hindiPlaylist = getOrCreatePlaylist(com.mus.android.data.classifier.LanguageClassifier.PLAYLIST_HINDI)
        playlistDao.clearPlaylistTracks(hindiPlaylist.id)
        val hindiTracks = tracks.filter { it.language == com.mus.android.data.classifier.LanguageClassifier.HINDI }
        hindiTracks.forEachIndexed { index, track ->
            playlistDao.insertPlaylistTrack(
                PlaylistTrack(playlistId = hindiPlaylist.id, trackId = track.id, position = index)
            )
        }

        val englishPlaylist = getOrCreatePlaylist(com.mus.android.data.classifier.LanguageClassifier.PLAYLIST_ENGLISH)
        playlistDao.clearPlaylistTracks(englishPlaylist.id)
        val englishTracks = tracks.filter { it.language == com.mus.android.data.classifier.LanguageClassifier.ENGLISH }
        englishTracks.forEachIndexed { index, track ->
            playlistDao.insertPlaylistTrack(
                PlaylistTrack(playlistId = englishPlaylist.id, trackId = track.id, position = index)
            )
        }

        val regionalPlaylist = getOrCreatePlaylist(com.mus.android.data.classifier.LanguageClassifier.PLAYLIST_REGIONAL)
        playlistDao.clearPlaylistTracks(regionalPlaylist.id)
        val regionalTracks = tracks.filter { it.language == com.mus.android.data.classifier.LanguageClassifier.REGIONAL }
        regionalTracks.forEachIndexed { index, track ->
            playlistDao.insertPlaylistTrack(
                PlaylistTrack(playlistId = regionalPlaylist.id, trackId = track.id, position = index)
            )
        }
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
    suspend fun getTrackById(id: Long): Track? = trackDao.getTrackById(id)
    fun getTracksByAlbum(albumId: Long): Flow<List<Track>> = trackDao.getTracksByAlbum(albumId)
    fun getTracksByArtist(artist: String): Flow<List<Track>> = trackDao.getTracksByArtist(artist)
    fun getTracksByLanguage(language: String): Flow<List<Track>> = trackDao.getTracksByLanguage(language)
    fun searchTracks(query: String): Flow<List<Track>> = trackDao.search(query)
    suspend fun getTrackCount(): Int = trackDao.getTrackCount()

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
        playlistDao.deletePlaylistById(playlistId)
    }

    suspend fun addTrackToPlaylist(playlistId: Long, trackId: Long) {
        val maxPos = playlistDao.getMaxPosition(playlistId) ?: -1
        playlistDao.insertPlaylistTrack(
            PlaylistTrack(
                playlistId = playlistId,
                trackId = trackId,
                position = maxPos + 1,
            )
        )
        val playlist = playlistDao.getPlaylistById(playlistId) ?: return
        playlistDao.updatePlaylist(playlist.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun removeTrackFromPlaylist(playlistId: Long, trackId: Long) {
        playlistDao.removeTrackFromPlaylist(playlistId, trackId)
    }

    suspend fun getPlaylistTrackCount(playlistId: Long): Int {
        return playlistDao.getPlaylistTrackCount(playlistId)
    }

    // ── Waveform ──────────────────────────────────────────────
    suspend fun getWaveform(trackId: Long, uri: String): List<Float>? {
        return waveformExtractor.getWaveform(trackId, uri)
    }
}
