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

    /**
     * 按 termCode 取学期行。
     *
     * `ORDER BY id DESC` 不是性能写法而是**判据**（#460）：`semesters` 表没有 termCode 的唯一索引，
     * 数据库层面不禁止同 termCode 多行，而 `LIMIT 1` 在无 `ORDER BY` 时交出哪一枚由扫描顺序决定 ——
     * 同一问题两次读数可以给出两个答案。加上它之后，这一条与本文件
     * [getCurrent] / [observeCurrent] 的 `ORDER BY id DESC LIMIT 1` 同口径
     * （「最近写入的那一行 = 当前学期」），取法在盘上是确定的。
     * 形状守卫 `data/local/SemesterRowShapeGuardTest` 钉这一条 SQL 逐字，
     * 口径本体在 `data/local/SemesterRowPolicy.kt` 的 [pickSemesterRowForTermCode]。
     *
     * 旧形状（T249 之前逐字）：`SELECT * FROM semesters WHERE termCode = :termCode LIMIT 1`
     * —— 全文件三族查询里只有这一族没有 ORDER BY，守卫按「抹掉注释之后 0 处」钉它不许回来。
     */
    @Query("SELECT * FROM semesters WHERE termCode = :termCode ORDER BY id DESC LIMIT 1")
    suspend fun getByTermCode(termCode: String): SemesterEntity?

    /**
     * 同一 termCode 的**全部**行（id 降序，枚数不作依据）。
     *
     * 只有「切换当前学期」用得上它：那一跳不改写任何内容，所以它必须先知道这一 termCode 到底有几枚行、
     * 再决定只动其中哪一枚（见 `planSemesterRowBump`）。其余写路（保存 / 导入 / 恢复）走
     * [deleteByTermCode] 的 termCode 级收口，不需要读列表。
     */
    @Query("SELECT * FROM semesters WHERE termCode = :termCode ORDER BY id DESC")
    suspend fun getRowsByTermCode(termCode: String): List<SemesterEntity>

    @Query("DELETE FROM semesters WHERE termCode = :termCode")
    suspend fun deleteByTermCode(termCode: String)

    /**
     * 按主键精确删一枚行（#460：切换学期那一跳用它，不用 [deleteByTermCode]）。
     *
     * 差别只在同 termCode 有多枚行的那一刻：`DELETE ... WHERE termCode = ?` 把兄弟行的
     * 开学日与总周数一起吃掉，而那一枚行用户在屏上从没看过。
     */
    @Query("DELETE FROM semesters WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(semester: SemesterEntity): Long
}
