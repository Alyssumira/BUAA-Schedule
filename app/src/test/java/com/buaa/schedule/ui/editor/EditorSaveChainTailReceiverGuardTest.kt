package com.buaa.schedule.ui.editor

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T260④：钉住**「编辑器保存链的收尾不许再只靠那一枚会被组合取消的 scope」这一族**。
 *
 * 病（台账 #498 = T258-04，装机口径「点保存到落库之间转一次屏」）：写库那一半早在 T124c 就进了
 * `viewModelScope` 的那一枚 job（页 scope 被 dispose 也跑完整趟），而收尾那一半（`onBack()`）
 * 还挂在 `rememberCoroutineScope()` 上。转屏落在那两半之间 ⇒ 课已入库、页面退回「添加课程」
 * （新建那一路的身份还是 `id = 0L`，草稿又是 `rememberSaveable` 的），再点一次插出第二门同样的课。
 * 在册普查（`CourseEditorDeleteExitReceiverGuardTest` 第 ③ 层）当时把这枚落点登记为
 * 「今天不咬人、但脆」，并写明正确的收法是照删除链那一手搬 —— 本卡接的就是那一半。
 *
 * ## 钉法：点名用哪一种
 *
 * 主钉法是 **抹注释找锚点 + 读形状**（花括号配平取块，块内判落点与次序），
 * 辅以 **数出现次数**（枚数就是档位表，长一枚少一枚都红）。**通篇不写死行号** ——
 * 报错消息只给 文件 + 判据 + 实到枚数，`L<行>` 那一段由 `hint()` 从**当趟读到的原文**算出来，
 * 换一台机器、插一段注释都不会假红（本仓那一族死号吃过好几次）。
 * 逐字节那一种本卡只用在一枚字面量上（闸门那一行的并算式），因为它要的正是「一字不改」。
 *
 * 五层，两侧都有格子：
 * 1. [结论交回那一手必须在 viewModelScope 的 job 体内]：两枚 `...AndAwait` 各自体内那一枚协程，
 *    `editorSaveSettled(` **落在体内**、`job.join()` 之后 0 处。⇒ **朝宽扭就在这一格红**：
 *    把它挪到 join 之后（= 挪回调用者那枚组合 scope）本卡当场白修 —— 那正是本卡的病灶本身。
 * 2. [保存链收尾向 VM 报到那一手排在这一支自己退栈之前]：体内 `onSaveTailReached(` 恰好一处、
 *    次序是 `saveCourseDraft(` → 报到 → `onBack()`；删除链体内 0 处。⇒ 摘掉报到（目的地层
 *    连活着的那一支也一起接管 ⇒ 连 pop 两格）或把它排在写库之前（还什么都不知道就报到了）都红。
 * 3. [目的地层那枚认领协程与渲染闸门平级]：`LaunchedEffect(editorSaveFinish)` 恰好一处、
 *    **不在** `if (!uiState.loading && !courseMissing && reminderGateOpen) {` 那块体内（那一行的第三半是
 *    T381① 添的提醒就绪位，本档改钉这一整行；认领协程仍在块外——把它挪进块内就是本卡病灶），体内判据一枚、
 *    消费一枚、pop 一枚，且**消费排在退栈之前**（两句之间无挂起点 ⇒ 点火即消费）。
 * 4. [立旗与领号排在 await 之前]：`onSave` lambda 体内 `nextEditorSaveRequestId()` 与
 *    `editorSaveRequested(` 各一枚且都排在 `viewModel.saveCourseAndAwait(` 之前 ——
 *    同 T166/T177 那面删除旗的理由：await 之后那一支多半跑不到。
 * 5. [闸门那一枚吃的是跨配置存活的旗，不是只活到转屏那一秒的那一枚]：编辑器里
 *    `saving = saving || saveInFlight` 逐字节一处、`editorCanSave` 调用形状未改形（本卡不碰
 *    在册 #379 那三枚红字段）、MainActivity 侧 `holdsSaveGate(editorSaveFinish)` 一处；
 *    号的声明必须是 `rememberSaveable`（裸 `remember` 那一形 0 处）。
 */
