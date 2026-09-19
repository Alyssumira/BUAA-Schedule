package com.buaa.schedule.core.designsystem

import androidx.compose.ui.graphics.Color
import com.buaa.schedule.widget.WidgetAppearance
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [compositeLuma] 与**逐通道混合**的交叉核对。
 *
 * ## 为什么这条对照值得钉
 *
 * 平台画一块半透明 tint 是这样算的：把 tint 的每个 8 bit 通道与底下那片像素的同一通道
 * 按 alpha 线性插值，再作为 sRGB 值写进表面。也就是说"混合"发生在**编码通道**那一维。
 * [compositeLuma] 只有亮度、没有颜色，于是它必须先把亮度折回编码那一维、插值、再解回来——
 * 这是"亮度代理"（把一块色当成与它等亮度的中性灰）而非第三种混合模型。
 *
 * 代理何时是**精确**的：中性灰。灰的三条通道相同，亮度权重 0.2126+0.7152+0.0722 = 1，
 * 于是"灰的亮度"与"灰的编码通道值"互为解析反函数，代理与逐通道的差恒为 0
 * （[theLumaProxyIsExactlyTheChannelMixForNeutralGrays] 按 1e-4 钉它，实测差在浮点最后一位）。
 * 场景是纯色壁纸/中性玻璃板时走的就是这条路。
 *
 * 代理何时开始偏：tint 有彩度时。等亮度的一条彩色与一条灰，编码通道均值并不相同
 * （[readableLuminance] 是逐通道解码后再加权，编码域里不存在线性泛函），
 * 偏差随彩度增大。[theProxyStaysInsideOneLumaStepOfTheChannelMixOnTheRealPalette]
 * 把这门色板（[CourseColors] 八支 + 深浅两支玻璃板）上的实测最大偏差钉成一个界，
 * [theTwoModelsOnlyDisagreeWhereBothInksAlreadyFailAA] 再把它翻译成判据语言：
 * **两个模型会在选墨上分歧的那些格，黑白两支本来都不到 AA**（落在
 * [contentOnLuma] 文档里那条 0.183~0.225 无解带里），所以代理误差从来不会把一块
 * 本来读得清的板判成读不清、也不会反过来。真出现那种格就该改模型，
 * 该报告而不是改这条断言。
 *
 * 逐通道那一份口径不是这里新立的：桌面小组件早就这么算（
 * [WidgetAppearance.autoShouldUseDarkText] 把背景色与背板逐通道插值后才量亮度），
 * 所以 [theWidgetChainStillDecidesTheSameInkAsTheDesignSystem] 直接拿那个未改动的生产函数对赌选墨结果。
 */
class CompositeLumaChannelCrossCheckTest {

    /**
     * 中性灰 × 中性灰：亮度代理必须与逐通道混合**精确相等**。
     *
     * 这条是 [compositeLuma] 换维度的定义性检查——旧的"线性光"口径在这一条上差到 0.23
     * （深色玻璃板以 0.60 叠在纯白上：模型 0.4049 / 真实 0.1717），所以它改前必红。
     *
     * 网格取 20 的倍数（0..240）而不是 8：`Color` 把每条通道量化到 8 bit（`0.5f` 实存
     * 128/255），混合值落在两个整数通道之间时逐通道那一份会被取整，1e-4 就全花在取整噪声上、
     * 比的不再是同一个量。20 的倍数配上 [NEUTRAL_ALPHAS] 那六档（每档乘 20 都是整数）
     * 让 `alpha*tint + (1-alpha)*scene` 恒好落在 8 bit 网格上，两边比的是同一个像素。
     */
    @Test
    fun theLumaProxyIsExactlyTheChannelMixForNeutralGrays() {
        var checked = 0
        for (tintStep in 0..12) for (sceneStep in 0..12) for (alpha in NEUTRAL_ALPHAS) {
            val tint = gray(tintStep * 20)
            val scene = gray(sceneStep * 20)
            assertEquals(
                "灰 ${tintStep * 20} 以 $alpha 叠在灰 ${sceneStep * 20} 上：代理 ${proxy(tint, scene, alpha)} " +
                    "≠ 逐通道 ${channelMix(tint, scene, alpha)}",
                channelMix(tint, scene, alpha), proxy(tint, scene, alpha), EXACT_TOLERANCE,
            )
            checked++
        }
        assertEquals("参数化退化：灰格网格应为 13×13×6", 13 * 13 * NEUTRAL_ALPHAS.size, checked)
    }

