package com.buaa.schedule.widget

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 生产接线：`BUAAApplication.onCreate` 那条冷启动后台链现在**只**经
 * [ColdStartRebuild.run] 这道闸门（审计 §2.1 P1-① 剩余 + §2.2 P1-②）。
 *
 * 为什么需要按源码形状核对：闸门本体是 JVM 测得到的（`ColdStartRebuildTest` 数的是"整链被跑了几遍"），
 * 但"生产上到底有没有把整链交给它"这件事 JVM 跑不到 —— 本模块单测没有 Robolectric，
 * `Application.onCreate` 一调就是 android.jar 里那些抛 "not mocked" 的桩。
 * 接线写错有两种形态，都不红：
 * ① 漏接（照旧裸调那两个重建步骤）⇒ 判据白写，行为与改动前一字不差；
 * ② 半接（判据只管第一半，组件四步照旧每次跑）⇒ 审计 §2.1 那笔账只省掉一小半，
 *    而"跳过"这件事在日志上看着是生效的。
 * 形状守卫钉的就是这两种。手法与 `ClassProgressCleanupDecisionTest` 一致：读 .kt、
 * 按"签名之后第一个左括号"配平取函数体（含嵌套分支）、**找不到就抛**（`assumeTrue` 式的跳过
 * 等于没有守卫）。整份文件级扫描一律先抹注释**再**抹字符串字面量（[blankCommentsAndLiterals]）：
 * 只抹注释的那份会把 `ui/settings/SettingsScreen.kt` 里作为字符串出现的「斜杠紧跟星号」当成注释开头，
 * 一口气吞掉后面的真代码。（这两处说明都不许把那两个字符写全：KDoc 自己是块注释，而 Kotlin 的块注释
 * 能嵌套，写了就等于把后面的代码关进注释里 —— 本文件第一版就红在这儿。）
 */
class ColdStartRebuildWiringTest {

    /** ① 冷启动那条链整条交给闸门，BUAAApplication 里不留任何一处裸调用 */
    @Test
    fun coldStartChainIsHandedToTheGateExactlyOnce() {
        val source = withoutComments(readMainSource(BUAA_APPLICATION_FILE))
        val step = normalize(balancedBlock(source, "step(\"coldStartRebuild\")"))

        assertTrue(
            "闸门那一步里调的不是 ColdStartRebuild：\n$step",
            step.contains("ColdStartRebuild.run(this@BUAAApplication)"),
        )

        val flat = normalize(source)
        val gate = occurrences(flat, "ColdStartRebuild.run(")
        assertEquals(
            "BUAAApplication 里闸门被调了 $gate 遍，只能是 1 遍：\n$flat",
            1,
            gate,
        )
        assertEquals(
            "重建整链的第一半还在 BUAAApplication 里裸调，跳过的判据管不到它：\n$flat",
            0,
            occurrences(flat, "BackgroundSync.rescheduleRemindersAndBells("),
        )
        assertEquals(
            "组件那四步还在 BUAAApplication 里裸调（这就是「半接」：判据只管第一半时审计 §2.1 的账只省一小半）：\n$flat",
            0,
            occurrences(flat, "BackgroundSync.runColdStartWidgetSteps("),
        )
    }

