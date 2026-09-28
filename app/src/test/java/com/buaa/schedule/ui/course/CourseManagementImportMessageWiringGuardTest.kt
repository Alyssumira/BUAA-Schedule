package com.buaa.schedule.ui.course

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T134：前置守卫 —— 钉住「课表管理页这一格**今天没落地**，而且它今天**不该**由这张卡落地」。
 *
 * ## 判下来的三件事（本卡的结论是"不动 main"，所以守卫钉的是未满足的前置，不是修法）
 *
 * 1. **VM 的组删除链不是「只写不回的失败档」**：`deleteCourseGroup` 返回 `Boolean`，两条早退都回
 *    `false`（`if (courses.isEmpty()) return false` / `if (snapshot.courses.isEmpty())
 *    return@suspendCatching false`），`.fold({ it }, { e -> … })` 把结论原样端出去，异常那一档才顺手写
 *    `AppMessage("删除课程失败：…")`。而 `:222` 的两支文案与 `:223` 那颗 `actionLabel` 闸**都读的是同一枚
 *    `deleted`** ⇒ 删组这一族在 UI 上根本没有「用户点了、却一句反馈都没有」的档。T127 类头残账写的是
 *    **归因**（「还在课表里」这句没说清原因），不是「无人念」。
 * 2. **真正没人念的是另外两族**：换色那一族（`viewModel.updateCourse(` 的返回值 `Long?` 被丢掉）与撤销
 *    那一族（`fun undoDeleteCourse() = undo()` 返回 `Unit`，`没有可撤销的操作` / `已撤销：…` /
 *    `撤销失败：…` 三句只写进 `importMessage`）。撤销那一族按返回值**压根分不出文案** —— 这笔账在
 *    VM 的对外形状上，本卡红线明令不改 VM，所以只能登记。
 * 3. **接桥会付三笔账**：① `deleteCourseGroup` 异常那一档与 `:221` 那一条双念；② 同一条
 *    `SnackbarHostState` 上后 show 的取消先 show 的 ⇒ 「已删除 + 那颗撤销」会被随后任何一句
 *    `importMessage` 提前收掉（T127 §6 与 UndoUpdateEntry 第 ④ 层钉的就是那颗按钮的窗口）；
 *    ③ `HomeScreen :515-520` 那枚桥吃掉的是**整个** `StateFlow`：用户从首页带着一句未完成的话跳进
 *    管理页，那句话会被管理页吃掉、回首页就不再念。 ⇒ 判「这一页不该接 `importMessage`、
 *    真正的账在 VM 的对外形状上」，交付物 = 本守卫（扳机钉住前置未落地那一格）。
 *
 * ## 本守卫的判据为什么按「前置未满足」取
 *
 * 它**不许**被读成"当下盘面合格"：第 ①②⑥ 层钉的是「VM 在这一页能触发的动作里写了五句只进
 * `importMessage` 的话，而这一页一处也不读」——这是**病**，只是本卡判下来不由"在这一页补渲染"来收。
 * 谁要收，必须先让这三层红一次、并把第 ③ 层那十八枚锚点与第 ⑤ 层那格画得出的位置一起重钉：
 * - 在这一页补上任何 `importMessage` 渲染接线 ⇒ 第 ①②⑦ 层与第 ③ 层红（不许悄悄落地）；
 * - 把这一页唯一的渲染接线（`:221` 那一条）**摘掉** ⇒ 第 ③④⑤ 层红；
 * - 把它**挪到根本画不出来的位置**（例如 `if (isEmpty)` 那一支——有课的时候那个分支压根不组合）
 *   ⇒ 第 ③⑤ 层红，落点与次序全数对也不许放行。
 *
 * 只做**源码核对**（JVM，无 Robolectric、无设备、不读时钟）。判据一律按**落点 / 次序 / 逐字**取，
 * 不只数总出现次数。零装机级证据（本仓红线：禁 adb / 禁模拟器 / 禁真机）。
 */
class CourseManagementImportMessageWiringGuardTest {

    // ─────────────── ① 病本体：VM 在这一页能触发的五句话，这一页一处也不读 ───────────────

