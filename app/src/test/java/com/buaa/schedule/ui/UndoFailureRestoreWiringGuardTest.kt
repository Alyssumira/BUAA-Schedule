package com.buaa.schedule.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T183③：接线守卫 —— 钉住「撤销写库失败那一步，真的把条目还回栈顶，而文案跟着这句实话走」。
 *
 * ## 为什么这一层不是装饰
 *
 * 判据内核（`UndoFailureRecovery.kt`）与栈语义（`UndoManager.restoreTop`）各自有单测钉着，
 * 但本仓登记过的洞正是**"内核给得出正确结论"与"调用点真的读了它"是两件事**
 * （同 `CourseDeletionWiringGuardTest` 段首那一笔）。这一族更其明显：
 * 少写一句 `UndoManager.restoreTop(last)`，编译过、单测全绿、真机上表现与改前**一模一样**
 * —— 条目照样被吃掉，而那句「这条撤销还留着」就成了当面的谎。
 *
 * ## 七层，全部按落点 / 次序 / 逐字取判据
 *
 * 1. [失败那支里真的回推 三道闸逐字在一句里] —— `undo()` 的 `onFailure` 块体内必须出现回推动作，
 *    且被「落库没成」与「这一档该留」两道闸一起闸住；回推排在写文案**之前**（文案读的是返回值）。
 * 2. [回推只闸在落库那一手上 刷 UI 那一拍抛了不许回推] —— `applyUndo` → 旗标 → `afterDataChangedInternal()`
 *    三拍次序；旗标只在 VM 体内出现两次（声明 + 置真）。
 * 3. [栈那头的票据闸门] —— `lastPopped` 的立起 / 三处作废 / 引用相等比对 / 回推体里不许有驱逐语句。
 * 4. [调用点册子 枚数与落点] —— 全仓 main 扫：回推点、类名链取法、文案装配点各落在哪几份文件。
 * 5. [内核零 android 零时钟 那张表与那句半句的落点] —— 纯度 + `heldSuffix` 只在类体一处拼装（本仓派生字段口径）。
 * 6. [既有的两把尺没被搬走] —— `pop()` 仍捞栈顶、`UndoEntry` 仍是两枚字段且全仓只有一处构造点（T123 没被提前动）。
 * 7. [报错文案册子 撤销那五句仍在原位] —— 与 `CourseManagementImportMessageWiringGuardTest` 第 ① 层同一把尺，
 *    这里多钉一枚：回推成功那支的文案**只许由内核装配**（不许在 VM 里现拼一句「还留着」）。
 *
 * 只做**源码核对**（JVM，无 Robolectric、无设备、不读时钟）。
 * ⚠️ 它钉的是"接线形状"，**不等于** SQLite 真失败之后条目回得来那一档（零设备红线，见回执未验到清单）。
 * 输入面：本守卫读 `src/main/java` 下的工作树文本，已由 `app/build.gradle.kts` 里
 * `guardReadWorkingTreeFiles` 第 ① 项整体声明成 `testDebugUnitTest` 的输入 ⇒ 不必新增条目。
 */
class UndoFailureRestoreWiringGuardTest {

    // ─────────────── ① 失败那一支里真的回推，而且回推排在文案之前 ───────────────

