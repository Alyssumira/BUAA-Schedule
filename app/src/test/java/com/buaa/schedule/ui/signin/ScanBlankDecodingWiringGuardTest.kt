package com.buaa.schedule.ui.signin

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「空白原文不可能走到 `rejectScan`」的接线守卫（T87 / #107）。
 *
 * 判据本体在 [ScanDecodingAdmission] 里已经有表驱动（`ScanDecodingAdmissionTest`），
 * 这里只管五件 JVM 跑不到的事：
 *
 * ① 内核保持纯 JVM（零 import、零时钟、不认识解码器与相机类型）—— 否则这张表就跑不到；
 * ② 相机那条路：准入排在**取墙钟与提交闸门之前**，`onCode` 全 analyzer 只有一处且在准入之后
 *    （这就是"空白走不到状态机"的可核形式：唯一的递交口前面有一道 return）；
 * ③ 留痕那颗辅助函数：只记账 + 说 Info 级一句话，**一个字都不碰判死棘轮**
 *    （`health` / `noteDecodeFailed` / `scannerWorking` / `awaitingUserAction` 全不许出现 ——
 *    对着空白墙面把扫码页判死，是比弹一张假卡更坏的失效）；
 * ④ 相册那条路：同一颗判据、同一个措辞出口，不收时落的是既有那句「图里没认出二维码」；
 * ⑤ 反向钉：七档判据与状态机那些文件本卡一个字都没碰（逐字节对基点），
 *    万一有人把"收不收"改回状态机那一头，这里先红。
 *
 * 手法沿用 [ScanRejectWiringGuardTest] / [ScanSilentBranchGuardTest] / [IClassSignInWiringGuardTest]：
 * 读源码文本、匹配前先抹注释、**找不到锚点就抛**（静默跳过等于没有守卫）。
 */
class ScanBlankDecodingWiringGuardTest {

    /** ① 内核纯度：零 import、零设备符号、零时钟，函数与哨兵一枚不少 */
    @Test
    fun admissionKernelStaysPureJvmAndClockFree() {
        val code = withoutComments(readMainSource(KERNEL_FILE))
        val imports = code.lines().filter { it.trim().startsWith("import ") }
        assertTrue("$KERNEL_FILE 里出现了 import，这段判据就到不了 JVM：\n$imports", imports.isEmpty())
        for (
            banned in listOf(
                "android.", "androidx.", "Log.", "Build.", "SystemClock", "System.currentTimeMillis",
                "currentTimeMillis", "Date(", "Instant", "ImageProxy", "Barcode", "CameraX", "ProcessCameraProvider",
            )
        ) {
            assertFalse("判据本体自己去碰了设备/时钟/解码器（$banned）—— 这些必须是参数：", code.contains(banned))
        }
        assertTrue("内核文件是空的？", code.length > 1_000)
        for (
            entry in listOf(
                "fun decodingAdmission(", "fun traceBlankDecoding(", "fun decodingAdmissionTraceText(",
                "fun decodedTextSubmissionClause(", "enum class DecodingSource", "enum class DecodingBlankness",
                "enum class DecodingSubmission", "data class BlankDecodingLedger",
            )
        ) {
            assertTrue("内核少了 $entry 这一档：", code.contains(entry))
        }
        for (sentinel in listOf("BlankDecodingLogStride", "NoDecodingFrame", "SingleActionOrdinal")) {
            assertTrue("哨兵/节流常数 $sentinel 不在内核里：", code.contains(sentinel))
        }
        // 空白口径只有一把尺子：判据自己不许另立字符集/长度门槛（那是"把真码杀掉"的那条路）
        val admission = balancedBlock(code, "internal fun decodingAdmission(")
        assertTrue("准入不再吃 stdlib 的 isBlank/isEmpty（自造第三种空白定义）：\n$admission", admission.contains(".isBlank()"))
        assertTrue("准入用了 trim 后的长度（那会把长度账算成两份）：\n$admission", !admission.contains("trim()"))
        for (banned in listOf("length <", "length >", "length <=", "length >=")) {
            assertFalse("准入自己加了长度门槛（$banned）—— 长度 1 的真码就是被这一类判据杀掉的：\n$admission", admission.contains(banned))
        }
    }

