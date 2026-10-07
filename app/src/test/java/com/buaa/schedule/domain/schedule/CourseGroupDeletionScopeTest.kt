package com.buaa.schedule.domain.schedule

import com.buaa.schedule.domain.model.Course
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T253③：判据内核 `courseGroupDeletionScope` 的表驱动单测（纯 JVM，零 android、零设备、不读时钟）。
 *
 * ## 每格都要有、两侧都要有格子
 *
 * 这一族的病不是"算错一个数"，而是**屏上念的句子与真正执行的那一手分家**：搜索命中 2 段而
 * 整组 5 段时，按钮念「删除整门课」、弹窗念「全部 N 个片段」，交给仓储层的却是那 2 段。
 * 所以这张表把「朝宽扭一次」与「朝窄扭一次」都摆进格子：
 * - **朝宽**（甲档 `WholeCourse`）：过滤命中 2 / 整组 5 ⇒ 删 5、念 5，且 `narrowedByFilter` 必须
 *   如实报"过滤态真的收窄过"（那句文案要靠它）；把甲档那一支扭成交回命中片段 ⇒ 这一格当场红。
 * - **朝窄**（乙档 `MatchedFragmentsOnly`）：同一批数据 ⇒ 删 2、念 2；把乙档扭成交回整组 ⇒
 *   那一格当场红。两档各占一行、判的是同一条数据的两个方向，缺一侧就是半边判据没被人踩过。
 *
 * 另加一枚**逐行不变式**：`announcedFragmentCount == fragments.size`。它不许被人当"废话"删掉 ——
 * 本卡治的就是"念的数与删的集是两枚东西"，那一手一旦在函数体里被拆开（比如念整组、删命中），
 * 这一格是第二道 catch（第一道是调用点那侧的形状守卫）。
 */
class CourseGroupDeletionScopeTest {

    private fun course(
        id: Long,
        name: String,
        day: Int,
        periods: List<Int> = listOf(1, 2),
        teacher: String? = null,
        alias: String? = null,
        groupKey: String? = "G1",
    ) = Course(
        id = id,
        name = name,
        alias = alias,
        dayOfWeek = day,
        periods = periods,
        weeks = (1..16).toList(),
        sourceGroupKey = groupKey,
        teacher = teacher,
        semesterCode = "2025-2026-2",
    )

    /** 整组五段：三天三节，教师两两不同 —— 「搜索命中 2 / 整组 5」那一格的原料 */
    private fun fiveFragmentGroup(): List<Course> = listOf(
        course(11, "高等数学", day = 1, periods = listOf(1, 2), teacher = "张三"),
        course(12, "高等数学", day = 2, periods = listOf(3, 4), teacher = "张三"),
        course(13, "高等数学", day = 3, periods = listOf(3, 4), teacher = "李四"),
        course(14, "高等数学", day = 4, periods = listOf(5, 6), teacher = "李四"),
        course(15, "高等数学", day = 5, periods = listOf(7, 8), teacher = "王五"),
    )

    private class Row(
        val label: String,
        val wholeGroup: List<Course>,
        val query: String,
        val request: GroupDeletionRequest,
        /** 期望真正交给仓储层的那一组 id（顺序也判） */
        val expectDeletedIds: List<Long>,
        /** 期望弹窗念的那个数 */
        val expectAnnounced: Int,
        /** 期望命中的段数（`narrowedByFilter` 的分母） */
        val expectMatched: Int,
        val expectNarrowed: Boolean,
    )

