package com.buaa.schedule.ui.signin

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 扫码提交闸门（`QrCodeAnalyzer` 里那道锁，T44 重做）。
 *
 * 用户报的「扫码没反应」根因：旧闸门是一颗 `consumed: Boolean`，**任何一次放行**都把它置成
 * true，而它只在 `SignInState.Idle` 那一档被清 —— Idle 又只有用户按结果卡上的
 * 「重新扫码 / 继续扫码」才回得来。于是扫到一次之后每一帧都被静默丢掉（`image.close()` +
 * return），预览看着完全活着、镜头再对准一张码也不会有任何反应。
 *
 * 现在的判据是「原文 + 时刻」，见 [shouldSubmitScan]：换一张码立刻放行，同一张码要过冷却期、
 * 而且屏幕上没有等用户按的结果卡。测试用注入的假时钟，闸门本体不读墙钟。
 *
 * 本文件对生产代码只做一件事：调 [shouldSubmitScan] 这些纯函数 + 按源码形状核对几条
 * JVM 跑不到的接线（置位先于回调、resume 还连着那颗按钮）。零 `android` import。
 */
class ScanSubmissionGateTest {

    private val t0 = 1_700_000_000_000L

    private fun handled(payload: String, offsetMillis: Long) = ScanHandled(payload, t0 + offsetMillis)

    /** ① 从没放行过：第一帧就递交 */
    @Test
    fun firstFrameGoesThrough() {
        assertTrue(shouldSubmitScan(null, "https://spoc.buaa.edu.cn/x?qdid=1", t0, awaitingUserAction = false))
    }

    /** ② 同一张码、冷却期内：仍然抑制（这就是「一次只签一个」那条不变式） */
    @Test
    fun samePayloadInsideCooldownStaysSuppressed() {
        val last = handled("CODE-A", 0)
        assertFalse(shouldSubmitScan(last, "CODE-A", t0 + 1, awaitingUserAction = false))
        assertFalse(shouldSubmitScan(last, "CODE-A", t0 + RescanCooldownMillis - 1, awaitingUserAction = false))
        // 连续几十帧都落在这一档：这就是旧闸门要挡的重投风暴，一秒都不许漏
        var submitted = 0
        for (millis in 0L until RescanCooldownMillis step 16) {
            if (shouldSubmitScan(last, "CODE-A", t0 + millis, awaitingUserAction = false)) submitted++
        }
        assertEquals(0, submitted)
    }

    /** ③ 换了一张码：立刻放行，不等冷却期 —— 这一条就是旧闸门锁死用户的那一档 */
    @Test
    fun differentPayloadGoesThroughImmediately() {
        val last = handled("CODE-A", 0)
        assertTrue(shouldSubmitScan(last, "CODE-B", t0 + 1, awaitingUserAction = false))
        // 结果卡还挂着也放行：换码是唯一一条不需要按按钮就能重试的路
        assertTrue(shouldSubmitScan(last, "CODE-B", t0 + 1, awaitingUserAction = true))
        // 从"解码失败"的留痕里也出得来：相机好了一下之后照样能签
        assertTrue(shouldSubmitScan(handled(DecodeFailurePayload, 0), "CODE-A", t0 + 1, awaitingUserAction = true))
    }

    /** ④ 同一张码、冷却期已过（且界面没在等用户按）：放行 */
    @Test
    fun samePayloadAfterCooldownGoesThrough() {
        val last = handled("CODE-A", 0)
        assertTrue(shouldSubmitScan(last, "CODE-A", t0 + RescanCooldownMillis, awaitingUserAction = false))
        assertTrue(shouldSubmitScan(last, "CODE-A", t0 + RescanCooldownMillis * 10, awaitingUserAction = false))
    }

    /** ⑤ 结果卡挂着（Failed / Signed 等按钮）：同一张码不再重投，等多久都不投 */
    @Test
    fun samePayloadIsNotResubmittedWhileACardIsOnScreen() {
        val last = handled("CODE-A", 0)
        assertFalse(shouldSubmitScan(last, "CODE-A", t0 + RescanCooldownMillis, awaitingUserAction = true))
        assertFalse(shouldSubmitScan(last, "CODE-A", t0 + 10_000, awaitingUserAction = true))
        // 理由（本卡唯一不那么显然的一条）：同一张码重投出来的还是**相等**的 Failed 值，
        // MutableStateFlow 不重发相等的值 ⇒ Idle 那一支永远等不到清闸门的机会。
        // 只按冷却期放行的话就是每 1500ms 一次真提交，一直刷到用户离开。
    }

    /** ⑥ Idle / reset()：清闸门之后同一张码、零间隔也放行（那颗「重新扫码」按钮的语义） */
    @Test
    fun resumeClearsTheGateEntirely() {
        // resume() 的本体是"把记着的这两样都放下"—— 形状钉在下面第 ⑨ 条 b；这里是语义账：
        // 放下之后同一份原文、同一毫秒也要能再投一次
        assertTrue(shouldSubmitScan(null, "CODE-A", t0, awaitingUserAction = true))
        val last = handled("CODE-A", 0)
        assertFalse(shouldSubmitScan(last, "CODE-A", t0, awaitingUserAction = true))
    }

    /** ⑦ 时钟倒退（NTP 校时、用户改表）：方向是少投一次，不是多投 */
    @Test
    fun clockGoingBackwardsSuppresses() {
        val last = handled("CODE-A", 10_000)
        assertFalse(shouldSubmitScan(last, "CODE-A", t0, awaitingUserAction = false))
    }

