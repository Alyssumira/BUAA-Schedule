package com.buaa.schedule.data.import

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T193③：把「哪一枚是第 1 周」这一判的**落点唯一性**与**病灶不许回潮**钉成静态守卫
 * （读 `app/src/main` 工作树文本，纯 JVM、无 Robolectric、无设备、不读时钟）。
 *
 * 读法与小工具抄 `BuaaSemesterStartWiringGuardTest` / `ui/CourseDeletionWiringGuardTest`：
 * 抹注释保字面量、`occurrences` 数枚数、失败消息带实到枚数与文件名册子、花括号配平取分支体。
 *
 * ## 输入面（本仓规矩：守卫读工作树文本，就得把这些文件声明成测试任务的输入）
 *
 * `app/src/main/java` 整目录已在 `app/build.gradle.kts` 的 `guardReadWorkingTreeFiles` 里进
 * `tasks.withType<Test>()` 的 `inputs`（T92①，台账 #136），所以「改了源码、单跑守卫拿到
 * Gradle 缓存绿灯」那一档盖得住；本次没有新增**目录之外**的读取面，无需再动构建脚本。
 *
 * ## 钉的七层
 *
 * 1. [判据唯一落点] `fun resolveBuaaFirstWeek(` 全 `app/src/main` 恰好 1 处，且入参是壳
 *    （`List<BuaaWeekRow>`）而不是网络 DTO（内核里 `BuaaTermWeekDto` 0 处）。
 * 2. [病灶不回潮] `BuaaInPageFetcher.kt` 里 `minByOrNull { it.serialNumber ?: Int.MAX_VALUE }`
 *    恰好 0 处 —— 并且**结构探测器**（`minByOrNull { …serialNumber… }` 或「列表首行当第 1 周」）
 *    也 0 处：后者连「摘掉 `?: Int.MAX_VALUE` 只留键」那等改名版一起拦。
 * 3. [同族册子] 全 `app/src/main` 的同一枚字面针恰好 1 处、落点册子 `{BuaaScheduleParser.kt=1}`
 *    —— 那是 §3 的另案（零生产调用方的死入口），本卡不动它，枚数钉住，将来谁再长一枚就红。
 * 4. [裁剪只有一处] 见该层注释（这一层的期望枚数与卡面给的数字不同，理由写在消息里）。
 * 5. [内核纯度 + 接线] 内核零 android、零时钟、零 parse、零 import；调用点对内核的调用恰好 1 处、
 *    入参映射恰好 1 处、两档日志各在各自的分支里、`runCatching` 外壳与四档早返回原样还在。
 * 6. [反套套逻辑] 探测器必须**认得**改前那一形原文（内联样例抓到 1 处），同时**不误伤**盘上合法的
 *    `Color.kt:225` / `CourseWeekSpans.kt:92` 两形与 `maxByOrNull`（各 0 处）。
 *    本仓口径：不许拿「grep 没匹配到」当「全绿」。
 * 7. [另案那处一个字节没动] `parseTermWeeks` 仍是原样（`?: return null` 那一枚诚实兜底还在）。
 *
 * 本守卫只读源码，**不证明**运行时行为：判据本体各档由 `BuaaFirstWeekPolicyTest` 钉；
 * 「教务页面真回一批没号的行时用户看到什么」归编排者装机。
 */
class BuaaFirstWeekPolicyGuardTest {

    // ─────────────── ① 判据唯一落点：内核只有一份，且吃的是壳 ───────────────

