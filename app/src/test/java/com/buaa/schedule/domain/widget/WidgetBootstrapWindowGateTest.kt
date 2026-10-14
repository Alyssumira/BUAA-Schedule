package com.buaa.schedule.domain.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T368②：`WidgetBootstrapWindowGate.decide` 的表驱动逐格账（纯 JVM，无 Robolectric、无设备、不读钟）。
 *
 * 四枚事实（`elapsedRealtime` 读数、上一次补注册的读数、窗口、「本进程补注册过没有」）全部当参数递进来，
 * 判据体内一个字都不读 —— 所以这一张表能在 JVM 里逐格跑（与在册先例那一族同口径）。
 *
 * 钉的是本卡病灶那一格与它的**两侧**：
 * - 朝「压得过宽」扭一刀 —— 把「确实从没领过 ⇒ 放行」那条短路摘掉，或者把它挪到窗口比较后面 ⇒
 *   第 1／2／3 格（本次进程第一枚补注册恰好落在开机后一分钟内）当场从 `GrantedFirstBootstrap`
 *   掉进 `SuppressedAsDuplicate`，零点闹钟与那条 12 小时兜底整轮不注册 ⇒ 红；
 * - 朝「压得过窄」扭一刀 —— 把窗口比较从「严格小于」挪成「小于等于」（或把窗口当 0）⇒
 *   第 5／6／7 格（同一次广播里六家 Provider 各进来一次的那一路）当场变成放行，
 *   那道时间闸白夹，每广播 25 次 binder 调用回来 ⇒ 红。
 * 边界三格（`window-1` / `window` / `window+1`）就是这两刀各自的落点，方向钉死在
 * 「恰好等于窗口」那一格是**放行**（改前的 `<` 就是这个语义，不许顺手改严）。
 *
 * 第 2 格是本卡题眼：`lastClaimedAt` 还是初始哨兵、而 `now` 落在窗口之内。改前那一形里初始值就是
 * 刻度起点那一枚零、`elapsedRealtime()` 开机那一刻正好从 0 起走，两者同格 ⇒ 这一格今天必须是
 * `GrantedFirstBootstrap`，谁把哨兵换回「刻度起点上的数字」它就当场变回 `SuppressedAsDuplicate`。
 * 「开机后第 0 毫秒」那一格（第 1 格）是把同一件事钉到最极端：读数与旧初始值同值也必须发放。
 */
class WidgetBootstrapWindowGateTest {

    private data class Cell(
        val label: String,
        val nowElapsedRealtime: Long,
        val lastClaimedAt: Long,
        val windowMillis: Long,
        val hasEverBootstrapped: Boolean,
        val expectedTier: String,
        val claimedAt: Long? = null,
        val elapsedSinceLastClaim: Long? = null,
    )

    private companion object {
        const val NEVER = WidgetBootstrapSentinel.NEVER_BOOTSTRAPPED_MILLIS
        const val WINDOW = 60_000L
    }

