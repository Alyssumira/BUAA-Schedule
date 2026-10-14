package com.buaa.schedule.ui.editor

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「外部那一跳撞上了已删的课」这条链路的接线守卫（T335，台账 #388）。
 *
 * 本模块没有 Compose 运行时（无 Robolectric、无 ui-test），`MainActivity` 编辑器目的地那一层
 * 「先念后退」到底成不成立只能扫源码，刀法照抄 [com.buaa.schedule.core.LaunchRequestWiringGuardTest]：
 * 读源文件文本、匹配前先 `blankComments` 抹注释（本卡的 KDoc 里就写着「不许重演 courseId >= 0」这类
 * 禁令本身，连注释一起扫会红在自己人手上）、函数体按花括号配平取、找不到锚点就抛、不用 assumeTrue 跳过。
 *
 * ## 为什么这一族账值得单独钉
 *
 * 判据内核（`domain/editor/EditorMissingCourseExit.kt`）在 JVM 里怎么表驱动都成立，但**用户听不听得见**
 * 全在接线那三行上。这一条链路上有三处各自独立的退回方式，每一处都恰好把在册 #388 那句「一句话不说」放回来：
 *
 * 1. 话被摘掉，或者话排在 `popBackStack()` **之后** —— 退栈那一手把整棵子树摘走，之后写进消息口的那一句
 *    还要不要人念，取决于首页重新组合的时机，而不取决于这一行；次序是本卡的判据之一，得钉成形状。
 * 2. 话被换成 composition 上的载体（`remember { mutableStateOf(...) }`、`LocalSnackbarHost`、
 *    `snackbarHostState.showSnackbar(...)`）—— 那一枚载体正长在**即将被弹掉**的那条 entry 上，
 *    与「旗不许落在无键 remember」是同一族（T177 的装机账：composition 上的值不跨配置变化，
 *    更不跨 popBackStack）。载体必须是 VM 上那枚 `StateFlow`。
 * 3. 消息口退回「写了没人念」（台账 #134 那一族）：目的地层往 `importMessage` 写了一句，而首页那枚
 *    读者被摘掉/清场排在展示之前 ⇒ 用户依旧什么都听不见，而 main 那一侧看起来什么都没坏。
 *
 * 另有两格是本卡的**边界**证据，不是多余的形状：协程的键仍然只有两枚（把删除旗加进键里就是连 pop 两格
 * 那一族），而「目标在不在屏上」这把尺全仓只许有一枚（`val courseMissing` 与那道渲染闸门读同一个值，
 * 内核再算一遍 `courseId >= 0 && course == null` 就是第二把尺）。
 */
class ExternalCourseGoneWiringGuardTest {

    // ---- ① 内核纯度：只吃布尔，不重演存在性那把尺 ----

