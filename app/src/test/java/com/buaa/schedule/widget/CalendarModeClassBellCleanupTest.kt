package com.buaa.schedule.widget

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ai/T15 第一枚：日历模式（「系统日历提醒」）那条早退分支到底该不该把课堂铃一起撤掉。
 *
 * 改前的形状是 `ReminderScheduler.cancelAll` + 无条件 `ClassProgressScheduler.cancelAll`，
 * 而这个函数紧接着 `return false`，于是 [BackgroundSync.rescheduleRemindersAndBells]
 * 一收到 false 就又把铃排回来 —— **一边拆一边装**。拆的那一下是同族第四处
 * （前三处：ai/T11 的「课还在上」、ai/T11b 的「正在数课前倒计时」、以及
 * [com.buaa.schedule.reminder.ReminderScheduler.shouldTakeDownClassProgress] 那份判据本身）：
 * `ClassProgressScheduler.cancelAll` 的第一步就是撤双铃 + `ClassProgressDnd.restore` +
 * 抹掉勿扰记录与看门狗闹钟，正在上课时每重排一次用户就被放开勿扰约 5 秒，
 * 而那份记录正是当下要靠的自愈凭据（重新进入被 ROM 吞掉就再也救不回来）。
 *
 * 「日历模式下课堂铃该跑」这个结论不是从代码读出来的，是三条证据拼的：
 * ① 模式选择器自己的文案是「**提醒方式**：应用内提醒 / 系统日历提醒」，管的是课前提醒
 * 从哪条通道下发，而课堂实况与自动勿扰**没有日历等价物**；
 * ② [BackgroundSync.rescheduleRemindersAndBells] 的 KDoc 本来就把「① 系统日历提醒模式」列成
 * "本轮课堂铃没人排、要补排"的第一类人群，`rescheduleNextWindow` 同样自述
 * "独立于课前提醒开关" —— 反过来要真让日历模式没有课堂铃，得改这个 `return false` 契约，
 * 而 [ColdStartRebuild] 的钥匙 2 正读它；
 * ③ 风险不对称：判成"不该跑"= 从一整类用户手里收掉一个主打功能。
 *
 * 修法取**窄判据**：只撤"两枚课堂开关都关"的那种用户（= 用户自己关了本功能），
 * 口径与 [com.buaa.schedule.reminder.ClassProgressScheduler.rescheduleNextWindow]
 * 开头读的那一份逐字相同。这里**不**复用 [com.buaa.schedule.reminder.ReminderScheduler.shouldTakeDownClassProgress]
 * 那份重判据：它要先做一轮全量 `planNextClassWindow` 搜索，而这个 `false` 紧随其后就要
 * 经由续排再做**同一轮**搜索并按那份判据清理（`rescheduleWindows`：挑不出窗口 → cancelAll；
 * 课还没开始且不在数课前倒计时 → 停服务 + 撤常驻 + restore），在这里再搜一遍是纯白付。
 *
 * ①② 那两格判据（[BackgroundSync.shouldCancelAllInCalendarMode]）与"这一步撤了什么"是 JVM 测得到的：
 * 清理的编排本身就在生产侧（[BackgroundSync.cleanUpInCalendarMode]，不接 Context、三件副作用全注入），
 * ②那组行为断言跑的就是它 —— 撤几遍、开关读几遍、抛了报没报都是生产真做了几个动作。
 * 剩下测不到的是"接线到底注没注对那三样"（本模块没有 Robolectric，`getSharedPreferences`
 * 与两个 `cancelAll` 都要 Context），按源码形状核对 —— 写法照抄
 * [com.buaa.schedule.reminder.BootDndSelfHealDecisionTest] 的
 * `balancedBlock` / `withoutComments` / `blankCommentsAndLiterals` / `findMainJavaDir`：
 * 找不到文件就抛，不用 `assumeTrue` 跳过（跳过的守卫等于没守卫）。
 */
class CalendarModeClassBellCleanupTest {

    // ---- ① 判据真值表 ----------------------------------------------------------

    @Test
    fun bothSwitchesOffCancelsEverything() {
        assertTrue(BackgroundSync.shouldCancelAllInCalendarMode(classProgress = false, dndEnabled = false))
    }

