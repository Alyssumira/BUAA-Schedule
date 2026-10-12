package com.buaa.schedule.widget

import com.buaa.schedule.domain.widget.WidgetWeekBrowseRequestCode
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T329③：把「翻周热区的 requestCode 是怎么来的」这件事钉在**生产接线**上（台账 #577）。
 *
 * ## 族别与钉法点名（卡面 §4 的四种里选了哪几种）
 *
 * 主尺 = **读形状 + 抹注释后找锚点切函数体**（判据 ② 与 ①）。病灶不是某一行写错，而是
 * 「(实例, 方向) 这两枚事实有没有各自进入取码」：把 `laneOf(delta)` 删成 `+ 0`、或把
 * `appWidgetId * LANE_COUNT` 退成裸 `appWidgetId`，都能让逐字节字面量匹配"看着还在"（同一行、
 * 同一个基准），而单射当场没了 —— 只有把函数体切出来、分别要求"实例项被格数放大"与
 * "方向项由 delta 现推"两枚结构都在场，才拦得住那两种退化。
 * 辅钉三把，都在同一批文件里：**数出现次数**（调用点 delegate 一枚、另两族取法四枚/两枚，
 * 全是抹注释后的绝对枚数）、**抹注释找锚点**（`PendingIntent.getBroadcast(…)` 那一格括号配平
 * 切出来的块里只许递参数）、**反套套逻辑成对复算**（病灶行"代码里 0 处 / 含注释原文里恰好 1 处"
 * —— 取证那句留在 `applyWeekBrowse` 的 KDoc 里，删了它上面那个 0 就成了"注释被洗干净"的假绿）。
 *
 * ## 两侧的红臂（本守卫自己也得是活的）
 *
 * - **朝窄**（把改前那一形原样放回 `WidgetCommon.kt` 的调用点）⇒ 判据 ① 与 ③ 当场红：
 *   delegate 那枚读数 0 枚、病灶行代码里不再是 0 枚；
 * - **退化①**（把 `laneOf(delta)` 删回 `+ 0`）⇒ 判据 ② 红（方向项锚点读不到），
 *   表驱动 `WidgetWeekBrowseRequestCodeTest` 的「同实例两方向」格也红；
 * - **退化②**（整枚算术搬回 `WidgetCommon.kt` 内联）⇒ 判据 ①（体内不许出现 `+ appWidgetId`）
 *   与 ③（基准名在代码里必须 0 枚）一起红。
 *
 * 只做**源码核对**（JVM，无 Robolectric、无设备、不读钟）。输入面：本守卫读 `src/main/java`
 * 下的工作树文本，已由 `app/build.gradle.kts` 里 `guardReadWorkingTreeFiles` 第 ① 项整体声明成
 * `testDebugUnitTest` 的输入。⚠️ 未装机：本卡零 adb，「两枚热区并成一枚 PendingIntent 之后
 * extras 被覆写成哪一手」是 framework 行为推断，本守卫钉的是取码形状，不声称量到过投递。
 */
class WidgetWeekBrowseRequestCodeWiringGuardTest {

