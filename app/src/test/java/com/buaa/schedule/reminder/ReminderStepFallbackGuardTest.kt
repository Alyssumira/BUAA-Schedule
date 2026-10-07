package com.buaa.schedule.reminder

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T227 #333 / #335 / #334 的形状守卫：广播与服务里那些**附加能力不许连坐核心能力**的站点，
 * 每一块都得有自己的可观测出口。纯 JVM 源码核对（无 Robolectric、无设备、不读时钟）。
 *
 * ## 病是一族三处
 *
 * - `#333` [ClassProgressReceiver] 的 ACTION_START：一整段跑在**一枚** `runCatching` 里，
 *   而 `startLiveWindow`（附加）排在 `ClassProgressDnd.enter`（核心：用户开了「上课自动勿扰」）
 *   之前 ⇒ 实况那手一抛（ensureChannels 的 binder、`builder.build()`、`notify()` 抛的
 *   SecurityException 之外的类型），`enter` 根本不被执行，这一节课永久不静音；
 *   而下课铃早已排在前面，`restore` 因「没记录」整程 no-op，自愈链也无从接手。
 * - `#335` [ReminderReceiver]：同一族第二处 —— `startLiveWindow` 在前、`notifyCourse` 在后、
 *   共用一枚兜底 ⇒ 某一节课**任何提醒都没有**（横幅、声音、锁屏全无），
 *   而那句 `Log.w` 写成「（实况/通知）」，把两种死法合成一句。
 * - `#334` [CourseFluidService] 的 `finishLiveAndReschedule`：`runCatching { restore(...) }`
 *   **没有 onFailure** ⇒ 挂了 30 分钟（看门狗那一档）零证据。同文件另三枚同形的裸吞
 *   （契约那一手只有 `.onSuccess`、onDestroy 摘前台通知、降级重排没递出去）一起收口成注入口。
 *
 * ## 为什么只能钉形状
 *
 * 抛的那一手要真 binder，本模块没有 Robolectric ⇒ "实际吃不吃得到这一格"归装机/真机。
 * 这里钉三件可静态核的事：
 * 1. 第 ① 层 —— **读形状**（取包住每一枚动作的**最内**那枚 `runCatching`，核它后面紧跟的链里
 *    有 `.onFailure`，且块里有一句 W 级以上的日志或注入的报告口）
 *    + **顺序**（`scheduleEnd` 仍排第一、`enter` 仍在实况之后、课前实况仍在 `rescheduleAll` 之前）
 *    + **数出现次数**（一枚兜底罩两枚动作那一形 = 0 处）；
 * 2. 第 ② 层 —— **逐字节**（四句结论文案两两不同，且每句都说清另一半照常）；
 * 3. 第 ③ 层 —— **数出现次数**（CourseFluidService 全文「没有结论出口的 runCatching」= 0 处）
 *    + **逐字节**（五枚顶层注入报告口：声明恰一处、被递出去恰一处）
 *    + **读形状**（[runLiveWrapUpSteps] 内核零 android、零时钟，两步各一枚 onFailure）；
 * 4. 第 ④ 层 —— **抹注释找锚点**：改前那三形喂进同一把尺，各数到该数的值；
 *    注释里逐字登记的旧形状抹完必须 0 处。
 */
class ReminderStepFallbackGuardTest {

    // ─────────────── ① 四块动作各有自己一枚兜底，且次序没换 ───────────────

