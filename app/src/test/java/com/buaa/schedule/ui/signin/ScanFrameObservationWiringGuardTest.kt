package com.buaa.schedule.ui.signin

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「空白原文不可能被计成 `CodeReadable`」的接线守卫（T88 / 台账 #132）。
 *
 * T87 收的是**递交**那一头（空白原文不再进状态机），本卡收的是**观测**那一头：
 * `QrCodeAnalyzer.noteFrameRung` 改前用 `code.rawValue != null` 判"这帧读到了东西"，
 * 于是装机实测那种每帧 `len=0` 的误检被数成"读到一枚有原文的码"，两个后果都是实测的
 * （151.4 s / 1,389 帧那一窗口，探针未入库）：
 * - 检测驱动的缩放阶梯被按住 —— `tooSmallStreak` 实测**恒为 0**，一帧都没攒过；
 * - `retryableStreak` 每帧清零 —— 第二引擎（zxing-cpp 兜底）实测 **151 秒 0 发火**，
 *   这就是 T66/T67 之后"这台模拟器永远叫不醒兜底"那笔账的成因。
 *
 * 本守卫钉四件事：
 *
 * ① **接线形状**：调用点不再自己判"读到了"，只递观测；判档那一棵树（[frameCodeRung]）
 *    与兜底的触发判据（[retryableRung]）本卡一个字没动 —— 修法只是把尺子交出去。
 * ② **一把尺子**：[frameSymbolCounts] 里唯一的"空白"判据就是对 T87 那颗
 *    [decodingAdmission] 的一次委托；本文件不许长出第二把 `isBlank` / `!= null` / 长度门槛
 *    （仓库最忌讳的就是同一条空白尺子写两遍，两把迟早不一致）。
 * ③ **反向钉**：文案那颗 [scanFrameAidText] 所在文件、准入内核、兜底壳三份逐字节未动
 *    —— 本卡不许靠改措辞把新出现的提示糊过去，也不许靠改兜底那道 `isNullOrBlank` 少发几火。
 * ④ **实测账可在 JVM 复算**：把装机那 1,450 帧（框短边按实测直方图）整窗口在 JVM 里跑一遍，
 *    改前/改后各一遍。这是 ② 那条决定（不给触发判据加第二条）的可复算依据：
 *    **补解 3 发封顶**（实测逐字 zxing-cpp 计时 6 / 5 / 12 ms），改前 0 发。
 *    顺带钉住那条被实测换掉因果的账：改后阶梯仍不抬，但原因从"被误判成读到"
 *    变成"那面棋盘格的框正好在 291 px 阈值两侧抖（实测 21.2% 在线下）"。
 *
 * 手法沿用 [ScanBlankDecodingWiringGuardTest] / [ScanSecondEngineWiringGuardTest]：
 * 读源码文本、匹配前先抹注释、找不到锚点就抛（静默跳过等于没有守卫）。
 */
class ScanFrameObservationWiringGuardTest {

    /** ①a 调用点：只递观测，不再自己判"读到了" */
    @Test
    fun theCallSiteNoLongerJudgesReadabilityItself() {
        val analyzer = balancedBlock(withoutComments(readMainSource(SCAN_SCREEN_FILE)), "private class QrCodeAnalyzer(")
        val note = balancedBlock(analyzer, "private fun noteFrameRung(")
        // 本卡的头号反向钉：那把错的尺子不许以任何形态回来
        assertFalse("调用点又开始用 rawValue != null 判\"读到了\"（空白原文会重新被数成 CodeReadable）：\n$note", note.contains("rawValue != null"))
        assertFalse("调用点自己写了空白判据（第二把尺子）：\n$note", note.contains(".isBlank()") || note.contains(".isEmpty()"))
        assertTrue("帧观测不再走内核那把计数（frameSymbolCounts）：\n$note", note.contains("frameSymbolCounts("))
        assertEquals("数符号的调用点只能一处：", 1, occurrences(note, "frameSymbolCounts("))
        assertEquals("每枚符号的观测只能在一处构造：", 1, occurrences(note, "FrameSymbol("))
        // T65 ②d 的靶子不许在改写中被弄丢：紧凑测量仍从帧里抠
        assertTrue("不再从 Barcode 上抠原文（②d 的靶子）：\n$note", note.contains("code.rawValue"))
        assertTrue("不再从 Barcode 上抠框（②d 的靶子）：\n$note", note.contains("code.boundingBox"))
        assertTrue("框缺失不再递内核的哨兵：\n$note", note.contains("NoCandidateBoxShortEdgePx"))
        assertTrue("判档仍走内核：\n$note", note.contains("frameCodeRung(") && note.contains("advanceScanAssist("))
        // 尺子只在内核那一处：调用点连"准入"两个字都不该出现
        assertEquals("调用点自己调准入判据（判档与递交就该各数一遍了）：", 0, occurrences(note, "decodingAdmission("))
        // 空白档一个字都不许写进判死/闸门那本账（T87 ③ 同一条纪律，本卡照抄）
        for (
            banned in listOf(
                "health", "noteDecodeFailed", "scannerWorking", "awaitingUserAction", "giveUp",
                "handled = ", "valuelessCodes", "DecoderFrameAction", "Log.",
            )
        ) {
            assertFalse("帧观测开始写判死/闸门那本账，或按帧留痕了（$banned）：\n$note", note.contains(banned))
        }
    }

