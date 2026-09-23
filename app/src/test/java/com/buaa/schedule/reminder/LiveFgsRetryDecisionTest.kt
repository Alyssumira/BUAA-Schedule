package com.buaa.schedule.reminder

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「课程实况降级之后还要不要再试一次」的判据表（#119 / T78，2026-09-23）。
 *
 * 内核是 [nextLiveFgsRetry] / [liveFgsAttemptsAlreadyArmed] 两个顶层函数：零 android import、
 * 零时钟读取（`Build.VERSION`、精确闹钟授权、窗口相对此刻是什么状态，全部由调用点算成参数递进来）。
 * 本文件因此可以纯粹按分档跑，不需要 Robolectric —— 生产接线由
 * [LiveFgsDegradeWiringGuardTest] 按源码文本核。
 *
 * 这张表要钉住的三件事，每一件都对应这台机器上量到的一笔读数（原文与命令在
 * `docs/STATUS.md` 的 T78 段）：
 * 1. **只有"换触发源"这一种重试**：重投同一发 `startForegroundService` 在 2.3 / 5.3 / 20.4 秒
 *    三档等待后判据逐字段相同（`uidState: RCVR; code:DENIED; tempAllowListReason:<null>`，六发全 DENIED），
 *    所以内核**没有**"等一会儿再原地重投"这一档，只有 [LiveFgsRetry.ArmOnce]（重排课堂窗口，
 *    下一发从 `setAlarmClock` 的闹钟豁免档进来，实测 `code:ALARM_MANAGER_ALARM_CLOCK` ⇒ Allowed）；
 * 2. **上限写死**：重试仍失败就不许排第三次（[exhaustedNeverArmsAThirdShot]）；
 * 3. **窗口已过就不再试**：课都下课了，重排出来的铃没有对象（[windowOverOutranksEverything]）；
 * 4. **判定顺序本身**：注释里那句"窗口已过压过一切、已经重试过排在最后"只有"两条同时成立"时才量得动，
 *    所以表里成对排了相邻档（课前 vs 铃未响 / 结构性 / 已重试过，铃未响 vs 结构性 / 已重试过，
 *    结构性 vs 已重试过，窗口已过 vs 全部由 [windowOverOutranksEverything] 扫满 32 组合）——
 *    有人把 `when` 的分支重排，这里当场红。
 */
class LiveFgsRetryDecisionTest {

    /** 一行：档名 + 五个入参 + 期望判据。入参全是调用点算好的布尔，没有一个是时钟 */
    private data class Row(
        val case: String,
        val windowStillLive: Boolean,
        val inClassPhase: Boolean,
        val bellAlreadyRung: Boolean,
        val exemptionKept: Boolean,
        val attemptsAlreadyArmed: Int,
        val maxAttempts: Int,
        val expected: LiveFgsRetry,
    ) {
        fun decide() = nextLiveFgsRetry(
            windowStillLive = windowStillLive,
            inClassPhase = inClassPhase,
            bellAlreadyRung = bellAlreadyRung,
            nextAttemptKeepsAlarmClockExemption = exemptionKept,
            attemptsAlreadyArmed = attemptsAlreadyArmed,
            maxAttempts = maxAttempts,
        )
    }

