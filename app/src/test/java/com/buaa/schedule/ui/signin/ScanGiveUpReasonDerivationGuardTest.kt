package com.buaa.schedule.ui.signin

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T99：把 `ScanDecoderHealth.giveUpReason` 的**不发火前提**从运行期事实钉成静态前提。
 *
 * 病灶（`docs/derived-field-audit.md` §2.1，全仓唯一一枚 a+b+c 字面齐全的派生构造参数）：
 * - `ScanRecoveryPolicy.kt:65` `val giveUpReason: String? = null,` 长在**参数表**上 ⇒
 *   `copy()` 会把没点名的它原样带走；
 * - `:160` 是生产侧唯一的赋值，源是同表的 `consecutiveFailures`（`:157`）与 `suspensionCycles`（`:159`）
 *   ⇒ 它是这两枚参数的函数（`"连续 $consecutive 帧解码失败、自动试回 $cycles 轮仍不成"`）；
 * - `:165` 与 `:167-172` 两处 `health.copy(...)` 改了这两枚源字段**却不重算** `giveUpReason`。
 *
 * 今天不发火**只靠** `:150` 那一行运行期早返回（`if (health.giveUpReason != null) return health`）：
 * 判死之后这个函数对任何输入都原样返回同一枚对象（`SpocScanScreen.kt:1214` 还靠 `next === health`
 * 的引用相等提前退出），所以那两条不重算的路径只在 `giveUpReason == null` 时走得到。
 * 真回归 0 枚 ≠ 不用钉 —— 谁把 `:150` 改掉、或在 `:165`/`:167` 之外再加一处改源的 copy，
 * 界面 `SpocScanScreen.kt:1072`（`val why = health.giveUpReason ?: "停用窗口内"`）与取证行
 * `ScanFrameFlowPolicy.kt:142` 立刻开始念**上一轮的** N/M，而 N/M 与当下账上的两枚数不是同一把尺子。
 *
 * 为什么走"形状守卫"而不是 T95 那一刀（把字段挪进类体当派生属性）：这一枚**不是同表参数的纯函数**，
 * 它还要"判到哪一档"这个上下文（`:155` 的 `cycles >= MaxDecodeSuspensionCycles`），挪进类体等于把判据
 * 复制成两处；而它的不发火前提是一行显式代码，一行显式代码钉得住，`copy()` 的语义盲区钉不住。
 *
 * 手法沿用本仓既有四种钉法里的两种（逐字节 + 数出现次数）：读源码文本、匹配前先抹注释、
 * **找不到锚点就抛**（静默跳过等于没有守卫）、每条计数都配一枚"被挖的那段确实还在"的靶子
 * （参照 `ScanFrameFlowGuardTest:399` 的 `baseline.contains("internal fun scannerGiveUp")`）。
 *
 * ⚠️ 与 `ScanFrameFlowGuardTest` 的分工线：那一枚管**读取点**（`:352` 不许 `frameFlowStop` 自己读
 * giveUpReason 拼第二份判死档、`:363` 不许页面 `LaunchedEffect` 自己算这本账），本枚管**产地**
 * （`:150` 那唯一一道保护、赋值只许一处、不重算的 copy 只许两条）。两处判据读的是不同的段，
 * 没有第二条真相。
 *
 * ⚠️ 判据 ①~⑤ **只数主源码那一份文件**（⑥ 是故意扩到整棵 `src/main` 的站点普查）：
 * `app/src/test` 里另有 7 处手搓 `giveUpReason` 赋值
 * （`ScanFrameFlowGuardTest.kt:93`、`ScanRecoveryPolicyTest.kt:154,158,176,178,210,212`）是测试
 * fixture，本文件一次都不把 `src/test` 读进计数（⑤ 钉住输入面本身、⑥ 的普查只走 `src/main`）。
 *
 * ⚠️ 主源码一个字没改：`ScanRecoveryPolicy.kt` 被四枚逐字节反向钉（
 * `ScanBlankDecodingWiringGuardTest.kt:192`、`ScanFrameAidWordingWiringGuardTest.kt:201`、
 * `ScanSecondEngineWiringGuardTest.kt:314`、`ScanFrameFlowGuardTest.kt:388`）加一枚纯 JVM/import
 * 检查（`ScanSilentBranchGuardTest.kt:229`）钉着，连一行锚点注释都加不得 ⇒ 本卡全部判据只读文本。
 *
 * 运行期那一半（判死后 `assertSame` 引用相等原样返回）已由 `ScanRecoveryPolicyTest.kt:122` 摆过真账，
 * 本卡钉的是静态那一半。本文件零 `android` import。
 */
