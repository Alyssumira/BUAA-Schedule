package com.buaa.schedule.ui.home

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T131 接线守卫：病不在内核（内核有自己的表驱动单测 [ConflictShiftWeekScopeTest]），
 * 在**这一枚按钮按下去以后到底写没写那几周**。这条链上一旦断线就是静默的：
 * 库里照样写成功、界面照样念「已应用」，只是**无辜的周次也被挪走了**，
 * 用户从任何一屏都看不出来（那几周本来排得好好的）。
 *
 * 刀法照抄 [com.buaa.schedule.ui.stats.StatsT82WiringGuardTest]：读源文件文本、
 * 匹配前先 `blankComments` 抹注释（本卡 KDoc 里就写着被禁的写法本身）、
 * 函数体按花括号配平取、锚点找不到就抛、不用 assumeTrue 跳过。
 *
 * 钉六档：
 * 1. `applyConflictShift` 体内那记 `copy` 必须**同时**带 `periods` 与 `weeks`
 *    —— 只带 `periods` 就是 T131 之前那记空枪（`updateCourse` 第三判恒假）；
 * 2. 作用域判据全仓恰好取一次，且取它的那一处就在 `applyConflictShift` 体内
 *    （"返回布尔后两页各判一次"在本仓算违反，两串周次也绝不允许各交各的）；
 * 3. 两枚入口（首页横幅、统计页冲突卡）继续走**同一份** `applyConflictShift`，
 *    且各自都把 `group.weeks` 交出去，页内不许自己 `updateCourse`；
 * 4. 旗标与作用域成对：`CourseSaveOptions(partialWeeks = true)` 与 `weeks = scopedWeeks`
 *    必须同现（只留旗标 = 空枪；只改 weeks 不交旗标 = 走不到拆行那一支）；
 * 5. 「只改这些周」那句文案与真收窄**同生同死**：文案在而作用域不在，就是名实不副；
 * 6. 前置未落地的扳机：建议侧 `suggestNearestFreeShift` 的 blockers 仍按 target 的
 *    **全部**周次筛（over-constrained 是安全的一侧）。谁把它收窄而未同时收窄写入，
 *    就会给不冲突的周次造出新冲突 —— 那一格本卡明令不许动。
 */
class ConflictShiftWeekScopeWiringGuardTest {

    // ---- ① 那记 copy 必须带上 weeks ----

    @Test
    fun theShiftWriteCarriesTheWeekScopeAlongWithThePeriods() {
        val code = blankComments(source(WIZARD))
        val body = functionBody(code, APPLY_SIGNATURE, "applyConflictShift")
        assertTrue(
            "`applyConflictShift` 体内那记 copy 不见了：作用域没地方带",
            body.contains(COPY_WITH_WEEKS),
        )
        assertFalse(
            "落库又退成只改 `periods`（T131 之前那记空枪）：`original` 是按 course.id 读回来的那一行，" +
                "weeks 原封不动 ⇒ `updateCourse` 的第三判 `original.weeks != course.weeks` 恒假 ⇒ " +
                "partialWeeks 旗标空转、整门课的全部周次一起被挪走，而按钮写着「只改这些周」。\n" +
                "复算：grep -n \"original.weeks != course.weeks\" app/src/main/java/com/buaa/schedule/ui/ScheduleViewModel.kt",
            body.contains("copy(periods = newPeriods)"),
        )
    }

    // ---- ② 判据只取一次，而且就取在落库那一步 ----

    @Test
    fun weekScopeIsJudgedExactlyOnceAndInsideTheWrite() {
        val code = blankComments(source(WIZARD))
        val body = functionBody(code, APPLY_SIGNATURE, "applyConflictShift")
        assertTrue(
            "`applyConflictShift` 没取作用域判据了：那几周由谁算的？",
            body.contains(JUDGE_CALL),
        )
        // 全仓（main）只许这一处取判据：两页各判一次就是两份能各自写错的账
        val hits = mainFilesWith("ConflictShiftWeekScope.weeksToShift(")
        assertEquals(
            "作用域判据在 main 里被取了 " + hits.joinToString { "${it.first}:${it.second}" } +
                " 处，应当恰好一处（就在 applyConflictShift 体内）。" +
                "长出第二处 = 有人另判了一次，两串周次就能各交各的",
            1,
            hits.size,
        )
        // 递进去的第二串必须是 target.weeks：拿组周当 target 周次就等于没求交集
        assertTrue(
            "`weeksToShift` 的第二个参数不再是 target 自己的周次：交集那一档就没了",
            Regex("""weeksToShift\(\s*groupWeeks,\s*target\.weeks\s*\)""").containsMatchIn(body),
        )
    }

