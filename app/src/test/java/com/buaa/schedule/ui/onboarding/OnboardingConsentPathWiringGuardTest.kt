package com.buaa.schedule.ui.onboarding

import com.buaa.schedule.domain.onboarding.OnboardingGesture
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T271④：首启引导「同意路径」的接线守卫 —— 四处出口**各自带走哪一手**，钉到源码形状上。
 *
 * 钉的四法（逐条点名，报错都带文件与判据，不写行号）：
 *
 * - **逐字节**（④ 那一段）：常驻页那一手的两枚字面必须一字不差地还在 ——
 *   `FirstRun.acceptPrivacy(context)` 与 `Text("同意隐私说明")`。
 *   它们就是第 ⓪ 问丁档里「乙」的那一枚按钮；删掉它，跳过引导的用户重新无处同意，
 *   本卡整族复发。同一段还拿这枚按钮的**在册旗**
 *   [PERMANENT_PRIVACY_CONSENT_ENTRY_AVAILABLE] 的字面值与它互相对账：
 *   按钮没了而旗还写着 true ⇒ 红；按钮在而旗被翻成 false ⇒ 也红（判据吃的是旗，
 *   旗说谎就等于把 PathToPermanentEntry 那一档换成 PathSevered）。
 * - **抹注释找锚点**（② ③ 段）：先把注释抹平再找锚点，钉的是代码而不是白话 ——
 *   `fun recordOnboardingGesture(` 那一体的配平块里必须同时有判据调用与两枚**带闸门**的落盘位，
 *   而且 `OnboardingHeader(` 那一体内必须出现 `recordOnboardingGesture(OnboardingGesture.HeaderSkip)`。
 *   后者就是「跳过那一手」的名字住在哪一层：把它摘掉，跳过又变回只写引导完成位的单向门。
 * - **数出现次数**（① 段 + ⑤ 段的枚数册子）：`recordOnboardingGesture(` 恰 5 枚、
 *   `FirstRun.completeOnboarding(context)` 恰 **1** 枚（住在闸门里）、
 *   `FirstRun.acceptPrivacy(context)` 在 `ui/onboarding/` 恰 1 枚、全 main 恰 2 枚。
 * - **读形状**（① 段 + ⑥ 段）：五枚调用点交出的 `OnboardingGesture` 必须与内核在册的五枚
 *   **一一对齐**（缺枚、重复、多一枚都算漂），并且落「引导已完成」的那四枚
 *   （`OnboardingGesture.writesOnboardingDone`）必须与内核的册子逐名相等。
 *
 * 为什么要这么钉：基点上 `completeOnboarding` 四枚裸调用、`acceptPrivacy` 一枚裸调用，
 * 枚数差本身**不是**缺陷（引导完成与同意是两扇不同的门，`core/FirstRun.kt` 的文件头写着这条），
 * 所以本守卫不许写成「枚数差即红」那种恒真判据。它钉的是形状：
 * 四枚落完成位的手必须各自点名自己的那一枚 [OnboardingGesture]，
 * 而带同意的那一手必须有 `writesConsent` 闸门在前 ——
 * 把四枚裸调用抄回来 ⇒ ① 的 5 枚册子红、③ 的闸门红、④ 的常驻页那一手还在但引导里没人落同意位，
 * 于是 ② 的 `OnboardingHeader` 锚点红。三处合起来才是「哪几枚出口各自带走哪一手」。
 *
 * 不跳过、不 assumeTrue：找不到文件、找不到锚点一律抛，静默通过等于没有守卫。
 */
class OnboardingConsentPathWiringGuardTest {

