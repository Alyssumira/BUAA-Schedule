package com.buaa.schedule.domain.widget

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T329②：`WidgetWeekBrowseRequestCode.requestCode` 的表驱动逐格账（纯 JVM，无 Robolectric、无设备）。
 *
 * 钉的是**单射性**这件事本身，不是今天的算式：
 * - (a) 同一枚实例的两个方向必须不同码 —— 少一格方向项（`(if (delta > 0) 1 else 0)` 被删回 `+ 0`）
 *   就当场并成一枚数 ⇒ 第 2 格红；
 * - (b) 相邻实例的另一方向也必须不同码 —— 题眼那一行就是改前撞的那一枚：实例 1 的「下周」与
 *   实例 2 的「上周」。改前那一形给的都是 200_002（`200_000 + 1 + 1 == 200_000 + 2 + 0`），
 *   今天交回 200_003 与 200_004 ⇒ 第 3 格红；
 * - (c) 一批 id（含 0、负数、大值、声明域的两个边界）下返回值两两不同 ⇒ 第 4 格红；
 * - (d) 与 header / list 两族在**同一枚 id** 下不撞（跨族分块那本账不在本卡收：`appWidgetId ≥ 100_000`
 *   时三族仍会互相撞进同一枚数，登记在回执的遗留问题）⇒ 第 5 格红。
 *
 * 期望值**全部手写死**在下面那张表里（本仓反复犯的病就是拿被测算式现算期望值 —— 那是自证，不是尺）。
 * 表里的数按 `200_000 + 2 × appWidgetId + 方向档` 手算，测试代码里一个字都不重新实现它。
 *
 * ⚠️ 未装机（本卡零 adb、零设备读数）：撞号是盘上算术必然，而「两枚热区并成一枚 PendingIntent 之后
 * 覆写成哪一手」是 framework 行为推断（`Intent.filterEquals` 不比 extras，framework 文档口径）；
 * 这一枚表钉的是取码单射，不声称量到过任何一次真机投递。
 */
class WidgetWeekBrowseRequestCodeTest {

    private data class Cell(
        val label: String,
        val appWidgetId: Int,
        val delta: Int,
        val expected: Int,
    )

