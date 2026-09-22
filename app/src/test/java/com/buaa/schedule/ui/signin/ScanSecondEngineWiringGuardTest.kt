package com.buaa.schedule.ui.signin

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * T66「第二引擎（zxing-cpp 兜底）」接线的源码形状守卫 + 反向钉。
 *
 * 判据本体在 [ScanSecondEnginePolicy] 里已经有表驱动单测（`ScanSecondEnginePolicyTest`），
 * 这里只管四件 JVM 跑不到的事：
 *
 * ① 内核保持零 import、不吃设备事实，也不认识任何解码器的类；
 * ② 页面**真的**接了它，而且接在对的位置上 —— 补解排在把帧交给 ML Kit 之前、
 *    `ImageProxy` 的 close 时机一个字没动、失败一行都不写进 ML Kit 那本健康度账、
 *    递交只走两条引擎共用的那道闸门、账本随绑定复位；
 * ③ 全仓 zxing-cpp 的类型只许出现在那一枚薄壳里（换引擎的口子必须只有一个），
 *    兜底的缺席**不许**变成一档 UI 文案；
 * ④ 反向钉：T65 撤走的东西（手电/补光一整族、ML Kit 缩放建议 API、T64 的固定缩放档）
 *    一个字都不许回来，而 T66 本卡**没碰过**的那几份文案/探针文件要逐字节对得上基线。
 *
 * 手法沿用 [ScanCameraAidWiringGuardTest] / [ScanSilentBranchGuardTest]：读源码文本、
 * 匹配前先抹注释、找不到锚点就抛（静默跳过等于没有守卫）、每档反向钉都配一个必须命中的靶子。
 */
class ScanSecondEngineWiringGuardTest {

    // ---- ① 内核纯度 ----

    /** ①a 判据内核零 import、零设备符号、函数与常数一枚不少（否则表驱动就塌回真机） */
    @Test
    fun secondEngineKernelStaysPureJvm() {
        val raw = readMainSource(KERNEL_FILE)
        val code = withoutComments(raw)
        val imports = code.lines().filter { it.trim().startsWith("import ") }
        assertTrue("$KERNEL_FILE 里出现了 import，这段判据就到不了 JVM：\n$imports", imports.isEmpty())
        for (
            banned in listOf(
                "android.", "androidx.", "ImageProxy", "BarcodeReader", "zxingcpp",
                "Build.", "SystemClock", "System.currentTimeMillis", "Log.",
            )
        ) {
            assertFalse("判据本体自己去碰了设备/解码器（$banned）—— 这些都必须是参数", code.contains(banned))
        }
        assertTrue("内核文件是空的？", code.length > 3_000)
        for (entry in listOf(
            "fun retryableRung", "fun secondEngineDecision", "fun secondEngineAfterFire",
            "fun secondEngineAfterResult", "fun secondEngineStopText", "fun secondEngineProbeOnConstruct",
        )) {
            assertTrue("内核少了 $entry 这一档：", code.contains(entry))
        }
        for (cadence in listOf(
            "SecondEngineStreakFrames", "SecondEngineFrameGap",
            "SecondEngineMaxFiresPerBind", "SecondEngineGiveUpAfterMisses",
        )) {
            assertTrue("节流常数 $cadence 不在内核里：", code.contains(cadence))
        }
        // 账本必须是全默认值的数据类（调用点整枚换引用；半改的账本会让两本计数各说各话）
        assertTrue("账本不再是带默认值的数据类：", code.contains("internal data class SecondEngineLedger("))
        assertTrue("停法的处境不再是个封闭档级：", code.contains("internal sealed class SecondEngineDecision"))
    }

