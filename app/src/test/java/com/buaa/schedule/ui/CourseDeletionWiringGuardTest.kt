package com.buaa.schedule.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T121③b：接线守卫 —— 钉住「删没删到」这一判**真的被用上了**，而不只是躺在内核里。
 *
 * 本卡的形状改了 `repository.deleteCourse` 的返回类型
 * （`List<ReminderSetting>` → `CourseDeletion`）。本仓有过一次真回归就是这么合进 master 的：
 * **签名/字段改了、拷贝站点没人赋值，而门禁全绿因为没有一枚测试走那条路**。
 * `deleteCourse` 这条链改前正是零覆盖（`grep -rn deleteCourse app/src/test` 与
 * `app/src/androidTest` 双双空手），所以这一层不是装饰：判据内核给得出正确结论，
 * 跟"VM 那一步真的读了它"是两件事，后者只有源码核对能钉。
 *
 * 五层，全部**逐处 / 按位置 / 逐字**取判据（"枚数对但落点错"是本仓已登记的洞）：
 * 1. [仓储层那一步按读回来的行本身判 且删不到就什么都不动] —— 行从 `courseDao.getById` 读回来、
 *    结论由内核造、**删行动作被结论闸住**（不许再出现裸的 `deleteCourseRow(course)`）。
 * 2. [VM 那一步真的读结论 判没删到就不报成功也不压栈] —— 四拍的**次序**：删库 → 判没删到就早退 →
 *    才压栈 → 才刷 UI；且块里的 Boolean 被 `.fold({ it }, …)` 原样端出去（不是旧那句 `.fold({ true }, …)`）。
 * 3. [调用点册子 仓储层一处VM层两处 枚数与落点都要对得上] —— 全仓 main 扫，枚数 + 落在哪几份文件。
 * 4. [首页那一支…]/[编辑器那一支…] —— 一枚布尔两张嘴：一处管提示条文案与那颗「撤销」按钮，
 *    一处管**要不要退出编辑页**。这两处源码本卡一字未改，改了要回来重判。
 * 5. [判据内核零android零时钟 结论只有内核一处能造] —— 内核纯度与"唯一构造点"。
 *
 * 只做**源码核对**（JVM，无 Robolectric、无设备、不读时钟）。
 * ⚠️ 它钉的是"接线形状"，**不等于** SQLite 上真删一行成功/失败那一档 —— 那一档本卡没做（零设备红线）。
 */
class CourseDeletionWiringGuardTest {

    // ─────────────── ① 仓储层：行本身才算数，删行动作被结论闸住 ───────────────

