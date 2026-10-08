package com.buaa.schedule.domain.schedule

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [#524]（T275 审计 N1，第⓪问裁的丁档）的源码形状守卫：星期序号 → 日期那一判是不是**只有一把尺**，
 * 以及三处日期推导是不是真的**问**它（而不是自己再夹一次、再写一遍 `(dayOfWeek - 1)`）。
 * 纯 JVM 源码核对：无 Robolectric、无设备、不读时钟。
 *
 * 本守卫读 `app/src/main/java` 下的源码文本 —— 那一目录已在 `app/build.gradle.kts` 的
 * `guardReadWorkingTreeFiles` 里整目录进 `tasks.withType<Test>()` 的 `inputs`（T92①，台账 #136），
 * 所以改 main 不重跑测试那种假绿在这里不成立。
 *
 * ## 四家钉法逐格点名（卡面第 ③ 段要求：每格自报钉法，报错消息里不写行号）
 *
 * 1. [theThreeDerivationSitesEachAskTheKernelOnce] —— **逐字节**（三处各一枚整行）+ **数出现次数**
 *    （三处各一枚偏移落地的行、各一枚"越界就走自己那一档"的行）；
 * 2. [noCourseRowWeekdayOffsetExpressionSurvivedAnywhereInMain] —— **数出现次数（全 main 册子）**：
 *    改前那一形 0 处、`dayOffsetOrReject(` 恰五处（1 定义 + 4 调用）；
 * 3. [theRemainingPlusDaySitesAreGuardedByConstruction] —— **数出现次数（按文件）+ 逐字节**：
 *    盘面剩下的 `plusDays((` 只有三处，且两处按类型取值（`java.time.DayOfWeek.value` 恒 1..7、
 *    `LaunchRequestPolicy` 的 `DAY_OF_WEEK_RANGE` 夹过），第三处乘的是 `* 7L`（周号那一维，不是星期）；
 * 4. [writeBoundaryRejectsOutOfRangeWeekdayThroughTheSameKernel] —— **读形状 + 逐字节**：
 *    写侧 normalize 体里问内核恰一处、旧字面量 `in 1..7` 0 处（丙档那一半）；
 * 5. [theKernelIsPureJvmAndDoesNotCollapseToBoolean] —— **读形状**（零钟/零时区/零权限/零 SDK_INT/零库）
 *    + **逐字节**（签名交回 sealed 档位，不是 Boolean）；
 * 6. [theUpperBoundHasASingleSource] —— **逐字节**（上界取自 SemesterStats.TOTAL_DAYS 那一行）
 *    + **数出现次数**（内核体内没有第二枚 7 当界用）；
 * 7. [probeRecognisesThePreFixShape] —— **抹注释找锚点**：改前那三形喂进同一把尺各数到该数的值，
 *    而写在 KDoc 散文里的那些引用抹完必须 0 处（防"锚点拼错的假绿"，也防"把散文当证据"）。
 */
class WeekdayDateOffsetSingleRulerGuardTest {

    // ─────────────── ① 三处推导各问内核一次 ───────────────

    @Test
    fun theThreeDerivationSitesEachAskTheKernelOnce() {
        for (relative in DERIVATION_FILES) {
            val code = withoutComments(readMainSource(relative))
            val lines = code.lines().map { it.trim() }

            assertEquals(
                "**钉法 = 逐字节**：$relative 里问内核那一手必须恰一处（两处 = 同一文件写了两遍尺；" +
                    "零处 = 这一处又回到裸算偏移，本卡的主病灶）：\n" + KERNEL_CALL,
                1,
                lines.count { it == KERNEL_CALL },
            )
            assertEquals(
                "**钉法 = 逐字节**：$relative 里日期那一手必须吃内核交回的偏移（还自己算就是第二把尺）",
                1,
                lines.count { it == OFFSET_USE },
            )
            assertEquals(
                "**钉法 = 数出现次数**：$relative 里「拒绝那一档」的处理恰一处，实到 " +
                    lines.count { it == REJECT_PROCEED || it == REJECT_NULL },
                1,
                lines.count { it == REJECT_PROCEED } + lines.count { it == REJECT_NULL },
            )
            // 改前那一形在本文件里必须一处不剩
            assertEquals(
                "**钉法 = 数出现次数**：$relative 里「(course.dayOfWeek - 1)」必须 0 处 —— 偏移只能来自内核",
                0,
                occurrences(code, "(course.dayOfWeek - 1)"),
            )
        }
    }

    // ─────────────── ② 全 main：改前那一形归零，调用册子对上 ───────────────

    @Test
    fun noCourseRowWeekdayOffsetExpressionSurvivedAnywhereInMain() {
        val all = mainCode()

        assertEquals(
            "**钉法 = 数出现次数（全 main 册子）**：改前那三处共用的 `.plusDays((course.dayOfWeek - 1).toLong())` " +
                "必须整体归零（还有 = 第四处没并进来，或者有人把某一处改回去了）",
            0,
            occurrences(all, ".plusDays((course.dayOfWeek - 1).toLong())"),
        )
        assertEquals(
            "**钉法 = 数出现次数（全 main 册子）**：`dayOffsetOrReject(` 在 main 恰五处 = 内核定义一枚 + " +
                "三处日期推导 + 写侧 normalize 一枚。多一枚 = 长出了第二个读者没进这本册子；" +
            "少一枚 = 有一处改回自己算（这是本卡唯一允许的形状，不许朝上重钉枚数）",
            5,
            occurrences(all, "dayOffsetOrReject("),
        )
        assertEquals(
            "**钉法 = 数出现次数（全 main 册子）**：吃内核偏移那一手恰三处（就是那三处日期推导）",
            3,
            occurrences(all, ".plusDays(dayTier.offsetDays)"),
        )
        assertEquals(
            "**钉法 = 数出现次数（全 main 册子）**：`object WeekdayDateOffset` 全仓一枚（第二份内核 = 第二把尺，" +
                "本卡收的就是这个族）",
            1,
            occurrences(all, "object WeekdayDateOffset {"),
        )
        assertEquals(
            "**钉法 = 数出现次数（全 main 册子）**：`sealed class WeekdayOffset` 全仓一枚",
            1,
            occurrences(all, "sealed class WeekdayOffset {"),
        )
    }

    // ─────────────── ③ 剩下的 plusDays(( 处都有构造性护栏，且不是课程行 ───────────────

    @Test
    fun theRemainingPlusDaySitesAreGuardedByConstruction() {
        val counts = mainSourcesWithCode()
            .map { it.first to linesWith(it.second, "plusDays((") }
            .filter { it.second > 0 }
            .toMap()

        assertEquals(
            "**钉法 = 数出现次数（按文件）**：全 main 里 `plusDays((` 只剩三处，且逐处都是本卡之外的事：" +
                "IcsParser（`java.time.DayOfWeek.value` 恒 1..7）、HomeScreen（组件跳转那一手，值过 " +
                "LaunchRequestPolicy 的 DAY_OF_WEEK_RANGE）、WeekView（乘的是 `* 7L`，周号那一维）。" +
                "多一处 = 第四处 `plusDays((… - 1)` 长出来了且没护栏；少一处 = 有人顺手改了本卡没授权的那一手。" +
                "实到：" + counts.entries.joinToString { "${it.key}=${it.value} " },
            mapOf(
                ICS_FILE to 1,
                HOME_FILE to 1,
                WEEK_VIEW_FILE to 1,
            ),
            counts,
        )
        assertEquals(
            "**钉法 = 数出现次数**：三处日期推导里 `plusDays((` 必须 0 处（偏移一律走内核）",
            0,
            DERIVATION_FILES.sumOf { linesWith(withoutComments(readMainSource(it)), "plusDays((") },
        )

        // ICS 那一处的护栏来自类型：DayOfWeek 枚举的 value 只有 1..7 这一种取值
        val ics = withoutComments(readMainSource(ICS_FILE))
        assertEquals(
            "**钉法 = 逐字节**：ICS 那一手吃的是 `java.time.DayOfWeek` 枚举（不是课程行的 Int），" +
                "所以它天然不会拿到 0 / 8 —— 这一枚必须还在，改了类型来源就要回来重钉本层",
            1,
            occurrences(ics, "val weekdays: List<java.time.DayOfWeek>"),
        )
        // 组件跳转那一手的护栏在 LaunchRequestPolicy 的区间夹取里
        val launch = withoutComments(readMainSource(LAUNCH_POLICY_FILE))
        assertEquals(
            "**钉法 = 逐字节**：HomeScreen 拿到的 dow 只能过这一行（越界是 null，走 `?: return@LaunchedEffect`）",
            1,
            occurrences(launch, "dayOfWeek = rawDayOfWeek.takeIf { it in DAY_OF_WEEK_RANGE },"),
        )
        // WeekView 那一处乘的是周，不是天：`* 7L` 在，就不会被读成星期偏移
        val weekView = withoutComments(readMainSource(WEEK_VIEW_FILE))
        assertEquals(
            "**钉法 = 逐字节**：表头那一手必须带 `* 7L`（周号那一维）—— 去掉它它就变成第四处星期推导",
            1,
            occurrences(weekView, "plusDays((it - 1).toLong() * 7L)"),
        )
    }

    // ─────────────── ④ 写侧那一半：normalize 问同一枚内核 ───────────────

    @Test
    fun writeBoundaryRejectsOutOfRangeWeekdayThroughTheSameKernel() {
        val code = withoutComments(readMainSource(COURSE_CONSTRAINTS_FILE))
        val body = balancedBlock(code, "fun normalize(course: Course): Course? {")

        assertEquals(
            "**钉法 = 逐字节**：写侧的星期那一判必须问内核（丁档的丙那一半；自己再写一遍比较式就是两把尺）",
            1,
            occurrences(body, KERNEL_CALL),
        )
        assertEquals(
            "**钉法 = 读形状**：写侧拿到非 Accepted 那一档必须整行判死（`return null`），" +
                "不许夹取成 1 或 7 —— 夹取会把「不知道星期」的门课画成周一那门课",
            1,
            occurrences(body, "if (dayTier !is WeekdayOffset.Accepted) return null"),
        )
        for (needle in listOf("in 1..7", "coerceIn(1, 7)", "dayOfWeek.coerce", "?: 1", "dayOfWeek = 1")) {
            assertEquals(
                "**钉法 = 读形状**：normalize 体里「$needle」必须 0 处 —— 旧字面量或兜底一长回来，" +
                    "写侧与读侧就又不是同一把尺了。实到 " + occurrences(body, needle) + " 处",
                0,
                occurrences(body, needle),
            )
        }
        assertEquals(
            "**钉法 = 数出现次数（全 main 册子）**：`dayOfWeek !in 1..7` 那种写侧自己写的比较式全仓必须归零",
            0,
            occurrences(mainCode(), "dayOfWeek !in 1..7"),
        )
        // 其余判据不许被顺手改掉（本卡只动星期那一判）
        for (needle in listOf("periods.isEmpty()", "weeks.isEmpty()", "normalizePeriods(course.periods)", "normalizeWeeks(course.weeks)")) {
            assertEquals("**钉法 = 逐字节**：normalize 里「$needle」那一手必须原样还在", 1, occurrences(body, needle))
        }
    }

    // ─────────────── ⑤ 内核自己：纯 JVM、不折成 Boolean ───────────────

    @Test
    fun theKernelIsPureJvmAndDoesNotCollapseToBoolean() {
        val kernel = balancedBlock(withoutComments(readMainSource(KERNEL_FILE)), "object WeekdayDateOffset {")

        assertEquals(
            "**钉法 = 逐字节**：内核交回的必须是 sealed 档位。签名一旦改成 `: Boolean`（或 `Long?` 那种" +
                "把两档 Rejected 合成一枚的做法），调用点就得自己重问「越的是哪一头」—— 本仓成文禁令",
            1,
            occurrences(kernel, "fun dayOffsetOrReject(dayOfWeek: Int): WeekdayOffset {"),
        )
        assertEquals("**钉法 = 读形状**：内核体里 `Boolean` 必须 0 处", 0, occurrences(kernel, "Boolean"))
        for (needle in listOf(
            "LocalDate.now(", "LocalDateTime.now(", "Clock.", "ZoneId", "TimeZone", "System.currentTimeMillis(",
            "Build.VERSION", "SDK_INT", "Context", "Dao", "Repository", "repository", "Log.", "Intent",
            "Permission", "checkSelfPermission",
        )) {
            assertEquals(
                "**钉法 = 读形状**：内核里「$needle」必须 0 处 —— 钟/时区/权限/SDK/库 任一出现，" +
                    "这一判就不再是能在 JVM 侧逐格断言的纯判据（台账 #265 那一族）。实到 " +
                    occurrences(kernel, needle) + " 处",
                0,
                occurrences(kernel, needle),
            )
        }
        // 三档都在同一枚 sealed 里，调用点念得出偏哪一侧
        val tiers = balancedBlock(withoutComments(readMainSource(KERNEL_FILE)), "sealed class WeekdayOffset {")
        for (tier in listOf("class Accepted(", "class BeforeWeek(", "class AfterWeek(")) {
            assertEquals("**钉法 = 读形状**：档位 `$tier` 必须在（缺 BeforeWeek/AfterWeek 之一就是把 0 与 8 合成一档）", 1, occurrences(tiers, tier))
        }
        assertFalse(
            "**钉法 = 读形状**：内核不许读库（`toEntity` / `Flow` 出现就不再是纯判据）",
            listOf("toEntity", "Flow", "await(").any { kernel.contains(it) },
        )
    }

    // ─────────────── ⑥ 上界只有一个出处 ───────────────

    @Test
    fun theUpperBoundHasASingleSource() {
        assertEquals(
            "**钉法 = 逐字节**：合法上界必须**取自**展示侧那枚常量，全仓恰一处这么写",
            1,
            occurrences(mainCode(), "const val LAST_DAY_INDEX: Int = SemesterStats.TOTAL_DAYS"),
        )
        val kernel = balancedBlock(withoutComments(readMainSource(KERNEL_FILE)), "object WeekdayDateOffset {")
        assertEquals(
            "**钉法 = 读形状**：内核体内拿数字当上界的写法（`> 7` / `in 1..7` / `<= 7`）必须 0 处 —— " +
                "两端都只能问常量，否则展示侧改 TOTAL_DAYS 时这一把尺不会跟着走。实到 " +
            (occurrences(kernel, "> 7") + occurrences(kernel, "in 1..7") + occurrences(kernel, "<= 7")),
            0,
            occurrences(kernel, "> 7") + occurrences(kernel, "in 1..7") + occurrences(kernel, "<= 7"),
        )
        // 展示侧那四枚既有护栏本卡没动（动了就是跨卡），这里只登记枚数与两种写法
        val qualifiedCount = occurrences(mainCode(), "!in 1..SemesterStats.TOTAL_DAYS")
        val unqualifiedCount = occurrences(mainCode(), "!in 1..TOTAL_DAYS")
        assertEquals(
            "**钉法 = 数出现次数（全 main 册子）**：展示侧那一族脏星期护栏仍恰四处 —— " +
                "三处写限定名（WeekDaySchedule / WeekFreeGrid / WeeklyLoadTrend）、一处写本对象内的短名" +
                "（SemesterStats 自己）；两者相加必须等于 4。长出第五处就该回来问：是不是又在推导侧或写侧" +
                "各写了一遍尺（本卡收的就是这个族）。实到 限定 " + qualifiedCount + " + 短名 " + unqualifiedCount,
            4,
            qualifiedCount + unqualifiedCount,
        )
        assertEquals(
            "**钉法 = 数出现次数**：内核与写侧都不许用 `!in` 那一形再写一遍尺（只问档位）",
            0,
            occurrences(balancedBlock(withoutComments(readMainSource(KERNEL_FILE)), "object WeekdayDateOffset {"), "!in") +
                occurrences(balancedBlock(withoutComments(readMainSource(COURSE_CONSTRAINTS_FILE)), "fun normalize(course: Course): Course? {"), "!in 1.."),
        )
    }

    // ─────────────── ⑦ 探测器自检：认得改前那一形，也不把散文当证据 ───────────────

    @Test
    fun probeRecognisesThePreFixShape() {
        // 朝宽（W 臂的形状）：改前那三处共用的裸推导喂进同一把尺，必须数到 3
        val preFixThreeSites = """
            val date = semesterStart.plusWeeks((week - 1).toLong())
                .plusDays((course.dayOfWeek - 1).toLong())
            val date2 = semesterStart.plusWeeks((week - 1).toLong())
                .plusDays((course.dayOfWeek - 1).toLong())
            val date3 = semesterStart.plusWeeks((week - 1).toLong())
                .plusDays((course.dayOfWeek - 1).toLong())
        """.trimIndent()
        assertEquals(
            "探测器必须抓得出改前那一形（数到 3 处才算第 ② 层那句「0 处」不是空锚点）",
            3,
            occurrences(preFixThreeSites, ".plusDays((course.dayOfWeek - 1).toLong())"),
        )
        assertEquals(
            "探测器也必须抓得出写侧自己写比较式那一形（第 ④ 层那句「0 处」的对照物）",
            1,
            occurrences("fun normalize(c: Course): Course? { if (c.dayOfWeek !in 1..7) return null }", "dayOfWeek !in 1..7"),
        )
        // 朝窄：同一批针尖写在 KDoc 散文里，抹完注释必须 0 处
        val proseOnly = "/** 改前那一手是 .plusDays((course.dayOfWeek - 1).toLong())，见 KDoc 登记 */\n// if (c.dayOfWeek !in 1..7) return null"
        val strippedProse = withoutComments(proseOnly)
        assertEquals(
            "抹注释之后散文里那些形状必须 0 处，否则本守卫就是在把 KDoc 的字数当证据",
            0,
            occurrences(strippedProse, ".plusDays((course.dayOfWeek - 1).toLong())") + occurrences(strippedProse, "dayOfWeek !in 1..7"),
        )
        // 真盘面对照：main 里那些形状确实只活在注释里
        assertTrue(
            "main 的注释里必须仍登记着改前那一形（本卡与台账的取证句，第 ② 层抹的正是它），" +
                "否则第 ⑦ 层那两枚对照物就是空摆的",
            mainSourcesWithRaw().any { occurrences(it.second, ".plusDays((course.dayOfWeek - 1).toLong())") > 0 },
        )
    }

    // ─────────────── 小工具（抄自 reminder/TermWeekGateSingleSourceGuardTest） ───────────────

    private fun linesWith(code: String, needle: String): Int =
        code.lines().count { it.contains(needle) }

    private fun readMainSource(relative: String): String {
        val file = File(findMainJavaDir(), relative)
        assertTrue("找不到 $relative：文件挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText().replace("\r\n", "\n")
    }

    private fun mainSourcesWithRaw(): List<Pair<String, String>> = rawMainSources()

    private fun mainSourcesWithCode(): List<Pair<String, String>> = rawMainSources().map { it.first to withoutComments(it.second) }

    private fun rawMainSources(): List<Pair<String, String>> {
        val root = findMainJavaDir()
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("${root.path} 下一个 .kt 都没有，路径不对", files.isNotEmpty())
        return files.map {
            root.toPath().relativize(it.toPath()).toString().replace(File.separatorChar, '/') to it.readText().replace("\r\n", "\n")
        }
    }

    private fun mainCode(): String = mainSourcesWithCode().joinToString("\n") { it.second }

    /** 只抹注释、保留字符串字面量：main 与本文件的 KDoc 都逐字登记着改前那些形状 */
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

    /** 按花括号配平从签名起取整段（含签名） */
    private fun balancedBlock(source: String, signature: String): String {
        val at = source.indexOf(signature)
        check(at >= 0) { "找不到 $signature：改过名或挪过家就要回来重钉本守卫" }
        val open = source.indexOf('{', at)
        check(open >= at) { "$signature 之后找不到左括号" }
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(at, i + 1)
                }
            }
        }
        throw IllegalStateException("$signature 的花括号没配平")
    }

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
        const val KERNEL_FILE = "com/buaa/schedule/domain/schedule/WeekdayDateOffset.kt"
        const val COURSE_CONSTRAINTS_FILE = "com/buaa/schedule/domain/schedule/CourseConstraints.kt"
        const val OCCURRENCES_FILE = "com/buaa/schedule/domain/schedule/ScheduleOccurrences.kt"
        const val PROGRESS_FILE = "com/buaa/schedule/reminder/ClassProgressScheduler.kt"
        const val REMINDER_FILE = "com/buaa/schedule/reminder/ReminderScheduler.kt"
        const val ICS_FILE = "com/buaa/schedule/data/import/IcsParser.kt"
        const val HOME_FILE = "com/buaa/schedule/ui/home/HomeScreen.kt"
        const val WEEK_VIEW_FILE = "com/buaa/schedule/ui/home/WeekView.kt"
        const val LAUNCH_POLICY_FILE = "com/buaa/schedule/core/LaunchRequestPolicy.kt"

        /** 三处日期推导（本卡的白名单，一枚不多一枚不少） */
        val DERIVATION_FILES = listOf(OCCURRENCES_FILE, PROGRESS_FILE, REMINDER_FILE)

        const val KERNEL_CALL = "val dayTier = WeekdayDateOffset.dayOffsetOrReject(course.dayOfWeek)"
        const val OFFSET_USE = ".plusDays(dayTier.offsetDays)"
        const val REJECT_PROCEED = "if (dayTier !is WeekdayOffset.Accepted) continue"
        const val REJECT_NULL = "if (dayTier !is WeekdayOffset.Accepted) return null"
    }
}
