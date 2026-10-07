package com.buaa.schedule.widget

import com.buaa.schedule.domain.widget.WidgetRolloverActionKind
import com.buaa.schedule.domain.widget.WidgetRolloverClaim
import com.buaa.schedule.domain.widget.WidgetRolloverGate
import com.buaa.schedule.domain.widget.WidgetRolloverSentinel
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T256④：钉「开机后窗口之内的跨天广播被当成刚刚刷过」这一族形状**不会再回来**（台账 #478）。
 *
 * ## 钉法点名（卡面 §4 的四种里选哪一种）
 *
 * 主尺 = **读形状**（花括号配平取块 + 索引顺序）。病灶不是某一行写错，而是**两件事的先后与归属**：
 * ① 「从没领过」那条短路必须排在窗口比较**之前**；② 全量刷新那一手必须只长在放行档的块里，
 * 而续排那一手必须在整块分派**之外**。逐字节比对只能钉住"这一行今天长这样"，
 * 把短路挪到窗口比较后面、每一行逐字节都对得上，缺陷就整块回来 ⇒ 只有索引顺序与块归属钉得住它。
 * 辅钉两把，都在同一批文件里：**抹注释后数出现次数**（病灶行"代码里 0 处 / 含注释 1 处"成对复算）、
 * **逐字节比对**（哨兵别名那一行、窗口常数那一行、decide 的返回类型那一行）。
 *
 * ## 拦「初始值与时间戳又共用一格」这一手（三处互锁，不是套套逻辑）
 *
 * 1. `AtomicLong(NEVER_CLAIMED_MILLIS)` 代码里恰好 1 枚，`AtomicLong(0L)` 代码里 **0** 枚、
 *    含注释原文里**恰好 1 枚**（KDoc 逐字留着改前那一手）⇒ 那个 0 是"代码里真的没有"，
 *    不是"把取证删干净"。
 * 2. `hasEverRefreshed = prev != NEVER_CLAIMED_MILLIS` 恰好 1 枚：这一枚把"领过没有"钉成
 *    **由哨兵现推**。有人改成常量、改回 `!= 0L`、或另起一枚 AtomicBoolean 与时间戳分居两处，
 *    这一格当场分家（分居两处正是本卡判掉的乙/丙变体）。
 * 3. 内核里 `-1L` 只许有**一枚定义**（`NEVER_CLAIMED_MILLIS = -1L`），调用点只引用不另写；
 *    两枚文件里裸 `-1L` 的总账 = 1。
 *
 * ## 两侧的红臂（本守卫自己也得是活的）
 *
 * - **压制过头（原缺陷复发方向）**：把初始值改回 `AtomicLong(0L)` ⇒ 第 1 格红；
 *   把内核里「从没领过」那条短路删掉 ⇒ 顺序格与规则锚点格同时红，
 *   而表驱动 `WidgetRolloverGateTest` 的题眼格也红；
 * - **去重失效（双刷复发方向）**：把窗口那一行压掉的结论换成放行档 ⇒
 *   规则锚点格与档位枚数格红，表驱动的窗口内那格也红。
 *
 * 只做**源码核对**（JVM，无 Robolectric、无设备、不读钟）。输入面：本守卫读 `src/main/java`
 * 下的工作树文本，已由 `app/build.gradle.kts` 里 `guardReadWorkingTreeFiles` 整体声明成
 * `testDebugUnitTest` 的输入。⚠️ 未装机：开机时刻与广播投递都没有设备读数（本卡零 adb）。
 */
class WidgetRolloverClaimWiringGuardTest {

