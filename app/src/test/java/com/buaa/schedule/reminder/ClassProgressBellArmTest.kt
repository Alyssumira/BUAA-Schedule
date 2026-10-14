package com.buaa.schedule.reminder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 病灶 B 的判据表：课堂铃排失败必须**被看见**，而且不许被读成成功。
 *
 * 钉的是 [ClassProgressScheduler.armStartBell] 与 [ClassProgressScheduler.armEndBell]
 * 两枚档位本体 —— **零 android、零时钟**：三手 `set` 与留痕口全是参数（形状照
 * `rescheduleNextWindow` 那枚内部重载，本模块单测没有 Robolectric，真跑一次排铃要设备）。
 *
 * 两格病灶各有一侧账：
 * - ① 省电档那一手裸着 `runCatching` 直接 `return` ⇒ 失败既不进 logcat 也不进结论；
 * - ② `recoverCatching` 的返回值被丢掉 ⇒ 退回精确档再失败时读起来像"一次都没失败过"。
 *
 * 每格都同时断言三样：**结论 Boolean**、**留痕的条数与内容**、**哪几手 set 真被调过**
 * （调用次序是"成功之后不许再试兜底"那一判的证据，只断言结论的话会把多试一手放过去）。
 *
 * ## 第三家：勿扰看门狗那一族的排程（ai/T385）
 *
 * `ClassProgressDnd.armWatchdog` 改前是本表那两格病灶的**原形状**：两支失败各只念一句
 * "勿扰看门狗排程失败，只剩冷启动自愈"就走到函数末尾，既没有结论、也没有把"该补排"落到下一次起手
 * （那句"冷启动自愈"还是期限门控的，闹钟不存在时它只剩"下一次起手恰好已过期限"那一档）。
 * 改法照本表这两家：档位本体收成 [ClassProgressDnd.armWatchdogAttempt]（零 android、零时钟、
 * 三手 set + 落账口 + 留痕口全是参数）。看门狗那一族因此**多断言一样**：落账口收到几次、各收到什么值
 * —— "结论落没落账"是这一族的补救链的唯一入口，光断言结论会把"排上了不落账 / 落两次账"放过去。
 */
class ClassProgressBellArmTest {

    /** 一次跑的结论：排上没有、留痕了哪几句、试了哪几手 */
    private class Run(
        val armed: Boolean,
        val logs: List<String>,
        val tried: List<String>,
    )

    private fun starter(canExact: Boolean, fail: Set<String>): Run {
        val logs = mutableListOf<String>()
        val tried = mutableListOf<String>()
        val armed = ClassProgressScheduler.armStartBell(
            canExact = canExact,
            setPowerSave = {
                tried += "powerSave"
                if ("powerSave" in fail) throw Boom("省电档抛")
            },
            setAlarmClock = {
                tried += "alarmClock"
                if ("alarmClock" in fail) throw Boom("setAlarmClock 抛")
            },
            setExactAsFallback = {
                tried += "exactFallback"
                if ("exactFallback" in fail) throw Boom("退回精确档抛")
            },
            logFailure = { reason, _ -> logs += reason },
        )
        return Run(armed, logs, tried)
    }

    private fun ender(canExact: Boolean, fail: Set<String>): Run {
        val logs = mutableListOf<String>()
        val tried = mutableListOf<String>()
        val armed = ClassProgressScheduler.armEndBell(
            canExact = canExact,
            setExact = {
                tried += "exact"
                if ("exact" in fail) throw Boom("精确档抛")
            },
            setPowerSave = {
                tried += "powerSave"
                if ("powerSave" in fail) throw Boom("省电档抛")
            },
            logFailure = { reason, _ -> logs += reason },
        )
        return Run(armed, logs, tried)
    }

