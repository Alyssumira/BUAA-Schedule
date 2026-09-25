package com.buaa.schedule.data.local

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「凡是走 [KeystoreBlobStore] 的 SharedPreferences，都必须出现在两份备份规则的排除名单里」
 * 这把尺子（T85b-B）。
 *
 * 为什么值得单独钉：排除名单是**手写的资源文件**，凭证存储是**另一头写的 Kotlin**。
 * 两边唯一的连线就是一枚字面量（prefs 名 + `.xml`），所以加一枚凭证存储的人
 * 只要没顺手改 res/xml，那枚密文就会安静地跟着云备份上云 —— 编译不响、
 * 单测不响、装机也不响，因为它只在用户换机或开了备份的时候才发生。
 *
 * T84 就漏了这一次：`buaa_cookie_store.xml` 与 `spoc_token_store.xml` 都在名单里，
 * 新加的 `iclass_id_store.xml` 不在，而它和前两枚是同一套密文、同一条排除理由。
 * 那一笔当时只留下一句「本卡记下但没有改」的注释（T85b-A 已经把它兑现）。
 * 本文件要防的是**下一枚**：不靠人记得，靠一次 `testDebugUnitTest`。
 *
 * 判据是从源码里**数出来**的，不是抄一份名单回来对抄 —— 抄来的名单会跟着源码一起漏改。
 * 数法：扫 `app/src/main/java` 下所有 `.kt`，取每个 `KeystoreBlobStore(` 实例化的
 * `prefsName` 字面量（具名实参、或按位置的第一枚字面量都认）。
 *
 * 三条口径说明：
 * - **读不到就是要失败，不许 skip**。刀法照 `IClassSignInWiringGuardTest`：
 *   跳过的守卫比没有守卫更糟，历史上"文件读不到就 assume 掉"掩过真问题。
 * - 只看**工作目录里的源码与资源**，不碰构建产物、不碰网络、不装机，纯 JVM。
 * - `spoc_token_store.xml` 那一行**不在本判据的射程内**，而且不许把它算进来：
 *   `SpocTokenStore.kt` 已随 T85 删除，盘上再没有对应的 prefs 名可数。那行排除是给
 *   **老设备残留**留的，删掉等于把别人设备上的凭证密文送上云 —— 它由两份文件里的注释
 *   把口径钉着，不由这把尺子管（这把尺子只管"现在还在写的存储"）。
 *
 * ⚠️ 改完 `res/xml` 单独重跑本文件时**要带 `--rerun`**：JVM 测试任务的输入是编译产物，
 * 资源文件不在里面，Gradle 会直接端上缓存的绿灯（本轮取证时就是这么"通过"了一次删掉
 * 三行排除的改动）。门禁那侧跑的是 `--rerun-tasks`，不受这个影响。
 */
class BackupRulesCoverCredentialStoresTest {

    /** 主判据：每一枚在用的凭证存储，两份规则文件都得排除掉它 */
    @Test
    fun everyKeystoreBackedCredentialPrefIsExcludedFromBothRuleFiles() {
        val stores = credentialStorePrefNames()
        val legacy = read(RELATIVE_LEGACY)
        val modern = read(RELATIVE_MODERN)

        // 名单本身不许是空的：扫不出任何存储 ⇒ 数法坏了，"全都排除了"就是句空话
        assertTrue(
            "一个 KeystoreBlobStore 实例都没扫出来：要么存储整条搬了家（本守卫要跟着改），" +
                "要么 `prefsName` 的写法换了 ⇒ 下面那些「都排除了」全是假的",
            stores.isNotEmpty(),
        )

        val cloud = excludePathsInBlock(modern, "cloud-backup")
        val transfer = excludePathsInBlock(modern, "device-transfer")
        val legacyPaths = excludePathsInBlock(legacy, "full-backup-content")

        val missing = ArrayList<String>()
        for (pref in stores) {
            val file = "$pref.xml"
            if (file !in cloud) missing += "backup_rules.xml <cloud-backup> 没排除 $file"
            if (file !in transfer) missing += "backup_rules.xml <device-transfer> 没排除 $file"
            if (file !in legacyPaths) missing += "backup_rules_legacy.xml 没排除 $file"
        }
        assertEquals(
            "凭证存储漏出备份名单（漏一行就是 quietly 把密文送上云 / 迁到别的设备）：" +
                "\n" + missing.joinToString("\n"),
            emptyList<String>(),
            missing,
        )
    }

