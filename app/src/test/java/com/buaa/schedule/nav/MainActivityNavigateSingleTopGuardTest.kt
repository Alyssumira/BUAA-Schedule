package com.buaa.schedule.nav

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MainActivity 里每一枚 `navigate(` 调用点的**单顶册子**（T344②，在册候选 T333-01）。
 *
 * ## 病灶
 * 用户快速双击首页任一课程卡／入口 ⇒ 同一 route 同 args 叠两枚 back-stack entry ⇒ 返回键要按两下
 * 才出得去。本卡之前盘上只有四枚调用式写了单顶（组件行点击那一路、通知落点那一路、「先清后跳」
 * 那一手、一级页助手自己），其余十四枚调用点全是裸 `navigate(route)`。
 * 带 `popUpTo("iclass_login") { inclusive = true }` 的那一枚最坏：登录连点会把扫码页叠两枚 ——
 * inclusive 的 popUpTo 只清得掉**还在栈上**的那枚登录页，清不掉第二枚扫码页。
 *
 * ## 为什么这里没有可抽的运行时判据（本仓那条「纯 JVM 内核 + 表驱动」规矩怎么落）
 * 本仓守卫的常规形状是把判据抽成一枚纯 JVM 函数、把时钟／权限当参数递进去。这一族抽不出那种内核：
 * 单顶是**调用点字面量**，没有任何运行期输入可以喂给它 —— 硬造一枚
 * `fun shouldBeSingleTop(route: String): Boolean` 就是把表抄第二遍还假装是判据。
 * 所以内核这一格换成**册子**：下面的 ROSTER 逐枚列「盘上第 N 枚调用式是谁、它的 route 原文是什么、
 * 它必须单顶」，对账对象是 MainActivity 的源码文本（抹注释之后）。表驱动仍然成立：每一格都是逐行走
 * 这张表，而不是给十八枚调用点写十八段散文断言。零 android import、不起 Robolectric。
 *
 * ## 第⓪问裁定（甲：逐处补单顶，不留例外枚）
 * 十八枚逐枚读过，没有一枚是**故意**要叠两枚的：
 * - editor 一族那两处 KDoc 说的是「按 entry 隔离删除旗标」，靠的是不同 courseId ⇒ 不同 args ⇒
 *   单顶本来就不合并（单顶只比栈顶那一枚与逐位相等的 args）；
 * - 同 courseId 连续两枚 entry 唯一组得出来的来路就是连点，那正是要修的形状；
 * - 盘上早有两枚在册的 editor 单顶先例（组件那一路的 `editor/$id`、引导落点的 `editor/-1`），
 *   同一枚 destination、同一条理由 ⇒ 补参数改的是「叠不叠」，不是语义。
 * 丙（抽一枚全仓统一的 safeNavigate 助手）判死：`grep -rn 'fun.*navigate' app/src/main` 全仓只有一枚
 * `navigateTopLevel`，而它是 `popUpTo(graph.findStartDestination().id){saveState}` + `restoreState` 的
 * 一级页语义（NavHost 只有 home/import/settings 三枚一级条目），detail 页套进去会把来路那页从栈上弹掉、
 * 返回键从此回首页而不是回列表 —— 改的是行为不是写法，而且盘上没有「默认单顶的裸 push」这枚抽象，
 * 新造一枚就是本仓没有的形状。乙（只补守卫不补参数）判死：册子一旦等于「现状＝十四枚缺」，
 * 这一族就永远没有红的一天。
 * ⇒ 十七枚补字面 options lambda（与在册三枚逐字同形），一级页助手那一枚体内本就有那一行。
 *
 * ## 钉法族 = 数出现次数（T344③ 的三格在同一个类里）
 * ③(a) 一枚**不读册子**的独立尺：抹注释后逐枚解析盘上每一处 `navigate(`，缺单顶就红，并把那一枚的
 *   行号与原文打进断言消息。③(b) 反向一格：册子长度必须等于真实调用点数（本仓把这叫「第二枚读者」——
 *   有人新增一枚 `navigate(` 而册子没看见它，这一格必须当场响，尺子不许只会数自己认识的东西）。
 *   ③(c) 写法尺：带单顶的调用式枚数与调用点枚数同源，且不许有反着写的 `launchSingleTop = false`。
 *
 * ## 配对口径与覆盖面边界（如实写）
 * - 配对按**源码顺序**逐枚对：第 i 行册子对第 i 枚解析出来的调用式。中间插一枚新调用点会让后面全部错位，
 *   于是同时红在「原文片段读不到」与 ③(b) 的枚数格 —— 错位的响声比漏检贵得多，这是刻意的。
 * - `(route)` 那三枚（通知落点、一次性落点、一级页助手体内）实参原文相同，靠顺序与行名区分；
 *   真要换形状，先动这张表。
 * - 单顶只认字面量赋值那一行：写成 `launchSingleTop = false` 不算带单顶（③(c) 那格还单独数它）。
 * - 取法照本仓在册守卫（[com.buaa.schedule.ui.home.StatsEntryWiringGuardTest] 与
 *   [com.buaa.schedule.nav.NavRootStartDestinationGuardTest] 同一套刀法）：读工作树源码文本、匹配前先
 *   抹注释（MainActivity 的 KDoc 里就写着两处 `navigate("editor/{courseId}")` 那样的散文，连注释一起扫
 *   会数出二十枚）、实参表按括号配平取、options lambda 按花括号配平取、找不到锚点就抛、不用 assumeTrue 跳过。
 * - 只读 MainActivity 这一枚 main 文件：别的屏幕的调用点属于别的卡（本轮现量 app/src/main 里真的在叫
 *   navigate 的只有这一枚文件，另一处 KDoc 散文不算读者）。
 * - 屏上表现（连点之后返回键要按几下）本卡一格都没验到：零设备、零 adb。
 */