    @Test
    fun `失败那支里真的把条目还回栈顶 三道闸逐字在一句里`() {
        val raw = readMainSource(SCHEDULE_VIEW_MODEL)
        val code = blankCommentsKeepingLiterals(raw)
        val undo = functionBody(code, VM_UNDO_SIGNATURE, "ScheduleViewModel.undo()")
        val failure = onFailureBody(undo.body, "undo() 的 onFailure")

        assertEquals(
            "`onFailure` 块体内必须出现回推动作 `$RESTORE_CALL`（恰好一处，实到 " +
                occurrences(failure, RESTORE_CALL) + " 处）：这一句摘掉，编译照过、单测全绿，" +
                "而真机上条目仍然被吃掉 —— 本卡的病本体就活在这一句上",
            1,
            occurrences(failure, RESTORE_CALL),
        )
        assertEquals(
            "回推那一句必须**三道闸逐字连在一行**（`$GATE`）：\n" +
                "  少 `!writtenToDb` ⇒ 落库成功而刷 UI 抛了也回推 ⇒ 同一条撤销点两回、同一门课插回库里两遍；\n" +
                "  少 `kind.restoreEntry` ⇒ 撞约束那种永远失败的条目压在栈顶，下面所有条目一起撤不回来；\n" +
                "  少 `UndoManager.restoreTop(last)` ⇒ 病本体回来。\n" +
                "实到 " + occurrences(failure, GATE) + " 处",
            1,
            occurrences(failure, GATE),
        )
        assertEquals(
            "`onFailure` 块体里碰栈只许碰这一回（`UndoManager.` 恰好一处，实到 " +
                occurrences(failure, "UndoManager.") + " 处）：别处再动栈（自己 removeLast、自己 push 一份副本）" +
                "就绕过了栈那头那三道票据闸门",
            1,
            occurrences(failure, "UndoManager."),
        )
        // 落点：档名由内核造，实参是**类名链**而不是异常本身
        assertEquals(
            "调用点必须把异常换成**类名链**再交给内核（`$KIND_CALL` 恰好一处）：内核零 android import，" +
                "把 Throwable 直接递进去就得在内核里 import android.database.* —— 本仓那条规矩的反面",
            1,
            occurrences(failure, KIND_CALL),
        )
        assertEquals(
            "不许把 Throwable 原样递给内核（`undoFailureKindOf(it)` 0 处，实到 " +
                occurrences(failure, "undoFailureKindOf(it)") + " 处）",
            0,
            occurrences(failure, "undoFailureKindOf(it)"),
        )
        // 次序：回推 → 才写文案（文案读的是回推的返回值，反了就是先许诺后干活）
        val gateAt = failure.indexOf(GATE)
        val copyAt = failure.indexOf(HELD_COPY_BRANCH)
        assertTrue(
            "回推必须排在文案那一拍**之前**（那一支的 `if (held)` 读的就是它的返回值）：" +
                "\n  回推 L${lineOf(raw, undo.openBrace + 1 + gateAt)} / " +
                "文案分支 L${lineOf(raw, undo.openBrace + 1 + copyAt)}",
            gateAt in 0 until copyAt,
        )
        assertEquals(
            "文案必须**由回推的返回值分两支**（`$HELD_IF` 恰好一处）：拿常量 `true`、或把「还留着」" +
                "写进无条件那一支，就是本卡红线不许的那种承诺（实到 " + occurrences(failure, HELD_IF) + " 处）",
            1,
            occurrences(failure, HELD_IF),
        )
        assertEquals(
            "回推成功那一支的整句只许由内核装配（`$HELD_COPY_CALL` 恰好一处，实到 " +
                occurrences(failure, HELD_COPY_CALL) + " 处）",
            1,
            occurrences(failure, HELD_COPY_CALL),
        )
        assertEquals(
            "VM 里不许现拼那半句「还留着」（`kind.heldSuffix` 0 处，实到 " +
                occurrences(undo.body, "kind.heldSuffix") + " 处）：装配点只有内核类体那一处，" +
                "两处各拼一句就是文案与判据分家的开始",
            0,
            occurrences(undo.body, "kind.heldSuffix"),
        )
        assertEquals(
            "没回推那一支必须仍用改前那一句逐字（`$OLD_COPY` 恰好一处）：那句不宣称条目留着，" +
                "所以不构成谎；而它同时被 `CourseManagementImportMessageWiringGuardTest` 第 ① 层按枚数钉着",
            1,
            occurrences(undo.body, OLD_COPY),
        )
    }

    // ─────────────── ② 回推只闸在落库那一手上 ───────────────

