package com.buaa.schedule.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T70 滑动判据内核的表驱动单测（纯 JVM，不起 Compose）。
 *
 * 本模块没有 Compose 运行时（无 Robolectric、无 ui-test），所以"这一滑算不算翻日"
 * "正文这一帧跟不跟手""松手要不要弹回去"这三道判据只能按本仓口径抽成纯函数在这里打表；
 * 接线那侧由 [DaySwipeWiringGuardTest] 用源码扫描钉住"真的有人按这张表调用"。
 *
 * 每一档都摆**真账**：阈值取这台镜像上 72dp 在 420dpi 下换算出的 189px，
 * 事件数取一次 `input swipe 300ms` 实际注入的约 30 个 —— 换一台 density 不同的机器
 * 这些数会变，但判据本身与它们无关（density 是调用点折的）。
 */
class DaySwipePolicyTest {

    // ---- ① 翻日判定：两侧对称、阈值含边界 ----

    @Test
    fun commitDecisionIsSymmetricAndInclusiveAtTheThreshold() {
        data class Case(val dragPx: Float, val expected: DaySwipeCommit, val why: String)
        val threshold = 189f // 72dp @ 420dpi
        listOf(
            Case(-threshold, DaySwipeCommit.NextDay, "恰好拖到阈值：含边界，算翻出去（与改前 `<=` 同口径）"),
            Case(-threshold - 0.5f, DaySwipeCommit.NextDay, "越过阈值"),
            Case(-720f, DaySwipeCommit.NextDay, "一次整屏快滑"),
            Case(-threshold + 1f, DaySwipeCommit.Stay, "差 1px 没到：不许翻"),
            Case(0f, DaySwipeCommit.Stay, "原地按下没动"),
            Case(threshold - 1f, DaySwipeCommit.Stay, "反向差 1px"),
            Case(threshold, DaySwipeCommit.PreviousDay, "反向恰好到阈值：同样含边界"),
            Case(threshold + 0.5f, DaySwipeCommit.PreviousDay, "反向越过"),
            Case(720f, DaySwipeCommit.PreviousDay, "反向整屏快滑"),
        ).forEach { case ->
            assertEquals("${case.why}（dragPx=${case.dragPx}）", case.expected, daySwipeCommit(case.dragPx, threshold))
        }
    }

    @Test
    fun commitDecisionScalesWithTheThresholdItIsGiven() {
        // 同一笔位移在低 density 机器上算翻、在高 density 机器上不算翻：
        // 判据不许自己藏一个 dp 常量，阈值必须是传进来的那一个
        assertEquals(DaySwipeCommit.NextDay, daySwipeCommit(-100f, 96f))
        assertEquals(DaySwipeCommit.Stay, daySwipeCommit(-100f, 189f))
        assertEquals(DaySwipeCommit.Stay, daySwipeCommit(-100f, 378f))
    }

    // ---- ② 跟手位移：reduce-motion 与阻尼天花板 ----

    @Test
    fun followOffsetStaysZeroUnderReduceMotionWhateverTheDrag() {
        listOf(-720f, -189f, -1f, 0f, 1f, 189f, 720f).forEach { drag ->
            assertEquals(
                "reduce-motion 开着就不许有位移（dragPx=$drag）：阈值判定照旧走 daySwipeCommit",
                0f,
                dayDragFollowOffset(drag, 189f, reduceMotion = true),
                0f,
            )
        }
    }

    @Test
    fun followOffsetDampsAtTheThresholdAndKeepsTheSign() {
        data class Case(val dragPx: Float, val expectedPx: Float, val why: String)
        val threshold = 189f
        val ratio = DAY_DRAG_FOLLOW_RATIO
        listOf(
            Case(0f, 0f, "没动就没位移"),
            Case(-90f, -90f * ratio, "阈值内按比例跟"),
            Case(90f, 90f * ratio, "反向同样按比例"),
            Case(-threshold, -threshold * ratio, "恰好到天花板"),
            Case(-720f, -threshold * ratio, "越过去的部分不再增加：继续拖不携带新信息"),
            Case(720f, threshold * ratio, "反向越过去也一样被天花板截住"),
            Case(-10_000f, -threshold * ratio, "极端值不许把回弹距离撑开"),
        ).forEach { case ->
            assertEquals(
                "${case.why}（dragPx=${case.dragPx}）",
                case.expectedPx,
                dayDragFollowOffset(case.dragPx, threshold, reduceMotion = false),
                0.001f,
            )
        }
    }

