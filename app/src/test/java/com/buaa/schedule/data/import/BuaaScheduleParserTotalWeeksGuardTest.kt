package com.buaa.schedule.data.import

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T216③：把台账 #238 病灶 B 的两格钉成静态守卫 ——
 * ①「签名默认值 `totalWeeks: Int = 20` 的落点册子」与②「`fallbackWeekCourses` 这枚计数到底有没有
 * 进用户可见报数」。读 `app/src/main` 工作树文本，纯 JVM、无 Robolectric、无设备、不读时钟。
 *
 * 输入面：`app/src/main/java` 整目录已在 `app/build.gradle.kts` 的 `guardReadWorkingTreeFiles` 里进
 * `tasks.withType<Test>()` 的 `inputs`（T92①，台账 #136），"改了源码、单跑守卫拿缓存绿灯"那一档盖得住；
 * 本卡没有新增**目录之外**的读取面，不动构建脚本。
 *
 * ## 钉法四家逐格点名（卡面 §3-2 要的就是这一栏）
 *
 * 1. **数出现次数（册子）** —— 第 ① 层：`totalWeeks: Int = 20` 在全 `app/src/main` 恰好 **2** 处、
 *    册子 `{BuaaScheduleParser.kt=2}`；`data/import/` 那一层也是 2 处。第 ③ 层同一手法数
 *    `fallbackWeekCourses` 的消费者册子与那句文案的全仓枚数。
 * 2. **逐字节** —— 第 ① 层那两枚签名行；第 ③ 层的预览文案、那两枚 `if` 的条件、以及
 *    「计数进预览」的**落点名册**（`PREVIEW_HOP_ROSTER` 四跳 + `PREVIEW_CONTRACT` 契约一枚，每枚都判
 *    「全 app/src/main 的册子恰好 `{该文件=期望枚数}`」）。这本网页在 T216 是一枚裸针
 *    `viewModel.previewBuaaCourses(semester, parsed.courses, parseWarnings)`；T336 把抓取本体搬进
 *    `data/import/BuaaImportJob.kt` 之后那一行不再成行（作业体的接收者换成 `BuaaImportJob.Host`，
 *    问 viewModel 的那一跳挪进 Host 覆写），性质没变、手数从一枚变四枚 ⇒ 逐枚点名而不是改个裸针。
 * 3. **读形状** —— 第 ② 层按**圆括号配平**取实参表、数顶层逗号（"省着不传"当场红）；第 ③ 层按
 *    **花括号配平**取那枚 `if` 的分支体（文案必须真在这一支体内）；第 ④ 层取 `refreshSchedule` 的
 *    函数体数 `add(`（那条链今天只有一档警告）。
 * 4. **抹注释找锚点** —— 第 ③ 层末尾那一格：`parseArrangedList` 在 `BuaaLoginScreen.kt` 的**含注释**
 *    原文里 2 处（一处是"此前走 parseArrangedList 把警告整个丢掉了"那句历史说明）、**抹注释后** 1 处。
 *    只数原文会把历史说明当成第二枚调用，只数抹注释后的会放过"预览页退回薄壳"那一手 ⇒ 两侧一起钉。
 *
 * ## 两侧的红臂（第 ⑤ 层另摆内联样例，本守卫自己也得是活的）
 *
 * - **朝松**：`BuaaWebSession.refreshSchedule` 那枚调用省掉 `info.totalWeeks` ⇒ 第 ② 层实参枚数格红；
 * - **改名躲针**：再往 `data/import/` 的某枚签名上加第三枚默认 20 ⇒ 第 ① 层两把册子尺一起红；
 * - **文案搬家 / 摘掉**：把那句「已按整学期展示」挪出 `if (parsed.fallbackWeekCourses > 0)` 那一支 ⇒
 *   第 ③ 层读形状格红；整句删掉 ⇒ 逐字节格红。
 *
 * ⚠️ 本守卫**不修行为，也不装成已修**。第 ④ 层钉的是「静默刷新那一路今天零处读 `fallbackWeekCourses`」
 * 这一件**事实**：卡面 §2-B 说这枚计数没进用户可见报数 ⇒ 按盘面**驳回一半**（首次导入预览那一路进了，
 * 就是第 ③ 层钉的那句；刷新那一路没进）。把缺口钉成册子而不是钉成绿灯，是为了让接它的那张卡一定撞红
 * 一次、必须显式改判据，而不是悄悄多一个消费者。
 */
class BuaaScheduleParserTotalWeeksGuardTest {

