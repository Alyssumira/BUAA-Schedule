package com.buaa.schedule.domain.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T243：带「第」前缀的周次写法必须与不带前缀**同解**（台账 #425）。
 *
 * 病：`第1-16周` 走完 clean 仍是「第1-16」，`split("-")` 得到 ["第1", "16"]，
 * `toIntOrNull` 把「第1」**整枚**丢掉 ⇒ range 从 2 枚掉到 1 枚 ⇒ 走单周支交回 `[16]`；
 * 末尾的 `1..CourseConstraints.MAX_WEEK` 限幅与非空的 `[16]` 不打架，编辑器
 * `weeks.isEmpty()` 那扇闸门同样放行 ⇒ 用户肉眼看见 16 周、库里存下 1 周。
 *
 * 第 0 问（宽容剥除还是判非法）按盘上证据裁给**宽容剥除**，凭据两处：
 * - `WeekParser` 今天已经在容忍纯外观差异：`normalizeWidths` 全角转半角，
 *   而在册 [WeekParserMalformedTest.fullWidthDigitsAndDashParseLikeHalfwidth]
 *   把 `１－１６` 钉成 1..16、`１－１５单` 钉成奇数周；
 * - `clean` 已经在剥「周」「空格」「，」，全是同一族"课表里的装饰字"。
 * 反过来，本仓真正判死的是 `17-2`（首尾倒置）、`2000000000-2000000001`（下界越界）
 * 那种**需要猜用户意图**的真歧义；「第」剥掉之后语义一个都不少，不属于那一档。
 *
 * 一形确有本仓自证的出处：ICS 导出的 DESCRIPTION 后半段就是这一形，
 * `IcsParser` 里的原文是 `第1-2节 · 第1,3,5周`。教务页面与纸质课表同样这么写
 * （教务路 `BuaaScheduleParser` 那一支能否真出现，本卡未验到，见卡 §8）。
 *
 * 每格都附「改前实际」—— 即本卡落地前 `WeekParser.parse` 真算出来的那个值，
 * 推演逐行核过；它同时进断言消息，所以红名自己会报出输入与改前读数。
 */
class WeekParserPrefixTest {

    /** 一格：输入 → 期望 → 改前实际 */
    private data class Row(val input: String, val expected: List<Int>, val preFix: List<Int>)

    private fun assertRow(row: Row) {
        assertEquals(
            "input=${row.input}（改前实际=${row.preFix}）",
            row.expected,
            WeekParser.parse(row.input),
        )
    }

    // ---- 正题：肉眼所见的周次集合 ----

    @Test
    fun `prefixed forms parse to the weeks the user sees`() {
        for (row in listOf(
            // 改前 [16]：区间首枚「第1」被丢光，range.size 从 2 塌到 1
            Row("第1-16周", (1..16).toList(), listOf(16)),
            // 改前 [3,5]：首段「第1」整枚丢，第 1 周静默缺席
            Row("第1,3,5周", listOf(1, 3, 5), listOf(3, 5)),
            // 改前 [16]：中文逗号先转英文，再撞上和第一格同一个塌法
            Row("第1-16周，第18周", (1..16) + listOf(18), listOf(16)),
            // 改前 []：塌成裸 16，又落在奇偶档上被 16 % 2 == 0 踢掉 ⇒ 整条判红
            Row("第1-16周(单)", (1..16).step(2).toList(), emptyList()),
            // 改前 [17]：塌成裸 17，17 是奇数所以活下来 —— 少 8 周却不红
            Row("第1-17周(单)", (1..17).step(2).toList(), listOf(17)),
            // 改前 [16]：双数档同样先塌成裸 16，这次 16 是偶数被留下
            Row("第1-16周(双)", (2..16).step(2).toList(), listOf(16)),
            // 改前 [11]：单周形，剥掉「第」才轮得到 toIntOrNull 认它
            Row("第11周", listOf(11), emptyList()),
            // 改前 [16]：全角与「第」叠加 —— normalizeWidths 先把外观归一，前缀这一族才露出来
            Row("第１－１６周", (1..16).toList(), listOf(16)),
            // 改前 1..16：裸「单」段是全局修饰符（在册那一格），但「第1-16」塌成 16 后被踢空
            Row("第1-16周,单周", (1..16).step(2).toList(), emptyList()),
            // 改前 []：上界 99 被夹到 MAX_WEEK=30 是本分，改前连区间都塌了 ⇒ 空
            Row("第1-99周", (1..30).toList(), emptyList()),
        )) {
            assertRow(row)
        }
    }

