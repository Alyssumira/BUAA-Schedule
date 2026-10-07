package com.buaa.schedule.ui.settings

/**
 * WakeUp 兼容 JSON 导出成功那一句的措辞（T181②，T203 补第二档）。
 *
 * 零 android / androidx import（本仓口径）：三枚计数由调用点
 * `com.buaa.schedule.ui.ScheduleViewModel.exportWakeUpTo` 递进来，
 * 两档门数取自 [com.buaa.schedule.domain.schedule.WakeUpDropTally.tally]。
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
 * ## 为什么每一支必须是**一条完整字面量**
 *
 * 「丢掉的课不进导出件」这件事由 [com.buaa.schedule.domain.schedule.ScheduleExporters.toWakeUpJson]
 * 决定（T27 + T203），这一句只负责把它说给用户听。四支各自闭合以后，
 * `UserVisibleParenBalanceGuardTest` 那条"用户可见串逐枚配平"的尺子才能直接钉住它 ——
 * 拼接链上的配平要靠下一行补齐，正是这一枚病能活下来的原因。
 */
internal fun wakeUpExportMessage(
    totalCourses: Int,
    noPeriodCourses: Int,
    noWeekCourses: Int,
): String {
    val placed = totalCourses - noPeriodCourses - noWeekCourses
    return when {
        noPeriodCourses == 0 && noWeekCourses == 0 -> "WakeUp JSON 已导出（$placed 门课）"
        noWeekCourses == 0 -> "WakeUp JSON 已导出（$placed 门课；另有 $noPeriodCourses 门没有节次、无法定位，未写入）"
        noPeriodCourses == 0 -> "WakeUp JSON 已导出（$placed 门课；另有 $noWeekCourses 门没有周次、无法定位，未写入）"
        else -> "WakeUp JSON 已导出（$placed 门课；另有 $noPeriodCourses 门没有节次、$noWeekCourses 门没有周次，都无法定位，未写入）"
    }
}
