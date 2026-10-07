package com.buaa.schedule.core.designsystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T215②：盘上那枚 `glass_alpha` 进 [Personalization.cardAlpha] 之前的边界清洗
 * （`cardAlphaFromDisk`，同文件末那枚判据函数）—— 纯 JVM 表驱动，零 Context、零设备。
 *
 * ## 这张表从哪儿来（区间不是照卡面猜的）
 *
 * 定义域 0.3–1.0 是三处独立读数撞出来的同一档：
 * 1. `ui/settings/SettingsScreen.kt` 那根滑条声明的 `valueRange = 0.3f..1f` —— 运行期**唯一**
 *    能写 `Personalization.cardAlpha` 的入口（`Personalization.cardAlpha = ` 全仓一枚，
 *    枚数与位置钉在 `GlassAlphaRangeSingleSourceGuardTest`）；
 * 2. `ui/home/WeekView.kt` 课程卡 tintAlpha 的线性映射 `((cardAlpha - 0.3f) / 0.7f)`，
 *    它自己的注释就写着「cardAlpha 可取 0.3f..1f」，那枚 `0.7f` 就是 1.0 − 0.3；
 * 3. `docs/DESIGN_SYSTEM.md` 的 `cardAlphaScale` 那一格：「滑条最低 0.3，归一后只剩 0.34 倍」
 *    —— 0.3 / 0.88 实测 0.3409，与本文件 [theClampKeepsEveryLegalStepDistinctAndOrdered] 量到的
 *    `cardAlphaScale(0.3f)` 逐位同值 ⇒ 文档念的就是这一档。
 * 夹取落在**读盘处**而不是六处消费点的理由（六家各自夹什么、为什么没有一家挡得住 NaN）
 * 写在判据函数的 KDoc 里，本文件只交它的账。
 *
 * ## NaN 那一格是量出来的
 *
 * 卡面禁止"照抄直觉"，所以 `Float.coerceIn` 对 NaN 的真实行为单独钉成一枚判据
 * （[coerceInLetsNanThroughMeasuredNotGuessed]）：**两档比较都是 false ⇒ NaN 原样穿出**，
 * 连端点自己是 NaN 都不抛。这条实测同时就是修法选择的前提 —— 只在读盘处补一枚 `coerceIn`
 * 挡不住 NaN，所以判据函数多写了 `isFinite()` 那一手；而
 * [theGlassChainSwallowsNoNaNOnceTheDiskReadIsClamped] 把"改前 NaN 一路画到 Color 上、
 * 改后落到有限数"两条读数并排放。
 *
 * 另两格与本仓已修的族同源：空区间（T22 那枚 `Cannot coerce value to an empty range`）在这里
 * 必须**照抛**（[anInvertedRangeStaysLoudRatherThanBeingSilentlyReordered]）—— 判据函数不许
 * 顺手把倒置的端点排好，那等于把下一枚 T22 洗成静默画错。
 */
class GlassAlphaDiskClampTest {

    /**
     * 主表：卡面点名的五档（上界外 / 下界外 / NaN / 负数 / 正常）全部在册，另加端点、
     * ±∞、±Float.MAX_VALUE、负 NaN 四档越界的形状。
     *
     * 逐格断三件事：落点**等于**表里那个数（不是"差不多在区间里"）、落在 0.3–1.0 内、是有限数。
     */
    @Test
    fun everyDiskCellLandsOnTheTabledLandingPoint() {
        for (cell in diskCells) {
            val landed = runCatching { cell.raw.cardAlphaFromDisk() }.getOrElse {
                throw AssertionError("${cell.label}：第一次读盘就抛（盘上值 ${cell.raw}）—— 老数据那一格复发", it)
            }
            assertEquals("${cell.label}：raw=${cell.raw}", cell.expected, landed, 0f)
            assertTrue(
                "${cell.label}：交出去的 $landed 不在 [$MIN-$MAX] 之内 —— 下游六家会各自按自己的饱和点画",
                landed in MIN..MAX,
            )
            assertTrue("${cell.label}：交出去的是 $landed，不是有限数", landed.isFinite())
        }
    }

