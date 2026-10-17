package com.buaa.schedule.data.import

import android.util.Log
import android.webkit.WebView
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import com.buaa.schedule.domain.importing.BuaaWeekShellPolicy
import com.buaa.schedule.domain.importing.WeekShellTier
import com.buaa.schedule.domain.schedule.CourseConstraints
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

/**
 * 北航教务「页面上下文抓取」——唯一可靠路径。
 *
 * 决定性结论（2026-09-13 浏览器实测）：独立 HttpURLConnection/OkHttp 复刻
 * 教务接口必 401（服务端认页面上下文携带的完整凭证），而**在已登录的 byxt
 * 页面里 fetch（credentials: include）则 200 可用**。
 *
 * 已确认接口：
 * - GET  /api/home/kb/xnxq.do                             → 学年学期列表
 * - POST /api/home/student/getMyScheduleDetail.do          → 课表（type=week|term）
 * - GET  /api/home/getTermWeeks.do?termCode=X              → 周次
 * - POST /api/home/student/getSections.do                  → 节次
 *
 * ## 为什么用「全局槽位 + 轮询」而不是别的做法
 *
 * `evaluateJavascript` 等价于 DevTools 的 `Runtime.evaluate`，**不带 awaitPromise**：
 * 脚本返回 Promise 时回调只能拿到 `{}`，不是 resolve 后的值。实测见
 * `WebViewEvaluateJavascriptContractTest`：
 * - `(function(){ return 42 })()`       → `"42"`（同步值可用）
 * - `Promise.resolve(42)`              → `"{}"`
 * - `(async function(){ return 42 })()` → `"{}"`
 *
 * 因此「fetch 结果当 evaluateJavascript 返回值取」永远拿不到数据；只靠
 * evaluateJavascript 就**只能读同步值**。两条可行路线：
 * 1. `addJavascriptInterface` 回调桥——实测在页面已加载后再注入不生效
 *    （页面报 `ReferenceError: __buaaBridge is not defined`），不可靠；
 * 2. **把结果写进 window 全局槽位，再用 evaluateJavascript 同步读回**——见 [fetch]。
 *
 * 本实现采用方案 2：结果异步落到 `window.__buaaFetch[id]`，原生侧轮询读取并清除。
 * 只依赖「同步表达式能正确返回」这一条已被测试钉死的契约。
 */
object BuaaInPageFetcher {

    private const val TAG = "BuaaInPageFetcher"
    // R3 审查 P2-17：25s 超时 × 整学期逐周轮询 = 单次刷新最长可达 25 分钟。
    // 页面内 fetch 正常 1-3s 就应返回，12s 已覆盖弱网 + 教务慢查询的尾部延迟；
    // 真正卡死的请求宁可让这一周标记失败（失败周会以 warning 呈现并支持重试），
    // 也不要让用户在进度条前等上几分钟。
    private const val TIMEOUT_MS = 12_000L
    private const val POLL_INTERVAL_MS = 120L

    /**
     * 单周抓取失败后的静默重试间隔。评审 P0-3：弱网抖动 / 教务瞬时超时是单周
     * 失败的主因，间隔给服务端一个喘息窗口；19 周最坏情况也只多花 ~10s。
     */
    private const val RETRY_DELAY_MS = 500L

    /**
     * 单周最多 2 次尝试（含首次）。此前"超时重取 + 解析失败再重取"叠起来一周能发
     * 4 次页面内 fetch，25 周最坏 ~17 分钟死磕教务处，而且没有任何全局预算
     * （R5 F-38）。弱网抖动一次重试足够；再失败就是会话/服务的问题，多重试无意义。
     */
    private const val MAX_ATTEMPTS_PER_WEEK = 2

    /**
     * 连续 3 周失败即中止整轮：这几乎一定是会话过期或教务不可用，而不是单周抖动，
     * 剩下的周次只会重复同一手（要么不答，要么答了而外壳里没有课程数据）。
     * 剩余周次一律记为失败，调用方据此拒绝覆盖既有课表。
     */
    private const val MAX_CONSECUTIVE_FAILURES = 3

