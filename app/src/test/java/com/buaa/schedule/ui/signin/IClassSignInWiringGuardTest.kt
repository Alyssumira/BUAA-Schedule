package com.buaa.schedule.ui.signin

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iClass 这一族签到链的**接线**守卫（T84）。
 *
 * 这一族最贵的三种坏法都不是"签不上"，而是**看起来签得上**：
 * 1. 失败卡把用户送去**另一族**的登录页 —— 他在 iClass 上没登录，落到的却是一页
 *    只需要走完 CAS 跳转的 WebView，页面上没有一个这一族要填的字段（静默死路，
 *    这一族 T44/T45/T59 已经修过三条，不许再开第四条）；
 * 2. 设置页那一行带了 `= {}` 默认值 —— 漏接线时编译不响、行照旧画得出、点下去没反应（T41/T59④ 那一族）；
 * 3. 把口令落盘"以便下次自动重登" —— 那对凭据能过学校统一身份认证，明文进 SharedPreferences
 *    就是交给任何拿到这台设备的人（老客户端正是这么干的，本仓刻意不照抄）。
 *
 * 刀法照抄 `SpocSignInEntryWiringGuardTest`：读源码文本、**读不到锚点就抛**
 * （跳过的守卫比没有守卫更糟）、匹配前先抹注释。
 */
class IClassSignInWiringGuardTest {