    @Test
    fun `判据内核全仓只有一份 入参是壳而不是网络 DTO`() {
        val byFile = readMainSources()
        val kernel = blankCommentsKeepingLiterals(source(KERNEL))

        assertEquals(
            "`$KERNEL_FN_ANCHOR` 在 app/src/main 恰好 1 处（判据唯一落点；实到 " +
                occurrencesOfAll(byFile, KERNEL_FN_ANCHOR) + " 处，册子 " + ledger(byFile, KERNEL_FN_ANCHOR) +
                "）：第二份定义 = 两把尺量同一件事，改其一不改其余",
            1,
            occurrencesOfAll(byFile, KERNEL_FN_ANCHOR),
        )
        assertEquals(
            "落点必须是内核自己那份文件（实到 " + filesHolding(byFile, KERNEL_FN_ANCHOR) + "）",
            listOf(KERNEL_NAME),
            filesHolding(byFile, KERNEL_FN_ANCHOR),
        )
        assertEquals(
            "判据本体的入参声明 `rows: List<BuaaWeekRow>` 恰好一处（实到 " +
                occurrences(kernel, "rows: List<BuaaWeekRow>") + " 处）：签名一改，第 ⑥ 层那些内联样例就" +
                "不再是对着真判据比的",
            1,
            occurrences(kernel, "rows: List<BuaaWeekRow>"),
        )
        assertEquals(
            "内核里 `BuaaTermWeekDto` 必须 0 处（实到 " + occurrences(kernel, "BuaaTermWeekDto") +
                " 处）：那是 `@Serializable` 的网络 DTO，内核吃它就等于把序列化面拖进判据 —— " +
                "以后接口多给一个字段就得重判一次「谁算第 1 周」",
            0,
            occurrences(kernel, "BuaaTermWeekDto"),
        )
    }

    // ─────────────── ② 病灶不许回潮（调用点） ───────────────

    @Test
    fun `病灶那一形在 fetchSemesterInfo 里零处`() {
        val fetcher = blankCommentsKeepingLiterals(source(FETCHER))

        assertEquals(
            "`$LESION_NEEDLE` 在 `$FETCHER` 恰好 0 处（实到 " + occurrences(fetcher, LESION_NEEDLE) +
                " 处）。它就是 T193 登记的病灶本体：只实现了两条判据里的第一条，" +
                "而 `?: weeks.first()` 是不可达支 —— 真正会走到的退化是 serialNumber 全 null 时" +
                "minByOrNull 键全体并列、于是回列表首行",
            0,
            occurrences(fetcher, LESION_NEEDLE),
        )
        val lesions = firstWeekPickLesions(fetcher)
        assertEquals(
            "结构探测器在 `$FETCHER` 也必须 0 处（实到 " + lesions.size + " 处：" +
                lesions.joinToString { "index=$it" } + "）。探测器比字面针宽一档 —— " +
                "它连「`minByOrNull { it.serialNumber }`（摘掉 `?: Int.MAX_VALUE` 的改名版）」与" +
                "「直接拿 `weeks.first().startDate` 当开学日」（接线变异那一形）一起拦",
            0,
            lesions.size,
        )
    }

    // ─────────────── ③ 同族写法的册子：全 main 恰好 1 处 ───────────────

    @Test
    fun `同族写法全 main 一枚不多 落点册子钉死`() {
        val byFile = readMainSources()

        assertEquals(
            "全 app/src/main 的 `$LESION_NEEDLE` 恰好 1 处（实到 " +
                occurrencesOfAll(byFile, LESION_NEEDLE) + " 处，册子 " + ledger(byFile, LESION_NEEDLE) +
                "）。卡面在 7bed35a 上算好的三枚数（判据定义 1 / 调用点 0 / 全 main 同族 1）里" +
                "这一格是第三枚：剩下那一枚在 `BuaaScheduleParser.parseTermWeeks` —— " +
                "普查显示它零生产调用方（只有 `BuaaTermWeeksParserTest` 在叫），属「死入口」另案，" +
                "本卡不动它；枚数与落点一起钉，将来谁再长一枚就红",
            1,
            occurrencesOfAll(byFile, LESION_NEEDLE),
        )
        assertEquals(
            "那一枚的落点册子必须是 `{BuaaScheduleParser.kt=1}`（实到 " + ledger(byFile, LESION_NEEDLE) +
                "）：内核如果改用 `minByOrNull { it.serialNumber ?: Int.MAX_VALUE }` 那一形，" +
                "这一格会红 —— 但那正是第 ② 层拦的东西，两格互为备份",
            listOf(SCHEDULE_PARSER_NAME),
            filesHolding(byFile, LESION_NEEDLE),
        )
        val structural = byFile.entries.toList()
            .sortedBy { it.key }
            .filter { firstWeekPickLesions(it.value).isNotEmpty() }
        assertEquals(
            "结构探测器在全 app/src/main 也只许 1 处（实到 " + structural.size + " 处，册子 " +
                structural.joinToString(prefix = "{", postfix = "}") { "${it.key}=${firstWeekPickLesions(it.value).size}" } +
                "）：探测器比字面针宽，这一格是「换个写法躲针」那一档的备份",
            1,
            structural.size,
        )
    }

