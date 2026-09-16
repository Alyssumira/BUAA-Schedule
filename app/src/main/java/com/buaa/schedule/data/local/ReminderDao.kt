package com.buaa.schedule.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ReminderDao {
    @Query("SELECT * FROM reminders")
    fun observeAll(): Flow<List<ReminderEntity>>

    @Query("SELECT * FROM reminders")
    suspend fun getAll(): List<ReminderEntity>

    @Query("SELECT * FROM reminders WHERE courseId = :courseId LIMIT 1")
    suspend fun getByCourse(courseId: Long): ReminderEntity?

    /**
     * 一次取回若干课程的提醒（组删除前快照用）。
     *
     * ⚠️ 与 [deleteByCourses] 同理：`courseIds` 为空时 Room 会生成 `IN ()`，
     * SQLite 直接抛错，调用方必须先判空。
     */
    @Query("SELECT * FROM reminders WHERE courseId IN (:courseIds)")
    suspend fun getByCourses(courseIds: List<Long>): List<ReminderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(reminder: ReminderEntity)

    @Query("DELETE FROM reminders WHERE courseId = :courseId")
    suspend fun deleteByCourse(courseId: Long)

    /**
     * 批量删除若干课程的提醒。
     *
     * ⚠️ `courseIds` 为空时 Room 会生成 `IN ()`，SQLite 不接受、会直接抛错，
     * 调用方必须先判空。
     */
    @Query("DELETE FROM reminders WHERE courseId IN (:courseIds)")
    suspend fun deleteByCourses(courseIds: List<Long>)
}