class ScanGiveUpReasonDerivationGuardTest {

    /**
     * ① 判据 1（逐字节）：`:150` 那一整行原文必须还在，而且还得是那颗函数的**第一条语句**。
     *
     * 它是这枚字段唯一的保护：挪走位置（例如掉到 `val sameFrame` 后面）与删掉等价 —— 都让
     * `:165`/`:167` 那两条不重算的路径第一次真的能被 `giveUpReason != null` 走到。
     */
    @Test
    fun theOnlyEarlyReturnGuardIsStillVerbatimAndStillFirstStatement() {
        val code = policyCode()
        // 整行原文（含缩进）：ScanRecoveryPolicy.kt:150
        val guard = "if (health.giveUpReason != null) return health"
        val hits = code.lines().filter { it.trim() == guard }
        assertEquals(
            "ScanRecoveryPolicy.kt:150 那行早返回不是一整行了（它换成别的写法 = 这枚字段唯一的保护没了）：",
            1, hits.size,
        )
        assertEquals(
            "ScanRecoveryPolicy.kt:150 的缩进也变了（本条钉的是**整行原文**，4 空格缩进算在内）：",
            "    $guard", hits.first(),
        )
        val body = kernelBody(code)
        assertEquals(
            "ScanRecoveryPolicy.kt:150 不再紧跟在 healthAfterDecodeFailure 的签名之后 = 不再是第一条语句：" +
                "（晚一条语句，`:152`/`:154` 就已经先按新帧把 consecutive/cycles 记进账上，" +
                "而界面念的还是旧串）：",
            guard, body.lines()[1].trim(),
        )
        // 靶子：扫的确实是那两条不重算的路径的宿主函数（读空文件能让上面三条一次全绿）
        assertTrue(
            "靶子：healthAfterDecodeFailure 的签名整行还在（零命中=读错了文件或整份读空）：",
            code.contains(
                "internal fun healthAfterDecodeFailure(health: ScanDecoderHealth, frameSerial: Long): ScanDecoderHealth {",
            ),
        )
        assertTrue(
            "靶子：紧接早返回的 ScanRecoveryPolicy.kt:151 还在：",
            code.contains("val sameFrame = frameSerial == health.framesAtLastFailure"),
        )
    }

    /**
     * ② 判据 2（数出现次数）：生产侧的赋值全仓只许这一处（`:160`）。
     *
     * 第二处赋值就是第二把尺子 —— 一枚"应当永远等于 `(consecutiveFailures, suspensionCycles)`"
     * 的串开始有两个产地，`:165`/`:167` 那两条不重算的路径就不再是唯一的过期通道。
     *
     * ⚠️ 数的是**赋值**，用的是 `giveUpReason\s*=(?!=)`：卡面上那句"grep `giveUpReason =` 只有一处"
     * 在字面量层面其实是 2 处 —— `:116` 的 `health.giveUpReason == null` 共享 `giveUpReason =` 这个前缀，
     * 那是**读取点**不是赋值，负向查表把它排除掉（下面的靶子 3 把那 1 处 `==` 单独钉住，防口径漂）。
     */
    @Test
    fun productionAssignsTheDerivedFieldAtExactlyOneSite() {
        val code = policyCode()
        assertEquals(
            "ScanRecoveryPolicy.kt 里 giveUpReason 的赋值点只许有一处（现在在 :160）；" +
                "字段本身在 :65 的参数表上，所以多一处赋值 = 多一把尺子：",
            1, assignmentCount(code),
        )
        // 位置：唯一那处赋值必须还长在判死那一支里（:155-:162），不是在别处"顺手补一行"
        val branch = giveUpBranch(kernelBody(code))
        assertEquals(
            "ScanRecoveryPolicy.kt:160 那处赋值不在判死分支（if (cycles >= MaxDecodeSuspensionCycles)）里了：" +
                "赋值一旦离开判死分支，它就不再是 `:154` 那枚 cycles 的函数：",
            1, assignmentCount(branch),
        )
        // 靶子 1：被数的那一行原文（含 `$consecutive`/`$cycles` 两枚插值）还在
        val literal = "giveUpReason = \"连续 \$consecutive 帧解码失败、自动试回 \$cycles 轮仍不成\","
        assertEquals("靶子：:160 那行赋值原文不在了（计数扫的不是它）：", 1, code.lines().count { it.trim() == literal })
        // 靶子 2：串里两枚插值各自点名一枚同表参数 ⇒ 它确实是那两枚参数的函数（本卡的前提 (a)）
        assertTrue(
            "靶子：赋值串不再同时带 \$consecutive 与 \$cycles（那它就不是那两枚参数的函数了，本守卫要跟着改）：",
            literal.contains("\$consecutive") && literal.contains("\$cycles"),
        )
        // 靶子 3：:116 那枚 `== null` 读取点还在（②的 (?!=) 就是为了给它让路；它没了说明读的不是这份文件）
        assertEquals("靶子：ScanRecoveryPolicy.kt:116 那处 `giveUpReason ==` 比较不是赋值，命中数应为 1：", 1, Regex("""giveUpReason\s*==""").findAll(code).count())
        assertEquals("靶子：ScanRecoveryPolicy.kt:116 的读取点原文还在：", 1, occurrences(code, "health.giveUpReason == null && health.suspendUntilFrame < 0L"))
    }

