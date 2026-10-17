package com.buaa.schedule.widget

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T400：桌面组件／兜底 Worker 那条后台链上的"一把 runCatching 连坐"与"折成成功"一族（在册 #675／#676／#677 ＋ #680 C-13 同族）。
 *
 * 盘面上这条链有三层坏形状，本文件的格子逐层对号：
 * - **#675（重）**：`BackgroundSync.rescheduleReminders` 抛那一档被 `.getOrDefault(true)` 折成"本轮排上了课前提醒闹钟"。
 *   那枚 `true` 有两读：`rescheduleRemindersAndBells` 里交给 [ColdStartRebuild] 的钥匙 2（这个读法**方向安全、不许改窄**），
 *   与 `onDataChanged` 里交给 [WidgetFallbackWorker] 的 `bellsHandled`（这个读法把"抛了"读成"课堂铃有人接手"⇒ 续排被跳过，
 *   而且 Worker 调这两步时一枚报告口都没传 ⇒ 失败在 Worker 侧**结构上不可见**，`Result.success()` 又让 WorkManager 不重试）。
 *   本卡收的是**读法与留痕**：`onDataChanged` 接一枚 `onStepFailed`（默认 [NO_STEP_FAILURE] ⇒ 其余调用点零改动），
 *   Worker 据此有界地 `Result.retry()`；`true` 的含义一个字不动（[reminderRescheduleStillFoldsThrowIntoTrueForKeyTwo] 就是那一格的反向钉）。
 * - **#676（中）**：`syncAndRedrawAllWidgets` 里六枚 `updateAll` 逐字排在一起、整段零闸门，
 *   第一家抛 ⇒ 同家剩余实例不画 + 后面五家整轮不画，日志只有一句"刷新 Widget 失败"，分不出断在哪一层。
 *   本卡把闸门**分层**（快照 sync 一段 + 六家各一段）并逐段点名留痕，
 *   ⚠️ 但**第三层（同家逐实例）在 `WidgetCommon.updateAllOfProvider*` 那四枚 `ids.forEach` 体内、不在本卡射程**
 *   （[layeredGatesDoNotClaimThePerInstanceLayer] 把那枚残余钉成在册量，不许报成"连坐已收干净"）。
 *   分层之后失败仍必须交回外层那道闸门（[layerFailureIsHandedBackToTheOuterGateInsteadOfEaten]），
 *   否则 [ColdStartRebuild] 会把这一轮记成干净、写下指纹白跳 24 小时 —— 那是"修出一件新病"的形状。
 * - **#677（中）**：这条链上 `runCatching` 十枚（卡面数成九枚，漏 `:638`），没有一处把协程取消复投。
 *   ⚠️ 形状约束：**在 `runCatching { try X catch { throw 折出来的那枚 } }` 里补复投是空操作** ——
 *   同一把 runCatching 会把它自己 catch 里抛出去的东西再吞一遍（`ui/ScheduleViewModel.kt` 的 `suspendCatching` 就是活反例）。
 *   要修就得把 `runCatching` 那一层**本身**换掉，所以 [neitherOwnedFileNestsACancellationRethrowInsideRunCatching] 钉的是形状而不是枚数。
 *   另外十枚里只有六枚块内带挂起点（可能被取消），其余四枚在非挂起 `fun` 里、取消结构上不可能发生 ⇒
 *   [nonSuspendingSitesStayRunCatchingBecauseCancellationCannotAriseThere] 把"甲档不收"钉住，免得下一张卡"顺手统一"。
 * - **#680 C-13 同族**：`scheduleWidgetMidnight` 那两支早退（无组件、拿不到 AlarmManager）与同形而未列的
 *   `cancelWidgetMidnight` 一支，此前零留痕 ⇒ [midnightAlarmEarlyExitsAreNamedOnTheLog]。
 *
 * 本模块**没有 Robolectric**（`android.util.Log` 与 `getSharedPreferences` 都是抛 "not mocked" 的桩），
 * 所以这里只钉两件事：① 纯 JVM 数得出来的那两枚判据（分层闸门的本体与重试判据 —— 见 [③ 之后补的那组行为格子]），
 * ② 接线形状（源码文本核对）。写法照抄 [CalendarModeClassBellCleanupTest] 的
 * `read` / `withoutComments` / `balancedBlock` / `occurrences` / `findMainJavaDir` 那一族：
 * 找不到文件就抛，不用 `assumeTrue` 跳过（跳过的守卫等于没守卫）。
 */
class WidgetBackgroundChainGateTest {

    // ---- #676：分层闸门 ----------------------------------------------------------

