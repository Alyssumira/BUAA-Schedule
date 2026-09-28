package com.buaa.schedule.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T124a②：钉「首页拖课 / 首页缩放 / 课表管理页换色那三笔写库的**协程接收者**是
 * `viewModel.viewModelScope`，而不是页面 `rememberCoroutineScope()` 的那枚 scope」——
 * 纯源码核对（JVM，无 Robolectric、无设备）。
 *
 * ## 病：写库链挂在 composition scope 上
 *
 * `rememberCoroutineScope()` 给的 scope 绑在**这一次组合**上：目的地被 dispose 时它就被取消。
 * 本仓 `MainActivity` 的顶层导航切 tab 走的是 `popUpTo(startDestinationId) { saveState = true }`
 * + `restoreState` ⇒ 切走一次就是真的卸载目的地（`HomeScreen.kt` 与 `ImportScreen.kt` 里两处
 * 以"页面不在时 collect 不活着"为前提的注释说的就是同一件事）。于是写在页 scope 里的
 * `viewModel.updateCourse(...)` 有一个取消点落在**写库之后、`pushUpdate(...)` 与
 * `afterDataChangedInternal(...)` 之前**：库里改了、撤销条目没压、课前铃/桌面组件/明日预告
 * 这一趟不重排 —— 而且 UI 上完全静默（那一手的返回值今天本来就被丢掉，连失败都没人念）。
 *
 * ## 修法与这张卡的边界
 *
 * 评估卡把这一族分三档，**A 档 = 只搬接收者**，成立的前提就是"这一手的结果今天没人读"：
 * 整块搬进 `viewModel.viewModelScope.launch { … }`，不需要新增 VM 方法、不需要新造共享可变状态，
 * 零 `ScheduleViewModel.kt` diff。先例是本仓 `ConflictWizardDialog.kt` 的 `applyConflictShift`
 * （`viewModel.viewModelScope.launch` + `job.join()`，档 ⑦ 钉着它没被换回去）。
 *
 * B 档（读返回值那一手）body 里带着 `snackbarHostState.showSnackbar(...)` 这类只在组合期成立的东西，
 * **整块搬**会把提示条的回灌链拆断 ⇒ 只能半搬：写库那一手进 VM 新增的 `...AndAwait` 里的
 * `viewModelScope` job，`join()` 与提示条留在页 scope 上。T124b 收的就是这两枚（首页长按删课、
 * 管理页删整组），形状由档 ⑨（VM 侧）与档 ⑩（调用点侧）钉；K3（编辑器那两枚）今天仍**没动**。
 * 档 ⑤ 因此钉"被整块搬走的三枚 body 里 composition-only 调用恰好 0 处"（真搬了），
 * 档 ⑥ 反向钉"页 scope 名下不许再长出新的写库链"。
 *
 * ## 钉法（十档，两侧都有格子）
 *
 * 1. 拖课 handler 体内：`viewModel.viewModelScope.launch {` 恰好一枚、页 scope 那一枚恰好 0 枚、
 *    三笔 `viewModel.updateCourse(` **全部**躺在那一枚协程体里（落点 + 读形状，不是文件级计数）；
 * 2. 缩放 handler 体内：同一形状，一笔写库，且那一记 copy 逐字原样；
 * 3. 管理页 `onPickColor` 体内：同一形状，一笔写库，copy 与 `CourseSaveOptions(applyToGroup = true)`
 *    逐字原样（搬接收者不许顺手改载荷）；
 * 4. 三枚落点的**宿主行逐字**是 `viewModel.viewModelScope.launch {`，行号由两种独立算法
 *    （数换行 / 按 `\n` 切分）给出同一个数，报错消息逐枚点名 文件 + 行号 + 原文；这一档原来还挂着
 *    一枚「被点名的行数 == `MOVED.size`」，T124a⑤ 判成恒等式删掉（那份 report 每轮恰好 append 一枚
 *    换行 ⇒ 右侧量的就是循环轮数，而循环就是按 `MOVED` 迭代的），它想钉的「盘上 ↔ 名册互为等集」
 *    由档 ⑧ 那格独立数盘接管；
 * 5. 三枚 body 里 composition-only 调用 0 处（提示条 / 分页 / 滚动 / 触感 / `LaunchedEffect`）：
 *    这一格是"整块搬成立"的证据，也是谁把 snackbar 塞进这一枚协程时的红名；
 * 6. **反向棘轮**：全仓每一枚由 `rememberCoroutineScope()` 供给的变量名下，写库那一手的册子
 *    按 文件 × 那一手逐字 登记，判据是**只许减不许增**（不是"三枚之外还剩几枚"的计数 ——
 *    B/K3 把剩下的继续搬走时这一格照样只说真话；新长出一枚、或把搬走的那一枚搬回来才红）。
 *    册子被掏空也红（那一格就不判任何东西了）；
 * 7. 先例未被顶掉：`applyConflictShift` 仍走 `viewModel.viewModelScope` + `job.join()`。
 * 8. **双向闭合**：档 ①〜③ 只钉「名册 `MOVED` 里在册的每一枚都合格」，本格钉另一侧「盘上该被点名的
 *    每一枚都进了名册」—— 从盘上按 `VM_SCOPE_LANDING_FILES` 那两枚文件独立扫出所有
 *    `viewModel.viewModelScope.launch {`（接收者形状 + 花括号配平取体，全程不读 `MOVED`），
 *    身份 = 文件 × 那一枚协程体内写库的笔数，与 `MOVED` 那把**一次盘都不开**的身份多重集互为等集
 *    （数量与身份都等，双向差各自点名）。⇒ 摘掉名册里一枚、或盘上多长出一枚（把 B 档落点悄悄搬过去也算）都红。
 * 9. **B 档 VM 侧（T124b）**：两枚 `...AndAwait` 各自的**方法体内**必须有 `val job = viewModelScope.launch {`
 *    那一枚 job、写库那一手逐字落在它的协程体里、`job.join()` 在协程体**外**、`return deleted` 排在 join 之后、
 *    `var deleted = false` 是体内局部捕获。⇒ 朝宽扭（把方法退成 `return deleteCourse(course)` 那种透传）红。
 * 10. **B 档调用点侧（T124b）**：两枚调用点仍挂在页 scope 的 `scope.launch {` 里，写库那一手已换成
 *     `viewModel....AndAwait(`，裸的 `viewModel.deleteCourse(` / `viewModel.deleteCourseGroup(` 0 处，
 *     而 `showSnackbar(` / 那颗 `actionLabel = "撤销".takeIf { deleted },` / 撤销那一手逐字各一处。
 *     ⇒ 朝窄扭（调用点换回旧名）红。
 */
