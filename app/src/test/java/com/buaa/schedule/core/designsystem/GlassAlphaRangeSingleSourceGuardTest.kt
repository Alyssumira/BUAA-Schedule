package com.buaa.schedule.core.designsystem

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T215③-A：静态守卫 —— `glass_alpha` 那枚定义域**只许有一把尺**（卡面 §2 后半：
 * 「A 那一枚的区间来源被钉住，不许消费侧与读盘侧各写一份字面量」）。
 *
 * 夹取本身落在读盘处（`Personalization.kt` 文件末那枚 `cardAlphaFromDisk`），区间是三枚编译期常量
 * [Personalization.MIN_CARD_ALPHA] / [Personalization.MAX_CARD_ALPHA] / [Personalization.DEFAULT_CARD_ALPHA]。
 * 本卡只许改 Personalization.kt 一颗文件，所以那三处**写着同一对端点的字面量**（滑条、课程卡、
 * 倍率除数）不能换成型 `Personalization.MIN_CARD_ALPHA` —— 换不了就用守卫对齐：
 * 从三颗消费侧文件里把字面量**抠出数值**来，与常量的数值比，谁漂谁红。
 * 这与"两把尺各量各的"的区别在于：漂了会响，而不是各自安静地长成两个数。
 *
 * ## 四家钉法逐格点名（卡面 §2 要求）
 *
 * - **逐字节**：原文针（`const val MIN_CARD_ALPHA = 0.3f` 那一行、滑条那句 `valueRange = 0.3f..1f`、
 *   课程卡那句 `((Personalization.cardAlpha - 0.3f) / 0.7f)`、倍率那一整行）—— 改一个字符就红；
 * - **数出现次数**（抹注释后）：枚数尺（`0.88f` 在 Personalization.kt 只许一枚、
 *   `cardAlphaFromDisk(` 全仓只许两枚 = 定义 + 调用点）；
 * - **读形状**：按函数体/属性声明切块再判（读盘那一行必须"取数 + 走判据"连成一气、
 *   判据函数体必须是 `if (isFinite()) coerceIn(min, max) else fallback` 那一形）；
 * - **数出现次数（全 main 册子）**：树扫（`Personalization.cardAlpha` 的读者册子、
 *   `glass_alpha` 的读者册子）—— 抓"第二枚文件里冒出来的调用点"，那是前三家都看不见的形状。
 *
 * ⚠️ 本卡不许改那三颗消费侧文件，所以这里钉的是**数值对齐**而不是"字面量已经消失"；
 * 将来把 0.3f/0.7f/0.88f 换成符号时，这几格会先红一次（针断了），那时按新形状重钉。
 */
class GlassAlphaRangeSingleSourceGuardTest {