    // ─────────────── ④ 裁剪只长在产物的那一侧 ───────────────
    //
    // 卡面第 4 格给的期望是「`?.substringBefore(" ")` 在 BuaaInPageFetcher.kt 恰好 1 处、
    // 且落在内核调用产物那一行」。**实到 0 处，本层按 0 钉**，理由写在消息里：
    // 内核判据 4 交出去的已经是 `trim().substringBefore(" ")` 的产物，而 `Resolved.startDate`
    // 是非空 String —— 在调用点再写一枚 `?.substringBefore(" ")` 需要先把产物的类型声明成可空，
    // 那等于自己造一枚「永远走不到的 `?.`」，正是本卡 §1-2 登记的「不可达支」那一形。
    // 所以这一层的**语义**（裁剪只做一次、不许挪回 DTO 侧再来一遍）按更严的形状钉：
    // 调用点 0 处 + 内核 1 处且落在候选构造那一行。

    @Test
    fun `时间尾巴只裁一次 裁在内核的候选构造那一行`() {
        val fetcher = blankCommentsKeepingLiterals(source(FETCHER))
        val kernel = blankCommentsKeepingLiterals(source(KERNEL))

        for (needle in listOf(CUT_NEEDLE_SAFE, CUT_NEEDLE_PLAIN)) {
            assertEquals(
                "`$needle` 在 `$FETCHER` 必须 0 处（实到 " + occurrences(fetcher, needle) +
                    " 处）：卡面第 4 格给的是 1，本层按 0 钉 —— 内核产物已是裁好的串，" +
                    "调用点再裁一次要么需要一枚永远走不到的 `?.`（本卡登记的不可达支那一形），" +
                    "要么就是把裁剪挪回 DTO 侧再来一遍（卡面同一格明令不许的那一手）",
                0,
                occurrences(fetcher, needle),
            )
        }
        assertEquals(
            "内核里 `$CUT_NEEDLE_PLAIN` 恰好 1 处（实到 " + occurrences(kernel, CUT_NEEDLE_PLAIN) +
                " 处）：裁剪全仓只做这一次，做在候选行的比较键上",
            1,
            occurrences(kernel, CUT_NEEDLE_PLAIN),
        )
        val cutLine = lineAt(kernel, kernel.indexOf(CUT_NEEDLE_PLAIN))
        assertTrue(
            "那一枚裁剪必须长在候选构造那一行（比较键与产物同一枚）。实到行：`" + cutLine.trim() +
                "` —— 挪去 DTO 映射那一侧的话这一格红",
            cutLine.contains("Candidate("),
        )
    }

    // ─────────────── ⑤ 内核纯度 + 调用点接线 ───────────────

    @Test
    fun `内核零 android 零时钟零 parse 一行 import 都不需要`() {
        val kernel = blankCommentsKeepingLiterals(source(KERNEL))

        for (needle in listOf("import android", "LocalDate.now(", "System.currentTimeMillis", "Calendar.")) {
            assertEquals(
                "内核里 `$needle` 必须 0 处（实到 " + occurrences(kernel, needle) +
                    " 处）：本仓收单硬口径 —— 设备面与时钟一律由调用点当参数传。" +
                    "判据一旦能读时钟，「今天所在周的周一」就会顺着这枚参数爬回来（T180 否掉的那一等猜法）",
                0,
                occurrences(kernel, needle),
            )
        }
        for (needle in listOf("LocalDate.parse(", "mondayOf(", "import java.time")) {
            assertEquals(
                "内核里 `$needle` 必须 0 处（实到 " + occurrences(kernel, needle) +
                    " 处）：判据 4 要的是「挑中那一行 startDate 的原样」，不 parse、不归一成周一 —— " +
                    "「认不认得这枚串」是 T180 的 resolveBuaaSemesterStart 那一判的活，" +
                    "在这里 parse 就是「抢救格式 = 猜」的入口",
                0,
                occurrences(kernel, needle),
            )
        }
        assertEquals(
            "内核文件整份零 import（实到 " + occurrences(kernel, "\nimport ") + " 处）：判据只用 String 与 Int " +
                "的比较，本仓这一族内核里最纯的一枚。将来真要引 `java.time` 之外的东西时这一格会红一次，" +
                "逼着来人重读本卡的「为什么串序就够、为什么不需要 parse」那一段",
            0,
            occurrences(kernel, "\nimport "),
        )
    }

