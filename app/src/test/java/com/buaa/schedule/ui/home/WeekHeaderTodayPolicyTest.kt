package com.buaa.schedule.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T182 的表驱动单测：周课表**表头**那枚「今天」胶囊的判据内核 [shouldHighlightToday]。
 *
 * 用户能看到的病灶是效果那一句：寒暑假里顶栏第一行写着「假期中」，同屏周课表的表头却在
 * 学期**第 1 周**里同星期几的那一格上扣着一枚"今天"胶囊，而那一格底下印的日期根本不是今天。
 * 机制在 [WeekView] 组合期里，本模块没有 Compose 运行时，所以把这一判抽成
 * [WeekHeaderTodayPolicy] 的纯内核在这里摆场景 —— 浏览周次、当前教学周、有没有学期三样事实
 * 全部由参数注入（本仓硬口径：没有时钟、没有 Robolectric、没有 android import）。
 *
 * 表打的四个档位（卡面点名的那四格）：
 * 1. 学期在 + currentWeek null + displayWeek null ⇒ **false**（病灶本体）；
 * 2. 正常当周（displayWeek == currentWeek 且非 null）⇒ true；
 * 3. 无学期（压根没设学期）⇒ true（刻意留的方向感，那一档 cellDate 全为 null）；
 * 4. 浏览往周（displayWeek != currentWeek）⇒ false。
 *
 * 另外三遍不冗余的尺：②假期里手动跳到第 N 周那一档（析取支 `|| currentWeek == null` 的
 * 本体，与 1 是两条独立出路）；⑤整张格子上「新判据 ⊆ 改前判据」的单调核对（这一判把
 * "只朝窄收、不许顺手放宽"钉成实数，翻分支式的假收窄也过不了它）；
 * ⑥与同屏顶栏 [weekHeadline] 的口径同源核对（"两处自相矛盾"就是这张表的第 0 列）。
 */
class WeekHeaderTodayPolicyTest {

    private data class Row(
        val scene: String,
        val displayWeek: Int?,
        val currentWeek: Int?,
        val hasSemester: Boolean,
        val expected: Boolean,
    )

    /** 改前那句原样转录：`isBrowsingCurrentWeek || currentWeek == null` */
    private fun legacyHighlightToday(displayWeek: Int?, currentWeek: Int?): Boolean =
        displayWeek == currentWeek || currentWeek == null

    private val weeks = listOf(null, 1, 3, 5)

    // ─────────────── ① 卡面点名的四个档位 ───────────────

    @Test
    fun `四个档位逐格对号`() {
        val table = listOf(
            Row("寒暑假（学期结束后）：学期在、当前教学周取不到、也没在浏览别的周", null, null, true, false),
            Row("正常当周：本周就是第 5 周，屏幕也停在第 5 周", 5, 5, true, true),
            Row("无学期：没设学期，表头不印日期只印星期名", null, null, false, true),
            Row("浏览往周：本周第 5 周，屏幕停在第 3 周", 3, 5, true, false),
        )
        for (row in table) {
            assertEquals(
                "${row.scene} —— shouldHighlightToday(" +
                    "displayWeek=${row.displayWeek}, currentWeek=${row.currentWeek}, " +
                    "hasSemester=${row.hasSemester})",
                row.expected,
                shouldHighlightToday(row.displayWeek, row.currentWeek, row.hasSemester),
            )
        }
    }

    // ─────────────── ② 病灶的两条出路各自都得堵上 ───────────────

    /**
     * 假期里用户手动翻到第 7 周（[WeekGrid] 那一侧此时给的是空网格、顶栏写着「第7周（浏览）」）：
     * 改前的析取支 `|| currentWeek == null` 就是为这一档兜底的，它把胶囊扣在**第 7 周**同星期几
     * 那一格上，而今天压根不在第 7 周里。这一格与上面 null==null 那一格是两条独立出路。
     */
    @Test
    fun `假期里手动翻到某周 也不许把那一周的某天说成今天`() {
        assertFalse(shouldHighlightToday(displayWeek = 7, currentWeek = null, hasSemester = true))
        assertFalse(shouldHighlightToday(displayWeek = 1, currentWeek = null, hasSemester = true))
        assertFalse(shouldHighlightToday(displayWeek = 20, currentWeek = null, hasSemester = true))
    }

    /**
     * 病灶的另一条出路：`isBrowsingCurrentWeek = displayWeek == currentWeek` 在两个 null 上
     * 自己就成立了，不需要那枚析取支也放行。所以光删掉 `|| currentWeek == null` 不够，
     * 「当前教学周未知」这一档必须由内核单独否掉。
     */
    @Test
    fun `两个 null 相等不是「正在浏览本周」的证据`() {
        assertTrue(
            "改前正是这一格让 isBrowsingCurrentWeek 自己为真：先把它钉住，再核新判据为假",
            legacyHighlightToday(null, null),
        )
        assertFalse(shouldHighlightToday(null, null, hasSemester = true))
    }

    // ─────────────── ③ 无学期那一档是刻意留的方向感 ───────────────