    /**
     * 区间本体（钉法 = **逐字节** + **数出现次数** + **读形状**）：定义域是且仅是这三枚常量，
     * 而且它们组得出一个**非空、含默认档**的区间。
     *
     * `MIN < MAX` 这一手是给 T22 那一族上锁的：`coerceIn` 在 `min > max` 时当场抛，
     * 而读盘处交进 `coerceIn` 的正是这两枚常量 —— 只要它们没被写成反向的，那条链上就没有空区间的载体。
     * `MIN <= DEFAULT <= MAX` 是给"默认档落在域外"上锁：那样每次缺键都会落成一枚"越界又被夹回来"的数,
     * 读盘与内存两条初值路径就不再同值。
     */
    @Test
    fun theDomainIsThreeNamedConstantsAndTheirNumbersStillMakeOneNonEmptyInterval() {
        val source = blankCommentsKeepingLiterals(readMainSource(PERSONALIZATION))
        for (needle in listOf(DECL_MIN, DECL_MAX, DECL_DEFAULT)) {
            assertEquals("钉法 = 逐字节：区间常量「$needle」不再是恰好一枚定义：", 1, occurrences(source, needle))
        }
        val min = floatOf(source, "MIN_CARD_ALPHA")
        val max = floatOf(source, "MAX_CARD_ALPHA")
        val default = floatOf(source, "DEFAULT_CARD_ALPHA")
        assertEquals("实测常量 MIN_CARD_ALPHA 的数值漂了", 0.3f, min, 0f)
        assertEquals("实测常量 MAX_CARD_ALPHA 的数值漂了", 1f, max, 0f)
        assertEquals("实测常量 DEFAULT_CARD_ALPHA 的数值漂了", 0.88f, default, 0f)
        assertTrue("区间空了（$min > $max）—— 读盘处那枚 coerceIn 当场就是 T22 那枚 FATAL", min < max)
        assertTrue("默认档落在域外（$min..$max 不含 $default）—— 缺键与越界两档从此交出不同的数",
            default in min..max)
        // 钉法 = 数出现次数：同一枚数不许在同一个文件里有第二份字面量
        assertEquals(
            "钉法 = 数出现次数（抹注释后）：`0.88f` 在 Personalization.kt 里必须只剩 DEFAULT_CARD_ALPHA 那一枚 —— " +
                "第二枚就是「默认档有两把尺」（属性初值或读盘默认值抄了字面量），改一处漏一处",
            ONE_DEFAULT_LITERAL,
            occurrences(source, "0.88f"),
        )
        assertEquals(
            "钉法 = 数出现次数（抹注释后）：`0.3f` 在这颗文件里只剩 MIN_CARD_ALPHA 那一枚",
            1,
            occurrences(source, "0.3f"),
        )
    }

    /**
     * 读盘那一行与判据函数（钉法 = **逐字节** + **读形状** + **数出现次数**）：
     * `load` 交出去的数必须**经过那枚具名判据**，"摘掉夹取"必须在这一格红。
     *
     * 这一格是卡面 §3「朝宽：把夹取摘掉必须红」的落点 —— 表驱动单测测的是判据函数自己,
     * 判据函数与 `load` 之间那一根线只有源码形状守得住（本模块没有 Robolectric，
     * `load()` 要 Context，跑不了）。
     */
    @Test
    fun theReadSiteRunsTheNamedKernelAndTheUnclampedFormIsGone() {
        val source = blankCommentsKeepingLiterals(readMainSource(PERSONALIZATION))
        assertEquals(
            "钉法 = 逐字节：读盘那一行不再是「取数 + 走判据」连成一气（现针见本格常量）—— " +
                "少了 `.cardAlphaFromDisk()` 就是本卡修的病灶复发：盘上写多少就用多少",
            1,
            occurrences(source, LOAD_SITE),
        )
        assertEquals(
            "钉法 = 数出现次数（抹注释后）：改前那一形「cardAlpha = prefs.getFloat(\"glass_alpha\", 0.88f)」" +
                "必须 0 处 —— 它回来了就是夹取被摘掉（卡面 §3 朝宽那一格）",
            0,
            occurrences(source, OLD_RAW_SITE),
        )
        assertEquals("钉法 = 逐字节：判据函数的签名一行只许一枚：", 1, occurrences(source, KERNEL_HEAD))
        assertEquals(
            "钉法 = 读形状：判据函数的本体不再是「有限数才夹、非有限数回默认」那一形" +
                "（现体：" + kernelBody(source) + "）—— 摘掉 isFinite 那一手，NaN 就顺着 coerceIn 穿出去" +
                "（穿不穿得出去是实测格，钉在 GlassAlphaDiskClampTest 的 coerceIn 那一格）",
            KERNEL_BODY,
            kernelBody(source),
        )
        for (needle in listOf(KERNEL_MIN_ARG, KERNEL_MAX_ARG, KERNEL_DEFAULT_ARG)) {
            assertEquals(
                "钉法 = 逐字节：判据函数的默认实参不再点名区间常量「$needle」—— 换成字面量就是" +
                    "读盘侧第二份区间数字，本卡的主判据（一把尺）当场破",
                1,
                occurrences(source, needle),
            )
        }
        assertEquals(
            "钉法 = 数出现次数：`cardAlphaFromDisk(` 在这颗文件里恰两枚（定义 + load 的调用点）—— " +
                "第三枚就是第二个走这枚判据的地方（多半是 save 侧被顺手补了一手，那要重判「幂等」那一格）",
            2,
            occurrences(source, "cardAlphaFromDisk("),
        )
        assertEquals(
            "钉法 = 数出现次数（全 main 册子）：调用这枚判据的文件只许 Personalization.kt 自己",
            listOf(PERSONALIZATION),
            mainFilesMentioning("cardAlphaFromDisk"),
        )
    }

