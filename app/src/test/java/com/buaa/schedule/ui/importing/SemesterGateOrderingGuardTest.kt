package com.buaa.schedule.ui.importing

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T196②：把首次教务导入那一路的**次序**钉成静态守卫 ——
 * `resolveBuaaSemesterStart(` 的调用点必须早于 `fetchSemesterCourses(` 的调用点。
 *
 * ## 病
 *
 * 改前的 `ui/importing/BuaaLoginScreen.kt`：拿到 `fetchSemesterInfo` 的结果之后**立刻**进
 * `fetchSemesterCourses`（逐周抓完，每周一到两次页面内 fetch），直到最外层 `when` 的 `else` 支里
 * 才判开学日。于是开学日取不到时，用户已经等完整轮逐周抓取，最后听到一句「已保留原课表未做改动，
 * 请稍后重试」—— 而失败档的 `totalWeeks` 是硬写的 20，那一轮空转正好二十周。
 * 对照本仓另一路：静默刷新 `refreshSchedule` 的次序本来就是对的（拿信息 → 立刻判 → 才逐周抓）。
 * T180 之后这一条链的下游是**闸门不是兜底**，所以「先判再抓」不是优化而是省钱的那一手。
 *
 * ## 钉的六层（纯源码核对：JVM、无 Robolectric、无设备、不读时钟）
 *
 * 1. [两枚调用点各恰好一处 且闸门排在抓取之前] 按 index 比（本仓「配对先后」那一族已有的钉法），
 *    枚数钉死是 index 比较**有意义**的前提 —— 两枚调用点各多一处的话「谁在前」就答不出是哪一对。
 * 2. [闸门那一支一行课表都不抓] 取 `is BuaaSemesterStart.Undetermined ->` 那一段体：
 *    `fetchSemesterCourses(` 必须 0 处 —— 这才是本格的意义，次序对了而失败支里还偷偷抓一轮，
 *    钱照旧白花。同一支里 retain 一枚、`fetchState = null` 一枚、报错一枚、`onBack()` 一枚逐处数。
 * 3. [贵的两枚排在抓取之后] `outcome.failedWeeks` / `outcome.courses.isEmpty()` 两枚判据的 index
 *    必须**晚于** `fetchSemesterCourses(`，且三句用户可见措辞按 `ui/importing` **整个目录**逐字取
 *    （本卡只动次序不动文案；按目录取是因为④那一格把「原因 → 句子」的映射抽成这个目录下的纯函数，
 *    钉在单份文件上会跟着那次搬家假红）。
 * 4. [进度文案不许说谎] 「正在逐周获取课表（学期 …」那一句必须落在闸门**之后**（它今天说的是
 *    已经判过开学日、真的开始逐周抓了），抓取之前那一句是新的「正在获取学期信息（学期 …」。
 * 5. [姊妹路照旧] `BuaaWebSession.refreshSchedule` 盘上的次序仍是「信息 → 判 → 抓」，
 *    本卡的钉法与它同源（它一个字没动，见卡面 §3 不许碰）。
 * 6. [反套套逻辑] 探测器必须把**改前那一段**（先抓后判）判成「次序错」，把**刷新那一路的形状**
 *    判成「次序对」，还要把「两枚调用点只有一枚」那种退化如实报成「答不出」而不是「绿」。
 *    本仓口径：不许拿「grep 没匹配到」当「全绿」。
 *
 * 档位如实写明：本守卫钉的是**次序与形状**，不证明运行时行为 ——
 * 「开学日判不出时页面内 fetch 真的零次」「抖一次真能救回来」都归编排者装机（本卡零 adb）。
 */
class SemesterGateOrderingGuardTest {

    // ─────────────── ① 两枚调用点各一枚 闸门排在抓取之前 ───────────────

