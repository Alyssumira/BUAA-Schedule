package com.buaa.schedule.domain.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * T368②：`WidgetRerenderWindowGate.decide` 的表驱动逐格账（纯 JVM，无 Robolectric、无设备、不读钟）。
 *
 * 四枚事实全部当参数递进来（该实例上一次的读数、这一次单调钟的读数、去重窗口、
 * 「这一枚实例补绘过没有」），判据体内不读钟 ⇒ 能在 JVM 里逐格跑。
 *
 * 与另一枚内核（同目录那道补注册窗口）的**两处方向差异**都在这张表里有自己的格：
 * - 钟倒退那一档这里判**压掉**（多做得起、也真该省的那一手是读库＋拼 RemoteViews＋可能生成位图），
 *   另一族判放行（那两手是幂等注册）；
 * - 这一族没有「动作种类」那一档（只有一种宿主重绑事件，造一枚假的分类只会多出第二把尺）。
 *
 * ⓪ 段裁的是**形状卫生**：这道窗只有 1_500 毫秒，而两个入口都要等系统起完、桌面起完、
 * 宿主投递、本进程被拉起来才走得到，开机刻度内那一格本仓零设备读数。
 * 所以这张表钉的是「形状不许复发」，不是「今天有人看见过」——
 * 下面那格 `改前那一形只在这些格上给出另一个答案` 把差集逐枚点名，正是为了把
 * 「可达性只有形状」这件事钉成一个可数的读数，而不是一句自述。
 */
class WidgetRerenderWindowGateTest {

    private data class Cell(
        val label: String,
        val nowElapsedRealtime: Long,
        val lastClaimedAt: Long,
        val windowMillis: Long,
        val hasEverRerendered: Boolean,
        val expectedTier: String,
        val claimedAt: Long? = null,
        val elapsedSinceLastClaim: Long? = null,
    )

    private companion object {
        const val NEVER = WidgetRerenderSentinel.NEVER_RERENDERED_MILLIS
        const val WINDOW = 1_500L
    }

