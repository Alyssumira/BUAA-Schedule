package com.buaa.schedule.domain.schedule

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T332④ 静态守卫：钉「这学期多少周」这枚事实的**生产接线**（合卡口径 = 台账 #452 + #398 + #397）。
 *
 * 表驱动那一半（判据本体、四把尺的差异册子）在 `domain/schedule/SemesterWeekCountTest`（T332③）。
 * 这一枚管盘上形状：全仓只许有那一枚具名读数在夹读侧的学期周数，而判据尺**刻意不读**它。
 *
 * ## 病（为什么形状也要钉）
 * T331② 把 `SemesterStats` / `HomeScreen` 顶栏 / `WeekNavigationAvailability` 三把尺换成问
 * [SemesterWeekCount.readable]，而"换源"这件事本身有两个方向会退化：
 * 1. **朝宽**：哪天有人在某个调用点又写一遍学期周数夹取（或直接读 `semester?.totalWeeks` 再折 1），
 *    屏上就又长出第二把能各自写错的尺 —— #452 那扇门就是这么来的：同一枚脏学期行，四把尺四样夹法，
 *    谁都没对过账（清单里点得到「第 1 周」，判据尺对同一学期一周都不放行）。
 * 2. **朝窄**：哪天有人"顺手把判据尺也接上读数"（看着像并尺，其实是把 #397/#404 那一族的账改了口径），
 *    [TermWeekGate.UNBOUNDED_TOTAL_WEEKS] = Int.MAX_VALUE 一经夹取就变 30，widget 与 reminder 那几处
 *    既有答音当场改口。这一格必须钉成"读形状"，而不是靠一句注释。
 *
 * ## 七层判据（每层都点名红了去改哪一条链）
 * ① [theSemesterWeekClampLivesInExactlyTheBookedPlaces] —— 学期周数夹取的落点册子（抹注释后逐枚点名，
 *    枚数是**等式**不是下限）：全 `app/src/main` 代码侧恰 5 枚、5 枚文件各 1 枚，其中只有
 *    `SemesterWeekCount.kt` 那一枚是读侧学期周数夹取。
 * ② [proseNeverEntersTheClampRegister] —— 同一把尺的"裸原文"那栏：现量 6 枚，多的那 1 枚是
 *    `SemesterWeekCount.kt` 自己 KDoc 里的散文 ⇒ 钉住"抹注释这一步真的在挡假阳性"，两栏不许合流。
 * ③ [noScreenClampsItsOwnSemesterWeekCount] —— `ui/` 那一侧一枚学期周数夹取都不许有（屏幕只许问读数），
 *    外加改前那三形原文各 0 处（病灶不回潮）。
 * ④ [everyReadSideCallSiteAsksTheReading] —— 读侧的落点册子：`SemesterWeekCount.readable(` 恰 4 枚
 *    （展示尺 1 枚 + 跳周清单/Pager 页数/Pager 分母 3 枚）；`HomeScreen` 那一枚因为换行只能按
 *    `.readable(state.semester?.totalWeeks)` 逐字节钉；读数本体那一行也逐字节钉死。
 * ⑤ [theGateBuysTheRawBoundFromItsCallSiteNotTheReading] —— 判据尺那一侧：`TermWeekGate.kt` 原文与
 *    抹注释后两栏都读不到 `SemesterWeekCount` / `readable(` / `MAX_TOTAL_WEEKS`（0 处不是被我抹掉的），
 *    `isInTermWeek` 体内逐字节是"把调用点递来的 bound 原样喂回 [WeekCalculator]"，`weeksInsideTerm` 同形。
 *    并尺那五侧（排铃两条链 + 冲突 + 三条导出链）里"问读数"必须各 0 处。
 * ⑥ [theUnboundedSentinelKeepsItsShapeAndItsCallSites] —— 那一等"明说不判上界"的输入：定义逐字节钉死，
 *    落点册子按**盘上真形状**钉（定义 1 + 排铃默认参数 1），不钉内核 KDoc 那句过期的转述（见下"如实写"）。
 * ⑦ [theKernelDocAnchorIsBookedAndTheDetectorSeesThroughComments] +
 *    [theProbeRecognisesTheShapesItClaimsToCatch] —— 在册锚点 + 反套套逻辑：内核 KDoc 那句
 *    「⚠️ [TermWeekGate] **不读**这一枚读数」裸原文恰 1 枚、抹注释后 0 枚，且它那半句理由（裸上界 /
 *    UNBOUNDED_TOTAL_WEEKS / 变成 30）必须逐枚读得到；再把"改读数的判据尺"那一形与"新写一枚夹取"那一形
 *    喂进同一把尺各数到该数的值，证上面那些「0 处／恰 N 枚」不是空锚点。
 *
 * ## 复算这两把尺（命令里写成方括号类是故意的：一个字都不匹配本文件）
 * - 夹取落点·裸原文那栏：
 *   `grep -rnE 'coerceIn[(][[:space:]]*1[[:space:]]*,[[:space:]]*([A-Za-z][A-Za-z0-9_]*[.])*MAX_TOTAL_WEEKS[)]' app/src/main | wc -l` → 6
 *   （抹注释那栏 = 本守卫内部口径 → 5；差的那枚是 SemesterWeekCount.kt 的 KDoc 散文）
 * - 读侧落点：`grep -rn 'SemesterWeekCount[.]readable[(]' app/src/main | wc -l` → 4
 * - 哨兵：`grep -rn 'UNBOUNDED_TOTAL_WEEKS' app/src/main` → 6 枚命中，其中代码侧 2 枚
 *
 * ## 如实写（覆盖面边界与已知不等）
 * - 只扫 `app/src/main/java` 下的 `.kt`，读的是**工作树**（已在 `app/build.gradle.kts` 的
 *   `guardReadWorkingTreeFiles` 输入面里，T92①）；零 android import、零 Robolectric、零时钟、零设备。
 * - 夹取那把尺只认「coerceIn 下界 1、上界 MAX_TOTAL_WEEKS」这一形。导出器那一侧从前在 ScheduleExporters
 *   的 `:77` 兜的是学期上限常数（`semester?.totalWeeks ?: CourseConstraints.MAX_TOTAL_WEEKS`）——
 *   那是**兜底**不是夹取，从来不在本册子里。T356（#602 × #397 的交点）把那一等换成"两等出处"
 *   （学期行自己给的上界／这份文件实际覆盖到的最晚一周），今天导出器代码里 `MAX_TOTAL_WEEKS` 一枚都不剩，
 *   由同目录 `TermWeekGateSingleRulerWiringGuardTest` 新增的那一层按逐字节＋抹注释＋枚数＋读形状钉着。
 * - ⚠️ 本守卫第 ⑤ 层那句「并尺五侧里问具名读数必须 0 处」今天**照旧是绿的、也照旧不许松**：
 *   导出器**没有**接 [SemesterWeekCount.readable] —— 接了就把上界夹到 30，会改掉上面差异册子第 ② 列
 *   「学期行 = 999 ⇒ 判据尺不夹上界」那一格（#395 / #404 既有口径）⇒ 那一半仍是欠账（T356 回执的跨卡那一格）。
 * - `SemesterStats.kt:238` 末尾那一枚夹取在册（它防的是退路那把课程周号尺越界，不是学期行），
 *   行为面由 `SemesterWeekCountTest.displayRulerStillClampsTheCourseWeekFallback` 四格钉着。
 * - 内核 KDoc 第 41-44 行那句转述「展示侧那三处历史包袱（widget/WidgetCommon.kt 两处、
 *   reminder/LiveClassResyncer.kt 一处）」在盘上是 0 枚 UNBOUNDED_TOTAL_WEEKS —— 那三处调的是
 *   `WeekCalculator.currentWeekOrNull` 递学期行裸值，压根不经过判据尺（同一处说法在 TermWeekGate.kt:46-48，
 *   台账 #404 早已登记那句过期）。本守卫钉的是"那条链递的是调用点裸上界"这一**形状**，不钉转述。
 * - 本守卫自带一枚抹注释助手，写法逐字抄同目录 `TermWeekGateSingleRulerWiringGuardTest` 里那一枚
 *   （本仓每一枚守卫自带一份、都是 private，没有可跨文件调用的那一枚）。造坏助手（末尾交回的是字符数组
 *   的身份串而不是源码）＝守卫静默永不读源码，而 `CommentStrippingHelperShapeGuardTest` 那一族的枚数尺
 *   会先把绝对值 +1 顶红：新增一枚合法助手的代价就是回来把那四枚写死值各 +1（本卡 ④ 档同一批做，
 *   改前 133／133／134／134 → 改后 134／134／135／135）。
 */
