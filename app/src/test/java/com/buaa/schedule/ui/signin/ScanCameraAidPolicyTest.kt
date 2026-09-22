package com.buaa.schedule.ui.signin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T64 判据内核的表驱动单测：交付帧够不够、点击→测光点映射、手电档位、缩放钳制。
 *
 * 全文件零 android 依赖 —— 这正是将判据抽成 [ScanCameraAidPolicy] 的全部理由：
 * 640×480 到底够不够、点在 FILL_CENTER 裁切后的哪儿，都必须能在 JVM 里逐支打表，
 * 而不是只能在真机上"扫一枪看看"。
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

    // ---- ③ 手电档位 ----

    @Test
    fun torchAffordanceTable() {
        val cases = listOf(
            // hasFlash, state, live, givenUp → expected
            Quad(false, TorchStateOff, true, false, TorchAffordance.HiddenNoFlash),
            Quad(false, TorchStateOn, false, true, TorchAffordance.HiddenNoFlash),   // 没灯排最前
            Quad(true, TorchStateOff, true, true, TorchAffordance.HiddenDecoderDead), // 判死压过"还活着"
            Quad(true, TorchStateOn, false, false, TorchAffordance.HiddenCameraNotLive),
            Quad(true, TorchStateUndefined, false, true, TorchAffordance.HiddenDecoderDead),
            Quad(true, TorchStateOff, true, false, TorchAffordance.ShowTurnOn),
            Quad(true, TorchStateUndefined, true, false, TorchAffordance.ShowTurnOn), // 状态未知按"开"起步
            Quad(true, TorchStateOn, true, false, TorchAffordance.ShowTurnOff),
        )
        for ((hasFlash, state, live, givenUp, expected) in cases) {
            assertEquals(
                "hasFlash=$hasFlash state=$state live=$live givenUp=$givenUp：",
                expected,
                torchAffordance(hasFlash, state, live, givenUp),
            )
        }
    }

    @Test
    fun hiddenTorchAffordancesCarryNoLabelAndShownOnesDo() {
        for (hidden in listOf(TorchAffordance.HiddenNoFlash, TorchAffordance.HiddenDecoderDead, TorchAffordance.HiddenCameraNotLive)) {
            assertFalse("$hidden 不许画按钮", hidden.show)
            assertEquals("$hidden 不许带文案", "", hidden.label)
        }
        assertTrue(TorchAffordance.ShowTurnOn.show)
        assertTrue(TorchAffordance.ShowTurnOff.show)
        assertTrue("开关两颗的话不许一样：", TorchAffordance.ShowTurnOn.label != TorchAffordance.ShowTurnOff.label)
        // 按下去的目标态：关着的那颗按下去开、开着的那颗按下去关
        assertTrue(torchTargetState(TorchAffordance.ShowTurnOn))
        assertFalse(torchTargetState(TorchAffordance.ShowTurnOff))
    }

    // ---- ④ 缩放钳制 ----

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

    private data class Quad<A, B, C, D, E>(val a: A, val b: B, val c: C, val d: D, val e: E)
    private data class ZoomRow(val requested: Float, val min: Float?, val max: Float?, val expected: Float?)
}