class WriteChainReceiverWiringGuardTest {

    private companion object {
        const val HOME_SCREEN = "com/buaa/schedule/ui/home/HomeScreen.kt"
        const val MANAGEMENT = "com/buaa/schedule/ui/course/CourseManagementScreen.kt"
        const val EDITOR = "com/buaa/schedule/ui/editor/CourseEditorScreen.kt"
        const val WIZARD = "com/buaa/schedule/ui/home/ConflictWizardDialog.kt"

        /** 三枚落点各自的**宿主 anchor**（按 handler 体取块，不按行号取块） */
        const val MOVE_ANCHOR = "val handleCourseMove: (Course, Int, Int, Boolean) -> Unit = remember("
        const val RESIZE_ANCHOR = "val handleCourseResize: (Course, List<Int>) -> Unit = remember("
        const val PICK_COLOR_ANCHOR = "onPickColor = { index ->"

        const val VM_SCOPE_LAUNCH = "viewModel.viewModelScope.launch {"
        const val PAGE_SCOPE_LAUNCH = "scope.launch {"
        const val SCOPE_DECL = "val scope = rememberCoroutineScope()"
        const val VM_SCOPE_IMPORT = "import androidx.lifecycle.viewModelScope"

        const val UPDATE_COURSE = "viewModel.updateCourse("
        const val MOVE_COPY = "val shifted = course.copy("
        const val RESIZE_COPY =
            "viewModel.updateCourse(course.copy(periods = newPeriods.sorted(), isManualOverride = true))"
        const val PICK_COPY = "primary.copy(colorIndex = index, customColorArgb = null),"
        const val GROUP_OPTION = "CourseSaveOptions(applyToGroup = true),"

        /** 只在组合期成立的东西：搬进 VM 协程就是错（提示条回灌链会断在这一手被取消上） */
        val COMPOSITION_ONLY = listOf(
            "snackbarHostState.showSnackbar(",
            "pagerState",
            "animateScrollTo",
            "scrollState",
            "performHaptics",
            "LaunchedEffect(",
        )

        /**
         * 写库链的册子按这一组逐字名取（VM 的改库 API + 编辑器那两枚包装）。
         *
         * ⚠️ **刻意不含** `viewModel.deleteCourseAndAwait(` / `viewModel.deleteCourseGroupAndAwait(`：
         * 那两枚是 B 档的形状，页 scope 名下留下的只有 `join()` 那个等待点，写库那一手已经在 VM 的
         * `viewModelScope` job 里（档 ⑨ 钉的就是它）。把它们收进这一族，等于让档 ⑥ 反过来去逼
         * "把 B 档那一半也搬回页 scope" —— 那正是本卡修掉的病。
         */
        val WRITE_NEEDLES = listOf(
            "viewModel.saveCourse(",
            UPDATE_COURSE,
            "viewModel.deleteCourse(",
            "viewModel.deleteCourseGroup(",
            "viewModel.undo(",
            "viewModel.undoDeleteCourse(",
            "saveCourseDraft(",
            "onDelete(target)",
        )

        /**
         * 档 ⑥ 的**参照物**（本卡改完那天盘点的真值）：页 scope 名下还挂着写库链的落点册子。
         *
         * 判据是**只许减不许增**：这一格不是"三枚之外还有几枚"的计数 —— B 档与 K3 之后
         * 把剩下的继续搬走时，本格仍然为真；
         * 谁在页 scope 名下**新**挂一笔写库（或把已经搬走的那一枚搬回来）才红。
         *
         * **T124b（B 档）从这一族摘掉的是两枚**：首页 `viewModel.deleteCourse(` 与管理页
         * `viewModel.deleteCourseGroup(` —— 那两手的写库已经进了 VM `...AndAwait` 体内那一枚
         * `viewModelScope` job（页 scope 名下现在只剩 `job.join()` 那个等待点，形状由档 ⑨⑩ 钉）。
         * ⚠️ 另两枚**留在册子上没摘**，因为它们在盘上确实还挂在页 scope 名下、本卡按红线也不动它们：
         * `viewModel.undo(` 与 `viewModel.undoDeleteCourse(` 是**非 suspend** 的 fire-and-forget，
         * VM 里 `fun undo()` 自己就起在 `viewModelScope` 上 ⇒ 搬不搬这一手都是既成事实，别顺手摘。
         * ⚠️ 编辑器的 `saveCourseDraft(` / `onDelete(target)` 是 **T124c** 的账，本卡不许动。
         */
        val PAGE_SCOPE_WRITE_LEDGER = mapOf(
            HOME_SCREEN to mapOf("viewModel.undo(" to 1),
            MANAGEMENT to mapOf("viewModel.undoDeleteCourse(" to 1),
            EDITOR to mapOf("saveCourseDraft(" to 1, "onDelete(target)" to 1),
        )

        /** 本卡搬走的三枚：文件 → 宿主 anchor → 该 handler 体内的写库笔数 */
        val MOVED = listOf(
            Triple(HOME_SCREEN, MOVE_ANCHOR, 3),
            Triple(HOME_SCREEN, RESIZE_ANCHOR, 1),
            Triple(MANAGEMENT, PICK_COLOR_ANCHOR, 1),
        )

        /**
         * 档 ⑧ 的**扫描清单**：这里把两枚文件自己列一遍，**不取 `MOVED` 的键** ——
         * 取了名册的键，「摘掉名册里那一枚」就会同时把盘上那一侧的扫描范围一起缩掉，
         * 两把尺一起退、本格永不红（那正是它要堵的那一扭）。
         * 新落点若长在这两枚文件之外：这里与 `MOVED` 要一起补，只补一边都红。
         */
        val VM_SCOPE_LANDING_FILES = listOf(HOME_SCREEN, MANAGEMENT)

        // ─────────── B 档（T124b）：两枚 ...AndAwait 的 VM 侧与调用点侧形状 ───────────

        const val SCHEDULE_VIEW_MODEL = "com/buaa/schedule/ui/ScheduleViewModel.kt"

        /** VM 那两枚入口体内共用的形状字面量（钉的是形状，不是文件级计数） */
        const val B_JOB_LAUNCH = "val job = viewModelScope.launch {"
        const val B_JOIN = "job.join()"
        const val B_LOCAL_FLAG = "var deleted = false"
        const val B_RETURN_FLAG = "return deleted"
        const val B_ANY_LAUNCH = "launch {"
        const val B_SNACKBAR_CALL = "snackbarHostState.showSnackbar("
        const val B_GATED_LABEL = "actionLabel = \"撤销\".takeIf { deleted },"

        /**
         * B 档两枚：VM 入口的签名 / 那一枚 job 体内的写库那一手 / 页面上那一支的调用点。
         * `signature` 以 `{` 收尾 —— [blockOf] 取的是"锚点之后第一个花括号"配平出来的那块，
         * 签名里若先出现花括号就会切错（真换了写法本格会以「锚点末尾不是花括号」点名，不会假绿）。
         */
        val B_STAGED = listOf(
            BStage(
                label = "首页长按删一门课（deleteCourseAndAwait）",
                uiFile = HOME_SCREEN,
                signature = "suspend fun deleteCourseAndAwait(course: Course): Boolean {",
                jobWrite = "deleted = deleteCourse(course)",
                passthrough = "return deleteCourse(course)",
                awaitCall = "viewModel.deleteCourseAndAwait(course)",
                rawCall = "viewModel.deleteCourse(",
                undoCall = "if (result == SnackbarResult.ActionPerformed) viewModel.undo()",
            ),
            BStage(
                label = "课表管理页删一整组（deleteCourseGroupAndAwait）",
                uiFile = MANAGEMENT,
                signature = "suspend fun deleteCourseGroupAndAwait(courses: List<Course>): Boolean {",
                jobWrite = "deleted = deleteCourseGroup(courses)",
                passthrough = "return deleteCourseGroup(courses)",
                awaitCall = "viewModel.deleteCourseGroupAndAwait(target.fragments)",
                rawCall = "viewModel.deleteCourseGroup(",
                undoCall = "viewModel.undoDeleteCourse()",
            ),
        )

        /** B 档一枚的登记形状 */
        data class BStage(
            val label: String,
            val uiFile: String,
            val signature: String,
            val jobWrite: String,
            val passthrough: String,
            val awaitCall: String,
            val rawCall: String,
            val undoCall: String,
        )
    }

