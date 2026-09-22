package com.buaa.schedule.ui.signin

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T67「判死之后相机还绑着空转」的表驱动单测 + 接线形状守卫 + 反向钉。
 *
 * 判据本体在 [ScanFrameFlowPolicy]（零 import），这里管两类事：
 *
 * ①②③ JVM 跑得动的：暂时 / 判死 / 判死后回到前台恢复 / 重复推送同一个判死 / 停用窗口里绝不
 *   解绑 —— 五档全部拿**真账**摆出来（[driveToGiveUp] 从第 1 帧起逐帧喂失败，一路经过
 *   [decoderFrameAction] 的 Skip/Probe 走到 giveUpReason 落上），不手搓一枚"看起来像判死"的
 *   health：手搓会把"第几轮才判死""判死后 working 不再翻面"这两件事糊过去。
 * ④ 措辞：停帧那一行必须说清三件事（帧流到此为止 / 第几帧为什么 / 还剩什么活路），
 *   且不许指向任何一条已经不存在的入口或动作（手输 2026-09-21 整条删除、手电/补光
 *   2026-09-22 整条拆除、"退出重进"从来不是这一页的活路 —— 活路是回到前台）。
 * ⑤⑥ 跑不到的（Composable 与 CameraProvider 在 JVM 里造不出来）：按源码形状核。手法沿用
 *   [ScanCameraAidWiringGuardTest] / [ScanSecondEngineWiringGuardTest]：读源码文本、匹配前先抹
 *   注释、**找不到锚点就抛**（静默跳过等于没有守卫）、每档反向钉都配一个必须命中的靶子。
 *
 * 本文件零 `android` import。
 */
class ScanFrameFlowGuardTest {

    // ---- 真账：逐帧把健康度推到判死 ----

    /** 一串"在第几帧失败"喂进判据 */
    private fun failures(vararg frameSerials: Long, start: ScanDecoderHealth = ScanDecoderHealth()) =
        frameSerials.fold(start) { health, frame -> healthAfterDecodeFailure(health, frame) }

    /** 停用窗口内那一档（连错 3 帧、第 0 轮）：working=false 但**没判死** */
    private fun suspended(): ScanDecoderHealth = failures(1L, 2L, 3L)

    /**
     * 从 [startFrame] 起逐帧喂失败，直到 giveUpReason 落上（Skip 的帧只推进帧号，不喂失败）。
     *
     * 为什么要走真账而不是 copy()：判死的档位是"连错 3 帧 × 试回 3 轮"攒出来的，
     * 途中 working 一直是 false —— 本卡那句"scannerWorking 不再翻面、所以没人解绑"只有
     * 这样才摆得出来。走不到判死就抛（表驱动不许静默少一档）。
     */
    private fun driveToGiveUp(start: ScanDecoderHealth, startFrame: Long): ScanDecoderHealth {
        var health = start
        var frame = startFrame
        var guard = 0
        while (health.giveUpReason == null) {
            check(++guard <= 5_000) { "推不到判死，帧号一路走到 $frame：$health" }
            if (decoderFrameAction(health, frame) != DecoderFrameAction.Skip) {
                health = healthAfterDecodeFailure(health, frame)
            }
            frame++
        }
        return health
    }

    private fun givenUp(): ScanDecoderHealth = driveToGiveUp(ScanDecoderHealth(), 1L)

    // ---- ① 停不停帧：五档逐支 ----

