package com.buaa.schedule.data.import

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 签到码的**收码口**，以及这一层不许长出来的东西。
 *
 * T85 之前这颗文件钉的是"两族怎么分流"（先 iClass 后 SPOC，防 `qdid` 抢码）。抢码那一族
 * 整条拆掉之后，这一族还在钉三件事，而且每一件都比对岸更值得钉：
 * 1. 真码逐字进、逐字出（[ScanTarget.IClass] 只装原文 —— 抽字段重组就是另一条请求）；
 * 2. **收码口只有一族**：`?:` 后面不许再长出第二个解析器，那等于把签到打到别的平台上；
 * 3. 纯度守卫（仓库口径，判据必须能在 JVM 里表驱动跑）：解析层零 import、零 android、零取钟。
 *
 * 零取钟这一条在 iClass 这一族上格外要紧 —— 那张码带着 `timestamp`，
 * 谁都可能"顺手"拿它跟本地时间比一下然后判个"码已过期"。那种判定既没有契约依据
 * （时钟不同步就是假阳性），又把服务端该给的实话换成了我们编的话，所以这里连读钟口都不留。
 */
class ScanTargetParserTest {

    private val iClassCode =
        "http://iclass.buaa.edu.cn:8081/app/course/stu_scan_sign.action" +
            "?courseSchedId=2488752&timestamp=1790247391994"

    @Test
    fun `iClass 那张码落到 iClass 一支且原文逐字`() {
        assertEquals(ScanTarget.IClass(iClassCode), ScanTargetParser.parse(iClassCode))
    }

    /**
     * 智学北航那三种形态（完整 H5 链接、hash 路由片段、裸 32 位 ID）从前**都**被收码口收下。
     * T85 之后它们一律不收 —— 这一条是"拆干净"的正向证据：不是"文件没了"，
     * 而是同一批输入现在的结论反过来了。裸 ID 那一支尤其要紧：它没有任何上下文，
     * 收下来就是拿本校账号去打一个本仓已经不接的平台。
     */
    @Test
    fun `智学北航那三种形态如今一概不收`() {
        for (raw in listOf(
            "https://spoc.buaa.edu.cn/bhspoc/#/pages/table/signIn?qdid=1AA9A5D7F4295A0DE0630211FE0AB83E",
            "https://spoc.buaa.edu.cn/bhspoc/#/pages/table/signIn?zjdm=ZJ001&czid=CZ002&step=1",
            "1AA9A5D7F4295A0DE0630211FE0AB83E",
            "/pages/table/signIn?qdid=abc",
        )) {
            assertFalse("那一族已经拆掉了，还收它：$raw", ScanTargetParser.parse(raw) != null)
        }
    }

    @Test
    fun `不是这一族的码时返回空`() {
        for (raw in listOf(null, "", "https://weixin.qq.com/r/abc", "智慧教室 3 号楼")) {
            assertFalse("不该认出目标：$raw", ScanTargetParser.parse(raw) != null)
        }
    }

    /**
     * 收码口只有一族：这条从前钉"先 iClass 后 SPOC"的**次序**，次序这个概念现在没了。
     *
     * 改钉"这里只有一次调用"——那是同一条防线的现在形态：哪天有人拿 `?:` 接回第二个解析器，
     * 抢码就会重新发生（而它不会以"解析失败"露出来，只会以"服务端回一句看不懂的中文"露出来）。
     * 注释里那段防抢的理由**没有**跟着留在判据里：抢码的那一族已经不在了，留着就是描述一场
     * 不存在的竞争（[ScanTargetParser] 的类注释改成了实话，这一条钉的是判据代码本身）。
     */
    @Test
    fun `收码口只有一次调用`() {
        val code = blankComments(readSource(TARGET_FILE))
        val calls = Regex("""\w+QrParser\.parse\(raw\)""").findAll(code).map { it.value }.toList()
        assertEquals("收码口里的解析器调用：", listOf("IClassQrParser.parse(raw)"), calls)
        assertFalse("判据代码里又出现了 Spoc 字样：\n$code", code.contains("Spoc"))
        assertEquals("ScanTarget 的目标支数：", 1, Regex("""data class (\w+)\(""").findAll(code).count())
    }

    @Test
    fun `解析层零import零android零取钟`() {
        for (name in listOf(TARGET_FILE, PARSER_FILE, URL_FILE, REJECT_FILE)) {
            val code = blankComments(readSource(name))
            val imports = code.lines().map(String::trim).filter { it.startsWith("import ") }
            assertTrue("$name 出现了 import，这一层就到不了纯 JVM：$imports", imports.isEmpty())
            for (banned in CLOCK_AND_ANDROID) {
                assertFalse("$name 里出现了「$banned」：判据自己去碰设备/时钟了", code.contains(banned))
            }
            assertTrue("$name 空得可疑", code.length > 800)
        }
    }

    // ---- 源码核对工具（与同族守卫一个刀法：读不到就抛，静默跳过等于没有守卫）----

    private fun readSource(relativeUnderImportPackage: String): String {
        val file = File(findMainJavaDir(), "$IMPORT_PACKAGE/$relativeUnderImportPackage")
        assertTrue("找不到 ${file.path}：文件挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText()
    }

    /** 把行注释与块注释抹成空格（换行位置不变）：钉的是代码，不是白话 */
    private fun blankComments(src: String): String {
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
        const val IMPORT_PACKAGE = "com/buaa/schedule/data/import"
        const val TARGET_FILE = "ScanTarget.kt"
        const val PARSER_FILE = "IClassQrParser.kt"
        const val URL_FILE = "IClassSignUrl.kt"

        /** T83：拒绝原因的分档判据与三处措辞都住在这颗文件里，纯度口径与上面三枚一模一样 */
        const val REJECT_FILE = "ScanReject.kt"

        val CLOCK_AND_ANDROID = listOf(
            "android.",
            "androidx.",
            "SystemClock",
            "System.currentTimeMillis",
            "Instant",
            "LocalDateTime",
            "Date(",
        )
    }
}
