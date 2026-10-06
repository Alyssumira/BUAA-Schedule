package com.buaa.schedule.data.repository

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 后台路径（广播 / 服务 / 组件）取 Repository 只许走 `Context.scheduleRepository()` 一个入口。
 *
 * 钉的是 ai/T169 第 1 件：改前三枚调用点各自 `new` 了一枚 Repository ——
 * `reminder/ReminderReceiver`（课前提醒）、`reminder/TomorrowPreviewReceiver`（明日预告）
 * 直接绕开入口裸构造，`widget/WidgetDataSynchronizer.sync` 则把入口里那段兜底**逐字抄了一份**。
 *
 * 代价不是"看着不整齐"：每 new 一枚就多 5 枚 DAO 代理，而且 `ScheduleRepository` 那把
 * `writeMutex`（R5 F-34 的串行写锁）是**实例级**的 —— 第二枚实例带着第二把锁，
 * "全仓唯一一把写锁"这个前提在那两条闹钟链上根本不成立。
 *
 * 本模块单测没有 Robolectric（android.jar 里全是抛 "not mocked" 的桩），跑不了真的构造，
 * 所以按结构核对主源码：抹掉注释后全仓数构造点，并要求那一枚正好落在兜底函数自己体内。
 * 定位源码的办法与 `LaunchRequestWiringGuardTest` / `ClassProgressReceiverMainThreadTest`
 * 一致；找不到目录就直接抛，不用 assumeTrue 跳过 —— 找错路径只表现为"永远是绿的"，比红更糟。
 */
class BackgroundRepositoryEntryPointGuardTest {

    /**
     * ① 全仓 `ScheduleRepository(AppDatabase...)` 的裸构造只许有**一枚**，
     * 而且必须是 `scheduleRepository()` 自己体内那个兜底。
     *
     * 这一条同时挡两头的退化：有人把闹钟链换回裸构造（枚数变 2+），
     * 以及有人把兜底本身拆掉、让非 Application 的 Context 直接拿到 null。
     */
    @Test
    fun onlyTheEntryPointStillConstructsARepository() {
        val hits = mainSources().flatMap { source ->
            BARE_CONSTRUCTION.findAll(source.code).map { source.path to it.range.first }.toList()
        }.sortedBy { it.first }

        assertEquals(
            "后台路径自己 new Repository 的地方只能有兜底那一枚（每多一枚 = 多一层 DAO 包装 + " +
                "多一把独立的 writeMutex）：\n" +
                hits.joinToString("\n") { "${it.first} 偏移 ${it.second}" },
            1,
            hits.size,
        )

        val (path, offset) = hits.single()
        assertEquals("唯一那枚裸构造不在兜底函数所在的文件里：", ENTRY_POINT_FILE, path)
        val code = blankComments(source(ENTRY_POINT_FILE))
        val declarationAt = code.indexOf(ENTRY_POINT_DECLARATION)
        assertTrue(
            "找不到 `fun Context.scheduleRepository()`：入口改了名的话，本守卫与它钉的那条兜底要一起跟着改",
            declarationAt >= 0,
        )
        assertTrue(
            "唯一那枚裸构造跑到了 scheduleRepository() 外面 —— 兜底只许住在这条函数体里",
            offset > declarationAt,
        )
    }

    /**
     * ② 三枚改点必须真的在调用入口，而不是"删掉了构造但也没取 Repository"。
     *
     * 逐枚点名：这条断言红了说明那一枚又退回自己装配（或整段被顺手删掉）。
     */
    @Test
    fun theThreeBackgroundCallersGoThroughTheEntryPoint() {
        for (path in listOf(REMINDER_RECEIVER, TOMORROW_PREVIEW_RECEIVER, WIDGET_DATA_SYNCHRONIZER)) {
            val code = blankComments(source(path))
            val calls = CALL_SITE.findAll(code).count()
            assertEquals(
                "$path 里 `scheduleRepository()` 的调用次数是 $calls，只能是一枚：\n" +
                    code.lines().filter { it.contains("scheduleRepository()") }.joinToString("\n"),
                1,
                calls,
            )
        }
    }

    /** ③ 兜底那段本身还在（改点复用它的同时不许把它削成"只认 Application"） */
    @Test
    fun theEntryPointKeepsItsNonApplicationFallback() {
        val code = blankComments(source(ENTRY_POINT_FILE))
        val body = code.substringAfter(ENTRY_POINT_DECLARATION)
        assertTrue(
            "scheduleRepository() 丢了非 Application Context 的兜底：广播/组件的 Context 拿不到 " +
                "BUAAApplication 时就没有 Repository 可用了\n$code.substring(0, 400.coerceAtMost(code.length))",
            body.contains("?: ScheduleRepository(AppDatabase.getInstance(app))"),
        )
        assertTrue(
            "scheduleRepository() 丢了复用 Application 级实例那一半：那才是去重的意义所在",
            body.contains("?.repository"),
        )
    }

    // ---- 源码读取与抹注释（与仓内其它静态守卫同一口径）----------------------------------------------------

    private data class Source(val path: String, val code: String)

    private fun source(relativeFromJava: String): String {
        val file = File(findMainJavaDir(), relativeFromJava)
        assertTrue("找不到 ${file.path}：这条守卫就是空的", file.isFile)
        return file.readText()
    }

    private fun mainSources(): List<Source> {
        val root = findMainJavaDir()
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("${root.path} 下一个 .kt 都没有，路径不对", files.isNotEmpty())
        return files.map { Source(it.relativeTo(root).path.replace('\\', '/'), blankComments(it.readText())) }
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

    /** 注释抹成等长空格（字面量原样留着）：数的是代码里的形状，不是注释里的那句说明 */
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
        /** 允许出现裸构造的唯一一处：兜底函数自己体内 */
        const val ENTRY_POINT_FILE = "com/buaa/schedule/data/repository/ScheduleRepository.kt"
        const val ENTRY_POINT_DECLARATION = "fun Context.scheduleRepository(): ScheduleRepository"

        const val REMINDER_RECEIVER = "com/buaa/schedule/reminder/ReminderReceiver.kt"
        const val TOMORROW_PREVIEW_RECEIVER = "com/buaa/schedule/reminder/TomorrowPreviewReceiver.kt"
        const val WIDGET_DATA_SYNCHRONIZER = "com/buaa/schedule/widget/WidgetDataSynchronizer.kt"

        // 换行/空格都算命中：谁把这段折成两行，枚数照样要数到
        val BARE_CONSTRUCTION = Regex("""ScheduleRepository\s*\(\s*AppDatabase""")
        val CALL_SITE = Regex("""scheduleRepository\s*\(\s*\)""")
    }
}
