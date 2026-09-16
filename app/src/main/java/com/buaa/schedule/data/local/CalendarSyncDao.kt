package com.buaa.schedule.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CalendarSyncDao {
    @Query("SELECT * FROM calendar_sync")
    suspend fun getAll(): List<CalendarSyncEntity>

    @Query("SELECT * FROM calendar_sync WHERE calendarId = :calendarId")
    suspend fun getByCalendar(calendarId: Long): List<CalendarSyncEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<CalendarSyncEntity>)

    @Query("DELETE FROM calendar_sync WHERE occurrenceId IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("DELETE FROM calendar_sync")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM calendar_sync")
    suspend fun count(): Int
}