    /**
     * 接口绝对 origin（R5 F-39，取自 [BuaaWebSession.BYXT_ORIGIN]）。注入脚本原先写
     * `fetch('/jwapp/…')`，按**当前文档 origin** 解析：登录回跳途中停在 sso 域时
     * 会把请求打到错误的服务器上。
     */
    private val API_ORIGIN = BuaaWebSession.BYXT_ORIGIN

    /** 轮询时连读带清，避免保留的会话 WebView 里全局槽位无限增长 */
    private const val RESULT_STORE = "window.__buaaFetch"

    /** 学期代码白名单：只允许数字/字母与 `.-_`，防止畸形取值进入页面内脚本 */
    private val TERM_CODE_PATTERN = Regex("[0-9A-Za-z._-]{1,32}")

    /** 失败哨兵：错误信息会拼在该前缀之后回传 */
    private const val ERROR_PREFIX = "\u0000BUA_FETCH_ERROR\u0000"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val requestSeq = AtomicLong(0L)

    @Serializable
    private data class XnxqItem(val itemCode: String?, val itemName: String?, val selected: Boolean? = null)

    @Serializable
    private data class XnxqResp(val datas: List<XnxqItem>? = emptyList())

    /** 学年学期选项 */
    data class TermOption(val code: String, val name: String, val selected: Boolean = false)

    /**
     * 学期信息：周数 + 第 1 周周一（开学日）+ 「没有开学日的话是**为什么**」（T196④）。
     *
     * [missingReason] 只在 `firstWeekMonday == null` 那一侧有值，取五枚之一
     * （[SemesterInfoReason]，与 `fetchSemesterInfo` 里五档早返回一一对应）；
     * 拿到了串的那一档留 null —— 那时无「为什么没有」要交代，而「有串而认不出」那一等的
     * 判据在 T180 的 `resolveBuaaSemesterStart` 手里，不在这一格。
     *
     * 为什么这枚参数要**带默认值**：下游 `BuaaWebSession.refreshSchedule` 只读
     * `firstWeekMonday` 与 `totalWeeks` 两枚，构造点全在 `fetchSemesterInfo` 体内 ——
     * 卡面 §3 不许碰那份文件，所以这一格的新增必须以「不改变任何既有调用点形状」的方式落地。
     *
     * ⚠️ [totalWeeks] 前四档仍是硬写的 20：那一笔账在台账 #236（`?: 20` 那一族，还要判
     * 「没学期时该不该给周次导航」），本卡只做**原因分档**、20 保持现状。
     */
    data class SemesterInfo(
        val totalWeeks: Int,
        val firstWeekMonday: String?,
        val missingReason: SemesterInfoReason? = null,
    )

    /**
     * 在当前已登录的 byxt 页面上下文里发一个请求，并把响应文本回传原生。
     *
     * @param path 以 `/` 开头的接口路径；内部按 [API_ORIGIN] 解析成绝对 URL，
     *   不依赖当前文档 origin（R5 F-39）
     * @return 响应体文本；请求失败 / 超时 / 返回错误哨兵时返回 null
     */
    suspend fun fetch(
        webView: WebView,
        method: String,
        path: String,
        body: String,
    ): String? {
        val requestId = requestSeq.incrementAndGet().toString()
        // 1) 先把请求挂上：结果异步写入全局槽位（脚本本身同步返回 undefined）
        webView.post { webView.evaluateJavascript(startScript(method, path, body, requestId), null) }

        // 2) 轮询同步读回结果（读到时顺手清除槽位）
        val raw = withTimeoutOrNull(TIMEOUT_MS) {
            var value: String? = null
            while (value == null) {
                value = evalSync(webView, pollScript(requestId))
                if (value == null) delay(POLL_INTERVAL_MS)
            }
            value
        }
        if (raw == null) {
            Log.w(TAG, "页面内抓取超时：$method $path")
            // 超时也必须清槽：requestId 单调递增、永不复用，页面里那条 fetch 稍后
            // 仍会把整份响应写进 store[id]。而会话 WebView 是刻意保留的——每超时一次
            // 就永久泄漏一份几十到几百 KB 的周课表 JSON 在 JS 堆里（:84 的注释
            // 承诺了"连读带清"，此前只有正常读回那一支兑现了）。
            webView.post { webView.evaluateJavascript(clearScript(requestId), null) }
            return null
        }
        if (raw.startsWith(ERROR_PREFIX)) {
            Log.w(TAG, "页面内抓取失败：$method $path -> ${raw.removePrefix(ERROR_PREFIX)}")
            return null
        }
        Log.i(TAG, "← $method $path (${raw.length}B)")
        return raw
    }

