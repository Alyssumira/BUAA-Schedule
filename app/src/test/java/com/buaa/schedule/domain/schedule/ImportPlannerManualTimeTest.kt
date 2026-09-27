package com.buaa.schedule.domain.schedule

import com.buaa.schedule.domain.model.Course
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T133③a：把「教务刷新会不会把用户手改过时间的课冲回来」钉成表驱动单测（纯 JVM）。
 *
 * ## 病状与病根
 *
 * 用户在首页拖课 / 缩放进节次 / 在冲突向导里点「只改这些周」，改完当场生效；下一次教务刷新
 * （`ScheduleViewModel` 那一路 `repository.replaceSemesterCourses` ⇒
 * `ScheduleRepository.replaceSemesterCoursesInTx`）把这些课**按教务的原始时刻恢复**，
 * 顺带把挂在这些行上的提醒删掉。
 *
 * 病根在两处叠在一起：
 * 1. [ImportPlanner.courseKey] 把 `dayOfWeek` 与 `periods.joinToString(",")` 算进身份钥匙
 *    （复算：`sed -n '19,27p' app/src/main/java/com/buaa/schedule/domain/schedule/ImportPlanner.kt`）；
 * 2. [ImportPlanner.buildImportPlan] 的 `matchable` 只收未标 manual 的 existing 行、末尾只交回
 *    "标了 manual 的 existing + 本批 imported"（`:39` 与 `:61`）⇒ 一行**没标 manual** 的 existing，
 *    只要它的钥匙在本次 imported 里没有同名钥匙，就**根本不出现在最终 plan 里**，也就是被丢掉。
 *
 * 于是"改了时间"= "换了钥匙"= "下次刷新整行消失"。而刷新前那一秒界面上看起来是对的，
 * 所以这条链在用户眼里是"刷新把课冲回来了"，不是"删除"。
 *
 * ## 提醒那一维（症状的第二半）
 *
 * 丢的不只是行：`ScheduleRepository.deleteRemindersOfDroppedCourses(existing, plan)` 按
 * `keptIds = plan.map { it.id }` 反筛 `existing.map { it.id }`，被丢掉那一行的 id 不在 keptIds 里
 * ⇒ 它的提醒行被一起删掉。那枚函数是仓储层的 `private suspend fun`，而 `:app` 的 JVM 测试依赖面
 * 只有 `junit` 一枚（**没有 Robolectric、没有能在 JVM 上跑 Room 的缝**）⇒ 本文件用
 * [droppedCourseIds] 把**那两行算式原样**在 plan 上复算一遍，给出"这一格掉没掉提醒"的落点；
 * "仓储里那一刀确实还在、确实按这两行算"由源码核对守卫 `ManualTimeOverrideWiringGuardTest` 钉死。
 *
 * ## 表的两侧
 *
 * 每格都带 `expectSurvives`：判据朝"更宽"扭（刷新覆盖一切）⇒ 已标 manual 那几格红；
 * 朝"更窄"扭（凡改过的都丢）⇒ 未标 manual 那几格红。`droppedCourseIds` 那一列同时给出
 * "掉不掉提醒"，所以两半症状每格都有落点，不是只在散文里说"还会丢提醒"。
 */
class ImportPlannerManualTimeTest {

    private val term = "2026-2027-1"
    private val allWeeks = (1..16).toList()

    /** 教务给的那一版：周一 1-2 节，整学期，未标 manual（教务抓取回来的行恒为 false） */
    private fun schoolRow(
        id: Long = 0L,
        dayOfWeek: Int = 1,
        periods: List<Int> = listOf(1, 2),
        weeks: List<Int> = allWeeks,
        name: String = "高等数学",
    ) = Course(
        id = id,
        name = name,
        teacher = "张三",
        location = "J3-101",
        dayOfWeek = dayOfWeek,
        periods = periods,
        weeks = weeks,
        sourceGroupKey = "G1",
        semesterCode = term,
        credit = 3.5,
    )

    /**
     * 表 ①：一格一笔。`editedRow` 是**用户手改以后库里的这一行**（existing），
     * `importedRow` 是**下一次刷新教务给的同一门课**（原始时刻，id 由 DAO 现取，恒为 0L）。
     */
    private data class Cell(
        val name: String,
        val editedRow: Course,
        val importedRow: Course,
        val expectSurvives: Boolean,
        val why: String,
    )

