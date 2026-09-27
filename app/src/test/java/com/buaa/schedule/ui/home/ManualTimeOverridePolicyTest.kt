package com.buaa.schedule.ui.home

import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.schedule.ConflictDetector
import com.buaa.schedule.domain.schedule.CourseConflictResolution
import com.buaa.schedule.domain.schedule.CourseConstraints
import com.buaa.schedule.domain.schedule.ImportPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T133b 表驱动单测：「移动课程这一趟到底动没动时间」这一判（纯 JVM，零 android、零时钟、不装机）。
 *
 * 判据本体在 [ManualTimeOverridePolicy.forCourseMove]，四枚事实（要写的日与节次、那一行现在的
 * 日与节次）加上那一行**已经带没带旗标**，全部由本文件当参数递进去（本仓口径，同
 * [ConflictShiftWeekScopeTest]）。"handler 有没有真的把这一判端给落库那一行"由源码核对守卫
 * [ManualTimeOverrideWiringGuardTest] 钉，本文件只钉判据本身。⚠️ 与那张表一样，这里复跑的是
 * **结论与算式**，不是 Room、不是设备：**未装机**。
 *
 * ## 这一判要挡的东西
 *
 * 标 `isManualOverride = true` 很贵：那一行从此退出教务刷新的匹配与覆盖（教务之后改课名/教师/
 * 地点/周次都不再传导），刷新还会把教务那一版当新课补进来（同一门课两张卡）。T133 把拖课那一枚
 * copy 无条件标真，前提是"落点侧已经闸在『时间真的变了』上" —— 而长按菜单/卡片动作的
 * 「移动到…」那一路**没有同值闸**（选择框初值就是出发那一格，确认后无条件回调），于是
 * "打开、什么都不改、点两下确认"也会把一门课标成 manual。表里 `expectFlag = false`
 * 那三格钉的就是这一记空操作**不许**换来那两件事。
 *
 * ## 每格两条独立读数
 *
 * 1. `expectFlag` —— 表里逐格写着的期望；
 * 2. `keyChanged` —— 用**生产代码** [ImportPlanner.courseKey] 比对"写进去那一行"与"出发那一行"
 *    的身份钥匙。这一判的成立条件就是"这一趟把钥匙换掉了"，所以两者必须同向：
 *    `expectFlag == (原来已标 manual || keyChanged)`。判据朝宽扭（恒真、或连 `weeks` 一起判）⇒
 *    `expectFlag = false` 那三格红；朝窄扭（恒 false、或把判据反着取、或丢掉粘住那一支）⇒
 *    真改动那几格红。
 *
 * 另有一判专盯"这一趟确实什么都没改"：把旗标抹平以后 `written == before` 必须逐字段相等，
 * 真改动那几格则必须不等 —— 空操作按空操作的样子认，不靠散文。
 */
class ManualTimeOverridePolicyTest {

    private val term = "2026-2027-1"
    private val allWeeks = (1..16).toList()

    private fun row(
        dayOfWeek: Int,
        periods: List<Int>,
        manual: Boolean = false,
    ) = Course(
        id = 5L,
        name = "高等数学",
        teacher = "张三",
        location = "J3-101",
        dayOfWeek = dayOfWeek,
        periods = periods,
        weeks = allWeeks,
        sourceGroupKey = "G1",
        semesterCode = term,
        credit = 3.5,
        isManualOverride = manual,
    )

    /**
     * 一格 = 一次「从 [Cell.before] 这一行出发、由拖拽或选择框交回那两枚数」的移动。
     *
     * [Cell.targetSegmentStart] / [Cell.draggedSegmentStart] 是 `WeekView` 那一侧的两枚数（被拖
     * 那一段的目标起始节与出发起始节），handler 拿到的是**换算后的整课起始节** —— 这一层换算
     * （`CourseMoveRequest.courseStartPeriod`）照抄在 [writtenRow] 里，免得表里摆出真码给不出的输入。
     */
    private class Cell(
        val name: String,
        val before: Course,
        /** 交回 handler 的目标日列（`WeekView` 的 index，0..6；落库是 `+1`） */
        val newDayIndex: Int,
        val targetSegmentStart: Int,
        val draggedSegmentStart: Int,
        /** 落库那一行的 `isManualOverride` 该是什么：本卡的两格都在这里 */
        val expectFlag: Boolean,
        val why: String,
    )

