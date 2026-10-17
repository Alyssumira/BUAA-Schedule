package com.buaa.schedule.data.import

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T401③：教务导入闸门的**源码形状**守卫 —— 「覆盖导入的进料口必须吃 [BuaaWeekShellPolicy] 这道判据」，
 * 以及三条在册防线与两处硬边界不许被下一枚卡改弱。
 *
 * 为什么这一格得有尺：判据本体是纯 JVM 内核，表驱动单测（`BuaaWeekShellPolicyTest`）只能证明
 * **内核自己**答得对，证不了**产线那一圈在问它**。产线那句一旦退回就地写的 `?.takeIf { … }`，
 * 内核照样全绿、而 `{"code":null,"datas":null}` 那几周照样被覆盖导入静默删掉。
 * 所以本守卫读工作树文本（`app/src/main/java` 整目录已在 `app/build.gradle.kts` 的
 * `guardReadWorkingTreeFiles` 第 ① 项里进 `testDebugUnitTest` 的 inputs ⇒ 改一行注释、class 字节不变
 * 那一档假绿在这里不成立）。
 *
 * ## 钉法逐格自报（计数尺一律**抹注释后再数**，否则守卫把自己钉的旧形原文当成病灶）
 *
 * 1. [gateAsksTheKernelExactlyOnce] —— 逐字节 + 数出现次数（产线调用点）；
 * 2. [oldInlinePredicateCannotComeBack] —— 数出现次数（旧判据原文与其两枚「或」支）＋ 反事实探针；
 * 3. [gateEatsTheDatasCellNotTheArrangedListCell] —— 逐字节 + 数出现次数（硬边界：`isNotEmpty` 在产线零处）；
 * 4. [kernelDecisionTableIsThreeLinesAndIgnoresTheCount] —— 逐字节（三行档位表）+ 数出现次数
 *    （`||` 零处、非判据事实只出现在形参表上）；
 * 5. [kernelIsPureJvmWithNoReverseDependencyOnData] —— 数出现次数（零 android/androidx、零反依赖、
 *    零时钟/权限/设备度量/WebView 上下文）；
 * 6. [theTwoImportGatesStayAtTheirPost] —— 逐字节 + 数出现次数（两枚在岗闸门与那一句全空挡，
 *    不许被扩成「部分空也挡」来代替判据本身）；
 * 7. [theBreakerStillEatsTheSameCounter] —— 数出现次数（熔断与预算常量枚数，丙档不许被摘掉）；
 * 8. [probeRecognisesBothWrongShapes] —— 反事实（朝宽那一形与朝窄那一形各喂给上面那两把尺，
 *    必须数到该数的值 ⇒ 第 3、4 层不是空转）。
 */
class BuaaWeekShellImportGateGuardTest {

    // ─────────────── ① 进料口只许问内核一次 ───────────────

    @Test
    fun gateAsksTheKernelExactlyOnce() {
        val fetcher = code(FETCHER)
        assertEquals(
            "**钉法 = 数出现次数**：产线那一圈问内核恰一处（实到 " + occurrences(fetcher, KERNEL_CALL) +
                " 处）。零处 = 闸门不再吃这道判据，「外壳解得开而里面没数据」那一形随时能回潮；" +
                "两处 = 同一把尺在一份文件里写了两遍，改判据要翻两处",
            1,
            occurrences(fetcher, KERNEL_CALL),
        )
        assertEquals(
            "**钉法 = 逐字节**：放行那一手必须逐字 `$IMPORTABLE_ARM`（实到 " +
                occurrences(fetcher, IMPORTABLE_ARM) + " 处）：闸门唯一的出口是内核交回的档位，" +
                "调用点不许再叠一条自己的条件",
            1,
            occurrences(fetcher, IMPORTABLE_ARM),
        )
        assertEquals(
            "**钉法 = 逐字节**：内核的 import 恰一处（实到 " + occurrences(fetcher, KERNEL_IMPORT) + " 处）",
            1,
            occurrences(fetcher, KERNEL_IMPORT),
        )
    }

