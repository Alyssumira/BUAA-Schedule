package com.buaa.schedule.reminder

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 开机重建（BOOT_COMPLETED / MY_PACKAGE_REPLACED）第一步到底该不该把勿扰恢复回去。
 *
 * 实测到的故障（emulator-5554，2026-09-18，一节 20:40–21:25 的课正在进行、
 * `dnd_during_class=true`、课前提醒全关；唤醒锁 `boot_rebuild` 开始后 14ms）：
 *
 * ```
 * 20:50:47.071  WakeLocks 进入 tag=boot_rebuild
 * 20:50:47.085  ZenModeController: Zen mode setting changed to 0   ← 开机那步无条件 restore
 * 20:50:47.133  ReminderScheduler: 正处在课堂窗口内：本轮不撤销课堂铃与勿扰，交给续排链
 * 20:50:47.266  WakeLocks 跑完 tag=boot_rebuild elapsed=195ms held=true
 * 20:50:52.308  ZenModeController: Zen mode setting changed to 2   ← 5.2s 后续排链排出已过期的上课铃，重新 enter
 * ```
 *
 * 「重启把闹钟全清了、遗留记录再没有下课铃来恢复」只对**真正遗留**的记录（期限已过 /
 * 旧版本没写期限）成立；正在上课时那条 `dnd_saved_interruption_filter` 记录连同看门狗
 * 是当下正要用的自愈凭据，抢先恢复每收一次重建广播就把用户静音放开 5 秒
 * （覆盖安装 `MY_PACKAGE_REPLACED` 走同一接收器，抖动一样）。审计 §2.9 / ai/T11
 * 那一族的第二处。
 *
 * 改法：那一步改走 [ClassProgressDnd.selfCheck] 的期限判据 —— 判据与编排收在
 * [ClassProgressDnd.restoreIfStale]（不接 Context、恢复动作收成注入的 lambda，
 * 形状抄 [ReminderScheduler.takeDownClassProgressIfNeeded]），开机侧不许长第二份
 * 「有记录 + 期限」的比较式。「有记录但期限未到」交给紧随其后的续排链收口：
 * 没有课在进行时它必然走到带判据的清理并把 `restore` 调下去（四类情形逐一核对过，
 * 路径写在 [com.buaa.schedule.reminder.BootReceiver] 那一步的注释里）。
 *
 * 生产接线（开机那一步真的经由 selfCheck 走）JVM 跑不到（`restore` / `selfCheck`
 * 要 Context，本模块没有 Robolectric，android.jar 里全是抛 "not mocked" 的桩），
 * 按源码形状核对；写法照抄 [ClassProgressCleanupDecisionTest] 的
 * `balancedBlock` / `withoutComments` / `findMainJavaDir` —— 找不到文件就抛，
 * 不用 assumeTrue 跳过（跳过的守卫等于没守卫）。
 *
 * ## 第二本账（ai/T385，甲档）：排程失败之后"下一次起手该不该补排那只看门狗"
 *
 * 上面那一族把开机那一步收成"只在期限已过时动手"是对的（实测抖动就在未过期那一档），
 * 但它同时暴露出一格没有补救的洞：`armWatchdog` 三手 `set` 全抛时**闹钟根本不存在**，
 * 而 `selfCheck` 只有"期限已过"才会动手 ⇒ 静默没有上界（下一节上课铃还会把期限往后覆写，
 * 净效果反向变长）。改法是把"上一次没排上"落成一格 prefs 事实、由 `selfCheck` 起手补排一次。
 *
 * 于是本文件多一族格子：[rearmGateReadsNoClockAndNoPrefsOfItsOwn] 与那六格表驱动量的是
 * [ClassProgressDnd.watchdogRearmDue]（零 Context、零 android、四枚输入全注入），
 * 形状与本文件那族 `restoreIfStale` 逐字同规格；两判的**互补关系**由
 * [theTwoVerdictsAreNeverBothAtOnce] 穷举 12 格钉住 ——
 * 甲档不许把上面那族"期限未到不许恢复"的不变量削弱一分：
 * 补排只在期限**未到**时发生，期限一到动手的仍然是那一次 `restore`。
 */
