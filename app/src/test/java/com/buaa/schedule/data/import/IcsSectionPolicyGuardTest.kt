package com.buaa.schedule.data.import

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T192③：把「节次判据只有一枚内核、调用点两处、旧那一形 0 处、null 闸长在 `..` 之前」钉成源码核对。
 *
 * 纯源码核对（JVM，无 Robolectric、无设备、不读时钟）。读法与写法抄
 * `data/import/BuaaSemesterStartWiringGuardTest`（抹注释保字面量 + 花括号配平取块 +
 * 失败消息带实到枚数与文件册子）。
 *
 * ## 输入面（本仓规矩：守卫读工作树文本就得把这些文件声明成测试任务的输入）
 *
 * 本守卫读 `app/src/main/java` 下的源码文本 —— 那一条已在 `app/build.gradle.kts` 的
 * `guardReadWorkingTreeFiles` 里整目录进 `tasks.withType<Test>()` 的 `inputs`（T92①，台账 #136），
 * 所以"改了源码、单跑守卫拿到 Gradle 缓存绿灯"那一档盖得住；本次没有新增**目录之外**的读取面
 * （不读 res/、不读清单、不读脚本），无需再动构建脚本。
 *
 * ## 六层钉子
 *
 * 1. [病灶不回潮]：`return previous?.number ?: 1` 在 `app/src/main` 恰好 **0** 处，
 *    而它的**形状**（节次那一句：`.number` / `beginSection` / `startSection` / `endSection`
 *    后面挂 `?: 1`）全树 0 处；`sectionOf` 这个名也 0 处（拆干净了才算拆）。
 *    T229 把 `beginSection` 收进这一层：`BuaaScheduleParser.parseArrangedListOutcome`
 *    那一枚折成「第 1 节」的兜底已修，尺子跟着收紧，不许留「注释说会抓而尺不抓」。
 * 2. [判据只有一份、调用点恰好两处]：`fun resolveIcsPeriod(` 全 main 1 处；
 *    `IcsParser.kt` 里对它的调用 2 处，且逐字分别是 `startTime` 与 `endTime` 那一枚 ——
 *    少一枚就是有一侧还在自己算节次。
 * 3. [反套套逻辑]：探测器**认得**改之前 `sectionOf` 的原文（内联喂进去必须报 1 处），
 *    也**不误伤**盘上合法的 `?: 1`（`RE_INTERVAL` 那枚、`Course.startPeriod` 那枚 —— 全仓这一族
 *    有既有合法用法，杀整族等于替别的卡拆东西）。探测器只认「节次那一句」那一形。
 * 4. [诚实格的新形状]：`endSection < startSection` 仍在（恰好一处）；两枚节次号的可空性
 *    被上面那道 null 闸收掉，而 null 闸的下标必须**早于** `(startSection..endSection)` ——
 *    本仓「顺序决定生死」那一族的老规矩；体内 `!!` 0 处（不许用非空断言把 null 折成崩溃）。
 * 5. [内核纯度与留痕接线]：`$POLICY` 零 android import、零时钟读取；内核里 `firstOrNull` /
 *    `lastOrNull` / `indexOf` 之类**按列表位置取**的写法 0 处（事实 2 的静态那半）；
 *    `IcsParser.kt` 里留痕走 `Log.w(TAG, …)` 且 TAG 是常量、`onSkippedEvent` 恰一处调用、
 *    `colorIndex` 仍吃 `startSection`、`IcsSlot` 的转换只有一处；
 *    两份红线文件（`ScheduleViewModel.kt` / `ScheduleRepository.kt`）对新内核**零引用**
 *    （本卡没碰它们，别的卡要接上去得自己带守卫）。
 * 6. [周号与周一锚点全 main 各只有一份]（T362/#617 加的一层，被测面从「节次」扩到「同一条 ICS 链的周次维」）：
 *    `IcsParser.kt` 里 `WeekCalculator.currentWeek(` / `WeekCalculator.mondayOf(` 各恰一处、
 *    `ChronoUnit` / `DAYS.between` / `minusDays((` / `dayOfWeek.value - 1` 全 0 处；
 *    `/ 7).toInt() + 1` 与 `dayOfWeek.value - 1).toLong()` 在全 app/src/main 各**恰 1 处**且栖身处要点名
 *    （前者只能在 `WeekCalculator.kt`、后者只能在 `Semester.kt` 的 `mondayOfWeekAnchor` 体内）；
 *    另有探测器自检（改前两形各必须抓到 1 处）＋不误伤自检（HomeScreen 那枚别的减法式必须判 0）。
 *
 * 本守卫只读源码，**不证明**运行时行为：判据逐格语义归 `IcsSectionPolicyTest`，
 * parse 产出的课程形状归 `IcsParserSectionWiringTest`。
 */
