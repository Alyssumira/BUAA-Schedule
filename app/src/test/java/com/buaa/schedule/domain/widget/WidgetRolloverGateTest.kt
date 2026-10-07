package com.buaa.schedule.domain.widget

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T256②：`WidgetRolloverGate.decide` 的表驱动逐格账（纯 JVM，无 Robolectric、无设备、不读钟）。
 *
 * 钉的是本卡病灶那一格与它的**两侧**：
 * - 朝「压得过宽」扭一刀 —— 把判据顺序里「从没领过」那条短路摘掉 ⇒ 第 1 格
 *   （`lastClaimedAt` 仍是初始哨兵、`now` 又落在窗口之内）当场从 `GrantedFirstClaim`
 *   掉进 `SuppressedAsDuplicate`，开机后窗口之内到达的那条真跨天广播又被整段咽掉 ⇒ 红；
 * - 朝「压得过窄」扭一刀 —— 把窗口比较的边界从「严格小于」挪成「小于等于」（或把窗口当 0）⇒
 *   第 4 格（窗口之内的重复那一路）当场从 `SuppressedAsDuplicate` 变成 `GrantedAfterWindow`，
 *   零点那两条广播又各跑一遍全学期快照与六家重绘 ⇒ 红。
 * 边界三格（`window-1` / `window` / `window+1`）就是这两刀各自的落点，方向钉死在
 * 「恰好等于窗口」那一格是**放行**（改前的 `<` 就是这个语义，不许顺手改严）。
 *
 * 第 1 格是本卡题眼：`lastClaimedAt == 初始值` 且 `now < window`。改前那一形里
 * 初始值就是 `0L`、而 `elapsedRealtime()` 从开机那一刻起走，两者同格 ⇒ 这一格今天必须是
 * `GrantedFirstClaim`，谁把哨兵换回「刻度起点上的数字」它就当场变回 `SuppressedAsDuplicate`。
 */
class WidgetRolloverGateTest {

    private data class Cell(
        val label: String,
        val actionKind: WidgetRolloverActionKind,
        val nowElapsedRealtime: Long,
        val lastClaimedAt: Long,
        val windowMillis: Long,
        val hasEverRefreshed: Boolean,
        val expectedTier: String,
        val claimedAt: Long? = null,
        val elapsedSinceLastClaim: Long? = null,
    )

    private companion object {
        const val NEVER = WidgetRolloverSentinel.NEVER_CLAIMED_MILLIS
        const val WINDOW = 120_000L

        val ROLLOVER_ACTION = WidgetRolloverActionKind.SystemDateChanged
    }

