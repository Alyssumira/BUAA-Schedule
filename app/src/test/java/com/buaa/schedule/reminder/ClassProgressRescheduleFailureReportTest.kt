package com.buaa.schedule.reminder

import com.buaa.schedule.reminder.ClassProgressScheduler.NextWindowInput
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 课堂铃兜底续排那一步的失败，冷启动闸门看得见吗（ai/T14；收掉的是 ai/T13 自己在两处注释里
 * 登记的那条「已知残余 / 方向安全」，账写在 `BackgroundSync.rescheduleRemindersAndBells` 的注释里）。
 *
 * 为什么这件事值得单独钉：[ClassProgressScheduler.rescheduleNextWindow] 整体裹在 `runCatching` 里，
 * 而它调的 `rescheduleWindows` **第一句就是** `cancel`（撤上/下课铃）。真正的失败点全在 `cancel`
 * **之前**（读 prefs、取仓库、查学期与课表，Room 的 `SQLiteFullException` / cursor window 都现实存在）——
 * 抛在那里意味着上一轮那对课堂铃还挂着、本轮一个字都没执行，闸门钥匙 2 探
 * `hasPendingClassBells` 只会探到「闹钟在」，于是这一整轮被跳过，最长 24 小时里用户用的是
 * **上一轮的窗口**（课被删了还在响、改过时间了还按旧的时刻响）。
 * 唯一补得上的做法是把失败记进闸门的 `failures` 清单（⇒ 不写指纹 ⇒ 下一次冷启动无条件重跑），
 * 而「记进清单」这一步就是下面这些用例：抛 ⇒ 报一次、报的标签是什么、以及**什么不算失败**。
 *
 * 为什么测的是不接 Context 的编排本体（`internal` 重载 + 注入 lambda）：本模块单测没有
 * Robolectric（android.jar 里全是抛 "not mocked" 的桩），真跑一次这条链要设备；
 * 形状抄 [com.buaa.schedule.widget.BackgroundSync.runColdStartWidgetSteps] 与 `ColdStartRebuildTest`。
 * `Log.w` 那句也因此做成注入的 `logFailure` 钩子（默认值仍是原来那行，一字未改），
 * 否则它自己那桩「not mocked」异常会在报告口之前先把用例带偏。
 *
 * 生产接线（`BackgroundSync` 真把它自己那个报告口传下来了、另外四个调用点仍吃默认值）
 * JVM 跑不到，由 [ClassProgressRescheduleWiringTest] 按源码形状核对。
 */
class ClassProgressRescheduleFailureReportTest {

    /** 一条注入链的执行记录：报告口收到过什么、三步各被走过没有、日志与报告谁先 */
    private class Trace {
        val reports = mutableListOf<Pair<String, Throwable>>()
        val inputs = mutableListOf<NextWindowInput>()

        /** 事件序列：`log` 与 `report` 的先后是审计 §4.4 取证条件的一部分 */
        val events = mutableListOf<String>()
        var loads = 0

        val labels: List<String> get() = reports.map { it.first }
    }

    private fun input() = NextWindowInput(
        courses = emptyList(),
        semesterStart = LocalDate.of(2026, 9, 7),
        timeSlots = emptyList(),
    )

    /**
     * 跑一次编排本体。
     *
     * @param switchesFail 读两个课堂开关就抛（生产里是 `getSharedPreferences`）
     * @param loadFail 查库抛（生产里是 `scheduleRepository()` / `getCurrentSemester()` /
     *   `getDisplayCourses` / `getTimeSlots`，正是「上一轮的铃还挂着」那一类失败点）
     * @param loadNull 学期或学期起始日期缺失那条早退（**不是**失败）
     * @param rescheduleFail 真正重排那一步抛（`cancel` 之后的那半边）
     */
    private fun runSeam(
        trace: Trace,
        classProgress: Boolean = true,
        dndEnabled: Boolean = false,
        switchesFail: Boolean = false,
        loadFail: Boolean = false,
        loadNull: Boolean = false,
        rescheduleFail: Boolean = false,
    ) = runBlocking {
        ClassProgressScheduler.rescheduleNextWindow(
            readClassSwitches = {
                trace.events += "switches"
                if (switchesFail) throw IllegalStateException("课堂开关读不动")
                classProgress to dndEnabled
            },
            loadWindowInput = {
                trace.loads++
                trace.events += "load"
                if (loadFail) throw IllegalStateException("课表读不动（SQLiteFullException 的同族）")
                if (loadNull) null else input()
            },
            reschedule = {
                trace.inputs += it
                trace.events += "reschedule"
                if (rescheduleFail) throw SecurityException("精确闹钟权限被运行期撤销")
            },
            onStepFailed = { label, error ->
                trace.reports += label to error
                trace.events += "report"
            },
            logFailure = {
                trace.events += "log"
            },
        )
    }

    // ---- 抛了就要报：三个失败点各一遍 ----------------------------------------

