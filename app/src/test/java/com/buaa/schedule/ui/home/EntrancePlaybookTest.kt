package com.buaa.schedule.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * T52② 的进场判据：「只播一次」与「总时长封顶」都是纯 JVM 事实，钉在这里。
 *
 * 内核（EntrancePlaybook.kt）零 android：设备事实（reduce-motion、玻璃治理判定）由调用方
 * 当参数传进来，所以这一整类判据不需要 Robolectric、也不需要把 Compose 拉起来跑。
 */
class EntrancePlaybookTest {

    @Before
    fun resetPlaybook() {
        // 进程级可变对象：不清记忆的话，单测之间会互相吃掉"第一次"
        EntrancePlaybook.resetForTests()
    }

    @Test
    fun claimIsOncePerProcessPerKey() {
        assertTrue("本进程第一次讨要 [day-list] 进场必须给播", EntrancePlaybook.claim(EntrancePlaybook.DAY_LIST))
        assertFalse("第二次不许再播：来回切页签不该反复播", EntrancePlaybook.claim(EntrancePlaybook.DAY_LIST))
        assertFalse(EntrancePlaybook.claim(EntrancePlaybook.DAY_LIST))
        assertTrue("另一种模式是另一次进场", EntrancePlaybook.claim(EntrancePlaybook.DAY_TIMELINE))
        assertTrue(EntrancePlaybook.hasPlayed(EntrancePlaybook.DAY_LIST))
        assertFalse("没讨要过的键不该被算成播过", EntrancePlaybook.hasPlayed(EntrancePlaybook.WEEK_GRID))
    }

    @Test
    fun playbookKeysAreDistinctConstants() {
        val keys = setOf(
            EntrancePlaybook.DAY_LIST,
            EntrancePlaybook.DAY_TIMELINE,
            EntrancePlaybook.WEEK_GRID,
        )
        assertEquals("三把键必须互不相同，否则周视图会把今日页的进场吃掉", 3, keys.size)
        keys.forEach { assertTrue("键不能是空串", it.isNotBlank()) }
    }

    @Test
    fun resetForTestsRestoresTheFirstClaim() {
        // resetForTests 存在的全部理由就是单测能重播；这条钉的是它自己的定义，
        // @Before 里那句清空顺带也就是靠这条才不是想当然
        assertTrue(EntrancePlaybook.claim(EntrancePlaybook.WEEK_GRID))
        assertFalse(EntrancePlaybook.claim(EntrancePlaybook.WEEK_GRID))
        EntrancePlaybook.resetForTests()
        assertFalse("清了记忆还标记成播过", EntrancePlaybook.hasPlayed(EntrancePlaybook.WEEK_GRID))
        assertTrue("resetForTests 之后同一把键必须能重播", EntrancePlaybook.claim(EntrancePlaybook.WEEK_GRID))
    }

    // ---- 窗口切分：总时长封顶 + 压步长而不是排队 ------------------------------

    @Test
    fun lastSlotAlwaysSettlesExactlyWhenDriverEnds() {
        for (slotCount in 1..40) {
            assertEquals(
                "格数 $slotCount 时末格没在驱动收尾那一刻落定 —— 总长就随格数增长了",
                1f,
                entranceSettleFraction(slotCount - 1, slotCount),
                0.0001f,
            )
        }
    }

    @Test
    fun noSlotEverSettlesAfterTheDriver() {
        for (slotCount in 1..40) {
            for (slot in 0 until slotCount) {
                val settle = entranceSettleFraction(slot, slotCount)
                assertTrue("格 $slot/$slotCount 的落定时刻越过了驱动终点：$settle", settle <= 1f + 1e-4f)
                assertTrue("落定时刻不能是负的：$settle", settle > 0f)
            }
        }
    }

    @Test
    fun moreSlotsCompressTheStepNotTheTimeline() {
        // 「条目多时压步长而不是排队」的可执行定义：末格落定时刻不随格数变化，
        // 而相邻两格的起跑间隔随格数变小
        fun stepOf(slotCount: Int): Float =
            entranceSettleFraction(1, slotCount) - entranceSettleFraction(0, slotCount)

        val two = stepOf(2)
        val seven = stepOf(7)
        val twenty = stepOf(20)
        assertTrue("步长没随格数压下来：2=$two 7=$seven", two > seven)
        assertTrue("步长没随格数压下来：7=$seven 20=$twenty", seven > twenty)
        assertEquals("末格落定时刻随格数漂了", 1f, entranceSettleFraction(19, 20), 0.0001f)
    }

    @Test
    fun firstSlotIsNotGivenTheWholeDriver() {
        // 曾经踩过的一脚：把 slot=0 特判成"整条驱动"，第一格会比后面所有格慢一倍，
        // 读起来像"卡在第一张才走"
        assertEquals(0f, entranceSlotProgress(0f, 0, 7), 0f)
        assertEquals(
            "第 1 格的落定时刻应当是窗口长度，而不是驱动终点",
            ENTRANCE_WINDOW_FRACTION,
            entranceSettleFraction(0, 7),
            0.0001f,
        )
        assertEquals(1f, entranceSlotProgress(ENTRANCE_WINDOW_FRACTION, 0, 7), 0.0001f)
        assertTrue(
            "第 1 格比第 2 格晚落定就是反了",
            entranceSettleFraction(0, 7) < entranceSettleFraction(1, 7),
        )
    }