    @Test
    fun `首次导入那一路 开学日闸门排在逐周抓取之前`() {
        val code = blankCommentsKeepingLiterals(source(LOGIN_SCREEN))
        val gates = indexOfAll(code, GATE_CALL)
        val fetches = indexOfAll(code, COURSE_FETCH_CALL)

        assertEquals(
            "`$LOGIN_NAME` 里 `$GATE_CALL` 的调用点恰好 1 处（实到 " + gates.size + " 处，" +
                lineHints(code, gates) + "）：枚数钉死是「谁在前」这一判**有意义**的前提 —— " +
                "长出第二处闸门的话，下面那格比的是第一枚与唯一那枚抓取，结论就与代码无关了",
            1,
            gates.size,
        )
        assertEquals(
            "`$LOGIN_NAME` 里 `$COURSE_FETCH_CALL` 的调用点恰好 1 处（实到 " + fetches.size + " 处，" +
                lineHints(code, fetches) + "）：同上。这一枚是整条链里最贵的一手（每周一到两次页面内 fetch）",
            1,
            fetches.size,
        )
        assertTrue(
            "次序判据：`$GATE_CALL` 落在 index=" + gates.first() + "（L" + lineOf(code, gates.first()) +
                "），而 `$COURSE_FETCH_CALL` 落在 index=" + fetches.first() + "（L" +
                lineOf(code, fetches.first()) + "）—— 闸门必须**早于**逐周抓取。" +
                "红了就是有人把闸门搬回抓取之后（T196② 的病复发：开学日取不到时用户已经等完二十周空转），" +
                "或是有人把 `fetchSemesterInfo` 之后那一句「正在获取学期信息」换成抓取时才说的话",
            gates.first() < fetches.first(),
        )
        // `fetchSemesterInfo` 是这两枚的共同上游：它必须仍在闸门之前，否则「先判」判的是没抓过的信息
        val infos = indexOfAll(code, INFO_FETCH_CALL)
        assertEquals("`$INFO_FETCH_CALL` 在 `$LOGIN_NAME` 恰好 1 处（实到 " + infos.size + " 处）", 1, infos.size)
        assertTrue(
            "`fetchSemesterInfo(` 必须早于闸门（实到 L" + lineOf(code, infos.first()) + " vs L" +
                lineOf(code, gates.first()) + "）：闸门吃的是它交出的那枚串，" +
                "把它挪到闸门之后就等于拿没抓到的信息去判开学日",
            infos.first() < gates.first(),
        )
    }

    // ─────────────── ② 闸门失败那一支一行课表都不抓 ───────────────

