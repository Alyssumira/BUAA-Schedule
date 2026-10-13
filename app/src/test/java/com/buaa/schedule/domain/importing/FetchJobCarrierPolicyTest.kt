package com.buaa.schedule.domain.importing

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T336③ 表驱动单测：`FetchJobCarrierPolicy` 那三张档表逐格钉死（在册 #412）。
 *
 * 纯 JVM：判据零 android import、零时钟、零设备（本仓硬规则），所以这里连 Robolectric 都不起。
 *
 * ## 逐格点名（本文件 12 枚 @Test / 16 处断言点 / 运行期 60 格读数）
 * 复算尺（@Test 枚数）：`grep -a -o 'tests="[0-9]*"' app/build/test-results/testDebugUnitTest/TEST-com.buaa.schedule.domain.importing.FetchJobCarrierPolicyTest.xml` → 12（T337⑤a 按实跑 XML 复算，不是数注释）。
 * 格数按循环展开：4 + 6×3（在飞六枚 × 三档载体）+ 3（webViewRef × 三档）+ 6（加载读数 × COMPOSITION）
 * + 12（加载读数 × 另两档 = 6×2，T337⑤a 补）+ 3（名单外 × 三档）+ 8（点火全表）
 * + 1（AttachOnly 不许改判）+ 4（归零表）+ 1（在飞归属）= 60。
 * ① 名单本体（枚数 + 逐枚）：在飞六枚、平台一枚、登录页加载读数六枚，三族互斥。
 * ② ③ ④ 载体裁定：六枚在飞事实 × 三档载体 = 18 格 ——
 *    `COMPOSITION` 全判 `JobDiesWithDigits`（盘上改前那一形）、
 *    `SNAPSHOT` 全判 `DigitsLie`（**甲档单独用的病**：数字活、作业死 ⇒ 屏上念一具尸体的现在时）、
 *    `PROCESS_HOLDER` 全判 `Supported`。
 * ⑤ WebView 那一枚：`SNAPSHOT` 判 `Unsnapshotable`（卡面证伪档①），另两档放行。
 * ⑥ 与作业无关的六枚加载读数：留在组合上是对的（不许顺手也搬进 holder）；另两档也不许误判 ——
 *    [pageLoadReadingsAreNotMisjudgedOnTheOtherTwoCarriers] 逐枚钉加载读数在 `SNAPSHOT` / `PROCESS_HOLDER`
 *    仍是 `Supported`（判成 `DigitsLie` / `JobDiesWithDigits` 就是逼页面把与作业无关的读数搬进快照或 holder）。
 *    这 12 格是 T337⑤ 的 V 臂量出来的：把那两栏一起判死时，改前那 48 格与接线守卫 10 层都一字不红
 *    —— ⑥ 原先只走 COMPOSITION 那一栏，另两栏没有读者。名单那一族另有一枚 N 臂（在飞名单多收一枚加载读数）
 *    在 ① 与 ⑥ 当场就红，与这 12 格不是同一个洞。
 * ⑦ 名单外一律 `NotOnRoster`：新增一枚在飞状态却忘了进名单 ⇒ 这一格先红，不许静默放行。
 * ⑧ 点火裁定全表 8 行（三枚 Boolean 穷举）：**第 6 行是本卡的心脏** ——
 *    `askedToFetch=true`（重建后 fetchStarted 仍为真）+ `jobRunning=true` + 同一枚平台
 *    ⇒ 必须 `AttachOnly`，把它判成 `Start` 就是「把几十秒作废后从头再抓」（卡面证伪档②）。
 * ⑨ 防误点火：`askedToFetch=false` 那四行（数据还没到、没人按过抓取）一律 `Idle`。
 * ⑩ 归零裁定：只有重新点火两档许把进度数字显式归零，`AttachOnly` 归零就是把还在走的作业抹成 0/N。
 */
class FetchJobCarrierPolicyTest {

    // ─────────────── ① 名单本体 ───────────────

    @Test
    fun rostersAreExactlyTheBookedNames() {
        val expectedInFlight = setOf(
            "fetchState",
            "fetchWeek",
            "fetchTotal",
            "fetchStarted",
            "fetchCancelled",
            "importPrepared",
        )
        assertEquals(
            "在飞事实名单必须逐枚等于卡面点名的那六枚（多一枚 = 有人把不该随作业存的读数塞进 holder；" +
                "少一枚 = 那一枚又落回组合，转屏即归零）：",
            expectedInFlight,
            FetchJobCarrierPolicy.IN_FLIGHT_FACTS,
        )
        assertEquals(
            "抓取平台只有 webViewRef 一枚（它的长寿命合法载体是进程内 holders，不是快照）：",
            setOf("webViewRef"),
            FetchJobCarrierPolicy.PLATFORM_FACTS,
        )
        assertEquals(
            "登录页自己的加载读数六枚 —— 与抓取作业无关，本卡不搬：",
            setOf("loadProgress", "loadError", "loadStarted", "pageVisible", "timedOut", "retryToken"),
            FetchJobCarrierPolicy.PAGE_LOAD_FACTS,
        )
        val union = FetchJobCarrierPolicy.IN_FLIGHT_FACTS + FetchJobCarrierPolicy.PLATFORM_FACTS +
            FetchJobCarrierPolicy.PAGE_LOAD_FACTS
        val sumOfRosters = expectedInFlight.size + FetchJobCarrierPolicy.PLATFORM_FACTS.size +
            FetchJobCarrierPolicy.PAGE_LOAD_FACTS.size
        assertEquals(
            "三族名单不许交叉（同一枚名字被两把尺各自判一次 = 全仓第二把尺，本仓口径）：" +
                "并集 ${union.size} 对 三族之和 $sumOfRosters（并集缩水就说明有一枚名字被登记了两遍）",
            sumOfRosters,
            union.size,
        )
    }

