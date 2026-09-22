package com.buaa.schedule.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 顶栏「学期统计」入口宽度预算内核的表驱动单测（T69）。
 *
 * 打的是这张表的每一格边界：`<=` 取等号那一档（**预算恰好等于实宽**）、差 1px 那一档、
 * 一枚都放不下那一档、预算还没量到那一档、空表那一档，以及"偏好顺序不是宽度顺序"
 * 这一档（内核按声明顺序走，不重排 —— 重排了就把产品定的降级顺序换成了按宽度挑）。
 *
 * 表里的宽度全是**合成整数**，不是装机实测数：内核吃的就是"调用点量好的整数"，
 * 这里再掺进任何一枚真机数字，就等于把两条不相干的账绑在一起钉。
 * 装机实宽（density 2.625 / fontScale 1.0 那一档）由 `StatsEntryWiringGuardTest`
 * 钉"调用点确实用 TextMeasurer 量的"，数值本身记在 docs/STATUS.md 的 T69 一节。
 */
class StatsEntryPolicyTest {

    // ---- ① 三档形状：全都带文字标签，顺序就是偏好顺序 ----

    @Test
    fun everyTierCarriesATextLabelBecauseBareIconsAreRejected() {
        for (tier in StatsEntryTier.entries) {
            assertTrue(
                "「${tier.name}」这一档没有文字标签：用户要的是看得出里面有什么，裸图标不合格",
                tier.label.isNotBlank(),
            )
        }
    }

    @Test
    fun declarationOrderIsThePreferenceOrder() {
        assertEquals(
            "降级顺序写死了是 学期统计 → 图标+统计 → 统计（内核按声明顺序取第一枚放得下的）",
            listOf(StatsEntryTier.Full, StatsEntryTier.IconCompact, StatsEntryTier.Compact),
            StatsEntryTier.entries.toList(),
        )
        assertTrue("只有中间那一档带图标", StatsEntryTier.entries.count { it.withIcon } == 1)
        assertEquals(StatsEntryTier.IconCompact, StatsEntryTier.entries.first { it.withIcon })
    }

    @Test
    fun talkBackAlwaysHearsTheFullName() {
        for (tier in StatsEntryTier.entries) {
            assertEquals(
                "缩到「统计」那一档以后，画面上已经没有答案了，读屏必须还念得出全称：${tier.name}",
                "学期统计", tier.contentDescription,
            )
        }
    }

    // ---- ② 候选表：每档的宽度 = 它自己的文字 + 它自己的内衬 ----

    /** 合成宽度：四字 112px、二字 56px、内衬 63px、图标块 53px、最小触控 126px */
    private fun table(
        fullPx: Int = 112,
        shortPx: Int = 56,
        paddingPx: Int = 63,
        iconPx: Int = 53,
        minPx: Int = 126,
    ): List<StatsEntryCandidate> = statsEntryCandidates(fullPx, shortPx, paddingPx, iconPx, minPx)

    @Test
    fun candidateWidthsUseThatTiersOwnLabelAndOwnChrome() {
        val byTier = table().associate { it.tier to it.widthPx }
        assertEquals("满档 = 四字 + 内衬", 112 + 63, byTier[StatsEntryTier.Full])
        assertEquals("图标档 = 二字 + 内衬 + 图标块", 56 + 63 + 53, byTier[StatsEntryTier.IconCompact])
        assertEquals("末档 = 二字 + 内衬", 56 + 63, byTier[StatsEntryTier.Compact])
    }

    @Test
    fun candidateListCoversEveryTierExactlyOnce() {
        assertEquals(StatsEntryTier.entries.toList(), table().map { it.tier })
    }

    @Test
    fun minTouchWidthFloorsATinyLabel() {
        // 系统字号调到最小、量出来只有 20px：胶囊不许跟着缩成一颗点不到的东西
        val widths = table(fullPx = 20, shortPx = 10, paddingPx = 4, iconPx = 4, minPx = 126)
        assertEquals(listOf(126, 126, 126), widths.map { it.widthPx })
    }

