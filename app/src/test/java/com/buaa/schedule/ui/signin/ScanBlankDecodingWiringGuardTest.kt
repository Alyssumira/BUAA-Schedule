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
     *
     * ⚠️ 现在时的账（T404C② 改口，只说这一枚格子**此刻**钉成什么样；上面 T90／T92 那两段是历史叙述，一字不动）：
     * ⑤b 那张表是**四枚文件**（`listOf(REJECT_FILE, VIEW_MODEL_FILE, GATE_FILE, RECOVERY_FILE)`）配**四枚基点常量**
     *（`when`：`GATE_FILE -> ddf493f`、`VIEW_MODEL_FILE -> bd0eca7`、`REJECT_FILE -> bd0eca7`、
     *  `RECOVERY_FILE` 落 `else -> 6a27324`）—— 也就是上面那句"其余四份文件照旧整文件逐字节对 [T87_BASELINE]"
     * 在 T245②／T257③／T302①／T395①／T404① 五次授权改动之后只对**棘轮那一枚**还成立。
     * 文件底部另留**三枚"前一头"常量**（`00b0b51`／`869fc13`／`06214c1`，外加它们共同的起点 `6a27324`）
     * 给靶子复算差集：每一枚基点的实物都由"两头逐字节必须不同"＋"被授权那一手的出现次数差"＋
     * "差集不许更大"＋（`ScanReject.kt` 那一族再加一枚**逐字还原**）钉着，一枚都没有朝宽改过。
     */
    @Test
    fun filesThisCardMustNotTouchAreByteIdenticalToBaseline() {
        for (relative in listOf(REJECT_FILE, VIEW_MODEL_FILE, GATE_FILE, RECOVERY_FILE)) {
            val path = "$MAIN_PREFIX/$relative"
            // T245② 被授权改过 ScanSubmissionGate.kt 的**措辞**（占位的语义 + awaitingUserAction
            // 有两个写者那两句），所以这一枚的基点前移到 T245②；其余三枚那时仍按 6a27324 逐字节核对
            // （状态机那一枚随后由 T257③ 前移、ScanReject.kt 那一枚由 T309① 前移 —— 见下面两段）。
            // 反向钉一枚没删、没放宽：闸门文件从今往后照样"不许顺手改"。
            //
            // T257③ 被授权改过 SignInViewModel.kt 的**漏斗**（那第四处 `if (flight.value) return`
            // 换成 ScanFlightAdmission 分档 + 一句卡面 + 一行 Log.w），所以这一枚的基点同样前移，
            // 前移的实物由下面那枚靶子当场复算（新旧两份状态机逐字节不同才允许不同源）。
            // 状态机从今往后照样"不许顺手改"：改它就得连带重钉这一枚与本文件的反向钉。
            //
            // T395① 把状态机那一枚的基点从 00b0b51（T257③ 那一枚）再前移一次：被授权改的只有
            // **闸门那一次原子换**（`dispatching` 的出处从 `flight.value` 那次读换成
            // `flight.compareAndSet(false → true)` 的结果，落回从裸写换成 CAS）。
            // 为什么这一枚非改不可：递交入口不止两条、也不都在主线程（相机兜底那一手就在 CameraX 的
            // 分析线程上就地调状态机），于是"读→判→写"在两颗线程之间不是原子的一步，两发真请求可以
            // 一起飞出去。差集的大小由下面那枚 T395 靶子当场复算（闸门之外那七处枚数一字不动），
            // 反向钉照 T245②／T257③／T309① 那一套：不删、不放宽，只把基点押到被授权的那一手上。
            //
            // T309① 前移第三枚：REJECT_FILE（ScanReject.kt）的基点从 6a27324 前移到 T302① 那枚提交。
            // 被授权改的是**四刀委托**：`urlSchemeOf(text)` / `urlPathOf(text)` / `urlAuthority(text)` /
            // `splitAuthority(it)` 这四处原本同包的裸调用改成跨层写全限定名 `com.buaa.schedule.domain.url.*`，
            // 另加三行说明注释 —— 写全限定名而不是引 import，是为了不当掉 `ScanTargetParserTest`
            //「解析层零 import 零 android 零取钟」那道纯度钉（那颗文件今天 `^import ` 行数仍 0，T309① 复算过）。
            // 四刀的实现本体逐字节搬进 `domain/url/UrlShape.kt`：T309① 把 6a27324 那份（当时住在
            // IClassQrParser.kt 尾部）与新那份的函数体逐字符对过，四枚全部相同 ⇒ 这一手是行为保持的搬家。
            // 授权人＝编排者（10-12 裁「并尺合法」：全仓只许一把 authority 刀，#455 那一族「唯一真源旁边
            // 又各写一遍」在本仓反复出事）；先例就是上面那两次前移（T245②／T257③），处置也照同一套：
            // 基点前移＋反向钉不删不放宽＋把"被授权的差集到底有多大"钉成可复算的格子（见下面那枚靶子）。
            // 七档判据、措辞与取证行这一手一个字都没动，所以这颗文件从今往后照样"不许顺手改"：
            // 谁再动它的判据或措辞，逐字节那一头当场红。
            //
            // T404①（崩卡的实现段，由本回收卡 T404C 按「基点前移＋补靶子」这一档收口）把这份清单里
            // **两枚**文件的基点各前移一次：
            //  · [REJECT_FILE] 押的那一手是「把 `IClassQrParser.queryValue(…) != null` 换成
            //    在册的 `IClassQrParser.hasQueryParam(…)`」那一枚存在性判据（`ScanReject.kt` 里
            //    `USER_ID_PARAM` 那一行，全文件**只**差这一行、行数一字不动），
            //  · [VIEW_MODEL_FILE] 押的那一手是 [fail] 那句 `error.message ?: "网络异常，请稍后重试"`
            //    换成按档取句子（`SubmitFailureClassifier.screenLine` / `.forensicsLine`）。
            // 两次前移都照 T245②／T257③／T309①／T395③ 那一套：不删、不放宽，
            // 差集的形状由下面那两枚 T404① 靶子当场复算；旧常量留在原地不删——它们是靶子的"前一头"。
            val rev = when (relative) {
                GATE_FILE -> T245_GATE_BASELINE
                VIEW_MODEL_FILE -> T404_VIEW_MODEL_BASELINE
                REJECT_FILE -> T404_REJECT_BASELINE
                else -> T87_BASELINE
            }
            val baseline = baselineBlob(rev, path)
            check(baseline != null) { "git 跑不动或基线取不到（$path@$rev），反向钉无从核对" }
            assertEquals(
                "$relative 相对基点被改过了：本卡只该在解码与递交之间加一道准入，" +
                    "七档文案、状态机漏斗、闸门与棘轮都不许动",
                normalizeNewlines(baseline),
                normalizeNewlines(File(findMainJavaDir(), relative).readText()),
            )
        }
        // 靶子：这一枚的基点前移是有实物的（新旧两份闸门文件逐字节不同才允许不同源），
        // 否则"换个 sha"就等于把反向钉空转掉
        assertTrue(
            "ScanSubmissionGate.kt 在 6a27324 与 T245 基点上逐字节相同 ⇒ 基点前移是白给的，这一枚反向钉已经空转",
            baselineBlob(T87_BASELINE, "$MAIN_PREFIX/$GATE_FILE") != baselineBlob(T245_GATE_BASELINE, "$MAIN_PREFIX/$GATE_FILE"),
        )
        // 同一枚靶子换到状态机那一头（T257③）：旧基线里那句静默早退必须还在，
        // 新基线里必须已经换成判据分档 —— 两头都在，才说明前移前的是"被授权改过"那一手
        val viewModelPath = "$MAIN_PREFIX/$VIEW_MODEL_FILE"
        val oldViewModel = baselineBlob(T87_BASELINE, viewModelPath)
        val newViewModel = baselineBlob(T257_VIEW_MODEL_BASELINE, viewModelPath)
        check(oldViewModel != null && newViewModel != null) {
            "状态机的两份基线取不到（$viewModelPath），靶子无从复算"
        }
        assertTrue(
            "SignInViewModel.kt 在 6a27324 与 T257 基点上逐字节相同 ⇒ 基点前移是白给的，这一枚反向钉已经空转",
            normalizeNewlines(oldViewModel) != normalizeNewlines(newViewModel),
        )
        assertTrue(
            "旧基线里已经没有那处被换掉的静默早退了（前移的前面不是本卡改的那一手）：",
            Regex(Regex.escape("if (flight.value) return")).findAll(oldViewModel).count() == 4,
        )
        assertTrue(
            "新基线的状态机不再问 ScanFlightAdmission（前移的后面不是本卡改的那一手）：",
            newViewModel.contains("scanFlightAdmission("),
        )
        // ---- 靶子（T395①，照 T257③／T309① 那一套写法）：状态机这一枚第二次前移押的是「闸门那一次原子换」 ----
        // 两头都有实物：中间那一枚（00b0b51）里闸门的两次裸写与那一次会过期的读必须还在，
        // 本卡那一枚（基点）里它们必须一枚都不剩，而 CAS 恰好两枚（领取 + 落回）。
        // 差集不许比这更大：闸门之外那七处（三处按按钮的守卫、唯一那颗闸门字段与它的 StateFlow、
        // 漏斗、说话那颗、取证行、原文字段的落点数）在新基线里枚数与中间那一枚一字不动。
        val gateShape = baselineBlob(T257_VIEW_MODEL_BASELINE, viewModelPath)
        val gateClaimed = baselineBlob(T395_VIEW_MODEL_BASELINE, viewModelPath)
        check(gateShape != null && gateClaimed != null) {
            "状态机闸门的两份基线取不到（$viewModelPath），T395① 的靶子无从复算"
        }
        assertTrue(
            "SignInViewModel.kt 在 00b0b51 与 T395① 基点上逐字节相同 ⇒ 这一枚基点前移是白给的，反向钉已经空转",
            normalizeNewlines(gateShape) != normalizeNewlines(gateClaimed),
        )
        val gateShapeCode = withoutComments(gateShape)
        val gateClaimedCode = withoutComments(gateClaimed)
        // 被授权的那一手（写点从两枚裸写换成两枚 CAS，判据吃的从读换成领取的结果）
        assertEquals("00b0b51 那份闸门还是两次裸写（「读→判→写」里的那两回写）：", 2, occurrences(gateShapeCode, "flight.value = "))
        assertEquals("00b0b51 那份递给判据的是一次会过期的读（本卡收的就是这一格）：", 1, occurrences(gateShapeCode, "dispatching = flight.value"))
        assertEquals("00b0b51 那份一枚原子换都没有（靶子取错了就量不出本卡那一手）：", 0, occurrences(gateShapeCode, "flight.compareAndSet"))
        assertEquals("T395① 那份的闸门写点应当恰好两枚原子换（领取 + 落回）：", 2, occurrences(gateClaimedCode, "flight.compareAndSet"))
        assertEquals("T395① 那份又长回裸写（读→判→写那一形状从这一格复活）：", 0, occurrences(gateClaimedCode, "flight.value = "))
        assertEquals("T395① 那份递给判据的不是领取的结果：", 1, occurrences(gateClaimedCode, "dispatching = !claimed"))
        for ((needle, expected) in listOf(
            "if (flight.value) return" to 3,
            "MutableStateFlow(false)" to 1,
            "val inFlight: StateFlow<Boolean>" to 1,
            "scanFlightAdmission(" to 1,
            "tellDroppedArrival(" to 2,
            "dispatchingPayload" to 4,
            "Log.w(TAG, droppedArrivalTraceText(" to 1,
        )) {
            assertEquals("T395① 没被授权动「$needle」，新基线里的枚数与 00b0b51 那份对不上：", expected, occurrences(gateClaimedCode, needle))
            assertEquals("00b0b51 那份「$needle」的枚数不是 $expected（靶子取错了，这么比量不出东西）：", expected, occurrences(gateShapeCode, needle))
        }
        // ---- 靶子（T404①，照上面 T395① 那条先例的写法）：状态机这一枚第三次前移押的就是「fail 那句用户文案换了出处」 ----
        // 前一头（869fc13 ＝ 上面那枚 gateClaimed 的同一份字节，不重取）里 `error.message ?: …` 必须还在、
        // 两枚 SubmitFailureClassifier 调用必须一枚都没有；基点那份里那句必须归零、两枚调用各恰好一枚，
        // 而 `Log.w(TAG, ` 从两枚变三枚（第三枚就是本卡补的那行取证）。差集不许比这更大：
        // 闸门那一族＋漏斗那十一处枚数一字不动，行数 390 → 410（+20：一句换两枚调用＋两段 KDoc/注释）。
        val spokenBlob = baselineBlob(T404_VIEW_MODEL_BASELINE, viewModelPath)
        check(spokenBlob != null) { "状态机 `fail` 的 T404① 基线取不到（$viewModelPath@$T404_VIEW_MODEL_BASELINE），这一枚靶子无从复算" }
        val screenOld = normalizeNewlines(gateClaimed)
        val screenNew = normalizeNewlines(spokenBlob)
        assertTrue(
            "SignInViewModel.kt 在 869fc13 与 T404① 基点上逐字节相同 ⇒ 这一枚基点前移是白给的，反向钉已经空转",
            screenOld != screenNew,
        )
        val screenOldCode = withoutComments(screenOld)
        val screenNewCode = withoutComments(screenNew)
        // 被授权的那一手（屏上句子与取证行的出处）
        assertEquals("869fc13 那份还在把 JVM 原文当用户文案（本卡收的就是这一格）：", 1, occurrences(screenOldCode, "error.message ?: "))
        assertEquals("T404① 那份又长回 JVM 原文上屏（那一形状从这一格复活）：", 0, occurrences(screenNewCode, "error.message ?: "))
        assertEquals("869fc13 那份一枚按档取句子都没有（靶子取错了就量不出本卡那一手）：", 0, occurrences(screenOldCode, "SubmitFailureClassifier.screenLine("))
        assertEquals("T404① 那份的屏上句子不是按档取的：", 1, occurrences(screenNewCode, "SubmitFailureClassifier.screenLine("))
        assertEquals("869fc13 那份没有递交这一侧的取证行：", 0, occurrences(screenOldCode, "SubmitFailureClassifier.forensicsLine("))
        assertEquals("T404① 那份的取证行不止一枚（第二份＝页面自算口径）：", 1, occurrences(screenNewCode, "SubmitFailureClassifier.forensicsLine("))
        assertEquals("869fc13 那份没有那颗内核的 import：", 0, occurrences(screenOldCode, "import com.buaa.schedule.domain.signin.SubmitFailureClassifier"))
        assertEquals("T404① 那份的 import 恰好一枚：", 1, occurrences(screenNewCode, "import com.buaa.schedule.domain.signin.SubmitFailureClassifier"))
        assertEquals("869fc13 那份「Log.w(TAG, 」恰好两枚：", 2, occurrences(screenOldCode, "Log.w(TAG, "))
        assertEquals("T404① 那份的「Log.w(TAG, 」不是三枚（第三枚＝本卡补的那行取证，少一枚就是它被撤了）：", 3, occurrences(screenNewCode, "Log.w(TAG, "))
        // 行数（手写期望值，两枚独立源现量：`git show <rev>:<path> | wc -l` 与守卫里这把 `newlines`）
        assertEquals("SignInViewModel.kt 在 869fc13 上的行数（手写期望值）：", 390, newlines(screenOld))
        assertEquals("SignInViewModel.kt 在 T404① 基点上的行数＝前一头＋20：", 410, newlines(screenNew))
        // 差集不许更大：闸门那一族与漏斗那十一处在新基线里枚数与前一头一字不动
        for ((needle, expected) in listOf(
            "flight.compareAndSet" to 2,
            "flight.value = " to 0,
            "dispatching = !claimed" to 1,
            "if (flight.value) return" to 3,
            "MutableStateFlow(false)" to 1,
            "val inFlight: StateFlow<Boolean>" to 1,
            "scanFlightAdmission(" to 1,
            "tellDroppedArrival(" to 2,
            "dispatchingPayload" to 4,
            "Log.w(TAG, droppedArrivalTraceText(" to 1,
            "relogin = false" to 5,
        )) {
            assertEquals("T404① 没被授权动「$needle」，新基线里的枚数与 869fc13 那份对不上：", expected, occurrences(screenNewCode, needle))
            assertEquals("869fc13 那份「$needle」的枚数不是 $expected（靶子取错了，这么比量不出东西）：", expected, occurrences(screenOldCode, needle))
        }
        // ---- 靶子（T309①，照上面 T257③ 那条先例的写法）：ScanReject.kt 这次前移的差集**恰好**是那四刀＋那段注释 ----
        // ⚠️ 不许只写成"新旧两份逐字节不同就通过"：那只能证明前移不是白给的，证不了差集里只有被授权的那一手。
        // 下面四枚格子的期望值全部手写（326 行 / 330 行 / 四刀 / 三行注释），最后一枚才是真防线：
        // 把新基线里那四刀委托与那段注释**逐字还原**回去，结果必须与旧基线的每一个字节相同。
        val rejectPath = "$MAIN_PREFIX/$REJECT_FILE"
        val oldReject = baselineBlob(T87_BASELINE, rejectPath)
        val newReject = baselineBlob(T302_REJECT_BASELINE, rejectPath)
        check(oldReject != null && newReject != null) {
            "ScanReject.kt 的两份基线取不到（$rejectPath），靶子无从复算"
        }
        val rejectBefore = normalizeNewlines(oldReject)
        val rejectAfter = normalizeNewlines(newReject)
        assertTrue(
            "ScanReject.kt 在 6a27324 与 06214c1 上逐字节相同 ⇒ 基点前移是白给的，这一枚反向钉已经空转",
            rejectBefore != rejectAfter,
        )
        // 格子 1（行数）：旧基线 326 行、新基线 330 行 —— 恰好多 4 行（三行注释＋那一刀被写成两行）
        assertEquals("ScanReject.kt 在 6a27324 上的行数（手写期望值）：", 326, newlines(rejectBefore))
        assertEquals("ScanReject.kt 在 06214c1 上的行数＝旧基线＋4：", 330, newlines(rejectAfter))
        // 格子 2（被改动的表达式）：跨层前缀恰好四枚、四刀各恰好一处、旧基线里一枚都没有（那时还是同包裸调用）
        assertEquals(
            "06214c1 里「$T302_DELEGATION_PREFIX」只许出现四刀（第五枚就是又起了一把尺）：",
            4,
            occurrences(rejectAfter, T302_DELEGATION_PREFIX),
        )
        assertEquals(
            "6a27324 里不该有跨层委托（那四刀当时是同包的裸调用）：",
            0,
            occurrences(rejectBefore, T302_DELEGATION_PREFIX),
        )
        for (call in listOf("urlSchemeOf(", "urlPathOf(", "urlAuthority(", "splitAuthority(")) {
            assertEquals("旧基线里 $call 只许一处：", 1, occurrences(rejectBefore, call))
            assertEquals("新基线里 $call 只许一处（两处＝签到这一族自己又写了一遍）：", 1, occurrences(rejectAfter, call))
        }
        // 格子 3（那段注释）：必须**逐字**还是那三行，而且旧基线里没有它（有就说明基线取错了、量不出事）
        val rejectCommentBlock = T302_REJECT_COMMENT_LINES.joinToString("\n")
        assertEquals("T302① 补的那段说明注释是 3 行：", 3, T302_REJECT_COMMENT_LINES.size)
        assertEquals("那段注释在新基线里只许一处：", 1, occurrences(rejectAfter, rejectCommentBlock))
        assertTrue(
            "新基线里没有那段逐字的授权注释（被前移的那一手不是被授权的形状，措辞被改过）：\n$rejectCommentBlock",
            rejectAfter.contains(rejectCommentBlock),
        )
        assertTrue("旧基线里已经带着那段注释（基线取错了，这么比量不出任何东西）：", !rejectBefore.contains(T302_REJECT_COMMENT_HEAD))
        // 格子 4（真防线）：还原四刀＋那段注释之后必须逐字节落回旧基线。差集里多一个字符（换判据、改措辞、
        //              动取证行、把代码挪个位置）都红在这里，而不是红在"顺手换个 sha"。
        val rejectUnfolded = rejectAfter.replace("$rejectCommentBlock\n", "")
        assertEquals("抹掉那三行注释之后是 327 行（330-3）：", 327, newlines(rejectUnfolded))
        assertEquals("那一刀在新基线里必须是 T302① 的两行形状（找不到就没法把它并回去）：", 1, occurrences(rejectUnfolded, T302_REJECT_PARTS_NEW))
        val rejectRejoined = rejectUnfolded.replace(T302_REJECT_PARTS_NEW, T302_REJECT_PARTS_OLD)
        assertEquals("把换行的那一刀并回一行之后是 326 行（与旧基线同行数）：", 326, newlines(rejectRejoined))
        val rejectRebuilt = rejectRejoined.replace(T302_DELEGATION_PREFIX, "")
        assertEquals("四刀该还原干净（跨层前缀归零）：", 0, occurrences(rejectRebuilt, T302_DELEGATION_PREFIX))
        assertEquals(
            "ScanReject.kt 相对 6a27324 的差集不只有「那四刀委托＋那段注释」：逐字还原回去之后还剩别的改动 ⇒ " +
                "七档判据、措辞或取证行被顺手改过，这一枚基点前移不成立、得回退 T302① 那一手",
            rejectBefore,
            rejectRebuilt,
        )
        // ---- 靶子（T404①，照上面 T309① 那条先例的写法）：ScanReject.kt 这次前移的差集**恰好那一行** ----
        // ⚠️ 这一手前后**行数相同**（现量两枚独立源：`git show 06214c1:<path> | wc -l` ＝ 330、
        //   `git show bd0eca7:<path> | wc -l` ＝ 330，守卫里这把 `newlines` 也是 330 / 330）⇒ 这一枚靶子
        //   一枚都不许押在行数差上（押了就是当场假绿）：押出现次数差＋逐字还原，行数只当"不许漂"的旁证钉。
        val rejectT404Blob = baselineBlob(T404_REJECT_BASELINE, rejectPath)
        check(rejectT404Blob != null) { "ScanReject.kt 的 T404① 基线取不到（$rejectPath@$T404_REJECT_BASELINE），这一枚靶子无从复算" }
        val rejectT404 = normalizeNewlines(rejectT404Blob)
        val rejectT302Code = withoutComments(rejectAfter)
        val rejectT404Code = withoutComments(rejectT404)
        assertTrue(
            "ScanReject.kt 在 06214c1 与 T404① 基点上逐字节相同 ⇒ 这一枚基点前移是白给的，这一枚反向钉已经空转",
            rejectAfter != rejectT404,
        )
        // 格子 1（出现次数差）：`USER_ID_PARAM` 那一行换掉了——旧形状归零、新形状恰好一枚，`!= null` 随之少一枚
        assertEquals("06214c1 那份「IClassQrParser.queryValue(」恰好两枚（抹注释后数出现次数）：", 2, occurrences(rejectT302Code, "IClassQrParser.queryValue("))
        assertEquals("T404① 那份还留着两枚（本卡那一手没落地）：", 1, occurrences(rejectT404Code, "IClassQrParser.queryValue("))
        assertEquals("06214c1 那份还不认识「IClassQrParser.hasQueryParam(」（靶子取错了就量不出本卡那一手）：", 0, occurrences(rejectT302Code, "IClassQrParser.hasQueryParam("))
        assertEquals("T404① 那份的存在性判据恰好一枚（第二枚＝签到这一族自己又写了一遍空白尺子）：", 1, occurrences(rejectT404Code, "IClassQrParser.hasQueryParam("))
        assertEquals("06214c1 那份抹注释后的「!= null」恰好四枚：", 4, occurrences(rejectT302Code, "!= null"))
        assertEquals("T404① 那份的「!= null」不是三枚（这一手被撤销、或又被别人添了一枚判据）：", 3, occurrences(rejectT404Code, "!= null"))
        // 格子 2（不许漂的旁证）：行数两头同值，四刀与跨层前缀枚数两头一字不动
        assertEquals("ScanReject.kt 在 06214c1 上的行数（手写期望值）：", 330, newlines(rejectAfter))
        assertEquals("ScanReject.kt 在 T404① 基点上的行数与前一头相同（这一手的差集只有一行，行数量不出它）：", 330, newlines(rejectT404))
        for ((needle, expected) in listOf(
            T302_DELEGATION_PREFIX to 4,
            "urlSchemeOf(" to 1,
            "urlPathOf(" to 1,
            "urlAuthority(" to 1,
            "splitAuthority(" to 1,
        )) {
            assertEquals("T404① 没被授权动「$needle」，新基线里的枚数与 06214c1 那份对不上：", expected, occurrences(rejectT404Code, needle))
            assertEquals("06214c1 那份「$needle」的枚数不是 $expected（靶子取错了，这么比量不出东西）：", expected, occurrences(rejectT302Code, needle))
        }
        // 格子 3（真防线·逐字还原）：把那一行并回 06214c1 的形状，结果必须与那份**逐字节**相同。
        //              差集里多一个字符（换判据、改措辞、动取证行、把代码挪个位置）都红在这里，而不是红在"顺手换个 sha"。
        assertEquals("那一行在 T404① 那份里必须是 `hasQueryParam` 的形状（找不到就没法把它并回去）：", 1, occurrences(rejectT404, T404_REJECT_LINE_NEW))
        assertEquals("旧形状在 T404① 那份里必须归零（没归零＝还原的替换会撞上两处，比不出东西）：", 0, occurrences(rejectT404, T404_REJECT_LINE_OLD))
        assertEquals(
            "ScanReject.kt 相对 06214c1 的差集不只有「`USER_ID_PARAM` 那一行的存在性判据」：逐字还原回去之后还剩别的改动 ⇒ " +
                "七档判据、措辞或取证行被顺手改过，这一枚基点前移不成立、得回退 T404① 那一手",
            rejectAfter,
            rejectT404.replace(T404_REJECT_LINE_NEW, T404_REJECT_LINE_OLD),
        )
        val statusPath = "$MAIN_PREFIX/$STATUS_FILE"
        val statusBaseline = baselineBlob(T87_BASELINE, statusPath)
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
        val flowBaseline = baselineBlob(T87_BASELINE, flowPath)
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

    /**
     * 换行符枚数（＝ `wc -l` 那把尺：数的是 `\n` 的枚数，量的是 `git show <rev>:<path>` 取回的**整份文件**
     * 抹 CR 之后的字节，**不抹注释**——抹注释那把是 [withoutComments] ＋ [occurrences]，两把尺各管各的期望值）。
     * 现在时（T404C② 现量）：⑤b 里被这一把尺钉住的期望值一共三对，每对都由两枚独立源各量一遍
     *（`git show <rev>:<path> | wc -l` 与这一把 `newlines`）—— `ScanReject.kt` 在 6a27324／06214c1 上
     * **326／330**（T309① 那枚靶子）、在 06214c1／bd0eca7 上 **330／330**（T404① 那一枚：两头同值，
     * 所以那一手的差集**不押行数**、押出现次数与逐字还原）、`SignInViewModel.kt` 在 869fc13／bd0eca7 上
     * **390／410**（T404① 的状态机靶子）。
     * 用 `count("\n")` 而不是 `lines().size`：后者"结尾那个换行算不算一行"是 stdlib 的口径，
     * 而 ⑤b 的靶子期望值是**手写字面数**，钉的必须是与我复算时同一把尺。
     */
    private fun newlines(text: String): Int = occurrences(text, "\n")

    /**
     * 在册 #733（门禁可移植性 · 乙档）的基线取数口：**两套并存，理由写在这里**。
     *
     *  · 下面 [GOLDEN_BASELINE_LEDGER] 那五行里的 (基点 sha, 文件) 只活在五枚**未合并分支**上
     *    （`ai/T245`／`ai/T257`／`ai/T395`／`ai/T404b`／`ai/T404C`），**不在 master 的祖先链上**
     *    （本卡现量：`git merge-base --is-ancestor <sha> HEAD` 四枚全 NO）。本仓每轮清理 worktree 与
     *    `ai/` 打头那批分支是常规动作 ⇒ 谁清了那五枚分支，`git show` 就在一枚什么都没改的树上取不到对象，
     *    反向钉当场硬红，而报错话只说"git 跑不动或基线取不到"，读不出真因。这一类改成从 classpath
     *    资源 `baseline/<sha7>__<basename>.txt` 读 —— blob 由 `git show <rev>:<path>` 直接落盘、
     *    逐字节、不过编辑器（本仓在册：手重打会把 CRLF 写进 blob，日后对基线 rebase 时造假冲突）。
     *  · 仍可达的基点（6a27324／06214c1／d7af6f8／003bbdc／03d6546／83d18f5／9e155df）押在 HEAD 的
     *    祖先链上，清分支也取得到 ⇒ **照旧走 [gitShow]**，口径与消息一字不改；它们的可达性由
     *    [everyBaselineStillTakenFromGitIsStillReachableFromHead] 那格常驻钉住（#733 从此不靠人工审计）。
     *
     * 取不到不许静默：golden 这一头当场 `check`（非 null 且非空），git 这一头沿用调用点原有的
     * `check(... != null)` —— 两栏同一口径，都不许退化成"没比也算过"。
     */
    private fun baselineBlob(rev: String, path: String): String? {
        val row = GOLDEN_BASELINE_LEDGER.firstOrNull { it.startsWith("$rev $path ") }
            ?: return gitShow(rev, path)
        val sha = row.substringBefore(' ')
        val resource = "baseline/$sha" + "__" + path.substringAfterLast('/').removeSuffix(".kt") + ".txt"
        val bytes = javaClass.getResourceAsStream("/$resource")?.use { it.readBytes() }
        check(bytes != null && bytes.isNotEmpty()) {
            "golden 基线取不到或为空：app/src/test/resources/$resource（在册 #733：$path@$sha 这一枚已经取成 golden，" +
                "资源不在＝红，不许静默跳过）"
        }
        // blob 是 LF（`git show` 不走 smudge），而 Windows 工作树 checkout 出来的 .txt 可能被补成 CRLF；
        // 这里抹回 blob 的形状，使 golden 这一头与 [gitShow] 那一头是同一把尺。
        return String(bytes, Charsets.UTF_8).replace("\r\n", "\n")
    }

    /**
     * 小册子（在册 #733 乙档）：五行，一行一枚已迁成 golden 资源的基线，格式
     * `"<sha7> <仓库内路径> <授权卡号/事由>"`。取数口 [baselineBlob] 只认这张表 ⇒
     * 将来谁要把基点再前移一格，就**必须有格子可改**：这里加/改一行，同时按
     * `git show <rev>:<path> > app/src/test/resources/baseline/<sha7>__<basename>.txt` 落一枚 blob
     * 并当场双向 md5 对账（[everyGoldenBaselineIsOnClasspathAndMatchesItsLedgerRow] 钉枚数＝5、
     * 资源在场非空、且逐枚与那行 (sha,path) 对得上）。
     */
    private val GOLDEN_BASELINE_LEDGER: List<String> = listOf(
        "ddf493f app/src/main/java/com/buaa/schedule/ui/signin/ScanSubmissionGate.kt T245② 被授权改闸门措辞（占位的语义 + awaitingUserAction 有两个写者那两句）——三枚反向钉共同的基点",
        "869fc13 app/src/main/java/com/buaa/schedule/ui/signin/SignInViewModel.kt T395① 被授权改闸门那一次原子换（领取与落回各一枚 compareAndSet）",
        "00b0b51 app/src/main/java/com/buaa/schedule/ui/signin/SignInViewModel.kt T257③ 被授权改漏斗（第四处 if (flight.value) return 换成 ScanFlightAdmission 分档）",
        "bd0eca7 app/src/main/java/com/buaa/schedule/ui/signin/SignInViewModel.kt T404① 被授权改 fail 那句用户文案的出处（按档取句子 + 一行取证）",
        "bd0eca7 app/src/main/java/com/buaa/schedule/data/import/ScanReject.kt T404① 被授权改 USER_ID_PARAM 那一行的存在性判据（queryValue 非空 → hasQueryParam）",
    )

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

    /**
     * 新增判据 ①（在册 #733 · 乙档的"册子格"）：五枚 golden 基线全部在场、非空，且逐枚与册子里那行
     * (sha, path) 对得上。四把尺：
     *  · 册子枚数**写死 5**，行格式三段（sha7 ／ 仓库内路径 ／ 授权卡号·事由）；
     *  · 资源名按命名规则 `<sha7>__<basename>.txt` 从那一行推出来，必须从 classpath 读得到且非空；
     *  · "对得上"＝那份 blob 的 `package` 句正是那行路径所在的包（资源名串门、落盘取错 path 都当场红）；
     *  · 取数口 [baselineBlob] 对那一行必须返回**同一份字节**（证明这行走到了，不是纸面账）。
     */
    @Test
    fun everyGoldenBaselineIsOnClasspathAndMatchesItsLedgerRow() {
        assertEquals("册子（GOLDEN_BASELINE_LEDGER）的枚数写死五枚，少一行或多一行都是有人在纸上动账：", 5, GOLDEN_BASELINE_LEDGER.size)
        val names = mutableListOf<String>()
        for (row in GOLDEN_BASELINE_LEDGER) {
            val parts = row.split(" ", limit = 3)
            assertEquals("册子每一行必须是「<sha7> <path> <授权卡号/事由>」三段：「$row」", 3, parts.size)
            val sha = parts[0]
            val path = parts[1]
            val resource = "baseline/$sha" + "__" + path.substringAfterLast('/').removeSuffix(".kt") + ".txt"
            names += resource
            val bytes = javaClass.getResourceAsStream("/$resource")?.use { it.readBytes() }
            check(bytes != null && bytes.isNotEmpty()) {
                "golden 基线取不到或为空：app/src/test/resources/$resource（在册 #733：$path@$sha 这一枚押在未合并分支上，" +
                    "已经取成 golden；资源不在＝红，不许静默跳过）"
            }
            val blob = String(bytes, Charsets.UTF_8).replace("\r\n", "\n")
            val pkg = "package " + path.removePrefix("$MAIN_PREFIX/").substringBeforeLast('/').replace('/', '.')
            assertTrue(
                "$resource 与册子那行对不上（$sha $path）：那份 blob 里没有本路径所在包的 package 句 ⇒ 资源名写错门，或落盘时取错了 path",
                blob.contains(pkg),
            )
            assertEquals(
                "取数口 baselineBlob 对册子那行没走 golden（$sha $path）：册子与取数口两张账对不上 —— 这枚基线还在从 git 取：",
                blob,
                baselineBlob(sha, path),
            )
        }
        assertEquals("五枚 golden 的资源名必须互不相同（同名就是互相覆盖，账上少一枚）：", 5, names.distinct().size)
    }

    /**
     * 新增判据 ②（在册 #733 · 乙档的"迁出格"）：那四枚不可达 sha 的基点**不再经 git 取**。
     * 尺＝按字符串数"引用这四枚 sha（字面量，或持有它们的五枚常量名）的 git 取数调用点"，
     * 三枚在册守卫各两栏：原文一栏、抹注释（[withoutComments]）后再数一遍一栏 —— 四栏都得归零。
     */
    @Test
    fun unreachableBaselinesAreNeverTakenFromGitAgain() {
        val migrated = Regex(
            "gitShow\\s*\\(\\s*[^)]*?(?:ddf493f|869fc13|00b0b51|bd0eca7" +
                "|T245_GATE_BASELINE|T257_VIEW_MODEL_BASELINE|T395_VIEW_MODEL_BASELINE" +
                "|T404_VIEW_MODEL_BASELINE|T404_REJECT_BASELINE)"
        )
        for (relative in listOf(
            "app/src/test/java/com/buaa/schedule/ui/signin/ScanBlankDecodingWiringGuardTest.kt",
            "app/src/test/java/com/buaa/schedule/ui/signin/ScanFrameAidWordingWiringGuardTest.kt",
            "app/src/test/java/com/buaa/schedule/ui/signin/ScanSecondEngineWiringGuardTest.kt",
        )) {
            val file = File(findRepoRoot(), relative)
            check(file.isFile) { "找不到守卫自己的源码：${file.path} —— 这一格的尺没东西可数" }
            val source = file.readText()
            assertEquals(
                "原文这一栏：$relative 里还有引用四枚不可达 sha 的 git 取数调用点（在册 #733 该走 golden 资源）：",
                0,
                migrated.findAll(source).count(),
            )
            assertEquals(
                "抹注释后再数一遍：$relative 的代码那一栏里还有引用四枚不可达 sha 的 git 取数调用点：",
                0,
                migrated.findAll(withoutComments(source)).count(),
            )
        }
    }

    /**
     * 新增判据 ③（在册 #733 的"绊线格"，最值钱的一格：把 #733 从人工审计变成常驻守卫）：
     * 对每一枚**仍走 git 的可达基点**断言它现在仍然可达 —— `git cat-file -e <sha>^{commit}` 成功
     * **且** `git merge-base --is-ancestor <sha> HEAD` 成功；不可达就红，并点名"这枚也要迁 golden"。
     * 名单＝三枚在册守卫仍在从 git 取的基点：本文件那两枚常量（6a27324／06214c1）＋另两枚守卫与
     * 别处守卫在用的五枚（d7af6f8／003bbdc／03d6546／83d18f5／9e155df）。
     * 谁把哪一枚迁成 golden，就顺手把这一行的名单同步一下（这一格少盯一枚＝册子那一格多盯一枚）。
     */
    @Test
    fun everyBaselineStillTakenFromGitIsStillReachableFromHead() {
        val stillOnGit = listOf(
            T87_BASELINE,
            T302_REJECT_BASELINE,
            "d7af6f8",
            "003bbdc",
            "03d6546",
            "83d18f5",
            "9e155df",
        ).distinct()
        val broken = mutableListOf<String>()
        for (sha in stillOnGit) {
            val objectExists = gitExitOk(listOf("cat-file", "-e", "$sha" + "^{commit}"))
            val onAncestorChain = gitExitOk(listOf("merge-base", "--is-ancestor", sha, "HEAD"))
            if (!objectExists || !onAncestorChain) {
                broken += "$sha（cat-file=${if (objectExists) "ok" else "NO"}、merge-base=${if (onAncestorChain) "ok" else "NO"}）"
            }
        }
        assertEquals(
            "这些仍走 git 的基点已经不可达（对象取不到，或已掉出 HEAD 的祖先链）⇒ 按在册 #733 这一枚也要迁成 golden 资源" +
                "（取数口只认册子那张表），别等反向钉报一句「git 跑不动或基线取不到」读不出真因：",
            emptyList<String>(),
            broken,
        )
    }

    /** `git` 的退出码：0 算 true。跑不动（没 git／起不了子进程）直接抛，不许静默算过 */
    private fun gitExitOk(args: List<String>): Boolean {
        val process = try {
            ProcessBuilder(listOf("git") + args).directory(findRepoRoot()).redirectErrorStream(true).start()
        } catch (io: java.io.IOException) {
            throw IllegalStateException("git 跑不动（${args.joinToString(" ")}）：在册 #733 的绊线格要靠 git 复算可达性，不许静默", io)
        }
        process.inputStream.readBytes()
        return process.waitFor() == 0
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

        /** T245② 授权改过 ScanSubmissionGate.kt 的措辞，这一枚反向钉的基点因此前移到这里 */
        const val T245_GATE_BASELINE = "ddf493f"

        /**
         * T257③：状态机漏斗被授权改过的那一手（第四处静默早退还给判据分档），
         * 所以 [VIEW_MODEL_FILE] 的逐字节基点前移到这一枚 —— 靶子在本条守卫里当场复算。
         */
        const val T257_VIEW_MODEL_BASELINE = "00b0b51"

        /**
         * T395①：状态机那一枚的基点第二次前移。被授权的只有**闸门那一次原子换**（领取与落回各一枚
         * `compareAndSet`，判据吃的从 `flight.value` 那次读换成领取的结果），差集的大小由 ⑤b 那枚
         * T395 靶子当场复算：闸门之外那七处枚数一字不动。
         *
         * [T257_VIEW_MODEL_BASELINE] 留在原地不删：它是靶子的"前一头"，没有它这份差集就无从复算。
         */
        const val T395_VIEW_MODEL_BASELINE = "869fc13"

        /**
         * T404①：[VIEW_MODEL_FILE] 的逐字节基点第三次前移。被授权的只有状态机 `fail(error)` 那一句
         * 用户文案的出处（`error.message ?: "网络异常，请稍后重试"` → `SubmitFailureClassifier.screenLine`
         * ＋一行 `.forensicsLine`，另加两段 KDoc/注释），闸门那一族与漏斗一枚都没动。
         *
         * 差集的形状由 ⑤b 那枚 T404① 靶子当场复算（出现次数＋行数两头各钉）。
         * [T395_VIEW_MODEL_BASELINE] 留在原地不删：它是这一枚靶子的"前一头"。
         */
        const val T404_VIEW_MODEL_BASELINE = "bd0eca7"

        /**
         * T302①（由编排者 10-12 裁「并尺合法」授权）把 [REJECT_FILE] 里那四刀同包裸调用改成跨层委托
         * `com.buaa.schedule.domain.url.*` 并补了三行说明注释，所以这一枚的逐字节基点前移到那一枚。
         *
         * 前移不是白给的，也不是放松：差集的形状由 ⑤b 那枚靶子当场复算（期望值手写），
         * 还原回去必须逐字节落回 [T87_BASELINE] —— 从今往后动 ScanReject.kt 的判据或措辞就红在这里。
         */
        const val T302_REJECT_BASELINE = "06214c1"

        /**
         * T404①（崩卡的实现段）把 [REJECT_FILE] 里 `USER_ID_PARAM` 那一行的存在性判据
         * `IClassQrParser.queryValue(shape.source, …) != null` 换成在册的
         * `IClassQrParser.hasQueryParam(shape.source, …)`，所以这一枚的逐字节基点前移到那一枚。
         *
         * 这一手的差集**恰好一行**（全文件行数 330 → 330，行数在这里量不出东西）——
         * 形状由 ⑤b 那枚 T404① 靶子当场复算，押的是出现次数差＋逐字还原，不押行数。
         * [T302_REJECT_BASELINE] 留在原地不删：它是这一枚靶子的"前一头"。
         */
        const val T404_REJECT_BASELINE = "bd0eca7"

        /**
         * T404① 在 [REJECT_FILE] 里换掉的那一行，两枚**逐字**形状（8 枚缩进在内）：
         * 旧的那一行（[T302_REJECT_BASELINE] 那份）与新的那一行（[T404_REJECT_BASELINE] 那份）。
         * ⑤b 的 T404① 靶子用这一对做逐字还原 —— 把新那份并回旧形状之后必须与旧基线逐字节相同。
         */
        const val T404_REJECT_LINE_OLD =
            "        if (IClassQrParser.queryValue(shape.source, IClassSignUrl.USER_ID_PARAM) != null) {"

        const val T404_REJECT_LINE_NEW =
            "        if (IClassQrParser.hasQueryParam(shape.source, IClassSignUrl.USER_ID_PARAM)) {"

        /** 那四刀共用的跨层前缀：靶子按枚数钉它（新基线恰好 4 枚、旧基线 0 枚、还原后归零） */
        const val T302_DELEGATION_PREFIX = "com.buaa.schedule.domain.url."

        /** 那句「T302①：这三刀……」的起头（旧基线里必须没有，否则基线取错了） */
        const val T302_REJECT_COMMENT_HEAD = "// T302①：这三刀"

        /** T302① 补的那段说明注释，**逐字三行**（改了措辞就不叫"被授权的那一段"，还原也还原不回去） */
        val T302_REJECT_COMMENT_LINES = listOf(
            "            // T302①：这三刀（scheme / path / authority+port）搬进了 domain/url/UrlShape.kt ——",
            "            // 全仓只许一枚 authority 刀，签到那一路与教务会话那一路共用同一把尺子。",
            "            // 跨层写全限定名：本文件的零 import 行由 ScanTargetParserTest 的纯度守卫钉着。",
        )

        /** 被改写成两行的那一刀（新基线形状）与它原本的一行（旧基线形状）：靶子用这一对把行数对回去 */
        const val T302_REJECT_PARTS_NEW =
            "            val parts = if (urlShaped) com.buaa.schedule.domain.url.urlAuthority(text)\n" +
                "                ?.let { com.buaa.schedule.domain.url.splitAuthority(it) } else null"

        const val T302_REJECT_PARTS_OLD =
            "            val parts = if (urlShaped) urlAuthority(text)?.let { splitAuthority(it) } else null"

        /** 真码形状（值本身是假的，滚动码的真值永远不该出现在这里） */
        val realCode =
            "http://iclass.buaa.edu.cn:8081/app/course/stu_scan_sign.action?courseSchedId=1234567&timestamp=1695000000"
    }
}