    // ─────────────── ② ③ ④ 在飞六枚 × 三档载体 ───────────────

    @Test
    fun inFlightFactsOnCompositionDieWithTheirJob() {
        for (fact in FetchJobCarrierPolicy.IN_FLIGHT_FACTS) {
            assertEquals(
                "$fact 长在组合上（改前那一形 `by remember { mutable… }`）必须判 JobDiesWithDigits：" +
                    "转屏销毁组合 ⇒ 读数归零 ⇒ 抓取作业连带被取消",
                CarrierVerdict.JobDiesWithDigits,
                FetchJobCarrierPolicy.verdict(fact, FactCarrier.COMPOSITION),
            )
        }
    }

    @Test
    fun inFlightFactsInSnapshotKeepDigitsButLieAboutTheJob() {
        for (fact in FetchJobCarrierPolicy.IN_FLIGHT_FACTS) {
            assertEquals(
                "$fact 放进 rememberSaveable（甲档单独用）必须判 DigitsLie：快照只救读数，" +
                    "作业还长在组合上 ⇒ 重建后屏上念着第 7/19 周、脚下从第 1 周重跑",
                CarrierVerdict.DigitsLie,
                FetchJobCarrierPolicy.verdict(fact, FactCarrier.SNAPSHOT),
            )
        }
    }

    @Test
    fun inFlightFactsInProcessHolderAreTheOnlySupportedCarrier() {
        for (fact in FetchJobCarrierPolicy.IN_FLIGHT_FACTS) {
            assertEquals(
                "$fact 的载体寿命 ≥ 作业寿命 ⇒ 进程内 holder 是唯一放行的一档：" +
                    "载体寿命必须 ≥ 作业寿命这一条就是本卡的判据本体",
                CarrierVerdict.Supported,
                FetchJobCarrierPolicy.verdict(fact, FactCarrier.PROCESS_HOLDER),
            )
        }
    }

    // ─────────────── ⑤ WebView：不可快照 ───────────────

    @Test
    fun webViewRefNeverEntersSnapshot() {
        assertEquals(
            "webViewRef + SNAPSHOT 必须判死：WebView 不可快照，把它塞进 savedInstanceState 是错的（卡面证伪档①）",
            CarrierVerdict.Unsnapshotable,
            FetchJobCarrierPolicy.verdict("webViewRef", FactCarrier.SNAPSHOT),
        )
        for (carrier in listOf(FactCarrier.COMPOSITION, FactCarrier.PROCESS_HOLDER)) {
            assertEquals(
                "webViewRef + $carrier 放行：页面那一枚由 AndroidView 的 factory 每轮重新供，" +
                    "跨销毁的合法载体是 BuaaWebSession 那一族进程内 holders",
                CarrierVerdict.Supported,
                FetchJobCarrierPolicy.verdict("webViewRef", carrier),
            )
        }
    }

    // ─────────────── ⑥ 与作业无关的读数：留在组合上是对的 ───────────────

    @Test
    fun pageLoadReadingsMayStayInComposition() {
        for (fact in FetchJobCarrierPolicy.PAGE_LOAD_FACTS) {
            assertEquals(
                "$fact 不属于在飞那一族 ⇒ 长在组合上不是病（本卡不许顺手把它也搬进 holder，" +
                    "搬一枚不该搬的读数同样是把寿命与事实配错）",
                CarrierVerdict.Supported,
                FetchJobCarrierPolicy.verdict(fact, FactCarrier.COMPOSITION),
            )
        }
    }

    @Test
    fun pageLoadReadingsAreNotMisjudgedOnTheOtherTwoCarriers() {
        for (carrier in listOf(FactCarrier.SNAPSHOT, FactCarrier.PROCESS_HOLDER)) {
            for (fact in FetchJobCarrierPolicy.PAGE_LOAD_FACTS) {
                assertEquals(
                    "$fact 在 $carrier 档也必须判 Supported：它不属于在飞那一族，判成 DigitsLie / JobDiesWithDigits " +
                        "就是逼页面把一枚与作业无关的读数搬进快照或 holder —— 那是把寿命与事实配错的**反方向**，" +
                        "与上一格（留在组合上是对的）合起来才把「加载读数三档都不许误判」钉全（T337⑤a：V 臂扭的就是这两栏）",
                    CarrierVerdict.Supported,
                    FetchJobCarrierPolicy.verdict(fact, carrier),
                )
            }
        }
    }