class EditorSaveChainTailReceiverGuardTest {

    // ─────────────── ① VM：结论交回那一手的落点 ───────────────

    @Test
    fun `两枚AndAwait交回结论那一手长在viewModelScope的job体内而不是join之后`() {
        val code = blankCommentsKeepingLiterals(source(SCHEDULE_VIEW_MODEL))
        for (entry in listOf(SAVE_AWAIT_SIGNATURE to SAVE_JOB_WRITE, UPDATE_AWAIT_SIGNATURE to UPDATE_JOB_WRITE)) {
            val signature = entry.first
            val fn = blockOf(code, signature, "VM 的「$signature」")
            val jobAt = fn.indexOf(JOB_LAUNCH)
            assertTrue(
                "$FILE_VM 里「$signature」体内必须有 `$JOB_LAUNCH`（实到 " + occurrences(fn, JOB_LAUNCH) +
                    " 处）：写库那一手与结论交回都挂在这枚 job 上，它不归页面管",
                jobAt >= 0,
            )
            val jobBody = braceBodyFrom(fn, jobAt + JOB_LAUNCH.length - 1).body
            assertEquals(
                "$FILE_VM：「$signature」体内 `$JOB_LAUNCH` 恰好一处（实到 " + occurrences(fn, JOB_LAUNCH) +
                    " 处）：这一族不许长出第二枚接收者",
                1,
                occurrences(fn, JOB_LAUNCH),
            )
            assertEquals(
                "$FILE_VM：写库那一手 `${entry.second}` 必须在那一枚协程体内（实到 " +
                    occurrences(jobBody, entry.second) + " 处）—— T124c 的形状不许回退",
                1,
                occurrences(jobBody, entry.second),
            )
            assertEquals(
                "$FILE_VM：**本卡的题眼** —— `editorSaveSettled(landed = savedId != null)` 必须在" +
                    "那一枚 `viewModelScope` 协程**体内**恰好一处（体内实到 " + occurrences(jobBody, SETTLE_CALL) +
                    " 处、整枚方法内 " + occurrences(fn, SETTLE_CALL) + " 处）。**朝宽扭就在这一格红**：把它挪到" +
                    "`job.join()` 之后（哪怕留在同一枚方法体内、看着还像 VM 的活）它跑的就是**调用者**那一枚" +
                    "`rememberCoroutineScope()` —— 转屏落在写库期间时结论压根没人交回，页 scope 的续体也早被取消，" +
                    "于是「课已入库而页面退回添加课程、再点一次插第二门课」原样复活。" +
                    hint(source(SCHEDULE_VIEW_MODEL), SETTLE_CALL),
                1,
                occurrences(jobBody, SETTLE_CALL),
            )
            assertEquals(
                "$FILE_VM：`job.join()` 之后那一手不许再交回结论（体外实到 " +
                    (occurrences(fn, SETTLE_CALL) - occurrences(jobBody, SETTLE_CALL)) + " 处）：" +
                    "join 是页 scope 那一侧的等待点，挂在它后面的都不是转屏存活的那一枚",
                0,
                occurrences(fn, SETTLE_CALL) - occurrences(jobBody, SETTLE_CALL),
            )
        }
    }

    // ─────────────── ② 编辑器：收尾报到那一手 ───────────────