    @Test
    fun `管理页一处也不读 importMessage 而这一页能触发的五句只长在 VM 里`() {
        val code = blankCommentsKeepingLiterals(readMainSource(PAGE))
        val vm = blankCommentsKeepingLiterals(readMainSource(SCHEDULE_VIEW_MODEL))

        assertEquals(
            "这一页对 `importMessage` 的引用必须是 **0 处** —— 这一格钉的是**未落地的前置**，不是达标状态：\n" +
                "  · 红了第一读法 = 有人在这一页补了渲染接线。本卡判下来那条路**不是**收口（判词见类头第 3 条：" +
                "双念 / 顶掉那颗撤销 / 吃掉首页那枚全局 StateFlow），要落地得先连第 ②③⑤⑦ 层一起重判；\n" +
                "  · 若这一页当真接了话，这一格必须**改成**逐字钉桥的形状（落点 / 次序 / 读完即清），" +
                "不许一删了之 —— 那等于把 T127 类头那笔残账重新挂回无人认领。\n" +
                "复算：grep -n 'importMessage' app/src/main/java/$PAGE",
            0,
            occurrences(code, "importMessage"),
        )
        // 五枚写点逐字仍在（少了 = VM 不再只写 importMessage ⇒ 本卡的判据要重判；多了 = 又一句无人念）
        for ((needle, expected) in VM_POINTS_FOR_THIS_PAGE) {
            val actual = occurrences(vm, needle)
            assertEquals(
                "VM 写给**这一页能触发的动作**的那一句必须还在（`$needle`）。\n" +
                    "复算：grep -Fn '$needle' app/src/main/java/$SCHEDULE_VIEW_MODEL\n" +
                    "少了 = VM 把这句话改成返回给调用点了 ⇒ 这一页不再需要渲染它（本卡的判据当场换边）；" +
                    "多了 = 又一句只写不回的反馈进了同一枚流 ⇒ 得回来重判「谁念、念几次」：" +
                    "实际 $actual 处，应当 $expected 处",
                expected,
                actual,
            )
        }
    }

    // ─────────────── ② 消费册子：今天三枚，管理页不在册（扳机格） ───────────────

    @Test
    fun `消费 importMessage 的页面册子今天三枚 管理页不在册`() {
        val byFile = readAllMainSources().mapValues { (_, text) -> blankCommentsKeepingLiterals(text) }
        val readers = byFile.filter { occurrences(it.value, READER_NEEDLE) > 0 }.keys.toList().sorted()

        assertEquals(
            "全仓**消费** `viewModel.importMessage` 的页面今天恰好三枚：首页 `:515` / 导入页 `:117` / " +
                "设置页 `:199`（首页那一枚原写 `:467`，是 T124a 之前就漂了的旧数，本卡按盘面订正）。" +
                "课表管理页**不在册** —— 这一格就是本卡那句「谁要落地先看这格」的可复核形式。\n" +
                "复算：git grep -n 'viewModel.importMessage.collectAsState()' -- app/src/main\n" +
                "多一枚（管理页进册）= 有人落地了渲染接线，得连第 ③⑤⑦ 层与 T127 §6「只许一处 showSnackbar」" +
                "那格一起重钉；少一枚 = 有一页的接线漂了，那句话又回到无人念",
            listOf(HOME_SCREEN_NAME, IMPORT_SCREEN_NAME, SETTINGS_SCREEN_NAME),
            readers,
        )
        assertEquals(
            "每一枚读者各建一次读物（合计恰好三枚）：同一页两枚 `collectAsState` 会把「谁吃掉这枚 StateFlow」" +
                "变成未定义 —— 本卡的判词第 3 条③（接桥会抢吃掉全局那一句）正是靠这一格才量得出来",
            3,
            byFile.values.sumOf { occurrences(it, READER_NEEDLE) },
        )
        assertEquals(
            "`showSnackbar(message.text)` 那枚「读完即清」的桥今天全仓只有一枚（首页 `:518`）：" +
                "第二枚进来就是「两页抢吃同一枚 StateFlow」，谁先组合谁吃掉 ⇒ 必须连本卡第 ①② 层的判词一起重判。" +
                "\n复算：grep -rn 'showSnackbar(message.text)' app/src/main --include='*.kt'",
            1,
            byFile.values.sumOf { occurrences(it, "showSnackbar(message.text)") },
        )
        assertFalse(
            "管理页**不在**读者册子里（这一格与第 ① 层同题，但它是按**册子**判的：把桥藏进别的 composable " +
                "再从管理页调它，第 ① 层按单文件数是 0 处、这一格按册子照样抓得住）",
            readers.contains(COURSE_MANAGEMENT_NAME),
        )
    }

    // ─────────────── ③ 锚点册子：十八枚锚点逐字在原行号、文件仍 456 行 ───────────────

