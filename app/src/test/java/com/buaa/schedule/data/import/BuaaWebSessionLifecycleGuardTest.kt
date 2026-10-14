package com.buaa.schedule.data.import

import com.buaa.schedule.domain.session.PreviousWebView
import com.buaa.schedule.domain.session.RetainSource
import com.buaa.schedule.domain.session.RestoreBackoffSentinel
import com.buaa.schedule.domain.session.RestoreBackoffWindowGate
import com.buaa.schedule.domain.session.SessionRetainGate
import com.buaa.schedule.domain.session.RestoreBackoffWindow
import com.buaa.schedule.domain.session.RestoreSingleFlight
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T378④：钉「教务会话四枚出口的拆法与单飞旗的归属」这一族形状**不会再回来**。
 *
 * 病灶不是某一行写错，而是**先后与归属**：
 * ① 判据排在状态写入之后（先换引用、再无条件写 lastUrl）；
 * ② 拆旧少一手（挂着视图树直接 destroy，另外三枚出口都是先摘树）；
 * ③ 收旗与拆闸分家（retain 拆掉全仓唯一还能收 `restoreInFlight` 的超时闸，自己却不收旗）；
 * ④ 退避钟拿墙上钟且不夹下限（回拨 ⇒ 差为负 ⇒ 恒早退，且那一档一句话不说）；
 * ⑤ 初始值与时间戳共用同一格（台账 #496 那一族）。
 * 所以主尺是**读形状**（花括号配平取块 ＋ 索引顺序 ＋ 逐字节锚点），
 * 逐字节与数出现次数只在各自的格子里当副钉：把判据搬回状态写入之后时，每一行逐字节都对得上，
 * 而缺陷整块回来 —— 只有索引顺序与块归属拦得住那一手（本仓 §58 的教训：抽函数/挪函数会漂出刀口，
 * 所以本守卫一律按「声明块」取块，不按行号）。
 *
 * ## 报错消息里一枚行号都不写
 *
 * 行号会漂（本仓为此重钉过十几枚锚点）。命中那一处的**原文**照登进消息，够把人带到那一处；
 * 台账要的漂移表在卡的回执里单独交。
 *
 * ## 两侧的红臂（本守卫自己也得是活的，读数在 ⑤ 那枚 commit 里）
 *
 * - **W 臂（朝宽）**：把内核的出身判据摘掉（任何候选都算收下）⇒ 表驱动那几枚题眼格红；
 * - **N 臂（朝窄）**：从 `retain` 里删掉「先摘树再 destroy」那一手 ⇒ 本守卫的次序格红。
 *   两臂都必须在**自己那一格**红，不许连带把别的格子也压绿。
 *
 * ## 输入面
 *
 * 只读 `app/src/main/java` 下的工作树文本 —— 该目录整体已在 `app/build.gradle.kts` 的
 * `guardReadWorkingTreeFiles` 里进 `tasks.withType<Test>()` 的 inputs（T92①，台账 #136），
 * 本卡没有新增目录之外的读取面（不读 res/、不读清单、不读脚本）。纯 JVM，零设备，不读钟。
 *
 * ## 钉不住的行为（明说，不许用「覆盖了」这个词）
 *
 * 本守卫钉的是**形状**：它证明代码里没有那一手／有那一手的次序对，它**不证明**运行期真的拆掉了、
 * 收下了、恢复了。「拒收一枚非教务出身的实例会不会让用户重走一遍 SSO」「先摘树再 destroy 之后
 * 登录页 `onRelease` → `releaseChildren` 那一路的语义要不要跟着变」「单调钟换尺之后真机上退避
 * 是不是那 60 秒」三件都只能装机判 ⇒ 登记给编排者（本卡零 adb）。
 */
class BuaaWebSessionLifecycleGuardTest {

    // ─────────────── ① 两枚内核的纯度 ───────────────

