package com.buaa.schedule.ui.stats

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T346：统计页「按周细看」那三枚钻取态的**存活尺**守卫（在册 #412 口径的同族第二落点）。
 *
 * ## 病（本卡在起点 42baf01 上现量的盘面）
 *
 * `StatsScreen.kt` 的 `WeekDrillCard` 里三枚**用户点出来**的钻取态原本是裸 `remember`：
 * `var expanded by remember { mutableStateOf(false) }`、`var selectedWeek by remember { … <Int?>(null) }`、
 * `var selectedDay by remember { … <Int?>(null) }`；而同屏那枚冲突向导开关（showConflictWizard）早就是
 * `rememberSaveable` ⇒ **同屏两把存活尺**。Manifest 零枚 configChanges ⇒ 转屏与折叠屏展开必重建 Activity，
 * 一过就把用户钻到的「第 N 周 → 周 X 明细」静默清零，读起来像"应用把我的选择重置了"。
 *
 * ## 修法与本守卫钉的那一格
 *
 * 载体换 `rememberSaveable`，写法照本仓成文先例（`HomeScreen` 的 `browseWeekState` 那一族：转屏要活下来
 * 就存**非空原始类型**，一律用 -1 当"没手动选过"，读的时候再退成可空；第二处成文见 `DayBrowsePolicy`）。
 * **光换载体不够**：`rememberSaveable { mutableStateOf<Int?>(null) }` 编译过、门禁绿、转屏回来还是 null，
 * 是一枚比裸 remember 更难发现的假绿（它连报错都不跳）。于是钉三件事，缺一件就是这一族换个形状活回来：
 * ① 名册逐枚点名 + 非空初值；② 卡体内**零枚**裸 `remember` 的 state 声明，且盘上的 saveable 落点名单
 * 逐字等于名册（一一对应，两向都数）；④ 「可空初值进 saveable 槽位」那一形在全页 0 枚。
 *
 * ## ③ 那一格才是本卡的靶子：同屏不许两把存活尺
 *
 * 全页留下的裸 `remember` state 只许是在册例外 `statsBandHeightPx` 一枚，名字逐枚点名。它不是谁点出来的值，
 * 是 `onSizeChanged` 每帧重量的占位高度；它的初值还必须是"还没量到"的 null（在册
 * [com.buaa.schedule.ui.home.HeaderBandWiringGuardTest.placeholderHeightComesFromTheRenderedFrame]
 * 钉着这一形）⇒ 学不得钻取态的 -1 哨兵（0 是一枚真量到的高度）。名册**长了**（第二枚裸 remember 冒出来）
 * 红，名册**空了**（把例外也换成 saveable）一样红 —— 两把尺不是只朝一个方向长。
 *
 * ## 为什么 `selectedDay` 不是派生字段（卡面第⓪问点名的怀疑，盘面上否）
 *
 * 它不随 `selectedWeek` 变：写点只有「整周」那一枚胶囊（清成 -1）与七枚日胶囊，读点只吃它自己；换周次不清它、
 * 收起再展开也不清它 ⇒ "值 = f(同表参数)"不成立，`docs/derived-field-audit.md` 那两把合法落点
 * （类体属性 / 每个 copy 站点配守卫）都套不上，它必须独立存。三枚俱然。
 *
 * ## 两臂变异（本卡的自证形状）
 *
 * - 朝宽：把名册里任一枚的载体换回裸 `remember { mutableStateOf(…) }` ⇒ ①（点名）与 ②（零枚裸 remember）
 *   一起红，另一臂不会互相顶掉。
 * - 朝窄：把名册里摘掉一枚（盘上那枚 state 原样留着）⇒ ② 的「盘上 saveable 落点名单 == 名册」当场红
 *   （少点名一枚），⑥ 的入口枚数对账再补一刀。
 *
 * ## 覆盖面边界（如实写）
 *
 * 纯 JVM 源码扫描（无 Robolectric、无 Compose 运行时、零设备）⇒ 钉的是**载体与形状**，
 * 不是"转屏之后屏上真的还选着第 N 周"。那一格今天只能装机验，本卡零 adb ⇒ **未验到**。
 * 刀法照 [StatsT82WiringGuardTest] 与 [com.buaa.schedule.ui.home.DayBrowseWiringGuardTest]：读源文件文本、
 * 匹配先抹注释（本卡 KDoc 里就写着被禁的那几种写法本身，连注释一起扫会红在自己人手上）、函数体按花括号配平取、
 * 找不到锚点就抛、不用 assumeTrue 跳过。
 */
