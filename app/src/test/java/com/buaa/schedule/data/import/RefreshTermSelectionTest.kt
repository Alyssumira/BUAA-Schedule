package com.buaa.schedule.data.import

import com.buaa.schedule.domain.importing.RefreshTermChoice
import com.buaa.schedule.domain.importing.RefreshTermSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T403② 表驱动单测：`RefreshTermSelection.choose` 那四档次序逐格钉死（在册 #347）。
 *
 * 纯 JVM：判据本体零 android import、零时钟、零设备（本仓硬规则），这里连 Robolectric 都不起。
 * 落点在 `data/import/` 而不是内核的同名包 ——
 * 内核那一本同名测试目录（`app/src/test/java/com/buaa/schedule/domain/importing` 整本）由另一枚在飞的卡独占
 * （卡面 §3 禁改清单），本卡只按名字引内核。
 *
 * ## 病灶在这一本表上的样子
 * 改前 `BuaaWebSession.refreshSchedule` 的体内那一行
 * `terms.firstOrNull { it.selected }?.code ?: terms.firstOrNull()?.code` 让「用户在首页下拉点的 A」与
 * 「这一趟真的去抓的那一枚」是两个读数；本表第 ③ 格那一行（`requested=A / selected=B` → `Fetch(A)`）
 * 就是那一枚错配的正面判据：它要是退回去答 `Fetch(B)`，#347 就原地复活。
 *
 * ## 逐格点名（本文件 9 枚 @Test / 运行期 58 格读数，格按循环展开：19＋1＋2＋2＋4＋3＋3＋2＋22）
 * ① 全表逐字（19 格 = 18 行 × 1 次结论比对 ＋ 1 格四档分布册子）：四档各自的落点一次数清。
 * ② 不变式（1 格）：`Fetch` 那一档交回的码**恒在本轮清单里** ——
 *    这一格是「用户手打的串不会绕过清单直送页面内 fetch」的全部凭据
 *    （对照 `BuaaInPageFetcher` 里 `fetchSemesterInfo` 有 `TERM_CODE_PATTERN`、逐周那一趟没有）。
 * ③ 本卡的心脏（2 格）：点名 A 而 selected=B ⇒ `Fetch(A)`，且**不是** `Fetch(B)`。
 * ④ 防误判（2 格）：点名的那一枚**恰是**服务器 selected ⇒ 仍 `Fetch` 它，不许判成「清单里没有」。
 * ⑤ 拒绝档（4 格）：清单没有 ⇒ `NotOffered`，交回的是 trim 后的原始串，且不退回 selected。
 * ⑥ 默认档三形（3 格）：`null`／空串／全空白 都走服务器 selected 那一格（首页菜单那枚不递学期的调用点）。
 * ⑦ selected 自身不可用（3 格）：null 或不在清单里 ⇒ 退清单第一枚，绝不交一枚清单外的码、也不交 null。
 * ⑧ 清单为空那一档排在点名判定之前（2 格）：点名而清单为空 ⇒ `NoOfferedList` 而不是 `NotOffered`。
 * ⑨ 三档互斥／档名册子闭合／幂等／逐字匹配（22 格 = 三枚档 1 ＋ 全表档名集合 1 ＋ 18 行各判两次同结论 18 ＋
 *    大小写不折叠 1 ＋ 清单不做 trim 1）：答案一律是具名档，不折 Boolean，也不替用户把坏串洗成好串。
 */
class RefreshTermSelectionTest {

    // ─────────────── ① 全表逐字 ───────────────

    @Test
    fun `全表每一格的结论逐字钉死 四档各自的落点一枚不多一枚不少`() {
        for (row in ROWS) {
            assertEquals(
                "行「${row.name}」：requested=${row.requested.show()} / 清单=${row.offered} / selected=" +
                    "${row.selected.show()} ⇒ 应当 ${row.expected}，实到 ${RefreshTermSelection.choose(row.requested, row.offered, row.selected)}",
                row.expected,
                RefreshTermSelection.choose(row.requested, row.offered, row.selected),
            )
        }
        val armCount = ROWS.groupBy { it.expected::class.simpleName }.mapValues { it.value.size }
        assertEquals(
            "① 那本表的四档分布必须恰好 Fetch 11／NotOffered 4／NoOfferedList 3（共 18 行）：" +
                "某一档长出第二行就得回来补判据，少一行就是那一档没人验了 ⇒ 实到 " + armCount,
            mapOf("Fetch" to 11, "NotOffered" to 4, "NoOfferedList" to 3),
            armCount,
        )
    }

