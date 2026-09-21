package com.buaa.schedule.ui.home

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T56（浏览非当前周时顶栏第二行的日期要跟着那一周）的结构守卫——本模块没有 Compose
 * 运行时（无 Robolectric、无 ui-test），HomeScreen 的回归只能走纯 JVM 源码扫描，
 * 刀法与 [DayTimelineStructureGuardTest] / [CourseTitleRowBudgetGuardTest] 一致：
 * 先抹注释（字符串保留），找不到源码目录直接抛，不用 assumeTrue 跳过。
 *
 * 钉的是机制不是文案：
 * 1. 判据内核 TopBarDateLabel.kt 零 android/androidx import（收单硬判据）。
 * 2. 顶栏第二行必须由 topBarDateLabel(...) 派生，且 browseWeek 真的传了进去——
 *    缺陷本体就是那一行只读 today。
 * 3. remember 的键必须列全四个入参（漏 browseWeek 翻周不动、漏 today 跨零点不动，
 *    都是 T41/T43 踩过的同一类坑）。
 * 4. HomeScreen 里不许再长出第二套周算术（plusWeeks / (week - 1) * 7）或第二处
 *    「M月d日」格式化：换算只在 SemesterWeekDates，格式只在 TopBarDateLabel。
 */
class TopBarDateWiringGuardTest {

    @Test
    fun dateLabelKernelStaysAndroidFree() {
        val text = source("com/buaa/schedule/ui/home/TopBarDateLabel.kt")
        val offenders = text.lines().map { it.trimStart() }
            .filter { it.startsWith("import android") }
        assertTrue(
            "TopBarDateLabel.kt 是要在 JVM 单测里跑裸的判据内核，混进设备依赖就没法测" +
                "（学期、开学日、今天、浏览周号全部得当参数传进来）：\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
        assertTrue(
            "周 → 周一的换算必须走 SemesterWeekDates，别在 UI 层再写一遍",
            text.contains("SemesterWeekDates.mondayOf("),
        )
    }

    @Test
    fun secondTopBarLineIsDerivedFromBrowseWeek() {
        val home = blankComments(source("com/buaa/schedule/ui/home/HomeScreen.kt"))
        val call = Regex("topBarDateLabel\\(([^)]*)\\)").find(home)
            ?: throw AssertionError("HomeScreen 不再调用 topBarDateLabel：第二行日期又回到只读 today 了")
        val args = call.groupValues[1]
        for (input in listOf("semesterStart", "currentWeek", "browseWeek", "today")) {
            assertTrue(
                "topBarDateLabel 的实参少了 $input，这一行就会在某种变化下停在旧值：$args",
                args.contains(input),
            )
        }
        assertTrue(
            "顶栏第二行的 Text 必须消费 dateLabel（由浏览周次派生），不能再直接摆 today",
            home.contains("text = dateLabel,"),
        )
        assertTrue(
            "HomeScreen 里不许出现第二处「M月d日」格式化，格式口径归 TopBarDateLabel 一处",
            !home.contains("M月d日"),
        )
    }

    @Test
    fun dateLabelRememberKeysCoverEveryInput() {
        val home = blankComments(source("com/buaa/schedule/ui/home/HomeScreen.kt"))
        val remember = Regex("remember\\(([^)]*)\\)\\s*\\{\\s*topBarDateLabel\\(").find(home)
            ?: throw AssertionError("dateLabel 不再由 remember 包住：每次重组重算格式器，且键的约定无从可验")
        val keys = remember.groupValues[1]
        for (key in listOf("semesterStart", "currentWeek", "browseWeek", "today")) {
            assertTrue("remember 的键漏了 $key —— 缓存住旧日期的就是这种漏键：$keys", keys.contains(key))
        }
    }

    @Test
    fun homeScreenHoldsNoSecondWeekArithmetic() {
        val home = blankComments(source("com/buaa/schedule/ui/home/HomeScreen.kt"))
        val offenders = home.lines()
            .map { it.trim() }
            .filter { it.contains("plusWeeks(") || Regex("\\(week - 1\\)\\s*\\*\\s*7").containsMatchIn(it) }
        assertTrue(
            "HomeScreen 里出现了第二套「周号 → 日期」算术，改学期锚点口径时它一定会漂：" +
                "\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    // ---- 靶子定位与词法小工具 ---------------------------------------------------

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

    /** 把 `//` 与 `/* */` 注释抹成空格（字符串保留、长度与换行位置不变） */
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
}