    @Test
    fun classProgressOnAloneKeepsTheChain() {
        assertFalse(BackgroundSync.shouldCancelAllInCalendarMode(classProgress = true, dndEnabled = false))
    }

    @Test
    fun dndOnAloneKeepsTheChain() {
        assertFalse(BackgroundSync.shouldCancelAllInCalendarMode(classProgress = false, dndEnabled = true))
    }

    @Test
    fun bothSwitchesOnKeepsTheChain() {
        assertFalse(BackgroundSync.shouldCancelAllInCalendarMode(classProgress = true, dndEnabled = true))
    }

    /**
     * 默认值那一格（`PREF_CLASS_PROGRESS=true` / `PREF_DND=false`）落在**保留**这一头：
     * 「日历模式 + 课堂铃在跑」是**默认状态**，不是小众配置 —— 改前的无条件 cancelAll
     * 命中的是每一个没主动动过这两枚开关的日历模式用户。默认值本身的形状另钉：
     * [calendarBranchReadsTheSameTwoPrefsAsTheRescheduleChain]。
     */
    @Test
    fun productionDefaultsAreOnTheKeepSide() {
        assertFalse(BackgroundSync.shouldCancelAllInCalendarMode(classProgress = true, dndEnabled = false))
    }

    // ---- ② 行为：日历模式那一步到底撤了什么 ------------------------------------

    /** 一轮清理的账：两类闹钟各撤了几遍、开关读了几遍、有没有向报告口留痕、按什么顺序 */
    private class Calls(
        val reminderAlarms: Int,
        val classBells: Int,
        val switchReads: Int,
        val failures: List<String>,
        val events: List<String>,
    )

    /**
     * 跑一遍**生产**那条编排本体 [BackgroundSync.cleanUpInCalendarMode]：三件要 Context 的副作用
     * 换成计数，报告口换成收集标签，日志钩子换成记一笔（`android.util.Log` 在本模块 JVM 单测里
     * 是抛 "not mocked" 的桩，不隔开就永远轮不到报告口 —— 同 `ClassProgressRescheduleFailureReportTest`）。
     *
     * ai/T15 收工之前这里是**测试自己复刻**的一份日历分支，`reminders++` / `switches += …` /
     * `failures += CLEANUP_LABEL` 全是复刻体自己写的，于是下面那 4 条"行为"断言数的是复刻体：
     * 生产里把 `ReminderScheduler.cancelAll(context)` 删掉、把报告标签改掉，它们照样绿。
     * 编排抽成生产侧的 internal seam 之后（形状照抄 [BackgroundSync.runColdStartWidgetSteps] 与
     * [com.buaa.schedule.reminder.ClassProgressScheduler.rescheduleNextWindow]），这里数到的才是
     * 生产真的做了几个动作。"接线注没注对那三样"仍然测不到，归 ③ 那组源码形状守卫。
     */
    private fun runCalendarModeCleanup(
        classProgress: Boolean,
        dndEnabled: Boolean,
        cancelInAppReminderAlarms: () -> Unit = {},
        cancelClassBells: () -> Unit = {},
    ): Calls {
        var reminders = 0
        var bells = 0
        var reads = 0
        val failures = mutableListOf<String>()
        val events = mutableListOf<String>()
        BackgroundSync.cleanUpInCalendarMode(
            cancelInAppReminderAlarms = {
                events += "alarm"
                cancelInAppReminderAlarms()
                reminders++
            },
            readClassSwitches = {
                events += "switches"
                reads++
                classProgress to dndEnabled
            },
            cancelClassBells = {
                events += "bells"
                cancelClassBells()
                bells++
            },
            onStepFailed = { label, _ ->
                events += "report"
                failures += label
            },
            logFailure = { events += "log" },
        )
        return Calls(reminders, bells, reads, failures, events)
    }

    @Test
    fun inAppReminderAlarmIsAlwaysCancelled() {
        for ((classProgress, dndEnabled) in listOf(
            true to true, true to false, false to true, false to false,
        )) {
            val calls = runCalendarModeCleanup(classProgress, dndEnabled)
            assertEquals(
                "日历模式本轮就是不用应用内闹钟，四种开关下都要撤那条：$classProgress/$dndEnabled",
                1,
                calls.reminderAlarms,
            )
        }
    }

