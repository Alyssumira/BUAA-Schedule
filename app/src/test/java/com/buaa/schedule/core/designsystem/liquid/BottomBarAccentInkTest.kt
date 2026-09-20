package com.buaa.schedule.core.designsystem.liquid

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.buaa.schedule.core.designsystem.BlueOnSurfaceVariant
import com.buaa.schedule.core.designsystem.BluePrimary
import com.buaa.schedule.core.designsystem.ContentDark
import com.buaa.schedule.core.designsystem.ContentLight
import com.buaa.schedule.core.designsystem.DarkBlueOnSurfaceVariant
import com.buaa.schedule.core.designsystem.DarkBluePrimary
import com.buaa.schedule.core.designsystem.DarkGlassTint
import com.buaa.schedule.core.designsystem.DesignTokens
import com.buaa.schedule.core.designsystem.LightGlassTint
import com.buaa.schedule.core.designsystem.SceneLuma
import com.buaa.schedule.core.designsystem.compositeLuma
import com.buaa.schedule.core.designsystem.contrastRatio
import com.buaa.schedule.core.designsystem.legibilityAlphaFloor
import com.buaa.schedule.core.designsystem.readableLuminance
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [bottomBarAccentInk]：底栏**选中态**那一族的墨也按实际复合底色解（ai/T32）。
 *
 * ## 这张靶子是 ai/T25b 有意留下的那一半
 *
 * [bottomBarInk] 那一卡明知故犯地写着"不动选中态与指示器那一族"，于是未选中的图标/文字
 * 已经按复合底色解过，选中的那一份（`scheme.primary`）还是写死的品牌色。逐格量下来
 * （默认档 cardAlpha=0.88，场景组与 `BottomBarInkTest` 同一套）：
 *
 * | 壁纸块 | 深色档 primary 在它那块板上的读数 | 浅色档 primary |
 * |---|---|---|
 * | 内置渐变 | 4.4953 | 2.5496 |
 * | 极白块 | 2.4161 | 1.7113 |
 * | 极黑块 | 3.6500 | 1.4248 |
 * | 均匀中灰 | 3.4187 | 2.5496 |
 * | 中等亮块 | 3.6500 | 1.7113 |
 *
 * **十格全低于 AA**，最糊的 1.42:1。注意读数的对照物是**指示器那块板**（[bottomBarIndicatorPlateLuma]），
 * 不是栏体板：选中态那一份图标/文字在稳态下被指示器盖着，而指示器的 `onDrawSurface` 会在
 * 它头上叠一层 wash（深色档白 @0.10、浅色档黑 @0.10），墨与板一起被罩。拿栏体板去量这一族
 * 就是拿一块它并不坐在上面的板。
 *
 * 编排者转来的那个 **2.78:1** 不是这一族的数，见 [theQuotedRatioBelongsToAnotherObject]：
 * 它是**未选中**那支 `onSurfaceVariant` 在**栏体板**上的读数（`BottomBarInkTest` 钉的正是那个 2.78）。
 *
 * ## 为什么这里只测纯函数
 *
 * 与 `BottomBarInkTest` 同一处境：本模块 JVM 单测没有 Compose 运行时（无 Robolectric、无
 * ui-test），`@Composable` 体内的取值测不到，接线形状与"场景亮度只读一次"只能按源码核对，
 * 见 [theAccentInkSharesTheBarsInputsAndTheWashHasOneSource]。
 *
 * 场景亮度靠 [SceneLuma] 驱动（风格同 `BottomBarSurfaceAlphaTest`）。
 */
class BottomBarAccentInkTest {

    @After
    fun restoreScene() {
        SceneLuma.wallpaper = SceneLuma.Stats.Unknown
    }

    // ---- 先量：改前这一族到底读不读得清 --------------------------------------

    /**
     * 改前的真实读数逐格钉死（[Cell] 那张表，默认档 cardAlpha=0.88）。
     *
     * 这条与实现无关：它量的就是 `scheme.primary` 本身，[bottomBarAccentInk] 换成什么墨都
     * 不动它。所以它同时是两件事的证据——"十格全低于 AA"（本卡的前提），以及色表/天花板/
     * 下限一旦被挪动这里当场报数。
     */
    @Test
    fun theBrandAccentFailsEveryCellOfTheTriggerGrid() {
        val readings = TRIGGER_GRID.map(::measure)
        assertEquals("触发格少了一格，下面那张表就不该照旧", TRIGGER_GRID.size, readings.size)
        for ((index, row) in readings.withIndex()) {
            val expected = BEFORE[index]
            assertEquals("${row.cell.label}：改前读数不再是 ${expected.brand}:1 了", expected.brand, row.brandOnIndicator, 0.01f)
            assertTrue(
                "${row.cell.label}：这一格现在只有 ${row.brandOnIndicator}:1，本卡的前提（十格全低于 AA）不成立了",
                row.brandOnIndicator < DesignTokens.WCAG_AA_RATIO,
            )
        }
        // 十格里最接近达标的深色档内置渐变更接近，但它就是不到 AA：4.4953
        val best = readings.maxOf { it.brandOnIndicator }
        assertTrue("最接近达标的那格已经到 AA 了（$best:1），本卡该交的是空手而不是改动", best < DesignTokens.WCAG_AA_RATIO)
    }

