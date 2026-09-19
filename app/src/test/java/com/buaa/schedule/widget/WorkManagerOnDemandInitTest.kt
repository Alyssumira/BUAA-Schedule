package com.buaa.schedule.widget

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WorkManager 按需初始化的**耦合守卫**（T18，冷启动主线程账）。
 *
 * 这张卡的两半分处两个文件、中间没有任何编译期联系：
 * - 清单里给 `androidx.work.WorkManagerInitializer` 挂上 `tools:node="remove"`；
 * - `BUAAApplication` 实现 `androidx.work.Configuration.Provider`。
 * 少任何一半都不红，而且两种"半个"的坏法都不一样难看：
 *
 * ① 只摘 initializer、忘了 Provider：work-runtime 2.9.1 的
 *    `WorkManagerImpl.getInstance(Context)` 在「未初始化 + applicationContext 不是
 *    `Configuration.Provider`」时**不自我初始化**，直接抛 `IllegalStateException`。
 *    我们两处调用点（`BackgroundSync.cancelLegacyPeriodicWork`、
 *    `WidgetFallbackWorker.ensure`）外面都套着 `runCatching`，于是症状不是崩溃而是
 *    「兜底任务永远注册不上」—— 组件装完不动课表就永远停在放置当天，日志里只有一行 WARN。
 * ② 只加 Provider、没摘 initializer：起手照旧在主线程上初始化，那笔账一分没省，
 *    而 `workManagerConfiguration` 变成一段没人读的 dead code（androidx.startup 那条路
 *    走的是 initializer 自己 new 出来的 Configuration）。
 *
 * 还有一种"整块删干净"的错法比上面两种都贵：把 `InitializationProvider` 整个
 * `tools:node="remove"`。profileinstaller 1.4.1 的 Initializer 就挂在这个 provider 的
 * meta-data 上，本应用自己分发、拿不到 Play 的云端 profile，Baseline Profile 只能靠它
 * 落到 `/data/misc/release-profile`（T16 那张卡的全部收益在这里）。emoji2 与
 * ProcessLifecycle 两条同理。这条守卫把"provider 必须还在、是 merge 不是 remove、
 * 被摘掉的只有 WorkManager 那一条"钉成断言。
 *
 * 手法与 [ColdStartRebuildWiringTest] 一致：读源码/清单文本按形状核对，找不到锚点就抛
 * （`assumeTrue` 式的跳过等于没有守卫）；整文件级扫描先抹注释**再**抹字符串字面量
 * （[blankCommentsAndLiterals]），因为清单与 KDoc 里都把这些名字当话说，只抹一份会数重。
 */
class WorkManagerOnDemandInitTest {

    /** ① 清单侧：provider 还在、是 merge，被摘掉 remove 的 meta-data 只有 WorkManager 那一条 */
    @Test
    fun manifestRemovesOnlyTheWorkManagerInitializerAndKeepsTheProvider() {
        val manifest = readManifest()
        val provider = balancedElement(manifest, "provider", INITIALIZATION_PROVIDER)

        assertTrue(
            "InitializationProvider 整个不见了：Baseline Profile（profileinstaller 1.4.1）、" +
                "emoji2、ProcessLifecycle 三条 Initializer 都挂在它身上，删 provider 等于删掉它们",
            provider.contains(INITIALIZATION_PROVIDER),
        )
        assertTrue(
            "authority 不该动：它必须是 applicationId 派生出来的 androidx-startup，" +
                "改了 androidx.startup 就注册不上：\n$provider",
            provider.contains("applicationId") && provider.contains("androidx-startup"),
        )
        assertTrue(
            "provider 的 exported 不该动：\n$provider",
            provider.contains("android:exported=\"false\""),
        )
        assertTrue(
            "provider 这一层必须是 tools:node=\"merge\"。写成 remove 或不写都会把另外三条 " +
                "Initializer 一起带走：\n$provider",
            provider.contains("tools:node=\"merge\""),
        )

        val fragments = children(manifest, INITIALIZATION_PROVIDER)
        val removed = fragments.filter { it.contains("tools:node=\"remove\"") }.map { metaDataName(it) }
        assertEquals(
            "被摘掉的 Initializer 集合变了：$removed。这张卡的账只包括 WorkManager 一条；" +
                "另外三条（profileinstaller / emoji2 / ProcessLifecycle）各有别的东西依赖",
            listOf("androidx.work.WorkManagerInitializer"),
            removed,
        )
        // provider 起始标签本身不许挂 remove（上面那条 merge 断言只管"有没有"，这里管"有没有第二处"）
        val header = provider.substringBefore("<meta-data")
        assertTrue(
            "provider 起始标签上不许出现 tools:node=\"remove\"：\n$header",
            !header.contains("tools:node=\"remove\""),
        )
        assertEquals(
            "InitializationProvider 的 meta-data 只剩一条 remove 才对（其余三条由库的清单提供，" +
                "不在主清单里显式写）：$fragments",
            1,
            fragments.count { it.contains("tools:node=") },
        )
    }