    // ─────────────── ① 签名默认值 20：册子只许这两枚 ───────────────

    @Test
    fun `默认 20 只许长在解析器那两枚签名上 别处再长就红`() {
        val byFile = readMainSources()
        val importDir = readImportDir()
        val parser = source(PARSER)

        assertEquals(
            "**钉法 = 数出现次数（全 main 册子）**：`$DEFAULT_20` 在全 app/src/main 恰好 2 处（实到 " +
                occurrencesOfAll(byFile, DEFAULT_20) + " 处，册子 " + ledger(byFile, DEFAULT_20) +
                "）：两枚都在 `$PARSER` —— `parseArrangedList` 与 `parseArrangedListOutcome` 的签名各一枚。" +
                "卡面 §2-B-1 问「这枚默认值今天真的在决定用户看到的周次范围吗」：第 ② 层量下来**不决定**" +
                "（两枚生产调用点都传满），所以它不该再多一处，也不该长出第三枚签名默认值",
            2,
            occurrencesOfAll(byFile, DEFAULT_20),
        )
        assertEquals(
            "**钉法 = 数出现次数（落点册子）**：落点必须只有 `" + PARSER_NAME + "`（实到 " +
                filesHolding(byFile, DEFAULT_20) + "）：`data/import/` 之外再落一枚默认 20 的话这一格红",
            listOf(PARSER_NAME),
            filesHolding(byFile, DEFAULT_20),
        )
        assertEquals(
            "**钉法 = 数出现次数（按目录册子）**：`data/import/` 这一层里 `$DEFAULT_20` 恰好 2 处（实到 " +
                occurrencesOfAll(importDir, DEFAULT_20) + " 处，册子 " + ledger(importDir, DEFAULT_20) +
                "）。卡面 §3-2 给的两种写法是「归零」或「按判定写成册子」—— 本卡按**册子**钉：" +
                "今天这 2 处是如实保留的既有形状（生产零依赖、省略只长在 JVM 单测里，第 ② 层量），" +
                "删默认值要连坐六枚既有测试调用点，超出本卡白名单（只许改解析器 + 那枚姊妹守卫 + 新建测试）",
            2,
            occurrencesOfAll(importDir, DEFAULT_20),
        )
        assertEquals(
            "**钉法 = 逐字节**：签名行 `$SIGNATURE_LINE` 在 `$PARSER` 恰好 2 处（实到 " +
                occurrences(parser, SIGNATURE_LINE) + " 处）：逐字节钉的是那两枚**带缩进的整行** —— " +
                "把它换成 `totalWeeks: Int = DEFAULT_TERM_WEEKS`（值仍是 20、针从此消失）这一格会红，" +
                "而第 ② 层那把调用点尺盖不住那一手，两格互为备份",
            2,
            occurrences(parser, SIGNATURE_LINE),
        )
    }

    // ─────────────── ② 生产调用点：两枚，且零省略 ───────────────

    @Test
    fun `两枚生产调用点都把 totalWeeks 传满 谁省着不传就红`() {
        val byFile = readMainSources()

        val expectedCallSites = listOf(CALL_LIST to WEB_NAME, CALL_OUTCOME to LOGIN_NAME)
        for ((needle, expectedFile) in expectedCallSites) {
            val hits = byFile.entries.toList().sortedBy { it.key }
                .flatMap { (name, text) -> List(occurrences(text, needle)) { name } }
            assertEquals(
                "**钉法 = 逐字节 + 数出现次数**：调用点针 `$needle` 在全 app/src/main 恰好 1 处、" +
                    "且必须落在 `$expectedFile`（实到 ${hits.size} 处，落点 $hits）。今天只有两枚生产调用点：" +
                    "静默刷新那一路（`BuaaWebSession.refreshSchedule` 用 `$CALL_LIST`）与首次导入那一路" +
                    "（`BuaaLoginScreen` 的抓取分支用 `$CALL_OUTCOME`）。针前面的点号把解析器里那两枚**定义**" +
                    "与内部委派排除掉（定义那一枚由下面的 `fun …(` 那格管）",
                listOf(expectedFile),
                hits,
            )
            val text = byFile.getValue(expectedFile)
            val at = text.indexOf(needle)
            val args = topLevelArgCount(text, at + needle.length)
            assertEquals(
                "**钉法 = 读形状**：`$expectedFile` 里 `$needle` 那一枚调用的**顶层实参必须恰好 3 枚**" +
                    "（实到 $args 枚）：圆括号配平之后数顶层逗号。省掉第三枚（`totalWeeks`）= 那一路上第一次" +
                    "让「这学期几周」由一枚没有来源的签名默认值决定；第 ① 层那把册子尺管不到「调用点省略」，" +
                    "管不到就必须在这里管住",
                3,
                args,
            )
        }

        val parser = source(PARSER)
        for (definition in listOf("fun parseArrangedList(", "fun parseArrangedListOutcome(")) {
            assertEquals(
                "**钉法 = 数出现次数**：`$definition` 在 `$PARSER` 恰好一处（实到 " +
                    occurrences(parser, definition) + " 处）：默认值就挂在这两枚定义上，多一枚重载 = " +
                    "多一扇可以「省着不传」的门",
                1,
                occurrences(parser, definition),
            )
        }
    }

