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
 * 3. [同族册子] 全 `app/src/main` 的同一枚字面针恰好 **0 处**（T193 那版是 1，落点
 *    `{BuaaScheduleParser.kt=1}`；T216 把那一枚换成调真路内核 ⇒ 册子归零，这一格从"单文件尺"
 *    升成"全仓尺"，将来谁再长一枚就红）。
 * 4. [裁剪只有一处] 见该层注释（这一层的期望枚数与卡面给的数字不同，理由写在消息里）。
 * 5. [内核纯度 + 接线] 内核零 android、零时钟、零 parse、零 import；调用点对内核的调用恰好 1 处、
 *    入参映射恰好 1 处、两档日志各在各自的分支里、`runCatching` 外壳与四档早返回原样还在。
 * 6. [反套套逻辑] 探测器必须**认得**改前那一形原文（内联样例抓到 1 处），同时**不误伤**盘上合法的
 *    `Color.kt:225` / `CourseWeekSpans.kt:92` 两形与 `maxByOrNull`（各 0 处）。
 *    本仓口径：不许拿「grep 没匹配到」当「全绿」。
 * 7. [T193 留给下一张卡的那一处另案] **本层从「钉原样」改成钉「新形状」**（T216，卡面 §1 点名要写明
 *    这一手）：`parseTermWeeks` 不再自己判「谁第 1 周」，改为把 DTO 映射成壳再交 [KERNEL_FN_ANCHOR]，
 *    挑不出那一档仍直书 `return null`。守卫没删、没 `@Ignore`，换掉的只有判据。
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

    // ─────────────── ③ 同族写法的册子：全 main 归零（T216 重钉） ───────────────
    //
    // **这一层的期望枚数被 T216 改过**：T193 那版钉的是「全 main 恰好 1 处、落点
    // `{BuaaScheduleParser.kt=1}`」，那一枚就是当时留给下一张卡的另案（零生产调用方的第二路
    // 「周列表→学期」）。T216 把那一处换成调真路内核之后，同族针在全 `app/src/main` 归零 ——
    // 于是这一格从"单文件尺"升成"全仓尺"：第 ② 层管 fetchSemesterInfo 那一枚不许回潮，
    // 这一格管**任何**文件都不许再长一枚。两格仍是互为备份（探测器版与字面针版各一头）。

    @Test
    fun `同族写法全 main 已经归零 再长一枚就红`() {
        val byFile = readMainSources()

        assertEquals(
            "全 app/src/main 的 `$LESION_NEEDLE` 必须 0 处（实到 " +
                occurrencesOfAll(byFile, LESION_NEEDLE) + " 处，册子 " + ledger(byFile, LESION_NEEDLE) +
                "）。T193 那版钉的是 1 处（`BuaaScheduleParser.parseTermWeeks` 那一枚另案，" +
                "普查零生产调用方）；T216 把那一处换成调 `resolveBuaaFirstWeek` ⇒ 期望改判成 0，" +
                "守卫本体一字没删。今天再有人写「只按 serialNumber 挑第 1 周」，不管长在哪个文件里都红",
            0,
            occurrencesOfAll(byFile, LESION_NEEDLE),
        )
        assertEquals(
            "那一枚字面针的落点册子必须是空册子（实到 " + ledger(byFile, LESION_NEEDLE) +
                "）：T193 钉的是 `{BuaaScheduleParser.kt=1}`，T216 之后任何非空册子都算回潮",
            emptyList<String>(),
            filesHolding(byFile, LESION_NEEDLE),
        )
        val structural = byFile.entries.toList()
            .sortedBy { it.key }
            .filter { firstWeekPickLesions(it.value).isNotEmpty() }
        assertEquals(
            "结构探测器在全 app/src/main 也必须 0 处（实到 " + structural.size + " 处，册子 " +
                structural.joinToString(prefix = "{", postfix = "}") { "${it.key}=${firstWeekPickLesions(it.value).size}" } +
                "）：探测器比字面针宽，这一格拦的是「换个写法躲针」（摘掉 `?: Int.MAX_VALUE` 的改名版、" +
                "直接拿列表首行当开学日）—— 归零之后它与第 ② 层合起来才是全仓尺",
            0,
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
            "Undetermined 那一档交出去的是 `SemesterInfo(weeks.size, null, …)`（恰好一处，实到 " +
                occurrences(undetermined, UNDETERMINED_PRODUCT) + " 处）：**不是** `SemesterInfo(20, null, …)` —— " +
                "周数那一格今天的样子要留着，本卡只改「怎么挑第 1 周」（第三枚实参是 T196④ 的原因名）",
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
        // ⚠️ T196④ 给 `SemesterInfo` 加了第三枚实参（那一档「为什么没有」的原因），构造点于是从
        // `SemesterInfo(20, null)` 变成 `SemesterInfo(20, null, SemesterInfoReason.…)`。
        // **枚数一格没动（4 与 1）、期望值也没改**，只有针去掉右括号 —— 卡面 §1-④ 的 ⚠️ 写明
        // 本卡不许改 `totalWeeks` 的 20 那四格，`20` 与 `null` 两枚实参逐字还在那一行上。
        assertEquals(
            "`SemesterInfo(20, null,` 在 `$FETCHER` 恰好 4 处（实到 " + occurrences(fetcher, "SemesterInfo(20, null,") +
                " 处）：T188 登记过的那四档早返回一字不动（termCode 非法 / raw 取不到 / weeks 空 / " +
                "整块 runCatching 失败），枚数钉死的是「四档」这件事本身",
            4,
            occurrences(fetcher, "SemesterInfo(20, null,"),
        )
        assertEquals(
            "`.getOrDefault(SemesterInfo(20, null,` 外壳恰好一处（实到 " +
                occurrences(fetcher, "}.getOrDefault(SemesterInfo(20, null,") +
                " 处）：本卡改写的是块内怎么挑第 1 周，不许把 runCatching 或 getOrDefault 摘掉 —— " +
                "那两枚实物同时是 `BuaaSemesterStartWiringGuardTest` 第 ① 层负对照的证据",
            1,
            occurrences(fetcher, "}.getOrDefault(SemesterInfo(20, null,"),
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

        // 盘上那几枚合法的 minByOrNull 用法仍在（否则上面的负对照就自证为空）
        val byFile = readMainSources()
        val legitOnDisk = occurrencesOfAll(byFile, "minByOrNull")
        assertTrue(
            "盘上抹注释后的 `minByOrNull` 至少还有 4 处（实到 $legitOnDisk 处）：探测器是在一片合法用法里" +
                "挑出那一枚病灶，不是在全仓空转 —— 盘上只剩病灶那一枚的话第 ②③ 层的 0 就没东西可拦了。" +
                "T193 那版这枚尺是 5（病灶那一枚自己也算在内），T216 把 `BuaaScheduleParser` 那枚换成" +
                "调真路内核 ⇒ 盘上少一枚，地板跟着降到 4（今天实到 4：Color / CourseWeekSpans / " +
                "ClassProgressScheduler / StatsEntryPolicy 各一枚，都与序号无关）",
            legitOnDisk >= 4,
        )
    }

    // ─────────────── ⑦ 那处另案：从「钉原样」改钉「新形状」（T216） ───────────────
    //
    // T193 在 `parseTermWeeks` 那一处留了个另案，把这层的期望**逐字钉成原样**（同族针 1 处 +
    // `?: return null` 那枚诚实兜底），好让下一张卡改它时一定撞红。T216 就是那张卡：那一处今天
    // 改成「DTO → 壳的映射 + 调真路内核 + 挑不出直书 return null」。
    // ⇒ 本层按卡面 §1 的要求**换判据、不换守卫**：不删、不 `@Ignore`，把原来钉"原样"的三格改成
    // 钉"新形状"，并且用四种钉法各钉一头（逐字节 / 数出现次数 / 读形状 / 抹注释找锚点）。
    // 没被钉住的那一格也写明：`?: return null` 那枚字面兜底今天在该文件只剩 2 处（`extractCampus`
    // 与 `extractLocation` 各一枚），它已经不是 parseTermWeeks 的兜底 —— 兜底换了形状，就换针。

    @Test
    fun `parseTermWeeks 那处另案已换成调真路内核 挑不出照旧直书 return null`() {
        val raw = source(SCHEDULE_PARSER)
        val parser = blankCommentsKeepingLiterals(raw)

        // 钉法①（逐字节）：函数头、DTO→壳 那一手、Undetermined 那一档，三行一个字都不许漂
        for (needle in listOf(
            PARSE_TERM_WEEKS_SIGNATURE,
            WEEK_ROW_MAPPING,
            UNDETERMINED_FALLBACK,
        )) {
            assertEquals(
                "**钉法 = 逐字节**：`$needle` 在 `$SCHEDULE_PARSER` 必须恰好一处（实到 " +
                    occurrences(parser, needle) + " 处）。这三行是 T216 给那处另案立的新形状 —— " +
                    "改一个字（把内核调用换成自己挑行、把 Undetermined 那一档换成 `?: 兜底日期`）当场红",
                1,
                occurrences(parser, needle),
            )
        }

        // 钉法②（数出现次数）：函数本身一处、对内核的调用一处、自己不复刻的判据零处
        assertEquals(
            "**钉法 = 数出现次数**：`fun parseTermWeeks(` 在 `$SCHEDULE_PARSER` 恰好一处（实到 " +
                occurrences(parser, "fun parseTermWeeks(") + " 处）。T193 那版这一格钉的是「原样不许动」，" +
                "T216 之后钉的是「这一路还在，但它只是真路内核的一层薄壳」—— 悄悄删掉它也算改形状",
            1,
            occurrences(parser, "fun parseTermWeeks("),
        )
        assertEquals(
            "**钉法 = 数出现次数**：解析器对内核的调用 `resolveBuaaFirstWeek(` 恰好一处（实到 " +
                occurrences(parser, "resolveBuaaFirstWeek(") + " 处）：0 处 = 那一判被复刻回第二本，" +
                "2 处 = 同一次抓取被两把尺量（第 ① 层那枚「定义唯一」还是 1，这里数的是调用）",
            1,
            occurrences(parser, "resolveBuaaFirstWeek("),
        )
        assertEquals(
            "**钉法 = 数出现次数**：解析器里 `minByOrNull` 必须 0 处（实到 " +
                occurrences(parser, "minByOrNull") + " 处）—— 这就是「从钉原样改成钉新形状」那一手：",
            0,
            occurrences(parser, "minByOrNull"),
        )

        // 钉法③（读形状）：花括号配平切出函数体，体里既不许有序号键也不许有列表首行
        val body = braceBodyFrom(parser, parser.indexOf('{', parser.indexOf(PARSE_TERM_WEEKS_SIGNATURE)))
        for (needle in listOf("minByOrNull", FIRST_ROW_DIRECT, FIRST_ROW_SAFE, "?: weeks.first()", "indexOfFirst")) {
            assertEquals(
                "**钉法 = 读形状**：`parseTermWeeks` 体内 `$needle` 必须 0 处（实到 " +
                    occurrences(body, needle) + " 处）：这一判的两条来源与并列分档都在内核里，" +
                    "壳里再挑一次就是第二本判据（第 ②③ 层那两把全仓尺也在这儿等着）",
                0,
                occurrences(body, needle),
            )
        }
        assertEquals(
            "**钉法 = 读形状**：体内对 `resolveBuaaFirstWeek(` 的调用必须真在体内（实到 " +
                occurrences(body, "resolveBuaaFirstWeek(") + " 处）：只在参数默认值或注释里挂个名字不算接线",
            1,
            occurrences(body, "resolveBuaaFirstWeek("),
        )

        // 钉法④（抹注释找锚点）：这枚文件的 KDoc **逐字引用着改前那一形**，含注释的原文里有 1 处、
        // 抹注释之后的代码里 0 处 —— 两枚数一起钉，才既不让守卫把自己钉红、也不让回潮藏在注释旁边。
        assertEquals(
            "**钉法 = 抹注释找锚点**：`$LESION_NEEDLE` 在**含注释**的原文里恰好 1 处（实到 " +
                occurrences(raw, LESION_NEEDLE) + " 处）—— 那一处是 KDoc 里「改前是什么形状」的逐字引文，" +
                "删了引文这格会红，把引文搬进代码那格也会红（下面第 ③ 格管代码侧）",
            1,
            occurrences(raw, LESION_NEEDLE),
        )
        assertEquals(
            "**钉法 = 抹注释找锚点**：同一枚针在**抹注释后**的代码里必须 0 处（实到 " +
                occurrences(parser, LESION_NEEDLE) + " 处）：T193 那版这一格钉的是 1（另案原样），" +
                "T216 之后代码侧归零、注释侧留档",
            0,
            occurrences(parser, LESION_NEEDLE),
        )

        // 另两格：这枚壳的诚实兜底不换位置；同名册子那枚常量还得被用得上（防漂移的锚点自查）
        assertEquals(
            "**钉法 = 数出现次数**：`return null` 在 parseTermWeeks 体内恰好 3 处（实到 " +
                occurrences(body, "return null") + " 处）：空列表 / 内核 Undetermined / 学期号不唯一，" +
                "三档各一枚，谁都别把它换成猜出来的日期 —— 改前那枚诚实兜底（`?: return null`）换了形状，" +
                "但「挑不出就说挑不出」那一判一个字没改",
            3,
            occurrences(body, "return null"),
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

        /** 判据本体与病灶那枚字面针（卡面 §4 的 1 / 0 / 1 三枚数；T216 之后第三枚改判成 0） */
        const val KERNEL_FN_ANCHOR = "fun resolveBuaaFirstWeek("
        const val LESION_NEEDLE = "minByOrNull { it.serialNumber ?: Int.MAX_VALUE }"

        /**
         * T216 第 ⑦ 层「新形状」那三枚逐字节针（改前钉的是「原样不许动」，见该层注释）。
         *
         * 三行合起来就是那处另案今天的样子：一层薄壳 —— 自己认得 DTO，但**不自己判**第 1 周，
         * 判不出时直书 `return null`。任何一行漂了，都说明有人在壳里重新立判据或换了兜底形状。
         */
        const val PARSE_TERM_WEEKS_SIGNATURE = "fun parseTermWeeks(items: List<BuaaTermWeekDto>): Semester? {"
        const val WEEK_ROW_MAPPING = "resolveBuaaFirstWeek(items.map { BuaaWeekRow(it.serialNumber, it.startDate) })"
        const val UNDETERMINED_FALLBACK = "is BuaaFirstWeek.Undetermined -> return null"

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

        /**
         * T196④ 之后 `SemesterInfo` 多第三枚实参（那一档「为什么没有」的原因），
         * 针去掉右括号；**期望枚数仍是 1**，`weeks.size` 那一格本卡不许改成 20。
         */
        const val UNDETERMINED_PRODUCT = "SemesterInfo(weeks.size, null,"
    }
}
