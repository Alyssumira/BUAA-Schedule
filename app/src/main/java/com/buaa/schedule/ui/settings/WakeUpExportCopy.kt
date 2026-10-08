package com.buaa.schedule.ui.settings

import com.buaa.schedule.domain.schedule.WakeUpExportDecision

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

/**
 * 一段排课都写不出去那一档（T277 审计 N4 → T279①）：实话是"没写出去"，不是"已导出 0 段"。
 *
 * 改前这一等根本没有句子：`exportWakeUpTo` 只判 `courses.isEmpty()`（**入参**），
 * 而两档 drop 把每一门都丢下时交回的是 `{"courses":[]}` ⇒ 默认的截断写照样开流，
 * 用户挑的那份 `.json` 被换成一张空课表，屏幕上端出来的仍是上面那四支里的
 * 「已导出（0 段排课；另有 N 段…未写入）」—— 一句在中文里读起来像成功回执的句子。
 * 同门那把尺在册裁定在 `domain/schedule/IcsExportGate.kt`（甲档：零日程时**完全不碰**用户选中的
 * 那个文件），本函数就是那条裁定落到第二条落盘链上的那一半句子。
 *
 * 两支各自一条**完整字面量**（与上面那四支同一写法，理由见 [wakeUpExportMessage] 的「为什么」一节）。
 * [com.buaa.schedule.domain.schedule.WakeUpExportDecision.RefuseEmpty] 交回的是
 * [com.buaa.schedule.domain.schedule.WakeUpTargetFileMutation.LEAVE_UNTOUCHED] ⇒ "没有被改动"
 * 这句承诺钉的是**我们这一手不动它**（系统选择器建文档那一步不在本应用手里，本卡零设备量不到）。
 */
internal fun wakeUpExportRefusedMessage(decision: WakeUpExportDecision.RefuseEmpty): String =
    if (decision.droppedFragments > 0) {
        "这 ${decision.droppedFragments} 段都缺少节次或周次、无法定位，没有可导出的 WakeUp 内容，未写出文件，你选中的文件没有被改动"
    } else {
        "这个学期排不出任何一段可导出的内容，未写出文件，你选中的文件没有被改动"
    }

/** 段数报得出、导出件却是空的：那是导出器内部两格打架，不叫用户去修课表（同 ICS 那一档的分工）。 */
internal fun wakeUpExportInconsistentMessage(decision: WakeUpExportDecision.RefuseInconsistent): String =
    "导出失败：算出 ${decision.writtenFragments} 段可写内容却发现导出件是空的，未写出文件，你选中的文件没有被改动"