    /** ①【读形状 + 数出现次数】五枚出口各点名一次，且点名的正是内核在册的那五枚。 */
    @Test
    fun everyOnboardingHandGoesThroughTheGateByItsOwnName() {
        val text = blankComments(onboarding())
        val callSites = Regex("""recordOnboardingGesture\(\s*OnboardingGesture\.(\w+)""")
            .findAll(text)
            .map { it.groupValues[1] }
            .toList()
        assertEquals(
            "$ONBOARDING 里 recordOnboardingGesture( 的调用处数应当恰好 5 枚" +
                "（跳过 / 隐私步下一步 / 导入落点 / 手动添加落点 / 末步落首页），实到 ${callSites.size} 枚：\n" +
                callSites.joinToString(),
            5,
            callSites.size,
        )
        assertEquals(
            "$ONBOARDING 交出的出口名册与内核 OnboardingGesture 的在册册子对不上 —— " +
                "哪一枚出口漏了判据，或新加了一枚没在册的出口：\n实到 " + callSites.sorted().joinToString(),
            OnboardingGesture.entries.map { it.name }.sorted(),
            callSites.sorted(),
        )
        assertTrue(
            "$ONBOARDING 里同一枚出口被点名了两遍（两处出口共用一枚枚举 = 其中一处不知道自己是谁）：" +
                callSites.groupingBy { it }.eachCount().filterValues { it > 1 }.keys,
            callSites.distinct().size == callSites.size,
        )
    }

    /**
     * ②【抹注释找锚点】跳过那一手（顶栏）必须走过判据：`OnboardingHeader(` 那一体里点名 HeaderSkip。
     *
     * 这一格就是本卡的题眼。基点上那一处是裸的 `FirstRun.completeOnboarding(context)`，
     * 于是「先跳过，稍后设置」把引导那扇门永久关上而不同意位不落 ——
     * 把裸调用抄回来，这一格先红。
     */
    @Test
    fun theSkipHandCarriesTheJudgment() {
        val header = balancedBlock(onboarding(), "OnboardingHeader(")
        assertTrue(
            "OnboardingHeader( 那一体里找不到 recordOnboardingGesture(OnboardingGesture.HeaderSkip) —— " +
                "$ONBOARDING 的顶栏出口又变回只写「引导已完成」的那扇单向门：\n$header",
            header.contains("recordOnboardingGesture(OnboardingGesture.HeaderSkip)"),
        )
    }

    /**
     * ③【抹注释找锚点 + 读形状】落盘的两枚位都必须吃判据交下来的闸门，且同意闸门排在完成闸门之前。
     *
     * 「四枚出口都写完成位而其中没有同意路径」这一形在这里被拦住：完成位全仓只剩一枚裸调用，
     * 而且它前面必须有 `disposition.writesConsent` 那一行 —— 顺序即语义：
     * 先答「这一手带不带用户的同意」，再答「引导算不算看完」。
     */
    @Test
    fun theGateBodyWritesBothBitsBehindTheirOwnConditions() {
        val body = balancedBlock(onboarding(), "fun recordOnboardingGesture(gesture: OnboardingGesture)")
        val dispose = body.indexOf("OnboardingConsentGate.gestureDisposition(")
        val consentWrite = body.indexOf("if (disposition.writesConsent) FirstRun.acceptPrivacy(context)")
        val doneWrite = body.indexOf("if (disposition.writesOnboardingDone) FirstRun.completeOnboarding(context)")
        assertTrue(
            "recordOnboardingGesture 的体里没调 OnboardingConsentGate.gestureDisposition —— " +
                "$ONBOARDING 自己在落盘，判据被绕开了：\n$body",
            dispose >= 0,
        )
        assertTrue(
            "同意位的落盘必须写成 `if (disposition.writesConsent) FirstRun.acceptPrivacy(context)`：" +
                "写成裸调用就是替用户同意（全仓任何一处都不许），写成别的样子守卫读不到它：\n$body",
            consentWrite >= 0,
        )
        assertTrue(
            "「引导已完成」的落盘必须写成 `if (disposition.writesOnboardingDone) FirstRun.completeOnboarding(context)`：" +
                "裸调用就是四枚出口都不带同意路径那一形：\n$body",
            doneWrite >= 0,
        )
        assertTrue(
            "闸门顺序要紧：同意位（writesConsent）要排在完成位（writesOnboardingDone）之前，" +
                "否则跳过那一手先把门关上，同意位再落也没人读了：\n$body",
            dispose < consentWrite && consentWrite < doneWrite,
        )
        assertTrue(
            "五手里至少一枚要把判据交下来的话留进日志（HyperOS 截在 Info 级 ⇒ Log.i/Log.w，不写 Log.d）：" +
                "\n$body",
            body.contains("Log.w(") && body.contains("Log.i(") && !body.contains("Log.d("),
        )
    }