    /**
     * ①a 表驱动主档：暂时 / 判死 / 已停过 / 判死后恢复，各自落在哪一档结论。
     *
     * `flowBound` 是调用点读出来的设备事实（两个句柄任非空 = 帧流还开着），这里当参数摆两种取值。
     */
    @Test
    fun frameFlowStopTableCoversTemporaryGivenUpAndAlreadyStopped() {
        val given = givenUp()
        val recovered = healthAfterPageVisible(given)
        val table = listOf(
            // 活着与偶发抖动：帧照旧到
            Row("健康 + 绑着", ScanDecoderHealth(), true, FrameFlowStop.Keep),
            Row("偶发一帧失败（还没停用）+ 绑着", failures(1L), true, FrameFlowStop.Keep),
            // T59① 的那条活路：停用窗口里**绝不**解绑
            Row("停用窗口内（第 0 轮）+ 绑着", suspended(), true, FrameFlowStop.Keep),
            Row("停用窗口第 2 档（已过 1 轮）+ 绑着", failures(1L, 2L, 3L, 23L), true, FrameFlowStop.Keep),
            // 判死 + 还绑着 = 本卡的账
            Row("判死 + 绑着", given, true, FrameFlowStop.Unbind),
            Row("判死且停用帧号还在后面 + 绑着", given.copy(suspendUntilFrame = given.framesAtLastFailure + 1_000L), true, FrameFlowStop.Unbind),
            // 重复推送同一个判死：帧流已经不绑了 ⇒ 不许解第二次
            Row("判死 + 早已停帧", given, false, FrameFlowStop.Keep),
            Row("健康 + 压根没绑过", ScanDecoderHealth(), false, FrameFlowStop.Keep),
            // 判死后回到前台（health 归零）：这一档不解绑，重绑由绑定那颗 effect 负责
            Row("判死后回到前台恢复 + 没绑着", recovered, false, FrameFlowStop.Keep),
            Row("判死后回到前台恢复 + 还绑着", recovered, true, FrameFlowStop.Keep),
            // 额度用完那一档：真判死了照停（没活路更要停，那正是本卡的电账）
            Row(
                "判死 + 回到前台额度已用完 + 绑着",
                recovered.copy(giveUpReason = "连续 3 帧解码失败、自动试回 3 轮仍不成", pageVisibleRecoveries = MaxPageVisibleRecoveries),
                true,
                FrameFlowStop.Unbind,
            ),
        )
        for (row in table) {
            assertEquals(row.label, row.expected, frameFlowStop(row.health, row.flowBound))
        }
        // 靶子：两种结论都真被摆出来过（全表只有一种 = 判据或 anchor 漂了）
        assertEquals("全表只有一种结论：", 2, table.map { it.expected }.distinct().size)
        assertEquals("停帧那一档出现 3 次（判死 + 还绑着的三种变体）：", 3, table.count { it.expected == FrameFlowStop.Unbind })
    }

    /**
     * ①b 停用窗口里的**每一帧**都不许停帧 —— 那是 T59①「窗口过完没有」的唯一观测量。
     */
    @Test
    fun suspensionWindowNeverStopsTheFrameFlowOnAnyFrame() {
        val health = suspended()
        val until = health.suspendUntilFrame
        check(until > 0L) { "没摆出停用窗口：$health" }
        for (frame in (until - 20L)..(until + 5L)) {
            val expected = if (frame < until) DecoderFrameAction.Skip else DecoderFrameAction.Probe
            assertEquals("第 $frame 帧的帧动作（窗口止于 $until）：", expected, decoderFrameAction(health, frame))
            assertEquals("停用窗口里不许停帧（第 $frame 帧）：", FrameFlowStop.Keep, frameFlowStop(health, true))
        }
        // 探针帧失败 ⇒ 进入第 1 轮，仍旧是"暂时"
        val second = healthAfterDecodeFailure(health, until)
        assertEquals(1, second.suspensionCycles)
        assertEquals("第 1 轮之后还是暂时，不许停帧：", FrameFlowStop.Keep, frameFlowStop(second, true))
        // 靶子：连错不到阈值的抖动连停用都没进，更不进停帧（这一档零命中=摆错了账）
        val shaken = failures(1L, 5L)
        assertTrue("靶子：两帧抖动就该既不停用也不判死：", shaken.suspendUntilFrame < 0L && shaken.giveUpReason == null)
        assertEquals(FrameFlowStop.Keep, frameFlowStop(shaken, true))
    }

    /** ①c 判死之后 [decoderFrameAction] 永远 Skip —— "绑着但什么都不解"那笔账的本体 */
    @Test
    fun givenUpDecoderSkipsEveryFrameForeverSoTheStreamMustStop() {
        val given = givenUp()
        assertTrue("真账没推到判死：", scannerGiveUp(given) && !scannerWorkingOf(given))
        for (frame in listOf(given.framesAtLastFailure, given.framesAtLastFailure + 1L, 1_000_000L)) {
            assertEquals("判死后第 $frame 帧还在被解（那就不必停帧了）：", DecoderFrameAction.Skip, decoderFrameAction(given, frame))
        }
        // 判死发生时 working 早已是 false 且不再翻面 ⇒ 绑定那颗 effect 的键表不动 ⇒ 没人解绑
        val pushed = healthAfterDecodeFailure(given, 999_999L)
        assertSame("判死之后健康度还在被改写（重复推送会白换引用）：", given, pushed)
        assertEquals(false, scannerWorkingOf(pushed))
    }

