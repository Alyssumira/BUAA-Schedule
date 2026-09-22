package com.buaa.schedule.ui.signin

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 三条**裸静默支路**与「帧到达证据」的接线守卫（T59③⑤）。
 *
 * ③ 那三条的共性是：走到那一支之后，程序状态变了、用户那边什么都没有。
 * 按帧的那一条（解出条码却读不出原文）只能留痕 —— 做成 UI 就是每秒十几条重写组合；
 * 按次的那两条（相册取消 / 相册根本没开始、以及飞行中按下的按钮）是**界面**问题，
 * 修法是让动作本身反映实情（点不动、或者说得出"这次没开始"），不是排队重投。
 *
 * ⑤ 帧计数与首帧标记是这两类留痕的唯一时间轴：判据要算"停用窗口过完没有"，
 * 取证要能回答"相机到底有没有在送帧"。它们按帧跑，所以**按帧路径一次分配都不许有**，
 * 而这条性质在 JVM 里只能按源码形状核（跑不到 analyzer）。
 *
 * 手法沿用 `ScanSubmissionGateTest` / `BarhopperNativeLibProbeTest`：读源码文本、
 * 配平取函数体、**找不到锚点就抛**（静默跳过等于没有守卫）、匹配前先抹注释。
 */
class ScanSilentBranchGuardTest {

    /**
     * ① 第一支：`codes.firstOrNull()?.rawValue` 为 null 那一档不再同形。
     *
     * 钉三件事：null 分支存在、它走的是"看到一枚没有原文的条码"这一条计数路、
     * 而说话由节流判据管（第一次必说、之后每 stride 一次）—— 没有节流的话这一条
     * 守卫本身就会把用户那台机器的 logcat 冲干净。
     */
    @Test
    fun valuelessBarcodeBranchCountsAndThrottlesItsTrace() {
        val analyzer = analyzerBody()
        val success = balancedBlock(analyzer, ".addOnSuccessListener { codes ->")
        check(!success.contains("?.let")) { "成功分支又回到 `?.let {}` 那一份静默写法：\n$success" }
        assertTrue("没有 rawValue 那一档不再走计数支路：\n$success", success.contains("noteNoReadableValue("))
        assertTrue("原文取值与「有没有条码」分不开（第一条支路就还是同形的）：\n$success", success.contains("val raw = first?.rawValue"))

        val note = balancedBlock(analyzer, "private fun noteNoReadableValue(")
        assertTrue("没数账（只有日志的话重启之后就什么都不是）：\n$note", note.contains("++valuelessCodes"))
        assertTrue(
            "留痕没走节流判据 —— 这是按帧路径，一帧一行的日志等于没有日志：\n$note",
            note.indexOf("shouldLogValuelessBarcode(") in 0 until note.indexOf("Log.w("),
        )
        assertTrue("干脆没条码（空结果，常态）那一档不许说话：\n$note", note.contains("if (!sawBarcodeWithoutText) return"))
    }

    /**
     * ② ⑤ 的首帧标记：每次绑定一行，把「相机没送帧」与「送了帧但没结果」分开。
     *
     * 顺序是这里唯一有意思的部分：计数自增必须排在停用判断**之前**，否则停用期间
     * 帧不再到达计数 —— 内核就永远算不出"窗口过完了"，这一档只剩退出重进。
     */
    @Test
    fun firstFrameMarkerIsOncePerBindAndTheCounterRunsAheadOfTheSuspension() {
        val analyzer = analyzerBody()
        val analyze = balancedBlock(analyzer, "override fun analyze(")
        val counting = analyze.indexOf("val frame = ++frameCount")
        val skip = analyze.indexOf("decoderFrameAction(health, frame)")
        check(counting >= 0 && skip >= 0) { "帧计数或停用判据不在 analyze 里了，⑤ 的证据链断了：\n$analyze" }
        assertTrue("计数排到了停用判断后面（停用窗口里的帧不再推进时间轴，窗口永远过不完）：", counting < skip)
        assertTrue(
            "首帧标记排到了停用判断后面（那种情况下这一行永远不出，读起来就是「帧没到过」）：",
            analyze.indexOf("firstFrameLogged = true") in counting until skip,
        )
        assertEquals("首帧那一行的调用点只能一处：", 2, occurrences(analyzer, "logFirstFrameArrived("))
        val marker = balancedBlock(analyzer, "private fun logFirstFrameArrived(")
        assertTrue("首帧标记没带帧号（那就对不上内核的账）：\n$marker", marker.contains("第 \$frame 帧"))
    }