    /**
     * 这一层最早是 T134 那条**红线**（`CourseManagementScreen.kt` 净改动 0 行）的可核形式，
     * 钉的是 T134 判「不动 main」之后**不许被顺手改动**的那几枚锚点。
     * T124a 起它不再是"零改动"：那一张卡把换色那一枚协程的接收者从页 scope 搬到
     * `viewModel.viewModelScope`（import +1 行、落点上方 +4 行注释、`:186` 那一行改字），
     * 本层随之重钉到现值。T124a⑤ 把方法名里那两句已经不成立的话改成实话：枚数不是十四（下面那把尺
     * 在本棵树上量出**十八枚**）、main 侧也不再零改动（那张卡真的动过这一页）⇒ 名字与本 KDoc 同一
     * 口径，不再留"真相只写在 KDoc 里、名字继续撒谎"那一层。⚠️ 改名会让这张卡的红名漂一次：
     * 旧名「管理页 main 侧零改动 十四枚锚点逐字在原行号」⇒ 新名就是下面那枚方法。
     * 枚数只是名字与这段说明的说辞，**断言里没有一枚钉它**（钉的是 `PAGE_LINES` 与逐字漂移），
     * 所以本轮只改名字与说明、锚点册子那十八行内容一字未动。复算这把尺（数册子那一段里
     * 「行号 to 原文」的行）：
     * `awk '/^        val ANCHORS = listOf\($/{f=1;next} f&&/^        \)$/{f=0} f' <本文件>`
     * `  | grep -c '^[[:space:]]*[0-9]\+ to '` ⇒ 18（第二条尺 `grep -cE '^ +[0-9]+ to ' <本文件>` 也给 18，
     * 两把都不靠行号 ⇒ 本 KDoc 不往里写会被插行推着走的行号）。
     * 判据仍是 `行号 → 逐字原文`：插一行、删一行、把某句挪一位，都会在这儿露出来
     * （docs 里 `docs/derived-field-audit.md` §9.1 #6 与 §9.2 #5 仍按旧行号钉，留给文档卡重钉）。
     */
    @Test
    fun `管理页锚点册子十八枚逐字在原行号 main侧已由T124a改字`() {
        val raw = readMainSource(PAGE)
        // 工作树是 CRLF：文件以换行收尾，split 出来最后一段是空串 —— 按"行"判要先把它摘掉，
        // 否则 456 行的那格会量成 457（这一格钉的就是枚数本身，不能靠 ±1 的宽容过）
        val lines = raw.split('\n').let { if (it.last().isBlank()) it.dropLast(1) else it }.map { it.trim() }
        assertEquals(
            "这一页今天仍是 ${PAGE_LINES} 行（T134 判「不动 main」、T124a 搬走那一枚协程的接收者后" +
                "本格重钉到现值；谁再插谁删都要先让这一格红、再把第 ①②⑤ 层的落点判据一起重钉）。\n" +
                "复算：wc -l app/src/main/java/$PAGE",
            PAGE_LINES,
            lines.size,
        )
        val drifted = ANCHORS.filter { (line, verbatim) ->
            line !in 1..lines.size || lines[line - 1] != verbatim
        }
        assertEquals(
            "锚点漂移 $drifted 处。复算单枚：awk 'NR==行号' app/src/main/java/$PAGE\n" +
                "每一枚都写在 docs/derived-field-audit.md 的账里（`:186`/`:188` 是 §9.1 #6 那两处起手块、" +
                "`:218`–`:225` 与 `:227` 是 §9.2 #5 那两道间接闸与那颗撤销）。\n" +
                "⚠️ 上面这几枚是 **T124a 之后的现值**：那一张卡把换色那一枚协程的接收者从页 scope 搬到 " +
                "`viewModel.viewModelScope`（import +1 行、落点上方 +4 行注释 ⇒ 181 起整体 +5），而 " +
                "docs/derived-field-audit.md §9.1 #6 / §9.2 #5 里仍写着 `:181`/`:183`/`:213`–`:220`/`:222` " +
                "那一套旧行号 ⇒ 文档那一侧留给文档卡重钉，本格先按盘面把锚钉住。谁再插一行、删一行、" +
                "把某句挪一位，都会在这儿露出来。" +
                "漂移的读法有两种，都得回来重判：① 有人在这一页改了文案/接线（那就是本卡判下来不该由它改）；" +
                "② 有人在这一页**之前**插了行（那会把全仓指向这一页的锚点一起顶穿）",
            emptyList<Pair<Int, String>>(),
            drifted,
        )
    }

    // ─────────────── ④ 反向格：:221 那一支的文案与那颗动作 label 不许被顺手改掉 ───────────────