    /** ② 闸门外的三步照旧每次冷启动都跑，而且排在闸门前面 */
    @Test
    fun stepsOutsideTheGateStayUnconditionalAndComeFirst() {
        val code = withoutComments(readMainSource(BUAA_APPLICATION_FILE))
        val onCreate = normalize(balancedBlock(code, "override fun onCreate()"))
        val gate = onCreate.indexOf("step(\"coldStartRebuild\")")
        val legacy = onCreate.indexOf("step(\"cancelLegacyPeriodicWork\")")
        val dnd = onCreate.indexOf("step(\"dndSelfCheck\")")

        assertTrue("找不到闸门那一步：\n$onCreate", gate >= 0)
        assertTrue(
            "旧版固定周期轮询的退出被挪进闸门里了 —— 它一被跳过就是个自我闭合的死循环" +
                "（被退役的 legacy 任务下一次唤醒仍被判成「无事可做」），见 ColdStartRebuild 类注释",
            legacy in 0 until gate,
        )
        assertTrue(
            "勿扰 selfCheck 被挪进闸门里了 —— 它是勿扰的唯一自愈入口（ai/T12 刚把开机那条改走它）",
            dnd in 0 until gate,
        )
        assertTrue(onCreate.contains("BackgroundSync.cancelLegacyPeriodicWork(this@BUAAApplication)"))
        assertTrue(onCreate.contains("com.buaa.schedule.reminder.ClassProgressDnd.selfCheck(this@BUAAApplication)"))
        // Personalization / 通知渠道在 launch 之前，本来就不在跳过范围内：确认没被顺手挪进那条链
        assertTrue(onCreate.indexOf("Personalization.load(this)") < onCreate.indexOf("applicationScope.launch"))
        assertTrue(
            onCreate.indexOf("com.buaa.schedule.reminder.ReminderNotifications.ensureChannels(this)") <
                onCreate.indexOf("applicationScope.launch"),
        )
        // 闸门那一步里不许藏着上面任何一步（"外面"必须是真外面）
        val step = normalize(balancedBlock(code, "step(\"coldStartRebuild\")"))
        assertTrue(!step.contains("cancelLegacyPeriodicWork") && !step.contains("selfCheck"))
    }

    /** ③ 闸门的 Context 入口：整链两半各接一次，而且共用同一个失败留痕口 */
    @Test
    fun gateWiresBothHalvesOfTheChainExactlyOnce() {
        val code = withoutComments(readMainSource(COLD_START_REBUILD_FILE))
        val wiring = normalize(balancedBlock(code, "suspend fun run(context: Context): Outcome"))

        val chainCalls = occurrences(wiring, "BackgroundSync.rescheduleRemindersAndBells(")
        val widgetCalls = occurrences(wiring, "BackgroundSync.runColdStartWidgetSteps(")
        assertEquals(
            "重建整链的第一半在闸门里被调了 $chainCalls 遍，只能一遍：\n$wiring",
            1,
            chainCalls,
        )
        assertEquals(
            "组件那四步在闸门里被调了 $widgetCalls 遍，只能一遍：\n$wiring",
            1,
            widgetCalls,
        )
        // 两半都吃到闸门那个 report 口：闸门数得出"这一轮有没有干净跑完"，才谈得上写不写指纹
        assertTrue(wiring.contains("BackgroundSync.rescheduleRemindersAndBells(context, report)"))
        assertTrue(wiring.contains("BackgroundSync.runColdStartWidgetSteps(context, report)"))
        // 第一半的返回值就是钥匙 2 下一次要探的那一头（"本轮排上了课前提醒闹钟"）
        assertTrue(
            "闸门没把 rescheduleRemindersAndBells 的结论翻成闹钟头：钥匙 2 会探错一头：\n$wiring",
            wiring.contains("if (reminderArmed) AlarmHead.PreClassReminder else AlarmHead.ClassBells"),
        )
        assertTrue(
            "生产入口自己动起了闹钟/库的脑筋（那些活只许在两半里）：\n$wiring",
            !wiring.contains("getSystemService") && !wiring.contains("AppDatabase"),
        )
    }

