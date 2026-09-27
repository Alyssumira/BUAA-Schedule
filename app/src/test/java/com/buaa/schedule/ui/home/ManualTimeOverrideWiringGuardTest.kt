package com.buaa.schedule.ui.home

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T133③b：钉「用户在首页拖课 / 缩放进节次 / 冲突向导点『只改这些周』那三处落库，
 * 每一处都真的带着 `isManualOverride = true`」——纯源码核对（JVM，无 Robolectric、无设备、不读时钟）。
 *
 * ## 为什么病在接线而不在内核
 *
 * 内核那一侧 [com.buaa.schedule.domain.schedule.ImportPlanner.buildImportPlan] 的行为是对的，
 * 而且本卡不许动它（同 key 的条目会**合并成一行、周次取并集**，一门课的不同节次段今天正是靠
 * `courseKey` 里的 `dayOfWeek` + `periods` 两维分开的）。病在**调用点没交旗标**：
 * `courseKey` 含这两维（`ImportPlanner.kt:19-27`）+ `matchable` 只收未标 manual 的行（`:39`）+
 * 末尾只交回「标了 manual 的 existing + 本批 imported」（`:61`）⇒ 改过时间又没标 manual 的那一行，
 * 钥匙在本次 imported 里没有同名项 ⇒ **不进 plan ⇒ 整行丢掉**，挂在它上面的提醒随
 * `ScheduleRepository.deleteRemindersOfDroppedCourses` 一起被删。
 * 判据形状与逐格账在 `ImportPlannerManualTimeTest`（表①），本文件只管接线。
 *
 * ## 为什么必须按落点取判据
 *
 * 全站写 `isManualOverride` 的 UI 落点只有三枚（拖课 / 缩放 / 向导）加一枚组外观。只数总枚数的话，
 * 「把旗标从拖课那处搬到别处去」也算对 ⇒ 本守卫按**文件 / 函数体 / 逐字 / 次序**四把尺子取。
 *
 * ## T133b 改的那一枚：拖课那枚旗标不再恒真
 *
 * T133 给三处 copy 一律标 `= true`，前提是"落点侧已经闸在时间真的变了上"——那前提只覆盖了
 * 三分之二的落点：长按菜单/卡片动作的「移动到…」那一路**没有同值闸**（`onConfirm` 无条件把
 * 选中的日与节装成 `CourseMoveRequest`，确认窗再无条件回调 `onCourseMove`），于是"打开选择框、
 * 选回原来那一格、点两下确认"这一笔空操作也会把一门课标成 manual ⇒ 它从此退出教务刷新的匹配与
 * 覆盖，刷新还补一张重复卡。T133b 把拖课那一枚改成 [ManualTimeOverridePolicy.forCourseMove] 算的
 * 值；缩放与向导两枚**照旧恒真**（落点闸分别还在 `WeekView.kt:1113` 与
 * `CourseConflictResolution.kt:126`，档 ⑫ 钉着那三道闸）。判据本身逐格红在哪，见
 * `ManualTimeOverridePolicyTest`（表②）。
 *
 * ## 钉十二档
 *
 * 1. `handleCourseMove` 体内 `val shifted = course.copy(...)` 那一次调用里，`dayOfWeek`、
 *    `periods`、`isManualOverride` 三项**同在一记 copy 内**且按此次序，且第三项交的是判据的
 *    结果（不再是一枚字面 `true`）；
 * 2. 该 handler 的三支写库（整课 / 本周但拿不到周号 / 本周 partialWeeks）递的都是 `shifted`，
 *    没有绕过它去 `updateCourse(course)` 的旁路；
 * 3. `handleCourseResize` 体内那记 copy 逐字带旗标（同一种病：改的就是 `periods`）；
 * 4. `applyConflictShift` 体内那记 copy 同时带 `weeks = scopedWeeks`、`partialWeeks = true`
 *    与旗标（T131 的账不许被本卡顶掉）；
 * 5. 落点册子**按新形状两本数**：字面 `isManualOverride = true` 只剩缩放 / 向导 / 组外观各一枚，
 *    由判据供给的 `isManualOverride = manualTimeOverride` 只有拖课那一枚；编辑器那一枚既有判据
 *    表达式原样在（本卡照它的写法，不许顺手改它）；
 * 6. 提醒那一刀确实挂在这条链上：`replaceSemesterCoursesInTx` 五步次序 + keptIds/droppedIds
 *    两行逐字 + 教务刷新那一句调用点仍在；
 * 7. 部分周次那一支「标哪一行」：`updateCoursePartialWeeks` 同时动两行，**只有拆出来的新行**该带
 *    旗标，被收窄的原行那一记 copy 里不许出现旗标；
 * 8. 本卡修法成立的前提：`courseKey` 仍含 `dayOfWeek` 与 `periods` 两维；谁摘掉它就该回来重判；
 * 9. 报错消息里的行号自检：`lineOf` 与按行切分两种独立算法必须给出同一个数（本仓登记过把行号
 *    拼错的洞，故行号一律显式加括号，不写左结合的那一种）；
 * 10. 拖课那一枚旗标确实是**内核算出来的**：`val manualTimeOverride = ManualTimeOverridePolicy
 *    .forCourseMove(` 那五枚具名实参逐字在、按次序，且递进去的"要写的值"与"那一行现在的值"
 *    就是同一记 copy 落库/比对的那两份（不许各算一遍）；
 * 11. 判据内核仍是纯判据：`ManualTimeOverridePolicy.kt` 里零 import（android/androidx/Room/JVM
 *    时钟一律进不来）、全仓只有这一处 `fun forCourseMove(`、函数本体仍是那三判的析取；
 * 12. 反向格子：选择框那条路**必须还在**（不许拿"删掉不闸的那一路"当修法），且它的
 *    `onConfirm` 体内一道 `if` 都没有 —— 这正是本卡判据必须收在 handler 的证据。
 */
