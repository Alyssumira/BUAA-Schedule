package com.buaa.schedule.ui.signin

import java.io.File
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * barhopper 原生库可用性探针的守卫（T24）。
 *
 * ①②③④ 真跑代码；⑤⑥⑦ 读源码文本核对（后三条量的是"漂移就红"，手法沿用
 * `ScanChainWarmUpTest`：本模块没有 Robolectric、`android.content.Context` 在 JVM 里
 * 造不出来，于是读文件、配平取函数体或实参、**找不到锚点就抛**）：
 *
 * ①②③④ 判据是**真去 `System.loadLibrary`**、三态摆得对、结论进程内缓存且只付一次、
 *   dlopen 不落在调用线程上。这四条只能注入着跑（宿主 JVM 里没有那颗 `.so`，
 *   present / missing 两条不注入就根本摆不出来）。
 * ⑤ 探针里那个库名与 `app/build.gradle.kts` 排除掉的三条 jniLibs **同源**：这条是
 *   真去读脚本文本比对，不是注释提醒 —— 名字一漂，探针就退化成"永远回答可用"的空壳，
 *   非 arm64 的 release 包立刻回到启动即崩。
 * ⑥ 全仓库只有探针那一处 `System.loadLibrary`：不许有第二处自己判断。
 * ⑦ 扫码页那四条纪律（scanner 以结论为键、绑定分析流之前先等判定、相册只放行"已判定
 *   可用"、缺库时文案不再指向相册）—— 这一页的降级形状一旦漂走，用户就会被指到一条死路。
 *
 * 至于「判定不可用时预热那一档零 ML Kit 调用」，那条落在 `ScanChainWarmUpTest` ⑨⑩。
 */
class BarhopperNativeLibProbeTest {

    /** ① 结论来自那次加载本身：present / missing 两条各归各，库名按常量递进去 */
    @Test
    fun verdictComesFromTheLoadItself() {
        val loaded = mutableListOf<String>()
        val present = BarhopperNativeLibProbe { name -> loaded += name }
        assertEquals(NativeLibVerdict.Available, present.decideNow())
        assertEquals("加载动作拿到的库名不是探针里那个常量：", listOf(BARHOPPER_NATIVE_LIBRARY), loaded)
        assertTrue("已判定可用还不放行解码，扫码页的两条解码路就都开不了", present.isAvailable())

        val missing = BarhopperNativeLibProbe {
            throw UnsatisfiedLinkError("dlopen failed: library \"lib$BARHOPPER_NATIVE_LIBRARY.so\" not found")
        }
        assertEquals(NativeLibVerdict.Missing, missing.decideNow())
        assertFalse(missing.isAvailable())
    }

    /** ② 三态：「未判定」既不是可用也不是已判定不可用；读结论这一路一次都不加载 */
    @Test
    fun undecidedIsADistinctThirdStateAndReadingItNeverLoads() {
        var loads = 0
        val probe = BarhopperNativeLibProbe { loads++ }
        assertNull("新探针必须停在未判定：把它折叠成 Missing，arm64 用户会白看一帧降级页", probe.verdict)
        assertFalse("未判定不许放行解码", probe.isAvailable())
        assertFalse("未判定也不许当成\"已判定不可用\" —— UI 就是靠这两者分开决定收不收入口", probe.verdict == NativeLibVerdict.Missing)
        assertEquals("光读结论就把库加载了一遍：组合期每读一次都是一次 dlopen", 0, loads)

        assertEquals(NativeLibVerdict.Available, probe.decideNow())
        assertEquals("判定动作没被记下来", 1, loads)
    }

    /** ③ 缓存：并发进来也只付一次加载，之后的判定一律复用同一份结论 */
    @Test
    fun verdictIsLoadedExactlyOnceEvenUnderRaces() {
        val attempts = AtomicInteger(0)
        val gate = CyclicBarrier(THREADS)
        val probe = BarhopperNativeLibProbe {
            attempts.incrementAndGet()
            Thread.sleep(30L) // 把双检那段窗口撑开，让争抢真的发生
        }
        val results = ConcurrentLinkedQueue<NativeLibVerdict>()
        val workers = (1..THREADS).map {
            Thread {
                gate.await()
                results += probe.decideNow()
            }.also { it.start() }
        }
        workers.forEach { it.join() }

        assertEquals(THREADS, results.size)
        assertEquals("并发判定给出了不止一种结论：$results", setOf(NativeLibVerdict.Available), results.toSet())
        assertEquals("加载动作做了 ${attempts.get()} 次，只许 1 次", 1, attempts.get())
        probe.decideNow()
        probe.decideNow()
        assertEquals("结论已经在缓存里，还是又去 load 了一遍", 1, attempts.get())
    }

