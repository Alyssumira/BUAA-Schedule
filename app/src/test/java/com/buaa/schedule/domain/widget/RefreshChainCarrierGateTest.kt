package com.buaa.schedule.domain.widget

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T366②：`RefreshChainCarrierGate.decide` 的表驱动逐格账（纯 JVM，无 Robolectric、无设备、不读钟）。
 *
 * ## 这张表钉的是哪笔账
 * 一条链的头顶**要不要**取那一枚读表载体（台账 #218 ／ 在册 #536 F2 同族）。取多了与取少了都有真代价，
 * 所以判据两侧都得能听见红：
 * - 朝「一律取」扭一刀 —— 把 `SkipOnlyWidgetConsumer` 那一格折进 `Build`（看着只是"省事，反正各步
 *   都认 null"）：第 3 格当场从 Skip 变 Build，而那一趟唯一的消费者只要学期行／节次表／课程那三格、
 *   自己那一趟就读得完 ⇒ 每天必跑的零点那条链从此净增一趟提醒册子的查询（改前根本没有这一趟）⇒ 红；
 * - 朝「一律不取」扭一刀 —— 消费者计数那道判断恒判 Skip：第 1 格（改时间满档那一趟，原先四张表
 *   各读满 4／4／(3+K)／4 遍）当场红。
 * 接线那一半（四枚调用点真的把 `snapshot = chainSnapshot` 递进去了、载体只取一次）不在本表射程：
 * 那是**接线形状**而不是纯判据，钉在 `widget/WidgetRefreshChainCarrierWiringGuardTest`（T366③）。
 * 卡面那条硬规矩说的就是这件事 —— 不许把非纯的东西包装成"纯内核"。
 *
 * ## 八格怎么排
 * 三枚输入的真值表全枚举：今天可达的三路与不可达的五路都列，不可达那几格是形状证据（label 逐格点名
 * 它对应广播里的哪一路，读红的人不必回头翻码）。档位吃的是枚举名本体（`.name`），不是 simpleName ——
 * 枚举常量没有自己的类，后者八格会交回同一个 "RefreshChainCarrierPlan" 而这张表永远读不到差异。
 */
class RefreshChainCarrierGateTest {

    private data class Cell(
        val label: String,
        val hasReminderChainStep: Boolean,
        val hasPreviewStep: Boolean,
        val hasWidgetRefreshStep: Boolean,
        val expectedTier: String,
    )

    private companion object {
        const val BUILD = "Build"
        const val SKIP_WIDGET_ONLY = "SkipOnlyWidgetConsumer"
        const val SKIP_NO_CONSUMER = "SkipNoCarrierConsumer"

        val FLAGS = listOf(false, true)

        /** 八格输入（真值表全枚举），[逐格] 与 [单调性] 两把尺共用同一本输入 */
        val ROWS: List<Triple<Boolean, Boolean, Boolean>> =
            FLAGS.flatMap { reminder -> FLAGS.flatMap { preview -> FLAGS.map { widget -> Triple(reminder, preview, widget) } } }
    }

    /** 一格递进去，交回档位名（唯一的调用出口，两把尺共用同一把尺） */
    private fun tierOf(row: Triple<Boolean, Boolean, Boolean>): String =
        RefreshChainCarrierGate.decide(row.first, row.second, row.third).name

    @Test
    fun `逐格 取与不取的八格真值表`() {
        val cells = listOf(
            // ── 今天可达的三路 ──
            Cell(
                "改时间/换时区那一趟 组件+提醒链+明日预告三枚消费者同读一批表",
                hasReminderChainStep = true, hasPreviewStep = true, hasWidgetRefreshStep = true,
                expectedTier = BUILD,
            ),
            Cell(
                "精确闹钟权限回来那一趟 提醒链内部就是重排提醒与课堂铃兜底两步同读",
                hasReminderChainStep = true, hasPreviewStep = false, hasWidgetRefreshStep = false,
                expectedTier = BUILD,
            ),
            Cell(
                "纯零点那一趟（本卡新立的那一格）唯一消费者只要那三格 自己一趟读得完",
                hasReminderChainStep = false, hasPreviewStep = false, hasWidgetRefreshStep = true,
                expectedTier = SKIP_WIDGET_ONLY,
            ),
            // ── 不可达那五格：形状证据 ──
            Cell(
                "三枚消费者都不在 今天被调用点外面那道动作判断拦在链外",
                hasReminderChainStep = false, hasPreviewStep = false, hasWidgetRefreshStep = false,
                expectedTier = SKIP_NO_CONSUMER,
            ),
            Cell(
                "只有明日预告 组件那步不在 ⇒ 白读那一路不成立 取",
                hasReminderChainStep = false, hasPreviewStep = true, hasWidgetRefreshStep = false,
                expectedTier = BUILD,
            ),
            Cell(
                "组件 + 明日预告 提醒链缺席那一路 ⇒ 两枚消费者同读学期行与节次表",
                hasReminderChainStep = false, hasPreviewStep = true, hasWidgetRefreshStep = true,
                expectedTier = BUILD,
            ),
            Cell(
                "提醒链 + 组件 明日预告被开关短路那一路",
                hasReminderChainStep = true, hasPreviewStep = false, hasWidgetRefreshStep = true,
                expectedTier = BUILD,
            ),
            Cell(
                "提醒链 + 明日预告 桌面上一个组件都没放那一路",
                hasReminderChainStep = true, hasPreviewStep = true, hasWidgetRefreshStep = false,
                expectedTier = BUILD,
            ),
        )
        assertEquals(
            "表得有八格（实到 " + cells.size + "）：三枚布尔的真值表少了哪一格，单调性那把尺就少一个参照物",
            ROWS.size,
            cells.size,
        )
        for ((index, cell) in cells.withIndex()) {
            val row = Triple(cell.hasReminderChainStep, cell.hasPreviewStep, cell.hasWidgetRefreshStep)
            val tier = tierOf(row)
            assertEquals(
                "第 ${index + 1} 格（${cell.label}）：递进 reminder=${cell.hasReminderChainStep} " +
                    "preview=${cell.hasPreviewStep} widget=${cell.hasWidgetRefreshStep} ⇒ 实到档位「$tier」" +
                    "（期望「${cell.expectedTier}」）",
                cell.expectedTier,
                tier,
            )
        }
    }