class BootDndSelfHealDecisionTest {

    private val minute = 60_000L

    /** 跑一次 seam：返回注入的恢复动作被调了几遍（生产接线传的就是"留痕 + restore(context)"） */
    private fun restoreCalls(hasSavedFilter: Boolean, deadline: Long, nowMillis: Long): Int {
        var calls = 0
        val acted = ClassProgressDnd.restoreIfStale(
            hasSavedFilter = hasSavedFilter,
            deadline = deadline,
            nowMillis = nowMillis,
            onStale = { calls++ },
        )
        // 结论与动作必须同进同出：只报"动手了"不动手（或反之）都是接线错了
        assertEquals("返回值与 onStale 的调用次数不一致", calls > 0, acted)
        return calls
    }

    // ---- 负例：三类真正遗留的场景，行为与改动前一条都不许差 --------------------

    /** 有记录 + 期限已过（下课铃与看门狗都随重启没了）：照旧当场自愈 */
    @Test
    fun expiredDeadlineStillRestores() {
        val now = 1_789_000_000_000L

        assertEquals("期限已过的遗留记录开机必须照旧恢复", 1, restoreCalls(true, now - minute, now))
    }

    /** 旧版本没写期限（deadline=0）：那种记录本来就没人会来恢复，按"已过期限"处理，照旧动手 */
    @Test
    fun missingDeadlineStillRestores() {
        val now = 1_789_000_000_000L

        assertEquals("缺期限的旧版本记录开机必须照旧恢复", 1, restoreCalls(true, 0L, now))
    }

    /** 没记录（本次压根没进过勿扰）：三种期限形态下都必须 no-op，"不动用户设置"的不变量不许削弱 */
    @Test
    fun noRecordIsNoOpForEveryDeadlineState() {
        val now = 1_789_000_000_000L

        for (deadline in listOf(now - minute, 0L, now + minute)) {
            assertEquals("无记录时绝不能动用户自己的勿扰设置", 0, restoreCalls(false, deadline, now))
        }
    }

    // ---- 修复要买到的那一条 ----------------------------------------------------

    /**
     * 正在上课（实测那一节：20:50 开机、课到 21:25、期限 = 下课 + 30min = 21:55）：
     * 开机这步一次恢复都不许发生 —— 抢先 restore 就是把勿扰放开 5 秒，
     * 而且抹掉的记录与看门狗正是这一节课当下要靠的自愈凭据，重新进入被 ROM 吞掉就救不回来。
     */
    @Test
    fun freshRecordWithinDeadlineIsNotRestoredAtBoot() {
        val classEnd = 1_789_000_000_000L
        val deadline = classEnd + 30 * minute
        val bootAt = classEnd - 35 * minute

        assertEquals(
            "课还在上（期限未到）时开机步骤不许恢复，交给紧随其后的续排链收口",
            0,
            restoreCalls(true, deadline, bootAt),
        )
    }

    /** 边界：期限恰好到点算"已过"（与 selfCheck 原来那条 `deadline > now` 早退逐字同口径） */
    @Test
    fun deadlineExactlyNowRestores() {
        val now = 1_789_000_000_000L

        assertEquals(1, restoreCalls(true, now, now))
    }

    /** 结论只认传进去的时钟：同一条记录绕着期限翻面，判据自己不读钟 */
    @Test
    fun conclusionFollowsTheInjectedClock() {
        val deadline = 1_789_000_000_000L

        assertEquals("期限未到却恢复了", 0, restoreCalls(true, deadline, deadline - minute))
        assertEquals("期限已过却没恢复", 1, restoreCalls(true, deadline, deadline + minute))
    }