    private val table = listOf(
        Cell(
            name = "拖到别的一天·未标 manual（改前那格）",
            editedRow = schoolRow(id = 5L).copy(dayOfWeek = 3),
            importedRow = schoolRow(),
            expectSurvives = false,
            why = "dayOfWeek 在钥匙里 ⇒ 改过的行钥匙对不上 imported ⇒ 整行不进 plan（这就是病状本身）",
        ),
        Cell(
            name = "拖到别的一天·已标 manual（T133 的落点）",
            editedRow = schoolRow(id = 5L).copy(dayOfWeek = 3, isManualOverride = true),
            importedRow = schoolRow(),
            expectSurvives = true,
            why = "标了 manual 才进得了 `existing.filter { it.isManualOverride }` 那一支",
        ),
        Cell(
            name = "拖到更晚的节次·未标 manual（改前那格）",
            editedRow = schoolRow(id = 5L).copy(periods = listOf(3, 4)),
            importedRow = schoolRow(),
            expectSurvives = false,
            why = "periods.joinToString(\",\") 也在钥匙里，同一天换节次一样丢",
        ),
        Cell(
            name = "拖到更晚的节次·已标 manual（T133 的落点）",
            editedRow = schoolRow(id = 5L).copy(periods = listOf(3, 4), isManualOverride = true),
            importedRow = schoolRow(),
            expectSurvives = true,
            why = "同上：只有 manual 那一支能活过一次刷新",
        ),
        Cell(
            name = "缩放进节次（1-2 → 1-2-3）·未标 manual（改前那格）",
            editedRow = schoolRow(id = 5L).copy(periods = listOf(1, 2, 3)),
            importedRow = schoolRow(),
            expectSurvives = false,
            why = "handleCourseResize 改的就是 periods：不标的话同一族病",
        ),
        Cell(
            name = "缩放进节次·已标 manual（T133 的落点）",
            editedRow = schoolRow(id = 5L).copy(periods = listOf(1, 2, 3), isManualOverride = true),
            importedRow = schoolRow(),
            expectSurvives = true,
            why = "缩放与拖课共用同一枚判据",
        ),
        Cell(
            name = "向导「只改这些周」拆出的新行·未标 manual（改前那格）",
            editedRow = schoolRow(id = 5L).copy(dayOfWeek = 3, periods = listOf(3, 4), weeks = listOf(5)),
            importedRow = schoolRow(),
            expectSurvives = false,
            why = "partialWeeks 拆出来的是一枚新行，钥匙也是新的 ⇒ 下次刷新照样被冲掉",
        ),
        Cell(
            name = "向导「只改这些周」拆出的新行·已标 manual（T133 的落点）",
            editedRow = schoolRow(id = 5L).copy(
                dayOfWeek = 3,
                periods = listOf(3, 4),
                weeks = listOf(5),
                isManualOverride = true,
            ),
            importedRow = schoolRow(),
            expectSurvives = true,
            why = "向导那一记 copy 带旗标 ⇒ 拆出来的那一行活得下来",
        ),
        Cell(
            name = "整门课平移两段连排（1-2 + 9-10 → 3-4 + 11-12）·已标 manual",
            editedRow = schoolRow(id = 5L).copy(periods = listOf(3, 4, 11, 12), isManualOverride = true),
            importedRow = schoolRow(),
            expectSurvives = true,
            why = "多段连排整课平移后 periods 是四元列表，钥匙整个换掉了",
        ),
        Cell(
            name = "用户没动时间、教务改了上课日 ⇒ 必须传导（不许误伤）",
            editedRow = schoolRow(id = 5L),
            importedRow = schoolRow(dayOfWeek = 4),
            expectSurvives = false,
            why = "这一格钉的是**反面**：没标 manual 的原行被教务新版顶掉是正确行为，" +
                "T133 的修法不许把它也保下来（判据朝窄扭到这里必须红）",
        ),
    )

    /**
     * 原样照抄 `ScheduleRepository.deleteRemindersOfDroppedCourses` 那两行算式
     * （keptIds / droppedIds），用于给出"这一格掉不掉提醒"的落点。
     * 仓储里那一刀本身在 JVM 上跑不到（private suspend + Room），由守卫钉形状。
     */
    private fun droppedCourseIds(existing: List<Course>, plan: List<Course>): List<Long> {
        val keptIds = plan.mapTo(HashSet()) { it.id }
        return existing.map { it.id }.filterNot { it in keptIds }
    }

    // ─────────────── ① 表 ①：留的是哪一行、id 归谁、提醒掉不掉 ───────────────

