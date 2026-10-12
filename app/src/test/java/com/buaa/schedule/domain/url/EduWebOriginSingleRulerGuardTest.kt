package com.buaa.schedule.domain.url

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T302③：把**「全 `app/src/main` 里再不许出现拿教务 origin 做裸前缀比较」这一形**钉成 0 处，
 * 并钉住本卡的四样前提（在册 #515）。
 *
 * 纯源码核对（JVM，无 Robolectric、无设备、不读时钟）。读法与小工具抄
 * `domain/calendar/CalendarReclaimSingleSourceGuardTest`（抹注释保字面量 ＋ 数出现次数 ＋ 逐字节锚点）。
 * ⚠️ **报错消息里一枚行号都不写**：行号会漂（本仓为此重钉过十几次锚点，台账 §45 又记了两把新形状的度量错）。
 * 命中那一行的**原文**照登，够把人带到那一处。
 *
 * ## 钉法 = 读形状（主钉），为什么不是另一种
 *
 * 卡面给的四种钉法各有靶，本守卫选「读形状」当主钉，其余三种只在各自的格子里当副钉：
 *
 * - **逐字节不能当主钉**：盘面这一形在改前有四种字节写法（`startsWith("$BYXT_ORIGIN/")` 两枚、
 *   `startsWith("$BYXT_ORIGIN")` 一枚、`startsWith(com.buaa.schedule.data.import.BuaaWebSession.BYXT_ORIGIN)`
 *   一枚），一枚针只能钉住其中一种；把针放宽到覆盖四种，又会把**合法**写法
 *   （`SESSION_ENTRY_URL = "$BYXT_ORIGIN/jwapp/…"` 那枚模板、`API_ORIGIN = BuaaWebSession.BYXT_ORIGIN`
 *   那枚赋值）一起钉成禁物。逐字节的靶是"某一句"，本守卫的靶是"某一形"。它在第④格仍在岗：
 *   钉三枚调用点的原文，防"换成等价但不同形"（`indexOf(prefix) == 0`）那一步退路。
 * - **抹注释找锚点不能当主钉**：那是"锚点必须存在"的正向钉法，拿它钉**缺席**就是假绿的温床 ——
 *   锚点被改名时 `check()` 至少还响，但更常见的是有人把比较换成另一枚写法，锚点还在、防线已经没了。
 *   抹注释在本守卫里是**读法**而不是钉法：所有格子都在抹注释后的文本上取，注释里的历史行号与例句
 *   （内核与两颗调用点的 KDoc 都抄了改前原文当证据）不会伪装成命中。
 * - **数出现次数不能当主钉**：`BYXT_ORIGIN` 在 main 里本来就有三枚合法用法，枚数分不清
 *   "用来比较"与"用来拼 URL"。它当副钉最合适：第②格钉调用枚数、第⑤格钉刀的枚数、
 *   第⑥格钉字面量的逐文件分布、第⑦格钉旧尺子 0 枚。
 *
 * ## 探测器自检（反套套逻辑）
 *
 * 第②组格子把探测器本身过两遍：改前三枚原文（手写在针里的字符串常量，**没有一枚来自被验文件的
 * 运行时输出**）必须逐枚报出；四枚合法/超范围的写法必须一枚都不报。探测器空转 ⇒ 自检先红；
 * 探测器过宽 ⇒ 自检也红。第①格读的是真盘，两格合起来才不是一句自我实现的话。
 *
 * ## 输入面
 *
 * 本守卫读 `app/src/main/java` 下的源码文本 —— 那一条已在 `app/build.gradle.kts` 的
 * `guardReadWorkingTreeFiles` 里整目录进 `tasks.withType<Test>()` 的 `inputs`（T92①，台账 #136），
 * 本次没有新增目录之外的读取面（不读 res/、不读清单、不读脚本），无需再动构建脚本。
 *
 * ## 拦的是哪五步退路
 *
 * 1. 把任何一枚调用点换回前缀比较（第①格）。"加一段路径就当防线"那种绕法由第⑥格兜：
 *    `startsWith("https://byxt.buaa.edu.cn/jwapp…")` 在第①格不报（它问的是第三个问题 ——
 *    `maybeCaptureSession` 那条"是不是教务首页路由"的判据，本卡不动、登记在册），
 *    但它会把 `BuaaLoginScreen.kt` 的教务 host 枚数从 2 抬到 3 ⇒ 第⑥格红；
 * 2. 在内核里补 android/时钟/时区/权限/`Build.VERSION`/库读取，或把两枚分档折成 Boolean（第③格）；
 * 3. 在别处再刻一枚 authority 刀（第⑤格：四刀的定义只许住在 `domain/url/UrlShape.kt`，各 1 枚）；
 * 4. 把 origin 字面量再抄一份进 `data/` 或 `ui/`（第⑥格）；
 * 5. 把 `:509-511` 那把 URI ＋ `endsWith` 后缀的旧尺子留在盘上，让它与内核并存成第二本档（第⑦格）。
 */