    /** 六家共用一把闸门 ⇒ 每一层各有一把，而且层名点名到是谁家 */
    @Test
    fun redrawHasOneGatePerLayerAndTheCarrierStillFlows() {
        val body = balancedBlock(withoutComments(read(BACKGROUND_SYNC_FILE)), "internal suspend fun syncAndRedrawAllWidgets(")

        assertTrue(
            "重绘仍然是一整把闸门（一家抛 = 后面几家整轮不画）：没走分层执行体\n$body",
            body.contains("runIsolatedRedrawLayers("),
        )
        for (label in REDRAW_LAYER_LABELS) {
            assertTrue("这一层没有自己的闸门，或者闸门名没点名它是哪一层：$label\n$body", body.contains("\"$label\" to {"))
        }
        assertEquals(
            "重绘的分层实到 " + occurrences(body, "\" to {") + " 段，写死 7 段（1 枚快照 sync + 6 家 Provider）：" +
                "多一段是有人在替六家做主，少一段就是某家的闸门被顺手摘了",
            7,
            occurrences(body, "\" to {"),
        )
        // 层序 = 验证过的执行顺序（那枚 KDoc 写着"别在这里挪"），逐枚比索引
        val indexes = REDRAW_LAYER_LABELS.map { body.indexOf("\"$it\" to {") }
        assertEquals(
            "重绘顺序漂了（现顺序：$indexes）：六家 updateAll 的先后是验证过的口径",
            REDRAW_LAYER_LABELS.indices.sortedBy { indexes[it] }.toList(),
            REDRAW_LAYER_LABELS.indices.toList(),
        )
        // 控制格：分层不是收窄。载体那一排与六枚 updateAll 的调用一个字不许因为"顺手"而少
        assertTrue("快照那一步不再吃入口载体：$body", body.contains("WidgetDataSynchronizer.sync(context, snapshot)"))
        for (call in REDRAW_LAYER_CALLS) {
            assertTrue("这一家的重绘调用被顺手改了/收了：$call\n$body", body.contains(call + "(context)"))
        }
    }

    /** 分层只解决连坐；失败清单必须交回外层那道闸门，否则是拿"看不见"换"少连坐" */
    @Test
    fun layerFailureIsHandedBackToTheOuterGateInsteadOfEaten() {
        val sync = withoutComments(read(BACKGROUND_SYNC_FILE))
        val body = balancedBlock(sync, "internal suspend fun syncAndRedrawAllWidgets(")

        assertTrue("逐层试完之后没把失败清单交回外层（六家各自吞掉 ⇒ 冷启动那把闸门会写下指纹白跳 24 小时）：\n$body",
            body.contains("if (failedLayers.isNotEmpty())"))
        assertTrue("失败清单没有向上抛的形状：\n$body", body.contains("throw WidgetRedrawLayersFailed(failedLayers)"))
        assertTrue(
            "载体类不存在（清单被折成 Boolean 就分不出是哪一层）：",
            sync.contains("internal class WidgetRedrawLayersFailed(val failedLayers: List<String>)"),
        )
        // 两处调用点的形状照旧（两处都被在册守卫逐字钉着，分层不许动它们）
        val refresh = balancedBlock(sync, "suspend fun refreshWidgets(")
        assertTrue("refreshWidgets 不再把载体递给重绘：$refresh", refresh.contains("syncAndRedrawAllWidgets(context, snapshot)"))
        // 那枚 Context 重载是**表达式体**（`): Boolean = runColdStartWidgetSteps(…)`），
        // balancedBlock 只会切到第一枚实参 lambda（`probeHasWidgets = { … }`）⇒ 按两枚重载之间的窗口切
        // （先例：ChainReadSnapshotWiringGuardTest 里 `loadWindowInput = load@{` 那一格记的同一类地雷）
        val flat = sync.replace(Regex("\\s+"), " ")
        val wiringAt = flat.indexOf("suspend fun runColdStartWidgetSteps(")
        val seamAt = flat.indexOf("internal suspend fun runColdStartWidgetSteps(", wiringAt)
        check(wiringAt >= 0 && seamAt > wiringAt) { "两枚 runColdStartWidgetSteps 重载的相对位置变了：wiringAt=$wiringAt seamAt=$seamAt" }
        val coldStart = flat.substring(wiringAt, seamAt)
        assertTrue(
            "冷启动那一步的接线不再逐字相同（ColdStartWidgetStepsTest:176 钉的就是这一串；分层闸门不许动调用点）：$coldStart",
            coldStart.contains("refreshWidgetData = { syncAndRedrawAllWidgets(context, snapshot) }"),
        )
    }

    /** ⚠️ 诚实格：本卡只收到"逐层"，第三层（同家逐实例）仍在射程外，别把它报成已收干净 */
    @Test
    fun layeredGatesDoNotClaimThePerInstanceLayer() {
        val common = withoutComments(read(WIDGET_COMMON_FILE))

        // 那四枚 ids.forEach 仍然是裸的：本卡不许碰 WidgetCommon，所以"第一家某枚实例抛 ⇒ 同家剩余实例不画"这一档仍在盘上。
        for (anchor in listOf(
            "suspend fun updateAllOfProvider(",
            "suspend fun updateAllOfProviderNext(",
            "suspend fun updateAllOfProviderTwoDay(",
            "suspend fun updateAllOfProviderWeekGrid(",
        )) {
            val block = balancedBlock(common, anchor)
            assertEquals(
                "$anchor 体内的 forEach 枚数不再是 1（本卡不许动 WidgetCommon，动了就是越界）：$block",
                1,
                occurrences(block, ".forEach {"),
            )
        }
        // 而这条链的分层只在 BackgroundSync 这一侧：层数 7 段里没有一枚"逐实例"的层
        val body = balancedBlock(withoutComments(read(BACKGROUND_SYNC_FILE)), "internal suspend fun syncAndRedrawAllWidgets(")
        assertFalse(
            "分层里出现了逐实例那一层（那是 WidgetCommon 的事，本卡射程外）：$body",
            body.contains("appWidgetId") || body.contains("forEach {"),
        )
    }