    private companion object {
        const val COMMON = "com/buaa/schedule/widget/WidgetCommon.kt"
        const val READER = "com/buaa/schedule/domain/widget/WidgetWeekBrowseRequestCode.kt"

        // ── 调用点：只许递参数 ──
        const val DELEGATE = "WidgetWeekBrowseRequestCode.requestCode(appWidgetId, delta)"
        const val GET_BROADCAST = "PendingIntent.getBroadcast("
        const val APPLY_HEAD = "private fun applyWeekBrowse("

        // ── 病灶行（改前那一形）：代码里 0 枚，含注释的取证原文里恰好 1 枚 ──
        const val OLD_INLINE =
            "WEEK_BROWSE_REQUEST_CODE_BASE + appWidgetId + (if (delta > 0) 1 else 0)"
        const val OLD_BASE_NAME = "WEEK_BROWSE_REQUEST_CODE_BASE"

        // ── 取码本体：单射形状的两枚结构项 ──
        const val REQUEST_HEAD = "fun requestCode(appWidgetId: Int, delta: Int): Int ="
        const val LANE_HEAD = "fun laneOf(delta: Int): Int ="
        const val ID_SCALED = "appWidgetId * LANE_COUNT"
        const val LANE_TERM = "laneOf(delta)"
        const val BARE_ID_TERM = "+ appWidgetId +"
        const val ZERO_LANE_TERM = "+ 0"
        const val SIGN_TEST = "delta > 0"
        const val BASE_DEF = "const val BASE = 200_000"
        const val LANE_COUNT_DEF = "const val LANE_COUNT = 2"
        const val PREV_LANE_DEF = "const val PREV_LANE = 0"
        const val NEXT_LANE_DEF = "const val NEXT_LANE = 1"
        const val PURITY_PROMISE = "不读钟、不读时区、不读 `Context`、不探桌面"

        // ── 另两族：取法与数值都不许被本卡搬走（抹注释后的绝对枚数） ──
        const val HEADER_OFFSET_DEF = "private const val HEADER_REQUEST_CODE_OFFSET = 100_000"
        const val HEADER_ARG = "appWidgetId + HEADER_REQUEST_CODE_OFFSET,"
        const val BARE_TEMPLATE_ARG =
            "                appWidgetId,\n                listTapIntent,"
        const val GET_ACTIVITY = "PendingIntent.getActivity("

        /** 纯 JVM 判据的口径：这些只许在调用点当参数递进来，读数文件里一枚都不许出现 */
        val FORBIDDEN_READS = listOf(
            "android.",
            "androidx.",
            "Context",
            "AppWidgetManager",
            "PendingIntent",
            "RemoteViews",
            "SystemClock",
            "elapsedRealtime",
            "currentTimeMillis",
            "LocalDate",
            "ZoneId",
            "TimeZone",
            "Instant",
            "Build.VERSION",
        )
    }

    // ─────────────── ① 调用点：只递参数，不再自己做算术 ───────────────

    @Test
    fun `翻周的 requestCode 只由那枚具名读数出码`() {
        val code = blankedSource(COMMON)

        assertEquals(
            "调用点必须恰好一枚 delegate「$DELEGATE」（实到 " + occurrences(code, DELEGATE) + "）。" +
                "0 枚 = 算术被搬回 WidgetCommon 内联（本卡点名的第二种退化）；" +
                "2 枚以上 = 两处各自出码，又是两把尺",
            1,
            occurrences(code, DELEGATE),
        )
        assertEquals(
            "读数文件名的出现次数在代码里必须恰好 2 枚（import 一枚 + delegate 一枚，实到 " +
                occurrences(code, "WidgetWeekBrowseRequestCode") + "）。多出来的那一枚就是第二处出码点",
            2,
            occurrences(code, "WidgetWeekBrowseRequestCode"),
        )
        assertEquals(
            "本文件里 PendingIntent.getBroadcast 恰好一枚（实到 " + occurrences(code, GET_BROADCAST) +
                "）：翻周热区是 widget 目录里唯一带实例算术的广播 PI，另一族在 BackgroundSync 用固定码、" +
                "不在本卡边界内",
            1,
            occurrences(code, GET_BROADCAST),
        )

        val block = balancedCall(code, GET_BROADCAST, "翻周热区那枚广播 PendingIntent")
        assertEquals("getBroadcast 那一格里必须正好一次 delegate", 1, occurrences(block, DELEGATE))
        assertEquals(
            "getBroadcast 那一格里不许出现裸 id 加法「$BARE_ID_TERM 的前半」（实到 " +
                occurrences(block, "+ appWidgetId") + " 枚）：内联算术回来了就是本卡第二种退化",
            0,
            occurrences(block, "+ appWidgetId"),
        )
        assertEquals(
            "getBroadcast 那一格里不许再自己判方向「if (delta > 0)」（实到 " +
                occurrences(block, "if (delta > 0)") + " 枚）：方向档只许住在读数里",
            0,
            occurrences(block, "if (delta > 0)"),
        )

        val body = balancedBlock(code, APPLY_HEAD, "applyWeekBrowse")
        assertEquals("翻周热区的出码只在 applyWeekBrowse 体内发生一次", 1, occurrences(body, DELEGATE))
        assertEquals(
            "applyWeekBrowse 体内不许出现病灶行「$OLD_BASE_NAME」（实到 " +
                occurrences(body, OLD_BASE_NAME) + " 枚）",
            0,
            occurrences(body, OLD_BASE_NAME),
        )
    }

    // ─────────────── ② 取码本体：(实例, 方向) 的单射形状 ───────────────