class MainActivityNavigateSingleTopGuardTest {

    /**
     * ② 表驱动：册子与盘上调用式逐枚配对，每一枚都必须带单顶。
     * 红的时候念的是「第几枚、叫什么、缺什么、原文是什么」，不是一句「数量不符」。
     */
    @Test
    fun everyRosteredNavigateCallSiteCarriesLaunchSingleTop() {
        val calls = navigateCallSites()
        val problems = mutableListOf<String>()
        if (calls.size != ROSTER.size) {
            problems.add(
                "册子 " + ROSTER.size + " 行 ／ 盘上实到 " + calls.size + " 枚：顺序配对从这一格起不可信" +
                    "（枚数那一格单独点名，这一格只把配对失败的逐枚念出来）",
            )
        }
        for (row in ROSTER) {
            val call = calls.getOrNull(row.ordinal - 1)
            if (call == null) {
                problems.add("#" + row.ordinal + " " + row.name + "：盘上没有第 " + row.ordinal + " 枚调用式")
                continue
            }
            for (needle in row.expectations) {
                if (!call.expression.contains(needle)) {
                    problems.add(
                        "#" + row.ordinal + " " + row.name + "：调用式里读不到原文片段「" + needle + "」" +
                            "（实到 " + call.describe() + "）",
                    )
                }
            }
            if (row.singleTopRequired && !call.singleTop) {
                problems.add("#" + row.ordinal + " " + row.name + "：缺 " + SINGLE_TOP + "（" + call.describe() + "）")
            }
        }
        assertTrue(
            "钉法 = 表驱动册子逐枚对账（抹注释后按源码顺序配对）：ROSTER 每一行在盘上有一枚调用式、" +
                "实参原文对得上、且带「" + SINGLE_TOP + "」。在册那几枚的写法是字面 options lambda，" +
                "补的那几枚逐字同形；一级页助手那一枚的单顶在它自己那枚多行 lambda 体内。" +
                "实到 " + calls.size + " 枚调用式 ／ 册子 " + ROSTER.size + " 行 ｜ 问题：" +
                problems.joinToString(" ／ "),
            problems.isEmpty(),
        )
    }

    /**
     * ② 裁定钉死：册子里「故意叠两枚」那一档必须 0 枚。
     * 有人想按乙处理某一枚（登记成「册子＝现状」而不补参数），就得先把那一枚点名进这一格的例外名单、
     * 并在上面的裁定一节写清为什么它非要两枚 entry —— 悄悄把某行改成 false 会当场红在这里。
     */
    @Test
    fun rosterGrantsNoExceptionToAnyCallSite() {
        val exempt = ROSTER.filter { row -> !row.singleTopRequired }.map { row -> "#" + row.ordinal + " " + row.name }
        assertTrue(
            "第⓪问裁定是甲：盘上没有一枚调用点是故意要叠两枚的（editor 一族的「按 entry 隔离删除旗标」" +
                "靠的是不同 courseId ⇒ 不同 args ⇒ 单顶本来就不合并）。例外档实到 " + exempt.size +
                " 枚 ／ 写死 " + EXPECTED_EXEMPT_ROWS + " 枚：" + exempt.joinToString(" ／ ") +
                "（要留例外就得连本段判据与类 KDoc 的裁定一起改，别只改表）",
            exempt.size == EXPECTED_EXEMPT_ROWS,
        )
    }