    /** ② 应用侧：BUAAApplication 必须补上 Configuration.Provider，且给的是默认配置 */
    @Test
    fun applicationSuppliesTheConfigurationTheRemovedInitializerUsedToBuild() {
        val raw = readMainSource(BUAA_APPLICATION_FILE)
        val code = withoutComments(raw)
        assertTrue(
            "BUAAApplication 没有实现 androidx.work.Configuration.Provider —— 摘掉 initializer 之后" +
                "每一处 getInstance(context) 都会抛 IllegalStateException，而调用点外面的 runCatching " +
                "会把它压成一行 WARN：\n${head(code)}",
            Regex("class BUAAApplication[^{]*Configuration\\.Provider").containsMatchIn(code),
        )
        val getter = normalize(code)
        assertTrue(
            "workManagerConfiguration 必须逐字等于被摘掉的 WorkManagerInitializer.create() 里那句 " +
                "Configuration.Builder().build()，否则这不是搬账而是改配置：\n${head(code)}",
            getter.contains(
                "override val workManagerConfiguration: Configuration " +
                    "get() = Configuration.Builder().build()",
            ),
        )
        // 这个 getter 是在 WorkManagerImpl 的静态 sLock 里被调的：上面那条逐字比对已经把
        // "只构造一个对象就返回"钉住了 —— 这里再钉一次反向的，防止有人把它改成表达式体之外的东西。
        assertTrue(
            "workManagerConfiguration 的声明行里混进了 WorkManager/getInstance：" +
                "它是在 WorkManagerImpl 那把静态 sLock 里被读的，回头调 WorkManager 就是自锁风险",
            !code.lineSequence()
                .dropWhile { !it.contains("override val workManagerConfiguration") }
                .take(2)
                .any { it.contains("WorkManager.") || it.contains("getInstance") },
        )
        // 反向也要钉：不许有人图省事再补一句显式 initialize，那等于把 initializer 请回来
        val flat = blankCommentsAndLiterals(raw)
        assertEquals(
            "BUAAApplication 里出现了 WorkManager.initialize( —— 起手初始化又回来了，" +
                "按需这条路的意义就被抵消了",
            0,
            occurrences(flat, "WorkManager.initialize("),
        )
    }

    /** ③ 调用点集合：真调 WorkManager 的文件只有两个，全主源码扫一遍钉住 */
    @Test
    fun workManagerCallSitesAreExactlyTheTwoKnownFiles() {
        val hits = mutableListOf<String>()
        for (source in mainSources()) {
            val code = blankCommentsAndLiterals(source.text)
            if (occurrences(code, "WorkManager.") > 0) hits += source.relative
        }
        assertEquals(
            "WorkManager 的调用点集合变了（多一处就等于多一个「谁第一个付初始化钱」的线程要审）：\n$hits",
            listOf(
                "$MAIN_PREFIX/com/buaa/schedule/widget/BackgroundSync.kt",
                "$MAIN_PREFIX/com/buaa/schedule/widget/WidgetFallbackWorker.kt",
            ).sorted(),
            hits.sorted(),
        )
    }

