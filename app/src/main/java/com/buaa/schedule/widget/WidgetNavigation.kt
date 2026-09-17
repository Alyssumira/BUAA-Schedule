package com.buaa.schedule.widget

import com.buaa.schedule.domain.model.Semester
import com.buaa.schedule.domain.model.startLocalDate
import com.buaa.schedule.domain.schedule.WeekCalculator
import java.time.LocalDate

/**
 * 桌面组件 → App 的导航契约，以及组件翻周的纯逻辑。
 *
 * 常量放在 widget 包里而不是 MainActivity 里：组件侧是生产方，
 * MainActivity 只负责消费它认得的那几个 deeplink 键。
 */
object WidgetNavigation {

    /**
     * 点 4×2 网格的一格 → 那一天的日视图。
     * 值为 ISO 星期序号：`1`=周一 … `7`=周日；缺省或 `0` 表示不是从格子进来的。
     */
    const val EXTRA_DAY_OF_WEEK = "com.buaa.schedule.widget.EXTRA_DAY_OF_WEEK"

    /**
     * 组件表头的「上一周 / 下一周」热区。
     *
     * 用**显式组件**意图发给该组件自己的 Provider，因此不需要在 manifest 里加
     * intent-filter（manifest 不归组件侧改）：显式意图不受 filter 匹配限制。
     */
    const val ACTION_BROWSE_WEEK = "com.buaa.schedule.widget.ACTION_BROWSE_WEEK"

    /** [ACTION_BROWSE_WEEK] 的周次增量：`-1` 上一周，`+1` 下一周 */
    const val EXTRA_WEEK_DELTA = "com.buaa.schedule.widget.EXTRA_WEEK_DELTA"
}

/**
 * 该组件实例当前应当渲染的教学周：基准周 + 持久化浏览偏移，两侧夹在 `[1, totalWeeks]`。
 * 未设置学期 / 开学日期非法时返回 null（调用方按「无课表」降级）。
 */
internal fun displayWeekOf(semester: Semester?, today: LocalDate, offset: Int): Int? {
    val base = browseBaseWeek(semester, today) ?: return null
    return (base + offset).coerceIn(1, totalWeeksOf(semester))
}

/**
 * 渲染口径的重载：偏移要先过 [effectiveWeekOffset] 的过期判定，四个渲染点
 * （两个 Provider 重绘 + 两个 RemoteViewsFactory 取数）必须算出同一个周次，
 * 所以这条顺序不能靠调用方各自记住。
 */
internal fun displayWeekOf(semester: Semester?, today: LocalDate, binding: WidgetBinding): Int? =
    displayWeekOf(semester, today, effectiveWeekOffset(binding, browseBaseWeek(semester, today)))

/**
 * 偏移只在**取它的那一周**里有效（审查 3.5 的收口）。
 *
 * 基准周一变就归零：周日晚上点「下周」预览周一的课，到了周一那一周本身就该显示它，
 * 继续留着 +1 会让组件从此永久漂一周——而桌面上没有任何第二个入口能翻回来。
 */
internal fun effectiveWeekOffset(binding: WidgetBinding, baseWeek: Int?): Int =
    if (baseWeek != null && binding.weekOffsetBase == baseWeek) binding.weekOffset else 0

/**
 * 可浏览的基准周。
 *
 * 假期中 [WeekCalculator.currentWeek] 会给出 0（开学前）或 totalWeeks+1（结束后），
 * 这里退到最近的学期内周 —— 否则寒假里想提前看第 1 周安排，热区会整个点不动。
 */
internal fun browseBaseWeek(semester: Semester?, today: LocalDate): Int? {
    val start = semester?.startLocalDate ?: return null
    return WeekCalculator.currentWeek(start, today).coerceIn(1, totalWeeksOf(semester))
}

/** 偏移的允许区间：基准周 + 偏移必须仍落在学期内，点到底就停住而不是绕回 */
internal fun clampWeekOffset(baseWeek: Int, totalWeeks: Int, offset: Int): Int {
    val total = totalWeeks.coerceAtLeast(1)
    val base = baseWeek.coerceIn(1, total)
    return offset.coerceIn(1 - base, total - base)
}

/** 一次点击（[delta] = ±1）之后的新偏移 */
internal fun stepWeekOffset(baseWeek: Int, totalWeeks: Int, offset: Int, delta: Int): Int =
    clampWeekOffset(baseWeek, totalWeeks, offset + delta)

/** totalWeeks 为 0 的脏数据会让 coerceIn(min > max) 抛异常，先兜成 1 周 */
private fun totalWeeksOf(semester: Semester?): Int = (semester?.totalWeeks ?: 1).coerceAtLeast(1)