    /** seam 不读钟、不碰 prefs：三个输入全是注入的，JVM 侧"恢复动作被调了几遍"才钉得住 */
    @Test
    fun seamReadsNoClockAndNoPrefsOfItsOwn() {
        val body = balancedBlock(withoutComments(readMainSource(CLASS_PROGRESS_DND_FILE)), "internal fun restoreIfStale(")

        for (sneak in listOf("System.currentTimeMillis(", "LocalDateTime.now(", "Instant.", "Date(", "Clock.", "prefs(", "getSharedPreferences(")) {
            assertFalse("判据自己读钟或自己摸状态（$sneak）：三个输入必须全是注入进来的：\n$body", body.contains(sneak))
        }
    }

    // ---- 补排门控（ai/T385 甲档）：排程失败之后"该不该再排一次" -------------------

    /** 一趟自愈的两个结论：该不该补排那只闹钟、硬超时那把尺动手了几次 */
    private class SelfCheckRun(
        val rearmDue: Boolean,
        val restoreCalls: Int,
    )

    /**
     * 把两判放在同一次跑里量：[ClassProgressDnd.watchdogRearmDue] 的期限门控**复用**
     * [ClassProgressDnd.restoreIfStale] 那一把尺（空动作取结论），所以"补排"与"硬超时恢复"
     * 天生互补 —— 这一手写法本身就要被钉：两判同时动手（补一只立刻该撤销的闹钟）与
     * 两判同时不动手（闹钟没了、恢复也没做）都是这一族的失效形状。
     */
    private fun selfCheckRun(
        hasSavedFilter: Boolean,
        rearmPending: Boolean,
        deadline: Long,
        nowMillis: Long,
    ): SelfCheckRun {
        var calls = 0
        val rearm = ClassProgressDnd.watchdogRearmDue(
            hasSavedFilter = hasSavedFilter,
            rearmPending = rearmPending,
            deadline = deadline,
            nowMillis = nowMillis,
        )
        val stale = ClassProgressDnd.restoreIfStale(
            hasSavedFilter = hasSavedFilter,
            deadline = deadline,
            nowMillis = nowMillis,
            onStale = { calls++ },
        )
        assertEquals("restoreIfStale 的返回值与 onStale 的调用次数不一致", calls > 0, stale)
        return SelfCheckRun(rearm, calls)
    }

    /** 旗在 + 有记录 + 期限未到：这一格就是甲买到的那一格 —— 排程失败后下一次起手补排一次 */
    @Test
    fun pendingFlagWithFreshDeadlineIsRearmed() {
        val now = 1_789_000_000_000L

        val run = selfCheckRun(hasSavedFilter = true, rearmPending = true, deadline = now + minute, nowMillis = now)
        assertTrue("排程失败旗在而期限未到，这一趟就该补排那只闹钟", run.rearmDue)
        assertEquals("期限未到就绝不该按硬超时动手（动手 = 把正在生效的勿扰放开）", 0, run.restoreCalls)
    }

    /** 没记录（本次压根没进过勿扰）时，旗在不在、期限什么形态都不许补排，也不许恢复 */
    @Test
    fun noRecordNeverRearmsEvenWithPendingFlag() {
        val now = 1_789_000_000_000L

        for (deadline in listOf(now - minute, 0L, now + minute)) {
            val run = selfCheckRun(hasSavedFilter = false, rearmPending = true, deadline = deadline, nowMillis = now)
            assertFalse("没进过勿扰就不要动用户设置，也不要多付一次排程 binder：$deadline", run.rearmDue)
            assertEquals("没进过勿扰就不该有硬超时恢复：$deadline", 0, run.restoreCalls)
        }
    }

    /**
     * 旗不在（上一次确实排上了）：不许每次起手都顶替重写那只闹钟 ——
     * `setAlarmClock` 是同 requestCode 重排，白闪一次状态栏图标、也多给 ROM 一次吞的机会。
     */
    @Test
    fun absentFlagLeavesTheScheduledAlarmAlone() {
        val now = 1_789_000_000_000L

        val run = selfCheckRun(hasSavedFilter = true, rearmPending = false, deadline = now + minute, nowMillis = now)
        assertFalse("没有失败旗就没有要补的东西", run.rearmDue)
        assertEquals("期限未到就不该动手恢复", 0, run.restoreCalls)
    }

