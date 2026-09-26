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
 * 4.（T115 补）**同一页的状态由两枚读者各判一次** —— 不是签不上，而是同一列两行话互相打架：
 *    上面那行状态句按 `saved` → `submitting` → `serverMessage` 排（登完念「已登录…正在进入扫码页…」），
 *    下面那颗按钮的字却写着 `if (submitting)`、不看 `saved`（照旧念「正在登录…」）。
 *    账在 `docs/derived-field-audit.md` §8 #5 / §8.3-B，判据见下面第 ⑦ 枚。
 *
 * 刀法照抄 `ScanSignInEntryWiringGuardTest`：读源码文本、**读不到锚点就抛**
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

    /**
     * ② 失败卡那颗「去登录」真的通向 iClass 的登录页，而且**没有第二个出口**回来。
     *
     * T84/T85 之间这一条的形状换过一次：从前它钉"按 `SignInPlatform` 分流，两族的目标都在"。
     * 智学北航整条拆掉之后，那枚枚举的存在理由（送错一族 = 丢进一个这一族不需要填的页面）
     * 没了，于是枚举、`Failed.platform` 这一位、以及 MainActivity 里那个 `when` 一起拆了 ——
     * 本条改钉"只剩一个出口"，防的是同一件事的现在形态：那颗按钮通向的不是 iClass 登录页，
     * 或者有人又把第二枚枚举与第二条路由接回来（那才是真给这条链开出第四条静默死路）。
     */
    @Test
    fun failureCardGoesToTheOneLoginPageAndNoSecondExitComesBack() {
        val code = blankComments(source(MAIN_ACTIVITY))
        val block = balancedBlock(code, "composable(\"spoc_scan\")")
        assertTrue(
            "扫码页失败卡那颗按钮不再跳 iclass_login（没有会话时用户就出不去）：\n$block",
            block.contains("navController.navigate(\"iclass_login\")"),
        )
        assertFalse("又出现了第二个登录出口：\n$block", block.contains("spoc_login"))
        val vm = blankComments(source(VIEW_MODEL))
        assertEquals(
            "SignInPlatform 的定义处数（这一族只剩一个登录页，那枚枚举应当已经拆干净）：",
            0, Regex("""enum class SignInPlatform""").findAll(vm).count(),
        )
        assertFalse("Failed 状态又带回了平台这一位：\n$vm", vm.contains("val platform:"))
        // 「去登录」那颗按钮仍然把出口交给了调用方（平台信息不该在状态机里丢）
        val screen = blankComments(source(SCAN_SCREEN))
        assertTrue(
            "失败卡上那颗按钮没把出口交给调用方（界面自己决定去哪 = 状态机与路由各说各话）：\n$screen",
            screen.contains("Button(onClick = onNeedLogin"),
        )
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
        // 清凭证的形状：清完要把本地状态翻回未登录，不能等重组（那一行会一直写着"已登录"）
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

    /**
     * ⑦ 登录成功后这一页**只有一处**说得出"登录到哪一步了"（T115 · 账在 `docs/derived-field-audit.md` §8 #5）。
     *
     * 病形状：同一列两行话互相打架。三枚 remembered 变量的读者有两枚各判一次 —— 状态句读那支按
     * `saved` → `submitting` → `serverMessage` 排好的梯，那颗按钮的字却读 `if (submitting)`；
     * `saved` 赢下梯子第一档、按钮**不吃这条梯** ⇒ 登录成功之后、这一页真正离场之前，
     * 上面念「已登录北航 iClass，正在进入扫码页…」而按钮照旧念「正在登录…」。
     * 导航侧 `MainActivity` 给这一页提供了 `LocalAnimatedVisibilityScope` ⇒ 确实存在一段
     * 旧页仍在组合的退场窗口；⚠️ 本仓今天禁设备，那段窗口有多长**没量过**，
     * 本条钉的是"两枚读者不许各判一次"这件事本身，不写帧数也不写毫秒。
     *
     * 落点选方向 2（单一真源，与 #114「顶栏与 body 两个日期源」同一族病同一刀）加方向 3 的那一半
     * （`saved` 一到就把整页收口在「已登录」这一档）：界面话的判定点收成 `val phase = when { … }` 一枚，
     * 状态句 / 那颗按钮的字 / 失败卡的语义色三处**都投影自它**。
     * **不采纳方向 1**（成功档补一句 `submitting = false`）：那枚旗同时是本页唯一的重入闸
     * `if (submitting) return` 与三处 `enabled = !submitting`、键盘 Done 那记短路的共同来源，
     * 落旗等于在退场窗口里把表单放开、并允许第二趟登录 POST —— 而它一旦失败就写 `serverMessage`，
     * 把「已登录」那句话染成失败卡，比原来那句错话贵。故两侧都要有格子：
     * 朝宽扭（第二读者回来 / 换梯序 / 成功档落旗）必须红，
     * 朝紧扭（少一档 / 失败档不落旗 / 锚点换名）也必须红。
     */
    @Test
    fun loginPhaseIsJudgedOnceAndEveryOnScreenReaderProjectsFromIt() {
        val screen = blankComments(source(ICLASS_LOGIN_SCREEN))

        // —— 判一次：阶段梯是三枚裸旗唯一被读成"界面话"的地方（锚点没了就抛，不许静默绿）——
        val ladder = balancedBlock(screen, "val phase = when {")
        assertTrue(
            "阶段梯子没有 saved 这一档（登录成功这件事界面上就再也读不到）：\n$ladder",
            ladder.contains("saved -> LoginPhase.Saved"),
        )
        assertTrue(
            "阶段梯子把 saved 排到 submitting 之后了 ⇒ 登录成功后上面那行又会念「正在登录…」，" +
                "本卡钉的就是这个序：\n$ladder",
            ladder.indexOf("saved ->") in 0 until ladder.indexOf("submitting ->"),
        )
        assertEquals("阶段梯子应当有四档（Idle / Submitting / Failed / Saved）：\n$ladder", 4, occurrences(ladder, "LoginPhase."))

        // —— 界面上那三处读者都投影自阶段值，谁都不许再自己判一次旗 ——
        assertTrue("状态句不再投影自阶段值（它自己又判了一次）：\n$screen", screen.contains("val statusText = when (phase)"))
        assertTrue(
            "那颗按钮的字不再投影自阶段值 —— 从前它写 Text(if (submitting) …)，" +
                "是躲在状态句梯子旁边的第二枚读者，同一列两行互斥就是这么来的：\n$screen",
            screen.contains("Text(phase.buttonLabel)"),
        )
        assertFalse("按钮的字又自己判 submitting 了（第二枚读者回来）：\n$screen", screen.contains("Text(if (submitting)"))
        assertTrue("失败卡那层语义色也按阶段取：\n$screen", screen.contains("val isError = phase == LoginPhase.Failed"))
        assertFalse("语义色又自己读 serverMessage（判法与阶段梯不一致 = 第三枚读者）：\n$screen", screen.contains("val isError = serverMessage != null"))

        // —— Saved 那一档的措辞：说实话；既不跟着"在飞"那句，也不退回"闲置"那句 ——
        val phaseEnum = balancedBlock(screen, "private enum class LoginPhase(val buttonLabel: String) {")
        val savedLabel = Regex("""Saved\("([^"]*)"\)""").find(phaseEnum)
        check(savedLabel != null) { "LoginPhase.Saved 没有 buttonLabel：措辞换载体了，本守卫要跟着改" }
        val wording = savedLabel.groupValues[1]
        assertTrue(
            "登录成功后按钮上写的是「$wording」：与「正在登录…」同句就是本卡钉的那处互斥，" +
                "与「登录」同句则把闸门还开着的一页说成闲下来了：\n$phaseEnum",
            wording.isNotBlank() && wording != "正在登录…" && wording != "登录",
        )
        assertTrue("请求在飞那一档按钮应当说「正在登录…」：\n$phaseEnum", phaseEnum.contains("Submitting(\"正在登录…\")"))

        // —— 闸门：成功档**故意**不落旗（方向 1 的代价），失败档必须落（否则表单永远按住）——
        val onSuccess = balancedBlock(screen, ".onSuccess {")
        assertTrue("登录成功没把 saved 立起来（阶段梯第一档就读不到）：\n$onSuccess", onSuccess.contains("saved = true"))
        assertTrue("登录成功后没把口令从内存里清掉：\n$onSuccess", onSuccess.contains("password = \"\""))
        assertFalse(
            "成功档补了一句 submitting = false（方向 1）：那枚旗是本页唯一的重入闸与三处 enabled、" +
                "键盘 Done 短路的共同来源，落旗等于在退场窗口里松开表单、允许第二趟登录 POST，" +
                "而它一旦失败就写 serverMessage、把「已登录」那句话染成失败卡：\n$onSuccess",
            onSuccess.contains("submitting = false"),
        )
        val onFailure = balancedBlock(screen, ".onFailure {")
        assertTrue("失败档不落旗 ⇒ 表单永远按住、用户改完口令也再来不了：\n$onFailure", onFailure.contains("submitting = false"))
        assertTrue("重入闸没了（同一次登录可以发两趟 POST）：\n$screen", screen.contains("if (submitting) return"))
        assertEquals("读 !submitting 的 enabled 应当恰好三处（两枚字段 + 那颗按钮）：", 3, occurrences(screen, "enabled = !submitting"))
        assertEquals("键盘 Done 那记短路应当恰好一处：", 1, occurrences(screen, "if (!submitting) submit()"))
    }

    // ---- 源码核对工具（与 ScanSignInEntryWiringGuardTest 同一套刀法）----

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