    @Test
    fun progressRisesMonotonicallyAndInSlotOrder() {
        val slotCount = 9
        var d = 0f
        while (d <= 1f) {
            var previous = Float.MAX_VALUE
            for (slot in 0 until slotCount) {
                val value = entranceSlotProgress(d, slot, slotCount)
                assertTrue("输出越界：d=$d slot=$slot -> $value", value in 0f..1f)
                assertTrue(
                    "名次 $slot 在第 $previous 格之前落定了（驱动 d=$d）",
                    value <= previous + 1e-4f,
                )
                previous = value
            }
            d += 0.01f
        }
        for (slot in 0 until slotCount) {
            assertEquals("驱动到 1 时第 $slot 格没落定", 1f, entranceSlotProgress(1f, slot, slotCount), 0.0001f)
            assertEquals("驱动为 0 时第 $slot 格不该已经可见", 0f, entranceSlotProgress(0f, slot, slotCount), 0f)
        }
    }

    @Test
    fun progressNeverGoesBackwardAsDriverAdvances() {
        for (slot in 0 until 12) {
            var last = -1f
            var d = 0f
            while (d <= 1.0001f) {
                val value = entranceSlotProgress(d, slot, 12)
                assertTrue("第 $slot 格出现了倒退（$last -> $value）", value >= last)
                last = value
                d += 0.005f
            }
        }
    }

    @Test
    fun degenerateInputsClampInsteadOfThrowing() {
        // 数据在驱动跑完之前就变了：slot 可能越界，n-1 也可能是 0
        assertEquals(0f, entranceSlotProgress(-1f, 0, 5), 0f)
        assertEquals(1f, entranceSlotProgress(2f, 4, 5), 1f)
        assertEquals("负名次按第 1 格处理", entranceSlotProgress(0.3f, 0, 5), entranceSlotProgress(0.3f, -3, 5), 0f)
        assertEquals("超出格数按末格处理", entranceSlotProgress(0.3f, 99, 5), entranceSlotProgress(0.3f, 4, 5), 0f)
        // 只有一格 / 零格：整条驱动就是它自己，绝不除零
        assertEquals(0.37f, entranceSlotProgress(0.37f, 0, 1), 0f)
        assertEquals(0.37f, entranceSlotProgress(0.37f, 0, 0), 0f)
        assertEquals(1f, entranceSettleFraction(0, 1), 0f)
        // 窗口分数被传歪了也不炸：0 与 1.5 都会被夹进可用区间
        assertTrue(entranceSlotProgress(1f, 3, 8, 0f) in 0f..1f)
        assertTrue(entranceSlotProgress(1f, 3, 8, 1.5f) in 0f..1f)
        assertTrue(entranceSlotProgress(1f, 3, 8, -1f) in 0f..1f)
    }

    @Test
    fun windowFractionLeavesOverlapBetweenNeighbours() {
        // 「一路铺开」而不是「逐条排队」的构造条件：相邻两格的窗口必须重叠，
        // 也就是窗口长度 w 必须大于步长 (1-w)/(n-1)
        val w = ENTRANCE_WINDOW_FRACTION
        assertTrue("窗口分数必须落在 (0,1)：$w", w > 0f && w < 1f)
        // 两格时末格必须正好压着驱动终点（0.55..1.0），所以只有 ≥3 格才谈得上"重叠铺开"
        for (slotCount in 3..40) {
            val step = (1f - w) / (slotCount - 1)
            assertTrue("第 2 格会在第 1 格落定之后才开始：w=$w step=$step", w > step)
        }
    }

    @Test
    fun noNeighbourPairBothSitAtZeroWhenTheEarlierOneSettles() {
        // 上面那条的行为面：第 k 格落定的那一刻，第 k+1 格必须已经离开 0——
        // 否则驱动上就出现"前一张已到位、后一张纹丝不动"的死区，读起来是排队。
        // 两格时无解：末格要压着驱动终点，就只能从 1-w 起步，与首格窗口天然脱开，
        // 所以这里和上一条一样从 3 格起谈重叠
        val w = ENTRANCE_WINDOW_FRACTION
        for (slotCount in 3..40) {
            for (slot in 0 until slotCount - 1) {
                val settleOfSlot = entranceSettleFraction(slot, slotCount)
                val nextProgress = entranceSlotProgress(settleOfSlot, slot + 1, slotCount)
                assertTrue(
                    "第 $slot 格（n=$slotCount）落定时刻 $settleOfSlot 上，第 ${slot + 1} 格还停在 0",
                    nextProgress > 0f || settleOfSlot >= 1f,
                )
                // 顺带钉住：此刻第 k 格自己必然已满
                assertEquals(1f, entranceSlotProgress(settleOfSlot, slot, slotCount), 0.0001f)
            }
            // w 若被调歪到 ≥1，窗口退化成整条驱动、错峰消失——这里当场抓住
            assertTrue("窗口分数必须小于 1 才谈得上错峰：w=$w", w < 1f)
        }
    }
}
