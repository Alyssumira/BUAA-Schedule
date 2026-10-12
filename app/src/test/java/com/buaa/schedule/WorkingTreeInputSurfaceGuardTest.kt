package com.buaa.schedule

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 守卫的输入面对账（T315①）。
 *
 * `:app:testDebugUnitTest` 的工作树输入面 `guardReadWorkingTreeFiles`（T86 / T92① 落地，
 * 登记在 `app/build.gradle.kts` 里 `val guardReadWorkingTreeFiles = files(` 那一段）今天**没有
 * 任何一枚尺**拿它跟「守卫真读到哪些文件」对账 —— 本卡第 0 问的缺口就是这么量出来的：
 * `ReleaseArtifactSignerSingleRulerGuardTest` 册子⑥（`theDebugSignerConstantHasOneWriterOneReaderAndTheHandbookAgrees`）
 * 在 `:327` 逐字读发版手册那一页找三枚针，路径常量在该文件 `:468`，而登记面里 docs 一格都没有 ⇒
 * 手册单独改一改，测试任务判 UP-TO-DATE、端一枚旧绿（同一条命令的改前/改后行为读数在 T315 回执）。
 *
 * 钉法 = 三向对账，只读文本、纯 JVM、零 assume：
 * 1. 读者 ⊆ 登记：`app/src/test` 里每一枚「整条字面量就是一条 markdown 路径」的格，必须在登记面里
 *    找得着（那枚文件本身，或它所在的已登记目录）—— 漏登记当场红。
 * 2. 登记 ⊆ 读者（只数 docs 那一族）：登记面里指向 markdown 的每一格都得有读者 —— 登记整目录
 *    （`../docs`）或没人读的 `../README.md` 都算多登记，当场红。这一向管的是**代价**：铺到整目录
 *    等于「改任一 docs 就重跑全量测试」，而那一节自己写的口径是「逐项列的都是真读到的路径」。
 * 3. 登记的每条路径在盘上存在：写错一个字母的登记 = Gradle 眼里的**空输入**，后果与第 1 向要修的
 *    那格一模一样（改了不重跑、永远绿），所以这一向也红。
 *
 * ⚠️ 覆盖面边界（如实写，别当它没有）：第 1、2 两向只吃 markdown 字面量。把尺子铺到别的扩展名
 * 今天会假红 —— 普查数到 7 枚测试文件写着仓库根探针 `File(dir, "settings.gradle.kts").isFile`
 * （BackupRulesCoverCredentialStoresTest、BarhopperNativeLibProbeTest、IClassSignInWiringGuardTest、
 * ScanBlankDecodingWiringGuardTest、ScanFrameAidWordingWiringGuardTest、
 * ScanFrameObservationWiringGuardTest、ScanSecondEngineWiringGuardTest），那七处一个字节内容都没读，
 * 纯粹是找根。要按「内容有没有被读」判，得先有一把「字面量落在 readText 链上」的尺 —— 本卡不猜。
 * androidTest 那棵树零枚 markdown 读者（同一把尺扫过），它也不跑 JVM 门禁。
 */
class WorkingTreeInputSurfaceGuardTest {

    @Test
    fun everyMarkdownPathReadByATestIsRegisteredAsATestInput() {
        val entries = registeredEntries()
        val readers = markdownReaders()
        val unregistered = readers.filterKeys { path -> !coveredByAny(path, entries) }.toList().sorted()
        assertTrue(
            "钉法 = 集合对账（读者 ⊆ 登记）：守卫按文本读到的每一枚 markdown 路径都得登记成测试任务的输入，" +
                "漏一格就是「改了它却不重跑」那一档假绿。登记面 " + entries.size + " 格：" + entries.sorted() +
                " ｜ 读者册子：" + readers.keys.sorted() +
                " ｜ 未登记的读者：" + unregistered.joinToString(" ｜ ") { (path, where) -> path + " ← " + where },
            unregistered.isEmpty(),
        )
    }

    @Test
    fun everyRegisteredMarkdownEntryHasAReader() {
        val entries = registeredEntries()
        val readers = markdownReaders()
        val deadWeight = entries
            .filter { entry -> entry.isDocsFamily() && readers.keys.none { path -> path == entry } }
            .sorted()
        assertTrue(
            "钉法 = 集合对账（登记 ⊆ 读者，只数 docs 那一族）：登记面里指向 markdown 的每一格都得有读者 —— " +
                "登记整目录（`docs`）或没人读的 README 都算多登记，多一格的代价是「改任一 docs 就重跑全量测试」，" +
                "而那一节的口径是逐项列真读到的路径。读者册子：" + readers.keys.sorted() +
                " ｜ 多登记的格：" + deadWeight,
            deadWeight.isEmpty(),
        )
    }

    @Test
    fun everyRegisteredEntryResolvesToSomethingOnDisk() {
        val appDir = File(repoRoot(), "app")
        val raw = registeredRaw()
        val missing = raw.filter { entry -> !File(appDir, entry).exists() }
        assertTrue(
            "钉法 = 存在性：登记成输入的每条路径必须在盘上存在 —— 不存在的路径在 Gradle 眼里是空输入，" +
                "登记写错一个字母等于静默少一格，与本卡要修的假绿同形。枚数：" + raw.size +
                " ｜ 盘上找不到的格：" + missing.joinToString(" ｜ ") { entry -> entry + " → " + File(appDir, entry).path },
            missing.isEmpty(),
        )
    }