    @Test
    fun `删组那一支的文案与那颗动作 label 一字未动 提示条不许整条删掉`() {
        val code = blankCommentsKeepingLiterals(readMainSource(PAGE))

        for (needle in listOf(
                GROUP_READ_VERDICT,
                GROUP_MESSAGE_EXPRESSION,
                GROUP_GATED_LABEL,
                GROUP_DURATION,
                GROUP_PERFORMED,
                GROUP_UNDO,
            )) {
            assertEquals(
                "T127 那一支的这一拍必须逐字一处（`$needle`）。\n" +
                    "复算：grep -Fn '$needle' app/src/main/java/$PAGE\n" +
                    "**本卡的红线就在这一格**：判「这一页不接 importMessage」不等于允许在这一页动删组提示条 —— " +
                    "文案两支、那颗 `actionLabel` 的闸、时长、点了才撤销那一判，一处都不许顺手改",
                1,
                occurrences(code, needle),
            )
        }
        assertEquals(
            "旧病那句**无条件**的撤销按钮不许回来（`actionLabel = \"撤销\",` 必须 0 处）：" +
                "本卡不动 main，也就顺手不动那颗闸 —— 它是 T127 的产物",
            0,
            occurrences(code, UNGATED_LABEL),
        )
        assertEquals(
            "这一页的渲染接线今天恰好一处 `showSnackbar`（`:221` 那一条）。" +
                "**朝宽扭这里红**：整条删掉 = 0 处（删掉的是唯一画得出来的那句）；" +
                "在这一页补一枚 bespoke 提示 = 2 处。\n" +
                "复算：grep -n 'showSnackbar(' app/src/main/java/$PAGE",
            1,
            occurrences(code, "snackbarHostState.showSnackbar("),
        )
        assertEquals(
            "`Scaffold(` / `SnackbarHost(` / `SnackbarHostState(` 各恰好一处：本卡没给这一页换载体",
            3,
            occurrences(code, "Scaffold(") + occurrences(code, "SnackbarHost(") +
                occurrences(code, "SnackbarHostState("),
        )
    }

    // ─────────────── ⑤ 朝窄格：那一支画得出的位置仍在确认框里 不许挪进空表分支 ───────────────