class IcsSectionPolicyGuardTest {

    private companion object {
        const val POLICY = "com/buaa/schedule/data/import/IcsSectionPolicy.kt"
        const val PARSER = "com/buaa/schedule/data/import/IcsParser.kt"
        const val BUAA_PARSER = "com/buaa/schedule/data/import/BuaaScheduleParser.kt"
        const val VIEW_MODEL = "com/buaa/schedule/ui/ScheduleViewModel.kt"
        const val REPOSITORY = "com/buaa/schedule/data/repository/ScheduleRepository.kt"
        const val COURSE = "com/buaa/schedule/domain/model/Course.kt"
        const val WEEK_CALCULATOR = "com/buaa/schedule/domain/schedule/WeekCalculator.kt"
        const val SEMESTER = "com/buaa/schedule/domain/model/Semester.kt"
        const val HOME_SCREEN = "com/buaa/schedule/ui/home/HomeScreen.kt"

        /** 「除以 7 再加 1」那枚周号算式的字形（全 main 只许 WeekCalculator 一处） */
        const val WEEK_DIV_SEVEN = "/ 7).toInt() + 1"

        /** 「减掉星期序号归到周一」那枚锚点公式的字形（全 main 只许 mondayOfWeekAnchor 的体内一处） */
        const val ANCHOR_TO_MONDAY = "dayOfWeek.value - 1).toLong()"

        /** 改前 IcsParser 那一形（探测器自检的靶：抓不到就说明那格 1 是假绿） */
        const val PRE_FIX_WEEK_FORMULA =
            "        return (ChronoUnit.DAYS.between(semesterStart, date) / 7).toInt() + 1"
        const val PRE_FIX_ANCHOR_FORMULA =
            "            val mondayOfStart = startDate.minusDays((startDate.dayOfWeek.value - 1).toLong())"

        /** 同族但**不是**第二套锚点的那一枚（HomeScreen 按目标星期几回退，本尺不许误伤） */
        const val HOME_SCREEN_DAY_OFFSET =
            "            today.minusDays(((today.dayOfWeek.value - dow + 7) % 7).toLong())"

        /** 病灶逐字（旧 `IcsParser.sectionOf` 的最后一行） */
        const val LITERAL_SHAP = "return previous?.number ?: 1"

        /** 定义与调用点 */
        const val KERNEL_DEFINITION = "internal fun resolveIcsPeriod("
        const val CALL_START = "resolveIcsPeriod(startTime, slots)"
        const val CALL_END = "resolveIcsPeriod(endTime, slots)"

        /** 新接线（逐字） */
        const val PARSE_BODY_ANCHOR = "): List<Course> {"
        const val RESOLVED_PROJECTION = "as? IcsPeriodResolution.Resolved)?.number"
        const val NULL_GATE = "if (startSection == null || endSection == null) {"
        const val HONEST_CELL = "if (endSection < startSection) emptyList()"
        const val RANGE = "(startSection..endSection).toList()"
        const val COLOR_INDEX = "colorIndex = (startSection + dow.value) % 8,"
        const val SKIP_TRACE_CALL = "onSkippedEvent("
        const val SKIP_TRACE_PARAM = "onSkippedEvent: (String) -> Unit"
        const val TAG_CONST = "private const val TAG = \"IcsParser\""
        const val LOG_CALL = "android.util.Log.w(TAG, message)"
        const val SLOT_CONVERSION = "map { IcsSlot(number = it.number, start = it.start, end = it.end) }"

        /** 盘上合法的 `?: 1`（本探测器**不许**认识的实物） */
        const val LEGIT_INTERVAL_FALLBACK = "?.toIntOrNull() ?: 1"
        const val LEGIT_START_PERIOD = "periods.minOrNull() ?: 1"

        /**
         * 「节次那一句」那一形：节次号的取值（`.number`）、本卡那两枚局部 val
         * （`startSection` / `endSection`）或教务 DTO 的那两枚字段（`beginSection` / `endSection`）
         * 后面挂一枚 `?: 1` 兜底。
         *
         * T229 收紧：`beginSection` 也进来了。改前这一行自述「`beginSection ?: 1`
         * （`BuaaScheduleParser`）……不能被抓出来 —— 卡面明令别把整族杀了」，而那一枚兜底
         * 正是 T229 ① 修掉的病灶 ⇒ 注释说会抓而尺不抓的那一格不许留（卡面点名要处理）。
         * 族里**仍然不抓**的只剩 `Course.startPeriod` 那一枚（T27 判定要保留、
         * `ui/EmptyPeriodPlacementTest` 钉着），与本卡无关。
         */
        val PERIOD_FALLBACK = Regex(
            """(?:\.number\b|beginSection\b|startSection\b|endSection\b)\s*\?:\s*1\b""",
        )
    }