    // ---- 读盘与小工具 ------------------------------------------------------------------------------

    /** 登记面原文（注释已抹，只留 `files(` 里那些引号格） */
    private fun registeredRaw(): List<String> {
        val script = File(repoRoot(), "app/build.gradle.kts").readText()
        val marker = "guardReadWorkingTreeFiles = files("
        val at = script.indexOf(marker)
        assertTrue(
            "找不到「$marker」：输入面那一节挪了家要回来改这里 —— 本卡的三向对账全押在它上面",
            at >= 0,
        )
        val open = at + marker.length - 1
        var depth = 0
        var index = open
        while (index < script.length) {
            val ch = script[index]
            if (ch == '(') {
                depth++
            } else if (ch == ')') {
                depth--
                if (depth == 0) break
            }
            index++
        }
        assertTrue(
            "「$marker」后面找不到配平的右括号（走到 " + index + " 停下）：那一节被改动过，先看盘再谈绿",
            depth == 0,
        )
        val block = blankComments(script.substring(open + 1, index))
        val entries = QUOTED.findAll(block).map { match -> match.groupValues[1] }.toList()
        assertTrue(
            "登记面读出来是空的（实到 " + entries.size + " 格）：解析漂了就不要拿这一格当绿",
            entries.isNotEmpty(),
        )
        return entries
    }

    /**
     * 归一到「仓库根相对」：不带 `../` 的格是相对 `:app` 模块目录写的，带 `../` 的往仓库根走。
     * 与构建脚本那段的 `PathSensitivity.RELATIVE` 同一口径，只是这里要拿它跟读者对表。
     */
    private fun registeredEntries(): List<String> = registeredRaw().map { entry ->
        if (entry.startsWith("../")) entry.removePrefix("../") else "app/$entry"
    }

    /**
     * 读者册子：`app/src/test` 下「整条字面量就是一条 markdown 路径」的格。
     * 行法 = 逐行取双引号字面量，先跳过整行注释（行首 `//`、`/*`、`*`），再只留无空白、无模板、
     * 无转义而以 `.md` 收尾的 —— 报错文案里那种带空白与 `§` 的散文引用（普查数到 10 处）天然落不
     * 进尺子。本文件自己不进册子（同那枚 `assumeTrue` 针的手法）。
     */
    private fun markdownReaders(): Map<String, String> {
        val root = repoRoot()
        val testRoot = File(root, "app/src/test")
        assertTrue("找不到 app/src/test：这一格的覆盖面就是它", testRoot.isDirectory)
        val files = testRoot.walkTopDown()
            .filter { file -> file.isFile && file.extension == "kt" }
            .toList()
            .sortedBy { file -> file.path }
        val found = linkedMapOf<String, String>()
        for (file in files) {
            if (file.name == SELF) continue
            file.readText().lines().forEachIndexed { lineIndex, rawLine ->
                val line = rawLine.trimStart()
                if (line.startsWith("//") || line.startsWith("/*") || line.startsWith("*")) return@forEachIndexed
                QUOTED.findAll(line).forEach literal@{ match ->
                    val literalText = match.groupValues[1]
                    if (!MARKDOWN_PATH.matches(literalText)) return@literal
                    val path = literalText.removePrefix("../")
                    if (!found.containsKey(path)) {
                        found[path] = relativeToRoot(root, file) + " 的 :" + (lineIndex + 1)
                    }
                }
            }
        }
        return found
    }

    private fun coveredByAny(path: String, entries: List<String>): Boolean =
        entries.any { entry -> entry == path || path.startsWith("$entry/") }

    private fun String.isDocsFamily(): Boolean =
        this == "docs" || startsWith("docs/") || endsWith(".md")

    private fun relativeToRoot(root: File, file: File): String =
        file.relativeTo(root).path.replace(File.separatorChar, '/')

    /** 工作目录是模块目录还是仓库根不由这里决定：几种布局都试一遍（同 ReleaseArtifactSignerSingleRulerGuardTest） */
    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        var hops = 0
        while (dir != null && hops < 5) {
            val current = dir!!
            if (File(current, "app/build.gradle.kts").isFile) return current
            val parent = current.parentFile
            if (parent != null && File(parent, "app/build.gradle.kts").isFile) return parent
            dir = parent
            hops++
        }
        throw IllegalStateException("找不到仓库根：当前目录 ${File("").absolutePath}")
    }

    /** 抹注释保行号：与 UserVisibleParenBalanceGuardTest 同一写法（字符串与行位置不动） */
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
                            src.startsWith("/*", j) -> {
                                depth++
                                j += 2
                            }

                            src.startsWith("*/", j) -> {
                                depth--
                                j += 2
                            }

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
                            src[j] == quote -> {
                                j++
                                break
                            }

                            src[j] == '\n' -> break
                            else -> j++
                        }
                    }
                    i = j
                }

                else -> i++
            }
        }
        return out.toString()
    }

    private companion object {
        const val SELF = "WorkingTreeInputSurfaceGuardTest.kt"

        /** 一条双引号字面量（原文逐行喂进来，跨行的 raw string 落不进这把尺） */
        val QUOTED = Regex("\"([^\"]*)\"")

        /** 整条字面量就是一条 markdown 路径：无空白、无模板、无转义 */
        val MARKDOWN_PATH = Regex("[A-Za-z0-9_./-]+[.]md")
    }
}