    @Test
    fun `两枚内核零 android 也不读钟读设备`() {
        for (file in listOf(RETAIN_GATE, BACKOFF_GATE)) {
            val raw = source(file)
            val offenders = raw.lineSequence().map { it.trim() }
                .filter { it.startsWith("import ") }
                .filter { it.contains("android.") || it.contains("androidx.") }
                .toList()
            assertEquals("$file 必须零 android / androidx import（判据本体要能在 JVM 里表驱动）。实到：" + offenders,
                emptyList<String>(), offenders)
            val code = blankComments(raw)
            for (needle in FORBIDDEN_READS) {
                assertEquals(
                    "$file 里「$needle」必须 0 处：它一读这一枚，「这一枚实例该不该收下」就不再是纯函数 —— " +
                        "同样的候选在两次调用里可能给出两种结论。本仓口径：这些事实留在调用点当参数递进来。" +
                        "实到 " + occurrences(code, needle) + " 处",
                    0,
                    occurrences(code, needle),
                )
            }
            assertEquals(
                "$file 里不许有任何一枚判据以 Boolean 收尾（「$BOOLEAN_RETURN」必须 0 处）：" +
                    "折成布尔就是把「不能当会话」「旧的还得留着」「旗归谁收」三件事念成一句话",
                0,
                occurrences(code, BOOLEAN_RETURN),
            )
        }
        // 反套套逻辑：纯度承诺必须留痕，否则上面那一串 0 没人负责
        assertEquals(
            "内核 KDoc 里那句纯度承诺必须含注释恰好 1 处（取证搬走 ⇒ 上面那些 0 就是假绿）。实到 " +
                occurrences(source(RETAIN_GATE), PURITY_PROMISE_RETAIN),
            1,
            occurrences(source(RETAIN_GATE), PURITY_PROMISE_RETAIN),
        )
        assertEquals(
            "退避那枚内核的纯度承诺同款留痕。实到 " + occurrences(source(BACKOFF_GATE), PURITY_PROMISE_BACKOFF),
            1,
            occurrences(source(BACKOFF_GATE), PURITY_PROMISE_BACKOFF),
        )
    }

    // ─────────────── ② 五枚病灶原文：代码 0 处 / 含注释恰好 1 处 ───────────────

    @Test
    fun `五枚病灶原文代码侧零处而取证原文恰好一处`() {
        val raw = source(WEB_SESSION)
        val code = blankComments(raw)
        for (needle in DISEASES) {
            assertEquals(
                "「$needle」必须**代码 0 处／含注释恰好 1 处**：代码那一档是本卡修的病灶，注释那一档是取证 —— " +
                    "读到 0 说明取证被删干净（上面那些 0 就成了假绿），读到 2 说明病灶回潮。实到 " +
                    occurrences(code, needle) + " 与 " + occurrences(raw, needle),
                listOf(0, 1),
                listOf(occurrences(code, needle), occurrences(raw, needle)),
            )
        }
    }

    // ─────────────── ③ 主尺：判据排在一切状态写入之前 ───────────────

    @Test
    fun `先问出身再动状态 retain 体内判据排在换引用与写 lastUrl 之前`() {
        val code = blankComments(source(WEB_SESSION))
        val body = balancedBlock(code, "fun retain(webView: WebView", "retain 的函数体")

        assertEquals(
            "`$RETAIN_GATE_CALL` 在 retain 体内恰好一枚（实到 " + occurrences(body, RETAIN_GATE_CALL) +
                " 枚）：两枚判据 = 两把尺各判各的；零枚 = 调用点又开始自己判出身",
            1,
            occurrences(body, RETAIN_GATE_CALL),
        )
        val decideAt = body.indexOf(RETAIN_GATE_CALL)
        val swapAt = body.indexOf("sessionWebView = webView")
        val urlWriteAt = body.indexOf("lastUrl = webView.url")
        val emitAt = body.indexOf("_sessionRetained.tryEmit(Unit)")
        assertTrue(
            "判据（index $decideAt）必须排在换引用（index $swapAt）之前：改前是 `val old = sessionWebView` " +
                "之后**立刻** `sessionWebView = webView`，于是任何一枚没有出身判据的上缴都能顶掉活会话 —— " +
                "次序搬回去时每一行逐字节都对得上而缺陷整块回来",
            decideAt in 0 until swapAt,
        )
        assertTrue(
            "判据（index $decideAt）必须排在写 lastUrl（index $urlWriteAt）之前：改前那一手是无条件的，" +
                "一枚 URL 还是 about:blank 的新实例照样把 hasSession 的唯一真相写脏",
            decideAt in 0 until urlWriteAt,
        )
        assertTrue(
            "lastUrl 的赋值必须仍排在门铃信号之前（index $urlWriteAt vs $emitAt）—— 在册" +
                "《SpecialDayRefreshWiringGuardTest》同格互为备份：早一步发，订阅者回头问 hasSession 还是 false",
            urlWriteAt in 0 until emitAt,
        )
        assertEquals(
            "「lastUrl = webView.url」在 retain 体内恰好一枚（实到 " + occurrences(body, "lastUrl = webView.url") +
                " 枚）：多一枚就是有一档在判据之外另写了一次会话真相",
            1,
            occurrences(body, "lastUrl = webView.url"),
        )
    }

