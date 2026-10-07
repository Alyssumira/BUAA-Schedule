package com.buaa.schedule.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.buaa.schedule.domain.model.ReminderSetting

/**
 * 提醒表的一行。
 *
 * ⚠️ **没有这一行才是常态**：教务导入 / 备份恢复 / 手动新建这三条写库链都不会为新课程建行
 * （逐条落点见 [ReminderSetting] 顶部 KDoc）。所以这里的两枚默认值不是"建行时的兜底"，
 * 而是那枚产品口径的**同一份来源**：一律引用 [ReminderSetting.DEFAULT_ENABLED] 与
 * [ReminderSetting.DEFAULT_ADVANCE_MINUTES]，全仓不许再写一遍字面量
 * （枚数册子由 `reminder/ReminderDefaultSingleSourceGuardTest` 钉住）。
 *
 * 为什么实体也带着默认值而不是让建行的人现填：Room 的 `@Insert(onConflict = REPLACE)` 走的是
 * 整行构造，凡是"用户第一次动这枚开关"才建行的路径（编辑保存、恢复）都必须给全两格；
 * 默认值与领域那两枚同源，才不会出现"库里存的默认与代码里读的默认是两个数"。
 */
@Entity(tableName = "reminders")
data class ReminderEntity(
    @PrimaryKey
    val courseId: Long,
    val enabled: Boolean = ReminderSetting.DEFAULT_ENABLED,
    val advanceMinutes: Int = ReminderSetting.DEFAULT_ADVANCE_MINUTES,
)
