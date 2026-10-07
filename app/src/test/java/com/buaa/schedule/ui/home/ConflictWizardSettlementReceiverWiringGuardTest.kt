package com.buaa.schedule.ui.home

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T250②：钉「冲突向导那一次点击的**收尾**（等 `applyConflictShift` 那枚 job 的结论 + 记
 * `onShiftEnd` 的账）长在 `viewModel.viewModelScope` 上，而不是长在 `ConflictGroupRow` 那一行的
 * 组合 scope 上」—— 纯源码核对（JVM，无 Robolectric、无设备、不读时钟）。
 *
 * ## 为什么本守卫不许再按逐字节字面量钉（这一族的病根）
 *
 * 在册那两枚棘轮（[com.buaa.schedule.ui.WriteChainReceiverWiringGuardTest] 与
 * [com.buaa.schedule.ui.editor.CourseEditorDeleteExitReceiverGuardTest]）拿
 * `const val SCOPE_DECL = "val scope = rememberCoroutineScope()"` 当尺，**按逐字节匹配**。
 * 向导那一枚写的是 `val rowScope = rememberCoroutineScope()` —— 换了个名就绕过去了：
 * 计数照旧 0、棘轮照旧绿，而病一直长在盘上。所以本守卫的判据一律按**形状/族**：
 *  - 族一：`rememberCoroutineScope` 这个标识符（含 import、含任何换名的声明、含
 *    `rememberCoroutineScope().launch` 那种就地调用）在向导生产文件里**次数必须为 0**；
 *  - 族二：本文件每一枚 `<接收者>.launch {` 的接收者必须落在 **`viewModel.viewModelScope` 那一族**
 *    （viewModel 递进来的 scope，或 `job` 的完成回调）—— 行组合 scope / 页 scope / 自己
 *    `CoroutineScope(...)` 造的 scope 都不在族里。
 * 档 ④ 把那两把尺各喂一枚「换名靶子」，逐字节那把量不到、形状那把必须点名 ⇒ 谁把本守卫退成
 * 逐字节字面量匹配，第 ④ 档先红。
 *
 * ## 钉法（七档）
 *
 * 1. 族一（标识符次数 + 换名声明的形状正则两把尺各数一次，都要给空）；
 * 2. 族二（每一枚 `.launch {` 的接收者逐枚点名文件 + 行号 + 原文，枚数与族别一起对）；
 * 3. 那一手点击的形状：`onShiftStart` 同步立旗在先、协程体内恰好一枚 viewModelScope 落点、
 *    `onShiftEnd(courseKey, onApplyShift(…))` 逐字躺在那一枚协程体里；
 * 4. 换名靶子：形状尺点名、逐字节尺失明（本守卫自证免疫）；
 * 5. 两枚调用点（首页、统计页）不许把这一手抄回目的地层：那枚 `onApplyShift` lambda 体内
 *    协程 0 枚、`rememberCoroutineScope` 0 处；
 * 6. 卡面 §5 的契约：`applyConflictShift` 那枚 job 的语义与**次序**一字未动、收尾没被搬进落库
 *    那一手；弹窗层那两枚账（`shiftedCourses` / `pendingCourses`）的**记账位置**仍在弹窗层、
 *    没被"顺手"搬回行内；
 * 7. 内核纯度：配套的 [ConflictWizardSettlementReceiverPolicyTest] 零 android import、零读钟 ——
 *    本卡要求"替身与接线两侧都有格子"，替身那一侧一掺 android 就再也打不开。
 */
class ConflictWizardSettlementReceiverWiringGuardTest {

    // ---- ① 族一：本文件里 rememberCoroutineScope 一次都不许多（不按名字钉） ----

    @Test
    fun 向导生产文件里任何换名的行组合scope声明都算违反() {
        val code = blankComments(source(WIZARD))
        // 尺 A：标识符本身（import、声明、就地调用一起算）
        assertEquals(
            "$WIZARD 里 `rememberCoroutineScope` 这个标识符必须**0 次**（注释除外）——实到 " +
                occurrences(code, "rememberCoroutineScope") + " 次：本文件的 KDoc 早就把这条规矩写明了" +
                "（作用域必须是 `viewModelScope`），而收尾那一手原先挂在**换过名**的那一枚行组合 scope 上，" +
                "在册那两枚按 `val scope = rememberCoroutineScope()` 逐字节匹配的棘轮因此全程为绿。\n" +
                "复算：grep -n 'rememberCoroutineScope' app/src/main/java/$WIZARD",
            0,
            occurrences(code, "rememberCoroutineScope"),
        )
        // 尺 B：形状 —— 任何 `va[lr] <任意名字> = rememberCoroutineScope()` 的变量名，逐枚点名
        val declared = scopeDeclNames(code)
        assertEquals(
            "$WIZARD 里由行组合 scope 供给的变量名必须一枚都不剩（换名不算修好）——实到 " + declared + "：",
            emptyList<String>(),
            declared,
        )
    }