    /** ②a 相机那条路：准入排在取钟与闸门之前，`onCode` 只有那一处、且在其后 */
    @Test
    fun theCameraDoorJudgesBlankTextBeforeItTouchesTheGateOrTheCallback() {
        val analyzer = analyzerBody()
        val gate = balancedBlock(analyzer, "private fun submitDecodedText(")
        assertTrue(
            "相机那条不再问准入判据（空白原文就会又走进状态机、弹那张指责用户的卡）：\n$gate",
            gate.contains("decodingAdmission(raw, DecodingSource.CameraFrame)"),
        )
        val admitted = gate.indexOf("if (!admission.admitted)")
        val clock = gate.indexOf("System.currentTimeMillis()")
        val judged = gate.indexOf("shouldSubmitScan(")
        val set = gate.indexOf("handled = ScanHandled(")
        val submit = gate.indexOf("onCode(")
        check(admitted >= 0 && clock >= 0 && judged >= 0 && set >= 0 && submit >= 0) {
            "闸门内部那五句的形状换写法了，本条要跟着改：\n$gate"
        }
        assertTrue("准入排到了取墙钟/闸门判据之后（空白原文已经被记进闸门账本了）：", admitted < clock)
        assertTrue("准入排到了 shouldSubmitScan 之后（同一份空白的冷却期账是谁在记？）：", admitted < judged)
        assertTrue("不收那一支没在置位之前 return：", admitted < set)
        assertTrue("不收那一支没在 onCode 之前 return：", admitted < submit)
        assertTrue("不收那一支没走留痕那颗辅助函数：\n$gate", gate.contains("noteBlankDecoding(admission)"))
        assertTrue(
            "不收那一支说的不是 BlankRejected（措辞会跟三种出路对不上账）：\n$gate",
            gate.contains("return DecodingSubmission.BlankRejected"),
        )
        // 唯一的递交口只有一个：准入之外没有第二条路能把原文递给状态机
        assertEquals("onCode( 不止闸门那一处了（那就是绕过准入的提交口）：", 1, occurrences(analyzer, "onCode("))
        assertEquals("相机那条的准入调用点只能一处：", 1, occurrences(analyzer, "decodingAdmission("))
        assertTrue(
            "analyze 的成功分支还在直接投原文（绕过共用闸门 = 两份真相）：\n${balancedBlock(analyzer, ".addOnSuccessListener { codes ->")}",
            balancedBlock(analyzer, ".addOnSuccessListener { codes ->").contains("submitDecodedText(raw)"),
        )
    }

    /** ②b 三种出路只有一份措辞，兜底那一行不许自己拼"被闸门压住" */
    @Test
    fun theFallbackLineSpeaksThroughTheThreeWayClause() {
        val analyzer = analyzerBody()
        val try2 = balancedBlock(analyzer, "private fun trySecondEngine(")
        assertTrue("兜底不再看闸门给的三种出路：\n$try2", try2.contains("decodedTextSubmissionClause("))
        assertFalse(
            "兜底又自己拼了一句「被提交闸门压住」（空白原文那一档会被它说成闸门的锅）：\n$try2",
            try2.contains("被提交闸门压住"),
        )
        assertEquals("出路措辞的出口全页只许一处：", 1, occurrences(withoutComments(readMainSource(SCAN_SCREEN_FILE)), "decodedTextSubmissionClause("))
    }

    /** ③ 留痕只记账 + 说话，一个字都不碰判死棘轮 */
    @Test
    fun theBlankTraceWritesNothingIntoTheRatchet() {
        val analyzer = analyzerBody()
        val note = balancedBlock(analyzer, "private fun noteBlankDecoding(")
        assertTrue("留痕没走内核的记账+节流：\n$note", note.contains("traceBlankDecoding(blankDecoding"))
        assertTrue("节流没排在说话之前（一帧一行会把 logcat 冲干净）：\n$note", note.indexOf("if (!trace.speak) return") < note.indexOf("Log.i("))
        assertTrue("那一行没走内核措辞（手拼半句就是第二份口径）：\n$note", note.contains("decodingAdmissionTraceText("))
        assertTrue("级别不是 Info（本档没有失败，Warn 会造假故障；D/V 在用户那台机器上读不到）：\n$note", note.contains("Log.i(TAG,"))
        for (banned in listOf("Log.d(", "Log.v(", "Log.e(")) {
            assertFalse("留痕级别漂了（$banned）：\n$note", note.contains(banned))
        }
        for (
            banned in listOf(
                "health", "noteDecodeFailed", "scannerWorking", "awaitingUserAction", "giveUp",
                "handled = ", "valuelessCodes",
            )
        ) {
            assertFalse("空白原文的留痕开始写判死/闸门那本账了（$banned）—— 对着空白墙面也能把扫码页判死：\n$note", note.contains(banned))
        }
        // 账本：一处声明 + 每次绑定复位
        assertEquals("留痕账本的声明处只能一处：", 1, occurrences(analyzer, "private var blankDecoding"))
        assertTrue("账本没标 @Volatile（两个写者：ML Kit 回调线程与分析线程）：$analyzer", analyzer.contains("@Volatile private var blankDecoding"))
        val mark = balancedBlock(analyzer, "fun markBindStarted(")
        assertTrue("绑定钩子没复位留痕账本（旧序号对不上新画面）：\n$mark", mark.contains("blankDecoding = BlankDecodingLedger()"))
    }

    /** ④ 相册那条路：同一颗判据、不收时落的是既有那句「图里没认出二维码」 */
    @Test
    fun theGalleryDoorUsesTheSameJudgeAndTheExistingSentence() {
        val gallery = balancedBlock(withoutComments(readMainSource(SCAN_SCREEN_FILE)), "ActivityResultContracts.PickVisualMedia(),")
        val judge = gallery.indexOf("decodingAdmission(raw, DecodingSource.GalleryImage)")
        val signIn = gallery.indexOf("viewModel.signIn(")
        check(judge >= 0 && signIn >= 0) { "相册那条的准入或递交换了写法，④ 要跟着改：\n$gallery" }
        assertTrue("相册那条先递交再判准入（顺序倒了这一档就白收）：", judge < signIn)
        assertTrue("相册那条不再报「图里没认出码」：\n$gallery", gallery.contains("viewModel.reportNoQrCode()"))
        assertTrue("不收那一支没把 raw == null 一起接住（判据是 null-safe 的，两支该走同一句话）：\n$gallery", gallery.contains("raw == null"))
        assertTrue("相册那条的留痕没走内核措辞：\n$gallery", gallery.contains("decodingAdmissionTraceText("))
        assertFalse("相册那条被卷进提交闸门了（它是一次动作，没有风暴要挡）：\n$gallery", gallery.contains("shouldSubmitScan("))
        // 一次动作没有"第几帧"，也不该有计数风暴
        assertTrue("相册那条编了帧号或自己数次数：\n$gallery", gallery.contains("NoDecodingFrame") && gallery.contains("SingleActionOrdinal"))
    }

    /** ⑤a 语义侧的反向钉：把内核真跑一遍，空白档一律不收 —— 收下来的那些才轮得到七档说话 */
    @Test
    fun everyBlankShapeIsRefusedBeforeTheSevenRungsAreEverAsked() {
        for (payload in listOf<String?>("", " ", "\n", "\t", "  \n\t ")) {
            for (source in DecodingSource.entries) {
                assertFalse("这一档空白原文还能被递交（$payload / $source）", decodingAdmission(payload, source).admitted)
            }
        }
        // 真码必须照旧收：这条链上"收得下"是唯一能签到出去的形状
        assertTrue("真码被准入杀掉了", decodingAdmission(realCode, DecodingSource.CameraFrame).admitted)
        assertTrue("真码在相册那条路上被杀掉了", decodingAdmission(realCode, DecodingSource.GalleryImage).admitted)
    }

    /**
     * ⑤b 反向钉：七档判据、状态机、闸门与棘轮那几份文件本卡一个字都没碰。
     *
     * ⚠️ T90（#134）改过这条钉法，改的是**形状**不是**松紧**：
     * [STATUS_FILE] 原来也在这份"整文件逐字节"的清单里，而 T90 被授权改的恰恰是它那一段
     * 帧观测措辞（误检帧不再被宣称"看见二维码了"）。于是这一条换成两半：
     *  1. 其余四份文件照旧整文件逐字节对 [T87_BASELINE]（本卡不许碰的就是这四份）；
     *  2. [STATUS_FILE] 改成**挖掉被授权那一段之后**逐字节对同一枚基线 —— 也就是
     *     "除了帧观测措辞那一段，这个文件相对 T87 起点仍然一个字都没动"。锚点找不到就抛，
     *     所以"把那段挪个位置再改"也红（见 [stripFrameAidSection]）。
     * 那段本身钉在哪：逐字八格（四档 × 本轮有没有读出过原文）在 `ScanUiStatusTest` ⑨a，
     * "两档都必须出声、不许整体改哑"在 ⑨b，"不许宣称看见、也不许宣称没有码"在 ⑨c，
     * 而"这一句只能由内核给、调用点一个字都不拼"由 [ScanFrameAidWordingWiringGuardTest] 钉。
     * 这里另留两枚靶子：允许动的那一段确实还长着 T90 那个形状（按证据分支、两套建议都在），
     * 否则上面那些守卫扫的就是空气。
     *
     * ⚠️ T92（#133）把同一套换法用到 [FRAME_FLOW_FILE] 上（**仍不是放松**，是被挖那段配了三枚靶子）：
     * T92 被授权改的是那颗内核头部"空转有多少帧"那一段的**读数口径**（旧文本把一个 2.80 帧/秒
     * 写成了这一页的固有属性，被 T87/T88/T91 六个读数打到 3.1–3.3 倍）。判据、措辞、取证行全文
     * 都在被挖段之外，照旧逐字节。见 [cutFrameFlowRate]。
     */
    @Test
    fun filesThisCardMustNotTouchAreByteIdenticalToBaseline() {
        for (relative in listOf(REJECT_FILE, VIEW_MODEL_FILE, GATE_FILE, RECOVERY_FILE)) {
            val path = "$MAIN_PREFIX/$relative"
            val baseline = gitShow(T87_BASELINE, path)
            check(baseline != null) { "git 跑不动或基线取不到（$path@$T87_BASELINE），反向钉无从核对" }
            assertEquals(
                "$relative 相对基点被改过了：本卡只该在解码与递交之间加一道准入，" +
                    "七档文案、状态机漏斗、闸门与棘轮都不许动",
                normalizeNewlines(baseline),
                normalizeNewlines(File(findMainJavaDir(), relative).readText()),
            )
        }
        val statusPath = "$MAIN_PREFIX/$STATUS_FILE"
        val statusBaseline = gitShow(T87_BASELINE, statusPath)
        check(statusBaseline != null) { "git 跑不动或基线取不到（$statusPath@$T87_BASELINE），反向钉无从核对" }
        val current = normalizeNewlines(File(findMainJavaDir(), STATUS_FILE).readText())
        assertEquals(
            "$STATUS_FILE 在帧观测措辞那一段之外被改过了：T90 只被授权改「画面里有码但没解开」两档的断语，" +
                "结构性降级阶梯（scanUiStatus 七档）、cameraLive 判据与相册前缀都不许动",
            normalizeNewlines(stripFrameAidSection(statusBaseline)),
            stripFrameAidSection(current),
        )
        // 靶子：允许动的那一段确实还是 T90 那个形状（少了任何一枚就说明守卫在扫空气）
        val aid = frameAidSection(current)
        assertTrue("帧观测措辞不再按「本轮读出过原文没有」分支（断语被改回无条件了）：\n$aid", aid.contains("readableCodeSeen"))
        for (anchor in listOf("看见二维码了，但它小到解不出来", "看见二维码了，但一时解不开", "还没扫出内容")) {
            assertTrue("帧观测措辞里少了锚点「$anchor」：\n$aid", aid.contains(anchor))
        }
        // 计数只看函数体：KDoc 里也**引用**过「看见二维码了」，整段去数会把注释那一次算进去
        val aidBody = balancedBlock(withoutComments(current), "internal fun scanFrameAidText(")
        assertEquals("「看见二维码了」只许出现在有证据那一支（两句）：\n$aidBody", 2, occurrences(aidBody, "看见二维码了"))
        assertEquals("无证据那一支「还没扫出内容」只许两句（两档各一句）：\n$aidBody", 2, occurrences(aidBody, "还没扫出内容"))
        // 两档**各自**按证据位分支：只留一处分支就是有一档的断语被改回无条件（⑨a 那种假绿）
        assertEquals("两档各自都要按「本轮读出过原文没有」分一次支：\n$aidBody", 2, occurrences(aidBody, "if (readableCodeSeen)"))
        assertEquals("帧观测措辞出口只能有一颗（第二份=页面自算口径）：", 1, occurrences(current, "internal fun scanFrameAidText("))
        // ---- T92（#133）：ScanFrameFlowPolicy 的"空转速率那一段"之外逐字节，钉法与上面同一套 ----
        val flowPath = "$MAIN_PREFIX/$FRAME_FLOW_FILE"
        val flowBaseline = gitShow(T87_BASELINE, flowPath)
        check(flowBaseline != null) { "git 跑不动或基线取不到（$flowPath@$T87_BASELINE），反向钉无从核对" }
        val flowCurrent = normalizeNewlines(File(findMainJavaDir(), FRAME_FLOW_FILE).readText())
        assertEquals(
            "$FRAME_FLOW_FILE 在「空转有多少帧」那一段之外被改过了：T92 只被授权改那一段的读数口径，" +
                "停帧判据（frameFlowStop）、三档措辞与那条取证行的全文（frameFlowStopLogText）都不许顺手改",
            cutFrameFlowRate(normalizeNewlines(flowBaseline), flowPath),
            cutFrameFlowRate(flowCurrent, FRAME_FLOW_FILE),
        )
        // 靶子 1：基线侧那一段确实带着被改掉的那个裸数，而且**还没有**本轮补的复现法
        //         （反过来说明挖的就是那一段，也说明基线没被偷偷换成今天）
        val baselineRateSpan = frameFlowRateSection(normalizeNewlines(flowBaseline))
        assertTrue("靶子丢了：基线侧那一段里没有旧文本写死的 2.80 —— 挖错了地方，这么比量不出任何东西",
            baselineRateSpan.contains("2.80"))
        assertTrue("基线侧那一段已经带着「$FRAME_FLOW_RECIPE_ANCHOR」：基线取错了（拿今天比今天的尺子量不出事）",
            !baselineRateSpan.contains(FRAME_FLOW_RECIPE_ANCHOR))
        // 靶子 2：工作树那一段确实还在，而且是"带条件的区间 + 复现法"那个形状
        //         （整段删掉、或改回一句没有口径的裸数，都会红在这里）
        val rateSpan = frameFlowRateSection(flowCurrent)
        for (anchor in listOf(
            "8.01", "4.73", "2.60", "2.80", FRAME_FLOW_RECIPE_ANCHOR, "logcat -d -v year",
        )) {
            assertTrue("空转速率那一段少了「$anchor」（区间、争用档、旧读数或复现法被删掉了）：\n$rateSpan", rateSpan.contains(anchor))
        }
        assertFalse(
            "空转速率又退回没有口径的裸数（「模拟器实测该页 X 帧/秒」那一形状，正是 #133 收的账）：\n$rateSpan",
            rateSpan.contains("模拟器实测该页"),
        )
    }

    /**
     * T92 被授权改动的那一段（[FRAME_FLOW_FILE] 头部"空转有多少帧"的读数）在文本里的区间。
     * 三条纪律照本文件 [stripFrameAidSection] 与 [ScanFrameAidWordingWiringGuardTest] 的 `cutT91Spans`：
     * 起始锚点必须**唯一**（不唯一就是段边界不成立）、收尾锚点找不到就抛、段长越界就抛。
     * 两个锚点本身都不在被挖的范围内 ⇒ "把那段挪个位置"或"改掉锚点"都会红。
     */
    private fun frameFlowRateRange(text: String, label: String): IntRange {
        val at = text.indexOf(FRAME_FLOW_RATE_START)
        check(at >= 0) { "$label 里找不到空转速率那一段的起始锚点「$FRAME_FLOW_RATE_START」：那段挪过家或改了措辞，钉法要跟着重看" }
        check(text.indexOf(FRAME_FLOW_RATE_START, at + 1) < 0) {
            "$label 里起始锚点「$FRAME_FLOW_RATE_START」出现两次：被挖段的边界不唯一，逐字节比较无从谈起"
        }
        val stop = text.indexOf(FRAME_FLOW_RATE_END, at)
        check(stop > at) { "$label 里那一段的收尾锚点「$FRAME_FLOW_RATE_END」找不到：段边界漂了" }
        check(stop - at in FRAME_FLOW_RATE_MIN..FRAME_FLOW_RATE_MAX) {
            "$label 里那一段长 ${stop - at} 字符，越出 [$FRAME_FLOW_RATE_MIN,$FRAME_FLOW_RATE_MAX]：不像是一段读数（钉法本身漂了）"
        }
        return at until stop
    }

    /** 只取那一段（靶子用） */
    private fun frameFlowRateSection(text: String): String = text.substring(frameFlowRateRange(text, FRAME_FLOW_FILE))

    /** 挖掉那一段，剩下的字节照比 */
    private fun cutFrameFlowRate(text: String, label: String): String = text.removeRange(frameFlowRateRange(text, label))

    /** 被授权改动那一段：帧观测措辞的 KDoc 起、到文件末尾（锚点没了就抛，不许退化成不比较） */
    private fun frameAidSection(text: String): String {
        val at = text.indexOf(FRAME_AID_ANCHOR)
        check(at >= 0) { "找不到帧观测措辞那一段的锚点「$FRAME_AID_ANCHOR」：这段挪过家或被改名，T90 的钉法要跟着重看" }
        return text.substring(at)
    }

    /** 把被授权那一段挖掉之后的文件体（挖之前的长度自证：不许挖空整份文件） */
    private fun stripFrameAidSection(text: String): String {
        val normalized = normalizeNewlines(text)
        val section = frameAidSection(normalized)
        check(section.length in 500..9_000) { "被挖掉的那一段长度是 ${section.length}，不像是一段措辞（钉法本身漂了）" }
        return normalized.replace(section, "")
    }

    // ---- 源码核对工具（与同族守卫一个刀法）----

    private fun analyzerBody(): String = balancedBlock(withoutComments(readMainSource(SCAN_SCREEN_FILE)), "private class QrCodeAnalyzer(")

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
        const val KERNEL_FILE = "com/buaa/schedule/ui/signin/ScanDecodingAdmission.kt"
        const val VIEW_MODEL_FILE = "com/buaa/schedule/ui/signin/SignInViewModel.kt"
        const val GATE_FILE = "com/buaa/schedule/ui/signin/ScanSubmissionGate.kt"
        const val RECOVERY_FILE = "com/buaa/schedule/ui/signin/ScanRecoveryPolicy.kt"
        const val FRAME_FLOW_FILE = "com/buaa/schedule/ui/signin/ScanFrameFlowPolicy.kt"
        const val STATUS_FILE = "com/buaa/schedule/ui/signin/ScanUiStatus.kt"
        const val REJECT_FILE = "com/buaa/schedule/data/import/ScanReject.kt"

        /**
         * T90 被授权改动的那一段（帧观测措辞）的锚点：它的 KDoc 第一行。
         *
         * ⑤b 靠它把这一段挖掉之后再逐字节比 —— 锚点漂了/整段挪家就抛，
         * 而不是退化成"没比也算过"。
         */
        const val FRAME_AID_ANCHOR = "T65① 新增：「画面里有码"

        /**
         * T92（#133）被授权改动的那一段（[FRAME_FLOW_FILE] 头部"空转有多少帧"的读数）的两个锚点。
         *
         * 起始那句在改动前后逐字保留（它是上一句的尾巴），收尾那句是下一节的开头 —— 两者都
         * **不在**被挖的范围内，所以锚点一漂就抛，改锚点也等于红。
         */
        const val FRAME_FLOW_RATE_START = "这就是本卡的账"
        const val FRAME_FLOW_RATE_END = "所以这里只问三件事"

        /** 139 = 基线（T87 起点）那一段的实际长度；1,815 = T92 本轮那一段的实际长度 */
        const val FRAME_FLOW_RATE_MIN = 100
        const val FRAME_FLOW_RATE_MAX = 4_000

        /** "复现法确实写进去了"的靶子锚点（两边都用它证明挖的是同一段） */
        const val FRAME_FLOW_RECIPE_ANCHOR = "怎么复现"

        /** 本卡的起点（master）：⑤b 那几份"本卡不许碰"的文件按这一枚哈希逐字节核对 */
        const val T87_BASELINE = "6a27324"

        /** 真码形状（值本身是假的，滚动码的真值永远不该出现在这里） */
        val realCode =
            "http://iclass.buaa.edu.cn:8081/app/course/stu_scan_sign.action?courseSchedId=1234567&timestamp=1695000000"
    }
}