    /**
     * ⚠️ 实测格（不是直觉格）：`Float.coerceIn` 对 NaN **不夹**，端点自己是 NaN 也**不抛**。
     *
     * 这一枚是"判据函数里那句 `isFinite()` 不是多余的"的全部理由：若 `coerceIn` 会把 NaN 收敛到
     * 端点，读盘处一枚 `coerceIn` 就够了，本卡就不必加第二手。四行读数全是盘面实测：
     * - `NaN.coerceIn(0.3f, 1f)` → 仍是 NaN（`this < min` 与 `this > max` 两次比较都 false）；
     * - `-NaN.coerceIn(0.3f, 1f)` → 同样 NaN（符号位不影响）；
     * - `0.5f.coerceIn(NaN, NaN)` → **不抛、也不夹**，原样交出 0.5（空区间那句 require 只在
     *   `min > max` 时点火，而 NaN 参与的三次比较全是 false ⇒ 它连"端点是 NaN"都判不出来，
     *   更连"值该被夹到哪儿"都答不出来）；
     * - `DesignTokens.cardAlphaScale(NaN)` → NaN（倍率那一家也不夹）。
     * ⇒ "只在读盘处夹一枚 coerceIn"这一支会把 NaN 原样送进六家消费点。
     */
    @Test
    fun coerceInLetsNanThroughMeasuredNotGuessed() {
        val coerced = Float.NaN.coerceIn(MIN, MAX)
        assertTrue(
            "实测读数变了：NaN.coerceIn($MIN,$MAX) 现在给 $coerced —— 本卡的修法（要不要补 isFinite）" +
                "是按「coerceIn 放行 NaN」量的，它变了就要重判",
            coerced.isNaN(),
        )
        val negativeNaN = (-Float.NaN).coerceIn(MIN, MAX)
        assertTrue("实测读数变了：(-NaN).coerceIn($MIN,$MAX) 现在给 $negativeNaN", negativeNaN.isNaN())
        val withNanBounds = runCatching { 0.5f.coerceIn(Float.NaN, Float.NaN) }
        assertTrue(
            "实测读数变了：端点是 NaN 的 coerceIn 现在会抛（${withNanBounds.exceptionOrNull()}）—— " +
                "那「空区间」那一族在『端点被算坏』这一档上多了一层保险，本卡与 T22 的口径要合并重钉",
            withNanBounds.isSuccess,
        )
        assertEquals(
            "实测读数：两枚端点都是 NaN 时 coerceIn **既不抛也不夹**（NaN 参与的三次比较全 false）⇒ " +
                "被夹的那枚数原样穿出（现交出 ${withNanBounds.getOrNull()}）。这条实测就是" +
                "「端点的脏只能靠编译期常量钉死、不能指望 coerceIn 报错」的凭据",
            0.5f,
            withNanBounds.getOrThrow(),
            0f,
        )
        val scale = DesignTokens.cardAlphaScale(Float.NaN)
        assertTrue(
            "实测：cardAlphaScale(NaN) 现在给 $scale —— 倍率那一家若不夹 NaN，读盘处就必须挡",
            scale.isNaN(),
        )
        // 同一条事实要在真实的消费链上复现一次：它不抛（下面那枚判据量的是画出来什么）。
        assertFalse(
            "实测：NaN 倍率走 glassSurfaceAlpha 现在会抛 —— 那本卡对这一格的口径要从「静默画透明板」" +
                "改成「冷启动 FATAL」，KDoc 与回执都要跟着改",
            nanScaleSurfaceAlphaThrew,
        )
    }

