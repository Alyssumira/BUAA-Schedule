package com.buaa.schedule.reminder

import java.io.File
import java.util.zip.ZipFile
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * 「release 包里那几行取证 logcat 还在不在」的守卫（T21，2026-09-19）。
 *
 * 为什么值得单独钉：审计 `docs/AUDIT-BATTERY-2026-09-18.md` §4.3 数的是**持锁次数**
 * （`logcat -d -s WakeLocks | grep -oE "tag=[a-z_]+" | sort | uniq -c`，一次持锁恰好一行），
 * §4.4 的过滤条件是 `ClassProgressScheduler` 那句「下课后续排课堂窗口失败」，
 * T11 / T12 / T13 三张卡的设备侧证据分别读 `ReminderScheduler`、`ClassProgressDnd`、`ColdStartRebuild`
 * 那几句中文判据。这些行一旦被压缩规则当无用代码删掉，省电回归在真机上出问题时手上一个证据都没有；
 * 而 **minify 只在 release 生效，JVM 单测对这条路径本来完全无感** ——
 * `docs/APK-SIZE-AUDIT-2026-09-19.md` §8 第 1 条欠的就是这笔"运行时结论全部靠静态证据"的账。
 *
 * 本轮实测结论（同文档 §9）：**上面这些行在 release 产物里全部活着，一行没死**。
 * 机制上的原因是 AGP 8.13 那份 `proguard-android-optimize.txt` 里**没有**
 * `-assumenosideeffects class android.util.Log` 那一段（老版本 AGP 有，那是"release 里 Log.d 全灭"
 * 这个流传很广的说法的来源），本工程自己的规则文件与全部依赖的 consumer rules 里也没有。
 * 死掉的只有 `BuildConfig.DEBUG` 之后那几行诊断日志（`GlassDiag` / `jankRate=`），那是常量折叠，
 * 设计如此 —— 而它们恰好充当本文件所有产物层断言的**反向对照**：R8 确实会重建常量池，
 * 所以"字面量在 dex 里"不是恒真。
 *
 * 三层断言，强度与可跑性不同，别混着读：
 * 1. [noRuleFileStripsAndroidUtilLog] / [releaseStillUsesTheAuditedShrinkingConfig] —— 恒跑，
 *    钉规则层与构建脚本形状，只证明"没人写过删 Log 的规则"，**不证明产物里真的有**；
 * 2. [forensicSitesAreStillLogCalls] —— 恒跑，钉的是**源码层**：那几行还在、级别没变、
 *    消息字面量还是同一句、而且不是资源条目。这条同样**不证明产物**；
 * 3. [minifiedDexStillCarriesEveryForensicLine] / [effectiveR8ConfigCarriesNoLogStrippingRule] ——
 *    读 `:app:assembleRelease` 的产物（APK 里的 classes\*.dex、R8 dump 出的 configuration.txt），
 *    **没有产物时用 assumeTrue 跳过**（`testDebugUnitTest` 不依赖 `assembleRelease`，产物在不在
 *    取决于谁最后跑过什么）。跳过不等于没事：要连着跑
 *    `:app:assembleRelease :app:testDebugUnitTest` 才吃到第 3 层。
 *
 * 还有最后一层谁都在 JVM 里给不出：release 装机后 `logcat -s WakeLocks` 真吐得出那一行。
 * 产物里有常量、有 invoke 指令 ≠ 运行时那一路径被走到过 —— 这一条交回编排者真机验收。
 *
 * 解析上的两个已知坑都绕开了：① 不做"先把整份文件的注释抹掉再找字面量"那一步
 * （字面量里出现的 `[*]/` 或 `/[*]` 会让那种写法一路吞掉后文，本仓库踩过一次），这里只扫字面量本身；
 * ② 断言打的全是产物与规则文件，测试里没有复刻任何生产分支。
 */
class ReleaseForensicLogSurvivalTest {

