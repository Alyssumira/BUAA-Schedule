package com.buaa.schedule.ui.signin

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「提示栏那一句只能由内核给，调用点一个字都不拼」的接线守卫（T90 / 台账 #134）。
 *
 * ## 这张卡的来龙去脉（① 的事实结论在这里，不在界面上）
 *
 * 用户最初报的是「扫码还是不行」。T83→T85→T87→T88 把签到链到真平台（iClass）、又把 ML Kit 的
 * "空原文"误检从**递交**与**帧观测**两头收了口。T88 收完之后冒出一笔新的用户可见副作用：
 * 扫码页那条提示栏开始对误检帧说「看见二维码了，但一时解不开：请拿稳对准它」——
 * 对着一个根本没有码的画面叫用户对准它，与用户当初抱怨的「扫到的不是签到码」同一类怪罪。
 *
 * 动手之前先量的是事实：**运行时能不能把"误检"与"一张真的但拍糊/拍小的码"分开？**
 * 三条读数（探针是临时的、未入库，读数逐字留在 `.tmp` 那份里，此处按字段抄全）：
 * 1. 依赖字节码（`javap com.google.mlkit.vision.barcode.common.Barcode`，bundled
 *    barcode-scanning-common 17.0.0）：公开面只有 `getFormat` / `getValueType` /
 *    `getBoundingBox` / `getCornerPoints` / `getRawValue` / `getDisplayValue` / `getRawBytes`
 *    加十一枚按原文解出来的子结构（Url/Email/Phone/Sms/WiFi/Geo/CalendarEvent/ContactInfo/
 *    DriverLicense…）。**没有置信度、没有质量分、没有"这一枚解开了没有"的标志位。**
 * 2. 装机逐字段实读 —— 相机那条（这台 AVD 的虚拟场景，每帧都误检，八行覆盖第 1..100 帧）：
 *    `fmt=-1`(FORMAT_UNKNOWN) `vt=0`(TYPE_UNKNOWN) `rawLen=0` `rawIsEmpty=true` `dispLen=0`
 *    `bytesLen=0` `corners=4` `corner0=Point(529..535, 414..417)`
 *    `box=Rect(529..535, 368..375 - 848..851, 664..671)`（短边实测 284..303，与 T88 那本账同一条带）
 * 3. **同一颗 scanner 的对照组** —— 相册那条喂自造靶子（一发一枚文件，本地先用 zxing-cpp
 *    回读自证过字节）：
 *    - 真码、清晰（108 字符的 iClass 形状）：`fmt=256`(QR_CODE) `vt=8`(TYPE_URL) `rawLen=108`
 *      `dispLen=108` `bytesLen=108` `corners=4` `corner0=Point(39, 40)`
 *      `box=Rect(39, 40 - 450, 450)` `urlLen=108` —— 解开了，字段才亮起来
 *    - 真码、糊到本地 zxing-cpp 也解不开（同一枚码高斯 σ=6）：`fmt=-1` `vt=0` `rawLen=0`
 *      `rawIsEmpty=true` `dispLen=0` `bytesLen=0` `corners=4` `box=Rect(43, 46 - 456, 442)`
 *      ⇒ **与上面第 2 条那种误检逐字段同形**，只有框的大小不同
 *    - 真码、缩到 40×40（真"小到解不出来"的那一张）：`fmt=-1` `vt=0` `rawLen=0`
 *      `rawIsEmpty=true` `bytesLen=0` `corners=4` `box=Rect(1, 1 - 39, 38)`
 *      ⇒ 同样与误检同形，而它的框比那面棋盘格的**小**一个数量级
 *
 * ⇒ **结论：不能分开。** `format` 在"没解开"时一律 -1（真码糊掉那一张也是 -1），所以它不是
 * 真假的信号、只是"解没解开"的信号；`rawValue` 在两种"没解开"里都交回**空串而不是 null**，
 * 所以连 [DecodingBlankness] 里 NoText 与 EmptyText 那一格分别都当不了分界（这条如果成立，
 * 本来是唯一可能省掉证据位的写法）；`boundingBox`/`cornerPoints` 三种形状都有，量不出真假。
 * 唯一带证据的区分只能来自**原文本身**（T87 那颗 [decodingAdmission] 在量的东西），
 * 而它是一枚「本轮到底有没有读出一枚有内容的码」的会话事实，不是这一帧的检测质量。
 *
 * ## 于是修法只许是"降级断语、留下建议"
 *
 * - 不许改成"这里没有码"：那同样在断言一件拿不到证据的事；
 * - 不许把提示栏整体映射成 null：用"什么都不说"换"说错话"，用户对着糊码再没有指引（②/③ 钉着）；
 * - 允许的是：句子按 [readableCodeSeen] 分支 —— 本轮读出过非空白原文才说「看见二维码了」，
 *   没读出过只说「还没扫出内容」，两支持有的动作一模一样。
 *
 * 本守卫钉五件 JVM 跑不到的事：
 * ① 那句提示**只**由 [scanFrameAidText] 给，调用点一个字都不拼（页面里搜不到任何一句措辞）；
 * ② 喂它的那枚设备侧事实在 analyzer 里**只有一个写点**，且排在准入判据之后（空白原文翻不动它），
 *    复位跟着绑定走，push 一路接到组合 —— 尺子仍是 T87 那一颗，全仓没长第二把；
 * ③ 内核保持纯判据：`ScanUiStatus.kt` 零 import、零时钟、不认识 Barcode/Log，
 *    措辞全在它自己身上（四档一张表，两档出声两档禁声）；
 * ④ 提示栏不许被整体改哑：两档在两种证据下都必须出声，禁声只许落在那两档；
 * ⑤ 反向钉：判据侧（帧观测计数、判档树、准入内核）相对本卡起点逐字节未动 ——
 *    本卡只动"话怎么说"，不许顺手把 ① 的结论实现成第二把尺子。
 *
 * 手法沿用 [ScanFrameObservationWiringGuardTest] / [ScanBlankDecodingWiringGuardTest]：
 * 读源码文本、匹配前先抹注释、找不到锚点就抛（静默跳过等于没有守卫）。
 */