    // ─────────────── ① 拖课：整块搬到了 viewModelScope 上 ───────────────

    @Test
    fun `拖课那三笔写库的协程接收者是 viewModelScope 而不是页 scope`() {
        val block = assertMovedLanding(
            file = HOME_SCREEN,
            anchor = MOVE_ANCHOR,
            label = "handleCourseMove（拖拽与「移动到…」选择框的共同收口）",
            expectedWrites = 3,
        )
        assertTrue(
            "那一枚 viewModelScope 协程必须在 `val shifted = course.copy(` **之后**：写库递的是" +
                "算好的那一份，把协程起在 copy 之前就是另一种形状了（档 ①②③ 判的是这一枚收口）",
            block.indexOf(VM_SCOPE_LAUNCH) > block.indexOf(MOVE_COPY),
        )
    }

    // ─────────────── ② 缩放改节次 ───────────────

    @Test
    fun `缩放那一笔写库的协程接收者是 viewModelScope 而不是页 scope`() {
        val block = assertMovedLanding(
            file = HOME_SCREEN,
            anchor = RESIZE_ANCHOR,
            label = "handleCourseResize（缩放改节次）",
            expectedWrites = 1,
        )
        assertEquals(
            "那一笔写库逐字原样（本卡只搬接收者，载荷一字不许动）：" +
                "\n  实到「$RESIZE_COPY」在体内 " + occurrences(block, RESIZE_COPY) + " 处" +
                "\n复算：grep -n 'newPeriods.sorted(), isManualOverride' app/src/main/java/$HOME_SCREEN",
            1,
            occurrences(block, RESIZE_COPY),
        )
    }

    // ─────────────── ③ 管理页换色（组外观） ───────────────

    @Test
    fun `管理页换色那一笔写库的协程接收者是 viewModelScope 而不是页 scope`() {
        val block = assertMovedLanding(
            file = MANAGEMENT,
            anchor = PICK_COLOR_ANCHOR,
            label = "onPickColor（课表管理页那一格换色，走 applyToGroup）",
            expectedWrites = 1,
        )
        assertEquals("那一记 copy 逐字原样", 1, occurrences(block, PICK_COPY))
        assertEquals(
            "那一枚 options 逐字原样（组外观那一支的账在 T128，本卡不动它）",
            1,
            occurrences(block, GROUP_OPTION),
        )
    }

    // ─────────────── ④ 三枚落点的宿主行逐字 + 行号两种算法自证 ───────────────

    @Test
    fun `三枚落点的宿主行逐字是 viewModelScope 那一枚 行号两种算法给同一个数`() {
        for ((file, anchor, _) in MOVED) {
            val raw = source(file)
            val code = blankComments(raw)
            val block = blockOf(code, anchor, "$file 的「$anchor」")
            val hits = indexOfAll(block.text, VM_SCOPE_LAUNCH)
            assertEquals(
                "宿主行 `viewModel.viewModelScope.launch {` 在该 handler 体内恰好一处（实到 " +
                    hits.size + " 处）：$file / 「$anchor」",
                1,
                hits.size,
            )
            val absolute = block.start + hits.first()
            val byLineOf = lineOf(raw, absolute)
            val bySplit = raw.substring(0, absolute).split('\n').size
            assertEquals(
                "两种独立算法（数换行 vs 按 `\\n` 切分）读出的行号不一致 ⇒ 报错消息会指错地方：" +
                    "$file 宿主行按 lineOf 是 L$byLineOf、按 split 是 L$bySplit",
                bySplit,
                byLineOf,
            )
            val lineText = raw.lines().getOrElse(byLineOf - 1) { "<越界>" }.trim()
            assertEquals(
                "宿主行必须逐字是 `$VM_SCOPE_LAUNCH`（缩进不计）——实到 $file L$byLineOf：「$lineText」。" +
                    "这一枚的接收者若被写回页 scope 的那枚变量，档 ①②③ 与本格一起红",
                VM_SCOPE_LAUNCH,
                lineText,
            )
        }
        // 这里原来还有一枚 `assertEquals("三枚落点都要被点到名", MOVED.size, report.count { it == '\n' })`：
        // 那个 report 在循环里每轮恰好 append 一枚 `\n` ⇒ 右侧量的就是「循环跑了几轮」，而循环就是按
        // `MOVED` 迭代的 ⇒ 左右同为 MOVED.size、恒等、结构上不可能红（与 T118 判掉 DG ③ 那一格同款的尺）。
        // 本格因此只留下三枚真判据：体内那一枚协程恰好一处、两种行号算法给同一个数、宿主行逐字。
        // 它原本想钉的「盘上落点与名册互为等集」已由档 ⑧ 那格（一次 `MOVED` 都不读的独立盘扫 ↔
        // 一次盘都不开的名册身份，双向差集）接管 ⇒ 按同一判法删掉，不留死码。

        // 两枚文件的接线前提：import 在、且那枚页 scope 声明本卡没顺手删（B 档还要用它）
        for (file in listOf(HOME_SCREEN, MANAGEMENT)) {
            val code = blankComments(source(file))
            assertTrue("$file 的 `$VM_SCOPE_IMPORT` 必须在（少了它这两枚落点根本编译不过）", code.contains(VM_SCOPE_IMPORT))
            assertEquals(
                "$file 里 `val scope = rememberCoroutineScope()` 的声明恰好一处（本卡不动声明，" +
                    "B 档那两枚落点还要挂在它上面）",
                1,
                occurrences(code, SCOPE_DECL),
            )
        }
    }

