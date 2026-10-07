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

    private class Boom(message: String) : RuntimeException(message)
}