    private companion object {

        /** 表里每格的手写期望值（测试代码不许由它们反推算式） */
        val CELLS = listOf(
            // ── 0 号 id：族的起点 ──
            Cell("id 0 的上周落在基准上", 0, -1, 200_000),
            Cell("id 0 的下周是基准加一", 0, 1, 200_001),
            // ── 题眼：改前 next(1) == prev(2) == 200_002 ──
            Cell("id 1 的上周", 1, -1, 200_002),
            Cell("id 1 的下周 改前与 id 2 的上周同码", 1, 1, 200_003),
            Cell("id 2 的上周 改前与 id 1 的下周同码", 2, -1, 200_004),
            Cell("id 2 的下周", 2, 1, 200_005),
            Cell("id 3 的上周", 3, -1, 200_006),
            Cell("id 3 的下周", 3, 1, 200_007),
            // ── 桌面上真实会看到的量级 ──
            Cell("id 7 的上周", 7, -1, 200_014),
            Cell("id 7 的下周", 7, 1, 200_015),
            Cell("id 42 的上周", 42, -1, 200_084),
            Cell("id 42 的下周", 42, 1, 200_085),
            Cell("id 999 的上周", 999, -1, 201_998),
            Cell("id 999 的下周", 999, 1, 201_999),
            Cell("id 12345 的上周", 12_345, -1, 224_690),
            Cell("id 12345 的下周", 12_345, 1, 224_691),
            // ── header 族（id + 100_000）的交界那一档 id：同 id 下仍不撞（跨族分块的账不在本卡）──
            Cell("id 100000 的上周", 100_000, -1, 400_000),
            Cell("id 100000 的下周", 100_000, 1, 400_001),
            Cell("id 1000000 的上周", 1_000_000, -1, 2_200_000),
            Cell("id 1000000 的下周", 1_000_000, 1, 2_200_001),
            Cell("id 一亿的上周", 100_000_000, -1, 200_200_000),
            Cell("id 一亿的下周", 100_000_000, 1, 200_200_001),
            // ── 声明域的两枚边界（再往外 Int 要溢出，单射不再成立）──
            Cell("域上界的上周", 1_000_000_000, -1, 2_000_200_000),
            Cell("域上界的下周", 1_000_000_000, 1, 2_000_200_001),
            Cell("域下界的上周", -1_000_000_000, -1, -1_999_800_000),
            Cell("域下界的下周", -1_000_000_000, 1, -1_999_799_999),
            // ── 负数 id：结构上取不到，但取码函数是全域的，一并进账 ──
            Cell("id -1 的上周", -1, -1, 199_998),
            Cell("id -1 的下周", -1, 1, 199_999),
            Cell("id -3 的上周", -3, -1, 199_994),
            Cell("id -3 的下周", -3, 1, 199_995),
            Cell("id -42 的上周", -42, -1, 199_916),
            Cell("id -42 的下周", -42, 1, 199_917),
            Cell("id -1000000 的上周", -1_000_000, -1, -1_800_000),
            Cell("id -1000000 的下周", -1_000_000, 1, -1_799_999),
            // ── 只认符号不认步长：delta 的绝对值不参与取码 ──
            Cell("id 5 而 delta 是 0 归到上周那一格", 5, 0, 200_010),
            Cell("id 4 的下周", 4, 1, 200_009),
            Cell("id 4 而步长是 3 走的还是下周那一格", 4, 3, 200_009),
            Cell("id -7 而步长是 -5 走的还是上周那一格", -7, -5, 199_986),
        )

        /** 单射普查用的 id 批（含 0、负数、大值与域边界） */
        val SURVEY_IDS = listOf(
            0, 1, 2, 3, 4, 5, 7, 42, 999, 12_345, 100_000, 1_000_000, 100_000_000,
            1_000_000_000, -1, -3, -7, -42, -1_000_000, -1_000_000_000,
        )

        /** (d) 那一批：同 id 下三族的取法各是多少，逐枚手写（header = id + 100_000、list = 裸 id） */
        val FAMILY_ROWS = listOf(
            FamilyRow(appWidgetId = 0, listRequestCode = 0, headerRequestCode = 100_000, weekPrev = 200_000, weekNext = 200_001),
            FamilyRow(appWidgetId = 1, listRequestCode = 1, headerRequestCode = 100_001, weekPrev = 200_002, weekNext = 200_003),
            FamilyRow(appWidgetId = 2, listRequestCode = 2, headerRequestCode = 100_002, weekPrev = 200_004, weekNext = 200_005),
            FamilyRow(appWidgetId = 7, listRequestCode = 7, headerRequestCode = 100_007, weekPrev = 200_014, weekNext = 200_015),
            FamilyRow(appWidgetId = 42, listRequestCode = 42, headerRequestCode = 100_042, weekPrev = 200_084, weekNext = 200_085),
            FamilyRow(appWidgetId = 999, listRequestCode = 999, headerRequestCode = 100_999, weekPrev = 201_998, weekNext = 201_999),
            FamilyRow(appWidgetId = 100_000, listRequestCode = 100_000, headerRequestCode = 200_000, weekPrev = 400_000, weekNext = 400_001),
            FamilyRow(appWidgetId = 1_000_000, listRequestCode = 1_000_000, headerRequestCode = 1_100_000, weekPrev = 2_200_000, weekNext = 2_200_001),
            FamilyRow(appWidgetId = -3, listRequestCode = -3, headerRequestCode = 99_997, weekPrev = 199_994, weekNext = 199_995),
        )
    }

    /** 同一枚 id 下三族（列表 / 头部 / 翻周两格）的手写期望码 */
    private data class FamilyRow(
        val appWidgetId: Int,
        val listRequestCode: Int,
        val headerRequestCode: Int,
        val weekPrev: Int,
        val weekNext: Int,
    )

    // ─────────────── ① 表驱动逐格账（期望值手写死） ───────────────

    @Test
    fun `逐格 每枚实例与方向的码都是表里那一枚死值`() {
        val drift = CELLS.mapNotNull { cell ->
            val actual = WidgetWeekBrowseRequestCode.requestCode(cell.appWidgetId, cell.delta)
            if (actual == cell.expected) {
                null
            } else {
                "${cell.label}：id=${cell.appWidgetId} delta=${cell.delta} 实到 $actual ／ 手写 $cell.expected"
            }
        }
        assertEquals(
            "表驱动 ${CELLS.size} 格全走一遍（期望值是手写的死值，不由被测算式现算）。漂移格：" +
                drift.joinToString(" ／ "),
            emptyList<String>(),
            drift,
        )
    }

