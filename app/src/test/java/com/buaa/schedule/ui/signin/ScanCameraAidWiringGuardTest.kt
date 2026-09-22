package com.buaa.schedule.ui.signin

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * T64「帧质量」接线的源码形状守卫。
 *
 * 判据本体在 [ScanCameraAidPolicy] 里有表驱动单测，这里只管三件 JVM 跑不到的事：
 * ① 内核保持零 import、不吃设备事实（否则表驱动就塌回真机）；
 * ② 页面**真的**接了它 —— 分辨率请求挂在 builder 上、交付尺寸每绑定一行且措辞出自内核
 *    （手拼字符串数学就是判据的第二份）、测光点用**分析流**构造的 factory、手电调用点排在
 *    hasFlashUnit 判据之后、缩放先过钳制；
 * ③ 反向钉：`ScanUiStatus.kt` 的放弃阶梯文案**没被本卡动过**（那是下一卡的活，先动它就是抢跑）。
 *
 * 手法沿用 [ScanSilentBranchGuardTest] / [SpecialDayBadgeWiringGuardTest]：读源码文本、
 * 匹配前先抹注释、找不到锚点就抛（静默跳过等于没有守卫）、每档断言都要有命中数 > 0 的靶子。
 */
class ScanCameraAidWiringGuardTest {

    /** ①a 内核纯度：零 import、零设备符号。判据一碰 android，表驱动就只能在真机上重做。 */
    @Test
    fun cameraAidKernelStaysPureJvm() {
        val raw = readMainSource(KERNEL_FILE)
        val code = withoutComments(raw)
        val imports = code.lines().filter { it.trim().startsWith("import ") }
        assertTrue("$KERNEL_FILE 里出现了 import，这段判据就到不了 JVM：\n$imports", imports.isEmpty())
        for (
            banned in listOf(
                "android.", "androidx.", "Build.", "CameraInfo", "DisplayMetrics", "ImageAnalysis",
                "PreviewView", "PackageManager", "SystemClock", "System.currentTimeMillis",
            )
        ) {
            assertFalse("判据本体自己去碰了设备（$banned）—— 设备事实必须是参数", code.contains(banned))
        }
        assertTrue("内核文件是空的？", code.length > 2_000)
        for (entry in listOf(
            "minUsefulAnalysisShortEdgePx", "analysisFrameVerdict", "analysisFrameLogText",
            "analysisMeteringPointForTap", "clampedZoomRatio",
            "frameCodeRung", "advanceScanAssist", "zoomLadderRatio",
        )) {
            assertTrue("内核少了 $entry 这一档：", code.contains("fun $entry"))
        }
        // T65④：手电档位整条撤走（用户决策「场景不用」），内核里不许留壳也不许等它回来
        for (gone in listOf("torchAffordance", "torchTargetState", "TorchAffordance", "TorchState")) {
            assertFalse("手电的判据壳残留在内核里（$gone）：", code.contains(gone))
        }
    }

    /** ①b 下限与候选框阈值必须能从推导链复算：常数一个字都不许漂成魔数 */
    @Test
    fun frameFloorStaysDerivedFromTheTwoPixelReasoning() {
        val code = withoutComments(readMainSource(KERNEL_FILE))
        assertTrue("2px/模块 的出处（ML Kit 文档下限）没了：", code.contains("MinModuleSizePx = 2"))
        assertTrue("QR 边长预算档漂了（v20=97 模块）：", code.contains("QrModuleSideBudget = 97"))
        assertTrue("远距占洞系数漂了：", code.contains("DistantCodeHoleFill = 0.5f"))
        assertTrue("请求目标不是 720p 了：", code.contains("RequestAnalysisHeightPx = 720"))
        assertTrue("下限不再由推导算出：", code.contains("MinModuleSizePx * QrModuleSideBudget"))
        // T65①：档位阈值 = 实测可用档 3 px/模块 × 同一枚 v20 预算，两处都不许漂
        assertTrue("实测可用档漂了（同行口径 ≥3px/模块）：", code.contains("UsefulModulePx = 3"))
        assertTrue("候选框阈值不再由推导算出：", code.contains("UsefulModulePx * QrModuleSideBudget"))
        // T65②：阶梯常数住在内核（设备区间由调用点钳制），帧数口径全在表驱动单测里钉
        for (cadence in listOf("ZoomStepFrames", "ZoomRollbackFrames", "RungSettleFrames")) {
            assertTrue("阶梯的帧数口径 $cadence 不在内核里：", code.contains(cadence))
        }
    }

