package com.buaa.schedule.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T190 的表驱动：周课表**表头那一行日期**用的「学期第几周」内核 [headerWeekNumberOrNull]。
 *
 * 病灶是 [WeekView] 组合期里**并排两枚**同一个量的派生：Pager 那一枚 `val pagerWeek = displayWeek ?: currentWeek`
 * 不带兜底，表头那一枚（改前原文 `val displayWeekNumber = displayWeek ?: currentWeek ?: 1`）带。
 * 寒暑假 / 学期结束后（有学期行、`currentWeek == null`、用户没手动浏览周次）诚实的那枚回 null ⇒
 * Pager 退回单页网格、屏幕上是空网格；带 `?: 1` 的那枚退成 1 ⇒ **表头印出学期第 1 周那七天**的日期，
 * 而第 1 周根本不在屏上，同屏顶栏第一行写着「假期中」。T182 收了胶囊（不再谎报哪天是今天），
 * 日期这一半留在本卡。
 *
 * 表打的档位（卡面点名的五格）：正常当周 / 浏览往周 / `(null, null) ⇒ null` / `(3, null) ⇒ 3` /
 * `(null, 5) ⇒ 5`。另外三遍不冗余的尺：②内核**从不造出两枚入参之外的周号**（这就是"零 `?: 1`"的实数版，
 * 比"grep 没匹配到"强）；③与改前逐格相比只削掉会说谎的那一格（一格不许加）；
 * ④与同屏顶栏 [weekHeadline] 同源、⑤与 T182 那半枚 [shouldHighlightToday] 同源
 * （"亮着的胶囊"与"印着的日期"必须同一条口径，两半是同一枚矛盾）。
 *
 * 纯 JVM：浏览周次 / 当前教学周两样事实全部由参数注入 —— 没有时钟、没有 Robolectric、没有 android import
 * （本仓收单硬口径；理由见 [WeekHeaderTodayPolicy] 的 KDoc）。
 */
class WeekHeaderDatePolicyTest {

    private data class Row(
        val scene: String,
        val displayWeek: Int?,
        val currentWeek: Int?,
        val expected: Int?,
    )

    /** 改前表头那一枚原样转录：`displayWeek ?: currentWeek ?: 1`（最后一枚 `?: 1` 就是本卡的病灶） */
    private fun legacyHeaderWeekNumber(displayWeek: Int?, currentWeek: Int?): Int? =
        displayWeek ?: currentWeek ?: 1

    private val weeks = listOf(null, 1, 3, 5)

    // ─────────────── ① 卡面点名的五档 ───────────────

    @Test
    fun `五个档位逐格对号`() {
        val table = listOf(
            Row(
                "正常当周：本周第 5 周、屏幕也停在第 5 周 ⇒ 表头印第 5 周的七天（与改前逐字同值，本卡不许动正常档）",
                5, 5, 5,
            ),
            Row(
                "浏览往周：本周第 5 周、屏幕停在第 3 周 ⇒ 印的是屏上那周的日期，不是本周的",
                3, 5, 3,
            ),
            Row(
                "寒暑假（学期结束后）：两枚周次事实都取不到 ⇒ null。改前正是在这一格退成 1，" +
                    "表头在空网格上印出第 1 周的日期，而同屏顶栏写着「假期中」",
                null, null, null,
            ),
            Row(
                "假期里用户手动翻到第 3 周：有浏览周次就照它印（Pager 此时也在第 3 周那一页），与顶栏「第3周（浏览）」同值",
                3, null, 3,
            ),
            Row(
                "displayWeek 缺、currentWeek 有 ⇒ 用当前周。这一格与改前同值（改前取到的也是 5），" +
                    "且唯一调用点（HomeScreen 传 browseWeek ?: state.currentWeek）造不出它 —— " +
                    "列出来是为了钉住「本卡只削会说谎的那一格」",
                null, 5, 5,
            ),
        )
        for (row in table) {
            val actual = headerWeekNumberOrNull(row.displayWeek, row.currentWeek)
            assertEquals(
                "${row.scene} —— headerWeekNumberOrNull(" +
                    "displayWeek=${row.displayWeek}, currentWeek=${row.currentWeek}) 实到 $actual",
                row.expected,
                actual,
            )
            if (row.expected == null) {
                assertNull(
                    "${row.scene} —— 表头这一枚必须回 null（回 0 或回 1 都是在造周次，" +
                        "而调用点据此把 weekStartDate 留成 null、表头那一行只剩星期名）",
                    actual,
                )
            }
        }
    }

