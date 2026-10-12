package com.buaa.schedule.ui.home

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T197 接线守卫：没设学期时那一排周次控件到底**吃什么数**。
 *
 * 病不在内核（内核有自己的表驱动单测 [WeekNavigationAvailabilityTest]），在接线与那枚硬写的兜底：
 * `HomeScreen` 的 `totalWeeks = (state.semester?.totalWeeks ?: 20)` 递进 `ScheduleToolbarRow` 之后
 * 同时喂「跳转到周次」的清单与学期进度线；`WeekView` 的 `val totalWeeks = (semester?.totalWeeks ?: 20)`
 * 下游是卡角那枚「不是每周都有」的旗（进读屏文案「，不是每周都有」与卡角标记）。两处都拿假 20 当真数用，
 * 而同一屏的表头因 T190 一枚日期都不印、网格因 `showAllCoursesWithoutWeek = semester == null`
 * 把全部课程画出来 —— 界面自己都不信那 20 周。
 *
 * 刀法照抄同目录 [WeekHeaderDateWiringGuardTest]（T190）与 [WeekHeaderTodayWiringGuardTest]（T182）：
 * 读 `app/src/main` 的工作树文本、匹配前先抹注释保留字面量（本卡的注释与内核 KDoc 里就写着被禁写法的
 * 原文）、数枚数、锚点找不到就抛、失败消息带实到与落点册子、不用 assumeTrue 跳过。
 * 工作树文件已在 `app/build.gradle.kts` 的 `guardReadWorkingTreeFiles` 输入面里（`src/main/java` 整目录）。
 * ⚠️ 本仓口径：不许拿「grep 没匹配到」当全绿 ⇒ 第⑥层先把探测器本身按在改前原文、三例合法写法与
 * 第三等账那一枚上各量一遍。
 *
 * 钉七层：
 * 1. 内核 [WeekNavigationAvailability] 零 android、零时钟、零设备读数，五枚判据的签名逐枚点名，
 *    体里一枚 `?: 20` 都不许藏（藏在参数上与藏在函数体里是同一枚病灶的两个落点）；
 * 2. 病灶那枚 `?: 20` 在 `HomeScreen.kt` 与 `WeekView.kt` 里各恰好 0 处，且全 main 的落点册子里只剩
 *    设置页那一枚（第三等账不许顺手改：那是用户输入的缺省周数，与首页那两枚不同族）；
 * 3. `showWeekNav` 只许由 [shouldOfferWeekNavigation] 给出：改前那一形 `selectedTab == 0` 0 处、
 *    绕过内核在调用点现写合取 0 处，而簇自己那枚 `AnimatedVisibility(visible = showWeekNav)` 不许换主
 *    （#113/#118 两笔权重账的前提）；
 * 4. 三枚消费点形状：`weekChoices` 那枚 `remember(totalWeeks)` 的键配对不许漂（改成拿清单当键就把旁边
 *    那段「键与来源配对」的注释作废了）、学期进度线必须被 [shouldDrawSemesterProgress] 把关、
 *    `WeekView` 两枚旗必须走 [notEveryWeekFlag]（改前那形 `course.weeks.size < totalWeeks` 0 处）；
 * 5. 全仓唯一性：五枚判据在 main 里的落点册子逐枚点名（定义 1 + 调用枚数点清）；
 * 6. 反套套逻辑：探测器必须认得改前那一形原文（两枚各 1 处），又不误伤合法夹取与合法兜底
 *    （`coerceIn(1, MAX_TOTAL_WEEKS)`、`coerceAtLeast(1)` 那三例各 0 处），并在设置页那枚合法
 *    `weeksNumber ?: 20` 上抓到 1 处；内核文件原文各抓到 1 处、抹注释后 0 处（对照组）；
 * 7. T190/T182 刚落地的两处接线仍在原位原样（本卡只动 `:225` 那一枚兜底与它下游的旗）。
 */
class WeekNavigationAvailabilityWiringGuardTest {

    // ─────────────── ① 内核仍是纯 JVM 判据 ───────────────

