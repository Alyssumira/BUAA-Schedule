package com.buaa.schedule.ui.home

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T68b 的结构守卫——「顶栏第二行不许读一个不在屏上的日子」。
 *
 * 本模块没有 Compose 运行时（无 Robolectric、无 ui-test），`HomeScreen` 里那一行
 * 「只有周页签才渲染」的事实只能扫源码，刀法照抄 [DayBrowseWiringGuardTest]：
 * `blankComments` 抹注释、`balancedBlock` 取块、找不到锚点就抛、不用 assumeTrue 跳过。
 *
 * 账是 T68 自己带来的：那一卡把「日视图这一帧画哪一天」收成全页唯一一份真相
 * `browseDateOnScreen` 并喂给四个消费方，其中一个是顶栏第二行
 * `topBarDateLabel(..., dateOnScreen = browseDateOnScreen)`。但 `dateLabel` 只活在
 * `Crossfade(targetState = selectedTab == 0)` 的 `isWeekTab` 那一支 —— 人在周课表页签时
 * 日视图根本没画出来，第二行却报日视图翻到的那一天。真机装机实测（f128bc02）：
 * 网格里高亮的今天是 9/22 周二，第二行写「9月24日 星期四」。
 * 变量名里的 "OnScreen" 在这一档是假的。
 */
class DayViewOnScreenWiringGuardTest {

    // ---- ① 复现：这一行读的日子必须经过「日视图在不在屏上」这道闸 ----

    /**
     * 缺陷本体。改前 `topBarDateLabel` 的实参只有
     * `semesterStart, state.currentWeek, browseWeek, today, browseDateOnScreen` ——
     * 五个入参里没有任何一维讲得出「日视图此刻画没画在屏上」，
     * 于是同一份 `browseDateOnScreen` 在周页签上也被端给第二行。
     *
     * 这一维不许由调用点就地 `if (selectedTab == 1 || isWide)` 现搭：判据本体在
     * `dayViewOnScreen`（页签 / 是否宽屏并排 / 首帧页签定没定），设备侧事实当参数传进去。
     */
    @Test
    fun secondLineDayIsGatedByTheOnScreenKernel() {
        val home = blankComments(source("com/buaa/schedule/ui/home/HomeScreen.kt"))
        val call = Regex("topBarDateLabel\\(([^)]*)\\)").find(home)
            ?: throw AssertionError("HomeScreen 不再调用 topBarDateLabel：顶栏第二行的算式脱钩了")
        val args = call.groupValues[1]
        assertTrue(
            "顶栏第二行只吃 browseDateOnScreen，没有任何一维说明「日视图此刻在不在屏上」。\n" +
                "窄屏周页签上日视图根本没渲染，而 dateLabel 只活在 Crossfade 的 isWeekTab 那一支" +
                "（见本文件 secondLineOnlyRenderedInTheWeekBranch），于是那一行报的是屏上没有的日子：\n" +
                "真机 f128bc02 量到「网格高亮今天 = 9/22 周二」而第二行写「9月24日 星期四」。\n" +
                "现在这五个实参是：$args",
            Regex("dayViewOnScreen\\(|dayViewIsOnScreen|dayViewDrawn").containsMatchIn(args),
        )
    }

    // ---- ② 前提：第二行只活在周页签那一支（本卡判据成立的那件事） ----

    /**
     * 上面那条断言的前提，单独钉一枚：`dateLabel` 的渲染点只有 `isWeekTab` 那一支，
     * 今日页签那一支画的是 `dayTabHeadline`（一行，没有第二行）。
     *
     * 这一档改前改后都该是绿的——它是"周页签上读 `browseDateOnScreen` 就是读一个不在
     * 屏上的日子"这句话的全部依据。哪天有人把第二行也搬进今日那一支，这条要红，
     * 那时该重问的是「日视图在不在屏上」这道闸还成不成立，而不是把闸拆了。
     */
    @Test
    fun secondLineOnlyRenderedInTheWeekBranch() {
        val home = blankComments(source("com/buaa/schedule/ui/home/HomeScreen.kt"))
        val branch = balancedBlock(home, "targetState = selectedTab == 0")
        val weekHalf = branch.substringBefore("} else {")
        val dayHalf = branch.substringAfter("} else {")
        assertTrue(
            "顶栏第二行（dateLabel）已经不在 isWeekTab 那一支里了，本卡的判据前提要重核：\n$weekHalf",
            weekHalf.contains("text = dateLabel,"),
        )
        assertFalse(
            "今日页签那一支冒出第二行日期就是重复摆日期（日视图页头紧挨着下面就写着那一天）：\n$dayHalf",
            dayHalf.contains("dateLabel"),
        )
        assertTrue(
            "今日页签那一行必须仍走 dayTabHeadline（T68 那一档，本卡不许动它）：\n$dayHalf",
            dayHalf.contains("text = dayTabHeadline("),
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
        throw IllegalStateException("$signature 的括号没配平")
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