class StatsDrillStateSurvivalGuardTest {

    /**
     * 名册（本卡的尺）= **用户点出来的钻取态**：离开组合就该丢、而用户会认为它跨得转屏。
     * 每枚配它该有的非空初值（= 第一次进这一页那一档：收起 / 没选过）。
     */
    private val drillRoster: List<Pair<String, String>> = listOf(
        "expanded" to "false",
        "selectedWeek" to "-1",
        "selectedDay" to "-1",
    )

    /** 全页留下的裸 `remember` state 例外名册（格 ③：名字与枚数一起钉） */
    private val bareRememberExceptions: List<String> = listOf("statsBandHeightPx")

    /** 格 ⑤：哨兵（selectedWeek / selectedDay）允许出现的四类落点，其余落点一律算"读哨兵" */
    private val sentinelBookedSites: List<String> = listOf(
        // 声明本身
        "by rememberSaveable",
        // 唯一一处"退成可空"
        "takeIf { it >= 0 }",
        // memoize 键：在册 StatsT82WiringGuardTest⑤ 点名要键里含 selectedWeek 这个名
        "remember(courses, semester, timeSlots, selectedWeek)",
    )

    // ---- ① 名册逐枚点名：每一枚都得是 saveable + 非空初值 ----

    @Test
    fun everyRosteredDrillStateIsDeclaredSaveableWithTheBookedNonEmptyInitialValue() {
        val card = drillCardBody()
        for ((name, initialValue) in drillRoster) {
            val decl = Regex("var\\s+$name\\s+by\\s+rememberSaveable\\s*\\{\\s*mutableStateOf\\(([^)]*)\\)\\s*\\}")
                .find(card)
            assertTrue(
                "「$name」不再是 rememberSaveable 的 state（或换了个抓不到的写法）：转屏／折叠屏展开一过，" +
                    "用户钻到的那一周、那一天就回到折叠态，而同屏那枚向导开关还活着 —— 两把存活尺",
                decl != null,
            )
            val captured = decl!!.groupValues[1].trim()
            assertEquals(
                "「$name」的初值必须是「第一次进这一页」那一档的非空原始值：初值写 null 就是那条静默不落盘的形，" +
                    "写别的值就是替用户预选了他没点过的东西",
                initialValue, captured,
            )
        }
    }

    // ---- ② 双向对账：卡体内零枚裸 remember 的 state，且 saveable 落点名单 == 名册 ----

    @Test
    fun theDrillCardKeepsOneSurvivalRulerAndItsRosterOneToOne() {
        val card = drillCardBody()
        val bare = Regex("var\\s+(\\w+)\\s+by\\s+remember\\s*\\{").findAll(card).map { it.groupValues[1] }.sorted().toList()
        assertTrue(
            "钻取那一块里冒出裸 remember 的 state（实到 ${bare.size} 枚：${bare.joinToString(" ／ ")}）：" +
                "这三枚全是用户点出来的钻取态，任何一枚回到 remember 就是把「转屏归零」放回来（朝宽的变异臂）",
            bare.isEmpty(),
        )
        val saveable = Regex("var\\s+(\\w+)\\s+by\\s+rememberSaveable\\s*\\{")
            .findAll(card).map { it.groupValues[1] }.sorted().toList()
        val booked = drillRoster.map { it.first }.sorted()
        assertEquals(
            "卡体内「用户点出来的 state」名单与 rememberSaveable 落点必须一一对应：" +
                "名册 $booked ／ 盘上 $saveable（多一枚 = 冒出一个没进名册的存活态；" +
                "少一枚 = 名册里摘掉了盘上还活着的，那是朝窄的变异臂）",
            booked, saveable,
        )
        assertEquals(
            "卡体内的 state 声明总枚数（名册 ${drillRoster.size} 枚 ／ 实到 ${bare.size + saveable.size} 枚）" +
                "：这一页的钻取态只许这三枚，第四枚得先进名册",
            drillRoster.size, bare.size + saveable.size,
        )
    }

    // ---- ③ 同屏不许两把尺：全页的裸 remember 例外名册 ----

