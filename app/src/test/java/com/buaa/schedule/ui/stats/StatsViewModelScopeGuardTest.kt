package com.buaa.schedule.ui.stats

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 统计页那枚 ViewModel 的**作用域**守卫（T75，台账 #116）。
 *
 * 本模块没有 Compose 运行时（无 Robolectric、无 ui-test），"这一页到底吃的是哪一枚 VM"
 * 只能扫源码，刀法照抄 [StatsPageStageWiringGuardTest] 与
 * [com.buaa.schedule.ui.home.StatsEntryWiringGuardTest]：读源文件文本、匹配前先
 * `blankComments` 抹注释（本卡的 KDoc 里就写着被禁的那种写法本身，连注释一起扫会红在
 * 自己人手上）、实参表按括号配平取、找不到锚点就抛、不用 assumeTrue 跳过。
 *
 * 为什么这一族账值得单独钉：`StatsScreen` 的 `viewModel` 原本带着
 * `= viewModel(factory = ...)` 默认值，那颗默认值在 NavHost 的 `composable("stats")` 里
 * 解析 ⇒ 宿主是那条 nav entry 的 `ViewModelStore` ⇒ **每次进入这一页都新造一枚**
 * `ScheduleViewModel`，`uiState` 从 `stateIn` 的 `initialValue` 重走一遍加载链。
 * 症状不是崩溃也不是错账，只是每次进页都慢一拍、外加"正在读取本学期课表"闪一下，
 * 所以它能在 master 上活很久。装机取证（buaa36 / debug 包 / 临时探针）：改前 18 次进入
 * 打出 18 枚互不相同的 VM 身份、18 次先亮 Loading；改后 19 次进入全是同一枚 VM、0 次 Loading。
 *
 * 钉四档（共 6 条）：
 * 1. `composable("stats")` 的调用点显式写着 `viewModel = viewModel`，且全仓只有这一个
 *    `StatsScreen(` 生产调用点；
 * 2. `StatsScreen` 的签名里 `viewModel: ScheduleViewModel` **不许带默认值**，本页也不许再
 *    出现 `ScheduleViewModel.Factory(` 与 `viewModel(` 同框（那就是把默认值原样搬回来）；
 * 3. 传进去的那枚必须是 `AppNavHost` 里 `val viewModel: ScheduleViewModel = viewModel(...)`
 *    定义的 Activity 作用域那枚 —— 与 `settings/{section}` 等八个调用点同一枚（本卡照的就是
 *    那个范本），而不是随手造的同类对象；
 * 4. 复用 VM 之后**不许顺手削弱 T74 那套判档与 Sharing 口径**：Loading 那一面还在画、
 *    `statsPageStageOf` 的调用点还在、`uiState` 仍是 `WhileSubscribed(5_000)`。
 */
class StatsViewModelScopeGuardTest {

    // ---- ① 调用点：显式传，且只有一个 ----

    /**
     * ①-a 缺陷本体：`composable("stats")` 里那句 `StatsScreen(...)` 必须写着 `viewModel = viewModel`。
     *
     * 三种漂法各拦一次：整个参数被漏掉（默认值一长回来就是这种静默）、现场传一枚新的
     * （`viewModel = viewModel(factory = ...)`，字面上也有 `viewModel =` 但宿主又变回 nav entry）、
     * 传同类但不同作用域的对象（①-b 与 ③ 合起来拦）。
     */
    @Test
    fun statsRouteCallSitePassesTheSharedViewModel() {
        val activity = blankComments(source(MAIN_ACTIVITY))
        val route = balancedBlock(activity, "composable(\"stats\")")
        val sites = invocationArgumentLists(route, "StatsScreen")
        assertEquals("composable(\"stats\") 里的 StatsScreen 调用点数应当恰好一处：${sites.size}", 1, sites.size)
        val args = sites[0]
        val passed = Regex("""viewModel\s*=\s*([A-Za-z_][\w.]*)""").findAll(args).map { it.groupValues[1] }.toList()
        assertEquals(
            "统计页没有吃 Activity 作用域那枚 ScheduleViewModel：只要这一页自己再拿一枚，" +
                "每次进入就都是新 VM + 从 initialValue 重走加载链（台账 #116）。实参表是：$args",
            listOf("viewModel"), passed,
        )
        assertFalse(
            "调用点现场又造了一枚（`viewModel(factory = ...)` 的宿主是那条 nav entry，" +
                "等于把默认值搬到这里来写）：$args",
            Regex("""viewModel\s*=\s*viewModel\(""").containsMatchIn(args),
        )
    }

