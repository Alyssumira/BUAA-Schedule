package com.buaa.schedule.ui.home

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T58 的守卫：学分与体育项目的**接线**不许静默 no-op。
 *
 * 本仓反复栽过的坑是"数据链路全有、没人显示"——credit 从解析到 Room 到备份都在，
 * 差的就是界面上那几行；接线一旦被"顺手重构"掉，单测里判据内核照样全绿，
 * 用户那边学分又人间蒸发了。所以照 [NowLineUnderCardsGuardTest] 的刀法扫源码文本钉住：
 * 找不到源码目录直接抛（跳过的守卫比没有守卫更糟）、该有调用点的一处都没有就红。
 *
 * 钉住四条：
 * 1. 详情 Sheet 真的调用了 creditLabel 与 peProjectOf，且行序是 教师/地点/校区/学分/体育项目/备注
 *    （学分整行缺席的语义由 creditLabel 返回 null 实现，不许有人退回 DetailRow 的「未设置」）。
 * 2. peProjectOf 的调用实参**必须是 course.name**：displayName 是别名优先，
 *    用户把别名改成「体育课」项目名就没处剥了——这条换成 displayName 编译器不会响。
 * 3. 管理页课程组与日视图卡片行的学分都走 joinMeta/creditLabel，不许手拼 " · "。
 * 4. 全仓 main 源码不许出现 DecimalFormat / NumberFormat：学分格式化钉死在
 *    CourseMetaFormat 的手拼字符串上，locale 敏感的格式化器会让 JVM 单测与设备行为劈叉。
 */
class CourseMetaWiringGuardTest {

    @Test
    fun detailSheetRendersCreditAndPeProjectInOrder() {
        val code = blankComments(source(DETAIL_SHEET))
        val teacher = at(code, "DetailRow(\"教师\"")
        val location = at(code, "DetailRow(\"地点\"")
        val campus = at(code, "DetailRow(\"校区\"")
        val credit = at(code, "creditLabel(course.credit)")
        val pe = at(code, "peProjectOf(")
        val remark = at(code, "DetailRow(\"备注\"")
        // creditLabel 包着的那行必须是"有值才画"：整行若退回无条件 DetailRow("学分", ...)，
        // 缺失就会印成「未设置」——at() 找到的 DetailRow("学分" 只允许出现在 ?.let 分支里，
        // 这里用调用点数恰为一处兜住"删了接线"和"接成无条件占位"两种退化
        assertTrue(
            "学分行必须经 creditLabel(course.credit) 喂给 DetailRow，且 DetailRow(\"学分\" 只有一处：" +
                "无条件画它就等于把缺失冒充成占位文案（$credit）",
            code.indexOf("creditLabel(course.credit)") in 0 until remark &&
                Regex("""DetailRow\("学分"""").findAll(code).count() == 1,
        )
        assertTrue(
            "行序必须是 教师→地点→校区→学分→体育项目→备注（$teacher/$location/$campus/$credit/$pe/$remark）",
            teacher < location && location < campus && campus < credit && credit < pe && pe < remark,
        )
    }

    @Test
    fun peProjectIsAlwaysTakenFromCourseName() {
        val code = blankComments(source(DETAIL_SHEET))
        val args = Regex("""peProjectOf\(\s*([^)]*)\)""").findAll(code).map { it.groupValues[1].trim() }.toList()
        assertTrue(
            "详情 Sheet 里一个 peProjectOf 调用点都没有——接线被删了，体育项目又隐身了",
            args.isNotEmpty(),
        )
        for (arg in args) {
            assertTrue(
                "peProjectOf($arg)：体育项目必须从 course.name 剥。displayName 别名优先，" +
                    "用户把别名改成「体育课」就把项目弄丢了，这条换实参编译器不会报错",
                arg == "course.name",
            )
        }
    }

    @Test
    fun groupRowAndDayCardFeedCreditThroughJoinMeta() {
        val management = blankComments(source(MANAGEMENT))
        assertTrue(
            "管理页课程组摘要必须经 creditLabel(group.fragments...) 进 joinMeta，不许手拼 \" · \"",
            Regex("""creditLabel\(\s*group\.fragments\.firstNotNullOfOrNull""").containsMatchIn(management),
        )
        val day = blankComments(source(DAY_VIEW))
        val at = at(day, "internal fun dayCourseMetaLine")
        val body = day.substring(at, day.indexOf('\n', day.indexOf("course.credit)", at)))
        assertTrue(
            "日视图列表模式卡片行必须把学分经 creditLabel(course.credit) 交给 joinMeta" +
                "（缺项连分隔符一起缺席）：@$at",
            body.contains("creditLabel(course.credit)") && body.contains("joinMeta("),
        )
    }

    /** ④：学分格式化只有一处手拼实现，别冒出第二份 locale 敏感的 */
    @Test
    fun noLocaleSensitiveNumberFormatterAnywhere() {
        val root = findMainJavaDir()
        val hits = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.filter { file ->
            // 注释要先抹掉再扫：CourseMetaFormat 自己的 KDoc 就写着"不许用 DecimalFormat"，
            // 连注释一起扫会把这句禁令 itself 当成违规（第一版就红在了这上面）
            val code = blankComments(file.readText())
            Regex("""\b(DecimalFormat|NumberFormat)\b""").containsMatchIn(code)
        }.map { it.relativeTo(root).path }.toList()
        assertTrue(
            "main 源码里出现了 DecimalFormat/NumberFormat：$hits —— 学分/数值显示一律走 " +
                "CourseMetaFormat 的手拼字符串，格式化器跟着 locale 走，JVM 单测钉的断言" +
                "和设备上的输出可能对不上",
            hits.isEmpty(),
        )
    }

    // ---- 靶子定位小工具（与 NowLineUnderCardsGuardTest 同一套刀法）----

    /** 靶子必须存在：找不到就红，而不是返回 -1 让上面的大小比较"恰好"成立 */
    private fun at(code: String, needle: String): Int {
        val idx = code.indexOf(needle)
        assertTrue("找不到 $needle —— 靶子没了，这条钉子的前提已经不成立", idx >= 0)
        return idx
    }

    private fun source(relative: String): String {
        val file = File(findMainJavaDir(), relative)
        assertTrue("找不到 $relative：挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText()
    }

    /** 单测的工作目录是模块目录还是仓库根不由这里决定：几种布局都试一遍，全落空就抛 */
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

    /** 把 `//` 与 `/* */` 注释抹成空格（字符串保留、长度与换行位置不变）：钉的是接线，不是注释 */
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

    private companion object {
        const val DETAIL_SHEET = "com/buaa/schedule/ui/home/CourseDetailSheet.kt"
        const val MANAGEMENT = "com/buaa/schedule/ui/course/CourseManagementScreen.kt"
        const val DAY_VIEW = "com/buaa/schedule/ui/home/DayView.kt"
    }
}