    @Test
    fun `调用点真的读内核产物 两档各一句日志 外壳四档早返回原样还在`() {
        val fetcher = blankCommentsKeepingLiterals(source(FETCHER))

        assertEquals(
            "调用点对内核的调用 `resolveBuaaFirstWeek(` 在 `$FETCHER` 恰好 1 处（实到 " +
                occurrences(fetcher, "resolveBuaaFirstWeek(") + " 处）：摘掉它 = 这一族白修",
            1,
            occurrences(fetcher, "resolveBuaaFirstWeek("),
        )
        assertEquals(
            "DTO → 壳的映射 `BuaaWeekRow(` 恰好 1 处（实到 " + occurrences(fetcher, "BuaaWeekRow(") +
                " 处）：判据的入参必须由调用点造，别在 DTO 上顺手裁日期",
            1,
            occurrences(fetcher, "BuaaWeekRow("),
        )
        assertEquals(
            "`when (firstWeek)` 那一枚恰好一处（实到 " + occurrences(fetcher, "when (firstWeek)") +
                " 处）：两档结论必须都被人认领",
            1,
            occurrences(fetcher, "when (firstWeek)"),
        )

        val resolved = branchBody(fetcher, RESOLVED_BRANCH)
        val undetermined = branchBody(fetcher, UNDETERMINED_BRANCH)
        assertEquals(
            "Resolved 分支里 `Log.i(` 恰好一处（实到 " + occurrences(resolved, "Log.i(") +
                " 处）：用了哪条判据要留痕",
            1,
            occurrences(resolved, "Log.i("),
        )
        assertEquals(
            "Resolved 分支里 `Log.w(` 必须 0 处（实到 " + occurrences(resolved, "Log.w(") + " 处）",
            0,
            occurrences(resolved, "Log.w("),
        )
        assertEquals(
            "Undetermined 分支里 `Log.w(` 恰好一处（实到 " + occurrences(undetermined, "Log.w(") +
                " 处）：判不出比猜一枚日期更该被看见",
            1,
            occurrences(undetermined, "Log.w("),
        )
        assertEquals(
            "Undetermined 分支里 `Log.i(` 必须 0 处（实到 " + occurrences(undetermined, "Log.i(") + " 处）",
            0,
            occurrences(undetermined, "Log.i("),
        )
        for (branch in listOf(resolved to "Resolved", undetermined to "Undetermined")) {
            assertEquals(
                "「${branch.second}」分支里 `Log.d(` 必须 0 处（实到 " + occurrences(branch.first, "Log.d(") +
                    " 处）：release 包里的 Info 闸门才读得到，取证句写日志级别写轻了等于没写",
                0,
                occurrences(branch.first, "Log.d("),
            )
        }
        assertEquals(
            "Undetermined 那一档交出去的是 `SemesterInfo(weeks.size, null)`（恰好一处，实到 " +
                occurrences(undetermined, UNDETERMINED_PRODUCT) + " 处）：**不是** `SemesterInfo(20, null)` —— " +
                "周数那一格今天的样子要留着，本卡只改「怎么挑第 1 周」",
            1,
            occurrences(undetermined, UNDETERMINED_PRODUCT),
        )
        assertEquals(
            "Resolved 那一档把内核产物原样交出 `SemesterInfo(weeks.size, firstWeek.startDate)`（恰好一处，实到 " +
                occurrences(resolved, RESOLVED_PRODUCT) + " 处）：加任何 `?: ` 就是把不可达兜底请回来",
            1,
            occurrences(resolved, RESOLVED_PRODUCT),
        )

        // §3 那几枚不许碰的格子：四档早返回与 runCatching 外壳都按原样数
        assertEquals(
            "`SemesterInfo(20, null)` 在 `$FETCHER` 恰好 4 处（实到 " + occurrences(fetcher, "SemesterInfo(20, null)") +
                " 处）：T188 登记过的那四档早返回一字不动（termCode 非法 / raw 取不到 / weeks 空 / " +
                "整块 runCatching 失败）",
            4,
            occurrences(fetcher, "SemesterInfo(20, null)"),
        )
        assertEquals(
            "`.getOrDefault(SemesterInfo(20, null))` 外壳恰好一处（实到 " +
                occurrences(fetcher, "}.getOrDefault(SemesterInfo(20, null))") +
                " 处）：本卡改写的是块内怎么挑第 1 周，不许把 runCatching 或 getOrDefault 摘掉 —— " +
                "那两枚实物同时是 `BuaaSemesterStartWiringGuardTest` 第 ① 层负对照的证据",
            1,
            occurrences(fetcher, "}.getOrDefault(SemesterInfo(20, null))"),
        )
        assertEquals(
            "`}.getOrDefault(value)`（`decodeJsString` 那一枚合法兜底）必须还在（实到 " +
                occurrences(fetcher, "}.getOrDefault(value)") + " 处）：同上，它是姊妹守卫负对照的盘上实物",
            1,
            occurrences(fetcher, "}.getOrDefault(value)"),
        )
    }