class SemesterWeekCountWiringGuardTest {

    // ─────────────── ① 学期周数夹取的落点册子（抹注释后） ───────────────

    @Test
    fun theSemesterWeekClampLivesInExactlyTheBookedPlaces() {
        val booked = listOf(
            "  $FILE_FETCHER: 1 处",
            "  $FILE_PARSER: 1 处",
            "  $FILE_CONSTRAINTS: 1 处",
            "  $FILE_SEMESTER_STATS: 1 处",
            "  $FILE_READING: 1 处",
        ).joinToString(separator = "\n") + "\n"
        val register = clampRegister(stripped = true)
        assertEquals(
            "**钉法 = 数出现次数（抹注释后逐枚点名，等式不是下限）**：「这学期多少周」的读侧/写侧夹取今天全仓恰这 5 枚 —— " +
                "$FILE_FETCHER 与 $FILE_PARSER 是**写侧**（教务取数 / 解析周次上界）、$FILE_CONSTRAINTS 是写侧归一" +
                " normalizeTotalWeeks、$FILE_SEMESTER_STATS 是展示尺对**退路**（课程周号最大值）的那一夹、" +
                "$FILE_READING 是唯一的**读侧学期周数夹取**（具名读数本体）。册子多出一枚 = 有人在读侧又自己夹了一遍" +
                "学期周数（#452 那一族的病：同一件事实两把尺各错各的）。红了就去看新增那一枚长在哪条链上：\n$register",
            booked,
            register,
        )
        assertEquals(
            "**钉法 = 枚数尺（绝对值）**：全 app/src/main 抹注释后的学期周数夹取枚数必须恰 $EXPECTED_CLAMP_TOTAL 枚。" +
                "实到 " + clampCount(stripped = true) + " 枚 —— 低于它是尺子读空了（先看 withoutComments 还在不在物化），" +
                "高于它是长出了不经读数的夹取",
            EXPECTED_CLAMP_TOTAL,
            clampCount(stripped = true),
        )
        val kernel = withoutComments(readMainSource(FILE_READING))
        assertEquals(
            "**钉法 = 读形状 + 逐字节**：读侧的学期周数夹取只许长在 $FILE_READING 那一枚 —— " +
                "object 恰一处、读数体那一行逐字节恰一处。实到：object " +
                occurrences(kernel, "object SemesterWeekCount {") + " 处 ／ 读数体 " +
                occurrences(kernel, READING_BODY) + " 处。读数体改形（比如把 takeIf 那一档换成折回 1）" +
                "就是把「脏值答 null」这条裁定从根上撤掉，#452 那一格立刻回潮",
            Pair(1, 1),
            Pair(occurrences(kernel, "object SemesterWeekCount {"), occurrences(kernel, READING_BODY)),
        )
    }