    // ─────────────── ⑤ 搬走的三枚 body 里没有 composition-only 调用 ───────────────

    @Test
    fun `搬走的三枚协程体里没有任何只在组合期成立的调用`() {
        for ((file, anchor, _) in MOVED) {
            val code = blankComments(source(file))
            val block = blockOf(code, anchor, "$file 的「$anchor」").text
            val launchAt = block.indexOf(VM_SCOPE_LAUNCH)
            assertTrue("先要有那一枚协程才谈得上它的 body：$file / 「$anchor」", launchAt >= 0)
            val body = bodyOf(block, launchAt + VM_SCOPE_LAUNCH.length - 1, "$file 的 viewModelScope 协程体")
            val found = COMPOSITION_ONLY.filter { body.contains(it) }
            assertTrue(
                "这一枚被整块搬走的协程体里出现了 composition-only 调用：" + found.joinToString { "「$it」" } +
                    "\n  $file / 「$anchor」\n搬进 `viewModelScope` 之后那些东西要么读不到、要么在页面已经" +
                    "离开时把回灌链拆断（提示条永远不出现）。正确做法只有一种：写库那一手搬走、" +
                    "composition-only 那几行留在页 scope 上 —— 那是 B 档的形状，与整块搬不能混用",
                found.isEmpty(),
            )
        }
    }

    // ─────────────── ⑥ 反向棘轮：页 scope 名下不许再长出写库链 ───────────────

    @Test
    fun `页 scope 名下的写库链册子只许减不许增`() {
        val actual = pageScopeWriteLedger()
        val grown = mutableListOf<String>()
        for ((file, byNeedle) in actual.toSortedMap()) {
            val allowed = PAGE_SCOPE_WRITE_LEDGER[file]
            if (allowed == null) {
                grown += "$file 新挂了一枚页 scope 写库落点（不在册子里）：" + byNeedle.toSortedMap().entries.joinToString()
                continue
            }
            for ((needle, count) in byNeedle.toSortedMap()) {
                val cap = allowed[needle]
                if (cap == null) {
                    grown += "$file 的页 scope 体里长出新的那一手「$needle」×$count（不在册子里）"
                } else if (count > cap) {
                    grown += "$file 的页 scope 体里「$needle」从册子登记的 $cap 处涨到 $count 处"
                }
            }
        }
        assertTrue(
            "页 scope（`rememberCoroutineScope()` 供给的变量）名下写库链超出参照物：\n" +
                grown.joinToString("\n") +
                "\n  实到册子：" + actual.toSortedMap().entries.joinToString { (f, n) ->
                    f + "={" + n.toSortedMap().entries.joinToString { "${it.key}×${it.value}" } + "}"
                } +
                "\n  参照物登记的册子：" + PAGE_SCOPE_WRITE_LEDGER.toSortedMap().entries.joinToString { (f, n) ->
                    f + "={" + n.toSortedMap().entries.joinToString { "${it.key}×${it.value}" } + "}"
                } +
                "\n本卡判据是「写库那一手不许挂在 composition scope 上」：新长出一枚 = 又开一笔" +
                "『库里改了、撤销条目没压、课前铃/桌面组件/明日预告不重排、UI 静默』的账。" +
                "反向也挑明：把已登记的搬走**不红**（只许减不许增），要继续收就照 B/K3 那一档——" +
                "body 里带 composition-only 的那些枚只能半搬。" +
                "\n复算：grep -rn 'rememberCoroutineScope()' app/src/main --include=*.kt",
            grown.isEmpty(),
        )
        assertFalse(
            "参照物册子被掏空了：这一族的账今天还没收完，册子里一枚都不留就等于这一格不再判任何东西",
            PAGE_SCOPE_WRITE_LEDGER.isEmpty(),
        )
        // 本卡搬走的三枚：今天必须确实**不在**页 scope 名下（棘轮的"这一侧"）
        val movedGhosts = MOVED.filter { (file, anchor, _) ->
            val code = blankComments(source(file))
            occurrences(blockOf(code, anchor, file).text, PAGE_SCOPE_LAUNCH) > 0
        }
        assertTrue("本卡搬走的三枚里又出现页 scope 落点：$movedGhosts", movedGhosts.isEmpty())
    }

    // ─────────────── ⑦ 先例未被顶掉 ───────────────

    @Test
    fun `先例那一枚仍走 viewModelScope 加 join`() {
        val code = blankComments(source(WIZARD))
        assertEquals(
            "`applyConflictShift` 那一枚 `val job = viewModel.viewModelScope.launch {` 必须逐字在（实到 " +
                occurrences(code, "val job = viewModel.viewModelScope.launch {") + " 处）：" +
                "它是本卡搬法的先例，被换回页 scope 就是两头一起退。" +
                "\n复算：grep -n 'viewModel.viewModelScope.launch' app/src/main/java/$WIZARD",
            1,
            occurrences(code, "val job = viewModel.viewModelScope.launch {"),
        )
        assertEquals("同一函数仍按 `job.join()` 等结果", 1, occurrences(code, "job.join()"))
        assertEquals("先例那一枚体内仍读返回值（那一笔 updateCourse 还在）", 1, occurrences(code, UPDATE_COURSE))
    }