    private data class Cell(
        val name: String,
        val canExact: Boolean,
        val fail: Set<String>,
        val expectedArmed: Boolean,
        /** 期望的留痕条数：这是"失败不许零留痕"那格的正身 */
        val expectedLogs: Int,
        val expectedTried: List<String>,
        /** 留痕必须点名的字样（哪一枚铃、哪一档），否则取证时分不出档 */
        val expectedLogContains: List<String>,
    )

    private val startCells = listOf(
        Cell(
            name = "上课铃｜授权了 + setAlarmClock 一次成功 ⇒ 排上了、零留痕、不许再试兜底那一手",
            canExact = true,
            fail = emptySet(),
            expectedArmed = true,
            expectedLogs = 0,
            expectedTried = listOf("alarmClock"),
            expectedLogContains = emptyList(),
        ),
        Cell(
            name = "上课铃｜setAlarmClock 抛、退回精确档成功 ⇒ 排上了，但两句留痕都要在",
            canExact = true,
            fail = setOf("alarmClock"),
            expectedArmed = true,
            expectedLogs = 1,
            expectedTried = listOf("alarmClock", "exactFallback"),
            expectedLogContains = listOf("setAlarmClock 失败，退回精确档重排上课铃"),
        ),
        Cell(
            name = "上课铃｜setAlarmClock 与退回那一手都抛 ⇒ 必须判『没排上』+ 两句留痕（旧写法的第②格）",
            canExact = true,
            fail = setOf("alarmClock", "exactFallback"),
            expectedArmed = false,
            expectedLogs = 2,
            expectedTried = listOf("alarmClock", "exactFallback"),
            expectedLogContains = listOf(
                "setAlarmClock 失败，退回精确档重排上课铃",
                "退回精确档那一手也没排上课铃",
            ),
        ),
        Cell(
            name = "上课铃｜未授权 + 省电档成功 ⇒ 排上了、零留痕",
            canExact = false,
            fail = emptySet(),
            expectedArmed = true,
            expectedLogs = 0,
            expectedTried = listOf("powerSave"),
            expectedLogContains = emptyList(),
        ),
        Cell(
            name = "上课铃｜未授权 + 省电档抛 ⇒ 必须判『没排上』且必须留痕（旧写法的第①格：裸 runCatching）",
            canExact = false,
            fail = setOf("powerSave"),
            expectedArmed = false,
            expectedLogs = 1,
            expectedTried = listOf("powerSave"),
            // 后果要念出来：实况与自动勿扰都不会自己开始
            expectedLogContains = listOf("省电档排上课铃失败", "自动勿扰"),
        ),
    )

    private val endCells = listOf(
        Cell(
            name = "下课铃｜授权 + 精确档成功 ⇒ 排上了、零留痕、不许再试非精确档",
            canExact = true,
            fail = emptySet(),
            expectedArmed = true,
            expectedLogs = 0,
            expectedTried = listOf("exact"),
            expectedLogContains = emptyList(),
        ),
        Cell(
            name = "下课铃｜精确档抛、非精确档成功 ⇒ 排上了 + 一句留痕（ROM 省电策略那一档）",
            canExact = true,
            fail = setOf("exact"),
            expectedArmed = true,
            expectedLogs = 1,
            expectedTried = listOf("exact", "powerSave"),
            expectedLogContains = listOf("精确下课铃排程失败，退回非精确档"),
        ),
        Cell(
            name = "下课铃｜两手都抛 ⇒ 必须判『没排上』+ 两句留痕：勿扰挂着不解除这件事得留下证据",
            canExact = true,
            fail = setOf("exact", "powerSave"),
            expectedArmed = false,
            expectedLogs = 2,
            expectedTried = listOf("exact", "powerSave"),
            expectedLogContains = listOf("精确下课铃排程失败，退回非精确档", "非精确档也没排上下课铃"),
        ),
        Cell(
            name = "下课铃｜未授权 + 省电档成功 ⇒ 排上了、零留痕",
            canExact = false,
            fail = emptySet(),
            expectedArmed = true,
            expectedLogs = 0,
            expectedTried = listOf("powerSave"),
            expectedLogContains = emptyList(),
        ),
        Cell(
            name = "下课铃｜未授权 + 省电档抛 ⇒ 判『没排上』+ 留痕点名看门狗与冷启动那两条自愈路",
            canExact = false,
            fail = setOf("powerSave"),
            expectedArmed = false,
            expectedLogs = 1,
            expectedTried = listOf("powerSave"),
            expectedLogContains = listOf("省电档排下课铃失败", "勿扰"),
        ),
    )