    // ─────────────── ② 裸原文那栏：散文不许进账 ───────────────

    @Test
    fun proseNeverEntersTheClampRegister() {
        val stripped = clampCount(stripped = true)
        val raw = clampCount(stripped = false)
        // 现量：裸原文 6 枚／抹注释 5 枚，多的那一枚是 SemesterWeekCount.kt:39 的 KDoc 散文
        //（那句"把原来分散在调用点的两枚夹取收到这里"逐字引了病灶原文）。
        assertEquals(
            "**钉法 = 口径尺（两栏各钉绝对值）**：学期周数夹取这把尺，裸原文栏必须恰 $EXPECTED_CLAMP_RAW_TOTAL 枚、" +
                "抹注释栏必须恰 $EXPECTED_CLAMP_TOTAL 枚，差 $EXPECTED_CLAMP_PROSE_DELTA 枚。实到 裸 $raw ／ 抹 $stripped。" +
                "两栏合流（差归零）= 那句登记病灶的散文被删了（删证据不是修尺），或者扫描器改成了读原文" +
                "（那 ① 层的册子会跟着漂）；差变大 = 又多了一句逐字引用，要回来抄两栏读数，不许改成 >=。" +
                "裸原文那栏的册子：\n" + clampRegister(stripped = false),
            Pair(EXPECTED_CLAMP_RAW_TOTAL, EXPECTED_CLAMP_TOTAL),
            Pair(raw, stripped),
        )
        val proseHere = clampRegisterOf(FILE_READING, stripped = false) - clampRegisterOf(FILE_READING, stripped = true)
        assertEquals(
            "**钉法 = 读形状**：那一枚散文差必须只落在 $FILE_READING 自己头上（它 KDoc 里逐字写着病灶原文），" +
                "别的文件一枚都不许多。实到该文件 裸比抹多 $proseHere 枚 ／ 全树多 " + (raw - stripped) + " 枚",
            Pair(raw - stripped, 1),
            Pair(raw - stripped, proseHere),
        )
    }

    // ─────────────── ③ 屏幕那一侧：一枚都不许自己夹 ───────────────

