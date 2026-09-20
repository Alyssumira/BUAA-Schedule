package com.buaa.schedule.widget

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 玻璃背景那层 tint 的纯算术层：tint x 透明度 -> 最终 ARGB。
 *
 * 钉住的缺陷（装机实测，组件 id=8）：开了「玻璃感壁纸背景」后透明度滑杆完全无效 ——
 * `w8.alpha` 从 80 拨到 0，桌面 tile 内部像素一动不动，始终是底板色 (22,32,58)。
 * 根因是绘制侧用 `PorterDuffColorFilter(tint, SRC_OVER)` 叠色：这种滤波器的输出
 * **替换**被画像素的颜色（含 alpha），tint 自身 alpha=255 时复合结果恒不透明，
 * `paint.alpha` 只是喂进滤波器的 src alpha，被结果整个顶掉 —— 滑杆值根本没进颜色。
 *
 * 为什么测的是抽出来的 [glassTintArgb] 而不是 `render()` 本身：本模块没有 Robolectric，
 * `Canvas` / `Log` 在 JVM 里全是抛 "not mocked" 的桩，真画图的那段测不到，
 * 能测、也只有该测的就是「alpha 进没进最终颜色」这条位运算（抽取理由与
 * `WidgetGlassSource` 同一套）。
 */
class WidgetBackgroundRendererTest {

    /** 装机实测样本：w8.background = -15327174 = 0xFF16203A（深海军蓝底板） */
    private val tint = 0xFF16203A.toInt()

    /** 自选壁纸是一张纯白 PNG（1080x2400），糊完还是纯白，所以底色按纯白算 */
    private val whiteWallpaper = 0x00FFFFFF

    /**
     * SRC_OVER 的参考实现：把 [glassTintArgb] 折出来的 ARGB 当地面色，逐通道
     * （fg*a + bg*(255-a) + 128) / 255 合成到基色上。
     *
     * 这段不参与生产 —— 真合成是 drawRect 走默认 SRC_OVER 由光栅管线做的。
     * 它在这里只为把期望像素算出来：被钉的算式是 alpha **进了前景色高字节** 这件事。
     */
    private fun srcOver(argb: Int, baseRgb: Int): Int {
        val a = argb ushr 24
        fun channel(shift: Int): Int =
            (((argb ushr shift) and 0xFF) * a + ((baseRgb ushr shift) and 0xFF) * (255 - a) + 128) / 255
        return (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    @Test
    fun alphaEightyOverWhiteWallpaperCompositesToTheExpectedPixel() {
        val layer = glassTintArgb(tint, 80)
        // 滑杆的 80% 必须落到高字节：80*255/100 = 204，差一个单位这层都盖不死"滑杆无效"
        assertEquals("alpha=80% 没折进颜色，这层又是永远不透明", 204, layer ushr 24)
        // 0.8*(22,32,58) + 0.2*(255,255,255) = (68.6,76.6,97.4) -> (69,77,97) = 0x454D61。
        // 改前实测：这一格显示的是 (22,32,58)，与 alpha=0 那格一模一样。
        assertEquals("alpha=80 + 纯白壁纸该显示 (69,77,97)", 0x454D61, srcOver(layer, whiteWallpaper))
    }

    @Test
    fun alphaZeroLeavesTheBlurredWallpaperUntouched() {
        val layer = glassTintArgb(tint, 0)
        assertEquals("alpha=0 时这层必须完全退出复合", 0, layer ushr 24)
        // 期望：看到糊过的壁纸本身（这张壁纸是纯白），而不是 (22,32,58) 那块纯色板
        assertEquals(whiteWallpaper, srcOver(layer, whiteWallpaper))
    }

    @Test
    fun alphaHundredIsTheOpaqueTint() {
        val layer = glassTintArgb(tint, 100)
        assertEquals("alpha=100% -> 完全不透明", 255, layer ushr 24)
        assertEquals("alpha=100 时壁纸被整层盖住，显示的就是底板原色", 0x16203A, srcOver(layer, whiteWallpaper))
    }

    @Test
    fun tintRgbSurvivesWhateverAlphaTheSliderSays() {
        // 三格共用的 RGB 都来自同一枚 0xFF16203A：折 alpha 只许动高字节
        for (alphaPercent in listOf(0, 42, 80, 100)) {
            assertEquals(
                "alpha=$alphaPercent 时底色被改掉了，滑杆调的应该是浓度不是色相",
                tint and 0xFFFFFF,
                glassTintArgb(tint, alphaPercent) and 0xFFFFFF,
            )
        }
    }

    @Test
    fun outOfRangeAlphaIsClampedInsteadOfWrappingAround() {
        // 越界若不夹住，`a shl 24` 会溢出到符号位外面把整份 ARGB 弄脏；
        // 口径与 alphaFraction（coerceIn(0, 100)）以及纯色那条分支保持一致。
        assertEquals("负数按 0 处理", glassTintArgb(tint, 0), glassTintArgb(tint, -20))
        assertEquals("超过 100 按全不透明处理", glassTintArgb(tint, 100), glassTintArgb(tint, 150))
    }
}
