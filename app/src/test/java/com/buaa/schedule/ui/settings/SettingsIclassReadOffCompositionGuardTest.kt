package com.buaa.schedule.ui.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #713 甲档的落地守卫：iClass 登录状态的首枚取数不再落在组合期。
 *
 * ## 病灶（只这一处，别泛化）
 * `ui/settings/SettingsScreen.kt` 从前在函数体根组合里写
 * `remember(permissionResumeTick) { mutableStateOf(IClassSession.hasSession()) }`，
 * 那一手顺着 `IClassSession.userId()` → `IClassIdStore.load` 走到
 * `data/local/KeystoreBlobStore.kt` 的密钥库 binder ＋ AES/GCM ＋ prefs 读，
 * 进程内**首枚**调用就这样钉在组合期。
 * 被推翻的说法一并记在这里，免得下一枚卡再信一遍：`userId()` 的记忆位 `loadedFromDisk`
 * 只写 true、全仓零处写回 false ⇒ 一进程至多解一次密文，所以真档只剩「首枚落在组合期」这一条。
 *
 * ## 判据（四格，全是纯 JVM 源码扫描，零 android.* import）
 * ① 抹注释后全颗文件里那一问恰好 1 枚，**且**它长在 `LaunchedEffect(permissionResumeTick)` 的
 *    配平块里、那一块同时含 `withContext(Dispatchers.IO)` —— 两半都断，只断「块里有」会放过
 *    块外另有一枚同步读的形状。
 * ② `permissionResumeTick++` 仍在，且那枚 Effect 的键就是 `permissionResumeTick`
 *    （不是 `Unit`、不是无 key）。
 * ③ 三档文案表驱动逐格钉（title／summary／chip 的字／chip 的色），`null` 档那颗色不许念 error。
 * ④ 反空转＋跨卡备份：退登那一块仍含 `IClassSession.clear()` 与翻回 false 的本地写；
 *    那一枚 item 的可见性实参右值原文在册；`visible = ` 在本文件的枚数按现量写死
 *    （钉的是枚数不是行号）；且「含 `withContext(Dispatchers.IO)` 的那枚块」与「含取数的那枚块」
 *    是同一枚块。
 *
 * ## 写法口径
 * 抄 `SettingsGroupBodyImmediateEvalGuardTest` 的既有方言（`findMainJavaDir` ＋ StringBuilder
 * 那一族抹注释助手），不摊字符数组 —— 那一族的枚数正被 `CommentStrippingHelperShapeGuardTest`
 * 按绝对值钉着，本卡不去动别人那几枚写死值。
 */
class SettingsIclassReadOffCompositionGuardTest {

    /** ① 那一问只剩 1 枚，而且长在 IO 背书的 Effect 块里 */
    @Test
    fun theSessionProbeNowSitsOnlyInsideTheIoBackedEffect() {
        val code = withoutComments(readMain(SETTINGS_SCREEN))
        val hits = positions(code, PROBE)
        assertEquals(
            "全颗 SettingsScreen.kt（抹注释栏）里「IClassSession.hasSession()」不再是恰好 1 枚。" +
                "**2 枚**＝有人把这一问又写回组合期（块外另起一处同步读，本卡那条病就没修）；" +
                "**0 枚**＝取数被摘了，那一行现在永远念「正在确认」。实到 " + hits.size + " 枚",
            1,
            hits.size,
        )
        val block = balancedBlock(code, EFFECT_KEY)
        assertTrue(
            "那枚 Effect 的块里不再含 withContext(Dispatchers.IO) —— 取数又回到组合线程上跑了：\n" + block,
            block.contains(IO_DISPATCH),
        )
        assertEquals(
            "「含 withContext(Dispatchers.IO) 的那枚块」里没有那一问（或不止一枚）：" +
                "取数与切线程必须同在一枚块里，实到 " + occurrences(block, PROBE) + " 枚",
            1,
            occurrences(block, PROBE),
        )
        assertEquals(
            "全颗文件里 withContext(Dispatchers.IO) 不再是恰好 1 枚（多一枚＝第二处 IO 点，" +
                "少一枚＝切线程被摘）",
            1,
            occurrences(code, IO_DISPATCH),
        )
    }

