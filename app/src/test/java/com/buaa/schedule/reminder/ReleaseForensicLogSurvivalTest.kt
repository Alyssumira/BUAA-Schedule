package com.buaa.schedule.reminder

import java.io.File
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Properties
import java.util.zip.ZipFile
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.fail
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
 * T78 新加的那一行 `liveFgsDegraded site=`（#119 唯一的取证口）与六枚 `LiveFgsRetry.token` 一并
 * 由本文件的第 3 层产物断言覆盖：2026-09-23 在 `:app:assembleRelease` 的产物上按 dex 字节复核过。
 * 机制上的原因是 AGP 8.13 那份 `proguard-android-optimize.txt` 里**没有**
 * `-assumenosideeffects class android.util.Log` 那一段（老版本 AGP 有，那是"release 里 Log.d 全灭"
 * 这个流传很广的说法的来源），本工程自己的规则文件与全部依赖的 consumer rules 里也没有。
 * 死掉的只有 `BuildConfig.DEBUG` 之后那几行诊断日志（`GlassDiag` / `jankRate=`），那是常量折叠，
 * 设计如此 —— 而它们恰好充当本文件所有产物层断言的**反向对照**：R8 确实会重建常量池，
 * 所以"字面量在 dex 里"不是恒真。
 *
 * 四层断言，强度与可跑性不同，别混着读：
 * 1. [noRuleFileStripsAndroidUtilLog] / [releaseStillUsesTheAuditedShrinkingConfig] —— 恒跑，
 *    钉规则层与构建脚本形状，只证明"没人写过删 Log 的规则"，**不证明产物里真的有**；
 * 2. [forensicSitesAreStillLogCalls] —— 恒跑，钉的是**源码层**：那几行还在、级别没变、
 *    消息字面量还是同一句、而且不是资源条目。这条同样**不证明产物**；
 * 3. [minifiedDexStillCarriesEveryForensicLine] / [effectiveR8ConfigCarriesNoLogStrippingRule] ——
 *    读 `:app:assembleRelease` 的产物（APK 里的 classes\*.dex、R8 dump 出的 configuration.txt），
 *    **日常门禁里没有产物就跳过**（`testDebugUnitTest` 不依赖 `assembleRelease`，产物在不在
 *    取决于谁最后跑过什么）。台账 #535 在这一层再加一枚
 *    [artifactLayerCrossChecksTheRecordedSignerAgainstTheApkBytes]：读同一枚 APK 的 v1（JAR）签名块，
 *    与自证条里新增的 `signedBy` / `signerDn` / `signerSha256` 三格对表。**它的字节那一格任何路径都跑**
 *    （调试密钥签出来的产物在这枚守卫面前就是红），"跳过"只发生在拿不到产物那一档。跳过不等于没事：要连着跑
 *    `:app:assembleRelease :app:testDebugUnitTest` 才吃到第 3 层。
 * 4. [releaseScriptRunsTheArtifactLayerGuardsAfterTheArtifact] /
 *    [artifactLayerHasExactlyOneSkipExitAndBothSidesAreWired] —— 恒跑，钉的是**发版路径本身**
 *    （台账 #340）：`release.ps1` 把第 3 层那两枚排在 `:app:releasePackage` 之后重跑一遍、
 *    带 `-PbuaaRequireReleaseArtifacts` 与本轮的 `-PreleaseRunId`；命中那个开关时第 3 层
 *    **不许跳过**（[missingArtifact] 是唯一出口，跳过即红），且产物必须过
 *    `:app:releasePackage` 写下的自证条（SHA-256 复算 + runId + 包内版本对 gradle.properties）。
 *    第 3 层"能不能读到产物"从此不再是一句口头纪律：它有一枚读文本的守卫在下面盯着。
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

        /** 编排者那几本账用到的全部取证行（§4.3 / §4.4 / T11 / T12 / T13 / T14 / T78 的过滤条件） */
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
            Site(
                "reminder/CourseFluidService.kt", "w", "CourseFluidService", "liveFgsDegraded site=",
                "#119 / T78 的取证口：实况起不来时唯一一行说清「这一档重排了没有」的判据行，" +
                    "删了它等于用户看不见、编排者也看不见",
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
            // T78：`action=` 那一格读的是 LiveFgsRetry.token —— 枚举一旦被 R8 换成 int，
            // logcat 里就只剩 skip:1 / skip:4 这种没人认得的数，取证行自己先失去意义
            "reminder/LiveFgsRetry.kt" to listOf(
                "armed", "skip:window-over", "skip:bell-not-rung",
                "skip:before-class", "skip:no-exact-alarm", "skip:exhausted",
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
        val apk = releaseApkForArtifactLayer(
            "取证那几行的 dex",
            "恒跑的那两条（规则层 + 源码层）才是这条链的下限",
        )
        val dex = dexBytes(apk)
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
        val file = r8ConfigurationForArtifactLayer(
            "恒跑的第 1 层（仓库里的规则文件）才是这条链的下限",
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

    // ---- 4. 发版路径：产物层不许跳过 + 产物自证本轮（#340）-------------------------------------------

    /**
     * `release.ps1` 必须把这两枚产物层守卫排在 `:app:releasePackage` **之后**再跑一遍，
     * 并带上 `-PbuaaRequireReleaseArtifacts`（桥成测试 JVM 的 `buaa.requireReleaseArtifacts`）。
     *
     * 为什么这一层值得单独钉：脚本里的那一步顺序是"这三格在发版路径上真被读到"的**唯一**保证，
     * 而 `release.ps1` 原先不在任何一枚测试的输入面里 —— 台账 #340 原样复发（把产物层那一支删回
     * 产物之前、或删掉那个开关）时，全量单测一枚都不会红，红的是用户手机上的包。
     * 手法是**读形状 + 数出现次数**，判据全落在文本上，不跑构建； needle 一律带引号取，
     * 免得撞上脚本自己那几行解释性注释（注释里也写着这些任务名）。
     */
    @Test
    fun releaseScriptRunsTheArtifactLayerGuardsAfterTheArtifact() {
        val script = workingTreeText(RELEASE_SCRIPT)
        val packageAt = script.indexOf(TASK_RELEASE_PACKAGE)
        check(packageAt >= 0) { "$RELEASE_SCRIPT 里已经没有 $TASK_RELEASE_PACKAGE 了：发版入口换了写法，本层要重钉" }

        val forensicAt = onlyAt(script, "'" + FORENSIC_CLASS + "'", "产物层守卫 $FORENSIC_CLASS 的点名")
        val scanAt = onlyAt(script, "'" + SCAN_GUARD_CLASS + "'", "产物层守卫 $SCAN_GUARD_CLASS 的点名")
        assertTrue(
            "两枚产物层守卫被排在 :app:releasePackage **之前**（取证 log 存活 / 兜底 .so 是否 DEFLATED / " +
                "zxingcpp/BarcodeReader 是否还在 release dex 这三格会回到\"每一轮必跳\"，" +
                "也就是台账 #340 那一格原样复发）：releasePackage 在 $packageAt，两枚类在 $forensicAt / $scanAt",
            forensicAt > packageAt && scanAt > packageAt,
        )

        // 打包之前那一趟单测不许点名产物层：那时本轮产物根本不存在，点了也只是跳
        assertFalse(
            "产物还没打出来就在 --tests 里点名产物层守卫：那种跑法必然 assumeTrue 跳过，" +
                "把\"跑过了\"当成\"读到了\"正是本层要拦的形状",
            script.substring(0, packageAt).contains("--tests"),
        )
        val testTaskCount = countOf(script, TASK_TEST_DEBUG)
        assertEquals(
            "$TASK_TEST_DEBUG 应出现 3 次（传号那一支、不传号那一支各一次，加上产物之后的那一趟），" +
                "实到 $testTaskCount 次：少掉第三趟就是 #340 没落地，" +
                "把两枚守卫挪进前面那两趟里同样算错（那时本轮产物还不存在）",
            3,
            testTaskCount,
        )
        assertTrue(
            "第二趟 $TASK_TEST_DEBUG 排在 :app:releasePackage 之前 ⇒ 产物层读的仍是 build/ 里的残留",
            script.lastIndexOf(TASK_TEST_DEBUG) > packageAt,
        )

        // 缓存：这两枚类读的是产物，而产物**故意不在**测试输入面里，不强制重跑就会端住上一轮的绿
        assertEquals(
            "产物层那一步必须 --rerun-tasks（只许一次），实到 ${countOf(script, "'--rerun-tasks'")} 次",
            1,
            countOf(script, "'--rerun-tasks'"),
        )

        // 两个开关都得真的传下去
        assertEquals(
            "-PbuaaRequireReleaseArtifacts 只许在产物层那一步传一次，实到 ${countOf(script, FLAG_REQUIRE_ARTIFACTS)} 次" +
                "：传 0 次就是让守卫继续\"没产物也绿\"，传两次就是把恒跑的门禁也变成\"必须先打包\"",
            1,
            countOf(script, FLAG_REQUIRE_ARTIFACTS),
        )
        assertEquals(
            "-PreleaseRunId 要在打包与守卫两步各传一次（同一个号），实到 ${countOf(script, FLAG_RUN_ID)} 次：" +
                "只传给守卫 ⇒ 侧车里没号可比；只传给打包 ⇒ 本轮暗号这一格静默失效",
            2,
            countOf(script, FLAG_RUN_ID),
        )
    }

    /**
     * "不许报 skipped"这句话本身也得有尺：跳过出口只许一枚，而且开关的两侧都要有人接。
     *
     * 数出现次数那一档，读的是这两枚守卫**自己**的源码文本 —— 谁图省事在本文件里再补一枚
     * 裸的 assume 出口（绕过 [missingArtifact]），发版路径上那三格就又回到"绿着跳过"了。
     * needle 用拼接取（`"assumeTrue" + "("`），否则这枚针会数到它自己那行代码；
     * 相应地，本文件的注释与报错文案里都不许出现"needle 原样 + 左括号"那个形状。
     */
    @Test
    fun artifactLayerHasExactlyOneSkipExitAndBothSidesAreWired() {
        for (relative in listOf(THIS_TEST_FILE, SCAN_GUARD_FILE)) {
            val text = workingTreeText(relative)
            val skips = countOf(text, SKIP_EXIT_NEEDLE)
            assertEquals(
                "$relative 里那枚唯一的跳过出口应当是 missingArtifact 内部那一处（多一处就是有人绕过 #340 的" +
                    "纪律、把产物层重新接回静默跳过；少一处说明日常门禁没路可跳了）：实到 $skips 处",
                1,
                skips,
            )
            assertTrue(
                "$relative 不再读 $PROPERTY_REQUIRE_ARTIFACTS：那个开关一旦改名，发版路径会退化成\"永远跳\"",
                text.contains(PROPERTY_REQUIRE_ARTIFACTS),
            )
            assertTrue(
                "$relative 里 missingArtifact 这一枚出口被改名或删掉了（跳过只该有一个地方能发生）：",
                text.contains("missingArtifact"),
            )
        }
        // 开关的两侧：脚本传、构建脚本桥、守卫读 —— 三环都在盘上才算这条链没断（接收者链同族）
        val build = workingTreeText(BUILD_SCRIPT)
        assertTrue(
            "app/build.gradle.kts 没把 ${PROPERTY_REQUIRE_ARTIFACTS} 桥进测试 JVM（project property 到不了" +
                "单测进程，脚本那一侧改了等于没改）：",
            build.contains("buaaRequireReleaseArtifacts") && build.contains("\"$PROPERTY_REQUIRE_ARTIFACTS\""),
        )
        assertTrue(
            "app/build.gradle.kts 里没有写自证条的那一步（读侧再怎么改也只是读空气）：",
            build.contains("writeReleaseProvenance") && build.contains(PROVENANCE_FILE.substringAfterLast('/')),
        )
        val script = workingTreeText(RELEASE_SCRIPT)
        assertTrue(
            "release.ps1 里没人传 buaaRequireReleaseArtifacts 这个开关（同上：单侧接线等于没接）：",
            script.contains("buaaRequireReleaseArtifacts"),
        )
    }

    /**
     * 产物层读的那一枚包必须**自带签名者证据**，而且侧车对签名者的声明要能与**同一枚包的字节**对表
     * （台账 #535 —— #340 那条链原先能在一份不可发布的包上打满绿灯，就是因为签名者不在判据里）。
     *
     * 三格各拦一种"看着对"：
     * 1. **字节**：产物层读到的这枚包如果是 Android 调试密钥签的（证书 DN 就是
     *    [DEBUG_SIGNER_DN_MARKER] 那张公开调试证书），任何路径都当场红 —— 这一格不看开关、
     *    不看侧车，日常门禁也吃到；
     * 2. **声明与字节对表**：有自证条时 `signerDn` / `signerSha256` 必须与这里**独立复算**出来的
     *    第一枚 v1 签名块证书逐字相等，`signedBy` 必须与字节量出来的档位一致（读不到签名块那一档
     *    构建侧按来路放行，此时这三格得同时为空、且不许宣称 debug）。侧车谎报在这里红，
     *    而不是等到有人去读屏幕；
     * 3. **名字与档位同册**：自证条 `apk=` 指着的那枚文件如果顶着可发布名
     *    `buaa-schedule-<gradle.properties 里的 VERSION_NAME>.apk`，声明的签名者就必须是 release；
     *    反过来声明不是 release 时那个名字就不该是可发布名。这一格钉的正是 #535 本体 ——
     *    调试自签的包躺在正式名上、`printPublishSteps` 逐字教人把它当附件传上去。
     *
     * 与构建脚本的关系：`signerCertificates` 在 app 的构建脚本里，不在单 classpath 上，所以这里是
     * **第二枚读者**而不是复用 —— 这一格要的就是"声明方与核对方各读一遍各自的字节"，
     * 两枚读者只同源于 JDK 的 ZipFile + X.509 API。调试密钥那张证书 DN 是公开常量（工具链自带的），
     * 本文件与构建脚本各写一份的事实由 `ReleaseArtifactSignerSingleRulerGuardTest` 跨文件钉住。
     */
    @Test
    fun artifactLayerCrossChecksTheRecordedSignerAgainstTheApkBytes() {
        val floor = "签名者不进自证条（#535 那一格）时，产物层能在一份装机就要用户先卸载、" +
            "而卸载会清掉课表数据库的包上打满绿灯。"
        val root = projectDir()
        val provenance = readProvenance(root)
        val apk = releaseApkForArtifactLayer("签名者这一格（signedBy / signerDn / signerSha256）", floor)
        val certificates = signerCertificatesOf(apk)
        val distinguishedNames = certificates.map { it.first }

        // 1. 字节：这一枚绝不能是调试密钥签的
        val debugCertificates = distinguishedNames.filter { it.contains(DEBUG_SIGNER_DN_MARKER, ignoreCase = true) }
        assertTrue(
            "产物层读到的这枚包（${apk.name}）是**调试密钥**签的：DN=$debugCertificates —— " +
                "台账 #535：这种包顶着可发布的名字进 dist，`releasePackage` 与产物层守卫照样全绿，" +
                "而装它的那台设备第一次换回正式签名就会「未安装成功」，卸载连带清掉课表数据库。",
            debugCertificates.isEmpty(),
        )

        // 2/3 没有自证条 = 这一趟没跑过 :app:releasePackage（日常门禁正是这一档，没有声明可核）。
        // 这里不许自己加跳过出口：第 1 格在任何路径下都已经跑完，发版路径读不到侧车由
        // releaseApkForArtifactLayer 那一枚唯一出口处理。
        if (provenance == null) return

        val claimed = provenance.getProperty(KEY_SIGNED_BY)?.trim().orEmpty()
        val claimedDn = provenance.getProperty(KEY_SIGNER_DN)?.trim().orEmpty()
        val claimedFingerprint = provenance.getProperty(KEY_SIGNER_SHA)?.trim().orEmpty()
        assertTrue(
            "自证条没有 $KEY_SIGNED_BY 这一格（$PROVENANCE_FILE）：#535 说的那样" +
                "「十条键里没有一条是签名者」又回来了，产物层对签名这件事重新变成读空气",
            claimed.isNotEmpty(),
        )
        assertTrue(
            "自证条里的 $KEY_SIGNED_BY=\"$claimed\" 不是三档之一（release / debug / unsigned）：" +
                "档位换了写法，读侧与写侧就不是同一本册子了",
            claimed in setOf(SIGNER_RELEASE, SIGNER_DEBUG, SIGNER_UNSIGNED),
        )

        if (certificates.isNotEmpty()) {
            assertEquals("自证条里的 signerDn 与字节里第一枚签名证书不等：", claimedDn, distinguishedNames.first())
            assertEquals(
                "自证条里的 signerSha256 与这里独立复算的证书指纹不等 —— 侧车指着的字节与它自己的声明" +
                    "不是同一份东西：",
                claimedFingerprint,
                certificates.first().second,
            )
            val bytesSay = if (debugCertificates.isNotEmpty()) SIGNER_DEBUG else SIGNER_RELEASE
            assertEquals(
                "自证条宣称 $KEY_SIGNED_BY=$claimed，而字节量出来是 $bytesSay（DN=$distinguishedNames）：" +
                    "谎报签名者",
                bytesSay,
                claimed,
            )
        } else {
            // 读不到 v1 签名块：只有 v2/v3（发布签名配置 enableV1Signing 被关掉那一档）或根本没签。
            // 这一档构建侧按来路放行，但侧车不能再宣称任何一张证书，也不许自称 debug。
            assertTrue(
                "字节里读不到 v1（JAR）签名块，可自证条却写着 signerDn=\"$claimedDn\" / " +
                    "signerSha256=$claimedFingerprint —— 它宣称的那张证书不在这枚包里",
                claimedDn.isEmpty() && claimedFingerprint.isEmpty(),
            )
            assertNotEquals(
                "字节里读不到签名块却宣称 debug：debug 这一档的判据是**量到**那张调试证书" +
                    "（DN 片段见 DEBUG_SIGNER_DN_MARKER），按旗子写结论就是 #535 原先那个形状",
                SIGNER_DEBUG,
                claimed,
            )
        }

        val relative = provenance.getProperty(KEY_APK)?.trim().orEmpty()
        val wearPublishableName = File(relative).name == PUBLISHABLE_NAME_PREFIX +
            gradleProperty(root, "VERSION_NAME") + ".apk"
        assertEquals(
            "名字与签名者不同册（#535 本体）：$PROVENANCE_FILE 里 $KEY_APK=\"$relative\"，" +
                "可发布名的产物其签名者声明必须是 $SIGNER_RELEASE，非 $SIGNER_RELEASE 的产物不许拿那个名字" +
                "（客户端按规范名**第一顺位**选中它，而调试签名覆盖不了正式签名）",
            SIGNER_RELEASE == claimed,
            wearPublishableName,
        )
    }

    // ---- 产物层唯一的"拿产物"出口 -------------------------------------------------------------------

    /**
     * 读 APK 里 v1（JAR）签名块的证书「DN 到 SHA-256 指纹」：纯 JVM 的 ZipFile + X.509 读法，
     * 与构建脚本那枚 `signerCertificates` 各读一遍（理由见 [artifactLayerCrossChecksTheRecordedSignerAgainstTheApkBytes]）。
     */
    private fun signerCertificatesOf(apk: File): List<Pair<String, String>> {
        val read = ArrayList<Pair<String, String>>()
        ZipFile(apk).use { zip ->
            val factory = CertificateFactory.getInstance("X.509")
            for (entry in zip.entries().toList()) {
                if (entry.isDirectory || !entry.name.startsWith("META-INF/")) continue
                if (entry.name.substringAfterLast('.', "") !in SIGNER_BLOCK_EXTENSIONS) continue
                zip.getInputStream(entry).use { input ->
                    for (certificate in factory.generateCertificates(input)) {
                        val x509 = certificate as X509Certificate
                        read += x509.subjectX500Principal.name to MessageDigest.getInstance("SHA-256")
                            .digest(x509.encoded).joinToString("") { byte -> "%02X".format(byte) }
                    }
                }
            }
        }
        return read
    }

    /** 发版路径（`-PbuaaRequireReleaseArtifacts` 命中）时缺产物 / 产物不是本轮的都当场红 */
    private val releaseArtifactsRequired: Boolean
        get() = System.getProperty(PROPERTY_REQUIRE_ARTIFACTS) == "true"

    /**
     * 产物层"读不到证据"的唯一出口（本文件的跳过只发生在这里，别处一律不许自己跳过）。
     *
     * 日常门禁：跳过，与 #340 之前一字不差 —— 单测不该去拉起 release 构建。
     * 发版路径：跳过就是红。这一句是整枚卡的核心，因为"跳了算绿"正是那三格从没被读过的原因。
     */
    private fun missingArtifact(reason: String, floor: String): Nothing {
        if (releaseArtifactsRequired) {
            fail(
                "$reason\n—— 发版路径上这一格**不许跳过**（$PROPERTY_REQUIRE_ARTIFACTS 已命中）：" +
                    "跳过了就等于把\"keep 规则改坏也带绿灯发出\"这笔账交回给没人读过的状态。$floor"
            )
        }
        assumeTrue("$reason —— $floor", false)
        throw IllegalStateException("unreachable")
    }

    /**
     * 产物层该读的那一枚 release APK。
     *
     * 有自证条时**只认自证条指的那一枚**：旧读法是 `sortedBy { it.name }.firstOrNull()`，
     * 而 `app-release-unsigned.apk` 按字典序排在 `app-release.apk` 前面（`-` 是 0x2D、`.` 是 0x2E），
     * signed 与 unsigned 同时躺在产物目录里时它拿的永远是没签过的那一枚 —— 真要发出去的那份包
     * 从来没被产物层读过。没有自证条（手工连跑）时才退回旧读法，且只在非发版路径上。
     */
    private fun releaseApkForArtifactLayer(what: String, floor: String): File {
        val root = projectDir()
        val provenance = readProvenance(root)
        if (provenance == null) {
            if (releaseArtifactsRequired) {
                missingArtifact(
                    "本轮没有 :app:releasePackage 写的自证条（$PROVENANCE_FILE）：也就是说这一趟根本没打出产物，" +
                        "而 $what 只能去读 build/ 里剩着的那一枚 —— 分不清它是本轮的还是上一轮的",
                    floor,
                )
            }
            val legacy = releaseApk()
                ?: missingArtifact("没有 :app:assembleRelease 的产物（app/build/outputs/apk/release 下的 .apk）", floor)
            return legacy
        }
        val relative = provenance.getProperty(KEY_APK)?.trim().orEmpty()
        if (relative.isEmpty()) {
            missingArtifact("自证条里没有 $KEY_APK 这一项（$PROVENANCE_FILE）", floor)
        }
        val apk = File(root, relative)
        if (!apk.isFile) {
            missingArtifact("自证条指着 $relative，可它不在盘上（$PROVENANCE_FILE 是旧的？）", floor)
        }
        assertProvenanceMatches(provenance, KEY_APK_SHA, apk, "APK", root, floor)
        return apk
    }

    /** R8 生效规则集那份产物：同样要过自证条（keep 规则改坏最直接的证据就在这枚文件里） */
    private fun r8ConfigurationForArtifactLayer(floor: String): File {
        val root = projectDir()
        val provenance = readProvenance(root)
        if (provenance == null) {
            if (releaseArtifactsRequired) {
                missingArtifact(
                    "本轮没有 :app:releasePackage 写的自证条（$PROVENANCE_FILE），R8 生效规则集读不到",
                    floor,
                )
            }
            val legacy = File(root, "app/build/outputs/mapping/release/configuration.txt")
            if (!legacy.isFile) missingArtifact("没有 R8 的 configuration.txt 产物（跑过 :app:assembleRelease 才有）", floor)
            return legacy
        }
        val relative = provenance.getProperty(KEY_CONFIGURATION)?.trim().orEmpty()
        if (relative.isEmpty()) missingArtifact("自证条里没有 $KEY_CONFIGURATION 这一项", floor)
        val file = File(root, relative)
        if (!file.isFile) {
            missingArtifact(
                "自证条指着 $relative，可它不在盘上：那是 R8 的 mapping 产物，" +
                    "isMinifyEnabled 被关掉时不会生成，而发版路径上它必须生成",
                floor,
            )
        }
        assertProvenanceMatches(provenance, KEY_CONFIGURATION_SHA, file, "configuration.txt", root, floor)
        return file
    }

    /**
     * 把"本轮"这两个字算出来，而不是相信它。四格各拦一种"看着对"的残留：
     * 1. **字节**：复算 SHA-256 与侧车对表 ⇒ 文件被覆盖过 / 是别的轮次留下的，一眼穿。
     * 2. **暗号**：`-PreleaseRunId` 本轮由 `release.ps1` 当场生成，同时递给打包与守卫两步；
     *    侧车记的号与守卫拿的号不一致 ⇒ 读到的是别的轮次的产物。为什么不用 mtime：
     *    复制、解压、杀毒软件扫一遍都会改它，而"同一个版本号发两次"时 mtime 还挺像话。
     * 3. **包内版本**：侧车里的 `versionName/versionCode` 是 `assertArtifactVersion` 拿 aapt2
     *    **从包里读出来**的那对，与 `gradle.properties` 现值对表；`aapt2=skipped`（本机没 build-tools）
     *    单独算红 —— 不许把"这一格没读"混进"读过并且对上了"。
     * 4. **出处**：`builtBy` 必须是 `:app:releasePackage`，防止有人手工造一份侧车哄过前三格。
     */
    private fun assertProvenanceMatches(
        provenance: Properties,
        shaKey: String,
        artifact: File,
        label: String,
        root: File,
        floor: String,
    ) {
        val problems = ArrayList<String>()
        val recorded = provenance.getProperty(shaKey)?.trim().orEmpty()
        if (recorded.isEmpty()) {
            problems += "自证条里没有 $shaKey"
        } else {
            val actual = sha256Of(artifact)
            if (!recorded.equals(actual, ignoreCase = true)) {
                problems += "$label 字节与自证条不符（侧车 ${recorded.take(16)}… / 复算 ${actual.take(16)}…）" +
                    "⇒ 手里这枚不是 :app:releasePackage 当时交出来的那一枚"
            }
        }
        val expectedRun = System.getProperty(PROPERTY_RUN_ID).orEmpty()
        val recordedRun = provenance.getProperty(KEY_RUN_ID)?.trim().orEmpty()
        if (expectedRun.isNotEmpty() && recordedRun != expectedRun) {
            problems += "$label 出自另一轮（自证条 runId=\"$recordedRun\" / 本轮=\"$expectedRun\"）"
        }
        if (provenance.getProperty(KEY_BUILT_BY)?.trim() != BUILT_BY) {
            problems += "自证条不是 $BUILT_BY 写的（builtBy=${provenance.getProperty(KEY_BUILT_BY)}）"
        }
        if (provenance.getProperty(KEY_AAPT2)?.trim() != "ok") {
            problems += "包内版本这一格根本没读过（aapt2=${provenance.getProperty(KEY_AAPT2)}）：" +
                "本机没有 build-tools 时 :app:releasePackage 会跳过 aapt2 核对，发版路径不许在这种前提下签绿"
        }
        for ((key, gradleKey) in listOf(KEY_VERSION_NAME to "VERSION_NAME", KEY_VERSION_CODE to "VERSION_CODE")) {
            val recordedValue = provenance.getProperty(key)?.trim().orEmpty()
            val inTree = gradleProperty(root, gradleKey)
            if (recordedValue.isEmpty() || recordedValue != inTree) {
                problems += "$label 里的 $key=\"$recordedValue\" 与 gradle.properties 的 $gradleKey=\"$inTree\" 对不上" +
                    "⇒ 要么改号那步没生效，要么这枚产物不是这一号的构建"
            }
        }
        if (problems.isNotEmpty()) {
            missingArtifact("本轮产物自证不成立（$label）：\n" + problems.joinToString("\n"), floor)
        }
    }

    /** 侧车不存在 = 这一轮没跑过 :app:releasePackage（或 build 被清过），交回调用方分档处理 */
    private fun readProvenance(root: File): Properties? {
        val file = File(root, PROVENANCE_FILE)
        if (!file.isFile) return null
        // 逐行取 = 之前的键值，不用 Properties.load：那把 ISO-8859-1 解释注释里的中文，
        // 值虽然不受影响，但文件头那行注释一旦含 = 就会多出一个怪键，手工读没这个坑
        val props = Properties()
        for (line in file.readLines()) {
            val at = line.indexOf('=')
            if (at <= 0 || line.startsWith("#")) continue
            props.setProperty(line.substring(0, at).trim(), line.substring(at + 1).trim())
        }
        return props
    }

    private fun gradleProperty(root: File, key: String): String =
        File(root, "gradle.properties").takeIf { it.isFile }?.readLines()
            ?.firstOrNull { it.startsWith("$key=") }
            ?.substringAfter('=')?.trim().orEmpty()

    private fun sha256Of(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { byte -> "%02x".format(byte) }

    // ---- 第 4 层用的小工具 ------------------------------------------------------------------------------

    private fun workingTreeText(relativeFromRoot: String): String {
        val file = File(projectDir(), relativeFromRoot)
        assertTrue("找不到 ${file.path}：这一层的判据依赖它，路径挪过家要回来改这里", file.isFile)
        return file.readText()
    }

    /** [needle] 必须恰好出现一次，返回它的位置；0 次或多次都抛（扫空气与扫错方向都不算读到） */
    private fun onlyAt(text: String, needle: String, what: String): Int {
        val times = countOf(text, needle)
        check(times == 1) { "$what 在 $RELEASE_SCRIPT 里出现 $times 次（只许 1 次）：$needle" }
        return text.indexOf(needle)
    }

    private fun countOf(text: String, needle: String): Int {
        var n = 0
        var from = 0
        while (true) {
            val at = text.indexOf(needle, from)
            if (at < 0) return n
            n++
            from = at + needle.length
        }
    }

    private val RELEASE_SCRIPT = "release.ps1"
    private val BUILD_SCRIPT = "app/build.gradle.kts"
    private val THIS_TEST_FILE = "app/src/test/java/com/buaa/schedule/reminder/ReleaseForensicLogSurvivalTest.kt"
    private val SCAN_GUARD_FILE = "app/src/test/java/com/buaa/schedule/ui/signin/ScanSecondEngineWiringGuardTest.kt"
    private val FORENSIC_CLASS = "com.buaa.schedule.reminder.ReleaseForensicLogSurvivalTest"
    private val SCAN_GUARD_CLASS = "com.buaa.schedule.ui.signin.ScanSecondEngineWiringGuardTest"
    private val PROVENANCE_FILE = "app/build/release-provenance/release-artifact.properties"

    // 带引号取：脚本自己的注释里也写着这些任务名，不带引号就会把注释数进去
    private val TASK_RELEASE_PACKAGE = "':app:releasePackage'"
    private val TASK_TEST_DEBUG = "':app:testDebugUnitTest'"
    private val FLAG_REQUIRE_ARTIFACTS = "'-PbuaaRequireReleaseArtifacts'"
    private val FLAG_RUN_ID = "\"-PreleaseRunId="

    private val PROPERTY_REQUIRE_ARTIFACTS = "buaa.requireReleaseArtifacts"
    private val PROPERTY_RUN_ID = "buaa.releaseRunId"

    /** 拼接取，理由见 [artifactLayerHasExactlyOneSkipExitAndBothSidesAreWired]：这枚针不许数到自己 */
    private val SKIP_EXIT_NEEDLE = "assumeTrue" + "("
    private val KEY_APK = "apk"
    private val KEY_APK_SHA = "apkSha256"
    private val KEY_CONFIGURATION = "configuration"
    private val KEY_CONFIGURATION_SHA = "configurationSha256"
    private val KEY_RUN_ID = "runId"
    private val KEY_BUILT_BY = "builtBy"
    private val KEY_AAPT2 = "aapt2"
    private val KEY_VERSION_NAME = "versionName"
    private val KEY_VERSION_CODE = "versionCode"
    private val BUILT_BY = ":app:releasePackage"

    // ---- 台账 #535：签名者三档、公开调试证书的 DN 片段、可发布名 ---------------------------------------

    private val KEY_SIGNED_BY = "signedBy"
    private val KEY_SIGNER_DN = "signerDn"
    private val KEY_SIGNER_SHA = "signerSha256"
    private val SIGNER_RELEASE = "release"
    private val SIGNER_DEBUG = "debug"
    private val SIGNER_UNSIGNED = "unsigned"

    /**
     * 工具链自带那张调试证书的 DN 片段（公开常量，不是任何密钥材料）。
     * 与 app/build.gradle.kts 里 debugSignerDnMarker 是同一枚字面 —— 写侧与读侧共用一个事实，
     * 哪一侧改了另一侧就得跟着改，这件事由 `ReleaseArtifactSignerSingleRulerGuardTest` 跨文件钉住。
     */
    private val DEBUG_SIGNER_DN_MARKER = "CN=Android Debug"

    /** v1（JAR）签名块的扩展名，与构建脚本里那组 SIGNER_BLOCKS 同一份 */
    private val SIGNER_BLOCK_EXTENSIONS = setOf("RSA", "DSA", "EC")

    /** 可发布名前缀：客户端按规范名**第一顺位**选中附件，所以这个名字本身就是判据 */
    private val PUBLISHABLE_NAME_PREFIX = "buaa-schedule-"

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