    private val rows = listOf(
        // ---- 该发的那一档 ----
        Row(
            case = "首次失败：课中、铃已响、窗口还活着、闹钟豁免还在、没重试过",
            windowStillLive = true, inClassPhase = true, bellAlreadyRung = true,
            exemptionKept = true, attemptsAlreadyArmed = 0, maxAttempts = 1,
            expected = LiveFgsRetry.ArmOnce,
        ),
        // ---- 四档「不该发」，各自单独成立时就地封住 ----
        Row(
            case = "窗口已过（课已下课）：其余全放行也不排",
            windowStillLive = false, inClassPhase = true, bellAlreadyRung = true,
            exemptionKept = true, attemptsAlreadyArmed = 0, maxAttempts = 1,
            expected = LiveFgsRetry.SkipWindowOver,
        ),
        Row(
            case = "已重试过（本窗口重排 1 次后又一发倒了）：不许排第三次",
            windowStillLive = true, inClassPhase = true, bellAlreadyRung = true,
            exemptionKept = true, attemptsAlreadyArmed = 1, maxAttempts = 1,
            expected = LiveFgsRetry.SkipExhausted,
        ),
        Row(
            case = "结构性拒绝：精确闹钟未授权，重排出来的那一发不带豁免档",
            windowStillLive = true, inClassPhase = true, bellAlreadyRung = true,
            exemptionKept = false, attemptsAlreadyArmed = 0, maxAttempts = 1,
            expected = LiveFgsRetry.SkipStructural,
        ),
        Row(
            case = "课前那一档：掉的是倒计时，而上课铃本来就排着、必然带着豁免再响一次",
            windowStillLive = true, inClassPhase = false, bellAlreadyRung = true,
            exemptionKept = true, attemptsAlreadyArmed = 0, maxAttempts = 1,
            expected = LiveFgsRetry.SkipBeforeClass,
        ),
        Row(
            case = "上课铃还没响（课中阶段却给了个未来起点）：闹钟本就排着，重排只会拆了重弹",
            windowStillLive = true, inClassPhase = true, bellAlreadyRung = false,
            exemptionKept = true, attemptsAlreadyArmed = 0, maxAttempts = 1,
            expected = LiveFgsRetry.SkipBellNotRung,
        ),
        // ---- 判定顺序：越靠前越硬 ----
        Row(
            case = "顺序·窗口已过 压过 已重试过",
            windowStillLive = false, inClassPhase = true, bellAlreadyRung = true,
            exemptionKept = true, attemptsAlreadyArmed = 7, maxAttempts = 1,
            expected = LiveFgsRetry.SkipWindowOver,
        ),
        Row(
            case = "顺序·窗口已过 压过 结构性拒绝",
            windowStillLive = false, inClassPhase = true, bellAlreadyRung = true,
            exemptionKept = false, attemptsAlreadyArmed = 0, maxAttempts = 1,
            expected = LiveFgsRetry.SkipWindowOver,
        ),
        Row(
            case = "顺序·课前 压过 铃未响（两个都成立时报课前，因为那是这一档的全部理由）",
            windowStillLive = true, inClassPhase = false, bellAlreadyRung = false,
            exemptionKept = true, attemptsAlreadyArmed = 0, maxAttempts = 1,
            expected = LiveFgsRetry.SkipBeforeClass,
        ),
        Row(
            case = "顺序·结构性拒绝 压过 已重试过（都不排，但账要记在真正拦住它的那一格）",
            windowStillLive = true, inClassPhase = true, bellAlreadyRung = true,
            exemptionKept = false, attemptsAlreadyArmed = 5, maxAttempts = 1,
            expected = LiveFgsRetry.SkipStructural,
        ),
        // 相邻档两两对着钉：`when` 里这四支的顺序只在"两条同时成立"时才量得出来，
        // 缺一条就是有人把分支重排之后仍全绿（窗口已过那一支由 windowOverOutranksEverything 全组合扫）。
        Row(
            case = "顺序·课前 压过 结构性拒绝",
            windowStillLive = true, inClassPhase = false, bellAlreadyRung = true,
            exemptionKept = false, attemptsAlreadyArmed = 0, maxAttempts = 1,
            expected = LiveFgsRetry.SkipBeforeClass,
        ),
        Row(
            case = "顺序·课前 压过 已重试过",
            windowStillLive = true, inClassPhase = false, bellAlreadyRung = true,
            exemptionKept = true, attemptsAlreadyArmed = 9, maxAttempts = 1,
            expected = LiveFgsRetry.SkipBeforeClass,
        ),
        Row(
            case = "顺序·铃未响 压过 结构性拒绝",
            windowStillLive = true, inClassPhase = true, bellAlreadyRung = false,
            exemptionKept = false, attemptsAlreadyArmed = 0, maxAttempts = 1,
            expected = LiveFgsRetry.SkipBellNotRung,
        ),
        Row(
            case = "顺序·铃未响 压过 已重试过",
            windowStillLive = true, inClassPhase = true, bellAlreadyRung = false,
            exemptionKept = true, attemptsAlreadyArmed = 9, maxAttempts = 1,
            expected = LiveFgsRetry.SkipBellNotRung,
        ),
        // ---- 上限是参数，不是内核里写死的数：调用点给几就认几 ----
        Row(
            case = "上限·max=2 时第二次仍可发",
            windowStillLive = true, inClassPhase = true, bellAlreadyRung = true,
            exemptionKept = true, attemptsAlreadyArmed = 1, maxAttempts = 2,
            expected = LiveFgsRetry.ArmOnce,
        ),
        Row(
            case = "上限·max=2 时第三次不发",
            windowStillLive = true, inClassPhase = true, bellAlreadyRung = true,
            exemptionKept = true, attemptsAlreadyArmed = 2, maxAttempts = 2,
            expected = LiveFgsRetry.SkipExhausted,
        ),
        Row(
            case = "上限·max=0（有人想把这条路关掉）：一档都不发",
            windowStillLive = true, inClassPhase = true, bellAlreadyRung = true,
            exemptionKept = true, attemptsAlreadyArmed = 0, maxAttempts = 0,
            expected = LiveFgsRetry.SkipExhausted,
        ),
    )