    @Test
    fun `内核是纯 JVM 判据 体里不许藏二十周`() {
        val code = blankCommentsKeepingLiterals(readMainSource(KERNEL))
        for (banned in listOf(
            "import android", "import androidx", "Build.VERSION", "LocalContext", "LocalConfiguration",
            "SystemClock", "currentTimeMillis", "TimeZone", "LocalDate", "LocalTime", "java.time", "Clock",
            "Calendar", "remember", "Composable",
        )) {
            val hits = occurrences(code, banned)
            assertEquals(
                "$KERNEL_NAME 里「$banned」出现 $hits 处，期望 0 处：这一枚说的是「有没有学期、这学期多少周、" +
                    "该不该给周次导航」，自己去读设备/时钟/组合期就只在「这台机器上的今天」成立，" +
                    "JVM 里打不开这张表（本仓硬口径）",
                0,
                hits,
            )
        }
        assertEquals(
            "内核里「?: 20」出现 ${occurrences(code, "?: 20")} 处，期望 0 处：病灶从调用点搬进函数体还是病灶 —— " +
                "没学期时函数体里补一枚 20，与当年在 HomeScreen 与 WeekView 里各写一枚没有区别",
            0,
            occurrences(code, "?: 20"),
        )
        for (signature in listOf(
            "internal fun shouldOfferWeekNavigation(selectedTab: Int, hasSemester: Boolean, weekTab: Int): Boolean",
            "internal fun weekChoicesFor(hasSemester: Boolean, totalWeeks: Int?): List<Int>",
            "internal fun shouldDrawSemesterProgress(hasSemester: Boolean): Boolean",
            "internal fun weekCountsOf(",
            "internal fun notEveryWeekFlag(weeksSize: Int, notEveryWeekBase: Int?): Boolean",
        )) {
            assertEquals(
                "内核签名漂了：「$signature」出现 ${occurrences(code, signature)} 处，期望恰好 1 处。" +
                    "这一族判据的入参就是「页签序号 / 有没有学期 / 学期总周数 / 这批课的周次」四样调用点事实，" +
                    "少一枚就意味着内核自己去反查",
                1,
                occurrences(code, signature),
            )
        }
        assertEquals(
            "内核里「$CLAMP」出现 ${occurrences(code, CLAMP)} 处，期望 0 处（T331② 重钉：改前这里恰 2 处 —— " +
                "清单那一枚与两枚结论那一枚各自夹取）：学期周数的上下界现在只许长在具名读数 " +
                "$READING_CALL 里那一枚，内核再自己夹一遍就是第二把尺（#452 那扇门的门轴就是这个形状）。" +
                "红了就是把就地夹取写回了内核",
            0,
            occurrences(code, CLAMP),
        )
        assertEquals(
            "内核里「$READING_CALL」出现 ${occurrences(code, READING_CALL)} 处，期望恰好 3 处（清单那一枚 + " +
                "两枚结论那两枚）：与上面那一格互为正反 —— 夹取搬走的同一批落点必须改问读数，" +
                "少一枚就是有一条链既不自己夹、也不问读数（那它吃的又是哪门子数？）",
            3,
            occurrences(code, READING_CALL),
        )
    }

    // ─────────────── ② 病灶那枚二十周：两枚文件各 0 处 ───────────────