    /** ① 路由真的注册了，而且渲染的就是那一页；登完换成扫码页而不是留在栈里 */
    @Test
    fun iclassLoginRouteIsRegisteredAndHandedToTheScanScreen() {
        val code = blankComments(source(MAIN_ACTIVITY))
        assertEquals(
            "composable(\"iclass_login\") 注册了 ${Regex("""composable\("iclass_login"""").findAll(code).count()} 处，应当恰好一处",
            1, Regex("""composable\("iclass_login"""").findAll(code).count(),
        )
        val block = balancedBlock(code, "composable(\"iclass_login\")")
        assertTrue("路由注册了但里面没渲染 IClassLoginScreen：\n$block", block.contains("IClassLoginScreen("))
        assertTrue(
            "登录成功后没换成扫码页（用户登完还得自己退出去找入口）：\n$block",
            block.contains("navController.navigate(\"spoc_scan\")"),
        )
        assertTrue("登录页在栈里没被 popUpTo 掉：\n$block", block.contains("popUpTo(\"iclass_login\") { inclusive = true }"))
    }

    /** ② 扫码页那颗「去登录」按平台分流，两族的目标都在 */
    @Test
    fun failureCardSendsEachPlatformToItsOwnLoginPage() {
        val code = blankComments(source(MAIN_ACTIVITY))
        val block = balancedBlock(code, "composable(\"spoc_scan\")")
        assertTrue("扫码页的 onNeedLogin 不再按平台分流（iClass 会被送进 WebView 登录页）：\n$block", block.contains("SignInPlatform.IClass -> navController.navigate(\"iclass_login\")"))
        assertTrue("SPOC 那一族失去了它的登录出口：\n$block", block.contains("SignInPlatform.Spoc -> navController.navigate(\"spoc_login\")"))

        val screen = blankComments(source(SCAN_SCREEN))
        assertTrue(
            "失败卡上那颗按钮没有把平台交给调用方（平台信息在状态机里丢了）：\n$screen",
            screen.contains("onClick = { onNeedLogin(s.platform) }"),
        )
        val vm = blankComments(source(VIEW_MODEL))
        assertTrue("Failed 状态不带平台字段：\n$vm", vm.contains("val platform: SignInPlatform"))
        assertEquals("SignInPlatform 的定义处数：", 1, Regex("""enum class SignInPlatform""").findAll(vm).count())
    }

    /** ③ 没有 iClass 会话时那条支路必须说话并且给出口（不许静默） */
    @Test
    fun missingIClassSessionLeadsToTheLoginPageNotToASilentStop() {
        val vm = blankComments(source(VIEW_MODEL))
        val branch = balancedBlock(vm, "private suspend fun submitToIClass(")
        assertTrue("没有会话时没走 authorized()：\n$branch", branch.contains("IClassSession.authorized()"))
        val failed = branch.substringAfter("SignInState.Failed(")
        assertTrue("没有会话那一档没落到失败卡上：\n$branch", failed.isNotBlank() && !failed.startsWith(")"))
        assertTrue("失败卡不给「去登录」出口（relogin 没置上）：\n$branch", failed.contains("relogin = true"))
        assertTrue("出口没指明是 iClass 那一族：\n$branch", failed.contains("platform = SignInPlatform.IClass"))
        assertTrue("登录页那条链上没人取过 id：\n$branch", branch.contains("iClassApi.signIn("))
    }

    /** ④ 设置页那一行：默认值不许长回来，两处调用点都得把回调传下去 */
    @Test
    fun settingsRowHasNoSilentDefaultAndBothCallSitesPassTheCallback() {
        val settings = blankComments(source(SETTINGS_SCREEN))
        val parameter = Regex("""onOpenIClassSignIn: \(\) -> Unit(\s*=\s*[^\n,]*)?""").find(settings)
        check(parameter != null) { "SettingsScreen 的参数表里没有 onOpenIClassSignIn：那一行入口换地方了，本守卫要跟着改" }
        assertFalse(
            "这颗回调带了 no-op 默认值（漏接线时编译不响、那一行永远点不动）：${parameter.value.trim()}",
            parameter.value.contains("= {"),
        )
        assertEquals("onClick = onOpenIClassSignIn 的落点应当恰好一处：", 1, occurrences(settings, "onClick = onOpenIClassSignIn"))
        // 与 SPOC 那颗共用同一条清凭证的形状：清完要把本地状态翻回未登录，不能等重组
        val logout = balancedBlock(settings, "title = \"退出北航 iClass 登录\"")
        assertTrue("退出这一档没真的清会话：\n$logout", logout.contains("IClassSession.clear()"))
        assertTrue("清完没把界面状态翻回去（那一行会一直写着已登录）：\n$logout", logout.contains("iclassSignedIn = false"))

        val activity = blankComments(source(MAIN_ACTIVITY))
        assertEquals("MainActivity 里 SettingsScreen 的调用点数应当是 2（根页 + 分类页）：", 2, Regex("""SettingsScreen\(""").findAll(activity).count())
        callArgumentLists(activity, "SettingsScreen(").forEachIndexed { index, site ->
            assertTrue("第 ${index + 1} 处 SettingsScreen 没传 onOpenIClassSignIn：\n$site", site.contains("onOpenIClassSignIn = openIClassSignIn"))
        }
    }

    /** ⑤ 口令不落盘：存储侧只有一枚 id，登录页也不许出现"记住口令"那类写法 */
    @Test
    fun passwordNeverReachesAnyStore() {
        val session = blankComments(source(ICLASS_SESSION))
        for (banned in listOf("SharedPreferences", "putString", ".edit {", "getSharedPreferences")) {
            assertFalse("会话层自己去碰明文存储（$banned）：落盘只许走 KeystoreBlobStore", session.contains(banned))
        }
        // 落盘函数收的就是 id：签名里没有 password 这一位
        val save = balancedBlock(session, "fun save(")
        assertTrue("save 的入参不再是 (context, id)：\n$save", save.contains("fun save(context: Context, id: String)"))
        assertTrue("会话层往 IClassIdStore 写了别的东西：\n$session", session.contains("IClassIdStore.save(context, value)"))
        // 落盘的唯一来源是登录响应里那枚 id（不是 phone、不是 password）
        assertTrue(
            "login 成功后落盘的不再是响应回的那枚 id：\n$session",
            session.contains(".onSuccess { id -> save(context, id) }"),
        )
        val store = blankComments(source(ICLASS_STORE))
        assertTrue("id 存储不是 KeystoreBlobStore 的实例：\n$store", store.contains("KeystoreBlobStore("))
        assertFalse("id 存储里出现了 password 字样：\n$store", store.contains("password"))
        val screen = blankComments(source(ICLASS_LOGIN_SCREEN))
        assertTrue("登录页没有把口令交给会话层：\n$screen", screen.contains("IClassSession.login(context, phone, password)"))
        assertTrue("登录成功后没把口令从内存里清掉：\n$screen", screen.contains("password = \"\""))
        for (banned in listOf("SharedPreferences", "putString", "AutoFocus", "autofill")) {
            assertFalse("登录页自己去碰持久化/自动填充（$banned）：\n$screen", screen.contains(banned))
        }
    }

    /** ⑥ 扫码页这一族的清单门槛：不许有人为了这条链去放开明文流量 */
    @Test
    fun manifestKeepsCleartextClosed() {
        val manifest = File(findRepoRoot(), "app/src/main/AndroidManifest.xml").let {
            assertTrue("找不到清单：$it", it.isFile)
            it.readText()
        }
        for (banned in listOf("usesCleartextTraffic", "networkSecurityConfig", "xml/network_security")) {
            assertFalse("清单里出现了「$banned」：iClass 那条链靠 https:8181 走通，不靠放开明文", manifest.contains(banned))
        }
    }

    // ---- 源码核对工具（与 SpocSignInEntryWiringGuardTest 同一套刀法）----

    private fun source(relative: String): String {
        val file = File(findMainJavaDir(), relative)
        assertTrue("找不到 ${file.path}：文件挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText()
    }

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

    private companion object {
        const val MAIN_ACTIVITY = "com/buaa/schedule/MainActivity.kt"
        const val SETTINGS_SCREEN = "com/buaa/schedule/ui/settings/SettingsScreen.kt"
        const val SCAN_SCREEN = "com/buaa/schedule/ui/signin/SpocScanScreen.kt"
        const val VIEW_MODEL = "com/buaa/schedule/ui/signin/SignInViewModel.kt"
        const val ICLASS_SESSION = "com/buaa/schedule/data/import/IClassSession.kt"
        const val ICLASS_STORE = "com/buaa/schedule/data/local/IClassIdStore.kt"
        const val ICLASS_LOGIN_SCREEN = "com/buaa/schedule/ui/signin/iclass/IClassLoginScreen.kt"
    }
}
