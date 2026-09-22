package com.buaa.schedule.ui.home

/**
 * 顶栏「学期统计」入口的**宽度预算**内核（T69）。零 import 的纯 JVM 判据
 * （仓库口径，见 [DayTimelineBlockLines] / [specialDayHeaderSurface] / [dayViewOnScreen]：
 * 判据一旦自己去读 `MaterialTheme` / `LocalDensity` / `LocalConfiguration`，
 * 这张表在 JVM 里就打不开了）。
 *
 * 用户这句「学期统计的入口太深了吧，明明这么丰富的内容」，账是这样的：
 * `StatsScreen` 全仓只有一个路由 `"stats"`，而改前唯一的用户可见入口是
 * `SettingsScreen` 里 `key = "stats"` 那一行 —— 底栏「我的」→ 设置分区 → 学期统计，
 * 至少两跳，而且藏在设置里。一屏能放下的东西，被收进了第三层。
 *
 * 于是入口要搬到首页顶栏第一行（`GlassSegmentedControl` 之前）。搬过来的**唯一**风险是宽度：
 * 那一行是 `Row`，左边那颗 `Column` 带 `weight(1f)`，非加权子节点先按声明顺序吃掉自己的份、
 * 剩下的才轮到它 —— 胶囊多要的那一截，全部从日期那一列的份里扣。扣穿了就是 T48 在设置页
 * 犯过的同一笔账（长课名把状态胶囊整枚裁到卡外），只不过这次被裁的是「9月22日 星期二」。
 * 所以这里把"这一档摆不摆得下"从直觉变成算式：**先量，再摆，最后一道防线才是省略号**。
 *
 * ## 宽度必须是量出来的
 *
 * 不许按「字符数 × 字号」估：本仓为这类账已经栽过两次。T48 是裁到卡外那一笔；
 * T61 是另一头 —— `TextStyle.lineHeight` 是**下限不是实画行高**（装机量到 labelLarge
 * 19.81dp / labelMedium 17.14dp，而排版表标称 20/16），按标称记的账只留 1px 真余量，
 * 换一枚 MiSans 就穿。横向同理：CJK 全角字宽、标点全角、字体 fallback 三样都会让
 * "4 个字 = 4 × 字号"少算，而少算的那一截正好是要裁掉的那一截。
 * 所以三档的实宽与左列的实宽一律由调用点用 `TextMeasurer` 量好（**向上取整到整 px**）
 * 当整数递进来，内核只做减法与一次 `<=`。
 *
 * ## 降级顺序
 *
 * [StatsEntryTier] 的**声明顺序**就是偏好顺序：第一枚最想要，往后一档比前一档更窄
 * （图标那一档用「统计」两字换掉「学期统计」四字，20dp 的图标块换 28dp 的字，所以更窄）。
 * 内核按声明顺序取第一枚放得下的，不重排、不排序 —— 哪天调用点把顺序写歪了，
 * 答复就跟着歪，而 `StatsEntryPolicyTest` 里"偏好顺序不是宽度顺序"那一档正是钉这件事的。
 * 一枚都放不下时返回**最窄**那一档并标 `fits = false`：挤的时候该挤自己，
 * 不许把同行的分段控件顶出屏外（那是 T48 的另一半）。
 *
 * ⚠️ 三档**全都带文字标签**。裸图标不合格：用户要的是"看得出里面有什么"，
 * 一颗没有字面的图标回答不了这个问题（`StatsEntryPolicyTest` 逐档钉着 label 非空）。
 */

/**
 * 顶栏入口的三档形状。**声明顺序 = 偏好顺序**（宽 → 窄），每档都带文字标签。
 *
 * @param label 胶囊上写出来的字面量
 * @param withIcon 文字左边要不要摆一枚图标（图标只补"这是颗能点的东西"，
 *   永远不许代替 label —— 见文件头那条硬判据）
 */
internal enum class StatsEntryTier(val label: String, val withIcon: Boolean) {
    /** 首选：「学期统计」四个字，说得出这一跳通向什么 */
    Full(label = "学期统计", withIcon = false),

    /**
     * 次选：图标 + 「统计」。
     *
     * 让掉的是「学期」两个字，补回来一枚图标 —— 图标块（16dp 图标 + 4dp 间隙）比它换掉的
     * 两个全角字窄，所以这一档确实比 [Full] 省宽度；而读屏念的仍是全称（[contentDescription]）。
     */
    IconCompact(label = "统计", withIcon = true),

    /** 末选：「统计」。仍然是文字，不是裸图标 */
    Compact(label = "统计", withIcon = false);

    /**
     * 读屏名：哪一档都念全称。
     *
     * 胶囊被缩到「统计」时，"统计的是**什么**"在画面上已经没有答案了，只剩这里还说得出；
     * 图标那一档的 `Icon(contentDescription = null)` 也是同一个意思 —— 图标不另起名字，
     * 免得读屏念出「统计，图表图标，学期统计」三截。
     */
    val contentDescription: String get() = Full.label
}

/**
 * 一档候选连同它的**实测**宽度。
 *
 * @param widthPx 这一档在顶栏那一行里占掉的整颗宽度（px，向上取整）：
 *   文字实宽 + 胶囊左右内衬（+ 图标与图标间隙），并已经抬到最小触控宽度之上。
 *   换算与取整全在调用点（那是设备侧事实：density、fontScale、字体），这里只吃整数。
 */