    // ─────────────── ① 病灶不回潮 ───────────────

    @Test
    fun `节次那一句的 1 兜底在 app-src-main 零命中`() {
        val literalHits = mutableListOf<String>()
        val shapeHits = mutableListOf<String>()
        for (file in mainSources()) {
            if (occurrences(file.code, LITERAL_SHAP) > 0) {
                literalHits += "${file.path} 逐字 `$LITERAL_SHAP` ×" + occurrences(file.code, LITERAL_SHAP)
            }
            for (at in indexOfAllByRegex(file.code, PERIOD_FALLBACK)) {
                shapeHits += "${file.path}:${lineOf(file.code, at)}: ${lineAt(file.code, at).trim()}"
            }
        }
        assertEquals(
            "`$LITERAL_SHAP` 在 app/src/main 必须 0 处 —— 它就是 T192 那一形的字面本体" +
                "（早于第一节课的活动被凭空安成第 1 节，然后真的写库、真的上课表）。实到 " +
                literalHits.size + " 处：\n  " + literalHits.joinToString("\n  "),
            0,
            literalHits.size,
        )
        assertEquals(
            "「节次那一句挂 `?: 1`」那一形（`.number ?: 1` / `beginSection ?: 1` / " +
                "`startSection ?: 1` / `endSection ?: 1`）在 app/src/main 必须 0 处，" +
                "病灶不许换个名字回潮。实到 " + shapeHits.size +
                " 处：\n  " + shapeHits.joinToString("\n  "),
            0,
            shapeHits.size,
        )
        // T229 ①：把这一格从"全树扫"里单独点出教务那一路，免得它以后又被"整树 0"那一句盖过去
        val buaa = blankCommentsKeepingLiterals(source(BUAA_PARSER))
        val buaaHits = indexOfAllByRegex(buaa, PERIOD_FALLBACK).map { lineAt(buaa, it).trim() }
        assertEquals(
            "`$BUAA_PARSER` 里挂 `?: 1` 的节次那一句必须 0 处（实到 " + buaaHits.size +
                " 处：${buaaHits.joinToString()}）：这一枚是 T229 ① 修掉的落点 —— 星期缺失整条丢、" +
                "节次缺失折成第 1 节 = **同一行数据两把尺**。它回潮就是" +
                "「一节根本不存在的课画上周课表、排铃、被导出，用户手改过还被 isManualOverride 锁住」",
            0,
            buaaHits.size,
        )
        assertEquals(
            "`$BUAA_PARSER` 里 `val start = item.beginSection` 必须还在、且不挂兜底（实到 " +
                occurrences(buaa, "val start = item.beginSection") + " 处）：" +
                "换成 `?: 1` 上面那格会红，换成 `!!` 是另一种死法（非空断言 = 把缺字段折成崩溃），" +
                "两形都要挡 ⇒ 逐字钉这一句的前半截",
            1,
            occurrences(buaa, "val start = item.beginSection"),
        )
        assertEquals(
            "`$BUAA_PARSER` 里 `!!` 必须 0 处（实到 " + occurrences(buaa, "!!") +
                " 处）：把 `?: 1` 换成非空断言不是修法，是把「静默造出一节假课」换成「教务送一个缺字段就崩」",
            0,
            occurrences(buaa, "!!"),
        )
    }

    @Test
    fun `旧的 sectionOf 整个拆掉 名字一处不留`() {
        val parser = blankCommentsKeepingLiterals(source(PARSER))
        assertEquals(
            "`$PARSER` 里 `sectionOf(` 必须 0 处（实到 " + occurrences(parser, "sectionOf(") +
                " 处）：卡面裁定是「拆掉」而不是「留着再包一层」，" +
                "留着旧函数就等于给旧判据留了一条可以被重新接上的后门",
            0,
            occurrences(parser, "sectionOf("),
        )
        var anywhere = 0
        for (file in mainSources()) anywhere += occurrences(file.code, "sectionOf(")
        assertEquals(
            "`sectionOf(` 在 app/src/main 全树必须 0 处（实到 $anywhere 处）",
            0,
            anywhere,
        )
    }

    // ─────────────── ② 判据只有一份、调用点恰好两处 ───────────────