    @Test
    fun noScreenClampsItsOwnSemesterWeekCount() {
        val offenders = sources().filter { it.relative.startsWith(UI_PREFIX) }
            .map { it.relative to regexCount(withoutComments(it.text), CLAMP_REGEX) }
            .filter { it.second > 0 }
        assertEquals(
            "**钉法 = 数出现次数（覆盖面 = app/src/main 的 ui/ 那一整侧）**：屏幕侧学期周数夹取必须 0 枚 —— " +
                "顶栏、跳周清单、Pager 都只许问具名读数。T331② 收掉的正是 HomeScreen 与 " +
                "WeekNavigationAvailability 里那两枚就地夹取（那一形就是 #452 那扇门的门轴）。" +
                "实到 " + offenders.sumOf { it.second } + " 枚，出资文件：" +
                offenders.joinToString(" ／ ") { "${it.first}×${it.second}" } + "（空 = 0 枚）。红了就是把夹取写回了调用点",
            "",
            offenders.joinToString(" ／ ") { "${it.first}×${it.second}" },
        )
        val home = withoutComments(readMainSource(FILE_HOME))
        assertEquals(
            "**钉法 = 数出现次数**：HomeScreen 里旧那一形「$LEGACY_HOME_CLAMP」必须 0 处（换源前它恰 1 处，" +
                "那一处对屏念出「这学期 1 周」）。实到 " + occurrences(home, LEGACY_HOME_CLAMP) + " 处",
            0,
            occurrences(home, LEGACY_HOME_CLAMP),
        )
        val nav = withoutComments(readMainSource(FILE_WEEK_NAV))
        for (legacy in listOf(LEGACY_CHOICE_CLAMP, LEGACY_COUNTS_CLAMP)) {
            assertEquals(
                "**钉法 = 数出现次数**：$FILE_WEEK_NAV 里旧那一形「$legacy」必须 0 处（清单那一枚与两枚结论那一枚" +
                    "各自就地夹取的那一形）。实到 " + occurrences(nav, legacy) + " 处",
                0,
                occurrences(nav, legacy),
            )
        }
    }

    // ─────────────── ④ 读侧调用点册子：谁在问这枚读数 ───────────────

    @Test
    fun everyReadSideCallSiteAsksTheReading() {
        val booked = listOf(
            "  $FILE_SEMESTER_STATS: 1 处",
            "  $FILE_WEEK_NAV: 3 处",
        ).joinToString(separator = "\n") + "\n"
        val register = needleRegister(READING_CALL)
        assertEquals(
            "**钉法 = 数出现次数（落点册子，等式）**：「$READING_CALL」在 app/src/main 的落点必须逐枚等于这本册子" +
                "（展示尺一枚；跳周清单、Pager 页数、Pager 分母那三枚）⇒ 全仓问这枚读数的调用点逐枚点名。" +
                "少一枚 = 有一条链又不吃学期行了；多一枚 = 又有一处不自己算而挂进来。" +
                "HomeScreen 那一枚因为换行不在这把尺的射程，由下面那格单独逐字节钉：\n$register",
            booked,
            register,
        )
        assertEquals(
            "**钉法 = 枚数尺**：「$READING_CALL」总枚数恰 $EXPECTED_READING_CALLS 枚。实到 " +
                mainOccurrences(READING_CALL) + " 枚",
            EXPECTED_READING_CALLS,
            mainOccurrences(READING_CALL),
        )
        val home = withoutComments(readMainSource(FILE_HOME))
        assertEquals(
            "**钉法 = 逐字节**：顶栏递给 `ScheduleToolbarRow` 的那枚实参必须就是读数的答案" +
                "（「$HOME_TOOLBAR_ARG」恰 1 处）。实到 " + occurrences(home, HOME_TOOLBAR_ARG) + " 处 —— " +
                "0 处就是顶栏又不问读数了（#452 改回「这学期 1 周」的那条路），2 处就是同一份实参表里派生了一遍周数",
            1,
            occurrences(home, HOME_TOOLBAR_ARG),
        )
        assertTrue(
            "**钉法 = 读形状**：$FILE_HOME 里那枚读数必须是全限定名直调（HomeScreen 没 import 这一枚，" +
                "靠的是 com.buaa.schedule.domain.schedule 那一串）⇒ 实参表里读得到整串名字。实到 " +
                occurrences(home, "com.buaa.schedule.domain.schedule.SemesterWeekCount") + " 处",
            home.contains("com.buaa.schedule.domain.schedule.SemesterWeekCount"),
        )
    }

    // ─────────────── ⑤ 判据尺刻意不读这枚读数：递的是调用点裸上界 ───────────────

