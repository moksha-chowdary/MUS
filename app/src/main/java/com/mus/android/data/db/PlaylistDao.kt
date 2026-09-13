package com.mus.android.data.db

import androidx.room.*
import com.mus.android.data.model.Playlist
import com.mus.android.data.model.PlaylistTrack
import com.mus.android.data.model.Track
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY updatedAt DESC")
    fun getAllPlaylists(): Flow<List<Playlist>>

    @Query("SELECT * FROM playlists ORDER BY name COLLATE NOCASE ASC")
    suspend fun getAllPlaylistsOnce(): List<Playlist>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun getPlaylistById(id: Long): Playlist?

    @Query("SELECT * FROM playlists WHERE name = :name LIMIT 1")
    suspend fun getPlaylistByName(name: String): Playlist?

    @Query("SELECT * FROM playlists WHERE systemKey = :systemKey LIMIT 1")
    suspend fun getPlaylistBySystemKey(systemKey: String): Playlist?

    @Query("SELECT * FROM playlists WHERE isSystemPlaylist = 1")
    suspend fun getSystemPlaylists(): List<Playlist>

    @Query("SELECT * FROM playlists WHERE isSystemPlaylist = 1")
    fun observeSystemPlaylists(): Flow<List<Playlist>>

    @Query("SELECT COUNT(*) FROM playlists")
    suspend fun getPlaylistCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: Playlist): Long

    @Update
    suspend fun updatePlaylist(playlist: Playlist)

    @Delete
    suspend fun deletePlaylist(playlist: Playlist)

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    suspend fun deletePlaylistById(playlistId: Long)

    // Playlist tracks
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPlaylistTrack(playlistTrack: PlaylistTrack): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPlaylistTracks(playlistTracks: List<PlaylistTrack>): List<Long>

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId AND trackId = :trackId")
    suspend fun removeTrackFromPlaylist(playlistId: Long, trackId: Long)

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId AND trackId IN (:trackIds)")
    suspend fun removeTracksFromPlaylist(playlistId: Long, trackIds: List<Long>)

    @Query("SELECT playlistId FROM playlist_tracks WHERE trackId = :trackId")
    suspend fun getPlaylistIdsForTrack(trackId: Long): List<Long>

    @Query("SELECT trackId FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun getTrackIdsForPlaylist(playlistId: Long): List<Long>

    @Query("SELECT playlistId FROM playlist_tracks WHERE trackId = :trackId")
    fun observePlaylistIdsForTrack(trackId: Long): Flow<List<Long>>

    @Query("""
        SELECT t.* FROM tracks t 
        INNER JOIN playlist_tracks pt ON t.id = pt.trackId 
        WHERE pt.playlistId = :playlistId 
        ORDER BY pt.position ASC
    """)
    fun getPlaylistTracks(playlistId: Long): Flow<List<Track>>

    @Query("SELECT COUNT(*) FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun getPlaylistTrackCount(playlistId: Long): Int

    @Query("SELECT COUNT(*) FROM playlist_tracks WHERE playlistId = :playlistId AND trackId = :trackId")
    suspend fun isTrackInPlaylist(playlistId: Long, trackId: Long): Int

    @Query("SELECT MAX(position) FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun getMaxPosition(playlistId: Long): Int?

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun clearPlaylistTracks(playlistId: Long)

    @Transaction
    suspend fun moveTracksTransaction(
        sourcePlaylistId: Long,
        destinationPlaylistId: Long,
        trackIds: List<Long>
    ): Int {
        if (trackIds.isEmpty() || sourcePlaylistId == destinationPlaylistId) return 0
        removeTracksFromPlaylist(sourcePlaylistId, trackIds)
        val sourcePlaylist = getPlaylistById(sourcePlaylistId)
        if (sourcePlaylist != null) {
            updatePlaylist(sourcePlaylist.copy(updatedAt = System.currentTimeMillis()))
        }

        var currentMaxPos = getMaxPosition(destinationPlaylistId) ?: -1
        val tracksToInsert = mutableListOf<PlaylistTrack>()
        for (trackId in trackIds) {
            if (isTrackInPlaylist(destinationPlaylistId, trackId) == 0) {
                currentMaxPos++
                tracksToInsert.add(
                    PlaylistTrack(
                        playlistId = destinationPlaylistId,
                        trackId = trackId,
                        position = currentMaxPos,
                        addedAt = System.currentTimeMillis()
                    )
                )
            }
        }
        if (tracksToInsert.isNotEmpty()) {
            insertPlaylistTracks(tracksToInsert)
        }
        val destPlaylist = getPlaylistById(destinationPlaylistId)
        if (destPlaylist != null) {
            updatePlaylist(destPlaylist.copy(updatedAt = System.currentTimeMillis()))
        }
        return tracksToInsert.size
    }

    @Transaction
    suspend fun copyTracksTransaction(
        destinationPlaylistId: Long,
        trackIds: List<Long>
    ): Int {
        if (trackIds.isEmpty()) return 0
        var currentMaxPos = getMaxPosition(destinationPlaylistId) ?: -1
        val tracksToInsert = mutableListOf<PlaylistTrack>()
        for (trackId in trackIds) {
            if (isTrackInPlaylist(destinationPlaylistId, trackId) == 0) {
                currentMaxPos++
                tracksToInsert.add(
                    PlaylistTrack(
                        playlistId = destinationPlaylistId,
                        trackId = trackId,
                        position = currentMaxPos,
                        addedAt = System.currentTimeMillis()
                    )
                )
            }
        }
        if (tracksToInsert.isNotEmpty()) {
            insertPlaylistTracks(tracksToInsert)
        }
        val destPlaylist = getPlaylistById(destinationPlaylistId)
        if (destPlaylist != null) {
            updatePlaylist(destPlaylist.copy(updatedAt = System.currentTimeMillis()))
        }
        return tracksToInsert.size
    }

    @Transaction
    suspend fun removeTracksTransaction(
        playlistId: Long,
        trackIds: List<Long>
    ) {
        if (trackIds.isEmpty()) return
        removeTracksFromPlaylist(playlistId, trackIds)
        val playlist = getPlaylistById(playlistId)
        if (playlist != null) {
            updatePlaylist(playlist.copy(updatedAt = System.currentTimeMillis()))
        }
    }
}