    /**
     * 内存初值与盘上默认值同源（钉法 = **逐字节** + **数出现次数**）：
     * `Personalization` 造出来与从盘上读回来，必须是同一个数，否则"没存过"与"存过默认值"两档不同形。
     */
    @Test
    fun theMemorySeedAndTheDiskDefaultAreTheSameConstantNotTwoCopiesOfANumber() {
        val source = blankCommentsKeepingLiterals(readMainSource(PERSONALIZATION))
        assertEquals("钉法 = 逐字节：属性初值必须走 DEFAULT_CARD_ALPHA：", 1, occurrences(source, SEED_SITE))
        assertEquals(
            "钉法 = 数出现次数：`mutableFloatStateOf(0.88f)` 那一形必须 0 处 —— 它就是初值抄的第二份字面量",
            0,
            occurrences(source, "mutableFloatStateOf(0.88f)"),
        )
        assertEquals(
            "钉法 = 数出现次数：读盘默认值那一手也必须走常量（`prefs.getFloat(\"glass_alpha\", DEFAULT_CARD_ALPHA)`）",
            1,
            occurrences(source, "prefs.getFloat(\"glass_alpha\", DEFAULT_CARD_ALPHA)"),
        )
    }

    /**
     * 两枚改不了的消费侧（钉法 = **逐字节** + **读形状**取数值再比）：
     * 滑条的 `valueRange` 与课程卡那根线性映射的两个端点，数值上必须仍等于常量那一档。
     *
     * 这一格是"不许消费侧与读盘侧各写一份字面量"在本卡约束下的**唯一可行钉法**：
     * 字面量还在那两颗文件里（本卡不许改），但它们一旦漂（有人把滑条改成 0.2f..1f、
     * 或把课程卡的 0.7f 分母改了），这里立刻红 —— 而不是让读盘夹取与消费侧长成两个区间。
     */
    @Test
    fun theSliderAndTheCourseCardStillCarryTheSameTwoEndpointsAsTheConstants() {
        val min = floatOf(blankCommentsKeepingLiterals(readMainSource(PERSONALIZATION)), "MIN_CARD_ALPHA")
        val max = floatOf(blankCommentsKeepingLiterals(readMainSource(PERSONALIZATION)), "MAX_CARD_ALPHA")
        val screen = blankCommentsKeepingLiterals(readMainSource(SETTINGS_SCREEN))
        assertEquals(
            "钉法 = 逐字节：透明度滑条那句 `valueRange = 0.3f..1f` 漂了（这颗文件里现在 0 枚）—— " +
                "它是运行期唯一能写 cardAlpha 的入口，定义域换了档就得回 Personalization 的三枚常量一起改",
            1,
            occurrences(screen, SLIDER_RANGE),
        )
        val sliderEnds = SLIDER_RANGE.split("..").let { it[0].dropWhile { c -> !c.isDigit() } to it[1] }
        val sliderMin = num(sliderEnds.first)
        val sliderMax = num(sliderEnds.second)
        assertEquals("读形状取数：滑条下界与 MIN_CARD_ALPHA 不再同值", min, sliderMin, 0f)
        assertEquals("读形状取数：滑条上界与 MAX_CARD_ALPHA 不再同值", max, sliderMax, 0f)
        val week = blankCommentsKeepingLiterals(readMainSource(WEEK_VIEW))
        assertEquals(
            "钉法 = 逐字节：课程卡 tintAlpha 那根映射的原文漂了（这颗文件里现在 0 枚）",
            1,
            occurrences(week, COURSE_CARD_MAPPING),
        )
        val parts = num(COURSE_CARD_MAPPING.substringAfter("- ").substringBefore(") /"))
        val span = num(COURSE_CARD_MAPPING.substringAfter("/ ").substringBefore(")"))
        assertEquals("读形状取数：那根映射的左端点不再是 MIN_CARD_ALPHA", min, parts, 0f)
        assertEquals("读形状取数：分母 0.7f 不再等于 MAX − MIN（区间被改过，映射没跟着改）", max - min, span, 1e-6f)
    }

