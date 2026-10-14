package com.buaa.schedule.data.local

import com.buaa.schedule.data.import.TeachingScheduleParser
import com.buaa.schedule.ui.SpecialDayCacheAge
import com.buaa.schedule.ui.SpecialDayDecision
import com.buaa.schedule.ui.SpecialDayFetchSkip
import com.buaa.schedule.ui.SpecialDayMonth
import com.buaa.schedule.ui.SpecialDayMonthWrite
import com.buaa.schedule.ui.SpecialDayTrigger
import com.buaa.schedule.ui.SpecialDayWriteState
import com.buaa.schedule.ui.decideSpecialDayFetch
import com.buaa.schedule.ui.isWritten
import com.buaa.schedule.ui.specialDayFetchDoneLog
import com.buaa.schedule.ui.specialDayWriteFailures
import com.buaa.schedule.ui.specialDayWriteHead
import com.buaa.schedule.ui.specialDayWriteFailureLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T369②：「落盘结论 × 月份名册 ⇒ 这一句该念什么」的表驱动单测（在册 #336 ＋ 任务表 T248-C1 那格的判据半边）。
 *
 * ## 这张表能钉什么、钉不了什么
 * 能钉的是**纯的那一半**：[specialDayFetchDoneLog] / [specialDayWriteHead] / [specialDayWriteFailures]
 * 全是 `SpecialDayRefreshPolicy` 里的纯 JVM 判据 —— 落盘结论由调用点折算成 [SpecialDayWriteState] 递进来，
 * 函数体不读盘、不读钟、不读 `Context`。改前那一头压根没有这一维输入（`put` 返回 `Unit`），
 * 所以「接口回了 200 而盘上只有半截」与「落上了」在代码里长同一个样子，也就无从打表。
 *
 * **钉不了的另外写**：原子改名、`.part` 命名、`delete()` 返回值这些 IO 形状天生不是纯判据，
 * 本仓铁律是"非纯函数只有守卫这一条落点"，所以它们由 `SpecialDayCacheAtomicWriteGuardTest` 钉（同卡 ③）。
 * 这里不许为了多几格绿而把 IO 伪装成"判据"—— 那是把 #336 换成另一种查不出。
 *
 * ## 六组格（每格都有独立判别力，没有一格是另一格的复述）
 * - **甲** 句头三档（全落上 / 部分没落上 / 一枚都没落上）＋ 空名册那一档念窄的那句。
 *   N 臂要红的就是这里：把"有失败也照念已更新"改回去，甲组的第 2、3、4 格同时红。
 * - **乙** 名册只认档名：四档里只有 WRITTEN 算落上，顺序与递进来的名册一致。
 * - **丙** 「未落盘月份」那一栏逐枚点名，含"改名那手平静返回假、压根没有异常"那一格
 *   （那一档要是被折成"无"，读日志的人就以为全落上了）。
 * - **丁** 整句：接口那一头（未返回月份）与盘这一头（未落盘月份）**两栏互不顶替** ——
 *   同一个月"回来了但没落上"与"压根没回来"是两件事，并成一栏就是一句新谎。
 * - **戊** 端到端"多久不再补抓"：走 [decideSpecialDayFetch] 的三档月龄（1 / 7 / 8），
 *   把回执里那句"7 天整之内一次都不补抓"钉成刻度，而不是只说"会出问题"。
 *   另加一格甲档的**自愈**：写失败时正式件没被碰过 ⇒ 上一份好内容的旧 mtime 照旧说话 ⇒ 下一轮就补。
 * - **己** 取证格（⓪ 段那两条读源码读出来的真结论，落成可执行的）：半截 JSON 时
 *   [TeachingScheduleParser.parse] **接住**（语法不合法 ⇒ 空列表，loadAll 跳过这一枚文件），
 *   而"语法合法而形状怪"时它**抛**（`walk` 不在那手 `runCatching` 里）⇒ 同一条链对"内容坏"有两档结论。
 *   这两格是 SpecialDayCache 那段假注释被订正的**依据**；哪天有人把 parse 的覆盖面改了，
 *   这里红 = 那段注释也要同批改，不许只动一边。
 *
 * ⚠️ 全文件不调任何取钟口、不读文件系统、零 android import（`TeachingScheduleParser` 那边只有
 * kotlinx.serialization 与 java.time，都在 JVM 单测面里跑得动）。
 */