    /**
     * ①d 重复推送同一个判死：解绑只许发生一次。
     *
     * 两层各钉一件事：内核那层「帧流已经不绑了 ⇒ Keep」（就算那颗 effect 因故重跑也不会解第二次）；
     * 真账那层「同一份判死再推一次，health 一枚都不换」（分析器 `next === health` 直接 return，
     * 压根不再 push）。
     */
    @Test
    fun theSameGiveUpPushedTwiceUnbindsOnce() {
        val given = givenUp()
        assertEquals("第一次推送：", FrameFlowStop.Unbind, frameFlowStop(given, true))
        // 调用点解绑之后句柄为 null ⇒ 第二次推送同一档
        assertEquals("第二次推送（已停帧）：", FrameFlowStop.Keep, frameFlowStop(given, false))
        for (frame in listOf(given.framesAtLastFailure, given.framesAtLastFailure + 7L, 500L, 4_000L)) {
            val again = healthAfterDecodeFailure(given, frame)
            assertSame("同一档判死被推了第二次还换了引用：", given, again)
            assertEquals("同一档判死的第二次推送要求再解绑：", FrameFlowStop.Keep, frameFlowStop(again, false))
        }
    }

    /**
     * ①e 判死后回到前台：恢复那条路必须还是活的，而且**不靠**这里把相机绑回去。
     *
     * 本卡的头号回归风险就是这一档，所以钉四件事：恢复后的 health 不再判死且 working
     * （⇒ `scannerWorking` 翻回 true ⇒ 绑定那颗 effect 重跑）；停帧判据对它返回 Keep
     * （不许把刚绑回来的流再掐了）；恢复之后再判死仍照停（额度还剩）；额度用完不再给机会。
     */
    @Test
    fun recoveryAfterGiveUpLeavesTheRebindToTheBindEffect() {
        val given = givenUp()
        val first = healthAfterPageVisible(given)
        assertEquals("回到前台没把相机这条救回来（那停帧就是永久断粮）：", true, scannerWorkingOf(first))
        assertEquals(false, scannerGiveUp(first))
        assertEquals(1, first.pageVisibleRecoveries)
        assertEquals("恢复之后停帧判据还要求解绑（会把刚绑回来的流掐了）：", FrameFlowStop.Keep, frameFlowStop(first, true))
        // 恢复之后再走到判死：还能再停一次（这一档是"第二次判死"，帧号接着往上数）
        val again = driveToGiveUp(first, 200L)
        assertEquals("第二次判死不再停帧（电账又回来了）：", FrameFlowStop.Unbind, frameFlowStop(again, true))
        assertTrue("活路那句该说还剩第 2/2 次：${frameFlowWayBack(again)}", frameFlowWayBack(again).contains("2/$MaxPageVisibleRecoveries"))
        val second = healthAfterPageVisible(again)
        assertEquals(2, second.pageVisibleRecoveries)
        assertEquals("第二次恢复之后停帧判据还要求解绑：", FrameFlowStop.Keep, frameFlowStop(second, true))
        // 额度用完之后再判死：health 一枚都不许换（ON_RESUME 不能成为无界重试的引擎）
        val spent = again.copy(pageVisibleRecoveries = MaxPageVisibleRecoveries)
        assertSame("额度用完之后还在给新机会：", spent, healthAfterPageVisible(spent))
    }

    // ---- ② 措辞：只许说当下真做得到的动作 ----

    /** ②a 停帧之后还剩什么活路：额度 0/1/2 三档各说各的话，且谁都不许指向已死的入口 */
    @Test
    fun wayBackSentenceNamesTheOnlyLiveWaysBack() {
        val given = givenUp()
        val fresh = frameFlowWayBack(given)
        assertTrue("额度还剩时没说出回到前台那第 1/2 次：$fresh", fresh.contains("1/$MaxPageVisibleRecoveries"))
        assertTrue("没把「回到前台」说成活路：$fresh", fresh.contains("回到前台"))
        assertTrue("活路里该留着相册（走到这一档时 scanner 必然还活着）：$fresh", fresh.contains("相册"))
        val once = frameFlowWayBack(given.copy(pageVisibleRecoveries = 1))
        assertTrue("用掉一次之后没报第 2/2 次：$once", once.contains("2/$MaxPageVisibleRecoveries"))
        assertNotEquals("额度 0 与 1 说成了同一句话：", fresh, once)
        val spent = frameFlowWayBack(given.copy(pageVisibleRecoveries = MaxPageVisibleRecoveries))
        assertTrue("额度用完还假装有机会：$spent", spent.contains("已用完"))
        assertNotEquals("额度用完与还剩说成了同一句话：", fresh, spent)
        // 禁指向语：手输（2026-09-21 整条删除）、手电/补光（2026-09-22 整条拆除）、
        // 以及"退出重进/重新打开页面"—— 这一页的恢复路是回到前台，不是重开一次
        for (text in listOf(fresh, once, spent)) {
            for (banned in listOf("手输", "输入签到码", "手动输入", "手电", "补光", "闪光", "照亮", "灯", "重新打开", "退出重进", "重进", "设置", "秒")) {
                assertFalse("活路那句指向了不存在的入口/动作「$banned」：$text", text.contains(banned))
            }
        }
    }