    /**
     * ④ 组件侧：onEnabled / onDisabled 只许有一处，而且必须走 `*FromReceiver`。
     *
     * `ACTION_APPWIDGET_ENABLED` / `ACTION_APPWIDGET_DISABLED` 都是冷进程入口，
     * `AppWidgetProvider.onReceive` 派下来的回调全在主线程上；按需初始化之后，
     * 进程里第一个调 `getInstance` 的线程就地付建库的钱。所以这两条路在广播主线程上
     * 只许做一次 `goAsync()` + 一次协程派发。
     */
    @Test
    fun widgetAddRemoveCallbacksLeaveTheMainThreadImmediately() {
        val providers = widgetProviderFiles()
        assertEquals(
            "组件 Provider 的数量与历史形状不符（六家各写一份 onEnabled/onDisabled 才是这里要收的账）：$providers",
            6,
            providers.size,
        )
        for (relative in providers) {
            val code = blankCommentsAndLiterals(readMainSource(relative))
            assertEquals(
                "$relative 里还留着 onEnabled：它跑在广播主线程上，会就地付 WorkManager 的初始化钱",
                0,
                occurrences(code, "override fun onEnabled("),
            )
            assertEquals(
                "$relative 里还留着 onDisabled：同上",
                0,
                occurrences(code, "override fun onDisabled("),
            )
        }

        val base = withoutComments(readMainSource(SCHEDULE_APP_WIDGET_PROVIDER_FILE))
        val enabled = normalize(balancedBlock(base, "override fun onEnabled(context: Context)"))
        val disabled = normalize(balancedBlock(base, "override fun onDisabled(context: Context)"))
        assertTrue(
            "onEnabled 没走 bootstrapFromReceiver（同步那一份会在广播主线程上碰 WorkManager）：\n$enabled",
            enabled.contains("WidgetCommon.bootstrapFromReceiver(this, context)"),
        )
        assertTrue(
            "onDisabled 没走 cancelMidnightIfNoWidgetsFromReceiver：\n$disabled",
            disabled.contains("WidgetCommon.cancelMidnightIfNoWidgetsFromReceiver(this, context)"),
        )

        val common = withoutComments(readMainSource(WIDGET_COMMON_FILE))
        val pairs = listOf(
            "bootstrapFromReceiver" to normalize(balancedBlock(common, "internal fun bootstrapFromReceiver(")),
            "cancelMidnightIfNoWidgetsFromReceiver" to
                normalize(balancedBlock(common, "internal fun cancelMidnightIfNoWidgetsFromReceiver(")),
        )
        for ((name, body) in pairs) {
            assertTrue(
                "$name 必须先把广播 ticket 用 goAsync() 续上再派发，否则进程会在活干完前被回收：\n$body",
                body.contains("receiver.goAsync()"),
            )
            assertTrue(
                "$name 要复用 onUpdate 那条 launchRefresh（Dispatchers.IO），不要新造一个机制：\n$body",
                body.contains("launchRefresh("),
            )
            assertTrue(
                "$name 里不许直接调 WorkManager：\n$body",
                !body.contains("WorkManager."),
            )
        }
    }

    // ---- 源码核对工具（抄 ColdStartRebuildWiringTest）----

    private class Source(val relative: String, val text: String)

