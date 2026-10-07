package com.buaa.schedule.domain.schedule

import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.Semester
import com.buaa.schedule.domain.model.TimeSlot
import com.buaa.schedule.domain.model.TimeSlotProfile
import com.buaa.schedule.domain.model.joinMeta
import com.buaa.schedule.domain.model.periodLabelOf
import com.buaa.schedule.domain.model.startLocalDate
import com.buaa.schedule.domain.model.weekdayLabel
import java.time.LocalDate

/**
 * 数据导出（纯函数，可单测）：
 * - [toWakeUpJson]：WakeUp 课程表兼容 JSON——用户可把它导入 WakeUp 继续用，
 *   降低迁移成本（schema 参照社区通用格式：name/position/day/startNode/step/startWeek/endWeek/type/color）。
 * - [toWeeklyText]：纯文本周课表，便于粘贴到聊天工具。
 */
object ScheduleExporters {

    /** 导出 WakeUp 兼容 JSON。注意 WakeUp 的课程模型是 startNode+step 连续节次，
     *  非连续节次（如 1-2+9-10）按起始节连续段截断导出。
     *
     *  导出的门数**可以少于**入参：一条节次都没有（T27）、一条周次都没有（T203/#237）的课整条不写，
     *  调用方要按"写出去了几门"说话，不能拿 courses.size 报数；两档各几门见 [WakeUpDropTally.tally]。
     *
     *  写出去的 `startWeek`/`endWeek`/`type` **只数学期内的那些周次**（T222/#307）：判据是
     *  [TermWeekGate.weeksInsideTerm]，上界用的就是同一份 JSON 里那枚 `maxWeek`，
     *  所以对方 App 不会再拿到一节课在学期结束之后。整门课都在学期外那一档今天**仍然出场**，
     *  把它判成第三档"没进导出件"要连着改报数那两枚文件 ⇒ 回执「需要跨卡配合」。
     *
     *  不带 credit：WakeUp 的 courses schema 里没有学分这一列，多写未知键能不能被容忍
     *  取决于对方解析器（不受我们控制），而学分只有本应用的统计页用得上。 */
    fun toWakeUpJson(
        courses: List<Course>,
        semester: Semester?,
        timeSlots: List<TimeSlot>,
    ): String {
        val slots = if (timeSlots.isNotEmpty()) timeSlots else TimeSlotProfile.DEFAULT
        // WakeUp 按「startDate=第 1 周周一」推周次；无学期时用今天兜底必须先归一到周一，
        // 否则对方 App 里整表课程错位最多 6 天
        val startMonday = WeekCalculator.mondayOf(
            runCatching { LocalDate.parse(semester?.startDate ?: LocalDate.now().toString()) }
                .getOrDefault(LocalDate.now())
        )
        val start = startMonday.toString()
        // 这一枚数同时写进 `maxWeek`（见下面那行）⇒ 它就是这份导出件自己声明的学期上界，
        // 周次裁剪判的必须是它，不能另找一把尺（T222，#307）
        val totalWeeks = semester?.totalWeeks ?: CourseConstraints.MAX_TOTAL_WEEKS

        fun compact(time: String): String = time.replace(":", "") + "00"

        val nodeArray = slots.joinToString(",") { slot ->
            """{"node":${slot.number},"name":"第${slot.number}节","s":"${compact(slot.startTime)}","e":"${compact(slot.endTime)}"}"""
        }
        // 没有节次的课**整条不进导出件**（T27）：这里此前取 course.startPeriod，
        // 而它在空表时兜底成 1，紧跟的那句 while 又立刻退出 —— 于是产出一条
        // `startNode:1,step:0`，即"周一第 1 节上一门 0 节课"。文案链上空一节次
        // 只是少一句「第1-2节」，这条链上它是**离开本应用的数据**：WakeUp 照单收下，
        // 用户在对方 App 里看到一节根本不存在的课，且我们无从收回。
        // 也不能换个猜测值顶上（第 0 节、-1 都是对方 schema 里的合法节点号）：
        // 唯一诚实的做法是这一项不出场，与 T26 的 joinMeta 同口径。空周次那一档（#237）也同此判据：
        // 调用方（ScheduleViewModel.exportWakeUpTo）负责把"少了几门、各因哪一档"说给用户听。
        val courseArray = courses.mapNotNull { course ->
            val startNode = course.firstPeriodOrNull ?: return@mapNotNull null
            var step = 0
            while (course.periods.contains(startNode + step)) step++
            val sortedWeeks = course.weeks.sorted()
            // 学期外的那些周次不进 startWeek/endWeek/type（T222，#307）：改前这里吃的是**整条**
            // `course.weeks.sorted()`，而下面 `"maxWeek":$totalWeeks` 写的是学期总周数 ——
            // 同一份 JSON 里 `endWeek > maxWeek` 就是对方 App 多出来的那几天
            // （WakeUp 按 `startDate` + `startWeek..endWeek` + `type` 推日期，学期结束后那一周照样排课），
            // 而那是离开本应用的数据，我们无从收回。
            // 判据走 [TermWeekGate.weeksInsideTerm]（与排铃两条链、冲突判定、ICS/系统日历同一枚符号），
            // 递进去的上界就是这份导出件**自己声明**的那一枚 `maxWeek` ⇒ 没有学期行时也在判：
            // 那一档判不出的是"这学期到底几周"，不是"这一周超出我自己写的范围"。
            // ⚠️ 整门课都在学期外时（裁完交回空表）这里**照旧让它出场**，不另加一枚 `return@mapNotNull null`：
            // 那等于多出第三档"没进导出件"，要同时改 `WakeUpDropTally` 的档位与 `wakeUpExportMessage`
            // 那句报数 —— 两枚文件都不在本卡白名单里（那句的"门 / 段"措辞是 #308 的地盘）
            // ⇒ 逐字登记进回执「需要跨卡配合」，不在这里顺手改。
            val weeks = TermWeekGate.weeksInsideTerm(sortedWeeks, startMonday, totalWeeks).ifEmpty { sortedWeeks }
            val startWeek = weeks.firstOrNull() ?: return@mapNotNull null // 改前是 `?: 1`：空周次造出"第 1-1 周"（#237）
            val endWeek = weeks.last() // 上一行已判非空 ⇒ 改前那枚 `?: startWeek` 是不可达支（T193 那一族）
            val type = when {
                weeks.all { it % 2 == 1 } -> 1 // 单周；`&& weeks.isNotEmpty()` 同上已是死格，一并拆掉
                weeks.all { it % 2 == 0 } -> 2 // 双周
                else -> 0                       // 每周
            }
            """{"name":${jsonStr(course.name)},"teacher":${jsonStr(course.teacher ?: "")},""" +
                """"position":${jsonStr(course.location ?: "")},"day":${course.dayOfWeek},""" +
                """"startNode":$startNode,"step":$step,"startWeek":$startWeek,"endWeek":$endWeek,""" +
                """"type":$type,"color":${course.colorIndex % 20}}"""
        }.joinToString(",")
        return """{"name":${jsonStr(semester?.termName ?: "我的课表")},"startDate":"$start",""" +
            """"tableInfo":{"name":${jsonStr(semester?.termName ?: "我的课表")},"startDate":"$start",""" +
            """"maxWeek":$totalWeeks,"nodesPerDay":${slots.size},"time":"[$nodeArray]"},""" +
            """"courses":[$courseArray]}"""
    }