    @Test
    fun classBellsAreCancelledOnlyWhenBothSwitchesAreOff() {
        for ((classProgress, dndEnabled) in listOf(true to true, true to false, false to true)) {
            val calls = runCalendarModeCleanup(classProgress, dndEnabled)
            assertEquals(
                "至少有一枚开着就不许撤（撤了就是日历模式用户被收掉上课实况与自动勿扰）：" +
                    "classProgress=$classProgress dndEnabled=$dndEnabled",
                0,
                calls.classBells,
            )
        }
        val cancelled = runCalendarModeCleanup(classProgress = false, dndEnabled = false)
        assertEquals(
            "两枚都关（用户自己关了本功能）时不撤就是留一条永不消失的常驻通知 + 永久勿扰",
            1,
            cancelled.classBells,
        )
    }

    /** 清理仍然整块裹在同一道 `runCatching` 里，失败照旧报给那一个报告口（ai/T14 的账） */
    @Test
    fun cleanupFailureStillGoesToTheOneReportPort() {
        val failed = runCalendarModeCleanup(
            classProgress = false,
            dndEnabled = false,
            cancelInAppReminderAlarms = { error("撤闹钟这一步抛了") },
        )
        assertEquals("失败没报给报告口（闸门会把这一轮记成干净）：", listOf(CLEANUP_LABEL), failed.failures)
        assertEquals("撤提醒闹钟那步就抛了，不许记成「撤过了」", 0, failed.reminderAlarms)
        assertEquals(0, failed.classBells)
        assertEquals("抛在撤闹钟那一步就不该再去读开关、再撤课堂铃：${failed.events}", 0, failed.switchReads)

        // 报告口只此一个标签、而且在那句取证日志之后（顺序反了 §4.4 的账就数歪）
        val throwingBells = runCalendarModeCleanup(
            classProgress = false,
            dndEnabled = false,
            cancelClassBells = { error("撤铃抛了") },
        )
        assertEquals("撤铃那一步抛了也要报，而且只报一次：", listOf(CLEANUP_LABEL), throwingBells.failures)
        assertEquals(
            "这一轮的步骤顺序与抽出前逐条一致（日志钩子排在报告口之前）：",
            "alarm,switches,bells,log,report",
            throwingBells.events.joinToString(","),
        )

        val clean = runCalendarModeCleanup(classProgress = false, dndEnabled = false)
        assertEquals("干净一轮不该留痕：", emptyList<String>(), clean.failures)
        assertEquals("干净一轮不该碰日志钩子：", "alarm,switches,bells", clean.events.joinToString(","))
    }

    @Test
    fun theCriterionConsultsExactlyTheTwoClassSwitches() {
        val calls = runCalendarModeCleanup(classProgress = false, dndEnabled = false)
        assertEquals(
            "那一轮把两枚开关读了不止一遍（同一轮两次磁盘，两次结论可以不一致）：${calls.events}",
            1,
            calls.switchReads,
        )

        val seam = calendarCleanupSeam()
        assertTrue(
            "判据不再只由那一次读出来的两枚开关喂（同族又要长一份判据）：\n$seam",
            seam.contains("val (classProgress, dndEnabled) = readClassSwitches()") &&
                seam.contains("shouldCancelAllInCalendarMode(classProgress, dndEnabled)"),
        )
        assertEquals("一次清理里把判据问了不止一遍：\n$seam", 1, occurrences(seam, "shouldCancelAllInCalendarMode("))
    }

    // ---- ③ 源码形状：生产接线确实按这份判据走 ----------------------------------