    @Test
    fun `取码本体两枚结构项都在场 实例项被格数放大 方向项由 delta 现推`() {
        val code = blankedSource(READER)

        val request = sliceToNextMember(code, REQUEST_HEAD, "requestCode")
        assertEquals(
            "实例项「$ID_SCALED」在 requestCode 体内必须恰好一枚（实到 " + occurrences(request, ID_SCALED) +
                "）。0 枚就是退成裸 `appWidgetId`：next(k) 与 prev(k+1) 又算出同一个数 —— " +
                "`200_000 + k + 1 == 200_000 + (k+1) + 0` 这一手不需要任何运行期前提",
            1,
            occurrences(request, ID_SCALED),
        )
        assertEquals(
            "方向项「$LANE_TERM」在 requestCode 体内必须恰好一枚（实到 " + occurrences(request, LANE_TERM) +
                "）。0 枚就是 `(if (delta > 0) 1 else 0)` 被删回 `+ 0` 那一档：同一枚实例的两格热区" +
                "并成一枚 PendingIntent",
            1,
            occurrences(request, LANE_TERM),
        )
        assertEquals(
            "requestCode 体内不许出现裸 id 加法「$BARE_ID_TERM」（实到 " + occurrences(request, BARE_ID_TERM) +
                " 枚）—— 那正是改前的形状",
            0,
            occurrences(request, BARE_ID_TERM),
        )
        assertEquals(
            "requestCode 体内不许把方向项写成常量「$ZERO_LANE_TERM」（实到 " + occurrences(request, ZERO_LANE_TERM) +
                " 枚）：方向只许走 laneOf 现推",
            0,
            occurrences(request, ZERO_LANE_TERM),
        )

        val lane = sliceToNextMember(code, LANE_HEAD, "laneOf")
        assertEquals("方向判据「$SIGN_TEST」在 laneOf 体内恰好一枚（实到 " + occurrences(lane, SIGN_TEST) + "）",
            1, occurrences(lane, SIGN_TEST))
        assertEquals("下周那一档要在 laneOf 里出场一次", 1, occurrences(lane, "NEXT_LANE"))
        assertEquals("上周那一档要在 laneOf 里出场一次", 1, occurrences(lane, "PREV_LANE"))

        for (definition in listOf(BASE_DEF, LANE_COUNT_DEF, PREV_LANE_DEF, NEXT_LANE_DEF)) {
            assertEquals(
                "读数文件的常数定义「$definition」必须恰好一枚（实到 " + occurrences(code, definition) +
                    "）。改数值不是本卡口径：起点仍在 200_000、header 与列表两族一字未动",
                1,
                occurrences(code, definition),
            )
        }
    }

    // ─────────────── ③ 反套套逻辑：病灶行代码 0 枚 / 取证原文 1 枚 ───────────────

    @Test
    fun `改前那一形只在取证注释里活着`() {
        val raw = source(COMMON)
        val code = blankComments(normalizeNewlines(raw))

        assertEquals(
            "病灶行「$OLD_INLINE」在 WidgetCommon 的代码里必须 **0** 枚（实到 " +
                occurrences(code, OLD_INLINE) + "）：它让 next(k) 与 prev(k+1) 同码",
            0,
            occurrences(code, OLD_INLINE),
        )
        assertEquals(
            "含注释的原文里那一句必须恰好 **1** 枚（applyWeekBrowse 的 KDoc 逐字留着做取证，实到 " +
                occurrences(raw, OLD_INLINE) + "）。读到 0 = 取证被删干净，上面那个 0 就成了假绿；" +
                "读到 2 = 病灶回潮",
            1,
            occurrences(raw, OLD_INLINE),
        )
        assertEquals(
            "旧基准名「$OLD_BASE_NAME」在代码里 0 枚（实到 " + occurrences(code, OLD_BASE_NAME) +
                "）、含注释恰好 1 枚（实到 " + occurrences(raw, OLD_BASE_NAME) + "）",
            listOf(0, 1),
            listOf(occurrences(code, OLD_BASE_NAME), occurrences(raw, OLD_BASE_NAME)),
        )
        assertEquals(
            "基准数字 200_000 不许留在调用点（实到 " + occurrences(code, "200_000") + " 枚）：取码搬家后" +
                "整族起点只许活在那枚读数里",
            0,
            occurrences(code, "200_000"),
        )
        val readerRaw = source(READER)
        assertEquals(
            "读数文件里也不许出现那枚内联算式（代码 0 / 原文 0，实到 " +
                occurrences(blankComments(normalizeNewlines(readerRaw)), OLD_INLINE) + " 与 " +
                occurrences(readerRaw, OLD_INLINE) + "）：取证只留在 WidgetCommon 一处，两处都留就是双记账",
            listOf(0, 0),
            listOf(
                occurrences(blankComments(normalizeNewlines(readerRaw)), OLD_INLINE),
                occurrences(readerRaw, OLD_INLINE),
            ),
        )
    }