    /**
     * 倍率那一家（钉法 = **逐字节** + **读形状**取数再算）：除数必须仍是默认档，
     * 而它的两个饱和点必须**包住**这一档定义域 —— 这正是"夹在 0.3–1.0 不产生死区"的算术凭据。
     */
    @Test
    fun theScaleDivisorIsTheSameDefaultAndItsSaturationWindowWrapsTheWholeDomain() {
        val tokens = blankCommentsKeepingLiterals(readMainSource(DESIGN_TOKENS))
        assertEquals("钉法 = 逐字节：倍率那一整行漂了（这颗文件里现在 0 枚）", 1, occurrences(tokens, SCALE_KERNEL))
        val divisor = num(SCALE_KERNEL.substringAfter("userAlpha / ").substringBefore(")"))
        val windowLow = num(SCALE_KERNEL.substringAfter("coerceIn(").substringBefore(","))
        val windowHigh = num(SCALE_KERNEL.substringAfterLast(", ").substringBeforeLast(")"))
        val default = floatOf(blankCommentsKeepingLiterals(readMainSource(PERSONALIZATION)), "DEFAULT_CARD_ALPHA")
        val min = floatOf(blankCommentsKeepingLiterals(readMainSource(PERSONALIZATION)), "MIN_CARD_ALPHA")
        val max = floatOf(blankCommentsKeepingLiterals(readMainSource(PERSONALIZATION)), "MAX_CARD_ALPHA")
        assertEquals("读形状取数：cardAlphaScale 的除数不再等于 DEFAULT_CARD_ALPHA（「默认 = 1.0 倍」那条口径断了）",
            default, divisor, 0f)
        assertTrue(
            "倍率的饱和窗口不再包住定义域（下界 $windowLow × $divisor = ${windowLow * divisor} 已在 MIN $min 右边）—— " +
                "那样把读盘夹到 0.3 就会与倍率的死区撞在一起，滑条左半段从此拖了没反应",
            windowLow * divisor <= min,
        )
        assertTrue(
            "倍率的饱和窗口不再包住定义域（上界 $windowHigh × $divisor = ${windowHigh * divisor} 已在 MAX $max 左边）—— " +
                "同上，只是右半段",
            windowHigh * divisor >= max,
        )
        assertEquals(
            "钉法 = 数出现次数（全 main 册子）：念这枚倍率的 main 文件枚数变了（实到 " +
                "${mainFilesMentioning("cardAlphaScale").size}）—— 多一家就是多一处按自己的口径吃 cardAlpha",
            SCALE_READERS,
            mainFilesMentioning("cardAlphaScale").size,
        )
    }