    /** ④ 跳过的那条路：判据一放行，整链两半与写指纹都不许被碰到 */
    @Test
    fun skippedRoundTouchesNeitherTheChainNorTheFingerprint() {
        val body = chainBody()
        val guard = body.indexOf("if (!decision.rebuild) return Outcome.Skipped")
        assertTrue("闸门里找不到那条提前 return（判据放行也照样往下跑）：\n$body", guard >= 0)

        val chainCalls = occurrences(body, "rebuildRemindersAndBells(report)")
        val widgetCalls = occurrences(body, "runWidgetSteps(report)")
        assertEquals(
            "重建整链的第一半被调了 $chainCalls 遍，只能一遍（多一遍就是 §2.2 的并发重复）：\n$body",
            1,
            chainCalls,
        )
        assertEquals(
            "组件那四步被调了 $widgetCalls 遍，只能一遍：\n$body",
            1,
            widgetCalls,
        )
        assertTrue(body.indexOf("rebuildRemindersAndBells(report)") > guard)
        assertTrue(body.indexOf("runWidgetSteps(report)") > guard)
        assertTrue(body.indexOf("writeFingerprint(") > guard)
    }

    /** ⑤ 指纹只写一处，而且写在前面的"这一轮干净吗"那道判断之后 */
    @Test
    fun fingerprintIsWrittenOnlyOnceAndOnlyOnTheCleanPath() {
        val body = chainBody()
        val cleanGuard = body.indexOf("if (head == null || failures.isNotEmpty()) return Outcome.RebuiltWithFailures")
        assertTrue(
            "找不到「这一轮不干净就不往下写」那道判断（审计 §2.1 的红线：任一步抛了不许记成功）：\n$body",
            cleanGuard >= 0,
        )
        val writes = occurrences(body, "writeFingerprint(Fingerprint(")
        assertEquals(
            "指纹被写了 $writes 处，只能一处：\n$body",
            1,
            writes,
        )
        assertTrue(body.indexOf("writeFingerprint(Fingerprint(") > cleanGuard)
    }

    /**
     * ⑥ 全仓库扫一遍：冷启动那条链只有一个驱动者，重建整链的调用点集合就是已知那四处。
     *
     * 审计 §2.2 的坏交错来自"onCreate 版 + 广播版"并发跑同一套重建，而卡片给的修法是把重复
     * 执行消掉（不是给清理分支再加判据）。这里钉住的是这件事的形状：冷启动侧只有一条路
     * （闸门），而且只被 `BUAAApplication` 调一次；哪天有人再加第二个冷启动驱动者，
     * 这条守卫当场红 —— 那正是 §2.2 的现场。
     */
    @Test
    fun coldStartChainHasExactlyOneDriverRepoWide() {
        val rebuildCallSites = mutableListOf<String>()
        var drivers = 0
        for (source in mainSources()) {
            val code = blankCommentsAndLiterals(source.text)
            for (at in occurrencesOf(code, REBUILD_CHAIN_CALL)) {
                val lineStart = code.lastIndexOf('\n', at) + 1
                if (code.substring(lineStart, at).contains("fun ")) continue // 声明自身
                rebuildCallSites += source.relative
            }
            drivers += occurrencesOf(code, "ColdStartRebuild.run(").size
        }
        assertEquals(
            "冷启动链的驱动者有 $drivers 个，只能有 1 个（多一个就又多一处「每次进程创建都重跑」，" +
                "还有 §2.2 的并发重复）",
            1,
            drivers,
        )
        assertEquals(
            "重建整链的调用点集合变了。冷启动侧只许经 ColdStartRebuild；广播侧（开机 / 改时间 / 数据变化）" +
                "本来就是各自的触发条件，不该由这张卡动：\n${rebuildCallSites.sorted()}",
            listOf(
                "$MAIN_PREFIX/com/buaa/schedule/reminder/BootReceiver.kt",
                "$MAIN_PREFIX/com/buaa/schedule/ui/ScheduleViewModel.kt",
                "$MAIN_PREFIX/com/buaa/schedule/widget/ColdStartRebuild.kt",
                "$MAIN_PREFIX/com/buaa/schedule/widget/WidgetRefreshReceiver.kt",
            ).sorted(),
            rebuildCallSites.sorted(),
        )
    }

