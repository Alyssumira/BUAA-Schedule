package com.buaa.schedule.ui

import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.Semester
import com.buaa.schedule.domain.schedule.ConflictDetector
import com.buaa.schedule.domain.schedule.CourseConflictResolution
import com.buaa.schedule.domain.schedule.ImportPlanner
import com.buaa.schedule.ui.importing.importConflictBanner
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T95①：导入确认卡那句「存在 N 组时间冲突」必须跟着逐条勾选走。
 *
 * 病（T94① 引入的回归）：T94 把标题从 `conflicts.size`（配对条数）换成
 * `PendingImport.conflictGroupCount`（归并后的组数），而那枚数是 `showPendingImport`
 * 在预览成型时算好、存进构造参数的。可 `PendingImport` 还有两处 `copy()` 会换掉
 * `conflicts` 却换不掉它 —— `togglePendingImportCourse` 与 `setAllPendingImportSelected`
 * 都是 `copy(conflicts = findConflicts(selection.toWrite))`，`copy()` 把旧的组数原样带走。
 * 后果：用户取消勾选一门撞车的课，下面的配对明细变小，标题那枚「N 组」不动。
 *
 * 本文件两层：
 * 1. **行为层** [excludingOneCollidingCourseShrinksTheGroupCountTheTitleReads] ——
 *    拿真内核（`findConflicts` / `groupConflicts`）与真选型函数（`resolveImportSelection`）
 *    跑一遍"两簇各两门 ⇒ 2 配对 2 组 ⇒ 取消勾选一门 ⇒ 1 配对 1 组"这条链，
 *    断言标题读的那枚数与画出来的配对归并后的组数相等；
 *    [mergedGroupCountFollowsTheExcludedSubsetThroughTheSelectionFunction] 再把同一件事
 *    在纯函数那一层摊开成三档（改前改后都绿，用来对照"本来该是几个数"）。
 * 2. **形状层** [groupCountIsDerivedAndCannotBeSetAtAnyCopySite] —— 钉的是"这枚数不可能与
 *    `conflicts` 脱钩"这件事本身：`conflictGroupCount` 不许再出现在构造参数表里、
 *    也不许在任何 `copy()`/构造调用点上被赋值，只能由 `conflicts` 派生。
 *    形状层是**编译期**的网（第三处写站点想漏都漏不了），行为层证的是它真的对得上数。
 */
class PendingImportConflictGroupTest {

    private val semester = Semester(
        id = 1L,
        termCode = "2026-2027-1",
        termName = "2026-2027 秋季学期",
        startDate = "2026-09-07",
        totalWeeks = 19,
    )

    private fun course(id: Long, name: String, day: Int) = Course(
        id = id,
        name = name,
        dayOfWeek = day,
        periods = listOf(3, 4),
        weeks = (1..8).toList(),
        semesterCode = semester.termCode,
    )

    /** 两簇互不相干的撞车：周一那格两门、周三那格两门 ⇒ 2 条配对、归并成 2 组 */
    private val twoClusters = listOf(
        course(1L, "周一撞车甲", day = 1),
        course(2L, "周一撞车乙", day = 1),
        course(3L, "周三撞车丙", day = 3),
        course(4L, "周三撞车丁", day = 3),
    )

    private fun pairsOf(courses: List<Course>): List<ConflictDetector.Conflict> =
        ConflictDetector.findConflicts(courses)

    private fun groupsOf(pairs: List<ConflictDetector.Conflict>): Int =
        CourseConflictResolution.groupConflicts(pairs).size

    /**
     * 与 `showPendingImport` 同一次成型：配对出自真内核，组数按 T94 那把尺子归并。
     *
     * 组数不在这里递 —— `PendingImport.conflictGroupCount` 是 `conflicts` 的派生属性（T95①），
     * 构造点与 `copy()` 都点不到它。改前那一版本函数要多写一行
     * `conflictGroupCount = groupsOf(pairs)`（当时它是构造参数，不传编译不过），
     * 那一行正是本次回归的形状：`copy()` 换 `conflicts` 时没人换它。
     */
    private fun pendingOf(courses: List<Course>): PendingImport =
        PendingImport(
            semester = semester,
            courses = courses,
            existingCount = 0,
            addedCount = courses.size,
            changedCount = 0,
            conflicts = pairsOf(courses),
        )