    /**
     * 期限已过（含缺失 0 那一档）：动手的是硬超时恢复，不是补排 ——
     * 补一只立刻就该撤销的闹钟 = 多一次唤醒、恢复还晚了一整节课。
     */
    @Test
    fun passedDeadlineGoesToTheStaleRestoreNotToRearm() {
        val now = 1_789_000_000_000L

        for (deadline in listOf(now - minute, now, 0L)) {
            val run = selfCheckRun(hasSavedFilter = true, rearmPending = true, deadline = deadline, nowMillis = now)
            assertFalse("期限（$deadline）已过/缺失时该恢复而不是补排", run.rearmDue)
            assertEquals("期限（$deadline）已过/缺失时硬超时必须动手", 1, run.restoreCalls)
        }
    }

    /** 结论只认传进去的时钟：同一条"记录 + 旗"绕着期限翻面，补排判据自己不读钟 */
    @Test
    fun rearmConclusionFollowsTheInjectedClock() {
        val deadline = 1_789_000_000_000L

        assertTrue(
            "期限未到却判不补排",
            selfCheckRun(hasSavedFilter = true, rearmPending = true, deadline = deadline, nowMillis = deadline - minute).rearmDue,
        )
        assertFalse(
            "期限恰好到点还去补排（那一档归硬超时恢复）",
            selfCheckRun(hasSavedFilter = true, rearmPending = true, deadline = deadline, nowMillis = deadline).rearmDue,
        )
        assertFalse(
            "期限已过还去补排",
            selfCheckRun(hasSavedFilter = true, rearmPending = true, deadline = deadline, nowMillis = deadline + minute).rearmDue,
        )
    }

    /**
     * 全组合穷举（2 枚记录态 × 2 枚旗态 × 3 种期限形态 = 12 格）：两判**永不同时**动手，
     * 而"补排"那一档在整张表里恰好只出现一次 —— 折松一格（旗不参与、或期限不参与）这一格立刻红。
     */
    @Test
    fun theTwoVerdictsAreNeverBothAtOnce() {
        val now = 1_789_000_000_000L
        val deadlines = listOf(now - minute, now, now + minute)
        var rearmCells = 0

        val offenders = buildList {
            for (hasSaved in listOf(true, false)) {
                for (flag in listOf(true, false)) {
                    for (deadline in deadlines) {
                        val run = selfCheckRun(hasSaved, flag, deadline, now)
                        if (run.rearmDue && run.restoreCalls > 0) {
                            add("记录=$hasSaved 旗=$flag 期限=$deadline：补排与硬超时恢复同时动手")
                        }
                        if (run.rearmDue) rearmCells++
                    }
                }
            }
        }
        assertEquals("两判同一次动手（闹钟补上了又立刻被恢复拆掉）：$offenders", emptyList<String>(), offenders)
        assertEquals(
            "「有记录 + 旗在 + 期限未到」这一档在全组合里恰一枚（0 枚＝补排链断了，>1 枚＝期限门控或旗被摘）",
            1,
            rearmCells,
        )
    }

    /** 补排判据同样不读钟、不摸 prefs、不碰平台：四枚输入全是注入的标量 */
    @Test
    fun rearmGateReadsNoClockAndNoPrefsOfItsOwn() {
        val body = balancedBlock(withoutComments(readMainSource(CLASS_PROGRESS_DND_FILE)), "internal fun watchdogRearmDue(")

        for (sneak in listOf("System.currentTimeMillis(", "LocalDateTime.now(", "Instant.", "Date(", "Clock.", "prefs(", "getSharedPreferences(")) {
            assertFalse("补排判据自己读钟或自己摸状态（$sneak）：四枚输入必须全是注入进来的：\n$body", body.contains(sneak))
        }
    }

    // ---- 判据只此一份 + 生产接线（源码形状）-----------------------------------

