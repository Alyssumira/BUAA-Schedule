package com.buaa.schedule.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T52④ 的守卫：课程卡 → 编辑器的共享元素，**四条入口都得接上，且只有一处键的写法**。
 *
 * 修之前的现状（读码结论，不是猜的）：`course_${id}` 这个键在四个文件里各抄了一遍，
 * 抄到第五处就漏了——今日课表的列表模式（CourseTimelineCard）与时间轴模式（色块）
 * 两处都没注册共享元素，于是从今日页点开一节课只有整页淡入淡出，
 * 从周课表点同一节课却是卡片飞进编辑器。
 *
 * 刀法照抄 [com.buaa.schedule.ui.home.DayTimelineStructureGuardTest]：抹注释、
 * 按文件扫计数、靶子一个都没扫到也判红、找不到源码目录直接抛。
 */
class CourseSharedElementGuardTest {

    @Test
    fun keyIsWrittenInExactlyOnePlace() {
        // 扫整棵主源码树，而不是一个写死的清单：新增一处副本恰恰是我们要抓的事
        val root = findMainJavaDir()
        val offenders = mutableListOf<Pair<String, Int>>()
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val n = Regex("""rememberSharedContentState\(\s*key\s*=\s*"course_""")
                .findAll(blankComments(file.readText()))
                .count()
            if (n > 0) offenders += file.relativeTo(root).path.replace('\\', '/') to n
        }
        assertTrue(
            "rememberSharedContentState(key = \"course_…\") 只许出现在 CourseSharedElement.kt 里，" +
                "多一处副本就是下一次漏接的来源（这族键抄到第五处就漏了两处）：\n" +
                offenders.joinToString("\n") { "${it.first} = ${it.second}" },
            offenders.size == 1 && offenders[0].first == HELPER && offenders[0].second == 1,
        )
    }

    /** 收口之后：四个入口全部走同一个 helper，且日视图两种模式各有一处 */
    @Test
    fun everyCourseEntryRegistersThroughTheHelper() {
        val expected = mapOf(
            DAY_VIEW to 2,     // 列表模式的卡 + 时间轴的色块
            WEEK_VIEW to 1,    // 周视图课程格
            COURSE_MANAGEMENT to 1,
            EDITOR to 1,
        )
        val report = expected.map { (file, want) ->
            val got = Regex("courseSharedElementModifier\\(").findAll(blankComments(source(file))).count()
            file to (got to want)
        }
        val bad = report.filter { (it.second.first) < it.second.second }
        assertTrue(
            "课程卡入口少接一处共享元素，从那一屏点开课就只有整页淡入淡出：" +
                bad.joinToString("; ") { "${it.first} 期望 ≥${it.second.second} 处，实际 ${it.second.first}" },
            bad.isEmpty(),
        )
    }

    /** 宿主侧的两个 Local 还在：helper 拿不到它们会静默退成空修饰符，那时候这里该红 */
    @Test
    fun hostStillProvidesBothLocals() {
        val main = blankComments(source(MAIN_ACTIVITY))
        assertTrue("MainActivity 不再包 SharedTransitionLayout：共享元素全线失效", main.contains("SharedTransitionLayout("))
        assertTrue("LocalSharedTransitionScope 不再供出去", main.contains("LocalSharedTransitionScope provides"))
        val homeProvides = Regex("composable\\(\"home\"\\)[\\s\\S]{0,200}LocalAnimatedVisibilityScope provides this")
            .containsMatchIn(main)
        assertTrue("首页 destination 不再供 AnimatedVisibilityScope：从今日页点卡就没有转场作用域", homeProvides)
    }

    /** helper 自己：两个 Local 缺任一个 must 退成原样（扩展写法下 this 就是"空修饰符"），而不是 NPE / 静默注册半条链 */
    @Test
    fun helperDegradesWhenLocalsAreMissing() {
        val code = blankComments(source(HELPER))
        // ④ 之后 helper 是 Modifier 扩展（compose lint 判红非扩展的 modifier 工厂），
        // 退化时原样交还接收者：语义与旧版 `?: return Modifier` 逐字等价
        assertTrue(
            "helper 要对两个 Local 都做 ?: return this（引导页那一支不是 NavHost 的 destination）",
            code.contains("LocalSharedTransitionScope.current ?: return this") &&
                code.contains("LocalAnimatedVisibilityScope.current ?: return this"),
        )
        assertTrue("courseId 为 null（新增课程）时也要退成原样", code.contains("courseId ?: return this"))
    }

    private fun source(relative: String): String {
        val file = File(findMainJavaDir(), relative)
        assertTrue("找不到 $relative：挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText()
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

    /** 把 `//` 与 `/* */` 注释抹成空格（字符串保留、长度与换行位置不变） */
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

    private companion object {
        const val HELPER = "com/buaa/schedule/ui/CourseSharedElement.kt"
        const val DAY_VIEW = "com/buaa/schedule/ui/home/DayView.kt"
        const val WEEK_VIEW = "com/buaa/schedule/ui/home/WeekView.kt"
        const val COURSE_MANAGEMENT = "com/buaa/schedule/ui/course/CourseManagementScreen.kt"
        const val EDITOR = "com/buaa/schedule/ui/editor/CourseEditorScreen.kt"
        const val MAIN_ACTIVITY = "com/buaa/schedule/MainActivity.kt"
    }
}
