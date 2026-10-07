package com.buaa.schedule.domain.schedule

/**
 * 导出 ICS 那一站对用户选中的文件**动几手**（落盘模式两档，卡面不许把它折成一枚 Boolean）。
 *
 * [TRUNCATE_AND_WRITE] 是唯一会毁掉既有内容的那一档：`ContentResolver.openOutputStream(uri)`
 * 不给模式参数时走的是框架默认的截断写（`"w"`，事务档 `"wt"` 同样截断；只有 `"wta"` 才是追加），
 * **流一开，原文件内容当场没了** —— 后面那句 `write(text)` 哪怕写了 0 个字符，
 * 用户选中的那份文件也已经是 0 字节了。所以这一档只许跟着"真的有事可写"那一格出场。
 *
 * ⚠️ 本卡零设备：截断这一手是 ContentResolver 的公开契约 + 同文件 `exportWakeUpTo` 那段
 * 「实际可能写出一个空文件」的自证，不是装机读到的数（见回执的未验到清单）。
 */
enum class IcsTargetFileMutation { TRUNCATE_AND_WRITE, LEAVE_UNTOUCHED }

/**
 * 「这一次 ICS 导出该不该落盘 / 用哪种落盘模式 / 该念哪一句」那三件事的**同一本册子**（T252，#309）。
 *
 * 纯判据：零 android / androidx import、**不读时钟、不读时区、不读权限、不读设备度量** ——
 * 四枚事实（日程条数、被跳过的课次数、导出件文本、用户是否选中了文件）全部由调用点
 * （`ui/ScheduleViewModel.exportIcsTo`）当参数递进来，本文件里一个都不许自己去取。
 *
 * ## 病在哪
 *
 * 改前 `exportIcsTo` 的三行长这样：`exportIcs()` 先算完，`writeTextToUri(uriString, result.text)`
 * 再开流，最后一句 `append("已导出 ${result.eventCount} 个日程，可在系统日历中导入")`。
 * 条数为 0 时 `IcsExporter.export` 交回的是 `ExportResult("", 0, …)`，而 `writeTextToUri`
 * 拿默认模式开流 ⇒ **先把用户选中的文件截成 0 字节**，屏幕再把"零条"念成一句成功回执。
 * 也就是说：导出没内容这件事，被折成了对用户文件的一次破坏，而破坏完还报喜。
 *
 * 「0」这一格是**可达**的，不是理论分支：课程不为空、学期也设了，但
 * ① 每门课的周次全落在学期区间之外（T222 之后 `ScheduleOccurrences.build` 会整批裁掉），
 * 或 ② 每门课的节次都不在作息表里（那一格 `skipped++`，一条 VEVENT 都拼不出来）——
 * 两种都走到 `occurrences.isEmpty()` ⇒ `ExportResult("", 0, …)`。
 *
 * ## 四档为什么不并成两档
 *
 * 卡面第 ⓪ 问裁的是**甲档：零日程时完全不碰用户选中的那个文件**（理由见回执）。
 * 于是"不落盘"内部还剩两件必须分开说的事：
 * · [IcsExportDecision.RefuseEmpty] —— 条数就是 0，这是**课表排不出课**，用户能做的是回去补节次/周次；
 * · [IcsExportDecision.RefuseInconsistent] —— 条数报得出、导出件却是空的，这是**导出器自己两格打架**，
 *   用户做什么都没用。把它并进前者的文案，等于让用户去修一个没有坏的地方。
 * 再加上 [IcsExportDecision.NoFileSelected]（选择器取消）与 [IcsExportDecision.WriteTruncating]
 * （有事可写），合起来四档 —— 少任何一档，就有一格要借别人的文案说话。
 *
 * ## 判据顺序（顺序即语义）
 *
 * 1. 没选中文件 ⇒ [IcsExportDecision.NoFileSelected]，先于其它一切：这一等连"要不要写"都还没问出来。
 *    ⚠️ 今天**唯一**的调用点在设置页的 `CreateDocument` 回调里，`uri?.let { … }` 已经把 null 滤掉，
 *    所以这一档是替**第二枚调用点**兜的门，不是装机可达的活路（本卡不拿它去要设备证据）。
 * 2. 条数 ≤ 0 ⇒ [IcsExportDecision.RefuseEmpty]。**这一条排在文本判据之前**：
 *    导出器哪天改成交回一枚合法的空 `BEGIN/END VCALENDAR`（乙档那个形状），"有文本"就不再等于
 *    "有日程可写"，按文本判会又开始截用户的文件；按条数判才钉得住第 ⓪ 问的裁定。
 * 3. 条数 > 0 而文本空白 ⇒ [IcsExportDecision.RefuseInconsistent]。
 *    写出 0 字节正是本卡的病灶，所以这一等也不许开流，哪怕它"看起来有内容可数"。
 * 4. 其余 ⇒ [IcsExportDecision.WriteTruncating]，把导出件原样交回调用点去写。
 *
 * ## 证据档位
 *
 * 表驱动 JVM 单测 `IcsExportGateTest`（四档逐格 + 两侧的错误判据各有格子）与源码形状守卫
 * `IcsExportGateWiringGuardTest`（开流那一手只长在 WriteTruncating 支里、判空排在开流之前、
 * 改前那一形在代码里 0 处而只留在注释里）。文案本身在 `ui/settings/IcsExportCopy.kt`，
 * 同一枚测试文件逐句钉。
 */