class EduWebOriginSingleRulerGuardTest {

    // ─────────────── ① 那一形全树 0 处 ───────────────

    @Test
    fun `拿教务 origin 做裸前缀比较 全树零命中`() {
        val hits = mutableListOf<String>()
        for (file in mainSources()) {
            val code = blankCommentsKeepingLiterals(file.readText())
            for (line in code.lines()) {
                val t = line.trim()
                if (t.isNotEmpty() && barePrefixComparison(t)) hits += "${rel(file)} ⇒ $t"
            }
        }
        assertEquals(
            "`app/src/main` 里「拿教务 origin 做前缀/后缀/包含比较」这一形必须 0 处 —— 它就是 #515 的病体" +
                "（带斜杠与不带斜杠的两种针各自说谎，合起来四把尺）。实到 " + hits.size + " 处：\n" +
                hits.joinToString("\n"),
            0,
            hits.size,
        )
    }

    /**
     * 探测器自检。朝松的靶：探测器空转 ⇒ 前三枚不报，本格红；
     * 朝窄的靶：探测器宽到把"拼 URL"也当禁物 ⇒ 后四枚报出来，本格红。
     */
    @Test
    fun `探测器自检 改前原文报得出 合法写法不冤枉`() {
        val caught = FORBIDDEN_SAMPLES.filter { barePrefixComparison(it.trim()) }
        assertEquals(
            "探测器必须逐枚抓住改前那三枚原文（抓不住就说明它空转，第①格是假绿）。实到 " + caught.size + " 枚：",
            FORBIDDEN_SAMPLES.size,
            caught.size,
        )
        val flaggedLegit = LEGIT_SAMPLES.filter { barePrefixComparison(it.trim()) }
        assertEquals(
            "探测器不许冤枉这四枚写法：前两枚是拼 URL 的模板与赋值，第三枚是 origin 的别名声明，" +
                "第四枚（带路由的字面量）问的是第三个问题、由第⑥格的枚数账盯。实报 " + flaggedLegit.size + " 枚：\n" +
                flaggedLegit.joinToString("\n"),
            0,
            flaggedLegit.size,
        )
    }

    // ─────────────── ② 三枚调用点各问内核，且只问一次 ───────────────

    /**
     * 钉法 = **数出现次数**（分文件；import 行整行抹空后再数 —— 数的是"问了几次"，不是"引了几次"）。
     *
     * `eduDocumentVerdict` 的调用在 main 里恰好 3 枚：`hasSession()` ＋ 恢复链的 `onPageFinished` ＋
     * 登录页就绪闸门。多一枚 = 有人在别处又算了一次"是不是教务"（第二把尺的现在形态）；
     * 少一枚 = 有调用点不再问内核（本卡修的那件事正在退）。
     */
    @Test
    fun `三枚调用点都只问内核`() {
        val session = callCode(WEB_SESSION)
        val login = callCode(LOGIN_SCREEN)
        val kernel = callCode(KERNEL)
        assertEquals(
            "`eduDocumentVerdict` 在 `$WEB_SESSION` 恰好 2 枚调用（hasSession ＋ 恢复链）：",
            2,
            occurrences(session, "eduDocumentVerdict"),
        )
        assertEquals(
            "`eduDocumentVerdict` 在 `$LOGIN_SCREEN` 恰好 1 枚调用（就绪闸门；改前它是裸前缀那一枚）：",
            1,
            occurrences(login, "eduDocumentVerdict"),
        )
        assertEquals(
            "`sessionDomainVerdict` 在 `$WEB_SESSION` 恰好 1 枚调用（留在会话域还是外站的第二问）：",
            1,
            occurrences(session, "sessionDomainVerdict"),
        )
        assertEquals(
            "内核里两枚函数头各 1 枚（改名/搬走 = 本守卫与三枚调用点要一起重钉）；内核之外的调用总数同时钉死 3 ＋ 1：" +
                "实到 " + mainCalls("eduDocumentVerdict") + " 与 " + mainCalls("sessionDomainVerdict") + "：",
            listOf(1, 1, 3, 1),
            listOf(
                occurrences(kernel, "fun eduDocumentVerdict("),
                occurrences(kernel, "fun sessionDomainVerdict("),
                mainCalls("eduDocumentVerdict") - occurrences(kernel, "eduDocumentVerdict"),
                mainCalls("sessionDomainVerdict") - occurrences(kernel, "sessionDomainVerdict"),
            ),
        )
    }