    companion object {
        /** 一行取证：源码位置、logcat 级别、tag、消息字面量开头，以及它撑着的那本账 */
        private data class Site(
            val file: String,
            val level: String,
            val tag: String,
            val messageHead: String,
            val why: String,
            /** 跨行 `+` 拼接的消息后半：单个字面量扫不到，只能点名 */
            val extraFragments: List<String> = emptyList(),
        )

        /** 编排者那几本账用到的全部取证行（§4.3 / §4.4 / T11 / T12 / T13 / T14 的过滤条件） */
        private val SITES = listOf(
            Site(
                "reminder/WakeLocks.kt", "d", "WakeLocks", "唤醒锁跑完 tag=",
                "§4.3 的持锁次数账：一次持锁恰好一行，少了它次数就数不到",
            ),
            Site(
                "reminder/WakeLocks.kt", "w", "WakeLocks", "唤醒锁没跑赢超时 tag=",
                "§2.5 的「跑赢 10 秒上限」告警",
                extraFragments = listOf("block 期间锁已被系统收回"),
            ),
            Site(
                "reminder/ClassProgressScheduler.kt", "w", "ClassProgressScheduler", "下课后续排课堂窗口失败",
                "§4.4 的过滤条件本身，删了等于账本瞎掉",
            ),
            Site(
                "widget/ColdStartRebuild.kt", "d", "ColdStartRebuild", "冷启动后台重建：",
                "T13 闸门的中文判据行（§4.4-b 的改前/改后对账读的就是它）",
            ),
            Site(
                "widget/ColdStartRebuild.kt", "w", "ColdStartRebuild", "后台链路初始化失败：",
                "闸门「这一轮有没有失败」的落点",
            ),
            Site(
                "reminder/ClassProgressDnd.kt", "w", "ClassProgressDnd", "勿扰已过恢复期限仍未收到下课铃",
                "T12 硬超时恢复的唯一应用侧痕迹",
            ),
            Site(
                "reminder/ReminderScheduler.kt", "d", "ReminderScheduler", "正处在课堂窗口内",
                "T11 判据放行的证据",
            ),
            Site(
                "reminder/LiveClassResyncer.kt", "d", "LiveClassResyncer", "课堂窗口内补起课程实况",
                "T12 四条自愈路之一，没日志就无法证明是它补的",
            ),
            Site(
                "reminder/CourseFluidService.kt", "d", "CourseFluidService", "上课铃未在窗口内落地",
                "勿扰自愈依赖的那条补排路",
            ),
            Site(
                "reminder/TomorrowPreviewReceiver.kt", "d", "TomorrowPreviewReceiver", "往后没有可推的明日预告",
                "链条停止的判据行",
            ),
            Site(
                "reminder/BootReceiver.kt", "w", "BootReceiver", "开机/升级后台重建失败",
                "开机链的失败痕迹",
            ),
            Site(
                "reminder/ClassProgressReceiver.kt", "w", "ClassProgressReceiver", "续排下一节课堂窗口失败",
                "下课铃之后那一步的失败痕迹",
            ),
        )

        /** 只以 `Decision.reason` 进日志的闸门判据（不是 Log 调用的直接实参，单独列） */
        private val GATE_REASONS = listOf(
            "widget/ColdStartRebuild.kt" to listOf(
                "无成功记录：整链重跑",
                "已过 ",
                "时钟回摆",
                "自己的闹钟不在了",
                "三把钥匙均放行：跳过重建整链",
            ),
        )

        /**
         * 反向对照：`BuildConfig.DEBUG` 之后那几行诊断日志的消息，release 产物里**必须查无此文**。
         * 它们不在 ⇒ 读到的确实是折叠过 DEBUG 分支的 minified 产物 ⇒ 上面那些"在"才算证据。
         */
        private val DEBUG_ONLY_FRAGMENTS = listOf("GlassDiag", "jankRate=")

        private val TEMPLATE = Regex("""\$\{[^{}]*\}|\$[A-Za-z_][A-Za-z0-9_]*""")
    }

