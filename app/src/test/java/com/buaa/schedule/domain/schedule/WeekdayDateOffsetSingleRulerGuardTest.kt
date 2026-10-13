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
 * 7. [probeRecognisesThePreFixShape] —— **抹注释找锚点 + 读形状**：改前那三形喂进同一把尺各数到该数的值，
 *    而写在 KDoc 散文里的那些引用抹完必须 0 处（防"锚点拼错的假绿"，也防"把散文当证据"）；
 *    并自检第 ⑧ 层那两把探测器（声明尺认三形且不认 `private fun`、分词尺不许从长名里切出短名）；
 * 8. [theMainSourceZeroCallRosterIsClosedAndNamed] —— **数出现次数（domain 全册）+ 按文件 + 逐字节**：
 *    把「主源零调用」那批死件钉成一本**闭合名册**（台账 #616 要的正是枚数），逐枚点名唯一读者。
 *    本层只登记、不删件；同名重载里死掉的那一枚名字级尺看不见，改按签名行与调用点字面逐字节钉；
 * 9. [theKernelPointersNameTheRightGuardCellAndRightLine] —— **逐字节读 raw KDoc**：本格钉的**对象就是那句
 *    散文本身**（注释把读者指到错的守卫格子／错的针尖行，台账 #615）。第 ⑦ 层禁的是"拿散文当代码
 *    形状的证据"，这里钉的是"散文自己说了哪几个字"，两件事不冲突，所以这一层**故意不抹注释**。
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

        // ── 第 ⑧ 层那两把探测器的自检（本卡实测出的一枚坑，落进尺里）──
        // 声明正则少闭一枚括号 ⇒ 伴生对象初始化抛 PatternSyntaxException，本文件九格全红、
        // 而红点不在任何一枚断言上（ExceptionInInitializerError / NoClassDefFoundError）。
        // 名册尺要是探测器坏了却没人查，「实到」就能悄悄变成空集，整本名册跟着空钉。
        assertEquals(
            "**钉法 = 读形状**：声明尺必须认得裸 `fun`／带修饰符的 `internal suspend fun`／扩展形 `fun Foo.bar(` " +
                "三形并交出函数名（名册靠它才只登记非 private 声明）",
            listOf("dayStatusOf", "owesAMessage", "today"),
            listOf(
                FUN_DECLARATION.find("fun dayStatusOf(day: LocalDate): String?")?.groupValues?.get(1),
                FUN_DECLARATION.find("internal suspend fun owesAMessage(mins: Int)")?.groupValues?.get(1),
                FUN_DECLARATION.find("fun WeekCalculator.today(): Int")?.groupValues?.get(1),
            ),
        )
        assertFalse(
            "**钉法 = 读形状**：`private fun` 不许被声明尺认进这本名册 —— 名册口径写的是「非 private」，" +
                "它要是匹配上了，一枚没人调的私有助手就会被当成死件登记，尺就不再是它自述的那一把",
            FUN_DECLARATION.containsMatchIn("private fun hidden(): Int = 1"),
        )
        assertFalse(
            "**钉法 = 读形状**：分词尺不许从 `isCurrentWeekMatchesCurrentWeek` 里切出 `isCurrentWeek` —— " +
                "第 ⑧ 层那本名册是名字级的，子串尺会把这种测试方法名顶成「有读者」，把死件读成活件",
            lineIdentifiers("assertTrue(isCurrentWeekMatchesCurrentWeek(week))").contains("isCurrentWeek"),
        )
    }

    // ─────────────── ⑧ 死件名册：domain 里「主源零调用」的函数（#616；本卡第 ⓪ 问裁的甲档 = 登记，不删） ───────────────

    @Test
    fun theMainSourceZeroCallRosterIsClosedAndNamed() {
        val computed = domainFunctionsWithNoMainSourceCall()

        assertEquals(
            "**钉法 = 数出现次数（domain 全册）+ 读形状**：`app/src/main/java/com/buaa/schedule/domain` 下面，" +
                "「main 侧除了自己那一枚声明行之外零调用」的非 private `fun`，必须与名册逐枚相等。" +
                "尺 = 抹注释后按标识符分词（子串尺会把 `isCurrentWeekMatchesCurrentWeek` 那种测试方法名也算进来），" +
                "声明行自己不算调用。多一枚 = 主源里又长出一枚没人调的函数；少一枚 = 有人把名册里某一枚接上了生产、" +
                "或整枚删了。两种都要回来重钉这本名册 —— 本卡不许它悄悄复活，也不许悄悄变死。" +
                "\n名册：" + MAIN_SOURCE_ZERO_CALL_FUNS.sorted() +
                "\n实到：" + computed.sorted(),
            MAIN_SOURCE_ZERO_CALL_FUNS.toSet(),
            computed,
        )

        // 名册里每一枚的唯一读者逐枚点名：测试那一格被删掉，本层当场红（#616 说的就是
        // 「测试覆盖着一个生产里不存在的形状」，这句承诺得有尺钉着才不算散文）
        val readersBySymbol = identifierFilesBySymbol(testCodeSources(), SELF_GUARD_FILE)
        for ((symbol, expectedReaders) in SOLE_READERS_OF_THE_DEAD) {
            assertEquals(
                "**钉法 = 数出现次数（按文件）**：名册里「$symbol」的读者册必须恰是 $expectedReaders。" +
                    "本守卫自己把符号名写进了名册串，它不算读者（已按文件名排除 —— 这是名册尺的第二枚坑：" +
                    "钉名册的那格文件自己会把自己读成第二枚读者）。实到：" + (readersBySymbol[symbol] ?: emptySet<String>()),
                expectedReaders,
                readersBySymbol[symbol] ?: emptySet<String>(),
            )
        }

        // ── 名字级尺看不见的那一手：同名重载里死掉的那一枚（在册 #616 的第 5 枚就住在这里）──
        val periodWindows = withoutComments(readMainSource(PERIOD_WINDOWS_FILE))
        val todayPlanner = withoutComments(readMainSource(TODAY_PLANNER_FILE))
        assertEquals(
            "**钉法 = 数出现次数（按文件）**：`periodWindowsOf` 三枚重载都在，main 出现册子 = 本文件 6 枚" +
                "（3 枚声明 + 3 处内部自调）+ TodayPlanner 1 枚 + 闹钟链 2 枚（1 枚 import + 1 处调用）。" +
                "多一枚 = 有人给吃 `List<TimeSlot>` 的那枚重载接上了生产调用；少一枚 = 有一枚重载被删。" +
                "⚠️ 这一手看不见「把某一处调用从 Map 重载挪到 List<TimeSlot> 重载」那一形（挪动不改枚数），" +
                "要看那一形得做参数类型解析，不在文本尺射程内",
            mapOf(PERIOD_WINDOWS_FILE to 6, TODAY_PLANNER_FILE to 1, PROGRESS_FILE to 2),
            mainLineCountBySymbol("periodWindowsOf"),
        )
        assertEquals(
            "**钉法 = 逐字节**：主源零调用那一枚重载（`periodWindowsOf` 吃 `List<TimeSlot>` 的那一形）的签名行必须还在 —— " +
                "名册钉的是「它活着而 main 没人调」，不许悄悄删掉让那句口径失去宿主",
            1,
            occurrences(periodWindows, "    timeSlots: List<TimeSlot>,"),
        )
        assertEquals(
            "**钉法 = 数出现次数（按文件）+ 逐字节**：`minutesUntil` 在 main = 两枚声明 + 两处调用，" +
                "两处调用都在 TodayPlanner 且吃的是 `LocalTime` 那一形 ⇒ `LocalDateTime` 那枚重载 main 零调用。" +
                "调用点换成 LocalDateTime、或摘掉任一枚重载，这本册子就会动",
            mapOf(PERIOD_WINDOWS_FILE to 2, TODAY_PLANNER_FILE to 2),
            mainLineCountBySymbol("minutesUntil"),
        )
        assertEquals(
            "**钉法 = 逐字节**：`minutesUntil(from: LocalDateTime, to: LocalDateTime)` 那枚签名必须恰一处",
            1,
            occurrences(periodWindows, "fun minutesUntil(from: LocalDateTime, to: LocalDateTime): Long ="),
        )
        for (needle in listOf("minutesUntil(now, it.start)", "minutesUntil(now, it.end)")) {
            assertEquals(
                "**钉法 = 逐字节**：TodayPlanner 那一手「$needle」必须恰一处（判定 LocalDateTime 重载死在主源的参照物）",
                1,
                occurrences(todayPlanner, needle),
            )
        }
        assertEquals(
            "**钉法 = 逐字节**：那两处调用的接收者 `now` 必须仍声明成 `now: LocalTime,` —— 它是「这两手落在 " +
                "LocalTime 重载上」的唯一凭据；把它换成 LocalDateTime 就等于把 :173 那枚重载接回生产，本层要红",
            1,
            occurrences(todayPlanner, "now: LocalTime,"),
        )

        // ── 「死宿主里的口径声明」那一枚：LEGAL_DAY_RANGE（#615 的第二处）──
        assertEquals(
            "**钉法 = 数出现次数（全 main 册子）**：`LEGAL_DAY_RANGE` 在 main（抹注释后）只剩自己那一行声明 —— " +
                "写侧 normalize 与读侧三处推导都不读它。谁把它接上生产或删掉，都要回来改这一格与同文件那句 KDoc",
            1,
            occurrences(mainCode(), "LEGAL_DAY_RANGE"),
        )
        assertEquals(
            "**钉法 = 数出现次数（按文件）**：`LEGAL_DAY_RANGE` 的读者册必须恰是 WeekdayDateOffsetTest 一枚文件",
            setOf(WEEKDAY_DATE_OFFSET_TEST_FILE),
            identifierFilesBySymbol(testCodeSources(), SELF_GUARD_FILE)["LEGAL_DAY_RANGE"] ?: emptySet<String>(),
        )
    }

    // ─────────────── ⑨ 指路自检：注释不许把读者送到错的守卫格子／错的针尖行 ───────────────

    @Test
    fun theKernelPointersNameTheRightGuardCellAndRightLine() {
        val raw = readMainSource(KERNEL_FILE) // 这一格**故意不抹注释**：钉的对象就是那句散文本身
        val code = withoutComments(raw)

        assertEquals(
            "**钉法 = 逐字节读 raw KDoc**：内核那段指路句必须把「上界只有一个出处」这一判送到**第 ⑥ 层**" +
                "（`theUpperBoundHasASingleSource`）那一格，且恰一句",
            1,
            occurrences(raw, "第 ⑥ 层按"),
        )
        assertEquals(
            "**钉法 = 逐字节读 raw KDoc**：「第 ⑤ 层」必须 0 处 —— 第 ⑤ 层钉的是「纯 JVM、不折成 Boolean」，" +
                "跟「上界只有一个出处」不相干；W 臂把这句改回去红的就是这一手",
            0,
            occurrences(raw, "第 ⑤ 层"),
        )
        assertEquals(
            "**钉法 = 逐字节读 raw KDoc**：指路句钉的针尖必须是 `const val LAST_DAY_INDEX: Int = " +
                "SemesterStats.TOTAL_DAYS` 那一行；raw 里两处（声明本体 + 指路句逐字引用它）",
            2,
            occurrences(raw, SINGLE_UPPER_BOUND_LINE),
        )
        assertEquals(
            "**钉法 = 逐字节**：同一行针尖抹掉注释后在 main 仍恰一处 —— 这正是第 ⑥ 层数的那枚，两把尺对得上",
            1,
            occurrences(mainCode(), SINGLE_UPPER_BOUND_LINE),
        )
        assertEquals(
            "**钉法 = 逐字节读 raw KDoc**：不许再把那枚针尖说成 `[LEGAL_DAY_RANGE] 那一行` —— 守卫全文没有出现" +
                "过这个符号（第 ⑥ 层数的是 LAST_DAY_INDEX 那一行）",
            0,
            occurrences(raw, "[LEGAL_DAY_RANGE] 那一行"),
        )
        assertEquals(
            "**钉法 = 逐字节读 raw KDoc**：「共用的那把尺」那半句必须 0 处 —— 它替 main 侧承诺「写侧与读侧三处" +
                "都用这一枚区间」，而 main 一处都不读它（同层上面那枚枚数尺就是反证）",
            0,
            occurrences(raw, "共用的那把尺"),
        )
        assertEquals(
            "**钉法 = 逐字节读 raw KDoc**：越上界那一档的注释里不许再写裸 `> 7` —— 同文件 LAST_DAY_INDEX 的 KDoc " +
                "自己写着「不是再写一遍 7」，散文里钉第二枚 7 就是同一判据的第二个出处",
            0,
            occurrences(raw, "> 7"),
        )
        assertEquals(
            "**钉法 = 逐字节读 raw KDoc**：「第 ①② 层按 dayOffsetOrReject( 数调用点」那半句仍然成立，" +
                "两处调用点册子（① 逐字节整行、② 全 main 枚数）都在这本守卫里",
            1,
            occurrences(raw, "第 ①② 层"),
        )
        assertEquals(
            "**钉法 = 数出现次数**：内核体内（抹注释）拿数字当上界那一族照旧 0 处 —— 本格动了两句注释，" +
                "不许顺手把代码里的那把尺挪走（与第 ⑥ 层同口径，这里再数一遍，防「改注释时误伤代码」）",
            0,
            occurrences(
                balancedBlock(code, "object WeekdayDateOffset {"),
                "> 7",
            ) + occurrences(balancedBlock(code, "object WeekdayDateOffset {"), "in 1..7"),
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

    // ── 第 ⑧⑨ 层用的那两把尺：按标识符分词（不是子串），声明行自己不算调用 ──

    private fun rawTestSources(): List<Pair<String, String>> {
        val root = findTestJavaDir()
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("${root.path} 下一个 .kt 都没有，路径不对", files.isNotEmpty())
        return files.map {
            root.toPath().relativize(it.toPath()).toString().replace(File.separatorChar, '/') to it.readText().replace("\r\n", "\n")
        }
    }

    private fun testCodeSources(): List<Pair<String, String>> = rawTestSources().map { it.first to withoutComments(it.second) }

    private fun domainCodeSources(): List<Pair<String, String>> = mainSourcesWithCode().filter { it.first.startsWith(DOMAIN_PACKAGE_DIR) }

    private fun lineIdentifiers(line: String): Set<String> = IDENTIFIER_TOKEN.findAll(line).map { it.value }.toSet()

    /** domain 里非 private 的 `fun` 声明：name -> 它自己那些「文件:行」 */
    private fun domainFunctionDeclarations(): Map<String, Set<String>> {
        val declarations = HashMap<String, MutableSet<String>>()
        for ((file, code) in domainCodeSources()) {
            code.lines().forEachIndexed { index, line ->
                val match = FUN_DECLARATION.find(line.trim()) ?: return@forEachIndexed
                declarations.getOrPut(match.groupValues[1]) { HashSet() }.add("$file:${index + 1}")
            }
        }
        return declarations
    }

    /**
     * domain 里「main 侧除自己那枚声明行之外零调用」的函数名。
     * ⚠️ 名字级尺：同名重载里死掉的那一枚在这里看不见（另一枚同名的活着就把整族算成活的），
     *    所以第 ⑧ 层除了这本册子，还逐字节钉着那两枚重载的签名行与调用点。
     */
    private fun domainFunctionsWithNoMainSourceCall(): Set<String> {
        val declarations = domainFunctionDeclarations()
        val sites = HashMap<String, MutableSet<String>>()
        for ((file, code) in mainSourcesWithCode()) {
            code.lines().forEachIndexed { index, line ->
                for (token in lineIdentifiers(line)) sites.getOrPut(token) { HashSet() }.add("$file:${index + 1}")
            }
        }
        return declarations.mapNotNull { (name, ownDeclarationSites) ->
            val usedElsewhere = (sites[name] ?: emptySet()).filterNot { it in ownDeclarationSites }
            if (usedElsewhere.isEmpty()) name else null
        }.toSet()
    }

    /** symbol -> 出现过它的那些文件（可按文件名排除某一枚，比如本守卫自己：它把符号名写进了名册串） */
    private fun identifierFilesBySymbol(entries: List<Pair<String, String>>, excludeFile: String): Map<String, Set<String>> {
        val out = HashMap<String, MutableSet<String>>()
        for ((file, code) in entries) {
            if (file.endsWith(excludeFile.substringAfterLast('/'))) continue
            code.lines().forEach { line ->
                for (token in lineIdentifiers(line)) out.getOrPut(token) { HashSet() }.add(file)
            }
        }
        return out
    }

    /** main 里某一枚标识符按文件数的「出现过它的那些行」枚数 */
    private fun mainLineCountBySymbol(symbol: String): Map<String, Int> =
        mainSourcesWithCode()
            .map { it.first to it.second.lines().count { line -> lineIdentifiers(line).contains(symbol) } }
            .filter { it.second > 0 }
            .toMap()

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

    private fun findTestJavaDir(): File {
        var dir: File? = File("").absoluteFile
        repeat(6) {
            val hit = listOf("src/test/java", "app/src/test/java").map { File(dir, it) }.firstOrNull { it.isDirectory }
            if (hit != null) return hit
            dir = dir?.parentFile
        }
        throw IllegalStateException("找不到 app/src/test/java：当前目录 ${File("").absolutePath}")
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

        // ── 第 ⑧⑨ 层：死件名册与指路自检 ──

        /** 名册尺的扫描面：domain 那一目录（台账 #616 的口径就是「domain 里主源零调用的函数」） */
        const val DOMAIN_PACKAGE_DIR = "com/buaa/schedule/domain/"

        const val PERIOD_WINDOWS_FILE = "com/buaa/schedule/domain/schedule/PeriodWindows.kt"
        const val TODAY_PLANNER_FILE = "com/buaa/schedule/domain/schedule/TodayPlanner.kt"

        /** 第 ⑥ 层逐字节钉的那一行针尖（第 ⑨ 层要确认指路句点的正是它） */
        const val SINGLE_UPPER_BOUND_LINE = "const val LAST_DAY_INDEX: Int = SemesterStats.TOTAL_DAYS"

        const val SELF_GUARD_FILE = "com/buaa/schedule/domain/schedule/WeekdayDateOffsetSingleRulerGuardTest.kt"
        const val WEEKDAY_DATE_OFFSET_TEST_FILE = "com/buaa/schedule/domain/schedule/WeekdayDateOffsetTest.kt"
        const val PERIOD_WINDOWS_TEST_FILE = "com/buaa/schedule/domain/schedule/PeriodWindowsTest.kt"
        const val WEEK_CALCULATOR_EDGE_TEST_FILE = "com/buaa/schedule/domain/schedule/WeekCalculatorEdgeTest.kt"
        const val EDITOR_MISSING_COURSE_EXIT_TEST_FILE = "com/buaa/schedule/domain/editor/EditorMissingCourseExitTest.kt"
        const val FETCH_JOB_CARRIER_POLICY_TEST_FILE = "com/buaa/schedule/domain/importing/FetchJobCarrierPolicyTest.kt"

        /**
         * 名册（基点 `239ef4d` 现量，本卡第 ⓪ 问复算逐枚点开过）：domain 里「main 侧除声明外零调用」的函数。
         * 在册 #616 说「5 枚不是 2 枚」——那 5 枚是 `PeriodWindows` ＋ `WeekCalculator` 那一族（含一枚
         * 同名重载里死掉的，住不进这本名字级册子，由下面逐字节那两手钉着）；本卡把尺伸到整个 domain 目录，
         * 另 4 枚同族的也一并收进来。删件不是本卡的选项（甲档），只登记与点名。
         */
        val MAIN_SOURCE_ZERO_CALL_FUNS = listOf(
            "dayStatusOf", // PeriodWindows 裸 :145
            "epochMillisToLocalDateTime", // PeriodWindows 裸 :204
            "isCurrentWeek", // WeekCalculator 裸 :48
            "isInFlightFact", // FetchJobCarrierPolicy 裸 :113
            "owesAMessage", // EditorMissingCourseExit 裸 :98
            "popsBackStack", // EditorMissingCourseExit 裸 :93
            "shareCodeStreamStoppedCopy", // ShareCodeSizeGate 裸 :160 —— main 与 test 两侧都零读者
        )

        /** 名册里每一枚的唯一读者（空集 = 全仓除定义外没人读它） */
        val SOLE_READERS_OF_THE_DEAD = mapOf(
            "dayStatusOf" to setOf(PERIOD_WINDOWS_TEST_FILE),
            "epochMillisToLocalDateTime" to setOf(PERIOD_WINDOWS_TEST_FILE),
            "isCurrentWeek" to setOf(WEEK_CALCULATOR_EDGE_TEST_FILE),
            "isInFlightFact" to setOf(FETCH_JOB_CARRIER_POLICY_TEST_FILE),
            "owesAMessage" to setOf(EDITOR_MISSING_COURSE_EXIT_TEST_FILE),
            "popsBackStack" to setOf(EDITOR_MISSING_COURSE_EXIT_TEST_FILE),
            "shareCodeStreamStoppedCopy" to emptySet<String>(),
        )

        val IDENTIFIER_TOKEN = Regex("[A-Za-z_][A-Za-z0-9_]*")

        /** `private fun` 天生不进册（修饰符不在这一串里），所以下面不必再单独排除它 */
        val FUN_DECLARATION = Regex(
            "^(?:public |internal |open |override |abstract |suspend |inline |final |infix |operator |external )" +
                "*fun (?:[A-Za-z_][A-Za-z0-9_]*\\.)?([A-Za-z_][A-Za-z0-9_]*)\\(",
        )
    }
}
