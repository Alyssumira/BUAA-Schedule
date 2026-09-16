package com.buaa.schedule.domain.model

import java.time.LocalTime

data class TimeSlot(
    val id: Long = 0L,
    val number: Int,
    val startTime: String, // HH:mm
    val endTime: String,   // HH:mm
)

/**
 * 节次表 → 「节次号 → (上课, 下课)」，时间非法的行直接丢掉。
 *
 * 课次展开、今日计划、课堂窗口三处都要按节次取墙钟时间，
 * 此前各自抄了一遍同样的 `runCatching { LocalTime.parse(...) }`。
 */
fun List<TimeSlot>.toStartEndTimes(): Map<Int, Pair<LocalTime, LocalTime>> =
    mapNotNull { slot ->
        runCatching {
            slot.number to (LocalTime.parse(slot.startTime) to LocalTime.parse(slot.endTime))
        }.getOrNull()
    }.toMap()