    // ─────────────── ③ 内核纯度与两枚分档 ───────────────

    /**
     * 钉法 = **数出现次数**（禁物 0 枚 ＋ import 0 枚）＋ **逐字节**（返回类型与分档原文）。
     *
     * 两颗内核文件必须零 import 行（连 `java.net.URI` 都不许请 —— 丙档被实测否掉：query 里一枚裸
     * ASCII 空格就让 URI 抛，"真教务文档"于是被念成"不是这个域"，比盘面上那枚带斜杠的针还窄）。
     */
    @Test
    fun `内核保持纯JVM并保持两枚分档`() {
        for (file in listOf(KERNEL, KNIFE)) {
            val code = blankCommentsKeepingLiterals(readMainSource(file))
            assertEquals(
                "`$file` 的 import 行必须 0 枚（domain 层的纯 JVM 判据；请回 URI 就是把丙档那把说谎的刀请回来）：",
                0,
                code.lines().count { it.trim().startsWith("import ") },
            )
            for (forbidden in FORBIDDEN_IN_KERNEL) {
                assertEquals(
                    "内核 `$file` 里出现了禁物「$forbidden」—— 判据本体不许读时钟/时区/权限/`Build.VERSION`/" +
                        "库，也不许换回 URI。改这一格之前先回看内核 KDoc「纯度」那一段。实到 " +
                        occurrences(code, forbidden) + " 处",
                    0,
                    occurrences(code, forbidden),
                )
            }
        }
        val kernel = blankCommentsKeepingLiterals(readMainSource(KERNEL))
        assertEquals(
            "内核里没有任何一枚判据以 Boolean 收尾（`): Boolean` 必须 0 处）：两问（是不是教务 origin / " +
                "是不是北航会话域）折成一枚 Boolean，就是把「会话没了」与「页面还没就绪」念成同一句话：",
            0,
            occurrences(kernel, "): Boolean"),
        )
        assertEquals(
            "五枚教务档 ＋ 三枚会话档逐枚恰 1 处（少一枚 = 那一档被并掉了，多一枚 = 长出第二本档）：",
            listOf(1, 1, 1, 1, 1, 1, 1, 1),
            VERDICT_CASES.map { occurrences(kernel, "data object $it") },
        )
        assertEquals(
            "两枚分档各定义 1 枚（`sealed interface EduDocument` / `sealed interface SessionDomain`）——" +
                "把第二枚并进第一枚就是「一个 Boolean 答两个问题」的写法：" +
                "「sso 那一格」在单测里钉的是语义，这一格钉的是它没法被并档的形状：",
            listOf(1, 1),
            listOf(
                occurrences(kernel, "sealed interface EduDocument"),
                occurrences(kernel, "sealed interface SessionDomain"),
            ),
        )
    }

    // ─────────────── ④ 三枚调用点的原文（逐字节） ───────────────

    /**
     * 钉法 = **逐字节**。这一格不钉"某一形"，只钉这六句原文各在 —— 换成等价但不同形
     * （`indexOf(prefix) == 0`、或者在 `hasSession()` 外面补一句设备判断）都会红。
     */
    @Test
    fun `三枚调用点的原文`() {
        val session = blankCommentsKeepingLiterals(readMainSource(WEB_SESSION))
        val login = blankCommentsKeepingLiterals(readMainSource(LOGIN_SCREEN))
        assertEquals(
            "`hasSession()` 的原文逐字节 1 处（它只做「问一次内核」这一件事，外面没有补判设备的位置）：",
            1,
            occurrences(session, HAS_SESSION_BODY),
        )
        assertEquals(
            "恢复链里 `when (eduDocumentVerdict(url)) {` 1 处（分档就是分档，不是 if 的两不靠）：",
            1,
            occurrences(session, RESTORE_WHEN_HEAD),
        )
        assertEquals(
            "恢复链认到的那一档原文 1 处（命中即 `restoreInFlight = false` ＋ `retain(...)`）：",
            1,
            occurrences(session, EDU_BRANCH),
        )
        assertEquals(
            "登录页就绪闸门那一枚 `if (` 原文 1 处（改前它少一枚尾斜杠，#515 的病体就在这一行）：",
            1,
            occurrences(login, LOGIN_GATE_BODY),
        )
        assertEquals(
            "「重定向到非北航域」与「会话已失效，需要重新登录」两枚句子各 1 处（并成一句就是又把两问折成一档）：",
            listOf(1, 1),
            listOf(occurrences(session, NON_BUAA_COPY), occurrences(session, STALE_SESSION_COPY)),
        )
        assertEquals(
            "「教务系统页面尚未就绪，请稍后重试」那句报错在登录页 1 处（改前那句谎话的对面就是它）：",
            1,
            occurrences(login, NOT_READY_COPY),
        )
    }

