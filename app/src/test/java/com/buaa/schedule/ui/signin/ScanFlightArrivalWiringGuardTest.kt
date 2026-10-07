package com.buaa.schedule.ui.signin

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T257④：「丢掉那一手必须有名有姓」的接线与形状守卫。
 *
 * ## 钉法点名（本仓四种刀法里用的是哪几种）
 *
 * - **抹注释找锚点**（[balancedBlock] + [withoutComments]）：①②③⑤⑦ 取的是函数体，
 *   锚点是符号本身（`fun signIn(...)` / `private fun dispatchNow(` / `private fun submitDecodedText(`），
 *   **不是行号** —— 这一页上一轮就栽在"锚点写成文件名冒号数字"上，改一次行就空转；
 * - **数出现次数**：②④⑥⑦⑧ 钉的是"这一个口子只许有一处"，多一处就是第二份真相；
 * - **读形状**：③⑧ 读的是同一颗函数里的**次序与配对**（置位在 launch 之前、三样在同一颗 finally 里清）；
 * - **逐字节**：本文件**不用** —— [ScanFlightAdmission] 是本卡新写的文件，没有历史基线可钉；
 *   它的基线由本卡 ⑤ 的变异自证负责，"改了判据本体而单测没红"那一档由
 *   [ScanFlightAdmissionTest] 的十六格全表负责。
 *
 * ## 找不着锚点就抛
 *
 * 沿用 [ScanSubmissionGateTest] / [ScanSilentBranchGuardTest] 的纪律：跳过的守卫比没有守卫更糟，
 * 所以每一处取值都是 `check(...) { ... }` 或 [balancedBlock] 自己抛，错误消息一律带**文件与判据**，
 * 不带行号。
 *
 * ## 反套套逻辑
 *
 * 每一条都钉的是"这一句在不在"以外的东西：次序（③）、唯一性（②④⑦）、级别（⑥）、
 * 参数表里有没有默认值（②）、以及"该文件里根本没有那一枚取值口"（⑧）。
 * 没有任何一条是"grep 没匹配到就算绿"。
 */
class ScanFlightArrivalWiringGuardTest {

    /**
     * ① 状态机漏斗：`signIn` 的早退只能是判据给的那几档，不许再有一颗静默 `return`。
     *
     * 本卡的病灶就是 `if (flight.value) return` 那一句 —— 它把一次扫码吃掉而屏上一个字都不说。
     * 钉的不是"这一句没了"，而是"这一手的出路全部出自判据"：Dispatch → 真发，
     * Dropped → 说话，DropAlreadyNoticed → 显式写出来的不念（不是 when 缺支）。
     */
    @Test
    fun theFunnelHandsEveryArrivalToTheKernelInsteadOfReturningSilently() {
        val vm = withoutComments(readMainSource(VIEW_MODEL_FILE))
        val signIn = balancedBlock(vm, "fun signIn(raw: String, cooldownRemainingMillis: Long, isRetryHand: Boolean)")
        check(signIn.contains("scanFlightAdmission(")) {
            "$VIEW_MODEL_FILE 的 signIn 不再问 scanFlightAdmission：投不投又回到调用点自己判了（两份真相）\n$signIn"
        }
        assertEquals("signIn 里还留着静默的 return：$signIn", 0, occurrences(signIn, "return"))
        assertTrue("在飞这一位没递给判据（判据就会自己去读状态机）：$signIn", signIn.contains("dispatching = flight.value"))
        assertTrue("「是不是同一枚」没递给判据：$signIn", signIn.contains("samePayloadAsDispatching ="))
        assertTrue("「这一次在飞念过没有」没递给判据：$signIn", signIn.contains("alreadyNoticedForThisDispatch = dropNoticed"))
        assertTrue("Dispatch 那一支接的不是真发那一发：$signIn", signIn.contains("ScanFlightAdmission.Dispatch -> dispatchNow(raw)"))
        assertTrue("Dropped 那一支接的不是说话那颗：$signIn", signIn.contains("is ScanFlightAdmission.Dropped -> tellDroppedArrival(admission)"))
        assertTrue(
            "「不念」那一档不是显式写出来的（when 少一支就是有人在等编译器替他兜着）：$signIn",
            signIn.contains("ScanFlightAdmission.DropAlreadyNoticed ->"),
        )
    }