    @Test
    fun `逐格 六档判据的表驱动账`() {
        val cells = listOf(
            // ── 题眼：初始哨兵 + 窗口之内 ⇒ 必须放行（改前正是这一格被压掉）──
            Cell(
                "开机后 45 秒的第一条 DATE_CHANGED 不再被当成刚刷过",
                WidgetRolloverActionKind.SystemDateChanged, 45_000L, NEVER, WINDOW, false,
                "GrantedFirstClaim", claimedAt = 45_000L,
            ),
            Cell(
                "开机后第 0 毫秒 读数与旧初始值同值那一格也放行",
                WidgetRolloverActionKind.SystemDateChanged, 0L, NEVER, WINDOW, false,
                "GrantedFirstClaim", claimedAt = 0L,
            ),
            Cell(
                "开机后 119 秒 窗口边界内侧仍放行",
                WidgetRolloverActionKind.SelfMidnightAlarm, 119_999L, NEVER, WINDOW, false,
                "GrantedFirstClaim", claimedAt = 119_999L,
            ),
            Cell(
                "从没领过而读数早已出窗 走的还是第一次领那一档",
                WidgetRolloverActionKind.SystemTimezoneChanged, 900_000L, NEVER, WINDOW, false,
                "GrantedFirstClaim", claimedAt = 900_000L,
            ),
            // ── 去重本业：领过且窗口之内 ⇒ 压掉 ──
            Cell(
                "领过而窗口之内 系统广播压掉自排闹钟那一条",
                WidgetRolloverActionKind.SelfMidnightAlarm, 100_000L, 20_000L, WINDOW, true,
                "SuppressedAsDuplicate", elapsedSinceLastClaim = 80_000L,
            ),
            Cell(
                "同一毫秒两条 差为 0 也在窗口之内",
                WidgetRolloverActionKind.SystemTimeChanged, 60_000L, 60_000L, WINDOW, true,
                "SuppressedAsDuplicate", elapsedSinceLastClaim = 0L,
            ),
            // ── 边界三格：方向钉死 ──
            Cell(
                "边界内侧 差为窗口减一 压掉",
                WidgetRolloverActionKind.SystemDateChanged, 120_000L, 1L, WINDOW, true,
                "SuppressedAsDuplicate", elapsedSinceLastClaim = 119_999L,
            ),
            Cell(
                "边界恰等 差为整窗口 放行",
                WidgetRolloverActionKind.SystemDateChanged, 120_001L, 1L, WINDOW, true,
                "GrantedAfterWindow", claimedAt = 120_001L, elapsedSinceLastClaim = 120_000L,
            ),
            Cell(
                "边界外侧 差为窗口加一 放行",
                WidgetRolloverActionKind.SystemDateChanged, 120_002L, 1L, WINDOW, true,
                "GrantedAfterWindow", claimedAt = 120_002L, elapsedSinceLastClaim = 120_001L,
            ),
            // ── 窗口取 0 那一档：去重整个关掉时不许误压 ──
            Cell(
                "窗口为 0 领过也一律放行",
                WidgetRolloverActionKind.SystemTimeChanged, 500L, 400L, 0L, true,
                "GrantedAfterWindow", claimedAt = 500L, elapsedSinceLastClaim = 100L,
            ),
            // ── 单调钟倒进取 ──
            Cell(
                "读数倒退 不许冒充刚刚刷过",
                WidgetRolloverActionKind.SystemDateChanged, 10_000L, 50_000L, WINDOW, true,
                "SuppressedClockInverted",
            ),
            // ── 旗与哨兵打脸那一档：偏向刷新 ──
            Cell(
                "旗说领过而读数还是初始哨兵 判据不背这个锅",
                WidgetRolloverActionKind.SystemDateChanged, 30_000L, NEVER, WINDOW, true,
                "SentinelContradiction",
            ),
            Cell(
                "旗说没领过而读数已是真实时刻 同样打脸",
                WidgetRolloverActionKind.SystemTimezoneChanged, 900_000L, 800_000L, WINDOW, false,
                "SentinelContradiction",
            ),
            // ── 不是跨天那一族的动作：排在一切判据之前 ──
            Cell(
                "非跨天动作且从没领过 也不领配额",
                WidgetRolloverActionKind.NotRollover, 900_000L, NEVER, WINDOW, false,
                "NotRolloverAction",
            ),
            Cell(
                "非跨天动作而出窗 依旧不领配额",
                WidgetRolloverActionKind.NotRollover, 9_999_999L, 1L, WINDOW, true,
                "NotRolloverAction",
            ),
            // ── 四种跨天动作各走通一次（不许把 actionKind 折成一枚 Boolean）──
            Cell(
                "自排零点闹钟 第一次领",
                WidgetRolloverActionKind.SelfMidnightAlarm, 7_000L, NEVER, WINDOW, false,
                "GrantedFirstClaim", claimedAt = 7_000L,
            ),
            Cell(
                "系统改时间 第一次领",
                WidgetRolloverActionKind.SystemTimeChanged, 7_000L, NEVER, WINDOW, false,
                "GrantedFirstClaim", claimedAt = 7_000L,
            ),
            Cell(
                "系统改时区 第一次领",
                WidgetRolloverActionKind.SystemTimezoneChanged, 7_000L, NEVER, WINDOW, false,
                "GrantedFirstClaim", claimedAt = 7_000L,
            ),
        )

        for (cell in cells) {
            val decision = WidgetRolloverGate.decide(
                actionKind = cell.actionKind,
                nowElapsedRealtime = cell.nowElapsedRealtime,
                lastClaimedAt = cell.lastClaimedAt,
                windowMillis = cell.windowMillis,
                hasEverRefreshed = cell.hasEverRefreshed,
            )
            assertEquals(
                "格子「${cell.label}」：输入 now=${cell.nowElapsedRealtime} last=${cell.lastClaimedAt} " +
                    "window=${cell.windowMillis} everRefreshed=${cell.hasEverRefreshed} " +
                    "action=${cell.actionKind} 应落在 ${cell.expectedTier}，实到 ${tier(decision)}",
                cell.expectedTier,
                tier(decision),
            )
            if (cell.claimedAt != null) {
                assertEquals(
                    "格子「${cell.label}」：写回哨兵的读数必须是这一次递进来的 now（${cell.claimedAt}）",
                    cell.claimedAt,
                    claimedAtOf(decision),
                )
            }
            if (cell.elapsedSinceLastClaim != null) {
                assertEquals(
                    "格子「${cell.label}」：交回的「距上一次领取」读数应为 ${cell.elapsedSinceLastClaim}",
                    cell.elapsedSinceLastClaim,
                    elapsedOf(decision),
                )
            }
            assertEquals(
                "格子「${cell.label}」：档位要把动作种类原样交回调用点（日志要说得出是哪一条广播）",
                cell.actionKind,
                actionOf(decision),
            )
        }
    }

