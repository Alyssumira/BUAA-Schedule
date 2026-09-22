package com.buaa.schedule.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 假期/调休标注「这一轮到底去不去抓」判据的表驱动单测（T60）。
 *
 * 这一档以前在 JVM 里**没法测**：判据（本月/下月、有没有缓存文件）写在
 * `ScheduleViewModel.refreshSpecialDays()` 里，而"有没有缓存"要去问 `SpecialDayCache`
 * 读文件系统、"有没有会话"要去问 `BuaaWebSession` 那个 WebView 单例 —— 三样都得造假，
 * 于是这一链的四种停法一行都没被打过表。用户看到的表现就是「节假日没有标注出来」，
 * 而日志里连一行痕迹都没有：全仓唯一的触发点跑在会话出现之前，那一趟
 * 在 `fetchTeachingSchedule` 第一行返回 null，静默结束，此后再也没人敲过门。
 *
 * 抽成 [SpecialDayRefreshPolicy] 之后，五份输入全是值：当前月份、缓存月份的月龄、
 * 屏上月份、会话在不在位、闸门有没有被占 —— 这里逐支钉住。
 * 本文件不调任何取钟口：`now` 是参数，TTL 是常量，这正是这张卡要的形态。
 */
class SpecialDayRefreshPolicyTest {

    private val now = month(2026, 9)
    private val nextMonth = month(2026, 10)

    /** 默认窗口那份"都还新鲜"的缓存：本月 + 下月各一份，月龄由表格指定 */
    private fun freshWindow(nowAge: Long = 0L, nextAge: Long = 0L) =
        listOf(SpecialDayCacheAge(now, nowAge), SpecialDayCacheAge(nextMonth, nextAge))

    private fun decide(
        cached: List<SpecialDayCacheAge>,
        visible: List<SpecialDayMonth> = emptyList(),
        session: Boolean = true,
        inFlight: Boolean = false,
        at: SpecialDayMonth = now,
    ) = decideSpecialDayFetch(
        now = at,
        cachedAges = cached,
        visibleMonths = visible,
        sessionAvailable = session,
        fetchInFlight = inFlight,
    )

    // ---- ① 空缓存：默认窗口两个月都要抓，且顺序是"本月在前" ----

    @Test
    fun emptyCachePlansTheDefaultWindow() {
        val decision = decide(cached = emptyList())
        assertTrue("空缓存这一轮什么都没打算抓：$decision", decision is SpecialDayDecision.Fetch)
        decision as SpecialDayDecision.Fetch
        assertEquals(listOf(now, nextMonth), decision.months)
        assertEquals("两个月都在窗口内，不该有顺延：", emptyList<SpecialDayMonth>(), decision.deferredMonths)
    }

    @Test
    fun defaultWindowFollowsTheMonthAcrossTheYearBoundary() {
        // 12 月进位：下个月是次年 1 月，不是 12 月自己（`(year*12+month)` 折回日历时最容易错的一档）
        val december = decide(cached = emptyList(), at = month(2026, 12))
        assertEquals(
            listOf(month(2026, 12), month(2027, 1)),
            (december as SpecialDayDecision.Fetch).months,
        )
        // 1 月往前翻月：上一个是上年 12 月
        assertEquals(month(2025, 12), month(2026, 1).plus(-1))
        assertEquals(month(2027, 1), month(2026, 12).plus(1))
        assertEquals("序号折回月份时 12 月整倍数要退一年：", month(2026, 12), specialDayMonthOfOrdinal(month(2026, 12).ordinal))
    }

    // ---- ② TTL：满 7 天仍然新鲜，第 8 天才过期（口径与原 SpecialDayCache.staleMonths 一致）----

    @Test
    fun cacheAgeBoundaryAtSevenDaysIsStillFreshAndEightIsStale() {
        // 下月一律没有缓存：于是"本月进不进待抓列表"就是新鲜那条判据的读数
        val table = listOf(0L to true, 1L to true, 6L to true, 7L to true, 8L to false, 30L to false)
        for ((age, expectFresh) in table) {
            val decision = decide(cached = listOf(SpecialDayCacheAge(now, age)))
            decision as SpecialDayDecision.Fetch
            val expected = if (expectFresh) listOf(nextMonth) else listOf(now, nextMonth)
            assertEquals(
                "本月月龄 $age 天（TTL = $SpecialDayCacheStaleAfterDays 天）的待抓列表不对：",
                expected, decision.months,
            )
        }
        val allFresh = decide(cached = freshWindow(nowAge = 7L, nextAge = 7L))
        allFresh as SpecialDayDecision.Skip
        assertEquals("两月都在 TTL 内却还要联网：", SpecialDayFetchSkip.CacheFresh, allFresh.reason)
        assertEquals("「已查月份」要点名这两月，否则读日志的人分不清是没查还是查了没过期：",
            listOf(now, nextMonth), allFresh.months)
    }