    /**
     * 纯文本周课表（按天分组，空课日略过）。
     *
     * 不打学分：这段文本是给人看/往群里粘的，不是 `TextScheduleParser` 认的那个 6 列格式
     * （不会被解析回来），每行多挂一个 "3.5学分" 只是把课表撑长 —— 学分归统计页说。
     *
     * 一行里的地点与节次走 [joinMeta]：教务确实给过没有教室、也没有节次的行，
     * 那时缺的那一段连同 " · " 一起不出场，而不是留下 "高等数学 · " 这种像被截断的尾巴。
     */
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
                // 与预告那条链同一条口径：时刻取自真实的第一节，startPeriod 的空表兜底
                // 会在导出的文本里凭空写一个 08:00
                val startTime = course.periods.minOrNull()?.let { first ->
                    slots.firstOrNull { it.number == first }?.startTime
                } ?: ""
                val timePrefix = if (startTime.isNotBlank()) "$startTime " else ""
                timePrefix + joinMeta(course.displayName, course.location, periodLabelOf(course.periods, slots))
            }
            "${weekdayLabel(day) ?: day}\n$lines"
        }
        return if (body.isEmpty()) {
            "$header\n${if (targetWeek != null) "本周" else "本学期"}没有课"
        } else "$header\n\n${body.joinToString("\n\n")}"
    }

    private fun jsonStr(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