    @Test
    fun theGateBuysTheRawBoundFromItsCallSiteNotTheReading() {
        val raw = readMainSource(FILE_GATE)
        val gate = withoutComments(raw)
        // 两栏都数：TermWeekGate.kt 连注释里都没提这枚读数 ⇒ 那一串「0 处」不是被我抹出来的假绿
        for (column in listOf("原文" to raw, "抹注释" to gate)) {
            for (needle in listOf("SemesterWeekCount", "readable(", "weekAxisLength", "MAX_TOTAL_WEEKS")) {
                assertEquals(
                    "**钉法 = 数出现次数（" + column.first + " 栏）**：判据尺 $FILE_GATE 里「$needle」必须 0 处 —— " +
                        "它买的是调用点递来的**裸上界**（裁定见 $FILE_READING 的 KDoc 那段 ⚠️ 与下面两格）。" +
                        column.first + " 栏实到 " + occurrences(column.second, needle) + " 处",
                    0,
                    occurrences(column.second, needle),
                )
            }
        }
        // 那条链的形状：签名收裸值 → null 那一档早返回 → 把 bound 原样喂回全仓唯一那枚比较式
        val body = balancedBlock(gate, "fun isInTermWeek(")
        for (shape in listOf(GATE_RAW_BOUND, GATE_MONDAY, GATE_DELEGATION)) {
            assertEquals(
                "**钉法 = 逐字节（读形状）**：`isInTermWeek` 体内那一形「$shape」必须恰 1 处 —— 三形合起来就是" +
                    "「上界是调用点递进来的裸值，判据只把它转交给 [WeekCalculator] 那一枚比较式」。" +
                    "0 处 = 那条链换了形（十有八九是有人在这里插了具名读数，把 UNBOUNDED_TOTAL_WEEKS 夹成 30）",
                1,
                occurrences(body, shape),
            )
        }
        assertFalse(
            "**钉法 = 读形状**：`isInTermWeek` 体内不许长出任何夹取/限幅/折返的字样（coerceIn、coerceAtMost、" +
                "coerceAtLeast、takeIf 出现就是判据尺开始自己定上界，#397/#404 那一族的账会被这一格悄悄改掉）。" +
                "实到那一段：" + body.lines().filter { it.contains("coerce") || it.contains("takeIf") }.joinToString(" ／ "),
            listOf("coerceIn", "coerceAtMost", "coerceAtLeast", "takeIf").any { shape -> body.contains(shape) },
        )
        val weeksEntry = balancedBlock(gate, "fun weeksInsideTerm(")
        assertEquals(
            "**钉法 = 逐字节**：`weeksInsideTerm` 递给 `isInTermWeek` 的上界必须是形参裸值" +
                "（「$GATE_WEEKS_DELEGATION」恰 1 处）。实到 " + occurrences(weeksEntry, GATE_WEEKS_DELEGATION) + " 处",
            1,
            occurrences(weeksEntry, GATE_WEEKS_DELEGATION),
        )
        // 并尺那五侧（排铃两条链 + 冲突 + 三条导出链）一枚都不许挂上读数
        for (file in listOf(FILE_CONFLICT, FILE_OCCURRENCES, FILE_EXPORTERS, FILE_SCHEDULER, FILE_REMINDER)) {
            val code = withoutComments(readMainSource(file))
            assertEquals(
                "**钉法 = 数出现次数**：$file 里「$READING_CALL」必须 0 处 —— 排铃/冲突/导出那三条链吃的是学期行裸值" +
                    "（#397 那半条在册欠账，本卡不许动），不许把它们接到读数上。实到 " +
                    occurrences(code, READING_CALL) + " 处",
                0,
                occurrences(code, READING_CALL),
            )
        }
    }

    @Test
    fun theUnboundedSentinelKeepsItsShapeAndItsCallSites() {
        val gate = withoutComments(readMainSource(FILE_GATE))
        assertEquals(
            "**钉法 = 逐字节**：「$UNBOUNDED_DEFINITION」恰 1 处 —— 那一等「明说不判上界」的输入本体就是 " +
                "Int.MAX_VALUE。实到 " + occurrences(gate, UNBOUNDED_DEFINITION) + " 处。它一旦被改成经过" +
                "[SemesterWeekCount.readable]，这枚值进夹取就变 30 ⇒ 下面那两枚消费点的答音当场改口",
            1,
            occurrences(gate, UNBOUNDED_DEFINITION),
        )
        val register = needleRegister("UNBOUNDED_TOTAL_WEEKS")
        assertEquals(
            "**钉法 = 数出现次数（全 main 册子，抹注释后）**：「UNBOUNDED_TOTAL_WEEKS」今天只剩两枚代码落点 = " +
                "定义（$FILE_GATE）+ 排铃那一侧的默认参数（$FILE_SCHEDULER）。⚠️ 内核 KDoc 转述的" +
                "「widget 两处／LiveClassResyncer 一处」在盘上是 0 枚（台账 #404 早已登记 TermWeekGate.kt:46-48 " +
                "那句过期），本册子钉的是盘上真形状，不钉转述：\n$register",
            listOf("  $FILE_GATE: 1 处", "  $FILE_SCHEDULER: 1 处").joinToString(separator = "\n") + "\n",
            register,
        )
        val scheduler = withoutComments(readMainSource(FILE_SCHEDULER))
        assertEquals(
            "**钉法 = 逐字节**：$FILE_SCHEDULER 里那枚默认参数「$SCHEDULER_DEFAULT」恰 1 处（排铃两条链自己不许" +
                "拿它当缺省那一档由 reminder/TermWeekGateSingleSourceGuardTest 钉，本格只钉形状与落点）",
            1,
            occurrences(scheduler, SCHEDULER_DEFAULT),
        )
        // 展示侧那两处走的是 WeekCalculator 裸值：读数铺不到那一侧的反证
        for (file in listOf(FILE_WIDGET_COMMON, FILE_RESYNCER)) {
            val code = withoutComments(readMainSource(file))
            assertEquals(
                "**钉法 = 数出现次数**：$file 里「$READING_CALL」必须 0 处（这两处调的是 " +
                    "WeekCalculator.currentWeekOrNull 递学期行裸值，既不经过判据尺、更不经过这枚读数）。实到 " +
                    occurrences(code, READING_CALL) + " 处",
                0,
                occurrences(code, READING_CALL),
            )
        }
    }

