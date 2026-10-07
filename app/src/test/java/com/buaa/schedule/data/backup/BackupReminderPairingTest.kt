package com.buaa.schedule.data.backup

import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.schedule.ImportPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T229 ②（#303）：备份提醒的「片段依据 + 逐枚配对」判据表 —— 纯 JVM，零 android、零时钟、零库。
 *
 * 病灶一句话：写侧逐条发（同键两枚照发），读侧以前两处都是"后一枚吃掉前一枚"的 `associate`，
 * 而 `courseKey` 的七维里**没有 weeks** ⇒ 同一门课拆出的多枚片段共用一枚键，
 * 恢复后只有一枚片段领得到提醒，谁活下来由两张**无 ORDER BY** 查询的行序决定。
 *
 * 表里每一格都摆**改前会红**与**改后为绿**两头：
 * - 「只有一枚片段配得上」那一档 = 改前的实际行为（注释里写明了期望枚数差）；
 * - 「哪一枚活下来」那一档 = 改前由行序决定，这里用**排列不变**那两格把它钉成内容决定。
 *
 * 键与周次都用 [ImportPlanner.courseKey] / [Course] 真实构造，不手写签名字符串 ——
 * 手写键会让这张表测不到"七维里没有 weeks"这件事本身。
 */
class BackupReminderPairingTest {

    private companion object {
        const val TERM = "2026-2027-1"
    }

    /** 同门课的两枚片段：除周次外七维全同 ⇒ courseKey 相同（这正是 #303 的成因） */
    private fun fragment(id: Long, weeks: List<Int>, name: String = "高等数学") = Course(
        id = id,
        name = name,
        teacher = "张三",
        location = "J3-101",
        dayOfWeek = 1,
        periods = listOf(1, 2),
        weeks = weeks,
        sourceGroupKey = "2026-2027-1|MATH|01",
        semesterCode = TERM,
    )

    private fun keyOf(course: Course) = ImportPlanner.courseKey(course)

    private fun setting(
        courseKey: String,
        weeks: List<Int>?,
        enabled: Boolean = true,
        advance: Int = 10,
    ) = BackupReminderPairing.Setting(
        courseKey = courseKey,
        fragmentWeeks = weeks,
        enabled = enabled,
        advanceMinutes = advance,
    )

    private fun rowOf(course: Course) = BackupReminderPairing.rowOf(course)

    private fun assertShape(outcome: BackupReminderPairing.Outcome, expected: List<Pair<Long, Pair<Boolean, Int>>>) {
        assertEquals(
            "落库形状 (courseId → enabled/advance) 实到 " + outcome.assignments.map {
                it.courseId to (it.enabled to it.advanceMinutes)
            },
            expected,
            outcome.assignments.map { it.courseId to (it.enabled to it.advanceMinutes) },
        )
    }

    // ─────────────── ① 键本身：七维里没有 weeks（病灶成因） ───────────────

    @Test
    fun `同门课的两枚片段确实是同一枚键`() {
        val early = fragment(11L, (1..8).toList())
        val late = fragment(12L, (9..16).toList())

        assertEquals(
            "这一格是整张表的前提：两枚片段的 courseKey **相同**（只有 weeks 不同）。" +
                "哪天键里补进了周次，#303 就不成立了，这一格先红、逼着改判据",
            keyOf(early),
            keyOf(late),
        )
        assertTrue(
            "而两枚片段的周次确实不同（实到 ${early.weeks.size} / ${late.weeks.size}）：" +
                "键相同 + 周次不同 = 只有片段依据能把它们分开",
            early.weeks != late.weeks,
        )
    }

    // ─────────────── ② 新格式：片段依据精确命中 ───────────────

    @Test
    fun `同键两枚片段各领各的设置 一枚都不许被吃掉`() {
        val early = fragment(11L, listOf(1, 2, 3))
        val late = fragment(12L, listOf(9, 10, 11))
        val key = keyOf(early)

        val outcome = BackupReminderPairing.pair(
            // 改前：这两枚设置被 `associate` 合成一枚（后写吃前写），两行也只配到一行
            // ⇒ 恢复后 11 领到"关+15"、12 领到"开+5"，两枚都保住；改前只有其中一行有提醒
            settings = listOf(
                setting(key, early.weeks, enabled = false, advance = 15),
                setting(key, late.weeks, enabled = true, advance = 5),
            ),
            rows = listOf(rowOf(early), rowOf(late)),
        )

        assertShape(outcome, listOf(11L to (false to 15), 12L to (true to 5)))
        assertEquals(
            "两枚都必须是**精确命中**这一档（实到 " + outcome.assignments.map { it.basis } + "）：" +
                "退档意味着片段依据没起作用",
            listOf(BackupReminderPairing.Basis.FragmentMatched, BackupReminderPairing.Basis.FragmentMatched),
            outcome.assignments.map { it.basis },
        )
        assertEquals("一枚都不许多吃到对不回课程的账上", 0, outcome.unplaced.size)
    }

