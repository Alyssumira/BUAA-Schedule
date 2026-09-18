package com.buaa.schedule.reminder

import com.buaa.schedule.domain.model.Course
import com.buaa.schedule.domain.model.TimeSlot
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「本轮挑不出下一条课前提醒」时，课堂侧那一揽子撤销（撤上/下课铃 + 停服务 + 收常驻通知 +
 * **恢复勿扰** + 取消看门狗）到底该不该当场执行。
 *
 * 实测到的故障（emulator-5554、master ed70a1d，设备真有一节在上的课、`dnd_during_class=true`、
 * 课前提醒全关）：每一次重排都走 [ReminderScheduler.rescheduleAll] 的 `plan == null` 分支，
 * 那里无条件 `ClassProgressScheduler.cancelAll` ——
 * `20:06:05.450 ZenModeController →0`（正在上课却被强制恢复勿扰），
 * 约 5 秒后 `20:06:10.610 →2`（紧随其后的续排链排出一个时刻已过期的上课铃，ACTION_START 再进一次），
 * 冷启动那条链同理（`20:06:28.942 →0`）。
 * 抖一下只是看得见的部分，真正致命的是中间那道撤销把**自愈凭据**
 * （`dnd_saved_interruption_filter` 记录 + 勿扰看门狗闹钟）一起抹掉：
 * [ClassProgressDnd.selfCheck] 判的是"有残留记录才动手"，而那次重新进入全靠一个已过期闹钟 ——
 * 被 ROM 吞掉就永久没进，谁都救不回来。
 *
 * 带判据的那份清理只有 [ClassProgressScheduler.rescheduleWindows] 一处实现（硬约束：
 * 那里不许再加新分支），所以改动落在 [ReminderScheduler] 这一侧，判据收进
 * [ReminderScheduler.shouldTakeDownClassProgress]；撤销动作在 JVM 侧只能以注入 lambda 的
 * **调用次数**呈现（本模块单测没有 Robolectric：`cancelAll` 要 Context，
 * android.jar 里全是抛 "not mocked" 的桩）。形状抄
 * [com.buaa.schedule.widget.BackgroundSync.runColdStartWidgetSteps]。
 *
 * 生产接线（撤销到底是不是经由这个 seam 走的）JVM 跑不到，所以另按源码核对，
 * 写法与 `ReminderRescheduleRoundTest` / `ColdStartWidgetStepsTest` 一致：
 * 找不到目录或文件就直接抛，不用 assumeTrue 跳过。
 */
class ClassProgressCleanupDecisionTest {

    private val semesterStart = LocalDate.of(2026, 9, 7) // 周一
    private val slots = listOf(
        TimeSlot(number = 1, startTime = "08:00", endTime = "08:45"),
        TimeSlot(number = 2, startTime = "08:50", endTime = "09:35"),
    )

    /** 周一（第 1 周）08:00-09:35 的一节课 */
    private fun course(id: Long = 1L, dayOfWeek: Int = 1) = Course(
        id = id,
        name = "算法竞赛训练",
        location = "J3-101",
        dayOfWeek = dayOfWeek,
        periods = listOf(1, 2),
        weeks = listOf(1),
    )

    /** 跑一次 seam：返回注入的撤销动作被调了几遍（生产接线传的就是 `cancelAll`） */
    private fun takeDownCalls(
        courses: List<Course>,
        classProgressEnabled: Boolean,
        dndEnabled: Boolean,
        now: LocalDateTime,
    ): Int {
        var calls = 0
        val tookDown = ReminderScheduler.takeDownClassProgressIfNeeded(
            courses = courses,
            semesterStart = semesterStart,
            timeSlots = slots,
            classProgressEnabled = classProgressEnabled,
            dndEnabled = dndEnabled,
            now = now,
            tearDown = { calls++ },
        )
        // 结论与动作必须同进同出：只留日志不撤销（或反之）都是接线错了
        assertEquals("返回值与 tearDown 的调用次数不一致", calls > 0, tookDown)
        return calls
    }

    // ---- 修复要买到的那一条 --------------------------------------------------

    /**
     * 课前提醒全关（正是走 `plan == null` 的人群）+ 正在上课 + 「课程进行中」开着：
     * 一次撤销都不许发生 —— 少一次 restore / cancelClassOngoing / cancelWatchdog，
     * 勿扰就不会被抖那 5 秒，自愈凭据也不会被抹掉。
     */
    @Test
    fun inProgressClassIsNotTakenDownWhenProgressSwitchIsOn() {
        val calls = takeDownCalls(
            courses = listOf(course()),
            classProgressEnabled = true,
            dndEnabled = false,
            now = LocalDateTime.of(2026, 9, 7, 8, 20),
        )

        assertEquals(
            "课还在上，restore/cancelClassOngoing/cancelWatchdog 一次都不该被调用：" +
                "勿扰会先被强制恢复、约 5 秒后再被重新进入，中间还把自愈凭据一起抹了",
            0,
            calls,
        )
    }