    // ─────────────── ④ 主尺：拒收档真的什么都不动 ───────────────

    @Test
    fun `拒收档里会话槽宿主与门铃一律零手 但它必须开口说话`() {
        val code = blankComments(source(WEB_SESSION))
        val body = balancedBlock(code, "fun retain(webView: WebView", "retain 的函数体")
        val refused = balancedBlock(body, REFUSED_ARM, "拒收那一档")

        for (needle in listOf("sessionWebView = webView", "lastUrl = webView.url", "moveToHiddenHost",
            "_sessionRetained.tryEmit", ".destroy(", "cancelRestoreGuard(", "restoreInFlight = true")) {
            assertEquals(
                "拒收档里「$needle」必须 0 处（实到 " + occurrences(refused, needle) +
                    " 枚）：这一档的语义就是「这枚实例不配占会话槽」—— 多一手就是拿拒收当收下过一半",
                0,
                occurrences(refused, needle),
            )
        }
        assertEquals(
            "拒收档里 `Log.w(` 恰好一枚（实到 " + occurrences(refused, "Log.w(") +
                " 枚）：0 枚就是本卡第 2 问点名的那种「整段跳过而一句话不说」，2 枚就是同一档两处各说一遍",
            1,
            occurrences(refused, "Log.w("),
        )
        assertTrue(
            "拒收那句必须把内核给的**三样结论**都念出来（出身档、旧的怎么办、旗与闸怎么办），" +
                "否则日志只说「没收下」，事后照样判不出是谁扭的：",
            refused.contains("verdict.candidateVerdict") && refused.contains("verdict.previousWebView") &&
                refused.contains("verdict.singleFlight"),
        )
        // 凭证那一手在两档之外：拒收实例不等于把登录态一起修没
        val persistAt = body.indexOf("persistCookies()")
        val refusedAt = body.indexOf(REFUSED_ARM)
        assertTrue(
            "`persistCookies()` 在 retain 体内恰好一枚（实到 " + occurrences(body, "persistCookies()") +
                " 枚）且必须排在两档分派**之外**（index $persistAt vs 拒收档 index $refusedAt）：" +
                "长在任一档里就是另一档漏做，CookieManager 是进程级的，与「会话槽归谁」是两件事",
            occurrences(body, "persistCookies()") == 1 && persistAt > refusedAt,
        )
    }

    // ─────────────── ⑤ 主尺：拆旧只许先摘树再拆 ───────────────

    @Test
    fun `拆旧那一手与另外三枚出口同一次序 摘树早于 destroy`() {
        val code = blankComments(source(WEB_SESSION))
        val tear = balancedBlock(code, "PreviousWebView.RemoveFromTreeThenDestroy -> previous?.let", "拆旧那一档")

        val removeAt = tear.indexOf("(old.parent as? ViewGroup)?.removeView(old)")
        val stopAt = tear.indexOf("old.stopLoading()")
        val destroyAt = tear.indexOf("old.destroy()")
        assertTrue(
            "摘树（index $removeAt）必须排在 stopLoading（index $stopAt）与 destroy（index $destroyAt）之前：" +
                "改前这一枚是全仓唯一挂在树上直接 `runCatching { old.destroy() }` 的出口，" +
                "另外三枚（clear／releaseForMemory／abandonRestore）都是先 removeView —— 次序搬回去逐字节仍对得上",
            removeAt in 0 until stopAt && stopAt < destroyAt,
        )
        assertEquals(
            "拆旧那一档里 `runCatching { old.destroy() }` 这一形必须 0 处（实到 " +
                occurrences(tear, "runCatching { old.destroy() }") + " 枚）：它就是把摘树那一手省掉的写法本身",
            0,
            occurrences(tear, "runCatching { old.destroy() }"),
        )
        // 四枚出口的拆法对齐：全文件「?.removeView(」的册子只能是这样
        assertEquals(
            "全文件 `(web.parent as? ViewGroup)?.removeView(web)` 恰好三枚（clear／releaseForMemory／abandonRestore）" +
                "＋ retain 那一枚 `(old.parent as? ViewGroup)?.removeView(old)` 恰好一枚，实到 " +
                occurrences(code, "(web.parent as? ViewGroup)?.removeView(web)") + " 与 " +
                occurrences(code, "(old.parent as? ViewGroup)?.removeView(old)"),
            listOf(3, 1),
            listOf(
                occurrences(code, "(web.parent as? ViewGroup)?.removeView(web)"),
                occurrences(code, "(old.parent as? ViewGroup)?.removeView(old)"),
            ),
        )
        assertEquals(
            "「上缴的就是会话槽那一枚本身」那一档必须与「手里没有旧的」同手不动（两枚并成一档写 Unit，实到 " +
                occurrences(code, CANDIDATE_IS_PREVIOUS_ARM) + " 枚）：拆开写就可能被「顺手」补一手 destroy",
            1,
            occurrences(code, CANDIDATE_IS_PREVIOUS_ARM),
        )
    }