    /** ①b 内核那一侧：计数与判档的次序不许漂，空白判据只是一次委托 */
    @Test
    fun theCountingKernelDelegatesTheOneBlankRuler() {
        val code = withoutComments(readMainSource(FRAME_LEDGER_FILE))
        val count = balancedBlock(code, "internal fun frameSymbolCounts(")
        assertTrue(
            "数符号不再委托 T87 那颗准入判据（那就是第二把空白尺子的出生地）：\n$count",
            count.contains("decodingAdmission(symbol.rawValue, DecodingSource.CameraFrame).admitted"),
        )
        for (banned in listOf(".isBlank()", ".isEmpty()", "trim()", "!= null", "== null", "length <", "length >")) {
            assertFalse("内核自己另立了一把空白/长度尺子（$banned）：\n$count", count.contains(banned))
        }
        assertTrue("空白原文那一支不再落 candidates（本卡的修法本体）：\n$count", count.contains("candidates++"))
        assertTrue("读到了的那一支仍然只数 readable：\n$count", count.contains("readable++"))
        assertTrue("框不再被带上（不可信档就没了依据）：\n$count", count.contains("symbol.boxShortEdgePx > largestEdge"))
        // 靶子：判档那棵树与阶梯那颗没被顺手改写（真改了上面几条也照样绿）
        assertTrue("判档本体不在了：", code.contains("if (readableCodeCount > 0) return FrameCodeRung.CodeReadable"))
        assertTrue("阶梯的可重试连击判据不在了：", code.contains("if (retryableRung(rung)) state.retryableStreak + 1L else 0L"))
        // FrameSymbol 必须是普通类：data class 的 toString() 带着原文，哪天拼进取证行就是漏值
        assertTrue("FrameSymbol 不再是普通类（data class 会把原文带进 toString）：", code.contains("internal class FrameSymbol("))
        assertFalse("FrameSymbol 被换成了 data class：", code.contains("data class FrameSymbol"))
        assertTrue("哨兵常量不在内核里（两处各写 -1 就是两份真相）：", code.contains("internal const val NoCandidateBoxShortEdgePx = -1"))
    }

    /** ② 新增那颗计数函数保持纯 JVM：零 import、零时钟、不认识解码器与相机类型 */
    @Test
    fun theNewCountingKernelStaysPureJvm() {
        val raw = readMainSource(FRAME_LEDGER_FILE)
        val code = withoutComments(raw)
        val imports = code.lines().filter { it.trim().startsWith("import ") }
        assertTrue("$FRAME_LEDGER_FILE 里出现了 import，这段判据就到不了 JVM：\n$imports", imports.isEmpty())
        for (
            banned in listOf(
                "android.", "androidx.", "Log.", "Build.", "SystemClock", "System.currentTimeMillis",
                "currentTimeMillis", "ImageProxy", "Barcode", "CameraX", "ProcessCameraProvider", "ZoomState",
            )
        ) {
            assertFalse("帧观测内核自己去碰了设备/时钟/解码器（$banned）—— 这些必须是参数：", code.contains(banned))
        }
        assertTrue("内核文件是空的？", code.length > 3_000)
        for (entry in listOf("internal fun frameSymbolCounts(", "internal class FrameSymbol(", "internal class FrameSymbolCounts(")) {
            assertTrue("内核少了 $entry 这一档：", code.contains(entry))
        }
    }

