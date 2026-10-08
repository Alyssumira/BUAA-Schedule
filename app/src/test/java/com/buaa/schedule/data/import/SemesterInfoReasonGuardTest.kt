package com.buaa.schedule.data.import

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T196④：把「`SemesterInfo` 带上一枚原因，且那句用户可见的话按两维分档」钉成静态守卫
 * （读 `app/src/main` 工作树文本，纯 JVM、无 Robolectric、无设备、不读时钟）。
 *
 * ## 病
 *
 * 改前四档早返回（termCode 不过白名单 / fetch 回 null / weeks 空 / 整块 `runCatching` 失败）
 * 加 T193 那一档（判不出第 1 周）**全都只交出 `firstWeekMonday = null`** 一枚事实，
 * 而 `BuaaLoginScreen` 那句文案只按「raw 有没有」分两等 —— 「页面根本没答」与
 * 「教务答了而日期不认识」被压成同一句「请稍后重试」。
 *
 * ## 钉的六层
 *
 * 1. [构造点数与四档枚数] `fetchSemesterInfo` 体内 `SemesterInfo(` 恰好 6 处 = 四档早返回
 *    （`SemesterInfo(20, null, …`）+ 内核两档（`SemesterInfo(weeks.size, …`），
 *    四枚 20 **一枚不许变枚数**（卡面 §1-④ 的 ⚠️：那一格 `?: 20` 的账在台账 #236，本卡只做原因分档）。
 * 2. [五枚原因各就各位] 每一枚 `[SemesterInfoReason]` 枚举值在体内逐字出现恰好一处，
 *    且落在那一档自己的早返回行上（按 index 与那一档的锚点配对，不数全局枚数了事）；
 *    枚举文件里每枚声明恰好一处 ⇒ 「一一对应」而不是「有一档被两枚顶掉」。
 * 3. [接线] `BuaaLoginScreen` 里 `semesterStartFailureMessage(` 恰好一处、落在
 *    `BuaaSemesterStart.Undetermined` 那一支里，且两枚实参 `raw = start.raw` 与
 *    `reason = info.missingReason` **都在** —— 只递 raw 就等于把分档白做（那是改前的形状）。
 * 4. [文案搬干净] 那一句的三个字面片段在 `BuaaLoginScreen` 里 0 处、在纯函数文件里各 1 处
 *    （留在页面里 = 表驱动单测吃不到，而 `:app` 没有 Robolectric）。
 * 5. [纯度 + 一本表] 原因枚举文件与文案文件零 android import、零时钟、零 WebView、
 *    不 import `BuaaInPageFetcher`；全 `app/src/main` 里 `enum class …Reason` 恰好 3 枚
 *    （T193 那本 + 本卡这本 + T265 的 `MarkerBlindReason`，那一枚只说「所有权标记为什么读不回来」，
 *    它的句子表在 `RemovalCopy` 里并由另一枚守卫逐档钉着）⇒ 卡面「别再造第二本分类表」由枚数守着。
 *    第五枚 `FirstWeekUndetermined` **不重述**内核那三枚 reason，所以它不是第二本表而是入口。
 * 6. [反套套逻辑] 探测器必须认得**改前那一形**（四档都不带原因 ⇒ 体内枚数 0）、认得
 *    「只递 raw」那一形（接线扭法）、并把「姊妹路 `BuaaWebSession` 不构造这枚类型」钉成 0 处
 *    —— 那一枚为 0 才是卡面 §8 那句「④不需要动 `BuaaWebSession.kt`」的凭据。
 */
class SemesterInfoReasonGuardTest {

    // ─────────────── ① 构造点数 / 四档枚数 ───────────────

