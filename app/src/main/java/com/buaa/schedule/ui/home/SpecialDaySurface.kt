package com.buaa.schedule.ui.home

/**
 * 「页头这一天该把标注摆成什么样」的版面判据内核（T62③）。纯判据，**零 import**：
 * 不碰 android/androidx、不读 `LocalConfiguration` / `DisplayMetrics`、不碰 `MaterialTheme`，
 * 所有宽度都是调用点**量好了当参数递进来的数**（收单硬判据，同 [SpecialDayRefreshPolicy]、
 * `ui/home/DayTimelineBlockLines.kt` 两个先例的形）。
 *
 * 用户这句「节假日没有标注出来」在 T60 接上数据、判据内核（`SpecialDayBadgePolicy`）定下
 * "挂哪一枚、全称是什么"之后，剩的账全部在**摆**上：今日页页头是一行两行的固定版面，
 * 左右 16dp 外就是翻页箭头，中间那一列窄屏只剩 ~230dp；把「休· 国庆节调休上班」整句
 * 无条件塞进副行，就是 T48 修过的病 replay——长文案把同行别的东西挤出可见区。
 * 所以这里把"摆不摆得下"从直觉变成算式：先量（调用点用 TextMeasurer 实测各段宽度），
 * 再摆（本内核按宽度预算给结论），最后一道防线才是 ellipsis。
 *
 * 判"放不放得下"的算式只有一个 `<=`，为什么还值得单独成文：它必须**可打表**。
 * 恰好放得下 / 差一点点 / 完全放不下 / 没有说明 / 说明超长这五档，在真机上靠肉眼
 * 一种档一种档换字号去撞是不现实的（页头宽度还随箭头、系统字号、翻到的那天变），
 * 而在这里它们只是七枚 Double 的输入组合。
 *
 * ⚠️ 产品口径不变（见 `SpecialDay` 的 KDoc）：**只标注、不参与任何计算**。
 * 本内核答的只是"这句话摆成哪一档"，周次计算、课程过滤、提醒调度一律不读它。
 */

/** [specialDayHeaderSurface] 的结论，三档互斥 */
internal sealed interface SpecialDayHeaderSurface {

    /** 这一天没有标注（或标注文本本身是空的）：页头**一个字节都不许多出来**——不许留占位空文案 */
    data object None : SpecialDayHeaderSurface

    /** 一整句都装得下（如「休· 中秋节」）。[text] 就是要显示的那一句 */
    data class Full(val text: String) : SpecialDayHeaderSurface

    /**
     * 整句装不下，只挂徽标本体（「休」/「班」一枚字）。
     *
     * 注意取向：**被省略的永远是说明，不是徽标本体**——"节假日没有标注出来"这句投诉
     * 的底线是至少看得见「休」，所以宁可丢「· 国庆节调休上班」也不让那枚字消失。
     * 就算连本体都量不下也回这一档：调用点那一行用 T48 的 `weight(1f, fill = false)`
     * 摆法（徽标拿自然宽、前导文字吃剩下的并 ellipsis），真到极限时省略号吃掉的是
     * 前导的周次文字，本体永远排在被裁的第一现场之外。
     */
    data class BadgeOnly(val text: String) : SpecialDayHeaderSurface
}

/**
 * 页头那一行按宽度预算该摆哪一档。
 *
 * 算式：`leadingWidthDp + gapWidthDp + fullWidthDp <= availableWidthDp` 成立才给 [Full]；
 * 边界取等号（恰好放得下就是放得下，Compose 的约束判定同样是 `<=`）。
 * [fullText] 缺席或为空白时按"只有本体"处理：此时 [Full] 与 [BadgeOnly] 的文本相同，
 * 差别只在"这一格本来就没有说明可省"，调用点无须再判一次 note。
 *
 * 宽度全部是 dp 的**实测值**（px 换算、fontScale 都归调用点管）：内核自己去读
 * `LocalDensity` 的那一刻，这张表就再也打不开了——而"差 0.5dp 时到底掉哪一档"
 * 恰恰是最需要被钉死的一档。
 *
 * @param badgeText 徽标本体（「休」/「班」），null/空白 = 这一天没有标注 → [None]
 * @param fullText 带全称的整句（「休· 中秋节」），null/空白 = 这一天没有说明可摆
 * @param badgeWidthDp [badgeText] 的实测宽度
 * @param fullWidthDp [fullText] 的实测宽度（无说明时按 [badgeWidthDp] 递进来即可）
 * @param leadingWidthDp 同一行里**已有文字**（「今天 · 第 N 周」）的实测宽度
 * @param gapWidthDp 徽标与前导文字之间的固定留白（调用点那份 padding 的 dp 值）
 * @param availableWidthDp 页头中间那一列的可用宽度（左右箭头之外的实测值）
 */
internal fun specialDayHeaderSurface(
    badgeText: String?,
    fullText: String?,
    badgeWidthDp: Double,
    fullWidthDp: Double,
    leadingWidthDp: Double,
    gapWidthDp: Double,
    availableWidthDp: Double,
): SpecialDayHeaderSurface {
    val badge = badgeText?.takeIf { it.isNotBlank() } ?: return SpecialDayHeaderSurface.None
    val full = fullText?.takeIf { it.isNotBlank() } ?: badge
    if (full == badge) {
        // 没有说明可省：装不装得下只决定落在哪一档，两档的文字是同一枚本体
        val fitsBadge = leadingWidthDp + gapWidthDp + badgeWidthDp <= availableWidthDp
        return if (fitsBadge) {
            SpecialDayHeaderSurface.Full(badge)
        } else {
            SpecialDayHeaderSurface.BadgeOnly(badge)
        }
    }
    val fitsFull = leadingWidthDp + gapWidthDp + fullWidthDp <= availableWidthDp
    return if (fitsFull) {
        SpecialDayHeaderSurface.Full(full)
    } else {
        SpecialDayHeaderSurface.BadgeOnly(badge)
    }
}

/**
 * 说明被版面藏起来时，读屏那一侧要听到的整句：`国庆节（节假日）` / `调休上班那句（调休上班）`。
 *
 * 摆不进格子的不是字，是**信息**：周表头一格窄屏只有 ~43dp，「国庆节调休上班」七个字
 * 会把日期本身挤没（这就是全称只进 semantics 不进画面的原因），但读屏用户不该因此
 * 永远听不到节假日叫什么。休/班两种说法在括号里分得开——屏幕上那一枚字的颜色本来就
 * 是两色（error/primary），读屏等价物只能落在措辞上。
 *
 * null/空白 note 回 null：调用点拿到 null 就**不加任何语义**，没有标注的格子读起来
 * 与改前逐字节一致（与 [None] 同一口径：无数据不许多出东西）。
 */
internal fun specialDayHiddenNoteDescription(isHoliday: Boolean, note: String?): String? {
    val name = note?.takeIf { it.isNotBlank() } ?: return null
    return "$name（${if (isHoliday) "节假日" else "调休上班"}）"
}