    @Test
    fun `二十周的兜底在两枚病灶文件里一枚不许留`() {
        for (file in listOf(HOME_SCREEN, WEEK_VIEW)) {
            val code = blankCommentsKeepingLiterals(readMainSource(file))
            val hits = occurrences(code, "?: 20")
            assertEquals(
                "$file 里「?: 20」出现 $hits 处，期望 0 处：没学期时那枚 20 就是「这学期 20 周」唯一的来源，" +
                    "清单、进度线、读屏旗全吃它。判据在 $KERNEL_NAME",
                0,
                hits,
            )
            for (legacy in listOf(LEGACY_HOME_LINE, LEGACY_WEEK_LINE, LEGACY_CHOICE_LINE, LEGACY_FLAG_LINE)) {
                val legacyHits = occurrences(code, legacy)
                assertEquals(
                    "$file 里改前那一形「$legacy」出现 $legacyHits 处，期望 0 处（本卡收的就是这一形）",
                    0,
                    legacyHits,
                )
            }
        }
        // 第三等账不许顺手改：全 main 抹注释后「?: 20」只剩设置页那一枚（用户输入的缺省周数）。
        // 内核文件只许在 KDoc 里逐字引着改前原文（对照组），抹注释之后一枚都不剩 ⇒ 不进这本册子。
        val ledger = mainHits("?: 20").associate { File(it.first).name to it.second }
        assertEquals(
            "main 里「?: 20」的落点册子漂了：期望 $THIRD_ACCOUNT_LEDGER，实到 $ledger —— " +
                "首页与周视图那两枚是本卡病灶（必须为 0），设置页那一枚属「用户填周数时的缺省」，" +
                "与 #235/#230 那一族同账，本卡不许动",
            THIRD_ACCOUNT_LEDGER,
            ledger,
        )
        assertEquals(
            "内核文件原文里应当留着那两枚改前兜底（KDoc 逐字引的，「?: 20」枚数 " +
                "${occurrences(readMainSource(KERNEL), "?: 20")}，期望 2 处：两枚病灶各一枚），" +
                "而抹注释后必须归零 —— 这一对是「探测器能看见真东西」的对照组",
            2,
            occurrences(readMainSource(KERNEL), "?: 20"),
        )
    }

    // ─────────────── ③ showWeekNav 只许走内核 ───────────────

    @Test
    fun `周次导航那一维必须走内核`() {
        val home = blankCommentsKeepingLiterals(readMainSource(HOME_SCREEN))
        val toolbar = scheduleToolbarCallArguments(home)

        assertEquals(
            "「$NAV_WIRING」出现 ${occurrences(home, NAV_WIRING)} 处，期望恰好 1 处：簇画不画只许由内核回答，" +
                "0 处就是有人把判据又现写了一遍",
            1,
            occurrences(home, NAV_WIRING),
        )
        assertEquals(
            "改前那一形「showWeekNav = selectedTab == 0」出现 ${occurrences(home, LEGACY_NAV_LINE)} 处，" +
                "期望 0 处：只看页签就是没学期也照给那 20 周的导航",
            0,
            occurrences(home, LEGACY_NAV_LINE),
        )
        // 绕过内核、在调用点现写合取（变异 3 的那一形）同样是病灶
        val inline = Regex("""showWeekNav\s*=\s*(?:selectedTab[^,\n]*&&|[^,\n]*semester[^,\n]*&&)""").findAll(home).toList()
        assertEquals(
            "showWeekNav 的实参里现写着合取（${inline.size} 处，期望 0 处，落点在第 " +
                "${inline.firstOrNull()?.let { lineOf(home, it.range.first) } ?: -1} 行附近）：" +
                "页签与学期两维的判据只许有一份，写在调用点上就是下一轮改一处漏一处",
            0,
            inline.size,
        )
        assertEquals(
            "递给工具条的「$HAS_SEMESTER_ARG」出现 ${occurrences(toolbar, HAS_SEMESTER_ARG)} 处，期望恰好 1 处：" +
                "工具条那两枚判据（清单与进度线）都吃它，多一枚就是在同一份实参表里又派生了一遍" +
                "「有没有学期」（首页那一枚 weekHeadline 自己吃的是同一枚 state.semester，不在本守卫射程）",
            1,
            occurrences(toolbar, HAS_SEMESTER_ARG),
        )
        assertEquals(
            "递给工具条的「$TOTAL_WEEKS_ARG」出现 ${occurrences(toolbar, TOTAL_WEEKS_ARG)} 处，期望恰好 1 处：" +
                "T331② 重钉 —— 改前这里是 `totalWeeks = state.semester?.totalWeeks?.coerceIn(1, …MAX_TOTAL_WEEKS)`" +
                "（脏学期行被折成 1 递给屏，#452 那一格「这学期 1 周」就是这么念出来的），现在只许递具名读数。" +
                "0 处 = 顶栏又不问读数了；2 处 = 同一份实参表里又派生了一遍周数",
            1,
            occurrences(toolbar, TOTAL_WEEKS_ARG),
        )
        assertTrue(
            "递进工具条的那一枚必须是读数的答案（「$TOTAL_WEEKS_READING_CALL」实到 " +
                "${occurrences(toolbar, TOTAL_WEEKS_READING_CALL)} 处，期望恰 1 处）：" +
                "清单与进度线吃的就是它 ⇒ 顶栏读数与它同源于那一枚读数",
            occurrences(toolbar, TOTAL_WEEKS_READING_CALL) == 1,
        )
        assertEquals(
            "「$TOTAL_WEEKS_NULL_ARG」那一形（就地夹在读参数上的旧写法）在工具条实参表里必须 0 处，实到 " +
                "${occurrences(toolbar, TOTAL_WEEKS_NULL_ARG)} 处：它就是 #452 的屏上那一半，病灶不许回潮",
            0,
            occurrences(toolbar, TOTAL_WEEKS_NULL_ARG),
        )
        // 簇的进出条件仍只认 showWeekNav（#113/#118 那两笔权重账的前提：常驻加权槽 + 簇自身一枚 weight）
        val (args, content) = weekNavCluster(home)
        assertEquals(
            "簇那枚 AnimatedVisibility 的 visible 不再只吃 showWeekNav：$args",
            listOf("showWeekNav"),
            Regex("""visible\s*=\s*([A-Za-z0-9_]+)""").findAll(args).map { it.groupValues[1] }.toList(),
        )
        assertEquals(
            "簇的内容块里又出现「showWeekNav」（枚数 ${occurrences(content, "showWeekNav")}，期望 0 处）：" +
                "进出条件只许挂在 AnimatedVisibility 那一层的 visible 上，往里搬就是有人在同一段版面里" +
                "各判一遍画不画",
            0,
            occurrences(content, "showWeekNav"),
        )
        assertEquals(
            "簇的内容块里现写着「state.semester」（枚数 ${occurrences(content, "state.semester")}，期望 0 处）：" +
                "工具条吃的是实参（hasSemester / totalWeeks），从组合里回头再读一遍页面状态就是第二份判据",
            0,
            occurrences(content, "state.semester"),
        )
    }

