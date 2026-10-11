package com.buaa.schedule.ui.home

/**
 * 周网格的**纵向行预算**内核（T296①，在册 #537 的 F1／F2／F3）。零 import 的纯 JVM 判据。
 *
 * 它替代的是三处按**标称 lineHeight**（或压根不量）记账的形状：
 * - 课程卡：`titleStyle.lineHeight.takeIf { it.isSp }?.toDp() ?: 16.dp` —— 本仓 T61b① 判死过的那把尺（F1 重）。
 * - 节次轴：`height >= labelLineHeight * 3` 只决定"节次号画不画"，而 `"起\n止"` 那枚 `maxLines = 2` 的
 *   两行时间**无条件在场** —— `maxLines` 管的是行数上限，`Ellipsis` 收的是横向超宽，两者都不管纵向预算（F2 重）。
 * - 视口均分行高：`(screenHeightDp − 96) / slots` 里既没有字号、也没有实测行高，而同一支 `weekRowScale`
 *   在这一档整枚不再被消费（屏上却照样念「行高 130%」）（F3 中）。
 *
 * 为什么标称那把尺不算数（`DayTimelineBlockLines.kt:17-20` 逐字已在册）：Compose 的 `TextStyle.lineHeight`
 * 是**下限**而不是行盒，字体自带行框更高时以字体为准 —— 装机量到 labelMedium 标称 16dp 而实画 17.14dp，
 * 三行标称 52dp 实画 54.10dp，"预算说装得下"与"画出来装得下"之间只剩 1px（0.38dp）真余量，
 * 换一枚 MiSans（HyperOS 自带）就把这一px吃穿。所以这里的输入一律是**调用点量好的 dp**，
 * 内核只做"扣完就不再承诺下一行"这一件事。
 *
 * 分层口径与同族内核一致（`DayTimelineBlockLines.kt:14-15`、`StatsEntryPolicy.kt:6`、`SpecialDaySurface.kt:5`）：
 * 密度、fontScale、实测行高、视口实高都由 Composable 侧量成数字**递进来**，内核不读设备事实 ——
 * 反向即禁令（判据自己去读 MaterialTheme / Density / DisplayMetrics，JVM 表驱动单测就到位了）。
 *
 * 兜底次序照本仓成文口径：**先按实测记账，裁切只是安全网**（`DayTimelineBlockLines.kt:24-25`）。
 * WeekView 那两枚 `clipToBounds()` 落在定高容器的内边距之后，吃的是"没人采样过的字体"那一档残余，
 * 不是这本内核的替代品，也不许反过来拿它当"超了也没关系"的许可证。
 */

/** 容量对「想画的 wantedLines 行」的答复。 */
internal sealed interface WeekLineTier {
    /** wantedLines 行全都画得下，还剩 spareDp 的真余量 */
    data class Fit(val lines: Int, val spareDp: Double) : WeekLineTier

    /** 只画得下 lines 行（≥1），比想要的少 wantedLines − lines 行；deficitDp 是把想要的补齐还差多少 dp */
    data class ShortBy(val lines: Int, val wantedLines: Int, val deficitDp: Double) : WeekLineTier

    /** 连一行都画不下（或这一行的行高压根没量到）：这一档什么都不承诺 */
    object NothingFits : WeekLineTier
}

/**
 * 整行向下取整的容量答复。
 *
 * 用 `floor` 而不是 `toInt()`：后者朝零取整，容量算错时宁可**少承诺一行**，
 * 多承诺的那一行就是要压到邻卡上的那一行。
 */
internal fun weekLineTier(availableDp: Double, lineHeightDp: Double, wantedLines: Int): WeekLineTier {
    if (wantedLines <= 0) return WeekLineTier.Fit(0, availableDp.coerceAtLeast(0.0))
    if (availableDp <= 0.0 || lineHeightDp <= 0.0) return WeekLineTier.NothingFits
    val lines = minOf(kotlin.math.floor(availableDp / lineHeightDp).toInt(), wantedLines)
    return when {
        lines <= 0 -> WeekLineTier.NothingFits
        lines == wantedLines -> WeekLineTier.Fit(lines, availableDp - lines * lineHeightDp)
        else -> WeekLineTier.ShortBy(lines, wantedLines, wantedLines * lineHeightDp - availableDp)
    }
}