    /** 成文豁免格：`WidgetCommon.requestLiveRefresh` 不归这里管，也不许被任何"收窄/分层"顺路收进去 */
    @Test
    fun requestLiveRefreshIsStillOutOfThisChainsScope() {
        val sync = withoutComments(read(BACKGROUND_SYNC_FILE))
        assertEquals(
            "BackgroundSync 的代码栏里出现了 requestLiveRefresh（实到 " + occurrences(sync, "requestLiveRefresh") +
                " 枚）：那条路刻意不走 refreshWidgets，任何分层/收窄都不许把它顺路收进来",
            0,
            occurrences(sync, "requestLiveRefresh"),
        )
    }

    // ---- #677：取消复投的形状 ----------------------------------------------------

    /**
     * 六枚带挂起点的落点全部复投；绝对值钉死，多一枚少一枚都要红。
     *
     * 数的是**抹注释之后**的代码栏：本文件的 KDoc 里 `runCatching` 与 `CancellationException` 都出现过，
     * 裸 grep 会把散文数进来（同一个错误上一张卡刚犯过两次）。
     */
    @Test
    fun cancellableSitesOnTheBackgroundChainRethrowCancellation() {
        val sync = withoutComments(read(BACKGROUND_SYNC_FILE))
        val rethrow = occurrences(sync, "catch (e: CancellationException) {")
        val swallowing = occurrences(sync, "runCatching")

        assertEquals(
            "复投枚数 实到 $rethrow ／ 写死 8：readChainSnapshotOrNull、rescheduleReminders、refreshWidgetsAsync、" +
                "refreshWidgets、scheduleTomorrowPreview、runColdStartWidgetSteps 的探测、runChainStep、runIsolatedRedrawLayers。" +
                "少一枚就是那一档调用方把链摘掉时仍然被折成一次普通失败",
            8,
            rethrow,
        )
        // 十枚 runCatching 里射程外的四枚（见 nonSuspendingSites…）还在，射程内的六枚已经换成 try/catch
        assertEquals(
            "代码栏 runCatching 实到 $swallowing ／ 写死 4（cleanUpInCalendarMode、scheduleWidgetMidnight、" +
                "cancelLegacyPeriodicWork、hasAnyWidgetSafely —— 四枚都不在挂起函数里）",
            4,
            swallowing,
        )
        // 每一枚复投的第二臂吃 Throwable 而不是 Exception：原先那层 runCatching 本来吃 Throwable，
        // 收窄成 Exception 会把 Error 那一档（定制 ROM 的 NoSuchMethodError 之类）放逃到广播/Worker 里去崩。
        // 六枚换掉的落点 ＋ 新增的分层执行体 = 七臂；`readChainSnapshotOrNull` 仍吃 Exception（改前形状，本卡不动它）。
        assertEquals(
            "catch (e: Throwable) 实到 " + occurrences(sync, "catch (e: Throwable) {") + " ／ 写死 7" +
                "（六个换掉的落点各一臂 ＋ runIsolatedRedrawLayers），少一臂就是那一处把 Error 那一档放逃出去了",
            7,
            occurrences(sync, "catch (e: Throwable) {"),
        )
        // 第六枚（冷启动探测）落在两枚同名重载里，按行首锚点单独钉，免得 balancedBlock 切到上面那一枚 Context 版
        assertTrue(
            "runColdStartWidgetSteps 的探测那一枚没有换成带复投的 try/catch（取消会被折成「探测抛了」）：\n$sync",
            sync.contains("val hasWidgets = try {"),
        )
        for (anchor in listOf(
            "suspend fun rescheduleReminders(",
            "fun refreshWidgetsAsync(context: Context)",
            "suspend fun refreshWidgets(",
            "suspend fun scheduleTomorrowPreview(",
            "private suspend fun runChainStep(",
        )) {
            val block = balancedBlock(sync, anchor)
            assertTrue("$anchor 这一枚没有复投（取消被折成普通失败）：\n$block", block.contains("catch (e: CancellationException) {"))
            assertFalse("$anchor 外面还套着一把 runCatching（复投会被同一把再吞一遍）：\n$block", block.contains("runCatching"))
        }
    }