    @Test
    fun `内核零设备依赖 且不吃任何一处就地读取`() {
        val text = blankComments(source(KERNEL))
        val offenders = text.lines().map { it.trimStart() }
            .filter { it.startsWith("import android") || it.startsWith("import androidx") }
        assertTrue(
            "EditorMissingCourseExit.kt 是要在 JVM 单测里跑裸判据的内核，混进设备依赖就没法测" +
                "（三枚事实全得当参数传进来）：\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
        // 时钟 / Intent / NavBackStackEntry / savedStateHandle 这一族：出现一次就是就地读设备对象
        val bypass = text.lines().map { it.trim() }.filter { line ->
            listOf(
                "Intent", "Bundle", "savedInstanceState", "savedStateHandle", "NavBackStackEntry",
                "navController", "uiState", "Build.VERSION", "System.currentTimeMillis", "Calendar",
                "LocalDate", "LocalDateTime", "Clock.",
            ).any { line.contains(it) }
        }
        assertTrue(
            "内核不许自己够任何一枚状态（三枚布尔由调用点算好递进来）：\n" + bypass.joinToString("\n"),
            bypass.isEmpty(),
        )
        // 单把尺：存在性那把尺留在目的地层，内核只认「已经算好的 courseMissing」
        val secondRuler = text.lines().map { it.trim() }.filter {
            it.contains("courseId") || it.contains("course ==") || it.contains("course !=")
        }
        assertTrue(
            "内核里出现了重演存在性的写法（目的地层那枚 val courseMissing 才是唯一一把尺，" +
                "渲染闸门读的也是它；这里再算一遍就是两把尺）：\n" + secondRuler.joinToString("\n"),
            secondRuler.isEmpty(),
        )
    }

    // ---- ② 结论只由内核给出，裸形不许回来 ----

    @Test
    fun `目的地层只问内核一次 且基点那枚裸 if 不许回来`() {
        val body = exitEffect()
        assertEquals(
            "编辑器目的地那一条协程问内核的处数应当恰好一处（两处 = 同一个结论算两遍，零处 = 判据退回就地 if）：\n" +
                body.lines().filter { it.contains("EditorMissingCourseExit.verdictOf(") }.joinToString("\n"),
            1,
            body.lines().count { it.contains("EditorMissingCourseExit.verdictOf(") },
        )
        assertFalse(
            "基点上那枚「就地判 !loading && courseMissing 然后直接 popBackStack」的裸形又回来了 —— " +
                "它就是「只退不出声」的本体：\n" +
                body.lines().filter { it.contains("if (!uiState.loading && courseMissing)") }.joinToString("\n"),
            Regex("""if \(!uiState\.loading && courseMissing\) \{\s*navController\.popBackStack\(\)""")
                .containsMatchIn(body),
        )
    }

    // ---- ③ 次序：话排在退栈之前 ----

    @Test
    fun `欠话那一档先念后退 两句之间没有挂起点`() {
        val body = exitEffect()
        val said = body.indexOf("viewModel.showMessage(")
        val popped = body.indexOf("navController.popBackStack()")
        assertTrue(
            "目的地层这一档现在欠用户一句话：showMessage 被摘掉就是回到在册 #388 那句「一句话不说」",
            said >= 0,
        )
        assertTrue(
            "popBackStack 那一手必须还在（本卡不改「退不退」，只加「念不念」）",
            popped >= 0,
        )
        assertTrue(
            "话必须排在退栈**之前**：退栈把整棵子树摘走，排在它后面那句还念不念得出来就不由这一行决定了。" +
                "实测下标 said=$said popped=$popped",
            said < popped,
        )
        assertEquals(
            "这一档念的话应当恰好一句（两句 = 同一档写了两遍，用户同屏听见两条）",
            1,
            Regex("viewModel\\.showMessage\\(").findAll(body).count(),
        )
        // 分支体自己也要能切出来：话与退栈必须在同一个 PopWithMessage 分支里
        val branch = balancedBlock(activity(), "MissingCourseExitVerdict.PopWithMessage -> {")
        assertTrue(
            "PopWithMessage 分支里必须同时有「念」和「退」两手：\n" + branch,
            branch.contains("viewModel.showMessage(") && branch.contains("navController.popBackStack()"),
        )
        assertTrue(
            "PopWithMessage 分支里念在退前（分支级复核，防的是把 showMessage 挪进别的分支）：\n$branch",
            branch.indexOf("viewModel.showMessage(") < branch.indexOf("navController.popBackStack()"),
        )
        // 「本页自己删的」那一档只退不念：念了就是对着用户亲手删掉的课谎报死因
        val silentLines = body.lines().map { it.trim() }.filter {
            it.startsWith("MissingCourseExitVerdict.PopWithoutMessage")
        }
        assertEquals(
            "PopWithoutMessage 那一档必须还是单行、只有退栈这一手（删除链成功那一路页面本来就一个字不念）：\n" +
                silentLines.joinToString("\n"),
            listOf("MissingCourseExitVerdict.PopWithoutMessage -> navController.popBackStack()"),
            silentLines,
        )
    }

    // ---- ④ 载体：话只能长在跨 popBackStack 存活的那一枚状态上 ----

    @Test
    fun `话的载体是 VM 的消息口 不是正在被弹掉的那棵 composition`() {
        val body = exitEffect()
        val carriers = body.lines().map { it.trim() }.filter {
            it.contains("remember") || it.contains("mutableStateOf") ||
                it.contains("showSnackbar") || it.contains("savedStateHandle") || it.contains("rememberSaveable")
        }
        assertTrue(
            "这条协程里不许出现 composition 寿命的载体：那枚值长在**即将被弹掉**的 entry 上，" +
                "popBackStack() 之后没人再读它（与「旗不许落在无键 remember」同族，T177 的装机账）：\n" +
                carriers.joinToString("\n"),
            carriers.isEmpty(),
        )
        assertEquals(
            "MainActivity 全文不许出现第二套 Snackbar 直调：本卡那句话走的必须是 viewModel.showMessage " +
                "那一枚口（VM 上的 StateFlow，寿命跟 Activity 级 VM，不跟 composition）",
            0,
            blankComments(activity()).lines().count { it.contains("showSnackbar") },
        )
        // 也不许把「出声」退化成写日志：日志不是给用户听的一句话
        val logs = body.lines().map { it.trim() }.filter {
            it.contains("Log.") || it.contains("println") || it.contains("Logger")
        }
        assertTrue(
            "这一档的收尾是一句给用户的话，取证日志兑不了 #388：\n" + logs.joinToString("\n"),
            logs.isEmpty(),
        )
    }

    // ---- ⑤ 协程的键：那枚旗只当参数递，不许加进键里 ----

    @Test
    fun `协程的键仍是两枚 删除旗不许加进来`() {
        val text = activity()
        assertEquals(
            "结论那条协程的键写法在全文应当只有一处锚点（漂了就是守卫要跟着改的那一类）",
            1,
            Regex("LaunchedEffect\\(courseId, uiState\\.loading\\)").findAll(text).count(),
        )
        val keys = Regex("LaunchedEffect\\(([^)]*)\\)").findAll(text)
            .map { it.groupValues[1] }
            .filter { it.contains("courseId") }
            .toList()
        assertTrue(
            "把 deleteRequestedByThisEntry 加进这条协程的键里 ⇒ 旗翻真的那一刻两条协程各弹一次 ⇒ 连 pop 两格、" +
                "用户被多退一层（基点那行 ⚠️ 注释说的是同一件事）：\n" + keys.joinToString("\n"),
            keys.all { !it.contains("deleteRequestedByThisEntry") },
        )
        assertEquals(
            "删除链那一条协程（旗 + courseMissing + loading 三枚键）必须还在，且仍只有一处：" +
                "本卡没有把它的认领次序挪走，PopWithoutMessage 那一档靠的就是它",
            1,
            Regex("LaunchedEffect\\(deleteRequestedByThisEntry, courseMissing, uiState\\.loading\\)")
                .findAll(text).count(),
        )
        // 递给内核的必须是那枚跨配置存活的旗本身，不是另一枚就地算出来的布尔
        assertTrue(
            "verdictOf 的 deletionRequestedByThisEntry 参数必须递 `deleteRequestedByThisEntry` 本身" +
                "（savedStateHandle 上那一枚，T177）：\n" + exitEffect(),
            Regex("deletionRequestedByThisEntry = deleteRequestedByThisEntry(,|\\s)")
                .containsMatchIn(exitEffect()),
        )
    }

    // ---- ⑥ 消息口有活读者：目的地写了，首页得念 ----

    @Test
    fun `首页那枚读者还在念 importMessage 且清场排在展示之后`() {
        val home = blankComments(source(HOME_SCREEN))
        assertEquals(
            "HomeScreen 收集 importMessage 的处数应当恰好一处（零处 = 目的地层往空气里说话，台账 #134 那一族）",
            1,
            Regex("viewModel\\.importMessage\\.collectAsState\\(\\)").findAll(home).count(),
        )
        val shown = home.indexOf("showSnackbar(message.text)")
        val cleared = home.indexOf("viewModel.clearImportMessage()")
        assertTrue(
            "展示那一手必须还在（showSnackbar(message.text)）：口被读出来才是「用户听见」",
            shown >= 0,
        )
        assertTrue(
            "读完即清那一手必须还在：不清就是下次回到首页再弹一遍陈旧提示",
            cleared >= 0,
        )
        assertTrue(
            "清场必须排在展示**之后**（反过来就是先吃掉再念 ⇒ 永远念不出来）：shown=$shown cleared=$cleared",
            shown < cleared,
        )
    }

    // ---- ⑦ 单一存在性尺 + 渲染闸门原样 ----

    @Test
    fun `存在性那把尺全仓一枚 渲染闸门一字未动`() {
        val text = activity()
        assertEquals(
            "`val courseMissing = courseId >= 0 && course == null` 在全文应当只有一枚定义：" +
                "内核与渲染闸门都读它，出现第二枚定义就是两把尺",
            1,
            Regex("val courseMissing = courseId >= 0 && course == null").findAll(text).count(),
        )
        assertEquals(
            "编辑器子树那道闸门（白帧那一格的结构出处）必须还是 `!uiState.loading && !courseMissing` 加" +
                " T381① 那半 `reminderGateOpen` 一处：本卡不收白帧，也就不许顺手换它的尺；" +
                "第三半被摘或那一行被拆成两扇各自判一遍，这一把都断",
            1,
            Regex("if \\(!uiState\\.loading && !courseMissing && reminderGateOpen\\) \\{").findAll(text).count(),
        )
    }

    // ---- ⑧ 措辞不许越过自己拿得到的证据 ----

    @Test
    fun `那句话只说自己知道的那一半 死因得挂着可能`() {
        val body = exitEffect()
        val line = body.lines().first { it.contains("viewModel.showMessage(") }.trim()
        assertTrue(
            "这句话必须说出「不在当前课表里」这一半（目的地层唯一确证的死处），兑 #388 的「一句话不说」：\n$line",
            line.contains("不在当前课表"),
        )
        assertTrue(
            "这句话不许断言死因：管理页删的、组件行滞留一帧的、切学期的，目的地层一律分辨不了，" +
                "不带「可能」就是假话：\n$line",
            line.contains("可能"),
        )
        assertTrue(
            "这句话不许写成「删除成功」一类（那是删除链那一档的话，而那一档刻意保持静默）：\n$line",
            !line.contains("已删除") && !line.contains("删除成功"),
        )
    }

    // ---- 源码核对工具（与各 *WiringGuardTest 同一套刀法）----

    private fun activity(): String = blankComments(source(MAIN_ACTIVITY))

    /** 编辑器目的地那一条「念 + 退」的协程体（含外层大括号） */
    private fun exitEffect(): String =
        balancedBlock(activity(), "androidx.compose.runtime.LaunchedEffect(courseId, uiState.loading)")

    private fun source(relative: String): String {
        val file = File(findMainJavaDir(), relative)
        assertTrue("找不到 ${file.path}：文件挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText()
    }

    /** 从 [signature] 之后第一个 `{` 起配平到对应右括号（含）；找不到锚点就抛，静默跳过等于没有守卫 */
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

    /** 把 `//` 与 `/* */` 注释抹成空格（字符串保留、长度与换行位置不变）：钉的是接线，不是白话 */
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
                            src.startsWith("/*", j) -> { depth++; j += 2 }
                            src.startsWith("*/", j) -> { depth--; j += 2 }
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
                            src[j] == quote -> { j++; break }
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

    /** 单测的工作目录是模块目录还是仓库根不由这里决定：几种布局都试一遍，全落空就抛 */
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
        const val MAIN_ACTIVITY = "com/buaa/schedule/MainActivity.kt"
        const val KERNEL = "com/buaa/schedule/domain/editor/EditorMissingCourseExit.kt"
        const val HOME_SCREEN = "com/buaa/schedule/ui/home/HomeScreen.kt"
    }
}