    // ─────────────── ② 旧判据原文不许回潮 ───────────────

    @Test
    fun oldInlinePredicateCannotComeBack() {
        val fetcher = code(FETCHER)
        for (needle in listOf(OLD_OR_BRANCHES, OLD_OR_CODE_NULL, OLD_OR_CODE_ZERO)) {
            assertEquals(
                "**钉法 = 数出现次数**：旧判据的那一枚子串 `$needle` 在产线必须 0 处（实到 " +
                    occurrences(fetcher, needle) + " 处）。它一回潮，`it.code == null` 就又能单独放行" +
                    "一枚什么都没有的外壳，而那正是本卡点名的静默删除的进料口",
                0,
                occurrences(fetcher, needle),
            )
        }
        assertEquals(
            "**钉法 = 数出现次数**：`?.takeIf {` 在产线整圈已经归零（实到 " + occurrences(fetcher, OLD_TAKE_IF) +
                " 处）：改前那一手挂在 `?.takeIf` 上，它不在说明判据确实换了宿主，不是在原地改了个措辞",
            0,
            occurrences(fetcher, OLD_TAKE_IF),
        )
    }

    // ─────────────── ③ 闸门吃 datas 那一格，不吃 arrangedList 那一格 ───────────────

    @Test
    fun gateEatsTheDatasCellNotTheArrangedListCell() {
        val fetcher = code(FETCHER)
        assertEquals(
            "**钉法 = 逐字节**：递进内核的第一枚事实必须逐字 `$DATAS_FACT`（实到 " +
                occurrences(fetcher, DATAS_FACT) + " 处）—— 判据问的是「外壳里有没有 datas 那一格」，" +
                "也就是「教务答没答这一周」",
            1,
            occurrences(fetcher, DATAS_FACT),
        )
        assertEquals(
            "**钉法 = 数出现次数**：`arrangedList` 在产线整文件恰三处（实到 " + occurrences(fetcher, ARRANGED_LIST) +
                " 处 = ① 那一枚**非判据事实**的形参名、② 同一行从 DTO 读条数那一手、" +
                "③ 把这一周的课程并进总表那一行）。第四处只可能是「拿 arrangedList 的空与非空当闸门」——" +
                "那是卡面点名的硬边界，合法的空课周（寒暑假 / 停课周 / 台账 #310 那一族）会因此被记成失败",
            3,
            occurrences(fetcher, ARRANGED_LIST),
        )
        assertEquals(
            "**钉法 = 数出现次数**：`isNotEmpty` 在产线必须 0 处（实到 " + occurrences(fetcher, IS_NOT_EMPTY) +
                " 处）：这一格是硬边界最直接的一把尺 —— 闸门一侧一旦长出任何「非空」判据，" +
                "合法空课周那一形（`datas` 在而列表是空表）就从成功翻成失败，" +
                "连续三周这样的周会把整轮熔断掉、`succeeded` 报数一起少",
            0,
            occurrences(fetcher, IS_NOT_EMPTY),
        )
    }

    // ─────────────── ④ 内核的三行档位表逐字节，非判据事实一次都不读 ───────────────