    @Test
    fun everyTierOfTheRetryTable() {
        val failures = rows.filter { it.decide() != it.expected }
            .map { "${it.case}：期望 ${it.expected.token}，实得 ${it.decide().token}" }
        assertEquals(
            "重试分档表有档判错了（这张表的每一行都对应 emulator-5554 上的一笔读数或一条硬约束，" +
                "改动前先回 docs/STATUS.md 的 T78 段对账）：\n" + failures.joinToString("\n"),
            emptyList<String>(),
            failures,
        )
        assertEquals("分档表行数变了要连着本注释一起改（六档判据 × 顺序 × 上限）", 17, rows.size)
        // 靶子表不许"减档"：十六行里少一档，那档就再没人管（`when` 少一支也会落在这里）
        assertEquals(
            "六档判据必须在表里各自至少出现一次作期望值：" +
                (LiveFgsRetry.entries.toSet() - rows.map { it.expected }.toSet()).map { it.token },
            LiveFgsRetry.entries.toSet(),
            rows.map { it.expected }.toSet(),
        )
    }

    /** 每一档的 token 都得能在 logcat 里认出来，且不许两档共用一个 token */
    @Test
    fun everyTokenIsDistinctAndAsciiStable() {
        val tokens = LiveFgsRetry.entries.map { it.token }
        assertEquals("token 两两不重复才有得 grep", tokens.size, tokens.toSet().size)
        val offenders = tokens.filter { t -> !t.all { c -> c.isLetterOrDigit() || c == ':' || c == '=' || c == '-' } }
        assertEquals("token 里混进了非 ASCII 稳定字符（进 dex 常量与 logcat 的关键字）：$offenders", emptyList<String>(), offenders)
        assertEquals(
            "六档：armed / window-over / before-class / bell-not-rung / no-exact-alarm / exhausted",
            listOf("armed", "skip:window-over", "skip:bell-not-rung", "skip:before-class", "skip:no-exact-alarm", "skip:exhausted"),
            tokens,
        )
    }