    // ---- ② 族二：每一枚 .launch 的接收者必须落在 viewModelScope 那一族 ----

    @Test
    fun 本文件每一枚launch的接收者都在viewModelScope那一族() {
        val raw = source(WIZARD)
        val code = blankComments(raw)
        val receivers = launchReceivers(code)
        assertEquals(
            "$WIZARD 里 `.launch {` 必须恰好两枚（先例那一枚 job + 收尾那一枚），枚数变了就是本守卫要" +
                "跟着重判的前提变了 —— 实到 " + receivers.size + " 枚：\n" + receivers.joinToString("\n") {
                    "  L" + lineOf(raw, it.second) + "：接收者「" + it.first + "」"
                },
            2,
            receivers.size,
        )
        val offFamily = receivers.filter { !it.first.isScopeFamily() }
        assertTrue(
            "协程的接收者不在 **viewModel.viewModelScope / job 那一族**（族内只认这两型；页 scope、" +
                "行组合 scope、自己 CoroutineScope(…) 造的一枚都不算）——实到：\n" +
                offFamily.joinToString("\n") {
                    "  $WIZARD L" + lineOf(raw, it.second) + "：「" + it.first + ".launch {」"
                } +
                "\n协程的寿命一旦等于一行的组合寿命，行滚出 LazyColumn 视口那一刻就是" +
                "「库里写了、界面永远不知道写了」：按钮永久「写入中…」、已应用账记不上。",
            offFamily.isEmpty(),
        )
    }

    // ---- ③ 那一手点击的形状：立旗在先、收尾在那一枚 viewModelScope 协程体里 ----

    @Test
    fun 点击那一手的收尾协程落在viewModelScope上而记账在体内() {
        val code = blankComments(source(WIZARD))
        val hits = indexOfAll(code, ON_CLICK)
        assertEquals(
            "$WIZARD 里 `$ON_CLICK` 必须恰好一枚（就是「只改这些周」那颗按钮）——实到 " + hits.size + " 处：" +
                "锚点漂了就说明点击那一手换了形状，本守卫要跟着重判",
            1,
            hits.size,
        )
        val block = blockBody(code, code.indexOf('{', hits[0]), "那一手点击（onClick）")
        assertEquals(
            "点击那一手里 viewModelScope 的落点必须恰好一枚 —— 实到 " + occurrences(block, VM_SCOPE_LAUNCH) + " 枚：" +
                "0 枚就是收尾又被搬回行组合/页 scope，2 枚就是同一手起了两趟",
            1,
            occurrences(block, VM_SCOPE_LAUNCH),
        )
        val startAt = block.indexOf("onShiftStart(courseKey)")
        val launchAt = block.indexOf(VM_SCOPE_LAUNCH)
        assertTrue(
            "`onShiftStart(courseKey)` 必须排在协程**之前**（同步立起「正在写哪几门」，按钮当场变灰）：" +
                "startAt=$startAt, launchAt=$launchAt",
            startAt >= 0 && launchAt >= 0 && startAt < launchAt,
        )
        val body = blockBody(block, block.indexOf('{', launchAt), "收尾那一枚协程体")
        assertEquals(
            "收尾那一手里必须逐字是「`onShiftEnd(courseKey, onApplyShift(target, suggestion.periods, " +
                "group.weeks))`」（弹窗层的账由它复位）——实到 " + occurrences(body, SETTLEMENT) + " 处",
            1,
            occurrences(body, SETTLEMENT),
        )
        assertTrue(
            "收尾那一枚协程体里不许再长出族外接收者（行组合 scope 回来就是本卡的病）——实到 " +
                launchReceivers(body).map { it.first },
            launchReceivers(body).all { it.first.isScopeFamily() },
        )
    }