    @Test
    fun `四档早返回一枚不多一枚不少 周数那四格仍是硬写的 20`() {
        val body = functionBody(FETCHER, INFO_HEAD)

        assertEquals(
            "`fetchSemesterInfo` 体内 `SemesterInfo(` 恰好 6 处（实到 " + occurrences(body, "SemesterInfo(") +
                " 处）：四档早返回 + 内核那两档。长出第七处就是有人新添了一档却不给它原因名，" +
                "少一处就是某一档被并掉了",
            6,
            occurrences(body, "SemesterInfo("),
        )
        for (needle in listOf(FOUR_ARMS_NEEDLE, GET_OR_DEFAULT_NEEDLE)) {
            val expected = if (needle == FOUR_ARMS_NEEDLE) 4 else 1
            assertEquals(
                "`$needle` 在 `$FETCHER_NAME` 恰好 $expected 处（实到 " +
                    occurrences(blankCommentsKeepingLiterals(source(FETCHER)), needle) +
                    " 处）：卡面 §1-④ 的 ⚠️ 写明本卡**不许改 `totalWeeks` 的 20 那四格** —— " +
                    "`?: 20` 那一族的账在台账 #236（那里还要判「没学期时该不该给周次导航」）。" +
                    "本卡只往同一枚构造里多递一枚原因，20 保持现状",
                expected,
                occurrences(blankCommentsKeepingLiterals(source(FETCHER)), needle),
            )
        }
        assertEquals(
            "`SemesterInfo(weeks.size, null,` 恰好一处（实到 " +
                occurrences(body, WEEKS_SIZE_NULL_ARM) + " 处）：T193 那一档（判不出第 1 周）" +
                "今天交的是 `weeks.size` 而不是 20 —— 那一格本卡只补原因名，别把 20 请回来",
            1,
            occurrences(body, WEEKS_SIZE_NULL_ARM),
        )
        assertEquals(
            "`SemesterInfo(weeks.size, firstWeek.startDate)` 逐字一处（实到 " +
                occurrences(body, RESOLVED_PRODUCT) + " 处）：成功那一档**不递**第三枚实参" +
                "（`missingReason` 有默认值 null）—— 给它塞一枚原因等于造一枚永远走不到的「拿到了但又为什么没有」",
            1,
            occurrences(body, RESOLVED_PRODUCT),
        )
    }

    // ─────────────── ② 五枚原因与五档一一对应 ───────────────

    @Test
    fun `五枚原因各落在自己那一档 与早返回锚点逐处配对`() {
        val body = functionBody(FETCHER, INFO_HEAD)
        // 落点按「夹在两枚结构锚点之间」判：把 `InvalidTermCode` 递到 weeks 空那一档上，
        // 枚数一层完全看不出来（五枚还是各一处），念出来的却是「请求根本没发出去」而页面明明答了。
        val pairs = listOf(
            ReasonArm(
                entry = "InvalidTermCode",
                after = "TERM_CODE_PATTERN.matches(it)",
                before = "while (raw == null",
                what = "白名单那一判与重试圈之间：请求根本没发出去那一档",
            ),
            ReasonArm(
                entry = "PageUnanswered",
                after = "while (raw == null",
                before = "return runCatching",
                what = "重试圈之后、解析块之前：页面（重试之后仍然）没答那一档",
            ),
            ReasonArm(
                entry = "EmptyWeekList",
                after = "resp.datas.orEmpty()",
                before = "resolveBuaaFirstWeek(",
                what = "解出响应之后、判第 1 周之前：答了而一行周次都没给那一档",
            ),
            ReasonArm(
                entry = "FirstWeekUndetermined",
                after = "when (firstWeek)",
                before = "}.getOrDefault(",
                what = "内核结论那两档里 Undetermined 那一支：周次行拿到了而第 1 周判不出",
            ),
            ReasonArm(
                entry = "WeekListUnreadable",
                after = "}.getOrDefault(",
                before = "",
                what = "整块 runCatching 的兜底：答了而那份答读不出来那一档",
            ),
        )
        for (arm in pairs) {
            val needle = "SemesterInfoReason.${arm.entry}"
            assertEquals(
                "`$needle` 在 `fetchSemesterInfo` 体内恰好一处（实到 " + occurrences(body, needle) +
                    " 处）：五枚原因与五档早返回**一一对应**是本卡的立论 —— 少一处就是某一档又只交出 " +
                    "`firstWeekMonday = null` 那一枚事实（改前的形状），多一处就是有一枚原因被两档共用" +
                    "（那两档在用户端又变成同一件事）",
                1,
                occurrences(body, needle),
            )
            assertBetween(body, needle, arm, pairs)
        }
        val enumFile = blankCommentsKeepingLiterals(source(REASON_ENUM))
        assertEquals(
            "`enum class SemesterInfoReason {` 恰好一处（实到 " +
                occurrences(enumFile, "enum class SemesterInfoReason {") + " 处）",
            1,
            occurrences(enumFile, "enum class SemesterInfoReason {"),
        )
        for (arm in pairs) {
            assertEquals(
                "枚 `${arm.entry}` 在枚举文件里声明恰好一处（实到 " +
                    occurrences(enumFile, "${arm.entry},") + " 处）：同一枚原因声明两次 = " +
                    "两本表里各有一条同名不同义的档",
                1,
                occurrences(enumFile, "${arm.entry},"),
            )
        }
        assertEquals(
            "枚举共 5 枚（体内以逗号收尾的声明行；实到 " + reasonsDeclared(enumFile) +
                " 处）：与 `fetchSemesterInfo` 的五档锁死 —— 给枚举添一枚而调用点没跟上，" +
                "本卡第 ① 层那 6 处与这一格会一起红",
            5,
            reasonsDeclared(enumFile),
        )
    }

