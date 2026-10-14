package com.buaa.schedule.ui.course

import com.buaa.schedule.domain.model.Course
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * T383 裁的是「管理页在屏上把手写第二遍的『别名非空才作数』收回收」这一格。
 * 收完之后两手的关系是**一半同源、一半天生不同**，本表钉的就是行为那一半（纯 JVM，
 * 零 android import，不读钟、不读时区、不读权限、不读设备度量）。
 *
 * 两手与出处（读的是产物，不复制任何一手的表达式 —— 复制过来本文件就成了第三遍）：
 * - 实体那一手：[Course.displayName]，只认**这一行自己**的别名；
 * - 组那一手：[CourseGroup.displayName]，由 [groupCourses] 在归并点算完交回来
 *   （那一手在管理页里，扫的是整组的别名）。
 * 同源的那一档「别名非空才作数」只有一枚落点 = [Course.displayNameOrNull]，
 * **源码级**的钉子（真源体、回退形、第二遍探测器、类体同级声明册子）在
 * `domain/model/CourseDisplayNameSingleSourceGuardTest`，本文件不重复钉源码、只钉行为。
 *
 * ## 为什么不许把两手统一成一枚（第⓪问裁乙档否决的理由，摆在这儿而不是埋在卡面里）
 *
 * 编辑器逐片段保存，别名确实可以只挂在非 primary 的那一枚片段上；而整组门面由
 * [primaryFragmentOf] 按「有没有节次 → 节次 → 星期 → 行 id」挑，挑中的那枚完全可能没有别名。
 * 于是**同一行数据**上：组那一手念别名，实体那一手念教务原名。把组那一手改成
 * 「只读 primary 的 displayName」，用户挂在实验段上的别名就在管理页念不出来 —— 那是行为改变。
 * 本表把这一档摆成充要条件（见 `两手翻面的充要条件` 那一格），其余档必须给同一答案。
 */
class CourseDisplayNameRelationTableTest {

    private data class Row(
        val label: String,
        val fragments: List<Course>,
        /** 组那一手交回来的门面名 */
        val expectedGroupDisplayName: String,
        /** 实体那一手在门面碎片自己那一行上的答案 */
        val expectedPrimaryDisplayName: String,
    )

    @Test
    fun `十格逐档 组取法与实体取法各念什么`() {
        for (row in ROWS) {
            val groups = groupCourses(row.fragments, query = "")
            assertEquals("${row.label}：这些片段应当归并成一门课", 1, groups.size)
            val group = groups.single()
            val primary = primaryFragmentOf(group.fragments)

            assertEquals(
                "${row.label}：组那一手的门面名。切块原文（管理页归并点那一手，抹注释后逐字）：" +
                    "fragments.firstNotNullOfOrNull { it.displayNameOrNull } ?: primary.displayName",
                row.expectedGroupDisplayName,
                group.displayName,
            )
            assertEquals(
                "${row.label}：实体那一手在门面碎片上的显示名。切块原文（Course.kt 类体，抹注释后逐字）：" +
                    "get() = displayNameOrNull ?: name",
                row.expectedPrimaryDisplayName,
                primary.displayName,
            )
            assertEquals("${row.label}：卡面上的片段枚数不许被挑门面时吞掉", row.fragments.size, group.fragments.size)
        }
    }

    /**
     * 关系本体（本卡唯一真源裁定要的正是这一格）：两手**当且仅当**「门面碎片自己没有可用别名、
     * 而组里另有片段有」时给不同答案；其余档一律同答案。
     *
     * 判据吃的是 [Course.displayNameOrNull] 那一枚真源，不是在测试里再写一遍 trim 判空 ——
     * 再写一遍就把本卡的病灶搬进测试树。朝宽扭（把组那一手改成只读 primary）与朝窄扭
     * （把实体那一手改成扫全组）都会红在这一格。
     */
    @Test
    fun `两手翻面的充要条件 只有别名挂在非门面碎片那一档`() {
        for (row in ROWS) {
            val group = groupCourses(row.fragments, query = "").single()
            val primary = primaryFragmentOf(group.fragments)
            val differs = group.displayName != primary.displayName
            val expected = primary.displayNameOrNull == null &&
                group.fragments.any { it.id != primary.id && it.displayNameOrNull != null }

            assertEquals(
                "${row.label}：翻不翻面的根据 —— 门面碎片自己没有可用别名、而组里另有片段有，" +
                    "才许翻面；其余档两手必须同答案。实到 differs = $differs ／ 根据 = $expected" +
                    "（门面碎片 ${primary.id} 号、别名 ${primary.displayNameOrNull}）",
                expected,
                differs,
            )
        }
    }