    /** 「重试仍失败 ⇒ 不许再排第三次」按调用点的真实走法走一遍，而不是只看单次判定 */
    @Test
    fun exhaustedNeverArmsAThirdShot() {
        val max = 1
        var armedSoFar = 0
        val seen = ArrayList<String>()
        repeat(6) {
            val decision = nextLiveFgsRetry(
                windowStillLive = true,
                inClassPhase = true,
                bellAlreadyRung = true,
                nextAttemptKeepsAlarmClockExemption = true,
                attemptsAlreadyArmed = armedSoFar,
                maxAttempts = max,
            )
            seen += decision.token
            if (decision == LiveFgsRetry.ArmOnce) armedSoFar++
        }
        assertEquals(
            "降级连着来六次，最多只许排出一次重排，第三次起全是 skip:exhausted（自激回路的闸门）",
            listOf("armed") + List(5) { "skip:exhausted" },
            seen,
        )
        assertEquals("真正排出去的重排次数", 1, armedSoFar)
    }

    /** 窗口已过压过一切：这一档必须与"还有几次额度"完全无关 */
    @Test
    fun windowOverOutranksEverything() {
        val cases = listOf(true, false) // 课前/课中
            .flatMap { phase -> listOf(true, false).map { phase to it } }
            .flatMap { (phase, rung) -> listOf(true, false).map { exempt -> Triple(phase, rung, exempt) } }
        val offenders = ArrayList<String>()
        for ((phase, rung, exempt) in cases) {
            for (attempts in 0..3) {
                val decision = nextLiveFgsRetry(
                    windowStillLive = false,
                    inClassPhase = phase,
                    bellAlreadyRung = rung,
                    nextAttemptKeepsAlarmClockExemption = exempt,
                    attemptsAlreadyArmed = attempts,
                    maxAttempts = 3,
                )
                if (decision != LiveFgsRetry.SkipWindowOver) offenders += "$phase/$rung/$exempt/$attempts -> ${decision.token}"
            }
        }
        assertEquals("下课之后就再也不该排铃：$offenders", emptyList<String>(), offenders)
    }

    // ---- 「第几次」的窗口身份 ------------------------------------------------------------------------

    @Test
    fun attemptsOnlyCountWithinTheSameWindow() {
        data class Row(val case: String, val recorded: Pair<Long, Long>, val candidate: Pair<Long, Long>, val expected: Int)

        val rows = listOf(
            Row("同一节课、同一个下课时刻 ⇒ 接着数", 7L to 1_700L, 7L to 1_700L, 2),
            Row("换了另一节课 ⇒ 归零", 7L to 1_700L, 8L to 1_700L, 0),
            Row("同一节课但下课时刻改过 ⇒ 归零（那是另一个窗口）", 7L to 1_700L, 7L to 1_800L, 0),
            Row("两个都对不上 ⇒ 归零", 7L to 1_700L, 9L to 1_900L, 0),
            // 空窗口哨兵值自己认自己，但它在 nextLiveFgsRetry 那一层就先落 window-over：
            // endMillis=0 永远小于此刻。这里钉的是"认账口径"，不是"会不会真排出去"。
            Row("空窗口哨兵 (0,0) 认同一份空账", 0L to 0L, 0L to 0L, 2),
        )
        val failures = rows.mapNotNull {
            val got = liveFgsAttemptsAlreadyArmed(
                recordedCourseId = it.recorded.first,
                recordedEndMillis = it.recorded.second,
                courseId = it.candidate.first,
                endMillis = it.candidate.second,
                recordedAttempts = 2,
            )
            if (got == it.expected) null else "${it.case}：期望 ${it.expected}，实得 $got"
        }
        assertEquals(
            "窗口身份判据错了（要么换课还背着上节课的重试次数，要么同一节课每次降级都从头数、上限形同虚设）：\n" +
                failures.joinToString("\n"),
            emptyList<String>(),
            failures,
        )
        // 靶子不能是空的：一行都没扫到说明上面那张表被谁整段删了
        assertEquals("窗口身份表应当恰好五行", 5, rows.size)
    }
}