    private companion object {
        const val RECEIVER = "com/buaa/schedule/widget/WidgetRefreshReceiver.kt"
        const val GATE = "com/buaa/schedule/domain/widget/WidgetRolloverGate.kt"

        const val NEVER = WidgetRolloverSentinel.NEVER_CLAIMED_MILLIS
        const val WINDOW = 120_000L

        // ── 调用点：哨兵与领取 ──
        const val SENTINEL_ALIAS =
            "private const val NEVER_CLAIMED_MILLIS = WidgetRolloverSentinel.NEVER_CLAIMED_MILLIS"
        const val SENTINEL_INIT = "AtomicLong(NEVER_CLAIMED_MILLIS)"
        const val DERIVED_FLAG = "hasEverRefreshed = prev != NEVER_CLAIMED_MILLIS,"
        const val CAS = "lastRolloverAt.compareAndSet(prev, commitAt)"
        const val READ_CLOCK = "android.os.SystemClock.elapsedRealtime()"
        const val WINDOW_CONST = "private const val ROLLOVER_SUPPRESS_MS = 120_000L"

        // ── 改前两手（病灶行）：代码里必须 0 处，取证里恰好 1 处 ──
        const val OLD_INIT = "java.util.concurrent.atomic.AtomicLong(0L)"
        const val OLD_CALL = "if (claimRolloverRefresh()) { BackgroundSync.refreshWidgets(context) }"

        // ── 调用点分派的形状 ──
        const val WHEN_CLAIM = "when (val claim = claimRolloverRefresh(rolloverActionKind(action))) {"
        const val GRANTED_BRANCH = "is WidgetRolloverClaim.GrantedAfterWindow -> {"
        const val CONTRADICTION_BRANCH = "is WidgetRolloverClaim.SentinelContradiction -> {"
        const val DUPLICATE_BRANCH = "is WidgetRolloverClaim.SuppressedAsDuplicate -> {"
        const val INVERTED_BRANCH = "is WidgetRolloverClaim.SuppressedClockInverted -> {"
        const val NOT_ROLLOVER_BRANCH = "is WidgetRolloverClaim.NotRolloverAction -> {"
        const val FULL_REFRESH = "BackgroundSync.refreshWidgets(context)"
        const val RESCHEDULE = "BackgroundSync.scheduleWidgetMidnight(context)"

        // ── 动作归类：四条跨天动作各一枚映射，兜底一枚 ──
        const val CLASSIFY_HEAD = "private fun rolloverActionKind(action: String): WidgetRolloverActionKind ="
        val CLASSIFY_ROWS = listOf(
            "ACTION_MIDNIGHT_REFRESH -> WidgetRolloverActionKind.SelfMidnightAlarm",
            "Intent.ACTION_DATE_CHANGED -> WidgetRolloverActionKind.SystemDateChanged",
            "Intent.ACTION_TIME_CHANGED -> WidgetRolloverActionKind.SystemTimeChanged",
            "Intent.ACTION_TIMEZONE_CHANGED -> WidgetRolloverActionKind.SystemTimezoneChanged",
            "else -> WidgetRolloverActionKind.NotRollover",
        )

        // ── 内核：签名 / 档位 / 规则顺序 ──
        const val DECIDE_SIGNATURE = "): WidgetRolloverClaim = when {"
        const val TIER_SUPER = " : WidgetRolloverClaim"
        const val NEVER_CONST_DEF = "const val NEVER_CLAIMED_MILLIS = -1L"
        const val RULE_NOT_ROLLOVER = "actionKind == WidgetRolloverActionKind.NotRollover ->"
        const val RULE_CONTRADICTION =
            "hasEverRefreshed == (lastClaimedAt == WidgetRolloverSentinel.NEVER_CLAIMED_MILLIS) ->"
        const val RULE_FIRST_CLAIM = "!hasEverRefreshed ->"
        const val RULE_INVERTED = "nowElapsedRealtime - lastClaimedAt < 0L ->"
        const val RULE_WINDOW = "nowElapsedRealtime - lastClaimedAt < windowMillis ->"
        val ACTION_ENTRIES = listOf(
            "SelfMidnightAlarm,",
            "SystemDateChanged,",
            "SystemTimeChanged,",
            "SystemTimezoneChanged,",
            "NotRollover,",
        )

        /** 判据本体的纯度口径（本仓明文：这些只许在调用点当参数递进来） */
        val FORBIDDEN_READS = listOf(
            "SystemClock",
            "elapsedRealtime",
            "currentTimeMillis",
            "Instant.now",
            "LocalDate.now",
            "ZoneId",
            "TimeZone",
            "Build.VERSION",
            "Context",
        )
    }