    /**
     * ①b 触发量 `retryableStreak` 挂在帧观测那本账上，而且与缩放那本账分家。
     *
     * 这一条守的是整颗内核唯一的输入量：它要是被改回"读设备"或"与 tooSmallStreak 合并"，
     * 第二引擎就会在没有缩放控制的设备上永远不触发（那恰恰是最需要它的设备）。
     */
    @Test
    fun triggerVolumeLivesInTheFrameLedgerAndIgnoresZoomCapability() {
        val code = withoutComments(readMainSource(FRAME_LEDGER_FILE))
        assertTrue("ScanAssistState 不再带 retryableStreak：", code.contains("val retryableStreak: Long = 0L"))
        val advance = balancedBlock(code, "internal fun advanceScanAssist(")
        assertTrue(
            "可重试连击不再由 [retryableRung] 算出（改成自己判档位就是第二份判据）：\n$advance",
            advance.contains("if (retryableRung(rung)) state.retryableStreak + 1L else 0L"),
        )
        // 关键分家：那一行既不在 zoomRatio 的分支里、也不看 zoomControlAvailable
        val line = advance.lineSequence().first { it.contains("retryableStreak = if (retryableRung") }
        assertFalse("可重试连击又开始看缩放能力了（两本账合并=一本会说谎）：$line", line.contains("zoomControlAvailable"))
        assertTrue("账本没把新计数器带下去：\n$advance", advance.contains("retryableStreak = retryableStreak,"))
        // 靶子：同一段里缩放那本账确实还在看设备能力（零命中=扫错了文件）
        assertTrue("缩放连击的口径变了，本条的比对前提要重核：", advance.contains("if (zoomRatio != null) 0L else streak"))
    }

    // ---- ② 接线形状 ----

    /**
     * ②a 补解排在 `scanner.process()` **之前**、Skip 之后，而 close 的时机一个字没动。
     *
     * 这三件事是同一枚硬币：帧的像素在这一刻只有一个读者，所以既不用为兜底复制一份
     * 亮度面（1280×720 的 Y 面就是 0.9 MB/帧），也不用把 close 挪到别处 —— 而 close 挪早
     * 或挪晚都各有过一次真实失效（见 analyze 里那段注释）。
     */
    @Test
    fun fallbackRunsOnTheFrameBeforeMlKitGetsItAndCloseStaysPut() {
        val analyze = balancedBlock(withoutComments(readMainSource(SCAN_SCREEN_FILE)), "override fun analyze(")
        val fallback = analyze.indexOf("trySecondEngine(image, frame)")
        val process = analyze.indexOf("scanner.process(")
        val skip = analyze.indexOf("decoderFrameAction(health, frame)")
        check(fallback >= 0 && process >= 0 && skip >= 0) {
            "补解调用、ML Kit 派发或停用判断的形状变了，这一条要跟着改：\n$analyze"
        }
        assertTrue("停用窗口里的帧也被喂给兜底了（Skip 之后就不该再有解码动作）：", fallback > skip)
        assertTrue("补解排到了 ML Kit 之后（那一帧的 buffer 已有第二个读者，proxy 生命周期也不再是我们说了算）：", fallback < process)
        // close 的四发一枚不少、而且仍挂在 onCompleteListener 上（不许挪进 finally、也不许提前）
        assertEquals("close 的调用点数目变了（Skip/空帧/同步抛/回调各一）：", 4, occurrences(analyze, "image.close()"))
        assertTrue("close 不再挂在 addOnCompleteListener 上：\n$analyze", analyze.contains(".addOnCompleteListener { image.close() }"))
        assertFalse("close 被挪进了 finally（ML Kit 还没读帧就报 Image is already closed）：", analyze.contains("} finally {"))
        // 兜底排在取 mediaImage 之后：那一档 null 就直接 return，不该为它构造任何东西
        assertTrue(
            "补解排到了 mediaImage 判空之前（那一档直接 return，为它多走一步是白工）：",
            fallback > analyze.indexOf("if (mediaImage == null)"),
        )
    }