    private val table = listOf(
        Cell(
            name = "选择框同值·单段（周一 1-2 打开又选回周一第 1 节）",
            before = row(dayOfWeek = 1, periods = listOf(1, 2)),
            newDayIndex = 0,
            targetSegmentStart = 1,
            draggedSegmentStart = 1,
            expectFlag = false,
            why = "本卡的病状本体：dayOfWeek 没变、delta=0 ⇒ 一笔逐字段什么都没改的写库，" +
                "不许把这门课标成 manual（标了就退出教务刷新 + 多一张重复卡）",
        ),
        Cell(
            name = "选择框同值·多段连排（周一 1-2 + 9-10，从 9-10 那段开框又选回第 9 节）",
            before = row(dayOfWeek = 1, periods = listOf(1, 2, 9, 10)),
            newDayIndex = 0,
            targetSegmentStart = 9,
            draggedSegmentStart = 9,
            expectFlag = false,
            why = "段基准换算以后整课起点没动（courseStartPeriod == course.startPeriod）⇒ 照样是空操作",
        ),
        Cell(
            name = "拖回原地（onDragEnd 那一闸本来就挡住了，这里验判据与它同向）",
            before = row(dayOfWeek = 3, periods = listOf(5, 6)),
            newDayIndex = 2,
            targetSegmentStart = 5,
            draggedSegmentStart = 5,
            expectFlag = false,
            why = "`WeekView.kt:899-902` 与 `:1030-1033` 让这一趟回不到 handler；万一有人把那一闸拆了，" +
                "判据在这里独立挡住（两道闸同向，不是拿本判据去重复判一次）",
        ),
        Cell(
            name = "只换上课日（周一 1-2 → 周三 1-2）",
            before = row(dayOfWeek = 1, periods = listOf(1, 2)),
            newDayIndex = 2,
            targetSegmentStart = 1,
            draggedSegmentStart = 1,
            expectFlag = true,
            why = "delta=0、periods 一字未动，但 dayOfWeek 在钥匙里 ⇒ 必须标 manual（T133 的正面账）",
        ),
        Cell(
            name = "同一天换起始节（周一 1-2 → 周一 3-4）",
            before = row(dayOfWeek = 1, periods = listOf(1, 2)),
            newDayIndex = 0,
            targetSegmentStart = 3,
            draggedSegmentStart = 1,
            expectFlag = true,
            why = "上课日没动，periods 整串平移 ⇒ 钥匙换了一把，必须标",
        ),
        Cell(
            name = "拖非首段后挪两节（1-2 + 9-10 的 9-10 段 → 第 11 节起）",
            before = row(dayOfWeek = 1, periods = listOf(1, 2, 9, 10)),
            newDayIndex = 0,
            targetSegmentStart = 11,
            draggedSegmentStart = 9,
            expectFlag = true,
            why = "段基准换算过的真改动（整课平移 +2 ⇒ 1-2 那一段也变成 3-4）：必须标。" +
                "这一格同时钉住 handler 那两枚中间量没算歪（P0 位移错位那一族）",
        ),
        Cell(
            name = "空操作·但那一行本来就标着 manual",
            before = row(dayOfWeek = 1, periods = listOf(1, 2), manual = true),
            newDayIndex = 0,
            targetSegmentStart = 1,
            draggedSegmentStart = 1,
            expectFlag = true,
            why = "旗标是**粘的**：一次空操作不许把早已改过的行洗回 false，" +
                "那等于让它重新掉进教务匹配（与本卡要挡的病同一种、方向相反）",
        ),
        Cell(
            name = "真改了时间·且那一行本来就标着 manual",
            before = row(dayOfWeek = 1, periods = listOf(1, 2), manual = true),
            newDayIndex = 4,
            targetSegmentStart = 6,
            draggedSegmentStart = 1,
            expectFlag = true,
            why = "两枚析取支同时为真，结论仍是标 —— 粘住不许被写成「只有原来没标才判时间」",
        ),
        Cell(
            name = "手里那行节次没排序（库里排不出这一形状，判据与钥匙同向即可）",
            before = row(dayOfWeek = 1, periods = listOf(2, 1)),
            newDayIndex = 0,
            targetSegmentStart = 1,
            draggedSegmentStart = 1,
            expectFlag = true,
            why = "delta=0，但 `sorted()` 以后 List 不等，而 `courseKey` 里那一句 " +
                "`periods.joinToString(\",\")` 也同样从「2,1」变成「1,2」⇒ 判据与钥匙同向为真。" +
                "（写库前 `CourseConstraints.normalizePeriods` 恒排序，所以真码里这一格在库里排不出来）",
        ),
    )

