package com.buaa.schedule.ui.stats

import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.schedule.SemesterStats
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T81 第一批「已算未露」那四件的判据内核 + 接线。
 *
 * 内核三枚（[semesterTitleOf] / [courseRowNote] / [sortedByCreditDesc]）是纯 JVM 的，
 * 表驱动钉死；最后一档 [statsScreenWiresEveryNewFact] 扫源码钉"接没接上"——
 * 本模块没有 Compose 运行时，而这一卡的病恰恰是"算好了没人画"（刀法同
 * [com.buaa.schedule.ui.home.CourseMetaWiringGuardTest]）。
 */
class StatsContentKernelsTest {

    private fun fragment(
        name: String,
        teacher: String? = null,
        location: String? = null,
        campus: String? = null,
    ) = Course(
        name = name,
        teacher = teacher,
        location = location,
        campus = campus,
        dayOfWeek = 1,
        periods = listOf(1),
        weeks = listOf(1),
    )

    private fun creditOf(name: String, key: String, credit: Double?, fragments: Int) =
        SemesterStats.CourseCredit(
            groupKey = key,
            course = fragment(name),
            credit = credit,
            fragments = List(fragments) { fragment(name) },
        )

    // ---- ① 学期名：两个降级值都不许当学期名吹 ----

    @Test
    fun semesterTitleTable() {
        val cases = listOf<Triple<String?, String?, SemesterTitle?>>(
            // 真名字：原样画
            Triple("2026 秋季学期", "2026-2027-1", SemesterTitle("2026 秋季学期", SemesterTitleKind.Named)),
            // buildFallbackSemester 那一支：termName 就是 termCode，标成学期代码
            Triple("2026-2027-1", "2026-2027-1", SemesterTitle("2026-2027-1", SemesterTitleKind.CodeOnly)),
            // 设置页名称留空时写进去的占位：退到学期代码那一档
            Triple("未命名学期", "2026-2027-1", SemesterTitle("2026-2027-1", SemesterTitleKind.CodeOnly)),
            // 名字有效但代码缺失：只能按名字那一档画
            Triple("秋季学期", null, SemesterTitle("秋季学期", SemesterTitleKind.Named)),
            Triple("  2026 秋季学期  ", "2026-2027-1", SemesterTitle("2026 秋季学期", SemesterTitleKind.Named)),
            // 没有任何可说的：整行不画
            Triple("", "2026-2027-1", null),
            Triple("   ", "2026-2027-1", null),
            Triple(null, "2026-2027-1", null),
            // 占位名 + 没有代码：无信息可给，整行不画（画"未命名学期"是废话）
            Triple("未命名学期", null, null),
            Triple("未命名学期", "   ", null),
        )
        for ((name, code, expected) in cases) {
            assertEquals("semesterTitleOf($name, $code)", expected, semesterTitleOf(name, code))
        }
    }

    @Test
    fun semesterNameEqualToCodeAfterTrimStillCountsAsCodeOnly() {
        val title = semesterTitleOf(" 2026-2027-1 ", "2026-2027-1")
        assertEquals(SemesterTitleKind.CodeOnly, title?.kind)
        assertEquals("2026-2027-1", title?.text)
    }

    // ---- ② 明细行：教师哨兵折掉、片段去重、超量折叠、N==1 不写 ----

    @Test
    fun fragmentNoteOnlyAppearsAboveOne() {
        assertNull(fragmentNote(1))
        assertNull(fragmentNote(0))
        assertEquals("由 2 段排课合并", fragmentNote(2))
        assertEquals("由 11 段排课合并", fragmentNote(11))
    }

    @Test
    fun courseRowNoteDeduplicatesAcrossFragments() {
        // 同一门课的两个片段：教师各一位、教室两间，去重后按 教师·地点·校区·段数 一行说完
        val note = courseRowNote(
            listOf(
                fragment("大学物理", teacher = "王教授", location = "J3-101", campus = "学院路校区"),
                fragment("大学物理", teacher = "李工程师", location = "M201", campus = "学院路校区"),
            ),
        )
        assertEquals("王教授、李工程师 · J3-101、M201 · 学院路校区 · 由 2 段排课合并", note)
    }

    @Test
    fun courseRowNoteNeverPrintsTheUnknownTeacherSentinel() {
        // 教务的字面量哨兵（BuaaScheduleParser.kt:78）不是 null：折掉之后这一行整段缺席
        assertNull(courseRowNote(listOf(fragment("入学教育", teacher = "未知教师"))))
        // 两个片段一位真教师 + 一位哨兵：只说真教师，不冒出"未知教师"
        val note = courseRowNote(
            listOf(
                fragment("A", teacher = "未知教师"),
                fragment("A", teacher = "张三"),
            ),
        )
        assertEquals("张三 · 由 2 段排课合并", note)
    }

