package com.buaa.schedule.data.import

import com.buaa.schedule.domain.model.mondayOfWeekAnchor
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T180：把「教务报上来的首周日期认不认得出来」那一判钉成表驱动单测（纯 JVM、零时钟注入面）。
 *
 * 判据本体在 `data/import/BuaaSemesterStartPolicy.kt` 的 `resolveBuaaSemesterStart`。
 * 病灶是旧 `BuaaLoginScreen` 那一段（`rawStart` / `startDate` 两枚 val）把
 * `runCatching { mondayOf(LocalDate.parse(rawStart)) }.getOrDefault(rawStart)` 的**兜底值**
 * 写成那一枚 parse 失败的原串 ⇒ 坏日期原样进 `Semester.startDate` 落库 ⇒
 * `Semester.startLocalDate` 此后恒 null（周课表空网格、提醒链 `cancelAll`、组件停在「假期中」、
 * 设置页念出并回写这串垃圾），且全程一句报错都没有。
 *
 * 档位（如实写明）：`:app` 的 JVM 测试面只有 `junit`，没有 Robolectric、没有能在 JVM 上跑
 * Room 的缝，所以本文件钉的是**判据**；"调用点有没有真的按这一判走「不动学期」那一路"由
 * 源码核对守卫 `BuaaSemesterStartWiringGuardTest` 钉；"落库一行再看周课表画不画得出来"
 * 那一档**没做**（红线零设备）。
 *
 * 表 ① 满足"两侧都要有格子"：
 * - 朝**松**扭一次（把 `Undetermined` 换成 `Resolved(今天所在周的周一)`，或干脆
 *   `?: raw` 把原串交回去 —— 就是旧形状）⇒ `认不出来的四档一律给「不动学期」信号` 与
 *   表 ① 里 `null` / 空串 / `2026/9/7` / BOM 那几格红；
 * - 朝**严**扭一次（`Resolved` 那侧全判成认不出来 / 去掉 `mondayOfWeekAnchor` 归一 /
 *   给日期加一枚"离今天太远就拒收"的合理性窗口）⇒ 表 ① 里 ISO 那几格、周日归一那格、
 *   以及 `认得出的 ISO 一律照收` 那一格红。
 */
class BuaaSemesterStartPolicyTest {

    // ─────────────── ① 判据表：认得出 / 认不出来，一格一笔 ───────────────

    @Test
    fun `判据表 每一格都按预期给出结论`() {
        for (cell in table) {
            val actual = resolveBuaaSemesterStart(cell.raw)
            assertEquals(
                "格子「${cell.name}」输入 = ${cell.raw?.let { "「$it」" } ?: "null"} ⇒ 期望 ${cell.expectedLabel}。" +
                    "实际得到 ${describe(actual)} —— ${cell.why}\n" +
                    "这一格红了说明判据朝${cell.mutantDirection}的方向跑掉了",
                cell.expected,
                actual,
            )
        }
    }

    /** 认不出来的那一侧必须整片站住：这一组就是"朝松"那一扭的靶子 */
    @Test
    fun `认不出来的四档一律给不动学期信号`() {
        for (cell in table.filter { it.expected is BuaaSemesterStart.Undetermined }) {
            val actual = resolveBuaaSemesterStart(cell.raw)
            assertEquals(
                "「${cell.name}」这一档（输入 ${cell.raw?.let { "「$it」" } ?: "null"}）必须判成 Undetermined，" +
                    "实际得到 ${describe(actual)}：红了说明判据变松了 —— 要么把 parse 失败的原串又当值交了出去" +
                    "（旧形状 `getOrDefault(rawStart)`），要么开始拿时钟猜「今天所在周的周一」。" +
                    "两条路都会把一个锚点写进 Semester.startDate，而它读出来是 null",
                BuaaSemesterStart.Undetermined(cell.raw),
                actual,
            )
        }
    }