    // ---- 剥除必须是全局的，不是行首的 ----

    @Test
    fun `prefix is stripped in every segment, not only at the head`() {
        for (row in listOf(
            // 改前 [16]：第二段「第18」整枚丢
            Row("第1-16周,第18周", (1..16) + listOf(18), listOf(16)),
            // 改前 1..16：行首不带「第」、行中带 —— 只剥行首的写法在这一格会漏
            Row("1-16周,第18周", (1..16) + listOf(18), (1..16).toList()),
            // 改前 [3,5]：三段各带一枚「第」
            Row("第1周,第3周,第5周", listOf(1, 3, 5), listOf(3, 5)),
        )) {
            assertRow(row)
        }
    }

    // ---- 控制格：不带「第」的，改前后同解（一格都不动既有 21 格的期望值） ----

    @Test
    fun `unprefixed controls keep their values`() {
        for (row in listOf(
            Row("1-16周", (1..16).toList(), (1..16).toList()),
            Row("1,3,5周", listOf(1, 3, 5), listOf(1, 3, 5)),
            Row("1-16周(单)", (1..16).step(2).toList(), (1..16).step(2).toList()),
            Row("1-16,18周", (1..16) + listOf(18), (1..16) + listOf(18)),
            // 卡 §4：位图判定在 clean 之前，所以剥「第」碰不到位图形
            Row("01010101000000000000", listOf(2, 4, 6, 8), listOf(2, 4, 6, 8)),
            // 卡 §4 里 :19-20 那条注释的理由：1-2 位数字是周次，不是位图
            Row("10", listOf(10), listOf(10)),
            Row("11", listOf(11), listOf(11)),
        )) {
            assertRow(row)
        }
    }

    // ---- 剥「第」不得凭空造出周次，也不得把判定顺序挪位 ----

    @Test
    fun `stripping the prefix never invents a week`() {
        for (row in listOf(
            Row("第", emptyList(), emptyList()),
            Row("第周", emptyList(), emptyList()),
            // 卡 §8 遗留面：中文数字仍然解析不出，本卡没把它顺手修了
            Row("第一周", emptyList(), emptyList()),
            Row("前三周", emptyList(), emptyList()),
            // 顺序格：位图判定发生在剥「第」之前 ⇒ 这串不被当位图，落回数字支而读不出
            // 若有人把剥除挪到位图判定之前，这里会变成 [2,4,6,8]（卡 §4 的回归）
            Row("第01010101000000000000", emptyList(), emptyList()),
        )) {
            assertRow(row)
        }
    }

    /**
     * 同一枚 `parse` 还被编辑器拿去解额外节次（`CourseEditorScreen` 那两处）。
     * 「节」字本卡不剥，那是节次那张卡的正题；本格只钉一件事：
     * **带不带「第」不再造成分叉**。
     * 改前「第1-8节」= []、而「1-8节」= [1]（带前缀那一支更差，直接判红）；
     * 改后两支同解（同为 [1]），绝对值留给节次那张卡钉。
     */
    @Test
    fun `period box reuse no longer forks on the prefix`() {
        assertEquals(
            "带「第」与不带的节次串必须同解（改前 [] vs [1]，带前缀那一支更差）",
            WeekParser.parse("1-8节"),
            WeekParser.parse("第1-8节"),
        )
        // 同解不等于解对了：「节」字没人剥，两枚都只交出 [1]，
        // 绝对值是节次那张卡的正题，本卡不替它背书
        assertEquals(listOf(1), WeekParser.parse("第1-8节"))
        assertTrue(WeekParser.parse("第1-8节").isNotEmpty())
    }
}