    @Test
    fun `保存链收尾先向VM报到再自己退栈 而删除链不领这枚旗`() {
        val code = blankCommentsKeepingLiterals(source(EDITOR))
        val saveHits = indexOfAll(code, SAVE_HAND)
        assertEquals(
            "$FILE_EDITOR 里 `$SAVE_HAND` 恰好一处（保存链那一手的身份证，实到 " + saveHits.size + " 处）",
            1,
            saveHits.size,
        )
        val body = launchBodyAround(code, saveHits.first(), EDITOR)
        assertEquals(
            "$FILE_EDITOR：保存链那一枚 `scope.launch { … }` 体内 `$TAIL_REPORT` 恰好一处（实到 " +
                occurrences(body, TAIL_REPORT) + " 处）。**这一格拦的就是「收尾又挂回组合 scope」**：" +
                "报到那一手是 `tailReported` 那格唯一的出处 —— 它被摘掉（或被搬去别处）之后，" +
                "目的地层无从分辨「页面那一支还活着」与「已经被组合取消」，只能两档都弹，" +
                "于是同一枚栈被 pop 两格（T124c 当年写下的「多 pop 一格」那条禁忌就是这么回来的）。" +
                hint(source(EDITOR), TAIL_REPORT),
            1,
            occurrences(body, TAIL_REPORT),
        )
        val reportAt = body.indexOf(TAIL_REPORT)
        val onBackAt = body.indexOf(ON_BACK_CALL)
        assertTrue(
            "$FILE_EDITOR：次序必须是 写库 → **报到** → 退栈：`$SAVE_HAND`@${body.indexOf(SAVE_HAND)} / " +
                "`$TAIL_REPORT`@$reportAt / `$ON_BACK_CALL`@$onBackAt。报到排在 `onBack()` 之后 = " +
                "退栈那一手已经跑了，而目的地层读到的还是「没报到」⇒ 它再替这一页弹一次；" +
                "报到排在写库之前 = 结论还没落地就先说这一支活着（本仓「顺序决定生死」那一族的老规矩：" +
                "这一格是**次序断言**，两处都在不算过）",
            body.indexOf(SAVE_HAND) in 0 until reportAt && reportAt in 0 until onBackAt,
        )
        assertEquals(
            "$FILE_EDITOR：这一枚体内 `$ON_BACK_CALL` 仍恰好一处（在册普查 ③ 那一支，本卡按红线没搬走它 —— " +
                "搬走的凭据就是上面那一次报到）：实到 " + occurrences(body, ON_BACK_CALL) + " 处",
            1,
            occurrences(body, ON_BACK_CALL),
        )
        // 删除链那一枚体不许顺手也报一次到：它交回的是另一枚旗（DELETE_REQUESTED_BY_THIS_ENTRY）
        val deleteHits = indexOfAll(code, DELETE_HAND)
        assertEquals("$FILE_EDITOR 里 `$DELETE_HAND` 恰好一处（删除链那一手）：", 1, deleteHits.size)
        val deleteBody = launchBodyAround(code, deleteHits.first(), EDITOR)
        assertEquals(
            "$FILE_EDITOR：删除链那一枚协程体内 `$TAIL_REPORT` 必须 0 处（实到 " +
                occurrences(deleteBody, TAIL_REPORT) + " 处）：那一条链的凭据是「本 entry 发起过删除」" +
                "（`DELETE_REQUESTED_BY_THIS_ENTRY` 那一面旗，T166/T177），与「保存在途」是两件事 —— " +
                "让删除链也报一次到，就是拿同一枚旗盖两趟操作，弹不弹再也分不出是哪一趟",
            0,
            occurrences(deleteBody, TAIL_REPORT),
        )
        assertEquals(
            "$FILE_EDITOR：两枚注入都在（`$SAVE_IN_FLIGHT_PARAM` 与 `$TAIL_REPORT_PARAM` 各一处，实到 " +
                occurrences(code, SAVE_IN_FLIGHT_PARAM) + " / " + occurrences(code, TAIL_REPORT_PARAM) +
                " 处），且**都不许给默认值**：`$SAVE_IN_FLIGHT_DEFAULTED` 与 `$TAIL_REPORT_DEFAULTED` 各 0 处" +
                "（实到 " + occurrences(code, SAVE_IN_FLIGHT_DEFAULTED) + " / " +
                occurrences(code, TAIL_REPORT_DEFAULTED) + " 处）—— 给了默认值，接线漏一处就静默退回本卡的病",
            true,
            occurrences(code, SAVE_IN_FLIGHT_PARAM) == 1 && occurrences(code, TAIL_REPORT_PARAM) == 1 &&
                occurrences(code, SAVE_IN_FLIGHT_DEFAULTED) == 0 && occurrences(code, TAIL_REPORT_DEFAULTED) == 0,
        )
    }