    @Test
    fun `回推只闸在落库那一手上 刷UI那一拍抛了不许把条目再放回去`() {
        val raw = readMainSource(SCHEDULE_VIEW_MODEL)
        val code = blankCommentsKeepingLiterals(raw)
        val undo = functionBody(code, VM_UNDO_SIGNATURE, "ScheduleViewModel.undo()")
        val block = functionBody(undo.body, SUSPEND_BLOCK, "undo() 里的 suspendCatching")

        assertEquals(
            "落库那一手必须仍在 `suspendCatching` 块内恰好一处（`${REPO_APPLY}`）",
            1,
            occurrences(block.body, REPO_APPLY),
        )
        assertEquals(
            "旗标的声明与置真各恰好一处（`$WRITE_FLAG_INIT` / `$WRITE_FLAG_SET`）：实到 " +
                occurrences(undo.body, WRITE_FLAG_INIT) + " 与 " + occurrences(undo.body, WRITE_FLAG_SET) + " 处。" +
                "置真那一枚被摘掉 ⇒ 回推恒被 `!writtenToDb` 挡住 = 本卡白做；" +
                "多出一枚（比如把旗标挪进 onFailure）⇒ 闸就空转了",
            listOf(1, 1),
            listOf(occurrences(undo.body, WRITE_FLAG_INIT), occurrences(undo.body, WRITE_FLAG_SET)),
        )
        val applyAt = block.body.indexOf(REPO_APPLY)
        val flagAt = block.body.indexOf(WRITE_FLAG_SET)
        val refreshAt = block.body.indexOf(VM_REFRESH)
        assertTrue(
            "块内三拍的次序只能是：落库 → 立旗 → 刷 UI。旗标排在落库之前 = 只要进了块就算成功（闸空转）；" +
                "排在刷 UI 之后 = 刷 UI 抛了旗标还是 false，于是把已经改成那一版的库又撤销一遍：" +
                "\n  落库 L${lineOf(raw, undo.openBrace + 1 + block.openBrace + 1 + applyAt)} / " +
                "立旗 L${lineOf(raw, undo.openBrace + 1 + block.openBrace + 1 + flagAt)} / " +
                "刷 UI L${lineOf(raw, undo.openBrace + 1 + block.openBrace + 1 + refreshAt)}",
            applyAt in 0 until flagAt && flagAt in 0 until refreshAt,
        )
        assertEquals(
            "`onFailure` 那一支全文件恰好一处（回推只有一个入口，别处再兜一层就没人知道条目归谁）",
            1,
            occurrences(undo.body, ON_FAILURE_ANCHOR),
        )
        assertTrue(
            "pop 仍排在 `viewModelScope.launch` **之前**（本卡选的是「失败回推」那一版，不是「落成才消费」那一版；" +
                "后者要让条目在写库期间仍躺在栈顶上，双点那颗按钮就会把同一条 applyUndo 跑两遍）：" +
                "\n  pop L${lineOf(raw, undo.openBrace + 1 + undo.body.indexOf(POP_CALL))} / " +
                "launch L${lineOf(raw, undo.openBrace + 1 + undo.body.indexOf(LAUNCH))}",
            undo.body.indexOf(POP_CALL) in 0 until undo.body.indexOf(LAUNCH),
        )
    }

    // ─────────────── ③ 栈那一头的票据闸门 ───────────────

