package com.buaa.schedule.core.designsystem

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 运行时降档的纯判据内核（T53①）——把「什么样的窗口该降档」从窗口挂接里剥出来逐边界打表。
 *
 * 判据本体在 GlassJankDecision.kt：零 android import，帧数 / 坏帧数 / 窗口时长 /
 * 冷却时间戳全是参数，所以这里不需要 Robolectric 也不需要任何假 Window。
 * 钉住的是改前的既有语义：release 要连续两个坏窗口才降档（100 帧地板、25% 含边界、
 * TOTAL_DURATION > 32ms 的坏帧口径由调用点统计后以 jankFrames 传进）、
 * 冷却 10 分钟内不连降（now - last < cooldown，恰好等于冷却即放行）、
 * debug 只报告不降档。这些边界此前埋在 OnFrameMetricsAvailableListener 的 lambda 里，
 * 一次都没在设备上跑过（T52 基线实测：GlassJank 0 行日志），更没法单测。
 */
class GlassJankDecisionTest {

    private val cooldown = 10 * 60_000L

    /** release、窗口已收尾的默认参数：表里只覆盖边界维度 */
    private fun releaseAction(
        frames: Long,
        jankFrames: Long,
        previousWindowBad: Boolean = false,
        nowMillis: Long = 20_000L,
        lastLowerAtMillis: Long? = null,
    ) = glassJankWindowAction(
        debug = false,
        windowElapsedMillis = 10_000L,
        sampleWindowMillis = 10_000L,
        frames = frames,
        jankFrames = jankFrames,
        minFrames = 100L,
        jankRatePercent = 25L,
        previousWindowBad = previousWindowBad,
        nowMillis = nowMillis,
        lastLowerAtMillis = lastLowerAtMillis,
        lowerCooldownMillis = cooldown,
    )

    @Test
    fun badWindowBoundariesAreTableDriven() {
        val cases = listOf(
            // 帧数为 0：没有分母，按不可信处理（除零在整数乘式里天然不存在）
            Quad(0L, 0L, false, GlassJankWindowAction.GoodWindow),
            // 全是坏帧但没到 100 帧地板：静止画面/刚进前台的窗口不可信
            Quad(99L, 99L, false, GlassJankWindowAction.GoodWindow),
            // 恰好踩上地板：24% 仍差一根线
            Quad(100L, 24L, false, GlassJankWindowAction.GoodWindow),
            // 恰好 25% 算坏（整数乘式 >=，与改前逐字同口径）
            Quad(100L, 25L, false, GlassJankWindowAction.FirstBadWindow),
            // 占比按帧数放大后仍是 25%：乘式不受整除截断影响（400 帧的坏线是 100 帧）
            Quad(400L, 99L, false, GlassJankWindowAction.GoodWindow),
            Quad(400L, 100L, false, GlassJankWindowAction.FirstBadWindow),
            // 连击已成立 → 越过 FirstBadWindow
            Quad(100L, 25L, true, GlassJankWindowAction.Demote),
            Quad(1000L, 999L, true, GlassJankWindowAction.Demote),
        )
        for ((frames, jank, previousBad, expected) in cases) {
            assertEquals(
                "frames=$frames jank=$jank previousWindowBad=$previousBad",
                expected,
                releaseAction(frames, jank, previousWindowBad = previousBad),
            )
        }
    }

    @Test
    fun badGoodBadSequenceNeverDemotes() {
        // 坏 → 好 → 坏：中间那个好窗口必须清掉连击，第三次坏只是又一次 FirstBad。
        // 改前这条 reset 埋在 else 分支里，这里把它钉成序列。
        var previousBad = false
        val sequence = listOf(
            Triple(100L, 25L, GlassJankWindowAction.FirstBadWindow),
            Triple(100L, 0L, GlassJankWindowAction.GoodWindow),
            Triple(100L, 25L, GlassJankWindowAction.FirstBadWindow),
        )
        for ((frames, jank, expected) in sequence) {
            val action = releaseAction(frames, jank, previousWindowBad = previousBad)
            assertEquals("序列步 frames=$frames jank=$jank", expected, action)
            previousBad = action == GlassJankWindowAction.FirstBadWindow
        }
    }