    @Test
    fun `闸门认不出来那一支不逐周抓取 但保住登录 WebView 并把状态收干净`() {
        val code = blankCommentsKeepingLiterals(source(LOGIN_SCREEN))
        val hits = indexOfAll(code, UNDETERMINED_BRANCH)
        assertEquals("`$UNDETERMINED_BRANCH` 恰好一处（本层取块的宿主）：实到 " + hits.size + " 处", 1, hits.size)
        val branch = braceBodyFrom(code, code.indexOf('{', hits.first() + UNDETERMINED_BRANCH.length))

        assertEquals(
            "认不出来那一支里 `$COURSE_FETCH_CALL` 必须 **0 处**（实到 " + occurrences(branch, COURSE_FETCH_CALL) +
                " 处）：这一格才是本卡的意义 —— 次序搬到前面而失败支里还留着一手逐周抓取，" +
                "钱照旧白花、用户照旧等完整轮才听到那句「已保留原课表未做改动」。" +
                "（`fetchSemesterCourses` 全文件仍恰好一处，见第 ① 层，所以这一支 0 处 = 那一枚长在成功支里）",
            0,
            occurrences(branch, COURSE_FETCH_CALL),
        )
        assertEquals(
            "那一支里 `fetch(` 也必须 0 处（实到 " + occurrences(branch, "fetch(") +
                " 处）：连一次页面内请求都不许多发",
            0,
            occurrences(branch, "fetch("),
        )
        // retain 那一行的位置：卡面 §2 点名要复核的一格。提前返回不许把这条链拆断。
        assertEquals(
            "那一支里 `$RETAIN_CALL` 恰好一处（实到 " + occurrences(branch, RETAIN_CALL) +
                " 处）：retain 原本只长在逐周抓取**之后**，闸门搬到抓取之前以后，这一支要是照旧不掉 retain，" +
                "用户返回时 AndroidView.onRelease 会当场 destroy 那枚 WebView —— 已登录的 byxt 会话跟着丢，" +
                "下次导入得重走一遍 SSO（形状与这一趟另两枚提前返回一致：retain → 清状态 → 报错 → onBack）",
            1,
            occurrences(branch, RETAIN_CALL),
        )
        assertEquals(
            "那一支里 `fetchState = null` 恰好一处（实到 " + occurrences(branch, "fetchState = null") +
                " 处）：清了才画得出底部那张状态卡；只报错不收进度的话，" +
                "返回之后状态栏还挂着「正在获取学期信息…」那一行没在做的话",
            1,
            occurrences(branch, "fetchState = null"),
        )
        assertEquals(
            "那一支里 `showMessage(` 恰好一处且带 `isError = true`（实到 " +
                occurrences(branch, "showMessage(") + " / " + occurrences(branch, "isError = true") + " 处）",
            1,
            occurrences(branch, "showMessage("),
        )
        assertEquals(
            "那一支里 `onBack(` 恰好一处（实到 " + occurrences(branch, "onBack(") + " 处）",
            1,
            occurrences(branch, "onBack("),
        )
        for (needle in listOf("Semester(", "previewBuaaCourses(", "importPrepared = true", "onImportPrepared(")) {
            assertEquals(
                "认不出来那一支里 `$needle` 必须 0 处（实到 " + occurrences(branch, needle) +
                    " 处）：这一支的语义是**不动学期**（T180 那一族，`BuaaSemesterStartWiringGuardTest` 同格互为备份）",
                0,
                occurrences(branch, needle),
            )
        }
        assertTrue(
            "那一支内的次序必须是 retain → 清状态 → 报错 → 返回（实到 retain=" + branch.indexOf(RETAIN_CALL) +
                "、fetchState=" + branch.indexOf("fetchState = null") + "、showMessage=" +
                branch.indexOf("showMessage(") + "、onBack=" + branch.indexOf("onBack(") +
                "）：retain 掉在 onBack 之后就是先把页面弹走再试图保住那枚正在被销毁的 WebView",
            branch.indexOf(RETAIN_CALL) < branch.indexOf("showMessage(") &&
                branch.indexOf("fetchState = null") < branch.indexOf("showMessage(") &&
                branch.indexOf("showMessage(") < branch.indexOf("onBack("),
        )
    }

    // ─────────────── ③ 贵的两枚排在抓取之后 措辞逐字未改 ───────────────