    @Test
    fun `栈那一头的票据闸门三处作废 按引用比 回推体里不许有驱逐语句`() {
        val undoManager = blankCommentsKeepingLiterals(readMainSource(UNDO_MANAGER))

        assertEquals(
            "票据本体 `private var lastPopped: UndoEntry? = null` 恰好一处（它是 restoreTop 唯一的凭据）",
            1,
            occurrences(undoManager, TICKET_FIELD),
        )
        val popLine = indexOfAll(undoManager, POP_LINE)
        assertEquals(
            "`pop()` 必须把摘走的那一枚**立成票据**，而且整行逐字在册（`$POP_LINE` 恰好一处，实到 " +
                popLine.size + " 处）：少了 `.also { lastPopped = it }` 那一截，restoreTop 那道闸永远读不到凭据" +
                "（回推恒 false ⇒ 症状与改前一模一样，而门禁全绿）；" +
                "把它换成两行（先 removeLastOrNull 再赋值） ⇒ 本卡第 ⑥ 层那枚「pop 仍捞栈顶」的尺与" +
                "UndoUpdateEntryGuardTest 第 ⑤ 层要一起重判",
            1,
            popLine.size,
        )
        val restore = functionBody(undoManager, RESTORE_SIGNATURE, "UndoManager.restoreTop()")
        assertEquals(
            "回推必须**按引用**比票据（`$TICKET_COMPARE` 恰好一处，实到 " +
                occurrences(restore.body, TICKET_COMPARE) + " 处）。换成结构相等 `==` 就通了：条目是 data class，" +
                "「同一份快照的另一枚实例」也会被认成本次摘走的那一条 ⇒ 双份进栈",
            1,
            occurrences(restore.body, TICKET_COMPARE),
        )
        assertEquals(
            "回推成功必须**顺手抹掉票据**（`$TICKET_VOID` 在 restoreTop 体内恰好一处）：不抹的话" +
                "同一枚条目能被回推两回，栈里躺着两份同一份快照",
            1,
            occurrences(restore.body, TICKET_VOID),
        )
        assertEquals(
            "回推只放回栈顶，不许在 restoreTop 里再补一枚驱逐语句（`removeFirst` 0 处，实到 " +
                occurrences(restore.body, "removeFirst") + " 处）：票据保证它永远有位置，" +
                "而多一处 `stack.removeFirst()` 会把 UndoUpdateEntryGuardTest 第 ⑤ 层那枚「驱逐语句恰好一处」顶掉",
            0,
            occurrences(restore.body, "removeFirst"),
        )
        val push = functionBody(undoManager, "fun push(action: UndoAction, label: String) {", "UndoManager.push()")
        val clear = functionBody(undoManager, "fun clear() {", "UndoManager.clear()")
        assertEquals(
            "push 体内必须作废票据（`$TICKET_VOID` 一处）：那之后栈顶归新那条操作，旧条目硬按回顶上就是改乱顺序",
            1,
            occurrences(push.body, TICKET_VOID),
        )
        assertEquals(
            "clear 体内也必须作废票据（`$TICKET_VOID` 一处）：`ScheduleRepository` 那四枚清栈落点的理由" +
                "就是「快照指向的行已经不属于当前这张表」，回推得跟着这条账",
            1,
            occurrences(clear.body, TICKET_VOID),
        )
        assertEquals(
            "`lastPopped` 在栈那一头恰好出现 " + TICKET_USES + " 次（声明 / pop 立票 / push 作废 / " +
                "clear 作废 / restoreTop 比对 / restoreTop 抹票）：多一处 = 又长出一条不经这三道闸的读法",
            TICKET_USES,
            occurrences(undoManager, "lastPopped"),
        )
    }

    // ─────────────── ④ 调用点册子：枚数 + 落点 ───────────────