    /**
     * 2.78:1 那个数是**另一个对象**的读数：未选中的 `onSurfaceVariant` 在**栏体板**上。
     *
     * 三个数挨着摆出来（深色档 × 极白块 × 0.88 那一格）：
     * - 中性墨 `#C5C6D0` / 栏体板 = **2.7815** —— 编排者引的那个 2.78，`BottomBarInkTest` 的靶子；
     * - 选中墨 `#AAC7FF` / 栏体板 = **2.7725** —— 与上者只差 0.009，两支亮度碰巧挨着，纯巧合；
     * - 选中墨 / **指示器板**（它真坐在的那块）= **2.4161** —— 本卡的靶子。
     *
     * 钉住这三行是为了别再拿"2.78 已经修好了"当结论：2.7815 那一格 ai/T25b 确实修了，
     * 2.4161 这一族那时没动。
     */
    @Test
    fun theQuotedRatioBelongsToAnotherObject() {
        val target = measure(TRIGGER_GRID[1]) // 深色档 × 极白块
        val neutralOnBar = contrastRatio(target.barPlate, DarkBlueOnSurfaceVariant.readableLuminance())
        assertEquals("中性墨/栏体板不再是 2.7815（本卡引用的那个数就是它）", 2.7815f, neutralOnBar, 0.01f)
        assertEquals("选中墨/栏体板不再是 2.7725", 2.7725f, target.brandOnBar, 0.01f)
        assertEquals("选中墨/指示器板不再是 2.4161（本卡的靶子）", 2.4161f, target.brandOnIndicator, 0.01f)
        assertNotEquals(
            "两块板算成一个了：wash 那一层没进对照物，选中墨就是在块画不出来的板上解的",
            target.barPlate, target.indicatorPlate,
        )
        // 巧合就钉成巧合：两支墨的读数挨得很近，但谁都不是谁
        assertTrue("2.78 与选中墨读数的距离变了，说明这两个数里至少有一个被色表改动带跑了",
            kotlin.math.abs(neutralOnBar - target.brandOnBar) < 0.02f)
    }

    // ---- 再修：解出来的墨 --------------------------------------------------

    /**
     * 十格里 **9 格**解到 AA，第 10 格（深色档 × 极白块）是**真的无解**：那块的板上黑白两支
     * 都不到 AA（白 3.8840 / 近黑 3.3027），连整条坡道 8 bit 量化后的最优一档也只有 3.8840:1。
     * ai/T36 起这一格**一步都不推**，逐字交回主题那一支 `#AAC7FF`（[theUnsolvedCellHandsBackTheBrandAccentVerbatim]）。
     * 格数与归属钉在 [theSweepPinsTheUnsolvedCellsAndTheKeptBrandOnes] 里。
     *
     * 解出来的墨逐字钉死：换色表/换判据/换二分步长都会让某一格挪一档，那时这里报数、
     * 表跟着更新，而不是靠一个松弛区间糊过去。
     */
    @Test
    fun solvedAccentInksArePinnedCellByCell() {
        val readings = TRIGGER_GRID.map(::measure)
        for ((index, row) in readings.withIndex()) {
            val expected = BEFORE[index]
            assertEquals("${row.cell.label}：解出来的墨不再是 ${expected.hex}", expected.hex, hex(row.ink))
            assertEquals("${row.cell.label}：栏体 alpha 被本卡顺手改了", expected.alpha, row.alpha, 0.001f)
            assertTrue("${row.cell.label}：这一格的改前读数与 alpha 该成对出现", expected.brand > 0f)
            if (expected.onIndicator > 0f) {
                assertEquals(
                    "${row.cell.label}：解出的墨在指示器板上不再是 ${expected.onIndicator}:1",
                    expected.onIndicator, row.onIndicator, 0.01f,
                )
                assertTrue(
                    "${row.cell.label}：解出的墨只有 ${row.onIndicator}:1，低于 AA",
                    row.onIndicator >= DesignTokens.WCAG_AA_RATIO,
                )
            } else {
                // 无解那格：不许谎称达标，也不许推到底——逐字交回主题那一支（ai/T36）
                assertEquals("${row.cell.label}：无解格的读数漂了", 2.4161f, row.onIndicator, 0.01f)
                assertEquals("${row.cell.label}：无解格该一步不推、交回主题 accent", row.cell.accent, row.ink)
                assertNotEquals("${row.cell.label}：无解格又推到底交出坡道尽头那支了", ContentLight, row.ink)
            }
            assertEquals("${row.cell.label}：栏体板上的读数不再是 ${expected.onBar}:1", expected.onBar, row.onBar, 0.01f)
        }
    }

    /**
     * 无解那一格**不许推到底**（ai/T36 的唯一行为规则）：整条坡道读不到 AA 时，
     * [bottomBarAccentInk] 逐字交回传进来的 [accent]，而不是一支"付清色相代价却仍不到 AA"的墨。
     *
     * 三段证据，全按实现用的那把尺子（[bottomBarAccentInk] 内部同一口径：[reads] 对指示器那块板）量：
     *
     * 1. **确实无解**：把 `accent → 纯白` 与 `accent → #1A1B20` 两条坡道各 256 档全部过一遍
     *    8 bit 取整，逐档量在指示器板上的读数，最高的一档（`k=254/255`，已经是纯白）只有
     *    **3.8840:1** < [DesignTokens.WCAG_AA_RATIO]。"端点即最优"不靠单调性声称，靠这里扫出来。
     * 2. **改前付的是全价**：那一格推到底交出的是纯白，而同一个输入下未选中那支中性墨
     *    （[bottomBarInk]）也是纯白——两支逐字相同，选中态在那一格只剩胶囊与字重在分档。
     *    新行为交回的 `#AAC7FF` 与那支中性墨最大通道差 85/255，一眼可辨回来了；
     *    代价写在 [theSolvedInkAlwaysClearsTheBarPlate] 里（栏体板 2.7725:1）。
     * 3. **反面**：紧邻的有解格（深色档 × 极黑块，同一支品牌蓝、板亮度 0.1289）仍解到
     *    4.5034:1，新判据不许把它一起收进"不推"那一族。
     */
    @Test
    fun theUnsolvedCellHandsBackTheBrandAccentVerbatim() {
        val row = measure(TRIGGER_GRID[1]) // 深色档 × 极白块 × cardAlpha=0.88
        assertEquals("触发格选错了：这一格才该是无解格", "cardAlpha=0.88 × 极白块 × 深色档", row.cell.label)

        // 1) 整条坡道（含 8 bit 量化后）的最优一档仍读不到 AA —— 与实现同一把尺子
        val readingsOnRamps = listOf(ContentLight, ContentDark).flatMap { target ->
            ramp(row.cell.accent, target).map { reads(it, row.indicatorPlate, row.cell.dark) }
        }
        val best = readingsOnRamps.max()
        assertEquals("坡道档数不是 2 × 256：量化扫描退化成一档了", 2 * (RAMP_END + 1), readingsOnRamps.size)
        assertEquals("无解格的最优那一档不再是 3.8840:1（这条坡道本来有解了，本卡的判据该重新对账）", 3.8840f, best, 0.01f)
        assertTrue("这块板现在有解了（最优 $best:1 ≥ AA），那这一格就不该再走\"逐字交回\"那条分支", best < DesignTokens.WCAG_AA_RATIO)

        // 2) 交回的必须是 accent 本身：逐字相等，不是"接近"、不是"色相差 < X"
        assertEquals("无解格该逐字交回传进来的 accent", row.cell.accent, row.ink)
        assertEquals("无解格交出的就是主题 primary 那一支本身", DarkBluePrimary, row.ink)
        assertEquals("交回的这支在它自己那块板上仍是 2.4161:1", row.brandOnIndicator, row.onIndicator, 0f)
        assertNotEquals("无解格又交出坡道尽头那支纯白了（付全价买不到可读性）", ContentLight, row.ink)
        val neutral = bottomBarInk(row.cell.plate, row.alpha, row.cell.text, row.cell.dark, row.sceneLuma)
        assertEquals("这一格的未选中中性墨不再是纯白（本卡\"选中==未选中\"的前提变了）", ContentLight, neutral)
        assertEquals("改前那支纯白与该格未选中墨不再逐字相同", neutral, drawn(ramp(row.cell.accent, ContentLight)[RAMP_END]))
        assertEquals(
            "交回的选中墨与未选中中性墨最大通道差不再是 85/255（一眼可辨这条又没了）",
            85,
            maxChannelDiff(row.ink, neutral),
        )

        // 3) 反面：紧邻的有解格不许被"无解"判据误收
        val solvedNeighbor = measure(TRIGGER_GRID[2]) // 深色档 × 极黑块
        assertNotEquals("有解格被逐字交回了：无解判据尺子太宽", solvedNeighbor.cell.accent, solvedNeighbor.ink)
        assertTrue(
            "有解格交出的墨只有 ${solvedNeighbor.onIndicator}:1",
            solvedNeighbor.onIndicator >= DesignTokens.WCAG_AA_RATIO,
        )
        assertEquals("有解格那一步都不许多推：解出的墨不再是 4.5034:1", 4.5034f, solvedNeighbor.onIndicator, 0.01f)
    }

