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
 * B 档（读返回值那一手）与 K3（编辑器那两枚）今天**没动**：它们的 body 里带着
 * `snackbarHostState.showSnackbar(...)` 这类只在组合期成立的东西，整块搬会把提示条的回灌链拆断。
 * 档 ⑤ 因此钉"被整块搬走的三枚 body 里 composition-only 调用恰好 0 处"（真搬了），
 * 档 ⑥ 反向钉"页 scope 名下不许再长出新的写库链"。
 *
 * ## 钉法（七档，两侧都有格子）
 *
 * 1. 拖课 handler 体内：`viewModel.viewModelScope.launch {` 恰好一枚、页 scope 那一枚恰好 0 枚、
 *    三笔 `viewModel.updateCourse(` **全部**躺在那一枚协程体里（落点 + 读形状，不是文件级计数）；
 * 2. 缩放 handler 体内：同一形状，一笔写库，且那一记 copy 逐字原样；
 * 3. 管理页 `onPickColor` 体内：同一形状，一笔写库，copy 与 `CourseSaveOptions(applyToGroup = true)`
 *    逐字原样（搬接收者不许顺手改载荷）；
 * 4. 三枚落点的**宿主行逐字**是 `viewModel.viewModelScope.launch {`，行号由两种独立算法
 *    （数换行 / 按 `\n` 切分）给出同一个数，报错消息逐枚点名 文件 + 行号 + 原文；
 * 5. 三枚 body 里 composition-only 调用 0 处（提示条 / 分页 / 滚动 / 触感 / `LaunchedEffect`）：
 *    这一格是"整块搬成立"的证据，也是谁把 snackbar 塞进这一枚协程时的红名；
 * 6. **反向棘轮**：全仓每一枚由 `rememberCoroutineScope()` 供给的变量名下，写库那一手的册子
 *    按 文件 × 那一手逐字 登记，判据是**只许减不许增**（不是"三枚之外还剩几枚"的计数 ——
 *    B/K3 把剩下的继续搬走时这一格照样只说真话；新长出一枚、或把搬走的那一枚搬回来才红）。
 *    册子被掏空也红（那一格就不判任何东西了）；
 * 7. 先例未被顶掉：`applyConflictShift` 仍走 `viewModel.viewModelScope` + `job.join()`。
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

        /** 写库链的册子按这一组逐字名取（VM 的改库 API + 编辑器那两枚包装） */
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
         * 把首页删课那枚、管理页删组那枚、编辑器那两枚继续搬走时，本格仍然为真；
         * 谁在页 scope 名下**新**挂一笔写库（或把已经搬走的那一枚搬回来）才红。
         */
        val PAGE_SCOPE_WRITE_LEDGER = mapOf(
            HOME_SCREEN to mapOf("viewModel.deleteCourse(" to 1, "viewModel.undo(" to 1),
            MANAGEMENT to mapOf("viewModel.deleteCourseGroup(" to 1, "viewModel.undoDeleteCourse(" to 1),
            EDITOR to mapOf("saveCourseDraft(" to 1, "onDelete(target)" to 1),
        )

        /** 本卡搬走的三枚：文件 → 宿主 anchor → 该 handler 体内的写库笔数 */
        val MOVED = listOf(
            Triple(HOME_SCREEN, MOVE_ANCHOR, 3),
            Triple(HOME_SCREEN, RESIZE_ANCHOR, 1),
            Triple(MANAGEMENT, PICK_COLOR_ANCHOR, 1),
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
        val report = StringBuilder()
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
            report.append("\n  ").append(file).append(" L").append(byLineOf).append("：「").append(lineText).append("」")
        }
        assertEquals("三枚落点都要被点到名（逐枚 文件 + 行号 + 原文）", MOVED.size, report.count { it == '\n' })

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