    // ─────────────── ⑧ 双向闭合：盘上扫出的落点集合 ↔ 名册 MOVED 互为等集 ───────────────

    /**
     * 档 ①〜③ 的读法都是「拿 `MOVED` 当输入去查盘」⇒ 它只钉得住**在册的每一枚都合格**；
     * 谁把某一枚落点写回页 scope 之后顺手把名册里那一枚也删掉，那三档一起退、这一族反而全绿。
     * 本格钉的是另一侧：**盘上该被点名的每一枚都进了名册**，两把尺互相证伪 ——
     *  - 盘上那一侧：[diskVMScopeLandingIdentities] 只认 [VM_SCOPE_LANDING_FILES] 那两枚文件，
     *    按接收者形状扫 `viewModel.viewModelScope.launch {`，花括号配平取体、在体内数写库笔数，
     *    **一次 `MOVED` 都不读**；
     *  - 名册那一侧：`MOVED` 逐枚把 文件 × 登记的笔数 拼成身份，**一次盘都不开**。
     * ⇒ 身份拼法同一个函数、两侧输入两条独立路径：名册少登记一枚（含被摘掉那一枚）红，
     * 盘上多长出一枚（B 档落点被悄悄搬过去、或复制一份）也红；只比 size 会漏「一枚身份换成另一枚」那一扭，
     * 故按身份做**多重集**双向差。
     */
    @Test
    fun `盘上扫出的 viewModelScope 落点集合与名册互为等集 摘一枚或长一枚都红`() {
        val disk = diskVMScopeLandingIdentities()
        val roster = MOVED.map { (file, anchor, writes) ->
            Landing(
                file = file,
                site = "名册登记的 anchor「$anchor」",
                identity = landingIdentity(file, writes),
                excerpt = "登记体内写库 $writes 笔",
            )
        }
        assertTrue(
            "盘上那一侧一枚 `$VM_SCOPE_LAUNCH` 都没扫到 ⇒ 本格不再判任何东西（空集对空集也算闭合）：" +
                "\n  扫描清单：$VM_SCOPE_LANDING_FILES" +
                "\n  名册登记的身份：" + roster.map { it.identity },
            disk.isNotEmpty(),
        )
        val byDisk = disk.map { it.identity }.groupingBy { it }.eachCount()
        val byRoster = roster.map { it.identity }.groupingBy { it }.eachCount()
        val unregistered = byDisk.filter { (identity, n) -> n > (byRoster[identity] ?: 0) }.keys
        val notOnDisk = byRoster.filter { (identity, n) -> n > (byDisk[identity] ?: 0) }.keys
        assertTrue(
            "盘上导出的落点集合与名册 `MOVED` 互为等集这一判不成立（朝宽：盘上长了一枚没登记；" +
                "朝窄：名册里那一枚在盘上已不存在）：" +
                (if (unregistered.isEmpty()) "" else "\n  盘上有、名册没登记：" + disk.filter { it.identity in unregistered }
                    .joinToString("；") { "${it.file} 那一枚（体内首行「${it.excerpt}」）= ${it.identity}" }) +
                (if (notOnDisk.isEmpty()) "" else "\n  名册有、盘上扫不到：" + roster.filter { it.identity in notOnDisk }
                    .joinToString("；") { "${it.file} 的 ${it.site} = ${it.identity}" }) +
                "\n  盘上导出 ${disk.size} 枚：" + disk.joinToString("；") { "${it.file}→${it.identity}" } +
                "\n  名册登记 ${roster.size} 枚：" + roster.joinToString("；") { "${it.file}→${it.identity}" } +
                "\n身份 = 文件 + 那一枚协程体内 `$UPDATE_COURSE` 的笔数；两把尺一条只读盘、一条只读名册，" +
                "所以『把落点写回页 scope 再顺手把名册那一枚删掉』与『把一枚 B 档落点悄悄搬上 viewModelScope』" +
                "都会在这一格露出来。" +
                "\n收法只有两种，都得明说：① 那一枚本就该在 viewModelScope 上 ⇒ 往 `MOVED` 补一枚" +
                "（宿主 anchor 与体内笔数一起登记，档 ①②③④⑤ 跟着它走）；" +
                "② 那一枚不该在 ⇒ 把盘上那一枚写回原处，**别摘名册**（摘名册不摘盘就是本格要堵的那一扭）。" +
                "\n复算：grep -n 'viewModel.viewModelScope.launch' " +
                VM_SCOPE_LANDING_FILES.joinToString(" ") { "app/src/main/java/$it" },
            unregistered.isEmpty() && notOnDisk.isEmpty(),
        )
    }

    // ─────────────── ⑨ B 档：VM 那两枚 ...AndAwait 体内的接收者与结论回带 ───────────────