    @Test
    fun `仓储层那一步按读回来的行本身判 且删不到就什么都不动`() {
        val raw = readMainSource(SCHEDULE_REPOSITORY)
        val code = blankCommentsKeepingLiterals(raw)
        val locked = functionBody(code, REPO_SIGNATURE, "ScheduleRepository.deleteCourse")
        val txExtracted = functionBody(locked.body, REPO_TRANSACTION, "deleteCourse 的事务体")
        // 不 trim：体里的偏移与整份文件的位置一一对应（trim 会把开头的换行吃掉，行号就漂了）
        val tx = txExtracted.body
        val txBase = locked.openBrace + 1 + txExtracted.openBrace + 1

        assertEquals(
            "仓储层的删除必须在事务内把那一行读回来（`listOfNotNull(courseDao.getById(…))`）——" +
                "旧实现根本不读行，于是返回值里只剩提醒，调用点无从知道“那一行到底存不存在”。" +
                "这一处消失 = 存在性的证据又断了；出现两回 = 一趟删除读两回，要重判哪一份才算数",
            1,
            occurrences(tx, REPO_READ_ROW_BACK),
        )
        assertEquals(
            "结论必须由内核造（`val deletion = courseDeletionOf(rows, reminders)`）：" +
                "绕过它就是调用点自己判一次“删没删到”，判据本体就不再只有一份了",
            1,
            occurrences(tx, REPO_VERDICT),
        )
        assertEquals(
            "提醒要按**读回来的那一行**去取（`rows.mapNotNull { reminderDao.getByCourse(it.id)`），" +
                "不按调用方手里那份对象的 id：id 复用那一档（孤儿提醒撞上下一次复用的 id）" +
                "拿手里那份当键会清错行",
            1,
            occurrences(tx, REPO_REMINDERS_BY_FOUND_ROW),
        )
        assertEquals(
            "删行动作必须被结论闸住（`if (deletion.removedAnything) deleteCourseRow(rows.first())`）：" +
                "没读到行就整个事务什么都不动 —— 与组删除 `deleteCourseGroup` 的 `rows.isEmpty()` 早退同形。" +
                "这一处变了说明“删不到也要顺手清一把提醒”又回来了，而那份清理撤销不了",
            1,
            occurrences(tx, REPO_GATED_DELETE),
        )
        assertEquals(
            "不许再出现裸的 `deleteCourseRow(course)`：删的是库里那一行，不是调用方手里那份" +
                "可能已经过期的对象（phantom Delete 复活时插回库里的就是那份内容）",
            0,
            occurrences(tx, "deleteCourseRow(course)"),
        )
        assertTrue(
            "事务体最后端出去的必须就是那份结论（`deletion`），而不是旧实现那句提醒表 —— " +
                "端提醒表就等于把“存不存在”这件事又交回给调用点猜",
            tx.trim().endsWith("deletion"),
        )
        // 逐处取次序，不是数出现次数
        val readAt = tx.indexOf(REPO_READ_ROW_BACK)
        val reminderAt = tx.indexOf(REPO_REMINDERS_BY_FOUND_ROW)
        val verdictAt = tx.indexOf(REPO_VERDICT)
        val deleteAt = tx.indexOf(REPO_GATED_DELETE)
        assertTrue(
            "仓储层四拍的次序应当是：读回行 → 按那一行取提醒 → 内核造结论 → 闸住的删行。" +
                "漂了任何一步都要重判（例如“先删行再判”就等于判据不参与写库）：" +
                "\n  读回行 L${lineOf(raw, txBase + readAt)} / 取提醒 L${lineOf(raw, txBase + reminderAt)} / " +
                "造结论 L${lineOf(raw, txBase + verdictAt)} / 闸住删行 L${lineOf(raw, txBase + deleteAt)}",
            readAt in 0 until reminderAt && reminderAt in 0 until verdictAt && verdictAt in 0 until deleteAt,
        )
    }

    // ─────────────── ② VM：真的读了结论，没删到就早退且不压栈 ───────────────

