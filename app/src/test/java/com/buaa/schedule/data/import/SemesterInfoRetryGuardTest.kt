package com.buaa.schedule.data.import

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T196①：把「学期信息这一趟也有静默重试，而且吃的是课表那一圈**同一份**预算」钉成静态守卫
 * （读 `app/src/main` 工作树文本，纯 JVM、无 Robolectric、无设备、不读时钟）。
 *
 * ## 病
 *
 * 改前 `data/import/BuaaInPageFetcher.kt` 的 `fetchSemesterInfo` **只 fetch 一次**
 * （`val raw = fetch(...) ?: return SemesterInfo(20, null)`），而逐周课表那三趟有
 * `while (parsed == null && attempt < MAX_ATTEMPTS_PER_WEEK)` 那一圈。T180 之后学期信息这一条链的
 * 下游是**闸门不是兜底**（首次导入认不出开学日就报错返回、静默刷新认不出就 `Result.failure`），
 * 所以一次超时/抖动 = 整趟导入失败。同一枚「弱网抖动」的解释在课表那一侧拿到了重试额度、
 * 在这一侧没有 —— 本卡补的就是这一格不对称。
 *
 * ## 为什么是静态守卫而不是行为单测
 *
 * `fetchSemesterInfo` 是 suspend + WebView，`:app` 的 JVM 测试面只有 junit、**没有 Robolectric**，
 * 运行时造不出那枚 WebView；卡面 §4 明确**不许为此新开接口 seam**（那是扩大改动面）。
 * 于是这一格钉**可测的形状**：重试那一圈必须逐字引用 `MAX_ATTEMPTS_PER_WEEK` 与 `RETRY_DELAY_MS`
 * 两枚常量（枚数各 1 处、落在 `fetchSemesterInfo` 体内），并钉 `fetchSemesterInfo` 体内 `fetch(`
 * 的调用点恰好 1 处（一圈只有一个 fetch，防止长出第三本预算）。
 * 「教务抖一次真能救回来」那一档进**未验到**，归编排者装机。
 *
 * ## 钉的五层
 *
 * 1. [预算只能来自既有两枚常量] `fetchSemesterInfo` 体内 `MAX_ATTEMPTS_PER_WEEK` 恰好 1 处 +
 *    `RETRY_DELAY_MS` 恰好 1 处 + 退避那一圈逐字 `$RETRY_LOOP` 一处。写死成 `attempt < 1`、
 *    `Thread.sleep(500)`、或新开一枚 `MAX_ATTEMPTS_PER_SEMESTER_INFO` 都在这一格红。
 * 2. [一圈只有一个 fetch] 体内 `fetch(` 恰好 1 处 —— 长出第二枚就是第二本预算（一趟最多发两次
 *    页面内请求这句话就不成立了），而体内 `delay(` 也恰好 1 处（只有退避那一手会等多久）。
 * 3. [延时可取消] 体内 `ensureActive()` 恰好 1 处，且**落在 `delay(` 之前**：这一趟跑在导入协程里，
 *    取消之后不该再多等那 500ms。同时钉 `fetchSemesterCourses` 体内仍是 1 处（卡面写明那一圈的缺
 *    是另一格，本卡不许顺手修 —— 顺手修了这里会红一次，逼着重新判一次那两圈该不该同一口径）。
 * 4. [预算册子] 全文件 `MAX_ATTEMPTS_PER_WEEK` 恰好 3 处（声明 1 + 两圈各 1）、`RETRY_DELAY_MS`
 *    恰好 3 处（同上），全文件带 `ATTEMPTS` 的标识符恰好 3 处 ⇒ 「同族预算数只有这一枚」。
 * 5. [反套套逻辑] 探测器必须把**改前那一形**（只 fetch 一次、没有圈）判成「没有重试」，把课表那一圈
 *    原文判成「有重试且吃同一份预算」，把「圈在而常量被写死」那一形判成「预算不是那枚常量」，
 *    还要把「新开一枚常量」那一形当场报出来。本仓口径：不许拿「grep 没匹配到」当「全绿」。
 */
class SemesterInfoRetryGuardTest {

    // ─────────────── ① 预算来自既有两枚常量 ───────────────

