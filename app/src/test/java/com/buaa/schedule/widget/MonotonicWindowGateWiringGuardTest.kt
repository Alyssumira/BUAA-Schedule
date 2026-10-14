package com.buaa.schedule.widget

import com.buaa.schedule.domain.widget.WidgetBootstrapSentinel
import com.buaa.schedule.domain.widget.WidgetBootstrapWindow
import com.buaa.schedule.domain.widget.WidgetBootstrapWindowGate
import com.buaa.schedule.domain.widget.WidgetRerenderSentinel
import com.buaa.schedule.domain.widget.WidgetRerenderWindow
import com.buaa.schedule.domain.widget.WidgetRerenderWindowGate
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T368③：钉「单调钟窗口判据把『从没领过』念成『刚刚领过』」这一族形状**不会再回来**（台账 #495 ＋ #496）。
 *
 * 在册先例那一族（跨天全量刷新那一道窗）已经有自己的接线守卫；本卡两枚落点（组件补注册那一道
 * 一分钟的窗、宿主重绑补绘那一道 1.5 秒的窗）在 ⓪ 段实测是**零枚在册守卫**（四条名字全仓 test 树零命中）。
 *
 * ## 钉法点名
 *
 * 主尺 = **读形状**（花括号配平取块 ＋ 索引顺序），因为病灶不是某一行写错，而是**先后与归属**：
 * ① 「确实从没领过 ⇒ 放行」那条短路必须排在窗口比较**之前**；② 那两手续排只许长在放行档的块里；
 * ③ 被压掉与被发放两条各有一条读得到的痕迹。逐字节比对只能钉住「这一行今天长这样」——
 * 把短路挪到窗口比较后面时，每一行逐字节都对得上而缺陷整块回来，所以只有索引顺序与块归属钉得住。
 * 辅钉三把，都在同一批文件里：抹注释后数出现次数（病灶行「代码里 0 处 / 含注释 1 处」双向复算）、
 * 逐字节比对（哨兵别名那一行、两枚窗口常数、decide 的返回类型那一行）、运行时档位对账。
 *
 * ## 拦「初始值与时间戳又共用一格」这一手（三处互锁，不是套套逻辑）
 *
 * 1. 两枚调用点里那两枚病灶串代码侧 **0** 处、含注释的取证原文里恰好 1 处 ⇒ 那个 0 是「代码里真的没有」，
 *    不是「把取证删干净」。
 * 2. 「领过没有」那一格在两枚调用点各恰好一枚，且都是**现推**的（一枚由哨兵比、一枚由那一格在不在比）：
 *    改成常量、改回与零比、或另起一枚旗标与读数分居两处，当场分家。
 * 3. 两枚内核各只有**一枚** `-1L` 定义，两枚调用点里裸 `-1L` 0 枚。
 *    那一枚是「单调钟取不出负数」那一侧的**专属值**，本守卫的报错消息与断言都不许把它当
 *    elapsedRealtime 的时刻念（本仓「把不适用值当哨兵」那一族假绿先例）。
 *
 * ## 两侧的红臂（本守卫自己也得是活的，读数在 T368④ 那枚 commit 里）
 *
 * - **W 臂（朝宽）**：把补绘那一枚兜底换回刻度起点那一枚零 ⇒ 只有第 ① 枚测试红（表驱动那两枚只吃内核）
 *   ⇒ 层归属＝守卫；
 * - **N 臂（朝窄）**：从内核里摘掉「确实从没领过」那条短路 ⇒ 顺序格与规则枚数格红，
 *   两枚表驱动的题眼格也红 ⇒ 层归属＝守卫＋表驱动；
 *   另一枚 N 臂把表驱动里「发放」那一档的期望改成压掉 ⇒ 只有表驱动红（证明那张表自己是活的）。
 *
 * 只做**源码核对**（JVM，无 Robolectric、无设备、不读钟）。输入面：本守卫读 `src/main/java`
 * 下的工作树文本，已由 `app/build.gradle.kts` 里 `guardReadWorkingTreeFiles` 整体声明成
 * `testDebugUnitTest` 的输入。未装机：开机时刻与广播投递都没有设备读数（本卡零 adb）。
 */
class MonotonicWindowGateWiringGuardTest {

    /** 两枚内核的同构规格：文件、旗标名、decide 返回类型那一行、档位继承那一行 */
    private data class GateSpec(
        val file: String,
        val flag: String,
        val signature: String,
        val tierSuper: String,
        val neverConst: String,
    )