    // ─────────────── ⑦ 名单闭合：不许静默放行 ───────────────

    @Test
    fun factsOffTheRosterAreNotSilentlyPassed() {
        for (carrier in FactCarrier.values()) {
            assertEquals(
                "名单外那一枚名字（fetchAttemptToken，本仓没有）在 $carrier 档必须报 NotOnRoster 而不是 Supported：" +
                    "静默放行 = 新增一枚在飞状态却忘了进名单时，这一族判据一个字都不会红",
                CarrierVerdict.NotOnRoster,
                FetchJobCarrierPolicy.verdict("fetchAttemptToken", carrier),
            )
        }
    }

    // ─────────────── ⑧ 点火裁定全表 ───────────────

    @Test
    fun ignitionTableIsExhaustiveOverTheThreeInputs() {
        val rows = listOf(
            // askedToFetch, jobRunning, platformIsJobPlatform, 期望, 这一格钉住的事
            Triple(Triple(false, false, false), Ignition.Idle, "没人要求抓、没作业、没平台 ⇒ 一条请求都不发"),
            Triple(Triple(false, false, true), Ignition.Idle, "没人要求抓 ⇒ 平台到位也不许自己点着火（防误点火）"),
            Triple(Triple(false, true, false), Ignition.Idle, "没人要求抓 ⇒ 连作业在飞都不许再点一次"),
            Triple(Triple(false, true, true), Ignition.Idle, "askedToFetch 排在最前：它是唯一的要求抓取旗标，别的两枚都不许替它点火"),
            Triple(Triple(true, false, false), Ignition.Start, "要求抓 + 没作业在飞 ⇒ 点一次火"),
            Triple(Triple(true, true, true), Ignition.AttachOnly, "本卡的心脏：重建后 fetchStarted 仍为真、作业已在飞、平台还是那一枚 ⇒ 只挂读数，绝不二次点火"),
            Triple(Triple(true, true, false), Ignition.StartOnReplacedPlatform, "作业在飞但脚下平台已被换枚（旧那枚随组合销毁）⇒ 在新平台上再点一次"),
            Triple(Triple(true, false, true), Ignition.Start, "要求抓 + 作业已收尾 ⇒ 同一枚平台也可以再点一轮（与已在飞那一档分得开）"),
        )
        for ((input, expected, why) in rows) {
            val (asked, running, same) = input
            assertEquals(
                "ignition($asked, $running, $same) 必须是 $expected —— $why",
                expected,
                FetchJobCarrierPolicy.ignition(
                    askedToFetch = asked,
                    jobRunning = running,
                    platformIsJobPlatform = same,
                ),
            )
        }
    }

    @Test
    fun attachOnlyIsNeverRebrandedAsRecovery() {
        // 单独一枚把「恢复 ≠ 重跑」这句钉死：把 AttachOnly 换成 Start 就是本卡点名的病复发。
        assertEquals(
            "已在飞 + 同一枚平台那一手不许落到 Start/StartOnReplacedPlatform 任何一档（那两支都是重新点火）：",
            false,
            FetchJobCarrierPolicy.ignition(true, true, true) == Ignition.Start ||
                FetchJobCarrierPolicy.ignition(true, true, true) == Ignition.StartOnReplacedPlatform,
        )
    }

    // ─────────────── ⑩ 归零裁定 ───────────────

    @Test
    fun onlyReigniteResetsTheProgressDigits() {
        val rows = listOf(
            Ignition.Idle to false,
            Ignition.AttachOnly to false,
            Ignition.Start to true,
            Ignition.StartOnReplacedPlatform to true,
        )
        for ((action, expected) in rows) {
            assertEquals(
                "$action 之后进度数字该不该显式归零 = $expected（AttachOnly 归零就是把还在走的作业抹成 0/N；" +
                    "重新点火不归零就是拿旧数字念新作业）",
                expected,
                FetchJobCarrierPolicy.progressResetsOnIgnition(action),
            )
        }
    }

    @Test
    fun inFlightMembershipIsAskedByJobLifetimeNotByNameGuessing() {
        assertEquals(
            "「载体寿命必须 ≥ 作业寿命」这一问只对名单内那一族说话：webViewRef（平台）与 retryToken（加载读数）都不许被算成在飞",
            listOf(true, false, false),
            listOf(
                FetchJobCarrierPolicy.isInFlightFact("fetchWeek"),
                FetchJobCarrierPolicy.isInFlightFact("webViewRef"),
                FetchJobCarrierPolicy.isInFlightFact("retryToken"),
            ),
        )
    }
}
