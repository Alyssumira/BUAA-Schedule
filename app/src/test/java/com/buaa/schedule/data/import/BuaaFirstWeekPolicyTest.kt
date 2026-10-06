package com.buaa.schedule.data.import

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T193①：把「`getTermWeeks` 那一枚才是第 1 周」钉成表驱动单测（纯 JVM、零 Robolectric、零时钟）。
 *
 * 判据本体在 `data/import/BuaaFirstWeekPolicy.kt` 的 `resolveBuaaFirstWeek`。病灶是
 * `BuaaInPageFetcher.kt:271` 那一行：注释承诺两条判据（`serialNumber 最小` / `startDate 最早`），
 * 代码只有第一条，而 `?: weeks.first()` 是不可达支 —— 真正会走到的退化是**所有** `serialNumber`
 * 都是 null 时 `minByOrNull` 的并列语义（每行都算成 `Int.MAX_VALUE` ⇒ 键全并列 ⇒ 回第一个，
 * 也就是**列表首行**）。教务只是没给序号，旧代码却把首行当第 1 周用。
 *
 * 每一格把「为什么」写进 assertEquals 的消息里（本仓口径），失败消息同时把实到的结论与那一档的
 * `detail` 念出来。
 *
 * 两侧都有格子：
 * - 朝**宽**扭一次（把判据 2 末尾的交叉核对整段摘掉 ⇒ 序号与日期分家时"信序号那条"）⇒
 *   [交叉核对那一档] 与 [判据 2 的分家格] 红；
 * - 朝**窄**扭一次（判据 3 改成「全 null ⇒ Undetermined」⇒ 不肯走注释承诺的第二条）⇒
 *   [病灶那一档] 与 [全 null 的 Resolved 格] 红。
 *
 * 档位如实写明：这里钉的是**判据**。"调用点有没有真的读这一枚内核"由静态守卫
 * `BuaaFirstWeekPolicyGuardTest` 钉；"教务页面真返回一批没号的行时长什么样"归编排者装机。
 */
class BuaaFirstWeekPolicyTest {

    // ─────────────── ① 判据表：挑得出 / 挑不出，一格一笔 ───────────────

    @Test
    fun `判据表 每一格都按预期给出结论`() {
        for (cell in table) {
            val actual = resolveBuaaFirstWeek(cell.rows)
            assertEquals(
                "格子「${cell.name}」输入 = ${cell.rows.joinToString(prefix = "[", postfix = "]") { describeRow(it) }} " +
                    "⇒ 期望 ${cell.expected}。实际得到 ${verdict(actual)} —— ${cell.why}\n" +
                    "这一格红了说明判据朝${cell.mutantDirection}的方向跑掉了",
                cell.expected,
                verdict(actual),
            )
        }
    }

    /**
     * 病灶那一档单独钉一次，把「改前的代码在这里给列表首行」写在消息里。
     *
     * 这一格就是「朝窄」那一扭的靶子：判据 3 若被改成「全 null 一律 Undetermined」，
     * 注释承诺的第二条判据就又没人实现了（改回去只是把病灶换成"这一档干脆不导入"，
     * 比修好差 —— 全 null 的返回体是**真数据**， earliest 那条本来就能唯一定出第 1 周）。
     */
    @Test
    fun `全 null 序号而列表首行是第 8 周时必须挑到最早那一枚`() {
        val rows = listOf(
            BuaaWeekRow(null, "2026-10-26 00:00:00"), // 列表首行：其实是第 8 周
            BuaaWeekRow(null, "2026-11-02 00:00:00"),
            BuaaWeekRow(null, "2026-09-07 00:00:00"), // 第 1 周在这一行的 startDate 上
            BuaaWeekRow(null, "2026-09-14 00:00:00"),
        )
        assertEquals(
            "所有 serialNumber 都是 null 时，判据走注释承诺的第二条（startDate 最早），" +
                "必须挑出「2026-09-07」；实际得到 ${describeVerdict(rows)}。" +
                "改前的代码在这一档回的是**列表首行**「2026-10-26」（minByOrNull 键全并列时取第一个），" +
                "而那一枚串合法可 parse，所以首次导入的 resolveBuaaSemesterStart 与静默刷新的 " +
                "parse→mondayOf 链都拦不住它 —— 整张课表的日期与周次编号一起往后挪三周",
            "Resolved(2026-09-07, EarliestStartDate)",
            verdict(resolveBuaaFirstWeek(rows)),
        )
    }