    // ---- ③ 两枚入口同源，且都把这一组的周次交出去 ----

    @Test
    fun bothEntriesShareTheSameWriteAndHandOverTheGroupWeeks() {
        for (relative in listOf(HOME_SCREEN, STATS_SCREEN)) {
            val code = blankComments(source(relative))
            val hits = allOccurrences(code, "applyConflictShift(")
            assertEquals(
                "$relative 里 `applyConflictShift(` 有 ${hits.size} 处，应当恰好一处：" +
                    "冲突的处置落库全站一份（T82），作用域判据也在那一处（T131）",
                1,
                hits.size,
            )
            assertTrue(
                "$relative 的 onApplyShift 没把这一组的周次 `group.weeks` 交出去：" +
                    "少了这一串，写点就只能拿 target 的全部周次当作用域 ⇒ 又回到整行覆盖",
                Regex("""onApplyShift\s*=\s*\{\s*target,\s*newPeriods,\s*groupWeeks\s*->""")
                    .containsMatchIn(code),
            )
            // 那一枚 lambda 自己不许夹带写库：HomeScreen 另有拖拽那条 updateCourse（:424-442），
            // 那是「整门课都挪」的另一件事，本卡不动它，所以只掐向导这一支
            val at = code.indexOf("onApplyShift")
            assertTrue("$relative 找不到 onApplyShift 那一句", at >= 0)
            val lambda = code.substring(at, minOf(at + 200, code.length))
            assertFalse(
                "$relative 在 onApplyShift 里自己调了 `updateCourse`：viewModelScope / join / " +
                    "作用域判据三条账全在 applyConflictShift 那一处，抄一份就是三件能各自写错的事",
                lambda.contains("updateCourse("),
            )
        }
        // 向导那一行递给回调的第三串也得是 group.weeks（不是 target.weeks、不是组周的和）
        val wizard = blankComments(source(WIZARD))
        assertTrue(
            "ConflictGroupRow 那一次点击没把 `group.weeks` 递给回调：行头「第 N 周」念的与写进去的会分家",
            wizard.contains("onApplyShift(target, suggestion.periods, group.weeks)"),
        )
    }

    // ---- ④ 旗标与作用域成对 ⑤ 文案与作用域同生同死 ----

    @Test
    fun thePartialWeeksFlagAndTheCopyAndTheCopywritTravelTogether() {
        val code = blankComments(source(WIZARD))
        val body = functionBody(code, APPLY_SIGNATURE, "applyConflictShift")
        val hasFlag = body.contains("CourseSaveOptions(partialWeeks = true)")
        val hasScope = body.contains("weeks = scopedWeeks")
        assertEquals(
            "`partialWeeks = true` 与 `weeks = scopedWeeks` 必须同现同缺（flag=\$hasFlag, scope=\$hasScope）：" +
                "只留旗标是 T131 之前那记空枪（第三判恒假）；只改 weeks 不交旗标就根本走不到 " +
                "`updateCoursePartialWeeks`，收窄等于零",
            true,
            hasFlag && hasScope,
        )
        val promisesScope = code.contains("只改这些周")
        assertEquals(
            "按钮那句「只改这些周」与真收窄不同步（文案在=\$promisesScope, 作用域在=\$hasScope）：" +
                "要么把文案改成名实相符的口径（整门课都挪），要么把作用域带回来，不许一半一半",
            promisesScope,
            hasScope,
        )
    }

    // ---- ⑥ 内核纯度：零 android import、零时钟读；周次一律当参数传 ----

