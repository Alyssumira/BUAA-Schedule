package com.buaa.schedule.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 假期/调休标注那条数据链的**接线**守卫（T60）。
 *
 * 用户看到的「节假日没有标注出来」，四种病因里三种都长得一模一样：界面没问题、
 * 解析没问题、缓存没问题，坏的是"这一轮压根没去抓"。而"没去抓"这件事在代码里
 * 有两种写法，只看一眼是分不出来的：
 * - 判据漏了一档（旧写法：只在回首页时抓一次，会话之后出现了也没人再敲門）；
 * - 判据走到了、但那一档不吭声（旧写法：`?: return@launch`，与"抓到了但没数据"同形）。
 *
 * 所以这里钉的是**形状**，不是行为（行为在 `SpecialDayRefreshPolicyTest` 里逐支打表）：
 * 1. 判据内核保持零 import 的纯 JVM 形态（引了 android/java.time 的那一档就测不到了）；
 * 2. 会话刚建立那一声敲门真的接在补抓上，而且接在 `BuaaWebSession.retain()` 这个唯一咽喉上
 *    （登录页五处上缴 + 冷启动 Cookie 恢复都汇到那一处，所以全仓只需要一行发射）；
 * 3. `runSpecialDayFetch` 里每一个提前 return 之前都有一行取证；
 * 4. 闸门领还得回来（CAS + finally），不许长出第二颗裸 Boolean；
 * 5. 首页两条线都在：回首页一次、屏上月份变了再一次；
 * 6. TTL 判据只剩内核一份，读文件那侧只报月龄。
 *
 * 手法照抄 `ScanSilentBranchGuardTest` / `SpocSignInEntryWiringGuardTest`：读源码文本、
 * **找不到文件锚点就抛**（静默跳过的守卫比没有守卫更糟）、匹配前先抹注释
 * （内核文件的 KDoc 里就写着「零 java.time import」这类禁令本身）。
 */
class SpecialDayRefreshWiringGuardTest {

    // ---- ① 内核纯度 ----

    /** 判据内核保持纯 JVM：零 import、零设备/时钟口 */
    @Test
    fun refreshKernelStaysPureJvm() {
        val raw = readMainSource(POLICY_FILE)
        val code = blankComments(raw)
        val imports = code.lines().filter { it.trim().startsWith("import ") }
        assertTrue("$POLICY_FILE 里出现了 import，这段判据就到不了 JVM：\n$imports", imports.isEmpty())
        for (
            banned in listOf(
                "android.", "androidx.", "java.time", "YearMonth", "LocalDate",
                "System.currentTimeMillis", "SystemClock", "Date(", "Instant", "BuaaWebSession",
            )
        ) {
            assertFalse("判据本体自己去碰了设备/日历/会话（$banned）—— 这些事实必须是参数", code.contains(banned))
        }
        // 空白判据文件等于什么都没扫（本仓的守卫一律要求命中数 > 0）
        assertTrue("内核文件是空的？", code.length > 1_000)
        assertTrue("TTL 不在内核里：", code.contains("SpecialDayCacheStaleAfterDays"))
        // 四档停法都留在内核的枚举里：少一档就意味着有人在调用点各写了一份 if
        assertEquals("四档停法少了一档：", 4, SpecialDayFetchSkip.entries.size)
    }

    // ---- ② 会话刚建立那一声敲门 ----

    /**
     * `BuaaWebSession` 在会话真的可用那一刻发信号，发在 [BuaaWebSession.retain] 函数体内。
     *
     * 咽喉只有一处：`fetchTeachingSchedule` 要同时过 `sessionWebView != null` 与 `hasSession()`，
     * 而这两样只在 retain 里被赋值 —— 登录页那五处 `retain(...)` 与冷启动 Cookie 恢复成功后的
     * 那一处全汇到它。发射点若从 1 处变成 2 处，就说明有人在别处另造了一份"会话可用"的真相。
     */
    @Test
    fun sessionBecomingAvailableIsBroadcastFromTheSingleChokePoint() {
        val code = blankComments(readMainSource(WEB_SESSION_FILE))
        val emit = Regex("""_sessionRetained\.tryEmit\(Unit\)""").findAll(code).count()
        assertEquals("会话上缴的信号发射点数应当恰好一处（多了就是有人复刻了第二份真相）：", 1, emit)
        val retain = balancedBlock(code, "fun retain(webView: WebView")
        assertTrue("发射点不在 retain 体内（那就是别的时机在敲门）：", retain.contains("_sessionRetained.tryEmit(Unit)"))
        assertTrue("retain 里 lastUrl 的赋值必须排在发射之前（订阅者回头问 hasSession 要拿得到 true）：",
            retain.indexOf("lastUrl = webView.url") < retain.indexOf("_sessionRetained.tryEmit(Unit)"))
        // 订阅侧：ViewModel 收这一路并据此补抓
        val vm = blankComments(readMainSource(VIEW_MODEL_FILE))
        assertTrue(
            "ViewModel 不再订阅 sessionRetained —— 「会话刚出现」那一档又没人敲門了（本卡的核心那条线）：",
            vm.contains("BuaaWebSession.sessionRetained.collect"),
        )
        assertTrue(
            "收到信号之后没有真的去补抓：",
            balancedBlock(vm, "sessionRetained.collect").contains("refreshSpecialDays(SpecialDayTrigger.SessionReady)"),
        )
    }