    // ─────────────── ③ 报数消费者册子 + 文案接线 ───────────────

    @Test
    fun `兜底计数进预览那一路的文案 逐字节与读形状都在`() {
        val byFile = readMainSources()
        val screen = source(LOGIN_SCREEN)

        assertEquals(
            "**钉法 = 数出现次数（册子）**：`fallbackWeekCourses` 全 app/src/main 的落点册子必须恰好是 " +
                "{$LOGIN_NAME, $PARSER_NAME}（实到 " + filesHolding(byFile, "fallbackWeekCourses") +
                "，枚数册子 " + ledger(byFile, "fallbackWeekCourses") + "）：解析器那 2 处是声明与计数点，" +
                "预览页那 3 处是「判 > 0」「念进文案」「第二枚 if 的互斥条件」。册子里多一枚（有人把计数接到" +
                "别的路上了）或少一处（接线被拆）都红 —— 这一格就是「消费者普查」本身",
            listOf(LOGIN_NAME, PARSER_NAME),
            filesHolding(byFile, "fallbackWeekCourses"),
        )
        assertEquals(
            "**钉法 = 数出现次数**：`fallbackWeekCourses` 全仓恰好 5 处（实到 " +
                occurrencesOfAll(byFile, "fallbackWeekCourses") + " 处，册子 " +
                ledger(byFile, "fallbackWeekCourses") + "）：枚数与落点一起数，才拦得住" +
                "「在别处再读一遍同一枚计数、口径写歪」那一档",
            5,
            occurrencesOfAll(byFile, "fallbackWeekCourses"),
        )
        assertEquals(
            "**钉法 = 逐字节**：预览文案针 `$FALLBACK_COPY` 在全 app/src/main 恰好 1 处、只长在 " +
                LOGIN_NAME + "（实到 " + occurrencesOfAll(byFile, FALLBACK_COPY) + " 处，册子 " +
                ledger(byFile, FALLBACK_COPY) + "）—— 这就是「把补出来的周次念出来」那一半今天的形状：" +
                "`N 条课程缺少教师/周次信息，已按整学期展示，请核对`",
            1,
            occurrencesOfAll(byFile, FALLBACK_COPY),
        )
        assertEquals(
            "**钉法 = 逐字节**：文案那一支的条件针 `$FALLBACK_BRANCH` 恰好 1 处（实到 " +
                occurrences(screen, FALLBACK_BRANCH) + " 处）",
            1,
            occurrences(screen, FALLBACK_BRANCH),
        )
        val branch = branchBody(screen, FALLBACK_BRANCH)
        assertEquals(
            "**钉法 = 读形状**：`$FALLBACK_BRANCH` 那一支体内 `$FALLBACK_COPY` 恰好 1 处（实到 " +
                occurrences(branch, FALLBACK_COPY) + " 处）：文案挪去别的分支、或把 `add(…)` 换成写日志，" +
                "这一格红 —— 「计数进了 ParseOutcome 却没进报数」正是本卡要拦的那一手",
            1,
            occurrences(branch, FALLBACK_COPY),
        )
        assertEquals(
            "**钉法 = 读形状**：同一支体内必须念出计数本身（`parsed.fallbackWeekCourses` 实到 " +
                occurrences(branch, "parsed.fallbackWeekCourses") + " 处，期望 1）：只写一句" +
                "「有些课的周次是补出来的」而不带枚数，等于把 #229 那一族的报数又折回一句空话",
            1,
            occurrences(branch, "parsed.fallbackWeekCourses"),
        )
        // T336「教务抓取换载体」把这一跳拆成了四跳：作业体（`BuaaLoginScreen` 里那枚 `BuaaImportJob.Host`
        // 扩展函数，接收者不再是 `viewModel`）→ 进程内 holder 朝当前活着的 Host 转发 →（页面已离开时
        // 三枚一起攒进 pendingPreview，attach 时逐枚交回）→ 页面那枚 Host 覆写 → viewModel。
        // 旧的那枚「最后一跳」针 `viewModel.previewBuaaCourses(semester, parsed.courses, parseWarnings)`
        // 在盘上不再成行 —— 所以这里钉的是**一整本落点名册**：把警告递下去的每一枚手都得逐字节在册，
        // 少一枚红、多一枚没被点名的同样红。为什么必须一枚都不许省：
        // `ScheduleViewModel.previewBuaaCourses` 的第三枚形参带 `= emptyList()` 默认值，
        // **任何一跳省着不传都是编译得过的**，省下来的那一跳正好把 #238 病灶 B 放回静默。
        for ((file, role, needle) in PREVIEW_HOP_ROSTER) {
            assertEquals(
                "**钉法 = 逐字节 + 数出现次数（落点名册）**：`$needle` 的册子必须是 `{$file=1}`（实到 " +
                    ledger(byFile, needle) + "）：它是「兜底计数进预览」这条链上的 $role。" +
                    "摘掉这一跳的第三枚实参（或把它换成 `emptyList()` / `parseWarnings.size` 一类）" +
                    "都从这一格露出来，而上一格那些「文案在不在分支体内」的尺全都盖不住它",
                "{$file=1}",
                ledger(byFile, needle),
            )
        }
        assertEquals(
            "**钉法 = 逐字节 + 数出现次数（契约）**：`$PREVIEW_CONTRACT` 的册子必须是 `{$JOB_NAME=2}`" +
                "（实到 " + ledger(byFile, PREVIEW_CONTRACT) + "）：`BuaaImportJob.Host` 的接口声明与 holder " +
                "的覆写各一枚 —— 第三枚形参 `warnings: List<String>` **在契约里没有默认值**，这就是" +
                "「谁想把警告这一维从链路上摘掉就得连签名一起改、改就要被点名」的那道门",
            "{$JOB_NAME=2}",
            ledger(byFile, PREVIEW_CONTRACT),
        )
        assertEquals(
            "**钉法 = 读形状**：页面那枚 Host 覆写的参数表里必须还写着第三枚 `warnings: List<String>,`" +
                "（在 `$LOGIN_NAME` 恰好 1 处，实到 " + occurrences(screen, "warnings: List<String>,") +
                " 处）：覆写把形参删掉、体内再自己 `emptyList()` 交下去，上面四跳全绿而用户一个字看不见 —— " +
                "这一格堵的就是这条路",
            1,
            occurrences(screen, "warnings: List<String>,"),
        )
        assertEquals(
            "**钉法 = 逐字节**：第二枚 if 的互斥条件必须逐字在此（实到 " + occurrences(screen, SECOND_BRANCH) +
                " 处）：`unknownTeacherCourses` 那一句只在「没有兜底课」时才念 ⇒ 两样混在一批时，" +
                "只缺教师的那几门没人念（T216 量出来的第二处报数缺口，`BuaaFallbackWeeksOutcomeTest` " +
                "有对应格子；修它要动这份文件，不在本卡白名单 ⇒ 登记给编排者）",
            1,
            occurrences(screen, SECOND_BRANCH),
        )
    }