    // ─────────────── ② (a) 同一枚实例的两个方向 ───────────────

    @Test
    fun `同一枚实例的两个方向不同码 少一枚方向项就并成同码`() {
        val same = SURVEY_IDS.mapNotNull { id ->
            val prev = WidgetWeekBrowseRequestCode.requestCode(id, -1)
            val next = WidgetWeekBrowseRequestCode.requestCode(id, 1)
            if (prev == next) "id $id 的两个方向并成了 $prev" else null
        }
        assertEquals(
            "每个 id 的「上周」与「下周」必须不同码（实到同码的 id：" + same.joinToString(" ／ ") + "）。" +
                "把方向项删回 `+ 0` 时这一格当场红 —— 两枚热区并成一枚 PendingIntent，" +
                "extras 由最后渲染那一手说了算",
            emptyList<String>(),
            same,
        )
        // 表里那两枚手写值就是这个性质的样本（不是由算式现算）
        assertEquals("id 1 的上周手写值", 200_002, WidgetWeekBrowseRequestCode.requestCode(1, -1))
        assertEquals("id 1 的下周手写值", 200_003, WidgetWeekBrowseRequestCode.requestCode(1, 1))
        assertEquals("方向档本身也要分家", listOf(0, 1), listOf(
            WidgetWeekBrowseRequestCode.laneOf(-1),
            WidgetWeekBrowseRequestCode.laneOf(1),
        ))
    }

    // ─────────────── ③ (b) 相邻实例与另一方向：题眼那一枚 ───────────────

    @Test
    fun `相邻实例的另一方向不同码 题眼就是改前撞的那一对`() {
        val collided = SURVEY_IDS.mapNotNull { id ->
            val nextOfThis = WidgetWeekBrowseRequestCode.requestCode(id, 1)
            val prevOfNext = WidgetWeekBrowseRequestCode.requestCode(id + 1, -1)
            if (nextOfThis == prevOfNext) "next($id) == prev(${id + 1}) == $nextOfThis" else null
        }
        assertEquals(
            "实例 k 的「下周」与实例 k+1 的「上周」必须不同码（改前那一形给的都是同一个数）。" +
                "撞上的格子：" + collided.joinToString(" ／ ") +
                " —— 这一格红就是台账 #577 那枚撞号回潮：两枚组件的热区共享一枚 PendingIntent，" +
                "点 k 的下一周可能把 -1 与 k+1 递进广播，写进另一枚组件的周次账",
            emptyList<String>(),
            collided,
        )
        assertEquals("题眼左半边 next(1) 的手写值", 200_003, WidgetWeekBrowseRequestCode.requestCode(1, 1))
        assertEquals("题眼右半边 prev(2) 的手写值 改前这里也是 200_002", 200_004, WidgetWeekBrowseRequestCode.requestCode(2, -1))
        assertEquals(
            "题眼那两枚今天差一格（改前那一形差 0 ⇒ 同码）：实到 " +
                (WidgetWeekBrowseRequestCode.requestCode(2, -1) - WidgetWeekBrowseRequestCode.requestCode(1, 1)),
            1,
            WidgetWeekBrowseRequestCode.requestCode(2, -1) - WidgetWeekBrowseRequestCode.requestCode(1, 1),
        )
    }

    // ─────────────── ④ (c) 一批 id 下两两不同 ───────────────

    @Test
    fun `一批 id 含零与负数与大值 返回值两两不同`() {
        val codes = SURVEY_IDS.flatMap { id ->
            listOf(
                id to WidgetWeekBrowseRequestCode.requestCode(id, -1),
                id to WidgetWeekBrowseRequestCode.requestCode(id, 1),
            )
        }
        val duplicated = codes.groupBy { (_, code) -> code }.filterValues { it.size > 1 }
        assertEquals(
            "${codes.size} 枚取码（${SURVEY_IDS.size} 个 id × 两个方向）必须两两不同。撞码的账：" +
                duplicated.entries.joinToString(" ／ ") { (code, pairs) -> "$code ← $pairs" } +
                " ｜ 判据：同一枚码就是同一个 PendingIntent，extras 不参与判等",
            emptyList<String>(),
            duplicated.entries.map { (code, pairs) -> "$code ← $pairs" },
        )
        // 表里手写的期望值自己也不能重复 —— 期望值若自己就撞，上面那格就成了自证。
        // 唯一一对允许同值的是「只认符号不认步长」那两行（id 4 的 +1 与 +3 走同一格）。
        val expectedDuplicates = CELLS.map { cell -> cell.expected }.groupingBy { code -> code }.eachCount()
            .filterValues { count -> count > 1 }
        assertEquals(
            "表里 ${CELLS.size} 枚手写期望值里只许出现「只认符号」那一对同值（200_009 两行），" +
                "其余必须互不相同。实到重复值：" + expectedDuplicates,
            listOf(200_009),
            expectedDuplicates.keys.sorted(),
        )
    }