    /** 一枚原因与它所隶属的那一档：靠「夹在两枚结构锚点之间」判落点 */
    private data class ReasonArm(val entry: String, val after: String, val before: String, val what: String)

    /** [needle] 的落点必须落在 `after` 之后（有 `before` 时还要在它之前），且那两枚锚点各恰好一处 */
    private fun assertBetween(body: String, needle: String, arm: ReasonArm, all: List<ReasonArm>) {
        val at = indexOfAll(body, needle)
        val afterAt = indexOfAll(body, arm.after)
        assertEquals(
            "落点判据的左锚点 `${arm.after}` 在体内恰好一处（实到 " + afterAt.size + " 处，第 " +
                "${all.indexOf(arm) + 1} 档「${arm.what}」）：锚点自己长出第二处就没法判「哪一行了」，" +
                "这一格红说明调用点的形状变了，落点判据要跟着重钉",
            1,
            afterAt.size,
        )
        val hit = at.first()
        assertTrue(
            "`$needle` 必须落在**它自己那一档**（${arm.what}）：左锚点 `${arm.after}` 在 L" +
                lineOf(body, afterAt.first()) + "（index=${afterAt.first()}）而这一枚在 L" +
                lineOf(body, hit) + "（index=$hit）—— 落点判的是位置，不是枚数",
            hit > afterAt.first(),
        )
        if (arm.before.isNotEmpty()) {
            val beforeAt = indexOfAll(body, arm.before)
            assertEquals(
                "落点判据的右锚点 `${arm.before}` 在体内恰好一处（实到 " + beforeAt.size + " 处）",
                1,
                beforeAt.size,
            )
            assertTrue(
                "`$needle` 必须**早于**右锚点 `${arm.before}`（实到 L" + lineOf(body, hit) + " vs L" +
                    lineOf(body, beforeAt.first()) + "）：越过右锚点它就落到隔壁那一档上了",
                hit < beforeAt.first(),
            )
        }
    }

    // ─────────────── ③ 接线：闸门那一支真的把两维都递过去了 ───────────────

    @Test
    fun `登录页只递两枚现成事实给纯函数 两枚实参缺一即红`() {
        val code = blankCommentsKeepingLiterals(source(LOGIN_SCREEN))
        val calls = indexOfAll(code, COPY_CALL)
        assertEquals(
            "`$LOGIN_NAME` 里 `$COPY_CALL` 恰好一处（实到 " + calls.size + " 处，" + lineHints(code, calls) +
                "）：它是「原因 → 用户句子」这映射的唯一接线点。0 处 = 分档整个没接上；" +
                "2 处 = 有两扇门各自拼句子，那就是第二本表",
            1,
            calls.size,
        )
        val hits = indexOfAll(code, UNDETERMINED_BRANCH)
        assertEquals("`$UNDETERMINED_BRANCH` 恰好一处（本层取块的宿主）：实到 " + hits.size + " 处", 1, hits.size)
        val branch = braceBodyFrom(code, code.indexOf('{', hits.first() + UNDETERMINED_BRANCH.length))
        assertEquals(
            "那一枚调用必须落在 `$UNDETERMINED_BRANCH` 那一支里（实到支内 " +
                occurrences(branch, COPY_CALL) + " 处）：认得出来的那一支没有「为什么没有」要交代",
            1,
            occurrences(branch, COPY_CALL),
        )
        for (needle in listOf(RAW_ARGUMENT, REASON_ARGUMENT)) {
            assertEquals(
                "`$needle` 在 `$LOGIN_NAME` 恰好一处（实到 " + occurrences(code, needle) +
                    " 处）：**两枚维度各一枚**是卡面 §1-④ 的原话（「有没有拿到串」与「页面有没有答」）。" +
                    "少 `reason` 那枚就是改前那个形状 —— 五档压回一句「请稍后重试」",
                1,
                occurrences(code, needle),
            )
        }
    }