    /** 闸门编排本体（不接 Context 那个 `run`）的函数体，签名之后那段 */
    private fun chainBody(): String {
        val block = withoutComments(readMainSource(COLD_START_REBUILD_FILE)).let { balancedBlock(it, "internal suspend fun run(") }
        return normalize(block.substring(block.indexOf('{') + 1))
    }

    // ---- 源码核对工具（抄 ClassProgressCleanupDecisionTest 与 WakeLockTimeoutFloorTest）----

    private class Source(val relative: String, val text: String)

    private fun readMainSource(relativeFromJava: String): String {
        val file = File(findMainJavaDir(), relativeFromJava)
        assertTrue("找不到 ${file.path}：文件挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText()
    }

    /** 只扫主源码：测试目录里那些注入的假实现不该算进调用点集合 */
    private fun mainSources(): List<Source> {
        val javaDir = findMainJavaDir()
        val files = javaDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("${javaDir.path} 下一个 .kt 都没有，路径不对", files.isNotEmpty())
        return files.map { Source("$MAIN_PREFIX/${it.relativeTo(javaDir).path.replace('\\', '/')}", it.readText()) }
    }

    /**
     * 从 [signature] 之后第一个 `{` 起配平到对应的右括号（含），返回**整段**（内部嵌套分支一起数）。
     * 找不到锚点就抛 —— 锚点失效必须显式失败，静默跳过等于没有守卫。
     */
    private fun balancedBlock(source: String, signature: String): String {
        val at = source.indexOf(signature)
        check(at >= 0) { "找不到 $signature：函数改名或挪过家，这条守卫要跟着改" }
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

    /** 抹注释、留字面量：切单个函数体用这份（字面量里的中文还要参与断言） */
    private fun withoutComments(source: String): String {
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

    /**
     * 注释与字符串字面量**都**抹成空白、长度与换行位置不变：整份文件的计数扫描用这份。
     *
     * 只抹注释的那份在 `ui/settings/SettingsScreen.kt` 上会出事：那里有一个作为字符串字面量
     * 出现的 「斜杠紧跟星号」，会被当成块注释开头一口气吞掉后面的真代码。
     */
    private fun blankCommentsAndLiterals(src: String): String {
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

                src.startsWith("\"\"\"", i) -> {
                    val end = src.indexOf("\"\"\"", i + 3).let { if (it < 0) out.size else it + 3 }
                    for (k in (i + 3) until (end - 3).coerceAtLeast(i + 3)) if (out[k] != '\n') out[k] = ' '
                    i = end
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
                    for (k in (i + 1) until (j - 1).coerceAtLeast(i + 1)) out[k] = ' '
                    i = j
                }

                else -> i++
            }
        }
        return String(out)
    }

    private fun occurrencesOf(haystack: String, needle: String): List<Int> {
        val hits = mutableListOf<Int>()
        var from = 0
        while (true) {
            val at = haystack.indexOf(needle, from)
            if (at < 0) return hits
            hits += at
            from = at + needle.length
        }
    }

    /** 工作目录是模块目录还是仓库根不由这里决定：两种布局都试，全落空就抛（跳过的守卫等于没守卫） */
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

    private fun occurrences(haystack: String, needle: String): Int = occurrencesOf(haystack, needle).size

    private fun normalize(code: String): String = code.replace(Regex("\\s+"), " ").trim()

    private companion object {
        const val BUAA_APPLICATION_FILE = "com/buaa/schedule/BUAAApplication.kt"
        const val COLD_START_REBUILD_FILE = "com/buaa/schedule/widget/ColdStartRebuild.kt"

        /** 报告里的路径前缀：人一眼认得出这是主源码，不用去猜相对谁 */
        const val MAIN_PREFIX = "app/src/main/java"

        /** 重建整链第一半的调用名（不含 `BackgroundSync.` 前缀，两种写法都数得进来） */
        const val REBUILD_CHAIN_CALL = "rescheduleRemindersAndBells("
    }
}