    /** ②a 分辨率请求真的挂在 builder 上，而 KEEP_ONLY_LATEST / setTargetRotation 一颗没掉 */
    @Test
    fun analysisBuilderActuallyAsksForTheBiggerFrame() {
        val code = withoutComments(readMainSource(SCAN_SCREEN_FILE))
        // 终点取 `.apply { setAnalyzer(` 而不是第一个 `.build()` —— 那颗先属于内层的
        // ResolutionSelector.Builder()，拿它截段会把后两档切在窗外。
        val builder = balancedFrom(code, "val analysis = ImageAnalysis.Builder()", ".apply { setAnalyzer(")
        assertTrue("builder 上没挂 setResolutionSelector（回到默认 640×480 了）：\n$builder", builder.contains("setResolutionSelector("))
        assertTrue("请求的不是内核钉的那颗目标：\n$builder", builder.contains("android.util.Size(RequestAnalysisWidthPx, RequestAnalysisHeightPx)"))
        assertTrue("fallback 规则不是先高后低：\n$builder", builder.contains("FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER"))
        assertTrue("KEEP_ONLY_LATEST 掉了（按帧节奏的命根子）：\n$builder", builder.contains("STRATEGY_KEEP_ONLY_LATEST"))
        assertTrue("setTargetRotation 掉了（停用-重绑路径的旋转口径）：\n$builder", builder.contains("setTargetRotation(targetRotation)"))
    }

    /**
     * ②b 交付尺寸取证行：每绑定一行、复位点与首帧同行、措辞出自内核、排在停用判断之前。
     *
     * 手拼 `if (width < floor)` 就是判据的第二份 —— 守卫认 [analysisFrameLogText] 这一个出口；
     * 排在 Skip 之后则停用窗口里这行变盲区，"到过什么样的帧"就断了账。
     */
    @Test
    fun deliveredSizeLogIsPerBindAndSpeaksThroughTheKernel() {
        val code = withoutComments(readMainSource(SCAN_SCREEN_FILE))
        val analyzer = balancedBlock(code, "private class QrCodeAnalyzer(")
        val analyze = balancedBlock(analyzer, "override fun analyze(")
        val sizeGate = analyze.indexOf("if (!frameSizeLogged)")
        val skip = analyze.indexOf("decoderFrameAction(health, frame)")
        check(sizeGate >= 0 && skip >= 0) { "交付尺寸那一路或停用判断不复存在（回到「请求了=拿到了」的糊账）：\n$analyze" }
        assertTrue("交付尺寸行排到了停用判断后面（停用窗口里读不到交付尺寸）：", sizeGate < skip)
        assertEquals("置位只许一处（每绑定一行的本体）：", 1, occurrences(analyze, "frameSizeLogged = true"))
        assertTrue("置位没接 logDeliveredAnalysisSize：\n$analyze", analyze.contains("logDeliveredAnalysisSize()"))
        val mark = balancedBlock(analyzer, "fun markBindStarted(")
        assertTrue("绑定钩子没复位 frameSizeLogged（第二次绑定起这行永远不出，T59b① 同型）：\n$mark", mark.contains("frameSizeLogged = false"))
        val log = balancedBlock(analyzer, "private fun logDeliveredAnalysisSize(")
        assertTrue("取证行没走 Log.i（HyperOS 砍到 Info，Log.d 等于没写）：\n$log", log.contains("Log.i("))
        assertTrue("措辞没出自内核（手拼字符串数学 = 判据的第二份）：\n$log", log.contains("analysisFrameLogText("))
        assertEquals("analysisFrameLogText 的调用点只能一处：", 1, occurrences(code, "analysisFrameLogText("))
        // 按帧入口零日志的既有守卫管不着这一段的保险：尺寸行里没有第二颗 Log.
        assertEquals("尺寸路径按帧直接写日志了：", 0, occurrences(analyze.substring(sizeGate, skip), "Log."))
    }