    // ─────────────── ③ 目的地层：认领协程的形状与平级位置 ───────────────

    @Test
    fun `目的地层那枚认领协程与渲染闸门平级 消费排在退栈之前`() {
        val code = blankCommentsKeepingLiterals(source(MAIN_ACTIVITY))
        val effectHits = indexOfAll(code, CLAIM_EFFECT)
        assertEquals(
            "$FILE_ACTIVITY 里 `$CLAIM_EFFECT` 恰好一处（本卡的退栈认领点；实到 " + effectHits.size +
                " 处）：多一枚 = 多一路能弹这一格栈的分支，少一枚 = 收尾没人认领（本卡的病原样回来）",
            1,
            effectHits.size,
        )
        val body = braceBodyFrom(code, code.indexOf('{', effectHits.first() + CLAIM_EFFECT.length)).body
        assertEquals(
            "认领协程体内 `$CLAIMED_BY_DESTINATION` 恰好一处（判据只此一枚，档位本体在 " +
                "$FILE_KERNEL 由表驱动单测逐格钉）：实到 " + occurrences(body, CLAIMED_BY_DESTINATION) + " 处",
            1,
            occurrences(body, CLAIMED_BY_DESTINATION),
        )
        assertEquals(
            "体内 `$CONSUME` 恰好一处（点火即消费，载体是 VM 那一枚旗）：实到 " + occurrences(body, CONSUME) + " 处",
            1,
            occurrences(body, CONSUME),
        )
        assertEquals(
            "体内 `$POP_BACK_STACK` 恰好一处：实到 " + occurrences(body, POP_BACK_STACK) +
                " 处 —— 多一枚就是连 pop 两格",
            1,
            occurrences(body, POP_BACK_STACK),
        )
        assertTrue(
            "次序必须是 **消费 → 退栈**：`$CONSUME`@${body.indexOf(CONSUME)} 必须排在 " +
                "`$POP_BACK_STACK`@${body.indexOf(POP_BACK_STACK)} 之前，两句之间没有挂起点 ⇒ 这一趟一定弹得出去；" +
                "消费之后状态归零 ⇒ 转屏或键变化把这枚协程重建也点不动第二次（不会把用户多往后弹一格）。" +
                "挪到 pop 之后就是「pop 已在途」那一刻转屏的那一枚反例",
            body.indexOf(CONSUME) in 0 until body.indexOf(POP_BACK_STACK),
        )
        // 接收者必须在外面：闸门那块体内不许有这条协程
        val gateHits = indexOfAll(code, RENDER_GATE)
        assertEquals("$FILE_ACTIVITY 里 `$RENDER_GATE` 恰好一处：", 1, gateHits.size)
        val gateBody = braceBodyFrom(code, gateHits.first() + RENDER_GATE.length - 1).body
        assertFalse(
            "$CLAIM_EFFECT 被挪进了 `$RENDER_GATE` 那块体里 ⇒ 它和 `CourseEditorScreen` **同一棵子树**，" +
                "闸门摘页的同一刻它也被取消：这正是本卡要修的那枚病换了个位置。它必须与闸门**平级**" +
                "（长在 `composable { backStackEntry -> … }` 那一层），实到 " + occurrences(gateBody, CLAIM_EFFECT) + " 处",
            gateBody.contains(CLAIM_EFFECT),
        )
        // 号必须存在跨配置变化存活的那一枚槽里
        assertEquals(
            "$FILE_ACTIVITY：`$REQUEST_ID_DECL` 恰好一处（本条 entry 的号存 saveable 槽 —— " +
                "T177 那一族同一个理由：裸 remember 的那一枚长在 composition 上，转屏就归零，" +
                "新 composition 配不上自己那一趟的结论）。实到 " + occurrences(code, REQUEST_ID_DECL) + " 处",
            1,
            occurrences(code, REQUEST_ID_DECL),
        )
        assertFalse(
            "号不许换成裸 `remember`（正则 `$COMPOSITION_BACKED_REQUEST_ID` 实到 " +
                Regex(COMPOSITION_BACKED_REQUEST_ID).findAll(code).count() +
                " 处）：那一枚的寿命就是这棵 composition 的寿命，正是本卡病灶要的那一枚「转屏就没了」的载体",
            Regex(COMPOSITION_BACKED_REQUEST_ID).containsMatchIn(code),
        )
    }

