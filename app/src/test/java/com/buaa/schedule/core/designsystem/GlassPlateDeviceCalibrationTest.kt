package com.buaa.schedule.core.designsystem

import androidx.compose.ui.graphics.Color
import com.buaa.schedule.core.designsystem.liquid.BOTTOM_BAR_SURFACE_ALPHA_CEILING
import com.buaa.schedule.core.designsystem.liquid.bottomBarInk
import com.buaa.schedule.core.designsystem.liquid.bottomBarSceneLuma
import com.buaa.schedule.core.designsystem.liquid.bottomBarSurfaceAlpha
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 底栏玻璃板的**实测校准表**：模型算出来的板亮度，必须与真机上画出来的那片中位像素对上。
 *
 * ## 数据来源（不是拟合，是量出来的）
 *
 * buaa36 AVD（1080×2400，Android 36），把纯色 PNG 推成系统壁纸，逐档换壁纸后
 * 截图取**底栏胶囊内底板**的中位像素（避开描边、高光与文字），再按 WCAG 曲线把那个像素
 * 折成相对亮度。深色档 5 行 + 浅色档 3 行，全部是同一台机器、同一个默认透明度滑条
 * （cardAlpha 0.88 → [DesignTokens.cardAlphaScale] = 1.0）下量的。
 * 浅色档只有 v=0 那一行画的是"下限解出来的 alpha"（0.505），另两行停在 0.20 基准档；
 * 深色档被 0.60 天花板钉住在 v≥160 的三行上，v=0/96 两行当时画的是各自的原始档
 * （0.20 / 旧下限解出的 0.272）——所以 alpha 是**每行自己的输入条件**，逐行记在表里，
 * 而不是一个共用的常数。
 *
 * ## 这张表为什么值得进仓
 *
 * 平台（Skia 在 sRGB 表面上的 `drawRect(color.copy(alpha = …))`）混合的是**编码通道值**，
 * 而这条模型过去混的是**线性相对亮度**。sRGB 的解码曲线是凸函数，于是凸函数上的线性插值
 * 永远 ≥ 真实渲染结果（Jensen）——差多少不靠辩论，这张表就是尺子：
 * 深色档 × 纯白壁纸那一行，旧模型给 0.4049，像素是 0.1717，**差 2.36 倍**。
 * 断言用 ±0.03 绝对亮度这道闸门（宽松地包住壁纸采样与中位取整的误差；混合维度搬对之后
 * 八行的模型-像素偏差最大 0.0008（浅色 × 纯黑那一行），闸门宽度只用来吸收浮点与采样噪声，
 * 不给口径错误留位置）。
 *
 * @see com.buaa.schedule.core.designsystem.compositeLuma 被校准的那条式子
 * @see CompositeLumaChannelCrossCheckTest 同一件事的算法侧交叉核对（逐通道混合）
 */
class GlassPlateDeviceCalibrationTest {

    @After
    fun restoreScene() {
        SceneLuma.wallpaper = SceneLuma.Stats.Unknown
    }

    // ---- 模型必须落在平台真正混合的那一维 --------------------------------------

    /**
     * 八行实测逐行对上生产链路算出的板亮度。
     *
     * alpha 取该行**当时真正画下去的那一档**（表里逐行记），场景亮度走生产的
     * [bottomBarSceneLuma]（即下限与墨色共用的那一份读法），底板色走真实成员
     * [DarkGlassTint] / [LightGlassTint] 的 [readableLuminance]。
     * 三个输入都是生产口径，唯一"来自测试"的就是那档 alpha——它本身也是量出来/当时策略
     * 决定的事实，不是拟合参数。
     */
    @Test
    fun everyMeasuredRowLandsOnTheRenderedPlate() {
        for (row in deviceRows) {
            SceneLuma.wallpaper = row.stats
            val alpha = row.renderedAlpha ?: barAlpha(row)
            val plate = compositeLuma(row.tint.readableLuminance(), sceneLumaOf(row), alpha)
            assertEquals(
                "${row.label}：alpha=$alpha 场景=${row.sceneLuma} → 模型算出的板 $plate，实测 ${row.measuredPlate}" +
                    "（旧线性口径在这一档会给 ${row.tint.readableLuminance() * alpha + row.sceneLuma * (1f - alpha)}）",
                row.measuredPlate, plate, MEASURED_PLATE_TOLERANCE,
            )
        }
    }