    /** 只开「上课自动勿扰」、关掉常驻通知的这类用户同样不能被打扰（两个开关互相独立） */
    @Test
    fun inProgressClassIsNotTakenDownWhenOnlyDndIsOn() {
        val calls = takeDownCalls(
            courses = listOf(course()),
            classProgressEnabled = false,
            dndEnabled = true,
            now = LocalDateTime.of(2026, 9, 7, 9, 34, 59), // 下课前一秒
        )

        assertEquals(0, calls)
    }

    // ---- 负例：既有的清理语义一条都不许松 ------------------------------------

    /** 两个开关都关着：`rescheduleNextWindow` 会因此早退，这条分支没人接手，照旧收干净 */
    @Test
    fun bothSwitchesOffStillTakeDownEvenMidClass() {
        val calls = takeDownCalls(
            courses = listOf(course()),
            classProgressEnabled = false,
            dndEnabled = false,
            now = LocalDateTime.of(2026, 9, 7, 8, 20),
        )

        assertEquals("两个课堂开关都关时必须照旧 cancelAll", 1, calls)
    }

    /** 学期已结束 / 一节都挑不出来（R5 F-11）：此刻没有课在进行，照旧收干净 */
    @Test
    fun noClassWindowAtAllStillTakesDown() {
        val afterTheOnlyWeek = LocalDateTime.of(2026, 9, 8, 10, 0)

        assertEquals("挑不出课堂窗口时必须照旧 cancelAll", 1, takeDownCalls(listOf(course()), true, true, afterTheOnlyWeek))

        // 课表为空（生产上更早的那条分支接手，判据本身也要给出同一个结论）
        assertTrue(
            "没有课表时收铃不许带判据",
            ReminderScheduler.shouldTakeDownClassProgress(
                courses = emptyList(),
                semesterStart = semesterStart,
                timeSlots = slots,
                classProgressEnabled = true,
                dndEnabled = true,
                now = LocalDateTime.of(2026, 9, 7, 8, 20),
            ),
        )
    }

    /** 课还没开始：此刻没有课在进行，遗留的常驻通知与勿扰照旧要收（与改动前逐字一致） */
    @Test
    fun classNotStartedYetStillTakesDown() {
        val calls = takeDownCalls(
            courses = listOf(course()),
            classProgressEnabled = true,
            dndEnabled = true,
            now = LocalDateTime.of(2026, 9, 7, 7, 30),
        )

        assertEquals(1, calls)
    }

    /**
     * 用户把正在上的那门课删了（重排后窗口跳到周二）：R5 F-11 点名的场景，
     * 必须照旧收干净，否则那条常驻通知会一路留到明天那节课下课。
     */
    @Test
    fun deletedOngoingClassStillTakesDown() {
        val calls = takeDownCalls(
            courses = listOf(course(id = 2L, dayOfWeek = 2)), // 周一那节已被删
            classProgressEnabled = true,
            dndEnabled = true,
            now = LocalDateTime.of(2026, 9, 7, 8, 20),
        )

        assertEquals("正在上的那节课被删掉后必须收干净", 1, calls)
    }

    /**
     * 窗口边界（含开课那一秒、不含下课那一秒）：判据整个挂在
     * [ClassProgressScheduler.ClassWindow.ongoingAt] 上，这里只钉住"没有第二份比较式"。
     */
    @Test
    fun windowBoundariesFollowOngoingAt() {
        val courses = listOf(course())

        assertEquals(
            "开课那一秒起算进行中",
            0,
            takeDownCalls(courses, true, false, LocalDateTime.of(2026, 9, 7, 8, 0)),
        )
        assertEquals(
            "下课那一秒起算已结束",
            1,
            takeDownCalls(courses, true, false, LocalDateTime.of(2026, 9, 7, 9, 35)),
        )
    }