    /**
     * ③ 判据 3（数出现次数）：改源却不重算的那两条路径必须还是**两条**，行号写进消息里。
     *
     * `consecutiveFailures = consecutive` 在本文件里出现 **3** 处：`:157`（判死那一支，它同支的 `:160` 会重算，
     * 所以它不是问题）、`:165`、`:168`（`:167-172` 那一支）。所以要数的是**去掉判死分支之后的 2**。
     * 加站点必须同步改本守卫 —— 三个数（3 = 2 + 1）互相核对，只动其中一处就红。
     */
    @Test
    fun theCopySitesThatChangeTheSourcesWithoutRecomputingAreStillExactlyTwo() {
        val code = policyCode()
        val body = kernelBody(code)
        val branch = giveUpBranch(body)
        val outside = body.replace(branch, " ")
        val needle = "consecutiveFailures = consecutive"
        assertEquals(
            "ScanRecoveryPolicy.kt 里 `$needle` 的出现次数不是「判死那一支 1 + 不重算的两条 2」了：",
            3, occurrences(body, needle),
        )
        assertEquals("ScanRecoveryPolicy.kt:157（判死那一支，同支 :160 会重算，不是病灶）仍是 1 处：", 1, occurrences(branch, needle))
        assertEquals(
            "ScanRecoveryPolicy.kt:165（`return health.copy(consecutiveFailures = consecutive, framesAtLastFailure = frameSerial)`）" +
                "与 :167-172（同一枚 copy，另带 suspensionCycles/suspendUntilFrame）是" +
                "「改了 giveUpReason 的两枚源字段却不重算」那两条路径，" +
                "条数一变就必须同步改本守卫（多一条 = 多一条念旧数的通道）：",
            2, occurrences(outside, needle),
        )
        // 靶子 1：:165 那处 copy 的整行原文（挖空文件骗得过上面三个数，骗不过这一条）
        assertTrue(
            "靶子：ScanRecoveryPolicy.kt:165 那处 copy 的原文还在：\n$outside",
            outside.contains("return health.copy(consecutiveFailures = consecutive, framesAtLastFailure = frameSerial)"),
        )
        // 靶子 2：:167-172 那处 copy 仍在同时改源字段与停用帧号
        assertTrue(
            "靶子：ScanRecoveryPolicy.kt:167-172 那处 copy 仍在改 suspensionCycles/suspendUntilFrame：\n$outside",
            outside.contains("suspensionCycles = cycles,") &&
                outside.contains("suspendUntilFrame = frameSerial + suspendWindowFrames(cycles)"),
        )
        // 靶子 3：那两条路径里 giveUpReason 只以 :150 那道早返回出现一次（真补了一处赋值 = 判据 ② 也该红）
        assertEquals("靶子：不重算的两条路径（:165 / :167-172）里 giveUpReason 只该出现一次，就是 :150 的早返回：", 1, occurrences(outside, "giveUpReason"))
        assertEquals("靶子：本函数三处 `return health.copy(` —— 一处判死（:156）、两处不重算（:165 / :167）：", 3, occurrences(body, "return health.copy("))
    }

