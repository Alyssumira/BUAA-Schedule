package com.buaa.schedule.domain.schedule

import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.Semester

/**
 * 课程周次覆盖（Gantt 式横条）的判据内核。
 *
 * 纯 Kotlin：没有 Compose、没有 Android 依赖，可直接 JVM 单测。
 * "现在是第几周"这类设备事实由调用方当参数传进来（`ScheduleUiState.currentWeek`，
 * 它的上游是 `WeekCalculator`），本文件不读时钟 —— 因此"哪些周已经过去"可测。
 *
 * 它只回答一件事：**这门课在学期的哪几段周次里真的在上**。
 * 统计页此前只有 [SemesterStats.dayLoads] 那种"全学期平均"的说法，平均数会把
 * "只上 1-8 周"和"整学期都上"抹成同一个高度，而这两件事对学生的差别是
 * "第 9 周以后我周一全天空"还是"周一那门课还在"。
 *
 * 沿用 [SemesterStats] 的两个口径：
 * 1. **一行 = 一门课，不是一条排课片段**：按 [SemesterStats.courseGroupKey] 归并后，
 *    组内所有片段的周次取**并集**（理论课 1-8 周 + 实验课 9-16 周是一门课上下半程，
 *    不是两门各占半条）；
 * 2. **横轴长度只按教学周号算**：与 [SemesterStats.weekAxisLength] 同一个分母，
 *    越出 1..totalWeeks 的周次一律裁掉（教务给过越界周次）。
 *
 * 第三件事是本文件特有的、也是最容易画错的一件事：**"没有周次数据" ≠ "整学期没课"**。
 * 一条 `weeks` 为空、或者周次全部越界的片段，会被标成 [Coverage.weeksUnknown]
 * 让界面画成"周次未知"，而不是画一条空轨 —— 后者是在对用户撒谎。
 */
object CourseWeekSpans {

    /**
     * 一门课（按 [SemesterStats.courseGroupKey] 归并）的周次覆盖。
     *
     * @param groupKey 课程身份键，仅用于稳定排序与调试，界面不显示
     * @param course 组内第一个片段：只为展示层取颜色（`courseColor` 要读整条片段上的
     *   `customColorArgb` / `colorIndex`），名字一律读 [label]，别再各自派生一遍
     * @param label 课程显示名（别名优先，见 `Course.displayName`）
     * @param spans 该课实际在上的周次区间，**升序、两两不相邻也不重叠**：
     *   连续周次并成一段，单周 `{1,3,5}` 这类就是三段（不为"好看"把中间的空周并进去）
     * @param firstWeek 最早一次上课的教学周；无有效周次时 null
     * @param lastWeek 最晚一次上课的教学周，即**结课周**；无有效周次时 null
     * @param fragmentCount 该门课由几条排课片段并成（界面可说明"合并自 2 段"）
     * @param weeksUnknown 没有任何落在学期范围内的周次 = 周次数据缺失，
     *   **不是**"这门课整学期都没课"
     * @param finished 已经结课：[lastWeek`]/`currentWeek 都拿得到，且结课周早于当前周。
     *   currentWeek 为 null（假期中 / 学期原点缺失）时恒为 false —— 没有原点就不下结论
     */
    data class Coverage(
        val groupKey: String,
        val course: Course,
        val label: String,
        val spans: List<IntRange>,
        val firstWeek: Int?,
        val lastWeek: Int?,
        val fragmentCount: Int,
        val weeksUnknown: Boolean,
        val finished: Boolean,
    )