    // ─────────────── ④ 文案搬干净：页面里不许再留字面量 ───────────────

    @Test
    fun `那一句的头与尾各一枚字面量 页面里不再留着拼句子的片段`() {
        val login = blankCommentsKeepingLiterals(source(LOGIN_SCREEN))
        val copy = blankCommentsKeepingLiterals(source(COPY))

        // 页面那一侧：三片段都得是 0（除了与「失败周」那一句共用的那一尾，它本来就该在页面里）
        for (needle in listOf(FAILURE_HEAD, MISSING_WORD)) {
            assertEquals(
                "`$needle` 在 `$LOGIN_NAME` 必须 0 处（实到 " + occurrences(login, needle) +
                    " 处）：留在 Composable 里就得装机才验得了（`:app` 的 JVM 面没有 Robolectric），" +
                    "表驱动单测吃不到那一格 —— 它今天就在 `Undetermined` 支里各写一枚，本卡把它搬进纯函数",
                0,
                occurrences(login, needle),
            )
        }
        assertEquals(
            "`$FAILURE_TAIL` 在 `$LOGIN_NAME` 恰好一处（实到 " + occurrences(login, FAILURE_TAIL) +
                " 处）：剩下的那一处是**「抓取未完成：第 … 周失败」那一句**的收尾，卡面 §2 明令不改它的措辞；" +
                "闸门那一句的同一枚收尾已经搬进纯函数（那里也恰好一处）",
            1,
            occurrences(login, FAILURE_TAIL),
        )
        // 纯函数那一侧：头、尾、「缺失」那一等各一枚
        for (needle in listOf(FAILURE_HEAD, FAILURE_TAIL, MISSING_WORD)) {
            assertEquals(
                "`$needle` 在文案文件里恰好一处（实到 " + occurrences(copy, needle) +
                    " 处）：头与尾各一枚是「所有档共用那一问与那一尾」的实现凭据 —— " +
                    "各档各抄一遍会让 `SemesterGateOrderingGuardTest` 在整目录里数那句「已保留原课表…」多出好几处",
                1,
                occurrences(copy, needle),
            )
        }
        assertEquals(
            "五枚原因的措辞各一处（`SemesterInfoReason.` 在文案文件里 5 处；实到 " +
                occurrences(copy, "SemesterInfoReason.") + " 处）：少一枚 = 那一档又落回共用句子；" +
                "多一枚 = 有一枚原因被两档共用（与第 ② 层互为备份）",
            5,
            occurrences(copy, "SemesterInfoReason."),
        )
    }

    // ─────────────── ⑤ 纯度 + 一本表 ───────────────