    @Test
    fun `学期信息那一圈吃的是课表同一份预算 两枚常量各恰好一处`() {
        val body = fetchSemesterInfoBody()

        assertEquals(
            "`fetchSemesterInfo` 体内 `$BUDGET` 恰好 1 处（实到 " + occurrences(body, BUDGET) +
                " 处）。0 处的两种形状都要红：① 把预算**写死**成 `attempt < 1` / `attempt < 2` —— " +
                "那等于把这一趟退回「一次都不重试」或者偷偷加到第三圈，都不在卡面的账里；" +
                "② 新开一枚 `MAX_ATTEMPTS_PER_SEMESTER_INFO` 之类的常量 —— 卡面 §2 明令「不许新开一枚预算数」，" +
                "两本预算的意思是「学期信息这趟最多发几次页面内请求」得翻两处代码才答得出",
            1,
            occurrences(body, BUDGET),
        )
        assertEquals(
            "`fetchSemesterInfo` 体内 `$BACKOFF` 恰好 1 处（实到 " + occurrences(body, BACKOFF) +
                " 处）：退避间隔必须引那枚既有常量。写成字面量 `500`、或 `Thread.sleep(500)` 的话，" +
                "将来调 `RETRY_DELAY_MS` 只调得动课表那一圈、调不动这一圈 —— 同一枚「喘息窗口」分家",
            1,
            occurrences(body, BACKOFF),
        )
        assertEquals(
            "重试那一圈逐字 `$RETRY_LOOP` 恰好一处（实到 " + occurrences(body, RETRY_LOOP) +
                " 处）：卡面 §2 要的是「照课表那一圈的 `while (raw == null && attempt < MAX_ATTEMPTS_PER_WEEK)` " +
                "语义」，而课表那一圈的圈头写法就是这一形（那一圈的条件是 `parsed == null`，" +
                "这一圈吃的是响应文本所以是 `raw == null`）。换了条件写法（比如 do-while、" +
                "或把 `raw == null` 换成 `attempt < ...` 单条件）= 这一格先红、下面第 ② 层再判" +
                "「有没有真的只发一枚 fetch」",
            1,
            occurrences(body, RETRY_LOOP),
        )
    }

    // ─────────────── ② 一圈只有一个 fetch ───────────────

    @Test
    fun `学期信息体内只有一枚 fetch 一圈一次请求 不长第三本预算`() {
        val body = fetchSemesterInfoBody()

        assertEquals(
            "`fetchSemesterInfo` 体内 `fetch(` 的调用点恰好 1 处（实到 " + occurrences(body, "fetch(") +
                " 处）：重试圈的意义就在于「同一枚请求再发一次」，圈内长出第二枚 fetch" +
                "（例如「换一条接口试」「先探活再抓」）就有了第二本预算，" +
                "「这一趟最多两次页面内请求」这句话作废。" +
                "（`fetchSemesterInfo` 自己的名字不算命中：针是 `fetch(`）",
            1,
            occurrences(body, "fetch("),
        )
        assertEquals(
            "体内 `delay(` 恰好 1 处（实到 " + occurrences(body, "delay(") +
                " 处）：这一趟只该在两次尝试之间退避一次。多一枚 delay 就是多一段没人算过的等待，" +
                "而这一趟今天跑在**用户等导入**的那条协程上",
            1,
            occurrences(body, "delay("),
        )
        assertTrue(
            "`delay(` 之前必须先 `ensureActive()`（实到 delay=${backoffAt(body)}、ensureActive=" +
                "${cancelAt(body)}）：卡面 §2 点名「重试之间的 delay 必须可被取消」，" +
                "`delay` 自己虽是挂起点，协程已取消时那 500ms 也不该再排一遍",
            backoffAt(body) > cancelAt(body),
        )
    }

    // ─────────────── ③ 延时可取消 + 那一圈的缺不许顺手修 ───────────────

