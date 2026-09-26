package com.buaa.schedule.ui.importing

import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.schedule.ConflictDetector
import com.buaa.schedule.domain.schedule.CourseConflictResolution
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导入这一族「N 组」措辞（T94①）。
 *
 * 病：确认卡标题与三条解析完成提示念的都是 `conflicts.size`（两两配对的条数），
 * 而句子里写的字是"组"。归并判据早就有（[CourseConflictResolution.groupConflicts]，
 * 首页横幅 T82 与统计页冲突卡都吃它），本卡把同一把尺子量到这四处上。
 *
 * 所以本文件**不重测归并**（`CourseConflictResolutionTest` 有
 * mergesChainedConflictsIntoOneGroup / separatesGroupsByDay / emptyInputYieldsNoGroups 三档，
 * 统计页那侧还有 `StatsConflictCopyTest.threeCoursesCollidingPairwiseAreOneGroup`）；
 * 这里只补两层：
 * 1. 导入这一族的三句**文案确实按组数出**，且吃的是真内核
 *    （`findConflicts` → `groupConflicts`）跑出来的组数，不是手填的一个数；
 * 2. 标题念的数与明细画的行数**各档分别是多少**（钉住"两枚数分开报"这件事），
 *    外加一处接线：组数只在 `showPendingImport` 归并一次，界面与三条提示读同一枚字段。
 */
class ImportConflictCopyTest {

    private fun course(name: String, day: Int, periods: List<Int>, id: Long) = Course(
        id = id,
        name = name,
        dayOfWeek = day,
        periods = periods,
        weeks = (1..8).toList(),
        semesterCode = "2026-2027-1",
    )

    /** 一批课程经真内核得到的两枚数：两两配对的条数、归并后的组数 */
    private class Clash(courses: List<Course>) {
        val pairs: List<ConflictDetector.Conflict> = ConflictDetector.findConflicts(courses)
        val groups: List<CourseConflictResolution.ConflictGroup> = CourseConflictResolution.groupConflicts(pairs)

        /** 界面上真正画出来的那几行配对（与 ImportScreen 同一道 take、同一枚常数） */
        val drawnPairs: List<ConflictDetector.Conflict> = pairs.take(MAX_INLINE_CONFLICT_PAIRS)
    }

    /** 同一格摆 [count] 门课：配对是 C(n,2)，归并后是 1 组 */
    private fun sameSlot(count: Int, day: Int = 1, periods: List<Int> = listOf(3, 4)) =
        Clash((1..count).map { course("课$it", day = day, periods = periods, id = it.toLong()) })

    private fun mondayPlusWednesdayPlusFriday() = Clash(
        (1..3).map { course("周一课$it", day = 1, periods = listOf(3, 4), id = it.toLong()) } +
            (11..12).map { course("周三课$it", day = 3, periods = listOf(3, 4), id = it.toLong()) },
    )

    private fun threeDays() = Clash(
        (1..3).map { course("周一课$it", day = 1, periods = listOf(3, 4), id = it.toLong()) } +
            (11..13).map { course("周三课$it", day = 3, periods = listOf(3, 4), id = it.toLong()) } +
            (21..22).map { course("周五课$it", day = 5, periods = listOf(3, 4), id = it.toLong()) },
    )

    private fun twoPairsOnDifferentDays() = Clash(
        listOf(
            course("高等数学", day = 1, periods = listOf(3, 4), id = 1L),
            course("大学物理", day = 1, periods = listOf(3, 4), id = 2L),
            course("体育", day = 3, periods = listOf(3, 4), id = 3L),
            course("毛概", day = 3, periods = listOf(3, 4), id = 4L),
        ),
    )

    private fun twoPairsSameDayDifferentSlots() = Clash(
        listOf(
            course("A", day = 1, periods = listOf(3, 4), id = 1L),
            course("B", day = 1, periods = listOf(3, 4), id = 2L),
            course("C", day = 1, periods = listOf(7, 8), id = 3L),
            course("D", day = 1, periods = listOf(7, 8), id = 4L),
        ),
    )

    /** 链式撞：A-B 撞第 5-8 周、B-C 撞第 9-16 周，A 与 C 谁也没占谁的时间 → 2 条配对、1 组 */
    private fun chainedClash() = Clash(
        listOf(
            Course(
                id = 1L, name = "A", dayOfWeek = 1, periods = listOf(3, 4),
                weeks = (1..8).toList(), semesterCode = "2026-2027-1",
            ),
            Course(
                id = 2L, name = "B", dayOfWeek = 1, periods = listOf(3, 4),
                weeks = (5..16).toList(), semesterCode = "2026-2027-1",
            ),
            Course(
                id = 3L, name = "C", dayOfWeek = 1, periods = listOf(3, 4),
                weeks = (9..16).toList(), semesterCode = "2026-2027-1",
            ),
        ),
    )