    // ─────────────── ④ 立旗与领号排在 await 之前 ───────────────

    @Test
    fun `立旗与领号都排在 await 之前 onSave 那一枚 lambda 体内`() {
        val code = blankCommentsKeepingLiterals(source(MAIN_ACTIVITY))
        val lambdaHits = indexOfAll(code, ON_SAVE_ANCHOR)
        assertEquals("$FILE_ACTIVITY 里 `$ON_SAVE_ANCHOR` 恰好一处（取块的宿主）：", 1, lambdaHits.size)
        val lambda = braceBodyFrom(code, code.indexOf('{', lambdaHits.first())).body
        assertEquals("体内 `$MINT_ID` 恰好一处（领号）：实到 " + occurrences(lambda, MINT_ID) + " 处", 1, occurrences(lambda, MINT_ID))
        assertEquals("体内 `$RAISE_FLAG` 恰好一处（立旗）：实到 " + occurrences(lambda, RAISE_FLAG) + " 处", 1, occurrences(lambda, RAISE_FLAG))
        assertEquals(
            "体内 `$AWAIT_SAVE` 恰好一处：本卡不换这两枚入口的名字（在册 WriteChainReceiverWiringGuardTest " +
                "档 ⑪⑬ 钉的就是它们），实到 " + occurrences(lambda, AWAIT_SAVE) + " 处",
            1,
            occurrences(lambda, AWAIT_SAVE),
        )
        assertTrue(
            "次序必须是 **领号 → 立旗 → await**：" +
                "`$MINT_ID`@${lambda.indexOf(MINT_ID)} / `$RAISE_FLAG`@${lambda.indexOf(RAISE_FLAG)} / " +
                "`$AWAIT_SAVE`@${lambda.indexOf(AWAIT_SAVE)}。立旗排在 await 之后 = 那一枚旗十有八九立不起来" +
                "（这一整支跑在编辑器的组合期 scope 上，转屏就取消）⇒ 目的地层读不到在途、也读不到结论，" +
                "本卡白修；领号排在立旗之后 = 旗上带的号是上一趟的，配对那一格当场失效",
            lambda.indexOf(MINT_ID) in 0 until lambda.indexOf(RAISE_FLAG) &&
                lambda.indexOf(RAISE_FLAG) in 0 until lambda.indexOf(AWAIT_SAVE),
        )
        assertEquals(
            "`$FLAG_RAISE_FN` 在 $FILE_VM 的**方法体外**不许有第二处调用点（体内那次才是 VM 自己的实现，" +
                "全仓调用点只此一枚）：实到 " + occurrences(code, RAISE_FLAG) + " 处",
            1,
            occurrences(code, RAISE_FLAG),
        )
    }

    // ─────────────── ⑤ 闸门吃的是跨配置存活的那一枚 ───────────────