    @Test
    fun `节次判据全仓只有一份 调用点 start 与 end 各一枚`() {
        val policy = blankCommentsKeepingLiterals(source(POLICY))
        val parser = blankCommentsKeepingLiterals(source(PARSER))

        assertEquals(
            "`$POLICY` 里内核签名 `$KERNEL_DEFINITION` 恰好一处（实到 " +
                occurrences(policy, KERNEL_DEFINITION) + " 处）",
            1,
            occurrences(policy, KERNEL_DEFINITION),
        )
        val treeDefinitions = mainSources().sumOf { occurrences(it.code, "fun resolveIcsPeriod(") }
        assertEquals(
            "`fun resolveIcsPeriod(` 在 app/src/main 全树恰好一处（实到 $treeDefinitions 处）：" +
                "第二份定义 = 两把尺量同一件事，改其一不改其余（T192 的判据只许一处真相）",
            1,
            treeDefinitions,
        )
        assertEquals(
            "`$PARSER` 里对内核的调用恰好两枚（实到 " + occurrences(parser, "resolveIcsPeriod(") +
                " 处）：DTSTART 与 DTEND 各一枚。少一枚 = 有一侧仍在自己算节次；" +
                "多一枚 = 有人在别处绕过内核再判一次",
            2,
            occurrences(parser, "resolveIcsPeriod("),
        )
        for (anchor in listOf(CALL_START, CALL_END)) {
            assertEquals(
                "调用点必须逐字是「$anchor」（本文件里恰好一处，实到 " + occurrences(parser, anchor) +
                    " 处）：枚数对但实参换了（比如两侧都喂 startTime）这一格照样要红",
                1,
                occurrences(parser, anchor),
            )
        }
        assertEquals(
            "`$PARSER` 里现写节次的痕迹必须 0 处（实到 " + occurrences(parser, ".hour") +
                " 处 `.hour`）：节次只能来自内核的结论，不许在调用点拿钟点数自己算",
            0,
            occurrences(parser, ".hour"),
        )
    }

    // ─────────────── ③ 反套套逻辑：探测器自己得是活的 ───────────────

    /**
     * 只报"全树 0 命中"而探测器其实什么都抓不到，等于没有守卫（本仓库径：别拿 grep 没匹配到当全绿）。
     * 朝松（探测器瞎）红在第一手；朝窄（见 `?: 1` 就杀）红在第二、三手。
     */
    @Test
    fun `探测器认得改之前的原文 也不误伤合法的 1 兜底`() {
        val oldSectionOf = """
            private fun sectionOf(time: LocalTime, slots: List<DefaultSlot>): Int {
                slots.firstOrNull { !time.isBefore(it.start) && time.isBefore(it.end) }?.let { return it.number }
                val previous = slots.lastOrNull { !time.isBefore(it.start) }
                return previous?.number ?: 1
            }
        """.trimIndent()
        assertEquals(
            "探测器必须把改之前 `sectionOf` 原文里那一形抓出来（实到 " +
                hitsOf(blankCommentsKeepingLiterals(oldSectionOf)).size +
                " 处 —— 0 处说明探测器空转，第 ① 层那句「全树 0 命中」是假的绿）",
            1,
            hitsOf(blankCommentsKeepingLiterals(oldSectionOf)).size,
        )

        // T229 新收进来的那一枚：教务导入的 `beginSection ?: 1` 改前原文（BuaaScheduleParser 的循环头三行）
        val oldBuaaArrangedHead = """
            val courseName = item.courseName ?: "未知课程"
            val day = item.dayOfWeek ?: return@forEachIndexed
            val start = item.beginSection ?: 1
            val end = item.endSection ?: start
        """.trimIndent()
        assertEquals(
            "探测器必须把改之前 `$BUAA_PARSER` 里那一枚 `beginSection ?: 1` 抓出来（实到 " +
                hitsOf(blankCommentsKeepingLiterals(oldBuaaArrangedHead)).size +
                " 处）：这一枚是 T229 ① 才收进尺子的，收进来却抓不到 = 第 ① 层那句「全树 0 命中」" +
                "对教务那一路是假的绿。挨着的 `endSection ?: start` 不许一起被算成命中" +
                "（缺 end 有 start 是**有出处**的单节，不是凭空造第 1 节）",
            1,
            hitsOf(blankCommentsKeepingLiterals(oldBuaaArrangedHead)).size,
        )

        // 合法一：`extractInterval` 的 INTERVAL 缺省值（RFC 5545 规定 ≥1），与节次无关
        val legitLine = "return (RE_INTERVAL.find(line)?.groupValues?.get(1)$LEGIT_INTERVAL_FALLBACK)"
        assertEquals(
            "`$PARSER` 里 `extractInterval` 那一枚合法兜底（$LEGIT_INTERVAL_FALLBACK…）不许被算成命中：" +
                "它是 INTERVAL 的缺省值，与节次无关；探测器宽到见 `?: 1` 就杀，" +
                "就会把这一族的合法用法一起拆掉（卡面明令「别把整族杀了」）。实到 " +
                hitsOf(legitLine).size + " 处",
            0,
            hitsOf(legitLine).size,
        )

        // 合法二：Course.startPeriod 的 `?: 1` —— T27 判定**故意保留**的兜底（别的卡的守卫钉着它）
        val legitStartPeriod = "val startPeriod: Int get() = $LEGIT_START_PERIOD"
        assertEquals(
            "`Course.startPeriod` 那一枚 `?: 1`（$LEGIT_START_PERIOD）不许被算成命中：那是 T27 " +
                "判定过要**保留**的兜底（`ui/EmptyPeriodPlacementTest` 钉着它），" +
                "卡面也明令「别把整族杀了」。实到 " + hitsOf(legitStartPeriod).size + " 处",
            0,
            hitsOf(legitStartPeriod).size,
        )

        // 盘上那两枚实物还在：它们被顺手删掉的话，上面的负对照就成了空转
        val parser = blankCommentsKeepingLiterals(source(PARSER))
        assertEquals(
            "`$PARSER` 里 `extractInterval` 那枚合法 `$LEGIT_INTERVAL_FALLBACK` 必须仍在（负对照的实物；" +
                "实到 " + occurrences(parser, LEGIT_INTERVAL_FALLBACK) + " 处）。" +
                "本卡修完之后 `$PARSER` 里 `?: 1` 一族就该只剩这一枚",
            1,
            occurrences(parser, LEGIT_INTERVAL_FALLBACK),
        )
        val course = blankCommentsKeepingLiterals(source(COURSE))
        assertEquals(
            "`$COURSE` 里 `$LEGIT_START_PERIOD` 必须仍在（T27 保留的那枚，不归本卡）：实到 " +
                occurrences(course, LEGIT_START_PERIOD) + " 处",
            1,
            occurrences(course, LEGIT_START_PERIOD),
        )
    }