class ManualTimeOverrideWiringGuardTest {

    private companion object {
        const val HOME_SCREEN = "com/buaa/schedule/ui/home/HomeScreen.kt"
        const val WIZARD = "com/buaa/schedule/ui/home/ConflictWizardDialog.kt"
        const val WEEK_VIEW = "com/buaa/schedule/ui/home/WeekView.kt"
        const val POLICY = "com/buaa/schedule/ui/home/ManualTimeOverridePolicy.kt"
        const val CONFLICT_RESOLUTION = "com/buaa/schedule/domain/schedule/CourseConflictResolution.kt"
        const val EDITOR = "com/buaa/schedule/ui/editor/CourseEditorScreen.kt"
        const val REPOSITORY = "com/buaa/schedule/data/repository/ScheduleRepository.kt"
        const val VIEW_MODEL = "com/buaa/schedule/ui/ScheduleViewModel.kt"
        const val PLANNER = "com/buaa/schedule/domain/schedule/ImportPlanner.kt"

        const val MOVE_COPY_ANCHOR = "val shifted = course.copy("
        const val MOVE_LAMBDA_ANCHOR = "val handleCourseMove: (Course, Int, Int, Boolean) -> Unit = remember("
        const val RESIZE_LAMBDA_ANCHOR = "val handleCourseResize: (Course, List<Int>) -> Unit = remember("
        const val RESIZE_COPY = "course.copy(periods = newPeriods.sorted(), isManualOverride = true)"
        const val APPLY_SIGNATURE = "suspend fun applyConflictShift("
        const val WIZARD_COPY = "target.copy(periods = newPeriods, weeks = scopedWeeks, isManualOverride = true)"
        const val FLAG_WRITE = "isManualOverride = true"
        const val EDITOR_PRECEDENT =
            "isManualOverride = initialCourse?.isManualOverride == true || initialCourse?.sourceGroupKey != null,"
        const val REPLACE_SIGNATURE = "private suspend fun replaceSemesterCoursesInTx("
        const val PARTIAL_SIGNATURE = "suspend fun updateCoursePartialWeeks("
        const val NARROW_ORIGINAL = "courseDao.update(original.copy(weeks = remaining.sorted()).toEntity())"
        const val INSERT_FRAGMENT = "courseDao.insert(normalized.copy(id = 0L).toEntity())"
        const val REFRESH_CALL = "repository.replaceSemesterCourses(fetched.semester, fetched.courses)"

        // ── T133b：拖课那一枚旗标改由判据内核算 ──
        const val JUDGED_WRITE = "isManualOverride = manualTimeOverride"
        const val MOVE_JUDGE_ANCHOR = "val manualTimeOverride = ManualTimeOverridePolicy.forCourseMove("
        const val POLICY_SIGNATURE = "fun forCourseMove("
        const val POLICY_BODY =
            "originalIsManualOverride || newDayOfWeek != originalDayOfWeek || newPeriods != originalPeriods"
        const val NEW_DAY_LOCAL = "val newDayOfWeek = newDayIndex + 1"

        // ── 三处落点的同值闸（判据只补第三处没有的那一道） ──
        const val DRAG_GATE = "d.targetDayIndex != d.originDayIndex ||"
        const val RESIZE_GATE = "if (merged != r.course.periods) {"
        const val SUGGESTION_GATE = "if (candidate == currentPeriods) continue"
        const val PICKER_ON_CONFIRM = "onConfirm = { dayIndex, startPeriod ->"
        const val PICKER_INITIAL_DAY =
            "var dayIndex by remember(request) { mutableIntStateOf(request.dayIndex.coerceIn(0, dayNames.lastIndex)) }"
        const val PICKER_INITIAL_PERIOD = "mutableIntStateOf(request.segment.first.coerceIn(1, maxStartPeriod))"

        /** 落点册子（文件 → 枚数）· 无条件那一本：两枚 UI 落点 + 一枚组外观 */
        val EXPECTED_LANDING = mapOf(
            REPOSITORY to 1,
            HOME_SCREEN to 1,
            WIZARD to 1,
        )

        /** 落点册子 · 由判据供给那一本：只有拖课那一枚（选择框与拖拽共用这一个收口） */
        val EXPECTED_JUDGED = mapOf(
            HOME_SCREEN to 1,
        )
    }