    @Test
    fun kernelDecisionTableIsThreeLinesAndIgnoresTheCount() {
        val kernel = code(KERNEL)
        for (line in listOf(ARM_DATA_GIVEN, ARM_ERROR_ENVELOPE, ARM_NO_DATAS)) {
            assertEquals(
                "**钉法 = 逐字节**：档位表那一行 `$line` 恰一处（实到 " + occurrences(kernel, line) +
                    " 处）。改任何一行都要回来重钉这三枚 —— 判据本体全仓只有这一把尺",
                1,
                occurrences(kernel, line),
            )
        }
        assertEquals(
            "**钉法 = 数出现次数**：内核体内 `||` 必须 0 处（实到 " + occurrences(kernel, OR_OPERATOR) +
                " 处）：改前那枚判据就是靠两枚「或」支把「没数据」洗成「有数据」的；" +
                "档位表不许再用「或」把一枚非判据事实抬成放行理由（KDoc 里引着的那句原文已被抹掉，" +
                "这一格读的是代码那一栏）",
            0,
            occurrences(kernel, OR_OPERATOR),
        )
        assertEquals(
            "**钉法 = 数出现次数**：`arrangedListSize` 在内核里必须只出现在形参表上（实到 " +
                occurrences(kernel, ARRANGED_LIST_SIZE) + " 处 = 声明那一枚）。" +
                "两处就是内核开始读条数了 —— 那正是「合法空课周被判成失败」那一档病的第一步",
            1,
            occurrences(kernel, ARRANGED_LIST_SIZE),
        )
        assertEquals(
            "**钉法 = 逐字节**：放行结论的唯一出口 `$IMPORTABLE_GETTER` 恰一处（实到 " +
                occurrences(kernel, IMPORTABLE_GETTER) + " 处）：闸门放不放行只有一个定义处",
            1,
            occurrences(kernel, IMPORTABLE_GETTER),
        )
        assertEquals(
            "**钉法 = 数出现次数**：`fun tier(` 恰一处（实到 " + occurrences(kernel, TIER_DECL) + " 处）",
            1,
            occurrences(kernel, TIER_DECL),
        )
    }

    // ─────────────── ⑤ 内核是纯 JVM：零 android、零反依赖、零钟零权限零度量 ───────────────

    @Test
    fun kernelIsPureJvmWithNoReverseDependencyOnData() {
        val kernel = code(KERNEL)
        for (needle in listOf(IMPORT_ANDROID, IMPORT_ANDROIDX, IMPORT_DATA_LAYER)) {
            assertEquals(
                "**钉法 = 数出现次数**：`$needle` 在内核里必须 0 处（实到 " + occurrences(kernel, needle) +
                    " 处）。domain 不许反依赖 data，更不许带 android 进 JVM 打表 —— " +
                    "这条边界一旦被顶穿，`BuaaWeekShellPolicyTest` 那五格就只能改跑 Robolectric 或者干脆跑不起来",
                0,
                occurrences(kernel, needle),
            )
        }
        for (needle in listOf(NEEDLE_WEBVIEW, NEEDLE_CONTEXT, NEEDLE_SDK_INT, NEEDLE_CURRENT_TIME, NEEDLE_LOCAL_DATE_NOW)) {
            assertEquals(
                "**钉法 = 数出现次数**：`$needle` 在内核里必须 0 处（实到 " + occurrences(kernel, needle) +
                    " 处）：判据不许读设备、不许读时钟 —— 要 Context 的事一律在调用点当参数注入",
                0,
                occurrences(kernel, needle),
            )
        }
        assertEquals(
            "**钉法 = 数出现次数**：内核文件的 import 语句一枚都没有（实到 " + occurrences(kernel, IMPORT_STATEMENT) +
                " 处）：这一格红了说明内核开始问外面的东西要事实，而事实应当由调用点递进来",
            0,
            occurrences(kernel, IMPORT_STATEMENT),
        )
    }

    // ─────────────── ⑥ 两枚在岗闸门与那句全空挡：不许改弱 ───────────────

