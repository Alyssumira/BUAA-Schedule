package com.buaa.schedule.ui.home

/**
 * 日视图时间轴色块的**行预算**内核（T61）。零 import 的纯 JVM 判据。
 *
 * 它替代的是 `38.dp / 58.dp` 那两枚魔法数。那两档是按**一种字体缩放 + 一张排版表**
 * 标定出来的：45 分钟的课在 1.05dp/分钟下只有 47.25dp，而第三行（教室）要 58dp
 * ⇒ 单节课的色块永远只出课名＋时间段，这就是用户报的「时间轴模式显示的文字内容有点少」。
 * 同一对常数在系统字号调大时又反过来**过度承诺**（T54 实测到的正是"第三行把课名顶了出去"）：
 * 行高按 fontScale 长，而 dp 门槛不长，两头都错。
 *
 * 所以这里不量高度、只算账：调用点把已经量好的事实（块高、每行的行高、块的内边距、
 * 可用宽度）当数字递进来，内核回答"这一块的 Column 里按顺序该画哪几行"。
 * 仓库口径（同 [com.buaa.schedule.ui.signin.ScanRecoveryPolicy] / [DayTimelineAxis]）：
 * 判据一旦自己去读 MaterialTheme / Density / Build / 量文本，JVM 表驱动单测就到位了。
 *
 * 行高取的是排版表里的 `lineHeight`（× fontScale 后按 dp 递进来），不是文本真测：
 * Compose 的 lineHeight 是**下限**而非定值，字体自带的行框更高时以字体为准
 * （本仓 12sp 的 labelMedium 实测 17.1dp > 标称 16sp）。差出来的那一截是**已知的余量**，
 * 装机逐块量过没有裁切（见 T61 的截图账）；要改成逐行实测就得把 TextMeasurer 搬进组合期，
 * 拿一次首帧抖动换 1dp 的精确度，不值。
 */

/**
 * 时间轴色块里的一枚候选文字行。
 *
 * 枚举的声明顺序**不是**绘制顺序（绘制顺序由调用点递进来的 specs 决定），
 * 这里排的确实是本应用的内容优先级：课名 > 时间/节次/教师 > 教室 > 备注。
 *
 * @param pinned 永不裁掉的那一行。只有课名够格：它是这块颜色的身份，
 *   裁掉它这块就变成一块不知道是什么的色带——宁可文字按块高裁切。
 */
internal enum class DayTimelineBlockLine(val pinned: Boolean) {
    /** 第一行：课程名（labelLarge） */
    CourseName(pinned = true),

    /** 第二行：`08:00–08:45 · 第1节 · 王建国`（labelMedium，缺项由 joinMeta 连同分隔符一起缺席） */
    TimeAndTeacher(pinned = false),

    /** 第三行：教室（labelMedium，次级墨色） */
    Room(pinned = false),

    /** 第四行：备注（只有连排的高块装得下，T61 之前这一行压根不存在） */
    Remark(pinned = false),
}

/**
 * 一枚候选行连同它量好的行高。
 *
 * @param heightDp 该行占掉的 dp（排版 lineHeight × fontScale）。
 *   ≤0 当"这一行没有内容可摆"处理（调用点据此直接不递，递了也不画）。
 */
internal data class DayTimelineLineSpec(
    val line: DayTimelineBlockLine,
    val heightDp: Double,
)

/** 内核的答复：按绘制顺序排好的行集合。 */
internal data class DayTimelineBlockPlan(val lines: List<DayTimelineBlockLine>) {
    operator fun contains(line: DayTimelineBlockLine): Boolean = line in lines
}

/**
 * 纵向行预算：从块高里扣掉上下内边距，剩下的按 specs 的**给定顺序**逐行分，
 * 第一次装不下就往后的行全部让位（行序 = 信息优先级，矮行不许插队）；
 * `pinned` 行始终在场（哪怕容量已经用光）。
 *
 * 横向只有一条规则：可用宽度量不到（≤0）时附加行一概不承诺。
 * 宽度在这里**不是**限制项——色块是通栏的、每行都锁 `maxLines = 1 + Ellipsis`，
 * 超宽由省略号收口（对照 T48 那笔真正的宽度账：Row 的同排子节点互抢，这里没有同排）。
 * 它留在这里是为了让"什么都量不到"那一档有个明确行为，而不是零宽也照画三行省略号。
 *
 * @param blockHeightDp 色块的高度（dp，已经过 minTouchTarget 补齐——补齐的意义就是"这里能多放东西"）
 * @param contentVerticalPaddingDp 块内文字的上下内边距（单侧）
 * @param availableWidthDp 一行可用的宽度（dp），0/负 = 没量到
 */
internal fun planDayTimelineBlockLines(
    blockHeightDp: Double,
    contentVerticalPaddingDp: Double,
    availableWidthDp: Double,
    specs: List<DayTimelineLineSpec>,
): DayTimelineBlockPlan {
    val capacity = (blockHeightDp - contentVerticalPaddingDp * 2.0).coerceAtLeast(0.0)
    val widthKnown = availableWidthDp > 0.0
    val chosen = mutableListOf<DayTimelineBlockLine>()
    var used = 0.0
    var starved = false
    for (spec in specs) {
        if (spec.heightDp <= 0.0) continue
        if (spec.line.pinned) {
            chosen += spec.line
            used += spec.heightDp
            continue
        }
        // 行序就是信息优先级：装不下之后不许让后面的矮行插队。
        // 教室(16) 换备注(16) 这种"塞得进就换一张牌"的读法，等于把内容顺序交回给高度，
        // 而这三行的取舍账是按"用户先该看到什么"排的。
        if (starved || !widthKnown) continue
        if (used + spec.heightDp > capacity) {
            starved = true
            continue
        }
        chosen += spec.line
        used += spec.heightDp
    }
    return DayTimelineBlockPlan(chosen)
}