    /**
     * ③ 反向钉：判档树、兜底触发判据、措辞与准入内核本卡都没动。
     *
     * 区域比对而不是整文件比对：本卡在 [ScanCameraAidPolicy] / [ScanSecondEnginePolicy] 里
     * 只加了注释与新函数，判档那一段与触发那一段必须逐字节等于起点。
     * 另外三份（文案、准入内核、兜底壳）整文件逐字节 —— 本卡不许把新出现的提示改哑，
     * 也不许把兜底那道空白过滤改松来少发几火。
     */
    @Test
    fun theRungTreeAndTheFallbackTriggerWereNotRewritten() {
        assertRegionUnchanged(FRAME_LEDGER_FILE, "internal fun frameCodeRung(", "internal val ZoomLadderRatios")
        assertRegionUnchanged(SECOND_ENGINE_FILE, "internal fun retryableRung(", "internal const val SecondEngineStreakFrames")
        assertRegionUnchanged(SECOND_ENGINE_FILE, "internal fun secondEngineDecision(", "internal fun secondEngineAfterFire")
        for (relative in listOf(STATUS_FILE, ADMISSION_FILE, SHELL_FILE)) {
            val path = "$MAIN_PREFIX/$relative"
            val baseline = gitShow(T88_BASELINE, path)
            check(baseline != null) { "git 跑不动或基线取不到（$path@$T88_BASELINE），反向钉无从核对" }
            assertEquals(
                "$relative 相对本卡起点被改过了：本卡只动观测侧的数法，" +
                    "文案、准入判据与兜底壳都不许动（改措辞把提示改哑、或改空白过滤少发火，都是本卡要避免的那类假绿）",
                normalizeNewlines(baseline),
                normalizeNewlines(File(findMainJavaDir(), relative).readText()),
            )
        }
        // 靶子：这三份文件确实各自管着本卡不许碰的那件事
        assertTrue("靶子丢了：文案那颗函数还在？", gitShow(T88_BASELINE, "$MAIN_PREFIX/$STATUS_FILE")!!.contains("internal fun scanFrameAidText("))
        assertTrue("靶子丢了：准入判据还在？", gitShow(T88_BASELINE, "$MAIN_PREFIX/$ADMISSION_FILE")!!.contains("internal fun decodingAdmission("))
        assertTrue("靶子丢了：兜底那道空白过滤还在？", gitShow(T88_BASELINE, "$MAIN_PREFIX/$SHELL_FILE")!!.contains("!it.text.isNullOrBlank()"))
    }