    /**
     * 卡面定的钉法是「**方法体里必须有这一枚 job、且结论由它带回**」—— 不是"全仓 `viewModelScope`
     * 出现几次"。所以每一枚先按签名花括号配平切出它自己的函数体，再在**体内**判六件事：
     *  1. `val job = viewModelScope.launch {` 恰好一枚，且它是体内**唯一**一枚 `launch {`
     *     （接收者形状 + 枚数一起钉：换成 `scope.launch` 或再起一枚都会露出来）；
     *  2. 写库那一手（`deleted = deleteCourse(course)`）逐字恰好一枚，且**整个躺在那一枚 job 的协程体里**
     *     —— 体外一笔都不许留（"枚数对但落点错"那一洞）；
     *  3. `job.join()` 恰好一枚，且**不在**协程体内 ⇒ 它是调用者 scope 上的等待点：页面已经离开时
     *     调用者一起被取消，而那一枚 job 归 viewModelScope 管、继续跑完（这正是 B 档要的效果）；
     *  4. `return deleted` 排在 `job.join()` **之后** ⇒ 端出去的就是那一枚 job 写进局部量的结论；
     *  5. `var deleted = false` 恰好一枚、在体内、在 launch **之前** ⇒ 它是函数内的局部捕获，
     *     没被升级成 VM 的共享可变属性（那会新造一枚没人清的状态，本仓为这类账判过死）；
     *  6. 反向钉 `return deleteCourse(course)` 那一型 0 处 —— **朝宽扭（把方法退成透传）红在这一格**：
     *     透传等于写库又落回调用者的 scope，第 1〜5 条一起塌。
     */
    @Test
    fun `B档两枚AndAwait的VM体内 写库那一手在viewModelScope的job里且结论由它带回`() {
        val code = blankComments(source(SCHEDULE_VIEW_MODEL))
        for (stage in B_STAGED) {
            val fn = blockOf(code, stage.signature, "VM 的「${stage.signature}」").text

            assertEquals(
                "${stage.label}：体内那一枚 `$B_JOB_LAUNCH` 恰好一处（实到 " +
                    occurrences(fn, B_JOB_LAUNCH) + " 处）。这一枚 job 就是写库那一手的接收者 —— " +
                    "它挂在 viewModelScope 上，页面被 dispose 时不会被取消。" +
                    "\n复算：grep -n 'viewModelScope.launch' app/src/main/java/$SCHEDULE_VIEW_MODEL",
                1,
                occurrences(fn, B_JOB_LAUNCH),
            )
            assertEquals(
                "${stage.label}：体内 `$B_ANY_LAUNCH` 总数必须恰好 1（实到 " + occurrences(fn, B_ANY_LAUNCH) +
                    " 处）：上一格钉的是 viewModelScope 那一枚，这一格钉的是「除此之外没有第二枚协程」——" +
                    "把接收者写回页 scope 的 `scope.launch` 会同时塌掉两格，只起第二枚也算",
                1,
                occurrences(fn, B_ANY_LAUNCH),
            )
            val jobAt = fn.indexOf(B_JOB_LAUNCH)
            val jobBody = bodyOf(fn, jobAt + B_JOB_LAUNCH.length - 1, stage.label + " 的那一枚 viewModelScope 协程体")
            assertEquals(
                "${stage.label}：写库那一手 `${stage.jobWrite}` 在体内逐字恰好一处（实到 " +
                    occurrences(fn, stage.jobWrite) + " 处）",
                1,
                occurrences(fn, stage.jobWrite),
            )
            assertEquals(
                "${stage.label}：那一笔写库必须在**那一枚 job 的协程体内**（体外还漏了 " +
                    (occurrences(fn, stage.jobWrite) - occurrences(jobBody, stage.jobWrite)) +
                    " 笔）：枚数对而落点在体外 = 它又跟着调用者的 scope 一起被取消",
                1,
                occurrences(jobBody, stage.jobWrite),
            )
            assertEquals(
                "${stage.label}：`$B_JOIN` 在体内恰好一处（实到 " + occurrences(fn, B_JOIN) +
                    " 处），且**不许在协程体内**（实到 " + occurrences(jobBody, B_JOIN) + " 处）：" +
                    "join 是页 scope 那一侧的等待点，塞进 job 里就没人等结论了",
                true,
                occurrences(fn, B_JOIN) == 1 && occurrences(jobBody, B_JOIN) == 0,
            )
            assertEquals(
                "${stage.label}：局部捕获 `$B_LOCAL_FLAG` 在体内恰好一处（实到 " + occurrences(fn, B_LOCAL_FLAG) +
                    " 处）——它一旦升级成 VM 的属性，这一处就会从体内消失",
                1,
                occurrences(fn, B_LOCAL_FLAG),
            )
            assertEquals(
                "${stage.label}：`$B_RETURN_FLAG` 在体内恰好一处（实到 " + occurrences(fn, B_RETURN_FLAG) +
                    " 处）：端出去的就是那一枚 job 写进局部量的结论",
                1,
                occurrences(fn, B_RETURN_FLAG),
            )
            val flagAt = fn.indexOf(B_LOCAL_FLAG)
            val joinAt = fn.indexOf(B_JOIN)
            val returnAt = fn.indexOf(B_RETURN_FLAG)
            assertTrue(
                "${stage.label}：四拍次序应当是 局部捕获 → 起 job → 等 job → 端结论：" +
                    "\n  $B_LOCAL_FLAG@$flagAt / $B_JOB_LAUNCH@$jobAt / $B_JOIN@$joinAt / " +
                    "$B_RETURN_FLAG@$returnAt（-1 表示那一处压根没找着）" +
                    "\n任何一环倒过来（先 return 再 join、或捕获写在 launch 之后）端出去的都是上一次的值",
                flagAt in 0 until jobAt && jobAt in 0 until joinAt && joinAt in 0 until returnAt,
            )
            assertEquals(
                "${stage.label}：不许退成透传 `${stage.passthrough}`（实到 " + occurrences(fn, stage.passthrough) +
                    " 处）。**朝宽扭就在这一格红**：直接 `suspend` 转调 = 写库那一手又落回调用者" +
                    "（页 scope）的上下文里，本卡白做 ——  join() 只是等一个结论，它不搬接收者",
                0,
                occurrences(fn, stage.passthrough),
            )
        }
    }

    // ─────────────── ⑩ B 档：调用点只搬一半（写库换名、提示条留页 scope） ───────────────