    /**
     * ② 三枚参数都不许长回默认值，两颗入口都得当场把话说完（T59④ 那一族：漏传编译器不响）。
     *
     * 数的是**两份**：参数表里不许有 `=` 兜底，调用点两处都得显式写。
     * 相册那一路漏写 `isRetryHand = true` 的后果不是编译错，而是"用户按了那一下、屏幕上什么都不知道"。
     */
    @Test
    fun everyArrivalFactIsPassedAtTheDoorInsteadOfDefaulted() {
        val vm = withoutComments(readMainSource(VIEW_MODEL_FILE))
        val parameter = Regex("""cooldownRemainingMillis: Long(\s*=\s*[^\n,)]*)?""").find(vm)
        check(parameter != null) { "$VIEW_MODEL_FILE 的 signIn 参数表里已经没有 cooldownRemainingMillis：接线前提变了，本守卫要跟着改" }
        assertFalse("冷却剩余又长回默认值（漏传那一手就吃默认档）：${parameter.value.trim()}", parameter.value.contains("="))
        val retry = Regex("""isRetryHand: Boolean(\s*=\s*[^\n,)]*)?""").find(vm)
        check(retry != null) { "$VIEW_MODEL_FILE 的 signIn 参数表里已经没有 isRetryHand：这一手是不是重试没人递给判据了" }
        assertFalse("这一手是不是重试长回默认值（相册那一手会被当成按帧）：${retry.value.trim()}", retry.value.contains("="))

        val screen = withoutComments(readMainSource(SCAN_SCREEN_FILE))
        assertEquals("相机那一路显式写 isRetryHand = false 的落点只能一处：", 1, occurrences(screen, "isRetryHand = false"))
        assertEquals("相册那一路显式写 isRetryHand = true 的落点只能一处：", 1, occurrences(screen, "isRetryHand = true"))
        assertEquals("「没有账」那一档的调用点只能一处（相册）：", 1, occurrences(screen, "NoLedgerCooldownRemainingMillis"))
    }

    /**
     * ③ 三枚对子成对：置位排在派发之前，清场排在同一颗 finally 里。
     *
     * 这正是本仓被"成对字段漏清"这一族打过很多次的那个形状：
     * - 漏 `flight.value = false` 就是按钮永久按灭；
     * - 漏 `dispatchingPayload = null` 就是把上一发的原文当成这一发的比较基准，
     *   于是下一张码被误判成"同一枚"，界面对用户说谎；
     * - 漏 `dropNoticed = false` 就是把下一次在飞的那一句永久按住不念（回到静默那一档）。
     */
    @Test
    fun theThreeCompanionsAreClaimedBeforeLaunchAndReleasedTogetherInOneFinally() {
        val vm = withoutComments(readMainSource(VIEW_MODEL_FILE))
        val dispatch = balancedBlock(vm, "private fun dispatchNow(raw: String)")
        val claim = dispatch.indexOf("flight.value = true")
        val payload = dispatch.indexOf("dispatchingPayload = raw")
        val notice = dispatch.indexOf("dropNoticed = false")
        val launch = dispatch.indexOf("viewModelScope.launch")
        check(claim >= 0 && payload >= 0 && notice >= 0 && launch >= 0) {
            "dispatchNow 的置位那三句换了写法，成对账无从核对：\n$dispatch"
        }
        assertTrue("置位排到了 launch 之后（协程可能先跑起来，比较基准就是空的）：", maxOf(claim, payload, notice) < launch)
        val finallyAt = dispatch.indexOf("finally {")
        check(finallyAt > launch) { "dispatchNow 没有 finally：任何一条抛出去的路径都会把在飞旗留在 true\n$dispatch" }
        val released = dispatch.substring(finallyAt)
        for (companion in listOf("flight.value = false", "dispatchingPayload = null", "dropNoticed = false")) {
            assertEquals("$companion 没在同一颗 finally 里清（或清了两处）：$released", 1, occurrences(released, companion))
        }
    }