    // ─────────────── ⑥ 反套套逻辑：探测器自己得会做事 ───────────────

    /**
     * 只报「盘上 0 命中」而探测器其实什么都抓不到，等于没有守卫（本仓口径：别拿 grep 没匹配到当全绿）。
     * 两个方向各一头：朝松（探测器瞎）红在第一手，朝窄（探测器见 `minByOrNull` 就杀）红在第二、三手。
     */
    @Test
    fun `探测器认得旧那一形 也不误伤合法的 minByOrNull 与 maxByOrNull`() {
        // 改之前的原文（BuaaInPageFetcher.kt:270-272 那三行）
        val oldShape = """
            // 第1周（serialNumber 最小 / startDate 最早）的 startDate 即开学日
            val first = weeks.minByOrNull { it.serialNumber ?: Int.MAX_VALUE } ?: weeks.first()
            SemesterInfo(weeks.size, first.startDate?.substringBefore(" "))
        """.trimIndent()
        assertEquals(
            "探测器必须把改之前那一形原文抓出来。实到 " + firstWeekPickLesions(oldShape).size +
                " 处 —— 0 处说明探测器空转，第 ② 层那句「盘上 0 处」是假的绿",
            1,
            firstWeekPickLesions(oldShape).size,
        )

        // 改名版：摘掉 `?: Int.MAX_VALUE` 只留键（字面针抓不到，探测器必须抓到）
        val renamedKey = "val first = weeks.minByOrNull { it.serialNumber } ?: weeks.first()"
        assertEquals(
            "字面针抓不到的改名版（`minByOrNull { it.serialNumber }`）探测器也必须抓到。实到 " +
                firstWeekPickLesions(renamedKey).size + " 处",
            1,
            firstWeekPickLesions(renamedKey).size,
        )

        // 接线版：绕过内核直接取列表首行（卡面要求的接线变异那一形）
        val directFirstRow = "SemesterInfo(weeks.size, weeks.first().startDate)"
        assertEquals(
            "「直接拿列表首行当开学日」那一形探测器也必须抓到。实到 " +
                firstWeekPickLesions(directFirstRow).size + " 处",
            1,
            firstWeekPickLesions(directFirstRow).size,
        )

        // 合法一：Color.kt:225 那一形（minByOrNull 但键与 serialNumber 无关，且产物是 !!）
        val legitColor = """
            val foreground = listOf(preferred, if (preferred == ContentLight) ContentDark else ContentLight)
                .minByOrNull { alphaNeededByInk(tintLuma, scene, requested, it) }!!
        """.trimIndent()
        assertEquals(
            "`Color.kt:225` 那一形（合法的 minByOrNull，键里没有 serialNumber）**不许**被算成命中。实到 " +
                firstWeekPickLesions(legitColor).size + " 处 —— 探测器宽到见 minByOrNull 就杀的话，" +
                "它会把上游合法的取最小值一起拆掉",
            0,
            firstWeekPickLesions(legitColor).size,
        )

        // 合法二：CourseWeekSpans.kt:92 那一形
        val legitSpans = """
            return rows.asSequence()
                .filter { !it.weeksUnknown }
                .mapNotNull { c -> c.lastWeek?.takeIf { it >= week }?.let { it to c } }
                .minByOrNull { it.first }
                ?.second
        """.trimIndent()
        assertEquals(
            "`CourseWeekSpans.kt:92` 那一形（合法的 minByOrNull，取「最先结课」）不许被算成命中。实到 " +
                firstWeekPickLesions(legitSpans).size + " 处",
            0,
            firstWeekPickLesions(legitSpans).size,
        )

        // 合法三：maxByOrNull（盘上 GlassSurface.kt:406 那一族）
        val legitMax = "val best = candidates.maxByOrNull { plate -> plate.score } ?: return null"
        assertEquals(
            "`maxByOrNull` 那一形不许被算成命中（探测器只认 min 侧的序号键）。实到 " +
                firstWeekPickLesions(legitMax).size + " 处",
            0,
            firstWeekPickLesions(legitMax).size,
        )

        // 盘上那枚合法的 minByOrNull 用法仍在（否则上面的负对照就自证为空）
        val byFile = readMainSources()
        val legitOnDisk = occurrencesOfAll(byFile, "minByOrNull")
        assertTrue(
            "盘上的 `minByOrNull` 至少还有 5 处（实到 $legitOnDisk 处）：探测器是在一片合法用法里" +
                "挑出那一枚病灶，不是在全仓空转 —— 盘上只剩病灶那一枚的话第 ② 层的 0 就没东西可拦了",
            legitOnDisk >= 5,
        )
    }