    // ─────────────── ④ 三枚消费点：键配对 / 进度线闸门 / 分母 ───────────────

    @Test
    fun `清单的 remember 键配对不许漂`() {
        val home = blankCommentsKeepingLiterals(readMainSource(HOME_SCREEN))

        assertEquals(
            "「$CHOICE_WIRING」出现 ${occurrences(home, CHOICE_WIRING)} 处，期望恰好 1 处：" +
                "键仍是 totalWeeks、来源换成内核。旁边那段注释讲的是「键与来源配成一对才不白干活」，" +
                "把键换成清单本身（remember(weekChoices)）就是作废那段账 —— 本卡明确不许",
            1,
            occurrences(home, CHOICE_WIRING),
        )
        for (banned in listOf("remember(weekChoices)", "(1..totalWeeks).toList()")) {
            assertEquals(
                "HomeScreen 里出现「$banned」（枚数 ${occurrences(home, banned)}，期望 0 处）：" +
                    "前者是把清单本身当键（那一格每次重组都换一份 List，记住=没记住），" +
                    "后者是清单来源又回到就地物化 —— 两形都是本卡收的东西",
                0,
                occurrences(home, banned),
            )
        }
        assertEquals(
            "「items(weekChoices, key = { it })」的枚数应当恰好 1（清单消费端形状不许改）：实到 " +
                "${occurrences(home, "items(weekChoices, key = { it })")}",
            1,
            occurrences(home, "items(weekChoices, key = { it })"),
        )
    }

    @Test
    fun `学期进度线必须被闸门把关`() {
        val home = blankCommentsKeepingLiterals(readMainSource(HOME_SCREEN))

        assertEquals(
            "「$PROGRESS_WIRING」出现 ${occurrences(home, PROGRESS_WIRING)} 处，期望恰好 1 处：" +
                "没学期时这条轨道根本不该在场（currentWeek=null 只把填充归零，96dp 的素色轨道照画，" +
                "而那条轨道说的「这学期多少周」并不存在）",
            1,
            occurrences(home, PROGRESS_WIRING),
        )
        val total = mainHits("SemesterProgressLine(").sumOf { it.second }
        assertEquals(
            "main 里「SemesterProgressLine(」共 $total 处，期望 2 处（组件定义 1 + 首页这一处消费）：" +
                "多一枚就是又有一处在画学期进度",
            2,
            total,
        )
    }