    /**
     * 同一张表再过一遍**完整生产链路**（alpha 也由 [bottomBarSurfaceAlpha] 现解）：
     * 只有"下限这一维跟着一起搬对了"的格子才敢这么跑。
     *
     * 准入门槛是一条可核对的事实，不是手写名单：**链路现解的 alpha 仍等于当初画下去的那档**
     * （±[ALPHA_POLICY_EPSILON]）。这条判据同时反过来说 [DeviceRow.chainMoved] 必须是真值——
     * 名单与实际解算一旦脱节，这里当场报"标记过期"，免得下一次改动把某行悄悄塞进/塞出守卫。
     * 画下去的那档与链路值不同的那些行，比的已经不是同一块板，校准交给
     * [everyMeasuredRowLandsOnTheRenderedPlate]。
     *
     * 浅色 × 纯黑壁纸那一行是这类里最典型的一格：下限与天花板互相顶住——旧下限按线性口径
     * 自以为把板抬到 AA 线（0.456）而像素只有 0.198；混合维度搬对之后下限要的 alpha 越过
     * 0.60 天花板，板被合法地抬到 0.29 一档。它的实测值校准的是混合维度（见
     * [everyMeasuredRowLandsOnTheRenderedPlate]），
     * 这里改用"现在的板真的读得清了"作结（见 [theRowTheOldFloorFlatteredNowReallyClearsAA]）。
     */
    @Test
    fun theLiveChainReproducesEveryRowWhoseAlphaPolicyDidNotMove() {
        assertTrue("校准表被改窄到 5 行以下（${deviceRows.size}）：这张表不再是一次壁纸扫描", deviceRows.size >= 5)
        var checked = 0
        var atCeiling = 0
        var atBaseRawAlpha = 0
        val staleFlags = mutableListOf<String>()
        for (row in deviceRows) {
            SceneLuma.wallpaper = row.stats
            val alpha = barAlpha(row)
            // renderedAlpha = null 表示"画下去的就是链路现解的那一档"，按定义没动
            val moved = row.renderedAlpha?.let { abs(it - alpha) > ALPHA_POLICY_EPSILON } == true
            if (moved != row.chainMoved) {
                staleFlags += "${row.label}：chainMoved=${row.chainMoved}，但链路现解 $alpha vs 画下去的 " +
                    "${row.renderedAlpha}——准入判据与名单脱钩了"
            }
            if (moved) continue
            val plate = compositeLuma(row.tint.readableLuminance(), sceneLumaOf(row), alpha)
            assertEquals(
                "${row.label}：生产链路（alpha=$alpha）算出的板 $plate 与实测 ${row.measuredPlate} 脱钩了",
                row.measuredPlate, plate, MEASURED_PLATE_TOLERANCE,
            )
            assertTrue("${row.label}：链路 alpha 越过天花板", alpha <= BOTTOM_BAR_SURFACE_ALPHA_CEILING + 1e-6f)
            if (alpha >= BOTTOM_BAR_SURFACE_ALPHA_CEILING - 1e-6f) atCeiling++
            if (abs(alpha - DesignTokens.CHROME_SURFACE_ALPHA) <= ALPHA_POLICY_EPSILON) atBaseRawAlpha++
            checked++
        }
        assertTrue("chainMoved 名单已过期：\n${staleFlags.joinToString("\n")}", staleFlags.isEmpty())
        assertEquals(
            "校准表被改窄了：能跑完整链路的行数应等于表里没搬动 alpha 策略的行数",
            deviceRows.count { !it.chainMoved }, checked,
        )
        // 三条防空转的守卫：链路子集不许塌成单一档位或单一主题，否则上面那条对表
        // 就只在替一种夹取口径背书（"下限顶住天花板"与"下限落回基准档"是两条不同的路）。
        assertTrue("链路子集里深色档零行（$checked 行全在浅色）：暗板那一支失去覆盖",
            deviceRows.filterNot { it.chainMoved }.any { it.darkTheme })
        assertTrue("链路子集里浅色档零行（$checked 行全在深色）：亮板那一支失去覆盖",
            deviceRows.filterNot { it.chainMoved }.any { !it.darkTheme })
        assertTrue("链路子集没有一行停在天花板（$atCeiling）：越顶收敛这条口径没人守", atCeiling > 0)
        assertTrue(
            "链路子集没有一行停在基准档 ${DesignTokens.CHROME_SURFACE_ALPHA}（$atBaseRawAlpha）：" +
                "下限不挡事的常态没人守",
            atBaseRawAlpha > 0,
        )
    }