    /**
     * 反向对照：这三块排除名单确实**读得出东西**，所以上面那句「在名单里」不是空集比空集。
     *
     * 不加这一条的话，标签写法一变（比如 `<cloud-backup>` 带了属性、或改了缩进）
     * 就会让解析器返回空集合，于是"什么都没排除"与"排除的正好是扫出来的那些"在断言里长得一样。
     */
    @Test
    fun eachRuleBlockActuallyParsesToANonEmptyExcludeList() {
        val modern = read(RELATIVE_MODERN)
        val legacy = read(RELATIVE_LEGACY)
        for ((label, paths) in listOf(
            "<cloud-backup>" to excludePathsInBlock(modern, "cloud-backup"),
            "<device-transfer>" to excludePathsInBlock(modern, "device-transfer"),
            "<full-backup-content>" to excludePathsInBlock(legacy, "full-backup-content"),
        )) {
            assertTrue(
                "$label 里一条 exclude 都没解析出来：解析器坏了，第一条判据就成了自证",
                paths.size >= 3,
            )
        }
        // 两个通道各自独立：只写在一边等于另一边没排除（T85b-A 之前 iclass 就是这个形状）
        assertEquals(
            "<cloud-backup> 与 <device-transfer> 的排除名单长岔了：两个通道要走同一条口径",
            excludePathsInBlock(modern, "cloud-backup").sorted(),
            excludePathsInBlock(modern, "device-transfer").sorted(),
        )
    }

    /**
     * 靶子自检：这把尺子**真的会红**。
     *
     * 拿一枚没登记的 prefs 名走一遍同一条判据，必须被判成缺三行。
     * 少了这一条，"漏排 iclass_id_store.xml 那次没人发现"完全可以再来一次 ——
     * 因为解析器什么都没看见时，上面两条断言都是绿的。
     */
    @Test
    fun rulerStillReportsAnUnregisteredStore() {
        val modern = read(RELATIVE_MODERN)
        val legacy = read(RELATIVE_LEGACY)
        val probe = "no_such_credential_store"
        for ((label, paths) in listOf(
            "<cloud-backup>" to excludePathsInBlock(modern, "cloud-backup"),
            "<device-transfer>" to excludePathsInBlock(modern, "device-transfer"),
            "<full-backup-content>" to excludePathsInBlock(legacy, "full-backup-content"),
        )) {
            assertFalse("$probe.xml 不该出现在 $label 里", probe + ".xml" in paths)
        }
    }

    // ---- 数存储名的那一段：扫源码，不抄名单 ----

    /**
     * `app/src/main/java` 下每一枚 `KeystoreBlobStore(...)` 实例化用到的 prefs 名。
     *
     * 类声明那一处（`internal class KeystoreBlobStore(`）必须跳过 —— 它的形参表里
     * 一个字符串字面量都没有，混进来也数不出东西，但"按名字扫"这件事要说清楚。
     */
    private fun credentialStorePrefNames(): List<String> {
        val root = File(findMainJavaDir(), "com/buaa/schedule")
        assertTrue("找不到源码目录：$root", root.isDirectory)
        val sources = ArrayList<File>()
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.forEach { sources += it }
        assertTrue("main 源码树里一个 .kt 都没有：路径或工作副本坏了", sources.isNotEmpty())

        val out = LinkedHashSet<String>()
        for (file in sources) {
            val code = blankComments(file.readText())
            var from = 0
            while (true) {
                val at = code.indexOf(CALL, from)
                if (at < 0) break
                from = at + CALL.length
                if (isDeclaration(code, at)) continue
                val args = argumentList(code, at + CALL.length - 1)
                val named = NAMED_PREFS.find(args)
                val pref = named?.groupValues?.get(1) ?: positionalFirstLiteral(args)
                if (pref != null) {
                    assertTrue(
                        "prefs 名不该带路径分隔或空白：$pref（${file.name}）",
                        pref.isNotBlank() && !pref.contains('/') && !pref.contains('\\') && !pref.contains(' '),
                    )
                    out += pref
                } else {
                    throw IllegalStateException(
                        "${file.path} 里有一处 $CALL 取不出 prefsName：实参表=$args。" +
                            "写法换过了要把数法一起改 —— 让它静默不计入，等于给下一枚漏排开门",
                    )
                }
            }
        }
        return out.sorted()
    }