    /** 复刻 handler 的算式（`HomeScreen.kt:439-451`）：越界早退 ⇒ 返回 null = 这一趟什么都不写 */
    private fun writtenRow(cell: Cell): Course? {
        val courseStartPeriod = cell.before.startPeriod +
            (cell.targetSegmentStart - cell.draggedSegmentStart)
        val delta = courseStartPeriod - cell.before.startPeriod
        val shiftedPeriods = cell.before.periods.map { it + delta }.sorted()
        if (shiftedPeriods.any { it < 1 || it > CourseConstraints.MAX_PERIOD }) return null
        val newDayOfWeek = cell.newDayIndex + 1
        return cell.before.copy(
            dayOfWeek = newDayOfWeek,
            periods = shiftedPeriods,
            isManualOverride = ManualTimeOverridePolicy.forCourseMove(
                originalIsManualOverride = cell.before.isManualOverride,
                newDayOfWeek = newDayOfWeek,
                originalDayOfWeek = cell.before.dayOfWeek,
                newPeriods = shiftedPeriods,
                originalPeriods = cell.before.periods,
            ),
        )
    }

    private fun Cell.reading(written: Course?): String {
        val head = "  existing = ${before.dayOfWeek}/${before.periods}/manual=${before.isManualOverride}" +
            "\n  交回 handler：日列=$newDayIndex、段起点 $draggedSegmentStart→$targetSegmentStart"
        val tail = if (written == null) {
            "\n  handler 越界早退（什么都不写）"
        } else {
            "\n  写进去 = ${written.dayOfWeek}/${written.periods}/manual=${written.isManualOverride}"
        }
        return head + tail
    }

    /** 这一趟到底动没动时间 —— 拿**行本身**读数，不拿判据的返回值（两条独立账） */
    private fun Cell.timeReallyChanged(written: Course): Boolean =
        written.dayOfWeek != before.dayOfWeek || written.periods != before.periods

    // ─────────────── ① 两格都要有：同值不许翻、真改了日或起始节必须翻 ───────────────

    @Test
    fun `同值移动没把行标成 manual 真改了日或起始节必须标`() {
        for (cell in table) {
            val row = requireNotNull(writtenRow(cell)) {
                "档位「${cell.name}」：${cell.reading(null)} —— 这一格 handler 早退了，表里排的是给得出的输入"
            }
            assertEquals(
                "档位「${cell.name}」：旗标判错了。" +
                    "\n  ${cell.reading(row)}" +
                    "\n  ${cell.why}" +
                    "\n  期望 isManualOverride = ${cell.expectFlag}" +
                    "\n复算：sed -n '61,69p' app/src/main/java/com/buaa/schedule/ui/home/ManualTimeOverridePolicy.kt" +
                    "\n  落库处：sed -n '439,451p' app/src/main/java/com/buaa/schedule/ui/home/HomeScreen.kt",
                cell.expectFlag,
                row.isManualOverride,
            )
            if (!cell.timeReallyChanged(row) && !cell.before.isManualOverride) {
                assertEquals(
                    "档位「${cell.name}」：这一格既没动时间也没有粘住可言 ⇒ 落库那一整行必须与出发那一行" +
                        "**逐字段一模一样**，它才是一笔空操作；本卡判它「不许标 manual」的前提（什么都没改）" +
                        "也就立得住。动了别的字段就不是空操作，那一判要重摆。${cell.reading(row)}",
                    cell.before,
                    row,
                )
            }
            if (cell.timeReallyChanged(row)) {
                assertNotEquals(
                    "档位「${cell.name}」：表里说这一格动了时间（日或节次与出发那一行不同），" +
                        "可整行读回来一模一样。${cell.reading(row)}",
                    cell.before,
                    row,
                )
            }
            if (!cell.timeReallyChanged(row) && cell.before.isManualOverride) {
                assertEquals(
                    "档位「${cell.name}」：粘住那一格这一趟没动时间 ⇒ 整行（含旗标）必须**原样**，" +
                        "判据只许决定旗标那一维，不许顺手把别的时间量也重算一遍。${cell.reading(row)}",
                    cell.before,
                    row,
                )
            }
        }
    }