    @Test
    fun `那一条提示条画得出来的位置仍在那层确认框的 confirmButton 之内`() {
        val raw = readMainSource(PAGE)
        val code = blankCommentsKeepingLiterals(raw)

        val showHits = indexOfAll(code, "snackbarHostState.showSnackbar(")
        assertEquals(
            "先要只有那一处调用点才谈得上它的落点（第 ④ 层按枚数判，这一层按落点判）", 1, showHits.size,
        )
        val showAt = showHits.first()

        val confirmHits = indexOfAll(code, CONFIRM_ANCHOR)
        assertEquals("`confirmButton = {` 恰好一处（那层确认框的确认位）", 1, confirmHits.size)
        // 三枚锚点都以 `{` 收尾，所以开括号位 = 锚点起点 + 锚点长度 - 1
        val confirm = braceBodyFrom(code, confirmHits.first() + CONFIRM_ANCHOR.length - 1)
        val modalHits = indexOfAll(code, MODAL_ANCHOR)
        assertEquals("`ModalTransition(payload = pendingDelete) {` 恰好一处（确认框的载体）", 1, modalHits.size)
        val modal = braceBodyFrom(code, modalHits.first() + MODAL_ANCHOR.length - 1)

        assertTrue(
            "那一处必须落在 `confirmButton = {` 的体**之内**（今天 L${lineOf(raw, showAt)}，" +
                "confirmButton 体 L${lineOf(raw, confirm.openBrace)}–L${lineOf(raw, confirm.closeBrace)}）：" +
                "**朝窄扭这里红** —— 把它挪进 `if (isEmpty)` 那一支、或挪出 `ModalTransition` 之外、" +
                "或挪进 `Crossfade` 的 lambda，枚数仍然对、文案仍然逐字对，而「删一整组」这件事" +
                "只在列表非空时才可能发生 ⇒ 那句话永远画不出来（「枚数对但落点错」那一洞的正面堵法）",
            showAt in (confirm.openBrace + 1) until confirm.closeBrace,
        )
        assertTrue(
            "`confirmButton` 那一段本身必须仍在那层 `ModalTransition(payload = pendingDelete)` 之内" +
                "（今天 L${lineOf(raw, confirm.openBrace)} 落在 L${lineOf(raw, modal.openBrace)}–" +
                "L${lineOf(raw, modal.closeBrace)} 之间）：载体挪走 ⇒ 提示条就长在了一棵不组合的树上",
            confirm.openBrace in (modal.openBrace + 1) until modal.closeBrace,
        )
        val emptyHits = indexOfAll(code, EMPTY_BRANCH_ANCHOR)
        assertEquals("`if (isEmpty) {` 那一支恰好一处（Crossfade 的空态分支）", 1, emptyHits.size)
        val empty = braceBodyFrom(code, emptyHits.first() + EMPTY_BRANCH_ANCHOR.length - 1)
        assertTrue(
            "那一处**不许**落在空态分支里（今天空态体是 L${lineOf(raw, empty.openBrace)}–" +
                "L${lineOf(raw, empty.closeBrace)}）：那一支只在「一门课也没有」时组合，" +
                "而删一整组的前提正是有课 ⇒ 挪进去等于把提示条画在用户永远走不到的一帧里",
            showAt !in (empty.openBrace + 1) until empty.closeBrace,
        )
        // 那一处必须**直接**躺在 scope.launch 的体里：再被 if / when 包一层就成了条件渲染
        val launchHits = indexOfAll(code, LAUNCH_ANCHOR).filter {
            it in (confirm.openBrace + 1) until confirm.closeBrace
        }
        assertEquals(
            "`confirmButton` 之内那趟 `scope.launch {` 恰好一处（`:219`）—— 它是那一句唯一的宿主",
            1,
            launchHits.size,
        )
        val launchOpen = launchHits.first() + LAUNCH_ANCHOR.length - 1
        assertEquals(
            "`showSnackbar` 那一条必须**直接**落在 `scope.launch` 的体里（相对那枚花括号的深度恰好 1），" +
                "实测深度 ${depthWithin(code, launchOpen, showAt)}、落在 L${lineOf(raw, showAt)}。" +
                "**朝窄扭这里红**：把它整个包进 `if (groups.isEmpty()) { … }` 之类的一层（在这一页有课才删得动组，" +
                "那一档恒假），深度就成 2 —— 文案逐字对、`showSnackbar` 枚数对、连 confirmButton 的包含关系都对，" +
                "而那句话永远不出现（「枚数对但落点错」那一洞的第二道堵法）",
            1,
            depthWithin(code, launchOpen, showAt),
        )
        // 次序：起协程 → 读删除结论 → 才念那一句（同一趟 scope.launch 之内，`:219`–`:229`）
        val verdictAt = code.indexOf(GROUP_READ_VERDICT)
        val launchAt = code.lastIndexOf(LAUNCH_ANCHOR, verdictAt)
        val undoAt = code.indexOf(GROUP_UNDO)
        assertTrue(
            "四拍次序：起协程 → 读删除结论 → 念那一句 → 有人点了那颗按钮才撤销。今天落在 " +
                "L${lineOf(raw, launchAt)} / L${lineOf(raw, verdictAt)} / L${lineOf(raw, showAt)} / " +
                "L${lineOf(raw, undoAt)}：「念排在读之前」就是无条件那句谎（T127 的病本体，本卡不许复现）",
            launchAt in 0 until verdictAt && verdictAt in 0 until showAt && showAt in 0 until undoAt,
        )
    }

    // ─────────────── ⑥ 真正的账在 VM 的对外形状：撤销那一族压根没有可分文案的信号 ───────────────

