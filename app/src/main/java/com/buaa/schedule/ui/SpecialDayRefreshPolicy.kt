package com.buaa.schedule.ui

/**
 * 「学习日程」假期/调休标注**该不该再去抓一次**的判据内核（T60）。纯判据，零 android/androidx/java.time import。
 *
 * 用户报的「节假日没有标注出来」不是缺功能：抓 → 解析 → 落盘 → 表头画「休/班」这条链
 * 早就端到端接好了（`BuaaWebSession.fetchTeachingSchedule` / `TeachingScheduleParser` /
 * `SpecialDayCache` / `WeekView` 的 `specialDaysOfWeek`），坏的是**这条链只被走到的那几次**：
 * - 全仓唯一一处调用是首页 `LaunchedEffect(Unit)`，一个进程一次，而它跑的时候多半还没有
 *   可用的教务 WebView（冷启动时 `fetchTeachingSchedule` 第一行就 `sessionWebView ?: return null`），
 *   于是那一次机会被白白用掉 —— 之后用户登录成功、会话出现了，也没有任何东西再敲一次门；
 * - 判据只看"本月/下月"，用户翻到跨月的那一周（或寒假那一周）时那个月从没进过缓存；
 * - 三条早退全部不带日志，"没抓到"与"根本没去抓"在证据上完全一样。
 *
 * 所以这一档的修法与扫码页那轮（`ScanRecoveryPolicy`）同形：**把判定收进一个可以在 JVM 里
 * 逐支打表的纯函数**，设备侧事实（当前月份、缓存月份的月龄、屏上出现的月份、会话在不在位、
 * 上一趟有没有在跑）一律由调用点当参数传进来。判据自己去读 `YearMonth.now()` 或问 WebView
 * 单例的那一刻，表驱动单测就到位了 —— 而这一条链的每一档都必须能用输入复现，
 * 因为它剩下的全部风险就是"某一档悄悄不干活"。
 *
 * ⚠️ 本文件只管**要不要去抓、抓哪几个月、没抓的话说哪一句**。抓回来的东西怎么用、
 * 标注如何参与渲染，都在链的另一头，且产品口径不变：假期标注**只标注不跳过**
 * （见 `SpecialDay` 的 KDoc），本内核不影响周次计算与提醒调度。
 */

/**
 * 月份键。内核不引 `java.time`（那是设备/平台侧的类型，仓库口径见类 KDoc），
 * 调用点负责 `YearMonth ↔ SpecialDayMonth` 的双向折算。
 *
 * [ordinal] 把月份压成一个可比较、可加减的整数：`now + 1` 与"翻月"都不需要引日历库，
 * 而 `(year, month)` 的还原只在 [specialDayMonthOfOrdinal] 一处（12 月进位到次年 1 月、
 * 以及 1 月退位到上年 12 月这两头最容易写错，故单独一档打表）。
 */
internal data class SpecialDayMonth(val year: Int, val month: Int) {

    /** 连续可加减的月份序号；只做大小比较与 ±N，不当日历用 */
    val ordinal: Int get() = year * MonthsPerYear + month

    operator fun plus(offsetMonths: Int): SpecialDayMonth = specialDayMonthOfOrdinal(ordinal + offsetMonths)
}

/** 一年十二个月。文件内私有：换算只在本文件这一头发生，别处要用就调 [specialDayMonthOfOrdinal] */
private const val MonthsPerYear = 12

/** [SpecialDayMonth.ordinal] → 月份；[ordinal] 落在 12 月整倍数的档位上要退一年、月置 12 */
internal fun specialDayMonthOfOrdinal(ordinal: Int): SpecialDayMonth {
    // floorDiv + mod 而不是 `/` 与 `%`：负数年份（脏数据、或调用点算错）取模会给出负月份，
    // 于是得到一个既不存在又和任何缓存文件名都对不上的键 —— 那一年就永远判成"没缓存"。
    val year = ordinal.floorDiv(MonthsPerYear)
    val month = ordinal.mod(MonthsPerYear)
    return if (month == 0) {
        SpecialDayMonth(year - 1, MonthsPerYear)
    } else {
        SpecialDayMonth(year, month)
    }
}

/**
 * 一个已缓存月份 + 它的**月龄**（天）。
 *
 * 月龄由调用点从缓存文件的 mtime 与设备墙钟算出来（`SpecialDayCache.cachedMonthAges`）：
 * "多大算旧"是判据、留在本文件的 [SpecialDayCacheStaleAfterDays]，"现在几点、文件改了没有"
 * 是设备事实、留在调用点。
 */
internal data class SpecialDayCacheAge(val month: SpecialDayMonth, val ageDays: Long)