    @Test
    fun cooldownBlocksTheSecondBadWindowUntilItsBoundary() {
        val lastLower = 100_000L
        // 差 1ms 满冷却 → 拦下（连击作废由调用点按 CooldownHold 清，不自动补降）
        assertEquals(
            GlassJankWindowAction.CooldownHold,
            releaseAction(100L, 25L, previousWindowBad = true, nowMillis = lastLower + cooldown - 1L, lastLowerAtMillis = lastLower),
        )
        // 恰好等于冷却 → 放行（lowerTierForJank 的闸门是 < cooldown，两侧必须同一条线）
        assertEquals(
            GlassJankWindowAction.Demote,
            releaseAction(100L, 25L, previousWindowBad = true, nowMillis = lastLower + cooldown, lastLowerAtMillis = lastLower),
        )
        // null = 从未降档：任何时刻都不算冷却
        assertEquals(
            GlassJankWindowAction.Demote,
            releaseAction(100L, 25L, previousWindowBad = true, nowMillis = 0L, lastLowerAtMillis = null),
        )
    }

    @Test
    fun windowStaysOpenBelowSampleWindowEvenInDebug() {
        for (debug in listOf(false, true)) {
            assertEquals(
                "debug=$debug",
                GlassJankWindowAction.WindowOpen,
                glassJankWindowAction(
                    debug = debug,
                    windowElapsedMillis = 9_999L,
                    sampleWindowMillis = 10_000L,
                    frames = 10_000L,
                    jankFrames = 10_000L,
                    minFrames = 100L,
                    jankRatePercent = 25L,
                    previousWindowBad = true,
                    nowMillis = 9_999L,
                    lastLowerAtMillis = null,
                    lowerCooldownMillis = cooldown,
                ),
            )
        }
    }

    @Test
    fun debugReportsEvenOnTheSecondConsecutiveBadWindow() {
        // debug 的口径：连续坏到第三十个窗口也只打日志，降档链只属于 release
        assertEquals(
            GlassJankWindowAction.DebugReport,
            glassJankWindowAction(
                debug = true,
                windowElapsedMillis = 10_000L,
                sampleWindowMillis = 10_000L,
                frames = 100L,
                jankFrames = 100L,
                minFrames = 100L,
                jankRatePercent = 25L,
                previousWindowBad = true,
                nowMillis = 10_000L,
                lastLowerAtMillis = null,
                lowerCooldownMillis = cooldown,
            ),
        )
    }

    @Test
    fun staticCapThresholdsKeepTheirBoundaries() {
        val off = 0
        val standard = 1
        val cases = listOf(
            // 128MB 含边界 → OFF；129MB 放行（<= 的等号是改前原样）
            Quad(128L, 8, off, off),
            Quad(129L, 8, off, standard),
            // 4 核含边界 → OFF；5 核放行
            Quad(256L, 4, off, off),
            Quad(256L, 5, off, standard),
            // 两条闸门任一命中即 OFF
            Quad(128L, 4, off, off),
            Quad(4096L, 8, off, standard),
        )
        for ((memoryMb, cores, _, expected) in cases) {
            assertEquals(
                "memoryMb=$memoryMb cores=$cores",
                expected,
                glassStaticCapTier(memoryMb, cores, off, standard),
            )
        }
    }

    @Test
    fun coolingDownPredicateMatchesGovernanceGate() {
        assertEquals(false, isGlassLowerCoolingDown(null, 5L, cooldown))
        assertEquals(false, isGlassLowerCoolingDown(0L, cooldown, cooldown))
        assertEquals(true, isGlassLowerCoolingDown(0L, cooldown - 1L, cooldown))
        // 同刻必拦：now == last 差值 0 < cooldown——与 lowerTierForJank 改前那条
        // `previous != null && nowMillis - previous < LOWER_COOLDOWN_MS` 逐字符同式
        assertEquals(true, isGlassLowerCoolingDown(0L, 0L, cooldown))
    }
}

/** 表驱动用的四元组（本模块测试没有 commons-lang，手写一个解构够用的） */
private data class Quad<out A, out B, out C, out D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
)