    /**
     * ②b `trySecondEngine` 只做四件事：递参数、按结论发不发、记账、留痕。
     *
     * 四道禁写各守一类失效：写 `health` 就是把兜底的故障记到主力头上（T59① 那类
     * "把暂时说成永久"从另一头复活）；自己判"要不要发"就是判据的第二份；
     * 直接 `onCode` 就是绕过共用闸门；日志不内核化就是手拼措辞。
     */
    @Test
    fun fallbackCallSiteDelegatesEveryJudgementAndWritesNothingButItsOwnLedger() {
        val analyzer = balancedBlock(withoutComments(readMainSource(SCAN_SCREEN_FILE)), "private class QrCodeAnalyzer(")
        val try2 = balancedBlock(analyzer, "private fun trySecondEngine(")
        for (entry in listOf(
            "secondEngineDecision(", "secondEngineAfterFire(", "secondEngineAfterResult(",
            "secondEngine.ensureReady()", "secondEngine.decode(image)", "secondEngine.mayTry()",
            "submitDecodedText(", "reportSecondEngineStop(",
        )) {
            assertTrue("补解那一段没走 $entry：\n$try2", try2.contains(entry))
        }
        // 判据一枚都不许自己写：连击、间隔、额度、停法全部来自内核
        for (banned in listOf("fires >=", "misses >=", "- ledger.lastFireFrame", "streak >=", "streak <")) {
            assertFalse("调用点自己算起节流了（$banned）—— 判据只许在 [secondEngineDecision]：\n$try2", try2.contains(banned))
        }
        assertFalse("兜底的故障记进了 ML Kit 的健康度账（health）：\n$try2", try2.contains("health ="))
        assertFalse("兜底写进了 decoderFrameAction/healthAfter 那一本账：", try2.contains("healthAfter"))
        assertFalse("绕过共用闸门直接递交：\n$try2", try2.contains("onCode("))
        assertFalse("按帧路径出现 Log.d（HyperOS 砍到 Info，等于没写）：", try2.contains("Log.d("))
        assertFalse("出现 Log.v/Log.e（仓库口径只用 Info/Warn）：", try2.contains("Log.v(") || try2.contains("Log.e("))
        assertTrue("发火与失手都不留痕（静默 no-op 是本页修过三轮的病）：\n$try2", try2.contains("Log.i("))
        assertTrue("引擎起不来那种终局判据得是 Warn：\n$try2", try2.contains("Log.w("))
        // 一切意外都得接住：这一颗的身份是"兜底"，穿出去就是把整页扫码带走
        assertTrue("decode 没包在 runCatching 里（native 抛出来会带走整页）：\n$try2", try2.contains("runCatching { secondEngine.decode(image) }"))
        // 停法那一档：只在换档时说话，且只说一次
        val report = balancedBlock(analyzer, "private fun reportSecondEngineStop(")
        assertTrue("停法没走内核措辞（手拼字符串就是判据的第二份）：\n$report", report.contains("secondEngineStopText(decision)"))
        assertTrue("停法没做换挡去重（每帧一句会把 logcat 冲干净）：\n$report", report.contains("if (decision == secondEngineStopReported) return"))
        assertTrue("常态档（结果卡挂着）用了 Warn：\n$report", report.contains("if (decision is SecondEngineDecision.CodeInHand) Log.i(TAG, line) else Log.w(TAG, line)"))
        assertEquals("停法措辞的出口全页只许一处：", 1, occurrences(analyzer, "secondEngineStopText("))
    }

    /** ②c 注入点与复位点：每轮绑定一份新额度，而引擎是进程内那一份 */
    @Test
    fun engineIsInjectedAtTheAnalyzerAndResetEveryBind() {
        val code = withoutComments(readMainSource(SCAN_SCREEN_FILE))
        val analyzer = balancedBlock(code, "private class QrCodeAnalyzer(")
        assertTrue("分析器不再从外部拿第二引擎（写死在里面就没法测、也没法退役）：", analyzer.contains("private val secondEngine: ZxingCppFallbackDecoder,"))
        val remember = balancedBlock(code, "remember(scanner, bindGeneration)")
        assertTrue("构造点没显式注入进程内那一份：\n$remember", remember.contains("secondEngine = zxingCppFallback,"))
        val mark = balancedBlock(analyzer, "fun markBindStarted(")
        assertTrue("绑定钩子没复位补解账本（旧额度对不上新画面）：\n$mark", mark.contains("secondEngineLedger = SecondEngineLedger()"))
        assertTrue("绑定钩子没复位停法留痕（上一轮为什么闭嘴对不上这一轮）：\n$mark", mark.contains("secondEngineStopReported = null"))
        // 台账字段只能有一处声明，且不许是 @Volatile（唯一的写者就是分析线程那一颗）
        val decl = analyzer.lineSequence().first { it.contains("private var secondEngineLedger") }
        assertFalse("补解账本被标成 @Volatile 却仍有两个写者（$decl）：", decl.contains("@Volatile"))
        assertEquals("补解账本的声明处只能一处：", 1, occurrences(analyzer, "private var secondEngineLedger"))
        assertEquals("停法留痕的声明处只能一处：", 1, occurrences(analyzer, "private var secondEngineStopReported"))
    }