    @Test
    fun `VM 那一步真的读结论 判没删到就不报成功也不压栈`() {
        val raw = readMainSource(SCHEDULE_VIEW_MODEL)
        val code = blankCommentsKeepingLiterals(raw)
        val extracted = functionBody(code, VM_SIGNATURE, "ScheduleViewModel.deleteCourse")
        val body = extracted.body

        assertEquals(
            "VM 那一步必须仍然只经一次仓储层删除（`repository.deleteCourse(course)`）",
            1,
            occurrences(body, VM_CALLS_REPOSITORY),
        )
        assertEquals(
            "VM 必须读结论里那一枚“删到了的行”，并在它为空时早退" +
                "（`val removed = deletion.removedCourse ?: return@suspendCatching false`）：" +
                "这一处消失或改写，就是回到“不抛异常即报 true”那个缺陷本体",
            1,
            occurrences(body, VM_EARLY_EXIT),
        )
        assertEquals(
            "压栈必须排在那道早退**之后**，且压的是**读回来的那一行**" +
                "（`UndoManager.pushDelete(removed, deletion.removedReminders)`）：" +
                "压 `course`（调用方手里那份）等于把过期对象交给撤销去重插；" +
                "压栈排到早退之前就是那条 phantom Delete 本身",
            1,
            occurrences(body, VM_PUSH_FOUND_ROW),
        )
        assertEquals(
            "不许再按调用方手里那份对象压栈（`pushDelete(course,`）",
            0,
            occurrences(body, "pushDelete(course,"),
        )
        val callAt = body.indexOf(VM_CALLS_REPOSITORY)
        val exitAt = body.indexOf(VM_EARLY_EXIT)
        val pushAt = body.indexOf(VM_PUSH_FOUND_ROW)
        val refreshAt = body.indexOf(VM_REFRESH)
        assertTrue(
            "VM 四拍的次序应当是：删库 → 判没删到就早退 → 才压栈 → 才刷 UI。" +
                "“先删库、成功才压栈”这条不变量一旦倒过来，删除失败会留下一条悬空撤销记录；" +
                "而“压栈排在早退之前”留下的是一条 phantom 记录 —— 那一次真正的删除被它压在栈底：" +
                "\n  删库 L${lineOf(raw, extracted.openBrace + 1 + callAt)} / " +
                "早退 L${lineOf(raw, extracted.openBrace + 1 + exitAt)} / " +
                "压栈 L${lineOf(raw, extracted.openBrace + 1 + pushAt)} / " +
                "刷 UI L${lineOf(raw, extracted.openBrace + 1 + refreshAt)}",
            callAt in 0 until exitAt && exitAt in 0 until pushAt && pushAt in 0 until refreshAt,
        )
        assertTrue(
            "块里那个 Boolean 必须被 `.fold({ it }, …)` 原样端出去。回到旧那句 `.fold({ true }, …)`" +
                "等于把早退那一拍作废：仓储层判“没删到”，VM 仍然对用户报 true —— 本卡修的正是这一句谎",
            code.startsWith(VM_FOLD_PASSTHROUGH, extracted.closeBrace),
        )
    }

    // ─────────────── ③ 调用点册子：枚数 + 落点 ───────────────

    /**
     * 形状一改要跟着改的就是这几处。**枚数与落点一起钉**：只数枚数的话，有人把仓储层那一处
     * 挪去别的文件、再在别处补一枚同名调用，守卫照样绿（本仓登记过这一洞）。
     */
    @Test
    fun `调用点册子 仓储层一处VM层两处 枚数与落点都要对得上`() {
        val byFile = readAllMainSources().mapValues { (_, text) -> blankCommentsKeepingLiterals(text) }

        assertEquals(
            "`repository.deleteCourse(` 的调用点全仓恰好一处（本卡改返回类型时要跟着改的就是它，" +
                "KDoc 里那笔“唯一调用点”的账记的就是它）。**多一处** = 又有调用点拿到这份结论，" +
                "要按第 ② 层那套“读结论 → 早退 → 才压栈”重钉一次；**少一处** = 这条链换了入口，" +
                "账要重算：" + filesHint(byFile, REPO_CALL_SITE),
            listOf(SCHEDULE_VIEW_MODEL_NAME),
            byFile.filter { occurrences(it.value, REPO_CALL_SITE) > 0 }.keys.toList(),
        )
        assertEquals(
            "仓储层那一处所在的表达式必须还是 `val deletion = repository.deleteCourse(course)`：" +
                "换成 `val removedReminders = …`（旧写法）说明有人把结论又降级成了提醒表",
            1,
            occurrences(byFile.getValue(SCHEDULE_VIEW_MODEL_NAME), VM_CALLS_REPOSITORY),
        )
        assertEquals(
            "`viewModel.deleteCourse(` 的调用点全仓恰好两处：**编辑器**（MainActivity 的 onDelete）与" +
                "**首页长按菜单**（HomeScreen 的 handleCourseDelete）。这枚函数的签名没变、返回值语义变了" +
                "（“不抛异常” → “真的删到了那一行”），两处消费方各拿它做什么由第 ④ 层钉：" +
                filesHint(byFile, VM_CALL_SITE),
            listOf(MAIN_ACTIVITY_NAME, HOME_SCREEN_NAME).sorted(),
            byFile.filter { occurrences(it.value, VM_CALL_SITE) > 0 }.keys.toList().sorted(),
        )
        assertEquals(
            "`CourseDeletion.Removed(` 全仓只许出现在内核里一处（`courseDeletionOf` 之内）：" +
                "别处也能造这个结论，就等于能在判据之外自行宣布“删到了”，phantom 那条路重新通：" +
                filesHint(byFile, VERDICT_CONSTRUCTION),
            listOf(POLICY_NAME),
            byFile.filter { occurrences(it.value, VERDICT_CONSTRUCTION) > 0 }.keys.toList(),
        )
        assertEquals(
            "全仓压“单行删除”这条撤销记录的入口 `UndoManager.pushDelete(` 恰好一处。" +
                "长出第二处 = 又多一条路径能往栈里塞 Delete，而本守卫只钉住了原来那一处的次序：" +
                filesHint(byFile, "UndoManager.pushDelete("),
            listOf(SCHEDULE_VIEW_MODEL_NAME),
            byFile.filter { occurrences(it.value, "UndoManager.pushDelete(") > 0 }.keys.toList(),
        )
    }