/**
 * 课程卡里能画哪几行。
 *
 * 优先级照调用点原有的那段账（副信息先占位，课名用剩下的空间；「这节课去哪上」是这张卡最该当场回答的），
 * 差别只在**单位换成实测行高**，并且每一枚可选行都要给课名留出**至少一行**——留不出就不画这一行，
 * 而不是"标称说还剩 4dp 也算一行"。
 *
 * 课名是这张卡唯一 pinned 的行（理由同 `DayTimelineBlockLine.CourseName`：裁掉它这张卡就变成
 * 不知道是什么的颜色块），所以 `titleLines` 下限恒为 1；真要超了，由调用点那枚 `clipToBounds()` 兜住，
 * 而超出的量留在 [titleTier] 里说实话。
 */
internal data class WeekCourseCardPlan(
    /** 「第 3-4 节」那一行（连续段跨两节以上才有意义） */
    val showPeriodLine: Boolean,
    /** 教师/地点那一行 */
    val showMetaLine: Boolean,
    /** 课名画几行（1..maxTitleLines） */
    val titleLines: Int,
    /** 课名对「想画满 maxTitleLines 行」这个理想的答复：溢出留痕用，不许拿它当"照画"的许可 */
    val titleTier: WeekLineTier,
)

internal fun planWeekCourseCard(
    cardHeightDp: Double,
    contentVerticalPaddingDp: Double,
    titleLineHeightDp: Double,
    metaLineHeightDp: Double,
    hasMetaLine: Boolean,
    hasPeriodLine: Boolean,
    maxTitleLines: Int = WEEK_COURSE_CARD_MAX_TITLE_LINES,
): WeekCourseCardPlan {
    val available = (cardHeightDp - contentVerticalPaddingDp * 2.0).coerceAtLeast(0.0)
    val oneTitle = titleLineHeightDp.coerceAtLeast(0.0)
    var remaining = available
    var showMeta = false
    if (hasMetaLine && metaLineHeightDp > 0.0 && remaining - metaLineHeightDp >= oneTitle) {
        showMeta = true
        remaining -= metaLineHeightDp
    }
    var showPeriod = false
    if (hasPeriodLine && metaLineHeightDp > 0.0 && remaining - metaLineHeightDp >= oneTitle) {
        showPeriod = true
        remaining -= metaLineHeightDp
    }
    val tier = weekLineTier(remaining, titleLineHeightDp, maxTitleLines)
    val drawable = when (tier) {
        is WeekLineTier.Fit -> tier.lines
        is WeekLineTier.ShortBy -> tier.lines
        // 容量连一行都不够：pinned 行照画（宁可被 clip 吃掉一角），溢出由 tier 留痕
        WeekLineTier.NothingFits -> 1
    }
    return WeekCourseCardPlan(
        showPeriodLine = showPeriod,
        showMetaLine = showMeta,
        titleLines = drawable.coerceIn(1, maxTitleLines.coerceAtLeast(1)),
        titleTier = tier,
    )
}

/**
 * 节次轴标签列画哪几行（F2）。
 *
 * 参考稿是「1 / 08:00 / 08:45」三行，改前那枚判据只管第一行、两行时间永远照画。
 * 这里把**三行一起**算进同一笔账：容量按整行向下取整，画不出来的行**就是不画**，
 * 而不是甩给 `contentAlignment = Center` 对称溢出到邻行上。
 *
 * 为什么两行装不下时**不并成一行**（`08:00–08:45`）：节次轴列宽是定值 dp、与字号无关
 * （`DesignTokens.weekTimeColumnWidth = 48.dp`，`timeColumnWidth` 唯一读点），11 枚数字＋连接符
 * 在 labelMedium 那一档横向本来就放不下，并一行只会把「纵向溢出型」换成「横向省略号截断」——
 * 后者看着像数据错了。实际字宽是**待装机读数**，但这个形状不必去赌那枚读数。
 * 所以容量只够一行时只画**起时**（这一行决定"这节课几点开始"，是轴上最该保的那一半）。
 */
internal data class WeekTimeAxisPlan(
    val showPeriodNumber: Boolean,
    val showTwoLineTime: Boolean,
    /** 容量只够一行：只画起时 */
    val showStartOnly: Boolean,
    /** 对「三行都想画」这个理想的答复（溢出留痕；NothingFits 时整档不画） */
    val tier: WeekLineTier,
)