    @Test
    fun `预览页必须走 Outcome 版 那枚历史说明留在注释里`() {
        val raw = rawSource(LOGIN_SCREEN)
        val screen = blankCommentsKeepingLiterals(raw)

        // 钉法④（抹注释找锚点）：这一格是本文件唯一一处「含注释 / 抹注释」两侧枚数真的不同的针
        assertEquals(
            "**钉法 = 抹注释找锚点**：`parseArrangedList` 在**含注释**的 `$LOGIN_SCREEN` 原文里恰好 " +
                RAW_PARSE_LIST_IN_LOGIN + " 处（实到 " + occurrences(raw, BARE_CALL) + " 处）：一枚是第 " +
                "230-231 行那句历史说明「此前走 parseArrangedList 把警告整个丢掉了」，一枚是那枚 Outcome 版调用" +
                "（`parseArrangedListOutcome` 的前缀也算它）。说明被删掉 ⇒ 这一格红（那道理由得留在盘上）",
            RAW_PARSE_LIST_IN_LOGIN,
            occurrences(raw, BARE_CALL),
        )
        assertEquals(
            "**钉法 = 抹注释找锚点**：同一枚针在**抹注释后**的代码里恰好 " + CODE_PARSE_LIST_IN_LOGIN +
                " 处（实到 " + occurrences(screen, BARE_CALL) + " 处）：预览页今天只调 Outcome 版那一路。" +
                "把它改回丢掉计数的薄壳 ⇒ 代码侧变 2 处、这一格当场红（而上一格会告诉你注释里那句" +
                "「此前走 parseArrangedList」原来不是白写的）",
            CODE_PARSE_LIST_IN_LOGIN,
            occurrences(screen, BARE_CALL),
        )
    }