    private fun readMainSource(relativeFromJava: String): String {
        val file = File(findMainJavaDir(), relativeFromJava)
        assertTrue("找不到 ${file.path}：文件挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText()
    }

    private fun readManifest(): String {
        val file = File(findModuleDir(), "src/main/AndroidManifest.xml")
        assertTrue("找不到 ${file.path}", file.isFile)
        return file.readText()
    }

    private fun widgetProviderFiles(): List<String> {
        val javaDir = findMainJavaDir()
        return javaDir.walkTopDown()
            .filter { it.isFile && it.name.endsWith("WidgetProvider.kt") }
            // 基类不在这里数：它恰恰是**唯一该有** onEnabled/onDisabled 的那一份，
            // 下面单独按 SCHEDULE_APP_WIDGET_PROVIDER_FILE 核它。这里要收的账是
            // "六家具体 Provider 各写一份、内容逐字相同"有没有被真正清空。
            .filterNot { it.name == BASE_PROVIDER_NAME }
            // 只给相对 src/main/java 的路径：这份清单会被 readMainSource() 原样拼回去，
            // 再带 MAIN_PREFIX 就成 app/src/main/java/app/src/main/java/... 了
            .map { "com/buaa/schedule/widget/${it.name}" }
            .sorted()
            .toList()
    }

    private fun mainSources(): List<Source> {
        val javaDir = findMainJavaDir()
        val files = javaDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("${javaDir.path} 下一个 .kt 都没有，路径不对", files.isNotEmpty())
        return files.map { Source("$MAIN_PREFIX/${it.relativeTo(javaDir).path.replace('\\', '/')}", it.readText()) }
    }

    /** 某个 `<tag>` 元素整段（含结束标签），按 android:name 定位；多行起始标签与自闭合都处理 */
    private fun balancedElement(src: String, tag: String, name: String): String {
        val open = "<$tag"
        var from = 0
        while (true) {
            val at = src.indexOf(open, from)
            if (at < 0) break
            val next = src.getOrElse(at + open.length) { ' ' }
            if (next == ' ' || next == '\n' || next == '\r' || next == '\t' || next == '>') {
                val end = startTagEnd(src, at)
                val header = src.substring(at, end)
                if (header.contains("android:name=\"$name\"")) {
                    return if (header.trimEnd().endsWith("/")) {
                        src.substring(at, end + 1)
                    } else {
                        val close = src.indexOf("</$tag>", end)
                        assertTrue("<$tag> 没有结束标签：$name", close >= 0)
                        src.substring(at, close + tag.length + 3)
                    }
                }
            }
            from = at + open.length
        }
        throw IllegalStateException("清单里找不到名为 $name 的 <$tag>：改名或挪过家，这条守卫要跟着改")
    }

    /** 起始标签里 `>` 的下标；引号内的 `>` 不算（清单里没有 `>` 出现在属性值里，这层保险留着不亏） */
    private fun startTagEnd(src: String, from: Int): Int {
        var quote = ' '
        var i = from
        while (i < src.length) {
            val c = src[i]
            when {
                quote != ' ' && c == quote -> quote = ' '
                quote == ' ' && (c == '"' || c == '\'') -> quote = c
                quote == ' ' && c == '>' -> return i
            }
            i++
        }
        throw IllegalStateException("< 之后没找到闭合的 >：$from")
    }

    /** 该 provider 元素内部的每个 `<meta-data>` 片段 */
    private fun children(src: String, providerName: String): List<String> {
        val block = balancedElement(src, "provider", providerName)
        val out = mutableListOf<String>()
        var from = 0
        while (true) {
            val at = block.indexOf("<meta-data", from)
            if (at < 0) return out
            val end = block.indexOf('>', at)
            out += block.substring(at, end + 1)
            from = end + 1
        }
    }

    private fun metaDataName(fragment: String): String {
        val key = "android:name=\""
        val at = fragment.indexOf(key)
        if (at < 0) return "?"
        val start = at + key.length
        val end = fragment.indexOf('"', start)
        return if (end < 0) "?" else fragment.substring(start, end)
    }

    /**
     * 从 [signature] 之后第一个 `{` 起配平到对应右括号（含），返回整段（嵌套分支一起数）。
     * 找不到锚点就抛 —— 静默跳过等于没有守卫。
     */
    private fun balancedBlock(source: String, signature: String): String {
        val at = source.indexOf(signature)
        check(at >= 0) { "找不到 $signature：声明改名或挪过家，这条守卫要跟着改" }
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

    /** 注释与字符串字面量都抹成空白，长度与换行位置不变：整份文件的计数扫描用这份 */
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

    private fun occurrences(haystack: String, needle: String): Int {
        var count = 0
        var from = 0
        while (true) {
            val at = haystack.indexOf(needle, from)
            if (at < 0) return count
            count++
            from = at + needle.length
        }
    }

    private fun normalize(code: String): String = code.replace(Regex("\\s+"), " ").trim()

    private fun head(code: String): String = code.replace(Regex("\\s+"), " ").trim().take(400)

    /** 工作目录是模块目录还是仓库根不由这里决定：两种布局都试，全落空就抛 */
    private fun findModuleDir(): File {
        var dir: File? = File("").absoluteFile
        repeat(5) {
            val hit = listOf("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml")
                .map { File(dir, it) }
                .firstOrNull { it.isFile }
            if (hit != null) return hit.parentFile.parentFile.parentFile
            dir = dir?.parentFile
        }
        throw IllegalStateException("找不到 AndroidManifest.xml：当前目录 ${File("").absolutePath}")
    }

    private fun findMainJavaDir(): File = File(findModuleDir(), "src/main/java").also {
        assertTrue("${it.path} 不存在", it.isDirectory)
    }

    private companion object {
        const val MAIN_PREFIX = "app/src/main/java"
        const val BUAA_APPLICATION_FILE = "com/buaa/schedule/BUAAApplication.kt"
        const val SCHEDULE_APP_WIDGET_PROVIDER_FILE = "com/buaa/schedule/widget/ScheduleAppWidgetProvider.kt"

        /** [SCHEDULE_APP_WIDGET_PROVIDER_FILE] 的文件名：从"六家 Provider"的枚举里排掉它 */
        const val BASE_PROVIDER_NAME = "ScheduleAppWidgetProvider.kt"
        const val WIDGET_COMMON_FILE = "com/buaa/schedule/widget/WidgetCommon.kt"
        const val INITIALIZATION_PROVIDER = "androidx.startup.InitializationProvider"
    }
}
