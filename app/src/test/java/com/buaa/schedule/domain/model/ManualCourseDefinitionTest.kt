package com.buaa.schedule.domain.model

import com.buaa.schedule.data.backup.BackupCourse
import com.buaa.schedule.data.backup.toDomain
import com.buaa.schedule.domain.schedule.CourseConstraints
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T184①：判据单测 —— 「手动课程」= **这一行不挂在任何学期下**（`Course.isManualCourse`）。
 *
 * ## 裁定（读两侧证据之后）
 *
 * 「手动课程」这个词全仓早就只有一个意思，而且是恢复侧那一个：
 * - `data/local/CourseDao.getManualCourses()` 的查询写的是 `WHERE semesterCode IS NULL`；
 * - `data/repository/ScheduleRepository` 展示口径那段的 KDoc 写「手动课程（semesterCode 为 null）」；
 * - `domain/schedule/CourseFilter` 写「手动添加的课程（semesterCode 为 null）始终显示」。
 * 而 `isManualOverride` 说的是另一件事（「这行被用户手改过，从此退出教务刷新的匹配与覆盖」，
 * 见 `ui/home/ManualTimeOverridePolicy`）：T133 之后被拖过 / 挪过时间的教务课是
 * `isManualOverride = true` **且** `semesterCode` 非空 —— 两集合天生不重合。
 * 于是病灶在**预览那一句**：它把「拖过的教务课」念成了「手动课程」。
 *
 * 收成一枚判据（`Course.isManualCourse`，值 = f(同表参数) ⇒ 类体属性那条合法落点）之后，
 * 两句读的是同一批行；剩下的数差只有两处**合法的**：
 * `CourseConstraints.normalize` 判死的行（同句另有「跳过无效数据 N 条」念它）与
 * 本地已有的行（恢复句用「新增」二字，走 courseKey 去重）。见 [预览与恢复按同一判据分同一批行]。
 *
 * 纯 JVM：不读时钟、不碰权限、无 Robolectric；`semesterCode` / `isManualOverride` 由用例当参数传进来。
 */
class ManualCourseDefinitionTest {

    private data class Case(
        val label: String,
        val semesterCode: String?,
        val isManualOverride: Boolean,
        val expectManual: Boolean,
        val why: String,
    )

    private val cases = listOf(
        Case(
            label = "纯教务行",
            semesterCode = SEMESTER,
            isManualOverride = false,
            expectManual = false,
            why = "挂在学期下 ⇒ 不是手动课程",
        ),
        Case(
            label = "被手改过的教务行",
            semesterCode = SEMESTER,
            isManualOverride = true,
            expectManual = false,
            why = "病灶那一档：拖过 / 挪过时间的教务课，isManualOverride 为真而 semesterCode 照样非空 ⇒ " +
                "旧预览句把它数成手动课程、恢复句数它 0 条，两句对不上",
        ),
        Case(
            label = "真手动行",
            semesterCode = null,
            isManualOverride = false,
            expectManual = true,
            why = "用户手工加的行不挂学期 ⇒ 手动课程（与 CourseDao 的 IS NULL 同一批）",
        ),
        Case(
            label = "真手动行且被改过",
            semesterCode = null,
            isManualOverride = true,
            expectManual = true,
            why = "手动行当然也能被改：改没改与挂不挂学期是两条独立的轴",
        ),
        Case(
            label = "学期码为空串",
            semesterCode = "",
            expectManual = false,
            isManualOverride = false,
            why = "空串不等于 null：normalize 不洗 semesterCode、SQL 的 IS NULL 也只认 null，" +
                "两侧同判「非手动」⇒ 这一档不分歧，别顺手改成 isNullOrBlank",
        ),
    )

    @Test
    fun `判据表 五档逐条`() {
        cases.forEach { c ->
            val course = course(semesterCode = c.semesterCode, isManualOverride = c.isManualOverride)
            assertEquals("${c.label}：${c.why}", c.expectManual, course.isManualCourse)
        }
        assertEquals("表里的档数（改动这张表要一起改枚数）", 5, cases.size)
    }

    @Test
    fun `备份 DTO 走 toDomain 之后判据不漂`() {
        // 预览拿的是 DTO（toDomain 不经 normalize），恢复拿的是 normalize 之后的行 ——
        // toDomain 必须原样带回 semesterCode，否则同一判据在两侧数出两批行。
        cases.forEach { c ->
            val fromBackup = backupCourse(
                semesterCode = c.semesterCode,
                isManualOverride = c.isManualOverride,
            ).toDomain()
            assertEquals("${c.label}：toDomain 之后判据应当不变", c.expectManual, fromBackup.isManualCourse)
            assertEquals(
                "${c.label}：toDomain 之后 semesterCode 应当原样",
                c.semesterCode,
                fromBackup.semesterCode,
            )
        }
    }