    /** ② ON_RESUME 那一手仍然驱动这枚 Effect，键没被换成 Unit 或没有键 */
    @Test
    fun theResumeTickStillDrivesTheEffectAndTheKeyIsNotUnit() {
        val code = withoutComments(readMain(SETTINGS_SCREEN))
        assertEquals(
            "`permissionResumeTick++` 不再是恰好 1 枚：从系统设置页返回时那一格就不再刷新登录态了",
            1,
            occurrences(code, TICK_BUMP),
        )
        assertEquals(
            "本文件里 `LaunchedEffect(` 的调用点不再是恰好 1 枚（多一枚＝又起了一处延迟位）",
            1,
            occurrences(code, "LaunchedEffect("),
        )
        assertEquals(
            "那枚 Effect 的键不再是 `permissionResumeTick` 逐字（换成 Unit／无 key＝从 iClass 登录页" +
                "返回时登录态不再改口；换成别的位＝刷新源被换人）",
            1,
            occurrences(code, EFFECT_KEY),
        )
        assertEquals(
            "`LaunchedEffect(Unit)` 出现在本文件＝键被摘，那一问只在首次组合读一次",
            0,
            occurrences(code, "LaunchedEffect(Unit)"),
        )
        assertEquals(
            "状态位仍然是一枚可变状态而不是快照（`null` 档要能被 IO 那手改口）",
            1,
            occurrences(code, STATE_DECL),
        )
        assertTrue(
            "老正形回来了：取数又被塞进带键的 remember 快照里（mutableStateOf(IClassSession…) 正是本卡要挪开的那一手）",
            occurrences(code, "mutableStateOf(IClassSession") == 0,
        )
    }

    /** ③ 三档文案表：逐格逐字节钉，`null` 档的色不许念 error */
    @Test
    fun theThreeTierCopyTableIsPinnedByteForByte() {
        val code = withoutComments(readMain(SETTINGS_SCREEN))
        val title = balancedBlock(code, "title = when (iclassSignedIn) {")
        val summary = balancedBlock(code, "summary = when (iclassSignedIn) {")
        val chipText = balancedBlock(code, "text = when (iclassSignedIn) {")
        val chipColor = balancedBlock(code, "color = when (iclassSignedIn) {")
        val cells = listOf(
            title to "null -> \"正在确认北航 iClass 登录状态\"",
            title to "true -> \"已登录北航 iClass\"",
            title to "false -> \"未登录北航 iClass\"",
            summary to "null -> \"正在读本机加密存储里那枚 id，读完这句会自己改口\"",
            summary to "true -> \"课堂上扫的那张码（轻新课堂）用的 id 只存在本机加密存储里，不随备份迁移\"",
            summary to "false -> \"老师投的那张码属于这一族：登一次即可，之后签到只用到服务端回传的 id\"",
            chipText to "null -> \"确认中\"",
            chipText to "true -> \"正常\"",
            chipText to "false -> \"去登录\"",
            chipColor to "null -> MaterialTheme.colorScheme.onSurfaceVariant",
            chipColor to "true -> MaterialTheme.colorScheme.onSurfaceVariant",
            chipColor to "false -> MaterialTheme.colorScheme.error",
        )
        for ((block, cell) in cells) {
            assertTrue(
                "三档表里这一格漂了（逐字节判）：" + cell + "\n所属块头 40 个字符＝" +
                    block.take(40).replace("\n", "⏎"),
                block.contains(cell),
            )
        }
        val nullColor = chipColor.lines().first { it.trimStart().startsWith("null ->") }
        assertTrue(
            "`null` 档那颗 chip 的字色改成了 error（卡面明令不许：读盘中不是错误，别拿红字吓人）：" +
                nullColor.trim(),
            !nullColor.contains("colorScheme.error") && nullColor.contains("onSurfaceVariant"),
        )
        assertEquals(
            "chip 的色那一块里 error 不再是恰好 1 枚（应当只剩 false 档那一枚）",
            1,
            occurrences(chipColor, "colorScheme.error"),
        )
        assertTrue(
            "退登那一枚 item 的可见性实参右值不再是「与 true 比」：`null` 档会把那一行亮在未读到盘时，" +
                "而它是唯一清凭证的活路 —— 这一格是甲档形状的一部分",
            code.contains(ITEM_LOGOUT_ROW),
        )
    }