    /**
     * 改前 / 改后并排：同一枚盘上 NaN，"原样交给消费侧"与"先过 [cardAlphaFromDisk]"的差别。
     *
     * 基线取一条**读得清**的（暗玻璃板 + 浅色正文 + 无壁纸场景，下限落在 0.08 的绝对地板，口径同
     * `GlassSurfaceAlphaTest.readablePlates`），这样夹取那一手不会把结论混进饱和档。四条实测：
     * - 改前：`glassSurfaceAlpha` 交出 **NaN**（不抛）；
     * - 改前落到画板上：`Color.copy(alpha = NaN)` 把 alpha 折成 **0**，与 `copy(alpha = 0f)`
     *   逐位同一枚数 ⇒ 这块玻璃底板被画成全透明，文字直接压在壁纸上 —— 不报错、不红、门禁全绿，
     *   正是本仓那一族"静默画错"；
     * - 改后：有限数，落在 `DesignTokens.GLASS_HARD_MIN_ALPHA..SURFACE_ALPHA_CEILING`；
     * - 而且与"没存过这枚偏好"的默认档读数**逐位相等**（NaN 回默认，所以观感 = 出厂档）。
     */
    @Test
    fun theGlassChainSwallowsNoNaNOnceTheDiskReadIsClamped() {
        val before = glassSurfaceAlphaOf(Float.NaN)
        assertTrue(
            "改前那格实测读数变了：raw=NaN 经消费侧算出 $before（本卡说的是「NaN 一路穿到画板」）",
            before.isNaN(),
        )
        assertEquals(
            "实测：NaN alpha 的画板与 alpha=0 的画板不是同一枚数 —— 「静默画成全透明」这句要改成别的后果",
            DarkGlassTint.copy(alpha = 0f).value,
            DarkGlassTint.copy(alpha = before).value,
        )
        val after = glassSurfaceAlphaOf(Float.NaN.cardAlphaFromDisk())
        assertFalse("改后那格还是 NaN：读盘处的夹取没生效", after.isNaN())
        assertTrue(
            "改后那格 $after 越出 [${DesignTokens.GLASS_HARD_MIN_ALPHA}, $SURFACE_ALPHA_CEILING]",
            after in DesignTokens.GLASS_HARD_MIN_ALPHA..SURFACE_ALPHA_CEILING,
        )
        assertEquals(
            "NaN 档回的是默认档，所以改后的读数应与「没存过这枚偏好」逐位相等",
            glassSurfaceAlphaOf(DEFAULT),
            after,
            0f,
        )
    }

    /**
     * 合法档位一格都不许动（改前改后逐位对照）：0.3 / 0.35 / 0.5 / 0.7 / 0.88 / 1.0 六档 ×
     * 三条真实式子（倍率、课程卡 tintAlpha、面板 surfaceAlpha）。
     *
     * 这一枚是「为了修崩溃顺手把通透感调没了」那支先例的口径：夹取只在盘外点火，域内必须恒等。
     */
    @Test
    fun theClampMovesNoLegalCell() {
        for (raw in listOf(MIN, 0.35f, 0.5f, 0.7f, DEFAULT, MAX)) {
            assertEquals("$raw 这一档不该被这次改动碰到", raw, raw.cardAlphaFromDisk(), 0f)
            assertEquals(
                "$raw 这一档的倍率读数变了",
                DesignTokens.cardAlphaScale(raw),
                DesignTokens.cardAlphaScale(raw.cardAlphaFromDisk()),
                0f,
            )
            assertEquals(
                "$raw 这一档的课程卡 tintAlpha 变了",
                courseTintAlpha(raw),
                courseTintAlpha(raw.cardAlphaFromDisk()),
                0f,
            )
            assertEquals(
                "$raw 这一档的面板 surfaceAlpha 变了",
                glassSurfaceAlphaOf(raw),
                glassSurfaceAlphaOf(raw.cardAlphaFromDisk()),
                0f,
            )
        }
    }

    /**
     * 往返幂等：`save()` 把内存态原样落盘、下一次冷启动再读回来，读数不许往一端漂。
     *
     * 幂等在这里不是数学洁癖：本仓"成对字段"那一族（#149 / T106–T110 / T116）漏的都是
     * 「写回去的数与读回来的数不是同一枚」这一型。每一档都要 `f(f(x)) == f(x)`。
     */
    @Test
    fun clampingIsIdempotentAcrossTheSaveLoadRoundTrip() {
        for (cell in diskCells) {
            val once = cell.raw.cardAlphaFromDisk()
            assertEquals("${cell.label}：第二次读盘又夹了一道（幂等破了）", once, once.cardAlphaFromDisk(), 0f)
        }
    }