    /**
     * ④a 装机那 1,450 帧在 JVM 里整窗口复算：改前按住阶梯与兜底，改后兜底 3 发封顶。
     *
     * 框短边用的是**实测直方图**（284:4 286:9 287:25 289:131 290:138 291:4 292:218 293:365
     * 296:418 299:18 300:114 303:6，合计 1,450，中位 293），顺序按"每档轮转一遍"排 ——
     * 与实机一样在 291 px 阈值两侧抖。判据侧只有一句区别：数符号时用不用那把错的尺子。
     */
    @Test
    fun theMeasuredEmulatorWindowReproducesInTheJvm() {
        val edges = measuredFrameEdges()
        assertEquals("复算用的帧数与装机读数对不上：", 1_450, edges.size)
        assertEquals("复算用的\"太小\"帧数与装机对不上（21.2%）：", 307, edges.count { it < MinUsefulCandidateBoxPx })

        val before = runWindow(edges, blankRuler = false)
        val after = runWindow(edges, blankRuler = true)

        // 改前：每一帧都被数成"读到了码"
        assertEquals("改前那种数法仍然把帧判成 CodeReadable（反向钉失效）：", 1_450, before.readableFrames)
        assertEquals("改前缩放连击是攒了的？装机实测恒为 0：", 0L, before.maxTooSmallStreak)
        assertEquals("改前可重试连击是攒了的？装机实测恒为 0：", 0L, before.maxRetryableStreak)
        assertEquals("改前兜底发过火（装机 151 秒实测 0 发，本卡要避免的正是这条被修好）：", 0, before.fires)
        assertEquals("改前缩放命令一发都没发过：", 0, before.zoomCommands)

        // 改后：一帧都不再是 CodeReadable，兜底按既有节流发火，且 3 发封顶
        assertEquals("改后仍有帧被判成 CodeReadable（空白原文又溜回读到了那一侧）：", 0, after.readableFrames)
        assertEquals("改后的档位分布与装机对不上（CodeTooSmall 帧数）：", 307, after.tooSmallFrames)
        assertEquals("改后的档位分布与装机对不上（CodeUndecodable 帧数）：", 1_143, after.undecodableFrames)
        assertEquals("改后可重试连击必须逐帧攒满（装机探针末帧 1,450）：", 1_450L, after.maxRetryableStreak)
        assertEquals("改后缩放命令仍一发射不出（每段\"太小\"连击都不够一档的预算）：", 0, after.zoomCommands)
        // 本卡 ② 的那笔决定：发火率不是"每帧"，而是三次失手即整轮封口
        assertEquals("兜底发火次数不再是 3（本卡判 (c) 的全部依据就是这笔账）：", SecondEngineGiveUpAfterMisses, after.fires)
        assertTrue("兜底的终局不是判死那一档（三次失手就该闭嘴，不是继续按帧双解）：$after", after.lastDecision is SecondEngineDecision.GaveUp)
        assertTrue("第一发不许早于连击门槛那一帧（$SecondEngineStreakFrames 帧）：", after.firstFireFrame >= SecondEngineStreakFrames)
        assertTrue("两发补解之间不许短于 $SecondEngineFrameGap 帧：", after.gapBetweenFires.isEmpty() || after.gapBetweenFires.min() >= SecondEngineFrameGap)
        // 复算与装机那一条读数**不同**的地方要说清：装机 tooSmallStreak 恒为 0 是因为这台
        // 没有缩放控制（advanceScanAssist 那一支连计都不计），不是因为框的分布；这里模拟的是
        // "一台有缩放控制的设备"，于是攒到的是每轮 5 帧的连击 —— 仍不够抬一档的预算。
        assertTrue("复算里的太小连击不该攒满一档（$ZoomStepFrames 帧）：${after.maxTooSmallStreak}", after.maxTooSmallStreak < ZoomStepFrames)
    }

    /**
     * ④b 阶梯"被按住"这件事的反向钉：把同一批误检帧按"框一直太小"排一次，
     * 阶梯必须真的走完四档再回滚 —— 改前那种数法下它连一帧都不攒。
     *
     * 这一档不是装机读数（那面棋盘格的框在阈值两侧抖），是**构造**：如果哪天误检的框
     * 一直小于 291 px，或者换一台真有缩放控制的设备，本卡的修法要能兑现"阶梯照走"。
     * 兜底那本账同时钉住：换序不许把 3 发封顶换成每帧双解。
     */
    @Test
    fun aConsistentlyTooSmallFalseDetectionNowClimbsTheLadder() {
        val edges = List(307) { MinUsefulCandidateBoxPx - 1 } + List(1_143) { MinUsefulCandidateBoxPx + 5 }
        val before = runWindow(edges, blankRuler = false)
        val after = runWindow(edges, blankRuler = true)
        assertEquals("改前：误检被数成读到码，阶梯连一帧都不攒：", 0, before.zoomCommands)
        assertEquals("改前：兜底也一发射不出：", 0, before.fires)
        // 顶档 + 回滚 = 五发命令（四档各一发，再连满 ZoomRollbackFrames 帧回基线一发）
        assertEquals("改后：阶梯必须爬到顶档（四档）才谈得上回滚：", ZoomLadderRatios.size, after.maxStep)
        assertTrue("改后：阶梯必须回滚并封口（抬了没用的工程定义）：$after", after.rolledBack)
        assertEquals("改后：四档加一次回滚共五发命令：", ZoomLadderRatios.size + 1, after.zoomCommands)
        assertEquals("换序不许把补解改成每帧双解（仍是三次失手封顶）：", SecondEngineGiveUpAfterMisses, after.fires)
        assertTrue("换序后终局仍是判死那一档：$after", after.lastDecision is SecondEngineDecision.GaveUp)
    }

    // ---- 复算用的窗口驱动（与 analyze 的次序一致：先按上一帧的连击决定补不补，再数这一帧） ----

    private class WindowAccount(
        val readableFrames: Int,
        val tooSmallFrames: Int,
        val undecodableFrames: Int,
        val maxTooSmallStreak: Long,
        val maxRetryableStreak: Long,
        val zoomCommands: Int,
        val maxStep: Int,
        val rolledBack: Boolean,
        val fires: Int,
        val firstFireFrame: Long,
        val gapBetweenFires: List<Long>,
        val lastDecision: SecondEngineDecision,
    )