    @Test
    fun theTwoImportGatesStayAtTheirPost() {
        val login = code(LOGIN_SCREEN)
        val viewModel = code(VIEW_MODEL)
        val webSession = code(WEB_SESSION)

        assertEquals(
            "**钉法 = 逐字节**：首次导入那一路的闸门 `$LOGIN_FAILED_WEEKS_ARM` 恰一处（实到 " +
                occurrences(login, LOGIN_FAILED_WEEKS_ARM) + " 处）：本卡改的是它的进料口，不是它本身",
            1,
            occurrences(login, LOGIN_FAILED_WEEKS_ARM),
        )
        assertEquals(
            "**钉法 = 逐字节**：静默刷新那一路的闸门 `$VM_INCOMPLETE_ARM` 恰一处（实到 " +
                occurrences(viewModel, VM_INCOMPLETE_ARM) + " 处）：同上",
            1,
            occurrences(viewModel, VM_INCOMPLETE_ARM),
        )
        assertEquals(
            "**钉法 = 数出现次数**：那句「全空才挡」的两枚原句各恰一处 —— 首次导入 `$LOGIN_EMPTY_ARM` 实到 " +
                occurrences(login, LOGIN_EMPTY_ARM) + " 处、静默刷新 `$VM_EMPTY_ARM` 实到 " +
                occurrences(viewModel, VM_EMPTY_ARM) + " 处：本卡不许把它们扩成「部分空也挡」来代替判据本身",
            2,
            occurrences(login, LOGIN_EMPTY_ARM) + occurrences(viewModel, VM_EMPTY_ARM),
        )
        assertEquals(
            "**钉法 = 数出现次数**：「把全空挡改成按条数比大小」那一形 0 处（实到 " +
                occurrences(viewModel, VM_SIZE_COMPARE) + " 处）",
            0,
            occurrences(viewModel, VM_SIZE_COMPARE),
        )
        assertEquals(
            "**钉法 = 逐字节**：`$WEB_SESSION_WIRE` 恰一处（实到 " + occurrences(webSession, WEB_SESSION_WIRE) +
                " 处）：刷新一路的 `failedWeeks` 就是从 `SemesterFetchOutcome` 直递到 `SemesterCourses` 的 —— " +
                "本卡的改动正是顺着这一枚字段穿过闸门的；这一行断了，判据改得再对也到不了屏",
            1,
            occurrences(webSession, WEB_SESSION_WIRE),
        )
    }

    // ─────────────── ⑦ 熔断仍吃同一枚计数（丙档不许被摘掉） ───────────────

    @Test
    fun theBreakerStillEatsTheSameCounter() {
        val fetcher = code(FETCHER)
        for (needle in listOf(BREAKER_INCR, BREAKER_TRIP, FAILED_ADD_WEEK)) {
            assertEquals(
                "**钉法 = 逐字节**：`$needle` 恰一处（实到 " + occurrences(fetcher, needle) +
                    " 处）：「答了而外壳里没有 datas」的那一周走的就是这一圈失败那一支 —— " +
                    "摘掉任何一手，空壳周就从熔断的账上逃掉（丙档），整轮二十周可以一路空成功跑完",
                1,
                occurrences(fetcher, needle),
            )
        }
        assertEquals(
            "**钉法 = 数出现次数**：连败清零那一手 `consecutiveFailures = 0` 恰两处（实到 " +
                occurrences(fetcher, RESET_COUNT) + " 处 = 声明处初始化 + 只有真拿到数据才清零）。" +
                "第三处就是有人把清零搬到判据外面了",
            2,
            occurrences(fetcher, RESET_COUNT),
        )
        for (budget in listOf(BUDGET, BACKOFF)) {
            assertEquals(
                "**钉法 = 数出现次数**：预算常量 `$budget` 全文件恰三处（声明 + 两圈各一处；实到 " +
                    occurrences(fetcher, budget) + " 处）：本卡不新开预算、也不在日志句里引用它 —— " +
                    "这一枚与在册 SemesterInfoRetryGuardTest 第 ④ 层同尺，两处一起钉着才不算散文",
                3,
                occurrences(fetcher, budget),
            )
        }
        assertEquals(
            "**钉法 = 逐字节**：那一圈的圈头逐字 `$COURSES_LOOP` 恰一处（实到 " +
                occurrences(fetcher, COURSES_LOOP) + " 处）：在册守卫也钉这一枚，本卡改动不许换圈形",
            1,
            occurrences(fetcher, COURSES_LOOP),
        )
    }

    // ─────────────── ⑧ 反事实：两枚探测器都认得那两种错形状 ───────────────