    @Test
    fun `逐格 五档判据的表驱动账`() {
        val cells = listOf(
            // ── 题眼：初始哨兵 ⇒ 从没领过，读数落在开机刻度之内也放行（改前正是这几格被压掉）──
            Cell(
                "开机后第 0 毫秒的第一枚补注册 读数与旧初始值同值也必须发放",
                0L, NEVER, WINDOW, false,
                "GrantedFirstBootstrap", claimedAt = 0L,
            ),
            Cell(
                "开机后 30 秒桌面恢复广播的第一枚补注册 不再被当成刚注册过",
                30_000L, NEVER, WINDOW, false,
                "GrantedFirstBootstrap", claimedAt = 30_000L,
            ),
            Cell(
                "开机后 59 秒 599 毫秒 窗口边界内侧的第一枚照样发放",
                59_999L, NEVER, WINDOW, false,
                "GrantedFirstBootstrap", claimedAt = 59_999L,
            ),
            Cell(
                "开机后整一分钟的第一枚 出不出窗都走首次那一档 短路排在窗口之前",
                60_000L, NEVER, WINDOW, false,
                "GrantedFirstBootstrap", claimedAt = 60_000L,
            ),
            Cell(
                "开机三小时之后才第一次补注册 走的还是首次那一档",
                900_000L, NEVER, WINDOW, false,
                "GrantedFirstBootstrap", claimedAt = 900_000L,
            ),
            // ── 去重本业：领过而窗口之内 ⇒ 压掉（同一次广播里六家 Provider 各进来一次）──
            Cell(
                "领过而窗口之内 第二家的广播被压掉",
                100_000L, 50_000L, WINDOW, true,
                "SuppressedAsDuplicate", elapsedSinceLastClaim = 50_000L,
            ),
            Cell(
                "同一毫秒两枚投递 差为 0 也在窗口之内",
                60_000L, 60_000L, WINDOW, true,
                "SuppressedAsDuplicate", elapsedSinceLastClaim = 0L,
            ),
            Cell(
                "边界内侧 差为窗口减一 压掉",
                65_998L, 5_999L, WINDOW, true,
                "SuppressedAsDuplicate", elapsedSinceLastClaim = 59_999L,
            ),
            // ── 边界两格：方向钉死（恰等窗口 = 放行，不许顺手改严）──
            Cell(
                "边界恰等 差为整窗口 放行",
                65_999L, 5_999L, WINDOW, true,
                "GrantedAfterWindow", claimedAt = 65_999L, elapsedSinceLastClaim = 60_000L,
            ),
            Cell(
                "边界外侧 差比窗口多一毫秒 放行",
                66_000L, 5_999L, WINDOW, true,
                "GrantedAfterWindow", claimedAt = 66_000L, elapsedSinceLastClaim = 60_001L,
            ),
            Cell(
                "上一枚是昨天的读数 早已出窗 照常补注册",
                2_000_000L, 5_000L, WINDOW, true,
                "GrantedAfterWindow", claimedAt = 2_000_000L, elapsedSinceLastClaim = 1_995_000L,
            ),
            // ── 钟倒进取（本卡裁的方向：偏向放行，见内核 KDoc 那条「为什么与另一族相反」）──
            Cell(
                "单调钟倒退 递进来的读数比存的那枚小 照做并要说实话",
                10_000L, 50_000L, WINDOW, true,
                "GrantedClockInverted",
            ),
            Cell(
                "倒退一格也要走到同一档 差为负一毫秒",
                49_999L, 50_000L, WINDOW, true,
                "GrantedClockInverted",
            ),
            // ── 哨兵自相矛盾（旗与读数互相打脸，两枚方向各一格）──
            Cell(
                "旗说领过而读数还是初始哨兵 打脸档 照做且告警",
                30_000L, NEVER, WINDOW, true,
                "SentinelContradiction",
            ),
            Cell(
                "旗说没领过而读数是一枚真实时刻 同一档 反向那格",
                30_000L, 12_345L, WINDOW, false,
                "SentinelContradiction",
            ),
        )
        for ((index, cell) in cells.withIndex()) {
            val window = WidgetBootstrapWindowGate.decide(
                cell.nowElapsedRealtime,
                cell.lastClaimedAt,
                cell.windowMillis,
                cell.hasEverBootstrapped,
            )
            assertEquals(
                "第 ${index + 1} 格「${cell.label}」实到档位 ${tier(window)}",
                cell.expectedTier,
                tier(window),
            )
            assertEquals(
                "第 ${index + 1} 格「${cell.label}」写回哨兵的那一枚读数",
                cell.claimedAt,
                claimedAtOf(window),
            )
            assertEquals(
                "第 ${index + 1} 格「${cell.label}」念出来的那段「距上次领取」",
                cell.elapsedSinceLastClaim,
                elapsedOf(window),
            )
        }
    }

    @Test
    fun `题眼格与去重格是两种结论 不许念成同一句话`() {
        val first = WidgetBootstrapWindowGate.decide(30_000L, NEVER, WINDOW, false)
        val duplicate = WidgetBootstrapWindowGate.decide(100_000L, 50_000L, WINDOW, true)
        val after = WidgetBootstrapWindowGate.decide(2_000_000L, 5_000L, WINDOW, true)
        val inverted = WidgetBootstrapWindowGate.decide(10_000L, 50_000L, WINDOW, true)
        val contradiction = WidgetBootstrapWindowGate.decide(30_000L, NEVER, WINDOW, true)
        val againAfterWindow = WidgetBootstrapWindowGate.decide(66_000L, 5_999L, WINDOW, true)
        val tiers = listOf(first, duplicate, after, inverted, contradiction, againAfterWindow)
            .map { tier(it) }
        assertEquals(
            "五枚不同结论要在同一张表里各占一格（实到 $tiers）—— 少一枚就是有档折进了别的档，" +
                "守卫那些「每档各有一条可读痕迹」的计数格就不再是本卡的尺",
            5,
            tiers.distinct().size,
        )
        assertEquals(
            "题眼格（初始哨兵 + 窗口之内）今天必须是首次领取那一档，实到 ${tiers[0]}：" +
                "变成 SuppressedAsDuplicate 就是台账 #496 复发",
            "GrantedFirstBootstrap",
            tiers[0],
        )
        assertNotEquals(
            "「从没领过被误判成刚领过」与「去重要压的那一路」必须是两档（实到 ${tiers[0]} 对 ${tiers[1]}）：" +
                "合成一句就是本卡第 2 问点名的那种分档失败",
            tiers[0],
            tiers[1],
        )
        assertEquals(
            "窗口之内的真重复那一格今天仍必须是压掉档，实到 ${tiers[1]}：去重语义不许被修坏",
            "SuppressedAsDuplicate",
            tiers[1],
        )
    }