    // ─────────────── ⑦ 同族第二处（死入口）一个字节没动 ───────────────

    @Test
    fun `parseTermWeeks 那处另案仍是原样 兜底仍是诚实的 return null`() {
        val parser = blankCommentsKeepingLiterals(source(SCHEDULE_PARSER))

        assertEquals(
            "`fun parseTermWeeks(` 在 `$SCHEDULE_PARSER` 恰好一处（实到 " +
                occurrences(parser, "fun parseTermWeeks(") + " 处）：本卡的交付里不许出现对它的改动" +
                "（普查显示零生产调用方，属「死入口」另案）",
            1,
            occurrences(parser, "fun parseTermWeeks("),
        )
        assertEquals(
            "它那枚同族写法必须还是原样一处（实到 " + occurrences(parser, LESION_NEEDLE) + " 处）",
            1,
            occurrences(parser, LESION_NEEDLE),
        )
        val honestFallback = occurrences(parser, "?: return null")
        assertTrue(
            "它的兜底必须是诚实的 `?: return null`（实到 $honestFallback 处）：与 `?: weeks.first()` " +
                "的区别就在这儿 —— 挑不出就说挑不出",
            honestFallback >= 1,
        )
    }

    // ---------------- 探测器：「哪一枚算第 1 周」那一族病灶 ----------------

    /**
     * 找出 [code] 里所有「拿一批周次行挑第 1 周却只按序号（或干脆按列表首行）挑」的落点。
     *
     * 两形各算一处命中：
     * - `minByOrNull { …serialNumber… }`：键里出现 `serialNumber` 才算 —— 这一族的问题从来不是
     *   「用了 minByOrNull」，而是「拿序号当唯一判据，序号缺席时键全并列」；
     * - `.first().startDate` / `.firstOrNull()?.startDate`：列表首行当第 1 周那一形（接线变异的目标）。
     *
     * 比字面针 `minByOrNull { it.serialNumber ?: Int.MAX_VALUE }` 宽一档，为的是拦住「摘掉
     * `?: Int.MAX_VALUE` 只留键」那等改名版；又比「见 minByOrNull 就杀」窄一档，为的是不误伤
     * `Color.kt:225` / `CourseWeekSpans.kt:92` 那两形（第 ⑥ 层两头都钉）。
     */
    private fun firstWeekPickLesions(code: String): List<Int> {
        val hits = mutableListOf<Int>()
        var from = 0
        while (true) {
            val at = code.indexOf("minByOrNull", from)
            if (at < 0) break
            from = at + "minByOrNull".length
            var open = at + "minByOrNull".length
            while (open < code.length && code[open].isWhitespace()) open++
            if (open >= code.length || code[open] != '{') continue
            val close = matchingBrace(code, open) ?: continue
            if (code.substring(open + 1, close).contains("serialNumber")) hits += at
        }
        for (needle in listOf(FIRST_ROW_SAFE, FIRST_ROW_DIRECT)) {
            hits += indexOfAll(code, needle)
        }
        return hits.sorted()
    }