    // ─────────────── ⑥ 在册锚点 + ⑦ 反套套逻辑 ───────────────

    @Test
    fun theKernelDocAnchorIsBookedAndTheDetectorSeesThroughComments() {
        val kernelRaw = readMainSource(FILE_READING)
        val kernelBlanked = withoutComments(kernelRaw)
        assertEquals(
            "**钉法 = 在册锚点（抹注释找锚点）**：内核 KDoc 那句「$ANCHOR」必须逐字在场恰 1 枚 —— " +
                "「判据尺不读这枚读数」是**设计裁定**，不是漏掉；把这句注释抹掉就等于把裁定撤了。实到 " +
                occurrences(kernelRaw, ANCHOR) + " 枚",
            1,
            occurrences(kernelRaw, ANCHOR),
        )
        assertEquals(
            "**钉法 = 对照组（同一枚锚点抹注释后必须归零）**：「$ANCHOR」在代码里必须 0 枚（它是文档，不是判据本体）。" +
                "实到 " + occurrences(kernelBlanked, ANCHOR) + " 枚 —— 抹完还在就是助手读空了，" +
                "那上面所有「0 处」全是空的",
            0,
            occurrences(kernelBlanked, ANCHOR),
        )
        val segment = kernelRaw.substringAfter(ANCHOR).substringBefore("## 差异册子")
        for (phrase in listOf("裸上界", "UNBOUNDED_TOTAL_WEEKS", "变成 30")) {
            assertTrue(
                "**钉法 = 在册锚点的下半句**：锚点那一句里必须读得到「$phrase」—— 它就是这一格的理由本身。" +
                    "红了说明那段被改写得只剩一句「不读」而理由没了 ⇒ 下一轮就会有人「顺手并尺」",
                segment.contains(phrase),
            )
        }
    }

    @Test
    fun theProbeRecognisesTheShapesItClaimsToCatch() {
        // 朝宽的反证：改前那几形与"新写一枚夹取"那一形喂进同一把尺，各数到该数的值
        // ⇒ 上面那些「0 处／恰 N 枚」不是空锚点。
        val probeRows = listOf(
            Triple("改前顶栏那一形（就地夹在读参数上）", LEGACY_HOME_CLAMP, 1),
            Triple("改前清单那一形（区间里就地夹）", LEGACY_CHOICE_CLAMP, 1),
            Triple("新写一枚不经读数的学期周数夹取", "val weeks = semester?.totalWeeks?.coerceIn(1, CourseConstraints.MAX_TOTAL_WEEKS) ?: 1", 1),
        )
        for (row in probeRows) {
            assertEquals(
                "**探测器自检（" + row.first + "）**：这一形必须被同一把尺数到 " + row.third + " 枚，实到 " +
                    regexCount(row.second, CLAMP_REGEX) + " 枚 —— 数不到的话第 ① 层那句「恰 5 枚」与" +
                    "第 ③ 层那句「ui 侧 0 枚」就都是空锚点（本仓口径：锚点拼错的假绿比没有守卫更糟）",
                row.third,
                regexCount(row.second, CLAMP_REGEX),
            )
        }
        // 不误伤：同形但不同事的那两族（节次上界 / 名字不是 MAX_TOTAL_WEEKS 的全限定常量）
        val notSemester = listOf(
            "val startSection = dto.startSection.coerceIn(1, CourseConstraints.MAX_PERIOD)",
            "val weekUpperBound = maxWeeks.coerceIn(1, com.buaa.schedule.domain.schedule.CourseConstraints.MAX_WEEK)",
            "selectedWeek = (currentWeek ?: 1).coerceIn(1, totalWeeks.coerceAtLeast(1))",
        )
        for (shape in notSemester) {
            assertEquals(
                "**探测器自检（不误伤）**：这一形量的不是「这学期多少周」（$shape），必须 0 枚，实到 " +
                    regexCount(shape, CLAMP_REGEX) + " 枚 —— 把合法写法一起扫红的尺会逼下一张卡把守卫改松",
                0,
                regexCount(shape, CLAMP_REGEX),
            )
        }
        // 朝窄的反证：判据尺"并尺"那一形必须让第 ⑤ 层的裸上界针读不到
        val gateReadingTheRuler = "val bound = SemesterWeekCount.readable(totalWeeks) ?: return false"
        assertEquals(
            "**探测器自检（判据尺改读读数那一形）**：裸上界针「$GATE_RAW_BOUND」必须读不到它（0 处），" +
                "而读数针必须读到 1 处 —— 第 ⑤ 层那格的两个方向各有对照物。实到（裸上界针，读数针）= " +
                Pair(occurrences(gateReadingTheRuler, GATE_RAW_BOUND), occurrences(gateReadingTheRuler, READING_CALL)),
            Pair(0, 1),
            Pair(occurrences(gateReadingTheRuler, GATE_RAW_BOUND), occurrences(gateReadingTheRuler, READING_CALL)),
        )
        // 锚点只许在注释里说话：同一句抄进行注释，抹注释后必须归零
        val anchorInCode = "    fun isInTermWeek(week: Int) = Unit  // $ANCHOR"
        assertEquals(
            "**探测器自检（在册锚点不被代码复制洗成假绿）**：把锚点抄进行注释那一形，抹注释后必须 0 枚，实到 " +
                occurrences(withoutComments(anchorInCode), ANCHOR) + " 枚",
            0,
            occurrences(withoutComments(anchorInCode), ANCHOR),
        )
        // 尺子自己不许读空
        assertTrue(
            "app/src/main 一个 .kt 都没扫到（实到 " + sources().size + " 枚）：本守卫所有读数都是空的",
            sources().isNotEmpty(),
        )
    }