    // ─────────────── ④ 另两族的取法与数值一字未动 ───────────────

    @Test
    fun `header 与列表两族的取法与数值本卡一字未动`() {
        val code = blankedSource(COMMON)

        assertEquals(
            "header 偏移的定义那一行逐字节一枚在场「$HEADER_OFFSET_DEF」（实到 " +
                occurrences(code, HEADER_OFFSET_DEF) + "）：本卡不许改这两族的数值",
            1,
            occurrences(code, HEADER_OFFSET_DEF),
        )
        assertEquals(
            "header 一族「$HEADER_ARG」必须恰好 4 枚（实到 " + occurrences(code, HEADER_ARG) +
                "）：列表渲染、下一节课、今明两栏、周网格四处",
            4,
            occurrences(code, HEADER_ARG),
        )
        assertEquals(
            "列表/网格模板的裸 id 取法必须恰好 2 枚（实到 " + occurrences(code, BARE_TEMPLATE_ARG) +
                "）：两枚 setPendingIntentTemplate 各一枚。把这两族也搬进出码函数是**改数值**的路径，" +
                "本卡明令不走",
            2,
            occurrences(code, BARE_TEMPLATE_ARG),
        )
        assertEquals(
            "本文件的 PendingIntent.getActivity 恰好 6 枚（实到 " + occurrences(code, GET_ACTIVITY) +
                "）：两族模板 2 枚 + header 4 枚，一枚都不许多出或搬走",
            6,
            occurrences(code, GET_ACTIVITY),
        )
    }

    // ─────────────── ⑤ 纯度口径：那枚读数不许自己去摸设备 ───────────────

    @Test
    fun `取码读数是纯 JVM 判据 不读钟不读区不摸 Context`() {
        val raw = source(READER)
        val offenders = raw.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("import ") }
            .filter { it.contains("android.") || it.contains("androidx.") }
            .toList()
        assertEquals("$READER 必须零 android / androidx import。实到：" + offenders, emptyList<String>(), offenders)