    /** ④ 反空转＋跨卡备份：退登那手的本地写仍在原地翻面，枚数尺与同一枚块尺都在 */
    @Test
    fun theLogoutStillFlipsTheStateInPlaceAndTheRulerCountsHold() {
        val code = withoutComments(readMain(SETTINGS_SCREEN))
        val logout = balancedBlock(code, "title = \"退出北航 iClass 登录\"")
        assertTrue(
            "退登那一块不再清凭证了（IClassSession.clear() 没了）—— 盘上那枚 id 一旦失效就没有活路：\n" + logout,
            logout.contains("IClassSession.clear()"),
        )
        assertEquals(
            "退登那一块里翻回未登录的本地写不再是恰好 1 枚。LaunchedEffect 不替它翻面：这一手删掉，" +
                "退登后界面会继续念「已登录」直到下次进页 —— 卡面 ③ 那条代价钉的就是它：\n" + logout,
            1,
            occurrences(logout, "iclassSignedIn = false"),
        )
        // 钉的是枚数不是行号：这一档若写成行号，上面任何一段注释把行推下去就误红。
        assertEquals(
            "SettingsScreen.kt 里 `visible = ` 的枚数不再是现量 4 枚（icclass 那一枚 ＋ 学期／毛玻璃／" +
                "状态那三枚的并）—— 枚数漂了先问新增那一枚取不取数，别直接改这格写死值",
            VISIBLE_ARG_TOTAL_IN_SCREEN,
            occurrences(code, VISIBLE_ARG),
        )
        val effectBlock = balancedBlock(code, EFFECT_KEY)
        assertTrue(
            "IO 那手与那一问不在同一枚块里（切线程与取数分家＝本卡 ① 要防的那一形）",
            effectBlock.contains(IO_DISPATCH) && effectBlock.contains(PROBE),
        )
        assertEquals(
            "全颗文件里 `LaunchedEffect(` 只有 1 枚，所以「含 IO 的那枚块」与「含取数的那枚块」是同一枚" +
                "（这一格与 ① 合起来才堵住块外另起一处同步读）",
            1,
            occurrences(balancedBlock(code, "LaunchedEffect("), PROBE),
        )
        assertTrue(
            "取数那一点仍在主源码的 com/buaa/schedule/ui/settings 这一族里（文件挪过家的话这条守卫要" +
                "跟着改路径，不是把本格摘掉）",
            readMain(SETTINGS_SCREEN).isNotEmpty(),
        )
    }

    // ---- 读盘 ---------------------------------------------------------------------