    // ---------------- 读盘与小工具（与本仓守卫同一套写法）----------------

    private class MainFile(val relative: String, val text: String)

    /** 一趟测试里读一遍树就够（同一 JVM 实例内工作树不变；变异自证是靠重新起 gradle 生效的） */
    private val files: List<MainFile> by lazy {
        val root = findMainJavaDir()
        val found = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(
            "${root.path} 下一个 .kt 都没有（实到 ${found.size} 枚），路径不对 ⇒ 本守卫所有「0 处」都是空的",
            found.isNotEmpty(),
        )
        found.map {
            MainFile(
                root.toPath().relativize(it.toPath()).toString().replace(File.separatorChar, '/'),
                it.readText().replace("\r\n", "\n"),
            )
        }.sortedBy { file -> file.relative }
    }

    private fun sources(): List<MainFile> = files

    private fun clampCount(stripped: Boolean): Int =
        sources().sumOf { regexCount(if (stripped) withoutComments(it.text) else it.text, CLAMP_REGEX) }

    private fun clampRegister(stripped: Boolean): String = sources()
        .map { it.relative to regexCount(if (stripped) withoutComments(it.text) else it.text, CLAMP_REGEX) }
        .filter { it.second > 0 }
        .joinToString(separator = "") { "  ${it.first}: ${it.second} 处\n" }

    private fun clampRegisterOf(relative: String, stripped: Boolean): Int = sources()
        .filter { it.relative == relative }
        .sumOf { regexCount(if (stripped) withoutComments(it.text) else it.text, CLAMP_REGEX) }

    /** 某串的落点册子（一律抹注释后），只列有命中的文件 */
    private fun needleRegister(needle: String): String = sources()
        .map { it.relative to occurrences(withoutComments(it.text), needle) }
        .filter { it.second > 0 }
        .joinToString(separator = "") { "  ${it.first}: ${it.second} 处\n" }

    private fun mainOccurrences(needle: String): Int = sources().sumOf { occurrences(withoutComments(it.text), needle) }