    /**
     * ④【逐字节】同意入口不依赖引导页可达：常驻页那一格一字不差地还在，且与在册旗对得上账。
     *
     * 第 ⓪ 问丁档的「乙」就住在这一格。以前那枚弹窗的 `confirmButton = {}` 只有「撤回」，
     * 没同意过的人在这页上什么也做不了 —— 所以这一体里现在必须有点名「同意隐私说明」的那一手。
     */
    @Test
    fun thePermanentScreenStillOffersAConsentHand() {
        val settings = blankComments(settings())
        assertEquals(
            "$SETTINGS 里 FirstRun.acceptPrivacy(context) 应当恰好一处（数据与隐私弹窗的确认位），实到 " +
                occurrences(settings, "FirstRun.acceptPrivacy(context)") + " 处",
            1,
            occurrences(settings, "FirstRun.acceptPrivacy(context)"),
        )
        assertEquals(
            "$SETTINGS 里 Text(\"同意隐私说明\") 应当恰好一处（确认位那颗按钮的字面），实到 " +
                occurrences(settings, "Text(\"同意隐私说明\")") + " 处 —— 少一处就是同意入口又搬回引导页了",
            1,
            occurrences(settings, "Text(\"同意隐私说明\")"),
        )
        val dialog = balancedBlock(settings(), "payload = if (showPrivacyDialog) privacyConsentAt else null")
        assertTrue(
            "数据与隐私那层弹窗的确认位必须真的给出同意那一手（改前是 confirmButton = {}，只有撤回）：\n" +
                dialog.take(dialog.length.coerceAtMost(400)),
            dialog.contains("confirmButton = {") && !dialog.contains("confirmButton = {},"),
        )
        // 在册旗与那一枚按钮互相对账：判据吃的就是这枚旗，旗与按钮不一致 = 交回的档位在读谎
        val acceptHandOnThePermanentScreen =
            occurrences(settings, "FirstRun.acceptPrivacy(context)") == 1
        assertEquals(
            "旗与按钮必须同一侧：$SETTINGS 有 FirstRun.acceptPrivacy(context) 一枚 ⇒ " +
                "acceptHand=$acceptHandOnThePermanentScreen，而 $ONBOARDING 里 " +
                "PERMANENT_PRIVACY_CONSENT_ENTRY_AVAILABLE = $flagLiteral。" +
                "按钮被删而旗还写着 true，判据就承诺一枚盘面上没有的按钮；" +
                "按钮还在而旗被翻成 false，每一手跳过都会被念成死路",
            acceptHandOnThePermanentScreen,
            flagLiteral,
        )
    }

    /** ⑤【数出现次数】丙档的持续提示接线：判据一枚、红点两枚（定义 + 调用）、副标题那一句一枚。 */
    @Test
    fun theUnconsentedStateIsSaidOnThePermanentScreen() {
        val settings = blankComments(settings())
        val ledger = listOf(
            "OnboardingConsentGate.screenNotice(" to 1,
            "privacyNotice.showsMark" to 1,
            "privacyRowSummary(privacyNotice)" to 1,
            "PrivacyConsentMissingDot()" to 2,
        )
        for ((anchor, expected) in ledger) {
            assertEquals(
                "$SETTINGS 里 $anchor 应当 $expected 处（未同意这件事在常驻页上的持续提示档），实到 " +
                    occurrences(settings, anchor) + " 处 —— 少一枚就是红点或那句话被摘掉了",
                expected,
                occurrences(settings, anchor),
            )
        }
        assertEquals(
            "$ONBOARDING 里 recordOnboardingGesture( 的枚数（5 处调用 + 1 处定义）应当是 6，实到 " +
                occurrences(blankComments(onboarding()), "recordOnboardingGesture("),
            6,
            occurrences(blankComments(onboarding()), "recordOnboardingGesture("),
        )
    }