    /**
     * 真实色板（[CourseColors] 八支 + [DarkGlassTint] / [LightGlassTint]）叠在中性灰场景上：
     * 报出代理与逐通道的最大偏差，并钉住它在一个"够不着选墨判据"的界内。
     *
     * 界是**先跑出数再写**的：全 33 档灰场景（`step * 8`，最后一档 256 被 `Color` 夹成纯白）
     * × a ∈ {0.2, 0.5, 0.6, 0.9} 实测最大 0.0393
     * （青色 #00B8D4 以 0.6 叠在纯白上，代理 0.5949 / 逐通道 0.5557），
     * 断言界 0.045 只留一档彩度余量——把课程色再往饱和里推、或有人偷偷改指数，这里先炸。
     * 绝对亮度 0.045 的量级意义：它换不来任何一次选墨翻转，见下一条。
     */
    @Test
    fun theProxyStaysInsideOneLumaStepOfTheChannelMixOnTheRealPalette() {
        var worst = 0f
        var where = ""
        var checked = 0
        for (tint in CourseColors + listOf(DarkGlassTint, LightGlassTint)) {
            for (sceneStep in 0..32) {
                val scene = gray(sceneStep * 8)
                for (alpha in PALETTE_ALPHAS) {
                    val dev = abs(channelMix(tint, scene, alpha) - proxy(tint, scene, alpha))
                    if (dev > worst) {
                        worst = dev
                        where = "tint=${tint.describe()} 场景=灰${sceneStep * 8} alpha=$alpha " +
                            "代理=${proxy(tint, scene, alpha)} 逐通道=${channelMix(tint, scene, alpha)}"
                    }
                    checked++
                }
            }
        }
        assertTrue(
            "真实色板上的代理误差长到了 $worst（$where）——偏差已经能挪动选墨判据，该重新设计模型而不是放宽这条",
            worst <= PALETTE_DEVIATION_BOUND,
        )
        assertEquals("参数化退化：色板扫描应为 10×33×4", 10 * 33 * PALETTE_ALPHAS.size, checked)
    }

    /**
     * 把 [theProxyStaysInsideOneLumaStepOfTheChannelMixOnTheRealPalette] 量到的误差翻译成判据语言——
     * 要守的是那句"不许把该选白墨的格判成黑墨"：
     * 两个模型**选墨不一致**的那些格，白墨与近黑墨在两边的读数都不到 AA。
     *
     * 换句话说：代理的误差只可能落在 [contentOnLuma] 文档里那条黑白都读不清的中间带，
     * 那里没有可读性信息、只有观感。一旦哪天出现"一边达标一边不达标"的分歧格，
     * 就说明亮度代理真的把某格判反了——那时该动的是模型，不是这条断言。
     */
    @Test
    fun theTwoModelsOnlyDisagreeWhereBothInksAlreadyFailAA() {
        var checked = 0
        var disagreements = 0
        val offenders = mutableListOf<String>()
        val darkLuma = ContentDark.readableLuminance()
        val lightLuma = ContentLight.readableLuminance()
        for (tint in CourseColors + listOf(DarkGlassTint, LightGlassTint)) {
            for (sceneStep in 0..32) {
                val scene = gray(sceneStep * 8)
                for (alpha in PALETTE_ALPHAS + listOf(0.08f, 0.3f, 0.72f, 0.96f, 1f)) {
                    val proxyLuma = proxy(tint, scene, alpha)
                    val realLuma = channelMix(tint, scene, alpha)
                    val proxyInk = contentOnLuma(proxyLuma)
                    val realInk = contentOnLuma(realLuma)
                    val readableAnywhere = maxOf(
                        contrastRatio(proxyLuma, lightLuma), contrastRatio(proxyLuma, darkLuma),
                        contrastRatio(realLuma, lightLuma), contrastRatio(realLuma, darkLuma),
                    )
                    if (proxyInk != realInk) {
                        disagreements++
                        if (readableAnywhere >= DesignTokens.WCAG_AA_RATIO) {
                            offenders += "tint=${tint.describe()} 场景=灰${sceneStep * 8} α=$alpha → " +
                                "代理 $proxyLuma 判 $proxyInk / 逐通道 $realLuma 判 $realInk，" +
                                "而这四格里最好的一支已有 $readableAnywhere:1"
                        }
                    }
                    checked++
                }
            }
        }
        assertTrue("分歧格一个都没有：这条断言是空的，代理与逐通道已经同构到不需要对照了", disagreements > 0)
        assertTrue("代理把有解的格判反了：\n${offenders.joinToString("\n")}", offenders.isEmpty())
        assertEquals("参数化退化：判据扫描应为 10×33×9", 10 * 33 * (PALETTE_ALPHAS.size + 5), checked)
    }

