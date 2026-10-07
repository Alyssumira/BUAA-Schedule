package com.buaa.schedule.ui.home

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T202（#241 / 原 T200）接线守卫：那颗「课次 / 时间」模式胶囊到底**吃什么数才画出来**。
 *
 * 病（读码复核过的因果链）：胶囊原先住在第二行那一簇 `AnimatedVisibility` **里面**，簇的可见性
 * 是 `shouldOfferWeekNavigation(selectedTab, hasSemester, weekTab) = hasSemester && selectedTab == weekTab`，
 * 于是 `hasSemester` 那一维捎带走了一颗与"这学期多少周"毫无关系的按钮 ——
 * 没设学期（新装还没导入 / 学期行被删）时**全仓唯一一颗**能改 `Personalization.weekGridMode` 的入口
 * 从屏上消失（§ 下面「唯一入口」那一段是本守卫复核过的结论，不是抄来的），而偏好照旧落盘
 * （`Personalization` 的 load/save 都在），`WeekView` 那枚 `timeMode` 照旧为真 ⇒
 * 屏上画的是 24 小时时间轴、切回来的那颗胶囊看不见。
 *
 * ## §3 第 1 步的复核：`weekGridMode` 的全部读写点（本文件把它们钉成册子）
 *
 * - 写：**只有首页那一颗胶囊**（`HomeScreen` 里 `Personalization.weekGridMode = if (timeMode) …` 一处）；
 * - 读：`HomeScreen` 与 `WeekView` 各一枚 `val timeMode = … == WEEK_GRID_TIME_24H`，加 `Personalization`
 *   自己的 load/save 两行；
 * - 设置页（`SettingsScreen`）里 `grep` 遍 `weekGridMode` / `WEEK_GRID_TIME_24H` / `week_grid_mode`
 *   **零枚** —— 那里有 `weekFitViewport`、`weekRowScale`、`weekCornerRadiusDp`，但没有第二颗时间轴开关。
 * ⇒ 卡面 §2 那句「全仓唯一入口」**成立**，不存在"从设置页绕过去"的退路（卡面 §6 第二条那个降级措辞
 * 用不上）。这一条不是白话：第⑥层把它钉成册子（写点全 main 恰好一枚、且落在 HomeScreen 的胶囊里）。
 *
 * ## 刀法（本仓四家钉法各用在哪一格）
 *
 * 读 `app/src/main` 的工作树文本、匹配前先抹注释保留字面量（本卡的注释里就逐字写着病灶原文，
 * 不抹就会假绿）、括号配平取实参表与内容块、锚点找不到就抛、失败消息带实到枚数与落点、不用 assumeTrue。
 * 工作树文件已在 `app/build.gradle.kts` 的守卫输入面里（`src/main/java` 整目录）。
 * 1. **抹注释找锚点**：①（胶囊那一段源码里没有 `hasSemester` / `showWeekNav`）、⑤（内核函数体里也没有）；
 * 2. **读形状**（括号配平 + 行的先后次序）：③（加权槽在 AnimatedVisibility 之外）、④（簇里剩哪三样）；
 * 3. **数出现次数**：②（新判据的定义与调用枚数）、④（三样消费点各一枚）、⑥（写点册子）；
 * 4. **逐字节**：②（调用点那一行 `showTimeModeToggle = shouldOfferTimeModeToggle(selectedTab, weekTab = 0)`
 *    与形参声明 `showTimeModeToggle: Boolean` 各恰好一枚）。
 * ⚠️ 本仓口径：不许拿「grep 没匹配到」当全绿 ⇒ 第⑥层把探测器本身按在改前那一形与两枚合法写法上各量一遍。
 *
 * ## 钉六层
 *
 * 1. 胶囊的可见性表达式里**没有** `hasSemester`（也没有 `showWeekNav`）：`visible` 只许是
 *    `showTimeModeToggle` 一枚标识符；罩着胶囊的 AnimatedVisibility 只许有一枚（多一枚就是又被
 *    某个外层门槛捎走，改前那一形正是簇罩着它）；胶囊那一段（实参表 + 内容块）抹注释后 0 枚 `hasSemester`；
 * 2. 新判据全仓唯一落点：定义一枚（内核）+ 调用一枚（HomeScreen），逐字节的接线两枚；
 *    内核那一枚的签名只吃 `selectedTab` 与 `weekTab`（序号由调用点递进来，内核里一枚都不写死）；
 * 3. #113/#118 那两笔权重账的前提：常驻加权槽 `Row(modifier = Modifier.weight(1f))` 仍在全仓唯一一枚、
 *    自身无 `visible`、**不在任何** AnimatedVisibility 的内容块里，且两枚 AnimatedVisibility（胶囊、簇）
 *    都排在它之后（按行的先后次序钉）；
 * 4. 出簇不等于散架：簇（`visible = showWeekNav`）里仍装着 ‹ 周次 › 两枚步进与学期进度线闸门，
 *    内容块里 `weekGridMode` 归零；跳周清单那一枚 `weekChoicesFor(hasSemester, totalWeeks)` 照旧；
 * 5. 内核函数体里也不许藏 `hasSemester`（藏在参数上与藏在调用点是同一枚病灶的两个落点）；
 * 6. 反套套逻辑：探测器在改前那一形上抓到 `showWeekNav`、在"把 hasSemester 写进 visible"那一形上
 *    抓到 `hasSemester`，在本仓合法写法（簇自身那一枚 `visible = showWeekNav`）上不误伤。
 */
