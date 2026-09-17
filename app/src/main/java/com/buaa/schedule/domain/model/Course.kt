package com.buaa.schedule.domain.model

import androidx.compose.runtime.Immutable
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * 课程记录。
 *
 * 北航教务中一门课可能由多个片段组成（理论课 / 实验课、不同教师 / 教室 / 周次），
 * 因此每条 Course 只描述一个“排课片段”；[sourceGroupKey] 用于把同一门课的多个片段关联起来。
 *
 * [periods] 是节次列表，支持非连续节次（如第 1,2 节 + 第 9,10 节连排）；
 * 常规连续课程存 1..N 展开后的列表。
 *
 * [Immutable]：所有字段都是 val，且 List 在构造后不再被修改（导入流程一律 copy 生成新值）。
 * 标上之后 Compose 编译器把它当稳定类型，Composable 参数里的 Course 即使
 * 只换了引用也能被跳过重组 —— 课表页每帧有几十个 Course 参数，收益明显。
 */
@Immutable
data class Course(
    val id: Long = 0L,
    val name: String,
    /** 课程别名（仅显示层使用）：设置后卡片/列表显示别名，教务原名保留 */
    val alias: String? = null,
    val teacher: String? = null,
    val location: String? = null,
    val campus: String? = null,
    val dayOfWeek: Int,
    val periods: List<Int>,
    val weeks: List<Int>,
    val colorIndex: Int = 0,
    /** 自定义课程卡片颜色（ARGB），为空时按 colorIndex 从调色板取 */
    val customColorArgb: Long? = null,
    val remark: String? = null,
    val sourceGroupKey: String? = null,
    val semesterCode: String? = null,
    val isManualOverride: Boolean = false,
) {
    val startPeriod: Int get() = periods.minOrNull() ?: 1
    val endPeriod: Int get() = periods.maxOrNull() ?: 1

    /** 显示名：别名优先，空别名回退教务原名 */
    val displayName: String
        get() = alias?.trim()?.takeIf { it.isNotEmpty() } ?: name
}

/**
 * 相邻两节之间允许的墙钟间隔上限（分钟）：超过就不算连堂。
 *
 * 默认作息里课间只有 5–15 分钟，午休 1h45m、晚饭 45m —— 取 20 分钟
 * 既保住 1-2、6-7 这类真正的连堂，又把第 5、6 节（11:30→14:45）
 * 这种「节次号相邻、中间隔着午饭」的列表切成两段。
 * 用户自定义作息若把课间拉到 45 分钟以上，会被判成两段，这正是期望行为。
 */
const val MAX_LINKED_PERIOD_GAP_MINUTES = 20L

/**
 * 把节次列表切成连续段，用于渲染和展示，如 [1,2,9,10] -> [[1..2],[9..10]]
 *
 * [gapMinutes] 给出相邻两节的墙钟间隔（前一节下课 → 后一节上课）：
 * 只按节次号相邻切段的话，`[5,6]` 会变成一个横跨午饭的 3h15m 段落 ——
 * 导出 3h15m 的 VEVENT、"课程进行中"与勿扰罩住整个午休都由它引起。
 * 不传（或该节次没有时间表）时退化为按节次号相邻，与旧的显示类调用方一致。
 */
fun List<Int>.toPeriodSegments(
    gapMinutes: (Int, Int) -> Long? = { _, _ -> null },
): List<IntRange> {
    if (isEmpty()) return emptyList()
    val sorted = sorted().distinct()
    val segments = mutableListOf<IntRange>()
    var start = sorted.first()
    var prev = start
    for (i in 1 until sorted.size) {
        val current = sorted[i]
        val gap = gapMinutes(prev, current)
        if (current == prev + 1 && (gap == null || gap <= MAX_LINKED_PERIOD_GAP_MINUTES)) {
            prev = current
        } else {
            segments.add(start..prev)
            start = current
            prev = current
        }
    }
    segments.add(start..prev)
    return segments
}

/**
 * 由「节次号 → (开始, 结束)」时间表构造 [toPeriodSegments] 要的间隔查询。
 * 任一节次缺时间时返回 null，该处按节次号相邻处理。
 */
fun periodGapMinutesOf(
    slotTimes: Map<Int, Pair<LocalTime, LocalTime>>,
): (Int, Int) -> Long? = { from, to ->
    val end = slotTimes[from]?.second
    val begin = slotTimes[to]?.first
    if (end == null || begin == null) null else ChronoUnit.MINUTES.between(end, begin)
}

/** 单个连续节次段的人类可读标签，如 1..2 -> "第1-2节" */
fun periodLabel(segment: IntRange): String =
    if (segment.first == segment.last) "第${segment.first}节" else "第${segment.first}-${segment.last}节"

/** 节次列表的人类可读标签，如 [1,2,9,10] -> "第1-2,9-10节" */
fun periodLabel(periods: List<Int>): String =
    periods.toPeriodSegments().joinToString(",") { range ->
        if (range.first == range.last) "${range.first}" else "${range.first}-${range.last}"
    }.let { "第${it}节" }

/** 星期简写表，下标 0 = 周一。桌面组件与课程实况共用一份措辞 */
val WEEKDAY_LABELS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

/**
 * 按 ISO 星期序号（1=周一…7=周日）取简写。
 *
 * 越界返回 null 而不是抛：教务导入的脏数据里确实出现过 `dayOfWeek = 0/8`，
 * 而从组件的 `RemoteViewsFactory` 里抛出异常会让整个宿主停在灰色崩溃块上，
 * 不会重试 —— 少一个日标签远比整块组件消失划算。
 */
fun weekdayLabel(dayOfWeek: Int): String? = WEEKDAY_LABELS.getOrNull(dayOfWeek - 1)