    // ---- ① 三句措辞的形状（表驱动）----

    @Test
    fun bannerSpeaksGroupCount() {
        val cases = listOf(
            Triple(0, "存在 0 组时间冲突", "零组那一档由界面的 isEmpty() 挡着，措辞只如实报它拿到的数"),
            Triple(1, "存在 1 组时间冲突", "三门课互撞就是这一档：改前念的是「3 组」"),
            Triple(2, "存在 2 组时间冲突", "两条互不相干的冲突"),
            Triple(11, "存在 11 组时间冲突", "两位数不串行"),
        )
        cases.forEach { (groupCount, expected, why) ->
            assertEquals(why, expected, importConflictBanner(groupCount))
        }
    }

    /** 三条解析完成提示原先各写一遍、字面只差前缀；收进一件内核后逐字对回改前那三句 */
    @Test
    fun parseSummaryKeepsEveryOriginalSentenceShape() {
        val cases = listOf(
            "解析完成：" to "解析完成：新增 3，更新 1，冲突 2 组，请确认导入。",
            "ICS 解析完成：" to "ICS 解析完成：新增 3，更新 1，冲突 2 组，请确认导入。",
            "文本解析完成：" to "文本解析完成：新增 3，更新 1，冲突 2 组，请确认导入。",
        )
        cases.forEach { (prefix, expected) ->
            assertEquals(
                prefix,
                expected,
                importParseSummary(prefix = prefix, addedCount = 3, changedCount = 1, groupCount = 2),
            )
        }
        assertEquals(
            "零冲突那一档仍带这一句（句式本来就固定），只是数成 0",
            "ICS 解析完成：新增 0，更新 0，冲突 0 组，请确认导入。",
            importParseSummary(prefix = "ICS 解析完成：", addedCount = 0, changedCount = 0, groupCount = 0),
        )
    }

    @Test
    fun pairNoteSeparatesAllListedFromTruncated() {
        val cases = listOf(
            Triple(1, 1, "两两相撞 1 对，逐一列出："),
            Triple(3, 3, "两两相撞 3 对，逐一列出："),
            Triple(4, 3, "两两相撞 4 对，这里只列前 3 对："),
            Triple(9, 3, "两两相撞 9 对，这里只列前 3 对："),
        )
        cases.forEach { (pairCount, shownCount, expected) ->
            assertEquals(
                "$pairCount 对 / 画 $shownCount 行",
                expected,
                importConflictPairNote(pairCount = pairCount, shownCount = shownCount),
            )
        }
    }

    // ---- ② 「几组」那一枚数字按真内核出 ----

    /** 三门课挤同一格：配对 3 条、组 1 个 —— 改前标题那句念的是"存在 3 组" */
    @Test
    fun threeCoursesCollidingPairwiseSayOneGroup() {
        val clash = sameSlot(3)
        assertEquals("两两配对是 3 条", 3, clash.pairs.size)
        assertEquals("归并成组是 1 组", 1, clash.groups.size)
        assertEquals("存在 1 组时间冲突", importConflictBanner(clash.groups.size))
        assertEquals(
            "解析完成：新增 3，更新 1，冲突 1 组，请确认导入。",
            importParseSummary(prefix = "解析完成：", addedCount = 3, changedCount = 1, groupCount = clash.groups.size),
        )
    }

    /** 两条互不相干的冲突（不同天）⇒ 2 组 */
    @Test
    fun twoUnrelatedClashesSayTwoGroups() {
        val clash = twoPairsOnDifferentDays()
        assertEquals(2, clash.pairs.size)
        assertEquals(2, clash.groups.size)
        assertEquals("存在 2 组时间冲突", importConflictBanner(clash.groups.size))
        assertEquals(
            "两两相撞 2 对，逐一列出：",
            importConflictPairNote(pairCount = clash.pairs.size, shownCount = clash.drawnPairs.size),
        )
    }

    /** 同一天但不同时段：仍是两组 —— 「组」不是"撞了几天" */
    @Test
    fun sameDayDifferentSlotsAreStillTwoGroups() {
        val clash = twoPairsSameDayDifferentSlots()
        assertEquals(2, clash.pairs.size)
        assertEquals(2, clash.groups.size)
        assertEquals("存在 2 组时间冲突", importConflictBanner(clash.groups.size))
    }

