package com.buaa.schedule.domain.importing

import com.buaa.schedule.data.import.BuaaScheduleResponse
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T401②：教务「按周取课表」那一趟的**外壳形状 × 判据结论**全表（纯 JVM，零 android、零设备）。
 *
 * 题眼钉的是同一件事的两头，一头都不许松：
 * - **朝宽**：`{"code":null,"datas":null}`、`{}`、`{"code":"0","datas":null}` 曾被子串
 *   `it.code == null || it.code == "0"` **单独**放行，于是那一周既没进失败周次、也没交回任何一门课，
 *   被「先删该学期再插入」的覆盖导入静默删掉，而屏上念绿色成功 ⇒ 今天必须一律挡在闸门外；
 * - **朝窄**：合法的空课周（寒暑假、停课周、台账 #310「该周课全落在作息表之外」那一族）长的正是
 *   `datas` 在而 `arrangedList` 是空表那一形 ⇒ 必须仍记成功。把它判成失败会同时改坏
 *   `MAX_CONSECUTIVE_FAILURES` 熔断与 `succeeded` 报数两头（卡面点名的硬边界）。
 *
 * 期望值一律**手写死**：本文件不出现判据的第二份实现（连改前那一形都不复刻 —— 它的形状由
 * `BuaaWeekShellImportGateGuardTest` 按源码文本反事实钉，改前那三条件原文不许回潮）。
 *
 * 表里的 `raw` 都走**真实解码**（`Json { ignoreUnknownKeys = true; isLenient = true }` 与
 * `BuaaDtos.kt` 的字段默认值），为的是把「`{}` 解得开而两枚字段都是 null」这条来路本身钉成格子，
 * 而不是拿它当前提。⚠️ 代价如实写：这份配置是产线 `BuaaInPageFetcher` 里那一份的**抄本**（产线那枚是
 * `private`，JVM 侧递不进来），哪天产线改了配置，本表不会自动跟着变 —— 顶掉它的不是这条链，
 * 而是一枚新增的解码前提，归在册守卫那一族管。
 */
class BuaaWeekShellPolicyTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 一行 = 一枚外壳原文 → 内核档位（先解码再喂事实，与产线调用点同一手顺序） */
    private fun tierOf(raw: String): WeekShellTier {
        val shell = json.decodeFromString<BuaaScheduleResponse>(raw)
        return BuaaWeekShellPolicy.tier(
            datasPresent = shell.datas != null,
            arrangedListSize = shell.datas?.arrangedList?.size,
            code = shell.code,
        )
    }

    private class Row(
        val name: String,
        val raw: String,
        val tier: WeekShellTier,
        val importable: Boolean,
    )

    @Test
    fun `外壳形状全表 档位与能否进覆盖导入逐格手写死`() {
        val rows = listOf(
            Row(
                "题眼：code 与 datas 两枚都是 null（会话半失效那一形）",
                """{"code":null,"datas":null}""",
                WeekShellTier.ShellWithoutDatas,
                false,
            ),
            Row(
                "连 code 都没有的空外壳",
                """{}""",
                WeekShellTier.ShellWithoutDatas,
                false,
            ),
            Row(
                "code 是成功值而 datas 那一格没给（本机不替服务器兜这一格）",
                """{"code":"0","datas":null}""",
                WeekShellTier.ShellWithoutDatas,
                false,
            ),
            Row(
                "在册注释点名的限流外壳",
                """{"code":"-1","datas":null}""",
                WeekShellTier.ErrorEnvelope,
                false,
            ),
            Row(
                "带 msg 的错误外壳：一句报错救不了它",
                """{"code":"-1","datas":null,"msg":"系统繁忙，请稍后重试"}""",
                WeekShellTier.ErrorEnvelope,
                false,
            ),
            Row(
                "合法空课周：datas 在而 arrangedList 是空表 ⇒ 记成功",
                """{"code":"0","datas":{"arrangedList":[]}}""",
                WeekShellTier.DataGiven,
                true,
            ),
            Row(
                "合法空课周的另一形：datas 是空对象（三枚列表都走 DTO 默认值）",
                """{"datas":{}}""",
                WeekShellTier.DataGiven,
                true,
            ),
            Row(
                "正常带数据",
                """{"code":"0","datas":{"arrangedList":[{"courseName":"高等数学","dayOfWeek":1}]}}""",
                WeekShellTier.DataGiven,
                true,
            ),
            Row(
                "残余（本机分不开）：错误码而 datas 那一格在 ⇒ 按交回的课表处理，是加法、不删东西",
                """{"code":"-1","datas":{"arrangedList":[]}}""",
                WeekShellTier.DataGiven,
                true,
            ),
            Row(
                "未知字段不许改变档位（ignoreUnknownKeys 的来路）",
                """{"code":null,"datas":null,"traceId":"x","unknownFlag":true}""",
                WeekShellTier.ShellWithoutDatas,
                false,
            ),
        )
        for (row in rows) {
            val tier = tierOf(row.raw)
            assertEquals("格子「${row.name}」的档位：", row.tier, tier)
            assertEquals(
                "格子「${row.name}」能否进覆盖导入（闸门放不放行）：",
                row.importable,
                tier.importable,
            )
        }
    }

    /**
     * 判据与注释同一条：`datasPresent` 是**唯一**的放行判据 —— 而改前那两枚「或」支
     * （`it.code == null`、`it.code == "0"`）今天各自单独放行不了任何一格。
     */
    @Test
    fun `code 缺省与 code 为成功值都放行不了任何一格`() {
        for (raw in listOf(
            """{}""",
            """{"code":null,"datas":null}""",
            """{"code":"0","datas":null}""",
            """{"code":null,"datas":null,"msg":"稍后再试"}""",
        )) {
            assertFalse("「$raw」改前被子串放行，今天必须挡在闸门外（实到档位 ${tierOf(raw)}）", tierOf(raw).importable)
        }
    }

    /**
     * 硬边界那一格的正面证明：`arrangedListSize` 是**非判据事实**。
     *
     * 同一枚 code、同一枚 `datasPresent` 之下，条数取 0 / 1 / 7 三档必须恒给**同一档位**；
     * 反过来 `datasPresent = false` 时条数也救不回来。哪天有人把闸门改成「要求 arrangedList 非空」，
     * 这一格（以及 `合法空课周记成功…` 那一格）当场红。
     */
    @Test
    fun `arrangedList 的条数一次都不参与判据`() {
        for (code in listOf(null, "0", "-1", "9")) {
            val byZero = BuaaWeekShellPolicy.tier(datasPresent = true, arrangedListSize = 0, code = code)
            val byOne = BuaaWeekShellPolicy.tier(datasPresent = true, arrangedListSize = 1, code = code)
            val bySeven = BuaaWeekShellPolicy.tier(datasPresent = true, arrangedListSize = 7, code = code)
            val absentList = BuaaWeekShellPolicy.tier(datasPresent = true, arrangedListSize = null, code = code)
            assertEquals("datas 那一格在 ⇒ 条数不得改变档位（code=$code，实到 $byZero / $byOne / $bySeven / $absentList）", byZero, byOne)
            assertEquals("datas 那一格在 ⇒ 条数不得改变档位（code=$code，实到 $byZero / $bySeven）", byZero, bySeven)
            assertEquals("datas 那一格在 ⇒ 条数不得改变档位（code=$code，实到 $byZero / $absentList）", byZero, absentList)
            assertEquals("datas 那一格在就是答了这一周（code=$code）：", WeekShellTier.DataGiven, byZero)
            assertTrue("datas 那一格在就必须能进覆盖导入（code=$code）：", byZero.importable)

            val noDatas = listOf(0, 1, 7, null).map { size ->
                BuaaWeekShellPolicy.tier(datasPresent = false, arrangedListSize = size, code = code)
            }
            for (tier in noDatas) {
                assertFalse("datas 那一格没给 ⇒ 任何条数都不许放行（code=$code，实到 $tier）", tier.importable)
            }
            assertEquals("datas 那一格没给时档位只由 code 起名、与条数无关（code=$code）：", noDatas.first(), noDatas.last())
        }
    }

    /**
     * 朝窄反向钉（卡面点名的那一档病）：合法的空课周**记成功**，一次都不许记进失败周次。
     * 判成失败的两头代价：连续三周空课会把整轮熔断掉、`succeeded` 报数一起少。
     */
    @Test
    fun `合法空课周记成功 不许被记成失败`() {
        val legit = listOf(
            """{"code":"0","datas":{"arrangedList":[]}}""",
            """{"datas":{"arrangedList":[]}}""",
            """{"datas":{"arrangedList":[],"notArrangeList":[],"practiceList":[]}}""",
            """{"datas":{}}""",
            """{"code":"0","datas":{"arrangedList":null}}""",
        )
        for (raw in legit) {
            assertEquals("合法空课周必须仍是 DataGiven（「$raw」实到 ${tierOf(raw)}）：", WeekShellTier.DataGiven, tierOf(raw))
        }
    }

    /**
     * 把「`{}` 解得开而两枚字段都是 null」这条**来路**钉成格子：改前放行它的是解码器 ＋ DTO 默认值，
     * 不是判据看错了。这一格红了说明来路变了（比如 DTO 给了 `datas` 一个非 null 默认值），
     * 那时上面那张表要重读。
     */
    @Test
    fun `空外壳解得开而两枚字段都是 null 这就是改前被放行的来路`() {
        val shell = json.decodeFromString<BuaaScheduleResponse>("""{}""")
        assertNull(" `{}` 在本仓解码配置下 datas 必须是 null：", shell.datas)
        assertNull("`{}` 在本仓解码配置下 code 必须是 null：", shell.code)
        val halfDead = json.decodeFromString<BuaaScheduleResponse>("""{"code":null,"datas":null}""")
        assertNull("会话半失效那一形 datas 必须是 null（不是空对象）：", halfDead.datas)
        assertNull("会话半失效那一形 code 必须是 null：", halfDead.code)
    }
}