    /**
     * 有解的格子（30 格里的 27 格）在**两块板上都**达标；无解那 3 格逐字交出 accent，
     * 于是它们在栏体板上读不到 AA —— 这条不变式的边界在 ai/T36 之后收窄到这里。
     *
     * 原来这条是"30 格在栏体板上全部 ≥ AA"，成立是因为无解那几格被推到坡道尽头换回一支纯白
     * （白在栏体板上 4.72:1，在指示器板上仍只有 3.88:1）。ai/T36 判定"付全价却什么都没买到"
     * 不该付，于是那几格改成交回主题那一支 `#AAC7FF`：栏体板上 2.7725:1、指示器板上 2.4161:1。
     * **有解格的 AA 下限一个数字都没放宽**（那 27 格两块板都仍逐格 ≥ AA），被挪出这条不变式的
     * 只有无解那 3 格，而它们被 [theUnsolvedCellHandsBackTheBrandAccentVerbatim] 按另一条规则钉住。
     *
     * 无解格的**集合**在这里一起钉死（3 格、全是深色档 × 极白块）：将来某一格挪出这个集合
     * （板子变好读了），它就必须同时通过上面那条 ≥ AA 的断言，否则这里当场报数。
     */
    @Test
    fun theSolvedInkAlwaysClearsTheBarPlate() {
        val readings = SWEEP.map(::measure)
        assertEquals("参数化测试退化：组合数没对上", 5 * 3 * 2, readings.size)
        val (unsolved, solvable) = readings.partition { it.onIndicator < DesignTokens.WCAG_AA_RATIO }
        assertEquals(
            "无解格集合变了：${unsolved.map { "${it.cell.label}=${it.onIndicator}:1" }}",
            setOf(
                "cardAlpha=0.3 × 极白块 × 深色档",
                "cardAlpha=0.88 × 极白块 × 深色档",
                "cardAlpha=1.0 × 极白块 × 深色档",
            ),
            unsolved.map { it.cell.label }.toSet(),
        )
        assertEquals("有解格数不再是 27", SWEEP.size - 3, solvable.size)
        for (row in solvable) {
            assertTrue(
                "${row.cell.label}：选中墨在栏体板上只有 ${row.onBar}:1（可见层那一排就是这块板）",
                row.onBar >= DesignTokens.WCAG_AA_RATIO,
            )
            assertTrue(
                "${row.cell.label}：选中墨在指示器板上只有 ${row.onIndicator}:1，有解格就该两块板都读清",
                row.onIndicator >= DesignTokens.WCAG_AA_RATIO,
            )
        }
        for (row in unsolved) {
            assertEquals("${row.cell.label}：无解格交出的是主题那一支本身", row.cell.accent, row.ink)
            assertEquals("${row.cell.label}：无解格的栏体板读数不再是品牌色自己的 2.7725", 2.7725f, row.onBar, 0.01f)
        }
    }