    // ─────────────── ④ 诚实格的新形状 + null 闸的顺序 ───────────────

    @Test
    fun `null 闸长在区间展开之前 诚实格按新形状钉住`() {
        val parser = blankCommentsKeepingLiterals(source(PARSER))
        val open = parser.indexOf(PARSE_BODY_ANCHOR) + PARSE_BODY_ANCHOR.length - 1
        val body = braceBodyFrom(parser, open)

        assertEquals(
            "可空节次号必须被一道 null 闸收掉（`$NULL_GATE` 恰好一处，实到 " +
                occurrences(body, NULL_GATE) + " 处）：卡面要求「只有两侧都是 Resolved 才成课」",
            1,
            occurrences(body, NULL_GATE),
        )
        val gateAt = body.indexOf(NULL_GATE)
        val rangeAt = body.indexOf(RANGE)
        assertTrue(
            "顺序决定生死：null 闸（index=$gateAt）必须**早于** `$RANGE`（index=$rangeAt），" +
                "否则 null 就顺着 `..` 走下去 —— 要么编译不过，要么被一枚 `!!` 折成运行期崩溃，" +
                "两者都不是「如实跳过」那一条路（事实 4）。实到 gate=$gateAt / range=$rangeAt",
            gateAt in 0 until rangeAt,
        )
        assertEquals(
            "两侧的可空取法各恰好一枚（`$RESOLVED_PROJECTION` 实到 " +
                occurrences(body, RESOLVED_PROJECTION) + " 处）：一枚都不许换成 `!!` 或 `?: 1`",
            2,
            occurrences(body, RESOLVED_PROJECTION),
        )
        assertEquals(
            "诚实格 `$HONEST_CELL` 必须还在（恰好一处，实到 " + occurrences(body, HONEST_CELL) +
                " 处）：DTEND 早于 DTSTART 仍要走空 periods 那一等，`endSection < startSection` " +
                "这一格在可空内核之后必须重想过 —— 本卡的答法是「null 已在闸外挡掉，这一格只管倒序」",
            1,
            occurrences(body, HONEST_CELL),
        )
        assertEquals(
            "区间展开 `$RANGE` 恰好一处（实到 " + occurrences(body, RANGE) + " 处）",
            1,
            occurrences(body, RANGE),
        )
        assertEquals(
            "体内 `!!` 必须 0 处（实到 " + occurrences(body, "!!") +
                " 处）：非空断言等于把「落不进节次表」从如实跳过换成运行期崩溃",
            0,
            occurrences(body, "!!"),
        )
        assertEquals(
            "`$COLOR_INDEX` 逐字一处（实到 " + occurrences(body, COLOR_INDEX) +
                " 处）：卡面要求 colorIndex 继续用 startSection（Resolved 的那枚 number），" +
                "换成 endSection 或常量都会让同一门课在课表上换色",
            1,
            occurrences(body, COLOR_INDEX),
        )
    }