    @Test
    fun `回推点 类名链取法 文案装配点 全仓枚数与落点都要对得上`() {
        val byFile = readAllMainSources().mapValues { (_, text) -> blankCommentsKeepingLiterals(text) }

        assertEquals(
            "`UndoManager.restoreTop(` 的调用点全仓恰好一处（就在 `undo()` 的 onFailure 里）。" +
                "长出第二处 = 又有一条链把撤销条目往栈里塞，而本守卫只钉住了原来那一处的三道闸：" +
                filesHint(byFile, RESTORE_CALL_SITE),
            listOf(SCHEDULE_VIEW_MODEL_NAME),
            byFile.filter { occurrences(it.value, RESTORE_CALL_SITE) > 0 }.keys.toList(),
        )
        assertEquals(
            "`UndoManager.pop()` 的调用点全仓恰好一处（还是 `undo()` 那一枚；本卡没新增消费点）：" +
                filesHint(byFile, POP_CALL_SITE),
            listOf(SCHEDULE_VIEW_MODEL_NAME),
            byFile.filter { occurrences(it.value, POP_CALL_SITE) > 0 }.keys.toList(),
        )
        assertEquals(
            "内核那三枚入口（判档 / 取类名链 / 装配文案）只许落在两份文件里：内核自己 + VM 那一枚调用点。" +
                "长出第三处落点 = 有人在别处自己判一次「该不该留」或自己拼一句失败话术" +
                "（判据与文案就此分家）：" + filesHint(byFile, KERNEL_API_NEEDLE),
            listOf(SCHEDULE_VIEW_MODEL_NAME, UNDO_KERNEL_NAME),
            byFile.filter { occurrences(it.value, KERNEL_API_NEEDLE) > 0 }.keys.sorted(),
        )
        assertEquals(
            "`undoFailureKindOf(` 全仓恰好两枚（内核定义一枚 + VM 调用一枚）：多一枚就是又有一条链判档",
            2,
            byFile.values.sumOf { occurrences(it, "undoFailureKindOf(") },
        )
        assertEquals(
            "`exceptionClassNamesOf(` 全仓恰好两枚（定义 + 调用）：这条链的取法只许有一份",
            2,
            byFile.values.sumOf { occurrences(it, "exceptionClassNamesOf(") },
        )
        assertEquals(
            "`copyForRestored(` 全仓恰好两枚（类体定义 + VM 调用）：装配点一份、消费点一份",
            2,
            byFile.values.sumOf { occurrences(it, "copyForRestored(") },
        )
        assertEquals(
            "「撤销失败：」这句前缀在 main 里恰好两份字面量（VM 那枚旧句 + 内核装配那枚）；" +
                "第三处 = 有人在别处又拼了一句失败话术，两把尺都会分叉：" + filesHint(byFile, FAILURE_PREFIX),
            listOf(SCHEDULE_VIEW_MODEL_NAME, UNDO_KERNEL_NAME),
            byFile.filter { occurrences(it.value, FAILURE_PREFIX) > 0 }.keys.sorted(),
        )
        assertEquals(
            "「这条撤销还留着」那半句在 main 里恰好 " + HELD_PHRASE_TABLE_ROWS + " 枚（内核那张表上回推那三档各一枚），" +
                "实到 " + byFile.values.sumOf { occurrences(it, HELD_PHRASE) } + " 枚；" +
                "第四枚 = 有人在表外私自许诺「还留着」",
            HELD_PHRASE_TABLE_ROWS,
            byFile.values.sumOf { occurrences(it, HELD_PHRASE) },
        )
        assertEquals(
            "那半句的落点恰好内核一份文件：VM 里再出现一处就是「文案与判据分家」那一型的复发（第 ① 层" +
                "那一格钉的是同一件事的调用点侧）：" + filesHint(byFile, HELD_PHRASE),
            listOf(UNDO_KERNEL_NAME),
            byFile.filter { occurrences(it.value, HELD_PHRASE) > 0 }.keys.sorted(),
        )
        assertEquals(
            "撤销那两枚 UI 入口仍恰好两枚（首页提示条 `viewModel.undo()` / 管理页 `viewModel.undoDeleteCourse()`）：" +
                "回推这一族对两枚入口一视同仁，长出第三枚入口要连第 ①② 层一起重判",
            listOf(SCHEDULE_VIEW_MODEL_NAME),
            byFile.filter { occurrences(it.value, VM_UNDO_ALIAS) > 0 }.keys.toList(),
        )
    }

    // ─────────────── ⑤ 内核纯度与表的形状 ───────────────