    @Test
    fun theWholePageKeepsExactlyTheBookedBareRememberStateAndNothingElse() {
        val code = blankComments(source(STATS_SCREEN))
        val bare = Regex("var\\s+(\\w+)\\s+by\\s+remember\\s*\\{").findAll(code).map { it.groupValues[1] }.sorted().toList()
        val booked = bareRememberExceptions.sorted()
        assertEquals(
            "统计页的裸 remember state 名册必须逐字等于在册那一份（$booked）—— 实到 $bare。" +
                "多出来的一枚 = 同屏第二把存活尺（钻取态学回去，或新页面态没判存活）；" +
                "少掉的那一枚 = 有人把占位高度也换成了 saveable：它是每帧由 onSizeChanged 重量的量，" +
                "不是谁点出来的值，换成 saveable 就是把上一帧的 px 当托底（T80-C 那笔棘轮账换个维度重演）",
            booked, bare,
        )
        assertTrue(
            "statsBandHeightPx 仍得是 remember + 「还没量到」的 null 那一形（在册 HeaderBandWiringGuardTest 同款判据）：" +
                "它的 null 语义与 -1 哨兵不兼容 —— 0 是一枚真量到的高度，拿 0/-1 当「没量到」就是向那条带" +
                "承诺一个没量过的高度",
            Regex("var\\s+statsBandHeightPx\\s+by\\s+remember\\s*\\{\\s*mutableStateOf<Int\\?>\\(null\\)\\s*\\}")
                .containsMatchIn(code),
        )
        assertTrue(
            "同屏另一头的冲突向导开关必须是 saveable（这一页的存活尺按它对齐，它退回裸 remember 就是名册又长了一枚）",
            Regex("var\\s+showConflictWizard\\s+by\\s+rememberSaveable\\s*\\{").containsMatchIn(code),
        )
    }

    // ---- ④ 假绿形：可空初值进不了 saveable 的槽位 ----

    @Test
    fun noNullableInitialValueEverEntersASaveableSlotOnThisPage() {
        val code = blankComments(source(STATS_SCREEN))
        val typed = Regex("rememberSaveable[^\\n]*mutableStateOf<[A-Za-z.,\\s]*\\?>").findAll(code).map { it.value }.toList()
        val untypedNull = Regex("rememberSaveable[^\\n]*mutableStateOf\\(\\s*null\\s*\\)").findAll(code).map { it.value }.toList()
        assertTrue(
            "统计页出现了「可空初值进 saveable 槽位」的形（显式 <X?> 实到 ${typed.size} 枚 ／ 裸 null 实到 " +
                "${untypedNull.size} 枚）：本仓成文先例判它「Int? 会静默不落盘」—— 看着换了载体，转屏回来还是 " +
                "null，比裸 remember 更难发现。要存活就存非空原始类型 + 读时退成可空（见 chosenWeek / chosenDay）",
            typed.isEmpty() && untypedNull.isEmpty(),
        )
        assertEquals(
            "这一形在全页的落点枚数必须是 0（口径 = 抹注释后的原文；本文件 4 枚 state 声明各有其位，" +
                "三枚走 -1/false、例外那枚走 <Int?>(null) 但它的载体是 remember 不是 saveable）：",
            0, allOccurrences(code, "rememberSaveable { mutableStateOf<").size,
        )
    }

    // ---- ⑤ 读点只有一把尺：哨兵只许在声明／退可空／写点／memoize 键 ----

    @Test
    fun theSentinelsAreOnlyReadThroughTheRecoveredNullables() {
        val card = drillCardBody()
        val writeSite = Regex("selected(Week|Day)\\s*=\\s*[^=]")
        val offenders = card.lines()
            .map { it.trim() }
            .filter { it.contains("selectedWeek") || it.contains("selectedDay") }
            .filterNot { line ->
                sentinelBookedSites.any { line.contains(it) } || writeSite.containsMatchIn(line)
            }
            .toList()
        assertTrue(
            "哨兵被直接拿来读了（实到 ${offenders.size} 行：${offenders.joinToString(" ／ ")}）：" +
                "-1 这一档只许「退成可空的那一行」认识它，别处再读一次就是第二套真相 —— 漏掉一处 >= 0 判定，" +
                "屏上就能出现「第 -1 周」或者「整周」胶囊与某一天的胶囊同时亮着。读点一律走 chosen*，写点按 -1 清空",
            offenders.isEmpty(),
        )
        assertTrue(
            "退可空那一行必须在（它是名册与屏之间唯一一把读尺）：$DERIVED_LINE_HINT",
            Regex("val\\s*\\(chosenWeek,\\s*chosenDay\\)\\s*=\\s*selectedWeek\\.takeIf\\s*\\{\\s*it\\s*>=\\s*0\\s*\\}" +
                "\\s+to\\s+selectedDay\\.takeIf\\s*\\{\\s*it\\s*>=\\s*0\\s*\\}").containsMatchIn(card),
        )
        for (derived in listOf("chosenWeek", "chosenDay")) {
            val reads = allOccurrences(card, derived).size
            assertTrue(
                "$derived 的读点少了（派生行成了摆设，或者读点被换回哨兵）：实到 $reads 枚，下限 2 枚 —— " +
                    "周次那一维要喂胶囊高亮与明细块，日次那一维要喂胶囊高亮、明细过滤与「只展 3 条」那格",
                reads >= 2,
            )
        }
    }