    // ---- 读盘与解析（与各 *WiringGuardTest 同一套刀法）---------------------------------------------

    /** 盘上每一枚 `navigate(` 调用式：实参表按括号配平、紧随其后的 options lambda 按花括号配平 */
    private fun navigateCallSites(): List<NavigateCall> {
        val code = blankedMainActivity()
        val out = mutableListOf<NavigateCall>()
        var from = 0
        while (true) {
            val at = code.indexOf(NAVIGATE_CALL, from)
            if (at < 0) break
            val open = at + NAVIGATE_CALL.length - 1
            val arguments = code.substring(open, balancedParenEnd(code, open) + 1)
            var next = open + arguments.length
            while (next < code.length && code[next].isWhitespace()) next++
            val options = if (next < code.length && code[next] == '{') {
                code.substring(next, balancedBraceEnd(code, next) + 1)
            } else {
                ""
            }
            out.add(NavigateCall(ordinal = out.size + 1, line = lineOf(code, at), arguments = arguments, options = options))
            from = at + NAVIGATE_CALL.length
        }
        assertTrue(
            "抹注释之后的 " + MAIN_ACTIVITY + " 里一枚 " + NAVIGATE_CALL + " 都没读到：文件读空了，" +
                "不要拿后面那些「实到 0」当绿",
            out.isNotEmpty(),
        )
        return out
    }

    /** 抹注释之后的 MainActivity 源码文本（长度与换行位置不变，报错消息里的行号因此可信） */
    private fun blankedMainActivity(): String = blankComments(source(MAIN_ACTIVITY))

