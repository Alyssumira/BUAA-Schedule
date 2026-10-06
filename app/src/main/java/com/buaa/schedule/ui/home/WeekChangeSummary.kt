package com.buaa.schedule.ui.home

import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.schedule.SemesterStats

/**
 * 「这一周的课程与上周不同」那句摘要的判据内核（③C-02）。
 *
 * 纯判据：零 android import，本周周号与上周周号都由调用点（`HomeScreen` 里的
 * `weekChangeSummary`）量好了当参数递进来 —— 「现在是第几周」读的是时钟与学期锚点，
 * 那是 [com.buaa.schedule.domain.schedule.WeekCalculator] 的职责，本文件不碰它，
 * 因此"交接周该念几个门"可以直接在 JVM 里跑表。
 *
 * ## 病（注释与代码自相矛盾的那一句）
 *
 * 抽核之前那一版按 `Course.id` 数集合差，而 `id` 是**一条排课片段**的主键。
 * 教务最常见的形态恰好是「同一门课拆成两段：理论 1-8 周 + 实验 9-16 周」，两段各有各的
 * `id` ⇒ 第 9 周本周集合 = {实验} 、上周集合 = {理论}，差集两边各剩 1 个元素，
 * 摘要于是念成「比上周多 1 门、少 1 门」，而按门的真相是**没变**（门没变、课也没"没了"，
 * 只是同一门课换了一半）。摘要整条都在讲"门数动了"，数的却是行数，
 * 同段注释那句「两段课只算一门」兑不了现。
 *
 * ## 口径：只有 [SemesterStats.courseGroupKey] 这一份
 *
 * 「一门课 ≠ 一条 Course」是全仓既有口径：学分归并（[SemesterStats.creditsByCourse]）、
 * 周次覆盖图（[com.buaa.schedule.domain.schedule.CourseWeekSpans]）、
 * 每日门数（[SemesterStats.dayLoads]）、趋势结课周
 * （[com.buaa.schedule.domain.schedule.WeeklyLoadTrend]）都取它，本文件不另造第四套。
 * 于是同一段注释里那句「两段课只算一门」第一次真的由代码兑了现。
 *
 * ⚠️ 已知且**刻意沿用**的粗口径：`sourceGroupKey == null` 的那一档（手动课程、文本/ICS 导入）
 * [SemesterStats.courseGroupKey] 退化成 `学期|课程名`，两门同名课会被并成 1 门、摘要跟着少报。
 * 那是 [SemesterStats] 早就写下并接受的口径（见它的 KDoc「没有组键的退化成名字即身份」），
 * 本文件只沿用、不修 —— 在这里另拧一档就得给全站四台引擎对第二次账。
 *
 * 周号越界（`lastWeek < 1`，即本周是第 1 周或更早）时不下结论：第 1 周之前没有"上周"可比，
 * 硬比会把整学期的第一课念成"多 N 门"，那是噪声不是异动。
 */
object WeekChangeSummary {

    /**
     * 本周相对上周的异动枚数，**单位是门，不是排课片段条数**。
     *
     * @param added 本周在、上周不在的门数
     * @param removed 上周在、本周不在的门数
     */
    data class Counts(val added: Int, val removed: Int) {
        /** 两个方向都没动 ⇒ 界面不该留痕 */
        val unchanged: Boolean get() = added == 0 && removed == 0
    }

    /**
     * 按门数两个周号的课集合并求差。
     *
     * 门集合用 [Set] 而不是 [List]：一条门可以有多个片段落在同一周（理论课与实验课同组、
     * 或一周排两次），列表会把它们数成两门。
     *
     * @param courses 已经过页面侧筛选的课程集合（校区筛选等在调用点做完再递进来）
     * @param thisWeek 摘要要说的那一周（浏览周优先，见调用点）
     * @param lastWeek 与它比的那一周
     */
    fun counts(courses: List<Course>, thisWeek: Int, lastWeek: Int): Counts {
        val thisWeekDoors = doorKeysAt(courses, thisWeek)
        val lastWeekDoors = doorKeysAt(courses, lastWeek)
        return Counts(
            added = (thisWeekDoors - lastWeekDoors).size,
            removed = (lastWeekDoors - thisWeekDoors).size,
        )
    }

    /** 某一周实际在上的那些**门**的身份键集合 */
    fun doorKeysAt(courses: List<Course>, week: Int): Set<String> =
        courses.filter { it.weeks.contains(week) }
            .mapTo(mutableSetOf()) { SemesterStats.courseGroupKey(it) }

    /**
     * 摘要文案；返 null = 这一条不显示（要么没得比，要么按门数真的没动）。
     *
     * 两个周号都可空是因为调用点那一周本来就可能是 null（未设学期时
     * [com.buaa.schedule.domain.schedule.WeekCalculator] 给不出周号）；
     * "取哪个周号、上周怎么退一格"仍由调用点决定，本函数只负责判没得比的那一档。
     * 文案里念的枚数与 [counts] 同源，绝不在此处再数一遍片段。
     */
    fun summaryText(courses: List<Course>, thisWeek: Int?, lastWeek: Int?): String? {
        val week = thisWeek ?: return null
        val previous = lastWeek ?: return null
        if (week < 1 || previous < 1) return null
        val changed = counts(courses, week, previous)
        return when {
            changed.unchanged -> null
            changed.added > 0 && changed.removed > 0 -> "比上周多 ${changed.added} 门、少 ${changed.removed} 门"
            changed.added > 0 -> "比上周多 ${changed.added} 门"
            else -> "比上周少 ${changed.removed} 门"
        }
    }
}