    /** 甲档不收的那四枚：非挂起 `fun` 里块内没有挂起点，取消结构上不可能发生 ⇒ 换它们是零收益 churn */
    @Test
    fun nonSuspendingSitesStayRunCatchingBecauseCancellationCannotAriseThere() {
        val sync = withoutComments(read(BACKGROUND_SYNC_FILE))
        for (anchor in listOf(
            "internal fun cleanUpInCalendarMode(",
            "fun scheduleWidgetMidnight(context: Context, hasAnyWidget: Boolean",
            "fun cancelLegacyPeriodicWork(context: Context)",
            "fun hasAnyWidgetSafely(context: Context): Boolean =",
        )) {
            val block = balancedBlock(sync, anchor)
            assertTrue("$anchor 那一枚 runCatching 被换掉了（本卡的裁定是不换：块内零挂起点）：\n$block", block.contains("runCatching"))
        }
        val worker = withoutComments(read(FALLBACK_WORKER_FILE))
        assertTrue(
            "WidgetFallbackWorker.ensure 那枚 runCatching 被换掉了（同样是非挂起；摘掉它 ensure 就会崩那两条调用方）：\n" +
                balancedBlock(worker, "fun ensure(context: Context, hasAnyWidget: Boolean"),
            balancedBlock(worker, "fun ensure(context: Context, hasAnyWidget: Boolean").contains("runCatching"),
        )
    }

    /** 反向守卫（防"空操作形状"回潮）：任何一把 runCatching 的块内都不许出现取消复投 */
    @Test
    fun neitherOwnedFileNestsACancellationRethrowInsideRunCatching() {
        for (relative in listOf(BACKGROUND_SYNC_FILE, FALLBACK_WORKER_FILE)) {
            val code = withoutComments(read(relative))
            val offenders = balancedBlocksContaining(code, "runCatching", "CancellationException")
            assertTrue(
                "$relative 里有 runCatching 块内补复投（那是空操作：同一把 runCatching 会把自己 catch 里抛出去的那枚再吞一遍）：" +
                    offenders.joinToString(" ／ "),
                offenders.isEmpty(),
            )
        }
    }

    // ---- #675：折成成功那一族 ----------------------------------------------------

    /**
     * `true` 的含义一格不许改窄（它是 [ColdStartRebuild] 钥匙 2 的输入，`CalendarModeClassBellCleanupTest:389`
     * 与 `ClassProgressRescheduleWiringTest:42` 钉着包装体）；本卡改的是**读法**。
     */
    @Test
    fun reminderRescheduleStillFoldsThrowIntoTrueForKeyTwo() {
        val body = balancedBlock(withoutComments(read(BACKGROUND_SYNC_FILE)), "suspend fun rescheduleReminders(")
        val throwableArm = balancedBlock(body, "catch (e: Throwable) {")

        assertEquals(
            "抛那一档不再报给 onStepFailed（Worker 侧就还是看不见）：\n$throwableArm",
            1,
            occurrences(throwableArm, "onStepFailed(\"rescheduleReminders\", e)"),
        )
        // balancedBlock 切的是「锚点到那记收尾右括号（含）」，所以臂内最后一个**表达式**在倒数第二行
        val armTail = throwableArm.lines().dropLast(1).last { it.isNotBlank() }.trim()
        assertEquals(
            "抛那一档的结论不再是 true —— 钥匙 2 会探错闹钟头（要修的正是 Worker 那一侧的读法，不是这里的含义）：" +
                "现尾部表达式 = $armTail",
            "true",
            armTail,
        )
        assertFalse(
            "getOrDefault 又回来了（它把「没人接手」与「抛了」压成同一个 true，正是 #675 的病灶）：\n$body",
            body.contains("getOrDefault"),
        )
        // 包装体那两枚在册针逐字仍在（硬约束 1）：本卡的改动不该碰到它们
        val wrapper = balancedBlock(withoutComments(read(BACKGROUND_SYNC_FILE)), "suspend fun rescheduleRemindersAndBells(")
        assertTrue("包装体的守卫形状变了：\n$wrapper", wrapper.contains("if (!reminderArmed) {"))
        assertTrue(
            "续排那一步的报告口/载体形状变了（在册守卫逐字钉着）：\n$wrapper",
            wrapper.contains("ClassProgressScheduler.rescheduleNextWindow(context, onStepFailed, snapshot)"),
        )
        assertEquals("续排在包装体里被走了不止一遍：\n$wrapper", 1, occurrences(wrapper, "rescheduleNextWindow("))
        assertFalse("包装体里传的是无操作常量：\n$wrapper", wrapper.contains("rescheduleNextWindow(context, NO_STEP_FAILURE)"))
    }