internal fun planWeekTimeAxis(heightDp: Double, lineHeightDp: Double): WeekTimeAxisPlan {
    val tier = weekLineTier(heightDp, lineHeightDp, WEEK_TIME_AXIS_LINES)
    val lines = when (tier) {
        is WeekLineTier.Fit -> tier.lines
        is WeekLineTier.ShortBy -> tier.lines
        WeekLineTier.NothingFits -> 0
    }
    return WeekTimeAxisPlan(
        showPeriodNumber = lines >= WEEK_TIME_AXIS_LINES,
        showTwoLineTime = lines >= 2,
        showStartOnly = lines == 1,
        tier = tier,
    )
}

/**
 * 「视口均分行高」那一支的行高（F3）。
 *
 * 三条改动，逐条对着改前的算式：
 * 1. 视口用**网格自身量到的实高**（`gridViewportPx.height` 折回 dp），不再是
 *    `screenHeightDp − 96` —— 那枚 96 是页头条**实测高度**（`HomeScreen.kt` 的
 *    `headerBandFloorHeightPx`，装机 126px）的一张 dp 抄本：字号调大时页头条会长，96 这一枚不会，
 *    于是「视口均分」在 1.3× 下均不出视口。
 * 2. `weekRowScale` 在这一档**照吃**：改前 fit 为真时那支滑杆整枚不再被消费，而设置页
 *    （`SettingsScreen` 的「行高 N%」）照样念给用户、照样 save()。本仓成文裁定：叫「设计如此」
 *    就要点名改它的那枚字段与代价 —— 所以这里把旋钮喂回来，代价写在设置页那句话里：
 *    托底生效时网格改为滚动（网格本来就在 `verticalScroll` 里，见 WeekView 的容器注释）。
 * 3. 行高有**实测一行**的地板托底：`weekRowHeightFloorDp(实测行高, 卡内边距)`。
 *    字号就是那枚缺失的项 —— 它从地板进来，而不是从"dp 门槛乘 fontScale"进来（那是 F1 的老错）。
 *
 * @param viewportHeightDp 网格视口实高（dp）；≤0 = 这一帧还没量到 → 回退到 [fallbackRowHeightDp]
 * @param fallbackRowHeightDp 首帧回退值：调用点递默认档行高（已经乘过 weekRowScale）
 */
internal fun weekFitViewportRowHeightDp(
    viewportHeightDp: Double,
    slotCount: Int,
    rowScale: Double,
    minimumRowHeightDp: Double,
    fallbackRowHeightDp: Double,
): Double {
    val floor = minimumRowHeightDp.coerceAtLeast(0.0)
    if (viewportHeightDp <= 0.0) return maxOf(fallbackRowHeightDp, floor)
    val scale = if (rowScale.isFinite() && rowScale > 0.0) rowScale else 1.0
    val fitted = viewportHeightDp.coerceAtLeast(WEEK_FIT_VIEWPORT_MIN_DP) / slotCount.coerceAtLeast(1)
    return maxOf(fitted * scale, floor)
}

/**
 * 行高地板：一行**实测**行高 + 卡片自己的上下内边距。
 *
 * 两条消费链共用同一枚行高（卡片与节次轴），其中卡片还要额外扣内边距，是更贵的那一条 ——
 * 拿它当地板，节次轴只会更宽松。没量到行高时回 0（＝不设地板），因为这本内核不该自己造一枚数；
 * 调用点那把尺（`measureTimelineLineHeightDp`）量不到时走的是**偏高**退让支路，不会递 0 进来。
 */
internal fun weekRowHeightFloorDp(lineHeightDp: Double, contentVerticalPaddingDp: Double): Double =
    if (lineHeightDp <= 0.0) 0.0 else lineHeightDp + contentVerticalPaddingDp * 2.0

/** 课程卡课名最多画几行（改前 `coerceIn(1, 3)` 的那枚 3，收成一枚有名字的常量） */
internal const val WEEK_COURSE_CARD_MAX_TITLE_LINES = 3

/** 节次轴满档行数：节次号 + 起 + 止（参考稿那一列的三行） */
internal const val WEEK_TIME_AXIS_LINES = 3

/** 视口再矮也按这个数均分（改前那枚 `coerceAtLeast(200)` 的原值，语义不变） */
internal const val WEEK_FIT_VIEWPORT_MIN_DP = 200.0
