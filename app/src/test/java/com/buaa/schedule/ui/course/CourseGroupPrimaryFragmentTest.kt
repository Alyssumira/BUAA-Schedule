package com.buaa.schedule.ui.course

import com.buaa.schedule.domain.model.Course
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * T207 病灶 A：课表管理页「整组门面由哪一枚碎片供」的判据，表驱动纯 JVM 单测。
 *
 * 改前那一格（`ui/course/CourseManagementScreen.kt` 里 `groupCourses` 的 `primary`）是
 * `fragments.minByOrNull { it.startPeriod } ?: fragments.first()`，两病：
 * 1. `Course.startPeriod` 把空节次兜底成 **1**（它自己的 KDoc 就写着「别拿它当位置用」），
 *    于是**一门没有节次的碎片凭空占掉第 1 节、赢下整组的门面** —— 组名、教师、门面名
 *    全取自那枚"根本没有时间"的碎片；
 * 2. `?: fragments.first()` 是不可达支（`groupBy` 的 value 必非空）。
 *
 * 现判据（[primaryFragmentOf]）：**没有节次的碎片不参与"谁最先上"的比较**，四档
 * 有没有节次 → 节次 → 星期 → 行 id。本表逐档摆格子，每格都同时核对
 * 「挑中的是哪一枚」与「组卡面念出来的是它的哪三格」。
 *
 * ## 「并列可能不可能」这一格的判定（依据写在测试里，不靠卡面口径）
 *
 * 读 [groupKeyOf] 的键形就能判死，两族键各有各的并列法，**并列是常态而不是假风险**：
 * - 导入组（`sourceGroupKey` 非空）：键是「学期|教学班|课序号」，**不含星期与节次** ⇒
 *   同组可跨几天几节；而同一条教务记录里「一个教师 × 一个周次段」拆出的多枚片段
 *   星期与节次全同 ⇒ **节次档必然并列**（下面 R4/R5 两格）。
 * - 手动组（键是「manual:名称|星期|节次」）：键已经把星期与节次钉死 ⇒
 *   组内每一枚的节次与星期都相等 ⇒ **整组并列**（R6 那一格连带把那枚"不可能"钉死：
 *   手动组里"靠节次分出胜负"不可能，因为节次本身就在键里）。
 *
 * 只做源码级判据（JVM，无 Robolectric、无设备、不读时钟）。
 */
class CourseGroupPrimaryFragmentTest {

    private data class Case(
        val label: String,
        val fragments: List<Course>,
        val expectedPrimaryId: Long,
        val expectedName: String,
        val expectedTeacher: String?,
        val expectedDisplayName: String,
    )

    @Test
    fun `门面取自哪一枚碎片 九格表驱动`() {
        for (case in CASES) {
            val groups = groupCourses(case.fragments, query = "")
            assertEquals("${case.label}：应当只归并出一组", 1, groups.size)
            val group = groups.single()
            val primary = case.fragments.first { it.id == case.expectedPrimaryId }

            assertEquals("${case.label}：门面碎片的 id", case.expectedPrimaryId, primaryFragmentOf(group.fragments).id)
            assertEquals("${case.label}：组名取自门面碎片", primary.name, group.name)
            assertEquals("${case.label}：教师取自门面碎片", primary.teacher, group.teacher)
            assertEquals("${case.label}：门面向名也取自同一枚碎片（组内无别名时）", case.expectedDisplayName, group.displayName)
            assertEquals("${case.label}：碎片枚数不许被挑门面时吞掉", case.fragments.size, group.fragments.size)
        }
    }

    /**
     * 挑门面**不许吃列表序**：把输入顺序整列倒过来（倒序 = 数据库返回序换一趟），
     * 三格门面字段必须逐字不变。改前 R1/R4 两格在倒序下会换人 —— 那正是"用户看不见也控制不了"
     * 的那一格（`minByOrNull` 的并列语义回列表首枚）。
     */
    @Test
    fun `输入顺序整列倒过来 门面三格一字不变`() {
        for (case in CASES) {
            val forward = groupCourses(case.fragments, query = "").single()
            val reversed = groupCourses(case.fragments.reversed(), query = "").single()
            assertEquals("${case.label}：倒序输入后组名不许换人", forward.name, reversed.name)
            assertEquals("${case.label}：倒序输入后教师不许换人", forward.teacher, reversed.teacher)
            assertEquals("${case.label}：倒序输入后门面向名不许换人", forward.displayName, reversed.displayName)
            assertEquals(
                "${case.label}：倒序输入后挑中的碎片 id 必须还是同一枚",
                primaryFragmentOf(forward.fragments).id,
                primaryFragmentOf(reversed.fragments).id,
            )
        }
    }