class SpecialDayCacheWriteOutcomeTableTest {

    private val now = month(2026, 9)
    private val nextMonth = month(2026, 10)
    private val farMonth = month(2026, 11)

    /** 甲：句头三档 + 空名册那一档 */
    @Test
    fun headlineRefusesToClaimUpdatedWhenNothingLanded() {
        val rows = listOf(
            Row(
                "两枚都落上",
                listOf(write(now), write(nextMonth)),
                "假期标注已更新",
            ),
            Row(
                "一枚落上一枚没落上",
                listOf(write(now), write(nextMonth, SpecialDayWriteState.TEMP_WRITE_FAILED, "IOException")),
                "假期标注部分未落盘",
            ),
            Row(
                "一枚都没落上（三档失败各一枚）",
                listOf(
                    write(now, SpecialDayWriteState.TEMP_WRITE_FAILED, "IOException"),
                    write(nextMonth, SpecialDayWriteState.PROMOTION_FAILED),
                    write(farMonth, SpecialDayWriteState.CACHE_DIR_UNREACHABLE),
                ),
                "假期标注未落盘",
            ),
            Row(
                "名册是空的（本轮一枚结论都没交回来）⇒ 念窄的那句",
                emptyList(),
                "假期标注未落盘",
            ),
        )
        val problems = rows.filter { row -> specialDayWriteHead(row.writes) != row.expected }
            .map { row ->
                row.label + "：实到「" + specialDayWriteHead(row.writes) + "」／写死「" + row.expected + "」"
            }
        assertTrue(
            "句头三档判红了（在册 " + rows.size + " 格）。这一格钉的是 #336 的正身：改前 `put` 交不回结论，" +
                "句头只有一档、永远念「已更新」，于是「接口回了而盘上是半截」与「落上了」在 logcat 里同形。" +
                "逐格：" + problems.joinToString(" ／ "),
            problems.isEmpty(),
        )
    }

    /** 乙：名册只认档名 —— 四档里只有 WRITTEN 算落上，且失败档一枚都不许丢 */
    @Test
    fun failureRosterRecognisesEveryTierExceptTheWrittenOne() {
        val all = listOf(
            write(now),
            write(nextMonth, SpecialDayWriteState.TEMP_WRITE_FAILED, "IOException"),
            write(farMonth, SpecialDayWriteState.PROMOTION_FAILED, "SecurityException"),
            write(month(2027, 1), SpecialDayWriteState.CACHE_DIR_UNREACHABLE),
        )
        val failures = specialDayWriteFailures(all)
        assertEquals(
            "四档名册里「没落上」的应当恰是三枚（实到 " + failures.size + "）—— " +
                "少一枚就是有一档失败被折成已落盘，而那正是 #336 的形状",
            3,
            failures.size,
        )
        assertEquals(
            "失败档必须逐枚点名且顺序照名册：",
            listOf(nextMonth, farMonth, month(2027, 1)),
            failures.map { it.month },
        )
        assertEquals(
            "WRITTEN 那一枚不许被算进失败名册：",
            false,
            failures.any { it.state == SpecialDayWriteState.WRITTEN },
        )
        assertEquals(
            "isWritten 只许认 WRITTEN（四档逐枚）：",
            listOf(true, false, false, false),
            all.map { it.isWritten },
        )
    }

    /** 丙：未落盘那一栏的措辞 —— 含"改名平静返回假、连异常都没有"那一格 */
    @Test
    fun failureColumnNamesEachMonthWithItsStageAndOptionalCause() {
        assertEquals(
            "一枚都没失败时那一栏要念「无」，留空读起来像掉了字：",
            "无",
            specialDayWriteFailureLabel(emptyList()),
        )
        assertEquals(
            "带异常类名那一档：",
            "2026-10(临时件没写完整：IOException)",
            specialDayWriteFailureLabel(
                listOf(write(nextMonth, SpecialDayWriteState.TEMP_WRITE_FAILED, "IOException")),
            ),
        )
        assertEquals(
            "改名那手平静返回假（没有异常）时**不许整栏消失**，档名还得说得出：",
            "2026-11(改名没被接受)",
            specialDayWriteFailureLabel(listOf(write(farMonth, SpecialDayWriteState.PROMOTION_FAILED))),
        )
        assertEquals(
            "多枚失败逐枚点名、用 / 连：",
            "2026-09(缓存目录立不起来)/2026-10(临时件没写完整：IOException)",
            specialDayWriteFailureLabel(
                listOf(
                    write(now, SpecialDayWriteState.CACHE_DIR_UNREACHABLE),
                    write(nextMonth, SpecialDayWriteState.TEMP_WRITE_FAILED, "IOException"),
                ),
            ),
        )
    }