    /** 表里三格 `expectFlag = false` 是本卡的正面（同值），其余是 T133 的正面（真改动）。 */
    @Test
    fun `表里同值与真改动两族各有格子 不许拿删格子当变绿的修法`() {
        val sameValue = table.filterNot { it.expectFlag }.map { it.name }.sorted()
        assertEquals(
            "同值那一族（不许标 manual）应当正好这三格：选择框单段 / 选择框多段连排 / 拖回原地。" +
                "少一格 = 本卡的病又漏了一味药；拿删格子让测试变绿不算修好" +
                "（实到 $sameValue）",
            listOf(
                "拖回原地（onDragEnd 那一闸本来就挡住了，这里验判据与它同向）",
                "选择框同值·单段（周一 1-2 打开又选回周一第 1 节）",
                "选择框同值·多段连排（周一 1-2 + 9-10，从 9-10 那段开框又选回第 9 节）",
            ).sorted(),
            sameValue,
        )
        assertEquals(
            "真改动那一族（必须标）在这张表里应当有六格：只换日 / 换起始节 / 拖非首段 / " +
                "粘住两格 / 未排序那一格。判据被朝窄扭时红的就是这一族，不许把它删空",
            6,
            table.count { it.expectFlag },
        )
    }

    // ─────────────── ② 与身份钥匙同向：判据的成立条件就是「换了钥匙」 ───────────────

    @Test
    fun `旗标与身份钥匙的换没换同向 没换钥匙就不许多占一行`() {
        for (cell in table) {
            val written = writtenRow(cell) ?: continue
            val keyChanged = ImportPlanner.courseKey(written) != ImportPlanner.courseKey(cell.before)
            assertEquals(
                "档位「${cell.name}」：`isManualOverride` 必须等于「原来已标 manual ⊷ 这一趟换了身份钥匙」。" +
                    "\n  ${cell.reading(written)}" +
                    "\n  钥匙换了吗 = $keyChanged（`ImportPlanner.courseKey` 现算）" +
                    "\n  ${cell.why}" +
                    "\n复算：sed -n '19,27p' app/src/main/java/com/buaa/schedule/domain/schedule/ImportPlanner.kt\n" +
                    "判据比钥匙宽 = 空操作也退出教务刷新（同值那三格红在这里）；" +
                    "判据比钥匙窄 = 真改了时间的行下次刷新整行消失、连提醒一起删",
                cell.before.isManualOverride || keyChanged,
                written.isManualOverride,
            )
        }
    }

    /**
     * 粘住那一格单独报一次：判据被写成"只判这一趟"（丢掉 `originalIsManualOverride`
     * 那一枚析取支）时红在这里 —— 一次空操作把编辑器早已标过的行洗回 false，
     * 下一轮教务刷新它就直接被顶回原时刻。
     */
    @Test
    fun `已经标过的行不许被一次空操作洗回未标`() {
        val before = row(dayOfWeek = 1, periods = listOf(1, 2), manual = true)
        val cell = Cell(
            name = "粘住",
            before = before,
            newDayIndex = 0,
            targetSegmentStart = 1,
            draggedSegmentStart = 1,
            expectFlag = true,
            why = "",
        )
        val written = requireNotNull(writtenRow(cell)) { "粘住那一格 handler 早退了：${cell.reading(null)}" }
        assertTrue(
            "同值移动以后旗标被洗成 false 了：${cell.reading(written)}",
            written.isManualOverride,
        )
        assertEquals(
            "粘住只许动旗标，不许顺手改节次（判据不是第二道缩放闸）",
            before.periods,
            written.periods,
        )
        assertEquals(
            "粘住只许动旗标，不许顺手改周次",
            before.weeks,
            written.weeks,
        )
    }

    // ─────────────── ③ 向导那一枚：为什么它不需要这一判（同值可达性判定） ───────────────

