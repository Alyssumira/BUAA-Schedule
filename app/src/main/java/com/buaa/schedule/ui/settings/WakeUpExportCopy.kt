package com.buaa.schedule.ui.settings

/**
 * WakeUp 兼容 JSON 导出成功那一句的措辞（T181②，T203 补第二档，T224 把单位念对）。
 *
 * 零 android / androidx import（本仓口径）：三枚计数由调用点
 * `com.buaa.schedule.ui.ScheduleViewModel.exportWakeUpTo` 递进来。三枚都是**排课片段枚数**
 * （一条 [com.buaa.schedule.domain.model.Course] = 一段排课），门数取自
 * [com.buaa.schedule.domain.schedule.WakeUpDropTally.tally] 的那两档。
 *
 * ## 病在哪
 *
 * 此前这一句的两支长在 ViewModel 里，而 `else` 那一支是两段字面量拼起来的：
 * 前一段开了「（」、后一段没有关 —— 于是课表里只要有一门**没有节次**的课（这条输入可达，
 * `EmptyPeriodPlacementTest` 已证），Snackbar 就念一句括号不闭合的破句。
 * 同一函数的另一支（两档都为 0 的那一支）是关的，所以这一枚不是口径问题，是漏了一个字。
 *
 * T203 那半场：导出件丢课其实有**两档**（没有节次 = T27，没有周次 = #237），而这一句只念了
 * 前那一档。空周次的课整条不进导出件之后，"N 门课"仍是多的 —— 少掉的那几门没人念。
 * 于是补第三、四支，两个数各跟着自己那一档出场；两档互斥（同一门课只落一档），
 * 所以 `placed` 减两次不会把同一门减两遍。
 *
 * T224 这半场是**单位**：这一句吃的三枚数全都数在排课片段行上
 * （`ScheduleExporters.toWakeUpJson` 一条 Course 写出一条 WakeUp 课程），可它此前一律念「门课」。
 * 于是设置页那一格先说「22 门课」、点完 toast 说「20 门课」，而统计页同一批数据说「18 门课 · 22 段排课」——
 * 三句里的「门」不是同一把尺。今天这一句念「段排课」，与
 * [com.buaa.schedule.ui.settings.wakeUpExportRowSummary]（同一页那一行的预览）同尺，
 * 数字本身一枚没动。
 *
 * ## 为什么每一支必须是**一条完整字面量**
 *
 * 「丢掉的课不进导出件」这件事由 [com.buaa.schedule.domain.schedule.ScheduleExporters.toWakeUpJson]
 * 决定（T27 + T203），这一句只负责把它说给用户听。四支各自闭合以后，
 * `UserVisibleParenBalanceGuardTest` 那条"用户可见串逐枚配平"的尺子才能直接钉住它 ——
 * 拼接链上的配平要靠下一行补齐，正是这一枚病能活下来的原因。
 *
 * ⚠️ 残账（本卡零 adb、也不许改 `domain/schedule/WakeUpDropTally.kt`）：那本册子的字段名仍叫
 * `noPeriodCourses` / `noWeekCourses`，数的是片段行。改名要连统计与守卫一起走，登记在回执的
 * 「需要跨卡配合」里。
 */
internal fun wakeUpExportMessage(
    totalFragments: Int,
    noPeriodFragments: Int,
    noWeekFragments: Int,
): String {
    val placed = totalFragments - noPeriodFragments - noWeekFragments
    return when {
        noPeriodFragments == 0 && noWeekFragments == 0 -> "WakeUp JSON 已导出（$placed 段排课）"
        noWeekFragments == 0 -> "WakeUp JSON 已导出（$placed 段排课；另有 $noPeriodFragments 段没有节次、无法定位，未写入）"
        noPeriodFragments == 0 -> "WakeUp JSON 已导出（$placed 段排课；另有 $noWeekFragments 段没有周次、无法定位，未写入）"
        else -> "WakeUp JSON 已导出（$placed 段排课；另有 $noPeriodFragments 段没有节次、$noWeekFragments 段没有周次，都无法定位，未写入）"
    }
}