    /** ①-b 全仓只许有一个生产调用点：多一处就多一处可能漏传 */
    @Test
    fun statsScreenIsInvokedExactlyOnceRepoWide() {
        val dir = findMainJavaDir()
        val files = dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList().sortedBy { it.path }
        assertTrue("$dir 下一个 .kt 都没有，扫描范围取错了", files.isNotEmpty())
        val hits = ArrayList<String>()
        for (file in files) {
            val code = blankComments(file.readText())
            for (match in Regex("""\bStatsScreen\(""").findAll(code)) {
                val at = match.range.first
                // 声明（`fun StatsScreen(`）不算调用点：按声明取会把形参表当实参表量
                if (code.substring(0, at).trimEnd().endsWith("fun")) continue
                balancedArguments(code, match.range.last)
                hits += file.name
            }
        }
        assertEquals(
            "StatsScreen 的生产调用点全仓应当恰好一处（在 MainActivity 的 composable(\"stats\") 里）。" +
                "多一处就是要多钉一处「有没有把 Activity 那枚 VM 传下去」：$hits",
            listOf("MainActivity.kt"), hits,
        )
    }

    // ---- ② 签名：默认值不许长回来 ----

    /**
     * ②-a `viewModel: ScheduleViewModel` 后面不许跟 `=`。
     *
     * 与 T69 对 `onOpenStats` 收口同一条理由：默认值挂在真参数上，漏接线时编译器不响、
     * 界面照旧画得出来，只是每次进页都慢一拍——这类账 JVM 单测与真机都很难归因。
     */
    @Test
    fun viewModelParameterHasNoSilentDefault() {
        val screen = blankComments(source(STATS_SCREEN))
        val parameter = Regex("""viewModel: ScheduleViewModel(\s*=[^,)]*)?""").find(screen)
        check(parameter != null) { "StatsScreen 的参数表里已经没有 viewModel 了：这一页换数据源了，本守卫要跟着改" }
        assertEquals(
            "这颗参数的默认值又长回来了：漏传时编译器不响、每次进页新造一枚 VM 重走加载链（台账 #116）。" +
                "现在的参数是「${parameter.value.trim()}」，默认值那一段是「${parameter.groupValues[1].trim()}」",
            "", parameter.groupValues[1].trim(),
        )
        assertTrue(
            "参数表里这颗的类型不再是 ScheduleViewModel（这一页换数据源了，本守卫要跟着改）：${parameter.value}",
            parameter.value.trim() == "viewModel: ScheduleViewModel",
        )
    }

    /** ②-b 整页不许再出现「本页自造 VM」那对写法：`viewModel(` 与 `ScheduleViewModel.Factory(` */
    @Test
    fun statsPageNeverBuildsItsOwnViewModel() {
        val screen = blankComments(source(STATS_SCREEN))
        assertFalse(
            "统计页里又出现了 viewModel(...) 取 VM 的写法（宿主是 nav entry，就是 #116 的成因）：" +
                screen.lines().filter { it.trim().startsWith("viewModel(") }.joinToString("\n"),
            Regex("""\bviewModel\(""").containsMatchIn(screen),
        )
        assertFalse(
            "统计页里还在自己构造 ScheduleViewModel.Factory：这一页不该知道 VM 是怎么造出来的",
            screen.contains("ScheduleViewModel.Factory("),
        )
        assertFalse(
            "统计页重新 import 了 lifecycle 的 viewModel()：默认值就是它带进来的",
            screen.contains("import androidx.lifecycle.viewmodel.compose.viewModel"),
        )
    }

    // ---- ③ 传的那一枚是 Activity 作用域那枚，不是同类里的随便一枚 ----