    private fun readMain(relativeFromMainJava: String): String {
        val file = File(findMainJavaDir(), relativeFromMainJava)
        assertTrue("找不到 ${file.path}：文件挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText()
    }

    private fun findMainJavaDir(): File {
        var dir: File? = File("").absoluteFile
        repeat(5) {
            val hit = listOf("src/main/java", "app/src/main/java")
                .map { path -> File(dir, path) }
                .firstOrNull { candidate -> candidate.isDirectory }
            if (hit != null) return hit
            dir = dir?.parentFile
        }
        throw IllegalStateException("找不到 app/src/main/java：当前目录 " + File("").absolutePath)
    }

    // ---- 仪器（文件顶层：纯文本刀法，与 SettingsGroupBodyImmediateEvalGuardTest 同方言）----

    /**
     * 从 [signature] 之后第一个 `{` 起配平到对应右括号（含），交回「签名 + 块体」整段；
     * 找不到锚点就抛，静默跳过等于没有守卫。
     */
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

    companion object {
        private const val SETTINGS_SCREEN = "com/buaa/schedule/ui/settings/SettingsScreen.kt"
        private const val PROBE = "IClassSession.hasSession()"
        private const val EFFECT_KEY = "LaunchedEffect(permissionResumeTick) {"
        private const val IO_DISPATCH = "withContext(Dispatchers.IO)"
        private const val TICK_BUMP = "permissionResumeTick++"
        private const val STATE_DECL = "var iclassSignedIn by remember { mutableStateOf<Boolean?>(null) }"
        private const val ITEM_LOGOUT_ROW = "item(key = \"iclass_logout\", visible = iclassSignedIn == true) {"

        // 现量（71fbeda ＋ #713 甲档落地后）：钉枚数，不钉行号。
        private const val VISIBLE_ARG = "visible = "
        private const val VISIBLE_ARG_TOTAL_IN_SCREEN = 4

        /** 只抹注释、保留字面量与换行位置（长度不变 ⇒ 行号仍可信）。走 StringBuilder。 */
        private fun withoutComments(source: String): String {
            val builder = StringBuilder(source.length)
            var i = 0
            while (i < source.length) {
                when {
                    source.startsWith("//", i) -> {
                        var j = i
                        while (j < source.length && source[j] != '\n') j++
                        repeat(j - i) { builder.append(' ') }
                        i = j
                    }

                    source.startsWith("/*", i) -> {
                        var depth = 1
                        var j = i + 2
                        while (j < source.length && depth > 0) {
                            when {
                                source.startsWith("/*", j) -> {
                                    depth++
                                    j += 2
                                }

                                source.startsWith("*/", j) -> {
                                    depth--
                                    j += 2
                                }

                                source.startsWith(TRIPLE_QUOTE, j) -> {
                                    val end = source.indexOf(TRIPLE_QUOTE, j + 3)
                                    j = if (end < 0) source.length else end + 3
                                }

                                source[j] == '"' || source[j] == '\'' -> j = skipLiteral(source, j)
                                else -> j++
                            }
                        }
                        for (k in i until minOf(j, source.length)) {
                            builder.append(if (source[k] == '\n') '\n' else ' ')
                        }
                        i = j
                    }

                    source.startsWith(TRIPLE_QUOTE, i) -> {
                        val end = source.indexOf(TRIPLE_QUOTE, i + 3)
                        val stop = if (end < 0) source.length else end + 3
                        builder.append(source, i, stop)
                        i = stop
                    }

                    source[i] == '"' || source[i] == '\'' -> {
                        val stop = skipLiteral(source, i)
                        builder.append(source, i, stop)
                        i = stop
                    }

                    else -> {
                        builder.append(source[i])
                        i++
                    }
                }
            }
            return builder.toString()
        }

        /** 字符串／字符字面量的收尾下标（含闭引号）；未闭合就退回行首，绝不吞掉后面的整段代码。 */
        private fun skipLiteral(source: String, at: Int): Int {
            if (source.startsWith(TRIPLE_QUOTE, at)) {
                val end = source.indexOf(TRIPLE_QUOTE, at + 3)
                return if (end < 0) source.length else end + 3
            }
            val quote = source[at]
            var j = at + 1
            while (j < source.length) {
                when {
                    source[j] == '\\' -> j += 2
                    source[j] == quote -> return j + 1
                    source[j] == '\n' -> return j
                    else -> j++
                }
            }
            return source.length
        }

        private const val TRIPLE_QUOTE = "\"\"\""

        private fun positions(text: String, needle: String): List<Int> {
            val hits = mutableListOf<Int>()
            var from = 0
            while (true) {
                val at = text.indexOf(needle, from)
                if (at < 0) return hits
                hits.add(at)
                from = at + needle.length
            }
        }

        private fun occurrences(text: String, needle: String): Int = positions(text, needle).size
    }
}
// 变异自证两臂（本趟现量；两臂各跑一次过滤测试、只读新守卫这一枚类；还原一律 git checkout ＋ git diff --quiet HEAD 对账，两次都交回 CLEAN）：
// W 朝宽（状态位那一行改回带键的 remember 快照里直接问会话）＝ 4 tests / 2 failures：
//   ① theSessionProbeNowSitsOnlyInsideTheIoBackedEffect 红在「那一问不再是恰好 1 枚」：expected:<1> but was:<2>（本文件 :44）
//   ② theResumeTickStillDrivesTheEffectAndTheKeyIsNotUnit 红在「状态位仍是一枚可变状态」那一格：expected:<1> but was:<0>（:95）
// N 朝窄（withContext(Dispatchers.IO) 被摘、只在 Effect 里 launch 同步解）＝ 4 tests / 2 failures：
//   ① 同一枚格红在「块里不再含 IO 那手」，消息里逐字念出病形块体（:52）
//   ④ theLogoutStillFlipsTheStateInPlaceAndTheRulerCountsHold 红在「IO 那手与那一问不在同一枚块里」（:176）
// 合点：取数只许长在「键为 permissionResumeTick 的那一枚 Effect ＋ IO 那手」里，宽窄两侧的偏离都被听见。