    @Test
    fun `单调性 多一枚消费者不许把结论退回不取`() {
        // 判据的方向只有一个：消费者只会让「取」更该取。逐格把任一 false 翻成 true，
        // 结论不许从 Build 退回任一 Skip 档 —— 这一格挡的是"两把尺各判各的"（组件那一档被特判硬压）。
        for (row in ROWS) {
            if (tierOf(row) != BUILD) continue
            for (widened in ROWS.filter { it != row && it.widens(row) }) {
                assertEquals(
                    "「${tierOf(row)}」那一格（$row）再多一枚消费者 ⇒ $widened 不许退回不取",
                    BUILD,
                    tierOf(widened),
                )
            }
        }
        // 反向那一刀也得有参照物：两格「不取」各加一枚消费者时该往哪走，逐枚点名 ——
        // 判据不是"消费者越多越取"那么粗：只剩组件那一枚消费者加进来仍然是不取（白读那一手不许被算数）。
        assertEquals(
            "零消费者那一格加上组件那一枚 ⇒ 还是「只剩组件」那一档（实到 " +
                tierOf(Triple(false, false, true)) + "）：计数 1 而且正是组件，载体那第四格没人吃",
            SKIP_WIDGET_ONLY,
            tierOf(Triple(false, false, true)),
        )
        for ((row, label) in listOf(
            Triple(false, true, false) to "零消费者那格 + 明日预告一枚",
            Triple(true, false, false) to "零消费者那格 + 提醒链一枚",
            Triple(false, true, true) to "只剩组件那格 + 明日预告一枚",
            Triple(true, false, true) to "只剩组件那格 + 提醒链一枚",
        )) {
            assertEquals(
                "$label ⇒ 必须进「取」那一档（实到 " + tierOf(row) + "）：这一格红 = 判据把" +
                    "「多一步同读一批表」当成了不加，台账 #218 那笔账就修不完",
                BUILD,
                tierOf(row),
            )
        }
    }

    @Test
    fun `反套套逻辑 三档都被这张表数到 而且各数到几格`() {
        val tiers = ROWS.map { tierOf(it) }
        assertEquals(
            "八格输入必须只交出这三档（实到 " + tiers.distinct().sorted() + "）：少一档就是有一档在代码里" +
                "没有自己的处置（判据折档），多一档就是判据长出了这张表不认识的第 4 路",
            listOf(SKIP_NO_CONSUMER, SKIP_WIDGET_ONLY, BUILD).sorted(),
            tiers.distinct().sorted(),
        )
        assertEquals(
            "「取」那档在八格里占 6 格（实到 " + tiers.count { it == BUILD } + "）：只有「零消费者」与" +
                "「只剩组件那一枚消费者」两格不取。数字变了就要回来重判那两格还成不成立（T366 ⓪ 的逐路账）",
            listOf(1, 1, 6),
            listOf(
                tiers.count { it == SKIP_NO_CONSUMER },
                tiers.count { it == SKIP_WIDGET_ONLY },
                tiers.count { it == BUILD },
            ),
        )
    }

    /** [this] 是不是 [other] 的加宽（每一枚消费者判据都不减） */
    private fun Triple<Boolean, Boolean, Boolean>.widens(other: Triple<Boolean, Boolean, Boolean>): Boolean =
        first >= other.first && second >= other.second && third >= other.third
}