    // ---- ④ 换名靶子：形状尺点名，逐字节尺失明（本守卫自证免疫） ----

    @Test
    fun 形状尺在换名的那一形上不像逐字节字面量那样失明() {
        val target = blankComments(ROW_SCOPE_TARGET)
        // 在册那两枚棘轮的尺（逐字节字面量）：靶子里一枚都数不到 ⇒ 那正是被绕过去的那个洞
        assertEquals(
            "逐字节字面量那把尺在靶子上量不到任何东西（`$SCOPE_DECL_LITERAL` 这一串换名就不认了）——" +
                "本格钉的是「本守卫不许退成这一把尺」",
            0,
            occurrences(target, SCOPE_DECL_LITERAL),
        )
        // 形状尺（族一）：换名的声明必须被点名
        assertEquals(
            "形状尺必须把换名那一枚声明数出来（`val rowScope = …` 也算）——实到 " + scopeDeclNames(target) + "：",
            listOf("rowScope"),
            scopeDeclNames(target),
        )
        // 族尺（族二）：非 viewModelScope 族的接收者必须被点名
        assertEquals(
            "族尺必须把「rowScope.launch」这一枚接收者点名（它不在 viewModel.viewModelScope / job 那一族）" +
                "——实到 " + launchReceivers(target).map { it.first } + "：",
            listOf("rowScope"),
            launchReceivers(target).filter { !it.first.isScopeFamily() }.map { it.first },
        )
    }

    // ---- ⑤ 两枚调用点不许把这一手抄回目的地层 ----

    @Test
    fun 首页与统计页那两枚回调里不许自己起协程() {
        for (relative in listOf(HOME_SCREEN, STATS_SCREEN)) {
            val code = blankComments(source(relative))
            val at = code.indexOf(ENTRY_LAMBDA)
            assertTrue(
                "$relative 找不到那枚 `onApplyShift` lambda 的锚点「$ENTRY_LAMBDA」：" +
                    "锚点漂了（换签名、换写法）本守卫就要跟着重判",
                at >= 0,
            )
            val body = blockBody(code, code.indexOf('{', at), "$relative 的 onApplyShift lambda")
            assertEquals(
                "$relative 的 onApplyShift 体内协程必须 0 枚：落库与作用域三条账都在 applyConflictShift " +
                    "那一处，收尾那一手归弹窗层那枚 viewModelScope 协程（T250①）——实到 " +
                    launchReceivers(body).map { it.first } + "：",
                0,
                launchReceivers(body).size,
            )
            assertEquals(
                "$relative 的 onApplyShift 体内不许出现行组合 scope（实到 " +
                    occurrences(body, "rememberCoroutineScope") + " 处）：",
                0,
                occurrences(body, "rememberCoroutineScope"),
            )
        }
    }

    // ---- ⑥ 卡面 §5 的契约：先例那一手与弹窗层记账位置都不许动 ----

    @Test
    fun 先例那一枚job的次序未动而弹窗层那两枚账没被搬回行内() {
        val code = blankComments(source(WIZARD))
        val apply = functionBody(code, APPLY_SIGNATURE, "applyConflictShift")
        assertEquals(
            "`applyConflictShift` 体内 `$VM_JOB` 必须恰好一枚（实到 " + occurrences(apply, VM_JOB) + " 处）：" +
                "本卡只搬收尾那一手，:102 那一手的语义与顺序是契约",
            1,
            occurrences(apply, VM_JOB),
        )
        val jobAt = apply.indexOf(VM_JOB)
        val joinAt = apply.indexOf("job.join()")
        val returnAt = apply.indexOf("return saved")
        assertTrue(
            "那一手仍按「起 job → job.join() → return saved」的次序（jobAt=$jobAt, joinAt=$joinAt, " +
                "returnAt=$returnAt）：join 挪到协程体里就是 fire-and-forget，调用方拿不到结论",
            jobAt in 0 until joinAt && joinAt < returnAt,
        )
        assertEquals(
            "收尾那一手不许被搬进落库那一手（记账仍由弹窗层认领，`onShiftEnd` 在 applyConflictShift " +
                "体内必须 0 处；实到 " + occurrences(apply, "onShiftEnd") + " 处）：",
            0,
            occurrences(apply, "onShiftEnd"),
        )
        // 弹窗层那两枚账的记账位置：卡面 §5 明令不许"顺手"搬回行内
        val dialog = functionBody(code, DIALOG_SIGNATURE, "ConflictWizardDialog")
        val row = functionBody(code, ROW_SIGNATURE, "ConflictGroupRow")
        assertEquals(
            "`mutableStateOf(setOf<String>())` 在弹窗层必须恰好两枚（shiftedCourses + pendingCourses；实到 " +
                occurrences(dialog, LEDGER_DECL) + " 枚）：",
            2,
            occurrences(dialog, LEDGER_DECL),
        )
        assertEquals(
            "行内（ConflictGroupRow）那两枚账必须 0 枚（实到 " + occurrences(row, LEDGER_DECL) +
                " 枚）：挂回行 remember 就是 group 换实例即复位的那一版，重复位移保护形同虚设",
            0,
            occurrences(row, LEDGER_DECL),
        )
    }