    /**
     * 读者与写者的册子（钉法 = **数出现次数（全 main 册子）** + **数出现次数**）：
     * `Personalization.cardAlpha` 今天六枚文件、七处引用，其中**写**只有一枚（设置页松手那一手）。
     *
     * 两头都钉：多一枚读者 = 多一处按自己的方式吃这枚数（本卡的区间依据清单要重算）；
     * 多一枚写者 = 出现第二条能塞越界值进内存的路（读盘处的夹取盖不住它，那一格就得改成消费侧夹）。
     */
    @Test
    fun theReaderLedgerAndTheSingleWriterAreStillWhatTheRangeWasDerivedFrom() {
        assertEquals(
            "钉法 = 数出现次数（全 main 册子）：提到 `Personalization.cardAlpha` 的文件册子变了（实到 " +
                "${mainFilesMentioning("Personalization.cardAlpha")}）",
            ALPHA_READERS.sorted(),
            mainFilesMentioning("Personalization.cardAlpha"),
        )
        val screen = blankCommentsKeepingLiterals(readMainSource(SETTINGS_SCREEN))
        assertEquals(
            "钉法 = 数出现次数：全仓唯一那枚写点（滑条松手）变了 —— 第二枚写点必须回来看"
                + "本卡为什么把夹取放在读盘处（那是「内存态只能由有界的入口写」这条前提的另一半）",
            1,
            occurrences(screen, "Personalization.cardAlpha = cardAlpha"),
        )
        var mentions = 0
        for (file in mainFilesMentioning("Personalization.cardAlpha")) {
            mentions += occurrences(blankCommentsKeepingLiterals(readMainSource(file)), "Personalization.cardAlpha")
        }
        assertEquals(
            "钉法 = 数出现次数：`Personalization.cardAlpha` 的引用总数不再是 7（六枚读者 + 一枚写点；" +
                "上格的册子只说「哪些文件」，这一枚说「各读了几遍」）",
            ALPHA_MENTIONS,
            mentions,
        )
    }

    // ---- 源码核对小工具（与 ui/CalendarSyncTargetPairingGuardTest 同一把刀）----

    /** 从 `const val <name> = 0.3f` 那一行把数值抠出来（抠不出来就抛，绝不静默交回 0f） */
    private fun floatOf(source: String, name: String): Float {
        val match = Regex("const val $name = ([0-9.eE+-]+)f").find(source)
        check(match != null) { "抠不出 `const val $name` 的数值：声明换了形状，本守卫要跟着改" }
        return match.groupValues[1].toFloat()
    }

    /** 判据函数的本体：单表达式函数，取 `): Float =` 之后到行尾那一段 */
    private fun kernelBody(source: String): String {
        val at = source.indexOf(KERNEL_HEAD)
        check(at >= 0) { "找不到判据函数的签名「$KERNEL_HEAD」：它挪过家或改了名" }
        val marker = source.indexOf("): Float =", at)
        check(marker > at) { "判据函数不再是单表达式那一形（找不到 `): Float =`）：本体换了形状要回来重钉" }
        return source.substring(marker + "): Float =".length).lineSequence().first().trim()
    }

