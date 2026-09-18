package com.buaa.schedule.domain.schedule

import com.buaa.schedule.domain.model.Course

/**
 * 领域输入约束：所有写入路径（教务导入、文本/ICS/口令/备份导入、手动编辑）
 * 统一经这里校验/归一化，UI 只做提示、不做最终防线。
 */
object CourseConstraints {

    /** 教学周上限（含位图/区间展开后的单值） */
    const val MAX_WEEK = 30

    /** 学期总周数上限 */
    const val MAX_TOTAL_WEEKS = 30

    /** 节次上限 */
    const val MAX_PERIOD = 30

    /** 提醒提前分钟数上限 */
    const val MAX_ADVANCE_MINUTES = 240

    /** 单次导入/恢复的课程条数上限 */
    const val MAX_COURSE_COUNT = 2000

    /** 自定义颜色合法上限（ARGB 32 位，0x00000000..0xFFFFFFFF） */
    const val MAX_CUSTOM_COLOR_ARGB = 0xFFFFFFFFL

    /**
     * 单门课程学分上限。北航一门课最多十几学分（毕业设计量级），100 足以拦住
     * 脏数据又不会误伤任何真实课程 —— 统计页是按学分求和的，一条 `"1e308"`
     * 就足以把整学期的总学分变成无穷大。
     */
    const val MAX_CREDIT = 100.0

    fun normalizeTotalWeeks(value: Int): Int = value.coerceIn(1, MAX_TOTAL_WEEKS)

    fun normalizeAdvanceMinutes(value: Int): Int = value.coerceIn(0, MAX_ADVANCE_MINUTES)

    /**
     * 自定义颜色归一化：只接受 32 位 ARGB 范围内的值，越界返回 null
     * （回退到按 [Course.colorIndex] 取调色板），避免损坏备份/分享口令
     * 把非法数值写库后由 Color 消费端渲染出随机颜色。
     */
    fun normalizeCustomColorArgb(value: Long?): Long? =
        value?.takeIf { it in 0L..MAX_CUSTOM_COLOR_ARGB }

    /**
     * 学分归一化：只接受 `0..MAX_CREDIT` 内的有限数值，其余一律判为「没有学分数据」。
     *
     * 坏值必须回退成 null 而不是 0：统计页里 0 的含义是"教务明说这门课不计学分"，
     * 脏数值（负数、`1e308`、NaN）冒充 0 会把总学分算错、而且从界面上看不出来。
     */
    fun normalizeCredit(value: Double?): Double? =
        value?.takeIf { it.isFinite() && it in 0.0..MAX_CREDIT }

    fun normalizeWeeks(weeks: List<Int>): List<Int> =
        weeks.filter { it in 1..MAX_WEEK }.distinct().sorted()

    fun normalizePeriods(periods: List<Int>): List<Int> =
        periods.filter { it in 1..MAX_PERIOD }.distinct().sorted()

    /**
     * 校验并归一化一条课程。无法挽救（无名称、无节次、无周次）返回 null。
     */
    fun normalize(course: Course): Course? {
        if (course.dayOfWeek !in 1..7) return null
        val periods = normalizePeriods(course.periods)
        if (periods.isEmpty()) return null
        val weeks = normalizeWeeks(course.weeks)
        if (weeks.isEmpty()) return null
        val name = course.name.trim().ifEmpty { return null }
        return course.copy(
            name = name,
            periods = periods,
            weeks = weeks,
            colorIndex = course.colorIndex.coerceAtLeast(0),
            customColorArgb = normalizeCustomColorArgb(course.customColorArgb),
            credit = normalizeCredit(course.credit),
        )
    }
}
