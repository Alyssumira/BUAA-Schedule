package com.buaa.schedule.ui.home

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T70 的结构守卫——本模块没有 Compose 运行时（无 Robolectric、无 ui-test），
 * "滑动这条路径上到底还剩几次分配、几次快照写"只能走纯 JVM 源码扫描，
 * 刀法与 [DayBrowseWiringGuardTest] 一致：先抹注释（字符串保留），找不到锚点就抛，
 * **不用 assumeTrue 跳过**。
 *
 * 钉的是机制不是文案。用户报的是效果（「左右滑动动画有点卡顿」），
 * 而效果落在这几条链路上，断掉任何一条都会让 [DaySwipePolicy] 变成死代码：
 * 1. 判据内核零 android import、零时钟读取（px、阈值、reduce-motion 全由调用点传）。
 * 2. 手势识别器不许再按 `date` 重建——那正是"连手快滑两下掉一下"的机制。
 * 3. 事件回调里只剩普通字段写：一次 `launch`、一次 `snapTo`、一次 State 写都不许留。
 * 4. 位移每帧最多写一次，且写者只有那颗帧回调协程。
 * 5. 松手收回位移必须按 [dayDragSettleMode] 分两档，翻出去那一侧不许再挂长弹簧。
 * 6. 反向钉 T68：翻页仍然只上报 `onDateChange`，DayView 不持日期、不读时钟。
 */
class DaySwipeWiringGuardTest {

    private val dayViewPath = "com/buaa/schedule/ui/home/DayView.kt"
    private val dayView get() = blankComments(source(dayViewPath))

    // ---- ① 内核纯度 ----