    @Test
    fun `不是每周都有的分母必须走内核`() {
        val week = blankCommentsKeepingLiterals(readMainSource(WEEK_VIEW))

        assertEquals(
            "「$WEEK_COUNTS_WIRING」出现 ${occurrences(week, WEEK_COUNTS_WIRING)} 处，期望恰好 1 处：" +
                "Pager 页数与卡角旗的分母必须同源（同一屏上「这学期多少周」只许一个答案）",
            1,
            occurrences(week, WEEK_COUNTS_WIRING),
        )
        assertEquals(
            "「$FLAG_WIRING」出现 ${occurrences(week, FLAG_WIRING)} 处，期望恰好 2 处（24 小时时间轴那一支 + " +
                "节次行那一支）：改前是「course.weeks.size < totalWeeks」，假 20 当分母时读屏替我们念" +
                "「不是每周都有」",
            2,
            occurrences(week, FLAG_WIRING),
        )
        assertEquals(
            "「notEveryWeekBase = notEveryWeekBase,」递给网格的枚数应当恰好 2（两支 WeekGrid 各一枚）：实到 " +
                "${occurrences(week, "notEveryWeekBase = notEveryWeekBase,")}",
            2,
            occurrences(week, "notEveryWeekBase = notEveryWeekBase,"),
        )
    }

    // ─────────────── ⑤ 全仓只一份定义：落点册子点清 ───────────────

    @Test
    fun `五枚判据的定义与调用点册子`() {
        val ledger: List<Triple<String, Map<String, Int>, Int>> = listOf(
            Triple("shouldOfferWeekNavigation(", mapOf(KERNEL_NAME to 1, HOME_SCREEN_NAME to 1), 2),
            Triple("weekChoicesFor(", mapOf(KERNEL_NAME to 1, HOME_SCREEN_NAME to 1), 2),
            Triple("shouldDrawSemesterProgress(", mapOf(KERNEL_NAME to 1, HOME_SCREEN_NAME to 1), 2),
            Triple("weekCountsOf(", mapOf(KERNEL_NAME to 1, WEEK_VIEW_NAME to 1), 2),
            Triple("notEveryWeekFlag(", mapOf(KERNEL_NAME to 1, WEEK_VIEW_NAME to 2), 3),
        )
        for ((needle, expected, total) in ledger) {
            val hits = mainHits(needle)
            val actual = hits.associate { File(it.first).name to it.second }
            assertEquals(
                "main 里「$needle」的落点册子漂了：期望 $expected，实到 $actual —— 定义一枚 + 调用点逐枚点名。" +
                    "第二处定义就是第二份能各自写错的判据（DayView / 组件侧抄一份就会各错各的，" +
                    "与 T182 那条「两处界面各答一遍」同一族）",
                expected,
                actual,
            )
            assertEquals(
                "main 里「$needle」的总枚数不是 $total（定义 1 + 调用点若干）：实到 ${hits.sumOf { it.second }} 处",
                total,
                hits.sumOf { it.second },
            )
        }
    }

    // ─────────────── ⑥ 反套套逻辑：探测器认得改前那一形 ───────────────