    @Test
    fun `贵的两枚判据排在抓取之后 两句措辞一个字没改`() {
        val code = blankCommentsKeepingLiterals(source(LOGIN_SCREEN))
        val fetchAt = indexOfAll(code, COURSE_FETCH_CALL).first()

        val arms = listOf(FAILED_WEEKS_ARM, EMPTY_COURSES_ARM)
        for (arm in arms) {
            val hits = indexOfAll(code, arm)
            assertEquals(
                "`$arm` 在 `$LOGIN_NAME` 恰好一处（实到 " + hits.size + " 处，" + lineHints(code, hits) +
                    "）：这两枚是**贵的**判据 —— 它们要有逐周抓取的结果才谈得上",
                1,
                hits.size,
            )
            assertTrue(
                "`$arm` 必须晚于 `$COURSE_FETCH_CALL`（L" + lineOf(code, hits.first()) + " vs L" +
                    lineOf(code, fetchAt) + "）：卡面 §2 把两枚失败消息的优先级定死了 —— " +
                    "先判开学日（便宜的，零页面内 fetch），再判 failedWeeks / courses.isEmpty()（贵的）。" +
                    "把这两枚任一搬到闸门之前，就是拿没抓过的结果去判「有没有失败周」",
                hits.first() > fetchAt,
            )
        }
        // 措辞不许被「顺手」改掉：本卡只动次序。文案按**目录**取而不按 `$LOGIN_NAME` 取，是因为
        // ④那一格把「原因 → 用户句子」的映射抽成 ui/importing/ 下的纯函数（照 ImportCompletionCopy 的先例），
        // 那一刀之后那句仍然该在这个目录里、且只该出现一次 —— 钉在单份文件上会跟着那次搬家假红。
        val byFile = readImportingDir()
        for (wording in listOf(
            FAILED_WEEKS_MESSAGE to 1,
            EMPTY_COURSES_MESSAGE to 1,
            GATE_MESSAGE_HEAD to 1,
            GATE_MESSAGE_TAIL to 2,
        )) {
            val found = occurrencesOfAll(byFile, wording.first)
            assertEquals(
                "用户可见句子里「${wording.first}」在 ui/importing 整个目录里恰好 " + wording.second +
                    " 处（实到 " + found + " 处，册子 " + ledger(byFile, wording.first) +
                    "）：卡面 §2 写明「**不改**那两句的措辞」，②只改次序；文案分档那一刀在④" +
                    "（原因表：多枚 arm 各一句，但那两枚贵的判据的措辞不在④的分档范围里）。" +
                    "TAIL 那一句是 2 处 = 闸门那一句 + 失败周那一句共用同一句「请稍后重试」的收尾，" +
                    "长出第三处就是有人在复刻这一族文案",
                wording.second,
                found,
            )
        }
    }

    // ─────────────── ④ 进度文案不许说谎 ───────────────

    @Test
    fun `逐周那句进度文案排在闸门之后`() {
        val code = blankCommentsKeepingLiterals(source(LOGIN_SCREEN))
        val gates = indexOfAll(code, GATE_CALL)
        val before = indexOfAll(code, INFO_PROGRESS)
        val after = indexOfAll(code, WEEKLY_PROGRESS)

        assertEquals("`$INFO_PROGRESS` 恰好一处（实到 " + before.size + " 处）", 1, before.size)
        assertEquals("`$WEEKLY_PROGRESS` 恰好一处（实到 " + after.size + " 处）", 1, after.size)
        assertTrue(
            "「正在获取学期信息（学期 …」必须早于闸门（L" + lineOf(code, before.first()) + " vs L" +
                lineOf(code, gates.first()) + "）：它描述的是 `fetchSemesterInfo` 那一趟",
            before.first() < gates.first(),
        )
        assertTrue(
            "「正在逐周获取课表（学期 …」必须**晚于**闸门（实到 L" + lineOf(code, after.first()) +
                "，闸门在 L" + lineOf(code, gates.first()) + "）：改前它写在 `fetchSemesterInfo` 之前，" +
                "于是开学日判不出时用户整轮看到的都是「正在逐周获取课表」而一行课表都没抓过 —— " +
                "次序改过来之后这句必须跟着搬进成功支，否则文案在说谎",
            after.first() > gates.first(),
        )
    }

    // ─────────────── ⑤ 姊妹路（静默刷新）盘上次序照旧 ───────────────

    @Test
    fun `静默刷新那一路盘上仍是 信息 到 判 到 逐周抓`() {
        val code = blankCommentsKeepingLiterals(source(WEB_SESSION))

        assertEquals("`$WEB_SESSION_NAME` 里 `$COURSE_FETCH_CALL` 恰好一处（实到 " +
            occurrences(code, COURSE_FETCH_CALL) + " 处）", 1, occurrences(code, COURSE_FETCH_CALL))
        assertEquals(
            "`$WEB_SESSION_NAME` 里 `未取得学期第一周周一` 那一句恰好一处（实到 " +
                occurrences(code, REFUSE_MESSAGE) + " 处）：它就是那一路的闸门（`?: existingStartDate ?: return " +
                "Result.failure(...)`），本卡的次序照它改的",
            1,
            occurrences(code, REFUSE_MESSAGE),
        )
        assertTrue(
            "次序：`fetchSemesterInfo(` → 闸门句子 → `fetchSemesterCourses(`（实到 L" +
                lineOf(code, code.indexOf(INFO_FETCH_CALL)) + " / L" +
                lineOf(code, code.indexOf(REFUSE_MESSAGE)) + " / L" +
                lineOf(code, code.indexOf(COURSE_FETCH_CALL)) + "）。本卡红线是不碰 `BuaaWebSession.kt` —— " +
                "这一格红了说明那一路的次序被改过，两路不再同源，首次导入这一路的理由要重读",
            precedes(code, INFO_FETCH_CALL, REFUSE_MESSAGE) == true &&
                precedes(code, REFUSE_MESSAGE, COURSE_FETCH_CALL) == true,
        )
    }