    /**
     * ④ 补一条同族前提：复位那两档走的是**整枚新构造**，不是 copy。
     *
     * `:183`（解成功清零）与 `:195`（回到前台给新额度）一旦有人"顺手"改成 `health.copy(...)`，
     * 判死那轮的旧串就被原样带进一个"活着"的状态里 —— 而 `:150` 那道早返回管不到它们（它在另一颗函数里）。
     * 这一档是 audit §2 里"现在恰好没坏"的第二半个理由，所以它也得钉。
     */
    @Test
    fun theResetPathsStillBuildAFreshInstanceInsteadOfCopying() {
        val code = policyCode()
        val needle = "ScanDecoderHealth(pageVisibleRecoveries ="
        assertEquals("复位路径（:183 / :195）整枚新构造的次数不是 2 了：", 2, occurrences(code, needle))
        val success = balancedBlock(code, "internal fun healthAfterDecodeSuccess(")
        val visible = balancedBlock(code, "internal fun healthAfterPageVisible(")
        // 靶子：两档各自点名 —— 零命中=扫错了函数体
        assertTrue("靶子：ScanRecoveryPolicy.kt:183 仍是整枚新构造：\n$success", success.contains("return ScanDecoderHealth(pageVisibleRecoveries = health.pageVisibleRecoveries)"))
        assertTrue("靶子：ScanRecoveryPolicy.kt:195 仍是整枚新构造：\n$visible", visible.contains("return ScanDecoderHealth(pageVisibleRecoveries = health.pageVisibleRecoveries + 1)"))
        for ((label, block) in listOf(":179-184 healthAfterDecodeSuccess" to success, ":192-196 healthAfterPageVisible" to visible)) {
            assertEquals("$label 里出现了 health.copy(（复位路径一旦改成 copy 就会带走旧的判死串）：", 0, occurrences(block, "health.copy("))
        }
    }

    /**
     * ⑤ 计数面的边界：本守卫只读主源码那一份文件，`src/test` 的 fixture 一律不进输入。
     *
     * 手搓的 `giveUpReason = "又坏了"` / `"连续 3 帧解码失败、自动试回 3 轮仍不成"` 这类串在测试里有 7 处；
     * 把它们数进来会让判据 ② 直接变成一个假红，也会让"生产侧只有一处赋值"这件事失去含义
     * （「回到前台额度用完」那一档生产代码**从不**产生，它只能靠 fixture 表达）。
     */
    @Test
    fun theCountersScanOnlyTheMainKernelFile() {
        val file = File(findMainJavaDir(), POLICY_FILE)
        val slashed = file.path.replace('\\', '/')
        assertTrue("扫的对象不在 src/main/java 下（测试 fixture 会被数进判据 ②）：$slashed", slashed.contains("/src/main/java/"))
        assertEquals("扫的不是 ScanRecoveryPolicy.kt 这一份：", "ScanRecoveryPolicy.kt", file.name)
        val raw = normalizeNewlines(file.readText())
        assertTrue("靶子：读的这份里有生产定义本身（fixture 文件里不可能有）：", raw.contains("internal data class ScanDecoderHealth("))
        // 三枚同表参数仍全在参数表上 —— 前提 (a) 的全部；挪进类体就等于换了修法，本守卫要跟着改
        assertTrue("靶子：giveUpReason 仍长在 :65 的参数表上：", raw.contains("    val giveUpReason: String? = null,"))
        assertTrue("靶子：它的两枚源字段也在参数表上（:60 / :63）：", raw.contains("    val consecutiveFailures: Int = 0,") && raw.contains("    val suspensionCycles: Int = 0,"))
        // 空白判据文件等于什么都没扫（本仓守卫的老规矩）
        assertTrue("ScanRecoveryPolicy.kt 是空的？", raw.length > 3_000)
        // 抹掉注释后的形状：1 声明 + 3 读取（:116/:128/:137）+ 1 早返回（:150）+ 1 赋值（:160）= 6
        assertEquals(
            "抹过注释的主源码里 `giveUpReason` 的字面出现次数不是「1 声明 + 3 读取 + 1 保护 + 1 赋值」了：" +
                "（多出来的那一次要么是第二处赋值，要么是新长的读取点 —— 两边都要改守卫）：",
            6, occurrences(policyCode(), "giveUpReason"),
        )
    }