    // ─────────────── ① 拖课那一记 copy：三项同在一记 copy 内、按次序 ───────────────

    @Test
    fun `拖课构造出的那一行同时带着时间两维与旗标`() {
        val raw = source(HOME_SCREEN)
        val code = blankComments(raw)
        val args = callArgs(code, MOVE_COPY_ANCHOR, "handleCourseMove 里的 `val shifted = course.copy(`")
        val order = listOf(
            "dayOfWeek = newDayOfWeek",
            "periods = shiftedPeriods",
            "isManualOverride = manualTimeOverride",
        ).map { args.indexOf(it) }
        assertTrue(
            "拖课那一记 copy 里三行必须齐全且按「dayOfWeek → periods → isManualOverride」次序。" +
                "\n  实到（$MOVE_COPY_ANCHOR 之后）：" + args.trim().replace(Regex("""\s+"""), " ") +
                "\n  起手在 L" + lineOf(raw, raw.indexOf(MOVE_COPY_ANCHOR)) +
                "\n复算：sed -n '447,451p' app/src/main/java/com/buaa/schedule/ui/home/HomeScreen.kt\n" +
                "少了旗标 = 这一次挪动改了 courseKey 的两维 ⇒ 下次教务刷新整行按原时刻冲回来，" +
                "并且挂在它上面的提醒被 deleteRemindersOfDroppedCourses 一起删掉。" +
                "次序也算：把旗标放到 copy 之外（另一记 copy 里）就是本守卫要防的「枚数对但落点错」",
            order.distinct().size == 3 && order == order.sorted(),
        )
        assertEquals(
            "T133b 之后拖课这一枚不许再是字面 `true`：那是把「打开选择框、选回原来那一格、点两下确认」" +
                "这一笔逐字段什么都没改的空操作也标成 manual ⇒ 那一行从此退出教务刷新的匹配与覆盖，" +
                "刷新还会补一张重复卡进来。旗标必须由档 ⑩ 那一枚判据供给" +
                "（实到「$FLAG_WRITE」在 copy 实参里 " + occurrences(args, FLAG_WRITE) + " 处）",
            0,
            occurrences(args, FLAG_WRITE),
        )
    }

    // ─────────────── ② 三支写库递的都是被标过的那一份 ───────────────

    @Test
    fun `拖课的三支写库全部递的是带旗标的那一行`() {
        val code = blankComments(source(HOME_SCREEN))
        val body = balancedBlock(code, MOVE_LAMBDA_ANCHOR, "handleCourseMove")
        assertEquals(
            "`viewModel.updateCourse(shifted)` 在该 handler 体内恰好两处（非 thisWeekOnly 那一支 + " +
                "本周但拿不到周号退回整课那一支），两处都必须递 `shifted` 而不是 `course`。" +
                "\n复算：grep -n \"updateCourse(shifted)\" app/src/main/java/com/buaa/schedule/ui/home/HomeScreen.kt",
            2,
            occurrences(body, "viewModel.updateCourse(shifted)"),
        )
        assertEquals(
            "「仅本周」那一支必须递 `shifted.copy(weeks = listOf(week))`：拆出来的那一行才是用户挪走的" +
                "那几周，旗标只能由 `shifted` 带过去（① 钉的就是它）",
            1,
            occurrences(body, "shifted.copy(weeks = listOf(week))"),
        )
        assertEquals(
            "那一支交的是 `CourseSaveOptions(partialWeeks = true)`（T131 的账，本卡不许动）",
            1,
            occurrences(body, "CourseSaveOptions(partialWeeks = true)"),
        )
        assertFalse(
            "handler 体内出现了绕过 `shifted` 直接写 `updateCourse(course)` 的旁路：那一支没带旗标，" +
                "挪动照样被下次刷新冲回来",
            Regex("""updateCourse\(\s*course\s*[,)]""").containsMatchIn(body),
        )
    }

    // ─────────────── ③ 缩放改节次 ───────────────

    @Test
    fun `缩放改节次那一处同样标成手动覆盖`() {
        val raw = source(HOME_SCREEN)
        val code = blankComments(raw)
        val body = balancedBlock(code, RESIZE_LAMBDA_ANCHOR, "handleCourseResize")
        assertEquals(
            "`handleCourseResize` 体内那记 copy 逐字没了对齐串。缩放改的就是 `periods`" +
                "（courseKey 的一维），不标 manual 就是与拖课同一种病。" +
                "\n复算：grep -n \"newPeriods.sorted(), isManualOverride\" app/src/main/java/com/buaa/schedule/ui/home/HomeScreen.kt",
            1,
            occurrences(body, RESIZE_COPY),
        )
        assertTrue(
            "那一处落在 L" + lineOf(raw, raw.indexOf(RESIZE_COPY)) + "，必须在 `handleCourseResize` 块内",
            lineOf(raw, raw.indexOf(RESIZE_COPY)) > lineOf(raw, raw.indexOf(RESIZE_LAMBDA_ANCHOR)),
        )
    }

    // ─────────────── ④ 冲突向导：旗标与 T131 那两维同现 ───────────────