    /**
     * 没有学期时 `weekStartDate` 就是 null ⇒ [DayHeader] 的 `cellDate` 全为 null，表头只印星期名
     * 不印日期，胶囊断言的只是"今天星期几"，与第几周无关 ⇒ 不构成谎话。
     * 把这一档一起关掉会让人在没设学期时连"今天排在第几列"都找不到，本卡明确不许。
     */
    @Test
    fun `没有学期时方向感留着 与各枚周号无关`() {
        for (displayWeek in weeks) {
            for (currentWeek in weeks) {
                assertTrue(
                    "无学期这一档 displayWeek=$displayWeek / currentWeek=$currentWeek 必须仍亮：" +
                        "表头此时不印任何日期，胶囊只说今天星期几",
                    shouldHighlightToday(displayWeek, currentWeek, hasSemester = false),
                )
            }
        }
    }

    // ─────────────── ④ 不许顺手放宽：逐格比改前 ───────────────

    @Test
    fun `正常当周与浏览往周这两档与改前逐格同值`() {
        for (displayWeek in weeks) {
            for (currentWeek in weeks) {
                if (currentWeek == null) continue
                assertEquals(
                    "currentWeek=$currentWeek 非 null 的那些格子改前改后必须同值（本卡只削会说谎的格子）：" +
                        "displayWeek=$displayWeek",
                    legacyHighlightToday(displayWeek, currentWeek),
                    shouldHighlightToday(displayWeek, currentWeek, hasSemester = true),
                )
            }
        }
    }

    /**
     * 「只朝窄收」的实数版。格子按本仓的一条事实筛掉不可达的那些：`currentWeek` 非 null 的前提
     * 是有学期（ScheduleViewModel 只在 `semester?.startLocalDate` 上算它），
     * 所以 hasSemester=false 而 currentWeek 非 null 的 8 格不进表。
     * 在剩下的 20 格上：新判据为真的每一格改前也必须为真（一格不许加），
     * 而两者不等的格子**只许**是「学期在、当前教学周取不到」那一族 4 格 —— 正是会说谎的那几格。
     * 翻分支式的假收窄（把某一档换成 true）会露在 widened 那一断言上。
     */
    @Test
    fun `新判据是改前判据的严格子集 且只削会说谎的那几格`() {
        val narrowed = mutableListOf<String>()
        val widened = mutableListOf<String>()
        var reachable = 0
        for (hasSemester in listOf(false, true)) {
            for (displayWeek in weeks) {
                for (currentWeek in weeks) {
                    if (currentWeek != null && !hasSemester) continue
                    reachable++
                    val before = legacyHighlightToday(displayWeek, currentWeek)
                    val after = shouldHighlightToday(displayWeek, currentWeek, hasSemester)
                    val cell = "hasSemester=$hasSemester displayWeek=$displayWeek currentWeek=$currentWeek"
                    if (after && !before) widened += cell
                    if (before && !after) narrowed += cell
                }
            }
        }
        assertEquals("可达格子枚数（改这张尺先确认表本身没被换小）", 20, reachable)
        assertEquals("新判据比改前更宽（一格都不许加）：${widened.joinToString(" | ")}", emptyList<String>(), widened)
        assertEquals(
            "削掉的格子枚数不是 4：${narrowed.joinToString(" | ")}",
            4,
            narrowed.size,
        )
        assertTrue(
            "削掉的格子里混进了 currentWeek 非 null 的那一档就不是本卡的账：${narrowed.joinToString(" | ")}",
            narrowed.all { it.contains("currentWeek=null") && it.contains("hasSemester=true") },
        )
    }

    // ─────────────── ⑤ 第 1 周本身不许被当成特殊档 ───────────────

    /**
     * 修法不是"第 1 周不亮"：病灶在于**假期里把第 1 周的日期当今天**，不是第 1 周本身。
     * 真的在第 1 周时（currentWeek=1）表头印的就是本周的日期，胶囊必须照亮 ——
     * 谁要是靠"见到 1 就关"来交这张卡，这一格先红。
     */
    @Test
    fun `学期第 1 周正常当周照样亮`() {
        assertTrue(shouldHighlightToday(1, 1, hasSemester = true))
        assertFalse(shouldHighlightToday(1, null, hasSemester = true))
        assertFalse(shouldHighlightToday(null, 1, hasSemester = true))
    }

    // ─────────────── ⑥ 与同屏顶栏那句同源 ───────────────

    /**
     * 病灶的判据本体就是"同一屏两处各说各话"，所以这张表直接拿顶栏那句 [weekHeadline] 对：
     * 胶囊亮 ⇔ 顶栏说的是「第N周」（本周）或「未设置学期」（表头此时不印日期）；
     * 顶栏说「假期中」「第N周（浏览）」的任何一格，胶囊都不许亮。
     */
    @Test
    fun `表头胶囊与顶栏那句周次标题永不打架`() {
        for (hasSemester in listOf(false, true)) {
            for (displayWeek in weeks) {
                for (currentWeek in weeks) {
                    val headline = weekHeadline(hasSemester, displayWeek, currentWeek)
                    val highlight = shouldHighlightToday(displayWeek, currentWeek, hasSemester)
                    val honest = headline == "未设置学期" || headline.matches(Regex("""第\d+周"""))
                    assertEquals(
                        "顶栏写着「$headline」时表头胶囊亮=$highlight，两处必须同源（" +
                            "hasSemester=$hasSemester displayWeek=$displayWeek currentWeek=$currentWeek）",
                        honest,
                        highlight,
                    )
                    if (headline == "假期中") {
                        assertFalse("顶栏说「假期中」而表头把某一天标成今天 —— 病灶本体", highlight)
                    }
                }
            }
        }
    }
}