    // ─────────────── ④ 刷新那一路：今天零处读计数（钉事实，不钉愿望） ───────────────

    @Test
    fun `刷新那一路今天不念兜底计数 接上它时必须改这一格`() {
        val session = source(WEB_SESSION)
        val viewModel = source(VIEW_MODEL)

        assertEquals(
            "`fallbackWeekCourses` 在 `$WEB_SESSION` 必须 0 处（实到 " +
                occurrences(session, "fallbackWeekCourses") + " 处）：静默刷新那一路走的是**丢计数的薄壳** " +
                "`$CALL_LIST`（`$PARSER` 段首那格 KDoc 记的就是这一手）⇒ 教务没给周次信息时" +
                "「这几门课的周次是补出来的」在那一屏没有任何一句替它说话（台账 #238 病灶 B 的第二跳）。" +
                "⚠️ 这一格不是「已修好」的凭证：把刷新那一路改走 Outcome 版 + 递 warnings 要动 " +
                "`BuaaWebSession.kt` 与 `ScheduleViewModel.kt`，超出本卡白名单 ⇒ 落地那张卡**必须把这里的 0 改成非 0**",
            0,
            occurrences(session, "fallbackWeekCourses"),
        )
        assertEquals(
            "**钉法 = 读形状**：`refreshSchedule` 体内 `add(` 恰好 1 处（实到 " + addCountInRefresh(session) +
                " 处）：那条链今天的 warnings 只由「教学周抓取失败」那一档喂，一节都不许当成" +
                "「已经有两档在喂」来改 —— 数的是形状，不是印象",
            1,
            addCountInRefresh(session),
        )
        assertEquals(
            "刷新完成那一句（`$REFRESH_DONE_COPY` 起手）在 `$VIEW_MODEL` 恰好 1 处，且它所在那一行里 " +
                "`warnings` 必须 0 处（实到 " + occurrences(viewModel, REFRESH_DONE_COPY) + " 处 / " +
                refreshLineReadsWarnings(viewModel) + " 处）：`fetched.warnings` 今天只在「未完成」那一支被念，" +
                "完成那一支整段不读 —— #229 那一族在刷新链上的形状，本卡只量不修",
            0,
            refreshLineReadsWarnings(viewModel),
        )
    }

    // ─────────────── ⑤ 反套套逻辑：两枚探测器都得自己抓得到 ───────────────

    /** 只报「盘上几处」而探测器其实数不清，等于没有守卫（本仓口径：别拿 grep 没匹配到当全绿）。 */
    @Test
    fun `实参计数器与分支体探测器都不是空转`() {
        val omitted = "val domain = BuaaScheduleParser.parseArrangedList(outcome.courses, selectedTerm)"
        val omittedAt = omitted.indexOf(CALL_LIST)
        assertTrue(
            "内联样例：省掉第三枚实参那一形必须被计数器认成 2 枚（实到 " +
                topLevelArgCount(omitted, omittedAt + CALL_LIST.length) + " 枚）—— 认不出的话第 ② 层那句" +
                "「顶层实参恰好 3 枚」就是空转，盘上真省了也报绿",
            2 == topLevelArgCount(omitted, omittedAt + CALL_LIST.length),
        )

        val named = "X.parseArrangedListOutcome(items, termCode, totalWeeks = info.totalWeeks)"
        val nested = "X.parseArrangedListOutcome(listOf(a, b), termCode, totalWeeks)"
        for (sample in listOf(named to 3, nested to 3)) {
            val at = sample.first.indexOf(CALL_OUTCOME)
            assertEquals(
                "内联样例：`" + sample.first + "` 必须数成 " + sample.second + " 枚（第二枚是「实参里带逗号' " +
                    "的嵌套形状，数错就会假红）",
                sample.second,
                topLevelArgCount(sample.first, at + CALL_OUTCOME.length),
            )
        }

        val inside = "if (parsed.fallbackWeekCourses > 0) {\n    add(\"3" + FALLBACK_COPY + "\")\n}"
        assertEquals(
            "内联样例：文案长在 `$FALLBACK_BRANCH` 体内时必须抓得到（实到 " +
                occurrences(branchBody(inside, FALLBACK_BRANCH), FALLBACK_COPY) + " 处）",
            1,
            occurrences(branchBody(inside, FALLBACK_BRANCH), FALLBACK_COPY),
        )
        val outside =
            "add(\"3" + FALLBACK_COPY + "\")\nif (parsed.fallbackWeekCourses > 0) {\n    Log.i(TAG, \"fb\")\n}"
        assertEquals(
            "内联样例：文案被挪出那一支时体内必须 0 处（实到 " +
                occurrences(branchBody(outside, FALLBACK_BRANCH), FALLBACK_COPY) +
                " 处）—— 第 ③ 层那一格靠这两头才不是套套逻辑",
            0,
            occurrences(branchBody(outside, FALLBACK_BRANCH), FALLBACK_COPY),
        )
    }

