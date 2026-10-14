package com.buaa.schedule.domain.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T378③：`domain/session/RestoreBackoffWindowGate` 的表驱动单测（纯 JVM，零设备，**零取钟**）。
 *
 * 两枚题眼各钉一格：
 * 1. **单调钟倒退那一档不许被念成「刚刚失败过」**（改前是不夹下限的差值比较：回拨 ⇒ 差为负 ⇒
 *    恒早退，而那一档全函数唯一一句话不说）；
 * 2. **「从没失败过」不许借刻度起点那一枚零**（台账 #496：换单调钟之后拿 `0L` 当初始值，
 *    开机后一分钟内的第一次恢复就被判成「一分钟前刚失败过」而整段跳过）。
 *
 * 期望值全部手写。第三格把**改前的那一形**（`now - last < window` 与 `= 0L`）在测试里照抄一遍当
 * 反证：同两枚读数，改前后结论必须不同 —— 那一格红了说明内核只是把旧判据换了个名字。
 */
class RestoreBackoffWindowGateTest {

    private data class Row(
        val why: String,
        val now: Long,
        val last: Long,
        val window: Long,
        val expect: String,
    )

    private fun tiers(table: List<Row>): List<String> = table.map {
        RestoreBackoffWindowGate.decide(it.now, it.last, it.window).javaClass.simpleName
    }

    @Test
    fun `退避窗口四档逐枚给结论`() {
        val table = listOf(
            Row(
                why = "题眼①：本进程从没失败过（哨兵 -1L），而这台机器刚开机 1 秒 —— 必须放行。" +
                    "改前那种「初始值 = 刻度起点」在这里会被窗口判据压掉（台账 #496 复发）",
                now = 1_000L,
                last = RestoreBackoffSentinel.NEVER_FAILED_MILLIS,
                window = 60_000L,
                expect = "GrantedNeverFailed",
            ),
            Row(
                why = "从没失败过，机器已开机一小时：同一档（哨兵那一格与窗口刻度无关）",
                now = 3_600_000L,
                last = RestoreBackoffSentinel.NEVER_FAILED_MILLIS,
                window = 60_000L,
                expect = "GrantedNeverFailed",
            ),
            Row(
                why = "失败过、还在 60 秒退避窗口之内：压掉（这一档的去重语义不许被修坏 —— " +
                    "onStart 每次回前台都敲一次 restore，不压就是反复新建 WebView 直到 OOM）",
                now = 500_000L,
                last = 460_000L,
                window = 60_000L,
                expect = "SuppressedWithinWindow",
            ),
            Row(
                why = "恰好出窗（差值 == 窗口）：出窗那一档，放行 —— 边界归「出窗」不归「窗口内」",
                now = 520_000L,
                last = 460_000L,
                window = 60_000L,
                expect = "GrantedAfterWindow",
            ),
            Row(
                why = "早已出窗：放行，并把差值交回给调用点写日志",
                now = 900_000L,
                last = 460_000L,
                window = 60_000L,
                expect = "GrantedAfterWindow",
            ),
            Row(
                why = "题眼②：读数倒退（now < last）。递进来的是单调钟，同进程内结构上不该发生，" +
                    "真发生就是调用点递了别的尺 —— 单独一档且**偏向放行**；" +
                    "改前这一枚落在「小于窗口」那一支里 ⇒ 恒早退、且一句日志都不留",
                now = 10_000L,
                last = 460_000L,
                window = 60_000L,
                expect = "GrantedClockInverted",
            ),
            Row(
                why = "窗口为 0（调用点把窗口夹没了）：任何真实读数都算出窗，放行",
                now = 460_000L,
                last = 460_000L,
                window = 0L,
                expect = "GrantedAfterWindow",
            ),
            Row(
                why = "窗口为 0 而读数仍是哨兵：短路与窗口无关，走「从没失败过」那一档",
                now = 460_000L,
                last = RestoreBackoffSentinel.NEVER_FAILED_MILLIS,
                window = 0L,
                expect = "GrantedNeverFailed",
            ),
            Row(
                why = "Long 下溢侧的读数（倒退到刻度之外很远）：仍是倒退档，不许被折成「刚失败过」",
                now = 0L,
                last = 460_000L,
                window = 60_000L,
                expect = "GrantedClockInverted",
            ),
        )
        assertEquals(
            "逐枚档位对不上（实到 ${tiers(table)}）：",
            table.map { it.expect },
            tiers(table),
        )
        for ((index, expect) in table.map { it.expect }.withIndex()) {
            assertEquals("${table[index].why} ⇒ 档位：", expect, tiers(table)[index])
        }
    }

