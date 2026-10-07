package com.buaa.schedule.ui.importing

import com.buaa.schedule.data.import.SemesterInfoReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T196④：把「开学日没有 ⇒ 那句用户可见的话」钉成 **两维表驱动**（纯 JVM、零 Robolectric、零时钟）。
 *
 * 映射本体在 `SemesterStartFailureCopy.kt` 的 `semesterStartFailureMessage(raw, reason)`。
 * 病是这一句今天只按「raw 有没有」分两等，把**五种不同的事**压成同一句「请稍后重试」：
 * 学期代码没过白名单（那一趟请求根本没发出去）、页面没答（重试之后仍然超时）、答了而一行周次都没给、
 * 答了而那份答读不出来、以及 T193 那一档（周次行拿到了而第 1 周判不出）。
 *
 * 两维就是卡面 §1-④ 那两句：**有没有拿到串**（[raw]）与**页面到底答没答**（[reason]）。
 * 每一格的期望值是**整句逐字**（本仓口径：文案表驱动只报「不等于另一档」等于没有守卫），
 * 「为什么是这一句」写在 `why` 里并进失败消息。
 *
 * 两侧都有靶子：
 * - 朝**宽**扭一次（把某两档的措辞并成同一句，例如让 `EmptyWeekList` 也说「页面根本没答」）⇒
 *   [distinct 那一格] 与那一格自己的逐字期望同时红；
 * - 朝**窄**扭一次（给 `SemesterInfoReason` 添一枚而这一张表没跟上）⇒ `when` 不收全，
 *   **编译当场红**（这一档不需要运行时格子，KDoc 里写明）；
 * - 接线扭一次（页面不再递 `info.missingReason`、只递 raw）⇒ 静态守卫
 *   `SemesterInfoReasonGuardTest` 红，见那里第 ③ 层。
 */
class SemesterStartFailureCopyTest {

    // ─────────────── ① 逐字表 ───────────────

    @Test
    fun `两维表每一格都逐字念出那一句`() {
        for (cell in table) {
            val actual = semesterStartFailureMessage(cell.raw, cell.reason)
            assertEquals(
                "格子「${cell.name}」输入 = raw ${cell.raw?.let { "「$it」" } ?: "null"} × " +
                    "reason ${cell.reason ?: "null（抓取这一侧没缺）"} ⇒ 期望逐字：\n" +
                    "  ${cell.expected}\n实际：\n  $actual\n为什么这一句：${cell.why}",
                cell.expected,
                actual,
            )
        }
    }

    // ─────────────── ② 两维各自都要在句子里留下痕迹 ───────────────

    /**
     * 「有没有拿到串」那一维：raw 缺失与 raw 有一枚串时，句子里那一格必须**不同**；
     * 空白串与 null 必须**同格**（`isNullOrBlank` 那一判的形状 —— 教务给一枚全空白的日期就是没给）。
     */
    @Test
    fun `有没有拿到串那一维在两等处给出两种话 空白串与 null 同一等`() {
        val missing = semesterStartFailureMessage(null, SemesterInfoReason.PageUnanswered)
        val blank = semesterStartFailureMessage("   ", SemesterInfoReason.PageUnanswered)
        val quoted = semesterStartFailureMessage("2026-09-07", null)
        assertEquals(
            "全空白串必须与 null 同一等（实到 blank=$blank / missing 同形状）。红了说明有人把 " +
                "`isNullOrBlank()` 换成 `== null` —— 那等于让教务一枚「三枚空格」的日期走进" +
                "「原样那枚串」那一档，用户读到一句括号里嵌着空白的破句",
            missing,
            blank,
        )
        assertTrue(
            "拿到串那一等必须把**原样那枚串**念给用户（取证就靠它，不加工、不抢救格式）。实到 $quoted",
            quoted.contains("「2026-09-07」"),
        )
        assertTrue(
            "没拿到串那一等必须说实话「缺失」，不许写成空引号或「未提供」之类的第三种话。实到 $missing",
            missing.contains("startDate = 缺失"),
        )
    }

    /**
     * 「页面答没答」那一维：五枚原因各一句，**互不相同** —— 这一格才是本卡的目的本身。
     *
     * 把任意两档并成同一句（朝宽那一扭）在这里红，且红的同时第 ① 层那一格也红（互为备份）。
     */
    @Test
    fun `五档原因给出五句互不相同的话 不许再压成同一句`() {
        val sentences = SemesterInfoReason.entries.map {
            semesterStartFailureMessage(null, it)
        }
        val distinct = sentences.distinct()
        assertEquals(
            "五枚原因必须各念各的（实到 " + distinct.size + " / " + sentences.size + " 枚不同）。" +
                "改前那一格的全部病灶就在这里：`firstWeekMonday = null` 背后五种事被压成一句" +
                "「请稍后重试」。并掉哪两档，就说明那两档在用户端又变成同一件事了：" +
                sentences.withIndex().joinToString(prefix = "\n  ", separator = "\n  ") {
                    "${it.index + 1}) ${it.value}"
                },
            SemesterInfoReason.entries.size,
            distinct.size,
        )
        // 「没答」那一族与「答了」那一族必须分得开：这是卡面点名的那一档混淆
        assertTrue(
            "「页面根本没答」那一档的句子必须带着它自己的说法（实到 " +
                sentences[SemesterInfoReason.entries.indexOf(SemesterInfoReason.PageUnanswered)] + "）",
            sentences[SemesterInfoReason.entries.indexOf(SemesterInfoReason.PageUnanswered)]
                .contains("页面根本没答"),
        )
        assertTrue(
            "「答了而一行周次都没给」不许复用「页面根本没答」那半句（实到 " +
                sentences[SemesterInfoReason.entries.indexOf(SemesterInfoReason.EmptyWeekList)] + "）",
            !sentences[SemesterInfoReason.entries.indexOf(SemesterInfoReason.EmptyWeekList)]
                .contains("页面根本没答"),
        )
    }

