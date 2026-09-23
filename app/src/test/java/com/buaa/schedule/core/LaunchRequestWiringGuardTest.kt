package com.buaa.schedule.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「冷启动重放一次性启动请求」这条链路的接线守卫（T71，台账 #112）。
 *
 * 本模块没有 Compose 运行时（无 Robolectric、无 ui-test），`MainActivity.onCreate` 里那三行
 * 到底认不认 Intent 上的 extra 只能扫源码，刀法照抄 [com.buaa.schedule.ui.home.StatsEntryWiringGuardTest]
 * 与 [com.buaa.schedule.ui.home.DayBrowseWiringGuardTest]：读源文件文本、匹配前先 `blankComments`
 * 抹注释（本卡的 KDoc 里就写着「不许再出现 courseIdFrom」这类禁令本身，连注释一起扫会红在自己人手上）、
 * 函数体按花括号配平取、找不到锚点就抛、不用 assumeTrue 跳过。
 *
 * 为什么这一族账值得单独钉：病根是**同一个判据在两个入口上说了两句话** ——
 * `onCreate` 无条件赋值、`onNewIntent` 用 `?.let`（无请求就不动）。基点上只有后者是对的，
 * 于是任务栈根 intent 上那枚陈旧的组件 extra 每次冷启动都被重放一遍（用户读作"我只点了图标，
 * 它自己跳到周三"）。本卡把判据收进 [launchRequestsOf] 之后，这条链路上任何一处**退回**无条件赋值、
 * 退回就地解析 Intent、或把内核的缺省哨兵与生产方改掉，都会让某一档重新亮红：
 *
 * 1. 内核纯度：[LaunchRequestPolicy] 零 android import、零时钟读取，且**不吃 `Intent`/`Bundle`** ——
 *    只吃抽好的原始值，否则 JVM 单测跑不动、判据也退回"就地读设备对象"。
 * 2. `onCreate`：三行赋值全吃同一个内核结论，且交给内核的那个布尔读的是 `savedInstanceState`；
 *    旧的 `courseIdFrom` / `routeFrom` / `dayOfWeekFrom` 三份就地判据不许回来。
 * 3. `onNewIntent`：三行仍必须是 `?.let`（无请求不动），且明写 `hasSavedState = false` ——
 *    进程被杀后再点组件格子时，那一跳**只**从这里进来（装机实测 `onNewIntent day=1`）。
 * 4. 解析 Intent 只许一处：全文件三枚 extra 的读取点必须全在 `launchRequestsFrom` 体内
 *    —— 这一档钉的正是"这条重放链路上没有第二处无条件赋值"。
 * 5. 生产端与内核同源：组件模板的缺省值（星期几 0 / 课程 id -1L）与格子写的 `position + 1`
 *    必须还对着内核里那三枚常数，漂一位就把"普通启动"认成请求（或反过来把请求认成缺省）。
 * 6. 消费端的一次性链还在：三颗状态各有一处 `= null` 的取走回调 —— 内核"重建那一档不认"
 *    靠的就是"上一次生命周期里它已经被取走过"。
 */
class LaunchRequestWiringGuardTest {

    // ---- ① 内核纯度 ----