    @Test
    fun `保存闸门取 saving 与跨配置存活旗的并 editorCanSave 本体三格判据未改形`() {
        val editor = blankCommentsKeepingLiterals(source(EDITOR))
        assertEquals(
            "$FILE_EDITOR：`$GATE_LINE` 逐字节恰好一处（实到 " + occurrences(editor, GATE_LINE) +
                " 处）。**朝宽扭就在这一格红**：把 `saveInFlight` 摘出闸门，写库在飞那一刻转屏之后" +
                "新 composition 里 `saving` 从 false 重新算起 ⇒ 那颗按钮当场放开 ⇒ 第二趟 `id = 0L` 起手，" +
                "库里多出第二门同样的课（台账 #498 那枚「重」正落在这里）。" +
                hint(source(EDITOR), GATE_LINE),
            1,
            occurrences(editor, GATE_LINE),
        )
        assertEquals(
            "$FILE_EDITOR：`$GATE_CALL_SHAPE` 恰好一处 —— 本卡只换了送进 `saving` 那一位的值，" +
                "`editorCanSave` 的三格判据与调用形状一字未动（在册 #379 / T258-01 那三枚红字段是另一张卡）：" +
                "实到 " + occurrences(editor, GATE_CALL_SHAPE) + " 处",
            1,
            occurrences(editor, GATE_CALL_SHAPE),
        )
        val activity = blankCommentsKeepingLiterals(source(MAIN_ACTIVITY))
        assertEquals(
            "$FILE_ACTIVITY：`$GATE_ARG` 恰好一处（闸门那一枚实参只能由内核的投影算出来，" +
                "就地再判一次 phase 就是第二把尺）：实到 " + occurrences(activity, GATE_ARG) + " 处",
            1,
            occurrences(activity, GATE_ARG),
        )
        assertEquals(
            "$FILE_ACTIVITY：`$REPORT_ARG` 恰好一处（报到那一条链的接线点）：实到 " +
                occurrences(activity, REPORT_ARG) + " 处",
            1,
            occurrences(activity, REPORT_ARG),
        )
        // 内核那三枚入口在全仓 main 的调用枚数册子：任何一处归零 = 内核交回的档位没人吃，
        // 表驱动单测再密也判不到生产（判据退回「注释里说说」那一族）。
        for ((needle, expected) in listOf(
            KERNEL_READOUT_CALL to 1,
            HOLDS_GATE_PROJECTION to 1,
            CLAIMED_PROJECTION to 1,
        )) {
            assertEquals(
                "全仓 main 里 `$needle` 恰好 $expected 处（实到 " + occurrences(mainSourcesBlanked(), needle) +
                    " 处）：这一枚入口没人调 = 本卡的判据在生产侧断链；多于一处 = 同一个判断有人各算一遍（两把尺）",
                expected,
                occurrences(mainSourcesBlanked(), needle),
            )
        }
    }

    // ---------------- 源码核对小工具（抄 CourseEditorDeleteExitReceiverGuardTest / DeleteFlagConfigSurvivalGuardTest）----------------

    /** 全仓 main（抹注释、CRLF 归一）拼成一枚串，供「内核入口有没有人调」那一格数落点 */
    private fun mainSourcesBlanked(): String {
        val out = StringBuilder()
        for ((_, text) in mainSources()) out.append(blankCommentsKeepingLiterals(text))
        return out.toString()
    }

    /** 锚点所在方法 / lambda 的那一块：取锚点之后第一个花括号 */
    private fun blockOf(code: String, anchor: String, label: String): String {
        val at = code.indexOf(anchor)
        assertTrue("$label 的锚点 `$anchor` 在盘上找不到（写法或文件名挪过家，本守卫要跟着改）", at >= 0)
        assertEquals("$label 的锚点 `$anchor` 必须恰好一处（两枚就是两把尺）：", 1, code.split(anchor).size - 1)
        return braceBodyFrom(code, code.indexOf('{', at)).body
    }

    /** 从 [hit] 往前找**包围它的那一枚** `scope.launch {`，取出那一段协程体（取不出就抛：静默跳过等于没有守卫） */
    private fun launchBodyAround(code: String, hit: Int, file: String): String {
        val at = code.lastIndexOf("$PAGE_SCOPE_LAUNCH", hit)
        assertTrue(
            "$file：那一处（offset=$hit）往前找不到包围它的 `$PAGE_SCOPE_LAUNCH` —— 这一手换了载体" +
                "（不在页 scope 的协程里了？）：那要回来重判本守卫这一层，不是把这一格摘掉",
            at >= 0,
        )
        val extracted = braceBodyFrom(code, at + PAGE_SCOPE_LAUNCH.length - 1)
        assertTrue(
            "$file：取出的协程体不含那一处（花括号配平跑偏）：hit=$hit body=${extracted.first}..${extracted.second}",
            hit > extracted.first && hit < extracted.second,
        )
        return extracted.body
    }