    @Test
    fun probeRecognisesBothWrongShapes() {
        // 朝宽那一形 = 改前判据原文（本卡点名的静默删除的进料口）
        val wide = PRE_FIX_SNIPPET
        assertTrue(
            "探测器认得朝宽那一形：旧判据原文里 `$OLD_OR_CODE_NULL` 必须数到 1（实到 " +
                occurrences(wide, OLD_OR_CODE_NULL) + " 处）。数到 0 说明第 ② 层的针拼错了，" +
                "那句「旧判据 0 处」就成了空转的假绿",
            occurrences(wide, OLD_OR_CODE_NULL) == 1 && occurrences(wide, OLD_TAKE_IF) == 1,
        )
        assertEquals(
            "探测器认得朝宽那一形里没有内核（实到 " + occurrences(wide, KERNEL_CALL) +
                " 处）：第 ① 层那一枚「恰一处」不是恒真",
            0,
            occurrences(wide, KERNEL_CALL),
        )
        // 朝窄那一形 = 卡面点名的硬边界（拿 arrangedList 的非空当闸门）
        val narrow = PRE_NARROW_SNIPPET
        assertEquals(
            "探测器认得朝窄那一形：`$IS_NOT_EMPTY` 必须数到 1（实到 " + occurrences(narrow, IS_NOT_EMPTY) +
                " 处）。数到 0 说明第 ③ 层那枚「0 处」是拿空文本量的",
            1,
            occurrences(narrow, IS_NOT_EMPTY),
        )
        assertEquals(
            "探测器认得朝窄那一形：把闸门挪到条数上之后，产线第 ③ 层那枚逐字节针 `$DATAS_FACT` 就数不到了" +
                "（实到 " + occurrences(narrow, DATAS_FACT) + " 处）—— 说明第 ③ 层那句「恰一处」不是恒真",
            0,
            occurrences(narrow, DATAS_FACT),
        )
        // 如实点名一枚尺子的盲点：朝窄那一形的 `arrangedList` 枚数与产线**相等**（都是 3 处），
        // 数出现次数那一把尺看不出手艺换了；看得见的是「非空」那一枚针（上面已钉 1 处）与
        // 逐字节的 `datasPresent` 那一枚。两把尺要分开取，理由就在这一格。
        assertEquals(
            "朝窄那一形里 `arrangedList` 枚数不变（实到 " + occurrences(narrow, ARRANGED_LIST) +
                " 处 = 产线第 ③ 层钉的那个数）：枚数尺对这一档病是瞎的，别拿它当唯一防线",
            3,
            occurrences(narrow, ARRANGED_LIST),
        )
    }

    // ---- 读盘与小工具 ------------------------------------------------------------------------------

    /** 相对 `app/src/main/java` 读那枚文件，并**抹注释保行号**后交出（本守卫所有计数尺都只读这一栏） */
    private fun code(relative: String): String = blankCommentsKeepingLiterals(raw(relative))

    private fun raw(relative: String): String {
        val file = File(findMainJavaDir(), relative)
        assertTrue("读不到 $relative：这一族守卫的覆盖面就是这几枚文件，挪过家要回来改这里", file.isFile)
        return file.readText().replace("\r\n", "\n")
    }

    private fun occurrences(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var from = 0
        var hits = 0
        while (true) {
            val at = haystack.indexOf(needle, from)
            if (at < 0) return hits
            hits++
            from = at + needle.length
        }
    }