    /** 字面量抠数：`0.3f` → 0.3f（本仓的浮点字面量一律带 f 尾巴，掉了就抛） */
    private fun num(text: String): Float {
        check(text.endsWith("f")) { "抠出来的字面量「$text」不带 f 尾巴：形状与预期不符，本守卫要重看" }
        return text.dropLast(1).toFloat()
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

    private fun readMainSource(relativeFromMainJava: String): String {
        val file = File(findMainJavaDir(), relativeFromMainJava)
        assertTrue("找不到 ${file.path}：文件挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText()
    }

    private fun mainFilesMentioning(needle: String): List<String> {
        val root = findMainJavaDir()
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("${root.path} 下一个 .kt 都没有，扫描路径不对", files.isNotEmpty())
        return files.filter { needle in blankCommentsKeepingLiterals(it.readText()) }
            .map { it.relativeTo(root).path.replace('\\', '/') }
            .sorted()
    }

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

    /** 只抹注释、保留字符串字面量内容，长度与换行位置不变（行号可信）；同族先例同名工具 */
    private fun blankCommentsKeepingLiterals(source: String): String {
        val out = source.toCharArray()
        var i = 0
        while (i < out.size) {
            when {
                source.startsWith("//", i) -> {
                    val nl = source.indexOf('\n', i).let { if (it < 0) out.size else it }
                    for (k in i until nl) out[k] = ' '
                    i = nl
                }

                source.startsWith("/*", i) -> {
                    var depth = 1
                    var j = i + 2
                    while (j < out.size && depth > 0) {
                        when {
                            source.startsWith("/*", j) -> { depth++; j += 2 }
                            source.startsWith("*/", j) -> { depth--; j += 2 }
                            else -> j++
                        }
                    }
                    for (k in i until j.coerceAtMost(out.size)) if (out[k] != '\n') out[k] = ' '
                    i = j
                }

                source.startsWith("\"\"\"", i) -> {
                    i = source.indexOf("\"\"\"", i + 3).let { if (it < 0) out.size else it + 3 }
                }

                out[i] == '"' || out[i] == '\'' -> {
                    val quote = out[i]
                    var j = i + 1
                    while (j < out.size) {
                        when {
                            source[j] == '\\' -> j += 2
                            source[j] == quote -> { j++; break }
                            source[j] == '\n' -> break
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

    private companion object {
        const val PERSONALIZATION = "com/buaa/schedule/core/designsystem/Personalization.kt"
        const val DESIGN_TOKENS = "com/buaa/schedule/core/designsystem/DesignTokens.kt"
        const val SETTINGS_SCREEN = "com/buaa/schedule/ui/settings/SettingsScreen.kt"
        const val WEEK_VIEW = "com/buaa/schedule/ui/home/WeekView.kt"

        const val DECL_MIN = "const val MIN_CARD_ALPHA = 0.3f"
        const val DECL_MAX = "const val MAX_CARD_ALPHA = 1f"
        const val DECL_DEFAULT = "const val DEFAULT_CARD_ALPHA = 0.88f"

        const val LOAD_SITE =
            "cardAlpha = prefs.getFloat(\"glass_alpha\", DEFAULT_CARD_ALPHA).cardAlphaFromDisk()"
        const val OLD_RAW_SITE = "cardAlpha = prefs.getFloat(\"glass_alpha\", 0.88f)"
        const val SEED_SITE = "var cardAlpha by mutableFloatStateOf(DEFAULT_CARD_ALPHA)"

        const val KERNEL_HEAD = "internal fun Float.cardAlphaFromDisk("
        const val KERNEL_BODY = "if (isFinite()) coerceIn(min, max) else fallback"
        const val KERNEL_MIN_ARG = "min: Float = Personalization.MIN_CARD_ALPHA"
        const val KERNEL_MAX_ARG = "max: Float = Personalization.MAX_CARD_ALPHA"
        const val KERNEL_DEFAULT_ARG = "fallback: Float = Personalization.DEFAULT_CARD_ALPHA"

        const val SLIDER_RANGE = "valueRange = 0.3f..1f"
        const val COURSE_CARD_MAPPING =
            "((Personalization.cardAlpha - 0.3f) / 0.7f).coerceIn(0f, 1f)"
        const val SCALE_KERNEL =
            "fun cardAlphaScale(userAlpha: Float): Float = (userAlpha / 0.88f).coerceIn(0.18f, 1.25f)"

        const val ONE_DEFAULT_LITERAL = 1

        /** 量于本卡盘面：念这枚倍率的 main 文件（定义 + 四类表面） */
        const val SCALE_READERS = 5

        /** 量于本卡盘面：`Personalization.cardAlpha` 的读者册子与引用总数 */
        val ALPHA_READERS = listOf(
            "com/buaa/schedule/core/designsystem/GlassSurface.kt",
            "com/buaa/schedule/core/designsystem/liquid/LiquidBottomTabs.kt",
            "com/buaa/schedule/core/designsystem/liquid/LiquidFab.kt",
            "com/buaa/schedule/core/designsystem/liquid/LiquidMenu.kt",
            "com/buaa/schedule/ui/home/WeekView.kt",
            "com/buaa/schedule/ui/settings/SettingsScreen.kt",
        )

        const val ALPHA_MENTIONS = 7
    }
}