    // ─────────────── ① 主尺：初始值不再与时间戳共用一格 ───────────────

    @Test
    fun `哨兵初始值与时间戳不可混 三处互锁`() {
        val raw = source(RECEIVER)
        val code = blankComments(raw)

        assertEquals(
            "$RECEIVER 里哨兵初始化「$SENTINEL_INIT」必须恰好一枚（实到 " + occurrences(code, SENTINEL_INIT) +
                "）：两枚就是又开了第二套刻度，0 枚就是哨兵换了别的载体",
            1,
            occurrences(code, SENTINEL_INIT),
        )
        assertEquals(
            "「$DERIVED_FLAG」必须恰好一枚 —— 「领过没有」这一格只许**由哨兵现推**。" +
                "实到 " + occurrences(code, DERIVED_FLAG) + " 枚：改成常量 true 就是本卡病灶的变体" +
                "（窗口拿 -1 去比，开机头两分钟又整段跳过），改成 `!= 0L` 就是共用一格直接回来，" +
                "另起一枚 AtomicBoolean 与时间戳分居两处则又是两把尺",
            1,
            occurrences(code, DERIVED_FLAG),
        )
        assertEquals(
            "哨兵别名那一行逐字节一枚在场（恰好一枚）：「$SENTINEL_ALIAS」。" +
                "实到 " + occurrences(code, SENTINEL_ALIAS) + " 枚 —— 有人把这行换成自己写的负数字面量，" +
                "刻度与判据两头就各有一套「从没刷过」",
            1,
            occurrences(code, SENTINEL_ALIAS),
        )
        // 反套套逻辑：病灶行在代码里 0 处，而含注释的取证原文里恰好 1 处
        assertEquals(
            "改前那一手「$OLD_INIT」在代码里必须 **0** 处（它把初始值同时当「上一次刷新时刻」用）。" +
                "\n  复算：把 " + RECEIVER + " 的 // 与块注释抹成空格后数这一串" +
                "\n  实到枚数：" + occurrences(code, OLD_INIT),
            0,
            occurrences(code, OLD_INIT),
        )
        assertEquals(
            "含注释的原文里「$OLD_INIT」必须恰好 **1** 处（KDoc 逐字留着改前那一手做取证）。" +
                "实到 " + occurrences(raw, OLD_INIT) + " 枚：读到 0 = 取证被搬走，上面那个 0 就成了"
                + "「注释删干净」的假绿；读到 2 = 病灶回潮",
            1,
            occurrences(raw, OLD_INIT),
        )
        assertEquals(
            "哨兵定义在全仓只许有一枚：内核里「$NEVER_CONST_DEF」恰好 1 枚，而调用点里裸 `-1L` 0 枚。" +
                "实到 " + occurrences(blankComments(source(GATE)), NEVER_CONST_DEF) + " 与 " +
                occurrences(code, "-1L"),
            listOf(1, 0),
            listOf(occurrences(blankComments(source(GATE)), NEVER_CONST_DEF), occurrences(code, "-1L")),
        )
        assertEquals(
            "窗口常数逐字节钉死「$WINDOW_CONST」恰好一枚（实到 " + occurrences(code, WINDOW_CONST) + "）。" +
                "改它不是本卡口径：窗口值与「初始值 vs 时间戳」是两件事，" +
                "要动窗口请连内核表驱动与本页三格一起重钉",
            1,
            occurrences(code, WINDOW_CONST),
        )
    }

    // ─────────────── ② 主尺：内核里短路与窗口的先后（顺序即语义） ───────────────

