package com.buaa.schedule.domain.schedule

import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.Semester
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * T356（#602 本尊，顺带 #397 与它的交点那一档）：WakeUp 导出件里 `maxWeek` 那一格**到底由谁填**的表驱动账。
 *
 * ## 病（两档，同一个根）
 *
 * 导出器原本是这样拿上界的：学期行给得出就用它，给不出就兜 `CourseConstraints.MAX_TOTAL_WEEKS`（= 30），
 * 而**同一枚数**既写进 `"maxWeek"` 又递进 [TermWeekGate.weeksInsideTerm] 当裁剪上界。
 *
 * - **档一（#602）没有学期行**：屏上没有任何一枚学期说过"这学期 30 周"，导出件却自己声明了它 ——
 *   这是一枚**伪造的事实**。而且它带牙齿：裁剪判的就是它，于是第 31 周以后的课次被**真裁掉**
 *   （改前那格现量：课次 1、2、35 ⇒ 交回 `startWeek:1,endWeek:2` 而 `maxWeek:30`）。
 * - **档二（#397 与 #602 的交点）有学期行但那一行是脏的（`totalWeeks = 0`）**：`?:` 压根不开火
 *   （`Semester.totalWeeks` 是非空 `Int`）⇒ `maxWeek:0` 原样写出去，而 0 当上界时
 *   [TermWeekGate.isInTermWeek] 对每一周都答"不在学期内"⇒ 整门课被裁光，紧跟那句
 *   `.ifEmpty { sortedWeeks }` 又整条兜回全量 ⇒ 这份 JSON 里 `startWeek..endWeek` 是全量周次、
 *   `maxWeek` 却是 0，正是导出器注释点名的那个形状（`endWeek > maxWeek` = 对方 App 多出来的那几天）。
 *
 * ## 现在的判据（两等出处，一枚都不许是编的）
 *
 * 1. 学期行**自己**说得上话（那一枚周数 > 0）⇒ 上界 = 它，裁剪也判它（#307 那半条一字不动）；
 * 2. 说不上话（没有学期行 / 那一行是 0 或负数）⇒ 上界 = **这份文件实际覆盖到的最晚一周**，
 *    它是从课程自己交出的周次里读出来的可观察事实，不是常数；裁剪也判它 ⇒ 在这一等里
 *    "界内的课次一枚都不许被裁掉"是**可证的**（没有任何一枚周次能超过全体课程的最大周号），
 *    而"没有可信学期事实 ⇒ 不声明学期上界"（甲档的意图）与"不许两把尺"（第 ⓪ 问 ③）同时成立。
 *
 * 下界那一判在第二等里**照样有牙齿**：第 0 周与负周次压根不是教学周，这一判不需要任何学期事实
 * （[SemesterWeekDates.mondayOf] 对 week < 1 交回 null ⇒ [TermWeekGate.isInTermWeek] 答"不在学期内"）。
 *
 * ## 判不出时的安全侧（本仓成文口径要有明确一句）
 *
 * 这一条导出链的安全侧是**原样出场**，不是不出场：`weeksInsideTerm` 自己的口径就是
 * 「两枚学期事实都在手上才裁，任一枚取不到就原样交回」，而编出来的上界说不出「确证在学期外」这一句。
 * 把"整门课都在学期外"那一等判成第三档"没进导出件"要同时改报数那两枚文件（本卡白名单外）
 * ⇒ 逐字登记进回执「需要跨卡配合」，本文件第 ④ 等那两格钉的就是**今天仍照旧出场**这枚现状。
 *
 * ## 可达性（档二不是不可达支，别按 #402 那一族判薄）
 *
 * 写侧今天一条都不产 ≤ 0：`normalizeTotalWeeks` 在 main 只有三枚调用点（备份恢复两等 + 设置页一枚），
 * 教务那两路（解析与取数）都在空列表处早返回。唯一进得来的路是**存量学期行**：
 * 已发版的 v0.1.0 / v0.1.1 把备份里的裸值直写库（`git show v0.1.1` 里 `BackupModels.kt` 的
 * `totalWeeks = totalWeeks` 与仓储那一句 `replaceSemesterCoursesInTx(backupSemester, …)` 都是现成的），
 * `SemesterEntity` 没有 CHECK 也没有 DEFAULT，迁移链一枚都没动 `semesters`
 * ⇒ 那行脏值今天就躺在读侧零护栏的链上，经裸映射进 `state.semester`，导出器逐字读它。
 *
 * ⚠️ 本文件是**表驱动判据账**（真调生产导出器，固定日期，零 android import、不起 Robolectric）；
 * 源码形状那一半在 `TermWeekGateSingleRulerWiringGuardTest` 的第 ⑥ 层（不写常数、两本账同一枚标识符）。
 */