    /** 窄判据守着那次撤课堂铃，而且撤的仍然是完整那一份（判据在编排本体里，接线的尊号在分支里） */
    @Test
    fun cancelAllInCalendarBranchIsGuardedByTheCriterion() {
        val branch = calendarModeBranch()
        val seam = calendarCleanupSeam()
        val guard = balancedBlock(seam, "if (shouldCancelAllInCalendarMode(")
        // 分支里注给 cancelClassBells 的那一样，就是守卫块唯一会执行的撤
        val bells = balancedBlock(branch, "cancelClassBells = {")

        assertTrue(
            "日历模式那一步的课堂铃清理不再走窄判据（同族第四处回来了）：\n$seam",
            seam.contains("if (shouldCancelAllInCalendarMode("),
        )
        assertTrue(
            "守卫块里撤的必须是编排那三件里的 cancelClassBells（判据之外又多撤一样 = 新的一处无条件撤）：\n$guard",
            guard.contains("cancelClassBells()"),
        )
        assertTrue(
            "注给 cancelClassBells 的不再是完整那一份（cancelAll 才带 restore 与撤常驻；" +
                "只 cancel 双铃就是「只撤一半」的新残留）：\n$bells",
            bells.contains("ClassProgressScheduler.cancelAll(context)"),
        )
        assertEquals(
            "分支里除了注入那一次还自己动手撤了一遍课堂铃（绕开了判据）：\n$branch",
            1,
            occurrences(branch, "ClassProgressScheduler.cancelAll"),
        )
        assertEquals(
            "日历模式那一步不再调用生产那一份编排（三件注入有了着落却没人执行，等于这条清理没了）：\n$branch",
            1,
            occurrences(branch, "cleanUpInCalendarMode("),
        )
    }

    /** 判据之前一次撤课堂铃都不许有 —— 有的话就是"无条件撤"回来了 */
    @Test
    fun cancelAllIsNoLongerUnconditionalInTheCalendarBranch() {
        val branch = calendarModeBranch()
        val seam = calendarCleanupSeam()
        val at = seam.indexOf("if (shouldCancelAllInCalendarMode(")
        assertTrue("找不到那道守卫：\n$seam", at >= 0)
        // 只数编排本体：签名里那三个参数名各自带着 cancelClassBells / cancelInAppReminderAlarms
        val bodyStart = seam.indexOf("runCatching {")
        check(bodyStart in 0 until at) { "编排本体里找不到那道 runCatching（抽出去的分支又长回 Context 侧了？）：\n$seam" }
        val beforeGuard = seam.substring(bodyStart, at)

        assertFalse(
            "守卫之前还出现一次撤课堂铃 = 无条件撤回来了：正在上课时每重排一次就被放开勿扰约 5 秒，" +
                "那份勿扰记录与看门狗一并抹掉：\n$beforeGuard",
            beforeGuard.contains("cancelClassBells"),
        )
        assertTrue(
            "应用内提醒闹钟那一头仍然一律撤（那就是这个模式的本意），而且排在判据之前：\n$beforeGuard",
            beforeGuard.contains("cancelInAppReminderAlarms()"),
        )
        // 顺序钉完了要钉"那一头撤的到底是哪样本尊"：两本尊对调，上面两条一起被骗过去
        val alarms = balancedBlock(branch, "cancelInAppReminderAlarms = {")
        assertTrue(
            "判据之前一律撤的那一本尊不再是 ReminderScheduler.cancelAll(context)：\n$alarms",
            alarms.contains("ReminderScheduler.cancelAll(context)"),
        )
        assertFalse(
            "撤应用内闹钟那一头被注成了课堂铃那一样（无条件撤就藏在这一次对调里）：\n$alarms",
            alarms.contains("ClassProgressScheduler"),
        )
        assertEquals(
            "日历模式仍然一律不接手课堂窗口（返回值契约不许改：[ColdStartRebuild] 的钥匙 2 在读它）：\n$branch",
            1,
            occurrences(branch, "return false"),
        )
    }

    /** 两枚开关的**来源**与**默认值**两侧必须同口径：分叉的后果是"撤了没人排回来" */
    @Test
    fun calendarBranchReadsTheSameTwoPrefsAsTheRescheduleChain() {
        val branch = calendarModeBranch()
        val switches = balancedBlock(
            withoutComments(read(CLASS_PROGRESS_SCHEDULER_FILE)),
            "readClassSwitches = {",
        )

        for (constant in listOf("PREFS_NAME", "PREF_CLASS_PROGRESS", "PREF_DND")) {
            assertTrue(
                "BackgroundSync 侧的 $constant 不再取自 ClassProgressReceiver" +
                    "（自设一份字符串就是同一件事的第二份口径）：\n$branch",
                branch.contains("ClassProgressReceiver.$constant"),
            )
            assertTrue(
                "续排链侧的 $constant 不再取自 ClassProgressReceiver：\n$switches",
                switches.contains("ClassProgressReceiver.$constant"),
            )
        }
        // 默认值也是口径的一部分：这两枚开关此前还在设置页置灰，(true, false) 就是绝大多数
        // 日历模式用户的实况 —— 任一侧改了默认值，"撤"与"不排"就会分叉
        for (default in listOf("PREF_CLASS_PROGRESS, true", "PREF_DND, false")) {
            assertTrue("BackgroundSync 侧的默认值不再是 $default：\n$branch", branch.contains(default))
            assertTrue("续排链侧的默认值不再是 $default：\n$switches", switches.contains(default))
        }
        assertEquals(
            "这一轮读了两遍 prefs（同一轮里两次磁盘，两次结论可以不一致）：\n$branch",
            1,
            occurrences(branch, "getSharedPreferences("),
        )
    }

