package com.buaa.schedule.ui.signin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T245：一次瞬时 ML Kit 失败把兜底第二引擎整轮锁死 —— 判据表 + **生产接线**守卫。
 *
 * ## 本文件存在的理由（家族说法要挑明）
 *
 * 改前那三处 `codeInHand` 命中（`ScanSecondEnginePolicyTest` 的行表默认值、
 * `ScanFrameObservationWiringGuardTest` 的 `runWindow`）全是**纯函数替身** —— 前者默认
 * `false`、后者干脆把它写死成 `false`。替身全绿而接线无人管，于是
 * `SpocScanScreen.kt` 里 `trySecondEngine` 那一行
 * `codeInHand = handled != null || awaitingUserAction` 把 [DecodeFailurePayload]
 * （按 ScanSubmissionGate.kt 的定义就是「那次解码根本没产出东西」）读成了「手里有一张码」，
 * 一整轮绑定的 zxing-cpp 补解就此被 [SecondEngineDecision.CodeInHand] 与
 * `afterMissesReprobeAllowsReprobe` 的 `!codeInHand` 双重按死 —— 后来真出现的糊码再也等不到补解。
 * 所以 ⑥ 那一格钉的是**那枚生产表达式本身**（逐字节 + 族别点名），不是它的替身。
 *
 * ## 第 0 问的裁定（甲）在这份表里怎么读
 *
 * 占位**不该**进 codeInHand。三档见 [secondEngineHandState]：Empty / FailureTrace / RealCode。
 * FailureTrace 那一档在提交闸门头上继续拦同档重投（留痕取证那一半用途，枚数钉在
 * `ScanSubmissionGateTest` ⑨），但在补解这一头它不算「手里有码」（⑦ 钉的是这一半没被摘掉）。
 * 两把尺各管各的，这才是那枚占位真正的形状。
 *
 * 零 android 依赖、零 Robolectric：判据本体是纯 JVM 函数，墙钟不进这张表
 * （[ScanHandled.atMillis] 由调用点填，本文件也只填测试用的假时刻）。
 */
class ScanSecondEngineCodeInHandGuardTest {

    // ---- ① 闸门留痕的三档：一枚都不许并档 ----

    private data class StateRow(
        val label: String,
        val handled: ScanHandled?,
        val expected: SecondEngineHandState,
    )

    @Test
    fun handStateTable() {
        val rows = listOf(
            StateRow("闸门空着", null, SecondEngineHandState.Empty),
            StateRow("只有失败留痕（占位 = 没有原文）", ScanHandled(DecodeFailurePayload, 1_000L), SecondEngineHandState.FailureTrace),
            StateRow("真有一张码", ScanHandled("CODE-A", 1_000L), SecondEngineHandState.RealCode),
            // 空白原文解出来那一档也得并进 RealCode：它进过闸门就是"有东西"，别在这里夹带 T88 那笔账
            StateRow("闸门里躺着空白原文", ScanHandled("   ", 1_000L), SecondEngineHandState.RealCode),
        )
        for (row in rows) {
            assertSame("${row.label}：", row.expected, secondEngineHandState(row.handled))
        }
        assertEquals(
            "handStateTable 覆盖的档数变了（Empty/FailureTrace/RealCode 三档一枚都不能并）：",
            3,
            rows.map { it.expected }.distinct().size,
        )
    }

    // ---- ② 表驱动：三档 handled × awaitingUserAction 真假 = 6 格 ----

    private data class Row(
        val label: String,
        val handled: ScanHandled?,
        val awaitingUserAction: Boolean,
        val expected: Boolean,
    )

    @Test
    fun codeInHandTable() {
        val rows = listOf(
            // 闸门空着：这时 awaitingUserAction 说的才是它本来的那句话（屏上挂着结果卡）
            Row("空闸门 + 没卡", null, awaitingUserAction = false, expected = false),
            Row("空闸门 + 卡挂着", null, awaitingUserAction = true, expected = true),
            // 失败留痕两档：**awaiting 是失败分支自己写的**（SpocScanScreen 两条失败出口），
            // 所以它在这一档里不能单独当"屏上有卡"的证据 —— 本卡锁死的正是第 4 格那一形
            Row("失败留痕 + awaiting 假", ScanHandled(DecodeFailurePayload, 1_000L), awaitingUserAction = false, expected = false),
            Row("失败留痕 + awaiting 真（锁死形状）", ScanHandled(DecodeFailurePayload, 1_000L), awaitingUserAction = true, expected = false),
            // 真原文那一档： awaiting 无论真假都停 —— 闸门里那张还没投出去/刚投过在冷却，补了也白发
            Row("真原文 + awaiting 假", ScanHandled("CODE-A", 1_000L), awaitingUserAction = false, expected = true),
            Row("真原文 + awaiting 真", ScanHandled("CODE-A", 1_000L), awaitingUserAction = true, expected = true),
        )
        for (row in rows) {
            assertEquals(
                "${row.label}（handled=${row.handled?.payload?.replace("\u0000", "\\u0000")} awaiting=${row.awaitingUserAction}）：",
                row.expected,
                secondEngineCodeInHand(row.handled, row.awaitingUserAction),
            )
        }
        // 靶子：六格里 true 与 false 两档都真的出现过，否则这张表在空转
        assertEquals("codeInHandTable 的结论档数变了（成立/不成立都得有格）：", 2, rows.map { it.expected }.distinct().size)
        assertEquals("表驱动格数不是 6（三档 handled × awaiting 真假）：", 6, rows.size)
    }