class WakeUpMaxWeekSourceTest {

    /** 固定日期：开学日 = 周一，「今天」落在学期内第二周 —— 本文件一格都不读钟 */
    private val semesterStart = "2026-09-07"
    private val today = LocalDate.of(2026, 9, 15)

    private fun semester(totalWeeks: Int) = Semester(
        termCode = "T",
        termName = "本学期",
        startDate = semesterStart,
        totalWeeks = totalWeeks,
    )

    /** 学期行的 startDate 畸形（parse 当场拒掉）那一等：上界仍由学期行给，锚点才退到今天 */
    private fun malformedStartSemester(totalWeeks: Int) = Semester(
        termCode = "T",
        termName = "本学期",
        startDate = "07/09/2026",
        totalWeeks = totalWeeks,
    )

    private fun course(weeks: List<Int>, periods: List<Int> = listOf(1, 2)) = Course(
        id = 1L,
        name = "高数",
        teacher = "张三",
        location = "J3-101",
        dayOfWeek = 1,
        periods = periods,
        weeks = weeks,
    )

    private class Exported(val maxWeek: Int, val entries: List<Entry>) {
        data class Entry(val startWeek: Int, val endWeek: Int, val type: Int)
    }

    private fun parse(json: String): Exported {
        // 允许负号：改前一枚 `totalWeeks = -5` 的脏学期行把 `-5` **原样**写进 maxWeek（本卡 ① 步现量到的，
        // 只认 (\d+) 的针读不出这一格，那一等就成了守卫读不到的暗格 —— 针要认得下病灶的全部形状）
        val maxWeek = Regex("\"maxWeek\":(-?\\d+)").find(json)?.groupValues?.get(1)?.toIntOrNull()
            ?: error("导出件里没有 maxWeek 那一格，或者它写成了非数字（那就是对方解析器要处理的第二份真相）：$json")
        val entries = Regex("\"startWeek\":(-?\\d+),\"endWeek\":(-?\\d+),\"type\":(-?\\d+)")
            .findAll(json)
            .map { Exported.Entry(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt()) }
            .toList()
        return Exported(maxWeek, entries)
    }

    private fun export(courses: List<Course>, row: Semester?): Exported =
        parse(ScheduleExporters.toWakeUpJson(courses, row, emptyList(), today))

    private data class Cell(
        val tier: String,
        val row: Semester?,
        val weeks: List<Int>,
        val expectedMaxWeek: Int,
        val expectedEntries: List<Exported.Entry>,
        /** 真话列：这一等的上界出处是「学期行」还是「这份文件自己的覆盖度」（两等之外不许有第三种） */
        val ceilingSource: String,
    )

    // ─────────────── 四象限（+ 补格），逐格钉 maxWeek 与 startWeek..endWeek ───────────────

