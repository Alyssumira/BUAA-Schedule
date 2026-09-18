package com.buaa.schedule.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * @param indices 两张索引对应的都是高频过滤列：
 * - `semesterCode`：导入/恢复/学期切换都按学期整体读写（`deleteBySemester` /
 *   `getBySemester`），没有索引时每次导入都要全表扫一遍；
 * - `sourceGroupKey`：`updateCourseGroupAppearance` 按教学班批量改外观，
 *   也是全表匹配。
 * 两张索引由 MIGRATION_6_7 在已有库上补建。
 */
@Entity(
    tableName = "courses",
    indices = [
        Index(value = ["semesterCode"]),
        Index(value = ["sourceGroupKey"]),
    ],
)
data class CourseEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val name: String,
    val alias: String? = null,
    val teacher: String?,
    val location: String?,
    val campus: String?,
    val dayOfWeek: Int,
    /** 节次列表（支持非连续），由 Converters 存为逗号分隔文本 */
    val periods: List<Int>,
    val weeks: List<Int>,
    val colorIndex: Int,
    val customColorArgb: Long? = null,
    val remark: String?,
    val sourceGroupKey: String?,
    val semesterCode: String?,
    /**
     * `defaultValue` 必须写：老库上这一列是 `ALTER TABLE … DEFAULT 0` 加出来的，
     * 而 Room 2.6+ 把默认值纳入 schema 比对。实体里不声明 → 新装的表没有默认值，
     * 与迁移后的表不是同一份 schema（R5 F-03）。声明后编译期 identityHash 变化，
     * 故 v7 → v8 重建 `courses`。
     */
    @ColumnInfo(name = "isManualOverride", defaultValue = "0")
    val isManualOverride: Boolean = false,
    /**
     * 学分（教务导入才有；手动/文本/ICS 课程为 null）。
     *
     * 可空且**故意不给 defaultValue**：v8 及更早的库里这些课是"没采到学分"，
     * 补成 0 会被统计页当成"教务明说这门课 0 学分"（见 [com.buaa.schedule.domain.model.Course.credit]）。
     * 由 MIGRATION_8_9 以 `ADD COLUMN credit REAL` 追加，列序与实体声明序一致。
     */
    val credit: Double? = null,
)