    @Test
    fun `四档互不相混 且倒退与窗口内不共用一句话`() {
        val never = RestoreBackoffWindowGate.decide(1_000L, RestoreBackoffSentinel.NEVER_FAILED_MILLIS, 60_000L)
        val within = RestoreBackoffWindowGate.decide(500_000L, 460_000L, 60_000L)
        val inverted = RestoreBackoffWindowGate.decide(10_000L, 460_000L, 60_000L)
        val after = RestoreBackoffWindowGate.decide(900_000L, 460_000L, 60_000L)
        val names = listOf(never, within, inverted, after).map { it.javaClass.simpleName }
        assertEquals("四枚输入必须交出四枚不同档位（实到 $names）：", 4, names.distinct().size)
        assertNotEquals(
            "「读数倒退」与「窗口之内的真重复」必须分档（实到 ${names[2]} vs ${names[1]}）：" +
                "并档就是改前那一形 —— 负差值落进「小于窗口」那一支，把递错了尺念成「刚刚失败过」",
            within.javaClass.simpleName,
            inverted.javaClass.simpleName,
        )
        assertNotEquals(
            "「从没失败过」必须与「出窗放行」分档：并档就把「没有上一次」与「上一次很久以前」又折成一句话",
            never.javaClass.simpleName,
            after.javaClass.simpleName,
        )
        val granted = listOf(never, inverted, after).filter {
            it.javaClass.simpleName.startsWith("Granted")
        }
        assertEquals("四档里放行必须恰好 3 档（压掉只许窗口内那一档）：", 3, granted.size)
    }

    /**
     * 反证（在册口径：不许拿「grep 没匹配到」当全绿，也不许拿「新内核自答自」当证据）：
     * 把**改前那一形**照抄进测试，同一批读数下两头的结论必须逐枚分家；
     * 而本内核给出的读数字段（差值）必须与手写算术逐枚相等。
     */
    @Test
    fun `改前那一形在这两枚读数上会说谎 新内核不会`() {
        val window = 60_000L

        // 改前的两形（逐字照抄基点盘面，只是取的是本地函数，不读任何设备面）
        fun suppressedUnderOldWallClockRuler(now: Long, last: Long): Boolean = now - last < window

        // 病①：墙上钟被往回拨 5 分钟 —— 改前的判据把「再试一次」压掉，而且一句话不说
        val nowAfterRollback = 460_000L - 300_000L
        val lastFailureBeforeRollback = 460_000L
        assertTrue(
            "反证必须成立：改前那一形在回拨后的读数上判「还在窗口内」（差值 = " +
                (nowAfterRollback - lastFailureBeforeRollback) + "，负数恒小于 $window）—— " +
                "这一格若为 false，说明本测试的靶子写错了，下面那条比较就没有对手",
            suppressedUnderOldWallClockRuler(nowAfterRollback, lastFailureBeforeRollback),
        )
        assertEquals(
            "同一枚读数，新内核必须给「倒退 ⇒ 偏向放行」那一档，而不是「刚刚失败过」：",
            "GrantedClockInverted",
            RestoreBackoffWindowGate.decide(nowAfterRollback, lastFailureBeforeRollback, window)
                .javaClass.simpleName,
        )

        // 病②：初始值与刻度起点共用那一格 —— 开机 1 秒内的第一次恢复被改前那一形压掉
        val oldInitialValue = 0L
        val oneSecondAfterBoot = 1_000L
        assertTrue(
            "反证必须成立：改前「初始值 = 0L」配单调钟，开机后 1 秒的第一次恢复会被判成" +
                "「$window 毫秒前刚失败过」（差值 = ${oneSecondAfterBoot - oldInitialValue}）",
            suppressedUnderOldWallClockRuler(oneSecondAfterBoot, oldInitialValue),
        )
        assertEquals(
            "同一枚读数换成具名哨兵之后必须走「从没失败过」那一档（短路排在窗口判据之前）：",
            "GrantedNeverFailed",
            RestoreBackoffWindowGate.decide(oneSecondAfterBoot, RestoreBackoffSentinel.NEVER_FAILED_MILLIS, window)
                .javaClass.simpleName,
        )

        // 出窗那一档交回的差值必须与手写算术相等（内核不许自己算错刻度）
        val after = RestoreBackoffWindowGate.decide(900_000L, 460_000L, window)
        assertTrue(
            "出窗那一档必须把差值原样交回调用点（日志要说得出等了多久），实到 $after：",
            after is RestoreBackoffWindow.GrantedAfterWindow &&
                after.elapsedSinceFailureMillis == 900_000L - 460_000L,
        )
        val within = RestoreBackoffWindowGate.decide(500_000L, 460_000L, window)
        assertTrue(
            "压掉那一档必须把「已经等了多久」与「窗口本身」两枚都交回（句子要念得出两个数），实到 $within：",
            within is RestoreBackoffWindow.SuppressedWithinWindow &&
                within.elapsedSinceFailureMillis == 40_000L && within.windowMillis == window,
        )
    }

    @Test
    fun `哨兵是负数 与单调钟刻度取不出同一枚读数`() {
        assertEquals(
            "台账 #496：哨兵要单立具名负数，不许照抄在册先例之外的 0L —— " +
                "elapsedRealtime() 结构上取不出负数，所以「从没失败过」与任何真实读数不可混：",
            -1L,
            RestoreBackoffSentinel.NEVER_FAILED_MILLIS,
        )
        assertNotEquals(
            "哨兵不许与刻度起点同格（那就是本卡病②本身）：",
            0L,
            RestoreBackoffSentinel.NEVER_FAILED_MILLIS,
        )
    }
}
