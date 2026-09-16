package com.buaa.schedule.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 系统日历同步映射：本应用创建的每个日历事件一条记录。
 * 同步时按 occurrenceId 匹配期望日程，实现增量更新与移除。
 */
@Entity(tableName = "calendar_sync")
data class CalendarSyncEntity(
    /** 稳定课次 ID（ScheduleOccurrences.stableIdFor） */
    @PrimaryKey
    val occurrenceId: String,
    /** 课程身份键（同门课全部课次共用，便于按课程排查） */
    val courseStableId: String,
    /** 上课日期 yyyy-MM-dd */
    val occurrenceDate: String,
    val calendarId: Long,
    val calendarEventId: Long,
    /** 上次同步时的内容摘要，用于判断是否需要更新 */
    val contentHash: String,
    val syncedAt: Long,
)