    /**
     * 单调 + 域内单射，外加两条饱和窗口必须**包住**这一档定义域。
     *
     * 反面的先例就是 `WeekView` 那行过期注释承认的老病：旧写法
     * `(cardAlpha * 0.62f).coerceIn(0.42f, 0.72f)` 让 0.3~0.68 一整段算出同一个 0.42，
     * 用户往左拖到底看不到任何变化。夹取本身若把域内压平成一段，症状一模一样。
     */
    @Test
    fun theClampKeepsEveryLegalStepDistinctAndOrdered() {
        val steps = (0..20).map { MIN + (MAX - MIN) * it / 20f }
        val landed = steps.map { it.cardAlphaFromDisk() }
        for (index in 1 until steps.size) {
            assertTrue(
                "${steps[index]} 这一档算出 ${landed[index]}，比上一档 ${landed[index - 1]} 还小 —— 夹取把顺序弄反了",
                landed[index] >= landed[index - 1],
            )
            assertTrue(
                "${steps[index]} 与 ${steps[index - 1]} 交出同一个数 ${landed[index]} —— 域内出现死区",
                landed[index] != landed[index - 1],
            )
        }
        assertEquals(
            "倍率下界那一段没夹住 0.3（实测 $EXPECTED_LOW_SCALE vs cardAlphaScale(MIN)）",
            EXPECTED_LOW_SCALE, DesignTokens.cardAlphaScale(MIN), 0f,
        )
        assertEquals(
            "倍率上界那一段没夹住 1.0（实测 ${DesignTokens.cardAlphaScale(MAX)}）",
            EXPECTED_HIGH_SCALE, DesignTokens.cardAlphaScale(MAX), 0f,
        )
    }

    /**
     * 毒档全扫一遍都不抛（卡面点名的"老数据第一次读盘必须不崩"那一格）。
     *
     * 扫的是**判据函数自己**：十六枚盘上能出现的形状（NaN / ±∞ / ±Float.MAX_VALUE / 负数 / 零 /
     * 离谱大数 / 次正规数），每一枚都必须交出落在域内的有限数。与
     * [anInvertedRangeStaysLoudRatherThanBeingSilentlyReordered] 是一对：盘上的数再脏都不抛，
     * **代码里**写反的区间照抛。
     */
    @Test
    fun noDiskShapeAtAllCanThrowOnTheFirstRead() {
        val poisons = listOf(
            Float.NaN, -Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
            Float.MAX_VALUE, -Float.MAX_VALUE, Float.MIN_VALUE, -Float.MIN_VALUE,
            0f, -0f, -1f, -0.5f, 1.0001f, 2f, 100f, 1e30f,
        )
        for (raw in poisons) {
            val outcome = runCatching { raw.cardAlphaFromDisk() }
            assertTrue("盘上读数 $raw 第一次读盘就抛：${outcome.exceptionOrNull()}", outcome.isSuccess)
            val landed = outcome.getOrThrow()
            assertTrue("$raw 交出了 $landed（不在域内或不是有限数）", landed in MIN..MAX && landed.isFinite())
        }
    }

    /**
     * 空区间那一族（T22 的成因）在这里必须**仍然响**：判据函数不许替调用方把倒置的端点排好。
     *
     * 默认区间是两枚编译期常量（`0.3f` / `1f`），组不成空区间 ⇒ 那条链上没有 T22 的载体；
     * 这一格钉的是"将来有人把区间挪成运行期参数时，失败必须是响的"。
     * 反面顺手量一件事：把 NaN 当**端点**喂进来，`coerceIn` 判不出来（上面测过它不抛，
     * 而且把被夹的数原样交回，域外的 0.1 就这么穿出去）——
     * 所以 `isFinite()` 那一手管的是盘上的数，端点的脏只能靠常量本身（守卫钉在
     * `GlassAlphaRangeSingleSourceGuardTest` 的「MIN < MAX」那一格）。
     */
    @Test
    fun anInvertedRangeStaysLoudRatherThanBeingSilentlyReordered() {
        val outcome = runCatching { 0.5f.cardAlphaFromDisk(min = 0.9f, max = 0.3f) }
        assertTrue("端点写反了却不抛 —— 判据函数替调用方排了序，下一枚 T22 就此变静默", outcome.isFailure)
        val error = outcome.exceptionOrNull()!!
        assertTrue(
            "${error::class.simpleName} 不是 IllegalArgumentException：区间反了与值脏了得是两种病",
            error is IllegalArgumentException,
        )
        assertTrue(
            "实测报错消息里没有那句空区间（现文：${error.message}）—— 消息换了写法要回来重钉这一串针",
            error.message.orEmpty().contains("Cannot coerce value to an empty range"),
        )
        val nanBounds = runCatching { 0.1f.cardAlphaFromDisk(min = Float.NaN, max = Float.NaN) }
        assertTrue(
            "实测读数变了：NaN 当端点现在会抛（${nanBounds.exceptionOrNull()}）—— 上面 KDoc 那句" +
                "「端点的脏只能靠常量本身」要跟着改口径",
            nanBounds.isSuccess,
        )
        assertEquals(
            "实测读数：判据函数在『端点被算坏成 NaN』时**原样交出域外的 0.1**（isFinite 管的是盘上的值，" +
                "不是端点）—— 这一格不是给那种写法开绿灯，它交的是「端点必须留在编译期常量上」的全部理由；" +
                "符号那一手钉在 GlassAlphaRangeSingleSourceGuardTest 的逐字节格里",
            0.1f,
            nanBounds.getOrThrow(),
            0f,
        )
    }