    // ---- ③ 本卡的正面靶：瞬时失败之后**下一帧**兜底仍然能开火 ----

    /**
     * 复刻 `analyze()` 的那一手再走一遍判据。
     *
     * ① 与 ② 只钉了派生函数本身；这一格钉的是**端到端**：把失败留痕照两条失败出口的形状
     * 写进闸门（`handled = ScanHandled(DecodeFailurePayload, …)` + `awaitingUserAction = true`，
     * 枚数由 `ScanSubmissionGateTest` ⑨ 钉着），再让连击到线的那一帧过 [secondEngineDecision]。
     * 同一格用**改前那把尺**再跑一遍当作反面靶 —— 它必须落到 CodeInHand，否则这张表根本
     * 没盖住病灶，改判据时也不会有人提醒。
     */
    @Test
    fun transientFailureLeavesTheNextFrameAbleToFire() {
        val streak = SecondEngineStreakFrames
        val frame = 11L
        // 第 10 帧：ML Kit onFailure（或 process() 同步抛）留下的那一手 —— 两样都写，
        // 与两条失败出口的形状一字不差（声明成可空，让改前那把尺真的按 `!= null` 判一次）
        val handled: ScanHandled? = ScanHandled(DecodeFailurePayload, 1_000L)
        val awaitingFromFailureBranch = true

        val beforeFix = secondEngineDecision(
            ledger = SecondEngineLedger(),
            streak = streak,
            frame = frame,
            codeInHand = handled != null || awaitingFromFailureBranch, // 改前那枚生产表达式，原样复刻
            engineUsable = true,
        )
        assertSame(
            "反面靶丢了：改前那把尺在这一帧不再落 CodeInHand，说明本卡描述的整轮锁死没人复现了",
            SecondEngineDecision.CodeInHand,
            beforeFix,
        )

        val afterFix = secondEngineDecision(
            ledger = SecondEngineLedger(),
            streak = streak,
            frame = frame,
            codeInHand = secondEngineCodeInHand(handled, awaitingFromFailureBranch),
            engineUsable = true,
        )
        assertSame("瞬时失败之后的下一帧必须还能发火（兜底被一枚占位锁死 = 真糊码再也等不到补解）", SecondEngineDecision.Fire, afterFix)
    }

    /**
     * ④ 判死之后那一发复探也不许被失败留痕挡掉。
     *
     * [afterMissesReprobeAllowsReprobe] 的 `!codeInHand` 是第二道锁：快节奏的三发花完落
     * GaveUp 之后，T91 留的「每 [SecondEngineReprobeFrameGap] 帧再试一发」同样读这一位。
     * 只修 `secondEngineDecision` 里 `codeInHand -> CodeInHand` 那一行而忘了这一道，
     * 症状会轻一点但仍锁着 —— 所以两档各一格。
     */
    @Test
    fun transientFailureDoesNotBlockTheReprobeShot() {
        val givenUp = SecondEngineLedger(fires = 3, misses = SecondEngineGiveUpAfterMisses, lastFireFrame = 37L)
        val reprobeFrame = 37L + SecondEngineReprobeFrameGap

        assertTrue(
            "判死后的复探被失败留痕挡掉了（一次瞬时失败就此锁掉整轮绑定的每一发）：",
            afterMissesReprobeAllowsReprobe(
                givenUp,
                streak = 99L,
                frame = reprobeFrame,
                codeInHand = secondEngineCodeInHand(ScanHandled(DecodeFailurePayload, 1_000L), awaitingUserAction = true),
                engineUsable = true,
            ),
        )
        assertFalse(
            "真原文在手时复探照旧该被挡（这一档是本卡的窄向另一半，摘掉它就是把兜底改成常态双解）：",
            afterMissesReprobeAllowsReprobe(
                givenUp,
                streak = 99L,
                frame = reprobeFrame,
                codeInHand = secondEngineCodeInHand(ScanHandled("CODE-A", 1_000L), awaitingUserAction = false),
                engineUsable = true,
            ),
        )
    }