    /**
     * 组里两枚片段各带一枚**不同**的非空别名时，门面名读的是**归并点那一手**的次序
     * （`SemesterStats.creditsByCourse` 交回来的输入序），不是卡面上排过序的
     * [CourseGroup.fragments]（星期 → 节次）。
     *
     * 这一格是「门面向名为什么不搬进 [CourseGroup] 类体当属性」的根据：搬进去读的就是排过序的
     * 那一手，`firstNotNullOfOrNull` 会念出另一枚别名 —— 派生字段的落点因此留在构造点，
     * 源码那一侧由 `domain/model/CourseDisplayNameSingleSourceGuardTest` 的构造点那格钉住。
     */
    @Test
    fun `两枚片段各带一枚别名时 门面名读归并点次序不读卡面列序`() {
        val friday = course(11, "高等数学A", day = 5, periods = listOf(10), alias = "周五那一段的别名")
        val monday = course(12, "高等数学A", day = 1, periods = listOf(1), alias = "周一那一段的别名")
        val group = groupCourses(listOf(friday, monday), query = "").single()

        assertEquals(
            "归并点那一手（输入序：周五的片段在前）念的是周五那枚别名",
            "周五那一段的别名",
            group.displayName,
        )
        assertNotEquals(
            "卡面列序（星期 → 节次）把周一那一段排到了前面，同一把 firstNotNullOfOrNull 在那一手上" +
                "会念出**另一枚**别名 —— 门面名要是改成读卡面那一手就在这儿翻面。" +
                "卡面第一枚非空别名实到：" + group.fragments.firstNotNullOfOrNull { it.displayNameOrNull },
            group.displayName,
            group.fragments.firstNotNullOfOrNull { it.displayNameOrNull },
        )
        assertEquals(
            "卡面列序第一枚是周一那一段（星期档先分胜负）",
            12L,
            group.fragments.first().id,
        )
    }

    /**
     * 组里没有任何可用别名时，门面名**逐字等于**组名（= 门面碎片的教务原名），一个字符都不许多。
     * 这一格是回退那一手的形状：回退的是门面碎片的教务原名，不是别名、不是空格、不是空串。
     *
     * 选档吃的是数据自己（组里每一枚片段的 [Course.displayNameOrNull] 都为 null），不是"两手答案相同"
     * 那枚弱条件 —— 后者会把「别名挂在门面碎片自己身上」那两档也选进来，那两档的门面名当然不等于组名。
     * 判据同样只读真源，不在测试里再写一遍 trim 判空。
     */
    @Test
    fun `组内没有可用别名时 门面名逐字等于组名`() {
        val rows = ROWS.filter { row -> row.fragments.none { it.displayNameOrNull != null } }
        assertEquals(
            "表里「全组都没有可用别名」那一族必须非空（选空了下面就是真空通过）：实到 " + rows.size + " 档",
            true,
            rows.isNotEmpty(),
        )
        for (row in rows) {
            val group = groupCourses(row.fragments, query = "").single()
            assertEquals(
                "${row.label}：没有可用别名时门面名就该是组名本身，实到「${group.displayName}」／组名「${group.name}」",
                group.name,
                group.displayName,
            )
        }
    }

    /**
     * 真源自己那一档的四格（[Course.displayNameOrNull]）：null / 空串 / 全空白都算「没有别名」，
     * 带空白的别名只 trim 不改字。实体那一手与组那一手共吃的就是这一档，
     * 所以这一格红了等于两手的「别名非空才作数」一起漂了。
     */
    @Test
    fun `真源那一档四格 空别名一律不作数`() {
        assertNull("别名缺项就是不作数", course(21, "高等数学A", alias = null).displayNameOrNull)
        assertEquals(
            "空串别名不作数（作数的话实体那一手会念出空名字）",
            null,
            course(22, "高等数学A", alias = "").displayNameOrNull,
        )
        assertEquals(
            "全空白别名不作数：实到 " + course(23, "高等数学A", alias = "   ").displayNameOrNull,
            null,
            course(23, "高等数学A", alias = "   ").displayNameOrNull,
        )
        assertEquals(
            "带空白的别名 trim 之后作数，但不许顺手改中间的字",
            "高数 A",
            course(24, "高等数学A", alias = "  高数 A ").displayNameOrNull,
        )

        assertEquals(
            "不作数那一档实体回退教务原名",
            "高等数学A",
            course(25, "高等数学A", alias = "   ").displayName,
        )
        assertEquals(
            "作数那一档实体念别名",
            "高数",
            course(26, "高等数学A", alias = " 高数 ").displayName,
        )
    }