    /**
     * ②d 不许在主线程/绑定路径上等兜底。
     *
     * 两处形状合起来才是这条纪律：全页唯一的 await 是 barhopper 那颗探针的
     * `awaitDecided()`（它在 IO 线程上跑），而 T66 新增的两处 await 计数为零 ——
     * 兜底的结论必须由"第一次真要用的那一下"现场做出来（分析线程、我们自己的 try 里）。
     */
    @Test
    fun nobodyWaitsForTheFallbackOnTheCallingThread() {
        val code = withoutComments(readMainSource(SCAN_SCREEN_FILE))
        assertEquals("扫码页出现了新的 runBlocking/await（不许在组合或主线程等解码器）：", 2, occurrences(code, "awaitDecided()"))
        assertEquals("T66 不该引入任何 await/join：", 0, occurrences(code, "runBlocking"))
        val shell = withoutComments(readMainSource(SHELL_FILE))
        for (banned in listOf("runBlocking", ".await(", "Thread.sleep", "Dispatchers", "withContext", "launch(")) {
            assertFalse("薄壳里出现了阻塞/切线程的写法（$banned）—— 它只许被分析线程就地调用：\n$shell", shell.contains(banned))
        }
        assertTrue("靶子：薄壳确实把结论缓存成整枚 volatile 换（零命中=扫错了文件）：", shell.contains("@Volatile"))
    }

    // ---- ③ zxing-cpp 的类型只许待在那一枚薄壳里 ----

    /** ③a 全仓 import zxingcpp 的文件只有壳那一枚；页面对解码器身份一无所知 */
    @Test
    fun onlyTheShellKnowsTheSecondEngineClass() {
        val hits = mainSources().filter { withoutComments(it.text).contains("import zxingcpp.") }.map { it.relative }
        assertEquals(
            "认识 zxing-cpp 的文件应当只有那一枚薄壳（第二条解码路的口子必须只有一个）：",
            listOf("$MAIN_PREFIX/$SHELL_FILE"),
            hits,
        )
        val page = withoutComments(readMainSource(SCAN_SCREEN_FILE))
        assertFalse("扫码页直接 import 了 zxing-cpp（换引擎就得改页面）：", page.contains("zxingcpp"))
        assertFalse("扫码页自己摸 BarcodeReader：", page.contains("BarcodeReader"))
    }

    /** ③b 兜底的缺席不许变成一档 UI 文案：它不改变用户手上还剩什么 */
    @Test
    fun fallbackUnavailabilityStaysALogLineNotAUiTier() {
        val status = withoutComments(readMainSource(STATUS_FILE))
        for (banned in listOf("SecondEngine", "zxing", "兜底", "第二引擎")) {
            assertFalse("UI 判据里出现了「$banned」—— 兜底缺席是一行取证话，不是新的一档文案：", status.contains(banned))
        }
        // 靶子：这一档确实还是 T65 那几档（零命中=扫错了文件）
        assertTrue("ScanUiStatus 的放弃阶梯不在了：", status.contains("decoderMissing -> "))
        val page = withoutComments(readMainSource(SCAN_SCREEN_FILE))
        assertEquals("扫码页的 UI 档位判据调用点数目变了（兜底不该新增一档）：", 1, occurrences(page, "scanUiStatus("))
        // 预热那一头也不许顺手把 1.7 MB 的 .so 常驻进去（取舍写在壳的类注释里）
        val warmUp = withoutComments(readMainSource(WARM_UP_FILE))
        for (banned in listOf("zxing", "ZxingCpp", "secondEngine", "SecondEngine")) {
            assertFalse("预热链路把兜底引擎也热上了（$banned）—— 那是为罕见路径付常驻内存：", warmUp.contains(banned))
        }
        assertTrue("靶子：预热文件还在跑 ML Kit 那一条（零命中=扫错了文件）：", warmUp.contains("barhopper") || warmUp.contains("Barhopper"))
    }

    // ---- ④ 依赖与裁剪的形状 ----

