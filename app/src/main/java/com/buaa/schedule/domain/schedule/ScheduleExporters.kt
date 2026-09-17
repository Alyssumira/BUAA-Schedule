package com.buaa.schedule.domain.schedule

import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.Semester
import com.buaa.schedule.domain.model.TimeSlot
import com.buaa.schedule.domain.model.TimeSlotProfile
import com.buaa.schedule.domain.model.periodLabel
import com.buaa.schedule.domain.model.startLocalDate
import java.time.LocalDate

/**
 * 数据导出（纯函数，可单测）：
 * - [toWakeUpJson]：WakeUp 课程表兼容 JSON——用户可把它导入 WakeUp 继续用，
 *   降低迁移成本（schema 参照社区通用格式：name/position/day/startNode/step/startWeek/endWeek/type/color）。
 * - [toWeeklyText]：纯文本周课表，便于粘贴到聊天工具。
 */
object ScheduleExporters {

    /** 导出 WakeUp 兼容 JSON。注意 WakeUp 的课程模型是 startNode+step 连续节次，
     *  非连续节次（如 1-2+9-10）按起始节连续段截断导出。 */
    fun toWakeUpJson(
        courses: List<Course>,
        semester: Semester?,
        timeSlots: List<TimeSlot>,
    ): String {
        val slots = if (timeSlots.isNotEmpty()) timeSlots else TimeSlotProfile.DEFAULT
        // WakeUp 按「startDate=第 1 周周一」推周次；无学期时用今天兜底必须先归一到周一，
        // 否则对方 App 里整表课程错位最多 6 天
        val start = WeekCalculator.mondayOf(
            runCatching { LocalDate.parse(semester?.startDate ?: LocalDate.now().toString()) }
                .getOrDefault(LocalDate.now())
        ).toString()
        val totalWeeks = semester?.totalWeeks ?: CourseConstraints.MAX_TOTAL_WEEKS

        fun compact(time: String): String = time.replace(":", "") + "00"

        val nodeArray = slots.joinToString(",") { slot ->
            """{"node":${slot.number},"name":"第${slot.number}节","s":"${compact(slot.startTime)}","e":"${compact(slot.endTime)}"}"""
        }
        val courseArray = courses.joinToString(",") { course ->
            val startNode = course.startPeriod
            var step = 0
            while (course.periods.contains(startNode + step)) step++
            val weeks = course.weeks.sorted()
            val startWeek = weeks.firstOrNull() ?: 1
            val endWeek = weeks.lastOrNull() ?: startWeek
            val type = when {
                weeks.all { it % 2 == 1 } && weeks.isNotEmpty() -> 1 // 单周
                weeks.all { it % 2 == 0 } && weeks.isNotEmpty() -> 2 // 双周
                else -> 0                                            // 每周
            }
            """{"name":${jsonStr(course.name)},"teacher":${jsonStr(course.teacher ?: "")},""" +
                """"position":${jsonStr(course.location ?: "")},"day":${course.dayOfWeek},""" +
                """"startNode":$startNode,"step":$step,"startWeek":$startWeek,"endWeek":$endWeek,""" +
                """"type":$type,"color":${course.colorIndex % 20}}"""
        }
        return """{"name":${jsonStr(semester?.termName ?: "我的课表")},"startDate":"$start",""" +
            """"tableInfo":{"name":${jsonStr(semester?.termName ?: "我的课表")},"startDate":"$start",""" +
            """"maxWeek":$totalWeeks,"nodesPerDay":${slots.size},"time":"[$nodeArray]"},""" +
            """"courses":[$courseArray]}"""
    }

    /** 纯文本周课表（按天分组，空课日略过） */
    fun toWeeklyText(
        courses: List<Course>,
        semester: Semester?,
        timeSlots: List<TimeSlot>,
        week: Int?,
    ): String {
        val slots = if (timeSlots.isNotEmpty()) timeSlots else TimeSlotProfile.DEFAULT
        val targetWeek: Int? = week ?: semester?.startLocalDate?.let { start ->
            WeekCalculator.currentWeekOrNull(start, semester.totalWeeks, LocalDate.now())
        }
        val dayNames = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
        val header = buildString {
            append(semester?.termName?.let { "$it · " } ?: "")
            // 假期/学期外取不到当前周时列出的是全学期所有周次，标题必须如实说明，
            // 不能顶着「课表」二字让人误以为是本周
            append(
                when {
                    targetWeek != null -> "第 $targetWeek 周"
                    semester != null -> "假期中 · 全学期课程"
                    else -> "课表 · 全学期课程"
                }
            )
        }
        val body = (1..7).mapNotNull { day ->
            val dayCourses = courses
                .filter { it.dayOfWeek == day && (targetWeek == null || it.weeks.contains(targetWeek)) }
                .sortedBy { it.startPeriod }
            if (dayCourses.isEmpty()) return@mapNotNull null
            val lines = dayCourses.joinToString("\n") { course ->
                val startTime = slots.firstOrNull { it.number == course.startPeriod }?.startTime ?: ""
                buildString {
                    if (startTime.isNotBlank()) append("$startTime ")
                    append(course.name)
                    course.location?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                    append(" · ").append(periodLabel(course.periods))
                }
            }
            "${dayNames[day - 1]}\n$lines"
        }
        return if (body.isEmpty()) {
            "$header\n${if (targetWeek != null) "本周" else "本学期"}没有课"
        } else "$header\n\n${body.joinToString("\n\n")}"
    }

    private fun jsonStr(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