    // ─────────────── ⑥ 主尺：收旗与拆闸同档 旗的收口只有三处 ───────────────

    @Test
    fun `拆闸只许与收旗同档 releaseForMemory 不许碰旗`() {
        val code = blankComments(source(WEB_SESSION))
        val cancelTogether = balancedBlock(code, COLLECT_BOTH_ARM, "收旗又拆闸那一档")
        assertEquals(
            "那一档里 `restoreInFlight = false` 恰好一枚（实到 " + occurrences(cancelTogether, "restoreInFlight = false") +
                " 枚）：0 枚就是改前那一形（拆了全仓唯一还能收旗的闸，自己不收旗）",
            1,
            occurrences(cancelTogether, "restoreInFlight = false"),
        )
        assertEquals(
            "那一档里 `cancelRestoreGuard()` 恰好一枚（实到 " + occurrences(cancelTogether, "cancelRestoreGuard()") + " 枚）",
            1,
            occurrences(cancelTogether, "cancelRestoreGuard()"),
        )
        val keepGuard = balancedBlock(code, COLLECT_KEEP_ARM, "收旗但留闸那一档")
        assertEquals(
            "「收旗不拆闸」那一档里 `cancelRestoreGuard(` 必须 0 处（实到 " + occurrences(keepGuard, "cancelRestoreGuard(") +
                " 枚）：还在飞的那一枚 WebView 只剩这道闸会替它拆，闸一拆它就成了挂在单例外的孤儿",
            0,
            occurrences(keepGuard, "cancelRestoreGuard("),
        )
        assertEquals(
            "而那一档仍必须收旗（`restoreInFlight = false` 恰好一枚，实到 " + occurrences(keepGuard, "restoreInFlight = false") +
                " 枚）：旗不收，restore() 此后恒在 if (restoreInFlight) return 那一档静默早退",
            1,
            occurrences(keepGuard, "restoreInFlight = false"),
        )
        // 旗的收口册子：全文件只有这几处
        assertEquals(
            "全文件 `restoreInFlight = false` 恰好 4 枚（clear／abandonRestore／retain 的两枚收下档）、" +
                "`restoreInFlight = true` 恰好 1 枚（restore 置旗）、`cancelRestoreGuard()` 调用恰好 3 枚 " +
                "（加定义那一行是 4 枚）—— 实到 " + occurrences(code, "restoreInFlight = false") + " / " +
                occurrences(code, "restoreInFlight = true") + " / " + occurrences(code, "cancelRestoreGuard()"),
            listOf(4, 1, 4),
            listOf(
                occurrences(code, "restoreInFlight = false"),
                occurrences(code, "restoreInFlight = true"),
                occurrences(code, "cancelRestoreGuard()"),
            ),
        )
        val release = balancedBlock(code, "fun releaseForMemory()", "releaseForMemory 的函数体")
        for (needle in listOf("restoreInFlight", "cancelRestoreGuard")) {
            assertEquals(
                "releaseForMemory 体内「$needle」必须 0 处（实到 " + occurrences(release, needle) +
                    " 枚）：它在 sessionWebView == null 时整段早退，而那正是恢复在飞的状态 —— 它插不进这一族，" +
                    "硬加一手只会与超时闸抢同一枚旗（在册那五条里「releaseForMemory 零处 restoreInFlight」" +
                    "这一条的裁定见本卡回执：不是漏做，是归口）",
                0,
                occurrences(release, needle),
            )
        }
        assertEquals(
            "超时闸那一问的判据必须是身份比较（`if (sessionWebView !== web) abandonRestore(web, \"加载超时\")` 恰好一枚，" +
                "实到 " + occurrences(code, GUARD_IDENTITY) + " 枚）：旗现在会被 retain 提前收掉，" +
                "判据若仍吃那枚旗就等于把闸举废",
            1,
            occurrences(code, GUARD_IDENTITY),
        )
    }

    // ─────────────── ⑦ 单调钟的接线 ───────────────