    @Test
    fun `关掉的那半不许复活`() {
        // 用户看得见的两档之一：关掉的那半重新响。断言的是 enabled=false 原样落到它那一行。
        val early = fragment(21L, listOf(1, 3, 5))
        val late = fragment(22L, listOf(7, 9))
        val key = keyOf(early)

        val outcome = BackupReminderPairing.pair(
            settings = listOf(
                setting(key, late.weeks, enabled = true, advance = 20),
                setting(key, early.weeks, enabled = false, advance = 10),
            ),
            rows = listOf(rowOf(late), rowOf(early)),
        )

        assertShape(outcome, listOf(21L to (false to 10), 22L to (true to 20)))
    }

    @Test
    fun `周次两侧写法不同也算命中`() {
        // 备份里 [3,1,2] 与库里 [1,2,3]（以及重复一枚）必须是同一片段：签名两侧都过 canonicalWeeks
        val row = fragment(31L, listOf(1, 2, 3))
        val key = keyOf(row)

        val outcome = BackupReminderPairing.pair(
            settings = listOf(setting(key, listOf(3, 1, 2, 2), enabled = false, advance = 45)),
            rows = listOf(rowOf(row)),
        )

        assertShape(outcome, listOf(31L to (false to 45)))
        assertEquals(
            "命中档还是精确命中（实到 ${outcome.assignments.single().basis}），不是退到组内猜",
            BackupReminderPairing.Basis.FragmentMatched,
            outcome.assignments.single().basis,
        )
    }

    // ─────────────── ③ 老备份（没有片段依据）：逐枚配对 ───────────────

    @Test
    fun `老备份同键两枚设置 两枚片段都领得到`() {
        val early = fragment(41L, listOf(1, 2, 3))
        val late = fragment(42L, listOf(9, 10, 11))
        val key = keyOf(early)

        val outcome = BackupReminderPairing.pair(
            settings = listOf(
                setting(key, null, enabled = false, advance = 15),
                setting(key, null, enabled = true, advance = 5),
            ),
            rows = listOf(rowOf(early), rowOf(late)),
        )

        assertEquals(
            "改前这里只有 1 枚 assignment（另一枚被 associate 吃掉）⇒ 实到 " + outcome.assignments.size,
            2,
            outcome.assignments.size,
        )
        assertEquals(
            "枚数保住，落到哪一行按组内确定序（周次升序 → id）推：1-2-3 在前 ⇒ 它领 enabled=false 那一枚",
            listOf(41L to (false to 15), 42L to (true to 5)),
            outcome.assignments.map { it.courseId to (it.enabled to it.advanceMinutes) },
        )
        assertEquals(
            "两枚都记成「老备份无依据」这一档 —— 结论必须说得出自己是猜的（[Basis.LegacyNoSignature]）",
            listOf(
                BackupReminderPairing.Basis.LegacyNoSignature,
                BackupReminderPairing.Basis.LegacyNoSignature,
            ),
            outcome.assignments.map { it.basis },
        )
    }

    @Test
    fun `老备份枚数多于行数 多出来的进账不许覆盖已配上号的行`() {
        val early = fragment(51L, listOf(1, 2))
        val late = fragment(52L, listOf(9, 10))
        val key = keyOf(early)

        val outcome = BackupReminderPairing.pair(
            settings = listOf(
                setting(key, null, enabled = false, advance = 1),
                setting(key, null, enabled = false, advance = 2),
                setting(key, null, enabled = true, advance = 3),
            ),
            rows = listOf(rowOf(early), rowOf(late)),
        )

        assertEquals("两行各领一枚", 2, outcome.assignments.size)
        assertEquals(
            "第三枚进 unplaced 账（实到 ${outcome.unplaced.size}）：改前它是「后写吃前写」把已配上号的" +
                "那一行再覆盖一遍 —— 那是同一种病，只是换了方向",
            1,
            outcome.unplaced.size,
        )
        assertEquals(
            "按确定序（advance 升序）前两枚留下，落库是 (false,1) 与 (false,2)",
            listOf(51L to (false to 1), 52L to (false to 2)),
            outcome.assignments.map { it.courseId to (it.enabled to it.advanceMinutes) },
        )
    }

    // ─────────────── ④ 退档与丢档 ───────────────

    @Test
    fun `签名对不上时退到键组 而不是把提醒丢掉`() {
        val row = fragment(61L, listOf(1, 2, 3))
        val key = keyOf(row)

        val outcome = BackupReminderPairing.pair(
            settings = listOf(setting(key, listOf(40, 41), enabled = false, advance = 25)),
            rows = listOf(rowOf(row)),
        )

        assertShape(outcome, listOf(61L to (false to 25)))
        assertEquals(
            "退档必须留痕（实到 ${outcome.assignments.single().basis}）：备份是用户可改的输入，" +
                "周次被改到库里没有的那一枚时**提醒不能消失**，但也不能装作是精确命中",
            BackupReminderPairing.Basis.SignatureUnmatched,
            outcome.assignments.single().basis,
        )
        assertEquals("退档了就不算丢", 0, outcome.unplaced.size)
    }