    /** ②c 点按对焦：映射出自内核、factory 用**分析流**构造、AF-only、会自取消、先问支持再发 */
    @Test
    fun tapToFocusMapsThroughTheKernelOntoTheAnalysisUseCase() {
        val code = withoutComments(readMainSource(SCAN_SCREEN_FILE))
        val gesture = balancedBlock(code, "detectTapGestures { tap ->")
        assertTrue("点击没走内核映射（FILL_CENTER 的逆变换没人做）：\n$gesture", gesture.contains("analysisMeteringPointForTap("))
        assertTrue("映射结果被直接丢弃（拒映射档没接住）：\n$gesture", gesture.contains("mapped == null"))
        val factoryAt = gesture.indexOf("SurfaceOrientedMeteringPointFactory(")
        check(factoryAt >= 0) { "测光 factory 不见了：\n$gesture" }
        val factoryCall = balancedParen(gesture, factoryAt)
        assertTrue(
            "factory 没用**分析流**构造（两参/Preview 那颗按活跃 Preview 画幅换算，正是卡里点名的坑）：\n$factoryCall",
            factoryCall.contains("analysis"),
        )
        assertTrue("AF-only 的 flag 掉了：\n$gesture", gesture.contains("FocusMeteringAction.FLAG_AF"))
        assertTrue("不会自动取消（焦点被一次点击永久举着）：\n$gesture", gesture.contains("setAutoCancelDuration("))
        val support = gesture.indexOf("isFocusMeteringSupported(")
        val start = gesture.indexOf("startFocusAndMetering(")
        check(support >= 0 && start >= 0) { "支持性守卫或 startFocusAndMetering 调用没了：\n$gesture" }
        assertTrue("先问支持再发（不支持 AF 的设备上必须不抛）：", support < start)
        // T65③：受理行的排印位置就是它的语义 —— 支持性守卫之后（不支持的那按没受理，
        // 不许谎报受理）、下发之前（先记账再发，future 失败另有监听兜着）
        val accepted = gesture.indexOf("点按对焦已受理")
        check(accepted >= 0) { "受理路径的留痕被删了（这一页修过三轮的病：静默 no-op 复活）：\n$gesture" }
        val acceptedLine = gesture.lineSequence().first { it.contains("点按对焦已受理") }
        assertTrue("受理行不许是 Log.d（HyperOS 砍到 Info，等于没写）：$acceptedLine", acceptedLine.contains("Log.i("))
        assertTrue("受理行没带视口点（tap.x，读不出按在哪）：$acceptedLine", acceptedLine.contains("tap.x"))
        assertTrue("受理行没带映射后的测光点（读不出内核拿这次点击干了什么）：$acceptedLine", acceptedLine.contains("mapped.x"))
        assertTrue("受理行排到了守卫/下发之外（说假或漏说）：", accepted in support until start)
        assertTrue("startFocusAndMetering 没包 runCatching（同步抛能穿出去）：\n$gesture", gesture.contains("runCatching { camera.cameraControl.startFocusAndMetering"))
        assertTrue("路径不活时这一按连一句话都不留（静默吞点击是本页修过三轮的病）：\n$gesture", gesture.contains("cameraLive"))
    }

