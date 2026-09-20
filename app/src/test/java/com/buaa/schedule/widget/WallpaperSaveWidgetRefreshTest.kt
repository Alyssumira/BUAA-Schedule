package com.buaa.schedule.widget

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * App 内改了壁纸，桌面上那些组件要跟上 —— 而且只在**一处**收口。
 *
 * `Personalization.save()` 有十几个调用点（设置页各处 + 首页），它写下的
 * `wallpaper_uri` / `wallpaper_use_system` 正是 `WidgetBackgroundRenderer` 决定糊哪张图
 * 读的那两个键。改动前**没有任何一处**在保存后触发组件重绘：用户换完壁纸，
 * 组件还在糊旧图，要等到下一次课表数据刷新才跟上（真机核对：设置页里唯一会主动刷组件的
 * 是那颗手动「立即刷新」按钮）。
 *
 * 为什么又是按源码形状核对：收口本身要 Context（`getSharedPreferences` 在无 Robolectric
 * 的 JVM 单测里是抛 "not mocked" 的桩），跑不了真链路；能跑的判据表在
 * `WidgetGlassSourceTest.saveOnlyNeedsAWidgetRedrawWhenTheTwoKeysTheWidgetReadsChange`。
 * 这里钉的是三种不红的坏形状：
 * ① 漏接（save() 还是不刷，用户仍然要自己去找那颗手动按钮）；
 * ② 在调用点各抄一段刷新代码（十几份，早晚有人漏，而且每份都会压到主线程上）；
 * ③ 收口了但把 binder 往返与解码压回主线程（save() 是从点击回调里调的）。
 */
class WallpaperSaveWidgetRefreshTest {

    /** ① 收口只有一处，而且就在 save() 里 */
    @Test
    fun saveIsTheOnlyChokePointForTheWallpaperDrivenRedraw() {
        val callSites = mutableListOf<String>()
        for (source in mainSources()) {
            val code = blankCommentsAndLiterals(source.text)
            for (at in occurrencesOf(code, "refreshWidgetsAsync(")) {
                val lineStart = code.lastIndexOf('\n', at) + 1
                if (code.substring(lineStart, at).contains("fun ")) continue // 声明自身
                callSites += source.relative
            }
        }
        assertEquals(
            "组件重绘的调用点应当只有 Personalization.save() 这一处（多一处就是有人在复制粘贴，" +
                "少一处就是没收口）：\n$callSites",
            listOf("$MAIN_PREFIX/com/buaa/schedule/core/designsystem/Personalization.kt"),
            callSites,
        )
    }

    /** ② save() 里那一次调用要挂在"这两个键真的变了"上，而且要排在落盘之后 */
    @Test
    fun redrawIsScheduledOnlyAfterThoseTwoKeysActuallyChanged() {
        val body = balancedBlock(withoutComments(readMainSource(PERSONALIZATION_FILE)), "fun save(context: Context)")

        assertEquals(
            "变更判据被问了两遍，或者根本没问（恒刷 = 每拖一次滑块重绘六个组件；不问 = 白重绘）：\n$body",
            1,
            occurrences(body, "WidgetGlassSource.wallpaperKeysChanged("),
        )
        assertEquals(
            "save() 里重绘只许一次：\n$body",
            1,
            occurrences(body, "refreshWidgetsAsync("),
        )
        // 顺序：读旧值 -> 写 prefs -> 再按结论重绘。反过来就是拿旧图重绘一次。
        assertTrue(
            "重绘排在了落盘前面（组件读的是 prefs，早一步就还是旧壁纸）：\n$body",
            body.indexOf("prefs.edit") in 0 until body.indexOf("refreshWidgetsAsync("),
        )
        assertTrue(
            "重绘没有挂在判据上（那是把 15 个调用点全变成重绘点）：\n$body",
            body.contains("if (wallpaperChanged)"),
        )
    }

    /** ③ 重绘整条离开主线程，而且就地吞异常 */
    @Test
    fun redrawLeavesTheMainThreadAndSwallowsItsOwnFailures() {
        val body = balancedBlock(
            withoutComments(readMainSource(BACKGROUND_SYNC_FILE)),
            "fun refreshWidgetsAsync(context: Context)",
        )

        assertTrue(
            "重绘没挪到 IO 线程：save() 是设置页/首页点击回调里调的，" +
                "探测 Launcher + 全学期快照 + 六个 Provider 重绘一笔笔都在主线程上：\n$body",
            body.contains("CoroutineScope(Dispatchers.IO).launch"),
        )
        assertTrue("把活又挪回主线程了：\n$body", !body.contains("Dispatchers.Main"))
        assertTrue(
            "没吞异常：协程默认处理器接不住就是杀进程（同 WidgetCommon.launchRefresh 那条 R5 F-24 的账）：\n$body",
            body.contains("runCatching"),
        )
    }

    /** ④ 挑图那条链只有一份：设置页与组件配置页共用，谁都不许自己写那两个键 */
    @Test
    fun pickedWallpaperIsStoredThroughExactlyOneHelper() {
        val personalization = readMainSource(PERSONALIZATION_FILE)
        val helper = balancedBlock(withoutComments(personalization), "fun applyPickedWallpaper(context: Context, uri: Uri)")

        assertTrue(
            "挑图那条链没有顺手把 URI 写进 Personalization（组件读的就是它）：\n$helper",
            helper.contains("wallpaperUri = uri.toString()") && helper.contains("save(context)"),
        )
        assertTrue(
            "丢了持久授权：OpenDocument 的 URI 默认只授权到本次会话，进程重启后组件读不到那张图：\n$helper",
            helper.contains("takePersistableUriPermission"),
        )

        var writers = 0
        for (source in mainSources()) {
            writers += occurrences(blankCommentsAndLiterals(source.text), "wallpaperUri = uri.toString()")
        }
        assertEquals(
            "把 SAF 挑回来的 URI 写进状态的那句话全仓库只许有一份（第二份就是复制粘贴的那条链）",
            1,
            writers,
        )
    }

    // ---- 源码核对工具（抄 ColdStartRebuildWiringTest）----

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

    /** 从 [signature] 之后第一个 `{` 起配平到对应右括号（含），返回整段（嵌套分支一起数） */
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

    /** 抹注释、留字面量：切单个函数体用这份（锚点带中文文案） */
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
     * （这两处说明都不许把那两个字符写全：KDoc 自己是块注释，而 Kotlin 的块注释能嵌套 ——
     * 写了就等于把后面的代码关进注释里。）
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

    private fun occurrences(haystack: String, needle: String): Int = occurrencesOf(haystack, needle).size

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

    private companion object {
        const val PERSONALIZATION_FILE = "com/buaa/schedule/core/designsystem/Personalization.kt"
        const val BACKGROUND_SYNC_FILE = "com/buaa/schedule/widget/BackgroundSync.kt"

        /** 报告里的路径前缀：人一眼认得出这是主源码，不用去猜相对谁 */
        const val MAIN_PREFIX = "app/src/main/java"
    }
}