    @Test
    fun `键在库里对不出任何行 逐枚进账`() {
        val row = fragment(71L, listOf(1, 2), name = "高等数学")
        val otherKey = keyOf(row) + "|已改名的课"

        val outcome = BackupReminderPairing.pair(
            settings = listOf(setting(otherKey, listOf(1), enabled = false, advance = 3)),
            rows = listOf(rowOf(row)),
        )

        assertEquals("没有任何一行被写", 0, outcome.assignments.size)
        assertEquals(
            "这一枚进 unplaced 账（改前它是**静默**丢的：`courseByKey[key]?.let` 一句什么都不留）",
            listOf(otherKey),
            outcome.unplaced.map { it.courseKey },
        )
    }

    // ─────────────── ⑤ 排列不变：谁活下来不再由行序决定 ───────────────

    private fun permutations(items: List<Int>): List<List<Int>> {
        if (items.size <= 1) return listOf(items)
        return items.flatMapIndexed { i, head ->
            permutations(items.take(i) + items.drop(i + 1)).map { listOf(head) + it }
        }
    }

    @Test
    fun `结果与两侧输入的任何排列无关`() {
        val a = fragment(81L, listOf(1, 2))
        val b = fragment(82L, listOf(5, 6))
        val c = fragment(83L, listOf(9, 10))
        val key = keyOf(a)
        val rows = listOf(rowOf(a), rowOf(b), rowOf(c))
        val settings = listOf(
            setting(key, a.weeks, enabled = false, advance = 1),
            setting(key, b.weeks, enabled = true, advance = 2),
            setting(key, c.weeks, enabled = false, advance = 3),
        )

        val reference = BackupReminderPairing.pair(settings, rows)
        assertEquals("参照形状", listOf(81L to (false to 1), 82L to (true to 2), 83L to (false to 3)),
            reference.assignments.map { it.courseId to (it.enabled to it.advanceMinutes) })

        val rowPerms = permutations(listOf(0, 1, 2)).map { p -> p.map { rows[it] } }
        val setPerms = permutations(listOf(0, 1, 2)).map { p -> p.map { settings[it] } }
        var checked = 0
        for (rp in rowPerms) {
            for (sp in setPerms) {
                val out = BackupReminderPairing.pair(sp, rp)
                assertEquals(
                    "排列不变（行序 3! × 条目序 3! 里的一格）：rows=${rp.map { it.courseId }} / " +
                        "settings=${sp.map { it.advanceMinutes }}",
                    reference,
                    out,
                )
                checked++
            }
        }
        assertEquals("3! × 3! = 36 格全跑到了", 36, checked)
    }

    @Test
    fun `老备份那一档同样与行序无关`() {
        // 这一格才真正钉住 #303 那句「谁活下来由无 ORDER BY 查询的行序决定」：
        // 没有片段依据时，配对仍然只由**内容**（周次升序 → id、设置按内容升序）决定。
        val a = fragment(91L, listOf(1, 2))
        val b = fragment(92L, listOf(9, 10))
        val c = fragment(93L, listOf(13, 14))
        val key = keyOf(a)
        val rows = listOf(rowOf(a), rowOf(b), rowOf(c))
        val settings = listOf(
            setting(key, null, enabled = false, advance = 5),
            setting(key, null, enabled = true, advance = 15),
            setting(key, null, enabled = true, advance = 25),
        )

        val reference = BackupReminderPairing.pair(settings, rows)
        val outcomes = permutations(listOf(0, 1, 2)).map { p ->
            BackupReminderPairing.pair(settings, p.map { rows[it] })
        } + permutations(listOf(0, 1, 2)).map { p ->
            BackupReminderPairing.pair(p.map { settings[it] }, rows)
        }
        outcomes.forEach { assertEquals("行序/条目序换过来必须同一份结论", reference, it) }
        assertEquals(
            "内容升序配对：周次 1-2 领 (false,5)，9-10 领 (true,15)，13-14 领 (true,25)",
            listOf(91L to (false to 5), 92L to (true to 15), 93L to (true to 25)),
            reference.assignments.map { it.courseId to (it.enabled to it.advanceMinutes) },
        )
    }

    // ─────────────── ⑥ 不同键互不连坐 ───────────────

    @Test
    fun `两门课的键不同就各配各的`() {
        val math = fragment(101L, listOf(1, 2))
        val history = fragment(102L, listOf(1, 2), name = "中国近代史")
        assertNotEquals("两门课的键必须不同（这一格的前提）", keyOf(math), keyOf(history))

        val outcome = BackupReminderPairing.pair(
            settings = listOf(
                setting(keyOf(math), math.weeks, enabled = false, advance = 7),
                setting(keyOf(history), history.weeks, enabled = true, advance = 30),
            ),
            rows = listOf(rowOf(math), rowOf(history)),
        )

        assertShape(outcome, listOf(101L to (false to 7), 102L to (true to 30)))
        assertEquals("零丢枚", 0, outcome.unplaced.size)
    }

    @Test
    fun `空输入给空结论`() {
        val empty = BackupReminderPairing.pair(emptyList(), emptyList())
        assertEquals(emptyList<Any>(), empty.assignments)
        assertEquals(emptyList<Any>(), empty.unplaced)
    }
}