    // ---- ⑥ 尺子自己还活着：锚点唯一 + 名册的根据（用户点出来的入口枚数）----

    @Test
    fun theRosterIsGroundedInTheEntryPointsAndTheScannerIsNotBlind() {
        val code = blankComments(source(STATS_SCREEN))
        assertEquals(
            "「private fun WeekDrillCard(」这一枚靶子在全页必须恰好 1 枚落点（实到 " +
                "${allOccurrences(code, "private fun WeekDrillCard(").size} 枚）：多一枚就是有人复制了钻取块，" +
                "本守卫的 indexOf 会挑错那一块，名册对账就成了看风景",
            1, allOccurrences(code, "private fun WeekDrillCard(").size,
        )
        val card = drillCardBody()
        assertEquals(
            "钻取块的入口枚数（展开／收起那一行整行可点）必须是 1 枚：它是 expanded 那一名册项的根据",
            1, allOccurrences(card, "clickable(role = Role.Button)").size,
        )
        assertEquals(
            "钻取块的胶囊入口（周次行／「整周」／日次行）必须是 3 处 DrillChip( 调用：它们是 selectedWeek 与 " +
                "selectedDay 两枚名册项的根据。少一处 = 删了入口还留着 state（那枚 state 该跟着退出名册）；" +
                "多一处 = 又开了一枚没人点得着的胶囊",
            3, allOccurrences(card, "DrillChip(").size,
        )
        assertFalse(
            "扫描器读空了：抹注释后的卡体长度只有 ${card.length} 字符（WeekDrillCard 至少是几百行的那一块）",
            card.length < 500,
        )
    }

    // ---- 靶子定位与词法小工具（与 StatsT82WiringGuardTest 同族写法）-------------------

    /** `WeekDrillCard` 的函数体（含两端花括号，注释已抹） */
    private fun drillCardBody(): String = functionBody(STATS_SCREEN, "private fun WeekDrillCard(")

    private fun functionBody(relative: String, signature: String): String {
        val code = blankComments(source(relative))
        val at = code.indexOf(signature)
        assertTrue("找不到 $signature：靶子没了（$relative）—— 改名／搬家的话本守卫要跟着改，不许静默放行", at >= 0)
        val brace = code.indexOf('{', at)
        val end = matchingClose(code, brace) ?: throw AssertionError("$relative 的花括号配不上对，解析器该修了")
        assertTrue("$relative 函数体终点在起点之前？", end > brace)
        return code.substring(brace, end)
    }

    private fun source(relative: String): String {
        val file = File(findMainJavaDir(), relative)
        assertTrue("找不到 $relative：挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText()
    }

    private fun allOccurrences(hay: String, needle: String): List<Int> {
        val hits = mutableListOf<Int>()
        var at = hay.indexOf(needle)
        while (at >= 0) {
            hits += at
            at = hay.indexOf(needle, at + needle.length)
        }
        return hits
    }

    private fun matchingClose(code: String, open: Int): Int? {
        if (open < 0 || code[open] !in "([{") return null
        var depth = 0
        for (i in open until code.length) {
            when (code[i]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return null
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

    /** 把 `//` 与 `/* */` 注释抹成空格（字符串保留、长度与换行位置不变）：钉的是接线，不是白话 */
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

    private companion object {
        const val STATS_SCREEN = "com/buaa/schedule/ui/stats/StatsScreen.kt"

        /** 报错消息里念一句形状，别把正则本体抄进文案（抄了就等于给散文再数一遍） */
        const val DERIVED_LINE_HINT = "val (chosenWeek, chosenDay) = selectedWeek.takeIf… to selectedDay.takeIf…"
    }
}