    // ─────────────── ⑤ 内核纯度 · 留痕接线 · 红线文件 ───────────────

    @Test
    fun `内核零 android 零时钟 且不按列表位置取节次`() {
        val policy = blankCommentsKeepingLiterals(source(POLICY))

        assertEquals(
            "纯 JVM 判据必须**零 android import**（仓库硬规矩：设备面一律由调用点当参数传进来）。" +
                "实到 " + occurrences(policy, "import android") + " 处",
            0,
            occurrences(policy, "import android"),
        )
        val clockHits = occurrences(policy, "LocalTime.now(") + occurrences(policy, " LocalDateTime.now(") +
            occurrences(policy, "System.currentTimeMillis") + occurrences(policy, "Calendar.")
        assertEquals(
            "内核里读时钟的面必须 0 处（`LocalTime.now(` / `LocalDateTime.now(` / " +
                "`System.currentTimeMillis` / `Calendar.` 合计实到 $clockHits 处）：" +
                "节次判定只许看喂进来的钟点，读一次时钟就让整条解析不可复现、单测不可断言",
            0,
            clockHits,
        )
        // 事实 2 的静态那半：判据不许按列表位置取
        for (needle in listOf("firstOrNull(", "lastOrNull(", ".first(", ".last(", "indexOf(")) {
            assertEquals(
                "内核里 `$needle` 必须 0 处（实到 " + occurrences(policy, needle) +
                    " 处）：按列表位置取 = 把 `TimeSlotDao` 的 `ORDER BY number` 当成「按 start 升序」用，" +
                    "而这条链上没有任何东西保证 start 升序（SettingsScreen 的节次草稿逐格自由填 HH:mm）。" +
                    "判据只看 start 与 number 的全序（表驱动里「打乱 == 升序」那一格是它的行为半边）",
                0,
                occurrences(policy, needle),
            )
        }
        for (decl in listOf(
                "internal sealed interface IcsPeriodResolution",
                "data class Resolved(val number: Int, val via: Via)",
                "data object BeforeFirstSlot : IcsPeriodResolution",
                "data object NoSlots : IcsPeriodResolution",
                "internal enum class Via { InsideSlot, NearestPrevious }",
                "internal data class IcsSlot(",
            )
        ) {
            assertEquals(
                "内核的声明形状逐字一枚：「$decl」（实到 " + occurrences(policy, decl) +
                    " 处）：第三枚结论（比如 `Guessed`）会绕过本卡的判据，假节次又有了入口",
                1,
                occurrences(policy, decl),
            )
        }
    }

    @Test
    fun `跳过那一等留痕走常量 TAG 而红线文件零引用`() {
        val parser = blankCommentsKeepingLiterals(source(PARSER))

        assertEquals(
            "`$PARSER` 里 `$TAG_CONST` 恰好一处（实到 " + occurrences(parser, TAG_CONST) +
                " 处）：留痕的 TAG 用常量，不写魔法字符串（与 `BuaaInPageFetcher` / `BuaaWebSession` 同一写法）",
            1,
            occurrences(parser, TAG_CONST),
        )
        assertEquals(
            "设备侧默认留痕必须是 `$LOG_CALL`（恰好一处，实到 " + occurrences(parser, LOG_CALL) +
                " 处）：`onSkippedEvent` 的默认值就是这一句，调用点（`ui/ScheduleViewModel.importIcs`）" +
                "一个字都不必改 —— 本卡不许碰那份文件",
            1,
            occurrences(parser, LOG_CALL),
        )
        assertEquals(
            "`$SKIP_TRACE_PARAM` 恰好一处（实到 " + occurrences(parser, SKIP_TRACE_PARAM) + " 处）",
            1,
            occurrences(parser, SKIP_TRACE_PARAM),
        )
        assertEquals(
            "留痕在体内恰好一处调用（`$SKIP_TRACE_CALL`，实到 " + occurrences(parser, SKIP_TRACE_CALL) +
                " 处）：跳过的每一条都要念出来，一条都不许哑",
            1,
            occurrences(parser, SKIP_TRACE_CALL),
        )
        assertEquals(
            "跳过计数一处声明 + 一处自增（实到 " + occurrences(parser, "var skippedEvents = 0") + " + " +
                occurrences(parser, "skippedEvents++") + " 处）：`IcsParser.parse` 今天仍返回 " +
                "List<Course>（卡面明令不许为了报数去改 VM 的公共签名），这一档只进留痕",
            2,
            occurrences(parser, "var skippedEvents = 0") + occurrences(parser, "skippedEvents++"),
        )
        assertEquals(
            "DefaultSlot → IcsSlot 的转换只许一处（`$SLOT_CONVERSION`，实到 " +
                occurrences(parser, SLOT_CONVERSION) + " 处）：卡面要求「转换写在 IcsParser 里一处即可」，" +
                "两处以上就会有两份表形状",
            1,
            occurrences(parser, SLOT_CONVERSION),
        )
        assertEquals(
            "`slotTable` 与 `DefaultSlot` 必须原样保留（实到 " + occurrences(parser, "private fun slotTable(") +
                " + " + occurrences(parser, "private data class DefaultSlot(") + " 处）",
            2,
            occurrences(parser, "private fun slotTable(") + occurrences(parser, "private data class DefaultSlot("),
        )

        for (path in listOf(VIEW_MODEL, REPOSITORY)) {
            val code = blankCommentsKeepingLiterals(source(path))
            assertEquals(
                "`$path` 里对新内核的引用必须 0 处（实到 " + occurrences(code, "resolveIcsPeriod(") +
                    " 处）：本卡的红线是这两份文件零改动（T184/T187 一族正在占用）。" +
                    "谁要把节次判据接到那两路上，得自己带一枚守卫来",
                0,
                occurrences(code, "resolveIcsPeriod("),
            )
        }
    }

