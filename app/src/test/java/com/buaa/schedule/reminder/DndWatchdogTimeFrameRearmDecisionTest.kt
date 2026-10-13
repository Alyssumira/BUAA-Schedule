package com.buaa.schedule.reminder

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 在册 #603：系统改时间 / 跨时区之后，那只勿扰看门狗到底该不该跟着**新帧**挪 ——
 * [ClassProgressDnd.watchdogRearmDecision] 的六档逐档量（表驱动 JVM 单测）。
 *
 * ## 病灶（盘面读数见 ai/T354 的 ① 那枚提交）
 *
 * 本仓 11 枚闹钟落点全是 `RTC_WAKEUP` ＋绝对 epoch 毫秒，毫秒是**排铃那一刻**按当时的
 * 系统时间 / 默认时区算出来的。改时间 / 跨时区之后 [com.buaa.schedule.widget.WidgetRefreshReceiver]
 * 那一跳会把 10 枚重排到新帧，唯独勿扰看门狗不跟着走 —— 它唯一的排程点是
 * [ClassProgressDnd.enter]，而 enter 唯一的调用点是**上课铃那一刻**。
 * 于是"下课 + 宽限"那一格期限连同闹钟一起停在旧帧：勿扰在一个错误的墙钟时刻解除，
 * 而 [ClassProgressDnd.selfCheck] 判的还是那一个旧帧值。
 *
 * ## 为什么这一族只有这一枚判据留在 JVM 侧
 *
 * 参数表里**没有 Context**（`hasSavedFilter` / `classOngoing` 两枚 Boolean + 两枚毫秒），
 * 手法抄 [ClassProgressDnd.restoreIfStale]：本模块没有 Robolectric，
 * `getSharedPreferences` / `setAlarmClock` 都是抛 "not mocked" 的桩，
 * JVM 侧钉得住的是"哪一档该动手"，动手那一手（prefs 落格 + 重排闹钟）与它的接线
 * 按源码形状核对，钉在 `reminder/CountdownClockSingleReadGuardTest` 的 ⑤ 层。
 *
 * **判据体内一处钟都不读**：六档的输入里没有 `nowMillis` —— "现在几点"这件事被调用点
 * 那只**已经注入的** `now` 消化成 `classOngoing` 一枚 Boolean 再递进来（本仓成文禁令，
 * `domain/schedule/TodayPlanner.kt` 的类注释逐字写着「函数体不读 `LocalDate.now()`」）。
 * 因此下面每一格都用**固定日期 + 固定 zone** 现算毫秒，跑一千遍是同一个数。
 *
 * ## 六档的形状（[ClassProgressDnd.WatchdogRearm]，逐枚点名）
 *
 * 1. [noWatchdogToMoveWinsOverEveryMillisecondShape] —— 没进过勿扰：四种毫秒形态一律不动；
 * 2. [noClassInSessionOutranksEveryOtherBrake] —— 新帧这一轮没有正在上的课：连 stored=0 都排不到它后面；
 * 3. [nullFreshDeadlineIsNeverInvented] —— 新期限算不出（null）：不凭空造；
 * 4. [legacyRecordWithoutDeadlineIsLeftToSelfCheck] —— 那格从来没写过（0 / 负）：归 selfCheck；
 * 5. [sameFrameIsANoOp] —— 期限本来就跟着新帧走过：不白付一次 binder；
 * 6. [movedFrameRearmsInBothDirections] —— 唯一该动手的那一档，改早与改晚两个方向各一枚。
 */
class DndWatchdogTimeFrameRearmDecisionTest {

    private val minute = 60_000L
    private val hour = 60 * minute

    /** 这一节课：2026-10-14（周三）10:00–11:40 这套墙钟事实，按给定 zone 折成绝对毫秒 */
    private fun wallMillis(zoneId: String, hourOfDay: Int, minuteOfHour: Int): Long =
        LocalDate.parse("2026-10-14").atTime(hourOfDay, minuteOfHour)
            .atZone(ZoneId.of(zoneId)).toInstant().toEpochMilli()

    /** 旧帧（排铃那一刻的默认时区 = +08）那一格 prefs 里挂着的期限 = 下课 + 宽限 */
    private val storedOldFrame = wallMillis("Asia/Shanghai", 11, 40) + 30 * minute

    /** 正在上课中的读数（同一节 10:00–11:40，取 10:30 那一秒） */
    private val ongoingMillis = wallMillis("Asia/Shanghai", 10, 30)

    // ---- ① 第一档：没进过勿扰 ------------------------------------------------