    /**
     * ④ 一句话只有一个生产者，而且滚动码的原文走不出那一枚字段。
     *
     * `dispatchingPayload` 的全部用处就是比一次相等：声明 + 置位 + 清场 + 一次比较 = 四处，
     * 第五处就是有人把它写进日志、卡面或 state —— 那等于把"还能再签一次"的凭证递出去。
     */
    @Test
    fun theDroppedHandSpeaksThroughOneKernelAndLeavesNoPayloadBehind() {
        val vm = withoutComments(readMainSource(VIEW_MODEL_FILE))
        assertEquals("卡面那句话的出口不止一处（第二处就是 VM 自己拼的）：", 1, occurrences(vm, "droppedArrivalCardText("))
        assertEquals("取证行的出口不止一处：", 1, occurrences(vm, "droppedArrivalTraceText("))
        assertEquals("dispatchingPayload 的落点应当是「声明 + 置位 + 清场 + 比较」四处：", 4, occurrences(vm, "dispatchingPayload"))
        val tell = balancedBlock(vm, "private fun tellDroppedArrival(")
        for (banned in listOf("dispatchingPayload", "raw", "target.rawUrl")) {
            assertFalse("说话那颗把 $banned 递出去了（原文一个字都不许进日志与卡面）：\n$tell", tell.contains(banned))
        }
        assertFalse(
            "状态里带了原文这一位（Failed 多带一枚 payload 字段就是第二份凭证存放处）：",
            vm.contains("evidence = ") && tell.contains("evidence = "),
        )
    }

    /**
     * ⑤ 丢掉那一手就是丢掉：不许在这里补投、排队、自动签到。
     *
     * 本卡的红线之一（"不许做自动签到"）在源码里的形状就是这一条：
     * 说话那颗既不派协程、也不碰 api、也不置在飞旗。
     */
    @Test
    fun aDroppedHandIsNeverFiredLater() {
        val vm = withoutComments(readMainSource(VIEW_MODEL_FILE))
        val tell = balancedBlock(vm, "private fun tellDroppedArrival(")
        for (banned in listOf("dispatchNow(", "viewModelScope.launch", "iClassApi", "flight.value = true", "ScanTargetParser")) {
            assertFalse("丢掉那一手的支路里出现了 $banned（那就是把「丢」改成了「晚一点再投」）：\n$tell", tell.contains(banned))
        }
        for (queue in listOf("Channel<", "enqueue", "MutableList<String>", "ArrayDeque")) {
            assertFalse("状态机开始排队重投（$queue）—— T59③ 与本卡都判死过：", vm.contains(queue))
        }
        // 「不念」那一档：它是漏斗里的一支，不是一颗函数 —— 从漏斗体内取它之后的那一段，
        // 别用 balancedBlock（那一支没有自己的花括号，配平会一路取到下一颗函数去）。
        val funnel = balancedBlock(vm, "fun signIn(raw: String, cooldownRemainingMillis: Long, isRetryHand: Boolean)")
        val silent = funnel.substringAfter("ScanFlightAdmission.DropAlreadyNoticed ->")
        check(silent != funnel) { "$VIEW_MODEL_FILE 的漏斗里少了「不念」那一档：\n$funnel" }
        for (banned in listOf("Log.", "_state.value", "dispatchNow(", "tellDroppedArrival(")) {
            assertFalse("「不念」那一档写了 $banned（那一档的全部意义就是屏上已经有一句）：\n$silent", silent.contains(banned))
        }
    }

    /**
     * ⑥ 级别与归属：新增的取证行是 Warn，不是 `Log.d`；措辞也不许在界面文件里再抄一份。
     *
     * 用户那台机器（HyperOS）把 logcat 截在 Info 级、release 又剥 Verbose ⇒
     * 这一行要读得到才叫"留了痕迹"，口径同 [ScanSilentBranchGuardTest] ⑦。
     */
    @Test
    fun theNewTraceIsReadableOnTheDeviceAndNotRewrittenByThePage() {
        val vm = withoutComments(readMainSource(VIEW_MODEL_FILE))
        assertEquals("丢掉那一手的取证行只许一处，而且必须是 Warn：", 1, occurrences(vm, "Log.w(TAG, droppedArrivalTraceText("))
        for (banned in listOf("Log.d(", "Log.v(", "Log.e(", "Log.i(TAG, droppedArrival")) {
            assertEquals("状态机里出现了 $banned（Info 以下读不到，Warn 以下的级别漂了就成了假留痕）：", 0, occurrences(vm, banned))
        }
        // 屏幕上那三句的唯一生产者在内核里：界面文件抄一份就是第二份真相
        val screen = withoutComments(readMainSource(SCAN_SCREEN_FILE))
        for (sentence in listOf("这一张码没有投出去", "与正在提交的那一张是同一枚", "你刚递进来的这一次没有投出去")) {
            assertFalse("扫码页自己拼了内核的那一句「$sentence」：", screen.contains(sentence))
        }
        val kernel = withoutComments(readMainSource(ADMISSION_FILE))
        for (sentence in listOf("这一张码没有投出去", "与正在提交的那一张是同一枚", "你刚递进来的这一次没有投出去")) {
            assertTrue("内核里少了那一句「$sentence」（三档三句是这一颗的全部意义）：", kernel.contains(sentence))
        }
    }

