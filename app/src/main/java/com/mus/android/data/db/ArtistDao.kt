package com.mus.android.data.db

import androidx.room.*
import com.mus.android.data.model.Artist
import kotlinx.coroutines.flow.Flow

@Dao
interface ArtistDao {
    @Query("SELECT * FROM artists ORDER BY name ASC")
    fun getAllArtists(): Flow<List<Artist>>

    @Query("SELECT * FROM artists WHERE id = :id")
    suspend fun getArtistById(id: Long): Artist?

    @Query("SELECT * FROM artists")
    suspend fun getAllArtistsOnce(): List<Artist>

    @Query("SELECT * FROM artists WHERE name LIKE '%' || :query || '%' ORDER BY name ASC")
    fun search(query: String): Flow<List<Artist>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(artists: List<Artist>)

    @Query("DELETE FROM artists")
    suspend fun deleteAll()

    @Query("DELETE FROM artists WHERE id IN (:ids)")
    suspend fun deleteArtistsByIds(ids: List<Long>)
}