    // ---------------- 源码核对小工具（与仓内其它静态守卫同一口径）----------------

    private fun branchBody(code: String, anchor: String): String {
        val hits = indexOfAll(code, anchor)
        assertEquals("`$anchor` 恰好一处（本层取块的宿主）：实到 " + hits.size + " 处", 1, hits.size)
        return braceBodyFrom(code, code.indexOf('{', hits.first() + anchor.length))
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

    private fun source(relativeFromMainJava: String): String {
        val file = File(findMainJavaDir(), relativeFromMainJava)
        assertTrue("找不到 ${file.path}：文件挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText().replace("\r\n", "\n")
    }

    /** 文件名 → 抹注释后的内容（同名文件直接抛，避免按文件名建册子时读错份） */
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

    /** 从已知开括号位按花括号配平取出那一段体 */
    private fun braceBodyFrom(source: String, open: Int): String {
        check(source.getOrNull(open) == '{') { "锚点末尾不是花括号：写法变了，切不出一段体（实到「${
            source.getOrNull(open)
        }」）" }
        return source.substring(open + 1, (matchingBrace(source, open) ?: error("花括号没配平：从 $open 起")))
    }

    /** 花括号配平（跳过字符串与字符字面量）；不配平返回 null */
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
     * 本卡尤其需要抹注释：`BuaaFirstWeekPolicy.kt` 的段首**逐字引着旧那一形原文**
     * （`minByOrNull { it.serialNumber ?: Int.MAX_VALUE } ?: weeks.first()`），
     * 不抹的话探测器会把自己钉的守卫当成病灶。
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

    private companion object {
        const val KERNEL = "com/buaa/schedule/data/import/BuaaFirstWeekPolicy.kt"
        const val KERNEL_NAME = "BuaaFirstWeekPolicy.kt"
        const val FETCHER = "com/buaa/schedule/data/import/BuaaInPageFetcher.kt"
        const val SCHEDULE_PARSER = "com/buaa/schedule/data/import/BuaaScheduleParser.kt"
        const val SCHEDULE_PARSER_NAME = "BuaaScheduleParser.kt"

        /** 判据本体与病灶那枚字面针（卡面 §4 的 1 / 0 / 1 三枚数） */
        const val KERNEL_FN_ANCHOR = "fun resolveBuaaFirstWeek("
        const val LESION_NEEDLE = "minByOrNull { it.serialNumber ?: Int.MAX_VALUE }"

        /** 时间尾巴裁剪的两形针（安全调用版与裸调用版） */
        const val CUT_NEEDLE_SAFE = "?.substringBefore(\" \")"
        const val CUT_NEEDLE_PLAIN = "substringBefore(\" \")"

        /** 「列表首行当第 1 周」那一形的两枚针 */
        const val FIRST_ROW_DIRECT = ".first().startDate"
        const val FIRST_ROW_SAFE = ".firstOrNull()?.startDate"

        /** 调用点两档分支的锚点与产物 */
        const val RESOLVED_BRANCH = "is BuaaFirstWeek.Resolved ->"
        const val UNDETERMINED_BRANCH = "is BuaaFirstWeek.Undetermined ->"
        const val RESOLVED_PRODUCT = "SemesterInfo(weeks.size, firstWeek.startDate)"
        const val UNDETERMINED_PRODUCT = "SemesterInfo(weeks.size, null)"
    }
}
