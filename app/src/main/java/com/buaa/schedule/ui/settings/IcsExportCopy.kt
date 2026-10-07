package com.buaa.schedule.ui.settings

import com.buaa.schedule.domain.schedule.IcsExportDecision

/**
 * ICS 导出那一站的用户回执（T252，#309）：档位由
 * [com.buaa.schedule.domain.schedule.IcsExportGate.decide] 定，这里只负责把它说成人话。
 *
 * 零 android / androidx import（本仓口径）：句子只吃判据交回的那几枚已算好的数，
 * 所以在 JVM 侧就能逐句断言 —— 三枚函数、四种落点，一格一句。
 *
 * ## 病在哪
 *
 * 改前这三句长在 `ui/ScheduleViewModel.exportIcsTo` 体内，而且**只有一句**：
 * `"已导出 ${result.eventCount} 个日程，可在系统日历中导入"`。条数为 0 时它照样出场，
 * 于是屏幕上念的是「已导出 0 个日程」—— 一句在中文里读起来像成功的句子（"已导出"三个字
 * 承担的是成功回执，没人会把中间的 0 读成"什么都没导出"），而同一趟已经把用户选中的文件
 * 截成了 0 字节。**报数的位子与判断"有没有东西可报"的位子是同一枚**，这就是这一族账的形状：
 * 文案不判档，只把 `eventCount` 原样插进去。
 *
 * ## 为什么"未写出文件"必须自己成句、不许押在下一段
 *
 * 两支各自一条**完整字面量**（与 [wakeUpExportMessage] 同一写法）：开括号押上一行、闭括号押下一行
 * 正是 `UserVisibleParenBalanceGuardTest` 点名的 T181② 那枚病活下来的形状。
 *
 * ## 「没有被改动」这句为什么敢说
 *
 * 判据把 [IcsExportDecision.RefuseEmpty] 与 [IcsExportDecision.RefuseInconsistent] 都判成
 * [com.buaa.schedule.domain.schedule.IcsTargetFileMutation.LEAVE_UNTOUCHED] ⇒ 那两支**一次都不开流**。
 * ⚠️ 但要说清边界：系统选择器（`ActivityResultContracts.CreateDocument`）在把 Uri 交回来之前
 * 自己已经建了一枚文档，那一步不在本应用手里，本卡零设备也量不到它建出来的那一刻是 0 字节还是
 * 带模板。所以这句承诺钉死的是**"我们这一手不动它"**，不是"那个文件一定不存在/一定有旧内容"。
 */

/** 写出成功那一档：逐字沿用改前那句（单位与句子都不许跟着本卡漂），跳过数为零时不带尾巴。 */
internal fun icsExportWrittenMessage(decision: IcsExportDecision.WriteTruncating): String =
    if (decision.skippedOccurrences > 0) {
        "已导出 ${decision.eventCount} 个日程，可在系统日历中导入（${decision.skippedOccurrences} 个课次因节次时间缺失被跳过）"
    } else {
        "已导出 ${decision.eventCount} 个日程，可在系统日历中导入"
    }

/** 零日程那一档：实话是"没写出去"，不是"导出了 0 个"。 */
internal fun icsExportRefusedMessage(decision: IcsExportDecision.RefuseEmpty): String =
    if (decision.skippedOccurrences > 0) {
        "没有可导出的日程（${decision.skippedOccurrences} 个课次因节次时间缺失被跳过），未写出文件，你选中的文件没有被改动"
    } else {
        "这个学期排不出任何课次，没有可导出的日程，未写出文件，你选中的文件没有被改动"
    }

/** 条数报得出、导出件却是空的：那是导出器内部两格打架，不叫用户去修课表。 */
internal fun icsExportInconsistentMessage(decision: IcsExportDecision.RefuseInconsistent): String =
    "导出失败：算出 ${decision.eventCount} 个日程却没有导出内容，未写出文件，你选中的文件没有被改动"
