package com.buaa.schedule.reminder

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 实况降级那条路**接对了没有**的守卫（#119 / T78，2026-09-23）。
 *
 * [LiveFgsRetryDecisionTest] 钉的是判据内核那张表；这张表在设备上其实一次都没被执行过，
 * 除非 `CourseFluidService` 那两个吞异常的地方真的把读数递给内核、内核的结论真的去重排。
 * 本模块没有 Robolectric（android.jar 里全是抛 "not mocked" 的桩），起不了服务，
 * 所以刀法照抄 [com.buaa.schedule.ui.home.ToolbarCampusWidthGuardTest]：读 `app/src/main`
 * 源文件文本、匹配前先 `blankComments` 抹注释（钉的是接线，不是白话）、括号配平取实参表与
 * 内容块、**找不到锚点就抛**、不许用 `assumeTrue` 跳过 —— 跳过与没有守卫是同一件事。
 *
 * 前面五档各自钉一条已经付过学费的约束：
 * 1. `postProgressNotification()` 里 `startForeground` 那处 onFailure（当前源码 `:163`）与
 *    `start()` 里 `ContextCompat.startForegroundService` 那处（当前源码 `:435`）**两处都过 reportLiveDegrade**
 *    （台账 #119 记的是改前基线上的 `:159` / `:310`）：有人嫌取证烦、退回"只留一行 WARN"，
 *    本卡的账立刻又变成看不见的；
 * 2. 全服务的 `Build.VERSION.SDK_INT` 只许两枚，其中**属于这条判据的那一枚只能写在
 *    [keepsAlarmClockExemption]** 一处 —— "内核返回布尔、调用点各判一次 SDK_INT"是本仓硬禁；
 * 3. [LiveFgsRetry.kt] 零 android import、零时钟读取：`now` 只在调用点算一次递进去，
 *    否则这张表在单测里量不到、只能靠推断；
 * 4. 重排挂在应用级作用域且整段被 `runCatching` 包住：它站在 `onFailure` 里，逃出去的异常
 *    会把下课铃广播（[ClassProgressReceiver] 整段 runCatching 之内）或服务主线程直接打崩；
 * 5. 降级这条路不产生任何**新的**用户可见打扰：通知 id 仍是 `20_260_002`，不加渠道、
 *    不弹 toast、不动勿扰、不排 WorkManager、不开新 Handler 线程，设置页也不加说明行。
 *
 * T78b 复核时补的三档（钉的是上面五条各自"漏判"的那一面）：
 * 6. 取证那一行**必须在源码里活着**（前四条只钉"没人退回 WARN"，不钉这行本身）——
 *    [ReleaseForensicLogSurvivalTest] 的产物层也读它，但那一层没有 release 产物时会跳过；
 * 7. 降级这条路**不许多出一台调度器**：本卡换的是"触发源"，走的是既有的
 *    `ClassProgressScheduler.rescheduleNextWindow`，不是新写一枚 AlarmManager / WorkRequest /
 *    Handler —— 后者会把这台机器上量到的豁免档判据换成一张没人量过的排程表；
 * 8. `reportLiveDegrade` **整条路不许把异常抛回调用方**：凡碰 android 框架的调用
 *    （`getSystemService` / `applicationContext` / `applicationScope`）都得站在 `runCatching` 里，
 *    这条路本身也不许出现 `throw`。
 */
class LiveFgsDegradeWiringGuardTest {

    // ---- ① 两处吞异常的站点都得报 --------------------------------------------------------------------