    // ─────────────── ② 不变式：Fetch 那一档恒在清单里 ───────────────

    @Test
    fun `Fetch 那一档交回的码恒在本轮清单里 这一路唯一的白名单就是清单`() {
        val escaping = mutableListOf<String>()
        for (row in ROWS) {
            val verdict = RefreshTermSelection.choose(row.requested, row.offered, row.selected)
            if (verdict is RefreshTermChoice.Fetch && verdict.termCode !in row.offered) {
                escaping += "${row.name} ⇒ Fetch(${verdict.termCode}) 不在 ${row.offered}"
            }
        }
        assertEquals(
            "全 18 行里不许有一行的 Fetch 交回一枚**清单外**的学期码：那一句一破，" +
                "「用户手打的串先过本轮清单」这层就没有了（逐周那一趟 `fetchSemesterCourses` 把 termCode " +
                "直接拼进页面内 JS 的请求体，零枚形状白名单兜底）。实到越界的行：" + escaping,
            emptyList<String>(),
            escaping,
        )
    }

    // ─────────────── ③ 本卡的心脏：点名 A 就取 A ───────────────

    @Test
    fun `点名 A 而服务器 selected 是 B 就取 A 绝不退回 B`() {
        val verdict = RefreshTermSelection.choose(TERM_A, listOf(TERM_A, TERM_B), TERM_B)
        assertEquals(
            "这一行就是 #347 的正面判据：用户在首页「学期切换」下拉点的是 A、教务标 selected 的是 B ⇒ " +
                "取数那一趟必须认 A。答成 Fetch(B) 就是把改前那句 `firstOrNull { it.selected }` 请回来",
            RefreshTermChoice.Fetch(TERM_A),
            verdict,
        )
        assertTrue(
            "反向也钉一句：这一格不许等于 Fetch(B)（实到 $verdict）—— 只数「有没有等于 A」的正面格，" +
                "挡不住有人把两档并成一句「总是取 selected」",
            verdict != RefreshTermChoice.Fetch(TERM_B),
        )
    }

    // ─────────────── ④ 防误判：点名的恰是 selected 那一格 ───────────────

    @Test
    fun `点名的那一枚恰好等于服务器 selected 也照样取它 不许判成清单里没有`() {
        val verdict = RefreshTermSelection.choose(TERM_B, listOf(TERM_A, TERM_B), TERM_B)
        assertEquals(
            "用户重新点了一次「服务器自己当前那一枚」⇒ 必须 Fetch(TERM_B)。把它判成 NotOffered 是朝窄那一手：" +
                "清单判据认的是**在不在清单里**，不是**等不等于 selected**；这一格红就说明判据被改写成了" +
                "「只有不同于 selected 才算点名」",
            RefreshTermChoice.Fetch(TERM_B),
            verdict,
        )
        assertEquals(
            "同款第二形：清单里只有 selected 一枚时也一样（实到 " +
                RefreshTermSelection.choose(TERM_B, listOf(TERM_B), TERM_B) + "）",
            RefreshTermChoice.Fetch(TERM_B),
            RefreshTermSelection.choose(TERM_B, listOf(TERM_B), TERM_B),
        )
    }

    // ─────────────── ⑤ 拒绝档：整趟拒绝，不静默退回 ───────────────

    @Test
    fun `点名而本轮清单没有 就交回 NotOffered 且念的是 trim 后的那一枚`() {
        for ((needle, expected) in listOf(
            "2099-2100-9" to "2099-2100-9",
            "  2099-2100-9  " to "2099-2100-9",
            "not-a-term" to "not-a-term",
            "学期 A" to "学期 A",
        )) {
            val verdict = RefreshTermSelection.choose(needle, listOf(TERM_A, TERM_B), TERM_B)
            assertEquals(
                "点名「$needle」（清单里没有）⇒ 必须 NotOffered(\"$expected\")，" +
                    "而不是 Fetch(selected=B) —— 退回 selected 就是 #347 本体，" +
                    "而且会把「你打的那一枚教务没给」这一句可执行的话咽下去",
                RefreshTermChoice.NotOffered(expected),
                verdict,
            )
        }
    }

    // ─────────────── ⑥ 默认档三形 ───────────────