    /**
     * 链式撞：明细按**对**列才有意义 —— 组里那三门课并非两两都撞，
     * 改成按组列就会把"A 与 C 其实没占彼此的时间"这件事说反（也丢用户判断依据）。
     */
    @Test
    fun chainedPairsMergeToOneGroupWhileDetailStillListsTwoPairs() {
        val clash = chainedClash()
        assertEquals("A-B 与 B-C 两条配对", 2, clash.pairs.size)
        assertEquals("传递闭包归并成 1 组", 1, clash.groups.size)
        assertEquals("存在 1 组时间冲突", importConflictBanner(clash.groups.size))
        assertEquals(
            "两两相撞 2 对，逐一列出：",
            importConflictPairNote(pairCount = clash.pairs.size, shownCount = clash.drawnPairs.size),
        )
        assertEquals("组里仍是三门课：哪两门真撞在这一层已经丢了", 3, clash.groups.single().courses.size)
    }

    // ---- ③ 标题念的数 vs 明细画的行数：各档实跑 ----

    /**
     * 这张表就是"改完之后用户在屏上看到的两枚数分别是多少"的记录（仅 JVM 证据，未上机）。
     * 每一格都从真内核跑出来：`pairs` 走 [ConflictDetector.findConflicts]、
     * `groups` 走 [CourseConflictResolution.groupConflicts]、`drawnRows` 走界面同一道 take。
     */
    @Test
    fun headlineCountVersusDrawnPairRows() {
        val rows = listOf(
            Row(
                what = "同格 2 门", clash = sameSlot(2),
                pairs = 1, groups = 1, drawnRows = 1,
                banner = "存在 1 组时间冲突", pairNote = "两两相撞 1 对，逐一列出：",
            ),
            Row(
                what = "同格 3 门", clash = sameSlot(3),
                pairs = 3, groups = 1, drawnRows = 3,
                banner = "存在 1 组时间冲突", pairNote = "两两相撞 3 对，逐一列出：",
            ),
            Row(
                what = "同格 4 门", clash = sameSlot(4),
                pairs = 6, groups = 1, drawnRows = 3,
                banner = "存在 1 组时间冲突", pairNote = "两两相撞 6 对，这里只列前 3 对：",
            ),
            Row(
                what = "周一 3 门 + 周三 2 门", clash = mondayPlusWednesdayPlusFriday(),
                pairs = 4, groups = 2, drawnRows = 3,
                banner = "存在 2 组时间冲突", pairNote = "两两相撞 4 对，这里只列前 3 对：",
            ),
            Row(
                what = "周一 3 门 + 周三 3 门 + 周五 2 门", clash = threeDays(),
                pairs = 7, groups = 3, drawnRows = 3,
                banner = "存在 3 组时间冲突", pairNote = "两两相撞 7 对，这里只列前 3 对：",
            ),
            Row(
                what = "链式撞（A-B、B-C）", clash = chainedClash(),
                pairs = 2, groups = 1, drawnRows = 2,
                banner = "存在 1 组时间冲突", pairNote = "两两相撞 2 对，逐一列出：",
            ),
        )

        val reading = mutableListOf<Row>()
        rows.forEach { row ->
            val clash = row.clash
            assertEquals("${row.what}：配对条数", row.pairs, clash.pairs.size)
            assertEquals("${row.what}：归并组数", row.groups, clash.groups.size)
            assertEquals("${row.what}：标题念的数", row.banner, importConflictBanner(clash.groups.size))
            assertEquals("${row.what}：明细行数", row.drawnRows, clash.drawnPairs.size)
            assertEquals(
                "${row.what}：明细小标题",
                row.pairNote,
                importConflictPairNote(pairCount = clash.pairs.size, shownCount = clash.drawnPairs.size),
            )
            // 四处同一把尺子：提示句里那枚数与标题那枚数必须是同一个
            assertTrue(
                "${row.what}：提示句的组数与标题分岔",
                importParseSummary(prefix = "解析完成：", addedCount = 0, changedCount = 0, groupCount = clash.groups.size)
                    .contains("冲突 ${clash.groups.size} 组"),
            )
            reading += row.copy(
                banner = importConflictBanner(clash.groups.size),
                pairNote = importConflictPairNote(
                    pairCount = clash.pairs.size,
                    shownCount = clash.drawnPairs.size,
                ),
            )
        }
        // T94① 收单要的那句"标题念的数 vs 明细画的行数"—— 各档读数由这一趟真内核跑出来，
        // 不是手算：报告里引的每一个数都出自下面这段 system-out（仅 JVM 证据，未上机）
        println("T94① 导入向导各档读数（配对 / 组 / 画出的行数）")
        reading.forEach { row ->
            println(
                "  ${row.what}: pairs=${row.clash.pairs.size} groups=${row.clash.groups.size} " +
                    "drawnRows=${row.clash.drawnPairs.size} | 标题「${row.banner}」| 小标题「${row.pairNote}」",
            )
        }
    }