    @Test
    fun eachBellChainStepHasItsOwnFallbackAndKeepsItsPlace() {
        val progress = withoutComments(readMainSource(PROGRESS_RECEIVER))
        val reminder = withoutComments(readMainSource(REMINDER_RECEIVER))

        val startLive = enclosingFallback(progress, "ReminderNotifications.startLiveWindow(")
        val enter = enclosingFallback(progress, "ClassProgressDnd.enter(context, end)")
        val live = enclosingFallback(reminder, "ReminderNotifications.startLiveWindow(")
        val banner = enclosingFallback(reminder, "notifyCourse(context, scheduled)")

        val four = listOf(
            "ClassProgressReceiver·上课实况" to startLive,
            "ClassProgressReceiver·上课自动勿扰" to enter,
            "ReminderReceiver·课前实况" to live,
            "ReminderReceiver·课前提醒横幅" to banner,
        )
        for ((label, site) in four) {
            check(site != null) {
                "**钉法 = 读形状**：$label 这一步没有自己的 runCatching —— 要么又被并回整段兜底" +
                    "（一抛就连坐另一条），要么整块没人兜（一抛就是广播主线程当场崩）"
            }
            assertTrue(
                "**钉法 = 读形状**：$label 的 runCatching 之后没有紧跟 .onFailure（失败被丢在 " +
                    "Result 上没人读）：\n后继链「${site.chain}」",
                site.chain.contains(".onFailure"),
            )
            assertTrue(
                "**钉法 = 读形状**：$label 的 .onFailure 里必须有一句**可观测出口**（W 级以上的日志，" +
                    "或文件顶层那几枚注入的报告口）。HyperOS 把 logcat 截在 Info ⇒ 写成 Log.d 等于没写：" +
                    "\n" + site.onFailure,
                OBSERVABLE_EXIT.containsMatchIn(site.onFailure),
            )
        }

        // 次序：一枚都不许挪
        val scheduleEnd = progress.indexOf("ClassProgressScheduler.scheduleEnd(context, end)")
        assertTrue(
            "**钉法 = 读形状 + 顺序**：下课铃必须仍排在实况与勿扰之前（R5 F-31，与 " +
                "ClassProgressCleanupDecisionTest 的 startBellStillSchedulesEndBellFirst 同一判据）：" +
                "scheduleEnd=$scheduleEnd / 实况=${startLive!!.at} / enter=${enter!!.at}",
            scheduleEnd in 0 until startLive.at && startLive.at < enter.at,
        )
        val rescheduleAll = reminder.indexOf("ReminderScheduler.rescheduleAll(")
        assertTrue(
            "**钉法 = 读形状 + 顺序**：课前实况那一块必须仍排在 rescheduleAll 之前 —— " +
                "startLiveWindow 体内的 recordCountdownOwnership 要早于紧随其后的重排" +
                "（T206 落的那条「先记归属再下发」，不许为了对称把它挪后）：" +
                "实况=${live!!.at} / rescheduleAll=$rescheduleAll",
            live.at < rescheduleAll,
        )
        assertTrue(
            "**钉法 = 读形状 + 顺序**：三块的次序是 实况 → 横幅 → 重排，反过来说明兜底被并回过：" +
                "实况=${live.at} / 横幅=${banner!!.at} / 重排=$rescheduleAll",
            live.at < banner.at && banner.at < rescheduleAll,
        )

        // 连坐那一形（一枚**叶子**兜底罩住两枚动作）今天必须数不到
        for ((label, code) in listOf("ClassProgressReceiver" to progress, "ReminderReceiver" to reminder)) {
            val doubled = leafSites(code).filter { site ->
                ACTION_ANCHORS.count { anchor -> site.body.contains(anchor) } > 1
            }
            assertEquals(
                "**钉法 = 数出现次数**：$label 里有一枚叶子 runCatching 同时罩住两枚课堂动作" +
                    "（附加能力一抛就把核心能力带走，正是 #333/#335 的病灶形状）：\n" +
                    doubled.joinToString("\n") { site ->
                        ACTION_ANCHORS.filter { site.body.contains(it) }.joinToString(" + ")
                    },
                emptyList<String>(),
                doubled.map { it.body },
            )
            // 那两枚文件各自还留着"整段那一枚"（T14 落的、罩着唤醒锁与 when 的），不许被顺手拆掉
            assertEquals(
                "**钉法 = 数出现次数**：$label 的 runCatching 枚数与预期不符（整段那一枚是唤醒锁/分支" +
                    "编排的兜底，四块分兜是加在它**里面**的，不是把它换掉）。实到 " +
                    runCatchingSites(code).size + " 枚",
                EXPECTED_RUN_CATCHING[label],
                runCatchingSites(code).size,
            )
        }
    }

    // ─────────────── ② 四句措辞两两不同，死的是哪半条读得出来 ───────────────