    /** `onDataChanged` 现在接得住报告口，并把它递给提醒那一步 */
    @Test
    fun onDataChangedGainsTheReportPortAndHandsItToTheReminderStep() {
        val code = withoutComments(read(BACKGROUND_SYNC_FILE))
        val signature = parameterList(code, "suspend fun onDataChanged(")
        val body = balancedBlock(code, "suspend fun onDataChanged(")

        assertTrue(
            "onDataChanged 的形参表里没有报告口（Worker 那条链调它，失败就只能落在 Log.w 那一行）：$signature",
            signature.contains("onStepFailed: (label: String, error: Throwable) -> Unit = NO_STEP_FAILURE"),
        )
        assertTrue("提醒那一步没有吃这条链的报告口：\n$body", body.contains("rescheduleReminders(context, onStepFailed, snapshot)"))
        // 控制格：载体与结论那两枚实参一个字没少（ChainReadSnapshotWiringGuardTest 的那根针已随 #675 同批重钉成四枚实参）
        assertTrue(
            "组件那一步不再吃载体/结论（少任何一枚都是 #536 F2 那族复发）：实参表 = " +
                body.lines().first { it.contains("refreshWidgets(") }.trim(),
            body.contains("refreshWidgets(context, hasAnyWidget, snapshot, onStepFailed)"),
        )
        assertTrue("入口那一次读仍然走可复投的那一枚：\n$body", body.contains("val snapshot = readChainSnapshotOrNull(context)"))
    }

    /** Worker：失败留在册 + 有界重试，两条在册针（R5 F-16 与"探测只问一遍"）不许被顺手带坏 */
    @Test
    fun fallbackWorkerCountsFailedStepsAndRetriesBoundedly() {
        val worker = withoutComments(read(FALLBACK_WORKER_FILE))
        val doWork = balancedBlock(worker, "override suspend fun doWork(): Result")

        assertTrue("doWork 里那枚记账口不存在（抛了仍然结构上不可见）：\n$doWork", doWork.contains("val reportFailedStep:"))
        assertTrue(
            "onDataChanged 那一步没吃记账口（#675 那一档在 Worker 侧仍然只剩一句 Log.w）：\n$doWork",
            doWork.contains("onDataChanged(applicationContext, hasAnyWidget = hasWidget, onStepFailed = reportFailedStep)"),
        )
        assertTrue(
            "无组件那一条路没吃记账口（rescheduleReminders 抛了 Worker 一样不知道）：\n$doWork",
            doWork.contains("rescheduleReminders(applicationContext, reportFailedStep)"),
        )
        assertTrue("重试判据没被调用（仍然一律 Result.success）：\n$doWork", doWork.contains("shouldRetryFallbackChain("))
        assertTrue("没有 Result.retry() 那一支：\n$doWork", doWork.contains("return Result.retry()"))
        // 在册针逐字仍在
        assertTrue(
            "续排那一步的调用形状变了（ClassProgressCleanupDecisionTest:631 逐字钉着）：\n$doWork",
            doWork.contains("ClassProgressScheduler.rescheduleNextWindow(applicationContext)"),
        )
        assertFalse("doWork 又开始调 ensure()（R5 F-16：无组件会把自己注销掉）：\n$doWork", doWork.contains("WidgetFallbackWorker.ensure("))
        assertFalse("探测结论没传给 onDataChanged（同一趟链要问 Launcher 两遍）：\n$doWork", doWork.contains("onDataChanged(applicationContext)"))
        assertEquals("doWork 里跨 binder 探测不止一枚：\n$doWork", 1, occurrences(doWork, "hasAnyWidgetSafely("))
    }

    // ---- #680 C-13 同族：闹钟那两支早退的留痕 --------------------------------------

    /**
     * 今天/明晚翻面的唯一闹钟：两支早退各自点名，控制流一个字不动（继续 return，不抛 ⇒ "别崩广播"保留）。
     *
     * ⚠️ 留痕一律落在 Info／Warning 级、**不落 Debug**：本仓自己成文的取证口径写着真机 logcat 被 HyperOS
     * 截在 Info 级（`MonotonicWindowGateWiringGuardTest` 与 `WidgetRolloverClaimWiringGuardTest` 里那两格
     * 「这一族零枚 Log 得不许」＋「`Log.d` 必须 0 枚 —— 写了等于没写」）。
     * 一张卡面写着"零留痕"的链，修成一条读不到的留痕，等于没修（第⓪问的裁定在这里跟着盘面事实改口，
     * 见 T400② 的订正段）。
     */
    @Test
    fun midnightAlarmEarlyExitsAreNamedOnTheLog() {
        val code = withoutComments(read(BACKGROUND_SYNC_FILE))
        val midnight = balancedBlock(code, "fun scheduleWidgetMidnight(context: Context, hasAnyWidget: Boolean")
        val cancel = balancedBlock(code, "fun cancelWidgetMidnight(context: Context)")

        assertTrue(
            "「桌面没有组件」那一支仍然零留痕，或者留痕落在读不到的级别：\n$midnight",
            midnight.contains("Log.i("),
        )
        assertEquals("无组件那一支的留痕恰好一枚（多一处就是同一档两处各说一遍）：\n$midnight", 1, occurrences(midnight, "Log.i("))
        assertTrue(
            "拿不到 AlarmManager 那一支还是静默 return（这条链是翻面唯一的闹钟）：\n$midnight",
            midnight.contains("拿不到 AlarmManager"),
        )
        assertEquals(
            "排闹钟那一族的 Warning 留痕实到 " + occurrences(midnight, "Log.w(") + " ／ 写死 2" +
                "（改前那句「调度零点刷新失败」＋新加的「拿不到 AlarmManager」；少一枚就是静默的那一支又回来了）：\n$midnight",
            2,
            occurrences(midnight, "Log.w("),
        )
        assertTrue("撤闹钟那一支（同形而卡面未列的那一枚）仍然静默：\n$cancel", cancel.contains("拿不到 AlarmManager"))
        // 控制格：两支都还是"当场退出"，没有改成抛（抛会崩广播回调，那是既有理由）
        assertTrue("无组件那一支不再撤残留闹钟（被改成了抛/继续往下排？）：\n$midnight", midnight.contains("cancelWidgetMidnight(context)"))
        assertEquals("排闹钟那一步仍然只有一把就地 runCatching：\n$midnight", 1, occurrences(midnight, "runCatching"))
        assertTrue("零点闹钟那一路的 builder 接线变了：\n$midnight", midnight.contains("midnightPendingIntent(context)"))
        assertFalse("两支之一被改成向外抛（会崩在广播回调里）：\n$midnight", midnight.contains("throw "))
    }