    @Test
    fun `原因枚举与文案两份文件零 android 零时钟 全仓 Reason 枚举只三本`() {
        for (path in listOf(REASON_ENUM, COPY)) {
            val code = blankCommentsKeepingLiterals(source(path))
            for (needle in listOf("import android", "import androidx", "LocalDate.now(", "System.currentTimeMillis", "WebView")) {
                assertEquals(
                    "`$path` 里 `$needle` 必须 0 处（实到 " + occurrences(code, needle) +
                        " 处）：这一族的两份文件都进得了 JVM 单测，靠的就是这一句",
                    0,
                    occurrences(code, needle),
                )
            }
        }
        val copy = blankCommentsKeepingLiterals(source(COPY))
        assertEquals(
            "文案文件里不许出现 `SemesterInfo(`（实到 " + occurrences(copy, "SemesterInfo(") +
                " 处）：它只吃两枚现成事实（raw 与 reason），重造那枚类型就是把构造点分散到界面层",
            0,
            occurrences(copy, "SemesterInfo("),
        )
        assertEquals(
            "文案文件里不许 import `BuaaInPageFetcher`（实到 " + occurrences(copy, "BuaaInPageFetcher") +
                " 处）：那是设备面（WebView + Log）的宿主，引进来这一份文件就出不了 JVM 单测",
            0,
            occurrences(copy, "BuaaInPageFetcher"),
        )
        val byFile = readMainSources()
        val reasonEnums = byFile.entries.toList().sortedBy { it.key }
            .filter { Regex("enum class \\w*Reason").find(it.value) != null }
        assertEquals(
            "全 app/src/main 里 `enum class …Reason` 恰好 3 枚（T193 的 `UndeterminedReason` + 本卡的 " +
                "`SemesterInfoReason` + T265 的 `MarkerBlindReason`；实到 " + reasonEnums.size + " 枚，册子 " +
                reasonEnums.joinToString { "${it.key}=${Regex("enum class \\w*Reason").findAll(it.value).toList().size}" } +
                "）。卡面 §1-④ 的「别再造第二本分类表」就钉在这一格：多一本表意味着同一件事有两种名字，" +
                "而「哪一档念哪一句」又要翻两处代码才答得出。T265 那一枚按同一把尺复算过：它不是句子表 " +
                "（它说不出任何一句用户文案，只说明『所有权标记为什么读不回来』这三档诊断），" +
                "那一族的「哪一档念哪一句」在 `RemovalCopy` 里，而那张表被 CalendarReclaimSingleSourceGuardTest " +
                "逐档钉着六张嘴 —— 第四枚 `…Reason` 仍然要按同样两步复算（它是不是句子表？它的句子表在哪枚守卫里？）" +
                "才能进这本册子",
            3,
            reasonEnums.size,
        )
        assertEquals(
            "那三枚的落点必须是 `BuaaFirstWeekPolicy.kt`、`CalendarReclaimPlanner.kt` 与 `SemesterInfoReason.kt`" +
                "（实到 " + reasonEnums.joinToString { it.key } + "）：搬去界面层 = 把判据表搬到读它的地方",
            listOf("BuaaFirstWeekPolicy.kt", "CalendarReclaimPlanner.kt", "SemesterInfoReason.kt"),
            reasonEnums.map { it.key },
        )
    }

    // ─────────────── ⑥ 反套套逻辑 + 姊妹路 ───────────────