    @Test
    fun theFourVerdictsNameDifferentHalves() {
        val messages = listOf(
            "ClassProgressReceiver·上课实况" to verdictOf(readMainSource(PROGRESS_RECEIVER), "ReminderNotifications.startLiveWindow("),
            "ClassProgressReceiver·上课自动勿扰" to verdictOf(readMainSource(PROGRESS_RECEIVER), "ClassProgressDnd.enter(context, end)"),
            "ReminderReceiver·课前实况" to verdictOf(readMainSource(REMINDER_RECEIVER), "ReminderNotifications.startLiveWindow("),
            "ReminderReceiver·课前提醒横幅" to verdictOf(readMainSource(REMINDER_RECEIVER), "notifyCourse(context, scheduled)"),
        )

        for ((label, message) in messages) {
            assertTrue("$label 读不出那句结论文案（报告口没落成一句字面量）：「$message」", message.isNotBlank())
        }
        assertEquals(
            "**钉法 = 逐字节**：四句文案必须两两不同。改前是「课程进行中广播处理失败」与" +
                "「课前提醒展示失败（实况/通知）」各罩着两种死法，现场分不出是勿扰没进、" +
                "实况没起、还是横幅没发：\n" + messages.joinToString("\n") { "${it.first} -> ${it.second}" },
            4,
            messages.map { it.second }.distinct().size,
        )
        for ((label, message) in messages) {
            assertTrue(
                "$label 那句只说了自己死了，没说另一半照常 ⇒ 读日志的人仍然只能猜这条链的残骸范围" +
                    "（文案要含「照常」或「不受影响」；实到「$message」）",
                message.contains("照常") || message.contains("不受影响"),
            )
        }
    }

    // ─────────────── ③ 服务里那四枚裸吞只剩注入口，全文再无丢掉的失败 ───────────────

    @Test
    fun theServiceLeavesNoSwallowWithoutVerdict() {
        val service = withoutComments(readMainSource(SERVICE))

        val naked = runCatchingSites(service).filter { site ->
            CONCLUSION_MARKERS.none { marker -> site.chain.contains(marker) || site.body.contains(marker) }
        }
        assertEquals(
            "**钉法 = 数出现次数**：CourseFluidService 里不许有把失败丢在 Result 上没人读的 runCatching" +
                "（红了就是 #334 那一族的形状回来了：整机静音挂到看门狗那 30 分钟，" +
                "而 logcat 一个字都不留）：\n" +
                naked.joinToString("\n") { "起点 ${it.at}：" + it.body.lines().first().trim() },
            emptyList<String>(),
            naked.map { it.body },
        )

        for (hook in listOf(
            LOG_LIVE_RESTORE, LOG_LIVE_RESCHEDULE, LOG_FGS_CONTRACT, LOG_STOP_FOREGROUND, LOG_DEGRADE_RESCHEDULE,
        )) {
            val declaration = "private val $hook: (Throwable) -> Unit = {"
            assertEquals(
                "**钉法 = 逐字节**：`$hook` 的声明恰一处（顶层属性 = 可注入，JVM 侧才读得到；" +
                    "写死在 onFailure 里就先崩在 \"not mocked\" 的桩上）：实到 " +
                    occurrences(service, declaration) + " 处",
                1,
                occurrences(service, declaration),
            )
            assertEquals(
                "**钉法 = 数出现次数**：`$hook` 全文件恰两枚 —— 上面那枚声明 + 一处真接线" +
                    "（有声明没接线 = 那道兜底还是没人念；三枚 = 有人把同一句留痕抄了第二份）：" +
                    "实到 " + occurrences(service, hook) + " 处",
                2,
                occurrences(service, hook),
            )
        }

        val wrapUp = functionBlock(service, "internal fun runLiveWrapUpSteps(")
        assertEquals(
            "**钉法 = 读形状**：收尾内核两步各一枚兜底（恰两枚 .onFailure；一枚 = 又并回连坐那一形）：" +
                "实到 " + occurrences(wrapUp, ".onFailure") + " 处\n$wrapUp",
            2,
            occurrences(wrapUp, ".onFailure"),
        )
        for (banned in listOf("android.util.Log", "Log.", "Context", "System.currentTimeMillis", "java.time")) {
            assertTrue(
                "**钉法 = 读形状**：内核 runLiveWrapUpSteps 里出现了「$banned」= 它不再是零 android、" +
                    "零时钟的判据，那张表在单测里就量不到（与本仓 LiveFgsRetry 同一口径）：\n$wrapUp",
                !wrapUp.contains(banned),
            )
        }
        assertEquals(
            "**钉法 = 逐字节**：finishLiveAndReschedule 必须真的走那枚内核（实到 " +
                occurrences(functionBlock(service, "private fun finishLiveAndReschedule()"), "runLiveWrapUpSteps(") +
                " 处）",
            1,
            occurrences(functionBlock(service, "private fun finishLiveAndReschedule()"), "runLiveWrapUpSteps("),
        )
        assertEquals(
            "**钉法 = 数出现次数（全 main）**：改前那一形 `runCatching { ClassProgressDnd.restore` 必须 0 处" +
                "（实到 " + mainOccurrences("runCatching { ClassProgressDnd.restore") + " 处）",
            0,
            mainOccurrences("runCatching { ClassProgressDnd.restore"),
        )
    }