    /** 认得出的那一侧必须整片站住：这一组就是"朝严"那一扭的靶子 */
    @Test
    fun `认得出的 ISO 一律照收 不许加日期合理性窗口`() {
        val resolvable = listOf(
            // 正常开学周
            "2026-09-07" to "2026-09-07",
            // 历史学期（补录上去年的课表是正常操作：起点在过去不是拒收的理由）
            "2024-02-26" to "2024-02-26",
            // 提前导入下一学期（今天离开学还有好几周，正是"不许拿今天猜"那一档的用例）
            "2030-09-02" to "2030-09-02",
            // 周日/周六报到：归一到该自然周的周一
            "2026-09-06" to "2026-08-31",
            "2026-09-05" to "2026-08-31",
        )
        for ((raw, monday) in resolvable) {
            val actual = resolveBuaaSemesterStart(raw)
            assertEquals(
                "认得出的 ISO「$raw」应当收成 Resolved($monday)，实际 ${describe(actual)}。" +
                    "红了说明判据变严了：要么有人给它加了一枚「离今天多远就拒收」的合理性窗口" +
                    "（那会打掉补录历史学期与提前导入下一学期两档合法用例），" +
                    "要么 parse 的口径被改动、认不出 ISO 了",
                BuaaSemesterStart.Resolved(LocalDate.parse(monday)),
                actual,
            )
        }
    }

    // ─────────────── ② 归一：全仓只有 mondayOfWeekAnchor 那一份公式 ───────────────

    /**
     * 归一是必须的：教务 `getTermWeeks` 第 1 周的 `startDate` 偶尔是报到日（周日/周六），
     * 不归一会让首次导入的整张表偏移一到六天，而下一次静默刷新又把它挪回来。
     */
    @Test
    fun `结论一律是周一 与全仓那一枚归一公式同源`() {
        for (raw in listOf("2026-09-07", "2026-09-06", "2026-09-05", "2026-09-13", "2024-02-25")) {
            val actual = resolveBuaaSemesterStart(raw)
            val monday = (actual as? BuaaSemesterStart.Resolved)?.monday
            assertEquals(
                "「$raw」的结论应当等于 mondayOfWeekAnchor(该日期) —— 判据里那一枚归一被拆掉的话，" +
                    "实际会停在 ${describe(actual)}",
                mondayOfWeekAnchor(LocalDate.parse(raw)),
                monday,
            )
            assertEquals(
                "结论必须落在周一（ISO dayOfWeek.value = 1），「$raw」实到 $monday（" +
                    "${monday?.dayOfWeek}）",
                1,
                monday?.dayOfWeek?.value,
            )
        }
    }

    /** 周一日期上归一是幂等的：把结论再喂回判据，必须一个字都不变 */
    @Test
    fun `归一是幂等的 结论再喂回去不自搬`() {
        for (raw in listOf("2026-09-07", "2026-09-06", "2024-02-25")) {
            val first = resolveBuaaSemesterStart(raw) as BuaaSemesterStart.Resolved
            val second = resolveBuaaSemesterStart(first.monday.toString())
            assertEquals(
                "「$raw」归一出来的周一「${first.monday}」再喂回去必须还是它自己（实到 ${describe(second)}）：" +
                    "不归一的那一版会每喂一次就往回挪几天，静默刷新与首次导入因此各算一套锚点",
                first,
                second,
            )
        }
    }

    // ─────────────── ③ Undetermined 那一枚：只带原文，不带任何日期 ───────────────

    @Test
    fun `认不出来时只带得出原文 不许折算成任何一个日期`() {
        for (raw in listOf(null, "", "   ", "2026/9/7", "2026年9月7日", "﻿2026-09-07", "2026-02-30", "2026-09")) {
            val actual = resolveBuaaSemesterStart(raw)
            assertEquals(
                "输入 ${raw?.let { "「$it」" } ?: "null"} 的结论必须**不是** Resolved（实到 ${describe(actual)}）：" +
                    "Undetermined 一旦被折算成任何一个日期，那个日期就进了 Semester.startDate，" +
                    "而 Semester.startLocalDate 读它是 null —— 本卡修的正是这一条",
                false,
                actual is BuaaSemesterStart.Resolved,
            )
            assertEquals(
                "输入 ${raw?.let { "「$it」" } ?: "null"} 的 Undetermined 必须原样带得出 raw（给用户的报错要用；" +
                    "实到 ${describe(actual)}）",
                BuaaSemesterStart.Undetermined(raw),
                actual,
            )
        }
    }