    @Test
    fun `探测器认得改前那一形与只递 raw 那一形 姊妹路不构造这枚类型`() {
        // 改前那一形：四档早返回都只交 `SemesterInfo(20, null)`，一枚原因名都没有
        // （构造点与盘上同数：四档早返回 + 内核那两档 = 6 处，这样「枚数相同、原因缺席」这一手才演得出来）
        val oldShape = """
            val safeTermCode = termCode.takeIf { TERM_CODE_PATTERN.matches(it) }
                ?: return SemesterInfo(20, null)
            val raw = fetch(webView, "GET", path, "") ?: return SemesterInfo(20, null)
            return runCatching {
                if (weeks.isEmpty()) return SemesterInfo(20, null)
                when (firstWeek) {
                    is BuaaFirstWeek.Resolved -> SemesterInfo(weeks.size, firstWeek.startDate)
                    is BuaaFirstWeek.Undetermined -> SemesterInfo(weeks.size, null)
                }
            }.getOrDefault(SemesterInfo(20, null))
        """.trimIndent()
        assertEquals(
            "探测器必须把**改前那一形**判成「一档原因都没带」（`SemesterInfo(20, null,` 数到 0）。实到 " +
                occurrences(oldShape, FOUR_ARMS_NEEDLE) + " 处 —— 数出 4 就说明这一格读的是自己的样例而不是形状",
            0,
            occurrences(oldShape, FOUR_ARMS_NEEDLE),
        )
        assertEquals(
            "改前那一形里 `SemesterInfo(` 也是 6 处（实到 " + occurrences(oldShape, "SemesterInfo(") +
                " 处）⇒ 第 ① 层那枚「体内 6 处」**单独**拦不住「不带原因」那一形，" +
                "所以第 ② 层按档配对那一格不是冗余（两格分工写在这里）",
            6,
            occurrences(oldShape, "SemesterInfo("),
        )

        // 接线扭法：只递 raw（= 改前的两等文案），探测器必须报 reason 缺席
        val rawOnly = """
            viewModel.showMessage(
                semesterStartFailureMessage(raw = start.raw),
                isError = true,
            )
        """.trimIndent()
        assertEquals(
            "「只递 raw」那一形里 `$REASON_ARGUMENT` 必须 0 处（实到 " + occurrences(rawOnly, REASON_ARGUMENT) +
                " 处）：卡面 §1-④ 的第二维就是它。而 `$COPY_CALL` 还是 1 处（实到 " +
                occurrences(rawOnly, COPY_CALL) + " 处）⇒ 只数调用点枚数的守卫会被这一手骗过去",
            0,
            occurrences(rawOnly, REASON_ARGUMENT),
        )

        // 姊妹路：本卡没碰 BuaaWebSession.kt，它既不构造也不读这枚原因
        val web = blankCommentsKeepingLiterals(source(WEB_SESSION))
        val constructed = CONSTRUCTION.findAll(web).toList().size
        assertEquals(
            "`$WEB_SESSION_NAME` 里**构造** `SemesterInfo(…)` 恰好 0 处（实到 " + constructed +
                " 处）：它是**卡面 §8 那一格的凭据** —— ④ 给 `SemesterInfo` 加原因**不需要**动那份文件，" +
                "因为它只消费 `firstWeekMonday` / `totalWeeks` 两枚、从不构造。" +
                "（针前面吃了「标识符边界」那一手：`fetcher.fetchSemesterInfo(` 里含 `SemesterInfo(` 那六个字符，" +
                "拿裸字面量数会把它算成构造点。）" +
                "这一格红了说明有人在姊妹路里造了 SemesterInfo，那一格就要交回编排者判（卡面 §3 不许碰）",
            0,
            constructed,
        )
        assertEquals(
            "`$WEB_SESSION_NAME` 里 `missingReason` 恰好 0 处（实到 " + occurrences(web, "missingReason") +
                " 处）：那一路的失败句今天仍是「未取得学期第一周周一，未改动课表」一句 —— " +
                "分档只送到首次导入那一路（卡面 §1-④ 点名的那一处），刷新那一路要不要跟着分档是另一格",
            0,
            occurrences(web, "missingReason"),
        )
    }

    // ---------------- 取块与小工具 ----------------

    /** 枚数：枚举体内以逗号收尾的声明行（KDoc 与 `[...]` 引用都不吃这一形） */
    private fun reasonsDeclared(enumFile: String): Int =
        Regex("""^\s{4}[A-Z]\w*,$""", RegexOption.MULTILINE).findAll(enumFile).toList().size

    /**
     * 从 `suspend fun xxx(` 那枚锚点切出整段函数体。
     *
     * 开括号**不是**「锚点之后第一枚 `{`」：参数表里的默认值 `= { _, _ -> }` 也在花括号里，
     * 拿它当块头会切出一枚空块（本卡第 ①② 层全在读空文本）。先配平参数表的圆括号再取其后第一枚 `{`。
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
        check(open > paramsClose) { "锚点 `$anchor` 的参数表之后找不到函数体开括号（变成表达式函数体？），本守卫要跟着改" }
        val body = braceBodyFrom(code, open)
        assertTrue(
            "切出来的 `$anchor` 函数体小于 $MIN_BODY_CHARS 字符（实到 " + body.length +
                "）：块切歪了的话「体内枚数」全在读空文本",
            body.length >= MIN_BODY_CHARS,
        )
        return body
    }

    private fun source(relativeFromMainJava: String): String {
        val file = File(findMainJavaDir(), relativeFromMainJava)
        assertTrue("找不到 ${file.path}：文件挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText().replace("\r\n", "\n")
    }

    /** 文件名 → 抹注释后的内容（同名文件直接抛，避免按文件名建册子时读错份） */
    private fun readMainSources(): Map<String, String> {
        val root = findMainJavaDir()
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("${root.path} 下一个 .kt 都没有：路径不对，本守卫就是空的", files.isNotEmpty())
        val out = linkedMapOf<String, String>()
        files.forEach {
            check(!out.containsKey(it.name)) { "main 源码里有同名文件 ${it.name}，按文件名建册子会读错份" }
            out[it.name] = blankCommentsKeepingLiterals(it.readText().replace("\r\n", "\n"))
        }
        assertEquals("扫 main 源码的文件数与建出来的册子条目数不等", files.size, out.size)
        return out
    }