    /** ⑧ 冷却期是同一个数、且写在闸门旁边（不许出现第二份口径） */
    @Test
    fun cooldownIsOneNamedConstant() {
        assertEquals(1_500L, RescanCooldownMillis)
        val source = withoutComments(readMainSource(GATE_FILE))
        val declarations = source.lines().count { it.contains("CooldownMillis") && it.contains("const val") }
        assertEquals("冷却期声明了 $declarations 处，只能一处", 1, declarations)
    }

    /**
     * ⑨ 接线形状（JVM 跑不到 analyzer，只能按源码核对）：三件事都必须成立。
     *
     * a. **置位先于回调**：`handled = ScanHandled(...)` 排在 `onCode(...)` 前面 ——
     *    回调里就开始发请求，这期间新帧可能已经进来了，晚一步置位就是双重提交；
     * b. **resume 还连着那颗按钮**，而按钮的唯一入口是 Idle 那一档；
     *    c. **失败留痕 + awaitingUserAction 一起写**，两条失败出口（ML Kit 的失败回调、
     *    process() 同步抛）都要写，漏一条就是旧 consumed 的死锁在某一头复活。
     */
    @Test
    fun analyzerKeepsTheOrderingAndTheTwoReleasePaths() {
        val code = withoutComments(readMainSource(SCAN_SCREEN_FILE))
        // 相册那条入口里也有一个 `addOnSuccessListener { codes ->`，所以整段先在 analyzer 体内找
        val analyzer = balancedBlock(code, "private class QrCodeAnalyzer(")

        val success = balancedBlock(analyzer, ".addOnSuccessListener { codes ->")
        val set = success.indexOf("handled = ScanHandled(")
        val submit = success.indexOf("onCode(")
        check(set >= 0 && submit >= 0) { "成功分支不再是「置位 + onCode」那一对了：\n$success" }
        assertTrue("置位排到了回调后面（这期间进来的新帧会二次提交）：\n$success", set < submit)
        // 判据本体必须在置位之前读过一次闸门
        assertTrue(
            "成功分支不再走 shouldSubmitScan 判据：\n$success",
            success.indexOf("shouldSubmitScan(") in 0 until set,
        )

        // 两处失败留痕：ML Kit 的失败回调 + process() 同步抛，漏一条就是旧 consumed 的死锁在某一头复活
        val failureMarks = occurrences(analyzer, "handled = ScanHandled(DecodeFailurePayload,")
        assertEquals("失败留痕写了 $failureMarks 处，应当是 2（ML Kit 失败回调 + 同步抛）：", 2, failureMarks)
        assertTrue(
            "失败分支没把闸门按在「只认新码」这一档（同一档失败会每 1500ms 重投一次）",
            occurrences(analyzer, "awaitingUserAction = true") >= 2,
        )
        // 墙钟只在调用点读：analyzer 里读、判据函数里不读
        assertEquals("analyzer 里没有 System.currentTimeMillis() 以外的取钟口", 3, occurrences(analyzer, "System.currentTimeMillis()"))

        // 布尔死锁不许复活
        assertEquals("consumed 还在：${occurrences(code, "consumed")}", 0, occurrences(code, "consumed"))

        // b. resume()/markAwaitingUserAction() 都由 state 那一颗 effect 驱动，
        //    而 resume() 真的两样都放下
        val resume = balancedBlock(analyzer, "fun resume()")
        assertTrue("resume() 没清掉放行记录（按了「重新扫码」还是投不进去）：\n$resume", resume.contains("handled = null"))
        assertTrue("resume() 没清掉等按钮那一档：\n$resume", resume.contains("awaitingUserAction = false"))
        val driver = balancedBlock(code, "LaunchedEffect(state)")
        assertTrue(driver.contains("analyzer?.resume()"))
        assertTrue(driver.contains("if (state is SignInState.Idle)"))
        assertTrue(
            "结果卡那一档没推给闸门（第 ⑤ 条就成了纸上判据）：\n$driver",
            driver.contains("markAwaitingUserAction(state is SignInState.Failed || state is SignInState.Signed)"),
        )
    }

    /** ⑩ 仓库口径：这两份判据文件里一个 android import 都不许有（否则 JVM 单测跑不到） */
    @Test
    fun judgesStayPureJvm() {
        for (file in listOf(GATE_FILE, STATUS_FILE)) {
            val imports = withoutComments(readMainSource(file)).lines().filter { it.startsWith("import ") }
            assertTrue(
                "$file 里出现了 android/androidx 依赖，这段判据就到不了 JVM：\n$imports",
                imports.none { it.contains("android") },
            )
            assertTrue("$file 的 import 只该是 junit 那些工具以外的东西：${imports.size}", imports.isEmpty())
        }
        // 时钟与设备事实留在调用点：判据函数不许自己读墙钟
        val gate = withoutComments(readMainSource(GATE_FILE)) + withoutComments(readMainSource(STATUS_FILE))
        assertFalse("判据本体自己去读了墙钟", gate.contains("System.currentTimeMillis"))
    }

    /** ⑪ 相册那条入口不受闸门影响（它是单次动作，本来就没有按帧重投的问题） */
    @Test
    fun galleryPathDoesNotTouchTheGate() {
        val code = withoutComments(readMainSource(SCAN_SCREEN_FILE))
        val gallery = balancedBlock(code, "ActivityResultContracts.PickVisualMedia(),")
        check(gallery.contains("scanner.process(")) { "相册那条解码换了写法：\n$gallery" }
        assertFalse("相册那条被卷进闸门了（它是一次动作，没有风暴要挡）", gallery.contains("shouldSubmitScan("))
    }

    // ---- 源码核对工具（与 ColdStartRebuildWiringTest 同一套手法）----

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
        const val STATUS_FILE = "com/buaa/schedule/ui/signin/ScanUiStatus.kt"
    }
}