    private companion object {
        const val COMMON = "com/buaa/schedule/widget/WidgetCommon.kt"
        const val PROVIDER = "com/buaa/schedule/widget/ScheduleAppWidgetProvider.kt"
        const val BOOT_GATE = "com/buaa/schedule/domain/widget/WidgetBootstrapWindowGate.kt"
        const val RERENDER_GATE = "com/buaa/schedule/domain/widget/WidgetRerenderWindowGate.kt"

        // ── 病灶行（改前那四手）：代码里必须 0 处，含注释的取证里恰好 1 处 ──
        const val OLD_BOOT_INIT = "@Volatile private var lastBootstrapAt: Long = 0L"
        const val OLD_BOOT_WINDOW = "if (now - lastBootstrapAt < BOOTSTRAP_INTERVAL_MS) return"
        const val OLD_RERENDER_INIT = "val previous = lastRerenderAt[appWidgetId] ?: 0L"
        const val OLD_RERENDER_WINDOW =
            "if (now - previous < RERENDER_DEDUPE_MS) return@synchronized true"

        // ── 两枚调用点：哨兵别名 / 现推的旗 / 写回 ──
        const val BOOT_SENTINEL_ALIAS =
            "private const val NEVER_BOOTSTRAPPED_MILLIS = WidgetBootstrapSentinel.NEVER_BOOTSTRAPPED_MILLIS"
        const val RERENDER_SENTINEL_ALIAS =
            "private const val NEVER_RERENDERED_MILLIS = WidgetRerenderSentinel.NEVER_RERENDERED_MILLIS"
        const val BOOT_DERIVED_FLAG = "hasEverBootstrapped = prev != NEVER_BOOTSTRAPPED_MILLIS,"
        const val RERENDER_DERIVED_FLAG = "hasEverRerendered = previous != null,"
        const val RERENDER_FALLBACK = "lastClaimedAt = previous ?: NEVER_RERENDERED_MILLIS,"
        const val BOOT_CAS = "lastBootstrapAt.compareAndSet(prev, commitAt)"
        const val BOOT_ATOMIC_INIT = "AtomicLong(NEVER_BOOTSTRAPPED_MILLIS)"
        const val RERENDER_WRITE_BACK = "lastRerenderAt[appWidgetId] = commitAt"
        const val READ_CLOCK_FQ = "android.os.SystemClock.elapsedRealtime()"
        const val READ_CLOCK_SHORT = "SystemClock.elapsedRealtime()"
        const val BOOT_WINDOW_CONST = "private const val BOOTSTRAP_INTERVAL_MS = 60_000L"
        const val RERENDER_WINDOW_CONST = "const val RERENDER_DEDUPE_MS = 1_500L"
        const val SYNCHRONIZED = "synchronized(lastRerenderAt)"

        // ── 两枚调用点的分派形状 ──
        const val BOOT_WHEN = "when (val window = claimBootstrapWindow()) {"
        const val RERENDER_WHEN = "when (val window = claimRerenderWindow(appWidgetId)) {"
        const val BOOT_DUPLICATE_BRANCH = "is WidgetBootstrapWindow.SuppressedAsDuplicate -> {"
        val BOOT_BRANCHES = listOf(
            "is WidgetBootstrapWindow.GrantedFirstBootstrap,",
            "is WidgetBootstrapWindow.SentinelContradiction -> {",
            "is WidgetBootstrapWindow.GrantedClockInverted -> {",
            BOOT_DUPLICATE_BRANCH,
        )
        val RERENDER_BRANCHES = listOf(
            "is WidgetRerenderWindow.GrantedFirstRerender -> {",
            "is WidgetRerenderWindow.GrantedAfterWindow -> {",
            "is WidgetRerenderWindow.SentinelContradiction -> {",
            "is WidgetRerenderWindow.SuppressedAsDuplicate -> {",
            "is WidgetRerenderWindow.SuppressedClockInverted -> {",
        )
        const val BOOT_ACTION = "registerBackgroundSyncSteps(context)"
        const val BOOT_MIDNIGHT = "BackgroundSync.scheduleWidgetMidnight(context)"
        const val BOOT_FALLBACK = "WidgetFallbackWorker.ensure(context)"

        // ── 两枚内核：签名 / 档位 / 规则顺序 ──
        const val DECIDE_SIGNATURE = "): WidgetBootstrapWindow = when {"
        const val DECIDE_SIGNATURE_RERENDER = "): WidgetRerenderWindow = when {"
        const val TIER_SUPER_BOOT = " : WidgetBootstrapWindow"
        const val TIER_SUPER_RERENDER = " : WidgetRerenderWindow"
        const val NEVER_CONST_BOOT = "const val NEVER_BOOTSTRAPPED_MILLIS = -1L"
        const val NEVER_CONST_RERENDER = "const val NEVER_RERENDERED_MILLIS = -1L"
        const val BOOLEAN_RETURN = "): Boolean"
        const val PURITY_PROMISE = "不读时钟、不读时区、不读权限、不读 `Build.VERSION`"

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

        val GATES = listOf(
            GateSpec(BOOT_GATE, "hasEverBootstrapped", DECIDE_SIGNATURE, TIER_SUPER_BOOT, NEVER_CONST_BOOT),
            GateSpec(
                RERENDER_GATE,
                "hasEverRerendered",
                DECIDE_SIGNATURE_RERENDER,
                TIER_SUPER_RERENDER,
                NEVER_CONST_RERENDER,
            ),
        )

        /** 判据顺序名册（在册先例那条「顺序即语义」的同款）：打脸 → 从没领过 → 钟倒退 → 窗口内 */
        fun orderRules(flag: String): List<String> = listOf(
            "$flag == (lastClaimedAt == ",
            "!$flag ->",
            "nowElapsedRealtime - lastClaimedAt < 0L ->",
            "nowElapsedRealtime - lastClaimedAt < windowMillis ->",
        )
    }