class TimeModeToggleWiringGuardTest {

    // ─────────────── ① 胶囊的可见性表达式里没有 hasSemester（抹注释找锚点） ───────────────

    @Test
    fun `时间模式胶囊的可见性只吃页签那一枚标识符`() {
        val home = blankCommentsKeepingLiterals(readMainSource(HOME_SCREEN))
        val gates = chipVisibilityGates(home)

        assertEquals(
            "罩着「课次 / 时间」胶囊的 AnimatedVisibility 枚数是 ${gates.size}，期望恰好 1 处：" +
                "0 处 = 胶囊不再由可见性门槛决定画不画（那就是无条件常驻，今日页签上也多一颗点不动的按钮）；" +
                "两枚 = 它又被某枚外层门槛捎走 —— 改前那一形正是周次簇罩着它，" +
                "没设学期时那颗按钮跟着簇一起没了（#241 的本体）",
            1,
            gates.size,
        )
        for ((args, content) in gates) {
            assertEquals(
                "胶囊那枚 AnimatedVisibility 的 visible 不再只吃 showTimeModeToggle（实到 " +
                    "${visibleIdents(args)}）：画不画只由「这一屏画的是不是周课表」决定，" +
                    "掺进学期那一维就是没设学期时切不了 24 小时时间轴",
                listOf("showTimeModeToggle"),
                visibleIdents(args),
            )
            for (banned in listOf("hasSemester", "showWeekNav", "state.semester")) {
                assertEquals(
                    "胶囊那一段源码里「$banned」出现 ${occurrences(args + content, banned)} 处，期望 0 处：" +
                        "这一族判据分家之后，胶囊这一枚只许吃页签 —— 抹注释后仍算得数，" +
                        "写在注释里的那些引用（本卡注释逐字引着病灶）不算",
                    0,
                    occurrences(args + content, banned),
                )
            }
            assertEquals(
                "胶囊自己那枚 AnimatedVisibility 的实参表里 weight( 出现 ${occurrences(args, "weight(")} 处，" +
                    "期望 0 处：#113 那笔账钉的就是「权重挂在 AnimatedVisibility 内部」这一形 —— " +
                    "胶囊出簇之后如果给自己讨一份 weight，它就成了那枚无权重子件以外的第二份伸展权",
                0,
                occurrences(args, "weight("),
            )
        }
        // 胶囊本体（那颗写 weekGridMode 的 Box）仍在这一段里，搬动没把它搬丢
        assertTrue(
            "胶囊那一段内容块里已经没有写点「$CHIP_WRITE」了：① 认人的锚就是这一枚，" +
                "它不在就说明那颗按钮被搬去了别处（本守卫的射程跟着变）",
            gates[0].second.contains(CHIP_WRITE),
        )
    }

    // ─────────────── ② 新判据全仓唯一落点 + 真被 HomeScreen 调用（数出现次数 + 逐字节） ───────────────

    @Test
    fun `新判据只有一份定义且被首页真的调用`() {
        val hits = mainHits(NEW_PREDICATE).associate { File(it.first).name to it.second }
        assertEquals(
            "main 里「$NEW_PREDICATE」的落点册子漂了：期望 $EXPECTED_LEDGER，实到 $hits —— " +
                "内核定义一枚 + 首页调用一枚。第二枚定义就是第二份能各自写错的判据" +
                "（本仓那族「同一判据两份写法各错各的」的账），0 枚调用就是判据长在内核里没人吃",
            EXPECTED_LEDGER,
            hits,
        )
        assertEquals(
            "main 里「$NEW_PREDICATE」总枚数不是 2（定义 1 + 调用 1）：实到 ${mainHits(NEW_PREDICATE).sumOf { it.second }} 处",
            2,
            mainHits(NEW_PREDICATE).sumOf { it.second },
        )
        val home = blankCommentsKeepingLiterals(readMainSource(HOME_SCREEN))
        assertEquals(
            "调用点那一行「$CHIP_WIRING」出现 ${occurrences(home, CHIP_WIRING)} 处，期望恰好 1 处（逐字节钉）：" +
                "页签序号必须由调用点当实参递进来（weekTab = 0 是首页的事实，不是内核的暗号）",
            1,
            occurrences(home, CHIP_WIRING),
        )
        // ⚠️ 这一枚不许拿"文件里有没有 `visible = showWeekNav` 这个字面量"当尺 —— 簇自己那枚门槛
        // 本来就叫这个名字（照字面量数就永远 1 处，假绿）。尺是**枚数**：胶囊出簇之后全文件只许
        // 剩簇那一枚，胶囊再讨一枚 showWeekNav 就是把它塞回了簇里（病灶回潮），实到 2 处即红。
        val weekNavGates = Regex("""visible\s*=\s*showWeekNav\b""").findAll(home).toList()
        assertEquals(
            "HomeScreen 里 `visible = showWeekNav` 的枚数是 ${weekNavGates.size}，期望恰好 1 处（就是周次簇" +
                "自己那一枚门槛）：多出来的那一枚只会是「课次 / 时间」胶囊又去吃簇的门槛 —— " +
                "没设学期时那颗按钮跟着簇一起没（#241 病灶原样回潮）。胶囊那一段的 visible 由 ① 逐字节钉",
            1,
            weekNavGates.size,
        )
        assertEquals(
            "工具条形参「$CHIP_PARAM」出现 ${occurrences(home, CHIP_PARAM)} 处，期望恰好 1 处：" +
                "画不画由调用点递进来，工具条自己不许去读 selectedTab",
            1,
            occurrences(home, CHIP_PARAM),
        )
        for (banned in listOf("showTimeModeToggle = selectedTab == 0", "showTimeModeToggle = true")) {
            assertEquals(
                "HomeScreen 里出现「$banned」（枚数 ${occurrences(home, banned)}，期望 0 处）：" +
                    "在调用点现写页签比较就是第二套口径（内核里那枚 weekTab 参数正是为了不给它留这条路），" +
                    "恒真则连页签都不看了 —— 今日页签上会多出一颗点了没反应的胶囊",
                0,
                occurrences(home, banned),
            )
        }
        // 工具条体内不许回头自己读页签/学期（判据只从实参进来）
        val toolbar = balancedBlock(home, "private fun ScheduleToolbarRow(")
        assertEquals(
            "ScheduleToolbarRow 体内读 selectedTab（枚数 ${occurrences(toolbar, "selectedTab")}，期望 0 处）：" +
                "这一族账的收单口径是「页签序号由调用点递进来」，组件里再读一遍就是两份判据",
            0,
            occurrences(toolbar, "selectedTab"),
        )
    }

    // ─────────────── ③ 加权槽仍在 AnimatedVisibility 之外（读形状：行的先后次序） ───────────────

    @Test
    fun `常驻加权槽不在任何 AnimatedVisibility 里面`() {
        val home = blankCommentsKeepingLiterals(readMainSource(HOME_SCREEN))
        val toolbar = balancedBlock(home, "private fun ScheduleToolbarRow(")

        assertEquals(
            "全文件里那枚常驻加权槽「$PERMANENT_SLOT」出现 ${occurrences(home, PERMANENT_SLOT)} 处，期望恰好 1 处：" +
                "它就是 #118 那笔账的答案（簇退场跑完会整枚退出组合，那份 weight 不能跟着走）",
            1,
            occurrences(home, PERMANENT_SLOT),
        )
        val slotAt = toolbar.indexOf(PERMANENT_SLOT)
        check(slotAt >= 0) { "ScheduleToolbarRow 里已经没有 $PERMANENT_SLOT：加权槽整个换写法了，本守卫要跟着改" }
        val slotArgs = balancedArguments(toolbar, toolbar.indexOf('(', slotAt))
        assertFalse(
            "加权槽自己开始随页签进出（实参表里有 visible）：$slotArgs —— #118 那笔账（校区横跳 805px）就是" +
                "「常驻件自己没了」那一枚病灶",
            Regex("""visible\s*=""").containsMatchIn(slotArgs),
        )
        // 几何关系：加权槽必须落在**每一枚** AnimatedVisibility 的内容块之外
        val enclosing = ArrayList<Int>()
        for (match in Regex("""\bAnimatedVisibility\(""").findAll(toolbar)) {
            val open = match.range.last
            val args = balancedArguments(toolbar, open)
            val brace = toolbar.indexOf('{', open + args.length - 1)
            if (brace < 0) continue
            val content = balancedBraces(toolbar, brace)
            val contentAt = toolbar.indexOf(content)
            if (slotAt in (contentAt + 1) until (contentAt + content.length)) enclosing += lineOf(toolbar, match.range.first)
        }
        assertEquals(
            "加权槽被第 ${enclosing.joinToString()} 行附近那枚 AnimatedVisibility 罩在**里面**了（期望 0 枚罩它）：" +
                "那正是 #118 的成因形状 —— AnimatedVisibility 退场后整枚退出组合，权重视同没有，" +
                "校区从右端跳回行首",
            0,
            enclosing.size,
        )
        // 行的先后次序：槽在先，胶囊与簇两枚 AnimatedVisibility 都在它之后（槽是它们的家）
        val chipAt = toolbar.indexOf(CHIP_VISIBILITY)
        val clusterAt = toolbar.indexOf(CLUSTER_VISIBILITY)
        check(chipAt >= 0 && clusterAt >= 0) {
            "胶囊（$chipAt）或簇（$clusterAt）的 visible 找不到了：写法换过了，本守卫要跟着改"
        }
        assertTrue(
            "加权槽那一行（第 ${lineOf(toolbar, slotAt)} 行）不再排在两枚 AnimatedVisibility 之前：" +
                "槽在先、簇与胶囊在内 —— 反过来的话槽就落在簇的内容块里了（③ 上面那格）",
            slotAt < chipAt && slotAt < clusterAt,
        )
        val slotBrace = toolbar.indexOf('{', slotAt + PERMANENT_SLOT.length)
        check(slotBrace >= 0) { "加权槽没有内容块：$PERMANENT_SLOT 的写法变了，本守卫要跟着改" }
        val slotEnd = slotBrace + balancedBraces(toolbar, slotBrace).length
        assertTrue(
            "胶囊（第 ${lineOf(toolbar, chipAt)} 行）或簇（第 ${lineOf(toolbar, clusterAt)} 行）不再落在加权槽" +
                "的内容块之内（第 ${lineOf(toolbar, slotBrace)}–${lineOf(toolbar, slotEnd)} 行）：" +
                "这一排的余量算法要重算 —— 出簇的胶囊如果搬去加权件之外，它就成了那一行里第二枚按整份铺开的件",
            chipAt in slotBrace until slotEnd && clusterAt in slotBrace until slotEnd,
        )
    }

    // ─────────────── ④ 出簇之后簇里剩哪三样（读形状 + 数出现次数） ───────────────

    @Test
    fun `簇里剩下的三样继续吃 showWeekNav`() {
        val home = blankCommentsKeepingLiterals(readMainSource(HOME_SCREEN))
        val toolbar = balancedBlock(home, "private fun ScheduleToolbarRow(")
        val (clusterArgs, clusterContent) = clusterGate(toolbar)

        assertEquals(
            "簇那枚 AnimatedVisibility 的 visible 不再只吃 showWeekNav：${visibleIdents(clusterArgs)}",
            listOf("showWeekNav"),
            visibleIdents(clusterArgs),
        )
        assertEquals(
            "周次簇的内容块里「weekGridMode」出现 ${occurrences(clusterContent, "weekGridMode")} 处，期望 0 处：" +
                "本卡搬的就是这一格 —— 胶囊留在簇里，hasSemester 那一维就照旧捎走它",
            0,
            occurrences(clusterContent, "weekGridMode"),
        )
        for (kept in listOf("KeyboardArrowLeft", "KeyboardArrowRight", PROGRESS_WIRING)) {
            assertEquals(
                "簇的内容块里「$kept」出现 ${occurrences(clusterContent, kept)} 处，期望恰好 1 处：" +
                    "‹ 周次 › 两枚步进与学期进度线吃的都是「这学期多少周」，这三样**继续**吃 showWeekNav " +
                    "才是分家后的正确形状（把它们跟着搬出簇就是 T197 那笔假 20 周的回潮）",
                1,
                occurrences(clusterContent, kept),
            )
        }
        // 跳周清单那三样：消费点与闸门照旧（清单在弹窗里，弹窗只能由簇里那一格打开）
        assertEquals(
            "「$CHOICE_WIRING」出现 ${occurrences(home, CHOICE_WIRING)} 处，期望恰好 1 处：" +
                "跳周清单吃的仍是 hasSemester 那一维（本卡不许把它搬到页签上去）",
            1,
            occurrences(home, CHOICE_WIRING),
        )
        assertEquals(
            "「weekGridMode」在 HomeScreen 里（抹注释后）的枚数是 ${occurrences(home, "weekGridMode")}，期望 2 处" +
                "（读：val timeMode = … 一处；写：胶囊的 onClick 一处）：多一枚就是又有一处在拨这枚偏好，" +
                "少一枚就是那颗胶囊本体没了",
            2,
            occurrences(home, "weekGridMode"),
        )
    }

    // ─────────────── ⑤ 内核那一枚函数体里也不许藏 hasSemester（抹注释找锚点） ───────────────

    @Test
    fun `内核新判据是纯 JVM 两维判据`() {
        val kernel = blankCommentsKeepingLiterals(readMainSource(KERNEL))
        assertEquals(
            "内核签名「$KERNEL_SIGNATURE」出现 ${occurrences(kernel, KERNEL_SIGNATURE)} 处，期望恰好 1 处（逐字节）：" +
                "入参只有「选中的页签 + 周课表那一枚页签的序号」两枚调用点事实，内核里一枚都不写死",
            1,
            occurrences(kernel, KERNEL_SIGNATURE),
        )
        val at = kernel.indexOf(NEW_PREDICATE)
        check(at >= 0) { "内核里已经没有 $NEW_PREDICATE：判据搬走了，本守卫要跟着改" }
        val next = kernel.indexOf("internal fun ", at + NEW_PREDICATE.length)
        val body = if (next < 0) kernel.substring(at) else kernel.substring(at, next)
        for (banned in listOf("hasSemester", "semester", "totalWeeks", "?: 20", "LocalContext", "SystemClock")) {
            assertEquals(
                "新判据那一段里「$banned」出现 ${occurrences(body, banned)} 处，期望 0 处：" +
                    "学期那一维写进函数体（哪怕参数表里没有）就是把胶囊塞回了簇里；" +
                    "时钟/设备读数则让这张表在 JVM 里打不开（本仓收单硬口径）",
                0,
                occurrences(body, banned),
            )
        }
        assertEquals(
            "新判据的返回式「selectedTab == weekTab」枚数 ${occurrences(body, "selectedTab == weekTab")}，" +
                "期望恰好 1 处：这一枚就是「这一屏画的是不是周课表」的全部含义",
            1,
            occurrences(body, "selectedTab == weekTab"),
        )
    }

    // ─────────────── ⑥ 反套套逻辑 + weekGridMode 读写点册子（数出现次数） ───────────────

    @Test
    fun `探测器认得改前那一形 也不误伤合法写法`() {
        // (a) 改前那一形：胶囊住在簇里，罩着它的是 visible = showWeekNav
        val legacy = chipVisibilityGates(LEGACY_CLUSTER_BLOCK)
        assertEquals("改前那一形里罩着胶囊的 AnimatedVisibility 枚数：", 1, legacy.size)
        assertEquals(
            "探测器在改前那一形上读到的 visible 应当是 showWeekNav（它连病灶都不认得，① 那句「只吃 " +
                "showTimeModeToggle」就是空的）：${visibleIdents(legacy[0].first)}",
            listOf("showWeekNav"),
            visibleIdents(legacy[0].first),
        )
        // (b) 把 hasSemester 直接写进 visible 的那一形（同一病灶的另一种写法）必须被 ① 那格抓到
        val narrow = chipVisibilityGates(NARROW_CHIP_BLOCK)
        assertEquals("把 hasSemester 写进 visible 那一形里罩着胶囊的枚数：", 1, narrow.size)
        assertTrue(
            "探测器在「visible = hasSemester && …」那一形上没抓到 hasSemester（实到 ${narrow[0].first}）：" +
                "① 那格「该段源码不含 hasSemester」的钉法就是空的",
            narrow[0].first.contains("hasSemester"),
        )
        // (c) 合法写法不许误伤：胶囊出簇、自身一枚门槛（本卡改完的形状），簇里那一枚 showWeekNav 不该被算成门槛
        val legal = chipVisibilityGates(LEGAL_CLUSTER_WITHOUT_CHIP)
        assertEquals(
            "探测器在「胶囊自成一枚门槛 + 簇里没有胶囊」这一形上抓到 ${legal.size} 枚门槛，期望恰好 1 处：" +
                "把合法写法一起扫红的尺会逼下一张卡把守卫改松（这一形就是本卡改完之后的盘面）",
            1,
            legal.size,
        )
        assertEquals(
            "合法那一形的门槛标识符应当只有 showTimeModeToggle：${visibleIdents(legal[0].first)}",
            listOf("showTimeModeToggle"),
            visibleIdents(legal[0].first),
        )
        val legalSegmentHasSemester = occurrences(legal[0].first + legal[0].second, "hasSemester")
        assertEquals(
            "合法那一形里胶囊那一段的 hasSemester 枚数：实到 $legalSegmentHasSemester 处（期望 0）",
            0,
            legalSegmentHasSemester,
        )
        val legalClusterChip = occurrences(clusterGate(LEGAL_CLUSTER_WITHOUT_CHIP).second, "weekGridMode")
        assertEquals(
            "合法那一形里簇的内容块仍带着胶囊（weekGridMode 枚数 $legalClusterChip，期望 0 处）：" +
                "④ 那格读的就是这一件事，探测器在这一格上必须先看得对",
            0,
            legalClusterChip,
        )
        // (d) 唯一入口那句结论钉成册子：weekGridMode 的写点全 main 恰好一枚，落在 HomeScreen
        val writes = mainHits(CHIP_WRITE)
        val writeLedger = writes.associate { File(it.first).name to it.second }
        assertEquals(
            "main 里「$CHIP_WRITE」的落点册子：期望 $WRITE_LEDGER，实到 $writeLedger —— " +
                "这是卡面 §3 第 1 步复核出来的结论（**设置页里没有第二颗入口**，SettingsScreen 里 " +
                "weekGridMode / WEEK_GRID_TIME_24H / week_grid_mode 零枚），" +
                "所以「没设学期时那一档切不了 24 小时时间轴」没有从设置绕过去的那条退路。" +
                "多一枚 = 又长出一个写点（本卡的「唯一入口」措辞要跟着降级）；0 枚 = 胶囊本体没了",
            WRITE_LEDGER,
            writeLedger,
        )
        assertEquals(
            "SettingsScreen 里「weekGridMode」枚数（抹注释后）：实到 " +
                "${occurrences(blankCommentsKeepingLiterals(readMainSource(SETTINGS_SCREEN)), "weekGridMode")} 处，" +
                "期望 0 处：复核过设置页确实没有第二颗入口。真有人补一颗，本卡那句「唯一入口」就要降级成" +
                "「首页那一档看不见入口、但能从设置绕过去」，⑥(d) 那格会先把话说明白",
            0,
            occurrences(blankCommentsKeepingLiterals(readMainSource(SETTINGS_SCREEN)), "weekGridMode"),
        )
    }

    // ---------------- helpers ----------------

    /**
     * 罩着「课次 / 时间」胶囊的每一枚 AnimatedVisibility：返回（实参表, 内容块）。
     *
     * 认人靠**内容里那枚 weekGridMode 写点**（[CHIP_WRITE]）而不是靠"第几枚 AnimatedVisibility"或靠名字：
     * 按序号取哪天有人在上面插一枚就量错了对象，按 `showTimeModeToggle` 取则是拿结论当尺（套套逻辑）。
     * 枚数 > 1 意味着胶囊被嵌套在两枚门槛之下 —— 改前那一形（外层是簇的 showWeekNav）正是这么量的。
     */
    private fun chipVisibilityGates(code: String): List<Pair<String, String>> {
        val gates = ArrayList<Pair<String, String>>()
        for (match in Regex("""\bAnimatedVisibility\(""").findAll(code)) {
            val open = match.range.last
            val args = balancedArguments(code, open)
            val brace = code.indexOf('{', open + args.length - 1)
            if (brace < 0) continue
            val content = balancedBraces(code, brace)
            if (!content.contains(CHIP_WRITE)) continue
            gates += args to content
        }
        return gates
    }

    /** 簇那枚 `visible = showWeekNav` 的 AnimatedVisibility：返回（实参表, 内容块）；找不到就抛 */
    private fun clusterGate(code: String): Pair<String, String> {
        for (match in Regex("""\bAnimatedVisibility\(""").findAll(code)) {
            val open = match.range.last
            val args = balancedArguments(code, open)
            if (!args.contains("showWeekNav")) continue
            return args to balancedBraces(code, code.indexOf('{', open + args.length - 1))
        }
        throw AssertionError("ScheduleToolbarRow 里没有 visible = showWeekNav 的 AnimatedVisibility：簇整个没了，本守卫要重新核")
    }

    /** `visible = 标识符` 里的那些标识符（合取写法会把两侧都点出来，正是 ① 要拦的形状） */
    private fun visibleIdents(args: String): List<String> =
        Regex("""visible\s*=\s*([A-Za-z0-9_. ]+)""").findAll(args)
            .map { it.groupValues[1].trim() }
            .flatMap { it.split(Regex("""\s*&&\s*|\s*\|\|\s*""")) }
            .toList()

    private fun balancedArguments(code: String, open: Int): String {
        var depth = 0
        for (index in open until code.length) {
            when (code[index]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return code.substring(open, index + 1)
                }
            }
        }
        throw IllegalStateException("第 $open 个字符之后的左括号没配平：${code.substring(open, minOf(open + 60, code.length))}")
    }