    // ---- 1. 规则层与构建脚本形状（恒跑）-------------------------------------------------------------

    /** 仓库自己的规则文件里，不许出现目标类能盖住 `android.util.Log` 的 assume 规则 */
    @Test
    fun noRuleFileStripsAndroidUtilLog() {
        val root = projectDir()
        val files = listOf(
            File(root, "app/proguard-rules.pro"),
            File(root, "kyant-backdrop/consumer-rules.pro"),
        )
        for (f in files) {
            assertTrue("找不到规则文件 ${f.path}：路径变了本文件要跟着改，不然扫的是空气", f.isFile)
        }
        val offenders = files.flatMap { f -> logStrippingRules(f.readText()).map { "${f.name} -> $it" } }
        assertEquals(
            "这些 -assumenosideeffects / -assumevalues 的目标类能盖住 android.util.Log：R8 会把" +
                "返回值没被用的 Log 调用整条删掉，§4.3 的次数账与 §2.5 的告警当场失去数据" +
                "（老版本 AGP 的 proguard-android-optimize.txt 里就有这一条，那时 release 里 Log.d 全灭）。" +
                "确实要加的话，先重跑 :app:assembleRelease 与本文件，再决定哪几行可以让出去：\n" +
                offenders.joinToString("\n"),
            emptyList<String>(),
            offenders,
        )
    }

    /**
     * release 仍在用被审过的那套收缩配置。
     *
     * 明说这一条的性质：它是**形状**断言，只钉"构建脚本没换过收缩口径"，不钉产物内容。
     * 换默认规则文件、关掉 minify 或关掉资源收缩，都会让第 3 层读到的东西与发布包不再是同一份，
     * 所以要先红在这里，而不是让产物层静默地量一个不收缩的包。
     */
    @Test
    fun releaseStillUsesTheAuditedShrinkingConfig() {
        val gradle = File(projectDir(), "app/build.gradle.kts")
        assertTrue("找不到 app/build.gradle.kts：${gradle.path}", gradle.isFile)
        val text = gradle.readText()
        assertTrue(
            "release 的默认规则文件换了：被审过的那一份（AGP 8.13 的 proguard-android-optimize.txt）" +
                "里没有 -assumenosideeffects class android.util.Log，换一份就得重查",
            text.contains("""getDefaultProguardFile("proguard-android-optimize.txt")"""),
        )
        assertTrue(
            "app/proguard-rules.pro 不再挂在 release 上：第 1 层扫的就是它，扫错文件等于没扫",
            text.contains("\"proguard-rules.pro\""),
        )
        val releaseBlock = text.substringAfter("buildTypes {").substringBefore("compileOptions {")
        assertTrue(
            "release 的 isMinifyEnabled 被关了：那等于本卡的产物层量的是一个不收缩的包，" +
                "取证日志当然还在 —— 但发布包不是它",
            Regex("isMinifyEnabled\\s*=\\s*true").containsMatchIn(releaseBlock),
        )
        assertTrue(
            "release 的 isShrinkResources 被关了：取证文案必须是 dex 常量（见 " +
                "[forensicSitesAreStillLogCalls] 里那条「不许是资源条目」），" +
                "一旦搬进 strings.xml，删除路径就换成 R8 的资源收缩，本文件的判据要整个重写",
            Regex("isShrinkResources\\s*=\\s*true").containsMatchIn(releaseBlock),
        )
    }

    // ---- 2. 源码层（恒跑，只证明源码）-------------------------------------------------------------