    /** 读 prefs 抛：这是「铃还挂着、本轮一个字都没执行」那一类，闸门最该看见的一次 */
    @Test
    fun failureBeforeCancelIsReportedOnceWithTheStepLabel() {
        val trace = Trace()
        runSeam(trace, switchesFail = true)

        assertEquals("读开关这一步抛了，闸门必须恰好收到一次报告：\n${trace.events}", 1, trace.reports.size)
        assertEquals(
            "报告口的标签口径变了（既有的是 rescheduleReminders / rebuildRemindersAndBells / " +
                "coldStartWidgetSteps 这一族）：${trace.labels}",
            listOf("rescheduleNextWindow"),
            trace.labels,
        )
        assertTrue(
            "报告里带的异常被换掉了：${trace.reports}",
            trace.reports.single().second.message == "课堂开关读不动",
        )
        assertEquals("抛在开关那一步就不该再往下查库", 0, trace.loads)
        assertEquals("抛在开关那一步就不该再往下重排", 0, trace.inputs.size)
    }

    @Test
    fun databaseReadFailureIsReportedOnce() {
        val trace = Trace()
        runSeam(trace, loadFail = true)

        assertEquals("查库这一步抛了，闸门必须恰好收到一次报告：\n${trace.events}", 1, trace.reports.size)
        assertEquals(listOf("rescheduleNextWindow"), trace.labels)
        assertEquals("这一步抛了就不该走到重排", 0, trace.inputs.size)
    }

    @Test
    fun rescheduleFailureIsReportedOnce() {
        val trace = Trace()
        runSeam(trace, rescheduleFail = true)

        assertEquals("重排那一步（cancel 之后的半边）抛了也要报：\n${trace.events}", 1, trace.reports.size)
        assertEquals(listOf("rescheduleNextWindow"), trace.labels)
        assertEquals(1, trace.inputs.size)
    }

    @Test
    fun logLineStillComesBeforeTheReport() {
        val trace = Trace()
        runSeam(trace, loadFail = true)

        assertEquals(
            "那句 Log.w（审计 §4.4 的取证过滤条件）与报告口各一次，而且日志在前：\n${trace.events}",
            listOf("switches", "load", "log", "report"),
            trace.events,
        )
    }

    /** 禁改项：`runCatching` 不许改成向外抛 —— 五个调用点里四个没有报告口 */
    @Test
    fun failureStillNeverEscapesTheStep() {
        val trace = Trace()
        runSeam(trace, rescheduleFail = true)
        runSeam(trace, loadFail = true)
        runSeam(trace, switchesFail = true)

        assertEquals("三个失败点各报一次，一共三次（多一遍就是同一道失败报了两遍）", 3, trace.reports.size)
        assertEquals(
            "异常逃出这一步了：下课铃广播与前台服务那条链没有报告口，会被它带崩",
            3,
            trace.labels.count { it == "rescheduleNextWindow" },
        )
    }

    // ---- 干净跑完与三条「不是失败」的早退 ------------------------------------

    @Test
    fun cleanRunReportsNothingAndReschedulesExactlyOnce() {
        val trace = Trace()
        runSeam(trace)

        assertEquals("正常跑完不该有任何留痕：\n${trace.events}", emptyList<String>(), trace.labels)
        assertEquals(
            "整步的顺序：读开关 → 查库 → 重排，一步不多一步不少",
            listOf("switches", "load", "reschedule"),
            trace.events,
        )
        assertEquals("重排只能被走一遍（多一遍就是把刚排上的上课铃撤了重排）", 1, trace.inputs.size)
        assertEquals("传给重排的必须是刚查出来的那份输入", input(), trace.inputs.single())
    }

    @Test
    fun bothSwitchesOffIsAnEarlyReturnNotAFailure() {
        val trace = Trace()
        runSeam(trace, classProgress = false, dndEnabled = false)

        assertEquals(
            "两个开关都关是「没有东西可排」，不是「排失败了」：记成失败只会逼闸门白重跑一整轮" +
                "\n${trace.events}",
            emptyList<String>(),
            trace.labels,
        )
        assertEquals("两个开关都关时不该再查库", 0, trace.loads)
        assertEquals("两个开关都关时不该重排", 0, trace.inputs.size)
    }

    @Test
    fun eitherSwitchAloneStillContinuesTheChain() {
        // 早退判据若写成 `classProgress || dndEnabled`，上面那条用例正好反了：
        // 只开勿扰（关掉常驻通知）的人实况没有，但勿扰与下课铃全靠这条链续排
        val progressOnly = Trace()
        runSeam(progressOnly, classProgress = true, dndEnabled = false)
        val dndOnly = Trace()
        runSeam(dndOnly, classProgress = false, dndEnabled = true)

        assertEquals(1, progressOnly.inputs.size)
        assertEquals(1, dndOnly.inputs.size)
        assertEquals(emptyList<String>(), progressOnly.labels)
        assertEquals(emptyList<String>(), dndOnly.labels)
    }

    @Test
    fun missingSemesterOrStartIsAnEarlyReturnNotAFailure() {
        val trace = Trace()
        runSeam(trace, loadNull = true)

        assertEquals(
            "学期为空 / 学期没有起始日期那两条 return 同样不是失败：那是「没有可排的窗口」，" +
                "记成失败会让闸门每一轮都无条件重跑（审计 §2.1 那笔省电量级就没了）\n${trace.events}",
            emptyList<String>(),
            trace.labels,
        )
        assertEquals("早退之后不该再走重排", 0, trace.inputs.size)
        assertEquals(listOf("switches", "load"), trace.events)
    }
}