class ScanFrameAidWordingWiringGuardTest {

    /** ①a 提示栏那句：一个调用点、两个实参都是内核的入参，页面不拼字 */
    @Test
    fun theHintBarSentenceComesFromTheKernelAndThePageAssemblesNothing() {
        val code = withoutComments(readMainSource(SCAN_SCREEN_FILE))
        assertEquals("scanFrameAidText 被调了 ${occurrences(code, "scanFrameAidText(")} 处，只能一处", 1, occurrences(code, "scanFrameAidText("))
        val call = code.lines().first { it.contains("scanFrameAidText(") }.trim()
        assertTrue("调用点不再把两个入参都交给内核（第二份口径的出生地）：\n$call", call.contains("scanFrameAidText(frameRung, readableCodeSeen)"))
        assertFalse("调用点自己在拼句子（+ 号）：\n$call", call.contains("+ \""))
        // 结构性降级面前这一颗必须禁声（对着死相机讲"走近一点"是新的假话）
        assertTrue("提示栏不再让位于 scanUiStatus：\n$call", call.contains("hintText == null"))
        assertTrue("提示栏不再要求相机路径活着：\n$call", call.contains("cameraLive"))
        // 页面里一个字都不许藏着措辞原文：那是第二份口径
        for (lit in listOf("看见二维码了", "还没扫出内容", "走近一点", "拿稳", "取景框正中", "更清晰的码")) {
            assertFalse("提示文案还在页面里另写一份（$lit），判据就被绕过了", code.contains(lit))
        }
    }

    /** ①b 屏上那枚事实来自组合里的一份状态，而且**只有一个写者**（分析器推，页面不自答） */
    @Test
    fun theEvidenceFactIsCompositionStateNotAPrivateGuess() {
        val code = withoutComments(readMainSource(SCAN_SCREEN_FILE))
        assertEquals("readableCodeSeen 的界面状态只能一份：", 1, occurrences(code, "var readableCodeSeen by remember"))
        assertTrue("证据位没跟档位一样由 analyzer 推进来（页面自己猜就是第二份真相）：\n$code",
            code.contains("onReadableCodeSeenChanged = { seen -> readableCodeSeen = seen }"))
        // 写它的只有那一处 push：页面不再像 frameRung 那样另清一次 —— 清它的责任在绑定钩子里，
        // 两边各清一次就会在"绑定没成功"那一档漂开（分析器清了、界面还挂着旧话）
        assertEquals("界面侧对 readableCodeSeen 的赋值只能有 push 那一处：", 1, occurrences(code, "readableCodeSeen = seen"))
        val composition = code.substringBefore("private class QrCodeAnalyzer(")
        assertEquals("组合侧不许自己把它清成 false（第二个写者）：", 0, occurrences(composition, "readableCodeSeen = false"))
        assertTrue("清 false 必须长在分析器的绑定钩子里（与那几本按绑定的账同一处）：", code.contains("readableCodeSeen = false"))
    }

    /** ②a 分析器里那一枚事实：一处写、排在准入之后、随绑定复位、只由 T87 那颗尺子决定 */
    @Test
    fun theAnalyzerLatchesTheEvidenceOnlyThroughTheOneAdmissionRuler() {
        val analyzer = balancedBlock(withoutComments(readMainSource(SCAN_SCREEN_FILE)), "private class QrCodeAnalyzer(")
        val gate = balancedBlock(analyzer, "private fun submitDecodedText(")
        assertTrue("闸门不再问准入判据（那就长不出这一位，或者第二把尺子已经生出来了）：\n$gate",
            gate.contains("decodingAdmission(raw, DecodingSource.CameraFrame)"))
        val refused = gate.indexOf("if (!admission.admitted)")
        val latched = gate.indexOf("noteReadableCodeSeenOnce()")
        val clock = gate.indexOf("System.currentTimeMillis()")
        check(refused >= 0 && latched >= 0 && clock >= 0) { "闸门那几句的形状换写法了，②a 要跟着改：\n$gate" }
        assertTrue("证据位排在准入之前（空白原文就能把它翻起来）：", refused < latched)
        assertTrue("证据位排到了取墙钟之后（递交侧与观测侧又错开一帧）：", latched < clock)
        assertEquals("翻证据位只能「定义一处 + 闸门里调一次」：", 2, occurrences(analyzer, "noteReadableCodeSeenOnce()"))
        val latch = balancedBlock(analyzer, "private fun noteReadableCodeSeenOnce(")
        assertTrue("翻位没走那把唯一的尺子（admitted）：\n$latch", latch.contains("if (readableCodeSeen) return"))
        assertTrue("翻位没推给界面：\n$latch", latch.contains("onReadableCodeSeenChanged(true)"))
        for (banned in listOf("isBlank", "isEmpty", "trim()", "length", "Barcode", "Log.")) {
            assertFalse("翻位自己判了原文（$banned）—— 全仓只许有准入那一把空白尺子：\n$latch", latch.contains(banned))
        }
        // 复位跟着绑定走（与阶梯/留痕/补解那几本账同一处）
        val mark = balancedBlock(analyzer, "fun markBindStarted(")
        assertTrue("绑定钩子没清这枚证据位（旧一轮的读数会说新一轮的话）：\n$mark", mark.contains("readableCodeSeen = false"))
        assertTrue("清了位却没推界面（界面会停在上一轮那句话）：\n$mark", mark.contains("onReadableCodeSeenChanged(false)"))
        // 这枚事实不许写进任何别的账（判死/闸门/停帧）
        for (banned in listOf("health", "noteDecodeFailed", "scannerWorking", "awaitingUserAction", "giveUp", "handled = ", "valuelessCodes")) {
            assertFalse("证据位开始写判死/闸门那本账了（$banned）：\n$latch", latch.contains(banned))
        }
        assertEquals("准入判据的调用点仍只能一处（相机那条）：", 1, occurrences(analyzer, "decodingAdmission("))
        assertEquals("递交口仍只能一处：", 1, occurrences(analyzer, "onCode("))
    }

    /** ②b 档位那一本账一个字没动：这一卡只加了一枚并列的证据位 */
    @Test
    fun theRungLedgerItselfWasNotRewritten() {
        val analyzer = balancedBlock(withoutComments(readMainSource(SCAN_SCREEN_FILE)), "private class QrCodeAnalyzer(")
        val note = balancedBlock(analyzer, "private fun noteFrameRung(")
        for (entry in listOf("frameSymbolCounts(", "frameCodeRung(", "advanceScanAssist(", "onFrameRungChanged(", "onZoomCommand(")) {
            assertTrue("帧观测那本账不再走 $entry（本卡不许改判据）：\n$note", note.contains(entry))
        }
        assertFalse("档位测量开始自己判空白（第二把尺子）：\n$note", note.contains("isBlank"))
        assertEquals("换挡的界面 push 仍只有一处：", 1, occurrences(note, "onFrameRungChanged("))
    }

    /** ③ 内核保持纯判据：零 import、零时钟、不认识解码器与相机；措辞四档一张表全在它身上 */
    @Test
    fun theWordingKernelStaysPureJvmAndHoldsEverySentence() {
        val raw = readMainSource(STATUS_FILE)
        val code = withoutComments(raw)
        val imports = code.lines().filter { it.trim().startsWith("import ") }
        assertTrue("$STATUS_FILE 里出现了 import，这段判据就到不了 JVM：\n$imports", imports.isEmpty())
        for (banned in listOf("android.", "androidx.", "Log.", "Build.", "SystemClock", "System.currentTimeMillis", "Barcode", "ImageProxy")) {
            assertFalse("措辞内核自己去碰了设备/时钟/解码器（$banned）：", code.contains(banned))
        }
        val aid = balancedBlock(code, "internal fun scanFrameAidText(")
        assertTrue("入参不再是「档位 + 本轮读出过原文没有」两枚：\n$aid",
            aid.contains("rung: FrameCodeRung") && aid.contains("readableCodeSeen: Boolean"))
        for (rung in listOf("CodeReadable", "CodeTooSmall", "CodeUndecodable", "NothingDetected")) {
            assertTrue("表里少了 $rung 这一档：\n$aid", aid.contains("FrameCodeRung.$rung"))
        }
        assertEquals("禁声只许两档（读到码 / 什么都没看见）：", 2, occurrences(aid, "-> null"))
        assertTrue("证据分支不在了（断语又变成无条件了）：\n$aid", aid.contains("if (readableCodeSeen)"))
        assertEquals("两档各自都要按证据位分一次支（少一处就是有一档被改回无条件）：\n$aid", 2, occurrences(aid, "if (readableCodeSeen)"))
        // 四句全份（两档 × 两种证据），且"看见二维码了"只许出现在有证据那一支
        assertEquals("句子总数应当是四句（两档 × 有证据/没证据）：", 4, Regex("\"[^\"]{10,}\"").findAll(aid).count())
        assertEquals("「看见二维码了」只许两句（有证据那一支）：", 2, occurrences(aid, "看见二维码了"))
        // 建议动作两支持有的一样：走近/对准/拿稳/换一张各两处
        for (advice in listOf("走近一点", "取景框正中", "拿稳", "换一张更清晰的码")) {
            assertEquals("建议「$advice」在两档的两种证据下都该各留一处：", 2, occurrences(aid, advice))
        }
        // 界面词汇纪律：不许把内部词汇写进给用户看的句子
        val sentences = Regex("\"[^\"]{10,}\"").findAll(aid).map { it.value }.toList()
        for (sentence in sentences) {
            for (jargon in listOf("档", "族", "帧", "候选框", "解码器", "内核", "误检")) {
                assertFalse("这句把内部词汇写给了用户（$jargon）：$sentence", sentence.contains(jargon))
            }
            for (banned in listOf("手电", "补光", "照亮", "闪光", "灯", "手输", "设置", "秒")) {
                assertFalse("这句指向不存在的硬件/入口或承诺了时间（$banned）：$sentence", sentence.contains(banned))
            }
        }
    }

    /**
     * ④ 反向钉：判据侧那几份文件相对本卡起点逐字节未动 —— 本卡只动"话怎么说"。
     *
     * ⚠️ [SECOND_ENGINE_FILE] 在 T91（#135）之后换了钉法，口径与 T90 给 `ScanUiStatus.kt` 换的那一种
     * 一模一样（见 [ScanSecondEngineWiringGuardTest] ④e 与本文件 ③）：整文件逐字节比较换成
     * **"挖掉 T91 被授权改动的那几段之后逐字节"**。本卡要抓的还是原来那件事 ——
     * ① 的结论是"误检与真糊码分不开"，所以谁都不许新立第二把空白尺子；T91 被授权的只有
     * "三次失手之后还许不许再试一发"这一条判据，其余（准入、判档树、停法的另三档、额度与间隔）
     * 一个字节都没让它漂。被挖的那几段每段都配了靶子，删掉判据来蒙混会当场红。
     *
     * ⚠️ T92（#133）又按同一套换法处理了 [FRAME_FLOW_FILE]（**不是放松**：被挖那段配了三枚靶子）——
     * T92 被授权改的是那颗内核头部"空转有多少帧"那一段的**读数口径**（旧文本把一个 2.80 帧/秒
     * 写成了这一页的固有属性）。判据、措辞与停帧那条取证行的全文都在被挖段之外，照旧逐字节。
     * 见 [cutT92FrameFlowSpan]，口径与 [ScanBlankDecodingWiringGuardTest] ⑤b 同一份。
     *
     * ⚠️ 现在时的账（T404C② 改口，只说这一枚格子**此刻**钉成什么样；上面 T91／T92 那两段是历史叙述，一字不动）：
     * ④ 那张表是**四枚文件**（`listOf(ADMISSION_FILE, RECOVERY_FILE, GATE_FILE, VIEW_MODEL_FILE)`）配**三枚基点常量**
     *（`when`：`GATE_FILE -> ddf493f`、`VIEW_MODEL_FILE -> bd0eca7`、其余两枚落 `else -> d7af6f8`）。
     * 状态机那一枚到 T404① 已经是**第三次前移**（`00b0b51`／`869fc13` 两枚"前一头"常量留在文件底部不删，
     * 各给一枚靶子复算差集），而 `ScanReject.kt` **不在这份清单里**（它由 [ScanBlankDecodingWiringGuardTest] ⑤b
     * 一枚钉着，那边同批前移）。每一枚基点的实物都由"两头逐字节必须不同"＋"被授权那一手的出现次数差"＋
     * "差集不许更大"钉着，一枚都没有朝宽改过。
     */
    @Test
    fun theJudgingSideWasNotTouchedByThisCard() {
        for (relative in listOf(ADMISSION_FILE, RECOVERY_FILE, GATE_FILE, VIEW_MODEL_FILE)) {
            val path = "$MAIN_PREFIX/$relative"
            // T245② 被授权改过 ScanSubmissionGate.kt 的**措辞**（占位的语义 + awaitingUserAction
            // 有两个写者那两句），所以这一枚的基点前移到 T245②；其余两枚仍按 d7af6f8 逐字节核对。
            // T257③ 被授权改过 SignInViewModel.kt 的**漏斗**（第四处 `if (flight.value) return`
            // 换成 ScanFlightAdmission 分档 + 一句卡面 + 一行 Log.w），同理前移到那一枚；
            // 两头都有靶子当场复算字节差异，前移不是白给的。
            // T395① 把状态机这一枚再前移一次：被授权的只有**闸门那一次原子换**（领取与落回各一枚
            // compareAndSet）。为什么非改不可：递交入口不止两条、也不都在主线程（相机兜底那一手就在
            // CameraX 的分析线程上就地调状态机），"读→判→写"在两颗线程之间不是原子的一步，两发真请求
            // 可以一起飞出去。差集大小由下面那枚 T395 靶子当场复算，反向钉照旧不删、不放宽。
            // T404①（崩卡的实现段，由本回收卡 T404C 收口）把状态机这一枚第三次前移：被授权的只有
            // `fail(error)` 那一句**用户文案的出处**（`error.message ?: "网络异常，请稍后重试"` →
            // 按档取句子 `SubmitFailureClassifier.screenLine(...)` ＋一行 `.forensicsLine(...)`，另加两段
            // KDoc/注释）。这一格为什么非改不可：那一句 JVM 原文带着主机名与端口上屏，是 #696 收的账，
            // 而"话怎么说"这一族归判据侧的在册内核 —— 差集大小由下面那枚 T404① 靶子当场复算。
            // 反向钉一枚没删、没放宽：闸门与状态机从今往后照样"不许顺手改"。
            val rev = when (relative) {
                GATE_FILE -> T245_GATE_BASELINE
                VIEW_MODEL_FILE -> T404_VIEW_MODEL_BASELINE
                else -> T90_BASELINE
            }
            val baseline = gitShow(rev, path)
            check(baseline != null) { "git 跑不动或基线取不到（$path@$rev），反向钉无从核对" }
            assertEquals(
                "$relative 相对本卡起点被改过了：① 的结论是「分不清」，所以判据侧一个字都不许动 —— " +
                    "把误检当成新档去判，就是给全仓添第二把空白尺子",
                normalizeNewlines(baseline),
                normalizeNewlines(File(findMainJavaDir(), relative).readText()),
            )
        }
        // 靶子：这一枚的基点前移是有实物的（新旧两份闸门文件逐字节不同才允许不同源），
        // 否则"换个 sha"就等于把反向钉空转掉
        assertTrue(
            "ScanSubmissionGate.kt 在 d7af6f8 与 T245 基点上逐字节相同 ⇒ 基点前移是白给的，这一枚反向钉已经空转",
            gitShow(T90_BASELINE, "$MAIN_PREFIX/$GATE_FILE") != gitShow(T245_GATE_BASELINE, "$MAIN_PREFIX/$GATE_FILE"),
        )
        // 同一枚靶子换到状态机那一头（T257③）：旧基线里那四处静默早退必须还在，
        // 新基线里必须已经换成判据分档，两头都在才说明前移押的是本卡改的那一手
        val viewModelPath = "$MAIN_PREFIX/$VIEW_MODEL_FILE"
        val oldViewModel = gitShow(T90_BASELINE, viewModelPath)
        val newViewModel = gitShow(T257_VIEW_MODEL_BASELINE, viewModelPath)
        check(oldViewModel != null && newViewModel != null) {
            "状态机的两份基线取不到（$viewModelPath），靶子无从复算"
        }
        assertTrue(
            "SignInViewModel.kt 在 d7af6f8 与 T257 基点上逐字节相同 ⇒ 基点前移是白给的，这一枚反向钉已经空转",
            normalizeNewlines(oldViewModel) != normalizeNewlines(newViewModel),
        )
        assertTrue(
            "旧基线里已经没有那四处被换掉的静默早退（前移的前面不是本卡改的那一手）：",
            Regex(Regex.escape("if (flight.value) return")).findAll(oldViewModel).count() == 4,
        )
        assertTrue(
            "新基线的状态机不再问 ScanFlightAdmission（前移的后面不是本卡改的那一手）：",
            newViewModel.contains("scanFlightAdmission("),
        )
        // ---- 靶子（T395①）：状态机这一枚第二次前移押的就是「闸门那一次原子换」，别的一字没动 ----
        // 中间那一枚（00b0b51）里闸门两次裸写 + 一次会过期的读必须还在；本卡那一枚里它们必须归零，
        // CAS 恰好两枚（领取 + 落回）。差集不许更大：闸门之外那七处在新基线里枚数一字不动。
        val gateShape = gitShow(T257_VIEW_MODEL_BASELINE, viewModelPath)
        val gateClaimed = gitShow(T395_VIEW_MODEL_BASELINE, viewModelPath)
        check(gateShape != null && gateClaimed != null) {
            "状态机闸门的两份基线取不到（$viewModelPath），T395① 的靶子无从复算"
        }
        assertTrue(
            "SignInViewModel.kt 在 00b0b51 与 T395① 基点上逐字节相同 ⇒ 这一枚基点前移是白给的，反向钉已经空转",
            normalizeNewlines(gateShape) != normalizeNewlines(gateClaimed),
        )
        val gateShapeCode = withoutComments(gateShape)
        val gateClaimedCode = withoutComments(gateClaimed)
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
        // ---- 靶子（T404①，照上面 T395① 那条先例的写法，也与 [ScanBlankDecodingWiringGuardTest] ⑤b 同批同一套） ----
        // 状态机这一枚第三次前移押的就是「`fail` 那句用户文案换了出处」：前一头（869fc13 ＝ 上面那枚
        // gateClaimed 的同一份字节，不重取）里 `error.message ?: …` 必须还在、两枚 SubmitFailureClassifier
        // 调用必须一枚都没有；基点那份里那句必须归零、两枚调用各恰好一枚，而 `Log.w(TAG, ` 从两枚变三枚
        //（第三枚就是本卡补的那行取证）。差集不许比这更大：闸门那一族＋漏斗那十一处枚数一字不动，
        // 行数 390 → 410（+20：一句换两枚调用＋两段 KDoc/注释）。
        val spokenBlob = gitShow(T404_VIEW_MODEL_BASELINE, viewModelPath)
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
        // 行数（手写期望值，两枚独立源现量：`git show <rev>:<path> | wc -l` 与下面这把 `newlines`）
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
        // 帧观测那颗内核的头部：挖掉 T92 的「空转速率」一段之外逐字节（同一把尺子，另一枚文件）
        val flowPath = "$MAIN_PREFIX/$FRAME_FLOW_FILE"
        val flowBaseline = gitShow(T90_BASELINE, flowPath)
        check(flowBaseline != null) { "git 跑不动或基线取不到（$flowPath@$T90_BASELINE），反向钉无从核对" }
        val flowCurrent = normalizeNewlines(File(findMainJavaDir(), FRAME_FLOW_FILE).readText())
        assertEquals(
            "$FRAME_FLOW_FILE 在「空转有多少帧」那一段之外被改过了：T92 只许改那一段的读数口径，" +
                "停帧判据与三档措辞都长在别处，一个字都不该跟着漂",
            cutT92FrameFlowSpan(normalizeNewlines(flowBaseline), "$flowPath@$T90_BASELINE"),
            cutT92FrameFlowSpan(flowCurrent, FRAME_FLOW_FILE),
        )
        // 靶子：基线侧那段带着被改掉的裸数、且没有本轮补的复现法（挖错地方/换错基线都会红）
        val baselineSpan = t92FrameFlowSection(normalizeNewlines(flowBaseline))
        assertTrue("靶子丢了：基线侧那一段里没有旧文本写死的 2.80 —— 挖错了地方，这么比量不出任何东西", baselineSpan.contains("2.80"))
        assertTrue("基线侧那一段已经带着「$T92_RECIPE_ANCHOR」：基线取错了（拿今天比今天的尺子量不出事）",
            !baselineSpan.contains(T92_RECIPE_ANCHOR))
        // 靶子：工作树那段确实还在，而且是"区间 + 争用档 + 复现法"那个形状，也没退回裸数
        val span = t92FrameFlowSection(flowCurrent)
        for (anchor in listOf("8.01", "4.73", "2.60", "2.80", T92_RECIPE_ANCHOR, "logcat -d -v year")) {
            assertTrue("空转速率那一段少了「$anchor」（区间、争用档、旧读数或复现法被删了）：\n$span", span.contains(anchor))
        }
        assertFalse("又退回没有口径的裸数（「模拟器实测该页 X 帧/秒」那一形状）：\n$span", span.contains("模拟器实测该页"))
        // 兜底那颗内核：挖掉 T91 的五段之外逐字节
        val kernelPath = "$MAIN_PREFIX/$SECOND_ENGINE_FILE"
        val kernelBaseline = gitShow(T90_BASELINE, kernelPath)
        check(kernelBaseline != null) { "git 跑不动或基线取不到（$kernelPath@$T90_BASELINE），反向钉无从核对" }
        val kernelCurrent = normalizeNewlines(File(findMainJavaDir(), SECOND_ENGINE_FILE).readText())
        assertEquals(
            "$SECOND_ENGINE_FILE 在 T91 被授权的那几段之外被改过了：这一卡只许改「三次失手之后还许不许再试」，" +
                "节流常数（额度/间隔/连击）、准入那把唯一的空白尺子与其余三档停法都不许顺手改",
            cutT91Spans(cutT245Spans(normalizeNewlines(kernelBaseline), "$kernelPath@$T90_BASELINE", T91Side.Baseline), "$kernelPath@$T90_BASELINE", T91Side.Baseline),
            cutT91Spans(cutT245Spans(kernelCurrent, SECOND_ENGINE_FILE, T91Side.Current), SECOND_ENGINE_FILE, T91Side.Current),
        )
        // 靶子：被挖掉的确实是本卡那件事（整段删掉判据、或把复探写成无限重试都会红在这里）
        assertTrue("靶子丢了：复探那颗常数没了（挖掉它就等于把这一卡撤了）：", kernelCurrent.contains("internal const val SecondEngineReprobeFrameGap = 120L"))
        assertTrue("靶子丢了：复探判据本体没了：", kernelCurrent.contains("internal fun afterMissesReprobeAllowsReprobe("))
        assertTrue("靶子丢了：复探没有吃同一份额度（封顶那一道闸门不在）：", kernelCurrent.contains("ledger.fires < SecondEngineMaxFiresPerBind &&"))
        assertTrue("靶子丢了：判死那一档还是旧的「本轮不再请它补解」那句谎：", !kernelCurrent.contains("本轮绑定不再请它补解"))
        // 靶子（T245）：挖掉的确实是被授权的那几段本身 —— 锚点对不上就等于把整份内核放行
        assertTrue("靶子丢了：工作树里没有 T245 的三档内核（挖错了地方）：", kernelCurrent.contains("internal enum class SecondEngineHandState {"))
        assertTrue("靶子丢了：工作树里 FailureTrace 那一档不是「不算手里有码」：", kernelCurrent.contains("SecondEngineHandState.FailureTrace -> false"))
        // 帧观测的数法与判档树：整段逐字节（本卡没加计数、没改阈值、没改阶梯）
        val ledger = "$MAIN_PREFIX/$FRAME_LEDGER_FILE"
        val ledgerBaseline = gitShow(T90_BASELINE, ledger)
        check(ledgerBaseline != null) { "git 跑不动或基线取不到（$ledger@$T90_BASELINE）" }
        for (region in listOf(
            listOf("internal fun frameSymbolCounts(", "internal const val UsefulModulePx"),
            listOf("internal fun frameCodeRung(", "internal val ZoomLadderRatios"),
        )) {
            assertEquals(
                "$FRAME_LEDGER_FILE 的 ${region[0]} 那一段相对本卡起点被改过了：",
                regionOf(normalizeNewlines(ledgerBaseline), region[0], region[1], ledger),
                regionOf(normalizeNewlines(File(findMainJavaDir(), FRAME_LEDGER_FILE).readText()), region[0], region[1], ledger),
            )
        }
        // 靶子：扫的确实是这卡管的那件事（空文件/挪过家都要红）
        assertTrue("靶子丢了：措辞内核还在？", File(findMainJavaDir(), STATUS_FILE).readText().contains("internal fun scanFrameAidText("))
        assertTrue("靶子丢了：准入判据还在？", gitShow(T90_BASELINE, "$MAIN_PREFIX/$ADMISSION_FILE")!!.contains("internal fun decodingAdmission("))
    }

    // ---- 源码核对工具（与同族守卫一个刀法）----

    private fun regionOf(source: String, startSignature: String, endSignature: String, path: String): String {
        val start = source.indexOf(startSignature)
        check(start >= 0) { "$path 里找不到 $startSignature：写法换过了，这条守卫要跟着改" }
        val end = source.indexOf(endSignature, start + startSignature.length)
        check(end > start) { "$path 里 $startSignature 之后找不到 $endSignature：区域边界漂了" }
        return source.substring(start, end)
    }

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
     * 口径与 [ScanBlankDecodingWiringGuardTest] 的 `newlines` 同一枚（T404C② 那批靶子在两枚守卫里押的是
     * 同一对写死值：`SignInViewModel.kt` 在 869fc13／bd0eca7 上 **390／410**，各由两枚独立源现量 ——
     * `git show <rev>:<path> | wc -l` 与这一把 `newlines`）。
     * 用 `count("\n")` 而不是 `lines().size`：后者"结尾那个换行算不算一行"是 stdlib 的口径，
     * 而 ④ 的靶子期望值是**手写字面数**，钉的必须与复算时同一把尺。
     */
    private fun newlines(text: String): Int = occurrences(text, "\n")

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

    // ---- T91（#135）的挖段重钉：被授权改动的只有「三次失手之后还许不许再试」那一条判据 ----

    /** 挖哪一侧：Current = 工作树，Baseline = `git show` 取回的那一份 */
    private enum class T91Side { Current, Baseline }

    /**
     * @param addedByThisCard true = 这一段是 T91 新增的（基线侧必须**找不到**它），
     *                        false = 这一段 T91 被授权改动（两侧都按同一对锚点挖掉，剩下的字节照比）
     */
    private data class T91Span(
        val start: String,
        val end: String,
        val addedByThisCard: Boolean,
        val what: String,
        val minChars: Int,
        val maxChars: Int,
    )

    private val t91KernelSpans = listOf(
        T91Span(
            start = " * ## T91 收的那笔账",
            end = " * ## 为什么发火判据吃的是",
            addedByThisCard = true,
            what = "文件注释里 T91 收账那一节",
            minChars = 400, maxChars = 4_000,
        ),
        T91Span(
            start = "    // T91：三次失手之后不再一票封死",
            end = "    ledger.misses >= SecondEngineGiveUpAfterMisses -> SecondEngineDecision.GaveUp",
            addedByThisCard = true,
            what = "判据里那一发复探的分支",
            minChars = 200, maxChars = 2_000,
        ),
        T91Span(
            start = "// T91：复探的常数与判据成对放在这里",
            end = "/**\n * 第二引擎的探针档位",
            addedByThisCard = true,
            what = "复探常数与判据本体",
            minChars = 1_200, maxChars = 8_000,
        ),
        T91Span(
            start = "    is SecondEngineDecision.GaveUp ->",
            end = "    is SecondEngineDecision.EngineUnusable ->",
            addedByThisCard = false,
            what = "判死那一档的措辞",
            minChars = 40, maxChars = 900,
        ),
        T91Span(
            start = "    /** 连续几次没解出来",
            end = "    internal object GaveUp",
            addedByThisCard = false,
            what = "GaveUp 那一档的一句话文档",
            minChars = 20, maxChars = 400,
        ),
    )

    /**
     * 挖掉 [t91KernelSpans] 那几段。三条纪律一枚不少，都是这一族守卫的既有口径：
     * 锚点找不到就抛（不许退化成"没比也算过"）、段长越出区间也抛（锚点对上但整段被换掉也算漂）、
     * "本卡新增"的那些段在**基线侧**必须找不到（找得到就是基线取错了 —— 拿今天比今天的尺子量不出事）。
     */
    private fun cutT91Spans(text: String, label: String, side: T91Side): String {
        var rest = text
        for (span in t91KernelSpans) {
            val at = rest.indexOf(span.start)
            if (side == T91Side.Baseline && span.addedByThisCard) {
                check(at < 0) { "$label 里已经带着 T91 新增的「${span.what}」：基线取错了，这么比量不出任何东西" }
                continue
            }
            check(at >= 0) { "$label 里找不到 T91 段的锚点「${span.what}」（「${span.start.take(20)}」）：那段挪过家了，钉法要跟着重看" }
            val stop = rest.indexOf(span.end, at + span.start.length)
            check(stop > at) { "$label 里「${span.what}」的收尾锚点找不到：段边界漂了" }
            check(stop - at in span.minChars..span.maxChars) {
                "$label 里「${span.what}」长 ${stop - at} 字符，越出 [${span.minChars},${span.maxChars}]：钉法本身漂了"
            }
            rest = rest.removeRange(at, stop)
        }
        return rest
    }

    // ---- T245（#434 / T242-C1）的挖段重钉：被授权改的是「闸门留痕算不算手里有码」那一颗派生判据与它的措辞 ----

    /**
     * @param addedByThisCard true = 这一段是 T245 新增的（基线侧必须**找不到**它），
     *                        false = 这一段 T245 被授权改动（两侧按同一对锚点挖掉，剩下的字节照比）
     */
    private data class T245Span(
        val start: String,
        val end: String,
        val addedByThisCard: Boolean,
        val what: String,
        val minChars: Int,
        val maxChars: Int,
    )

    private val t245KernelSpans = listOf(
        T245Span(
            start = "/**\n * 闸门那份留痕的三种处境（T245）",
            end = "/**\n * 这一帧要不要交给第二引擎补解。",
            addedByThisCard = true,
            what = "SecondEngineHandState 三档与 secondEngineCodeInHand 派生判据",
            minChars = 800, maxChars = 12_000,
        ),
        T245Span(
            start = "    /** 闸门里",
            end = "    internal object CodeInHand",
            addedByThisCard = false,
            what = "CodeInHand 那一档的一句话文档",
            minChars = 10, maxChars = 900,
        ),
        T245Span(
            start = "    is SecondEngineDecision.CodeInHand ->",
            end = "    is SecondEngineDecision.OutOfBudget ->",
            addedByThisCard = false,
            what = "CodeInHand 的停痕措辞",
            minChars = 20, maxChars = 900,
        ),
        T245Span(
            start = " * @param codeInHand",
            end = " * @param engineUsable",
            addedByThisCard = false,
            what = "secondEngineDecision 的 @param codeInHand",
            minChars = 20, maxChars = 1_200,
        ),
        // 复探五道闸门里 `!codeInHand` 那一条 KDoc 不单列：它在 afterMissesReprobeAllowsReprobe
        // 的头上，而那颗函数是 T91 新增的（t91KernelSpans 第三段 addedByThisCard），T90 基线侧
        // 整段都不存在 —— 本卡在这一段里动的字由 T91 那一段一并挖掉，两侧同形。
    )

    /**
     * 挖掉 [t245KernelSpans] 那几段（T245）。纪律与 [cutT91Spans] 同源：锚点找不到就抛、
     * 段长越界就抛、「本卡新增」那一段在基线侧必须找不到。
     *
     * 先挖 T245 再挖 T91：T91 的第三段（复探常数与判据本体）把 afterMissesReprobeAllowsReprobe
     * 整颗函数连同它的 KDoc 一起盖住了，反过来的话 T245 那条 KDoc 锚点会先被挖没。
     */
    private fun cutT245Spans(text: String, label: String, side: T91Side): String {
        var rest = text
        for (span in t245KernelSpans) {
            val at = rest.indexOf(span.start)
            if (side == T91Side.Baseline && span.addedByThisCard) {
                check(at < 0) { "$label 里已经带着 T245 新增的「${span.what}」：基线取错了，这么比量不出任何东西" }
                continue
            }
            check(at >= 0) { "$label 里找不到 T245 段的锚点「${span.what}」（「${span.start.take(20)}」）：那段挪过家了，钉法要跟着重看" }
            val stop = rest.indexOf(span.end, at + span.start.length)
            check(stop > at) { "$label 里「${span.what}」的收尾锚点找不到：段边界漂了" }
            check(stop - at in span.minChars..span.maxChars) {
                "$label 里「${span.what}」长 ${stop - at} 字符，越出 [${span.minChars},${span.maxChars}]：钉法本身漂了"
            }
            rest = rest.removeRange(at, stop)
        }
        return rest
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

    // ---- T92（#133）的挖段重钉：被授权改动的只有 ScanFrameFlowPolicy 头部「空转有多少帧」那一段 ----

    /**
     * 挖掉那一段。三条纪律与 [cutT91Spans] 同源：起始锚点必须唯一（不唯一=边界不成立）、
     * 收尾锚点找不到就抛、段长越界就抛。两个锚点本身都**不在**被挖范围内，所以"挪个位置再改"
     * 与"改掉锚点"都会红。基线侧与工作树侧走同一对锚点，剩下的字节照比。
     */
    private fun cutT92FrameFlowSpan(text: String, label: String): String = text.removeRange(t92FrameFlowRange(text, label))

    /** 只取那一段（靶子用） */
    private fun t92FrameFlowSection(text: String): String = text.substring(t92FrameFlowRange(text, FRAME_FLOW_FILE))

    private fun t92FrameFlowRange(text: String, label: String): IntRange {
        val at = text.indexOf(T92_FRAME_FLOW_START)
        check(at >= 0) { "$label 里找不到空转速率那一段的起始锚点「$T92_FRAME_FLOW_START」：那段挪过家或改了措辞，钉法要跟着重看" }
        check(text.indexOf(T92_FRAME_FLOW_START, at + 1) < 0) {
            "$label 里起始锚点「$T92_FRAME_FLOW_START」出现两次：被挖段的边界不唯一，逐字节比较无从谈起"
        }
        val stop = text.indexOf(T92_FRAME_FLOW_END, at)
        check(stop > at) { "$label 里那一段的收尾锚点「$T92_FRAME_FLOW_END」找不到：段边界漂了" }
        check(stop - at in T92_FRAME_FLOW_MIN..T92_FRAME_FLOW_MAX) {
            "$label 里那一段长 ${stop - at} 字符，越出 [$T92_FRAME_FLOW_MIN,$T92_FRAME_FLOW_MAX]：不像是一段读数（钉法本身漂了）"
        }
        return at until stop
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
        const val STATUS_FILE = "com/buaa/schedule/ui/signin/ScanUiStatus.kt"
        const val FRAME_LEDGER_FILE = "com/buaa/schedule/ui/signin/ScanCameraAidPolicy.kt"
        const val ADMISSION_FILE = "com/buaa/schedule/ui/signin/ScanDecodingAdmission.kt"
        const val SECOND_ENGINE_FILE = "com/buaa/schedule/ui/signin/ScanSecondEnginePolicy.kt"
        const val RECOVERY_FILE = "com/buaa/schedule/ui/signin/ScanRecoveryPolicy.kt"
        const val FRAME_FLOW_FILE = "com/buaa/schedule/ui/signin/ScanFrameFlowPolicy.kt"
        const val GATE_FILE = "com/buaa/schedule/ui/signin/ScanSubmissionGate.kt"
        const val VIEW_MODEL_FILE = "com/buaa/schedule/ui/signin/SignInViewModel.kt"

        /** 本卡的起点（master d7af6f8）：④ 那几处反向钉按这一枚逐字节核对 */
        const val T90_BASELINE = "d7af6f8"

        /** T245② 授权改过 ScanSubmissionGate.kt 的措辞，这一枚反向钉的基点因此前移到这里 */
        const val T245_GATE_BASELINE = "ddf493f"

        /**
         * T257③：状态机漏斗被授权改过的那一手（第四处 `if (flight.value) return` 换成
         * ScanFlightAdmission 分档 + 一句卡面 + 一行 Log.w），所以 [VIEW_MODEL_FILE] 的逐字节
         * 基点前移到这一枚；靶子在本条守卫里当场复算新旧两份的字节差异。
         */
        const val T257_VIEW_MODEL_BASELINE = "00b0b51"

        /**
         * T395①：[VIEW_MODEL_FILE] 的逐字节基点第二次前移。被授权的只有闸门那一次原子换
         * （领取 + 落回各一枚 `compareAndSet`），差集形状由本条守卫里那枚 T395 靶子当场复算。
         * [T257_VIEW_MODEL_BASELINE] 留在原地不删 —— 它是靶子的"前一头"。
         */
        const val T395_VIEW_MODEL_BASELINE = "869fc13"

        /**
         * T404①：[VIEW_MODEL_FILE] 的逐字节基点第三次前移。被授权的只有状态机 `fail(error)` 那一句
         * 用户文案的出处（`error.message ?: "网络异常，请稍后重试"` → `SubmitFailureClassifier.screenLine`
         * ＋一行 `.forensicsLine`，另加两段 KDoc/注释），闸门那一族与漏斗一枚都没动。
         * [T395_VIEW_MODEL_BASELINE] 留在原地不删 —— 它是这一枚靶子的"前一头"。
         */
        const val T404_VIEW_MODEL_BASELINE = "bd0eca7"

        /**
         * T92（#133）被授权改动的那一段（[FRAME_FLOW_FILE] 头部"空转有多少帧"的读数）的两个锚点，
         * 与 [ScanBlankDecodingWiringGuardTest] ⑤b 用的是同一对（两边各留一份副本，同这一族
         * 其它工具的既有口径）。起始那句是上一句的尾巴、改动前后逐字保留；收尾那句是下一节的开头。
         */
        const val T92_FRAME_FLOW_START = "这就是本卡的账"
        const val T92_FRAME_FLOW_END = "所以这里只问三件事"

        /** 139 = 基线（T90 起点）那段实际长度；1,815 = T92 本轮那段实际长度 */
        const val T92_FRAME_FLOW_MIN = 100
        const val T92_FRAME_FLOW_MAX = 4_000

        /** "复现法确实写进去了"的靶子锚点 */
        const val T92_RECIPE_ANCHOR = "怎么复现"
    }
}