    // ---- ④ 接线：组数只归并一次，界面与三条提示读同一枚字段 ----

    @Test
    fun groupCountIsMergedOnceInTheViewModelAndNotRecomputedInComposition() {
        val viewModel = readMainSource("com/buaa/schedule/ui/ScheduleViewModel.kt")
        assertEquals(
            "归并应当恰好一处（在 showPendingImport 里算好）：组合期每帧重算就是把 T82 " +
                "在首页修掉的账再欠一遍，两处各算也会各说各的数",
            1, occurrences(viewModel, "CourseConflictResolution.groupConflicts("),
        )
        assertEquals(
            "三条解析完成提示都该吃 pending.conflictGroupCount（少了就是还有一条路在念配对数）",
            3, occurrences(viewModel, "groupCount = pending.conflictGroupCount"),
        )
        for (banned in listOf("conflicts.size}", "组，请确认导入")) {
            assertFalse(
                "ScheduleViewModel 里还有「$banned」：那句组数的话又回到 VM 里就地拼了",
                viewModel.contains(banned),
            )
        }
    }

    @Test
    fun importScreenReadsTheGroupCountAndKeepsThePairDetail() {
        val screen = readMainSource("com/buaa/schedule/ui/importing/ImportScreen.kt")
        assertTrue(
            "确认卡标题没吃归并后的组数（三门课互撞会念成「3 组」而按组只有一档）",
            screen.contains("importConflictBanner(pending.conflictGroupCount)"),
        )
        assertFalse(
            "界面里不许硬写「组时间冲突」：那句话的措辞只许在 ImportConflictCopy 里有一份",
            screen.contains("组时间冲突"),
        )
        assertFalse(
            "界面里不许再拿 \${pending.conflicts.size} 说话：那是配对条数，不是组数",
            screen.contains("pending.conflicts.size}"),
        )
        // 明细仍按配对列（配对信息删不得），且行数上限走内核那枚常数，小标题才与画出的行对得上
        assertTrue(
            "明细的 take 没走 MAX_INLINE_CONFLICT_PAIRS：常数与界面会各改各的",
            screen.contains("pending.conflicts.take(MAX_INLINE_CONFLICT_PAIRS)"),
        )
        assertTrue(
            "小标题的 shownCount 没吃真正画出来的那几行：那就是两处各数一遍",
            screen.contains("shownCount = shownPairs.size"),
        )
    }

    /** 本仓硬判据：纯 JVM 判据内核文件零 android import —— 这一件连 import 都不该有 */
    @Test
    fun kernelFileIsPureJvmWithNoImportsAtAll() {
        val code = readMainSource("com/buaa/schedule/ui/importing/ImportConflictCopy.kt")
        val imports = code.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("import ") }
            .toList()
        assertTrue("措辞内核里出现了 import，这段判据就到不了 JVM：\n$imports", imports.isEmpty())
    }

    // ---- 源码核对小工具（抄 StatsT82WiringGuardTest 那一族）----

    private data class Row(
        val what: String,
        val clash: Clash,
        val pairs: Int,
        val groups: Int,
        val banner: String,
        val pairNote: String,
        val drawnRows: Int,
    )

    private fun occurrences(hay: String, needle: String): Int {
        var count = 0
        var at = hay.indexOf(needle)
        while (at >= 0) {
            count++
            at = hay.indexOf(needle, at + needle.length)
        }
        return count
    }

    private fun readMainSource(relativeFromJava: String): String {
        val file = File(findMainJavaDir(), relativeFromJava)
        assertTrue("找不到 ${file.path}：文件挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText()
    }

    private fun findMainJavaDir(): File {
        var dir: File? = File("").absoluteFile
        repeat(5) {
            val hit = listOf("src/main/java", "app/src/main/java")
                .map { File(dir, it) }
                .firstOrNull { it.isDirectory }
            if (hit != null) return hit
            dir = dir?.parentFile
        }
        throw IllegalStateException("找不到 app/src/main/java：当前目录 ${File("").absolutePath}")
    }
}