    private fun readMainSource(relative: String): String {
        val file = File(findMainJavaDir(), relative)
        assertTrue("找不到 $relative：挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText().replace("\r\n", "\n")
    }

    private fun regexCount(haystack: String, pattern: Regex): Int = pattern.findAll(haystack).toList().size

    private fun occurrences(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var at = haystack.indexOf(needle)
        while (at >= 0) {
            count++
            at = haystack.indexOf(needle, at + needle.length)
        }
        return count
    }

    /** 按花括号配平从签名起取整段（含签名）；锚点找不到就抛，静默跳过等于没有守卫 */
    private fun balancedBlock(source: String, signature: String): String {
        val at = source.indexOf(signature)
        check(at >= 0) { "找不到 $signature：写法换过了，这条守卫要跟着改" }
        val open = source.indexOf('{', at)
        check(open >= at) { "$signature 之后找不到左括号" }
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(at, index + 1)
                }
            }
        }
        throw IllegalStateException("$signature 的花括号没配平")
    }

    /**
     * 只抹注释、保留字符串字面量，长度与换行位置不变（报错消息里的行号因此可信）。
     * 逐字抄同目录 `TermWeekGateSingleRulerWiringGuardTest` 里的同族助手；末尾那一句是本仓钉着的
     * 物化写法 —— 换成对字符数组调 `toString()` 交回的就是数组身份串，这一枚守卫会编译绿、门禁绿、
     * 每次判据都绿，而从没读过一句它宣称在读的文件（T315 留下、T317 改回那一族病）。
     */
    private fun withoutComments(source: String): String {
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
        repeat(6) {
            val hit = listOf("src/main/java", "app/src/main/java").map { File(dir, it) }.firstOrNull { it.isDirectory }
            if (hit != null) return hit
            dir = dir?.parentFile
        }
        throw IllegalStateException("找不到 app/src/main/java：当前目录 ${File("").absolutePath}")
    }

    private companion object {
        const val UI_PREFIX = "com/buaa/schedule/ui/"

        // 读数本体与它的读侧
        const val FILE_READING = "com/buaa/schedule/domain/schedule/SemesterWeekCount.kt"
        const val FILE_SEMESTER_STATS = "com/buaa/schedule/domain/schedule/SemesterStats.kt"
        const val FILE_WEEK_NAV = "com/buaa/schedule/ui/home/WeekNavigationAvailability.kt"
        const val FILE_HOME = "com/buaa/schedule/ui/home/HomeScreen.kt"

        // 册子里其余三枚夹取宿主（写侧两枚 + 归一一枚）
        const val FILE_FETCHER = "com/buaa/schedule/data/import/BuaaInPageFetcher.kt"
        const val FILE_PARSER = "com/buaa/schedule/data/import/BuaaScheduleParser.kt"
        const val FILE_CONSTRAINTS = "com/buaa/schedule/domain/schedule/CourseConstraints.kt"

        // 判据尺那一侧（第 ⑤⑥ 层）
        const val FILE_GATE = "com/buaa/schedule/domain/schedule/TermWeekGate.kt"
        const val FILE_CONFLICT = "com/buaa/schedule/domain/schedule/ConflictDetector.kt"
        const val FILE_OCCURRENCES = "com/buaa/schedule/domain/schedule/ScheduleOccurrences.kt"
        const val FILE_EXPORTERS = "com/buaa/schedule/domain/schedule/ScheduleExporters.kt"
        const val FILE_SCHEDULER = "com/buaa/schedule/reminder/ClassProgressScheduler.kt"
        const val FILE_REMINDER = "com/buaa/schedule/reminder/ReminderScheduler.kt"
        const val FILE_WIDGET_COMMON = "com/buaa/schedule/widget/WidgetCommon.kt"
        const val FILE_RESYNCER = "com/buaa/schedule/reminder/LiveClassResyncer.kt"

        /** ⓪ 步现量写死的期望（口径 = 本守卫内部抹注释后的文本，不是裸 grep） */
        const val EXPECTED_CLAMP_TOTAL = 5
        const val EXPECTED_CLAMP_RAW_TOTAL = 6
        const val EXPECTED_CLAMP_PROSE_DELTA = 1
        const val EXPECTED_READING_CALLS = 4

        const val READING_CALL = "SemesterWeekCount.readable("
        const val READING_BODY = "totalWeeks?.takeIf { it > 0 }?.coerceIn(1, CourseConstraints.MAX_TOTAL_WEEKS)"
        const val HOME_TOOLBAR_ARG = ".readable(state.semester?.totalWeeks)"

        // 判据尺那条链的形状（逐字节）
        const val GATE_RAW_BOUND = "val bound = totalWeeks ?: return false"
        const val GATE_MONDAY = "val weekMonday = SemesterWeekDates.mondayOf(start, week) ?: return false"
        const val GATE_DELEGATION = "return WeekCalculator.currentWeekOrNull(start, bound, weekMonday) == week"
        const val GATE_WEEKS_DELEGATION = "weeks.filter { isInTermWeek(it, semesterStart, totalWeeks) }"
        const val UNBOUNDED_DEFINITION = "const val UNBOUNDED_TOTAL_WEEKS: Int = Int.MAX_VALUE"
        const val SCHEDULER_DEFAULT = "totalWeeks: Int? = TermWeekGate.UNBOUNDED_TOTAL_WEEKS,"

        // 内核 KDoc 里那句在册锚点
        const val ANCHOR = "⚠️ [TermWeekGate] **不读**这一枚读数"

        // 改前那几形（病灶不回潮那一格 + 探测器自检的对照物）
        const val LEGACY_HOME_CLAMP = "?.coerceIn(1, com.buaa.schedule.domain.schedule.CourseConstraints.MAX_TOTAL_WEEKS)"
        const val LEGACY_CHOICE_CLAMP = "(1..totalWeeks.coerceIn(1, CourseConstraints.MAX_TOTAL_WEEKS)).toList()"
        const val LEGACY_COUNTS_CLAMP = "?.coerceIn(1, CourseConstraints.MAX_TOTAL_WEEKS)"
    }
}

/**
 * 学期周数夹取那一族：`coerceIn(1, …MAX_TOTAL_WEEKS)` —— 裸常量名与全限定名都认。
 * 针拆开写（同 `CommentStrippingHelperShapeGuardTest` 那一招）：拼出来的模式串只存在于运行时。
 */
private val CLAMP_REGEX: Regex = Regex("coerceIn" + "\\(\\s*1\\s*,\\s*(?:[A-Za-z][A-Za-z0-9_]*\\.)*MAX_TOTAL_WEEKS\\s*\\)")