    private fun lineHints(source: String, positions: List<Int>): String {
        if (positions.isEmpty()) return ""
        return "\n" + positions.joinToString("") { at -> "  L" + lineOf(source, at) + ": " + lineAt(source, at).trim() + "\n" }
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

    private fun braceBodyFrom(source: String, open: Int): String {
        check(source.getOrNull(open) == '{') {
            "锚点末尾不是花括号：写法变了，切不出一段体（实到「${source.getOrNull(open)}」）"
        }
        return source.substring(open + 1, (matchingBrace(source, open) ?: error("花括号没配平：从 $open 起")))
    }

    private fun matchingBrace(src: String, open: Int): Int? = balance(src, open, '{', '}')

    private fun matchingParen(src: String, open: Int): Int? = balance(src, open, '(', ')')

    /** 括号配平（跳过字符串与字符字面量）；[open] 指向开括号，返回配平的那枚闭括号下标 */
    private fun balance(src: String, open: Int, opening: Char, closing: Char): Int? {
        if (src.getOrNull(open) != opening) return null
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
                c == opening -> depth++
                c == closing -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return null
    }

    /**
     * **只抹注释、保留字符串字面量内容**，长度与换行位置不变（失败消息里的行号因此可信）。
     * 本卡尤其需要抹注释：`SemesterInfoReason.kt` 的 KDoc 里逐字点着五枚枚举值名与
     * `SemesterInfo(20, null, …)` 那几句，不抹的话第 ② 层与第 ④ 层会把自己的文档数成接线。
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
        const val REASON_ENUM = "com/buaa/schedule/data/import/SemesterInfoReason.kt"
        const val COPY = "com/buaa/schedule/ui/importing/SemesterStartFailureCopy.kt"
        const val LOGIN_SCREEN = "com/buaa/schedule/ui/importing/BuaaLoginScreen.kt"
        const val LOGIN_NAME = "BuaaLoginScreen.kt"
        const val WEB_SESSION = "com/buaa/schedule/data/import/BuaaWebSession.kt"
        const val WEB_SESSION_NAME = "BuaaWebSession.kt"

        const val INFO_HEAD = "suspend fun fetchSemesterInfo("
        const val UNDETERMINED_BRANCH = "is BuaaSemesterStart.Undetermined ->"

        /** 四档早返回（20 那一族）与内核那一档 */
        const val FOUR_ARMS_NEEDLE = "SemesterInfo(20, null,"
        const val GET_OR_DEFAULT_NEEDLE = "}.getOrDefault(SemesterInfo(20, null,"
        const val WEEKS_SIZE_NULL_ARM = "SemesterInfo(weeks.size, null,"
        const val RESOLVED_PRODUCT = "SemesterInfo(weeks.size, firstWeek.startDate)"

        /** 五档各自的落点判据见 `五枚原因各落在自己那一档` 那一张表（夹在两枚结构锚点之间） */

        /** 接线两枚实参与那一句的头尾、「有没有拿到串」那一等 */
        const val COPY_CALL = "semesterStartFailureMessage("
        const val RAW_ARGUMENT = "raw = start.raw"
        const val REASON_ARGUMENT = "reason = info.missingReason"
        const val FAILURE_HEAD = "教务系统没有给出可识别的开学日期"
        const val FAILURE_TAIL = "已保留原课表未做改动，请稍后重试"
        const val MISSING_WORD = "缺失"

        const val MIN_BODY_CHARS = 800

        /**
         * 构造点针：前面吃「标识符边界」—— 裸 `SemesterInfo(` 会把 `fetcher.fetchSemesterInfo(` 里
         * 那六个字符一起数进来（姊妹路那一格实测就是这样假红的，第一版把针写成裸字面量给判成 1 处）。
         */
        val CONSTRUCTION = Regex("(?<![A-Za-z0-9_])SemesterInfo\\(")
    }
}