    /** 抓取学年学期列表 */
    suspend fun fetchTermList(webView: WebView): List<TermOption> {
        val raw = fetch(webView, "GET", "/jwapp/sys/homeapp/api/home/kb/xnxq.do", "") ?: return emptyList()
        return runCatching {
            json.decodeFromString<XnxqResp>(raw).datas.orEmpty().mapNotNull {
                it.itemCode?.let { code -> TermOption(code, it.itemName ?: code, it.selected == true) }
            }
        }.onFailure { Log.w(TAG, "学期列表解析失败", it) }.getOrDefault(emptyList())
    }

    /**
     * 抓取「学习日程」月历（原始 JSON，含 JJR=节假日 / SKKC=本月有课日）。
     *
     * @param rq 任意一个落在目标月份内的日期（接口按月返回）
     */
    suspend fun fetchTeachingScheduleMonth(webView: WebView, rq: LocalDate): String? =
        fetch(
            webView, "GET",
            "/jwapp/sys/homeapp/api/home/teachingSchedule/list.do?rq=$rq&lxdm=student",
            "",
        )

    /** 按周抓取的完整性结果：调用方据此决定能否覆盖落库 */
    data class SemesterFetchOutcome(
        val courses: List<BuaaCourseDto>,
        /** 外壳里有 `datas` 那一格的周次数量（空课周算在内：那一格在、列表是空表 = 教务答了「这周没课」） */
        val succeededWeeks: Int,
        /**
         * 没能交出这一周课表的周次（从 1 开始），**两档都在这里面**：
         * 抓取或解析失败的、以及页面答了而那份答的外壳里没有 `datas` 那一格的（限流 / 会话半失效 ——
         * 判据本体见 `domain/importing/BuaaWeekShellPolicy.kt`）。
         *
         * 后者必须记在这里而不是记进 [succeededWeeks]：覆盖导入是「先删掉该学期再写入交回的这份」，
         * 一周没进本物又没进失败周次，就意味着那几周的既有课程被删掉而界面上念的是绿色成功。
         */
        val failedWeeks: List<Int>,
    ) {
        val isComplete: Boolean get() = failedWeeks.isEmpty()
    }