    /**
     * ②b（T59b①）首帧标志的复位点：每一次绑定都要重新报得出首帧行。
     *
     * 上一档只钉了"置位与帧号的次序"，没钉"复位"—— 而"每次绑定一行"这半句恰恰是当时
     * 做不到的：analyzer 实例的存活期比一次绑定长（`remember(scanner, bindGeneration)`），
     * `scannerWorking` 翻回 true 那条自动恢复路径重跑绑定却不换实例，标志不复位的话
     * 第二次绑定起就永远没有首帧行 —— 读证据的人又分不清「这次绑定没换来帧」和
     * 「标志早就烧掉了」，这一行存在的唯一理由就没了。复位钩子就是绑定成功时必被调一次的
     * [QrCodeAnalyzer.markBindStarted]。
     * 顺带钉住帧号时间轴不许被复位逻辑连坐：`val frame = ++frameCount` 仍排在停用判断之前，
     * 而 markBindStarted 里不许出现把 frameCount 一起清零的"顺手"。
     */
    @Test
    fun everyBindRearmsTheFirstFrameMarkerWithoutTouchingTheFrameTimeline() {
        val analyzer = analyzerBody()
        val mark = balancedBlock(analyzer, "fun markBindStarted(")
        assertTrue(
            "重绑不再复位首帧标志（回到什么样就是坏了：第二次绑定起永远没有首帧行，" +
                "「没换来帧」与「标志烧掉了」又读不出来了）：\n$mark",
            mark.contains("firstFrameLogged = false"),
        )
        assertFalse(
            "绑定钩子里顺手把 frameCount 也清了（回到什么样就是坏了：那是内核算停用窗口的时间轴，" +
                "清零就等于窗口永远过不完）：\n$mark",
            mark.contains("frameCount"),
        )
        val analyze = balancedBlock(analyzer, "override fun analyze(")
        val counting = analyze.indexOf("val frame = ++frameCount")
        val skip = analyze.indexOf("decoderFrameAction(health, frame) == DecoderFrameAction.Skip")
        check(counting >= 0 && skip >= 0) { "帧计数或停用判断换了写法，⑤ 的证据链要跟着重核：\n$analyze" }
        assertTrue(
            "帧号自增排到了停用判断后面（回到什么样就是坏了：停用窗口里的帧不再推进时间轴，" +
                "窗口永远过不完，「第 N 帧」也不再是全局帧号）：",
            counting < skip,
        )
    }

    /**
     * ③ 按帧路径本身不许直接写日志：所有留痕都必须在"状态翻面 / 节流通过 / 只发生一次"
     * 之后，由那几颗辅助函数说。这一条就是"must not allocate per frame"的可核形式 ——
     * 一条 `Log.i(...)` 的字符串拼接就是一次分配，放在入口等于每秒几十次。
     */
    @Test
    fun perFrameEntryPathCarriesNoLoggingAndNoStateWrite() {
        val analyzer = analyzerBody()
        val analyze = balancedBlock(analyzer, "override fun analyze(")
        assertEquals("analyze 入口路径里直接出现了 Log. 调用（按帧分配）：\n$analyze", 0, occurrences(analyze, "Log."))
        // scannerWorking 那一份真相只能由内核经回调推，不许在帧路径上顺手写死
        assertFalse("帧路径上自己写了 scannerWorking（棘轮就会从这一头复活）：\n$analyze", analyze.contains("scannerWorking ="))
        assertFalse("帧路径上出现了 consumed 那颗布尔死锁：\n$analyze", analyze.contains("consumed"))
    }