    /** ②b 停帧那一行取证：三件事一枚不少（帧流到此为止 / 第几帧为什么 / 还剩什么活路） */
    @Test
    fun stopLogLineCarriesTheThreeFactsItOwes() {
        val given = givenUp()
        val line = frameFlowStopLogText(given)
        assertTrue("没说清帧流到此为止：$line", line.contains("不再到达") && line.contains("close"))
        assertTrue("没说出 close 的时机没被动（这一页的头号地雷，不许让日志含糊）：$line", line.contains("没动它的时机"))
        assertTrue("没说出第几帧判的死：$line", line.contains("第 ${given.framesAtLastFailure} 帧判死"))
        val reason = requireNotNull(given.giveUpReason)
        assertTrue("没带判死原因原文：$line", line.contains(reason))
        assertTrue("没说出还剩什么活路：$line", line.endsWith(frameFlowWayBack(given)))
        assertTrue("原因里该有那两件事实（连错几帧 / 试回几轮）：$reason", reason.contains("连续") && reason.contains(MaxDecodeSuspensionCycles.toString()))
        // 帧号未记录那一档：说实话，不许编一个 0 出来
        val noFrame = frameFlowStopLogText(given.copy(framesAtLastFailure = -1L))
        assertTrue("帧号缺失那一档没说实话：$noFrame", noFrame.contains("帧号未记录"))
        assertFalse("帧号缺失那一档把 -1 报了出去：$noFrame", noFrame.contains("-1"))
        // 靶子：停用窗口不进这一行（它有分析器里既有的那一行），所以它的原因本来是空的
        assertTrue("靶子：停用窗口没有判死原因：", suspended().giveUpReason == null)
    }

    // ---- ③ 常数与档位：不许漂成魔数 ----

    @Test
    fun policyKeepsTwoConclusionsAndTheBoundedQuota() {
        assertEquals("停帧只有两档（多一档就得回这里改表）：", 2, FrameFlowStop.entries.size)
        assertEquals("回到前台的额度上限：", 2, MaxPageVisibleRecoveries)
        assertEquals("连错几帧才算坏：", 3, ConsecutiveDecodeFailureLimit)
        assertEquals("自动试回几轮才算没救：", 3, MaxDecodeSuspensionCycles)
        // 判死前压掉的帧是有界的（20 + 40 + 60 = 120 帧 ≈ 4 秒），判死之后的空转没有上界
        // —— 这一枚差额就是本卡要收的那笔电账的形状
        val bounded = suspendWindowFrames(0) + suspendWindowFrames(1) + suspendWindowFrames(2)
        assertEquals("停用窗口的总帧数不再是 3 轮 × 递增倍数（$bounded）：", 6L * DecodeFailureSuspendWindowFrames, bounded)
    }

    // ---- ④ 接线形状（JVM 跑不到 Composable，按源码核对） ----