    // ─────────────── ④ 两处消费方：一枚布尔两张嘴 ───────────────

    @Test
    fun `首页那一支仍按这枚布尔决定文案与撤销按钮 两处同读一个变量`() {
        val raw = readMainSource(HOME_SCREEN)
        val code = blankCommentsKeepingLiterals(raw)
        val hits = indexOfAll(code, HOME_DELETE_CALL)
        assertEquals(
            "首页的 `val deleted = viewModel.deleteCourse(course)` 应当恰好一处（长按菜单那条链）",
            1,
            hits.size,
        )
        val launch = scopeLaunchBody(code, hits.first())
        assertTrue(
            "提示条文案必须仍由这枚布尔分两支（「已删除「…」」/「删除失败：… 还在课表里」）。" +
                "删不到走的就是既有那句“还在课表里” —— 本卡按红线没造新文案，改措辞要回来重判",
            launch.contains(HOME_IF_DELETED) && launch.contains(HOME_STILL_THERE_COPY),
        )
        assertEquals(
            "`actionLabel = \"撤销\".takeIf { deleted }` 必须还在且恰好一处：那颗「撤销」按钮在不在" +
                "**只**由这枚布尔决定。删不到时它不许长出来 —— 长出来就是邀请用户去撤销一条 phantom 记录",
            1,
            occurrences(launch, HOME_UNDO_BUTTON),
        )
        assertTrue(
            "两处消费方都必须排在 `viewModel.deleteCourse(course)` 之后（同一个 launch 块里读同一枚 " +
                "`deleted`）：顺序倒了就是在读上一次删除的结果",
            launch.indexOf(HOME_DELETE_CALL) < launch.indexOf(HOME_IF_DELETED) &&
                launch.indexOf(HOME_IF_DELETED) < launch.indexOf(HOME_UNDO_BUTTON),
        )
        assertEquals(
            "成功那支的文案「已删除「…」」原文也应当恰好一处（改前改后一字未动 —— 本卡按红线没造新文案）",
            1,
            occurrences(launch, HOME_DELETED_COPY),
        )
        assertEquals(
            "那条提示条上的「撤销」仍然走 `viewModel.undo()`（无参、捞栈顶）。本卡按红线没动这一族：" +
                "先删 A、再删 B、再点 A 那条提示条的「撤销」会捞到 B —— 那是 `undo()` 无参另有其账" +
                "（已在收单报告里登记给编排者）。谁把它改成按笔撤销，这里要跟着重钉",
            1,
            occurrences(launch, HOME_UNDO_CALL),
        )
    }