    // ─────────────── ⑥ 反套套逻辑：探测器自己得会做事 ───────────────

    @Test
    fun `探测器认得改前那一形 也认得刷新那一路 缺调用点时答不出而不是报绿`() {
        // 改前的原文：拿到学期信息之后**先**逐周抓，判开学日排在最外层 when 的 else 支里
        val oldShape = """
            fetchState = "正在逐周获取课表（学期 ${'$'}selectedTerm）..."
            val info = fetcher.fetchSemesterInfo(web, selectedTerm)
            val totalWeeks = info.totalWeeks
            val outcome = fetcher.fetchSemesterCourses(
                webView = web,
                termCode = selectedTerm,
                totalWeeks = totalWeeks,
            )
            when {
                outcome.failedWeeks.isNotEmpty() -> { viewModel.showMessage("抓取未完成", isError = true) }
                else -> {
                    when (val start = resolveBuaaSemesterStart(info.firstWeekMonday)) {
                        is BuaaSemesterStart.Undetermined -> { viewModel.showMessage("没有可识别的开学日期", isError = true) }
                        is BuaaSemesterStart.Resolved -> { viewModel.previewBuaaCourses(semester, parsed.courses, parseWarnings) }
                    }
                }
            }
        """.trimIndent()
        assertEquals(
            "探测器必须把**改前那一段**（先抓后判）判成「次序错」：拿到的形状是闸门 index 晚于抓取 index。" +
                "实到 " + precedes(oldShape, GATE_CALL, COURSE_FETCH_CALL) +
                " —— 判成 true 说明探测器空转，第 ① 层那句「闸门在前」就是假的绿",
            false,
            precedes(oldShape, GATE_CALL, COURSE_FETCH_CALL),
        )

        // 刷新那一路的形状（信息 → 判 → 才逐周抓）：同一枚探测器必须判它「次序对」
        val refreshShape = """
            val info = fetcher.fetchSemesterInfo(web, selectedTerm)
            val startDate = resolveBuaaSemesterStart(info.firstWeekMonday)
                as? BuaaSemesterStart.Resolved ?: existingStartDate ?: return Result.failure(IllegalStateException("未取得学期第一周周一，未改动课表"))
            val outcome = fetcher.fetchSemesterCourses(
                webView = web, termCode = selectedTerm, totalWeeks = info.totalWeeks, onProgress = onProgress,
            )
        """.trimIndent()
        assertEquals(
            "探测器必须把**刷新那一路**（`BuaaWebSession` 的次序，本卡照它改的）判成「次序对」。实到 " +
                precedes(refreshShape, GATE_CALL, COURSE_FETCH_CALL) +
                " —— 判成 false 说明它把「正确的次序」也一起杀了，那第 ① 层红的时候没人知道该信谁",
            true,
            precedes(refreshShape, GATE_CALL, COURSE_FETCH_CALL),
        )

        // 缺一枚调用点：答不出（null），不许默认成「次序对」
        val noGate = "val outcome = fetcher.fetchSemesterCourses(webView = web, termCode = selectedTerm)"
        assertEquals(
            "闸门被整段摘掉时探测器必须回 null（实到 " + precedes(noGate, GATE_CALL, COURSE_FETCH_CALL) +
                "）：「没有第二枚调用点」不等于「次序对」—— 摘掉闸门正是 T180 那一族最坏的一扭，" +
                "把 null 折成 true 的话第 ① 层会安静地绿下去",
            null,
            precedes(noGate, GATE_CALL, COURSE_FETCH_CALL),
        )
        val noFetch = "when (val start = resolveBuaaSemesterStart(info.firstWeekMonday)) { else -> onBack() }"
        assertEquals(
            "逐周抓取被整段摘掉时同样必须回 null（实到 " + precedes(noFetch, GATE_CALL, COURSE_FETCH_CALL) + "）",
            null,
            precedes(noFetch, GATE_CALL, COURSE_FETCH_CALL),
        )

        // 盘上的两枚针都还在（否则上面的内联样例就自证为空）
        val code = blankCommentsKeepingLiterals(source(LOGIN_SCREEN))
        assertTrue(
            "盘上 `$GATE_CALL` 与 `$COURSE_FETCH_CALL` 都得至少各一枚（实到 " + occurrences(code, GATE_CALL) +
                " / " + occurrences(code, COURSE_FETCH_CALL) + " 处）：探测器是在盘上那一对比 index，" +
                "不是在全空的文件里比 index",
            occurrences(code, GATE_CALL) >= 1 && occurrences(code, COURSE_FETCH_CALL) >= 1,
        )
    }