    @Test
    fun `向导那一记 copy 同时带着周次作用域与旗标`() {
        val raw = source(WIZARD)
        val code = blankComments(raw)
        val body = balancedBlock(code, APPLY_SIGNATURE, "applyConflictShift")
        assertEquals(
            "`applyConflictShift` 体内那记 copy 必须逐字是「periods + weeks = scopedWeeks + " +
                "isManualOverride = true」三项齐全（实到 " + occurrences(body, WIZARD_COPY) + " 处）：" +
                "少了 weeks 是 T131 的病，少了旗标是 T133 的病。" +
                "\n复算：grep -n \"weeks = scopedWeeks, isManualOverride\" app/src/main/java/com/buaa/schedule/ui/home/ConflictWizardDialog.kt",
            1,
            occurrences(body, WIZARD_COPY),
        )
        assertTrue(
            "旗标必须与 `CourseSaveOptions(partialWeeks = true)` 同现，且 copy 在前、options 在后",
            body.indexOf(WIZARD_COPY) in 0 until body.indexOf("CourseSaveOptions(partialWeeks = true)"),
        )
        assertTrue(
            "那一记 copy 落在 L" + lineOf(raw, raw.indexOf(WIZARD_COPY)) + "，必须在函数签名之后",
            code.indexOf(WIZARD_COPY) > code.indexOf(APPLY_SIGNATURE),
        )
    }

    // ─────────────── ⑤ 落点册子：文件 × 枚数，不是全仓总数（无条件/判据两本） ───────────────

    @Test
    fun `main里标手动覆盖的落点逐格对上不多不少`() {
        val byFile = mutableMapOf<String, Int>()
        val judgedByFile = mutableMapOf<String, Int>()
        mainSources().forEach { (relative, text) ->
            val code = blankComments(text)
            occurrences(code, FLAG_WRITE).takeIf { it > 0 }?.let { byFile[relative] = it }
            occurrences(code, JUDGED_WRITE).takeIf { it > 0 }?.let { judgedByFile[relative] = it }
        }
        assertEquals(
            "main 里字面 `isManualOverride = true` 的落点册子变了（实到 " +
                byFile.toSortedMap().entries.joinToString { "${it.key}=${it.value}" } +
                "）。应当是" + EXPECTED_LANDING.toSortedMap().entries.joinToString { "${it.key}=${it.value}" } +
                "：两枚无条件 UI 落点（缩放 / 向导，落点闸见档 ⑫）+ 一枚组外观，一格格数。" +
                "长出一枚 = 有人在别的路径上乱标（那一行同样从此退出教务刷新的匹配与覆盖）；" +
                "少一枚 = 那条链又回到「下次刷新按原时刻冲回来、连带删提醒」。" +
                "\n拖课那一枚不在这一本里 —— 它自 T133b 起由判据供给（下面那一本），" +
                "拿它去补上面某一枚的数就是两头都不对。" +
                "\n复算：git grep -n \"isManualOverride = true\" -- app/src/main",
            EXPECTED_LANDING.toSortedMap(),
            byFile.toSortedMap(),
        )
        assertEquals(
            "由判据供给的旗标落点册子变了（实到 " +
                judgedByFile.toSortedMap().entries.joinToString { "${it.key}=${it.value}" } +
                "），应当是" + EXPECTED_JUDGED.toSortedMap().entries.joinToString { "${it.key}=${it.value}" } +
                "：只有拖课/选择框那个共同收口一枚。多一枚 = 有人给已经闸住的落点又叠了一道判据" +
                "（两处判同一件事，本仓算违反）；少一枚 = 空操作又开始标 manual 了" +
                "\n复算：git grep -n \"isManualOverride = manualTimeOverride\" -- app/src/main",
            EXPECTED_JUDGED.toSortedMap(),
            judgedByFile.toSortedMap(),
        )
        assertEquals(
            "编辑器那一枚既有判据表达式（「我改过这门课」的先例口径）必须原样在：" +
                "本卡照它的写法，但不许顺手改它",
            1,
            occurrences(blankComments(source(EDITOR)), EDITOR_PRECEDENT),
        )
    }

    // ─────────────── ⑥ 提醒那一刀：被冲掉的那条链上确实有这一刀 ───────────────