    /** ④ dlopen 不发生在调用线程上；已有结论时 awaitDecided 既不加载也不换线程 */
    @Test
    fun awaitDecidedNeverLoadsOnTheCallingThread() {
        var loads = 0
        var loadThread: Thread? = null
        val probe = BarhopperNativeLibProbe {
            loads++
            loadThread = Thread.currentThread()
        }
        val caller = Thread.currentThread()
        val verdict = runBlocking { probe.awaitDecided() }

        assertEquals(NativeLibVerdict.Available, verdict)
        assertEquals(1, loads)
        val where = checkNotNull(loadThread) { "加载动作根本没跑：awaitDecided 没去判定" }
        assertNotSame("dlopen 落在调用线程上（这里就是主线程那一类调用方）", caller, where)

        // 已判定：同一条路走完不再加载、也不再换线程 —— 扫码页那两处判定读取都走这里，
        // arm64 上这一步必须是零开销，否则这一页相对改动前就多出一帧等待
        val again = runBlocking { probe.awaitDecided() }
        assertSame(verdict, again)
        assertEquals(1, loads)
        assertSame(where, loadThread)
    }

    /** ⑤ 库名与 build 脚本的排除清单同源：改一边不改另一边就红（真读文本，不是注释提醒） */
    @Test
    fun probeLibraryNameIsTheSameStringTheBuildScriptExcludes() {
        // 只取 `excludes.addAll(...)` 那一次调用的实参，不对整份脚本做注释剥除：
        // 脚本注释里就有 `lib/x86/**` 这种反例示例，按 /* */ 配平剥注释会把它当成
        // 块注释的起点、一口吞掉后面整段（这条守卫第一次落地就是这么红的）。
        val script = readRepoFile(BUILD_SCRIPT)
        val marker = "packaging.jniLibs.excludes.addAll"
        val at = script.indexOf(marker)
        check(at >= 0) { "$BUILD_SCRIPT 里找不到 $marker()：release 的 ABI 剪枝换写法了，探针的前提要重新核" }
        val argument = lineCommentsOut(balancedParens(script, script.indexOf('(', at)))
        val excluded = Regex("\"lib/([^\"]+)/([^\"]+\\.so)\"").findAll(argument)
            .map { it.groupValues[1] to it.groupValues[2] }
            .toList()
        check(excluded.isNotEmpty()) {
            "$BUILD_SCRIPT 的排除清单里一个 .so 都没有：release 带全 ABI 的话这颗探针连同它的守卫" +
                "就该退役，而不是留在这里装绿"
        }
        assertEquals(
            "release 裁掉的应当正好是 arm64 之外那三档（多裁一档=又伤一个 ABI，少裁一档=体积白涨）：$excluded",
            setOf("armeabi-v7a", "x86", "x86_64"),
            excluded.map { it.first }.toSet(),
        )
        assertEquals(
            "被排除的文件名与探针里的库名不是一件事了：探针会立刻退化成\"永远回答可用\"的空壳，" +
                "非 arm64 的 release 包回到启动即崩。$excluded vs lib$BARHOPPER_NATIVE_LIBRARY.so",
            setOf("lib$BARHOPPER_NATIVE_LIBRARY.so"),
            excluded.map { it.second }.toSet(),
        )
        assertTrue("arm64-v8a 也被裁掉的话，探针在最主流的那档设备上也会判成不可用：$excluded", excluded.none { it.first == "arm64-v8a" })
    }

    /** ⑥ 全仓库只有一处真去 loadLibrary：结论不许被复制到第二个地方自己判断 */
    @Test
    fun systemLoadLibraryAppearsExactlyOnceInMainSources() {
        val hits = mainSources().mapNotNull { source ->
            val times = count(blankCommentsAndLiterals(source.text), "System.loadLibrary(")
            if (times == 0) null else "${source.relative}($times)"
        }
        assertEquals(
            listOf("$MAIN_PREFIX/$PROBE_FILE(1)"),
            hits,
        )
    }

    /** ⑦ 扫码页的四条纪律都落在探针上（降级形状漂走就等于把用户指向死路） */
    @Test
    fun scanScreenPutsEveryDecodeBehindTheProbe() {
        val code = normalize(withoutComments(readSource(SCAN_SCREEN_FILE)))

        assertTrue(
            "读结论的方式不再是\"已判定 Missing\"：未判定被当成不可用的话，arm64 上这一页会先黑一帧",
            code.contains("barhopperNativeLib.verdict == NativeLibVerdict.Missing"),
        )
        assertTrue("scanner 不再以探针结论为键（缺库时它还是会被建出来）：", code.contains("remember(decoderMissing)"))

        val bind = balancedBlock(code, "LaunchedEffect(granted, provider, scannerWorking, analyzer)")
        check(bind.contains("bindToLifecycle(")) { "绑定分析流那一步换形状了，这条守卫要跟着改：\n$bind" }
        assertTrue(
            "bindToLifecycle 之前没先 awaitDecided()：第一帧解码就可能发生在一个未判定的 scanner 上",
            bind.indexOf("barhopperNativeLib.awaitDecided()") in 0 until bind.indexOf("bindToLifecycle("),
        )

        val gallery = balancedBlock(code, "ActivityResultContracts.PickVisualMedia(),")
        check(gallery.contains("scanner.process(")) { "相册那条解码换了写法：\n$gallery" }
        assertTrue(
            "相册识别不再看探针结论 —— 它送进的是同一个 scanner，缺库时同样解不出东西",
            gallery.indexOf("barhopperNativeLib.isAvailable()") in 0 until gallery.indexOf("scanner.process("),
        )

        // 缺库那一档的文案分支：T44 起整条提示档位搬进了 ScanUiStatus.kt 的 scanUiStatus()
        // （纯 JVM 判据，每档都能单测，见 ScanUiStatusTest）。T45 起口径换了：这一页只剩
        // 相机与相册两条入口，而它们用的是**同一颗** scanner —— 缺库时两条一起没，
        // 这一档没有任何出路可指。提到相册（同死的死路）或任何"手输/输入"（已删的入口）
        // 都是谎话，所以这些子串一个都不许出现在这一档的文案里。
        val ladder = normalize(withoutComments(readSource(SCAN_STATUS_FILE)))
        val marker = "decoderMissing -> \""
        val at = ladder.indexOf(marker)
        check(at >= 0) { "缺库那一档的文案分支不在了（或被合并进 scanner 不可用那一档）：$marker" }
        val text = ladder.substring(at, ladder.indexOf('"', at + marker.length))
        for (banned in listOf("相册", "手输", "输入")) {
            assertFalse("解码器整条都不在，这一档却没有老实说用不了，而是提起了「$banned」：$text", text.contains(banned))
        }
    }