    /**
     * 并列档的**根据**（不是"我们假设有并列"）：并列那几格里，参与比较的键逐枚相等，
     * 而 `groupKeyOf` 交出的键也相等（读 [CourseGroup.key]，它是同一枚键的原样端出），
     * ⇒ 并列由键形造成，只能由后续档（星期 → id）收口。
     */
    @Test
    fun `并列那一档的键真的相等 依据是键形而不是猜测`() {
        val importedPair = listOf(
            course(id = 11, name = "高数", day = 1, periods = listOf(1, 2), groupKey = "G1", teacher = "王"),
            course(id = 12, name = "高数", day = 1, periods = listOf(1, 2), groupKey = "G1", teacher = "李"),
        )
        val manualPair = listOf(
            course(id = 21, name = "自习", day = 1, periods = listOf(1, 2), weeks = (1..8).toList(), teacher = "甲"),
            course(id = 22, name = "自习", day = 1, periods = listOf(1, 2), weeks = (9..16).toList(), teacher = "乙"),
        )

        val importedGroup = groupCourses(importedPair, query = "").single()
        val manualGroup = groupCourses(manualPair, query = "").single()

        for (pair in listOf(importedPair, manualPair)) {
            val periods = pair.map { it.firstPeriodOrNull }.distinct()
            val days = pair.map { it.dayOfWeek }.distinct()
            assertEquals("${pair.first().name}：节次档并列（firstPeriodOrNull 相等）", 1, periods.size)
            assertEquals("${pair.first().name}：星期档也并列", 1, days.size)
            // 手动组的键把星期与节次钉死 ⇒ 「靠节次在手动组里分出胜负」这一档**不可能**
            assertEquals("手动组里两枚的 startPeriod 也相等（列序那把尺比不出东西）", pair[0].startPeriod, pair[1].startPeriod)
        }
        assertEquals("导入组的键就是 sourceGroupKey 原样（不含星期与节次 ⇒ 并列来自片段而非键）", "G1", importedGroup.key)
        assertEquals(
            "手动组的键形 = 名称|星期|节次 ⇒ 组内节次与星期由键锁死（这一格是「手动组里靠节次分胜负不可能」的依据）",
            "manual:自习|1|1,2",
            manualGroup.key,
        )

        // 反面对照：导入组里节次**能**分出胜负的那一格（跨天跨节），键仍然唯一
        val acrossDays = listOf(
            course(id = 31, name = "高数", day = 1, periods = listOf(5, 6), groupKey = "G2"),
            course(id = 32, name = "高数", day = 3, periods = listOf(1, 2), groupKey = "G2"),
        )
        assertEquals("同键跨天跨节仍是同一组", 1, groupCourses(acrossDays, query = "").size)
        assertEquals("键相同而节次不同 ⇒ 并列只发生在节次相等的那些格上", listOf(5, 1), acrossDays.map { it.firstPeriodOrNull })
        assertEquals("这一格挑中早节次那枚（本卡没改这一档的轴向）", 32L, primaryFragmentOf(acrossDays).id)
        assertNotEquals("两枚碎片的节次不许被写成同一个值来骗过上一格", acrossDays[0].firstPeriodOrNull, acrossDays[1].firstPeriodOrNull)
    }

    /**
     * 门面向名的**别名优先**那一格不改：别名可能只登记在某个片段上，
     * 组里只要有一枚带非空别名就以它为准，跟门面挑中谁无关（`groupDisplayNameOf` 的既有语义）。
     */
    @Test
    fun `组里任一枚带别名时门面向名仍走别名优先`() {
        val aliasOnUntimed = groupCourses(
            listOf(
                course(id = 51, name = "大学物理", day = 1, periods = emptyList(), groupKey = "G3", teacher = "甲", alias = "物理"),
                course(id = 52, name = "大学物理实验", day = 4, periods = listOf(7, 8), groupKey = "G3", teacher = "乙"),
            ),
            query = "",
        ).single()

        assertEquals("门面挑中有节次的那枚", 52L, primaryFragmentOf(aliasOnUntimed.fragments).id)
        assertEquals("组名仍取门面碎片的教务原名", "大学物理实验", aliasOnUntimed.name)
        assertEquals("乙", aliasOnUntimed.teacher)
        assertEquals("别名优先那格读的是全组，不受门面影响", "物理", aliasOnUntimed.displayName)
    }