    /** ④a 依赖坐标/版本/两条 exclude 全在（camera-core 1.5.2 的抬升是真风险） */
    @Test
    fun dependencyIsPinnedAndStopsUpliftingTheCameraFamily() {
        val catalog = readRepoFile(CATALOG_FILE)
        assertTrue("版本目录里没有 zxing-cpp 那一档：", catalog.contains("io.github.zxing-cpp"))
        assertTrue("zxing-cpp 的版本没钉住：", catalog.contains("zxingcppAndroid = \"3.1.1\""))
        val script = readRepoFile(BUILD_SCRIPT)
        assertTrue("app 没依赖 zxing-cpp：", script.contains("implementation(libs.zxingcpp.android)"))
        val at = script.indexOf("implementation(libs.zxingcpp.android)")
        val block = script.substring(at, script.indexOf('\n', script.indexOf('}', at)).let { if (it < 0) script.length else it })
        assertTrue(
            "没 exclude 掉 camera-core：它的 POM 带 1.5.2，会把相机那一族从钉死的 1.4.2 上单独抬走\n$block",
            block.contains("exclude(group = \"androidx.camera\", module = \"camera-core\")"),
        )
        assertTrue("没 exclude 掉 kotlin-stdlib（POM 带 2.3.20，会抬走全仓 stdlib）：\n$block", block.contains("module = \"kotlin-stdlib\""))
        // 反向钉：不许有第二个来源（比如直接写坐标绕过目录）
        assertEquals("依赖声明只能一处：", 1, occurrences(script, "zxingcpp.android"))
        assertFalse("绕过版本目录硬写了 zxing-cpp 的坐标：", Regex("\"io\\.github\\.zxing-cpp:android:").containsMatchIn(script))
    }

    /** ④b 三档非 arm64 的兜底 .so 与主力的三档一起裁（文件名与壳里的库名同源） */
    @Test
    fun releaseStripsBothEnginesOffArm64Only() {
        val script = readRepoFile(BUILD_SCRIPT)
        val marker = "packaging.jniLibs.excludes.addAll"
        val at = script.indexOf(marker)
        check(at >= 0) { "$BUILD_SCRIPT 里找不到 $marker()：release 的 ABI 剪枝换写法了" }
        val argument = lineCommentsOut(balancedParens(script, script.indexOf('(', at)))
        val excluded = Regex("\"lib/([^\"]+)/([^\"]+\\.so)\"").findAll(argument).map { it.groupValues[2] }.toList()
        check(excluded.isNotEmpty()) { "排除清单里一个 .so 都没有：这一条守卫就该退役，而不是留在这里装绿" }
        assertEquals("兜底那颗 .so 的文件名漂了（壳里 loadLibrary 的库名与它必须同源）：", 3, excluded.count { it == ZXINGCPP_SO_FILE })
        assertEquals("主力那颗 .so 少裁了一档：", 3, excluded.count { it == "libbarhopper_v3.so" })
        assertFalse("arm64 档里混进了任何一颗库：", argument.contains("arm64"))
        assertTrue("release 还在用 legacy packaging（.so 压缩入库）：", script.contains("useLegacyPackaging = true"))
    }

    // ---- ④c–④e 反向钉：T65 撤走的东西不许回来 ----

    /** ④c 手电/补光一族在扫码这一页零命中（产品决策 2026-09-22：场景不用，永久撤走） */
    @Test
    fun torchStaysRemovedAcrossTheSigninPage() {
        val files = signinSources()
        for (banned in listOf("torch", "Torch", "手电", "enableTorch", "hasFlashUnit", "补光")) {
            val hits = files.filter { blankCommentsAndLiterals(it.text).contains(banned) }.map { it.relative }
            assertTrue("撤走的东西回来了（$banned）：$hits", hits.isEmpty())
        }
        // 靶子：扫的确实是这一页（空目录会让这一条永远绿）
        assertTrue("扫码这一页的源文件数目不对：", files.size >= 8)
    }

    /** ④d T64 的固定缩放档与 ML Kit 的缩放建议 API：都不许因为"加了第二引擎"而顺手回来 */
    @Test
    fun zoomDeadWeightsStayPinnedOut() {
        val page = blankCommentsAndLiterals(readMainSource(SCAN_SCREEN_FILE))
        for (banned in listOf("ProjectorZoomRatio", "ZoomSuggestionOptions", "setZoomSuggestionOptions")) {
            assertFalse("接了 bundled 路径上的死代码或旧固定档（$banned）：", page.contains(banned))
        }
        assertTrue("靶子：阶梯的正主还在（零命中=扫错了文件）：", page.contains("advanceScanAssist("))
        val shell = blankCommentsAndLiterals(readMainSource(SHELL_FILE)) + blankCommentsAndLiterals(readMainSource(KERNEL_FILE))
        assertFalse("第二引擎开始自己动视场了（缩放只许走 ML Kit 那一侧的阶梯）：", shell.contains("ZoomSuggestion"))
        assertFalse("第二引擎带回了固定缩放档：", shell.contains("ProjectorZoomRatio"))
    }