    @Test
    fun `判据内核零import零时钟 那张表与那半句都只有一份落点`() {
        val raw = readMainSource(UNDO_KERNEL)
        val code = blankCommentsKeepingLiterals(raw)

        val imports = raw.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("import ") }
            .toList()
        assertEquals(
            "判据内核连**一枚 import 都没有**（零 android / 零 androidx / 零 java.time，运行期事实全部当参数递进来）。" +
                "实到：$imports",
            emptyList<String>(),
            imports,
        )
        val clockReads = CLOCK_LITERALS.filter { code.contains(it) }
        assertEquals(
            "判据内核里出现了读当下 / 读设备的写法 $clockReads —— 内核自己读就与调用点的时序脱钩了",
            emptyList<String>(),
            clockReads,
        )
        assertEquals(
            "档名本体 `internal enum class UndoFailureKind(` 全仓恰好一处",
            listOf(UNDO_KERNEL_NAME),
            readAllMainSources()
                .mapValues { (_, text) -> blankCommentsKeepingLiterals(text) }
                .filter { occurrences(it.value, ENUM_SIGNATURE) > 0 }.keys.toList(),
        )
        assertEquals(
            "四档各给三列（回推 / 许诺 / 那半句）：每枚赋值逐字在册，$TABLE_ROWS 行表 + 榜上三枚。" +
                "少一枚 = 那一列在某一档上没人管了（文案与判据分家的入口）",
            listOf(4, 4, 4, TABLE_ROWS),
            listOf(
                occurrences(code, "restoreEntry ="),
                occurrences(code, "retryInvitation ="),
                occurrences(code, "heldSuffix ="),
                occurrences(code, " to UndoFailureKind."),
            ),
        )
        assertEquals(
            "榜（`UNDO_FAILURE_PRIORITY`）体内恰好三枚档（`    UndoFailureKind.` 那三行）：多出第四档 = " +
                "兜底档被写进了榜（它不是某一档），少一枚 = 某一档不再参与取序、静默落兜底",
            3,
            occurrences(code, PRIORITY_ROW),
        )
        assertEquals(
            "那半句的装配点只许类体那一处（`$COPY_ASSEMBLY` 恰好一处）：这是本仓派生落点规矩——" +
                "值 = f(同表参数) 就长在类体里，不给它在调用点二次拼装的机会",
            1,
            occurrences(code, COPY_ASSEMBLY),
        )
        assertEquals(
            "`heldSuffix` 在内核里恰好 " + HELD_SUFFIX_USES + " 处（声明 1 + 四档赋值 4 + 类体拼装 1）：" +
                "多一处就是有人绕过 copyForRestored 直接拿半句去拼",
            HELD_SUFFIX_USES,
            occurrences(code, "heldSuffix"),
        )
        assertEquals(
            "链读取上限 `names.size < CLASS_CHAIN_DEPTH` 恰好一处（两道防爆里的那一枚硬上限）",
            1,
            occurrences(code, DEPTH_GUARD),
        )
    }

    // ─────────────── ⑥ 既有的两把尺没被本卡搬走 ───────────────

    @Test
    fun `pop仍捞栈顶 条目仍是两枚字段 构造点仍只有一处`() {
        val undoManager = blankCommentsKeepingLiterals(readMainSource(UNDO_MANAGER))

        assertEquals(
            "`pop()` 仍捞**栈顶**（`$POP_TOP` 那一行原样，末尾只是接了 `.also { lastPopped = it }`）：" +
                "本卡没动它的签名与语义。这一枚同时被 `UndoUpdateEntryGuardTest` 第 ⑤ 层钉着，" +
                "谁把 pop 换成按身份捞，两把尺一起要重判",
            1,
            occurrences(undoManager, POP_TOP),
        )
        assertEquals(
            "`UndoEntry` 仍是 `(action, label)` 两枚字段：本卡**没有**给条目添身份/代数那一枚字段" +
                "（回推凭的是栈那头的票据，跨表派生字段那条规矩因此不必配 copy 站点守卫）。" +
                "长出第三枚字段就是 T123 那一半被提前动了，要连 `UndoUpdateEntryGuardTest` 一起重判",
            2,
            occurrences(entryParameterBlock(undoManager), "val "),
        )
        assertEquals(
            "条目的构造点全仓恰好一处（`UndoEntry(` 只在 `push` 体内）：多一处 = 有一条路能造出不带票据身份的条目",
            listOf(UNDO_MANAGER_NAME),
            readAllMainSources()
                .mapValues { (_, text) -> blankCommentsKeepingLiterals(text) }
                .filter { occurrences(it.value, "UndoEntry(") > 0 }.keys.toList(),
        )
        assertEquals(
            "`@Synchronized` 恰好 " + SYNCHRONIZED_ROWS + " 枚（push / pushDelete / pushDeleteGroup / pushCreate / " +
                "pushUpdate / pop / restoreTop / clear）：本卡新增的那一手必须与栈的其它操作同锁，" +
                "少一枚 = 票据在锁外被读（回推与 push 竞态）；多一枚 = 又长了一条动栈的公开入口，要连第 ③ 层重判",
            SYNCHRONIZED_ROWS,
            occurrences(undoManager, "@Synchronized"),
        )
        assertEquals(
            "条目里不许出现 `copy(` （回推放回的是**同一枚实例**，不是它的副本）",
            0,
            occurrences(undoManager, ".copy("),
        )
    }

    // ─────────────── ⑦ 撤销那五句仍在原位 ───────────────

    /**
     * 与 `CourseManagementImportMessageWiringGuardTest` 第 ① 层同一把尺，多钉一枚：
     * 回推成功那支的文案**只能由内核装配**（那一族的枚数册子在这里也钉一遍，
     * 因为本卡动的正是这五句所在的那一枚函数体）。
     */
    @Test
    fun `撤销那三句仍在原位 而回推那支的文案不在VM里现拼`() {
        val code = blankCommentsKeepingLiterals(readMainSource(SCHEDULE_VIEW_MODEL))
        listOf(NOTHING_TO_UNDO, UNDO_DONE, OLD_COPY, VM_UNDO_ALIAS).forEach { needle ->
            assertEquals(
                "撤销那一族原来那几句仍逐字在原位、恰好一处（`$needle`）：本卡只**加**了回推那支，" +
                    "没有改走返回值的对外形状（那是那一族守卫判下来的红线）—— 实到 " +
                    occurrences(code, needle) + " 处",
                1,
                occurrences(code, needle),
            )
        }
        assertEquals(
            "VM 里不许出现「还留着」那半句的字面量（它属于内核那张表，实到 " +
                occurrences(code, HELD_PHRASE) + " 处）",
            0,
            occurrences(code, HELD_PHRASE),
        )
        assertEquals(
            "`fun undo(): Boolean` 那种可分文案的返回值仍然 0 处（本卡按红线没动撤销的对外形状）",
            0,
            occurrences(code, "fun undo(): Boolean") + occurrences(code, "fun undoDeleteCourse(): Boolean"),
        )
    }

    // ─────────────── 源码核对小工具（照抄 CourseDeletionWiringGuardTest 那一族）───────────────

    /** 一枚 `{`…`}` 的体：开括号位、闭括号位、中间那段 */
    private class Extracted(val body: String, val openBrace: Int, val closeBrace: Int)

    /** 从 [signature] 起的那枚函数：取签名之后第一个 `{` 到与它配平的那个 `}` 之间那一段 */
    private fun functionBody(source: String, signature: String, who: String): Extracted {
        val hits = indexOfAll(source, signature)
        assertEquals("签名 `$signature` 在 $who 里应当恰好一处，实际 ${hits.size} 处", 1, hits.size)
        val open = source.indexOf('{', hits.first())
        check(open > 0) { "$who 的签名之后找不到 `{`：函数体换了写法，本守卫要跟着改" }
        return braceBodyFrom(source, open)
    }

    /** `undo()` 里那一枚 `} .onFailure {` 的体（回推那一支的全部判据都落在这里） */
    private fun onFailureBody(undoBody: String, who: String): String {
        val hits = indexOfAll(undoBody, ON_FAILURE_ANCHOR)
        assertEquals("$who 里 `$ON_FAILURE_ANCHOR` 应当恰好一处，实际 ${hits.size} 处", 1, hits.size)
        val brace = undoBody.indexOf('{', hits.first() + 1)
        check(undoBody[hits.first() + ON_FAILURE_ANCHOR.length - 1] == '{') { "onFailure 那锚点末尾不是花括号" }
        return braceBodyFrom(undoBody, brace).body
    }

    /** 从已知开括号位 [open] 起按花括号配平（跳过字符串字面量）取出那一段体 */
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
                    // 字符字面量整个跳过去：`'{'` / `'}'` 混进配平里就是假红/假绿两不沾的第三态
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
        throw IllegalStateException("花括号没配平：从偏移 $open 起切不出那一段体")
    }

    /** `data class UndoEntry(` 的构造参数表那一段（比字段枚数用） */
    private fun entryParameterBlock(source: String): String {
        val start = source.indexOf(ENTRY_SIGNATURE)
        check(start >= 0) { "找不到 $ENTRY_SIGNATURE：条目换了写法，本守卫要跟着改" }
        val open = source.indexOf('(', start)
        val close = source.indexOf(')', open)
        check(close > open) { "UndoEntry 的参数表切不出来" }
        return source.substring(open, close)
    }

    /** 那一处落在原文的第几行（失败消息要能把人带到那一处，只报个数等于没有守卫） */
    private fun lineOf(rawSource: String, position: Int): Int =
        if (position < 0) -1 else rawSource.substring(0, position).count { it == '\n' } + 1

    private fun filesHint(byFile: Map<String, String>, needle: String): String =
        byFile.filter { occurrences(it.value, needle) > 0 }
            .entries
            .joinToString(separator = "") { "  ${it.key}: ${occurrences(it.value, needle)} 处\n" }

    /** 全仓 main 源码：文件名 → 内容（扫全部是为了"不许长出第二处落点"） */
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
     * 本守卫尤其需要这一步：`UndoManager.kt` 与 `UndoFailureRecovery.kt` 的段首 KDoc 里
     * **抄了这些锚点本身**（`UndoManager.pop()`、`stack.removeLastOrNull()`、`撤销失败：`、
     * `lastPopped`、`heldSuffix` 都在那笔账里）。不抹注释的话第 ④⑤ 层那些枚数册子会数出凭空的
     * 第二处、第三处 —— 那正是"枚数对但落点错"的反面：枚数本身就是假的。
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
        // 文件
        const val SCHEDULE_VIEW_MODEL = "com/buaa/schedule/ui/ScheduleViewModel.kt"
        const val UNDO_MANAGER = "com/buaa/schedule/data/undo/UndoManager.kt"
        const val UNDO_KERNEL = "com/buaa/schedule/ui/UndoFailureRecovery.kt"
        const val SCHEDULE_VIEW_MODEL_NAME = "ScheduleViewModel.kt"
        const val UNDO_MANAGER_NAME = "UndoManager.kt"
        const val UNDO_KERNEL_NAME = "UndoFailureRecovery.kt"

        // ① VM 的失败那支
        const val VM_UNDO_SIGNATURE = "fun undo() {"
        const val POP_CALL = "UndoManager.pop()"
        const val LAUNCH = "viewModelScope.launch {"
        const val ON_FAILURE_ANCHOR = ".onFailure {"
        const val RESTORE_CALL = "UndoManager.restoreTop(last)"
        const val GATE = "!writtenToDb && kind.restoreEntry && UndoManager.restoreTop(last)"
        const val KIND_CALL = "undoFailureKindOf(exceptionClassNamesOf(it))"
        const val HELD_IF = "_importMessage.value = if (held) {"
        const val HELD_COPY_BRANCH = "if (held) {"
        const val HELD_COPY_CALL = "kind.copyForRestored(it.message)"
        const val OLD_COPY = "AppMessage(\"撤销失败：\${it.message}\", isError = true)"

        // ② 落库那一手与旗标
        const val SUSPEND_BLOCK = "suspendCatching {"
        const val REPO_APPLY = "repository.applyUndo(last.action)"
        const val WRITE_FLAG_INIT = "var writtenToDb = false"
        const val WRITE_FLAG_SET = "writtenToDb = true"
        const val VM_REFRESH = "afterDataChangedInternal()"

        // ③ 栈那一头
        const val POP_TOP = "fun pop(): UndoEntry? = stack.removeLastOrNull()"
        const val POP_LINE = "fun pop(): UndoEntry? = stack.removeLastOrNull().also { lastPopped = it }"
        const val RESTORE_SIGNATURE = "fun restoreTop(entry: UndoEntry): Boolean {"
        const val TICKET_FIELD = "private var lastPopped: UndoEntry? = null"
        const val TICKET_COMPARE = "if (lastPopped !== entry) return false"
        const val TICKET_VOID = "lastPopped = null"
        const val TICKET_USES = 6

        // ④ 册子
        const val RESTORE_CALL_SITE = "UndoManager.restoreTop("
        const val POP_CALL_SITE = "UndoManager.pop()"
        const val KERNEL_API_NEEDLE = "undoFailureKindOf("
        const val FAILURE_PREFIX = "\"撤销失败："
        const val HELD_PHRASE = "这条撤销还留着"
        const val HELD_PHRASE_TABLE_ROWS = 3
        const val VM_UNDO_ALIAS = "fun undoDeleteCourse() = undo()"
        const val NOTHING_TO_UNDO = "AppMessage(\"没有可撤销的操作\")"
        const val UNDO_DONE = "AppMessage(\"已撤销：\${last.label}\")"

        // ⑤⑥ 内核与条目
        const val ENUM_SIGNATURE = "internal enum class UndoFailureKind("
        const val TABLE_ROWS = 12
        const val PRIORITY_ROW = "\n    UndoFailureKind."
        const val COPY_ASSEMBLY = "return \"撤销失败：\$cause\$heldSuffix\""
        const val HELD_SUFFIX_USES = 6
        const val DEPTH_GUARD = "names.size < CLASS_CHAIN_DEPTH"
        const val ENTRY_SIGNATURE = "data class UndoEntry("
        const val SYNCHRONIZED_ROWS = 8

        val CLOCK_LITERALS = listOf(
            "System.currentTimeMillis", "System.nanoTime", "java.time", "LocalDate", "LocalTime",
            "LocalDateTime", "Calendar", "Date(", "Clock", ".now(", "TimeZone", "Build.",
            "getSystemService", "LocalContext", "displayMetrics",
        )
    }
}