    // ---- ⑦ 内核纯度：替身那一侧一掺 android 就再也打不开 ----

    @Test
    fun 配套内核只跑在JVM上零android零读钟() {
        val code = blankComments(sourceOfTestDir(POLICY_TEST))
        for (banned in listOf(
            "import android", "import androidx", "import java.time", "import kotlin.time",
            "SystemClock", "currentTimeMillis", "nanoTime", "LocalDate", "LocalDateTime", "Clock", "Date(",
        )) {
            assertTrue(
                "$POLICY_TEST 里出现了「$banned」：判据内核一旦依赖 android/时钟，这一档在 JVM 门禁里" +
                    "就打不开（本仓无 Robolectric，Compose 那一侧只能按形状钉 + 纯内核）",
                !code.contains(banned),
            )
        }
        assertEquals(
            "内核那一侧必须确实**跑**在协程上（「= runBlocking {」恰好三枚，①②③ 各一枚；实到 " +
                occurrences(code, RUN_BLOCKING_USE) + " 枚）：一枚都不跑协程的「替身」钉不到取消语义，" +
                "而本卡的病恰恰是取消语义",
            3,
            occurrences(code, RUN_BLOCKING_USE),
        )
    }

    // ---------------- helpers（刀法照抄 ConflictShiftWeekScopeWiringGuardTest） ----------------

    /** 族一尺：`va[lr] <任意名字> = rememberCoroutineScope()` 里的变量名（**换名照样抓到**）。 */
    private fun scopeDeclNames(code: String): List<String> =
        SCOPE_DECL_SHAPE.findAll(code).map { it.groupValues[1] }.toList()

    /** 族二尺：每一枚 `<接收者>.launch {` 的 (接收者, 偏移)。 */
    private fun launchReceivers(code: String): List<Pair<String, Int>> =
        LAUNCH_RECEIVER.findAll(code).map { it.groupValues[1] to it.range.first }.toList()

    /** 族内只认这两型：viewModel 递进来的 `viewModelScope`，或那枚 `job`（完成回调那一族）。 */
    private fun String.isScopeFamily(): Boolean =
        this == "viewModel.viewModelScope" || endsWith(".viewModelScope") || this == "viewModelScope"

    /** 从开括号取到配平的那一枚闭括号，返回**体内**文本；锚点找不到或段长越界都抛，不静默跳过。 */
    private fun blockBody(code: String, openBraceAt: Int, label: String): String {
        assertTrue("$label 找不到开括号（锚点漂了要回来重钉本守卫）", openBraceAt in 0 until code.length)
        assertTrue("$label 的开括号处不是 `{`：实到「${code[openBraceAt]}」", code[openBraceAt] == '{')
        var depth = 0
        for (i in openBraceAt until code.length) {
            when (code[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return code.substring(openBraceAt + 1, i)
                }
            }
        }
        throw IllegalStateException("$label 的花括号没配平")
    }

    /** 从签名那句起按花括号配平取整个函数（含签名自己）；锚点找不到就抛。 */
    private fun functionBody(code: String, signature: String, label: String): String {
        val at = code.indexOf(signature)
        assertTrue("$label 的签名锚点没找到（改名/挪家要回来重钉本守卫）", at >= 0)
        val open = code.indexOf('{', at)
        assertTrue("$label 找不到函数体的开括号", open >= 0)
        var depth = 0
        for (i in open until code.length) {
            when (code[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return code.substring(at, i + 1)
                }
            }
        }
        throw IllegalStateException("$label 的花括号没配平")
    }

