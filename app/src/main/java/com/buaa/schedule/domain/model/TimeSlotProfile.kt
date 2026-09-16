package com.buaa.schedule.domain.model

/**
 * 北航默认 14 节时间表——全应用唯一副本。
 * 视图渲染、提醒调度、ICS 导入导出、未配置节次时的兜底都从这里取，
 * 修改作息只改这一处。
 */
object TimeSlotProfile {

    val DEFAULT: List<TimeSlot> = listOf(
        TimeSlot(number = 1, startTime = "08:00", endTime = "08:45"),
        TimeSlot(number = 2, startTime = "08:50", endTime = "09:35"),
        TimeSlot(number = 3, startTime = "09:50", endTime = "10:35"),
        TimeSlot(number = 4, startTime = "10:40", endTime = "11:25"),
        TimeSlot(number = 5, startTime = "11:30", endTime = "12:15"),
        TimeSlot(number = 6, startTime = "14:00", endTime = "14:45"),
        TimeSlot(number = 7, startTime = "14:50", endTime = "15:35"),
        TimeSlot(number = 8, startTime = "15:50", endTime = "16:35"),
        TimeSlot(number = 9, startTime = "16:40", endTime = "17:25"),
        TimeSlot(number = 10, startTime = "17:30", endTime = "18:15"),
        TimeSlot(number = 11, startTime = "19:00", endTime = "19:45"),
        TimeSlot(number = 12, startTime = "19:50", endTime = "20:35"),
        TimeSlot(number = 13, startTime = "20:40", endTime = "21:25"),
        TimeSlot(number = 14, startTime = "21:30", endTime = "22:15"),
    )
}
