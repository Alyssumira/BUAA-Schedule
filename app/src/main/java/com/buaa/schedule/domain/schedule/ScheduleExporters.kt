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
 * 数据导出（纯判据，可单测）：本仓口径 —— 零 android / androidx import（纯度那把尺按**两种形状**判：
 * `import` 行与 `@androidx.…` 全限定写法都算出场，钉在册守卫的 ⑤ 那格）、不读时钟、不读时区、不读库 ——
 * 「今天」由调用点当参数递进来（两枚公共函数各收一枚 `today`，同一次导出只读一次钟）。
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
     *  取决于对方解析器（不受我们控制），而学分只有本应用的统计页用得上。
     *
     *  ## 本卡（T279①）动的两格，与改前的形状（守卫按注释/代码各数一遍）
     *
     *  1. **字符串字段全部走 [WakeUpJsonText.literal]**（丙档：sanitize 再 escape）。改前那两行私有
     *     `jsonStr` 只做 `replace("\\", "\\\\")` 与双引号那一手，裸换行/制表符原样出场 ⇒
     *     RFC 8259 §7 判死整份文件，对方 App 一条课都收不到而屏幕上念"已导出"。
     *     这一手与 `data/export/IcsExporter.kt` 的 `escape`（先归一行结束符、再逐个转义）同形。
     *  2. **课程名走 [com.buaa.schedule.domain.model.Course.displayName]**，与 ICS / 系统日历那两条链
     *     的 `title` 同一枚出处（改前这里读 `course.name` ⇒ 用户设的别名压根进不了对方 App，
     *     屏上、日历里都是别名而导出件是教务原名，三链同源字段两种取法）。
     *
     *  ⚠️ 残账改口（T279①b：本卡端到端那一格量出来的，审计 N2 的范围窄了）：`tableInfo.time`
     *     是一枚**装着 JSON 的字符串**（`"time":"[{ … }]"`），改前把 `nodeArray` 原样押进去 ⇒
     *     那串里每一枚 `"` 都没转义，整份文件在 `"time":"[{` 之后的第一枚引号处就把字符串关掉了 ——
     *     也就是说**即使用户文本里一枚控制字符都没有，这份 .json 也从来不是合法 JSON**
     *     （RFC 8259 §7 的另一手：串内引号必须转义；审计只点了裸换行那一手）。
     *     现在这一枚也走 [WakeUpJsonText.literal]：sanitize 对机器生成的 ASCII 是一次空操作，
     *     escape 把串内的引号折成 `\"` ⇒ 交回的文件合法，而 `time` 的**内容**一字不变
     *     （对方解析出来的仍是那串节点表）。`compact` 那一手只是把冒号摘掉，不是转义落点。
     *
     * @param today 「今天」的唯一出处 = 调用点（`ScheduleViewModel.exportWakeUpTo` 递的是
     *   `uiState.today`，即 dayTicker 的滴答值）：内核读钟就不是纯判据 —— 同一次导出的两次点击
     *   可能给出不同结论，而没有学期行时「第 1 周周一」会跟着屏幕外的时刻漂（T351 裁 甲）。 */
    fun toWakeUpJson(
        courses: List<Course>,
        semester: Semester?,
        timeSlots: List<TimeSlot>,
        today: LocalDate,
    ): String {
        val slots = if (timeSlots.isNotEmpty()) timeSlots else TimeSlotProfile.DEFAULT
        // WakeUp 按「startDate=第 1 周周一」推周次；没有学期行、或那枚 startDate 畸形时，兜底用的是
        // **调用点递进来的那枚 today**（内核一枚钟都不读），且必须先归一到周一，
        // 否则对方 App 里整表课程错位最多 6 天。
        // 两手吃同一枚 today：改前这里读两次 LocalDate.now()，正好跨过零点时 parse 那一手与兜底那一手
        // 会拿到两枚不同的日期（同一次导出两只钟 = #338 那一族的形状）。
        val startMonday = WeekCalculator.mondayOf(
            semester?.startDate?.let { raw -> runCatching { LocalDate.parse(raw) }.getOrNull() } ?: today
        )
        val start = startMonday.toString()
        // 这一枚数同时写进 `maxWeek`（见下面那行）⇒ 它就是这份导出件自己声明的学期上界，
        // 周次裁剪判的必须是它，不能另找一把尺（T222，#307）。
        //
        // ⚠️ #602 与 #397 的交点改的就是**这一枚数的出处**（判据只有两等，第三等一概不许有）：
        // ① 学期行**自己**说得上话（那一枚周数 > 0）⇒ 用它，裁剪也判它 —— #307 那半条一字未动；
        // ② 说不上话（没有学期行，或那一行是 0 / 负数：那压根不是一枚周数）⇒ 用**这份文件实际
        //    覆盖到的最晚一周**，它是从课程自己交出的周次里读出来的可观察事实，不是任何一枚常数。
        // 改前第二等兜的是 `semester?.totalWeeks ?: CourseConstraints.MAX_TOTAL_WEEKS`（= 30）：
        // 屏上没有任何一枚学期说过"这学期 30 周"，导出件却自己声明了它；而且这一枚**带牙齿** ——
        // 裁剪判的就是它，于是第 31 周以后的真课次被裁进对方 App 看不见的地方（本卡 ① 步现量：
        // 课次 1、2、35 ⇒ 交回 startWeek 1 / endWeek 2）。脏学期行那两等更直接：0 与 -5 被**原样**
        // 写进 maxWeek，配全量 endWeek ⇒ 正撞下面那句 `endWeek > maxWeek`（现量 endWeek 3 / 12 配 maxWeek 0）。
        // 两枚账（写进 maxWeek 的、递进裁剪的）今天仍是**同一个局部量** ⇒ 不许长成两把尺。
        // 第二等里"界内一枚都不裁"是可证的：没有任何一枚周次能超过全体在写课程的最大周号；
        // 而**下界那一判照样有牙齿**（第 0 周与负周次不是教学周，这一判不需要学期事实 ——
        // SemesterWeekDates.mondayOf 对 week < 1 交回 null ⇒ isInTermWeek 答"不在学期内"）。
        // 判不出时的安全侧在这一条链上是**原样出场**而不是不出场（[TermWeekGate.weeksInsideTerm] 的成文
        // 口径：两枚学期事实都在手上才裁，任一枚取不到就原样交回；写出去的数据收不回来，
        // 而"裁掉它"要说得出确证在学期外，编出来的上界说不出这一句）。
        // 脏值答"没有上界"与 [SemesterWeekCount.readable] 同一口径，但这里**不接那枚具名读数**：
        // 它还把上界夹到 30，会改掉那本册子差异表里「学期行 999 ⇒ 判据尺不夹上界」那一格
        // （#395 / #404 那一族的既有口径）⇒ 逐字登记进回执「需要跨卡配合」，不在这里顺手并尺。
        val termCeiling = semester?.totalWeeks?.takeIf { it > 0 }
        // 第二等顶上前面的位置用这一枚：真正会写进 courses 的那些课（两档 drop 的册子已经数清了）
        // 覆盖到的最晚一周。拿全体入参的周号来算会把"没节次所以整条不写"那些课也报进上界，
        // 那又是一枚文件自己没展示过的数。
        val coveredMaxWeek = courses.filter { WakeUpDropTally.wakeUpDrop(it) == null }
            .flatMap { it.weeks }
            .maxOrNull() ?: 0
        val totalWeeks = termCeiling ?: coveredMaxWeek

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
            // 递进去的上界就是这份导出件**自己声明**的那一枚 `maxWeek`（上面那两枚账是同一个局部量，
            // 不是第二把尺）：学期行说得上话 ⇒ 判的是真学期上界；说不上话 ⇒ 判的是这份文件自己覆盖到
            // 的最晚一周，于是那一等里"界内的课次一枚都不被裁"是构造出来的事实。
            // （#602 改前这一位在没有学期行时顶的是那枚常数 30 ⇒ 第 31 周以后的**真课次**被一枚
            // 屏上从没出现过的数裁走，而它已经离开本应用了。）
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
            """{"name":${WakeUpJsonText.literal(course.displayName)},"teacher":${WakeUpJsonText.literal(course.teacher ?: "")},""" +
                """"position":${WakeUpJsonText.literal(course.location ?: "")},"day":${course.dayOfWeek},""" +
                """"startNode":$startNode,"step":$step,"startWeek":$startWeek,"endWeek":$endWeek,""" +
                """"type":$type,"color":${course.colorIndex % 20}}"""
        }.joinToString(",")
        // `time` 是一枚**装着 JSON 的字符串**：串里那些 `"` 一枚都不许裸出场，否则整份 .json 在
        // 这一格就把字符串关掉（T279①b）。sanitize 对机器生成的 ASCII 是空操作，走的就是 escape 那一手。
        val timeField = WakeUpJsonText.literal("[$nodeArray]")
        return """{"name":${WakeUpJsonText.literal(semester?.termName ?: "我的课表")},"startDate":"$start",""" +
            """"tableInfo":{"name":${WakeUpJsonText.literal(semester?.termName ?: "我的课表")},"startDate":"$start",""" +
            """"maxWeek":$totalWeeks,"nodesPerDay":${slots.size},"time":$timeField},""" +
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
     *
     * @param today 「今天」的唯一出处 = 调用点：设置页那一枚递的是 `uiState.today`
     *（dayTicker 的滴答值，跨午夜由它推进），与同一屏顶栏「第 N 周」吃的是同一次读数。
     */
    fun toWeeklyText(
        courses: List<Course>,
        semester: Semester?,
        timeSlots: List<TimeSlot>,
        week: Int?,
        today: LocalDate,
    ): String {
        val slots = if (timeSlots.isNotEmpty()) timeSlots else TimeSlotProfile.DEFAULT
        // 「哪一周是本周」按调用点递进来的 today 算，内核不读钟（与 ui 层 combine 里那一枚
        // currentWeekOrNull 同一形状）：同一屏上的顶栏周次与这份导出件必须来自同一次读数，
        // 否则跨过零点时标题念「第 N 周」而正文裁的是上一周。
        val targetWeek: Int? = week ?: semester?.startLocalDate?.let { start ->
            WeekCalculator.currentWeekOrNull(start, semester.totalWeeks, today)
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
}