    /**
     * 「有记录 + 期限」的比较式全文件只有一处（seam 里那条 `deadline >`），
     * `selfCheck` 委托它，开机那侧一个原始比较式都不许出现（接口契约：口径只此一份）。
     */
    @Test
    fun deadlinePredicateLivesInExactlyOnePlace() {
        val source = withoutComments(readMainSource(CLASS_PROGRESS_DND_FILE))
        val seam = balancedBlock(source, "internal fun restoreIfStale(")
        val selfCheck = balancedBlock(source, "fun selfCheck(context: Context)")

        assertEquals(
            "ClassProgressDnd.kt 里长出了第二份「期限过了没有」的比较式：口径只许 seam 那一条",
            1,
            occurrences(source, "deadline >"),
        )
        assertTrue("唯一的比较式不在 seam 里：\n$seam", seam.contains("deadline >"))
        assertTrue(
            "selfCheck 不再经由 seam（回去自己写比较式了？）：\n$selfCheck",
            selfCheck.contains("restoreIfStale("),
        )

        val boot = withoutComments(readMainSource(BOOT_RECEIVER_FILE))
        for (raw in listOf(
            "dnd_saved_interruption_filter",
            "dnd_restore_deadline",
            "KEY_SAVED_FILTER",
            "KEY_DND_DEADLINE",
            "deadline >",
            // ai/T385③ 只往这张表里**加**词条（收紧不放宽）：补排那一格事实也是 ClassProgressDnd
            // 自己的书账，开机侧既不许读它、也不许自己写它 —— 那等于绕过 selfCheck 另起一枚入口。
            "dnd_watchdog_rearm_pending",
            "KEY_WATCHDOG_REARM_PENDING",
            "watchdogRearmDue",
            "armWatchdog",
        )) {
            assertFalse("BootReceiver 自己又写了一遍判据（$raw）：只许复用 ClassProgressDnd 那侧的入口", boot.contains(raw))
        }
    }

    /**
     * 开机那一步 = 一道 selfCheck，不留裸 restore，且排在续排链之前。
     *
     * 这条钉的是"改在了地方"：判据写对但调用点又补一句无条件 restore，
     * 上面那些行为断言全绿、设备上的抖动照旧（红证 2 就是把它换回裸 restore，这条必须红）。
     */
    @Test
    fun bootStepRoutesThroughSelfCheckBeforeTheRescheduleChain() {
        val source = withoutComments(readMainSource(BOOT_RECEIVER_FILE))
        val step = balancedBlock(source, "step(\"dndSelfCheck\") {")

        assertTrue(
            "勿扰那一步不再经由期限判据（selfCheck）：\n$step",
            step.contains("ClassProgressDnd.selfCheck(context)"),
        )
        assertFalse(
            "开机步骤里还留着裸 restore：正在上课时每收一次重建广播就被静音放开 5 秒\n$source",
            source.contains("ClassProgressDnd.restore("),
        )
        assertEquals(
            "BootReceiver 对 ClassProgressDnd 只许引用 selfCheck 这一个入口：\n$source",
            1,
            occurrences(source, "ClassProgressDnd."),
        )

        // 安全网是紧随其后的那条重建链：顺序翻面，"期限未到"的那类记录就没人收口了
        val dndStep = source.indexOf("step(\"dndSelfCheck\") {")
        // T297（#536 F2）：这一步开始吃入口那一份读载体（第三枚实参形态是命名实参 snapshot =）
        val reschedule = source.indexOf("rescheduleRemindersAndBells(context, snapshot = chainSnapshot)")
        assertTrue(
            "勿扰步骤不再排在 rescheduleRemindersAndBells 之前：安全网链条（BootReceiver 注释里那四条路径）失效",
            dndStep in 0 until reschedule,
        )
    }