    /** 半新半旧：只补过期那一月 —— 以前这里整月一起跳过（本月第一次抓成功后就永久跳过，R5 F-44） */
    @Test
    fun partiallyStaleWindowRefetchesOnlyTheStaleHalf() {
        val decision = decide(cached = freshWindow(nowAge = 0L, nextAge = 9L))
        decision as SpecialDayDecision.Fetch
        assertEquals("只该补过期那个下月：", listOf(nextMonth), decision.months)
        val flipped = decide(cached = freshWindow(nowAge = 12L, nextAge = 0L)) as SpecialDayDecision.Fetch
        assertEquals("反过来也一样，新鲜的月份不许被顺手重抓：", listOf(now), flipped.months)
    }

    // ---- ③ 屏上月份：窗口之外的那几个月也要进来（"翻到跨月的那一周没标注"就是这一档）----

    @Test
    fun visibleMonthsOutsideTheDefaultWindowAreRequested() {
        val last = month(2026, 8)
        val far = month(2026, 11)
        val table = listOf(
            last to "上学期里的寒假月",
            far to "两个月之后",
            month(2025, 12) to "去年",
        )
        for ((visible, label) in table) {
            val decision = decide(cached = freshWindow(), visible = listOf(visible))
            decision as SpecialDayDecision.Fetch
            assertEquals("$label 那一月没进待抓列表：", listOf(visible), decision.months)
        }
        // 屏上月份自己已经新鲜时，回到 CacheFresh 那一档，并且日志要点名查过它
        val freshVisible = decide(cached = freshWindow() + SpecialDayCacheAge(nextMonth.plus(2), 1L), visible = listOf(nextMonth.plus(2)))
        assertEquals(
            "屏上月份已有新鲜缓存还去联网：",
            SpecialDayFetchSkip.CacheFresh,
            (freshVisible as SpecialDayDecision.Skip).reason,
        )
    }

    /** 同一轮里默认窗口与屏上月份都要抓：默认窗口排在前面，截断时才先牺牲远月 */
    @Test
    fun defaultWindowIsOrderedAheadOfVisibleMonths() {
        val decision = decide(cached = emptyList(), visible = listOf(month(2027, 3), month(2026, 8)))
        decision as SpecialDayDecision.Fetch
        assertEquals(
            "屏上月份排到了默认窗口之前（截断时先牺牲的就是眼前这一屏），或者远月排在了近月之前：",
            listOf(now, nextMonth, month(2026, 8), month(2027, 3)),
            decision.months,
        )
    }

    /** 一趟最多抓 MaxSpecialDayMonthsPerFetch 个月，多出来的要说出来（顺延 ≠ 没触发） */
    @Test
    fun excessMonthsAreDeferredAndNamedAsDeferred() {
        val visible = (1..6).map { now.plus(it) }
        val decision = decide(cached = emptyList(), visible = visible)
        decision as SpecialDayDecision.Fetch
        assertEquals("一趟抓的月份数没有卡在名额上：", MaxSpecialDayMonthsPerFetch, decision.months.size)
        assertEquals("本月不在第一批里（默认窗口被远月挤掉了）：", now, decision.months.first())
        assertEquals("该抓 7 个、只排上 " + MaxSpecialDayMonthsPerFetch + " 个，剩下的必须是「顺延」而不是消失：",
            7 - MaxSpecialDayMonthsPerFetch, decision.deferredMonths.size)
        assertEquals("顺延的月份必须与请求的那几月不重不漏：", 7, (decision.months + decision.deferredMonths).toSet().size)
        val line = specialDayFetchDoneLog(decision.months, decision.months, decision.deferredMonths, 0, SpecialDayTrigger.BrowseMonth)
        assertTrue("成功那一行没把顺延的月份点名（读起来就像全都补上了）：$line",
            line.contains("顺延月份=2027-01/2027-02/2027-03"))
    }

    // ---- ④ 会话不在位：这一档以前是纯静默的死路（fetchTeachingSchedule 第一行 return null）----