    // ---- ⑤ 停痕只许念可能为真的那两件事 ----

    /**
     * [secondEngineStopText] 的 CodeInHand 那一改。
     *
     * 卡面 §3.2 那句「不许出现页上没有的东西被念成在屏幕上」在这一档的具体形状：
     * 旧句念「闸门里已经有原文或结果卡正等用户按」，而失败留痕那一档**两样都没有**
     * （屏上是一张都没立的空白取景框）。③ 已经把那一档从 CodeInHand 里摘出去了，
     * 这里钉的是句子本身：两个成因都要点得出、且要与自己划清失败留痕那一档。
     */
    @Test
    fun stopTextNamesOnlyWhatCanActuallyBeTrue() {
        val line = requireNotNull(secondEngineStopText(SecondEngineDecision.CodeInHand))
        assertTrue("停痕没点名「闸门里的真原文」这一成因：$line", line.contains("真原文"))
        assertTrue("停痕没点名「屏上挂着结果卡」这一成因：$line", line.contains("结果卡"))
        assertTrue("停痕没把自己与失败留痕那一档划清（页上什么都没有时这句话会被念成谎话）：$line", line.contains("失败留痕"))
        assertTrue("停痕太短，读不出后果：$line", line.length >= 12)
        for (banned in listOf("手输", "输入签到码", "手电", "补光", "闪光")) {
            assertFalse("停痕指向了已经不存在的入口「$banned」：$line", line.contains(banned))
        }
        // 正面配对：失败留痕那一帧根本走不到这句（否则上面三句都是空话）
        assertSame(
            "页上既无原文也无卡的那一帧仍被念成 CodeInHand ⇒ 停痕必然说谎：",
            SecondEngineDecision.Fire,
            secondEngineDecision(
                SecondEngineLedger(), SecondEngineStreakFrames, 11L,
                secondEngineCodeInHand(ScanHandled(DecodeFailurePayload, 1_000L), awaitingUserAction = true), true,
            ),
        )
    }

    /**
     * ⑥ **生产接线守卫**（本卡要的正是这一格）。
     *
     * 钉的是 `SpocScanScreen.kt` 里 `trySecondEngine` 递参数那一枚**表达式本身**，
     * 读形状 + 逐字节，族别点名：以前无人管的正是这一行，而改前那三处 codeInHand 命中
     * 全在纯函数替身里（一枚还把它写死成 false）。两条禁写各挡一类回归：
     * - 少了派生调用 ⇒ 接线又回到别处（或干脆没了），判据与页面各说各话；
     * - 出现 `handled != null` ⇒ 退回改前那把尺，一枚瞬时失败重新锁死整轮兜底。
     */
    @Test
    fun productionWiringFeedsTheKernelPredicateAndNotTheOldBool() {
        val code = withoutComments(readMainSource(SCAN_SCREEN_FILE))
        val analyzer = balancedBlock(code, "private class QrCodeAnalyzer(")
        val try2 = balancedBlock(analyzer, "private fun trySecondEngine(")
        assertTrue(
            "T245 的接线守卫：trySecondEngine 递出去的 codeInHand 不再是 secondEngineCodeInHand(handled, awaitingUserAction) —— " +
                "闸门留痕的分档又没人钉了（改前那一枚 handled != null 会把失败占位读成手里有码）：\n$try2",
            try2.contains("codeInHand = secondEngineCodeInHand(handled, awaitingUserAction),"),
        )
        assertFalse(
            "T245 的窄向回归：codeInHand 又退回 handled != null 那一把尺（一次瞬时 ML Kit 失败重新锁死整轮兜底）：\n$try2",
            try2.contains("handled != null"),
        )
        assertFalse(
            "T245 的另一头回归：codeInHand 只剩 awaitingUserAction —— 闸门里的真原文就不再算数了：\n$try2",
            try2.contains("codeInHand = awaitingUserAction"),
        )
        // 分档判据在整页只许一处调用：写第二份就是本卡那句"两把尺"
        assertEquals("secondEngineCodeInHand( 在 SpocScanScreen.kt 里不止一处（判据的第二份真相）：", 1, occurrences(code, "secondEngineCodeInHand("))
        assertEquals("secondEngineHandState( 在 SpocScanScreen.kt 里被调用过（分档只许在派生判据那一颗里发生）：", 0, occurrences(code, "secondEngineHandState("))

        // 内核侧的三样配套：声明一枚、三档一枚都不许并、FailureTrace 那一档的结论钉成 false
        val kernel = withoutComments(readMainSource(SECOND_ENGINE_FILE))
        assertEquals("fun secondEngineCodeInHand 的声明处只能一处：", 1, occurrences(kernel, "internal fun secondEngineCodeInHand"))
        assertEquals("fun secondEngineHandState 的声明处只能一处：", 1, occurrences(kernel, "internal fun secondEngineHandState"))
        // 三档的枚数要按**行**数，所以这里读原文（withoutComments 会把整段块注释折成一个空格、
        // 于是枚举条目被粘到上一行行尾，行数就不可信了）；注释行以 `*` 开头，天然不会被数进来
        val enumBody = balancedBlock(readMainSource(SECOND_ENGINE_FILE), "internal enum class SecondEngineHandState {")
        for (entry in listOf("Empty", "FailureTrace", "RealCode")) {
            // 只数枚举体内**独占一行**的那一枚条目，免得把引用它的句子数进来变成套套逻辑
            val declared = enumBody.lines().count { it.trim() == entry || it.trim() == "$entry," }
            assertEquals("SecondEngineHandState 的 $entry 这一档不是恰好一枚（并档就是本卡那把两用的尺）：\n$enumBody", 1, declared)
        }
        assertTrue(
            "secondEngineHandState 不再拿 DecodeFailurePayload 分档（占位被并进了 RealCode 或 Empty）：",
            kernel.contains("handled.payload == DecodeFailurePayload -> SecondEngineHandState.FailureTrace"),
        )
        assertTrue(
            "FailureTrace 那一档又被读成「手里有码」（本卡的病灶原形）：",
            kernel.contains("SecondEngineHandState.FailureTrace -> false"),
        )
    }