    /** 丁：整句里接口那一头与盘这一头两栏互不顶替 */
    @Test
    fun doneLogKeepsInterfaceColumnAndDiskColumnApart() {
        val line = specialDayFetchDoneLog(
            requested = listOf(now, nextMonth, farMonth),
            returned = listOf(now, nextMonth),
            deferred = emptyList(),
            writes = listOf(write(now), write(nextMonth, SpecialDayWriteState.PROMOTION_FAILED)),
            totalAnnotations = 7,
            trigger = SpecialDayTrigger.SessionReady,
        )
        assertTrue("句头没跟着落盘结论改口（一枚没落上也念「已更新」就是假话）：$line", line.startsWith("假期标注部分未落盘"))
        assertTrue("接口那一栏要照常点名没回来的月份（2026-11 压根没回来）：$line", line.contains("未返回月份=2026-11"))
        assertTrue("盘这一栏只写 2026-10，不许把「没回来」那枚也算成没落盘：$line", line.contains("未落盘月份=2026-10(改名没被接受)"))
        assertTrue("落盘那一栏只写落上的：$line", line.contains("落盘月份=2026-09"))
        assertFalse("两栏并成一栏了（未落盘里冒出 2026-11）：$line", line.contains("未落盘月份=2026-10(改名没被接受)/2026-11"))
        // 在册那五栏一字不改（SpecialDayRefreshPolicyTest 的两格按它们断言，本卡只往里加栏、不搬不删）
        for (kept in listOf("触发=会话刚建立", "请求月份=2026-09/2026-10/2026-11", "返回月份=2026-09/2026-10", "顺延月份=无", "标注累计=7 条")) {
            assertTrue("在册那一栏被本卡搬走了：$kept ｜ $line", line.contains(kept))
        }
    }

    /** 戊：端到端"多久"的刻度 + 甲档失败后的自愈路径（走 decide 那枚在册判据） */
    @Test
    fun freshnessWindowIsExactlySevenDaysAndFailedWriteSelfHealsNextTrigger() {
        // 改前那枚"半截 + 新 mtime"在名册里的形状：一枚月龄 1 天的**正常新鲜缓存**。
        // 这一格钉的就是"名册没有内容档"的后果：判据看见 1 天 ⇒ 判新鲜 ⇒ 一个都不补。
        val halfWritten = decide(cached = listOf(SpecialDayCacheAge(now, 1L), SpecialDayCacheAge(nextMonth, 1L)))
        assertEquals(
            "半截文件（月龄 1 天）在判据那一头被判成需要补抓了？改前它判的可是「新鲜」，" +
                "这一格钉的是「7 天盲区」成立的前提",
            SpecialDayFetchSkip.CacheFresh,
            (halfWritten as SpecialDayDecision.Skip).reason,
        )
        // 刻度两端：第 7 天当天仍算新鲜（向上取整后 ageDays<=7），第 8 天才过期 ⇒ 盲区恰 7 天整
        val daySeven = decide(cached = listOf(SpecialDayCacheAge(now, 7L), SpecialDayCacheAge(nextMonth, 7L)))
        assertTrue(
            "月龄 7 天那一档被判成过期了（口径应写死为 <=7 新鲜，第 8 天才过期）：$daySeven",
            daySeven is SpecialDayDecision.Skip && (daySeven as SpecialDayDecision.Skip).reason == SpecialDayFetchSkip.CacheFresh,
        )
        val dayEight = decide(cached = listOf(SpecialDayCacheAge(now, 8L), SpecialDayCacheAge(nextMonth, 8L)))
        dayEight as SpecialDayDecision.Fetch
        assertEquals(
            "月龄 8 天那一档没被排进补抓（盲区就不是 7 天整了）：",
            listOf(now, nextMonth),
            dayEight.months,
        )
        // 甲档的自愈：写失败时正式件没被碰过 ⇒ 上一份好内容的**旧 mtime** 照旧说话 ⇒ 下一轮当场补抓
        val staleAfterFailedWrite = decide(cached = listOf(SpecialDayCacheAge(now, 9L), SpecialDayCacheAge(nextMonth, 1L)))
        staleAfterFailedWrite as SpecialDayDecision.Fetch
        assertEquals(
            "写失败留下的那份 9 天大的旧缓存没有被排进补抓 ⇒ 甲档「下一次触发就自愈」那句是假话：",
            listOf(now),
            staleAfterFailedWrite.months,
        )
        // 对照：没有文件（改前的 clear/首装形状）—— 一枚都不新鲜
        (decide(cached = emptyList()) as SpecialDayDecision.Fetch).months.let { pending ->
            assertEquals("无缓存时默认窗口两枚都要补：", listOf(now, nextMonth), pending)
        }
    }

