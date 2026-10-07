package com.buaa.schedule.domain.schedule

import java.time.LocalDate

/**
 * 「这一周在不在学期内」的**唯一**判据（#263 由 T214 落在 `reminder/ClassProgressScheduler.kt`，
 * #306 + #307 由 T222 搬进 domain —— **搬家，不是复制**：全仓这一枚 `object TermWeekGate`，
 * 排铃两条链与冲突判定、三条导出链引用的是**同一个符号**，那件事由守卫第 ⑦ 层逐枚点名）。
 *
 * ## 它补的是哪半句话
 *
 * 排铃两条链此前只吃 `course.weeks`：周次升序 → `semesterStart.plusWeeks(week - 1)` → 排闹钟。
 * **`Semester.totalWeeks` 不是判据的参数**，而展示侧那一遍全判它
 * （[WeekCalculator.currentWeekOrNull] 里 `if (week <= 0 || week > totalWeeks) return null`。
 * T214 那一张卡自己数过：盘面 18 处调用、散在 9 枚文件里）。
 * 于是 `weeks` 里有超出总周数的值时（教务脏数据、学期行 `totalWeeks` 少报、手动课、旧版本遗留），
 * **周课表上那一周是空的，闹钟 / 上课铃 / 下课铃 / 自动勿扰却会在那一周照响照挂**（#263），
 * 而冲突判定还在按那几周报「存在时间冲突」、三条导出链还在把那些周次**写进离开本应用的数据**（#306 + #307）。
 *
 * ## 三条口径（搬家后一字未改，改的只是宿主在哪一层）
 *
 * 1. **与展示侧同源**：本体不写第二遍 `week <= totalWeeks`，而是把那一周的周一
 *    （[SemesterWeekDates.mondayOf]，也是展示侧翻周那一份）喂回 [WeekCalculator.currentWeekOrNull]，
 *    要求它映射回来的教学周**就是这一周**。比较式全仓只有 [WeekCalculator] 那一处，
 *    本文件一处都没有 —— 这正是卡面要防的「同一判据两份写法各错各的」。
 * 2. **零时钟**：`currentWeekOrNull` 的 `today` 形参带着一枚 `LocalDate.now()` 默认值，
 *    这里**必须**显式递那一周的周一（守卫 `reminder/TermWeekGateSingleSourceGuardTest` 第 ① 层
 *    按逐字节钉这三格实参）。读默认值那一手 = 判据在函数体内读钟，与纯函数承诺自相矛盾
 *    （台账 #265 那一族）。
 * 3. **判不出就不放行**：[semesterStart] 为 null（没有学期行 / 学期日期解析不出来）或
 *    [totalWeeks] 为 null（没有学期行）时 [isInTermWeek] 一律答"不在学期内"。
 *
 * ## 为什么冲突侧多一枚 [weeksInsideTerm]，而它不是第二把尺
 *
 * [isInTermWeek] 那一档"判不出 ⇒ 不放行"对**排铃**与**导出**都是对的口径（前者没有日历起点就没有
 * 可以吵醒用户的闹钟，后者写出去的数据收不回来，台账 T27 / #227 那族的取舍）。
 * 冲突判定不能照抄它：没有学期行时周课表走的是 `ui/home/WeekView.kt` 的
 * `showAllCoursesWithoutWeek = semester == null`（把课全画出来），那一周**确实在屏上**，
 * 于是"这一周两门课撞了"是真话；若这里也答"不放行"，就等于把没设学期的用户的冲突提醒整条关掉 ——
 * 排铃侧宁漏的代价是"少响一次铃"，冲突侧宁漏的代价是"用户看不见真冲突、不去修，
 * 最后两门课真的在同一间教室撞"。所以 [weeksInsideTerm] 的口径是**只裁确证在学期外的那几周**：
 * 两枚学期事实都在手上才裁，任一枚取不到就原样交回。
 * 它不写第二遍比较式，本体就是逐周过 [isInTermWeek] —— 尺还是那一把，只是**判不出时谁退到哪一档**
 * 由调用点的后果决定（这半句话写在 [weeksInsideTerm] 自己的 KDoc 与守卫第 ⑤ 层）。
 *
 * [UNBOUNDED_TOTAL_WEEKS] 是唯一一档"明说不判上界"的输入，只留给展示侧那三处还没收进来的
 * 调用点（`widget/WidgetCommon.kt` 两处、「实况重挂」`reminder/LiveClassResyncer.kt` 一处，
 * 它们改起来要动 T214 那张卡的白名单外的文件 ⇒ 台账里登记的跨卡那一格）。排铃两条链**不许**用它。
 */