    /**
     * ④a 停帧那颗 effect 存在、判据出自内核，而且句柄与解绑同批（T64/T65 的纪律）。
     */
    @Test
    fun stopFrameFlowEffectExistsAndNullsHandlesBeforeUnbinding() {
        val code = withoutComments(readMainSource(SCAN_SCREEN_FILE))
        val effect = balancedBlock(code, "LaunchedEffect(scannerGiveUp)")
        assertTrue("停帧没走内核判据（自己拼分支就是第二份口径）：\n$effect", effect.contains("frameFlowStop("))
        assertTrue("health 是从分析器读出来的快照（判据不许自己去碰设备）：\n$effect", effect.contains("decoderHealthSnapshot()"))
        assertTrue("结论没和内核比对（返回什么就干什么=判据白写）：\n$effect", effect.contains("!= FrameFlowStop.Unbind"))
        val cameraCleared = effect.indexOf("boundCamera = null")
        val analysisCleared = effect.indexOf("analysisUseCase = null")
        val unbind = effect.indexOf("cameraProvider.unbindAll()")
        check(cameraCleared >= 0 && analysisCleared >= 0 && unbind >= 0) {
            "句柄清零或解绑那一步不在停帧 effect 里了（句柄不许指向已解绑的 Camera）：\n$effect"
        }
        assertTrue("句柄没排在 unbindAll 之前清零：\n$effect", cameraCleared < unbind && analysisCleared < unbind)
        assertTrue("停帧那一行的措辞没出自内核（手拼就是第二份真相）：\n$effect", effect.contains("Log.w(TAG, frameFlowStopLogText("))
        // 换挡才说话：这一档不许出现按帧/循环路径
        assertFalse("停帧 effect 里出现了循环（会按帧刷解绑）：\n$effect", effect.contains("while ("))
        assertFalse("停帧 effect 里出现 Log.i/Log.d/Log.e（终局判据一律 Warn）：\n$effect",
            effect.contains("Log.i(") || effect.contains("Log.d(") || effect.contains("Log.e("))
        // 靶子：全页解绑一共两处（绑定前那次 + 停帧这次），多一处就是有人另起了一条停帧路
        assertEquals("unbindAll 的调用点数目变了：", 2, occurrences(code, "unbindAll()"))
    }

    /**
     * ④b 停用窗口那条路一个字没动：`!scannerWorking` 仍排在绑定 effect 自己的 unbindAll 之前。
     *
     * 这是本卡最容易被"顺手统一"改坏的地方 —— 那一句一旦挪到 unbindAll 之后，T59① 的自动
     * 活路（窗口过完自己探一帧）就没了，界面上会多出一档只能退出重进的病。
     */
    @Test
    fun suspensionStillKeepsTheFrameFlowAndTheKeyStringIsIntact() {
        val code = withoutComments(readMainSource(SCAN_SCREEN_FILE))
        assertTrue("绑定那颗 effect 的键表被改过了（形状守卫钉着这一串）：", code.contains("LaunchedEffect(granted, provider, scannerWorking, analyzer)"))
        val bind = balancedBlock(code, "LaunchedEffect(granted, provider, scannerWorking, analyzer)")
        val guard = bind.indexOf("if (!granted || !scannerWorking) return@LaunchedEffect")
        val unbind = bind.indexOf("cameraProvider.unbindAll()")
        check(guard >= 0 && unbind >= 0) { "停用/绑定那一句或绑定前的解绑换写法了，T59① 的前提要重核：\n$bind" }
        assertTrue("停用窗口现在会先解绑再返回（T59① 的自动活路被掐了）：\n$bind", guard < unbind)
        // 判死的 push 那一句字面量不许漂（ScanUiStatusTest ⑤ 钉着同一串）
        assertTrue("判死翻面不再推进组合：", code.contains("onGiveUpChanged = { giveUp -> scannerGiveUp = giveUp }"))
        // 分析器只交出快照，不参与解绑：它手里既没有 provider，也不该去摸
        val analyzer = balancedBlock(code, "private class QrCodeAnalyzer(")
        val getter = balancedBlock(analyzer, "fun decoderHealthSnapshot()")
        assertFalse("分析器自己去解绑（close 次序的坑就是这么踩出来的）：\n$getter", getter.contains("unbind"))
        assertFalse("分析器整类里出现了 unbindAll：", analyzer.contains("unbindAll"))
        assertTrue("靶子：快照函数就是读那一枚 volatile health：\n$getter", getter.contains("= health"))
    }