    /**
     * ④ 相册那两条：取消要说一声，"根本没开始"要说清是哪一条病因。
     *
     * 钉的是拆分本身：以前三个条件捏成一个 `if (a && b && c)`，落空就是纯静默，
     * 而那颗按钮的 enabled 只看 `scanner != null` —— 于是"判定没到手"那一档点得动、
     * 点下去什么都没有。
     */
    @Test
    fun galleryCancelAndBlockedBranchesBothSaySomething() {
        val code = withoutComments(readMainSource(SCAN_SCREEN_FILE))
        val gallery = balancedBlock(code, "ActivityResultContracts.PickVisualMedia(),")
        assertTrue("uri == null 那一档又回到静默：\n$gallery", gallery.contains("if (uri == null)"))
        val cancelLog = gallery.indexOf("相册选图被取消")
        assertTrue("取消那一档没有留痕（「什么都没发生」与「用户没选图」读不出来）：\n$gallery", cancelLog >= 0)
        assertTrue(
            "两条支路没拆开（取消与「解码器不在」混成一行就没法对账）：\n$gallery",
            gallery.indexOf("reportGalleryBlocked(") > cancelLog,
        )
        // 挡住那一支必须先说清、再落到状态机，且必须排在真正解码之前
        val blocked = gallery.indexOf("scanner == null ||")
        check(blocked >= 0) { "相册那条不再是拆开的两道判断，③ 的第二支回来了：\n$gallery" }
        assertTrue("被挡住那一支没走 reportGalleryBlocked：\n$gallery", gallery.indexOf("reportGalleryBlocked(") > blocked)
        assertTrue(
            "相册那条被卷进提交闸门了（它是一次动作，没有风暴要挡）：\n$gallery",
            !gallery.contains("shouldSubmitScan("),
        )
        assertTrue("挡住那一支不报相机侧的帧数（两份证据对不上）：\n$gallery", gallery.contains("framesArrived()"))
    }

    /**
     * ⑤ 飞行中的按下不再被吞：守卫读同一个来源，界面按同一个来源把按钮按灭。
     *
     * 排队被明确排除（本卡的取舍）：签到重复提交比"这次没吃进去"更糟，
     * 所以这里钉的是「没有第二份 inFlight」，而不是「有重试队列」。
     */
    @Test
    fun inFlightTapsDisableTheAffordanceInsteadOfVanishing() {
        val vm = withoutComments(readMainSource(VIEW_MODEL_FILE))
        assertEquals("inFlight 有了第二个来源：", 1, occurrences(vm, "MutableStateFlow(false)"))
        assertFalse("还留着裸 Boolean 的 inFlight（界面就读不到它）：", vm.contains("private var inFlight"))
        assertTrue("inFlight 没作为 StateFlow 暴露出去：", vm.contains("val inFlight: StateFlow<Boolean>"))
        // 三处吞动作的守卫读的都是那颗 flow
        assertEquals("if (flight.value) return 应当有 4 处（signIn/reset/reportNoQrCode/reportGalleryBlocked）：",
            4, occurrences(vm, "if (flight.value) return"))
        assertFalse("签到入口在排队重投（本卡明确不许）：", vm.contains("Channel<") || vm.contains("enqueue"))

        val screen = withoutComments(readMainSource(SCAN_SCREEN_FILE))
        assertTrue("界面没 collect inFlight：按钮的 enabled 就是凭空的", screen.contains("viewModel.inFlight.collectAsState()"))
        assertEquals("结果卡那两颗按钮（重新扫码 / 继续扫码）都得由它按住：", 2, occurrences(screen, "enabled = !inFlight"))
        assertTrue("相册那颗不看 inFlight（还是会被吞一次）：", screen.contains("scanner != null && !inFlight"))
    }

    /**
     * ⑥ 相册「没开始」与「没认出码」必须是两句话。
     *
     * 复用的代价很具体：那张卡会说"换一张更清晰的图"，而这一条路根本没去解图 ——
     * 用户就会反复挑同一张好图。
     */
    @Test
    fun blockedGalleryHasItsOwnSentence() {
        val vm = withoutComments(readMainSource(VIEW_MODEL_FILE))
        val blocked = balancedBlock(vm, "fun reportGalleryBlocked()")
        check(blocked.contains("SignInState.Failed(")) { "相册被挡住那一档没落到失败卡上：\n$blocked" }
        val noCode = balancedBlock(vm, "fun reportNoQrCode()")
        val blockedText = blocked.substringAfter("Failed(").substringBefore("relogin")
        assertTrue("两档说成了同一句话：", blockedText != noCode.substringAfter("Failed(").substringBefore("relogin"))
        for (banned in listOf("换一张", "更清晰", "手输", "输入签到码")) {
            assertFalse("「没开始」那一档把用户指向挑图/输入（$banned）：$blockedText", blockedText.contains(banned))
        }
    }