    @Test
    fun `混合批次按判据数出的条数`() {
        val batch = listOf(
            course(name = "教务甲", semesterCode = SEMESTER),
            course(name = "教务乙", semesterCode = SEMESTER),
            course(name = "拖过的教务甲", semesterCode = SEMESTER, isManualOverride = true),
            course(name = "挪过时间的教务乙", semesterCode = OTHER_SEMESTER, isManualOverride = true),
            course(name = "手动甲", semesterCode = null),
            course(name = "手动乙", semesterCode = null, isManualOverride = true),
        )
        val manual = batch.filter { it.isManualCourse }.map { it.name }.toSet()
        val overridden = batch.filter { it.isManualOverride }.map { it.name }.toSet()

        assertEquals("六行里挂不住学期的只有两行", setOf("手动甲", "手动乙"), manual)
        assertEquals("判据在这一批行上数 2 条", 2, batch.count { it.isManualCourse })
        // 下面两行是病灶本身：旧预览口径拿 isManualOverride 在同一批行上数出 3 条，
        // 其中两行是挂在学期下的教务课（用户只是把它们拖过 / 挪过时间）。
        // 留着它是为了钉住「两集合不重合」，不许有人靠把判据换成 isManualOverride 来"统一"。
        assertEquals(
            "同一批行按 isManualOverride 数是 3 条，且其中 2 条根本不是手动课程：" +
                "这就是预览句与恢复句对不上的来源",
            setOf("拖过的教务甲", "挪过时间的教务乙"),
            overridden - manual,
        )
        assertEquals("旧口径在同一批行上数出的枚数（钉住 2≠3 这枚差）", 3, overridden.size)
    }

    @Test
    fun `预览与恢复按同一判据分同一批行`() {
        val rows = listOf(
            backupCourse(semesterCode = SEMESTER),
            backupCourse(semesterCode = SEMESTER, isManualOverride = true),
            backupCourse(semesterCode = null, name = "手动甲"),
            backupCourse(semesterCode = null, name = "手动乙"),
            backupCourse(semesterCode = null, name = "被判死的手动行", periods = emptyList()),
        )
        // 预览那一句：DTO → toDomain → 判据
        val previewSide = rows.count { it.toDomain().isManualCourse }
        // 恢复那一句：DTO → toDomain → normalize（判死的行不进）→ 判据
        val normalized = rows.map { it.toDomain() }.mapNotNull(CourseConstraints::normalize)
        val restoreSide = normalized.count { it.isManualCourse }

        assertEquals("预览：五行里三行不挂学期", 3, previewSide)
        assertEquals("恢复：判死一行后只剩两行不挂学期", 2, restoreSide)
        val droppedManual = rows.map { it.toDomain() }
            .count { it.isManualCourse && CourseConstraints.normalize(it) == null }
        assertEquals(
            "两句的数差只许来自被 normalize 判死的行（同句另有「跳过无效数据 N 条」念它），" +
                "判据本身不许再造差：" +
                "预览 $previewSide − 恢复 $restoreSide ≠ 被判死的手动行 $droppedManual",
            previewSide - restoreSide,
            droppedManual,
        )
        // 有效行上两侧同判（这才叫"同一批行"）
        normalized.forEach { row ->
            assertEquals(
                "有效行 ${row.name} 在两侧必须同判：${row.semesterCode}",
                rows.first { it.name == row.name }.toDomain().isManualCourse,
                row.isManualCourse,
            )
        }
    }

    // ─────────────── 造行 ───────────────

    private fun course(
        semesterCode: String?,
        isManualOverride: Boolean = false,
        name: String = "高等数学",
    ) = Course(
        name = name,
        dayOfWeek = 1,
        periods = listOf(1, 2),
        weeks = listOf(1, 2, 3),
        semesterCode = semesterCode,
        isManualOverride = isManualOverride,
    )

    private fun backupCourse(
        semesterCode: String?,
        isManualOverride: Boolean = false,
        name: String = "高等数学",
        periods: List<Int> = listOf(1, 2),
    ) = BackupCourse(
        name = name,
        dayOfWeek = 1,
        periods = periods,
        weeks = listOf(1, 2, 3),
        semesterCode = semesterCode,
        isManualOverride = isManualOverride,
    )

    private companion object {
        const val SEMESTER = "2025-2026-1"
        const val OTHER_SEMESTER = "2024-2025-2"
    }
}
