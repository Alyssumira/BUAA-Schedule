package com.buaa.schedule.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T219③：`WidgetCommon.oobItemKey` 的撞车账（纯 JVM、零 android 读数、零时钟读数）。
 *
 * 卡面 T219 §1 要求「越界那一格返回的 id 与正常行的 id 会不会撞车」必须由推算给出出处，
 * 而推算要能被机器复算 ⇒ 这一枚就是那把尺。三条结论与三格一一对应：
 * 1. **不同列不可能撞**（高 32 位是 `position + 1`）：第 ① 层穷举。
 * 2. **同一列的撞号条件是 `内容指纹 ≡ position (mod 2^32)`** —— 每列恰好 1/2^32 的指纹会撞，
 *    而且这一族可以**代数式枚出来**：第 ② 层钉的正是「撞号集合里只有这一枚」与
 *    「+2^32 的同一 residue class 也算撞」两头，把 1/2^32 那句概率话钉成等式。
 * 3. **越界轮之间兜底 id 必须稳定**（同一枚空行不该被宿主当成两行）：第 ③ 层。
 *
 * 网格 `cells` 只有 7 列（`WEEKDAY_LABELS`），列表 `rows` 按一周课量给到 40 列的预算 ⇒
 * 下面用 `POSITIONS` 覆盖 0..40 两侧。
 */
class WidgetOobItemKeyTest {

    // ─────────────── ① 不同列不可能撞 ───────────────

    @Test
    fun `越界兜底 id 与任何不同列的正常行 id 都不可能相同`() {
        val stamps = listOf(
            0L, 1L, -1L, Long.MAX_VALUE, Long.MIN_VALUE,
            (1L shl 32), (1L shl 32) + 7L, -1234567890123L,
        )
        var pairs = 0
        for (p in POSITIONS) {
            for (q in POSITIONS) {
                if (p == q) continue
                for (stamp in stamps) {
                    assertNotEquals(
                        "position=$p 的越界兜底 id 与 position=$q 的正常行 id 撞了（指纹 $stamp）：" +
                            "itemKey 把 position 放在高 32 位，两列不同就不该相等",
                        oob(p),
                        WidgetCommon.itemKey(q, stamp),
                    )
                    pairs++
                }
            }
        }
        // 兜底与「越界列以外」的正常行穷举过一遍，避免 POSITIONS 被改窄后这格静默变空
        assertTrue("穷举没跑起来：POSITIONS=${POSITIONS.size} 列，期望至少 7 列", pairs >= 7 * 6 * stamps.size)
    }

    // ─────────────── ② 同列撞号的集合正好是「指纹 ≡ P」那一族 ───────────────

    @Test
    fun `同列撞号的条件就是内容指纹模 2 的 32 次方等于列号 每列只占一枚`() {
        for (p in POSITIONS) {
            val fallback = oob(p)
            // 会撞的那一枚：正常行的指纹算出来正好是列号本身
            assertEquals(
                "position=$p：正常行指纹取 $p 时必须与兜底 id 同值 —— 这就是唯一可撞的 residue class",
                fallback,
                WidgetCommon.itemKey(p, p.toLong()),
            )
            // 同一 residue class（差 2^32 的整数倍）也算撞：低位被 and 0xFFFF_FFFFL 截过
            assertEquals(
                "position=$p：指纹与 $p 同余（差一枚 2^32）时同样撞，说明撞号集合是 1/2^32 而不是 1/2^63",
                fallback,
                WidgetCommon.itemKey(p, p.toLong() + (1L shl 32)),
            )
            // 相邻 residue class 不撞：把 1/2^32 那一句的两边都钉住，否则上一条可能是套套逻辑
            for (delta in listOf(-3L, -1L, 1L, 2L, 4294L)) {
                assertNotEquals(
                    "position=$p：指纹 $p+$delta 不属于「≡ $p (mod 2^32)」那一族，不该与兜底 id 同值",
                    fallback,
                    WidgetCommon.itemKey(p, p.toLong() + delta),
                )
            }
        }
    }

    @Test
    fun `真实画出来的那些指纹 没有一枚落在越界兜底那一列上`() {
        // 两枚工厂正常行的低位指纹形状：内容 hashCode + 外观 viewIdStamp()（都是 32 位 Int hashCode）
        val appearanceStamp = WidgetAppearance().viewIdStamp()
        val contents = listOf(
            "周一高等数 A学楼-302", "周五体育篮球", "无课", "", "1大学物理(上)",
            "WEEK_GRID_MAX_LINES", "周三毛概1", "＋3",
        ).map { it.hashCode().toLong() } + listOf(0L, 1L, Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())

        var checked = 0
        for (p in POSITIONS) {
            for (content in contents) {
                val stamp = content + appearanceStamp
                assertNotEquals(
                    "position=$p 的正常行指纹（内容 $content + 外观戳 $appearanceStamp）落在了越界兜底 " +
                        "那一族上（≡ $p mod 2^32）⇒ 这一列的兜底 id 与正常行同值，" +
                        "宿主会把两条不同内容缓存成同一行（T137/#170 那一族）",
                    oob(p),
                    WidgetCommon.itemKey(p, stamp),
                )
                checked++
            }
        }
        assertTrue("穷举枚数少于预期（POSITIONS=${POSITIONS.size} × contents=${contents.size}）", checked >= 100)
    }

    // ─────────────── ③ 越界轮之间稳定 · 非负 ───────────────

    @Test
    fun `越界兜底 id 稳定且非负 宿主可以把同一张空行认成同一行`() {
        for (p in POSITIONS) {
            assertEquals(
                "position=$p：连续两轮越界必须交出同一枚 id —— 那两格画的是同一张空 RemoteViews，" +
                    "id 变了就会让宿主把一行空行重新 inflate 一遍",
                oob(p),
                oob(p),
            )
            assertTrue(
                "position=$p：id 必须非负（宿主拿它当 stable row id，负数进 SparseArray 那一族的口径要不起）",
                oob(p) >= 0L,
            )
        }
        // 不同列的兜底 id 之间也必须互异，否则 hasStableIds() 下一轮多格越界会撞
        assertEquals(
            "各列的兜底 id 枚数必须等于列数（有两列撞了就说明高位没起作用）",
            POSITIONS.size,
            POSITIONS.map { oob(it) }.distinct().size,
        )
    }

    /** 走 WidgetCommon 那一枚出处，别在这里重算一遍公式（重算了就等于没测出处） */
    private fun oob(position: Int): Long = WidgetCommon.oobItemKey(position)

    private companion object {
        /** 网格 7 列 + 列表按一周课量的预算 0..40 */
        val POSITIONS = (0..40).toList()
    }
}