    @Test
    fun swipeKernelReadsNoAndroidAndNoClock() {
        val text = blankComments(source("com/buaa/schedule/ui/home/DaySwipePolicy.kt"))
        val offenders = text.lines().map { it.trimStart() }
            .filter { it.startsWith("import ") }
        assertTrue(
            "DaySwipePolicy.kt 连一条 import 都不该有：px 与阈值由调用点折好传进来，" +
                "内核一旦自己 import LocalDensity / Build，'内核返回布尔、调用点各判一次 density' 就回来了：\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
        val clock = text.lines().map { it.trim() }
            .filter {
                it.contains("LocalDate.now(") || it.contains("LocalTime.now(") ||
                    it.contains("System.currentTimeMillis") || it.contains("Clock.")
            }
        assertTrue("内核里出现了读时钟的写法：\n" + clock.joinToString("\n"), clock.isEmpty())
        // 设备事实必须是参数而不是常量：72dp 这一档留在调用点
        assertFalse("内核不许自己写死 dp/density：", text.contains(".dp"))
        assertFalse("内核不许自己 toPx()：", text.contains("toPx("))
    }

    // ---- ② 手势识别器不再随 date 重建 ----

    @Test
    fun dragRecognizerIsNotRebuiltOnEveryPagedDay() {
        val at = dayView.indexOf(".pointerInput(")
        check(at >= 0) { "DayView 里找不到 .pointerInput(：横滑翻日被整个删掉了？" }
        val key = Regex("\\.pointerInput\\(([^)]*)\\)").find(dayView)?.groupValues?.get(1)?.trim()
        assertEquals(
            "手势识别器的键必须是 Unit：按 date 键每翻一天就把 detectHorizontalDragGestures " +
                "拆掉重装，正在飞的那一次拖拽直接被取消（连手快滑两下会掉一下）",
            "Unit",
            key,
        )
        assertFalse("识别器键里又出现了 date：", key!!.contains("date"))
        // 拖拽回调读的是 rememberUpdatedState 那两份，不是被捕获的旧值
        val block = dayView.substring(at, at + 1600)
        assertTrue("onDragEnd 不再读 latestDate：翻页会停在第一次捕获的那一天\n$block", block.contains("latestDate"))
        assertTrue("onDragEnd 不再走 latestOnDateChange：\n$block", block.contains("latestOnDateChange("))
        val updated = Regex("val latest(Date|OnDateChange) by rememberUpdatedState\\(")
            .findAll(dayView).count()
        assertEquals("date 与 onDateChange 各要一枚 rememberUpdatedState：", 2, updated)
    }

    // ---- ③ 事件路径上不再起协程、不再写 State ----

    @Test
    fun pointerEventPathOnlyAccumulatesPlainFields() {
        val handler = balancedBlock(dayView, ") { _, dragAmount ->")
        assertEquals(
            "事件回调里只许剩两次普通字段写（累计位移 + 事件序号），多一次都是每事件分配：\n$handler",
            2,
            Regex("drag\\.(totalPx|events)\\s*[+-]?=").findAll(handler).count(),
        )
        for (banned in listOf("launch", "snapTo", "animateTo", "withFrameNanos")) {
            assertFalse("事件回调里又出现 $banned —— 每事件一次协程/快照写就是 T70② 要治的那件事：\n$handler",
                handler.contains(banned))
        }
        assertFalse("事件回调直接写 State（swipeDrag 那一类）：\n$handler", handler.contains("swipeDrag"))
        // 累加值必须是普通字段，不是 State
        val holder = balancedBlock(dayView, "private class DayDragAccumulator")
        assertTrue("DayDragAccumulator 的两个成员必须是普通字段：\n$holder", holder.contains("var totalPx: Float"))
        assertTrue(holder.contains("var events: Long"))
        assertFalse("累加值不许做成 State：\n$holder", holder.contains("mutableFloatStateOf"))
    }

    // ---- ④ 位移每帧最多写一次，且写者唯一 ----

    @Test
    fun dragOffsetHasExactlyOneFrameCoalescedWriter() {
        val writer = balancedBlock(dayView, "LaunchedEffect(dragActive, reduceMotion)")
        assertTrue("帧回调协程不在了：位移又回到每事件一次写\n$writer", writer.contains("withFrameNanos"))
        assertTrue("写位移前不再问过'这一帧有没有新事件'：\n$writer",
            writer.contains("dayDragShouldWriteOffset("))
        assertEquals("整颗 effect 里 snapTo 只许一处（多一处就是第二个写者）：\n$writer",
            1, Regex("dragShift\\.snapTo\\(").findAll(writer).count())
        assertTrue("落地的位移必须出自内核，不许在调用点重算一遍阻尼：\n$writer",
            writer.contains("dayDragFollowOffset(drag.totalPx, swipeThresholdPx, reduceMotion)"))
        assertTrue("reduce-motion 开关必须由内核判，调用点不许自己写 !reduceMotion：\n$writer",
            writer.contains("dayDragShouldFollow(reduceMotion)"))
        // 全文件只有这一处 snapTo
        assertEquals("DayView 里 dragShift.snapTo 只许出现在帧回调里：",
            1, Regex("dragShift\\.snapTo\\(").findAll(dayView).count())
    }

    // ---- ⑤ 松手收回位移分两档 ----

    @Test
    fun settleSpecComesFromTheKernelAndTheCommittedSideDropsTheSpring() {
        val settle = balancedBlock(dayView, "val settleDrag: (DaySwipeCommit) -> Unit")
        assertTrue("收回路径不再问内核：\n$settle", settle.contains("dayDragSettleMode(commit)"))
        assertTrue("没翻出去那一侧仍要弹簧（这一档手感不许变）：\n$settle",
            settle.contains("motionSpringFor<Float>(reduceMotion)"))
        assertTrue("翻出去那一侧必须改挂与转场同起同落的那一档，否则整页正文被一根" +
            "没有明确长度的弹簧拖着多画几百毫秒：\n$settle",
            settle.contains("motionSpecFor<Float>(reduceMotion, MotionTokens.DURATION_SNAP)"))
        // 两个调用点都要把这一滑的结论递进去
        val dragEnd = balancedBlock(dayView, "onDragEnd = {")
        assertTrue("onDragEnd 不再把 commit 递给 settleDrag：\n$dragEnd",
            dragEnd.contains("settleDrag(commit)"))
        assertTrue("onDragEnd 的翻页结论必须由内核算：\n$dragEnd",
            dragEnd.contains("daySwipeCommit(drag.totalPx, swipeThresholdPx)"))
        val cancel = balancedBlock(dayView, "onDragCancel = {")
        assertTrue("取消那一支没有位移可收，必须走弹回那一档：\n$cancel",
            cancel.contains("settleDrag(DaySwipeCommit.Stay)"))
    }

    // ---- ⑥ 五枚判据都有人调用（防静默 no-op） ----

    @Test
    fun everyKernelJudgementIsCalledFromTheView() {
        for (fn in listOf(
            "daySwipeCommit", "dayDragFollowOffset", "dayDragSettleMode",
            "dayDragShouldFollow", "dayDragShouldWriteOffset",
        )) {
            val hits = Regex("$fn\\(").findAll(dayView).count()
            assertTrue("$fn 在 DayView 里一次都没被调用——判据白长在内核文件里（本仓踩过的静默 no-op）", hits > 0)
        }
        // 阈值只有一处真相：72dp 只在调用点折一次 px
        assertEquals("72.dp.toPx() 只许出现在 DayView 的调用点：",
            1, Regex("72\\.dp\\.toPx\\(").findAll(dayView).count())
    }

    // ---- ⑦ 反向钉：T68 与"不归本卡管"的那几档一个字都不许漂 ----

    @Test
    fun t68OwnershipAndNeighbouringCardsSurvive() {
        // 翻页仍然只上报，DayView 不持日期状态、不读时钟
        assertFalse("DayView 又自己读 LocalDate.now() 了（T68 的守卫也钉这一条）：",
            dayView.contains("LocalDate.now("))
        assertFalse("DayView 不许自己存「翻到哪一天」：", dayView.contains("var browseDate"))
        assertTrue("「回到今天」那一行的形状不许漂：",
            Regex("if \\(date != today\\) \\{\\s*TextButton\\(onClick = \\{ onDateChange\\(today\\) \\}")
                .containsMatchIn(dayView))
        // 共享轴转场不许被"顺手"删掉（模式一律用 \s+：源文件是 CRLF，写死 \n 会假红）
        assertTrue("AnimatedContent 仍按 date 转场（本卡拆的是滑动路径，不是共享轴）：",
            Regex("AnimatedContent\\(\\s+targetState = date,").containsMatchIn(dayView))
        assertTrue("转场规格仍出自 dayAxisTransition：",
            dayView.contains("dayAxisTransition(reduceMotion, forward = targetState.isAfter(initialState))"))
        // 页头那一格：只淡入不横移的理由（左右各 48dp 箭头）不许被换机制时丢掉
        val headerLayer = Regex(
            "Column\\(\\s+modifier = Modifier\\s+\\.weight\\(1f\\)" +
                "\\s+\\.graphicsLayer \\{ translationX = dragShift\\.value \\},"
        )
        assertTrue("页头那一列仍挂 dragShift 位移——T62 那一档「页头只淡入不横移」靠的是它左右各 48dp 箭头，" +
            "换机制时不许把它卷进整页位移里", headerLayer.containsMatchIn(dayView))
        assertEquals("dragShift.value 只许被页头与正文两处 graphicsLayer 读：",
            2, Regex("translationX = dragShift\\.value").findAll(dayView).count())
        // 里层那枚 AnimatedContent（列表/时间轴）不许被卷进来
        assertEquals("时间轴/列表那一档的 AnimatedContent 仍是一枚：",
            1, Regex("targetState = timelineMode").findAll(dayView).count())
        //  HorizontalPager 不许被偷偷引进日视图
        assertFalse("日视图被换成了 HorizontalPager——本卡的结论恰恰是这台镜像上 Pager 每帧贵 6 倍，" +
            "要换也得另立一卡重新量：", dayView.contains("HorizontalPager"))
    }

    // ---- ⑧ 转场期不再裸算全量过滤 ----

    @Test
    fun dayCourseFilterIsRememberedAcrossTheTransition() {
        val at = dayView.indexOf("val dayCourses = ")
        check(at >= 0) { "找不到 val dayCourses = ：那一处过滤挪过家了，这条守卫要跟着改" }
        val head = dayView.substring(at, at + 60)
        assertTrue("dayCourses 又回到组合期裸算了——AnimatedContent 转场那 140ms 里新旧两页" +
            "每重组一次就把整张课表过滤+排序一遍：\n$head", head.contains("remember(date, courses, semester"))
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
