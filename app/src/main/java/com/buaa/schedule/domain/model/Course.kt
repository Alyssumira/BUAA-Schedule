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
    /**
     * 学分。教务接口（`BuaaCourseDto.credit`）给的是 `"3.5"` / `"0.0"` 这样的字符串，
     * 这里存**解析后的数值**，解析不出来就是 null。
     *
     * 为什么不用字符串原样存：统计页要按学分求和与排序，字符串口径把
     * 「没有学分数据」和「学分就是 0」混成同一件事，而教务确实返回过 `"0.0"`
     * 的课（入学教育类），两类必须分得开。
     * - null：不知道（手动/文本/ICS 导入、字段缺失、脏值），不计入总学分；
     * - 0.0：教务明说这门课不计学分。
     *
     * 位置只能在末尾：全仓约 50 处 `Course(...)` 有按位置构造的，插在中间会静默错位。
     */
    val credit: Double? = null,
) {
    /**
     * 起始/结束节次。**空表兜底成 1**，所以它回答的是"排在哪门课前面"，
     * 不是"这门课第 1 节有课"。
     *
     * 全仓十来处 `sortedBy { it.startPeriod }` / `minByOrNull` 靠的就是这个语义：
     * 没有节次的课排到最前面，是次序问题，不出错。改成可空会把那串调用点全变成
     * `?:` 或 `!!`，等于把同一件事摊到十几处重做一遍判断。
     *
     * 但**别拿它当位置用**：一旦把这个 Int 当成"确实有一节在 N"来渲染、导出、
     * 撑时间轴，空节次的课就会凭空占掉第 1 节（T27：WakeUp JSON 里的
     * `startNode:1,step:0`、组件上的「1高等数学」、24h 轴被拉到 08:00）。
     * 需要区分"第 1 节"和"根本没有节次"时用 [firstPeriodOrNull] / [lastPeriodOrNull]，
     * 拿不准就直呼 `periods.minOrNull()` —— 两个属性只是它的一层薄壳。
     */
    val startPeriod: Int get() = periods.minOrNull() ?: 1
    /** 见 [startPeriod]：结束节次的同一条口径，同样不可用于定位。 */
    val endPeriod: Int get() = periods.maxOrNull() ?: 1

    /**
     * [startPeriod] 的可空版：没有节次就是 null，不替用户宣称"第 1 节有课"。
     *
     * 位置渲染 / 对外导出走这一条，与 T26 定下的「缺项整段跳过」（[joinMeta]）同源：
     * 缺的那一项不出场，而不是换个猜测值顶上。清单类界面（课程管理页、待导入清单）
     * 仍要照常列出这门课 —— 它是用户数据，不能因为缺节次就人间蒸发。
     */
    val firstPeriodOrNull: Int? get() = periods.minOrNull()

    /** 见 [firstPeriodOrNull]：[endPeriod] 的可空版。 */
    val lastPeriodOrNull: Int? get() = periods.maxOrNull()

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
 * [toPeriodSegments] 的显式「不按时间切」选项：相邻节次号一律算连堂。
 *
 * 只给**确实拿不到节次表**的调用方用（课程编辑器在节次表加载前就要画出分段预览）。
 * 曾经这是 `gapMinutes` 的默认值 —— 默认值让「忘记传」与「有意不传」长得一模一样，
 * 于是 P1-2 藏在 [periodLabel] 里两轮没被发现。现在参数必填，编译器替我们记着。
 */
val NO_PERIOD_GAP: (Int, Int) -> Long? = { _, _ -> null }

/**
 * 把节次列表切成连续段，用于渲染和展示，如 [1,2,9,10] -> [[1..2],[9..10]]
 *
 * [gapMinutes] 给出相邻两节的墙钟间隔（前一节下课 → 后一节上课）：
 * 只按节次号相邻切段的话，`[5,6]` 会变成一个横跨午饭的 3h15m 段落 ——
 * 导出 3h15m 的 VEVENT、"课程进行中"与勿扰罩住整个午休都由它引起。
 * 拿不到节次表时显式传 [NO_PERIOD_GAP]，别在切段口径上留隐性默认。
 */
fun List<Int>.toPeriodSegments(
    gapMinutes: (Int, Int) -> Long?,
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

/**
 * 节次列表的人类可读标签，如 [1,2,9,10] -> "第1-2,9-10节"
 *
 * [gapMinutes] 必须与 [toPeriodSegments] 切课次时用的是**同一个**口径：这里曾经漏传，
 * 于是 `[5,6]` 的课在周视图渲染成两张卡、文案却写「第5-6节」，导出的 ICS 里
 * 两个事件都标着「第5-6节」而各自只覆盖 45 分钟（P1-2）。
 * 拿不到节次表时显式传 [NO_PERIOD_GAP]。
 *
 * **没有节次时返回空串**，不是 "第节"：以前的写法是 `joinToString` 得到 "" 再套上
 * 「第…节」，于是通知里冒出一句只有包装、没有内容的「第节」。空串就是全应用约定的
 * "这一项缺席"信号，由各调用点的 [joinMeta] 连分隔符一起丢掉 —— 与其在十来处
 * 各写一遍判空，不如让口径只有一处。
 */
fun periodLabel(periods: List<Int>, gapMinutes: (Int, Int) -> Long?): String =
    periods.toPeriodSegments(gapMinutes)
        .joinToString(",") { range ->
            if (range.first == range.last) "${range.first}" else "${range.first}-${range.last}"
        }
        .takeIf { it.isNotEmpty() }
        ?.let { "第${it}节" }
        ?: ""

/**
 * [periodLabel] 的节次表版本：自己从 [slots] 算间隔，省掉每个展示层调用点各写一遍样板。
 *
 * 空表回退 [TimeSlotProfile.DEFAULT] —— 与导出/渲染各处已有的兜底同口径
 * （学期还没同步到节次表时也要能标出节次，而不是干脆不写）。
 * 这条兜底管的是**节次表缺失**，与「这门课没有节次」是两件事：`periods` 为空时
 * 这里照样按 [periodLabel] 的口径返回空串，不会因为换了默认表就凭空造出节次。
 */
fun periodLabelOf(periods: List<Int>, slots: List<TimeSlot>): String = periodLabel(
    periods,
    periodGapMinutesOf(slots.ifEmpty { TimeSlotProfile.DEFAULT }.toStartEndTimes()),
)

/**
 * 剥掉 [periodLabel] 那层「第…节」包装，只留裸节次号："第1-2,9-10节" -> "1-2,9-10"。
 *
 * 课程管理页的摘要与桌面组件的行副字段都要这串裸号（「第…节」在窄行里没有信息量），
 * 只是一个直接写、一个还要补回「节」字 —— 共用的是剥包装这一步，不是最终写法。
 * **空标签原样返回空串**：组件那边补「节」字必须等确认有内容之后，
 * 否则空节次会剩下一个光秃秃的「节」。
 */
fun unwrappedPeriodLabel(label: String): String =
    label.removePrefix("第").removeSuffix("节").trim()

/**
 * 一行文案的段落拼接：**空段落连同分隔符一起缺席**（「缺项整段跳过」）。
 *
 * 这条约定全应用早就有了 —— 课程实况的 `liveMetaLine`、组件行副字段的 `widgetRowMeta`
 * 都是 `listOfNotNull(x.takeIf { it.isNotBlank() }).joinToString(" · ")` 那个形状。
 * 抽出来是因为节次这条链上有两处漏了：先无条件 `append(" · ")` 再拼标签，
 * 标签一缺席就留下悬空分隔符。调用点只此一处判空，别在十来处各写一套 `if`。
 *
 * 分隔符默认「空格点空格」，与通知/组件既有口径一致；括号里用 "，"、
 * 窄行里用 " " 的调用点自己传。
 */
fun joinMeta(parts: Iterable<String?>, separator: String = " · "): String =
    parts.filterNotNull().filter { it.isNotBlank() }.joinToString(separator)

/** [joinMeta] 的逐项写法，见上。 */
fun joinMeta(vararg parts: String?, separator: String = " · "): String = joinMeta(parts.toList(), separator)

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