    // ─────────────── ⑤ 全仓一枚 authority 刀 ───────────────

    /**
     * 钉法 = **数出现次数**（全 main 树）。四刀的定义只许住在 `domain/url/UrlShape.kt`，各 1 枚。
     *
     * `data/import` 侧（IClassQrParser / IClassSignUrl / ScanReject）以**全限定名**引用它们而不是
     * import 行 —— 那是 `ScanTargetParserTest.解析层零import零android零取钟` 钉着的凭据（那三枚文件的
     * import 行数合同是 0）。这一格守的是反方向：不许在 layer 下面再刻一把同形的刀。
     */
    @Test
    fun `全仓只有一枚authority刀`() {
        val definitions = mutableListOf<String>()
        for (file in mainSources()) {
            val code = blankCommentsKeepingLiterals(file.readText())
            for (knife in KNIFE_DEFS) {
                val n = occurrences(code, knife)
                if (n > 0) definitions += "${rel(file)} ⇒ $knife × $n"
            }
        }
        assertEquals(
            "四刀在 `app/src/main` 的定义总数必须 4 枚（一刀一枚）且全部落在 `$KNIFE`。" +
                "对不上 = 有人又刻了一把，或者把该合的那一把拆开了：\n" + definitions.joinToString("\n"),
            listOf(
                "$KNIFE ⇒ internal fun splitAuthority( × 1",
                "$KNIFE ⇒ internal fun urlAuthority( × 1",
                "$KNIFE ⇒ internal fun urlPathOf( × 1",
                "$KNIFE ⇒ internal fun urlSchemeOf( × 1",
            ).sorted(),
            definitions.sorted(),
        )
    }

    // ─────────────── ⑥ origin 字面量只有一枚真源 ───────────────

    /**
     * 钉法 = **数出现次数**（带引号的整串 0 枚）＋ **逐文件枚数**（教务 host 字面量的分布册子）。
     *
     * `BYXT_ORIGIN` 从此是内核 `ORIGIN` 的别名，而 origin 的整串字面量在 main 里不再出现
     * （内核里由 `"https://$HOST"` 拼出）。逐文件分布同时是第①格的备份：
     * 枚数只能等于这本账，多一枚 = 有人新抄了一枚字面量，少一枚 = 该留的那枚被搬走了。
     */
    @Test
    fun `origin 字面量只有一枚真源`() {
        val quoted = mutableListOf<String>()
        val spread = mutableListOf<String>()
        for (file in mainSources()) {
            val code = blankCommentsKeepingLiterals(file.readText())
            val n = occurrences(code, QUOTED_ORIGIN)
            if (n > 0) quoted += "${rel(file)} ⇒ $QUOTED_ORIGIN × $n"
            val hosts = occurrences(code, EDU_HOST_LITERAL)
            if (hosts > 0) spread += "${rel(file)} = $hosts"
        }
        assertEquals(
            "带引号的 origin 整串「$QUOTED_ORIGIN」在 `app/src/main` 必须 0 处（内核拼它，别处只派生）。" +
                "实到：\n" + quoted.joinToString("\n"),
            0,
            quoted.size,
        )
        assertEquals(
            "教务 host 字面量在 main 里的逐文件枚数就是这本账（内核 HOST 1 ＋ 会话 Cookie 域 1 ＋ " +
                "登录页的 sso service 与 homeapp 路由 2）；对不上 = 有人多抄了一枚：" +
                "实到 " + spread.joinToString(" / "),
            listOf("$KERNEL = 1", "$LOGIN_SCREEN = 2", "$WEB_SESSION = 1").sorted(),
            spread.sorted(),
        )
        assertEquals(
            "`$WEB_SESSION` 里 `BYXT_ORIGIN` 的定义原文逐字节 1 处（别名，不是第二枚真源）：",
            1,
            occurrences(blankCommentsKeepingLiterals(readMainSource(WEB_SESSION)), ORIGIN_ALIAS),
        )
    }

