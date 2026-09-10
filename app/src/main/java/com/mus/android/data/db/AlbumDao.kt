package com.mus.android.data.db

import androidx.room.*
import com.mus.android.data.model.Album
import kotlinx.coroutines.flow.Flow

@Dao
interface AlbumDao {
    @Query("SELECT * FROM albums ORDER BY title ASC")
    fun getAllAlbums(): Flow<List<Album>>

    @Query("SELECT * FROM albums WHERE id = :id")
    suspend fun getAlbumById(id: Long): Album?

    @Query("SELECT * FROM albums")
    suspend fun getAllAlbumsOnce(): List<Album>

    @Query("SELECT * FROM albums WHERE artist = :artist ORDER BY year DESC")
    fun getAlbumsByArtist(artist: String): Flow<List<Album>>

    @Query("SELECT * FROM albums WHERE title LIKE '%' || :query || '%' OR artist LIKE '%' || :query || '%' ORDER BY title ASC")
    fun search(query: String): Flow<List<Album>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(albums: List<Album>)

    @Query("DELETE FROM albums")
    suspend fun deleteAll()

    @Query("SELECT * FROM albums ORDER BY RANDOM() LIMIT :limit")
    fun getRandomAlbums(limit: Int = 10): Flow<List<Album>>

    @Query("DELETE FROM albums WHERE id IN (:ids)")
    suspend fun deleteAlbumsByIds(ids: List<Long>)
}
