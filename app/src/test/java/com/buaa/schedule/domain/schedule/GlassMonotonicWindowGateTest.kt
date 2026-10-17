package com.buaa.schedule.domain.schedule

import com.buaa.schedule.core.designsystem.GlassJankWindowAction
import com.buaa.schedule.core.designsystem.glassJankWindowAction
import com.buaa.schedule.core.designsystem.isGlassLowerCoolingDown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 玻璃采样链那两枚进程内时长的单调钟护栏（T416②，在册 #747丁）。
 *
 * 判据本体在 `GlassMonotonicWindowGate.kt`：零 android import、不读钟，`elapsedRealtime()` 的读数
 * 全当参数递进来，所以这里不需要 Robolectric、不需要假 Window，也不需要真的去改系统时钟——
 * 「时钟回拨」在这一层就是一枚**负差**的输入格子，能当场打表。
 *
 * 铺开的格子（期望值全部手写在盘上，不调用被测判据算期望）：正差 / 零差 / **负差**三档 ×
 * 两枚护栏，另加两格「护栏接上在册判据之后结论怎么变」—— 那两格才是题眼：
 * 改前负差会让冷却恒真（永不再降档）、让采样窗恒不收尾（整条降档链静默停摆），
 * 改后负差都折成偏向放行（冷却作废、窗口当场收尾）。
 */
class GlassMonotonicWindowGateTest {

    private companion object {
        const val COOLDOWN = 10L * 60_000L
        const val SAMPLE_WINDOW = 10_000L
        const val BOOT = 0L

        /** 单调钟结构上取不出负数 ⇒ 倒退档交回的这一枚不是一枚读数，而是一枚「过了足够久」 */
        const val INVERTED = Long.MAX_VALUE
    }

    // ---- 护栏一：同一把刻度上的经过量 ----------------------------------------------------

    @Test
    fun elapsedSpanCoversPositiveZeroAndNegativeDiffs() {
        val table = listOf(
            // (later, earlier, 期望) —— 每一档都在说「回拨一次会发生什么」
            SpanRow(10_000L, BOOT, 10_000L, "正差：开机后整 10 秒"),
            SpanRow(15_000L, 5_000L, 10_000L, "正差：两枚读数都非零"),
            SpanRow(10_001L, 10_000L, 1L, "正差：只过 1 毫秒"),
            SpanRow(5_000L, 5_000L, 0L, "零差：同刻"),
            SpanRow(4_999L, 5_000L, INVERTED, "负差：读数倒退 1 毫秒"),
            SpanRow(BOOT, 600_000L, INVERTED, "负差：回拨到刻度起点"),
            SpanRow(9_999L, 10_000L, INVERTED, "负差：只差 1 毫秒就到窗口线"),
        )
        for (row in table) {
            assertEquals(
                "${row.why}：later=${row.later} earlier=${row.earlier}",
                row.expected,
                glassMonotonicElapsedMillis(row.later, row.earlier),
            )
        }
    }

    @Test
    fun invertedSpanIsBiggerThanEveryWindowConstant() {
        // 倒退档交回的量必须比两枚窗口常数都大，否则「偏向放行」落不了地
        val inverted = glassMonotonicElapsedMillis(BOOT, 1L)
        assertTrue("倒退档必须大于 10 秒采样窗：$inverted", inverted > SAMPLE_WINDOW)
        assertTrue("倒退档必须大于 10 分钟降档冷却：$inverted", inverted > COOLDOWN)
    }

    // ---- 护栏二：喂给冷却判据的「上次降档时刻」 -------------------------------------------

    @Test
    fun cooldownLastReadingsCoverNullPositiveZeroAndNegativeDiffs() {
        val table = listOf(
            LastRow(null, 5_000L, null, "从未降档：原样交回 null（首降必须放行）"),
            LastRow(1_000L, 5_000L, 1_000L, "正差：原样交回，冷却判据自己算"),
            LastRow(5_000L, 5_000L, 5_000L, "零差：同刻刚降过 ⇒ 仍在冷却，不许折成 null"),
            LastRow(5_001L, 5_000L, null, "负差：上次读数比现在晚 ⇒ 折成「从未降档」（偏向放行）"),
            LastRow(600_000L, BOOT, null, "负差：回拨到刻度起点，同上一格"),
        )
        for (row in table) {
            assertEquals(
                "${row.why}：last=${row.last} now=${row.now}",
                row.expected,
                glassLowerAtForCooldown(row.last, row.now),
            )
        }
    }