    /** 逐条勾选那颗按钮实际做的事（与 `togglePendingImportCourse` 同一串 copy 参数） */
    private fun toggleOff(pending: PendingImport, course: Course): PendingImport {
        val excluded = pending.excludedKeys + ImportPlanner.courseKey(course)
        val selection = resolveImportSelection(pending.courses, excluded, emptyList())
        return pending.copy(
            excludedKeys = excluded,
            addedCount = selection.addedCount,
            changedCount = selection.changedCount,
            keptCount = selection.keptCount,
            conflicts = pairsOf(selection.toWrite),
        )
    }

    // ---- 1. 行为层 ----

    @Test
    fun excludingOneCollidingCourseShrinksTheGroupCountTheTitleReads() {
        val pending = pendingOf(twoClusters)
        println("T95① 改前/改后对照（两簇各两门）")
        println("  初始：pairs=${pending.conflicts.size} 组数(标题读)=${pending.conflictGroupCount} 归并实算=${groupsOf(pending.conflicts)}")
        assertEquals(2, pending.conflicts.size)
        assertEquals(2, pending.conflictGroupCount)

        // 用户取消勾选「周三撞车丙」：那一簇不再撞，周三那格只剩一门
        val after = toggleOff(pending, twoClusters[2])
        println("  取消一门后：pairs=${after.conflicts.size} 组数(标题读)=${after.conflictGroupCount} 归并实算=${groupsOf(after.conflicts)}")

        // 明细（新鲜值）确实小了一档：2 条配对 → 1 条
        assertEquals("明细的配对条数该跟着子集变小", 1, after.conflicts.size)
        // 标题读的那枚数必须跟着走 —— 改前冻结在 2
        assertEquals(
            "取消勾选掉一门撞车的课之后，标题念的组数必须等于「剩下的配对归并出来的组数」：" +
                "配对从 2 掉到 1、组数从 2 掉到 1，标题却还念着上一屏那枚数就是本次的回归",
            groupsOf(after.conflicts),
            after.conflictGroupCount,
        )
        // 用户看得见的那句字必须与画出来的那几行配对自洽
        assertEquals(
            "界面上「标题 vs 明细」两句话对不上号",
            importConflictBanner(groupsOf(after.conflicts)),
            importConflictBanner(after.conflictGroupCount),
        )

        // 再取消「周一撞车甲」：只剩两门各占一天 ⇒ 一条配对都不剩，组数该归零
        val emptied = toggleOff(after, twoClusters[0])
        println("  再取消一门：pairs=${emptied.conflicts.size} 组数(标题读)=${emptied.conflictGroupCount}")
        assertEquals(0, emptied.conflicts.size)
        assertEquals("冲突全被排除后组数该归零（这一档整块不渲染，但字段值也不许留着旧数）", 0, emptied.conflictGroupCount)
    }

    @Test
    fun droppingOnePairInsideASingleGroupKeepsTheGroupCountAtOne() {
        // 三门挤同一格：findConflicts 交回 3 条配对，groupConflicts 归并成 1 组。
        // 排除其中一门只剩 1 条配对，归并后**仍是** 1 组 —— 组数跟的是归并结果而不是配对条数，
        // 这一档钉的是"派生不等于逐条同号"，改前改后都该绿（改前是"恰好相等"的运气）。
        val threeSameSlot = listOf(
            course(11L, "同格甲", day = 1),
            course(12L, "同格乙", day = 1),
            course(13L, "同格丙", day = 1),
        )
        val pending = pendingOf(threeSameSlot)
        assertEquals(3, pending.conflicts.size)
        assertEquals(1, pending.conflictGroupCount)

        val after = pending.copy(conflicts = pairsOf(threeSameSlot.take(2)))
        println("  同一组里排除一门：pairs=${after.conflicts.size} 组数(标题读)=${after.conflictGroupCount} 归并实算=${groupsOf(after.conflicts)}")
        assertEquals(1, after.conflicts.size)
        assertEquals("还剩一组就该念 1 组（组数不是配对条数）", groupsOf(after.conflicts), after.conflictGroupCount)
    }