    /**
     * ④e T66 本卡没碰过的那几份文件逐字节对得上基线。
     *
     * 兜底最容易顺手改到的三处：降级文案（ScanUiStatus）、探针（BarhopperNativeLibProbe）、
     * 预热（ScanChainWarmUp）—— 三者各管一条已经钉死的失效链，本卡一个字都不该动。
     * 基线取本分支的基点（master 003bbdc）：区域重钉的纪律沿用
     * [ScanCameraAidWiringGuardTest]，抄字面量进测试就是第四份真相。
     * git 不可用时判失败而不是静默通过。
     */
    @Test
    fun filesThisCardMustNotTouchAreByteIdenticalToBaseline() {
        for (relative in listOf(STATUS_FILE, PROBE_FILE, WARM_UP_FILE, RECOVERY_FILE, GATE_FILE)) {
            val path = "$MAIN_PREFIX/$relative"
            val baseline = gitShow("$T66_BASELINE", path)
            check(baseline != null) { "git 跑不动或基线取不到（$path@${T66_BASELINE}），反向钉无从核对" }
            assertEquals(
                "$relative 相对基点被改过了：本卡只该加第二引擎，不该动文案/探针/预热那三条链",
                normalizeNewlines(baseline),
                normalizeNewlines(File(findMainJavaDir(), relative).readText()),
            )
        }
    }

    /**
     * ④f 产物层：release 包里两颗解码库都只留 arm64，而兜底那颗确实被压缩入库了。
     *
     * 脚本层的 exclude（④b）只证明"我们许了个愿"，产物层才证明 AGP 真按文件名裁了 ——
     * 这一条的存在理由与 `BarhopperNativeLibProbeTest` ⑤ 相反：那条防的是清单漂走，
     * 这条防的是"清单对了但没生效"（写进 `android{}` 顶层、或版本目录被别的变体覆盖那一类）。
     *
     * ⚠️ 没有 release 产物时 assumeTrue 跳过 ⇒ 门禁顺序必须先跑 `:app:assembleRelease`
     *（仓库口径：跳过数记在门禁里，跳了就不是绿）。
     */
    @Test
    fun releaseApkShipsEachDecoderLibraryForArm64Only() {
        val apk = releaseApk()
        assumeTrue(
            "没有 :app:assembleRelease 的产物（app/build/outputs/apk/release/\u002a.apk），产物层无从判断" +
                "—— 脚本层那两条（④a/④b）才是这条链的下限",
            apk != null,
        )
        val entries = ZipFile(requireNotNull(apk)).use { zip ->
            zip.entries().toList().filter { it.name.startsWith("lib/") && it.name.endsWith(".so") }
        }
        check(entries.isNotEmpty()) { "release 包里一个 .so 都没有：ABI 剪枝伤及无辜了（连 graphics.path 都没了）" }
        for (so in listOf("libbarhopper_v3.so", ZXINGCPP_SO_FILE)) {
            assertEquals(
                "release 包里 $so 只该有 arm64-v8a 那一档（多一档就是拿包体买一条本来没有解码路的 ABI）：",
                listOf("lib/arm64-v8a/$so"),
                entries.map { it.name }.filter { it.endsWith("/$so") },
            )
        }
        // useLegacyPackaging 是新那颗 .so 的全部体积账：不压缩的话兜底一进 release 就是 +1.7MB
        val fallback = entries.firstOrNull { it.name.endsWith(ZXINGCPP_SO_FILE) }
        check(fallback != null) { "release 包里找不到兜底那颗 .so：它压根没被装进来，④b 的裁法要重核" }
        assertEquals(
            "兜底那颗 .so 不是 DEFLATED（useLegacyPackaging 没作用到它头上，release 白涨一档）：" +
                "原始 ${fallback.size} / 压缩后 ${fallback.compressedSize}",
            ZipEntry.DEFLATED,
            fallback.method,
        )
        assertTrue(
            "包里没有 libandroidx.graphics.path.so（按文件名裁的约定被破坏了）：",
            entries.any { it.name.contains("libandroidx.graphics.path.so") },
        )
        // 那颗 .so 在包里，还得有人**叫得动**它：zxing-cpp 的 AAR 自带一条
        // `-keep class zxingcpp.** { *; }`（consumer rules），R8 要是哪天没吃到它，
        // 兜底就在 release 上静默失效（native 方法注册不上）—— 只有 dex 字符串池能证伪这件事。
        val dex = dexBytes(apk)
        assertTrue(
            "release 的 dex 里找不到 zxing-cpp 的类（AAR 那条 consumer keep 规则没生效 ⇒ 兜底在 release 上静默失效）",
            dex.contains("zxingcpp/BarcodeReader"),
        )
        // 反向对照：池子确实是 R8 重建过的那一份（空池/读错文件都会让上一条永远绿）
        assertTrue("dex 里连扫码页自己的类都没有，读取口径不对：", dex.contains("SpocScanScreen"))
    }