    private val cells = listOf(
        // ① 有学期行 ＋ 正常 totalWeeks：#307 那半条一字不许动
        Cell(
            tier = "① 有学期·上界 19｜课次 1..19 ⇒ 界内一枚都不裁",
            row = semester(19), weeks = (1..19).toList(), expectedMaxWeek = 19,
            expectedEntries = listOf(Exported.Entry(1, 19, 0)), ceilingSource = "学期行",
        ),
        Cell(
            tier = "① 有学期·上界 19｜课次 1..21 ⇒ 20/21 被**真**上界裁掉（牙齿在这一等）",
            row = semester(19), weeks = (1..21).toList(), expectedMaxWeek = 19,
            expectedEntries = listOf(Exported.Entry(1, 19, 0)), ceilingSource = "学期行",
        ),
        Cell(
            tier = "① 有学期·上界 19｜课次只到第 8 周 ⇒ maxWeek 仍是 19（学期上界优先于覆盖度）",
            row = semester(19), weeks = (1..8).toList(), expectedMaxWeek = 19,
            expectedEntries = listOf(Exported.Entry(1, 8, 0)), ceilingSource = "学期行",
        ),
        Cell(
            tier = "① 有学期·上界 19｜startDate 畸形⇒锚点退到今天，上界仍由学期行给",
            row = malformedStartSemester(19), weeks = (1..25).toList(), expectedMaxWeek = 19,
            expectedEntries = listOf(Exported.Entry(1, 19, 0)), ceilingSource = "学期行",
        ),
        // ② 有学期行但那一行是脏的（#397 × #602 的交点）
        Cell(
            tier = "② 学期行 = 0（脏）｜课次 1、2、3 ⇒ 不拿 0 当上界，maxWeek 取覆盖到的第 3 周",
            row = semester(0), weeks = listOf(1, 2, 3), expectedMaxWeek = 3,
            expectedEntries = listOf(Exported.Entry(1, 3, 0)), ceilingSource = "这份文件自己覆盖到的一周",
        ),
        Cell(
            tier = "② 学期行 = 0（脏）｜课次 1..12 ⇒ 改前是 maxWeek:0 配 endWeek:12（正撞那句 endWeek > maxWeek）",
            row = semester(0), weeks = (1..12).toList(), expectedMaxWeek = 12,
            expectedEntries = listOf(Exported.Entry(1, 12, 0)), ceilingSource = "这份文件自己覆盖到的一周",
        ),
        Cell(
            tier = "② 学期行 = -5（脏）⇒ 与 0 同一档，负数也不许当上界",
            row = semester(-5), weeks = listOf(1, 2, 3), expectedMaxWeek = 3,
            expectedEntries = listOf(Exported.Entry(1, 3, 0)), ceilingSource = "这份文件自己覆盖到的一周",
        ),
        // ③ 没有学期行（#602 本尊）
        Cell(
            tier = "③ 无学期行｜课次 1、2、25 ⇒ 不写 30 这枚屏上没人说过的数，maxWeek = 25",
            row = null, weeks = listOf(1, 2, 25), expectedMaxWeek = 25,
            expectedEntries = listOf(Exported.Entry(1, 25, 0)), ceilingSource = "这份文件自己覆盖到的一周",
        ),
        Cell(
            tier = "③ 无学期行｜课次 1、2、35 ⇒ 改前 35 被编出来的 30 真裁掉（现量 endWeek:2），现在原样出场",
            row = null, weeks = listOf(1, 2, 35), expectedMaxWeek = 35,
            expectedEntries = listOf(Exported.Entry(1, 35, 0)), ceilingSource = "这份文件自己覆盖到的一周",
        ),
        Cell(
            tier = "③ 无学期行｜单枚周次 7 ⇒ 上界 7 而 endWeek 7（同源；第二把尺一扭这一格就分家）",
            row = null, weeks = listOf(7), expectedMaxWeek = 7,
            expectedEntries = listOf(Exported.Entry(7, 7, 1)), ceilingSource = "这份文件自己覆盖到的一周",
        ),
        Cell(
            tier = "③ 无学期行｜0 与负周次**照样裁**（这一判不需要学期事实：屏上没有第 0 周）",
            row = null, weeks = listOf(-3, 0, 3), expectedMaxWeek = 3,
            expectedEntries = listOf(Exported.Entry(3, 3, 1)), ceilingSource = "这份文件自己覆盖到的一周",
        ),
        // ④ 整门课都在学期外（真上界那一等）：在册的现状，钉的是"照旧出场"，不是"已被修好"
        Cell(
            tier = "④ 有学期·上界 19｜课次 20、21 全在学期外 ⇒ 现状照旧出场（第三档 = 跨卡那一格）",
            row = semester(19), weeks = listOf(20, 21), expectedMaxWeek = 19,
            expectedEntries = listOf(Exported.Entry(20, 21, 0)), ceilingSource = "学期行",
        ),
        Cell(
            tier = "④ 有学期·上界 19｜极端 400 周 ⇒ 同上照旧出场，本卡不动这一等",
            row = semester(19), weeks = listOf(400), expectedMaxWeek = 19,
            expectedEntries = listOf(Exported.Entry(400, 400, 2)), ceilingSource = "学期行",
        ),
    )

