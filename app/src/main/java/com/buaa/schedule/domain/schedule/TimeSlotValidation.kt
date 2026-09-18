package com.buaa.schedule.domain.schedule

import com.buaa.schedule.domain.model.TimeSlot
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/**
 * 单条节次时间是否合法：必须是**真实存在的** 24 小时制时刻，且结束晚于开始。
 *
 * 归属在领域层：备份恢复（[com.buaa.schedule.data.repository.ScheduleRepository.restoreBackup]）
 * 曾反向 import `ui.settings` 里的这份校验——数据层要不要丢一条节次，
 * 不该取决于 Compose 文件的内部函数（R7 P3-20）。
 * 节次时间写坏会让整个课表的时间轴错位，抽成顶层函数便于单测。
 *
 * 校验必须与消费侧 [com.buaa.schedule.domain.model.toStartEndTimes] 的 `LocalTime.parse`
 * 同口径（P1-1）。此前这里只看 `^\d{2}:\d{2}$` 的字面形状，于是 `99:99` 能存进库，
 * 而解析侧把它丢掉 —— 用户看到的是「保存成功，但时间轴少一节」。
 * 现在直接用解析结果做判据：**存进去必然读得出来**。
 */
fun isValidTimeSlot(slot: TimeSlot): Boolean {
    val start = parseTimeOrNull(slot.startTime) ?: return false
    val end = parseTimeOrNull(slot.endTime) ?: return false
    return start < end
}

/**
 * `HH:mm` 的严格解析器。
 *
 * 不用 `LocalTime.parse` 的默认行为：它是 SMART 档，会把 `"24:00"` 读成 `00:00`
 * —— 那正是要拦掉的越界值之一。STRICT + `H`/`m` 字段又会让 `"8:00"` 因为缺前导零
 * 而失败，正好与既有的格式约定一致（`HH:mm` 必须是两位）。
 */
private val TIME_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm").withResolverStyle(ResolverStyle.STRICT)

private fun parseTimeOrNull(text: String): LocalTime? =
    runCatching { LocalTime.parse(text, TIME_FORMAT) }.getOrNull()