    // ─────────────── ① 主尺：初始值不再与时间戳共用一格 ───────────────

    @Test
    fun `两枚调用点里那两枚病灶串代码侧零处而取证原文恰好一处`() {
        val commonRaw = source(COMMON)
        val common = blankComments(commonRaw)
        val providerRaw = source(PROVIDER)
        val provider = blankComments(providerRaw)

        // 卡面第 ③ 段那两格：?: 0L 与 = 0L 在两枚调用点的代码侧各为 0 枚
        for ((label, code) in listOf(COMMON to common, PROVIDER to provider)) {
            assertEquals(
                "$label 代码里「?: 0L」必须 0 处（把「没有上一次」借刻度起点那一枚零来表达，" +
                    "就是本卡病灶本身）。实到 " + occurrences(code, "?: 0L") + " 处",
                0,
                occurrences(code, "?: 0L"),
            )
            assertEquals(
                "$label 代码里「= 0L」必须 0 处（同一枚病的另一枚写法）。实到 " +
                    occurrences(code, "= 0L") + " 处",
                0,
                occurrences(code, "= 0L"),
            )
        }

        // 反套套逻辑：改前那四手在含注释的原文里各恰好 1 处（取证搬走 ⇒ 上面那些 0 就成了假绿）
        for ((file, needle, raw, code) in listOf(
            Octuple(COMMON, OLD_BOOT_INIT, commonRaw, common),
            Octuple(COMMON, OLD_BOOT_WINDOW, commonRaw, common),
            Octuple(PROVIDER, OLD_RERENDER_INIT, providerRaw, provider),
            Octuple(PROVIDER, OLD_RERENDER_WINDOW, providerRaw, provider),
        )) {
            assertEquals(
                "$file 里改前那一手「$needle」必须**代码 0 处 / 含注释恰好 1 处**：" +
                    "代码那一档是本卡修的病灶，注释那一档是取证 —— 读到 0 = 取证被搬走，" +
                    "上面那些 0 就成了「注释删干净」的假绿；读到 2 = 病灶回潮。实到 " +
                    occurrences(code, needle) + " 与 " + occurrences(raw, needle),
                listOf(0, 1),
                listOf(occurrences(code, needle), occurrences(raw, needle)),
            )
        }

        // 现推的旗与写回：各恰好一枚
        assertEquals(
            "「$BOOT_DERIVED_FLAG」必须恰好一枚 —— 「领过没有」只许**由哨兵现推**。实到 " +
                occurrences(common, BOOT_DERIVED_FLAG) + " 枚：写成常量 true 就是本卡病灶的变体" +
                "（窗口拿 -1 去比，开机那一分钟又整段跳过），改回与零比就是共用一格直接回来",
            1,
            occurrences(common, BOOT_DERIVED_FLAG),
        )
        assertEquals(
            "「$RERENDER_DERIVED_FLAG」必须恰好一枚 —— 「补绘过没有」只许由**那一格在不在**现推。实到 " +
                occurrences(provider, RERENDER_DERIVED_FLAG) + " 枚：另起一枚旗标与读数分居两处又是两把尺",
            1,
            occurrences(provider, RERENDER_DERIVED_FLAG),
        )
        assertEquals(
            "兜底那一级现在交的是哨兵，「$RERENDER_FALLBACK」恰好一枚。实到 " +
                occurrences(provider, RERENDER_FALLBACK) + " 枚",
            1,
            occurrences(provider, RERENDER_FALLBACK),
        )
        assertEquals(
            "两枚哨兵别名各恰好一枚：实到 " + occurrences(common, BOOT_SENTINEL_ALIAS) + " 与 " +
                occurrences(provider, RERENDER_SENTINEL_ALIAS) +
                " —— 谁把它们换成自己写的负数字面量，刻度与判据两头就各有一套「从没领过」",
            listOf(1, 1),
            listOf(occurrences(common, BOOT_SENTINEL_ALIAS), occurrences(provider, RERENDER_SENTINEL_ALIAS)),
        )
    }