    @Test
    fun `逐格 五档判据的表驱动账`() {
        val cells = listOf(
            // ── 题眼：该实例从没补绘过 ⇒ 放行，读数落在开机刻度之内也一样（改前正是被压掉的那几格）──
            Cell(
                "开机后第 0 毫秒的第一枚 optionsChanged 与旧兜底值同值也必须补绘",
                0L, NEVER, WINDOW, false,
                "GrantedFirstRerender", claimedAt = 0L,
            ),
            Cell(
                "开机后 1 秒的第一枚 窗口之内也照样放行 短路排在窗口之前",
                1_000L, NEVER, WINDOW, false,
                "GrantedFirstRerender", claimedAt = 1_000L,
            ),
            Cell(
                "开机后 1 秒 499 毫秒 边界内侧的第一枚",
                1_499L, NEVER, WINDOW, false,
                "GrantedFirstRerender", claimedAt = 1_499L,
            ),
            Cell(
                "开机后整 1 秒 500 毫秒的第一枚 出不出窗都走首次那一档",
                1_500L, NEVER, WINDOW, false,
                "GrantedFirstRerender", claimedAt = 1_500L,
            ),
            Cell(
                "开机一小时之后新添加的实例第一枚重绑 走的还是首次那一档",
                3_600_000L, NEVER, WINDOW, false,
                "GrantedFirstRerender", claimedAt = 3_600_000L,
            ),
            // ── 去重本业：补绘过而窗口之内 ⇒ 压掉（一次拖拽连发好几轮 optionsChanged）──
            Cell(
                "补绘过而窗口之内 第二轮改尺寸被压掉",
                3_000L, 2_000L, WINDOW, true,
                "SuppressedAsDuplicate", elapsedSinceLastClaim = 1_000L,
            ),
            Cell(
                "同一毫秒两枚投递 差为 0 也在窗口之内",
                9_000L, 9_000L, WINDOW, true,
                "SuppressedAsDuplicate", elapsedSinceLastClaim = 0L,
            ),
            Cell(
                "边界内侧 差为窗口减一 压掉",
                2_499L, 1_000L, WINDOW, true,
                "SuppressedAsDuplicate", elapsedSinceLastClaim = 1_499L,
            ),
            // ── 边界两格：方向钉死（恰等窗口 = 放行，不许顺手改严）──
            Cell(
                "边界恰等 差为整窗口 放行",
                2_500L, 1_000L, WINDOW, true,
                "GrantedAfterWindow", claimedAt = 2_500L, elapsedSinceLastClaim = 1_500L,
            ),
            Cell(
                "边界外侧 差比窗口多一毫秒 放行",
                2_501L, 1_000L, WINDOW, true,
                "GrantedAfterWindow", claimedAt = 2_501L, elapsedSinceLastClaim = 1_501L,
            ),
            Cell(
                "上一枚是十分钟前的读数 早已出窗 照常补绘",
                600_000L, 1_000L, WINDOW, true,
                "GrantedAfterWindow", claimedAt = 600_000L, elapsedSinceLastClaim = 599_000L,
            ),
            // ── 钟倒进取（这一族压掉，但不许冒充「刚刚补绘过」）──
            Cell(
                "单调钟倒退 递进来的读数比存的那枚小 压掉并要说实话",
                1_000L, 5_000L, WINDOW, true,
                "SuppressedClockInverted",
            ),
            Cell(
                "倒退一格也要走到同一档 差为负一毫秒",
                4_999L, 5_000L, WINDOW, true,
                "SuppressedClockInverted",
            ),
            // ── 哨兵自相矛盾（旗与读数互相打脸，两枚方向各一格）──
            Cell(
                "旗说补绘过而那一格还是初始哨兵 打脸档 偏向补绘且告警",
                1_000L, NEVER, WINDOW, true,
                "SentinelContradiction",
            ),
            Cell(
                "旗说没补绘过而那一格是一枚真实时刻 同一档 反向那格",
                1_000L, 9_876L, WINDOW, false,
                "SentinelContradiction",
            ),
        )
        for ((index, cell) in cells.withIndex()) {
            val window = WidgetRerenderWindowGate.decide(
                cell.nowElapsedRealtime,
                cell.lastClaimedAt,
                cell.windowMillis,
                cell.hasEverRerendered,
            )
            assertEquals(
                "第 ${index + 1} 格「${cell.label}」实到档位 ${tier(window)}",
                cell.expectedTier,
                tier(window),
            )
            assertEquals(
                "第 ${index + 1} 格「${cell.label}」写回那一格的读数",
                cell.claimedAt,
                claimedAtOf(window),
            )
            assertEquals(
                "第 ${index + 1} 格「${cell.label}」念出来的那段「距上次补绘」",
                cell.elapsedSinceLastClaim,
                elapsedOf(window),
            )
        }
    }

    @Test
    fun `题眼格与去重格是两种结论 不许念成同一句话`() {
        val first = WidgetRerenderWindowGate.decide(1_000L, NEVER, WINDOW, false)
        val duplicate = WidgetRerenderWindowGate.decide(3_000L, 2_000L, WINDOW, true)
        val after = WidgetRerenderWindowGate.decide(600_000L, 1_000L, WINDOW, true)
        val inverted = WidgetRerenderWindowGate.decide(1_000L, 5_000L, WINDOW, true)
        val contradiction = WidgetRerenderWindowGate.decide(1_000L, NEVER, WINDOW, true)
        val tiers = listOf(first, duplicate, after, inverted, contradiction).map { tier(it) }
        assertEquals(
            "五枚输入必须交出五枚不同档位（实到 $tiers）—— 有档折掉了，守卫上面那些计数格就不再是本卡的尺",
            5,
            tiers.distinct().size,
        )
        assertEquals(
            "题眼格（初始哨兵 + 窗口之内）今天必须是首次补绘那一档，实到 ${tiers[0]}：" +
                "变成 SuppressedAsDuplicate 就是台账 #495 复发",
            "GrantedFirstRerender",
            tiers[0],
        )
        assertNotEquals(
            "「从没补绘过被误判成刚补绘过」与「去重要压的那一路」必须是两档（实到 ${tiers[0]} 对 ${tiers[1]}）",
            tiers[0],
            tiers[1],
        )
        assertEquals(
            "窗口之内的真重复那一格今天仍必须是压掉档，实到 ${tiers[1]}：去重语义不许被修坏",
            "SuppressedAsDuplicate",
            tiers[1],
        )
        assertEquals(
            "这一族的钟倒退回的是**压掉**那一档，实到 ${tiers[3]}：另一枚内核给的是放行，" +
                "两族各自的代价方向是本卡裁开的，不许在这里被写成同一句",
            "SuppressedClockInverted",
            tiers[3],
        )
    }