    @Test
    fun `编辑器那一支拿同一枚布尔决定要不要退出编辑页 那条 else 从此走得通`() {
        val raw = readMainSource(COURSE_EDITOR_SCREEN)
        val code = blankCommentsKeepingLiterals(raw)
        assertEquals(
            "编辑器入口的签名 `onDelete: suspend (Course) -> Boolean,` 必须还在：MainActivity 那个" +
                "`onDelete = { viewModel.deleteCourse(it) }` 就是按它接的。改成 Unit 就等于把" +
                "“删没删到”这条真相从编辑页拿掉",
            1,
            occurrences(code, EDITOR_PARAM_TYPE),
        )
        val gates = indexOfAll(code, EDITOR_IF_ON_DELETE)
        assertEquals(
            "`if (onDelete(target)) {` 恰好一处（删除确认框那颗按钮）：它决定的是**要不要退出编辑页**，" +
                "不是提示条文案 —— 这就是卡面问的“有没有别处拿返回值当“这课存在过”的证据去做别的决定”，" +
                "答案是“有，两处”，本枚是第二处",
            1,
            gates.size,
        )
        val launch = scopeLaunchBody(code, gates.first())
        assertEquals(
            "那条 else 支路（`saveError = \"删除失败，请重试\"`）**改形状以前从来没走过**（不抛异常就是 true），" +
                "修完以后它会走：删不到 ⇒ 留在编辑页亮既有那句红条，随后 MainActivity 的 courseMissing " +
                "闸门把目标行已不在屏上的编辑页弹出栈。这句话术本卡没改；要改成别的口径（或直接 onBack）" +
                "都得回来重判这一格",
            1,
            occurrences(launch, EDITOR_ERROR_COPY),
        )
        assertTrue(
            "`onBack()` 必须仍被那道 `if` 闸着（无条件退出 = 编辑器不再读这枚布尔）",
            launch.contains(EDITOR_ON_BACK),
        )
        assertEquals(
            "MainActivity 那处 `onDelete = { viewModel.deleteCourse(it) }` 也应当恰好一处",
            1,
            occurrences(blankCommentsKeepingLiterals(readMainSource(MAIN_ACTIVITY)), MAIN_ACTIVITY_ON_DELETE),
        )
    }

    // ─────────────── ⑤ 内核纯度：判据只有一份，且不在内核里读设备 ───────────────