    // ─────────────── ② 主尺：内核里短路与窗口的先后（顺序即语义） ───────────────

    @Test
    fun `从没领过那条短路排在窗口比较之前 两枚内核各一把`() {
        for (spec in GATES) {
            val code = blankComments(source(spec.file))
            val body = balancedBlock(code, "fun decide(", "${spec.file} 的 decide 体")
            val rules = orderRules(spec.flag)
            val positions = rules.map { body.indexOf(it) }
            for ((index, rule) in rules.withIndex()) {
                assertTrue(
                    "${spec.file} 判据「$rule」必须在场（第 $index 条，实到索引 ${positions[index]}；" +
                        "锚点漂了要回来重钉本守卫）",
                    positions[index] >= 0,
                )
                assertEquals(
                    "${spec.file} 判据「$rule」必须恰好一枚（实到 " + occurrences(body, rule) +
                        "）：少任何一枚就有两档要借同一句话说话，多的那枚就是有人新加了第二把尺",
                    1,
                    occurrences(body, rule),
                )
            }
            assertTrue(
                "**顺序钉**：「从没领过 ⇒ 放行」（索引 ${positions[1]}）必须排在窗口比较（索引 ${positions[3]}）" +
                    "之前。挪到后面 = 开机窗口之内的第一次领取又被压掉，逐字节每一行都对得上而缺陷整块回来 " +
                    "—— 这就是本卡题眼那一格（在册先例的 KDoc 原话：窗口判据在前 ⇒ 原缺陷复发）",
                positions[1] < positions[3],
            )
            assertTrue(
                "顺序钉第二格：哨兵打脸那一档排在一切时间判据之前（实到 ${positions[0]} 对 ${positions[1]}）",
                positions[0] < positions[1],
            )
            assertTrue(
                "顺序钉第三格：钟倒退那一档排在窗口比较之前（实到 ${positions[2]} 对 ${positions[3]}）：" +
                    "负差值一旦落进「小于窗口」那一支，就会被念成「刚刚领过」，而它明明是递错了尺 —— " +
                    "两件事不许共用一句话",
                positions[2] < positions[3],
            )
            assertEquals(
                "${spec.file} 的 decide 返回类型必须是分档而不是一枚 Boolean：「${spec.signature}」恰好 1 枚、" +
                    "「$BOOLEAN_RETURN」0 枚（实到 " + occurrences(code, spec.signature) + " 与 " +
                    occurrences(code, BOOLEAN_RETURN) + "）",
                listOf(1, 0),
                listOf(occurrences(code, spec.signature), occurrences(code, BOOLEAN_RETURN)),
            )
            assertEquals(
                "${spec.file} 档位枚数恰好 5（实到 " + occurrences(code, spec.tierSuper) +
                    "）：折成一枚 Boolean 是 0，把两档并成一是 4 —— 每一档都要有自己那句实话",
                5,
                occurrences(code, spec.tierSuper),
            )
        }
    }

    // ─────────────── ③ 主尺：那两手续排只长在放行档里 ───────────────