    /** 逐格都过完再报（第一格就抛的话，变异自证时分不出"红的是点名那一手还是别的顺手格子"）。 */
    @Test
    fun `四象限逐格钉住 maxWeek 的出处与写出去的周次`() {
        val problems = mutableListOf<String>()
        for (cell in cells) {
            val exported = export(listOf(course(cell.weeks)), cell.row)
            if (exported.maxWeek != cell.expectedMaxWeek) {
                problems += "${cell.tier}\n  —— maxWeek 这一格写歪了：判据只有两等出处（学期行自己给的上界 / " +
                    "这份文件实际覆盖到的最晚一周），第三等（常数 30、脏值 0 原样出场）就是 #602 与 #397 的那一枚根。" +
                    "\n  实到 maxWeek=${exported.maxWeek}，期望=${cell.expectedMaxWeek}（出处：${cell.ceilingSource}）"
            }
            if (exported.entries != cell.expectedEntries) {
                problems += "${cell.tier}\n  —— 写出去的 startWeek/endWeek/type 不对：裁过头就是把用户的真课次裁进对方" +
                    "看不见的地方，该裁没裁就是把学期外的那几周送出去（本仓口径：离开本应用的数据收不回来）。" +
                    "\n  实到 ${exported.entries}，期望 ${cell.expectedEntries}"
            }
        }
        assertEquals(
            "逐格对账（红的每一格都点名在上面，共 " + problems.size + " 格）：\n" +
                problems.joinToString(separator = "\n"),
            emptyList<String>(),
            problems,
        )
    }

    /**
     * **同源那一格**（与上面那格分开钉，变异自证才分得出方向）：只要这门课有一枚周次落在它自己声明的
     * 上界之内，写出去的 `endWeek` 就不许越过 `maxWeek` —— 这是导出器注释里那句
     * 「同一份 JSON 里 `endWeek > maxWeek` 就是对方 App 多出来的那几天」的正面写法。
     *
     * ⚠️ 这一格**拦不住**"两本账一起改齐到一枚编出来的数"（把伪造常数同时喂给裁剪与声明，它就照绿）：
     * 那一臂由上面那格的 `expectedMaxWeek` 独立点名 —— 这正是本卡要第三臂的原因，两本账同源是必要条件、
     * 不是充分条件。
     */
    @Test
    fun `界内的课次一枚都不许越过自己声明的上界`() {
        val problems = mutableListOf<String>()
        for (cell in cells) {
            val exported = export(listOf(course(cell.weeks)), cell.row)
            val ceiling = if (cell.row == null || cell.row.totalWeeks <= 0) cell.expectedMaxWeek else cell.row.totalWeeks
            val inside = cell.weeks.count { it in 1..ceiling }
            if (inside == 0) continue
            val endWeek = exported.entries.maxOf { it.endWeek }
            if (endWeek > exported.maxWeek) {
                problems += "${cell.tier}\n  —— 界内还有 $inside 枚周次，写出去的 endWeek=$endWeek 却越过声明的 " +
                    "maxWeek=${exported.maxWeek}：对方 App 就会在学期结束之后照样排课（收不回来的那一手）。" +
                    "红在这格 = 裁剪用的那一枚与写进 maxWeek 的那一枚**分家**了（两把尺）"
            }
        }
        assertEquals(
            "同源账逐格对完（共 " + problems.size + " 格分家）：\n" + problems.joinToString(separator = "\n"),
            emptyList<String>(),
            problems,
        )
    }

