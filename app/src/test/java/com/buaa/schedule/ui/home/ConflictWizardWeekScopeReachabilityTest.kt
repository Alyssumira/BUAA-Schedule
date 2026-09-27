package com.buaa.schedule.ui.home

import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.schedule.ConflictDetector
import com.buaa.schedule.domain.schedule.CourseConflictResolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T131 第 0 步：「组周 ⊊ target.weeks」这一格到底排不排得出来 —— 用**真码**算，不复述任何模型。
 *
 * 病状（T130 报的，本卡逐字复核）：[applyConflictShift] 只 `copy(periods = …)`、不动 `weeks`，
 * 而 [com.buaa.schedule.ui.ScheduleViewModel.updateCourse] 的闸门第三项是
 * `original.weeks != course.weeks` —— `original` 就是按 `course.id` 读回来的那一行，
 * 于是「那一判」在今天的写点上**恒为假**，`partialWeeks = true` 那枚旗标走不到
 * `updateCoursePartialWeeks`，落库退成整行覆盖（连不冲突的周一起挪）。
 *
 * 但「旗标空」不等于「收窄之后行为会变」：只有当**组周真是 target 那一行周次的真子集**时，
 * 把作用域换成 `组周 ∩ target.weeks` 才真的少改东西。这一格排不出来，本卡就该判成 (C)
 * （只剩文案与 options 名实不副）。所以这里不写模型、不誊判据，直接吃生产函数那三件：
 *   [ConflictDetector.findConflicts]（哪两两撞、撞在第几周）
 *   [CourseConflictResolution.groupConflicts]（谁和谁算一组、组的周并集怎么来的）
 *   [CourseConflictResolution.suggestNearestFreeShift]（那颗按钮出不出场）
 * 输入是课表本身，与首页/统计页喂给向导的那两份同源（`state.conflicts` 归并、`state.courses` 当 others）。
 *
 * 全程零 android、零时钟：只验「排不排得出来」，不验「装机上第几周」。
 * ⚠️ 这是 JVM 里跑生产纯函数，**不是装机实测**。
 */
class ConflictWizardWeekScopeReachabilityTest {

    private fun course(
        id: Long,
        name: String,
        periods: List<Int>,
        weeks: List<Int>,
        day: Int = 1,
    ) = Course(
        id = id,
        name = name,
        dayOfWeek = day,
        periods = periods,
        weeks = weeks,
    )

    /** 组周与 target.weeks 的三种关系，本文件只认这三档 */
    private enum class Relation { EQUAL, STRICT_SUBSET, NOT_SUBSET }

    /**
     * 把课表喂进真码，取出「向导第一行」的 target 与它所属组的周次。
     *
     * 这里刻意复刻 [ConflictWizardDialog] 那一行的取法：`group.courses.firstOrNull()`，
     * 而组内次序来自 `CourseConflictResolution.groupConflicts` 的
     * `sortedBy { it.startPeriod }` —— **target 由节次最早决定，与谁的周次更宽无关**。
     * 这正是「组周恰好不是 target 的周次」能排出来的机制。
     */
    private class WizardRow(
        val target: Course,
        val groupWeeks: List<Int>,
        val suggestion: CourseConflictResolution.ShiftSuggestion?,
        val relation: Relation,
        /** target 里那些「整组谁都没撞」的周：整行覆盖会连它们一起挪走 */
        val weeksWithoutAnyConflict: List<Int>,
    )

    private fun firstWizardRow(courses: List<Course>): WizardRow {
        val conflicts = ConflictDetector.findConflicts(courses)
        val groups = CourseConflictResolution.groupConflicts(conflicts)
        assertTrue("这格根本组不出冲突组，向导一行都不出", groups.isNotEmpty())
        val group = groups.first()
        val target = group.courses.firstOrNull() ?: error("空组")
        val relation = when {
            group.weeks == target.weeks -> Relation.EQUAL
            target.weeks.containsAll(group.weeks) -> Relation.STRICT_SUBSET
            else -> Relation.NOT_SUBSET
        }
        // target 在哪些周里其实谁也没撞：把那一周的课单独喂回 findConflicts
        val untouched = target.weeks.filter { week ->
            ConflictDetector.findConflicts(
                courses.mapNotNull { c -> if (week in c.weeks) c.copy(weeks = listOf(week)) else null }
            ).isEmpty()
        }
        return WizardRow(
            target = target,
            groupWeeks = group.weeks,
            suggestion = CourseConflictResolution.suggestNearestFreeShift(target, courses),
            relation = relation,
            weeksWithoutAnyConflict = untouched,
        )
    }

    // ---- ① 组周 == target.weeks：今天的空枪与甲的写法在这一格**同解** ----

    @Test
    fun equalWhenBothCoursesSpanTheSameWeeks() {
        val row = firstWizardRow(
            listOf(
                course(id = 1, name = "A", periods = listOf(1, 2), weeks = (1..16).toList()),
                course(id = 2, name = "B", periods = listOf(2, 3), weeks = (1..16).toList()),
            )
        )
        assertEquals(Relation.EQUAL, row.relation)
        assertEquals((1..16).toList(), row.groupWeeks)
        assertNotNull("这一格按钮都不出，就构不成名实不副", row.suggestion)
        // 整门课都在冲突里 ⇒ 挪整门课就是挪全部冲突周，无「被悄悄改掉的无辜周」
        assertTrue(row.weeksWithoutAnyConflict.isEmpty())
    }