    /**
     * ③ 传进去的那枚必须是 Activity 作用域那枚，不是同类里的随便一枚。
     *
     * 这条链有三截，少任何一截「复用」都是空话：`BUAAScheduleApp` 里那份
     * `val viewModel: ScheduleViewModel = viewModel(factory = ...)`（宿主是 Activity 的
     * ViewModelStore）→ 两处 `AppNavHost(viewModel = viewModel, ...)`（窄屏与宽屏各一处）→
     * `AppNavHost` 的形参 `viewModel: ScheduleViewModel` **也不许带默认值**，
     * 否则 `"stats"` 里那句 `viewModel = viewModel` 传的是那颗默认值。
     * 顺带钉"统计页与设置页吃同一枚"：`settings/{section}` 那条是本卡的范本。
     */
    @Test
    fun thePassedViewModelIsTheActivityScopedOne() {
        val activity = blankComments(source(MAIN_ACTIVITY))
        assertEquals(
            "Activity 作用域那枚 viewModel 的定义处数应当恰好一处（多一处就有两枚同类型的热 VM 在飘，" +
                "传哪一枚变成写法问题）：",
            1, Regex("""val viewModel: ScheduleViewModel = viewModel\(""").findAll(activity).count(),
        )
        val definedAt = activity.indexOf("val viewModel: ScheduleViewModel = viewModel(")
        val statsAt = activity.indexOf("composable(\"stats\")")
        assertTrue(
            "composable(\"stats\") 排在那枚 Activity 作用域 VM 的定义之前（定义在第 " +
                "${activity.substring(0, definedAt).count { c -> c == '\n' } + 1} 行、路由在第 " +
                "${activity.substring(0, statsAt).count { c -> c == '\n' } + 1} 行）：这一页吃不到它",
            definedAt in 0 until statsAt,
        )
        // `(?<!val )` 是为了只数形参那一颗：`val viewModel: ScheduleViewModel = viewModel(...)`
        // 是定义、不是形参，它带不带 `=` 由上面那条断言管
        val hostParameter = Regex("""(?<!val )viewModel: ScheduleViewModel(\s*=[^,)]*)?""").findAll(activity).toList()
        assertEquals(
            "MainActivity 里 `viewModel: ScheduleViewModel` 这颗形参只许出现一次（AppNavHost 收的那颗）：" +
                hostParameter.map { it.value.trim() },
            1, hostParameter.size,
        )
        assertEquals(
            "AppNavHost 那颗 viewModel 形参又长回了默认值（统计页传的就不是 Activity 那枚了）：" +
                "「${hostParameter[0].value.trim()}」，默认值那一段是「${hostParameter[0].groupValues[1].trim()}」",
            "", hostParameter[0].groupValues[1].trim(),
        )
        val hosts = invocationArgumentLists(activity, "AppNavHost")
        assertEquals("AppNavHost 的调用点数应当恰好两处（窄屏 + 宽屏导航栏两套布局）：${hosts.size}", 2, hosts.size)
        for (args in hosts) {
            assertTrue(
                "有一处 AppNavHost 没把 Activity 那枚 VM 传下去（统计页在它的 NavHost 里，漏传就是" +
                    "那一套布局下这一页又自造一枚）：$args",
                Regex("""viewModel\s*=\s*viewModel,""").containsMatchIn(args),
            )
        }
        assertEquals(
            "把 Activity 那枚显式传下去的调用点处数应当恰好 9（两处 AppNavHost + 首页两页签 + 导入 +" +
                "管理 + 设置根页 + settings/{section} + 本页）。少一处就是有一页又回到自造 VM，" +
                "多一处则要来看这一页该不该算进共用名单：",
            9, Regex("""viewModel\s*=\s*viewModel,""").findAll(activity).count(),
        )
    }

    // ---- ④ 复用之后不许顺手拆掉 T74 的防线 ----

    /**
     * ④-a Loading 那一面与它的判据都还在。
     *
     * 复用 VM 之后这一档在装机上基本看不见（改后 19 次进入 0 次亮、录屏 36 帧 0 帧是它），
     * "看不见"很容易被当成"可以删"——但进程被杀后重建、磁盘慢时首帧照样是 `initialValue`，
     * 那时它仍是唯一一句真话（台账 #115 的账没销）。
     */
    @Test
    fun loadingArmSurvivesTheViewModelReuse() {
        val screen = blankComments(source(STATS_SCREEN))
        assertTrue(
            "Loading 那一面被摘了：复用 VM 之后它确实难见到，但首帧吃 initialValue 那条路还在",
            Regex("""StatsPageStage\.Loading -> CenteredStatsCard \{ StatsLoadingCard\(\) \}""").containsMatchIn(screen),
        )
        assertEquals("statsPageStageOf 的调用点数应当恰好一处：", 1, Regex("statsPageStageOf\\(").findAll(screen).count())
        assertTrue(
            "判据不再吃 uiState.loading：改前正是「只看条数不看就绪」把两档并成一档（#115）",
            Regex("""ready\s*=\s*!state\.loading""").containsMatchIn(screen),
        )
    }