    @Test
    fun kernelReadsNoAndroidNoClockAndNoIntent() {
        val text = blankComments(source(KERNEL))
        val offenders = text.lines().map { it.trimStart() }
            .filter { it.startsWith("import android") || it.startsWith("import androidx") }
        assertTrue(
            "LaunchRequestPolicy.kt 是要在 JVM 单测里跑裸判据的内核，混进设备依赖就没法测" +
                "（三枚原始值与那个布尔全得当参数传进来）：\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
        // 时钟：内核自己读一次「现在」，判据就与调用点的生命周期脱钩（T41/T43 同一类坑）
        val clock = text.lines().map { it.trim() }.filter {
            it.contains("System.currentTimeMillis") || it.contains("Calendar") ||
                it.contains("LocalDate.now(") || it.contains("LocalDateTime") || it.contains("Clock.")
        }
        assertTrue("内核里出现了读时钟的写法：\n" + clock.joinToString("\n"), clock.isEmpty())
        // 判据只吃抽好的值：递 Intent 或整份 savedInstanceState 进来就是"就地读设备对象"的另一种写法
        val bypass = text.lines().map { it.trim() }.filter {
            it.contains("Intent") || it.contains("Bundle") || it.contains("savedInstanceState")
        }
        assertTrue(
            "内核不许接 Intent / Bundle / savedInstanceState 本身，只接调用点抽出来的原始值" +
                "（否则 JVM 侧根本没法表驱动，判据也会退回按设备对象各判一次）：\n" + bypass.joinToString("\n"),
            bypass.isEmpty(),
        )
    }

    // ---- ② onCreate：三行全吃内核结论，那个布尔读 savedInstanceState ----

    @Test
    fun onCreateFeedsAllThreeStatesFromTheKernel() {
        val body = balancedBlock(activity(), "override fun onCreate(")
        for ((state, field) in listOf(
            "requestedCourseId" to "courseId",
            "requestedRoute" to "route",
            "requestedDayOfWeek" to "dayOfWeek",
        )) {
            val lines = body.lines().filter { it.trim().startsWith("$state.value =") }
            assertEquals(
                "$state 在 onCreate 里的赋值处数应当恰好一处（多一处就是这条链路上又冒出一处无条件赋值）：\n" +
                    body.lines().filter { it.contains("$state.value") }.joinToString("\n"),
                1,
                lines.size,
            )
            assertTrue(
                "$state 在 onCreate 里不再吃内核结论（$field）——无条件重放启动 Intent 上那枚陈旧 extra " +
                    "就是台账 #112 的病本体：\n" + lines.joinToString("\n"),
                lines.single().contains("= launch.$field"),
            )
        }
        assertTrue(
            "onCreate 交给内核的那个布尔必须读 savedInstanceState：重建那一档（转屏、进程被杀后从图标进来）" +
                "就是靠它区分出来的（装机实测 saved=true 的两档都是重放）",
            Regex("""launchRequestsFrom\([^)]*savedInstanceState""").containsMatchIn(body),
        )
        val activity = activity()
        for (gone in listOf("courseIdFrom", "routeFrom", "dayOfWeekFrom")) {
            assertFalse(
                "$gone() 那三份就地判据又回来了：两个入口各写一份取值规则，就是本卡要收掉的那处自相矛盾",
                Regex("""fun $gone\(""").containsMatchIn(activity),
            )
        }
    }

    // ---- ③ onNewIntent：无请求不动，且明写这是刚按出来的 ----

    @Test
    fun onNewIntentStillIgnoresAbsentRequests() {
        val body = balancedBlock(activity(), "override fun onNewIntent(")
        for ((state, field) in listOf(
            "requestedCourseId" to "courseId",
            "requestedRoute" to "route",
            "requestedDayOfWeek" to "dayOfWeek",
        )) {
            assertEquals(
                "$state 在 onNewIntent 里只许被「有请求才动」那一行写（无条件赋值会把没带请求的 intent 认成清空请求）：\n" +
                    body.lines().filter { it.contains("$state.value") }.joinToString("\n"),
                1,
                body.lines().count { it.contains("$state.value =") },
            )
            assertTrue(
                "$state 这一行不再是 ?.let 形状（基点 :227-229 的写法是对的，本卡要保住它）：" +
                    "桌面图标那条 intent 上三枚请求全是 null，无条件赋值等于每次切回前台都清一遍",
                Regex("""fresh\.$field\?\.let \{ $state\.value = it \}""").containsMatchIn(body),
            )
        }
        assertTrue(
            "onNewIntent 必须明写 hasSavedState = false：新送来的 intent 就是用户刚刚按的那一次。" +
                "进程被杀后再点组件格子时，那一跳只能从这里进来（装机实测 onCreate 拿到的是陈旧的 day=5、" +
                "onNewIntent 才是新的 day=1）",
            Regex("""launchRequestsFrom\(intent, hasSavedState = false\)""").containsMatchIn(body),
        )
    }

    // ---- ④ 解析 Intent 只许一处 ----

    @Test
    fun intentIsParsedExactlyOnce() {
        val activity = activity()
        val reads = Regex("""\bget(?:Long|Int|String)Extra\(""").findAll(activity).toList()
        assertEquals(
            "MainActivity 里读 extras 的写法应当恰好三处（三枚请求各一处），且全在同一个抽参函数体内：" +
                "\n" + reads.joinToString("\n") { it.value },
            3,
            reads.size,
        )
        val body = balancedBlock(activity, "private fun launchRequestsFrom(")
        for (read in reads) {
            // 配平取体：三处读取必须都落在这个函数体内，别处再读一次就是绕过内核
            assertTrue(
                "有一处 extras 读取不在 launchRequestsFrom 体内（绕过内核就地判 = 第二处无条件赋值）",
                body.contains(read.value),
            )
        }
        assertTrue(
            "launchRequestsFrom 没有把 hasSavedState 转给内核（在调用点写死一个值，就是绕开判据的第二处赋值）",
            body.contains("hasSavedState = hasSavedState"),
        )
    }

    // ---- ⑤ 生产端与内核同源 ----

    /**
     * 组件模板的缺省值与格子写进去的值，必须还对着内核里那三枚常数。
     *
     * 漂法的两种各拦一次：模板缺省从 0 改成 1（普通启动第一次点击就被认成「跳周一」）、
     * 内核的取值域从 1..7 改成 0..7（缺省 0 变成合法请求）。两处都是编译器不会响的改动。
     */
    @Test
    fun widgetProducersStillMatchTheKernelSentinels() {
        val kernel = blankComments(source(KERNEL))
        assertTrue("内核的星期几缺省哨兵不再是 0：$NO_DAY_OF_WEEK", kernel.contains("NO_DAY_OF_WEEK = 0"))
        assertTrue("内核的课程 id 缺省哨兵不再是 -1L", kernel.contains("NO_COURSE_ID = -1L"))
        assertTrue("内核的星期几取值域不再是 1..7", kernel.contains("DAY_OF_WEEK_RANGE = 1..7"))
        val common = blankComments(source(WIDGET_COMMON))
        assertTrue(
            "4×2 网格的模板 intent 不再预置星期几 0（这一枚必须与内核的 NO_DAY_OF_WEEK 同数：宿主合并 " +
                "fillInIntent 之前的第一次点击直接吃模板）：\n" +
                common.lines().filter { it.contains("EXTRA_DAY_OF_WEEK") }.joinToString("\n"),
            common.contains("putExtra(WidgetNavigation.EXTRA_DAY_OF_WEEK, 0)"),
        )
        assertTrue(
            "组件列表的模板 intent 不再预置课程 id -1L（同上，与 NO_COURSE_ID 同源）",
            common.contains("putExtra(CourseListFactory.EXTRA_COURSE_ID, -1L)"),
        )
        val grid = blankComments(source(GRID_SERVICE))
        assertTrue(
            "格子点击不再写 position + 1（那一枚就是星期几请求的本体，越界值会被内核按「无请求」丢掉）：\n" +
                grid.lines().filter { it.contains("EXTRA_DAY_OF_WEEK") }.joinToString("\n"),
            grid.contains("putExtra(WidgetNavigation.EXTRA_DAY_OF_WEEK, position + 1)"),
        )
    }

    /** 通知链路那一枚路由 extra：生产方还在发，且发的必须是白名单里的字面量 */
    @Test
    fun notificationStillSendsTheRoutableExtras() {
        val notifications = blankComments(source(REMINDER_NOTIFICATIONS))
        assertTrue("通知侧不再写 EXTRA_ROUTE（routeFrom 那一档就成了死判据）", notifications.contains("putExtra(MainActivity.EXTRA_ROUTE, it)"))
        assertTrue("通知侧不再写 EXTRA_COURSE_ID", notifications.contains("putExtra(MainActivity.EXTRA_COURSE_ID, courseId)"))
        val activity = activity()
        val routable = Regex("""ROUTABLE_FROM_INTENT\s*=\s*setOf\(([^)]*)\)""").find(activity)
            ?: throw AssertionError("找不到 ROUTABLE_FROM_INTENT：白名单换写法了，本守卫要跟着改")
        for (route in listOf("spoc_scan", "spoc_login")) {
            assertTrue("白名单少了 $route（通知按钮点了会被内核拦回首页）：${routable.groupValues[1]}", routable.groupValues[1].contains("\"$route\""))
        }
    }

    // ---- ⑥ 消费端的一次性链还在 ----

    /**
     * 三颗状态各要有一处「取走即清空」的回调。
     *
     * 内核"重建那一档不认"的前提是**上一次生命周期里这枚请求已经被消费方取走**
     * （`onDayRequestConsumed` / `onRouteRequestConsumed` / `onCourseRequestConsumed`）。
     * 那颗回调一旦漂没，请求就会在状态里常驻，本卡的判据也就失去了依据。
     */
    @Test
    fun everyRequestStateHasAConsumePath() {
        val body = balancedBlock(activity(), "setContent {")
        for (state in listOf("requestedCourseId", "requestedRoute", "requestedDayOfWeek")) {
            assertEquals(
                "$state 的「取走即清空」处数应当恰好一处：\n" +
                    body.lines().filter { it.contains("$state.value = null") }.joinToString("\n"),
                1,
                body.lines().count { it.trim().contains("$state.value = null") },
            )
        }
    }

    // ---- 源码核对工具（与各 *WiringGuardTest 同一套刀法）----

    private fun activity(): String = blankComments(source(MAIN_ACTIVITY))

    private fun source(relative: String): String {
        val file = File(findMainJavaDir(), relative)
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

    /** 把 `//` 与 `/* */` 注释抹成空格（字符串保留、长度与换行位置不变）：钉的是接线，不是白话 */
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

    private companion object {
        const val MAIN_ACTIVITY = "com/buaa/schedule/MainActivity.kt"
        const val KERNEL = "com/buaa/schedule/core/LaunchRequestPolicy.kt"
        const val WIDGET_COMMON = "com/buaa/schedule/widget/WidgetCommon.kt"
        const val GRID_SERVICE = "com/buaa/schedule/widget/WeekGridWidgetService.kt"
        const val REMINDER_NOTIFICATIONS = "com/buaa/schedule/reminder/ReminderNotifications.kt"
    }
}
