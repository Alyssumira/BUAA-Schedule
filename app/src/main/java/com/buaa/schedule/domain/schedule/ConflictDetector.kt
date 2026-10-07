package com.buaa.schedule.domain.schedule

import com.buaa.schedule.domain.model.Course
import java.time.LocalDate

object ConflictDetector {

    /**
     * @Immutable：字段都是 val 且 List 构造后不再变（由 findConflicts 整体重建）。
     * 与 [com.buaa.schedule.domain.model.Course] 一起让主页的 state 成为稳定类型。
     */
    @androidx.compose.runtime.Immutable
    data class Conflict(
        val first: Course,
        val second: Course,
        val weeks: List<Int>,
    )

    /**
     * 检测课程列表中同一时间、同一周次的冲突。
     * 忽略 id 相同（自己与自己）的课程。
     *
     * ## 两枚学期参数不是可选的（T222，#306）
     *
     * 改前这里只有 `a.weeks.intersect(b.weeks.toSet())`，**签名里没有学期** ⇒ 只在 1-8 周撞过的两门课，
     * 在学期只有 19 周而 `weeks` 里混进 20 的用户那儿，第 20 周照样报「存在时间冲突」；
     * 同一屏于是同时摆出「共 19 周」+「整天没课：周三」（`domain/stats/SemesterStats.kt` 的 `dayLoads`
     * **是裁周次的**）+「有 1 组时间冲突」+「周三 · 第 20-21 周」三句互相打脸。
     * 更重的一格在写库：冲突向导那句「只改这些周」把这串假周次原样递进
     * `ConflictShiftWeekScope.weeksToShift` 再进 `updateCourse`（落点逐行见回执）。
     *
     * 裁周次走 [TermWeekGate.weeksInsideTerm] —— **与排铃两条链、三条导出链同一枚符号**，
     * 本文件不写第二遍 `week in 1..totalWeeks`（全仓那一枚比较式只在 [WeekCalculator] 里，
     * 由 `reminder/TermWeekGateSingleSourceGuardTest` 第 ① 层数出现次数钉着）。
     *
     * ## 判不出学期时**不裁**，这与排铃侧故意不对称
     *
     * [semesterStart] 或 [totalWeeks] 取不到 ⇒ 两枚周次事实都没有根据 ⇒ 维持改前的形状
     * （照旧全判）。理由与 `#263` 第 3 条口径对照着读：
     * - 排铃侧「判不出就不放行」的代价是"少响一次铃"，而它判不出时连"哪一天几点"都算不出来；
     * - 冲突判定的后果是**警示 + 建议挪课**：没有学期行时周课表走的是
     *   `ui/home/WeekView.kt` 的 `showAllCoursesWithoutWeek = semester == null`（把课全画出来），
     *   那一周**确实在屏上**，"这一周两门课撞了"是真话；这里若也答"不放行"，
     *   等于把没设学期的用户的冲突提醒整条关掉 —— 他看不见冲突、不会去修，
     *   最后两门课真的在同一间教室撞上。宁漏不多在这一侧漏的是**真话**，不划算。
     *
     * @param semesterStart 开学日期（`Semester.startLocalDate`，null = 没有学期行或那一行解析不出日期）
     * @param totalWeeks 学期总周数（`Semester.totalWeeks`，null = 没有学期行可问）
     *   **没有默认值**：这一枚判据的"今天不知道学期"必须由调用点明说，不许退到兜底周数
     *   （台账 #236 那族 `?: 20` 造出「这学期 20 周」的账就是这么来的）
     */
    fun findConflicts(
        courses: List<Course>,
        semesterStart: LocalDate?,
        totalWeeks: Int?,
    ): List<Conflict> {
        val result = mutableListOf<Conflict>()
        val sorted = courses.sortedWith(
            compareBy<Course> { it.dayOfWeek }
                .thenBy { it.startPeriod }
                .thenBy { it.name }
        )
        for (i in sorted.indices) {
            for (j in i + 1 until sorted.size) {
                val a = sorted[i]
                val b = sorted[j]
                if (a.id == b.id && a.id != 0L) continue
                // 已按星期升序排好：b 一旦跨到下一天，这个 i 后面再不会有同日的了
                if (b.dayOfWeek > a.dayOfWeek) break
                if (!periodsOverlap(a.periods, b.periods)) continue
                // 交出来的周次先过一遍学期尺：判得出的越界周次不进 weeks
                // ⇒ 冲突卡不再挂到屏上画不到的那一周、向导那句「只改这些周」也不再按假周次写库
                val overlapWeeks = TermWeekGate.weeksInsideTerm(
                    a.weeks.intersect(b.weeks.toSet()).sorted(),
                    semesterStart,
                    totalWeeks,
                )
                if (overlapWeeks.isNotEmpty()) {
                    result.add(Conflict(first = a, second = b, weeks = overlapWeeks))
                }
            }
        }
        return result
    }

    private fun periodsOverlap(a: List<Int>, b: List<Int>): Boolean =
        a.any { it in b }
}