    /** ④c 恢复那条路（回到前台 → scannerWorking 翻回 true → 绑定那颗 effect 重跑）接线仍在 */
    @Test
    fun thePageVisibleRecoveryPathStillDrivesTheRebind() {
        val code = withoutComments(readMainSource(SCAN_SCREEN_FILE))
        val resume = balancedBlock(code, "LaunchedEffect(permissionResumeTick)")
        assertTrue("回到前台不再问分析器要 working：\n$resume", resume.contains("analyzer?.recoverOnPageVisible()"))
        assertTrue("working 没写回组合（那停过帧之后就再也绑不回来了）：\n$resume", resume.contains("scannerWorking = working"))
        val analyzer = balancedBlock(code, "private class QrCodeAnalyzer(")
        val recover = balancedBlock(analyzer, "fun recoverOnPageVisible(")
        assertTrue("恢复时不再推 working（绑定那颗 effect 的键就不动）：\n$recover", recover.contains("onWorkingChanged(working)"))
        assertTrue("恢复时不再推判死档位：\n$recover", recover.contains("onGiveUpChanged(scannerGiveUp(next))"))
        // 订正的形状：额度用完那一行必须先算档位再进模板。原来那句是
        // `"…）：" + health.giveUpReason ?: "停用窗口内"`，`+` 绑得比 `?:` 紧 ⇒ Elvis 永远取
        // 左操作数，停用窗口里读出来是"…：null" —— 本卡那句"还剩什么活路"的近邻证据不许说谎。
        assertTrue("额度用完那一行不再先算档位（死 Elvis 回来了）：\n$recover", recover.contains("val why = health.giveUpReason ?: \"停用窗口内\""))
        assertFalse("取证的拼接又把 ?: 挂在 + 后面（读出来就是 null）：\n$recover", Regex("""\+\s*health\.giveUpReason""").containsMatchIn(recover))
        // 绑定成功那一条自己带 unbindAll + 句柄重写 ⇒ 停过帧之后照样能回来
        val bind = balancedBlock(code, "LaunchedEffect(granted, provider, scannerWorking, analyzer)")
        assertTrue("绑定成功路径不再写句柄：\n$bind", bind.contains("boundCamera = camera"))
        assertTrue("绑定不再把 analyzer 交给分析流：\n$bind", bind.contains("setAnalyzer(analysisExecutor, activeAnalyzer)"))
        assertTrue("靶子：停帧那颗 effect 在绑定那颗之后（同一个快照里判据读到的是新 health）：", code.indexOf("LaunchedEffect(scannerGiveUp)") > code.indexOf("LaunchedEffect(granted, provider, scannerWorking, analyzer)"))
    }

    // ---- ⑤ 内核纯度 ----

    /** ⑤a 判据内核零 import、零设备符号、三档判据一枚不少（否则表驱动就塌回真机） */
    @Test
    fun frameFlowKernelStaysPureJvm() {
        val raw = readMainSource(KERNEL_FILE)
        val code = withoutComments(raw)
        val imports = code.lines().filter { it.trim().startsWith("import ") }
        assertTrue("$KERNEL_FILE 里出现了 import，这段判据就到不了 JVM：\n$imports", imports.isEmpty())
        for (
            banned in listOf(
                "android.", "androidx.", "Build.", "CameraProvider", "ProcessCamera", "ImageProxy",
                "ImageAnalysis", "SystemClock", "System.currentTimeMillis", "Log.", "PackageManager",
            )
        ) {
            assertFalse("判据本体自己去碰了设备（$banned）—— 这些都必须是参数", code.contains(banned))
        }
        assertTrue("内核文件是空的？", code.length > 1_000)
        for (entry in listOf(
            "internal enum class FrameFlowStop", "internal fun frameFlowStop",
            "internal fun frameFlowWayBack", "internal fun frameFlowStopLogText",
        )) {
            assertTrue("内核少了 $entry 这一档：", code.contains(entry))
        }
        // 「暂时 vs 判死」只许有一个来源：停帧判据复用 [scannerGiveUp]，不自建第二份判死档
        val stop = balancedBlock(code, "internal fun frameFlowStop(")
        assertTrue("停帧判据不再吃 [scannerGiveUp]：\n$stop", stop.contains("scannerGiveUp(health)"))
        assertFalse("停帧判据自己读 giveUpReason 拼分支（第二份判死档）：\n$stop", stop.contains("giveUpReason"))
        assertFalse("停帧判据自己去读停用帧号：\n$stop", stop.contains("suspendUntilFrame"))
        assertTrue("靶子：额度上限仍是恢复那颗内核的常数：", code.contains("MaxPageVisibleRecoveries"))
        assertFalse("内核里出现了硬编码的次数上限：", Regex(">= 2").containsMatchIn(code))
    }

    /** ⑤b 判据不许漏回分析器 / 页面：调用点只许"读事实 + 执行" */
    @Test
    fun judgementDoesNotLeakBackIntoThePage() {
        val code = withoutComments(readMainSource(SCAN_SCREEN_FILE))
        val effect = balancedBlock(code, "LaunchedEffect(scannerGiveUp)")
        for (banned in listOf("giveUpReason", "suspendUntilFrame", "consecutiveFailures", "suspensionCycles", "MaxPageVisibleRecoveries")) {
            assertFalse("页面自己算起判死/额度那本账（$banned）：\n$effect", effect.contains(banned))
        }
        // 靶子：内核文件里这些字段是被读的（零命中=扫错了对象）
        val kernel = withoutComments(readMainSource(KERNEL_FILE))
        assertTrue("靶子：内核仍在读额度与帧号：", kernel.contains("pageVisibleRecoveries") && kernel.contains("framesAtLastFailure"))
        assertEquals("停帧判据在页面里的调用点只能一处：", 1, occurrences(code, "frameFlowStop("))
        assertEquals("停帧措辞在页面里的调用点只能一处：", 1, occurrences(code, "frameFlowStopLogText("))
    }