    // ---------------- 探测器 ----------------

    /**
     * 比两枚针的**第一个落点**：[first] 早于 [second] 回 true，晚于回 false，
     * 任一枚缺席回 **null**（"答不出"与"次序对"是两件事，不许折成一枚）。
     *
     * 只比 index 不比行号：本仓这些守卫读的是抹注释后的原文（长度与换行不变），
     * 但两枚针可能落在同一行上，index 是唯一不会误判的那本账。
     */
    private fun precedes(code: String, first: String, second: String): Boolean? {
        val a = code.indexOf(first)
        val b = code.indexOf(second)
        if (a < 0 || b < 0) return null
        return a < b
    }

    // ---------------- 源码核对小工具（与仓内其它静态守卫同一口径）----------------

    private fun source(relativeFromMainJava: String): String {
        val file = File(findMainJavaDir(), relativeFromMainJava)
        assertTrue("找不到 ${file.path}：文件挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText().replace("\r\n", "\n")
    }

    /** 文件名 → 抹注释后的内容（`ui/importing` 整个目录；同名文件直接抛，避免建册子时读错份） */
    private fun readImportingDir(): Map<String, String> {
        val root = File(findMainJavaDir(), IMPORTING_DIR)
        assertTrue("找不到 ${root.path}：包挪过家的话这条守卫要跟着改路径", root.isDirectory)
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("${root.path} 下一个 .kt 都没有：路径不对，本层就是空的", files.isNotEmpty())
        val out = linkedMapOf<String, String>()
        files.forEach {
            check(!out.containsKey(it.name)) { "ui/importing 里有同名文件 ${it.name}，按文件名建册子会读错份" }
            out[it.name] = blankCommentsKeepingLiterals(it.readText().replace("\r\n", "\n"))
        }
        assertEquals("扫出来的文件数与建出来的册子条目数不等", files.size, out.size)
        return out
    }

    private fun occurrencesOfAll(byFile: Map<String, String>, needle: String): Int =
        byFile.values.sumOf { occurrences(it, needle) }

    /** 文件名册子：只列有命中的那份与它的枚数（失败消息里要能看见落在哪几份文件） */
    private fun ledger(byFile: Map<String, String>, needle: String): String =
        byFile.entries.toList().sortedBy { it.key }
            .filter { occurrences(it.value, needle) > 0 }
            .joinToString(prefix = "{", postfix = "}") { "${it.key}=${occurrences(it.value, needle)}" }

    /** 命中所在行的行号与原文：失败消息要能把人带到那一处，只报个数等于没有守卫 */
    private fun lineHints(source: String, positions: List<Int>): String {
        if (positions.isEmpty()) return ""
        return "\n" + positions.joinToString("") { at ->
            "  L" + lineOf(source, at) + ": " + lineAt(source, at).trim() + "\n"
        }
    }

    private fun lineOf(code: String, index: Int): Int = code.substring(0, index).count { it == '\n' } + 1

    private fun lineAt(code: String, index: Int): String {
        val lines = code.split('\n')
        return lines.getOrElse(lineOf(code, index) - 1) { "<越界>" }
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

    /** 从已知开括号位按花括号配平取出那一段体 */
    private fun braceBodyFrom(source: String, open: Int): String {
        check(source.getOrNull(open) == '{') {
            "锚点末尾不是花括号：写法变了，切不出一段体（实到「${source.getOrNull(open)}」）"
        }
        return source.substring(open + 1, (matchingBrace(source, open) ?: error("花括号没配平：从 $open 起")))
    }

    /** 花括号配平（跳过字符串与字符字面量）；不配平返回 null */
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

    /**
     * **只抹注释、保留字符串字面量内容**，长度与换行位置不变（报错消息里的行号因此可信）。
     * 本卡尤其需要抹注释：`BuaaLoginScreen` 闸门那一段 KDoc **逐字写着改前的形状**
     * （「抓完二十周再判开学日」那一段点着 `fetchSemesterCourses` 与 `resolveBuaaSemesterStart` 两枚符号），
     * 不抹的话探测器会把自己钉的守卫当成「次序错」。
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

    /** 单测的工作目录是模块目录还是仓库根不由这里决定：几种布局都试一遍，全落空就抛 */
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
        const val LOGIN_SCREEN = "com/buaa/schedule/ui/importing/BuaaLoginScreen.kt"
        const val LOGIN_NAME = "BuaaLoginScreen.kt"
        const val WEB_SESSION = "com/buaa/schedule/data/import/BuaaWebSession.kt"
        const val WEB_SESSION_NAME = "BuaaWebSession.kt"

        /** 文案措辞按整个 `ui/importing` 目录取（④那一格会把「原因 → 句子」抽进这个目录） */
        const val IMPORTING_DIR = "com/buaa/schedule/ui/importing"

        /** 两枚调用点：便宜的那枚（只吃已到手的串）与贵的那枚（每周一到两次页面内 fetch） */
        const val GATE_CALL = "resolveBuaaSemesterStart("
        const val COURSE_FETCH_CALL = "fetchSemesterCourses("
        const val INFO_FETCH_CALL = "fetchSemesterInfo("

        /** 闸门那一支的宿主与里面必须出现的四手 */
        const val UNDETERMINED_BRANCH = "is BuaaSemesterStart.Undetermined ->"
        const val RETAIN_CALL = "BuaaWebSession.retain(web, context.findActivity())"

        /** 贵的两枚判据（`SemesterFetchOutcome` 的两枚现成结论） */
        const val FAILED_WEEKS_ARM = "outcome.failedWeeks.isNotEmpty()"
        const val EMPTY_COURSES_ARM = "outcome.courses.isEmpty()"

        /** 三句用户可见措辞：本卡只动次序，不动文案 */
        const val FAILED_WEEKS_MESSAGE = "抓取未完成：第 "
        const val EMPTY_COURSES_MESSAGE = "教务系统未返回该学期课程（可能未选课）"
        const val GATE_MESSAGE_HEAD = "教务系统没有给出可识别的开学日期"
        const val GATE_MESSAGE_TAIL = "已保留原课表未做改动，请稍后重试"

        /** 两枚进度文案（`$selectedTerm` 那一截不进针，免得测试文件自己吃字符串模板） */
        const val INFO_PROGRESS = "正在获取学期信息（学期 "
        const val WEEKLY_PROGRESS = "正在逐周获取课表（学期 "

        /** 姊妹路（静默刷新）那一枚闸门的句子 */
        const val REFUSE_MESSAGE = "未取得学期第一周周一"
    }
}