    // ---- 取值 --------------------------------------------------------------

    /** `WeekView` 课程卡 tintAlpha 那条式子（本卡不许改它，故在这里同式复算；字面量对齐归守卫管） */
    private fun courseTintAlpha(cardAlpha: Float): Float {
        val fraction = ((cardAlpha - 0.3f) / 0.7f).coerceIn(0f, 1f)
        return 0.26f + (0.68f - 0.26f) * fraction
    }

    /** 读得清的那条基线（暗玻璃板 + 浅色正文 + 无壁纸场景），口径同 `GlassSurfaceAlphaTest.readablePlates` */
    private fun glassSurfaceAlphaOf(cardAlpha: Float): Float {
        SceneLuma.wallpaper = SceneLuma.Stats.Unknown
        return glassSurfaceAlpha(
            variant = GlassVariant.PANEL,
            material = DesignTokens.glassMaterial(GlassVariant.PANEL),
            semanticTint = null,
            alphaScale = DesignTokens.cardAlphaScale(cardAlpha),
            baseTint = DarkGlassTint,
            text = DarkBlueOnSurfaceVariant,
            darkTheme = true,
        )
    }

    private companion object {
        /** 定义域与默认档都从 [Personalization] 的三枚编译期常量取，测试里不写第二份字面量 */
        const val MIN = Personalization.MIN_CARD_ALPHA
        const val MAX = Personalization.MAX_CARD_ALPHA
        const val DEFAULT = Personalization.DEFAULT_CARD_ALPHA

        /** 倍率的两端按"定义域恰好落在 cardAlphaScale 的线性段里"算，不是抄来的数 */
        const val EXPECTED_LOW_SCALE = 0.3f / 0.88f
        const val EXPECTED_HIGH_SCALE = 1f / 0.88f

        /** 一次实测的副产物：NaN 倍率走 glassSurfaceAlpha 到不到抛（供上面那枚「不抛」的断言用） */
        val nanScaleSurfaceAlphaThrew: Boolean = runCatching {
            SceneLuma.wallpaper = SceneLuma.Stats.Unknown
            glassSurfaceAlpha(
                variant = GlassVariant.PANEL,
                material = DesignTokens.glassMaterial(GlassVariant.PANEL),
                semanticTint = null,
                alphaScale = DesignTokens.cardAlphaScale(Float.NaN),
                baseTint = DarkGlassTint,
                text = DarkBlueOnSurfaceVariant,
                darkTheme = true,
            )
        }.isFailure

        /** 表：卡面点名的五档 + 端点 + ±∞ + ±Float.MAX_VALUE + 负 NaN */
        val diskCells = listOf(
            Cell("负数（下界外）", -0.5f, MIN),
            Cell("零下界（下界外）", 0f, MIN),
            Cell("下界端点", MIN, MIN),
            Cell("正常档（滑条中段）", 0.5f, 0.5f),
            Cell("正常档（默认）", DEFAULT, DEFAULT),
            Cell("上界端点", MAX, MAX),
            Cell("上界外一点点", 1.05f, MAX),
            Cell("上界外（12 倍）", 12f, MAX),
            Cell("Float.MAX_VALUE", Float.MAX_VALUE, MAX),
            Cell("负 Float.MAX_VALUE", -Float.MAX_VALUE, MIN),
            Cell("正无穷（脏偏好）", Float.POSITIVE_INFINITY, DEFAULT),
            Cell("负无穷（脏偏好）", Float.NEGATIVE_INFINITY, DEFAULT),
            Cell("NaN（脏偏好）", Float.NaN, DEFAULT),
            Cell("负 NaN（符号位不该有别）", -Float.NaN, DEFAULT),
        )
    }

    /** 表里一格：盘上读数 → 期望落点 */
    private class Cell(val label: String, val raw: Float, val expected: Float)
}