    // ---------------- 源码核对小工具（与仓内其它静态守卫同一口径）----------------

    /** 从实参表的第一枚字符起按圆括号配平取表，数顶层逗号 ⇒ 顶层实参枚数（空表回 0） */
    private fun topLevelArgCount(code: String, from: Int): Int {
        val close = matchingParen(code, from) ?: error("圆括号没配平：从 $from 起")
        val body = code.substring(from, close)
        if (body.isBlank()) return 0
        var depth = 0
        var inString = false
        var escaped = false
        var commas = 0
        for (c in body) {
            when {
                inString -> when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }

                c == '"' -> inString = true
                c == '(' || c == '[' || c == '{' -> depth++
                c == ')' || c == ']' || c == '}' -> depth--
                c == ',' && depth == 0 -> commas++
            }
        }
        return commas + 1
    }

    private fun matchingParen(src: String, from: Int): Int? {
        var depth = 1
        var i = from
        var inString = false
        var escaped = false
        while (i < src.length) {
            val c = src[i]
            when {
                inString -> when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }

                c == '"' -> inString = true
                c == '(' -> depth++
                c == ')' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return null
    }

    /** `refreshSchedule` 的函数体（锚点用返回值那一行的花括号：参数表里有一枚默认 lambda，别切错） */
    private fun addCountInRefresh(session: String): Int {
        val hits = indexOfAll(session, REFRESH_SIGNATURE_TAIL)
        assertEquals("`$REFRESH_SIGNATURE_TAIL` 恰好一处（本层取块的宿主）：实到 " + hits.size + " 处", 1, hits.size)
        return occurrences(braceBodyFrom(session, session.indexOf('{', hits.first())), "add(")
    }

    private fun refreshLineReadsWarnings(viewModel: String): Int {
        val at = viewModel.indexOf(REFRESH_DONE_COPY)
        assertTrue("`$REFRESH_DONE_COPY` 必须存在（实到 index=$at）", at >= 0)
        return occurrences(lineAt(viewModel, at), "warnings")
    }

    private fun branchBody(code: String, anchor: String): String {
        val hits = indexOfAll(code, anchor)
        assertEquals("`$anchor` 恰好一处（本层取块的宿主）：实到 " + hits.size + " 处", 1, hits.size)
        // 本文件的锚点**自带左花括号**（`if (…) {` 整枚逐字节），所以块头就是锚点末尾那一枚；
        // 与姊妹守卫那枚"锚点不带花括号"的 branchBody 差在这里 —— 照抄会切到下一枚 if 的体。
        check(anchor.endsWith("{")) { "本层的锚点必须自带左花括号：实到「$anchor」" }
        return braceBodyFrom(code, hits.first() + anchor.length - 1)
    }

    private fun filesHolding(byFile: Map<String, String>, needle: String): List<String> =
        byFile.filter { occurrences(it.value, needle) > 0 }.keys.toList().sorted()

    private fun occurrencesOfAll(byFile: Map<String, String>, needle: String): Int =
        byFile.values.sumOf { occurrences(it, needle) }

    /** 文件名册子：只列有命中的那份与它的枚数（失败消息里要能看见落在哪几份文件） */
    private fun ledger(byFile: Map<String, String>, needle: String): String =
        byFile.entries.toList().sortedBy { it.key }
            .filter { occurrences(it.value, needle) > 0 }
            .joinToString(prefix = "{", postfix = "}") { "${it.key}=${occurrences(it.value, needle)}" }

    /** 抹注释后的单份 main 源码 */
    private fun source(relativeFromMainJava: String): String =
        blankCommentsKeepingLiterals(rawSource(relativeFromMainJava))

    /** 含注释的原样（第 ③ 层那把「抹注释找锚点」尺的两头之一） */
    private fun rawSource(relativeFromMainJava: String): String {
        val file = File(findMainJavaDir(), relativeFromMainJava)
        assertTrue("找不到 ${file.path}：文件挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText().replace("\r\n", "\n")
    }

    /** 目录读取面：只给第 ① 层用（`data/import/` 那一层的册子） */
    private fun readImportDir(): Map<String, String> {
        val root = File(findRepoRoot(), "app/src/main/java/com/buaa/schedule/data/import")
        assertTrue("找不到 ${root.path}：目录挪过家的话本守卫要跟着改路径", root.isDirectory)
        val out = linkedMapOf<String, String>()
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach {
            check(!out.containsKey(it.name)) { "有同名文件 ${it.name}，按文件名建册子会读错份" }
            out[it.name] = blankCommentsKeepingLiterals(it.readText().replace("\r\n", "\n"))
        }
        assertTrue("${root.path} 下一个 .kt 都没有：路径不对，本层就是空的", out.isNotEmpty())
        return out
    }

    /** 文件名 → 抹注释后的内容（整棵 app/src/main/java） */
    private fun readMainSources(): Map<String, String> {
        val root = findMainJavaDir()
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("${root.path} 下一个 .kt 都没有：路径不对，本守卫就是空的", files.isNotEmpty())
        val out = linkedMapOf<String, String>()
        files.forEach {
            check(!out.containsKey(it.name)) { "main 源码里有同名文件 ${it.name}，按文件名建册子会读错份" }
            out[it.name] = blankCommentsKeepingLiterals(it.readText().replace("\r\n", "\n"))
        }
        assertEquals("扫 main 源码的文件数与建出来的册子条目数不等", files.size, out.size)
        return out
    }

    private fun lineAt(code: String, index: Int): String {
        val lines = code.split('\n')
        return lines.getOrElse(code.substring(0, index).count { it == '\n' }) { "<越界>" }
    }

    private fun indexOfAll(haystack: String, needle: String): List<Int> {
        val hits = mutableListOf<Int>()
        if (needle.isEmpty()) return hits
        var from = 0
        while (true) {
            val at = haystack.indexOf(needle, from)
            if (at < 0) return hits
            hits += at
            from = at + needle.length
        }
    }

    private fun occurrences(haystack: String, needle: String): Int = indexOfAll(haystack, needle).size

    private fun braceBodyFrom(source: String, open: Int): String {
        check(source.getOrNull(open) == '{') { "锚点末尾不是花括号：写法变了，切不出一段体（实到「${
            source.getOrNull(open)
        }」）" }
        return source.substring(open + 1, (matchingBrace(source, open) ?: error("花括号没配平：从 $open 起")))
    }

    private fun matchingBrace(src: String, open: Int): Int? {
        var depth = 0
        var i = open
        var inString = false
        var escaped = false
        while (i < src.length) {
            val c = src[i]
            when {
                inString -> when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }

                c == '"' -> inString = true
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return null
    }

    /** **只抹注释、保留字符串字面量内容**，长度与换行位置不变（报错消息里的行号因此可信） */
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

    /** 单测的工作目录是模块目录还是仓库根不由这里决定：几种布局都试一遍，全落空就抛 */
    private fun findMainJavaDir(): File {
        var dir: File? = File("").absoluteFile
        repeat(6) {
            val hit = listOf("src/main/java", "app/src/main/java")
                .map { File(dir, it) }
                .firstOrNull { it.isDirectory }
            if (hit != null) return hit
            dir = dir?.parentFile
        }
        throw IllegalStateException("找不到 app/src/main/java：当前目录 ${File("").absolutePath}")
    }

    private fun findRepoRoot(): File {
        var dir: File? = File("").absoluteFile
        repeat(6) {
            if (dir != null && File(dir, "app/src/main/java").isDirectory) return dir
            dir = dir?.parentFile
        }
        throw IllegalStateException("找不到仓库根：当前目录 ${File("").absolutePath}")
    }

    private companion object {
        const val PARSER = "com/buaa/schedule/data/import/BuaaScheduleParser.kt"
        const val PARSER_NAME = "BuaaScheduleParser.kt"
        const val WEB_SESSION = "com/buaa/schedule/data/import/BuaaWebSession.kt"
        const val WEB_NAME = "BuaaWebSession.kt"
        const val LOGIN_SCREEN = "com/buaa/schedule/ui/importing/BuaaLoginScreen.kt"
        const val LOGIN_NAME = "BuaaLoginScreen.kt"

        /** T336 之后的抓取载体（进程内 holder）—— 第 ③ 层那本落点名册里有两跳长在它身上 */
        const val JOB_NAME = "BuaaImportJob.kt"
        const val VIEW_MODEL = "com/buaa/schedule/ui/ScheduleViewModel.kt"

        /** 第 ① 层：签名默认值那枚针 + 逐字节那一行 */
        const val DEFAULT_20 = "totalWeeks: Int = 20"
        const val SIGNATURE_LINE = "        totalWeeks: Int = 20,"

        /** 第 ② 层：两枚生产调用点的针（前面的点号把定义与内部委派排除掉） */
        const val CALL_LIST = ".parseArrangedList("
        const val CALL_OUTCOME = ".parseArrangedListOutcome("

        /** 第 ③ 层：预览文案、它那一支的条件、第二枚 if、以及「计数进预览」的落点名册 */
        const val FALLBACK_COPY = " 条课程缺少教师/周次信息，已按整学期展示，请核对"
        const val FALLBACK_BRANCH = "if (parsed.fallbackWeekCourses > 0) {"
        const val SECOND_BRANCH =
            "if (parsed.unknownTeacherCourses > 0 && parsed.fallbackWeekCourses == 0) {"

        /**
         * 「警告表真的被递进预览」在合并态（gate/21，T336 换载体之后）的四跳落点名册。
         * 身份 = 文件名 × 逐字节针，判据 = 每枚册子恰好 `{该文件=1}`。
         *
         * 改前这一格钉的是 `viewModel.previewBuaaCourses(semester, parsed.courses, parseWarnings)`
         * 一枚裸针（T216 时代的形状：页面直接问 viewModel）。T336 把抓取本体搬进
         * `data/import/BuaaImportJob.kt` 之后，那一行在盘上不再成行：作业体的接收者换成了
         * `BuaaImportJob.Host`（`BuaaLoginScreen` 里那枚 `private suspend fun
         * BuaaImportJob.Host.fetchBuaaScheduleInPage(...)`），真正问 viewModel 的那一跳挪到了
         * Host 覆写里。性质没变（`parseWarnings` 仍然一路进预览），变的是它有四枚手而不是一枚，
         * 所以逐枚点名而不是把裸针改个名。
         */
        val PREVIEW_HOP_ROSTER = listOf(
            Triple(
                LOGIN_NAME,
                "第一跳：作业体把 `buildList` 出来的警告表交给 Host（页面里那枚 `BuaaImportJob.Host` 扩展函数体内）",
                "previewBuaaCourses(semester, parsed.courses, parseWarnings)",
            ),
            Triple(
                JOB_NAME,
                "第二跳：进程内 holder 朝**当前活着**的那枚 Host 转发",
                "live.previewBuaaCourses(semester, courses, warnings)",
            ),
            Triple(
                JOB_NAME,
                "第二跳的兜底：抓取跑完时页面已离开 ⇒ 三枚一起攒进 pendingPreview，attach 时逐枚交回" +
                    "（`pending.third` 就是警告表；丢了它 = 转屏/离开之后再回来的人一个字念不着）",
                "live.previewBuaaCourses(pending.first, pending.second, pending.third)",
            ),
            Triple(
                LOGIN_NAME,
                "最后一跳：页面的 Host 覆写把三枚实参原样递给 viewModel",
                "viewModel.previewBuaaCourses(semester, courses, warnings)",
            ),
        )

        /** 契约那一枚：Host 的接口声明 + holder 的覆写各一枚，第三枚形参不许有默认值 */
        const val PREVIEW_CONTRACT =
            "suspend fun previewBuaaCourses(semester: Semester, courses: List<Course>, warnings: List<String>)"

        /** 抹注释找锚点那格的针与两侧实测枚数（BuaaLoginScreen，量于 `ai/T216`） */
        const val BARE_CALL = "parseArrangedList"
        const val RAW_PARSE_LIST_IN_LOGIN = 2
        const val CODE_PARSE_LIST_IN_LOGIN = 1

        /** 第 ④ 层：刷新那一路的两枚锚点 */
        const val REFRESH_SIGNATURE_TAIL = "): kotlin.Result<SemesterCourses>? {"
        const val REFRESH_DONE_COPY = "课表已刷新："
    }
}