    /**
     * ⑦ 新留痕的级别与归属：只留 Info/Warn，不登记进那份审计名单。
     *
     * 两件事都要写明：
     * - 用户那台机器（HyperOS）把 logcat 砍到 Info、release 又剥 Verbose ⇒ 写 `Log.d`
     *   等于没写，所以这一页的取证行一律 Info/Warn（本条钉住，别下一轮"顺手降级"）；
     * - 正因为它们本来就是 release 可见的，就**不需要** `if (BuildConfig.DEBUG)` 包 ——
     *   这一页没有任何 debug-only 输出（本条也钉住，包了就说明有人在按帧路径上偷偷加了Verbose）。
     * - 不把它们加进 `ReleaseForensicLogSurvivalTest.SITES`：那份名单是省电审计 §4.3/§4.4
     *   的过滤条件清单（按 tag 过滤才有账），没有一行读扫码页的 tag；扫码链的存活由
     *   同一份名单的"仓库里没有删 `android.util.Log` 的 assume 规则"那条全局守卫兜着。
     */
    @Test
    fun newTracesStayAtInfoOrWarnAndAreNotDebugGated() {
        val code = withoutComments(readMainSource(SCAN_SCREEN_FILE))
        for (banned in listOf("Log.d(", "Log.v(", "Log.e(")) {
            assertEquals("扫码页取证行用了 $banned（Info 以下在读不到，级别漂过就红）：", 0, occurrences(code, banned))
        }
        assertEquals("这一页出现了 debug-only 门禁（本卡新增行全是 release 可见的取证行）：", 0, occurrences(code, "BuildConfig.DEBUG"))
        val vm = withoutComments(readMainSource(VIEW_MODEL_FILE))
        assertEquals("ViewModel 里也不许有 debug-only 输出：", 0, occurrences(vm, "BuildConfig.DEBUG"))
        // 级别扫查要有靶子：一条 Log 都没扫到就是解析器或路径坏了
        assertTrue("扫码页一行 Log. 都没扫到：", occurrences(code, "Log.") > 5)
    }

    /**
     * ⑧ 内核纯度（T59①② 那份判据的硬门槛）：零 import、零 android、零取钟口。
     *
     * 停用与退避全部以**帧数**表达，所以这里连墙钟都不许出现 —— 一旦判据自己去读时钟，
     * 表驱动单测就得造假时钟，而 [ScanSubmissionGateTest] ⑨ 那句"analyzer 里恰好三处
     * `System.currentTimeMillis()`"也会跟着对不上账。
     */
    @Test
    fun recoveryKernelStaysPureJvmAndClockFree() {
        val raw = readMainSource(POLICY_FILE)
        val code = withoutComments(raw)
        val imports = code.lines().filter { it.trim().startsWith("import ") }
        assertTrue("$POLICY_FILE 里出现了 import，这段判据就到不了 JVM：\n$imports", imports.isEmpty())
        for (banned in listOf("android.", "androidx.", "SystemClock", "System.currentTimeMillis", "Date(", "Instant")) {
            assertFalse("判据本体自己去碰了设备/时钟（$banned）—— 设备事实必须是参数", code.contains(banned))
        }
        // 空白判据文件等于什么都没扫（本仓的守卫一律要求命中数 > 0）
        assertTrue("内核文件是空的？", code.length > 1_000)
        assertTrue("棘轮的三档界限不在内核里：", code.contains("ConsecutiveDecodeFailureLimit"))
    }

    // ---- 源码核对工具 ----

    private fun analyzerBody(): String = balancedBlock(withoutComments(readMainSource(SCAN_SCREEN_FILE)), "private class QrCodeAnalyzer(")

    private fun readMainSource(relativeFromJava: String): String {
        val file = File(findMainJavaDir(), relativeFromJava)
        assertTrue("找不到 ${file.path}：文件挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText()
    }

    /** 从 [signature] 之后第一个 `{` 起配平到对应右括号（含）；找不到锚点就抛，静默跳过等于没有守卫 */
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

    /** 单测的 cwd 是 :app 模块目录，也可能是仓库根：两种布局都试，全落空就抛 */
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

    private companion object {
        const val SCAN_SCREEN_FILE = "com/buaa/schedule/ui/signin/SpocScanScreen.kt"
        const val VIEW_MODEL_FILE = "com/buaa/schedule/ui/signin/SignInViewModel.kt"
        const val POLICY_FILE = "com/buaa/schedule/ui/signin/ScanRecoveryPolicy.kt"
    }
}