    // ─────────────── ⑦ 那两把旧尺子拆干净 ───────────────

    @Test
    fun `旧的两把尺子拆干净`() {
        val session = blankCommentsKeepingLiterals(readMainSource(WEB_SESSION))
        for (needle in OLD_RULERS) {
            assertEquals(
                "`$WEB_SESSION` 里「$needle」必须 0 处 —— 那是被内核接管的旧尺子（URI.host 那把 ＋ " +
                    "endsWith 后缀那把）。留着它就是留一份与内核并存的第二本档：" +
                    "实到 " + occurrences(session, needle) + " 处",
                0,
                occurrences(session, needle),
            )
        }
        assertFalse(
            "会话域不许在这颗文件里自己 `endsWith(` 一段域（那是「含任意子域」这一问的答案，" +
                "与「是不是教务」两码事，只能问内核）：",
            session.contains("endsWith("),
        )
    }

    // ---- 判据本体（读形状的探测器） ----

    /**
     * 「同一行里既做前缀/后缀/包含比较、又拿教务 origin 本体当针」就是本守卫要拦的那一形。
     *
     * 针只认 origin 本体：`BYXT_ORIGIN`（可带 `$`）、`EduWebOrigin.ORIGIN`、带引号的整串
     * `"https://byxt.buaa.edu.cn"` 或它 ＋一枚尾斜杠。**不**认 `"https://byxt.buaa.edu.cn/jwapp…"`
     * 那种带路由的字面量 —— 那一枚问的是第三个问题（是不是教务首页那条路由，`maybeCaptureSession`），
     * 本卡不动它，改由第⑥格的逐文件枚数盯住（多抄一枚就红）。
     */
    private fun barePrefixComparison(line: String): Boolean {
        val compares = line.contains("startsWith(") || line.contains("endsWith(") || line.contains("contains(")
        if (!compares) return false
        val pinnedBySymbol = line.contains("BYXT_ORIGIN") || line.contains("EduWebOrigin.ORIGIN")
        val pinnedByLiteral = line.contains(QUOTED_ORIGIN_SLASH) || line.contains(QUOTED_ORIGIN)
        return pinnedBySymbol || pinnedByLiteral
    }

    // ---- 源码核对小工具（与同族守卫一个刀法：读不到就抛，静默跳过等于没有守卫）----

    private fun rel(file: File): String = file.relativeTo(findMainJavaDir()).path.replace('\\', '/')

    private fun mainSources(): List<File> {
        val root = findMainJavaDir()
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("${root.path} 下一个 .kt 都没有，扫描路径不对", files.isNotEmpty())
        return files.sortedBy { it.path }
    }