    /** 四种毫秒形态（同帧 / 改晚 / 改早 / 根本没有期限）× 有无课堂窗口，一律不许动闹钟 */
    @Test
    fun noWatchdogToMoveWinsOverEveryMillisecondShape() {
        val shapes = listOf(
            storedOldFrame to storedOldFrame,
            storedOldFrame to wallMillis("Asia/Urumqi", 11, 40) + 30 * minute,
            storedOldFrame to wallMillis("Asia/Tokyo", 11, 40) + 30 * minute,
            0L to storedOldFrame,
        )

        for (classOngoing in listOf(true, false)) {
            for ((stored, fresh) in shapes) {
                assertEquals(
                    "没进过勿扰却准备动手 stored=$stored fresh=$fresh ongoing=$classOngoing",
                    ClassProgressDnd.WatchdogRearm.NoWatchdogToMove,
                    ClassProgressDnd.watchdogRearmDecision(
                        hasSavedFilter = false,
                        classOngoing = classOngoing,
                        storedDeadlineMillis = stored,
                        freshDeadlineMillis = fresh,
                    ),
                )
            }
        }
    }

    // ---- ② 第二档：新帧这一轮没有"正在上的那一节" ----------------------------

    /**
     * 这一档是本卡实现中途改掉的那一手：[ClassProgressScheduler.rescheduleWindows] 选的是
     * "最早一个还没结束的窗口"，下课之后（或用户把钟拨过这一节之后）它就是**下一节**，
     * 而上一节课那一格记录可能还在（[ClassProgressDnd.restore] 因权限被撤而失败时刻意保留记录）。
     * 照下一节的下课时刻去重排 = 替一格没有主人的记录把恢复点推后一整节课。
     */
    @Test
    fun noClassInSessionOutranksEveryOtherBrake() {
        val nextClassFresh = wallMillis("Asia/Shanghai", 15, 40) + 30 * minute

        for (stored in listOf(storedOldFrame, 0L, -1L)) {
            assertEquals(
                "没有正在上的课却按下一节重排 stored=$stored",
                ClassProgressDnd.WatchdogRearm.NoClassInSession,
                ClassProgressDnd.watchdogRearmDecision(
                    hasSavedFilter = true,
                    classOngoing = false,
                    storedDeadlineMillis = stored,
                    freshDeadlineMillis = nextClassFresh,
                ),
            )
        }
        // null 与同帧两个方向也排在它后面：档位顺序就是这一格
        assertEquals(
            ClassProgressDnd.WatchdogRearm.NoClassInSession,
            ClassProgressDnd.watchdogRearmDecision(true, false, storedOldFrame, null),
        )
        assertEquals(
            ClassProgressDnd.WatchdogRearm.NoClassInSession,
            ClassProgressDnd.watchdogRearmDecision(true, false, storedOldFrame, storedOldFrame),
        )
    }

    // ---- ③ 第三档：新帧的下课时刻算不出来 ------------------------------------

    /** null = 拿不到下课时刻（节次表缺时间那一族窗口）：不许凭空造一条期限 */
    @Test
    fun nullFreshDeadlineIsNeverInvented() {
        assertEquals(
            ClassProgressDnd.WatchdogRearm.NoFreshDeadline,
            ClassProgressDnd.watchdogRearmDecision(true, true, storedOldFrame, null),
        )
        // 同一格改成有值就是 Rearm：这一档拦的只是"算不出"，不是"不想动"
        assertEquals(
            ClassProgressDnd.WatchdogRearm.Rearm,
            ClassProgressDnd.watchdogRearmDecision(
                true,
                true,
                storedOldFrame,
                wallMillis("Asia/Calcutta", 11, 40) + 30 * minute,
            ),
        )
    }

    // ---- ④ 第四档：那格期限从来没写过 --------------------------------------

    /**
     * `stored == 0` 是加看门狗之前的旧版本记录，[ClassProgressDnd.selfCheck] 本来就按
     * "已过期限"处理它；这里不许替它造一条新帧期限（那等于把一次已经到点的恢复往后推）。
     * 它排在 `AlreadyCurrent` 之前：`0 == 0` 那格两处同时成立时走这一档。
     */
    @Test
    fun legacyRecordWithoutDeadlineIsLeftToSelfCheck() {
        val fresh = wallMillis("Asia/Tokyo", 11, 40) + 30 * minute

        for (stored in listOf(0L, -1L, Long.MIN_VALUE)) {
            assertEquals(
                "旧版本那格 0 被造出新期限 stored=$stored",
                ClassProgressDnd.WatchdogRearm.LegacyRecordWithoutDeadline,
                ClassProgressDnd.watchdogRearmDecision(true, true, stored, fresh),
            )
        }
        assertEquals(
            "0 == 0 处两档打架",
            ClassProgressDnd.WatchdogRearm.LegacyRecordWithoutDeadline,
            ClassProgressDnd.watchdogRearmDecision(true, true, 0L, 0L),
        )
    }

    // ---- ⑤ 第五档：期限本来就跟着新帧走过 ----------------------------------