    @Test
    fun `判据内核零android零时钟 结论只有内核一处能造`() {
        val raw = readMainSource(POLICY)
        val code = blankCommentsKeepingLiterals(raw)

        val frameworkImports = raw.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("import android.") || it.startsWith("import androidx.") }
            .toList()
        assertEquals(
            "判据内核里出现了 android/androidx 的 import —— 这一族的规矩是纯判据：设备/权限/度量" +
                "一律由调用点量好了当参数递进来。$frameworkImports",
            emptyList<String>(),
            frameworkImports,
        )
        val clockReads = CLOCK_LITERALS.filter { code.contains(it) }
        assertEquals(
            "判据内核里出现了读当下/读设备的写法 $clockReads —— 内核自己读就与调用点的时序脱钩了" +
                "（T41/T43 那一类坑）。要判哪一档，就把那一秒、那个 SDK_INT 当参数传进来",
            emptyList<String>(),
            clockReads,
        )
        assertEquals(
            "判据本体 `internal fun deletionRemovedSomething(` 全仓恰好一份",
            1,
            occurrences(code, KERNEL_PREDICATE),
        )
        val predicateStart = code.indexOf(KERNEL_PREDICATE)
        val nextDeclaration = code.indexOf(KERNEL_NEXT_FUNCTION, predicateStart)
        assertTrue(
            "判据本体与下一枚 `courseDeletionOf` 都应当找得着（切不出那一句就算没有守卫）",
            predicateStart >= 0 && nextDeclaration > predicateStart,
        )
        val bodyMark = code.indexOf(PREDICATE_RETURN_ARROW, predicateStart)
        assertTrue(
            "判据本体应当是 `): Boolean = …` 那一句：换了写法（表达式体改块体、改了返回类型）要重钉",
            bodyMark in predicateStart until nextDeclaration,
        )
        val predicateBody = code.substring(bodyMark, nextDeclaration)
        assertEquals(
            "判据本体只许读“事务内读回来的行”。`reminderCount` 声明了却不参与判定是**故意**的" +
                "（把“空提醒 ≠ 没删到”钉在格子表里）；它一旦出现在体里，就是判据开始拿提醒当" +
                "存在的证据 —— 本卡缺陷的成因那一族",
            0,
            occurrences(predicateBody, "reminderCount"),
        )
        assertEquals(
            "判据那一句 `foundRows.isNotEmpty()` 只许一处：多一处就是长出了第二把尺子",
            1,
            occurrences(code, KERNEL_PREDICATE_BODY),
        )
        assertEquals(
            "结论的读法 `val removedAnything: Boolean` 只许定义一处（接口上那一句投影）。" +
                "两处以上 = 有人在别处另判一次“删没删到”",
            1,
            occurrences(code, KERNEL_PROJECTION),
        )
        assertEquals(
            "仓储层的 `deleteCourse` 不许退回旧签名 `List<ReminderSetting>` —— 那份表只装得下提醒，" +
                "装不下“那一行到底存不存在”，本卡的病就是这么来的",
            0,
            occurrences(
                blankCommentsKeepingLiterals(readMainSource(SCHEDULE_REPOSITORY)),
                REPO_OLD_SIGNATURE,
            ),
        )
    }

    // ---- 源码核对小工具（抄 CourseEditorSaveErrorClearPairingGuardTest / CalendarSyncDiffClearPairingGuardTest）----

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
        throw IllegalStateException("花括号没配平：从偏移 $open 起切不出一段体")
    }

    /** 从 [hit] 往前找包围它的那次 `scope.launch {`，再按花括号配平取出那一段体 */
    private fun scopeLaunchBody(source: String, hit: Int): String {
        val anchor = source.lastIndexOf(LAUNCH_ANCHOR, hit)
        check(anchor >= 0) {
            "那一处往前找不到包围它的 scope.launch {：这段操作换了载体（不在协程里了？），本守卫要跟着改"
        }
        val brace = anchor + LAUNCH_ANCHOR.length - 1
        check(source[brace] == '{') { "`scope.launch {` 那锚点末尾不是花括号：写法变了" }
        val extracted = braceBodyFrom(source, brace)
        check(hit in extracted.openBrace + 1..extracted.closeBrace) { "取出的 scope.launch 体不含那一处：配平跑偏了" }
        return extracted.body
    }

    /** 那一处落在原文的第几行（失败消息要能把人带到那一处，只报个数等于没有守卫） */
    private fun lineOf(rawSource: String, position: Int): Int =
        if (position < 0) -1 else rawSource.substring(0, position).count { it == '\n' } + 1

    private fun filesHint(byFile: Map<String, String>, needle: String): String =
        byFile.filter { occurrences(it.value, needle) > 0 }
            .entries
            .joinToString(separator = "") { "  ${it.key}: ${occurrences(it.value, needle)} 处\n" }

    /** 全仓 main 源码：文件名 → 内容（扫全部是为了“不许长出第三处”，本卡参与的只有五份） */
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
     * 本卡尤其需要这一步：`CourseDeletionPolicy.kt` 的段首 KDoc 里**抄了这些字面量本身**
     * （`repository.deleteCourse(course)`、`viewModel.deleteCourse(it)`、`还在课表里`、
     * `actionLabel = "撤销".takeIf { deleted }` 都在那笔调用点账里）。不抹注释的话
     * 第 ③ 层那几枚册子会数出凭空的第三处、第四处 —— 那正是"枚数对但落点错"的反面：
     * 枚数本身就是假的。
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
        const val SCHEDULE_REPOSITORY = "com/buaa/schedule/data/repository/ScheduleRepository.kt"
        const val SCHEDULE_VIEW_MODEL = "com/buaa/schedule/ui/ScheduleViewModel.kt"
        const val POLICY = "com/buaa/schedule/data/repository/CourseDeletionPolicy.kt"
        const val HOME_SCREEN = "com/buaa/schedule/ui/home/HomeScreen.kt"
        const val MAIN_ACTIVITY = "com/buaa/schedule/MainActivity.kt"
        const val COURSE_EDITOR_SCREEN = "com/buaa/schedule/ui/editor/CourseEditorScreen.kt"
        const val SCHEDULE_VIEW_MODEL_NAME = "ScheduleViewModel.kt"
        const val MAIN_ACTIVITY_NAME = "MainActivity.kt"
        const val HOME_SCREEN_NAME = "HomeScreen.kt"
        const val POLICY_NAME = "CourseDeletionPolicy.kt"

        // ① 仓储层
        const val REPO_SIGNATURE =
            "suspend fun deleteCourse(course: Course): CourseDeletion = writeMutex.withLock {"
        const val REPO_TRANSACTION = "db.withTransaction {"
        const val REPO_READ_ROW_BACK = "listOfNotNull(courseDao.getById(course.id)?.toDomain())"
        const val REPO_REMINDERS_BY_FOUND_ROW = "rows.mapNotNull { reminderDao.getByCourse(it.id)"
        const val REPO_VERDICT = "val deletion = courseDeletionOf(rows, reminders)"
        const val REPO_GATED_DELETE = "if (deletion.removedAnything) deleteCourseRow(rows.first())"
        const val REPO_OLD_SIGNATURE = "suspend fun deleteCourse(course: Course): List<ReminderSetting>"

        // ②③ VM 与册子
        const val VM_SIGNATURE = "suspend fun deleteCourse(course: Course): Boolean = suspendCatching {"
        const val VM_CALLS_REPOSITORY = "repository.deleteCourse(course)"
        const val VM_CALL_SITE = "viewModel.deleteCourse("
        const val REPO_CALL_SITE = "repository.deleteCourse("
        const val VM_EARLY_EXIT = "val removed = deletion.removedCourse ?: return@suspendCatching false"
        const val VM_PUSH_FOUND_ROW = "UndoManager.pushDelete(removed, deletion.removedReminders)"
        const val VM_REFRESH = "afterDataChangedInternal()"
        const val VM_FOLD_PASSTHROUGH = "}.fold({ it }, { e ->"
        const val VERDICT_CONSTRUCTION = "CourseDeletion.Removed("

        // ④ 消费方
        const val HOME_DELETE_CALL = "val deleted = viewModel.deleteCourse(course)"
        const val HOME_IF_DELETED = "if (deleted)"
        const val HOME_DELETED_COPY = "\"已删除「"
        const val HOME_STILL_THERE_COPY = "还在课表里"
        const val HOME_UNDO_BUTTON = "actionLabel = \"撤销\".takeIf { deleted }"
        const val HOME_UNDO_CALL = "viewModel.undo()"
        const val LAUNCH_ANCHOR = "scope.launch {"
        const val EDITOR_PARAM_TYPE = "onDelete: suspend (Course) -> Boolean,"
        const val EDITOR_IF_ON_DELETE = "if (onDelete(target)) {"
        const val EDITOR_ERROR_COPY = "saveError = \"删除失败，请重试\""
        const val EDITOR_ON_BACK = "onBack()"
        const val MAIN_ACTIVITY_ON_DELETE = "onDelete = { viewModel.deleteCourse(it) }"

        // ⑤ 内核
        const val KERNEL_PREDICATE = "internal fun deletionRemovedSomething("
        const val KERNEL_NEXT_FUNCTION = "internal fun courseDeletionOf("
        const val PREDICATE_RETURN_ARROW = "): Boolean ="
        const val KERNEL_PREDICATE_BODY = "foundRows.isNotEmpty()"
        const val KERNEL_PROJECTION = "val removedAnything: Boolean"
        val CLOCK_LITERALS = listOf(
            "System.currentTimeMillis", "System.nanoTime", "java.time", "LocalDate", "LocalTime",
            "LocalDateTime", "Calendar", "Date(", "Clock", ".now(", "TimeZone", "Build.",
            "getSystemService", "LocalContext", "displayMetrics",
        )
    }
}