    /**
     * ⑦ 反向禁写：占位在**留痕取证**那一档还在被用着，摘掉它也是回归。
     *
     * 这是卡面 §3.4 的宽侧靶：把哨兵从所有判据里彻底摘掉（两条失败出口不再写占位、内核不再分档）
     * 看着像"更宽松更安全"，实际上是把同一档失败的去重记号一起拆了 —— 于是每次失败都变成
     * "从没放行过"，闸门那一头的冷却期与 `awaitingUserAction` 全部失效。
     */
    @Test
    fun failurePlaceholderStillLeavesItsTraceForForensics() {
        val code = withoutComments(readMainSource(SCAN_SCREEN_FILE))
        val analyzer = balancedBlock(code, "private class QrCodeAnalyzer(")
        assertEquals(
            "失败留痕不再写 DecodeFailurePayload（两条失败出口：ML Kit onFailure + process() 同步抛）：",
            2,
            occurrences(analyzer, "handled = ScanHandled(DecodeFailurePayload,"),
        )
        // 留痕那一头继续靠 awaitingUserAction 压同档重投（枚数与 ScanSubmissionGateTest ⑨ 同一口径）
        assertTrue("失败分支没再把闸门按在「只认新码」这一档：", occurrences(analyzer, "awaitingUserAction = true") >= 2)

        val kernel = withoutComments(readMainSource(SECOND_ENGINE_FILE))
        assertTrue("内核不再分这一档（占位彻底没人认得了）：", kernel.contains("SecondEngineHandState.FailureTrace"))
        assertTrue("secondEngineCodeInHand 不再吃 handled（留痕那一档的作用被摘走）：", kernel.contains("internal fun secondEngineCodeInHand(handled: ScanHandled?, awaitingUserAction: Boolean): Boolean"))

        val gate = withoutComments(readMainSource(GATE_FILE))
        assertTrue("DecodeFailurePayload 的声明挪过家而这里没跟着改：", gate.contains("internal const val DecodeFailurePayload"))
        // 提交闸门那一头的语义一个字不许动：占位仍按"同一张码"去重（本卡只摘补解那一头的误读）
        assertTrue("闸门不再按 payload 分新旧（占位的去重作用是留痕那一半的全部）：", gate.contains("if (raw != last.payload) return true"))
    }

    // ---- 源码核对工具（与 ScanSubmissionGateTest / ColdStartRebuildWiringTest 同一套手法）----

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
        const val GATE_FILE = "com/buaa/schedule/ui/signin/ScanSubmissionGate.kt"
        const val SECOND_ENGINE_FILE = "com/buaa/schedule/ui/signin/ScanSecondEnginePolicy.kt"
    }
}