    // ─────────────── ⑥ 周号算式与周一锚点：全 main 各只有一份（T362/#617） ───────────────

    /**
     * 第 ⑥ 层钉的是**另一维**的接线：本节次守卫的主体是「节次怎么算」，而 IcsParser 同一条链上还写着
     * 「周号怎么算」与「哪天算一周的第一天」—— T362 把这两枚抄本换源到 WeekCalculator 之后，
     * 由本文件顺带把它们钉成册子尺（同一枚被测量文件、同一套读法，不值得为两格新开一枚守卫文件）。
     *
     * 正形（ICS 链只问 WeekCalculator）+ 负形（本文件不再自己算）+ 全 main 册子（各恰一处、且处所在哪枚文件）
     * + 探测器自检（改前那一形喂进来必须抓到、HomeScreen 那枚不同的减法式不许误伤）。
     */
    @Test
    fun `周号算式与周一锚点全 main 各只有一份 而 IcsParser 只问 WeekCalculator`() {
        val parser = blankCommentsKeepingLiterals(source(PARSER))

        // 正形：两条臂各接线一次 —— 少了就是有人把换源又拆回本地算式，多了就是同一件事问两遍
        for ((needle, label) in listOf(
            "WeekCalculator.currentWeek(" to "周号那一维",
            "WeekCalculator.mondayOf(" to "归一到周一那一维",
        )) {
            assertEquals(
                "**钉法 = 逐字节 + 数出现次数**：`$PARSER` 里 `$needle` 必须恰好一处（$label；实到 " +
                    occurrences(parser, needle) + " 处）。\n" +
                    "复算：grep -Fn '$needle' app/src/main/java/$PARSER\n" +
                    "0 处 = 换源被拆掉、这一维重新就地自己算；2 处 = 同一条链上问了两遍，改其一不改其余",
                1,
                occurrences(parser, needle),
            )
        }

        // 负形：本文件里那两枚抄本的**字形**必须一处不剩（抹完注释再数，注释里提它们不算出场）
        for (needle in listOf("ChronoUnit", "DAYS.between", "minusDays((", "dayOfWeek.value - 1")) {
            assertEquals(
                "**钉法 = 读形状（抹注释保字面量）**：`$PARSER` 里「$needle」必须 0 处（实到 " +
                    occurrences(parser, needle) + " 处）：出现一次就是第二套锚点/周号算式又长回来了 —— " +
                    "WeekCalculator 文件头那句「全应用不再有第二套锚点算法」当场变回假话",
                0,
                occurrences(parser, needle),
            )
        }

        // 全 main 册子：两枚算式各自只许活在一枚文件里，而且要点名是哪一枚
        val all = mainSources()
        for ((needle, home, label) in listOf(
            Triple(WEEK_DIV_SEVEN, WEEK_CALCULATOR, "「除以 7 再加 1」那枚周号算式"),
            Triple(ANCHOR_TO_MONDAY, SEMESTER, "「减掉星期序号归到周一」那枚锚点公式"),
        )) {
            val holders = all.filter { occurrences(it.code, needle) > 0 }.map { it.path }
            val count = all.sumOf { occurrences(it.code, needle) }
            assertEquals(
                "**钉法 = 数出现次数（全 main 册子）**：$label「$needle」全 app/src/main 只许 **1** 处，且只能长在 " +
                    "`$home`（实到 $count 处，分布在 $holders）。" +
                    "多一处 = 第二套同一判据的抄本（本卡收的就是这一族）；0 处 = 唯一那一份被改名或搬家，" +
                    "尺子跟着挪之前不许留哑",
                1,
                count,
            )
            assertEquals(
                "**钉法 = 按文件点名（全 main 册子）**：${label}的栖身处必须逐字就是 `$home`（实到 $holders）",
                listOf(home),
                holders,
            )
        }

        // 探测器自检（朝宽那一臂）：改前那两形原文喂进同一把尺，必须各数到 1
        assertEquals(
            "**钉法 = 抹注释找锚点**：改前 IcsParser 那一形必须被「$WEEK_DIV_SEVEN」抓到（实到 " +
                occurrences(PRE_FIX_WEEK_FORMULA, WEEK_DIV_SEVEN) +
                " 处）：数不到就说明这把尺空转，上面那格 1 是假绿",
            1,
            occurrences(PRE_FIX_WEEK_FORMULA, WEEK_DIV_SEVEN),
        )
        assertEquals(
            "**钉法 = 抹注释找锚点**：改前 IcsParser 那枚就地重写的锚点必须被「$ANCHOR_TO_MONDAY」抓到（实到 " +
                occurrences(PRE_FIX_ANCHOR_FORMULA, ANCHOR_TO_MONDAY) + " 处）",
            1,
            occurrences(PRE_FIX_ANCHOR_FORMULA, ANCHOR_TO_MONDAY),
        )
        // 不误伤（朝窄那一臂）：HomeScreen 那枚是**另一种**减法式（按目标星期几回退，T361 的覆盖面），
        // 它同样含 `dayOfWeek.value` 与 `.toLong()`，宽一把就会把它算成第二套锚点、本格当场假阳
        assertEquals(
            "**钉法 = 反套套逻辑**：`$HOME_SCREEN` 那句 `today.minusDays(((today.dayOfWeek.value - dow + 7) % 7)" +
                ".toLong())` 必须被这把尺判 0 处（实到 " +
                occurrences(HOME_SCREEN_DAY_OFFSET, ANCHOR_TO_MONDAY) +
                " 处）：它不是「归到本周一」那一形，把它杀了就是替别的卡拆东西",
            0,
            occurrences(HOME_SCREEN_DAY_OFFSET, ANCHOR_TO_MONDAY),
        )
    }