    @Test
    fun `刷新只留标了 manual 的手改行 掉没掉提醒逐格报出`() {
        for (cell in table) {
            val existing = listOf(cell.editedRow)
            val plan = ImportPlanner.buildImportPlan(existing, listOf(cell.importedRow))
            val kept = plan.filter { it.id == cell.editedRow.id }
            val planShape = plan.joinToString { c -> "id=${c.id} ${c.dayOfWeek}/${c.periods}/manual=${c.isManualOverride}" }

            assertEquals(
                "表①「${cell.name}」：那一行" +
                    (if (cell.expectSurvives) "应当" else "不应当") + "活过这次刷新。" +
                    "\n  ${cell.why}" +
                    "\n  existing = ${cell.editedRow.dayOfWeek}/${cell.editedRow.periods}/manual=" +
                    "${cell.editedRow.isManualOverride}，imported = " +
                    "${cell.importedRow.dayOfWeek}/${cell.importedRow.periods}" +
                    "\n  plan = [$planShape]" +
                    "\n复算：sed -n '39p;61p' app/src/main/java/com/buaa/schedule/domain/schedule/ImportPlanner.kt",
                cell.expectSurvives,
                kept.isNotEmpty(),
            )

            val dropped = droppedCourseIds(existing, plan)
            val expectDropped: List<Long> = if (cell.expectSurvives) emptyList() else listOf(cell.editedRow.id)
            assertEquals(
                "表①「${cell.name}」：提醒那一维与" +
                    "`deleteRemindersOfDroppedCourses` 的 keptIds 反筛必须同格同结论" +
                    "（dropped=$dropped 而 plan=[$planShape]）",
                expectDropped,
                dropped,
            )
        }
    }

    /**
     * 活下来的那一行必须**逐字段还是用户摆的那一份**：刷新不许把课名/教师/地点/时间/周次里的
     * 任何一项顶回教务的旧值（这正是"manual 退出匹配"的正面语义）。
     */
    @Test
    fun `活下来的那一行不许被教务那一版顶掉任何一项`() {
        for (cell in table.filter { it.expectSurvives }) {
            val plan = ImportPlanner.buildImportPlan(listOf(cell.editedRow), listOf(cell.importedRow))
            assertEquals(
                "表①「${cell.name}」：id=5 那一行应当原样留在那儿",
                listOf(cell.editedRow),
                plan.filter { it.id == cell.editedRow.id },
            )
            val survivor = plan.first { it.id == cell.editedRow.id }
            assertEquals("上课日不许被顶回教务值", cell.editedRow.dayOfWeek, survivor.dayOfWeek)
            assertEquals("节次不许被顶回教务值", cell.editedRow.periods, survivor.periods)
            assertEquals("周次不许被顶回教务值", cell.editedRow.weeks, survivor.weeks)
            assertTrue("留在 plan 里的那一行读回来仍是 manual", survivor.isManualOverride)
        }
    }

    /**
     * 代价那一格（要说清、也要钉住）：标了 manual 以后，教务那一版不再顶掉原行，
     * 而是**以一枚新行补进课表** ⇒ 同一门课两张卡（一张教务时刻、一张手挪的）。
     * 这是 manual 机制本来的语义（编辑器保存/组外观走的是同一枚旗标），T133 只是把三处
     * "改了时间却没标"的落点接上；谁想拿掉这一格，就得连带把"教务那一版怎么收"一起判。
     */
    @Test
    fun `标了 manual 的代价是教务那一版以新行补进来同一门课两张卡`() {
        val existing = schoolRow(id = 5L).copy(dayOfWeek = 3, isManualOverride = true)
        val plan = ImportPlanner.buildImportPlan(listOf(existing), listOf(schoolRow()))

        assertEquals(
            "manual 行 + 教务那一版 ⇒ plan 两行。谁把它并成一行了，本卡判的代价就得重算",
            2,
            plan.size,
        )
        val schoolCopyOnTheBoard = plan.first { it.id != 5L }
        assertEquals(
            "教务那一版拿的是**新行 id=0L**（不许去抢 manual 行的 id，否则挂在 5 上的提醒会挪家）",
            0L,
            schoolCopyOnTheBoard.id,
        )
        assertEquals(1, schoolCopyOnTheBoard.dayOfWeek)
        assertFalse(schoolCopyOnTheBoard.isManualOverride)
        // 手挪那一行的提醒保住（keptIds 含 5），教务那一版没有提醒可删
        assertEquals(emptyList<Long>(), droppedCourseIds(listOf(existing), plan))
    }

    // ─────────────── ② 部分周次那一支：同时动两行 ⇒ "标哪一行"是两个问题 ───────────────