    @Test
    fun `退避前可取消 而课表那一圈的缺本卡不动`() {
        val body = fetchSemesterInfoBody()
        assertEquals(
            "`fetchSemesterInfo` 体内 `ensureActive()` 恰好 1 处（实到 " + occurrences(body, "ensureActive()") +
                " 处）：0 处就是照抄了课表那一圈的缺（卡面明令别照抄），2 处就是往同一圈里塞了第二次检查、" +
                "把「取消即停」写成了一句咒语",
            1,
            occurrences(body, "ensureActive()"),
        )
        val courses = functionBody(FETCHER, COURSES_HEAD)
        assertEquals(
            "`fetchSemesterCourses` 体内 `ensureActive()` 仍恰好 1 处（实到 " +
                occurrences(courses, "ensureActive()") + " 处）：卡面 §2 写明那一圈没有圈内的 " +
                "`ensureActive()` 是**另一格**的账，「你别照抄它的缺，也别顺手去修它」。" +
                "这一格红了说明有人顺手改了那一圈 —— 那要连那一圈的取消语义一起重判一次",
            1,
            occurrences(courses, "ensureActive()"),
        )
        assertEquals(
            "那一圈的圈头仍逐字 `$COURSES_LOOP` 一处（实到 " + occurrences(courses, COURSES_LOOP) +
                " 处）：本卡的预算口径全部照它抄，它挪了本卡的理由句就要重读",
            1,
            occurrences(courses, COURSES_LOOP),
        )
    }

    // ─────────────── ④ 预算册子：同族常量只有这一枚 ───────────────

    @Test
    fun `全文件预算常量册子钉死 新开一枚就红`() {
        val file = blankCommentsKeepingLiterals(source(FETCHER))

        for (needle in listOf(BUDGET, BACKOFF)) {
            assertEquals(
                "`$needle` 在 `$FETCHER_NAME` 恰好 3 处（声明 1 + 课表那一圈 1 + 学期信息这一圈 1；实到 " +
                    occurrences(file, needle) + " 处）。多出第四处 = 有人在别处又开了一圈预算，" +
                    "少一处 = 这一族被摘掉了一圈",
                3,
                occurrences(file, needle),
            )
        }
        assertEquals(
            "`$BUDGET` 的声明逐字 `$BUDGET_DECLARATION` 恰好一处（实到 " +
                occurrences(file, BUDGET_DECLARATION) + " 处）：预算**数值**归这一格管，" +
                "本卡不动它（改前是 2，卡面要的是复用而不是重新拍数）",
            1,
            occurrences(file, BUDGET_DECLARATION),
        )
        val attempts = Regex("ATTEMPTS").findAll(file).toList()
        assertEquals(
            "全文件带 `ATTEMPTS` 的标识符恰好 3 处（同一枚常量的声明与两次使用；实到 " + attempts.size +
                " 处）。新开一枚 `MAX_ATTEMPTS_PER_SEMESTER_INFO` / `SEMESTER_INFO_RETRIES` 之类的话，" +
                "这一格红在这里 —— 那是卡面 §2 禁的那一手，也是「两次页面内请求」这句话从此要翻两处代码才答得出的开始",
            3,
            attempts.size,
        )
    }

    // ─────────────── ⑤ 反套套逻辑：探测器自己得会做事 ───────────────