    @Test
    fun `退避读的是单调钟 取钟住在调用点 哨兵不在调用点另写`() {
        val code = blankComments(source(WEB_SESSION))
        assertEquals(
            "`SystemClock.elapsedRealtime()` 在 `$WEB_SESSION` 恰好两枚（abandonRestore 写读数、restore 读钟；" +
                "实到 " + occurrences(code, READ_CLOCK) + " 枚）：多一枚就是有人在第三个地方自己取钟",
            2,
            occurrences(code, READ_CLOCK),
        )
        assertEquals(
            "全文件 `System.currentTimeMillis` 必须 0 处（实到 " + occurrences(code, "System.currentTimeMillis") +
                " 枚）：墙上钟被往回拨就是那一枚不夹下限的负差值 —— 恒早退且一句话不说",
            0,
            occurrences(code, "System.currentTimeMillis"),
        )
        for ((needle, label) in listOf(
            SENTINEL_ALIAS to "哨兵别名那一行（调用点只引用不另写负数）",
            FIELD_DECL to "字段声明那一行（初始值交的是哨兵，不是刻度起点那一枚零）",
            WINDOW_CONST to "退避窗口常数",
        )) {
            assertEquals(
                "$label 逐字节恰好一枚「$needle」（实到 " + occurrences(code, needle) + " 枚）：" +
                    "改成自己写的负数或零，刻度与判据两头就各有一套「从没失败过」",
                1,
                occurrences(code, needle),
            )
        }
        assertEquals(
            "`$BACKOFF_GATE_CALL` 恰好一枚（实到 " + occurrences(code, BACKOFF_GATE_CALL) +
                " 枚）：两处判据 = 两把尺各判各的",
            1,
            occurrences(code, BACKOFF_GATE_CALL),
        )
        val restoreBody = balancedBlock(code, "fun restore(activity: android.app.Activity", "restore 的函数体")
        assertEquals(
            "restore 体内裸 `-1L` 必须 0 处（实到 " + occurrences(restoreBody, "-1L") +
                " 枚）：那一枚是「单调钟取不出负数」那一侧的专属值，只在**内核**里定义一次，" +
                "也不许被当成一枚时刻念",
            0,
            occurrences(restoreBody, "-1L"),
        )
        // 四档分派逐枚在场：压掉那一档必须早退并留痕，倒退那一档必须单独说一句
        for (arm in BACKOFF_ARMS) {
            assertEquals(
                "档位分支「$arm」恰好一枚（少一枚就是那一档在代码里没有自己的处置）：实到 " +
                    occurrences(restoreBody, arm),
                1,
                occurrences(restoreBody, arm),
            )
        }
        val suppressed = balancedBlock(restoreBody, BACKOFF_ARMS.first(), "退避窗口之内那一档")
        assertTrue(
            "压掉那一档必须既留痕又早退（实到：" + suppressed.replace('\n', ' ') + "）：只留痕不早退就是白折一档",
            suppressed.contains("Log.i(") && suppressed.contains("return"),
        )
        assertEquals(
            "读数倒退那一档必须逐字节是「一枚 Log.w 而没有块」—— 也就是**不早退**那一形" +
                "（「$INVERTED_ARM」恰好一枚，实到 " + occurrences(restoreBody, INVERTED_ARM) +
                " 枚）：把它改成带 return 的块就是拿「递错了尺」冒充「刚刚失败过」，那一格的病会原样回来",
            1,
            occurrences(restoreBody, INVERTED_ARM),
        )
        assertEquals(
            "退避分派必须四档逐枚在场（实到 " + occurrences(restoreBody, "is RestoreBackoffWindow.") +
                " 枚 is 档）：少一枚就是某一档在代码里没有自己的处置",
            4,
            occurrences(restoreBody, "is RestoreBackoffWindow."),
        )
    }

    // ─────────────── ⑧ 登录页侧：上缴带来源 判据不外溢 ───────────────