    // ---- ② 组周 ⊊ target.weeks：这一格排得出来，且按钮真的出场 ----

    @Test
    fun strictSubsetWhenOnlyAPartOfTheTargetsWeeksIsInvolved() {
        // A 上满 1-16 周，B 只有 3-5 周，两门在 Monday 第 2 节咬住
        val row = firstWizardRow(
            listOf(
                course(id = 1, name = "A", periods = listOf(1, 2), weeks = (1..16).toList()),
                course(id = 2, name = "B", periods = listOf(2, 3), weeks = listOf(3, 4, 5)),
            )
        )
        assertEquals(
            "「组周 ⊊ target.weeks」这一格排得出来：组周 = 撞上的那三周，target 却排满 16 周",
            Relation.STRICT_SUBSET,
            row.relation,
        )
        assertEquals(listOf(3, 4, 5), row.groupWeeks)
        assertEquals((1..16).toList(), row.target.weeks)
        assertNotNull("按钮（只改这些周）在这一格确实出场，用户看得见那句承诺", row.suggestion)

        // 损害边界：target 里有一批周整组谁也没撞，整行覆盖照样把它们挪走了
        assertEquals(
            "除冲突三周以外，target 的另外 13 周本来一节都不撞",
            (listOf(1, 2) + (6..16)).toList(),
            row.weeksWithoutAnyConflict,
        )
    }

    // ---- ③ 传递闭包那一格：组周既可能等于、也可能**盖到 target 外面去** ----

    @Test
    fun chainCanPushGroupWeeksOutsideTheTargetsOwnWeeks() {
        // A(1-8, 1-2 节) 撞 B(1-16, 2-3 节)、B 撞 C(9-16, 3-4 节)，A 与 C 节次不重叠
        // ⇒ 传递闭包把三门归成一组，组周 = 1-16
        // target = startPeriod 最早的那门 = A（只排 1-8 周）
        val row = firstWizardRow(
            listOf(
                course(id = 1, name = "A", periods = listOf(1, 2), weeks = (1..8).toList()),
                course(id = 2, name = "B", periods = listOf(2, 3), weeks = (1..16).toList()),
                course(id = 3, name = "C", periods = listOf(3, 4), weeks = (9..16).toList()),
            )
        )
        assertEquals("组周 1-16 超出了 target 自己的周次 ⇒ 不是子集", Relation.NOT_SUBSET, row.relation)
        assertEquals((1..16).toList(), row.groupWeeks)
        assertEquals(
            "target 由 sortedBy { startPeriod } 决定，与周次宽窄无关：这里拿到的是只排 1-8 周的那门",
            (1..8).toList(),
            row.target.weeks,
        )
        // 交集恰好等于 target 的全部周次 ⇒ 这一格收窄是 no-op，甲与今天同解（不拆行）
        assertTrue(row.target.weeks.containsAll(row.groupWeeks.filter { it in row.target.weeks }))
        assertEquals(row.target.weeks, row.groupWeeks.filter { it in row.target.weeks }.sorted())
    }

    /**
     * ⊊ 也发生在链条格里：target 是最宽的那门，两边各撞一段，中间那段谁也没撞。
     * 这一格是甲真正少改东西的证据（今天整行覆盖会把中间无辜段一起挪走）。
     */
    @Test
    fun subsetAlsoArisesInsideATwoSidedChain() {
        val row = firstWizardRow(
            listOf(
                course(id = 1, name = "A", periods = listOf(2, 3), weeks = (1..4).toList()),
                course(id = 2, name = "B", periods = listOf(1, 2), weeks = (1..16).toList()),
                course(id = 3, name = "C", periods = listOf(2, 3), weeks = (9..12).toList()),
            )
        )
        // target = B（startPeriod 1 最早），组周 = 1-4 ∪ 9-12
        assertEquals("B", row.target.name)
        assertEquals(Relation.STRICT_SUBSET, row.relation)
        assertEquals((1..4).toList() + (9..12).toList(), row.groupWeeks)
        assertNotNull(row.suggestion)
        assertEquals(
            "5-8 与 13-16 这两段谁也没撞，却在今天的整行覆盖里被一起挪走",
            (5..8).toList() + (13..16).toList(),
            row.weeksWithoutAnyConflict,
        )
    }

    /**
     * 空枪机制本身：`original` 是按 `course.id` 读回来的那一行，
     * 所以 `copy(periods = …)` 之后 `weeks` 必然仍与 `original.weeks` 相等 ——
     * 这一判与课表长什么样**无关**，恒为假。用真码把「恒等」这一档在四格上都验一遍。
     */
    @Test
    fun copyOfPeriodsNeverChangesWeeksSoTheGateIsAlwaysFalse() {
        val targets = listOf(
            course(id = 1, name = "A", periods = listOf(1, 2), weeks = (1..16).toList()),
            course(id = 2, name = "B", periods = listOf(2, 3), weeks = listOf(3, 4, 5)),
            course(id = 3, name = "C", periods = listOf(1, 2), weeks = (1..8).toList()),
        )
        targets.forEach { target ->
            val written = target.copy(periods = listOf(7, 8))
            assertEquals(
                "${target.name}：只改节次以后 weeks 逐字不变 ⇒ updateCourse 第三判必假",
                target.weeks,
                written.weeks,
            )
        }
    }
}