    @Test
    fun `补注册两手只长在放行档里 被压掉那一档一手都不许做`() {
        val code = blankComments(source(COMMON))
        val block = balancedBlock(code, BOOT_WHEN, "bootstrapBackgroundSync 的分派块")

        assertEquals(
            "分派那一手逐字节恰好一枚：「$BOOT_WHEN」。实到 " + occurrences(code, BOOT_WHEN),
            1, occurrences(code, BOOT_WHEN),
        )
        for (branch in BOOT_BRANCHES) {
            assertEquals(
                "档位分支「$branch」恰好一枚（少一枚就是那一档在代码里没有自己的处置，" +
                    "多一枚就是同一档两处分别处置）。实到 " + occurrences(block, branch),
                1, occurrences(block, branch),
            )
        }
        assertEquals(
            "那两手续排在分派块里恰好 **3** 枚（实到 " + occurrences(block, BOOT_ACTION) +
                "）：首次领取 ＋ 出窗放行 ＋ 打脸偏向放行；涨上去就是有档位偷注册两遍，掉下去就是有档没人注册",
            3, occurrences(block, BOOT_ACTION),
        )
        val duplicate = balancedBlock(block, BOOT_DUPLICATE_BRANCH, "去重那一档")
        assertEquals(
            "被压掉那一档里**不许**有补注册（实到 " + occurrences(duplicate, BOOT_ACTION) + " 枚）：" +
                "判据说压掉、代码却照注册，分档就白折了 —— 那是「把几档折成一枚 Boolean」的另一种写法",
            0, occurrences(duplicate, BOOT_ACTION),
        )
        assertEquals(
            "零点闹钟那一手在本文件里恰好 1 枚（多一枚就是又开了第二处注册点）。实到 " +
                occurrences(code, BOOT_MIDNIGHT),
            1, occurrences(code, BOOT_MIDNIGHT),
        )
        assertEquals(
            "兜底任务那一手在本文件里恰好 1 枚。实到 " + occurrences(code, BOOT_FALLBACK),
            1, occurrences(code, BOOT_FALLBACK),
        )
    }

    // ─────────────── ④ 痕迹：被压掉与被发放各有一条读得到的话 ───────────────

    @Test
    fun `发放与压掉两条各有自己的痕迹 这一族零枚 Log 得不许`() {
        val bootBlock = balancedBlock(blankComments(source(COMMON)), BOOT_WHEN, "补注册的分派块")
        val rerenderBlock = balancedBlock(blankComments(source(PROVIDER)), RERENDER_WHEN, "补绘的分派块")

        for ((label, block) in listOf("补注册" to bootBlock, "补绘" to rerenderBlock)) {
            val marks = occurrences(block, "Log.i(") + occurrences(block, "Log.w(")
            assertTrue(
                "$label 那一族的分派块里 Log.i/Log.w 至少 4 枚（实到 $marks）：每一档各说一句，" +
                    "少一枚就又有一档静默 —— 本卡第 2 问点名的正是「咽掉之后用户与取证都看不见」",
                marks >= 4,
            )
            assertEquals(
                "$label 这一族里 `Log.d` 必须 0 枚（真机 logcat 被 HyperOS 截在 Info 级，写了等于没写）。" +
                    "实到 " + occurrences(block, "Log.d"),
                0, occurrences(block, "Log.d"),
            )
            assertTrue(
                "$label 这一族的「发放」与「压掉」两条都得留在 Log.i 那一级（实到 " +
                    occurrences(block, "Log.i(") + " 枚，至少 2 枚：一条发放、一条压掉）",
                occurrences(block, "Log.i(") >= 2,
            )
        }
        // 块内逐档点名：每一档恰好一枚痕迹
        val roster = BOOT_BRANCHES.map { COMMON to it } + RERENDER_BRANCHES.map { PROVIDER to it }
        for ((file, anchor) in roster) {
            val body = balancedBlock(blankComments(source(file)), anchor, "档位「$anchor」")
            val hits = occurrences(body, "Log.i(") + occurrences(body, "Log.w(")
            assertEquals(
                "$file 档位「$anchor」的块里必须恰好 1 枚痕迹（实到 $hits）：0 枚就是改前那种" +
                    "「整段跳过而一句话不说」，2 枚就是同一档两处各说一遍",
                1, hits,
            )
        }
        // 补绘那一族的返回值方向也钉死：三枚放行 return false、两枚压掉 return true
        assertEquals(
            "补绘分派块里 `false`（= 不压掉）恰好 3 枚（实到 " + occurrences(rerenderBlock, "false") +
                "）：首次、出窗、打脸三档放行",
            3, occurrences(rerenderBlock, "false"),
        )
        assertEquals(
            "补绘分派块里 `true`（= 压掉）恰好 2 枚（实到 " + occurrences(rerenderBlock, "true") +
                "）：窗口内去重 ＋ 读数倒退",
            2, occurrences(rerenderBlock, "true"),
        )
    }