    /** 己：⓪ 取证的两条真结论落成可执行的（半截 ⇒ 接住；形状怪 ⇒ 抛） */
    @Test
    fun parserSwallowsTruncatedJsonButPropagatesTheMalformedShapeOne() {
        val full = GOOD_RESPONSE
        assertEquals(
            "夹具坏了（完整响应解析不出两条标注），下面那几格就全是在测空气：",
            2,
            TeachingScheduleParser.parse(full).size,
        )
        // 半截 = writeText 先截断再写、中途被停下来的那份前缀。四个刀口都试：
        // 语法不合法 ⇒ parse 里那手 runCatching 接住 ⇒ 空列表（loadAll 因此"跳过这一枚文件"）。
        val cuts = listOf(1, full.indexOf("\"mc\""), full.length / 2, full.length - 2)
        val survivors = cuts.mapNotNull { end ->
            val prefix = full.substring(0, end.coerceIn(1, full.length))
            runCatching { TeachingScheduleParser.parse(prefix) }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?.let { "刀口 " + end + "（长度 " + prefix.length + "）解出 " + it.size + " 条" }
        }
        assertTrue(
            "半截 JSON 被 parse 解出了标注（" + survivors.joinToString(" ／ ") + "）—— " +
                "那「跳过这一枚文件」这一档就不成立了，SpecialDayCache 里那段订正过的注释要同批改",
            survivors.isEmpty(),
        )
        assertEquals(
            "空白正文：",
            emptyList<Any>(),
            TeachingScheduleParser.parse("   "),
        )
        // 第二档：语法合法而形状怪 —— walk/extractDay 不在那手 runCatching 里 ⇒ **整条抛出**。
        // 这一格是"半截 ⇒ 跳过"与"形状怪 ⇒ loadAll 抛给调用点"两档并存的可执行证明，
        // 也是那句假注释（「方向与 loadAll 读到坏文件时一致」）为什么只算半真的依据。
        assertThrows(IllegalArgumentException::class.java) {
            TeachingScheduleParser.parse("""{"rq":{"a":1}}""")
        }
    }

    // ---- 夹具与小工具 ------------------------------------------------------------------------------

    private fun month(year: Int, month: Int) = SpecialDayMonth(year, month)

    private fun write(
        month: SpecialDayMonth,
        state: SpecialDayWriteState = SpecialDayWriteState.WRITTEN,
        cause: String? = null,
    ) = SpecialDayMonthWrite(month, state, cause)

    private fun decide(cached: List<SpecialDayCacheAge>) = decideSpecialDayFetch(
        now = now,
        cachedAges = cached,
        visibleMonths = emptyList(),
        sessionAvailable = true,
        fetchInFlight = false,
    )

    private class Row(val label: String, val writes: List<SpecialDayMonthWrite>, val expected: String)

    private companion object {
        /** 与在册 `TeachingScheduleParserTest` 同一形态的响应（两条 JJR），拿来当"完整正文"的对照。 */
        const val GOOD_RESPONSE =
            """{"datas":{"JJR":[{"rq":"2026-10-01","mc":"国庆节","lx":"1"},""" +
                """{"rq":"2026-10-02","mc":"国庆节","lx":"1"}]}}"""
    }
}
