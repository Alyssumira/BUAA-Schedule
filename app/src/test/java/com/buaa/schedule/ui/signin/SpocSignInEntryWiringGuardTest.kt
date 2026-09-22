package com.buaa.schedule.ui.signin

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 扫码签到那条入口的**接线**守卫（T59④）。
 *
 * 用户报「扫码没反应」有几种不同的病因，这一条是其中最省事的一种：页面本身完好，
 * 但通往它的那条线是空的。本仓已经为此栽过一次（T41「学期统计」入口静默 no-op），
 * 而 `SettingsScreen` 的那一族回调全都带着 `= {}` —— 漏传一个参数编译器不响、
 * 界面照旧画得出那一行、点下去什么也不发生。这一轮把签到那颗的默认值摘掉，
 * 并用本文件钉住"摘了之后不许再长回来"。
 *
 * 刀法照抄 `CourseMetaWiringGuardTest` / `NowLineUnderCardsGuardTest`：读源码文本、
 * **读不到目录就抛**（跳过的守卫比没有守卫更糟）、命中 0 处就红、匹配前先抹注释
 * （签到页的 KDoc 里就写着「手输签到码已删除」这句禁令本身，连注释一起扫会红在自己人手上）。
 *
 * 钉六条：
 * 1. `spoc_scan` 这条路由真的注册在 NavHost 上，而且 `SpocScanScreen` 就是它的目标；
 * 2. 两处 `SettingsScreen(` 调用点都把 `onOpenSpocSignIn` 传成了同一个回调（漏一处 = 那一档点不动）；
 * 3. 那颗参数**没有** no-op 默认值，而设置页里它真的挂在某一行的 `onClick` 上；
 * 4. 首页那条入口走的也是同一个回调，且 `HomeScreen` 的参数也没有默认值兜着；
 * 5. 通知按钮那条深链的路由名与注册的路由同名（改了名字深链会静默落回首页）；
 * 6. 已删除的第三条入口「手输签到码」不许以任何形态回来（禁现扫描 + 禁现输入控件）。
 */
class SpocSignInEntryWiringGuardTest {

