package com.buaa.schedule.ui.signin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T64/T65 判据内核的表驱动单测：交付帧够不够、点击→测光点映射、缩放钳制，
 * 以及 T65①② 的画面档位（[frameCodeRung]）与检测驱动缩放阶梯（[advanceScanAssist]）。
 *
 * 全文件零 android 依赖 —— 这正是将判据抽成 [ScanCameraAidPolicy] 的全部理由：
 * 640×480 到底够不够、点在 FILL_CENTER 裁切后的哪儿、一枚 290 px 的候选框算不算"太小"、
 * 连 30 帧太小该不该抬一档，都必须能在 JVM 里逐支打表，而不是只能在真机上"扫一枪看看"。
 */
class ScanCameraAidPolicyTest {

    // ---- ① 交付帧下限：常数必须能从推导链复算 ----

    @Test
    fun shortEdgeFloorIsDerivedNotMagic() {
        // 2px/模块 × 97 模块 ÷ (0.5 占洞 × 0.62 洞占比) = 194 ÷ 0.31 ≈ 625.8 → 626
        assertEquals(626, minUsefulAnalysisShortEdgePx(0.62f))
        // 洞占比越大要求越低；整屏(1f)时 194÷0.5 = 388
        assertEquals(388, minUsefulAnalysisShortEdgePx(1f))
        // 退化占比拿请求目标兜底（宁可要求高，不可把不够的帧判成够）
        for (bad in listOf(0f, -0.5f, 1.5f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertEquals("退化占比 $bad 没拿请求目标兜底", RequestAnalysisHeightPx, minUsefulAnalysisShortEdgePx(bad))
        }
    }

    /**
     * T83④：拿真码把 [QrModuleSideBudget] 这笔账重算一遍，结论是 97 仍然对 ——
     * 而"仍然对"必须是算出来的，不是把上一版的注释抄一遍。
     *
     * 真码逐字原文 108 字符，按 ISO/IEC 18004 的 byte 模式容量表（本机用 `qrcode` 库逐档编码复算，
     * 不是查记忆）落 v6(L) / v7(M) / v8(Q) / v10(H) ⇒ 41 / 45 / 49 / **57** 模块/边。
     * 下面三个反证数就是"顺着真码降到 57"的代价：① 的下限掉到 368（默认帧过关）、
     * 而 ④ 按 3 px/模块要的是 552（同一枚码被两本账一读一否）、候选框阈值掉到 171（阶梯更早停手）。
     */
    @Test
    fun realSignInCodeStillFitsTheModuleBudget() {
        val realCode = "http://iclass.buaa.edu.cn:8081/app/course/stu_scan_sign.action" +
            "?courseSchedId=2488752&timestamp=1790247391994"
        assertEquals("真码原文不再是 108 个字符（这条链的取证形状变了，整笔账要重算）：", 108, realCode.length)
        assertEquals("QR 版本→边长模块数的公式不是 17 + 4v 了：", 57, 17 + 4 * 10)
        assertEquals("预算对应的版本不再是 v20：", 97, 17 + 4 * 20)
        assertTrue("预算已经盖不住真码最坏那一档（v10 = 57 模块/边）：", QrModuleSideBudget >= 57)
        // ① 与 ④ 两本账都必须把 CameraX 默认那一帧判死 —— 这正是"照着一张码降档"会丢掉的东西
        val defaultShortEdge = 480
        assertEquals("① 的下限不再是 2px × 97 ÷ (0.5 × 0.62)：", 626, minUsefulAnalysisShortEdgePx(0.62f))
        assertTrue(
            "640×480 在 ① 这一档过关了（那本账的立论当场消失）：",
            defaultShortEdge < minUsefulAnalysisShortEdgePx(0.62f),
        )
        assertEquals("④ 的候选框阈值不再由同一个预算算出：", 291, MinUsefulCandidateBoxPx)
        // 默认帧（短边 480）给一枚 v10 真码的模块尺寸：码体 480×0.5×0.62 = 148.8 px ÷ 57 = **2.61 px/模块**
        // —— 到了 ④ 认的 3 px 可用档之下，所以 ① 把这帧判成 BelowFloor 不是"按 97 才判得死"，
        // 而是这一帧本来就喂不出可用的码：两本账在同一枚真码上同向，这才留得住 97 这档。
        val realCodeModulePx = defaultShortEdge * DistantCodeHoleFill * 0.62f / 57f
        assertTrue(
            "640×480 已经给到真码 $realCodeModulePx px/模块（≥${UsefulModulePx}px）—— 那 ① 判它就是过严，" +
                "预算该降到 57 而不是 97：",
            realCodeModulePx < UsefulModulePx,
        )
        // 反证：预算降到真码的最坏档 57 ⇒ ① 的下限 368（480 过关）、④ 的可用档 552（480 不过关）
        assertEquals("降到 v10 档时 ① 的下限：", 368, floorForBudget(57, MinModuleSizePx))
        assertEquals("降到 v10 档时 ④ 的可用档：", 552, floorForBudget(57, UsefulModulePx))
        assertEquals("降到 v10 档时的候选框阈值：", 171, UsefulModulePx * 57)
        assertTrue(
            "降档之后 640×480 就过关 ①（368 ≤ 480）而仍然不够 ④（480 < 552）—— 两本账会互相打脸",
            floorForBudget(57, MinModuleSizePx) <= defaultShortEdge && defaultShortEdge < floorForBudget(57, UsefulModulePx),
        )
        // T84 提交拼的 &id= 只活在提交 URL 上，不在投影码里（否则这条码要按 121 字符重算）
        assertEquals("提交形状（原文 + &id=）才是 121 字符：", 121, "$realCode&id=123456789".length)
    }

    /** 同一套推导，只把 [QrModuleSideBudget] 换成别的模块数再算一遍（只为上面那三个反证数服务） */
    private fun floorForBudget(modulesPerSide: Int, pxPerModule: Int): Int {
        val raw = (pxPerModule * modulesPerSide).toFloat() / (DistantCodeHoleFill * 0.62f)
        val floor = raw.toInt()
        return if (raw > floor) floor + 1 else floor
    }

    /** 关键表：CameraX 默认的 640×480 必须落在 BelowFloor —— 这张卡的全部立论都钉在这一行上 */
    @Test
    fun verdictTableSeparatesDefaultFromRequest() {
        val cases = listOf(
            // width, height, verdict
            Triple(640, 480, AnalysisFrameVerdict.BelowFloor),      // CameraX 默认档：短边 480 < 626
            Triple(480, 640, AnalysisFrameVerdict.BelowFloor),      // 竖帧同理（短边判据不看方向）
            Triple(960, 540, AnalysisFrameVerdict.BelowFloor),      // 540p 也不过线
            Triple(1024, 600, AnalysisFrameVerdict.BelowFloor),     // 600 < 626，差 26 px 也是不过
            Triple(1024, 640, AnalysisFrameVerdict.MeetsFloor),     // 过下限、没到 720
            Triple(960, 720, AnalysisFrameVerdict.MeetsRequest),    // 4:3 的"抬一档"兑现：短边 720
            Triple(1280, 720, AnalysisFrameVerdict.MeetsRequest),   // 请求原样兑现
            Triple(1600, 1200, AnalysisFrameVerdict.MeetsRequest),  // 16:9 没有时给更大的 4:3
            Triple(0, 480, AnalysisFrameVerdict.BrokenFrame),
            Triple(-5, 1200, AnalysisFrameVerdict.BrokenFrame),
            Triple(640, 0, AnalysisFrameVerdict.BrokenFrame),
        )
        for ((width, height, expected) in cases) {
            assertEquals(
                "${width}×$height 判成了什么：",
                expected,
                analysisFrameVerdict(width, height, 0.62f),
            )
        }
    }

    @Test
    fun verdictRespectsDegenerateViewfinderRatio() {
        // 占比退化 ⇒ 下限翻到 720：700×640 在正常占比下是 MeetsFloor，这里必须是 BelowFloor
        assertEquals(AnalysisFrameVerdict.MeetsFloor, analysisFrameVerdict(700, 640, 0.62f))
        assertEquals(AnalysisFrameVerdict.BelowFloor, analysisFrameVerdict(700, 640, 0f))
    }

    /** 取证行由内核拼：每档都要说得出"请求 vs 实际"，非法档说非法 */
    @Test
    fun logTextSaysTheSameThingTheVerdictMeans() {
        val below = analysisFrameLogText(640, 480, 0.62f)
        assertTrue("低于下限那档没点名请求尺寸：$below", below.contains("请求 ${RequestAnalysisWidthPx}×${RequestAnalysisHeightPx}"))
        assertTrue("低于下限那档没给线的出处：$below", below.contains("626"))
        assertTrue("低于下限那档没说后果：$below", below.contains("救不回来"))
        val met = analysisFrameLogText(1280, 720, 0.62f)
        assertTrue("兑现档还在哭根因：$met", !met.contains("救不回来"))
        assertTrue("兑现档没说实话（余量）：$met", met.contains("余量"))
        val middle = analysisFrameLogText(1024, 640, 0.62f)
        assertTrue("中间档两头都要说：$middle", middle.contains("过了") && middle.contains("余量小"))
        assertTrue("非法档没说不可信：${analysisFrameLogText(0, 0, 0.62f)}", analysisFrameLogText(0, 0, 0.62f).contains("不可信"))
    }

    // ---- ② 点击 → 测光点映射 ----

    private data class TapCase(
        val label: String,
        val tapX: Float,
        val tapY: Float,
        val viewW: Float,
        val viewH: Float,
        val imgW: Int,
        val imgH: Int,
        val rot: Int,
    )

    @Test
    fun centreTapAlwaysMapsToCentreOfTheFullFrame() {
        for (case in listOf(
            // 竖屏手机 + 横帧转 90°：真机最常见的组合
            TapCase("portrait-90", 540f, 1200f, 1080f, 2400f, 640, 480, 90),
            TapCase("portrait-270", 540f, 1200f, 1080f, 2400f, 640, 480, 270),
            // 横屏 + rot 0
            TapCase("landscape-0", 960f, 640f, 1920f, 1280f, 640, 480, 0),
            // 方视口：一个轴恰好铺满、另一个轴裁切
            TapCase("square-180", 500f, 500f, 1000f, 1000f, 640, 480, 180),
        )) {
            val p = analysisMeteringPointForTap(
                case.tapX, case.tapY, case.viewW, case.viewH, case.imgW, case.imgH, case.rot,
            )
            assertNotNull("${case.label}：视口正中映射丢了", p)
            val point = requireNotNull(p)
            assertEquals("${case.label}：正中 x", 0.5f, point.x, 1e-4f)
            assertEquals("${case.label}：正中 y", 0.5f, point.y, 1e-4f)
        }
    }

    /** 手算账（portrait 1080×2400、帧 640×480、rot 90 ⇒ 显示 480×640、scale 3.75、横窗 288、offset 96） */
    @Test
    fun fillCenterCropIsUndoneWithTheExpectedNumbers() {
        fun map(x: Float, y: Float) = analysisMeteringPointForTap(x, y, 1080f, 2400f, 640, 480, 90)
        // 左上角：落在横窗左缘（显示帧的 96/480 = 0.2），纵缘 0
        assertEquals(0.2f, requireNotNull(map(0f, 0f)).x, 1e-4f)
        assertEquals(0f, requireNotNull(map(0f, 0f)).y, 1e-4f)
        // 右下角：横窗右缘 (96+288)/480 = 0.8，纵窗满格
        assertEquals(0.8f, requireNotNull(map(1080f, 2400f)).x, 1e-4f)
        assertEquals(1f, requireNotNull(map(1080f, 2400f)).y, 1e-4f)
        // 顶部中点：横向仍是裁切带的中点 0.5，纵向 0
        assertEquals(0.5f, requireNotNull(map(540f, 0f)).x, 1e-4f)
        assertEquals(0f, requireNotNull(map(540f, 0f)).y, 1e-4f)
    }

    @Test
    fun degenerateAndOutsideInputsRefuseToMap() {
        // 出界四边各一刀（不许夹到边缘——那是把画外的意图翻译成画面正中）
        assertNull(analysisMeteringPointForTap(-1f, 5f, 1080f, 2400f, 640, 480, 90))
        assertNull(analysisMeteringPointForTap(1081f, 5f, 1080f, 2400f, 640, 480, 90))
        assertNull(analysisMeteringPointForTap(5f, -0.5f, 1080f, 2400f, 640, 480, 90))
        assertNull(analysisMeteringPointForTap(5f, 2400.5f, 1080f, 2400f, 640, 480, 90))
        // NaN 点击
        assertNull(analysisMeteringPointForTap(Float.NaN, 100f, 1080f, 2400f, 640, 480, 90))
        // 视口/帧尺寸退化
        assertNull(analysisMeteringPointForTap(10f, 10f, 0f, 2400f, 640, 480, 90))
        assertNull(analysisMeteringPointForTap(10f, 10f, 1080f, -1f, 640, 480, 90))
        assertNull(analysisMeteringPointForTap(10f, 10f, 1080f, 2400f, 0, 480, 90))
        assertNull(analysisMeteringPointForTap(10f, 10f, 1080f, 2400f, 640, -8, 90))
        // 不认识的旋转值：宁可不映射也不猜一个坐标系
        assertNull(analysisMeteringPointForTap(540f, 1200f, 1080f, 2400f, 640, 480, 45))
        // 认识的正负等价：-90 ≡ 270
        assertEquals(0.5f, requireNotNull(analysisMeteringPointForTap(540f, 1200f, 1080f, 2400f, 640, 480, -90)).x, 1e-4f)
    }

    @Test
    fun rotationSwapsTheDisplayAspectAndLandscapeFollows() {
        // rot 90 vs rot 0 的同一视口：换轴后横向裁得更狠。竖视口 1080×2400、帧 640×480：
        // rot 0 时显示 640×480 ⇒ scale = max(1.6875, 5) = 5，横窗 216、offset 212
        val p = requireNotNull(analysisMeteringPointForTap(0f, 0f, 1080f, 2400f, 640, 480, 0))
        assertEquals(212f / 640f, p.x, 1e-4f)
        assertEquals(0f, p.y, 1e-4f)
    }

    // ---- ③ 缩放钳制 ----

    @Test
    fun zoomClampTable() {
        val cases = listOf(
            // requested, min, max → expected（null = 这台没有缩放控制）
            ZoomRow(1.5f, 1f, 8f, 1.5f),
            ZoomRow(0.2f, 1f, 8f, 1f),      // 下钳
            ZoomRow(20f, 1f, 8f, 8f),       // 上钳
            ZoomRow(1f, 2f, 8f, 2f),       // 请求在区间下界之下也钳得动
            ZoomRow(1.5f, null, 8f, null), // min 没报出来
            ZoomRow(1.5f, 1f, null, null), // max 没报出来
            ZoomRow(1.5f, null, null, null),
            ZoomRow(1.5f, 0f, 8f, null),   // min ≤ 0：数据不可信
            ZoomRow(1.5f, -1f, 8f, null),
            ZoomRow(1.5f, 4f, 4f, null),   // 固定变焦
            ZoomRow(1.5f, 8f, 1f, null),   // 区间倒挂
            ZoomRow(1.5f, Float.NaN, 8f, null),
            ZoomRow(2f, 1f, 1.5f, 1.5f),   // 区间窄也钳得到（顶到 1.5）
        )
        for (row in cases) {
            assertEquals("$row", row.expected, clampedZoomRatio(row.requested, row.min, row.max))
        }
        // NaN 请求按"不动"处理而不是对设备说"你没缩放控制"
        assertEquals(1f, clampedZoomRatio(Float.NaN, 1f, 8f))
    }

    // ---- ④ T65① 画面档位：阈值可复算 + 全表 ----

    /** 阈值不许漂成魔数：291 = 3 px/模块 × 97 模块预算，每一半都有出处 */
    @Test
    fun candidateBoxFloorIsDerivedNotMagic() {
        assertEquals("实测可用档不是 3 px/模块：", 3, UsefulModulePx)
        assertEquals("候选框阈值不再由推导算出：", UsefulModulePx * QrModuleSideBudget, MinUsefulCandidateBoxPx)
        assertEquals(291, MinUsefulCandidateBoxPx)
    }

    @Test
    fun frameCodeRungTable() {
        val rows = listOf(
            // readable, candidates, largestBoxEdge → expected（边读边有原文时框多大都不重要）
            RungRow(1, 0, -1, FrameCodeRung.CodeReadable),
            RungRow(2, 5, 10, FrameCodeRung.CodeReadable),
            RungRow(0, 0, -1, FrameCodeRung.NothingDetected),   // 一枚候选都没有：瞄的问题
            RungRow(0, 1, 290, FrameCodeRung.CodeTooSmall),     // 291 差一像素就是太小
            RungRow(0, 1, 291, FrameCodeRung.CodeUndecodable),  // 到线：连 v20 预算都放得下，不该再怪尺寸
            RungRow(0, 3, 1200, FrameCodeRung.CodeUndecodable), // 框大到顶也解不开：焦点/抖动的事
            RungRow(0, 1, 0, FrameCodeRung.CodeUndecodable),    // 框退化：不敢据此抬视场
            RungRow(0, 1, -7, FrameCodeRung.CodeUndecodable),   // 同上，负数
            RungRow(0, -2, 500, FrameCodeRung.NothingDetected), // 计数退化按没看见处理（宁不说谎）
            RungRow(-1, 1, 100, FrameCodeRung.CodeTooSmall),    // 负原文计数不算"读到了"
        )
        for (row in rows) {
            assertEquals(
                "readable=${row.readable} candidates=${row.candidates} edge=${row.edge}：",
                row.expected,
                frameCodeRung(row.readable, row.candidates, row.edge),
            )
        }
    }

    // ---- ④ T65② 缩放阶梯：走档、回滚、滞后、无缩放控制 ----

    /** 连续喂 n 帧同一档位，返回末状态 */
    private fun feed(
        start: ScanAssistState,
        rung: FrameCodeRung,
        frames: Int,
        zoomAvailable: Boolean = true,
    ): Pair<ScanAssistState, List<ScanAssistOutcome>> {
        var state = start
        val outcomes = mutableListOf<ScanAssistOutcome>()
        repeat(frames) {
            val outcome = advanceScanAssist(state, rung, zoomAvailable)
            state = outcome.state
            outcomes += outcome
        }
        return state to outcomes
    }

    /** 阶梯档位表本体：4 档、单调升、顶 2.0×、基线 1f 不在表里 */
    @Test
    fun zoomLadderIsMonotonicAndBounded() {
        assertEquals(4, ZoomLadderRatios.size)
        assertEquals(1.25f, ZoomLadderRatios.first(), 0f)
        assertEquals("顶档不许越过 2.0×（视场滚雪球的界）：", 2.0f, ZoomLadderRatios.last(), 0f)
        assertTrue("阶梯必须单调升：", ZoomLadderRatios.zipWithNext().all { (a, b) -> b > a })
        assertTrue("T64 的固定 1.5× 必须在表里（现在是路径的一站）：", ZoomLadderRatios.contains(1.5f))
        assertEquals(ZoomBaselineRatio, zoomLadderRatio(0))
        assertEquals(1.25f, zoomLadderRatio(1))
        assertEquals(2.0f, zoomLadderRatio(4))
        // 越界钳到表尾：取证路径不抛
        assertEquals(2.0f, zoomLadderRatio(9))
    }

    /** 走档的正身：每连满足 ZoomStepFrames 上一档，一次一个命令、比值对表 */
    @Test
    fun tooSmallStreakClimbsOneStepPerWindow() {
        var state = ScanAssistState()
        val commands = mutableListOf<Float>()
        repeat((ZoomStepFrames * ZoomLadderRatios.size).toInt()) {
            val outcome = advanceScanAssist(state, FrameCodeRung.CodeTooSmall, true)
            state = outcome.state
            outcome.zoomRatio?.let { commands += it }
        }
        assertEquals("四档预算里只许发四发：", ZoomLadderRatios, commands)
        assertEquals(ZoomLadderRatios.size, state.stepIndex)
        assertFalse("还没到回滚判据：", state.rolledBack)
    }

    /** 顶档之后再连满 ZoomRollbackFrames 帧仍太小 ⇒ 回基线 + 本轮封口（"stopped helping" 的帧数定义） */
    @Test
    fun stalledLadderRollsBackOnceAndStaysRolledBack() {
        var state = ScanAssistState()
        fun run(frames: Int): List<Float?> =
            (1..frames).map {
                val outcome = advanceScanAssist(state, FrameCodeRung.CodeTooSmall, true)
                state = outcome.state
                outcome.zoomRatio
            }
        run((ZoomStepFrames * ZoomLadderRatios.size).toInt()) // 爬到顶
        val toTop = state.stepIndex
        val rollbackWindow = run(ZoomRollbackFrames.toInt())
        assertEquals("顶档预算内不该提前回滚：", listOf(ZoomBaselineRatio), rollbackWindow.filterNotNull())
        assertTrue("顶档站满 ${ZoomRollbackFrames} 帧仍太小，必须判回滚：", state.rolledBack)
        assertEquals(0, state.stepIndex)
        assertEquals(toTop, ZoomLadderRatios.size)
        // 回滚之后无论再喂多少太小帧，一发射不出（视场还给用户）
        val after = run((ZoomStepFrames * 3).toInt())
        assertTrue("回滚后本轮不再试缩放：", after.none { it != null })
    }

    /** 连击只认 CodeTooSmall：中间夹一帧别的（读到码/太大/没码）就重新数 */
    @Test
    fun anyOtherRungClearsTheTooSmallStreak() {
        for (interrupt in listOf(FrameCodeRung.CodeReadable, FrameCodeRung.CodeUndecodable, FrameCodeRung.NothingDetected)) {
            val (state, outcomes) = feed(ScanAssistState(), FrameCodeRung.CodeTooSmall, (ZoomStepFrames - 1).toInt())
            assertTrue("差一帧就已经发过命令了：", outcomes.none { it.zoomRatio != null })
            val afterInterrupt = advanceScanAssist(state, interrupt, true)
            assertEquals("$interrupt 没把连击清零：", 0L, afterInterrupt.state.tooSmallStreak)
            val resumed = feed(afterInterrupt.state, FrameCodeRung.CodeTooSmall, (ZoomStepFrames - 1).toInt())
            assertTrue("$interrupt 之后重新数还不够发命令：", resumed.second.none { it.zoomRatio != null })
            val final = advanceScanAssist(resumed.first, FrameCodeRung.CodeTooSmall, true)
            assertEquals("$interrupt 之后补满一窗没抬档：", ZoomLadderRatios.first(), final.zoomRatio)
        }
    }

    /** 这台没有缩放控制：一帧都不许多动视场，但提示档位照说（"太小、走近点"在无缩放设备上是实话） */
    @Test
    fun noZoomControlMeansNoCommandsButHintsStillSpeak() {
        val (state, outcomes) = feed(ScanAssistState(), FrameCodeRung.CodeTooSmall, 300, zoomAvailable = false)
        assertTrue("没有缩放控制的设备收到了命令：", outcomes.none { it.zoomRatio != null })
        assertEquals(0, state.stepIndex)
        assertFalse(state.rolledBack)
        assertEquals("提示档位不该被设备能力噎住：", FrameCodeRung.CodeTooSmall, state.shownRung)
    }

    /** 屏上档位的滞后：单帧噪声不许打得提示条闪 */
    @Test
    fun shownRungNeedsASettledWindowToChange() {
        var state = ScanAssistState()
        // 5 帧太小 + 5 帧没码，反复三遍：候选永远攒不满 12 帧窗口
        repeat(3) {
            feed(state, FrameCodeRung.CodeTooSmall, 5).let { state = it.first }
            feed(state, FrameCodeRung.NothingDetected, 5).let { state = it.first }
        }
        assertEquals("抖动把屏上档位翻面了：", FrameCodeRung.NothingDetected, state.shownRung)
        // 站稳：第 12 帧恰好翻一次，之后不重翻
        val outcomes = (1..20).map {
            val outcome = advanceScanAssist(state, FrameCodeRung.CodeTooSmall, true)
            state = outcome.state
            outcome
        }
        assertEquals("换挡只许在第 12 帧发生一次：", listOf(12), outcomes.mapIndexedNotNull { i, o -> if (o.shownRungChanged) i + 1 else null })
        // 回到没码那一档同样要攒满窗口
        val back = (1..20).map {
            val outcome = advanceScanAssist(state, FrameCodeRung.NothingDetected, true)
            state = outcome.state
            outcome
        }
        assertEquals("回落也该有滞后：", listOf(12), back.mapIndexedNotNull { i, o -> if (o.shownRungChanged) i + 1 else null })
        assertEquals(FrameCodeRung.NothingDetected, state.shownRung)
    }

    // ---- ⑤ T88①：一帧的符号怎么数 —— 空白原文永远不算"读到了" ----

    /**
     * 判档的表驱动：五类原文各自落在 readable 还是 candidates 那一侧。
     *
     * 这一张表就是本卡的全部修法（装机那一发：151.4 s / 1,389 帧，**每帧**一枚
     * `len=0 / bytes=0 / corners=4` 的符号、框短边 284..303 px）。改前调用点用
     * `rawValue != null` 数，于是第二行那一档被数成"读到了"，阶梯与兜底一起被按住。
     *
     * ⚠️ 空白口径**不许在这张表里另立**：这里断言的是"落在哪一侧"，而"什么叫空白"
     * 由 [decodingAdmission] 那一把尺子说（它自己的十二档表在 `ScanDecodingAdmissionTest`）。
     * 两张表叠在一起才是完整的判据：U+3000 与 U+00A0 必须同侧（Kotlin 的 `isBlank()` 把
     * U+00A0 算空白，`java.lang.Character.isWhitespace` 不算 —— 拿后者当尺子就会漏一档），
     * 而零宽空格 U+200B 与长度 1 的真文本必须留在 readable（那才是真码，杀掉它就是 T87
     * 注释里点名的"把真码一起杀掉"那条路）。
     */
    @Test
    fun blankPayloadIsNeverCountedAsAReadCode() {
        val realCode = "http://iclass.buaa.edu.cn:8081/app/course/stu_scan_sign.action" +
            "?courseSchedId=2488752&timestamp=1790247391994"
        val rows = listOf(
            // 原文 → readable / candidates（框固定给一枚 300 px 的，只问数到哪一侧）
            SymbolRow(null, 0, 1),                   // ML Kit 只回框不给原文：本就在候选那一侧
            SymbolRow("", 0, 1),                     // 装机实测那一档：空串绝不是"扫到了"
            SymbolRow(" ", 0, 1),                    // 半角空格
            SymbolRow("\n", 0, 1),                   // 换行
            SymbolRow("\t", 0, 1),                   // 制表
            SymbolRow("\u3000", 0, 1),                // U+3000 全角空格
            SymbolRow("\u00A0", 0, 1),                // U+00A0 不换行空格
            SymbolRow(" \u3000\u00A0\n\t", 0, 1),   // 混一版全空白
            SymbolRow("a", 1, 0),                     // 长度 1 的真文本：单字符 QR 就是这形状
            SymbolRow("\u200Bx", 1, 0),               // 零宽空格不算空白（与准入同一把尺子）
            SymbolRow(realCode, 1, 0),                // 真码原文
        )
        for (row in rows) {
            val counts = frameSymbolCounts(listOf(FrameSymbol(row.payload, 300)))
            assertEquals("${row.payload}: readable", row.readable, counts.readableCodeCount)
            assertEquals("${row.payload}: candidates", row.candidates, counts.candidateCodeCount)
            // 框只跟着**候选**数（readable 那一支本来就 `continue` 掉了）：判档要的是"候选里最大的框"
            val expectedEdge = if (row.candidates > 0) 300 else NoCandidateBoxShortEdgePx
            assertEquals("${row.payload}: 框的账（candidate 才带框）：", expectedEdge, counts.largestCandidateBoxShortEdgePx)
            // 反向钉：空白档绝不允许长出 CodeReadable 那一档（本卡要避免的那件事故）
            val rung = frameCodeRung(counts.readableCodeCount, counts.candidateCodeCount, counts.largestCandidateBoxShortEdgePx)
            if (row.readable == 0) {
                assertFalse("空白/无原文被数成了 CodeReadable：${row.payload}", rung == FrameCodeRung.CodeReadable)
            } else {
                assertEquals("有原文那一档仍然是 CodeReadable：${row.payload}", FrameCodeRung.CodeReadable, rung)
            }
        }
        // 混合帧：一枚真码 + 三枚空白 ⇒ 只数那一枚（readable 压倒 candidates，与 frameCodeRung 的次序同源）
        val mixed = frameSymbolCounts(
            listOf(FrameSymbol("", 400), FrameSymbol(realCode, 10), FrameSymbol("\u3000", 500), FrameSymbol(null, 600)),
        )
        assertEquals("混合帧的 readable 数多了（空白挤进了读到的那一侧）：", 1, mixed.readableCodeCount)
        assertEquals("混合帧的 candidates：", 3, mixed.candidateCodeCount)
        assertEquals("最大短边按候选算：", 600, mixed.largestCandidateBoxShortEdgePx)
        // 空列表 = 这一帧什么都没交回来：三颗数都必须落在"没看见"那一侧
        val empty = frameSymbolCounts(emptyList())
        assertEquals(0, empty.readableCodeCount)
        assertEquals(0, empty.candidateCodeCount)
        assertEquals("没框时必须递哨兵（frameCodeRung 靠它落 NothingDetected）：", NoCandidateBoxShortEdgePx, empty.largestCandidateBoxShortEdgePx)
        assertEquals(FrameCodeRung.NothingDetected, frameCodeRung(empty.readableCodeCount, empty.candidateCodeCount, empty.largestCandidateBoxShortEdgePx))
        // 框缺失（哨兵）与退化框（≤0）原样带上：不可信档是内核的既有判据，不许在数符号时就地折掉
        val noBox = frameSymbolCounts(listOf(FrameSymbol("", NoCandidateBoxShortEdgePx)))
        assertEquals(1, noBox.candidateCodeCount)
        assertEquals(NoCandidateBoxShortEdgePx, noBox.largestCandidateBoxShortEdgePx)
        assertEquals("框不可信却去抬视场：", FrameCodeRung.CodeUndecodable, frameCodeRung(noBox.readableCodeCount, noBox.candidateCodeCount, noBox.largestCandidateBoxShortEdgePx))
        val zeroBox = frameSymbolCounts(listOf(FrameSymbol("", 0), FrameSymbol(" ", -7)))
        assertEquals("退化框被折成了没框（短边取最大而不是取哨兵）：", 0, zeroBox.largestCandidateBoxShortEdgePx)
    }

    /**
     * 判档之后的两本账：阶梯还走不走、第二引擎的连击攒不攒 —— 装机那一窗口逐帧复算。
     *
     * 三行对照用的是**同一批符号**，只有"数法"不同（改前 `rawValue != null` / 改后走准入）：
     * - 改前：1,389 帧全 `CodeReadable` ⇒ `tooSmallStreak` 恒 0、`retryableStreak` 恒 0；
     * - 改后（短边 ≥291 那 79.3%）：`CodeUndecodable` ⇒ 不抬视场（焦点的事，抬了白抬），
     *   但可重试连击每帧 +1 ⇒ 第二引擎终于有触发量；
     * - 改后（短边 <291 那 20.7%）：`CodeTooSmall` ⇒ 阶梯按 [ZoomStepFrames] 帧一档往上走。
     *
     * 第四行是本卡的红线：这台没有缩放控制时（装机实测 `zoomCtl=false`）连击都不计，
     * 一发射不出 —— 那条设备事实与空白原文无关，改前改后一样，本卡不假装修了它。
     */
    @Test
    fun blankFramesReleaseTheLadderAndFeedTheFallbackStreak() {
        val blank = listOf(FrameSymbol("", 296))
        val counts = frameSymbolCounts(blank)
        val undecodable = frameCodeRung(counts.readableCodeCount, counts.candidateCodeCount, counts.largestCandidateBoxShortEdgePx)
        assertEquals("装机那一档（短边 296）改后该落在：", FrameCodeRung.CodeUndecodable, undecodable)
        val tooSmall = frameSymbolCounts(listOf(FrameSymbol("", 290)))
        assertEquals(
            "短边 290（差一格到 291 阈值）：",
            FrameCodeRung.CodeTooSmall,
            frameCodeRung(tooSmall.readableCodeCount, tooSmall.candidateCodeCount, tooSmall.largestCandidateBoxShortEdgePx),
        )

        // 改前的数法：同一枚空白原文按 `rawValue != null` 算"读到了" ⇒ CodeReadable
        var before = ScanAssistState()
        // 改后：走准入 ⇒ 空白落 candidates
        var after = ScanAssistState()
        var afterTooSmall = ScanAssistState()
        repeat((ZoomStepFrames * 2).toInt()) {
            before = advanceScanAssist(before, FrameCodeRung.CodeReadable, true).state
            after = advanceScanAssist(after, undecodable, true).state
            afterTooSmall = advanceScanAssist(afterTooSmall, FrameCodeRung.CodeTooSmall, true).state
        }
        assertEquals("改前那种数法把可重试连击压死了（兜底永远叫不醒）：", 0L, before.retryableStreak)
        assertEquals("改前那种数法同时按住了缩放连击：", 0L, before.tooSmallStreak)
        assertEquals("改后可重试连击必须逐帧攒：", ZoomStepFrames * 2, after.retryableStreak)
        assertEquals("CodeUndecodable 不该抬视场（焦点的事，缩放帮不上）：", 0L, after.tooSmallStreak)
        assertEquals("改前误判成 CodeReadable，屏上档位自然也不会翻面：", FrameCodeRung.CodeReadable, before.shownRung)
        assertEquals("改后屏上档位走到 CodeUndecodable（滞后窗 [RungSettleFrames] 帧之后）：", FrameCodeRung.CodeUndecodable, after.shownRung)
        assertEquals("CodeTooSmall 那一侧连满两窗就该抬两档：", 2, afterTooSmall.stepIndex)
        assertEquals("抬过档之后缩放连击清零、可重试连击照旧往上攒：", ZoomStepFrames * 2, afterTooSmall.retryableStreak)
        assertEquals("发过命令的那一帧缩放连击清零（下一档从这一步之后重数）：", 0L, afterTooSmall.tooSmallStreak)

        // 这台没有缩放控制（装机实测 zoomCtl=false）：命令一发射不出，可重试连击照旧攒
        var noZoom = ScanAssistState()
        repeat((ZoomStepFrames * 2).toInt()) {
            noZoom = advanceScanAssist(noZoom, FrameCodeRung.CodeTooSmall, false).state
        }
        assertEquals("没有缩放控制的设备上不该有任何档位：", 0, noZoom.stepIndex)
        assertEquals("可重试连击不看缩放能力（两本账分家，兜底反而是唯一还能出声的手段）：", ZoomStepFrames * 2, noZoom.retryableStreak)
    }

    /** T88① 的判据本体：`advanceScanAssist` 里不许有第三本账 —— 空白档不推进判死 */
    @Test
    fun noRungWhatsoeverFeedsTheGiveUpRatchet() {
        // 判据侧的结构事实：四档全喂一遍，帧观测内核只返回 ScanAssistOutcome（没有健康度那颗）
        for (rung in FrameCodeRung.entries) {
            val outcome = advanceScanAssist(ScanAssistState(), rung, true)
            assertNotNull("档位 $rung 没给出下一状态：", outcome.state)
            assertTrue("档位 $rung 的取值域漂了：", FrameCodeRung.entries.contains(outcome.state.candidateRung))
            assertFalse("可重试连击出现了负数（判据被谁改写了？）：", outcome.state.retryableStreak < 0L)
        }
        // 空白原文那一档既不算失败也不算加分：四档里没有一档能改动 ConsecutiveDecodeFailureLimit 那本账
        assertEquals("判死的阈值不在恢复内核里（本卡一行都没接）：", 3, ConsecutiveDecodeFailureLimit)
    }

    private data class SymbolRow(val payload: String?, val readable: Int, val candidates: Int)

    private data class RungRow(
        val readable: Int,
        val candidates: Int,
        val edge: Int,
        val expected: FrameCodeRung,
    )

    private data class ZoomRow(val requested: Float, val min: Float?, val max: Float?, val expected: Float?)
}