    /** 把包里所有 classes*.dex 当成字节视图读出来（口径同 `ReleaseForensicLogSurvivalTest`） */
    private fun dexBytes(apk: File?): String = ZipFile(requireNotNull(apk)).use { zip ->
        val out = java.io.ByteArrayOutputStream()
        for (entry in zip.entries().toList().sortedBy { it.name }) {
            if (entry.name.startsWith("classes") && entry.name.endsWith(".dex")) {
                zip.getInputStream(entry).use { it.copyTo(out) }
            }
        }
        // ISO_8859_1 是字节 → 字符的 1:1 映射，拿它当"字节视图"就能直接查 UTF-8 字节
        String(out.toByteArray(), Charsets.ISO_8859_1)
    }

    /** release 产物：signed 与 unsigned 都认（口径同 `ReleaseForensicLogSurvivalTest`） */
    private fun releaseApk(): File? {
        val dir = File(findRepoRoot(), "app/build/outputs/apk/release")
        if (!dir.isDirectory) return null
        return dir.listFiles { f: File -> f.isFile && f.name.endsWith(".apk") }?.sortedBy { it.name }?.firstOrNull()
    }

    // ---- 源码核对工具（与 ScanCameraAidWiringGuardTest 同一套） ----

    private class Source(val relative: String, val text: String)

    private fun signinSources(): List<Source> =
        mainSources().filter { it.relative.contains("/ui/signin/") }

    /** 全仓 main 源文件（带相对路径），③a/④c 那两条要按文件报命中 */
    private fun mainSources(): List<Source> {
        val javaDir = findMainJavaDir()
        val files = javaDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("${javaDir.path} 下一个 .kt 都没有，路径不对", files.isNotEmpty())
        return files.map {
            Source("$MAIN_PREFIX/${it.relativeTo(javaDir).path.replace('\\', '/')}", it.readText())
        }
    }

    private fun readMainSource(relativeFromJava: String): String {
        val file = File(findMainJavaDir(), relativeFromJava)
        assertTrue("找不到 ${file.path}：文件挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText()
    }

    private fun readRepoFile(relativeFromRoot: String): String {
        val file = File(findRepoRoot(), relativeFromRoot)
        assertTrue("找不到 ${file.path}：这条\"漂移就红\"的守卫依赖它", file.isFile)
        return file.readText()
    }

    /** `git show <rev>:<path>`；取不到（没 git/没这个对象）返回 null，由调用方判失败 */
    private fun gitShow(rev: String, path: String): String? = try {
        val process = ProcessBuilder("git", "show", "$rev:$path")
            .directory(findRepoRoot())
            .redirectErrorStream(true)
            .start()
        val out = process.inputStream.readBytes() // 读干净再等：管道灌满会把子进程卡死在 waitFor 之前
        if (process.waitFor() != 0) null else String(out, Charsets.UTF_8)
    } catch (io: java.io.IOException) {
        null
    }

    private fun normalizeNewlines(text: String): String = text.replace("\r\n", "\n")

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

    /** 从 [openAt] 那个左括号起配平到对应右括号（含两端）；不配平就抛 */
    private fun balancedParens(source: String, openAt: Int): String {
        check(openAt in 0 until source.length && source[openAt] == '(') { "锚点 $openAt 不是左括号" }
        var depth = 0
        for (index in openAt until source.length) {
            when (source[index]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return source.substring(openAt, index + 1)
                }
            }
        }
        throw IllegalStateException("第 $openAt 个左括号没配平")
    }