    @Test
    fun `从没领过那条短路排在窗口比较之前`() {
        val code = blankComments(source(GATE))
        val body = balancedBlock(code, "fun decide(", "WidgetRolloverGate.decide")

        val firstClaim = body.indexOf(RULE_FIRST_CLAIM)
        val window = body.indexOf(RULE_WINDOW)
        assertTrue(
            "内核里两条规则都得在场（锚点漂了要回来重钉）：短路「$RULE_FIRST_CLAIM」实到索引 $firstClaim，" +
                "窗口「$RULE_WINDOW」实到索引 $window",
            firstClaim >= 0 && window >= 0,
        )
        assertTrue(
            "**顺序钉**：「从没领过 ⇒ 放行」必须排在窗口比较之前（实到 $firstClaim 与 $window，" +
                "前者必须更小）。挪到窗口后面 = 开机窗口内的第一次跨天又被压掉，" +
                "逐字节每一行都对得上而缺陷整块回来 —— 这就是本卡题眼那一格",
            firstClaim < window,
        )
        for ((index, rule) in listOf(RULE_NOT_ROLLOVER, RULE_CONTRADICTION, RULE_FIRST_CLAIM, RULE_INVERTED, RULE_WINDOW).withIndex()) {
            assertEquals(
                "判据「$rule」必须恰好一枚（第 $index 条，实到 " + occurrences(body, rule) + "）。" +
                    "少任何一枚就有两档要借同一句话说话，多的那枚就是有人新加了第二把尺",
                1,
                occurrences(body, rule),
            )
        }
        assertTrue(
            "顺序钉第二格：非跨天动作那一档排在一切时间判据之前（实到 " + body.indexOf(RULE_NOT_ROLLOVER) +
                " 必须小于 " + body.indexOf(RULE_CONTRADICTION) + "）",
            body.indexOf(RULE_NOT_ROLLOVER) < body.indexOf(RULE_CONTRADICTION),
        )
        assertEquals(
            "decide 的返回类型必须是分档而不是一枚 Boolean：「$DECIDE_SIGNATURE」恰好 1 枚、" +
                "「): Boolean」0 枚（实到 " + occurrences(code, DECIDE_SIGNATURE) + " 与 " +
                occurrences(code, "): Boolean") + "）",
            listOf(1, 0),
            listOf(occurrences(code, DECIDE_SIGNATURE), occurrences(code, "): Boolean")),
        )
        assertEquals(
            "档位枚数恰好 6（实到 " + occurrences(code, TIER_SUPER) + "）：折成一枚 Boolean 是 0，" +
                "把两档并成一是 5 —— 每一档都要有自己那句实话",
            6,
            occurrences(code, TIER_SUPER),
        )
        for (entry in ACTION_ENTRIES) {
            assertEquals("动作档位「$entry」恰好一枚（档位被合并就是又折成 Boolean）", 1, occurrences(code, entry))
        }
    }

    @Test
    fun `判据内核零 android 也不读钟读区读权限`() {
        val raw = source(GATE)
        val offenders = raw.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("import ") }
            .filter { it.contains("android.") || it.contains("androidx.") }
            .toList()
        assertEquals("$GATE 必须零 android / androidx import。实到：" + offenders, emptyList<String>(), offenders)