    /**
     * 探测器就是那枚 `?: 20` 与改前那两行原文。它必须：
     * - 在改前那一行原文上各抓到 1 处（否则第②层那句「0 处」全是空的）；
     * - 在本仓既有的合法夹取与合法兜底上抓 0 处（首页「上一周」按钮、周视图 Pager 的夹取、统计页那枚
     *   `currentWeek ?: 1`），把合法写法一起扫红的尺会逼下一张卡把守卫改松；
     * - 在设置页那枚第三等账（`weeksNumber ?: 20`）上抓到恰好 1 处：既证明这把尺真在读盘，
     *   也证明本卡没顺手把不相干的族一起收掉；
     * - 在内核文件的原文里各抓到 1 处（KDoc 逐字引了病灶原文），抹注释后 0 处 ——
     *   证明「抹注释保字面量」那一步真的在挡假阳性，而不是随手写的一句注释。
     */
    @Test
    fun `探测器抓得住改前那一形 也不误伤合法夹取`() {
        val probe = Regex("""\?:\s*20\b""")

        for (legacy in listOf(LEGACY_HOME_LINE, LEGACY_WEEK_LINE)) {
            assertEquals(
                "探测器在改前那一行原文「$legacy」上抓到 ${probe.findAll(legacy).toList().size} 处，期望 1 处：" +
                    "它连病灶都不认得，上面那几层「0 处」全是空的",
                1,
                probe.findAll(legacy).toList().size,
            )
        }
        for (legit in listOf(
            "onClick = { onBrowseWeekChange(((displayWeek ?: 1) - 1).coerceAtLeast(1)) }",
            "initialPage = (pagerWeek - 1).coerceIn(0, totalWeeks - 1),",
            "selectedWeek = (currentWeek ?: 1).coerceIn(1, totalWeeks.coerceAtLeast(1))",
            "totalWeeks = (state.semester?.totalWeeks ?: 1).coerceIn(1, CourseConstraints.MAX_TOTAL_WEEKS)",
        )) {
            assertEquals(
                "探测器在本仓既有的合法写法「$legit」上抓到 ${probe.findAll(legit).toList().size} 处，期望 0 处：" +
                    "把合法夹取与合法兜底一起扫红，下一张卡只会把守卫改松",
                0,
                probe.findAll(legit).toList().size,
            )
        }
        // 第三等账：设置页那一枚仍在（本卡不许动它，动了这格就会从 1 变 0）
        val settings = blankCommentsKeepingLiterals(readMainSource(SETTINGS_SCREEN))
        assertEquals(
            "「weeksNumber ?: 20」在 $SETTINGS_NAME 里出现 ${occurrences(settings, "weeksNumber ?: 20")} 处，" +
                "期望恰好 1 处：那是用户填周数时的缺省，与首页那两枚「界面拿假 20 当真数」不同族，" +
                "卡面 §2.3 点名的第三等账不许顺手改",
            1,
            occurrences(settings, "weeksNumber ?: 20"),
        )

        val kernelRaw = readMainSource(KERNEL)
        val kernelBlanked = blankCommentsKeepingLiterals(kernelRaw)
        for (legacy in listOf(LEGACY_HOME_LINE, LEGACY_WEEK_LINE)) {
            assertEquals(
                "内核文件原文里应当留着改前那一形（KDoc 逐字引的，「$legacy」枚数 " +
                    "${occurrences(kernelRaw, legacy)}，期望 1 处）：这一枚是「探测器能看见真东西」的对照组",
                1,
                occurrences(kernelRaw, legacy),
            )
            assertEquals(
                "同一枚内核文件抹注释之后应当 0 处（「$legacy」实到 ${occurrences(kernelBlanked, legacy)} 处）：" +
                    "注释里写着被禁写法是本仓文档的常态，不抹掉就到处是假阳性",
                0,
                occurrences(kernelBlanked, legacy),
            )
        }
    }

    // ─────────────── ⑦ T190/T182 的接线不许被本卡带走 ───────────────

    @Test
    fun `上一轮刚落地的两处接线仍在原位原样`() {
        val week = blankCommentsKeepingLiterals(readMainSource(WEEK_VIEW))

        for (kept in listOf(
            "val pagerWeek = displayWeek ?: currentWeek",
            "val displayWeekNumber = headerWeekNumberOrNull(displayWeek, currentWeek)",
            "shouldHighlightToday(displayWeek, currentWeek, hasSemester = semester != null)",
            "showAllCoursesWithoutWeek = semester == null",
        )) {
            assertEquals(
                "WeekView.kt 里「$kept」出现 ${occurrences(week, kept)} 处，期望恰好 1 处：那是 T182/T190 " +
                    "刚落地的判据接线，本卡只许动那一枚二十周的兜底与它下游的旗",
                1,
                occurrences(week, kept),
            )
        }
    }

    // ---------------- helpers ----------------

    /** 调用点那一枚 `ScheduleToolbarRow(...)` 的实参表（配平到与之匹配的右括号，含两端） */
    private fun scheduleToolbarCallArguments(code: String): String {
        for (match in Regex("""\bScheduleToolbarRow\(""").findAll(code)) {
            val at = match.range.first
            if (code.substring(0, at).trimEnd().endsWith("fun")) continue
            return balancedArguments(code, match.range.last)
        }
        throw AssertionError("HomeScreen 里没有 ScheduleToolbarRow 的调用点：整条没了，本守卫要重新核")
    }