    @Test
    fun ladderIsNarrowingInPreferenceOrderWhenMeasuredNormally() {
        // 正常量出来时三档必须一路变窄，否则"降级"买到的是更宽的东西（图标档换掉的是两个全角字）
        val widths = table(fullPx = 112, shortPx = 56, paddingPx = 63, iconPx = 53, minPx = 40)
            .map { it.widthPx }
        assertEquals(widths.sortedDescending(), widths)
    }

    // ---- ③ 预算：三段实测做一次减法 ----

    @Test
    fun budgetSubtractsNeighboursAndReservedGap() {
        // 行宽 1017 - 分段控件 368 - 左列 216 - 两侧留白 42 = 391（装机那一档的形状，数字是合成的）
        assertEquals(391, statsEntryBudgetPx(rowWidthPx = 1017, segmentedWidthPx = 368, leftColumnWidthPx = 216, reservedGapPx = 42))
    }

    @Test
    fun budgetIsUnknownWhenAnyFactIsMissing() {
        val base = mapOf(
            "rowWidthPx" to 1017,
            "segmentedWidthPx" to 368,
            "leftColumnWidthPx" to 216,
        )
        for (missing in base.keys) {
            val row = base["rowWidthPx"]?.takeIf { missing != "rowWidthPx" }
            val seg = base["segmentedWidthPx"]?.takeIf { missing != "segmentedWidthPx" }
            val col = base["leftColumnWidthPx"]?.takeIf { missing != "leftColumnWidthPx" }
            assertEquals(
                "$missing 还没量到，预算就不该有个数（猜一个常数就是下一笔 T48）",
                null,
                statsEntryBudgetPx(row, seg, col, reservedGapPx = 42),
            )
        }
    }

    @Test
    fun budgetMayGoNegativeAndMayBeExactlyZero() {
        assertEquals(0, statsEntryBudgetPx(600, 368, 216, 16))
        assertEquals(-1, statsEntryBudgetPx(599, 368, 216, 16))
    }

    // ---- ④ 挑档：边界逐格钉 ----

    private val ladder = listOf(
        StatsEntryCandidate(StatsEntryTier.Full, 240),
        StatsEntryCandidate(StatsEntryTier.IconCompact, 172),
        StatsEntryCandidate(StatsEntryTier.Compact, 119),
    )

    @Test
    fun budgetExactlyEqualWidthFitsThatTier() {
        for (candidate in ladder) {
            assertEquals(
                "预算恰好等于实宽就是放得下（Compose 的约束判定也是 <=，写成 < 会凭空降一档）",
                StatsEntryPlacement(candidate.tier, fits = true),
                planStatsEntry(candidate.widthPx, ladder),
            )
        }
    }

    @Test
    fun onePixelShortDropsOneTier() {
        assertEquals(
            StatsEntryPlacement(StatsEntryTier.IconCompact, fits = true),
            planStatsEntry(240 - 1, ladder),
        )
        assertEquals(
            StatsEntryPlacement(StatsEntryTier.Compact, fits = true),
            planStatsEntry(172 - 1, ladder),
        )
    }

    @Test
    fun generousBudgetTakesTheFirstPreference() {
        assertEquals(StatsEntryPlacement(StatsEntryTier.Full, fits = true), planStatsEntry(9999, ladder))
        assertEquals(StatsEntryPlacement(StatsEntryTier.Full, fits = true), planStatsEntry(240, ladder))
    }

    @Test
    fun nothingFitsNarrowestTierAndSaysSo() {
        assertEquals(StatsEntryPlacement(StatsEntryTier.Compact, fits = false), planStatsEntry(118, ladder))
        assertEquals(StatsEntryPlacement(StatsEntryTier.Compact, fits = false), planStatsEntry(0, ladder))
        assertEquals(StatsEntryPlacement(StatsEntryTier.Compact, fits = false), planStatsEntry(-40, ladder))
    }

    @Test
    fun unknownBudgetErrsTowardTakingLessSpace() {
        assertEquals(
            "首帧没量到就按满幅承诺，那一帧就会把同行的日期裁掉半截",
            StatsEntryPlacement(StatsEntryTier.Compact, fits = false),
            planStatsEntry(null, ladder),
        )
    }