    @Test
    fun `改前那一形只在这些格上给出另一个答案`() {
        // 改前的判据（本次进程第一枚补注册，lastBootstrapAt 的初始值就是刻度起点那一枚零）：
        // `if (now - 0L < WINDOW) return` ⇒ 咽掉。这里把它原样算一遍，只为了数出差集。
        val oldSuppressed = { now: Long -> now - 0L < WINDOW }
        val fresh = listOf(0L, 1L, 30_000L, 59_999L, 60_000L, 60_001L, 120_000L, 900_000L)
        val diff = fresh.filter { now ->
            val newWindow = WidgetBootstrapWindowGate.decide(now, NEVER, WINDOW, false)
            oldSuppressed(now) != tier(newWindow).startsWith("Suppressed")
        }
        assertEquals(
            "改前/改后的差集必须恰好是「开机后一分钟内的那几格」（实到 $diff）：" +
                "差集变大就是有格今天被无故翻面，变小就是哨兵那一格又与刻度同值",
            listOf(0L, 1L, 30_000L, 59_999L),
            diff,
        )
        for (now in listOf(60_000L, 60_001L, 120_000L, 900_000L)) {
            assertTrue(
                "出窗之后的第一枚改前改后都是放行（$now 那格今天给出 " +
                    tier(WidgetBootstrapWindowGate.decide(now, NEVER, WINDOW, false)) + "）",
                tier(WidgetBootstrapWindowGate.decide(now, NEVER, WINDOW, false))
                    .startsWith("Granted"),
            )
        }
    }

    @Test
    fun `判据体内不读钟 同一组读数两次必得同一档与同一写回值`() {
        val first = WidgetBootstrapWindowGate.decide(30_000L, NEVER, WINDOW, false)
        val second = WidgetBootstrapWindowGate.decide(30_000L, NEVER, WINDOW, false)
        assertEquals("同一组递进来的读数必须给出同一结论（判据里没有现读的钟）", tier(first), tier(second))
        assertEquals(
            "而且连写回哨兵的那一枚读数都要一模一样",
            claimedAtOf(first),
            claimedAtOf(second),
        )
        assertEquals(
            "哨兵今天还是 -1L（实到 ${WidgetBootstrapSentinel.NEVER_BOOTSTRAPPED_MILLIS}）：" +
                "把它改成 0L 就是「刻度起点与初始值同格」直接回潮。" +
                "⚠️ 这一枚是「单调钟取不出负数」那一侧的**专属值**，不是一枚 elapsedRealtime 读数，" +
                "报错消息与断言里都不许把它当成时刻念",
            -1L,
            WidgetBootstrapSentinel.NEVER_BOOTSTRAPPED_MILLIS,
        )
    }

    private fun tier(window: WidgetBootstrapWindow): String = window.javaClass.simpleName

    private fun claimedAtOf(window: WidgetBootstrapWindow): Long? = when (window) {
        is WidgetBootstrapWindow.GrantedFirstBootstrap -> window.claimedAtElapsedRealtime
        is WidgetBootstrapWindow.GrantedAfterWindow -> window.claimedAtElapsedRealtime
        else -> null
    }

    private fun elapsedOf(window: WidgetBootstrapWindow): Long? = when (window) {
        is WidgetBootstrapWindow.GrantedAfterWindow -> window.elapsedSinceLastClaimMillis
        is WidgetBootstrapWindow.SuppressedAsDuplicate -> window.elapsedSinceLastClaimMillis
        else -> null
    }
}
