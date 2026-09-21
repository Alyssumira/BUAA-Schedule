package com.buaa.schedule.core.designsystem

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 运行时降档链的接线守卫（T53）。
 *
 * 起因是 T52 基线的实测：debug 包同一进程 logcat 里 GlassDiag 1197 行、GlassJank **0 行**，
 * `ps -T` 里没有 FrameMetrics 线程——「持续掉帧就把玻璃降一档」这道闸门整条是死的，
 * 而吞掉注册异常的 runCatching 让它死得无声无息。这里用结构钉把三处复发路径封死：
 * 1. MainActivity 的 GlassJankMonitor.attach 调用点不许再回到 setContent 之前
 *    （机制取证见 GlassJankMonitor.attach 注释与本测试类所在提交的消息：平台链
 *    View.addFrameMetricsListener → FrameMetricsObserver → HardwareRendererObserver
 *    对 null Handler 抛 NPE，android-37.0 sources 里 HardwareRendererObserver.java:69；
 *    android-36 的 android.jar 只是 `throw new RuntimeException("Stub!")` 的桩，
 *    `javap -c` 出来即自证——所以实现证据只能钉在 37.0 sources 这条线上）。
 * 2. 纯判据内核零 android/androidx import，SDK 分支不许回到调用点
 *    （「返回布尔后调用点各判一次 Build.VERSION.SDK_INT」就是这条守卫要拦的写法）。
 * 3. 监听注册必须延到 decor 附加之后，且失败要留痕——吞异常的 runCatching 不许复活。
 *
 * 刀法与 DayTimelineStructureGuardTest / CourseTitleRowBudgetGuardTest 一致：
 * 先抹注释（字符串保留），再按花括号配对切函数体；找不到源码目录直接抛，
 * 不用 assumeTrue 跳过（找错路径只表现为"永远是绿的"，比红更糟）。
 */
class GlassJankMonitorWiringGuardTest {

    /** 钉死 T53 的主案发现场：attach 调用点一旦搬回 setContent 之前，这条先红 */
    @Test
    fun jankMonitorAttachesOnlyAfterSetContent() {
        val main = blankComments(source("com/buaa/schedule/MainActivity.kt"))
        val onCreateAt = main.indexOf("override fun onCreate(")
        assertTrue("MainActivity 里找不到 onCreate：靶子没了", onCreateAt >= 0)
        val bodyOpen = main.indexOf('{', onCreateAt)
        val bodyEnd = matchingClose(main, bodyOpen)
            ?: throw AssertionError("onCreate 的花括号配不上对，解析器该修了")
        val body = main.substring(bodyOpen, bodyEnd)

        val setContentAt = body.indexOf("setContent")
        val attachAt = body.indexOf("GlassJankMonitor.attach(")
        // 零命中即失败：静默缺席（改名/删调用）与顺序倒退是同一类回归
        assertTrue("onCreate 里找不到 setContent{…}", setContentAt >= 0)
        assertTrue(
            "onCreate 里找不到 GlassJankMonitor.attach——采样链的宿主登记丢了，" +
                "整条运行时降档会退回 T52 实测的 0 日志死链",
            attachAt >= 0,
        )
        assertTrue(
            "GlassJankMonitor.attach 又跑回 setContent 之前了：那一刻内容视图还不存在，" +
                "注册的正是 T52 实测从未收到过一帧回调的那个死挂接点",
            attachAt > setContentAt,
        )
    }

    /** 收单硬判据：纯判据内核零 android 依赖，设备事实全走参数 */
    @Test
    fun jankDecisionKernelStaysAndroidFree() {
        val kernel = source("com/buaa/schedule/core/designsystem/GlassJankDecision.kt")
        val offenders = kernel.lines().map { it.trimStart() }
            .filter { it.startsWith("import android") || it.startsWith("import androidx") }
        assertTrue(
            "GlassJankDecision.kt 是要在 JVM 单测里逐边界打表的判据内核，混进设备依赖就没法测" +
                "（帧数/窗口时长/冷却时间戳/内存核数必须当参数传进来）：\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
        assertTrue(
            "SDK 分支属于判据输入（debug 参数），不许在算式里长回来",
            run {
                // 只判代码：类注释里那句「不许再判 Build.VERSION.SDK_INT」本身带这个词
                val kernelCode = blankComments(kernel)
                !kernelCode.contains("SDK_INT") && !kernelCode.contains("BuildConfig")
            },
        )
        // 判据不许在监听器里就地重算一遍——调用点只许分发内核返回的动作
        val monitor = blankComments(source("com/buaa/schedule/core/designsystem/GlassJankMonitor.kt"))
        assertTrue("监听器必须经 glassJankWindowAction 决策", monitor.contains("glassJankWindowAction("))
        assertTrue(
            "GlassJankMonitor 里出现 SDK_INT = 判据又开始在调用点各判各的版本号",
            !monitor.contains("SDK_INT"),
        )
    }

    /** 注册延到 decor 附加之后；吞异常的 runCatching 与 null Handler 都不许复活 */
    @Test
    fun frameMetricsRegistrationIsDeferredAndFailsLoudly() {
        val monitor = blankComments(source("com/buaa/schedule/core/designsystem/GlassJankMonitor.kt"))
        assertTrue(
            "注册不许再发生在 attach() 的调用栈里：必须对 window.decorView post，" +
                "延到窗口附加之后再挂（T52 死链的第一半根因）",
            monitor.contains("window.decorView.post"),
        )
        assertTrue(
            "FrameMetrics 注册不能再把 null 当 Handler 递出去：平台的 null 兜底线程已删，" +
                "NPE 会被 runCatching 吞成死链（T52 死链的第二半根因）",
            !Regex("addOnFrameMetricsAvailableListener\\([^)]*,\\s*null\\s*\\)").containsMatchIn(monitor),
        )
        assertTrue(
            "注册失败必须走 Log.e 留痕，release 也要看得见——这道闸门是运行时自保护的自保护",
            monitor.contains("Log.e("),
        )
        // release 自杀逻辑保持可用：压到 OFF 后停采样，attach 顶端也要还认这个标志
        assertTrue(
            "releaseDeactivated 的停采样链路要原样在场",
            monitor.contains("releaseDeactivated = true") &&
                Regex("fun attach\\([\\s\\S]*releaseDeactivated").containsMatchIn(monitor),
        )
    }

    // ---- 靶子定位与词法小工具（与同族守卫测试同一把刀） ----------------------------

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

    /** 从 [open] 处的括号走到配平的那个闭合括号 */
    private fun matchingClose(code: String, open: Int): Int? {
        if (open < 0 || code[open] !in "([{") return null
        var depth = 0
        for (i in open until code.length) {
            when (code[i]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return null
    }
}