/**
 * 缓存超过这么多天就该重抓。教务的调休安排常在月中/次月才公布或改动，
 * 只按"这个月有文件"判定就会把本月第一次结果永久当成最终结果（R5 F-44 的原账）。
 *
 * 口径与 `SpecialDayCache.staleMonths` 时代一致：月龄按**向上取整**报（不足一天算一天），
 * 于是"满 7 天"落在 `ageDays == 8` 那一档，第 7 天当天仍然算新鲜。
 */
internal const val SpecialDayCacheStaleAfterDays = 7L

/**
 * 一趟最多补抓几个月。
 *
 * 每一次"月"抓是一趟页面内 JS 请求（`fetchTeachingScheduleMonth`），而触发点已经变成三处
 * （回首页 / 会话刚出现 / 翻到新的月份）：用户在学期里一路翻周翻到学期末尾时，
 * 屏上月份可以一次给出四五个都没缓存的月。默认窗口（本月 + 下月）+ 眼前那一两个才是
 * 这一行的服务对象，剩下的留给下一次翻页去补 —— 宁可翻到时慢半秒，不要拿一串
 * 几十秒的 WebView 请求去堵用户正在看的这一屏。
 */
internal const val MaxSpecialDayMonthsPerFetch = 4

/** 停在哪一档。每一档都必须说得出自己，因为这一链的全部历史就是"停在某一档而没人知道" */
internal enum class SpecialDayFetchSkip(val reason: String) {
    /** 上一趟还在跑：这一档**不报月份**（本轮压根没算过要抓哪几个月，报了就是假话） */
    AlreadyFetching("上一次补抓还在进行中"),

    /** 该看的月份都有新鲜缓存：这一档不联网，也不该联网 */
    CacheFresh("缓存都在有效期内"),

    /** 有该补的月份，但没有可用的教务会话（未登录，或保留的 WebView 已被系统回收） */
    SessionUnavailable("无可用教务会话"),

    /** 会话在位、请求发出去了，但一个月都没抓回来（`fetchTeachingSchedule` 空手回） */
    FetchReturnedNull("教务接口未返回任何月份数据"),
}

/** 谁敲的门。日志里带上它，才能回答"这次登录之后到底有没有人来问过" */
internal enum class SpecialDayTrigger(val label: String) {
    /** 进入首页（一个进程里可能很多次，NavHost 每次回到 home 都算） */
    PageResume("回首页"),

    /** 教务会话刚刚被上缴：这是 T60 补上的那一次"最早的可能" */
    SessionReady("会话刚建立"),

    /** 翻周/翻页翻出了没见过的月份 */
    BrowseMonth("浏览到新月份"),
}

/** 判据的结论 */
internal sealed interface SpecialDayDecision {
    /**
     * 该抓这些月份（已按 [MaxSpecialDayMonthsPerFetch] 截断，调用点照单抓即可）。
     *
     * [deferredMonths] 是"这轮该抓却没排上"的那些：截断本身也是一次静默的风险，
     * 调用点要把它们说进日志里，否则翻到那个月却一直没有标注时，读日志的人
     * 会以为是"这一轮没触发"，而真实情况是"触发了、名额用完了"。
     */
    data class Fetch(
        val months: List<SpecialDayMonth>,
        val deferredMonths: List<SpecialDayMonth> = emptyList(),
    ) : SpecialDayDecision

    /** 这一轮什么都不做，[reason] 说清停在哪一档、[months] 是这一句里可以点名的月份 */
    data class Skip(val reason: SpecialDayFetchSkip, val months: List<SpecialDayMonth>) : SpecialDayDecision
}

/**
 * 本轮该不该抓、抓哪几个月。
 *
 * 三档顺序是有意的：**闸门最先**（它连盘都不必读，更重要的是它不能声称自己看过月份），
 * 然后"没有要抓的"，最后才是"有要抓的但门没开"。会话那一档排在待抓判定之后，
 * 因为「无会话」这句话只有在本轮确实有活要干时才算一条诊断 —— 缓存新鲜时说"没登录"，
 * 读到的人就会去登录，而登录完回来仍然什么都不做。
 *
 * @param now 设备当前月份
 * @param cachedAges 已有缓存的月份与月龄（没列出的月份 = 没有缓存文件）
 * @param visibleMonths 屏幕上出现的月份（周视图那一周跨到的月 + 日视图那一天），可含历史月与远期月
 * @param sessionAvailable `BuaaWebSession.hasSession()` 的结果，由调用点读
 * @param fetchInFlight 上一趟补抓还在不在跑（调用点那颗单飞闸门的答案）
 */