    @Test
    fun `刷新落库那五步之后确实有一刀删提醒`() {
        val raw = source(REPOSITORY)
        val code = blankComments(raw)
        val body = balancedBlock(code, REPLACE_SIGNATURE, "replaceSemesterCoursesInTx")
        val bodyAt = code.indexOf(REPLACE_SIGNATURE)
        val steps = listOf(
            "val existing = courseDao.getBySemester(",
            "courseDao.deleteBySemester(",
            "ImportPlanner.buildImportPlan(existing, importedCourses)",
            "courseDao.insertAll(plan.map { it.toEntity() })",
            "deleteRemindersOfDroppedCourses(existing, plan)",
        )
        val missing = steps.filter { !body.contains(it) }
        assertTrue(
            "仓储那五步缺了：" + missing.joinToString { "「$it」" } +
                "\n复算：sed -n '499,519p' app/src/main/java/com/buaa/schedule/data/repository/ScheduleRepository.kt",
            missing.isEmpty(),
        )
        val positions = steps.map { body.indexOf(it) }
        assertTrue(
            "仓储那五步乱了次序（读 existing 必须最先、删提醒必须最后）：" +
                positions.mapIndexed { i, p -> "L" + lineOf(raw, bodyAt + p) + " " + steps[i] }.joinToString(" → "),
            positions == positions.sorted() && positions.distinct().size == steps.size,
        )
        val cleaner = balancedBlock(code, "private suspend fun deleteRemindersOfDroppedCourses(", "deleteRemindersOfDroppedCourses")
        assertEquals(
            "keptIds 那一行必须逐字在——`ImportPlannerManualTimeTest.droppedCourseIds` 复算的就是它，" +
                "它一变那张表的「掉没掉提醒」一列就得跟着重算",
            1,
            occurrences(cleaner, "val keptIds = plan.mapTo(HashSet()) { it.id }"),
        )
        assertEquals(
            "droppedIds 那一行必须逐字在（按 existing 的 id 反筛 keptIds）",
            1,
            occurrences(cleaner, "val droppedIds = existing.map { it.id }.filterNot { it in keptIds }"),
        )
        assertEquals(
            "最后确实把 droppedIds 交给 `reminderDao.deleteByCourses` 删提醒",
            1,
            occurrences(cleaner, "reminderDao.deleteByCourses(it)"),
        )
        assertEquals(
            "教务刷新那一句调用点必须还在（它才是「下一次刷新」的本体）：少了它本卡的病因没有触发者",
            1,
            occurrences(blankComments(source(VIEW_MODEL)), REFRESH_CALL),
        )
    }

    // ─────────────── ⑦ 部分周次那一支：标的是哪一行 ───────────────

    @Test
    fun `部分周次那一支只有拆出来的新行带旗标`() {
        val raw = source(REPOSITORY)
        val code = blankComments(raw)
        val body = balancedBlock(code, PARTIAL_SIGNATURE, "updateCoursePartialWeeks")
        assertEquals(
            "「只改这些周」同时动两行：把原行的 weeks 收窄（这一行仍与教务同 key、id 复用）",
            1,
            occurrences(body, NARROW_ORIGINAL),
        )
        assertEquals(
            "再插一枚新行（normalized 来自调用点那记带旗标的 copy ⇒ 本卡标的就是这一行）",
            1,
            occurrences(body, INSERT_FRAGMENT),
        )
        assertFalse(
            "被收窄的原行那一记 copy 里出现了旗标：它的 `periods` 仍是教务给的那一份，标 manual 就等于" +
                "让教务那一版在**其余每一周**都补一张重复卡（现状只重复被挪走的那几周）。" +
                "\n  该行 L" + lineOf(raw, raw.indexOf(NARROW_ORIGINAL)) + "：「$NARROW_ORIGINAL」" +
                "\n复算：grep -n \"original.copy(weeks = remaining.sorted())\" app/src/main/java/com/buaa/schedule/data/repository/ScheduleRepository.kt",
            NARROW_ORIGINAL.contains(FLAG_WRITE),
        )
        assertTrue(
            "次序：先收窄原行、再插新行（反过来新行的 weeks 从哪一行收窄出来就不是本卡判的那一份）",
            body.indexOf(NARROW_ORIGINAL) in 0 until body.indexOf(INSERT_FRAGMENT),
        )
    }

    // ─────────────── ⑧ 前提扳机：钥匙形状没变 ───────────────

    @Test
    fun `身份钥匙仍含上课日与节次 本卡修法的前提还立着`() {
        val code = blankComments(source(PLANNER))
        val dims = listOf("course.dayOfWeek,", "course.periods.joinToString(\",\"),")
        assertEquals(
            "`courseKey` 的两维必须逐字在（实到 " + dims.count { code.contains(it) } + " / 2）。" +
                "\n复算：sed -n '19,27p' app/src/main/java/com/buaa/schedule/domain/schedule/ImportPlanner.kt\n" +
                "谁把它们摘出去（本卡评估过的替代修法之一），本卡的「标 manual」修法就要连同 " +
                "`ImportPlannerManualTimeTest` 表①一起重判：那时同 key 会合并成一行、周次取并集，" +
                "一门课的不同节次段（1-2 与 9-10 连排、一周两次不同教室）就撞在一起了",
            2,
            dims.count { code.contains(it) },
        )
        assertEquals(
            "`matchable` 那一行必须逐字在：它是「钥匙对不上就整行丢」的前半（只收未标 manual 的 existing）",
            1,
            occurrences(code, "val matchable = existing.filter { !it.isManualOverride }.associateBy { courseKey(it) }"),
        )
        assertEquals(
            "末尾那一行必须逐字在：它是同一判的后半（只有标了 manual 的 existing 才被交回去）",
            1,
            occurrences(code, "return existing.filter { it.isManualOverride } + merged.values.toList()"),
        )
    }