    // ─────────────── ④ 探测器自己认得改前那三形 ───────────────

    @Test
    fun probeRecognisesThePreFixShapes() {
        val preFixReminder = """
            runCatching {
                if (livePhase != null) {
                    ReminderNotifications.startLiveWindow(
                        context = context,
                        phase = livePhase,
                    )
                }
                notifyCourse(context, scheduled)
            }.onFailure { android.util.Log.w(TAG, "课前提醒展示失败", it) }
        """.trimIndent()
        val blocks = runCatchingSites(preFixReminder)
        assertEquals("探测器必须切得出改前那一枚 runCatching 与它的后继链（实到 " + blocks.size + " 枚）", 1, blocks.size)
        assertEquals(
            "改前一形必须被数成「一枚兜底罩住两枚动作」（实到 " +
                ACTION_ANCHORS.count { blocks.first().body.contains(it) } + " 枚）—— 第 ① 层那句「0 处」的对照物",
            2,
            ACTION_ANCHORS.count { blocks.first().body.contains(it) },
        )
        assertTrue(
            "改前那枚兜底**是**有 onFailure 的（一句混两种死法的文案）⇒ 第 ① 层靠的不是" +
                "「有没有 onFailure」，而是「每一枚动作各自有没有」：\n" + blocks.first().chain,
            blocks.first().chain.contains(".onFailure"),
        )

        // #334 那一形：Result 被丢在原地，后继链一个字都没有
        val naked = runCatchingSites("runCatching { ClassProgressDnd.restore(applicationContext) }\nreschedule()")
        assertEquals("探测器必须抓得出 #334 那一形（枚数）", 1, naked.size)
        assertEquals(
            "改前那一形读不出任何后继链（实到「" + naked.first().chain + "」）⇒ 第 ③ 层那句「0 处」不是空锚点",
            "",
            naked.first().chain,
        )
        val healed = runCatchingSites("runCatching { ClassProgressDnd.restore(applicationContext) }.onFailure { LOG_LIVE_RESTORE_FAILURE(it) }")
        assertEquals(
            "同一把尺在改后那一形上要读出 onFailure（对照物；实到链「" + healed.first().chain + "」）",
            true,
            healed.first().chain.contains(".onFailure"),
        )

        val quotedInComment = """
            /**
             * 旧形状登记：runCatching { ClassProgressDnd.restore(applicationContext) } 这里改前没有 onFailure。
             */
            fun noop() = Unit
        """.trimIndent()
        assertEquals(
            "注释里逐字引用的那一形不许进第 ③ 层的账：未抹时实到 " +
                occurrences(quotedInComment, "runCatching { ClassProgressDnd.restore") + " 处，抹完必须 0 处",
            0,
            occurrences(withoutComments(quotedInComment), "runCatching { ClassProgressDnd.restore"),
        )
        assertEquals(
            "探针也不许把注释里的 onSuccess 当成结论出口（未抹时数到 1 处、抹完 0 处）",
            0,
            occurrences(withoutComments("/** 只有 .onSuccess 那一形写在注释里：.onSuccess */\nfun noop() = Unit"), ".onSuccess"),
        )
    }

    // ---------------- 源码核对小工具 ----------------