internal data class StatsEntryCandidate(
    val tier: StatsEntryTier,
    val widthPx: Int,
)

/**
 * 内核的答复：画哪一档，以及它到底放得下没有。
 *
 * @param fits false = 一枚都不在预算内。调用点此时要把胶囊夹进预算、文字走省略号
 *   （T48 的最后一道防线），而不是照自然宽度画出去把分段控件顶出屏外。
 */
internal data class StatsEntryPlacement(
    val tier: StatsEntryTier,
    val fits: Boolean,
)

/**
 * 顶栏第一行能给这颗胶囊用的宽度：行宽减去分段控件、减去左列、再减去胶囊自己带进
 * 那一行的两侧留白。
 *
 * 三档事实任一为 null（还没量到）就整体答 null —— 不猜、不兜一个常数。
 * 结果允许是负数：那一档就是"一枚都放不下"，由 [planStatsEntry] 折成 `fits = false`，
 * 这里不夹到 0（夹了就看不出究竟差多少，报告里也就拿不出数）。
 *
 * @param rowWidthPx 顶栏第一行**内容盒**的宽度（外边距已扣：左 spaceL、右 spaceS）
 * @param segmentedWidthPx 同一行最右边那位邻居的实测宽度（它按内容宽度排布、随系统字号长）
 * @param leftColumnWidthPx 左边那颗 Column 的**自然**宽度（Crossfade 两支里最宽的一行，见下）
 * @param reservedGapPx 胶囊左右两侧固定留白的**合计**（2 × spaceS，调用点按 density 换算好）
 */
internal fun statsEntryBudgetPx(
    rowWidthPx: Int?,
    segmentedWidthPx: Int?,
    leftColumnWidthPx: Int?,
    reservedGapPx: Int,
): Int? {
    val row = rowWidthPx ?: return null
    val segmented = segmentedWidthPx ?: return null
    val column = leftColumnWidthPx ?: return null
    return row - segmented - column - reservedGapPx
}

/**
 * 把三档的形状表拼出来：每档 = 它那一档的文字实宽 + 内衬（图标档再加图标块），
 * 并抬到最小触控宽度之上。
 *
 * 收成一个函数只为了一个理由：**档位与宽度的对应关系只许写一遍**。
 * 写在调用点就是"哪天加第四档，忘了量它的文字，那一档就按上一档的宽度算"，
 * 而这一档错下去的方向恰好是"承诺得比画出来的宽"——正是 T61 被打回的那个形状。
 *
 * `when (tier)` 不带 `else`：加一档而没在这里登记，编译期就红。
 *
 * @param fullLabelWidthPx 「学期统计」四字实测宽（px，已向上取整）
 * @param shortLabelWidthPx 「统计」二字实测宽（同上）
 * @param horizontalPaddingPx 胶囊左右内衬合计（2 × spaceM）
 * @param iconBlockPx 图标 + 图标间隙合计（iconSmall + 2 × spaceMicro）
 * @param minPillWidthPx 最小触控宽度（minTouchTarget 换算成 px）：文字再窄胶囊也不许窄过它
 */
internal fun statsEntryCandidates(
    fullLabelWidthPx: Int,
    shortLabelWidthPx: Int,
    horizontalPaddingPx: Int,
    iconBlockPx: Int,
    minPillWidthPx: Int,
): List<StatsEntryCandidate> = StatsEntryTier.entries.map { tier ->
    val labelWidthPx = when (tier) {
        StatsEntryTier.Full -> fullLabelWidthPx
        StatsEntryTier.IconCompact -> shortLabelWidthPx
        StatsEntryTier.Compact -> shortLabelWidthPx
    }
    val chromePx = horizontalPaddingPx + if (tier.withIcon) iconBlockPx else 0
    StatsEntryCandidate(tier, maxOf(minPillWidthPx, labelWidthPx + chromePx))
}

/**
 * 给定预算挑一档。
 *
 * 算式只有一条 `widthPx <= budgetPx`，取等号（**预算恰好等于实宽就是放得下** ——
 * Compose 的约束判定也是 `<=`，这里写成 `<` 会凭空降一档）。
 * 按 [StatsEntryTier] 的声明顺序取第一枚放得下的，不排序：偏好顺序是产品决定，
 * 宽度顺序只是它的副产品。
 *
 * 预算未知（null）时**偏「少占」**：答最窄的一档。方向不是随便挑的 ——
 * 首帧 `onSizeChanged` 还没回来，此时按满幅承诺就会把同行的日期裁掉半截，
 * 而"胶囊先短一截字、下一帧长回来"是看不见的（那一帧正被进场动画盖着）。
 *
 * @param candidates 空表（枚举被清空那一类编程错误）时不抛、答末选：
 *   首页顶栏抛一个异常，代价是整张课表没了，比降一档大得多。
 */
internal fun planStatsEntry(
    budgetPx: Int?,
    candidates: List<StatsEntryCandidate>,
): StatsEntryPlacement {
    val narrowest = candidates.minByOrNull { it.widthPx }?.tier ?: return StatsEntryPlacement(
        StatsEntryTier.Compact,
        fits = false,
    )
    if (budgetPx == null) return StatsEntryPlacement(narrowest, fits = false)
    val chosen = candidates.firstOrNull { it.widthPx <= budgetPx }
    return if (chosen == null) StatsEntryPlacement(narrowest, fits = false)
    else StatsEntryPlacement(chosen.tier, fits = true)
}