    /**
     * 抓取一个学期的课表（type=week 按周逐周请求后合并，兼容非均匀周排课）。
     * [totalWeeks] 由调用方传（教学周数）；返回当学期全部排课。
     *
     * 单周失败**不再静默丢弃**：失败周次会一并返回，因为调用方随后会做
     * "先清空该学期再写入"的覆盖导入 —— 如果按部分结果写入，
     * 没抓到的周次会被直接删掉。是否落库由调用方按 [SemesterFetchOutcome] 判断。
     *
     * T401：「页面答了、而那份答的外壳里没有 `datas` 那一格」的一周同样记进 [SemesterFetchOutcome.failedWeeks]
     * 而不是记进成功 —— 它一门课都没交回，记成成功就是上面那句话点名的静默删除。
     * 连带效果是 [MAX_CONSECUTIVE_FAILURES] 那道熔断对「会话半失效」不再隐形：改前那一周会把连败计数
     * 清零，整轮二十周可以一路「空成功」跑完而熔断一次都不响。
     */
    suspend fun fetchSemesterCourses(
        webView: WebView,
        termCode: String,
        totalWeeks: Int,
        campusCode: String = "",
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): SemesterFetchOutcome {
        val allCourses = mutableListOf<BuaaCourseDto>()
        // 上限必须与领域约束一致（此前写死 60，比 MAX_TOTAL_WEEKS 大一倍）。
        // totalWeeks 来自教务返回的学期信息，脏数据会让这里空转几十次页面内 fetch，
        // 每次都要等 TIMEOUT_MS —— 一次刷新就能卡好几分钟。
        val weeks = totalWeeks.coerceIn(1, CourseConstraints.MAX_TOTAL_WEEKS)
        var succeeded = 0
        val failed = mutableListOf<Int>()
        var consecutiveFailures = 0
        for (week in 1..weeks) {
            // 取消后立刻停止：不再发起下一周请求，已抓到的数据也不会写入
            // （落库在调用方 fetch 全部完成之后，见 BuaaLoginScreen）
            currentCoroutineContext().ensureActive()
            onProgress(week, weeks)
            val body = "termCode=$termCode&campusCode=$campusCode&type=week&week=$week"
            // 评审 P0-3（容错）：弱网抖动 / 教务瞬时超时是单周失败的主因，直接记
            // failedWeeks 会让用户面对「课表不完整」并整轮重导，所以失败后静默重试一次。
            // R5 F-38：超时与解析失败共用同一份预算，一周最多 2 次尝试。
            var parsed: BuaaScheduleResponse? = null
            // 「解得开、而外壳里没有 datas」的那一档留在手里，只为让下面那一行日志把两件事分开念：
            // 「页面没答」与「页面答了而那份答里没有课程数据」。两件事在屏上是同一句「第 N 周失败」，
            // 在本机日志里必须是两句话。
            var noDataTier: WeekShellTier? = null
            var attempt = 0
            while (parsed == null && attempt < MAX_ATTEMPTS_PER_WEEK) {
                if (attempt > 0) delay(RETRY_DELAY_MS)
                attempt++
                val shell = fetch(
                    webView, "POST",
                    "/jwapp/sys/homeapp/api/home/student/getMyScheduleDetail.do", body,
                )?.let { raw ->
                    runCatching { json.decodeFromString<BuaaScheduleResponse>(raw) }.getOrNull()
                }
                if (shell != null) {
                    // 判据本体在 `domain/importing/BuaaWeekShellPolicy.kt`（纯 JVM 内核，全仓唯一一把尺）：
                    // 只有「外壳里有 datas 那一格」才算教务答了这一周。code 缺省、或 code 是成功值，
                    // 都**不再单独放行**任何一格 —— 改前的两枚「或」支把 `{"code":null,"datas":null}`
                    // 与 `{}` 记成成功，于是那几周既没进失败周次、也没往课表里放一门课，
                    // 被覆盖导入「先删该学期再插入」静默删掉，而屏上念的是绿色成功。
                    // 反过来，`arrangedList` 是空表**不算失败**：那是合法的空课周（寒暑假 / 停课周 /
                    // 台账 #310 那一族），判成失败会同时改坏熔断与报数两头。
                    val tier = BuaaWeekShellPolicy.tier(
                        datasPresent = shell.datas != null,
                        arrangedListSize = shell.datas?.arrangedList?.size,
                        code = shell.code,
                    )
                    if (tier.importable) parsed = shell else noDataTier = tier
                }
            }
            if (parsed == null) {
                noDataTier?.let {
                    Log.w(TAG, "第 $week 周教务答了而那份答里没有课程数据（档位 $it），这一周记为失败，不进覆盖导入")
                }
                failed += week
                consecutiveFailures++
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    val remaining = (week + 1)..weeks
                    if (!remaining.isEmpty()) {
                        failed += remaining.toList()
                        Log.w(
                            TAG,
                            "连续 $consecutiveFailures 周抓取失败，中止本轮；剩余 ${remaining.count()} 周记为失败"
                        )
                    }
                    break
                }
                continue
            }
            consecutiveFailures = 0
            succeeded++
            parsed.datas?.arrangedList?.let { allCourses.addAll(it) }
        }
        return SemesterFetchOutcome(allCourses, succeeded, failed.toList())
    }

    /**
     * 抓取学期周数与开学日（getTermWeeks 每行为一周，含 startDate/endDate）。
     *
     * **这一趟有静默重试，且吃的是课表那一圈同一份预算**（T196①：`MAX_ATTEMPTS_PER_WEEK` 含首次共
     * 两次 + `RETRY_DELAY_MS`，不新开一枚预算数）。改前它只 `fetch` 一次，而课表那三趟有重试额度 ——
     * 同一枚「弱网抖动 / 教务瞬时超时」的解释在两侧不对称。
     *
     * 为什么这一趟比课表那一趟更该重试（本卡的理由，不是优化）：T180 之后这条链的下游是**闸门不是兜底**。
     * 首次导入那一路（`ui/importing/BuaaLoginScreen.kt` 的 `resolveBuaaSemesterStart`）认不出开学日就
     * 报错返回、不动课表；静默刷新那一路（`BuaaWebSession.kt` 的 `refreshSchedule`）认不出就
     * `Result.failure`。两边都没有「拿别的日期顶一下」那一手 —— 所以**一次瞬时抖动 = 整趟导入失败**，
     * 而且用户在 `fetchState` 上看着它跑完二十周才发现（T196② 之前那一轮空转还是白花的时间）。
     *
     * 与课表那一圈唯一多出来的一手：重试之前先 `ensureActive()`。这一趟跑在导入协程里，
     * 用户取消或页面离开时不该再多等那 [RETRY_DELAY_MS] 毫秒。（课表那一圈没有这一手 ——
     * 那是另一格的账，本卡不顺手修，也不照抄它的缺。）
     *
     * 运行期「抖一次真能救回来」这一档**没验**：`fetch` 要 WebView 页面上下文，JVM 造不出、
     * 本卡零 adb ⇒ 由静态守卫 `SemesterInfoRetryGuardTest` 钉形状，实屏归编排者装机。
     */
    suspend fun fetchSemesterInfo(
        webView: WebView,
        termCode: String,
    ): SemesterInfo {
        // termCode 会被拼进页面内 JS 的 URL 里。虽然来源是教务服务端返回的 itemCode，
        // 仍按白名单过滤一次：畸形取值不应该有机会改变脚本语义。
        val safeTermCode = termCode.takeIf { TERM_CODE_PATTERN.matches(it) }
            ?: return SemesterInfo(20, null, SemesterInfoReason.InvalidTermCode)
        // 弱网抖动 / 教务瞬时超时是这一趟失败的主因（见上面的 KDoc），一次超时就判「这个学期没有开学日」
        // 等于把整趟导入判死。语义与课表那一圈同一份预算：含首次共 MAX_ATTEMPTS_PER_WEEK 次尝试，
        // 两次之间退避 RETRY_DELAY_MS；一圈只有这一枚 fetch，不长第二本预算。
        var raw: String? = null
        var attempt = 0
        while (raw == null && attempt < MAX_ATTEMPTS_PER_WEEK) {
            if (attempt > 0) {
                currentCoroutineContext().ensureActive()
                delay(RETRY_DELAY_MS)
            }
            attempt++
            raw = fetch(
                webView, "GET",
                "/jwapp/sys/homeapp/api/home/getTermWeeks.do?termCode=$safeTermCode", "",
            )
        }
        // 重试之后仍然没有回音：这一档交出去的是「页面没答」而不是「这个学期没有开学日」。
        val response = raw ?: return SemesterInfo(20, null, SemesterInfoReason.PageUnanswered)
        return runCatching {
            val resp = json.decodeFromString<BuaaTermWeeksResponse>(response)
            val weeks = resp.datas.orEmpty()
            if (weeks.isEmpty()) return SemesterInfo(20, null, SemesterInfoReason.EmptyWeekList)
            // 第 1 周只认两条判据：serialNumber 最小、startDate 最早（判据本体 resolveBuaaFirstWeek）。
            // 两条判据分家、或各自被两行并列占着时判不出 ⇒ Undetermined，这一格把 firstWeekMonday
            // 回成 null，让下游两路各自的诚实失败链去拦（首次导入那一路的 :169 那一枚闸门 /
            // 静默刷新那一路的 :400-404）。
            // 旧写法 `minByOrNull { it.serialNumber ?: Int.MAX_VALUE } ?: weeks.first()` 只实现了第一条，
            // 而第二枚兜底是**不可达支**（上一行已保证 weeks 非空）；真正会走到的退化是 serialNumber
            // 全为 null 时键全体并列，minByOrNull 于是回**列表首行** —— 教务只是没给序号，
            // 旧代码却把首行当第 1 周用，而首行那枚串合法可 parse，下游拦不住「合法但错周」（T193）。
            val firstWeek = resolveBuaaFirstWeek(weeks.map { BuaaWeekRow(it.serialNumber, it.startDate) })
            when (firstWeek) {
                is BuaaFirstWeek.Resolved -> {
                    Log.i(
                        TAG,
                        "getTermWeeks 共 ${weeks.size} 周，第 1 周按 ${firstWeek.via} 定为「${firstWeek.startDate}」",
                    )
                    SemesterInfo(weeks.size, firstWeek.startDate)
                }
                is BuaaFirstWeek.Undetermined -> {
                    Log.w(
                        TAG,
                        "getTermWeeks 共 ${weeks.size} 周，判不出第 1 周（${firstWeek.reason}：${firstWeek.detail}），" +
                            "firstWeekMonday 回 null，未猜任何日期",
                    )
                    SemesterInfo(weeks.size, null, SemesterInfoReason.FirstWeekUndetermined)
                }
            }
            // 整块解不开（不是预期的 JSON 形状）也是**一档原因**，不是「没有学期信息」这一句话：
            // 页面答了而那份答读不出来，与「页面没答」在用户句子里必须是两句话（T196④）。
        }.getOrDefault(SemesterInfo(20, null, SemesterInfoReason.WeekListUnreadable))
    }

    /** 在 UI 线程执行一个脚本并同步取回字符串结果（Promise 不可用，只能读同步值） */
    private suspend fun evalSync(webView: WebView, script: String): String? =
        suspendCancellableCoroutine { cont ->
            webView.post {
                webView.evaluateJavascript(script) { value ->
                    if (cont.isActive) cont.resume(decodeJsString(value))
                }
            }
        }

    /**
     * 启动请求：结果写入全局槽位；出错时写入带 [ERROR_PREFIX] 的哨兵。
     *
     * 可见性为 internal 是刻意的：注入脚本没有设备就跑不了，但它同时定义了
     * 原生 ↔ 页面的协议。JVM 侧至少要把「绝对 origin + 协议标识」钉住
     * （R5 §8-4）。
     */
    internal fun startScript(method: String, path: String, body: String, requestId: String): String {
        val methodJs = if (method == "POST") "'POST'" else "'GET'"
        val bodyDeclaration = if (body.isBlank()) "" else "init.body = '${esc(body)}';"
        val url = API_ORIGIN + path
        return """
            (function() {
              var store = $RESULT_STORE = $RESULT_STORE || {};
              var id = '$requestId';
              if (location.origin !== '$API_ORIGIN') {
                store[id] = '$ERROR_PREFIX' + 'unexpected origin ' + location.origin;
                return;
              }
              var init = {
                method: $methodJs,
                headers: {'Content-Type': 'application/x-www-form-urlencoded', 'X-Requested-With': 'XMLHttpRequest'},
                credentials: 'include'
              };
              $bodyDeclaration
              fetch('${esc(url)}', init).then(function(r) {
                return r.text();
              }).then(function(text) {
                store[id] = text;
                console.log('BUA_FETCH_OK ' + id + ' status_len=' + text.length);
              }).catch(function(e) {
                store[id] = '$ERROR_PREFIX' + String(e);
                console.log('BUA_FETCH_ERR ' + id + ' ' + String(e));
              });
            })()
        """.trimIndent()
    }

    /** 轮询脚本：有结果就「读并清除」，没有则返回 null */
    private fun pollScript(requestId: String): String = """
        (function() {
          var store = $RESULT_STORE;
          if (!store) return null;
          var value = store['$requestId'];
          if (value === undefined) return null;
          delete store['$requestId'];
          return value;
        })()
    """.trimIndent()

    /** 超时后的清槽脚本：页面里那条 fetch 迟到写入时不再留下永久槽位 */
    private fun clearScript(requestId: String): String = """
        (function() {
          var store = $RESULT_STORE;
          if (store) delete store['$requestId'];
        })()
    """.trimIndent()

    /** evaluateJavascript 结果会被 JSON 再编码一次：去外层引号 + 反转义 */
    private fun decodeJsString(value: String?): String? {
        if (value == null || value == "null" || value == "undefined") return null
        return runCatching {
            val arr = org.json.JSONArray("[$value]")
            if (arr.length() == 1) arr.getString(0) else value
        }.getOrDefault(value)
    }

    private fun esc(s: String): String =
        s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "").replace("\r", "")
}