    /**
     * ⑥ 跨文件面：全仓 `src/main` 里改这本账的 copy 站点只许长在 ScanRecoveryPolicy.kt 这一份文件里。
     *
     * 判据 ①③ 只看那颗内核自己 —— 而卡面上那件真正会出事的事是"**谁将来加一处**
     * `health.copy(consecutiveFailures = …)`"，它完全可能加在别的文件里（页面就握着这枚状态：
     * `SpocScanScreen.kt:924` 的 `@Volatile private var health`）。这一条把 (b) 档的站点普查
     * 从一份文件扩到整棵 main，并且顺手钉住另一件事：**生产侧没有任何一处**能造出
     * 「回到前台额度用完」那一档的 giveUpReason（全仓 main 只有内核那一处赋值）。
     */
    @Test
    fun noOtherMainSourceCopiesTheHealthOrWritesItsSources() {
        val main = mainSourceCodes()
        for ((needle, expected) in listOf(
            "health.copy(" to 3, // :156 / :165 / :167 —— 与判据 ③ 的 3 同一把尺
            "consecutiveFailures = consecutive" to 3,
            "suspensionCycles = cycles" to 2, // :159 / :170
        )) {
            assertEquals("全仓 src/main 里 `$needle` 的出现次数不是 $expected 了：", expected, main.values.sumOf { occurrences(it, needle) })
            assertEquals(
                "全仓 src/main 里 `$needle` 不再只长在 ScanRecoveryPolicy.kt（换文件 = 第二本账）：",
                listOf(KERNEL_NAME),
                main.filter { occurrences(it.value, needle) > 0 }.keys.toList(),
            )
        }
        // 生产侧 giveUpReason 的赋值（含具名实参那种"构造时直接写死"）只许有 1 处、只许在这一份文件里
        val writers = main.filter { assignmentCount(it.value) > 0 }
        assertEquals("全仓 src/main 里给 giveUpReason 写值的文件数不是 1：${writers.keys}", listOf(KERNEL_NAME), writers.keys.toList())
        assertEquals("ScanRecoveryPolicy.kt 里多了一处 giveUpReason 赋值（第二把尺子）：", 1, assignmentCount(writers.getValue(KERNEL_NAME)))
        // 靶子 1：普查真的扫到了那一页（页面只整枚新构造，不 copy）—— 空扫描会让上面五条一次全绿
        assertEquals("靶子：SpocScanScreen.kt 里 ScanDecoderHealth( 的调用点不再是 1 处（:924 的初值）：", 1, occurrences(main.getValue("SpocScanScreen"), "ScanDecoderHealth("))
        assertEquals("靶子：全仓 main 里 ScanDecoderHealth( 仍是 4 处（内核 3 + 页面初值 1）：", 4, main.values.sumOf { occurrences(it, "ScanDecoderHealth(") })
        assertFalse("靶子：那一页自己 health.copy( 起了第二本账：", occurrences(main.getValue("SpocScanScreen"), "health.copy(") > 0)
        // 靶子 2：扫的是整棵 main，不是一份文件
        assertTrue("靶子：跨文件普查只扫到 ${main.size} 份 .kt（<150 = walk 没走对目录）：", main.size > 150)
    }

    // ---- 源码核对工具（与 ScanFrameFlowGuardTest 同一套，找不着锚点就抛） ----

    /** 抹过注释的主源码内核（判据 ①②③④⑤ 共用的那一份文本；注释里写多少遍都不进计数） */
    private fun policyCode(): String = withoutComments(normalizeNewlines(File(findMainJavaDir(), POLICY_FILE).readText()))

    /** 整棵 `src/main/java` 的 .kt，逐份抹过注释，键是去扩展名的文件名（判据 ⑥ 的跨文件普查面） */
    private fun mainSourceCodes(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        findMainJavaDir().walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            check(file.nameWithoutExtension !in out) { "src/main 里出现重名文件 ${file.name}：普查的键不唯一" }
            out[file.nameWithoutExtension] = withoutComments(normalizeNewlines(file.readText()))
        }
        return out
    }

    private fun kernelBody(code: String): String =
        balancedBlock(code, "internal fun healthAfterDecodeFailure(")

    /** 判死那一支（`:155-162`）：唯一给 `giveUpReason` 赋值的地方长在它里面 */
    private fun giveUpBranch(body: String): String =
        balancedBlock(body, "if (cycles >= MaxDecodeSuspensionCycles) {")

    /** 赋值点数：`=` 而**不是** `==`（:116 那处比较是读取点，见判据 ② 的靶子 3） */
    private fun assignmentCount(text: String): Int = Regex("""giveUpReason\s*=(?!=)""").findAll(text).count()

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

    private fun normalizeNewlines(text: String): String = text.replace("\r\n", "\n")

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
        /** 判据 ①~⑤ 只读这一份主源码；判据 ⑥ 铺到整棵 `src/main`，但 `src/test` 下那 7 处 fixture 永不进输入面 */
        const val POLICY_FILE = "com/buaa/schedule/ui/signin/ScanRecoveryPolicy.kt"

        /** 同一份文件在跨文件普查里的键（`walkTopDown` 的文件名，去扩展名） */
        const val KERNEL_NAME = "ScanRecoveryPolicy"
    }
}