    /** 从 [signature] 之后第一个 `{` 起配平到对应右括号（含）；找不到锚点就抛，静默跳过等于没有守卫 */
    private fun balancedBlock(source: String, signature: String): String {
        val at = source.indexOf(signature)
        check(at >= 0) { "找不到 $signature：写法换过了，这条守卫要跟着改" }
        return balancedBraces(source, source.indexOf('{', at))
    }

    private fun balancedBraces(source: String, from: Int): String {
        var depth = 0
        for (index in from until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(from, index + 1)
                }
            }
        }
        throw IllegalStateException("从第 $from 个字符起的左花括号没配平")
    }

    private fun readMainSource(relative: String): String {
        val file = File(findMainJavaDir(), relative)
        assertTrue("找不到 $relative：挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText()
    }

    /** 全仓 main 里扫某串文本（抹注释后），返回 (相对路径, 命中次数)，只挑有命中的文件 */
    private fun mainHits(needle: String): List<Pair<String, Int>> {
        val root = findMainJavaDir()
        val hits = mutableListOf<Pair<String, Int>>()
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val count = occurrences(blankCommentsKeepingLiterals(file.readText()), needle)
            if (count > 0) hits += file.relativeTo(root).path.replace('\\', '/') to count
        }
        return hits
    }

    private fun occurrences(haystack: String, needle: String): Int {
        var from = 0
        var found = 0
        while (true) {
            val at = haystack.indexOf(needle, from)
            if (at < 0) return found
            found++
            from = at + needle.length
        }
    }

    /** 失败消息把人带到那一处：position 在**原文**（不是抹注释后的文本）里的行号 */
    private fun lineOf(rawSource: String, position: Int): Int =
        if (position < 0) -1 else rawSource.substring(0, position).count { it == '\n' } + 1

    /** 只抹注释、保留字符串字面量，长度与换行位置不变（行号因此仍然可信） */
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

    private companion object {
        const val KERNEL = "com/buaa/schedule/ui/home/WeekNavigationAvailability.kt"
        const val HOME_SCREEN = "com/buaa/schedule/ui/home/HomeScreen.kt"
        const val SETTINGS_SCREEN = "com/buaa/schedule/ui/settings/SettingsScreen.kt"
        const val KERNEL_NAME = "WeekNavigationAvailability.kt"
        const val HOME_SCREEN_NAME = "HomeScreen.kt"

        const val NEW_PREDICATE = "shouldOfferTimeModeToggle("
        val EXPECTED_LEDGER = mapOf(KERNEL_NAME to 1, HOME_SCREEN_NAME to 1)

        /** 内核那一枚新判据的逐字节签名（两枚调用点事实，内核里一枚都不写死） */
        const val KERNEL_SIGNATURE =
            "internal fun shouldOfferTimeModeToggle(selectedTab: Int, weekTab: Int): Boolean ="

        /** 调用点与形参声明的逐字节锚 */
        const val CHIP_WIRING = "showTimeModeToggle = shouldOfferTimeModeToggle(selectedTab, weekTab = 0)"
        const val CHIP_PARAM = "showTimeModeToggle: Boolean,"
        const val CHIP_VISIBILITY = "visible = showTimeModeToggle,"
        const val CLUSTER_VISIBILITY = "visible = showWeekNav,"

        /** 胶囊本体那枚写点（⑥(d) 的册子尺，也是 ① 认人的锚） */
        const val CHIP_WRITE = "Personalization.weekGridMode = if (timeMode) {"

        /** #118 那笔账的答案：不随页签进出的加权槽 */
        const val PERMANENT_SLOT = "Row(modifier = Modifier.weight(1f))"

        /** 簇里剩下的两枚消费点 */
        const val PROGRESS_WIRING = "if (shouldDrawSemesterProgress(hasSemester)) SemesterProgressLine("
        const val CHOICE_WIRING = "val weekChoices = remember(totalWeeks) { weekChoicesFor(hasSemester, totalWeeks) }"

        /** weekGridMode 的写点册子：全 main 恰好一枚，落在首页那颗胶囊里（设置页零枚，见 ⑥） */
        val WRITE_LEDGER = mapOf(HOME_SCREEN_NAME to 1)

        /** ⑥(a) 改前那一形的合成原文（逐字取自 base 那棵树上的 ScheduleToolbarRow） */
        val LEGACY_CLUSTER_BLOCK = """
            Row(modifier = Modifier.weight(1f)) {
                AnimatedVisibility(
                    visible = showWeekNav,
                    modifier = Modifier.weight(1f),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier.clickable(
                                role = Role.Switch,
                            ) {
                                Personalization.weekGridMode = if (timeMode) {
                                    Personalization.WEEK_GRID_PERIOD
                                } else {
                                    Personalization.WEEK_GRID_TIME_24H
                                }
                            },
                        ) { Text(text = if (timeMode) "时间" else "课次") }
                        IconButton(onClick = { onBrowseWeekChange(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "上一周") }
                        IconButton(onClick = { onBrowseWeekChange(2) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "下一周") }
                    }
                }
                campusSlot?.invoke()
            }
        """.trimIndent()

        /** ⑥(b) 同一病灶的另一种写法：把 hasSemester 直接写进 visible */
        val NARROW_CHIP_BLOCK = """
            Row(modifier = Modifier.weight(1f)) {
                AnimatedVisibility(
                    visible = hasSemester && selectedTab == weekTab,
                ) {
                    Box(modifier = Modifier.clickable {
                        Personalization.weekGridMode = if (timeMode) {
                            Personalization.WEEK_GRID_PERIOD
                        } else {
                            Personalization.WEEK_GRID_TIME_24H
                        }
                    }) { Text(text = if (timeMode) "时间" else "课次") }
                }
                AnimatedVisibility(visible = showWeekNav, modifier = Modifier.weight(1f)) {
                    Row { IconButton(onClick = { onBrowseWeekChange(1) }) }
                }
            }
        """.trimIndent()

        /** ⑥(c) 合法写法：胶囊出簇之后，簇里只剩周次那一三样（本卡改完的形状） */
        val LEGAL_CLUSTER_WITHOUT_CHIP = """
            Row(modifier = Modifier.weight(1f)) {
                AnimatedVisibility(visible = showTimeModeToggle) {
                    Box(modifier = Modifier.clickable {
                        Personalization.weekGridMode = if (timeMode) {
                            Personalization.WEEK_GRID_PERIOD
                        } else {
                            Personalization.WEEK_GRID_TIME_24H
                        }
                    }) { Text(text = if (timeMode) "时间" else "课次") }
                }
                AnimatedVisibility(visible = showWeekNav, modifier = Modifier.weight(1f)) {
                    Row {
                        IconButton(onClick = { onBrowseWeekChange(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "上一周") }
                        if (shouldDrawSemesterProgress(hasSemester)) SemesterProgressLine(currentWeek = currentWeek)
                        IconButton(onClick = { onBrowseWeekChange(2) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "下一周") }
                    }
                }
            }
        """.trimIndent()
    }
}