    /** 这里只长"两枚开关"这一份轻判据，不许把续排那份重判据搬过来（分支与编排本体两头都钉） */
    @Test
    fun theCalendarBranchDoesNotGrowASecondFullPlan() {
        val branch = calendarModeBranch()
        val seam = calendarCleanupSeam()

        for (second in listOf("shouldTakeDownClassProgress", "planNextClassWindow(", "isCountingDownTo")) {
            assertFalse(
                "日历模式那一步长出了第二份判据（$second）：这里只认两枚开关，重判据归" +
                    "rescheduleWindows 那一份（紧随其后的续排会再做一遍同样的搜索）：\n$branch",
                branch.contains(second),
            )
            assertFalse(
                "清理的编排本体里长出了第二份判据（$second）：口径同上，两处一起才算一份：\n$seam",
                seam.contains(second),
            )
        }
    }

    /** 判据本体只认两个入参：不读钟、不摸 prefs、不查库 */
    @Test
    fun criterionBodyIsTheTwoSwitchesOnly() {
        val source = withoutComments(read(BACKGROUND_SYNC_FILE))
        val at = source.indexOf(SHOULD_CANCEL_ALL_DECLARATION)
        check(at >= 0) { "找不到 $SHOULD_CANCEL_ALL_DECLARATION：判据改过名，这条守卫要跟着改" }
        val declaration = source.substring(at).substringBefore("\n\n")

        assertEquals(
            "判据不再只认那两枚开关：\n$declaration",
            "!classProgress && !dndEnabled",
            declaration.substringAfter("=").trim(),
        )
        for (sneak in listOf("System.currentTimeMillis(", "LocalDateTime.now(", "getSharedPreferences(", "scheduleRepository(")) {
            assertFalse("判据自己读钟/摸状态（$sneak）：两个入参必须把结论喂全：\n$declaration", declaration.contains(sneak))
        }
    }

    /**
     * 两处判据必须同口径：续排链的早退那份（两枚都关 = 什么都不排）与这里那份
     * （两枚都关 = 当场撤干净）。钉的是**共改触发线** —— 那里改了形状这里就要跟着改，
     * 否则日历模式下会出现"这里放过了、那里也不排"或"这里撤了、那里不排回来"。
     */
    @Test
    fun rescheduleChainStillBailsOutOnTheSameTwoSwitches() {
        val scheduler = withoutComments(read(CLASS_PROGRESS_SCHEDULER_FILE))

        assertTrue(
            "ClassProgressScheduler.rescheduleNextWindow 的早退判据形状变了，口径要与 " +
                "BackgroundSync.shouldCancelAllInCalendarMode 一起改，两处不许分叉：\n" +
                linesContaining(scheduler, "classProgress").joinToString("\n"),
            scheduler.contains("if (!classProgress && !dndEnabled) return"),
        )
    }

    /** 兜底链那一步一个字没动：false 兑现的仍然是"当场补排一次" */
    @Test
    fun bellFallbackContractSurvives() {
        val source = withoutComments(read(BACKGROUND_SYNC_FILE))
        val wrapper = balancedBlock(source, "suspend fun rescheduleRemindersAndBells(")

        assertTrue("续排不再排在 !reminderArmed 的守卫里：\n$wrapper", wrapper.contains("if (!reminderArmed) {"))
        assertTrue(
            "续排那一步不再带报告口（ai/T14 的账会原地复活）：\n$wrapper",
            wrapper.contains("ClassProgressScheduler.rescheduleNextWindow(context, onStepFailed)"),
        )
        assertEquals(
            "包装里出现第二个 cancelAll（= 在这里替判据做主）：\n$wrapper",
            0,
            occurrences(wrapper, "ClassProgressScheduler.cancelAll"),
        )
    }