    /** 交叉核对那一档单独钉一次，它是「朝宽」那一扭的靶子（摘掉整段就只剩一条来源） */
    @Test
    fun `序号那条与日期那条分家时不许只信一条`() {
        val rows = listOf(
            BuaaWeekRow(1, "2026-09-21 00:00:00"), // 报着「我是第 1 周」，日期却比第 3 周还晚
            BuaaWeekRow(2, "2026-09-14 00:00:00"),
            BuaaWeekRow(3, "2026-09-07 00:00:00"),
        )
        val actual = resolveBuaaFirstWeek(rows)
        assertEquals(
            "serialNumber=1 那行的日期「2026-09-21」比候选里最早的一枚「2026-09-07」晚 ⇒ 两条来源分家，" +
                "必须判 Undetermined(SourcesDiverge)；实际得到 ${verdict(actual)}。" +
                "摘掉交叉核对（朝宽那一扭）会让这里回 Resolved(2026-09-21, SerialNumber) —— 那正是" +
                "「合法但错周」的串：两路下游都只认串合不合法，整表偏移三周而用户读不出来",
            "Undetermined(SourcesDiverge)",
            verdict(actual),
        )
        val detail = (actual as BuaaFirstWeek.Undetermined).detail
        assertTrue(
            "分家那一档的 detail 必须把两条来源各自的取值都念出来（日志要能看出是哪一档）。实到 $detail",
            detail.contains("2026-09-21") && detail.contains("2026-09-07"),
        )
    }

    // ─────────────── ② 两侧的整片站住 ───────────────

    /** 挑不出来的那一侧必须整片站住：这一组是"朝宽"那一扭的靶子 */
    @Test
    fun `挑不出第 1 周的那些档一律不许交出一枚日期`() {
        for (cell in table.filter { it.expected.startsWith("Undetermined") }) {
            val actual = resolveBuaaFirstWeek(cell.rows)
            assertEquals(
                "「${cell.name}」这一档必须判成 Undetermined，实际得到 ${verdict(actual)}：红了说明判据变宽了 —— " +
                    "要么并列那两行开始被「取第一个」静默挑走（就是病灶的 minByOrNull 并列语义），" +
                    "要么序号与日期分家时开始只信一条。两条都会把一枚「合法但错周」的串交给下游，" +
                    "而下游两路只认串本身合不合法",
                cell.expected,
                verdict(actual),
            )
        }
    }

    /** 挑得出来的那一侧必须整片站住：这一组是"朝窄"那一扭的靶子 */
    @Test
    fun `两条判据各自能定出唯一一枚时一律照收 不许加行数下限`() {
        for (cell in table.filter { it.expected.startsWith("Resolved") }) {
            val actual = resolveBuaaFirstWeek(cell.rows)
            assertEquals(
                "「${cell.name}」这一档应当收成 ${cell.expected}，实际 ${verdict(actual)}。" +
                    "红了说明判据变严了：要么有人把「候选全 null ⇒ 一律 Undetermined」写了回来" +
                    "（那是把注释承诺的第二条判据再次废掉），要么给输入加了「至少两行」这类" +
                    "本卡没有的额外要求（单行学期数据是合法输入，不是畸形输入）",
                cell.expected,
                verdict(actual),
            )
        }
    }

    // ─────────────── ③ 返回串的原样契约 ───────────────

    /** 判据 4：交出去的那枚串是 `trim().substringBefore(" ")` 的原样，不 parse、不归一、不读时钟 */
    @Test
    fun `返回串只裁掉时间尾巴 不认格式也不归一成周一`() {
        val withTail = resolveBuaaFirstWeek(listOf(BuaaWeekRow(1, "  2026-09-07 00:00:00  ")))
        assertEquals(
            "带时间尾巴与首尾空白的「  2026-09-07 00:00:00  」必须原样裁成「2026-09-07」，" +
                "实到 ${verdict(withTail)}：内核里不许 parse（parse = 多一条「抢救格式」的路）、" +
                "不许 mondayOf 归一（那是 T180 内核的活，两判各管一段）",
            "Resolved(2026-09-07, SerialNumber)",
            verdict(withTail),
        )
        val produced = (withTail as BuaaFirstWeek.Resolved).startDate
        assertFalse(
            "返回串里不许带空格之后的部分（实到「$produced」）",
            produced.contains(' '),
        )

        // 非 ISO 的串照原样交出去：本卡不判合法性，合法性是 resolveBuaaSemesterStart 那一判的事
        val slashed = resolveBuaaFirstWeek(listOf(BuaaWeekRow(1, "2026/09/07")))
        assertEquals(
            "教务写成斜杠格式时本内核照样按串序挑并把原串交出去（实到 ${verdict(slashed)}）：" +
                "「认不认得这枚串」由下游 T180 的 resolveBuaaSemesterStart 判，它认不出来就不动学期。" +
                "在这里加格式抢救就是「猜」的入口",
            "Resolved(2026/09/07, SerialNumber)",
            verdict(slashed),
        )
    }