    @Test
    fun `六枚上缴各写来源 但没有一枚自己写出身判据`() {
        val login = dropImports(blankComments(source(LOGIN_SCREEN)))
        assertEquals(
            "`$RETAIN_CALL` 恰好五枚（实到 " + occurrences(login, RETAIN_CALL) +
                " 枚）：另外一枚长在「取消导入」那一支里是多行写法，枚数由下面一格一起封顶 —— " +
                "本卡的收口在 retain 体内，登录页不该搬进任何出身判据，但这一手的**位置与次序**" +
                "在册《SemesterGateOrderingGuardTest》里逐枚钉着（T378④ 同批重钉到新版面）",
            5,
            occurrences(login, RETAIN_CALL),
        )
        assertEquals(
            "`$RETAIN_HEAD` 在登录页恰好六枚（单行五枚 ＋ 多行那一枚；实到 " + occurrences(login, RETAIN_HEAD) +
                " 枚）：长出第七枚就是有人在不写来源的地方私下上缴",
            6,
            occurrences(login, RETAIN_HEAD),
        )

        assertEquals(
            "来源枚举在登录页恰好六枚（每枚调用点各写一次；实到 " + occurrences(login, SOURCE_ARG) +
                " 枚）：少一枚就是有一枚调用点退回了「谁来都收下」的写法 —— 那正是崩溃重建那一格的病根",
            6,
            occurrences(login, SOURCE_ARG),
        )
        assertEquals(
            "恢复链那一枚来源在会话文件里恰好一枚「$RESTORE_CHAIN_CALL」（实到 " +
                occurrences(blankComments(source(WEB_SESSION)), RESTORE_CHAIN_CALL) + " 枚）：两枚就是两个来路共用一句话",
            1,
            occurrences(blankComments(source(WEB_SESSION)), RESTORE_CHAIN_CALL),
        )
        assertEquals(
            "登录页里 `eduDocumentVerdict` 仍恰好一枚（就绪闸门；实到 " + occurrences(login, "eduDocumentVerdict") +
                " 枚）：本卡的收口在 retain 体内，**不许**把出身判据外溢到六枚调用点各写一遍" +
                "（在册《EduWebOriginSingleRulerGuardTest》第②格同格互为备份）",
            1,
            occurrences(login, "eduDocumentVerdict"),
        )
    }

    // ─────────────── ⑨ 运行时对账：形状与档位认得同一套东西 ───────────────

    @Test
    fun `运行时那一侧也认得四档 题眼格仍是放行与拒收`() {
        val previousTiers = listOf(
            SessionRetainGate.decide("https://byxt.buaa.edu.cn/", RetainSource.LoginScreen, false, false, false),
            SessionRetainGate.decide("https://byxt.buaa.edu.cn/", RetainSource.LoginScreen, true, false, false),
            SessionRetainGate.decide("https://byxt.buaa.edu.cn/", RetainSource.LoginScreen, true, true, false),
            SessionRetainGate.decide("about:blank", RetainSource.LoginScreen, true, false, false),
        ).map { it.previousWebView }
        assertEquals("四枚旧实例档必须互不相同（实到 $previousTiers）：", 4, previousTiers.distinct().size)
        val flags = listOf(
            SessionRetainGate.decide("https://byxt.buaa.edu.cn/", RetainSource.LoginScreen, false, false, false),
            SessionRetainGate.decide("https://byxt.buaa.edu.cn/", RetainSource.LoginScreen, false, false, true),
            SessionRetainGate.decide("https://byxt.buaa.edu.cn/", RetainSource.CookieRestore, false, false, true),
            SessionRetainGate.decide("about:blank", RetainSource.LoginScreen, false, false, true),
        ).map { it.singleFlight }
        assertEquals("四枚旗与闸的档必须互不相同（实到 $flags）：", 4, flags.distinct().size)
        assertEquals(
            "题眼格：about:blank 上缴而手里有活会话，今天必须是拒收档（实到 " +
                SessionRetainGate.decide("about:blank", RetainSource.LoginScreen, true, false, false)
                    .javaClass.simpleName + "）：变成 Accepted 就是本卡第一条病复发",
            "Refused",
            SessionRetainGate.decide("about:blank", RetainSource.LoginScreen, true, false, false)
                .javaClass.simpleName,
        )
        assertEquals(
            "退避题眼格：哨兵读数 + 开机 1 秒，必须放行而不是压掉（台账 #496 复发探测器）：",
            "GrantedNeverFailed",
            RestoreBackoffWindowGate.decide(1_000L, RestoreBackoffSentinel.NEVER_FAILED_MILLIS, 60_000L)
                .javaClass.simpleName,
        )
        assertEquals(
            "倒退题眼格：now < last 必须单独成档而不是「窗口之内」（不夹下限那一形的复发探测器）：",
            "GrantedClockInverted",
            RestoreBackoffWindowGate.decide(10_000L, 460_000L, 60_000L).javaClass.simpleName,
        )
        assertEquals(
            "枚举档位枚数（内核自己数）：旧实例 4／旗与闸 4／退避 4 —— 少一枚就是那一档被并掉了：",
            listOf(4, 4),
            listOf(PreviousWebView.values().size, RestoreSingleFlight.values().size),
        )
        assertTrue(
            "退避四档在运行时确实四枚（实到 " +
                listOf(
                    RestoreBackoffWindow.GrantedNeverFailed::class,
                    RestoreBackoffWindow.GrantedAfterWindow::class,
                    RestoreBackoffWindow.GrantedClockInverted::class,
                    RestoreBackoffWindow.SuppressedWithinWindow::class,
                ).distinct().size + " 枚）：",
            listOf(
                RestoreBackoffWindow.GrantedNeverFailed::class,
                RestoreBackoffWindow.GrantedAfterWindow::class,
                RestoreBackoffWindow.GrantedClockInverted::class,
                RestoreBackoffWindow.SuppressedWithinWindow::class,
            ).distinct().size == 4,
        )
    }