    /** ① 路由注册：`composable("spoc_scan")` 一处，且它渲染的就是扫码页 */
    @Test
    fun scanRouteIsRegisteredAndRendersTheScanScreen() {
        val code = blankComments(source(MAIN_ACTIVITY))
        assertEquals(
            "composable(\"spoc_scan\") 注册了 ${Regex("""composable\("spoc_scan"""").findAll(code).count()} 处，应当恰好一处",
            1, Regex("""composable\("spoc_scan"""").findAll(code).count(),
        )
        val block = balancedBlock(code, "composable(\"spoc_scan\")")
        assertTrue("路由注册了但里面没渲染 SpocScanScreen：\n$block", block.contains("SpocScanScreen("))
        assertTrue(
            "扫码页的 onNeedLogin 不再跳 spoc_login（凭证失效时没有出口）：\n$block",
            block.contains("""navController.navigate("spoc_login")"""),
        )
    }

    /** ② 两处设置页调用点都得把那颗回调传下去（少一处 = 那一档静默 no-op） */
    @Test
    fun everySettingsScreenCallSitePassesTheSignInCallback() {
        val code = blankComments(source(MAIN_ACTIVITY))
        // 实参表按括号配平取，不能用非贪婪正则：`onBack = { navController.popBackStack() }`
        // 里那个 `) }` 会把懒匹配提前截断，量出来的"调用点"只剩头一行
        val sites = callArgumentLists(code, "SettingsScreen(")
        assertEquals("MainActivity 里的 SettingsScreen 调用点数应当是 2（根页 + 分类页）：${sites.size}", 2, sites.size)
        sites.forEachIndexed { index, site ->
            assertTrue("第 ${index + 1} 处 SettingsScreen 没传 onOpenSpocSignIn：\n$site", site.contains("onOpenSpocSignIn = openSpocSignIn"))
        }
        // 同一份判断只许写一次：两处各写一份迟早走岔（openSpocSignIn 的定义就是为此存在的）
        assertEquals(
            "openSpocSignIn 的定义处数应当是一处：",
            1, Regex("""val openSpocSignIn: \(\) -> Unit =""").findAll(code).count(),
        )
        val definition = balancedBlock(code, "val openSpocSignIn: () -> Unit =")
        assertTrue("那条入口不再按登录态分 spoc_scan / spoc_login：\n$definition", definition.contains("\"spoc_scan\""))
        assertTrue("没有登录态时它要先送登录页，而不是直接进必然失败的扫码页：\n$definition", definition.contains("\"spoc_login\""))
    }

    /** ③ 参数不许有 no-op 默认值，而设置页那一行真的把它挂在 onClick 上 */
    @Test
    fun signInCallbackParameterHasNoSilentDefault() {
        val code = blankComments(source(SETTINGS_SCREEN))
        val parameter = Regex("""onOpenSpocSignIn: \(\) -> Unit(\s*=\s*[^\n,]*)?""").find(code)
        check(parameter != null) { "SettingsScreen 的参数表里已经没有 onOpenSpocSignIn 了：接线前提变了，本守卫要跟着改" }
        assertFalse(
            "这颗回调又长回了 `= {}`：漏传时编译器不响、那一行设置点下去什么都不发生（T41 的成因）。" +
                "要恢复默认值就得先把本条守卫与 STATUS 里 T59④ 那一段一起改掉：${parameter.value.trim()}",
            parameter.value.contains("= {"),
        )
        assertEquals("onClick = onOpenSpocSignIn 的落点应当是一处（账户那一行）：", 1, occurrences(code, "onClick = onOpenSpocSignIn"))
        // 全仓不许有人当场传一颗空的进去
        val silent = blankComments(source(MAIN_ACTIVITY)).contains("onOpenSpocSignIn = {}")
        assertFalse("MainActivity 现场传了一颗 no-op 进去（等于换个地方把线剪了）：", silent)
    }

    /** ④ 首页那条入口共用同一个回调，HomeScreen 侧也不许有默认值 */
    @Test
    fun homeEntryUsesTheSameCallbackWithoutADefault() {
        val activity = blankComments(source(MAIN_ACTIVITY))
        assertTrue("首页的加号菜单不再走那颗共用回调（两处各写一份迟早走岔）：", activity.contains("onSpocSignIn = openSpocSignIn"))
        val home = blankComments(source(HOME_SCREEN))
        val parameter = Regex("""onSpocSignIn: \(\) -> Unit(\s*=\s*[^\n,]*)?""").find(home)
        check(parameter != null) { "HomeScreen 已经没有 onSpocSignIn 参数：入口换地方了，本守卫要跟着改" }
        assertFalse("HomeScreen 的参数带了默认值，漏传就是静默 no-op：${parameter.value.trim()}", parameter.value.contains("= {"))
        assertTrue("「扫码签到」那颗菜单项没调用它：", home.contains("{ onSpocSignIn() }"))
    }

    /** ⑤ 通知深链那一份路由名必须与注册的同名（漂了就是点了没反应，编译器不会响） */
    @Test
    fun notificationDeepLinkUsesTheSameRouteName() {
        val activity = blankComments(source(MAIN_ACTIVITY))
        val routable = Regex("""ROUTABLE_FROM_INTENT\s*=\s*setOf\(([^)]*)\)""").find(activity)
        check(routable != null) { "找不到 ROUTABLE_FROM_INTENT：从通知进页面的白名单换写法了，这条要跟着改" }
        assertTrue("spoc_scan 不在从 intent 放行的路由名单里（通知按钮点了会被拦回首页）：${routable.groupValues[1]}", routable.groupValues[1].contains("\"spoc_scan\""))
        val notifications = blankComments(source(REMINDER_NOTIFICATIONS))
        assertTrue("通知侧的扫码按钮不再指向 spoc_scan：", notifications.contains("""route = "spoc_scan""""))
    }

    /**
     * ⑥ 第三条入口不许回来（本卡硬规则）：手输签到码在现实里不存在可抄的东西，
     * 2026-09-21 整条删除。禁现的是**代码**里的字样与输入控件 —— 注释里写这句禁令
     * 本身是必要的背景，所以先抹注释再扫。
     */
    @Test
    fun manualCodeEntryDoesNotComeBackInAnyShape() {
        val dir = findMainJavaDir()
        val signin = File(dir, SIGNIN_PACKAGE)
        assertTrue("找不到扫码页目录 $SIGNIN_PACKAGE：扫描范围都取错了", signin.isDirectory)
        val files = signin.listFiles { f: File -> f.isFile && f.extension == "kt" }.orEmpty().sortedBy { it.name }
        assertTrue("$SIGNIN_PACKAGE 下一个 .kt 都没有，路径不对", files.isNotEmpty())
        for (file in files) {
            val code = blankComments(file.readText())
            for (banned in listOf("手输", "输入签到码", "手动输入")) {
                assertFalse("${file.name} 里出现了「$banned」：那条按假想需求做的入口又回来了", code.contains(banned))
            }
            // 一颗能敲字符串的控件就是那条入口的另一种形态（粘贴框、隐藏调试口同理）
            for (control in listOf("TextField(", "OutlinedTextField(", "BasicTextField(", "TextFieldValue")) {
                assertFalse("${file.name} 里出现了输入控件「$control」：签到码只能来自二维码", code.contains(control))
            }
        }
    }

    // ---- 源码核对工具（与 CourseMetaWiringGuardTest 同一套刀法）----

    private fun source(relative: String): String {
        val file = File(findMainJavaDir(), relative)
        assertTrue("找不到 ${file.path}：文件挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText()
    }

    /**
     * 每一次 [call]（形如 `Name(`）的实参表，配平到与之匹配的右括号（含两端）。
     *
     * 为什么不用正则：`onBack = { navController.popBackStack() }` 里那个 `) }`
     * 会把懒匹配的 `SettingsScreen\(([\s\S]*?)\)\s*\}` 提前截断，量出来的"一处调用点"
     * 只剩头两行 —— 那种假红比假绿更容易骗过人（它看起来像在正常工作）。
     * 一处都没命中就抛，静默返回空表等于这条守卫不跑。
     */
    private fun callArgumentLists(code: String, call: String): List<String> {
        require(call.endsWith("(")) { "$call 不是以左括号结尾的调用锚点" }
        val out = ArrayList<String>()
        var from = 0
        while (true) {
            val at = code.indexOf(call, from)
            if (at < 0) break
            val open = at + call.length - 1
            var depth = 0
            var end = -1
            for (index in open until code.length) {
                when (code[index]) {
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) {
                            end = index
                            break
                        }
                    }
                }
            }
            check(end > open) { "$call 的实参表括号没配平" }
            out += code.substring(open, end + 1)
            from = end
        }
        check(out.isNotEmpty()) { "一个 $call 调用点都没有：入口整条没了，本守卫要重新核" }
        return out
    }

    /** 从 [signature] 之后第一个 `{` 起配平到对应右括号（含）；找不到锚点就抛，静默跳过等于没有守卫 */
    private fun balancedBlock(source: String, signature: String): String {
        val at = source.indexOf(signature)
        check(at >= 0) { "找不到 $signature：写法换过了，这条守卫要跟着改" }
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

    private companion object {
        const val MAIN_ACTIVITY = "com/buaa/schedule/MainActivity.kt"
        const val SETTINGS_SCREEN = "com/buaa/schedule/ui/settings/SettingsScreen.kt"
        const val HOME_SCREEN = "com/buaa/schedule/ui/home/HomeScreen.kt"
        const val REMINDER_NOTIFICATIONS = "com/buaa/schedule/reminder/ReminderNotifications.kt"
        const val SIGNIN_PACKAGE = "com/buaa/schedule/ui/signin"
    }
}