    private fun course(
        id: Long,
        name: String,
        day: Int = 1,
        periods: List<Int> = listOf(1, 2),
        weeks: List<Int> = (1..16).toList(),
        groupKey: String? = "G1",
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

    private companion object {
        /** 门面碎片：id 1 那枚（有节次、节次最早、星期最早），别名一律挂在它身上或明确不挂 */
        private val TIMED_LEAD = Course(
            id = 1,
            name = "高等数学A",
            dayOfWeek = 1,
            periods = listOf(1, 2),
            weeks = (1..16).toList(),
            sourceGroupKey = "G1",
        )

        private val TIMED_SECOND = Course(
            id = 2,
            name = "高等数学A",
            dayOfWeek = 3,
            periods = listOf(6, 7),
            weeks = (1..16).toList(),
            sourceGroupKey = "G1",
        )

        private val UNTIMED = Course(
            id = 3,
            name = "高等数学A",
            dayOfWeek = 4,
            periods = emptyList(),
            weeks = (1..16).toList(),
            sourceGroupKey = "G1",
        )

        private val ROWS = listOf(
            Row(
                label = "别名只挂在非门面碎片上（唯一必须翻面的一档）",
                fragments = listOf(TIMED_LEAD, TIMED_SECOND.copy(alias = "高数")),
                expectedGroupDisplayName = "高数",
                expectedPrimaryDisplayName = "高等数学A",
            ),
            Row(
                label = "别名挂在门面碎片自己身上",
                fragments = listOf(TIMED_LEAD.copy(alias = "高数"), TIMED_SECOND),
                expectedGroupDisplayName = "高数",
                expectedPrimaryDisplayName = "高数",
            ),
            Row(
                label = "全组都没有别名",
                fragments = listOf(TIMED_LEAD, TIMED_SECOND),
                expectedGroupDisplayName = "高等数学A",
                expectedPrimaryDisplayName = "高等数学A",
            ),
            Row(
                label = "非门面碎片的别名是全空白（trim 之后不作数）",
                fragments = listOf(TIMED_LEAD, TIMED_SECOND.copy(alias = "   ")),
                expectedGroupDisplayName = "高等数学A",
                expectedPrimaryDisplayName = "高等数学A",
            ),
            Row(
                label = "非门面碎片的别名是空串",
                fragments = listOf(TIMED_LEAD, TIMED_SECOND.copy(alias = "")),
                expectedGroupDisplayName = "高等数学A",
                expectedPrimaryDisplayName = "高等数学A",
            ),
            Row(
                label = "门面碎片的别名是全空白",
                fragments = listOf(TIMED_LEAD.copy(alias = " \t "), TIMED_SECOND),
                expectedGroupDisplayName = "高等数学A",
                expectedPrimaryDisplayName = "高等数学A",
            ),
            Row(
                label = "单片段组、别名挂在自己身上",
                fragments = listOf(TIMED_LEAD.copy(alias = "高数")),
                expectedGroupDisplayName = "高数",
                expectedPrimaryDisplayName = "高数",
            ),
            Row(
                label = "没有节次的那一枚带别名（它照样当不了门面碎片）",
                fragments = listOf(TIMED_LEAD, UNTIMED.copy(alias = "高数补")),
                expectedGroupDisplayName = "高数补",
                expectedPrimaryDisplayName = "高等数学A",
            ),
            Row(
                label = "两枚片段各带一枚不同的别名（门面碎片那一枚赢）",
                fragments = listOf(TIMED_LEAD.copy(alias = "高数甲"), TIMED_SECOND.copy(alias = "高数乙")),
                expectedGroupDisplayName = "高数甲",
                expectedPrimaryDisplayName = "高数甲",
            ),
            Row(
                // 回退那一手交回的是**教务原名原样**：实体不许顺手替它 trim（真源只管别名那一档）。
                // 这一格是变异 D（把回退改成 `?: name.trim()`）钉出来的：没有这一格，那一审只在守卫的
                // 字面针上响、行为侧一声不响，等于行为判据漏了一档。
                label = "教务原名自己带空白（回退念原样、不顺手裁它）",
                fragments = listOf(TIMED_LEAD.copy(name = "  高等数学A  ")),
                expectedGroupDisplayName = "  高等数学A  ",
                expectedPrimaryDisplayName = "  高等数学A  ",
            ),
        )
    }
}