    /**
     * UI 路（改课表 / 改提醒 / 改节次那次收尾）两种模式都必须把铃排回来：
     * 此前 `ScheduleViewModel.afterDataChangedInternal` 给 CALENDAR 单开一条"只撤不排"的分支，
     * 日历模式用户改一次课表就没有课堂铃，直到下一次冷启动 / 开机 / 兜底 Worker。
     * 撤应用内闹钟那半件事本来就归 [BackgroundSync.rescheduleReminders] 的日历分支管
     * （它撤完返回 false，包装随即续排）。
     */
    @Test
    fun uiPathRearsTheBellsInBothReminderModes() {
        val body = balancedBlock(withoutComments(read(SCHEDULE_VIEW_MODEL_FILE)), "private suspend fun afterDataChangedInternal()")

        assertTrue(
            "改数据后的收尾不再无条件补排课堂铃（日历模式那半边的「只撤不排」回来了）：\n$body",
            body.contains("BackgroundSync.rescheduleRemindersAndBells(app)"),
        )
        assertEquals("收尾里续排被走了不止一遍：\n$body", 1, occurrences(body, "rescheduleRemindersAndBells("))
        assertFalse("收尾还在自己撤闹钟（判据只该有一份实现）：\n$body", body.contains("cancelAll("))
        assertFalse("收尾还在按提醒模式分叉：\n$body", body.contains("ReminderMode"))
        assertFalse(
            "收尾还在读 prefs 问提醒模式（那个结论已经不需要了：两种模式同一条路）：\n$body",
            body.contains("getSharedPreferences("),
        )
    }

    /**
     * 设置页那两枚课堂开关不再按提醒模式置灰（ai/T15 第三枚）：它们改的就是
     * [com.buaa.schedule.reminder.ClassProgressScheduler.rescheduleNextWindow] 开头读的那两枚
     * pref，而那份读**与提醒模式无关** —— 置灰 + 「『系统日历提醒』模式下不生效」的文案
     * 合起来等于把用户唯一能关掉这条链的入口藏掉，还告诉他这条链没在工作
     * （`PREF_CLASS_PROGRESS` 默认 true，于是"日历模式 + 课堂铃在跑"是默认状态）。
     */
    @Test
    fun classSwitchesStayEditableAndHonestInCalendarMode() {
        // 顺序只能是"先在原始源码上按括号配平切段，再对这一小段抹注释"，两个方向都换不得：
        // - 整份文件先抹注释：withoutComments 不认字符串字面量，SettingsScreen.kt 第 1167 行那个
        //   写在字面量里的「斜杠紧跟星号」（壁纸选择器的 image MIME 通配）会让它一路吞到下一个
        //   块注释结尾，1474 行往后的真代码整段消失，锚点直接找不到（就是这条守卫红过的原因，
        //   坑本身写在 [mainJavaSources] 的注释里）；
        // - 改走 blankCommentsAndLiterals：它连**字面量内容**一起抹掉，下面那句 "不生效" 与那两行
        //   正常文案就成了恒真的空守卫（本项目专门盯过"命中了但恒真"的形状）。
        // 切段之后只抹这一小段的注释是必要的：那段解释性注释自己就引了一句「文案还写着"不生效"」，
        // 不抹掉它，第一条 summary 断言会误红。这两段里没有写在字面量里的块注释开头两个字符
        // （切完逐字核过：classProgress 段 1474-1502、dnd 段 1503-1560，段内花括号各自配平，
        // 也没有别处那种字面量里的星号斜杠组合），于是 withoutComments 在这里只碰行注释。
        val settings = read(SETTINGS_SCREEN_FILE)
        val progress = withoutComments(balancedBlock(settings, "item(key = \"classProgress\") {"))
        val dnd = withoutComments(balancedBlock(settings, "item(key = \"dnd\") {"))

        assertFalse("课堂铃那枚开关又按提醒模式置灰了：\n$progress", progress.contains("enabled ="))
        assertFalse("勿扰那枚开关又按提醒模式置灰了：\n$dnd", dnd.contains("enabled ="))
        assertFalse(
            "课堂铃那枚开关的 summary 又长出「日历模式下不生效」那种假话：\n$progress",
            progress.contains("不生效"),
        )
        assertFalse("勿扰那枚开关的 summary 同上：\n$dnd", dnd.contains("不生效"))
        assertTrue(
            "那两行正常文案被顺手删了（开关说的是它自己做的事）：\n$progress",
            progress.contains("上课期间显示一条带倒计时的常驻通知"),
        )
        assertTrue("勿扰那行正常文案不在了：\n$dnd", dnd.contains("上课期间开启勿扰，下课后自动恢复"))
        assertTrue("课堂铃那枚开关不再写 PREF_CLASS_PROGRESS：\n$progress", progress.contains("PREF_CLASS_PROGRESS"))
        assertTrue("勿扰那枚开关不再写 PREF_DND：\n$dnd", dnd.contains("PREF_DND"))
    }