    // ---- 题眼：护栏接上在册判据之后的结论 ------------------------------------------------

    /** 冷却闸门用的还是 `GlassJankDecision.kt` 那一条，本卡不许在调用点重算第二把尺 */
    @Test
    fun cooldownVerdictAfterTheGuardCoversEveryDiffSign() {
        val table = listOf(
            CooldownRow(1_000L, 5_000L, true, "正差不足冷却：照旧拦"),
            CooldownRow(5_000L, 5_000L, true, "零差：同刻必拦（改前后一致）"),
            CooldownRow(
                5_000L + COOLDOWN,
                5_000L,
                false,
                "正差恰好等于冷却：放行（闸门是 < cooldown，与 lowerTierForJank 同一条线）",
            ),
            CooldownRow(
                600_000L,
                5_000L,
                false,
                "负差：改前 5_000-600_000 是负数、恒小于冷却 ⇒ 冷却永不结束；加护栏后作废",
            ),
        )
        for (row in table) {
            val guarded = isGlassLowerCoolingDown(
                glassLowerAtForCooldown(row.last, row.now),
                row.now,
                COOLDOWN,
            )
            assertEquals("${row.why}：last=${row.last} now=${row.now}", row.expected, guarded)
            if (row.last != null && row.now < row.last) {
                // 同一格不加护栏的读数必须与加护栏后相反，否则护栏是死码
                assertTrue(
                    "不加护栏时负差恒拦（这正是 #747丁 的病灶）：last=${row.last} now=${row.now}",
                    isGlassLowerCoolingDown(row.last, row.now, COOLDOWN),
                )
            }
        }
    }

    /** 采样窗收尾：负差必须当场收尾，而不是永远「窗口还开着」 */
    @Test
    fun windowCloseAfterTheGuardCoversEveryDiffSign() {
        assertEquals(
            "正差不足 10 秒：窗口还开着",
            GlassJankWindowAction.WindowOpen,
            windowAction(later = 5_000L, earlier = BOOT, lastLowerAt = null),
        )
        assertEquals(
            "正差恰好 10 秒：收尾；连击已成且不在冷却 ⇒ 降档",
            GlassJankWindowAction.Demote,
            windowAction(later = 10_000L, earlier = BOOT, lastLowerAt = null),
        )
        assertEquals(
            "负差（回拨）：改前永不收尾、整条降档链静默停摆；改后当场收尾",
            GlassJankWindowAction.Demote,
            windowAction(later = BOOT, earlier = 600_000L, lastLowerAt = 5_000L),
        )
    }

    // ---- 小工具 --------------------------------------------------------------------------

    private data class SpanRow(
        val later: Long,
        val earlier: Long,
        val expected: Long,
        val why: String,
    )

    private data class LastRow(
        val last: Long?,
        val now: Long,
        val expected: Long?,
        val why: String,
    )

    private data class CooldownRow(
        val last: Long?,
        val now: Long,
        val expected: Boolean,
        val why: String,
    )

    /** 走的是生产同一条路：负差先过护栏，再进在册判据（帧数格子固定给一枚坏窗口） */
    private fun windowAction(
        later: Long,
        earlier: Long,
        lastLowerAt: Long?,
    ): GlassJankWindowAction = glassJankWindowAction(
        debug = false,
        windowElapsedMillis = glassMonotonicElapsedMillis(later, earlier),
        sampleWindowMillis = SAMPLE_WINDOW,
        frames = 200L,
        jankFrames = 100L,
        minFrames = 100L,
        jankRatePercent = 25L,
        previousWindowBad = true,
        nowMillis = later,
        lastLowerAtMillis = glassLowerAtForCooldown(lastLowerAt, later),
        lowerCooldownMillis = COOLDOWN,
    )
}