    // ---- ③ 不再静默：每条提前退路都留一行 ----

    /**
     * `runSpecialDayFetch` 的四条结局（三档停 + 一档成功）各自留一行 `Log.i`，
     * 而且这一页不许出现 `Log.d`/`Log.v`（用户那台机器读不到）或 debug-only 门禁
     * （这些行就是给 release 装机读账用的，包进 `BuildConfig.DEBUG` 等于删掉）。
     */
    @Test
    fun everyEarlyReturnLeavesAnInfoLine() {
        val vm = blankComments(readMainSource(VIEW_MODEL_FILE))
        val body = balancedBlock(vm, "private suspend fun runSpecialDayFetch")
        // 三处 Log.i 盖住四种结局：Skip 那一处服务三种停法（措辞全在内核里分档）
        assertEquals("留痕处数应当恰好三处（多一处就有分支在自己拼话，少一处就有静默支路）：", 3, occurrences(body, "Log.i("))
        // 词边界而不是子串：`returned = ...` 那个具名实参里就含 "return"
        val earlyExits = Regex("""\breturn\b""").findAll(body).count()
        assertEquals("提前退路只能有一处（接口空手回那一档），且它前面必须已经留过痕：", 1, earlyExits)
        val nullBranch = body.substringAfter("if (fetched == null) {").substringBefore("return")
        assertTrue("空手回那一档没有说话（旧写法在这里是 `?: return@launch`）：\n$nullBranch", nullBranch.contains("specialDaySkipLog"))
        assertTrue("措辞必须出自内核那份，不许就地拼字符串：", occurrences(body, "specialDaySkipLog(") >= 2)
        // 停在哪一档由判据给，日志只负责念出来
        assertTrue("Skip 那一档没走内核给的措辞：", body.contains("specialDaySkipLog(decision.reason, decision.months, trigger)"))
        for (banned in listOf("Log.d(", "Log.v(", "Log.e(", "Log.w(")) {
            assertEquals("取证行用了 $banned（Info 以下在读不到，级别漂过就红）：", 0, occurrences(vm, banned))
        }
        // refreshSpecialDays 本体（领闸门那一层）也不许留裸退路
        val outer = balancedBlock(vm, "internal fun refreshSpecialDays")
        assertEquals("闸门那层不该有第二条退路（抢到/没抢到都进协程，由内核分档）：",
            0, Regex("""\breturn\b""").findAll(outer).count())
        assertFalse("旧写法那份 `?: return@launch` 在这一条链上回来了：",
            body.contains("return@launch") || outer.contains("return@launch"))
        assertEquals("这一页不许出现 debug-only 输出（包了就等于 release 里查不到账）：", 0, occurrences(vm, "BuildConfig.DEBUG"))
        // 级别扫查要有靶子：一行 Log 都没扫到就是解析器或路径坏了
        assertTrue("ViewModel 里一行 Log. 都没扫到：", occurrences(vm, "Log.") > 0)
    }

    // ---- ④ 闸门领还得回来 ----

    /** 单飞闸门：领取是 CAS、归还在 finally、且只归还自己领到的那一份；不许有裸 Boolean */
    @Test
    fun inFlightLatchIsClaimedByCasAndReleasedInFinally() {
        val vm = blankComments(readMainSource(VIEW_MODEL_FILE))
        val outer = balancedBlock(vm, "internal fun refreshSpecialDays")
        assertTrue("闸门不是 StateFlow（界面读不到「这一轮还在跑」，且 CAS 用不上）：",
            vm.contains("private val specialDayFetchInFlight = MutableStateFlow(false)"))
        assertFalse("长出了裸 Boolean 的闸门（T59 那类只关不开的开关就从这里复活）：",
            vm.contains("private var specialDayFetching"))
        assertTrue("领取没走 compareAndSet（两路会挤在同一份 false 上判通过）：",
            outer.contains("specialDayFetchInFlight.compareAndSet(expect = false, update = true)"))
        val finallyBlock = outer.substringAfter("finally {")
        assertTrue("归还排到了 finally 之外（抛异常那一趟之后永远不再补抓）：",
            finallyBlock.contains("if (claimed) specialDayFetchInFlight.value = false"))
        assertTrue("闸门被抢走的那一路去清了别人的闸门（等于把正在跑的那趟的门踹开）：",
            finallyBlock.contains("if (claimed)"))
        assertEquals("闸门字段的写点应当恰好两处（CAS 领取 + finally 归还）：",
            2, occurrences(vm, "specialDayFetchInFlight.value") + occurrences(vm, "specialDayFetchInFlight.compareAndSet"))
        // 判据拿到的 fetchInFlight 必须是这颗闸门的反面，不能另数一份
        assertTrue("内核拿到的 fetchInFlight 不是这颗闸门：",
            vm.contains("fetchInFlight = !claimed"))
    }

