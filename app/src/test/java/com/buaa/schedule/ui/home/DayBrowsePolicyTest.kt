package com.buaa.schedule.ui.home

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * T68 的复现测试：用户报的那句话是「为什么凌晨还是显示前一天的课表」。
 *
 * 机制在 `HomeScreen` 的组合期里，本模块没有 Compose 运行时，所以把决定 body 那一天的
 * 那三行算术搬进 [DayBrowsePolicy] 的当前实现（= 现状转录），在纯 JVM 上摆场景：
 * 「昨天用户翻到了某一天」+「today 前进一格」。第一条用例现在就红，
 * 红了才证明账落在这里。
 *
 * 全部日期由参数注入：没有时钟、没有 Robolectric、没有 runTest 赌时序。
 * 学期起点 2026-08-31（周一），今天取 2026-09-23（周三，第 4 周）——装机那台机器的日子。
 */
class DayBrowsePolicyTest {

    private val dayBefore = LocalDate.of(2026, 9, 22)   // 星期二，第 4 周
    private val today = LocalDate.of(2026, 9, 23)       // 星期三，第 4 周
    private val twoDaysAgo = LocalDate.of(2026, 9, 21)  // 星期一，第 4 周
    private val nextWeekMonday = LocalDate.of(2026, 9, 28) // 第 5 周周一

    // ─────────────── ① 跨午夜：浏览位置必须让位给真实今天 ───────────────

    /**
     * 缺陷本体（用户实际遇到的那一档）：昨天在日视图翻到了 9 月 22 日，
     * 那笔浏览是昨天（9 月 22 日当天）按下的；今早 today 前进到 9 月 23 日，
     * body 还在画 9 月 22 日 = 「凌晨还是显示前一天的课表」。
     */
    @Test
    fun `昨天翻到的那一天 跨过午夜以后不再作数`() {
        assertEquals(
            "today 已经前进到 9月23日，而存着的浏览日是昨天（锚定日 9月22日）按下的 —— " +
                "日视图就该画今天。「翻到别的日子」这件事不许跨天存活：" +
                "inUseBrowseDate(today=$today, anchoredOn=$dayBefore, browseDate=$dayBefore)",
            null,
            inUseBrowseDate(today, anchoredOn = dayBefore, browseDate = dayBefore),
        )
        assertEquals(
            "判据说这一天不再作数，body 却还是画着昨天 —— 用户看到的就是「前一天的课表」",
            today,
            dayViewDate(today, anchoredOn = dayBefore, browseDate = dayBefore),
        )
    }

    /** 同一档缺陷的第二条路径：昨天翻到前天，锚定日与浏览日都不是今天 */
    @Test
    fun `昨天翻到前天 今早也一样不作数`() {
        assertNull(inUseBrowseDate(today, anchoredOn = dayBefore, browseDate = twoDaysAgo))
        assertEquals(today, dayViewDate(today, anchoredOn = dayBefore, browseDate = twoDaysAgo))
    }

    /**
     * 杀进程冷启动（`rememberSaveable` 那条路，用户实际形态概率最高的一档）：
     * 两个槽位一起从 saved-state 里恢复回来，锚定日仍是昨天 ⇒ 判据得说过期。
     * 与上面那条同判据，这里单独摆一枚是因为它的输入是**盘上恢复的整数**，
     * 恢复次序不影响结论：只要锚定日与今天不是一个日子，就不许复活。
     */
    @Test
    fun `冷启动恢复出昨天的浏览日 仍然不作数`() {
        val restoredBrowseDate = LocalDate.ofEpochDay(dayBefore.toEpochDay())
        val restoredAnchor = LocalDate.ofEpochDay(dayBefore.toEpochDay())
        assertEquals(
            "冷启动以后日视图必须落在今天",
            today,
            dayViewDate(today, anchoredOn = restoredAnchor, browseDate = restoredBrowseDate),
        )
    }

    /** 只恢复出一半（另一半丢了 / 写入只落了一半）：同方向，跟随今天 */
    @Test
    fun `锚定日与浏览日缺一半时跟随今天`() {
        assertEquals(today, dayViewDate(today, anchoredOn = null, browseDate = dayBefore))
        assertEquals(today, dayViewDate(today, anchoredOn = today, browseDate = null))
        assertEquals(today, dayViewDate(today, anchoredOn = null, browseDate = null))
    }

    /** 时钟被往回调过（改系统时间、跨国往西）：旧锚定日也不会复活旧浏览日 */
    @Test
    fun `锚定日落在未来时同样不作数`() {
        assertNull(inUseBrowseDate(dayBefore, anchoredOn = today, browseDate = twoDaysAgo))
        assertEquals(dayBefore, dayViewDate(dayBefore, anchoredOn = today, browseDate = twoDaysAgo))
    }

    // ─────────────── ② 同一天之内：手动翻日子照旧有效 ───────────────

    /**
     * 反向钉：不许修成"一动就跳回今天"。锚定日 == 今天 ⇒ 翻到的那一天说话。
     */
    @Test
    fun `今天翻到的日子当天一直有效`() {
        assertEquals(dayBefore, inUseBrowseDate(today, anchoredOn = today, browseDate = dayBefore))
        assertEquals(dayBefore, dayViewDate(today, anchoredOn = today, browseDate = dayBefore))
        // 往后翻（明天 / 下周）也一样有效：判据只管"哪一天按下的"，不管翻去哪个方向
        assertEquals(
            today.plusDays(1),
            dayViewDate(today, anchoredOn = today, browseDate = today.plusDays(1)),
        )
        assertEquals(
            nextWeekMonday,
            dayViewDate(today, anchoredOn = today, browseDate = nextWeekMonday),
        )
        // 连翻好几屏：锚定日没变就一直是用户翻到的那一天
        var cursor = today
        for (step in 1..5) {
            cursor = cursor.minusDays(1)
            assertEquals(
                "同一天里第 $step 次翻页就被弹回今天了",
                cursor,
                dayViewDate(today, anchoredOn = today, browseDate = cursor),
            )
        }
    }

    /** 翻回今天：body 是今天（与「没翻过」同一结果），且不算"仍在浏览" */
    @Test
    fun `翻回今天以后 body 就是今天`() {
        assertEquals(today, dayViewDate(today, anchoredOn = today, browseDate = today))
    }

    /** 从没手动选过（两个槽位都是 -1 ⇒ 两个 null）：跟随今天 */
    @Test
    fun `没翻过日子时跟随今天`() {
        assertNull(inUseBrowseDate(today, anchoredOn = null, browseDate = null))
        assertEquals(today, dayViewDate(today, anchoredOn = null, browseDate = null))
    }

    // ─────────────── ③ 顶栏标题不许写着今天画着昨天 ───────────────

    @Test
    fun `body 画着别的日子时 标题不许自称今日`() {
        assertEquals("今日课表", dayTabHeadline(today, dateOnScreen = today))
        assertEquals(
            "顶栏写着「今日课表」、body 画着 9月22日 —— 标题与 body 各说一天，" +
                "这一档与 T56 那族（浏览周次时顶栏跟着那一周）是同一处没接上",
            "课表（浏览）",
            dayTabHeadline(today, dateOnScreen = dayBefore),
        )
        // 跨午夜让位给今天以后，标题自己回到「今日课表」
        assertEquals(
            "跨午夜以后标题该跟着 body 回到今天",
            "今日课表",
            dayTabHeadline(
                today,
                dateOnScreen = dayViewDate(today, anchoredOn = dayBefore, browseDate = dayBefore),
            ),
        )
    }
}