    /**
     * 交出主题那一支的格子分两类，各钉一类：
     *
     * - **本来就达标**：全族 30 格里只有**一格**走到"达标就留着"分支（cardAlpha=1.0 × 内置渐变 ×
     *   深色档，`#AAC7FF` 在指示器板上 4.5959:1）。这条既不许空转（格数 0 ⇒ 解法无条件换色，
     *   品牌色身份丢了），也不许变松（格数变多 ⇒ 判据被改成了"读不清也留着"）。
     * - **无解所以一步不推**（ai/T36）：3 格「深色档 × 极白块」的指示器板上黑白两支都不到 AA
     *   （较优那支也只有 3.8840:1），交出的墨逐字等于传进来的 accent。
     *
     * 两类合起来 4 格，其余 26 格交出的都不是原色那支。
     */
    @Test
    fun theSweepPinsTheUnsolvedCellsAndTheKeptBrandOnes() {
        val readings = SWEEP.map(::measure)
        val keptBecauseReadable = readings.filter { it.ink == it.cell.accent && it.brandOnIndicator >= DesignTokens.WCAG_AA_RATIO }
        assertEquals(
            "达标保留品牌色的格数不再是 1：${keptBecauseReadable.map { it.cell.label }}",
            1,
            keptBecauseReadable.size,
        )
        assertEquals("保留的是这一格", "cardAlpha=1.0 × 内置渐变 × 深色档", keptBecauseReadable[0].cell.label)
        assertEquals("那一格保留的就是主题 primary 本身", DarkBluePrimary, keptBecauseReadable[0].ink)
        assertEquals("那一格在指示器板上 4.5959:1", 4.5959f, keptBecauseReadable[0].onIndicator, 0.01f)
        assertTrue("品牌墨本来就达标却没原样交出", keptBecauseReadable[0].brandOnIndicator >= DesignTokens.WCAG_AA_RATIO)

        val kept = readings.filter { it.ink == it.cell.accent }
        assertEquals("交出主题那一支的格数不再是 4：${kept.map { it.cell.label }}", 4, kept.size)
        assertEquals("其余格子该被解而不是留着", SWEEP.size - 4, readings.size - kept.size)

        // 无解格：黑白两支都不到 AA，于是整条坡道一步不推、交回 accent；格数与归属一起钉
        val dead = readings.filter { it.onIndicator < DesignTokens.WCAG_AA_RATIO }
        assertEquals("无解格数不再是 3：${dead.map { "${it.cell.label}=${it.onIndicator}:1" }}", 3, dead.size)
        assertEquals(
            "无解格不再只有「深色档 × 极白块」这一族：${dead.map { it.cell.label }}",
            setOf(
                "cardAlpha=0.3 × 极白块 × 深色档",
                "cardAlpha=0.88 × 极白块 × 深色档",
                "cardAlpha=1.0 × 极白块 × 深色档",
            ),
            dead.map { it.cell.label }.toSet(),
        )
        for (row in dead) {
            assertTrue("${row.cell.label}：无解格不该是浅色档", row.cell.dark)
            assertEquals("${row.cell.label}：无解格该一步不推、逐字交回 accent", row.cell.accent, row.ink)
            assertEquals("${row.cell.label}：无解格交出的读数不再是品牌色自己的", row.brandOnIndicator, row.onIndicator, 0f)
            val onWashedPlate = { candidate: Color ->
                reads(candidate, row.indicatorPlate, row.cell.dark)
            }
            val bestOfTwo = maxOf(onWashedPlate(ContentLight), onWashedPlate(ContentDark))
            assertTrue(
                "${row.cell.label}：黑白较优也有 $bestOfTwo:1，那这格不再是无解格了（交出的是 ${row.onIndicator}:1）",
                bestOfTwo < DesignTokens.WCAG_AA_RATIO,
            )
            assertTrue(
                "${row.cell.label}：交出的这支只有${row.onIndicator}:1，比不推还差，说明推的方向反了",
                row.onIndicator <= bestOfTwo,
            )
        }
    }

    /**
     * 保住品牌色身份：解出来的墨必须是 `accent → 黑白较优那支` 这条 **256 档坡道**上的一档，
     * 而不是另发明的一支颜色，也不是坡道尽头那支裸候选。
     *
     * 坡道就是 [legibleAccentOn] 走的那一条（step 的粒度是 1/255，交出去之前还过一遍 8 bit
     * 取整），所以这里逐档复算同一条坡道、要求解出的墨**逐字**命中其中一档：这条与 lerp 用哪个
     * 色彩空间无关，也就不必替 Compose 的插值实现背书。实测两件事：
     *
     * - 深色档 15 格全在 `accent → 纯白` 那一条上，浅色档 15 格全在 `accent → #1A1B20` 那一条上；
     *   往白推是通道等比抬升，色相角只从 219.53° 抖到最多 220.00°；往近黑那支推掉的是饱和
     *   （彩度 0.808 → 最低 0.055），色相角走到最多 222.86°——端点自己就是 228° 的蓝，仍在蓝这一族。
     * - 有解的格子全部停在坡道中间（最深的一格 k=242/255）；无解那 3 格停在**起点**（k=0，
     *   逐字就是 accent），ai/T36 收掉的正是"无解还推到尽头交出裸候选"这一条。
     *
     * 判据不许空转：两条坡道各被 15 格命中，交出的墨仍有彩度。
     */
    @Test
    fun solvedInksSitOnTheRampToANeutralEndpoint() {
        val readings = SWEEP.map(::measure)
        var towardLight = 0
        var towardDark = 0
        for (row in readings) {
            val ink = drawn(row.ink)
            val lightRamp = ramp(row.cell.accent, ContentLight)
            val darkRamp = ramp(row.cell.accent, ContentDark)
            // lastIndexOf 而不是 indexOf：8 bit 取整让坡道最后几档撞成同一支色（实测坡道在
            // k=254 就已经是纯白），"推到尽头没有"要按这支色能占到的最深一档算。
            val lightK = lightRamp.lastIndexOf(ink)
            val darkK = darkRamp.lastIndexOf(ink)
            val expectedRamp = if (row.cell.dark) lightK else darkK
            assertTrue(
                "${row.cell.label}：解出的墨 ${hex(ink)} 不在 accent→${if (row.cell.dark) "纯白" else "近黑"} " +
                    "这条保色相的坡道上（往白 k=$lightK / 往近黑 k=$darkK）",
                expectedRamp >= 0,
            )
            if (row.cell.dark) towardLight++ else towardDark++
            val firstK = if (row.cell.dark) lightRamp.indexOf(ink) else darkRamp.indexOf(ink)
            val dead = row.onIndicator < DesignTokens.WCAG_AA_RATIO
            if (dead) {
                assertTrue(
                    "${row.cell.label}：无解格现在停在 k=$expectedRamp（首次命中 k=$firstK），不再是「一步不推」了",
                    firstK == 0,
                )
                assertEquals("${row.cell.label}：无解格该逐字交回 accent", row.cell.accent, row.ink)
            } else {
                assertTrue(
                    "${row.cell.label}：k=$expectedRamp 已经是坡道尽头，这不是「推」而是「换成裸候选」",
                    expectedRamp < RAMP_END,
                )
                assertTrue(
                    "${row.cell.label}：有解却一步没推（k=$firstK 就是 accent 自己），无解判据把它误收了",
                    firstK > 0 || row.brandOnIndicator >= DesignTokens.WCAG_AA_RATIO,
                )
                assertNotEquals("${row.cell.label}：有解却直接交出裸候选", ContentLight, row.ink)
                assertNotEquals("${row.cell.label}：有解却直接交出裸候选", ContentDark, row.ink)
                // 品牌色"还有颜色、还在蓝这一族"：无解那格才允许褪成灰
                val chroma = maxOf(row.ink.red, row.ink.green, row.ink.blue) -
                    minOf(row.ink.red, row.ink.green, row.ink.blue)
                assertTrue("${row.cell.label}：交出的墨已经褪成灰了（彩度 $chroma）", chroma > 0.02f)
                val hue = hueOf(row.ink)
                assertTrue(
                    "${row.cell.label}：色相角 ${hue}° 离开了蓝这一族（accent 是 ${hueOf(row.cell.accent)}°）",
                    hue in BLUE_HUE_MIN..BLUE_HUE_MAX,
                )
            }
        }
        assertEquals("深色档那一族不再全部往白推：只往白推了 $towardLight 格", 15, towardLight)
        assertEquals("浅色档那一族不再全部往近黑推：只往近黑推了 $towardDark 格", 15, towardDark)
    }

