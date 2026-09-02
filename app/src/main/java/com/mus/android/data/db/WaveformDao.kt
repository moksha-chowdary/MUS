package com.mus.android.data.db

import androidx.room.*
import com.mus.android.data.model.WaveformData

@Dao
interface WaveformDao {
    @Query("SELECT * FROM waveforms WHERE trackId = :trackId")
    suspend fun getWaveform(trackId: Long): WaveformData?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(waveform: WaveformData)

    @Query("DELETE FROM waveforms WHERE trackId = :trackId")
    suspend fun delete(trackId: Long)
}