    // ─────────────── ⑤ 内核纯度：判据体内一个字都不读 ───────────────

    @Test
    fun `两枚内核零 android 也不读钟读区读权限`() {
        for (spec in GATES) {
            val raw = source(spec.file)
            val offenders = raw.lineSequence()
                .map { it.trim() }
                .filter { it.startsWith("import ") }
                .filter { it.contains("android.") || it.contains("androidx.") }
                .toList()
            assertEquals("${spec.file} 必须零 android / androidx import。实到：" + offenders,
                emptyList<String>(), offenders)

            val code = blankComments(raw)
            for (needle in FORBIDDEN_READS) {
                assertEquals(
                    "${spec.file} 里「$needle」必须 0 处：读了它，「这一次要不要领配额」就不再是纯函数 —— " +
                        "同一次唤醒里的两枚投递可能给出不同结论（本仓口径：这些留在调用点当参数递进来）。" +
                        "\n  复算：把 ${spec.file} 的注释抹成空格后数「$needle」\n  实到枚数：" +
                        occurrences(code, needle),
                    0, occurrences(code, needle),
                )
            }
            // 反套套逻辑：KDoc 里那条纯度承诺必须留痕，否则上面那串 0 没人负责
            assertEquals(
                "${spec.file} 的 KDoc 里那句纯度承诺必须留痕（含注释原文恰好 1 处）。实到：" +
                    occurrences(raw, PURITY_PROMISE),
                1, occurrences(raw, PURITY_PROMISE),
            )
        }
    }

    // ─────────────── ⑥ 哨兵全仓只有一枚定义 ───────────────

    @Test
    fun `两枚哨兵各只有一枚定义 领取那两手不许写裸负数字面量`() {
        for (spec in GATES) {
            assertEquals(
                "「${spec.neverConst}」全仓只许有一枚定义（内核一枚、调用点只引用不另写）。实到 " +
                    occurrences(blankComments(source(spec.file)), spec.neverConst),
                1, occurrences(blankComments(source(spec.file)), spec.neverConst),
            )
        }
        // ⚠️ 覆盖面如实写：这一格只扫**领取那两手**（两枚 claim 函数体内），不是整枚文件 ——
        // WidgetCommon 里另有一枚与本族无关的既有 `-1L`（RemoteViews extra 的「没有课程 id」那一级兜底），
        // 把它一起数进来就是拿别人的账顶本卡的尺。
        val bootBody = balancedBlock(
            blankComments(source(COMMON)),
            "private fun claimBootstrapWindow(): WidgetBootstrapWindow {",
            "claimBootstrapWindow",
        )
        val rerenderBody = balancedBlock(
            blankComments(source(PROVIDER)),
            "private fun claimRerenderWindow(appWidgetId: Int): WidgetRerenderWindow =",
            "claimRerenderWindow",
        )
        // 报错消息里这一句只把 -1L 当「领过没有」那一枚哨兵念，不当 elapsedRealtime 的读数念
        assertEquals(
            "两枚领取函数体内裸 `-1L` 必须 0 枚（实到 " + occurrences(bootBody, "-1L") + " 与 " +
                occurrences(rerenderBody, "-1L") + "）：自己写一枚负数就是又开一套刻度 —— " +
                "而那一枚数字是「单调钟取不出负数」那一侧的专属值，不是一枚时刻",
            listOf(0, 0),
            listOf(occurrences(bootBody, "-1L"), occurrences(rerenderBody, "-1L")),
        )
        assertEquals(
            "补绘那一枚文件全篇裸 `-1L` 也恰好 0 枚（实到 " +
                occurrences(blankComments(source(PROVIDER)), "-1L") + "）",
            0, occurrences(blankComments(source(PROVIDER)), "-1L"),
        )
    }

    // ─────────────── ⑦ 领取那一手：写回只有一处，取钟住在调用点 ───────────────