    /**
     * [board] 的结果。
     *
     * @param totalWeeks 横轴长度，与 [SemesterStats.weekAxisLength] 同一口径
     * @param currentWeek 当前教学周；null = 原点缺失或不在学期内（界面得说"假期中"）
     * @param rows 覆盖条目，已按 [board] 的排序规则排好
     * @param unknownCount [Coverage.weeksUnknown] 的门数（图下的说明行要用真实数字）
     * @param finishedCount 已结课的门数
     */
    data class Board(
        val totalWeeks: Int,
        val currentWeek: Int?,
        val rows: List<Coverage>,
        val unknownCount: Int,
        val finishedCount: Int,
    ) {
        /** 一行都没有（一门课都没导入）：界面退化成"无数据"，不画空轨道 */
        val isEmpty: Boolean get() = rows.isEmpty()

        /** 有效条目（画得出横条的）门数 = [rows] 减去周次未知的那些 */
        val knownCount: Int get() = rows.size - unknownCount

        /**
         * 接下来最快结课的那门课：[Coverage.lastWeek] 不小于当前周的最小者。
         * 没有当前周、或所有课都已经结完时为 null —— 此时"还剩谁要结课"这个问题不成立。
         */
        val nextToEnding: Coverage?
            get() {
                val week = currentWeek ?: return null
                return rows.asSequence()
                    .filter { !it.weeksUnknown }
                    .mapNotNull { c -> c.lastWeek?.takeIf { it >= week }?.let { it to c } }
                    .minByOrNull { it.first }
                    ?.second
            }
    }

    /**
     * 把排课片段归并成一张覆盖板。
     *
     * 排序按"什么时候结课"：先结课的在前、周次未知的垫到最后（它们排不出先后，
     * 混在中间会让"往下扫一眼就是剩下的学期"这个读法断掉），
     * 同结课周再按开课周、最后按名字，**结果与传入顺序无关**。
     */
    fun board(courses: List<Course>, semester: Semester?, currentWeek: Int?): Board {
        val totalWeeks = SemesterStats.weekAxisLength(courses, semester)
        val grouped = LinkedHashMap<String, MutableList<Course>>()
        courses.forEach { course ->
            grouped.getOrPut(SemesterStats.courseGroupKey(course)) { mutableListOf() }.add(course)
        }
        val rows = grouped.map { (key, fragments) ->
            // 组内周次取并集（口径 1），越出 1..totalWeeks 的那几周一律裁掉（口径 2）
            val inRangeWeeks = fragments
                .flatMap { it.weeks }
                .filter { it in 1..totalWeeks }
                .distinct()
            val lastWeek = inRangeWeeks.maxOrNull()
            Coverage(
                groupKey = key,
                course = fragments.first(),
                label = fragments.first().displayName,
                spans = spansOf(inRangeWeeks),
                firstWeek = inRangeWeeks.minOrNull(),
                lastWeek = lastWeek,
                fragmentCount = fragments.size,
                weeksUnknown = inRangeWeeks.isEmpty(),
                finished = currentWeek != null && lastWeek != null && lastWeek < currentWeek,
            )
        }.sortedWith(
            compareBy<Coverage> { it.weeksUnknown }
                .thenBy { it.lastWeek ?: Int.MAX_VALUE }
                .thenBy { it.firstWeek ?: Int.MAX_VALUE }
                // 兜底一档：上面三档全都并列时（同名同周次的两门重修课）也要给个确定次序
                .thenBy { it.label }
                .thenBy { it.groupKey },
        )
        return Board(
            totalWeeks = totalWeeks,
            currentWeek = currentWeek,
            rows = rows,
            unknownCount = rows.count { it.weeksUnknown },
            finishedCount = rows.count { it.finished },
        )
    }

    /**
     * 周次集合 → 连续的显示段（纯函数，界面上的每一根横条对应结果里的一项）。
     *
     * 不连续的周次**必须**留成多根：`{1,3,5}` 并成 `1..5` 就把"只在单周上"这门课
     * 说成了整段都在上，而这正是这张图要回答的那个问题。
     */
    fun spansOf(weeks: Collection<Int>): List<IntRange> {
        val sorted = weeks.asSequence().filter { it > 0 }.distinct().sorted().toList()
        if (sorted.isEmpty()) return emptyList()
        val spans = mutableListOf<IntRange>()
        var start = sorted.first()
        var previous = start
        sorted.drop(1).forEach { week ->
            if (week == previous + 1) {
                previous = week
            } else {
                spans += start..previous
                start = week
                previous = week
            }
        }
        spans += start..previous
        return spans
    }
}