    /**
     * 与档 ⑨ 成一对：那一枚 job 在 VM 里，而等它的那一手**必须仍挂在页 scope 上** ——
     * 否则 `showSnackbar` 与那颗「撤销」没人接（B 档之所以只能半搬，就是因为 body 里带着
     * composition-only 调用）。逐枚取"页 scope 那一枚 `scope.launch {` 的协程体"，在里面判：
     *  - `viewModel.deleteCourseAndAwait(...)` 逐字恰好一处，且**整支页 scope 协程只有这一枚**；
     *  - 旧的那一枚裸 suspend 调用 `${stage.rawCall}` 恰好 0 处 —— **朝窄扭（调用点换回旧名）红在这一格**；
     *  - `snackbarHostState.showSnackbar(` 恰好一处、那颗 `actionLabel = "撤销".takeIf { deleted },` 恰好一处、
     *    撤销那一手恰好一处：这三条钉的是"提示条那一手一字未动"（红线 2，那是 T121/T127 收到的账）。
     */
    @Test
    fun `B档两枚调用点仍挂在页scope上 写库那一手已换成AndAwait而提示条那一手一字未动`() {
        for (stage in B_STAGED) {
            val code = blankComments(source(stage.uiFile))
            val bodies = launchBraces(code, "scope.launch").map {
                bodyOf(code, it, "${stage.uiFile} 的那一枚页 scope 协程体")
            }
            val hosts = bodies.filter { occurrences(it, stage.awaitCall) == 1 }
            assertEquals(
                "${stage.label}：页 scope（`rememberCoroutineScope()` 供给的那一枚）名下必须**恰好一处** " +
                    "`${stage.awaitCall}`（实到 ${hosts.size} 处，盘上页 scope 协程共 ${bodies.size} 枚）。" +
                    "join 必须仍跑在页 scope 那一侧，否则 snackbar 那条回灌链没人接。" +
                    "\n复算：grep -n '${stage.awaitCall}' app/src/main/java/${stage.uiFile}",
                1,
                hosts.size,
            )
            val body = hosts.first()
            assertEquals(
                "${stage.label}：那一支里不许再出现裸的 `${stage.rawCall}`（实到 " +
                    occurrences(body, stage.rawCall) + " 处）。**朝窄扭就在这一格红**：把调用点换回" +
                    "`...AndAwait` 之前那一枚，写库又整块挂在 composition scope 上 —— 切 tab 时取消点" +
                    "落在「删已成」与「压撤销条目 / 重排课前铃与桌面组件」之间，库里删了而撤销栈没有、" +
                    "后续那一趟没跑，界面还一句都没说（这正是本卡收的那一笔账）",
                0,
                occurrences(body, stage.rawCall),
            )
            assertEquals(
                "${stage.label}：提示条那一手必须留在页 scope 上（`$B_SNACKBAR_CALL` 恰好一处，实到 " +
                    occurrences(body, B_SNACKBAR_CALL) + " 处）：它是 composition-only 的调用，" +
                    "跟着写库一起搬进 VM 就是拆断回灌链（档 ⑤ 判的是被整块搬走的那三枚，这一格判的是没搬的那一枚）",
                1,
                occurrences(body, B_SNACKBAR_CALL),
            )
            assertEquals(
                "${stage.label}：那颗「撤销」的读法一字未改（`$B_GATED_LABEL` 恰好一处，实到 " +
                    occurrences(body, B_GATED_LABEL) + " 处）：本卡只换接收者与调用名，" +
                    "删没删到的两支文案与这道闸是 T121/T127 收到的账",
                1,
                occurrences(body, B_GATED_LABEL),
            )
            assertEquals(
                "${stage.label}：点了那颗按钮才撤销（`${stage.undoCall}` 恰好一处，实到 " +
                    occurrences(body, stage.undoCall) + " 处）",
                1,
                occurrences(body, stage.undoCall),
            )
            assertTrue(
                "${stage.label}：读结论必须排在提示条之前（同一个 launch 块里读同一枚 deleted）",
                body.indexOf(stage.awaitCall) in 0 until body.indexOf(B_SNACKBAR_CALL),
            )
            assertFalse(
                "${stage.label}：那一枚页 scope 协程体里不许出现 `$VM_SCOPE_LAUNCH`（实到 " +
                    occurrences(body, VM_SCOPE_LAUNCH) + " 处）：B 档搬走的那一半长在 **VM 方法体内**" +
                    "的那一枚 job 里，不在 UI 文件里 —— UI 里新长出一枚这种落点而没进 `MOVED` 名册，" +
                    "档 ⑧ 那一格也会一起红（那是它该红）。A 档搬走的三枚宿主行在**别的** handler 里，" +
                    "本格取的是这一枚协程体，不数整份文件",
                occurrences(body, VM_SCOPE_LAUNCH) > 0,
            )
        }
    }

    // ---------------- helpers ----------------

    /** 档 ①②③ 的共同判：那一枚协程在、页 scope 不在、写库笔数对、且每笔都在那一枚协程体内 */
    private fun assertMovedLanding(file: String, anchor: String, label: String, expectedWrites: Int): String {
        val code = blankComments(source(file))
        val block = blockOf(code, anchor, "$label 的 anchor「$anchor」").text
        assertEquals(
            "$label 体内 `viewModel.viewModelScope.launch {` 恰好一处（实到 " +
                occurrences(block, VM_SCOPE_LAUNCH) + " 处）" +
                "\n复算：grep -n 'viewModelScope.launch' app/src/main/java/$file",
            1,
            occurrences(block, VM_SCOPE_LAUNCH),
        )
        assertEquals(
            "$label 体内页 scope 的 `scope.launch {` 必须 0 处（实到 " + receiverOccurrences(block, PAGE_SCOPE_LAUNCH) +
                " 处）：那一枚 scope 绑在这次组合上，目的地被 dispose（切 tab 走 popUpTo+saveState）时" +
                "它一起被取消 ⇒ 取消点落在写库之后、pushUpdate/afterDataChangedInternal 之前，" +
                "就是库里改了而撤销条目与课前铃/桌面组件/明日预告没跟上，界面上还完全静默",
            0,
            receiverOccurrences(block, PAGE_SCOPE_LAUNCH),
        )
        assertEquals(
            "$label 体内写库那一手恰好 $expectedWrites 笔（实到 " + occurrences(block, UPDATE_COURSE) + " 笔）",
            expectedWrites,
            occurrences(block, UPDATE_COURSE),
        )
        val launchAt = block.indexOf(VM_SCOPE_LAUNCH)
        val body = bodyOf(block, launchAt + VM_SCOPE_LAUNCH.length - 1, label)
        val outside = occurrences(block, UPDATE_COURSE) - occurrences(body, UPDATE_COURSE)
        assertEquals(
            "$label 的 $expectedWrites 笔写库必须**全部**在那一枚 viewModelScope 协程体内" +
                "（体外还漏了 $outside 笔：枚数对但落点错，正是本仓要防的那一洞）",
            0,
            outside,
        )
        return block
    }

    /** 全仓：每枚由 `rememberCoroutineScope()` 供给的变量名下的 launch 体里，写库那一手的册子 */
    private fun pageScopeWriteLedger(): Map<String, Map<String, Int>> {
        val out = mutableMapOf<String, MutableMap<String, Int>>()
        val decl = Regex("""val\s+(\w+)\s*=\s*rememberCoroutineScope\(\)""")
        for ((relative, text) in mainSources()) {
            val code = blankComments(text)
            val names = decl.findAll(code).map { it.groupValues[1] }.toSet()
            if (names.isEmpty()) continue
            val byNeedle = mutableMapOf<String, Int>()
            for (name in names) {
                val receiver = "$name.launch"
                for (open in launchBraces(code, receiver)) {
                    val body = bodyOf(code, open, "$relative 的「$receiver」")
                    for (needle in WRITE_NEEDLES) {
                        val n = occurrences(body, needle)
                        if (n > 0) byNeedle[needle] = (byNeedle[needle] ?: 0) + n
                    }
                }
            }
            if (byNeedle.isNotEmpty()) out[relative] = byNeedle
        }
        return out
    }