    // ─────────────── ③ 两头固定：那一问与那一尾 ───────────────

    /**
     * 七句共用**同一句开头与同一句收尾**：开头说清「没有可识别的开学日」，
     * 收尾说清「原课表一个字没动 + 请稍后重试」—— 那两句是分档之后仍然要留的实话。
     */
    @Test
    fun `每一句都以那一问开头 以那一尾收尾`() {
        val rows = table.map { it.raw to it.reason } +
            SemesterInfoReason.entries.map { null to (it as SemesterInfoReason?) }
        for ((raw, reason) in rows.distinct()) {
            val sentence = semesterStartFailureMessage(raw, reason)
            assertTrue(
                "「${raw ?: "null"} × ${reason ?: "null"}」那一句必须以「教务系统没有给出可识别的开学日期」" +
                    "开头（实到 $sentence）：分档不许把那一问换掉，用户先要知道结果",
                sentence.startsWith("教务系统没有给出可识别的开学日期"),
            )
            assertTrue(
                "那一句必须以「已保留原课表未做改动，请稍后重试」结尾（实到 $sentence）：" +
                    "这一句是 T180 那一路「闸门不是兜底」的凭据 —— 没写进去用户会以为课表已经被改了",
                sentence.endsWith("已保留原课表未做改动，请稍后重试"),
            )
        }
    }

    /** 全角括号逐句配平（`UserVisibleParenBalanceGuardTest` 那一把尺在源码层数的是字面量，这里数产物） */
    @Test
    fun `每一句的全角括号都自给自足`() {
        for (cell in table) {
            val sentence = semesterStartFailureMessage(cell.raw, cell.reason)
            assertEquals(
                "格子「${cell.name}」那一句的开闭「（）」枚数不等（实到 " +
                    sentence.count { it == '（' } + " / " + sentence.count { it == '）' } +
                    "）：$sentence —— 半句押开括号、下一句押闭括号是 T181② 那枚病的形状",
                sentence.count { it == '（' },
                sentence.count { it == '）' },
            )
        }
    }

    // ─────────────── ④ 反套套逻辑：表得有格子吃 ───────────────

    @Test
    fun `表盖满五档原因 格子数与档位都对得上`() {
        val covered = table.mapNotNull { it.reason }.toSet()
        val missed = SemesterInfoReason.entries.filter { it !in covered }
        assertTrue(
            "表必须覆盖 [SemesterInfoReason] 的每一枚（没覆盖到：" + missed.joinToString() +
                "）。添一枚原因而不给它格子，就等于让那一档重新落回「按 raw 分两等」那旧口径",
            missed.isEmpty(),
        )
        assertTrue(
            "表至少 9 格（实到 ${table.size}）：读到很小的数说明这张表什么都没吃 —— " +
                "五档 + null 两等 + 空白串那一等 + 正交组合至少九格",
            table.size >= 9,
        )
        assertTrue(
            "表里至少一枚 raw 非空白的格子（实到 " + table.count { !it.raw.isNullOrBlank() } +
                " 枚）：「拿到串而认不出」是本卡两维的第一维，全是 null 的表就退化成单维",
            table.any { !it.raw.isNullOrBlank() },
        )
        assertTrue(
            "`reason = null` 那一等至少两格（实到 " + table.count { it.reason == null } +
                " 枚）：它自己有「有串」与「按构造不可达的没串」两等，只钉一等就漏那一枚异常形状",
            table.count { it.reason == null } >= 2,
        )
    }

    // ─────────────── 表体 ───────────────

    private data class Cell(
        val name: String,
        val raw: String?,
        val reason: SemesterInfoReason?,
        val expected: String,
        val why: String,
    )