    private val rows = listOf(
        // ── 朝宽那一侧：甲档在过滤态下不许少删 ──
        Row(
            label = "甲档 · 搜索命中 2 / 整组 5（本卡病灶那一格：删整组、念整组）",
            wholeGroup = fiveFragmentGroup(),
            query = "李四",
            request = GroupDeletionRequest.WholeCourse,
            expectDeletedIds = listOf(11L, 12L, 13L, 14L, 15L),
            expectAnnounced = 5,
            expectMatched = 2,
            expectNarrowed = true,
        ),
        Row(
            label = "甲档 · 搜索命中 1 / 整组 5",
            wholeGroup = fiveFragmentGroup(),
            query = "王五",
            request = GroupDeletionRequest.WholeCourse,
            expectDeletedIds = listOf(11L, 12L, 13L, 14L, 15L),
            expectAnnounced = 5,
            expectMatched = 1,
            expectNarrowed = true,
        ),
        Row(
            label = "甲档 · 搜索零命中（卡片本来不会出场，但判据不许借这一档少删）",
            wholeGroup = fiveFragmentGroup(),
            query = "赵六",
            request = GroupDeletionRequest.WholeCourse,
            expectDeletedIds = listOf(11L, 12L, 13L, 14L, 15L),
            expectAnnounced = 5,
            expectMatched = 0,
            expectNarrowed = true,
        ),
        Row(
            label = "甲档 · 搜索词命中整组每一段（过滤态成立却没有收窄）",
            wholeGroup = fiveFragmentGroup(),
            query = "高等数学",
            request = GroupDeletionRequest.WholeCourse,
            expectDeletedIds = listOf(11L, 12L, 13L, 14L, 15L),
            expectAnnounced = 5,
            expectMatched = 5,
            expectNarrowed = false,
        ),
        // ── 朝窄那一侧：乙档就是只删命中的那些 ──
        Row(
            label = "乙档 · 搜索命中 2 / 整组 5（同一批数据的反方向那一格）",
            wholeGroup = fiveFragmentGroup(),
            query = "李四",
            request = GroupDeletionRequest.MatchedFragmentsOnly,
            expectDeletedIds = listOf(13L, 14L),
            expectAnnounced = 2,
            expectMatched = 2,
            expectNarrowed = true,
        ),
        Row(
            label = "乙档 · 搜索零命中 ⇒ 交回空表（这一档就是「一段都不该删」，不许偷偷并成整组）",
            wholeGroup = fiveFragmentGroup(),
            query = "赵六",
            request = GroupDeletionRequest.MatchedFragmentsOnly,
            expectDeletedIds = emptyList(),
            expectAnnounced = 0,
            expectMatched = 0,
            expectNarrowed = true,
        ),
        Row(
            label = "乙档 · 搜索词命中整组每一段 ⇒ 与甲档同解（两档的差别只在有没有收窄）",
            wholeGroup = fiveFragmentGroup(),
            query = "高等数学",
            request = GroupDeletionRequest.MatchedFragmentsOnly,
            expectDeletedIds = listOf(11L, 12L, 13L, 14L, 15L),
            expectAnnounced = 5,
            expectMatched = 5,
            expectNarrowed = false,
        ),
        // ── 无过滤态：两档必须给同一个答案，否则「清空搜索框」就改变了一次删除范围 ──
        Row(
            label = "甲档 · 空搜索框",
            wholeGroup = fiveFragmentGroup(),
            query = "",
            request = GroupDeletionRequest.WholeCourse,
            expectDeletedIds = listOf(11L, 12L, 13L, 14L, 15L),
            expectAnnounced = 5,
            expectMatched = 5,
            expectNarrowed = false,
        ),
        Row(
            label = "乙档 · 空搜索框（与上面那甲档一字不差 ⇒ 无过滤态时两档同解）",
            wholeGroup = fiveFragmentGroup(),
            query = "",
            request = GroupDeletionRequest.MatchedFragmentsOnly,
            expectDeletedIds = listOf(11L, 12L, 13L, 14L, 15L),
            expectAnnounced = 5,
            expectMatched = 5,
            expectNarrowed = false,
        ),
        Row(
            label = "甲档 · 搜索框只有空格（归一之后 = 不过滤；不归一就长成「命中整组却算收窄」）",
            wholeGroup = fiveFragmentGroup(),
            query = "   ",
            request = GroupDeletionRequest.WholeCourse,
            expectDeletedIds = listOf(11L, 12L, 13L, 14L, 15L),
            expectAnnounced = 5,
            expectMatched = 5,
            expectNarrowed = false,
        ),
        // ── 边界形状 ──
        Row(
            label = "甲档 · 单片段组（整门课就一段，两档同解）",
            wholeGroup = listOf(course(21, "体育课", day = 2, teacher = "张三")),
            query = "张三",
            request = GroupDeletionRequest.WholeCourse,
            expectDeletedIds = listOf(21L),
            expectAnnounced = 1,
            expectMatched = 1,
            expectNarrowed = false,
        ),
        Row(
            label = "甲档 · 空组（没有片段可删：判据交回空表，早退那一手归 VM）",
            wholeGroup = emptyList(),
            query = "高等数学",
            request = GroupDeletionRequest.WholeCourse,
            expectDeletedIds = emptyList(),
            expectAnnounced = 0,
            expectMatched = 0,
            expectNarrowed = false,
        ),
        // ── 命中面：三个字段任一命中就算命中，大小写不敏感 ──
        Row(
            label = "甲档 · 按别名命中（别名只登记在两枚片段上时，命中的是那两枚）",
            wholeGroup = listOf(
                course(31, "高等数学A", day = 1, alias = "高数"),
                course(32, "高等数学A", day = 3, alias = "高数"),
                course(33, "高等数学A", day = 5, alias = null),
            ),
            query = "高数",
            request = GroupDeletionRequest.WholeCourse,
            expectDeletedIds = listOf(31L, 32L, 33L),
            expectAnnounced = 3,
            expectMatched = 2,
            expectNarrowed = true,
        ),
        Row(
            label = "乙档 · 按别名命中 ⇒ 只删那两枚（第三枚按教务原名搜才轮得到它）",
            wholeGroup = listOf(
                course(31, "高等数学A", day = 1, alias = "高数"),
                course(32, "高等数学A", day = 3, alias = "高数"),
                course(33, "高等数学A", day = 5, alias = null),
            ),
            query = "高数",
            request = GroupDeletionRequest.MatchedFragmentsOnly,
            expectDeletedIds = listOf(31L, 32L),
            expectAnnounced = 2,
            expectMatched = 2,
            expectNarrowed = true,
        ),
        Row(
            label = "乙档 · 大小写不敏感命中英文课名（同组另一枚不该被捎走）",
            wholeGroup = listOf(
                course(41, "C Language", day = 1),
                course(42, "体育", day = 3, teacher = "李四"),
            ),
            query = "c lang",
            request = GroupDeletionRequest.MatchedFragmentsOnly,
            expectDeletedIds = listOf(41L),
            expectAnnounced = 1,
            expectMatched = 1,
            expectNarrowed = true,
        ),
    )