    /** 簇那枚 `visible = showWeekNav` 的 AnimatedVisibility：返回（实参表, 内容块） */
    private fun weekNavCluster(code: String): Pair<String, String> {
        val row = balancedBlock(code, "private fun ScheduleToolbarRow(")
        for (match in Regex("""\bAnimatedVisibility\(""").findAll(row)) {
            val open = match.range.last
            val args = balancedArguments(row, open)
            if (!args.contains("showWeekNav")) continue
            val content = balancedBraces(row, row.indexOf('{', open + args.length - 1))
            return args to content
        }
        throw AssertionError("ScheduleToolbarRow 里没有 visible = showWeekNav 的 AnimatedVisibility：簇整个没了，本守卫要重新核")
    }

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
        const val WEEK_VIEW = "com/buaa/schedule/ui/home/WeekView.kt"
        const val SETTINGS_SCREEN = "com/buaa/schedule/ui/settings/SettingsScreen.kt"
        const val KERNEL_NAME = "WeekNavigationAvailability.kt"
        const val HOME_SCREEN_NAME = "HomeScreen.kt"
        const val WEEK_VIEW_NAME = "WeekView.kt"
        const val SETTINGS_NAME = "SettingsScreen.kt"

        /** 改前那两枚兜底的原文（探测器与「病灶不回潮」两层的共同标尺） */
        const val LEGACY_HOME_LINE = "totalWeeks = (state.semester?.totalWeeks ?: 20)"
        const val LEGACY_WEEK_LINE = "val totalWeeks = (semester?.totalWeeks ?: 20)"

        /** 改前清单与分母的原文（同一枚假 20 的下游两处消费） */
        const val LEGACY_CHOICE_LINE = "val weekChoices = remember(totalWeeks) { (1..totalWeeks).toList() }"
        const val LEGACY_FLAG_LINE = "notEveryWeek = course.weeks.size < totalWeeks"

        /**
         * 改前那两枚就地夹取的针（T331② 之后必须归零）：夹取只许长在具名读数里那一枚，
         * 内核与顶栏都改问 [SemesterWeekCount.readable]。
         */
        const val CLAMP = "coerceIn(1, CourseConstraints.MAX_TOTAL_WEEKS)"
        const val READING_CALL = "SemesterWeekCount.readable("

        /** 第三等账那一枚不许顺手改：全 main 抹注释后「?: 20」只剩它 */
        val THIRD_ACCOUNT_LEDGER = mapOf(SETTINGS_NAME to 1)

        const val LEGACY_NAV_LINE = "showWeekNav = selectedTab == 0"
        const val NAV_WIRING =
            "showWeekNav = shouldOfferWeekNavigation(selectedTab, state.semester != null, weekTab = 0)"
        const val HAS_SEMESTER_ARG = "hasSemester = state.semester != null"
        const val TOTAL_WEEKS_ARG = "totalWeeks = com.buaa.schedule.domain.schedule.SemesterWeekCount"
        const val TOTAL_WEEKS_READING_CALL = ".readable(state.semester?.totalWeeks)"

        /** 改前那一形（就地夹在读参数上，对屏念出「这学期 1 周」的那一枚）：现在必须 0 处 */
        const val TOTAL_WEEKS_NULL_ARG = "?.coerceIn(1, com.buaa.schedule.domain.schedule.CourseConstraints.MAX_TOTAL_WEEKS)"
        const val CHOICE_WIRING = "val weekChoices = remember(totalWeeks) { weekChoicesFor(hasSemester, totalWeeks) }"
        const val PROGRESS_WIRING = "if (shouldDrawSemesterProgress(hasSemester)) SemesterProgressLine("
        const val WEEK_COUNTS_WIRING =
            "val (totalWeeks, notEveryWeekBase) = weekCountsOf(semester != null, semester?.totalWeeks, courses)"
        const val FLAG_WIRING = "notEveryWeek = notEveryWeekFlag(course.weeks.size, notEveryWeekBase)"
    }
}