    @Test
    fun `改前那一形只在这些格上给出另一个答案`() {
        // 改前的判据（该实例那一格取不到就兜底成刻度起点那一枚零）：
        // `if (now - 0L < WINDOW) 压掉`。这里把它原样算一遍，只为了数出差集。
        val oldSuppressed = { now: Long -> now - 0L < WINDOW }
        val fresh = listOf(0L, 1L, 1_000L, 1_499L, 1_500L, 1_501L, 60_000L, 3_600_000L)
        val diff = fresh.filter { now ->
            val newWindow = WidgetRerenderWindowGate.decide(now, NEVER, WINDOW, false)
            oldSuppressed(now) != tier(newWindow).startsWith("Suppressed")
        }
        assertEquals(
            "改前/改后的差集必须恰好是「开机刻度内那 1.5 秒的那几格」（实到 $diff）—— " +
                "本卡的可达性只有形状，这一串数字就是形状本身：差集变大是有格今天被无故翻面，" +
                "变小是哨兵那一格又与刻度起点同值",
            listOf(0L, 1L, 1_000L, 1_499L),
            diff,
        )
    }

    @Test
    fun `判据体内不读钟 同一组读数两次必得同一档与同一写回值`() {
        val first = WidgetRerenderWindowGate.decide(1_000L, NEVER, WINDOW, false)
        val second = WidgetRerenderWindowGate.decide(1_000L, NEVER, WINDOW, false)
        assertEquals("同一组递进来的读数必须给出同一结论（判据里没有现读的钟）", tier(first), tier(second))
        assertEquals("而且连写回那一格的读数都要一模一样", claimedAtOf(first), claimedAtOf(second))
        assertEquals(
            "哨兵今天还是 -1L（实到 ${WidgetRerenderSentinel.NEVER_RERENDERED_MILLIS}）：" +
                "把它改成 0L 就是「刻度起点与初始值同格」直接回潮。" +
                "注意这一枚是「单调钟取不出负数」那一侧的**专属值**，不是一枚 elapsedRealtime 读数，" +
                "报错消息与断言里都不许把它当成时刻念",
            -1L,
            WidgetRerenderSentinel.NEVER_RERENDERED_MILLIS,
        )
    }

    private fun tier(window: WidgetRerenderWindow): String = window.javaClass.simpleName

    private fun claimedAtOf(window: WidgetRerenderWindow): Long? = when (window) {
        is WidgetRerenderWindow.GrantedFirstRerender -> window.claimedAtElapsedRealtime
        is WidgetRerenderWindow.GrantedAfterWindow -> window.claimedAtElapsedRealtime
        else -> null
    }

    private fun elapsedOf(window: WidgetRerenderWindow): Long? = when (window) {
        is WidgetRerenderWindow.GrantedAfterWindow -> window.elapsedSinceLastClaimMillis
        is WidgetRerenderWindow.SuppressedAsDuplicate -> window.elapsedSinceLastClaimMillis
        else -> null
    }
}