    @Test
    fun `每一格 删哪几段 念哪个数 落在哪一档 两侧都有对照`() {
        for (row in rows) {
            val scope = courseGroupDeletionScope(row.wholeGroup, row.query, row.request)
            assertEquals(
                "「${row.label}」：交给仓储层的那一组（id 逐个对、顺序也对）",
                row.expectDeletedIds,
                scope.fragments.map { it.id },
            )
            assertEquals(
                "「${row.label}」：弹窗要念的那个数",
                row.expectAnnounced,
                scope.announcedFragmentCount,
            )
            assertEquals(
                "「${row.label}」：落在哪一档必须原样端回（不许折成一枚 Boolean）",
                row.request,
                scope.request,
            )
            assertEquals(
                "「${row.label}」：命中几段（那句「搜索只命中其中 K 段」的分子）",
                row.expectMatched,
                scope.matchedFragmentCount,
            )
            assertEquals(
                "「${row.label}」：过滤态有没有真的收窄（决定文案提不提那一句）",
                row.expectNarrowed,
                scope.narrowedByFilter,
            )
            assertEquals(
                "「${row.label}」：整组段数（收窄与否的分母，不许由调用点再数一遍）",
                row.wholeGroup.size,
                scope.wholeGroupFragmentCount,
            )
        }
    }

    /**
     * 逐行不变式：**念的那个数**必须就是**交出去那一段数**。
     *
     * 这不是套套逻辑的复述：内核里 `announcedFragmentCount` 是一枚独立的构造实参（不是
     * `fragments.size` 的派生属性），所以"念整组、删命中"那一型在函数体里写得出、也就红得了 ——
     * 与 `SemesterStats.CourseCredit.fragmentCount` 那处「写成派生属性」相反的取舍，理由是本卡
     * 要的就是这两枚各能被人扭坏一次（扭坏任何一枚都必须在格子里露出来）。
     */
    @Test
    fun `逐行不变式 念的数与删的集同一枚`() {
        for (row in rows) {
            val scope = courseGroupDeletionScope(row.wholeGroup, row.query, row.request)
            assertEquals(
                "「${row.label}」：announcedFragmentCount 必须等于 fragments.size（念的数与删的集分家 = 本卡的病）",
                scope.fragments.size,
                scope.announcedFragmentCount,
            )
        }
    }