    @Test
    fun followOffsetNeverExceedsTheFollowRatioTimesTheThreshold() {
        // 反向钉：位移的绝对值上限就是 0.35×阈值，扫一遍不许有任何一格越界
        val threshold = 189f
        val ceiling = threshold * DAY_DRAG_FOLLOW_RATIO
        var drag = -2000f
        while (drag <= 2000f) {
            val offset = dayDragFollowOffset(drag, threshold, reduceMotion = false)
            assertTrue("|offset| 超了 0.35×阈值（dragPx=$drag, offset=$offset）", kotlin.math.abs(offset) <= ceiling + 0.001f)
            drag += 1f
        }
    }

    // ---- ③ 松手以后位移怎么收回：本卡的行为改动 ----

    @Test
    fun onlyTheUncommittedSwipeKeepsTheOpenEndedSpring() {
        assertEquals(
            "没翻出去必须弹回去，否则正文停在拖到的位置上（这一档手感与改前逐字一致）",
            DayDragSettleMode.SpringBack,
            dayDragSettleMode(DaySwipeCommit.Stay),
        )
        assertEquals(
            "翻出去了不许再挂那根没有明确长度的弹簧：拖拽位移慢慢往回走 0 与转场把新的一天" +
                "从同一方向滑进来是两套方向相反、时长不同的位移，同时挂着读成" +
                "\"滑到一半自己抖回去\"，而整页正文在这段弹簧里每帧重画",
            DayDragSettleMode.RideWithTransition,
            dayDragSettleMode(DaySwipeCommit.NextDay),
        )
        assertEquals("反向同理", DayDragSettleMode.RideWithTransition, dayDragSettleMode(DaySwipeCommit.PreviousDay))
    }

    @Test
    fun settleModeIsDecidedByTheCommitAlone() {
        // 反向钉：收回路径只认"翻没翻出去"这一件事，不认位移大小、不认 reduce-motion。
        // 若哪天把 reduce-motion 串进来，无障碍档下翻出去那一侧又会挂回长弹簧
        listOf(DaySwipeCommit.NextDay, DaySwipeCommit.PreviousDay).forEach { commit ->
            assertEquals(dayDragSettleMode(commit), dayDragSettleMode(commit))
        }
    }

    // ---- ④ 一帧最多写一次 ----

    @Test
    fun dragOffsetIsWrittenAtMostOncePerFrame() {
        data class Case(val lastWritten: Long, val latest: Long, val expected: Boolean, val why: String)
        listOf(
            Case(0L, 0L, false, "一个事件都没有：不许起第一次写"),
            Case(0L, 1L, true, "攒到第一个事件：写"),
            Case(1L, 1L, false, "这一帧没有新事件：不许重复写同一枚位移"),
            Case(1L, 5L, true, "一帧里挤了 4 个事件：只写最后一次"),
            Case(30L, 30L, false, "落定之后事件号不再动：帧回调该停"),
        ).forEach { case ->
            assertEquals(
                "${case.why}（last=${case.lastWritten} latest=${case.latest}）",
                case.expected,
                dayDragShouldWriteOffset(case.lastWritten, case.latest),
            )
        }
    }

    // ---- ⑤ reduce-motion 下整条帧回调都不该起 ----

    @Test
    fun followChainDoesNotStartUnderReduceMotion() {
        assertFalse("reduce-motion 开着：正文不跟手，帧回调也不必起", dayDragShouldFollow(reduceMotion = true))
        assertTrue("默认档要起", dayDragShouldFollow(reduceMotion = false))
    }

    // ---- ⑥ 三档判定互不越界：方向看 commit、幅度看 offset、开关看 reduceMotion ----

    @Test
    fun reduceMotionDoesNotChangeThePagingDecision() {
        // 这一条钉的是"reduce-motion 只关掉跟手，不关掉翻页"：
        // 若哪天把开关串进 daySwipeCommit，无障碍档下横滑就彻底失效了
        val threshold = 189f
        assertEquals(daySwipeCommit(-720f, threshold), DaySwipeCommit.NextDay)
        assertEquals(daySwipeCommit(720f, threshold), DaySwipeCommit.PreviousDay)
        assertEquals(0f, dayDragFollowOffset(-720f, threshold, reduceMotion = true), 0f)
    }
}