    /** 偏移 → 1 起的行号（用**未抹注释**的原文量，报错消息指的路才是对的） */
    private fun lineOf(raw: String, index: Int): Int = raw.substring(0, index.coerceAtLeast(0)).count { it == '\n' } + 1

    private fun source(relative: String): String = File(findMainJavaDir(), relative).readText()

    private fun sourceOfTestDir(relative: String): String = File(findTestJavaDir(), relative).readText()

    private fun indexOfAll(hay: String, needle: String): List<Int> {
        val found = mutableListOf<Int>()
        var at = hay.indexOf(needle)
        while (at >= 0) {
            found += at
            at = hay.indexOf(needle, at + needle.length)
        }
        return found
    }

    private fun occurrences(hay: String, needle: String): Int = indexOfAll(hay, needle).size

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

    private fun findTestJavaDir(): File {
        var dir: File? = File("").absoluteFile
        repeat(5) {
            val hit = listOf("src/test/java", "app/src/test/java")
                .map { File(dir, it) }
                .firstOrNull { it.isDirectory }
            if (hit != null) return hit
            dir = dir?.parentFile
        }
        throw IllegalStateException("找不到 app/src/test/java：当前目录 ${File("").absolutePath}")
    }

    private companion object {
        const val WIZARD = "com/buaa/schedule/ui/home/ConflictWizardDialog.kt"
        const val HOME_SCREEN = "com/buaa/schedule/ui/home/HomeScreen.kt"
        const val STATS_SCREEN = "com/buaa/schedule/ui/stats/StatsScreen.kt"
        const val POLICY_TEST = "com/buaa/schedule/ui/home/ConflictWizardSettlementReceiverPolicyTest.kt"

        const val APPLY_SIGNATURE = "suspend fun applyConflictShift("
        const val DIALOG_SIGNATURE = "fun ConflictWizardDialog("
        const val ROW_SIGNATURE = "private fun ConflictGroupRow("

        const val ON_CLICK = "onClick = {"
        const val VM_SCOPE_LAUNCH = "viewModel.viewModelScope.launch {"
        const val VM_JOB = "val job = viewModel.viewModelScope.launch {"
        const val SETTLEMENT = "onShiftEnd(courseKey, onApplyShift(target, suggestion.periods, group.weeks))"
        const val ENTRY_LAMBDA = "onApplyShift = { target, newPeriods, groupWeeks ->"
        const val LEDGER_DECL = "mutableStateOf(setOf<String>())"

        /** ⑦ 数的是内核里真跑起来的那几手，不是 import 那一行 */
        const val RUN_BLOCKING_USE = "= runBlocking {"

        /** 在册那两枚棘轮用的**逐字节**尺（本守卫的反面教材，只在第 ④ 档当靶子量一次） */
        const val SCOPE_DECL_LITERAL = "val scope = rememberCoroutineScope()"

        /** 族一：任意换名的行组合 scope 声明 */
        val SCOPE_DECL_SHAPE = Regex("""va[lr]\s+(\w+)\s*=\s*rememberCoroutineScope\(\)""")

        /** 族二：任意 `<接收者>.launch {` 的接收者 */
        val LAUNCH_RECEIVER = Regex("""([A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*)\.launch\s*\{""")

        /**
         * 换名靶子：本卡修掉的那一手原样摆在这儿（`val rowScope` + `rowScope.launch`），
         * 让形状尺与逐字节尺各量一次。⚠️ 这段不许删：删了就等于把"本守卫按形状钉"这一判
         * 自己摘掉 —— 谁退成逐字节字面量匹配，第 ④ 档就是靠它才红的。
         */
        val ROW_SCOPE_TARGET = """
            @Composable
            private fun ConflictGroupRow(target: Course, group: ConflictGroup, suggestion: ShiftSuggestion) {
                val rowScope = rememberCoroutineScope()
                TextButton(
                    enabled = !pending,
                    onClick = {
                        onShiftStart(courseKey)
                        rowScope.launch {
                            onShiftEnd(courseKey, onApplyShift(target, suggestion.periods, group.weeks))
                        }
                    },
                ) { Text(if (pending) "写入中…" else "只改这些周") }
            }
        """.trimIndent()
    }
}