    /**
     * 时刻只认传进来的那一个：这些用例都喂 2026-09-07 的固定时刻，
     * 判据若自己去读真钟（跑测试的机器不是那天），上面"正在上课"那几条会当场翻面。
     * 这里再补一条方向相反的：同一个课表喂两个不同时刻，结论必须跟着时刻走。
     */
    @Test
    fun conclusionFollowsTheInjectedClock() {
        val during = takeDownCalls(listOf(course()), true, true, LocalDateTime.of(2026, 9, 7, 8, 20))
        val before = takeDownCalls(listOf(course()), true, true, LocalDateTime.of(2026, 9, 7, 7, 59, 59))

        assertEquals("注入「正在上课」的时刻却收了", 0, during)
        assertEquals("注入「课还没开始」的时刻却没收", 1, before)
    }

    // ---- 生产接线（源码形状）------------------------------------------------

    /**
     * `plan == null` 分支里那道撤销只能经由 seam 注入，不许留一份无条件的。
     *
     * 这条钉的是"改在了地方"：判据写对但调用点又补一句无条件 cancelAll，
     * 上面那些行为断言全绿、设备上的抖动照旧。
     */
    @Test
    fun noReminderBranchRoutesTeardownThroughTheSeam() {
        val source = withoutComments(readMainSource(REMINDER_SCHEDULER_FILE))
        val branch = balancedBlock(source, "if (plan == null) {")

        assertTrue(
            "plan == null 那条分支不再经由 takeDownClassProgressIfNeeded 决策：\n$branch",
            branch.contains("takeDownClassProgressIfNeeded("),
        )
        assertEquals(
            "分支里的撤销只能有一处，且必须是注入给 seam 的那个 lambda：" +
                "多补一句无条件 cancelAll 就等于把抖动改回来：\n$branch",
            1,
            occurrences(branch, "ClassProgressScheduler.cancelAll(context)"),
        )
        assertTrue(
            "撤销没有作为 tearDown 传进 seam：\n$branch",
            branch.contains("tearDown = { ClassProgressScheduler.cancelAll(context) }"),
        )
        assertFalse(
            "分支里又长出一份零碎撤销（restore / cancelClassOngoing / cancelWatchdog 只能由 cancelAll 一处代表）：\n$branch",
            branch.contains("ClassProgressDnd.") || branch.contains("cancelClassOngoing("),
        )
    }

    /**
     * 判据只读注入的 [LocalDateTime]，不自己去拿钟。
     *
     * 同一次判断里"选窗口"与"判在不在上课"用两只钟，就会在跨过节次边界的那一秒
     * 选出上一节、却按下下一节的清理分支（`planOnce` 与 `rescheduleWindows`
     * 那两处注释防的就是这件事）。
     */
    @Test
    fun decisionReadsNoClockOfItsOwn() {
        val source = withoutComments(readMainSource(REMINDER_SCHEDULER_FILE))
        val body = balancedBlock(source, "internal fun shouldTakeDownClassProgress(")

        for (clock in listOf("LocalDateTime.now(", "System.currentTimeMillis(", "Instant.", "Date(", "Clock.")) {
            assertFalse("判据里自己读钟（$clock）：一次判断只能用传进来的那个 now：\n$body", body.contains(clock))
        }
        assertTrue(
            "判据没有把同一个 now 换算成毫秒交给 ongoingAt（选窗口与判在不在上课必须同源）：\n$body",
            body.contains("now.toEpochMillis()"),
        )
        // 「正在上课」的口径不许在这里重写一遍
        assertFalse("判据里自己写了窗口比较式：请用 ClassWindow.ongoingAt：\n$body", body.contains("startMillis"))
    }

    /**
     * 其余清理路径一条都没被削弱：无课表那条分支照旧无条件收、
     * 两个开关都关时下面那段照旧收（R5 F-11 / F-12）。
     */
    @Test
    fun otherTakeDownPathsStayUnconditional() {
        val source = withoutComments(readMainSource(REMINDER_SCHEDULER_FILE))
        val rescheduleAll = balancedBlock(source, "fun rescheduleAll(")

        assertEquals(
            "rescheduleAll 里 cancelAll 只能有这三处：无课表那条分支、seam 的 tearDown、两个开关都关的 else 分支：\n$rescheduleAll",
            3,
            occurrences(rescheduleAll, "ClassProgressScheduler.cancelAll(context)"),
        )
        val noSemesterBranch = balancedBlock(source, "if (semesterStart == null || courses.isEmpty()) {")
        assertTrue(
            "没配学期 / 课表已空时必须无条件收（「上课中途清空课表」没有续排链可等）：\n$noSemesterBranch",
            noSemesterBranch.contains("ClassProgressScheduler.cancelAll(context)"),
        )
        assertFalse(
            "无课表那条分支被加了判据：真没数据时「课还在上」根本不成立，收铃不许有条件（R5 F-11）：\n$noSemesterBranch",
            noSemesterBranch.contains("shouldTakeDownClassProgress"),
        )
    }