    /**
     * @param blankRuler false = 改前调用点那把尺子（`rawValue != null` 就算读到了），
     *                   true = 改后走 [frameSymbolCounts]（空白落 candidates）
     */
    private fun runWindow(edges: List<Int>, blankRuler: Boolean): WindowAccount {
        var assist = ScanAssistState()
        var ledger = SecondEngineLedger()
        var readable = 0
        var tooSmall = 0
        var undecodable = 0
        var maxTooSmallStreak = 0L
        var maxRetryableStreak = 0L
        var zoomCommands = 0
        var maxStep = 0
        var fires = 0
        var firstFireFrame = -1L
        val fireFrames = mutableListOf<Long>()
        var decision: SecondEngineDecision = SecondEngineDecision.Waiting
        for ((index, edge) in edges.withIndex()) {
            val frame = (index + 1).toLong()
            decision = secondEngineDecision(
                ledger = ledger,
                streak = assist.retryableStreak, // 截至**上一帧**为止，与 trySecondEngine 同一口径
                frame = frame,
                codeInHand = false,
                engineUsable = true,
            )
            if (decision is SecondEngineDecision.Fire) {
                fires++
                if (firstFireFrame < 0L) firstFireFrame = frame
                fireFrames += frame
                // 装机那一窗口三发全部没解出（命中 0 行），复算照抄这个事实
                ledger = secondEngineAfterResult(secondEngineAfterFire(ledger, frame), decoded = false, usable = true)
            }
            val counts = if (blankRuler) {
                frameSymbolCounts(listOf(FrameSymbol(rawValue = "", boxShortEdgePx = edge)))
            } else {
                FrameSymbolCounts(readableCodeCount = 1, candidateCodeCount = 0, largestCandidateBoxShortEdgePx = edge)
            }
            val rung = frameCodeRung(counts.readableCodeCount, counts.candidateCodeCount, counts.largestCandidateBoxShortEdgePx)
            when (rung) {
                FrameCodeRung.CodeReadable -> readable++
                FrameCodeRung.CodeTooSmall -> tooSmall++
                FrameCodeRung.CodeUndecodable -> undecodable++
                FrameCodeRung.NothingDetected -> Unit
            }
            val outcome = advanceScanAssist(assist, rung, zoomControlAvailable = true)
            assist = outcome.state
            if (outcome.zoomRatio != null) zoomCommands++
            maxStep = maxOf(maxStep, outcome.state.stepIndex)
            maxTooSmallStreak = maxOf(maxTooSmallStreak, assist.tooSmallStreak)
            maxRetryableStreak = maxOf(maxRetryableStreak, assist.retryableStreak)
        }
        val gaps = fireFrames.zipWithNext { previous, next -> next - previous }
        return WindowAccount(
            readableFrames = readable,
            tooSmallFrames = tooSmall,
            undecodableFrames = undecodable,
            maxTooSmallStreak = maxTooSmallStreak,
            maxRetryableStreak = maxRetryableStreak,
            zoomCommands = zoomCommands,
            maxStep = maxStep,
            rolledBack = assist.rolledBack,
            fires = fires,
            firstFireFrame = firstFireFrame,
            gapBetweenFires = gaps,
            lastDecision = decision,
        )
    }

    /** 装机探针读到的框短边直方图（临时探针，探针未入库；顺序按每档轮转排，与实机一样在阈值两侧抖） */
    private fun measuredFrameEdges(): List<Int> {
        val histogram = listOf(
            284 to 4, 286 to 9, 287 to 25, 289 to 131, 290 to 138, 291 to 4,
            292 to 218, 293 to 365, 296 to 418, 299 to 18, 300 to 114, 303 to 6,
        )
        for ((edge, _) in histogram) {
            check(edge > 0) { "直方图里的短边不可能是 $edge" }
        }
        val remaining = histogram.map { it.second }.toMutableList()
        val edges = mutableListOf<Int>()
        while (remaining.any { it > 0 }) {
            for (index in histogram.indices) {
                if (remaining[index] > 0) {
                    edges += histogram[index].first
                    remaining[index] -= 1
                }
            }
        }
        return edges
    }