    /** 没改时区也没改时间：新帧与旧帧逐毫秒相同 ⇒ 一次 binder 都不许多付 */
    @Test
    fun sameFrameIsANoOp() {
        assertEquals(
            ClassProgressDnd.WatchdogRearm.AlreadyCurrent,
            ClassProgressDnd.watchdogRearmDecision(true, true, storedOldFrame, storedOldFrame),
        )
        // 同一节下课墙钟、同一支时区，只是把毫秒重新算一遍：还是同帧
        assertEquals(
            ClassProgressDnd.WatchdogRearm.AlreadyCurrent,
            ClassProgressDnd.watchdogRearmDecision(
                true,
                true,
                storedOldFrame,
                wallMillis("Asia/Shanghai", 11, 40) + 30 * minute,
            ),
        )
    }

    // ---- ⑥ 第六档：唯一该动手的那一档（两个偏移方向）----------------------

    /**
     * 改晚（+08 → +06，同一句"11:40 下课"的绝对毫秒晚两小时）与
     * 改早（+08 → +10，早两小时）两个方向都必须动 ——
     * 卡面那句"早解还是晚解取决于偏移方向"落的就是这两枚。
     */
    @Test
    fun movedFrameRearmsInBothDirections() {
        val westward = wallMillis("Asia/Urumqi", 11, 40) + 30 * minute
        val eastward = wallMillis("Australia/Brisbane", 11, 40) + 30 * minute
        val farEastward = wallMillis("Pacific/Kiritimati", 11, 40) + 30 * minute

        assertEquals("向西改帧（+08 → +06）：新期限比旧的晚两小时", storedOldFrame + 2 * hour, westward)
        assertEquals("向东改帧（+08 → +10）：新期限比旧的早两小时", storedOldFrame - 2 * hour, eastward)

        assertEquals(
            "向西偏移（旧帧会让勿扰**晚**解）却不重排",
            ClassProgressDnd.WatchdogRearm.Rearm,
            ClassProgressDnd.watchdogRearmDecision(true, true, storedOldFrame, westward),
        )
        assertEquals(
            "向东偏移（旧帧会让勿扰**早**解）却不重排",
            ClassProgressDnd.WatchdogRearm.Rearm,
            ClassProgressDnd.watchdogRearmDecision(true, true, storedOldFrame, eastward),
        )
        assertEquals(
            "与旧帧相差六小时的那一档也是同一档（不靠偏移量大小决定动不动手）",
            ClassProgressDnd.WatchdogRearm.Rearm,
            ClassProgressDnd.watchdogRearmDecision(true, true, storedOldFrame, farEastward),
        )
    }

    // ---- ⑦ 那把尺本身：下课 + 宽限，且它不读钟 ----------------------------

    /** 宽限 = 30 分钟，两格毫秒由固定日期现算；下课时刻不可用就是 null（不兜第二种值） */
    @Test
    fun graceRulerIsTheSingleSourceAndReadsNoClock() {
        val classEnd = wallMillis("Asia/Shanghai", 11, 40)

        assertEquals(classEnd + 30 * minute, ClassProgressDnd.deadlineOfEnd(classEnd))
        // 两枚固定下课时刻：差值就是两枚宽限后的期限之差（宽限没有被各算各的）
        val otherZoneEnd = wallMillis("Asia/Urumqi", 11, 40)
        assertEquals(
            (otherZoneEnd - classEnd),
            (ClassProgressDnd.deadlineOfEnd(otherZoneEnd) ?: 0L) - (ClassProgressDnd.deadlineOfEnd(classEnd) ?: 0L),
        )
        assertNull(ClassProgressDnd.deadlineOfEnd(0L))
        assertNull(ClassProgressDnd.deadlineOfEnd(-1L))
    }

    /**
     * 与那把"该不该按硬超时恢复"的尺同一条不变量：只要 [ClassProgressDnd.restoreIfStale]
     * 判断此刻就该恢复（期限已过），新帧那一格就必须是"将来"—— 否则本判据永远不会落进
     * `Rearm`（正在上课这一条由 `classOngoing` 保证，`ongoingAt` 取 end 开区间）。
     */
    @Test
    fun rearmTierNeverPushesADueRestoreOut() {
        val fresh = ClassProgressDnd.deadlineOfEnd(wallMillis("Asia/Urumqi", 11, 40)) ?: 0L

        assertEquals(
            "正在上课（classOngoing=true）时新期限必然还没到点",
            false,
            ClassProgressDnd.restoreIfStale(
                hasSavedFilter = true,
                deadline = fresh,
                nowMillis = ongoingMillis,
                onStale = {},
            ),
        )
        assertEquals(
            ClassProgressDnd.WatchdogRearm.Rearm,
            ClassProgressDnd.watchdogRearmDecision(true, true, storedOldFrame, fresh),
        )
    }
}