    @Test
    fun `写回只有一处 取钟住在调用点 窗口常数逐字节钉死`() {
        val common = blankComments(source(COMMON))
        val provider = blankComments(source(PROVIDER))
        val bootBody = balancedBlock(
            common,
            "private fun claimBootstrapWindow(): WidgetBootstrapWindow {",
            "claimBootstrapWindow",
        )
        val rerenderBody = balancedBlock(
            provider,
            "private fun claimRerenderWindow(appWidgetId: Int): WidgetRerenderWindow =",
            "claimRerenderWindow",
        )

        assertEquals(
            "CAS 那一手在领取函数体内恰好一枚（0 枚就是退回先读后写：同一次广播里六枚协程全领到配额，" +
                "那道时间闸白夹）。实到 " + occurrences(bootBody, BOOT_CAS),
            1, occurrences(bootBody, BOOT_CAS),
        )
        assertEquals(
            "除 CAS 之外不许有第二处写哨兵：体内「lastBootstrapAt.set(」必须 0 枚（实到 " +
                occurrences(bootBody, "lastBootstrapAt.set(") + "）",
            0, occurrences(bootBody, "lastBootstrapAt.set("),
        )
        assertEquals(
            "载体那一枚初始化逐字节一枚「$BOOT_ATOMIC_INIT」（改前那一枚是刻度起点上的零，" +
                "而旧载体是 @Volatile 的先读后写）。实到 " + occurrences(common, BOOT_ATOMIC_INIT),
            1, occurrences(common, BOOT_ATOMIC_INIT),
        )
        assertEquals(
            "补注册那一枚体内取钟恰好一枚「$READ_CLOCK_SHORT」：判据本体一个字都不读钟，" +
                "读数在这里现取再递进去。实到 " + occurrences(bootBody, READ_CLOCK_SHORT),
            1, occurrences(bootBody, READ_CLOCK_SHORT),
        )
        assertEquals(
            "补绘那一枚体内取钟恰好一枚「$READ_CLOCK_FQ」。实到 " + occurrences(rerenderBody, READ_CLOCK_FQ),
            1, occurrences(rerenderBody, READ_CLOCK_FQ),
        )
        assertEquals(
            "两枚判据各只被调一枚（实到 " + occurrences(common, "WidgetBootstrapWindowGate.decide(") +
                " 与 " + occurrences(provider, "WidgetRerenderWindowGate.decide(") +
                "）：两处判据 = 两把尺各判各的",
            listOf(1, 1),
            listOf(
                occurrences(common, "WidgetBootstrapWindowGate.decide("),
                occurrences(provider, "WidgetRerenderWindowGate.decide("),
            ),
        )
        assertEquals(
            "补绘那一格的写回只有一处「$RERENDER_WRITE_BACK」（没领到配额那一格一格都不许动）。实到 " +
                occurrences(rerenderBody, RERENDER_WRITE_BACK),
            1, occurrences(rerenderBody, RERENDER_WRITE_BACK),
        )
        assertEquals(
            "补绘那一族的读改写还在同一把锁里（实到 " + occurrences(provider, SYNCHRONIZED) + " 枚）：" +
                "按实例分键的记忆单元不许换成进程级单值",
            1, occurrences(provider, SYNCHRONIZED),
        )
        assertEquals(
            "窗口常数逐字节钉死「$BOOT_WINDOW_CONST」恰好一枚（窗口值与「初始值 vs 时间戳」是两件事，" +
                "要动它请连表驱动与本页那些格一起重钉）。实到 " + occurrences(common, BOOT_WINDOW_CONST),
            1, occurrences(common, BOOT_WINDOW_CONST),
        )
        assertEquals(
            "窗口常数逐字节钉死「$RERENDER_WINDOW_CONST」恰好一枚。实到 " +
                occurrences(provider, RERENDER_WINDOW_CONST),
            1, occurrences(provider, RERENDER_WINDOW_CONST),
        )
    }

    // ─────────────── ⑧ 运行时反证：内核与调用点认得的是同一套档位 ───────────────