    /**
     * `<name>.launch` 那一次调用开出来的花括号位置：只认 `launch {` 与 `launch(…) {` 两种形状，
     * 且要求接收者前面不是标识符字符（`dragScope.launch` 不算 `scope.launch`）。
     */
    private fun launchBraces(code: String, receiver: String): List<Int> {
        val opens = mutableListOf<Int>()
        for (at in receiverOccurrencesWithOffset(code, receiver)) {
            var i = at + receiver.length
            if (i < code.length && code[i] == '(') {
                var depth = 0
                var closedAt = -1
                while (i < code.length) {
                    when (code[i]) {
                        '(' -> depth++
                        ')' -> depth--
                        else -> if (code[i] == '{') break // 括号里不该出现花括号：这一枚形状不认识
                    }
                    if (depth == 0) { closedAt = i; break }
                    i++
                }
                if (closedAt < 0) continue
                i = closedAt + 1
            }
            while (i < code.length && (code[i] == ' ' || code[i] == '\t' || code[i] == '\n' || code[i] == '\r')) i++
            if (i < code.length && code[i] == '{') opens += i
        }
        return opens
    }

    private data class Block(val text: String, val start: Int)

    /** 档 ⑧ 两侧共用的落点身份：file/site 供点名，identity 供等集比对，excerpt 是盘上那枚协程体的首行原文 */
    private data class Landing(val file: String, val site: String, val identity: String, val excerpt: String)

    /**
     * 两把尺共用的**身份拼法**（只是拼串，不是任何一侧的输入）：文件 + 那一枚 viewModelScope 协程体内
     * `$UPDATE_COURSE` 的笔数。名册那一侧的笔数取自 `MOVED` 的登记值、盘上那一侧取自体内实数 ——
     * 档 ①②③ 已经钉过「该 handler 体内的每一笔写库都在那一枚协程体内」，故两侧今天必须是同一个数，
     * 有人把一笔写库留在协程体外时这里也会一起露出来。
     */
    private fun landingIdentity(file: String, writes: Int): String = "$file#$UPDATE_COURSE×$writes"

    /**
     * 档 ⑧ 的**盘上那一侧**（独立读形状，全程不读 `MOVED`、不读 anchor 名）：
     * 扫 [VM_SCOPE_LANDING_FILES] 那两枚文件里每一枚 `viewModel.viewModelScope.launch {`
     * （接收者形状：前面不许贴标识符字符），花括号配平取它自己的协程体，在体内数写库笔数当身份。
     */
    private fun diskVMScopeLandingIdentities(): List<Landing> {
        val out = mutableListOf<Landing>()
        for (file in VM_SCOPE_LANDING_FILES) {
            val code = blankComments(source(file))
            for (at in receiverOccurrencesWithOffset(code, VM_SCOPE_LAUNCH)) {
                val body = bodyOf(code, at + VM_SCOPE_LAUNCH.length - 1, "$file 里那一枚 `$VM_SCOPE_LAUNCH` 的协程体")
                out += Landing(
                    file = file,
                    site = "盘上扫到的那一枚协程",
                    identity = landingIdentity(file, occurrences(body, UPDATE_COURSE)),
                    excerpt = firstLineExcerpt(body),
                )
            }
        }
        return out.sortedBy { it.identity }
    }

    /** 点名用的原文片段：体内第一行非空白（刻意不给行号 ⇒ 行号漂了这句也不会指错地方） */
    private fun firstLineExcerpt(body: String): String =
        body.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: "<协程体为空>"

    /** 从锚点起按花括号配平取整块（含锚点本身），返回块文本与它在原文里的起点偏移 */
    private fun blockOf(code: String, anchor: String, label: String): Block {
        val at = code.indexOf(anchor)
        assertTrue("$label 的锚点没找到（改名/挪家要回来重钉本守卫）：「$anchor」", at >= 0)
        val open = code.indexOf('{', at)
        assertTrue("$label 找不到开括号", open >= 0)
        return Block(code.substring(at, closeBraceOf(code, open, label) + 1), at)
    }

    /** [openBrace] 那枚花括号的**体内**文本（不含两侧花括号） */
    private fun bodyOf(code: String, openBrace: Int, label: String): String =
        code.substring(openBrace + 1, closeBraceOf(code, openBrace, label))

    private fun closeBraceOf(code: String, openBrace: Int, label: String): Int {
        check(code[openBrace] == '{') { "$label 的锚点末尾不是花括号：写法变了" }
        var depth = 0
        for (i in openBrace until code.length) {
            when (code[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        throw IllegalStateException("$label 的花括号没配平")
    }

    private fun source(relative: String): String = File(findMainJavaDir(), relative).readText()

    private fun mainSources(): Map<String, String> {
        val root = findMainJavaDir()
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .associateBy({ it.relativeTo(root).path.replace('\\', '/') }, { it.readText() })
    }

    private fun occurrences(hay: String, needle: String): Int = indexOfAll(hay, needle).size

    /** 接收者式的搜：命中处的前一个字符不许是标识符字符（`rowScope.launch` 不算 `scope.launch`） */
    private fun receiverOccurrencesWithOffset(hay: String, needle: String): List<Int> =
        indexOfAll(hay, needle).filter { at -> at == 0 || !(hay[at - 1].isLetterOrDigit() || hay[at - 1] == '_') }

    private fun receiverOccurrences(hay: String, needle: String): Int = receiverOccurrencesWithOffset(hay, needle).size

    private fun indexOfAll(hay: String, needle: String): List<Int> {
        val hits = mutableListOf<Int>()
        if (needle.isEmpty()) return hits
        var at = hay.indexOf(needle)
        while (at >= 0) {
            hits += at
            at = hay.indexOf(needle, at + needle.length)
        }
        return hits
    }

    /** 1-based 行号：`lineOf(src, 0) == 1`；拼消息时只写 `lineOf(...)`，不再做加法 */
    private fun lineOf(src: String, index: Int): Int =
        src.substring(0, index.coerceAtLeast(0).coerceAtMost(src.length)).count { it == '\n' } + 1

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