    /**
     * ②d T65①：帧观测的接线 —— 测量真从帧里抠、档位与阶梯真走内核、命令只许经回调出去。
     *
     * 这一档守的是整类「写了内核没人喂」的失效（本页三次「扫码没反应」里两次的形状）：
     * enableAllPotentialBarcodes 不打开，CodeTooSmall/CodeUndecodable 永远测不出来；
     * noteFrameRung 不排在提交分支之前，闸门吞掉的帧就不记账；内核自己一行 Log 都不该写。
     */
    @Test
    fun frameObservationIsFedByEverySuccessFrame() {
        val code = withoutComments(readMainSource(SCAN_SCREEN_FILE))
        // 仪器开关必须开在 scanner 上（bundled 实现认这颗；反向钉见下面 noZoomSuggestionIsPinnedOut）
        val options = balancedFrom(code, "BarcodeScannerOptions.Builder()", ".build(),")
        assertTrue("没开 enableAllPotentialBarcodes：「有码解不开」与「没码」在测量上不可分：\n$options", options.contains("enableAllPotentialBarcodes()"))
        assertTrue("格式收窄丢了（每帧多解一种格式的开销回来了）：\n$options", options.contains("setBarcodeFormats(Barcode.FORMAT_QR_CODE)"))
        val analyzer = balancedBlock(code, "private class QrCodeAnalyzer(")
        val success = balancedBlock(analyzer, ".addOnSuccessListener { codes ->")
        val note = balancedBlock(analyzer, "private fun noteFrameRung(")
        assertTrue("成功帧没喂测量（内核成了没人读的字典）：\n$success", success.contains("noteFrameRung(codes)"))
        assertTrue("测量必须排在提交分支之前（闸门吞帧不记账就永远缺样本）：\n$success", success.indexOf("noteFrameRung(") < success.indexOf("val first ="))
        for (entry in listOf("frameCodeRung(", "advanceScanAssist(", "code.boundingBox", "code.rawValue")) {
            assertTrue("紧凑测量没抠 $entry：\n$note", note.contains(entry))
        }
        assertTrue("档位翻面没推 UI（措辞内核白写）：\n$note", note.contains("onFrameRungChanged("))
        assertTrue("缩放命令没经回调出去（分析器自己摸 CameraControl 就是第二份接线）：\n$note", note.contains("onZoomCommand("))
        assertEquals("按帧路径直接写日志了（换挡的话在执行侧说）：", 0, occurrences(note, "Log."))
        // 账本随绑定复位：重绑定 = 视场回基线，旧 stepIndex 说假话
        val mark = balancedBlock(analyzer, "fun markBindStarted(")
        assertTrue("绑定钩子没复位帧观测账本（旧阶梯账对不上新画面）：\n$mark", mark.contains("assist = ScanAssistState()"))
    }

    /**
     * ②e T65②：缩放改由检测驱动 —— 命令侧先钳后设、全页一处 setZoomRatio，
     * 能力探测在绑定成功处一次；T64 的固定档不许以任何形态回来。
     */
    @Test
    fun zoomIsDetectionDrivenAndStillGoesThroughTheClamp() {
        val code = withoutComments(readMainSource(SCAN_SCREEN_FILE))
        val zoom = balancedBlock(code, "private fun applyScanAssistZoom(")
        assertTrue("命令侧没走内核钳制：\n$zoom", zoom.contains("clampedZoomRatio(targetRatio"))
        assertTrue("min/max 是从 zoomState 当参数读的：\n$zoom", zoom.contains("zoomState?.minZoomRatio") && zoom.contains("zoomState?.maxZoomRatio"))
        val nullBranch = zoom.indexOf("if (ratio == null)")
        val setter = zoom.indexOf("setZoomRatio(")
        check(nullBranch >= 0 && setter >= 0) { "null 档或 setZoomRatio 调用没了（缝拆了）：\n$zoom" }
        assertTrue("null 档没排在设置之前（越界的 IllegalArgumentException 就是这么来的）：", nullBranch < setter)
        assertEquals("setZoomRatio 的调用点全页只许一处：", 1, occurrences(code, "setZoomRatio("))
        assertTrue("句柄死档没留痕（命令静默蒸发是本页修过三轮的病）：\n$zoom", zoom.contains("相机句柄已失效"))
        // 能力探测：绑定成功处一次，null 档的那一句维持原字面量（取证口径不许漂）
        val bind = balancedBlock(code, "if (bound.isSuccess)")
        assertTrue("绑定处没探缩放能力（阶梯会在没缩放控制的设备上白攒命令）：\n$bind", bind.contains("zoomControlAvailable = "))
        assertTrue("探测也走同一颗钳制缝：\n$bind", bind.contains("clampedZoomRatio(zoomLadderRatio(1)"))
        assertEquals(
            "「这台没有缩放控制」那句话全页只许一种说法：", 2,
            occurrences(code, "这台设备没有可用的缩放控制（ZoomState 没报出 min/max 或区间固定），视场保持原样"),
        )
        // T64 的固定档整条撤走：每次绑定不问青红皂白抬 1.5× 的形态不许复活
        assertFalse("固定投影缩放档回来了（T65② 已改检测驱动）：", code.contains("ProjectorZoomRatio"))
        assertFalse("绑定里还有无条件 setZoomRatio 的旧缝：", bind.contains("applyProjectorZoom"))
    }