    /**
     * 「只改这些周」在 `updateCoursePartialWeeks` 里**同时动两行**：原行的 `weeks` 被收窄成
     * remaining、再插出一枚新行。本卡只让**拆出来的那一行**带旗标（= 调用点那记 copy 带过去的），
     * 被收窄的原行留 `false` 是有意的。
     *
     * 下面这两格把两种判法都摆出来，红的是"顺手把原行也标上"那一个方向：
     * 原行的 `periods` 仍是教务给的那一份 ⇒ 标了 manual 就等于让教务那一版在**其余每一周**
     * 都补一张重复卡（现状只重复被挪走的那一周）。
     */
    @Test
    fun `只改这些周以后拆出的新行留着而被收窄的原行仍与教务同 id 复用`() {
        val narrowedOriginal = schoolRow(id = 10L, weeks = listOf(1, 2, 3, 4, 6, 7, 8))
        val movedFragment = schoolRow(id = 99L, dayOfWeek = 3, periods = listOf(3, 4), weeks = listOf(5))
            .copy(isManualOverride = true)
        val plan = ImportPlanner.buildImportPlan(
            listOf(narrowedOriginal, movedFragment),
            listOf(schoolRow(weeks = allWeeks)),
        )

        val planShape = plan.joinToString { c -> "id=${c.id} ${c.dayOfWeek}/${c.periods}/weeks=${c.weeks}" }
        assertEquals(
            "两行都该在：manual 新行 + 与原 id 复用的教务行（plan=[$planShape]）",
            setOf(10L, 99L),
            plan.map { it.id }.toSet(),
        )
        // 原行 id 复用 ⇒ 挂在 10 上的提醒不会掉
        assertEquals(emptyList<Long>(), droppedCourseIds(listOf(narrowedOriginal, movedFragment), plan))
        assertEquals(
            "被挪走的那一周（第 5 周）会在原时刻**重新出现** —— 这是本卡明记的残账：原行的 weeks " +
                "被教务那一版整串恢复，ImportPlanner 没有「把被 manual 行占用的周次挖掉」这一档",
            allWeeks,
            plan.first { it.id == 10L }.weeks,
        )
        assertEquals(
            "manual 那一行只带被挪走的那一周",
            listOf(5),
            plan.first { it.id == 99L }.weeks,
        )
    }

    /** 反面一格：把原行也标上 manual 会变成什么（本卡不许，红在这里就是不许） */
    @Test
    fun `顺手把被收窄的原行也标上 manual 会让教务那一版在每一周多补一张卡`() {
        val narrowedOriginalMarked = schoolRow(id = 10L, weeks = listOf(1, 2, 3, 4, 6, 7, 8))
            .copy(isManualOverride = true)
        val movedFragment = schoolRow(id = 99L, dayOfWeek = 3, periods = listOf(3, 4), weeks = listOf(5))
            .copy(isManualOverride = true)
        val plan = ImportPlanner.buildImportPlan(
            listOf(narrowedOriginalMarked, movedFragment),
            listOf(schoolRow(weeks = allWeeks)),
        )

        assertEquals(
            "两行 manual ⇒ 教务那一版无行可配、只能以第三行补进来：整学期每张卡都翻倍。" +
                "这一格是「本卡为何只标拆出来的那一行」的证据，不是要实现的形状",
            3,
            plan.size,
        )
    }

    // ─────────────── ③ 钥匙形状：本卡修法成立的前提 ───────────────

    /**
     * 这一组是**扳机**：T133 的修法（在改时间的落点标 manual）完全是冲着
     * "`dayOfWeek` / `periods` 在身份钥匙里"这一形状开的。谁哪天把这两维摘出去，
     * 本卡这几格当场红，就得回来重判"还要不要标 manual"（摘掉以后同 key 会**合并成一行、
     * 周次取并集**，那时一门课的不同节次段就撞在一起了）。
     */
    @Test
    fun `身份钥匙仍然包含上课日与节次 本卡修法的前提还立着`() {
        val base = schoolRow(id = 5L)
        assertEquals(
            "只改周次/学分/别名都不该换钥匙（换了就复用到新 id ⇒ 提醒掉）",
            ImportPlanner.courseKey(base),
            ImportPlanner.courseKey(base.copy(weeks = listOf(1), credit = null, alias = "高数")),
        )
        assertFalse(
            "换了上课日还是同一把钥匙 ⇒ dayOfWeek 被摘出去了。" +
                "复算：sed -n '19,27p' app/src/main/java/com/buaa/schedule/domain/schedule/ImportPlanner.kt\n" +
                "那时本卡的「标 manual」修法要连表①一起重判",
            ImportPlanner.courseKey(base) == ImportPlanner.courseKey(base.copy(dayOfWeek = 4)),
        )
        assertFalse(
            "换了节次还是同一把钥匙 ⇒ periods 被摘出去了（同一门课的 1-2 与 9-10 两节段会并成一行）",
            ImportPlanner.courseKey(base) == ImportPlanner.courseKey(base.copy(periods = listOf(9, 10))),
        )
    }
}