    // ─────────────── ⑨ 行号自检：报错消息里的行号不许差一 ───────────────

    @Test
    fun `行号两种独立算法必须给出同一个数`() {
        val raw = source(HOME_SCREEN)
        val code = blankComments(raw)
        assertEquals(
            "`lineOf` 必须是 1-based：第 0 个字符落在第 1 行。本仓登记过报错消息把行号拼错的洞" +
                "（左结合那种 `" + "L" + " + 1 + x` 写法），本守卫的行号一律显式取一次",
            1,
            lineOf(raw, 0),
        )
        val computed = lineOf(code, code.indexOf(FLAG_WRITE))
        val byLines = raw.lines().indexOfFirst { it.contains(FLAG_WRITE) } + 1
        assertEquals(
            "两种独立算法（数换行 vs 按行切分）读出的行号不一致 ⇒ 报错消息里的行号会指错地方",
            byLines,
            computed,
        )
        assertEquals(
            "算出来的那一行必须真的含着旗标那一行文本：" + raw.lines()[computed - 1].trim(),
            true,
            raw.lines()[computed - 1].contains(FLAG_WRITE),
        )
    }

    // ─────────────── ⑩ 拖课那一枚旗标确实是内核算出来的（五枚具名实参 + 与 copy 同源） ───────────────

    @Test
    fun `拖课那一枚旗标由判据内核算出 不是恒真也不是各算一遍`() {
        val raw = source(HOME_SCREEN)
        val code = blankComments(raw)
        val body = balancedBlock(code, MOVE_LAMBDA_ANCHOR, "handleCourseMove")
        val args = callArgs(code, MOVE_JUDGE_ANCHOR, "拖课那一枚判据调用")
        val required = listOf(
            "originalIsManualOverride = course.isManualOverride",
            "newDayOfWeek = newDayOfWeek",
            "originalDayOfWeek = course.dayOfWeek",
            "newPeriods = shiftedPeriods",
            "originalPeriods = course.periods",
        )
        val order = required.map { args.indexOf(it) }
        val missing = required.filterNot { args.contains(it) }
        assertTrue(
            "判据调用缺了具名实参：" + missing.joinToString { "「$it」" } +
                "（实到 " + args.trim().replace(Regex("""\s+"""), " ") + "）" +
                "\n  起手在 L" + lineOf(raw, raw.indexOf(MOVE_JUDGE_ANCHOR)) +
                "\n复算：sed -n '440,446p' app/src/main/java/com/buaa/schedule/ui/home/HomeScreen.kt\n" +
                "五枚缺一不可：少了 `originalIsManualOverride` 就是丢掉粘住（一次空操作把编辑器早已" +
                "标过的行洗回 false）；少了 original 那一侧就无从比对；换成常量就是本档最后一判要拦的",
            order.distinct().size == 5 && order == order.sorted(),
        )
        assertTrue(
            "判据必须在这记 copy **之前**算好（`isManualOverride = manualTimeOverride` 引用的就是它）：" +
                "\n  判据 L" + lineOf(raw, raw.indexOf(MOVE_JUDGE_ANCHOR)) +
                " vs copy L" + lineOf(raw, raw.indexOf(MOVE_COPY_ANCHOR)),
            body.indexOf(MOVE_JUDGE_ANCHOR) in 0 until body.indexOf(MOVE_COPY_ANCHOR),
        )
        val copyArgs = callArgs(code, MOVE_COPY_ANCHOR, "拖课那一记 copy")
        assertTrue(
            "判据收到的「要写进去的那一份」必须就是 copy 落库的那同一份（两处各算一遍迟早分叉）：" +
                "\n  判据实参里没有「newDayOfWeek = newDayOfWeek」或 copy 里没有「dayOfWeek = newDayOfWeek」" +
                "、\n  判据实参里没有「newPeriods = shiftedPeriods」或 copy 里没有「periods = shiftedPeriods」",
            args.contains("newDayOfWeek = newDayOfWeek") && copyArgs.contains("dayOfWeek = newDayOfWeek") &&
                args.contains("newPeriods = shiftedPeriods") && copyArgs.contains("periods = shiftedPeriods"),
        )
        assertEquals(
            "「日列 → dayOfWeek」那一句加法在 handler 体内只能出现一次（就是 $NEW_DAY_LOCAL 那一枚局部量）：" +
                "判据与 copy 各写一遍 `newDayIndex + 1` 就是两处判同一件事的分叉点",
            1,
            occurrences(body, "newDayIndex + 1"),
        )
        assertFalse(
            "判据调用里出现了字面量实参（有人把它退回恒真/恒假）：" +
                "\n  实到 " + args.trim().replace(Regex("""\s+"""), " "),
            Regex("""=\s*(true|false)\s*[,)]""").containsMatchIn(args),
        )
        assertFalse(
            "handler 体内又长出第二道「日/节次变没变」的判据 —— 本仓「各判一次」算违反，" +
                "判据本体只许有 [ManualTimeOverridePolicy] 那一份（档 ⑪ 钉着它）",
            Regex("""newDayOfWeek\s*!=|shiftedPeriods\s*!=""").containsMatchIn(body),
        )
    }