    @Test
    fun noSessionSkipsWithThePendingMonthsNamed() {
        val decision = decide(cached = emptyList(), session = false)
        assertEquals(SpecialDayFetchSkip.SessionUnavailable, (decision as SpecialDayDecision.Skip).reason)
        assertEquals("待补月份没算出来（那就是白跑一趟）：", listOf(now, nextMonth), decision.months)
        // 缓存全新鲜时不许说"无会话"：那一档的实情是"本来就不用联网"
        val fresh = decide(cached = freshWindow(), session = false) as SpecialDayDecision.Skip
        assertEquals(
            "无会话 + 缓存新鲜 ⇒ 说成缓存新鲜，不能吓唬人去登录：",
            SpecialDayFetchSkip.CacheFresh, fresh.reason,
        )
    }

    // ---- ⑤ 闸门：并发触发只许一路过去，而且它排在所有判据之前 ----

    @Test
    fun inFlightWinsAheadOfEverythingElse() {
        val table = listOf(
            Triple(emptyList<SpecialDayCacheAge>(), true, "无缓存 + 有会话"),
            Triple(freshWindow(), true, "全新鲜 + 有会话"),
            Triple(emptyList<SpecialDayCacheAge>(), false, "无缓存 + 无会话"),
        )
        for ((cached, session, label) in table) {
            val decision = decide(cached = cached, session = session, inFlight = true)
            decision as SpecialDayDecision.Skip
            assertEquals("$label 那一档在闸门之后还各说一句话：", SpecialDayFetchSkip.AlreadyFetching, decision.reason)
            assertEquals(
                "$label：闸门那一档不许声称本轮看过哪些月份（它压根没算过）：",
                emptyList<SpecialDayMonth>(), decision.months,
            )
        }
    }

    // ---- ⑥ 四句停法 + 一句成功：每句只允许说它那一档真的知道的东西 ----

    @Test
    fun everySkipSaysWhichRungItStoppedAt() {
        val lines = SpecialDayFetchSkip.entries.map { reason ->
            reason to specialDaySkipLog(
                reason,
                if (reason == SpecialDayFetchSkip.AlreadyFetching) emptyList() else listOf(now, nextMonth),
                SpecialDayTrigger.SessionReady,
            )
        }
        for ((reason, line) in lines) {
            assertTrue("停法那一行没写明是哪一档：$line", line.contains(reason.reason))
            assertTrue("没写是谁敲的门（三处触发点分不开）：$line", line.contains("触发=会话刚建立"))
            assertTrue("四种结局里没有任何一行是空话：", line.length > 20)
        }
        val latched = lines.first { it.first == SpecialDayFetchSkip.AlreadyFetching }.second
        assertFalse("闸门那行报了月份（本轮没算过月份，那就是假话）：$latched", latched.contains("2026-09"))
        val fresh = lines.first { it.first == SpecialDayFetchSkip.CacheFresh }.second
        assertTrue("「缓存新鲜」那行没带上 TTL 的数字，读的人无从对照：$fresh", fresh.contains("7 天"))
        assertTrue("查过的月份要点名：$fresh", fresh.contains("2026-09/2026-10"))
        val nullBack = lines.first { it.first == SpecialDayFetchSkip.FetchReturnedNull }.second
        assertTrue("空手回那行要点名请求了哪几个月：$nullBack", nullBack.contains("请求月份=2026-09/2026-10"))

        // 触发点三个都要有名字：日志里出现「触发=null」就是枚举漏了 label
        assertEquals(3, SpecialDayTrigger.entries.size)
        assertTrue(SpecialDayTrigger.entries.all { it.label.isNotBlank() })
    }

    /** 逐月请求可以只成一半：成功那行必须把"没回来的那几个月"单列出来 */
    @Test
    fun partialFetchSuccessNamesTheMonthsThatDidNotComeBack() {
        val line = specialDayFetchDoneLog(
            requested = listOf(now, nextMonth),
            returned = listOf(now),
            deferred = emptyList(),
            totalAnnotations = 21,
            trigger = SpecialDayTrigger.PageResume,
        )
        assertTrue("请求两个月只回一个，那行却看不出来：$line", line.contains("未返回月份=2026-10"))
        assertTrue(line.contains("返回月份=2026-09"))
        assertTrue(line.contains("标注累计=21 条"))
        assertFalse("没回来的月份写成了「无」：$line", line.contains("未返回月份=无"))
    }

    /** 月份标签补零走 padStart（String.format 受 Locale 影响，那条理由见实现处） */
    @Test
    fun monthLabelsAreZeroPaddedWithoutLocale() {
        assertEquals("2026-01/2026-12", specialDayMonthsLabel(listOf(month(2026, 1), month(2026, 12))))
        assertEquals("空列表要说成「无」，留空读起来像掉了字：", "无", specialDayMonthsLabel(emptyList()))
    }

    private fun month(year: Int, month: Int) = SpecialDayMonth(year, month)
}