    @Test
    fun theKernelStaysPureJvmAndTakesBothWeekListsAsParameters() {
        val code = blankComments(source(KERNEL))
        for (banned in listOf(
            "import android", "import androidx", "import java.time", "import kotlin.time",
            "SystemClock", "currentTimeMillis", "nanoTime", "LocalDate", "LocalDateTime",
            "Clock", "Date(", "viewModel", "repository",
        )) {
            assertFalse(
                "$KERNEL 里出现了「$banned」：判据自己去读设备/时钟/仓储，这张表在 JVM 里就打不开，" +
                    "而组周与 target.weeks 按口径只能由调用点当参数传（本仓硬口径）",
                code.contains(banned),
            )
        }
        assertTrue(
            "weeksToShift 的签名不收两串周次了：那一判就没人递参数、只能自己去课表里嗅",
            Regex("""weeksToShift\(\s*groupWeeks: List<Int>,\s*targetWeeks: List<Int>\s*\)""")
                .containsMatchIn(code),
        )
    }

    // ---- ⑦ 前置扳机：建议侧的 blockers 不许先于写入收窄 ----

    @Test
    fun suggestionStillScansAllOfTheTargetsWeeks() {
        val code = blankComments(source(RESOLUTION))
        assertTrue(
            "`suggestNearestFreeShift` 的 blockers 不再按 target 的**全部**周次筛了。\n" +
                "复算：grep -n \"other.weeks.any { it in target.weeks }\" " +
                "app/src/main/java/com/buaa/schedule/domain/schedule/CourseConflictResolution.kt\n" +
                "本卡明令不许动这一句：建议侧收窄而写入侧也收窄，就会把课挪进「只在被排除的那些周里」" +
                "才成立的空位" +
                "—— 那些周照样在库里（target 的原行还留着它们），于是一门课拆出两半撞在一起，" +
                "造出用户根本没点过的冲突。保持 over-constrained 才是安全的一侧。",
            code.contains("other.weeks.any { it in target.weeks }"),
        )
    }

    // ---------------- helpers ----------------

    private fun source(relative: String): String = File(findMainJavaDir(), relative).readText()

    /** 全仓 main 里扫某串文本，返回 (相对路径, 命中次数) 只挑出有命中的文件 */
    private fun mainFilesWith(needle: String): List<Pair<String, Int>> {
        val root = findMainJavaDir()
        val hits = mutableListOf<Pair<String, Int>>()
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val count = allOccurrences(blankComments(file.readText()), needle).size
            if (count > 0) hits += file.relativeTo(root).path.replace('\\', '/') to count
        }
        return hits
    }

    private fun allOccurrences(hay: String, needle: String): List<Int> {
        val found = mutableListOf<Int>()
        var at = hay.indexOf(needle)
        while (at >= 0) {
            found += at
            at = hay.indexOf(needle, at + needle.length)
        }
        return found
    }

    /** 从签名那句起按花括号配平取函数体（含签名自己）；锚点找不到就抛，不静默跳过 */
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

    private companion object {
        const val WIZARD = "com/buaa/schedule/ui/home/ConflictWizardDialog.kt"
        const val KERNEL = "com/buaa/schedule/ui/home/ConflictShiftWeekScope.kt"
        const val HOME_SCREEN = "com/buaa/schedule/ui/home/HomeScreen.kt"
        const val STATS_SCREEN = "com/buaa/schedule/ui/stats/StatsScreen.kt"
        const val RESOLUTION = "com/buaa/schedule/domain/schedule/CourseConflictResolution.kt"

        const val APPLY_SIGNATURE = "suspend fun applyConflictShift("
        // T133 起这一行还带 `isManualOverride = true`（不标就会在下次教务刷新被整行冲掉，
        // 逐字钉子跟着挪家；那一维单独由 ManualTimeOverrideWiringGuardTest 钉，本文件只管周次）
        const val COPY_WITH_WEEKS = "target.copy(periods = newPeriods, weeks = scopedWeeks, isManualOverride = true)"
        const val JUDGE_CALL = "ConflictShiftWeekScope.weeksToShift("
    }
}