    private data class Extracted(val body: String, val first: Int, val second: Int)

    private fun braceBodyFrom(code: String, open: Int): Extracted {
        check(code.getOrNull(open) == '{') { "锚点末尾不是花括号：实到「${code.getOrNull(open)}」" }
        var depth = 0
        var index = open
        var inString = false
        var escaped = false
        while (index < code.length) {
            val c = code[index]
            when {
                inString -> when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }

                c == '"' -> inString = true
                c == '\'' -> {
                    var j = index + 2
                    if (index + 1 < code.length && code[index + 1] == '\\') j = index + 3
                    while (j < code.length && code[j] != '\'') j++
                    index = j
                }

                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) return Extracted(code.substring(open + 1, index), open, index)
                }
            }
            index++
        }
        throw IllegalStateException("花括号没配平：从偏移 $open 起")
    }

    /** 报错消息里点名 行号 + 原文（只报个数等于没有守卫） */
    private fun hint(raw: String, needle: String): String {
        val positions = indexOfAll(raw, needle)
        if (positions.isEmpty()) return " 盘上一处都没有"
        val lines = raw.split('\n')
        return positions.joinToString(prefix = "\n  ") { at ->
            val index = raw.substring(0, at).count { it == '\n' }
            "L${index + 1}: " + lines.getOrElse(index) { "<越界>" }.trim()
        }
    }

    private fun indexOfAll(haystack: String, needle: String): List<Int> {
        val hits = mutableListOf<Int>()
        if (needle.isEmpty()) return hits
        var from = 0
        while (true) {
            val at = haystack.indexOf(needle, from)
            if (at < 0) return hits
            hits += at
            from = at + needle.length
        }
    }

    private fun occurrences(haystack: String, needle: String): Int = indexOfAll(haystack, needle).size

    /** **CRLF 归一**：本仓 main 源码在盘上是 CRLF，判据不许吃检出风格 */
    private fun source(relativeFromMainJava: String): String {
        val file = File(findMainJavaDir(), relativeFromMainJava)
        assertTrue("找不到 ${file.path}：文件挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText().replace("\r\n", "\n")
    }

    private fun mainSources(): Map<String, String> {
        val root = File(findMainJavaDir(), "com/buaa/schedule")
        assertTrue("找不到 $root：包结构挪过家，本守卫要跟着改", root.isDirectory)
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .associateBy({ it.relativeTo(findMainJavaDir()).path.replace('\\', '/') }, { it.readText().replace("\r\n", "\n") })
    }

    private fun findMainJavaDir(): File {
        var dir: File? = File("").absoluteFile
        repeat(6) {
            val hit = listOf("src/main/java", "app/src/main/java")
                .map { File(dir, it) }
                .firstOrNull { it.isDirectory }
            if (hit != null) return hit
            dir = dir?.parentFile
        }
        throw IllegalStateException("找不到 app/src/main/java：当前目录 ${File("").absolutePath}")
    }

    /**
     * **只抹注释、保留字符串字面量内容**，长度与换行位置不变。
     * 本卡尤其需要抹注释：编辑器与 MainActivity 那几段 KDoc 里逐字写着 `onBack()`、
     * `editorSaveSettled`、`remember` 与被禁掉的那一形，不抹就会被数成盘上多出来的落点。
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
        const val MAIN_ACTIVITY = "com/buaa/schedule/MainActivity.kt"
        const val EDITOR = "com/buaa/schedule/ui/editor/CourseEditorScreen.kt"
        const val SCHEDULE_VIEW_MODEL = "com/buaa/schedule/ui/ScheduleViewModel.kt"
        const val KERNEL = "com/buaa/schedule/domain/editor/EditorSaveFinishing.kt"

        // 报错消息里点名用的文件名（**不带行号**：本仓有一把尺会把「文件名:行号」连写算成一枚锚点）
        const val FILE_ACTIVITY = "MainActivity.kt"
        const val FILE_EDITOR = "ui/editor/CourseEditorScreen.kt"
        const val FILE_VM = "ui/ScheduleViewModel.kt"
        const val FILE_KERNEL = "domain/editor/EditorSaveFinishing.kt"

        // ── ① VM 侧 ──
        const val SAVE_AWAIT_SIGNATURE = "suspend fun saveCourseAndAwait(course: Course): Long? {"
        const val UPDATE_AWAIT_SIGNATURE = "suspend fun updateCourseAndAwait(course: Course, options: CourseSaveOptions): Long? {"
        const val JOB_LAUNCH = "viewModelScope.launch {"
        const val SAVE_JOB_WRITE = "savedId = saveCourse(course)"
        const val UPDATE_JOB_WRITE = "savedId = updateCourse(course, options)"
        const val SETTLE_CALL = "editorSaveSettled(landed = savedId != null)"

        // ── ② 编辑器侧 ──
        const val PAGE_SCOPE_LAUNCH = "scope.launch {"
        const val SAVE_HAND = "val saved = saveCourseDraft("
        const val DELETE_HAND = "onDelete(target)"
        const val TAIL_REPORT = "onSaveTailReached()"
        const val ON_BACK_CALL = "onBack()"
        const val SAVE_IN_FLIGHT_PARAM = "saveInFlight: Boolean,"
        const val TAIL_REPORT_PARAM = "onSaveTailReached: () -> Unit,"
        const val SAVE_IN_FLIGHT_DEFAULTED = "saveInFlight: Boolean ="
        const val TAIL_REPORT_DEFAULTED = "onSaveTailReached: () -> Unit ="

        // ── ③ 目的地层 ──
        const val CLAIM_EFFECT = "LaunchedEffect(editorSaveFinish)"
        const val CLAIMED_BY_DESTINATION = "EditorSaveFinishing.claimedByDestination(editorSaveFinish)"
        const val CONSUME = "viewModel.editorSaveConsumed(editorSaveRequestId)"
        const val POP_BACK_STACK = "navController.popBackStack()"
        const val RENDER_GATE = "if (!uiState.loading && !courseMissing && reminderGateOpen) {"
        const val REQUEST_ID_DECL = "var editorSaveRequestId by rememberSaveable { mutableLongStateOf(EditorSaveSentinel.NO_REQUEST) }"
        const val COMPOSITION_BACKED_REQUEST_ID = """editorSaveRequestId\s+by\s+remember(?!Saveable)"""

        // ── ④ 立旗与领号的次序 ──
        const val ON_SAVE_ANCHOR = "onSave = { edited, options ->"
        const val MINT_ID = "editorSaveRequestId = viewModel.nextEditorSaveRequestId()"
        const val RAISE_FLAG = "viewModel.editorSaveRequested(editorSaveRequestId)"
        const val AWAIT_SAVE = "viewModel.saveCourseAndAwait(edited)"
        const val FLAG_RAISE_FN = "editorSaveRequested("

        // ── ⑤ 闸门 ──
        const val GATE_LINE = "saving = saving || saveInFlight"
        const val GATE_CALL_SHAPE = "editorCanSave(weeks = weeks, periods = periods, saving = "
        const val GATE_ARG = "saveInFlight = EditorSaveFinishing.holdsSaveGate(editorSaveFinish),"
        const val REPORT_ARG = "onSaveTailReached = { viewModel.editorSaveTailReached(editorSaveRequestId) },"
        const val HOLDS_GATE_PROJECTION = "EditorSaveFinishing.holdsSaveGate("
        const val CLAIMED_PROJECTION = "EditorSaveFinishing.claimedByDestination("
        const val KERNEL_READOUT_CALL = "EditorSaveFinishing.readout("
    }
}