    private fun readMainSource(relativeFromMainJava: String): String {
        val file = File(findMainJavaDir(), relativeFromMainJava)
        assertTrue("找不到 ${file.path}：文件挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText()
    }

    /** 抹注释 ＋ 把 import 行整行抹空（保持行数与列位）：数「问了几次判据」用这一份 */
    private fun callCode(relativeFromMainJava: String): String =
        dropImports(blankCommentsKeepingLiterals(readMainSource(relativeFromMainJava)))

    private fun mainCalls(needle: String): Int = mainSources()
        .sumOf { occurrences(dropImports(blankCommentsKeepingLiterals(it.readText())), needle) }

    private fun dropImports(code: String): String = code.lines()
        .joinToString("\n") { if (it.trimStart().startsWith("import ")) " ".repeat(it.length) else it }

    private fun findMainJavaDir(): File {
        var dir: File? = File("").absoluteFile
        repeat(6) {
            val hit = listOf("src/main/java", "app/src/main/java")
                .map { File(dir, it) }
                .firstOrNull { it.isDirectory }
            if (hit != null) return hit
            dir = dir?.parentFile
        }
        throw IllegalStateException("找不到 app/src/main/java：当前目录 ${File("").absolutePath}")
    }

    private fun occurrences(haystack: String, needle: String): Int {
        var from = 0
        var n = 0
        while (true) {
            val at = haystack.indexOf(needle, from)
            if (at < 0) return n
            n++
            from = at + needle.length
        }
    }

    /** **只抹注释、保留字符串字面量内容**，长度与换行位置不变（与同族守卫同一把抹刀）。 */
    private fun blankCommentsKeepingLiterals(src: String): String {
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
        const val KERNEL = "com/buaa/schedule/domain/url/EduWebOrigin.kt"
        const val KNIFE = "com/buaa/schedule/domain/url/UrlShape.kt"
        const val WEB_SESSION = "com/buaa/schedule/data/import/BuaaWebSession.kt"
        const val LOGIN_SCREEN = "com/buaa/schedule/ui/importing/BuaaLoginScreen.kt"

        const val QUOTED_ORIGIN = "\"https://byxt.buaa.edu.cn\""
        const val QUOTED_ORIGIN_SLASH = "\"https://byxt.buaa.edu.cn/\""
        const val EDU_HOST_LITERAL = "byxt.buaa.edu.cn"

        /**
         * 改前的三枚原文（⓪ 实测的字节形状）：探测器必须逐枚报出。
         * 针里那枚美元符号用 `${'$'}` 取（三引号里裸写 `$BYXT_ORIGIN` 会被 Kotlin 当成模板去解析符号，
         * 编译期就 unresolved —— 这一枚是本卡 ③ 趟实际踩到的雷，写在这儿免得下一枚卡再踩）。
         */
        val FORBIDDEN_SAMPLES = listOf(
            """fun hasSession(): Boolean = lastUrl?.startsWith("${'$'}BYXT_ORIGIN/") == true""",
            """if (url?.startsWith("${'$'}BYXT_ORIGIN/") == true) {""",
            """if (!web.url.orEmpty().startsWith(com.buaa.schedule.data.import.BuaaWebSession.BYXT_ORIGIN)) {""",
        )

        /** 三枚合法写法 ＋ 一枚超范围的写法（第三个问题）：探测器必须一枚都不报 */
        val LEGIT_SAMPLES = listOf(
            """private const val SESSION_ENTRY_URL = "${'$'}BYXT_ORIGIN/jwapp/sys/homeapp/index.do"""",
            "private val API_ORIGIN = BuaaWebSession.BYXT_ORIGIN",
            ORIGIN_ALIAS,
            """if (url?.startsWith("https://byxt.buaa.edu.cn/jwapp/sys/homeapp/") != true) return""",
        )

        const val HAS_SESSION_BODY =
            "fun hasSession(): Boolean = eduDocumentVerdict(lastUrl) == EduDocument.OnEduOrigin"
        const val RESTORE_WHEN_HEAD = "when (eduDocumentVerdict(url)) {"
        const val EDU_BRANCH = "EduDocument.OnEduOrigin -> {"
        const val LOGIN_GATE_BODY = "if (eduDocumentVerdict(web.url) != EduDocument.OnEduOrigin) {"

        const val ORIGIN_ALIAS =
            "const val BYXT_ORIGIN = com.buaa.schedule.domain.url.EduWebOrigin.ORIGIN"

        const val NON_BUAA_COPY = "重定向到非北航域"
        const val STALE_SESSION_COPY = "会话已失效，需要重新登录"
        const val NOT_READY_COPY = "教务系统页面尚未就绪，请稍后重试"

        val VERDICT_CASES = listOf(
            "OnEduOrigin : EduDocument",
            "SchemeDowngraded : EduDocument",
            "PortMismatch : EduDocument",
            "NotEduHost : EduDocument",
            "Unparseable : EduDocument",
            "AllowedBuaaDomain : SessionDomain",
            "ForeignDomain : SessionDomain",
            "Unparseable : SessionDomain",
        )

        val KNIFE_DEFS = listOf(
            "internal fun urlAuthority(",
            "internal fun urlSchemeOf(",
            "internal fun urlPathOf(",
            "internal fun splitAuthority(",
        )

        /** 被内核接管的旧尺子（改前 `:48` ＋ `:509-511`）：留在盘上就是与内核并存的第二本档 */
        val OLD_RULERS = listOf("SESSION_HOST_SUFFIX", "isAllowedSessionUrl", "java.net.URI", "URI(it)")

        val FORBIDDEN_IN_KERNEL = listOf(
            "import android", "import androidx", "System.currentTimeMillis", "SystemClock", "Date(",
            "Instant", "LocalDate.now", "TimeZone", "ZoneId", "Build.VERSION", "SDK_INT",
            "checkSelfPermission", "PackageManager", "ContentResolver", "java.net.URI", "Room",
        )
    }
}
