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
     * 剩下的周次只会重复超时。剩余周次一律记为失败，调用方据此拒绝覆盖既有课表。
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

    /** 学期信息：周数 + 第1周周一（开学日） */
    data class SemesterInfo(val totalWeeks: Int, val firstWeekMonday: String?)

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
        /** 成功返回并解析出数据的周次数量 */
        val succeededWeeks: Int,
        /** 抓取或解析失败的周次（从 1 开始） */
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
            var attempt = 0
            while (parsed == null && attempt < MAX_ATTEMPTS_PER_WEEK) {
                if (attempt > 0) delay(RETRY_DELAY_MS)
                attempt++
                parsed = fetch(
                    webView, "POST",
                    "/jwapp/sys/homeapp/api/home/student/getMyScheduleDetail.do", body,
                )?.let { raw ->
                    runCatching { json.decodeFromString<BuaaScheduleResponse>(raw) }.getOrNull()
                        // 教务限流/会话半失效时会返回 HTTP 200 的 {"code":"-1","datas":null}：
                        // JSON 解得动但没有数据，若记成功会让 isComplete 通过覆盖导入闸门，
                        // 把那些周的课程直接删没。判据与 BuaaApi.fetchSchedule 同口径。
                        ?.takeIf { it.datas != null || it.code == null || it.code == "0" }
                }
            }
            if (parsed == null) {
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

    /** 抓取学期周数与开学日（getTermWeeks 每行为一周，含 startDate/endDate） */
    suspend fun fetchSemesterInfo(
        webView: WebView,
        termCode: String,
    ): SemesterInfo {
        // termCode 会被拼进页面内 JS 的 URL 里。虽然来源是教务服务端返回的 itemCode，
        // 仍按白名单过滤一次：畸形取值不应该有机会改变脚本语义。
        val safeTermCode = termCode.takeIf { TERM_CODE_PATTERN.matches(it) }
            ?: return SemesterInfo(20, null)
        val raw = fetch(webView, "GET", "/jwapp/sys/homeapp/api/home/getTermWeeks.do?termCode=$safeTermCode", "")
            ?: return SemesterInfo(20, null)
        return runCatching {
            val resp = json.decodeFromString<BuaaTermWeeksResponse>(raw)
            val weeks = resp.datas.orEmpty()
            if (weeks.isEmpty()) return SemesterInfo(20, null)
            // 第1周（serialNumber 最小 / startDate 最早）的 startDate 即开学日
            val first = weeks.minByOrNull { it.serialNumber ?: Int.MAX_VALUE } ?: weeks.first()
            SemesterInfo(weeks.size, first.startDate?.substringBefore(" "))
        }.getOrDefault(SemesterInfo(20, null))
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

    /** evaluateJavascript 结果会被 JSON 再编码一次：去外层引号 + 反转义 */
    private fun decodeJsString(value: String?): String? {
        if (value == null || value == "null") return null
        return runCatching {
            val arr = org.json.JSONArray("[$value]")
            if (arr.length() == 1) arr.getString(0) else value
        }.getOrDefault(value)
    }

    private fun esc(s: String): String =
        s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "").replace("\r", "")
}