    // ---- 源码核对工具（与 ScanChainWarmUpTest 同一套手法）----

    private class Source(val relative: String, val text: String)

    private fun readSource(relativeFromJava: String): String {
        val file = File(findMainJavaDir(), relativeFromJava)
        assertTrue("找不到 ${file.path}：文件挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText()
    }

    private fun mainSources(): List<Source> {
        val javaDir = findMainJavaDir()
        val files = javaDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("${javaDir.path} 下一个 .kt 都没有，路径不对", files.isNotEmpty())
        return files.map { Source("$MAIN_PREFIX/${it.relativeTo(javaDir).path.replace('\\', '/')}", it.readText()) }
    }

    private fun readRepoFile(relativeFromRoot: String): String {
        val file = File(findRepoRoot(), relativeFromRoot)
        assertTrue("找不到 ${file.path}：这条\"漂移就红\"的守卫依赖它", file.isFile)
        return file.readText()
    }

    /** 从 [signature] 之后第一个 `{` 起配平到对应右括号（含）；找不到锚点就抛 */
    private fun balancedBlock(source: String, signature: String): String {
        val at = source.indexOf(signature)
        check(at >= 0) { "找不到 $signature：签名改过家（含换行/参数换行），这条守卫要跟着改" }
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

    /** 从 [openAt] 那个左括号起配平到对应右括号（含两端）；不配平就抛 */
    private fun balancedParens(source: String, openAt: Int): String {
        check(openAt in 0 until source.length && source[openAt] == '(') { "锚点 $openAt 不是左括号" }
        var depth = 0
        for (index in openAt until source.length) {
            when (source[index]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return source.substring(openAt, index + 1)
                }
            }
        }
        throw IllegalStateException("第 $openAt 个左括号没配平")
    }

    /** 只砍行注释（块注释不管：调用方给的必须是不会出现块注释的一小段） */
    private fun lineCommentsOut(source: String): String = source.lines().joinToString("\n") { line ->
        val slash = line.indexOf("//")
        if (slash >= 0) line.substring(0, slash) else line
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

    /** 注释与字符串字面量都抹成空白，长度与换行位置不变：整份文件的红线扫描用这份 */
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

    private fun count(haystack: String, needle: String): Int {
        var n = 0
        var from = 0
        while (true) {
            val at = haystack.indexOf(needle, from)
            if (at < 0) return n
            n++
            from = at + needle.length
        }
    }

    private fun normalize(code: String): String = code.replace(Regex("\\s+"), " ").trim()

    /** 工作目录是模块目录还是仓库根不由这里决定：两种布局都试，全落空就抛 */
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

    /** 同上，认 settings.gradle.kts + app/build.gradle.kts 这一对；单测的 cwd 是 :app 模块目录 */
    private fun findRepoRoot(): File {
        var dir: File? = File("").absoluteFile
        repeat(5) {
            val candidate = dir
            if (candidate != null && File(candidate, "settings.gradle.kts").isFile && File(candidate, BUILD_SCRIPT).isFile) {
                return candidate
            }
            dir = dir?.parentFile
        }
        throw IllegalStateException("找不到仓库根：当前目录 ${File("").absolutePath}")
    }

    private companion object {
        const val MAIN_PREFIX = "app/src/main/java"
        const val PROBE_FILE = "com/buaa/schedule/ui/signin/BarhopperNativeLibProbe.kt"
        const val SCAN_SCREEN_FILE = "com/buaa/schedule/ui/signin/SpocScanScreen.kt"
        const val SCAN_STATUS_FILE = "com/buaa/schedule/ui/signin/ScanUiStatus.kt"
        const val BUILD_SCRIPT = "app/build.gradle.kts"
        const val THREADS = 8
    }
}