    @Test
    fun `探测器认得改前那一形 也认得课表那一圈 更拦得住新开预算`() {
        // 改前那一形：只 fetch 一次，一次超时就把学期信息判成「没有」
        val oldShape = """
            val raw = fetch(webView, "GET", "/jwapp/sys/homeapp/api/home/getTermWeeks.do?termCode=${'$'}safeTermCode", "")
                ?: return SemesterInfo(20, null)
        """.trimIndent()
        assertEquals(
            "探测器必须把**改前那一形**判成「没有重试」（`$BUDGET` 0 处）。实到 " + occurrences(oldShape, BUDGET) +
                " 处 —— 要是这里就数出 1 处，第 ① 层那句「实到 1 处」就成了探测器空转的证据",
            0,
            occurrences(oldShape, BUDGET),
        )
        assertEquals(
            "改前那一形体内 `fetch(` 恰好 1 处（实到 " + occurrences(oldShape, "fetch(") +
                " 处）：所以第 ② 层那格**单独**拦不住「没有圈」—— 它拦的是「圈里长出第二枚 fetch」。" +
                "两格分工写在这里，别让下一个人以为一枚就够",
            1,
            occurrences(oldShape, "fetch("),
        )

        // 课表那一圈的原文（本卡照抄的对象）：探测器必须认得出它是「有圈、吃同一份预算」
        val coursesLoop = """
            var parsed: BuaaScheduleResponse? = null
            var attempt = 0
            while (parsed == null && attempt < MAX_ATTEMPTS_PER_WEEK) {
                if (attempt > 0) delay(RETRY_DELAY_MS)
                attempt++
                parsed = fetch(webView, "POST", "/x.do", body)?.let { raw -> runCatching { parse(raw) }.getOrNull() }
            }
        """.trimIndent()
        assertEquals(
            "课表那一圈里 `$BUDGET` 恰好一处（实到 " + occurrences(coursesLoop, BUDGET) +
                " 处）：探测器读得懂那一圈的写法，第 ① 层那句「照它抄」才有对照物",
            1,
            occurrences(coursesLoop, BUDGET),
        )
        assertEquals(
            "课表那一圈体内 `fetch(` 恰好一处（实到 " + occurrences(coursesLoop, "fetch(") + " 处）",
            1,
            occurrences(coursesLoop, "fetch("),
        )

        // 变异一：圈在而预算写死成 1 次
        val hardcoded = """
            var raw: String? = null
            var attempt = 0
            while (raw == null && attempt < 1) {
                if (attempt > 0) delay(RETRY_DELAY_MS)
                attempt++
                raw = fetch(webView, "GET", "/x.do", "")
            }
        """.trimIndent()
        assertEquals(
            "把预算**写死**成 `attempt < 1` 那一形必须让第 ① 层红（`$BUDGET` 数到 0 处）。实到 " +
                occurrences(hardcoded, BUDGET) + " 处；而它的 `fetch(` 还是 1 处（实到 " +
                occurrences(hardcoded, "fetch(") + " 处）⇒ 第 ② 层拦不住这一手，" +
                "所以「枚数各 1 处」那一格不是冗余",
            0,
            occurrences(hardcoded, BUDGET),
        )

        // 变异二：新开一枚同族常量
        val newConstant = """
            private const val MAX_ATTEMPTS_PER_SEMESTER_INFO = 2
            while (raw == null && attempt < MAX_ATTEMPTS_PER_SEMESTER_INFO) {
                if (attempt > 0) delay(RETRY_DELAY_MS)
                attempt++
                raw = fetch(webView, "GET", "/x.do", "")
            }
        """.trimIndent()
        assertEquals(
            "新开一枚 `MAX_ATTEMPTS_PER_SEMESTER_INFO` 那一形里 `$BUDGET` 必须数到 0（实到 " +
                occurrences(newConstant, BUDGET) + " 处），而 `ATTEMPTS` 字样必须数到 2 处（实到 " +
                Regex("ATTEMPTS").findAll(newConstant).toList().size + " 处）⇒ 第 ① 层与第 ④ 层互为备份",
            2,
            Regex("ATTEMPTS").findAll(newConstant).toList().size,
        )

        // 变异三：圈里长出第二枚 fetch（第三本预算）
        val twoFetches = """
            var raw: String? = null
            var attempt = 0
            while (raw == null && attempt < MAX_ATTEMPTS_PER_WEEK) {
                if (attempt > 0) { ensureActive(); delay(RETRY_DELAY_MS) }
                attempt++
                raw = fetch(webView, "GET", "/x.do", "") ?: fetch(webView, "GET", "/y.do", "")
            }
        """.trimIndent()
        assertEquals(
            "圈内换第二条接口那一形必须让第 ② 层红（`fetch(` 实到 " + occurrences(twoFetches, "fetch(") +
                " 处，期望 1 处）—— 而它的 `$BUDGET` 还是 1 处（实到 " + occurrences(twoFetches, BUDGET) +
                " 处）⇒ 一枚常量判据拦不住它，两格都得在",
            2,
            occurrences(twoFetches, "fetch("),
        )

        // 盘上那两圈都在（否则上面的内联样例就自证为空）
        val file = blankCommentsKeepingLiterals(source(FETCHER))
        assertTrue(
            "盘上 `$BUDGET` 至少还有 3 处（实到 " + occurrences(file, BUDGET) +
                " 处）：探测器是在盘上那两圈里数枚数，不是在全空的文本里数",
            occurrences(file, BUDGET) >= 3,
        )
        assertNotNull(
            "`fetchSemesterInfo` 的函数体必须切得出来（第 ①②③ 层的宿主）",
            fetchSemesterInfoBody().ifEmpty { null },
        )
    }