    /**
     * 抹注释保行号、字符串不动：与同族那几枚守卫同一写法（`BuaaFirstWeekPolicyGuardTest` 那一族）。
     * 本卡尤其需要抹注释：内核的段首逐字引着**改前那一形**的原文，产线那一段注释也逐句点名它，
     * 不抹的话第 ② 层会把自己钉的病灶当成回潮。
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
        const val FETCHER = "com/buaa/schedule/data/import/BuaaInPageFetcher.kt"
        const val KERNEL = "com/buaa/schedule/domain/importing/BuaaWeekShellPolicy.kt"
        const val LOGIN_SCREEN = "com/buaa/schedule/ui/importing/BuaaLoginScreen.kt"
        const val VIEW_MODEL = "com/buaa/schedule/ui/ScheduleViewModel.kt"
        const val WEB_SESSION = "com/buaa/schedule/data/import/BuaaWebSession.kt"

        // ① 产线调用点
        const val KERNEL_CALL = "BuaaWeekShellPolicy.tier("
        const val IMPORTABLE_ARM = "if (tier.importable) parsed = shell else noDataTier = tier"
        const val KERNEL_IMPORT = "import com.buaa.schedule.domain.importing.BuaaWeekShellPolicy"

        // ② 旧判据
        const val OLD_OR_BRANCHES = "it.datas != null || it.code == null || it.code == \"0\""
        const val OLD_OR_CODE_NULL = "|| it.code == null"
        const val OLD_OR_CODE_ZERO = "|| it.code == \"0\""
        const val OLD_TAKE_IF = "?.takeIf {"

        // ③ 硬边界
        const val DATAS_FACT = "datasPresent = shell.datas != null,"
        const val ARRANGED_LIST = "arrangedList"
        const val IS_NOT_EMPTY = "isNotEmpty"

        // ④ 内核档位表
        const val ARM_DATA_GIVEN = "datasPresent -> WeekShellTier.DataGiven"
        const val ARM_ERROR_ENVELOPE = "code != null && code != SUCCESS_CODE -> WeekShellTier.ErrorEnvelope"
        const val ARM_NO_DATAS = "else -> WeekShellTier.ShellWithoutDatas"
        const val OR_OPERATOR = "||"
        const val ARRANGED_LIST_SIZE = "arrangedListSize"
        const val IMPORTABLE_GETTER = "val importable: Boolean get() = this == DataGiven"
        const val TIER_DECL = "fun tier("

        // ⑤ 纯度
        const val IMPORT_ANDROID = "import android"
        const val IMPORT_ANDROIDX = "import androidx"
        const val IMPORT_DATA_LAYER = "import com.buaa.schedule.data"
        const val IMPORT_STATEMENT = "import "
        const val NEEDLE_WEBVIEW = "WebView"
        const val NEEDLE_CONTEXT = "Context"
        const val NEEDLE_SDK_INT = "SDK_INT"
        const val NEEDLE_CURRENT_TIME = "System.currentTimeMillis"
        const val NEEDLE_LOCAL_DATE_NOW = "LocalDate.now"

        // ⑥ 两枚在岗闸门
        const val LOGIN_FAILED_WEEKS_ARM = "outcome.failedWeeks.isNotEmpty()"
        const val VM_INCOMPLETE_ARM = "!fetched.isComplete"
        const val LOGIN_EMPTY_ARM = "outcome.courses.isEmpty()"
        const val VM_EMPTY_ARM = "fetched.courses.isEmpty()"
        const val VM_SIZE_COMPARE = "fetched.courses.size <"
        const val WEB_SESSION_WIRE = "SemesterCourses(semester, domain, warnings, outcome.failedWeeks)"

        // ⑦ 熔断与预算
        const val BREAKER_INCR = "consecutiveFailures++"
        const val BREAKER_TRIP = "if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES)"
        const val FAILED_ADD_WEEK = "failed += week"
        const val RESET_COUNT = "consecutiveFailures = 0"
        const val BUDGET = "MAX_ATTEMPTS_PER_WEEK"
        const val BACKOFF = "RETRY_DELAY_MS"
        const val COURSES_LOOP = "while (parsed == null && attempt < MAX_ATTEMPTS_PER_WEEK)"

        /** 朝宽那一形的原文（只喂给探测器自己，产线里它必须 0 处） */
        val PRE_FIX_SNIPPET = """
            runCatching { json.decodeFromString<BuaaScheduleResponse>(raw) }.getOrNull()
                ?.takeIf { it.datas != null || it.code == null || it.code == "0" }
        """.trimIndent()

        /** 朝窄那一形（卡面点名的硬边界：拿条数当闸门 ⇒ 合法空课周翻成失败） */
        val PRE_NARROW_SNIPPET = """
            val tier = BuaaWeekShellPolicy.tier(
                datasPresent = shell.datas?.arrangedList?.isNotEmpty() == true,
                arrangedListSize = shell.datas?.arrangedList?.size,
                code = shell.code,
            )
        """.trimIndent()
    }
}