    // ---- 行为格子（纯 JVM 判据本体，零 Context、零 android）------------------------

    /** 一层都不许被前面那家带走：第 k 家抛了，第 k+1 家…第 7 家仍然各画一次（#676 收的就是这一条） */
    @Test
    fun everyLayerRunsEvenWhenAnEarlierLayerThrows() = runBlocking {
        val trace = Trace()
        val failed = BackgroundSync.runIsolatedRedrawLayers(
            layers = productionShapedLayers(trace, failing = setOf("TodayWidgetProvider.updateAll")),
            reportLayer = { label, error -> trace.reports += label to error },
        )

        assertEquals(
            "第一家抛了后面的就不画（这正是改前三层连坐里最响的那一层）：实到调用 " + trace.called,
            REDRAW_LAYER_LABELS,
            trace.called,
        )
        assertEquals("失败清单里该只有那一家", listOf("TodayWidgetProvider.updateAll"), failed)
        assertEquals("留痕该恰好一枚、且报的就是那一家", 1, trace.reports.size)
    }

    /** 逐档点名：七层里任何一层抛，都只记它自己一个名字，其余六层照跑 */
    @Test
    fun eachLayerNamesItselfAndNothingElse() = runBlocking {
        for (culprit in REDRAW_LAYER_LABELS) {
            val trace = Trace()
            val failed = BackgroundSync.runIsolatedRedrawLayers(
                layers = productionShapedLayers(trace, failing = setOf(culprit)),
                reportLayer = { label, error -> trace.reports += label to error },
            )

            assertEquals("$culprit 抛了，七层仍然要各跑一次：实到 " + trace.called, REDRAW_LAYER_LABELS, trace.called)
            assertEquals("$culprit 那一层被记成了别的名字：实到 " + failed, listOf(culprit), failed)
            assertEquals("$culprit 的留痕枚数：实到 " + trace.reports.size, 1, trace.reports.size)
            assertEquals("报告里点名的层不对（留痕分不出是哪一层就等于没分层）", culprit, trace.reports.single().first)
        }
    }

    /** 取消是控制流不是失败：复投、后面的层不跑、也不记进失败清单（#677 落在这枚新 seam 上的那一半） */
    @Test
    fun cancellationIsNotALayerFailureAndStopsTheRemainingLayers() = runBlocking {
        val trace = Trace()
        val cancellation = CancellationException("调用方把这条链摘掉了")
        var caught: Throwable? = null
        try {
            BackgroundSync.runIsolatedRedrawLayers(
                layers = productionShapedLayers(trace, failing = setOf("WeekWidgetProvider.updateAll"), cancellation = cancellation),
                reportLayer = { label, error -> trace.reports += label to error },
            )
        } catch (e: Throwable) {
            caught = e
        }

        assertSame("取消没有被原样复投（被折成一次普通层失败就是 #677 的病灶）：$caught", cancellation, caught)
        assertEquals(
            "复投之后后面的层不该再跑（那是调用方已经不想跑了；culprit 自己算被跑到，因为它抛之前先记了一笔）：实到 " + trace.called,
            listOf("WidgetDataSynchronizer.sync", "TodayWidgetProvider.updateAll", "TomorrowWidgetProvider.updateAll", "WeekWidgetProvider.updateAll"),
            trace.called,
        )
        assertTrue("取消被记成了一枚层失败（外层会把它当「跑坏了」上报闸门）：" + trace.reports, trace.reports.isEmpty())
    }