    /** ④-b Sharing 口径没被顺手改：本卡只换 VM 的作用域，不动 `uiState` 的对外形状与 Sharing */
    @Test
    fun sharingPolicyOfUiStateIsUntouched() {
        val vm = blankComments(source(SCHEDULE_VIEW_MODEL))
        val at = vm.indexOf("val uiState = combine(")
        check(at >= 0) { "ScheduleViewModel 里已经没有 `val uiState = combine(`：uiState 换写法了，本守卫要跟着改" }
        // 不能按"下一个空行"切段：blankComments 把注释抹成空格，注释行读起来就是空行。
        // 改成锚在 stateIn 上取一小窗（463-467 那段），Sharing 口径就写在那三行里
        val stateInAt = vm.indexOf("stateIn(", at)
        check(stateInAt in at until at + 3_000) {
            "uiState 之后 3000 字符内找不到 stateIn：属性写法换过了，本守卫要跟着改"
        }
        val property = vm.substring(stateInAt, minOf(stateInAt + 220, vm.length))
        // stateIn 之前那一段是这条链的求值线程与去重口径，同样不在本卡范围内
        val upstream = vm.substring(at, stateInAt)
        assertEquals(
            "uiState 的 WhileSubscribed(5_000) 处数应当恰好一处（改数值、换成 WhileShared/Eagerly" +
                "或整条摘掉都不在本卡范围内）：",
            1, Regex("""SharingStarted\.WhileSubscribed\(5_000\)""").findAll(property).count(),
        )
        assertTrue(
            "uiState 的 initialValue 不再是 ScheduleUiState()：T74/T75 两卡的判据都建立在" +
                "「首帧可能是 loading=true 的空值」之上，换掉它要另开一张卡核",
            Regex("""initialValue\s*=\s*ScheduleUiState\(\)""").containsMatchIn(property),
        )
        assertTrue(
            "uiState 不再经 flowOn(Dispatchers.Default) + distinctUntilChanged：本卡不动这条链的求值线程",
            upstream.contains("flowOn(Dispatchers.Default)") && upstream.contains("distinctUntilChanged()"),
        )
    }

    // ---- 靶子定位与词法小工具（与 StatsPageStageWiringGuardTest / StatsEntryWiringGuardTest 同族）----

    private fun source(relative: String): String {
        val file = File(findMainJavaDir(), relative)
        assertTrue("找不到 $relative：挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText()
    }

    /** 每一次**调用**（`Name(`）的实参表；`fun Name(` 那枚声明跳过，一个命中都没有就抛 */
    private fun invocationArgumentLists(code: String, name: String): List<String> {
        val out = ArrayList<String>()
        for (match in Regex("""\b${Regex.escape(name)}\(""").findAll(code)) {
            val at = match.range.first
            if (code.substring(0, at).trimEnd().endsWith("fun")) continue
            out += balancedArguments(code, match.range.last)
        }
        check(out.isNotEmpty()) { "一个 $name 调用点都没有：靶子没了，本守卫要重新核" }
        return out
    }

    /** 从 [open] 那枚左括号起配平到与之匹配的右括号（含两端） */
    private fun balancedArguments(code: String, open: Int): String {
        var depth = 0
        for (index in open until code.length) {
            when (code[index]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return code.substring(open, index + 1)
                }
            }
        }
        throw IllegalStateException("第 $open 个字符之后的左括号没配平：${code.substring(open, minOf(open + 60, code.length))}")
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
        const val STATS_SCREEN = "com/buaa/schedule/ui/stats/StatsScreen.kt"
        const val SCHEDULE_VIEW_MODEL = "com/buaa/schedule/ui/ScheduleViewModel.kt"
    }
}