    /**
     * 甲档与过滤态**无关**这件事，按"整表逐格"再判一遍：把搜索框里那句换成任何别的关键词，
     * 甲档交出去的那一组都不许变。
     *
     * 单独摆一枚用例而不是并进上面那张表：这一枚判的是"跨格相等"那一种形状，
     * 表里的格子只各自判自己那一格 —— 有人把甲档改成"命中少就删少"，表里那格红，
     * 而这一枚红得更快也更点名（它就是登记的那句病灶本身）。
     */
    @Test
    fun `甲档在任意过滤态下交回的都是同一组`() {
        val group = fiveFragmentGroup()
        val baseline = courseGroupDeletionScope(group, "", GroupDeletionRequest.WholeCourse)
        for (query in listOf("张三", "李四", "王五", "高等数学", "赵六", "   ")) {
            val scope = courseGroupDeletionScope(group, query, GroupDeletionRequest.WholeCourse)
            assertEquals(
                "搜索框里写着「$query」时甲档交出去的那一组：必须与空搜索框那一枚逐位相同",
                baseline.fragments.map { it.id },
                scope.fragments.map { it.id },
            )
            assertEquals(
                "搜索框里写着「$query」时甲档念的那个数：必须与空搜索框那一枚相同（${baseline.announcedFragmentCount}）",
                baseline.announcedFragmentCount,
                scope.announcedFragmentCount,
            )
        }
    }

    /** 乙档才是"跟着过滤态走"的那一档：命中数随关键词变，删的范围就跟着变 */
    @Test
    fun `乙档随过滤态收窄 每一档各得其所`() {
        val group = fiveFragmentGroup()
        val byTeacher = mapOf(
            "张三" to listOf(11L, 12L),
            "李四" to listOf(13L, 14L),
            "王五" to listOf(15L),
            "赵六" to emptyList<Long>(),
        )
        for ((query, expected) in byTeacher) {
            val scope = courseGroupDeletionScope(group, query, GroupDeletionRequest.MatchedFragmentsOnly)
            assertEquals("乙档 · 关键词「$query」删的那一组", expected, scope.fragments.map { it.id })
            assertEquals("乙档 · 关键词「$query」念的那个数", expected.size, scope.announcedFragmentCount)
            assertEquals(
                "乙档 · 关键词「$query」的收窄判据",
                expected.size != group.size,
                scope.narrowedByFilter,
            )
        }
    }

    /** 判据本体不许重排：列序（星期 → 起始节次）由调用点排好，本函数只挑不乱 */
    @Test
    fun `判据只挑不排 交回的顺序就是传入的顺序`() {
        val scrambled = listOf(
            course(51, "自习", day = 5, periods = listOf(9, 10), teacher = "张三"),
            course(52, "自习", day = 1, periods = listOf(1, 2), teacher = "李四"),
            course(53, "自习", day = 3, periods = listOf(3, 4), teacher = "李四"),
        )
        val whole = courseGroupDeletionScope(scrambled, "", GroupDeletionRequest.WholeCourse)
        assertEquals("甲档 · 空过滤：原样端回（不许在这里 sort 第二遍）", listOf(51L, 52L, 53L), whole.fragments.map { it.id })
        val matched = courseGroupDeletionScope(scrambled, "李四", GroupDeletionRequest.MatchedFragmentsOnly)
        assertEquals("乙档 · 命中两枚：仍按传入顺序，不按星期", listOf(52L, 53L), matched.fragments.map { it.id })
        assertTrue(
            "甲档在过滤态下也必须原样端回整组（传入顺序）",
            courseGroupDeletionScope(scrambled, "李四", GroupDeletionRequest.WholeCourse).fragments
                .map { it.id } == listOf(51L, 52L, 53L),
        )
    }

    /** 「搜索态」那一枚尺只有一份：归一 + 三处字段命中，逐格对（列表面与判据面共吃它） */
    @Test
    fun `命中判据本体 三处字段 空关键词一律命中`() {
        val sample = course(61, "大学物理", day = 1, teacher = "钱老师", alias = "物理")
        assertEquals("空关键词 = 过滤态不成立 = 一律命中", true, courseFragmentMatchesQuery(sample, ""))
        assertTrue("按教务原名命中", courseFragmentMatchesQuery(sample, "大学物理"))
        assertTrue("按显示名（别名优先）命中", courseFragmentMatchesQuery(sample, "物理"))
        assertTrue("按教师命中", courseFragmentMatchesQuery(sample, "钱老师"))
        assertTrue("大小写不敏感那一档对三个字段都成立", courseFragmentMatchesQuery(course(62, "English", day = 1), "engl"))
        assertTrue("教师为 null 不许炸", courseFragmentMatchesQuery(course(63, "高等数学", day = 1, teacher = null), "高等"))
        assertEquals("都没命中就是没命中", false, courseFragmentMatchesQuery(sample, "体育"))
        assertEquals("关键词归一：去首尾空白", "高等数学", normalizedCourseQuery("  高等数学  "))
        assertEquals("关键词归一：整串空白归零 = 不过滤", "", normalizedCourseQuery("  \t "))
    }
}