    /**
     * 那几行取证还在、级别没被换走、消息字面量还是同一句，而且**是代码字面量而不是资源条目**。
     *
     * 这一条只证明源码层。R8 删不删，看第 3 层；它的作用是别让学生层的靶子悄悄变成别的东西。
     */
    @Test
    fun forensicSitesAreStillLogCalls() {
        val resourceText = resValuesText()
        for (site in SITES) {
            val src = mainSource(site.file)
            val literals = stringLiterals(src)
            val hit = literals.firstOrNull { it.second.startsWith(site.messageHead) }
            assertTrue(
                "${site.file} 里找不到以「${site.messageHead}」开头的字符串字面量：" +
                    "这行取证（${site.why}）要么被改名要么被删了 —— 改名的话审计 §4.3/§4.4 的过滤条件要一起改",
                hit != null,
            )
            val at = hit!!.first
            val call = src.lastIndexOf("Log.", at)
            assertTrue("${site.file} 的字面量前面找不到 Log. 调用：它不再进 logcat 了", call >= 0)
            val level = src.substring(call + "Log.".length).takeWhile { it.isLetterOrDigit() }
            assertEquals("${site.file} 那行的 logcat 级别变了", site.level, level)
            assertTrue(
                "${site.file} 里那行要显式带上 tag「${site.tag}」：换了 tag，`logcat -d -s ${site.tag}` 抓不到",
                literals.any { it.second == site.tag },
            )
            assertTrue(
                "取证文案「${site.messageHead}」跑到了 res/values 里：那样它就改由资源收缩负责删除" +
                    "（另一条路径，与 R8 删代码无关），第 3 层的判据要整个重写",
                !resourceText.contains(site.messageHead),
            )
            for (extra in site.extraFragments) {
                assertTrue("${site.file} 里拼接消息的另一半「$extra」不见了", literals.any { it.second.contains(extra) })
            }
        }
        for ((file, reasons) in GATE_REASONS) {
            val literals = stringLiterals(mainSource(file))
            for (r in reasons) {
                assertTrue(
                    "$file 里闸门判据「$r」变了或没了：§4.4-b 那本改前/改后账读的就是这句",
                    literals.any { it.second.contains(r) },
                )
            }
        }
    }

    // ---- 3. 产物层（没有 release 产物时跳过）------------------------------------------------------

    /**
     * 最终 dex 里每一行取证的**消息常量与 tag** 都还在，而且 `BuildConfig.DEBUG` 专属那几句确实不在。
     *
     * 判据强度要说清楚：字符串在常量池里 + 反向对照成立（R8 真的重建了池），这两条合起来才说明
     * "这行没被当无用代码删掉"。逐调用点的 invoke 指令是本轮用 `apkanalyzer dex code` 与 `dexdump -d`
     * 人工核过并登记在 `docs/APK-SIZE-AUDIT-2026-09-19.md` §9 的，本方法不重复那个解析。
     */
    @Test
    fun minifiedDexStillCarriesEveryForensicLine() {
        val apk = releaseApk()
        assumeTrue(
            "没有 :app:assembleRelease 的产物（app/build/outputs/apk/release/\u002a.apk），产物层无从判断" +
                "—— 恒跑的那两条（规则层 + 源码层）才是这条链的下限",
            apk != null,
        )
        val dex = dexBytes(apk!!)
        val absent = ArrayList<String>()
        val present = ArrayList<String>()

        for (site in SITES) {
            val literals = stringLiterals(mainSource(site.file))
            val head = checkNotNull(literals.firstOrNull { it.second.startsWith(site.messageHead) }) {
                "${site.file} 的消息字面量变了，先修 [forensicSitesAreStillLogCalls]"
            }.second
            for (fragment in fragmentsOf(head) + site.extraFragments) {
                (if (dexContains(dex, fragment)) present else absent) += "${site.tag} · $fragment"
            }
            (if (dexContains(dex, site.tag)) present else absent) += "tag=${site.tag}"
        }
        for ((file, reasons) in GATE_REASONS) {
            val literals = stringLiterals(mainSource(file))
            for (r in reasons) {
                // 不挑"第一条含该词的字面量"（可能挑错）：把所有候选字面量拆成静态片段，
                // 任一含该词的片段进了 dex 就算通过。
                val candidates = literals.filter { it.second.contains(r) }
                    .flatMap { fragmentsOf(it.second) }
                    .filter { it.contains(r) }
                check(candidates.isNotEmpty()) { "$file 里没有含「$r」的字面量，先修 [forensicSitesAreStillLogCalls]" }
                val hit = candidates.firstOrNull { dexContains(dex, it) }
                (if (hit != null) present else absent) += "$file · ${hit ?: candidates.first()}"
            }
        }
        // 靶子不能是空的：一条都没扫到时，"全都活着"这个结论是假的
        assertTrue("一个取证片段都没扫出来（解析器或路径坏了？）：present=${present.size}", present.size > 20)
        assertEquals(
            "release dex 里查无这些取证片段 ⇒ 它们被 R8 删了，§4.3 的次数账与 §4.4 的过滤条件在发布包里是瞎的" +
                "（本轮结论应为一条都不缺）：\n" + absent.joinToString("\n"),
            emptyList<String>(),
            absent,
        )

        val leaked = DEBUG_ONLY_FRAGMENTS.filter { dexContains(dex, it) }
        assertEquals(
            "DEBUG 专属的诊断字面量出现在了这份产物里：它不是 minified release（读错文件了？" +
                "isMinifyEnabled 被关了？BuildConfig.DEBUG 漏成 true？）" +
                "，那么上面那些「在」也就不构成证据：$leaked",
            emptyList<String>(),
            leaked,
        )
    }