    // ---- ⑥ 反向钉：本卡不许改坏的东西 ----

    /** ⑥a `image.close()` 的四发与次序一个字没动（本卡的头号地雷） */
    @Test
    fun closeTimingIsUntouched() {
        val analyze = balancedBlock(withoutComments(readMainSource(SCAN_SCREEN_FILE)), "override fun analyze(")
        assertEquals("close 的调用点数目变了（Skip/空帧/同步抛/回调各一）：", 4, occurrences(analyze, "image.close()"))
        assertTrue("close 不再挂在 addOnCompleteListener 上：\n$analyze", analyze.contains(".addOnCompleteListener { image.close() }"))
        assertFalse("close 被挪进了 finally（ML Kit 还没读帧就报 Image is already closed）：", analyze.contains("} finally {"))
        assertFalse("analyze 入口路径里出现了 Log.（按帧分配）：", analyze.contains("Log."))
        assertTrue("靶子：停用那一档仍然先 close 再返回：\n$analyze", analyze.contains("if (decoderFrameAction(health, frame) == DecoderFrameAction.Skip)"))
    }

    /** ⑥b 恢复内核 [ScanRecoveryPolicy] 逐字节没动：停帧这件事的判据长在它外面 */
    @Test
    fun recoveryKernelFileIsByteIdenticalToBaseline() {
        val path = "$MAIN_PREFIX/$POLICY_FILE"
        val baseline = gitShow(BASELINE, path)
        check(baseline != null) { "git 跑不动或基线取不到（$path@$BASELINE），反向钉无从核对" }
        assertEquals(
            "$POLICY_FILE 相对基点被改过了：停帧的判据必须长在 ScanFrameFlowPolicy 里，" +
                "别把第二份真相塞进恢复内核（那一本账另有 T66 的逐字节反向钉）",
            normalizeNewlines(baseline),
            normalizeNewlines(File(findMainJavaDir(), POLICY_FILE).readText()),
        )
        // 靶子：扫的确实是那颗内核（空文件会让这一条永远绿）
        assertTrue("靶子：恢复内核的判据本体还在：", baseline.contains("internal fun scannerGiveUp"))
    }

    /** ⑥c 撤走的东西不许因为"加了停帧"回来；T66 的第二引擎接线不许被改坏 */
    @Test
    fun deadEntrancesStayOutAndSecondEngineWiringSurvives() {
        val page = blankCommentsAndLiterals(readMainSource(SCAN_SCREEN_FILE))
        val kernel = blankCommentsAndLiterals(readMainSource(KERNEL_FILE))
        for (banned in listOf("ProjectorZoomRatio", "ZoomSuggestionOptions", "setZoomSuggestionOptions")) {
            assertFalse("已撤走的东西回来了（$banned）：", page.contains(banned))
        }
        for (banned in listOf("torch", "Torch", "enableTorch", "hasFlashUnit")) {
            assertFalse("手电的残壳回来了（$banned）：", page.contains(banned) || kernel.contains(banned))
        }
        // 靶子 1：阶梯的正主还在（零命中=扫错了文件）
        assertTrue("靶子：帧观测阶梯仍在页面上：", page.contains("advanceScanAssist("))
        // T66 那两道共用的闸门与补解调用点一枚不少
        val code = withoutComments(readMainSource(SCAN_SCREEN_FILE))
        assertEquals("trySecondEngine( 的出现次数不是「定义 + 调用」：", 2, occurrences(code, "trySecondEngine("))
        assertEquals("submitDecodedText( 的出现次数不是「定义 + 两条引擎」：", 3, occurrences(code, "submitDecodedText("))
        assertTrue("补解不再排在 ML Kit 之前（本卡不该动那一处次序）：", run {
            val analyze = balancedBlock(code, "override fun analyze(")
            val fallback = analyze.indexOf("trySecondEngine(image, frame)")
            val process = analyze.indexOf("scanner.process(")
            check(fallback >= 0 && process >= 0) { "analyze 里那两处的写法变了：\n$analyze" }
            fallback < process
        })
        assertFalse("布尔死锁回来了：", code.contains("consumed"))
    }