    /** 只砍行注释（块注释不管：调用方给的必须是不会出现块注释的一小段） */
    private fun lineCommentsOut(source: String): String = source.lines().joinToString("\n") { line ->
        val slash = line.indexOf("//")
        if (slash >= 0) line.substring(0, slash) else line
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

    /** 注释与字符串字面量都抹成空白，长度与换行位置不变：整份文件的红线扫描用这份 */
    private fun blankCommentsAndLiterals(src: String): String {
        val out = src.toCharArray()
        var i = 0
        while (i < out.size) {
            when {
                src.startsWith("//", i) -> {
                    val nl = src.indexOf('\n', i).let { if (it < 0) out.size else it }
                    for (k in i until nl) out[k] = ' '
                    i = nl
                }

                src.startsWith("/*", i) -> {
                    var depth = 1
                    var j = i + 2
                    while (j < out.size && depth > 0) {
                        when {
                            src.startsWith("/*", j) -> { depth++; j += 2 }
                            src.startsWith("*/", j) -> { depth--; j += 2 }
                            else -> j++
                        }
                    }
                    for (k in i until j.coerceAtMost(out.size)) if (out[k] != '\n') out[k] = ' '
                    i = j
                }

                src.startsWith("\"\"\"", i) -> {
                    val end = src.indexOf("\"\"\"", i + 3).let { if (it < 0) out.size else it + 3 }
                    for (k in (i + 3) until (end - 3).coerceAtLeast(i + 3)) if (out[k] != '\n') out[k] = ' '
                    i = end
                }

                out[i] == '"' || out[i] == '\'' -> {
                    val quote = out[i]
                    var j = i + 1
                    while (j < out.size) {
                        when {
                            src[j] == '\\' -> j += 2
                            src[j] == quote -> { j++; break }
                            src[j] == '\n' -> break
                            else -> j++
                        }
                    }
                    for (k in (i + 1) until (j - 1).coerceAtLeast(i + 1)) out[k] = ' '
                    i = j
                }

                else -> i++
            }
        }
        return String(out)
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

    /** 工作目录是模块目录还是仓库根不由这里决定：两种布局都试，全落空就抛 */
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

    /** 同上，认 settings.gradle.kts + app/build.gradle.kts 这一对；单测的 cwd 是 :app 模块目录 */
    private fun findRepoRoot(): File {
        var dir: File? = File("").absoluteFile
        repeat(5) {
            val candidate = dir
            if (candidate != null && File(candidate, "settings.gradle.kts").isFile && File(candidate, BUILD_SCRIPT).isFile) {
                return candidate
            }
            dir = dir?.parentFile
        }
        throw IllegalStateException("找不到仓库根：当前目录 ${File("").absolutePath}")
    }

    private companion object {
        const val MAIN_PREFIX = "app/src/main/java"
        const val SCAN_SCREEN_FILE = "com/buaa/schedule/ui/signin/SpocScanScreen.kt"
        const val KERNEL_FILE = "com/buaa/schedule/ui/signin/ScanSecondEnginePolicy.kt"
        const val SHELL_FILE = "com/buaa/schedule/ui/signin/ZxingCppFallbackDecoder.kt"
        const val FRAME_LEDGER_FILE = "com/buaa/schedule/ui/signin/ScanCameraAidPolicy.kt"
        const val STATUS_FILE = "com/buaa/schedule/ui/signin/ScanUiStatus.kt"
        const val PROBE_FILE = "com/buaa/schedule/ui/signin/BarhopperNativeLibProbe.kt"
        const val WARM_UP_FILE = "com/buaa/schedule/ui/signin/ScanChainWarmUp.kt"
        const val RECOVERY_FILE = "com/buaa/schedule/ui/signin/ScanRecoveryPolicy.kt"
        const val GATE_FILE = "com/buaa/schedule/ui/signin/ScanSubmissionGate.kt"
        const val BUILD_SCRIPT = "app/build.gradle.kts"
        const val CATALOG_FILE = "gradle/libs.versions.toml"

        /** 与 [BarhopperNativeLibProbeTest] ⑤ 同一颗库名（T66 的第二颗） */
        const val ZXINGCPP_SO_FILE = "libzxingcpp_android.so"

        /** 本分支的基点（master）：T66 不许改动上面那五份文件的比较基线 */
        const val T66_BASELINE = "003bbdc"
    }
}