    /**
     * ⑦ 闸门那一侧：记账仍然排在递交之前，而冷却剩余是在闸门算的。
     *
     * 这一条不重钉 [ScanSubmissionGateTest] ⑨a 那枚"置位先于回调"的钉子，
     * 钉的是它旁边的新事实：**递出去的不只是原文，还有这一枚在账上还要压多久**。
     * 谁自己去读那颗常数，谁就多长了一把冷却尺子。
     */
    @Test
    fun theGateStillAccountsBeforeItDispatchesAndHandsTheLedgerFactsOver() {
        val code = withoutComments(readMainSource(SCAN_SCREEN_FILE))
        val analyzer = balancedBlock(code, "private class QrCodeAnalyzer(")
        val gate = balancedBlock(analyzer, "private fun submitDecodedText(")
        val set = gate.indexOf("handled = ScanHandled(raw, now)")
        val submit = gate.indexOf("onCode(raw, RescanCooldownMillis)")
        check(set >= 0) { "闸门不再是「记账 + 递交」那两句了（⑨a 的钉子就在这一句上）：\n$gate" }
        check(submit >= 0) { "闸门递出去的那一手没把冷却剩余带上（状态机就会自己去读那颗常数，第二把尺）：\n$gate" }
        assertTrue("记账排到了递交之后（这期间进来的新帧会二次提交）：", set < submit)
        assertEquals("analyzer 里读 RescanCooldownMillis 的落点只许一处（闸门算给状态机那一次）：", 1, occurrences(analyzer, "RescanCooldownMillis"))
        assertEquals("onCode( 仍只能一处：", 1, occurrences(analyzer, "onCode("))
        assertEquals("相机那一路递交口只有一处带冷却剩余：", 1, occurrences(analyzer, "onCode(raw, RescanCooldownMillis)"))
    }

    /**
     * ⑧ 内核纯度与红线：这一颗判据不读钟、不读时区、不读权限、不读 `Build.VERSION`、
     * 不碰任何存储，也**不生成/不校准/不补算**任何时间戳。
     *
     * 顺带钉住"冷却常数只有一处声明"：这一颗只**引用**闸门那颗，
     * 不许在这里再 `const val RescanCooldownMillis` 一遍。
     */
    @Test
    fun theKernelStaysPureJvmAndNeverTouchesAClockOrAStore() {
        val raw = readMainSource(ADMISSION_FILE)
        val code = withoutComments(raw)
        val imports = code.lines().filter { it.trim().startsWith("import ") }
        assertTrue("$ADMISSION_FILE 里出现了 import，这段判据就到不了 JVM：\n$imports", imports.isEmpty())
        for (banned in listOf(
            "android.", "androidx.", "Build.VERSION", "System.currentTimeMillis", "SystemClock",
            "elapsedRealtime", "Date(", "Instant", "LocalDateTime", "LocalDate", "ZoneId", "TimeZone",
            "Random", "SharedPreferences", "Room", "DAO", "database",
        )) {
            assertFalse("判据本体自己去碰了设备/时钟/存储（$banned）—— 那些事实必须是参数", code.contains(banned))
        }
        assertFalse("内核里又声明了一枚冷却常数（第二把尺）：", code.contains("const val RescanCooldownMillis"))
        // 空白判据文件等于什么都没扫（本仓的守卫一律要求命中数 > 0）
        assertTrue("内核文件是空的？", code.length > 1_000)
        assertTrue("五档里少了「不重复念」那一档：", code.contains("data object DropAlreadyNoticed"))
        assertTrue("三档里少了「同一枚」那一档：", code.contains("data object DropSameCodeWhileDispatching"))
    }

    // ---- 源码核对工具（与 ScanSubmissionGateTest / ScanSilentBranchGuardTest 同一套刀法）----

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
        const val ADMISSION_FILE = "com/buaa/schedule/ui/signin/ScanFlightAdmission.kt"
    }
}