    /**
     * 两个站点各自：`.onFailure` 块里必须调 `reportLiveDegrade`，且原来那行带栈的 WARN 还在。
     *
     * 取证行自己不带 trace（一行判据 + 栈由 WARN 带），所以"WARN 没了"和"reportLiveDegrade 没了"
     * 是同一种回归的两半，这里一起钉。
     */
    @Test
    fun bothSwallowingSitesHandTheFailureToTheReporter() {
        val svc = blankComments(source(SERVICE))
        val cases = listOf(
            SiteCase(
                label = "postProgressNotification / startForeground",
                body = balancedBlock(svc, "private fun postProgressNotification()"),
                callName = "startForeground(",
                warnHead = "startForeground 失败，回退普通常驻通知",
                siteConstant = "SITE_START_FOREGROUND",
            ),
            SiteCase(
                label = "start / startForegroundService",
                body = balancedBlock(svc, "fun start("),
                callName = "ContextCompat.startForegroundService(",
                warnHead = "启动课程实况前台服务失败，回退普通常驻通知",
                siteConstant = "SITE_START_SERVICE",
            ),
        )
        for (case in cases) {
            val onFailureBlocks = onFailureBlocks(case.body, case.label)
            assertEquals(
                "${case.label} 里 .onFailure 的枚数应当恰好一枚（两枚 = 有一处吞异常没走到取证，" +
                    "零枚 = 这一档改成了往外抛）：\n" + case.body.lines().filter { it.contains(".onFailure") },
                1,
                onFailureBlocks.size,
            )
            val block = onFailureBlocks[0]
            assertTrue(
                "${case.label} 的 .onFailure 里不再调 reportLiveDegrade —— #119 报的就是" +
                    "「服务起不来时用户完全看不见」，退回一行 WARN 等于把这张卡重新打开：\n$block",
                Regex("""\breportLiveDegrade\(""").containsMatchIn(block),
            )
            assertTrue(
                "${case.label} 报的站名不对（`liveFgsDegraded site=` 那一格靠它分档）：\n$block",
                block.contains("site = ${case.siteConstant}"),
            )
            assertTrue(
                "${case.label} 里那一句调用点级 ${case.callName} 没了：本档核的对象换了地方",
                case.body.contains(case.callName),
            )
            assertTrue(
                "${case.label} 的 .onFailure 里丢了带栈的那行 WARN「${case.warnHead}」：" +
                    "取证行只有一行判据，栈是它带的：\n$block",
                Regex("""Log\.w\(\s*TAG,\s*"${Regex.escape(case.warnHead)}""").containsMatchIn(block),
            )
        }
        // 站名必须是两枚不同的常量，否则 logcat 里分不出档
        val sites = Regex("""private const val SITE_\w+ = "([^"]+)"""").findAll(svc).map { it.groupValues[1] }.toList()
        assertEquals("两枚站名常量（startForeground / startService）：$sites", listOf("startForeground", "startService"), sites.sorted())
    }

    private data class SiteCase(
        val label: String,
        val body: String,
        val callName: String,
        val warnHead: String,
        val siteConstant: String,
    )

    /** 每一次 `reportLiveDegrade(` **调用**都站在某个 `.onFailure` 块里：不站在吞异常处就该红 */
    @Test
    fun theReporterIsCalledFromNoOtherPlaceThanAnOnFailure() {
        val svc = blankComments(source(SERVICE))
        val calls = Regex("""\breportLiveDegrade\(""").findAll(svc)
            .filter { !svc.substring(0, it.range.first).trimEnd().endsWith("fun") }
            .map { it.range.first }
            .toList()
        assertEquals("reportLiveDegrade 的调用点数应当恰好两处（:163 与 :435）：${calls.size}", 2, calls.size)
        val ranges = onFailureRanges(svc)
        val orphans = calls.filter { at -> ranges.none { range -> at in range } }
        assertEquals("有 reportLiveDegrade 的调用点不在 .onFailure 里面（那它报的就不是降级，本卡的口径作废）", emptyList<Int>(), orphans)
    }

    /** 整份源码里每一枚 `.onFailure { ... }` 内容块在原串中的位置区间 */
    private fun onFailureRanges(code: String): List<IntRange> =
        Regex("""\.onFailure\s*\{""").findAll(code).map { match ->
            val open = match.range.last
            open..(open + balancedBraces(code, open).length - 1)
        }.toList()

    // ---- ② SDK_INT 只判一次 ---------------------------------------------------------------------------

    /**
     * 全服务只留两枚 `Build.VERSION.SDK_INT`：属于降级判据的那一枚只能在
     * [CourseFluidService] 的 `keepsAlarmClockExemption()` 里，另一枚是
     * `buildProgressNotification()` 里 Android 16+ 的 ProgressStyle 闸门（基点 `69f7b3a` 就在，
     * 与判据无关）。调用点（`reportLiveDegrade`）只许收算好的布尔。
     */
    @Test
    fun theSdkIntGateIsJudgedExactlyOnceOnThisPath() {
        val svc = blankComments(source(SERVICE))
        val kernel = balancedBlock(svc, "private fun keepsAlarmClockExemption(")
        val styleGate = balancedBlock(svc, "private fun buildProgressNotification(")
        val reads = Regex("""Build\.VERSION\.SDK_INT""").findAll(svc).map { it.range.first }.toList()
        assertEquals(
            "全服务 Build.VERSION.SDK_INT 的枚数应当恰好两枚（一枚闹钟豁免档判据 + 一枚基点就有的 " +
                "Android 16 ProgressStyle 闸门）—— 多一枚就是有人在调用点又判了一遍 SDK_INT，" +
                "那张判据表就不再是唯一真相：\n" +
                Regex(""".*Build\.VERSION\.SDK_INT.*""").findAll(svc).map { it.value.trim() }.joinToString("\n"),
            2,
            reads.size,
        )
        val inside = listOf(kernel, styleGate).count { gate -> reads.any { gate.containsAt(it, svc) } }
        assertEquals("两枚 SDK_INT 都应当落在已点名的两个函数体里：$inside", 2, inside)
        for (banned in listOf(
            balancedBlock(svc, "private fun reportLiveDegrade("),
            balancedBlock(svc, "private fun postProgressNotification()"),
            balancedBlock(svc, "fun start("),
        )) {
            assertFalse("判据的调用点里不许出现 Build.VERSION（内核收布尔）：\n$banned", banned.contains("Build.VERSION"))
        }
        // 属于判据的那一枚：S 档 + 精确闹钟授权，全服务各只一处，且都在 keepsAlarmClockExemption 里
        assertEquals("Build.VERSION_CODES.S 只许出现在 keepsAlarmClockExemption 一处", 1, Regex("""Build\.VERSION_CODES\.S\b""").findAll(svc).count())
        assertEquals("canScheduleExactAlarms 只许出现在 keepsAlarmClockExemption 一处", 1, svc.countOf("canScheduleExactAlarms"))
        assertTrue(
            "Build.VERSION_CODES.S 不在 keepsAlarmClockExemption 里了：判 SDK_INT 的地方搬家，本卡第 2 档要重写",
            kernel.contains("Build.VERSION_CODES.S"),
        )
        // 递进内核的是算好的布尔，不是让内核自己判
        val reporter = balancedBlock(svc, "private fun reportLiveDegrade(")
        assertTrue(
            "nextLiveFgsRetry 的 nextAttemptKeepsAlarmClockExemption 实参必须是 keepsAlarmClockExemption(...) 的结果：" +
                "\n" + argOf(reporter, "nextLiveFgsRetry(").orEmpty(),
            argOf(reporter, "nextLiveFgsRetry(")?.contains("nextAttemptKeepsAlarmClockExemption = keepsAlarmClockExemption(") == true,
        )
    }

    // ---- ③ 内核零 android、零时钟 ----------------------------------------------------------------------

    @Test
    fun theDecisionKernelTouchesNeitherAndroidNorTheClock() {
        val kernelText = blankComments(source(KERNEL))
        assertEquals(
            "LiveFgsRetry.kt 的 import 必须为零（它是纯判据内核，一旦 import 了 android，" +
                "本模块的 JVM 单测就跑不动这张表了）：\n" +
                Regex("""^import\b.*""", RegexOption.MULTILINE).findAll(kernelText).joinToString("\n") { it.value },
            0,
            Regex("""^import\b""", RegexOption.MULTILINE).findAll(kernelText).count(),
        )
        for (banned in listOf("android", "Build.", "System.currentTimeMillis", "java.time", "Calendar", "Date(", "Instant", "TimeZone", "Context")) {
            assertFalse("判据内核里出现了「$banned」：时钟与设备状态只能在调用点算成参数递进来", kernelText.contains(banned))
        }
        // 「Clock」这个词不列进黑名单：形参名 nextAttemptKeepsAlarmClock**Exemption** 里就带着它，
        // 钉住 currentTimeMillis / java.time / Calendar / Date( 这四条才是"时钟只在调用点读"的口径
        // 内核的入参形状：四枚布尔 + 两枚整型，全是调用点算好的值
        val signature = argOf(kernelText, "internal fun nextLiveFgsRetry(").orEmpty()
        val params = signature.split(",")
            .map { it.substringAfter("\n").trim().removeSuffix(")").trim() }
            .filter { it.isNotEmpty() }
        assertEquals(
            "nextLiveFgsRetry 的形参表（顺序就是判据表的顺序，多一枚少一枚都要连着单测改）：\n$signature",
            listOf(
                "windowStillLive: Boolean", "inClassPhase: Boolean", "bellAlreadyRung: Boolean",
                "nextAttemptKeepsAlarmClockExemption: Boolean", "attemptsAlreadyArmed: Int", "maxAttempts: Int",
            ),
            params,
        )
        assertEquals("窗口身份判据的形参表也要成对核一次", 5, argOf(kernelText, "internal fun liveFgsAttemptsAlreadyArmed(")!!.split(",").size - 1)
    }

    // ---- ④ 重排挂应用级作用域、整段 runCatching ----------------------------------------------------------

    @Test
    fun theRescheduleRidesTheApplicationScopeInsideRunCatching() {
        val svc = blankComments(source(SERVICE))
        val reporter = balancedBlock(svc, "private fun reportLiveDegrade(")
        assertTrue(
            "重排不再挂应用级作用域（(app as? BUAAApplication)?.applicationScope）：这里可能正站在一个" +
                "马上 onDestroy 的服务里，挂自己作用域会把刚排出去的窗口取消掉\n$reporter",
            Regex("""\(app as\? BUAAApplication\)\?\.applicationScope\?\.launch""").containsMatchIn(reporter),
        )
        val wrapped = Regex("""runCatching\s*\{""").findAll(reporter)
            .map { balancedBraces(reporter, it.range.last) }
            .toList()
        assertTrue(
            "那条重排整段必须被 runCatching 包住：它站在 onFailure 里，从这儿逃出去的异常会把" +
                "下课铃广播或服务主线程直接打崩（本卡不许引入崩溃）",
            wrapped.any { it.contains("applicationScope?.launch") && it.contains("rescheduleNextWindow(") },
        )
        assertEquals("reportLiveDegrade 里 runCatching 的枚数（一条重排 + 一条取 applicationContext）", 2, wrapped.size)
        // 只有 ArmOnce 才排，且账要记在锁里
        assertTrue("缺了 `if (decision != LiveFgsRetry.ArmOnce) return`：不排的档也会排出去", reporter.contains("!= LiveFgsRetry.ArmOnce) return"))
        assertTrue(
            "读账与写账不在同一把 synchronized(retryLedger) 里：两枚站点（:163 / :435）并发报时会各自以为还没试过（自激闸门失效）",
            Regex("""synchronized\(\s*retryLedger\s*\)""").containsMatchIn(reporter) &&
                argOf(reporter, "liveFgsAttemptsAlreadyArmed(") != null,
        )
        // 上限是写死的 1，且从常量递给内核（不许在调用点抄字面量）
        assertEquals("MAX_LIVE_FGS_RETRY 只许定义一次", 1, Regex("""MAX_LIVE_FGS_RETRY\s*=""").findAll(svc).count())
        assertTrue(
            "重排上限必须写成 `private const val MAX_LIVE_FGS_RETRY = 1`（挡自激回路的那道闸）",
            Regex("""private const val MAX_LIVE_FGS_RETRY = 1\b""").containsMatchIn(svc),
        )
        assertTrue(
            "内核的 maxAttempts 实参必须是常量而不是抄一份数字：抄的那份改起来没人管",
            argOf(reporter, "nextLiveFgsRetry(")?.contains("maxAttempts = MAX_LIVE_FGS_RETRY") == true,
        )
    }

    // ---- ⑤ 不许有新的用户可见打扰 ------------------------------------------------------------------------

    @Test
    fun degradingStaysInvisibleBeyondTheExistingNotification() {
        val svc = blankComments(source(SERVICE))
        val reporter = balancedBlock(svc, "private fun reportLiveDegrade(")
        for (banned in listOf(
            "Toast", "createNotificationChannel", "NotificationChannel", ".notify(", "setInterruptionFilter",
            "InterruptionFilter", "ZenConfig", "WorkManager", "Handler(", "Looper", "startForeground(",
        )) {
            assertFalse("降级那一步里出现了「$banned」：一次降级不该再骚扰用户第二遍，也不该新起线程/新排任务\n$reporter", reporter.contains(banned))
        }
        assertEquals("实况通知 id 只许一处定义", 1, Regex("""private const val NOTIFY_ID = 20_260_002""").findAll(svc).count())
        // 服务里新增的那段取证不许搬到设置页 / 资源里（那是"用另一处打扰换掉这一处打扰"）
        val settings = blankComments(source("com/buaa/schedule/ui/settings/SettingsScreen.kt"))
        val res = resValuesText()
        for (text in listOf(settings to "SettingsScreen.kt", res to "res/values")) {
            for (banned in listOf("liveFgsDegraded", "实况降级")) {
                assertFalse("${text.second} 里出现了「$banned」：#119 要的是 logcat 取证 + 自动重排，不是设置页加一行说明", text.first.contains(banned))
            }
        }
    }

    // ---- ⑥ 取证那一行本身活着（第 1~5 档只钉"没人退回 WARN"，不钉这行）--------------------------------

    /**
     * `liveFgsDegraded site=… action=…` 这行必须在 [reportLiveDegrade] 里、级别是 `w`、
     * 带 site 与 action 两格，而且**全服务只此一枚**。
     *
     * 它是 #119 从"用户完全看不境"里换回来的唯一东西：`ReleaseForensicLogSurvivalTest` 的产物层
     * 也读它，但那一层没有 release 产物时会 `assumeTrue` 跳过 —— 恒跑的下限只能钉在这里。
     * 第二枚同名行也不许出现：两行取证迟早只剩一行还有人读，分档就又是猜的。
     */
    @Test
    fun theForensicLineItselfIsStillThereAndIsTheOnlyOne() {
        val svc = blankComments(source(SERVICE))
        val reporter = balancedBlock(svc, "private fun reportLiveDegrade(")
        assertTrue(
            "reportLiveDegrade 里那行 `Log.w(TAG, \"liveFgsDegraded site=…\")` 没了或换名了：" +
                "`logcat -d -s CourseFluidService | grep liveFgsDegraded` 是这张卡唯一的取证口\n$reporter",
            Regex("""Log\.w\(\s*TAG,\s*"liveFgsDegraded site=""").containsMatchIn(reporter),
        )
        assertEquals(
            "全服务 liveFgsDegraded 取证行只许一枚（多一枚就是分档换了口径、少一枚就是回到静默）",
            1,
            Regex(""""liveFgsDegraded""").findAll(svc).count(),
        )
        // 两格都得在：site 分两枚站点，action 分六档
        for (column in listOf("site=$", "action=$")) {
            assertTrue("取证行丢了「$column」那一格：logcat 里就分不出档了", reporter.contains(column))
        }
        assertTrue(
            "取证文案搬进了资源条目：那样它就改由 R8 的资源收缩负责删除，产物层守卫的判据要整个重写",
            !reporter.contains("getString(R.string"),
        )
    }

    // ---- ⑦ 这条路不许多出一台调度器 ---------------------------------------------------------------------

    /**
     * 本卡换的是**触发源**，出口只有既有的 [ClassProgressScheduler.rescheduleNextWindow] 一条：
     * 降级这条路里不许新写 AlarmManager 排程、不许排 WorkManager、不许开 Handler/线程。
     *
     * 为什么值得钉死：`Handler(` / `Looper` 在服务里本来就有 2/3 枚（进度条自己滴答用的，
     * 基点 `69f7b3a` 就在），所以"整份文件没有 Handler"是假判据 —— 靶子只能是这条路本身。
     * 新造一台调度器等于把这台机器上量到的豁免档判据换成一张没人量过的排程表。
     */
    @Test
    fun theDegradePathAddsNoNewScheduler() {
        val svc = blankComments(source(SERVICE))
        val path = balancedBlock(svc, "private fun reportLiveDegrade(") +
            balancedBlock(svc, "private fun keepsAlarmClockExemption(")
        for (banned in listOf(
            "WorkRequest", "WorkManager", "enqueue(", "JobScheduler", "AlarmScheduler",
            "setAlarmClock(", "setExact(", "setExactAndAllowWhileIdle(", "setAndAllowWhileIdle(",
            "alarmManager.set", ".set(", "Handler(", "Looper", "Thread(", "postDelayed",
            "startForegroundService(", "startForeground(",
        )) {
            assertFalse(
                "降级这条路上出现了「$banned」：这一档的出路只有『走既有的 rescheduleNextWindow 换触发源』" +
                    "一条，新排一台调度器 / 重投同一发都不在读数支持的范围里\n$path",
                path.contains(banned),
            )
        }
        // 唯一的出口，而且走的是那个既有的类（不是自己另起一份排程）
        val exits = Regex("""ClassProgressScheduler\.rescheduleNextWindow\(""").findAll(path).count()
        assertEquals("降级这条路排出去的窗口只许一枚，且必须是 ClassProgressScheduler.rescheduleNextWindow", 1, exits)
        // AlarmManager 只许用来"读授权"，一次都不许用它排
        assertEquals("AlarmManager 在全服务只出现两枚（import + getSystemService 读授权）", 2, svc.countOf("AlarmManager"))
        assertEquals(
            "拿到的 AlarmManager 上只许调 canScheduleExactAlarms（读授权），多一枚 set 就是在这里偷偷排了个闹钟",
            listOf("alarmManager.canScheduleExactAlarms"),
            Regex("""alarmManager\.\w+""").findAll(svc).map { it.value }.distinct().sorted().toList(),
        )
    }

    // ---- ⑧ 整条路不许把异常抛回调用方 ---------------------------------------------------------------------

    /**
     * `reportLiveDegrade` 与它调的 `keepsAlarmClockExemption` 站在 `onFailure` 里面：
     * 凡碰 android 框架的调用（`getSystemService` / `applicationContext` / `applicationScope` /
     * `canScheduleExactAlarms` / `rescheduleNextWindow`）都必须落在某枚 `runCatching` 的块里，
     * 这两块自身也不许出现 `throw`。
     *
     * 2026-09-23 复核时这一条是真的漏的：`keepsAlarmClockExemption` 当时只把
     * `canScheduleExactAlarms()` 包进 runCatching，`context.getSystemService(...)` 是裸的 ——
     * 而 `SITE_START_SERVICE` 那一处递来的正是广播的临时 Context。现在整段包住，判不出来按
     * "没豁免"答（少排一次铃），而不是把下课铃广播打崩。
     */
    @Test
    fun nothingOnTheDegradePathEscapesToTheCaller() {
        val svc = blankComments(source(SERVICE))
        val bodies = listOf(
            "reportLiveDegrade" to functionRange(svc, "private fun reportLiveDegrade("),
            "keepsAlarmClockExemption" to functionRange(svc, "private fun keepsAlarmClockExemption("),
        )
        // 每条 runCatching 的绝对区间：只认落在这两枚函数里的那些
        val guarded = Regex("""runCatching\s*\{""").findAll(svc)
            .map { open -> open.range.last until (open.range.last + balancedBraces(svc, open.range.last).length) }
            .filter { range -> bodies.any { range.first in it.second } }
            .toList()
        val needles = listOf(
            "getSystemService", "applicationContext", "applicationScope",
            "canScheduleExactAlarms", "rescheduleNextWindow",
        )
        val offenders = ArrayList<String>()
        for ((label, range) in bodies) {
            for (needle in needles) {
                var from = range.first
                while (true) {
                    val at = svc.indexOf(needle, from)
                    if (at < 0 || at > range.last) break
                    if (guarded.none { it.contains(at) }) offenders += "$needle @$label"
                    from = at + needle.length
                }
            }
            val body = svc.substring(range.first, range.last + 1)
            assertFalse("$label 里出现了 throw：这一档的契约就是绝不往外抛\n$body", body.contains("throw "))
        }
        assertEquals(
            "降级这条路上有碰框架的调用没被 runCatching 包住：它抛出去就是把 ClassProgressReceiver 的广播" +
                "或服务主线程当场打崩（#119 修的是「看不见降级」，不是「引入崩溃」）：\n$offenders",
            emptyList<String>(),
            offenders,
        )
        // 判不出来时的兜底方向必须是「保守不排」（false ⇒ SkipStructural），不许是「乐观重排」
        assertTrue(
            "keepsAlarmClockExemption 判不出来时必须按「没豁免」答（getOrDefault(false)）：" +
                "排出去一发不带豁免的只是白跑，反过来则是把同一枚 DENIED 再撞一遍",
            svc.substringAfter("private fun keepsAlarmClockExemption(").lineSequence().take(12)
                .any { it.contains(".getOrDefault(false)") },
        )
        // 本卡是新写这枚系统服务读取的唯一一处：多一枚就是又有人在这条路上碰框架
        assertEquals(
            "全服务 getSystemService 只许一枚（本卡 keepsAlarmClockExemption 读精确闹钟授权那一处）：" +
                "每多一处就多一枚站在 onFailure 里可能抛的框架调用",
            1,
            Regex("""getSystemService""").findAll(svc).count(),
        )
    }

    /** 一枚函数声明的绝对区间：签名的起点到与之配平的右花括号（含两端） */
    private fun functionRange(code: String, signature: String): IntRange {
        val at = code.indexOf(signature)
        check(at >= 0) { "找不到 $signature：写法换过了，这条守卫要跟着改" }
        val open = code.indexOf('{', at)
        check(open >= 0) { "$signature 之后找不到左花括号" }
        return at until (open + balancedBraces(code, open).length)
    }

    // ---- 源码核对工具（与各 *WiringGuardTest 同一套刀法）--------------------------------------------------

    /** [body] 里每一枚 `.onFailure { ... }` 的内容块 */
    private fun onFailureBlocks(body: String, label: String): List<String> {
        val out = Regex("""\.onFailure\s*\{""").findAll(body).map { balancedBraces(body, it.range.last) }.toList()
        check(out.isNotEmpty()) { "$label 里一个 .onFailure 都没有：这一档的吞异常形状换过了，本守卫要重新核" }
        return out
    }

    /** `name(` 之后配平的实参表（含两端）；找不到就抛，静默跳过等于没有守卫 */
    private fun argOf(code: String, name: String): String? {
        val at = code.indexOf(name)
        check(at >= 0) { "找不到 $name：写法换过了，这条守卫要跟着改" }
        return balancedArguments(code, code.indexOf('(', at))
    }

    /** 从 [open] 那枚左括号起配平到与之匹配的右括号（含两端） */
    private fun balancedArguments(code: String, open: Int): String {
        var depth = 0
        for (index in open until code.length) {
            when (code[index]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return code.substring(open, index + 1)
                }
            }
        }
        throw IllegalStateException("第 $open 个字符之后的左括号没配平：${code.substring(open, minOf(open + 60, code.length))}")
    }