    @Test
    fun `没点名那三形 null 空串 全空白 都走服务器 selected 那一档`() {
        for (requested in listOf<String?>(null, "", "   ")) {
            assertEquals(
                "没点名那一形（实到 ${RefreshTermSelection.choose(requested, listOf(TERM_A, TERM_B), TERM_B)}）" +
                    "必须走服务器那一份 selected —— 这一档的宿主是首页菜单「刷新课表」那枚**不递学期**的调用点",
                RefreshTermChoice.Fetch(TERM_B),
                RefreshTermSelection.choose(requested, listOf(TERM_A, TERM_B), TERM_B),
            )
        }
    }

    // ─────────────── ⑦ selected 自己不可用 ───────────────

    @Test
    fun `selected 缺席或自己不在清单里就退清单第一枚 绝不交一枚清单外的码`() {
        assertEquals(
            "selected=null ⇒ 退清单第一枚（改前那句 `?: terms.firstOrNull()?.code` 的口径，本卡不改它）",
            RefreshTermChoice.Fetch(TERM_A),
            RefreshTermSelection.choose(null, listOf(TERM_A, TERM_B), null),
        )
        assertEquals(
            "selected 是一枚清单外的码 ⇒ 同样退清单第一枚，不许把 selected 自己原样交出去：" +
                "一旦交出去，② 那本不变式就破了（Fetch 的码不在清单里）",
            RefreshTermChoice.Fetch(TERM_A),
            RefreshTermSelection.choose(null, listOf(TERM_A, TERM_B), TERM_C),
        )
        assertEquals(
            "清单只有一枚且 selected=null ⇒ 就取那一枚（实到 " +
                RefreshTermSelection.choose("", listOf(TERM_B), null) + "）",
            RefreshTermChoice.Fetch(TERM_B),
            RefreshTermSelection.choose("", listOf(TERM_B), null),
        )
    }

    // ─────────────── ⑧ 清单为空那一档的优先级 ───────────────

    @Test
    fun `清单为空那一档排在点名判定之前 不许把没答读成你的代码不对`() {
        assertEquals(
            "清单为空 + 点名 ⇒ NoOfferedList，而不是 NotOffered：那一档下「你的代码不在清单里」是一句假话" +
                "（清单本身没到），用户据此做的那一手（改代码）根本不治因",
            RefreshTermChoice.NoOfferedList,
            RefreshTermSelection.choose(TERM_A, emptyList(), null),
        )
        assertEquals(
            "清单为空 + 没点名 ⇒ 同一档（实到 " +
                RefreshTermSelection.choose(null, emptyList(), TERM_A) + "）：selected 也救不了空清单",
            RefreshTermChoice.NoOfferedList,
            RefreshTermSelection.choose(null, emptyList(), TERM_A),
        )
    }

    // ─────────────── ⑨ 三档互斥 + 逐字匹配 ───────────────

    @Test
    fun `三档互斥不许折成 Boolean 而匹配是逐字匹配不 fold case 不 trim 清单`() {
        val arms = listOf<Any>(
            RefreshTermChoice.Fetch(TERM_A),
            RefreshTermChoice.NotOffered(TERM_A),
            RefreshTermChoice.NoOfferedList,
        )
        assertEquals(
            "三档的名字册子必须恰好是 {Fetch, NotOffered, NoOfferedList} 这三枚（折成 Boolean 就是把" +
                "「点名的那枚教务没给」与「教务压根没答清单」压成同一句话，而这两档的下一手" +
                "一枚要用户改代码、一枚要用户重新登录）。实到 " + arms.map { it::class.simpleName },
            listOf("Fetch", "NotOffered", "NoOfferedList"),
            arms.map { it::class.simpleName },
        )
        assertEquals(
            "全表 18 行的结论档类型名必须只有 {Fetch, NotOffered, NoOfferedList} 这三枚（判据本体返回的是档，" +
                "不是一枚「能不能刷」的旗位 —— 折成 Boolean 就把「点名的那枚教务没给」与" +
                "「教务压根没答清单」压成同一句话，而那两档的下一手一枚要用户改代码、一枚要用户重新登录）。" +
                "实到 " + arms.map { it::class.simpleName },
            setOf("Fetch", "NotOffered", "NoOfferedList"),
            ROWS.map { RefreshTermSelection.choose(it.requested, it.offered, it.selected)::class.simpleName }.toSet(),
        )
        for (row in ROWS) {
            assertEquals(
                "判据不读时钟、不看状态：行「${row.name}」同一输入连判两次必须同结论" +
                    "（这一族先例是 `BuaaSemesterStartPolicyTest`／`IcsSectionPolicyTest` 的同款格）",
                RefreshTermSelection.choose(row.requested, row.offered, row.selected),
                RefreshTermSelection.choose(row.requested, row.offered, row.selected),
            )
        }
        assertEquals(
            "大小写不折叠：清单给的是「abc」而用户打「ABC」⇒ NotOffered，不是 Fetch(abc)。" +
                "教务的 itemCode 是逐字标识符，折叠大小写去「救」一次输入等于替用户猜一枚学期",
            RefreshTermChoice.NotOffered("ABC"),
            RefreshTermSelection.choose("ABC", listOf("abc"), "abc"),
        )
        assertEquals(
            "清单自己不做 trim：清单里躺着「 X 」而用户打「X」⇒ NotOffered。" +
                "把清洗放到内核这一侧 = 悄悄改教务答回来的册子，两处读数又不同源一次",
            RefreshTermChoice.NotOffered("X"),
            RefreshTermSelection.choose("X", listOf(" X "), " X "),
        )
    }

