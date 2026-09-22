package com.mus.android.data.db

import androidx.room.*
import com.mus.android.data.model.DownloadQueueItem
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadQueueDao {

    @Query("SELECT * FROM download_queue ORDER BY dateAdded DESC")
    fun observeAll(): Flow<List<DownloadQueueItem>>

    @Query("SELECT * FROM download_queue ORDER BY dateAdded DESC")
    suspend fun getAllOnce(): List<DownloadQueueItem>

    @Query("SELECT * FROM download_queue WHERE id = :id")
    suspend fun getById(id: String): DownloadQueueItem?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: DownloadQueueItem): Long

    @Query("DELETE FROM download_queue WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE download_queue SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)

    @Query("SELECT COUNT(*) FROM download_queue WHERE id = :id")
    suspend fun exists(id: String): Int

    @Query("SELECT COUNT(*) FROM download_queue WHERE status = 'TO_DOWNLOAD'")
    fun observePendingCount(): Flow<Int>
}