    /**
     * ai/T385（甲档）那一族的**接线形状**：失败结论只在一处落账、补排只长在 selfCheck 起手那一处。
     *
     * 本格存在的理由与 `bootStepRoutesThroughSelfCheckBeforeTheRescheduleChain` 同一条：
     * 判据（[ClassProgressDnd.watchdogRearmDue] 与 [ClassProgressDnd.armWatchdogAttempt]）已经全被
     * 表驱动逐格量到了，但"**接线到底注没注对、旗到底落没落**"这件事 JVM 跑不到
     * （`getSharedPreferences` 是抛 "not mocked" 的桩）⇒ 只能按源码形状核对。五格各拦一种"看着对"：
     * - 排程入口的**册子**：`armWatchdog(` 全 main 只长在 `ClassProgressDnd.kt` 一枚文件里、四枚
     *   （定义 + enter + 新帧那一步 + selfCheck 补排）—— 第五枚在别处 = 又起了一条排程链；
     * - **旗的读写各恰一处**：`putBoolean(KEY_WATCHDOG_REARM_PENDING` 与
     *   `getBoolean(KEY_WATCHDOG_REARM_PENDING` 各一枚 ⇒ 不会出现"两处写一处读"那种永远读不到的旗；
     * - selfCheck 的体里补排那一步恰一处，且整趟只读一只钟一枚（补排门控与硬超时门控同一读数）；
     * - restore 成功那一格把旗与另外两格一起撤（不留化石）；
     * - cancelWatchdog 清旗**排在取服务之前**：`?: return` 那一手也得把书账清干净，
     *   否则用户关掉功能后留下一枚永远补排不完的旗。
     */
    @Test
    fun armFailureIsMarkedOnceAndSelfCheckIsTheOnlyRearmDoor() {
        val source = withoutComments(readMainSource(CLASS_PROGRESS_DND_FILE))
        val selfCheck = balancedBlock(source, "fun selfCheck(context: Context)")
        val restore = balancedBlock(source, "fun restore(context: Context)")
        val cancel = balancedBlock(source, "fun cancelWatchdog(context: Context)")

        val armRoster = mainJavaSources()
            .associate { (relative, text) -> relative to occurrences(text, "armWatchdog(") }
            .filterValues { it > 0 }
        assertEquals(
            "排看门狗这件事全仓只许长在 ClassProgressDnd.kt 那一枚文件里，且恰四枚" +
                "（定义 + enter + alignWatchdogToNewFrame + selfCheck 起手补排）：$armRoster",
            mapOf(CLASS_PROGRESS_DND_FILE to 4),
            armRoster,
        )
        assertEquals(
            "「上一次没排上」那一格的**写入点**只许一处（recordWatchdogArmOutcome 里 armed 为假那一支）：" +
                "两处写就是两本账，改一处忘一处会得到\"闹钟排上了却留着旗\"那种每次起手都重排的状态",
            1,
            occurrences(source, "putBoolean(KEY_WATCHDOG_REARM_PENDING"),
        )
        assertEquals(
            "那一格的**读取点**只许一处（selfCheck 起手）：别处读它就是在册外的地方另起一条补排链",
            1,
            occurrences(source, "getBoolean(KEY_WATCHDOG_REARM_PENDING"),
        )
        assertEquals(
            "selfCheck 的体里补排那一步恰一处（armWatchdog(context, deadline)，吃的是 prefs 里那一格期限）：\n$selfCheck",
            1,
            occurrences(selfCheck, "armWatchdog(context, deadline)"),
        )
        assertTrue("selfCheck 不再走 watchdogRearmDue 那一枚判据（自己摊三判了？）：\n$selfCheck", selfCheck.contains("watchdogRearmDue("))
        assertEquals(
            "selfCheck 整趟只许读一只钟一枚（补排门控与硬超时门控共用同一个读数；跨秒那一瞬两判各读各的" +
                "就会一次补错排、一次漏恢复）：\n实到 " + occurrences(selfCheck, "System.currentTimeMillis(") + " 处",
            1,
            occurrences(selfCheck, "System.currentTimeMillis("),
        )
        assertTrue("restore 成功却不撤补排旗（留下一枚化石，下一次起手白补一只闹钟）：\n$restore", restore.contains("remove(KEY_WATCHDOG_REARM_PENDING)"))
        assertTrue(
            "cancelWatchdog 不再清补排旗：用户关掉功能/清空课表之后 selfCheck 会补排一只刚刚被撤掉的闹钟\n$cancel",
            cancel.contains("remove(KEY_WATCHDOG_REARM_PENDING)"),
        )
        assertTrue(
            "清旗那一步必须排在取 AlarmManager **之前**：`?: return` 那一手也要把书账清干净\n$cancel",
            cancel.indexOf("remove(KEY_WATCHDOG_REARM_PENDING)") in 0 until cancel.indexOf("getSystemService("),
        )
    }