    /**
     * 卡面**列序**那把尺（星期 → `startPeriod`）本卡不动：没节次的碎片在列序里照常被
     * `startPeriod` 的兜底 1 顶到同星期第一段 —— 那是"排在谁前面"的次序问题，
     * `Course.startPeriod` 的 KDoc 明确允许这么用；本卡只把"谁当门面"那一格换掉。
     */
    @Test
    fun `列序那把尺一字未动 没节次的碎片照样参与排序`() {
        val group = groupCourses(
            listOf(
                course(id = 62, name = "高数", day = 1, periods = emptyList(), groupKey = "G4", teacher = "丁"),
                course(id = 63, name = "高数", day = 1, periods = listOf(9, 10), groupKey = "G4", teacher = "戊"),
                course(id = 61, name = "高数", day = 3, periods = listOf(1, 2), groupKey = "G4", teacher = "丙"),
            ),
            query = "",
        ).single()

        assertEquals("列序 = 星期先、再按 startPeriod（兜底 1 只在这里当次序用）", listOf(62L, 63L, 61L), group.fragments.map { it.id })
        assertEquals("门面却不属于那枚没节次的碎片，也不属于列首那枚", 61L, primaryFragmentOf(group.fragments).id)
        // 如实记下这一格盘面：卡面色标 / 共享元素 / 编辑目标念的是 `group.fragments.first()`（列首那枚），
        // 与门面无一枚同源保证 —— 把两者并成一枚要动列序那把尺，超出本卡点名的调用点那一格（残账）。
        assertEquals("列首那枚是没节次的碎片（卡片色与编辑目标读的就是它）", 62L, group.fragments.first().id)
        assertNotEquals("门面与列首并不同源（本卡不顺手统一，也不许有人以为已经统一了）", group.fragments.first().id, primaryFragmentOf(group.fragments).id)
    }

    /**
     * 空列表**不兜底**：改前那枚 `?: fragments.first()` 是不可达支（`groupBy` 的 value 必非空），
     * 现在真退化了当场响 —— 静默挑一枚正是 T193 第 2 条那一族。
     */
    @Test
    fun `空碎片列表当场响而不是静默挑一枚`() {
        assertThrows(NoSuchElementException::class.java) { primaryFragmentOf(emptyList()) }
    }