    /**
     * 指示器那块板**恒比栏体板难读**，所以两块板各解一次不是重复劳动。
     *
     * 两档主题的 wash 都是朝选中墨那一侧推板（深色档把板提亮、浅色档把板压暗），于是
     * 同一支墨在指示器板上的读数总比栏体板上低：30 格无一例外。反过来（wash 改朝墨的对面
     * 推、或浓度被改动）这条就红。
     */
    @Test
    fun theIndicatorPlateIsAlwaysTheHarderOfTheTwo() {
        val readings = SWEEP.map(::measure)
        for (row in readings) {
            assertTrue(
                "${row.cell.label}：同一支墨在指示器板上 ${row.onIndicator}:1 反而高于栏体板 ${row.onBar}:1，" +
                    "两块板不再是「指示器更难」这一族了",
                row.onIndicator < row.onBar,
            )
            assertEquals(
                "${row.cell.label}：指示器板不再是栏体板被 wash 罩一层的结果",
                compositeLuma(
                    bottomBarIndicatorWash(row.cell.dark).readableLuminance(),
                    row.barPlate,
                    BOTTOM_BAR_INDICATOR_WASH_ALPHA,
                ),
                row.indicatorPlate,
                0f,
            )
        }
    }

    /**
     * 本卡一个 number 都不动 alpha：0.60 天花板照旧，触发色组里没有任何一格超过它。
     *
     * 抬天花板能治这一族，但那条栏悬浮在整屏滚动的课表之上，抬到 AA 需要的 ≈0.74 就是把它
     * 压成实心横带（用户否过两次），所以解的一直是墨。
     */
    @Test
    fun theCeilingStandsAndNoCellPushesPastIt() {
        assertEquals("0.60 天花板被抬了", 0.60f, BOTTOM_BAR_SURFACE_ALPHA_CEILING, 0f)
        val readings = SWEEP.map(::measure)
        for (row in readings) {
            assertTrue(
                "${row.cell.label}：alpha ${row.alpha} 顶穿了天花板，本卡只该解墨不该动板",
                row.alpha <= BOTTOM_BAR_SURFACE_ALPHA_CEILING + 1e-6f,
            )
        }
        assertEquals(
            "深色档 × 极白块 × 0.88 这一格的 alpha 不再是 0.60 那一档",
            0.60f,
            measure(TRIGGER_GRID[1]).alpha,
            0f,
        )
    }

    /**
     * 把两处"反解下限 0.920"的过期数字钉在测量值上（`BottomBarSurfaceAlphaTest` 的注释与
     * [bottomBarSurfaceAlpha] 的 KDoc 写的是这两个数）：编码通道口径下四个角分别是
     * 深色×极白 **0.7367**、深色×极黑 0.3957、浅色×极白 **0.6905**、浅色×极黑 **0.7396**，
     * 全族最难的角是浅色档 × 极黑块那 0.7396。
     *
     * 四角一起钉是因为两支浅色档的下限挨得很近（0.6905 / 0.7396），只钉两角时把"极白"那一支
     * 误抄成"极黑"读不出问题（本卡第一版就抄错了）。
     *
     * 旧线性口径给过 0.920——那是一块画不出来的板（口径见 `GlassPlateDeviceCalibrationTest`）。
     * `BottomBarSurfaceAlphaTest` 里 `floor > 天花板` 那条断言不受影响，钉的是它仍然成立。
     */
    @Test
    fun theMeasuredFloorsReplaceTheStaleNumbers() {
        SceneLuma.wallpaper = WHITE_BLOCK
        val darkWhite = legibilityAlphaFloor(DarkGlassTint, DarkBlueOnSurfaceVariant, darkTheme = true)
        assertEquals("深色档 × 极白块的下限不再是 0.7367", 0.73671573f, darkWhite, 1e-5f)
        assertTrue("下限越顶这一格就还是顶穿天花板那一格（见 BottomBarSurfaceAlphaTest）", darkWhite > BOTTOM_BAR_SURFACE_ALPHA_CEILING)
        val lightWhite = legibilityAlphaFloor(LightGlassTint, BlueOnSurfaceVariant, darkTheme = false)
        assertEquals("浅色档 × 极白块的下限不再是 0.6905", 0.6905225f, lightWhite, 1e-5f)
        assertTrue(lightWhite > BOTTOM_BAR_SURFACE_ALPHA_CEILING)
        SceneLuma.wallpaper = BLACK_BLOCK
        val lightBlack = legibilityAlphaFloor(LightGlassTint, BlueOnSurfaceVariant, darkTheme = false)
        assertEquals("浅色档 × 极黑块的下限不再是 0.7396（这一角才是全族最难读的）", 0.73961425f, lightBlack, 1e-5f)
        assertTrue(lightBlack > BOTTOM_BAR_SURFACE_ALPHA_CEILING)
        val darkBlack = legibilityAlphaFloor(DarkGlassTint, DarkBlueOnSurfaceVariant, darkTheme = true)
        assertEquals("深色档 × 极黑块的下限不再是 0.3957", 0.3956857f, darkBlack, 1e-5f)
        assertTrue("这一角本来就在天花板下面，别把它写成顶穿的那一格", darkBlack < BOTTOM_BAR_SURFACE_ALPHA_CEILING)
    }