    /**
     * 第二等里"界内一枚都不许被裁"是**可证的**：上界取自全体课程的最大周号，没有任何一枚周次能超过它。
     * 这一格钉的是数据不丢（改前的伪造 30 恰恰会丢：课次 1、2、35 只交回 1..2）。
     */
    @Test
    fun `没有可信学期行时这一等不裁掉任何一枚真周次`() {
        val rows = listOf<Semester?>(null, semester(0), semester(-5))
        for (row in rows) {
            val weeks = listOf(1, 2, 9, 14, 25, 31, 35, 48)
            val exported = export(listOf(course(weeks)), row)
            assertEquals(
                "没有可信学期行（${row?.let { "学期行 " + it.totalWeeks } ?: "没有学期行"}）那一等，" +
                    "用户给出的每一枚真周次都必须照样出场（裁它要说得出「确证在学期外」）：${exported.entries}",
                listOf(Exported.Entry(1, 48, 0)),
                exported.entries,
            )
            assertEquals(
                "同一趟里 maxWeek 必须就是这份文件覆盖到的最晚一周（48），不是任何一枚常数",
                48,
                exported.maxWeek,
            )
        }
    }

    /** 一门课都没写出去那一等：文件覆盖不到任何一周 ⇒ maxWeek 取 0（诚实的最小值，不是 30）。 */
    @Test
    fun `一条课都没写出去时不声明学期上界`() {
        val exported = export(emptyList(), null)
        assertEquals("一条课都没有 ⇒ 这份文件一周都不覆盖，写 0 而不是写一枚屏上没人说过的上界", 0, exported.maxWeek)
        assertTrue("入参没有课时，courses 必须也是空的：${exported.entries}", exported.entries.isEmpty())
        // 生产链上这一份根本落不了盘（段数 ≤ 0 ⇒ WakeUpExportGate 判成 RefuseEmpty），
        // 这一格钉的是导出器**自己**交回的文本不许凭空声明学期。
        val onlyDroppable = export(listOf(course(emptyList(), periods = emptyList()), course(emptyList())), semester(0))
        assertEquals("全是「没节次/没周次」那两档课时，脏学期行也不许被折成 0 之外的上界：${onlyDroppable.maxWeek}", 0, onlyDroppable.maxWeek)
        assertTrue("两档 drop 照旧整条不写（本卡没把档位改成三档）：${onlyDroppable.entries}", onlyDroppable.entries.isEmpty())
    }

    /**
     * 别的字段不连坐：改上界出处只许动 `maxWeek` 与周次三格，节次、星期、颜色、门数一枚都不许跟着漂
     * （同批输入里"学期行 19 周"那一等与"没有学期行"那一等，除 maxWeek 与周次格外逐字节同形）。
     */
    @Test
    fun `换掉上界的出处不连坐其它字段`() {
        val jsonWithTerm = ScheduleExporters.toWakeUpJson(listOf(course(listOf(1, 2))), semester(19), emptyList(), today)
        val jsonWithoutTerm = ScheduleExporters.toWakeUpJson(listOf(course(listOf(1, 2))), null, emptyList(), today)
        val bodyWithTerm = jsonWithTerm.substringAfter("\"courses\":[")
        val bodyWithoutTerm = jsonWithoutTerm.substringAfter("\"courses\":[")
        assertEquals(
            "两条链的课程对象逐字节同形（day/startNode/step/name/position/color 都不该因上界出处而变）：" +
                "\n  有学期行那一等：" + bodyWithTerm + "\n  没有学期行那一等：" + bodyWithoutTerm,
            bodyWithTerm,
            bodyWithoutTerm,
        )
        assertTrue("学期行那一等仍声明 19：$jsonWithTerm", jsonWithTerm.contains("\"maxWeek\":19"))
        assertTrue("没有学期行那一等声明的是覆盖到的第 2 周，不是 30：$jsonWithoutTerm", jsonWithoutTerm.contains("\"maxWeek\":2"))
    }
}