    // ─────────────── ⑩ 反套套逻辑：探测器认得改前那一形 ───────────────

    /**
     * 探测器（`decisionPrecedesStateWrite`）在盘上真身那一份必须判「合规」，
     * 在手写的改前那一形必须判「违规」—— 判反或空转都当场红，
     * 于是第 ③ 格那句「判据排在换引用之前」不是一句自我实现的话。
     */
    @Test
    fun `探测器认得改前那一形 也不冤枉盘上现量`() {
        val oldShape = """
            fun retain(webView: WebView, activity: android.app.Activity? = null) {
                val old = sessionWebView
                sessionWebView = webView
                lastUrl = webView.url
                moveToHiddenHost(webView, activity)
                if (old != null && old !== webView) {
                    runCatching { old.destroy() }
                }
                cancelRestoreGuard()
            }
        """.trimIndent()
        assertEquals(
            "探测器必须把**改前那一形**判成「状态写在判据之前」（实到 " + decisionPrecedesStateWrite(oldShape) +
                "）：判成 true 说明它空转，第 ③ 格就是假的绿",
            false,
            decisionPrecedesStateWrite(oldShape),
        )
        val onDisk = balancedBlock(blankComments(source(WEB_SESSION)), "fun retain(webView: WebView", "retain 的函数体")
        assertEquals(
            "同一枚探测器必须把**盘上现量**判成合规（实到 " + decisionPrecedesStateWrite(onDisk) +
                "）：判成 false 说明它把正确的形状也一起杀了，第 ③ 格红的时候没人知道该信谁",
            true,
            decisionPrecedesStateWrite(onDisk),
        )
        assertEquals(
            "缺一枚判据调用时探测器必须回 null（答不出 ≠ 次序对）。实到 " +
                decisionPrecedesStateWrite("fun retain(webView: WebView, a: Any?) { sessionWebView = webView }"),
            null,
            decisionPrecedesStateWrite("fun retain(webView: WebView, a: Any?) { sessionWebView = webView }"),
        )
    }

    /** 判据（内核调用）是否排在 `sessionWebView =` 与 `lastUrl =` 两枚状态写入之前；任一枚缺席回 null */
    private fun decisionPrecedesStateWrite(body: String): Boolean? {
        val decide = body.indexOf(RETAIN_GATE_CALL)
        val swap = body.indexOf("sessionWebView = webView")
        val write = body.indexOf("lastUrl = webView.url")
        if (decide < 0 || swap < 0 || write < 0) return null
        return decide < swap && decide < write
    }

    // ---------------- 源码核对小工具（与同族守卫一个刀法：读不到就抛） ----------------

    private fun source(relative: String): String =
        normalizeNewlines(File(findMainJavaDir(), relative).readText())

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

    /** 工作树是 CRLF（core.autocrlf=true），针里的换行统一按 LF 写 */
    private fun normalizeNewlines(text: String): String = text.replace("\r\n", "\n")

    private fun dropImports(code: String): String = code.lines()
        .joinToString("\n") { if (it.trimStart().startsWith("import ")) " ".repeat(it.length) else it }

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

    /**
     * 把 `//` 与块注释抹成空格（字符串保留）。
     *
     * 形状口径（在册那条）：这一族**不许**用「字符数组 ＋ 包成 String」的写法 ——
     * 那会顶高在册 `CommentStrippingHelperShapeGuardTest` 的四枚写死值（三格同红，过滤门禁盖不住）；
     * StringBuilder ＋ replace ＋ lines/joinToString 才是合规范式。
     */
    private fun blankComments(src: String): String {
        val out = StringBuilder(src)
        var block = out.indexOf("/*")
        while (block >= 0) {
            val end = out.indexOf("*/", block + 2)
            if (end < 0) break
            out.replace(block, end + 2, " ")
            block = out.indexOf("/*")
        }
        val text = out.toString()
        return text.lines().joinToString("\n") { line ->
            val slash = line.indexOf("//")
            if (slash >= 0) line.substring(0, slash) else line
        }
    }

