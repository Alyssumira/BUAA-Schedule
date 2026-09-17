package com.buaa.schedule.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CourseDao {
    @Query("SELECT * FROM courses ORDER BY dayOfWeek, id")
    fun observeAll(): Flow<List<CourseEntity>>

    @Query("SELECT * FROM courses ORDER BY dayOfWeek, id")
    suspend fun getAll(): List<CourseEntity>

    @Query("SELECT * FROM courses WHERE id = :id")
    suspend fun getById(id: Long): CourseEntity?

    @Query("SELECT * FROM courses WHERE sourceGroupKey = :groupKey")
    suspend fun getByGroupKey(groupKey: String): List<CourseEntity>

    @Query("SELECT * FROM courses WHERE semesterCode = :semesterCode ORDER BY dayOfWeek, id")
    suspend fun getBySemester(semesterCode: String): List<CourseEntity>

    /** 展示口径：手动课程（semesterCode 为空） + 指定学期，避免读全表后在内存过滤 */
    @Query("SELECT * FROM courses WHERE semesterCode IS NULL OR semesterCode = :semesterCode ORDER BY dayOfWeek, id")
    suspend fun getDisplay(semesterCode: String): List<CourseEntity>

    /**
     * 手动课程（semesterCode 为空）。走 semesterCode 索引，
     * 恢复流程此前是 `getAll()` 后再在内存里过滤，全表白扫一遍。
     */
    @Query("SELECT * FROM courses WHERE semesterCode IS NULL")
    suspend fun getManualCourses(): List<CourseEntity>

    /**
     * 指定若干学期的课程 + 全部手动课程（恢复流程按 courseKey 回查 id 用）。
     *
     * ⚠️ `codes` 为空时 Room 会生成 `IN ()`，SQLite 不接受、会直接抛错，
     * 因此调用方必须在空列表时改用 [getManualCourses]。
     */
    @Query("SELECT * FROM courses WHERE semesterCode IN (:codes) OR semesterCode IS NULL")
    suspend fun getBySemestersOrManual(codes: List<String>): List<CourseEntity>

    @Query("DELETE FROM courses WHERE semesterCode = :semesterCode")
    suspend fun deleteBySemester(semesterCode: String)

    /** 学期重命名：把挂在旧代码下的课程整体改挂到新代码（提醒按 courseId 关联，无需动） */
    @Query("UPDATE courses SET semesterCode = :newCode WHERE semesterCode = :oldCode")
    suspend fun reassignSemester(oldCode: String, newCode: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(course: CourseEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(courses: List<CourseEntity>)

    @Update
    suspend fun update(course: CourseEntity)

    @Delete
    suspend fun delete(course: CourseEntity)

    @Query("DELETE FROM courses")
    suspend fun clearAll()
}