        val code = blankComments(raw)
        for (needle in FORBIDDEN_READS) {
            assertEquals(
                "判据文件里「$needle」必须 0 处：读了它，「这一次跨天要不要全量刷新」就不再是纯函数 —— " +
                    "同一条广播的两次投递可能给出不同结论（本仓口径：这些留在调用点当参数递进来）。" +
                    "\n  复算：把 $GATE 的注释抹成空格后数「$needle」\n  实到枚数：" + occurrences(code, needle),
                0,
                occurrences(code, needle),
            )
        }
        // 反套套逻辑：KDoc 里那条纯度承诺必须留痕，否则上面那串 0 没人负责
        assertEquals(
            "KDoc 里那句纯度承诺必须留痕（含注释原文恰好 1 处）。实到：" +
                occurrences(raw, "不读时钟、不读时区、不读权限、不读 `Build.VERSION`"),
            1,
            occurrences(raw, "不读时钟、不读时区、不读权限、不读 `Build.VERSION`"),
        )
        // 改前那两手在内核的取证块里各留一枚、代码里一枚都不许有
        assertEquals(
            "内核 KDoc 逐字引用的改前那一手「$OLD_CALL」：含注释恰好 0 枚、代码 0 枚" +
                "（本卡把它留在调用点的取证里，内核不重复抄）",
            listOf(0, 0),
            listOf(occurrences(raw, OLD_CALL), occurrences(code, OLD_CALL)),
        )
        assertEquals(
            "内核 KDoc 引用的另一手「java.util.concurrent.atomic.AtomicLong(0L)」含注释恰好 1 枚（取证在档）、" +
                "代码 0 枚。实到 " + occurrences(raw, OLD_INIT) + " 与 " + occurrences(code, OLD_INIT),
            listOf(1, 0),
            listOf(occurrences(raw, OLD_INIT), occurrences(code, OLD_INIT)),
        )
    }

    // ─────────────── ③ 主尺：全量刷新只长在放行档里，续排在分派之外 ───────────────

    @Test
    fun `全量刷新只长在放行档里 续排排在整块分派之外`() {
        val code = blankComments(source(RECEIVER))
        val block = balancedBlock(code, "if (action == ACTION_MIDNIGHT_REFRESH || timeChanged) {", "跨天那一支")

        assertEquals("分派那一手「$WHEN_CLAIM」逐字节恰好一枚（实到 " + occurrences(code, WHEN_CLAIM) + "）",
            1, occurrences(code, WHEN_CLAIM))
        for (branch in listOf(GRANTED_BRANCH, CONTRADICTION_BRANCH, DUPLICATE_BRANCH, INVERTED_BRANCH, NOT_ROLLOVER_BRANCH)) {
            assertEquals(
                "档位分支「$branch」在跨天那一支里恰好一枚（实到 " + occurrences(block, branch) + "）。" +
                    "少一枚就是那一档在代码里没有自己的处置，多一枚就是同一档两处分别处置",
                1,
                occurrences(block, branch),
            )
        }

        val granted = balancedBlock(block, GRANTED_BRANCH, "放行档（两枚 Granted 合并的那一支）")
        val contradiction = balancedBlock(block, CONTRADICTION_BRANCH, "哨兵打脸那一档")
        val duplicate = balancedBlock(block, DUPLICATE_BRANCH, "窗口内去重那一档")
        val inverted = balancedBlock(block, INVERTED_BRANCH, "读数倒退那一档")
        val notRollover = balancedBlock(block, NOT_ROLLOVER_BRANCH, "非跨天动作那一档")

        assertEquals(
            "全量刷新那一手在跨天那一支里恰好 **2** 枚（实到 " + occurrences(block, FULL_REFRESH) + "）：" +
                "两枚放行档共一支 + 打脸那一档偏向刷新；掉下来就是有档没人刷，涨上去就是有档位偷刷",
            2,
            occurrences(block, FULL_REFRESH),
        )
        assertEquals("放行档里必须恰好一枚全量刷新", 1, occurrences(granted, FULL_REFRESH))
        assertEquals("哨兵打脸那一档也要偏向刷新", 1, occurrences(contradiction, FULL_REFRESH))
        for ((label, branch) in listOf(
            "窗口内去重" to duplicate,
            "读数倒退" to inverted,
            "非跨天动作" to notRollover,
        )) {
            assertEquals(
                "压掉那一档「$label」里**不许**有全量刷新（实到 " + occurrences(branch, FULL_REFRESH) + " 枚）：" +
                    "判据说压掉、代码却照刷，分档就白折了 —— 这是「把几档折成一枚 Boolean」的另一种写法",
                0,
                occurrences(branch, FULL_REFRESH),
            )
        }

        // 续排那一手必须排在整块 when 之外、之后：被压掉那一档也得续排，否则下一跳整个没了
        assertEquals(
            "续排那一手「$RESCHEDULE」在跨天那一支里恰好 1 枚（实到 " + occurrences(block, RESCHEDULE) + "）",
            1,
            occurrences(block, RESCHEDULE),
        )
        assertTrue(
            "续排必须排在分派块**之后**（实到索引 " + block.indexOf(RESCHEDULE) + " 对 " +
                block.indexOf(WHEN_CLAIM) + "）：把它塞进某一档的块里 = 被压掉那一档不再续排，" +
                "下一次零点闹钟整个没了，组件就把前一天的课挂满一天（本卡后果的第二手）",
            block.indexOf(RESCHEDULE) > block.indexOf(WHEN_CLAIM) + WHEN_CLAIM.length,
        )
        for ((label, branch) in listOf("放行档" to granted, "打脸档" to contradiction, "去重档" to duplicate)) {
            assertEquals(
                "续排不许长在 $label 里面（实到 " + occurrences(branch, RESCHEDULE) + " 枚）",
                0,
                occurrences(branch, RESCHEDULE),
            )
        }

        // 改前那一形：代码 0 处 / 注释取证 1 处
        assertEquals(
            "改前那一手「$OLD_CALL」在代码里必须 **0** 处（Boolean 一折就把四种死法念成一句）。实到 " +
                occurrences(code, OLD_CALL),
            0,
            occurrences(code, OLD_CALL),
        )
        assertEquals(
            "含注释原文里它恰好 **1** 处（调用点 KDoc 的改前取证）。实到 " +
                occurrences(source(RECEIVER), OLD_CALL),
            1,
            occurrences(source(RECEIVER), OLD_CALL),
        )
    }

    // ─────────────── ④ 痕迹：每一档都要说得出话（Info 闸门） ───────────────

    @Test
    fun `每一档各有一条可读痕迹 这一支零 Log 得不许`() {
        val code = blankComments(source(RECEIVER))
        val block = balancedBlock(code, "if (action == ACTION_MIDNIGHT_REFRESH || timeChanged) {", "跨天那一支")

        assertEquals(
            "跨天那一支里 Log 调用恰好 5 枚（实到 " + (occurrences(block, "Log.i(") + occurrences(block, "Log.w(")) +
                "）：五枚落点各一枚。本卡第 ③ 段要的就是「被跳过时留下一条读得到的痕迹」，" +
                "少一枚就又有一档静默",
            5,
            occurrences(block, "Log.i(") + occurrences(block, "Log.w("),
        )
        val branches = listOf(
            "放行档" to (GRANTED_BRANCH to 1),
            "打脸档" to (CONTRADICTION_BRANCH to 1),
            "去重档" to (DUPLICATE_BRANCH to 1),
            "倒退档" to (INVERTED_BRANCH to 1),
            "非跨天档" to (NOT_ROLLOVER_BRANCH to 1),
        )
        for ((label, branchAndExpected) in branches) {
            val (anchor, expected) = branchAndExpected
            val body = balancedBlock(block, anchor, label)
            val hits = occurrences(body, "Log.i(") + occurrences(body, "Log.w(")
            assertEquals(
                "$label（「$anchor」）里必须恰好 $expected 枚 Log.i/Log.w 痕迹（实到 $hits）。" +
                    "0 枚就是改前那种「整段跳过而一句话不说」",
                expected,
                hits,
            )
        }
        assertEquals(
            "这一支里 `Log.d` 必须 0 枚（HyperOS 把 logcat 截在 Info 级，写了等于没写）。实到 " +
                occurrences(block, "Log.d"),
            0,
            occurrences(block, "Log.d"),
        )
    }

    // ─────────────── ⑤ 领取这一手：CAS 与取钟的位置 ───────────────

    @Test
    fun `哨兵只有 CAS 一处写入 取钟住在调用点`() {
        val code = blankComments(source(RECEIVER))
        val body = balancedBlock(
            code,
            "private fun claimRolloverRefresh(actionKind: WidgetRolloverActionKind): WidgetRolloverClaim {",
            "WidgetRefreshReceiver.claimRolloverRefresh",
        )

        assertEquals(
            "CAS 那一手「$CAS」在领取函数体内恰好一枚（实到 " + occurrences(body, CAS) + "）。" +
                "0 枚就是退回 @Volatile 的先读后写 —— 两条广播各领一次配额、全学期快照跑两遍",
            1,
            occurrences(body, CAS),
        )
        assertEquals(
            "除 CAS 之外不许有第二处写哨兵：体内「lastRolloverAt.set(」必须 0 枚（实到 " +
                occurrences(body, "lastRolloverAt.set(") + "）",
            0,
            occurrences(body, "lastRolloverAt.set("),
        )
        assertEquals(
            "体内取钟恰好一枚「$READ_CLOCK」：判据本体一个字都不读钟，读数在这里现取再递进去" +
                "（实到 " + occurrences(body, READ_CLOCK) + "）",
            1,
            occurrences(body, READ_CLOCK),
        )
        assertEquals(
            "体内递进去的判据是一枚（实到 " + occurrences(body, "WidgetRolloverGate.decide(") + "）",
            1,
            occurrences(body, "WidgetRolloverGate.decide("),
        )
        assertEquals(
            "领取函数的返回类型必须是分档（逐字节「): WidgetRolloverClaim {」一枚、「): Boolean」0 枚）。" +
                "实到 " + occurrences(code, "): WidgetRolloverClaim {") + " 与 " + occurrences(code, "): Boolean"),
            listOf(1, 0),
            listOf(occurrences(code, "): WidgetRolloverClaim {"), occurrences(code, "): Boolean")),
        )
        assertEquals(
            "@Volatile 不许被搬回来当去重用（实到 " + occurrences(code, "@Volatile") + "）",
            0,
            occurrences(code, "@Volatile"),
        )
    }

    // ─────────────── ⑥ 动作归类：四条跨天动作各一枚，兜底一枚 ───────────────

    @Test
    fun `动作归类四枚映射一枚兜底 不许折成 Boolean`() {
        val code = blankComments(source(RECEIVER))
        assertEquals("归类函数签名逐字节一枚在场", 1, occurrences(code, CLASSIFY_HEAD))
        for (row in CLASSIFY_ROWS) {
            assertEquals("归类那一手「$row」恰好一枚（实到 " + occurrences(code, row) + "）", 1, occurrences(code, row))
        }
        assertEquals(
            "调用点递进去的判据只有一枚「WidgetRolloverGate.decide('（实到 " +
                occurrences(code, "WidgetRolloverGate.decide(") + "）：两处判据 = 两把尺各判各的",
            1,
            occurrences(code, "WidgetRolloverGate.decide("),
        )
        assertEquals(
            "领取函数只许被调用一次（实到 " + occurrences(code, "claimRolloverRefresh(rolloverActionKind(action))") +
                " 枚）：多处各自领配额正是 CAS 那笔账要防的",
            1,
            occurrences(code, "claimRolloverRefresh(rolloverActionKind(action))"),
        )
    }

    // ─────────────── ⑦ 运行时反证：内核与调用点认得的档位是同一套 ───────────────

    @Test
    fun `运行时那一侧也认得六档 题眼格仍是放行`() {
        val decisions = listOf(
            WidgetRolloverGate.decide(WidgetRolloverActionKind.SystemDateChanged, 45_000L, NEVER, WINDOW, false),
            WidgetRolloverGate.decide(WidgetRolloverActionKind.SystemDateChanged, 200_000L, 20_000L, WINDOW, true),
            WidgetRolloverGate.decide(WidgetRolloverActionKind.SelfMidnightAlarm, 100_000L, 20_000L, WINDOW, true),
            WidgetRolloverGate.decide(WidgetRolloverActionKind.SystemTimeChanged, 10_000L, 50_000L, WINDOW, true),
            WidgetRolloverGate.decide(WidgetRolloverActionKind.SystemTimezoneChanged, 30_000L, NEVER, WINDOW, true),
            WidgetRolloverGate.decide(WidgetRolloverActionKind.NotRollover, 30_000L, NEVER, WINDOW, false),
        )
        val tiers = decisions.map { it.javaClass.simpleName }
        assertEquals(
            "六枚输入必须交出六枚不同档位（实到 $tiers）—— 有档折掉了，守卫上面那些计数格就不再是本卡的尺",
            6,
            tiers.distinct().size,
        )
        assertEquals(
            "题眼格（初始哨兵 + 开机窗口之内）今天必须是首次领取那一档，实到 ${tiers[0]}：变成 " +
                "SuppressedAsDuplicate 就是台账 #478 复发",
            WidgetRolloverClaim.GrantedFirstClaim::class.simpleName,
            tiers[0],
        )
        assertEquals(
            "哨兵常量今天还是 -1L（实到 " + WidgetRolloverSentinel.NEVER_CLAIMED_MILLIS + "）：" +
                "把它改成 0L 就是「刻度起点与初始值同格」直接回潮",
            -1L,
            WidgetRolloverSentinel.NEVER_CLAIMED_MILLIS,
        )
        assertEquals(
            "非跨天动作那一档不领配额（实到 ${tiers[5]}）",
            WidgetRolloverClaim.NotRolloverAction::class.simpleName,
            tiers[5],
        )
    }

    // ---------------- helpers（与 IcsExportGateWiringGuardTest 同写法） ----------------

    private fun source(relative: String): String = File(findMainJavaDir(), relative).readText()

    private fun occurrences(hay: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var at = hay.indexOf(needle)
        while (at >= 0) {
            count++
            at = hay.indexOf(needle, at + needle.length)
        }
        return count
    }

    /** 从锚点那一句起按花括号配平取整块（含锚点自己）；锚点找不到就抛 */
    private fun balancedBlock(code: String, anchor: String, label: String): String {
        val at = code.indexOf(anchor)
        assertTrue("$label 的锚点没找到（改名/挪家要回来重钉本守卫）：「$anchor」", at >= 0)
        val open = code.indexOf('{', at)
        assertTrue("$label 找不到块的开括号", open >= 0)
        var depth = 0
        for (i in open until code.length) {
            when (code[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return code.substring(at, i + 1)
                }
            }
        }
        throw IllegalStateException("$label 的花括号没配平")
    }

    /** 把 `//` 与块注释抹成空格（字符串保留、长度与换行位置不变）：钉的是接线，不是白话 */
    private fun blankComments(src: String): String {
        val out = src.toCharArray()
        var i = 0
        while (i < out.size) {
            when {
                src.startsWith("//", i) -> {
                    val nl = src.indexOf('\n', i).let { if (it < 0) out.size else it }
                    for (k in i until nl) out[k] = ' '
                    i = nl
                }

                src.startsWith("/*", i) -> {
                    var depth = 1
                    var j = i + 2
                    while (j < out.size && depth > 0) {
                        when {
                            src.startsWith("/*", j) -> { depth++; j += 2 }
                            src.startsWith("*/", j) -> { depth--; j += 2 }
                            else -> j++
                        }
                    }
                    for (k in i until j.coerceAtMost(out.size)) if (out[k] != '\n') out[k] = ' '
                    i = j
                }

                out[i] == '"' || out[i] == '\'' -> {
                    val quote = out[i]
                    var j = i + 1
                    while (j < out.size) {
                        when {
                            src[j] == '\\' -> j += 2
                            src[j] == quote -> { j++; break }
                            src[j] == '\n' -> break
                            else -> j++
                        }
                    }
                    i = j
                }

                else -> i++
            }
        }
        return String(out)
    }

    private fun findMainJavaDir(): File {
        var dir: File? = File("").absoluteFile
        repeat(5) {
            val hit = listOf("src/main/java", "app/src/main/java")
                .map { File(dir, it) }
                .firstOrNull { it.isDirectory }
            if (hit != null) return hit
            dir = dir?.parentFile
        }
        throw IllegalStateException("找不到 app/src/main/java：当前目录 ${File("").absolutePath}")
    }
}