    // ---------------- 取块 ----------------

    private fun fetchSemesterInfoBody(): String = functionBody(FETCHER, INFO_HEAD)

    /**
     * 从 `suspend fun xxx(` 那枚锚点起切出整段函数体。
     *
     * 开括号**不是**「锚点之后第一枚 `{`」：`fetchSemesterCourses` 的参数表里带默认值
     * `onProgress: (Int, Int) -> Unit = { _, _ -> }`，那枚花括号在参数表**之内**。
     * 所以先把参数表的圆括号配平到 `)`，再取其后第一枚 `{` —— 拿「第一枚花括号」切块的话
     * 切出来的是那枚默认值 lambda（9 个字符），下面那格 `MIN_BODY_CHARS` 就是为这一手准备的。
     */
    private fun functionBody(relative: String, anchor: String): String {
        val code = blankCommentsKeepingLiterals(source(relative))
        val hits = indexOfAll(code, anchor)
        assertEquals("`$anchor` 恰好一处（本层取块的宿主）：实到 " + hits.size + " 处", 1, hits.size)
        val head = hits.first()
        val paramsOpen = code.indexOf('(', head + anchor.length - 1)
        check(paramsOpen > head) { "锚点 `$anchor` 之后找不到参数表的开括号 —— 签名换写法了，本守卫要跟着改" }
        val paramsClose = matchingParen(code, paramsOpen)
            ?: error("参数表的圆括号没配平（从 $paramsOpen 起）：签名换写法了，本守卫要跟着改")
        val open = code.indexOf('{', paramsClose)
        check(open > paramsClose) {
            "锚点 `$anchor` 的参数表之后找不到函数体开括号 —— 它不再是块体函数（变成表达式函数体？），" +
                "本守卫要跟着改"
        }
        val body = braceBodyFrom(code, open)
        assertTrue(
            "切出来的 `$anchor` 函数体小于 " + MIN_BODY_CHARS + " 字符（实到 " + body.length +
                "）：块切歪了的话上面那些「体内枚数」全都在读空文本",
            body.length >= MIN_BODY_CHARS,
        )
        return body
    }

    /** `delay(` 与 `ensureActive()` 在体内的落点（切不出来就抛，不许默认成「次序对」） */
    private fun backoffAt(body: String): Int {
        val at = body.indexOf("delay(")
        check(at >= 0) { "体内没有 delay( —— 退避那一手被摘掉了，本层的次序判据要改成判「没有圈」" }
        return at
    }

    private fun cancelAt(body: String): Int {
        val at = body.indexOf("ensureActive()")
        check(at >= 0) { "体内没有 ensureActive() —— 第 ③ 层已经红；这里不折成 0，免得次序判据跟着假绿" }
        return at
    }

    // ---------------- 源码核对小工具（与仓内其它静态守卫同一口径）----------------