    /** 从锚点那一句起按花括号配平取整块（含锚点自己）；锚点找不到就抛 */
    private fun balancedBlock(code: String, anchor: String, label: String): String {
        val at = code.indexOf(anchor)
        assertTrue("$label 的锚点没找到（改名/挪家要回来重钉本守卫）：「$anchor」", at >= 0)
        val open = code.indexOf('{', at)
        assertTrue("$label 找不到块的开括号：「$anchor」", open >= 0)
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
        throw IllegalStateException("$label 的花括号没配平：「$anchor」")
    }

    private companion object {
        const val WEB_SESSION = "com/buaa/schedule/data/import/BuaaWebSession.kt"
        const val LOGIN_SCREEN = "com/buaa/schedule/ui/importing/BuaaLoginScreen.kt"
        const val RETAIN_GATE = "com/buaa/schedule/domain/session/SessionRetainGate.kt"
        const val BACKOFF_GATE = "com/buaa/schedule/domain/session/RestoreBackoffWindowGate.kt"

        // ── 判据调用与分派那一档 ──
        const val RETAIN_GATE_CALL = "SessionRetainGate.decide("
        const val BACKOFF_GATE_CALL = "RestoreBackoffWindowGate.decide("
        const val REFUSED_ARM = "is SessionRetainVerdict.Refused -> {"
        const val CANDIDATE_IS_PREVIOUS_ARM = "PreviousWebView.CandidateIsThePrevious -> Unit"
        const val COLLECT_BOTH_ARM = "RestoreSingleFlight.CollectFlagAndCancelGuard -> {"
        const val COLLECT_KEEP_ARM = "RestoreSingleFlight.CollectFlagKeepGuardArmed -> {"
        const val GUARD_IDENTITY = "if (sessionWebView !== web) abandonRestore(web, \"加载超时\")"

        // ── 五枚病灶原文（代码 0 处／含注释恰好 1 处） ──
        val DISEASES = listOf(
            "runCatching { old.destroy() }",
            "if (now - lastRestoreFailureAt < RESTORE_RETRY_INTERVAL_MS) return",
            "@Volatile private var lastRestoreFailureAt: Long = 0L",
            "val now = System.currentTimeMillis()",
            "if (restoreInFlight && sessionWebView == null) abandonRestore(web, \"加载超时\")",
        )

        // ── 单调钟那一族的逐字节锚点 ──
        const val READ_CLOCK = "SystemClock.elapsedRealtime()"
        const val SENTINEL_ALIAS =
            "private const val NEVER_FAILED_MILLIS = RestoreBackoffSentinel.NEVER_FAILED_MILLIS"
        const val FIELD_DECL =
            "@Volatile private var lastRestoreFailureAtElapsedRealtime: Long = NEVER_FAILED_MILLIS"
        const val WINDOW_CONST = "private const val RESTORE_RETRY_INTERVAL_MS = 60_000L"
        const val SUPPRESSED_ARM = "is RestoreBackoffWindow.SuppressedWithinWindow -> {"
        const val INVERTED_ARM = "is RestoreBackoffWindow.GrantedClockInverted -> Log.w("
        val BACKOFF_ARMS = listOf(SUPPRESSED_ARM, INVERTED_ARM)

        // ── 登录页侧 ──
        const val RETAIN_HEAD = "BuaaWebSession.retain("
        const val RETAIN_CALL = "BuaaWebSession.retain(web, RetainSource.LoginScreen, context.findActivity())"
        const val RETAIN_CALL_MULTILINE = "BuaaWebSession.retain(\n                                            web,\n                                            RetainSource.LoginScreen,\n                                            context.findActivity(),"
        const val SOURCE_ARG = "RetainSource.LoginScreen"
        const val RESTORE_CHAIN_CALL = "retain(view, RetainSource.CookieRestore, activity)"

        // ── 内核纯度 ──
        const val BOOLEAN_RETURN = "): Boolean"
        const val PURITY_PROMISE_RETAIN = "不读 `WebView`、不读 `Build.VERSION`、不读权限、不读时钟、不查库"
        const val PURITY_PROMISE_BACKOFF = "不读时钟、不读时区、不读权限、不读 `Build.VERSION`"
        val FORBIDDEN_READS = listOf(
            "SystemClock",
            "elapsedRealtime",
            "currentTimeMillis",
            "Instant.now",
            "LocalDate.now",
            "ZoneId",
            "TimeZone",
            "Build.VERSION",
            "SDK_INT",
            "checkSelfPermission",
            "Context",
            "WebView",
            "Activity",
            "Room",
        )
    }
}