    /** 从 [signature] 之后第一个 `{` 起配平到对应右括号（含） */
    private fun balancedBlock(source: String, signature: String): String {
        val at = source.indexOf(signature)
        check(at >= 0) { "找不到 $signature：写法换过了，这条守卫要跟着改" }
        return balancedBraces(source, source.indexOf('{', at))
    }

    /** 从 [open] 那枚左花括号起配平到对应右括号（含两端） */
    private fun balancedBraces(source: String, open: Int): String {
        check(open >= 0) { "找不到左花括号" }
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open, index + 1)
                }
            }
        }
        throw IllegalStateException("第 $open 个字符之后的左花括号没配平")
    }

    /** [offset] 这个位置落在 [block] 在 [whole] 里的那一段吗 */
    private fun String.containsAt(offset: Int, whole: String): Boolean {
        val start = whole.indexOf(this)
        return start >= 0 && offset in start..(start + length)
    }

    private fun String.countOf(needle: String): Int = Regex(Regex.escape(needle)).findAll(this).count()

    private fun source(relative: String): String {
        val file = File(findMainJavaDir(), relative)
        assertTrue("找不到 ${file.path}：文件挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText()
    }

    /** res/values 下的全部 XML 文本（取证文案不许搬到资源里，也不许在设置页加行） */
    private fun resValuesText(): String {
        val dir = File(findMainJavaDir().parentFile, "res/values")
        assertTrue("找不到 $dir：资源条目这条判据成了空话", dir.isDirectory)
        return dir.listFiles { f: File -> f.isFile && f.extension == "xml" }
            ?.sortedBy { it.name }
            ?.joinToString("\n") { it.readText() }
            .orEmpty()
    }

    /** 把 `//` 与 `/* */` 注释抹成空格（字符串保留、长度与换行位置不变）：钉的是接线，不是白话 */
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

    /** 单测的工作目录是模块目录还是仓库根不由这里决定：几种布局都试一遍，全落空就抛 */
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

    private companion object {
        const val SERVICE = "com/buaa/schedule/reminder/CourseFluidService.kt"
        const val KERNEL = "com/buaa/schedule/reminder/LiveFgsRetry.kt"
    }
}