    @Test
    fun startBellReportsEveryFailureAndNeverFakesSuccess() {
        for (cell in startCells) runCell(::starter, cell)
    }

    @Test
    fun endBellReportsEveryFailureAndNeverFakesSuccess() {
        for (cell in endCells) runCell(::ender, cell)
    }

    private fun runCell(run: (Boolean, Set<String>) -> Run, cell: Cell) {
        val actual = run(cell.canExact, cell.fail)

        val detail = "${cell.name}\n留痕=${actual.logs} 试过=${actual.tried} 结论=${actual.armed}"
        assertEquals("结论不对（$detail", cell.expectedArmed, actual.armed)
        assertEquals("留痕条数不对（$detail", cell.expectedLogs, actual.logs.size)
        assertEquals("试了哪几手不对（$detail", cell.expectedTried, actual.tried)
        for (fragment in cell.expectedLogContains) {
            assertTrue("留痕里没点名「$fragment」（$detail", actual.logs.any { it.contains(fragment) })
        }
        // 排上了却写了留痕 = 把成功说成失败；排失败却不写 = 吞掉。两边都不许出现
        if (cell.expectedArmed && cell.fail.isEmpty()) {
            assertTrue("全成功的一手都不该被记成失败（$detail", actual.logs.isEmpty())
        }
        if (!cell.expectedArmed) {
            assertFalse("判『没排上』的每一格都必须至少有一句留痕（$detail", actual.logs.isEmpty())
        }
    }

    /**
     * 留痕口拿到的是**那一手抛出来的同一个异常对象**，不是重新包装的、也不是 null。
     *
     * 取证时 `logcat -s ClassProgressScheduler` 那一行的栈顶就是这条链真正的死因；
     * 把异常吞成一句话或换成新对象，等于把 ROM 名与权限档位丢掉。
     */
    @Test
    fun logPortReceivesTheOriginalException() {
        val boom = Boom("ROM 省电策略收紧")
        val seen = mutableListOf<Pair<String, Throwable>>()
        val armed = ClassProgressScheduler.armEndBell(
            canExact = true,
            setExact = { throw boom },
            setPowerSave = { },
            logFailure = { reason, error -> seen += reason to error },
        )

        assertTrue("兜底那一手是成功的，结论必须是排上了", armed)
        assertEquals(1, seen.size)
        assertSame(boom, seen.first().second)
    }

    /** 三手都不抛时，两枚档位的结论必须都是"排上了"（反向对照：断言不是恒真） */
    @Test
    fun allSuccessIsStillTrueForBothBells() {
        assertTrue(starter(true, emptySet()).armed)
        assertTrue(starter(false, emptySet()).armed)
        assertTrue(ender(true, emptySet()).armed)
        assertTrue(ender(false, emptySet()).armed)
    }

    // ---- 第三家：勿扰看门狗的排程档位（ai/T385，病灶 = 两支失败无补救） ----------------

    /** 一次跑的结论：排上没有、留痕了哪几句、试了哪几手、落账口收到几次什么值 */
    private class WatchdogRun(
        val armed: Boolean,
        val logs: List<String>,
        val tried: List<String>,
        val marks: List<Boolean>,
    )