    /**
     * 改前那条"自我恭维"留下的那格账：浅色主题 + 纯黑壁纸。
     *
     * 旧下限按线性口径解出 0.5074，并自称板已经抬到 AA 线（0.4585 → 品牌墨恰好 4.5:1），
     * 而画出来的像素只有 0.198，品牌墨在真板上是 2.19:1。混合维度搬对之后同一格的下限要
     * 0.74，越过 0.60 天花板 → 栏体走满天花板，板 0.29，品牌墨 2.96:1 仍然读不清，
     * 于是 [bottomBarInk] 把这一格退给近黑（5.47:1）。alpha 与墨色两处一起动，
     * 才是这一格该有的样子——所以它不进 [theLiveChainReproducesEveryRowWhoseAlphaPolicyDidNotMove] 的完整链路对表。
     */
    @Test
    fun theRowTheOldFloorFlatteredNowReallyClearsAA() {
        val row = deviceRows.first { !it.darkTheme && it.wallpaper == 0 }
        assertEquals("靶子换行了：这一格本该是浅色 × 纯黑壁纸", 0, row.wallpaper)
        assertTrue("这一格本该是 alpha 策略搬过的那一档，却没被标记——准入判据与表脱钩了", row.chainMoved)
        SceneLuma.wallpaper = row.stats
        val alpha = barAlpha(row)
        assertTrue(
            "这一格的下限（改后 0.74）本该越过天花板，链路现解 $alpha（改前是 0.5074）",
            alpha >= BOTTOM_BAR_SURFACE_ALPHA_CEILING - 1e-6f,
        )
        val scene = sceneLumaOf(row)
        val plate = compositeLuma(row.tint.readableLuminance(), scene, alpha)
        val ink = bottomBarInk(row.tint, alpha, row.text, row.darkTheme, scene)
        assertNotEquals("板被天花板钉住后品牌墨读不清，这一格该退黑白", row.text, ink)
        assertTrue(
            "交出去的墨在它自己的板 $plate 上只有 ${contrastRatio(plate, ink.readableLuminance())}:1",
            contrastRatio(plate, ink.readableLuminance()) >= DesignTokens.WCAG_AA_RATIO,
        )
    }

    // ---- 形状守卫：端点 / 单调 / NaN ------------------------------------------