    @Test
    fun mergedGroupCountFollowsTheExcludedSubsetThroughTheSelectionFunction() {
        // 纯函数那一层的三档账：走的是两处 copy 站点真正调的那条链
        // resolveImportSelection → ConflictDetector.findConflicts → CourseConflictResolution.groupConflicts
        val rows = mutableListOf<Triple<String, Int, Int>>()
        var pending = pendingOf(twoClusters)
        rows += Triple("全部勾选（两簇各两门）", pending.conflicts.size, groupsOf(pending.conflicts))
        pending = toggleOff(pending, twoClusters[2])
        rows += Triple("取消「周三撞车丙」（周三那格只剩一门）", pending.conflicts.size, groupsOf(pending.conflicts))
        pending = toggleOff(pending, twoClusters[0])
        rows += Triple("再取消「周一撞车甲」（两簇都散了）", pending.conflicts.size, groupsOf(pending.conflicts))

        println("T95① 三档读数（配对条数 / 归并组数 = 标题该念的数）")
        rows.forEach { (what, pairs, groups) ->
            println("  $what: pairs=$pairs groups=$groups 标题「${importConflictBanner(groups)}」")
        }
        assertEquals(
            "三档的「归并组数」必须逐档跟着子集走（2 → 1 → 0）",
            listOf(2, 1, 0),
            rows.map { it.third },
        )
        assertEquals("配对条数同步 2 → 1 → 0", listOf(2, 1, 0), rows.map { it.second })
    }

    // ---- 2. 形状层：这枚数不可能与 conflicts 脱钩 ----

    @Test
    fun groupCountIsDerivedAndCannotBeSetAtAnyCopySite() {
        val viewModel = readMainSource("com/buaa/schedule/ui/ScheduleViewModel.kt")
        assertFalse(
            "`conflictGroupCount` 还能被赋值（构造参数或 copy 实参）：PendingImport 有两处按子集重算 " +
                "conflicts 的 copy 站点，只要这枚数还是可以被带过去的字段，任何一处漏写它就把旧数留下 —— " +
                "T94 就是这么翻车的。它只能是 conflicts 的派生属性。",
            viewModel.contains("conflictGroupCount ="),
        )
        assertFalse(
            "`conflictGroupCount` 还挂在 PendingImport 的构造参数表上（`val conflictGroupCount: Int,`）：" +
                "参数表里的字段 = copy() 会原样带走的字段",
            viewModel.contains("val conflictGroupCount: Int,"),
        )
        assertTrue(
            "组数没有由 conflicts 派生（换了源就是换了把尺子，与首页/统计页那两页不同名了）",
            viewModel.contains("val conflictGroupCount: Int = CourseConflictResolution.groupConflicts(conflicts).size"),
        )
        assertEquals(
            "归并全站只该在 PendingImport 这一处落（第二处 = 两处各算各的数）",
            1,
            occurrences(viewModel, "CourseConflictResolution.groupConflicts("),
        )
        assertEquals(
            "两处逐条勾选的 copy 站点都还在按子集重算 conflicts（少了就是勾了不重算）",
            2,
            occurrences(viewModel, "conflicts = ConflictDetector.findConflicts(selection.toWrite)"),
        )
        assertTrue(
            "确认卡标题读的仍是 PendingImport 上那枚组数（改回界面就地归并 = 第四处各算各的）",
            readMainSource("com/buaa/schedule/ui/importing/ImportScreen.kt")
                .contains("importConflictBanner(pending.conflictGroupCount)"),
        )
    }

    // ---- 源码核对小工具（与同族守卫一个刀法）----

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