    // ─────────────── ⑤ (d) 同 id 下不与 header/list 两族撞 ───────────────

    @Test
    fun `同一枚 id 下翻周两格与头部列表两族不同码`() {
        val drift = FAMILY_ROWS.mapNotNull { row ->
            val prev = WidgetWeekBrowseRequestCode.requestCode(row.appWidgetId, -1)
            val next = WidgetWeekBrowseRequestCode.requestCode(row.appWidgetId, 1)
            val four = listOf(row.listRequestCode, row.headerRequestCode, prev, next)
            when {
                prev != row.weekPrev || next != row.weekNext ->
                    "id ${row.appWidgetId}：翻周实到 $prev/$next ／ 手写 ${row.weekPrev}/${row.weekNext}"
                four.distinct().size != four.size ->
                    "id ${row.appWidgetId}：同 id 下四枚码 $four 里有重复"
                else -> null
            }
        }
        assertEquals(
            "同一枚 id 下的四枚码（列表 = 裸 id、头部 = id + 100_000、翻周两格）必须互不相同，" +
                "且翻周那两枚仍等于表里的死值。漂移格：" + drift.joinToString(" ／ ") +
                " ｜ 覆盖面如实写：这只钉**同 id** 那一档；跨族分块（appWidgetId ≥ 100_000 时" +
                "header(100_000) 就落在翻周族的起点上）不在本卡收，登记在册的遗留问题",
            emptyList<String>(),
            drift,
        )
    }

    // ─────────────── ⑥ 只认符号：步长绝对值不参与取码 ───────────────

    @Test
    fun `方向项只认符号 步长绝对值进不了取码`() {
        assertEquals("delta 取 +1 与 +3 必须同码（表里手写 200_009）", 200_009, WidgetWeekBrowseRequestCode.requestCode(4, 1))
        assertEquals("delta 取 +3 与 +1 同码", WidgetWeekBrowseRequestCode.requestCode(4, 1), WidgetWeekBrowseRequestCode.requestCode(4, 3))
        assertEquals("delta 取 -1 与 -5 同码（表里手写 199_986）", 199_986, WidgetWeekBrowseRequestCode.requestCode(-7, -5))
        assertEquals("delta 为 0 归到上周那一格（表里手写 200_010）", 200_010, WidgetWeekBrowseRequestCode.requestCode(5, 0))
        assertEquals("零与负数同档：与改前 `if (delta > 0) 1 else 0` 的方向口径一致", listOf(0, 0, 0), listOf(
            WidgetWeekBrowseRequestCode.laneOf(0),
            WidgetWeekBrowseRequestCode.laneOf(-1),
            WidgetWeekBrowseRequestCode.laneOf(-5),
        ))
    }

    // ─────────────── ⑦ 族的常数：数值钉死，改数值要连这两枚文件一起 ───────────────

    @Test
    fun `族的起点与格数与方向档 数值钉死`() {
        assertEquals(
            "翻周族的起点必须是 200_000（与改前同一枚基准；本卡只换算式不动起点）",
            200_000,
            WidgetWeekBrowseRequestCode.BASE,
        )
        assertEquals("一枚实例在这一族里占两格", 2, WidgetWeekBrowseRequestCode.LANE_COUNT)
        assertEquals("上周那一档", 0, WidgetWeekBrowseRequestCode.PREV_LANE)
        assertEquals("下周那一档", 1, WidgetWeekBrowseRequestCode.NEXT_LANE)
        assertEquals(
            "header 族与列表族的数值一个字都没动（本卡口径：改它们会把已装机的 PI 全部作废一次）：" +
                "列表 = 裸 id ⇒ id 7 就是 7；header = id + 100_000 ⇒ id 7 就是 100_007",
            listOf(7, 100_007),
            listOf(FAMILY_ROWS[3].listRequestCode, FAMILY_ROWS[3].headerRequestCode),
        )
    }
}