    // ─────────────── ⑪ 判据内核仍是纯判据、全仓只此一份 ───────────────

    @Test
    fun `判据内核仍是纯判据 零依赖且全仓只此一份`() {
        val raw = source(POLICY)
        val imports = raw.lines().map { it.trim() }.filter { it.startsWith("import ") }
        assertEquals(
            "判据内核 $POLICY 里出现了 import（实到 " + imports.joinToString { "「$it」" } + "）。" +
                "这一族的入门条件是零 android/androidx import、零时钟读取、不碰 ViewModel 也不碰数据库" +
                "（外部事实一律由调用点当参数递进来，见 `ConflictShiftWeekScope.kt` 段首）。" +
                "内核自己去嗅课表 / 读当下，就与首页那两枚 `remember` 的失效链脱钩了（T41/T43 那一类坑）",
            emptyList<String>(),
            imports,
        )
        val code = blankComments(raw)
        val body = code.substringAfter("): Boolean =").substringBefore("\n}").trim().replace(Regex("""\s+"""), " ")
        assertEquals(
            "判据本体不再是我们判的那三判析取（「原来已标 manual ⊷ 日变了 ⊷ 节次变了」），实到「$body」。" +
                "\n复算：sed -n '61,72p' app/src/main/java/com/buaa/schedule/ui/home/ManualTimeOverridePolicy.kt\n" +
                "朝宽扭（恒真、把周次也判进来）⇒ `ManualTimeOverridePolicyTest` 同值那三格红；" +
                "朝窄扭（恒假、反着取、丢掉粘住那一支）⇒ 真改动那几格与粘住那一格红",
            POLICY_BODY,
            body,
        )
        val homes = mainSources().filter { (_, text) -> blankComments(text).contains(POLICY_SIGNATURE) }.keys
        assertEquals(
            "`fun forCourseMove(` 的定义全仓必须只有一处（两处判据本体迟早分叉）：$homes",
            1,
            homes.size,
        )
        assertTrue("唯一那一处必须在 $POLICY 里，实到 $homes", homes.contains(POLICY))
    }

    // ─────────────── ⑫ 反向格子：选择框那条路还在、且确实没有同值闸 ───────────────

    @Test
    fun `选择框那条路还在而它确实没有同值闸`() {
        val code = blankComments(source(WEEK_VIEW))
        assertEquals(
            "「移动到…」的入口册子变了（实到三处应当是：长按菜单一项 + 宽窄两屏各一枚卡片动作）。" +
                "⚠️ 不许有人为了绕开这一卡的空操作问题把选择框那条路整个删掉 —— 它是 WCAG 2.2 · 2.5.7 " +
                "Dragging Movements 给读屏与精细动作受限用户的**唯一**改时间入口，删掉它等于把病改成残废。" +
                "\n复算：grep -n -A2 \"movePickerFor =\" app/src/main/java/com/buaa/schedule/ui/home/WeekView.kt",
            3,
            Regex("""movePickerFor\s*=\s*CourseMovePickerRequest\(""").findAll(code).count(),
        )
        assertEquals(
            "选择框那一扇 `ModalTransition(payload = movePickerFor)` 必须还在（payload 版，收场播得出来）",
            1,
            occurrences(code, "ModalTransition(payload = movePickerFor) { request, modal ->"),
        )
        assertEquals(
            "`CourseMovePickerDialog` 的调用与定义两处必须都在",
            2,
            occurrences(code, "CourseMovePickerDialog("),
        )
        assertEquals(
            "落库前的确认窗那一扇 `ModalTransition(payload = pendingMove)` 必须还在",
            1,
            occurrences(code, "ModalTransition(payload = pendingMove) { request, modal ->"),
        )
        assertEquals("「所有周」那枚按钮必须还在", 1, occurrences(code, """Text("所有周")"""))
        assertEquals("「仅本周」那枚按钮必须还在", 1, occurrences(code, """Text("仅本周")"""))
        assertEquals(
            "`onCourseMove?.invoke(` 的调用点必须还是确认窗那两处（拖拽与选择框共用同一套语义）",
            2,
            occurrences(code, "onCourseMove?.invoke("),
        )
        assertEquals(
            "首页仍把**同一份** `handleCourseMove`（本卡判据所在的那个收口）端给宽屏与窄屏两个分支 ——" +
                "换掉或摘掉任何一枚，选择框那条路就绕过了判据",
            2,
            occurrences(blankComments(source(HOME_SCREEN)), "onCourseMove = handleCourseMove"),
        )
        assertEquals(
            "选择框的初值仍是**出发那一格**（日）—— 这一句被改掉，本卡判的「同值可达」就不成立了，" +
                "得回来重判要不要判据",
            1,
            occurrences(code, PICKER_INITIAL_DAY),
        )
        assertEquals("选择框的初值仍是**出发那一格**（节次）", 1, occurrences(code, PICKER_INITIAL_PERIOD))
        val onConfirm = balancedBlock(code, PICKER_ON_CONFIRM, "选择框的 onConfirm")
        assertEquals(
            "`onConfirm` 仍无条件把选中的日与节装成 `CourseMoveRequest` 交给确认窗",
            1,
            occurrences(onConfirm, "pendingMove = CourseMoveRequest("),
        )
        assertFalse(
            "选择框的 `onConfirm` 里出现了 `if` —— 有人在那一侧加了同值闸。那就该把 handler 里那一枚判据" +
                "撤掉，两处判同一件事在本仓算违反（判据本体只许一份）。\n  实到 " +
                onConfirm.trim().replace(Regex("""\s+"""), " "),
            Regex("""\bif\b""").containsMatchIn(onConfirm),
        )
        assertEquals(
            "拖拽那两枚 `onDragEnd` 的同值闸（宽/窄各一枚）必须还在 —— 本卡判据说「拖回原地不回放到 " +
                "handler」靠的就是它（`ManualTimeOverridePolicyTest` 第三格与它同向）",
            2,
            occurrences(code, DRAG_GATE),
        )
        assertEquals(
            "缩放那一路的同值闸必须还在 —— 它是 `handleCourseResize` 那一枚仍然恒真的唯一理由",
            1,
            occurrences(code, RESIZE_GATE),
        )
        assertEquals(
            "向导那一路「原地不算建议」那一判必须还在（`suggestNearestFreeShift` 跳过 candidate == " +
                "currentPeriods）—— 它是 `applyConflictShift` 那一枚仍然恒真的唯一理由。" +
                "同值可达性两判在 `ManualTimeOverridePolicyTest` 档 ③ 用真码跑",
            1,
            occurrences(blankComments(source(CONFLICT_RESOLUTION)), SUGGESTION_GATE),
        )
    }