    /** `class KeystoreBlobStore(` / `interface` 之类的声明位，不是实例化 */
    private fun isDeclaration(code: String, at: Int): Boolean {
        val before = code.substring(0, at).trimEnd()
        return before.endsWith("class") || before.endsWith("interface") || before.endsWith("fun")
    }

    /** 从 `open` 处那个左括号起，取到配平的右括号为止（含括号） */
    private fun argumentList(code: String, open: Int): String {
        var depth = 0
        for (index in open until code.length) {
            when (code[index]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return code.substring(open, index + 1)
                }
            }
        }
        throw IllegalStateException("$CALL 的实参表括号没配平")
    }

    /** 位置实参的第一枚字面量就是 prefsName（构造器唯一的 ordering 事实） */
    private fun positionalFirstLiteral(args: String): String? =
        REGEX_STRING_FIRST.find(args)?.groupValues?.get(1)

    private fun excludePathsInBlock(xml: String, block: String): List<String> {
        val open = Regex("<$block\\b[^>]*>").find(xml)
            ?: throw IllegalStateException("规则文件里没有 <$block> 这个块：写法换过了，本守卫要跟着改")
        val close = xml.indexOf("</$block>", open.range.last)
        assertTrue("<$block> 没有闭合标签", close > open.range.last)
        return EXCLUDE_PATH.findAll(xml.substring(open.range.last + 1, close)).map { it.groupValues[1] }.toList()
    }

    /**
     * 读规则文件，并**先把 XML 注释抹掉**。
     *
     * 这两份文件的注释里写的正是排除理由（`<cloud-backup>` 这类标签名一旦出现在注释里，
     * 按标签找块就会找到注释那一段，于是排除名单读自错误区间 —— 而读出自注释的行数
     * 往往比真名单还多，看着更像"通过"）。真名单只可能来自标签本身。
     */
    private fun read(relativeUnderRepoRoot: String): String {
        val file = File(findRepoRoot(), relativeUnderRepoRoot)
        assertTrue("找不到 $relativeUnderRepoRoot：$file", file.isFile)
        return withoutXmlComments(file.readText())
    }

    /** 抹掉 `<!-- … -->`，保留换行，位置错乱不至于把标签吞一半 */
    private fun withoutXmlComments(xml: String): String {
        val out = xml.toCharArray()
        var i = 0
        while (i < out.size) {
            if (xml.startsWith("<!--", i)) {
                val end = xml.indexOf("-->", i + 4).let { if (it < 0) out.size else it + 3 }
                for (k in i until end.coerceAtMost(out.size)) if (out[k] != '\n') out[k] = ' '
                i = end
            } else {
                i++
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

    private fun findRepoRoot(): File {
        var dir: File? = File("").absoluteFile
        repeat(5) {
            if (dir != null && File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir?.parentFile
        }
        throw IllegalStateException("找不到仓库根：当前目录 ${File("").absolutePath}")
    }

    /** 抹掉注释再扫：注释里举过 `KeystoreBlobStore(` 的例子（`IClassIdStore.kt` 的头一段就是） */
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
        const val RELATIVE_MODERN = "app/src/main/res/xml/backup_rules.xml"
        const val RELATIVE_LEGACY = "app/src/main/res/xml/backup_rules_legacy.xml"
        const val CALL = "KeystoreBlobStore("
        val NAMED_PREFS = Regex("""prefsName\s*=\s*"([^"]+)"""")
        val REGEX_STRING_FIRST = Regex("""\(\s*"([^"]+)"""")
        val EXCLUDE_PATH = Regex("""<exclude\b[^>]*?\bpath\s*=\s*"([^"]*)"""")
    }
}