    /** 某个文件里一段区域相对起点逐字节未动（区域之外的注释随本卡增改，是合法的） */
    private fun assertRegionUnchanged(relative: String, startSignature: String, endSignature: String) {
        val path = "$MAIN_PREFIX/$relative"
        val baseline = gitShow(T88_BASELINE, path)
        check(baseline != null) { "git 跑不动或基线取不到（$path@$T88_BASELINE）：区域反向钉无从核对" }
        val current = File(findMainJavaDir(), relative).readText()
        val before = region(normalizeNewlines(baseline), startSignature, endSignature, path)
        val after = region(normalizeNewlines(current), startSignature, endSignature, path)
        assertEquals(
            "$relative 的 $startSignature 那一段相对本卡起点被改过了：本卡只改\"怎么数符号\"，" +
                "判档那棵树与兜底的触发判据都不许动（动了就是往被别的守卫钉着的账上再加一份真相）",
            before,
            after,
        )
        assertTrue("区域比对扫了个空档（$path 的 $startSignature）：", before.isNotBlank())
    }

    private fun region(source: String, startSignature: String, endSignature: String, path: String): String {
        val start = source.indexOf(startSignature)
        check(start >= 0) { "$path 里找不到 $startSignature：写法换过了，这条守卫要跟着改" }
        val end = source.indexOf(endSignature, start + startSignature.length)
        check(end > start) { "$path 里 $startSignature 之后找不到 $endSignature：区域边界漂了" }
        return source.substring(start, end)
    }

    // ---- 源码核对工具（与同族守卫一个刀法）----

    private fun readMainSource(relativeFromJava: String): String {
        val file = File(findMainJavaDir(), relativeFromJava)
        assertTrue("找不到 ${file.path}：文件挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText()
    }

    private fun balancedBlock(source: String, signature: String): String {
        val at = source.indexOf(signature)
        check(at >= 0) { "找不到 $signature：写法换过了，这条守卫要跟着改" }
        val open = source.indexOf('{', at)
        check(open >= at) { "$signature 之后找不到左括号" }
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(at, index + 1)
                }
            }
        }
        throw IllegalStateException("$signature 的花括号没配平")
    }

    private fun withoutComments(source: String): String {
        val out = StringBuilder(source)
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

    private fun occurrences(haystack: String, needle: String): Int {
        var count = 0
        var from = 0
        while (true) {
            val at = haystack.indexOf(needle, from)
            if (at < 0) return count
            count++
            from = at + needle.length
        }
    }

    /** `git show <rev>:<path>`；取不到（没 git / 没这个对象）返回 null，由调用方判失败而不是静默通过 */
    private fun gitShow(rev: String, path: String): String? = try {
        val process = ProcessBuilder("git", "show", "$rev:$path")
            .directory(findRepoRoot())
            .redirectErrorStream(true)
            .start()
        val out = process.inputStream.readBytes()
        if (process.waitFor() != 0) null else String(out, Charsets.UTF_8)
    } catch (io: java.io.IOException) {
        null
    }

    private fun normalizeNewlines(text: String): String = text.replace("\r\n", "\n")

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

    private fun findRepoRoot(): File {
        var dir: File? = File("").absoluteFile
        repeat(5) {
            val candidate = dir
            if (candidate != null && File(candidate, "settings.gradle.kts").isFile && File(candidate, "app/build.gradle.kts").isFile) {
                return candidate
            }
            dir = dir?.parentFile
        }
        throw IllegalStateException("找不到仓库根：当前目录 ${File("").absolutePath}")
    }

    private companion object {
        const val MAIN_PREFIX = "app/src/main/java"
        const val SCAN_SCREEN_FILE = "com/buaa/schedule/ui/signin/SpocScanScreen.kt"
        const val FRAME_LEDGER_FILE = "com/buaa/schedule/ui/signin/ScanCameraAidPolicy.kt"
        const val SECOND_ENGINE_FILE = "com/buaa/schedule/ui/signin/ScanSecondEnginePolicy.kt"
        const val ADMISSION_FILE = "com/buaa/schedule/ui/signin/ScanDecodingAdmission.kt"
        const val STATUS_FILE = "com/buaa/schedule/ui/signin/ScanUiStatus.kt"
        const val SHELL_FILE = "com/buaa/schedule/ui/signin/ZxingCppFallbackDecoder.kt"

        /** 本卡的起点（master）：反向钉按这一枚逐字节核对 */
        const val T88_BASELINE = "9e155df"
    }
}