    // ---- 接线形状（没有 Compose 运行时，只能按源码核对）----------------------

    /**
     * 选中墨必须与中性墨吃**同样三样输入**，且指示器那层 wash 只有一个真源。
     *
     * - [bottomBarAccentInk] 只许一个调用点，喂进去的是画板那一份 `effectiveContainerAlpha`
     *   与那一帧唯一的 `sceneLuma`（另算一份 alpha / 再读一次全局 = 一块板两个口径，
     *   ai/T25b 第 4 条契约），accent 就是 `scheme.primary`（不是 primaryContainer）；
     * - 函数体自己不许碰 [SceneLuma] 全局或 [com.buaa.schedule.core.designsystem.worstGlassSceneLuma]；
     * - 两行 tab 内容（可见层 + MovingAccent 隐藏层）都得拿到同一支选中墨，否则切换瞬间跳色
     *   （ai/T25b 第 2 条契约），`LocalLiquidBottomTabAccentInk provides` 数 == 2；
     * - 画出来的 wash 与解墨用的 wash 同源：`onDrawSurface` 里那两层各许出现一次
     *   [bottomBarIndicatorWash] + 一次 `copy(alpha = BOTTOM_BAR_INDICATOR_WASH_ALPHA)`，
     *   不许再出现第二个 0.1f 浓度字面量、也不许把深色档那层写回裸的 `Color.White`
     *   （按压变暗那支 `Color.Black.copy(alpha = 0.03f * progress)` 是另一件事，不在管区内）。
     */
    @Test
    fun theAccentInkSharesTheBarsInputsAndTheWashHasOneSource() {
        val tabs = File(rootMainJava(), "com/buaa/schedule/core/designsystem/liquid/LiquidBottomTabs.kt")
        assertTrue("${tabs.path} 读不到，路径不对", tabs.isFile)
        val code = normalized(tabs.readText())

        val definition = ACCENT_DEFINITION.find(code)
        assertTrue("bottomBarAccentInk 的定义没扫到（签名形如 `internal fun bottomBarAccentInk(` 变了），守卫是空的", definition != null)
        val params = definition!!.groupValues[1].replace(Regex("\\s+"), "")
        assertTrue("场景亮度必须是入参，而不是函数自己回头读全局：$params", "sceneLuma:Float" in params)
        assertTrue("accent 必须是入参：$params", "accent:Color" in params)
        assertTrue(
            "bottomBarAccentInk 自己读了场景全局/最差亮度，与栏体那次读不同源",
            "SceneLuma" !in definition.groupValues[2] && "worstGlassSceneLuma" !in definition.groupValues[2],
        )

        val calls = Regex("bottomBarAccentInk\\(([^)]*)\\)", RegexOption.DOT_MATCHES_ALL)
            .findAll(code)
            .map { it.groupValues[1] }
            .filter { " = " in it }
            .toList()
        assertEquals("bottomBarAccentInk 只许有一个调用点：$calls", 1, calls.size)
        assertTrue("选中墨没吃画板那一份 alpha：${calls[0]}", "effectiveAlpha = effectiveContainerAlpha" in calls[0])
        assertTrue("选中墨没吃那一帧唯一的场景亮度：${calls[0]}", "sceneLuma = sceneLuma" in calls[0])
        assertTrue("选中墨该解的是 primary 那一族：${calls[0]}", "accent = scheme.primary" in calls[0])

        assertEquals(
            "两行 tab 内容都得拿到同一支选中墨，否则指示器里的鬼影与外面不一致",
            2,
            Regex("LocalLiquidBottomTabAccentInk\\s+provides").findAll(code).count(),
        )
        // 场景亮度仍然只读一次：本卡新增的那次解墨不该带出第二处读取
        assertEquals("整份文件里场景亮度被读了不止一处", 1, Regex("worstGlassSceneLuma\\(").findAll(code).count())
        assertEquals("bottomBarSceneLuma 只许「定义 + 唯一调用点」两处", 2, Regex("bottomBarSceneLuma\\(").findAll(code).count())

        val drawn = onDrawSurfaceBlock(code)
        assertTrue("指示器 onDrawSurface 没扫到，wash 那条守卫是空的", drawn != null)
        val surface = drawn!!
        assertEquals(
            "两档主题的 wash 都得来自共用的那支 [bottomBarIndicatorWash]",
            2,
            Regex("bottomBarIndicatorWash\\(").findAll(surface).count(),
        )
        assertEquals(
            "那两层 wash 的浓度都得来自共用的那个常量",
            2,
            Regex("copy\\(\\s*alpha\\s*=\\s*BOTTOM_BAR_INDICATOR_WASH_ALPHA\\s*\\)").findAll(surface).count(),
        )
        assertTrue(
            "画的那层里还留着第二个 wash 浓度字面量（改了画出来那层，解墨那边不知道）",
            !Regex("alpha\\s*=\\s*0\\.1\\d*f").containsMatchIn(surface),
        )
        assertTrue(
            "深色档那层 wash 又写成裸的 Color.White 了：与解墨用的那支分家",
            !Regex("Color\\.White").containsMatchIn(surface),
        )

        val main = File(rootMainJava(), "com/buaa/schedule/MainActivity.kt")
        val mainCode = normalized(main.readText())
        val nav = NAV_DEFINITION.find(mainCode)
        assertTrue("NavItemContent 的定义没扫到，守卫是空的", nav != null)
        assertTrue(
            "选中墨要能整格交还给主题，且默认值必须是 null（=底栏没说话）：${nav!!.groupValues[1]}",
            Regex("accentInk\\s*:\\s*Color\\?\\s*=\\s*null") in nav.groupValues[1],
        )
        val flat = nav.groupValues[2].replace(Regex("\\s+"), "")
        assertTrue(
            "local 为 null 时必须逐字用回主题 primary，旧式底栏/宽屏导航栏就被顺带改掉了",
            "accentInk?:MaterialTheme.colorScheme.primary" in flat,
        )
        assertTrue("底栏那条没读 local 的选中墨，而是自己算了一份", "LocalLiquidBottomTabAccentInk" in mainCode)
    }