    /** 一枚 `runCatching {…}`：起点、体尾下标、体、以及紧跟其后那条链的原文与其中的 onFailure 内容 */
    private inner class Site(val at: Int, val end: Int, val body: String, val chain: String) {
        val onFailure: String
            get() {
                val marker = chain.indexOf(".onFailure")
                if (marker < 0) return ""
                val open = chain.indexOf('{', marker)
                if (open < 0) return ""
                return braceSegment(chain, open).removePrefix("{").removeSuffix("}")
            }
    }

    /**
     * 包住 [anchor] 那一处调用的**最内**一枚 runCatching。锚点零次 ⇒ 抛（守卫不许静默变绿）；
     * 没有被任何 runCatching 包住 ⇒ null，由调用方判红。
     */
    private fun enclosingFallback(code: String, anchor: String): Site? {
        val at = code.indexOf(anchor)
        check(at >= 0) { "锚点 `$anchor` 不在盘面上：改过名或挪过家就要回来重钉本守卫" }
        return runCatchingSites(code)
            .filter { it.at <= at && at < it.end }
            .minByOrNull { it.body.length }
    }

    /** 该动作那一步的结论文案（`.onFailure` 里第一句字面量）；读不出来给空串，由调用方判红 */
    private fun verdictOf(rawSource: String, anchor: String): String {
        val code = withoutComments(rawSource)
        val site = enclosingFallback(code, anchor) ?: return ""
        return Regex(""""([^"]{4,})"""").find(site.onFailure)?.groupValues?.get(1).orEmpty()
    }

    /** 全文每一枚 `runCatching {…}` 连同其后继链 */
    private fun runCatchingSites(code: String): List<Site> =
        Regex("""runCatching\s*\{""").findAll(code).map { match ->
            val open = match.range.last
            val block = braceSegment(code, open)
            val after = open + block.length
            Site(match.range.first, after, block.removePrefix("{").removeSuffix("}"), chainAfter(code, after))
        }.toList()

    /**
     * 只留**最外圈之内再没有第二枚 runCatching** 的那些块。
     *
     * 为什么要这一层过滤：广播那两枚文件里本来就有合法的"整段兜底"（罩着唤醒锁与 `when` 的那一枚，
     * 它抛出去就是广播主线程崩，那一道保护是 T14 落的，不许拆）。把它算进"一枚兜底罩两枚动作"
     * 那本账，守卫就会红在一格根本没病的形状上；而改前那一形（一枚**叶子**兜底同时罩住实况与横幅）
     * 才是病灶，第 ④ 层拿它当对照物。
     */
    private fun leafSites(code: String): List<Site> = runCatchingSites(code).filter { !it.body.contains("runCatching") }

    /** 从 [from] 起读一条调用链（`.onSuccess {…}` / `.onFailure {…}` / `.getOrDefault(…)`），链断即止 */
    private fun chainAfter(code: String, from: Int): String {
        val builder = StringBuilder()
        var index = from
        while (true) {
            while (index < code.length && code[index].isWhitespace()) index++
            if (code.getOrNull(index) != '.') break
            var name = index + 1
            while (name < code.length && (code[name].isLetterOrDigit() || code[name] == '_')) name++
            builder.append(code.substring(index, name))
            var body = name
            while (body < code.length && code[body].isWhitespace()) body++
            when (code.getOrNull(body)) {
                '{' -> {
                    val segment = braceSegment(code, body)
                    builder.append(code.substring(name, body + segment.length))
                    index = body + segment.length
                }

                '(' -> {
                    val segment = parenSegment(code, body)
                    builder.append(code.substring(name, body + segment.length))
                    index = body + segment.length
                }

                else -> break
            }
        }
        return builder.toString()
    }

    /** `signature` 起、花括号配平的那一整段（含签名） */
    private fun functionBlock(code: String, signature: String): String {
        val at = code.indexOf(signature)
        check(at >= 0) { "找不到 $signature：写法换过了，这条守卫要跟着改" }
        val open = code.indexOf('{', at)
        return code.substring(at, open + braceSegment(code, open).length)
    }

    private fun braceSegment(code: String, open: Int): String = segment(code, open, '{', '}')

    private fun parenSegment(code: String, open: Int): String = segment(code, open, '(', ')')

    private fun segment(code: String, open: Int, openChar: Char, closeChar: Char): String {
        check(code.getOrNull(open) == openChar) {
            "锚点处不是「$openChar」（实到「${code.getOrNull(open)}」）：切不出一段，本层是空的"
        }
        var depth = 0
        var inString = false
        var escaped = false
        var i = open
        while (i < code.length) {
            val c = code[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    openChar -> depth++
                    closeChar -> {
                        depth--
                        if (depth == 0) return code.substring(open, i + 1)
                    }
                }
            }
            i++
        }
        throw IllegalStateException("「$openChar」没配平：从 $open 起")
    }