    /** Error 那一档也算一层失败：闸门吃 Throwable，跟改前那层 runCatching 一样宽（不收窄成 Exception） */
    @Test
    fun errorsAreCountedAsLayerFailuresToo() = runBlocking {
        val trace = Trace()
        val brokenLink = NoSuchMethodError("RemoteViews 在个别定制 ROM 上抛的就是这一族")
        val failed = BackgroundSync.runIsolatedRedrawLayers(
            layers = listOf(
                "WidgetDataSynchronizer.sync" to { trace.called += "WidgetDataSynchronizer.sync" },
                "TodayWidgetProvider.updateAll" to {
                    trace.called += "TodayWidgetProvider.updateAll"
                    throw brokenLink
                },
                "TomorrowWidgetProvider.updateAll" to { trace.called += "TomorrowWidgetProvider.updateAll" },
            ),
            reportLayer = { label, error -> trace.reports += label to error },
        )

        assertEquals(
            "Error 被放逃出去、或者把后面的层带走了：实到 " + trace.called,
            listOf("WidgetDataSynchronizer.sync", "TodayWidgetProvider.updateAll", "TomorrowWidgetProvider.updateAll"),
            trace.called,
        )
        assertEquals(listOf("TodayWidgetProvider.updateAll"), failed)
        assertSame(brokenLink, trace.reports.single().second)
    }

    /** 抛几层就报几次、顺序 = 执行顺序、异常本体不换 */
    @Test
    fun everyFailedLayerIsReportedExactlyOnceInOrder() = runBlocking {
        val trace = Trace()
        val failed = BackgroundSync.runIsolatedRedrawLayers(
            layers = productionShapedLayers(
                trace,
                failing = setOf("WidgetDataSynchronizer.sync", "WeekGridWidgetProvider.updateAll", "TwoDayWidgetProvider.updateAll"),
            ),
            reportLayer = { label, error -> trace.reports += label to error },
        )

        assertEquals(
            "三层各抛一次 ⇒ 报告口该被调用三次（少一次就是有一层没人知道）：实到 " + trace.reports.size,
            3,
            trace.reports.size,
        )
        assertEquals(
            "失败清单的顺序就是执行顺序（取证要按这一串点名是哪一层）",
            listOf("WidgetDataSynchronizer.sync", "WeekGridWidgetProvider.updateAll", "TwoDayWidgetProvider.updateAll"),
            failed,
        )
        assertEquals(
            "报告里的层名与清单对不上",
            failed,
            trace.reports.map { it.first },
        )
        assertEquals("七层仍然各跑一次：实到 " + trace.called, 7, trace.called.size)
    }

    /** 边界：没有层就没有失败，也没有留痕（别把空清单当"全抛了"） */
    @Test
    fun noLayersMeansNoFailureAndNoReport() = runBlocking {
        val trace = Trace()
        val failed = BackgroundSync.runIsolatedRedrawLayers(layers = emptyList(), reportLayer = { label, error -> trace.reports += label to error })

        assertTrue(failed.isEmpty())
        assertTrue(trace.reports.isEmpty())
    }

    /** 重试判据表：抛过 且 没到顶才 retry；到顶就按原样成功（永久坏掉的库不该每一轮 30 秒地敲） */
    @Test
    fun retryHappensOnlyWhenSomethingFailedAndTheCeilingIsNotPassed() {
        // (本轮抛了几步, 本次尝试序号) → 该不该 retry
        val table = listOf(
            Triple(0, 0, false), // 干净跑完：第一趟也不该重跑
            Triple(0, 2, false),
            Triple(1, 0, true), // 抛了一步、第一趟 ⇒ 交回 WorkManager
            Triple(1, 1, true),
            Triple(2, 1, true),
            Triple(7, 2, true), // 七层全抛也照样只到第 3 趟为止
            Triple(1, 3, false), // ← 到顶：本卡写死的那一档，不再敲
            Triple(3, 3, false),
            Triple(1, 4, false), // 到顶之后继续往后仍然不再 retry
            Triple(0, 9, false),
        )
        for ((failedSteps, attempt, expected) in table) {
            assertEquals(
                "shouldRetryFallbackChain(failedStepCount = $failedSteps, runAttemptCount = $attempt) 该给 $expected",
                expected,
                shouldRetryFallbackChain(failedStepCount = failedSteps, runAttemptCount = attempt),
            )
        }
    }

    /** 上限那枚常量本身（判据表的第 3 档全靠它；谁把它调大就得回来重钉上面那张表） */
    @Test
    fun theRetryCeilingIsTheOneWrittenIntoTheConstant() {
        val worker = withoutComments(read(FALLBACK_WORKER_FILE))

        assertEquals(
            "`MAX_FALLBACK_RETRY_ATTEMPTS = 3` 恰好一枚（实到 " + occurrences(worker, "MAX_FALLBACK_RETRY_ATTEMPTS = 3") +
                "）：上限换了就得重钉上面那张判据表，别只改常量",
            1,
            occurrences(worker, "MAX_FALLBACK_RETRY_ATTEMPTS = 3"),
        )
        val functionAt = worker.indexOf("internal fun shouldRetryFallbackChain(")
        val companionAt = worker.indexOf("companion object")
        assertTrue(
            "判据函数被挪进了 companion object（顶层函数编译进 WidgetFallbackWorkerKt，JVM 单测调它不会" +
                "连带去初始化 CoroutineWorker 那堆 android 桩；挪进 companion 就把这层隔离弄丢了，而本模块没有 Robolectric）。" +
                "实到 functionAt=$functionAt ／ companionAt=$companionAt",
            functionAt >= 0 && (companionAt < 0 || functionAt < companionAt),
        )
    }