    // ─────────────── ② 零 `?: 1` 的实数版 ───────────────

    /**
     * 本仓口径：**不许拿「grep 没匹配到」当全绿**。所以"没有兜底"不靠文本比对，靠这一格——
     * 内核的结果永远等于两枚入参之一（[headerWeekNumberOrNull] 的语义就是 `displayWeek ?: currentWeek`），
     * 任何"入参里没有的周号"（1、0、-1、20…）都是造出来的。
     */
    @Test
    fun `内核从不造出两枚入参之外的周号`() {
        val fabricated = mutableListOf<String>()
        for (displayWeek in weeks) {
            for (currentWeek in weeks) {
                val actual = headerWeekNumberOrNull(displayWeek, currentWeek)
                if (actual != displayWeek && actual != currentWeek) {
                    fabricated += "displayWeek=$displayWeek currentWeek=$currentWeek ⇒ $actual"
                }
            }
        }
        assertEquals(
            "表头这一枚造出了入参之外的周次（病灶不回潮就是钉这一件事）：${fabricated.joinToString(" | ")}",
            emptyList<String>(),
            fabricated,
        )
        assertNull(
            "两枚入参都是 null 时表头必须留空：这一格就是寒暑假那一档（Pager 同档回 null 走单页网格）",
            headerWeekNumberOrNull(null, null),
        )
    }

    // ─────────────── ③ 只削会说谎的那一格 ───────────────

    /**
     * 与改前逐格对：改后与改前不等的那一格**只许**是 `displayWeek == null && currentWeek == null`
     * （改前在那里造出 1）；正常档一格不许丢日期 —— 谁把内核写成"见到 null 就全关"或"第 1 周不印"，
     * 这一格先红。反向的放宽也不许：改后为 null 时改前必须也为 null（不可能，故等价于"改后 ⊆ 改前"）。
     */
    @Test
    fun `与改前逐格相比只削会说谎的那一格`() {
        val differs = mutableListOf<String>()
        for (displayWeek in weeks) {
            for (currentWeek in weeks) {
                val before = legacyHeaderWeekNumber(displayWeek, currentWeek)
                val after = headerWeekNumberOrNull(displayWeek, currentWeek)
                if (before != after) differs += "displayWeek=$displayWeek currentWeek=$currentWeek：改前 $before ⇒ 改后 $after"
            }
        }
        assertEquals(
            "改前改后不等的格子不是 1 格（会说谎的只有「两枚周次都取不到」那一格）：${differs.joinToString(" | ")}",
            listOf("displayWeek=null currentWeek=null：改前 1 ⇒ 改后 null"),
            differs,
        )
    }

    /** 第 1 周不是特殊档：真的在第 1 周时表头印的就是本周日期，一格不许少 */
    @Test
    fun `学期第1周不是特殊档`() {
        assertWeek(1, headerWeekNumberOrNull(1, 1), "正常当周就是第 1 周")
        assertWeek(1, headerWeekNumberOrNull(1, null), "假期里翻到第 1 周（Pager 也在第 1 页）")
        assertWeek(1, headerWeekNumberOrNull(null, 1), "本周第 1 周、没手动浏览")
    }

    /** 两枚入参都是 Int?，拿一枚本地尺收一下：免得 assertEquals 落到重载上含糊 */
    private fun assertWeek(expected: Int?, actual: Int?, why: String) {
        assertEquals("$why —— headerWeekNumberOrNull 实到 $actual，期望 $expected", expected, actual)
    }

    // ─────────────── ④ 与同屏顶栏那句同源 ───────────────