    @Test
    fun courseRowNoteCollapsesMoreThanTwoValues() {
        val fragments = listOf(
            fragment("实验课", location = "M101"),
            fragment("实验课", location = "M102"),
            fragment("实验课", location = "M103"),
            fragment("实验课", location = "M104"),
        )
        assertEquals("M101、M102 等4处 · 由 4 段排课合并", courseRowNote(fragments))
    }

    @Test
    fun courseRowNoteSkipsBlankValuesAndReturnsNullWhenNothingLeft() {
        assertNull(courseRowNote(listOf(fragment("A", teacher = "  ", location = "", campus = null))))
        assertNull(courseRowNote(emptyList()))
        // 空串与全空白在片段之间也去重得掉，不会拼出「张三、」这种带尾巴的行
        assertEquals(
            "张三 · 由 2 段排课合并",
            courseRowNote(listOf(fragment("A", teacher = "张三"), fragment("A", teacher = "   "))),
        )
    }

    @Test
    fun courseRowNoteTakesPeProjectFromNameNotAlias() {
        // displayName 是别名优先：别名改成「体育课」就没处剥了，所以这枚吃 name（Sheet 同口径）
        val aliased = fragment("体育(田径)", teacher = "赵老师").copy(alias = "体育课")
        assertEquals("赵老师 · 体育项目 田径", courseRowNote(listOf(aliased)))
    }

    // ---- ③ 学分排序：降序、null 沉底、并列保持首次出现序 ----

    @Test
    fun sortPutsMissingCreditsLastAndKeepsFirstAppearanceOnTies() {
        val input = listOf(
            creditOf("新生研讨课", "K1", null, 1),
            creditOf("高等数学", "K2", 5.0, 2),
            creditOf("大学英语", "K3", 3.0, 1),
            creditOf("体育", "K4", null, 1),
            creditOf("线性代数", "K5", 5.0, 3),
        )

        val sorted = input.sortedByCreditDesc()

        // 学分降序；5.0 的并列两门按首次出现序（高数在前），"不知道"的两门整档沉底
        assertEquals(
            listOf("高等数学", "线性代数", "大学英语", "新生研讨课", "体育"),
            sorted.map { it.course.name },
        )
        // 排的是副本，不许把 summary 里那份原序改坏（domain 交出来的首次出现序还在）
        assertEquals("K1", input.first().groupKey)
        assertEquals(5, input.size)
    }

    @Test
    fun zeroCreditSortsAboveMissingCredit() {
        // 0 学分是教务明说的真值，不能和"没采到"混在同一档里
        val sorted = listOf(creditOf("入学教育", "Z1", 0.0, 1), creditOf("手动课", "Z2", null, 1))
            .sortedByCreditDesc()
        assertEquals(listOf("入学教育", "手动课"), sorted.map { it.course.name })
    }

    // ---- ④ 接线：四件都真的画在 StatsScreen 上 ----

    @Test
    fun statsScreenWiresEveryNewFact() {
        val code = statsScreenSource()
        val required = listOf(
            "CreditHeadline(summary, semesterTitle)",   // 学期名进了最上面那张卡
            "semesterTitleOf(",
            "SemesterTitleKind.CodeOnly -> \"学期代码", // 降级值那一档的措辞还在
            "这学期里，",                                // 每日课次数那句（口径钉死的那句）
            "it.courseCount",
            "perCourse.sortedByCreditDesc()",           // 学分排序
            "courseRowNote(item.fragments)",            // 教师/地点/校区 + 几段合并
            "formatCreditTotal(summary.totalCredits)",  // 合计的格式化
            "formatCredit(item.credit)",                // 单体的格式化（复用，不手抄）
        )
        val missing = required.filterNot { it in code }
        assertTrue("统计页又有人把 T81 的接线摘了：\n${missing.joinToString("\n")}", missing.isEmpty())
        assertFalse(
            "这一页不能再手抄一份学分格式化（改前的 trimCredits 就是 formatCredit 的手抄版）",
            "private fun trimCredits" in code,
        )
        assertFalse(
            "缺学分的画法必须与详情 Sheet 一致（null 不画），不许回到印一个「—」占位的老口径",
            "?: \"—\"" in code,
        )
    }

    private fun statsScreenSource(): String {
        var dir: File? = File("").absoluteFile
        repeat(5) {
            val root = listOf("src/main/java", "app/src/main/java")
                .map { File(dir, it) }
                .firstOrNull { it.isDirectory }
            if (root != null) {
                val file = File(root, "com/buaa/schedule/ui/stats/StatsScreen.kt")
                assertTrue("找不到 StatsScreen.kt：挪过家的话这条守卫要跟着改路径", file.isFile)
                return file.readText()
            }
            dir = dir?.parentFile
        }
        throw IllegalStateException("找不到 app/src/main/java：当前目录 ${File("").absolutePath}")
    }
}