sealed interface IcsExportDecision {

    /** 对用户选中那个文件的处置：四档里只许 [WriteTruncating] 交截断写。 */
    val mutation: IcsTargetFileMutation

    /**
     * 有事可写：导出件文本与条数都齐。[text] **只由这一档交回** —— 调用点想开流，
     * 就得先拿到这一档，于是"不落盘"那两支在代码里没有可写的正文可递。
     * 这不是编译器级别的锁（谁都能自己 new 一枚 WriteTruncating），钉住它的是
     * `IcsExportGateWiringGuardTest`：`exportIcsTo` 体内开流那一手恰好一枚、
     * 且长在 WriteTruncating 那一支的花括号里。
     */
    data class WriteTruncating(
        val text: String,
        val eventCount: Int,
        val skippedOccurrences: Int,
    ) : IcsExportDecision {
        override val mutation: IcsTargetFileMutation get() = IcsTargetFileMutation.TRUNCATE_AND_WRITE
    }

    /**
     * 一条日程都没有：不开流、不动用户选中的文件，把这句实话念出来。
     *
     * [skippedOccurrences] 原样留着：0 条而跳过数非零时，"为什么没有"正是用户唯一能动手的地方
     * （节次表里没这些课的时间），文案要说得出这一格。
     */
    data class RefuseEmpty(
        val skippedOccurrences: Int,
    ) : IcsExportDecision {
        override val mutation: IcsTargetFileMutation get() = IcsTargetFileMutation.LEAVE_UNTOUCHED
    }

    /**
     * 条数报得出、导出件却是空的：导出器内部两格打架（`eventCount` 与 `text` 不同源）。
     * 同样**不开流**，但句子必须与 [RefuseEmpty] 分开 —— 这一等不是"你的课表排不出课"。
     */
    data class RefuseInconsistent(
        val eventCount: Int,
    ) : IcsExportDecision {
        override val mutation: IcsTargetFileMutation get() = IcsTargetFileMutation.LEAVE_UNTOUCHED
    }

    /** 用户没选中任何文件（选择器取消）：不落盘，也一句不说 —— 没有失败可言。 */
    data object NoFileSelected : IcsExportDecision {
        override val mutation: IcsTargetFileMutation get() = IcsTargetFileMutation.LEAVE_UNTOUCHED
    }
}

/** [IcsExportGate] 的入口：把四枚已算好的事实折成一档处置。 */
object IcsExportGate {

    fun decide(
        eventCount: Int,
        skippedOccurrences: Int,
        icsText: String,
        fileSelected: Boolean,
    ): IcsExportDecision = when {
        !fileSelected -> IcsExportDecision.NoFileSelected
        eventCount <= 0 -> IcsExportDecision.RefuseEmpty(skippedOccurrences)
        icsText.isBlank() -> IcsExportDecision.RefuseInconsistent(eventCount)
        else -> IcsExportDecision.WriteTruncating(icsText, eventCount, skippedOccurrences)
    }
}
