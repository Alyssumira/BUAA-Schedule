package com.buaa.schedule.data.calendar

import android.provider.CalendarContract

/**
 * Calendars 表查询契约（从 `CalendarSyncManager` 里抽出来，为了能被测试钉住）。
 *
 * ACCOUNT_NAME 与 ACCOUNT_TYPE 必须成对出现：API 14+ 的 CalendarProvider 缺任何
 * 一个都直接抛 IllegalArgumentException，而查询外面套着 runCatching，异常被吞掉后
 * 对外只表现为"检索不到任何日历"（曾经真实发生过）。
 *
 * selection 里的 VISIBLE=1 也不能少：隐藏日历写不进去，列给用户选等于报错。
 * 但部分 ROM 的 provider 对带 `AND` 的 selection 返回空集，所以严格查询为空时必须
 * 退化为无条件查询 + 代码侧按访问级别筛（[fallbackWritable]）—— 两段式逻辑同样
 * 在这里，而不是散在 ContentResolver 调用旁边。
 */
internal object CalendarQuery {

    /** 投影：[_ID, DISPLAY_NAME, ACCOUNT_NAME, ACCOUNT_TYPE, ACCESS_LEVEL] */
    val projection: Array<String> = arrayOf(
        CalendarContract.Calendars._ID,
        CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
        CalendarContract.Calendars.ACCOUNT_NAME,
        CalendarContract.Calendars.ACCOUNT_TYPE,
        CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
    )

    /** 可写 + 可见日历的严格 selection */
    val writableSelection: String =
        "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= " +
            "${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR} AND " +
            "${CalendarContract.Calendars.VISIBLE} = 1"

    val sortOrder: String = "${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} ASC"

    /** 代码侧兜底过滤：至少要能写（CONTRIBUTOR 及以上） */
    fun fallbackWritable(
        calendars: List<CalendarSyncManager.CalendarInfo>,
    ): List<CalendarSyncManager.CalendarInfo> = calendars.filter {
        it.accessLevel >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR
    }

    /** 严格 selection 查到东西就用它，查不到（ROM 行为）才退回全量 + 代码侧筛 */
    fun resolveWritable(
        strict: List<CalendarSyncManager.CalendarInfo>,
        everything: () -> List<CalendarSyncManager.CalendarInfo>,
    ): List<CalendarSyncManager.CalendarInfo> =
        strict.ifEmpty { fallbackWritable(everything()) }
}