        val code = blankComments(normalizeNewlines(raw))
        for (needle in FORBIDDEN_READS) {
            assertEquals(
                "读数文件里「$needle」必须 0 处：摸了它，`(appWidgetId, delta) → requestCode` 就不再是" +
                    "纯函数 —— 同一枚 (实例, 方向) 两次渲染可能给出两个码，本卡那本单射账直接作废。" +
                    "\n  复算：把 $READER 的注释抹成空格后数「$needle」\n  实到枚数：" + occurrences(code, needle),
                0,
                occurrences(code, needle),
            )
        }
        assertEquals(
            "KDoc 里那句纯度承诺必须留痕（含注释原文恰好 1 枚，实到 " + occurrences(raw, PURITY_PROMISE) +
                "）：上面那些 0 没人负责就是套套逻辑",
            1,
            occurrences(raw, PURITY_PROMISE),
        )
    }

    // ─────────────── ⑥ 运行时反证：单射在那枚真读数上确实成立 ───────────────

    @Test
    fun `运行时那一侧也认得单射 题眼那一对不同码`() {
        val ids = listOf(0, 1, 2, 7, 42, 999, 100_000, -3)
        val sameId = ids.filter { id ->
            WidgetWeekBrowseRequestCode.requestCode(id, -1) == WidgetWeekBrowseRequestCode.requestCode(id, 1)
        }
        assertEquals("同一枚实例的两个方向不许同码。撞上的 id：" + sameId, emptyList<Int>(), sameId)

        val adjacent = ids.filter { id ->
            WidgetWeekBrowseRequestCode.requestCode(id, 1) == WidgetWeekBrowseRequestCode.requestCode(id + 1, -1)
        }
        assertEquals(
            "next(k) 与 prev(k+1) 不许同码（改前那一形对每个 k 都同码）。撞上的 k：" + adjacent,
            emptyList<Int>(),
            adjacent,
        )
        assertEquals(
            "题眼那两枚的手写值：next(1) = 200_003、prev(2) = 200_004（改前两者都是 200_002）",
            listOf(200_003, 200_004),
            listOf(
                WidgetWeekBrowseRequestCode.requestCode(1, 1),
                WidgetWeekBrowseRequestCode.requestCode(2, -1),
            ),
        )
        assertTrue(
            "格数必须还是 2（一枚实例两格），起点还是 200_000：实到 " +
                WidgetWeekBrowseRequestCode.LANE_COUNT + " 与 " + WidgetWeekBrowseRequestCode.BASE,
            WidgetWeekBrowseRequestCode.LANE_COUNT == 2 && WidgetWeekBrowseRequestCode.BASE == 200_000,
        )
    }

    // ---------------- helpers（与 ScanGiveUpReasonDerivationGuardTest 同写法：不引入族内助手） ----------------

    /** 抹过注释、换行归一之后的主源码文本（数的是代码，不是散文） */
    private fun blankedSource(relative: String): String = blankComments(normalizeNewlines(source(relative)))

    private fun source(relative: String): String = File(findMainJavaDir(), relative).readText()

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

    /** 从锚点起做**圆括号**配平，交回那次调用的整段（含锚点）；锚点找不到就抛，静默跳过等于没有守卫 */
    private fun balancedCall(code: String, anchor: String, label: String): String {
        val at = code.indexOf(anchor)
        check(at >= 0) { "$label 的锚点没找到（写法换过了）：「$anchor」" }
        var depth = 0
        for (index in at until code.length) {
            when (code[index]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return code.substring(at, index + 1)
                }
            }
        }
        throw IllegalStateException("$label 的圆括号没配平：「$anchor」")
    }

    /** 从签名起做**花括号**配平取整个函数体（含签名）；锚点找不到就抛 */
    private fun balancedBlock(code: String, anchor: String, label: String): String {
        val at = code.indexOf(anchor)
        check(at >= 0) { "$label 的锚点没找到（改名/挪家要回来重钉本守卫）：「$anchor」" }
        val open = code.indexOf('{', at)
        check(open >= at) { "$label 之后找不到左括号" }
        var depth = 0
        for (index in open until code.length) {
            when (code[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return code.substring(at, index + 1)
                }
            }
        }
        throw IllegalStateException("$label 的花括号没配平")
    }

    /**
     * 表达式函数体：从签名起切到**下一枚成员定义或本文件的收尾**为止。
     * 切出来的那一段里说话，才不会被隔壁函数的锚点冒充成"这格吃到了"。
     */
    private fun sliceToNextMember(code: String, anchor: String, label: String): String {
        val at = code.indexOf(anchor)
        check(at >= 0) { "$label 的锚点没找到（签名换了写法要回来重钉）：「$anchor」" }
        val nextFun = code.indexOf("\n    fun ", at + anchor.length)
        val endOfObject = code.indexOf("\n}", at + anchor.length)
        val stops = listOf(nextFun, endOfObject).filter { it >= 0 }
        check(stops.isNotEmpty()) { "$label 的函数体切不到收尾（文件形状变了）：「$anchor」" }
        return code.substring(at, stops.reduce { left, right -> if (left < right) left else right })
    }

    /** 块注释整段抹成一枚空格、行注释截到行尾：判的是代码，散文顶不动这把尺 */
    private fun blankComments(source: String): String {
        val out = StringBuilder(source)
        var block = out.indexOf("/*")
        while (block >= 0) {
            val end = out.indexOf("*/", block + 2)
            if (end < 0) break
            out.replace(block, end + 2, " ")
            block = out.indexOf("/*")
        }
        val text = out.toString()
        return text.lines().joinToString("\n") { line ->
            val slash = line.indexOf("//")
            if (slash >= 0) line.substring(0, slash) else line
        }
    }

    /** 工作树是 CRLF（core.autocrlf=true），针里的换行统一按 LF 写 */
    private fun normalizeNewlines(text: String): String = text.replace("\r\n", "\n")

    /** 单测的 cwd 是 :app 模块目录，也可能是仓库根：两种布局都试，全落空就抛 */
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
}