    /**
     * 与**未改动的**小组件链路对赌：`WidgetAppearance.autoShouldUseDarkText` 本来就是
     * 逐通道混合之后再量亮度（设计系统换维度后走的正是同一条口径），
     * 所以它算该用深字时，设计系统这边在中性灰底板上也不许判给白墨。
     *
     * 背板取 [gray] 127：那条链路写的是 `0.5f`，却把混好的通道值按 `(c * 255f).toInt()`
     * **截断**回 8 bit，于是真正参与算亮度的是 127/255 而不是 128/255。
     *
     * 只比"离黑白交点足够远"的格：同一个 1/255 的量化噪声（截断 vs 四舍五入）
     * 在 0.203 交点附近本来就翻得动判据，翻它的不是混合维度。
     */
    @Test
    fun theWidgetChainStillDecidesTheSameInkAsTheDesignSystem() {
        val widgetBackdrop = gray(127)
        // 对照的另一边也得站着：alpha=0 时小组件只剩自己的背板，它判"用深字"这件事
        // 说的就是"那块中灰仍在黑白交点之上一点"。靶子哪天漂到交点以下，这里先红。
        assertTrue(
            "小组件的背板不再是'略偏亮'的中灰，这条对照比的已经不是同一块板",
            WidgetAppearance.autoShouldUseDarkText(argbOf(widgetBackdrop), 0),
        )
        var compared = 0
        var skippedNearCrossing = 0
        for (step in 0..32) {
            val tint = gray(step * 8)
            for (percent in listOf(5, 10, 20, 30, 45, 50, 60, 72, 82, 90, 100)) {
                val alpha = percent / 100f
                val plate = proxy(tint, widgetBackdrop, alpha)
                if (abs(plate - CROSSING_LUMA) < 0.02f) {
                    skippedNearCrossing++
                    continue
                }
                assertEquals(
                    "灰 ${step * 8} 以 $percent% 叠在中灰背板上：小组件判深字、设计系统判白墨（板 $plate）",
                    WidgetAppearance.autoShouldUseDarkText(argbOf(tint), percent),
                    contentOnLuma(plate) != Color.White,
                )
                compared++
            }
        }
        assertTrue("一格都没比成：这条对照是空的", compared > 250)
        assertTrue("量化豁免格数为 0，说明交点判据没被真正碰过", skippedNearCrossing > 0)
    }

    // ---- 三种算法 ----------------------------------------------------------

    /** 设计系统的亮度代理：把 tint 与场景各折成一个亮度，交给 [compositeLuma]。 */
    private fun proxy(tint: Color, scene: Color, alpha: Float): Float =
        compositeLuma(tint.readableLuminance(), scene.readableLuminance(), alpha)

    /**
     * 平台真正做的事（也是 [WidgetAppearance.autoShouldUseDarkText] 的算法）：
     * 逐通道按 alpha 插值，再把结果颜色折成相对亮度。
     */
    private fun channelMix(tint: Color, scene: Color, alpha: Float): Float =
        Color(
            red = alpha * tint.red + (1f - alpha) * scene.red,
            green = alpha * tint.green + (1f - alpha) * scene.green,
            blue = alpha * tint.blue + (1f - alpha) * scene.blue,
        ).readableLuminance()

    private fun gray(channel: Int): Color = Color(channel / 255f, channel / 255f, channel / 255f)

    /**
     * 四舍五入而不是截断：这条链路自己就把通道量化到 8 bit，
     * 若再让截断噪声把"灰 n"喂成"灰 n-1"，被翻动的判据就不是混合维度了。
     */
    private fun argbOf(color: Color): Int {
        fun byte(channel: Float): Int = (channel * 255f).roundToInt()
        return (0xFF shl 24) or (byte(color.red) shl 16) or (byte(color.green) shl 8) or byte(color.blue)
    }

    /** 失败消息里的一块色卡读数：四条归一化通道（**不是**十六进制，真要 hex 见 `SemanticGlassPlateTest`）。 */
    private fun Color.describe(): String = "α=%f r=%f g=%f b=%f".format(alpha, red, green, blue)

    private companion object {
        /** 中性灰上没有代理误差：这条按"精确相等"钉，只留浮点最后一位的余量 */
        const val EXACT_TOLERANCE = 0.0001f

        /** 真实色板扫描的最大允许偏差：实测 0.0393，界留一档彩度余量 */
        const val PALETTE_DEVIATION_BOUND = 0.045f

        /** 黑/白等对比度交点（[contentOnLuma] 的判据转折点），用于挑出量化噪声翻得动的格 */
        const val CROSSING_LUMA = 0.203f

        val NEUTRAL_ALPHAS = listOf(0.05f, 0.2f, 0.35f, 0.5f, 0.6f, 0.9f)

        /** 色板扫描用的四档：生产里真会落到的薄涂（0.2）、中档（0.5/0.6）到接近不透明（0.9） */
        val PALETTE_ALPHAS = listOf(0.2f, 0.5f, 0.6f, 0.9f)
    }
}