    /** R8 dump 出的**生效**规则集里没有针对 android.util.Log 的 assume 规则（含依赖自带的 consumer rules） */
    @Test
    fun effectiveR8ConfigCarriesNoLogStrippingRule() {
        val file = File(projectDir(), "app/build/outputs/mapping/release/configuration.txt")
        assumeTrue(
            "没有 R8 的 configuration.txt 产物（跑过 :app:assembleRelease 才有），生效规则集读不到",
            file.isFile,
        )
        val text = file.readText()
        val offenders = logStrippingRules(text)
        assertEquals(
            "R8 实际吃下去的规则集里有目标类能盖住 android.util.Log 的 assume 规则" +
                "（多半来自某个依赖的 consumer rules，仓库里扫不到）：\n" + offenders.joinToString("\n"),
            emptyList<String>(),
            offenders,
        )
        // 反向对照：这份配置里确实有 assume 规则（Compose / coroutines / androidx.startup 自带的那几条），
        // 上面判 0 不是因为解析器什么都没看见。
        assertTrue(
            "解析器在生效配置里一条 -assumenosideeffects / -assumevalues 都没看见：" +
                "那「判 0 条 Log 规则」就没有意义，八成是格式变了",
            assumeRuleBlocks(text).isNotEmpty(),
        )
    }

    // ---- 词法与文件小工具 --------------------------------------------------------------------------

    /**
     * 挑出目标类可能盖住 `android.util.Log` 的 `-assumenosideeffects` / `-assumevalues` 规则。
     *
     * 类名模式按 ProGuard 的写法解释：单个 `*` 不跨包名分隔符，`**` 才跨 ——
     * 所以 Compose 自带的那条 `-assumenosideeffects class * { static ...Config *(...) return null; }`
     * 命中的是默认包，不算盖住 `android.util.Log`（同一次构建的产物层也独立证明了它的 Log 调用还在）。
     * 命中即判红，不再精细判断成员模式能不能匹配 `int d(String,String)` ——
     * 本工程不需要这种规则，而"看情况放行"的守卫下一轮就会被人写成永远放行。
     */
    private fun logStrippingRules(text: String): List<String> =
        assumeRuleBlocks(text).filter { block ->
            val pattern = block.substringAfter("class").substringBefore("{")
                .split(' ').filterNot { it in setOf("public", "final", "abstract", "class", "") }
                .joinToString(".")
            classNameRegex(pattern).matches("android.util.Log")
        }