    // ---- ④ 全仓库计数 ----------------------------------------------------------

    /**
     * `ClassProgressDnd.restore(` 的调用点仍是已知那 6 处、一处不多一处不少
     * （口径与 [com.buaa.schedule.reminder.BootDndSelfHealDecisionTest] 里那条同名的守卫一致：
     * ai/T15 三枚提交都不碰 restore，这里只钉"没被顺手挪"）。
     */
    @Test
    fun restoreCallSitesAreStillTheKnownSix() {
        val expected = mapOf(
            "com/buaa/schedule/reminder/ClassProgressReceiver.kt" to 1,  // 下课铃 ACTION_END
            "com/buaa/schedule/reminder/ClassProgressScheduler.kt" to 2, // 带判据的清理 + cancelAll
            "com/buaa/schedule/reminder/CourseFluidService.kt" to 1,
            "com/buaa/schedule/reminder/LiveClassResyncer.kt" to 1,
            "com/buaa/schedule/ui/settings/SettingsScreen.kt" to 1,
        )
        val actual = mainJavaSources()
            .associate { (relative, text) -> relative to occurrences(text, "ClassProgressDnd.restore(") }
            .filterValues { it > 0 }

        assertFalse(
            "BootReceiver 又出现了 restore 调用点（ai/T12 已经把它换成 selfCheck）：$actual",
            actual.containsKey(BOOT_RECEIVER_FILE),
        )
        assertEquals("restore 的调用点集合变了：$actual", expected, actual)
        assertEquals("restore 的调用点总数不再是 6 处", 6, expected.values.sum())
    }

    /**
     * 那轮全量窗口搜索的调用点集合没涨 —— 本卡明令不许在日历模式那一步复用
     * [com.buaa.schedule.reminder.ReminderScheduler.shouldTakeDownClassProgress] 那份重判据。
     */
    @Test
    fun fullWindowSearchDidNotGrowASecondCallSite() {
        val actual = mainJavaSources()
            .associate { (relative, text) ->
                relative to linesContaining(text, "planNextClassWindow(")
                    .count { !it.contains("fun planNextClassWindow(") }
            }
            .filterValues { it > 0 }

        assertFalse(
            "BackgroundSync 里出现了第二轮全量窗口搜索（纯白付：紧接着的续排还要再搜一遍）：\n$actual",
            actual.containsKey(BACKGROUND_SYNC_FILE),
        )
        assertEquals(
            "planNextClassWindow 的调用点集合变了（多一处 = 同族判据又长一份）：$actual",
            mapOf(
                "com/buaa/schedule/reminder/ClassProgressScheduler.kt" to 1,
                "com/buaa/schedule/reminder/LiveClassResyncer.kt" to 1,
                "com/buaa/schedule/reminder/ReminderScheduler.kt" to 1,
                "com/buaa/schedule/widget/WidgetCommon.kt" to 2,
            ),
            actual,
        )
    }

    /** 「日历模式要不要撤课堂铃」这个结论全仓库只此一处实现 */
    @Test
    fun cancelAllCriterionLivesInExactlyOnePlace() {
        val files = mainJavaSources()
            .filter { (_, text) -> text.contains("shouldCancelAllInCalendarMode(") }
            .map { (relative, _) -> relative }

        assertEquals(
            "「日历模式该不该撤课堂铃」长出了第二份实现：$files",
            listOf(BACKGROUND_SYNC_FILE),
            files,
        )
    }

    // ---- 源码核对工具（抄 BootDndSelfHealDecisionTest）--------------------------