    // ---- 取值 --------------------------------------------------------------

    private fun measure(cell: Cell): Reading {
        SceneLuma.wallpaper = cell.scene
        val alpha = bottomBarSurfaceAlpha(
            containerAlpha = DesignTokens.CHROME_SURFACE_ALPHA,
            userAlphaScale = DesignTokens.cardAlphaScale(cell.cardAlpha),
            containerColor = cell.plate,
            text = cell.text,
            darkTheme = cell.dark,
        )
        val sceneLuma = bottomBarSceneLuma(cell.plate, cell.text, cell.dark)
        val barPlate = bottomBarPlateLuma(cell.plate, alpha, sceneLuma)
        val indicatorPlate = bottomBarIndicatorPlateLuma(cell.plate, alpha, sceneLuma, cell.dark)
        val ink = bottomBarAccentInk(
            containerColor = cell.plate,
            effectiveAlpha = alpha,
            accent = cell.accent,
            darkTheme = cell.dark,
            sceneLuma = sceneLuma,
        )
        return Reading(
            cell = cell,
            alpha = alpha,
            sceneLuma = sceneLuma,
            barPlate = barPlate,
            indicatorPlate = indicatorPlate,
            brandOnIndicator = reads(cell.accent, indicatorPlate, cell.dark),
            brandOnBar = contrastRatio(barPlate, drawn(cell.accent).readableLuminance()),
            ink = ink,
            onIndicator = reads(ink, indicatorPlate, cell.dark),
            onBar = contrastRatio(barPlate, drawn(ink).readableLuminance()),
        )
    }

    /**
     * 这支墨在指示器那块板上最终被看到的比值：**墨与板一起**被 wash 罩着
     * （指示器画在录制好的整张内容之上）。只把板算进 wash 就是拿一块画不出来的板解墨。
     */
    private fun reads(ink: Color, indicatorPlate: Float, darkTheme: Boolean): Float = contrastRatio(
        indicatorPlate,
        compositeLuma(
            bottomBarIndicatorWash(darkTheme).readableLuminance(),
            drawn(ink).readableLuminance(),
            BOTTOM_BAR_INDICATOR_WASH_ALPHA,
        ),
    )

    /** 一个 Color 经 `toArgb()` 之后真正画出来的那一份（通道取整到 8 bit）。 */
    private fun drawn(color: Color): Color = Color(
        (color.red * 255f + 0.5f).toInt() / 255f,
        (color.green * 255f + 0.5f).toInt() / 255f,
        (color.blue * 255f + 0.5f).toInt() / 255f,
    )

    /**
     * 两支墨画出来之后的**最大通道差**（0..255，取整后逐通道比）：这一族"选中态一眼可辨"
     * 只有这一个可量的口径——差到 9/255 那一级肉眼就是同一支色（P2 那一格量的就是它）。
     */
    private fun maxChannelDiff(a: Color, b: Color): Int {
        val x = drawn(a)
        val y = drawn(b)
        return maxOf(
            kotlin.math.abs(x.red - y.red),
            kotlin.math.abs(x.green - y.green),
            kotlin.math.abs(x.blue - y.blue),
        ).let { (it * 255f + 0.5f).toInt() }
    }

    /**
     * `accent → 裸候选` 这条保色相直线上的 256 档坡道，逐档过一遍 8 bit 取整。
     *
     * 与 [legibleAccentOn] 走的是同一条：step 的粒度是 1/255，交出去之前也过 [drawn]，
     * 所以解出的墨该**逐字**命中其中一档，不需要容差区间。
     */
    private fun ramp(from: Color, to: Color): List<Color> =
        (0..RAMP_END).map { drawn(lerp(from, to, it / 255f)) }

    /**
     * HSL 色相角（0..360，无彩度时给 -1）：这个版本的 Compose 没有公开的 hue 扩展，
     * 手写的口径与 [RAMP_END] 那两条坡道的端点自洽即可——本卡只拿它判「还在不在蓝这一族」。
     */
    private fun hueOf(c: Color): Float {
        val max = maxOf(c.red, c.green, c.blue)
        val min = minOf(c.red, c.green, c.blue)
        val chroma = max - min
        if (chroma < 1e-6f) return -1f
        return when {
            max == c.red -> 60f * ((c.green - c.blue) / chroma)
            max == c.green -> 60f * (2f + (c.blue - c.red) / chroma)
            else -> 60f * (4f + (c.red - c.green) / chroma)
        }.let { if (it < 0f) it + 360f else it }
    }

    private fun hex(color: Color): String = "#%06X".format(
        ((color.red * 255f + 0.5f).toInt() shl 16) or
            ((color.green * 255f + 0.5f).toInt() shl 8) or
            (color.blue * 255f + 0.5f).toInt(),
    )

    /**
     * **指示器**那层的 `onDrawSurface = { ... }`，按花括号配平切：缩进与换行改了也不影响，
     * 而"画出来那层 wash 与解墨用的那支是否同源"只能在这一块里查。
     *
     * 从 `indicatorBackdrop` 那个锚点往后找：本文件里 `onDrawSurface` 有**三处**（可见层、
     * MovingAccent 隐藏层、指示器），从文件头搜会切到可见层那一块，里面本来就没有 wash，
     * 那几条 `count == 2` 的守卫就成了对着空块数的假绿。
     */
    private fun onDrawSurfaceBlock(code: String): String? {
        val anchor = code.indexOf("indicatorBackdrop")
        val start = code.indexOf("onDrawSurface", if (anchor < 0) 0 else anchor)
        if (start < 0) return null
        val open = code.indexOf('{', start)
        if (open < 0) return null
        var depth = 0
        for (i in open until code.length) {
            when (code[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return code.substring(open + 1, i)
                }
            }
        }
        return null
    }

    /** 一个格子：主题档 × 卡片透明度档 × 壁纸档，三样决定后面所有数。 */
    private class Cell(
        val label: String,
        val dark: Boolean,
        val cardAlpha: Float,
        val scene: SceneLuma.Stats,
    ) {
        val plate: Color = if (dark) DarkGlassTint else LightGlassTint
        val text: Color = if (dark) DarkBlueOnSurfaceVariant else BlueOnSurfaceVariant
        val accent: Color = if (dark) DarkBluePrimary else BluePrimary
    }