    /**
     * [compositeLuma] 的不变量：alpha=0 就是场景、alpha=1 就是底板、板与场景同亮度时
     * alpha 不作数、随 alpha 朝 tint 收敛，且 NaN 照旧向外传（不许变成 0，也不许抛）。
     *
     * 端点、退化与 NaN 那几条与"混在哪一维"无关，它们保证搬维度不会顺手搬出定义域外的行为；
     * 凸性那几条则**就是**维度的判据：sRGB 解码是凸函数，所以混在编码通道上的结果
     * 必须严格低于混在线性亮度上的结果（板与场景不同亮度、alpha 不在端点时）——旧口径两者恒等，
     * 这一格当场红。
     */
    @Test
    fun thePlateModelKeepsItsEndpointsItsMonotonyAndItsNaN() {
        val tint = DarkGlassTint.readableLuminance()
        val scene = Color(0xFF808080).readableLuminance()
        assertEquals(scene, compositeLuma(tint, scene, 0f), 1e-5f)
        assertEquals(tint, compositeLuma(tint, scene, 1f), 1e-5f)
        for (a in listOf(0.08f, 0.2f, 0.5f, 0.6f, 0.9f)) {
            assertEquals(tint, compositeLuma(tint, tint, a), 1e-5f)
        }
        for ((from, to) in listOf(scene to tint, tint to scene)) {
            val sweep = listOf(0f, 0.2f, 0.4f, 0.6f, 0.8f, 1f).map { compositeLuma(to, from, it) }
            // alpha 越大板越像 tint：写成"离 tint 越来越近"，暗板压亮景与亮板压暗景
            // 两个方向就能共用一条判据，不必各配一个不等号。
            assertTrue(
                "板亮度对 alpha 不再朝 tint 收敛：$sweep（tint=$to 场景=$from）",
                sweep.zipWithNext().all { (x, y) -> abs(y - to) <= abs(x - to) + 1e-6f },
            )
        }
        // 凸性（Jensen）：解码曲线是凸的，所以"先线性混合亮度"恒**高于**"混完再解码"，
        // 板与场景亮度不同、alpha 在开区间时还严格高于——旧口径与这条差的是实打实的一格，
        // 不是浮点噪声（这一族里最小的一格也差 0.014）。
        for (pair in listOf(tint to scene, scene to tint, 0.9f to 0.02f)) {
            for (a in listOf(0.1f, 0.35f, 0.6f, 0.85f)) {
                val linear = pair.first * a + pair.second * (1f - a)
                val encoded = compositeLuma(pair.first, pair.second, a)
                assertTrue(
                    "${pair.first} 以 $a 叠在 ${pair.second} 上：编码口径 $encoded 没有比线性口径 $linear " +
                        "低出 STRICT_GAP（旧口径两者恒等）",
                    linear - encoded >= STRICT_GAP,
                )
            }
        }
        assertTrue("场景 NaN 被吃成了 0：${compositeLuma(tint, Float.NaN, 0.6f)}", compositeLuma(tint, Float.NaN, 0.6f).isNaN())
        assertTrue("底板 NaN 被吃成了 0：${compositeLuma(Float.NaN, scene, 0.6f)}", compositeLuma(Float.NaN, scene, 0.6f).isNaN())
        assertTrue("alpha NaN 被吃成了 0：${compositeLuma(tint, scene, Float.NaN)}", compositeLuma(tint, scene, Float.NaN).isNaN())
    }

    // ---- 深色档那五行：解出来的墨不许是实测板上输的那支 ------------------------