    /**
     * 病灶的判据本体就是"同一屏两处各说各话"（胶囊那一半在 T182 已经这么钉过），所以这张表直接拿
     * 顶栏那句 [weekHeadline] 对：**表头印日期 ⇒ 顶栏必须说出一个具体周**。
     * 「假期中」「未设置学期」那两句底下，表头那一行只剩星期名。
     *
     * 不可达的格子按本仓两条事实筛掉，筛掉的枚数被钉死（改这张尺先确认表本身没被换小）：
     * - `hasSemester == false && currentWeek != null`：`currentWeek` 由 ScheduleViewModel 只在
     *   `semester?.startLocalDate` 上算，没有学期就没有它（4 枚浏览周次 × 3 枚周号 = 12 格）；
     * - `hasSemester == true && displayWeek == null && currentWeek != null`：唯一调用点 HomeScreen 传
     *   `displayWeek = browseWeek ?: state.currentWeek`，displayWeek 为 null 时 currentWeek 必为 null（3 格）。
     *   这一格落在筛子里是因为顶栏那句只看 displayWeek（`displayWeek == null` 直接说「假期中」），
     *   而内核照 currentWeek 给周号 —— 可达性为 0 的格子里它才成得了形，写在这儿正是要它别被当成实账。
     * 32 格筛掉 12 + 3 ⇒ 17 格进表。
     */
    @Test
    fun `表头印日期时顶栏那句必须说得出具体周`() {
        val namesAWeek = Regex("""第\d+周""")
        var reachable = 0
        val contradicts = mutableListOf<String>()
        for (hasSemester in listOf(false, true)) {
            for (displayWeek in weeks) {
                for (currentWeek in weeks) {
                    if (currentWeek != null && !hasSemester) continue
                    if (hasSemester && displayWeek == null && currentWeek != null) continue
                    reachable++
                    // 表头那一格印日期要同时满足两件事：内核给出周号 + 学期自己给得出开学日
                    //（`weekStartDate = displayWeekNumber?.let { semester?.startLocalDate?.plusDays(…) }`）
                    val week = headerWeekNumberOrNull(displayWeek, currentWeek)
                    val actual = hasSemester && week != null
                    val headline = weekHeadline(hasSemester, displayWeek, currentWeek)
                    // 「第5周」与「第5周（浏览）」都说得出具体周（containsMatchIn 而不是 matches：
                    // 浏览往周那一档顶栏多后缀那三个字，表头照旧要印日期）
                    if (actual != namesAWeek.containsMatchIn(headline)) {
                        contradicts += "hasSemester=$hasSemester displayWeek=$displayWeek " +
                            "currentWeek=$currentWeek ⇒ 顶栏「$headline」而表头印日期=$actual"
                    }
                    if (headline == "假期中" || headline == "未设置学期") {
                        assertTrue(
                            "顶栏写着「$headline」时表头还在印某一天的日期（病灶本体）：" +
                                "hasSemester=$hasSemester displayWeek=$displayWeek currentWeek=$currentWeek",
                            !actual,
                        )
                    }
                }
            }
        }
        assertEquals("可达格子枚数（改这张尺先确认表本身没被换小）", 17, reachable)
        assertEquals(
            "表头日期与顶栏那句周次标题打架：${contradicts.joinToString(" | ")}",
            emptyList<String>(),
            contradicts,
        )
    }

    // ─────────────── ⑤ 与 T182 那半枚同源 ───────────────

    /**
     * 胶囊（T182 [shouldHighlightToday]）与日期（本卡）是同一枚矛盾的两半，两半必须共用同一条口径：
     * **有学期而表头根本不印日期时，胶囊也不许亮** —— 否则"今天"就扣在一格没有日期的星期名上，
     * 与假期里扣在第 1 周某一天上是同一种谎。
     * （没学期那一档例外且刻意保留：那时两枚都是 null，[shouldHighlightToday] 给 true 只说"今天星期几"，
     * 本卡 KDoc 里那半句"方向感"就是它，见 [WeekHeaderTodayPolicy]。）
     */
    @Test
    fun `有学期而表头不印日期时胶囊也不许亮`() {
        for (displayWeek in weeks) {
            for (currentWeek in weeks) {
                val week = headerWeekNumberOrNull(displayWeek, currentWeek)
                val highlight = shouldHighlightToday(displayWeek, currentWeek, hasSemester = true)
                if (week == null) {
                    assertTrue(
                        "有学期、表头不印日期（第几周两枚事实都取不到），胶囊却亮着 ⇒ 亮在一格没有日期的星期名上：" +
                            "displayWeek=$displayWeek currentWeek=$currentWeek",
                        !highlight,
                    )
                }
            }
        }
    }
}