    /** 幂等：把 Resolved 的产物当唯一行的 startDate 再喂一次，必须得到同一枚结果 */
    @Test
    fun `对 Resolved 的产物再喂一次内核 得到同一枚结果`() {
        for (cell in table.filter { it.expected.startsWith("Resolved") }) {
            val once = resolveBuaaFirstWeek(cell.rows) as BuaaFirstWeek.Resolved
            val twice = resolveBuaaFirstWeek(listOf(BuaaWeekRow(null, once.startDate)))
            assertEquals(
                "格子「${cell.name}」第一次得到 ${verdict(once)}，把它交出的那枚串当唯一一行再喂一次" +
                    "必须还是同一枚串（实到 ${verdict(twice)}）：裁剪与比较键同构的话这一步不该漂移，" +
                    "漂移了说明返回的串与比较用的键不是同一枚",
                once.startDate,
                (twice as? BuaaFirstWeek.Resolved)?.startDate,
            )
        }
    }

    /** 每一档 Undetermined 都必须说实话：detail 不许是空串（报错要能看出是哪一档） */
    @Test
    fun `挑不出来的每一档都带得出原因的 detail`() {
        for (cell in table.filter { it.expected.startsWith("Undetermined") }) {
            val actual = resolveBuaaFirstWeek(cell.rows)
            assertTrue(
                "「${cell.name}」这一档的 detail 不许为空（日志里要念得出为什么不可信）。实到 " +
                    (actual as? BuaaFirstWeek.Undetermined)?.detail,
                actual is BuaaFirstWeek.Undetermined && actual.detail.isNotBlank(),
            )
            assertTrue(
                "「${cell.name}」的 reason 与格子期望一致（实到 " +
                    (actual as? BuaaFirstWeek.Undetermined)?.reason,
                actual is BuaaFirstWeek.Undetermined &&
                    cell.expected == "Undetermined(${actual.reason})",
            )
        }
    }

    // ─────────────── 表体 ───────────────

    private data class Cell(
        val name: String,
        val rows: List<BuaaWeekRow>,
        val expected: String,
        val why: String,
        val mutantDirection: String,
    )