    /**
     * T133b 第 0 步第 4 条的账：`applyConflictShift` 那一记 copy 仍无条件标真，理由不能是
     * "它是解冲突所以必然不同"，得是真码里**出不了同值格**。
     *
     * 这里喂的是向导自己那条链（[ConflictDetector.findConflicts] →
     * [CourseConflictResolution.groupConflicts] → [CourseConflictResolution.suggestNearestFreeShift]，
     * target 取 `group.courses.firstOrNull()`，与 `ConflictWizardDialog.kt:185` 同一取法）：
     * 只要建议出得来，`suggestion.periods` 就必须 **≠** `target.periods` —— 扳机在
     * `CourseConflictResolution.kt:126` 的 `if (candidate == currentPeriods) continue`。
     */
    @Test
    fun `向导那一枚出不了同值格 建议里的节次必然与原判不同`() {
        val schedules = listOf(
            // 两门课整学期互撞（组周 == target.weeks 那一格）
            listOf(
                wizardCourse(1, "A", listOf(1, 2), allWeeks),
                wizardCourse(2, "B", listOf(2, 3), allWeeks),
            ),
            // A 满学期、B 只有 3-5 周（组周 ⊊ target.weeks 那一格）
            listOf(
                wizardCourse(1, "A", listOf(1, 2), allWeeks),
                wizardCourse(2, "B", listOf(2, 3), listOf(3, 4, 5)),
            ),
            // 传递闭包那一格：target 的周次比组宽、候选位也不止一个
            listOf(
                wizardCourse(1, "A", listOf(2, 3), (1..8).toList()),
                wizardCourse(2, "B", listOf(1, 2), allWeeks),
                wizardCourse(3, "C", listOf(3, 4), (9..16).toList()),
            ),
        )
        var suggestions = 0
        for (courses in schedules) {
            val groups = CourseConflictResolution.groupConflicts(ConflictDetector.findConflicts(courses))
            assertTrue("这一格根本组不出冲突组，喂不进向导", groups.isNotEmpty())
            for (group in groups) {
                val target = group.courses.firstOrNull() ?: continue
                val suggestion = CourseConflictResolution.suggestNearestFreeShift(target, courses)
                    ?: continue
                suggestions++
                assertNotEquals(
                    "向导给出的时段与 target 自己的时段一模一样 ⇒ `applyConflictShift` 那一记" +
                        "`isManualOverride = true` 就落在一笔空操作上，得跟拖课那一枚一起改成判据。" +
                        "\n  target = ${target.name} ${target.dayOfWeek}/${target.periods}，" +
                        "建议 = ${suggestion.periods}（shiftedBy=${suggestion.shiftedBy}）" +
                        "\n复算：sed -n '120,130p' app/src/main/java/com/buaa/schedule/domain/schedule/CourseConflictResolution.kt",
                    target.periods.sorted(),
                    suggestion.periods,
                )
                assertEquals(
                    "建议必须保持节次数量（只平移不缩放）：${suggestion.periods} vs ${target.periods}",
                    target.periods.size,
                    suggestion.periods.size,
                )
            }
        }
        assertTrue(
            "三张课表一格建议都没出，上面那两判就成了空话（向导按钮压根不出场）",
            suggestions > 0,
        )
    }

    /**
     * 第二半：`scopedWeeks` 那一维**能**同值 —— 判据在这里帮不上忙，也不该帮。
     *
     * [ConflictShiftWeekScope.weeksToShift] 的两记 no-op 分支（`ConflictShiftWeekScope.kt:63`、
     * `:65`）把调用点递进来的 `targetWeeks` 原样送回，于是 `weeks` 一字未动。这一格不许被当成
     * "向导也在空操作"的证据：周次根本不在 `courseKey` 里，而同一趟的 `periods` 已经被上面那一判
     * 保证换掉了。
     */
    @Test
    fun `向导唯一能同值的是周次那一维 而它不在身份钥匙里`() {
        val target = wizardCourse(1, "A", listOf(1, 2), allWeeks)
        val verbatim = ConflictShiftWeekScope.weeksToShift(allWeeks, target.weeks)
        assertSame(
            "组周盖满 target ⇒ 收窄是 no-op，weeks 必须**原样实例**送回" +
                "（`updateCourse` 那一判比的是 List 逐元素相等，重排过就会凭空拆一行）",
            target.weeks,
            verbatim,
        )
        assertEquals(
            "周次不参与身份钥匙 ⇒ 同值的周次够不着 manual 那一账（反过来说：谁把 weeks 也判进" +
                "本卡的判据，就是在给空操作之外的趟发旗标）",
            ImportPlanner.courseKey(target),
            ImportPlanner.courseKey(target.copy(weeks = listOf(1, 2, 3))),
        )
    }

    private fun wizardCourse(id: Long, name: String, periods: List<Int>, weeks: List<Int>) = Course(
        id = id,
        name = name,
        dayOfWeek = 1,
        periods = periods,
        weeks = weeks,
    )
}