    /**
     * 本卡判「真正的账在别处」这一判的**证据本体**。它同时是回执里那两条的落点：
     * - **为什么「按 `updateCourse` 返回值分文案」不算收口**：返回值 `Long?` 只说成没成，
     *   `e.message` 只长在 VM 那一枚 `AppMessage("更新课程失败：…")` 里；照返回值分文案 = 把
     *   T127 §9.2 #5 那把尺（两支读**同一枚结论**）搬到一枚**没有闸的对象**上（返回值今天被丢掉、
     *   也没有第二句「为什么」），收的是「有没有 id」而不是「用户看不看得见那句原因」。
     * - **这一页哪几族真的没人念**：换色（`:187`）与撤销（`:227`）两族，逐枚原文写在第 ① 层那五枚写点里。
     */
    @Test
    fun `撤销那一族在 UI 侧没有可分文案的信号 这笔账在 VM 的对外形状上`() {
        val code = blankCommentsKeepingLiterals(readMainSource(PAGE))
        val vm = blankCommentsKeepingLiterals(readMainSource(SCHEDULE_VIEW_MODEL))

        assertEquals(
            "撤销仍走旧 alias、且返回 `Unit`（`fun undoDeleteCourse() = undo()` 恰好一处）。" +
                "**这一格是本卡判「不动 main」的根据**：这一页那颗按钮点下去之后拿不到任何结论，" +
                "VM 那三句（没有可撤销 / 已撤销 / 撤销失败）是这一族**唯一**的出口。" +
                "本卡红线不许改 VM ⇒ 只能登记，不许在这一页凑一句「大概撤销成功了」。\n" +
                "复算：grep -n 'fun undoDeleteCourse' app/src/main/java/$SCHEDULE_VIEW_MODEL",
            1,
            occurrences(vm, VM_UNDO_ALIAS),
        )
        assertEquals(
            "不许给撤销链长出可分文案的返回值（`fun undo(): Boolean` / `fun undoDeleteCourse(): Boolean` 全 0 处）：" +
                "那是**改 VM 对外形状**那一档（它被一批守卫按源码文本钉着、docs 里几十枚锚点指向它），" +
                "要收这一族得单独开一张卡，连同 UndoManager/撤销身份那一族（T123）一起判",
            0,
            occurrences(vm, "fun undo(): Boolean") + occurrences(vm, "fun undoDeleteCourse(): Boolean"),
        )
        assertEquals(
            "`fun undo() {` 仍无参（一处）：栈里捞哪一笔归 T123，本卡不提前动它",
            1,
            occurrences(vm, "fun undo() {"),
        )
        val bareUndo = code.lineSequence().map { it.trim() }.count { it.startsWith("viewModel.undoDeleteCourse()") }
        val bareUpdate = code.lineSequence().map { it.trim() }.count { it.startsWith("viewModel.updateCourse(") }
        assertEquals(
            "这一页那两枚**裸语句**调用各恰好一处（整行以它开头、没有接收者）：" +
                "`viewModel.undoDeleteCourse()` 与 `viewModel.updateCourse(` —— 这就是「只写 importMessage、" +
                "不返回给调用点」那一族在这一页的两枚入口（换色 `:187` / 撤销 `:227`）。\n" +
                "复算：grep -n 'viewModel.updateCourse(\\|viewModel.undoDeleteCourse()' app/src/main/java/$PAGE\n" +
                "**这格红了要回来重判的是本卡的判词**：谁把它们改成 UI 当场可读（接返回值自念一句、" +
                "或 VM 分档回话），这一页就不再需要渲染 `importMessage`，第 ①② 层那两层要跟着翻面",
            2,
            bareUndo + bareUpdate,
        )
        assertEquals(
            "`suspend fun updateCourse(…): Long? =` 签名未改（一处）：它交回的是「最终落库行 id 或 null」，" +
                "**不是原因** ⇒ 按它分文案收不掉这一族（判词写在上面那格的 KDoc）",
            1,
            occurrences(vm, VM_UPDATE_SIGNATURE),
        )
    }

    // ─────────────── ⑦ 红线：不许在这一页为这笔账长出 bespoke 载体 ───────────────

    @Test
    fun `这一页不许为这笔账长出第二条载体 也不许把陈账当已收`() {
        val code = blankCommentsKeepingLiterals(readMainSource(PAGE))

        for (needle in listOf(
                "Toast",                      // 页内即时反馈统一走 Snackbar（本仓 R7 ⑥ 的口径）
                "importMessage?.let",         // 常驻横幅：ImportScreen :392 / SettingsScreen :705 那一型，不自清
                "LaunchedEffect",             // 在这一页加 effect = 打算落地渲染接线，必须连第 ①②③⑤ 层重判
                "clearImportMessage",         // 这一页不许伸手吃那枚全局 StateFlow
                "showMessage(",               // VM 的写入口，UI 侧借它绕开册子判据也算越界
            )) {
            assertEquals(
                "`$needle` 在这一页必须 0 处：本卡判「不动 main」，也就判「不许在这一页为这笔账长出任何别的载体」。" +
                    "红了只有两种读法：① 有人拿另一条通道把 VM 的句子搬到这一页（toast / 常驻横幅 / 页内 effect / " +
                    "自己去清那枚流），那既没收到真正的账（VM 对外形状）、又把第 ② 层那枚「谁吃掉 StateFlow」" +
                    "的账搅浑；② 当真按本卡的收口方向改了 VM 的对外形状 —— 那要另开一张卡，" +
                    "本卡的判据与 T127 类头那笔残账一起重钉。" +
                    "\n复算：grep -n '$needle' app/src/main/java/$PAGE",
                0,
                occurrences(code, needle),
            )
        }
    }

    // ---- 源码核对小工具（抄 CourseGroupDeletionWiringGuardTest，同一族口径）----

    /** 一枚 `{`…`}` 的体：开括号位、闭括号位、中间那段 */
    private class Extracted(val body: String, val openBrace: Int, val closeBrace: Int)