    /** 从 [open] 那枚左括号配平到与之匹配的右括号，交回右括号下标 */
    private fun balancedParenEnd(code: String, open: Int): Int {
        var depth = 0
        for (index in open until code.length) {
            when (code[index]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        throw IllegalStateException(NAVIGATE_CALL + " 的实参表没配平：:" + lineOf(code, open))
    }

    /** 从 [open] 那枚左花括号配平到与之匹配的右花括号，交回右花括号下标 */
    private fun balancedBraceEnd(code: String, open: Int): Int {
        var depth = 0
        for (index in open until code.length) {
            when (code[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        throw IllegalStateException("options lambda 的花括号没配平：:" + lineOf(code, open))
    }

    private fun source(relative: String): String {
        val file = File(findMainJavaDir(), relative)
        assertTrue("找不到 ${file.path}：文件挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText().replace("\r\n", "\n")
    }

    /** 把 `//` 与 `/* */` 注释抹成空格（字符串保留、长度与行位置不变）：与同族助手逐字同一写法 */
    private fun blankComments(src: String): String {
        val out = src.toCharArray()
        var i = 0
        while (i < out.size) {
            when {
                src.startsWith("//", i) -> {
                    val nl = src.indexOf('\n', i).let { if (it < 0) out.size else it }
                    for (k in i until nl) out[k] = ' '
                    i = nl
                }

                src.startsWith("/*", i) -> {
                    var depth = 1
                    var j = i + 2
                    while (j < out.size && depth > 0) {
                        when {
                            src.startsWith("/*", j) -> {
                                depth++
                                j += 2
                            }

                            src.startsWith("*/", j) -> {
                                depth--
                                j += 2
                            }

                            else -> j++
                        }
                    }
                    for (k in i until j.coerceAtMost(out.size)) if (out[k] != '\n') out[k] = ' '
                    i = j
                }

                out[i] == '"' || out[i] == '\'' -> {
                    val quote = out[i]
                    var j = i + 1
                    while (j < out.size) {
                        when {
                            src[j] == '\\' -> j += 2
                            src[j] == quote -> {
                                j++
                                break
                            }

                            src[j] == '\n' -> break
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

    /** 工作目录是模块目录还是仓库根不由这里决定：几种布局都试一遍，全落空就抛 */
    private fun findMainJavaDir(): File {
        var dir: File? = File("").absoluteFile
        repeat(5) {
            val hit = listOf("src/main/java", "app/src/main/java")
                .map { candidate -> File(dir, candidate) }
                .firstOrNull { it.isDirectory }
            if (hit != null) return hit
            dir = dir?.parentFile
        }
        throw IllegalStateException("找不到 app/src/main/java：当前目录 ${File("").absolutePath}")
    }
}

/**
 * 册子的一行：盘上第 ordinal 枚 `navigate(` 调用式（抹注释后按源码顺序）是谁、实参里必须读得到的
 * 原文片段是什么、要不要单顶。顺序即身份 —— 新增一枚调用点会让后面全部错位，同时被枚数那一格点名。
 */
private data class NavigateCallSite(
    val ordinal: Int,
    val name: String,
    val expectations: List<String>,
    val singleTopRequired: Boolean = true,
)

/** 一枚从源码里解析出来的调用式：实参表 + 紧随其后的 options lambda（没有就是空串） */
private data class NavigateCall(
    val ordinal: Int,
    val line: Int,
    val arguments: String,
    val options: String,
) {
    /** 调用式原文（锚点 + 实参表 + options lambda） */
    val expression: String get() = "navigate" + arguments + options

    /** 单顶只认字面量赋值那一行：`launchSingleTop = false` 不算带单顶 */
    val singleTop: Boolean get() = options.contains(SINGLE_TOP)

    /** 断言消息里的调用式摘要：行号 + 折成一行的原文 */
    fun describe(): String =
        ":" + line + " `" + expression.replace("\n", " ⏎ ").replace(Regex("[ ]+"), " ") + "`"
}

/** ② 的册子：十八枚调用点逐枚点名（行号只当线索，判据认的是顺序与原文） */
private val ROSTER: List<NavigateCallSite> = listOf(
    NavigateCallSite(1, "组件行点击 → 编辑器（在册：requestedCourseId 那一路）", listOf("\"editor/\$id\"")),
    NavigateCallSite(2, "通知落点 → 任意路由（在册：requestedRoute 那一路）", listOf("(route)")),
    NavigateCallSite(3, "引导落点里的一次性路由（在册：先清后跳，editor/-1 与 import）", listOf("(route)")),
    NavigateCallSite(
        4,
        "openSpocSignIn → 扫码页／登录页分流（多行实参表）",
        listOf("\"spoc_scan\"", "\"iclass_login\"", "IClassSession.hasSession()"),
    ),
    NavigateCallSite(5, "openIClassSignIn → iClass 登录页", listOf("\"iclass_login\"")),
    NavigateCallSite(6, "openSettingsSection → 设置子页", listOf("\"settings/\${section.id}\"")),
    NavigateCallSite(7, "openCourseManagement → 课程管理", listOf("\"course_management\"")),
    NavigateCallSite(8, "openStats → 学期统计", listOf("\"stats\"")),
    NavigateCallSite(9, "HomeScreen.onAddCourse → 新增课程编辑器", listOf("\"editor/-1\"")),
    NavigateCallSite(10, "HomeScreen.onImportBuaa → 教务登录页", listOf("\"buaa_login\"")),
    NavigateCallSite(11, "HomeScreen.onCourseManagement → 课程管理", listOf("\"course_management\"")),
    NavigateCallSite(12, "HomeScreen.onCourseClick → 那一门课的编辑器", listOf("\"editor/\${course.id}\"")),
    NavigateCallSite(13, "CourseManagementScreen.onEditCourse → 那一门课的编辑器", listOf("\"editor/\${course.id}\"")),
    NavigateCallSite(14, "ImportScreen.onStartBuaaLogin → 教务登录页", listOf("\"buaa_login\"")),
    NavigateCallSite(15, "ImportScreen.onOpenHistory → 导入历史", listOf("\"import_history\"")),
    NavigateCallSite(16, "SpocScanScreen.onNeedLogin → iClass 登录页", listOf("\"iclass_login\"")),
    NavigateCallSite(
        17,
        "IClassLoginScreen.onLoggedIn → 扫码页（唯一一枚带 popUpTo inclusive 的）",
        listOf("\"spoc_scan\"", "popUpTo(\"iclass_login\") { inclusive = true }"),
    ),
    NavigateCallSite(
        18,
        "navigateTopLevel 体内（一级页助手自己：popUpTo + 单顶 + restoreState）",
        listOf("(route)", "popUpTo(graph.findStartDestination().id)", "restoreState = true"),
    ),
)

/** 钉的是「在册写法」那一句字面量：改一个字符就红 */
private const val SINGLE_TOP = "launchSingleTop" + " = true"

/** 调用式锚点：`navigate(` 紧跟左括号，`navigateTopLevel(` 这一族同名字落不进尺子 */
private const val NAVIGATE_CALL = "navigate" + "("

/** ② 裁定钉死：例外档写死 0 枚 */
private const val EXPECTED_EXEMPT_ROWS = 0

private const val MAIN_ACTIVITY = "com/buaa/schedule/MainActivity.kt"

/** 从文本开头数到 [index] 落在第几行（1-based，与 grep -n 同口径） */
private fun lineOf(text: String, index: Int): Int {
    var line = 1
    var at = 0
    while (at < index && at < text.length) {
        if (text[at] == '\n') line++
        at++
    }
    return line
}
