package com.buaa.schedule.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ImportHistoryDao {
    @Query("SELECT * FROM import_history ORDER BY importedAt DESC")
    fun observeAll(): Flow<List<ImportHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(history: ImportHistoryEntity): Long

    @Query("DELETE FROM import_history")
    suspend fun clearAll()
}
