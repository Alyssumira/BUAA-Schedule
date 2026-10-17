package com.buaa.schedule.reminder

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 卡 #748：倒计时归属从「两枚 `@Volatile` 字段分写分读」折成一枚不可变 `countdownOwnership`
 * 快照后，读侧 `ownsCountdownTo` / `releaseCountdownOwnership` 在四象限上的判定必须各归各的，
 * 且**交叉那一格只能由一整份快照显式产生**（撕裂的半新半旧写在结构上不再可达）。
 *
 * 四象限（stored 快照的两格 × 查询的两格）：
 *  - 两枚同新：stored=(新 id, 新 classStart) —— 对(新,新) 判真、对(旧,旧) 判假。
 *  - 两枚同旧：stored=(旧 id, 旧 classStart) —— 对(新,新) 判假、对(旧,旧) 判真。
 *  - 新 id ＋旧 classStart：交叉态 —— 对(新,新) 与 (旧,旧) 都判假（这正是旧分写形状里一次
 *    写一半能被读到的那一格；折成快照后它只能作为一份完整值出现，一次读永远拿得到配对的两格）。
 *  - 旧 id ＋新 classStart：另一方向的交叉 —— 同样两问皆假。
 *
 * 纯 JVM 判据表：只喂已构造好的快照对，不读时钟、不碰 android（落点纪律第 1 条）。
 * 交叉态在真机上的实际出现率、岛与勿扰的实际恢复，本单测没有样本（见回执「未验到」）。
 */
class CountdownOwnershipSnapshotAtomicityTest {

    private val idOld = 11L
    private val idNew = 22L
    private val startOld = 1_000L
    private val startNew = 2_000L

    private data class Quadrant(
        val name: String,
        val storedId: Long,
        val storedStart: Long,
        val ownsNew: Boolean,
        val ownsOld: Boolean,
    )

    @After
    fun tearDown() {
        ReminderNotifications.countdownOwnership = null
    }

    @Test
    fun `四象限各自判得干净`() {
        val quadrants = listOf(
            Quadrant("两枚同新", idNew, startNew, ownsNew = true, ownsOld = false),
            Quadrant("两枚同旧", idOld, startOld, ownsNew = false, ownsOld = true),
            Quadrant("新 id ＋旧 classStart", idNew, startOld, ownsNew = false, ownsOld = false),
            Quadrant("旧 id ＋新 classStart", idOld, startNew, ownsNew = false, ownsOld = false),
        )
        for (q in quadrants) {
            ReminderNotifications.countdownOwnership =
                ReminderNotifications.CountdownOwnership(q.storedId, q.storedStart)
            assertEquals(
                "${q.name}：对(新 id, 新 classStart) 的归属判定",
                q.ownsNew,
                ReminderNotifications.ownsCountdownTo(idNew, startNew),
            )
            assertEquals(
                "${q.name}：对(旧 id, 旧 classStart) 的归属判定",
                q.ownsOld,
                ReminderNotifications.ownsCountdownTo(idOld, startOld),
            )
        }
    }

    @Test
    fun `交叉快照上 release 判不中 只有整枚同值才清`() {
        // stored = 新 id ＋旧 classStart —— 旧形状里一次写一半正是这一格
        ReminderNotifications.countdownOwnership =
            ReminderNotifications.CountdownOwnership(idNew, startOld)

        // 想清「真正的新 pair」：交叉态判不中 ⇒ 返回 false 且不动别人的账
        assertFalse(ReminderNotifications.releaseCountdownOwnership(idNew, startNew))
        assertEquals(
            ReminderNotifications.CountdownOwnership(idNew, startOld),
            ReminderNotifications.countdownOwnership,
        )

        // 整枚同值才清得掉
        assertTrue(ReminderNotifications.releaseCountdownOwnership(idNew, startOld))
        assertNull(ReminderNotifications.countdownOwnership)
    }

    @Test
    fun `一次赋值就带上两格 读侧拿到的是同一份`() {
        val ownership = ReminderNotifications.CountdownOwnership(idNew, startNew)
        ReminderNotifications.countdownOwnership = ownership
        // 读到的就是刚写入的那一枚引用：两格同刻成对，永不可能读到半新半旧
        assertSame(ownership, ReminderNotifications.countdownOwnership)
        assertEquals(idNew, ownership.courseId)
        assertEquals(startNew, ownership.classStartMillis)
    }
}
