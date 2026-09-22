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

    private data class RungRow(
        val readable: Int,
        val candidates: Int,
        val edge: Int,
        val expected: FrameCodeRung,
    )

    private data class ZoomRow(val requested: Float, val min: Float?, val max: Float?, val expected: Float?)
}