    /**
     * 深色档五行：[bottomBarInk] 最终交出来的那支墨，放在**实测板**上必须不输给另一支黑白墨。
     *
     * 这条是这一族问题真正的用户可见后果：旧模型以为板亮到 0.30~0.40，`contentOnLuma`
     * 于是把 0.203 交点判给了近黑，而真实板整条壁纸扫描都没越过 0.203（上限 0.1717）——
     * 白墨在每一档深色壁纸上都是赢家，却被系统性判负，最坏一格只剩 3.65:1（本应 4.74:1）。
     * 品牌墨被留下的那几格另断一条：它必须在实测板上**真的**到 AA，
     * 否则"达标才留品牌墨"这条观感口径就是拿假亮度糊出来的。
     */
    @Test
    fun theDarkBarNeverHandsOutTheInkThatLosesOnTheMeasuredPlate() {
        var switched = 0
        var kept = 0
        for (row in deviceRows.filter { it.darkTheme }) {
            SceneLuma.wallpaper = row.stats
            val alpha = barAlpha(row)
            val ink = bottomBarInk(
                containerColor = row.tint,
                effectiveAlpha = alpha,
                text = row.text,
                darkTheme = row.darkTheme,
                sceneLuma = sceneLumaOf(row),
            )
            val onMeasured = contrastRatio(row.measuredPlate, ink.readableLuminance())
            when (ink) {
                ContentLight, ContentDark -> {
                    val other = if (ink == ContentLight) ContentDark else ContentLight
                    val onOther = contrastRatio(row.measuredPlate, other.readableLuminance())
                    assertTrue(
                        "${row.label}：选到输的那支——${if (ink == ContentLight) "白" else "近黑"}墨在实测板 " +
                            "${row.measuredPlate} 上只有 $onMeasured:1，另一支有 $onOther:1",
                        onMeasured >= onOther - 1e-3f,
                    )
                    switched++
                }

                else -> {
                    assertTrue(
                        "${row.label}：留了品牌墨却没在实测板 ${row.measuredPlate} 上达标（$onMeasured:1）",
                        onMeasured >= DesignTokens.WCAG_AA_RATIO,
                    )
                    kept++
                }
            }
        }
        assertTrue("换成黑白的格数是 0：这条扫描没测到「不达标才退」那一支", switched > 0)
        assertTrue("保留品牌墨的格数是 0：这条扫描没测到「实测板上真的达标才留」那一支", kept > 0)
        assertEquals("五行里多出一支既不是黑白也不是品牌墨的墨", 5, switched + kept)
    }

    /**
     * 最亮那一档（纯白壁纸、板被 0.60 天花板钉死）必须落到白墨，且在实测板上 ≥ AA。
     *
     * 它是旧口径错得最狠的一格（模型 0.4049 / 实测 0.1717），也是 T25b 那条"改墨不改天花板"
     * 的原始靶子：旧模型在这里判给近黑并自称 7.45:1，真实板上的近黑只有 3.65:1。
     */
    @Test
    fun theBrightestWallpaperRowLandsOnWhiteInkAtOrAboveAA() {
        val row = deviceRows.first { it.darkTheme && it.wallpaper == 255 }
        SceneLuma.wallpaper = row.stats
        val ink = bottomBarInk(
            containerColor = row.tint,
            effectiveAlpha = barAlpha(row),
            text = row.text,
            darkTheme = row.darkTheme,
            sceneLuma = sceneLumaOf(row),
        )
        assertEquals("纯白壁纸 × 深色档该落到白墨", ContentLight, ink)
        assertTrue(
            "白墨在实测板 ${row.measuredPlate} 上只有 ${contrastRatio(row.measuredPlate, ContentLight.readableLuminance())}:1",
            contrastRatio(row.measuredPlate, ContentLight.readableLuminance()) >= DesignTokens.WCAG_AA_RATIO,
        )
    }

    // ---- 取值 -------------------------------------------------------------

    /** 该行在生产链路下的栏体 alpha（默认透明度滑条档，与量像素时同一档）。 */
    private fun barAlpha(row: DeviceRow): Float = bottomBarSurfaceAlpha(
        containerAlpha = DesignTokens.CHROME_SURFACE_ALPHA,
        userAlphaScale = DesignTokens.cardAlphaScale(0.88f),
        containerColor = row.tint,
        text = row.text,
        darkTheme = row.darkTheme,
    )

    private fun sceneLumaOf(row: DeviceRow): Float =
        bottomBarSceneLuma(row.tint, row.text, row.darkTheme)