    @Test
    fun preferenceOrderBeatsWidthOrder() {
        // 故意把表排成"第一枚最窄"：内核仍按声明/传入顺序取第一枚放得下的，不重排
        val inverted = listOf(
            StatsEntryCandidate(StatsEntryTier.Full, 100),
            StatsEntryCandidate(StatsEntryTier.IconCompact, 400),
            StatsEntryCandidate(StatsEntryTier.Compact, 50),
        )
        assertEquals(StatsEntryPlacement(StatsEntryTier.Full, fits = true), planStatsEntry(100, inverted))
        // 而"挤不下"时该挤的是自己：返回最窄的那一枚（Compact 50），不是列表末尾，也不是满档
        assertEquals(StatsEntryPlacement(StatsEntryTier.Compact, fits = false), planStatsEntry(49, inverted))
    }

    @Test
    fun tiesGoToTheEarlierTier() {
        val tied = listOf(
            StatsEntryCandidate(StatsEntryTier.Full, 120),
            StatsEntryCandidate(StatsEntryTier.IconCompact, 120),
            StatsEntryCandidate(StatsEntryTier.Compact, 120),
        )
        assertEquals(StatsEntryPlacement(StatsEntryTier.Full, fits = true), planStatsEntry(120, tied))
        assertEquals(StatsEntryPlacement(StatsEntryTier.Full, fits = false), planStatsEntry(119, tied))
    }

    @Test
    fun emptyTableDegradesInsteadOfThrowing() {
        // 顶栏抛异常 = 整张课表没了，比降一档大得多
        assertEquals(StatsEntryPlacement(StatsEntryTier.Compact, fits = false), planStatsEntry(500, emptyList()))
        assertEquals(StatsEntryPlacement(StatsEntryTier.Compact, fits = false), planStatsEntry(null, emptyList()))
    }

    // ---- ⑤ 内核纯度：这张表要在 JVM 里裸跑 ----

    @Test
    fun kernelReadsNoDeviceFactsItself() {
        val text = statsEntryPolicySource()
        val offenders = text.lines().map { it.trimStart() }
            .filter { it.startsWith("import android") || it.startsWith("import androidx") }
        assertTrue(
            "StatsEntryPolicy.kt 混进设备依赖就没法在 JVM 单测里裸跑（宽度全得当参数传进来）：\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
        // 表现层/设备侧事实只能从参数进来：出现在代码里（注释已被抹掉）就是内核自己伸手去读
        val reaching = listOf(
            "LocalConfiguration", "screenWidthDp", "LocalDensity", "DisplayMetrics", "MaterialTheme",
            "LocalContext", "TextMeasurer", "DesignTokens", "@Composable", "toPx()", "ceil(",
            "LocalWindowInfo", "fontScale", "RoundedCornerShape", "Modifier",
        ).flatMap { name ->
            text.lines().map { it.trim() }.filter { it.contains(name) }.map { "$name -> $it" }
        }
        assertTrue("判据内核自己读了设备/表现层事实，参数那一维就成了摆设：\n" + reaching.joinToString("\n"), reaching.isEmpty())
        assertFalse("内核不许读时钟", text.lines().any { it.contains("System.currentTimeMillis") || it.contains("LocalDate.now(") })
    }

    /** 读内核源码并把注释抹成空格（注释里就会写「不读 LocalDensity」这类禁令本身） */
    private fun statsEntryPolicySource(): String {
        val file = java.io.File(findMainJavaDir(), "com/buaa/schedule/ui/home/StatsEntryPolicy.kt")
        assertTrue("找不到 StatsEntryPolicy.kt：挪过家的话这条守卫要跟着改路径", file.isFile)
        return blankComments(file.readText())
    }

    private fun findMainJavaDir(): java.io.File {
        var dir: java.io.File? = java.io.File("").absoluteFile
        repeat(5) {
            val hit = listOf("src/main/java", "app/src/main/java")
                .map { java.io.File(dir, it) }
                .firstOrNull { it.isDirectory }
            if (hit != null) return hit
            dir = dir?.parentFile
        }
        throw IllegalStateException("找不到 app/src/main/java：当前目录 ${java.io.File("").absolutePath}")
    }

    /** 把 `//` 与 `/* */` 注释抹成空格（字符串保留、长度与换行位置不变），刀法同各 *WiringGuardTest */
    private fun blankComments(src: String): String {
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
                    i = j
                }

                else -> i++
            }
        }
        return String(out)
    }
}