    // ---------------- 表与工具 ----------------

    /** null 在报错消息里念得出来，免得 `null` 与 `"null"` 两形读起来一样 */
    private fun String?.show(): String = this?.let { "\"$it\"" } ?: "<null>"

    private companion object {
        /** 用户在下拉里点的那一枚（#347 里的 A）：它是清单里的一枚，而 selected 标的是另一枚 */
        const val TERM_A = "2025-2026-2"

        /** 教务标成 selected 的那一枚（#347 里的 B，也正是 `DEFAULT_BUAA_TERM` 那一族的名字形状） */
        const val TERM_B = "2026-2027-1"

        /** 一枚清单外的形状合法串，专门用来验「selected 自己不在清单里」那一格 */
        const val TERM_C = "2024-2025-3"

        val LIST = listOf(TERM_A, TERM_B)
        val SINGLE = listOf(TERM_B)
        val NONE: List<String> = emptyList()

        data class Row(
            val name: String,
            val requested: String?,
            val offered: List<String>,
            val selected: String?,
            val expected: RefreshTermChoice,
        )

        val ROWS = listOf(
            // ── 默认档（没点名）：6 行 ──
            Row("默认/null/有 selected", null, LIST, TERM_B, RefreshTermChoice.Fetch(TERM_B)),
            Row("默认/空串/有 selected", "", LIST, TERM_B, RefreshTermChoice.Fetch(TERM_B)),
            Row("默认/全空白/有 selected", "   ", LIST, TERM_B, RefreshTermChoice.Fetch(TERM_B)),
            Row("默认/null/无 selected", null, LIST, null, RefreshTermChoice.Fetch(TERM_A)),
            Row("默认/空串/单枚清单", "", SINGLE, null, RefreshTermChoice.Fetch(TERM_B)),
            Row("默认/null/selected 在清单外", null, LIST, TERM_C, RefreshTermChoice.Fetch(TERM_A)),
            // ── 点名且在清单：5 行 ──
            Row("#347 的心脏：点 A 而 selected 是 B", TERM_A, LIST, TERM_B, RefreshTermChoice.Fetch(TERM_A)),
            Row("点的恰是 selected 那一枚", TERM_B, LIST, TERM_B, RefreshTermChoice.Fetch(TERM_B)),
            Row("点第二枚而清单没标 selected", TERM_B, listOf(TERM_A, TERM_B, TERM_C), null, RefreshTermChoice.Fetch(TERM_B)),
            Row("点名带首尾空白 先 trim 再判", "  $TERM_A  ", LIST, TERM_B, RefreshTermChoice.Fetch(TERM_A)),
            Row("清单只有一枚而点的就是它", TERM_B, SINGLE, null, RefreshTermChoice.Fetch(TERM_B)),
            // ── 点名而清单没有：4 行 ──
            Row("手打一枚教务没给的代码", "2099-2100-9", LIST, TERM_B, RefreshTermChoice.NotOffered("2099-2100-9")),
            Row("含空格的非法串", "学期 A", LIST, TERM_B, RefreshTermChoice.NotOffered("学期 A")),
            Row("大小写不折叠", "ABC", listOf("abc"), "abc", RefreshTermChoice.NotOffered("ABC")),
            Row("清单不做 trim", "X", listOf(" X "), " X ", RefreshTermChoice.NotOffered("X")),
            // ── 清单为空：3 行 ──
            Row("空清单/点名", TERM_A, NONE, null, RefreshTermChoice.NoOfferedList),
            Row("空清单/没点名", null, NONE, TERM_A, RefreshTermChoice.NoOfferedList),
            Row("空清单/全空白点名", "   ", NONE, null, RefreshTermChoice.NoOfferedList),
        )
    }
}