object TermWeekGate {

    /**
     * "这一档调用方明说自己不判学期上界"的输入值。
     *
     * ⚠️ 它是**展示侧的历史包袱**，不是第二枚"默认值"：排铃两条链的 `totalWeeks` 是必填参数，
     * 拿不到学期行时传 null（判不出 ⇒ 不放行），传这一枚就是把自己退回病灶本身。
     * 第 ③ 层守卫数的是"带这一枚默认值的调用点里，`reminder/` 那两条链一处都不许有"
     * （`alarmChainsNeverTakeTheUnboundedDefault`；第 ④ 层再按目录把 `planNextClassWindow` 的
     * 调用点逐枚点名，两把尺互为备份）。
     */
    const val UNBOUNDED_TOTAL_WEEKS: Int = Int.MAX_VALUE

    /**
     * 纯 JVM 判据：教学周 [week] 落不落在「[semesterStart] 开学、共 [totalWeeks] 周」这一学期里。
     *
     * @param week 待判的教学周序号（1 基，来自 `course.weeks`，可能是脏数据里的 0 / 负数 / 越界值）
     * @param semesterStart 开学日期；null = 没有学期行或那一行解析不出日期
     * @param totalWeeks 学期总周数；null = 没有学期行可问
     * @return 越上界、越下界、判不出 ⇒ false（不放行）
     */
    fun isInTermWeek(week: Int, semesterStart: LocalDate?, totalWeeks: Int?): Boolean {
        val start = semesterStart ?: return false
        val bound = totalWeeks ?: return false
        // 越下界在这一格里由"没有这一周"交代：SemesterWeekDates.mondayOf 对 week < 1 交 null
        val weekMonday = SemesterWeekDates.mondayOf(start, week) ?: return false
        return WeekCalculator.currentWeekOrNull(start, bound, weekMonday) == week
    }

    /**
     * [weeks] 里那些**确证在学期内**的教学周，顺序原样保留（调用方给的是升序就交回升序）。
     *
     * 冲突判定（`domain/schedule/ConflictDetector.kt`）吃这一枚：没有学期行 / 总周数取不到时
     * **不裁**（判不出 ⇒ 维持今天的形状），两枚学期事实都在手上时逐周过 [isInTermWeek]。
     * 三条导出链里 ICS 与系统日历共用的那枚（`ScheduleOccurrences.build`）判得出学期 ⇒ 走
     * [isInTermWeek] 直接不放行；WakeUp 那一枚走的是本函数（它递给判据的上界就是自己写进
     * `maxWeek` 的那一个数，见 `ScheduleExporters` 里那段注释）。
     *
     * 这里**没有**第二份 `week in 1..totalWeeks`：判据仍然只在 [WeekCalculator] 那一处
     * （守卫第 ① 层按"抹完注释全仓恰一处比较式"钉着）。
     */
    fun weeksInsideTerm(weeks: List<Int>, semesterStart: LocalDate?, totalWeeks: Int?): List<Int> =
        if (semesterStart == null || totalWeeks == null) weeks else weeks.filter { isInTermWeek(it, semesterStart, totalWeeks) }
}