    private fun readMainSource(relative: String): String {
        val file = File(findMainJavaDir(), relative)
        assertTrue("找不到 ${file.path}：文件挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText().replace("\r\n", "\n")
    }

    private fun mainOccurrences(needle: String): Int = mainSources().sumOf { occurrences(withoutComments(it), needle) }

    private fun mainSources(): List<String> {
        val root = findMainJavaDir()
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("${root.path} 下一个 .kt 都没有，路径不对", files.isNotEmpty())
        return files.map { it.readText().replace("\r\n", "\n") }
    }

    private fun occurrences(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var at = haystack.indexOf(needle)
        while (at >= 0) {
            count++
            at = haystack.indexOf(needle, at + needle.length)
        }
        return count
    }

    /** 只抹注释、保留字符串字面量（与 CountdownOwnershipAnchorGuardTest 同一把刀） */
    private fun withoutComments(source: String): String {
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
            val hit = listOf("src/main/java", "app/src/main/java").map { File(dir, it) }.firstOrNull { it.isDirectory }
            if (hit != null) return hit
            dir = dir?.parentFile
        }
        throw IllegalStateException("找不到 app/src/main/java：当前目录 ${File("").absolutePath}")
    }

    private companion object {
        const val PROGRESS_RECEIVER = "com/buaa/schedule/reminder/ClassProgressReceiver.kt"
        const val REMINDER_RECEIVER = "com/buaa/schedule/reminder/ReminderReceiver.kt"
        const val SERVICE = "com/buaa/schedule/reminder/CourseFluidService.kt"

        /** 会被连坐的那三枚动作名（附加 = 实况，核心 = 勿扰进入 / 提醒横幅） */
        val ACTION_ANCHORS = listOf(
            "ReminderNotifications.startLiveWindow(",
            "ClassProgressDnd.enter(",
            "notifyCourse(",
        )

        /**
         * 两枚广播文件里 runCatching 的枚数册子（抹注释数）：
         * ClassProgressReceiver = 整段那一枚（唤醒锁 + when，T14 落的）+ 上课实况 + 上课勿扰 + 续排；
         * ReminderReceiver = 课前实况 + 课前横幅 + 触发后重排。
         * 少一枚 = 有人把分兜并回去了。
         */
        val EXPECTED_RUN_CATCHING = mapOf("ClassProgressReceiver" to 4, "ReminderReceiver" to 3)

        /** 一枚 runCatching 的失败到底有没有被读走：这些后继里任一都算「有结论出口」 */
        val CONCLUSION_MARKERS = listOf(
            ".onFailure", ".getOrNull", ".getOrDefault", ".getOrThrow", ".recoverCatching", ".recover",
            ".isSuccess", ".isFailure", ".exceptionOrNull",
        )

        /** 可观测出口：W 级以上的日志，或文件顶层那几枚注入的报告口 */
        val OBSERVABLE_EXIT = Regex("""android\.util\.Log\.w\(|\bLog\.w\(|\bLOG_[A-Z_]+\(""")

        const val LOG_LIVE_RESTORE = "LOG_LIVE_RESTORE_FAILURE"
        const val LOG_LIVE_RESCHEDULE = "LOG_LIVE_RESCHEDULE_FAILURE"
        const val LOG_FGS_CONTRACT = "LOG_FGS_CONTRACT_FAILURE"
        const val LOG_STOP_FOREGROUND = "LOG_STOP_FOREGROUND_FAILURE"
        const val LOG_DEGRADE_RESCHEDULE = "LOG_DEGRADE_RESCHEDULE_FAILURE"
    }
}