    @Test
    fun `运行时那一侧也认得五档 两枚题眼格仍是放行`() {
        val never = WidgetBootstrapSentinel.NEVER_BOOTSTRAPPED_MILLIS
        val bootTiers = listOf(
            WidgetBootstrapWindowGate.decide(30_000L, never, 60_000L, false),
            WidgetBootstrapWindowGate.decide(2_000_000L, 5_000L, 60_000L, true),
            WidgetBootstrapWindowGate.decide(100_000L, 50_000L, 60_000L, true),
            WidgetBootstrapWindowGate.decide(10_000L, 50_000L, 60_000L, true),
            WidgetBootstrapWindowGate.decide(30_000L, never, 60_000L, true),
        ).map { it.javaClass.simpleName }
        assertEquals("补注册内核五枚输入必须交出五枚不同档位（实到 $bootTiers）—— 有档折掉了，" +
            "上面那些计数格就不再是本卡的尺", 5, bootTiers.distinct().size)
        assertEquals(
            "题眼格（初始哨兵 + 开机窗口之内）今天必须是首次领取那一档，实到 ${bootTiers[0]}：" +
                "变成 SuppressedAsDuplicate 就是台账 #496 复发",
            WidgetBootstrapWindow.GrantedFirstBootstrap::class.simpleName, bootTiers[0],
        )
        assertEquals(
            "窗口之内的真重复仍必须是压掉档，实到 ${bootTiers[2]}：去重语义不许被修坏",
            WidgetBootstrapWindow.SuppressedAsDuplicate::class.simpleName, bootTiers[2],
        )

        val rerenderNever = WidgetRerenderSentinel.NEVER_RERENDERED_MILLIS
        val rerenderTiers = listOf(
            WidgetRerenderWindowGate.decide(1_000L, rerenderNever, 1_500L, false),
            WidgetRerenderWindowGate.decide(600_000L, 1_000L, 1_500L, true),
            WidgetRerenderWindowGate.decide(3_000L, 2_000L, 1_500L, true),
            WidgetRerenderWindowGate.decide(1_000L, 5_000L, 1_500L, true),
            WidgetRerenderWindowGate.decide(1_000L, rerenderNever, 1_500L, true),
        ).map { it.javaClass.simpleName }
        assertEquals("补绘内核五枚输入必须交出五枚不同档位（实到 $rerenderTiers）", 5, rerenderTiers.distinct().size)
        assertEquals(
            "补绘题眼格今天必须是首次补绘那一档，实到 ${rerenderTiers[0]}（这一枚按 ⓪ 段裁的形状卫生钉，" +
                "不主张真机可达性）",
            WidgetRerenderWindow.GrantedFirstRerender::class.simpleName, rerenderTiers[0],
        )
        assertEquals(
            "两枚哨兵今天还是「单调钟取不出负数」那一侧的那一枚专属值（实到 " + never + " 与 " +
                rerenderNever + "）：把它们改成 0L 就是「刻度起点与初始值同格」直接回潮。" +
                "这一枚不是 elapsedRealtime 的读数，不许当时刻念",
            listOf(-1L, -1L),
            listOf(never, rerenderNever),
        )
    }

    /** 四元组只给本守卫那一串「代码 0 / 取证 1」的复算用（Kotlin 标准库只到 Triple） */
    private data class Octuple(
        val file: String,
        val needle: String,
        val raw: String,
        val code: String,
    )

    // ---------------- helpers（与 WidgetWeekBrowseRequestCodeWiringGuardTest 同写法） ----------------

    private fun source(relative: String): String =
        normalizeNewlines(File(findMainJavaDir(), relative).readText())

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

    /**
     * 把 `//` 与块注释抹成空格（字符串保留）。
     *
     * 形状口径（本仓在册那条）：这一族**不许**用「字符数组 ＋ 包成 String」的写法 ——
     * 那会顶高在册助手形状守卫的写死值（三格同红，而过滤门禁盖不住）；
     * StringBuilder ＋ replace ＋ lines/joinToString 才是合规范式。
     */
    private fun blankComments(source: String): String {
        val out = StringBuilder(source)
        var block = out.indexOf("/*")
        while (block >= 0) {
            val end = out.indexOf("*/", block + 2)
            if (end < 0) break
            out.replace(block, end + 2, " ")
            block = out.indexOf("/*")
        }
        val text = out.toString()
        return text.lines().joinToString("\n") { line ->
            val slash = line.indexOf("//")
            if (slash >= 0) line.substring(0, slash) else line
        }
    }

    /** 工作树是 CRLF（core.autocrlf=true），针里的换行统一按 LF 写 */
    private fun normalizeNewlines(text: String): String = text.replace("\r\n", "\n")

    /** 单测的 cwd 可能是 :app 模块目录也可能是仓库根：两种布局都试，全落空就抛 */
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