    /**
     * `restore()` 自身的语义与全部既有调用点都不被这张卡碰：
     * 它的唯一判据仍是「有没有记录过原状态」（不看期限、不走 seam）；
     * 全仓库 `ClassProgressDnd.restore(` 的调用点仍是那 6 处、一处不多一处不少。
     */
    @Test
    fun restoreSemanticsAndCallSitesAreUntouched() {
        val source = withoutComments(readMainSource(CLASS_PROGRESS_DND_FILE))
        val restore = balancedBlock(source, "fun restore(context: Context)")
        assertTrue(
            "restore 的进门判据（有没有记录过原状态）被改了：\n$restore",
            restore.contains("if (!prefs.contains(KEY_SAVED_FILTER)) return"),
        )
        assertFalse("restore 开始走期限 seam：它的语义不许被这张卡改\n$restore", restore.contains("restoreIfStale"))
        assertFalse("restore 开始自己读钟：唯一判据是有没有记录", restore.contains("System.currentTimeMillis("))
        assertFalse("restore 体内写出了期限比较式", restore.contains("deadline >"))

        val expected = mapOf(
            "com/buaa/schedule/reminder/ClassProgressReceiver.kt" to 1,  // 下课铃 ACTION_END
            "com/buaa/schedule/reminder/ClassProgressScheduler.kt" to 2, // 带判据的清理 :314 + cancelAll :523
            "com/buaa/schedule/reminder/CourseFluidService.kt" to 1,
            "com/buaa/schedule/reminder/LiveClassResyncer.kt" to 1,
            "com/buaa/schedule/ui/settings/SettingsScreen.kt" to 1,
        )
        val actual = mainJavaSources()
            .associate { (relative, text) -> relative to occurrences(text, "ClassProgressDnd.restore(") }
            .filterValues { it > 0 }

        assertFalse(
            "BootReceiver 又出现了 restore 调用点（这张卡只把它换成 selfCheck）：$actual",
            actual.containsKey(BOOT_RECEIVER_FILE),
        )
        assertEquals("restore 的调用点集合变了（既有调用点一律不许被这张卡改动）：$actual", expected, actual)
    }

    // ---- 源码核对工具（照抄 ClassProgressCleanupDecisionTest）------------------

    private fun readMainSource(relative: String): String {
        val file = File(findMainJavaDir(), relative)
        assertTrue("找不到 ${file.path}：文件挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText()
    }

    /**
     * 全量扫主源码（已抹注释与字面量）：钉"restore 调用点只此几处"用的，找不到一个 .kt 就抛。
     *
     * 这里不能复用 [withoutComments]：SettingsScreen.kt:1167 那种写在**字符串字面量里**的
     * 块注释开头两个字符，会被它当成块注释、把后面的代码整段吞掉。口径抄
     * `WakeLockTimeoutFloorTest.blankCommentsAndLiterals`（先抹字面量内容再抹注释）。
     */
    private fun mainJavaSources(): List<Pair<String, String>> {
        val root = findMainJavaDir()
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("${root.path} 下一个 .kt 都没有，路径不对", files.isNotEmpty())
        return files.map { it.relativeTo(root).path.replace('\\', '/') to blankCommentsAndLiterals(it.readText()) }
    }

    /** 抹注释、也抹字符串/字符字面量的**内容**（长度与换行位置不变）：同 WakeLockTimeoutFloorTest 那份 */
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
     * [signature] 以 `{` 结尾时按它自己配平（那条 `if` 分支 / lambda 块），否则（函数签名）
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
        const val CLASS_PROGRESS_DND_FILE = "com/buaa/schedule/reminder/ClassProgressDnd.kt"
        const val BOOT_RECEIVER_FILE = "com/buaa/schedule/reminder/BootReceiver.kt"
    }
}