    /**
     * ③ 反向钉：本卡不许动 ScanUiStatus 的放弃阶梯 —— 那是下一卡的活，先动就是抢跑。
     *
     * 用 git 比较而非字面量抄写：把三句文案抄进测试就是第四份真相，阶梯一漂守卫先死。
     * `git diff --quiet <基线> -- <文件>` 零退出 = 与本卡基线逐字节一致（正证）；
     * 退出 1 = 基线之后有人动过它 —— 那是属主卡（T63）合并了，反向钉的使命完成，让位跳过。
     * git 不可用时判失败而不是静默通过：这条守卫的全部价值就在于「动没动」可核。
     */
    @Test
    fun giveUpLadderTextWasNotTouchedByThisCard() {
        val raw = readMainSource(STATUS_FILE)
        // 靶子先立起来：这些字面量在基准点上就该存在（零命中=守卫没在扫真文件）
        for (lit in listOf("这台设备用不了相机扫码", "正在自动重试", "没带这台设备那一档的扫码解码库")) {
            assertTrue("ScanUiStatus 里找不到阶梯文案「$lit」—— 它已被改写，本守卫按 T63 合并后的新基线重钉", raw.contains(lit))
        }
        val untouched = gitLadderUntouchedAgainstBaseline()
        assumeTrue(
            "ScanUiStatus.kt 相对本卡基线 $T64_BASELINE 已有他人改动（多半是 T63 合了），反向钉让位",
            untouched != false,
        )
        assertTrue("git 跑不动，反向钉无从核对（本守卫要求 git 在场）", untouched == true)
    }

    // ---- 源码核对工具（与 ScanSilentBranchGuardTest 同一套，找不着锚点就抛） ----

    /** true = 与基线逐字节一致；false = 动过；null = git 跑不了（不存在/不在 PATH） */
    private fun gitLadderUntouchedAgainstBaseline(): Boolean? {
        val root = findRepoRoot() ?: return null
        val exit = try {
            val process = ProcessBuilder(
                "git", "diff", "--quiet", T64_BASELINE, "--", STATUS_FILE,
            ).directory(root).redirectErrorStream(true).start()
            process.inputStream.readBytes() // 读干净再等：管道灌满会把子进程卡死在 waitFor 之前
            process.waitFor()
        } catch (io: java.io.IOException) {
            return null
        }
        return when (exit) {
            0 -> true
            1 -> false
            else -> null
        }
    }

    private fun findRepoRoot(): File? {
        var dir: File? = File("").absoluteFile
        repeat(6) {
            val candidate = dir ?: return null
            if (File(candidate, ".git").exists()) return candidate
            dir = candidate.parentFile
        }
        return null
    }

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

    /** 从 [fromIndex] 处那个 `(` 起按圆括号配平截一段（factory 构造点就长这样） */
    private fun balancedParen(source: String, fromIndex: Int): String {
        val open = source.indexOf('(', fromIndex)
        check(open >= 0) { "$fromIndex 之后找不到左圆括号" }
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return source.substring(fromIndex, index + 1)
                }
            }
        }
        throw IllegalStateException("圆括号没配平")
    }

    /** 从 [startSignature] 起，到 [endSignature]（含）为止的一段 —— builder 链没有括号可配平就用它 */
    private fun balancedFrom(source: String, startSignature: String, endSignature: String): String {
        val at = source.indexOf(startSignature)
        check(at >= 0) { "找不到 $startSignature：写法换过了" }
        val end = source.indexOf(endSignature, at)
        check(end >= 0) { "$startSignature 之后找不到 $endSignature" }
        return source.substring(at, end + endSignature.length)
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
        const val KERNEL_FILE = "com/buaa/schedule/ui/signin/ScanCameraAidPolicy.kt"
        const val STATUS_FILE = "com/buaa/schedule/ui/signin/ScanUiStatus.kt"

        /** 本卡分支的 master 基线（反向钉的比较对象；T63 合并后守卫自行 assumeTrue 让位） */
        const val T64_BASELINE = "03d6546"
    }
}