    private fun source(relativeFromMainJava: String): String {
        val file = File(findMainJavaDir(), relativeFromMainJava)
        assertTrue("找不到 ${file.path}：文件挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText().replace("\r\n", "\n")
    }

    private fun indexOfAll(haystack: String, needle: String): List<Int> {
        val hits = mutableListOf<Int>()
        if (needle.isEmpty()) return hits
        var from = 0
        while (true) {
            val at = haystack.indexOf(needle, from)
            if (at < 0) return hits
            hits += at
            from = at + needle.length
        }
    }

    private fun occurrences(haystack: String, needle: String): Int = indexOfAll(haystack, needle).size

    private fun braceBodyFrom(source: String, open: Int): String {
        check(source.getOrNull(open) == '{') {
            "锚点末尾不是花括号：写法变了，切不出一段体（实到「${source.getOrNull(open)}」）"
        }
        return source.substring(open + 1, (matchingBrace(source, open) ?: error("花括号没配平：从 $open 起")))
    }

    private fun matchingBrace(src: String, open: Int): Int? {
        var depth = 0
        var i = open
        var inString = false
        var escaped = false
        while (i < src.length) {
            val c = src[i]
            when {
                inString -> when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }

                c == '"' -> inString = true
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return null
    }

    /** 圆括号配平（跳过字符串字面量）：[open] 指向 `(`，返回与之配平的 `)` 的下标 */
    private fun matchingParen(src: String, open: Int): Int? {
        if (src.getOrNull(open) != '(') return null
        var depth = 0
        var i = open
        var inString = false
        var escaped = false
        while (i < src.length) {
            val c = src[i]
            when {
                inString -> when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }

                c == '"' -> inString = true
                c == '(' -> depth++
                c == ')' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return null
    }

    /**
     * **只抹注释、保留字符串字面量内容**，长度与换行位置不变。
     * 本卡尤其需要抹注释：`fetchSemesterInfo` 的 KDoc 里逐字写着两枚预算常量名
     * （`MAX_ATTEMPTS_PER_WEEK` 与 [RETRY_DELAY_MS] 都在句子里点名），不抹的话第 ④ 层那枚
     * 「ATTEMPTS 全文件恰好 3 处」会被自己的文档顶成 4 处。
     */
    private fun blankCommentsKeepingLiterals(source: String): String {
        val out = source.toCharArray()
        var i = 0
        while (i < out.size) {
            when {
                source.startsWith("//", i) -> {
                    val nl = source.indexOf('\n', i).let { if (it < 0) out.size else it }
                    for (k in i until nl) out[k] = ' '
                    i = nl
                }

                source.startsWith("/*", i) -> {
                    var depth = 1
                    var j = i + 2
                    while (j < out.size && depth > 0) {
                        when {
                            source.startsWith("/*", j) -> { depth++; j += 2 }
                            source.startsWith("*/", j) -> { depth--; j += 2 }
                            else -> j++
                        }
                    }
                    for (k in i until j.coerceAtMost(out.size)) if (out[k] != '\n') out[k] = ' '
                    i = j
                }

                source.startsWith("\"\"\"", i) -> {
                    i = source.indexOf("\"\"\"", i + 3).let { if (it < 0) out.size else it + 3 }
                }

                out[i] == '"' || out[i] == '\'' -> {
                    val quote = out[i]
                    var j = i + 1
                    while (j < out.size) {
                        when {
                            source[j] == '\\' -> j += 2
                            source[j] == quote -> { j++; break }
                            source[j] == '\n' -> break
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
        repeat(6) {
            val hit = listOf("src/main/java", "app/src/main/java")
                .map { File(dir, it) }
                .firstOrNull { it.isDirectory }
            if (hit != null) return hit
            dir = dir?.parentFile
        }
        throw IllegalStateException("找不到 app/src/main/java：当前目录 ${File("").absolutePath}")
    }

    private companion object {
        const val FETCHER = "com/buaa/schedule/data/import/BuaaInPageFetcher.kt"
        const val FETCHER_NAME = "BuaaInPageFetcher.kt"

        /** 两枚块宿主：本卡改的这一圈与照抄的那一圈 */
        const val INFO_HEAD = "suspend fun fetchSemesterInfo("
        const val COURSES_HEAD = "suspend fun fetchSemesterCourses("

        /** 既有预算两枚（卡面 §2：复用，不许新开） */
        const val BUDGET = "MAX_ATTEMPTS_PER_WEEK"
        const val BACKOFF = "RETRY_DELAY_MS"
        const val BUDGET_DECLARATION = "private const val MAX_ATTEMPTS_PER_WEEK = 2"

        /** 两枚圈头（本卡这一圈逐字照课表那一圈的写法，只把 `parsed` 换成 `raw`） */
        const val RETRY_LOOP = "while (raw == null && attempt < MAX_ATTEMPTS_PER_WEEK)"
        const val COURSES_LOOP = "while (parsed == null && attempt < MAX_ATTEMPTS_PER_WEEK)"

        /** 取块自检：函数体至少这么长，切歪了就是读空文本 */
        const val MIN_BODY_CHARS = 800
    }
}