    private fun watchdog(canExact: Boolean, fail: Set<String>): WatchdogRun {
        val logs = mutableListOf<String>()
        val tried = mutableListOf<String>()
        val marks = mutableListOf<Boolean>()
        val armed = ClassProgressDnd.armWatchdogAttempt(
            canExact = canExact,
            setAlarmClock = {
                tried += "alarmClock"
                if ("alarmClock" in fail) throw Boom("setAlarmClock 抛")
            },
            setExact = {
                tried += "exact"
                if ("exact" in fail) throw Boom("退回精确档抛")
            },
            setPowerSave = {
                tried += "powerSave"
                if ("powerSave" in fail) throw Boom("省电档抛")
            },
            recordArmOutcome = { armed -> marks += armed },
            logFailure = { reason, _ -> logs += reason },
        )
        return WatchdogRun(armed, logs, tried, marks)
    }

    private data class WatchdogCell(
        val name: String,
        val canExact: Boolean,
        val fail: Set<String>,
        val expectedArmed: Boolean,
        /** 留痕条数：失败不许零留痕，成功不许留痕 */
        val expectedLogs: Int,
        val expectedTried: List<String>,
        /** 留痕必须点名的字样（哪一枚闹钟、哪一档、后果是什么） */
        val expectedLogContains: List<String>,
        /** 落账口收到的那一串值：每一次排程都必须**恰好**落一次账，值就是结论 */
        val expectedMarks: List<Boolean>,
    )

    private val watchdogCells = listOf(
        WatchdogCell(
            name = "看门狗｜授权 + setAlarmClock 一次成功 ⇒ 排上了、零留痕、不许再试兜底那一手、落账 true",
            canExact = true,
            fail = emptySet(),
            expectedArmed = true,
            expectedLogs = 0,
            expectedTried = listOf("alarmClock"),
            expectedLogContains = emptyList(),
            expectedMarks = listOf(true),
        ),
        WatchdogCell(
            name = "看门狗｜setAlarmClock 抛、退回精确档成功 ⇒ 排上了，但留痕要在、落账仍是 true",
            canExact = true,
            fail = setOf("alarmClock"),
            expectedArmed = true,
            expectedLogs = 1,
            expectedTried = listOf("alarmClock", "exact"),
            expectedLogContains = listOf("勿扰看门狗 setAlarmClock 失败，退回精确档"),
            expectedMarks = listOf(true),
        ),
        WatchdogCell(
            name = "看门狗｜两手都抛 ⇒ 必须判『没排上』+ 两句留痕 + 落账 false（改前第②格病灶：返回值被丢掉）",
            canExact = true,
            fail = setOf("alarmClock", "exact"),
            expectedArmed = false,
            expectedLogs = 2,
            expectedTried = listOf("alarmClock", "exact"),
            expectedLogContains = listOf(
                "勿扰看门狗 setAlarmClock 失败，退回精确档",
                "勿扰看门狗退回精确档那一手也没排上",
            ),
            expectedMarks = listOf(false),
        ),
        WatchdogCell(
            name = "看门狗｜未授权（canScheduleExactAlarms 为假那一支）+ 省电档成功 ⇒ 排上了、零留痕、落账 true",
            canExact = false,
            fail = emptySet(),
            expectedArmed = true,
            expectedLogs = 0,
            expectedTried = listOf("powerSave"),
            expectedLogContains = emptyList(),
            expectedMarks = listOf(true),
        ),
        WatchdogCell(
            name = "看门狗｜未授权 + 省电档抛 ⇒ 判『没排上』+ 留痕点名『补排』这一补救路（改前第①格：裸 runCatching）",
            canExact = false,
            fail = setOf("powerSave"),
            expectedArmed = false,
            expectedLogs = 1,
            expectedTried = listOf("powerSave"),
            // 后果要念出来：这次没有闹钟，只剩下一次起手补排
            expectedLogContains = listOf("勿扰看门狗省电档没排上", "补排"),
            expectedMarks = listOf(false),
        ),
    )

