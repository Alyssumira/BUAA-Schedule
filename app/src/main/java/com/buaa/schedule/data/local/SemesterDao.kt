package com.buaa.schedule.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SemesterDao {
    @Query("SELECT * FROM semesters ORDER BY id DESC LIMIT 1")
    fun observeCurrent(): Flow<SemesterEntity?>

    @Query("SELECT * FROM semesters ORDER BY id DESC LIMIT 1")
    suspend fun getCurrent(): SemesterEntity?

    /** 全部已存储学期（多课表切换用），按开学日期倒序 */
    @Query("SELECT * FROM semesters ORDER BY startDate DESC")
    fun observeAll(): Flow<List<SemesterEntity>>

    @Query("SELECT * FROM semesters ORDER BY startDate DESC")
    suspend fun getAll(): List<SemesterEntity>

    @Query("SELECT * FROM semesters WHERE termCode = :termCode LIMIT 1")
    suspend fun getByTermCode(termCode: String): SemesterEntity?

    @Query("DELETE FROM semesters WHERE termCode = :termCode")
    suspend fun deleteByTermCode(termCode: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(semester: SemesterEntity): Long
}