internal fun decideSpecialDayFetch(
    now: SpecialDayMonth,
    cachedAges: List<SpecialDayCacheAge>,
    visibleMonths: List<SpecialDayMonth>,
    sessionAvailable: Boolean,
    fetchInFlight: Boolean,
): SpecialDayDecision {
    if (fetchInFlight) return SpecialDayDecision.Skip(SpecialDayFetchSkip.AlreadyFetching, emptyList())

    // 默认窗口（本月 + 下月）永远在场、且排在最前：它保证"人在首页但一页都不翻"
    // 也会按月自己更新，而截断（[MaxSpecialDayMonthsPerFetch]）发生时先牺牲的是屏上
    // 那些**离本月更远**的月份 —— 用户一路翻到学期末尾时，眼前这个月比三个月前
    // 那个月更可能就是他下一眼要看的东西。
    val head = listOf(now, now.plus(1))
    val wanted = head + visibleMonths.filter { it !in head }
        .distinct()
        .sortedBy { monthDistanceFrom(it, now) }
    val freshMonths = cachedAges
        .filter { it.ageDays <= SpecialDayCacheStaleAfterDays }
        .map { it.month }
        .toSet()
    val pending = wanted.filter { it !in freshMonths }
    if (pending.isEmpty()) return SpecialDayDecision.Skip(SpecialDayFetchSkip.CacheFresh, wanted)
    if (!sessionAvailable) return SpecialDayDecision.Skip(SpecialDayFetchSkip.SessionUnavailable, pending)
    return SpecialDayDecision.Fetch(
        months = pending.take(MaxSpecialDayMonthsPerFetch),
        deferredMonths = pending.drop(MaxSpecialDayMonthsPerFetch),
    )
}

/** 与本月隔着几个月。自己写绝对值：引 `kotlin.math` 就等于给这块判据开了第二个取数口 */
private fun monthDistanceFrom(month: SpecialDayMonth, now: SpecialDayMonth): Int {
    val delta = month.ordinal - now.ordinal
    return if (delta < 0) -delta else delta
}

/**
 * 月份列表 → `2026-09/2026-10` 这样一个短串；空列表说成「无」而不是留空。
 *
 * 补零用 `padStart` 不用 `String.format("%02d")`：后者受默认 Locale 影响
 * （阿拉伯-埃及等 Locale 会写出非 ASCII 数字），而这一串是要拿去和缓存文件名
 * 以及 logcat 对账的（同一口径的理由见 `SpecialDayCache.fileNameOf`）。
 */
internal fun specialDayMonthsLabel(months: List<SpecialDayMonth>): String =
    if (months.isEmpty()) {
        "无"
    } else {
        months.joinToString("/") {
            "${it.year.toString().padStart(4, '0')}-${it.month.toString().padStart(2, '0')}"
        }
    }

/**
 * 停在某一档时说的那一句。级别由调用点定（一律 `Log.i`），措辞在这里定死：
 * 四种停法、四句话，每句里点名的月份都只能是那一档真的看过的那些。
 */
internal fun specialDaySkipLog(reason: SpecialDayFetchSkip, months: List<SpecialDayMonth>, trigger: SpecialDayTrigger): String =
    when (reason) {
        SpecialDayFetchSkip.AlreadyFetching -> "假期标注未抓取：${reason.reason} 触发=${trigger.label}"
        SpecialDayFetchSkip.CacheFresh ->
            "假期标注未抓取：${reason.reason}（${SpecialDayCacheStaleAfterDays} 天内）" +
                " 触发=${trigger.label} 已查月份=${specialDayMonthsLabel(months)}"
        SpecialDayFetchSkip.SessionUnavailable ->
            "假期标注未抓取：${reason.reason} 触发=${trigger.label} 待补月份=${specialDayMonthsLabel(months)}"
        SpecialDayFetchSkip.FetchReturnedNull ->
            "假期标注未写入：${reason.reason} 触发=${trigger.label} 请求月份=${specialDayMonthsLabel(months)}"
    }

/**
 * 抓到东西之后那一句（[returned] 非空时才有这一句；空手回走 [specialDaySkipLog] 的
 * [SpecialDayFetchSkip.FetchReturnedNull]）。
 *
 * 请求的月份与**真回来的**月份分开写：`fetchTeachingSchedule` 是逐月请求的，
 * 只说"成功"会让人以为请求里的每一月都到位了，而它完全可能只回来一半 ——
 * 剩下那一半正是"翻到那个月却没有标注"的那个现场。顺延的那几个也要说，
 * 那是本轮名额用完、不是"没触发"。
 */
internal fun specialDayFetchDoneLog(
    requested: List<SpecialDayMonth>,
    returned: List<SpecialDayMonth>,
    deferred: List<SpecialDayMonth>,
    totalAnnotations: Int,
    trigger: SpecialDayTrigger,
): String = buildString {
    append("假期标注已更新 触发=").append(trigger.label)
    append(" 请求月份=").append(specialDayMonthsLabel(requested))
    append(" 返回月份=").append(specialDayMonthsLabel(returned))
    val missing = requested.filter { it !in returned }
    append(" 未返回月份=").append(specialDayMonthsLabel(missing))
    append(" 顺延月份=").append(specialDayMonthsLabel(deferred))
    append(" 标注累计=").append(totalAnnotations).append(" 条")
}
