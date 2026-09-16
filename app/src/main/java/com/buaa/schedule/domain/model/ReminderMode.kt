package com.buaa.schedule.domain.model

/**
 * 提醒模式：
 * - [APP]：应用内 AlarmManager 提醒（默认，行为/隐私可控）
 * - [CALENDAR]：由系统日历应用的日程提醒负责（需先完成日历同步），
 *   应用内不再注册闹钟，避免双重通知
 */
object ReminderMode {
    const val PREF_KEY = "reminder_mode"
    const val APP = "app"
    const val CALENDAR = "calendar"
}