    /**
     * [BackgroundSync.rescheduleReminders] 里日历模式那条早退分支（含它自己的花括号）：
     * 三件要 Context 的副作用在这里**注入**给编排本体，"注的是哪两本尊、prefs 读几遍"归它钉。
     */
    private fun calendarModeBranch(): String =
        balancedBlock(withoutComments(read(BACKGROUND_SYNC_FILE)), "if (!usesInAppReminders(context)) {")

    /**
     * [BackgroundSync.cleanUpInCalendarMode] 的编排本体（含花括号）：
     * 判据、两步的**先后**、以及"整块仍然吞异常 + 那一个报告标签"都在这里，②那组行为断言跑的
     * 就是这一份，③这组形状守卫钉的是"生产接线真的调了它、而且注对了样"。
     */
    private fun calendarCleanupSeam(): String =
        balancedBlock(withoutComments(read(BACKGROUND_SYNC_FILE)), "internal fun cleanUpInCalendarMode(")

    private fun read(relative: String): String {
        val file = File(findMainJavaDir(), relative)
        assertTrue("找不到 ${file.path}：文件挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText()
    }

    /**
     * 全量扫主源码（抹注释 + 抹字面量内容）：整份文件级计数用。
     *
     * 这里不能复用 [withoutComments]：`ui/settings/SettingsScreen.kt` 里有写在**字符串字面量里**
     * 的块注释开头两个字符，会被它当成块注释、把后面的代码整段吞掉
     * （口径抄 `WakeLockTimeoutFloorTest.blankCommentsAndLiterals`）。
     */
    private fun mainJavaSources(): List<Pair<String, String>> {
        val root = findMainJavaDir()
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("${root.path} 下一个 .kt 都没有，路径不对", files.isNotEmpty())
        return files.map { it.relativeTo(root).path.replace('\\', '/') to blankCommentsAndLiterals(it.readText()) }
    }

    /** 抹注释、也抹字符串/字符字面量的**内容**（长度与换行位置不变）：同 BootDndSelfHealDecisionTest 那份 */
    private fun blankCommentsAndLiterals(src: String): String {
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

                src.startsWith("\"\"\"", i) -> {
                    val end = src.indexOf("\"\"\"", i + 3).let { if (it < 0) out.size else it + 3 }
                    for (k in (i + 3) until (end - 3).coerceAtLeast(i + 3)) if (out[k] != '\n') out[k] = ' '
                    i = end
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
                    for (k in (i + 1) until (j - 1).coerceAtLeast(i + 1)) out[k] = ' '
                    i = j
                }

                else -> i++
            }
        }
        return String(out)
    }

    /**
     * 从 [signature] 处那个左括号起配平到对应的右括号（含），返回整段。
     *
     * [signature] 以 `{` 结尾时按它自己配平（那条 `if` 分支 / 那个 lambda），否则（函数签名）
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

    /** 失败信息用：把含 [needle] 的代码行挑出来（注释已被抹成空白，不会污染） */
    private fun linesContaining(source: String, needle: String): List<String> =
        source.lines().map { it.trim() }.filter { it.isNotEmpty() && it.contains(needle) }

    private companion object {
        const val BACKGROUND_SYNC_FILE = "com/buaa/schedule/widget/BackgroundSync.kt"
        const val CLASS_PROGRESS_SCHEDULER_FILE = "com/buaa/schedule/reminder/ClassProgressScheduler.kt"
        const val BOOT_RECEIVER_FILE = "com/buaa/schedule/reminder/BootReceiver.kt"
        const val SCHEDULE_VIEW_MODEL_FILE = "com/buaa/schedule/ui/ScheduleViewModel.kt"
        const val SETTINGS_SCREEN_FILE = "com/buaa/schedule/ui/settings/SettingsScreen.kt"

        /**
         * 报告口那个标签：生产与这里必须同名，否则闸门数不到这一格。
         * ai/T15 收工之前这里比的是复刻体自己塞进去的值（恒真），现在比的是
         * [BackgroundSync.cleanUpInCalendarMode] 真的报出来的那个标签。
         */
        const val CLEANUP_LABEL = "cleanUpInCalendarMode"

        /** 判据声明的锚点（改过名这条守卫要跟着改） */
        const val SHOULD_CANCEL_ALL_DECLARATION = "fun shouldCancelAllInCalendarMode("
    }
}
