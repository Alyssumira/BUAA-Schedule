package com.buaa.schedule.ui.signin

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「解析失败要能说实话」的**接线**守卫（T83②③）。
 *
 * 判据本体在 `data/import/ScanReject.kt` 里有表驱动与全矩阵两档，这里只管三件 JVM 跑不到的事：
 * ① 状态机**真的**按档说话 —— 失败那一支走的是内核的 `classify` + `scanRejectCardText`，
 *    而不是在 ViewModel 里再拼一句"两族都不认"（那就是第二份口径，而且它已经是个错的了）；
 * ② 取证行**只出自内核** —— `Log.w(TAG, scanRejectForensicLine(info))` 一颗调用点，
 *    VM 里不许出现任何形状字面量（手拼一份，日志与界面就会各说各话），也不许出现
 *    `Log.d` / `Log.v` / `BuildConfig.DEBUG`（用户那台机器把 logcat 砍到 Info，写了等于没写）；
 * ③ 失败卡上那行形状**渲染得出来** —— 形状进了 `SignInState.Failed` 却没人画，
 *    用户看到的还是那一句断言（这一页修过的三条静默死路都是这个形状）。
 *
 * 手法沿用 [ScanSilentBranchGuardTest] / [IClassSignInWiringGuardTest]：读源码文本、
 * 匹配前先抹注释、**找不到锚点就抛**（静默跳过等于没有守卫）。
 */
class ScanRejectWiringGuardTest {

    /** ① 解析失败那一支：档位、话、出口，三者都从内核来 */
    @Test
    fun parseFailureIsExplainedByTheKernelNotByTheViewModel() {
        val vm = withoutComments(readMainSource(VIEW_MODEL_FILE))
        val branch = balancedBlock(vm, "private fun rejectScan(")
        assertTrue("失败那一支没问判据是哪一档：\n$branch", branch.contains("ScanRejectClassifier.classify(raw)"))
        assertTrue(
            "失败卡上那句不是内核按档拼的（VM 自己写了一句就是第二份口径）：\n$branch",
            branch.contains("reason = scanRejectCardText(info)"),
        )
        assertTrue(
            "失败卡没把形状交给界面（档位说了半句就断了）：\n$branch",
            branch.contains("evidence = scanRejectEvidenceText(info)"),
        )
        assertFalse("调用点自己按档位分支了（VM 里再 when 一次 rung 就是判据的第二份）：", branch.contains("info.rung"))
        // 那句糊成一句的旧话不许从任何一头复活
        for (gone in listOf("这不是一张", "智学北航与 iClass 都不认")) {
            assertFalse("旧的那句「$gone」还留在状态机里：T84 之后它是错的", vm.contains(gone))
        }
        assertEquals("解析失败只许有一条出口（第二颗 Failed(...) 就是没人分档的那一支）：", 1, occurrences(vm, "scanRejectCardText("))
    }

    /** ② 取证行：一颗调用点、Warn 级、措辞全出自内核，且这行不是按帧跑的 */
    @Test
    fun forensicLineIsLoggedOnceFromTheKernelAtWarnLevel() {
        val vm = withoutComments(readMainSource(VIEW_MODEL_FILE))
        val branch = balancedBlock(vm, "private fun rejectScan(")
        assertEquals("取证行的调用点只能一处：", 1, occurrences(vm, "scanRejectForensicLine("))
        assertTrue("调用点没把内核那一行交给 Log.w：\n$branch", branch.contains("Log.w(TAG, scanRejectForensicLine(info))"))
        for (banned in listOf("Log.d(", "Log.v(", "Log.e(")) {
            assertEquals("状态机里出现了 $banned（Info 以下在读不到的机器上等于没写）：", 0, occurrences(vm, banned))
        }
        assertEquals("状态机里不许有 debug-only 门禁（这一行 release 必须可见）：", 0, occurrences(vm, "BuildConfig.DEBUG"))
        for (lit in listOf("参数名=", "参数值=", "档=", "形状=")) {
            assertFalse("取证行的字面量「$lit」被抄进了状态机：那日志与界面就是两份口径", vm.contains(lit))
        }
        assertTrue("tag 没定（logcat 抓不到这一支）：", vm.contains("private const val TAG = \"ScanSignInParse\""))
        // 按次不按帧：这一支只在 signIn 里 parse 落空时走一次，而 signIn 排在提交闸门之后
        assertTrue("状态机自己去读了帧计数（这一支不该认识帧）：", !vm.contains("frameCount"))
    }

    /** ③ 失败卡画得出那行形状，而且形状字符串没在页面上另写一份 */
    @Test
    fun failureCardRendersTheScannedShape() {
        val screen = withoutComments(readMainSource(SCAN_SCREEN_FILE))
        assertTrue(
            "失败卡不再读 evidence 字段（形状进了状态机却没人画 —— 第四条静默支路）：\n$screen",
            screen.contains("val evidence = (state as? SignInState.Failed)?.evidence"),
        )
        assertTrue("形状为空时也照画一行空 Text：\n$screen", screen.contains("if (evidence != null)"))
        for (lit in listOf("不是签到码", "认不出服务器", "学生扫码签到的入口", "扫码解析失败")) {
            assertFalse("措辞在页面上又写了一份（「$lit」）：判据就被绕过了", screen.contains(lit))
        }
        // 形状那一行也必须成对取墨：ALERT 底板上那行小字要是走 onSurfaceVariant，改前的读数就从第二行复活
        val evidenceCall = balancedBlock(screen, "if (evidence != null)")
        assertTrue("形状那一行没成对取墨：\n$evidenceCall", evidenceCall.contains("LocalSemanticPlate"))
        assertEquals("形状那一行自己又算了一份墨色：", 1, occurrences(evidenceCall, "color = "))
    }

    /** ④ 反查：整条链上「解析失败」只有一处出口，两族并存之后不许有第二句糊话 */
    @Test
    fun onlyOneParseFailureSentenceExistsInTheSignInChain() {
        val vm = withoutComments(readMainSource(VIEW_MODEL_FILE))
        val screen = withoutComments(readMainSource(SCAN_SCREEN_FILE))
        // 相机与相册两条入口共用 signIn（手输入口已删除），所以"解析失败"这句话在源码里只有一处生产者
        assertEquals("分档那一颗只许定义一处：", 1, occurrences(vm, "fun rejectScan("))
        assertEquals("解析失败的出口只许一处（第二颗就是没人分档的那一支）：", 1, occurrences(vm, "rejectScan(raw)"))
        assertEquals("两条入口都还在投同一个 signIn：", 2, occurrences(screen, "viewModel.signIn("))
        assertTrue(
            "相册那条不再报「图里没认出码」：那是第二站（解码器）的事，与第三站的分档各说各的，两句话都要在",
            screen.contains("viewModel.reportNoQrCode()"),
        )
    }

    // ---- 源码核对工具（与同族守卫一个刀法）----

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

    private fun readMainSource(relativeFromJava: String): String {
        val file = File(findMainJavaDir(), relativeFromJava)
        assertTrue("找不到 ${file.path}：文件挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText()
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
    }
}