    /**
     * ⑥【读形状】全 main 的同意位只有两枚落点：引导里的闸门 + 常驻页的确认位。
     * 多一枚就是有人在自己那儿替用户置真；少一枚就是同意入口又只剩一条不可达的路。
     */
    @Test
    fun consentWritesStayTwoAcrossMainSources() {
        val hits = mainJavaDir().walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .map { it.relativeTo(mainJavaDir()).path.replace(File.separatorChar, '/') to it.readText() }
            .filter { (_, text) -> blankComments(text).contains("FirstRun.acceptPrivacy(context)") }
            .toList()
        assertEquals(
            "全 app/src/main/java 里 FirstRun.acceptPrivacy(context) 的落点册子应当是两枚" +
                "（引导闸门 + 常驻页确认位），实到 ${hits.size} 枚：\n" +
                hits.joinToString { (path, _) -> path },
            2,
            hits.size,
        )
        assertEquals(
            "同意位的两枚落点必须一枚在引导、一枚在常驻页（同一处写两遍 = 有一处没吃闸门）：\n" +
                hits.joinToString { (path, _) -> path },
            listOf(ONBOARDING, SETTINGS),
            hits.map { (path, _) -> path }.sorted(),
        )
    }

    // ---- helpers ----

    /** 在册旗的字面值（读源码而不是读常量：常量编译进产物，这里要的是盘上那一行写了什么） */
    private val flagLiteral: Boolean
        get() {
            val match = Regex("""PERMANENT_PRIVACY_CONSENT_ENTRY_AVAILABLE\s*=\s*(true|false)""")
                .find(blankComments(onboarding()))
            check(match != null) {
                "$ONBOARDING 里找不到 PERMANENT_PRIVACY_CONSENT_ENTRY_AVAILABLE = true/false 那一行：" +
                    "这枚旗是判据的输入，挪走了就要把守卫一起改"
            }
            return match.groupValues[1] == "true"
        }

    private fun occurrences(text: String, needle: String): Int =
        Regex(Regex.escape(needle)).findAll(text).count()

    private fun onboarding(): String = source(ONBOARDING)

    private fun settings(): String = source(SETTINGS)

    private fun source(relative: String): String {
        val file = File(mainJavaDir(), relative)
        assertTrue("找不到 ${file.path}：文件挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText()
    }

    private fun mainJavaDir(): File {
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

    /** 从 [signature] 之后第一个 `{` 起配平到对应右括号（含）；找不到锚点就抛，静默跳过等于没有守卫 */
    private fun balancedBlock(source: String, signature: String): String {
        val text = blankComments(source)
        val at = text.indexOf(signature)
        check(at >= 0) { "找不到 $signature：写法换过了，这条守卫要跟着改" }
        val open = text.indexOf('{', at)
        check(open >= at) { "$signature 之后找不到左括号" }
        var depth = 0
        for (index in open until text.length) {
            when (text[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(at, index + 1)
                }
            }
        }
        throw IllegalStateException("$signature 的花括号没配平")
    }

    /** 把 `//` 与 `/* */` 注释抹成空格（字符串保留、长度与换行位置不变）：钉的是接线，不是白话 */
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
        const val ONBOARDING = "com/buaa/schedule/ui/onboarding/OnboardingScreen.kt"
        const val SETTINGS = "com/buaa/schedule/ui/settings/SettingsScreen.kt"
    }
}