    /** ProGuard 类名模式 -> 正则：`**` 跨包，`*` 不跨，`.` 是分隔符 */
    private fun classNameRegex(pattern: String): Regex {
        val sb = StringBuilder()
        var i = 0
        while (i < pattern.length) {
            when {
                pattern.startsWith("**", i) -> { sb.append(".*"); i += 2 }
                pattern[i] == '*' -> { sb.append("[^.]*"); i++ }
                pattern[i] == '.' -> { sb.append("\\."); i++ }
                pattern[i] == '$' -> { sb.append("\\$"); i++ }
                else -> { sb.append(pattern[i]); i++ }
            }
        }
        return Regex(sb.toString())
    }

    /** 每条 assume 规则的完整文本（成员块可能跨行时把行吃到闭合的 `}` 为止） */
    private fun assumeRuleBlocks(text: String): List<String> {
        val out = ArrayList<String>()
        for (option in listOf("-assumenosideeffects", "-assumevalues")) {
            var from = 0
            while (true) {
                val at = text.indexOf(option, from)
                if (at < 0) break
                var end = text.indexOf('\n', at).let { if (it < 0) text.length else it }
                while (text.substring(at, end).count { it == '{' } > text.substring(at, end).count { it == '}' }) {
                    val next = text.indexOf('\n', end + 1)
                    if (next < 0) { end = text.length; break }
                    end = next
                }
                out += text.substring(at, end).replace(Regex("\\s+"), " ").trim()
                from = end
            }
        }
        return out
    }

    /** 一条 Kotlin 字符串字面量按 `$x` / `${expr}` 拆成的静态片段（dex 里存的就是这些片段） */
    private fun fragmentsOf(literal: String): List<String> =
        TEMPLATE.split(literal).filter { it.length >= 3 }

