package com.buaa.schedule.data.import

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 两族签到码的**分流**，以及这一层不许长出来的东西。
 *
 * 分流本身只有一句话（先 iClass 再 SPOC），但它决定的是"这次签到打到哪个平台"：
 * 两族同域（`*.buaa.edu.cn`）、都靠 query 传 ID，抢错方向的失败形状是
 * 「服务端回一句我们看不懂的中文」而不是「解析失败」，事后极难归因。
 *
 * 纯度守卫是仓库口径（判据必须能在 JVM 里表驱动跑）：解析层零 import、零 android、零取钟。
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

    @Test
    fun `智学北航那张码仍落到 SPOC 一支`() {
        assertEquals(
            ScanTarget.Spoc(SpocSignTarget.ByQdid("1AA9A5D7F4295A0DE0630211FE0AB83E")),
            ScanTargetParser.parse(
                "https://spoc.buaa.edu.cn/bhspoc/#/pages/table/signIn?qdid=1AA9A5D7F4295A0DE0630211FE0AB83E",
            ),
        )
        assertEquals(
            ScanTarget.Spoc(SpocSignTarget.ByCourse("ZJ001", "CZ002")),
            ScanTargetParser.parse(
                "https://spoc.buaa.edu.cn/bhspoc/#/pages/table/signIn?zjdm=ZJ001&czid=CZ002&step=1",
            ),
        )
        // 裸 ID 那一支（SPOC 独有）也不能被 iClass 认走
        assertEquals(
            ScanTarget.Spoc(SpocSignTarget.ByQdid("1AA9A5D7F4295A0DE0630211FE0AB83E")),
            ScanTargetParser.parse("1AA9A5D7F4295A0DE0630211FE0AB83E"),
        )
    }

    @Test
    fun `两族都不像时返回空`() {
        for (raw in listOf(null, "", "https://weixin.qq.com/r/abc", "智慧教室 3 号楼")) {
            assertFalse("不该认出目标：$raw", ScanTargetParser.parse(raw) != null)
        }
    }

    /** 判据的次序也钉一下：先 iClass 再 SPOC（理由见 [ScanTargetParser] 的类注释） */
    @Test
    fun `iClass 那一族排在前面`() {
        val code = blankComments(readSource(TARGET_FILE))
        val iClassAt = code.indexOf("IClassQrParser.parse(raw)")
        val spocAt = code.indexOf("SpocQrParser.parse(raw)")
        assertTrue("找不到两族各自的取值口，写法换过了：\n$code", iClassAt >= 0 && spocAt >= 0)
        assertTrue("SPOC 抢到了 iClass 前面：\n$code", iClassAt < spocAt)
    }

    @Test
    fun `解析层零import零android零取钟`() {
        for (name in listOf(TARGET_FILE, PARSER_FILE, URL_FILE)) {
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