    /** 从已知开括号位 [open] 起按花括号配平（跳过字符串与字符字面量）取出那一段体 */
    private fun braceBodyFrom(source: String, open: Int): Extracted {
        var depth = 0
        var index = open
        var inString = false
        var escaped = false
        while (index < source.length) {
            val c = source[index]
            when {
                inString -> when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }

                c == '"' -> inString = true
                c == '\'' -> {
                    var j = index + 2
                    if (index + 1 < source.length && source[index + 1] == '\\') j = index + 3
                    while (j < source.length && source[j] != '\'') j++
                    check(j < source.length) { "字符字面量没闭合：花括号配平会跑偏" }
                    index = j
                }

                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) return Extracted(source.substring(open + 1, index), open, index)
                }
            }
            index++
        }
        throw IllegalStateException("花括号没配平：从偏移 $open 起切不出一段体")
    }

    /**
     * 相对开括号位 [open] 的嵌套深度：直接落在那个体里 = 1，被 `if` / `when` / lambda 再包一层 = 2。
     * 字符串与字符字面量整个跳过（文案里的 `${…}` 那对花括号会被配平器读成两层）。
     */
    private fun depthWithin(source: String, open: Int, position: Int): Int {
        var depth = 0
        var index = open
        while (index < position) {
            when (val c = source[index]) {
                '"', '\'' -> {
                    var j = index + 1
                    while (j < source.length && source[j] != c) {
                        j += if (source[j] == '\\') 2 else 1
                    }
                    check(j < source.length) { "字面量没闭合：深度会跑偏" }
                    index = j
                }

                '{' -> depth++
                '}' -> depth--
            }
            index++
        }
        return depth
    }

    /** 那一处落在原文的第几行（行号一律用 `${x + 1}` 模板，绝不写 `"L" + x + 1` —— 左结合会拼成 L19021） */
    private fun lineOf(rawSource: String, position: Int): Int =
        if (position < 0) -1 else rawSource.substring(0, position).count { it == '\n' } + 1

    /** 全仓 main 源码：文件名 → 内容（钉册子要扫全部，不是数出现次数） */
    private fun readAllMainSources(): Map<String, String> {
        val root = File(findMainJavaDir(), "com/buaa/schedule")
        assertTrue("找不到 $root：包结构挪过家，本守卫要跟着改", root.isDirectory)
        val files = root.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.toList()
        val out = linkedMapOf<String, String>()
        files.forEach {
            check(!out.containsKey(it.name)) { "main 源码里有同名文件 ${it.name}，按文件名建册子会读错份" }
            out[it.name] = it.readText()
        }
        assertEquals("扫 main 源码的文件数与建出来的册子条目数不等", files.size, out.size)
        assertTrue("扫 main 源码扫出 0 份文件：$root", out.isNotEmpty())
        return out
    }

    private fun indexOfAll(haystack: String, needle: String): List<Int> {
        val hits = mutableListOf<Int>()
        var from = 0
        while (true) {
            val at = haystack.indexOf(needle, from)
            if (at < 0) return hits
            hits += at
            from = at + needle.length
        }
    }

    private fun occurrences(haystack: String, needle: String): Int = indexOfAll(haystack, needle).size

    private fun readMainSource(relativeFromMainJava: String): String {
        val file = File(findMainJavaDir(), relativeFromMainJava)
        assertTrue("找不到 ${file.path}：文件挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText()
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

    /**
     * **只抹注释、保留字符串字面量内容**，长度与换行位置不变（行号因此仍然可信）。
     *
     * 本文件尤其需要这一步：类头与各条失败消息里**抄了这些字面量本身**（`importMessage`、
     * `showSnackbar(message.text)`、`actionLabel = "撤销",` 都在账里）。不抹注释的话第 ②④⑦ 层
     * 那几枚「必须 0 处」的格子会数出凭空的第二处 —— 那枚 0 就是假的。
     */
    private fun blankCommentsKeepingLiterals(source: String): String {
        val out = source.toCharArray()
        var i = 0
        while (i < out.size) {
            when {
                source.startsWith("//", i) -> {
                    val nl = source.indexOf('\n', i).let { if (it < 0) out.size else it }
                    for (k in i until nl) out[k] = ' '
                    i = nl
                }

                source.startsWith("/*", i) -> {
                    var depth = 1
                    var j = i + 2
                    while (j < out.size && depth > 0) {
                        when {
                            source.startsWith("/*", j) -> { depth++; j += 2 }
                            source.startsWith("*/", j) -> { depth--; j += 2 }
                            else -> j++
                        }
                    }
                    for (k in i until j.coerceAtMost(out.size)) if (out[k] != '\n') out[k] = ' '
                    i = j
                }

                source.startsWith("\"\"\"", i) -> {
                    i = source.indexOf("\"\"\"", i + 3).let { if (it < 0) out.size else it + 3 }
                }

                out[i] == '"' || out[i] == '\'' -> {
                    val quote = out[i]
                    var j = i + 1
                    while (j < out.size) {
                        when {
                            source[j] == '\\' -> j += 2
                            source[j] == quote -> { j++; break }
                            source[j] == '\n' -> break
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

    private companion object {
        // 文件（相对 app/src/main/java）
        const val PAGE = "com/buaa/schedule/ui/course/CourseManagementScreen.kt"
        const val SCHEDULE_VIEW_MODEL = "com/buaa/schedule/ui/ScheduleViewModel.kt"
        const val COURSE_MANAGEMENT_NAME = "CourseManagementScreen.kt"
        const val HOME_SCREEN_NAME = "HomeScreen.kt"
        const val IMPORT_SCREEN_NAME = "ImportScreen.kt"
        const val SETTINGS_SCREEN_NAME = "SettingsScreen.kt"

        const val READER_NEEDLE = "viewModel.importMessage.collectAsState()"
        const val UNGATED_LABEL = "actionLabel = \"撤销\","
        const val CONFIRM_ANCHOR = "confirmButton = {"
        const val MODAL_ANCHOR = "ModalTransition(payload = pendingDelete) {"
        const val EMPTY_BRANCH_ANCHOR = "if (isEmpty) {"
        const val LAUNCH_ANCHOR = "scope.launch {"

        // ① VM 写给「这一页能触发的动作」的那五句（删除课程失败一枚在单课 :602、一枚在整组 :655）
        val VM_POINTS_FOR_THIS_PAGE = mapOf(
            "AppMessage(\"更新课程失败：\${e.message}\", isError = true)" to 1,
            "AppMessage(\"删除课程失败：\${e.message}\", isError = true)" to 2,
            "AppMessage(\"没有可撤销的操作\")" to 1,
            "AppMessage(\"已撤销：\${last.label}\")" to 1,
            "AppMessage(\"撤销失败：\${it.message}\", isError = true)" to 1,
        )
        const val VM_UNDO_ALIAS = "fun undoDeleteCourse() = undo()"
        const val VM_UPDATE_SIGNATURE =
            "suspend fun updateCourse(course: Course, options: CourseSaveOptions = CourseSaveOptions()): Long? ="

        // ④ T127 那一支（本卡一字不许动）
        // T124b（B 档）：调用名换成 ...AndAwait，**仍在 :220 那一行**（改名不插行 ⇒ 十八枚锚点的行号没漂）
        const val GROUP_READ_VERDICT = "val deleted = viewModel.deleteCourseGroupAndAwait(target.fragments)"
        const val GROUP_MESSAGE_EXPRESSION =
            "message = if (deleted) \"已删除「\${target.displayName}」\" " +
                "else \"删除失败：\${target.displayName} 还在课表里\","
        const val GROUP_GATED_LABEL = "actionLabel = \"撤销\".takeIf { deleted },"
        const val GROUP_DURATION = "duration = SnackbarDuration.Long,"
        const val GROUP_PERFORMED = "if (result == SnackbarResult.ActionPerformed) {"
        const val GROUP_UNDO = "viewModel.undoDeleteCourse()"

        // ③ 这一页的锚点册子（行号 → 逐字，docs §9.1 #6 / §9.2 #5 钉的就是这几枚）
        // T124a 在这一页搬走了一枚协程的接收者：import +1 行、那一枚落点上方 +4 行注释、
        // `:181` 那一行由 `scope.launch {` 改成 `viewModel.viewModelScope.launch {` ⇒ 181 起整体 +5。
        const val PAGE_LINES = 456
        val ANCHORS = listOf(
            99 to "val state by viewModel.uiState.collectAsState()",
            105 to "val snackbarHostState = remember { SnackbarHostState() }",
            109 to "Scaffold(",
            110 to "snackbarHost = { SnackbarHost(snackbarHostState) },",
            186 to "viewModel.viewModelScope.launch {",
            187 to "viewModel.updateCourse(",
            188 to "primary.copy(colorIndex = index, customColorArgb = null),",
            207 to "ModalTransition(payload = pendingDelete) { group, modal ->",
            215 to "confirmButton = {",
            218 to "pendingDelete = null",
            219 to "scope.launch {",
            220 to GROUP_READ_VERDICT,
            221 to "val result = snackbarHostState.showSnackbar(",
            222 to GROUP_MESSAGE_EXPRESSION,
            223 to GROUP_GATED_LABEL,
            224 to GROUP_DURATION,
            226 to GROUP_PERFORMED,
            227 to GROUP_UNDO,
        )
    }
}
