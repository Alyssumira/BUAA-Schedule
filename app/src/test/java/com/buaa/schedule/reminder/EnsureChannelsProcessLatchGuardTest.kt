package com.buaa.schedule.reminder

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ensureChannels` 的进程内门闩（ai/T169 第 2 件）。
 *
 * 改前它没有门闩：全仓 7 枚调用点里有三枚串在同一条通知下发链上
 * （`ReminderReceiver.notifyCourse` → `ReminderNotifications.startLiveWindow` →
 * `postClassOngoing`），于是一次课前闹钟唤醒连跑 3 遍 = 9 趟 `createNotificationChannel`
 * binder + 3 趟 prefs 读。渠道是进程级全局状态，同一进程里第二遍不可能有新结果
 * （重要性一旦创建只能由用户在系统设置里改），所以这一族重复全是白付。
 *
 * 门闩的**失败面**是这张卡的前置问题，本守卫把它钉成两条：
 * 1. 门闩只在整段走完之后落下 —— 半途而废不许当成"已建好"；
 * 2. 全仓只许有一枚程序化删渠道的点，而且它必须住在那条**一次性迁移**的门里
 *    （`KEY_LEGACY_CHANNEL_DELETED`）。这一条就是门闩成立的前提：哪天有人新加一处
 *    `deleteNotificationChannel`（或把迁移那道门拆了），"同一进程内不再补建"就可能
 *    变成"渠道被删了却再也不回来"，本守卫当场红，逼人来重新评估门闩。
 *
 * 本模块单测没有 Robolectric（android.jar 里全是抛 "not mocked" 的桩），跑不了真的
 * `ensureChannels`，所以按结构核对主源码；定位源码的办法与仓内其它静态守卫一致，
 * 找不到目录就直接抛，不用 assumeTrue 跳过。
 */
class EnsureChannelsProcessLatchGuardTest {

    /** ① 门闩存在且只有一枚，形状就是本仓那条一次先例的写法（AtomicBoolean） */
    @Test
    fun theProcessLatchIsDeclaredExactlyOnce() {
        val code = blankComments(source(REMINDER_NOTIFICATIONS))
        val declarations = Regex("""private\s+val\s+channelsEnsured\s*=\s*AtomicBoolean\(false\)""")
            .findAll(code).toList()

        assertEquals(
            "channelsEnsured 的门闩声明只能有一枚（多一枚就有两份不同步的\"已建过\"）：\n" +
                code.lines().filter { it.contains("channelsEnsured") }.joinToString("\n"),
            1,
            declarations.size,
        )
        assertTrue(
            "门闩要的是 java.util.concurrent.atomic.AtomicBoolean 的内存序，import 丢了编译也不会过，" +
                "但换成 @Volatile var 就不是同一件事了，这里要指名",
            code.contains("import java.util.concurrent.atomic.AtomicBoolean"),
        )
    }

    /** ② 短路必须是 ensureChannels 的**第一句**，而且要放在取系统服务之前 */
    @Test
    fun ensureChannelsShortCircuitsOnTheLatchBeforeDoingAnyWork() {
        val body = ensureChannelsBody()
        val firstStatement = body.lines().first { it.isNotBlank() }.trim()

        assertEquals(
            "ensureChannels 的第一句不再是门闩短路 —— 门闩要是排在 binder 之后，那 9 趟照样要付：\n$firstStatement",
            "if (channelsEnsured.get()) return",
            firstStatement,
        )
        assertTrue(
            "门闩那句跑到了 getSystemService 之后：拿服务本身也是一趟 binder，挡在它前面才有收益",
            body.indexOf("channelsEnsured.get()") < body.indexOf("getSystemService"),
        )
    }

    /** ③ 落闩在整段走完之后：半途 return / 抛异常都不该被当成"渠道已建好" */
    @Test
    fun theLatchFallsOnlyAfterTheWholeBodyRan() {
        val body = ensureChannelsBody()
        val setAt = body.indexOf("channelsEnsured.set(true)")
        assertTrue(
            "找不到落闩那句：门闩要是从没置位，它就只是每次多读一个布尔而已",
            setAt >= 0,
        )
        assertTrue(
            "落闩跑到了建渠道那一段之前 —— 这就是\"建了一半当建好了\"，渠道可能永远缺着",
            setAt > body.indexOf("manager.createNotificationChannel(channel)"),
        )
        // 唯一的提前 return 只许是 getSystemService 拿不到那一档：它不置位，下一次还要再试
        val beforeLatch = body.substringBefore("channelsEnsured.set(true)")
        assertEquals(
            "落闩之前的 `?: return` 只许有一枚（系统服务拿不到那档不置位、下次还要再试）：" +
                "多出来的那些 return 会把\"没建成的那一轮\"也记成已建过\n$beforeLatch",
            1,
            Regex("""\?: return""").findAll(beforeLatch).count(),
        )
    }

    /** ④ 门闩的前提：全仓只许一枚程序化删渠道的点，且它住在那条一次性迁移的门里 */
    @Test
    fun channelDeletionStaysASingleGatedMigration() {
        val deletions = mainSources().flatMap { file ->
            Regex("""deleteNotificationChannel""").findAll(file.code).map { file.path }.toList()
        }.sorted()

        assertEquals(
            "程序化删渠道的点只许有一枚（旧渠道那次一次性迁移）。多出来的每一枚都是门闩的失败面：" +
                "渠道被删掉后同一进程内再也不会补建 ——\n" + deletions.joinToString("\n"),
            listOf(ENTRY_POINT_OF_DELETION),
            deletions,
        )

        val code = blankComments(source(REMINDER_NOTIFICATIONS))
        val gate = code.indexOf("if (!prefs.getBoolean(KEY_LEGACY_CHANNEL_DELETED, false))")
        val delete = code.indexOf("manager.deleteNotificationChannel(LEGACY_CHANNEL_CLASS_PROGRESS)")
        assertTrue(
            "找不到那条迁移的门：门闩把 ensureChannels 收成进程内一次，前提是删渠道仍由 prefs " +
                "旗标挡住（改前那条门就是这段代码自己立的先例）",
            gate >= 0,
        )
        assertTrue(
            "删渠道那句跑到了迁移门之外 —— 那它就成了每进程都会做的常规动作，门闩要重新评估",
            delete in (gate + 1) until code.indexOf("CHANNEL_SPECS.forEach"),
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

    /** ensureChannels 的函数体（大括号配对切出来，注释已经抹掉） */
    private fun ensureChannelsBody(): String {
        val code = blankComments(source(REMINDER_NOTIFICATIONS))
        val at = code.indexOf(DECLARATION)
        check(at >= 0) { "找不到 `fun ensureChannels(context: Context)`：函数改了名，这条守卫要跟着改" }
        val open = code.indexOf('{', at)
        check(open > at) { "ensureChannels 的声明后面找不到函数体左括号：签名换写法了，这条守卫要跟着改" }
        var depth = 0
        var started = false
        var end = code.length
        for (i in open until code.length) {
            when (code[i]) {
                '{' -> { depth++; started = true }
                '}' -> {
                    depth--
                    if (started && depth == 0) {
                        end = i
                        break
                    }
                }
            }
        }
        return code.substring(open + 1, end)
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

    /** 注释抹成等长空格（字面量原样留着）：数的是代码里的形状，不是那段说明里的原话 */
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
        const val REMINDER_NOTIFICATIONS = "com/buaa/schedule/reminder/ReminderNotifications.kt"
        const val DECLARATION = "fun ensureChannels(context: Context)"
        const val ENTRY_POINT_OF_DELETION = "com/buaa/schedule/reminder/ReminderNotifications.kt"
    }
}