    // ---- 源码核对工具（抄 CalendarModeClassBellCleanupTest，逐枚同名同形）---------

    /** 按生产的层名册造一层：记一笔"这层被调用过"，再按名册决定它抛什么 */
    private fun productionShapedLayers(
        trace: Trace,
        failing: Set<String>,
        cancellation: Throwable? = null,
    ): List<Pair<String, suspend () -> Unit>> = REDRAW_LAYER_LABELS.map { label ->
        val boom: Throwable? = when {
            label in failing && cancellation != null -> cancellation
            label in failing -> IllegalStateException("$label 画不动")
            else -> null
        }
        label to suspendLayer(trace, label, boom)
    }

    private fun suspendLayer(trace: Trace, label: String, boom: Throwable?): suspend () -> Unit = {
        trace.called += label
        if (boom != null) throw boom
    }

    private class Trace {
        val called = mutableListOf<String>()
        val reports = mutableListOf<Pair<String, Throwable>>()
    }


    private fun read(relative: String): String {
        val file = File(findMainJavaDir(), relative)
        assertTrue("找不到 ${file.path}：文件挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText()
    }

    /** [signature] 处那个参数表（圆括号之间，嵌套括号与默认值一起收；锚点从函数名起算） */
    private fun parameterList(code: String, signature: String): String {
        val at = code.indexOf(signature)
        check(at >= 0) { "找不到 $signature：函数改名或挪过家，这条守卫要跟着改" }
        val open = code.indexOf('(', at)
        var depth = 0
        for (index in open until code.length) {
            when (code[index]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return code.substring(open + 1, index)
                }
            }
        }
        throw IllegalStateException("$signature 的参数括号没配平")
    }

    /** 从 [signature] 处那个左括号起配平到对应的右括号（含），返回整段（锚点到收尾，签名本身也在内） */
    private fun balancedBlock(source: String, signature: String): String {
        val at = source.indexOf(signature)
        check(at >= 0) { "找不到 $signature：那条分支或函数改过名，这条守卫要跟着改" }
        val open = if (signature.endsWith("{")) at + signature.length - 1 else source.indexOf('{', at)
        check(open >= at) { "$signature 之后找不到左括号" }
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(at, index + 1)
                }
            }
        }
        throw IllegalStateException("$signature 的花括号没配平")
    }

    /** 每一枚 [needle] 开头的块，把满足 [probe] 的那些块挑出来（反向守卫用：块内不许出现某形状） */
    private fun balancedBlocksContaining(source: String, needle: String, probe: String): List<String> {
        val hits = mutableListOf<String>()
        var from = 0
        while (true) {
            val at = source.indexOf(needle, from)
            if (at < 0) return hits
            val open = source.indexOf('{', at)
            var depth = 0
            var end = -1
            for (index in open until source.length) {
                when (source[index]) {
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            end = index
                            break
                        }
                    }
                }
            }
            check(end >= 0) { "$needle 的块没配平" }
            val block = source.substring(at, end + 1)
            if (block.contains(probe)) hits += block.lines().joinToString(" ") { it.trim() }.take(120)
            from = end + 1
        }
    }

    /** 注释里提到函数名与中文引号都不该影响配平，但会污染计数，所以先抹成空白 */
    private fun withoutComments(source: String): String {
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

    /** 工作目录是模块目录还是仓库根不由这里决定：两种布局都试，全落空就抛（跳过的守卫等于没守卫） */
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

    private fun occurrences(haystack: String, needle: String): Int {
        var count = 0
        var from = 0
        while (true) {
            val at = haystack.indexOf(needle, from)
            if (at < 0) return count
            count++
            from = at + needle.length
        }
    }

    private companion object {
        const val BACKGROUND_SYNC_FILE = "com/buaa/schedule/widget/BackgroundSync.kt"
        const val FALLBACK_WORKER_FILE = "com/buaa/schedule/widget/WidgetFallbackWorker.kt"

        /** 本卡不许碰的那一枚：只用来把"第三层仍在射程外"钉成在册量 */
        const val WIDGET_COMMON_FILE = "com/buaa/schedule/widget/WidgetCommon.kt"

        /** 分层之后的执行顺序 = 改动前六枚 updateAll 的逐字顺序，一枚都不许挪 */
        val REDRAW_LAYER_LABELS = listOf(
            "WidgetDataSynchronizer.sync",
            "TodayWidgetProvider.updateAll",
            "TomorrowWidgetProvider.updateAll",
            "WeekWidgetProvider.updateAll",
            "WeekGridWidgetProvider.updateAll",
            "NextClassWidgetProvider.updateAll",
            "TwoDayWidgetProvider.updateAll",
        )

        val REDRAW_LAYER_CALLS = listOf(
            "TodayWidgetProvider.updateAll",
            "TomorrowWidgetProvider.updateAll",
            "WeekWidgetProvider.updateAll",
            "WeekGridWidgetProvider.updateAll",
            "NextClassWidgetProvider.updateAll",
            "TwoDayWidgetProvider.updateAll",
        )
    }
}