    /** 判据本身不读时钟：同一枚输入连判两次必须同一个结论（没有任何隐藏状态） */
    @Test
    fun `判据不读时钟 同一输入连判两次同结论`() {
        for (cell in table) {
            assertEquals(
                "「${cell.name}」两次判定不一致 = 判据里有状态（时钟或随机）：那会让「不动学期」这一判" +
                    "在同一次导入里翻面，用户重试时读到的是另一套结论",
                resolveBuaaSemesterStart(cell.raw),
                resolveBuaaSemesterStart(cell.raw),
            )
        }
    }

    // ─────────────── ④ trim 只清首尾空白，不清洗内容 ───────────────

    @Test
    fun `trim 只清首尾空白 不替教务把坏串洗成好串`() {
        assertEquals(
            "首尾空白不参与判定（JSON 里带个尾巴是常事），「 2026-09-07 」实到 " +
                describe(resolveBuaaSemesterStart(" 2026-09-07 ")),
            BuaaSemesterStart.Resolved(LocalDate.parse("2026-09-07")),
            resolveBuaaSemesterStart(" 2026-09-07 "),
        )
        // 中间的空格属于内容：不许 substring(0, 10) 那一等"抢救"
        assertEquals(
            "「2026 - 09 - 07」这种中间掺空白的串必须算认不出来（抢救 = 猜）",
            BuaaSemesterStart.Undetermined("2026 - 09 - 07"),
            resolveBuaaSemesterStart("2026 - 09 - 07"),
        )
        assertEquals(
            "纯空白「    」与空串同一档（旧代码里它连 `?:` 那一手都不触发，直接把空白串写进库）",
            BuaaSemesterStart.Undetermined("    "),
            resolveBuaaSemesterStart("    "),
        )
    }

    private fun describe(value: BuaaSemesterStart): String = when (value) {
        is BuaaSemesterStart.Resolved -> "Resolved(${value.monday})"
        is BuaaSemesterStart.Undetermined -> "Undetermined(raw=${value.raw?.let { "「$it」" } ?: "null"})"
    }