    private val table: List<Cell> = listOf(
        Cell(
            name = "拿到了串而 T180 认不出（抓取这一侧没缺）",
            raw = "2026-09-07",
            reason = null,
            expected = "教务系统没有给出可识别的开学日期（第 1 周 startDate = 「2026-09-07」；" +
                "教务答了，而这一枚日期认不出是哪个），已保留原课表未做改动，请稍后重试",
            why = "唯一一等「串拿到了」。原样念那枚串是取证的全部依据 —— 不加工、不抢救格式、" +
                "不猜周一（那是 resolveBuaaSemesterStart 那一判否掉的）。",
        ),
        Cell(
            name = "异常形状：没带原因也没交出串",
            raw = null,
            reason = null,
            expected = "教务系统没有给出可识别的开学日期（第 1 周 startDate = 缺失；" +
                "抓取这一侧没带原因、也没交出日期，那是异常形状，取证看日志），已保留原课表未做改动，请稍后重试",
            why = "按构造到不了这一等（missingReason 为 null 就意味着交出了一枚非空白串）。" +
                "仍然给它一句实话而不是让它去顶「教务答了」那半句：本仓口径是" +
                "「不可达支不许拿兜底顶过去」（T193 登记的三条事实里第 2 条）。",
        ),
        Cell(
            name = "档一：学期代码没过白名单",
            raw = null,
            reason = SemesterInfoReason.InvalidTermCode,
            expected = "教务系统没有给出可识别的开学日期（第 1 周 startDate = 缺失；" +
                "学期代码没通过白名单，那一趟周次请求根本没发出去，不是网络差），已保留原课表未做改动，请稍后重试",
            why = "这一档与网络好坏无关：请求没发出去。把它和「页面没答」混成一句，" +
                "就是让一位学期代码畸形的用户去查自己的 WiFi。",
        ),
        Cell(
            name = "档二：页面没答（重试之后仍然）",
            raw = null,
            reason = SemesterInfoReason.PageUnanswered,
            expected = "教务系统没有给出可识别的开学日期（第 1 周 startDate = 缺失；" +
                "那一趟周次页没有回音，重试过一次仍然超时或失败，页面根本没答），已保留原课表未做改动，请稍后重试",
            why = "「重试过一次」这一手是 T196① 给的额度，句子里念出来用户才知道这不是没试。",
        ),
        Cell(
            name = "档二之空白串那一等（raw 是全空白）",
            raw = "   ",
            reason = SemesterInfoReason.PageUnanswered,
            expected = "教务系统没有给出可识别的开学日期（第 1 周 startDate = 缺失；" +
                "那一趟周次页没有回音，重试过一次仍然超时或失败，页面根本没答），已保留原课表未做改动，请稍后重试",
            why = "「有没有拿到串」那一维按 `isNullOrBlank` 判：全空白与 null 同一等。",
        ),
        Cell(
            name = "档三：答了而一行周次都没给",
            raw = null,
            reason = SemesterInfoReason.EmptyWeekList,
            expected = "教务系统没有给出可识别的开学日期（第 1 周 startDate = 缺失；" +
                "教务答了周次页，但一行周次都没给），已保留原课表未做改动，请稍后重试",
            why = "与档二的分界就在这里：**答了**而答内容是空的。多半是这一学期还没建周次，" +
                "不是网络，也不该让用户以为是超时。",
        ),
        Cell(
            name = "档四：答了而那份答读不出来",
            raw = null,
            reason = SemesterInfoReason.WeekListUnreadable,
            expected = "教务系统没有给出可识别的开学日期（第 1 周 startDate = 缺失；" +
                "教务答了周次页，而那份答不是预期的形状，原生读不出来），已保留原课表未做改动，请稍后重试",
            why = "接口改版 / 教务挂了个 HTML 错误页进来都会落这一档。它是「协议对不上」，" +
                "该报的是取证与升级，不是让用户反复点重试。",
        ),
        Cell(
            name = "档五：周次行拿到了而第 1 周判不出",
            raw = null,
            reason = SemesterInfoReason.FirstWeekUndetermined,
            expected = "教务系统没有给出可识别的开学日期（第 1 周 startDate = 缺失；" +
                "教务给了周次行，而判不出哪一行是第 1 周：两条判据分家，或并列成两枚不同的日期串），" +
                "已保留原课表未做改动，请稍后重试",
            why = "这一档不重述内核那三枚 reason（分家 / 序号并列 / 最早日期并列）—— 那一本表在 " +
                "BuaaFirstWeek.UndeterminedReason、日志里念它。卡面「别再造第二本分类表」的分界就画在这里。",
        ),
        Cell(
            name = "正交组合：有串而原因是第五档（抓取这一侧到不了）",
            raw = "2026-09-07",
            reason = SemesterInfoReason.FirstWeekUndetermined,
            expected = "教务系统没有给出可识别的开学日期（第 1 周 startDate = 「2026-09-07」；" +
                "教务给了周次行，而判不出哪一行是第 1 周：两条判据分家，或并列成两枚不同的日期串），" +
                "已保留原课表未做改动，请稍后重试",
            why = "两维是**正交**的两本事实、不是一枚枚举的两种写法：这一格 `fetchSemesterInfo` 发不出来" +
                "（第五档必然把 firstWeekMonday 回成 null），但纯函数是全覆盖的映射，" +
                "所以钉它「raw 那一格跟着变而 cause 那一格不变」= 两维真的各走各的。",
        ),
    )
}