    // ---------------- 源码核对小工具（与仓内其它静态守卫同一口径）----------------

    private data class Source(val path: String, val code: String)

    private fun source(relativeFromMainJava: String): String {
        val file = File(findMainJavaDir(), relativeFromMainJava)
        assertTrue("找不到 ${file.path}：文件挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText().replace("\r\n", "\n")
    }

    private fun mainSources(): List<Source> {
        val root = findMainJavaDir()
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("${root.path} 下一个 .kt 都没有：路径不对，本守卫就是空的", files.isNotEmpty())
        return files.map {
            Source(it.relativeTo(root).path.replace('\\', '/'), blankCommentsKeepingLiterals(it.readText()))
        }
    }

    /** 用探测器扫一段代码，返回命中的行文本（供失败消息用） */
    private fun hitsOf(code: String): List<String> =
        indexOfAllByRegex(code, PERIOD_FALLBACK).map { lineAt(code, it).trim() }

    private fun indexOfAllByRegex(haystack: String, needle: Regex): List<Int> =
        needle.findAll(haystack).map { it.range.first }.toList()

    private fun lineOf(code: String, index: Int): Int = code.substring(0, index).count { it == '\n' } + 1

    private fun lineAt(code: String, index: Int): String {
        val lines = code.split('\n')
        return lines.getOrElse(lineOf(code, index) - 1) { "<越界>" }
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

    /** 从已知的 `{` 起按花括号配平取出那一段体（不含外层花括号） */
    private fun braceBodyFrom(source: String, open: Int): String {
        check(source.getOrNull(open) == '{') {
            "锚点末尾不是花括号：写法变了，切不出一段体（实到「${source.getOrNull(open)}」）"
        }
        val close = matchingBrace(source, open) ?: error("花括号没配平：从 $open 起")
        return source.substring(open + 1, close)
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

    /**
     * **只抹注释、保留字符串字面量内容**，长度与换行位置不变（报错消息里的行号因此可信）。
     * 本卡尤其需要抹注释：`IcsParser` 与 `$POLICY` 的理由段里**逐字写着旧那一形**
     * （含 `$LITERAL_SHAP`），不抹的话探测器会把自己钉的守卫当成病灶。
     */
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
}