    // ---------------- helpers ----------------

    private fun source(relative: String): String = File(findMainJavaDir(), relative).readText()

    private fun mainSources(): Map<String, String> {
        val root = findMainJavaDir()
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .associateBy({ it.relativeTo(root).path.replace('\\', '/') }, { it.readText() })
    }

    private fun occurrences(hay: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var at = hay.indexOf(needle)
        while (at >= 0) {
            count++
            at = hay.indexOf(needle, at + needle.length)
        }
        return count
    }

    /** 1-based 行号：`lineOf(src, 0) == 1`；拼消息时只写 `lineOf(...)`，不再做加法 */
    private fun lineOf(src: String, index: Int): Int =
        src.substring(0, index.coerceAtLeast(0).coerceAtMost(src.length)).count { it == '\n' } + 1

    /** 取 `anchor(...` 那一记调用的实参文本（括号配平；锚点找不到就抛，不静默跳过） */
    private fun callArgs(code: String, anchor: String, label: String): String {
        val at = code.indexOf(anchor)
        assertTrue("$label 的锚点没找到（改名/挪家要回来重钉本守卫）：「$anchor」", at >= 0)
        val open = at + anchor.length - 1
        assertEquals("锚点必须以左括号收尾：「$anchor」", '(', code[open])
        var depth = 0
        for (i in open until code.length) {
            when (code[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return code.substring(open + 1, i)
                }
            }
        }
        throw IllegalStateException("$label 的括号没配平")
    }

    /** 从锚点那一句起按花括号配平取整块（含锚点自己）；锚点找不到就抛 */
    private fun balancedBlock(code: String, anchor: String, label: String): String {
        val at = code.indexOf(anchor)
        assertTrue("$label 的锚点没找到（改名/挪家要回来重钉本守卫）：「$anchor」", at >= 0)
        val open = code.indexOf('{', at)
        assertTrue("$label 找不到函数体的开括号", open >= 0)
        var depth = 0
        for (i in open until code.length) {
            when (code[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return code.substring(at, i + 1)
                }
            }
        }
        throw IllegalStateException("$label 的花括号没配平")
    }

    /** 把 `//` 与块注释抹成空格（字符串保留、长度与换行位置不变）：钉的是接线，不是白话 */
    private fun blankComments(src: String): String {
        val out = src.toCharArray()
        var i = 0
        while (i < out.size) {
            when {
                src.startsWith("//", i) -> {
                    val nl = src.indexOf('\n', i).let { if (it < 0) out.size else it }
                    for (k in i until nl) out[k] = ' '
                    i = nl
                }

                src.startsWith("/*", i) -> {
                    var depth = 1
                    var j = i + 2
                    while (j < out.size && depth > 0) {
                        when {
                            src.startsWith("/*", j) -> { depth++; j += 2 }
                            src.startsWith("*/", j) -> { depth--; j += 2 }
                            else -> j++
                        }
                    }
                    for (k in i until j.coerceAtMost(out.size)) if (out[k] != '\n') out[k] = ' '
                    i = j
                }

                out[i] == '"' || out[i] == '\'' -> {
                    val quote = out[i]
                    var j = i + 1
                    while (j < out.size) {
                        when {
                            src[j] == '\\' -> j += 2
                            src[j] == quote -> { j++; break }
                            src[j] == '\n' -> break
                            else -> j++
                        }
                    }
                    i = j
                }

                else -> i++
            }
        }
        return String(out)
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