    private companion object {
        fun course(
            id: Long,
            name: String,
            day: Int,
            periods: List<Int>,
            weeks: List<Int> = (1..16).toList(),
            groupKey: String? = null,
            teacher: String? = null,
            alias: String? = null,
        ) = Course(
            id = id,
            name = name,
            alias = alias,
            teacher = teacher,
            dayOfWeek = day,
            periods = periods,
            weeks = weeks,
            sourceGroupKey = groupKey,
        )

        fun untimed(id: Long, name: String, day: Int, teacher: String?, groupKey: String? = null) =
            course(id = id, name = name, day = day, periods = emptyList(), groupKey = groupKey, teacher = teacher)

        val CASES: List<Case> = listOf(
            // R1 主病灶：组里有一枚没节次，且它就在列表首位（改前 startPeriod 兜底 1 赢下比较）
            Case(
                label = "R1 组里有碎片没节次（它在列表首位）",
                fragments = listOf(
                    untimed(id = 1, name = "没有时间的课", day = 1, teacher = "甲", groupKey = "G1"),
                    course(id = 2, name = "高数", day = 2, periods = listOf(3, 4), groupKey = "G1", teacher = "乙"),
                ),
                expectedPrimaryId = 2,
                expectedName = "高数",
                expectedTeacher = "乙",
                expectedDisplayName = "高数",
            ),
            // R2 同一格的换序版：没节次的那一枚排在列表末位，挑中的还是那枚有节次的
            Case(
                label = "R2 组里有碎片没节次（它在列表末位）",
                fragments = listOf(
                    course(id = 2, name = "高数", day = 2, periods = listOf(3, 4), groupKey = "G1", teacher = "乙"),
                    untimed(id = 1, name = "没有时间的课", day = 1, teacher = "甲", groupKey = "G1"),
                ),
                expectedPrimaryId = 2,
                expectedName = "高数",
                expectedTeacher = "乙",
                expectedDisplayName = "高数",
            ),
            // R3 整组都没节次（手动组）：头两档比不出东西 ⇒ 星期也并列，由 id 收口（改前吃列表序）
            Case(
                label = "R3 组内全部没节次（手动组：键把星期与节次都钉死了）",
                fragments = listOf(
                    untimed(id = 9, name = "自习", day = 2, teacher = "辛"),
                    untimed(id = 5, name = "自习", day = 2, teacher = "己"),
                    untimed(id = 7, name = "自习", day = 2, teacher = "庚"),
                ),
                expectedPrimaryId = 5,
                expectedName = "自习",
                expectedTeacher = "己",
                expectedDisplayName = "自习",
            ),
            // R3b 整组都没节次、但同 groupKey 的几枚星期不同 ⇒ 由「星期 → id」收口
            Case(
                label = "R3b 组内全部没节次 + 星期不同（导入组那一族的键不含星期）",
                fragments = listOf(
                    untimed(id = 7, name = "研讨", day = 3, teacher = "庚", groupKey = "G8"),
                    untimed(id = 9, name = "研讨", day = 2, teacher = "辛", groupKey = "G8"),
                    untimed(id = 5, name = "研讨", day = 2, teacher = "己", groupKey = "G8"),
                ),
                expectedPrimaryId = 5,
                expectedName = "研讨",
                expectedTeacher = "己",
                expectedDisplayName = "研讨",
            ),
            // R4 导入组里「同天同节次两枚教师」⇒ 节次档必然并列，由 id 定序
            Case(
                label = "R4 导入组并列（同星期同节次，两个教师×周次段）",
                fragments = listOf(
                    course(id = 11, name = "高数", day = 1, periods = listOf(1, 2), groupKey = "G5", teacher = "王"),
                    course(id = 12, name = "高数", day = 1, periods = listOf(1, 2), groupKey = "G5", teacher = "李"),
                ),
                expectedPrimaryId = 11,
                expectedName = "高数",
                expectedTeacher = "王",
                expectedDisplayName = "高数",
            ),
            // R5 换序版：并列那格里列表首枚换成 id 大的那枚，挑中的仍是 id 小的
            Case(
                label = "R5 导入组并列 + 输入倒序（不吃数据库返回序）",
                fragments = listOf(
                    course(id = 12, name = "高数", day = 1, periods = listOf(1, 2), groupKey = "G5", teacher = "李"),
                    course(id = 11, name = "高数", day = 1, periods = listOf(1, 2), groupKey = "G5", teacher = "王"),
                ),
                expectedPrimaryId = 11,
                expectedName = "高数",
                expectedTeacher = "王",
                expectedDisplayName = "高数",
            ),
            // R6 手动组：键把星期与节次钉死 ⇒ 整组并列
            Case(
                label = "R6 手动组整组并列（键含星期与节次）",
                fragments = listOf(
                    course(id = 21, name = "自习", day = 1, periods = listOf(1, 2), weeks = (1..8).toList(), teacher = "甲"),
                    course(id = 22, name = "自习", day = 1, periods = listOf(1, 2), weeks = (9..16).toList(), teacher = "乙"),
                ),
                expectedPrimaryId = 21,
                expectedName = "自习",
                expectedTeacher = "甲",
                expectedDisplayName = "自习",
            ),
            // R7 节次档照常分胜负（本卡没改这一档的轴向：仍按节次先后，不看星期）
            Case(
                label = "R7 有节次的两枚按节次先后定门面（星期更晚的那枚赢）",
                fragments = listOf(
                    course(id = 31, name = "高数", day = 1, periods = listOf(5, 6), groupKey = "G6", teacher = "甲"),
                    course(id = 32, name = "高数", day = 3, periods = listOf(1, 2), groupKey = "G6", teacher = "乙"),
                ),
                expectedPrimaryId = 32,
                expectedName = "高数",
                expectedTeacher = "乙",
                expectedDisplayName = "高数",
            ),
            // R8 没节次的那枚星期更早也不许当门面（改前：兜底 1 + 列表首位，双输）
            Case(
                label = "R8 没节次的那枚星期更早，照样不参与",
                fragments = listOf(
                    untimed(id = 41, name = "开学第一课", day = 1, teacher = "甲", groupKey = "G7"),
                    course(id = 42, name = "高等数学", day = 5, periods = listOf(2, 3), groupKey = "G7", teacher = "乙"),
                ),
                expectedPrimaryId = 42,
                expectedName = "高等数学",
                expectedTeacher = "乙",
                expectedDisplayName = "高等数学",
            ),
        )
    }
}