    // ---- ⑤ 屏上月份这条线 ----

    /** 首页两条触发线：回首页一次、浏览月份变了再一次；后者必须挂在组合期之外（LaunchedEffect） */
    @Test
    fun homeTriggersTheRefreshOnResumeAndOnVisibleMonthChange() {
        val home = blankComments(readMainSource(HOME_SCREEN_FILE))
        val onResume = balancedBlock(home, "LaunchedEffect(Unit)")
        assertTrue("回首页不再触发补抓（那本来就是旧写法唯一的一档，别把它一起拆了）：",
            onResume.contains("viewModel.refreshSpecialDays()"))
        assertTrue("屏上月份上报不见了（跨月的那一周就再也没有标注）：",
            home.contains("viewModel.onSpecialDayVisibleMonths("))
        // 键必须带上浏览状态：漏 browseWeek 就是翻出跨月那一周不补抓
        val effect = Regex("""LaunchedEffect\(([^)]*)\)\s*\{\s*viewModel\.onSpecialDayVisibleMonths""").find(home)
        check(effect != null) { "月份上报没有包在 LaunchedEffect 里（组合期里直接调 VM 会每帧敲一次门）：$home" }
        val monthsKey = balancedBlock(home, "val specialDayMonths = remember(")
        for (key in listOf("browseWeek", "browseDate", "state.currentWeek", "state.semester")) {
            assertTrue("屏上月份的 remember 键漏了 $key（那一维变了却不重算）：\n$monthsKey", monthsKey.contains(key))
        }
        // VM 那一头：月份集合没变就不许再敲门（翻同一周的重组不该变成一串请求）
        val vm = blankComments(readMainSource(VIEW_MODEL_FILE))
        val setter = balancedBlock(vm, "fun onSpecialDayVisibleMonths")
        assertTrue("月份没变也会敲一次门（翻同一周就会刷出一串请求）：\n$setter",
            setter.contains("if (keys == specialDayVisibleMonths.value) return"))
        assertTrue("屏上月份没进判据（那就是白报）：", vm.contains("visibleMonths = specialDayVisibleMonths.value.toList()"))
    }

    // ---- ⑥ TTL 判据只有一份 ----

    /** 读文件那侧只报月龄；"多大算旧"只许写在内核里 */
    @Test
    fun cacheReportsAgeAndOnlyTheKernelOwnsTheTtl() {
        val cache = blankComments(readMainSource(CACHE_FILE))
        assertTrue("SpecialDayCache 不再提供月龄（判据就只能自己去碰文件系统）：",
            cache.contains("suspend fun cachedMonthAges(context: Context): Map<YearMonth, Long>"))
        assertFalse("TTL 判据回流到了缓存层（第二份真相）：", cache.contains("STALE_AFTER_DAYS"))
        assertFalse("缓存层又开始自己判过期：", cache.contains("staleMonths"))
        assertTrue("月龄没夹下限（mtime 在未来会算出负数，那一月就永远不再补抓）：",
            cache.contains("coerceAtLeast(0L)"))
        val policy = blankComments(readMainSource(POLICY_FILE))
        assertTrue("内核里的 TTL 与旧的 7 天口径走岔了：", policy.contains("SpecialDayCacheStaleAfterDays = 7L"))
    }

    // ---- 工具：读源码、抹注释、配平取块 ----

    private fun readMainSource(relativeFromJava: String): String {
        val file = File(findMainJavaDir(), relativeFromJava)
        assertTrue("找不到 ${file.path}：文件挪过家的话这条守卫要跟着改路径", file.isFile)
        return file.readText()
    }

    /** 从 [signature] 之后第一个 `{` 起配平到对应右括号（含）；找不到锚点就抛，静默跳过等于没有守卫 */
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

    private fun blankComments(source: String): String {
        val out = StringBuilder(source)
        var block = out.indexOf("/*")
        while (block >= 0) {
            val end = out.indexOf("*/", block + 2)
            if (end < 0) break
            out.replace(block, end + 2, " ")
            block = out.indexOf("/*")
        }
        val text = out.toString()
        return text.lines().joinToString("\n") { line ->
            val slash = line.indexOf("//")
            if (slash >= 0) line.substring(0, slash) else line
        }
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

    /** 单测的 cwd 是 :app 模块目录，也可能是仓库根：两种布局都试，全落空就抛 */
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

    private companion object {
        const val POLICY_FILE = "com/buaa/schedule/ui/SpecialDayRefreshPolicy.kt"
        const val VIEW_MODEL_FILE = "com/buaa/schedule/ui/ScheduleViewModel.kt"
        const val HOME_SCREEN_FILE = "com/buaa/schedule/ui/home/HomeScreen.kt"
        const val WEB_SESSION_FILE = "com/buaa/schedule/data/import/BuaaWebSession.kt"
        const val CACHE_FILE = "com/buaa/schedule/data/local/SpecialDayCache.kt"
    }
}