    /** 两个课堂开关一次重排只读一份 prefs（读两遍就是同一轮里两次磁盘，而且两遍结论可以不一致） */
    @Test
    fun classSwitchesAreReadOncePerRound() {
        val source = withoutComments(readMainSource(REMINDER_SCHEDULER_FILE))
        val rescheduleAll = balancedBlock(source, "fun rescheduleAll(")

        assertEquals(
            "rescheduleAll 里 getSharedPreferences 只能有一次：\n$rescheduleAll",
            1,
            occurrences(rescheduleAll, "getSharedPreferences("),
        )
        assertEquals(1, occurrences(rescheduleAll, "PREF_CLASS_PROGRESS"))
        assertEquals(1, occurrences(rescheduleAll, "PREF_DND"))
    }

    /**
     * 这张卡不许动 `ClassProgressReceiver` 的 ACTION_START 那段顺序（R5 F-31）：
     * `scheduleEnd` 必须排在 `enter()` 与其它副作用之前。
     */
    @Test
    fun startBellStillSchedulesEndBellFirst() {
        val source = withoutComments(readMainSource(CLASS_PROGRESS_RECEIVER_FILE))
        val endBell = source.indexOf("ClassProgressScheduler.scheduleEnd(context, end)")
        val liveWindow = source.indexOf("ReminderNotifications.startLiveWindow(")
        val enter = source.indexOf("ClassProgressDnd.enter(context, end)")

        assertTrue("上课铃里不再先排下课铃了（R5 F-31）：$endBell vs $enter", endBell in 0 until enter)
        assertTrue("下课铃被排到发实况之后：$liveWindow vs $endBell", endBell in 0 until liveWindow)
    }

    /** 续排链的兜底没被削弱（R5 F-12）：`rescheduleReminders` 返回 false 之后仍要独立续排一次 */
    @Test
    fun bellFallbackStillRunsWhenReminderChainBailsOut() {
        val source = withoutComments(readMainSource(BACKGROUND_SYNC_FILE))
        val wrapper = balancedBlock(source, "suspend fun rescheduleRemindersAndBells(context: Context)")

        assertTrue(
            "rescheduleRemindersAndBells 不再补排课堂铃：课前提醒全关的用户第一节课之后再没有实况与勿扰：\n$wrapper",
            wrapper.contains("ClassProgressScheduler.rescheduleNextWindow(context)"),
        )

        val worker = withoutComments(readMainSource(FALLBACK_WORKER_FILE))
        val doWork = balancedBlock(worker, "override suspend fun doWork(): Result")
        assertTrue(
            "兜底 Worker 不再补排课堂铃（同一件事的第二条路）：\n$doWork",
            doWork.contains("ClassProgressScheduler.rescheduleNextWindow(applicationContext)"),
        )
    }

    // ---- 源码核对工具 --------------------------------------------------------

    private fun readMainSource(relative: String): String {
        val file = File(findMainJavaDir(), relative)
        assertTrue("找不到 ${file.path}：文件挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText()
    }

    /**
     * 从 [open] 处那个左括号起配平到对应的右括号（含），返回整段。
     *
     * [signature] 以 `{` 结尾时按它自己配平（那条 `if` 分支），否则（函数签名）
     * 找它之后的第一个 `{`。取的是**整段**，因为它内部的嵌套分支也要一起数。
     */
    private fun balancedBlock(source: String, signature: String): String {
        val at = source.indexOf(signature)
        check(at >= 0) { "找不到 $signature：那条分支或函数改过名，这条守卫要跟着改" }
        val open = if (signature.endsWith("{")) at + signature.length - 1 else source.indexOf('{', at)
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

    /** 注释里提到函数名与中文引号都不该影响配平，但会污染计数，所以先抹成空白 */
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

    /** 工作目录是模块目录还是仓库根不由这里决定：两种布局都试，全落空就抛（跳过的守卫等于没守卫） */
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

    private companion object {
        const val REMINDER_SCHEDULER_FILE = "com/buaa/schedule/reminder/ReminderScheduler.kt"
        const val CLASS_PROGRESS_RECEIVER_FILE = "com/buaa/schedule/reminder/ClassProgressReceiver.kt"
        const val BACKGROUND_SYNC_FILE = "com/buaa/schedule/widget/BackgroundSync.kt"
        const val FALLBACK_WORKER_FILE = "com/buaa/schedule/widget/WidgetFallbackWorker.kt"
    }
}