    /**
     * 只扫字符串字面量，返回"内容起始下标 + 还原转义后的内容"。
     *
     * 刻意不"先抹注释再找字面量"：本仓库踩过一次，字面量里写的注释符号会让那种写法一路吞掉后文。
     * 只扫字面量时注释里的假字面量根本进不来 —— 扫描器从不在注释里认引号。
     * `${ ... }` 里的引号也不算结束（`xh=${if (blank) "(空)" else "已填"}` 就是这种形状）。
     */
    private fun stringLiterals(src: String): List<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>()
        var i = 0
        while (i < src.length) {
            when {
                src.startsWith("//", i) -> i = src.indexOf('\n', i).let { if (it < 0) src.length else it }
                src.startsWith("/*", i) -> i = skipBlockComment(src, i + 2)
                src.startsWith("\"\"\"", i) -> {
                    val end = src.indexOf("\"\"\"", i + 3)
                    if (end < 0) return out
                    out += (i + 3) to src.substring(i + 3, end)
                    i = end + 3
                }
                src[i] == '"' -> {
                    val sb = StringBuilder()
                    var j = i + 1
                    var brace = 0
                    while (j < src.length) {
                        val c = src[j]
                        when {
                            c == '\\' -> { sb.append(unescape(src, j)); j += 2 }
                            c == '$' && j + 1 < src.length && src[j + 1] == '{' -> {
                                brace++; sb.append('$'); sb.append('{'); j += 2
                            }
                            c == '{' && brace > 0 -> { brace++; sb.append(c); j++ }
                            c == '}' && brace > 0 -> { brace--; sb.append(c); j++ }
                            c == '"' && brace == 0 -> break
                            else -> { sb.append(c); j++ }
                        }
                    }
                    out += (i + 1) to sb.toString()
                    i = if (j < src.length) j + 1 else src.length
                }
                src[i] == '\'' -> i = skipCharLiteral(src, i + 1)
                else -> i++
            }
        }
        return out
    }

    /** 块注释吃到配平的结束符（Kotlin 允许嵌套），返回注释之后的下标 */
    private fun skipBlockComment(src: String, from: Int): Int {
        var depth = 1
        var j = from
        while (j < src.length && depth > 0) {
            when {
                src.startsWith("*/", j) -> { depth--; j += 2 }
                src.startsWith("/*", j) -> { depth++; j += 2 }
                else -> j++
            }
        }
        return j
    }

    private fun skipCharLiteral(src: String, from: Int): Int {
        var j = from
        while (j < src.length) {
            when (src[j]) {
                '\\' -> j += 2
                '\'' -> return j + 1
                else -> j++
            }
        }
        return j
    }

    /** 把 `\n` `\"` `\$` 之类还原成运行时的字符 —— 常量池里存的是还原后的那一串 */
    private fun unescape(src: String, at: Int): String = when (val c = src.getOrElse(at + 1) { ' ' }) {
        'n' -> "\n"
        't' -> "\t"
        'r' -> "\r"
        'b' -> "\b"
        '"' -> "\""
        '\'' -> "'"
        '\\' -> "\\"
        '$' -> "$"
        else -> c.toString()
    }

    private fun mainSource(relativeUnderMainJava: String): String {
        val file = File(projectDir(), "app/src/main/java/com/buaa/schedule/$relativeUnderMainJava")
        assertTrue("找不到 ${file.path}：取证行搬家的话要把本文件与审计文档一起改", file.isFile)
        return file.readText()
    }

    /** res/values 下的全部 XML 文本，用来确认取证文案不是资源条目 */
    private fun resValuesText(): String {
        val dir = File(projectDir(), "app/src/main/res/values")
        assertTrue("找不到 $dir：资源条目这条判据成了空话", dir.isDirectory)
        return dir.listFiles { f: File -> f.isFile && f.extension == "xml" }
            ?.sortedBy { it.name }
            ?.joinToString("\n") { it.readText() }
            .orEmpty()
    }

    /** 工作目录是模块目录还是仓库根不由这里决定：几种布局都试一遍，全落空就抛（同 WakeLockForensicsTest） */
    private fun projectDir(): File {
        var dir: File? = File("").absoluteFile
        var hops = 0
        while (dir != null && hops < 5) {
            val current = dir!!
            if (File(current, "app/src/main/java/com/buaa/schedule").isDirectory) return current
            if (File(current, "src/main/java/com/buaa/schedule").isDirectory) return current.parentFile
            dir = current.parentFile
            hops++
        }
        throw IllegalStateException("找不到项目根目录：当前目录 ${File("").absolutePath}")
    }

    /** release 产物：signed 与 unsigned 都认（本仓库的 worktree 没有 local.properties，产物是 unsigned） */
    private fun releaseApk(): File? {
        val dir = File(projectDir(), "app/build/outputs/apk/release")
        if (!dir.isDirectory) return null
        return dir.listFiles { f: File -> f.isFile && f.name.endsWith(".apk") }?.sortedBy { it.name }?.firstOrNull()
    }

    private fun dexBytes(apk: File): String = ZipFile(apk).use { zip ->
        val out = java.io.ByteArrayOutputStream()
        for (entry in zip.entries().toList().sortedBy { it.name }) {
            if (entry.name.startsWith("classes") && entry.name.endsWith(".dex")) {
                zip.getInputStream(entry).use { it.copyTo(out) }
            }
        }
        // ISO_8859_1 是字节 -> 字符的 1:1 映射，拿它当"字节视图"就能直接用 String.contains 查 UTF-8 字节
        String(out.toByteArray(), Charsets.ISO_8859_1)
    }

    /** 只在 BMP 内成立：MUTF-8 与 UTF-8 对 BMP 字符逐字节相同，增补平面字符会被 MUTF-8 拆成代理对 */
    private fun dexContains(dexView: String, fragment: String): Boolean {
        assertTrue("取证片段里有增补平面字符，MUTF-8 与 UTF-8 不再逐字节相同：$fragment", fragment.all {
            !Character.isSupplementaryCodePoint(it.code)
        })
        return dexView.contains(String(fragment.toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1))
    }
}