    @Test
    fun watchdogArmReportsEveryFailureAndNeverFakesSuccess() {
        for (cell in watchdogCells) {
            val actual = watchdog(cell.canExact, cell.fail)
            val detail = "${cell.name}\n留痕=${actual.logs} 试过=${actual.tried} 落账=${actual.marks} 结论=${actual.armed}"

            assertEquals("结论不对（$detail", cell.expectedArmed, actual.armed)
            assertEquals("留痕条数不对（$detail", cell.expectedLogs, actual.logs.size)
            assertEquals("试了哪几手不对（$detail", cell.expectedTried, actual.tried)
            assertEquals(
                "落账口的次数/值不对 —— 每一次排程必须**恰好**落一次账、值就是结论（$detail",
                cell.expectedMarks,
                actual.marks,
            )
            for (fragment in cell.expectedLogContains) {
                assertTrue("留痕里没点名「$fragment」（$detail", actual.logs.any { it.contains(fragment) })
            }
            // 落账口与结论必须同进同出：只落"排上了"不落别的（或反之）都是补救链断掉
            assertEquals("落账值必须与结论同值（$detail", listOf(cell.expectedArmed), actual.marks)
            if (cell.expectedArmed && cell.fail.isEmpty()) {
                assertTrue("全成功的一手都不该被记成失败（$detail", actual.logs.isEmpty())
            }
            if (!cell.expectedArmed) {
                assertTrue("判『没排上』的每一格都必须有留痕、且落账必须是 false（$detail", actual.logs.isNotEmpty())
            }
        }
    }

    /**
     * 失败那一手交给留痕口的是**它自己抛出来的那个异常对象**，不是重新包装的、也不是 null。
     *
     * 看门狗这一族要的就是这一格：装机上"哪一档被 ROM 掐了"只能从栈顶读，
     * 换成新对象或吞成一句话就等于把权限档位与 ROM 名丢掉。
     */
    @Test
    fun watchdogLogPortReceivesTheOriginalException() {
        val boom = Boom("HyperOS 收紧精确闹钟")
        val seen = mutableListOf<Pair<String, Throwable>>()
        val marks = mutableListOf<Boolean>()
        val armed = ClassProgressDnd.armWatchdogAttempt(
            canExact = true,
            setAlarmClock = { throw boom },
            setExact = { },
            setPowerSave = { },
            recordArmOutcome = { marks += it },
            logFailure = { reason, error -> seen += reason to error },
        )

        assertTrue("兜底那一手是成功的，结论必须是排上了", armed)
        assertEquals(1, seen.size)
        assertSame(boom, seen.first().second)
        assertEquals("排上了也要落账一次 true（撤旗那一路的证据）", listOf(true), marks)
    }

    /**
     * 反向对照：三手都不抛时两档都判"排上了"，而**表里必须同时有排上了与没排上两档** ——
     * 否则上面那些"结论 = true"的断言是恒真的（本卡最怕的就是假绿）。
     */
    @Test
    fun watchdogTableIsNotAMonotone() {
        assertTrue("全成功不该判没排上", watchdog(true, emptySet()).armed)
        assertTrue("全成功不该判没排上", watchdog(false, emptySet()).armed)
        assertTrue(
            "表里必须有一格『排上了』，否则『没排上』那些断言量不出差别",
            watchdogCells.any { it.expectedArmed },
        )
        assertTrue(
            "表里必须有一格『没排上』，否则整个档位本体可能是恒 true（改前那两支就是把失败读成没失败）",
            watchdogCells.any { !it.expectedArmed },
        )
        assertEquals(
            "『没排上』的格数与落 false 的格数必须一字不差（补救链不能有一格漏落账）",
            watchdogCells.count { !it.expectedArmed },
            watchdogCells.count { it.expectedMarks == listOf(false) },
        )
    }

    private class Boom(message: String) : RuntimeException(message)
}