    @Test
    fun `六档都是活的 同一趟输入各交回一枚不同结论`() {
        val decisions = listOf(
            WidgetRolloverGate.decide(ROLLOVER_ACTION, 45_000L, NEVER, WINDOW, false),
            WidgetRolloverGate.decide(ROLLOVER_ACTION, 200_000L, 20_000L, WINDOW, true),
            WidgetRolloverGate.decide(ROLLOVER_ACTION, 100_000L, 20_000L, WINDOW, true),
            WidgetRolloverGate.decide(ROLLOVER_ACTION, 10_000L, 50_000L, WINDOW, true),
            WidgetRolloverGate.decide(ROLLOVER_ACTION, 30_000L, NEVER, WINDOW, true),
            WidgetRolloverGate.decide(WidgetRolloverActionKind.NotRollover, 30_000L, NEVER, WINDOW, false),
        )
        val tiers = decisions.map { tier(it) }
        assertEquals(
            "六枚输入必须交出六枚不同档位（实到 $tiers）—— 少一枚就是有档被折进了别的档",
            6,
            tiers.distinct().size,
        )
        assertEquals(
            "本卡题眼那一格（初始哨兵 + 窗口之内）今天必须是放行档，实到 ${tiers[0]}",
            "GrantedFirstClaim",
            tiers[0],
        )
        assertEquals(
            "窗口之内的重复那一格今天必须是压掉档，实到 ${tiers[2]}",
            "SuppressedAsDuplicate",
            tiers[2],
        )
    }

    @Test
    fun `判据不吃时钟之外的东西 同一组读数两次必得同一档`() {
        val first = WidgetRolloverGate.decide(ROLLOVER_ACTION, 45_000L, NEVER, WINDOW, false)
        val second = WidgetRolloverGate.decide(ROLLOVER_ACTION, 45_000L, NEVER, WINDOW, false)
        assertEquals("同一组递进来的读数必须给出同一结论（判据里没有现读的钟）", tier(first), tier(second))
        assertEquals(
            "而且连写回哨兵的那一枚读数都要一模一样",
            claimedAtOf(first),
            claimedAtOf(second),
        )
    }

    @Test
    fun `四种跨天动作在同一组读数下同判`() {
        val kinds = listOf(
            WidgetRolloverActionKind.SelfMidnightAlarm,
            WidgetRolloverActionKind.SystemDateChanged,
            WidgetRolloverActionKind.SystemTimeChanged,
            WidgetRolloverActionKind.SystemTimezoneChanged,
        )
        val tiers = kinds.map { tier(WidgetRolloverGate.decide(it, 45_000L, NEVER, WINDOW, false)) }
        assertEquals(
            "四种跨天动作在「开机窗口内的第一次」这一格必须同判（实到 $tiers）—— " +
                "任何一行动作被漏掉或单判 就是又一处静默支路",
            List(4) { "GrantedFirstClaim" },
            tiers,
        )
        val suppressed = kinds.map { tier(WidgetRolloverGate.decide(it, 100_000L, 20_000L, WINDOW, true)) }
        assertEquals(
            "同一组「窗口之内」读数下四种动作也要同判（实到 $suppressed）",
            List(4) { "SuppressedAsDuplicate" },
            suppressed,
        )
    }

    private fun tier(decision: WidgetRolloverClaim): String = decision.javaClass.simpleName

    private fun claimedAtOf(decision: WidgetRolloverClaim): Long? = when (decision) {
        is WidgetRolloverClaim.GrantedFirstClaim -> decision.claimedAtElapsedRealtime
        is WidgetRolloverClaim.GrantedAfterWindow -> decision.claimedAtElapsedRealtime
        else -> null
    }

    private fun elapsedOf(decision: WidgetRolloverClaim): Long? = when (decision) {
        is WidgetRolloverClaim.GrantedAfterWindow -> decision.elapsedSinceLastClaimMillis
        is WidgetRolloverClaim.SuppressedAsDuplicate -> decision.elapsedSinceLastClaimMillis
        else -> null
    }

    private fun actionOf(decision: WidgetRolloverClaim): WidgetRolloverActionKind = when (decision) {
        is WidgetRolloverClaim.GrantedFirstClaim -> decision.actionKind
        is WidgetRolloverClaim.GrantedAfterWindow -> decision.actionKind
        is WidgetRolloverClaim.SuppressedAsDuplicate -> decision.actionKind
        is WidgetRolloverClaim.SuppressedClockInverted -> decision.actionKind
        is WidgetRolloverClaim.SentinelContradiction -> decision.actionKind
        is WidgetRolloverClaim.NotRolloverAction -> decision.actionKind
    }
}