    /** ⑥d 取证纪律：新增那一行是 Warn，级别不许漂；本页没有 debug-only 门禁 */
    @Test
    fun newTraceStaysAtWarnAndIsNotDebugGated() {
        val code = withoutComments(readMainSource(SCAN_SCREEN_FILE))
        val effect = balancedBlock(code, "LaunchedEffect(scannerGiveUp)")
        assertTrue("停帧那一行不是 Warn（HyperOS 砍到 Info 以下就读不到）：\n$effect", effect.contains("Log.w(TAG, frameFlowStopLogText("))
        assertEquals("本页出现了 debug-only 门禁：", 0, occurrences(code, "BuildConfig.DEBUG"))
        for (banned in listOf("Log.d(", "Log.v(", "Log.e(")) {
            assertEquals("取证行用了 $banned：", 0, occurrences(code, banned))
        }
        assertTrue("靶子：停帧那一行确实经过 TAG 出口（零命中=扫错了对象）：", effect.contains("TAG,"))
    }

    // ---- 表驱动的行 ----

    private class Row(
        val label: String,
        val health: ScanDecoderHealth,
        val flowBound: Boolean,
        val expected: FrameFlowStop,
    )

    // ---- 源码核对工具（与 ScanCameraAidWiringGuardTest 同一套，找不着锚点就抛） ----

    private fun readMainSource(relativeFromJava: String): String {
        val file = File(findMainJavaDir(), relativeFromJava)
        assertTrue("找不到 ${file.path}：文件挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText()
    }

    /** 从 [signature] 之后第一个 `{` 起配平到对应右括号（含）；找不到锚点就抛，静默跳过等于没有守卫 */
    private fun balancedBlock(source: String, signature: String): String {
        val at = source.indexOf(signature)
        check(at >= 0) { "找不到 $signature：写法换过了，这条守卫要跟着改" }
        val open = source.indexOf('{', at)
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

    /** 注释与字符串字面量都抹成空白（长度与换行位置不变）：整份文件的红线扫描用这份 */
    private fun blankCommentsAndLiterals(src: String): String {
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

                src.startsWith("\"\"\"", i) -> {
                    val end = src.indexOf("\"\"\"", i + 3).let { if (it < 0) out.size else it + 3 }
                    for (k in (i + 3) until (end - 3).coerceAtLeast(i + 3)) if (out[k] != '\n') out[k] = ' '
                    i = end
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
                    for (k in (i + 1) until (j - 1).coerceAtLeast(i + 1)) out[k] = ' '
                    i = j
                }

                else -> i++
            }
        }
        return String(out)
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

    /** `git show <rev>:<path>`；取不到（没 git / 没这个对象）返回 null，由调用方判失败 */
    private fun gitShow(rev: String, path: String): String? = try {
        val process = ProcessBuilder("git", "show", "$rev:$path")
            .directory(findRepoRoot())
            .redirectErrorStream(true)
            .start()
        val out = process.inputStream.readBytes() // 读干净再等：管道灌满会把子进程卡死在 waitFor 之前
        if (process.waitFor() != 0) null else String(out, Charsets.UTF_8)
    } catch (io: java.io.IOException) {
        null
    }

    private fun normalizeNewlines(text: String): String = text.replace("\r\n", "\n")

    private fun findRepoRoot(): File {
        var dir: File? = File("").absoluteFile
        repeat(6) {
            val candidate = checkNotNull(dir) { "走到根外面了：找不到仓库根" }
            if (File(candidate, ".git").exists()) return candidate
            dir = candidate.parentFile
        }
        throw IllegalStateException("找不到仓库根：当前目录 ${File("").absolutePath}")
    }

    /** 单测的 cwd 是 :app 模块目录，也可能是仓库根：两种布局都试，全落空就抛 */
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
        const val MAIN_PREFIX = "app/src/main/java"
        const val SCAN_SCREEN_FILE = "com/buaa/schedule/ui/signin/SpocScanScreen.kt"
        const val KERNEL_FILE = "com/buaa/schedule/ui/signin/ScanFrameFlowPolicy.kt"
        const val POLICY_FILE = "com/buaa/schedule/ui/signin/ScanRecoveryPolicy.kt"

        /** 反向钉的比较基线 = 本分支的基点（master 83d18f5）；下一轮重钉时换成新的属主卡合并点 */
        const val BASELINE = "83d18f5"
    }
}