    /** [measure] 的结果：两块板、改前后各一支墨的读数。 */
    private class Reading(
        val cell: Cell,
        val alpha: Float,
        val sceneLuma: Float,
        val barPlate: Float,
        val indicatorPlate: Float,
        val brandOnIndicator: Float,
        val brandOnBar: Float,
        val ink: Color,
        val onIndicator: Float,
        val onBar: Float,
    )

    /** 触发色组里一格改前/改后的读数（`onIndicator <= 0f` = 这一格无解，不报达标值）。 */
    private class Expect(
        val brand: Float,
        val alpha: Float,
        val hex: String,
        val onIndicator: Float,
        val onBar: Float,
    )

    private companion object {
        val WHITE_BLOCK = SceneLuma.Stats(mean = 0.45f, darkest = 0.02f, brightest = 1.0f)
        val BLACK_BLOCK = SceneLuma.Stats(mean = 0.35f, darkest = 0.0f, brightest = 0.20f)
        val MID_GRAY = SceneLuma.Stats(mean = 0.50f, darkest = 0.50f, brightest = 0.50f)
        val MID_BRIGHT = SceneLuma.Stats(mean = 0.45f, darkest = 0.02f, brightest = 0.35f)
        val SCENES = listOf(SceneLuma.Stats.Unknown, WHITE_BLOCK, BLACK_BLOCK, MID_GRAY, MID_BRIGHT)

        fun sceneLabel(scene: SceneLuma.Stats): String = when (scene) {
            SceneLuma.Stats.Unknown -> "内置渐变"
            WHITE_BLOCK -> "极白块"
            BLACK_BLOCK -> "极黑块"
            MID_GRAY -> "均匀中灰"
            else -> "中等亮块"
        }

        /** 默认档那一格 × 两档主题 × 五档壁纸 = 本卡的触发色组（10 格，顺序与 [BEFORE] 逐字对应） */
        val TRIGGER_GRID = listOf(true, false).flatMap { dark ->
            SCENES.map { scene ->
                Cell(
                    label = "cardAlpha=0.88 × ${sceneLabel(scene)} × ${if (dark) "深色档" else "浅色档"}",
                    dark = dark,
                    cardAlpha = 0.88f,
                    scene = scene,
                )
            }
        }

        /** 与 `BottomBarInkTest.inkStaysLegibleAcrossTheWholeFamily` 同一张网：三档透明度 × 五档壁纸 × 两档主题 */
        val SWEEP = listOf(0.3f, 0.88f, 1.0f).flatMap { cardAlpha ->
            SCENES.flatMap { scene ->
                listOf(
                    Cell("cardAlpha=$cardAlpha × ${sceneLabel(scene)} × 深色档", true, cardAlpha, scene),
                    Cell("cardAlpha=$cardAlpha × ${sceneLabel(scene)} × 浅色档", false, cardAlpha, scene),
                )
            }
        }

        /**
         * 触发色组 10 格的实测表（顺序与 [TRIGGER_GRID] 逐字对应）：
         * 改前品牌色在指示器板上的读数、这一格的 alpha、解出的墨、它在两块板上的读数。
         */
        val BEFORE = listOf(
            Expect(4.4953f, 0.20f, "#ABC7FF", 4.5025f, 5.6961f), // 深色 × 内置渐变
            Expect(2.4161f, 0.60f, "#AAC7FF", -1f, 2.7725f), // 深色 × 极白块：无解那格，一步不推交回 accent（负数 = 不许谎称达标）
            Expect(3.6500f, 0.3956857f, "#CEDFFF", 4.5034f, 5.6870f), // 深色 × 极黑块
            Expect(3.4187f, 0.60f, "#D9E7FF", 4.5057f, 5.6776f), // 深色 × 均匀中灰
            Expect(3.6500f, 0.554347f, "#CEDFFF", 4.5034f, 5.6870f), // 深色 × 中等亮块
            Expect(2.5496f, 0.20f, "#224D8C", 4.5077f, 4.9417f), // 浅色 × 内置渐变
            Expect(1.7113f, 0.60f, "#20304C", 4.5219f, 5.1657f), // 浅色 × 极白块
            Expect(1.4248f, 0.60f, "#1B1F29", 4.5406f, 5.3166f), // 浅色 × 极黑块
            Expect(2.5496f, 0.20f, "#224D8C", 4.5077f, 4.9417f), // 浅色 × 均匀中灰
            Expect(1.7113f, 0.60f, "#20304C", 4.5219f, 5.1657f), // 浅色 × 中等亮块
        )

        /** 坡道的最后一档：命中它就是「换成裸候选」而不是「推」 */
        const val RAMP_END = 255

        /** 解出的墨仍算蓝这一族：#1A73E8 是 214.09°，#AAC7FF 是 219.53°，推到底也不许离开这段 */
        const val BLUE_HUE_MIN = 200f
        const val BLUE_HUE_MAX = 235f

        val ACCENT_DEFINITION = Regex(
            "internal fun bottomBarAccentInk\\(([^)]*)\\)\\s*:\\s*Color\\s*\\{(.*?)\\n\\}",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )
        val NAV_DEFINITION = Regex(
            "fun ColumnScope\\.NavItemContent\\(([^)]*)\\)\\s*\\{(.*?)\\n}",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )

        /** 仓库里是 CRLF：先把行尾归一，`$` 与 `\n` 才按 Kotlin 里写的那样工作 */
        fun normalized(src: String): String = blankComments(src).replace("\r\n", "\n")

        fun rootMainJava(): File {
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

        /** 抹掉 `//` 与 `/* */` 注释的内容，长度与换行不动：KDoc 里写着 `Color.White` 不该被算成画的那层 */
        fun blankComments(src: String): String {
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
                        var j = i + 2
                        while (j < out.size && !src.startsWith("*/", j)) j++
                        val end = (j + 2).coerceAtMost(out.size)
                        for (k in i until end) if (out[k] != '\n') out[k] = ' '
                        i = end
                    }

                    else -> i++
                }
            }
            return String(out)
        }
    }
}