    private val table: List<Cell> = listOf(
        Cell(
            name = "序号齐全且与日期序一致",
            rows = listOf(
                BuaaWeekRow(1, "2026-09-07 00:00:00"),
                BuaaWeekRow(2, "2026-09-14 00:00:00"),
                BuaaWeekRow(3, "2026-09-21 00:00:00"),
            ),
            expected = "Resolved(2026-09-07, SerialNumber)",
            why = "两条判据同指一枚，用序号那条（via 留取证用）。",
            mutantDirection = "宽或严（两侧都挪得动）",
        ),
        Cell(
            name = "序号齐全而列表是乱序（首行是第 3 周）",
            rows = listOf(
                BuaaWeekRow(3, "2026-09-21 00:00:00"),
                BuaaWeekRow(1, "2026-09-07 00:00:00"),
                BuaaWeekRow(2, "2026-09-14 00:00:00"),
            ),
            expected = "Resolved(2026-09-07, SerialNumber)",
            why = "教务按当前周起排是常见返回序；序号那条必须与列表次序无关。",
            mutantDirection = "严（挑不回最小序号那一枚就会漂）",
        ),
        Cell(
            name = "序号 1 的行日期比序号 3 的行晚（两条来源分家）",
            rows = listOf(
                BuaaWeekRow(1, "2026-09-21 00:00:00"),
                BuaaWeekRow(2, "2026-09-14 00:00:00"),
                BuaaWeekRow(3, "2026-09-07 00:00:00"),
            ),
            expected = "Undetermined(SourcesDiverge)",
            why = "交叉核对那一支：序号说 1、日期说另一枚，两条来源分家 = 不可信。",
            mutantDirection = "宽",
        ),
        Cell(
            name = "最小序号被两行并列占着",
            rows = listOf(
                BuaaWeekRow(1, "2026-09-07 00:00:00"),
                BuaaWeekRow(1, "2026-09-14 00:00:00"),
                BuaaWeekRow(2, "2026-09-21 00:00:00"),
            ),
            expected = "Undetermined(AmbiguousSerialNumber)",
            why = "两行都报「我是第 1 周」就是数据坏了；minByOrNull 的「取第一个」正是本卡要消灭的猜法。",
            mutantDirection = "宽",
        ),
        Cell(
            name = "有号有没号混着：没号的行不当第 1 周",
            rows = listOf(
                BuaaWeekRow(null, "2026-09-28 00:00:00"),
                BuaaWeekRow(2, "2026-09-14 00:00:00"),
                BuaaWeekRow(1, "2026-09-07 00:00:00"),
            ),
            expected = "Resolved(2026-09-07, SerialNumber)",
            why = "延续今天 `?: Int.MAX_VALUE` 的取值语义：带号的行里挑最小，没号的行赢不了。",
            mutantDirection = "宽",
        ),
        Cell(
            name = "没号的行带着更早的日期（交叉核对也拦这一档）",
            rows = listOf(
                BuaaWeekRow(null, "2026-08-31 00:00:00"),
                BuaaWeekRow(1, "2026-09-07 00:00:00"),
            ),
            expected = "Undetermined(SourcesDiverge)",
            why = "候选里最早的一枚落在没号的行上 ⇒ 两条来源分家；卡面判据 2 的「同一批候选」" +
                "含没号的行，所以这里不许只信序号那条。",
            mutantDirection = "宽",
        ),
        Cell(
            name = "全 null 序号、列表首行是第 8 周（病灶本身）",
            rows = listOf(
                BuaaWeekRow(null, "2026-10-26 00:00:00"),
                BuaaWeekRow(null, "2026-09-07 00:00:00"),
                BuaaWeekRow(null, "2026-09-14 00:00:00"),
            ),
            expected = "Resolved(2026-09-07, EarliestStartDate)",
            why = "改前的代码在这一档回列表首行「2026-10-26」；注释承诺的第二条判据在这里必须真的被实现。",
            mutantDirection = "窄",
        ),
        Cell(
            name = "全 null 且最早那枚并列两枚不同串",
            rows = listOf(
                BuaaWeekRow(null, "2026-09-07 08:00:00"),
                BuaaWeekRow(null, "2026-09-07 00:00:00"),
                BuaaWeekRow(null, "2026-09-14 00:00:00"),
            ),
            expected = "Undetermined(AmbiguousEarliestStartDate)",
            why = "两枚不同串（时间尾巴不同）落在同一个最早日期上 ⇒ 「最早」不唯一，不许取第一个。",
            mutantDirection = "宽",
        ),
        Cell(
            name = "候选全空（null / 空串 / 纯空白）",
            rows = listOf(BuaaWeekRow(1, null), BuaaWeekRow(2, ""), BuaaWeekRow(3, "   ")),
            expected = "Undetermined(NoCandidateStartDate)",
            why = "「没给日期」不是「给了个能用的日期」；空白串与空串都不进候选。",
            mutantDirection = "宽",
        ),
        Cell(
            name = "空列表（内核不靠调用点保证非空）",
            rows = emptyList(),
            expected = "Undetermined(NoCandidateStartDate)",
            why = "调用点今天确实在上一行就早退了，但内核不许把那条非空前提当成自己的判据 —— " +
                "本卡登记的三条事实里第 2 条正是「不可达支掩盖真退化」。",
            mutantDirection = "宽",
        ),
        Cell(
            name = "单行列表（带号）",
            rows = listOf(BuaaWeekRow(7, "2026-09-07 00:00:00")),
            expected = "Resolved(2026-09-07, SerialNumber)",
            why = "单行是合法输入：不许造「必须 ≥2 行」这种本卡没有的额外要求。",
            mutantDirection = "严",
        ),
        Cell(
            name = "单行列表（没号）",
            rows = listOf(BuaaWeekRow(null, "2026-09-07 00:00:00")),
            expected = "Resolved(2026-09-07, EarliestStartDate)",
            why = "同上，只是走注释承诺的第二条判据。",
            mutantDirection = "严",
        ),
        Cell(
            name = "同一枚日期串并列两行（全 null）",
            rows = listOf(
                BuaaWeekRow(null, "2026-09-07 00:00:00"),
                BuaaWeekRow(null, "2026-09-07 00:00:00"),
            ),
            expected = "Undetermined(AmbiguousEarliestStartDate)",
            why = "两行报同一个开学日 = 周次数据本身重复，totalWeeks 已经不可信，这里不猜。",
            mutantDirection = "宽",
        ),
    )

    // ─────────────── 小工具 ───────────────

    /** 结论折成一枚可比对的串：detail 只用于日志，不参与判据比对（否则每格都要抄一遍长句） */
    private fun verdict(week: BuaaFirstWeek): String = when (week) {
        is BuaaFirstWeek.Resolved -> "Resolved(${week.startDate}, ${week.via})"
        is BuaaFirstWeek.Undetermined -> "Undetermined(${week.reason})"
    }

    private fun describeRow(row: BuaaWeekRow): String =
        "(${row.serialNumber}, ${row.startDate?.let { "「$it」" } ?: "null"})"

    private fun describeVerdict(rows: List<BuaaWeekRow>): String {
        val actual = resolveBuaaFirstWeek(rows)
        return verdict(actual) + (actual as? BuaaFirstWeek.Undetermined)?.let { "（detail=${it.detail}）" }.orEmpty()
    }
}