    /**
     * 一行实测。
     *
     * [renderedAlpha] = 量那片像素时真正画下去的 tint alpha：
     * `null` 表示那一档恰好就是生产链路当下解出的值（只剩"深色 × 纯黑壁纸"一行——黑壁纸给不了
     * 暗板任何杠杆，下限在那里落回 0.20 基准档）；数值则来自表注释里写明的那条来历，
     * 逐行钉死，因为链路改后解出的 alpha 不再等于历史上画下去的那一档。
     * [chainMoved] = 生产链路现解的 alpha 已经**不等于**当初画下去的那档
     * （±[ALPHA_POLICY_EPSILON]）：这一格比的已经不是同一块板，因此不拿链路值与实测值对表。
     * 搬混合维度之前只有一格满足它（浅色 × 纯黑，下限与 0.60 天花板互相顶住），搬完之后
     * 多行的下限都离开自己原来那一档，所以这条标记要按判据逐行核对，不能当常数维护。
     */
    private class DeviceRow(
        val label: String,
        val darkTheme: Boolean,
        val wallpaper: Int,
        val sceneLuma: Float,
        val measuredPlate: Float,
        val renderedAlpha: Float?,
        val chainMoved: Boolean = false,
    ) {
        val tint: Color get() = if (darkTheme) DarkGlassTint else LightGlassTint
        val text: Color get() = if (darkTheme) DarkBlueOnSurfaceVariant else BlueOnSurfaceVariant
        val stats: SceneLuma.Stats get() = SceneLuma.Stats(sceneLuma, sceneLuma, sceneLuma)
    }

    private companion object {
        /** 绝对亮度闸门：包住壁纸采样与中位取整的噪声，不包口径错误 */
        const val MEASURED_PLATE_TOLERANCE = 0.03f

        /** "链路现解的 alpha 还是不是当初画下去的那档"的比对容差 */
        const val ALPHA_POLICY_EPSILON = 1e-3f

        /**
         * 凸性闸门的下界：编码口径比线性口径至少低这么多。
         * 这一族（暗玻璃板 ↔ 灰 128、0.9 ↔ 0.02 各四档 alpha）最小的一格差 0.0139，
         * 取 0.01 留一档余量；旧线性口径与自己的差恒为 0，所以这一格改前必红。
         */
        const val STRICT_GAP = 0.01f

        val deviceRows = listOf(
            // 深色档：#14161C 以 0.60 叠在白块上，模型 0.4049 / 实测 0.1717 是这张表的锚点。
            // v=0 那档画的是 0.20 基准值（黑壁纸给不了暗板任何杠杆，下限落回绝对地板），
            // v=96 那档画的是旧线性下限自己解出的 0.272 ——
            // 即 (0.1170 - 0.0874) / (0.1170 - 0.0081)，它当时没够到 0.60 天花板。
            // 那一档是**画下去的事实**，所以按输入钉死、不填 null：搬对混合维度之后链路会解出
            // 别的 alpha，拿链路值去对 0.0721 那片像素就成了两套口径互比。
            DeviceRow("深色 × 纯黑壁纸", true, 0, 0.0000f, 0.0013f, null),
            DeviceRow("深色 × 灰阶 96", true, 96, 0.1170f, 0.0721f, 0.272f, chainMoved = true),
            DeviceRow("深色 × 灰阶 160", true, 160, 0.3515f, 0.0744f, 0.60f, chainMoved = true),
            DeviceRow("深色 × 灰阶 224", true, 224, 0.7454f, 0.1359f, 0.60f),
            DeviceRow("深色 × 纯白壁纸", true, 255, 1.0000f, 0.1717f, 0.60f),
            // 浅色档：#F2F4F8。v=0 那档画的是旧下限解出的 0.505（它自以为抬到了 AA 线），
            // v=160/255 两档画的是 0.20 基准值。
            DeviceRow("浅色 × 纯黑壁纸", false, 0, 0.0000f, 0.1978f, 0.505f, chainMoved = true),
            DeviceRow("浅色 × 灰阶 160", false, 160, 0.3515f, 0.4389f, 0.20f, chainMoved = true),
            DeviceRow("浅色 × 纯白壁纸", false, 255, 1.0000f, 0.9810f, 0.20f),
        )
    }
}
