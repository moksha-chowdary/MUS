package com.mus.android.data.db

import androidx.room.*
import com.mus.android.data.model.ArtworkPalette

@Dao
interface PaletteDao {
    @Query("SELECT * FROM artwork_palettes WHERE artworkUri = :artworkUri")
    suspend fun getPalette(artworkUri: String): ArtworkPalette?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(palette: ArtworkPalette)

    @Query("DELETE FROM artwork_palettes")
    suspend fun deleteAll()
}