    private companion object {

        /**
         * 卡面要求的四档（正常 ISO / 非 ISO `2026/9/7` / null / 空串）全在，其余是同一判据
         * 在真实坏值面上的补格：中文年月、BOM、带时间尾巴、不存在的日期、纯空白 ——
         * 都是 `BuaaInPageFetcher` 那一路可能原样递上来的东西（它只做 `substringBefore(" ")`）。
         */
        val table: List<Cell> = listOf(
            Cell(
                name = "正常 ISO 周一",
                raw = "2026-09-07",
                expected = BuaaSemesterStart.Resolved(LocalDate.parse("2026-09-07")),
                why = "教务给的是标准 yyyy-MM-dd，收成它自己（已经在周一上，归一不动它）",
                mutantDirection = "更严的方向跑掉了（认得出的串被拒收）",
            ),
            Cell(
                name = "非 ISO 斜杠单位",
                raw = "2026/9/7",
                expected = BuaaSemesterStart.Undetermined("2026/9/7"),
                why = "LocalDate.parse 认不出斜杠单位 —— 旧代码在这就是把「2026/9/7」写进库的那一等坏值",
                mutantDirection = "更松的方向跑掉了（parse 失败的原串又被当成日期交了出去）",
            ),
            Cell(
                name = "首周 startDate 缺席",
                raw = null,
                expected = BuaaSemesterStart.Undetermined(null),
                why = "null 不是「可以猜」的理由：提前导入下一学期时今天所在的那一周根本不是学期",
                mutantDirection = "更松的方向跑掉了（开始拿时钟猜「今天所在周的周一」）",
            ),
            Cell(
                name = "空串",
                raw = "",
                expected = BuaaSemesterStart.Undetermined(""),
                why = "空串在旧代码里连 `?:` 那一手都不触发（它不是 null），parse 失败后原样落库，" +
                    "读出来恒 null —— 这一格钉的是旧形状里最隐蔽的那一档",
                mutantDirection = "更松的方向跑掉了（空串被当成合法日期）",
            ),
            Cell(
                name = "中文年月",
                raw = "2026年9月7日",
                expected = BuaaSemesterStart.Undetermined("2026年9月7日"),
                why = "教务页面偶尔是这种渲染后的人话，不是日期",
                mutantDirection = "更松的方向跑掉了（往「再宽松一点」的抢救格式滑）",
            ),
            Cell(
                name = "带 BOM",
                raw = "﻿2026-09-07",
                expected = BuaaSemesterStart.Undetermined("﻿2026-09-07"),
                why = "BOM 不是首尾空白，trim 清不掉它，parse 失败 ⇒ 老实说认不出来。" +
                    "把它洗掉属于「抢救式宽容」，本卡明令不许（要洗也得连中文一起洗，那条路没有尽头）",
                mutantDirection = "更松的方向跑掉了（开始清洗原文以凑成可解析串）",
            ),
            Cell(
                name = "带时间尾巴",
                raw = "2026-09-07 00:00:00",
                expected = BuaaSemesterStart.Undetermined("2026-09-07 00:00:00"),
                why = "剥时间那一手归上游（BuaaInPageFetcher 的 substringBefore(\" \")），" +
                    "内核只认 ISO 日期本体 —— 两把尺都来剥一遍就会有一处漏、一处多剥",
                mutantDirection = "更松的方向跑掉了（内核自己再剥一次时间）",
            ),
            Cell(
                name = "首尾空白",
                raw = " 2026-09-07 ",
                expected = BuaaSemesterStart.Resolved(LocalDate.parse("2026-09-07")),
                why = "空白不参与判定；这是 trim 的唯一职责，再往内容里动就算抢救",
                mutantDirection = "更严的方向跑掉了（连合法串都被拒）",
            ),
            Cell(
                name = "不存在的日期",
                raw = "2026-02-30",
                expected = BuaaSemesterStart.Undetermined("2026-02-30"),
                why = "形状像 ISO 但日历上没这一天，java.time 会抛；写进库读出来恒 null",
                mutantDirection = "更松的方向跑掉了（坏日期被收下来）",
            ),
            Cell(
                name = "只有年月",
                raw = "2026-09",
                expected = BuaaSemesterStart.Undetermined("2026-09"),
                why = "缺日期的串补一个「01」就是猜学期起点，直接拒收",
                mutantDirection = "更松的方向跑掉了（补位凑串）",
            ),
            Cell(
                name = "周日（报到日）",
                raw = "2026-09-06",
                expected = BuaaSemesterStart.Resolved(LocalDate.parse("2026-08-31")),
                why = "ISO 一周的最后一天要往前退 6 天；这正是 mondayOfWeekAnchor 那一枚归一的存在理由",
                mutantDirection = "更严/归一被拆掉的方向跑掉了（整表偏移一到六天）",
            ),
            Cell(
                name = "历史学期",
                raw = "2024-02-26",
                expected = BuaaSemesterStart.Resolved(LocalDate.parse("2024-02-26")),
                why = "补录往学期的课表是合法操作：判据不许拿「离今天太远」当拒收理由",
                mutantDirection = "更严的方向跑掉了（日期合理性窗口）",
            ),
        )
    }

    private data class Cell(
        val name: String,
        val raw: String?,
        val expected: BuaaSemesterStart,
        val why: String,
        val mutantDirection: String,
    ) {
        val expectedLabel: String
            get() = when (expected) {
                is BuaaSemesterStart.Resolved -> "Resolved(${expected.monday})"
                is BuaaSemesterStart.Undetermined -> "Undetermined"
            }
    }
}
