package com.buaa.schedule.ui.signin

import java.io.File
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
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

    // ---- ④b′ 那六条排除挂在**谁**身上（台账 #339：release 专属裁剪的"只在 release"那把尺）----

    /**
     * ④b 比的是那六个文件名字符串；这一条比的是"这段排除长在谁的接收者上"。
     *
     * 少了这一条会留下什么：把 `excludes.addAll` 那一格挂到 `selector().all()` 或第二枚
     * `onVariants` 上、或者把 `variant.` 这个显式接收者省掉搬到别处 —— **六条文件名字面一字不动**，
     * 于是 ④b 与 `BarhopperNativeLibProbeTest` ⑤ 全绿、编译全绿、门禁三步全绿，而 debug 包从此
     * 只剩 arm64：x86_64 模拟器的扫码页永远只能走降级分支，本仓全部装机验收都在那台 AVD 上跑
     * ⇒ 验收链自己先瞎掉。这一族已经付过一次钱（裁剪一度对 debug 也生效），
     * `app/build.gradle.kts` 那句"只能写在这里"的散文说的也是同一件事 —— 散文不是尺，这一条才是。
     *
     * 钉法（四家点名）：**读形状**（花括号/圆括号配平取 `androidComponents` → `onVariants` → 闭包体
     * 三层区间）+ **数出现次数**（`onVariants` 恰一枚、两枚成员各恰一处）+ **抹注释找锚点**
     * （脚本注释里就写着 `androidComponents`、`buildTypes.release`、"lib/x86 加目录通配"这些反例，
     * 不抹注释就会把散文数进去）；字符串字面量**必须留着**，因为锚点 `withBuildType("release")`
     * 的内容就在字面量里，用 [blankCommentsAndLiterals] 那一把会把它抹平。
     */
    @Test
    fun jniLibsTrimHangsOnTheReleaseVariantReceiver() {
        val code = commentsOutKeepingLiterals(readRepoFile(BUILD_SCRIPT))
        val components = topLevelBlock(code, "androidComponents")
        val inComponents = code.substring(components.first, components.last + 1)
        val variantCalls = indexOfAll(inComponents, "onVariants(")
        assertEquals(
            "androidComponents 里只许一枚 onVariants（多一枚就有第二个变体也挂上了裁剪，而 ④b 那六个文件名" +
                "看不见这件事）：实到 ${variantCalls.size} 枚",
            1,
            variantCalls.size,
        )
        val callAt = components.first + variantCalls[0]
        val selectorOpen = code.indexOf('(', callAt)
        val selectorClose = matchingParen(code, selectorOpen)
        val selector = code.substring(selectorOpen, selectorClose + 1).replace(" ", "")
        assertTrue(
            "选择器不再是 selector().withBuildType(\"release\")，实到 $selector：换成 all() / withBuildType(\"debug\") " +
                "就等于对 debug 包一起裁，模拟器上的扫码页永远走降级分支（那正是这一格付过的钱）",
            selector.contains("selector().withBuildType(\"release\")"),
        )
        assertFalse("选择器里出现了 all()（对所有变体生效＝debug 也裁）：$selector", Regex("""\ball\(\)""").containsMatchIn(selector))

        val bodyOpen = code.indexOf('{', selectorClose)
        val bodyClose = matchingBrace(code, bodyOpen)
        val header = code.substring(selectorClose + 1, minOf(bodyOpen + 40, code.length))
        val param = Regex("""\{\s*([A-Za-z_][A-Za-z0-9_]*)\s*->""").find(header)?.groupValues?.get(1)
        checkNotNull(param) { "onVariants 之后不是 { 参数 -> 那一形，接收者链没起点可认：$header" }

        for (needle in listOf(EXCLUDES_NEEDLE, LEGACY_PACKAGING_NEEDLE)) {
            val hits = indexOfAll(code, needle)
            assertEquals(
                "$BUILD_SCRIPT 里 $needle 应恰好一处（多出来的那一处挂在谁的接收者上没人核对过），实到 ${hits.size} 处",
                1,
                hits.size,
            )
            val at = hits[0]
            assertTrue(
                "$needle 不在 release variant 的闭包里（它在 androidComponents 块${if (at in components) "内" else "外"}、" +
                    "在 onVariants 闭包${if (at in (bodyOpen + 1)..bodyClose) "内" else "外"}）：" +
                    "写进顶层 android.packaging、或写进 buildTypes.release 的花括号里沿隐式接收者链静默绑到外层，" +
                    "两种都编译得过、行为都等于顶层 ⇒ debug 只剩 arm64",
                at in (bodyOpen + 1)..bodyClose,
            )
            val lineStart = code.lastIndexOf('\n', at) + 1
            val receiver = code.substring(lineStart, at).trim()
            assertEquals(
                "$needle 的接收者链应当是闭包参数 \"$param.\"（由 release 那一枚 onVariants 给出），实到 \"$receiver\"：" +
                    "把显式接收者省掉，AGP 会沿外层接收者链绑到 android.packaging —— 编译不报错、门禁不红，" +
                    "只有 debug 包的 ABI 少三档",
                "$param.",
                receiver,
            )
        }

        // 反向钉一：顶层 android.packaging {} 里不许出现 jniLibs（裁剪只能挂在 release variant 上）
        val android = topLevelBlock(code, "android")
        val androidBody = code.substring(android.first, android.last + 1)
        val packagingBlocks = indexOfAll(androidBody, "packaging {")
        assertTrue("顶层 android 块里找不到 packaging { 那一格：形状改了要回来重钉（扫不到等于这条没跑）", packagingBlocks.isNotEmpty())
        for (hit in packagingBlocks) {
            val open = android.first + androidBody.indexOf('{', hit)
            val close = matchingBrace(code, open)
            assertFalse(
                "顶层 android.packaging 里出现了 jniLibs —— 那是对**所有**变体裁剪，" +
                    "debug 包只剩 arm64，x86_64 模拟器扫码永远降级：\n" + code.substring(open, close + 1),
                code.substring(open, close + 1).contains("jniLibs"),
            )
        }
        // 反向钉二：buildTypes 块里不许出现 packaging 成员（"只能写在这里"那句散文的构造性版本）
        val buildTypes = topLevelBlock(code, "buildTypes")
        val buildTypesBody = code.substring(buildTypes.first, buildTypes.last + 1)
        assertFalse(
            "buildTypes 块里出现了 packaging：AGP 的 BuildType 没有这个成员，写进去会沿隐式接收者链" +
                "静默绑到外层 android.packaging（编译不报错、行为等于顶层），" +
                "表现就是改完 debug 包照样只剩 arm64",
            buildTypesBody.contains("packaging"),
        )
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
     *
     * ⚠️ [STATUS_FILE] 的形状在 T90（#134）之后换了钉法（与
     * [ScanBlankDecodingWiringGuardTest] ⑤b 同一口径，理由也写在那里）：整文件比较换成
     * **"挖掉帧观测措辞那一段之后逐字节"**。本条要抓的还是原来那件事 —— 兜底不许顺手改文案链；
     * 而 T90 被授权改的正是那一段的断语，所以那段单独由 `ScanUiStatusTest` ⑨a/⑨b/⑨c 与
     * [ScanFrameAidWordingWiringGuardTest] 钉住（这里再留一枚靶子，防止那段整段消失后
     * "挖掉"把整个文件挖空）。其余四份照旧整文件逐字节。
     */
    @Test
    fun filesThisCardMustNotTouchAreByteIdenticalToBaseline() {
        for (relative in listOf(PROBE_FILE, WARM_UP_FILE, RECOVERY_FILE, GATE_FILE)) {
            val path = "$MAIN_PREFIX/$relative"
            // T245② 被授权改过 ScanSubmissionGate.kt 的**措辞**（DecodeFailurePayload 的语义 +
            // awaitingUserAction 有两个写者那两句），所以这一枚的反向钉基点前移到 T245②；
            // 其余三枚仍按 003bbdc 逐字节核对。守卫一枚没删、也没放宽：闸门文件从今往后照样
            // "不许顺手改"，要改必须像这样把基点与授权一起钉走。
            val rev = if (relative == GATE_FILE) T245_GATE_BASELINE else T66_BASELINE
            val baseline = baselineBlob(rev, path)
            check(baseline != null) { "git 跑不动或基线取不到（$path@$rev），反向钉无从核对" }
            assertEquals(
                "$relative 相对基点被改过了：本卡只该加第二引擎，不该动文案/探针/预热那三条链",
                normalizeNewlines(baseline),
                normalizeNewlines(File(findMainJavaDir(), relative).readText()),
            )
        }
        // 靶子：这一枚的基点前移是有实物的（新旧两份闸门文件逐字节不同的话才允许不同源），
        // 否则"换个 sha"就等于把反向钉空转掉。
        // 在册 #733 顺带补的**纵深**（盘面上不是活雷）：这一格原本写成"两枚基线做不等比较"，两头任何一枚
        // 取不到都会让 `null != 文本` 变成 true ⇒ 基线没了反而绿。盘面复核：本方法开头那段循环已经对
        // PROBE／WARM_UP／RECOVERY（003bbdc 那一头）与 GATE（不可达那一头的同名文件）各 check 过一轮，
        // 而且跑在这一格之前 ⇒ "基线取不到"在抵达这一格时就已经红了。所以这一枚补的是纵深、不是修活雷：
        // 改完之后这一格自己要求两头都非 null **且**逐字节不同，不再靠上游那圈循环替它兜。
        val gateAtT66 = baselineBlob(T66_BASELINE, "$MAIN_PREFIX/$GATE_FILE")
        val gateAtT245 = baselineBlob(T245_GATE_BASELINE, "$MAIN_PREFIX/$GATE_FILE")
        check(gateAtT66 != null && gateAtT245 != null) {
            "靶子的两头取不到（$MAIN_PREFIX/$GATE_FILE@$T66_BASELINE 或 @$T245_GATE_BASELINE）：不许拿 null≠文本 当绿"
        }
        assertTrue(
            "ScanSubmissionGate.kt 在 003bbdc 与 T245 基点上逐字节相同 ⇒ 基点前移是白给的，这一枚反向钉已经空转",
            normalizeNewlines(gateAtT66) != normalizeNewlines(gateAtT245),
        )
        val statusPath = "$MAIN_PREFIX/$STATUS_FILE"
        val statusBaseline = baselineBlob(T66_BASELINE, statusPath)
        check(statusBaseline != null) { "git 跑不动或基线取不到（$statusPath@$T66_BASELINE），反向钉无从核对" }
        val current = normalizeNewlines(File(findMainJavaDir(), STATUS_FILE).readText())
        val currentOutside = withoutFrameAidSection(current, STATUS_FILE)
        val baselineOutside = withoutFrameAidSection(normalizeNewlines(statusBaseline), "$statusPath@$T66_BASELINE")
        assertEquals(
            "$STATUS_FILE 在帧观测措辞那一段之外被改过了：兜底那一卡不许动降级文案链，" +
                "T90 之后唯一被授权动的就是「画面里有码但没解开」两档的断语",
            baselineOutside,
            currentOutside,
        )
        // 靶子：被挖掉的那一段确实还在（挖空整份文件就让这条守卫绿着骗过去）
        assertTrue("帧观测措辞那颗函数没了（提示条的出口被拆走）：", current.contains("internal fun scanFrameAidText("))
        assertTrue("帧观测措辞不再按「本轮读出过原文没有」分支：", current.contains("readableCodeSeen"))
    }

    /**
     * 挖掉 T90 被授权改动的那一段（帧观测措辞的 KDoc 起、到文件末尾）。
     * 锚点找不到就抛：宁可红，不许退化成"没比也算过"。
     */
    private fun withoutFrameAidSection(text: String, label: String): String {
        val at = text.indexOf(FRAME_AID_ANCHOR)
        check(at >= 0) { "$label 里找不到帧观测措辞那一段的锚点「$FRAME_AID_ANCHOR」：这段挪过家了，钉法要跟着重看" }
        check(text.length - at in 500..9_000) { "$label 里那一段的长度是 ${text.length - at}，不像是一段措辞（钉法本身漂了）" }
        return text.substring(0, at)
    }

    /**
     * ④f 产物层：release 包里两颗解码库都只留 arm64，而兜底那颗确实被压缩入库了。
     *
     * 脚本层的 exclude（④b）只证明"我们许了个愿"，产物层才证明 AGP 真按文件名裁了 ——
     * 这一条的存在理由与 `BarhopperNativeLibProbeTest` ⑤ 相反：那条防的是清单漂走，
     * 这条防的是"清单对了但没生效"（写进 `android{}` 顶层、或版本目录被别的变体覆盖那一类）。
     *
     * ⚠️ 产物从哪来、能不能算本轮：走 [releaseApkForArtifactLayer] —— 日常门禁里没有产物就跳
     *   （单测不该去拉起 release 构建），而 `release.ps1` 的最后一步带着
     *   `-PbuaaRequireReleaseArtifacts` 重跑本类 ⇒ 那时跳不过去：缺产物、产物不是本轮的（自证条的
     *   SHA-256 / runId / 包内版本任一格对不上）都当场红。台账 #340 之前这一步排在产物**之前**，
     *   于是兜底那颗 .so 压没压缩、zxingcpp/BarcodeReader 在不在 dex 这两格，发版路径上从没被读过。
     */
    @Test
    fun releaseApkShipsEachDecoderLibraryForArm64Only() {
        val apk = releaseApkForArtifactLayer(
            "脚本层那三条（④a、④b，以及钉接收者链的那一枚）才是这条链的下限",
        )
        val entries = ZipFile(apk).use { zip ->
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
    /**
     * 在册 #733（门禁可移植性 · 乙档）的基线取数口：**两套并存，理由**。
     * 下面 [GOLDEN_BASELINE_LEDGER] 那五行里的 (基点 sha, 文件) 只活在五枚未合并分支上
     * （`ai/T245`／`ai/T257`／`ai/T395`／`ai/T404b`／`ai/T404C`），不在 master 的祖先链上 ⇒ 本仓每轮
     * 清理 worktree 与 `ai/` 打头那批分支之后，`git show` 会在一枚什么都没改的树上取不到对象，反向钉硬红却只报
     * "git 跑不动或基线取不到"。这一类改成从 classpath 资源 `baseline/<sha7>__<basename>.txt` 读
     * （blob 由 `git show <rev>:<path>` 直接落盘、逐字节、不过编辑器）。
     * 仍可达的基点（003bbdc 那一族）押在 HEAD 祖先链上，清分支也取得到 ⇒ **照旧走 [gitShow]**，
     * 口径一字不改（可达性由 `ScanBlankDecodingWiringGuardTest` 的绊线格常驻钉住）。
     * 取不到不许静默：golden 这一头当场 `check`（非 null 且非空），git 这一头沿用调用点原有的 check。
     */
    private fun baselineBlob(rev: String, path: String): String? {
        val row = GOLDEN_BASELINE_LEDGER.firstOrNull { it.startsWith("$rev $path ") }
            ?: return gitShow(rev, path)
        val sha = row.substringBefore(' ')
        val resource = "baseline/$sha" + "__" + path.substringAfterLast('/').removeSuffix(".kt") + ".txt"
        val bytes = javaClass.getResourceAsStream("/$resource")?.use { it.readBytes() }
        check(bytes != null && bytes.isNotEmpty()) {
            "golden 基线取不到或为空：app/src/test/resources/$resource（在册 #733：$path@$sha 已经取成 golden，" +
                "资源不在＝红，不许静默跳过）"
        }
        // blob 是 LF（git show 不走 smudge），Windows 工作树 checkout 出来的 .txt 可能被补成 CRLF；
        // 抹回 blob 的形状，使 golden 这一头与 [gitShow] 那一头同一把尺。
        return String(bytes, Charsets.UTF_8).replace("\r\n", "\n")
    }

    /**
     * 小册子（在册 #733 乙档）：五行，一行一枚已迁成 golden 资源的基线，
     * `"<sha7> <仓库内路径> <授权卡号/事由>"`。取数口只认这张表 ⇒ 将来前移基点必须有格子可改
     * （这里加/改一行 + 按命名规则落一枚 blob + 当场双向 md5 对账）；枚数与内容钉在
     * `ScanBlankDecodingWiringGuardTest` 的册子格那一枚判据上。
     */
    private val GOLDEN_BASELINE_LEDGER: List<String> = listOf(
        "ddf493f app/src/main/java/com/buaa/schedule/ui/signin/ScanSubmissionGate.kt T245② 被授权改闸门措辞（占位的语义 + awaitingUserAction 有两个写者那两句）——三枚反向钉共同的基点",
        "869fc13 app/src/main/java/com/buaa/schedule/ui/signin/SignInViewModel.kt T395① 被授权改闸门那一次原子换（领取与落回各一枚 compareAndSet）",
        "00b0b51 app/src/main/java/com/buaa/schedule/ui/signin/SignInViewModel.kt T257③ 被授权改漏斗（第四处 if (flight.value) return 换成 ScanFlightAdmission 分档）",
        "bd0eca7 app/src/main/java/com/buaa/schedule/ui/signin/SignInViewModel.kt T404① 被授权改 fail 那句用户文案的出处（按档取句子 + 一行取证）",
        "bd0eca7 app/src/main/java/com/buaa/schedule/data/import/ScanReject.kt T404① 被授权改 USER_ID_PARAM 那一行的存在性判据（queryValue 非空 → hasQueryParam）",
    )

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

    // ---- 构建脚本文本形状工具（#339 的接收者链用）----

    /**
     * 抹注释、**字符串字面量原样保留**（与 [blankCommentsAndLiterals] 的分工：那一把把字面量也抹平，
     * 而这里的锚点 `withBuildType("release")` 的内容就长在字面量里）。
     * 注释里的 `androidComponents`、`buildTypes.release`、"lib/x86 加目录通配"那几处反例说明必须抹掉，
     * 否则"数出现次数"会把散文数进账里（本仓的尺子栽过这一手）。
     */
    private fun commentsOutKeepingLiterals(src: String): String {
        val out = src.toCharArray()
        var i = 0
        while (i < out.size) {
            when {
                src.startsWith("//", i) -> {
                    val nl = src.indexOf('\n', i).let { if (it < 0) out.size else it }
                    for (k in i until nl) if (out[k] != '\n') out[k] = ' '
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
                    i = j
                }

                else -> i++
            }
        }
        return String(out)
    }

    /** 顶层 `name { ... }` 的花括号区间；0 枚或多于 1 枚都抛（多一枚就有第二个挂点，链无从核对） */
    private fun topLevelBlock(code: String, name: String): IntRange {
        val hits = Regex("\\b$name\\s*\\{").findAll(code).map { it.range.first }.toList()
        check(hits.size == 1) {
            "$BUILD_SCRIPT 顶层的 $name 块找到 ${hits.size} 枚，只许 1 枚：拆成两枚（比如再来一个 androidComponents）" +
                "就意味着有第二个地方也能挂 jniLibs 裁剪，而这一条守卫看不见那一枚"
        }
        val open = code.indexOf('{', hits[0])
        return open..matchingBrace(code, open)
    }

    /** 从 [openAt] 的 `{` 配平到对应 `}`；不配平就抛 —— 静默返回 -1 会让区间判断退化成"没比也算过" */
    private fun matchingBrace(code: String, openAt: Int): Int {
        check(code[openAt] == '{') { "锚点 $openAt 不是左花括号" }
        var depth = 0
        for (index in openAt until code.length) {
            when (code[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        throw IllegalStateException("第 $openAt 个花括号没配平：脚本形状改了，接收者链要重看")
    }

    private fun matchingParen(code: String, openAt: Int): Int {
        check(code[openAt] == '(') { "锚点 $openAt 不是左圆括号" }
        var depth = 0
        for (index in openAt until code.length) {
            when (code[index]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        throw IllegalStateException("第 $openAt 个圆括号没配平")
    }

    private fun indexOfAll(haystack: String, needle: String): List<Int> {
        val hits = ArrayList<Int>()
        var from = 0
        while (true) {
            val at = haystack.indexOf(needle, from)
            if (at < 0) return hits
            hits += at
            from = at + 1
        }
    }

    private val EXCLUDES_NEEDLE = "packaging.jniLibs.excludes.addAll"
    private val LEGACY_PACKAGING_NEEDLE = "packaging.jniLibs.useLegacyPackaging"

    // ---- 产物层入口与自证条（#340，与 ReleaseForensicLogSurvivalTest 同一口径）----

    /** 发版路径（release.ps1 最后一步带 -PbuaaRequireReleaseArtifacts）时缺产物 / 不是本轮的都当场红 */
    private val releaseArtifactsRequired: Boolean
        get() = System.getProperty(PROPERTY_REQUIRE_ARTIFACTS) == "true"

    /**
     * 产物层"读不到证据"的唯一出口（本文件的跳过只发生在这里）。与
     * `ReleaseForensicLogSurvivalTest.missingArtifact` 同一口径：日常门禁跳，发版路径红。
     */
    private fun missingArtifact(reason: String, floor: String): Nothing {
        if (releaseArtifactsRequired) {
            fail(
                "$reason\n—— 发版路径上这一格**不许跳过**（$PROPERTY_REQUIRE_ARTIFACTS 已命中）：" +
                    "兜底那颗 .so 是不是 DEFLATED、zxingcpp/BarcodeReader 还在不在 release dex，" +
                    "跳过了就一个字都没读到。$floor"
            )
        }
        assumeTrue("$reason —— $floor", false)
        throw IllegalStateException("unreachable")
    }

    /**
     * 产物层该读的那一枚 release APK，并核 `:app:releasePackage` 写下的自证条。
     *
     * 旧读法 `sortedBy { it.name }.firstOrNull()` 在 signed 与 unsigned 并存时拿的是 **unsigned**
     * 那一枚（`-` 是 0x2D、`.` 是 0x2E），真要发出去的包从没被读过；而且 build/ 里躺着上一轮残留时
     * 它读的还是上一轮。自证条把这两格一起收掉：读它指的那枚文件、复算它的 SHA-256、
     * 对本轮 runId 与包内版本（同一把尺，口径与 `ReleaseForensicLogSurvivalTest` 一致）。
     */
    private fun releaseApkForArtifactLayer(floor: String): File {
        val root = findRepoRoot()
        val provenance = readProvenance(root)
        if (provenance == null) {
            if (releaseArtifactsRequired) {
                missingArtifact(
                    "本轮没有 :app:releasePackage 写的自证条（$PROVENANCE_FILE）：这一趟没打出产物，" +
                        "产物层只能去读 build/ 里剩着的那一枚，而它是不是本轮的没有东西能证明",
                    floor,
                )
            }
            return releaseApk() ?: missingArtifact("没有 :app:assembleRelease 的产物（app/build/outputs/apk/release 下的 .apk）", floor)
        }
        val relative = provenance.getProperty(KEY_APK)?.trim().orEmpty()
        if (relative.isEmpty()) missingArtifact("自证条里没有 $KEY_APK 这一项（$PROVENANCE_FILE）", floor)
        val apk = File(root, relative)
        if (!apk.isFile) missingArtifact("自证条指着 $relative，可它不在盘上（$PROVENANCE_FILE 是旧的？）", floor)
        val problems = ArrayList<String>()
        val recordedSha = provenance.getProperty(KEY_APK_SHA)?.trim().orEmpty()
        val actualSha = sha256Of(apk)
        if (recordedSha.isEmpty()) {
            problems += "自证条里没有 $KEY_APK_SHA"
        } else if (!recordedSha.equals(actualSha, ignoreCase = true)) {
            problems += "APK 字节与自证条不符（侧车 ${recordedSha.take(16)}… / 复算 ${actualSha.take(16)}…）" +
                "⇒ 手里这枚不是 :app:releasePackage 当时交出来的那一枚"
        }
        val expectedRun = System.getProperty(PROPERTY_RUN_ID).orEmpty()
        val recordedRun = provenance.getProperty(KEY_RUN_ID)?.trim().orEmpty()
        if (expectedRun.isNotEmpty() && recordedRun != expectedRun) {
            problems += "APK 出自另一轮（自证条 runId=\"$recordedRun\" / 本轮=\"$expectedRun\"）"
        }
        if (provenance.getProperty(KEY_BUILT_BY)?.trim() != BUILT_BY) {
            problems += "自证条不是 $BUILT_BY 写的（builtBy=${provenance.getProperty(KEY_BUILT_BY)}）"
        }
        if (provenance.getProperty(KEY_AAPT2)?.trim() != "ok") {
            problems += "包内版本这一格根本没读过（aapt2=${provenance.getProperty(KEY_AAPT2)}）"
        }
        for ((key, gradleKey) in listOf(KEY_VERSION_NAME to "VERSION_NAME", KEY_VERSION_CODE to "VERSION_CODE")) {
            val recordedValue = provenance.getProperty(key)?.trim().orEmpty()
            val inTree = gradleProperty(root, gradleKey)
            if (recordedValue.isEmpty() || recordedValue != inTree) {
                problems += "APK 里的 $key=\"$recordedValue\" 与 gradle.properties 的 $gradleKey=\"$inTree\" 对不上"
            }
        }
        if (problems.isNotEmpty()) missingArtifact("本轮产物自证不成立：\n" + problems.joinToString("\n"), floor)
        return apk
    }

    private fun readProvenance(root: File): Properties? {
        val file = File(root, PROVENANCE_FILE)
        if (!file.isFile) return null
        val props = Properties()
        for (line in file.readLines()) {
            val at = line.indexOf('=')
            if (at <= 0 || line.startsWith("#")) continue
            props.setProperty(line.substring(0, at).trim(), line.substring(at + 1).trim())
        }
        return props
    }

    private fun gradleProperty(root: File, key: String): String =
        File(root, "gradle.properties").takeIf { it.isFile }?.readLines()
            ?.firstOrNull { it.startsWith("$key=") }
            ?.substringAfter('=')?.trim().orEmpty()

    private fun sha256Of(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { byte -> "%02x".format(byte) }

    private val PROVENANCE_FILE = "app/build/release-provenance/release-artifact.properties"
    private val PROPERTY_REQUIRE_ARTIFACTS = "buaa.requireReleaseArtifacts"
    private val PROPERTY_RUN_ID = "buaa.releaseRunId"
    private val KEY_APK = "apk"
    private val KEY_APK_SHA = "apkSha256"
    private val KEY_RUN_ID = "runId"
    private val KEY_BUILT_BY = "builtBy"
    private val KEY_AAPT2 = "aapt2"
    private val KEY_VERSION_NAME = "versionName"
    private val KEY_VERSION_CODE = "versionCode"
    private val BUILT_BY = ":app:releasePackage"

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

        /** T245② 授权改过 ScanSubmissionGate.kt 的措辞，这一枚反向钉的基点因此前移到这里 */
        const val T245_GATE_BASELINE = "ddf493f"

        /**
         * T90（#134）被授权改动的那一段（帧观测措辞）的锚点：它的 KDoc 第一行。
         * ④e 靠它把这一段截掉之后再逐字节比 —— 锚点漂了就抛，不退化成"没比也算过"。
         */
        const val FRAME_AID_ANCHOR = "T65① 新增：「画面里有码"
    }
}
